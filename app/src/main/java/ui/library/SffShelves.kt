package io.github.prithirajn.readync.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.prithirajn.readync.data.Release
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.source.UpcomingSff
import io.github.prithirajn.readync.sync.Matcher
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.CpShapes
import io.github.prithirajn.readync.ui.theme.CpType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Coming in SFF as a bookshop's new-arrivals wall: a ledge for each week's books (and one for what
 * came out in the last fortnight), the covers stood facing out, bigger the more readers want them,
 * each with a tag under it. Swipe along a ledge and you feel the covers pass; tap one and it's taken
 * down, turned to you, with what Goodreads knows of it (its series or that it stands alone, its
 * rating, pages, when it first came out, its genres, who translated it) and what you can do with it.
 * Sort turns a week into a deck to swipe through: right to want, left to skip, up to watch its price.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SffShelves(
    vm: AppViewModel, state: Snapshot, sff: List<Release>, total: Int, now: Long,
    readAuthors: Set<String>, hasServers: Boolean, isNew: (Release) -> Boolean,
) {
    val c = Cp.colors
    val view = LocalView.current
    val picked = state.releaseSettings.sffGenres
    var weeks by rememberSaveable { mutableStateOf(4) }
    var pulled by rememberSaveable { mutableStateOf<String?>(null) }
    // A week being sorted as a deck (its name and books), when one is.
    var sorting by remember { mutableStateOf<Pair<String, List<Release>>?>(null) }
    sorting?.let { (name, books) -> SortDeck(vm, books, name) { sorting = null } }

    VSpace(12)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (g in UpcomingSff.GENRES + UpcomingSff.NEWS) {
            val on = g in picked
            Box(
                Modifier.clip(CpShapes.pill).background(if (on) genreInk(g).copy(alpha = 0.28f) else c.divider)
                    .bouncyClickable(CpShapes.pill, pressedScale = 0.94f, role = Role.Checkbox, onClickLabel = g) {
                        Haptics.flip(view, !on)
                        vm.setReleaseSettings { s -> s.copy(sffGenres = if (on) s.sffGenres - g else s.sffGenres + g) }
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) { Text(g, style = CpType.label, color = if (on) c.text else c.textMid) }
        }
    }
    if (sff.isEmpty()) {
        VSpace(10)
        CpCard {
            Quiet(
                if (total == 0) "Nothing yet. Reactor's monthly lists and books announced in the news come in with the next sync (or pull the page down)."
                else "Nothing in the genres picked.",
                Modifier.padding(16.dp), color = c.faint,
            )
        }
        return
    }

    // A shelf for the last fortnight, then one a week (Monday to Sunday), nearest first.
    val today = LocalDate.now()
    val shelves = remember(sff, now) {
        val out = sff.filter { it.isOut(now) }.sortedWith(compareByDescending<Release> { it.date ?: it.seenAt }.thenByDescending { it.facts?.rating ?: 0.0 })
        val coming = sff.filter { !it.isOut(now) && it.date != null }
            .groupBy { localDay(it.date!!).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }.toSortedMap()
        buildList {
            if (out.isNotEmpty()) add(Triple("Out in the last fortnight", null as LocalDate?, out))
            for ((monday, list) in coming) add(Triple("", monday, list.sortedWith(compareBy<Release> { it.date }.thenByDescending { it.facts?.rating ?: 0.0 })))
        }
    }
    for ((i, shelf) in shelves.take(weeks + 1).withIndex()) {
        val (label, monday, books) = shelf
        val name = label.ifEmpty {
            val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val weeksOn = ChronoUnit.WEEKS.between(thisMonday, monday)
            val span = "${monday!!.dayOfMonth}–${monday.plusDays(6).format(DateTimeFormatter.ofPattern("d MMM"))}"
            when (weeksOn) { 0L -> "This week · $span"; 1L -> "Next week · $span"; else -> "Week of ${monday.format(DateTimeFormatter.ofPattern("d MMM"))}" }
        }
        Row(Modifier.fillMaxWidth().padding(top = if (i == 0) 14.dp else 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Stamp(name, c.inks[(i * 2).mod(c.inks.size)].text)
                val cm = books.count(::isComicOrManga)
                val bk = books.size - cm
                Text(
                    listOfNotNull(bk.takeIf { it > 0 }?.let { "$it book${if (it == 1) "" else "s"}" }, cm.takeIf { it > 0 }?.let { "$it comics & manga" }).joinToString(" · "),
                    style = CpType.mono, color = c.faint,
                )
            }
            PillButton("Sort", { sorting = name to books.filter { !it.skipped } })
        }
        VSpace(6)
        SplitLedges(books, pulled, readAuthors, isNew) { r ->
            pulled = if (pulled == r.key) null else r.key
            if (!r.seen) vm.releaseSeen(r.key)
        }
        // The book taken off this shelf, turned to its cover, with everything about it.
        val off = books.firstOrNull { it.key == pulled }
        AnimatedVisibility(off != null, enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            off?.let { PulledOut(vm, state, it, now, hasServers, it.author?.let { a -> Matcher.baseTitle(a) in readAuthors } == true) }
        }
    }
    if (shelves.size > weeks + 1) {
        VSpace(14)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PillButton("${minOf(4, shelves.size - weeks - 1)} more weeks", { weeks += 4 })
        }
    }
    VSpace(10)
    Quiet("From Reactor's monthly new-release lists; LitRPG from Aethon, Shadow Alley Press, LitRPG Radar and SequelWatch; manga from VIZ and Seven Seas; comics from Marvel and Dark Horse; and books announced in your News. A book found in two places shows once. Days can change. Series, ratings and pages from Goodreads.", color = c.faint)
}

