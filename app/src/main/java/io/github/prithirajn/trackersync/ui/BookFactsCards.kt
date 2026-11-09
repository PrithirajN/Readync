package io.github.prithirajn.trackersync.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.prithirajn.trackersync.R
import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.BookFacts
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.sync.ReadingStats
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import io.github.prithirajn.trackersync.ui.theme.CpType
import java.time.format.DateTimeFormatter

/** "Dungeon Crawler Carl · Book 4", for under the author. */
fun seriesLine(f: BookFacts?): String? = f?.series?.let { s -> s + (f.seriesNumber?.let { " · Book $it" } ?: "") }

/** "4.4 from 5.1k readers · 2021", the rating's star drawn beside it. */
@Composable
fun RatingLine(f: BookFacts?) {
    if (f == null || (f.rating == null && f.year == null)) return
    val c = Cp.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        f.rating?.let { r ->
            LucideIcon(R.drawable.lucide_star_filled, Modifier.size(14.dp), Color(0xFFF2B84B))
            HSpace(4)
            Text(String.format(java.util.Locale.getDefault(), "%.1f", r), style = CpType.bodyStrong, color = c.text)
            f.ratingsCount?.let { n -> Text(" from ${compact(n)} reader${if (n == 1) "" else "s"}", style = CpType.small, color = c.quiet) }
        }
        f.year?.let { y -> Text((if (f.rating != null) " · " else "") + y, style = CpType.small, color = c.quiet) }
    }
}

private fun compact(n: Int): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.getDefault(), "%.1fM", n / 1_000_000.0).replace(".0M", "M")
    n >= 1_000 -> String.format(java.util.Locale.getDefault(), "%.1fk", n / 1_000.0).replace(".0k", "k")
    else -> n.toString()
}

/** A small picture for the moods readers tag most; a plain dot for the rest. */
private fun moodEmoji(mood: String): String = when (mood.lowercase().trim()) {
    "adventurous" -> "🗺️"
    "funny" -> "😂"
    "tense" -> "😬"
    "dark" -> "🌑"
    "lighthearted" -> "🎈"
    "emotional" -> "🥲"
    "hopeful" -> "🌱"
    "mysterious" -> "🔍"
    "reflective" -> "💭"
    "sad" -> "😢"
    "inspiring" -> "✨"
    "informative" -> "📚"
    "challenging" -> "🧗"
    "relaxing" -> "🛋️"
    "fast-paced", "fast paced", "fast" -> "⚡"
    "slow-paced", "slow paced", "slow" -> "🐢"
    "medium-paced", "medium paced", "medium" -> "🚶"
    "romantic" -> "💕"
    "scary", "creepy" -> "👻"
    else -> "•"
}

/**
 * How the book feels, from what readers tagged it with: moods as chips, genres in a line, and
 * content warnings kept behind a tap (some are spoilers).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeelCard(f: BookFacts?) {
    if (f == null || (f.moods.isEmpty() && f.genres.isEmpty() && f.warnings.isEmpty() && f.tropes.isEmpty() && f.pace == null)) return
    val c = Cp.colors
    var showWarnings by remember(f) { mutableStateOf(false) }
    VSpace(16)
    CpCard {
        Column(Modifier.padding(16.dp)) {
            if (f.moods.isNotEmpty() || f.pace != null) {
                Text("Readers find it", style = CpType.small, color = c.quiet)
                VSpace(8)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    f.pace?.let { p ->
                        Row(
                            Modifier
                                .background(c.accentTint, CpShapes.pill)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(moodEmoji(p), style = CpType.small)
                            HSpace(6)
                            Text(p, style = CpType.label, color = c.accentText)
                        }
                    }
                    for (m in f.moods) {
                        Row(
                            Modifier
                                .border(1.dp, c.accentRing, CpShapes.pill)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(moodEmoji(m), style = CpType.small)
                            HSpace(6)
                            Text(m.replaceFirstChar { it.uppercase() }, style = CpType.label, color = c.text)
                        }
                    }
                }
            }
            if (f.genres.isNotEmpty()) {
                if (f.moods.isNotEmpty() || f.pace != null) VSpace(14)
                Text("Genres", style = CpType.small, color = c.quiet)
                VSpace(4)
                Text(f.genres.joinToString(" · "), style = CpType.body, color = c.text)
            }
            if (f.tropes.isNotEmpty()) {
                VSpace(14)
                Text("Tropes", style = CpType.small, color = c.quiet)
                VSpace(4)
                Text(f.tropes.joinToString(" · "), style = CpType.body, color = c.text)
            }
            if (f.warnings.isNotEmpty()) {
                VSpace(12)
                Row(
                    Modifier
                        .bouncyClickable(CpShapes.pill, pressedScale = 0.97f, role = Role.Button, onClickLabel = if (showWarnings) "Hide content warnings" else "Show content warnings") { showWarnings = !showWarnings }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LucideIcon(R.drawable.lucide_triangle_alert, Modifier.size(14.dp), c.quiet)
                    HSpace(6)
                    Text(
                        (if (showWarnings) "Hide" else "Show") + " content warnings (${f.warnings.size})",
                        style = CpType.small, color = c.textMid,
                    )
                }
                AnimatedVisibility(showWarnings) {
                    Column {
                        VSpace(6)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (w in f.warnings) {
                                if (w in f.graphic) Chip("Graphic: $w", color = c.warn, background = c.warn.copy(alpha = 0.14f))
                                else Chip(w, color = c.textMid, background = c.divider)
                            }
                        }
                    }
                }
            }
            if (f.ratings.size > 1) {
                VSpace(12)
                Text(
                    "Rated " + f.ratings.entries.joinToString(" · ") { (site, r) -> String.format(java.util.Locale.getDefault(), "%.1f on %s", r, site) },
                    style = CpType.small, color = c.quiet,
                )
            }
            VSpace(10)
            Quiet("Tagged by readers on ${f.source}", color = c.faint)
        }
    }
}

/**
 * The book's reading in numbers: pages, how long it's been going, pace, and when it'll be done
 * at that pace. Finished books say how long they took.
 */
