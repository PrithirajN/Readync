package io.github.prithirajn.trackersync.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.prithirajn.trackersync.MainActivity
import io.github.prithirajn.trackersync.R
import java.util.concurrent.ConcurrentHashMap

/**
 * Android 16's Live Updates: a notification pinned at the top of the shade, with a chip by the
 * clock, for something under way that you started: a reading session (its timer on the chip), a
 * book downloading from your catalogs (how far), a sync you started (which step). Each is ongoing
 * while it runs and goes when it ends. On older Android versions the same notification shows as
 * an ordinary one with its progress bar.
 */
object LiveUpdates {

    private const val CHANNEL = "live-updates"
    const val TIMER_ID = 4100
    const val SYNC_ID = 4101
    private const val DOWNLOAD_BASE = 4200

    // Android 16's keys, set as extras so they work with this AndroidX version.
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    private const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"

    /** What the notification's buttons do, by action name (set while the thing is running). */
    internal val handlers = ConcurrentHashMap<String, () -> Unit>()

    private fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun channel(context: Context) {
        val ch = NotificationChannel(CHANNEL, "Live updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "A reading session, a download or a sync you started, while it's under way"
            setSound(null, null)
            enableVibration(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun open(context: Context, code: Int, action: String? = null, document: String? = null): PendingIntent {
        val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        action?.let { i.action = it }
        document?.let { i.putExtra(MainActivity.EXTRA_DOCUMENT, it) }
        return PendingIntent.getActivity(context, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun button(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, code,
            Intent(context, LiveUpdateReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun base(context: Context, title: String, text: String, chip: String?): NotificationCompat.Builder {
        channel(context)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addExtras(android.os.Bundle().apply {
                putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
                chip?.let { putString(EXTRA_SHORT_CRITICAL_TEXT, it) }
            })
    }

    private fun post(context: Context, id: Int, b: NotificationCompat.Builder) {
        if (!allowed(context)) return
        runCatching { NotificationManagerCompat.from(context).notify(id, b.build()) }
    }

    fun cancel(context: Context, id: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(id) }
    }

    // ---- 1. Reading session ----

    /**
     * The reading timer: the clock running on the chip (or "Paused"), the book, pages this session
     * and how far through the book, with Pause or Resume and End.
     */
    fun timer(
        context: Context, document: String, title: String, elapsedMs: Long, running: Boolean,
        page: Int, pagesThisSession: Int, pageCount: Int?,
    ) {
        val text = listOfNotNull(
            if (pageCount != null && pageCount > 0) "Page $page of $pageCount" else "Page $page",
            "$pagesThisSession page${if (pagesThisSession == 1) "" else "s"} this session",
            if (!running) "paused" else null,
        ).joinToString(" · ")
        val b = base(context, title, text, if (running) null else "Paused")
            .setContentIntent(open(context, 41, MainActivity.ACTION_OPEN_BOOK, document))
            .addAction(0, if (running) "Pause" else "Resume", button(context, if (running) "timer.pause" else "timer.resume", 42))
            .addAction(0, "End", button(context, "timer.end", 43))
        if (running) {
            // The chip counts up from when the session (less its pauses) began.
            b.setUsesChronometer(true).setShowWhen(true).setWhen(System.currentTimeMillis() - elapsedMs)
        } else {
            b.setUsesChronometer(false).setShowWhen(false)
        }
        if (pageCount != null && pageCount > 0) b.setProgress(pageCount, page.coerceIn(0, pageCount), false)
        post(context, TIMER_ID, b)
    }

    // ---- 2. Downloads from your catalogs ----

    fun downloadId(key: String) = DOWNLOAD_BASE + (key.hashCode() and 0x3FF)

    /** A book downloading: how much, how fast, how long left (the percentage on the chip), with Cancel. */
    fun download(context: Context, key: String, name: String, server: String, stage: String, done: Long, total: Long, bytesPerSec: Double) {
        val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else null
        val left = if (total > 0 && bytesPerSec > 1) ((total - done) / bytesPerSec).toLong() else null
        val text = listOfNotNull(
            if (total > 0) "${mb(done)} of ${mb(total)}" else if (done > 0) mb(done) else stage,
            if (bytesPerSec > 1) "${mb(bytesPerSec.toLong())}/s" else null,
            left?.let { if (it >= 60) "${it / 60} min left" else "${it}s left" },
        ).joinToString(" · ")
        val b = base(context, name, "$text\nFrom $server", pct?.let { "$it%" } ?: "…")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\nFrom $server"))
            .setContentIntent(open(context, 44))
            .addAction(0, "Cancel", button(context, "download.cancel:$key", 45 + (key.hashCode() and 0xFF)))
            .setProgress(100, pct ?: 0, pct == null)
        post(context, downloadId(key), b)
    }

    /** The download done: an ordinary notification to open the book (the live one goes). */
    fun downloaded(context: Context, key: String, name: String, uri: Uri, type: String) {
        cancel(context, downloadId(key))
        if (!allowed(context)) return
        channel(context)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle("Downloaded")
            .setContentText(name)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, 46 + (key.hashCode() and 0xFF), Intent.createChooser(view, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        runCatching { NotificationManagerCompat.from(context).notify(downloadId(key) + 1000, b.build()) }
    }

    private fun mb(bytes: Long): String =
        if (bytes >= 1_000_000) String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0) else "${(bytes / 1000).coerceAtLeast(1)} KB"

    // ---- 4. A sync you started ----

    /** A sync you started: which step it's on (on the chip, "Syncing"), the time it's taken. */
    fun sync(context: Context, step: String?, startedAt: Long) {
        val b = base(context, "Syncing your reading", step ?: "Starting…", "Syncing")
            .setContentIntent(open(context, 47))
            .setUsesChronometer(true).setShowWhen(true).setWhen(startedAt)
            .setProgress(0, 0, true)
        post(context, SYNC_ID, b)
    }
}

/** The Live Updates' buttons (Pause, Resume, End, Cancel), passed to whatever is running. */
class LiveUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val h = LiveUpdates.handlers[action]
        if (h != null) {
            // Back on the main thread, where the screen's state lives.
            android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { h() } }
        } else {
            // Nothing running answers it any more (the app was closed): the notification goes.
            when {
                action.startsWith("timer.") -> LiveUpdates.cancel(context, LiveUpdates.TIMER_ID)
                action.startsWith("download.cancel:") -> LiveUpdates.cancel(context, LiveUpdates.downloadId(action.removePrefix("download.cancel:")))
            }
        }
    }
}
