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
            item(key = "deals") { DealsList(state) { doc -> AppActions.pending.value = AppAction.OpenBook(doc) } }
        } else if (servers.isEmpty()) {
            item(key = "empty") { NoServers(onAdd = { addingNew = true }, onPaste = { pasting = true }, onSuggest = { name, url -> adding = Opds.Server(java.util.UUID.randomUUID().toString(), name, url) }) }
        } else if (searched.isEmpty()) {
            item(key = "servers-head") { CatalogHead("Your servers") }
            items(servers, key = { "srv-" + it.id }) { s -> ServerRow(s, onBrowse = { browsing = s }, onEdit = { adding = s }) }
            item(key = "hint") {
                Quiet(
                    "Type above and all ${servers.size} are searched at once. The same book on several servers shows once, with each place to get it.",
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = c.faint,
                )
            }
        } else {
            // Each server's answer, as it comes in; tap one to see only its books.
            item(key = "status") {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                status.entries.filter { it.value.error != null }.forEach { (id, st) ->
                    servers.firstOrNull { it.id == id }?.let { Quiet("${it.name}: ${st.error}", Modifier.padding(horizontal = 16.dp), color = c.faint, maxLines = 2) }
                }
            }
            val still = status.values.any { it.running }
            if (hits.isEmpty() && !still) item(key = "none") { Quiet("Nothing on any server for “$searched”.", Modifier.padding(16.dp)) }
            items(hits, key = { "h-" + it.key }) { h -> HitRow(h) { open = h } }
        }
    }

    open?.let { h -> HitSheet(vm, state, h) { open = null } }
    if (addingNew) ServerSheet(null, onClose = { addingNew = false })
    if (pasting) PasteServersSheet { pasting = false }
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
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    runCatching { BitmapFactory.decodeByteArray(b, 0, b.size, if (b.size > 400_000) opts else null)?.asImageBitmap() }.getOrNull()
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
        CatalogCover(h.places.first().first, e.image, e.title, 62.dp, 93.dp)
        HSpace(14)
        Column(Modifier.weight(1f)) {
            Text(e.title, fontFamily = Lora, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
            e.authors.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(", "), style = CpType.small, color = c.textMid, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            e.series?.let { Text(it + (e.seriesIndex?.let { n -> " #$n" } ?: ""), style = CpType.small, color = c.accentText, maxLines = 1) }
            VSpace(6)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                for (f in h.formats.take(4)) Chip(f, color = c.text, background = c.divider)
                for ((s, _) in h.places.distinctBy { it.first.id }) Chip(s.name, color = c.accentText, background = c.accentTint)
            }
        }
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
    var getting by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var got by remember { mutableStateOf<Pair<Uri, String?>?>(null) }
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
                            val key = server.id + a.href
                            val busy = getting == key
                            PillButton(if (busy) "${a.format} ${(progress * 100).toInt()}%" else "↓ ${a.format}" + if (a.rel?.endsWith("/buy") == true) " (buy)" else "", {
                                if (getting != null) return@PillButton
                                getting = key; progress = 0f; said = null
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { runCatching { Opds.download(context.applicationContext, server, a, e.title) { p -> progress = p } } }
                                    getting = null
                                    r.onSuccess { uri -> got = uri to a.type; said = "Saved to Downloads › ${io.github.prithirajn.trackersync.Edition.name}" }
                                        .onFailure { x -> said = x.message ?: "Couldn't download it" }
                                }
                            }, outlined = !busy)
                        }
                    }
                }
            }
        }
        said?.let { VSpace(10); AnimatedQuiet(it, color = c.textMid) }
        got?.let { (uri, type) ->
            VSpace(10)
            BigButton("Open it", {
                val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type ?: context.contentResolver.getType(uri) ?: "application/octet-stream")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { context.startActivity(Intent.createChooser(view, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure) said = "No app here opens this kind of file."
            })
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
