package io.github.prithirajn.trackersync.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpType
import io.github.prithirajn.trackersync.ui.theme.DarkGadget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * A year's finished books as one picture: spines on wooden shelves (each the colour of its cover,
 * as thick as the book is long), or a wall of covers. As a phone wallpaper or a post.
 */
object ShelfPoster {
    enum class Style(val label: String) { SHELF("Shelf"), WALL("Covers") }
    enum class Shape(val label: String) { WALLPAPER("Wallpaper"), POST("Post") }

    /** The years you've finished books in, newest first. */
    fun years(s: Snapshot, zone: ZoneId = ZoneId.systemDefault()): List<Int> =
        finished(s, null, zone).mapNotNull { yearOf(it, zone) }.distinct().sortedDescending()

    private fun yearOf(b: Book, zone: ZoneId) = (b.finishedAt ?: b.timestamp).takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).atZone(zone).year }

    fun finished(s: Snapshot, year: Int?, zone: ZoneId = ZoneId.systemDefault()): List<Book> =
        s.books.values.filter { it.status == ReadStatus.FINISHED && !it.held && (year == null || yearOf(it, zone) == year) }
            .sortedBy { it.finishedAt ?: it.timestamp }

    fun draw(context: Context, books: List<Book>, year: Int, style: Style, w: Int, h: Int): Bitmap {
        val f = Ink.faces(context)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val covers = books.map { b -> b.coverUrl?.let { runCatching { CoverCache.load(context, it, 600) }.getOrNull() } }
        val tint = covers.lastOrNull { it != null }?.let { Ink.coverColor(it) } ?: Ink.SIGNAL
        Ink.wallpaper(cv, w, h, tint, 0.08f)
        val pages = books.sumOf { it.pageCount ?: 0 }
        // The heading.
        val top = h * 0.09f
        Ink.line(cv, "$year", 80f, top, f.mono, 34f, Ink.DIM, w - 160f, spacing = 0.2f)
        Ink.line(cv, "My shelf", 80f, top + 110f, f.interBold, 104f, Ink.TEXT, w - 160f)
        Ink.line(cv, "${books.size} book${if (books.size == 1) "" else "s"}" + (if (pages > 0) " · " + java.text.NumberFormat.getIntegerInstance().format(pages) + " pages" else ""), 84f, top + 180f, f.inter, 38f, Ink.SIGNAL, w - 160f)
        val area = RectF(60f, top + 260f, w - 60f, h - 150f)
        if (style == Style.SHELF) shelves(cv, books, covers, area, f) else wall(cv, books, covers, area, f, tint)
        Ink.mark(cv, f, w / 2f, h - 70f)
        return bmp
    }

    private fun shelves(cv: Canvas, books: List<Book>, covers: List<Bitmap?>, area: RectF, f: Ink.Faces) {
        // Spine widths from page counts; scaled so all the books fill the shelves at a good size.
        val raw = books.map { b -> (28f + ((b.pageCount ?: 320).coerceIn(80, 1200) / 1200f) * 52f) }
        val heights = books.map { b -> 0.78f + ((b.document.hashCode() and 0xFF) / 255f) * 0.22f }
        val totalW = raw.sum() + books.size * 4f
        var rows = 1
        while (true) {
            val rowH = area.height() / rows
            val spineH = rowH - 40f
            val scale = (spineH / 300f).coerceAtMost(2.4f)
            if (totalW * scale <= area.width() * rows * 0.98f || rows >= 12) break
            rows++
        }
        val rowH = area.height() / rows
        val scale = ((rowH - 40f) / 300f).coerceAtMost(2.4f)
        // Fill as many rows as the books need, centred down the space.
        val perRowW = area.width()
        var row = 0
        var x = area.left
        val wood = Paint(Paint.ANTI_ALIAS_FLAG)
        fun plank(r: Int) {
            val y = area.top + (r + 1) * rowH - 22f
            wood.shader = LinearGradient(0f, y, 0f, y + 26f, intArrayOf(0xFF8A5A36.toInt(), 0xFF5E3B22.toInt()), null, Shader.TileMode.CLAMP)
            cv.drawRoundRect(RectF(area.left - 20f, y, area.right + 20f, y + 26f), 6f, 6f, wood)
            cv.drawRect(RectF(area.left - 20f, y + 26f, area.right + 20f, y + 40f), Paint().apply {
                shader = LinearGradient(0f, y + 26f, 0f, y + 40f, 0x66000000, 0x00000000, Shader.TileMode.CLAMP)
            })
        }
        plank(0)
        books.forEachIndexed { i, b ->
            val sw = raw[i] * scale
            if (x + sw > area.left + perRowW && row < rows - 1) { row++; x = area.left; plank(row) }
            val base = area.top + (row + 1) * rowH - 22f
            val sh = 300f * scale * heights[i]
            val r = RectF(x, base - sh, x + sw, base)
            val col = covers[i]?.let { Ink.coverColor(it) } ?: Ink.shade(Ink.SIGNAL, 0.7f)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = LinearGradient(r.left, 0f, r.right, 0f, intArrayOf(Ink.shade(col, 0.7f), col, Ink.lighten(col, 0.12f), Ink.shade(col, 0.6f)), floatArrayOf(0f, 0.25f, 0.6f, 1f), Shader.TileMode.CLAMP)
            cv.drawRoundRect(r, 4f, 4f, p)
            // Bands near the head and foot, as on a cloth binding.
            val band = Paint().apply { color = Ink.alpha(if (Ink.luminance(col) > 0.55) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), 70) }
            cv.drawRect(r.left + 3f, r.top + sh * 0.07f, r.right - 3f, r.top + sh * 0.07f + 3f * scale, band)
            cv.drawRect(r.left + 3f, r.bottom - sh * 0.09f, r.right - 3f, r.bottom - sh * 0.09f + 3f * scale, band)
            // The title up the spine.
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = f.interBold; textSize = (sw * 0.42f).coerceIn(10f, 34f)
                color = if (Ink.luminance(col) > 0.55) 0xFF1C1A17.toInt() else 0xFFF6F2EA.toInt()
            }
            val t = TextUtils.ellipsize(QuoteCard.shortTitle(b.displayTitle).uppercase(), tp, sh * 0.72f, TextUtils.TruncateAt.END).toString()
            cv.save(); cv.rotate(-90f, r.centerX(), r.centerY())
            cv.drawText(t, r.centerX() - tp.measureText(t) / 2, r.centerY() + tp.textSize * 0.35f, tp)
            cv.restore()
            x += sw + 4f * scale
        }
    }

    private fun wall(cv: Canvas, books: List<Book>, covers: List<Bitmap?>, area: RectF, f: Ink.Faces, tint: Int) {
        val n = books.size.coerceAtLeast(1)
        // The most columns that still let every cover fit, at a book's shape.
        var cols = 1
        while (true) {
            val cw = (area.width() - (cols - 1) * 18f) / cols
            val rows = (n + cols - 1) / cols
            if (rows * (cw * 1.5f + 18f) <= area.height() || cols >= 12) break
            cols++
        }
        val cw = (area.width() - (cols - 1) * 18f) / cols
        val ch = cw * 1.5f
        val rows = (n + cols - 1) / cols
        val y0 = area.top + (area.height() - (rows * ch + (rows - 1) * 18f)).coerceAtLeast(0f) / 2
        books.forEachIndexed { i, b ->
            val x = area.left + (i % cols) * (cw + 18f)
            val y = y0 + (i / cols) * (ch + 18f)
            Ink.drawBook(cv, covers[i], RectF(x, y, x + cw, y + ch), tint, f, b.displayTitle)
        }
    }
}

