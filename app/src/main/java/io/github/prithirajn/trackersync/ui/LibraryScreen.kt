package io.github.prithirajn.trackersync.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.border
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import io.github.prithirajn.trackersync.data.ReadStatus
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.collectAsState
import io.github.prithirajn.trackersync.sync.FinishFlow
import io.github.prithirajn.trackersync.sync.ManualBooks
import io.github.prithirajn.trackersync.sync.SyncEngine
import io.github.prithirajn.trackersync.sync.Shelves
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.unit.dp
import io.github.prithirajn.trackersync.R
import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.LinkSource
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.sync.Matcher
import io.github.prithirajn.trackersync.trackers.RemoteBook
import io.github.prithirajn.trackersync.trackers.Tracker
import io.github.prithirajn.trackersync.trackers.Trackers
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import io.github.prithirajn.trackersync.ui.theme.CpType

fun ago(millis: Long): String {
    if (millis <= 0) return "never"
    val s = (System.currentTimeMillis() - millis) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m ago"
        s < 86_400 -> "${s / 3600}h ago"
        else -> "${s / 86_400}d ago"
    }
}

/** The companion app's handwritten hello above the title. */
private fun greeting(): String {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when {
        h < 5 -> "Up late?"
        h < 12 -> "Good morning"
        h < 17 -> "Good afternoon"
        else -> "Good evening"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: AppViewModel, state: Snapshot, home: Int = 0) {
    // Highlights waiting to be checked before sending: shown in the Highlight of the day's place.
    val toCheck = remember(state.checkHighlights, state.clipChecked, state.sentClippings, state.cpClips, state.paperClips, state.kindleClips, state.koClips, state.clipEdits) {
        val ids = io.github.prithirajn.trackersync.sync.SpoilerCheck.waiting(state)
        if (ids.isEmpty()) emptyList() else io.github.prithirajn.trackersync.sync.Highlights.all(state).associateBy { it.clip.id }.let { m -> ids.mapNotNull { m[it] } }
    }
    var checkingHighlights by remember { mutableStateOf(false) }
    if (checkingHighlights) HighlightCheckSheet(vm, state) { checkingHighlights = false }
    var open by remember { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(ReadStatus.READING) }
    var adding by remember { mutableStateOf(false) }
    var loggingManga by remember { mutableStateOf(false) }
    var addQuery by remember { mutableStateOf("") }
    var rating by remember { mutableStateOf<String?>(null) }
    var autoSnap by remember { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(Shelves.Sort.RECENT) }
    var asking by remember { mutableStateOf(false) }
    // The Highlights shelf, beside the reading shelves: every passage, searchable and shareable.
    var clipsShelf by rememberSaveable { mutableStateOf(false) }
    var clipBook by rememberSaveable { mutableStateOf<String?>(null) }
    var editingClip by remember { mutableStateOf<io.github.prithirajn.trackersync.sync.Highlights.Item?>(null) }
    var sharingClip by remember { mutableStateOf<io.github.prithirajn.trackersync.sync.Highlights.Item?>(null) }
    var photoFrom by remember { mutableStateOf<String?>(null) }
    val allClips = rememberAllHighlights(state)
    val clipBooks = remember(allClips) { highlightBooks(allClips) }
    val shownClips = remember(allClips, clipBook, query) {
        io.github.prithirajn.trackersync.sync.Highlights.search(state, allClips.filter { clipBook == null || it.clip.document == clipBook }, query)
    }
    val listContext = androidx.compose.ui.platform.LocalContext.current
    // Checked when the Library opens and after a model is added (state changes redraw this).
    val aiReady = remember(state.settings) { vm.aiReady() }
    val all = state.books.values
    val counts = remember(state.books) { Shelves.counts(all) }
    val pages = remember(state.books) { Shelves.pagesRead(all) }
    // Today's pages, live: today's starting point, or the last count when today hasn't one yet.
    val today = remember(pages, state.books, state.pagesDay, state.pagesStartF, state.pagesCleared) { Shelves.todayPages(state) }
    val shown = remember(state.books, tab, sort, query) { Shelves.shown(all, tab, sort, query) }
    val dupe = remember(state.books) { Shelves.firstDuplicate(all) }
    val toRate = remember(state.books) { FinishFlow.toRate(all).firstOrNull() }
    // Paused trackers (switched off under Services) aren't shown on books; they get nothing.
    val anyLinked = Trackers.all.any { state.service(it.id).linked }
    val linked = Trackers.all.filter { state.service(it.id).let { s -> s.linked && s.enabled } }
    val c = Cp.colors

    // From outside the screen: a notification ("rate it"), a shortcut, a shared link.
    val pending by AppActions.pending.collectAsState()
    LaunchedEffect(pending) {
        when (val a = pending) {
            is AppAction.Rate -> { open = null; rating = a.document; AppActions.pending.value = null }
            is AppAction.AddBook -> { addQuery = ""; adding = true; AppActions.pending.value = null }
            is AppAction.OpenBook -> { open = a.document; AppActions.pending.value = null }
            is AppAction.AddFromShare -> { addQuery = a.query; adding = true; AppActions.pending.value = null }
            is AppAction.Snap -> {
                // The physical book you were last on; none yet means adding one first.
                val latest = all.filter { ManualBooks.isManual(it) && it.status != ReadStatus.FINISHED }.maxByOrNull { it.timestamp }
                if (latest != null) { tab = ReadStatus.READING; autoSnap = true; open = latest.document }
                else { addQuery = ""; adding = true; vm.notify("Add your physical book first", "Then Snap page goes straight to its camera.") }
                AppActions.pending.value = null
            }
            else -> Unit
        }
    }

    // Pull down to sync: the app's ribbon follows your finger (its arrows start moving once you've
    // pulled far enough), then goes. While the sync runs, the ribbon at the top moves instead, so
    // there's no second spinner over the page.
    val pull = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = false,
        onRefresh = { vm.syncNow() },
        state = pull,
        modifier = Modifier.fillMaxSize(),
        indicator = {
            val f = pull.distanceFraction
            if (f > 0f) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .graphicsLayer {
                            translationY = (f.coerceAtMost(1.4f) * 72.dp.toPx()) - 24.dp.toPx()
                            alpha = f.coerceIn(0f, 1f)
                            val sc = 0.6f + 0.4f * f.coerceIn(0f, 1f)
                            scaleX = sc; scaleY = sc
                        }
                        .size(44.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(c.surface),
                    contentAlignment = Alignment.Center,
                ) { SyncRibbon(22.dp, c.accentText, moving = f >= 1f) }
            }
        },
    ) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // The Library button in the bottom bar: back to the Reading shelf at the top, from wherever you were.
    LaunchedEffect(home) {
        if (home == 0) return@LaunchedEffect
        open = null; clipsShelf = false; clipBook = null
        searching = false; query = ""
        tab = ReadStatus.READING
        listState.animateScrollToItem(0)
    }
    LazyColumn(Modifier.fillMaxSize().fadeTopEdge(listState.canScrollBackward), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item {
            Column(PagePadding.fillMaxWidth()) {
                // The greeting with the page's keys beside it, so the title has the full width below.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Eyebrow(greeting()) }
                    // The keys sit together on a little tray, spaced so each reads as its own key.
                    Row(
                        Modifier.clip(CpShapes.pill).background(c.surface.copy(alpha = if (c.isDark) 0.55f else 0.7f))
                            .border(1.dp, c.hairline, CpShapes.pill).padding(horizontal = 5.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // With an on-device model: ask about your reading.
                        if (aiReady) IconCircleButton(R.drawable.lucide_sparkles, "Ask about your reading", { asking = true }, tint = c.textMid)
                        IconCircleButton(R.drawable.lucide_search, "Search", { searching = !searching; if (!searching) query = "" }, tint = if (searching) c.accentText else c.textMid)
                        // a physical book: added from Hardcover or Seekquel, updated by photo or page.
                        IconCircleButton(R.drawable.lucide_plus, "Add a book", { addQuery = ""; adding = true }, tint = c.textMid)
                        IconCircleButton(R.drawable.ic_ribbon_sync, if (state.running) "Syncing" else "Sync now", { vm.syncNow() }, busy = state.running)
                    }
                }
                PageTitle("Your library")
                AnimatedQuiet(
                    if (state.running) state.syncStep?.let { "Syncing \u00B7 $it…" } ?: "Syncing…"
                    else "${all.size} book${if (all.size == 1) "" else "s"} · synced ${ago(state.lastRun)}"
                )
            }
        }
        if (searching) {
            item(key = "search") {
                Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).animateItem()) {
                    Box(contentAlignment = Alignment.CenterEnd) {
                        CpTextField(
                            query, { query = it },
                            if (clipsShelf) "Search highlights" else "Search your library",
                            placeholder = if (clipsShelf) "A word, a note, a book or an author" else "Title or author",
                        )
                        if (query.isNotEmpty()) {
                            Box(
                                Modifier
                                    .padding(top = 22.dp, end = 12.dp)
                                    .size(28.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .clickable { query = "" },
                                contentAlignment = Alignment.Center,
                            ) {
                                LucideIcon(R.drawable.lucide_x, Modifier.size(16.dp), c.quiet)
                            }
                        }
                    }
                }
            }
        }
        toRate?.let { b ->
            item(key = "rate-${b.document}") {
                FinishPromptCard(
                    b,
                    onRate = { rating = b.document },
                    onDismiss = { vm.dismissFinish(b.document) },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).animateItem(),
                )
            }
        }
        // Physical books you've started on the reader: one prompt each.
        for ((m, cDoc) in state.handoffs) {
            val pb = state.books[m] ?: continue
            val rb = state.books[cDoc] ?: continue
            item(key = "handoff-$m") {
                HandoffCard(
                    pb, rb,
                    onContinue = { vm.continueOnReader(m) },
                    onKeep = { vm.keepBothCopies(m) },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).animateItem(),
                )
            }
        }
        // Seekquel's position differs for these books: one prompt each.
        val asks = state.progressAsks.entries.mapNotNull { (d, a) -> state.books[d]?.let { it to a } }
            .sortedByDescending { it.first.timestamp }
        for ((b, ask) in asks.take(3)) {
            item(key = "ask-${b.document}") {
                SeekquelProgressCard(
                    b, ask,
                    onUse = { vm.acceptSeekquelProgress(b.document) },
                    onKeep = { vm.denySeekquelProgress(b.document) },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).animateItem(),
                    kosyncOn = state.kosync != null,
                    onMoveBack = { vm.moveBackProgress(b.document) },
                    onAlways = { all -> vm.trustSource(if (all) null else b.document, ask.source) },
                )
            }
        }
        // Books that look like one another: Join or Keep apart (never joined by themselves).
        val samePairs = io.github.prithirajn.trackersync.sync.SameBook.pairs(state)
        for (m in samePairs.take(2)) {
            item(key = "same-${m.key}") {
                SameBookCard(
                    m,
                    onJoin = { vm.joinSameBook(m.keep.document, m.other.document) },
                    onApart = { vm.keepBooksApart(m.keep.document, m.other.document) },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).animateItem(),
                )
            }
        }
        // Books not going to your trackers yet: one prompt, again only when more come in.
        val waitingBooks = io.github.prithirajn.trackersync.sync.SameBook.waiting(state)
        if (waitingBooks.size > state.waitingSeen) {
            item(key = "waiting-books") {
                WaitingBooksCard(
                    waitingBooks,
                    onTurnOn = { vm.turnOnWaiting() },
                    onLater = { vm.dismissWaiting() },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).animateItem(),
                )
            }
        }
        // Finished books a source says you're reading again: one prompt each.
        for ((b, ask) in state.rereadAsks.entries.mapNotNull { (d, a) -> state.books[d]?.let { it to a } }.sortedByDescending { it.second.updatedAt }.take(3)) {
            item(key = "reread-${b.document}") {
                RereadAskCard(
                    b, ask,
                    onYes = { vm.rereadYes(b.document) },
                    onNo = { vm.rereadNo(b.document) },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).animateItem(),
                )
            }
        }
        // Read in KOReader, highlights not set up: offered once (until "Not now" or set up).
        if (state.koSeenAt > 0 && !state.koNudgeOff && io.github.prithirajn.trackersync.sync.KoreaderDevice.highlightsMissing(state)) {
            item(key = "ko-nudge") {
                KoreaderNudgeCard(
                    onSetUp = { vm.openKoHighlights() },
                    onLater = { vm.dismissKoNudge() },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).animateItem(),
                )
            }
        }
        // Several waiting: answer them all at once.
        if (asks.size >= 2) {
            item(key = "ask-all") {
                Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp).animateItem(), verticalAlignment = Alignment.CenterVertically) {
                    Quiet("${asks.size} books with a different position:", Modifier.weight(1f), color = Cp.colors.faint)
                    PillButton("Keep all", { vm.keepAllPositions() })
                    HSpace(8)
                    PillButton("Update all", { vm.updateAllPositions() }, outlined = false)
                }
            }
        }
        if (asks.size > 3) {
            item(key = "ask-more") {
                Quiet("${asks.size - 3} more books have a different position elsewhere; it's shown on each book.",
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp).animateItem(), color = Cp.colors.faint)
            }
        }
        if (all.isNotEmpty()) {
            item {
                // This month only: pages read and books finished since the 1st (reading is what's open now).
                StatsCard(
                    reading = counts[ReadStatus.READING] ?: 0,
                    pages = pages?.let { Shelves.pagesThisMonth(state, today) },
                    today = today,
                    finished = Shelves.finishedThisMonth(all),
                    month = Shelves.monthName(),
                    streak = Shelves.streak(state, today),
                    readToday = (today ?: 0) > 0,
                )
            }
            if (allClips.isNotEmpty() && !clipsShelf && !searching) {
                item(key = "daily") {
                    DailyHighlightCard(
                        state, allClips,
                        onOpenBook = { open = it },
                        onOpen = { editingClip = it },
                        onShare = { sharingClip = it },
                        onSeeAll = { clipsShelf = true; clipBook = null },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).animateItem(),
                        toCheck = toCheck,
                        onCheck = { checkingHighlights = true },
                    )
                }
            }
        }
        dupe?.let { d ->
            item(key = "dupe") {
                Row(
                    Modifier
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                        .fillMaxWidth()
                        .bouncyClickable(CpShapes.card, pressedScale = 0.98f) { open = d.document }
                        .background(c.accentTint)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LucideIcon(R.drawable.lucide_merge, Modifier.size(16.dp), c.accentText)
                    HSpace(10)
                    Text(
                        "\u201C${d.displayTitle}\u201D appears more than once. Open it and use \u22EF \u2192 Merge a duplicate to keep progress together.",
                        style = CpType.small,
                        color = c.accentText,
                        modifier = Modifier.weight(1f),
                    )
                    HSpace(6)
                    LucideIcon(R.drawable.lucide_chevron_right, Modifier.size(16.dp), c.accentText)
                }
            }
        }
        if (!anyLinked && all.isNotEmpty()) {
            item {
                CpCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("No trackers linked yet", style = CpType.bodyStrong, color = c.text)
                        Quiet("Link Hardcover, Goodreads, StoryGraph, Fable, Pagebound or Seekquel under Services to start syncing.")
                    }
                }
            }
        }
        if (all.isEmpty()) {
            item {
                Centered {
                    if (state.running) CircularProgressIndicator(Modifier.size(24.dp), color = c.faint)
                    else Quiet("No books yet. Read on your CrossPoint with sync turned on, then sync.")
                }
            }
        } else {
            item {
                // Highlights are opened from the Highlight of the day card ("All N"), not a shelf tab.
                StatusTabs(tab, counts, highlightsOn = clipsShelf) { tab = it; clipsShelf = false }
            }
            if (clipsShelf) {
                item(key = "clip-title") {
                    Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        LucideIcon(R.drawable.lucide_highlighter, Modifier.size(16.dp), c.accentText)
                        HSpace(8)
                        Text("Your highlights", style = CpType.bodyStrong, color = c.text, modifier = Modifier.weight(1f))
                        PillButton("Back to books", { clipsShelf = false; clipBook = null })
                    }
                }
                item(key = "clip-head") {
                    Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Quiet(
                            if (query.isNotBlank()) "${shownClips.size} match${if (shownClips.size == 1) "" else "es"}"
                            else "${allClips.size} from ${clipBooks.size} book${if (clipBooks.size == 1) "" else "s"}",
                            Modifier.weight(1f),
                            color = if (query.isNotBlank()) c.accentText else c.quiet,
                        )
                        if (allClips.isNotEmpty()) Row(
                            Modifier
                                .bouncyClickable(CpShapes.pill, pressedScale = 0.92f, role = Role.Button, onClickLabel = "Export all highlights") { exportHighlights(listContext, state, allClips) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LucideIcon(R.drawable.lucide_upload, Modifier.size(14.dp), c.quiet)
                            HSpace(6)
                            Text("Export", style = CpType.small, color = c.textMid)
                        }
                    }
                }
                highlightShelf(
                    state, shownClips, clipBooks, allClips.size, clipBook, query,
                    onFilter = { clipBook = it },
                    onOpenBook = { open = it },
                    onOpen = { editingClip = it },
                    onShare = { sharingClip = it },
                )
            } else {
            item(key = "sort") {
                // Sort: one tap cycles Recent → Title → Author → Progress.
                Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (query.isNotBlank()) {
                        Quiet("${shown.size} match${if (shown.size == 1) "" else "es"} in ${tab.label}", Modifier.weight(1f), color = c.accentText)
                    } else {
                        Box(Modifier.weight(1f))
                    }
                    Row(
                        Modifier
                            .bouncyClickable(CpShapes.pill, pressedScale = 0.92f) {
                                sort = Shelves.Sort.entries[(sort.ordinal + 1) % Shelves.Sort.entries.size]
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LucideIcon(R.drawable.lucide_arrow_up_down, Modifier.size(14.dp), c.quiet)
                        HSpace(6)
                        Text(sort.label, style = CpType.small, color = c.textMid)
                    }
                }
            }
            if (shown.isEmpty()) {
                item(key = if (query.isNotBlank()) "empty-search" else "empty-${tab.name}") {
                    if (query.isNotBlank()) {
                        SearchEmpty(
                            query = query,
                            shelf = tab.label,
                            onClear = { query = "" },
                            modifier = Modifier.animateItem(fadeInSpec = Motion.medium(), placementSpec = null, fadeOutSpec = Motion.fast()),
                        )
                    } else {
                        TabEmpty(tab, Modifier.animateItem(fadeInSpec = Motion.medium(), placementSpec = null, fadeOutSpec = Motion.fast()))
                    }
                }
            }
            items(shown, key = { it.document }) { book ->
                // New books fade in, and rows glide when a sync reorders them.
                BookRow(book, state, linked, Modifier.animateItem(fadeInSpec = Motion.medium(), placementSpec = Motion.springy(), fadeOutSpec = Motion.fast())) {
                    open = book.document
                }
            }
            }
        }
    }

    }
    open?.let { doc ->
        state.books[doc]?.let {
            BookSheet(vm, state, it, linked, autoSnap = autoSnap, onRate = { rating = doc }) { open = null; autoSnap = false }
        }
    }
    rating?.let { doc -> state.books[doc]?.let { RateSheet(vm, state, it) { rating = null } } }
    if (adding) AddBookSheet(vm, initialQuery = addQuery, onClose = { adding = false }, onAdded = { doc -> adding = false; tab = ReadStatus.READING; open = doc },
        onLogManga = { adding = false; loggingManga = true })
    if (loggingManga) LogReadingSheet(vm, state, null, onClose = { loggingManga = false })
    if (asking) AskLibrarySheet(vm) { asking = false }
    editingClip?.let { e -> EditHighlightSheet(vm, state, e) { editingClip = null } }
    sharingClip?.let { h ->
        QuoteShareSheet(
            h.clip, state.books[h.clip.document], others = BookShot.othersFor(state, h.clip), pageText = state.clipPages[h.clip.id],
            onPageText = { vm.setClipPage(h.clip.id, it) },
            onFromPhoto = state.books[h.clip.document]?.let { b -> { sharingClip = null; photoFrom = b.document } },
            sectors = BookShot.sectorsFor(state, state.books[h.clip.document]),
        ) { sharingClip = null }
    }
    photoFrom?.let { doc -> state.books[doc]?.let { PaperHighlightSheet(vm, state, it) { photoFrom = null } } }
}

