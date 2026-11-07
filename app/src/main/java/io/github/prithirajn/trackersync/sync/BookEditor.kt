package io.github.prithirajn.trackersync.sync

import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.LinkSource
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.Store
import io.github.prithirajn.trackersync.net.ServiceException
import io.github.prithirajn.trackersync.source.BookDates
import io.github.prithirajn.trackersync.source.CoverCandidate
import io.github.prithirajn.trackersync.source.CrossPointClient
import io.github.prithirajn.trackersync.trackers.Trackers
import io.github.prithirajn.trackersync.util.int
import io.github.prithirajn.trackersync.util.str
import org.json.JSONObject
import java.text.Normalizer

/**
 * The companion app's book menu: fixes made on crosspoint-sync itself (so every app and
 * reader sees them), then mirrored on the phone right away. Blocking; call off the main thread.
 * Supports CrossPoint books, Kindle books, Seekquel books, and physical books.
 */
class BookEditor(private val store: Store) {

    private fun hasClient(): Boolean = store.snapshot.account != null

    /** A book that lives on crosspoint-sync: changes go there first, and its errors are shown. */
    private fun onServer(b: Book): Boolean = b.origin == null && hasClient()

    private fun client(): CrossPointClient =
        CrossPointClient(store.snapshot.account ?: throw ServiceException("Sign in to CrossPoint first"))

    private fun book(document: String): Book =
        store.snapshot.books[document] ?: throw ServiceException("That book isn't in your library any more")

    private fun putBook(b: Book) = store.update { s -> s.copy(books = s.books + (b.document to b)) }

    /** Queues the book for every linked tracker, as a sync of it would. */
    private fun queueEverywhere(document: String) {
        for (svc in store.snapshot.services.values.filter { it.linked && it.enabled }) {
            SyncEngine.queue(store, svc.id, listOf(document))
        }
    }

    /**
     * New title and author. The server looks the cover and details up again, and trackers
     * that couldn't find the book under the old title try again with the new one.
     */
    fun editDetails(document: String, title: String, author: String) {
        val b = book(document)
        val t = title.trim()
        val a = author.trim()
        if (t.isEmpty()) throw ServiceException("A title is needed")
        val patch = JSONObject()
        if (t != b.title) patch.put("title", t)
        if (a != (b.author ?: "")) patch.put("author", a)
        if (patch.length() == 0) return
        var savedCover: String? = b.coverUrl
        var finalTitle = t
        var finalAuthor: String? = a.ifEmpty { null }
        // CrossPoint's own books are changed on crosspoint-sync first; if it refuses, nothing changes
        // here (a change only on the phone would be undone by the next sync).
        if (onServer(b)) {
            val saved = client().setInfo(document, patch)
            finalTitle = saved.str("title") ?: t
            finalAuthor = saved.str("author") ?: a.ifEmpty { null }
            savedCover = saved.str("cover_url") ?: b.coverUrl
        }
        putBook(
            b.copy(
                title = finalTitle,
                author = finalAuthor,
                coverUrl = savedCover,
                description = null,
                descriptionSource = null,
                detailsFetched = false,
            )
        )
        // "Needs a match" placeholders came from the old title: match again.
        store.update { s ->
            s.copy(links = s.links.filterValues { !(it.document == document && it.externalId == null && it.source == LinkSource.AUTO) })
        }
        queueEverywhere(document)
    }

    fun coverCandidates(document: String, query: String?): List<CoverCandidate> {
        val b = store.snapshot.books[document]
        val q = query ?: b?.title ?: return emptyList()
        val list = ArrayList<CoverCandidate>()
        if (hasClient()) {
            list.addAll(runCatching { client().coverCandidates(document, query) }.getOrDefault(emptyList()))
        }
        val sqSvc = store.snapshot.service("seekquel")
        if (sqSvc.linked && !sqSvc.needsReauth) {
            runCatching {
                val session = io.github.prithirajn.trackersync.trackers.Session(sqSvc.credential)
                val hits = io.github.prithirajn.trackersync.trackers.SeekquelTracker.search(session, q, b?.author)
                for (h in hits) {
                    h.coverUrl?.let { url ->
                        if (list.none { it.url == url }) {
                            list.add(CoverCandidate(url, "Seekquel", h.title, h.pages))
                        }
                    }
                }
            }
        }
        val olCover = runCatching { io.github.prithirajn.trackersync.source.OpenLibrary.cover(q, b?.author) }.getOrNull()
        if (olCover != null && list.none { it.url == olCover }) {
            list.add(CoverCandidate(olCover, "Open Library", q, null))
        }
        return list
    }

    /** A chosen cover, or null to go back to the automatic one. */
    fun setCover(document: String, url: String?) {
        val b = book(document)
        var shown = url
        if (onServer(b)) {
            val c = client()
            val saved = c.setInfo(document, JSONObject().put("cover_url", url ?: JSONObject.NULL))
            // Back to automatic: the server's own cover for the book.
            shown = saved.str("cover_url") ?: url ?: runCatching { c.cover(document) }.getOrNull()
        } else if (url == null) {
            shown = runCatching { io.github.prithirajn.trackersync.source.OpenLibrary.cover(b.title ?: "", b.author) }.getOrNull()
        }
        putBook(b.copy(coverUrl = shown))
    }

