package io.github.prithirajn.readync.sync

import io.github.prithirajn.readync.data.Book
import io.github.prithirajn.readync.data.ReadStatus

/**
 * Jumps aren't reading. Opening the contents, following a link or checking the endnotes moves your
 * position far ahead for a moment, and as trackers are never moved back on their own, that jump
 * would stay as your progress there. So a big move forward is held (the book here shows it; the
 * trackers keep where you were) until a later position near it says you're reading there. Back
 * before it, and it was a jump: dropped. (After ShelfSync 1.8.4, which sends no jump either.)
 *
 * Held as "jumped to;when;from" (fractions and unix seconds) in the snapshot's extras.
 */
object Jumps {
    /** A move forward at least this big (a tenth of the book) waits... */
    const val BIG = 0.10
    /** ...and needs at least this many print pages to count, so a short book's chapter isn't one. */
    const val PAGES = 25

    fun key(document: String) = "jump.$document"

    sealed interface Decision
    /** Held: nothing goes to trackers yet. */
    data class Hold(val saved: String, val note: String) : Decision
    /** Let go: the position goes out as usual. */
    data class Release(val note: String?) : Decision

    private data class Held(val to: Double, val at: Long, val from: Double)

    private fun parse(saved: String?): Held? = saved?.split(';')?.takeIf { it.size == 3 }?.let {
        val to = it[0].toDoubleOrNull() ?: return null
        val at = it[1].toLongOrNull() ?: return null
        val from = it[2].toDoubleOrNull() ?: return null
        Held(to, at, from)
    }

    /** Where you were before a held jump, as a whole percent; null with none held. */
    fun before(saved: String?): Int? = parse(saved)?.let { Math.round(it.from * 100).toInt() }

    private fun pct(f: Double) = "${Math.round(f * 100)}%"

    /**
     * What to do with a new position [to] (at [at]) for a book that was [old]. [saved] is a jump held
     * for it. Null: nothing to do with jumps.
     */
    fun decide(old: Book?, saved: String?, to: Double, at: Long, status: ReadStatus?): Decision? {
        val held = parse(saved)
        if (held != null) {
            if (at <= held.at) return null
            return when {
                // Reading on from there (or near it): it was where you went to read. Sent now.
                to >= held.to - 0.02 && to <= held.to + BIG -> Release("reading on from ${pct(held.to)}, so it's sent now")
                // Further ahead again: still a jump until confirmed.
                to > held.to + BIG -> Hold("$to;$at;${held.from}", "jumped again to ${pct(to)}; sent once you read on from there")
                // Back before it: it was only a look. Your real place goes out.
                else -> Release("back to ${pct(to)}, so the jump to ${pct(held.to)} wasn't sent")
            }
        }
        val from = old?.percentage ?: return null
        if (old.status != ReadStatus.READING && old.status != ReadStatus.PAUSED) return null
        // A finish is said by marking the book read; a jump to the last pages (the endnotes) is held too.
        if (status == ReadStatus.FINISHED) return null
        val pages = old.pageCount ?: 0
        if (to - from < BIG || (pages > 0 && (to - from) * pages < PAGES)) return null
        return Hold("$to;$at;$from", "jumped from ${pct(from)} to ${pct(to)}; sent once you read on from there")
    }
}
