package io.github.prithirajn.trackersync.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import android.graphics.Canvas as BitmapCanvas

/**
 * "Year in books": a set of 4:5 pictures for one year, in the paper or ink look: your numbers, top
 * reads, month by month, genre shelves, what you read (genres, moods, tropes), gut vs rubric, and
 * your shelf. Reorder them, leave some out, then share them together or save them as one ZIP.
 * Also the cover card for one book: its cover large, your stars, your rubric and your review.
 */

private const val W = 1080
private const val H = 1350

private class Look(ink: Boolean) {
    val bg = if (ink) 0xFF141210.toInt() else 0xFFF3EDE2.toInt()
    val fg = if (ink) 0xFFF2ECE2.toInt() else 0xFF29241E.toInt()
    val dim = if (ink) 0xFF9C948A.toInt() else 0xFF7A7166.toInt()
    val faint = if (ink) 0xFF2B2723.toInt() else 0xFFE3DACB.toInt()
    val accent = if (ink) 0xFFE0B25C.toInt() else 0xFFA4552E.toInt()
    val serif: Typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    val sans: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

private fun yearBooks(s: Snapshot, year: Int): List<Book> {
    val z = ZoneId.systemDefault()
    return s.books.values.filter { b ->
        b.status == ReadStatus.FINISHED && (b.finishedAt ?: 0) > 0 && Instant.ofEpochSecond(b.finishedAt!!).atZone(z).year == year
    }.sortedBy { it.finishedAt }
}

/** A picture with the look's background, its small heading and big title drawn; returns where content can start. */
private fun page(l: Look, year: Int, kicker: String, title: String): Triple<Bitmap, BitmapCanvas, Float> {
    val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    val cv = BitmapCanvas(bmp)
    cv.drawColor(l.bg)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.typeface = l.sans; p.textSize = 30f; p.color = l.accent; p.letterSpacing = 0.12f
    cv.drawText("$year IN BOOKS · ${kicker.uppercase()}", 72f, 112f, p)
    p.letterSpacing = 0f; p.typeface = l.serif; p.textSize = 74f; p.color = l.fg
    var y = 200f
    for (line in wrap(title, p, W - 144f).take(2)) { cv.drawText(line, 72f, y, p); y += 86f }
    footer(cv, l)
    return Triple(bmp, cv, y + 10f)
}

private fun footer(cv: BitmapCanvas, l: Look) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = l.sans; textSize = 26f; color = l.dim; textAlign = Paint.Align.RIGHT }
    cv.drawText("made with ${io.github.prithirajn.trackersync.Edition.name}", W - 72f, H - 60f, p)
}

private fun cover(context: Context, b: Book, px: Int): Bitmap? =
    b.coverUrl?.let { runCatching { CoverCache.load(context, it, px) }.getOrNull() }

private fun drawCover(cv: BitmapCanvas, l: Look, bmp: Bitmap?, b: Book, rect: RectF, radius: Float = 10f) {
    cv.save()
    cv.clipPath(android.graphics.Path().apply { addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW) })
    val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    if (bmp != null) {
        val sc = maxOf(rect.width() / bmp.width, rect.height() / bmp.height)
        val dw = bmp.width * sc; val dh = bmp.height * sc
        cv.drawBitmap(bmp, null, RectF(rect.centerX() - dw / 2, rect.centerY() - dh / 2, rect.centerX() + dw / 2, rect.centerY() + dh / 2), p)
    } else {
        p.color = l.faint; cv.drawRect(rect, p)
        p.color = l.fg; p.typeface = l.serif; p.textSize = (rect.width() / 7f).coerceIn(10f, 40f)
        var ty = rect.top + p.textSize * 1.6f
        for (line in wrap(b.displayTitle, p, rect.width() - 12f).take(4)) { cv.drawText(line, rect.left + 6f, ty, p); ty += p.textSize * 1.2f }
    }
    cv.restore()
}

// ---- the pictures ----

