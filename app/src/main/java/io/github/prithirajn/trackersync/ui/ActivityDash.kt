package io.github.prithirajn.trackersync.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.sync.Highlights
import io.github.prithirajn.trackersync.sync.Insights
import io.github.prithirajn.trackersync.sync.ReadingStats
import io.github.prithirajn.trackersync.sync.Shelves
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Pages read today, live, the same way the Library counts them. */
@Composable
internal fun rememberTodayPages(state: Snapshot): Int? {
    val all = state.books.values
    return remember(state.books, state.pagesDay, state.pagesDayStart, state.pagesTotal) {
        Shelves.pagesRead(all)?.let { p ->
            val before = Shelves.pagesBeforeToday(all)
            val dayStart = if (state.pagesDay == Shelves.day()) state.pagesDayStart else state.pagesTotal ?: p
            (p - maxOf(dayStart, before)).coerceAtLeast(0)
        }
    }
}

/** A panel of the device with its screen: the frame the Activity page's readouts sit in. */
@Composable
private fun DevicePanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = Cp.colors
    Box(
        modifier.fillMaxWidth().shadow(14.dp, RoundedCornerShape(26.dp), ambientColor = c.shade, spotColor = c.shade)
            .clip(RoundedCornerShape(26.dp)).background(c.surface).padding(10.dp),
    ) { content() }
}

/**
 * Today at a glance on the device's screen: pages today, this week (against last week) and your
 * streak, the week as a row of lit columns, and the month in one line.
 */
@Composable
internal fun TodayPanel(state: Snapshot) {
    val c = Cp.colors
    val todayPages = rememberTodayPages(state)
    val today = remember { LocalDate.now() }
    val w = remember(state.pagesByDay, todayPages) { Insights.week(state, today, todayPages) }
    val streak = remember(state.pagesByDay, todayPages) { Shelves.streak(state, todayPages) }
    val month = remember(state.pagesByDay, todayPages, state.books) {
        val pages = Shelves.pagesThisMonth(state, todayPages)
        val finished = Shelves.finishedThisMonth(state.books.values)
        val m = Shelves.month()
        val hl = Highlights.all(state).count { it.clip.createdAt > 0 && Shelves.month(it.clip.createdAt * 1000) == m }
        Triple(pages, finished, hl)
    }
    DevicePanel(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
        Screen(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Bottom) {
                for ((label, n, hot) in listOf(
                    Triple("Today", todayPages ?: 0, (todayPages ?: 0) > 0),
                    Triple("This week", w.total, false),
                    Triple("Streak", streak, streak >= 2),
                )) Column(Modifier.weight(1f)) {
                    DeviceLabel(label, color = c.screenDim)
                    RollingNumber(n, CpType.screenNumber.copy(fontSize = 34.sp, lineHeight = 36.sp), if (hot) c.accent else c.screenText)
                }
            }
            w.changePercent?.let { ch ->
                DeviceLabel((if (ch >= 0) "▲ " else "▼ ") + "${kotlin.math.abs(ch)}% on last week", color = if (ch >= 0) c.accent else c.screenDim)
            }
            Spacer(Modifier.height(12.dp))
            // The week: a column of segments per day, lit up to that day's pages.
            val max = (w.days.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
            val spoken = w.days.joinToString(", ") { (d, p) -> "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} $p pages" }
            Row(Modifier.fillMaxWidth().height(86.dp).semantics { contentDescription = spoken }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                w.days.forEachIndexed { i, (d, p) ->
                    val isToday = i == w.days.lastIndex
                    val frac by animateFloatAsState(p.toFloat() / max, spring(dampingRatio = 0.6f, stiffness = 120f), label = "dayBar")
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Canvas(Modifier.fillMaxWidth().weight(1f)) {
                            val segs = 8
                            val gap = 3.dp.toPx()
                            val sh = (size.height - gap * (segs - 1)) / segs
                            for (k in 0 until segs) {
                                val lit = p > 0 && (k + 0.5f) / segs <= frac.coerceAtLeast(0.07f)
                                drawRoundRect(
                                    if (lit) (if (isToday) c.accent else c.accent.copy(alpha = 0.6f)) else c.screenOff,
                                    topLeft = Offset(0f, size.height - (k + 1) * sh - k * gap), size = Size(size.width, sh),
                                    cornerRadius = CornerRadius(2.dp.toPx()),
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        DeviceLabel(d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), color = if (isToday) c.accent else c.screenDim)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val (mp, mf, mh) = month
            DeviceLabel("${Shelves.monthName()}: $mp pages · $mf finished · $mh highlights", color = c.screenDim)
        }
    }
}

/** Three keys into the things made from your reading: Wrapped, today's review, the shelf poster. */
@Composable
internal fun ReadingToolsRow(vm: AppViewModel, state: Snapshot) {
    val c = Cp.colors
    val context = LocalContext.current
    var wrapped by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf(false) }
    var poster by remember { mutableStateOf(false) }
    val left = remember(state.cpClips, state.koClips, state.paperClips, review) { runCatching { DailyReview.left(context, state) }.getOrDefault(0) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ToolTile("✨", "Wrapped", Shelves.monthName().substringBefore(' '), Modifier.weight(1f), signal = false) { wrapped = true }
        ToolTile("🔁", "Review", if (left > 0) "$left today" else "Done today", Modifier.weight(1f), signal = left > 0) { review = true }
        ToolTile("📚", "Shelf", "Poster", Modifier.weight(1f), signal = false) { poster = true }
    }
    if (wrapped) WrappedSheet(state) { wrapped = false }
    if (review) DailyReviewSheet(vm, state) { review = false }
    if (poster) ShelfPosterSheet(state) { poster = false }
}

@Composable
private fun ToolTile(emoji: String, title: String, line: String, modifier: Modifier, signal: Boolean, onClick: () -> Unit) {
    val kind = if (signal) KeyKind.SIGNAL else KeyKind.PLAIN
    Key(onClick, modifier.height(92.dp), kind = kind, shape = RoundedCornerShape(18.dp), label = title) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalAlignment = Alignment.Start) {
            Text(emoji, fontSize = 20.sp)
            Spacer(Modifier.height(6.dp))
            Text(title, style = CpType.bodyStrong, color = keyText(kind), maxLines = 1)
            Text(line, style = CpType.small, color = if (signal) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f) else Cp.colors.quiet, maxLines = 1)
        }
    }
}

