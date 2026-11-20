package io.github.prithirajn.readync.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import io.github.prithirajn.readync.data.Book
import io.github.prithirajn.readync.data.ReadStatus
import io.github.prithirajn.readync.data.Release
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.sync.Highlights
import io.github.prithirajn.readync.sync.Insights
import io.github.prithirajn.readync.sync.LibraryChecks
import io.github.prithirajn.readync.sync.ReadingStats
import io.github.prithirajn.readync.sync.Shelves
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Everything the Activity tab shows, worked out once, off the main thread, whenever your data
 * changes. The sections are list items that are dropped when they scroll away and made again when
 * they come back; each used to work its own numbers out on the main thread as it arrived (the
 * stutter while scrolling). Now they only draw what's here.
 */
@Immutable
internal class ActivityData(
    val todayPages: Int?,
    val week: Insights.Week,
    val streak: Int,
    /** This month's pages, books finished and highlights marked. */
    val month: Triple<Int, Int, Int>,
    val needs: List<Insights.Need>,
    val rhythm: Rhythm?,
    val heatStart: LocalDate,
    val heat: Map<LocalDate, Int>,
    val heatLevels: List<Int>,
    val year: Insights.Year,
    val issues: List<LibraryChecks.Issue>,
    val collectionLine: String,
    val reviewLeft: Int,
    val gut: List<Triple<Book, Double, Double>>,
    val releasesOut: List<Release>,
    val releasesComing: List<Release>,
) {
    companion object {
        const val HEAT_WEEKS = 18

        fun of(context: Context, s: Snapshot, today: LocalDate = LocalDate.now()): ActivityData {
            val todayPages = runCatching { Shelves.todayPages(s) }.getOrNull()
            val week = Insights.week(s, today, todayPages)
            val streak = runCatching { Shelves.streak(s, todayPages) }.getOrDefault(0)
            val m = Shelves.month()
            val month = Triple(
                runCatching { Shelves.pagesThisMonth(s, todayPages) }.getOrDefault(0),
                runCatching { Shelves.finishedThisMonth(s.books.values) }.getOrDefault(0),
                runCatching { Highlights.all(s).count { it.clip.createdAt > 0 && Shelves.month(it.clip.createdAt * 1000) == m } }.getOrDefault(0),
            )
            val start = today.minusDays((today.dayOfWeek.value - 1).toLong()).minusWeeks((HEAT_WEEKS - 1).toLong())
            val all = runCatching { Shelves.allDays(s) }.getOrDefault(emptyMap())
            val rhythm = runCatching { Rhythm.of(s, all, today) }.getOrNull()
            val heat = (0 until HEAT_WEEKS * 7).map { start.plusDays(it.toLong()) }.associateWith { all[it.toString()] ?: 0 }
            val nonzero = heat.values.filter { it > 0 }.sorted()
            val levels = listOf(0.25, 0.5, 0.75).map { q -> nonzero.getOrElse((nonzero.size * q).toInt()) { nonzero.lastOrNull() ?: 1 } }
            val gut = if (s.rubric.isEmpty()) emptyList() else s.books.values.mapNotNull { b ->
                val r = b.rating ?: return@mapNotNull null
                val avg = s.rubric.mapNotNull { s.rubricScores[b.document]?.get(it) }.takeIf { it.isNotEmpty() }?.average() ?: return@mapNotNull null
                Triple(b, r * 2, avg)
            }
            val now = System.currentTimeMillis() / 1000
            return ActivityData(
                todayPages = todayPages,
                week = week,
                streak = streak,
                month = month,
                needs = runCatching { Insights.needs(s) }.getOrDefault(emptyList()),
                rhythm = rhythm,
                heatStart = start,
                heat = heat,
                heatLevels = levels,
                year = Insights.year(s, today),
                issues = runCatching { LibraryChecks.run(s) }.getOrDefault(emptyList()),
                collectionLine = if (CollectionHooks.available) runCatching { CollectionHooks.summary(s) }.getOrDefault("") else "",
                reviewLeft = runCatching { DailyReview.left(context, s) }.getOrDefault(0),
                gut = gut,
                releasesOut = s.releases.values.filter { it.isOut(now) }.sortedByDescending { it.date ?: it.seenAt },
                releasesComing = s.releases.values.filter { !it.isOut(now) }.sortedBy { it.date ?: Long.MAX_VALUE },
            )
        }
    }
}

