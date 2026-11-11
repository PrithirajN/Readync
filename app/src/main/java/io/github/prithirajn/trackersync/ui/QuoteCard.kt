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
    fun render(context: Context?, content0: Content, look: Look, cover: Bitmap?, coverSeed: Int?, mark: Boolean = true, size: Size = Size.POST): Bitmap {
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
        val head = "from " + c.title + (c.author?.let { " · $it" } ?: "")
        cv.drawText(TextUtils.ellipsize(head, hp, (W - left - 96 - 130).toFloat(), TextUtils.TruncateAt.END).toString(), left, 150f, hp)
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
        val spineText = TextUtils.ellipsize(c.title.uppercase(), sp, H - 360f, TextUtils.TruncateAt.END).toString()
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
        cv.drawText(TextUtils.ellipsize("FROM " + c.title.uppercase(), kp, textW.toFloat(), TextUtils.TruncateAt.END).toString(), left, 130f, kp)
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
        cv.drawText(TextUtils.ellipsize(c.title.uppercase(), hp, textW - hp.measureText(pageTag) - 40f, TextUtils.TruncateAt.END).toString(), left, 120f, hp)
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
        cv.drawText(TextUtils.ellipsize(c.title.uppercase(), hp, inner - hp.measureText(tag) - 30f, TextUtils.TruncateAt.END).toString(), cx + pad, cy + 80f, hp)
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
        val title = TextUtils.ellipsize((c.title + (c.author?.let { " \u00B7 $it" } ?: "")).uppercase(), tp, textW.toFloat(), TextUtils.TruncateAt.END).toString()
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
