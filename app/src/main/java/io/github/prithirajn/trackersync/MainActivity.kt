package io.github.prithirajn.trackersync

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import io.github.prithirajn.trackersync.sync.ShareLinks
import io.github.prithirajn.trackersync.ui.AppAction
import io.github.prithirajn.trackersync.ui.AppActions
import io.github.prithirajn.trackersync.ui.AppRoot
import io.github.prithirajn.trackersync.ui.theme.TrackerSyncTheme

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // For: a tracker needing you to sign in again, a failed sync, a book you finished.
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (savedInstanceState == null) handle(intent)
        setContent {
            TrackerSyncTheme {
                AppRoot()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // The icon's colour changes only once you've left the app (and not on a rotation).
        if (!isChangingConfigurations) io.github.prithirajn.trackersync.ui.IconSwitcher.apply(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Shortcuts, notifications and shared links all arrive here. */
    private fun handle(intent: Intent?) {
        val action = when (intent?.action) {
            ACTION_RATE -> intent.getStringExtra(EXTRA_DOCUMENT)?.let { AppAction.Rate(it) }
            ACTION_SERVICES -> AppAction.Services
            ACTION_ADD_BOOK -> AppAction.AddBook
            ACTION_SNAP -> AppAction.Snap
            ACTION_SYNC -> AppAction.Sync
            ACTION_RELEASES -> AppAction.Releases
            ACTION_SFF -> AppAction.Sff(intent.getStringExtra(EXTRA_STORY))
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                ShareLinks.query(text)?.let { AppAction.AddFromShare(it) } ?: AppAction.AddBook
            }
            else -> null
        }
        if (action != null) AppActions.pending.value = action
    }

    companion object {
        const val ACTION_RATE = "io.github.prithirajn.trackersync.RATE"
        const val ACTION_SERVICES = "io.github.prithirajn.trackersync.SERVICES"
        const val ACTION_ADD_BOOK = "io.github.prithirajn.trackersync.ADD_BOOK"
        const val ACTION_SNAP = "io.github.prithirajn.trackersync.SNAP_PAGE"
        const val ACTION_SYNC = "io.github.prithirajn.trackersync.SYNC"
        const val ACTION_RELEASES = "io.github.prithirajn.trackersync.RELEASES"
        const val EXTRA_DOCUMENT = "document"
        const val ACTION_SFF = "io.github.prithirajn.trackersync.SFF"
        const val EXTRA_STORY = "story"
    }
}