/**
 * The day at a glance on the device's screen: books being read, pages this month (and today), books
 * finished; and the reading streak as a row of lit days, with a nudge when today isn't lit yet.
 */
@Composable
private fun StatsCard(reading: Int, pages: Int?, finished: Int, today: Int? = null, month: String? = null, streak: Int = 0, readToday: Boolean = false) {
    val c = Cp.colors
    Column(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
            .shadow(14.dp, androidx.compose.foundation.shape.RoundedCornerShape(26.dp), ambientColor = c.shade, spotColor = c.shade)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(26.dp))
            .background(c.surface)
            .padding(10.dp),
    ) {
        Screen {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Stat(reading, "Reading", Modifier.weight(1f))
                Stat(pages ?: 0, if (month != null) "Pages in $month" else "Pages", Modifier.weight(1.3f),
                    extra = today?.let { if (it > 0) "+${String.format(java.util.Locale.getDefault(), "%,d", it)} today" else "None yet today" })
                Stat(finished, if (month != null) "Done in $month" else "Finished", Modifier.weight(1f))
            }
            // The streak: a lit day for each day in a row, up to a week; today blinks until you read.
            if (streak >= 1) {
                VSpace(12)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("\uD83D\uDD25", style = CpType.body)
                    HSpace(6)
                    Text(
                        if (streak == 1) "Day one" else "$streak-day streak",
                        style = CpType.bodyStrong, color = c.screenText,
                    )
                    Fill()
                    val blink = androidx.compose.animation.core.rememberInfiniteTransition(label = "streak")
                    val b by blink.animateFloat(0.25f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(700), androidx.compose.animation.core.RepeatMode.Reverse), label = "blink")
                    // Read today: the streak's days lit (up to a week). Not yet: the days before, and today blinking.
                    val litCount = if (readToday) streak.coerceAtMost(7) else streak.coerceAtMost(6)
                    for (i in 0 until 7) {
                        val lit = i < litCount
                        val isToday = !readToday && i == litCount
                        Box(
                            Modifier.padding(start = 4.dp).size(width = 12.dp, height = 8.dp).clip(CpShapes.pill)
                                // Today's blink read while drawing, so only this dot redraws, not the panel.
                                .drawBehind {
                                    drawRect(
                                        when {
                                            lit -> c.accent
                                            isToday -> c.accent.copy(alpha = 0.25f + 0.5f * b)
                                            else -> c.screenOff
                                        },
                                    )
                                },
                        )
                    }
                }
                if (!readToday && streak >= 1) {
                    VSpace(6)
                    DeviceLabel("Read a page today to keep it going", color = c.screenDim)
                }
            }
        }
    }
}

