package io.github.prithirajn.trackersync.sync

import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.Link
import io.github.prithirajn.trackersync.data.LinkSource
import io.github.prithirajn.trackersync.data.Pending
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.source.Clipping
import io.github.prithirajn.trackersync.source.KoFile
import io.github.prithirajn.trackersync.source.KoPosition
import io.github.prithirajn.trackersync.source.MoonReaderClient
import io.github.prithirajn.trackersync.trackers.Trackers

/**
 * KOReader highlights (from its sync plugins' files) put on the books they're from. A file named
 * by KOReader's document ID goes to the book with that ID (its own, a merged copy's, or a KOSync
 * document linked to it); one named after the book's file goes by title and author, then is
 * remembered. A highlight the book already has (the same passage from CrossPoint, say) isn't
 * added twice. Highlights edited in KOReader are updated, deleted ones go. A book that isn't here
 * but whose name is known is added with its highlights, held back from trackers until you turn it
 * on; files that can't be named are listed so you can see them.
 */
object KoHighlightSync {

    data class Result(val snapshot: Snapshot, val added: Int, val books: Int, val unmatched: List<String>, val unmatchedPaths: List<String> = emptyList(), val created: Int = 0)

    /**
     * The IDs a file's book can have: KOReader's own (by content), and the one "Filename" matching
     * gives, from the book's exact file name when known.
     */
    internal fun idsOf(f: KoFile): List<String> =
        listOfNotNull(f.documentId, f.fileName?.let { KoPlacement.fileId(it) }).distinct()

    /** The book a file is for, or null. */
    internal fun match(s: Snapshot, f: KoFile): Book? {
        s.koHlDocs[f.path]?.let { d -> s.books[d]?.let { return it } }
        for (id in idsOf(f)) {
            s.books[id]?.let { return it }
            s.books.values.firstOrNull { id in it.aliases }?.let { return it }
            s.kosyncIds.entries.firstOrNull { id in it.value }?.let { e -> s.books[e.key]?.let { return it } }
        }
        val name = f.bookName ?: idsOf(f).firstNotNullOfOrNull { titleOf(s, it) }?.let { (t, a) -> if (a != null) "$t - $a" else t } ?: return null
        s.books[ORIGIN + ":" + Integer.toHexString(name.lowercase().hashCode())]?.let { return it }
        val books = s.books.values.filter { it.title != null }
        for ((title, author) in MoonReaderClient.guesses(name).map { (t, a) -> tidyTitle(t) to a }) {
            val hits = books.filter { Matcher.sameBook(title, author, it.title!!, it.author) }
            (hits.firstOrNull { it.origin == null } ?: hits.firstOrNull())?.let { return it }
        }
        return null
    }

    private fun textKey(t: String) = t.lowercase().filter { it.isLetterOrDigit() }.take(120)

    const val ORIGIN = "koreader"

    private val hex32 = Regex("[0-9a-f]{32}")

    /** "Seven Year Slip, The" -> "The Seven Year Slip". */
    internal fun tidyTitle(t: String): String {
        val m = Regex("""^(.*),\s*(The|A|An)$""", RegexOption.IGNORE_CASE).find(t.trim()) ?: return t.trim()
        return m.groupValues[2] + " " + m.groupValues[1].trim()
    }

    /** A KOReader book's title and author, known from its EPUB or sidecar here. */
    fun titleOf(s: Snapshot, documentId: String?): Pair<String, String?>? {
        val v = documentId?.let { s.koTitles[it] } ?: return null
        val title = v.substringBefore('\t').trim().takeIf { it.isNotEmpty() } ?: return null
        return title to v.substringAfter('\t', "").trim().takeIf { it.isNotEmpty() }
    }

    /** What to call a file that matched no book: its title when known, else null (only its ID is). */
    fun nameOf(s: Snapshot, documentIdOrName: String): String? =
        if (hex32.matches(documentIdOrName)) titleOf(s, documentIdOrName)?.first else documentIdOrName

    /** Books read in KOReader (positions from AnnotationSync) brought in, and titles given to books that have none. */
    data class Moved(val snapshot: Snapshot, val moved: Int, val added: Int, val unmatchedPaths: List<String>)

