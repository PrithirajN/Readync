package io.github.prithirajn.trackersync.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File
import kotlin.math.min
import kotlin.math.pow

/**
 * A passage as a short video for Reels, Stories or Threads: the book's cover blurred behind, the
 * cover itself dropping in and settling, then the passage typing itself onto the page with a
 * cursor, your note written in by hand under it, the title and author fading up, and a moment's
 * hold at the end. Encoded on the phone as an MP4 (H.264), 30 frames a second.
 */
object QuoteVideo {
    private const val FPS = 30
    private const val W = 1080

    class Input(val text: String, val note: String?, val title: String, val author: String?, val where: String?, val cover: Bitmap?, val story: Boolean)

    /** Makes the video into the cache and returns it. [progress] gets 0–1 as frames are made. Blocking. */
    fun make(context: Context, x: Input, progress: (Float) -> Unit = {}): File {
        val h = if (x.story) 1920 else 1350
        val f = Ink.faces(context)
        val tint = x.cover?.let { Ink.coverColor(it) } ?: Ink.SIGNAL
        val back = makeBackground(x.cover, tint, h)

        // The page: a cream card the passage types onto.
        val coverH = if (x.story) 520f else 380f
        val coverTop = if (x.story) 230f else 110f
        val card = RectF(80f, coverTop + coverH + 80f, W - 80f, h - (if (x.story) 260f else 150f))
        val qp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.lora; color = 0xFF221F1C.toInt() }
        val words = io.github.prithirajn.trackersync.trackers.quoted(x.text.trim())
        val noteH = if (x.note.isNullOrBlank()) 0 else 230
        val ql = Ink.fit(words, qp, (card.width() - 140).toInt(), (card.height() - 170 - noteH).toInt(), 64f, 32f, 1.34f)
        val np = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.caveat; textSize = 58f; color = Ink.shade(tint, 0.55f) }
        val nl = x.note?.takeIf { it.isNotBlank() }?.let { Ink.layout(it.trim(), np, (card.width() - 140).toInt(), 1.05f, 3) }

        // What doesn't change is drawn once, in software (soft shadows don't draw on the encoder's
        // hardware canvas): the book with its shadow, and the page with its shadow.
        val cw = coverH * 2f / 3f
        val pad = 60
        val bookSprite = Bitmap.createBitmap((cw + pad * 2).toInt(), (coverH + pad * 2).toInt(), Bitmap.Config.ARGB_8888).also {
            Ink.drawBook(Canvas(it), x.cover, RectF(pad.toFloat(), pad.toFloat(), pad + cw, pad + coverH), tint, f, x.title)
        }
        val cardSprite = Bitmap.createBitmap((card.width() + pad * 2).toInt(), (card.height() + pad * 2).toInt(), Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawRoundRect(RectF(pad.toFloat(), pad.toFloat(), pad + card.width(), pad + card.height()), 36f, 36f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFF3EFE7.toInt(); setShadowLayer(40f, 0f, 18f, 0x88000000.toInt())
            })
        }

        // The timeline, in seconds.
        val tIn = 0.9f
        val chars = words.length
        val typeSecs = (chars / 30f).coerceIn(1.5f, 7f)
        val tTyped = tIn + 0.3f + typeSecs
        val tNote = tTyped + (if (nl != null) 1.3f else 0f)
        val tTitle = tNote + 0.6f
        val total = tTitle + 2.2f
        val frames = (total * FPS).toInt()

        val out = File(File(context.cacheDir, "shares").apply { mkdirs() }, "quote-${System.currentTimeMillis()}.mp4")
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 7_000_000)
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
                val t = n.toFloat() / FPS
                val cv = surface.lockHardwareCanvas()
                try {
                    frame(cv, t, h, back, bookSprite, cardSprite, pad, x, f, tint, coverTop, coverH, card, ql, qp, nl, tIn, tTyped, typeSecs, tNote, tTitle)
                } finally {
                    surface.unlockCanvasAndPost(cv)
                }
                drain(false)
                progress((n + 1f) / frames)
            }
            codec.signalEndOfInputStream()
            drain(true)
        } finally {
            runCatching { codec.stop() }; runCatching { codec.release() }; runCatching { surface.release() }
            runCatching { if (track >= 0) muxer.stop() }; runCatching { muxer.release() }
            back.recycle(); bookSprite.recycle(); cardSprite.recycle()
        }
        return out
    }

    private fun ease(t: Float) = 1f - (1f - t.coerceIn(0f, 1f)).pow(3)

    /** Overshoots a little and settles, like a thing dropped onto a desk. */
    private fun settle(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        val c1 = 1.5f; val c3 = c1 + 1
        return 1 + c3 * (x - 1).pow(3) + c1 * (x - 1).pow(2)
    }

    private fun makeBackground(cover: Bitmap?, tint: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        if (cover != null) {
            // Blurred by shrinking hard and growing back, then darkened.
            val small = Bitmap.createScaledBitmap(cover, 24, (24f * cover.height / cover.width).toInt().coerceAtLeast(2), true)
            val soft = Bitmap.createScaledBitmap(small, 240, (240f * small.height / small.width).toInt().coerceAtLeast(2), true)
            Ink.drawCover(cv, soft, RectF(0f, 0f, W.toFloat(), h.toFloat()))
            small.recycle(); soft.recycle()
        } else cv.drawColor(Ink.shade(tint, 0.4f))
        cv.drawRect(0f, 0f, W.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0x73000000, 0x99000000.toInt(), 0xD9000000.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        })
        return bmp
    }

    private fun frame(
        cv: Canvas, t: Float, h: Int, back: Bitmap, bookSprite: Bitmap, cardSprite: Bitmap, pad: Int, x: Input, f: Ink.Faces, tint: Int,
        coverTop: Float, coverH: Float, card: RectF, ql: StaticLayout, qp: TextPaint, nl: StaticLayout?,
        tIn: Float, tTyped: Float, typeSecs: Float, tNote: Float, tTitle: Float,
    ) {
        // The background fades up from black.
        cv.drawColor(0xFF000000.toInt())
        cv.drawBitmap(back, 0f, 0f, Paint().apply { alpha = (255 * ease(t / 0.6f)).toInt() })
        // The cover drops in from above and settles, turning slightly as it lands.
        val k = settle(t / tIn)
        val cy = coverTop + coverH / 2 - (1 - k) * (coverTop + coverH)
        cv.save(); cv.translate(W / 2f, cy); cv.rotate((1 - k) * -14f - 2f)
        cv.drawBitmap(bookSprite, -bookSprite.width / 2f, -bookSprite.height / 2f, Paint(Paint.FILTER_BITMAP_FLAG))
        cv.restore()
        // The page slides up into place.
        val pk = ease((t - 0.4f) / 0.6f)
        if (pk > 0f) {
            cv.save(); cv.translate(0f, (1 - pk) * 120f)
            cv.drawBitmap(cardSprite, card.left - pad, card.top - pad, Paint().apply { alpha = (255 * pk).toInt() })
            val left = card.left + 70f
            val top = card.top + 80f
            // The passage, typed out to the letter, with a blinking cursor at the end.
            val shown = (((t - tIn - 0.3f) / typeSecs).coerceIn(0f, 1f) * ql.text.length).toInt()
            if (shown > 0) {
                for (line in 0 until ql.lineCount) {
                    val s = ql.getLineStart(line); val e = ql.getLineEnd(line)
                    if (s >= shown) break
                    val part = ql.text.subSequence(s, min(e, shown)).toString().trimEnd('\n')
                    cv.drawText(part, left + ql.getLineLeft(line), top + ql.getLineBaseline(line), qp)
                }
            }
            val blinkOn = t < tTyped + 0.2f || ((t * 2).toInt() % 2 == 0 && t < tTitle)
            if (shown >= 0 && t > tIn && blinkOn && t < tTitle + 0.5f) {
                val at = shown.coerceIn(0, ql.text.length)
                val line = ql.getLineForOffset(at)
                val cx = left + ql.getPrimaryHorizontal(at)
                cv.drawRect(cx + 4f, top + ql.getLineTop(line) + 10f, cx + 9f, top + ql.getLineBottom(line) - 8f, Paint().apply { color = Ink.alpha(tint, 220) })
            }
            // Your note, written in from left to right under the passage.
            if (nl != null && t > tTyped) {
                val nk = ((t - tTyped) / (tNote - tTyped)).coerceIn(0f, 1f)
                val ny = top + ql.height + 50f
                cv.save()
                cv.clipRect(left, ny, left + (card.width() - 140f) * ease(nk) + 20f, ny + nl.height + 10f)
                Ink.text(cv, nl, left, ny)
                cv.restore()
            }
            // The book, under the passage, fading up.
            val tk = ease((t - tTitle + 0.6f) / 0.6f)
            if (tk > 0f) {
                val by = card.bottom - 70f
                cv.drawRect(left, by - 70f, left + 60f, by - 65f, Paint().apply { color = Ink.alpha(tint, (255 * tk).toInt()) })
                Ink.line(cv, QuoteCard.shortTitle(x.title) + (x.author?.let { " · $it" } ?: ""), left, by - 18f, f.interBold, 32f, Ink.alpha(0xFF221F1C.toInt(), (255 * tk).toInt()), card.width() - 140f)
                x.where?.let { Ink.line(cv, it.uppercase(), left, by + 24f, f.mono, 22f, Ink.alpha(0xFF8A8176.toInt(), (255 * tk).toInt()), card.width() - 140f, spacing = 0.12f) }
            }
            cv.restore()
        }
        // The mark at the foot, at the end.
        if (t > tTitle) {
            val a = ease((t - tTitle) / 0.6f)
            val saved = cv.saveLayerAlpha(0f, 0f, W.toFloat(), h.toFloat(), (255 * a).toInt())
            Ink.mark(cv, f, W / 2f, h - 70f)
            cv.restoreToCount(saved)
        }
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
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/" + io.github.prithirajn.trackersync.Edition.name)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val cr = context.contentResolver
        val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        cr.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } } ?: return false
        cr.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        return true
    }
}
