package io.github.prithirajn.readync.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.github.prithirajn.readync.source.KoSidecar

/**
 * Reads KOReader's sidecar files from a folder on this device you picked (Android's folder
 * access, so only that folder is reachable): your books folder, where KOReader keeps a ".sdr"
 * folder next to each book, or koreader/docsettings or koreader/hashdocsettings when it keeps
 * them there. Only files that changed since the last sync are read.
 */
object KoSidecarFolder {

    fun install(app: Context) {
        KoSidecar.reader = { tree, known, seen -> read(app, tree, known, seen) }
        KoSidecar.bookId = { tree, path -> runCatching { bookId(app, tree, path) }.getOrNull() }
        KoSidecar.bookBytes = { tree, path -> runCatching { bookBytes(app, tree, path) }.getOrNull() }
    }

    private data class Doc(val id: String, val name: String, val modified: Long, val dir: Boolean, val size: Long = 0L)

    /** Where each book file was found on the last walk ("local:/Books/x.epub" -> its document id in the tree). */
    private val found = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun list(context: Context, tree: Uri, parentId: String): List<Doc> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val out = ArrayList<Doc>()
        context.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) out += Doc(c.getString(0), c.getString(1) ?: "", c.getLong(2), c.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR, if (c.isNull(4)) 0L else c.getLong(4))
        }
        return out
    }

    /** A sidecar's settings file ("metadata.epub.lua"), not its backups ("….lua.old"). */
    private fun isSidecar(name: String) = name.startsWith("metadata.", true) && name.endsWith(".lua", true)

    fun read(context: Context, folder: String, known: Map<String, String>, seen: MutableMap<String, String>): List<KoSidecar.Raw> {
        val tree = Uri.parse(folder)
        val out = ArrayList<KoSidecar.Raw>()
        val books = HashMap<String, String>()
        // Breadth first, a few levels down; ".sdr" folders are opened, other folders searched.
        var level = listOf(DocumentsContract.getTreeDocumentId(tree) to "")
        var visited = 0
        for (round in 0 until 7) {
            val next = ArrayList<Pair<String, String>>()
            for ((id, path) in level) {
                val docs = runCatching { list(context, tree, id) }.getOrDefault(emptyList())
                for (d in docs) {
                    val p = "$path/${d.name}"
                    if (d.dir) {
                        if (++visited < 5000) next += d.id to p
                    } else if (d.name.endsWith(".epub", true)) {
                        books["local:$p"] = "${d.modified}|${d.size}"
                        found["local:$p"] = d.id
                    } else if (isSidecar(d.name)) {
                        val key = "local:$p"
                        val stamp = d.modified.toString()
                        if (known[key] == stamp) { seen[key] = stamp; continue }
                        val text = runCatching {
                            context.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, d.id))?.use { String(it.readBytes(), Charsets.UTF_8) }
                        }.getOrNull() ?: continue
                        seen[key] = stamp
                        out += KoSidecar.Raw(key, text, d.modified)
                    }
                }
            }
            if (next.isEmpty()) break
            level = next
        }
        KoSidecar.books = books
        return out
    }

    private fun uriOf(context: Context, folder: String, path: String): Uri? {
        val tree = Uri.parse(folder)
        found[path]?.let { return DocumentsContract.buildDocumentUriUsingTree(tree, it) }
        // Not walked yet this run: follow the path one folder at a time.
        var id = DocumentsContract.getTreeDocumentId(tree)
        for (part in path.removePrefix("local:").split('/').filter { it.isNotEmpty() }) {
            id = list(context, tree, id).firstOrNull { it.name == part }?.id ?: return null
        }
        found[path] = id
        return DocumentsContract.buildDocumentUriUsingTree(tree, id)
    }

    /** KOReader's document ID for a book file: the MD5 of 1 KB samples at set places. */
    private fun bookId(context: Context, folder: String, path: String): String? {
        val uri = uriOf(context, folder, path) ?: return null
        return context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                io.github.prithirajn.readync.source.EpubText.partialMd5(ch.size()) { offset, length ->
                    val buf = java.nio.ByteBuffer.allocate(length)
                    ch.position(offset)
                    while (buf.hasRemaining() && ch.read(buf) > 0) {}
                    buf.array().copyOf(buf.position())
                }
            }
        }
    }

    private fun bookBytes(context: Context, folder: String, path: String): ByteArray? {
        val uri = uriOf(context, folder, path) ?: return null
        return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }
}