    /**
     * Where KOReader is in each book. A book already here (by document ID, a linked KOSync
     * document, or its file name) moves ahead when KOReader is further along and newer, and goes
     * to your trackers like any other change. One that isn't here is added when its title is known
     * (its EPUB or sidecar is in KOReader's folder on this device, or the file is named after the
     * book), following "Auto-sync new books". Books here without a title get KOReader's.
     */
    fun applyPositions(s: Snapshot, positions: List<KoPosition>, now: Long = System.currentTimeMillis()): Moved {
        val books = HashMap(s.books)
        val pending = HashMap(s.pending)
        val links = HashMap(s.links)
        val pagesOf = HashMap(s.koPages)
        var moved = 0
        var added = 0
        val unmatched = ArrayList<String>()
        /** KOReader's page count for the book, from where it is (page 592 at 98.2% -> 603). */
        fun learnPages(doc: String, p: KoPosition) {
            val page = p.page ?: return
            if (p.percentage < 0.05) return
            pagesOf[doc] = Math.round(page / p.percentage).toInt().coerceAtLeast(page)
        }
        fun queue(doc: String) {
            for (t in Trackers.all) {
                val svc = s.service(t.id)
                if (!svc.linked || !svc.enabled) continue
                val key = Link.key(t.id, doc)
                if (links[key]?.source == LinkSource.NONE) continue
                pending[key] = pending[key]?.copy(tries = 0) ?: Pending(t.id, doc, now)
            }
        }
        // Untitled books (a CrossPoint document with no title) named from KOReader's copy.
        for ((doc, b) in s.books) {
            if (b.title != null) continue
            titleOf(s, doc)?.let { (t, a) -> books[doc] = b.copy(title = tidyTitle(t), author = b.author ?: a) }
        }
        for (p in positions) {
            val asFile = KoFile(p.path, p.documentId, p.bookName, emptyList(), fileName = p.fileName)
            val b = match(s.copy(books = books), asFile)
            if (b != null) learnPages(b.document, p)
            if (b != null) {
                val ahead = kotlin.math.abs(p.percentage - b.percentage) > 0.0005 && p.timestamp > b.timestamp
                if (!ahead) continue
                val done = p.percentage >= 0.995
                val nowFinished = done && b.status != ReadStatus.FINISHED
                books[b.document] = b.copy(
                    percentage = p.percentage, timestamp = p.timestamp, currentPage = null,
                    status = when {
                        nowFinished -> ReadStatus.FINISHED
                        b.status == null || b.status == ReadStatus.TO_READ || b.status == ReadStatus.PAUSED -> ReadStatus.READING
                        else -> b.status
                    },
                    finishedAt = if (nowFinished) p.timestamp else b.finishedAt,
                    startedAt = b.startedAt ?: p.timestamp,
                )
                moved++
                if (!b.held) queue(b.document)
                continue
            }
            if (p.percentage <= 0.0) continue
            val ids = idsOf(asFile)
            val named = ids.firstNotNullOfOrNull { titleOf(s, it) }
                ?: p.bookName?.let { n -> MoonReaderClient.guesses(n).firstOrNull()?.let { (t, a) -> t to a } }
            if (named == null) { unmatched += p.path; continue }
            // Kept under the ID a reader would sync it by (content, else file name), so the same book from CrossPoint joins it.
            val doc = ids.firstOrNull() ?: (ORIGIN + ":" + Integer.toHexString(p.bookName!!.lowercase().hashCode()))
            if (books.containsKey(doc) || s.removedBooks[doc]?.let { p.percentage <= it + 0.001 } == true) continue
            learnPages(doc, p)
            val done = p.percentage >= 0.995
            books[doc] = Book(
                document = doc,
                title = tidyTitle(named.first),
                author = named.second,
                percentage = p.percentage,
                status = if (done) ReadStatus.FINISHED else ReadStatus.READING,
                timestamp = p.timestamp,
                origin = ORIGIN,
                startedAt = p.timestamp,
                finishedAt = if (done) p.timestamp else null,
            )
            added++
            if (s.settings.autoEnableNewBooks) queue(doc)
            else for (t in Trackers.all) {
                val key = Link.key(t.id, doc)
                if (links[key] == null) links[key] = Link(t.id, doc, null, source = LinkSource.NONE, note = "Not synced")
            }
        }
        return Moved(s.copy(books = books, pending = pending, links = links, koPages = pagesOf), moved, added, unmatched)
    }

    /** A note KOReader keeps on a bookmark (no passage): kept here, not sent to trackers as a quote. */
    fun isNote(id: String) = id.startsWith("ko:") && id.substringAfterLast(':').startsWith("n")

