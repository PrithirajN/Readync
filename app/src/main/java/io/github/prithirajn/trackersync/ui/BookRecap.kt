package io.github.prithirajn.trackersync.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.sync.Highlights
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpType
import io.github.prithirajn.trackersync.ui.theme.DarkGadget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.pow

/**
 * A finished book, told in numbers: how long it took, the pages and the days you read them on, the
 * hours, your highlights and the one you'd keep, and your stars. As a video for stories and reels,
 * or as a carousel of pictures to post on Threads.
 */
object BookRecap {
    data class Data(
        val book: Book,
        val start: LocalDate?,
        val finish: LocalDate?,
        /** Days from the first to the last, both counted. */
        val days: Int?,
        val pages: Int?,
        /** Pages on each day from start to finish (zeros kept), when the app saw you read. */
        val daily: List<Pair<LocalDate, Int>>,
        val minutes: Int,
        val readingDays: Int,
        val bestDay: Pair<LocalDate, Int>?,
        val highlights: Int,
        val notes: Int,
        val passage: Highlights.Item?,
        /** Where in the book each highlight is (0–1), for those whose place is known. */
        val marks: List<Double>,
        val rating: Double?,
        val review: String?,
        /** Pages an hour, from the days you timed (their pages over their minutes). */
        val pace: Double?,
        /** The most days in a row you read it, and the longest stretch you didn't, between start and finish. */
        val longestRun: Int,
        val longestBreak: Int,
        /** Share of its pages read on Saturdays and Sundays (0–100). */
        val weekendShare: Int?,
        /** Your other finished books: how long they took on average, at how many pages a day, and how many. */
        val usualDays: Double?,
        val usualPerDay: Double?,
        val usualOf: Int,
        /** Where it sits among the books you finished in its year: in order, by length and by speed. */
        val yearNumber: Int?,
        val yearCount: Int,
        val yearLongest: Int?,
        val yearFastest: Int?,
    ) {
        val perDay: Int? get() = if (pages != null && days != null && days > 0) Math.round(pages.toDouble() / days).toInt().takeIf { it > 0 } else null
        val hours: Double get() = minutes / 60.0
        /** Pages on the days you picked it up (not every day of the span). */
        val perReadingDay: Int? get() = if (pages != null && readingDays > 0) Math.round(pages.toDouble() / readingDays).toInt() else null
        /** An estimate, at about [WORDS_A_PAGE] words a page. */
        val words: Int? get() = pages?.let { it * WORDS_A_PAGE }
        val wordsAMinute: Int? get() = pace?.let { Math.round(it * WORDS_A_PAGE / 60.0).toInt() }
        /** Seconds a page at your pace. */
        val secondsAPage: Int? get() = pace?.takeIf { it > 0 }?.let { Math.round(3600 / it).toInt() }
        val readShare: Int? get() = if (days != null && days > 0 && readingDays > 0) Math.round(100.0 * readingDays.coerceAtMost(days) / days).toInt() else null
        val everyNPages: Int? get() = if (pages != null && highlights > 0) Math.round(pages.toDouble() / highlights).toInt().coerceAtLeast(1) else null
        val per100Pages: Double? get() = if (pages != null && pages > 0 && highlights > 0) highlights * 100.0 / pages else null
        /** This book's pages a day over your usual (above 1 is quicker). */
        val vsUsual: Double? get() = if (perDay != null && usualPerDay != null && usualPerDay > 0) perDay!! / usualPerDay else if (days != null && usualDays != null && days > 0) usualDays / days else null
    }

    const val WORDS_A_PAGE = 275

