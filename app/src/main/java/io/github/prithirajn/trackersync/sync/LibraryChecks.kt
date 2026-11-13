package io.github.prithirajn.trackersync.sync

import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.Snapshot

/**
 * Books whose details would make your numbers wrong: finished with no finish date (so they count in
 * no year), dates the wrong way round or in the future, finished books with no page count (no pages
 * in your totals), possible duplicates (the same book twice counts twice), and books you're reading
 * with no start date. Each with what to fix; nothing is changed by itself.
 */
object LibraryChecks {

    enum class Kind(val title: String, val why: String) {
        NO_FINISH_DATE("Finished, but no finish date", "They count in no year or month (Wrapped, \"so far\", your goal). Add the date under ⋯ › Reading dates."),
        BACKWARDS("Finished before it was started", "The start date is after the finish date, so reading time and pace come out wrong."),
        FUTURE("Dates in the future", "A start or finish date later than today."),
        NO_PAGES("Finished, but no page count", "These add no pages to your totals. Set the page count under ⋯ › Print pages."),
        DUPLICATES("Possibly the same book twice", "Each copy counts once in your numbers. Merge them under ⋯ › Merge a duplicate if they are."),
        NO_START("Reading, with no start date", "Pace and \"days reading\" need a start date."),
    }

    data class Issue(val kind: Kind, val books: List<Book>, val detail: Map<String, String> = emptyMap())

    /**
     * A comic issue or a manga volume, however it came in (from Seekquel, Hardcover…, not only the
     * manga log): "#3" or "Vol. 7" in the title, or tagged comics, manga or graphic novel. These have
     * no page counts to speak of, so they aren't asked for one.
     */
    fun comicOrManga(s: Snapshot, b: Book): Boolean {
        if (b.isMangaOrComic) return true
        if (Regex("(?i)#\\s*\\d+\\b|\\bvol(?:ume)?\\.?\\s*\\d+\\b|\\bissue\\s*\\d+\\b").containsMatchIn(b.displayTitle)) return true
        return s.facts[b.document]?.genres.orEmpty().any { g -> Regex("(?i)comic|manga|manhwa|manhua|graphic novel").containsMatchIn(g) }
    }

    fun run(s: Snapshot, now: Long = System.currentTimeMillis() / 1000): List<Issue> {
        val books = s.books.values.filter { !it.isMangaOrComic || it.status == ReadStatus.FINISHED }
        val day = 86_400L
        val out = ArrayList<Issue>()
        fun date(t: Long) = java.time.Instant.ofEpochSecond(t).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()

        val finished = books.filter { it.status == ReadStatus.FINISHED }
        finished.filter { (it.finishedAt ?: 0L) <= 0L }.takeIf { it.isNotEmpty() }?.let {
            out += Issue(Kind.NO_FINISH_DATE, it.sortedBy { b -> b.displayTitle.lowercase() })
        }
        books.filter { b -> val st = b.startedAt ?: 0; val fi = b.finishedAt ?: 0; st > 0 && fi > 0 && st - fi > day }
            .takeIf { it.isNotEmpty() }?.let { list ->
                out += Issue(Kind.BACKWARDS, list, list.associate { it.document to "started ${date(it.startedAt!!)}, finished ${date(it.finishedAt!!)}" })
            }
        books.filter { b -> (b.startedAt ?: 0) > now + day || (b.finishedAt ?: 0) > now + day }
            .takeIf { it.isNotEmpty() }?.let { list ->
                out += Issue(Kind.FUTURE, list, list.associate { b -> b.document to listOfNotNull(b.startedAt?.takeIf { it > now + day }?.let { "started ${date(it)}" }, b.finishedAt?.takeIf { it > now + day }?.let { "finished ${date(it)}" }).joinToString(", ") })
            }
        finished.filter { !comicOrManga(s, it) && (it.pageCount ?: 0) <= 0 }.takeIf { it.isNotEmpty() }?.let {
            out += Issue(Kind.NO_PAGES, it.sortedBy { b -> b.displayTitle.lowercase() })
        }
        // The same title and author more than once (merged copies are already one book). Issue and
        // volume numbers count: "Darkhawk #1" and "#2" are two books, not one twice.
        val groups = books.filter { it.title != null }
            .groupBy { Matcher.baseTitle(it.displayTitle) + "|" + (Matcher.volume(it.displayTitle) ?: "") + "|" + (it.author?.let { a -> Matcher.normalizeAuthor(a) } ?: "") }
            .values.filter { g -> g.size > 1 && Matcher.baseTitle(g.first().displayTitle).length >= 3 }
        if (groups.isNotEmpty()) {
            val list = groups.flatten()
            out += Issue(Kind.DUPLICATES, list, list.associate { b -> b.document to listOfNotNull(b.status?.label, b.origin?.let { "from $it" }).joinToString(" · ") })
        }
        books.filter { it.status == ReadStatus.READING && (it.startedAt ?: 0L) <= 0L }.takeIf { it.isNotEmpty() }?.let {
            out += Issue(Kind.NO_START, it)
        }
        return out
    }

    fun count(issues: List<Issue>): Int = issues.sumOf { it.books.size }
}
