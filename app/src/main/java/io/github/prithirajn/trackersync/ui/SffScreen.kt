package io.github.prithirajn.trackersync.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.prithirajn.trackersync.R
import io.github.prithirajn.trackersync.data.SffFeedItem
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.sync.Matcher
import io.github.prithirajn.trackersync.sync.SffArtifactAi
import io.github.prithirajn.trackersync.sync.SffFeed
import io.github.prithirajn.trackersync.sync.SffRadar
import io.github.prithirajn.trackersync.sync.SffRadar.Sector
import io.github.prithirajn.trackersync.sync.SffChart
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import io.github.prithirajn.trackersync.ui.theme.CpType
import io.github.prithirajn.trackersync.ui.theme.Inter
import io.github.prithirajn.trackersync.ui.theme.Lora
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

/** The SFF tab's three views, as Artifact had them. */
private enum class SffView(val label: String) { FOR_YOU("For You"), TOPICS("Topics"), PROFILE("Profile") }

/** Each sector's colour on cards and on the chart. */
internal fun sectorColor(sec: Sector): Color = when (sec) {
    Sector.ORBIT -> Color(0xFF5B8DEF)
    Sector.CITADEL -> Color(0xFFE0A33A)
    Sector.SPIRE -> Color(0xFF3FB68B)
    Sector.VOID -> Color(0xFF9B6BDF)
    Sector.CHRONOS -> Color(0xFFE2683C)
}