private fun summary(s: Snapshot, year: Int, l: Look): Bitmap {
    val books = yearBooks(s, year)
    val (bmp, cv, top) = page(l, year, "My year", "${books.size} books")
    val z = ZoneId.systemDefault()
    val pages = books.sumOf { it.pageCount ?: 0 }
    val avg = books.mapNotNull { it.rating }.takeIf { it.isNotEmpty() }?.average()
    val before = s.books.values.filter { b -> b.status == ReadStatus.FINISHED && (b.finishedAt ?: 0) > 0 && Instant.ofEpochSecond(b.finishedAt!!).atZone(z).year < year }
        .mapNotNull { it.author?.lowercase() }.toSet()
    val newAuthors = books.mapNotNull { it.author }.distinctBy { it.lowercase() }.count { it.lowercase() !in before }
    val byMonth = books.groupBy { Instant.ofEpochSecond(it.finishedAt!!).atZone(z).monthValue }
    val busiest = byMonth.maxByOrNull { it.value.size }
    val days = if (year == LocalDate.now().year) LocalDate.now().dayOfYear else LocalDate.of(year, 12, 31).dayOfYear
    val dnf = s.books.values.count { b -> b.status == ReadStatus.DNF && b.timestamp > 0 && Instant.ofEpochSecond(b.timestamp).atZone(z).year == year }
    val stats = listOfNotNull(
        "%,d".format(pages) to "pages",
        (if (pages > 0) "${pages / days.coerceAtLeast(1)}" else null)?.let { it to "pages a day" },
        avg?.let { fmt(it) + " ★" to "average rating" },
        "$newAuthors" to "authors new to you",
        busiest?.let { java.time.Month.of(it.key).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()) to "busiest month (${it.value.size})" },
        "$dnf" to "put down",
    )
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var y = top + 40f
    val colW = (W - 144f) / 2
    stats.forEachIndexed { i, (big, small) ->
        val x = 72f + (i % 2) * colW
        if (i % 2 == 0 && i > 0) y += 210f
        p.typeface = l.serif; p.textSize = 76f; p.color = l.fg
        cv.drawText(ellipsize(big, p, colW - 20f), x, y + 70f, p)
        p.typeface = l.sans; p.textSize = 30f; p.color = l.dim
        cv.drawText(small, x, y + 120f, p)
    }
    return bmp
}

private fun months(s: Snapshot, year: Int, l: Look): Bitmap {
    val z = ZoneId.systemDefault()
    val books = yearBooks(s, year)
    val counts = (1..12).map { m -> books.count { Instant.ofEpochSecond(it.finishedAt!!).atZone(z).monthValue == m } }
    val pages = (1..12).map { m -> books.filter { Instant.ofEpochSecond(it.finishedAt!!).atZone(z).monthValue == m }.sumOf { it.pageCount ?: 0 } }
    val (bmp, cv, top) = page(l, year, "Month by month", "${counts.maxOrNull() ?: 0} in my biggest month")
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val chartTop = top + 60f; val chartBottom = H - 220f
    val max = (counts.maxOrNull() ?: 1).coerceAtLeast(1)
    val slot = (W - 144f) / 12
    for (i in 0 until 12) {
        val hgt = (chartBottom - chartTop) * counts[i] / max
        val x = 72f + i * slot
        p.color = if (counts[i] == max && max > 0) l.accent else l.fg
        cv.drawRoundRect(RectF(x + 8f, chartBottom - hgt, x + slot - 8f, chartBottom), 10f, 10f, p)
        p.typeface = l.sans; p.textSize = 30f; p.textAlign = Paint.Align.CENTER
        if (counts[i] > 0) { p.color = l.fg; cv.drawText("${counts[i]}", x + slot / 2, chartBottom - hgt - 14f, p) }
        p.color = l.dim
        cv.drawText(java.time.Month.of(i + 1).getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()), x + slot / 2, chartBottom + 44f, p)
        p.textAlign = Paint.Align.LEFT
    }
    val best = pages.withIndex().maxByOrNull { it.value }
    if (best != null && best.value > 0) {
        p.typeface = l.sans; p.textSize = 30f; p.color = l.dim
        cv.drawText("Most pages: ${java.time.Month.of(best.index + 1).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())}, %,d".format(best.value), 72f, chartBottom + 100f, p)
    }
    return bmp
}

