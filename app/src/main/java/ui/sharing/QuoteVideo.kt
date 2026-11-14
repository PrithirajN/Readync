package io.github.prithirajn.readync.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import io.github.prithirajn.readync.R
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A passage as a short video for Reels, Stories or Threads. How it moves (typed out, word by word,
 * line by line, swept with a highlighter, a few words at a time, or a slow fade), where it sits (on
 * a page under its cover, straight on the scene, or as a subtitle over the cover), what's behind it,
 * the type, the pace and what else shows are all choices; the same scene draws the live preview in
 * the studio and every frame of the video. Encoded on the phone as an MP4 (H.264), 30 frames a second.
 */
object QuoteVideo {
    const val FPS = 30
    const val W = 1080

    enum class Motion(val label: String, val line: String) {
        TYPE("Typewriter", "Typed out letter by letter, with a cursor"),
        WORDS("Word by word", "Each word floats up in turn"),
        LINES("Line by line", "Lines rise in one after another"),
        HIGHLIGHT("Highlighter", "A marker sweeps across as if you're reading"),
        KINETIC("Kinetic", "A few big words at a time, then the whole passage"),
        FADE("Slow fade", "The passage drifts up, quietly"),
    }

    enum class Layout(val label: String) { CARD("Page"), OPEN("Scene"), SUBTITLE("Subtitle") }
    enum class Backdrop(val label: String) { BLUR("Blurred cover"), COVER("The cover"), COLOUR("Cover colour"), NIGHT("Night"), PAPER("Paper") }
    enum class Face(val label: String, val res: Int, val scale: Float) {
        LORA("Lora", R.font.lora, 1f), PLEX("Plex Serif", R.font.book_plex, 0.95f), CRIMSON("Crimson", R.font.book_crimson, 1.08f),
        INSTRUMENT("Instrument", R.font.book_instrument, 1.12f), INTER("Inter", R.font.inter, 0.92f),
        PIXEL("Pixel", R.font.book_pixelify, 0.95f), HAND("Hand", R.font.caveat, 1.25f),
    }
    enum class Pace(val label: String, val k: Float) { SLOW("Slow", 0.7f), NORMAL("Normal", 1f), FAST("Fast", 1.5f) }
    enum class Shape(val label: String, val h: Int) { STORY("Story", 1920), POST("Post", 1350), SQUARE("Square", 1080) }

    data class Spec(
        val motion: Motion = Motion.TYPE,
        val layout: Layout = Layout.CARD,
        val backdrop: Backdrop = Backdrop.BLUR,
        val face: Face = Face.LORA,
        val pace: Pace = Pace.NORMAL,
        val shape: Shape = Shape.STORY,
        val cover: Boolean = true,
        val note: Boolean = true,
        val title: Boolean = true,
        val zoom: Boolean = true,
        val mark: Boolean = true,
    )

    // ---- the last choices, kept for next time ----

    private fun prefs(context: Context) = context.getSharedPreferences("video", Context.MODE_PRIVATE)

    fun saved(context: Context): Spec = runCatching {
        val p = prefs(context)
        val d = Spec()
        Spec(
            motion = runCatching { Motion.valueOf(p.getString("motion", d.motion.name)!!) }.getOrDefault(d.motion),
            layout = runCatching { Layout.valueOf(p.getString("layout", d.layout.name)!!) }.getOrDefault(d.layout),
            backdrop = runCatching { Backdrop.valueOf(p.getString("backdrop", d.backdrop.name)!!) }.getOrDefault(d.backdrop),
            face = runCatching { Face.valueOf(p.getString("face", d.face.name)!!) }.getOrDefault(d.face),
            pace = runCatching { Pace.valueOf(p.getString("pace", d.pace.name)!!) }.getOrDefault(d.pace),
            shape = runCatching { Shape.valueOf(p.getString("shape", d.shape.name)!!) }.getOrDefault(d.shape),
            cover = p.getBoolean("cover", true), note = p.getBoolean("note", true), title = p.getBoolean("title", true),
            zoom = p.getBoolean("zoom", true), mark = p.getBoolean("mark", true),
        )
    }.getOrDefault(Spec())