/** Each genre's spot ink, for the chips and for a spine whose cover hasn't been seen yet. */
@Composable
private fun genreInk(g: String?): Color {
    val inks = Cp.colors.inks
    return when (g) {
        "Fantasy" -> inks[0].ink
        "Science fiction" -> inks[4 % inks.size].ink
        "Horror" -> inks[3 % inks.size].ink
        "Young adult" -> inks[1].ink
        "Romantasy" -> inks[3 % inks.size].ink.copy(alpha = 0.8f)
        "Crossover" -> inks[2].ink
        "LitRPG" -> inks[4 % inks.size].ink.copy(alpha = 0.75f)
        "Manga" -> inks[0].ink.copy(alpha = 0.7f)
        "Comics" -> inks[2].ink.copy(alpha = 0.7f)
        else -> Cp.colors.textMid
    }
}

/**
 * One week's ledge: the covers stood facing out on a printed board, each a little askew as a hand
 * propped it, bigger the more readers have it marked on Goodreads, with a price-tag card hung under
 * it (its series or that it stands alone, its rating, its day). Swipe along and you feel each cover
 * pass; tap one and it's taken down (it rises and the rest step back).
 */
@Composable
internal fun Ledge(books: List<Release>, pulled: String?, readAuthors: Set<String>, isNew: (Release) -> Boolean, scale: Float = 1f, onPick: (Release) -> Unit) {
    val view = LocalView.current
    val list = rememberLazyListState()
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex }.drop(1).collect { if (list.isScrollInProgress) Haptics.texture(view, 0.14f) }
    }
    // The ledge is as tall as its tallest cover (and the room a taken-down one rises into), not a
    // fixed height: a week of small covers otherwise sat under a wide empty band.
    val stand = remember(books, scale) { (books.maxOfOrNull { coverHeight(it) } ?: 128f) * scale + 16f }
    LazyRow(state = list, modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        items(books, key = { it.key }) { r ->
            FaceOut(r, pulled == r.key, pulled != null && pulled != r.key, isNew(r), r.author?.let { Matcher.baseTitle(it) in readAuthors } == true, stand, scale) { onPick(r) }
        }
    }
}

/** How tall a cover stands on its ledge: more readers wanting it on Goodreads, a bigger cover. */
private fun coverHeight(r: Release): Float {
    val n = r.facts?.ratings ?: 0
    return 128f + kotlin.math.min(64f, (kotlin.math.sqrt(n.toFloat()) * 1.3f))
}

/**
 * A cover stood facing out: the cover itself (askew by a degree or two, lifted and straightened
 * when taken down), its stretch of the board, and the tag hung under it.
 */
