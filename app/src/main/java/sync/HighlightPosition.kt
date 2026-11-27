package io.github.prithirajn.readync.sync

import io.github.prithirajn.readync.data.Book
import io.github.prithirajn.readync.data.PositionPoint
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.source.Change
import io.github.prithirajn.readync.source.Clipping

/**
 * Where a highlight sits in its book. crosspoint-sync gives a highlight's chapter (spine index)
 * but not its percentage, so it's worked out from the positions the reader reported: a position
 * in the same chapter (the one nearest in time to the highlight), else between the chapters
 * either side, else the position reported closest to when it was made. Physical-book
 * highlights carry the page you were on.
 */
object HighlightPosition {
    private const val KEEP = 400
    private const val NEAR_SECONDS = 6 * 3600L

    /** Adds the reported positions to each book's log, oldest dropped past [KEEP]. */
    fun record(s: Snapshot, changes: Collection<Change>): Snapshot {
        if (changes.isEmpty()) return s
        val out = HashMap(s.positions)
        for ((doc, cs) in changes.groupBy { it.document }) {
            val pts = (out[doc] ?: emptyList()) + cs.map { PositionPoint(it.timestamp, it.percentage.coerceIn(0.0, 1.0), it.spine) }
            out[doc] = pts.distinctBy { it.timestamp to it.percentage }.sortedBy { it.timestamp }.takeLast(KEEP)
        }
        return s.copy(positions = out)
    }

    /** Best guess (0–1) for [clip], or null when there's nothing to go on. */
    fun estimate(clip: Clipping, points: List<PositionPoint>): Double? {
        clip.percent?.let { return it }
        val sp = clip.spine
        if (sp != null) {
            val same = points.filter { it.spine == sp }
            if (same.isNotEmpty()) return same.minByOrNull { kotlin.math.abs(it.timestamp - clip.createdAt) }!!.percentage
            val below = points.filter { it.spine != null && it.spine < sp }.maxByOrNull { it.percentage }
            val above = points.filter { it.spine != null && it.spine > sp }.minByOrNull { it.percentage }
            if (below != null && above != null && above.spine!! > below.spine!! && above.percentage >= below.percentage) {
                val f = (sp - below.spine).toDouble() / (above.spine - below.spine)
                return below.percentage + (above.percentage - below.percentage) * f
            }
        }
        if (clip.createdAt > 0) {
            // The first position reported after it was made (the page it's on), else the last before.
            points.filter { it.timestamp >= clip.createdAt && it.timestamp - clip.createdAt <= NEAR_SECONDS }.minByOrNull { it.timestamp }
                ?.let { return it.percentage }
            points.filter { it.timestamp < clip.createdAt && clip.createdAt - it.timestamp <= NEAR_SECONDS }.maxByOrNull { it.timestamp }
                ?.let { return it.percentage }
        }
        return null
    }

    /**
     * CrossPoint's highlights with their place in the book: kept from before, else worked out
     * now (and kept, so it survives the position log moving on).
     */
    fun place(s: Snapshot, fresh: List<Clipping>): List<Clipping> {
        val before = s.cpClips.associateBy { it.id }
        return fresh.map { c ->
            val known = before[c.id]?.percent
            if (known != null) c.copy(percent = known)
            else c.copy(percent = estimate(c, s.positions[c.document] ?: emptyList()))
        }
    }

    /** The highlight's print page, when its place and the book's page count are known. */
    fun withPage(c: Clipping, book: Book?): Clipping {
        val pct = c.percent ?: return c
        val pages = book?.pageCount ?: return c
        return c.copy(page = Math.round(pct * pages).toInt().coerceIn(1, pages))
    }

    /** "12%" or "p. 54 (12%)" for showing where a highlight is. */
    fun label(c: Clipping): String? {
        val pct = c.percent ?: return null
        val p = Math.round(pct * 100).toInt()
        return c.page?.let { "p. $it ($p%)" } ?: "$p%"
    }
}
