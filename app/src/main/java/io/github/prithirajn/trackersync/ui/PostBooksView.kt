package io.github.prithirajn.trackersync.ui

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
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.source.PostBooks
import io.github.prithirajn.trackersync.sync.Matcher
import io.github.prithirajn.trackersync.sync.ShareJobs
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import io.github.prithirajn.trackersync.ui.theme.CpType
import io.github.prithirajn.trackersync.ui.theme.Lora
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
    val reading = running.entries.firstOrNull { it.key.startsWith("post:") }?.value
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
        if (PostBooks.platformOf(l) == null && io.github.prithirajn.trackersync.source.BookPrices.trackable(l) != null) {
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
    // Every book picked to start with, except the ones already in your library.
    fun inLibrary(f: PostBooks.Found) = state.books.values.any { b ->
        (f.isbn != null && b.isbn?.filter { it.isDigit() } == f.isbn) || Matcher.sameBook(f.title, f.author, b.title ?: "", b.author)
    }
    val picked = remember(t.at) { mutableStateListOf<String>().also { l -> l.addAll(t.books.filter { !inLibrary(it) }.map { it.title }) } }
    var said by remember(t.at) { mutableStateOf<String?>(null) }
    val chosen = t.books.filter { it.title in picked }
    // What was read on the post's pictures, to pick a title the finder missed, or one typed.
    val scope = rememberCoroutineScope()
    var showWords by remember(t.at) { mutableStateOf(t.books.isEmpty()) }
    var typed by remember(t.at) { mutableStateOf("") }
    var looking by remember(t.at) { mutableStateOf<String?>(null) }
    fun look(name: String) {
        if (looking != null || name.isBlank()) return
        looking = name
        scope.launch {
            val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { PostBooks.lookUp(name) }.getOrNull() }
            looking = null
            when {
                f == null -> said = "Couldn't look that up"
                t.books.any { Matcher.sameBook(it.title, it.author, f.title, f.author) } -> said = "${f.title} is already here"
                else -> {
                    picked.add(f.title); onAdd(f); typed = ""
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
            if (!t.post.url.startsWith("media:")) PillButton("Open", { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(t.post.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } })
        }
        t.post.caption?.takeIf { it.isNotBlank() }?.let { VSpace(8); Quiet(it, maxLines = 3) }
        if (t.books.isEmpty()) {
            VSpace(8)
            Quiet("No books found in it.", color = c.faint)
        } else {
            VSpace(12)
            // Three to a row: tap one to leave it out (or put it back).
            for (row in t.books.chunked(3)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (f in row) Box(Modifier.weight(1f)) {
                        val on = f.title in picked
                        val have = inLibrary(f)
                        Column(Modifier.fillMaxWidth().bouncyClickable(RoundedCornerShape(8.dp), pressedScale = 0.96f, onClickLabel = if (on) "Leave out" else "Pick") {
                            if (on) picked.remove(f.title) else picked.add(f.title)
                        }) {
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                Box {
                                    BookCover(f.cover, f.title, this@BoxWithConstraints.maxWidth, this@BoxWithConstraints.maxWidth * 1.5f)
                                    Box(
                                        Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp).clip(CircleShape)
                                            .background(if (on) c.accent else c.page.copy(alpha = 0.75f))
                                            .border(1.5.dp, if (on) c.accent else c.hairline, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) { if (on) Text("✓", style = CpType.small.copy(fontSize = 12.sp), color = androidx.compose.ui.graphics.Color.White) }
                                }
                            }
                            VSpace(5)
                            Text(f.title, fontFamily = Lora, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp, color = if (on) c.text else c.quiet, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            f.author?.let { Text(it, style = CpType.small.copy(fontSize = 11.sp), color = c.textMid, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            if (have) Text("In your library", style = CpType.small.copy(fontSize = 11.sp), color = c.accentText, maxLines = 1)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        VSpace(2)
        Text(
            if (showWords) "Hide what was read" else "Missing one? See what was read",
            style = CpType.small, color = c.accentText,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { showWords = !showWords }.padding(vertical = 6.dp),
        )
        if (showWords) {
            if (t.seen.isEmpty()) Quiet(if (t.post.url.startsWith("media:")) "No words could be read on them." else "No words could be read on its picture.", color = c.faint)
            else {
                Quiet("Words read on its picture. Tap one that's a title to look it up.")
                VSpace(6)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (w in t.seen.take(24)) PillButton(if (looking == w) "Looking…" else w.take(40), { look(w) })
                }
            }
            VSpace(8)
            CpTextField(typed, { typed = it }, "Or type a title", placeholder = "Title (and author)")
            VSpace(6)
            PillButton(if (looking != null && looking == typed) "Looking…" else "Look it up", {
                val parts = typed.split(Regex("\\s+by\\s+|\\s+[—–-]\\s+"), limit = 2)
                if (parts.size == 2) {
                    looking = typed
                    val name = parts[0]; val by = parts[1]
                    scope.launch {
                        val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { PostBooks.lookUp(name, by) }.getOrNull() }
                        looking = null
                        if (f != null && t.books.none { Matcher.sameBook(it.title, it.author, f.title, f.author) }) {
                            picked.add(f.title); onAdd(f); typed = ""
                            said = if (f.from == "typed") "Added as written (Google Books doesn't list it yet)" else "Found ${f.title}"
                        } else said = if (f == null) "Couldn't look that up" else "${f.title} is already here"
                    }
                } else look(typed)
            }, outlined = false)
            VSpace(8)
        }
        VSpace(4)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (chosen.isNotEmpty()) {
                PillButton("+ Want to read (${chosen.size})", { vm.addPostBooks(chosen) { said = it } }, outlined = false)
                PillButton("Add to price tracker", { vm.trackPostBooks(chosen) { said = it } })
            }
            if (!t.post.url.startsWith("media:")) PillButton("Look again", onAgain)
            PillButton("Remove", onRemove, danger = true)
        }
        said?.let { VSpace(6); AnimatedQuiet(it, color = c.textMid) }
    }
}