/**
 * The SFF tab: every piece of speculative-fiction news in one place, ranked the way Artifact
 * ranked news (see [SffFeed]), and the Star Chart and Astral Probe built on the same taste model
 * (see [SffChart]).
 *
 * For You opens on Today's sky (the day's biggest story, your probe, a highlight of yours to go
 * with it), then the probe's briefing, then the feed: a big hero story, picture cards with the
 * publisher, headline and why it's here, what's new since your last look marked, what you've read
 * dimmed, spoilers blurred, and every eighth story from a sector you rarely read. Topics is the
 * five sectors and the news topics to follow, mute or open. Profile is your Star Chart, level,
 * probe, expedition, constellations, the month's sky, saved stories, history and sources.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SffScreen(vm: AppViewModel, state: Snapshot, active: Boolean = true) {
    val c = Cp.colors
    var view by rememberSaveable { mutableStateOf(SffView.FOR_YOU) }
    var openSector by rememberSaveable { mutableStateOf<String?>(null) }
    var openTopic by rememberSaveable { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf<Pair<SffArtifactAi.SffStoryCluster, Int>?>(null) }
    var menuFor by remember { mutableStateOf<SffFeed.Story?>(null) }

    // A clock for "new", countdowns and the next refresh.
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() / 1000 } }

    // Each visit (the tab shown, or the app back in front with it shown): what's new is since the last one.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, active) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && active) {
                vm.sffVisit()
                now = System.currentTimeMillis() / 1000
                vm.refreshSff(force = false) // only if it's been about an hour
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(active) {
        if (active) {
            vm.sffVisit()
            if (state.sffItems.isEmpty() || now - state.sffUpdatedAt >= SffRadar.REFRESH_INTERVAL_SEC) vm.refreshSff(force = false)
        }
    }

    // Built off the main thread: clustering and ranking a few hundred stories takes a moment.
    val stories by produceState(emptyList<SffFeed.Story>(), state.sffItems, state.sffSky, state.sffProfile, state.books, state.facts, now / 600) {
        value = withContext(Dispatchers.Default) { runCatching { SffFeed.build(state, System.currentTimeMillis() / 1000) }.getOrDefault(emptyList()) }
    }
    val chart by produceState<SffChart.Chart?>(null, state.books, state.facts, state.sffSky, state.releases, now / 600) {
        value = withContext(Dispatchers.Default) { runCatching { SffChart.chart(state, System.currentTimeMillis() / 1000) }.getOrNull() }
    }

    // A story from a notification: open it.
    val pending by AppActions.pending.collectAsState()
    LaunchedEffect(pending, stories) {
        val a = pending as? AppAction.Sff ?: return@LaunchedEffect
        view = SffView.FOR_YOU
        openSector = null; openTopic = null
        val id = a.storyId
        if (id == null) { AppActions.pending.value = null; return@LaunchedEffect }
        val story = stories.firstOrNull { s -> s.cluster.perspectives.any { it.id == id } }
        if (story != null) {
            reading = story.cluster to story.cluster.perspectives.indexOfFirst { it.id == id }.coerceAtLeast(0)
            AppActions.pending.value = null
        } else state.sffItems.firstOrNull { it.id == id }?.let {
            reading = single(it, "From your alerts") to 0
            AppActions.pending.value = null
        }
    }

    val libraryTitles = remember(state.books) { state.books.values.associateBy { Matcher.baseTitle(it.displayTitle) } }
    val listState = rememberLazyListState()

    LazyColumn(Modifier.fillMaxSize().fadeTopEdge(listState.canScrollBackward), state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "head") {
            Column(PagePadding) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow(chart?.className ?: "Speculative fiction")
                        PageTitle("SFF")
                    }
                    ProbePill(state, now) { view = SffView.PROFILE }
                    HSpace(8)
                    IconCircleButton(R.drawable.lucide_refresh_cw, "Refresh the news", { vm.refreshSff(force = true) }, busy = state.sffLoading, tint = c.accentText)
                }
                VSpace(12)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (v in SffView.entries) {
                        val on = v == view && openSector == null && openTopic == null
                        Box(
                            Modifier.clip(CpShapes.pill).background(if (on) c.text else c.divider)
                                .bouncyClickable(CpShapes.pill, pressedScale = 0.94f, role = Role.Tab, onClickLabel = v.label) {
                                    view = v; openSector = null; openTopic = null
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) { Text(v.label, style = CpType.label, color = if (on) c.page else c.textMid) }
                    }
                }
            }
        }

        val sector = openSector?.let { Sector.byId(it) }
        val topic = openTopic?.let { id -> SffFeed.TOPICS.firstOrNull { it.id == id } }
        when {
            sector != null || topic != null -> {
                item(key = "filtered-head") {
                    FilteredHead(state, sector, topic, onBack = { openSector = null; openTopic = null }, vm = vm)
                }
                val shown = stories.filter { s ->
                    (sector == null || sector in s.sectors) && (topic == null || SffFeed.inTopic(s.cluster, topic))
                }
                if (shown.isEmpty()) item(key = "filtered-empty") { EmptyNote("Nothing here yet. New stories come in about every hour.") }
                items(shown, key = { "f-" + it.id }) { s ->
                    StoryCard(s, now, onOpen = { reading = s.cluster to it }, onMenu = { menuFor = s })
                }
            }
            view == SffView.FOR_YOU -> forYou(
                state = state, stories = stories, now = now, vm = vm,
                onOpen = { s, i -> reading = s.cluster to i },
                onOpenItem = { item, why -> reading = single(item, why) to 0 },
                onMenu = { menuFor = it },
                onProfile = { view = SffView.PROFILE },
            )
            view == SffView.TOPICS -> item(key = "topics") {
                SffTopics(vm, state, chart, stories, onSector = { openSector = it.id }, onTopic = { openTopic = it.id })
            }
            else -> item(key = "profile") {
                SffProfile(
                    vm, state, chart, now,
                    onOpenRead = { h ->
                        val item = state.sffItems.firstOrNull { it.id == h.id } ?: state.sffSky.saved.firstOrNull { it.id == h.id }
                            ?: SffFeedItem(id = h.id, category = "NEWS_AWARDS", source = h.source, title = h.title, url = h.url, coverUrl = h.coverUrl)
                        reading = single(item, "From your history") to 0
                    },
                    onOpenItem = { item -> reading = single(item, "Saved") to 0 },
                    onSector = { openSector = it.id },
                )
            }
        }
    }

    reading?.let { (cluster, index) ->
        SffArticleReaderSheet(
            vm = vm, state = state, cluster = cluster, initialPerspectiveIndex = index, libraryBaseTitles = libraryTitles,
            onSelectTag = { reading = null },
            onClose = { secs -> vm.recordSffReadDwell(cluster, secs); reading = null },
        )
    }
    menuFor?.let { s -> StoryMenu(vm, state, s) { menuFor = null } }
}

/** A single story as its own cluster, for the reader. */
internal fun single(item: SffFeedItem, why: String) =
    SffArtifactAi.SffStoryCluster("one-${item.id}", item, listOf(item), item.tags, item.source, why, false, 0.0)

