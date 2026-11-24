package io.github.prithirajn.readync.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import io.github.prithirajn.readync.R
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentWidth
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
import io.github.prithirajn.readync.data.ReadStatus
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.sync.Highlights
import io.github.prithirajn.readync.sync.Insights
import io.github.prithirajn.readync.sync.ReadingStats
import io.github.prithirajn.readync.sync.Shelves
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.CpType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Pages read today, live, the same way the Library counts them. */
@Composable
internal fun rememberTodayPages(state: Snapshot): Int? {
    val all = state.books.values
    return remember(state.books, state.pagesDay, state.pagesStartF, state.pagesCleared) { Shelves.todayPages(state) }
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
internal fun TodayPanel(data: ActivityData) {
    val c = Cp.colors
    val todayPages = data.todayPages
    val w = data.week
    val streak = data.streak
    val month = data.month
    // The week as a printed poster: the week's pages as one huge figure, the days as bars of ink
    // (today's printed over a second ink), and the rest of the numbers set in a line of small figures.
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            androidx.compose.runtime.key(w.total) {
                RegisteredText(w.total.toString(), CpType.screenNumber.copy(fontSize = 88.sp, lineHeight = 84.sp), replay = LocalTabVisible.current)
            }
            Column(Modifier.padding(start = 12.dp, bottom = 10.dp)) {
                Text("pages this week", fontFamily = io.github.prithirajn.readync.ui.theme.Display, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, fontSize = 22.sp, lineHeight = 24.sp, color = c.text)
                w.changePercent?.let { ch ->
                    Text((if (ch >= 0) "up " else "down ") + "${kotlin.math.abs(ch)}% on last week", style = CpType.mono, color = if (ch >= 0) c.accentText else c.quiet)
                }
            }
            Spacer(Modifier.weight(1f))
            // The week in miniature: seven slim ticks of ink, today's the boldest.
            val max = (w.days.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
            val spoken = w.days.joinToString(", ") { (d, p) -> "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} $p pages" }
            Row(Modifier.padding(bottom = 14.dp).height(34.dp).semantics { contentDescription = spoken }, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                w.days.forEachIndexed { i, (_, p) ->
                    val isToday = i == w.days.lastIndex
                    val frac by animateFloatAsState(p.toFloat() / max, spring(dampingRatio = 0.55f, stiffness = 90f), label = "tick")
                    Box(
                        Modifier
                            .width(5.dp)
                            .fillMaxHeight(frac.coerceAtLeast(0.08f))
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (p == 0) c.track else if (isToday) c.accent else c.accent.copy(alpha = 0.5f)),
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(c.text.copy(alpha = 0.6f)))
        val (mp, mf, mh) = month
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            for ((n, label) in listOf((todayPages ?: 0) to "today", streak to "day streak", mp to "this month", mf to "finished", mh to "marked")) {
                Column(Modifier.weight(1f)) {
                    RollingNumber(n, CpType.screenSmall, c.text)
                    Text(label, style = CpType.mono, color = c.accentText, maxLines = 2)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.text.copy(alpha = 0.2f)))
    }
}

/** Two keys into the things made from your reading: your year (Wrapped and the pictures) and today's review. */
@Composable
internal fun ReadingToolsRow(vm: AppViewModel, state: Snapshot, left: Int, onReviewClosed: () -> Unit) {
    var year by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf(false) }
    // "Make": everything here makes something from your reading to keep or share.
    Text("Make", style = CpType.bodyStrong, color = Cp.colors.text, modifier = Modifier.padding(start = 16.dp, top = 22.dp))
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ToolTile("✨", "Your year", "Wrapped & pictures", Modifier.weight(1f), signal = false) { year = true }
        ToolTile("🔁", "Review", if (left > 0) "$left today" else "Done today", Modifier.weight(1f), signal = left > 0) { review = true }
    }
    if (year) YourYearSheet(vm, state) { year = false }
    if (review) DailyReviewSheet(vm, state) { review = false; onReviewClosed() }
}

