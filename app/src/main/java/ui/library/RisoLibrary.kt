package io.github.prithirajn.readync.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.luminance
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
        val desk = androidx.compose.foundation.lazy.rememberLazyListState()
        ScrollTicks(desk)
        LazyRow(
            state = desk,
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
                .liftable(maxTilt = 10f, lift = 1.06f)
                .size(108.dp, 162.dp)
                .drawBehind { drawRoundRect(ink, topLeft = Offset(4.dp.toPx(), 5.dp.toPx()), size = size, cornerRadius = CornerRadius(6.dp.toPx())) },
        ) { BookCover(book.coverUrl, book.displayTitle, 108.dp, 162.dp) }
        Spacer(Modifier.height(12.dp))
        SegmentBar(book.percentage.toFloat(), height = 4.dp, on = ink)
        Spacer(Modifier.height(6.dp))
        Text(book.displayTitle.wholeWords(), fontFamily = DisplaySmall, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 17.sp, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                        // (the stamps show this week, Monday to Sunday; the figure is the whole run)
                        fontFamily = Display, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = c.text,
                    )
                    if (!readToday) Text(if (streak >= 1) "read a page today to keep it going" else "read a page today to start one", style = CpType.mono, color = c.quiet)
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

/** This week, Monday to Sunday, as ink stamps; today an empty ring that blinks slowly while it's still to do. */
@Composable
private fun Stamps(streak: Int, readToday: Boolean) {
    val c = Cp.colors
    // This week, Monday to Sunday: a stamp on each day of the run so far, today's ring blinking slowly until you read.
    val todayIdx = java.time.LocalDate.now().dayOfWeek.value - 1
    val blink = if (!readToday && LocalTabVisible.current && LocalAnimationsOn.current) {
        androidx.compose.animation.core.rememberInfiniteTransition(label = "today").animateFloat(
            0.12f, 1f,
            androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(1500, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "blink",
        )
    } else null
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until 7) {
            val back = todayIdx - i
            val on = when {
                back < 0 -> false
                back == 0 -> readToday
                readToday -> back < streak
                else -> back <= streak
            }
            val isToday = back == 0 && !readToday
            val future = back < 0
            val turn = remember(i) { listOf(-8f, 5f, -3f, 9f, -6f, 4f, -2f)[i] }
            Box(
                Modifier
                    .size(14.dp)
                    .graphicsLayer {
                        rotationZ = turn
                        alpha = when {
                            isToday -> blink?.value ?: 1f
                            future -> 0.45f
                            else -> 1f
                        }
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
                        if (pop > 0.01f) drawShape(shape, c.signalLedge.copy(alpha = pop.coerceIn(0f, 1f)), Offset(2.dp.toPx(), 3.dp.toPx()))
                    }
                    .clip(shape)
                    .background(fill)
                    // Off: the same cut-cornered stamp, as a faint tint inside an even inked edge (the
                    // edge used to be a plain rounded box clipped by this shape, so it thinned and broke
                    // at two corners).
                    .then(if (!on) Modifier.background(c.text.copy(alpha = 0.04f)).border(1.5.dp, c.text.copy(alpha = 0.2f), shape) else Modifier)
                    .bouncyClickable(shape, pressedScale = 0.94f, role = Role.Tab, onClickLabel = t.label) { Haptics.tick(view); onSelect(t) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (t == ReadStatus.DNF) "dnf" else t.label.lowercase(), style = CpType.mono.copy(fontSize = 13.sp), color = fg, modifier = Modifier.alignByBaseline())
                Spacer(Modifier.width(7.dp))
                // The count in the big figures' face, outlined like them, so a 1 doesn't read as a
                // Roman numeral and sits on the label's line.
                FigureText((counts[t] ?: 0).toString(), CpType.screenSmall.copy(fontSize = 16.sp, lineHeight = 18.sp), fg, Modifier.alignByBaseline())
            }
        }
    }
}

/**
 * One book in the index, as a printed slip: a wash of the cover's own colour, the cover tilted a
 * touch over a block of that ink (as on the book's page), the title in Bodoni over the author in
 * italics, little inked stamps for where it stands and where it came from, and the percentage set
 * large at the right with the time since you last read under it. Progress runs as a bar of the
 * same ink along the foot of the slip.
 */
@Composable
internal fun IndexRow(
    book: Book, status: String, lastRead: String, where: String?, held: Boolean, place: String?,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    val c = Cp.colors
    val ink = rememberBookInk(book.coverUrl)
    val shape = RoundedCornerShape(topStart = 18.dp, topEnd = 8.dp, bottomEnd = 18.dp, bottomStart = 8.dp)
    // Slightly different tilt per book, so the column of covers reads as placed by hand.
    val tilt = remember(book.document) { ((book.document.hashCode() % 5) - 2) * 0.9f }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(shape)
            .background(ink.copy(alpha = 0.10f))
            .drawBehind { drawShape(shape, ink.copy(alpha = 0.28f), stroke = 1.dp.toPx()) }
            .bouncyClickable(shape, pressedScale = 0.985f, onClickLabel = book.displayTitle, onClick = onClick),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .graphicsLayer { rotationZ = tilt }
                    .size(52.dp, 78.dp)
                    .drawBehind { drawRoundRect(ink, topLeft = Offset(4.dp.toPx(), 5.dp.toPx()), size = size, cornerRadius = CornerRadius(6.dp.toPx())) },
            ) { BookCover(book.coverUrl, book.displayTitle, 52.dp, 78.dp) }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(book.displayTitle.wholeWords(), fontFamily = DisplaySmall, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 21.sp, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                book.author?.let { Text(it, style = CpType.small.copy(fontStyle = FontStyle.Italic), color = c.textMid, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                place?.let { Text(it, style = CpType.small, color = c.accentText, maxLines = 1) }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SlipStamp(status, ink, filled = true)
                    where?.let { SlipStamp(it, ink, filled = false) }
                    if (held) SlipStamp("not sent yet", c.quiet, filled = false)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.Bottom) {
                    // Literata's figures, outlined (as the ledger's): Bodoni's hairline 4 read as a 1.
                    FigureText(book.percent.toString(), CpType.screenNumber.copy(fontSize = 30.sp, lineHeight = 30.sp), c.text)
                    Text("%", style = CpType.small, color = c.accentText, modifier = Modifier.padding(start = 1.dp, bottom = 3.dp))
                }
                Text(lastRead, style = CpType.mono, color = c.quiet, maxLines = 1)
            }
        }
        // Progress along the foot of the slip, in the book's ink.
        Box(Modifier.fillMaxWidth().height(4.dp).background(ink.copy(alpha = 0.16f))) {
            Box(Modifier.fillMaxWidth(book.percentage.toFloat().coerceIn(0f, 1f)).height(4.dp).background(ink))
        }
    }
}

/** A small inked stamp on a slip: filled with the book's ink, or just its outline. */
@Composable
private fun SlipStamp(text: String, ink: androidx.compose.ui.graphics.Color, filled: Boolean) {
    val c = Cp.colors
    Text(
        text.lowercase(),
        style = CpType.mono.copy(fontSize = 11.sp),
        color = if (filled) (if (ink.luminance() > 0.5f) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White) else c.textMid,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .then(if (filled) Modifier.background(ink) else Modifier.drawBehind {
                drawRoundRect(ink.copy(alpha = 0.6f), cornerRadius = CornerRadius(6.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            })
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
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
