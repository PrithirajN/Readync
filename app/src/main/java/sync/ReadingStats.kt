package io.github.prithirajn.readync.sync

import io.github.prithirajn.readync.data.Book
import io.github.prithirajn.readync.data.ReadStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A book's reading in numbers, for its page: pages read of its print pages, how long it's been
 * going (or took), the pace per day, and when it'll be done at that pace.
 */
data class ReadingStats(
    val pagesRead: Int?,
    val pageCount: Int?,
    val started: LocalDate?,
    val finished: LocalDate?,
    /** Days reading so far (counting the first day), or days it took once finished. */
    val days: Int?,
    /** Pages a day, when there's enough to say. */
    val pagesPerDay: Double?,
    /** The day it'll be finished at this pace, for a book being read. */
    val eta: LocalDate?,
) {
    val isEmpty: Boolean get() = pagesRead == null && started == null && finished == null

    companion object {
        fun of(book: Book, nowSec: Long = System.currentTimeMillis() / 1000, zone: ZoneId = ZoneId.systemDefault()): ReadingStats {
            fun day(sec: Long?) = sec?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalDate() }
            val pages = book.pageCount?.takeIf { it > 0 }
            val read = (book.currentPage ?: book.pagesRead ?: pages?.let { Math.round(book.percentage * it).toInt() })
                ?.let { r -> if (pages != null) r.coerceIn(0, pages) else r.coerceAtLeast(0) }
            val started = day(book.startedAt)
            val finished = if (book.status == ReadStatus.FINISHED) day(book.finishedAt) else null
            val end = finished ?: day(nowSec)
            val days = if (started != null && end != null && !end.isBefore(started)) {
                (java.time.temporal.ChronoUnit.DAYS.between(started, end) + 1).toInt()
            } else null
            val pace = if (days != null && read != null && read > 0 && (days >= 2 || finished != null)) read.toDouble() / days else null
            val eta = if (book.status == ReadStatus.READING && pace != null && pace >= 0.5 && pages != null && read != null && read < pages) {
                val left = Math.ceil((pages - read) / pace).toLong()
                day(nowSec)?.plusDays(left)?.takeIf { left <= 3650 }
            } else null
            return ReadingStats(read, pages, started, finished, days, pace, eta)
        }
    }
}
