package io.github.prithirajn.trackersync.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.prithirajn.trackersync.R
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.sync.Insights
import io.github.prithirajn.trackersync.sync.Shelves
import io.github.prithirajn.trackersync.trackers.Trackers
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import io.github.prithirajn.trackersync.ui.theme.CpType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private fun openBook(document: String) { AppActions.pending.value = AppAction.OpenBook(document) }

/** The reading half of the Activity page, as list items. */
fun LazyListScope.activityInsights(vm: AppViewModel, state: Snapshot) {
    // Today, this week and your streak on the device's screen, first.
    item(key = "today") { Box(Modifier.riseIn(0)) { TodayPanel(state) } }
    item(key = "needs") { NeedsCard(state) }
    // The things made from your reading: Wrapped, today's review, the shelf poster.
    item(key = "tools") { Box(Modifier.riseIn(1)) { ReadingToolsRow(vm, state) } }
    item(key = "soon") { FinishingSoonCard(state) }
    item(key = "heat") { HeatmapCard(state) }
    item(key = "year") { YearCard(vm, state) }
}

// ---- What needs you ----

@Composable
private fun NeedsCard(state: Snapshot) {
    val c = Cp.colors
    val needs = remember(state.services, state.links, state.progressAsks, state.books, state.kindleError, state.libbyError, state.kosyncError) {
        Insights.needs(state)
    }
    if (needs.isEmpty()) {
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth()
                .clip(CpShapes.card).background(c.accentTint).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LucideIcon(R.drawable.lucide_circle_check, Modifier.size(16.dp), c.accentText)
            HSpace(10)
            Text("All caught up: nothing needs you.", style = CpType.small, color = c.accentText)
        }
        return
    }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        Text("Needs you", style = CpType.bodyStrong, color = c.text)
        VSpace(8)
        CpCard {
            needs.forEachIndexed { i, n ->
                if (i > 0) RowDivider()
                val (icon, title, detail, warn) = when (n) {
                    is Insights.Need.SignIn -> Quad(R.drawable.lucide_lock, "Sign in to ${Trackers.byId(n.service)?.name ?: n.service} again", "Nothing reaches it until you do", true)
                    is Insights.Need.Source -> Quad(R.drawable.lucide_triangle_alert, "${n.name} isn't answering", n.detail, true)
                    is Insights.Need.Matches -> Quad(
                        R.drawable.lucide_search,
                        "${n.documents.size} book${if (n.documents.size == 1) "" else "s"} need${if (n.documents.size == 1) "s" else ""} a match picked or confirmed",
                        n.documents.take(2).mapNotNull { state.books[it]?.displayTitle }.joinToString(", ") + if (n.documents.size > 2) " and more" else "",
                        false,
                    )
                    is Insights.Need.Positions -> Quad(
                        R.drawable.lucide_scale,
                        n.documents.mapNotNull { state.progressAsks[it]?.source }.distinct().let { srcs ->
                            if (srcs.size == 1) io.github.prithirajn.trackersync.sync.Primary.name(srcs[0]) + " has a different position" else "Different positions from your sources"
                        },
                        "For ${n.documents.size} book${if (n.documents.size == 1) "" else "s"}: pick which to keep",
                        false,
                    )
                    is Insights.Need.Rate -> Quad(
                        R.drawable.lucide_star,
                        "Rate what you finished",
                        n.documents.take(2).mapNotNull { state.books[it]?.displayTitle }.joinToString(", ") + if (n.documents.size > 2) " and more" else "",
                        false,
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .bouncyClickable(RectangleShape, pressedScale = 0.99f, role = Role.Button, onClickLabel = title) {
                            when (n) {
                                is Insights.Need.SignIn, is Insights.Need.Source -> AppActions.pending.value = AppAction.Services
                                is Insights.Need.Matches -> openBook(n.documents.first())
                                is Insights.Need.Positions -> openBook(n.documents.first())
                                is Insights.Need.Rate -> AppActions.pending.value = AppAction.Rate(n.documents.first())
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(32.dp).clip(CpShapes.control).background(if (warn) c.warn.copy(alpha = 0.16f) else c.accentTint),
                        contentAlignment = Alignment.Center,
                    ) { LucideIcon(icon, Modifier.size(16.dp), if (warn) c.warn else c.accentText) }
                    HSpace(12)
                    Column(Modifier.weight(1f)) {
                        Text(title, style = CpType.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (detail.isNotBlank()) Quiet(detail, maxLines = 1)
                    }
                    LucideIcon(R.drawable.lucide_chevron_right, Modifier.size(16.dp), c.faint)
                }
            }
        }
    }
}

private data class Quad(val icon: Int, val title: String, val detail: String, val warn: Boolean)

// ---- This week ----

@Composable
private fun WeekCard(state: Snapshot) {
    val c = Cp.colors
    val all = state.books.values
    // Today's pages, live, the same way the Library counts them.
    val todayPages = remember(state.books, state.pagesDay, state.pagesStartF, state.pagesCleared) { Shelves.todayPages(state) }
    val today = remember { LocalDate.now() }
    val w = remember(state.pagesByDay, todayPages) { Insights.week(state, today, todayPages) }
    if (w.total == 0 && w.previousTotal == 0 && state.pagesByDay.isEmpty()) return
    val streak = remember(state.pagesByDay, todayPages) { Shelves.streak(state, todayPages) }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp)) {
        Text("This week", style = CpType.bodyStrong, color = c.text)
        VSpace(8)
        CpCard {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(String.format(Locale.getDefault(), "%,d", w.total), style = CpType.pageTitle, color = c.text)
                    HSpace(6)
                    Text("pages", style = CpType.body, color = c.quiet, modifier = Modifier.padding(bottom = 6.dp))
                    Box(Modifier.weight(1f))
                    w.changePercent?.let { ch ->
                        val up = ch >= 0
                        Text(
                            (if (up) "▲ " else "▼ ") + "${kotlin.math.abs(ch)}% on last week",
                            style = CpType.small,
                            color = if (up) c.accentText else c.quiet,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
                VSpace(12)
                WeekBars(w)
                VSpace(12)
                Text(
                    listOfNotNull(
                        "${w.readingDays} of 7 days",
                        if (w.total > 0) "about ${Math.round(w.total / 7.0)} a day" else null,
                        w.best?.let { (d, p) -> "best: ${d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}, $p" },
                        if (streak >= 2) "🔥 $streak-day streak" else null,
                    ).joinToString(" · "),
                    style = CpType.small, color = c.quiet,
                )
            }
        }
    }
}

