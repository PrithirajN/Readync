package io.github.prithirajn.trackersync.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.res.ResourcesCompat
import io.github.prithirajn.trackersync.R

/**
 * The drawing kit shared by the pictures made from your reading (the Wrapped slides, the shelf
 * poster, the moving quote, the widgets): the app's typefaces, colour helpers, covers drawn to
 * fill a box, and text set to fit.
 */
internal object Ink {
    class Faces(
        val inter: Typeface, val interBold: Typeface, val lora: Typeface, val loraBold: Typeface,
        val loraItalic: Typeface, val caveat: Typeface, val pixel: Typeface, val mono: Typeface,
    )

    @Volatile private var faces: Faces? = null

    fun faces(context: Context): Faces = faces ?: run {
        fun f(id: Int, fallback: Typeface) = runCatching { ResourcesCompat.getFont(context, id) }.getOrNull() ?: fallback
        val inter = f(R.font.inter, Typeface.SANS_SERIF)
        val lora = f(R.font.lora, Typeface.SERIF)
        Faces(
            inter = inter, interBold = Typeface.create(inter, Typeface.BOLD),
            lora = lora, loraBold = Typeface.create(lora, Typeface.BOLD),
            loraItalic = f(R.font.book_lora_italic, Typeface.create(lora, Typeface.ITALIC)),
            caveat = f(R.font.caveat, Typeface.SERIF), pixel = f(R.font.book_pixelify, Typeface.MONOSPACE),
            mono = f(R.font.book_plexmono, Typeface.MONOSPACE),
        ).also { faces = it }
    }

    // ---- colour ----

