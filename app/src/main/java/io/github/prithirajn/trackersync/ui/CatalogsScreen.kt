package io.github.prithirajn.trackersync.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.prithirajn.trackersync.R
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.source.Opds
import io.github.prithirajn.trackersync.sync.Matcher
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import io.github.prithirajn.trackersync.ui.theme.CpType
import io.github.prithirajn.trackersync.ui.theme.Lora
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One server's part in a search: still looking, how many it found, or what went wrong. */
private data class SearchState(val running: Boolean = true, val found: Int = 0, val error: String? = null)

/** A book as found across servers: the same title and author on several of them shows once. */
private data class Hit(val key: String, val entry: Opds.Entry, val places: List<Pair<Opds.Server, Opds.Entry>>) {
    val formats: List<String> get() = places.flatMap { (_, e) -> e.acquisitions.map { it.format } }.distinct().filter { it != "FILE" }
}

private fun keyOf(e: Opds.Entry): String =
    Matcher.baseTitle(e.title).lowercase().filter { it.isLetterOrDigit() } + "|" +
        (e.authors.firstOrNull()?.lowercase()?.split(Regex("[\\s,.]+"))?.filter { it.length > 1 }?.sorted()?.joinToString("") ?: "")

/** How well a book answers the words: the title first, then the author, then how many servers have it. */
private fun score(h: Hit, q: String): Double {
    val t = h.entry.title.lowercase(); val words = q.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    val a = h.entry.authors.joinToString(" ").lowercase()
    var s = 0.0
    if (t == q.lowercase()) s += 6
    if (t.startsWith(q.lowercase())) s += 3
    s += words.count { it in t } * 1.5 + words.count { it in a } * 1.0
    s += h.places.size * 0.6
    if (h.formats.isNotEmpty()) s += 0.5
    return s
}

