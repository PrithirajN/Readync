package io.github.prithirajn.trackersync.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.trackers.Anchors
import io.github.prithirajn.trackersync.trackers.Session
import io.github.prithirajn.trackersync.trackers.SpotifyTracker
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import io.github.prithirajn.trackersync.ui.theme.CpType

private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * On a book's page, when it's matched to an audiobook (Audiobookshelf, Spotify): where you're
 * listening, Resume in Spotify, and "Not the right spot?" to say which track your reading place is at.
 */
@Composable
fun AudiobookCard(vm: AppViewModel, state: Snapshot, book: Book) {
    val links = remember(state.links, state.services, book.document) { vm.audiobookLinks(state, book.document) }
    if (links.isEmpty()) return
    val c = Cp.colors
    val context = LocalContext.current
    for ((t, link) in links) {
        var tracks by remember(t.id, book.document) { mutableStateOf<Anchors.Tracks?>(null) }
        var loading by remember(t.id, book.document) { mutableStateOf(true) }
        var error by remember(t.id, book.document) { mutableStateOf<String?>(null) }
        var picking by remember(t.id, book.document) { mutableStateOf(false) }
        var resuming by remember { mutableStateOf(false) }
        var said by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(t.id, book.document) {
            vm.audiobookTracks(book.document, t.id) { r, e -> tracks = r; error = e; loading = false }
        }
        val anchored = remember(state.services, link) { Anchors.of(Session(state.service(t.id).credential), link) != null }
        VSpace(16)
        CpCard {
            Column(Modifier.padding(16.dp)) {
                Text("Audiobook on ${t.name}", style = CpType.bodyStrong, color = c.text)
                val tr = tracks
                when {
                    loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), color = c.faint, strokeWidth = 2.dp); HSpace(8); Quiet("Looking where you're listening…")
                    }
                    tr == null -> Quiet(error ?: "${t.name} has no chapters for it.", color = c.faint)
                    else -> {
                        val pos = tr.positionMs
                        val at = pos?.let { p -> tr.tracks.indexOfLast { it.startMs <= p }.coerceAtLeast(0) }
                        Quiet(
                            if (pos == null || at == null) "Not listened to yet."
                            else "Listening: ${tr.tracks[at].name}, ${clock(pos - tr.tracks[at].startMs)} in (${Math.round(pos * 100.0 / tr.totalMs.coerceAtLeast(1))}% of ${clock(tr.totalMs)})." +
                                if (anchored) " Matched to your reading by the track you picked." else "",
                            color = c.faint,
                        )
                    }
                }
                VSpace(10)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (t.id == SpotifyTracker.id) {
                        PillButton(if (resuming) "Starting…" else "Resume in Spotify", {
                            if (!resuming) {
                                resuming = true; said = null
                                vm.resumeSpotify(book.document, open = { app, web ->
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(app)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                        .onFailure { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                                }) { e -> resuming = false; said = e ?: "Playing in Spotify from your place." }
                            }
                        }, outlined = false)
                    }
                    if (tr != null && tr.tracks.isNotEmpty()) PillButton("Not the right spot?", { picking = !picking })
                }
                said?.let { VSpace(8); Quiet(it, color = c.faint) }
                if (picking && tr != null) {
                    VSpace(12)
                    Quiet("You're ${book.percent}% into the book. Which track starts where you're reading now?", color = c.textMid)
                    VSpace(8)
                    for ((i, track) in tr.tracks.withIndex()) {
                        Box(
                            Modifier.fillMaxWidth().clip(CpShapes.pill).background(c.divider.copy(alpha = 0.35f))
                                .bouncyClickable(CpShapes.pill, pressedScale = 0.98f) {
                                    vm.calibrateAudiobook(book.document, t.id, tr, i); picking = false
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(track.name, style = CpType.body, color = c.text, modifier = Modifier.weight(1f), maxLines = 1)
                                Quiet(clock(track.startMs), color = c.faint)
                            }
                        }
                        VSpace(6)
                    }
                    if (anchored) {
                        VSpace(4)
                        PillButton("Go back to matching by length", { vm.calibrateAudiobook(book.document, t.id, tr, null); picking = false })
                    }
                }
            }
        }
    }
}