private fun androidx.compose.foundation.lazy.LazyListScope.forYou(
    state: Snapshot,
    stories: List<SffFeed.Story>,
    now: Long,
    vm: AppViewModel,
    onOpen: (SffFeed.Story, Int) -> Unit,
    onOpenItem: (SffFeedItem, String) -> Unit,
    onMenu: (SffFeed.Story) -> Unit,
    onProfile: () -> Unit,
) {
    val today = SffFeed.today(state, stories, now)
    item(key = "today") { TodaySky(vm, state, today, now, onOpen = { today.top?.let { onOpen(it, 0) } }, onProfile = onProfile) }
    if (state.sffSky.briefing.isNotEmpty()) {
        item(key = "briefing") { Briefing(vm, state, onOpenItem) }
    }
    if (state.sffError != null && stories.isEmpty()) {
        item(key = "error") { EmptyNote(state.sffError, action = "Try again") { vm.refreshSff(force = true) } }
    } else if (stories.isEmpty()) {
        item(key = "loading") { EmptyNote(if (state.sffLoading) "Gathering the news from 40-odd sources…" else "Pull the news in with the refresh button.") }
    }
    val hero = SffFeed.hero(stories)
    hero?.let { h -> item(key = "hero") { HeroCard(h, now, onOpen = { onOpen(h, it) }, onMenu = { onMenu(h) }) } }
    val rest = stories.filter { it !== hero }
    val freshCount = rest.count { it.fresh }
    var shownNewLine = false
    var shownSeenLine = false
    for ((i, s) in rest.withIndex()) {
        if (s.fresh && !shownNewLine && freshCount > 0) {
            shownNewLine = true
            item(key = "new-line") { Divider("$freshCount new since ${clock(state.sffSky.prevSeenAt)}", accent = true) }
        }
        if (!s.fresh && shownNewLine && !shownSeenLine) {
            shownSeenLine = true
            item(key = "seen-line") { Divider("Seen before") }
        }
        item(key = "s-" + s.id) {
            StoryCard(s, now, onOpen = { onOpen(s, it) }, onMenu = { onMenu(s) })
        }
        if (i > 200) break
    }
    if (stories.isNotEmpty()) {
        item(key = "caught-up") {
            val next = ((state.sffUpdatedAt + SffRadar.REFRESH_INTERVAL_SEC - now) / 60).coerceAtLeast(1)
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("You're all caught up", style = CpType.bodyStrong, color = Cp.colors.text)
                Quiet("Fresh stories in about $next min. Your probe charges as you read.", color = Cp.colors.faint)
            }
        }
    }
}

private fun clock(at: Long): String =
    if (at <= 0) "your last look" else DateTimeFormatter.ofPattern("h:mm a").format(java.time.Instant.ofEpochSecond(at).atZone(java.time.ZoneId.systemDefault()))

@Composable
private fun Divider(text: String, accent: Boolean = false) {
    val c = Cp.colors
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (accent) Box(Modifier.size(8.dp).clip(CircleShape).background(c.accent))
        if (accent) HSpace(8)
        Text(text, style = CpType.label, color = if (accent) c.accentText else c.quiet)
        HSpace(10)
        Box(Modifier.weight(1f).height(1.dp).background(c.divider))
    }
}

@Composable
private fun EmptyNote(text: String, action: String? = null, onAction: () -> Unit = {}) {
    val c = Cp.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
        CpCard {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                LucideIcon(R.drawable.lucide_sparkles, Modifier.size(22.dp), c.accentText)
                VSpace(8)
                Quiet(text)
                if (action != null) { VSpace(10); PillButton(action, onAction, outlined = false) }
            }
        }
    }
}