/**
 * Catalogs: your OPDS book servers in one place. Type once and every server is searched at the
 * same time; the same book from several servers shows once, with each server and format to get it
 * from. Without a search, your servers to browse one by one, and a way to add one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CatalogsScreen(vm: AppViewModel, state: Snapshot) {
    val c = Cp.colors
    val context = LocalContext.current.applicationContext
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Opds.load(context) } }
    val servers by Opds.servers.collectAsState()
    val scope = rememberCoroutineScope()

    var query by rememberSaveable { mutableStateOf("") }
    val status = remember { mutableStateMapOf<String, SearchState>() }
    val found = remember { mutableStateListOf<Pair<Opds.Server, Opds.Entry>>() }
    var searched by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf<Opds.Server?>(null) }
    var addingNew by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf<Opds.Server?>(null) }
    var open by remember { mutableStateOf<Hit?>(null) }
    var only by remember { mutableStateOf<String?>(null) }
    var pasting by remember { mutableStateOf(false) }
    var copiesOpen by remember { mutableStateOf(false) }
    var serversOpen by remember { mutableStateOf(false) }
    // The newest books across every server: each asked at once, merged newest first, 50 shown.
    val latestFound = remember { mutableStateListOf<Triple<Opds.Server, Opds.Entry, Int>>() }
    var latestRunning by remember { mutableStateOf(0) }
    var latestRound by remember { mutableStateOf(0) }
    LaunchedEffect(servers.map { it.id }, latestRound) {
        if (servers.isEmpty()) return@LaunchedEffect
        latestFound.clear()
        latestRunning = servers.size
        servers.map { srv ->
            scope.async {
                val r = withContext(Dispatchers.IO) { runCatching { Opds.latest(srv, 30, fresh = latestRound > 0) } }
                r.getOrNull()?.forEachIndexed { i, e -> latestFound += Triple(srv, e, i) }
                latestRunning--
            }
        }.awaitAll()
    }
    // Search the catalogs, or the deals on books whose price you're watching.
    var mode by rememberSaveable { mutableStateOf("Search") }

    // Every server at once, a moment after you stop typing; results come in as each answers.
    LaunchedEffect(query, servers.map { it.id }) {
        val q = query.trim()
        if (q.length < 2 || servers.isEmpty()) { found.clear(); status.clear(); searched = ""; return@LaunchedEffect }
        delay(650)
        searched = q; only = null
        found.clear(); status.clear()
        servers.forEach { status[it.id] = SearchState() }
        servers.map { s ->
            scope.async {
                val r = withContext(Dispatchers.IO) { runCatching { Opds.search(context, s, q) } }
                if (searched != q) return@async
                r.onSuccess { list ->
                    val books = list.filter { it.isBook || it.navigation == null }
                    found.addAll(books.map { s to it })
                    status[s.id] = SearchState(running = false, found = books.size)
                }.onFailure { e -> status[s.id] = SearchState(running = false, error = e.message ?: "didn't answer") }
            }
        }.awaitAll()
    }

    val hits = remember(found.size, searched, only) {
        found.filter { only == null || it.first.id == only }
            .groupBy { keyOf(it.second) }
            .map { (k, list) -> Hit(k, list.maxByOrNull { (it.second.summary?.length ?: 0) + if (it.second.image != null) 500 else 0 }!!.second, list) }
            .sortedByDescending { score(it, searched) }
    }
    val latest = remember(latestFound.size) {
        // Newest first by the date each server gives; without one, each server's own order, taken in turn.
        latestFound.toList()
            .groupBy { keyOf(it.second) }
            .map { (k, list) -> Hit(k, list.maxByOrNull { (it.second.summary?.length ?: 0) + if (it.second.image != null) 500 else 0 }!!.second, list.map { it.first to it.second }) to list }
            .sortedWith(
                compareByDescending<Pair<Hit, List<Triple<Opds.Server, Opds.Entry, Int>>>> { (_, l) -> l.mapNotNull { it.second.updated?.take(19) }.maxOrNull() ?: "" }
                    .thenBy { (_, l) -> l.minOf { it.third } }
            )
            .map { it.first }
            .take(50)
    }
    val latestServers = remember(latestFound.size) { latestFound.map { it.first.id }.distinct().size }
    val listState = rememberLazyListState()

    LazyColumn(Modifier.fillMaxSize().fadeTopEdge(listState.canScrollBackward), state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "head") {
            Column(PagePadding) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow(if (servers.isEmpty()) "OPDS book servers" else "${servers.size} server${if (servers.size == 1) "" else "s"}, searched together")
                        PageTitle("Catalogs")
                    }
                    IconCircleButton(R.drawable.lucide_list_checks, "Paste a list of servers", { pasting = true }, tint = c.textMid)
                    HSpace(8)
                    IconCircleButton(R.drawable.lucide_plus, "Add a server", { addingNew = true }, tint = c.accentText)
                }
                VSpace(12)
                Segmented(listOf("Search", "Deals"), mode, { if (it == "Deals" && state.priceWatch.isNotEmpty()) "Deals · ${state.priceWatch.size}" else it }, { mode = it }, Modifier.fillMaxWidth())
                if (servers.isNotEmpty() && mode == "Search") {
                    VSpace(12)
                    val clear: (@Composable () -> Unit)? = if (query.isNotEmpty()) { { IconCircleButton(R.drawable.lucide_x, "Clear", { query = "" }) } } else null
                    CpTextField(query, { query = it }, "Search every catalog", placeholder = "Title, author or series", trailing = clear)
                }
            }
        }

        if (mode == "Deals") {
            item(key = "deals") { DealsList(vm, state) }
        } else if (servers.isEmpty()) {
            item(key = "empty") { NoServers(onAdd = { addingNew = true }, onPaste = { pasting = true }, onSuggest = { name, url -> adding = Opds.Server(java.util.UUID.randomUUID().toString(), name, url) }) }
        } else if (searched.isEmpty()) {
            // Your servers and the app's copies, one line each; the newest books across every server below.
            item(key = "servers") { ServersRow(servers.size) { serversOpen = true } }
            item(key = "copies") { CopiesRow { copiesOpen = true } }
            item(key = "latest-head") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { CatalogHead("Latest added") }
                    if (latestRunning > 0) {
                        CircularProgressIndicator(Modifier.size(14.dp), color = c.accent, strokeWidth = 2.dp)
                        HSpace(16)
                    } else {
                        IconCircleButton(R.drawable.lucide_refresh_cw, "Check for newer", { latestRound++ }, tint = c.textMid)
                        HSpace(12)
                    }
                }
            }
            item(key = "latest-note") {
                Quiet(
                    when {
                        latestRunning > 0 && latest.isEmpty() -> "Asking ${servers.size} servers for what they added last…"
                        latestRunning > 0 -> "${latest.size} so far · ${latestRunning} servers still answering"
                        latest.isEmpty() -> "None of your servers lists its newest books."
                        else -> "The ${latest.size} newest across ${latestServers} servers. Type above to search them all."
                    },
                    Modifier.padding(horizontal = 16.dp), color = c.faint,
                )
            }
            items(latest.chunked(3), key = { row -> "lr-" + row.first().key }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (h in row) Box(Modifier.weight(1f)) { HitTile(h) { open = h } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        } else {
            // Each server's answer, as it comes in; tap one to see only its books.
            item(key = "status") {
                // One line for the servers; their names (to see only one's books) when tapped.
                var showServers by remember { mutableStateOf(false) }
                val running = status.values.count { it.running }
                val answered = status.values.count { !it.running && it.error == null && it.found > 0 }
                Text(
                    (if (running > 0) "Searching ${servers.size} servers ($running still going)" else "Searched ${servers.size} servers · found on $answered") +
                        (only?.let { id -> " · only " + (servers.firstOrNull { it.id == id }?.name ?: "one") } ?: "") +
                        if (showServers) " · hide servers" else " · by server",
                    style = CpType.small, color = c.quiet,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
                        .bouncyClickable(RoundedCornerShape(8.dp), pressedScale = 0.99f, onClickLabel = "Servers") { showServers = !showServers },
                )
                if (showServers) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in servers) {
                        val st = status[s.id] ?: SearchState(running = false)
                        val on = only == s.id
                        Row(
                            Modifier.clip(CpShapes.pill).background(if (on) c.text else c.divider)
                                .bouncyClickable(CpShapes.pill, pressedScale = 0.94f, onClickLabel = "Only ${s.name}") { only = if (on) null else s.id }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            when {
                                st.running -> CircularProgressIndicator(Modifier.size(12.dp), color = c.accent, strokeWidth = 1.5.dp)
                                st.error != null -> Box(Modifier.size(7.dp).clip(CircleShape).background(c.warn))
                                else -> Box(Modifier.size(7.dp).clip(CircleShape).background(if (st.found > 0) c.good else c.faint))
                            }
                            HSpace(7)
                            Text(s.name + if (!st.running && st.error == null) " · ${st.found}" else "", style = CpType.small, color = if (on) c.page else c.textMid, maxLines = 1)
                        }
                    }
                }
                // Servers that couldn't search: one line, the reasons when tapped.
                val failed = status.entries.filter { it.value.error != null }
                if (failed.isNotEmpty()) {
                    var showWhy by remember { mutableStateOf(false) }
                    Column(Modifier.padding(horizontal = 16.dp).bouncyClickable(RoundedCornerShape(8.dp), pressedScale = 0.99f, onClickLabel = "Why") { showWhy = !showWhy }) {
                        Text("${failed.size} server${if (failed.size == 1) "" else "s"} couldn't search · ${if (showWhy) "hide" else "why?"}", style = CpType.small, color = c.quiet)
                        if (showWhy) for ((id, st) in failed) servers.firstOrNull { it.id == id }?.let {
                            Text("${it.name}: ${st.error}", style = CpType.small, color = c.faint, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
            val still = status.values.any { it.running }
            if (hits.isEmpty() && !still) item(key = "none") { Quiet("Nothing on any server for “$searched”.", Modifier.padding(16.dp)) }
            // Three to a row, covers first.
            items(hits.chunked(3), key = { row -> "hr-" + row.first().key }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (h in row) Box(Modifier.weight(1f)) { HitTile(h) { open = h } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    open?.let { h -> HitSheet(vm, state, h) { open = null } }
    if (addingNew) ServerSheet(null, onClose = { addingNew = false })
    if (pasting) PasteServersSheet { pasting = false }
    if (copiesOpen) CopiesSheet(vm, state) { copiesOpen = false }
    if (serversOpen) PlainSheet("Your servers", { serversOpen = false }) {
        Quiet("Tap one to browse its shelves; Edit to change or remove it.")
        VSpace(8)
        for (srv in servers) ServerRow(srv, onBrowse = { serversOpen = false; browsing = srv }, onEdit = { serversOpen = false; adding = srv })
    }
    adding?.let { s -> ServerSheet(s, onClose = { adding = null }) }
    browsing?.let { s -> BrowseServer(vm, state, s) { browsing = null } }
}

@Composable
private fun CatalogHead(text: String) {
    val c = Cp.colors
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = CpType.bodyStrong.copy(fontWeight = FontWeight.ExtraBold, fontSize = 18.sp), color = c.text)
        Box(Modifier.padding(start = 12.dp).weight(1f).height(1.dp).background(c.divider))
    }
}

/** With no server yet: what this is, a way to add one, and a public catalog to try. */
@Composable
private fun NoServers(onAdd: () -> Unit, onPaste: () -> Unit, onSuggest: (String, String) -> Unit) {
    val c = Cp.colors
    Column(Modifier.padding(16.dp)) {
        CpCard {
            Column(Modifier.padding(18.dp)) {
                Text("Search all your book servers at once", fontFamily = Lora, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 27.sp, color = c.text)
                VSpace(8)
                Quiet("Add the OPDS address of each server you use: Calibre-Web, Calibre's content server, Kavita, Komga, Stump, COPS and others. One search then looks through every one of them, and the same book found on several shows once, with each place and format to get it from.")
                VSpace(14)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigButton("Add a server", onAdd, Modifier.weight(1f))
                    BigButton("Paste a list", onPaste, Modifier.weight(1f), filled = false)
                }
                VSpace(10)
                Text("Or try a public one", style = CpType.small, color = c.quiet)
                VSpace(6)
                for ((name, url) in Opds.suggestions) PillButton("+ $name", { onSuggest(name, url) })
            }
        }
        VSpace(12)
        Quiet("Where to find the address: Calibre-Web /opds · Kavita: your OPDS link in Settings (it has your API key) · Komga /opds/v1.2/catalog · Stump /opds/v1.2/catalog · COPS /feed.php", color = c.faint)
    }
}

