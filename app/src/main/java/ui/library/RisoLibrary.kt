package io.github.prithirajn.readync.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.prithirajn.readync.data.Book
import io.github.prithirajn.readync.data.ReadStatus
import io.github.prithirajn.readync.sync.Shelves
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.CpType
import io.github.prithirajn.readync.ui.theme.Display
import io.github.prithirajn.readync.ui.theme.DisplaySmall

/*
 * The Library, printed: a desk of the other books you're reading as covers you can flick through,
 * a ledger of the month in big figures with the streak stamped in ink, and the index of your books
 * as a printed list (or a wall of covers) instead of stacked cards.
 */

/** A press that answers with its own motion (no ripple). */
private fun Modifier.press(source: MutableInteractionSource, label: String, onClick: () -> Unit): Modifier =
    clickable(source, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick)

/**
 * The other books open on your desk: big covers in a row you can flick, each tilted a little as if
 * laid down by hand, over its own colour slightly off register, with a ribbon of that colour
 * showing how far in you are. A press lifts the cover and straightens it.
 */
@Composable
internal fun DeskShelf(books: List<Book>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Cp.colors
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
            Text("also on your desk", style = CpType.eyebrow, color = c.accentText, modifier = Modifier.weight(1f))
            Text("${books.size}", style = CpType.mono, color = c.quiet)
        }
        LazyRow(
            contentPadding = PaddingValues(start = 16.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(books, key = { it.document }) { b ->
                val tilt = remember(b.document) { ((b.document.hashCode() and 0x7fffffff) % 5 - 2) * 0.9f }
                DeskBook(b, tilt) { onOpen(b.document) }
            }
        }
    }
}

@Composable
private fun DeskBook(book: Book, tilt: Float, onClick: () -> Unit) {
    val c = Cp.colors
    val view = androidx.compose.ui.platform.LocalView.current
    val ink = rememberBookInk(book.coverUrl)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val lift by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "deskLift")
    Column(Modifier.width(108.dp).press(source, book.displayTitle) { Haptics.tick(view); onClick() }) {
        Box(
            Modifier
                .graphicsLayer {
                    rotationZ = tilt * (1f - lift)
                    translationY = -6.dp.toPx() * lift
                    scaleX = 1f + 0.04f * lift; scaleY = 1f + 0.04f * lift
                }
                .size(108.dp, 162.dp)
                .drawBehind { drawRoundRect(ink, topLeft = Offset(4.dp.toPx(), 5.dp.toPx()), size = size, cornerRadius = CornerRadius(6.dp.toPx())) },
        ) { BookCover(book.coverUrl, book.displayTitle, 108.dp, 162.dp) }
        Spacer(Modifier.height(12.dp))
        SegmentBar(book.percentage.toFloat(), height = 4.dp, on = ink)
        Spacer(Modifier.height(6.dp))
        Text(book.displayTitle, fontFamily = DisplaySmall, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 17.sp, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("${book.percent}%", style = CpType.mono, color = c.quiet)
    }
}

/**
 * The month as a ledger: three big figures side by side between thin rules, and the streak under
 * them as a row of ink stamps, each pressed a little crooked; today's waits as an empty ring that
 * breathes until you read.
 */
@Composable
internal fun Ledger(
    reading: Int, pages: Int?, finished: Int, month: String?, today: Int?, streak: Int, readToday: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = Cp.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(c.text.copy(alpha = 0.7f)))
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            LedgerFigure(reading, "reading", Modifier.weight(1f))
            Rule()
            LedgerFigure(pages ?: 0, if (month != null) "pages in $month" else "pages", Modifier.weight(1.3f).padding(start = 12.dp),
                note = if (today != null && today > 0) "$today today" else "none yet today")
            Rule()
            LedgerFigure(finished, if (month != null) "done in $month" else "finished", Modifier.weight(1f).padding(start = 12.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.text.copy(alpha = 0.25f)))
        if (streak > 0 || !readToday) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (streak <= 1) "Day one" else "$streak days running",
                        fontFamily = Display, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = c.text,
                    )
                    if (!readToday && streak >= 1) Text("read a page today to keep it going", style = CpType.mono, color = c.quiet)
                }
                Stamps(streak, readToday)
            }
        }
    }
}

