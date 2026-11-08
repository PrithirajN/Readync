package io.github.prithirajn.trackersync.sync

import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.Link
import io.github.prithirajn.trackersync.data.LinkSource
import io.github.prithirajn.trackersync.data.Pending
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.source.Clipping
import io.github.prithirajn.trackersync.source.MoonPosition
import io.github.prithirajn.trackersync.source.MoonReaderClient
import io.github.prithirajn.trackersync.trackers.Trackers

/**
 * Moon+ Reader positions brought into the library. A book already here (matched by its file
 * name's title and author, then remembered) moves ahead when Moon+ is further along and newer,
 * and goes to your trackers like any other change. A book you've started in Moon+ that isn't here
 * yet is added, following the "Auto-sync new books" setting like books from anywhere else.
 */
object MoonSync {
    const val ORIGIN = "moonreader"

    data class Result(val snapshot: Snapshot, val moved: Int, val added: Int, val finished: Int, val highlights: Int = 0)

    /** Title and author guesses for a Moon+ file: the title from its highlights file first, then the file name's. */
    internal fun guesses(p: MoonPosition): List<Pair<String, String?>> {
        val fromName = MoonReaderClient.guesses(p.fileName)
        val t = p.title?.trim()?.takeIf { it.isNotEmpty() } ?: return fromName
        // The file name's author goes with the real title when the name had one.
        val author = fromName.firstOrNull { (title, _) -> Matcher.baseTitle(title) == Matcher.baseTitle(t) }?.second
            ?: fromName.firstOrNull()?.second?.takeIf { fromName.size > 1 && Matcher.baseTitle(fromName[1].first) == Matcher.baseTitle(t) }
        return listOf(t to author) + fromName
    }

    /** The book here that a Moon+ file is: remembered, else matched by title and author (CrossPoint's own copy first). */
    internal fun match(s: Snapshot, fileName: String, guesses: List<Pair<String, String?>> = MoonReaderClient.guesses(fileName)): Book? {
        s.moonDocs[fileName]?.let { d -> s.books[d]?.let { return it } }
        val books = s.books.values.filter { it.title != null }
        for ((title, author) in guesses) {
            val hits = books.filter { Matcher.sameBook(title, author, it.title!!, it.author) }
            (hits.firstOrNull { it.origin == null } ?: hits.firstOrNull())?.let { return it }
        }
        return null
    }

    fun apply(s: Snapshot, positions: List<MoonPosition>, now: Long = System.currentTimeMillis()): Result {
        val books = HashMap(s.books)
        val pending = HashMap(s.pending)
        val links = HashMap(s.links)
        val seen = HashMap(s.moonSeen)
        val docs = HashMap(s.moonDocs)
        val clips = ArrayList(s.moonClips)
        var newClips = 0
        var moved = 0
        var added = 0
        var finished = 0
        fun queue(doc: String) {
            for (t in Trackers.all) {
                val svc = s.service(t.id)
                if (!svc.linked || !svc.enabled) continue
                val key = Link.key(t.id, doc)
                if (links[key]?.source == LinkSource.NONE) continue
                pending[key] = pending[key]?.copy(tries = 0) ?: Pending(t.id, doc, now)
            }
        }
        /** A book's highlights as they are in Moon+ now: new ones added, edited ones updated, deleted ones gone. */
        fun takeHighlights(p: MoonPosition, doc: String) {
            val hs = p.highlights ?: return
            val prefix = "moon:" + Integer.toHexString(p.fileName.lowercase().hashCode()) + ":"
            val before = clips.filter { it.id.startsWith(prefix) }.associateBy { it.id }
            clips.removeAll { it.id.startsWith(prefix) }
            for (h in hs) {
                val id = prefix + h.id
                val old = before[id]
                clips += Clipping(
                    id = id, document = doc, text = h.text, note = h.note,
                    chapter = h.chapter?.let { "Chapter ${it + 1}" }, createdAt = h.createdAt / 1000,
                    percent = old?.percent, page = old?.page,
                )
                if (old == null) newClips++
            }
        }
        for (p in positions) {
            val g = guesses(p)
            val b = match(s.copy(books = books, moonDocs = docs), p.fileName, g)
            b?.let { takeHighlights(p, it.document) }
            if (seen[p.path] == p.savedAt) continue
            seen[p.path] = p.savedAt
            val sec = p.savedAt / 1000
            val done = p.fraction >= 0.995
            if (b != null) {
                docs[p.fileName] = b.document
                val ahead = kotlin.math.abs(p.fraction - b.percentage) > 0.0005 && sec > b.timestamp
                if (!ahead) continue
                val nowFinished = done && b.status != ReadStatus.FINISHED
                books[b.document] = b.copy(
                    percentage = p.fraction,
                    timestamp = sec,
                    currentPage = null,
                    status = when {
                        nowFinished -> ReadStatus.FINISHED
                        b.status == null || b.status == ReadStatus.TO_READ || b.status == ReadStatus.PAUSED -> ReadStatus.READING
                        else -> b.status
                    },
                    finishedAt = if (nowFinished) sec else b.finishedAt,
                    startedAt = b.startedAt ?: sec,
                )
                moved++
                if (nowFinished) finished++
                if (!b.held) queue(b.document)
                continue
            }
            // Not here yet: only books actually started in Moon+.
            if (p.fraction <= 0.0) continue
            val (title, author) = g.first()
            val doc = "$ORIGIN:" + Integer.toHexString(p.fileName.lowercase().hashCode())
            docs[p.fileName] = doc
            if (books.containsKey(doc) || s.removedBooks[doc]?.let { p.fraction <= it + 0.001 } == true) continue
            takeHighlights(p, doc)
            books[doc] = Book(
                document = doc,
                title = title,
                author = author,
                percentage = p.fraction,
                status = if (done) ReadStatus.FINISHED else ReadStatus.READING,
                timestamp = sec,
                origin = ORIGIN,
                startedAt = sec,
                finishedAt = if (done) sec else null,
            )
            added++
            if (s.settings.autoEnableNewBooks) queue(doc)
            else for (t in Trackers.all) {
                val key = Link.key(t.id, doc)
                if (links[key] == null) links[key] = Link(t.id, doc, null, source = LinkSource.NONE, note = "Not synced")
            }
        }
        return Result(s.copy(books = books, pending = pending, links = links, moonSeen = seen, moonDocs = docs, moonClips = clips), moved, added, finished, newClips)
    }
}