@Composable
private fun ServerRow(s: Opds.Server, onBrowse: () -> Unit, onEdit: () -> Unit) {
    val c = Cp.colors
    Row(
        Modifier.fillMaxWidth().bouncyClickable(RoundedCornerShape(0.dp), pressedScale = 0.99f, onClickLabel = "Browse ${s.name}") { onBrowse() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.accentTint), contentAlignment = Alignment.Center) {
            Text(s.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•", style = CpType.bodyStrong, color = c.accentText)
        }
        HSpace(12)
        Column(Modifier.weight(1f)) {
            Text(s.name, style = CpType.bodyStrong, color = c.text, maxLines = 1)
            Quiet(listOfNotNull(s.host, if (!s.user.isNullOrBlank()) "signed in" else null).joinToString(" · "), maxLines = 1)
        }
        PillButton("Edit", onEdit)
        HSpace(4)
        LucideIcon(R.drawable.lucide_chevron_right, Modifier.size(18.dp), c.quiet)
    }
}

/** A cover from a server (with its sign-in where needed), kept for the session. */
@Composable
private fun CatalogCover(server: Opds.Server, url: String?, title: String, width: Dp, height: Dp) {
    val c = Cp.colors
    val image by produceState<ImageBitmap?>(url?.let { coverMemory.get(it) }, url) {
        if (url != null && value == null) {
            value = withContext(Dispatchers.IO) {
                Opds.image(server, url)?.let { b ->
                    // Full size unless it's very large (a phone-width tile wants ~500 px).
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(b, 0, b.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= 600) sample *= 2
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    runCatching { BitmapFactory.decodeByteArray(b, 0, b.size, opts)?.asImageBitmap() }.getOrNull()
                }
            }?.also { coverMemory.put(url, it) }
        }
    }
    Box(Modifier.size(width, height).clip(RoundedCornerShape(6.dp)).background(c.cover), contentAlignment = Alignment.Center) {
        val img = image
        if (img != null) Image(img, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(title.take(24), style = CpType.small.copy(fontSize = 9.sp, lineHeight = 11.sp), color = c.quiet, modifier = Modifier.padding(4.dp), maxLines = 4)
    }
}

private val coverMemory = android.util.LruCache<String, ImageBitmap>(80)

/** A found book: cover, title, author and series, its formats, and the servers that have it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HitRow(h: Hit, onOpen: () -> Unit) {
    val c = Cp.colors
    val e = h.entry
    Row(
        Modifier.fillMaxWidth().bouncyClickable(RoundedCornerShape(0.dp), pressedScale = 0.99f, onClickLabel = "Open ${e.title}") { onOpen() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        CatalogCover(h.places.first().first, e.cover ?: e.thumbnail, e.title, 62.dp, 93.dp)
        HSpace(14)
        Column(Modifier.weight(1f)) {
            Text(e.title, fontFamily = Lora, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
            e.authors.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(", "), style = CpType.small, color = c.textMid, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            e.series?.let { Text(it + (e.seriesIndex?.let { n -> " #$n" } ?: ""), style = CpType.small, color = c.accentText, maxLines = 1) }
            VSpace(6)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                for (f in h.formats.take(4)) Chip(f, color = c.text, background = c.divider)
                val n = h.places.distinctBy { it.first.id }.size
                if (n > 1) Chip("Found in $n", color = c.accentText, background = c.accentTint)
            }
        }
    }
}

/** A search result as a tile: the cover, its title and author, its formats and how many servers have it. */
@Composable
private fun HitTile(h: Hit, onOpen: () -> Unit) {
    val c = Cp.colors
    val e = h.entry
    // The sharpest cover any of its servers has.
    val (server, cover) = h.places.firstNotNullOfOrNull { (srv, pe) -> pe.cover?.let { srv to it } }
        ?: h.places.firstNotNullOfOrNull { (srv, pe) -> pe.thumbnail?.let { srv to it } }
        ?: (h.places.first().first to null)
    val n = h.places.distinctBy { it.first.id }.size
    Column(Modifier.fillMaxWidth().bouncyClickable(RoundedCornerShape(8.dp), pressedScale = 0.97f, onClickLabel = "Open ${e.title}") { onOpen() }) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            CatalogCover(server, cover, e.title, maxWidth, maxWidth * 1.5f)
        }
        VSpace(7)
        Text(e.title, fontFamily = Lora, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        e.authors.firstOrNull()?.let { Text(it, style = CpType.small, color = c.textMid, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        VSpace(3)
        Text(
            listOfNotNull(h.formats.take(2).joinToString(" · ").ifBlank { null }, "found in $n").joinToString(" · "),
            style = CpType.small.copy(fontSize = 11.sp), color = c.accentText, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A book: its cover, title, author, series and summary, and each server with the formats it has,
 * to download (into Downloads) and open; or onto your Want to Read.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HitSheet(vm: AppViewModel, state: Snapshot, h: Hit, onClose: () -> Unit) {
    val c = Cp.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val e = h.entry
    // The download under way (or just done): which file, from where, how it's going.
    var dl by remember { mutableStateOf<Download?>(null) }
    val cancel = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var said by remember { mutableStateOf<String?>(null) }
    var more by remember { mutableStateOf(false) }
    val inLibrary = remember(state.books) { state.books.values.firstOrNull { Matcher.sameBook(e.title, e.authors.firstOrNull(), it.title ?: "", it.author) } }
    PlainSheet(e.title, onClose) {
        e.authors.takeIf { it.isNotEmpty() }?.let { Quiet(it.joinToString(", ")) }
        VSpace(14)
        Row {
            CatalogCover(h.places.first().first, e.cover ?: e.thumbnail, e.title, 96.dp, 144.dp)
            HSpace(14)
            Column(Modifier.weight(1f)) {
                e.series?.let { Text(it + (e.seriesIndex?.let { n -> " · book $n" } ?: ""), style = CpType.bodyStrong, color = c.accentText) }
                listOfNotNull(e.published, e.publisher, e.language?.uppercase()).joinToString(" · ").takeIf { it.isNotBlank() }?.let { Quiet(it) }
                if (e.categories.isNotEmpty()) { VSpace(6); Quiet(e.categories.joinToString(" · "), maxLines = 2) }
                VSpace(10)
                if (inLibrary != null) PillButton("In your library", { onClose(); AppActions.pending.value = AppAction.OpenBook(inLibrary.document) })
                else PillButton("+ Want to read", { vm.addFromCatalog(e.title, e.authors.firstOrNull(), e.cover ?: e.thumbnail) { err -> said = err ?: "Added to Want to Read" } })
            }
        }
        e.summary?.let { sum ->
            VSpace(14)
            Text(sum, style = CpType.body, color = c.textMid, maxLines = if (more) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.animateContentSize().bouncyClickable(RoundedCornerShape(8.dp), pressedScale = 0.995f, onClickLabel = if (more) "Less" else "More") { more = !more })
        }
        VSpace(18)
        Text("Get it from", style = CpType.label, color = c.text)
        for ((server, entry) in h.places) {
            VSpace(10)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.hairline, RoundedCornerShape(16.dp)).padding(12.dp)) {
                Text(server.name, style = CpType.bodyStrong, color = c.text)
                if (entry.acquisitions.isEmpty()) Quiet("No file to download here.", color = c.faint)
                else {
                    VSpace(8)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (a in entry.acquisitions.distinctBy { it.format + it.rel }) {
                            val busy = dl?.let { it.running && it.server.id == server.id && it.acq.href == a.href } == true
                            PillButton("↓ ${a.format}" + if (a.rel?.endsWith("/buy") == true) " (buy)" else "", {
                                if (dl?.running == true) return@PillButton
                                cancel.set(false); said = null
                                dl = Download(server, a, Opds.Progress("Starting"), running = true)
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) {
                                        runCatching { Opds.download(context.applicationContext, server, a, e.title, cancel) { p -> dl = dl?.copy(progress = p) } }
                                    }
                                    dl = dl?.copy(running = false, saved = r.getOrNull(), error = r.exceptionOrNull()?.message ?: if (r.isFailure) "Couldn't download it" else null)
                                }
                            }, outlined = !busy)
                        }
                        // The book's in your library: keep this EPUB inside the app, for the words around its quotes.
                        if (inLibrary != null && io.github.prithirajn.trackersync.source.BookCopies.epubOf(entry) != null) {
                            PillButton("Keep in the app for quotes", { vm.keepCatalogCopy(inLibrary.document, server, entry); said = "Getting a copy for quotes…" })
                        }
                    }
                }
            }
        }
        said?.let { VSpace(10); AnimatedQuiet(it, color = c.textMid) }
        dl?.let { d ->
            VSpace(12)
            DownloadCard(d, onCancel = { cancel.set(true) }, onDismiss = { dl = null }, onSaid = { said = it })
        }
    }
}