@Composable
private fun Stat(value: Int, label: String, modifier: Modifier, extra: String? = null) {
    val c = Cp.colors
    Column(modifier) {
        RollingNumber(value, CpType.screenSmall.copy(fontSize = 30.sp, lineHeight = 32.sp), c.screenText)
        VSpace(2)
        DeviceLabel(label, color = c.screenDim)
        extra?.let {
            VSpace(4)
            AnimatedContent(
                targetState = it,
                transitionSpec = { (fadeIn(Motion.medium()) + slideInVertically(Motion.bouncy()) { h -> h }) togetherWith fadeOut(Motion.fast()) },
                label = "statExtra",
            ) { e -> Text(e, style = CpType.small, color = if (e.startsWith("+")) c.accent else c.screenDim, maxLines = 1) }
        }
    }
}

/** Reading / Paused / Finished / Did Not Finish, each with its count, underlined when chosen. */
@Composable
private fun StatusTabs(
    current: ReadStatus,
    counts: Map<ReadStatus, Int>,
    highlights: Int = 0,
    highlightsOn: Boolean = false,
    onHighlights: () -> Unit = {},
    onSelect: (ReadStatus) -> Unit,
) {
    val c = Cp.colors
    // The selected shelf is always scrolled into view (Highlights sits past the screen's edge).
    val scroll = rememberScrollState()
    val starts = remember { androidx.compose.runtime.mutableStateMapOf<String, Int>() }
    val selectedKey = if (highlightsOn) "highlights" else current.name
    val edge = with(androidx.compose.ui.platform.LocalDensity.current) { 16.dp.roundToPx() }
    androidx.compose.runtime.LaunchedEffect(selectedKey, starts[selectedKey]) {
        val x = starts[selectedKey] ?: return@LaunchedEffect
        scroll.animateScrollTo((x - edge).coerceAtLeast(0))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        for (t in Shelves.tabs) {
            val on = t == current && !highlightsOn
            val tint by animateColorAsState(if (on) c.accentText else c.quiet, Motion.medium(), label = "tabText")
            val bar by animateColorAsState(if (on) c.accent else c.accent.copy(alpha = 0f), Motion.medium(), label = "tabBar")
            Column(
                Modifier
                    .width(IntrinsicSize.Max)
                    .onGloballyPositioned { starts[t.name] = it.positionInParent().x.toInt() }
                    .bouncyClickable(RectangleShape, pressedScale = 0.96f, role = Role.Tab) { onSelect(t) }
            ) {
                Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t.label, style = CpType.label, color = tint)
                    HSpace(6)
                    Text((counts[t] ?: 0).toString(), style = CpType.small, color = tint)
                }
                Box(Modifier.fillMaxWidth().height(2.dp).clip(CpShapes.pill).background(bar))
            }
        }
        // Highlights, as one more shelf.
        if (highlights > 0) {
            val tint by animateColorAsState(if (highlightsOn) c.accentText else c.quiet, Motion.medium(), label = "tabText")
            val bar by animateColorAsState(if (highlightsOn) c.accent else c.accent.copy(alpha = 0f), Motion.medium(), label = "tabBar")
            Column(
                Modifier
                    .width(IntrinsicSize.Max)
                    .onGloballyPositioned { starts["highlights"] = it.positionInParent().x.toInt() }
                    .bouncyClickable(RectangleShape, pressedScale = 0.96f, role = Role.Tab, onClick = onHighlights)
            ) {
                Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    LucideIcon(R.drawable.lucide_highlighter, Modifier.size(14.dp), tint)
                    HSpace(6)
                    Text("Highlights", style = CpType.label, color = tint)
                    HSpace(6)
                    Text(highlights.toString(), style = CpType.small, color = tint)
                }
                Box(Modifier.fillMaxWidth().height(2.dp).clip(CpShapes.pill).background(bar))
            }
        }
    }
}