/** The probe's charge (or where it is), up by the title; tap for the Star Chart. */
@Composable
private fun ProbePill(state: Snapshot, now: Long, onTap: () -> Unit) {
    val c = Cp.colors
    val sky = state.sffSky
    val text = when {
        SffChart.returned(sky, now) -> "Probe back!"
        sky.probeSector != null -> "Probe ${((sky.probeReturnsAt - now).coerceAtLeast(0) + 59) / 60}m"
        sky.charge >= 100 -> "Probe ready"
        else -> "Probe ${sky.charge}%"
    }
    val hot = SffChart.returned(sky, now) || (sky.probeSector == null && sky.charge >= 100)
    Box(
        Modifier.clip(CpShapes.pill).background(if (hot) c.accent else c.accentTint)
            .bouncyClickable(CpShapes.pill, pressedScale = 0.94f, onClickLabel = "Open the Star Chart") { onTap() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) { Text(text, style = CpType.label, color = if (hot) c.page else c.accentText) }
}

/** Today's sky: the day's biggest story, your probe, a highlight of yours, what's new. */
@Composable
private fun TodaySky(vm: AppViewModel, state: Snapshot, today: SffFeed.Today, now: Long, onOpen: () -> Unit, onProfile: () -> Unit) {
    val c = Cp.colors
    val sky = state.sffSky
    val night = Brush.linearGradient(listOf(Color(0xFF141A33), Color(0xFF2B1F4A), Color(0xFF3B2A3F)))
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(night).padding(18.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Today's sky", style = CpType.eyebrow, color = Color(0xFFF2D9A6))
                Box(Modifier.weight(1f))
                if (today.fresh > 0) {
                    Box(Modifier.clip(CpShapes.pill).background(Color(0x33FFFFFF)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text("${today.fresh} new", style = CpType.small, color = Color.White)
                    }
                }
            }
            today.top?.let { top ->
                VSpace(8)
                Column(Modifier.fillMaxWidth().bouncyClickable(CpShapes.control, pressedScale = 0.98f, onClickLabel = "Read") { onOpen() }.padding(vertical = 4.dp)) {
                    Text(top.lead.source.substringBefore(" ·").uppercase(), style = CpType.chip, color = Color(0xFFB9C3E8))
                    VSpace(2)
                    Text(top.lead.aiHeadline ?: top.lead.title, fontFamily = Lora, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    val outlets = top.cluster.perspectives.map { it.source.substringBefore(" ·") }.distinct().size
                    if (outlets > 1) {
                        VSpace(2)
                        Text("Covered by $outlets outlets", style = CpType.small, color = Color(0xFFB9C3E8))
                    }
                }
            }
            VSpace(12)
            // The probe, right here.
            Row(Modifier.fillMaxWidth().clip(CpShapes.control).background(Color(0x22FFFFFF)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val where = sky.probeSector?.let { Sector.byId(it).title }
                    Text(
                        when {
                            SffChart.returned(sky, now) -> "Your probe is back from $where"
                            where != null -> "Probe exploring $where"
                            sky.charge >= 100 -> "Probe charged: choose where it goes"
                            else -> "Probe charging · ${sky.charge}%"
                        },
                        style = CpType.label, color = Color.White,
                    )
                    VSpace(6)
                    val frac = when {
                        where != null -> (1f - (sky.probeReturnsAt - now).toFloat() / SffChart.PROBE_TRAVEL_SEC).coerceIn(0.03f, 1f)
                        else -> (sky.charge / 100f).coerceIn(0.03f, 1f)
                    }
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(CpShapes.pill).background(Color(0x33FFFFFF))) {
                        Box(Modifier.fillMaxWidth(frac).height(6.dp).clip(CpShapes.pill).background(Color(0xFFF2D9A6)))
                    }
                    if (where == null && sky.charge < 100) {
                        VSpace(4)
                        Text("Charges as you read: pages in any book, or stories read through.", style = CpType.small, color = Color(0xFFB9C3E8))
                    }
                }
                HSpace(10)
                when {
                    SffChart.returned(sky, now) -> PillButton("Bring it in", { vm.claimSffProbe() }, outlined = false)
                    sky.probeSector == null && sky.charge >= 100 -> PillButton("Launch", onProfile, outlined = false)
                }
            }
            today.echo?.let { (quote, book) ->
                VSpace(12)
                Text("“${quote.take(220)}${if (quote.length > 220) "…" else ""}”", fontFamily = Lora, fontSize = 15.sp, lineHeight = 22.sp, color = Color(0xFFEDE6F7))
                Text("— you highlighted this in $book", style = CpType.small, color = Color(0xFFB9C3E8))
            }
            sky.expedition?.takeIf { !it.done }?.let { e ->
                VSpace(12)
                val sec = Sector.byId(e.sector)
                Text(
                    "This week's expedition · ${sec.title}: ${e.articles}/${e.articlesGoal} stories" + if (e.pagesGoal > 0) " · ${e.pages.coerceAtMost(e.pagesGoal)}/${e.pagesGoal} pages" else "",
                    style = CpType.small, color = Color(0xFFF2D9A6),
                )
            }
        }
    }
}

