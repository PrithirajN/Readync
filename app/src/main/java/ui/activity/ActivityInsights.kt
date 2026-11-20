package io.github.prithirajn.readync.ui

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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.prithirajn.readync.R
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.sync.Insights
import io.github.prithirajn.readync.sync.Shelves
import io.github.prithirajn.readync.trackers.Trackers
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.CpShapes
import io.github.prithirajn.readync.ui.theme.CpType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private fun openBook(document: String) { AppActions.pending.value = AppAction.OpenBook(document) }

/** The reading half of the Activity page, as list items. */
internal fun LazyListScope.activityInsights(vm: AppViewModel, state: Snapshot, data: ActivityData?, onReviewClosed: () -> Unit) {
    // Worked out in the background (a moment, the first time only): until then just the header.
    if (data == null) return
    // Today, this week and your streak on the device's screen, first.
    item(key = "today", contentType = "today") { DebugTimed("today") { Box(Modifier.riseIn(0)) { TodayPanel(data) } } }
    item(key = "needs", contentType = "needs") { DebugTimed("needs") { NeedsCard(state, data.needs) } }
    // The things made from your reading: Wrapped, today's review, the shelf poster.
    item(key = "tools", contentType = "tools") { DebugTimed("tools") { Box(Modifier.riseIn(1)) { ReadingToolsRow(vm, state, data.reviewLeft, onReviewClosed) } } }
    // How you read (the Library already lists what you're reading).
    data.rhythm?.let { r -> item(key = "rhythm", contentType = "rhythm") { DebugTimed("rhythm") { RhythmCard(r) } } }
    item(key = "heat", contentType = "heat") { DebugTimed("heat") { HeatmapCard(state, data) } }
    item(key = "year", contentType = "year") { DebugTimed("year") { YearCard(vm, state, data.year) } }
}

// ---- What needs you ----

@Composable
private fun NeedsCard(state: Snapshot, needs: List<Insights.Need>) {
    val c = Cp.colors
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
                            if (srcs.size == 1) io.github.prithirajn.readync.sync.Primary.name(srcs[0]) + " has a different position" else "Different positions from your sources"
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

// ---- The year ----

@Composable
private fun YearCard(vm: AppViewModel, state: Snapshot, y: Insights.Year) {
    val c = Cp.colors
    if (y.finished.isEmpty() && y.goal == 0 && state.books.isEmpty()) return
    var editing by remember { mutableStateOf(false) }
    val tick = rememberTick()
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp)) {
        Text("${y.year} so far", style = CpType.bodyStrong, color = c.text)
        VSpace(8)
        CpCard {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GoalRing(y.count, y.goal)
                    HSpace(16)
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (y.goal > 0) "${y.count} of ${y.goal} books" else "${y.count} book${if (y.count == 1) "" else "s"}" + if (y.setAside.isEmpty()) " finished" else "",
                            style = CpType.section, color = c.text,
                        )
                        if (y.setAside.isNotEmpty()) Quiet("${y.finished.size} finished, ${y.setAside.size} set aside")
                        if (y.pages > 0) Quiet(String.format(Locale.getDefault(), "%,d print pages", y.pages))
                        y.ahead?.let { a ->
                            Text(
                                when {
                                    y.count >= y.goal -> "Goal reached 🎉"
                                    a > 0 -> "$a ahead of pace"
                                    a < 0 -> "${-a} behind pace"
                                    else -> "Right on pace"
                                },
                                style = CpType.small,
                                color = if (a >= 0 || y.count >= y.goal) c.accentText else c.warn,
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
                                    if (y.goal == 0 && !editing) vm.updateSettings { it.copy(yearGoal = maxOf(12, y.count + 6)) }
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
                    // The year's covers as one drawn strip: one thing to make and lay out as the card
                    // scrolls in, however many books (a row of cover views each was the stutter here).
                    CoverStrip(y.finished) { openBook(it.document) }
                }
            }
        }
    }
}

/**
 * Covers in a row that scrolls sideways, drawn in one go (no view per cover): those already on
 * the phone at once, the rest as they arrive. Tap one to open its book.
 */
@Composable
private fun CoverStrip(books: List<io.github.prithirajn.readync.data.Book>, onOpen: (io.github.prithirajn.readync.data.Book) -> Unit) {
    val c = Cp.colors
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val density = androidx.compose.ui.platform.LocalDensity.current
    val w = 44.dp; val h = 66.dp; val gap = 8.dp
    val hPx = with(density) { h.roundToPx() }
    val urls = remember(books) { books.map { it.coverUrl } }
    val covers by androidx.compose.runtime.produceState(
        urls.map { u -> u?.let { CoverCache.cached(it, hPx)?.asImageBitmap() } }, urls, hPx,
    ) {
        if (value.indices.none { value[it] == null && urls[it] != null }) return@produceState
        val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            urls.mapIndexed { i, u -> value[i] ?: u?.let { runCatching { CoverCache.load(context, it, hPx) }.getOrNull()?.asImageBitmap() } }
        }
        value = loaded
    }
    val spoken = remember(books) { "Finished: " + books.joinToString(", ") { it.displayTitle } }
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Canvas(
            Modifier
                .width((w + gap) * books.size - gap)
                .height(h)
                .semantics { contentDescription = spoken }
                .pointerInput(books) {
                    detectTapGestures { p ->
                        val cell = (w + gap).toPx()
                        val i = (p.x / cell).toInt()
                        if (i in books.indices && p.x - i * cell <= w.toPx()) onOpen(books[i])
                    }
                },
        ) {
            val cw = w.toPx(); val ch = h.toPx(); val step = (w + gap).toPx()
            val r = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
            for (i in books.indices) {
                val x = i * step
                val img = covers.getOrNull(i)
                val shape = androidx.compose.ui.graphics.Path().apply {
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(x, 0f, x + cw, ch, r))
                }
                clipPath(shape) {
                    drawRect(c.cover, Offset(x, 0f), Size(cw, ch))
                    if (img != null) {
                        // Cropped to fill, like the covers elsewhere.
                        val scale = maxOf(cw / img.width, ch / img.height)
                        val sw = (cw / scale).toInt().coerceAtMost(img.width)
                        val sh = (ch / scale).toInt().coerceAtMost(img.height)
                        drawImage(
                            img,
                            srcOffset = androidx.compose.ui.unit.IntOffset((img.width - sw) / 2, (img.height - sh) / 2),
                            srcSize = androidx.compose.ui.unit.IntSize(sw, sh),
                            dstOffset = androidx.compose.ui.unit.IntOffset(x.toInt(), 0),
                            dstSize = androidx.compose.ui.unit.IntSize(cw.toInt(), ch.toInt()),
                        )
                    }
                }
                drawRoundRect(c.hairline, Offset(x, 0f), Size(cw, ch), r, style = Stroke(1.dp.toPx()))
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

