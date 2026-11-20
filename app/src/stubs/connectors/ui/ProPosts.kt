package io.github.prithirajn.readync.ui

import android.content.Context
import androidx.compose.runtime.Composable
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.data.Store

/** Stand-in for the full edition's own posting: nothing here. */
object ProPosts {
    const val available = false
    class Network(val id: String, val name: String, val hint: String)
    val networks: List<Network> = emptyList()
    fun connected(s: Snapshot, id: String): Boolean = false
    fun summary(s: Snapshot, id: String): String = ""
    fun tick(context: Context, store: Store) {}
    fun upcoming(context: Context, s: Snapshot, now: Long): List<UpcomingPosts.Upcoming> = emptyList()
    @Composable
    fun Page(vm: AppViewModel, state: Snapshot, id: String) {}
}