/** Books grouped onto a shelf per genre (the five you read most), covers side by side. */
private fun genreShelves(context: Context, s: Snapshot, year: Int, l: Look): Bitmap {
    val books = yearBooks(s, year)
    val byGenre = books.groupBy { s.facts[it.document]?.genres?.firstOrNull() ?: it.titleTags.firstOrNull() ?: "Other" }
        .entries.sortedByDescending { it.value.size }
    val shelves = byGenre.filter { it.key != "Other" }.take(5).ifEmpty { byGenre.take(1) }
    val (bmp, cv, top) = page(l, year, "Genre shelves", shelves.firstOrNull()?.let { "Mostly ${it.key.lowercase()}" } ?: "My shelves")
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val avail = H - top - 140f
    val rowH = (avail / shelves.size.coerceAtLeast(1)).coerceAtMost(230f)
    var y = top + 20f
    for ((genre, list) in shelves) {
        p.typeface = l.sans; p.textSize = 30f; p.color = l.fg
        cv.drawText("$genre · ${list.size}", 72f, y + 30f, p)
        val ch = rowH - 70f; val cw = ch / 1.5f
        var x = 72f
        for (b in list) {
            if (x + cw > W - 72f) { p.color = l.dim; cv.drawText("+${list.size - list.indexOf(b)}", x, y + 50f + ch / 2, p); break }
            drawCover(cv, l, cover(context, b, (ch * 1.3f).toInt()), b, RectF(x, y + 44f, x + cw, y + 44f + ch), 6f)
            x += cw + 8f
        }
        // The shelf under the books.
        p.color = l.faint; cv.drawRect(72f, y + 44f + ch + 2f, W - 72f, y + 44f + ch + 12f, p)
        y += rowH
    }
    return bmp
}

/** Your genres, moods and tropes as bars. */
private fun breakdown(s: Snapshot, year: Int, l: Look): Bitmap {
    val books = yearBooks(s, year)
    fun top(of: (io.github.prithirajn.trackersync.data.BookFacts) -> List<String>, n: Int) = books.flatMap { b -> s.facts[b.document]?.let(of).orEmpty() }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(n)
    val genres = top({ it.genres }, 5); val moods = top({ it.moods }, 4); val tropes = top({ it.tropes }, 4)
    val (bmp, cv, startY) = page(l, year, "What I read", genres.firstOrNull()?.let { "${it.key}, mostly" } ?: "What I read")
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var y = startY + 20f
    for ((label, rows) in listOf("Genres" to genres, "Moods" to moods, "Tropes" to tropes)) {
        if (rows.isEmpty()) continue
        p.typeface = l.sans; p.textSize = 28f; p.color = l.accent; p.letterSpacing = 0.1f
        cv.drawText(label.uppercase(), 72f, y + 28f, p); p.letterSpacing = 0f
        y += 50f
        val max = rows.first().value.toFloat()
        for ((k, v) in rows) {
            val bw = (W - 144f - 260f) * v / max
            p.color = l.faint; cv.drawRoundRect(RectF(72f, y, W - 72f - 260f, y + 34f), 8f, 8f, p)
            p.color = l.fg; cv.drawRoundRect(RectF(72f, y, 72f + bw, y + 34f), 8f, 8f, p)
            p.typeface = l.sans; p.textSize = 28f; p.color = l.fg
            cv.drawText(ellipsize("$k  $v", p, 250f), W - 72f - 240f, y + 27f, p)
            y += 50f
        }
        y += 24f
    }
    if (genres.isEmpty() && moods.isEmpty()) {
        p.typeface = l.sans; p.textSize = 32f; p.color = l.dim
        cv.drawText("Genres and moods fill in as your books' details come in.", 72f, startY + 60f, p)
    }
    return bmp
}