/**
 * How you read, rather than what: the weekdays you read most (the last twelve weeks), your best day
 * and longest run ever, and what a reading day usually holds (the last ninety days).
 */
@Immutable
internal class Rhythm(
    /** Average pages each weekday, Monday first, over the last twelve weeks. */
    val weekdays: List<Double>,
    /** The weekday with the most, when one stands out. */
    val bigDay: java.time.DayOfWeek?,
    val best: Pair<LocalDate, Int>?,
    val longest: Int,
    /** Average pages on a day you read (last 90 days). */
    val perDay: Int,
    /** Average minutes on a day with minutes kept (last 90 days), when there are any. */
    val minutes: Int?,
    /** Share of the last 90 days you read on, 0–100. */
    val share: Int,
) {
    companion object {
        fun of(s: Snapshot, all: Map<String, Int>, today: LocalDate): Rhythm? {
            val days = all.mapNotNull { (k, v) -> runCatching { LocalDate.parse(k) }.getOrNull()?.takeIf { v > 0 && !it.isAfter(today) }?.let { it to v } }.toMap()
            if (days.size < 5) return null
            // Weekdays: the last 12 whole-or-part weeks, each weekday averaged over the times it came round.
            val from = today.minusDays(83)
            val sums = DoubleArray(7); val counts = IntArray(7)
            var d = from
            while (!d.isAfter(today)) { val i = d.dayOfWeek.value - 1; sums[i] += (days[d] ?: 0).toDouble(); counts[i]++; d = d.plusDays(1) }
            val weekdays = (0 until 7).map { if (counts[it] == 0) 0.0 else sums[it] / counts[it] }
            val top = weekdays.withIndex().maxByOrNull { it.value }
            val mean = weekdays.average()
            val bigDay = top?.takeIf { it.value > 0 && it.value >= mean * 1.25 }?.let { java.time.DayOfWeek.of(it.index + 1) }
            val best = days.maxByOrNull { it.value }?.toPair()
            // Longest run of days in a row, ever.
            var longest = 0; var run = 0; var prev: LocalDate? = null
            for (day in days.keys.sorted()) { run = if (prev != null && day == prev.plusDays(1)) run + 1 else 1; longest = maxOf(longest, run); prev = day }
            val recent = days.filterKeys { !it.isBefore(today.minusDays(89)) }
            val perDay = if (recent.isEmpty()) 0 else Math.round(recent.values.average()).toInt()
            val mins = HashMap<String, Int>()
            for (book in s.readingDays.values) for ((k, v) in book) { val m = v.minutes ?: 0; if (m > 0) mins[k] = (mins[k] ?: 0) + m }
            val recentMins = mins.filterKeys { k -> runCatching { !LocalDate.parse(k).isBefore(today.minusDays(89)) }.getOrDefault(false) }.values
            val minutes = recentMins.takeIf { it.size >= 3 }?.average()?.let { Math.round(it).toInt() }
            return Rhythm(weekdays, bigDay, best, longest, perDay, minutes, recent.size * 100 / 90)
        }
    }
}

/**
 * The Activity tab's data, kept up to date in the background. Null only until the first time it's
 * ready (a moment, once); after that a change shows the last numbers until the new ones are in.
 */
@Composable
internal fun rememberActivityData(state: Snapshot, refresh: Int): ActivityData? {
    val context = LocalContext.current.applicationContext
    return produceState<ActivityData?>(Cache.last?.takeIf { it.first === state && it.second == refresh }?.third, state, refresh) {
        val ready = Cache.last?.takeIf { it.first === state && it.second == refresh }?.third
        if (ready != null) { value = ready; return@produceState }
        val d = withContext(Dispatchers.Default) { runCatching { ActivityData.of(context, state) }.getOrNull() }
        if (d != null) { Cache.last = Triple(state, refresh, d); value = d }
    }.value ?: Cache.last?.third
}

/** The last worked-out data, so coming back to the tab shows it at once instead of working again. */
private object Cache {
    @Volatile var last: Triple<Snapshot, Int, ActivityData>? = null
}
