package io.github.prithirajn.readync.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.prithirajn.readync.R
import io.github.prithirajn.readync.data.SffFeedItem
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.sync.Matcher
import io.github.prithirajn.readync.sync.SffArtifactAi
import io.github.prithirajn.readync.sync.SffFeed
import io.github.prithirajn.readync.sync.SffRadar
import io.github.prithirajn.readync.sync.SffRadar.Sector
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.CpShapes
import io.github.prithirajn.readync.ui.theme.CpType
import io.github.prithirajn.readync.ui.theme.Inter
import io.github.prithirajn.readync.ui.theme.Lora
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** The SFF tab's views: ranked for you, newest first, by genre, and what you've kept. */
private enum class SffView(val label: String) { FOR_YOU("For you"), LATEST("Latest"), SCREEN("Screen"), GENRES("Genres"), SAVED("Saved") }

/** Each genre's colour, for its name over a story and its dot. */
internal fun sectorColor(sec: Sector): Color = when (sec) {
    Sector.ORBIT -> Color(0xFF5B8DEF)
    Sector.CITADEL -> Color(0xFFE0A33A)
    Sector.SPIRE -> Color(0xFF3FB68B)
    Sector.VOID -> Color(0xFF9B6BDF)
    Sector.CHRONOS -> Color(0xFFE2683C)
}

/** A genre by its plain name (the feed's sectors, without the star-chart names). */
internal fun genreName(sec: Sector): String = when (sec) {
    Sector.ORBIT -> "Science fiction"
    Sector.CITADEL -> "Fantasy"
    Sector.SPIRE -> "Progression & LitRPG"
    Sector.VOID -> "Horror & weird"
    Sector.CHRONOS -> "Dystopia & time travel"
}

