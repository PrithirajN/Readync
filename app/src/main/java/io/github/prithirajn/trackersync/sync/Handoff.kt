package io.github.prithirajn.trackersync.sync

import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.LinkSource
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.data.Store
import io.github.prithirajn.trackersync.net.ServiceException
import io.github.prithirajn.trackersync.trackers.RemoteBook

/**
 * A physical book you've started on your CrossPoint reader: offered to continue there. Accepting
 * moves the physical book's highlights, notes, review, rating, dates and tracker matches onto
 * the reader's copy, keeps the furthest position, and removes the physical entry.
 */
object Handoff {

    /** Pairs each physical book with the one CrossPoint book that's clearly the same, unless you kept them apart. */
    fun find(store: Store) = store.update { s -> s.copy(handoffs = pairs(s)) }

    fun pairs(s: Snapshot): Map<String, String> {
        val reader = s.books.values.filter { it.origin == null && it.title != null }
        val out = HashMap<String, String>()
        for (m in s.books.values.filter { (ManualBooks.isManual(it) || it.origin == "kindle") && it.title != null }) {
            val same = reader.filter { c ->
                Matcher.sameBook(m.title!!, m.author, c.title!!, c.author) ||
                    Matcher.score(m.title, m.author, RemoteBook(c.document, c.title, c.author)) >= 0.8
            }
            val c = same.singleOrNull() ?: continue
            if ("${m.document}|${c.document}" in s.handoffDenied) continue
            out[m.document] = c.document
        }
        return out
    }

    fun accept(store: Store, physical: String, now: Long = System.currentTimeMillis()) {
        val sec = now / 1000
        var target: String? = null
        store.update { s ->
            val cpDoc = s.handoffs[physical] ?: throw ServiceException("Nothing to move")
            val m = s.books[physical] ?: throw ServiceException("That book isn't in your library any more")
            val c = s.books[cpDoc] ?: throw ServiceException("The reader's copy isn't in your library any more")
            target = cpDoc
            // The furthest position wins; the physical one holds against CrossPoint's older reports.
            val physicalAhead = m.percentage > c.percentage + 0.0049
            val pages = c.pageCount ?: m.pageCount
            var merged: Book = c.copy(
                pageCount = pages,
                rating = c.rating ?: m.rating,
                review = c.review ?: m.review,
                reviewAsked = c.reviewAsked || m.reviewAsked,
                startedAt = listOfNotNull(c.startedAt, m.startedAt).minOrNull(),
                finishedAt = c.finishedAt ?: m.finishedAt,
                coverUrl = c.coverUrl ?: m.coverUrl,
                description = c.description ?: m.description,
                descriptionSource = if (c.description != null) c.descriptionSource else m.descriptionSource,
            )
            if (physicalAhead) {
                val stamp = maxOf(sec, c.timestamp + 1)
                merged = merged.copy(
                    percentage = m.percentage,
                    status = if (m.status == ReadStatus.FINISHED) ReadStatus.FINISHED else c.status ?: m.status,
                    currentPage = m.currentPage ?: pages?.let { Math.round(m.percentage * it).toInt() },
                    timestamp = stamp,
                    manualAt = stamp,
                )
            }
            // Tracker matches move over, so the reader's copy doesn't have to be matched again.
            val links = HashMap(s.links)
            for (l in s.links.values.filter { it.document == physical }) {
                links.remove(l.key)
                if (l.externalId == null || l.source == LinkSource.NONE) continue
                val existing = links[io.github.prithirajn.trackersync.data.Link.key(l.service, cpDoc)]
                if (existing == null || existing.externalId == null) {
                    val moved = l.copy(document = cpDoc)
                    links[moved.key] = moved
                }
            }
            fun <V> move(map: Map<String, V>): Map<String, V> =
                map[physical]?.let { v -> (map - physical).let { if (cpDoc in it) it else it + (cpDoc to v) } } ?: map
            s.copy(
                books = s.books - physical + (cpDoc to merged),
                links = links,
                pending = s.pending.filterValues { it.document != physical },
                paperClips = s.paperClips.map { if (it.document == physical) it.copy(document = cpDoc) else it },
                kindleClips = s.kindleClips.map { if (it.document == physical) it.copy(document = cpDoc) else it },
                libbyClips = s.libbyClips.map { if (it.document == physical) it.copy(document = cpDoc) else it },
                moonClips = s.moonClips.map { if (it.document == physical) it.copy(document = cpDoc) else it },
                seekquelRatings = move(s.seekquelRatings),
                seekquelDates = move(s.seekquelDates),
                progressAsks = s.progressAsks - physical,
                progressDenied = s.progressDenied - physical,
                handoffs = s.handoffs - physical,
            )
        }
        target?.let { ManualBooks.queueEverywhere(store, it) }
    }

    /** Keep both: not offered again for this pair. */
    fun keepBoth(store: Store, physical: String) = store.update { s ->
        val cpDoc = s.handoffs[physical] ?: return@update s
        s.copy(handoffs = s.handoffs - physical, handoffDenied = s.handoffDenied + "$physical|$cpDoc")
    }
}
