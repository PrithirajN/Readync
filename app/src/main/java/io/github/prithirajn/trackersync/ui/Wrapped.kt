package io.github.prithirajn.trackersync.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.TextPaint
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.sync.Highlights
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpType
import io.github.prithirajn.trackersync.ui.theme.DarkGadget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Your reading over a month or a year, as story slides to tap through and share: the pages and
 * days, your longest streak, the books you finished, the one you flew through, your passage of
 * the period, and everything in one card at the end. Worked out from what the app already keeps.
 */
object Wrapped {
    enum class Period { THIS_MONTH, LAST_MONTH, THIS_YEAR, LAST_YEAR }

    data class Data(
        val title: String,
        val short: String,
        val year: Boolean,
        val days: List<Pair<LocalDate, Int>>,
        val pages: Int,
        /** Of [pages], those counted from the page counts of finished books with no reading days logged (brought in from a history). */
        val fromBooks: Int,
        val minutes: Int,
        val readingDays: Int,
        val longestStreak: Int,
        val favouriteDay: DayOfWeek?,
        val bestDay: Pair<LocalDate, Int>?,
        val finished: List<Book>,
        val started: Int,
        val fastest: Pair<Book, Int>?,
        val topRated: Book?,
        val highlights: Int,
        val passage: Highlights.Item?,
        val topAuthor: Pair<String, Int>?,
    ) {
        val isEmpty: Boolean get() = pages == 0 && finished.isEmpty() && highlights == 0
    }

    fun data(s: Snapshot, period: Period, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Data {
        val (from, to) = when (period) {
            Period.THIS_MONTH -> today.withDayOfMonth(1) to today
            Period.LAST_MONTH -> YearMonth.from(today).minusMonths(1).let { it.atDay(1) to it.atEndOfMonth() }
            Period.THIS_YEAR -> today.withDayOfYear(1) to today
            Period.LAST_YEAR -> today.minusYears(1).withDayOfYear(1).let { it to it.withDayOfYear(it.lengthOfYear()) }
        }
        fun day(sec: Long?) = sec?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalDate() }
        fun inside(d: LocalDate?) = d != null && !d.isBefore(from) && !d.isAfter(to)
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { it to (s.pagesByDay[it.toString()] ?: 0) }.toList()
        val minutes = s.readingDays.values.sumOf { m -> m.entries.sumOf { (d, v) -> if (inside(runCatching { LocalDate.parse(d) }.getOrNull())) v.minutes ?: 0 else 0 } }
        var streak = 0; var best = 0
        for ((_, p) in days) { if (p > 0) { streak++; best = maxOf(best, streak) } else streak = 0 }
        val byWeekday = days.filter { it.second > 0 }.groupBy({ it.first.dayOfWeek }, { it.second }).mapValues { it.value.sum() }
        // Books brought in from a tracker's history count too: held only means they aren't sent anywhere.
        val books = s.books.values.toList()
        val finished = books.filter { it.status == ReadStatus.FINISHED && inside(day(it.finishedAt ?: it.timestamp)) }
            .sortedBy { it.finishedAt ?: it.timestamp }
        val fastest = finished.mapNotNull { b ->
            val st = day(b.startedAt); val fi = day(b.finishedAt)
            if (st != null && fi != null && !fi.isBefore(st)) b to (java.time.temporal.ChronoUnit.DAYS.between(st, fi).toInt() + 1) else null
        }.filter { (b, _) -> (b.pageCount ?: 0) >= 80 }.minByOrNull { (b, d) -> d.toDouble() / (b.pageCount ?: 1) }
        val clips = Highlights.all(s).filter { inside(day(it.clip.createdAt)) }
        val passage = clips.filter { !it.clip.note.isNullOrBlank() && it.clip.text.length in 40..320 }.maxByOrNull { it.clip.text.length }
            ?: clips.filter { it.clip.text.length in 40..320 }.maxByOrNull { it.clip.text.length }
            ?: clips.firstOrNull()
        val topAuthor = finished.mapNotNull { it.author?.takeIf { a -> a.isNotBlank() } }.groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.takeIf { it.value >= 2 }?.toPair()
        // Finished books with no reading days here (brought in from a history): their whole page count, in the month they were finished.
        val fromBooks = finished.filter { s.readingDays[it.document].isNullOrEmpty() }.sumOf { it.pageCount ?: 0 }
        val monthName = from.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
        return Data(
            title = if (period == Period.THIS_YEAR || period == Period.LAST_YEAR) "Your ${from.year} in reading" else "Your $monthName in reading",
            short = if (period == Period.THIS_YEAR || period == Period.LAST_YEAR) "${from.year}" else "$monthName ${from.year}",
            year = (period == Period.THIS_YEAR || period == Period.LAST_YEAR),
            days = days,
            pages = days.sumOf { it.second } + fromBooks,
            fromBooks = fromBooks,
            minutes = minutes,
            readingDays = days.count { it.second > 0 },
            longestStreak = best,
            favouriteDay = byWeekday.maxByOrNull { it.value }?.key,
            bestDay = days.filter { it.second > 0 }.maxByOrNull { it.second },
            finished = finished,
            started = books.count { inside(day(it.startedAt)) },
            fastest = fastest,
            topRated = finished.filter { (it.rating ?: 0.0) > 0 }.maxByOrNull { it.rating!! },
            highlights = clips.size,
            passage = passage,
            topAuthor = topAuthor,
        )
    }

