package io.github.prithirajn.trackersync.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as BitmapCanvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.sync.LibraryChecks
import io.github.prithirajn.trackersync.sync.ReadingExport
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * On the Activity tab: library checks (books whose details would make your numbers wrong), your
 * rating rubric and how it compares with your stars, a ranked image of the year's top books, and
 * your whole library exported as a spreadsheet.
 */
fun androidx.compose.foundation.lazy.LazyListScope.shelfprintTools(vm: AppViewModel, state: Snapshot) {
    item(key = "tools") { ToolsCard(vm, state) }
    if (state.rubric.isNotEmpty()) item(key = "gut-rubric") { GutVsRubricCard(state) }
}

@Composable
private fun ToolsCard(vm: AppViewModel, state: Snapshot) {
    val c = Cp.colors
    val issues = remember(state.books, state.facts) { LibraryChecks.run(state) }
    var checks by remember { mutableStateOf(false) }
    var rubric by remember { mutableStateOf(false) }
    var top by remember { mutableStateOf(false) }
    var export by remember { mutableStateOf(false) }
    var yearSet by remember { mutableStateOf(false) }
    var collection by remember { mutableStateOf(false) }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp)) {
        Text("Your library", style = CpType.bodyStrong, color = c.text)
        VSpace(8)
        CpCard {
            Column {
                if (CollectionHooks.available) {
                    NavRow(io.github.prithirajn.trackersync.R.drawable.lucide_library_big, "Your collection", CollectionHooks.summary(state)) { collection = true }
                    RowDivider()
                }
                val n = LibraryChecks.count(issues)
                NavRow(
                    io.github.prithirajn.trackersync.R.drawable.lucide_list_checks, "Library checks",
                    if (n == 0) "Everything adds up" else "$n book${if (n == 1) "" else "s"} to look at · ${issues.size} kind${if (issues.size == 1) "" else "s"}",
                ) { checks = true }
                RowDivider()
                NavRow(
                    io.github.prithirajn.trackersync.R.drawable.lucide_star, "Rating rubric",
                    if (state.rubric.isEmpty()) "Score books on what matters to you, beside your stars" else state.rubric.joinToString(" · "),
                ) { rubric = true }
                RowDivider()
                NavRow(io.github.prithirajn.trackersync.R.drawable.lucide_image, "Top books image", "Your year's top 10 or 20, ranked, to share") { top = true }
                RowDivider()
                NavRow(io.github.prithirajn.trackersync.R.drawable.lucide_sparkles, "Year in books", "A set of images: numbers, top reads, months, genre shelves, what you read, gut vs rubric, your shelf") { yearSet = true }
                RowDivider()
                NavRow(io.github.prithirajn.trackersync.R.drawable.lucide_download, "Export reading log", "Every book as a spreadsheet (Excel or CSV)") { export = true }
            }
        }
    }
    if (checks) ChecksSheet(issues) { checks = false }
    if (rubric) RubricSheet(vm, state) { rubric = false }
    if (top) TopBooksSheet(state) { top = false }
    if (export) ExportSheet(state) { export = false }
    if (yearSet) YearSetSheet(state) { yearSet = false }
    if (collection) CollectionSheet(vm, state) { collection = false }
}

// ---- Library checks ----

@Composable
private fun ChecksSheet(issues: List<LibraryChecks.Issue>, onClose: () -> Unit) {
    val c = Cp.colors
    PlainSheet("Library checks", onClose) {
        if (issues.isEmpty()) {
            Quiet("Every finished book has its dates and page count, and nothing looks doubled. Your numbers add up.")
            return@PlainSheet
        }
        Quiet("Books whose details would make your numbers wrong. Tap one to open it and fix it there; nothing is changed by itself.")
        for (i in issues) {
            VSpace(16)
            Text("${i.kind.title} · ${i.books.size}", style = CpType.bodyStrong, color = c.text)
            Quiet(i.kind.why, color = c.faint)
            VSpace(4)
            for (b in i.books.take(40)) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .bouncyClickable(RoundedCornerShape(10.dp), pressedScale = 0.98f, onClickLabel = "Open ${b.displayTitle}") {
                            onClose(); AppActions.pending.value = AppAction.OpenBook(b.document)
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BookCover(b.coverUrl, b.displayTitle, 30.dp, 45.dp)
                    HSpace(10)
                    Column(Modifier.weight(1f)) {
                        Text(b.displayTitle, style = CpType.label, color = c.text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Quiet(i.detail[b.document] ?: b.author.orEmpty(), maxLines = 1)
                    }
                }
            }
            if (i.books.size > 40) Quiet("and ${i.books.size - 40} more", color = c.faint)
        }
    }
}

