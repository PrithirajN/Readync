package io.github.prithirajn.readync.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.prithirajn.readync.ui.theme.AccentTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.prithirajn.readync.R
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.CpShapes
import io.github.prithirajn.readync.ui.theme.CpType
import kotlinx.coroutines.delay

/** The tabs, each printed in its own spot ink (an index into the theme's five inks). */
enum class Tab(val label: String, val icon: Int, val ink: Int) {
    LIBRARY("Library", R.drawable.lucide_library_big, 0),
    HIGHLIGHTS("Highlights", R.drawable.lucide_highlighter, 3),
    DISCOVER("Discover", R.drawable.lucide_globe, 1),
    STATS("Stats", R.drawable.lucide_list_checks, 2),
    SYNC("Sync", R.drawable.lucide_link, 4),
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.LIBRARY) }
    // Bumped by each tap on Library in the bottom bar, so the Library goes back to its Reading shelf.
    var libraryHome by remember { mutableStateOf(0) }

    // The app takes its accent from the cover of the book you last made progress in.
    val context = LocalContext.current.applicationContext
    // The icon setting lives with the cover colour too, for the switch when the app is left.
    LaunchedEffect(state.settings.iconFollowsBook) { IconSwitcher.setFollow(context, state.settings.iconFollowsBook) }
    val latestCover = CoverAccent.latestCover(state.books.values)
    val seed by produceState(initialValue = CoverAccent.last(context), latestCover) {
        val url = latestCover ?: return@produceState
        value = withContext(Dispatchers.IO) { CoverAccent.of(context, url) }
    }

    // A short buzz when a sync you're watching finishes.
    val view = LocalView.current
    var wasRunning by remember { mutableStateOf(false) }
    LaunchedEffect(state.running) {
        if (wasRunning && !state.running) {
            view.performHapticFeedback(
                if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM
                else android.view.HapticFeedbackConstants.VIRTUAL_KEY
            )
        }
        wasRunning = state.running
    }

    AccentTheme(seed) {
    CompositionLocalProvider(LocalAnimationsOn provides rememberAnimationsOn(), LocalToasts provides vm) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Cp.colors.page)
    ) {
        Crossfade(state.account == null, animationSpec = Motion.medium(), label = "signedIn") { signedOut ->
            if (signedOut) {
                SignInScreen(vm)
            } else {
                // The thumb cuts run down to the bottom edge, under the gesture bar; the rest keeps clear of the bars.
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.only(androidx.compose.foundation.layout.WindowInsetsSides.Top + androidx.compose.foundation.layout.WindowInsetsSides.Horizontal))) {
                    // All three tabs stay built, side by side, so switching only slides them:
                    // nothing is rebuilt mid-animation. Swiping between tabs works too.
                    val pager = rememberPagerState(initialPage = tab.ordinal) { Tab.entries.size }
                    val scope = rememberCoroutineScope()
                    LaunchedEffect(pager.settledPage) { tab = Tab.entries[pager.settledPage] }
                    // Shortcuts and notifications: go to the right tab (the Library handles its own).
                    val pendingAction by AppActions.pending.collectAsState()
                    LaunchedEffect(pendingAction) {
                        when (pendingAction) {
                            null -> Unit
                            // Each move runs on its own: clearing the action restarts this effect, and a move tied to it
                            // was cut off half way (it stopped on Stats on the way to Sync).
                            AppAction.Services -> { AppActions.pending.value = null; scope.launch { pager.animateScrollToPage(Tab.SYNC.ordinal) } }
                            AppAction.Sync -> { AppActions.pending.value = null; vm.syncNow() }
                            AppAction.Releases -> { AppActions.pending.value = null; DiscoverGo.section.value = DiscoverSection.RELEASES; scope.launch { pager.animateScrollToPage(Tab.DISCOVER.ordinal) } }
                            // Discover's News takes the action itself (to open the story); here it's only brought up.
                            // The move runs on its own: the tab takes the action (and clears it) at once, and a
                            // move tied to this effect was cut off half way (it stopped on Stats on the way to Sync).
                            is AppAction.Sff -> { DiscoverGo.section.value = DiscoverSection.NEWS; scope.launch { pager.animateScrollToPage(Tab.DISCOVER.ordinal) } }
                            // The Sync tab opens the screen itself.
                            is AppAction.ServicePage -> scope.launch { pager.animateScrollToPage(Tab.SYNC.ordinal) }
                            AppAction.Highlights -> { AppActions.pending.value = null; scope.launch { pager.animateScrollToPage(Tab.HIGHLIGHTS.ordinal) } }
                            AppAction.Posts, AppAction.Deals -> {
                                if (pendingAction == AppAction.Posts) { CatalogsGo.mode.value = "Posts"; DiscoverGo.section.value = DiscoverSection.CATALOGS }
                                else DiscoverGo.section.value = DiscoverSection.DEALS
                                AppActions.pending.value = null
                                tab = Tab.DISCOVER
                                // Opened from a share with the app not running: the pager isn't laid out on the
                                // first frame, and a move asked for then is lost. Wait for it, then move.
                                scope.launch {
                                    androidx.compose.runtime.snapshotFlow { pager.layoutInfo.visiblePagesInfo.isNotEmpty() }.first { it }
                                    runCatching { pager.animateScrollToPage(Tab.DISCOVER.ordinal) }
                                    if (pager.currentPage != Tab.DISCOVER.ordinal) pager.scrollToPage(Tab.DISCOVER.ordinal)
                                }
                            }
                            else -> {
                                // The Library takes these (opening a book, say) and clears them straight away.
                                if (pager.currentPage != Tab.LIBRARY.ordinal) scope.launch { pager.animateScrollToPage(Tab.LIBRARY.ordinal) }
                            }
                        }
                    }
                    // The tab you open on is made first, alone, so the app shows up quickly; the others
                    // are made one at a time just after (a moment apart, nearest first), and then all stay
                    // built so switching only slides them. (All five made before the first frame was most
                    // of a slow start.)
                    var built by remember { androidx.compose.runtime.mutableStateOf(setOf(tab.ordinal)) }
                    LaunchedEffect(Unit) {
                        // Past the first frames, so the open tab is on screen and its own entrance has run.
                        androidx.compose.runtime.withFrameNanos { }
                        kotlinx.coroutines.delay(450)
                        val order = Tab.entries.indices.sortedBy { kotlin.math.abs(it - pager.currentPage) }
                        for (i in order) {
                            if (i in built) continue
                            built = built + i
                            // A frame to draw it, then a pause, before the next.
                            androidx.compose.runtime.withFrameNanos { }
                            kotlinx.coroutines.delay(160)
                        }
                    }
                    // A tab gone to before its turn is made at once.
                    LaunchedEffect(pager.targetPage) { if (pager.targetPage !in built) built = built + pager.targetPage }
                    // Swiping between tabs: a small click as each tab takes over (half way across),
                    // only for a swipe of your finger (a tap on the tab bar clicks there already).
                    val swipeView = androidx.compose.ui.platform.LocalView.current
                    LaunchedEffect(pager) {
                        var byFinger = false
                        launch {
                            pager.interactionSource.interactions.collect { if (it is androidx.compose.foundation.interaction.DragInteraction.Start) byFinger = true }
                        }
                        launch {
                            androidx.compose.runtime.snapshotFlow { pager.isScrollInProgress }.collect { moving -> if (!moving) byFinger = false }
                        }
                        var last = pager.currentPage
                        androidx.compose.runtime.snapshotFlow { pager.currentPage }.collect { p ->
                            if (p != last && byFinger) io.github.prithirajn.readync.ui.Haptics.tick(swipeView)
                            last = p
                        }
                    }
                    Box(Modifier.weight(1f)) {
                    HorizontalPager(
                        state = pager,
                        beyondViewportPageCount = Tab.entries.size - 1,
                        modifier = Modifier.fillMaxSize(),
                        key = { Tab.entries[it].name },
                    ) { page ->
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    // A touch of depth while sliding: the page leaving fades and settles back.
                                    val off = kotlin.math.abs((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                                    alpha = 1f - off * 0.35f
                                    val s = 1f - off * 0.04f
                                    scaleX = s
                                    scaleY = s
                                }
                        ) {
                            // Tabs out of sight keep what they last showed: a sync's changes redraw the
                            // tab you're on (and the ones beside it, ready to slide in), not all five.
                            // A tab being gone to catches up as soon as the move starts.
                            val near = kotlin.math.abs(page - pager.currentPage) <= 1 || page == pager.targetPage || page == pager.settledPage
                            val held = remember { androidx.compose.runtime.mutableStateOf(state) }
                            androidx.compose.runtime.SideEffect { if (near && held.value !== state) held.value = state }
                            val pageState = if (near) state else held.value
                            // Each tab printed in its own ink, on paper grained with it.
                            io.github.prithirajn.readync.ui.theme.SectionInk(Tab.entries[page].ink) {
                            CompositionLocalProvider(LocalTabVisible provides (page == pager.settledPage)) {
                                RisoPaper(Modifier.fillMaxSize())
                                if (page in built) when (Tab.entries[page]) {
                                    Tab.LIBRARY -> LibraryScreen(vm, pageState, home = libraryHome)
                                    Tab.HIGHLIGHTS -> HighlightsScreen(vm, pageState)
                                    Tab.DISCOVER -> DiscoverScreen(vm, pageState, active = pager.settledPage == page)
                                    Tab.STATS -> ActivityScreen(vm, pageState)
                                    Tab.SYNC -> ServicesScreen(vm, pageState)
                                }
                            }
                            }
                        }
                    }
                    // The thumb cuts float over the page, which runs on under them and fades into the
                    // paper there, instead of stopping at a hard edge above them.
                    // Debug builds only: how smoothly frames are drawing, small, at the top.
                    if (io.github.prithirajn.readync.FrameWatch.showBadge.value) {
                        val fps by io.github.prithirajn.readync.FrameWatch.line
                        if (fps.isNotEmpty()) Text(
                            fps, fontSize = 9.sp, color = Color.White, maxLines = 2,
                            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 2.dp)
                                .background(Color.Black.copy(alpha = 0.7f))
                                .clickable { io.github.prithirajn.readync.FrameWatch.bench.intValue++ }
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                    TabBar(Tab.entries[pager.targetPage], Modifier.align(Alignment.BottomCenter)) { t ->
                        if (t == Tab.LIBRARY) libraryHome++
                        scope.launch {
                            // Firm and smooth: no overshoot when the whole page moves.
                            pager.animateScrollToPage(t.ordinal, animationSpec = spring(dampingRatio = 1f, stiffness = 300f))
                        }
                    }
                    }
                }
            }
        }
        Toaster(vm, Modifier.align(Alignment.BottomCenter))
        CrashReport()
        DnfSceneOverlay()
        if (state.account != null) FinishCelebrationOverlay(vm, state)
    }
    }
    }
}

