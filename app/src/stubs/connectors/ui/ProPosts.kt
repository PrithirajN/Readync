package io.github.prithirajn.readync.ui

import android.content.Context
import androidx.compose.runtime.Composable
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.data.Store

/** Stand-in for the full edition's own posting: nothing here. */
object ProPosts {
    const val available = false
    fun on(s: Snapshot): List<String> = emptyList()
    val offered: List<String> = emptyList()
    fun tick(context: Context, store: Store) {}
    @Composable
    fun Settings(vm: AppViewModel, state: Snapshot) {}
}