    fun data(s: Snapshot, book: Book, zone: ZoneId = ZoneId.systemDefault()): Data {
        fun day(sec: Long?) = sec?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalDate() }
        val log = s.readingDays[book.document].orEmpty().mapNotNull { (d, v) -> runCatching { LocalDate.parse(d) }.getOrNull()?.let { it to v } }.sortedBy { it.first }
        val start = day(book.startedAt) ?: log.firstOrNull()?.first
        val finish = day(book.finishedAt) ?: log.lastOrNull()?.first ?: day(book.timestamp)
        val days = if (start != null && finish != null && !finish.isBefore(start)) ChronoUnit.DAYS.between(start, finish).toInt() + 1 else null
        val pagesOn = log.associate { (d, v) -> d to (v.pages ?: 0) }
        val daily = if (start != null && finish != null && days != null && days <= 400 && pagesOn.values.any { it > 0 })
            generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(finish) }.map { it to (pagesOn[it] ?: 0) }.toList()
        else emptyList()
        val clips = Highlights.forBook(s, book.document)
        val passage = clips.filter { !it.clip.note.isNullOrBlank() && it.clip.text.length in 40..320 }.maxByOrNull { it.clip.text.length }
            ?: clips.filter { it.clip.text.length in 40..320 }.maxByOrNull { it.clip.text.length }
            ?: clips.minByOrNull { it.clip.text.length }
        // Pace from the timed days only, so untimed days don't make it look quicker than it was.
        val timed = log.filter { (it.second.minutes ?: 0) > 0 && (it.second.pages ?: 0) > 0 }
        val timedMin = timed.sumOf { it.second.minutes ?: 0 }
        val pace = if (timedMin >= 20) timed.sumOf { it.second.pages ?: 0 } * 60.0 / timedMin else null
        // Runs of reading days and of days off, between the first and last.
        var run = 0; var bestRun = 0; var off = 0; var bestOff = 0
        for ((_, p) in daily) { if (p > 0) { run++; off = 0; bestRun = maxOf(bestRun, run) } else { off++; run = 0; bestOff = maxOf(bestOff, off) } }
        val dailyTotal = daily.sumOf { it.second }
        val weekend = if (dailyTotal > 0) Math.round(100.0 * daily.filter { it.first.dayOfWeek.value >= 6 }.sumOf { it.second } / dailyTotal).toInt() else null
        // Your other finished books with both dates.
        data class Done(val b: Book, val days: Int, val fin: LocalDate)
        val done = s.books.values.filter { it.status == io.github.prithirajn.trackersync.data.ReadStatus.FINISHED }.mapNotNull { b ->
            val st = day(b.startedAt); val fi = day(b.finishedAt) ?: return@mapNotNull null
            Done(b, if (st != null && !fi.isBefore(st)) ChronoUnit.DAYS.between(st, fi).toInt() + 1 else -1, fi)
        }
        val others = done.filter { it.b.document != book.document && it.days > 0 }
        val usualDays = others.takeIf { it.size >= 2 }?.map { it.days }?.average()
        val usualPerDay = others.filter { (it.b.pageCount ?: 0) > 0 }.takeIf { it.size >= 2 }?.map { it.b.pageCount!!.toDouble() / it.days }?.average()
        val year = done.filter { finish != null && it.fin.year == finish.year }.let { list ->
            if (list.none { it.b.document == book.document } && finish != null) list + Done(book, days ?: -1, finish) else list
        }.sortedBy { it.fin }
        val yearNumber = year.indexOfFirst { it.b.document == book.document }.takeIf { it >= 0 }?.plus(1)
        val yearLongest = if (book.pageCount != null) year.filter { (it.b.pageCount ?: 0) > 0 }.sortedByDescending { it.b.pageCount }.indexOfFirst { it.b.document == book.document }.takeIf { it >= 0 }?.plus(1) else null
        val yearFastest = if (days != null) year.filter { it.days > 0 }.sortedBy { it.days }.indexOfFirst { it.b.document == book.document }.takeIf { it >= 0 }?.plus(1) else null
        return Data(
            pace = pace, longestRun = bestRun, longestBreak = bestOff, weekendShare = weekend,
            usualDays = usualDays, usualPerDay = usualPerDay, usualOf = others.size,
            yearNumber = yearNumber, yearCount = year.size, yearLongest = yearLongest, yearFastest = yearFastest,
            book = book,
            start = start, finish = finish, days = days,
            pages = book.pageCount?.takeIf { it > 0 },
            daily = daily,
            minutes = log.sumOf { it.second.minutes ?: 0 },
            readingDays = log.count { (it.second.pages ?: 0) > 0 || (it.second.minutes ?: 0) > 0 },
            bestDay = daily.filter { it.second > 0 }.maxByOrNull { it.second },
            highlights = clips.size,
            notes = clips.count { !it.clip.note.isNullOrBlank() },
            passage = passage,
            marks = clips.mapNotNull { it.clip.percent?.takeIf { p -> p in 0.0..1.0 } },
            rating = book.rating?.takeIf { it > 0 },
            review = book.review?.let { QuoteCard.cleanReview(it).trim() }?.takeIf { it.isNotBlank() },
        )
    }

    fun cover(context: Context, b: Book): Bitmap? = b.coverUrl?.let { runCatching { CoverCache.load(context, it, 900) }.getOrNull() }

    private fun short(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}"
    private fun span(d: Data): String? = when {
        d.start != null && d.finish != null && d.start != d.finish ->
            short(d.start) + " → " + short(d.finish) + (if (d.finish.year != LocalDate.now().year || d.start.year != d.finish.year) " ${d.finish.year}" else "")
        d.finish != null -> "Finished " + short(d.finish)
        else -> null
    }
    private fun stars(r: Double): String {
        val full = r.toInt().coerceIn(0, 5)
        val half = r - full >= 0.5
        return "★".repeat(full) + (if (half) "½" else "")
    }
    private fun hoursText(d: Data): String = if (d.hours >= 10) "${Math.round(d.hours)}" else String.format(Locale.getDefault(), "%.1f", d.hours).removeSuffix(".0").removeSuffix(",0")
    private fun plural(n: Int, one: String) = "$n $one" + if (n == 1) "" else "s"

    /** The words that go with it. */
    fun caption(d: Data): String = buildString {
        append("Finished “").append(d.book.displayTitle).append("”")
        d.book.author?.takeIf { it.isNotBlank() }?.let { append(" by ").append(it) }
        val bits = listOfNotNull(
            d.days?.let { plural(it, "day") },
            d.pages?.let { "$it pages" },
            d.minutes.takeIf { it >= 30 }?.let { hoursText(d) + " hours" },
            d.highlights.takeIf { it > 0 }?.let { plural(it, "highlight") },
        )
        if (bits.isNotEmpty()) append(": ").append(bits.joinToString(", "))
        append(".")
        d.rating?.let { append(" ").append(ThreadsAuto.starEmoji(it)) }
    }

    // ---- the carousel ----

    const val CW = 1080
    const val CH = 1350

    /** The pictures of the carousel, at least two. Blocking: the cover loads from the phone or the web. */
    fun slides(context: Context, d: Data, coverArt: Bitmap?): List<Bitmap> {
        val f = Ink.faces(context)
        val tint = coverArt?.let { Ink.coverColor(it) } ?: Ink.SIGNAL
        val num = java.text.NumberFormat.getIntegerInstance(Locale.getDefault())
        val out = ArrayList<Bitmap>()
        val w = CW.toFloat()
        fun slide(bloom: Float = 0.2f, draw: (Canvas) -> Unit) {
            val bmp = Bitmap.createBitmap(CW, CH, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            Ink.wallpaper(cv, CW, CH, tint, bloom)
            draw(cv)
            Ink.mark(cv, f, w / 2f, CH - 60f)
            out += bmp
        }
        val title = QuoteCard.shortTitle(d.book.displayTitle)

        // 1. The cover with its stamp, the dates and your stars.
        slide(0.3f) { cv ->
            val bh = 700f; val bw = bh * 2f / 3f
            cv.save(); cv.translate(w / 2f, 470f); cv.rotate(-2.5f)
            Ink.drawBook(cv, coverArt, RectF(-bw / 2, -bh / 2, bw / 2, bh / 2), tint, f, d.book.displayTitle)
            cv.restore()
            stamp(cv, f, w / 2f + bw / 2f - 30f, 170f, 1f, 255)
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.interBold; color = Ink.TEXT; letterSpacing = -0.01f }
            Ink.text(cv, Ink.fit(title, tp, CW - 160, 130, 64f, 40f, 1.05f, Layout.Alignment.ALIGN_CENTER), 80f, 870f)
            Ink.line(cv, d.book.author, w / 2f, 1050f, f.inter, 38f, Ink.DIM, w - 160f, Paint.Align.CENTER)
            span(d)?.let { Ink.line(cv, it, w / 2f, 1120f, f.mono, 32f, Ink.TEXT, w - 160f, Paint.Align.CENTER, 0.06f) }
            d.rating?.let { Ink.line(cv, stars(it), w / 2f, 1200f, f.interBold, 56f, Ink.SIGNAL, w - 160f, Paint.Align.CENTER, 0.08f) }
        }

        // From here, one number to a picture: small label, the big number, what it counts, a line
        // or two more, and a picture of it where there is one. The book's name at the top of each,
        // so any one of them makes sense on its own.
        fun stat(label: String, big: String, unit: String, subs: List<String>, bloom: Float, extra: (Canvas) -> Unit = {}) = slide(bloom) { cv ->
            Ink.line(cv, title, 90f, 140f, f.interBold, 34f, Ink.DIM, w - 180f)
            Ink.line(cv, label, 90f, 250f, f.mono, 30f, Ink.DIM, w - 180f, spacing = 0.16f)
            Ink.bigNumber(cv, big, 80f, 580f, 330f, w - 160f, Ink.SIGNAL, f.interBold)
            Ink.line(cv, unit, 94f, 700f, f.interBold, 84f, Ink.TEXT, w - 180f)
            subs.forEachIndexed { i, line -> Ink.line(cv, line, 94f, 800f + i * 62f, f.inter, 42f, if (i == 0) Ink.TEXT else Ink.DIM, w - 180f) }
            extra(cv)
        }
        val dayName = { day: LocalDate -> day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()) }

        // 2. How long it took, with a dot for every day (lit on the days you read).
        d.days?.let { days ->
            stat("IT TOOK", num.format(days), if (days == 1) "day" else "days", listOfNotNull(span(d)), 0.15f) { cv ->
                if (d.daily.size in 2..120) {
                    val n = d.daily.size
                    val cols = maxOf(7, (n + 4) / 5)
                    val rows = (n + cols - 1) / cols
                    val box = RectF(94f, 940f, w - 94f, 1230f)
                    val step = minOf(box.width() / cols, box.height() / rows)
                    val r = (step * 0.36f).coerceAtMost(26f)
                    val lit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.SIGNAL }
                    val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A2A27.toInt() }
                    d.daily.forEachIndexed { i, (_, p) ->
                        cv.drawCircle(box.left + step * (i % cols) + step / 2, box.top + step * (i / cols) + step / 2, r, if (p > 0) lit else dim)
                    }
                }
            }
        }

        // 3. The pages.
        d.pages?.let { pages ->
            stat("I READ", num.format(pages), "pages", listOfNotNull(
                d.perDay?.let { "about $it a day" },
                d.bestDay?.let { (day, p) -> "most in a day: $p, on ${dayName(day)} ${short(day)}" },
            ), 0.3f)
        }

        // 3b. The words, worked out from the pages.
        d.words?.let { words ->
            val rounded = if (words >= 10_000) (words / 1000) * 1000 else (words / 100) * 100
            stat("THAT'S ABOUT", num.format(rounded), "words", listOfNotNull(
                d.perDay?.let { "about ${num.format(it * WORDS_A_PAGE / 50 * 50)} a day" },
                d.perReadingDay?.takeIf { d.readingDays > 1 && it != d.perDay }?.let { "$it pages on each day I read" },
                "at roughly $WORDS_A_PAGE words a page",
            ), 0.22f)
        }

        // 4. Day by day.
        if (d.daily.size >= 2) slide(0.45f) { cv ->
            Ink.line(cv, title, 90f, 140f, f.interBold, 34f, Ink.DIM, w - 180f)
            Ink.line(cv, "DAY BY DAY", 90f, 250f, f.mono, 30f, Ink.DIM, w - 180f, spacing = 0.16f)
            Ink.line(cv, "${plural(d.readingDays.coerceAtLeast(d.daily.count { it.second > 0 }), "reading day")} of ${d.daily.size}", 90f, 330f, f.interBold, 56f, Ink.TEXT, w - 180f)
            bars(cv, d, RectF(90f, 420f, w - 90f, 1020f), 1f)
            d.bestDay?.let { (day, p) ->
                Ink.line(cv, "BEST DAY", 90f, 1100f, f.mono, 26f, Ink.DIM, w - 180f, spacing = 0.14f)
                Ink.line(cv, "${dayName(day)} ${short(day)} · $p pages", 90f, 1165f, f.interBold, 44f, Ink.SIGNAL, w - 180f)
            }
        }

        // 4b. How steadily: the share of days you read, the longest run and the longest break.
        d.readShare?.takeIf { d.daily.size >= 3 }?.let { share ->
            stat("I READ ON", "$share%", "of the days", listOfNotNull(
                "${d.readingDays.coerceAtMost(d.days ?: d.readingDays)} of ${d.days}",
                d.longestRun.takeIf { it >= 2 }?.let { "longest run: $it days in a row" },
                d.longestBreak.takeIf { it >= 2 }?.let { "longest break: $it days" },
                d.weekendShare?.let { "$it% of the pages on weekends" },
            ), 0.4f) { cv ->
                // A bar split in two: the days read, the days not.
                val box = RectF(94f, 1100f, w - 94f, 1150f)
                cv.drawRoundRect(box, 25f, 25f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A2A27.toInt() })
                cv.drawRoundRect(RectF(box.left, box.top, box.left + box.width() * share / 100f, box.bottom), 25f, 25f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.SIGNAL })
            }
        }

        // 5. The time it took.
        if (d.minutes >= 30) {
            stat("TIME WITH IT", hoursText(d), if (d.hours in 0.95..1.05) "hour" else "hours", listOfNotNull(
                d.readingDays.takeIf { it > 0 }?.let { "on ${plural(it, "reading day")}" },
                d.readingDays.takeIf { it > 0 }?.let { "about ${Math.round(d.minutes.toDouble() / it)} minutes a day I read" },
            ), 0.5f)
        } else if (d.readingDays > 0 && d.daily.size < 2) {
            stat("I PICKED IT UP ON", "${d.readingDays}", if (d.readingDays == 1) "day" else "days", listOfNotNull(d.days?.let { "of the ${plural(it, "day")} it took" }), 0.5f)
        }

        // 5b. Your pace on the days you timed.
        d.pace?.let { pace ->
            val sec = d.secondsAPage ?: 0
            stat("MY PACE", if (pace >= 10) "${Math.round(pace)}" else String.format(Locale.getDefault(), "%.1f", pace), "pages an hour", listOfNotNull(
                "one page every " + (if (sec >= 60) "${sec / 60} min ${sec % 60} s" else "$sec s"),
                d.wordsAMinute?.let { "about $it words a minute" },
                "a 300-page book: about ${Math.round(300 / pace)} hours",
            ), 0.5f)
        }

        // 6. The highlights, and where in the book they fall.
        if (d.highlights > 0) {
            stat("I HIGHLIGHTED", num.format(d.highlights), if (d.highlights == 1) "passage" else "passages", listOfNotNull(
                d.everyNPages?.let { "one every ${plural(it, "page")}" },
                d.per100Pages?.let { "${String.format(Locale.getDefault(), "%.1f", it).removeSuffix(".0")} for every 100 pages" },
                d.notes.takeIf { it > 0 }?.let { "${plural(it, "note")} of my own" },
            ), 0.35f) { cv ->
                if (d.marks.isNotEmpty()) {
                    val x0 = 94f; val x1 = w - 94f; val y = 1140f
                    Ink.line(cv, "WHERE THEY FALL", x0, 1050f, f.mono, 26f, Ink.DIM, w - 180f, spacing = 0.14f)
                    cv.drawRoundRect(RectF(x0, y - 5f, x1, y + 5f), 5f, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A2A27.toInt() })
                    val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.alpha(Ink.SIGNAL, 210) }
                    d.marks.forEach { m -> val x = x0 + (x1 - x0) * m.toFloat(); cv.drawRoundRect(RectF(x - 4f, y - 42f, x + 4f, y + 42f), 4f, 4f, tick) }
                    Ink.line(cv, "start", x0, y + 90f, f.inter, 32f, Ink.DIM, 300f)
                    Ink.line(cv, "end", x1, y + 90f, f.inter, 32f, Ink.DIM, 300f, Paint.Align.RIGHT)
                }
            }
        }

        // 7. The passage you'd keep.
        d.passage?.let { item ->
            slide(0.6f) { cv ->
                Ink.line(cv, if (d.highlights > 1) "ONE OF ${d.highlights} HIGHLIGHTS" else "THE ONE I HIGHLIGHTED", 90f, 140f, f.mono, 28f, Ink.DIM, w - 180f, spacing = 0.16f)
                val card = RectF(70f, 200f, w - 70f, 1190f)
                cv.drawRoundRect(card, 40f, 40f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2EEE6.toInt(); setShadowLayer(40f, 0f, 20f, 0x99000000.toInt()) })
                cv.drawText("“", card.left + 50f, card.top + 200f, TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.loraBold; textSize = 220f; color = Ink.alpha(tint, 120) })
                val qp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.lora; color = 0xFF221F1C.toInt() }
                val noteH = if (item.clip.note.isNullOrBlank()) 0 else 200
                Ink.text(cv, Ink.fit(item.clip.text.trim(), qp, (card.width() - 140).toInt(), 700 - noteH, 60f, 32f, 1.35f), card.left + 70f, card.top + 200f)
                item.clip.note?.takeIf { it.isNotBlank() }?.let { n ->
                    val np = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.caveat; textSize = 52f; color = Ink.shade(tint, 0.6f) }
                    Ink.text(cv, Ink.layout(n.trim(), np, (card.width() - 140).toInt(), 1.05f, 3), card.left + 70f, card.bottom - 280f)
                }
                Ink.line(cv, title, card.left + 70f, card.bottom - 70f, f.interBold, 34f, 0xFF221F1C.toInt(), card.width() - 140f)
            }
        }

        // 7b. Against your usual.
        d.vsUsual?.let { k ->
            val quicker = k >= 1.05; val slower = k <= 0.95
            val big = when { quicker -> String.format(Locale.getDefault(), "%.1f×", k); slower -> String.format(Locale.getDefault(), "%.1f×", 1 / k); else -> "≈1×" }
            stat("COMPARED TO MY USUAL", big, when { quicker -> "quicker than usual"; slower -> "slower than usual"; else -> "my usual pace" }, listOfNotNull(
                if (d.perDay != null && d.usualPerDay != null) "${d.perDay} pages a day, against my ${Math.round(d.usualPerDay)}" else null,
                if (d.days != null && d.usualDays != null) "${plural(d.days, "day")}, against my ${Math.round(d.usualDays)} on average" else null,
                "over my other ${d.usualOf} finished books",
            ), 0.3f)
        }

        // 7c. Its place in the year.
        if (d.yearNumber != null && d.yearCount >= 2 && d.finish != null) {
            stat("THIS YEAR", "#${d.yearNumber}", "book I finished in ${d.finish.year}", listOfNotNull(
                d.yearFastest?.let { if (it == 1) "my fastest this year" else "my ${ordinal(it)} fastest of ${d.yearCount}" },
                d.yearLongest?.let { if (it == 1) "my longest this year" else "my ${ordinal(it)} longest of ${d.yearCount}" },
            ), 0.45f)
        }

        // 8. What you made of it: your review under your stars, or just the stars.
        val review = d.review
        if (review != null) slide(0.35f) { cv ->
            Ink.line(cv, title, 90f, 140f, f.interBold, 34f, Ink.DIM, w - 180f)
            Ink.line(cv, "MY TAKE", 90f, 250f, f.mono, 30f, Ink.DIM, w - 180f, spacing = 0.16f)
            d.rating?.let { Ink.line(cv, stars(it), 90f, 370f, f.interBold, 84f, Ink.SIGNAL, w - 180f, spacing = 0.08f) }
            val rp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.lora; color = Ink.TEXT }
            val top = if (d.rating != null) 450f else 320f
            Ink.text(cv, Ink.fit(review, rp, CW - 180, (1200 - top).toInt(), 56f, 30f, 1.35f), 90f, top)
        } else d.rating?.let { r ->
            slide(0.35f) { cv ->
                Ink.line(cv, title, 90f, 140f, f.interBold, 34f, Ink.DIM, w - 180f)
                Ink.line(cv, "MY RATING", 90f, 250f, f.mono, 30f, Ink.DIM, w - 180f, spacing = 0.16f)
                Ink.line(cv, stars(r), 84f, 560f, f.interBold, 190f, Ink.SIGNAL, w - 160f, spacing = 0.04f)
                Ink.line(cv, "${if (r % 1.0 == 0.0) r.toInt().toString() else r.toString()} out of 5", 94f, 700f, f.interBold, 84f, Ink.TEXT, w - 180f)
            }
        }
        // A carousel needs two: with little known, the finish itself gets a picture.
        if (out.size < 2) slide(0.4f) { cv ->
            Ink.line(cv, "FINISHED", 90f, 250f, f.mono, 30f, Ink.DIM, w - 180f, spacing = 0.16f)
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.interBold; color = Ink.TEXT }
            Ink.text(cv, Ink.fit(title, tp, CW - 180, 420, 110f, 56f, 1.05f), 90f, 300f)
            Ink.line(cv, d.book.author, 94f, 820f, f.inter, 46f, Ink.DIM, w - 180f)
            d.finish?.let { Ink.line(cv, "on ${dayNameFull(it)} ${short(it)} ${it.year}", 94f, 900f, f.interBold, 50f, Ink.SIGNAL, w - 180f) }
        }
        return out
    }

    private fun ordinal(n: Int): String = n.toString() + when {
        n % 100 in 11..13 -> "th"; n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"; else -> "th"
    }

    private fun dayNameFull(day: LocalDate) = day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())

    /** The FINISHED stamp, tilted, centred on (cx, cy). */
    internal fun stamp(cv: Canvas, f: Ink.Faces, cx: Float, cy: Float, scale: Float, alpha: Int) {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.interBold; textSize = 54f; letterSpacing = 0.18f; color = Ink.alpha(Ink.SIGNAL, alpha); textAlign = Paint.Align.CENTER }
        val tw = p.measureText("FINISHED")
        cv.save()
        cv.translate(cx, cy); cv.rotate(-9f); cv.scale(scale, scale)
        val box = RectF(-tw / 2 - 34f, -56f, tw / 2 + 34f, 40f)
        cv.drawRoundRect(box, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.alpha(0xFF0B0B0A.toInt(), (alpha * 0.55f).toInt()) })
        cv.drawRoundRect(box, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7f; color = Ink.alpha(Ink.SIGNAL, alpha) })
        cv.drawText("FINISHED", 0f, 10f, p)
        cv.restore()
    }

    /** A bar for every day of the book (a week each when it ran long), [grow] 0–1 for how far up they've come. */
    internal fun bars(cv: Canvas, d: Data, box: RectF, grow: Float) {
        val bars = if (d.daily.size > 70) d.daily.chunked(7).map { wk -> wk.first().first to wk.sumOf { it.second } } else d.daily
        if (bars.isEmpty()) return
        val max = (bars.maxOf { it.second }).coerceAtLeast(1)
        val gap = if (bars.size > 40) 4f else if (bars.size > 20) 8f else 14f
        val bw = ((box.width()) - gap * (bars.size - 1)) / bars.size
        val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E1E1C.toInt() }
        val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.alpha(Ink.SIGNAL, 160) }
        val best = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.SIGNAL }
        val bestP = bars.maxOf { it.second }
        bars.forEachIndexed { i, (_, p) ->
            // Each bar starts a little after the one before it, so they rise left to right.
            val k = ((grow * (bars.size + 6) - i) / 6f).coerceIn(0f, 1f)
            val e = 1f - (1f - k).pow(3)
            val full = if (p == 0) 6f else (box.height() * p / max).coerceAtLeast(10f)
            val h = if (p == 0) 6f else (full * e).coerceAtLeast(if (k > 0f) 6f else 0f)
            if (h <= 0f) return@forEachIndexed
            val x = box.left + i * (bw + gap)
            cv.drawRoundRect(RectF(x, box.bottom - h, x + bw, box.bottom), minOf(6f, bw / 2), minOf(6f, bw / 2), if (p == 0) off else if (p == bestP) best else on)
        }
    }

    // ---- the video ----

    /**
     * The video: the cover drops and is stamped, then the days, the pages (bars rising), the hours,
     * your highlights with the one you'd keep typing out, your stars and review, and the cover again.
     * About 15 seconds at 1080×1920; each part crossfades into the next.
     */
    class Film(context: Context, private val d: Data, coverArt: Bitmap?) : QuoteVideo.Frames {
        override val w = 1080
        override val h = 1920
        private val f = Ink.faces(context)
        private val tint = coverArt?.let { Ink.coverColor(it) } ?: Ink.SIGNAL
        private val num = java.text.NumberFormat.getIntegerInstance(Locale.getDefault())
        private val title = QuoteCard.shortTitle(d.book.displayTitle)
        private val pad = 60

        // Drawn once in software (the encoder's canvas can't draw shadows or blooms).
        private val back: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { Ink.wallpaper(Canvas(it), w, h, tint, 0.25f) }
        private val bookH = 900f
        private val bookW = bookH * 2f / 3f
        private val book: Bitmap = Bitmap.createBitmap((bookW + pad * 2).toInt(), (bookH + pad * 2).toInt(), Bitmap.Config.ARGB_8888).also {
            Ink.drawBook(Canvas(it), coverArt, RectF(pad.toFloat(), pad.toFloat(), pad + bookW, pad + bookH), tint, f, d.book.displayTitle)
        }
        private val card = RectF(80f, 620f, w - 80f, 1560f)
        private val paper: Bitmap? = d.passage?.let {
            Bitmap.createBitmap((card.width() + pad * 2).toInt(), (card.height() + pad * 2).toInt(), Bitmap.Config.ARGB_8888).also { b ->
                Canvas(b).drawRoundRect(RectF(pad.toFloat(), pad.toFloat(), pad + card.width(), pad + card.height()), 40f, 40f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFF2EEE6.toInt(); setShadowLayer(40f, 0f, 18f, 0x88000000.toInt())
                })
            }
        }
        private val passage: StaticLayout? = d.passage?.let { item ->
            val qp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.lora; color = 0xFF221F1C.toInt() }
            Ink.fit(item.clip.text.trim(), qp, (card.width() - 140).toInt(), (card.height() - 300).toInt(), 62f, 32f, 1.35f)
        }
        private val review: StaticLayout? = d.review?.let { r ->
            val rp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.lora; color = Ink.TEXT }
            Ink.fit(r, rp, w - 200, 700, 54f, 34f, 1.35f)
        }

        private enum class Part { COVER, DAYS, PAGES, TIME, PACE, HIGHLIGHTS, USUAL, VERDICT, END }
        private val parts: List<Pair<Part, Float>> = listOfNotNull(
            Part.COVER to 3.0f,
            d.days?.let { Part.DAYS to 2.6f },
            (d.pages != null || d.daily.size >= 2).takeIf { it }?.let { Part.PAGES to 3.2f },
            (d.minutes >= 30 || d.readingDays > 0).takeIf { it }?.let { Part.TIME to 2.6f },
            (d.pace != null || d.readShare != null && d.daily.size >= 3).takeIf { it }?.let { Part.PACE to 2.8f },
            (d.highlights > 0).takeIf { it }?.let { Part.HIGHLIGHTS to (if (passage != null) (2.4f + (d.passage!!.clip.text.length / 34f).coerceIn(1.4f, 5f)) else 2.4f) },
            d.vsUsual?.let { Part.USUAL to 2.6f },
            (d.rating != null || review != null).takeIf { it }?.let { Part.VERDICT to (if (review != null) 4.2f else 2.6f) },
            Part.END to 2.6f,
        )
        private val starts: FloatArray = FloatArray(parts.size).also { a -> var t = 0f; parts.forEachIndexed { i, (_, len) -> a[i] = t; t += len } }
        override val duration: Float = parts.sumOf { it.second.toDouble() }.toFloat()
        private val fade = 0.35f

        private fun ease(t: Float) = 1f - (1f - t.coerceIn(0f, 1f)).pow(3)
        private fun settle(t: Float): Float { val v = t.coerceIn(0f, 1f); val c1 = 1.6f; val c3 = c1 + 1; return 1 + c3 * (v - 1).pow(3) + c1 * (v - 1).pow(2) }
        private fun count(target: Int, t: Float): String = num.format(Math.round(target * ease(t / 1.4f)).toInt())

        override fun draw(cv: Canvas, t: Float) {
            cv.drawBitmap(back, 0f, 0f, null)
            parts.forEachIndexed { i, (part, len) ->
                val s = starts[i]
                if (t < s - 0.001f || t > s + len + fade) return@forEachIndexed
                val lt = t - s
                val first = i == 0; val last = i == parts.lastIndex
                val aIn = if (first) 1f else (lt / fade).coerceIn(0f, 1f)
                val aOut = if (last || t <= s + len) 1f else ((s + len + fade - t) / fade).coerceIn(0f, 1f)
                val a = aIn * aOut
                if (a <= 0f) return@forEachIndexed
                val layer = cv.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), (a * 255).toInt())
                when (part) {
                    Part.COVER -> cover(cv, lt)
                    Part.DAYS -> days(cv, lt)
                    Part.PAGES -> pages(cv, lt, len)
                    Part.TIME -> time(cv, lt)
                    Part.PACE -> pace(cv, lt)
                    Part.USUAL -> usual(cv, lt)
                    Part.HIGHLIGHTS -> highlights(cv, lt, len)
                    Part.VERDICT -> verdict(cv, lt)
                    Part.END -> end(cv, lt)
                }
                cv.restoreToCount(layer)
            }
        }

        private fun label(cv: Canvas, t: String, y: Float, lt: Float) {
            val k = ease(lt / 0.5f)
            Ink.line(cv, t, 100f - (1 - k) * 30f, y, f.mono, 32f, Ink.alpha(Ink.DIM, (255 * k).toInt()), w - 200f, spacing = 0.16f)
        }

        private fun cover(cv: Canvas, lt: Float) {
            val k = settle(lt / 0.9f)
            val y = 760f + (1 - ease(lt / 0.9f)) * -260f
            cv.save()
            cv.translate(w / 2f, y); cv.rotate(-2.5f * k); cv.scale(0.85f + 0.15f * k, 0.85f + 0.15f * k)
            cv.drawBitmap(book, -book.width / 2f, -book.height / 2f, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (255 * ease(lt / 0.4f)).toInt() })
            cv.restore()
            // The stamp lands.
            if (lt > 1.0f) {
                val sk = ((lt - 1.0f) / 0.35f).coerceIn(0f, 1f)
                stamp(cv, f, w / 2f + bookW / 2f - 40f, 380f, 1.7f - 0.7f * ease(sk), (255 * sk).toInt())
            }
            if (lt > 1.4f) {
                val tk = ease((lt - 1.4f) / 0.6f)
                val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.interBold; color = Ink.alpha(Ink.TEXT, (255 * tk).toInt()) }
                Ink.text(cv, Ink.fit(title, tp, w - 160, 200, 76f, 46f, 1.05f, Layout.Alignment.ALIGN_CENTER), 80f, 1290f + (1 - tk) * 40f)
                Ink.line(cv, d.book.author, w / 2f, 1560f + (1 - tk) * 40f, f.inter, 42f, Ink.alpha(Ink.DIM, (255 * tk).toInt()), w - 160f, Paint.Align.CENTER)
            }
        }

        private fun days(cv: Canvas, lt: Float) {
            label(cv, "IT TOOK", 520f, lt)
            Ink.bigNumber(cv, count(d.days ?: 0, lt), 90f, 900f, 380f, w - 180f, Ink.SIGNAL, f.interBold)
            Ink.line(cv, if (d.days == 1) "day" else "days", 104f, 1030f, f.interBold, 90f, Ink.TEXT, w - 200f)
            span(d)?.let { sp ->
                val k = ease((lt - 0.8f) / 0.5f)
                Ink.line(cv, sp, 104f, 1140f + (1 - k) * 20f, f.mono, 40f, Ink.alpha(Ink.TEXT, (255 * k).toInt()), w - 200f, spacing = 0.05f)
            }
        }

        private fun pages(cv: Canvas, lt: Float, len: Float) {
            label(cv, "I READ", 330f, lt)
            val p = d.pages
            if (p != null) {
                Ink.bigNumber(cv, count(p, lt), 90f, 640f, 300f, w - 180f, Ink.SIGNAL, f.interBold)
                Ink.line(cv, "pages", 104f, 750f, f.interBold, 80f, Ink.TEXT, w - 200f)
                val k = ease((lt - 0.7f) / 0.5f)
                listOfNotNull(d.perDay?.let { "about $it a day" }, d.words?.let { "≈ ${num.format(if (it >= 10_000) it / 1000 * 1000 else it / 100 * 100)} words" })
                    .forEachIndexed { i, line -> Ink.line(cv, line, 104f, 840f + i * 60f, f.inter, 44f, Ink.alpha(if (i == 0) Ink.TEXT else Ink.DIM, (255 * k).toInt()), w - 200f) }
            } else Ink.line(cv, "day by day", 104f, 640f, f.interBold, 100f, Ink.TEXT, w - 200f)
            if (d.daily.size >= 2) bars(cv, d, RectF(100f, 1000f, w - 100f, 1560f), ((lt - 0.4f) / (len - 1.0f)).coerceIn(0f, 1f))
        }

        private fun subs(cv: Canvas, lines: List<String>, lt: Float, from: Float = 0.8f) {
            val k = ease((lt - from) / 0.5f)
            lines.forEachIndexed { i, line -> Ink.line(cv, line, 104f, 1140f + i * 64f + (1 - k) * 20f, f.inter, 42f, Ink.alpha(if (i == 0) Ink.TEXT else Ink.DIM, (255 * k).toInt()), w - 200f) }
        }

        private fun pace(cv: Canvas, lt: Float) {
            val p = d.pace
            if (p != null) {
                label(cv, "MY PACE", 520f, lt)
                val v = p * ease(lt / 1.4f)
                Ink.bigNumber(cv, if (p >= 10) "${Math.round(v)}" else String.format(Locale.getDefault(), "%.1f", v), 90f, 900f, 380f, w - 180f, Ink.SIGNAL, f.interBold)
                Ink.line(cv, "pages an hour", 104f, 1030f, f.interBold, 90f, Ink.TEXT, w - 200f)
                val sec = d.secondsAPage ?: 0
                subs(cv, listOfNotNull(
                    "a page every " + (if (sec >= 60) "${sec / 60} min ${sec % 60} s" else "$sec s"),
                    d.wordsAMinute?.let { "about $it words a minute" },
                    d.readShare?.let { "read on $it% of the days" },
                ), lt)
            } else {
                val share = d.readShare ?: 0
                label(cv, "I READ ON", 520f, lt)
                Ink.bigNumber(cv, count(share, lt) + "%", 90f, 900f, 380f, w - 180f, Ink.SIGNAL, f.interBold)
                Ink.line(cv, "of the days", 104f, 1030f, f.interBold, 90f, Ink.TEXT, w - 200f)
                subs(cv, listOfNotNull(
                    d.longestRun.takeIf { it >= 2 }?.let { "longest run: $it days in a row" },
                    d.longestBreak.takeIf { it >= 2 }?.let { "longest break: $it days" },
                ), lt)
            }
        }

        private fun usual(cv: Canvas, lt: Float) {
            val k = d.vsUsual ?: return
            label(cv, "COMPARED TO MY USUAL", 520f, lt)
            val quicker = k >= 1.05; val slower = k <= 0.95
            val target = if (slower) 1 / k else k
            val shown = if (!quicker && !slower) "≈1×" else String.format(Locale.getDefault(), "%.1f×", 1 + (target - 1) * ease(lt / 1.4f))
            Ink.bigNumber(cv, shown, 90f, 900f, 340f, w - 180f, Ink.SIGNAL, f.interBold)
            Ink.line(cv, when { quicker -> "quicker"; slower -> "slower"; else -> "right on pace" }, 104f, 1030f, f.interBold, 90f, Ink.TEXT, w - 200f)
            subs(cv, listOfNotNull(
                if (d.perDay != null && d.usualPerDay != null) "${d.perDay} pages a day vs my ${Math.round(d.usualPerDay)}" else null,
                if (d.days != null && d.usualDays != null) "${d.days} days vs my usual ${Math.round(d.usualDays)}" else null,
            ), lt)
        }

        private fun time(cv: Canvas, lt: Float) {
            if (d.minutes >= 30) {
                label(cv, "TIME WITH IT", 520f, lt)
                val hrs = d.hours * ease(lt / 1.4f)
                val shown = if (d.hours >= 10) "${Math.round(hrs)}" else String.format(Locale.getDefault(), "%.1f", hrs)
                Ink.bigNumber(cv, shown, 90f, 900f, 380f, w - 180f, Ink.SIGNAL, f.interBold)
                Ink.line(cv, if (d.hours in 0.95..1.05) "hour" else "hours", 104f, 1030f, f.interBold, 90f, Ink.TEXT, w - 200f)
            } else {
                label(cv, "I PICKED IT UP ON", 520f, lt)
                Ink.bigNumber(cv, count(d.readingDays, lt), 90f, 900f, 380f, w - 180f, Ink.SIGNAL, f.interBold)
                Ink.line(cv, if (d.readingDays == 1) "day" else "days", 104f, 1030f, f.interBold, 90f, Ink.TEXT, w - 200f)
            }
            val k = ease((lt - 0.8f) / 0.5f)
            val sub = listOfNotNull(
                d.readingDays.takeIf { it > 0 && d.minutes >= 30 }?.let { "on ${plural(it, "reading day")}" },
                d.bestDay?.let { (day, p) -> "best day ${day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${short(day)}: $p pages" },
            )
            sub.forEachIndexed { i, line -> Ink.line(cv, line, 104f, 1140f + i * 64f + (1 - k) * 20f, f.inter, 42f, Ink.alpha(Ink.TEXT, (255 * k).toInt()), w - 200f) }
        }

        private fun highlights(cv: Canvas, lt: Float, len: Float) {
            label(cv, "I HIGHLIGHTED", 260f, lt)
            Ink.bigNumber(cv, count(d.highlights, lt), 90f, 500f, 220f, w - 180f, Ink.SIGNAL, f.interBold)
            val word = (if (d.highlights == 1) "passage" else "passages") + (if (d.notes > 0) " · ${plural(d.notes, "note")}" else "")
            Ink.line(cv, word, 104f, 580f, f.interBold, 54f, Ink.TEXT, w - 200f)
            val pl = passage ?: return
            val pb = paper ?: return
            val k = ease((lt - 0.6f) / 0.5f)
            if (k <= 0f) return
            cv.save(); cv.translate(0f, (1 - k) * 80f)
            cv.drawBitmap(pb, card.left - pad, card.top - pad, Paint().apply { alpha = (255 * k).toInt() })
            cv.drawText("“", card.left + 50f, card.top + 190f, TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.loraBold; textSize = 220f; color = Ink.alpha(tint, (120 * k).toInt()) })
            // Typed out, a line at a time, clipped to the letters reached.
            val typeFrom = 1.0f; val typeFor = (len - typeFrom - 1.0f).coerceAtLeast(0.8f)
            val n = (pl.text.length * ((lt - typeFrom) / typeFor).coerceIn(0f, 1f)).toInt()
            val x0 = card.left + 70f; val y0 = card.top + 190f
            cv.save(); cv.translate(x0, y0)
            for (line in 0 until pl.lineCount) {
                val ls = pl.getLineStart(line)
                if (n <= ls) break
                val right = if (n >= pl.getLineEnd(line)) pl.width.toFloat() else pl.getPrimaryHorizontal(n)
                cv.save(); cv.clipRect(0f, pl.getLineTop(line).toFloat(), right, pl.getLineBottom(line).toFloat()); pl.draw(cv); cv.restore()
            }
            cv.restore()
            Ink.line(cv, title, card.left + 70f, card.bottom - 70f, f.interBold, 34f, Ink.alpha(0xFF221F1C.toInt(), (255 * k).toInt()), card.width() - 140f)
            cv.restore()
        }

        private fun verdict(cv: Canvas, lt: Float) {
            label(cv, "MY TAKE", if (review != null) 380f else 640f, lt)
            val r = d.rating
            val y = if (review != null) 560f else 900f
            if (r != null) {
                val full = r.toInt().coerceIn(0, 5); val half = r - full >= 0.5
                val total = full + if (half) 1 else 0
                val size = 150f
                for (i in 0 until total) {
                    val k = settle((lt - 0.25f - i * 0.18f) / 0.45f)
                    if (k <= 0f) continue
                    cv.save(); cv.translate(160f + i * 175f, y - size * 0.35f); cv.scale(k, k)
                    Ink.line(cv, if (i == full) "½" else "★", 0f, size * 0.35f, f.interBold, size, Ink.SIGNAL, 300f, Paint.Align.CENTER)
                    cv.restore()
                }
                if (review == null) {
                    val k = ease((lt - 1.4f) / 0.5f)
                    Ink.line(cv, "${if (r % 1.0 == 0.0) r.toInt().toString() else r.toString()} out of 5", 104f, y + 140f, f.inter, 48f, Ink.alpha(Ink.TEXT, (255 * k).toInt()), w - 200f)
                }
            }
            review?.let { rl ->
                val k = ease((lt - (if (r != null) 1.2f else 0.4f)) / 0.6f)
                cv.save(); cv.translate(100f, (if (r != null) y + 120f else 560f) + (1 - k) * 40f)
                val l = cv.saveLayerAlpha(0f, 0f, rl.width.toFloat(), rl.height.toFloat(), (255 * k).toInt())
                rl.draw(cv)
                cv.restoreToCount(l)
                cv.restore()
            }
        }

        private fun end(cv: Canvas, lt: Float) {
            val k = ease(lt / 0.7f)
            val sc = 0.55f
            cv.save(); cv.translate(w / 2f, 760f); cv.scale(sc, sc); cv.rotate(-2.5f)
            cv.drawBitmap(book, -book.width / 2f, -book.height / 2f, Paint(Paint.FILTER_BITMAP_FLAG))
            cv.restore()
            stamp(cv, f, w / 2f + bookW * sc / 2f - 10f, 560f, 0.8f, 255)
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.interBold; color = Ink.TEXT }
            Ink.text(cv, Ink.fit(title, tp, w - 160, 170, 64f, 40f, 1.05f, Layout.Alignment.ALIGN_CENTER), 80f, 1080f)
            val line = listOfNotNull(d.days?.let { plural(it, "day") }, d.pages?.let { "$it pages" }, d.rating?.let { stars(it) }).joinToString("  ·  ")
            Ink.line(cv, line, w / 2f, 1320f + (1 - k) * 20f, f.interBold, 44f, Ink.alpha(Ink.SIGNAL, (255 * k).toInt()), w - 160f, Paint.Align.CENTER)
            Ink.mark(cv, f, w / 2f, h - 140f)
        }

        fun recycle() { back.recycle(); book.recycle(); paper?.recycle() }
    }

    // ---- handing it on ----

    fun shareAll(context: Context, slides: List<Bitmap>, text: String) {
        val uris = ArrayList(slides.map { QuoteCard.cacheUri(context, it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/png"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share recap").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}

/**
 * The recap of a finished book: a video (watched live while it's worked out, then made, watched
 * and shared or saved) or a carousel of pictures to save, share or post on Threads in one go.
 */
@Composable
fun BookRecapSheet(vm: AppViewModel, state: Snapshot, book: Book, onClose: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val data = remember(book, state.readingDays, state.books) { BookRecap.data(state, book) }
    val caption = remember(data) { BookRecap.caption(data) }
    var mode by remember { mutableStateOf("Video") }
    var said by remember { mutableStateOf<String?>(null) }
    val coverArt by produceState<Bitmap?>(null, book.coverUrl) { value = withContext(Dispatchers.IO) { BookRecap.cover(context, book) } }
    var coverReady by remember { mutableStateOf(book.coverUrl == null) }
    LaunchedEffect(coverArt) { if (coverArt != null) coverReady = true }
    LaunchedEffect(book.coverUrl) { delay(4000); coverReady = true }

    // The video.
    var film by remember { mutableStateOf<BookRecap.Film?>(null) }
    LaunchedEffect(coverReady, data) {
        if (!coverReady) return@LaunchedEffect
        film = withContext(Dispatchers.Default) { runCatching { BookRecap.Film(context, data, coverArt) }.getOrNull() }
    }
    var making by remember { mutableStateOf<Float?>(null) }
    var made by remember { mutableStateOf<File?>(null) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(film, mode) {
        val sc = film ?: return@LaunchedEffect
        if (mode != "Video") return@LaunchedEffect
        val k = 0.34f
        val bufs = List(2) { Bitmap.createBitmap((sc.w * k).toInt(), (sc.h * k).toInt(), Bitmap.Config.ARGB_8888) }
        var which = 0
        val startAt = System.nanoTime()
        while (isActive) {
            val t = ((System.nanoTime() - startAt) / 1e9f) % (sc.duration + 0.8f)
            val b = bufs[which]
            withContext(Dispatchers.Default) { runCatching { b.eraseColor(0); val cv = Canvas(b); cv.save(); cv.scale(k, k); sc.draw(cv, t.coerceAtMost(sc.duration)); cv.restore() } }
            frame = b; tick++
            which = 1 - which
            delay(55)
        }
    }

    // The carousel.
    val slides by produceState<List<Bitmap>?>(null, coverReady, data) {
        if (!coverReady) return@produceState
        value = withContext(Dispatchers.Default) { runCatching { BookRecap.slides(context, data, coverArt) }.getOrNull() }
    }
    var posting by remember { mutableStateOf(false) }
    val threadsOn = state.threadsToken != null && state.threadsUserId != null

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DarkGadget {
            val c = Cp.colors
            Column(Modifier.fillMaxSize().background(c.page).windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconCircleButton(io.github.prithirajn.trackersync.R.drawable.lucide_x, "Close", onClose, tint = c.text)
                    Text("Recap", style = CpType.section, color = c.text, modifier = Modifier.padding(start = 12.dp, end = 12.dp))
                    Segmented(listOf("Video", "Carousel"), mode, { it }, { mode = it; said = null }, Modifier.weight(1f))
                }
                Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp).clip(RoundedCornerShape(20.dp)).background(c.screen).padding(10.dp)) {
                    Column(Modifier.fillMaxSize()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                            val what = if (mode == "Video") film?.let { "Story · ${Math.round(it.duration)}s" } ?: "Story" else slides?.let { "${it.size} pictures · 4:5" } ?: "Carousel"
                            DeviceLabel(QuoteCard.shortTitle(book.displayTitle) + " · " + what, color = c.screenDim, modifier = Modifier.weight(1f))
                            val status = when { mode == "Video" && made != null -> "Made"; mode == "Video" && making != null -> "Recording"; mode == "Video" -> "Live"; else -> "Ready" }
                            Led(true, size = 6.dp, color = if (status == "Made" || status == "Ready") androidx.compose.ui.graphics.Color(0xFF4ADE80) else c.accent)
                            Spacer(Modifier.width(6.dp))
                            DeviceLabel(status, color = c.accent)
                        }
                        Crossfade(mode, Modifier.fillMaxWidth().weight(1f), label = "recap") { m ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                if (m == "Video") {
                                    val file = made
                                    if (file != null) VideoPreview(file, 1080f / 1920f, Modifier.fillMaxHeight())
                                    else {
                                        val fr = frame
                                        if (fr == null) CircularProgressIndicator(Modifier.size(28.dp), color = c.accent, strokeWidth = 2.dp)
                                        else ComposeCanvas(Modifier.fillMaxHeight().aspectRatio(1080f / 1920f).clip(RoundedCornerShape(10.dp))) {
                                            tick.let { }
                                            drawImage(fr.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                                        }
                                    }
                                } else {
                                    val list = slides
                                    if (list == null) CircularProgressIndicator(Modifier.size(28.dp), color = c.accent, strokeWidth = 2.dp)
                                    else LazyRow(
                                        Modifier.fillMaxSize(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        itemsIndexed(list) { i, bmp ->
                                            Image(
                                                bmp.asImageBitmap(), "Picture ${i + 1} of ${list.size}", contentScale = ContentScale.Fit,
                                                modifier = Modifier.fillMaxHeight(0.92f).aspectRatio(BookRecap.CW.toFloat() / BookRecap.CH).clip(RoundedCornerShape(14.dp)),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (mode == "Carousel" && slides != null) {
                            Spacer(Modifier.height(8.dp))
                            Text("${slides?.size ?: 0} pictures, one stat each. Swipe to see them; they post together, in this order.", style = CpType.small, color = c.screenDim)
                        }
                    }
                }
                said?.let { AnimatedQuiet(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = c.textMid) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (mode == "Video") {
                        val file = made
                        if (file == null) {
                            BigButton(making?.let { "Recording… ${(it * 100).toInt()}%" } ?: "● Make video", {
                                if (film == null || making != null) return@BigButton
                                scope.launch {
                                    making = 0f; said = null
                                    val out = withContext(Dispatchers.Default) {
                                        runCatching {
                                            val rec = BookRecap.Film(context, data, coverArt)
                                            try { QuoteVideo.encode(context, rec) { p -> making = p } } finally { rec.recycle() }
                                        }.getOrNull()
                                    }
                                    making = null
                                    made = out
                                    if (out == null) said = "Couldn't make the video on this phone." else Haptics.confirm(view)
                                }
                            }, enabled = film != null)
                        } else {
                            BigButton("Again", { made = null }, Modifier.weight(0.8f), filled = false)
                            BigButton("Save", { said = if (QuoteVideo.save(context, file)) "Saved to Movies › ${io.github.prithirajn.trackersync.Edition.name}." else "Couldn't save here; use Share instead." }, Modifier.weight(1f), filled = false)
                            BigButton("Share", { runCatching { QuoteVideo.share(context, file, caption) } }, Modifier.weight(1.3f))
                        }
                    } else {
                        val list = slides
                        BigButton("Save all", {
                            list?.let { all -> val ok = all.all { QuoteCard.save(context, it) }; said = if (ok) "Saved ${all.size} pictures to Pictures" else "Couldn't save here; use Share instead." }
                        }, Modifier.weight(1f), filled = false, enabled = list != null)
                        BigButton("Share", { list?.let { runCatching { BookRecap.shareAll(context, it, caption) } } }, Modifier.weight(1f), filled = false, enabled = list != null)
                        BigButton(if (posting) "Posting…" else "Post to Threads", {
                            val all = list ?: return@BigButton
                            if (posting) return@BigButton
                            if (!threadsOn) {
                                said = when (QuoteCard.postToThreads(context, all.first(), caption)) { "app" -> "Opened in Threads (connect Threads under Services to post all of them at once)"; "web" -> "Threads opened in the browser"; else -> "Couldn't open Threads" }
                                return@BigButton
                            }
                            posting = true; said = null
                            vm.postRecapToThreads(book.document, all, caption, data.passage?.clip?.text) { err ->
                                posting = false
                                said = err ?: "Posted to Threads"
                                if (err == null) Haptics.confirm(view)
                            }
                        }, Modifier.weight(1.4f), look = KeyKind.INK, enabled = list != null)
                    }
                }
            }
        }
    }
}

/** After rating a finished book: offered its recap, to share wherever you like. */
@Composable
fun RecapOfferSheet(vm: AppViewModel, state: Snapshot, book: Book, onClose: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    if (open) { BookRecapSheet(vm, state, book, onClose); return }
    val c = Cp.colors
    val d = remember(book, state.readingDays) { BookRecap.data(state, book) }
    PlainSheet("Share how it went?", onClose) {
        Quiet("A short video for stories and reels, or a carousel of pictures: " +
            listOfNotNull(d.days?.let { "$it day" + if (it == 1) "" else "s" }, d.pages?.let { "$it pages" }, d.highlights.takeIf { it > 0 }?.let { "$it highlight" + if (it == 1) "" else "s" }, d.rating?.let { "your stars" })
                .joinToString(", ").ifEmpty { "the book and your stars" } + ".", color = c.textMid)
        VSpace(16)
        BigButton("\u2726 Make a recap", { open = true }, look = KeyKind.INK)
        VSpace(8)
        BigButton("Not now", onClose, filled = false)
    }
}
