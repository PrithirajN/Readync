package io.github.prithirajn.trackersync.ui

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import io.github.prithirajn.trackersync.R
import java.io.File

/**
 * A highlight drawn as a picture to share, in one of three layouts that are Tracker Sync's own:
 *
 * - Spotlight: the book's cover, softened, fills the card; the passage sits large over it, and a
 *   line along the bottom marks where in the book it was found.
 * - Highlighter: ruled paper with the passage marked in highlighter strokes, your note
 *   handwritten under it, and a stamp of where it was.
 * - Spine: the cover runs down the left like a book's spine with its title along it; the passage
 *   is on the page beside it, under a bookmark ribbon showing how far in it was.
 * - Book photo: the passage printed in a real-looking book on a desk, in its light (see [BookShot]).
 * - Editorial: a magazine pull-quote: a huge quote mark, the passage large, your note as a reader's aside.
 * - Typewriter: typed on paper, with your note in red pen in the margin.
 * - Index card: a ruled card taped to a desk, your note handwritten under the passage.
 * - Book card: the passage with the book itself, its real cover, title, series and author: on
 *   the cover's own colour, over the cover softened, or on paper with the cover set on it.
 * - System window: a LitRPG game's notification: the passage in a glowing panel, your note as
 *   the player's, and how far into the book as an experience bar.
 * - Wordprint: the passage's own fingerprint. Every word is a ray around a ring, as long as the
 *   word, in the order you read them, so no two passages make the same shape; an arc around it
 *   shows how far into the book you were. The passage sits under its print.
 *
 * Every layout takes its colour from the book's own cover. The newer ones come in three sizes
 * ([Size]): a feed post, a square, and a story.
 */
object QuoteCard {

    enum class Look(val label: String, val sized: Boolean = false) {
        SPOTLIGHT("Spotlight"), HIGHLIGHTER("Highlighter"), SPINE("Spine"), PAGE("Book photo"),
        EDITORIAL("Editorial", true), TYPEWRITER("Typewriter", true), INDEX_CARD("Index card", true), WORDPRINT("Wordprint", true),
        SYSTEM("System window", true), BOOK("Book card", true),
    }

    /** The picture's shape, for where it's going: a feed post (4:5), a square, or a story (9:16). */
    enum class Size(val label: String, val height: Int) { POST("Post", 1350), SQUARE("Square", 1080), STORY("Story", 1920) }

    data class Content(
        val text: String,
        val note: String?,
        val title: String,
        val author: String?,
        /** "Chapter 12", "p. 214", or null. */
        val where: String?,
        /** Where in the book (0–1), when known. */
        val percent: Double? = null,
        /** Its page in the book, when known. */
        val page: Int? = null,
        /** A note on its own, with no passage (a note on a bookmark): [text] is the note, shown in your hand, without quote marks. */
        val standalone: Boolean = false,
    )

    private const val W = 1080
    private const val DEFAULT_SEED = 0xFF4A7A62.toInt()

    private class Fonts(
        val lora: Typeface, val loraItalic: Typeface, val loraBold: Typeface, val inter: Typeface, val caveat: Typeface,
        val mono: Typeface = Typeface.MONOSPACE, val interBold: Typeface = Typeface.create(inter, Typeface.BOLD),
    )

    private fun fonts(context: Context?): Fonts {
        val lora = context?.let { runCatching { ResourcesCompat.getFont(it, R.font.lora) }.getOrNull() } ?: Typeface.SERIF
        val inter = context?.let { runCatching { ResourcesCompat.getFont(it, R.font.inter) }.getOrNull() } ?: Typeface.SANS_SERIF
        val caveat = context?.let { runCatching { ResourcesCompat.getFont(it, R.font.caveat) }.getOrNull() } ?: Typeface.SERIF
        val mono = context?.let { runCatching { ResourcesCompat.getFont(it, R.font.book_plexmono) }.getOrNull() } ?: Typeface.MONOSPACE
        val loraItalic = context?.let { runCatching { ResourcesCompat.getFont(it, R.font.book_lora_italic) }.getOrNull() } ?: Typeface.create(lora, Typeface.ITALIC)
        return Fonts(lora, loraItalic, Typeface.create(lora, Typeface.BOLD), inter, caveat, mono, Typeface.create(inter, Typeface.BOLD))
    }

    // ---- colour helpers ----

    private fun r(c: Int) = c shr 16 and 0xFF
    private fun g(c: Int) = c shr 8 and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    private fun shade(c: Int, f: Float) = argb(255, (r(c) * f).toInt(), (g(c) * f).toInt(), (b(c) * f).toInt())
    private fun lighten(c: Int, f: Float) = argb(255, (r(c) + (255 - r(c)) * f).toInt(), (g(c) + (255 - g(c)) * f).toInt(), (b(c) + (255 - b(c)) * f).toInt())
    private fun alpha(c: Int, a: Int) = (a shl 24) or (c and 0xFFFFFF)