@Composable
private fun WeekBars(w: Insights.Week) {
    val c = Cp.colors
    val max = (w.days.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    val spoken = w.days.joinToString(", ") { (d, p) -> "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} $p pages" }
    Row(
        Modifier.fillMaxWidth().height(112.dp).semantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        w.days.forEachIndexed { i, (d, p) ->
            val isToday = i == w.days.lastIndex
            val target = p.toFloat() / max
            val h by animateFloatAsState(target, Motion.bouncy(), label = "bar")
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                if (p > 0) Text(if (p >= 1000) "${p / 1000}k" else p.toString(), style = CpType.chip, color = if (isToday) c.accentText else c.faint, maxLines = 1)
                VSpace(2)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((70 * h).coerceAtLeast(if (p > 0) 4f else 2f).dp)
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                        .background(if (p == 0) c.divider else if (isToday) c.accent else c.accent.copy(alpha = 0.55f)),
                )
                VSpace(6)
                Text(
                    d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = CpType.small,
                    color = if (isToday) c.accentText else c.quiet,
                )
            }
        }
    }
}

// ---- The year ----

@Composable
private fun YearCard(vm: AppViewModel, state: Snapshot) {
    val c = Cp.colors
    val today = remember { LocalDate.now() }
    val y = remember(state.books, state.settings.yearGoal) { Insights.year(state, today) }
    if (y.finished.isEmpty() && y.goal == 0 && state.books.isEmpty()) return
    var editing by remember { mutableStateOf(false) }
    val tick = rememberTick()
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp)) {
        Text("${y.year} so far", style = CpType.bodyStrong, color = c.text)
        VSpace(8)
        CpCard {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GoalRing(y.finished.size, y.goal)
                    HSpace(16)
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (y.goal > 0) "${y.finished.size} of ${y.goal} books" else "${y.finished.size} book${if (y.finished.size == 1) "" else "s"} finished",
                            style = CpType.section, color = c.text,
                        )
                        if (y.pages > 0) Quiet(String.format(Locale.getDefault(), "%,d print pages", y.pages))
                        y.ahead?.let { a ->
                            Text(
                                when {
                                    y.finished.size >= y.goal -> "Goal reached 🎉"
                                    a > 0 -> "$a ahead of pace"
                                    a < 0 -> "${-a} behind pace"
                                    else -> "Right on pace"
                                },
                                style = CpType.small,
                                color = if (a >= 0 || y.finished.size >= y.goal) c.accentText else c.warn,
                            )
                        }
                        VSpace(6)
                        Text(
                            if (y.goal > 0) (if (editing) "Done" else "Change goal") else "Set a yearly goal",
                            style = CpType.small,
                            color = c.accentText,
                            modifier = Modifier
                                .clip(CpShapes.pill)
                                .bouncyClickable(CpShapes.pill, pressedScale = 0.94f, role = Role.Button) {
                                    if (y.goal == 0 && !editing) vm.updateSettings { it.copy(yearGoal = maxOf(12, y.finished.size + 6)) }
                                    editing = !editing
                                }
                                .padding(vertical = 4.dp),
                        )
                    }
                }
                if (editing) {
                    VSpace(10)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Books this year", style = CpType.body, color = c.text, modifier = Modifier.weight(1f))
                        PillButton("−", { vm.updateSettings { it.copy(yearGoal = (it.yearGoal - 1).coerceAtLeast(0)) }; tick() })
                        Text(
                            if (y.goal == 0) "Off" else y.goal.toString(),
                            style = CpType.bodyStrong, color = c.text,
                            modifier = Modifier.width(48.dp).padding(horizontal = 8.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        PillButton("+", { vm.updateSettings { it.copy(yearGoal = (it.yearGoal + 1).coerceAtMost(999)) }; tick() })
                    }
                }
                if (y.finished.isNotEmpty()) {
                    VSpace(14)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (b in y.finished) {
                            Box(Modifier.bouncyClickable(CpShapes.control, pressedScale = 0.94f, role = Role.Button, onClickLabel = b.displayTitle) { openBook(b.document) }) {
                                BookCover(b.coverUrl, b.displayTitle, 44.dp, 66.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A ring that fills toward the goal; without one, just the count in a circle. */
@Composable
private fun GoalRing(done: Int, goal: Int) {
    val c = Cp.colors
    val frac = if (goal > 0) (done.toFloat() / goal).coerceIn(0f, 1f) else 0f
    val anim by animateFloatAsState(frac, Motion.bouncy(), label = "ring")
    Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(72.dp)) {
            val stroke = 8.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(c.divider, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            if (goal > 0) drawArc(c.accent, -90f, 360f * anim, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text(if (goal > 0) "${Math.round(frac * 100)}%" else done.toString(), style = CpType.bodyStrong, color = c.text)
    }
}

