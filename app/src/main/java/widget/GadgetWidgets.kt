package io.github.prithirajn.trackersync.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.text.TextPaint
import android.widget.RemoteViews
import io.github.prithirajn.readync.R
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.sync.Highlights
import io.github.prithirajn.readync.ui.CoverCache
import io.github.prithirajn.readync.ui.Ink
import io.github.prithirajn.readync.ui.QuoteCard

/**
 * Widgets drawn as the app's little reading device: a graphite body with a dark screen inside.
 * Each is one picture drawn at the widget's size, redrawn after every sync.
 */
internal object GadgetDraw {
    /** The widget's size in pixels (capped, as a widget's picture has to stay small). */
    fun size(context: Context, options: Bundle?, minW: Int, minH: Int): Pair<Int, Int> {
        val d = context.resources.displayMetrics.density.coerceAtMost(2.5f)
        val portrait = context.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val wDp = options?.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)?.takeIf { it > 0 } ?: minW
        val hDp = options?.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)?.takeIf { it > 0 } ?: minH
        return (wDp * d).toInt().coerceIn(200, 1000) to (hDp * d).toInt().coerceIn(120, 700)
    }

    /** The body and its screen; returns the screen's box. */
    fun body(cv: Canvas, w: Int, h: Int, d: Float): RectF {
        val r = 22f * d
        val outer = RectF(0f, 0f, w.toFloat(), h.toFloat())
        cv.drawRoundRect(outer, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF2B2A27.toInt(), 0xFF1A1917.toInt(), Shader.TileMode.CLAMP)
        })
        cv.drawRoundRect(RectF(1f, 1f, w - 1f, h - 1f), r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x22FFFFFF })
        val pad = 8f * d
        val screen = RectF(pad, pad, w - pad, h - pad)
        cv.drawRoundRect(screen, r * 0.62f, r * 0.62f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.SCREEN })
        // Scanlines.
        val line = Paint().apply { color = 0x07FFFFFF }
        var y = screen.top
        while (y < screen.bottom) { cv.drawRect(screen.left, y, screen.right, y + 1f, line); y += 3f * d }
        return screen
    }
}

/** 3×1: your current book on the device's screen, its percentage in pixel digits and a bar of segments. */
class DeviceWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = refresh(context)

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, DeviceWidget::class.java))
            if (ids.isEmpty()) return
            val s = Widgets.snapshot(context) ?: return
            for (id in ids) {
                val (w, h) = GadgetDraw.size(context, manager.getAppWidgetOptions(id), 250, 80)
                val v = RemoteViews(context.packageName, R.layout.widget_picture)
                v.setImageViewBitmap(R.id.picture_image, draw(context, s, w, h))
                v.setOnClickPendingIntent(R.id.picture_root, Widgets.openApp(context))
                manager.updateAppWidget(id, v)
            }
        }

        private fun draw(context: Context, s: Snapshot, w: Int, h: Int): Bitmap {
            val d = context.resources.displayMetrics.density.coerceAtMost(2.5f)
            val f = Ink.faces(context)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            val sc = GadgetDraw.body(cv, w, h, d)
            val book = Widgets.reading(s).firstOrNull() ?: s.books.values.maxByOrNull { it.timestamp }
            val inner = RectF(sc.left + 12f * d, sc.top + 10f * d, sc.right - 12f * d, sc.bottom - 10f * d)
            // The cover at the left, when it's on the phone already.
            var textLeft = inner.left
            book?.coverUrl?.let { u -> runCatching { CoverCache.loadCached(context, u, 300) }.getOrNull() }?.let { cover ->
                val ch = inner.height()
                val cw = ch * 2f / 3f
                Ink.drawCover(cv, cover, RectF(inner.left, inner.top, inner.left + cw, inner.bottom), 4f * d)
                textLeft = inner.left + cw + 12f * d
            }
            val tw = inner.right - textLeft
            Ink.line(cv, if (book == null) "NOTHING IN PROGRESS" else "NOW READING", textLeft, inner.top + 11f * d, f.mono, 9f * d, Ink.DIM, tw, spacing = 0.08f)
            Ink.line(cv, book?.displayTitle?.let { QuoteCard.shortTitle(it) } ?: io.github.prithirajn.readync.Edition.name, textLeft, inner.top + 28f * d, f.interBold, 14f * d, Ink.TEXT, tw)
            val pct = book?.percent ?: 0
            // The big number, sized to the room under the title.
            val numSize = (inner.height() - 52f * d).coerceIn(16f * d, 46f * d)
            val np = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.pixel; textSize = numSize; color = Ink.SIGNAL }
            val base = inner.bottom - 16f * d
            cv.drawText("$pct", textLeft, base, np)
            val pw = np.measureText("$pct")
            Ink.line(cv, "%", textLeft + pw + 4f * d, base, f.mono, 12f * d, Ink.DIM, 30f * d)
            book?.let { b ->
                val detail = if (b.currentPage != null && b.pageCount != null) "p. ${b.currentPage} / ${b.pageCount}" else b.status?.label ?: ""
                Ink.line(cv, detail, inner.right, base, f.mono, 10f * d, Ink.DIM, tw / 2, Paint.Align.RIGHT)
            }
            // The segments along the foot.
            val segs = 14
            val gap = 3f * d
            val segW = (tw - gap * (segs - 1)) / segs
            val sy = inner.bottom - 6f * d
            for (i in 0 until segs) {
                val lit = (i + 0.5f) / segs <= pct / 100f
                cv.drawRoundRect(RectF(textLeft + i * (segW + gap), sy, textLeft + i * (segW + gap) + segW, sy + 5f * d), 2f * d, 2f * d,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (lit) Ink.SIGNAL else 0xFF1E1E1C.toInt() })
            }
            return bmp
        }
    }
}