/**
 * The SFF tab: speculative-fiction news from a few dozen outlets, clubbed by story and ranked for
 * you (see [SffFeed]), set like a magazine. For you opens on the lead story across a big picture,
 * then the top stories, what's new about books on your shelves, new books as covers, and the rest
 * as a list, with a wide picture now and then. Latest is everything newest first; Screen is SFF films,
 * series and adaptations; Genres is the five
 * genres and the news topics to follow, mute or open; Saved is what you've kept, what you've read,
 * alerts and sources.
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

    // A clock for "new" and the next refresh.
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
    val open: (SffFeed.Story, Int) -> Unit = { s, i -> reading = s.cluster to i }

    LazyColumn(Modifier.fillMaxSize().fadeTopEdge(listState.canScrollBackward), state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "head") {
            Column(PagePadding) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val today = LocalDate.now()
                        Eyebrow(today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()) + ", " + today.dayOfMonth + " " + today.month.getDisplayName(TextStyle.FULL, Locale.getDefault()))
                        PageTitle("SFF")
                    }
                    IconCircleButton(R.drawable.lucide_refresh_cw, "Refresh the news", { vm.refreshSff(force = true) }, busy = state.sffLoading, tint = c.accentText)
                }
                VSpace(12)
                Segmented(SffView.entries, view, { it.label }, { view = it; openSector = null; openTopic = null }, Modifier.fillMaxWidth())
            }
        }

        val sector = openSector?.let { Sector.byId(it) }
        val topic = openTopic?.let { id -> SffFeed.TOPICS.firstOrNull { it.id == id } }
        when {
            sector != null || topic != null -> {
                item(key = "filtered-head") { FilteredHead(state, sector, topic, onBack = { openSector = null; openTopic = null }, vm = vm) }
                val shown = stories.filter { s -> (sector == null || sector in s.sectors) && (topic == null || SffFeed.inTopic(s.cluster, topic)) }
                if (shown.isEmpty()) item(key = "filtered-empty") { EmptyNote("Nothing here yet. New stories come in about every hour.") }
                shown.forEachIndexed { i, s ->
                    item(key = "f-" + s.id) {
                        if (i == 0 && !s.lead.coverUrl.isNullOrBlank()) WideStory(s, open, { menuFor = s }) else ListStory(s, open, { menuFor = s })
                    }
                }
            }
            view == SffView.FOR_YOU -> forYou(state, stories, vm, open, onMenu = { menuFor = it })
            view == SffView.LATEST -> {
                val latest = stories.sortedByDescending { it.lead.publishedAt.takeIf { t -> t > 0 } ?: it.lead.firstSeenAt }
                if (latest.isEmpty()) item(key = "latest-empty") { EmptyNote(if (state.sffLoading) "Gathering the news…" else "Pull the news in with the refresh button.") }
                var lastDay: LocalDate? = null
                for (s in latest) {
                    val at = s.lead.publishedAt.takeIf { it > 0 } ?: s.lead.firstSeenAt
                    val d = if (at > 0) java.time.Instant.ofEpochSecond(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate() else null
                    if (d != null && d != lastDay) {
                        lastDay = d
                        item(key = "day-$d") { SectionHead(dayLabel(d)) }
                    }
                    item(key = "l-" + s.id) { ListStory(s, open, { menuFor = s }) }
                }
            }
            view == SffView.SCREEN -> {
                // SFF on screen: films, series and adaptations, best first, with a big picture now and then.
                val screen = stories.filter { SffFeed.isScreen(it.cluster) }
                item(key = "screen-head") { SectionHead("Films and series") }
                if (screen.isEmpty()) item(key = "screen-empty") {
                    EmptyNote(if (state.sffLoading) "Gathering film and TV news…" else "No film or TV news yet. New stories come in about every hour.")
                }
                val unread = screen.filter { !it.read }
                val seen = screen.filter { it.read }
                unread.forEachIndexed { i, s ->
                    item(key = "s-" + s.id) {
                        when {
                            i == 0 && !s.lead.coverUrl.isNullOrBlank() -> LeadStory(s, open) { menuFor = s }
                            i % 6 == 3 && !s.lead.coverUrl.isNullOrBlank() -> WideStory(s, open, { menuFor = s })
                            else -> ListStory(s, open, { menuFor = s })
                        }
                    }
                }
                if (seen.isNotEmpty()) {
                    item(key = "screen-read") { SectionHead("Already read") }
                    for (s in seen.take(20)) item(key = "sr-" + s.id) { ListStory(s, open, { menuFor = s }) }
                }
            }
            view == SffView.GENRES -> item(key = "genres") {
                SffGenres(vm, state, stories, onSector = { openSector = it.id }, onTopic = { openTopic = it.id })
            }
            else -> item(key = "saved") {
                SffSaved(
                    vm, state,
                    onOpenRead = { h ->
                        val item = state.sffItems.firstOrNull { it.id == h.id } ?: state.sffSky.saved.firstOrNull { it.id == h.id }
                            ?: SffFeedItem(id = h.id, category = "NEWS_AWARDS", source = h.source, title = h.title, url = h.url, coverUrl = h.coverUrl)
                        reading = single(item, "From your history") to 0
                    },
                    onOpenItem = { item -> reading = single(item, "Saved") to 0 },
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

private fun isBookish(s: SffFeed.Story) = s.lead.category == "DISCOVERY" || s.lead.category == "SERIES_HORIZON"

/**
 * For you, as a front page: the lead across a big picture, three top stories, what's new about books
 * on your shelves, new books as covers, then the rest as a list (a wide picture every sixth).
 */