/** Your servers as one line: how many, and the way in to browse or change them. */
@Composable
private fun ServersRow(count: Int, onOpen: () -> Unit) {
    val c = Cp.colors
    Row(
        Modifier.fillMaxWidth().bouncyClickable(RoundedCornerShape(0.dp), pressedScale = 0.99f, onClickLabel = "Your servers") { onOpen() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.accentTint), contentAlignment = Alignment.Center) {
            LucideIcon(R.drawable.lucide_globe, Modifier.size(18.dp), c.accentText)
        }
        HSpace(12)
        Column(Modifier.weight(1f)) {
            Text("Your servers", style = CpType.bodyStrong, color = c.text, maxLines = 1)
            Quiet("$count server${if (count == 1) "" else "s"} · browse, edit or remove", maxLines = 1)
        }
        LucideIcon(R.drawable.lucide_chevron_right, Modifier.size(18.dp), c.quiet)
    }
}

/** The app's own copies of books (for the words around quotes): how many, and the way in to them. */
@Composable
private fun CopiesRow(onOpen: () -> Unit) {
    val c = Cp.colors
    val copies by io.github.prithirajn.trackersync.source.BookCopies.copies.collectAsState()
    Row(
        Modifier.fillMaxWidth().bouncyClickable(RoundedCornerShape(0.dp), pressedScale = 0.99f, onClickLabel = "Copies for quotes") { onOpen() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.divider), contentAlignment = Alignment.Center) {
            LucideIcon(R.drawable.lucide_book_open, Modifier.size(18.dp), c.textMid)
        }
        HSpace(12)
        Column(Modifier.weight(1f)) {
            Text("Copies for quotes", style = CpType.bodyStrong, color = c.text, maxLines = 1)
            Quiet(
                if (copies.isEmpty()) "Books you're reading, kept inside the app for the lines around your quotes"
                else "${copies.size} book${if (copies.size == 1) "" else "s"} · ${sizeLabel(copies.values.sumOf { it.size })}, inside the app",
                maxLines = 2,
            )
        }
        LucideIcon(R.drawable.lucide_chevron_right, Modifier.size(18.dp), c.quiet)
    }
}

