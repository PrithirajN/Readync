package io.github.prithirajn.trackersync.sync

import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.Snapshot

/** The Library's status tabs and numbers, as the companion app's library page has them. */
object Shelves {
    val tabs = listOf(ReadStatus.READING, ReadStatus.PAUSED, ReadStatus.FINISHED, ReadStatus.DNF)

    /** The tab a book sits under. A book with no status is being read; Want to Read has no tab. */
    fun tabOf(b: Book): ReadStatus? = when (b.status) {
        null, ReadStatus.READING -> ReadStatus.READING
        ReadStatus.TO_READ -> null
        else -> b.status
    }

    fun counts(books: Collection<Book>): Map<ReadStatus, Int> =
        tabs.associateWith { t -> books.count { tabOf(it) == t } }

    enum class Sort(val label: String) { RECENT("Recent"), TITLE("Title"), AUTHOR("Author"), PROGRESS("Progress") }

    /**
     * A tab's books. Recent is newest first by what the tab is about (when it was finished for
     * Finished, the last sync otherwise); [query] matches title or author, any word order.
     */
    fun shown(books: Collection<Book>, tab: ReadStatus, sort: Sort = Sort.RECENT, query: String = ""): List<Book> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val on = books.filter { b ->
            tabOf(b) == tab && words.all { w ->
                (b.title ?: "").lowercase().contains(w) || (b.author ?: "").lowercase().contains(w)
            }
        }
        return when (sort) {
            Sort.RECENT -> if (tab == ReadStatus.FINISHED) on.sortedByDescending { it.finishedAt ?: it.timestamp }
                else on.sortedByDescending { it.timestamp }
            Sort.TITLE -> on.sortedBy { (it.title ?: "").lowercase().removePrefix("the ").removePrefix("a ") }
            Sort.AUTHOR -> on.sortedWith(compareBy({ (it.author ?: "\uFFFF").lowercase() }, { (it.title ?: "").lowercase() }))
            Sort.PROGRESS -> on.sortedByDescending { it.percentage }
        }
    }

    /**
     * Print pages read in one book: crosspoint-sync's own count, else the page you're on (a
     * physical book, or progress set by page), else progress × page count (all of it when
     * finished). Null when the book has no page count. Want to Read counts nothing.
     */
    fun pagesOf(b: Book): Int? {
        if (b.status == ReadStatus.TO_READ) return 0
        b.pagesRead?.let { return it }
        val pages = b.pageCount ?: return null
        if (b.status == ReadStatus.FINISHED) return pages
        b.currentPage?.let { return it.coerceIn(0, pages) }
        return Math.round(b.percentage.coerceIn(0.0, 1.0) * pages).toInt()
    }

    /** "2026-10" for [now] in this phone's time zone. */
    fun month(now: Long = System.currentTimeMillis()): String = day(now).take(7)

    /** "October" for [now]. */
    fun monthName(now: Long = System.currentTimeMillis()): String =
        java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).month
            .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())

    /**
     * Pages read this month: the days counted so far this month ([Snapshot.pagesByDay]), with
     * today's live count ([today]) in place of today's saved one. Only reading done while this
     * app was counting is in it; past reading brought in from a tracker's history isn't.
     */
    fun pagesThisMonth(s: Snapshot, today: Int?, now: Long = System.currentTimeMillis()): Int {
        val m = month(now)
        val d = day(now)
        val earlier = s.pagesByDay.filterKeys { it.startsWith(m) && it != d }.values.sum()
        return earlier + (today ?: s.pagesByDay[d] ?: 0)
    }

    /**
     * Days in a row with pages read, up to today (today counts once it has pages; until then the
     * streak still stands from yesterday). [today] is today's live count, when known.
     */
    fun streak(s: Snapshot, today: Int?, now: Long = System.currentTimeMillis()): Int {
        val zone = java.time.ZoneId.systemDefault()
        var d = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun pages(day: java.time.LocalDate): Int =
            if (day.toString() == day(now)) (today ?: s.pagesByDay[day.toString()] ?: 0) else (s.pagesByDay[day.toString()] ?: 0)
        if (pages(d) <= 0) d = d.minusDays(1)
        var n = 0
        while (pages(d) > 0 && n < 3650) { n++; d = d.minusDays(1) }
        return n
    }

    /** Books finished this month (by their finish date, else when they were last read). */
    fun finishedThisMonth(books: Collection<Book>, now: Long = System.currentTimeMillis()): Int {
        val m = month(now)
        return books.count { b ->
            b.status == ReadStatus.FINISHED &&
                (b.finishedAt ?: b.timestamp).let { t -> t > 0 && month(t * 1000) == m }
        }
    }

    /** Print pages read across the library, every book with a page count; null when none has one. */
    fun pagesRead(books: Collection<Book>): Int? {
        val known = books.mapNotNull { pagesOf(it) }
        return if (known.isEmpty()) null else known.sum()
    }

    /** "2026-10-28" for [now] in this phone's time zone: the day pages are counted under. */
    fun day(now: Long = System.currentTimeMillis()): String =
        java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()

    fun startOfDay(now: Long = System.currentTimeMillis()): Long {
        val zone = java.time.ZoneId.systemDefault()
        return java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toEpochSecond()
    }

    /** True if this book was finished or last read on a date prior to today. */
    fun isBeforeToday(b: Book, startOfDay: Long): Boolean {
        if (b.held) return true
        if (b.status == ReadStatus.FINISHED) {
            val finished = b.finishedAt ?: b.timestamp
            return finished < startOfDay
        }
        return b.timestamp < startOfDay
    }

    /** Print pages read across all books whose reading activity was before today. */
    fun pagesBeforeToday(books: Collection<Book>, now: Long = System.currentTimeMillis()): Int {
        val start = startOfDay(now)
        return books.filter { isBeforeToday(it, start) }.mapNotNull { pagesOf(it) }.sum()
    }

    /**
     * Records the library's total at [now]: the first total seen each day is that day's
     * starting point (yesterday's last total, when known), so today's pages are the difference.
     * Historical imports and books read before today automatically raise the starting baseline.
     * Keeps two months of days.
     */
    fun recordPages(s: Snapshot, now: Long = System.currentTimeMillis()): Snapshot {
        val total = pagesRead(s.books.values) ?: return s
        val today = day(now)
        val beforeToday = pagesBeforeToday(s.books.values, now)
        val dayStart = if (s.pagesDay == today) s.pagesDayStart else (s.pagesTotal ?: total)
        val start = maxOf(dayStart, beforeToday)
        val read = (total - start).coerceAtLeast(0)
        val days = (s.pagesByDay + (today to read)).entries.sortedByDescending { it.key }.take(60).associate { it.key to it.value }
        return s.copy(pagesTotal = total, pagesDay = today, pagesDayStart = start, pagesByDay = days)
    }

    /** A CrossPoint book that looks like another synced copy of itself, for the merge hint. */
    fun firstDuplicate(books: Collection<Book>): Book? {
        val own = books.filter { it.origin == null }.sortedByDescending { it.timestamp }
        return own.firstOrNull { b -> own.any { BookEditor.looksLikeSame(b, it) } }
    }
}
