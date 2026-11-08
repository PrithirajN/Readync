package io.github.prithirajn.trackersync.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.prithirajn.trackersync.ui.theme.AccentTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.prithirajn.trackersync.R
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import io.github.prithirajn.trackersync.ui.theme.CpType
import kotlinx.coroutines.delay

enum class Tab(val label: String, val icon: Int) {
    LIBRARY("Library", R.drawable.lucide_library_big),
    SFF("SFF", R.drawable.lucide_sparkles),
    SERVICES("Services", R.drawable.lucide_link),
    ACTIVITY("Activity", R.drawable.lucide_list_checks),
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
    CompositionLocalProvider(LocalAnimationsOn provides rememberAnimationsOn()) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Cp.colors.page)
    ) {
        // Soft glows in the book's colour drift behind every screen.
        AmbientBackground(Modifier.fillMaxSize())
        Crossfade(state.account == null, animationSpec = Motion.medium(), label = "signedIn") { signedOut ->
            if (signedOut) {
                SignInScreen(vm)
            } else {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
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
                            AppAction.Services -> { AppActions.pending.value = null; pager.animateScrollToPage(Tab.SERVICES.ordinal) }
                            AppAction.Sync -> { AppActions.pending.value = null; vm.syncNow() }
                            AppAction.Releases -> { AppActions.pending.value = null; pager.animateScrollToPage(Tab.ACTIVITY.ordinal) }
                            // The SFF tab takes the action itself (to open the story); here it's only brought up.
                            is AppAction.Sff -> pager.animateScrollToPage(Tab.SFF.ordinal)
                            else -> {
                                if (pager.currentPage != Tab.LIBRARY.ordinal) pager.animateScrollToPage(Tab.LIBRARY.ordinal)
                            }
                        }
                    }
                    HorizontalPager(
                        state = pager,
                        beyondViewportPageCount = Tab.entries.size - 1,
                        modifier = Modifier.weight(1f),
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
                            when (Tab.entries[page]) {
                                Tab.LIBRARY -> LibraryScreen(vm, state, home = libraryHome)
                                Tab.SFF -> SffScreen(vm, state, active = pager.settledPage == page)
                                Tab.SERVICES -> ServicesScreen(vm, state)
                                Tab.ACTIVITY -> ActivityScreen(vm, state)
                            }
                        }
                    }
                    TabBar(Tab.entries[pager.targetPage]) { t ->
                        if (t == Tab.LIBRARY) libraryHome++
                        scope.launch {
                            // Firm and smooth: no overshoot when the whole page moves.
                            pager.animateScrollToPage(t.ordinal, animationSpec = spring(dampingRatio = 1f, stiffness = 300f))
                        }
                    }
                }
            }
        }
        Toaster(vm, Modifier.align(Alignment.BottomCenter))
    }
    }
    }
}

@Composable
private fun TabBar(current: Tab, onSelect: (Tab) -> Unit) {
    val c = Cp.colors
    Column {
        RowDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.page.copy(alpha = 0.88f))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            for (t in Tab.entries) {
                val on = t == current
                val iconTint by animateColorAsState(if (on) c.accentText else c.faint, Motion.medium(), label = "tint")
                val labelTint by animateColorAsState(if (on) c.accentText else c.quiet, Motion.medium(), label = "label")
                val pill by animateColorAsState(if (on) c.accentTint else c.accentTint.copy(alpha = 0f), Motion.medium(), label = "pill")
                val pillWidth by animateDpAsState(if (on) 56.dp else 28.dp, Motion.bouncy(), label = "pillWidth")
                val lift by animateFloatAsState(if (on) 1.08f else 1f, Motion.bouncy(), label = "lift")
                Column(
                    Modifier
                        .weight(1f)
                        .bouncyClickable(CpShapes.control, pressedScale = 0.92f) { onSelect(t) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .height(28.dp)
                            .width(pillWidth)
                            .clip(CpShapes.pill)
                            .background(pill),
                        contentAlignment = Alignment.Center,
                    ) {
                        LucideIcon(t.icon, Modifier.size(20.dp).graphicsLayer { scaleX = lift; scaleY = lift }, iconTint)
                    }
                    Text(t.label, style = CpType.chip.copy(fontSize = CpType.small.fontSize), color = labelTint, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

/** Rise-and-fade toast, like the companion app's Toaster. */
@Composable
private fun Toaster(vm: AppViewModel, modifier: Modifier) {
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
            .padding(start = 16.dp, end = 16.dp, bottom = 80.dp),
    ) {
        val t = toast ?: return@AnimatedVisibility
        val c = Cp.colors
        Column(
            Modifier
                .fillMaxWidth()
                .shadow(8.dp, CpShapes.card)
                .clip(CpShapes.card)
                .background(c.surface)
                .border(1.dp, c.hairline, CpShapes.card)
                .clickable { vm.toast.value = null }
                .padding(horizontal = 16.dp, vertical = 12.dp),
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

/** Page padding used by every tab: px-4, pt-6. */
val PagePadding = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp)

@Composable
fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
fun StatusBarSpacer() = Box(Modifier.statusBarsPadding())