private fun sizeLabel(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
    else -> "${(bytes / 1000).coerceAtLeast(1)} KB"
}

/**
 * The copies: kept on their own for books you're reading (or not), each with where it came from and
 * its size, and books you're reading that don't have one yet, to look for now.
 */
@Composable
private fun CopiesSheet(vm: AppViewModel, state: Snapshot, onClose: () -> Unit) {
    val c = Cp.colors
    val context = LocalContext.current.applicationContext
    val bc = io.github.prithirajn.trackersync.source.BookCopies
    val copies by bc.copies.collectAsState()
    val busy by bc.busy.collectAsState()
    val statuses by bc.status.collectAsState()
    var auto by remember { mutableStateOf(bc.auto(context)) }
    PlainSheet("Copies for quotes", onClose) {
        Quiet("EPUBs from your catalogs, kept in the app's own storage (not in Downloads, not seen by other apps). Quote Studio's book photo and Fable's book-photo quotes use them for the real lines above and below your passage, when you haven't photographed its page.")
        VSpace(14)
        CpToggle(auto, { auto = it; bc.setAuto(context, it) }, "Keep copies of books you're reading")
        Quiet("One book each sync, looked for on all your catalogs by title and author; a book that isn't found is tried again in a few days.", color = c.faint)
        val missing = state.books.values
            .filter { it.status == io.github.prithirajn.trackersync.data.ReadStatus.READING && it.document !in copies && !it.isMangaOrComic }
            .sortedByDescending { it.timestamp }
        if (missing.isNotEmpty()) {
            VSpace(18)
            Text("Reading, without a copy", style = CpType.label, color = c.text)
            for (b in missing.take(12)) {
                VSpace(8)
                val st = statuses[b.document]
                val why = if (st == null && b.document !in busy) bc.lastFailure(context, b.document) else null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b.displayTitle, style = CpType.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Quiet(
                            when {
                                st != null && st.state != io.github.prithirajn.trackersync.source.BookCopies.Status.KEPT -> st.message ?: "Looking…"
                                why != null -> "Couldn't: ${why.message} · ${ago(why.at * 1000)}"
                                else -> b.author ?: "Not tried yet"
                            },
                            maxLines = 1, color = if (why != null || st?.state == io.github.prithirajn.trackersync.source.BookCopies.Status.FAILED) c.errorText else c.quiet,
                        )
                    }
                    PillButton(if (b.document in busy) "Looking…" else if (why != null) "Try again" else "Find", { if (b.document !in busy) vm.toggleBookCopy(b.document) })
                }
            }
        }
        VSpace(18)
        Text(if (copies.isEmpty()) "No copies yet" else "In the app · ${sizeLabel(copies.values.sumOf { it.size })}", style = CpType.label, color = c.text)
        for (cp in copies.values.sortedByDescending { it.at }) {
            val b = state.books[cp.document]
            VSpace(8)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(b?.displayTitle ?: cp.title, style = CpType.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Quiet("${cp.server} · ${sizeLabel(cp.size)}", maxLines = 1)
                }
                PillButton("Remove", { bc.remove(context, cp.document) })
            }
        }
        if (copies.size > 1) {
            VSpace(14)
            BigButton("Remove all copies", { bc.removeAll(context) }, filled = false)
        }
    }
}