/**
 * The thumb index: five cuts along the bottom edge, each in its tab's ink, like the tabs cut into
 * a book's pages. The chosen one rises with a spring and gets a dot; a press pushes a cut down.
 */
@Composable
private fun TabBar(current: Tab, modifier: Modifier = Modifier, onSelect: (Tab) -> Unit) {
    val c = Cp.colors
    val view = androidx.compose.ui.platform.LocalView.current
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Row(
        modifier
            .fillMaxWidth()
            // A soft wash of paper rising behind the cuts: what scrolls under them fades away.
            .drawBehind {
                drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to c.page.copy(alpha = 0f), 0.45f to c.page.copy(alpha = 0.82f), 1f to c.page,
                ))
            }
            .padding(start = 8.dp, end = 8.dp, top = 30.dp)
            .height(62.dp + nav),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (t in Tab.entries) {
            val on = t == current
            val ink = c.inks[t.ink].ink
            val lift by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 420f), label = "cutLift")
            val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            val push by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 900f), label = "cutPush")
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .graphicsLayer {
                        // Unchosen cuts sit lower (their foot runs off the screen); a press pushes one down.
                        translationY = (1f - lift) * 14.dp.toPx() + push * 4.dp.toPx()
                        scaleX = 1f + push * 0.04f
                    }
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(ink)
                    .clickable(interactionSource = source, indication = null, role = androidx.compose.ui.semantics.Role.Tab, onClickLabel = t.label) {
                        if (!on) Haptics.tick(view)
                        onSelect(t)
                    }
                    .padding(top = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LucideIcon(t.icon, Modifier.size(17.dp).graphicsLayer { rotationZ = -8f * push }, c.onAccent)
                Text(t.label, style = CpType.mono.copy(fontSize = CpType.small.fontSize), color = c.onAccent, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                Box(
                    Modifier
                        .padding(top = 3.dp)
                        .size(5.dp)
                        .graphicsLayer { scaleX = lift; scaleY = lift }
                        .clip(CpShapes.pill)
                        .background(c.onAccent),
                )
            }
        }
    }
}