@Composable
private fun ToolTile(emoji: String, title: String, line: String, modifier: Modifier, signal: Boolean, onClick: () -> Unit) {
    val kind = if (signal) KeyKind.SIGNAL else KeyKind.PLAIN
    Key(onClick, modifier.height(92.dp), kind = kind, shape = RoundedCornerShape(18.dp), label = title) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalAlignment = Alignment.Start) {
            Text(emoji, fontSize = 20.sp)
            Spacer(Modifier.height(6.dp))
            Text(title, style = CpType.bodyStrong, color = keyText(kind), maxLines = 1)
            Text(line, style = CpType.small, color = if (signal) Cp.colors.onAccent.copy(alpha = 0.8f) else Cp.colors.quiet, maxLines = 1)
        }
    }
}

/**
 * The last eighteen weeks as a grid of days, each lit by how much you read. Tap a day to see its
 * pages and the books you read on it.
 */
@Composable
internal fun HeatmapCard(state: Snapshot, data: ActivityData) {
    val c = Cp.colors
    val view = LocalView.current
    val today = remember { LocalDate.now() }
    val weeks = ActivityData.HEAT_WEEKS
    // Further back, a page of eighteen weeks at a time (0 = the latest, ending this week).
    var back by remember { mutableStateOf(0) }
    val earliest = remember(data.allDays) { data.allDays.filterValues { it > 0 }.keys.minOrNull()?.let { runCatching { LocalDate.parse(it) }.getOrNull() } }
    val canGoBack = earliest != null && earliest.isBefore(data.heatStart.minusWeeks((back * weeks).toLong()))
    // Columns are weeks (oldest first), rows Monday to Sunday.
    val start = data.heatStart.minusWeeks((back * weeks).toLong())
    val pages = remember(back, data) {
        if (back == 0) data.heat else (0 until weeks * 7).map { start.plusDays(it.toLong()) }.associateWith { data.allDays[it.toString()] ?: 0 }
    }
    val levels = data.heatLevels
    val lit = remember(pages, today) { pages.count { it.value > 0 } to pages.keys.count { !it.isAfter(today) } }
    if (back == 0 && lit.first == 0) return
    var picked by remember { mutableStateOf<LocalDate?>(null) }
    val fmt = remember { DateTimeFormatter.ofPattern("EEE d MMM") }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Reading days", style = CpType.bodyStrong, color = c.text, modifier = Modifier.weight(1f))
            DeviceLabel("${lit.first} of ${lit.second} days", color = c.quiet)
            HSpace(6)
            // Earlier and later pages of weeks.
            HeatArrow(R.drawable.lucide_chevron_left, "Earlier weeks", canGoBack) { back++; picked = null; Haptics.tick(view) }
            HSpace(4)
            HeatArrow(R.drawable.lucide_chevron_right, "Later weeks", back > 0) { back--; picked = null; Haptics.tick(view) }
        }
        if (back > 0) Text(
            "${DateTimeFormatter.ofPattern("d MMM yyyy").format(start)} to ${DateTimeFormatter.ofPattern("d MMM yyyy").format(start.plusWeeks(weeks.toLong()).minusDays(1))}",
            style = CpType.small, color = c.faint,
        )
        Spacer(Modifier.height(8.dp))
        CpCard {
            Column(Modifier.padding(14.dp)) {
                // Month names over the weeks they start in.
                Row(Modifier.fillMaxWidth()) {
                    var lastMonth = -1
                    for (wk in 0 until weeks) {
                        val d = start.plusWeeks(wk.toLong())
                        Box(Modifier.weight(1f)) {
                            if (d.monthValue != lastMonth && d.dayOfMonth <= 7) DeviceLabel(d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()), color = c.faint, modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true))
                        }
                        lastMonth = if (d.dayOfMonth <= 7) d.monthValue else lastMonth
                    }
                }
                Spacer(Modifier.height(4.dp))
                // The weeks ink in from the oldest, column by column, each time a page of them shows.
                val animate = LocalAnimationsOn.current
                val wave = remember(start) { androidx.compose.animation.core.Animatable(if (animate) 0f else 1f) }
                LaunchedEffect(start) { if (animate) wave.animateTo(1f, tween(700)) }
                // The day you're on pops up a size and settles.
                val pop = remember { androidx.compose.animation.core.Animatable(1f) }
                LaunchedEffect(picked) { if (animate && picked != null) { pop.snapTo(1.45f); pop.animateTo(1f, spring(dampingRatio = 0.38f, stiffness = 700f)) } }
                Canvas(
                    Modifier.fillMaxWidth().height(126.dp).pointerInput(start) {
                        detectTapGestures { p ->
                            val cw = size.width / weeks.toFloat(); val ch = size.height / 7f
                            val d = start.plusWeeks((p.x / cw).toInt().coerceIn(0, weeks - 1).toLong()).plusDays((p.y / ch).toInt().coerceIn(0, 6).toLong())
                            if (!d.isAfter(today)) { picked = if (picked == d) null else d; Haptics.tick(view) }
                        }
                    }.pointerInput(start, pages) {
                        // Hold a day, then slide: the days you pass are read out below, each felt
                        // as you cross it, harder on the days you read more.
                        detectDragGesturesAfterLongPress(
                            onDragStart = { p ->
                                val cw = size.width / weeks.toFloat(); val ch = size.height / 7f
                                val d = start.plusWeeks((p.x / cw).toInt().coerceIn(0, weeks - 1).toLong()).plusDays((p.y / ch).toInt().coerceIn(0, 6).toLong())
                                if (!d.isAfter(today)) { picked = d; Haptics.rise(view, 0.4f) }
                            },
                            onDrag = { ch0, _ ->
                                ch0.consume()
                                val cw = size.width / weeks.toFloat(); val ch = size.height / 7f
                                val d = start.plusWeeks((ch0.position.x / cw).toInt().coerceIn(0, weeks - 1).toLong()).plusDays((ch0.position.y / ch).toInt().coerceIn(0, 6).toLong())
                                if (d != picked && !d.isAfter(today)) {
                                    picked = d
                                    val n = pages[d] ?: 0
                                    val level = levels.count { n >= it } + if (n > 0) 1 else 0
                                    Haptics.texture(view, 0.1f + 0.18f * level)
                                }
                            },
                            onDragEnd = { Haptics.thud(view, 0.25f) },
                        )
                    },
                ) {
                    val cw = size.width / weeks; val ch = size.height / 7f
                    val cell = minOf(cw, ch) - 3.dp.toPx()
                    for (wk in 0 until weeks) for (dy in 0 until 7) {
                        val d = start.plusWeeks(wk.toLong()).plusDays(dy.toLong())
                        if (d.isAfter(today)) continue
                        val inked = ((wave.value * (weeks + 5) - wk) / 5f).coerceIn(0f, 1f)
                        if (inked <= 0f) continue
                        val p = pages[d] ?: 0
                        val col = when {
                            p == 0 -> c.track
                            p < levels[0] -> c.accent.copy(alpha = 0.3f)
                            p < levels[1] -> c.accent.copy(alpha = 0.55f)
                            p < levels[2] -> c.accent.copy(alpha = 0.8f)
                            else -> c.accent
                        }
                        val sz = cell * (0.4f + 0.6f * inked) * (if (d == picked) pop.value else 1f)
                        val tl = Offset(wk * cw + (cw - sz) / 2, dy * ch + (ch - sz) / 2)
                        drawRoundRect(col.copy(alpha = col.alpha * inked), tl, Size(sz, sz), CornerRadius(3.dp.toPx()))
                        if (d == picked || d == today) drawRoundRect(if (d == picked) c.text else c.accentText, Offset(tl.x - 1.5f, tl.y - 1.5f), Size(sz + 3f, sz + 3f), CornerRadius(4.dp.toPx()), style = Stroke(1.5.dp.toPx()))
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
                            Text("Tap a day, or hold and slide", style = CpType.small, color = c.faint)
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

/** A small round arrow for paging the heatmap, faded when there's nothing that way. */
@Composable
private fun HeatArrow(res: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Cp.colors
    Box(
        Modifier.size(30.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (enabled) c.divider else Color.Transparent)
            .then(if (enabled) Modifier.clickable(onClickLabel = label) { onClick() } else Modifier)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { LucideIcon(res, Modifier.size(16.dp), if (enabled) c.text else c.faint.copy(alpha = 0.5f)) }
}

/**
 * Your rhythm, printed like the week above it: the weekdays as bars of ink (the big day in full ink,
 * with a line saying so), then your best day, longest run and an ordinary reading day as figures.
 */
@Composable
internal fun RhythmCard(r: Rhythm) {
    val c = Cp.colors
    val fmt = remember { DateTimeFormatter.ofPattern("d MMM yyyy") }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 26.dp).fillMaxWidth()) {
        Text("Your rhythm", style = CpType.bodyStrong, color = c.text)
        Text(
            r.bigDay?.let { d ->
                val name = d.getDisplayName(TextStyle.FULL, Locale.getDefault())
                "${name}s are your big day: ${Math.round(r.weekdays[d.value - 1])} pages on average"
            } ?: "You read about the same every day of the week",
            fontFamily = io.github.prithirajn.readync.ui.theme.Display, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, fontSize = 19.sp, lineHeight = 22.sp, color = c.textMid,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(12.dp))
        val max = (r.weekdays.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
        val spoken = r.weekdays.withIndex().joinToString(", ") { (i, v) -> "${java.time.DayOfWeek.of(i + 1).getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${Math.round(v)} pages" }
        Row(Modifier.fillMaxWidth().height(84.dp).semantics { contentDescription = "Average pages by weekday: $spoken" }, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            r.weekdays.forEachIndexed { i, v ->
                val big = r.bigDay?.value == i + 1
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Box(
                        Modifier.fillMaxWidth().fillMaxHeight((v / max).toFloat().coerceIn(0.04f, 1f) * 0.78f)
                            .growIn(i)
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 2.dp, bottomStart = 2.dp, bottomEnd = 6.dp))
                            .background(if (v <= 0.0) c.track else if (big) c.accent else c.accent.copy(alpha = 0.45f)),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(java.time.DayOfWeek.of(i + 1).getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = CpType.mono, color = if (big) c.accentText else c.quiet)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(c.text.copy(alpha = 0.6f)))
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            val figures = listOfNotNull(
                r.best?.let { (d, p) -> p.toString() to "best day · ${fmt.format(d)}" },
                r.longest.toString() to "longest run",
                r.perDay.toString() to (if (r.minutes != null) "a reading day · ${r.minutes} min" else "a reading day"),
                "${r.share}%" to "of days read lately",
            )
            for ((n, label) in figures) {
                Column(Modifier.weight(1f)) {
                    FigureText(n, CpType.screenSmall, c.text, maxLines = 1)
                    Text(label, style = CpType.mono, color = c.accentText, maxLines = 2)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.text.copy(alpha = 0.2f)))
    }
}

/**
 * Everything made from your year in one place (before: Wrapped and the shelf poster here, the top
 * books image and the year's image set under Your library): pick one to make.
 */
@Composable
internal fun YourYearSheet(vm: AppViewModel, state: Snapshot, onClose: () -> Unit) {
    var open by remember { mutableStateOf<String?>(null) }
    when (open) {
        "wrapped" -> { WrappedSheet(state) { open = null }; return }
        "set" -> { YearSetSheet(state) { open = null }; return }
        "top" -> { TopBooksSheet(vm, state) { open = null }; return }
        "poster" -> { ShelfPosterSheet(state) { open = null }; return }
    }
    PlainSheet("Your year", onClose) {
        VSpace(6)
        CpCard {
            Column {
                NavRow(io.github.prithirajn.readync.R.drawable.lucide_sparkles, "Wrapped", "Your month or year as story slides, down to your reader type") { open = "wrapped" }
                RowDivider()
                NavRow(io.github.prithirajn.readync.R.drawable.lucide_image, "Year in books", "A set of pictures: numbers, top reads, months, genre shelves, gut vs rubric") { open = "set" }
                RowDivider()
                NavRow(io.github.prithirajn.readync.R.drawable.lucide_star, "Top books", "Your year's top 10 or 20, ranked, as one picture") { open = "top" }
                RowDivider()
                NavRow(io.github.prithirajn.readync.R.drawable.lucide_library_big, "Shelf poster", "The year's books as spines on a shelf, or a wall of covers") { open = "poster" }
            }
        }
        VSpace(12)
    }
}