    fun save(context: Context, s: Spec) {
        prefs(context).edit().putString("motion", s.motion.name).putString("layout", s.layout.name).putString("backdrop", s.backdrop.name)
            .putString("face", s.face.name).putString("pace", s.pace.name).putString("shape", s.shape.name)
            .putBoolean("cover", s.cover).putBoolean("note", s.note).putBoolean("title", s.title).putBoolean("zoom", s.zoom).putBoolean("mark", s.mark).apply()
    }

    class Input(val text: String, val note: String?, val title: String, val author: String?, val where: String?, val cover: Bitmap?)

    /** Anything that can be drawn frame by frame as a video: its size, its length, and the frame at any moment. */
    interface Frames {
        val w: Int
        val h: Int
        val duration: Float
        fun draw(cv: Canvas, t: Float)
    }

    // ---- the scene: everything worked out once, then drawn at any moment ----

    class Scene(context: Context, private val x: Input, private val s: Spec) : Frames {
        override val w = W
        override val h = s.shape.h
        private val f = Ink.faces(context)
        private val tint = x.cover?.let { Ink.coverColor(it) } ?: Ink.SIGNAL
        private val face: Typeface = runCatching { ResourcesCompat.getFont(context, s.face.res) }.getOrNull() ?: f.lora
        private val backdrop = if (x.cover == null && (s.backdrop == Backdrop.BLUR || s.backdrop == Backdrop.COVER)) Backdrop.COLOUR else s.backdrop
        private val onPaper = s.layout == Layout.CARD || backdrop == Backdrop.PAPER
        private val ink = if (onPaper) 0xFF221F1C.toInt() else Ink.TEXT
        private val noteInk = if (onPaper) Ink.shade(tint, 0.55f) else Ink.lighten(tint, 0.55f)
        private val markInk = if (onPaper) Ink.alpha(tint, 110) else Ink.alpha(Ink.lighten(tint, 0.2f), 140)
        private val showCover = s.cover && x.cover != null && s.layout != Layout.SUBTITLE || (s.cover && x.cover == null && s.layout == Layout.CARD)
        private val words = if (s.face == Face.HAND) x.text.trim() else io.github.prithirajn.readync.trackers.quoted(x.text.trim())
        private val note = x.note?.takeIf { s.note && it.isNotBlank() }?.trim()

