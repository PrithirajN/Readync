package io.github.prithirajn.trackersync.sync

import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.LinkSource
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.data.Store

/**
 * Two books here that look like one (the same title and author arriving under two IDs: KOReader's
 * copy and CrossPoint's, a file downloaded again, a book renamed on one device). Nothing is joined
 * by itself: each pair asks "Join" or "Keep apart", and a pair kept apart isn't asked about again.
 *
 * Also: books that came in but aren't going to your trackers yet (made from a reader's highlights,
 * or added while new books start turned off), so they can be turned on together.
 */
object SameBook {

    data class Match(val keep: Book, val other: Book) {
        val key: String get() = keyOf(keep.document, other.document)
    }

    fun keyOf(a: String, b: String) = if (a < b) "$a|$b" else "$b|$a"

    /** Which of two copies stays: CrossPoint's, then one already going to trackers, then the further along, then the older. */
    fun order(a: Book, b: Book): Match {
        val rank = compareBy<Book>({ if (it.origin == null) 0 else 1 }, { if (it.held) 1 else 0 }, { -it.percentage }, { it.timestamp })
        return if (rank.compare(a, b) <= 0) Match(a, b) else Match(b, a)
    }

    private fun same(a: Book, b: Book): Boolean {
        if (a.isMangaOrComic || b.isMangaOrComic) return false
        if (a.document in b.aliases || b.document in a.aliases) return false
        // Different issue or volume numbers ("#1", "#2"): different books.
        if (Matcher.volume(a.displayTitle) != Matcher.volume(b.displayTitle)) return false
        if (BookEditor.looksLikeSame(a, b)) return true
        // A subtitle or series tag on one copy only ("Title: A Novel", "Title (Book 2)"), with the same author.
        val ta = Matcher.baseTitle(a.displayTitle)
        if (ta.length < 4 || ta != Matcher.baseTitle(b.displayTitle)) return false
        val aa = a.author?.let { Matcher.normalizeAuthor(it) }.orEmpty()
        val ab = b.author?.let { Matcher.normalizeAuthor(it) }.orEmpty()
        return aa.isNotEmpty() && aa == ab
    }

    /** The pairs to ask about, newest first. */
    fun pairs(s: Snapshot): List<Match> {
        val books = s.books.values.filter { it.title != null && !it.isMangaOrComic && !ManualBooks.isManual(it) }
        val out = ArrayList<Match>()
        for (group in books.groupBy { Matcher.baseTitle(it.displayTitle).take(12) }.values) {
            if (group.size < 2) continue
            for (i in group.indices) for (j in i + 1 until group.size) {
                val a = group[i]; val b = group[j]
                if (keyOf(a.document, b.document) in s.keptApart || !same(a, b)) continue
                out += order(a, b)
            }
        }
        return out.sortedByDescending { maxOf(it.keep.timestamp, it.other.timestamp) }
    }

    /** The pair a book is in, for its page. */
    fun pairFor(s: Snapshot, document: String): Match? = pairs(s).firstOrNull { it.keep.document == document || it.other.document == document }

    fun keepApart(store: Store, a: String, b: String) = store.update { it.copy(keptApart = it.keptApart + keyOf(a, b)) }

    /**
     * Books that came in but don't go to your trackers yet: held (made from a reader's highlights
     * or another reader's library), or with every tracker turned off because new books start off.
     */
    fun waiting(s: Snapshot): List<Book> {
        val trackers = s.services.values.filter { it.linked && it.enabled }.map { it.id }
        if (trackers.isEmpty()) return emptyList()
        return s.books.values.filter { b ->
            if (b.isMangaOrComic || ManualBooks.isManual(b)) return@filter false
            // From a tracker's own history: those are chosen one by one, on purpose.
            if (b.held) return@filter b.origin == KoHighlightSync.ORIGIN || b.origin == "kindle" || b.origin == "moonreader" || b.origin == "libby"
            val mine = trackers.filter { it != b.origin }
            mine.isNotEmpty() && mine.all { t -> s.link(t, b.document)?.let { it.source == LinkSource.NONE && it.note == "Not synced" } == true }
        }.sortedByDescending { it.timestamp }
    }
}