// ---- Rating rubric ----

private val RUBRIC_IDEAS = listOf("Plot", "Characters", "Writing", "Pacing", "World", "Ending", "Emotion", "Enjoyment", "Romance", "Spice", "Twists", "Re-readability")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RubricSheet(vm: AppViewModel, state: Snapshot, onClose: () -> Unit) {
    val c = Cp.colors
    var custom by remember { mutableStateOf("") }
    PlainSheet("Rating rubric", onClose) {
        Quiet("Score books from 1 to 10 on up to eight things you choose, on each finished book's page. Its average sits beside your stars, and the Activity tab shows where the two disagree.")
        VSpace(12)
        Text("Scoring on", style = CpType.label, color = c.text)
        VSpace(6)
        if (state.rubric.isEmpty()) Quiet("Nothing yet: pick from the ideas below, or add your own.", color = c.faint)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (r in state.rubric) PillButton("$r  ✕", { vm.setRubric(state.rubric - r) }, outlined = false)
        }
        VSpace(14)
        Text("Ideas", style = CpType.label, color = c.text)
        VSpace(6)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (r in RUBRIC_IDEAS.filter { it !in state.rubric }) PillButton("+ $r", { if (state.rubric.size < 8) vm.setRubric(state.rubric + r) })
        }
        VSpace(12)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { CpTextField(custom, { custom = it.take(24) }, "Your own", placeholder = "e.g. Magic system") }
            HSpace(8)
            PillButton("Add", {
                val t = custom.trim()
                if (t.isNotEmpty() && state.rubric.size < 8 && state.rubric.none { it.equals(t, true) }) { vm.setRubric(state.rubric + t); custom = "" }
            }, outlined = false)
        }
        if (state.rubric.size >= 8) { VSpace(6); Quiet("Eight is the most.", color = c.faint) }
    }
}

/** On a finished book's page: its rubric scores (1 to 10 each) and their average. */
@Composable
fun RubricCard(vm: AppViewModel, state: Snapshot, book: Book) {
    if (state.rubric.isEmpty() || (book.status != ReadStatus.FINISHED && book.status != ReadStatus.DNF && book.rating == null)) return
    val c = Cp.colors
    val scores = state.rubricScores[book.document].orEmpty()
    VSpace(16)
    CpCard {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Rubric", style = CpType.bodyStrong, color = c.text, modifier = Modifier.weight(1f))
                val avg = state.rubric.mapNotNull { scores[it] }.takeIf { it.isNotEmpty() }?.average()
                Text(avg?.let { String.format(java.util.Locale.US, "%.1f / 10", it) } ?: "not scored", style = CpType.bodyStrong, color = if (avg != null) c.accentText else c.faint)
            }
            book.rating?.let { Quiet("Your stars: ${if (it % 1.0 == 0.0) it.toInt() else it} of 5 (${(it * 2).let { g -> if (g % 1.0 == 0.0) g.toInt() else g }} of 10)", color = c.faint) }
            for (cat in state.rubric) {
                VSpace(8)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(cat, style = CpType.label, color = c.text, modifier = Modifier.weight(1f))
                    Text(scores[cat]?.toString() ?: "–", style = CpType.label, color = c.textMid)
                }
                var v by remember(book.document, cat, scores[cat]) { mutableStateOf((scores[cat] ?: 0).toFloat()) }
                Slider(
                    value = v, onValueChange = { v = it }, valueRange = 0f..10f, steps = 9,
                    onValueChangeFinished = { vm.setRubricScore(book.document, cat, v.toInt().takeIf { it > 0 }) },
                    colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.divider),
                )
            }
            Quiet("Slide to 0 to leave one unscored.", color = c.faint)
        }
    }
}