        private val back: Bitmap = background()
        private val coverH = if (h >= 1900) 520f else if (h >= 1300) 380f else 300f
        private val coverTop = if (h >= 1900) 230f else 100f
        private val pad = 60
        private val bookSprite: Bitmap? = if (!showCover) null else {
            val ch = if (s.layout == Layout.OPEN) coverH * 0.7f else coverH
            val cw = ch * 2f / 3f
            Bitmap.createBitmap((cw + pad * 2).toInt(), (ch + pad * 2).toInt(), Bitmap.Config.ARGB_8888).also {
                Ink.drawBook(Canvas(it), x.cover, RectF(pad.toFloat(), pad.toFloat(), pad + cw, pad + ch), tint, f, x.title)
            }
        }
        private val coverCenterY = when (s.layout) {
            Layout.CARD -> coverTop + coverH / 2
            else -> coverTop + coverH * 0.35f
        }
        /** Where the words go, and the page under them for the card. */
        private val card: RectF? = if (s.layout == Layout.CARD) RectF(80f, (if (showCover) coverTop + coverH + 80f else 160f), w - 80f, h - (if (h >= 1900) 260f else 150f)) else null
        private val textBox: RectF = when (s.layout) {
            Layout.CARD -> RectF(card!!.left + 70f, card.top + 80f, card.right - 70f, card.bottom - 170f)
            Layout.OPEN -> RectF(100f, if (showCover) coverTop + coverH * 0.7f + 110f else h * 0.2f, w - 100f, h - (if (h >= 1900) 330f else 200f))
            Layout.SUBTITLE -> RectF(90f, h * 0.56f, w - 90f, h - (if (h >= 1900) 300f else 170f))
        }
        private val cardSprite: Bitmap? = card?.let { c ->
            Bitmap.createBitmap((c.width() + pad * 2).toInt(), (c.height() + pad * 2).toInt(), Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawRoundRect(RectF(pad.toFloat(), pad.toFloat(), pad + c.width(), pad + c.height()), 36f, 36f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (backdrop == Backdrop.PAPER) 0xFFFBF8F2.toInt() else 0xFFF3EFE7.toInt(); setShadowLayer(40f, 0f, 18f, 0x88000000.toInt())
                })
            }
        }
        private val np = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.caveat; textSize = 58f; color = noteInk }
        private val nl: StaticLayout? = note?.let { Ink.layout(it, np, textBox.width().toInt(), 1.05f, 3) }
        private val qp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; color = ink }
        private val start = when (s.layout) { Layout.CARD -> 64f; Layout.OPEN -> 74f; Layout.SUBTITLE -> 58f } * s.face.scale
        private val ql: StaticLayout = Ink.fit(words, qp, textBox.width().toInt(), (textBox.height() - (nl?.height ?: 0) - 40f).toInt().coerceAtLeast(200), start, 30f, if (s.face == Face.HAND) 1.15f else 1.34f)
        private val wordSpans: List<IntRange> = Regex("""\S+""").findAll(ql.text).map { it.range }.toList()
        private val chunks: List<String> = run {
            val out = ArrayList<String>(); var cur = StringBuilder()
            for (wd in words.split(Regex("""\s+""")).filter { it.isNotBlank() }) {
                if (cur.isNotEmpty() && (cur.length + wd.length > 16 || cur.count { it == ' ' } >= 2)) { out += cur.toString(); cur = StringBuilder() }
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(wd)
            }
            if (cur.isNotEmpty()) out += cur.toString()
            out
        }

        // The timeline, in seconds.
        private val tIn = 0.9f
        private val reveal: Float = (when (s.motion) {
            Motion.TYPE -> words.length / 30f
            Motion.WORDS -> wordSpans.size * 0.2f
            Motion.LINES -> ql.lineCount * 0.55f
            Motion.HIGHLIGHT -> ql.lineCount * 0.65f
            Motion.KINETIC -> chunks.size * 0.6f + 0.6f
            Motion.FADE -> 2.4f
        } / s.pace.k).coerceIn(1.4f, 11f)
        private val tTyped = tIn + 0.3f + reveal
        private val tNote = tTyped + (if (nl != null) 1.3f else 0f)
        private val tTitle = tNote + 0.6f
        override val duration: Float = tTitle + 2.2f

        private fun background(): Bitmap {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            val full = RectF(0f, 0f, w.toFloat(), h.toFloat())
            fun shade(top: Int, mid: Int, bottom: Int) = cv.drawRect(full, Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(top, mid, bottom), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            })
            when (backdrop) {
                Backdrop.BLUR -> { val soft = Ink.softBlur(x.cover!!); Ink.drawCover(cv, soft, full); soft.recycle(); shade(0x73000000, 0x99000000.toInt(), 0xD9000000.toInt()) }
                Backdrop.COVER -> { Ink.drawCover(cv, x.cover!!, full); shade(0x40000000, 0x80000000.toInt(), 0xF0000000.toInt()) }
                Backdrop.COLOUR -> {
                    cv.drawRect(full, Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Ink.shade(tint, 0.55f), Ink.shade(tint, 0.18f), Shader.TileMode.CLAMP) })
                    cv.drawCircle(w * 0.2f, h * 0.15f, w * 1.0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = RadialGradient(w * 0.2f, h * 0.15f, w * 1.0f, Ink.alpha(Ink.lighten(tint, 0.25f), 150), Ink.alpha(tint, 0), Shader.TileMode.CLAMP)
                    })
                }
                Backdrop.NIGHT -> Ink.wallpaper(cv, w, h, tint, 0.15f)
                Backdrop.PAPER -> {
                    cv.drawColor(0xFFEDE6D8.toInt())
                    // A faint grain, so it reads as paper rather than a flat colour.
                    val rnd = java.util.Random(7); val dot = Paint()
                    repeat(9000) { dot.color = if (rnd.nextBoolean()) 0x10000000 else 0x14FFFFFF; val px = rnd.nextFloat() * w; val py = rnd.nextFloat() * h; cv.drawRect(px, py, px + 2f, py + 2f, dot) }
                    cv.drawRect(full, Paint().apply { shader = RadialGradient(w / 2f, h / 2f, h * 0.8f, intArrayOf(0x00000000, 0x00000000, 0x33000000), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP) })
                }
            }
            return bmp
        }

        private fun ease(t: Float) = 1f - (1f - t.coerceIn(0f, 1f)).pow(3)
        private fun settle(t: Float): Float { val v = t.coerceIn(0f, 1f); val c1 = 1.5f; val c3 = c1 + 1; return 1 + c3 * (v - 1).pow(3) + c1 * (v - 1).pow(2) }
        private fun inked(alpha: Float) = TextPaint(qp).apply { this.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt() }

        /** The frame at [t] seconds, drawn in the video's own size (scale the canvas for a smaller preview). */
        override fun draw(cv: Canvas, t: Float) {
            cv.drawColor(0xFF000000.toInt())
            // The background fades up, and drifts in slowly when zoom is on.
            val z = if (s.zoom) 1f + 0.08f * (t / duration).coerceIn(0f, 1f) else 1f
            cv.save(); cv.scale(z, z, w / 2f, h / 2f)
            cv.drawBitmap(back, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (255 * ease(t / 0.6f)).toInt() })
            cv.restore()
            // The cover drops in and settles.
            bookSprite?.let { b ->
                val k = settle(t / tIn)
                cv.save(); cv.translate(w / 2f, coverCenterY - (1 - k) * (coverCenterY + b.height)); cv.rotate((1 - k) * -14f - 2f)
                cv.drawBitmap(b, -b.width / 2f, -b.height / 2f, Paint(Paint.FILTER_BITMAP_FLAG))
                cv.restore()
            }
            // The page rises into place.
            val pk = ease((t - 0.4f) / 0.6f)
            cv.save()
            if (card != null && cardSprite != null) {
                if (pk <= 0f) { cv.restore(); return }
                cv.translate(0f, (1 - pk) * 120f)
                cv.drawBitmap(cardSprite, card.left - pad, card.top - pad, Paint().apply { alpha = (255 * pk).toInt() })
            }
            val left = textBox.left
            val top = textBox.top
            val r0 = tIn + 0.3f
            val p = ((t - r0) / reveal).coerceIn(0f, 1f)
            when (s.motion) {
                Motion.TYPE -> {
                    val shown = (p * ql.text.length).toInt()
                    for (line in 0 until ql.lineCount) {
                        val st = ql.getLineStart(line); val en = ql.getLineEnd(line)
                        if (st >= shown) break
                        cv.drawText(ql.text.subSequence(st, min(en, shown)).toString().trimEnd('\n'), left + ql.getLineLeft(line), top + ql.getLineBaseline(line), qp)
                    }
                    val blink = t < tTyped + 0.2f || ((t * 2).toInt() % 2 == 0)
                    if (t > tIn && blink && t < tTitle + 0.5f) {
                        val at = shown.coerceIn(0, ql.text.length)
                        val line = ql.getLineForOffset(at)
                        val cx = left + ql.getPrimaryHorizontal(at)
                        cv.drawRect(cx + 4f, top + ql.getLineTop(line) + 10f, cx + 9f, top + ql.getLineBottom(line) - 8f, Paint().apply { color = if (onPaper) Ink.alpha(tint, 220) else Ink.lighten(tint, 0.3f) })
                    }
                }
                Motion.WORDS -> {
                    val step = reveal / wordSpans.size.coerceAtLeast(1)
                    wordSpans.forEachIndexed { i, rg ->
                        val k = ease((t - r0 - i * step) / 0.35f)
                        if (k <= 0f) return@forEachIndexed
                        val line = ql.getLineForOffset(rg.first)
                        cv.drawText(ql.text.subSequence(rg.first, rg.last + 1).toString(), left + ql.getPrimaryHorizontal(rg.first), top + ql.getLineBaseline(line) + (1 - k) * 22f, inked(k))
                    }
                }
                Motion.LINES -> {
                    val step = reveal / ql.lineCount.coerceAtLeast(1)
                    for (line in 0 until ql.lineCount) {
                        val k = ease((t - r0 - line * step) / 0.5f)
                        if (k <= 0f) continue
                        cv.drawText(ql.text.subSequence(ql.getLineStart(line), ql.getLineEnd(line)).toString().trimEnd('\n'), left + ql.getLineLeft(line), top + ql.getLineBaseline(line) + (1 - k) * 36f, inked(k))
                    }
                }
                Motion.HIGHLIGHT -> {
                    val step = reveal / ql.lineCount.coerceAtLeast(1)
                    val textIn = ease((t - 0.5f) / 0.5f)
                    for (line in 0 until ql.lineCount) {
                        val lp = ((t - r0 - line * step) / step).coerceIn(0f, 1f)
                        val l0 = left + ql.getLineLeft(line); val l1 = left + ql.getLineRight(line)
                        val lt = top + ql.getLineTop(line) + 8f; val lb = top + ql.getLineBottom(line) - 4f
                        if (lp > 0f) cv.drawRoundRect(RectF(l0 - 8f, lt, l0 + (l1 - l0 + 16f) * lp - 8f, lb), 10f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = markInk })
                        val txt = ql.text.subSequence(ql.getLineStart(line), ql.getLineEnd(line)).toString().trimEnd('\n')
                        cv.drawText(txt, l0, top + ql.getLineBaseline(line), inked(0.42f * textIn))
                        if (lp > 0f) {
                            cv.save(); cv.clipRect(l0 - 8f, lt - 20f, l0 + (l1 - l0 + 16f) * lp, lb + 20f)
                            cv.drawText(txt, l0, top + ql.getLineBaseline(line), inked(textIn))
                            cv.restore()
                        }
                    }
                }
                Motion.KINETIC -> {
                    val words2 = reveal - 0.6f
                    val tt = t - r0
                    if (tt < words2) {
                        if (tt >= 0f) {
                            val per = words2 / chunks.size.coerceAtLeast(1)
                            val i = (tt / per).toInt().coerceIn(0, chunks.lastIndex)
                            val k = ((tt - i * per) / (per * 0.35f)).coerceIn(0f, 1f)
                            val big = TextPaint(qp).apply { textSize = 150f * s.face.scale; alpha = (255 * k).toInt() }
                            val wdt = big.measureText(chunks[i])
                            if (wdt > textBox.width()) big.textSize *= textBox.width() / wdt
                            val sc = 0.86f + 0.14f * ease(k)
                            cv.save(); cv.scale(sc, sc, w / 2f, textBox.centerY())
                            big.textAlign = Paint.Align.CENTER
                            cv.drawText(chunks[i], w / 2f, textBox.centerY() + big.textSize * 0.35f, big)
                            cv.restore()
                        }
                    } else {
                        val k = ease((tt - words2) / 0.6f)
                        cv.save(); cv.translate(0f, (1 - k) * 30f)
                        for (line in 0 until ql.lineCount) cv.drawText(ql.text.subSequence(ql.getLineStart(line), ql.getLineEnd(line)).toString().trimEnd('\n'), left + ql.getLineLeft(line), top + ql.getLineBaseline(line), inked(k))
                        cv.restore()
                    }
                }
                Motion.FADE -> {
                    val k = ease(p)
                    cv.save(); cv.translate(0f, (1 - k) * 50f)
                    for (line in 0 until ql.lineCount) cv.drawText(ql.text.subSequence(ql.getLineStart(line), ql.getLineEnd(line)).toString().trimEnd('\n'), left + ql.getLineLeft(line), top + ql.getLineBaseline(line), inked(k))
                    cv.restore()
                }
            }
            // Your note, written in from left to right under the passage.
            if (nl != null && t > tTyped) {
                val nk = ((t - tTyped) / (tNote - tTyped)).coerceIn(0f, 1f)
                val ny = top + ql.height + 40f
                cv.save(); cv.clipRect(left, ny, left + textBox.width() * ease(nk) + 20f, ny + nl.height + 10f)
                Ink.text(cv, nl, left, ny)
                cv.restore()
            }
            // The book's title and where the passage is, fading up.
            if (s.title) {
                val tk = ease((t - tTitle + 0.6f) / 0.6f)
                if (tk > 0f) {
                    val by = when (s.layout) { Layout.CARD -> card!!.bottom - 70f; else -> textBox.bottom + 90f }
                    val dim = if (onPaper) 0xFF8A8176.toInt() else Ink.DIM
                    cv.drawRect(left, by - 70f, left + 60f, by - 65f, Paint().apply { color = Ink.alpha(if (onPaper) tint else Ink.lighten(tint, 0.3f), (255 * tk).toInt()) })
                    Ink.line(cv, QuoteCard.shortTitle(x.title) + (x.author?.let { " · $it" } ?: ""), left, by - 18f, f.interBold, 32f, Ink.alpha(ink, (255 * tk).toInt()), textBox.width())
                    x.where?.let { Ink.line(cv, it.uppercase(), left, by + 24f, f.mono, 22f, Ink.alpha(dim, (255 * tk).toInt()), textBox.width(), spacing = 0.12f) }
                }
            }
            cv.restore()
            if (s.mark && t > tTitle) {
                val a = ease((t - tTitle) / 0.6f)
                val saved = cv.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), (255 * a).toInt())
                Ink.mark(cv, f, w / 2f, h - 60f)
                cv.restoreToCount(saved)
            }
        }

        fun recycle() { back.recycle(); bookSprite?.recycle(); cardSprite?.recycle() }
    }

    /** Encodes [scene] into an MP4 in the cache and returns it. [progress] gets 0–1 as frames are made. Blocking. */
    fun encode(context: Context, scene: Frames, progress: (Float) -> Unit = {}): File {
        val frames = (scene.duration * FPS).toInt()
        val out = File(File(context.cacheDir, "shares").apply { mkdirs() }, "quote-${System.currentTimeMillis()}.mp4")
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, scene.w, scene.h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var written = 0L
        val info = MediaCodec.BufferInfo()
        fun drain(end: Boolean) {
            while (true) {
                val i = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start() }
                    i >= 0 -> {
                        val buf = codec.getOutputBuffer(i)
                        if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && track >= 0) {
                            // Frames as made, a 30th of a second apart, whatever the clock said.
                            info.presentationTimeUs = written * 1_000_000L / FPS
                            written++
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(i, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                    }
                }
            }
        }
        try {
            for (n in 0 until frames) {
                val cv = surface.lockHardwareCanvas()
                try { scene.draw(cv, n.toFloat() / FPS) } finally { surface.unlockCanvasAndPost(cv) }
                drain(false)
                progress((n + 1f) / frames)
            }
            codec.signalEndOfInputStream()
            drain(true)
        } finally {
            runCatching { codec.stop() }; runCatching { codec.release() }; runCatching { surface.release() }
            runCatching { if (track >= 0) muxer.stop() }; runCatching { muxer.release() }
        }
        return out
    }

    // ---- handing it on ----

    fun share(context: Context, file: File, text: String) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".pages", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            clipData = android.content.ClipData.newRawUri("Quote", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share video").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    /** Into Movies/Readync. False on Android 9 and older (where that needs a permission). */
    fun save(context: Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/" + io.github.prithirajn.readync.Edition.name)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val cr = context.contentResolver
        val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        cr.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } } ?: return false
        cr.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        return true
    }
}

