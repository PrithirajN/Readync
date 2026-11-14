package io.github.prithirajn.trackersync.sync

import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.LinkSource
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.trackers.Trackers
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the Activity page shows about your reading, worked out from what the app already keeps:
 * this week's pages, the year's books against a goal, what happened lately, and what needs you.
 */
object Insights {

    // ---- This week ----

    /** The last seven days of pages (oldest first, today last), and the seven before for comparison. */
    data class Week(
        val days: List<Pair<LocalDate, Int>>,
        val total: Int,
        val previousTotal: Int,
        val best: Pair<LocalDate, Int>?,
        val readingDays: Int,
    ) {
        /** +25 for a quarter more than the week before; null when there's nothing to compare. */
        val changePercent: Int? get() = if (previousTotal <= 0) null else Math.round((total - previousTotal) * 100.0 / previousTotal).toInt()
    }

    fun week(s: Snapshot, today: LocalDate, todayPages: Int? = null): Week {
        fun pages(d: LocalDate): Int =
            if (d == today) (todayPages ?: s.pagesByDay[d.toString()] ?: 0) else (s.pagesByDay[d.toString()] ?: 0)
        val days = (6 downTo 0).map { today.minusDays(it.toLong()) }.map { it to pages(it) }
        val prev = (13 downTo 7).sumOf { pages(today.minusDays(it.toLong())) }
        val best = days.filter { it.second > 0 }.maxByOrNull { it.second }
        return Week(days, days.sumOf { it.second }, prev, best, days.count { it.second > 0 })
    }

    // ---- The year ----

    data class Year(
        val year: Int,
        val finished: List<Book>,
        val pages: Int,
        val goal: Int,
        /** Books you'd have finished by today at an even pace through the year. */
        val expectedByNow: Double,
        /** Books set aside this year, counted when you've chosen to (see DnfPrefs). */
        val setAside: List<Book> = emptyList(),
    ) {
        /** Books toward the year: finished, plus set aside when they count. */
        val count: Int get() = finished.size + setAside.size
        /** Books ahead (+) or behind (−) an even pace; null without a goal. */
        val ahead: Int? get() = if (goal <= 0) null else Math.round(count - expectedByNow).toInt()
    }