    // ---- the slides ----

    private const val W = 1080
    private const val H = 1920

    /** Every slide that has something to say, in order. Blocking: covers load from the phone or the web. */
    fun slides(context: Context, s: Snapshot, d: Data): List<Bitmap> {
        val f = Ink.faces(context)
        val covers = HashMap<String, Bitmap?>()
        fun cover(b: Book): Bitmap? = covers.getOrPut(b.document) { b.coverUrl?.let { runCatching { CoverCache.load(context, it, 900) }.getOrNull() } }
        val tint = d.finished.lastOrNull()?.let { cover(it) }?.let { Ink.coverColor(it) }
            ?: s.books.values.maxByOrNull { it.timestamp }?.let { cover(it) }?.let { Ink.coverColor(it) } ?: Ink.SIGNAL
        val out = ArrayList<Bitmap>()
        fun slide(bloom: Float = 0.18f, draw: (Canvas) -> Unit) {
            val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            Ink.wallpaper(cv, W, H, tint, bloom)
            draw(cv)
            Ink.mark(cv, f, W / 2f, H - 80f)
            out += bmp
        }
        val num = java.text.NumberFormat.getIntegerInstance(Locale.getDefault())

        // 1. The cover: the period, and the books of it stacked like a pile.
        slide { cv ->
            Ink.line(cv, d.short.uppercase(), 96f, 250f, f.mono, 30f, Ink.DIM, W - 192f, spacing = 0.16f)
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.interBold; color = Ink.TEXT; letterSpacing = -0.02f }
            Ink.text(cv, Ink.fit(d.title, tp, W - 192, 380, 120f, 70f, 1.0f), 96f, 290f)
            val pile = (d.finished.takeLast(5).ifEmpty { s.books.values.sortedByDescending { it.timestamp }.take(3) })
            val bw = 330f; val bh = 495f
            pile.forEachIndexed { i, b ->
                val k = pile.size - 1 - i
                cv.save()
                cv.translate(W / 2f + (i - (pile.size - 1) / 2f) * 120f, 1180f + k * 10f)
                cv.rotate((i - (pile.size - 1) / 2f) * 6f)
                Ink.drawBook(cv, cover(b), RectF(-bw / 2, -bh / 2, bw / 2, bh / 2), tint, f, b.displayTitle)
                cv.restore()
            }
            Ink.line(cv, "Tap through", W / 2f, H - 190f, f.inter, 32f, Ink.DIM, W - 192f, Paint.Align.CENTER)
        }