/**
 * The video, playing on a loop so you can watch it before saving or sending it. Tap to pause and
 * play again.
 */
@androidx.compose.runtime.Composable
fun VideoPreview(file: File, aspect: Float, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    var playing by androidx.compose.runtime.remember(file) { androidx.compose.runtime.mutableStateOf(true) }
    var player by androidx.compose.runtime.remember(file) { androidx.compose.runtime.mutableStateOf<android.widget.VideoView?>(null) }
    androidx.compose.foundation.layout.Box(
        modifier
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(18.dp))
            .background(androidx.compose.ui.graphics.Color.Black)
            .bouncyClickable(RoundedCornerShape(18.dp), pressedScale = 0.98f, onClickLabel = if (playing) "Pause" else "Play") {
                player?.let { v -> if (v.isPlaying) { v.pause(); playing = false } else { v.start(); playing = true } }
            },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                android.widget.VideoView(ctx).apply {
                    setVideoPath(file.path)
                    setOnPreparedListener { mp -> mp.isLooping = true; mp.setVolume(0f, 0f); start() }
                    player = this
                }
            },
            modifier = androidx.compose.ui.Modifier.matchParentSize(),
            onRelease = { it.stopPlayback() },
        )
        if (!playing) {
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.size(64.dp).clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Color(0x99000000)),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) { androidx.compose.material3.Text("▶", style = io.github.prithirajn.readync.ui.theme.CpType.section, color = androidx.compose.ui.graphics.Color.White) }
        }
    }
}