/** Add or change a server: its name, OPDS address and sign-in, checked before it's kept. */
@Composable
private fun ServerSheet(server: Opds.Server?, onClose: () -> Unit) {
    val c = Cp.colors
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val isNew = server == null || Opds.servers.value.none { it.id == server.id }
    var name by remember { mutableStateOf(server?.name.orEmpty()) }
    var url by remember { mutableStateOf(server?.url.orEmpty()) }
    var user by remember { mutableStateOf(server?.user.orEmpty()) }
    var password by remember { mutableStateOf(server?.password.orEmpty()) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    PlainSheet(if (isNew) "Add a server" else "Edit ${server?.name}", onClose) {
        Quiet("The catalog's OPDS address. A username and password only if the server asks for one; they stay on this phone and go only to this server.")
        VSpace(14)
        CpTextField(url, { url = it.trim() }, "OPDS address", placeholder = "https://books.example.com/opds", keyboard = androidx.compose.ui.text.input.KeyboardType.Uri)
        VSpace(10)
        CpTextField(name, { name = it }, "Name", placeholder = "My Calibre")
        VSpace(10)
        CpTextField(user, { user = it }, "Username (optional)")
        VSpace(10)
        CpTextField(password, { password = it }, "Password (optional)", password = true)
        error?.let { VSpace(10); Text(it, style = CpType.small, color = c.errorText) }
        VSpace(16)
        BigButton(if (checking) "Checking…" else if (isNew) "Check and add" else "Check and save", {
            if (checking) return@BigButton
            val u = url.let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
            val s = Opds.Server(server?.id ?: java.util.UUID.randomUUID().toString(), name.ifBlank { runCatching { java.net.URI(u).host }.getOrNull() ?: "Server" }, u,
                user.ifBlank { null }, password.ifBlank { null }, search = null)
            checking = true; error = null
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        val feed = Opds.fetch(s, s.url)
                        Opds.put(context, s.copy(name = if (name.isBlank() && feed.title.isNotBlank()) feed.title else s.name))
                        runCatching { Opds.searchTemplate(context, Opds.servers.value.first { it.id == s.id }) }.getOrNull()
                    }
                }
                checking = false
                r.onSuccess { t ->
                    if (t == null) error = "Added. This server doesn't offer search, so it can only be browsed." else onClose()
                }.onFailure { x -> error = x.message ?: "Couldn't reach it" }
            }
        }, enabled = url.isNotBlank())
        if (!isNew && server != null) {
            VSpace(8)
            BigButton("Remove this server", { Opds.remove(context, server.id); onClose() }, filled = false, danger = true)
        }
    }
}

/**
 * One server, to browse like an OPDS reader: its shelves and lists to go into, its books to open,
 * more as you reach the end, and back the way you came.
 */