@Composable
private fun LedgerFigure(value: Int, label: String, modifier: Modifier, note: String? = null) {
    val c = Cp.colors
    Column(modifier) {
        RollingNumber(value, CpType.screenNumber.copy(fontSize = 36.sp, lineHeight = 38.sp), c.text)
        Text(label, style = CpType.mono, color = c.accentText, maxLines = 2)
        note?.let { Text(it, style = CpType.small, color = c.quiet) }
    }
}

@Composable
private fun Rule() {
    Box(Modifier.width(1.dp).height(58.dp).background(Cp.colors.text.copy(alpha = 0.2f)))
}

/** The last seven days as ink stamps; today an empty ring that breathes while it's still to do. */
@Composable
private fun Stamps(streak: Int, readToday: Boolean) {
    val c = Cp.colors
    val lit = if (readToday) streak.coerceAtMost(7) else streak.coerceAtMost(6)
    val breathe = if (!readToday && LocalTabVisible.current && LocalAnimationsOn.current) {
        androidx.compose.animation.core.rememberInfiniteTransition(label = "today").animateFloat(
            0.35f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "breathe",
        )
    } else null
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until 7) {
            val on = i < lit
            val isToday = !readToday && i == lit
            val turn = remember(i) { listOf(-8f, 5f, -3f, 9f, -6f, 4f, -2f)[i] }
            Box(
                Modifier
                    .size(14.dp)
                    .graphicsLayer {
                        rotationZ = turn
                        alpha = if (isToday) breathe?.value ?: 1f else 1f
                    }
                    .drawBehind {
                        val r = size.minDimension / 2f
                        when {
                            on -> {
                                drawCircle(c.signalLedge, r, center + Offset(1.2.dp.toPx(), 1.2.dp.toPx()))
                                drawCircle(c.accent, r)
                            }
                            isToday -> drawCircle(c.accent, r - 1.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                            else -> drawCircle(c.track, r * 0.55f)
                        }
                    },
            )
        }
    }
}

/**
 * The Index's heading: the word set big in Bodoni sliding into register, how many books and when
 * they last synced, and a little switch between the printed list and a wall of covers.
 */
@Composable
internal fun IndexHeading(subtitle: String, grid: Boolean, onGrid: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = Cp.colors
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 28.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            RegisteredText("Index", CpType.pageTitle.copy(fontSize = 44.sp, lineHeight = 46.sp))
            AnimatedQuiet(subtitle)
        }
        ViewSwitch(grid, onGrid)
    }
}

@Composable
private fun ViewSwitch(grid: Boolean, onGrid: (Boolean) -> Unit) {
    val c = Cp.colors
    val view = androidx.compose.ui.platform.LocalView.current
    val at by animateFloatAsState(if (grid) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "viewAt")
    Box(
        Modifier
            .size(width = 140.dp, height = 40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(c.field),
    ) {
        Box(
            Modifier
                .padding(3.dp)
                .size(width = 67.dp, height = 34.dp)
                .graphicsLayer { translationX = at * 67.dp.toPx() }
                .drawBehind { drawRoundRect(c.signalLedge, topLeft = Offset(1.5.dp.toPx(), 2.dp.toPx()), size = size, cornerRadius = CornerRadius(17.dp.toPx())) }
                .clip(RoundedCornerShape(17.dp))
                .background(c.accent),
        )
        Row(Modifier.fillMaxWidth().height(40.dp)) {
            for ((i, label) in listOf("list", "covers").withIndex()) {
                val on = (i == 1) == grid
                val fg by animateColorAsState(if (on) c.onAccent else c.textMid, label = "viewFg")
                Box(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton, onClickLabel = "Show as $label") {
                            if (!on) { Haptics.tick(view); onGrid(i == 1) }
                        },
                    contentAlignment = Alignment.Center,
                ) { Text(label, style = CpType.mono, color = fg) }
            }
        }
    }
}

/**
 * The shelves as stamps: the chosen one inked in the section's colour over its second ink, the
 * rest outlined, each with its count. They scroll sideways when they don't fit.
 */