/** What the probe brought back: its stories and a book, side by side. */
@Composable
private fun Briefing(vm: AppViewModel, state: Snapshot, onOpenItem: (SffFeedItem, String) -> Unit) {
    val c = Cp.colors
    val sky = state.sffSky
    val sec = sky.briefingSector?.let { Sector.byId(it) }
    Column(Modifier.padding(top = 16.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(sec?.let { sectorColor(it) } ?: c.accent))
            HSpace(8)
            Column(Modifier.weight(1f)) {
                Text("Probe briefing" + (sec?.let { " · ${it.title}" } ?: ""), style = CpType.bodyStrong, color = c.text)
                sky.briefingSector?.let { sky.revealed[it] }.orEmpty().lastOrNull()?.let { Quiet("Revealed on your chart: $it", color = c.accentText) }
            }
            PillButton("Done", { vm.sffDismissBriefing() })
        }
        VSpace(8)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(sky.briefing, key = { "b-" + it.id }) { item ->
                val read = sky.history.any { it.id == item.id && it.seconds >= 10 }
                Column(
                    Modifier.width(220.dp).clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.hairline, RoundedCornerShape(16.dp))
                        .bouncyClickable(RoundedCornerShape(16.dp), pressedScale = 0.98f, onClickLabel = "Read") { onOpenItem(item, "From your probe's briefing") }
                        .alpha(if (read) 0.55f else 1f),
                ) {
                    if (!item.coverUrl.isNullOrBlank()) StoryImage(item.coverUrl, Modifier.fillMaxWidth().height(110.dp))
                    Column(Modifier.padding(12.dp)) {
                        Text(item.source.substringBefore(" ·"), style = CpType.chip, color = c.accentText)
                        VSpace(2)
                        Text(item.aiHeadline ?: item.title, style = CpType.bodyStrong, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        item.matchedLibraryBook?.takeIf { item.category == "DISCOVERY" }?.let { VSpace(4); Quiet(it, maxLines = 1) }
                    }
                }
            }
        }
    }
}

