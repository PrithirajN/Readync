package io.github.prithirajn.readync.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.prithirajn.readync.R
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.source.PostBooks
import io.github.prithirajn.readync.sync.Matcher
import io.github.prithirajn.readync.sync.ShareJobs
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.CpShapes
import io.github.prithirajn.readync.ui.theme.CpType
import io.github.prithirajn.readync.ui.theme.Lora
import kotlinx.coroutines.launch

/** A post link shared to the app (from TikTok or Instagram), for the Posts view to open with. */
object PostShare {
    val pending = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** Screenshots or a screen recording shared to the app. */
    val media = kotlinx.coroutines.flow.MutableStateFlow<List<android.net.Uri>?>(null)
}

/**
 * Catalogs › Posts: paste (or share) a TikTok, Instagram or YouTube post and the books in it are
 * found — from its caption, the words on its picture and its hashtags, each checked against
 * Google Books — then added to your Want to Read or their prices tracked, all or a few. The posts
 * you've looked at stay listed, newest first.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PostBooksView(vm: AppViewModel, state: Snapshot) {
    val c = Cp.colors
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val traps = remember { mutableStateListOf<PostBooks.Trap>().also { it.addAll(PostBooks.saved(context)) } }
    var link by remember { mutableStateOf("") }
    var working by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Links (shared or pasted) are read in the background, so leaving the app doesn't stop them;
    // a notification says what came of each. Here: where each has got to, and the newest marked.
    var fresh by remember { mutableStateOf<Long?>(null) }
    val running by ShareJobs.running.collectAsState()
    val readingKey = running.keys.firstOrNull { it.startsWith("post:") }
    val reading = readingKey?.let { running[it] }
    val lastPost by ShareJobs.lastPost.collectAsState()
    LaunchedEffect(lastPost) {
        val (url, at) = lastPost ?: return@LaunchedEffect
        traps.clear(); traps.addAll(PostBooks.saved(context))
        fresh = at
        if (PostBooks.linkIn(link) == url) link = ""
        val t = traps.firstOrNull { it.at == at && it.post.url == url }
        error = if (t != null && t.books.isEmpty()) "No books named in that post's caption or picture. Under it, see the words read on its picture: tap a title, or type one." else null
    }
    val postError by ShareJobs.postError.collectAsState()
    LaunchedEffect(postError) { postError?.let { (_, why) -> error = why; ShareJobs.postError.value = null } }

    fun find(url: String, shared: Boolean = false) {
        val l = PostBooks.linkIn(url) ?: url.trim()
        if (!l.startsWith("http")) { error = "Paste the post's link (Share › Copy link in TikTok or Instagram)."; return }
        // A shop's page pasted here: its price tracked instead.
        if (PostBooks.platformOf(l) == null && io.github.prithirajn.readync.source.BookPrices.trackable(l) != null) {
            ShareJobs.trackPrice(context, l)
            link = ""
            vm.notify("Tracking that book's price", "It'll be on Catalogs › Deals in a moment")
            return
        }
        error = null
        // A shared link is already on its way (MainActivity started it); a pasted one starts here.
        if (!shared) ShareJobs.findPost(context, l)
        vm.notify(
            "Looking for books in that ${PostBooks.platformOf(l)?.let { "$it " }.orEmpty()}post",
            "Takes a few seconds; you'll get a notification, so you can leave the app",
        )
    }

    fun findIn(uris: List<android.net.Uri>) {
        if (uris.isEmpty() || working != null) return
        error = null
        working = "Reading the pictures"
        vm.notify("Looking for books in ${if (uris.size == 1) "that picture" else "those ${uris.size} pictures"}", "You'll see them on Catalogs › Posts")
        scope.launch {
            val r = runCatching { PostBooks.fromMedia(context, uris) { working = it } }
            working = null
            r.onSuccess { t ->
                traps.add(0, t)
                PostBooks.save(context, traps)
                fresh = t.at
                if (t.books.isEmpty()) error = "No books found in what was on screen. Covers and titles held up for a moment read best; under it, tap a title among the words read, or type one."
                vm.notify(
                    if (t.books.isEmpty()) "No books found in what was on screen" else "Found ${t.books.size} book${if (t.books.size == 1) "" else "s"}",
                    t.books.take(3).joinToString(", ") { it.title }.ifBlank { null },
                )
            }.onFailure {
                error = it.message ?: "Couldn't read them"
                vm.notify("Couldn't read them", it.message, error = true)
            }
        }
    }
    val sharedMedia by PostShare.media.collectAsState()
    LaunchedEffect(sharedMedia) { sharedMedia?.let { PostShare.media.value = null; findIn(it) } }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(20),
    ) { uris -> findIn(uris) }

    // A link shared to the app from TikTok or Instagram: looked at straight away.
    val shared by PostShare.pending.collectAsState()
    LaunchedEffect(shared) {
        shared?.let { link = it; PostShare.pending.value = null; find(it, shared = true) }
    }

    Column(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        VSpace(14)
        Text("Books in a post", style = CpType.section, color = c.text)
        Quiet("Paste a TikTok, Instagram, Threads or YouTube post (or share it to Readync) and the books it names are found: from its caption, the words on its picture and its hashtags, each checked against Google Books. For books only shown in the video, share screenshots or a screen recording of it.")
        VSpace(10)
        CpTextField(link, { link = it; error = null }, "Post link", placeholder = "https://www.tiktok.com/@…/video/…", keyboard = androidx.compose.ui.text.input.KeyboardType.Uri)
        VSpace(8)
        val busy = working ?: reading
        BigButton(if (busy != null) "$busy…" else "Find the books", { find(link) }, busy = busy != null)
        // Where it's got to (the button only spins), and a way to stop it.
        if (busy != null) {
            VSpace(6)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Quiet("$busy… You can leave the app; a notification says when it's done.", Modifier.weight(1f))
                if (readingKey != null && working == null) { HSpace(8); PillButton("Stop", { ShareJobs.stop(context, readingKey) }) }
            }
        }
        VSpace(6)
        // Books shown only in the video (covers held up, words on screen): screenshots or a screen recording of it.
        BigButton("From screenshots or a recording", {
            picker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }, filled = false, enabled = working == null)
        error?.let { VSpace(6); Quiet(it, color = c.errorText) }
    }

    if (traps.isEmpty() && working == null) {
        Quiet(
            "Nothing yet. Book posts that list titles in the caption, or show covers in the picture, work best.",
            Modifier.padding(16.dp), color = c.faint,
        )
    }
    for (t in traps.toList()) {
        TrapCard(
            vm, state, t, isNew = fresh == t.at,
            onRemove = { traps.remove(t); PostBooks.save(context, traps) },
            onAgain = { find(t.post.url) },
            onAdd = { f ->
                val i = traps.indexOfFirst { it.at == t.at && it.post.url == t.post.url }
                if (i >= 0) { traps[i] = traps[i].copy(books = traps[i].books + f); PostBooks.save(context, traps) }
                if (error != null && traps.firstOrNull()?.at == t.at) error = null
            },
        )
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrapCard(vm: AppViewModel, state: Snapshot, t: PostBooks.Trap, isNew: Boolean = false, onRemove: () -> Unit, onAgain: () -> Unit, onAdd: (PostBooks.Found) -> Unit) {
    val c = Cp.colors
    val context = LocalContext.current
    fun inLibrary(f: PostBooks.Found) = state.books.values.any { b ->
        (f.isbn != null && b.isbn?.filter { it.isDigit() } == f.isbn) || Matcher.sameBook(f.title, f.author, b.title ?: "", b.author)
    }
    // The book the three buttons act on: the first one not in your library, to start with.
    var focus by remember(t.at) { mutableStateOf(t.books.firstOrNull { !inLibrary(it) }?.title ?: t.books.firstOrNull()?.title) }
    var said by remember(t.at) { mutableStateOf<String?>(null) }
    var details by remember(t.at) { mutableStateOf<PostBooks.Found?>(null) }
    // What was read on the post's pictures, to pick a title the finder missed, or one typed.
    val scope = rememberCoroutineScope()
    var showWords by remember(t.at) { mutableStateOf(t.books.isEmpty()) }
    var typed by remember(t.at) { mutableStateOf("") }
    var looking by remember(t.at) { mutableStateOf<String?>(null) }
    fun look(name: String) {
        if (looking != null || name.isBlank()) return
        looking = name
        // "Title by Author" (or "Title - Author") typed: both used.
        val parts = name.split(Regex("\\s+by\\s+|\\s+[—–-]\\s+"), limit = 2)
        scope.launch {
            val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { PostBooks.lookUp(parts[0], parts.getOrNull(1)) }.getOrNull() }
            looking = null
            when {
                f == null -> said = "Couldn't look that up"
                t.books.any { Matcher.sameBook(it.title, it.author, f.title, f.author) } -> said = "${f.title} is already here"
                else -> {
                    focus = f.title; onAdd(f); typed = ""
                    said = if (f.from == "typed") "Added as written (Google Books doesn't list it yet)" else "Found ${f.title}"
                }
            }
        }
    }

    VSpace(16)
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(CpShapes.card).background(c.surface)
            .border(if (isNew) 2.dp else 1.dp, if (isNew) c.accent else c.hairline, CpShapes.card).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(c.accentTint), contentAlignment = Alignment.Center) {
                Text(t.post.platform.take(1), style = CpType.bodyStrong, color = c.accentText)
            }
            HSpace(10)
            Column(Modifier.weight(1f)) {
                Text(listOfNotNull(t.post.platform, t.post.creator?.let { if (it.startsWith("@")) it else "@$it" }).joinToString(" · "), style = CpType.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Quiet((if (isNew) "New · " else "") + "${t.books.size} book${if (t.books.size == 1) "" else "s"} · ${ago(t.at * 1000)}", maxLines = 1)
            }
            // The card's own actions, small and together: look again, open the post, remove.
            if (!t.post.url.startsWith("media:")) {
                IconCircleButton(R.drawable.lucide_refresh_cw, "Look again", onAgain)
                HSpace(6)
                IconCircleButton(R.drawable.lucide_globe, "Open the post", { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(t.post.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } })
                HSpace(6)
            }
            IconCircleButton(R.drawable.lucide_trash, "Remove", onRemove, tint = c.errorText)
        }
        t.post.caption?.takeIf { it.isNotBlank() }?.let { VSpace(8); Quiet(it, maxLines = 2) }
        if (t.books.isEmpty()) {
            VSpace(8)
            Quiet("No books found in it.", color = c.faint)
        } else {
            VSpace(12)
            // Just the covers, in a row (scrolls when there are many): tap one to choose it.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (f in t.books) {
                    val on = f.title == focus
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .border(if (on) 2.dp else 1.dp, if (on) c.accent else c.hairline, RoundedCornerShape(8.dp))
                            .bouncyClickable(RoundedCornerShape(8.dp), pressedScale = 0.96f, onClickLabel = "Choose ${f.title}") { focus = f.title },
                    ) {
                        BookCover(f.cover, f.title, 76.dp, 114.dp)
                        if (inLibrary(f)) Box(
                            Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp).clip(CircleShape).background(c.accent),
                            contentAlignment = Alignment.Center,
                        ) { Text("✓", style = CpType.small.copy(fontSize = 10.sp), color = androidx.compose.ui.graphics.Color.White) }
                    }
                }
            }
            val f = t.books.firstOrNull { it.title == focus }
            if (f != null) {
                VSpace(8)
                Text(
                    f.title + (f.author?.let { " · $it" } ?: "") + if (inLibrary(f)) " · in your library" else "",
                    style = CpType.small, color = c.textMid, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                VSpace(8)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Details", { details = f }, Modifier.weight(1f))
                    PillButton(if (inLibrary(f)) "In library" else "Want to read", { vm.addPostBooks(listOf(f)) { said = it } }, Modifier.weight(1f), outlined = inLibrary(f))
                    PillButton("Track", { vm.trackPostBooks(listOf(f)) { said = it } }, Modifier.weight(1f))
                }
            }
        }
        // A book the finder missed: a title among the words read off the picture, or one typed.
        VSpace(10)
        RowDivider()
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { showWords = !showWords }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (t.books.isEmpty()) "Find the book yourself" else "Missing one?", style = CpType.body, color = c.text, modifier = Modifier.weight(1f))
            Text(if (showWords) "Hide" else "Show", style = CpType.small, color = c.accentText)
        }
        if (showWords) {
            if (t.seen.isEmpty()) Quiet(
                when {
                    // Looked at before the words were kept: Look again shows them.
                    t.books.isEmpty() && !t.post.url.startsWith("media:") -> "Nothing kept from its picture yet: tap ↻ above to read it once more."
                    t.post.url.startsWith("media:") -> "No words could be read on them."
                    else -> "No words could be read on its picture."
                },
                color = c.faint,
            )
            else {
                Quiet("Words read on its picture: tap one that's a title.")
                VSpace(6)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (w in t.seen.take(24)) PillButton(if (looking == w) "Looking…" else w.take(40), { look(w) })
                }
            }
            VSpace(10)
            CpTextField(
                typed, { typed = it }, "Or type it", placeholder = "Title by Author",
                trailing = {
                    if (looking != null && looking == typed) CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                    else Box(Modifier.size(36.dp).clip(CircleShape).clickable(enabled = typed.isNotBlank()) { look(typed.trim()) }, contentAlignment = Alignment.Center) {
                        LucideIcon(R.drawable.lucide_search, Modifier.size(18.dp), if (typed.isNotBlank()) c.accent else c.faint)
                    }
                },
            )
        }
        said?.let { VSpace(6); AnimatedQuiet(it, color = c.textMid) }
    }
    details?.let { d -> PostBookDetailsSheet(vm, d, onWant = { vm.addPostBooks(listOf(d)) { said = it } }, onTrack = { vm.trackPostBooks(listOf(d)) { said = it } }, onClose = { details = null }) }
}

/** A book found in a post, looked at more closely: its description, ratings and genres. */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PostBookDetailsSheet(vm: AppViewModel, f: PostBooks.Found, onWant: () -> Unit, onTrack: () -> Unit, onClose: () -> Unit) {
    val c = Cp.colors
    var info by remember(f) { mutableStateOf<io.github.prithirajn.readync.source.QuickDetails.Info?>(null) }
    var failed by remember(f) { mutableStateOf(false) }
    var more by remember(f) { mutableStateOf(false) }
    LaunchedEffect(f) { vm.postBookDetails(f) { r -> if (r == null) failed = true else info = r } }
    GuardedSheet(onDismissRequest = onClose, containerColor = c.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row {
                BookCover(info?.cover ?: f.cover, f.title, 72.dp, 108.dp)
                HSpace(14)
                Column(Modifier.weight(1f)) {
                    Text(f.title, fontFamily = Lora, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 22.sp, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    f.author?.let { VSpace(2); Text(it, style = CpType.body, color = c.textMid, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    info?.let { i ->
                        val facts = listOfNotNull(i.year?.toString(), i.pages?.let { "$it pages" }).joinToString(" · ")
                        if (facts.isNotEmpty()) { VSpace(2); Quiet(facts) }
                        i.rating?.let { r ->
                            VSpace(8)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("★ " + String.format(java.util.Locale.US, "%.1f", r), style = CpType.bodyStrong, color = c.accentText)
                                i.ratingsCount?.let { n -> HSpace(6); Quiet("${String.format(java.util.Locale.US, "%,d", n)} ratings") }
                            }
                        }
                    }
                }
            }
            VSpace(14)
            val i = info
            when {
                i == null && !failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
                    HSpace(10)
                    Quiet("Asking Hardcover, Goodreads, Seekquel and Google Books…")
                }
                i == null -> Quiet("Couldn't get its details just now.", color = c.errorText)
                else -> {
                    // Each site's rating, small, under the overall one.
                    if (i.ratings.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for ((site, r) in i.ratings) Chip("$site ${String.format(java.util.Locale.US, "%.1f", r)}")
                        }
                        VSpace(12)
                    }
                    if (i.genres.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (g in i.genres) Chip(g, color = c.accentText, background = c.accentTint)
                        }
                        VSpace(12)
                    }
                    val d = i.description
                    if (d != null) {
                        Text(d, style = CpType.body, color = c.text, maxLines = if (more) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { more = !more })
                        VSpace(8)
                    } else Quiet("No description on any site yet.", color = c.faint)
                    if (i.sources.isNotEmpty()) { VSpace(4); Quiet("From " + i.sources.joinToString(", "), color = c.faint) }
                }
            }
            VSpace(16)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Want to read", { onWant(); onClose() }, Modifier.weight(1f), outlined = false)
                PillButton("Track price", { onTrack(); onClose() }, Modifier.weight(1f))
            }
        }
    }
}