        // 2. Pages: the big number, the days and hours, and a bar for every day.
        if (d.pages > 0) slide { cv ->
            Ink.line(cv, "YOU READ", 96f, 300f, f.mono, 30f, Ink.DIM, W - 192f, spacing = 0.16f)
            Ink.bigNumber(cv, num.format(d.pages), 88f, 560f, 270f, W - 176f, Ink.SIGNAL, f.interBold)
            Ink.line(cv, "pages", 100f, 660f, f.interBold, 64f, Ink.TEXT, W - 192f)
            val sub = listOfNotNull(
                d.readingDays.takeIf { it > 0 }?.let { "on $it day${if (it == 1) "" else "s"}" },
                d.fromBooks.takeIf { it > 0 }?.let { "${num.format(it)} from finished books' page counts" },
                d.minutes.takeIf { it >= 60 }?.let { "about ${Math.round(it / 60.0)} hours" },
                (d.pages / d.days.size.coerceAtLeast(1)).takeIf { it > 0 }?.let { "$it a day on average" },
            ).joinToString(" · ")
            Ink.line(cv, sub, 100f, 740f, f.inter, 38f, Ink.DIM, W - 200f)
            // A bar per day (or per week, over a year).
            if (d.pages - d.fromBooks > 0) {
            val bars = if (d.year) d.days.chunked(7).map { w -> w.first().first to w.sumOf { it.second } } else d.days
            val max = (bars.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
            val left = 96f; val right = W - 96f; val base = 1500f; val top = 950f
            val gap = if (bars.size > 40) 4f else 8f
            val bw = ((right - left) - gap * (bars.size - 1)) / bars.size
            bars.forEachIndexed { i, (_, p) ->
                val h = if (p == 0) 6f else ((base - top) * p / max).coerceAtLeast(10f)
                cv.drawRoundRect(RectF(left + i * (bw + gap), base - h, left + i * (bw + gap) + bw, base), 4f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (p == 0) 0xFF1E1E1C.toInt() else if (d.bestDay?.second == p && !d.year) Ink.SIGNAL else Ink.alpha(Ink.SIGNAL, 150)
                })
            }
            }
            d.bestDay?.let { (day, p) ->
                Ink.line(cv, "Best day: ${day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${day.dayOfMonth} ${day.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}, $p pages", 96f, 1580f, f.inter, 34f, Ink.TEXT, W - 192f)
            }
        }

