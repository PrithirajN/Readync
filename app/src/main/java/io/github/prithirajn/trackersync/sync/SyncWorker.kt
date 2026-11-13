package io.github.prithirajn.trackersync.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.prithirajn.trackersync.MainActivity
import io.github.prithirajn.trackersync.R
import io.github.prithirajn.trackersync.TrackerSyncApp
import io.github.prithirajn.trackersync.data.SyncSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Runs one [SyncEngine] pass: on a schedule (how often, Wi-Fi only and quiet hours come from
 * the Sync settings), and on "Sync now". Afterwards it tells you what needs you: a tracker to
 * sign in to again, a sync that failed, a book you just finished.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as TrackerSyncApp
        val s = app.store.snapshot
        // Something to sync: a crosspoint-sync account, or trackers (physical books, Seekquel).
        if (s.account == null && s.services.values.none { it.linked }) return@withContext Result.success()
        // Scheduled runs rest during quiet hours; "Sync now" always runs.
        if (PERIODIC_TAG in tags && s.settings.isQuiet(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))) {
            return@withContext Result.success()
        }
        val result = SyncEngine(app.store).run()
        Notices.after(applicationContext, app.store.snapshot.settings, result, app.store)
        runCatching { Notices.priceDrops(applicationContext, app.store) }
        // New highlights out to Threads, when you've turned that on.
        runCatching { io.github.prithirajn.trackersync.ui.ThreadsAuto.run(applicationContext, app.store) }
        Result.success()
    }

    companion object {
        private const val PERIODIC = "tracker-sync-periodic"
        private const val NOW = "tracker-sync-now"
        private const val PERIODIC_TAG = "scheduled"

        private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        /**
         * Schedules background syncs with the current settings. [changed] replaces an existing
         * schedule (the settings were edited); otherwise one already running is kept.
         */
        fun schedule(context: Context, settings: SyncSettings = SyncSettings(), changed: Boolean = false) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(settings.intervalMinutes.toLong().coerceAtLeast(15), TimeUnit.MINUTES)
                .setConstraints(constraints(settings.wifiOnly))
                .addTag(PERIODIC_TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                if (changed) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Right away, on any connection: you asked for it. */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints(false)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        }
    }
}

/**
 * Was the Astral Probe's return. The probe is gone from the SFF tab: a wake-up still queued from
 * before does nothing (kept so WorkManager can still find the class it saved).
 */
/** A Threads check of its own, at the time the next waiting session is due (see ThreadsAuto). */
class ThreadsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as TrackerSyncApp
        runCatching { io.github.prithirajn.trackersync.ui.ThreadsAuto.run(applicationContext, app.store) }
        Result.success()
    }

    companion object {
        fun schedule(context: Context, delaySec: Long) {
            val req = OneTimeWorkRequestBuilder<ThreadsWorker>()
                .setInitialDelay(delaySec, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("threads-next", ExistingWorkPolicy.REPLACE, req)
        }
    }
}

class SffProbeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()

    companion object {
        private const val NAME = "sff-probe-return"
        fun schedule(context: Context, delaySec: Long) {
            val request = OneTimeWorkRequestBuilder<SffProbeWorker>()
                .setInitialDelay(delaySec.coerceAtLeast(0), TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

/** What the app tells you after a background sync. */
object Notices {
    private const val SFF = "sff-news"
    private const val SIGN_IN = "sign-in"
    private const val PROBLEMS = "problems"
    private const val FINISHED = "finished"
    private const val RELEASES = "releases"
    private const val PRICES = "prices"
    private const val POSITIONS = "positions"
    private const val COPIES = "book-copies"
    private const val POSTS = "social-posts"
    private const val SHARED = "shared-links"

    /** Opens the app; with [action] (and [document]) it goes straight to that thing. */
    fun openApp(context: Context, action: String? = null, document: String? = null, code: Int = 0): PendingIntent {
        val i = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        action?.let { i.action = it }
        document?.let { i.putExtra(MainActivity.EXTRA_DOCUMENT, it) }
        return PendingIntent.getActivity(context, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun openStory(context: Context, storyId: String?, code: Int): PendingIntent {
        val i = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .setAction(MainActivity.ACTION_SFF)
        storyId?.let { i.putExtra(MainActivity.EXTRA_STORY, it) }
        return PendingIntent.getActivity(context, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Big news about your own series and authors (three a day at most, never spoilers). */
    fun sffNews(context: Context, store: io.github.prithirajn.trackersync.data.Store) {
        if (!allowed(context)) return
        val now = System.currentTimeMillis() / 1000
        val news = SffChart.bigNews(store.snapshot, now)
        if (news.isEmpty()) return
        channel(context, SFF, "SFF news about your books")
        val nm = NotificationManagerCompat.from(context)
        for ((i, item) in news.withIndex()) {
            nm.notify(
                500 + (item.id.hashCode() and 0xFFFF),
                NotificationCompat.Builder(context, SFF)
                    .setSmallIcon(R.drawable.lucide_sparkles)
                    .setContentTitle(item.aiHeadline ?: item.title)
                    .setContentText(listOfNotNull(item.source.substringBefore(" ·"), item.matchedLibraryBook?.substringAfter(": ", "")?.takeIf { it.isNotBlank() }).joinToString(" · "))
                    .setContentIntent(openStory(context, item.id, 50 + i))
                    .setAutoCancel(true)
                    .build(),
            )
        }
        store.update { it.copy(sffSky = SffChart.markNotified(it.sffSky, news.map { n -> n.id }, now)) }
    }

    /** A watched book's price under what you'd pay, or well under what it was. */
    fun priceDrops(context: Context, store: io.github.prithirajn.trackersync.data.Store) {
        if (!allowed(context)) return
        val drops = io.github.prithirajn.trackersync.source.BookPrices.newDrops(store)
        if (drops.isEmpty()) return
        channel(context, PRICES, "Price drops")
        val nm = NotificationManagerCompat.from(context)
        for ((book, p) in drops.take(3)) {
            val shop = io.github.prithirajn.trackersync.source.BookPrices.shop(p.store)?.name ?: p.store
            val price = "₹" + java.text.NumberFormat.getIntegerInstance(java.util.Locale("en", "IN")).format(Math.round(p.price ?: 0.0))
            nm.notify(
                700 + (book.document.hashCode() and 0xFFFF),
                NotificationCompat.Builder(context, PRICES)
                    .setSmallIcon(R.drawable.lucide_bell)
                    .setContentTitle("${book.displayTitle} is $price")
                    .setContentText(listOfNotNull(shop, p.format, p.mrp?.let { m -> "was ₹${Math.round(m)}" }).joinToString(" · "))
                    .setContentIntent(if (book.document in store.snapshot.books) openApp(context, MainActivity.ACTION_OPEN_BOOK, book.document, 70) else openApp(context, MainActivity.ACTION_DEALS, code = 71))
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    private fun rupeesText(v: Double) = "₹" + java.text.NumberFormat.getIntegerInstance(java.util.Locale("en", "IN")).format(Math.round(v))

    /** The books found in a post you shared or pasted (or that none were). Tapping opens Catalogs › Posts. */
    fun postBooks(context: Context, t: io.github.prithirajn.trackersync.source.PostBooks.Trap) {
        if (!allowed(context)) return
        channel(context, SHARED, "Shared links")
        val where = "that ${t.post.platform} post".replace("that Web post", "that page")
        val title = if (t.books.isEmpty()) "No books found in $where"
            else "Found ${t.books.size} book${if (t.books.size == 1) "" else "s"} in $where"
        val text = if (t.books.isEmpty()) "Tap to see the words read on its picture: pick a title, or type one"
            else t.books.joinToString(", ") { b -> b.title + (b.author?.let { " by $it" } ?: "") }
        NotificationManagerCompat.from(context).notify(
            900 + (t.post.url.hashCode() and 0x3FF),
            NotificationCompat.Builder(context, SHARED)
                .setSmallIcon(R.drawable.lucide_bell)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text + if (t.books.isNotEmpty()) "\nAdd them to Want to Read or track their prices on Catalogs › Posts." else ""))
                .setContentIntent(openApp(context, MainActivity.ACTION_POSTS, code = 72))
                .setAutoCancel(true)
                .build(),
        )
    }

    /** A shop's page you shared or pasted, now tracked: its price now and where. Tapping opens Catalogs › Deals. */
    fun priceTracked(context: Context, store: io.github.prithirajn.trackersync.data.Store, r: io.github.prithirajn.trackersync.source.BookPrices.Tracked) {
        if (!allowed(context)) return
        channel(context, SHARED, "Shared links")
        val w = r.document?.let { store.snapshot.priceWatch[it] }
        val best = w?.best
        val now = best?.price?.let { p ->
            "Now ${rupeesText(p)} at " + (io.github.prithirajn.trackersync.source.BookPrices.shop(best.store)?.name ?: best.store) + (best.format?.let { " · $it" } ?: "")
        } ?: "No price read yet; looked at again with each sync"
        val after = w?.target?.let { "You'll get a notice when it's ${rupeesText(it)} or less." } ?: "You'll get a notice when it drops."
        NotificationManagerCompat.from(context).notify(
            950 + ((r.document ?: r.message).hashCode() and 0x3FF),
            NotificationCompat.Builder(context, SHARED)
                .setSmallIcon(R.drawable.lucide_bell)
                .setContentTitle(r.message)
                .setContentText(now)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$now\n$after"))
                .setContentIntent(openApp(context, MainActivity.ACTION_DEALS, code = 73))
                .setAutoCancel(true)
                .build(),
        )
    }

    /** A shared link that couldn't be read or tracked, and why. */
    fun sharedFailed(context: Context, title: String, why: String, deals: Boolean, id: String) {
        if (!allowed(context)) return
        channel(context, SHARED, "Shared links")
        NotificationManagerCompat.from(context).notify(
            (if (deals) 950 else 900) + (id.hashCode() and 0x3FF),
            NotificationCompat.Builder(context, SHARED)
                .setSmallIcon(R.drawable.lucide_bell)
                .setContentTitle(title)
                .setContentText(why)
                .setStyle(NotificationCompat.BigTextStyle().bigText(why))
                .setContentIntent(openApp(context, if (deals) MainActivity.ACTION_DEALS else MainActivity.ACTION_POSTS, code = if (deals) 73 else 72))
                .setAutoCancel(true)
                .build(),
        )
    }

    /** The app, for notices raised where no context is at hand (a tracker posting to Micro.blog). */
    @Volatile var app: Context? = null

    /**
     * Something went up on a social site in your name (Threads, Micro.blog): what, and for which
     * book, so nothing is posted without you knowing. Tapping it opens the book.
     */
    fun posted(service: String, what: String, document: String? = null) {
        val context = app ?: return
        if (!allowed(context)) return
        channel(context, POSTS, "Posted to your socials")
        NotificationManagerCompat.from(context).notify(
            900 + ((service + what).hashCode() and 0xFFFF),
            NotificationCompat.Builder(context, POSTS)
                .setSmallIcon(R.drawable.ic_stat_sync)
                .setContentTitle("Posted to $service")
                .setContentText(what)
                .setStyle(NotificationCompat.BigTextStyle().bigText(what))
                .setContentIntent(if (document != null) openApp(context, MainActivity.ACTION_OPEN_BOOK, document, 90) else openApp(context, code = 90))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** A sync kept a copy of a book you're reading for quotes (or couldn't find one). */
    fun bookCopy(context: Context, book: io.github.prithirajn.trackersync.data.Book, copy: io.github.prithirajn.trackersync.source.BookCopies.Copy?, why: String?) {
        if (!allowed(context)) return
        channel(context, COPIES, "Copies for quotes")
        NotificationManagerCompat.from(context).notify(
            800 + (book.document.hashCode() and 0xFFFF),
            NotificationCompat.Builder(context, COPIES)
                .setSmallIcon(R.drawable.lucide_book_open)
                .setContentTitle(if (copy != null) "Kept a copy of ${book.displayTitle}" else "No copy of ${book.displayTitle} yet")
                .setContentText(
                    if (copy != null) "From ${copy.server}, for the lines around your quotes"
                    else (why ?: "Not on your catalogs") + " · tried again in a few days",
                )
                .setContentIntent(openApp(context, MainActivity.ACTION_OPEN_BOOK, book.document, 80))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** The probe is back, with what it found. */
    fun probeBack(context: Context, store: io.github.prithirajn.trackersync.data.Store) {
        val sky = store.snapshot.sffSky
        val now = System.currentTimeMillis() / 1000
        val mark = "probe:${sky.briefingAt}"
        if (!sky.probeAlerts || !allowed(context) || sky.briefing.isEmpty() || now - sky.briefingAt > 3600 || mark in sky.notified) return
        store.update { it.copy(sffSky = it.sffSky.copy(notified = (it.sffSky.notified + mark).takeLast(200))) }
        val where = sky.briefingSector?.let { SffRadar.Sector.byId(it).title } ?: "deep space"
        val stories = sky.briefing.count { it.category != "DISCOVERY" }
        val book = sky.briefing.firstOrNull { it.category == "DISCOVERY" }
        channel(context, SFF, "SFF news about your books")
        NotificationManagerCompat.from(context).notify(
            499,
            NotificationCompat.Builder(context, SFF)
                .setSmallIcon(R.drawable.lucide_sparkles)
                .setContentTitle("Your probe is back from $where")
                .setContentText(listOfNotNull("$stories ${if (stories == 1) "story" else "stories"}", book?.let { "and a find: ${it.title}" }).joinToString(" "))
                .setContentIntent(openStory(context, null, 49))
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun channel(context: Context, id: String, name: String) {
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun after(context: Context, settings: SyncSettings, result: SyncEngine.Result, store: io.github.prithirajn.trackersync.data.Store) {
        if (!allowed(context)) return
        val nm = NotificationManagerCompat.from(context)
        // Said once per signed-out set of services (not on every background sync until you sign in).
        val signPrefs = context.getSharedPreferences("notices", Context.MODE_PRIVATE)
        val signedOut = result.needsAttention.sorted().joinToString(", ")
        if (signedOut.isEmpty()) signPrefs.edit().remove("lastSignIn").apply()
        if (result.needsAttention.isNotEmpty() && signPrefs.getString("lastSignIn", null) != signedOut) {
            signPrefs.edit().putString("lastSignIn", signedOut).apply()
            channel(context, SIGN_IN, "Sign-in needed")
            nm.notify(
                1,
                NotificationCompat.Builder(context, SIGN_IN)
                    .setSmallIcon(R.drawable.ic_stat_sync)
                    .setContentTitle("Sign in again")
                    .setContentText("${result.needsAttention.joinToString(", ")} stopped syncing until you sign in again.")
                    .setContentIntent(openApp(context, MainActivity.ACTION_SERVICES))
                    .setAutoCancel(true)
                    .build(),
            )
        }
        // A failing sync once per new problem, not every 15 minutes.
        val prefs = context.getSharedPreferences("notices", Context.MODE_PRIVATE)
        if (settings.notifyFailures && result.failed) {
            if (prefs.getString("lastFailure", null) != result.message) {
                prefs.edit().putString("lastFailure", result.message).apply()
                channel(context, PROBLEMS, "Sync problems")
                nm.notify(
                    2,
                    NotificationCompat.Builder(context, PROBLEMS)
                        .setSmallIcon(R.drawable.lucide_triangle_alert)
                        .setContentTitle("Sync had a problem")
                        .setContentText(result.message)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(result.message))
                        .setContentIntent(openApp(context, MainActivity.ACTION_SERVICES))
                        .setAutoCancel(true)
                        .build(),
                )
            }
        } else if (!result.failed) {
            prefs.edit().remove("lastFailure").apply()
        }
        // New & upcoming: what's out (and, if you asked, reminders before), a few at most, then one line for the rest.
        val news = Releases.toNotify(store.snapshot)
        if (news.isNotEmpty()) {
            channel(context, RELEASES, "New & upcoming books")
            for ((i, pair) in news.take(3).withIndex()) {
                val (r, what) = pair
                val day = r.date?.let { java.time.format.DateTimeFormatter.ofPattern("d MMM").format(java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault())) }
                val title = if (what == "out") when (r.kind) {
                    "chapter" -> "New chapter: ${r.title}"
                    "webnovel" -> r.title.replaceFirstChar { it.uppercase() }
                    else -> "Out now: ${r.title}"
                } else "Coming ${day ?: "soon"}: ${r.title}"
                nm.notify(
                    300 + (r.key.hashCode() and 0xFFFF),
                    NotificationCompat.Builder(context, RELEASES)
                        .setSmallIcon(R.drawable.lucide_book_open)
                        .setContentTitle(title)
                        .setContentText(listOfNotNull(r.series.takeIf { r.kind == "book" || r.kind == "volume" }?.let { "Next in $it" + (r.number?.let { n -> " (#$n)" } ?: "") }, r.author).joinToString(" \u00B7 "))
                        .setContentIntent(openApp(context, MainActivity.ACTION_RELEASES, code = 30 + i))
                        .setAutoCancel(true)
                        .build(),
                )
            }
            if (news.size > 3) {
                nm.notify(
                    299,
                    NotificationCompat.Builder(context, RELEASES)
                        .setSmallIcon(R.drawable.lucide_book_open)
                        .setContentTitle("${news.size - 3} more new and upcoming")
                        .setContentText(news.drop(3).take(4).joinToString(", ") { it.first.title })
                        .setContentIntent(openApp(context, MainActivity.ACTION_RELEASES, code = 29))
                        .setAutoCancel(true)
                        .build(),
                )
            }
            store.update { st -> st.copy(releases = st.releases + news.mapNotNull { (r, what) -> st.releases[r.key]?.let { r.key to it.copy(notified = what) } }) }
        }
        sffNews(context, store)
        probeBack(context, store)
        // Another source has a different position: that book's trackers wait for your choice, so say so (once per position).
        val asks = store.snapshot.progressAsks
        // A jump back is said once (not again with every page turned below it); one further along, once per position.
        fun askKey(d: String, a: io.github.prithirajn.trackersync.data.ProgressAsk, st: io.github.prithirajn.trackersync.data.Snapshot) =
            if (st.books[d]?.let { a.fraction < it.percentage } == true) "${a.source}|back" else "${a.source}|${a.percent}"
        if (settings.notifyPositions) {
            val fresh = asks.filter { (d, a) -> store.snapshot.asksNotified[d] != askKey(d, a, store.snapshot) && store.snapshot.books.containsKey(d) }
            if (fresh.isNotEmpty()) {
                channel(context, POSITIONS, "Different positions")
                for ((i, e) in fresh.entries.take(3).withIndex()) {
                    val (doc, a) = e
                    val b = store.snapshot.books[doc] ?: continue
                    val back = a.fraction < b.percentage
                    nm.notify(
                        300 + (doc.hashCode() and 0xFFFF),
                        NotificationCompat.Builder(context, POSITIONS)
                            .setSmallIcon(R.drawable.lucide_book_open)
                            .setContentTitle(if (back) "${Primary.name(a.source)} went back to ${a.percent}%" else "${Primary.name(a.source)} has a different position")
                            .setContentText(if (back) "${b.displayTitle}: kept at ${b.percent}%. Open the book to use the new position or keep this one."
                                else "${b.displayTitle}: ${a.percent}% there, ${b.percent}% here. Its trackers wait until you choose.")
                            .setContentIntent(openApp(context, MainActivity.ACTION_OPEN_BOOK, doc, code = 30 + i))
                            .setAutoCancel(true)
                            .build(),
                    )
                }
            }
        }
        // A finished book being read again: asked once on the book, said once here.
        val rereadAsks = store.snapshot.rereadAsks
        if (settings.notifyPositions) {
            val fresh = rereadAsks.filter { (d, _) -> "reread:$d" !in store.snapshot.asksNotified && store.snapshot.books.containsKey(d) }
            if (fresh.isNotEmpty()) {
                channel(context, POSITIONS, "Different positions")
                for ((i, e) in fresh.entries.take(3).withIndex()) {
                    val (doc, a) = e
                    val b = store.snapshot.books[doc] ?: continue
                    nm.notify(
                        300 + ("reread:$doc".hashCode() and 0xFFFF),
                        NotificationCompat.Builder(context, POSITIONS)
                            .setSmallIcon(R.drawable.lucide_book_open)
                            .setContentTitle("Reading it again?")
                            .setContentText("${b.displayTitle} is at ${a.percent}% on ${Primary.name(a.source)}. It stays finished until you say yes.")
                            .setContentIntent(openApp(context, MainActivity.ACTION_OPEN_BOOK, doc, code = 40 + i))
                            .setAutoCancel(true)
                            .build(),
                    )
                }
            }
        }
        store.update { st ->
            st.copy(asksNotified = st.progressAsks.mapValues { (d, a) -> askKey(d, a, st) } +
                st.rereadAsks.keys.associate { "reread:$it" to "1" })
        }
        if (settings.notifyFinished && result.finished.isNotEmpty()) {
            channel(context, FINISHED, "Finished books")
            for ((i, doc) in result.finished.take(3).withIndex()) {
                val title = store.snapshot.books[doc]?.displayTitle ?: continue
                nm.notify(
                    100 + (doc.hashCode() and 0xFFFF),
                    NotificationCompat.Builder(context, FINISHED)
                        .setSmallIcon(R.drawable.lucide_book_open)
                        .setContentTitle("You finished $title")
                        .setContentText("Rate it, and your rating and review go to your trackers.")
                        .setContentIntent(openApp(context, MainActivity.ACTION_RATE, doc, code = 10 + i))
                        .setAutoCancel(true)
                        .build(),
                )
            }
        }
    }
}