private fun androidx.compose.foundation.lazy.LazyListScope.forYou(
    state: Snapshot,
    stories: List<SffFeed.Story>,
    vm: AppViewModel,
    onOpen: (SffFeed.Story, Int) -> Unit,
    onMenu: (SffFeed.Story) -> Unit,
) {
    if (state.sffError != null && stories.isEmpty()) {
        item(key = "error") { EmptyNote(state.sffError, action = "Try again") { vm.refreshSff(force = true) } }
        return
    }
    if (stories.isEmpty()) {
        item(key = "loading") { EmptyNote(if (state.sffLoading) "Gathering the news from 40-odd sources…" else "Pull the news in with the refresh button.") }
        return
    }
    val news = stories.filter { !isBookish(it) }
    val books = stories.filter { isBookish(it) && !it.lead.coverUrl.isNullOrBlank() }
    val lead = SffFeed.hero(news) ?: news.firstOrNull { !it.lead.coverUrl.isNullOrBlank() } ?: news.firstOrNull()
    val shelves = news.filter { it !== lead && it.lead.matchedLibraryBook != null && !it.read }.take(6)
    val rest = news.filter { it !== lead && it !in shelves }
    val top = rest.take(3)
    val more = rest.drop(3)

    lead?.let { s -> item(key = "lead") { LeadStory(s, onOpen) { onMenu(s) } } }
    if (top.isNotEmpty()) {
        item(key = "top-head") { SectionHead("Top stories") }
        for (s in top) item(key = "t-" + s.id) { TopStory(s, onOpen) { onMenu(s) } }
    }
    if (shelves.isNotEmpty()) {
        item(key = "shelves") {
            Column {
                SectionHead("About your books")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(shelves, key = { "sh-" + it.id }) { s -> ShelfStory(s, onOpen) }
                }
            }
        }
    }
    if (books.isNotEmpty()) {
        item(key = "books") {
            Column {
                SectionHead("New books")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(books.take(16), key = { "b-" + it.id }) { s -> BookStory(s, onOpen) }
                }
            }
        }
    }
    if (more.isNotEmpty()) {
        item(key = "more-head") { SectionHead("More news") }
        more.take(200).forEachIndexed { i, s ->
            item(key = "m-" + s.id) {
                if (i % 6 == 5 && !s.lead.coverUrl.isNullOrBlank() && !s.spoiler) WideStory(s, onOpen) { onMenu(s) }
                else ListStory(s, onOpen) { onMenu(s) }
            }
        }
    }
    item(key = "caught-up") {
        val next = ((state.sffUpdatedAt + SffRadar.REFRESH_INTERVAL_SEC - System.currentTimeMillis() / 1000) / 60).coerceAtLeast(1)
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("That's everything", style = CpType.bodyStrong, color = Cp.colors.text)
            Quiet("Fresh stories in about $next min.", color = Cp.colors.faint)
        }
    }
}

private fun dayLabel(d: LocalDate): String {
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()) + ", " + d.dayOfMonth + " " + d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    }
}

/** A section's name, set like a magazine's: bold, with a short rule in the accent beside it. */
@Composable
private fun SectionHead(text: String) {
    val c = Cp.colors
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 26.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, color = c.text)
        Box(Modifier.padding(start = 12.dp).weight(1f).height(1.dp).background(c.divider))
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

/** A story's picture, edge to edge, loaded like covers are (and cached the same way). */
@Composable
internal fun StoryImage(url: String?, modifier: Modifier) {
    val c = Cp.colors
    val context = LocalContext.current.applicationContext
    val px = with(LocalDensity.current) { 420.dp.roundToPx() }
    val image by produceState<ImageBitmap?>(url?.let { CoverCache.cached(it)?.asImageBitmap() }, url) {
        // A new story in the same slot: its own picture (or none), never the last one's.
        value = url?.let { CoverCache.cached(it)?.asImageBitmap() }
        if (url != null && value == null) {
            value = withContext(Dispatchers.IO) { runCatching { CoverCache.load(context, url, px) }.getOrNull()?.asImageBitmap() }
        }
    }
    Box(modifier.background(c.cover)) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

private fun minutesOf(item: SffFeedItem): Int? {
    val words = (item.fullContent ?: item.summary)?.split(Regex("\\s+"))?.size ?: return null
    return (words / 220).coerceAtLeast(1).takeIf { words > 120 }
}

private fun headline(s: SffFeed.Story) = s.lead.aiHeadline ?: s.lead.title

/** "Tor.com · 3h · 4 min", with "+2 outlets" when others carry it. */
private fun metaLine(s: SffFeed.Story): String {
    val lead = s.lead
    val outlets = s.cluster.perspectives.map { it.source.substringBefore(" ·") }.distinct().size
    return listOfNotNull(
        lead.source.substringBefore(" ·"),
        lead.publishedAt.takeIf { it > 0 }?.let { ago(it * 1000) },
        minutesOf(lead)?.let { "$it min" },
        (outlets - 1).takeIf { it > 0 }?.let { "+$it outlet${if (it > 1) "s" else ""}" },
    ).joinToString("  ·  ")
}

/** Above a headline: its genre in its colour (with a dot when it's new), or why it's here. */
@Composable
private fun Kicker(s: SffFeed.Story, onDark: Boolean = false) {
    val c = Cp.colors
    val sec = s.sectors.firstOrNull()
    val why = s.lead.matchedLibraryBook?.removePrefix("✨ On-Device AI Pick · ")
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (s.fresh && !s.read) { Box(Modifier.size(7.dp).clip(CircleShape).background(c.accent)); HSpace(6) }
        Text(
            why ?: sec?.let { genreName(it) } ?: "News",
            fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.5.sp,
            color = when { onDark -> Color.White.copy(alpha = 0.85f); why != null -> c.accentText; sec != null -> sectorColor(sec); else -> c.quiet },
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The lead story: its picture across the page, darkened at the foot, with the headline set on it. */
@Composable
private fun LeadStory(s: SffFeed.Story, onOpen: (SffFeed.Story, Int) -> Unit, onMenu: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp).fillMaxWidth().aspectRatio(0.86f).clip(shape)
            .bouncyClickable(shape, pressedScale = 0.99f, onClickLabel = "Read") { onOpen(s, 0) },
    ) {
        StoryImage(s.lead.coverUrl, Modifier.fillMaxSize().let { if (s.spoiler) it.blur(20.dp) else it })
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color(0x22000000), 1f to Color(0xE6000000))))
        Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
            Kicker(s, onDark = true)
            VSpace(8)
            Text(
                headline(s), fontFamily = Lora, fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 33.sp,
                color = Color.White, maxLines = 5, overflow = TextOverflow.Ellipsis,
                modifier = if (s.spoiler) Modifier.blur(8.dp) else Modifier,
            )
            s.lead.summary?.takeIf { !s.spoiler }?.let {
                VSpace(8)
                Text(it, fontFamily = Inter, fontSize = 14.5.sp, lineHeight = 20.sp, color = Color.White.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            VSpace(12)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(metaLine(s), style = CpType.small, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0x33FFFFFF)).bouncyClickable(CircleShape, pressedScale = 0.9f, onClickLabel = "More") { onMenu() }, contentAlignment = Alignment.Center) {
                    LucideIcon(R.drawable.lucide_ellipsis, Modifier.size(16.dp), Color.White)
                }
            }
        }
    }
}