/** What an empty tab says, in the companion app's voice. */
@Composable
private fun SearchEmpty(query: String, shelf: String, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val c = Cp.colors
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Eyebrow("No matches")
        VSpace(4)
        Text("No books found", style = CpType.section, color = c.text)
        VSpace(6)
        Text(
            "Nothing matches “$query” in $shelf.",
            style = CpType.body,
            color = c.quiet,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        VSpace(14)
        PillButton("Clear search", onClear)
    }
}

@Composable
private fun TabEmpty(tab: ReadStatus, modifier: Modifier = Modifier) {
    val (note, title, body) = when (tab) {
        ReadStatus.PAUSED -> Triple("Taking a breather?", "No paused books", "Books you set aside wait here until you pick them back up.")
        ReadStatus.FINISHED -> Triple("The best part is ahead", "No finished books yet", "Books you finish land here, newest first.")
        ReadStatus.DNF -> Triple("Life is too short", "Nothing abandoned", "Books you stop reading go here, guilt-free. Not every book is for everyone.")
        else -> Triple("Your shelf is waiting", "Nothing in progress", "Open a book on your CrossPoint and it shows up here as it syncs.")
    }
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Eyebrow(note)
        VSpace(4)
        Text(title, style = CpType.section, color = Cp.colors.text)
        VSpace(6)
        Text(body, style = CpType.body, color = Cp.colors.quiet, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun BookRow(book: Book, state: Snapshot, linked: List<Tracker>, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Cp.colors
    CpCard(
        modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .bouncyClickable(CpShapes.card, pressedScale = 0.985f, onClick = onClick)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
        BookCover(book.coverUrl, book.displayTitle, 48.dp, 72.dp)
        HSpace(14)
        Column(Modifier.weight(1f)) {
            Text(book.displayTitle, style = CpType.bodyStrong, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            book.author?.let { Quiet(it, maxLines = 1) }
            // A manga or comic volume: where you are in it.
            book.place?.takeIf { book.status != ReadStatus.FINISHED }?.let { Quiet(it, maxLines = 1, color = c.accentText) }
            VSpace(10)
            ProgressBar(book.percentage.toFloat())
            VSpace(6)
            val originName = book.origin?.let { originLabel(it) }
            // Where it came from, in the same quiet line: "33% · Reading · 11h ago · from Kindle".
            val where = when {
                ManualBooks.isManual(book) -> "physical book"
                originName != null -> "from $originName"
                else -> null
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedQuiet("${book.percent}% · ${book.status?.label ?: "Reading"} · ${ago(book.timestamp * 1000)}" + (where?.let { " · $it" } ?: ""), Modifier.weight(1f))
            }
            val targets = if (book.held) emptyList() else linked.filter { it.id != book.origin }
            if (book.held) {
                VSpace(8)
                Chip("Not sent to trackers yet", color = c.quiet, background = c.divider)
            } else if (targets.isNotEmpty()) {
                // A lettered dot per tracker, and words only for what needs you.
                val dots = targets.map { t ->
                    val link = state.link(t.id, book.document)
                    val waiting = state.pending.containsKey(io.github.prithirajn.trackersync.data.Link.key(t.id, book.document))
                    t.name to when {
                        link?.source == LinkSource.NONE -> Dot.OFF
                        link != null && (link.externalId == null || !link.confirmed) -> Dot.NEEDS_YOU
                        waiting || link == null -> Dot.WAITING
                        else -> Dot.SYNCED
                    }
                }
                val needs = dots.filter { it.second == Dot.NEEDS_YOU }.map { it.first }
                val toConfirm = targets.filter { t -> state.link(t.id, book.document)?.let { it.externalId != null && !it.confirmed && it.source != LinkSource.NONE } == true }.map { it.name }
                val off = dots.count { it.second == Dot.OFF }
                val note = when {
                    needs.isNotEmpty() && needs.size == toConfirm.size -> if (needs.size == 1) "confirm the match on ${needs[0]}" else "${needs.size} matches to confirm"
                    needs.size == 1 -> "pick the match on ${needs[0]}"
                    needs.size > 1 -> "${needs.size} need you"
                    off == dots.size -> "paused on every tracker"
                    dots.all { it.second == Dot.SYNCED } -> "up to date on all"
                    dots.any { it.second == Dot.WAITING } -> "syncing soon"
                    else -> null
                }
                VSpace(8)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TrackerDots(dots)
                    note?.let {
                        HSpace(8)
                        Text(it, style = CpType.small, color = if (needs.isNotEmpty()) c.warn else c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        }
    }
}

/** The companion app's description block: clamped with Read more, the source credited under it. */
@Composable
private fun Description(book: Book, looked: String? = null, onLookAgain: (() -> Unit)? = null, onTidy: (() -> Unit)? = null) {
    val c = Cp.colors
    val text = book.description
    if (text == null) {
        VSpace(16)
        if (!book.detailsFetched) {
            Quiet("Loading description…", color = c.faint)
        } else {
            // Nothing found: where it looked, and a way to look again.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Quiet("No description found" + (looked?.takeIf { it.isNotBlank() }?.let { " (looked in $it)" } ?: "") + ".", Modifier.weight(1f), color = c.faint)
                onLookAgain?.let { HSpace(8); PillButton("Look again", it) }
            }
        }
        return
    }
    var expanded by remember(book.document) { mutableStateOf(false) }
    var overflows by remember(book.document) { mutableStateOf(false) }
    VSpace(16)
    Text(
        text,
        style = CpType.body,
        color = c.textMid,
        maxLines = if (expanded) Int.MAX_VALUE else 6,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
    )
    if (overflows || expanded) {
        Text(
            if (expanded) "Show less" else "Read more",
            style = CpType.bodyStrong,
            color = c.accentText,
            modifier = Modifier
                .padding(top = 4.dp)
                .clickable { expanded = !expanded },
        )
    }
    book.descriptionSource?.takeIf { it != "you" }?.let { Quiet("Description from $it", Modifier.padding(top = 6.dp), color = c.faint) }
    // With an on-device model: take the clutter out, or put it in two sentences.
    onTidy?.let { VSpace(8); PillButton("Tidy with AI", it) }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun BookSheet(
    vm: AppViewModel,
    state: Snapshot,
    book: Book,
    allLinked: List<Tracker>,
    autoSnap: Boolean = false,
    onRate: () -> Unit = {},
    onClose: () -> Unit,
) {
    var comparing by remember { mutableStateOf(false) }
    var highlighting by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf(false) }
    var addingTo by remember { mutableStateOf<String?>(null) }
    var storyLink by remember { mutableStateOf(false) }
    // The AI helpers are offered when an on-device model is set up.
    val aiReady = remember { vm.aiReady() }
    var tidying by remember { mutableStateOf(false) }
    var timing by remember { mutableStateOf(false) }
    var whoIs by remember { mutableStateOf(false) }
    var recap by remember { mutableStateOf(false) }
    val openHistory = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    // A book brought in from a tracker or Kindle goes to the others, not back to where it came from.
    val origin = book.origin?.let { Trackers.byId(it) }
    val originName = book.origin?.let { originLabel(it) }
    // Past reading that hasn't been sent anywhere yet has no tracker rows until it's picked.
    val linked = if (book.held) emptyList() else allLinked.filter { it.id != book.origin }
    var searching by remember { mutableStateOf<Tracker?>(null) }
    var bookAction by remember { mutableStateOf<BookAction?>(null) }
    var postingReview by remember { mutableStateOf(false) }
    LaunchedEffect(book.document) { vm.loadDetails(book.document) }
    LaunchedEffect(Unit) { vm.loadHighlights() }
    val c = Cp.colors
    // The whole page takes this book's colour: its wallpaper, keys, lights and lit segments.
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val bookSeed by androidx.compose.runtime.produceState<Int?>(null, book.coverUrl) {
        value = book.coverUrl?.let { u -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { CoverAccent.of(appContext, u) }.getOrNull() } }
    }
    io.github.prithirajn.trackersync.ui.theme.AccentTheme(bookSeed) {
    GuardedSheet(
        // The grip is drawn on the book's wallpaper instead, so the colour runs right to the top.
        dragHandle = null,
        onDismissRequest = onClose,
        sheetState = rememberSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        Box(Modifier.fillMaxSize()) {
        // Always the full height of the screen, so switching pages or loading never resizes the sheet.
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            val facts = state.facts[book.document]
            // The book as a little device on a wallpaper in its cover's colours: a dark screen with
            // the title, the cover and your place rolling up in pixel digits, and keys under it.
            // At full height the sheet runs under the status bar: the wallpaper goes up behind it, the
            // grip, label and ⋯ start below it (so the menu never sits on the clock or the battery).
            val topInset = androidx.compose.foundation.layout.WindowInsets.statusBars
                .asPaddingValues().calculateTopPadding()
            Box(Modifier.fillMaxWidth()) {
                Wallpaper(height = 340.dp + topInset)
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = topInset)) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SheetGrip() }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DeviceLabel(
                            listOfNotNull(
                                if (ManualBooks.isManual(book)) "Physical book" else originName?.let { "From $it" } ?: "On CrossPoint",
                                book.pageCount?.let { "$it pages" },
                            ).joinToString("  /  "),
                            Modifier.weight(1f).padding(start = 6.dp),
                            color = c.textMid,
                        )
                        // The ⋯ menu for editing details, cover, print pages, dates, duplicates, or removing from library.
                        BookMenuButton(state, book, webNovelLinked = state.webNovels[book.document] != null) {
                            if (it == BookAction.WEBNOVEL) storyLink = true else bookAction = it
                        }
                    }
                    // The device.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .shadow(16.dp, androidx.compose.foundation.shape.RoundedCornerShape(28.dp), ambientColor = c.shade, spotColor = c.shade)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
                            .background(c.surface)
                            .padding(12.dp),
                    ) {
                        Screen {
                            Row {
                                Column(Modifier.weight(1f)) {
                                    DeviceLabel(
                                        when (book.status) {
                                            ReadStatus.FINISHED -> "Finished"
                                            ReadStatus.DNF -> "Did not finish"
                                            ReadStatus.PAUSED -> "Paused"
                                            ReadStatus.TO_READ -> "Want to read"
                                            else -> "Now reading"
                                        } + if (book.manualAt != null && book.timestamp == book.manualAt && !ManualBooks.isManual(book)) "  /  set by you" else "",
                                        color = c.screenDim,
                                    )
                                    VSpace(8)
                                    Text(book.displayTitle, style = CpType.section.copy(fontSize = 19.sp, lineHeight = 23.sp), color = c.screenText, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    VSpace(6)
                                    book.author?.let { Text(it, style = CpType.small, color = c.screenDim, maxLines = 1) }
                                    seriesLine(facts)?.let { Text(it, style = CpType.small, color = c.screenDim, maxLines = 1) }
                                }
                                HSpace(12)
                                // The cover pops in with a little bounce and a tilt when the book opens.
                                val pop = remember(book.document) { androidx.compose.animation.core.Animatable(0.7f) }
                                LaunchedEffect(book.document) { pop.animateTo(1f, Motion.bouncy()) }
                                BookCover(
                                    book.coverUrl, book.displayTitle, 78.dp, 116.dp,
                                    Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value; rotationZ = 3f + (1f - pop.value) * 14f },
                                )
                            }
                            VSpace(10)
                            Row(verticalAlignment = Alignment.Bottom) {
                                RollingNumber(book.percent, CpType.screenNumber, c.accent)
                                Text("%", style = CpType.screenSmall, color = c.screenDim, modifier = Modifier.padding(start = 3.dp, bottom = 7.dp))
                                Fill()
                                if (facts?.rating != null || facts?.year != null) Box(Modifier.padding(bottom = 8.dp)) { RatingLine(facts) }
                            }
                            VSpace(10)
                            SegmentBar(book.percentage.toFloat(), segments = 20, height = 7.dp, on = c.accent, off = c.screenOff)
                        }
                        VSpace(12)
                        // The keys: the everyday actions. Rate is the orange key until the book is rated.
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DeviceKey(R.drawable.lucide_star, book.rating?.let { SyncEngine.stars(it) } ?: "Rate", Modifier.weight(1f), signal = book.rating == null, onClick = onRate)
                            if (!ManualBooks.isManual(book)) DeviceKey(R.drawable.lucide_book_open, "Progress", Modifier.weight(1f)) { updating = true }
                            DeviceKey(R.drawable.lucide_camera, "Snap", Modifier.weight(1f)) { highlighting = true }
                            if (allLinked.isNotEmpty()) DeviceKey(R.drawable.lucide_scale, "Compare", Modifier.weight(1f)) { comparing = true }
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            // Main source, the copy for quotes and whether you own it: one line of labels, each opening its card.
            BookChips(vm, state, book)
            // Only for what looks like a web novel; any book can still be linked from the ⋯ menu.
            if (state.webNovels[book.document] == null && !storyLink && looksLikeWebNovel(book)) {
                VSpace(12)
                PillButton("Link it as a web novel", { storyLink = true })
            }
            // What needs you, right under the book: nothing else comes before it.
            // Started on the reader: offered on both copies.
            (state.handoffs[book.document]?.let { book.document to it }
                ?: state.handoffs.entries.firstOrNull { it.value == book.document }?.toPair())?.let { (m, cDoc) ->
                val pb = state.books[m]
                val rb = state.books[cDoc]
                if (pb != null && rb != null) {
                    VSpace(16)
                    HandoffCard(pb, rb, onContinue = { vm.continueOnReader(m); if (book.document == m) onClose() }, onKeep = { vm.keepBothCopies(m) })
                }
            }
            remember(state.books, state.keptApart, book.document) { io.github.prithirajn.trackersync.sync.SameBook.pairFor(state, book.document) }?.let { m ->
                VSpace(16)
                SameBookCard(m, onJoin = { vm.joinSameBook(m.keep.document, m.other.document) }, onApart = { vm.keepBooksApart(m.keep.document, m.other.document) })
            }
            state.rereadAsks[book.document]?.let { ask ->
                VSpace(16)
                RereadAskCard(book, ask, onYes = { vm.rereadYes(book.document) }, onNo = { vm.rereadNo(book.document) }, showBook = false)
            }
            state.progressAsks[book.document]?.let { ask ->
                VSpace(16)
                SeekquelProgressCard(book, ask, onUse = { vm.acceptSeekquelProgress(book.document) }, onKeep = { vm.denySeekquelProgress(book.document) }, showBook = false, kosyncOn = state.kosync != null,
                    onMoveBack = { vm.moveBackProgress(book.document) }, onAlways = { all -> vm.trustSource(if (all) null else book.document, ask.source) })
            }
            if (ManualBooks.isManual(book)) {
                VSpace(16)
                ManualProgressCard(vm, book, onRemoved = onClose, autoSnap = autoSnap)
            }
            // A manga or comic volume: log the page or the next chapter or issue right here.
            vm.seriesOf(book)?.let { series ->
                VSpace(16)
                MangaLogCard(vm, state, book, series)
            }

            // The rest in tabs, instead of one long stack of cards.
            val clipCount = remember(state.cpClips, state.paperClips, state.koClips, state.kindleClips, book.document) {
                runCatching { io.github.prithirajn.trackersync.sync.Highlights.forBook(state, book.document).size }.getOrDefault(0)
            }
            val needsSync = linked.any { t -> state.link(t.id, book.document)?.let { it.externalId != null && !it.confirmed && it.source != LinkSource.NONE } == true }
            var tab by rememberSaveable(book.document) { mutableStateOf(0) }
            VSpace(18)
            PageTabs(listOf("Book" + if (needsSync) " \u2022" else "", "Highlights" + (if (clipCount > 0) " $clipCount" else "")), tab) { tab = it }
            // The two pages fade across; the sheet stays its full height, so nothing jumps.
            androidx.compose.animation.Crossfade(tab, animationSpec = androidx.compose.animation.core.tween(220), label = "bookTab") { t ->
            Column(Modifier.fillMaxWidth()) {
            if (t == 1) {
                    HighlightsSection(vm, state, book)
                    if (clipCount == 0) { VSpace(16); Quiet("No highlights yet. Snap page adds one from a photo of the page.", color = c.faint) }
                
            } else {
                    // What the book is about, and how it reads.
                    Description(
                        book, state.detailsLooked[book.document],
                        onLookAgain = { vm.lookAgain(book.document) },
                        onTidy = if (aiReady) ({ tidying = true }) else null,
                    )
                    FeelCard(state.facts[book.document])
                
                BookSection("Reading")
                // A reading session with the timer, and (with the on-device model) who's who so far.
                if (book.status != io.github.prithirajn.trackersync.data.ReadStatus.FINISHED) {
                    VSpace(12)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BigButton("\u23F1 Start a reading session", { timing = true }, Modifier.weight(1.4f), look = KeyKind.INK)
                        if (aiReady && book.percent in 1..99) BigButton("Who's this?", { whoIs = true }, Modifier.weight(1f), filled = false)
                    }
                } else {
                    // Finished: the book told in numbers, as a video or a carousel to post.
                    VSpace(12)
                    BigButton("\u2726 Book recap: video & carousel", { recap = true }, look = KeyKind.INK)
                }
                    // Where the position comes from, how the reading went, and what's linked to it.
                    ReadingStatsCard(book)
                    DayByDayCard(state.readingDays[book.document])
                    if (!book.isMangaOrComic) WebNovelCard(vm, book, state.webNovels[book.document], book.document in state.grewAt, storyLink) { storyLink = it }
                    AudiobookCard(vm, state, book)
                    // Your rubric scores for it (when you've set up a rubric under Activity › Rating rubric).
                    RubricCard(vm, state, book)
                    // The book as one shareable card: its cover large, your stars, rubric and review; and its journal page.
                    run {
                        var coverCard by remember(book.document) { mutableStateOf(false) }
                        var journal by remember(book.document) { mutableStateOf(false) }
                        VSpace(12)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (book.status == io.github.prithirajn.trackersync.data.ReadStatus.FINISHED || book.rating != null) PillButton("Share a cover card", { coverCard = true })
                            if (CollectionHooks.available) PillButton("Journal page", { journal = true })
                        }
                        if (coverCard) CoverCardSheet(state, book) { coverCard = false }
                        if (journal) JournalSheet(state, book) { journal = false }
                    }
                
                BookSection("Sync")
                    ThreadsBookCard(vm, state, book) { postingReview = true }
                // Right under the book, where it's reachable without scrolling past every tracker.
                if (book.held) {
                    VSpace(16)
                    CpCard {
                        Column(Modifier.padding(16.dp)) {
                            Text("From your ${originName ?: "tracker"} history", style = CpType.bodyStrong, color = c.text)
                            Quiet("It isn't sent to your other trackers until you choose to. Sending marks it ${book.status?.label ?: "Reading"} there (dates stay as each tracker sets them).")
                            VSpace(12)
                            BigButton("Send to my trackers", { vm.sendHeld(book.document) }, enabled = allLinked.any { it.id != book.origin })
                        }
                    }
                } else if (linked.isNotEmpty()) {
                    VSpace(16)
                    BigButton("Sync this book now", { vm.syncBook(book.document); onClose() })
                    val anyPaused = linked.any { state.link(it.id, book.document)?.source == io.github.prithirajn.trackersync.data.LinkSource.NONE }
                    if (anyPaused) {
                        VSpace(8)
                        BigButton("Enable all trackers for this book", { vm.enableAllTrackers(book.document) }, filled = false)
                    }
                }
                VSpace(20)
                if (linked.isEmpty() && !book.held) {
                    Quiet(if (state.services.values.any { it.linked }) "Every tracker is paused under Services." else "Link a tracker under Services first.")
                }
                // Several first matches to check: confirm them together once you've looked.
                val unconfirmed = linked.filter { t -> state.link(t.id, book.document)?.let { it.externalId != null && !it.confirmed && it.source != LinkSource.NONE } == true }
                if (unconfirmed.size > 1) {
                    BigButton("All ${unconfirmed.size} matches are right", { vm.confirmAllMatches(book.document) })
                    VSpace(12)
                }
                if (linked.isNotEmpty()) CpCard {
                    linked.forEachIndexed { i, t ->
                        if (i > 0) RowDivider()
                        val link = state.link(t.id, book.document)
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ServiceTile(t.name)
                                HSpace(12)
                                Column(Modifier.weight(1f)) {
                                    Text(t.name, style = CpType.bodyStrong, color = c.text)
                                    val line = when {
                                        link == null -> "Not matched yet; matches on the next sync"
                                        link.source == LinkSource.NONE -> "Not synced"
                                        link.externalId == null -> link.note ?: "Needs a match"
                                        else -> "“${link.title ?: link.externalId}”" + (link.author?.let { " · $it" } ?: "")
                                    }
                                    Quiet(line, maxLines = 2)
                                    // A first match: nothing goes there until you say it's the right book.
                                    if (link != null && link.externalId != null && !link.confirmed && link.source != LinkSource.NONE) {
                                        Text("Is this the right book? Nothing is sent to ${t.name} until you confirm.", style = CpType.small, color = c.warn)
                                    }
                                    link?.takeIf { it.externalId != null }?.note?.let { Quiet(it, color = c.faint) }
                                    // What was sent there: progress from → to, pages, highlights; tap for earlier ones.
                                    val history = state.sent[io.github.prithirajn.trackersync.data.Link.key(t.id, book.document)].orEmpty()
                                    history.lastOrNull()?.let { last ->
                                        val open = t.id in openHistory
                                        VSpace(4)
                                        Column(
                                            Modifier
                                                .fillMaxWidth()
                                                // A square press area: the card's rounded corners were clipping the first letters.
                                                .bouncyClickable(androidx.compose.ui.graphics.RectangleShape, pressedScale = 0.99f) {
                                                    if (open) openHistory.remove(t.id) else openHistory.add(t.id)
                                                }
                                                .animateContentSize(Motion.springy())
                                        ) {
                                            Text("Sent ${last.describe()} · ${ago(last.at)}", style = CpType.small, color = c.accentText, maxLines = if (open) 3 else 1, overflow = TextOverflow.Ellipsis)
                                            if (open) {
                                                for (ev in history.dropLast(1).asReversed()) {
                                                    Text("${ev.describe()} · ${ago(ev.at)}", style = CpType.small, color = c.quiet, modifier = Modifier.padding(top = 2.dp))
                                                }
                                                if (history.size == 1) Quiet("Nothing earlier yet.", color = c.faint)
                                            } else if (history.size > 1) {
                                                Quiet("${history.size - 1} earlier · tap to see", color = c.faint)
                                            }
                                        }
                                    }
                                }
                            }
                            // The on-device AI's pick for a book the rules couldn't match: accept or dismiss.
                            val suggestion = state.aiSuggestions[io.github.prithirajn.trackersync.data.Link.key(t.id, book.document)]
                                ?.takeIf { it.id.isNotEmpty() && link?.externalId == null && link?.source != LinkSource.NONE }
                            if (suggestion != null) {
                                VSpace(8)
                                Column(Modifier.fillMaxWidth().background(c.accentTint, CpShapes.control).padding(horizontal = 12.dp, vertical = 10.dp)) {
                                    Text("AI suggests", style = CpType.small, color = c.accentText)
                                    Text("“${suggestion.title}”" + (suggestion.author?.let { " · $it" } ?: ""), style = CpType.body, color = c.text)
                                    VSpace(6)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        PillButton("Use it", { vm.acceptSuggestion(t, book.document) }, outlined = false)
                                        PillButton("Not this one", { vm.dismissSuggestion(t.id, book.document) })
                                    }
                                }
                            }
                            VSpace(8)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (link?.source == LinkSource.NONE) {
                                    // Paused for this book: turning it on is the one thing to do here.
                                    PillButton("Turn on", { vm.resetLink(t, book.document) }, outlined = false)
                                } else {
                                    if (link != null && link.externalId != null && !link.confirmed) PillButton("It's right", { vm.confirmMatch(t, book.document) }, outlined = false)
                                    PillButton(if (link?.externalId == null) "Find match" else "Change match", { searching = t }, outlined = link?.externalId != null)
                                    PillButton("Don't sync", { vm.neverSync(t, book.document) }, danger = true)
                                }
                            }
                        }
                    }
                }
                
            }
            }
            }
            // A web novel being linked from the pill shows straight away, whatever the tab.
            if (storyLink && tab != 0 && !book.isMangaOrComic) WebNovelCard(vm, book, state.webNovels[book.document], book.document in state.grewAt, storyLink) { storyLink = it }
            }
        }
        // What you just did (a copy kept, a price tracked…) answered here, over the book.
        SheetToaster()
        }
    }
    }
    searching?.let { t ->
        // Not on that tracker at all: from the search, add it there from the book's details.
        val canAdd = t.addsBooks && state.link(t.id, book.document)?.externalId == null
        SearchSheet(
            vm, t, book, state.link(t.id, book.document)?.externalId,
            onAdd = if (canAdd) ({ searching = null; addingTo = t.id }) else null,
        ) { searching = null }
    }
    if (comparing) CompareSheet(vm, state, book) { comparing = false }
    if (highlighting) PaperHighlightSheet(vm, state, book) { highlighting = false }
    if (tidying) AiDescriptionSheet(vm, book) { tidying = false }
    if (timing) ReadingTimerSheet(vm, book) { timing = false }
    if (whoIs) WhoIsSheet(vm, book) { whoIs = false }
    if (recap) BookRecapSheet(vm, state, state.books[book.document] ?: book) { recap = false }
    if (updating) UpdateProgressSheet(vm, book) { updating = false }
    addingTo?.let { first -> AddToTrackerSheet(vm, book, first) { addingTo = null } }
    bookAction?.let { a ->
        if (a == BookAction.DNF) DnfSheet(vm, state, book, onClose = { bookAction = null }, onMarked = {
            bookAction = null
            // Stopped: with Threads connected, offered to post the verdict.
            if (state.threadsToken != null) postingReview = true
        })
        else BookActionSheet(vm, state, book, a, onClose = { bookAction = null }, onRemoved = { bookAction = null; onClose() })
    }
    if (postingReview) state.books[book.document]?.let { b -> ReviewPostSheet(vm, state, b) { postingReview = false } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchSheet(vm: AppViewModel, tracker: Tracker, book: Book, currentId: String?, onAdd: (() -> Unit)? = null, onClose: () -> Unit) {
    val c = Cp.colors
    // Without web-novel tags ("[LitRPG, …]"), which trackers' searches don't match.
    var query by remember { mutableStateOf(listOfNotNull(book.title?.let { Matcher.plainTitle(it) }, Matcher.naturalAuthor(book.author)).joinToString(" ")) }
    var results by remember { mutableStateOf<List<RemoteBook>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun run() {
        busy = true; error = null
        vm.search(tracker, query, book.title, book.author) { r, e -> busy = false; results = r; error = e }
    }
    LaunchedEffect(Unit) { if (query.isNotBlank()) run() }

    GuardedSheet(
        dragHandle = { SheetGrip() },
        onDismissRequest = onClose,
        sheetState = rememberSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
        ) {
            Text("Match on ${tracker.name}", style = CpType.section, color = c.text)
            VSpace(12)
            CpTextField(query, { query = it }, "Search")
            VSpace(10)
            BigButton("Search", { run() }, filled = false, busy = busy, enabled = query.isNotBlank())
            error?.let { VSpace(10); Text(it, style = CpType.small, color = c.errorText) }
            val list = results
            if (list != null) {
                VSpace(16)
                if (list.isEmpty()) {
                    Quiet("Nothing found. Try fewer words, or just the title.")
                } else {
                    // The closest title and author, as auto-matching would score it.
                    val best = remember(list) {
                        val t = book.title ?: return@remember null
                        list.map { it to Matcher.score(t, book.author, it) }
                            .filter { it.second >= 0.6 }
                            .maxByOrNull { it.second }?.first?.id
                    }
                    CpCard {
                        list.forEachIndexed { i, r ->
                            if (i > 0) RowDivider()
                            MatchRow(r, best = r.id == best, current = r.id == currentId) {
                                vm.linkBook(tracker, book.document, r); onClose()
                            }
                        }
                    }
                    if (list.any { it.ratingsCount != null || it.reviewsCount != null || it.readers != null }) {
                        Quiet("Ratings, reviews and readers are as ${tracker.name} shows them.", Modifier.padding(top = 10.dp, start = 4.dp), color = c.faint)
                    }
                }
            }
            // Not there at all: create it on the tracker from the book's details.
            if (onAdd != null && results != null) {
                VSpace(18)
                Quiet("Not on ${tracker.name}?")
                VSpace(8)
                BigButton("Add it to ${tracker.name}", onAdd, filled = false)
            }
        }
    }
}

/** "987", "12.3k", "1.2M": short enough for a details line. */
private fun compact(n: Int): String = when {
    n < 1_000 -> n.toString()
    n < 10_000 -> String.format(java.util.Locale.US, "%.1fk", n / 1_000.0).replace(".0k", "k")
    n < 1_000_000 -> "${n / 1_000}k"
    n < 10_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0).replace(".0M", "M")
    else -> "${n / 1_000_000}M"
}