/** Stars against rubric average, as a picture (only with a rubric and scored books). */
private fun gutVsRubric(s: Snapshot, year: Int, l: Look): Bitmap? {
    val pts = yearBooks(s, year).mapNotNull { b ->
        val r = b.rating ?: return@mapNotNull null
        val avg = s.rubric.mapNotNull { s.rubricScores[b.document]?.get(it) }.takeIf { it.isNotEmpty() }?.average() ?: return@mapNotNull null
        Triple(b, r * 2, avg)
    }
    if (pts.size < 2) return null
    val (bmp, cv, top) = page(l, year, "Gut vs rubric", "My stars against my scores")
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val left = 120f; val right = W - 72f; val t = top + 40f; val b = t + (right - left) * 0.8f
    p.color = l.faint; p.strokeWidth = 3f
    cv.drawLine(left, b, right, b, p); cv.drawLine(left, t, left, b, p)
    p.color = l.dim; cv.drawLine(left, b, right, t, p)
    fun at(x: Double, y: Double) = (left + (x / 10.0 * (right - left)).toFloat()) to (b - (y / 10.0 * (b - t)).toFloat())
    p.color = l.accent
    for ((_, x, y) in pts) { val (px, py) = at(x, y); cv.drawCircle(px, py, 14f, p) }
    p.typeface = l.sans; p.textSize = 28f; p.color = l.dim
    cv.drawText("stars (of 10) →", right - 220f, b + 44f, p)
    cv.save(); cv.rotate(-90f, left - 40f, t + 200f); cv.drawText("rubric →", left - 40f, t + 200f, p); cv.restore()
    var y = b + 110f
    for ((bk, g, r) in pts.sortedByDescending { kotlin.math.abs(it.third - it.second) }.take(2)) {
        p.color = l.fg; p.textSize = 30f
        cv.drawText(ellipsize("“${bk.displayTitle}”: gut ${fmt(g)}, scores ${fmt(r)}", p, W - 144f), 72f, y, p); y += 50f
    }
    return bmp
}

private enum class Part(val label: String) {
    SUMMARY("My numbers"), TOP("Top reads"), MONTHS("Month by month"), SHELVES("Genre shelves"),
    BREAKDOWN("What I read"), GUT("Gut vs rubric"), SHELF("My shelf"),
}

