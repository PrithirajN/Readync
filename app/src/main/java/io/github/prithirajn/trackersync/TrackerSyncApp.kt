package io.github.prithirajn.trackersync

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import io.github.prithirajn.trackersync.data.KeystoreSecrets
import io.github.prithirajn.trackersync.data.LinkSource
import io.github.prithirajn.trackersync.data.Store
import io.github.prithirajn.trackersync.net.Http
import io.github.prithirajn.trackersync.sync.SyncEngine
import io.github.prithirajn.trackersync.sync.SyncWorker
import io.github.prithirajn.trackersync.trackers.HighlightSupport
import io.github.prithirajn.trackersync.trackers.Trackers
import io.github.prithirajn.trackersync.widget.SyncWidget
import java.io.File
import io.github.prithirajn.trackersync.ui.BookShot as BS

class TrackerSyncApp : Application() {
    lateinit var store: Store
        private set

    override fun onCreate() {
        super.onCreate()
        // Look like the system WebView, which is what signed in to StoryGraph and Goodreads.
        Http.defaultUserAgent = runCatching { WebSettings.getDefaultUserAgent(this) }.getOrDefault(Http.defaultUserAgent)
        store = Store(File(filesDir, "tracker-sync.json"), KeystoreSecrets())
        // The on-device model, when one is set up (Services → On-device AI); loaded on first use.
        io.github.prithirajn.trackersync.ai.Ai.provider = { io.github.prithirajn.trackersync.ai.LocalModel.get(this) }
        // Web novel sites behind a bot check are read through an unseen browser instead.
        io.github.prithirajn.trackersync.ui.PageBrowser.install(this)
        // Fable's "Book photo" quote style: the share sheet's book photo, square, in your saved look.
        io.github.prithirajn.trackersync.trackers.FableTracker.pagePicture = { clip, title, author ->
            val book = store.snapshot.books[clip.document]
            BS.png(
                this,
                BS.Content(
                    text = clip.text.trim(), author = author, title = title, chapter = clip.chapter,
                    page = clip.pageIn(book?.pageCount), others = BS.othersFor(store.snapshot, clip),
                ).let { c ->
                    // The real page around it, when you've photographed it.
                    val found = store.snapshot.clipPages[clip.id]?.let { io.github.prithirajn.trackersync.sync.PageContext.find(it, clip.text) }
                    if (found != null) c.copy(before = found.before, after = found.after) else c
                },
                BS.saved(this).copy(format = BS.Format.SQUARE),
                mark = BS.markOn(this),
            )
        }
        // Mihon's automatic backups, from the folder you chose.
        io.github.prithirajn.trackersync.ui.MihonFolder.install(this)
        io.github.prithirajn.trackersync.ui.KoSidecarFolder.install(this)
        fixLinksFromV010()
        resendFromV011()
        highlightsFromV012()
        // The worker checks whether there's anything to sync; schedule it with the saved settings.
        SyncWorker.schedule(this, store.snapshot.settings)
        watchForWidget()
    }

    /**
     * Redraws the home-screen widget when the app's state changes. A sync changes it many
     * times in a row, so redraws wait for a quiet half second.
     */
    private fun watchForWidget() {
        val main = Handler(Looper.getMainLooper())
        val redraw = Runnable { io.github.prithirajn.trackersync.widget.Widgets.refreshAll(this) }
        store.addListener {
            main.removeCallbacks(redraw)
            main.postDelayed(redraw, 500)
        }
    }

    /**
     * 0.1.0 auto-matched Goodreads books to the first edition search returned (an audiobook,
     * a 0-rating stray) and rounded Pagebound pages down. Once, on upgrade: re-match
     * Goodreads' automatic links and resend Pagebound progress. Manual links stay as they are.
     */
    private fun fixLinksFromV010() {
        val prefs = getSharedPreferences("migrations", MODE_PRIVATE)
        if (prefs.getBoolean("v011-rematch", false)) return
        val links = store.snapshot.links.values
        val goodreads = links.filter { it.service == "goodreads" && it.source == LinkSource.AUTO }.map { it.document }
        val pagebound = links.filter { it.service == "pagebound" && it.externalId != null }.map { it.document }
        if (goodreads.isNotEmpty() || pagebound.isNotEmpty()) {
            store.update { s ->
                s.copy(links = s.links
                    .filterValues { !(it.service == "goodreads" && it.source == LinkSource.AUTO) }
                    .mapValues { (_, l) -> if (l.service == "pagebound") l.copy(lastPercent = null) else l })
            }
            if (goodreads.isNotEmpty()) SyncEngine.queue(store, "goodreads", goodreads)
            if (pagebound.isNotEmpty()) SyncEngine.queue(store, "pagebound", pagebound)
        }
        prefs.edit().putBoolean("v011-rematch", true).apply()
    }

    /**
     * Up to 0.1.1, progress older than a day was never sent to Fable and Goodreads, and a
     * status change recorded the percentage as sent even when it wasn't. Once, on upgrade:
     * forget what was recorded as sent and queue every linked book again.
     */
    private fun resendFromV011() {
        val prefs = getSharedPreferences("migrations", MODE_PRIVATE)
        if (prefs.getBoolean("v012-resend", false)) return
        val linked = store.snapshot.links.values.filter { it.externalId != null && it.source != LinkSource.NONE }
        if (linked.isNotEmpty()) {
            store.update { s -> s.copy(links = s.links.mapValues { (_, l) -> l.copy(lastPercent = null) }) }
            for ((service, links) in linked.groupBy { it.service }) {
                SyncEngine.queue(store, service, links.map { it.document })
            }
        }
        prefs.edit().putBoolean("v012-resend", true).apply()
    }

    /** 0.2.0 adds highlights: switch them on for an already-linked StoryGraph (private journal). */
    private fun highlightsFromV012() {
        val prefs = getSharedPreferences("migrations", MODE_PRIVATE)
        if (prefs.getBoolean("v020-highlights", false)) return
        val since = System.currentTimeMillis() / 1000 - SyncEngine.RECENT_DAYS * SyncEngine.DAY
        for (t in Trackers.all) {
            if (t.highlights == HighlightSupport.PRIVATE_JOURNAL && store.snapshot.service(t.id).linked) {
                store.updateService(t.id) { it.copy(highlights = true, highlightsSince = since) }
            }
        }
        prefs.edit().putBoolean("v020-highlights", true).apply()
    }
}
