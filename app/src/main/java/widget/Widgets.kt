package io.github.prithirajn.trackersync.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.widget.RemoteViews
import io.github.prithirajn.readync.MainActivity
import io.github.prithirajn.readync.R
import io.github.prithirajn.readync.ReadyncApp
import io.github.prithirajn.readync.data.Book
import io.github.prithirajn.readync.data.ReadStatus
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.ui.CoverAccent
import io.github.prithirajn.readync.ui.CoverCache
import io.github.prithirajn.readync.util.Palette

/** Redraws every Tracker Sync widget that's placed: the sync card, the ring and the shelf. */
object Widgets {
    fun refreshAll(context: Context) {
        SyncWidget.refresh(context)
        RingWidget.refresh(context)
        ShelfWidget.refresh(context)
        DeviceWidget.refresh(context)
        HighlightWidget.refresh(context)
    }

    internal fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    internal fun snapshot(context: Context): Snapshot? = (context.applicationContext as? ReadyncApp)?.store?.snapshot

    internal fun dark(context: Context) =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** The book colour the app wears, in the widget's light or dark. */
    internal fun accent(context: Context, s: Snapshot): Palette.Accent? =
        CoverAccent.forWidget(context, CoverAccent.latestCover(s.books.values))?.let { Palette.accentFrom(it, dark(context)) }

    /** Books in progress, newest first. */
    internal fun reading(s: Snapshot): List<Book> =
        s.books.values.filter { it.status == null || it.status == ReadStatus.READING }.sortedByDescending { it.timestamp }

    internal fun update(context: Context, cls: Class<*>, build: (Snapshot) -> RemoteViews) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, cls))
        if (ids.isEmpty()) return
        val s = snapshot(context) ?: return
        val views = build(s)
        for (id in ids) manager.updateAppWidget(id, views)
    }
}

/** 2×1: the current book as a progress ring in its own colour, with its title and page. */
class RingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    companion object {
        fun refresh(context: Context) = Widgets.update(context, RingWidget::class.java) { s -> build(context, s) }

        private fun build(context: Context, s: Snapshot): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_ring)
            v.setOnClickPendingIntent(R.id.ring_root, Widgets.openApp(context))
            val accent = Widgets.accent(context, s)
            val book = Widgets.reading(s).firstOrNull() ?: s.books.values.maxByOrNull { it.timestamp }
            val pct = book?.percent ?: 0
            v.setImageViewBitmap(
                R.id.ring_image,
                ring(
                    context, pct,
                    accent?.accent ?: context.getColor(R.color.widget_accent),
                    context.getColor(R.color.widget_track),
                    context.getColor(R.color.widget_text),
                ),
            )
            v.setTextViewText(R.id.ring_title, book?.displayTitle ?: "Nothing in progress")
            v.setTextViewText(
                R.id.ring_detail,
                when {
                    book == null -> "Open ${io.github.prithirajn.readync.Edition.name}"
                    book.currentPage != null && book.pageCount != null -> "Page ${book.currentPage} of ${book.pageCount}"
                    else -> "${book.status?.label ?: "Reading"}"
                },
            )
            accent?.let { v.setTextColor(R.id.ring_detail, it.text) }
            return v
        }

        /** The ring: a soft track, the read part in the book's colour, the percentage inside. */
        private fun ring(context: Context, pct: Int, fill: Int, track: Int, text: Int): Bitmap {
            val d = context.resources.displayMetrics.density
            val size = (44 * d).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            val stroke = 4.5f * d
            val rect = RectF(stroke / 2, stroke / 2, size - stroke / 2, size - stroke / 2)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND }
            p.color = track
            cv.drawArc(rect, 0f, 360f, false, p)
            if (pct > 0) {
                p.color = fill
                cv.drawArc(rect, -90f, 360f * pct.coerceIn(0, 100) / 100f, false, p)
            }
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = text
                textAlign = Paint.Align.CENTER
                textSize = 11.5f * context.resources.displayMetrics.scaledDensity
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            cv.drawText("$pct%", size / 2f, size / 2f - (t.ascent() + t.descent()) / 2f, t)
            return bmp
        }
    }
}

/** 4×2: covers of up to four books you're reading, newest first, each with its percentage. */
class ShelfWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    companion object {
        private val covers = intArrayOf(R.id.shelf_cover0, R.id.shelf_cover1, R.id.shelf_cover2, R.id.shelf_cover3)
        private val pcts = intArrayOf(R.id.shelf_pct0, R.id.shelf_pct1, R.id.shelf_pct2, R.id.shelf_pct3)
        private val slots = intArrayOf(R.id.shelf_slot0, R.id.shelf_slot1, R.id.shelf_slot2, R.id.shelf_slot3)

        fun refresh(context: Context) = Widgets.update(context, ShelfWidget::class.java) { s -> build(context, s) }

        private fun build(context: Context, s: Snapshot): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_shelf)
            v.setOnClickPendingIntent(R.id.shelf_root, Widgets.openApp(context))
            val accent = Widgets.accent(context, s)
            val books = Widgets.reading(s).take(4)
            v.setViewVisibility(R.id.shelf_empty, if (books.isEmpty()) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.shelf_row, if (books.isEmpty()) View.GONE else View.VISIBLE)
            v.setTextViewText(R.id.shelf_title, if (books.size == 1) "Reading" else "Reading · ${Widgets.reading(s).size}")
            for (i in 0 until 4) {
                val b = books.getOrNull(i)
                // Empty slots keep their space, so covers stay the same size.
                v.setViewVisibility(slots[i], if (b == null) View.INVISIBLE else View.VISIBLE)
                if (b == null) continue
                // Only covers already on the phone: a widget update can't wait for a download.
                val cover = b.coverUrl?.let { runCatching { CoverCache.loadCached(context, it, 240) }.getOrNull() }
                if (cover != null) v.setImageViewBitmap(covers[i], cover) else v.setImageViewResource(covers[i], R.drawable.lucide_book_open)
                v.setTextViewText(pcts[i], "${b.percent}%")
                accent?.let { v.setTextColor(pcts[i], it.text) }
            }
            return v
        }
    }
}