        // 3. The rhythm: your longest streak and the day you read most.
        if (d.longestStreak >= 2 || d.favouriteDay != null) slide(0.5f) { cv ->
            Ink.line(cv, "LONGEST STREAK", 96f, 330f, f.mono, 30f, Ink.DIM, W - 192f, spacing = 0.16f)
            Ink.bigNumber(cv, "${d.longestStreak}", 84f, 640f, 330f, W - 176f, Ink.SIGNAL, f.interBold)
            Ink.line(cv, "days in a row", 100f, 740f, f.interBold, 60f, Ink.TEXT, W - 192f)
            // A light per day of the streak, in rows of seven.
            val n = d.longestStreak.coerceAtMost(35)
            for (i in 0 until n) {
                val x = 120f + (i % 7) * 130f; val y = 900f + (i / 7) * 110f
                cv.drawCircle(x, y, 30f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.alpha(Ink.SIGNAL, 70) })
                cv.drawCircle(x, y, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.SIGNAL })
            }
            d.favouriteDay?.let { fd ->
                val name = fd.getDisplayName(TextStyle.FULL, Locale.getDefault())
                Ink.line(cv, "YOUR READING DAY", 96f, 1560f, f.mono, 30f, Ink.DIM, W - 192f, spacing = 0.16f)
                Ink.line(cv, name + "s", 96f, 1660f, f.interBold, 84f, Ink.TEXT, W - 192f)
            }
        }

        // 4. What you finished, covers in a grid, with your stars.
        if (d.finished.isNotEmpty()) slide { cv ->
            Ink.line(cv, "YOU FINISHED", 96f, 260f, f.mono, 30f, Ink.DIM, W - 192f, spacing = 0.16f)
            Ink.line(cv, "${d.finished.size} book${if (d.finished.size == 1) "" else "s"}", 96f, 400f, f.interBold, 120f, Ink.TEXT, W - 192f)
            val shown = d.finished.takeLast(if (d.year) 24 else 9)
            val cols = when { shown.size <= 1 -> 1; shown.size <= 4 -> 2; shown.size <= 9 -> 3; shown.size <= 16 -> 4; else -> 5 }
            val gap = 30f
            val cw = ((W - 192f) - gap * (cols - 1)) / cols
            val ch = cw * 1.5f
            val rows = (shown.size + cols - 1) / cols
            val availH = 1600f - 500f
            val scale = minOf(1f, availH / (rows * ch + (rows - 1) * gap))
            val cw2 = cw * scale; val ch2 = ch * scale
            val gridW = cols * cw2 + (cols - 1) * gap
            val x0 = (W - gridW) / 2f
            shown.forEachIndexed { i, b ->
                val x = x0 + (i % cols) * (cw2 + gap); val y = 500f + (i / cols) * (ch2 + gap)
                Ink.drawBook(cv, cover(b), RectF(x, y, x + cw2, y + ch2), tint, f, b.displayTitle)
                b.rating?.takeIf { it > 0 && cols <= 3 }?.let { r ->
                    Ink.line(cv, "★".repeat(Math.round(r).toInt().coerceIn(1, 5)), x + cw2 / 2, y + ch2 + 46f, f.inter, 34f, Ink.SIGNAL, cw2, Paint.Align.CENTER)
                }
            }
            if (d.finished.size > shown.size) Ink.line(cv, "and ${d.finished.size - shown.size} more", W / 2f, 1680f, f.inter, 36f, Ink.DIM, W - 192f, Paint.Align.CENTER)
            d.topAuthor?.let { (a, n) -> Ink.line(cv, "Most read: $a ($n)", W / 2f, 1740f, f.inter, 34f, Ink.DIM, W - 192f, Paint.Align.CENTER) }
        }

        // 5. The one you flew through (or your favourite).
        val star = d.fastest?.first ?: d.topRated
        if (star != null) slide(0.35f) { cv ->
            val fast = d.fastest
            Ink.line(cv, if (fast != null) "YOU FLEW THROUGH" else "YOUR FAVOURITE", 96f, 260f, f.mono, 30f, Ink.DIM, W - 192f, spacing = 0.16f)
            val bw = 560f; val bh = 840f
            cv.save(); cv.translate(W / 2f, 820f); cv.rotate(-3f)
            Ink.drawBook(cv, cover(star), RectF(-bw / 2, -bh / 2, bw / 2, bh / 2), tint, f, star.displayTitle)
            cv.restore()
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.interBold; color = Ink.TEXT }
            Ink.text(cv, Ink.fit(QuoteCard.shortTitle(star.displayTitle), tp, W - 192, 170, 68f, 44f, 1.05f, Layout.Alignment.ALIGN_CENTER), 96f, 1300f)
            Ink.line(cv, star.author, W / 2f, 1520f, f.inter, 40f, Ink.DIM, W - 192f, Paint.Align.CENTER)
            val line = when {
                fast != null -> "in ${fast.second} day${if (fast.second == 1) "" else "s"}" + (star.pageCount?.let { " · ${Math.round(it.toDouble() / fast.second)} pages a day" } ?: "")
                else -> star.rating?.let { "★".repeat(Math.round(it).toInt().coerceIn(1, 5)) } ?: ""
            }
            Ink.line(cv, line, W / 2f, 1620f, f.interBold, 48f, Ink.SIGNAL, W - 192f, Paint.Align.CENTER)
        }

        // 6. Your passage of the period, on a page.
        d.passage?.let { item ->
            val b = s.books[item.clip.document]
            slide(0.6f) { cv ->
                Ink.line(cv, if (d.year) "YOUR PASSAGE OF THE YEAR" else "YOUR PASSAGE OF THE MONTH", 96f, 260f, f.mono, 30f, Ink.DIM, W - 192f, spacing = 0.16f)
                val card = RectF(80f, 340f, W - 80f, 1560f)
                cv.drawRoundRect(card, 40f, 40f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2EEE6.toInt(); setShadowLayer(40f, 0f, 20f, 0x99000000.toInt()) })
                val qp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.lora; color = 0xFF221F1C.toInt() }
                cv.drawText("“", 130f, 560f, TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.loraBold; textSize = 260f; color = Ink.alpha(tint, 120) })
                val noteH = if (item.clip.note.isNullOrBlank()) 0 else 220
                Ink.text(cv, Ink.fit(item.clip.text.trim(), qp, (card.width() - 140).toInt(), (900 - noteH), 64f, 34f, 1.35f), card.left + 70f, 560f)
                item.clip.note?.takeIf { it.isNotBlank() }?.let { n ->
                    val np = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.caveat; textSize = 56f; color = Ink.shade(tint, 0.6f) }
                    Ink.text(cv, Ink.layout(n.trim(), np, (card.width() - 140).toInt(), 1.05f, 3), card.left + 70f, card.bottom - 300f)
                }
                Ink.line(cv, b?.displayTitle?.let { QuoteCard.shortTitle(it) }, card.left + 70f, card.bottom - 90f, f.interBold, 36f, 0xFF221F1C.toInt(), card.width() - 140f)
                Ink.line(cv, "${d.highlights} passage${if (d.highlights == 1) "" else "s"} highlighted", W / 2f, 1680f, f.inter, 36f, Ink.DIM, W - 192f, Paint.Align.CENTER)
            }
        }

        // 7. Everything in one card.
        slide(0.25f) { cv ->
            Ink.line(cv, d.short.uppercase(), 96f, 300f, f.mono, 30f, Ink.DIM, W - 192f, spacing = 0.16f)
            Ink.line(cv, "In short", 96f, 420f, f.interBold, 100f, Ink.TEXT, W - 192f)
            val panel = RectF(80f, 520f, W - 80f, 1500f)
            cv.drawRoundRect(panel, 44f, 44f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.SCREEN })
            val cells = listOf(
                "Pages" to num.format(d.pages), "Books" to "${d.finished.size}",
                "Days" to "${d.readingDays}", "Streak" to "${d.longestStreak}",
                "Highlights" to "${d.highlights}", "Hours" to (if (d.minutes > 0) "${Math.round(d.minutes / 60.0)}" else "–"),
            )
            cells.forEachIndexed { i, (k, v) ->
                val x = panel.left + 70f + (i % 2) * (panel.width() / 2)
                val y = panel.top + 120f + (i / 2) * 300f
                Ink.line(cv, k.uppercase(), x, y, f.mono, 28f, Ink.DIM, panel.width() / 2 - 90f, spacing = 0.14f)
                Ink.bigNumber(cv, v, x - 4f, y + 160f, 140f, panel.width() / 2 - 90f, if (i == 0) Ink.SIGNAL else Ink.TEXT, f.interBold)
            }
            Ink.line(cv, "Started ${d.started} · finished ${d.finished.size}", W / 2f, 1620f, f.inter, 36f, Ink.DIM, W - 192f, Paint.Align.CENTER)
        }
        return out
    }

    /** The words that go with a shared slide. */
    fun caption(d: Data): String = buildString {
        append(d.title)
        append(": ")
        append(listOfNotNull(
            d.pages.takeIf { it > 0 }?.let { "$it pages" },
            d.finished.size.takeIf { it > 0 }?.let { "$it book${if (it == 1) "" else "s"} finished" },
            d.longestStreak.takeIf { it >= 2 }?.let { "a $it-day streak" },
        ).joinToString(", "))
        append(".")
    }
}