@Composable
fun ReadingStatsCard(book: Book) {
    val r = remember(book) { ReadingStats.of(book) }
    if (r.isEmpty) return
    val c = Cp.colors
    val fmt = remember { DateTimeFormatter.ofPattern("d MMM yyyy") }
    val finished = book.status == ReadStatus.FINISHED
    val cells = buildList {
        if (r.pagesRead != null && r.pageCount != null) add("Print pages" to "${r.pagesRead} of ${r.pageCount}")
        r.days?.let { d -> add((if (finished) "Read in" else "Reading for") to "$d day${if (d == 1) "" else "s"}") }
        r.started?.let { add("Started" to fmt.format(it)) }
        r.finished?.let { add("Finished" to fmt.format(it)) }
        r.pagesPerDay?.let { p -> add("Pace" to (if (p >= 10) "${Math.round(p)}" else String.format(java.util.Locale.getDefault(), "%.1f", p)) + " pages a day") }
        r.eta?.let { add("Done around" to fmt.format(it)) }
    }
    if (cells.isEmpty()) return
    VSpace(16)
    CpCard {
        Column(Modifier.padding(16.dp)) {
            Text(if (finished) "How it went" else "Your reading", style = CpType.small, color = c.quiet)
            VSpace(10)
            for (row in cells.chunked(2)) {
                Row(Modifier.fillMaxWidth()) {
                    for ((label, value) in row) {
                        Column(Modifier.weight(1f).padding(bottom = 12.dp)) {
                            Text(label, style = CpType.small, color = c.quiet)
                            Text(value, style = CpType.section, color = c.text)
                        }
                    }
                    if (row.size == 1) Box(Modifier.weight(1f))
                }
            }
            if (r.eta != null) Quiet("At your pace since you started.", color = c.faint)
        }
    }
}

/**
 * Day by day: each day you read the book, with the pages and minutes when they're known (from
 * KOReader's statistics, Hardcover's journal…), newest first, with a strip of bars for the shape
 * of it. Shown only when there's any.
 */
@Composable
fun DayByDayCard(days: Map<String, io.github.prithirajn.trackersync.data.ReadDay>?) {
    if (days.isNullOrEmpty()) return
    val c = Cp.colors
    val sorted = remember(days) { days.entries.sortedByDescending { it.key } }
    var all by remember(days) { mutableStateOf(false) }
    val totalPages = remember(days) { days.values.mapNotNull { it.pages }.sum() }
    val totalMinutes = remember(days) { days.values.mapNotNull { it.minutes }.sum() }
    val fmt = remember { java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy") }
    fun hours(m: Int) = if (m < 60) "$m min" else "${m / 60} h" + (if (m % 60 > 0) " ${m % 60} min" else "")
    VSpace(16)
    CpCard {
        Column(Modifier.padding(16.dp)) {
            Text("Day by day", style = CpType.small, color = c.quiet)
            VSpace(4)
            Text(
                listOfNotNull(
                    "${days.size} day${if (days.size == 1) "" else "s"} of reading",
                    totalPages.takeIf { it > 0 }?.let { "$it pages" },
                    totalMinutes.takeIf { it > 0 }?.let { hours(it) },
                ).joinToString(" · "),
                style = CpType.bodyStrong, color = c.text,
            )
            // The shape of it: one bar per day (oldest on the left), by pages, else minutes.
            val bars = sorted.reversed().takeLast(60)
            val values = bars.map { (it.value.pages ?: it.value.minutes ?: 0).toFloat() }
            val top = values.maxOrNull()?.takeIf { it > 0f }
            if (top != null && bars.size > 1) {
                VSpace(12)
                Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (v in values) {
                        Box(
                            Modifier.weight(1f).fillMaxHeight((v / top).coerceIn(0.06f, 1f))
                                .clip(CpShapes.pill).background(c.accent.copy(alpha = 0.75f))
                        )
                    }
                }
            }
            VSpace(10)
            for ((day, d) in (if (all) sorted else sorted.take(7))) {
                val date = runCatching { java.time.LocalDate.parse(day) }.getOrNull()
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(date?.let { fmt.format(it) } ?: day, style = CpType.body, color = c.textMid, modifier = Modifier.weight(1f))
                    Text(
                        listOfNotNull(
                            d.pages?.let { "$it page${if (it == 1) "" else "s"}" },
                            d.minutes?.let { hours(it) },
                            d.reached?.let { "to ${Math.round(it * 100)}%" },
                        ).joinToString(" · ").ifEmpty { "read" },
                        style = CpType.body, color = c.text,
                    )
                }
            }
            if (sorted.size > 7) {
                VSpace(6)
                PillButton(if (all) "Show fewer" else "All ${sorted.size} days", { all = !all })
            }
            val from = remember(days) { days.values.map { it.source }.distinct().joinToString(", ") }
            VSpace(6)
            Quiet("From $from.", color = c.faint)
        }
    }
}
