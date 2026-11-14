package io.github.prithirajn.readync.ui

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
import io.github.prithirajn.readync.data.Book
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.trackers.Anchors
import io.github.prithirajn.readync.trackers.Session
import io.github.prithirajn.readync.trackers.SpotifyTracker
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.CpShapes
import io.github.prithirajn.readync.ui.theme.CpType

private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * On a book's page, when it's matched to an audiobook (Audiobookshelf, Spotify): where you're
 * listening, Resume in Spotify, and "Not the right spot?" to say which track your reading place is at.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
        val chapterMap = remember(state.services, link) { Anchors.chapterMap(Session(state.service(t.id).credential), link.externalId) }
        var linking by remember(t.id, book.document) { mutableStateOf(false) }
        var linker by remember(t.id, book.document) { mutableStateOf<AppViewModel.ChapterLinker?>(null) }
        var linkError by remember(t.id, book.document) { mutableStateOf<String?>(null) }
        var editing by remember(t.id, book.document) { mutableStateOf<Int?>(null) }
        var chosen by remember(t.id, book.document) { mutableStateOf<Map<Int, Int>>(emptyMap()) }
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
                        chapterMap?.takeIf { it.pairs.isNotEmpty() }?.let { m ->
                            VSpace(4)
                            Quiet("Places go chapter by chapter: ${m.pairs.size} chapters matched" + (if (m.manual) " by you." else " by their titles."), color = c.faint)
                        }
                    }
                }
                VSpace(10)
                // Wraps on narrow phones.
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    if (tr != null && tr.tracks.size > 1) PillButton(if (linking) "Close" else "Match chapters", {
                        linking = !linking; editing = null; linkError = null
                        if (linking && linker == null) vm.audiobookChapters(book.document, t.id) { l, e ->
                            linker = l; linkError = e
                            // Start from how they're matched now.
                            if (l != null) chosen = l.map?.pairs.orEmpty().mapNotNull { p -> l.headings.indexOfFirst { it.spine == p.chapter }.takeIf { it >= 0 }?.let { p.track to it } }.toMap()
                        }
                    })
                }
                if (linking) {
                    VSpace(12)
                    val l = linker
                    val le = linkError
                    when {
                        le != null -> Quiet(le, color = c.faint)
                        l == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), color = c.faint, strokeWidth = 2.dp); HSpace(8); Quiet("Reading the book's chapters…")
                        }
                        else -> {
                            Quiet("Tap a track to say which chapter of the book it reads. Tracks not in the book (credits, an intro) can stay unmatched; places in between are shared out evenly.", color = c.textMid)
                            VSpace(8)
                            for ((i, track) in l.tracks.tracks.withIndex()) {
                                val h = chosen[i]?.let { l.headings.getOrNull(it) }
                                Box(
                                    Modifier.fillMaxWidth().clip(CpShapes.pill).background(c.divider.copy(alpha = if (editing == i) 0.7f else 0.35f))
                                        .bouncyClickable(CpShapes.pill, pressedScale = 0.98f) { editing = if (editing == i) null else i }
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                ) {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(track.name, style = CpType.body, color = c.text, modifier = Modifier.weight(1f), maxLines = 1)
                                            Quiet(clock(track.startMs), color = c.faint)
                                        }
                                        Quiet(h?.let { "\u2192 ${it.title.ifBlank { "Part ${it.spine + 1}" }} (${Math.round(it.share * 100)}%)" } ?: "Not matched", color = if (h != null) c.textMid else c.faint, maxLines = 1)
                                    }
                                }
                                if (editing == i) {
                                    VSpace(6)
                                    Column(Modifier.padding(start = 12.dp)) {
                                        PillButton("Not in the book", { chosen = chosen - i; editing = null })
                                        VSpace(6)
                                        for ((hi, heading) in l.headings.withIndex()) {
                                            Text(
                                                "${heading.title.ifBlank { "Part ${heading.spine + 1}" }}  ·  ${Math.round(heading.share * 100)}%",
                                                style = CpType.small, color = if (chosen[i] == hi) c.accent else c.text, maxLines = 1,
                                                modifier = Modifier.fillMaxWidth().clip(CpShapes.control).bouncyClickable(CpShapes.control, pressedScale = 0.98f) {
                                                    chosen = chosen + (i to hi); editing = null
                                                }.padding(horizontal = 8.dp, vertical = 8.dp),
                                            )
                                        }
                                    }
                                }
                                VSpace(6)
                            }
                            VSpace(6)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PillButton("Match automatically", { vm.setAudiobookChapters(book.document, t.id, l, null); linking = false; linker = null })
                                PillButton("Save", { vm.setAudiobookChapters(book.document, t.id, l, chosen); linking = false }, outlined = false)
                            }
                        }
                    }
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