/** A story's picture, edge to edge, loaded like covers are (and cached the same way). */
@Composable
internal fun StoryImage(url: String?, modifier: Modifier) {
    val c = Cp.colors
    val context = LocalContext.current.applicationContext
    val px = with(LocalDensity.current) { 360.dp.roundToPx() }
    val image by produceState<ImageBitmap?>(url?.let { CoverCache.cached(it)?.asImageBitmap() }, url) {
        if (url != null && value == null) {
            value = withContext(Dispatchers.IO) { runCatching { CoverCache.load(context, url, px) }.getOrNull()?.asImageBitmap() }
        }
    }
    Box(modifier.background(c.cover)) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/** The publisher's badge: its first letter in a small rounded square. */
@Composable
private fun Publisher(source: String, at: Long, tail: String? = null, dim: Boolean = false) {
    val c = Cp.colors
    val name = source.substringBefore(" ·")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(if (dim) c.faint else c.text), contentAlignment = Alignment.Center) {
            Text(name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•", fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = c.page)
        }
        HSpace(6)
        Text(name, style = CpType.small.copy(fontWeight = FontWeight.SemiBold), color = if (dim) c.quiet else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (at > 0) Text(" · " + ago(at * 1000), style = CpType.small, color = c.quiet, maxLines = 1)
        tail?.let { Text(" · $it", style = CpType.small, color = c.quiet, maxLines = 1) }
    }
}

private fun minutesOf(item: SffFeedItem): Int? {
    val words = (item.fullContent ?: item.summary)?.split(Regex("\\s+"))?.size ?: return null
    return (words / 220).coerceAtLeast(1).takeIf { words > 120 }
}

@Composable
private fun WhyLine(s: SffFeed.Story) {
    val c = Cp.colors
    val text = when {
        s.exploring -> "Exploring · ${s.sectors.firstOrNull()?.title ?: "something new"}"
        else -> s.lead.matchedLibraryBook?.removePrefix("✨ On-Device AI Pick · ") ?: s.cluster.whyInFeed.substringBefore(" · ")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        s.sectors.firstOrNull()?.let { Box(Modifier.size(7.dp).clip(CircleShape).background(sectorColor(it))); HSpace(6) }
        Text(text, style = CpType.small, color = if (s.exploring || s.lead.matchedLibraryBook != null) c.accentText else c.quiet, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The day's big story: a full-width picture, a large headline, the publisher, why it's here. */
@Composable
private fun HeroCard(s: SffFeed.Story, now: Long, onOpen: (Int) -> Unit, onMenu: () -> Unit) {
    val c = Cp.colors
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).border(1.dp, c.hairline, RoundedCornerShape(20.dp))
                .bouncyClickable(RoundedCornerShape(20.dp), pressedScale = 0.99f, onClickLabel = "Read") { onOpen(0) },
        ) {
            StoryImage(s.lead.coverUrl, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Column(Modifier.padding(16.dp)) {
                Publisher(s.lead.source, s.lead.publishedAt, minutesOf(s.lead)?.let { "$it min" })
                VSpace(8)
                Text(s.lead.aiHeadline ?: s.lead.title, fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 23.sp, lineHeight = 28.sp, color = c.text)
                s.lead.summary?.let { VSpace(6); Text(it, style = CpType.body, color = c.textMid, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                VSpace(10)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { WhyLine(s) }
                    Outlets(s, onOpen)
                    MenuDots(onMenu)
                }
            }
        }
    }
}

@Composable
private fun Outlets(s: SffFeed.Story, onOpen: (Int) -> Unit) {
    val c = Cp.colors
    val n = s.cluster.perspectives.size
    if (n > 1) {
        Box(
            Modifier.clip(CpShapes.pill).background(c.accentTint)
                .bouncyClickable(CpShapes.pill, pressedScale = 0.94f, onClickLabel = "Other outlets") { onOpen(1) }
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            val outlets = s.cluster.perspectives.map { it.source.substringBefore(" ·") }.distinct().size
            Text(if (outlets > 1) "+${outlets - 1} outlet${if (outlets > 2) "s" else ""}" else "+${n - 1} more", style = CpType.small, color = c.accentText)
        }
    }
}

@Composable
private fun MenuDots(onMenu: () -> Unit) {
    Box(Modifier.size(32.dp).clip(CircleShape).bouncyClickable(CircleShape, pressedScale = 0.9f, onClickLabel = "More") { onMenu() }, contentAlignment = Alignment.Center) {
        LucideIcon(R.drawable.lucide_ellipsis, Modifier.size(16.dp), Cp.colors.quiet)
    }
}

/** A story in the feed, Artifact-style: picture, publisher, headline, why it's here; dimmed once read; spoilers blurred. */
@Composable
private fun StoryCard(s: SffFeed.Story, now: Long, onOpen: (Int) -> Unit, onMenu: () -> Unit) {
    val c = Cp.colors
    val lead = s.lead
    var revealed by remember(s.id) { mutableStateOf(false) }
    val hidden = s.spoiler && !revealed
    val bookish = lead.category == "DISCOVERY" || lead.category == "SERIES_HORIZON"
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).alpha(if (s.read) 0.55f else 1f)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.hairline, RoundedCornerShape(18.dp))
                .bouncyClickable(RoundedCornerShape(18.dp), pressedScale = 0.99f, onClickLabel = "Read ${lead.title}") { if (hidden) revealed = true else onOpen(0) },
        ) {
            if (!bookish && !lead.coverUrl.isNullOrBlank()) {
                Box {
                    StoryImage(lead.coverUrl, Modifier.fillMaxWidth().height(170.dp).let { if (hidden) it.blur(18.dp) else it })
                    if (s.fresh && !s.read) NewBadge(Modifier.align(Alignment.TopStart).padding(10.dp))
                }
            }
            Row(Modifier.padding(14.dp)) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { Publisher(lead.source, lead.publishedAt, minutesOf(lead)?.let { "$it min" }, dim = s.read) }
                        if (s.fresh && !s.read && (bookish || lead.coverUrl.isNullOrBlank())) NewBadge()
                    }
                    VSpace(6)
                    Text(
                        lead.aiHeadline ?: lead.title,
                        fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 22.sp,
                        color = c.text, maxLines = 4, overflow = TextOverflow.Ellipsis,
                        modifier = if (hidden) Modifier.blur(8.dp) else Modifier,
                    )
                    if (hidden) {
                        VSpace(6)
                        Row(Modifier.clip(CpShapes.pill).background(c.divider).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            LucideIcon(R.drawable.lucide_eye_off, Modifier.size(13.dp), c.warn)
                            HSpace(6)
                            Text("May spoil a book you're reading · tap to show", style = CpType.small, color = c.textMid)
                        }
                    } else if (bookish) {
                        lead.subtitle?.let { VSpace(2); Quiet(it, maxLines = 1) }
                    }
                    VSpace(8)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { WhyLine(s) }
                        s.reaction?.let { Text(it, style = CpType.body); HSpace(4) }
                        if (s.saved) { LucideIcon(R.drawable.lucide_book_plus, Modifier.size(14.dp), c.accentText); HSpace(4) }
                        Outlets(s, onOpen)
                        MenuDots(onMenu)
                    }
                }
                if (bookish && !lead.coverUrl.isNullOrBlank()) {
                    HSpace(12)
                    BookCover(lead.coverUrl, lead.title, 58.dp, 86.dp)
                }
            }
        }
    }
}

