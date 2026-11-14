package io.github.prithirajn.trackersync.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import io.github.prithirajn.trackersync.MainActivity
import io.github.prithirajn.trackersync.R
import io.github.prithirajn.trackersync.TrackerSyncApp
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.sync.SyncWorker
import io.github.prithirajn.trackersync.trackers.Trackers
import io.github.prithirajn.trackersync.ui.CoverAccent
import io.github.prithirajn.trackersync.ui.CoverCache
import io.github.prithirajn.trackersync.util.Palette
import java.text.DateFormat
import java.util.Date

/**
 * Home-screen widget: the book being read, when the app last synced, how the trackers are
 * doing, and a sync button. Redrawn from the app's saved state whenever it changes.
 */
class SyncWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_SYNC) {
            pressedAt = System.currentTimeMillis()
            refresh(context)
            SyncWorker.runNow(context)
            // Offline, the sync waits for a network: stop spinning after a while either way.
            val app = context.applicationContext
            Handler(Looper.getMainLooper()).postDelayed({ refresh(app) }, PRESS_MS + 500)
        }
    }

    companion object {
        private const val ACTION_SYNC = "io.github.prithirajn.trackersync.widget.SYNC"

        private const val PRESS_MS = 15_000L

        /** When the sync button was tapped: spins from the tap until the sync starts. */
        @Volatile
        private var pressedAt = 0L

        /** Redraws every placed widget. Cheap when none are placed. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, SyncWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val app = context.applicationContext as? TrackerSyncApp ?: return
            val s = app.store.snapshot
            val views = build(context, s)
            for (id in ids) manager.updateAppWidget(id, views)
        }

        private fun build(context: Context, s: Snapshot): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_sync)
            val accent = accentFor(context, s)
            paint(v, accent)

            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.widget_root, open)
            val sync = PendingIntent.getBroadcast(
                context, 1,
                Intent(context, SyncWidget::class.java).setAction(ACTION_SYNC),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.widget_sync, sync)

            if (s.running) pressedAt = 0L
            val busy = s.running || System.currentTimeMillis() - pressedAt < PRESS_MS
            v.setViewVisibility(R.id.widget_spinner, if (busy) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.widget_sync, if (busy) View.INVISIBLE else View.VISIBLE)
            v.setTextViewText(
                R.id.widget_time,
                when {
                    busy -> "Syncing…"
                    s.lastRun > 0 -> "Synced " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.lastRun))
                    else -> ""
                },
            )

            if (s.account == null) {
                v.setViewVisibility(R.id.widget_book_row, View.GONE)
                status(context, v, "Open ${io.github.prithirajn.trackersync.Edition.name} to sign in", error = false)
                return v
            }

            // The book being read now: the newest one marked Reading, else the newest of all.
            val books = s.books.values.sortedByDescending { it.timestamp }
            val book = books.firstOrNull { it.status == ReadStatus.READING || it.status == null } ?: books.firstOrNull()
            if (book == null) {
                v.setViewVisibility(R.id.widget_book_row, View.GONE)
            } else {
                v.setViewVisibility(R.id.widget_book_row, View.VISIBLE)
                v.setTextViewText(R.id.widget_book, book.displayTitle)
                v.setTextViewText(R.id.widget_author, book.author ?: "")
                v.setProgressBar(R.id.widget_progress, 100, book.percent, false)
                v.setTextViewText(R.id.widget_percent, "${book.percent}% · ${book.status?.label ?: "Reading"}")
                accent?.let { v.setTextColor(R.id.widget_percent, it.text) }
                // Only a cover already saved on the phone: a widget update can't wait for a download.
                val cover = book.coverUrl?.let { runCatching { CoverCache.loadCached(context, it, 180) }.getOrNull() }
                if (cover != null) v.setImageViewBitmap(R.id.widget_cover, cover) else v.setImageViewResource(R.id.widget_cover, R.drawable.lucide_book_open)
            }

            val linked = Trackers.all.filter { s.service(it.id).linked }
            val needSignIn = linked.filter { s.service(it.id).needsReauth }
            val waiting = s.pending.size
            when {
                linked.isEmpty() -> status(context, v, "No trackers linked yet", error = false, accent)
                needSignIn.isNotEmpty() -> status(context, v, "Sign in again: " + needSignIn.joinToString(", ") { it.name }, error = true, accent)
                waiting > 0 -> status(context, v, "$waiting update${if (waiting == 1) "" else "s"} waiting", error = false, accent)
                else -> status(
                    context, v,
                    if (linked.size == 1) "${linked[0].name} up to date" else "All ${linked.size} trackers up to date",
                    error = false,
                    accent,
                )
            }
            return v
        }

        private fun status(context: Context, v: RemoteViews, text: String, error: Boolean, accent: Palette.Accent? = null) {
            v.setTextViewText(R.id.widget_status, text)
            v.setTextColor(
                R.id.widget_status,
                when {
                    error -> context.getColor(R.color.widget_error)
                    accent != null -> accent.text
                    else -> context.getColor(R.color.widget_accent_text)
                },
            )
        }

        /** The app's colour, from the cover of the book last read, in the widget's light or dark. */
        private fun accentFor(context: Context, s: Snapshot): Palette.Accent? {
            val seed = CoverAccent.forWidget(context, CoverAccent.latestCover(s.books.values)) ?: return null
            val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            return Palette.accentFrom(seed, dark)
        }

        /**
         * Tints the sync button and progress bar. Android 12 and later can tint the bar and the
         * button's circle; older versions get the icon and text in the colour.
         */
        private fun paint(v: RemoteViews, accent: Palette.Accent?) {
            accent ?: return
            v.setInt(R.id.widget_sync, "setColorFilter", accent.text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                v.setColorStateList(R.id.widget_progress, "setProgressTintList", ColorStateList.valueOf(accent.accent))
                v.setColorStateList(R.id.widget_sync, "setBackgroundTintList", ColorStateList.valueOf(accent.tint))
            }
        }
    }
}
