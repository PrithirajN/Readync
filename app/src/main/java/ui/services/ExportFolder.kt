package io.github.prithirajn.readync.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.github.prithirajn.readync.sync.Exports

/**
 * Obsidian notes in the vault folder you picked (Android's folder access, so only that folder is
 * reachable): a book's note found by its name, read, and written whole.
 */
object ExportFolder {

    fun install(app: Context) {
        Exports.readNote = { folder, name -> runCatching { read(app, folder, name) }.getOrNull() }
        Exports.writeNote = { folder, name, text -> runCatching { write(app, folder, name, text) }.getOrDefault(false) }
    }

    /** The note called [name] directly in the folder, or null. */
    private fun find(context: Context, tree: Uri, name: String): Uri? {
        val parent = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1).equals(name, ignoreCase = true)) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
        }
        return null
    }

    private fun read(context: Context, folder: String, name: String): String? {
        val uri = find(context, Uri.parse(folder), name) ?: return null
        return context.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
    }

    private fun write(context: Context, folder: String, name: String, text: String): Boolean {
        val tree = Uri.parse(folder)
        val uri = find(context, tree, name) ?: DocumentsContract.createDocument(
            context.contentResolver,
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
            // A type with no extension of its own, so the name stays "….md" (not "….md.txt").
            "application/octet-stream", name,
        ) ?: return false
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: return false
        return true
    }
}