    private fun layout(text: CharSequence, paint: TextPaint, width: Int, spacing: Float = 1.2f, maxLines: Int = Int.MAX_VALUE, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(align)
            .setLineSpacing(0f, spacing)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    /** The passage as large as fits [maxH], down to [min]px; cut short with … only past that. */
    private fun fit(text: String, paint: TextPaint, width: Int, maxH: Int, start: Float, min: Float, spacing: Float, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout {
        var size = start
        var l: StaticLayout
        while (true) {
            paint.textSize = size
            l = layout(text, paint, width, spacing, align = align)
            if (l.height <= maxH || size <= min) break
            size -= 3f
        }
        if (l.height > maxH) {
            val lineH = (l.height / l.lineCount.coerceAtLeast(1)).coerceAtLeast(1)
            l = layout(text, paint, width, spacing, maxLines = (maxH / lineH).coerceAtLeast(3), align = align)
        }
        return l
    }

    /** The cover scaled to fill [dst] (cropped, not stretched). */
    private fun drawCover(cv: Canvas, cover: Bitmap, dst: RectF, radius: Float = 0f, paintAlpha: Int = 255) {
        val sh = BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = maxOf(dst.width() / cover.width, dst.height() / cover.height)
        sh.setLocalMatrix(Matrix().apply {
            setScale(scale, scale)
            postTranslate(dst.left + (dst.width() - cover.width * scale) / 2, dst.top + (dst.height() - cover.height * scale) / 2)
        })
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = sh; alpha = paintAlpha }
        if (radius > 0) cv.drawRoundRect(dst, radius, radius, p) else cv.drawRect(dst, p)
    }

    /**
     * A soft-focus copy of the cover: a modest-size copy box-blurred three times (close to a true
     * gaussian), so the cover's shapes and colours still read and nothing turns blocky.
     */
    private fun soften(cover: Bitmap): Bitmap {
        val w = 200
        val h = (w.toFloat() * cover.height / cover.width).toInt().coerceIn(120, 600)
        val small = Bitmap.createScaledBitmap(cover, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(3) { boxPass(px, tmp, w, h, 3, horizontal = true); boxPass(tmp, px, w, h, 3, horizontal = false) }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** One sliding box average of radius [r] along rows (or columns), channel by channel. */
    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = 2 * r + 1
        for (line in 0 until lines) {
            fun at(i: Int): Int { val k = i.coerceIn(0, len - 1); return if (horizontal) src[line * w + k] else src[k * w + line] }
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) { val c = at(i); a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF }
            for (i in 0 until len) {
                val out = ((a / div) shl 24) or ((rr / div) shl 16) or ((g / div) shl 8) or (b / div)
                if (horizontal) dst[line * w + i] = out else dst[i * w + line] = out
                val add = at(i + r + 1); val drop = at(i - r)
                a += (add ushr 24) - (drop ushr 24)
                rr += ((add shr 16) and 0xFF) - ((drop shr 16) and 0xFF)
                g += ((add shr 8) and 0xFF) - ((drop shr 8) and 0xFF)
                b += (add and 0xFF) - (drop and 0xFF)
            }
        }
    }

    /**
     * The book's name without its series or subtitle: "The Gate of the Feral Gods: Dungeon Crawler
     * Carl Book Four" -> "The Gate of the Feral Gods". The whole name when there's nothing to take off.
     */
    internal fun shortTitle(t: String): String {
        val s = t.substringBefore(": ").substringBefore(" (").substringBefore(" [")
            .replace(Regex("(?i)[\\s,:–—-]*(book|vol\\.?|volume|part|#)\\s*#?(\\d+(\\.\\d+)?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|[ivx]+)\\s*$"), "")
            .trim().trimEnd(',', '-', '–', '—', ':').trim()
        return if (s.length >= 3) s else t
    }

    /**
     * A one-line label that fits [maxW]: the first of [variants] (fullest first) that fits at
     * [paint]'s size or a little smaller (to 82%, the last one to 70%), the paint left at that size.
     * Cut short with … only when even the shortest doesn't fit.
     */
    private fun fitLabel(paint: TextPaint, maxW: Float, vararg variants: String): String {
        val size = paint.textSize
        val tries = variants.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        tries.forEachIndexed { i, v ->
            val floor = if (i == tries.lastIndex) 0.70f else 0.82f
            var k = 1f
            while (k >= floor - 0.001f) {
                paint.textSize = size * k
                if (paint.measureText(v) <= maxW) return v
                k -= 0.04f
            }
        }
        // Nothing fits: the shortest, as small as allowed, cut short.
        paint.textSize = size * 0.70f
        return TextUtils.ellipsize(tries.lastOrNull().orEmpty(), paint, maxW, TextUtils.TruncateAt.END).toString()
    }

    /** "Found 37% of the way in" / "Chapter 12". */
    private fun whereLine(c: Content): String? {
        val pct = c.percent?.let { Math.round(it * 100).toInt() }
        return when {
            c.where != null && pct != null && !c.where.contains('%') -> "${c.where} · $pct% in"
            c.where != null -> c.where
            pct != null -> "Found $pct% of the way in"
            else -> null
        }
    }

    /** The small mark in a corner: a highlighter stroke with the app's name. */
    private fun brand(cv: Canvas, f: Fonts, x: Float, y: Float, ink: Int, mark: Int, rightAligned: Boolean) {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.inter; textSize = 24f; color = ink; letterSpacing = 0.16f; isFakeBoldText = true }
        val text = io.github.prithirajn.trackersync.Edition.name.uppercase()
        val w = p.measureText(text)
        val left = if (rightAligned) x - w else x
        cv.drawRoundRect(RectF(left - 8, y - 20, left + w + 8, y + 6), 6f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = alpha(mark, 110) })
        cv.drawText(text, left, y, p)
    }

    /** Draws the card. Blocking; call off the main thread. */
    fun render(
        context: Context?, content0: Content, look: Look, cover: Bitmap?, coverSeed: Int?, mark: Boolean = true, size: Size = Size.POST,
        /** For the Book card, which of its layouts (0–2); by default the same one for the same passage. */
        variant: Int? = null,
    ): Bitmap {
        val base = fonts(context)
        // Nothing to draw is drawn as an empty quote rather than failing.
        val content = content0.copy(text = content0.text.ifBlank { content0.note ?: "…" }, note = content0.note?.trim()?.takeIf { it.isNotEmpty() && !content0.standalone })
            .let { c -> if (c.standalone) c.copy(where = listOfNotNull("My note", c.where).joinToString(" · ")) else c }
        // A note on its own is in your hand where a passage would be set in type.
        val f = if (content.standalone) Fonts(base.caveat, base.caveat, base.loraBold, base.inter, base.caveat, base.mono, base.interBold) else base
        val seed = coverSeed ?: DEFAULT_SEED
        val h = size.height
        return when (look) {
            Look.EDITORIAL -> editorial(content, seed, f, mark, h)
            Look.TYPEWRITER -> typewriter(content, f, mark, h)
            Look.INDEX_CARD -> indexCard(content, seed, f, mark, h)
            Look.WORDPRINT -> wordprint(content, seed, f, mark, h)
            Look.SYSTEM -> systemWindow(content, seed, f, mark, h)
            Look.BOOK -> bookCard(content, cover, seed, f, mark, h, (variant ?: (content.text.hashCode() and 0x7FFFFFFF)) % BOOK_LAYOUTS)

            Look.SPOTLIGHT -> spotlight(content, cover, seed, f, mark)
            Look.HIGHLIGHTER -> highlighter(content, cover, seed, f, mark)
            Look.SPINE -> spine(content, cover, seed, f, mark)
            Look.PAGE -> BookShot.render(
                context,
                BookShot.Content(content0.text.ifBlank { content.text }, content.author, content.title, content0.where?.takeIf { !it.contains('%') }, content.page, note = content.note, coverColor = coverSeed, standalone = content.standalone),
                context?.let { BookShot.saved(it) } ?: BookShot.Options(),
                mark = mark,
            )
        }
    }

    // ---- Spotlight: the cover fills the card ----

    private fun spotlight(c: Content, cover: Bitmap?, seed: Int, f: Fonts, showMark: Boolean): Bitmap {
        val H = 1350
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val full = RectF(0f, 0f, W.toFloat(), H.toFloat())
        if (cover != null) drawCover(cv, soften(cover), full) else cv.drawColor(shade(seed, 0.45f))
        // Darken toward the bottom so the words always read.
        cv.drawRect(full, Paint().apply { shader = LinearGradient(0f, 0f, 0f, H.toFloat(), intArrayOf(0x8C000000.toInt(), 0xA6000000.toInt(), 0xE6000000.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP) })
        val glow = lighten(seed, 0.6f)

        if (showMark) brand(cv, f, 96f, 120f, 0xE6FFFFFF.toInt(), seed, rightAligned = false)

        // The passage, centred, as large as fits.
        val q = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.loraItalic; color = 0xFFFFFFFF.toInt(); setShadowLayer(12f, 0f, 2f, 0x66000000) }
        val textW = W - 2 * 120
        val noteH = if (c.note != null) 140 else 0
        val ql = fit(if (c.standalone) c.text.trim() else io.github.prithirajn.trackersync.trackers.quoted(c.text), q, textW, 640 - noteH, 66f, 34f, 1.25f, Layout.Alignment.ALIGN_CENTER)
        val qTop = 200f + (700 - noteH - ql.height) / 2f
        cv.save(); cv.translate(120f, qTop); ql.draw(cv); cv.restore()
        var y = qTop + ql.height + 36f
        // A short bar in the cover's colour under the passage.
        cv.drawRoundRect(RectF(W / 2f - 50, y, W / 2f + 50, y + 8), 4f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = glow })
        y += 40f
        c.note?.let { n ->
            val np = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.caveat; textSize = 46f; color = glow }
            val nl = layout("— $n", np, textW, 1.1f, maxLines = 2, align = Layout.Alignment.ALIGN_CENTER)
            cv.save(); cv.translate(120f, y); nl.draw(cv); cv.restore()
        }

        // The book: a sharp cover with its title and author.
        val bookTop = 1000f
        val coverRect = RectF(96f, bookTop, 96f + 110f, bookTop + 165f)
        if (cover != null) {
            cv.drawRoundRect(coverRect, 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000; setShadowLayer(20f, 0f, 8f, 0x99000000.toInt()) })
            drawCover(cv, cover, coverRect, 8f)
        }
        val tx = if (cover != null) coverRect.right + 32f else 96f
        val tw = (W - 96 - tx).toInt()
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.loraBold; textSize = 44f; color = 0xFFFFFFFF.toInt() }
        val tl = layout(c.title, tp, tw, 1.08f, maxLines = 2)
        cv.save(); cv.translate(tx, bookTop + 18f); tl.draw(cv); cv.restore()
        c.author?.let {
            val ap = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.inter; textSize = 30f; color = 0xCCFFFFFF.toInt() }
            cv.drawText(TextUtils.ellipsize(it, ap, tw.toFloat(), TextUtils.TruncateAt.END).toString(), tx, bookTop + 18f + tl.height + 44f, ap)
        }

        // Where in the book: a line with a dot at the passage.
        progressLine(cv, f, 96f, W - 96f, 1252f, c, glow, 0x40FFFFFF, 0xCCFFFFFF.toInt())
        return bmp
    }

    private fun progressLine(cv: Canvas, f: Fonts, left: Float, right: Float, y: Float, c: Content, fill: Int, track: Int, label: Int) {
        val line = whereLine(c) ?: return
        val lp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.inter; textSize = 26f; color = label; letterSpacing = 0.04f }
        cv.drawText(line, left, y - 26f, lp)
        val pct = c.percent ?: return
        cv.drawRoundRect(RectF(left, y, right, y + 6), 3f, 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = track })
        val x = left + (right - left) * pct.toFloat().coerceIn(0f, 1f)
        cv.drawRoundRect(RectF(left, y, x.coerceAtLeast(left + 6), y + 6), 3f, 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
        cv.drawCircle(x, y + 3, 13f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
        cv.drawCircle(x, y + 3, 13f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = 0xFFFFFFFF.toInt() })
    }

    // ---- Highlighter: ruled paper, the passage marked as if by hand ----

    private fun highlighter(c: Content, cover: Bitmap?, seed: Int, f: Fonts, showMark: Boolean): Bitmap {
        val paper = 0xFFFBF8F1.toInt()
        val ink = 0xFF2A2724.toInt()
        val rule = 0xFFD6E2EC.toInt()
        val margin = 0xFFE9A3A3.toInt()
        // The marker: the cover's colour made bright and see-through, a classic yellow when it's too dark.
        val marker = lighten(seed, 0.3f).let { m -> if (r(m) + g(m) + b(m) < 330) 0xFFFFE15A.toInt() else m }
        val left = 170f
        val textW = W - left.toInt() - 96

        val q = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.lora; color = ink }
        val ql = fit(c.text.trim(), q, textW, 980, 54f, 34f, 1.5f)
        val lineH = ql.height.toFloat() / ql.lineCount.coerceAtLeast(1)
        val noteP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.caveat; textSize = 52f; color = shade(seed, 0.55f).let { if (r(it) + g(it) + b(it) > 420) 0xFF2B4A8C.toInt() else it } }
        val nl = c.note?.let { layout(it, noteP, textW - 60, 1.05f, maxLines = 4) }

        val top = 250f
        val footer = 230f
        val H = (top + ql.height + (nl?.let { it.height + 90f } ?: 0f) + footer).toInt().coerceIn(1080, 1920)
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(paper)
        // Ruled lines in step with the passage's lines, and the red margin.
        val rp = Paint().apply { color = rule; strokeWidth = 2f }
        var ly = top + ql.getLineBaseline(0) + 14f - lineH * 3
        while (ly < H - 40) { if (ly > 60) cv.drawLine(0f, ly, W.toFloat(), ly, rp); ly += lineH }
        cv.drawLine(left - 36f, 0f, left - 36f, H.toFloat(), Paint().apply { color = margin; strokeWidth = 3f })

        // Where it's from, handwritten at the top.
        val hp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.caveat; textSize = 50f; color = ink }
        val byLine = c.author?.let { " · $it" } ?: ""
        val head = fitLabel(hp, (W - left - 96 - 130).toFloat(), "from " + c.title + byLine, "from " + shortTitle(c.title) + byLine, "from " + shortTitle(c.title))
        cv.drawText(head, left, 150f, hp)
        if (cover != null) {
            val cr = RectF(W - 96f - 92f, 70f, W - 96f, 70f + 138f)
            cv.save(); cv.rotate(4f, cr.centerX(), cr.centerY())
            cv.drawRect(RectF(cr.left - 8, cr.top - 8, cr.right + 8, cr.bottom + 8), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); setShadowLayer(10f, 0f, 4f, 0x33000000) })
            drawCover(cv, cover, cr)
            // A strip of tape holding it on.
            cv.drawRect(RectF(cr.centerX() - 40, cr.top - 22, cr.centerX() + 40, cr.top + 10), Paint().apply { color = 0x88F3E6B5.toInt() })
            cv.restore()
        }

        // The highlighter strokes, line by line, a little uneven like a real one.
        val mp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = alpha(marker, 140) }
        val rnd = java.util.Random(c.text.hashCode().toLong())
        for (i in 0 until ql.lineCount) {
            val l = left + ql.getLineLeft(i) - 10 + rnd.nextFloat() * 6
            val rr = left + ql.getLineRight(i) + 6 + rnd.nextFloat() * 14
            if (rr - l < 20) continue
            val baseline = top + ql.getLineBaseline(i)
            val tTop = baseline - q.textSize * 0.82f + rnd.nextFloat() * 6
            val tBot = baseline + q.textSize * 0.22f + rnd.nextFloat() * 6
            val path = Path().apply {
                moveTo(l + 6, tTop); lineTo(rr, tTop + rnd.nextFloat() * 4 - 2)
                quadTo(rr + 8, (tTop + tBot) / 2, rr - 2, tBot); lineTo(l, tBot + rnd.nextFloat() * 4 - 2)
                quadTo(l - 8, (tTop + tBot) / 2, l + 6, tTop); close()
            }
            cv.drawPath(path, mp)
        }
        cv.save(); cv.translate(left, top); ql.draw(cv); cv.restore()

        var y = top + ql.height + 70f
        nl?.let {
            // The arrow from Inter (the handwriting font hasn't one), in the note's ink.
            cv.drawText("\u21B3", left - 4, y + 40f, TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.inter; textSize = 44f; color = noteP.color })
            cv.save(); cv.translate(left + 50f, y); it.draw(cv); cv.restore()
            y += it.height + 20f
        }
        // A stamp with where it was.
        whereLine(c)?.let { w ->
            val sp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.inter; textSize = 28f; color = shade(seed, 0.6f).let { s -> if (r(s) + g(s) + b(s) > 420) 0xFFB23A3A.toInt() else s }; letterSpacing = 0.12f; isFakeBoldText = true }
            val text = w.uppercase()
            val sw = sp.measureText(text)
            val sx = left
            val sy = H - 110f
            cv.save(); cv.rotate(-3f, sx + sw / 2, sy)
            cv.drawRoundRect(RectF(sx - 18, sy - 40, sx + sw + 18, sy + 16), 10f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = sp.color })
            cv.drawText(text, sx, sy - 2, sp)
            cv.restore()
        }
        if (showMark) brand(cv, f, W - 96f, H - 96f, 0xFF6B655D.toInt(), marker, rightAligned = true)
        return bmp
    }

    // ---- Spine: the cover down the side, the page beside it ----

    private fun spine(c: Content, cover: Bitmap?, seed: Int, f: Fonts, showMark: Boolean): Bitmap {
        val H = 1350
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val spineW = 290f
        val page = lighten(seed, 0.9f)
        val ink = 0xFF221F1C.toInt()
        val deep = shade(seed, 0.5f)
        cv.drawColor(page)
        // The spine: the cover cropped tall, darkened at its edge like a binding.
        val sr = RectF(0f, 0f, spineW, H.toFloat())
        if (cover != null) drawCover(cv, cover, sr) else cv.drawRect(sr, Paint().apply { color = deep })
        cv.drawRect(sr, Paint().apply { shader = LinearGradient(0f, 0f, spineW, 0f, intArrayOf(0x33000000, 0x22000000, 0xB3000000.toInt()), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP) })
        // Binding lines across the spine.
        val band = Paint().apply { color = 0x66FFFFFF; strokeWidth = 3f }
        for (by in listOf(120f, 136f, H - 136f, H - 120f)) cv.drawLine(24f, by, spineW - 24f, by, band)
        // The title along the spine, bottom to top.
        val sp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.loraBold; textSize = 50f; color = 0xFFFFFFFF.toInt(); setShadowLayer(10f, 0f, 2f, 0xAA000000.toInt()) }
        val spineText = fitLabel(sp, H - 360f, c.title.uppercase(), shortTitle(c.title).uppercase())
        cv.save(); cv.rotate(-90f, spineW / 2, H / 2f)
        val tw = sp.measureText(spineText)
        cv.drawText(spineText, spineW / 2 - tw / 2, H / 2f + 18f, sp)
        cv.restore()
        // A shadow where the page meets the spine.
        cv.drawRect(RectF(spineW, 0f, spineW + 40f, H.toFloat()), Paint().apply { shader = LinearGradient(spineW, 0f, spineW + 40f, 0f, 0x40000000, 0x00000000, Shader.TileMode.CLAMP) })

        val left = spineW + 90f
        val textW = (W - left - 90).toInt()
        // The bookmark ribbon hanging from the top, with how far in.
        val rx = W - 150f
        val ribbon = Path().apply { moveTo(rx, 0f); lineTo(rx + 76, 0f); lineTo(rx + 76, 190f); lineTo(rx + 38, 160f); lineTo(rx, 190f); close() }
        cv.drawPath(ribbon, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = deep; setShadowLayer(8f, 0f, 4f, 0x44000000) })
        c.percent?.let { p ->
            val pp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.inter; textSize = 28f; color = 0xFFFFFFFF.toInt(); isFakeBoldText = true; textAlign = Paint.Align.CENTER }
            cv.drawText("${Math.round(p * 100)}%", rx + 38, 110f, pp)
        }

        // A big opening mark, then the passage on the page.
        val mark = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.loraBold; textSize = 240f; color = alpha(deep, 70) }
        if (!c.standalone) cv.drawText("“", left - 30f, 330f, mark)
        val q = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.lora; color = ink }
        val noteH = if (c.note != null) 170 else 0
        val ql = fit(c.text.trim(), q, textW, 760 - noteH, 56f, 32f, 1.32f)
        val qTop = 280f
        cv.save(); cv.translate(left, qTop); ql.draw(cv); cv.restore()
        var y = qTop + ql.height + 50f
        c.note?.let { n ->
            val np = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.caveat; textSize = 48f; color = deep }
            val nl = layout(n, np, textW, 1.05f, maxLines = 3)
            cv.save(); cv.translate(left, y); nl.draw(cv); cv.restore()
            y += nl.height + 20f
        }

        // At the foot of the page: author, where, and the mark.
        val foot = H - 170f
        cv.drawLine(left, foot, W - 90f, foot, Paint().apply { color = alpha(ink, 40); strokeWidth = 2f })
        val ap = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.inter; textSize = 30f; color = alpha(ink, 210); letterSpacing = 0.06f }
        val who = listOfNotNull(c.author, c.where).joinToString("  ·  ")
        if (who.isNotEmpty()) cv.drawText(TextUtils.ellipsize(who, ap, textW.toFloat(), TextUtils.TruncateAt.END).toString(), left, foot + 60f, ap)
        if (showMark) brand(cv, f, W - 90f, H - 60f, alpha(ink, 150), lighten(seed, 0.5f), rightAligned = true)
        return bmp
    }

    // ---- Book card: the passage with the book itself ----

    /** How many layouts the Book card has. */
    const val BOOK_LAYOUTS = 15

    /** The Book card's layouts by name, in order. */
    val BOOK_LAYOUT_NAMES = listOf(
        "Cover colour", "Over the cover", "On paper", "E-reader", "Polaroid", "Magazine", "Bookmark", "Night minimal",
        "Duotone", "Postcard", "Shelf", "Big mark", "Framed", "Ticket", "Now reading",
    )

    /**
     * The series a title names after its own name: "The Gate of the Feral Gods: Dungeon Crawler
     * Carl Book 4" -> "Dungeon Crawler Carl · Book 4"; "Words of Radiance (The Stormlight Archive,
     * #2)" -> "The Stormlight Archive · Book 2". Null when it names none.
     */
    internal fun seriesLine(title: String): String? {
        val rest = Regex("""\(([^)]+)\)\s*$""").find(title)?.groupValues?.get(1)
            ?: title.substringAfter(": ", "").takeIf { it.isNotBlank() }
            ?: return null
        val m = Regex("""(?i)^(.*?)[\s,:–—-]*(?:book|vol\.?|volume|part|#)\s*#?(\d+(?:\.\d+)?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|[ivx]+)\s*$""").find(rest.trim())
            ?: return null
        val name = m.groupValues[1].trim().trimEnd(',', ':', '-', '–', '—').trim()
        val words = mapOf("one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10", "eleven" to "11", "twelve" to "12")
        val n = m.groupValues[2].let { words[it.lowercase()] ?: it.uppercase() }
        return if (name.isEmpty()) "Book $n" else "$name · Book $n"
    }

    /** The cover as a real book: a soft shadow under it, rounded a touch, with light along its spine. */
    private fun bookCover(cv: Canvas, cover: Bitmap?, r: RectF, seed: Int, f: Fonts, title: String) {
        cv.drawRoundRect(RectF(r.left + 6f, r.top + 14f, r.right + 6f, r.bottom + 14f), 10f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x66000000; maskFilter = android.graphics.BlurMaskFilter(22f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        })
        if (cover != null) drawCover(cv, cover, r, 8f)
        else {
            // No cover: a plain cloth one in the cover colour, with the title on it.
            cv.drawRoundRect(r, 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = shade(seed, 0.7f) })
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.loraBold; textSize = r.width() * 0.13f; color = lighten(seed, 0.85f) }
            text(cv, layout(shortTitle(title), tp, (r.width() * 0.8f).toInt(), 1.1f, maxLines = 5, align = Layout.Alignment.ALIGN_CENTER), r.left + r.width() * 0.1f, r.top + r.height() * 0.18f)
        }
        // The spine's crease and a sheen across the cover.
        cv.drawRect(r.left, r.top, r.left + r.width() * 0.07f, r.bottom, Paint().apply {
            shader = LinearGradient(r.left, 0f, r.left + r.width() * 0.07f, 0f, intArrayOf(0x55FFFFFF, 0x22000000, 0x00000000), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        })
        cv.drawRoundRect(r, 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(r.left, r.top, r.right, r.bottom, intArrayOf(0x1FFFFFFF, 0x00FFFFFF, 0x14000000), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        })
    }

    private fun bookCard(c: Content, cover: Bitmap?, seed: Int, f: Fonts, showMark: Boolean, h: Int, layoutNo: Int): Bitmap {
        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val series = seriesLine(c.title)
        val name = shortTitle(c.title)
        val where = whereLine(c)
        val story = h > 1500
        val square = h < 1200
        when (layoutNo) {
            // 1. Over the cover itself, softened: the book on top, the passage under it, all centred.
            1 -> {
                if (cover != null) drawCover(cv, soften(cover), RectF(0f, 0f, W.toFloat(), h.toFloat())) else cv.drawColor(shade(seed, 0.4f))
                cv.drawRect(0f, 0f, W.toFloat(), h.toFloat(), Paint().apply {
                    shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0x8C000000.toInt(), 0xB3000000.toInt(), 0xE0000000.toInt()), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
                })
                val coverH = if (story) 400f else if (square) 230f else 300f
                val coverW = coverH * 2f / 3f
                val top = if (story) 190f else if (square) 70f else 100f
                bookCover(cv, cover, RectF(W / 2f - coverW / 2f, top, W / 2f + coverW / 2f, top + coverH), seed, f, c.title)
                var y = top + coverH + 64f
                val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.interBold; textSize = 40f; color = 0xFFFFFFFF.toInt() }
                val tl = layout(name, tp, W - 240, 1.1f, maxLines = 2, align = Layout.Alignment.ALIGN_CENTER)
                text(cv, tl, 120f, y)
                y += tl.height + 14f
                val sub = listOfNotNull(c.author, series).joinToString("  \u00B7  ")
                if (sub.isNotEmpty()) {
                    val sp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.inter; textSize = 28f; color = 0xCCFFFFFF.toInt() }
                    val st = fitLabel(sp, W - 240f, sub, listOfNotNull(c.author).joinToString())
                    cv.drawText(st, W / 2f - sp.measureText(st) / 2f, y + 30f, sp)
                    y += 50f
                }
                y += 50f
                cv.drawRoundRect(RectF(W / 2f - 40f, y, W / 2f + 40f, y + 6f), 3f, 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lighten(seed, 0.6f) })
                y += 56f
                val np = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.caveat; textSize = 50f; color = lighten(seed, 0.7f) }
                val nl = c.note?.let { layout(it, np, W - 240, 1.05f, maxLines = if (story) 4 else 2, align = Layout.Alignment.ALIGN_CENTER) }
                val foot = 120f
                val qp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.loraItalic; color = 0xFFFFFFFF.toInt() }
                val room = (h - y - foot - (nl?.let { it.height + 40f } ?: 0f)).toInt().coerceAtLeast(120)
                val ql = fit(if (c.standalone) c.text.trim() else io.github.prithirajn.trackersync.trackers.quoted(c.text), qp, W - 240, room, if (story) 84f else if (square) 64f else 74f, 28f, 1.24f, Layout.Alignment.ALIGN_CENTER)
                text(cv, ql, 120f, y)
                y += ql.height + 40f
                nl?.let { text(cv, it, 120f, y) }
                where?.let { w ->
                    val wp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.interBold; textSize = 22f; color = 0x99FFFFFF.toInt(); letterSpacing = 0.18f }
                    val t = fitLabel(wp, W - 240f, w.uppercase())
                    cv.drawText(t, W / 2f - wp.measureText(t) / 2f, h - 64f, wp)
                }
                if (showMark) brand(cv, f, W - 60f, 64f, 0xCCFFFFFF.toInt(), seed, rightAligned = true)
            }
            // 2. On paper, the book set on it at an angle, on a band of its colour.
            2 -> {
                val paper = 0xFFF7F2E8.toInt()
                val dark = 0xFF1F1B17.toInt()
                val deep = ink(seed)
                cv.drawColor(paper)
                val band = if (story) 470f else if (square) 300f else 360f
                cv.drawRect(0f, h - band, W.toFloat(), h.toFloat(), Paint().apply { color = deep })
                val left = 96f
                // The passage, with a bar in the book's colour beside it.
                val np = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.caveat; textSize = 50f; color = deep }
                val nl = c.note?.let { layout(it, np, W - 2 * left.toInt() - 30, 1.05f, maxLines = if (story) 4 else 2) }
                val top0 = if (story) 160f else if (square) 80f else 110f
                val qp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = if (c.standalone) f.caveat else f.lora; color = dark }
                // The book leans up into the page: the passage keeps clear of it.
                val bottom = h - band - band * 0.42f
                val room = (bottom - 40f - top0 - (nl?.let { it.height + 50f } ?: 0f)).toInt().coerceAtLeast(120)
                val ql = fit(c.text.trim(), qp, W - 2 * left.toInt() - 30, room, if (story) 96f else if (square) 74f else 88f, 28f, 1.2f)
                val top = top0 + maxOf(0f, (bottom - 40f - top0 - ql.height - (nl?.let { it.height + 50f } ?: 0f)) * 0.42f)
                cv.drawRoundRect(RectF(left, top + 6f, left + 8f, top + ql.height - 6f), 4f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = deep })
                text(cv, ql, left + 34f, top)
                nl?.let { text(cv, it, left + 34f, top + ql.height + 44f) }
                // The book, leaning, half on the band; its name beside it in white.
                val coverH = band * 1.05f
                val coverW = coverH * 2f / 3f
                val cx = W - 96f - coverW
                val cyTop = h - band - coverH * 0.38f
                cv.save(); cv.rotate(-4f, cx + coverW / 2f, cyTop + coverH / 2f)
                bookCover(cv, cover, RectF(cx, cyTop, cx + coverW, cyTop + coverH), seed, f, c.title)
                cv.restore()
                val textW = (cx - left - 40f).toInt()
                val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.loraBold; textSize = if (square) 40f else 46f; color = 0xFFFFFFFF.toInt() }
                val tl = layout(name, tp, textW, 1.1f, maxLines = 3)
                var y = h - band + 70f
                text(cv, tl, left, y)
                y += tl.height + 16f
                val ap = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.inter; textSize = 30f; color = 0xE6FFFFFF.toInt() }
                c.author?.let { a -> cv.drawText(fitLabel(ap, textW.toFloat(), a), left, y + 28f, ap); y += 46f }
                val sp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.interBold; textSize = 22f; color = 0xB3FFFFFF.toInt(); letterSpacing = 0.14f }
                listOfNotNull(series, where).firstOrNull()?.let { l -> cv.drawText(fitLabel(sp, textW.toFloat(), l.uppercase()), left, y + 34f, sp) }
                if (showMark) brand(cv, f, left, h - 50f, 0xCCFFFFFF.toInt(), lighten(deep, 0.3f), rightAligned = false)
            }
            3 -> eReader(cv, c, cover, seed, f, h, name, series, where)
            4 -> polaroid(cv, c, cover, seed, f, h, name, series, where)
            5 -> magazine(cv, c, cover, seed, f, h, name, series)
            6 -> bookmark(cv, c, cover, seed, f, h, name, series, where)
            7 -> nightMinimal(cv, c, cover, seed, f, h, name)
            8 -> duotone(cv, c, cover, seed, f, h, name, series, where)
            9 -> postcard(cv, c, cover, seed, f, h, name, series)
            10 -> shelf(cv, c, cover, seed, f, h, name, series)
            11 -> bigMark(cv, c, cover, seed, f, h, name, series)
            12 -> framed(cv, c, cover, seed, f, h, name, series)
            13 -> ticket(cv, c, cover, seed, f, h, name, series, where)
            14 -> nowReading(cv, c, cover, seed, f, h, name)
            // 0. The cover's own colour: the passage large, the book along the bottom (cover, name, series, author).
            else -> {
                var bg = seed
                repeat(16) { if (lum(bg) > 70f) bg = shade(bg, 0.86f) }
                cv.drawColor(bg)
                cv.drawRect(0f, 0f, W.toFloat(), h.toFloat(), Paint().apply {
                    shader = RadialGradient(W * 0.15f, h * 0.1f, W * 1.1f, intArrayOf(lighten(bg, 0.14f), bg, shade(bg, 0.8f)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                })
                val glow = lighten(seed, 0.62f)
                val left = 96f
                val strip = if (story) 330f else if (square) 250f else 290f
                val stripTop = h - 64f - strip
                // The passage: a big quote mark, then the words as large as fit.
                val mp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.loraBold; textSize = 170f; color = alpha(glow, 150) }
                val top0 = if (story) 150f else if (square) 70f else 96f
                val np = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.caveat; textSize = 52f; color = glow }
                val nl = c.note?.let { layout(it, np, W - 2 * left.toInt(), 1.05f, maxLines = if (story) 4 else 2) }
                val markH = if (c.standalone) 20f else 150f
                val qp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = if (c.standalone) f.caveat else f.lora; color = 0xFFFFFFFF.toInt() }
                val room = (stripTop - 50f - top0 - markH - (nl?.let { it.height + 44f } ?: 0f)).toInt().coerceAtLeast(120)
                // As large as fits: a short line big, a long passage smaller.
                val ql = fit(c.text.trim(), qp, W - 2 * left.toInt(), room, if (story) 104f else if (square) 80f else 96f, 30f, 1.16f)
                // The whole block a little above the middle of the space over the book.
                val block = markH + ql.height + (nl?.let { it.height + 44f } ?: 0f)
                val top = top0 + maxOf(0f, (stripTop - 50f - top0 - block) * 0.4f)
                if (!c.standalone) cv.drawText("\u201C", left - 8f, top + 128f, mp)
                val qTop = top + markH
                text(cv, ql, left, qTop)
                nl?.let {
                    val ny = qTop + ql.height + 44f
                    cv.drawRoundRect(RectF(left, ny - 22f, left + 60f, ny - 16f), 3f, 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = alpha(glow, 180) })
                    text(cv, it, left, ny)
                }
                // The book along the bottom, on a darker panel.
                val panel = RectF(64f, stripTop, W - 64f, h - 64f)
                cv.drawRoundRect(panel, 36f, 36f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 })
                val coverH = strip - 70f
                val coverW = coverH * 2f / 3f
                val coverR = RectF(panel.right - 40f - coverW, panel.top + 35f, panel.right - 40f, panel.top + 35f + coverH)
                bookCover(cv, cover, coverR, seed, f, c.title)
                val textW = (coverR.left - panel.left - 80f).toInt()
                val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.interBold; textSize = if (square) 38f else 44f; color = 0xFFFFFFFF.toInt() }
                val tl = layout(name, tp, textW, 1.12f, maxLines = 2)
                val sp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.inter; textSize = 28f; color = alpha(glow, 230) }
                val ap = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.inter; textSize = 30f; color = 0xCCFFFFFF.toInt() }
                val wp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.interBold; textSize = 21f; color = 0x99FFFFFF.toInt(); letterSpacing = 0.16f }
                val blockH = tl.height + (if (series != null) 42f else 0f) + (if (c.author != null) 44f else 0f) + (if (where != null) 46f else 0f)
                var y = panel.top + (panel.height() - blockH) / 2f
                text(cv, tl, panel.left + 40f, y)
                y += tl.height
                series?.let { s -> cv.drawText(fitLabel(sp, textW.toFloat(), s), panel.left + 40f, y + 34f, sp); y += 42f }
                c.author?.let { a -> cv.drawText(fitLabel(ap, textW.toFloat(), a), panel.left + 40f, y + 36f, ap); y += 44f }
                where?.let { w -> cv.drawText(fitLabel(wp, textW.toFloat(), w.uppercase()), panel.left + 40f, y + 38f, wp) }
                if (showMark) brand(cv, f, W - 72f, 70f, 0xCCFFFFFF.toInt(), seed, rightAligned = true)
            }
        }
        return bmp
    }

    // ---- A review, as square slides to swipe through (for a Threads carousel) ----

    /** A book's rating and review, to post. [dnfAt] is how far in you stopped, for a book you didn't finish. */
    data class Review(val title: String, val author: String?, val rating: Double?, val text: String, val dnfAt: Int? = null)

    /** How many colour schemes the review slides come in (the book's colour, paper, night). */
    const val REVIEW_THEMES = 3

    /**
     * A review written in Markdown as plain words for a picture: formatting marks taken off, links
     * as their words, and ||spoilers|| hidden.
     */
    internal fun cleanReview(md: String): String = md
        .replace(Regex("""\|\|[\s\S]*?\|\|"""), "(spoiler hidden)")
        .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
        .replace(Regex("""(?m)^\s{0,3}(#{1,6}|>)\s*"""), "")
        .replace(Regex("""(?m)^\s*[-+*]\s+"""), "\u2022 ")
        .replace(Regex("""(\*\*|__|~~|`)"""), "")
        .replace(Regex("""(?<![\w*])\*(?!\s)([^*\n]+?)\*(?!\w)"""), "$1")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()

    /**
     * [text] in pieces that each fill a slide ([maxH] tall at [paint]'s size): by paragraph and
     * sentence, a word at a time only for a sentence too long for a slide.
     */
    internal fun reviewChunks(text: String, paint: TextPaint, width: Int, maxH: Int, spacing: Float): List<String> {
        fun fits(t: String) = layout(t, paint, width, spacing).height <= maxH
        val pieces = ArrayList<String>()
        for ((pi, para) in text.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }.withIndex()) {
            val sentences = Regex("""[^.!?…]+[.!?…]+["”’)\]]*\s*|[^.!?…]+$""").findAll(para).map { it.value }.toList().ifEmpty { listOf(para) }
            sentences.forEachIndexed { si, sent -> pieces += (if (pi > 0 && si == 0) "\n\n" else "") + sent }
        }
        val out = ArrayList<String>()
        var cur = ""
        for (p in pieces) {
            val next = (cur + p).let { if (cur.isEmpty()) it.trimStart() else it }
            if (fits(next)) { cur = next; continue }
            if (cur.isNotBlank()) { out += cur.trim(); cur = "" }
            val piece = p.trimStart()
            if (fits(piece)) { cur = piece; continue }
            // One sentence longer than a slide: a word at a time.
            for (w in piece.split(' ')) {
                val n = if (cur.isEmpty()) w else "$cur $w"
                if (fits(n)) cur = n else { if (cur.isNotBlank()) out += cur.trim(); cur = w }
            }
        }
        if (cur.isNotBlank()) out += cur.trim()
        return out
    }

    /** Five stars from [x] (its left), [size] across each, filled to [rating] (halves too). */
    private fun drawStars(cv: Canvas, x: Float, cy: Float, size: Float, rating: Double, on: Int, off: Int) {
        fun star(cx: Float): Path = Path().apply {
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) size / 2f else size * 0.21f
                val a = Math.toRadians((-90.0 + i * 36.0))
                val px = cx + (r * Math.cos(a)).toFloat(); val py = cy + (r * Math.sin(a)).toFloat()
                if (i == 0) moveTo(px, py) else lineTo(px, py)
            }
            close()
        }
        val onP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = on }
        val offP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = off }
        for (i in 0 until 5) {
            val cx = x + size / 2f + i * size * 1.18f
            val p = star(cx)
            cv.drawPath(p, offP)
            val fill = (rating - i).coerceIn(0.0, 1.0).toFloat()
            if (fill > 0f) { cv.save(); cv.clipRect(cx - size / 2f, cy - size / 2f, cx - size / 2f + size * fill, cy + size / 2f); cv.drawPath(p, onP); cv.restore() }
        }
    }

    /**
     * The review as square slides: the book first (its cover, name, your stars, where you stopped
     * if you didn't finish), then your words a slide at a time, numbered, in one of the
     * [REVIEW_THEMES] colour schemes. At most [maxSlides] (the words cut short past that).
     */
    fun reviewSlides(context: Context?, r: Review, cover: Bitmap?, coverSeed: Int?, theme: Int, maxSlides: Int = 10): List<Bitmap> {
        val f = fonts(context)
        val seed = coverSeed ?: DEFAULT_SEED
        val S = 1080
        val name = shortTitle(r.title)
        val series = seriesLine(r.title)
        val t = theme.mod(REVIEW_THEMES)
        val bg: Int; val ink: Int; val soft: Int; val accent: Int; val starOff: Int
        when (t) {
            1 -> { bg = 0xFFF7F2E8.toInt(); ink = 0xFF1F1B17.toInt(); soft = 0xFF6B6359.toInt(); accent = deepOf(seed, 90f); starOff = 0x26000000 }
            2 -> { bg = 0xFF0B0B10.toInt(); ink = 0xFFF2F0EB.toInt(); soft = 0x99FFFFFF.toInt(); accent = lighten(seed, 0.55f); starOff = 0x33FFFFFF }
            else -> { bg = deepOf(seed, 60f); ink = 0xFFFFFFFF.toInt(); soft = 0xBFFFFFFF.toInt(); accent = lighten(seed, 0.62f); starOff = 0x33FFFFFF }
        }
        val gold = 0xFFFFC94A.toInt()
        fun base(): Pair<Bitmap, Canvas> {
            val b = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
            val cv = Canvas(b)
            cv.drawColor(bg)
            if (t == 0) cv.drawRect(0f, 0f, S.toFloat(), S.toFloat(), Paint().apply {
                shader = RadialGradient(S * 0.15f, S * 0.1f, S * 1.1f, intArrayOf(lighten(bg, 0.14f), bg, shade(bg, 0.8f)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            })
            if (t == 2 && cover != null) {
                drawCover(cv, soften(cover), RectF(-200f, -200f, S + 200f, S * 0.7f), paintAlpha = 70)
                // Fading into the night below, with no edge.
                cv.drawRect(0f, S * 0.3f, S.toFloat(), S * 0.7f + 2f, Paint().apply { shader = LinearGradient(0f, S * 0.3f, 0f, S * 0.7f, alpha(bg, 0), bg, Shader.TileMode.CLAMP) })
            }
            return b to cv
        }
        val words = cleanReview(r.text)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.lora; textSize = 46f; color = ink }
        val boxW = S - 180
        val boxH = S - 220 - 170
        var chunks = if (words.isBlank()) emptyList() else reviewChunks(words, tp, boxW, boxH, 1.38f)
        if (chunks.size > maxSlides - 1) chunks = chunks.take(maxSlides - 1).let { c -> c.dropLast(1) + (c.last().trimEnd() + "\u2026") }
        val out = ArrayList<Bitmap>()
        // 1. The book and your stars.
        run {
            val (b, cv) = base()
            val ch = 480f; val cw = 320f
            bookCover(cv, cover, RectF(90f, (S - ch) / 2f, 90f + cw, (S + ch) / 2f), seed, f, r.title)
            val x = 90f + cw + 70f
            val w = (S - x - 80f).toInt()
            var y = 300f
            line(cv, if (r.dnfAt != null) "MY REVIEW \u00B7 DID NOT FINISH" else "MY REVIEW", x, y, f.interBold, 22f, accent, w.toFloat(), spacing = 0.22f)
            y += 26f
            y += nameBlock(cv, name, x, y, w, f.loraBold, 52f, ink, 3) + 10f
            line(cv, r.author, x, y + 32f, f.inter, 30f, soft, w.toFloat()); if (r.author != null) y += 46f
            series?.let { line(cv, it, x, y + 30f, f.inter, 24f, soft, w.toFloat()); y += 42f }
            r.rating?.let { drawStars(cv, x, y + 70f, 52f, it, gold, starOff); y += 110f }
            r.dnfAt?.let { p ->
                val chip = "Stopped at $p%"
                val cp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.interBold; textSize = 24f; color = ink }
                val cwid = cp.measureText(chip) + 40f
                cv.drawRoundRect(RectF(x, y + 20f, x + cwid, y + 66f), 23f, 23f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = alpha(accent, 70) })
                cv.drawText(chip, x + 20f, y + 52f, cp)
            }
            if (chunks.isNotEmpty()) line(cv, "Swipe to read \u2192", S - 80f, S - 70f, f.interBold, 22f, soft, 400f, Paint.Align.RIGHT, 0.08f)
            out += b
        }
        // 2. Your words, a slide at a time.
        chunks.forEachIndexed { i, chunk ->
            val (b, cv) = base()
            // A small cover and the book at the top, so each slide stands on its own.
            bookCover(cv, cover, RectF(90f, 80f, 150f, 170f), seed, f, r.title)
            line(cv, name, 176f, 118f, f.interBold, 28f, ink, S - 300f)
            line(cv, listOfNotNull(r.author, if (r.dnfAt != null) "DNF at ${r.dnfAt}%" else null).joinToString("  \u00B7  "), 176f, 156f, f.inter, 24f, soft, S - 300f)
            line(cv, "${i + 2}/${chunks.size + 1}", S - 90f, 118f, f.interBold, 24f, soft, 120f, Paint.Align.RIGHT, 0.1f)
            cv.drawRect(90f, 205f, S - 90f, 207f, Paint().apply { color = alpha(accent, 120) })
            val l = layout(chunk, tp, boxW, 1.38f)
            text(cv, l, 90f, 240f + maxOf(0f, (boxH - l.height) * 0.2f))
            // The stars again on the last; an arrow on the others.
            if (i == chunks.lastIndex) r.rating?.let { drawStars(cv, 90f, S - 70f, 30f, it, gold, starOff) }
            else line(cv, "\u2192", S - 90f, S - 58f, f.interBold, 34f, soft, 60f, Paint.Align.RIGHT)
            out += b
        }
        return out
    }

    // ---- the Book card's other layouts ----

    /** [color] darkened until it's no brighter than [limit] (for a background, or ink on a light one). */
    private fun deepOf(color: Int, limit: Float): Int {
        var c = color
        repeat(16) { if (lum(c) > limit) c = shade(c, 0.86f) }
        return c
    }

    /**
     * The passage (and your note under it) as large as fits in [r], from [start]px down; placed
     * at [v] of the room left over (0 the top, 1 the bottom). Returns where it starts and ends.
     */
    private fun quoteIn(
        cv: Canvas, c: Content, f: Fonts, r: RectF, face: Typeface, color: Int, noteColor: Int, start: Float,
        align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL, v: Float = 0.4f, spacing: Float = 1.2f, quoted: Boolean = true,
    ): Pair<Float, Float> {
        val words = if (c.standalone || !quoted) c.text.trim() else io.github.prithirajn.trackersync.trackers.quoted(c.text.trim())
        // Your note in your hand under the passage, large enough to read on a phone.
        val np = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.caveat; textSize = (start * 0.66f).coerceIn(48f, 62f); this.color = noteColor }
        val nl = c.note?.let { layout("\u2014 $it", np, r.width().toInt(), 1.05f, maxLines = 3, align = align) }
        val noteH = nl?.let { it.height + 36f } ?: 0f
        val qp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = if (c.standalone) f.caveat else face; this.color = color }
        val ql = fit(words, qp, r.width().toInt(), (r.height() - noteH).toInt().coerceAtLeast(100), start, 28f, spacing, align)
        val block = ql.height + noteH
        val top = r.top + maxOf(0f, (r.height() - block) * v)
        text(cv, ql, r.left, top)
        nl?.let { text(cv, it, r.left, top + ql.height + 36f) }
        return top to top + block
    }

    /** One line of text that fits [maxW] (a little smaller, then cut short), at [x], [y]. */
    private fun line(cv: Canvas, t: String?, x: Float, y: Float, face: Typeface, size: Float, color: Int, maxW: Float, align: Paint.Align = Paint.Align.LEFT, spacing: Float = 0f) {
        if (t.isNullOrBlank()) return
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = size; this.color = color; letterSpacing = spacing; textAlign = align }
        cv.drawText(fitLabel(p, maxW, t), x, y, p)
    }

    /** The book's name in up to [lines] lines; returns how tall it came out. */
    private fun nameBlock(cv: Canvas, name: String, x: Float, y: Float, w: Int, face: Typeface, size: Float, color: Int, lines: Int = 2, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): Float {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = size; this.color = color }
        val l = layout(name, p, w, 1.1f, maxLines = lines, align = align)
        text(cv, l, x, y)
        return l.height.toFloat()
    }

    /** The cover filling [r] (cropped), or a block in the book's colour when there's none. */
    private fun coverFill(cv: Canvas, cover: Bitmap?, r: RectF, seed: Int) {
        if (cover != null) drawCover(cv, cover, r) else cv.drawRect(r, Paint().apply { color = shade(seed, 0.6f) })
    }

    private fun pctOf(c: Content) = c.percent?.let { "${Math.round(it * 100)}%" }

    /**
     * 4. The passage on an e-reader: the book's cover blurred behind, and in front a small reading
     * device whose grey e-ink screen shows your passage, with the book and how far in along its
     * top and a progress line at its foot.
     */
    private fun eReader(cv: Canvas, c: Content, cover: Bitmap?, seed: Int, f: Fonts, h: Int, name: String, series: String?, where: String?) {
        val full = RectF(0f, 0f, W.toFloat(), h.toFloat())
        if (cover != null) drawCover(cv, soften(cover), full) else cv.drawColor(shade(seed, 0.45f))
        cv.drawRect(full, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0x59000000, 0x8C000000.toInt(), 0xCC000000.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        })
        val story = h > 1500
        val square = h < 1200
        // The device: a graphite body with rounded corners and a soft shadow under it.
        val margin = if (square) 120f else 110f
        val top = if (story) 240f else if (square) 70f else 90f
        val bottom = h - (if (story) 300f else if (square) 150f else 190f)
        val body = RectF(margin, top, W - margin, bottom)
        cv.drawRoundRect(RectF(body.left + 10f, body.top + 26f, body.right + 10f, body.bottom + 26f), 56f, 56f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x99000000.toInt(); maskFilter = android.graphics.BlurMaskFilter(40f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        })
        cv.drawRoundRect(body, 56f, 56f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, body.top, 0f, body.bottom, 0xFF34322F.toInt(), 0xFF1E1D1B.toInt(), Shader.TileMode.CLAMP)
        })
        cv.drawRoundRect(body, 56f, 56f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x33FFFFFF })
        // The screen: warm grey e-ink paper, with a faint grain.
        val bez = 46f
        val chin = if (square) 70f else 96f
        val scr = RectF(body.left + bez, body.top + bez, body.right - bez, body.bottom - chin)
        cv.drawRoundRect(scr, 14f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE9E6DD.toInt() })
        cv.drawRoundRect(scr, 14f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(scr.left, scr.top, scr.right, scr.bottom, intArrayOf(0x14FFFFFF, 0x00000000, 0x12000000), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        })
        val ink = 0xFF26241F.toInt()
        val dim = 0xFF8B867B.toInt()
        val pad = 44f
        val x = scr.left + pad
        val w = scr.width() - 2 * pad
        // The screen's top line: the book on the left, how far in on the right.
        val pct = pctOf(c)
        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.inter; textSize = 22f; color = dim; letterSpacing = 0.04f }
        val pctW = pct?.let { sp.measureText(it) + 20f } ?: 0f
        cv.drawText(TextUtils.ellipsize(name, TextPaint(sp), w - pctW, TextUtils.TruncateAt.END).toString(), x, scr.top + 50f, sp)
        pct?.let { sp.textAlign = Paint.Align.RIGHT; cv.drawText(it, scr.right - pad, scr.top + 50f, sp); sp.textAlign = Paint.Align.LEFT }
        cv.drawLine(x, scr.top + 72f, scr.right - pad, scr.top + 72f, Paint().apply { color = 0x22000000; strokeWidth = 2f })
        // The passage, set like a page of the book.
        quoteIn(cv, c, f, RectF(x, scr.top + 104f, x + w, scr.bottom - 96f), f.lora, ink, 0xFF5A5247.toInt(), 64f, v = 0.4f, spacing = 1.36f)
        // Where it is, and a progress line along the foot of the screen.
        line(cv, where, x, scr.bottom - 54f, f.inter, 22f, dim, w)
        val barY = scr.bottom - 30f
        cv.drawRoundRect(RectF(x, barY, x + w, barY + 6f), 3f, 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 })
        c.percent?.let { p -> cv.drawRoundRect(RectF(x, barY, x + w * p.toFloat().coerceIn(0f, 1f), barY + 6f), 3f, 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }) }
        // A little power light on the chin.
        cv.drawCircle(body.centerX(), body.bottom - chin / 2f, 7f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lighten(seed, 0.55f); setShadowLayer(10f, 0f, 0f, lighten(seed, 0.55f)) })
        // Under the device: the book, its author and series, in white.
        val ty = bottom + (if (square) 70f else 90f)
        line(cv, name, W / 2f, ty, f.interBold, 36f, 0xFFFFFFFF.toInt(), W - 2 * margin, Paint.Align.CENTER)
        line(cv, listOfNotNull(c.author, series).joinToString("  \u00B7  "), W / 2f, ty + 48f, f.inter, 26f, 0xB3FFFFFF.toInt(), W - 2 * margin, Paint.Align.CENTER)
    }

    /** 5. A polaroid of the cover, taped on; the passage under it. */
    private fun polaroid(cv: Canvas, c: Content, cover: Bitmap?, seed: Int, f: Fonts, h: Int, name: String, series: String?, where: String?) {
        cv.drawColor(deepOf(seed, 55f))
        val k = if (h < 1200) 0.82f else 1f
        cv.save()
        cv.translate(250f * k + 20f, 330f * k)
        cv.rotate(-6f)
        cv.scale(k, k)
        cv.drawRect(-170f + 8f, -230f + 16f, 170f + 8f, 240f + 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x80000000.toInt(); maskFilter = android.graphics.BlurMaskFilter(26f, android.graphics.BlurMaskFilter.Blur.NORMAL) })
        cv.drawRect(-170f, -230f, 170f, 240f, Paint().apply { color = 0xFFFBFAF6.toInt() })
        coverFill(cv, cover, RectF(-145f, -205f, 145f, 125f), seed)
        line(cv, name, 0f, 190f, f.caveat, 40f, 0xFF333333.toInt(), 300f, Paint.Align.CENTER)
        cv.restore()
        cv.save(); cv.translate(250f * k + 20f, 105f * k); cv.rotate(-6f)
        cv.drawRect(-60f, -18f, 60f, 18f, Paint().apply { color = 0xBFF0E6C8.toInt() })
        cv.restore()
        val tx = 500f * k + 20f
        val tw = (W - tx - 80f).toInt()
        val nh = nameBlock(cv, name, tx, 200f * k, tw, f.interBold, 40f, 0xFFFFFFFF.toInt(), 3)
        line(cv, c.author, tx, 200f * k + nh + 40f, f.inter, 30f, 0xBFFFFFFF.toInt(), tw.toFloat())
        line(cv, series?.uppercase(), tx, 200f * k + nh + 86f, f.interBold, 20f, lighten(seed, 0.6f), tw.toFloat(), spacing = 0.14f)
        quoteIn(cv, c, f, RectF(96f, 620f * k, W - 96f, h - 110f), f.loraItalic, 0xFFFFFFFF.toInt(), lighten(seed, 0.7f), 76f, v = 0.3f)
        line(cv, where?.uppercase(), 96f, h - 64f, f.interBold, 20f, 0x8CFFFFFF.toInt(), W - 192f, spacing = 0.16f)
    }

    /** 6. The cover across the top, fading into its colour; the passage in bold type below. */
    private fun magazine(cv: Canvas, c: Content, cover: Bitmap?, seed: Int, f: Fonts, h: Int, name: String, series: String?) {
        val deep = deepOf(seed, 45f)
        cv.drawColor(deep)
        val top = h * 0.46f
        coverFill(cv, cover, RectF(0f, 0f, W.toFloat(), top), seed)
        cv.drawRect(0f, top * 0.45f, W.toFloat(), top + 2f, Paint().apply { shader = LinearGradient(0f, top * 0.45f, 0f, top, alpha(deep, 0), deep, Shader.TileMode.CLAMP) })
        line(cv, name.uppercase(), 80f, top - 40f, f.interBold, 26f, 0xFFFFFFFF.toInt(), W - 160f, spacing = 0.2f)
        quoteIn(cv, c, f, RectF(80f, top + 40f, W - 80f, h - 170f), f.interBold, 0xFFFFFFFF.toInt(), lighten(seed, 0.7f), 78f, v = 0.3f, spacing = 1.14f)
        cv.drawRect(80f, h - 118f, 130f, h - 113f, Paint().apply { color = lighten(seed, 0.6f) })
        line(cv, listOfNotNull(c.author, series).joinToString("  \u00B7  "), 80f, h - 70f, f.inter, 28f, 0xBFFFFFFF.toInt(), W - 160f)
    }

    /** 7. The book at the top beside a ribbon bookmark showing how far in; the passage below. */
    private fun bookmark(cv: Canvas, c: Content, cover: Bitmap?, seed: Int, f: Fonts, h: Int, name: String, series: String?, where: String?) {
        cv.drawColor(0xFFFBF8F2.toInt())
        val deep = deepOf(seed, 90f)
        bookCover(cv, cover, RectF(90f, 90f, 240f, 315f), seed, f, c.title)
        val tw = W - 280 - 240
        val nh = nameBlock(cv, name, 280f, 112f, tw, f.loraBold, 44f, 0xFF1F1B17.toInt())
        line(cv, c.author, 280f, 112f + nh + 40f, f.inter, 30f, 0xFF6B6359.toInt(), tw.toFloat())
        line(cv, series?.uppercase(), 280f, 112f + nh + 86f, f.interBold, 20f, deep, tw.toFloat(), spacing = 0.14f)
        val rx = W - 200f
        cv.drawPath(Path().apply { moveTo(rx, 0f); lineTo(rx + 90f, 0f); lineTo(rx + 90f, 330f); lineTo(rx + 45f, 290f); lineTo(rx, 330f); close() }, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = deep; setShadowLayer(8f, 0f, 4f, 0x44000000) })
        pctOf(c)?.let { line(cv, it, rx + 45f, 170f, f.interBold, 30f, 0xFFFFFFFF.toInt(), 86f, Paint.Align.CENTER) }
        cv.drawRect(90f, 380f, W - 90f, 382f, Paint().apply { color = 0xFFE6DFD2.toInt() })
        quoteIn(cv, c, f, RectF(90f, 420f, W - 90f, h - 120f), f.lora, 0xFF1F1B17.toInt(), deep, 84f, v = 0.38f)
        line(cv, where?.uppercase(), 90f, h - 70f, f.interBold, 20f, 0xFF8A8176.toInt(), W - 180f, spacing = 0.16f)
    }

    /** 8. Near black: the passage alone and large, the book small at the foot. */
    private fun nightMinimal(cv: Canvas, c: Content, cover: Bitmap?, seed: Int, f: Fonts, h: Int, name: String) {
        cv.drawColor(0xFF0D0D0F.toInt())
        quoteIn(cv, c, f, RectF(110f, 130f, W - 110f, h - 420f), f.lora, 0xFFF2F0EB.toInt(), lighten(seed, 0.6f), 92f, Layout.Alignment.ALIGN_CENTER, v = 0.45f)
        cv.drawRect(W / 2f - 120f, h - 380f, W / 2f + 120f, h - 378f, Paint().apply { color = 0x2EFFFFFF })
        bookCover(cv, cover, RectF(W / 2f - 70f, h - 330f, W / 2f + 70f, h - 120f), seed, f, c.title)
        line(cv, name.uppercase(), W / 2f, h - 80f, f.interBold, 24f, 0xFFF2F0EB.toInt(), W - 160f, Paint.Align.CENTER, 0.2f)
        line(cv, c.author, W / 2f, h - 40f, f.inter, 26f, 0x8CFFFFFF.toInt(), W - 160f, Paint.Align.CENTER)
    }

    /** 9. Two of the cover's colours in a diagonal, bold type, the book leaning at the bottom. */
    private fun duotone(cv: Canvas, c: Content, cover: Bitmap?, seed: Int, f: Fonts, h: Int, name: String, series: String?, where: String?) {
        val from = deepOf(seed, 60f)
        // The second colour: the cover's, its channels turned round (a related, different hue).
        val to = deepOf(lighten(argb(255, b(seed), r(seed), g(seed)), 0.15f), 80f)
        cv.drawRect(0f, 0f, W.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, W.toFloat(), h.toFloat(), from, to, Shader.TileMode.CLAMP) })
        val stripe = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14FFFFFF }
        var i = -h.toFloat()
        while (i < W) { cv.drawPath(Path().apply { moveTo(i, 0f); lineTo(i + h, h.toFloat()); lineTo(i + h + 30f, h.toFloat()); lineTo(i + 30f, 0f); close() }, stripe); i += 70f }
        quoteIn(cv, c, f, RectF(90f, 120f, W - 90f, h - 480f), f.interBold, 0xFFFFFFFF.toInt(), 0xCCFFFFFF.toInt(), 80f, v = 0.4f, spacing = 1.14f)
        cv.save(); cv.rotate(-3f, 200f, h - 255f)
        bookCover(cv, cover, RectF(90f, h - 420f, 310f, h - 90f), seed, f, c.title)
        cv.restore()
        val tw = W - 350 - 80
        val nh = nameBlock(cv, name, 350f, h - 290f, tw, f.interBold, 42f, 0xFFFFFFFF.toInt())
        line(cv, c.author, 350f, h - 290f + nh + 40f, f.inter, 30f, 0xCCFFFFFF.toInt(), tw.toFloat())
        line(cv, series?.uppercase(), 350f, h - 290f + nh + 86f, f.interBold, 20f, 0x99FFFFFF.toInt(), tw.toFloat(), spacing = 0.14f)
        line(cv, where?.uppercase(), 350f, h - 290f + nh + 124f, f.interBold, 20f, 0x99FFFFFF.toInt(), tw.toFloat(), spacing = 0.14f)
    }

    /** 10. A postcard: the cover as its stamp, the passage handwritten on its lines. */
    private fun postcard(cv: Canvas, c: Content, cover: Bitmap?, seed: Int, f: Fonts, h: Int, name: String, series: String?) {
        cv.drawColor(0xFFEFE8D8.toInt())
        cv.drawRoundRect(RectF(50f, 50f, W - 50f, h - 50f), 10f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFDF7.toInt(); setShadowLayer(14f, 0f, 4f, 0x22000000) })
        val deep = deepOf(seed, 90f)
        val sw = 200f; val sh = 270f; val sx = W - 110f - sw; val sy = 100f
        val stamp = RectF(sx - 14f, sy - 14f, sx + sw + 14f, sy + sh + 14f)
        cv.drawRect(stamp, Paint().apply { color = 0xFFFFFFFF.toInt(); setShadowLayer(10f, 0f, 2f, 0x2E000000) })
        // The stamp's perforated edge.
        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFDF7.toInt() }
        var t = 0f
        while (t < stamp.width()) { cv.drawCircle(stamp.left + t + 9f, stamp.top, 6f, hole); cv.drawCircle(stamp.left + t + 9f, stamp.bottom, 6f, hole); t += 18f }
        t = 0f
        while (t < stamp.height()) { cv.drawCircle(stamp.left, stamp.top + t + 9f, 6f, hole); cv.drawCircle(stamp.right, stamp.top + t + 9f, 6f, hole); t += 18f }
        coverFill(cv, cover, RectF(sx, sy, sx + sw, sy + sh), seed)
        // A postmark with how far in.
        pctOf(c)?.let { p ->
            cv.drawCircle(sx - 20f, sy + sh - 10f, 70f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = alpha(deep, 120) })
            line(cv, "$p IN", sx - 20f, sy + sh - 2f, f.interBold, 16f, alpha(deep, 160), 120f, Paint.Align.CENTER, 0.1f)
        }
        line(cv, "From", 110f, 170f, f.caveat, 52f, deep, 300f)
        val tw = (sx - 160f).toInt()
        val nh = nameBlock(cv, name, 110f, 196f, tw, f.loraBold, 40f, 0xFF1F1B17.toInt())
        line(cv, c.author, 110f, 196f + nh + 36f, f.inter, 28f, 0xFF6B6359.toInt(), tw.toFloat())
        val rule = Paint().apply { color = 0xFFE4DCCB.toInt(); strokeWidth = 2f }
        var y = 470f
        while (y < h - 150f) { cv.drawLine(110f, y, W - 110f, y, rule); y += 86f }
        quoteIn(cv, c, f, RectF(110f, 420f, W - 110f, h - 170f), f.caveat, 0xFF2B3A55.toInt(), deep, 84f, v = 0.2f, spacing = 1.02f, quoted = false)
        line(cv, series?.uppercase(), 110f, h - 100f, f.interBold, 18f, 0xFFA39A8B.toInt(), W - 220f, spacing = 0.14f)
    }

    /** 11. The passage over a shelf, the book standing on it among spines in its colours. */
    private fun shelf(cv: Canvas, c: Content, cover: Bitmap?, seed: Int, f: Fonts, h: Int, name: String, series: String?) {
        cv.drawColor(lighten(seed, 0.9f))
        val deep = deepOf(seed, 80f)
        quoteIn(cv, c, f, RectF(100f, 110f, W - 100f, h - 560f), f.lora, 0xFF1F1B17.toInt(), deep, 84f, v = 0.45f)
        val shelfY = h - 180f
        cv.drawRect(60f, shelfY, W - 60f, shelfY + 26f, Paint().apply { color = shade(deep, 0.9f) })
        cv.drawRect(60f, shelfY + 26f, W - 60f, shelfY + 44f, Paint().apply { color = 0x1F000000 })
        var sx = 330f
        for ((sh, sw, col) in listOf(Triple(420f, 58f, shade(seed, 0.6f)), Triple(480f, 44f, lighten(seed, 0.35f)), Triple(530f, 64f, shade(seed, 0.85f)))) {
            cv.drawRect(sx, shelfY - sh * 0.62f, sx + sw, shelfY, Paint().apply { color = col })
            cv.drawRect(sx + 6f, shelfY - sh * 0.62f + 30f, sx + sw - 6f, shelfY - sh * 0.62f + 34f, Paint().apply { color = 0x40FFFFFF })
            sx += sw + 6f
        }
        bookCover(cv, cover, RectF(sx + 12f, shelfY - 300f, sx + 212f, shelfY), seed, f, c.title)
        cv.drawRect(sx + 230f, shelfY - 250f, sx + 282f, shelfY, Paint().apply { color = shade(seed, 0.75f) })
        line(cv, name, 100f, shelfY + 100f, f.loraBold, 36f, 0xFF1F1B17.toInt(), W - 200f)
        line(cv, listOfNotNull(c.author, series).joinToString("  \u00B7  "), 100f, shelfY + 142f, f.inter, 26f, 0xFF5A5248.toInt(), W - 200f)
    }

    /** 12. White, a giant quote mark in the book's colour, bold type; the cover small in the corner. */
    private fun bigMark(cv: Canvas, c: Content, cover: Bitmap?, seed: Int, f: Fonts, h: Int, name: String, series: String?) {
        cv.drawColor(0xFFFFFFFF.toInt())
        val deep = deepOf(seed, 110f)
        if (!c.standalone) line(cv, "\u201C", 40f, 430f, f.loraBold, 520f, alpha(deep, 40), 600f)
        cv.save(); cv.rotate(4f, W - 155f, 187f)
        bookCover(cv, cover, RectF(W - 220f, 90f, W - 90f, 285f), seed, f, c.title)
        cv.restore()
        quoteIn(cv, c, f, RectF(100f, 300f, W - 100f, h - 200f), f.interBold, 0xFF141414.toInt(), deep, 76f, v = 0.35f, spacing = 1.16f, quoted = false)
        cv.drawRect(100f, h - 170f, W - 100f, h - 166f, Paint().apply { color = deep })
        line(cv, name, 100f, h - 110f, f.interBold, 34f, 0xFF141414.toInt(), W - 200f)
        line(cv, listOfNotNull(c.author, series).joinToString("  \u00B7  "), 100f, h - 66f, f.inter, 26f, 0xFF6A6A6A.toInt(), W - 200f)
    }

    /** 13. The passage framed like a print on the wall, the book set on the frame's edge. */
    private fun framed(cv: Canvas, c: Content, cover: Bitmap?, seed: Int, f: Fonts, h: Int, name: String, series: String?) {
        val deep = deepOf(seed, 70f)
        cv.drawColor(deep)
        val m = 60f
        val fh = h - 300f
        cv.drawRect(m, m, W - m, m + fh, Paint().apply { color = 0xFFF8F4EC.toInt(); setShadowLayer(20f, 0f, 8f, 0x55000000) })
        cv.drawRect(m + 22f, m + 22f, W - m - 22f, m + fh - 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = alpha(deep, 90) })
        quoteIn(cv, c, f, RectF(m + 90f, m + 90f, W - m - 90f, m + fh - 190f), f.loraItalic, 0xFF1F1B17.toInt(), deep, 80f, Layout.Alignment.ALIGN_CENTER, v = 0.45f)
        bookCover(cv, cover, RectF(W / 2f - 85f, m + fh - 200f, W / 2f + 85f, m + fh + 55f), seed, f, c.title)
        line(cv, name, W / 2f, h - 150f + 46f, f.loraBold, 38f, 0xFFFFFFFF.toInt(), W - 160f, Paint.Align.CENTER)
        line(cv, c.author, W / 2f, h - 100f + 46f, f.inter, 28f, 0xCCFFFFFF.toInt(), W - 160f, Paint.Align.CENTER)
    }

    /** 14. A ticket to the book: the cover on its stub; the passage above. */
    private fun ticket(cv: Canvas, c: Content, cover: Bitmap?, seed: Int, f: Fonts, h: Int, name: String, series: String?, where: String?) {
        val deep = deepOf(seed, 40f)
        cv.drawColor(deep)
        quoteIn(cv, c, f, RectF(90f, 110f, W - 90f, h - 500f), f.lora, 0xFFFFFFFF.toInt(), lighten(seed, 0.7f), 84f, v = 0.45f)
        val tx = 70f; val ty = h - 430f; val tw = W - 140f; val th = 340f
        cv.drawRoundRect(RectF(tx, ty, tx + tw, ty + th), 22f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFBF6EA.toInt() })
        val notch = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = deep }
        cv.drawCircle(tx + 300f, ty, 28f, notch); cv.drawCircle(tx + 300f, ty + th, 28f, notch)
        cv.drawLine(tx + 300f, ty + 36f, tx + 300f, ty + th - 36f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFCFC6B4.toInt(); strokeWidth = 3f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
        })
        coverFill(cv, cover, RectF(tx + 40f, ty + 40f, tx + 260f, ty + th - 40f), seed)
        val x = tx + 340f
        val w = tw - 380f
        line(cv, "ADMIT ONE READER", x, ty + 70f, f.interBold, 20f, deepOf(seed, 90f), w, spacing = 0.2f)
        line(cv, name, x, ty + 130f, f.loraBold, 40f, 0xFF1F1B17.toInt(), w)
        line(cv, c.author, x, ty + 178f, f.inter, 28f, 0xFF6B6359.toInt(), w)
        line(cv, series?.uppercase(), x, ty + 230f, f.interBold, 18f, 0xFF8A8176.toInt(), w, spacing = 0.14f)
        line(cv, where?.uppercase(), x, ty + 280f, f.interBold, 18f, 0xFF8A8176.toInt(), w, spacing = 0.14f)
    }

    /** 15. "Now reading", like a music player: the cover glowing, how far in as a progress bar, then the passage. */
    private fun nowReading(cv: Canvas, c: Content, cover: Bitmap?, seed: Int, f: Fonts, h: Int, name: String) {
        cv.drawColor(0xFF0B0B10.toInt())
        val k = if (h < 1200) 0.78f else 1f
        if (cover != null) drawCover(cv, soften(cover), RectF(W / 2f - 380f, 0f, W / 2f + 380f, 760f * k), paintAlpha = 150)
        else cv.drawCircle(W / 2f, 360f * k, 360f * k, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = RadialGradient(W / 2f, 360f * k, 360f * k, alpha(seed, 160), 0x00000000, Shader.TileMode.CLAMP) })
        cv.drawRect(0f, 0f, W.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, 820f * k, intArrayOf(0x330B0B10, 0xD90B0B10.toInt(), 0xFF0B0B10.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        })
        line(cv, "NOW READING", W / 2f, 90f * k, f.interBold, 22f, 0xB3FFFFFF.toInt(), 400f, Paint.Align.CENTER, 0.3f)
        bookCover(cv, cover, RectF(W / 2f - 150f * k, 130f * k, W / 2f + 150f * k, 130f * k + 450f * k), seed, f, c.title)
        val ny = 130f * k + 450f * k + 70f
        line(cv, name, W / 2f, ny, f.interBold, 42f, 0xFFFFFFFF.toInt(), W - 160f, Paint.Align.CENTER)
        line(cv, c.author, W / 2f, ny + 48f, f.inter, 28f, 0xB3FFFFFF.toInt(), W - 160f, Paint.Align.CENTER)
        val bx = 140f; val bw = W - 280f; val by = ny + 100f
        val pct = c.percent?.toFloat()?.coerceIn(0f, 1f) ?: 0f
        cv.drawRoundRect(RectF(bx, by, bx + bw, by + 8f), 4f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2EFFFFFF })
        cv.drawRoundRect(RectF(bx, by, bx + bw * pct, by + 8f), 4f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        cv.drawCircle(bx + bw * pct, by + 4f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        line(cv, c.where?.takeIf { !it.contains('%') }?.uppercase() ?: c.page?.let { "PAGE $it" }, bx, by + 50f, f.interBold, 18f, 0x99FFFFFF.toInt(), bw / 2f, spacing = 0.14f)
        line(cv, pctOf(c), bx + bw, by + 50f, f.interBold, 18f, 0x99FFFFFF.toInt(), bw / 2f, Paint.Align.RIGHT, 0.14f)
        quoteIn(cv, c, f, RectF(120f, by + 100f, W - 120f, h - 60f), f.loraItalic, 0xFFFFFFFF.toInt(), lighten(seed, 0.7f), 56f, Layout.Alignment.ALIGN_CENTER, v = 0.35f)
    }

    // ---- System window: a LitRPG game's notification ----

    private fun systemWindow(c: Content, seed: Int, f: Fonts, showMark: Boolean, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        // The game's blue, nudged a little toward the cover's colour.
        val cyan = 0xFF5CE1FF.toInt()
        val accent = argb(255, (r(cyan) * 0.8f + r(lighten(seed, 0.5f)) * 0.2f).toInt(), (g(cyan) * 0.8f + g(lighten(seed, 0.5f)) * 0.2f).toInt(), (b(cyan) * 0.8f + b(lighten(seed, 0.5f)) * 0.2f).toInt())
        val gold = 0xFFFFD76A.toInt()
        val textC = 0xFFE6F6FF.toInt()
        cv.drawRect(0f, 0f, W.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0xFF060A14.toInt(), 0xFF0C1630.toInt(), 0xFF070B16.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        })
        // A faint grid, as on a game's screen.
        val grid = Paint().apply { color = alpha(accent, 14); strokeWidth = 1.5f }
        var gx = 0f
        while (gx < W) { cv.drawLine(gx, 0f, gx, h.toFloat(), grid); gx += 54f }
        var gy = 0f
        while (gy < h) { cv.drawLine(0f, gy, W.toFloat(), gy, grid); gy += 54f }

        val left = 120f
        val panelL = 70f
        val panelR = W - 70f
        val inner = (panelR - panelL - 2 * 56f).toInt()
        val head = if (c.standalone) "PLAYER NOTE" else "SYSTEM NOTIFICATION"
        // The note as the player's, the passage as large as fits.
        val np = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.mono; textSize = if (h > 1500) 38f else 32f; color = gold }
        val nl = c.note?.let { layout("› $it", np, inner, 1.25f, maxLines = if (h > 1500) 5 else 3) }
        val qp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = if (c.standalone) f.caveat else f.mono; color = textC }
        val footRoom = 250f
        val maxPanel = h - 140f - footRoom
        val ql = fit(c.text.trim(), qp, inner, (maxPanel - 150f - (nl?.let { it.height + 70f } ?: 0f)).toInt().coerceAtLeast(160), if (h > 1500) 58f else if (h < 1200) 42f else 48f, 24f, 1.35f)
        val panelH = 150f + ql.height + (nl?.let { it.height + 70f } ?: 0f) + 50f
        val top = maxOf(110f, (h - footRoom - panelH) * 0.5f)
        val panel = RectF(panelL, top, panelR, top + panelH)
        // The panel: glass, a glowing edge, brackets at its corners.
        cv.drawRoundRect(panel, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = alpha(0xFF0A1A33.toInt(), 215) })
        cv.drawRoundRect(panel, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = accent; setShadowLayer(22f, 0f, 0f, alpha(accent, 200)) })
        val br = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = accent; strokeCap = Paint.Cap.ROUND }
        val k = 40f
        for ((x, y, dx, dy) in listOf(
            listOf(panel.left - 12f, panel.top - 12f, 1f, 1f), listOf(panel.right + 12f, panel.top - 12f, -1f, 1f),
            listOf(panel.left - 12f, panel.bottom + 12f, 1f, -1f), listOf(panel.right + 12f, panel.bottom + 12f, -1f, -1f),
        )) {
            cv.drawLine(x, y, x + k * dx, y, br)
            cv.drawLine(x, y, x, y + k * dy, br)
        }
        // The heading, between two diamonds, and a rule under it.
        val hp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.interBold; textSize = 28f; color = accent; letterSpacing = 0.3f }
        val ht = "\u25C6  $head  \u25C6"
        cv.drawText(ht, W / 2f - hp.measureText(ht) / 2f, panel.top + 74f, hp)
        cv.drawLine(panel.left + 56f, panel.top + 108f, panel.right - 56f, panel.top + 108f, Paint().apply { color = alpha(accent, 110); strokeWidth = 2f })
        var y = panel.top + 140f
        text(cv, ql, panel.left + 56f, y)
        y += ql.height
        nl?.let {
            y += 36f
            cv.drawLine(panel.left + 56f, y, panel.left + 156f, y, Paint().apply { color = alpha(gold, 140); strokeWidth = 2f })
            y += 34f
            text(cv, it, panel.left + 56f, y)
        }

        // How far in, as an experience bar; where, beside it.
        val barTop = panel.bottom + 70f
        val lp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.interBold; textSize = 24f; color = alpha(textC, 210); letterSpacing = 0.2f }
        val pct = c.percent?.toFloat()?.coerceIn(0f, 1f)
        val label = whereLine(c)?.substringBefore(" · ")?.takeIf { !it.contains('%') }?.uppercase() ?: c.page?.let { "PAGE $it" } ?: "PROGRESS"
        cv.drawText(TextUtils.ellipsize(label, lp, (panelR - panelL) * 0.6f, TextUtils.TruncateAt.END).toString(), panelL, barTop, lp)
        if (pct != null) {
            val pt = "EXP ${Math.round(pct * 100)}%"
            cv.drawText(pt, panelR - lp.measureText(pt), barTop, lp)
            val bar = RectF(panelL, barTop + 22f, panelR, barTop + 44f)
            cv.drawRoundRect(bar, 11f, 11f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = alpha(accent, 40) })
            val fill = RectF(bar.left, bar.top, bar.left + bar.width() * pct, bar.bottom)
            if (fill.width() > 4f) cv.drawRoundRect(fill, 11f, 11f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(fill.left, 0f, fill.right, 0f, alpha(accent, 200), accent, Shader.TileMode.CLAMP); setShadowLayer(14f, 0f, 0f, alpha(accent, 180))
            })
        }
        // The book, at the foot.
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.interBold; textSize = 25f; color = alpha(accent, 230); letterSpacing = 0.16f }
        val byLine = c.author?.let { " \u00B7 $it" } ?: ""
        val title = fitLabel(tp, W - 2 * left, (c.title + byLine).uppercase(), (shortTitle(c.title) + byLine).uppercase(), shortTitle(c.title).uppercase())
        cv.drawText(title, W / 2f - tp.measureText(title) / 2f, h - 100f, tp)
        if (showMark) brand(cv, f, W - left, h - 50f, alpha(accent, 170), alpha(accent, 255), rightAligned = true)
        return bmp
    }

    // ---- shared bits for the newer looks ----

    private fun lum(c: Int) = 0.299f * r(c) + 0.587f * g(c) + 0.114f * b(c)

    /** The cover's colour dark enough to be ink on a light page. */
    private fun ink(seed: Int): Int {
        var c = seed
        repeat(14) { if (lum(c) > 90f) c = shade(c, 0.85f) }
        return c
    }

    /** Where a block of [blockH] starts so it sits a little above the middle of [h] (it reads better). */
    private fun top(h: Int, blockH: Float, minTop: Float = 0f) = maxOf(minTop, (h - blockH) * 0.44f)

    private fun text(cv: Canvas, l: StaticLayout, x: Float, y: Float) { cv.save(); cv.translate(x, y); l.draw(cv); cv.restore() }

    private fun shadowed(color: Int, blur: Float = 30f, dy: Float = 12f, a: Int = 0x55) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; setShadowLayer(blur, 0f, dy, alpha(0, a)) }

    // ---- Editorial: a magazine pull-quote ----

    private fun editorial(c: Content, seed: Int, f: Fonts, showMark: Boolean, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val paper = 0xFFF8F4EC.toInt()
        val dark = 0xFF1E1B18.toInt()
        val accent = ink(seed)
        cv.drawColor(paper)
        val left = 110f
        val textW = W - 2 * left.toInt()
        // Kicker: where it's from, in spaced capitals, over a thin rule.
        val kp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.interBold; textSize = 25f; color = accent; letterSpacing = 0.22f }
        cv.drawText(fitLabel(kp, textW.toFloat(), "FROM " + c.title.uppercase(), "FROM " + shortTitle(c.title).uppercase(), shortTitle(c.title).uppercase()), left, 130f, kp)
        cv.drawRect(left, 160f, W - left, 162f, Paint().apply { color = alpha(dark, 60) })
        // The passage, large, under a huge quote mark in the cover's colour.
        val markP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.loraBold; textSize = 300f; color = alpha(accent, 210) }
        val notePad = c.note?.let { 70 } ?: 0
        val np = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.loraItalic; textSize = 34f; color = alpha(dark, 200) }
        val nl = c.note?.let { layout(it, np, textW - 36, 1.3f, maxLines = if (h > 1500) 6 else 4) }
        val bottomRoom = 150f + (nl?.let { it.height + notePad + 50f } ?: 0f)
        val qp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.lora; color = dark }
        val ql = fit(c.text.trim(), qp, textW, (h - 200f - 140f - bottomRoom - 120).toInt(), if (h > 1500) 76f else 66f, 34f, 1.22f)
        val block = 140f + ql.height + 120f + (nl?.let { it.height + notePad.toFloat() } ?: 0f)
        var y = top(h, block, 200f)
        // The mark above the passage, then the passage.
        if (!c.standalone) cv.drawText("“", left - 14f, y + 210f, markP)
        text(cv, ql, left, y + 140f)
        y += 140f + ql.height + 70f
        // The byline: a dash and the author, in italics.
        val bp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.loraItalic; textSize = 36f; color = dark }
        cv.drawText(TextUtils.ellipsize("— " + (c.author ?: c.title), bp, textW.toFloat(), TextUtils.TruncateAt.END).toString(), left, y, bp)
        y += 50f
        // Your note: a reader's aside, set off by a bar in the cover's colour.
        nl?.let {
            y += notePad - 30f
            val lp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.interBold; textSize = 22f; color = accent; letterSpacing = 0.2f }
            cv.drawText("READER’S NOTE", left + 36f, y, lp)
            cv.drawRect(left, y - 22f, left + 6f, y + 18f + it.height, Paint().apply { color = accent })
            text(cv, it, left + 36f, y + 18f)
        }
        // The foot: where it was, and the mark.
        cv.drawRect(left, h - 140f, W - left, h - 138f, Paint().apply { color = alpha(dark, 40) })
        whereLine(c)?.let { w ->
            val wp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.inter; textSize = 24f; color = alpha(dark, 170); letterSpacing = 0.14f }
            cv.drawText(TextUtils.ellipsize(w.uppercase(), wp, textW - 240f, TextUtils.TruncateAt.END).toString(), left, h - 92f, wp)
        }
        if (showMark) brand(cv, f, W - left, h - 92f, alpha(dark, 170), lighten(accent, 0.5f), rightAligned = true)
        return bmp
    }

    // ---- Typewriter: typed on paper, a red-pen note in the margin ----

    private fun typewriter(c: Content, f: Fonts, showMark: Boolean, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val paper = 0xFFF3EEE2.toInt()
        val ink = 0xFF2A2724.toInt()
        val pen = 0xFFB3261E.toInt()
        cv.drawColor(paper)
        // Darker towards the edges, like an old sheet.
        cv.drawRect(0f, 0f, W.toFloat(), h.toFloat(), Paint().apply {
            shader = android.graphics.RadialGradient(W / 2f, h / 2f, maxOf(W, h) * 0.75f, intArrayOf(0x00000000, 0x00000000, 0x22503C1E), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        })
        val left = 120f
        val textW = W - 2 * left.toInt()
        // The header typed across the top: the book, and the page.
        val hp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.mono; textSize = 26f; color = alpha(ink, 200); letterSpacing = 0.04f }
        val pageTag = c.page?.let { "p. $it" } ?: c.percent?.let { "${Math.round(it * 100)}%" } ?: ""
        val tw0 = TextPaint(hp)
        cv.drawText(fitLabel(tw0, textW - hp.measureText(pageTag) - 40f, c.title.uppercase(), shortTitle(c.title).uppercase()), left, 120f, tw0)
        cv.drawText(pageTag, W - left - hp.measureText(pageTag), 120f, hp)
        cv.drawRect(left, 140f, W - left, 142f, Paint().apply { color = alpha(ink, 70) })
        // The passage, typed: each line struck twice a hair apart, like a real ribbon.
        val np = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.caveat; textSize = 54f; color = pen }
        val nl = c.note?.let { layout(it, np, textW - 80, 1.02f, maxLines = if (h > 1500) 5 else 3) }
        val qp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.mono; color = ink }
        val ql = fit(c.text.trim(), qp, textW, (h - 260 - 190 - (nl?.let { it.height + 90 } ?: 0)).toInt(), if (h > 1500) 48f else 42f, 26f, 1.45f)
        val block = ql.height + (nl?.let { it.height + 90f } ?: 0f) + 70f
        var y = top(h, block, 220f)
        val ghost = TextPaint(qp).apply { color = alpha(ink, 70) }
        text(cv, layout(c.text.trim(), ghost, textW, 1.45f, maxLines = ql.lineCount), left + 1.6f, y + 1.2f)
        text(cv, ql, left, y)
        y += ql.height + 30f
        val sig = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.mono; textSize = 28f; color = alpha(ink, 220) }
        cv.drawText(TextUtils.ellipsize("— " + (c.author ?: c.title), sig, textW.toFloat(), TextUtils.TruncateAt.END).toString(), left, y + 30f, sig)
        y += 70f
        // Your note, in red pen, a little askew, with a curl pointing at the passage.
        nl?.let {
            y += 40f
            cv.save(); cv.rotate(-2.5f, left + 40f, y)
            val curl = Path().apply { moveTo(left + 10f, y + 30f); quadTo(left - 30f, y - 30f, left + 30f, y - 70f) }
            cv.drawPath(curl, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = pen; strokeCap = Paint.Cap.ROUND })
            text(cv, it, left + 60f, y)
            cv.restore()
        }
        if (showMark) brand(cv, f, W - left, h - 90f, alpha(ink, 160), 0xFFE9C46A.toInt(), rightAligned = true)
        return bmp
    }

    // ---- Index card: taped to the desk, your note under the passage ----

    private fun indexCard(c: Content, seed: Int, f: Fonts, showMark: Boolean, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        // The desk: the cover's colour, soft and a little lighter in the middle.
        val desk = shade(lighten(seed, 0.15f), 0.82f)
        cv.drawColor(desk)
        cv.drawRect(0f, 0f, W.toFloat(), h.toFloat(), Paint().apply {
            shader = android.graphics.RadialGradient(W / 2f, h * 0.45f, maxOf(W, h) * 0.7f, alpha(lighten(desk, 0.25f), 255), alpha(shade(desk, 0.8f), 255), Shader.TileMode.CLAMP)
        })
        val cw = 900f
        val cx = (W - cw) / 2f
        val pad = 60f
        val inner = (cw - 2 * pad).toInt()
        val inkBlue = 0xFF2B4A8C.toInt()
        val np = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.caveat; textSize = 52f; color = inkBlue }
        val nl = c.note?.let { layout("→ $it", np, inner, 1.02f, maxLines = if (h > 1500) 6 else 4) }
        val qp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.lora; color = 0xFF23211E.toInt() }
        val headH = 150f
        val foot = 110f
        val maxCard = h - 200f
        val ql = fit(c.text.trim(), qp, inner, (maxCard - headH - foot - (nl?.let { it.height + 50 } ?: 0)).toInt(), if (h > 1500) 52f else 46f, 28f, 1.38f)
        val ch = headH + ql.height + (nl?.let { it.height + 50f } ?: 0f) + foot
        val cy = top(h, ch, 100f)
        cv.save(); cv.rotate(-2f, W / 2f, cy + ch / 2)
        val card = RectF(cx, cy, cx + cw, cy + ch)
        cv.drawRect(card, shadowed(0xFFFFFDF6.toInt(), 30f, 14f, 0x50))
        // Blue rules in step with the lines, under a red one for the heading.
        val lineH = ql.height.toFloat() / ql.lineCount.coerceAtLeast(1)
        var ly = cy + headH + lineH - 6f
        val rule = Paint().apply { color = 0xFFC9D8EA.toInt(); strokeWidth = 2f }
        while (ly < card.bottom - 30f) { cv.drawLine(cx, ly, card.right, ly, rule); ly += lineH }
        cv.drawLine(cx, cy + headH - 30f, card.right, cy + headH - 30f, Paint().apply { color = 0xFFE29A9A.toInt(); strokeWidth = 3f })
        val hp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.interBold; textSize = 24f; color = 0xFF6B6359.toInt(); letterSpacing = 0.14f }
        val tag = c.page?.let { "P. $it" } ?: c.percent?.let { "${Math.round(it * 100)}%" } ?: ""
        val ip0 = TextPaint(hp)
        cv.drawText(fitLabel(ip0, inner - hp.measureText(tag) - 30f, c.title.uppercase(), shortTitle(c.title).uppercase()), cx + pad, cy + 80f, ip0)
        cv.drawText(tag, card.right - pad - hp.measureText(tag), cy + 80f, hp)
        text(cv, ql, cx + pad, cy + headH)
        nl?.let { text(cv, it, cx + pad, cy + headH + ql.height + 40f) }
        c.author?.let { a ->
            val ap = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.loraItalic; textSize = 28f; color = 0xFF6B6359.toInt() }
            cv.drawText(TextUtils.ellipsize("— $a", ap, inner.toFloat(), TextUtils.TruncateAt.END).toString(), cx + pad, card.bottom - 46f, ap)
        }
        // A strip of tape holding it down.
        cv.save(); cv.rotate(3f, W / 2f, cy)
        cv.drawRect(W / 2f - 90f, cy - 26f, W / 2f + 90f, cy + 26f, Paint().apply { color = 0x99F1E7C4.toInt() })
        cv.restore()
        cv.restore()
        if (showMark) brand(cv, f, W - 80f, h - 70f, 0xCCFFFFFF.toInt(), lighten(desk, 0.4f), rightAligned = true)
        return bmp
    }

    // ---- Wordprint: the passage's own fingerprint ----

    private fun wordprint(c: Content, seed: Int, f: Fonts, showMark: Boolean, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        // Night paper in the cover's colour; the print glows in a light tint of it.
        var bg = seed
        repeat(16) { if (lum(bg) > 38f) bg = shade(bg, 0.84f) }
        cv.drawColor(bg)
        val glow = lighten(seed, 0.55f)
        // One ray per word, as long as the word. A short passage (under 24 words) has too few for a
        // shape, so it goes by letter instead, each as long as its place in the alphabet.
        val byWord = c.text.trim().split(Regex("\\s+")).map { w -> w.count { it.isLetterOrDigit() } }.filter { it > 0 }
        val words = if (byWord.size >= 24) byWord.take(160)
            else c.text.filter { it.isLetterOrDigit() }.take(120).map { ch -> if (ch.isDigit()) (ch - '0') + 8 else (ch.lowercaseChar() - 'a').coerceIn(0, 25) + 1 }
        // The ring: bigger on a story, smaller on a square.
        val ringR = if (h > 1500) 190f else if (h < 1200) 120f else 150f
        val maxRay = if (h > 1500) 170f else if (h < 1200) 110f else 135f
        val cx = W / 2f
        // The text first, so the print and the passage can sit together in the middle.
        val left = 110f
        val textW = W - 2 * left.toInt()
        val foot = 160f
        val np = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.caveat; textSize = 48f; color = glow }
        val nl = c.note?.let { layout(it, np, textW, 1.04f, maxLines = if (h > 1500) 4 else 2, align = Layout.Alignment.ALIGN_CENTER) }
        val qp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.lora; color = 0xFFFFFFFF.toInt() }
        val printH = 2 * (ringR + maxRay) + 70f
        val minTop = if (h > 1500) 120f else 60f
        val room = (h - minTop - printH - foot - (nl?.let { it.height + 40f } ?: 0f)).toInt()
        val ql = fit(c.text.trim(), qp, textW, room.coerceAtLeast(150), if (h > 1500) 48f else 40f, 24f, 1.3f, Layout.Alignment.ALIGN_CENTER)
        val block = ql.height + (nl?.let { it.height + 40f } ?: 0f)
        val start = minTop + maxOf(0f, (h - foot - minTop - printH - block) * 0.45f)
        val cy = start + maxRay + ringR
        val n = words.size.coerceAtLeast(1)
        val step = 360f / n
        val longest = (words.maxOrNull() ?: 1).coerceAtLeast(1)
        val ray = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
        val thick = (2 * Math.PI * ringR / n * 0.55).toFloat().coerceIn(3f, 16f)
        for ((i, len) in words.withIndex()) {
            val a = Math.toRadians((i * step - 90f).toDouble())
            val l = 10f + maxRay * (len.toFloat() / longest).coerceIn(0f, 1f)
            val x0 = cx + ringR * Math.cos(a).toFloat(); val y0 = cy + ringR * Math.sin(a).toFloat()
            val x1 = cx + (ringR + l) * Math.cos(a).toFloat(); val y1 = cy + (ringR + l) * Math.sin(a).toFloat()
            // Long words brighter, short ones softer: the shape reads like the sentence's rhythm.
            ray.color = alpha(glow, (90 + 165 * len / longest).coerceIn(90, 255))
            ray.strokeWidth = thick
            cv.drawLine(x0, y0, x1, y1, ray)
        }
        // The inner circle, and how far into the book: an arc from the top, a dot where the passage is.
        val inner = RectF(cx - ringR + 26f, cy - ringR + 26f, cx + ringR - 26f, cy + ringR - 26f)
        cv.drawOval(inner, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = alpha(glow, 60) })
        val pct = c.percent?.toFloat()?.coerceIn(0f, 1f)
        val pctLabel: String
        if (pct != null) {
            val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; strokeCap = Paint.Cap.ROUND; color = 0xFFFFFFFF.toInt() }
            val pathArc = Path().apply { addArc(inner, -90f, 360f * pct) }
            cv.drawPath(pathArc, arc)
            val ea = Math.toRadians((360f * pct - 90f).toDouble())
            val rr = inner.width() / 2f
            cv.drawCircle(cx + rr * Math.cos(ea).toFloat(), cy + rr * Math.sin(ea).toFloat(), 11f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
            pctLabel = "${Math.round(pct * 100)}%"
        } else pctLabel = c.page?.let { "p.$it" } ?: if (c.standalone) "✎" else "\u201C"
        val cp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.loraBold; textSize = if (h < 1200) 50f else 62f; color = 0xFFFFFFFF.toInt() }
        cv.drawText(pctLabel, cx - cp.measureText(pctLabel) / 2f, cy + cp.textSize * 0.34f, cp)
        if (pct != null) {
            val ip = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.interBold; textSize = 20f; color = alpha(glow, 200); letterSpacing = 0.24f }
            val t = "INTO THE BOOK"
            // Only where it fits inside the ring.
            if (ip.measureText(t) < inner.width() * 0.78f) cv.drawText(t, cx - ip.measureText(t) / 2f, cy + cp.textSize * 0.34f + 38f, ip)
        }
        // The passage under its print, centred; your note in hand under it; the book at the foot.
        var y = cy + ringR + maxRay + 70f
        text(cv, ql, left, y)
        y += ql.height + 40f
        nl?.let { text(cv, it, left, y) }
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.interBold; textSize = 25f; color = alpha(glow, 230); letterSpacing = 0.16f }
        val byLine = c.author?.let { " \u00B7 $it" } ?: ""
        val title = fitLabel(tp, textW.toFloat(), (c.title + byLine).uppercase(), (shortTitle(c.title) + byLine).uppercase(), shortTitle(c.title).uppercase())
        cv.drawText(title, cx - tp.measureText(title) / 2f, h - 100f, tp)
        if (showMark) brand(cv, f, W - left, h - 50f, alpha(glow, 170), alpha(seed, 255), rightAligned = true)
        return bmp
    }

    /** Saves the card in the app's cache and returns a link other apps can open. */
    fun cacheUri(context: Context, bmp: Bitmap): Uri {
        val dir = File(context.cacheDir, "shares").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
        val f = File(dir, "quote-${System.currentTimeMillis()}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(context, context.packageName + ".pages", f)
    }

    fun share(context: Context, bmp: Bitmap, text: String) {
        val uri = cacheUri(context, bmp)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            clipData = ClipData.newRawUri("Quote", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share quote").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    /** The Threads app's package on Android. */
    private const val THREADS = "com.instagram.barcelona"

    /**
     * Straight into a new Threads post: the picture attached and [text] as its words, ready for you
     * to post. The words are on the clipboard too, as Threads doesn't always take them with a
     * picture. Without the Threads app, Threads opens in the browser with the words filled in
     * (it can't take a picture that way). Returns "app", "web" or null when neither could open.
     */
    fun postToThreads(context: Context, bmp: Bitmap, text: String): String? {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Quote", text))
        }
        val uri = cacheUri(context, bmp)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            setPackage(THREADS)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            clipData = ClipData.newRawUri("Quote", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(send) }.isSuccess) return "app"
        // Threads' own web link for a new post: words only (at most 500 characters on Threads).
        val web = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.threads.com/intent/post?text=" + java.net.URLEncoder.encode(text.take(500), "UTF-8")))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (runCatching { context.startActivity(web) }.isSuccess) "web" else null
    }

    /** Puts the picture on the clipboard (apps that take pasted images can paste it). */
    fun copy(context: Context, bmp: Bitmap) {
        val uri = cacheUri(context, bmp)
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(ClipData.newUri(context.contentResolver, "Quote", uri))
    }

    /** Saves to Pictures/Tracker Sync. Returns false where the phone needs a permission for that (Android 9 and older). */
    fun save(context: Context, bmp: Bitmap): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "quote-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/" + io.github.prithirajn.trackersync.Edition.name)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val cr = context.contentResolver
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        cr.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: return false
        cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return true
    }
}
