package io.github.prithirajn.trackersync.sync

import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.source.Clipping
import io.github.prithirajn.trackersync.source.KoFile
import io.github.prithirajn.trackersync.source.MoonReaderClient

/**
 * KOReader highlights (from its sync plugins' files) put on the books they're from. A file named
 * by KOReader's document ID goes to the book with that ID (its own, a merged copy's, or a KOSync
 * document linked to it); one named after the book's file goes by title and author, then is
 * remembered. A highlight the book already has (the same passage from CrossPoint, say) isn't
 * added twice. Highlights edited in KOReader are updated, deleted ones go. Books aren't created
 * from highlights alone: files that match no book are listed so you can see them.
 */
object KoHighlightSync {

    data class Result(val snapshot: Snapshot, val added: Int, val books: Int, val unmatched: List<String>, val unmatchedPaths: List<String> = emptyList())

    /** The book a file is for, or null. */
    internal fun match(s: Snapshot, f: KoFile): Book? {
        s.koHlDocs[f.path]?.let { d -> s.books[d]?.let { return it } }
        f.documentId?.let { id ->
            s.books[id]?.let { return it }
            s.books.values.firstOrNull { id in it.aliases }?.let { return it }
            s.kosyncIds.entries.firstOrNull { id in it.value }?.let { e -> s.books[e.key]?.let { return it } }
        }
        val name = f.bookName ?: return null
        val books = s.books.values.filter { it.title != null }
        for ((title, author) in MoonReaderClient.guesses(name)) {
            val hits = books.filter { Matcher.sameBook(title, author, it.title!!, it.author) }
            (hits.firstOrNull { it.origin == null } ?: hits.firstOrNull())?.let { return it }
        }
        return null
    }

    private fun textKey(t: String) = t.lowercase().filter { it.isLetterOrDigit() }.take(120)

    fun apply(s: Snapshot, files: List<KoFile>): Result {
        val clips = ArrayList(s.koClips)
        val docs = HashMap(s.koHlDocs)
        val unmatched = ArrayList<String>()
        val unmatchedPaths = ArrayList<String>()
        var added = 0
        val touched = HashSet<String>()
        for (f in files) {
            val b = match(s.copy(koHlDocs = docs), f)
            if (b == null) {
                if (f.annotations.isNotEmpty()) unmatched += (f.bookName ?: f.documentId ?: f.path.substringAfterLast('/'))
                unmatchedPaths += f.path
                continue
            }
            docs[f.path] = b.document
            val prefix = "ko:" + Integer.toHexString(f.path.lowercase().hashCode()) + ":"
            val before = clips.filter { it.id.startsWith(prefix) }.associateBy { it.id }
            clips.removeAll { it.id.startsWith(prefix) }
            // Passages the book already has from elsewhere (CrossPoint, Moon+, paper…): not added again.
            val elsewhere = ((s.cpClips + s.paperClips + s.kindleClips + s.libbyClips + s.moonClips + s.historyClips)
                .filter { it.document == b.document } + clips.filter { it.document == b.document && !it.id.startsWith(prefix) })
                .map { textKey(it.text) }.toHashSet()
            for (a in f.annotations) {
                if (textKey(a.text) in elsewhere) continue
                val id = prefix + Integer.toHexString(a.key.hashCode())
                val old = before[id]
                clips += Clipping(
                    id = id, document = b.document, text = a.text, note = a.note, chapter = a.chapter,
                    createdAt = a.createdAt.takeIf { it > 0 } ?: old?.createdAt ?: (System.currentTimeMillis() / 1000),
                    spine = a.spine, percent = old?.percent, page = old?.page,
                )
                if (old == null) added++
            }
            touched += b.document
        }
        return Result(s.copy(koClips = clips, koHlDocs = docs, koHlUnmatched = unmatched.distinct()), added, touched.size, unmatched.distinct(), unmatchedPaths)
    }
}