@Composable
internal fun ShelfStamps(
    current: ReadStatus,
    counts: Map<ReadStatus, Int>,
    highlightsOn: Boolean,
    onSelect: (ReadStatus) -> Unit,
) {
    val c = Cp.colors
    val view = androidx.compose.ui.platform.LocalView.current
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (t in Shelves.tabs) {
            val on = t == current && !highlightsOn
            val fill by animateColorAsState(if (on) c.accent else c.accent.copy(alpha = 0f), label = "stampFill")
            val fg by animateColorAsState(if (on) c.onAccent else c.textMid, label = "stampFg")
            val pop by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.45f, stiffness = 500f), label = "stampPop")
            val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 6.dp, bottomEnd = 16.dp, bottomStart = 6.dp)
            Row(
                Modifier
                    .graphicsLayer { translationY = -2.dp.toPx() * pop }
                    .drawBehind {
                        if (pop > 0.01f) drawRoundRect(c.signalLedge.copy(alpha = pop.coerceIn(0f, 1f)), topLeft = Offset(2.dp.toPx(), 3.dp.toPx()), size = size, cornerRadius = CornerRadius(12.dp.toPx()))
                    }
                    .clip(shape)
                    .background(fill)
                    .then(if (!on) Modifier.drawBehind {
                        drawRoundRect(c.text.copy(alpha = 0.22f), cornerRadius = CornerRadius(12.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                    } else Modifier)
                    .bouncyClickable(shape, pressedScale = 0.94f, role = Role.Tab, onClickLabel = t.label) { Haptics.tick(view); onSelect(t) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(t.label.lowercase(), style = CpType.mono.copy(fontSize = 13.sp), color = fg)
                Spacer(Modifier.width(6.dp))
                Text((counts[t] ?: 0).toString(), fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = fg)
            }
        }
    }
}

/**
 * One book in the index: a spine of its cover's colour, the cover, the title in Bodoni, the
 * author in italics, a thin bar of progress and a printed line of where it stands. No card: rows
 * are set on the page with a fine rule between them, and lean in a touch when pressed.
 */
@Composable
internal fun IndexRow(book: Book, meta: String, held: Boolean, place: String?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Cp.colors
    val ink = rememberBookInk(book.coverUrl)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .bouncyClickable(RoundedCornerShape(12.dp), pressedScale = 0.985f, onClickLabel = book.displayTitle, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(5.dp).height(66.dp).clip(RoundedCornerShape(topStart = 2.dp, topEnd = 4.dp, bottomEnd = 2.dp, bottomStart = 4.dp)).background(ink))
            Spacer(Modifier.width(10.dp))
            BookCover(book.coverUrl, book.displayTitle, 44.dp, 66.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(book.displayTitle, fontFamily = DisplaySmall, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 21.sp, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                book.author?.let { Text(it, style = CpType.small.copy(fontStyle = FontStyle.Italic), color = c.textMid, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                place?.let { Text(it, style = CpType.small, color = c.accentText, maxLines = 1) }
                Spacer(Modifier.height(8.dp))
                SegmentBar(book.percentage.toFloat(), height = 4.dp, on = ink)
                Spacer(Modifier.height(5.dp))
                AnimatedQuiet(meta, maxLines = 1)
                if (held) {
                    Spacer(Modifier.height(6.dp))
                    Chip("not sent to trackers yet", color = c.quiet, background = c.divider)
                }
            }
        }
        Box(Modifier.padding(start = 85.dp, end = 16.dp).fillMaxWidth().height(1.dp).background(c.text.copy(alpha = 0.1f)))
    }
}

/** A row of the wall of covers: three books side by side, each over its own colour, with its progress under it. */
@Composable
internal fun CoverRow(books: List<Book>, modifier: Modifier = Modifier, onOpen: (String) -> Unit) {
    val c = Cp.colors
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        for (b in books) {
            val ink = rememberBookInk(b.coverUrl)
            Column(Modifier.weight(1f).bouncyClickable(RoundedCornerShape(8.dp), pressedScale = 0.95f, onClickLabel = b.displayTitle) { onOpen(b.document) }) {
                androidx.compose.foundation.layout.BoxWithConstraints(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .drawBehind { drawRoundRect(ink, topLeft = Offset(3.dp.toPx(), 4.dp.toPx()), size = size, cornerRadius = CornerRadius(6.dp.toPx())) },
                ) { BookCover(b.coverUrl, b.displayTitle, maxWidth, maxHeight) }
                Spacer(Modifier.height(10.dp))
                SegmentBar(b.percentage.toFloat(), height = 3.dp, on = ink)
                Spacer(Modifier.height(4.dp))
                Text(b.displayTitle, style = CpType.small, color = c.textMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // Keep the columns even on the last row.
        repeat(3 - books.size) { Spacer(Modifier.weight(1f)) }
    }
}