@Composable
private fun BrowseServer(vm: AppViewModel, state: Snapshot, server: Opds.Server, onClose: () -> Unit) {
    val c = Cp.colors
    val stack = remember { mutableStateListOf(server.url) }
    val entries = remember { mutableStateListOf<Opds.Entry>() }
    var title by remember { mutableStateOf(server.name) }
    var next by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<Opds.Entry?>(null) }
    val scope = rememberCoroutineScope()
    val here = stack.last()
    LaunchedEffect(here) {
        loading = true; error = null; entries.clear(); next = null
        val r = withContext(Dispatchers.IO) { runCatching { Opds.fetch(server, here) } }
        r.onSuccess { f -> entries.addAll(f.entries); next = f.next; if (f.title.isNotBlank()) title = f.title }.onFailure { error = it.message }
        loading = false
    }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onClose() }
    Dialog(onDismissRequest = { back() }, properties = FullScreen) {
        Column(Modifier.fillMaxSize().background(c.page).fullScreenSafe()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconCircleButton(if (stack.size > 1) R.drawable.lucide_chevron_left else R.drawable.lucide_x, if (stack.size > 1) "Back" else "Close", { back() }, tint = c.text)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(title, style = CpType.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Quiet(server.name, maxLines = 1)
                }
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
            }
            RowDivider()
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                error?.let { item(key = "err") { Quiet(it, Modifier.padding(16.dp), color = c.errorText) } }
                items(entries, key = { "e-" + it.id + it.navigation }) { e ->
                    if (e.navigation != null && e.acquisitions.isEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().bouncyClickable(RoundedCornerShape(0.dp), pressedScale = 0.99f, onClickLabel = "Open ${e.title}") { stack.add(e.navigation) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(e.title, style = CpType.bodyStrong, color = c.text, maxLines = 2)
                                e.summary?.let { Quiet(it, maxLines = 1) }
                            }
                            LucideIcon(R.drawable.lucide_chevron_right, Modifier.size(18.dp), c.quiet)
                        }
                        RowDivider()
                    } else {
                        HitRow(Hit(e.id, e, listOf(server to e))) { open = e }
                    }
                }
                next?.let { n ->
                    item(key = "more") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            PillButton(if (loading) "Loading…" else "Load more", {
                                if (loading) return@PillButton
                                loading = true
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { runCatching { Opds.fetch(server, n) } }
                                    r.onSuccess { f -> entries.addAll(f.entries.filter { x -> entries.none { it.id == x.id } }); next = f.next }.onFailure { error = it.message }
                                    loading = false
                                }
                            })
                        }
                    }
                }
            }
        }
        open?.let { e -> HitSheet(vm, state, Hit(e.id, e, listOf(server to e))) { open = null } }
    }
}

/** How one pasted address went: being checked, kept (as what), already here, or why not. */
private data class Tried(val address: String, val state: String, val name: String? = null)

/**
 * Many servers at once: paste a list (one per line, or separated by spaces or commas, with or
 * without http://), and each is checked a few at a time; the ones that answer with a catalog are
 * kept, the rest are listed with why. A sign-in, if given, is used for all of them.
 */
