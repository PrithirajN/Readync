package io.github.prithirajn.readync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.prithirajn.readync.R
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.sync.Highlights
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.CpShapes
import io.github.prithirajn.readync.ui.theme.CpType

/**
 * Highlights, as a tab of their own: those waiting to be checked before they go out (or today's
 * passage), today's review, what your social networks post next, then every passage, searchable,
 * by book. Tap a passage to edit it, the arrow to share it as a picture, a book to open it.
 */
@Composable
fun HighlightsScreen(vm: AppViewModel, state: Snapshot) {
    val c = Cp.colors
    val context = LocalContext.current
    val all = rememberAllHighlights(state)
    var query by rememberSaveable { mutableStateOf("") }
    var bookFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Highlights.Item?>(null) }
    var sharing by remember { mutableStateOf<Highlights.Item?>(null) }
    var photoFrom by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var reviewing by remember { mutableStateOf(false) }
    var reviewRound by remember { mutableStateOf(0) }
    val toCheck = remember(state.checkHighlights, state.clipChecked, state.sentClippings, state.cpClips, state.paperClips, state.kindleClips, state.koClips, state.clipEdits) {
        val ids = io.github.prithirajn.readync.sync.SpoilerCheck.waiting(state)
        if (ids.isEmpty()) emptyList() else Highlights.all(state).associateBy { it.clip.id }.let { m -> ids.mapNotNull { m[it] } }
    }
    val books = remember(all) { highlightBooks(all) }
    val shown = remember(all, bookFilter, query) { Highlights.search(state, all.filter { bookFilter == null || it.clip.document == bookFilter }, query) }
    val reviewLeft = remember(state, reviewRound) { runCatching { DailyReview.left(context.applicationContext, state) }.getOrDefault(0) }
    // What your networks post next (worked out off the main thread).
    var nextPosts by remember { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) }
    LaunchedEffect(state.threadsPostLog, state.threadsBooks, state.extras, state.books.size, state.threadsToken) {
        nextPosts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { UpcomingPosts.next(context.applicationContext, state).map { Triple(UpcomingPosts.whenText(it), it.where, it.what) } }.getOrDefault(emptyList())
        }
    }
    val openBook: (String) -> Unit = { AppActions.pending.value = AppAction.OpenBook(it) }
    val listState = rememberLazyListState()

    // Pull past the top to sync now: new highlights come in with your books.
    val pullSync = rememberPullToAct { if (!state.running) vm.syncNow() }
    PullFrame(pullSync, state.running, "Pull to bring in new highlights", "Let go to sync", "Syncing…") { pm ->
        LazyColumn(pm.fillMaxSize().edgeBump().fadeTopEdge(listState.canScrollBackward), state = listState, contentPadding = PaddingValues(bottom = 32.dp + TabBarSpace)) {
            item(key = "head") {
                TabHeader(if (all.isEmpty()) "every passage you mark" else "${all.size} passages from ${books.size} books", "Highlights") {
                    if (all.isNotEmpty()) IconCircleButton(R.drawable.lucide_upload, "Export all highlights", { exportHighlights(context, state, all) }, tint = c.textMid)
                }
            }
            // Waiting to be checked, else today's passage.
            if (all.isNotEmpty() && query.isBlank() && bookFilter == null) {
                item(key = "daily") {
                    DailyHighlightCard(
                        state, all,
                        onOpenBook = openBook,
                        onOpen = { editing = it },
                        onShare = { sharing = it },
                        onSeeAll = { },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
                        toCheck = toCheck,
                        onCheck = { checking = true },
                        showAll = false,
                    )
                }
                // Today's review and what posts next, side by side as two quiet rows.
                item(key = "extras") {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                        ExtraRow(R.drawable.lucide_refresh_cw, "Daily review", if (reviewLeft > 0) "$reviewLeft passages to look back on today" else "Done for today", signal = reviewLeft > 0) { reviewing = true }
                        if (nextPosts.isNotEmpty()) {
                            Spacer(Modifier.size(8.dp))
                            ExtraRow(R.drawable.lucide_calendar_days, "Going out next", nextPosts.take(2).joinToString("  ·  ") { (w, where, _) -> "$where $w" }, signal = false) {
                                AppActions.pending.value = AppAction.ServicePage("share")
                            }
                        }
                    }
                }
            }
            if (all.isNotEmpty()) {
                item(key = "search") {
                    Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
                        val clear: (@Composable () -> Unit)? = if (query.isNotEmpty()) { { IconCircleButton(R.drawable.lucide_x, "Clear", { query = "" }) } } else null
                        CpTextField(query, { query = it }, "Search highlights", placeholder = "A word, a note, a book or an author", trailing = clear)
                    }
                }
                if (query.isNotBlank()) item(key = "count") {
                    Quiet("${shown.size} match${if (shown.size == 1) "" else "es"}", Modifier.padding(start = 16.dp, top = 8.dp), color = c.accentText)
                }
            }
            highlightShelf(
                state, shown, books, all.size, bookFilter, query,
                onFilter = { bookFilter = it },
                onOpenBook = openBook,
                onOpen = { editing = it },
                onShare = { sharing = it },
            )
        }
    }

    if (checking) HighlightCheckSheet(vm, state) { checking = false }
    if (reviewing) DailyReviewSheet(vm, state) { reviewing = false; reviewRound++ }
    editing?.let { e -> EditHighlightSheet(vm, state, e) { editing = null } }
    sharing?.let { h ->
        QuoteShareSheet(
            h.clip, state.books[h.clip.document], others = BookShot.othersFor(state, h.clip), pageText = state.clipPages[h.clip.id],
            onPageText = { vm.setClipPage(h.clip.id, it) },
            onFromPhoto = state.books[h.clip.document]?.let { b -> { sharing = null; photoFrom = b.document } },
            sectors = BookShot.sectorsFor(state, state.books[h.clip.document]),
        ) { sharing = null }
    }
    photoFrom?.let { doc -> state.books[doc]?.let { PaperHighlightSheet(vm, state, it) { photoFrom = null } } }
}

/** A quiet row under today's passage: an icon, a name, a line, and a dot when it wants you. */
@Composable
private fun ExtraRow(icon: Int, title: String, line: String, signal: Boolean, onClick: () -> Unit) {
    val c = Cp.colors
    Row(
        Modifier.fillMaxWidth().clip(CpShapes.control).background(c.divider)
            .bouncyClickable(CpShapes.control, pressedScale = 0.98f, role = Role.Button, onClickLabel = title, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LucideIcon(icon, Modifier.size(16.dp), if (signal) c.accentText else c.quiet)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = CpType.label, color = c.text)
            Text(line, style = CpType.small, color = c.quiet, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (signal) Box(Modifier.size(8.dp).clip(CpShapes.pill).background(c.accent))
        Spacer(Modifier.width(6.dp))
        LucideIcon(R.drawable.lucide_chevron_right, Modifier.size(16.dp), c.faint)
    }
}
