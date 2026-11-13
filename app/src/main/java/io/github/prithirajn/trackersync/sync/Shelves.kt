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
     * Today's pages, book by book: how far each book with a page count moved since today began
     * (its share then, or where it stood when last seen yesterday), times its pages. So only real
     * reading counts: a book that arrives today (an import, a new sync, a copy merged in) starts
     * where it already is, unless you started it today; a page count set or changed adds nothing;
     * a book read again counts from where it went back to. Keeps two months of days.
     */
    fun recordPages(s: Snapshot, now: Long = System.currentTimeMillis()): Snapshot {
        val today = day(now)
        val sod = startOfDay(now)
        val newDay = s.pagesDay != today
        val firstRun = s.pagesStartF.isEmpty() && s.pagesLastF.isEmpty()
        val starts = HashMap<String, Double>()
        val last = HashMap<String, Double>()
        var read = 0.0
        for (b in s.books.values) {
            if (b.held) continue
            val pages = b.pageCount?.takeIf { it > 0 } ?: continue
            val f = when (b.status) {
                ReadStatus.FINISHED -> 1.0
                ReadStatus.TO_READ -> 0.0
                else -> b.percentage.coerceIn(0.0, 1.0)
            }
            // A book last moved before today (read yesterday, synced only now) starts the day where it is.
            var start = (if (newDay && b.timestamp >= sod) s.pagesLastF[b.document] else if (newDay) f else s.pagesStartF[b.document])
                // Not seen before: where it is now, unless you started it today (then from the start).
                ?: if (!firstRun && (b.startedAt ?: 0L) >= sod) 0.0 else f
            // Gone back (read again, or a position moved back): today counts from there.
            if (f < start) start = f
            read += (f - start) * pages
            starts[b.document] = start
            last[b.document] = f
        }
        val counted = Math.round(read).toInt()
        val todayPages = when {
            today in s.pagesCleared -> 0
            // The first count by this way: what was already counted today stands until something moves.
            firstRun -> maxOf(counted, s.pagesByDay[today] ?: 0)
            else -> counted
        }
        val days = (s.pagesByDay + (today to todayPages)).entries.sortedByDescending { it.key }.take(60).associate { it.key to it.value }
        return s.copy(
            pagesTotal = pagesRead(s.books.values), pagesDay = today, pagesByDay = days,
            pagesStartF = starts, pagesLastF = last,
            pagesCleared = s.pagesCleared.filter { it >= day(now - 62L * 86_400_000) }.toSet(),
        )
    }

    /** Today's pages as [recordPages] counts them, without saving anything (for the screens). */
    fun todayPages(s: Snapshot, now: Long = System.currentTimeMillis()): Int? {
        if (s.books.values.none { (it.pageCount ?: 0) > 0 }) return null
        return recordPages(s, now).pagesByDay[day(now)]
    }

    /** A day that wasn't reading (an import or a fix counted as pages): set to none, and kept that way. */
    fun clearDay(s: Snapshot, day: String): Snapshot =
        s.copy(pagesByDay = s.pagesByDay + (day to 0), pagesCleared = s.pagesCleared + day)

    /** A CrossPoint book that looks like another synced copy of itself, for the merge hint. */
    fun firstDuplicate(books: Collection<Book>): Book? {
        val own = books.filter { it.origin == null }.sortedByDescending { it.timestamp }
        return own.firstOrNull { b -> own.any { BookEditor.looksLikeSame(b, it) } }
    }
}