    fun r(c: Int) = c shr 16 and 0xFF
    fun g(c: Int) = c shr 8 and 0xFF
    fun b(c: Int) = c and 0xFF
    fun argb(a: Int, r: Int, g: Int, b: Int) = (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    fun shade(c: Int, f: Float) = argb(255, (r(c) * f).toInt(), (g(c) * f).toInt(), (b(c) * f).toInt())
    fun lighten(c: Int, f: Float) = argb(255, (r(c) + (255 - r(c)) * f).toInt(), (g(c) + (255 - g(c)) * f).toInt(), (b(c) + (255 - b(c)) * f).toInt())
    fun alpha(c: Int, a: Int) = (a.coerceIn(0, 255) shl 24) or (c and 0xFFFFFF)
    fun luminance(c: Int) = (0.299 * r(c) + 0.587 * g(c) + 0.114 * b(c)) / 255.0

    const val SIGNAL = 0xFFFF6A3D.toInt()
    const val PAGE = 0xFF111110.toInt()
    const val SCREEN = 0xFF060606.toInt()
    const val TEXT = 0xFFF4F2EA.toInt()
    const val DIM = 0xFF8B8A82.toInt()

    /** The average colour of a cover's middle (for a spine, or a background), a little richer. */
    fun coverColor(cover: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(cover, 8, 12, true)
        var rr = 0; var gg = 0; var bb = 0; var n = 0
        for (y in 2 until 10) for (x in 1 until 7) {
            val p = small.getPixel(x, y); rr += r(p); gg += g(p); bb += b(p); n++
        }
        if (small !== cover) small.recycle()
        val hsv = FloatArray(3)
        Color.colorToHSV(argb(255, rr / n, gg / n, bb / n), hsv)
        hsv[1] = (hsv[1] * 1.15f).coerceAtMost(1f)
        return Color.HSVToColor(hsv)
    }

    // ---- drawing ----

    /**
     * A soft, smooth blur of [src] for a background: a small copy (made in halving steps, so it
     * averages instead of skipping pixels), box-blurred three times each way (close to a true
     * gaussian, with no blocks), to be drawn large with filtering.
     */
    fun softBlur(src: Bitmap, width: Int = 180, radius: Int = 7): Bitmap {
        var cur = src
        while (cur.width / 2 >= width) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, (cur.height / 2).coerceAtLeast(1), true)
            if (cur !== src) cur.recycle()
            cur = next
        }
        val w = width
        val h = (width.toFloat() * cur.height / cur.width).toInt().coerceAtLeast(2)
        val small = Bitmap.createScaledBitmap(cur, w, h, true)
        if (cur !== src && cur !== small) cur.recycle()
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        fun pass(from: IntArray, to: IntArray, horizontal: Boolean) {
            val len = if (horizontal) w else h
            val lines = if (horizontal) h else w
            for (line in 0 until lines) {
                var sr = 0; var sg = 0; var sb = 0
                fun at(i: Int): Int { val k = i.coerceIn(0, len - 1); return if (horizontal) from[line * w + k] else from[k * w + line] }
                for (i in -radius..radius) { val p = at(i); sr += r(p); sg += g(p); sb += b(p) }
                val n = radius * 2 + 1
                for (i in 0 until len) {
                    val idx = if (horizontal) line * w + i else i * w + line
                    to[idx] = argb(255, sr / n, sg / n, sb / n)
                    val add = at(i + radius + 1); val drop = at(i - radius)
                    sr += r(add) - r(drop); sg += g(add) - g(drop); sb += b(add) - b(drop)
                }
            }
        }
        repeat(3) { pass(px, tmp, true); pass(tmp, px, false) }
        small.setPixels(px, 0, w, 0, 0, w, h)
        return small
    }

    /** [cover] filling [dst] (cropped to fit), with rounded corners when [radius] is given. */
    fun drawCover(cv: Canvas, cover: Bitmap, dst: RectF, radius: Float = 0f, alpha: Int = 255) {
        val sh = BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = maxOf(dst.width() / cover.width, dst.height() / cover.height)
        sh.setLocalMatrix(Matrix().apply {
            setScale(scale, scale)
            postTranslate(dst.left + (dst.width() - cover.width * scale) / 2, dst.top + (dst.height() - cover.height * scale) / 2)
        })
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = sh; this.alpha = alpha }
        if (radius > 0) cv.drawRoundRect(dst, radius, radius, p) else cv.drawRect(dst, p)
    }

    /** A cover as a real book: a soft shadow under it, its spine's crease, a sheen. */
    fun drawBook(cv: Canvas, cover: Bitmap?, dst: RectF, fallback: Int, f: Faces, title: String) {
        cv.drawRoundRect(RectF(dst.left + 6f, dst.top + 16f, dst.right + 6f, dst.bottom + 16f), 10f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x80000000.toInt(); maskFilter = android.graphics.BlurMaskFilter(24f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        })
        if (cover != null) drawCover(cv, cover, dst, 8f)
        else {
            cv.drawRoundRect(dst, 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = shade(fallback, 0.7f) })
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.loraBold; textSize = dst.width() * 0.12f; color = lighten(fallback, 0.85f) }
            text(cv, layout(QuoteCard.shortTitle(title), tp, (dst.width() * 0.8f).toInt(), 1.1f, 6, Layout.Alignment.ALIGN_CENTER), dst.left + dst.width() * 0.1f, dst.top + dst.height() * 0.15f)
        }
        cv.drawRect(dst.left, dst.top, dst.left + dst.width() * 0.07f, dst.bottom, Paint().apply {
            shader = LinearGradient(dst.left, 0f, dst.left + dst.width() * 0.07f, 0f, intArrayOf(0x55FFFFFF, 0x22000000, 0x00000000), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        })
    }

    /** The app's wallpaper on a picture: two soft blooms of [color] over the dark page, fading down. */
    fun wallpaper(cv: Canvas, w: Int, h: Int, color: Int, bloomTop: Float = 0.2f) {
        cv.drawColor(PAGE)
        fun bloom(x: Float, y: Float, r: Float, c: Int, a: Int) {
            cv.drawCircle(x, y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(x, y, r, intArrayOf(alpha(c, a), alpha(c, a * 2 / 5), alpha(c, 0)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            })
        }
        val second = Color.HSVToColor(FloatArray(3).also { Color.colorToHSV(color, it); it[0] = (it[0] + 40f) % 360f })
        bloom(w * 0.22f, h * bloomTop, w * 1.0f, color, 150)
        bloom(w * 0.9f, h * (bloomTop + 0.25f), w * 0.85f, second, 110)
        // Scanlines, faint, as on the device's screen.
        val line = Paint().apply { this.color = 0x08FFFFFF }
        var y = 0f
        while (y < h) { cv.drawRect(0f, y, w.toFloat(), y + 1f, line); y += 4f }
    }

    fun layout(text: CharSequence, paint: TextPaint, width: Int, spacing: Float = 1.2f, maxLines: Int = Int.MAX_VALUE, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(align).setLineSpacing(0f, spacing).setIncludePad(false)
            .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build()

    /** Text sized down from [start] until it fits [maxH] (never below [min], then cut with an ellipsis). */
    fun fit(text: CharSequence, paint: TextPaint, width: Int, maxH: Int, start: Float, min: Float, spacing: Float = 1.25f, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout {
        var size = start
        while (size > min) {
            paint.textSize = size
            val l = layout(text, paint, width, spacing, align = align)
            if (l.height <= maxH) return l
            size -= 2f
        }
        paint.textSize = min
        val lineH = paint.fontSpacing * spacing
        return layout(text, paint, width, spacing, (maxH / lineH).toInt().coerceAtLeast(2), align)
    }

    fun text(cv: Canvas, l: StaticLayout, x: Float, y: Float) { cv.save(); cv.translate(x, y); l.draw(cv); cv.restore() }

    /** One line, cut with an ellipsis to [maxW]. */
    fun line(cv: Canvas, t: String?, x: Float, y: Float, face: Typeface, size: Float, color: Int, maxW: Float, align: Paint.Align = Paint.Align.LEFT, spacing: Float = 0f) {
        if (t.isNullOrBlank()) return
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = size; this.color = color; textAlign = align; letterSpacing = spacing }
        cv.drawText(TextUtils.ellipsize(t, p, maxW, TextUtils.TruncateAt.END).toString(), x, y, p)
    }

    /** A big number, heavy and tight like a poster's, shrunk if need be to fit [maxW] (never cut off). */
    fun bigNumber(cv: Canvas, t: String, x: Float, baseline: Float, size: Float, maxW: Float, color: Int, face: Typeface) {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = size; this.color = color; letterSpacing = -0.045f }
        val w = p.measureText(t)
        if (w > maxW) p.textSize = size * maxW / w
        cv.drawText(t, x, baseline, p)
    }

    /** The app's name, small, as a mark at the foot of a picture. */
    fun mark(cv: Canvas, f: Faces, centerX: Float, y: Float) {
        val t = "MADE WITH " + io.github.prithirajn.trackersync.Edition.name.uppercase()
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.mono; textSize = 24f; color = DIM; letterSpacing = 0.12f }
        val w = p.measureText(t)
        val left = centerX - (w + 26f) / 2
        cv.drawCircle(left + 7f, y - 8f, 7f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SIGNAL })
        cv.drawText(t, left + 26f, y, p)
    }
}