@Composable
private fun NewBadge(modifier: Modifier = Modifier) {
    val c = Cp.colors
    Box(modifier.clip(CpShapes.pill).background(c.accent).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text("NEW", style = CpType.chip, color = c.page)
    }
}

/** A story's other actions: not interested, hide the publisher, follow or mute its sector, save, share. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoryMenu(vm: AppViewModel, state: Snapshot, s: SffFeed.Story, onClose: () -> Unit) {
    val c = Cp.colors
    val context = LocalContext.current
    ModalBottomSheet(dragHandle = { SheetGrip() }, onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), shape = CpShapes.sheet, containerColor = c.page) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text(s.lead.aiHeadline ?: s.lead.title, style = CpType.bodyStrong, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Quiet(s.lead.source.substringBefore(" ·"))
            VSpace(14)
            fun row(label: String, note: String? = null, act: () -> Unit) = Pair(label to note, act)
            val publisher = s.lead.source.substringBefore(" ·")
            val actions = buildList {
                add(row(if (s.saved) "Remove from Saved" else "Save for later") { vm.sffSave(s.lead) })
                add(row("More like this", "Lifts its topics in your feed") { vm.sffFeedback(s.cluster, true) })
                add(row("Not interested", "Fewer stories like it") { vm.sffFeedback(s.cluster, false) })
                for (sec in s.sectors) {
                    val following = sec.id in state.sffSky.followed
                    add(row(if (following) "Stop following ${sec.title}" else "Follow ${sec.title}", sec.subtitle) { vm.sffFollow(sec.id, sec.title) })
                    add(row(if (sec.id in state.sffSky.mutedSectors) "Unmute ${sec.title}" else "Mute ${sec.title}") { vm.sffMute(sec.id, sec.title) })
                }
                add(row(if (publisher in state.sffSky.blockedSources) "Show $publisher again" else "Hide $publisher") { vm.sffBlockSource(publisher) })
                s.lead.url?.let { u ->
                    add(row("Share") {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_TEXT, "${s.lead.aiHeadline ?: s.lead.title}\n$u")
                        runCatching { context.startActivity(android.content.Intent.createChooser(send, "Share story")) }
                    })
                }
            }
            for ((text, act) in actions) {
                Column(
                    Modifier.fillMaxWidth().clip(CpShapes.control).bouncyClickable(CpShapes.control, pressedScale = 0.98f, onClickLabel = text.first) { act(); onClose() }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                ) {
                    Text(text.first, style = CpType.label, color = c.text)
                    text.second?.let { Quiet(it, color = c.faint) }
                }
            }
        }
    }
}

/** The head of one sector's or topic's own feed: back, its name, follow and mute. */
@Composable
private fun FilteredHead(state: Snapshot, sector: Sector?, topic: SffFeed.Topic?, onBack: () -> Unit, vm: AppViewModel) {
    val c = Cp.colors
    val id = sector?.id ?: topic?.id ?: return
    val name = sector?.title ?: topic?.label ?: ""
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).bouncyClickable(CircleShape, onClickLabel = "Back") { onBack() }, contentAlignment = Alignment.Center) {
                LucideIcon(R.drawable.lucide_chevron_left, Modifier.size(20.dp), c.text)
            }
            HSpace(6)
            Column(Modifier.weight(1f)) {
                Text(name, style = CpType.section, color = c.text)
                sector?.let { Quiet(it.subtitle) }
            }
        }
        VSpace(8)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val following = id in state.sffSky.followed
            PillButton(if (following) "Following" else "Follow", { vm.sffFollow(id, name) }, outlined = !following)
            if (sector != null) {
                val muted = id in state.sffSky.mutedSectors
                PillButton(if (muted) "Unmute" else "Mute", { vm.sffMute(id, name) })
            }
        }
    }
}

