package io.github.prithirajn.readync

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import io.github.prithirajn.readync.sync.ShareLinks
import io.github.prithirajn.readync.ui.AppAction
import io.github.prithirajn.readync.ui.AppActions
import io.github.prithirajn.readync.ui.AppRoot
import io.github.prithirajn.readync.ui.theme.TrackerSyncTheme

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // For: a tracker needing you to sign in again, a failed sync, a book you finished.
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (savedInstanceState == null) handle(intent)
        // The on-device model loaded ahead (only with the app open, on phones with the memory for it).
        if (savedInstanceState == null) io.github.prithirajn.readync.ai.LocalModel.warm(applicationContext)
        setContent {
            TrackerSyncTheme {
                io.github.prithirajn.readync.ui.ProvideAppInsets {
                    AppRoot()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The recent-apps card wears the same icon colour as the app (not the one it was opened from).
        io.github.prithirajn.readync.ui.IconSwitcher.applyToTask(this)
    }

    override fun onStop() {
        super.onStop()
        // The icon's colour changes only once you've left the app (and not on a rotation).
        if (!isChangingConfigurations) io.github.prithirajn.readync.ui.IconSwitcher.apply(this)
        // Leaving the app: the last changes written out (off the screen's thread).
        val app = application as ReadyncApp
        Thread({ app.store.flush() }, "store-flush").start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** The pictures or videos shared in [intent]. */
    @Suppress("DEPRECATION")
    private fun media(intent: Intent): List<android.net.Uri>? {
        val list = if (intent.action == Intent.ACTION_SEND_MULTIPLE) intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            else listOfNotNull(intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
        return list?.takeIf { it.isNotEmpty() }?.take(20)
    }

    /** Said straight away, over whatever is on screen, so a share is never silent. */
    private fun said(text: String) {
        runCatching { android.widget.Toast.makeText(this, text.replace("  ", " "), android.widget.Toast.LENGTH_LONG).show() }
    }

    /** Shortcuts, notifications and shared links all arrive here. */
    private fun handle(intent: Intent?) {
        val action = when (intent?.action) {
            ACTION_RATE -> intent.getStringExtra(EXTRA_DOCUMENT)?.let { AppAction.Rate(it) }
            ACTION_OPEN_BOOK -> intent.getStringExtra(EXTRA_DOCUMENT)?.let { AppAction.OpenBook(it) }
            ACTION_SERVICES -> AppAction.Services
            ACTION_ADD_BOOK -> AppAction.AddBook
            ACTION_SNAP -> AppAction.Snap
            ACTION_SYNC -> AppAction.Sync
            ACTION_RELEASES -> AppAction.Releases
            ACTION_POSTS -> AppAction.Posts
            ACTION_DEALS -> AppAction.Deals
            ACTION_SFF -> AppAction.Sff(intent.getStringExtra(EXTRA_STORY))
            // Screenshots or a screen recording of a book post: its books found on Catalogs › Posts.
            Intent.ACTION_SEND_MULTIPLE -> media(intent)?.let { io.github.prithirajn.readync.ui.PostShare.media.value = it; said("Looking for books in those pictures…"); AppAction.Posts }
            Intent.ACTION_SEND -> if (intent.type?.let { it.startsWith("image/") || it.startsWith("video/") } == true)
                media(intent)?.let { io.github.prithirajn.readync.ui.PostShare.media.value = it; AppAction.Posts }
            // The shared words (some apps send them only as a CharSequence, or only in the clip data).
            else (intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                ?: runCatching { intent.clipData?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull())?.let { text ->
                // A TikTok, Instagram or YouTube post: its books found on Catalogs › Posts.
                val post = io.github.prithirajn.readync.source.PostBooks.linkIn(text)
                    ?.takeIf { io.github.prithirajn.readync.source.PostBooks.platformOf(it) != null }
                // A bookshop's page (Amazon.in, Flipkart, Bookswagon…): its price tracked, in the background.
                val shop = io.github.prithirajn.readync.source.PostBooks.linkIn(text)
                    ?.takeIf { io.github.prithirajn.readync.source.BookPrices.trackable(it) != null }
                when {
                    post != null -> {
                        io.github.prithirajn.readync.sync.ShareJobs.findPost(this, post)
                        said("Looking for books in that ${io.github.prithirajn.readync.source.PostBooks.platformOf(post) ?: ""} post… you'll get a notification")
                        io.github.prithirajn.readync.ui.PostShare.pending.value = post
                        AppAction.Posts
                    }
                    shop != null -> {
                        io.github.prithirajn.readync.sync.ShareJobs.trackPrice(this, shop)
                        said("Tracking that book's price… you'll get a notification")
                        AppAction.Deals
                    }
                    else -> ShareLinks.query(text)?.let { AppAction.AddFromShare(it) } ?: AppAction.AddBook
                }
            }
            else -> null
        }
        if (action != null) AppActions.pending.value = action
    }

    companion object {
        const val ACTION_RATE = "io.github.prithirajn.trackersync.RATE"
        const val ACTION_OPEN_BOOK = "io.github.prithirajn.trackersync.OPEN_BOOK"
        const val ACTION_SERVICES = "io.github.prithirajn.trackersync.SERVICES"
        const val ACTION_ADD_BOOK = "io.github.prithirajn.trackersync.ADD_BOOK"
        const val ACTION_SNAP = "io.github.prithirajn.trackersync.SNAP_PAGE"
        const val ACTION_SYNC = "io.github.prithirajn.trackersync.SYNC"
        const val ACTION_RELEASES = "io.github.prithirajn.trackersync.RELEASES"
        const val ACTION_POSTS = "io.github.prithirajn.trackersync.POSTS"
        const val ACTION_DEALS = "io.github.prithirajn.trackersync.DEALS"
        const val EXTRA_DOCUMENT = "document"
        const val ACTION_SFF = "io.github.prithirajn.trackersync.SFF"
        const val EXTRA_STORY = "story"
    }
}