/** Your stars against your rubric average, a dot per book, with the books where they disagree most. */
@Composable
private fun GutVsRubricCard(state: Snapshot) {
    val c = Cp.colors
    val pts = remember(state.books, state.rubricScores, state.rubric) {
        state.books.values.mapNotNull { b ->
            val r = b.rating ?: return@mapNotNull null
            val avg = state.rubric.mapNotNull { state.rubricScores[b.document]?.get(it) }.takeIf { it.isNotEmpty() }?.average() ?: return@mapNotNull null
            Triple(b, r * 2, avg)
        }
    }
    if (pts.size < 2) return
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp)) {
        Text("Gut vs rubric", style = CpType.bodyStrong, color = c.text)
        Quiet("Your stars (as of 10) across, your rubric average up. Above the line: the scores rate it higher than your gut did.", color = c.faint)
        VSpace(8)
        CpCard {
            Column(Modifier.padding(16.dp)) {
                val dot = c.accent; val line = c.divider; val axis = c.hairline
                Canvas(Modifier.fillMaxWidth().aspectRatio(1.6f)) {
                    val w = size.width; val h = size.height
                    fun at(x: Double, y: Double) = Offset((x / 10.0 * w).toFloat(), (h - y / 10.0 * h).toFloat())
                    drawLine(axis, Offset(0f, h), Offset(w, h), 2f); drawLine(axis, Offset(0f, 0f), Offset(0f, h), 2f)
                    drawLine(line, at(0.0, 0.0), at(10.0, 10.0), 3f)
                    for ((_, x, y) in pts) {
                        drawCircle(dot, 9f, at(x, y))
                        drawCircle(dot.copy(alpha = 0.35f), 15f, at(x, y), style = Stroke(2f))
                    }
                }
                val gaps = pts.sortedByDescending { kotlin.math.abs(it.third - it.second) }.take(3).filter { kotlin.math.abs(it.third - it.second) >= 1.5 }
                if (gaps.isNotEmpty()) {
                    VSpace(10)
                    for ((b, g, r) in gaps) {
                        Quiet(
                            "“${b.displayTitle}”: " + if (r > g) "scores ${fmt(r)}, but your gut gave it ${fmt(g)}" else "your gut gave it ${fmt(g)}, the scores only ${fmt(r)}",
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

internal fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else String.format(java.util.Locale.US, "%.1f", v)

// ---- Top books image ----

internal enum class TopSize(val label: String, val w: Int, val h: Int) { POST("Post", 1080, 1350), SQUARE("Square", 1080, 1080), STORY("Story", 1080, 1920) }

/** The year's books, best first: by your stars, then your rubric average, then most recently finished. */
internal fun topBooks(s: Snapshot, year: Int, n: Int): List<Book> {
    val z = ZoneId.systemDefault()
    return s.books.values.filter { b ->
        b.status == ReadStatus.FINISHED && (b.finishedAt ?: 0) > 0 &&
            java.time.Instant.ofEpochSecond(b.finishedAt!!).atZone(z).year == year
    }.sortedWith(
        compareByDescending<Book> { it.rating ?: -1.0 }
            .thenByDescending { b -> s.rubric.mapNotNull { s.rubricScores[b.document]?.get(it) }.takeIf { it.isNotEmpty() }?.average() ?: -1.0 }
            .thenByDescending { it.finishedAt ?: 0 }
    ).take(n)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopBooksSheet(state: Snapshot, onClose: () -> Unit) {
    val c = Cp.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val thisYear = remember { LocalDate.now().year }
    var year by remember { mutableStateOf(thisYear) }
    var count by remember { mutableStateOf(10) }
    var ink by remember { mutableStateOf(true) }
    var size by remember { mutableStateOf(TopSize.POST) }
    var handle by remember { mutableStateOf("") }
    var titles by remember { mutableStateOf(true) }
    var stats by remember { mutableStateOf(true) }
    var ranks by remember { mutableStateOf(true) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    val books = remember(state.books, year, count, state.rubricScores) { topBooks(state, year, count) }
    LaunchedEffect(books, ink, size, handle, titles, stats, ranks) {
        kotlinx.coroutines.delay(250)
        bmp = withContext(Dispatchers.IO) { runCatching { renderTop(context, state, books, year, count, ink, size, handle.trim(), titles, stats, ranks) }.getOrNull() }
    }
    PlainSheet("Top books image", onClose) {
        Quiet("Your year's best, ranked by your stars (then your rubric average). Covers, rank numbers and stars, sized for a post, a square or a story.")
        VSpace(10)
        Segmented(listOf(thisYear, thisYear - 1), year, { it.toString() }, { year = it }, Modifier.fillMaxWidth())
        VSpace(8)
        Segmented(listOf(10, 20, 1000), count, { if (it >= 1000) "All" else "Top $it" }, { count = it }, Modifier.fillMaxWidth())
        VSpace(8)
        Segmented(listOf(true, false), ink, { if (it) "Ink" else "Paper" }, { ink = it }, Modifier.fillMaxWidth())
        VSpace(8)
        Segmented(TopSize.entries, size, { it.label }, { size = it }, Modifier.fillMaxWidth())
        VSpace(8)
        CpTextField(handle, { handle = it.take(30) }, "Your handle (optional)", placeholder = "@yourname")
        VSpace(6)
        CpToggle(titles, { titles = it }, "Titles under the covers")
        CpToggle(stats, { stats = it }, "The year's numbers")
        CpToggle(ranks, { ranks = it }, "Rank numbers")
        VSpace(12)
        if (books.isEmpty()) Quiet("No books finished in $year yet.", color = c.faint)
        bmp?.let { b ->
            Image(b.asImageBitmap(), contentDescription = "Top books of $year", modifier = Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height).clip(RoundedCornerShape(14.dp)))
            VSpace(10)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Share", { runCatching { QuoteCard.share(context, b, "My top ${books.size} books of $year") } }, outlined = false)
                PillButton("Save image", { said = if (runCatching { QuoteCard.save(context, b) }.getOrDefault(false)) "Saved to your photos" else "Couldn't save it; use Share instead." })
                PillButton("Threads", { scope.launch { said = runCatching { QuoteCard.postToThreads(context, b, "My top ${books.size} books of $year") }.getOrNull()?.let { "Opened Threads" } ?: "Couldn't open Threads" } })
                PillButton("X", { said = XPost.said(XPost.pictures(context, listOf(b), "My top ${books.size} books of $year"), "your top books") })
            }
            said?.let { VSpace(6); AnimatedQuiet(it, color = c.textMid) }
        }
    }
}

/** Draws the ranked image: a heading, the year's numbers, then covers in a grid, each with its rank and stars. */
internal fun renderTop(
    context: Context, s: Snapshot, books: List<Book>, year: Int, n: Int, ink: Boolean, size: TopSize, handle: String,
    titles: Boolean = true, stats: Boolean = true, ranks: Boolean = true,
): Bitmap {
    val w = size.w; val h = size.h
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cv = BitmapCanvas(bmp)
    val bg = if (ink) 0xFF141210.toInt() else 0xFFF3EDE2.toInt()
    val fg = if (ink) 0xFFF2ECE2.toInt() else 0xFF29241E.toInt()
    val dim = if (ink) 0xFF9C948A.toInt() else 0xFF7A7166.toInt()
    val accent = if (ink) 0xFFE0B25C.toInt() else 0xFFA4552E.toInt()
    cv.drawColor(bg)
    val serif = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    val sans = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val pad = 72f
    var y = pad + 20f
    p.typeface = sans; p.textSize = 30f; p.color = accent; p.letterSpacing = 0.12f
    cv.drawText("$year IN BOOKS", pad, y, p)
    p.letterSpacing = 0f
    y += 78f
    p.typeface = serif; p.textSize = 76f; p.color = fg
    cv.drawText(if (n >= 1000) "Everything I read" else "My top ${books.size}", pad, y, p)
    // The year's numbers, under the heading.
    val z = ZoneId.systemDefault()
    val all = s.books.values.filter { b -> b.status == ReadStatus.FINISHED && (b.finishedAt ?: 0) > 0 && java.time.Instant.ofEpochSecond(b.finishedAt!!).atZone(z).year == year }
    val pages = all.sumOf { it.pageCount ?: 0 }
    val avg = all.mapNotNull { it.rating }.takeIf { it.isNotEmpty() }?.average()
    if (stats) {
        y += 56f
        p.typeface = sans; p.textSize = 32f; p.color = dim
        cv.drawText(listOfNotNull("${all.size} books read", pages.takeIf { it > 0 }?.let { "%,d pages".format(it) }, avg?.let { "average ${fmt(it)} ★" }).joinToString("  ·  "), pad, y, p)
    }
    y += 50f
    // The grid: five across for a post or story, five across (smaller) for a square; rows to fit.
    // Five across for a top 10 or 20; more across for everything you read, so it still fits.
    val cols = when { books.size <= 20 -> 5; books.size <= 42 -> 6; books.size <= 72 -> 8; else -> 10 }
    val rows = ((books.size + cols - 1) / cols).coerceAtLeast(1)
    val gap = 22f
    val footer = 90f
    val availW = w - pad * 2
    val availH = h - y - footer
    val labelH = if (titles) 70f else 34f
    var cw = (availW - gap * (cols - 1)) / cols
    var ch = cw * 1.5f
    if (rows * (ch + labelH) + (rows - 1) * gap > availH) {
        ch = (availH - (rows - 1) * gap) / rows - labelH
        cw = ch / 1.5f
    }
    val gridW = cols * cw + (cols - 1) * gap
    val x0 = (w - gridW) / 2f
    books.forEachIndexed { i, b ->
        val r = i / cols; val col = i % cols
        val x = x0 + col * (cw + gap)
        val top = y + r * (ch + labelH + gap)
        val rect = RectF(x, top, x + cw, top + ch)
        val cover = b.coverUrl?.let { runCatching { io.github.prithirajn.trackersync.ui.CoverCache.load(context, it, (ch * 1.2f).toInt()) }.getOrNull() }
        cv.save()
        val path = android.graphics.Path().apply { addRoundRect(rect, 12f, 12f, android.graphics.Path.Direction.CW) }
        cv.clipPath(path)
        if (cover != null) {
            // Cropped to fill the tile, like a cover on a shelf.
            val sc = maxOf(rect.width() / cover.width, rect.height() / cover.height)
            val dw = cover.width * sc; val dh = cover.height * sc
            cv.drawBitmap(cover, null, RectF(rect.centerX() - dw / 2, rect.centerY() - dh / 2, rect.centerX() + dw / 2, rect.centerY() + dh / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            p.color = if (ink) 0xFF2A2622.toInt() else 0xFFE2D9CA.toInt(); cv.drawRect(rect, p)
            p.color = fg; p.typeface = serif; p.textSize = cw / 7f
            var ty = rect.top + cw / 4
            for (line in wrap(b.displayTitle, p, rect.width() - 16f).take(5)) { cv.drawText(line, rect.left + 8f, ty, p); ty += p.textSize * 1.2f }
        }
        cv.restore()
        // Its rank in a badge at the top left.
        if (ranks) {
            val badge = cw * 0.17f
            p.color = accent; cv.drawCircle(rect.left + badge + 4f, rect.top + badge + 4f, badge, p)
            p.color = bg; p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); p.textSize = badge * 1.1f; p.textAlign = Paint.Align.CENTER
            cv.drawText("${i + 1}", rect.left + badge + 4f, rect.top + badge + 4f + badge * 0.38f, p)
            p.textAlign = Paint.Align.LEFT
        }
        // Stars and title under the cover.
        p.typeface = sans; p.textSize = minOf(26f, cw / 6.5f); p.color = accent
        val stars = b.rating?.let { r -> "★".repeat(r.toInt()) + if (r % 1.0 >= 0.5) "½" else "" } ?: ""
        cv.drawText(stars, rect.left, rect.bottom + p.textSize + 8f, p)
        if (titles) {
            p.color = fg; p.textSize = minOf(22f, cw / 7.5f)
            cv.drawText(ellipsize(b.displayTitle, p, cw), rect.left, rect.bottom + labelH - 10f, p)
        }
    }
    // Footer: your handle and the app's mark.
    p.typeface = sans; p.textSize = 28f; p.color = dim
    if (handle.isNotEmpty()) cv.drawText(handle, pad, h - pad + 10f, p)
    p.textAlign = Paint.Align.RIGHT
    cv.drawText("made with ${io.github.prithirajn.trackersync.Edition.name}", w - pad, h - pad + 10f, p)
    p.textAlign = Paint.Align.LEFT
    return bmp
}

internal fun wrap(text: String, p: Paint, width: Float): List<String> {
    val out = ArrayList<String>(); var line = ""
    for (word in text.split(' ')) {
        val t = if (line.isEmpty()) word else "$line $word"
        if (p.measureText(t) <= width) line = t else { if (line.isNotEmpty()) out += line; line = word }
    }
    if (line.isNotEmpty()) out += line
    return out
}

internal fun ellipsize(text: String, p: Paint, width: Float): String {
    if (p.measureText(text) <= width) return text
    var t = text
    while (t.length > 1 && p.measureText("$t…") > width) t = t.dropLast(1)
    return t.trimEnd() + "…"
}

// ---- Export ----

@Composable
private fun ExportSheet(state: Snapshot, onClose: () -> Unit) {
    val c = Cp.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    val stamp = remember { LocalDate.now().toString() }
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    val saver = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val bytes = pending ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } }.isSuccess }
            said = if (ok) "Saved" else "Couldn't save it"
        }
        pending = null
    }
    fun share(bytes: ByteArray, name: String, type: String) {
        runCatching {
            val dir = File(context.cacheDir, "shares").apply { mkdirs() }
            val f = File(dir, name).apply { writeBytes(bytes) }
            val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".pages", f)
            context.startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "Share the reading log",
            ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { said = "Couldn't share it" }
    }
    PlainSheet("Export reading log", onClose) {
        Quiet("Every book in your library, one a row: title, author, status, start and finish dates, stars, review, pages, series, genres, moods, pace, ISBN, where it came from, how many highlights${if (state.rubric.isNotEmpty()) ", and your rubric scores" else ""}.")
        VSpace(6)
        Quiet("${state.books.size} books.", color = c.faint)
        VSpace(14)
        Text("Excel workbook (.xlsx)", style = CpType.label, color = c.text)
        Quiet("Opens in Excel, Google Sheets, Numbers and LibreOffice, with the header row kept in view.", color = c.faint)
        VSpace(6)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Save", { scope.launch { pending = withContext(Dispatchers.Default) { ReadingExport.xlsx(state) }; saver.launch("readync-reading-log-$stamp.xlsx") } }, outlined = false)
            PillButton("Share", { scope.launch { share(withContext(Dispatchers.Default) { ReadingExport.xlsx(state) }, "readync-reading-log-$stamp.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") } })
        }
        VSpace(14)
        Text("CSV", style = CpType.label, color = c.text)
        Quiet("Plain text any spreadsheet or reading site can read.", color = c.faint)
        VSpace(6)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Save", { scope.launch { pending = withContext(Dispatchers.Default) { ReadingExport.csv(state) }; saver.launch("readync-reading-log-$stamp.csv") } }, outlined = false)
            PillButton("Share", { scope.launch { share(withContext(Dispatchers.Default) { ReadingExport.csv(state) }, "readync-reading-log-$stamp.csv", "text/csv") } })
        }
        said?.let { VSpace(8); AnimatedQuiet(it, color = c.textMid) }
    }
}