/** Topics: the five sectors (your strength, how much you've explored) and the news topics. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SffTopics(vm: AppViewModel, state: Snapshot, chart: SffChart.Chart?, stories: List<SffFeed.Story>, onSector: (Sector) -> Unit, onTopic: (SffFeed.Topic) -> Unit) {
    val c = Cp.colors
    Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
        SectionTitle("Sectors")
        Quiet("Your strength in each grows with what you read; the feed leans toward it. Follow to see more, mute to see none.")
        VSpace(10)
        for (sec in Sector.entries) {
            val v = chart?.sectors?.firstOrNull { it.sector == sec }
            val count = stories.count { sec in it.sectors && !it.read }
            Column(
                Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(16.dp)).background(c.surface)
                    .border(1.dp, c.hairline, RoundedCornerShape(16.dp))
                    .bouncyClickable(RoundedCornerShape(16.dp), pressedScale = 0.99f, onClickLabel = "Open ${sec.title}") { onSector(sec) }
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(sectorColor(sec)))
                    HSpace(8)
                    Column(Modifier.weight(1f)) {
                        Text(sec.title, style = CpType.bodyStrong, color = c.text)
                        Quiet(sec.subtitle, maxLines = 1)
                    }
                    if (count > 0) Chip("$count unread", color = c.accentText, background = c.accentTint)
                }
                VSpace(10)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${v?.rank ?: "Initiate"} · ${v?.xp ?: 0} XP · ${((v?.explored ?: 0f) * 100).toInt()}% explored", style = CpType.small, color = c.textMid)
                        VSpace(4)
                        ProgressBar(((v?.strength ?: 0.0).toFloat()).coerceIn(0.03f, 1f))
                    }
                    HSpace(10)
                    val following = sec.id in state.sffSky.followed
                    val muted = sec.id in state.sffSky.mutedSectors
                    PillButton(if (following) "Following" else if (muted) "Muted" else "Follow", {
                        if (muted) vm.sffMute(sec.id, sec.title) else vm.sffFollow(sec.id, sec.title)
                    }, outlined = !following)
                }
            }
        }
        VSpace(10)
        SectionTitle("Topics")
        VSpace(8)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in SffFeed.TOPICS) {
                val n = stories.count { SffFeed.inTopic(it.cluster, t) && !it.read }
                val following = t.id in state.sffSky.followed
                Row(
                    Modifier.clip(CpShapes.pill).background(if (following) c.accentTint else c.surface).border(1.dp, c.hairline, CpShapes.pill)
                        .bouncyClickable(CpShapes.pill, pressedScale = 0.95f, onClickLabel = "Open ${t.label}") { onTopic(t) }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(t.label, style = CpType.label, color = if (following) c.accentText else c.text)
                    if (n > 0) { HSpace(6); Text("$n", style = CpType.small, color = c.quiet) }
                }
            }
        }
    }
}

/** Sizes for pictures of covers, so the probe and chart cards match the feed. */
internal val CoverW: Dp = 44.dp
internal val CoverH: Dp = 66.dp