private fun render(context: Context, s: Snapshot, year: Int, part: Part, ink: Boolean): Bitmap? {
    val l = Look(ink)
    return when (part) {
        Part.SUMMARY -> summary(s, year, l)
        Part.TOP -> renderTop(context, s, topBooks(s, year, 10), year, 10, ink, TopSize.POST, "")
        Part.MONTHS -> months(s, year, l)
        Part.SHELVES -> genreShelves(context, s, year, l)
        Part.BREAKDOWN -> breakdown(s, year, l)
        Part.GUT -> gutVsRubric(s, year, l)
        Part.SHELF -> ShelfPoster.draw(context, ShelfPoster.finished(s, year), year, ShelfPoster.Style.SHELF, W, H)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun YearSetSheet(state: Snapshot, onClose: () -> Unit) {
    val c = Cp.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val thisYear = remember { LocalDate.now().year }
    var year by remember { mutableStateOf(thisYear) }
    var ink by remember { mutableStateOf(true) }
    val order = remember { mutableStateListOf(*Part.entries.toTypedArray()) }
    val off = remember { mutableStateListOf<Part>() }
    var pics by remember { mutableStateOf<Map<Part, Bitmap?>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(year, ink) {
        busy = true
        pics = emptyMap()
        val out = LinkedHashMap<Part, Bitmap?>()
        for (p in Part.entries) {
            out[p] = withContext(Dispatchers.IO) { runCatching { render(context, state, year, p, ink) }.getOrNull() }
            pics = LinkedHashMap(out)
        }
        busy = false
    }
    val chosen = order.filter { it !in off && pics[it] != null }.mapNotNull { pics[it] }
    val zipSaver = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { zip(chosen, it) } }.isSuccess }
            said = if (ok) "Saved ${chosen.size} pictures" else "Couldn't save them"
        }
    }
    PlainSheet("Year in books", onClose) {
        Quiet("A set of pictures for one year. Move them up or down, leave out the ones you don't want, then share them together or save them as one ZIP.")
        VSpace(10)
        Segmented(listOf(thisYear, thisYear - 1), year, { it.toString() }, { year = it }, Modifier.fillMaxWidth())
        VSpace(8)
        Segmented(listOf(true, false), ink, { if (it) "Ink" else "Paper" }, { ink = it }, Modifier.fillMaxWidth())
        VSpace(12)
        for ((i, part) in order.toList().withIndex()) {
            val pic = pics[part]
            val missing = part in pics && pic == null
            val on = part !in off && !missing
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (pic != null) Image(pic.asImageBitmap(), part.label, Modifier.width(72.dp).aspectRatio(0.8f).clip(RoundedCornerShape(8.dp)).alpha(if (on) 1f else 0.35f))
                else androidx.compose.foundation.layout.Box(Modifier.width(72.dp).aspectRatio(0.8f).clip(RoundedCornerShape(8.dp)).background(c.divider), contentAlignment = Alignment.Center) {
                    if (!missing) CircularProgressIndicator(Modifier.width(18.dp), color = c.accent, strokeWidth = 2.dp)
                }
                HSpace(12)
                Column(Modifier.weight(1f)) {
                    Text(part.label, style = CpType.bodyStrong, color = if (on) c.text else c.faint)
                    Quiet(if (missing) (if (part == Part.GUT) "Needs a rubric and a few scored books" else "Nothing for this year") else if (on) "In the set" else "Left out", maxLines = 1)
                }
                PillButton("↑", { if (i > 0) { order.removeAt(i); order.add(i - 1, part) } })
                HSpace(4)
                PillButton("↓", { if (i < order.lastIndex) { order.removeAt(i); order.add(i + 1, part) } })
                HSpace(4)
                if (!missing) PillButton(if (part in off) "Add" else "Hide", { if (part in off) off.remove(part) else off.add(part) })
            }
        }
        VSpace(12)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Share ${chosen.size}", { if (chosen.isNotEmpty()) shareAll(context, chosen, "My $year in books") }, outlined = false)
            PillButton("Save as ZIP", { if (chosen.isNotEmpty()) zipSaver.launch("readync-$year-in-books.zip") })
            PillButton("Threads", { if (chosen.isNotEmpty()) said = runCatching { QuoteCard.postToThreads(context, chosen.first(), "My $year in books") }.getOrNull()?.let { "Opened Threads with the first picture" } ?: "Couldn't open Threads" })
            PillButton("X", { if (chosen.isNotEmpty()) said = XPost.said(XPost.pictures(context, chosen.take(4), "My $year in books"), "your year") })
        }
        if (busy) { VSpace(6); Quiet("Drawing the pictures…", color = c.faint) }
        said?.let { VSpace(6); AnimatedQuiet(it, color = c.textMid) }
    }
}

private fun zip(pics: List<Bitmap>, out: java.io.OutputStream) {
    ZipOutputStream(out).use { z ->
        pics.forEachIndexed { i, b ->
            z.putNextEntry(ZipEntry("%02d.png".format(i + 1)))
            b.compress(Bitmap.CompressFormat.PNG, 100, z)
            z.closeEntry()
        }
    }
}