@Composable
private fun FaceOut(r: Release, pulled: Boolean, dimmed: Boolean, new: Boolean, byYours: Boolean, stand: Float, scale: Float = 1f, onClick: () -> Unit) {
    val c = Cp.colors
    val view = LocalView.current
    val f = r.facts
    val h = (coverHeight(r) * scale).dp
    val w = h * 0.66f
    val tilt = listOf(-2.2f, 1.4f, -1f, 2f, -1.6f, 0.8f)[(r.key.hashCode()).mod(6)]
    val lift by animateDpAsState(if (pulled) (-14).dp else 0.dp, spring(dampingRatio = 0.5f, stiffness = 420f), label = "coverLift")
    val lean by animateFloatAsState(if (pulled) 0f else tilt, spring(dampingRatio = 0.5f, stiffness = 380f), label = "coverLean")
    val fade by animateFloatAsState(if (dimmed) 0.5f else 1f, tween(220), label = "coverFade")
    val slot = maxOf(w, 104.dp * scale) + 12.dp
    Column(Modifier.width(slot), horizontalAlignment = Alignment.CenterHorizontally) {
        // The covers all stand on the board: a box as tall as the biggest, the cover at its foot.
        Box(Modifier.height(stand.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier.graphicsLayer {
                    translationY = lift.toPx(); rotationZ = lean; alpha = fade
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                    shadowElevation = if (pulled) 16.dp.toPx() else 4.dp.toPx()
                    shape = RoundedCornerShape(3.dp); clip = false
                }.bouncyClickable(RoundedCornerShape(3.dp), pressedScale = 0.96f, role = Role.Button, onClickLabel = r.title) {
                    if (pulled) Haptics.fall(view, 0.35f) else Haptics.thud(view, 0.45f)
                    onClick()
                },
            ) {
                BookCover(r.coverUrl ?: f?.cover, f?.title ?: r.title, w, h)
                if (new) Text(
                    "NEW", style = CpType.chip, color = c.onAccent,
                    modifier = Modifier.align(Alignment.TopStart).offset((-4).dp, (-6).dp).stampIn(true).clip(CpShapes.pill).background(c.accent).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        // The board: one stretch per cover, meeting the next, a second ink peeking out under it.
        Box(Modifier.fillMaxWidth().height(10.dp)) {
            Box(Modifier.fillMaxWidth().height(6.dp).offset(y = 3.dp).background(c.inks[2].ink.copy(alpha = 0.45f)))
            Box(Modifier.fillMaxWidth().height(6.dp).background(c.textMid))
        }
        VSpace(6)
        PriceTag(r, byYours, Modifier.width(slot - 10.dp).graphicsLayer { alpha = fade; rotationZ = -lean * 0.6f; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f) })
    }
}

/** A shop's tag with a clipped corner, hung under a cover: series or standalone, stars, the day. */
private val TagShape = androidx.compose.foundation.shape.GenericShape { size, _ ->
    val cut = 8f * 2.6f
    moveTo(cut, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); lineTo(0f, cut); close()
}

@Composable
private fun PriceTag(r: Release, byYours: Boolean, modifier: Modifier) {
    val f = r.facts
    val ink = Color(0xFF2A1C22)
    val series = f?.series ?: r.series.ifBlank { null }
    val pos = f?.position ?: r.number
    Column(modifier.clip(TagShape).background(Color(0xFFF3E7DA)).padding(horizontal = 7.dp, vertical = 5.dp)) {
        Text(
            when {
                r.kind == "author" -> "New from ${r.series}"
                r.kind == "chapter" -> r.number?.let { "${r.series} · $it" } ?: r.series
                series != null -> series + (pos?.let { " #$it" } ?: "")
                pos != null -> "Book $pos"
                f?.standalone == true -> "Standalone"
                else -> f?.title ?: r.title
            },
            style = CpType.chip.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        val bits = listOfNotNull(f?.rating?.let { "★ %.1f".format(it) }, r.date?.let { shortDay(it) }, if (byYours) "♥" else null)
        if (bits.isNotEmpty()) Text(bits.joinToString(" · "), style = CpType.chip.copy(fontSize = 10.5.sp), color = ink.copy(alpha = 0.75f), maxLines = 1)
    }
}

/**
 * Sort a week: its covers as a deck, one at a time, big. Drag the top one right to want it, left
 * for not this one, up to watch its price; past the point you feel it catch, and let go it flies
 * off with a thud and the next comes up. The buttons do the same for a tap.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SortDeck(vm: AppViewModel, books: List<Release>, title: String, onClose: () -> Unit) {
    val c = Cp.colors
    val view = LocalView.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var at by remember { mutableStateOf(0) }
    val dx = remember { Animatable(0f) }
    val dy = remember { Animatable(0f) }
    var armed by remember { mutableStateOf(0) } // -1 skip, 1 want, 2 watch, 0 none
    // A card on its way off: nothing more until the next is up (a quick second tap would act twice).
    var flying by remember { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val reach = with(density) { 110.dp.toPx() }
    fun act(dir: Int) {
        if (flying) return
        val r = books.getOrNull(at) ?: return
        flying = true
        when (dir) { 1 -> vm.wantRelease(r); -1 -> vm.skipRelease(r.key); 2 -> vm.watchReleasePrice(r) }
        vm.releaseSeen(r.key)
        Haptics.thud(view, 0.6f)
        scope.launch {
            val tx = when (dir) { 1 -> reach * 5; -1 -> -reach * 5; else -> 0f }
            val ty = if (dir == 2) -reach * 7 else 0f
            launch { dx.animateTo(tx, tween(260)) }
            dy.animateTo(ty, tween(260))
            at++; armed = 0
            dx.snapTo(0f); dy.snapTo(0f)
            flying = false
        }
    }
    GuardedSheet(dragHandle = { SheetGrip() }, onDismissRequest = onClose, sheetState = rememberSheetState(skipPartiallyExpanded = true), shape = CpShapes.sheet, containerColor = c.page) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = CpType.section, color = c.text)
            Quiet(if (at < books.size) "${at + 1} of ${books.size} · right to want it, left to skip, up to watch its price" else "All sorted.", color = c.faint)
            VSpace(16)
            Box(Modifier.fillMaxWidth().height(430.dp), contentAlignment = Alignment.TopCenter) {
                if (at >= books.size) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 120.dp)) {
                        Text("That's the week.", style = CpType.section, color = c.text)
                        VSpace(12)
                        PillButton("Done", onClose)
                    }
                }
                // The next two wait under it, a little smaller and askew.
                for (k in 2 downTo 0) {
                    val r = books.getOrNull(at + k) ?: continue
                    key(r.key) {
                        val isTop = k == 0
                        Column(
                            Modifier
                                .graphicsLayer {
                                    if (isTop) {
                                        translationX = dx.value; translationY = dy.value
                                        rotationZ = dx.value / 22f
                                    } else {
                                        translationY = k * 10.dp.toPx(); scaleX = 1f - k * 0.05f; scaleY = 1f - k * 0.05f
                                        rotationZ = if (k == 1) -3f else 4f; alpha = 1f - k * 0.25f
                                    }
                                }
                                .then(if (!isTop) Modifier else Modifier.pointerInput(r.key) {
                                    detectDragGestures(
                                        onDragEnd = {
                                            val dir = when { dx.value > reach -> 1; dx.value < -reach -> -1; dy.value < -reach -> 2; else -> 0 }
                                            if (dir != 0) act(dir) else {
                                                if (armed != 0 || kotlin.math.abs(dx.value) > reach / 3) Haptics.fall(view, 0.3f)
                                                armed = 0
                                                scope.launch { launch { dx.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 400f)) }; dy.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 400f)) }
                                            }
                                        },
                                    ) { change, drag ->
                                        change.consume()
                                        scope.launch { dx.snapTo(dx.value + drag.x); dy.snapTo((dy.value + drag.y).coerceAtMost(reach * 0.4f)) }
                                        val now = when { dx.value > reach -> 1; dx.value < -reach -> -1; dy.value < -reach -> 2; else -> 0 }
                                        if (now != armed) { armed = now; if (now != 0) Haptics.rise(view, 0.55f) else Haptics.fall(view, 0.25f) }
                                        else if (kotlin.math.abs(drag.x) + kotlin.math.abs(drag.y) > 6f) Haptics.texture(view, 0.08f + 0.3f * (kotlin.math.hypot(dx.value, dy.value) / reach).coerceAtMost(1f))
                                    }
                                }),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box {
                                BookCover(r.coverUrl ?: r.facts?.cover, r.facts?.title ?: r.title, 210.dp, 315.dp)
                                if (isTop) {
                                    // What letting go will do, stamped on as you drag toward it.
                                    val p = (kotlin.math.max(kotlin.math.abs(dx.value), -dy.value) / reach).coerceIn(0f, 1f)
                                    val (word, ink) = when {
                                        dx.value > 0 && dx.value >= -dy.value -> "WANT" to c.good
                                        dx.value < 0 && -dx.value >= -dy.value -> "SKIP" to c.inks[0].ink
                                        else -> "WATCH PRICE" to c.accent
                                    }
                                    if (p > 0.15f) Text(
                                        word, style = CpType.section, color = ink,
                                        modifier = Modifier.align(Alignment.Center).graphicsLayer { alpha = p; rotationZ = -12f }
                                            .background(c.page.copy(alpha = 0.85f), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
                            }
                            if (isTop) {
                                VSpace(10)
                                val f = r.facts
                                Text(f?.title ?: r.title, style = CpType.bodyStrong, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val series = f?.series ?: r.series.ifBlank { null }
                                val pos = f?.position ?: r.number
                                Quiet(
                                    listOfNotNull(
                                        (f?.author ?: r.author),
                                        when { series != null -> series + (pos?.let { " #$it" } ?: ""); f?.standalone == true -> "Standalone"; else -> null },
                                        f?.rating?.let { "★ %.2f".format(it) + (f.ratings?.let { n -> " (${"%,d".format(n)})" } ?: "") },
                                        r.date?.let { "out " + shortDay(it) },
                                    ).joinToString(" · "),
                                    color = c.textMid, maxLines = 2,
                                )
                            }
                        }
                    }
                }
            }
            if (at < books.size) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Skip", { act(-1) })
                PillButton("Watch price", { act(2) })
                PillButton("Want it", { act(1) }, outlined = false)
            }
        }
    }
    // Each new top card is looked up on Goodreads if it hasn't been.
    LaunchedEffect(at) { books.getOrNull(at)?.let { vm.enrichRelease(it.key) }; books.getOrNull(at + 1)?.let { vm.enrichRelease(it.key) } }
}

/**
 * The book taken off the shelf: it turns from its spine to its cover as it comes out, and under it,
 * everything worth knowing: series and number (or that it stands alone), its Goodreads rating,
 * pages, binding and publisher, when it first came out (a translation's original), its genres and
 * translators, its day, and what you can do with it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PulledOut(vm: AppViewModel, state: Snapshot, r: Release, now: Long, hasServers: Boolean, byYours: Boolean) {
    val c = Cp.colors
    val f = r.facts
    val asked = f?.checkedAt?.let { it > 0 } == true
    LaunchedEffect(r.key) { vm.enrichRelease(r.key) }
    // The turn: from edge-on (its spine) to facing you.
    val animate = LocalAnimationsOn.current
    val turn = remember(r.key) { Animatable(if (animate) 78f else 0f) }
    LaunchedEffect(r.key) { turn.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = 260f)) }
    var more by remember(r.key) { mutableStateOf(false) }
    CpCard(Modifier.padding(top = 12.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row {
                BookCover(
                    r.coverUrl ?: f?.cover, f?.title ?: r.title, 96.dp, 144.dp,
                    Modifier.graphicsLayer { rotationY = turn.value; cameraDistance = 12f * density; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }.liftable(),
                )
                HSpace(14)
                Column(Modifier.weight(1f)) {
                    Text(f?.title ?: r.title, style = CpType.section, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    val by = f?.author ?: r.author
                    val tr = f?.translators.orEmpty()
                    if (by != null) Quiet("by $by" + if (tr.isNotEmpty()) " · translated by ${tr.joinToString(" & ")}" else "", color = c.textMid)
                    VSpace(6)
                    // Series, or standing alone.
                    val series = f?.series ?: r.series.ifBlank { null }
                    val pos = f?.position ?: r.number
                    Text(
                        when {
                            series != null -> (pos?.let { "Book $it of " } ?: "Part of ") + series
                            pos != null -> "Book $pos of a series"
                            f?.standalone == true -> "Standalone"
                            !asked -> "Looking it up on Goodreads…"
                            else -> "Series not known"
                        },
                        style = CpType.bodyStrong, color = if (series != null || pos != null) c.accentText else c.textMid,
                    )
                    // Goodreads rating, drawn as stars.
                    if (f?.rating != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Stars(f.rating)
                            HSpace(6)
                            Text("%.2f".format(f.rating) + (f.ratings?.let { " · ${"%,d".format(it)} rating${if (it == 1) "" else "s"}" } ?: ""), style = CpType.small, color = c.textMid)
                        }
                        Quiet("on Goodreads", color = c.faint)
                    } else if (asked && f?.missing != true) Quiet("No Goodreads ratings yet", color = c.faint)
                    else if (f?.missing == true) Quiet("Not on Goodreads yet", color = c.faint)
                }
            }
            VSpace(12)
            // The facts line, and the day.
            val releaseYear = r.date?.let { localDay(it).year }
            val facts = listOfNotNull(
                f?.pages?.let { "$it pages" }, f?.format, f?.publisher ?: r.source.substringAfter(" · ", "").ifBlank { null },
                f?.firstYear?.takeIf { y -> releaseYear != null && y < releaseYear }?.let { "first published $it" + if (f.translators.isNotEmpty()) " (this is its translation)" else "" },
            )
            if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = CpType.small, color = c.textMid)
            r.date?.let { d ->
                val days = ChronoUnit.DAYS.between(LocalDate.now(), localDay(d))
                Text(
                    "Out ${day(d)}" + when { days == 0L -> " · today"; days == 1L -> " · tomorrow"; days > 1 -> " · in $days days"; days == -1L -> " · yesterday"; else -> " · ${-days} days ago" },
                    style = CpType.small, color = c.accentText,
                )
            }
            if (byYours) Text("An author you've read", style = CpType.small, color = c.good)
            val tags = (listOfNotNull(r.genre) + f?.genres.orEmpty()).distinctBy { it.lowercase() }.take(5)
            if (tags.isNotEmpty()) {
                VSpace(8)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { tags.forEach { Chip(it) } }
            }
            r.blurb?.let { b ->
                VSpace(10)
                Text(b, style = CpType.body, color = c.text, maxLines = if (more) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                if (b.length > 220) Text(
                    if (more) "Less" else "More", style = CpType.label, color = c.accentText,
                    modifier = Modifier.padding(top = 4.dp).bouncyClickable(CpShapes.pill, onClickLabel = "More") { more = !more },
                )
            }
            VSpace(10)
            ReleaseMore(vm, state, r, now, hasServers, indent = 0.dp, showBlurb = false)
        }
    }
}

/** Five stars, filled to [rating] (in quarters), in the accent ink. */
@Composable
private fun Stars(rating: Double) {
    val c = Cp.colors
    Row {
        for (i in 0 until 5) {
            val fill = (rating - i).coerceIn(0.0, 1.0)
            Box(Modifier.size(13.dp)) {
                Text("★", style = CpType.small.copy(fontSize = 13.sp, lineHeight = 13.sp), color = c.divider)
                if (fill > 0.12) Box(Modifier.fillMaxHeight().width((13 * (Math.round(fill * 4) / 4.0)).dp).clip(RoundedCornerShape(0.dp))) {
                    Text("★", style = CpType.small.copy(fontSize = 13.sp, lineHeight = 13.sp), color = c.accent, softWrap = false, maxLines = 1)
                }
            }
        }
    }
}

/** A comic or a manga (or manhwa) rather than a book: by Coming soon's genre, or by what kind of release of yours it is. */
internal fun isComicOrManga(r: Release): Boolean =
    r.genre == UpcomingSff.MANGA || r.genre == UpcomingSff.COMICS || r.kind == "volume" || r.kind == "chapter" || r.kind == "issue"

/**
 * One week (or one of your shelves) as two ledges under its heading: the books, then the comics
 * and manga on a ledge of their own with a small label, so the two never mix on one board.
 */
@Composable
internal fun SplitLedges(list: List<Release>, pulled: String?, readAuthors: Set<String>, isNew: (Release) -> Boolean, scale: Float = 1f, onPick: (Release) -> Unit) {
    val c = Cp.colors
    val (cm, bk) = remember(list) { list.partition(::isComicOrManga) }
    if (bk.isNotEmpty()) Ledge(bk, pulled, readAuthors, isNew, scale, onPick)
    if (cm.isNotEmpty()) {
        if (bk.isNotEmpty()) {
            VSpace(10)
            Text("COMICS & MANGA", style = CpType.chip, color = c.textMid, modifier = Modifier.padding(start = 2.dp, bottom = 2.dp))
        }
        Ledge(cm, pulled, readAuthors, isNew, scale, onPick)
    }
}