/** A top story: serif headline with two lines of its summary, its picture square beside it. */
@Composable
private fun TopStory(s: SffFeed.Story, onOpen: (SffFeed.Story, Int) -> Unit, onMenu: () -> Unit) {
    val c = Cp.colors
    StoryRow(s, onOpen, onMenu) {
        Row {
            Column(Modifier.weight(1f)) {
                Kicker(s)
                VSpace(6)
                Text(headline(s), fontFamily = Lora, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp, color = c.text, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = spoilerBlur(s))
                s.lead.summary?.takeIf { !s.spoiler }?.let {
                    VSpace(6)
                    Text(it, fontFamily = Inter, fontSize = 14.sp, lineHeight = 19.sp, color = c.textMid, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!s.lead.coverUrl.isNullOrBlank()) {
                HSpace(14)
                StoryImage(s.lead.coverUrl, Modifier.size(104.dp).clip(RoundedCornerShape(16.dp)).let { if (s.spoiler) it.blur(14.dp) else it })
            }
        }
    }
}

/** A story in the list: small picture to the left, genre, headline, where it's from. */
@Composable
private fun ListStory(s: SffFeed.Story, onOpen: (SffFeed.Story, Int) -> Unit, onMenu: () -> Unit) {
    val c = Cp.colors
    StoryRow(s, onOpen, onMenu) {
        Row {
            if (!s.lead.coverUrl.isNullOrBlank()) {
                if (isBookish(s)) BookCover(s.lead.coverUrl, s.lead.title, 56.dp, 84.dp)
                else StoryImage(s.lead.coverUrl, Modifier.size(84.dp).clip(RoundedCornerShape(14.dp)).let { if (s.spoiler) it.blur(12.dp) else it })
                HSpace(14)
            }
            Column(Modifier.weight(1f)) {
                Kicker(s)
                VSpace(4)
                Text(headline(s), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = spoilerBlur(s))
                if (isBookish(s)) s.lead.subtitle?.let { VSpace(2); Quiet(it, maxLines = 1) }
            }
        }
    }
}

/** Now and then in the list: the picture across, the headline under it. */
@Composable
private fun WideStory(s: SffFeed.Story, onOpen: (SffFeed.Story, Int) -> Unit, onMenu: () -> Unit) {
    val c = Cp.colors
    StoryRow(s, onOpen, onMenu) {
        Column {
            StoryImage(s.lead.coverUrl, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp)))
            VSpace(12)
            Kicker(s)
            VSpace(6)
            Text(headline(s), fontFamily = Lora, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 27.sp, color = c.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
            s.lead.summary?.let { VSpace(6); Text(it, fontFamily = Inter, fontSize = 14.sp, lineHeight = 19.sp, color = c.textMid, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

private fun spoilerBlur(s: SffFeed.Story) = if (s.spoiler) Modifier.blur(7.dp) else Modifier

/**
 * The frame every story in the list shares: tap to read (a spoiler shows first), dimmed once
 * read, its meta line and menu under it, a hairline between stories.
 */
@Composable
private fun StoryRow(s: SffFeed.Story, onOpen: (SffFeed.Story, Int) -> Unit, onMenu: () -> Unit, content: @Composable () -> Unit) {
    val c = Cp.colors
    var revealed by remember(s.id) { mutableStateOf(false) }
    val hidden = s.spoiler && !revealed
    Column(
        Modifier.fillMaxWidth()
            .bouncyClickable(RoundedCornerShape(0.dp), pressedScale = 0.99f, onClickLabel = "Read ${s.lead.title}") { if (hidden) revealed = true else onOpen(s, 0) }
            .padding(start = 16.dp, end = 10.dp, top = 14.dp)
            .alpha(if (s.read) 0.5f else 1f),
    ) {
        Box(Modifier.padding(end = 6.dp)) { content() }
        if (hidden) {
            VSpace(8)
            Row(Modifier.clip(CpShapes.pill).background(c.divider).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                LucideIcon(R.drawable.lucide_eye_off, Modifier.size(13.dp), c.warn)
                HSpace(6)
                Text("May spoil a book you're reading · tap to show", style = CpType.small, color = c.textMid)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(metaLine(s), style = CpType.small, color = c.quiet, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (s.saved) { LucideIcon(R.drawable.lucide_book_plus, Modifier.size(14.dp), c.accentText); HSpace(4) }
            s.reaction?.let { Text(it, style = CpType.small); HSpace(4) }
            Box(Modifier.size(36.dp).clip(CircleShape).bouncyClickable(CircleShape, pressedScale = 0.9f, onClickLabel = "More") { onMenu() }, contentAlignment = Alignment.Center) {
                LucideIcon(R.drawable.lucide_ellipsis, Modifier.size(16.dp), c.quiet)
            }
        }
        Box(Modifier.padding(end = 6.dp).fillMaxWidth().height(1.dp).background(c.divider))
    }
}

/** News about a book on your shelves: its cover small, which book, the headline. */
@Composable
private fun ShelfStory(s: SffFeed.Story, onOpen: (SffFeed.Story, Int) -> Unit) {
    val c = Cp.colors
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.width(260.dp).clip(shape).background(c.surface).border(1.dp, c.hairline, shape)
            .bouncyClickable(shape, pressedScale = 0.98f, onClickLabel = "Read") { onOpen(s, 0) },
    ) {
        if (!s.lead.coverUrl.isNullOrBlank()) StoryImage(s.lead.coverUrl, Modifier.fillMaxWidth().height(120.dp).let { if (s.spoiler) it.blur(16.dp) else it })
        Column(Modifier.padding(14.dp)) {
            Kicker(s)
            VSpace(6)
            Text(headline(s), fontFamily = Lora, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = spoilerBlur(s))
            VSpace(6)
            Text(s.lead.source.substringBefore(" ·"), style = CpType.small, color = c.quiet, maxLines = 1)
        }
    }
}

/** A new book: its cover, title, author, and when it's out. */
@Composable
private fun BookStory(s: SffFeed.Story, onOpen: (SffFeed.Story, Int) -> Unit) {
    val c = Cp.colors
    Column(
        Modifier.width(116.dp).clip(RoundedCornerShape(12.dp)).bouncyClickable(RoundedCornerShape(12.dp), pressedScale = 0.97f, onClickLabel = "Open ${s.lead.title}") { onOpen(s, 0) }
            .alpha(if (s.read) 0.55f else 1f),
    ) {
        BookCover(s.lead.coverUrl, s.lead.title, 116.dp, 174.dp)
        VSpace(8)
        Text(s.lead.title, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, lineHeight = 17.sp, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(s.lead.author, s.lead.releaseDate ?: s.lead.year?.toString()).joinToString(" · "), style = CpType.small, color = c.quiet, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A story's other actions: save, more or less like it, follow or mute its genre, hide the publisher, share. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoryMenu(vm: AppViewModel, state: Snapshot, s: SffFeed.Story, onClose: () -> Unit) {
    val c = Cp.colors
    val context = LocalContext.current
    GuardedSheet(dragHandle = { SheetGrip() }, onDismissRequest = onClose, sheetState = rememberSheetState(skipPartiallyExpanded = true), shape = CpShapes.sheet, containerColor = c.page) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text(headline(s), fontFamily = Lora, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Quiet(s.lead.source.substringBefore(" ·"))
            VSpace(14)
            fun row(label: String, note: String? = null, act: () -> Unit) = Pair(label to note, act)
            val publisher = s.lead.source.substringBefore(" ·")
            val actions = buildList {
                add(row(if (s.saved) "Remove from Saved" else "Save for later") { vm.sffSave(s.lead) })
                add(row("More like this", "Lifts its topics in your feed") { vm.sffFeedback(s.cluster, true) })
                add(row("Not interested", "Fewer stories like it") { vm.sffFeedback(s.cluster, false) })
                for (sec in s.sectors) {
                    val name = genreName(sec)
                    val following = sec.id in state.sffSky.followed
                    add(row(if (following) "Stop following $name" else "Follow $name") { vm.sffFollow(sec.id, name) })
                    add(row(if (sec.id in state.sffSky.mutedSectors) "Unmute $name" else "Mute $name") { vm.sffMute(sec.id, name) })
                }
                add(row(if (publisher in state.sffSky.blockedSources) "Show $publisher again" else "Hide $publisher") { vm.sffBlockSource(publisher) })
                s.lead.url?.let { u ->
                    add(row("Share") {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_TEXT, "${headline(s)}\n$u")
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

/** The head of one genre's or topic's own feed: back, its name, follow and mute. */
@Composable
private fun FilteredHead(state: Snapshot, sector: Sector?, topic: SffFeed.Topic?, onBack: () -> Unit, vm: AppViewModel) {
    val c = Cp.colors
    val id = sector?.id ?: topic?.id ?: return
    val name = sector?.let { genreName(it) } ?: topic?.label ?: ""
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).bouncyClickable(CircleShape, onClickLabel = "Back") { onBack() }, contentAlignment = Alignment.Center) {
                LucideIcon(R.drawable.lucide_chevron_left, Modifier.size(20.dp), c.text)
            }
            HSpace(6)
            Column(Modifier.weight(1f)) {
                Text(name, fontFamily = Lora, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = sector?.let { sectorColor(it) } ?: c.text)
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

/** Genres: the five, each with its unread count, to open, follow or mute; then the news topics. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SffGenres(vm: AppViewModel, state: Snapshot, stories: List<SffFeed.Story>, onSector: (Sector) -> Unit, onTopic: (SffFeed.Topic) -> Unit) {
    val c = Cp.colors
    Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
        for (sec in Sector.entries) {
            val count = stories.count { sec in it.sectors && !it.read }
            val following = sec.id in state.sffSky.followed
            val muted = sec.id in state.sffSky.mutedSectors
            val shape = RoundedCornerShape(18.dp)
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(shape).background(c.surface).border(1.dp, c.hairline, shape)
                    .bouncyClickable(shape, pressedScale = 0.99f, onClickLabel = "Open ${genreName(sec)}") { onSector(sec) }
                    .alpha(if (muted) 0.5f else 1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(6.dp).height(78.dp).background(sectorColor(sec)))
                Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(genreName(sec), fontFamily = Lora, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = c.text)
                    Quiet(sec.subtitle, maxLines = 1)
                    if (count > 0) Text("$count unread", style = CpType.small, color = sectorColor(sec))
                }
                PillButton(if (following) "Following" else if (muted) "Muted" else "Follow", {
                    if (muted) vm.sffMute(sec.id, genreName(sec)) else vm.sffFollow(sec.id, genreName(sec))
                }, Modifier.padding(end = 12.dp), outlined = !following)
            }
        }
        VSpace(14)
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