/**
 * The last eighteen weeks as a grid of days, each lit by how much you read. Tap a day to see its
 * pages and the books you read on it.
 */
@Composable
internal fun HeatmapCard(state: Snapshot) {
    val c = Cp.colors
    val view = LocalView.current
    val today = remember { LocalDate.now() }
    val weeks = 18
    // Columns are weeks (oldest first), rows Monday to Sunday.
    val start = remember { today.minusDays((today.dayOfWeek.value - 1).toLong()).minusWeeks((weeks - 1).toLong()) }
    val pages = remember(state.pagesByDay) { (0 until weeks * 7).map { start.plusDays(it.toLong()) }.associateWith { state.pagesByDay[it.toString()] ?: 0 } }
    if (pages.values.all { it == 0 }) return
    val levels = remember(pages) {
        val nonzero = pages.values.filter { it > 0 }.sorted()
        listOf(0.25, 0.5, 0.75).map { q -> nonzero.getOrElse((nonzero.size * q).toInt()) { nonzero.lastOrNull() ?: 1 } }
    }
    var picked by remember { mutableStateOf<LocalDate?>(null) }
    val fmt = remember { DateTimeFormatter.ofPattern("EEE d MMM") }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Reading days", style = CpType.bodyStrong, color = c.text, modifier = Modifier.weight(1f))
            DeviceLabel("${pages.count { it.value > 0 }} of ${pages.keys.count { !it.isAfter(today) }} days", color = c.quiet)
        }
        Spacer(Modifier.height(8.dp))
        CpCard {
            Column(Modifier.padding(14.dp)) {
                // Month names over the weeks they start in.
                Row(Modifier.fillMaxWidth()) {
                    var lastMonth = -1
                    for (wk in 0 until weeks) {
                        val d = start.plusWeeks(wk.toLong())
                        Box(Modifier.weight(1f)) {
                            if (d.monthValue != lastMonth && d.dayOfMonth <= 7) DeviceLabel(d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()), color = c.faint)
                        }
                        lastMonth = if (d.dayOfMonth <= 7) d.monthValue else lastMonth
                    }
                }
                Spacer(Modifier.height(4.dp))
                Canvas(
                    Modifier.fillMaxWidth().height(126.dp).pointerInput(start) {
                        detectTapGestures { p ->
                            val cw = size.width / weeks.toFloat(); val ch = size.height / 7f
                            val d = start.plusWeeks((p.x / cw).toInt().toLong()).plusDays((p.y / ch).toInt().coerceIn(0, 6).toLong())
                            if (!d.isAfter(today)) { picked = if (picked == d) null else d; Haptics.tick(view) }
                        }
                    },
                ) {
                    val cw = size.width / weeks; val ch = size.height / 7f
                    val cell = minOf(cw, ch) - 3.dp.toPx()
                    for (wk in 0 until weeks) for (dy in 0 until 7) {
                        val d = start.plusWeeks(wk.toLong()).plusDays(dy.toLong())
                        if (d.isAfter(today)) continue
                        val p = pages[d] ?: 0
                        val col = when {
                            p == 0 -> c.track
                            p < levels[0] -> c.accent.copy(alpha = 0.3f)
                            p < levels[1] -> c.accent.copy(alpha = 0.55f)
                            p < levels[2] -> c.accent.copy(alpha = 0.8f)
                            else -> c.accent
                        }
                        val tl = Offset(wk * cw + (cw - cell) / 2, dy * ch + (ch - cell) / 2)
                        drawRoundRect(col, tl, Size(cell, cell), CornerRadius(3.dp.toPx()))
                        if (d == picked || d == today) drawRoundRect(if (d == picked) c.text else c.accentText, Offset(tl.x - 1.5f, tl.y - 1.5f), Size(cell + 3f, cell + 3f), CornerRadius(4.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                    }
                }
                Spacer(Modifier.height(8.dp))
                AnimatedContent(picked, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) }, label = "dayDetail") { d ->
                    if (d == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Less", style = CpType.small, color = c.faint)
                            for (a in listOf(0f, 0.3f, 0.55f, 0.8f, 1f)) Box(Modifier.padding(horizontal = 2.dp).size(10.dp).clip(RoundedCornerShape(2.dp)).background(if (a == 0f) c.track else c.accent.copy(alpha = a)))
                            Text("More", style = CpType.small, color = c.faint)
                            Spacer(Modifier.weight(1f))
                            Text("Tap a day", style = CpType.small, color = c.faint)
                        }
                    } else {
                        val key = d.toString()
                        val books = state.readingDays.filterValues { it.containsKey(key) }.keys.mapNotNull { state.books[it] }
                        Column {
                            Text("${fmt.format(d)} · ${pages[d] ?: 0} pages" + (state.readingDays.values.sumOf { it[key]?.minutes ?: 0 }.takeIf { it > 0 }?.let { " · $it min" } ?: ""), style = CpType.bodyStrong, color = c.text)
                            if (books.isNotEmpty()) Text(books.joinToString(", ") { QuoteCard.shortTitle(it.displayTitle) }, style = CpType.small, color = c.quiet, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** The books you're reading with when each will be done at your pace, soonest first. */
@Composable
internal fun FinishingSoonCard(state: Snapshot) {
    val c = Cp.colors
    val rows = remember(state.books) {
        state.books.values.filter { it.status == ReadStatus.READING && !it.held }
            .map { it to ReadingStats.of(it) }
            .sortedWith(compareBy({ it.second.eta == null }, { it.second.eta }, { -it.first.timestamp }))
            .take(4)
    }
    if (rows.isEmpty()) return
    val fmt = remember { DateTimeFormatter.ofPattern("EEE d MMM") }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp)) {
        Text("On the go", style = CpType.bodyStrong, color = c.text)
        Spacer(Modifier.height(8.dp))
        CpCard {
            rows.forEachIndexed { i, (b, st) ->
                if (i > 0) RowDivider()
                Row(
                    Modifier.fillMaxWidth()
                        .bouncyClickable(androidx.compose.ui.graphics.RectangleShape, pressedScale = 0.99f, role = Role.Button, onClickLabel = b.displayTitle) { AppActions.pending.value = AppAction.OpenBook(b.document) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BookCover(b.coverUrl, b.displayTitle, 30.dp, 45.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(QuoteCard.shortTitle(b.displayTitle), style = CpType.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        SegmentBar(b.percentage.toFloat(), Modifier.fillMaxWidth(), segments = 16, height = 5.dp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            listOfNotNull(
                                "${b.percent}%",
                                st.eta?.let { "done around ${fmt.format(it)}" },
                                st.pagesPerDay?.let { "${Math.round(it)} pages a day" },
                                if (st.eta == null && st.pageCount != null && st.pagesRead != null) "${st.pageCount - st.pagesRead} pages left" else null,
                            ).joinToString(" · "),
                            style = CpType.small, color = c.quiet, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