    /**
     * Where KOReader's page [pageno] is in the book (0–1), from how many pages KOReader lays it out
     * in ([pages]). Null when either isn't known.
     */
    internal fun placeOf(pageno: Int?, pages: Int?): Double? {
        if (pageno == null || pages == null || pages <= 0) return null
        return (pageno.toDouble() / pages).coerceIn(0.0, 1.0)
    }

    fun apply(s: Snapshot, files: List<KoFile>): Result {
        val clips = ArrayList(s.koClips)
        val docs = HashMap(s.koHlDocs)
        val pagesOf = HashMap(s.koPages)
        val unmatched = ArrayList<String>()
        val unmatchedPaths = ArrayList<String>()
        var added = 0
        val touched = HashSet<String>()
        val books = HashMap(s.books)
        var created = 0
        for (f in files) {
            var b = match(s.copy(koHlDocs = docs, books = books), f)
            // Not here, but named (from its EPUB, KOReader's file, the file's own name, or names you gave):
            // added with its highlights, held back from trackers until you turn it on (where you are isn't known).
            if (b == null && f.annotations.any { !it.bookmark }) {
                // A name for sure only: a known title, or the book's exact file name (not HighlightSync's altered one,
                // which may be a book here under another title; that waits to be matched).
                val named = idsOf(f).firstNotNullOfOrNull { titleOf(s, it) }
                    ?: f.fileName?.let { n -> MoonReaderClient.guesses(n).firstOrNull() }
                val doc = idsOf(f).firstOrNull() ?: f.fileName?.let { ORIGIN + ":" + Integer.toHexString(it.lowercase().hashCode()) }
                if (named != null && doc != null && !books.containsKey(doc) && !s.removedBooks.containsKey(doc)) {
                    val last = f.annotations.maxOfOrNull { it.updatedAt ?: it.createdAt }?.takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000)
                    val first = f.annotations.minOfOrNull { it.createdAt }?.takeIf { it > 0 }
                    b = Book(
                        document = doc, title = tidyTitle(named.first), author = named.second,
                        percentage = 0.0, status = ReadStatus.PAUSED, timestamp = last,
                        origin = ORIGIN, startedAt = first, held = true, reviewAsked = true,
                    )
                    books[doc] = b
                    created++
                }
            }
            if (b == null) {
                if (f.annotations.isNotEmpty()) unmatched += (f.bookName ?: f.documentId ?: f.path.substringAfterLast('/'))
                unmatchedPaths += f.path
                continue
            }
            docs[f.path] = b.document
            f.pages?.let { pagesOf[b.document] = it }
            val pages = pagesOf[b.document]
            val prefix = "ko:" + Integer.toHexString(f.path.lowercase().hashCode()) + ":"
            val before = clips.filter { it.id.startsWith(prefix) }.associateBy { it.id }
            clips.removeAll { it.id.startsWith(prefix) }
            // Passages the book already has from elsewhere (CrossPoint, Moon+, paper…): not added again.
            val elsewhere = ((s.cpClips + s.paperClips + s.kindleClips + s.libbyClips + s.moonClips + s.historyClips)
                .filter { it.document == b.document } + clips.filter { it.document == b.document && !it.id.startsWith(prefix) })
                .map { textKey(it.text) }.toHashSet()
            for (a in f.annotations) {
                if (!a.bookmark && textKey(a.text) in elsewhere) continue
                val id = prefix + (if (a.bookmark) "n" else "") + Integer.toHexString(a.key.hashCode())
                val old = before[id]
                clips += Clipping(
                    id = id, document = b.document, text = a.text, note = a.note, chapter = a.chapter,
                    createdAt = a.createdAt.takeIf { it > 0 } ?: old?.createdAt ?: (System.currentTimeMillis() / 1000),
                    // Its own place in the book, from KOReader's page; the print page is worked out from it when sending.
                    spine = a.spine, percent = placeOf(a.pageno, pages) ?: old?.percent, page = old?.page,
                )
                if (old == null) added++
            }
            touched += b.document
        }
        return Result(s.copy(books = books, koClips = clips, koHlDocs = docs, koHlUnmatched = unmatched.distinct(), koPages = pagesOf), added, touched.size, unmatched.distinct(), unmatchedPaths, created)
    }
}