    /** Print pages, or null to look the count up again. */
    fun setPages(document: String, pages: Int?) {
        val b = book(document)
        if (pages != null && pages !in 1..100_000) throw ServiceException("Enter the number of pages")
        if (onServer(b)) {
            client().setInfo(document, JSONObject().put("page_count", pages ?: JSONObject.NULL))
        }
        putBook(b.copy(pageCount = pages))
    }

    fun dates(document: String, tzOffsetMinutes: Int): BookDates? {
        val b = store.snapshot.books[document] ?: return null
        if (onServer(b)) return runCatching { client().dates(document, tzOffsetMinutes) }.getOrNull()
        return if (b.startedAt != null || b.finishedAt != null) BookDates(started = b.startedAt, finished = b.finishedAt, startManual = true, finishManual = true) else null
    }

    /**
     * Start and finish days as "YYYY-MM-DD". Only the keys present change; a null value hands
     * that date back to the reader's own. A finish date marks the book finished everywhere.
     */
    fun setDates(document: String, changes: Map<String, String?>) {
        val b = book(document)
        if (changes.isEmpty()) return
        val start = changes["start_date"]
        val finish = changes["finished_date"]
        if (start != null && finish != null && start > finish) throw ServiceException("The start date is after the finish date")
        val body = JSONObject()
        for ((k, v) in changes) body.put(k, v ?: JSONObject.NULL)
        var status = b.status
        if (onServer(b)) {
            val saved = client().setDates(document, body)
            saved.str("status")?.let { status = ReadStatus.fromCrossPoint(it) }
        }
        if (finish != null) status = ReadStatus.FINISHED
        // Kept on the phone too (the only copy for Kindle, Seekquel and physical books). A cleared
        // date on a CrossPoint book goes back to the reader's own, filled in on the next sync.
        val clearLocal = !onServer(b)
        val started = if ("start_date" in changes) start?.let { dayStart(it) } ?: if (clearLocal) null else b.startedAt else b.startedAt
        val finished = if ("finished_date" in changes) finish?.let { dayStart(it) } ?: if (clearLocal) null else b.finishedAt else b.finishedAt
        val nb = b.copy(status = status, startedAt = started, finishedAt = finished)
        putBook(nb)
        if (nb != b) queueEverywhere(document)
    }

    /** "2026-10-29" as unix seconds at the start of that day on this phone. */
    private fun dayStart(day: String): Long =
        java.time.LocalDate.parse(day).atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond()

    /**
     * Other books that look like this one synced twice: the same title, and the same author
     * when both have one (the companion app's rule).
     */
    fun likelyDuplicates(document: String): List<Book> {
        val b = store.snapshot.books[document] ?: return emptyList()
        return store.snapshot.books.values.filter { it.document != document && looksLikeSame(b, it) }
    }

    /**
     * Folds [duplicate] into [into] on the server. On the phone the duplicate's tracker
     * matches carry over where [into] has none, and the duplicate goes away.
     */
    fun merge(duplicate: String, into: String, stats: Boolean) {
        val target = book(into)
        val dup = book(duplicate)
        if (target.origin == null && dup.origin == null && hasClient()) {
            client().merge(duplicate, into, stats)
        }
        store.update { s ->
            val links = HashMap(s.links)
            for (t in Trackers.all) {
                val from = links.remove(io.github.prithirajn.trackersync.data.Link.key(t.id, duplicate)) ?: continue
                val key = io.github.prithirajn.trackersync.data.Link.key(t.id, into)
                if (links[key]?.externalId == null && from.externalId != null) links[key] = from.copy(document = into)
            }
            val merged = target.copy(aliases = (target.aliases + duplicate).distinct())
            s.copy(
                books = s.books - duplicate + (into to merged),
                links = links,
                pending = s.pending.filterValues { it.document != duplicate },
            )
        }
        queueEverywhere(into)
    }

    /** Undoes a merge; the copy shows up as its own book when its reader next syncs it. */
    fun separate(document: String, alias: String) {
        val b = book(document)
        if (onServer(b)) {
            client().unmerge(alias)
        }
        putBook(b.copy(aliases = b.aliases - alias))
    }

    /**
     * Removes the book from the library. If the user reads further from the point of
     * removal, it will be added back with the new progress; otherwise it stays removed.
     */
    fun remove(document: String) {
        val b = book(document)
        if (onServer(b)) {
            client().remove(document)
        }
        val pct = b.percentage
        store.update { s ->
            s.copy(
                books = s.books - document,
                links = s.links.filterValues { it.document != document },
                pending = s.pending.filterValues { it.document != document },
                removedBooks = s.removedBooks + (document to pct),
            )
        }
    }

    companion object {
        private val nonWord = Regex("""[^\p{L}\p{M}\p{N}]+""")

        /** As the companion app normalizes: NFKC, lowercase, anything but letters and digits to spaces. */
        fun norm(s: String?): String =
            nonWord.replace(Normalizer.normalize(s ?: "", Normalizer.Form.NFKC).lowercase(), " ").trim()

        fun looksLikeSame(a: Book, b: Book): Boolean {
            if (a.document == b.document) return false
            val t = norm(a.title)
            if (t.isEmpty() || t != norm(b.title)) return false
            return a.author.isNullOrBlank() || b.author.isNullOrBlank() || norm(a.author) == norm(b.author)
        }
    }
}