@Composable
private fun PasteServersSheet(onClose: () -> Unit) {
    val c = Cp.colors
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val tried = remember { mutableStateListOf<Tried>() }
    var running by remember { mutableStateOf(false) }
    val addresses = remember(text) { Opds.addressesIn(text) }
    val existing = Opds.servers.value
    val fresh = addresses.filter { a -> existing.none { Opds.sameAddress(it.url, a) } }
    PlainSheet("Paste a list of servers", onClose) {
        Quiet("One address per line (or separated by spaces or commas). A bare address like 192.168.1.20:8080 is fine: the catalog is looked for where servers usually keep it. The ones that answer are added; the rest are left out.")
        VSpace(12)
        Box(
            Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(16.dp)).background(c.field).border(1.dp, c.hairline, RoundedCornerShape(16.dp)).padding(14.dp),
        ) {
            if (text.isEmpty()) Text("http://192.168.1.20:8080\nhttps://books.example.com/opds", style = CpType.body, color = c.faint)
            androidx.compose.foundation.text.BasicTextField(
                text, { text = it }, Modifier.fillMaxSize(),
                textStyle = CpType.body.copy(color = c.text),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri),
            )
        }
        VSpace(6)
        Quiet(
            when {
                addresses.isEmpty() -> "No addresses found yet."
                fresh.size < addresses.size -> "${addresses.size} addresses · ${addresses.size - fresh.size} already added"
                else -> "${addresses.size} address${if (addresses.size == 1) "" else "es"}"
            },
            color = c.textMid,
        )
        VSpace(10)
        CpTextField(user, { user = it }, "Username for all (optional)")
        VSpace(8)
        CpTextField(password, { password = it }, "Password for all (optional)", password = true)
        VSpace(14)
        val done = tried.count { it.state == "added" }
        BigButton(
            when {
                running -> "Checking… ${tried.count { it.state != "checking" }} of ${tried.size}"
                tried.isNotEmpty() && !running -> "Done · $done added"
                else -> "Connect ${fresh.size}"
            },
            {
                if (running) return@BigButton
                if (tried.isNotEmpty() && fresh.isEmpty()) { onClose(); return@BigButton }
                tried.clear()
                tried.addAll(fresh.map { Tried(it, "checking") })
                running = true
                scope.launch {
                    // A few at a time, so a long list doesn't swamp the phone's connection.
                    val gate = kotlinx.coroutines.sync.Semaphore(6)
                    fresh.map { a ->
                        async {
                            gate.acquire()
                            try {
                                val r = withContext(Dispatchers.IO) { runCatching { Opds.discover(a, user.ifBlank { null }, password.ifBlank { null }) } }
                                val i = tried.indexOfFirst { it.address == a }
                                r.onSuccess { s ->
                                    if (Opds.servers.value.none { Opds.sameAddress(it.url, s.url) }) {
                                        withContext(Dispatchers.IO) { Opds.put(context, s) }
                                        if (i >= 0) tried[i] = Tried(a, "added", s.name)
                                    } else if (i >= 0) tried[i] = Tried(a, "already here")
                                }.onFailure { e -> if (i >= 0) tried[i] = Tried(a, e.message ?: "no catalog there") }
                            } finally { gate.release() }
                        }
                    }.awaitAll()
                    running = false
                    text = ""
                }
            },
            enabled = running || fresh.isNotEmpty() || tried.isNotEmpty(),
        )
        if (tried.isNotEmpty()) {
            VSpace(14)
            Text("$done added · ${tried.count { it.state != "added" && it.state != "checking" }} not", style = CpType.label, color = c.text)
            VSpace(6)
            for (t in tried.sortedBy { when (it.state) { "added" -> 0; "checking" -> 1; else -> 2 } }) {
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (t.state) {
                        "checking" -> CircularProgressIndicator(Modifier.size(12.dp), color = c.accent, strokeWidth = 1.5.dp)
                        "added" -> Box(Modifier.size(8.dp).clip(CircleShape).background(c.good))
                        else -> Box(Modifier.size(8.dp).clip(CircleShape).background(c.faint))
                    }
                    HSpace(10)
                    Column(Modifier.weight(1f)) {
                        Text(t.name ?: t.address.removePrefix("http://").removePrefix("https://"), style = CpType.small, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (t.state != "added" && t.state != "checking") Text(t.state, style = CpType.small, color = c.quiet, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** A download in the book sheet: the file, the server, and how it's going or how it ended. */
private data class Download(
    val server: Opds.Server,
    val acq: Opds.Acquisition,
    val progress: Opds.Progress,
    val running: Boolean,
    val saved: Opds.Saved? = null,
    val error: String? = null,
)

/** "4.2 MB", "820 KB". */
private fun bytes(n: Long): String = when {
    n >= 1_000_000_000 -> String.format(java.util.Locale.getDefault(), "%.2f GB", n / 1e9)
    n >= 1_000_000 -> String.format(java.util.Locale.getDefault(), "%.1f MB", n / 1e6)
    n >= 1_000 -> "${n / 1000} KB"
    else -> "$n B"
}

/** "8 s", "2 min 10 s". */
private fun duration(secs: Long): String = if (secs < 60) "$secs s" else "${secs / 60} min" + (if (secs % 60 > 0 && secs < 600) " ${secs % 60} s" else "")

/**
 * The download, in full: the format and the server, the file's name, a bar (filling, or moving
 * when the server doesn't say the size), so much of so much, the speed and the time left, and
 * Cancel. Once done: where it went and how big it is, to open, share or find in Downloads; or
 * what went wrong.
 */
@Composable
private fun DownloadCard(d: Download, onCancel: () -> Unit, onDismiss: () -> Unit, onSaid: (String) -> Unit) {
    val c = Cp.colors
    val context = LocalContext.current
    val p = d.progress
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, if (d.error != null) c.warn else if (d.saved != null) c.good else c.accentRing, shape).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip(d.acq.format, color = c.text, background = c.divider)
            HSpace(8)
            Text(d.server.name, style = CpType.small, color = c.quiet, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            when {
                d.running -> CircularProgressIndicator(Modifier.size(14.dp), color = c.accent, strokeWidth = 1.5.dp)
                d.saved != null -> Text("✓ Saved", style = CpType.label, color = c.good)
                else -> Text(if (d.error == "Cancelled") "Cancelled" else "Failed", style = CpType.label, color = c.warn)
            }
        }
        (d.saved?.name ?: p.name)?.let { n ->
            VSpace(8)
            Text(n, style = CpType.bodyStrong, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (d.running) {
            VSpace(10)
            if (p.total > 0) {
                val frac = (p.done.toFloat() / p.total).coerceIn(0f, 1f)
                androidx.compose.material3.LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CpShapes.pill), color = c.accent, trackColor = c.divider)
            } else {
                androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp).clip(CpShapes.pill), color = c.accent, trackColor = c.divider)
            }
            VSpace(8)
            val line = when {
                p.stage != "Downloading" -> p.stage + "…"
                p.total > 0 -> {
                    val left = if (p.bytesPerSec > 1) ((p.total - p.done) / p.bytesPerSec).toLong() else -1
                    listOfNotNull(
                        "${bytes(p.done)} of ${bytes(p.total)}",
                        "${(p.done * 100 / p.total)}%",
                        p.bytesPerSec.takeIf { it > 1 }?.let { "${bytes(it.toLong())}/s" },
                        left.takeIf { it >= 0 }?.let { "about ${duration(it)} left" },
                    ).joinToString(" · ")
                }
                else -> listOfNotNull(bytes(p.done), p.bytesPerSec.takeIf { it > 1 }?.let { "${bytes(it.toLong())}/s" }, "size not given by the server").joinToString(" · ")
            }
            Text(line, style = CpType.small, color = c.textMid)
            VSpace(10)
            PillButton("Cancel", onCancel)
        }
        d.saved?.let { s ->
            VSpace(6)
            Text("${bytes(s.size)} · ${s.type.substringAfter('/').substringBefore('+').uppercase().take(12)} · Downloads › ${io.github.prithirajn.trackersync.Edition.name}", style = CpType.small, color = c.quiet)
            VSpace(12)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton("Open", {
                    val view = Intent(Intent.ACTION_VIEW).setDataAndType(s.uri, s.type)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (runCatching { context.startActivity(Intent.createChooser(view, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure) onSaid("No app here opens this kind of file.")
                }, Modifier.weight(1f))
                BigButton("Share", {
                    val send = Intent(Intent.ACTION_SEND).setType(s.type).putExtra(Intent.EXTRA_STREAM, s.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { context.startActivity(Intent.createChooser(send, "Send the book").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }, Modifier.weight(1f), filled = false)
                BigButton("Downloads", {
                    runCatching { context.startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { onSaid("Couldn't open your Downloads here.") }
                }, Modifier.weight(1f), filled = false)
            }
        }
        d.error?.let { err ->
            if (err != "Cancelled") { VSpace(6); Text(err, style = CpType.small, color = c.errorText) }
            VSpace(8)
            PillButton("Dismiss", onDismiss)
        }
    }
}