/**
 * One search result: cover, title and author, then what helps tell editions apart: labels
 * (on your shelf, best match, linked now, format), ratings and reviews, then year, pages,
 * editions and readers. Each part shows only when the tracker gave it.
 */
@Composable
private fun MatchRow(r: RemoteBook, best: Boolean, current: Boolean, onPick: () -> Unit) {
    val c = Cp.colors
    Row(
        Modifier
            .fillMaxWidth()
            .bouncyClickable(RectangleShape, pressedScale = 0.985f, onClick = onPick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        BookCover(r.coverUrl, r.title, 44.dp, 66.dp)
        HSpace(12)
        Column(Modifier.weight(1f)) {
            Text(r.title, style = CpType.bodyStrong, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Quiet(r.author ?: "Unknown author", maxLines = 1)

            val labels = buildList {
                if (current) add(Triple("Linked now", c.text, c.divider))
                if (r.onShelf) add(Triple("On your shelf", c.accentText, c.accentTint))
                if (best) add(Triple("Best match", c.accentText, c.accentTint))
                r.format?.let { add(Triple(it, c.quiet, c.divider)) }
            }
            if (labels.isNotEmpty()) {
                VSpace(6)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((text, fg, bg) in labels) Chip(text, color = fg, background = bg)
                }
            }

            val stats = listOfNotNull(
                r.rating?.let { "\u2605 " + String.format(java.util.Locale.US, "%.2f", it).trimEnd('0').trimEnd('.') },
                r.ratingsCount?.let { "${compact(it)} rating${if (it == 1) "" else "s"}" },
                r.reviewsCount?.let { "${compact(it)} review${if (it == 1) "" else "s"}" },
                r.readers?.let { "${compact(it)} reader${if (it == 1) "" else "s"}" },
            )
            if (stats.isNotEmpty()) {
                VSpace(6)
                Text(stats.joinToString(" · "), style = CpType.small, color = c.textMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val facts = listOfNotNull(
                r.year?.toString(),
                r.pages?.takeIf { it > 0 }?.let { "$it pages" },
                r.editions?.takeIf { it > 1 }?.let { "$it editions" },
            )
            if (facts.isNotEmpty()) {
                Text(facts.joinToString(" · "), style = CpType.small, color = c.quiet, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** Where a book was brought in from, as shown on it ("Kindle", "Libby", or a tracker's name). */
internal fun originLabel(origin: String): String? = when (origin.lowercase()) {
    "kindle" -> "Kindle"
    "libby" -> "Libby"
    "audible" -> "Audible"
    "mihon" -> "Mihon"
    "anilist" -> "AniList"
    "logged" -> "your log"
    "moonreader" -> "Moon+ Reader"
    "koreader" -> "KOReader"
    else -> io.github.prithirajn.trackersync.sync.HistoryImport.originName(origin) ?: Trackers.byId(origin)?.name
}

/**
 * A guess that a book is a web novel, so its link is offered up front: no ISBN or print pages,
 * and an author that reads like a site handle ("icohalliday", "Cool_Writer99") rather than a name.
 */
internal fun looksLikeWebNovel(book: io.github.prithirajn.trackersync.data.Book): Boolean {
    if (book.isMangaOrComic) return false // manga and comic volumes aren't web novels
    if (book.isbn != null || book.pageCount != null) return false
    val a = book.author?.trim() ?: return true
    if (a.isEmpty()) return true
    val handle = !a.contains(' ') && (a.first().isLowerCase() || a.any { it.isDigit() || it == '_' })
    return handle
}


/**
 * Tabs as a row of chips on a recessed strip; an ink pill slides from the chosen chip to the next
 * on a spring, with a click.
 */
@Composable
internal fun PageTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = Cp.colors
    val view = androidx.compose.ui.platform.LocalView.current
    // The tabs share the whole width equally; the chosen one sits under a lit orange pill that slides across.
    val at by androidx.compose.animation.core.animateFloatAsState(selected.toFloat(), androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 420f), label = "tabAt")
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(CpShapes.pill)
            .background(c.field)
            .drawBehind { drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Black.copy(alpha = if (c.isDark) 0.35f else 0.07f), androidx.compose.ui.graphics.Color.Transparent), endY = 5.dp.toPx())) }
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth(1f / tabs.size.coerceAtLeast(1))
                .height(38.dp)
                .graphicsLayer { translationX = at * size.width }
                .shadow(6.dp, CpShapes.pill, ambientColor = c.accent, spotColor = c.accent)
                .clip(CpShapes.pill)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(lerpColor(c.accent, androidx.compose.ui.graphics.Color.White, 0.15f), c.accent))),
        )
        Row(Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { i, t ->
                val on = i == selected
                val tint by androidx.compose.animation.animateColorAsState(if (on) androidx.compose.ui.graphics.Color.White else c.textMid, Motion.medium(), label = "tabTint")
                Box(
                    Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(CpShapes.pill)
                        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, role = androidx.compose.ui.semantics.Role.Tab) {
                            if (!on) Haptics.tick(view)
                            onSelect(i)
                        }
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(t, style = if (on) CpType.bodyStrong else CpType.label, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}


/** A key on the device: an icon over its name; the signal orange for the one that wants you. */
@Composable
private fun DeviceKey(icon: Int, label: String, modifier: Modifier = Modifier, signal: Boolean = false, onClick: () -> Unit) {
    val kind = if (signal) KeyKind.SIGNAL else KeyKind.PLAIN
    Key(onClick, modifier.height(62.dp), kind = kind, label = label) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LucideIcon(icon, Modifier.size(18.dp), keyText(kind))
            VSpace(4)
            Text(label, style = CpType.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = keyText(kind), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A heading between the parts of a book's page: a small label with a rule running off to the side. */
@Composable
private fun BookSection(title: String) {
    val c = Cp.colors
    VSpace(26)
    Row(verticalAlignment = Alignment.CenterVertically) {
        DeviceLabel(title, color = c.quiet)
        Box(Modifier.padding(start = 10.dp).weight(1f).height(1.dp).background(c.divider))
    }
}
