package io.github.prithirajn.trackersync.widget

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.prithirajn.readync.ReadyncApp
import io.github.prithirajn.readync.sync.SyncWorker
import java.text.DateFormat
import java.util.Date

/** Quick Settings tile: tap to sync now; it shows when the last sync was. */
class SyncTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        show(syncing = false)
    }

    override fun onClick() {
        super.onClick()
        SyncWorker.runNow(applicationContext)
        show(syncing = true)
    }

    private fun show(syncing: Boolean) {
        val tile = qsTile ?: return
        val s = (application as ReadyncApp).store.snapshot
        val busy = syncing || s.running
        tile.state = if (busy) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = io.github.prithirajn.readync.Edition.name
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when {
                busy -> "Syncing…"
                s.lastRun > 0 -> "Synced " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.lastRun))
                else -> "Tap to sync"
            }
        }
        tile.updateTile()
    }
}
