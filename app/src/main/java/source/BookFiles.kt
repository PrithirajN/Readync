package io.github.prithirajn.readync.source

import java.io.File

/**
 * Book files (EPUBs) kept by Readync itself, one per book: uploaded by you, or copied from KOReader
 * on another device. With a book's file here, a highlight or bookmark can be put on its exact words,
 * and a bookmark can show its page. Each is listed in the library's book files ("koBooks") as
 * "file:<document>.epub", with the file's own KOReader document ID (the same as the book's when it's
 * the very file on your e-reader).
 */
object BookFiles {
    /** Where they're kept (the app's own storage). Set by the app at start. */
    @Volatile var dir: File? = null

    const val PREFIX = "file:"

    fun key(document: String) = "$PREFIX$document.epub"

    fun documentOf(key: String): String? = key.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.removeSuffix(".epub")

    private fun file(key: String): File? {
        val d = dir ?: return null
        val name = key.removePrefix(PREFIX).takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,80}")) } ?: return null
        return File(File(d, "books"), name)
    }

    fun read(key: String): ByteArray? = file(key)?.takeIf { it.isFile }?.readBytes()

    /** Keeps [bytes] as [document]'s file; the entry for the library's book files ("modified|size|id"). */
    fun save(document: String, bytes: ByteArray): Pair<String, String> {
        val k = key(document)
        val f = file(k) ?: throw IllegalStateException("Storage isn't available")
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        val id = EpubText.partialMd5(bytes.size.toLong()) { o, n -> if (o >= bytes.size) null else bytes.copyOfRange(o.toInt(), minOf(bytes.size, o.toInt() + n)) }
        return k to "${f.lastModified()}|${bytes.size}|$id"
    }

    fun remove(key: String) { file(key)?.delete() }

    /** How many are kept and their total size in bytes. */
    fun usage(): Pair<Int, Long> {
        val files = dir?.let { File(it, "books").listFiles { f -> f.isFile && f.name.endsWith(".epub") } } ?: return 0 to 0L
        return files.size to files.sumOf { it.length() }
    }

    fun removeAll() { dir?.let { File(it, "books").listFiles()?.forEach { f -> f.delete() } } }
}