private fun shareAll(context: Context, pics: List<Bitmap>, text: String) {
    runCatching {
        val uris = ArrayList(pics.map { QuoteCard.cacheUri(context, it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/png")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share your year").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

// ---- the cover card (one book) ----

/** One book as a card: its cover large, title and author, your stars, your rubric average and your review. */
private fun coverCard(context: Context, s: Snapshot, b: Book, ink: Boolean, size: TopSize, withReview: Boolean): Bitmap {
    val l = Look(ink)
    val w = size.w; val h = size.h
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cv = BitmapCanvas(bmp)
    cv.drawColor(l.bg)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val coverH = when (size) { TopSize.STORY -> h * 0.46f; TopSize.SQUARE -> h * 0.55f; else -> h * 0.5f }
    val coverW = coverH / 1.5f
    val rect = RectF((w - coverW) / 2, 90f, (w + coverW) / 2, 90f + coverH)
    // A soft shadow under the cover.
    p.color = 0x55000000; cv.drawRoundRect(RectF(rect.left + 10f, rect.top + 16f, rect.right + 10f, rect.bottom + 16f), 16f, 16f, p)
    drawCover(cv, l, cover(context, b, (coverH * 1.2f).toInt()), b, rect, 14f)
    var y = rect.bottom + 90f
    p.typeface = l.serif; p.textSize = 60f; p.color = l.fg; p.textAlign = Paint.Align.CENTER
    for (line in wrap(b.displayTitle, p, w - 160f).take(2)) { cv.drawText(line, w / 2f, y, p); y += 70f }
    p.typeface = l.sans; p.textSize = 34f; p.color = l.dim
    b.author?.let { cv.drawText(it, w / 2f, y, p); y += 60f }
    val avg = s.rubric.mapNotNull { s.rubricScores[b.document]?.get(it) }.takeIf { it.isNotEmpty() }?.average()
    val line = listOfNotNull(
        b.rating?.let { r -> "★".repeat(r.toInt()) + if (r % 1.0 >= 0.5) "½" else "" },
        avg?.let { "rubric ${fmt(it)}/10" },
    ).joinToString("   ")
    if (line.isNotEmpty()) { p.color = l.accent; p.textSize = 40f; cv.drawText(line, w / 2f, y, p); y += 70f }
    val review = b.review?.let { QuoteCard.cleanReview(it) }?.trim().orEmpty()
    if (withReview && review.isNotEmpty()) {
        p.typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC); p.textSize = 34f; p.color = l.fg
        val room = ((h - 130f - y) / 46f).toInt().coerceAtLeast(1)
        val lines = wrap(review.replace(Regex("\\s+"), " "), p, w - 180f)
        lines.take(room).forEachIndexed { i, ln ->
            cv.drawText(if (i == room - 1 && lines.size > room) ellipsize("$ln …", p, w - 180f) else ln, w / 2f, y, p); y += 46f
        }
    }
    p.textAlign = Paint.Align.LEFT
    footerAt(cv, l, w, h)
    return bmp
}

private fun footerAt(cv: BitmapCanvas, l: Look, w: Int, h: Int) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = l.sans; textSize = 26f; color = l.dim; textAlign = Paint.Align.RIGHT }
    cv.drawText("made with ${io.github.prithirajn.trackersync.Edition.name}", w - 60f, h - 50f, p)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoverCardSheet(state: Snapshot, book: Book, onClose: () -> Unit) {
    val c = Cp.colors
    val context = LocalContext.current
    var ink by remember { mutableStateOf(true) }
    var size by remember { mutableStateOf(TopSize.POST) }
    var review by remember { mutableStateOf(!book.review.isNullOrBlank()) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ink, size, review) {
        bmp = withContext(Dispatchers.IO) { runCatching { coverCard(context, state, book, ink, size, review) }.getOrNull() }
    }
    PlainSheet("Cover card", onClose) {
        Quiet("The book's cover large, with your stars${if (state.rubric.isNotEmpty()) ", your rubric" else ""} and your review.")
        VSpace(10)
        Segmented(listOf(true, false), ink, { if (it) "Ink" else "Paper" }, { ink = it }, Modifier.fillMaxWidth())
        VSpace(8)
        Segmented(TopSize.entries, size, { it.label }, { size = it }, Modifier.fillMaxWidth())
        if (!book.review.isNullOrBlank()) { VSpace(6); CpToggle(review, { review = it }, "Show my review") }
        VSpace(12)
        bmp?.let { b ->
            Image(b.asImageBitmap(), book.displayTitle, Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height).clip(RoundedCornerShape(14.dp)))
            VSpace(10)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Share", { runCatching { QuoteCard.share(context, b, book.displayTitle) } }, outlined = false)
                PillButton("Save image", { said = if (runCatching { QuoteCard.save(context, b) }.getOrDefault(false)) "Saved to your photos" else "Couldn't save it; use Share instead." })
                PillButton("Threads", { said = runCatching { QuoteCard.postToThreads(context, b, book.displayTitle) }.getOrNull()?.let { "Opened Threads" } ?: "Couldn't open Threads" })
                PillButton("X", { said = XPost.said(XPost.pictures(context, listOf(b), book.displayTitle), "the card") })
            }
            said?.let { VSpace(6); AnimatedQuiet(it, color = c.textMid) }
        } ?: CircularProgressIndicator(Modifier.width(22.dp), color = c.accent, strokeWidth = 2.dp)
    }
}