/**
 * After the app closed on its own: what went wrong, offered to share (so it can be fixed) or let go.
 */
@Composable
private fun CrashReport() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var report by remember { mutableStateOf(io.github.prithirajn.readync.CrashLog.pending(context)) }
    val r = report ?: return
    val c = Cp.colors
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { io.github.prithirajn.readync.CrashLog.dismiss(context); report = null },
        containerColor = c.surface,
        title = { Text("Readync closed unexpectedly", style = CpType.bodyStrong, color = c.text) },
        text = {
            Column {
                Text(io.github.prithirajn.readync.CrashLog.headline(r), style = CpType.small, color = c.textMid)
                VSpace(8)
                Text("Share the report so it can be fixed. It says what went wrong in the app and your phone's model, nothing of your books or accounts.", style = CpType.small, color = c.quiet)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton({
                runCatching {
                    context.startActivity(android.content.Intent.createChooser(
                        android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_SUBJECT, "Readync crash report")
                            .putExtra(android.content.Intent.EXTRA_TEXT, r),
                        "Share the report",
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                io.github.prithirajn.readync.CrashLog.dismiss(context); report = null
            }) { Text("Share report", color = c.accentText) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton({ io.github.prithirajn.readync.CrashLog.dismiss(context); report = null }) { Text("Dismiss", color = c.textMid) }
        },
    )
}

/**
 * The view model whose messages pop up as toasts. Sheets (their own windows, over the app) show
 * them too with [SheetToaster], so what you just did is answered where you are.
 */
internal val LocalToasts = androidx.compose.runtime.compositionLocalOf<AppViewModel?> { null }

/** The toast inside a sheet, at its bottom (call it last, in a Box filling the sheet). */
@Composable
internal fun androidx.compose.foundation.layout.BoxScope.SheetToaster() {
    val vm = LocalToasts.current ?: return
    Toaster(vm, Modifier.align(Alignment.BottomCenter), bottom = 16.dp)
}

/** Rise-and-fade toast, like the companion app's Toaster. */
@Composable
internal fun Toaster(vm: AppViewModel, modifier: Modifier, bottom: androidx.compose.ui.unit.Dp = 80.dp) {
    val toast by vm.toast.collectAsState()
    LaunchedEffect(toast?.id) {
        val t = toast
        if (t != null) {
            // A message with Undo stays longer, so there's time to reach it.
            delay(if (t.onUndo != null) 6000 else 3500)
            if (vm.toast.value?.id == t.id) vm.toast.value = null
        }
    }
    AnimatedVisibility(
        visible = toast != null,
        enter = fadeIn(Motion.medium()) + slideInVertically(Motion.bouncy()) { it / 2 } + scaleIn(Motion.medium(), initialScale = 0.96f),
        exit = fadeOut(Motion.fast()) + slideOutVertically(Motion.fast()) { it / 4 },
        modifier = modifier
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = bottom),
    ) {
        val t = toast ?: return@AnimatedVisibility
        val c = Cp.colors
        // A slip of paper with a stripe of ink down its edge (red for a problem), offset over a second ink.
        val stripe = if (t.error) c.error else c.accent
        Column(
            Modifier
                .fillMaxWidth()
                .drawBehind { drawShape(CpShapes.card, c.signalLedge, androidx.compose.ui.geometry.Offset(3.dp.toPx(), 4.dp.toPx())) }
                .clip(CpShapes.card)
                .background(c.raised)
                .drawBehind { drawRect(stripe, size = androidx.compose.ui.geometry.Size(6.dp.toPx(), size.height)) }
                .clickable { vm.toast.value = null }
                .padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = CpType.bodyStrong, color = if (t.error) c.errorText else c.text)
                    t.detail?.let { Quiet(it) }
                }
                t.onUndo?.let { undo ->
                    PillButton("Undo", { vm.toast.value = null; undo() })
                }
            }
        }
    }
}

/** Room the thumb cuts take over the bottom of every page, added under each page's last item. */
val TabBarSpace: androidx.compose.ui.unit.Dp
    @Composable get() = 66.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/** Whether this tab is the one on the screen: endless little motions (a swaying ribbon) rest while it isn't. */
val LocalTabVisible = androidx.compose.runtime.compositionLocalOf { true }

/** Page padding used by every tab: px-4, pt-6. */
val PagePadding = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp)

@Composable
fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
fun StatusBarSpacer() = Box(Modifier.statusBarsPadding())