/** Pick the year, the look and the shape; see the poster; save or share it. */
@Composable
fun ShelfPosterSheet(state: Snapshot, onClose: () -> Unit) {
    val context = LocalContext.current
    val years = remember(state.books) { ShelfPoster.years(state) }
    var year by remember { mutableStateOf(years.firstOrNull() ?: java.time.LocalDate.now().year) }
    var style by remember { mutableStateOf(ShelfPoster.Style.SHELF) }
    var shape by remember { mutableStateOf(ShelfPoster.Shape.WALLPAPER) }
    var said by remember { mutableStateOf<String?>(null) }
    val conf = LocalConfiguration.current
    val ratio = (conf.screenHeightDp.toFloat() / conf.screenWidthDp.coerceAtLeast(1)).coerceIn(1.6f, 2.4f)
    val (w, h) = if (shape == ShelfPoster.Shape.WALLPAPER) 1080 to (1080 * ratio).toInt() else 1080 to 1350
    val books = remember(state.books, year) { ShelfPoster.finished(state, year) }
    val poster by produceState<Bitmap?>(null, books, style, w, h) {
        value = null
        value = withContext(Dispatchers.Default) { runCatching { ShelfPoster.draw(context, books, year, style, w, h) }.getOrNull() }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        DarkGadget {
            val c = Cp.colors
            Column(Modifier.fillMaxSize().background(c.page).windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconCircleButton(io.github.prithirajn.trackersync.R.drawable.lucide_x, "Close", onClose, tint = c.text)
                    Text("Shelf poster", style = CpType.section, color = c.text, modifier = Modifier.padding(start = 12.dp).weight(1f))
                }
                Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Crossfade(poster, animationSpec = tween(300), label = "poster") { p ->
                        if (p == null) {
                            if (books.isEmpty()) Text("No finished books in $year yet.", style = CpType.body, color = c.quiet)
                            else CircularProgressIndicator(Modifier.size(28.dp), color = c.accent, strokeWidth = 2.dp)
                        } else Image(
                            p.asImageBitmap(), "Your $year shelf", contentScale = ContentScale.Fit,
                            modifier = Modifier.aspectRatio(p.width.toFloat() / p.height).shadow(20.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)),
                        )
                    }
                }
                Column(Modifier.padding(horizontal = 12.dp)) {
                    if (years.size > 1) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (y in years) PillButton("$y", { year = y }, outlined = y != year)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Segmented(ShelfPoster.Style.entries, style, { it.label }, { style = it }, Modifier.weight(1f))
                        Segmented(ShelfPoster.Shape.entries, shape, { it.label }, { shape = it }, Modifier.weight(1f))
                    }
                    said?.let { Spacer(Modifier.size(6.dp)); AnimatedQuiet(it, color = c.textMid) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BigButton("Save", {
                            poster?.let { said = if (QuoteCard.save(context, it)) "Saved to Pictures. Set it as your wallpaper from your gallery." else "Couldn't save here; use Share instead." }
                        }, Modifier.weight(1f), filled = false, enabled = poster != null)
                        BigButton("Share", { poster?.let { runCatching { QuoteCard.share(context, it, "My $year shelf: ${books.size} books") } } }, Modifier.weight(1.4f), enabled = poster != null)
                    }
                }
            }
        }
    }
}