/**
 * The slides as a story: they advance by themselves (a bar along the top fills for each), tap the
 * right side for the next and the left for the one before, hold to pause. Share, save or post the
 * one showing, or save them all.
 */
@Composable
fun WrappedSheet(state: Snapshot, start: Wrapped.Period = Wrapped.Period.THIS_MONTH, onClose: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    var period by remember { mutableStateOf(start) }
    val data = remember(period, state.pagesByDay, state.books) { Wrapped.data(state, period) }
    val slides by produceState<List<Bitmap>?>(null, data) {
        value = null
        value = withContext(Dispatchers.Default) { runCatching { Wrapped.slides(context, state, data) }.getOrNull() }
    }
    var at by remember(slides) { mutableStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    val fill = remember(at, slides) { Animatable(0f) }
    LaunchedEffect(at, slides, paused) {
        val n = slides?.size ?: return@LaunchedEffect
        if (paused) return@LaunchedEffect
        fill.animateTo(1f, tween(((1f - fill.value) * 6000).toInt().coerceAtLeast(1), easing = LinearEasing))
        if (at < n - 1) at++
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DarkGadget {
            val c = Cp.colors
            Column(Modifier.fillMaxSize().background(c.page).windowInsetsPadding(WindowInsets.safeDrawing)) {
                // Which stretch of time.
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconCircleButton(io.github.prithirajn.trackersync.R.drawable.lucide_x, "Close", onClose, tint = c.text)
                    Spacer(Modifier.width(10.dp))
                    Segmented(Wrapped.Period.entries, period, { when (it) { Wrapped.Period.THIS_MONTH -> "This month"; Wrapped.Period.LAST_MONTH -> "Last month"; Wrapped.Period.THIS_YEAR -> "${java.time.LocalDate.now().year}"; Wrapped.Period.LAST_YEAR -> "${java.time.LocalDate.now().year - 1}" } }, { period = it }, Modifier.weight(1f))
                }
                val list = slides
                // The bars: one per slide, filled for those seen, filling for this one.
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(list?.size ?: 1) { i ->
                        Box(
                            Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.screenOff)
                                .drawBehind {
                                    val p = when { i < at -> 1f; i == at -> fill.value; else -> 0f }
                                    drawRoundRect(c.accent, size = Size(size.width * p, size.height), cornerRadius = CornerRadius(2.dp.toPx()))
                                },
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                    when {
                        list == null -> CircularProgressIndicator(Modifier.size(28.dp), color = c.accent, strokeWidth = 2.dp)
                        data.isEmpty -> Text("Nothing read in this stretch yet.", style = CpType.body, color = c.quiet)
                        else -> AnimatedContent(
                            at.coerceIn(0, list.lastIndex),
                            transitionSpec = { (fadeIn(tween(260)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = 300f), initialScale = 0.96f)) togetherWith fadeOut(tween(160)) },
                            label = "slide",
                        ) { i ->
                            Image(
                                list[i].asImageBitmap(), "Slide ${i + 1} of ${list.size}", contentScale = ContentScale.Fit,
                                modifier = Modifier.aspectRatio(1080f / 1920f).clip(RoundedCornerShape(22.dp))
                                    .pointerInput(list.size) {
                                        detectTapGestures(
                                            onPress = { paused = true; tryAwaitRelease(); paused = false },
                                            onTap = { p ->
                                                Haptics.tick(view)
                                                if (p.x < size.width / 3) { if (at > 0) at-- } else if (at < list.lastIndex) at++
                                            },
                                        )
                                    },
                            )
                        }
                    }
                }
                said?.let { AnimatedQuiet(it, Modifier.padding(horizontal = 16.dp), color = c.textMid) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val cur = list?.getOrNull(at)
                    BigButton("Save all", {
                        list?.let { all -> val ok = all.all { QuoteCard.save(context, it) }; said = if (ok) "Saved ${all.size} slides to Pictures" else "Couldn't save here; use Share instead." }
                    }, Modifier.weight(1f), filled = false, enabled = list != null && !data.isEmpty)
                    BigButton("Threads", {
                        cur?.let { said = when (QuoteCard.postToThreads(context, it, Wrapped.caption(data))) { "app" -> "Opened in Threads"; "web" -> "Threads opened in the browser"; else -> "Couldn't open Threads" } }
                    }, Modifier.weight(1f), look = KeyKind.INK, enabled = cur != null && !data.isEmpty)
                    BigButton("Share", { cur?.let { runCatching { QuoteCard.share(context, it, Wrapped.caption(data)) } } }, Modifier.weight(1.2f), enabled = cur != null && !data.isEmpty)
                }
            }
        }
    }
}
