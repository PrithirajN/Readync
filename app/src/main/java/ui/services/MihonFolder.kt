package io.github.prithirajn.readync.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Reads Mihon's newest automatic backup from the folder you chose (Android's folder access, so
 * only that folder is reachable). Looks in an "autobackup" folder inside it too, which is where
 * Mihon puts them when you pick its main folder.
 */
object MihonFolder {

    fun install(app: Context) {
        io.github.prithirajn.readync.source.MihonBackup.reader = { folder -> newest(app, folder) }
    }

    private data class Doc(val id: String, val name: String, val modified: Long, val dir: Boolean)

    private fun list(context: Context, tree: Uri, parentId: String): List<Doc> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val out = ArrayList<Doc>()
        context.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += Doc(c.getString(0), c.getString(1) ?: "", c.getLong(2), c.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR)
            }
        }
        return out
    }

    private fun isBackup(name: String) = name.endsWith(".tachibk", true) || name.endsWith(".proto.gz", true)

    fun newest(context: Context, folder: String): Triple<String, Long, ByteArray>? {
        val tree = Uri.parse(folder)
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        var docs = list(context, tree, rootId)
        if (docs.none { isBackup(it.name) }) {
            docs.firstOrNull { it.dir && it.name.equals("autobackup", true) }?.let { docs = list(context, tree, it.id) }
        }
        val pick = docs.filter { !it.dir && isBackup(it.name) }.maxByOrNull { it.modified } ?: return null
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, pick.id)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        return Triple(pick.name, pick.modified / 1000, bytes)
    }
}