/** 4×2: one of your highlights each day, on the device's screen, with its book. */
class HighlightWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = refresh(context)

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, HighlightWidget::class.java))
            if (ids.isEmpty()) return
            val s = Widgets.snapshot(context) ?: return
            for (id in ids) {
                val (w, h) = GadgetDraw.size(context, manager.getAppWidgetOptions(id), 300, 140)
                val v = RemoteViews(context.packageName, R.layout.widget_picture)
                v.setImageViewBitmap(R.id.picture_image, draw(context, s, w, h))
                v.setOnClickPendingIntent(R.id.picture_root, Widgets.openApp(context))
                manager.updateAppWidget(id, v)
            }
        }

        private fun draw(context: Context, s: Snapshot, w: Int, h: Int): Bitmap {
            val d = context.resources.displayMetrics.density.coerceAtMost(2.5f)
            val f = Ink.faces(context)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            val sc = GadgetDraw.body(cv, w, h, d)
            val inner = RectF(sc.left + 14f * d, sc.top + 12f * d, sc.right - 14f * d, sc.bottom - 12f * d)
            val item = Highlights.ofTheDay(Highlights.all(s), java.time.LocalDate.now())
            cv.drawCircle(inner.left + 3f * d, inner.top + 6f * d, 3f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.SIGNAL })
            Ink.line(cv, "HIGHLIGHT OF THE DAY", inner.left + 12f * d, inner.top + 9f * d, f.mono, 9f * d, Ink.DIM, inner.width())
            if (item == null) {
                Ink.line(cv, "Your highlights will show here.", inner.left, inner.centerY(), f.inter, 13f * d, Ink.TEXT, inner.width())
                return bmp
            }
            val book = s.books[item.clip.document]
            val qp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.lora; color = Ink.TEXT }
            val room = (inner.height() - 44f * d).toInt()
            val ql = Ink.fit(io.github.prithirajn.readync.trackers.quoted(item.clip.text.trim()), qp, inner.width().toInt(), room, 17f * d, 10f * d, 1.3f)
            Ink.text(cv, ql, inner.left, inner.top + 22f * d)
            Ink.line(cv, (book?.displayTitle?.let { QuoteCard.shortTitle(it) } ?: "") + (book?.author?.let { " · $it" } ?: ""), inner.left, inner.bottom, f.interBold, 10.5f * d, Ink.SIGNAL, inner.width())
            return bmp
        }
    }
}