    fun year(s: Snapshot, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Year {
        val y = today.year
        val finished = s.books.values.filter { b ->
            // Books brought in from a tracker's history or an export count too (held only means they aren't sent anywhere).
            b.status == ReadStatus.FINISHED &&
                (b.finishedAt ?: b.timestamp).let { t -> t > 0 && Instant.ofEpochSecond(t).atZone(zone).year == y }
        }.sortedByDescending { it.finishedAt ?: it.timestamp }
        // Books set aside this year, when you've chosen to count them (their pages as far as you got).
        val setAside = if (!io.github.prithirajn.trackersync.ui.DnfPrefs.counts(s)) emptyList() else s.books.values.filter { b ->
            b.status == ReadStatus.DNF && b.timestamp > 0 && Instant.ofEpochSecond(b.timestamp).atZone(zone).year == y
        }
        val pages = finished.sumOf { it.pageCount ?: 0 } + setAside.sumOf { b -> ((b.pageCount ?: 0) * b.percent / 100.0).toInt() }
        val goal = s.settings.yearGoal
        val dayOfYear = today.dayOfYear.toDouble()
        val daysInYear = today.lengthOfYear().toDouble()
        return Year(y, finished, pages, goal, if (goal > 0) goal * dayOfYear / daysInYear else 0.0, setAside)
    }

    // ---- What happened lately ----

    enum class Kind { STARTED, FINISHED, HIGHLIGHTED, DNF }

    /** "Started Feral Gods", "Finished X", "3 highlights in Y", one per book per day. */
    data class Event(val day: LocalDate, val kind: Kind, val document: String, val count: Int = 1, val at: Long)

    fun timeline(s: Snapshot, today: LocalDate, days: Int = 30, zone: ZoneId = ZoneId.systemDefault()): List<Event> {
        val since = today.minusDays(days.toLong() - 1)
        fun day(sec: Long?) = sec?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalDate() }
        val out = ArrayList<Event>()
        for (b in s.books.values) {
            if (b.held) continue
            day(b.startedAt)?.takeIf { !it.isBefore(since) && !it.isAfter(today) }?.let { out += Event(it, Kind.STARTED, b.document, at = b.startedAt!!) }
            if (b.status == ReadStatus.FINISHED) {
                day(b.finishedAt)?.takeIf { !it.isBefore(since) && !it.isAfter(today) }?.let { out += Event(it, Kind.FINISHED, b.document, at = b.finishedAt!!) }
            }
            if (b.status == ReadStatus.DNF) {
                day(b.timestamp)?.takeIf { !it.isBefore(since) && !it.isAfter(today) }?.let { out += Event(it, Kind.DNF, b.document, at = b.timestamp) }
            }
        }
        val clips = (s.cpClips + s.paperClips + s.kindleClips + s.libbyClips + s.moonClips + s.historyClips + s.koClips)
            .filter { it.id !in s.clipHidden && it.document in s.books }
            .distinctBy { it.id }
        clips.mapNotNull { c -> day(c.createdAt)?.takeIf { !it.isBefore(since) && !it.isAfter(today) }?.let { (it to c.document) to c.createdAt } }
            .groupBy({ it.first }, { it.second })
            .forEach { (key, times) -> out += Event(key.first, Kind.HIGHLIGHTED, key.second, times.size, times.max()) }
        // Newest day first; within a day, finishing before starting before highlights.
        return out.sortedWith(compareByDescending<Event> { it.day }.thenBy { it.kind.ordinal.let { o -> listOf(1, 0, 3, 2)[o] } }.thenByDescending { it.at })
    }

    // ---- What needs you ----

    sealed interface Need {
        /** A tracker that wants you to sign in again. */
        data class SignIn(val service: String) : Need
        /** Books where a tracker found no clear match. */
        data class Matches(val documents: List<String>) : Need
        /** Books where Seekquel has a different position. */
        data class Positions(val documents: List<String>) : Need
        /** Books finished lately and not rated yet. */
        data class Rate(val documents: List<String>) : Need
        /** A reading source (Kindle, Libby, KOSync, CrossPoint) that isn't answering. */
        data class Source(val name: String, val detail: String) : Need
    }

    fun needs(s: Snapshot, nowSec: Long = System.currentTimeMillis() / 1000): List<Need> {
        val out = ArrayList<Need>()
        for (t in Trackers.all) {
            val svc = s.service(t.id)
            if (svc.linked && svc.needsReauth) out += Need.SignIn(t.id)
        }
        s.kindleError?.takeIf { s.kindle != null }?.let { out += Need.Source("Kindle", it) }
        s.libbyError?.takeIf { s.libby != null }?.let { out += Need.Source("Libby", it) }
        s.kosyncError?.takeIf { s.kosync != null }?.let { out += Need.Source("KOSync", it) }
        val linked = Trackers.all.filter { s.service(it.id).let { v -> v.linked && v.enabled } }.map { it.id }.toSet()
        val unmatched = s.links.values
            .filter { it.service in linked && (it.externalId == null || !it.confirmed) && it.source != LinkSource.NONE && s.books[it.document]?.held == false }
            .map { it.document }.distinct()
            .sortedByDescending { s.books[it]?.timestamp ?: 0 }
        if (unmatched.isNotEmpty()) out += Need.Matches(unmatched)
        val asks = s.progressAsks.keys.filter { it in s.books }
        if (asks.isNotEmpty()) out += Need.Positions(asks)
        val rate = FinishFlow.toRate(s.books.values, nowSec).map { it.document }
        if (rate.isNotEmpty()) out += Need.Rate(rate)
        return out
    }
}
