package io.github.prithirajn.readync.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.prithirajn.readync.data.Book
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.source.CrossPointClient
import io.github.prithirajn.readync.source.EpubText
import io.github.prithirajn.readync.sync.KoPlacement
import io.github.prithirajn.readync.sync.SyncEngine
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.CpShapes
import io.github.prithirajn.readync.ui.theme.CpType
import java.io.File

/** What a bookmark is called in a list: its name, else its page's opening words. */
/** The book [document] belongs to here: its own, or the one it was merged into (null: no such book any more). */
internal fun bookmarkBook(state: Snapshot, document: String): Book? =
    state.books[document] ?: state.books.values.firstOrNull { document in it.aliases }

/** Every bookmark with its book (ones whose book is gone are left out), so the list and its count agree. */
internal fun allBookmarks(state: Snapshot): List<Pair<Book, CrossPointClient.Bookmark>> =
    state.cpBookmarks.flatMap { (d, l) -> bookmarkBook(state, d)?.let { bk -> l.map { bk to it } } ?: emptyList() }
        .distinctBy { "${it.first.document}|${it.second.id}" }

/** Whether [b] (kept under [document]) has been turned into a highlight or ticked off. */
internal fun bookmarkDone(state: Snapshot, document: String, b: CrossPointClient.Bookmark): Boolean =
    "$document|${b.id}" in state.bookmarkDone || state.cpBookmarks.any { (d, l) -> d != document && l.any { it.id == b.id } && "$d|${b.id}" in state.bookmarkDone }

internal fun bookmarkLabel(b: CrossPointClient.Bookmark): String =
    b.name?.trim()?.takeIf { it.isNotEmpty() } ?: b.summary?.trim()?.takeIf { it.isNotEmpty() }?.let { it + "…" } ?: "Bookmark"

internal fun bookmarkWhere(b: CrossPointClient.Bookmark): String = listOfNotNull(
    b.percentage?.let { "${Math.round(it * 100)}%" },
    b.updatedAt.takeIf { it > 0 }?.let { ago(it * 1000) },
).joinToString(" · ")

/**
 * A book's bookmarks (from your CrossPoint device), and its book file. A bookmark opens to its page,
 * where it can be turned into a highlight.
 */
@Composable
internal fun BookmarksSection(vm: AppViewModel, state: Snapshot, book: Book) {
    val c = Cp.colors
    LaunchedEffect(book.document) { vm.refreshBookmarks(book.document) }
    val marks = allBookmarks(state).filter { it.first.document == book.document }.map { it.second }.sortedBy { it.percentage ?: 0.0 }
    var open by remember { mutableStateOf<CrossPointClient.Bookmark?>(null) }
    VSpace(14)
    val checked = state.extras["bookmarksChecked:${book.document}"]?.toLongOrNull()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Quiet(checked?.let { "Checked with CrossPoint ${ago(it)}" } ?: "Checking with CrossPoint…", Modifier.weight(1f), color = c.faint)
        PillButton("Check again", { vm.refreshBookmarks(book.document) })
    }
    VSpace(10)
    if (marks.isEmpty()) {
        Quiet("No bookmarks on CrossPoint for this book yet. A bookmark you make on the device comes here after the device syncs this book (open it and use Sync in the reader menu, or Sync On Sleep with Wi-Fi on). KOReader's come too, with Also add them to CrossPoint.", color = c.faint)
    } else {
        CpCard {
            marks.forEachIndexed { i, b ->
                if (i > 0) RowDivider()
                val done = "${book.document}|${b.id}" in state.bookmarkDone
                Row(
                    Modifier.fillMaxWidth().bouncyClickable(RectangleShape, pressedScale = 0.99f) { open = b }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(width = 4.dp, height = 28.dp).background(if (done) c.divider else c.accent, CpShapes.pill))
                    HSpace(12)
                    Column(Modifier.weight(1f)) {
                        Text(bookmarkLabel(b), style = CpType.body, color = if (done) c.textMid else c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Quiet(bookmarkWhere(b) + if (done) " · highlighted" else "", color = c.faint, maxLines = 1)
                    }
                }
            }
        }
    }
    VSpace(16)
    BookFileCard(vm, state, book)
    open?.let { b -> BookmarkSheet(vm, state, book, b) { open = null } }
}

/** The book's EPUB kept in Readync: whether there is one, and adding, copying or removing it. */
@Composable
internal fun BookFileCard(vm: AppViewModel, state: Snapshot, book: Book) {
    val c = Cp.colors
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { busy = true; msg = null; vm.attachBookFile(book.document, uri) { m -> busy = false; msg = m } }
    }
    val found = KoPlacement.bookFor(state, book.document)
    val kept = state.koBooks.containsKey(io.github.prithirajn.readync.source.BookFiles.key(book.document))
    val onKo = state.koSsh != null && state.koSshBooks.values.any { it.substringAfterLast('|') == book.document }
    CpCard {
        Column(Modifier.padding(16.dp)) {
            Text("Book file", style = CpType.label, color = c.text)
            VSpace(4)
            Quiet(
                when {
                    found == null -> "Readync doesn't have this book's EPUB. With it, bookmarks show their page, and highlights go on their exact words on your e-reader and in KOReader."
                    found.path.startsWith(KoPlacement.COPY) && found.sameFile -> "Using the copy kept for quotes. It's the very file on your e-reader, so places are exact."
                    found.path.startsWith(KoPlacement.COPY) -> "Using the copy kept for quotes. It's another edition than your e-reader's, so places are found by their words. Add the very file for exact places."
                    found.sameFile -> "Readync has the very file that's on your e-reader, so places are exact."
                    else -> "Readync has a copy of this book that isn't the very file on your e-reader, so places are found by their words."
                },
                color = c.faint,
            )
            VSpace(10)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(if (busy) "Adding…" else if (kept) "Replace the file" else if (found != null) "Add the very file" else "Add the book file", {
                    if (!busy) runCatching { pick.launch(arrayOf("application/epub+zip", "application/octet-stream", "*/*")) }
                })
                if (onKo && found?.sameFile != true) PillButton("Copy from KOReader", {
                    busy = true; msg = null; vm.copyBooksFromKoSsh(book.document) { m -> busy = false; msg = m }
                })
                if (kept) PillButton("Remove", { vm.removeBookFile(book.document) }, danger = true)
            }
            msg?.let { VSpace(8); Quiet(it, color = c.faint) }
        }
    }
}

/**
 * One bookmark: its page (from the book's file), to turn into a highlight by tapping its first and
 * last word, or the whole page; without the file, a photo of the device's screen or typed words.
 * The highlight goes to CrossPoint (on its exact words when the file's here), and on to KOReader
 * and your trackers.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BookmarkSheet(vm: AppViewModel, state: Snapshot, book: Book, b: CrossPointClient.Bookmark, onClose: () -> Unit) {
    val c = Cp.colors
    val context = LocalContext.current
    var page by remember { mutableStateOf<SyncEngine.BookmarkText?>(null) }
    var loading by remember { mutableStateOf(true) }
    var hasFile by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(b.id, reload, state.koBooks.size) {
        loading = true
        vm.bookmarkPage(book.document, b) { t, f -> page = t; hasFile = f; loading = false }
    }
    // The page's words, with where each starts and ends in the page text.
    val words = remember(page) { page?.let { p -> Regex("\\S+").findAll(p.text).map { it.range.first to it.range.last + 1 }.toList() } ?: emptyList() }
    var from by remember(page) { mutableStateOf<Int?>(null) }
    var to by remember(page) { mutableStateOf<Int?>(null) }
    var note by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    val picked = remember { mutableStateListOf<Int>() }
    var reading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var photo by remember { mutableStateOf<Pair<File, Uri>?>(null) }
    var pending by remember { mutableStateOf<Uri?>(null) }
    val done = "${book.document}|${b.id}" in state.bookmarkDone
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val p = photo ?: return@rememberLauncherForActivityResult
        if (ok) pending = p.second else p.first.delete()
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { busy = true; vm.attachBookFile(book.document, uri) { m -> busy = false; error = m; reload++ } }
    }
    LaunchedEffect(pending) {
        val uri = pending ?: return@LaunchedEffect
        reading = true; error = null
        val r = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PageScanner.scan(context, uri) } }
        reading = false; pending = null
        photo?.first?.let { runCatching { it.delete() } }; photo = null
        r.onFailure { error = it.message ?: "Couldn't read that picture." }
        r.onSuccess { sc ->
            picked.clear()
            lines = sc.lines.map { it.text.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) error = "No text found on that picture."
        }
    }
    val selection: Pair<Int, Int>? = run {
        val a = from ?: return@run null
        val z = to ?: a
        val lo = minOf(a, z); val hi = maxOf(a, z)
        if (lo in words.indices && hi in words.indices) words[lo].first to words[hi].second else null
    }
    val scannedText = picked.sorted().joinToString(" ") { lines[it] }.replace(Regex("(\\w)- (\\w)"), "$1$2")
    fun save(text: String, place: EpubText.Place?) {
        busy = true; error = null
        vm.highlightFromBookmark(book.document, b, text, place, note) { e -> busy = false; if (e == null) onClose() else error = e }
    }

    FlowSheet(bookmarkLabel(b), onClose) {
        Quiet(listOf(book.displayTitle, bookmarkWhere(b)).filter { it.isNotBlank() }.joinToString(" · "), color = c.faint)
        VSpace(14)
        val p = page
        when {
            loading -> Quiet("Finding the page…")
            p != null && words.isNotEmpty() -> {
                Quiet(if (from == null) "Tap the first word of the passage, then the last." else if (to == null) "Now tap the last word." else "Tap another word to start again.")
                VSpace(8)
                CpCard {
                    FlowRow(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        words.forEachIndexed { i, (s, e) ->
                            val lo = from?.let { a -> minOf(a, to ?: a) }
                            val hi = from?.let { a -> maxOf(a, to ?: a) }
                            val on = lo != null && hi != null && i in lo..hi
                            Text(
                                p.text.substring(s, e),
                                style = CpType.body,
                                color = if (on) c.text else c.textMid,
                                modifier = Modifier
                                    .background(if (on) c.accentTint else c.surface.copy(alpha = 0f), CpShapes.pill)
                                    .bouncyClickable(CpShapes.pill, pressedScale = 0.94f) {
                                        when {
                                            from == null || to != null -> { from = i; to = null }
                                            else -> to = i
                                        }
                                    }
                                    .padding(horizontal = 3.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                VSpace(12)
                CpTextField(note, { note = it }, "Note (optional)", placeholder = "Your thought on it")
                VSpace(12)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigButton(if (busy) "Adding…" else "Highlight", {
                        val (s, e) = selection ?: return@BigButton
                        save(p.text.substring(s, e), EpubText.Place(p.spine, p.start + s, p.start + e))
                    }, Modifier.weight(1f), busy = busy, enabled = selection != null && !busy)
                    BigButton("Whole page", {
                        val (s, e) = words.first().first to words.last().second
                        save(p.text.substring(s, e), EpubText.Place(p.spine, p.start + s, p.start + e))
                    }, Modifier.weight(1f), filled = false, enabled = !busy)
                }
            }
            else -> {
                Quiet(
                    if (hasFile) "Readync has this book's file but couldn't find this bookmark's page in it (it may be another edition " +
                        "than the one on your device). Add the very file your device has, or photograph your device's screen, or type the passage."
                    else "Readync doesn't have this book's file, so it can't show the page. Add the EPUB" +
                        (if (state.koSsh != null) " or copy it from KOReader" else "") +
                        ", or photograph your device's screen, or type the passage.",
                )
                b.summary?.takeIf { it.isNotBlank() }?.let { VSpace(8); Text("“$it…”", style = CpType.body, color = c.textMid) }
                VSpace(10)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(if (hasFile) "Add the very file" else "Add the book file", { runCatching { pickFile.launch(arrayOf("application/epub+zip", "application/octet-stream", "*/*")) } })
                    if (state.koSsh != null) PillButton("Copy from KOReader", {
                        busy = true; vm.copyBooksFromKoSsh(book.document) { m -> busy = false; error = m; reload++ }
                    })
                }
            }
        }
        // Another way in: a photo of the device's screen, or the words typed.
        VSpace(18)
        RowDivider()
        VSpace(12)
        Text(if (page != null) "Or from a photo or typed" else "From a photo or typed", style = CpType.label, color = c.text)
        VSpace(8)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(if (reading) "Reading…" else "Photograph the screen", {
                val ph = PageScanner.newPhoto(context)
                photo = ph
                try { camera.launch(ph.second) } catch (e: ActivityNotFoundException) { error = "No camera app found. Type the passage instead." }
            })
            PillButton(if (typing) "Hide" else "Type it", { typing = !typing })
        }
        if (lines.isNotEmpty()) {
            VSpace(10)
            Quiet("Tap the lines to include:")
            VSpace(6)
            CpCard {
                lines.forEachIndexed { i, line ->
                    if (i > 0) RowDivider()
                    val on = i in picked
                    Row(
                        Modifier.fillMaxWidth()
                            .bouncyClickable(RectangleShape, pressedScale = 0.99f) { if (on) picked.remove(i) else picked.add(i) }
                            .background(if (on) c.accentTint else c.surface.copy(alpha = 0f))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) { Text(line, style = CpType.body, color = if (on) c.text else c.textMid) }
                }
            }
            VSpace(10)
            BigButton(if (busy) "Adding…" else "Highlight these lines", { save(scannedText, null) }, busy = busy, enabled = picked.isNotEmpty() && !busy)
        }
        if (typing) {
            VSpace(10)
            CpTextField(typed, { typed = it }, "The passage", placeholder = "Type or paste it")
            VSpace(10)
            BigButton(if (busy) "Adding…" else "Highlight it", { save(typed, null) }, busy = busy, enabled = typed.trim().length >= 2 && !busy)
        }
        error?.let { VSpace(10); Quiet(it, color = c.errorText) }
        VSpace(18)
        RowDivider()
        VSpace(12)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(if (done) "Not done" else "Mark done", { vm.tickBookmark(book.document, b.id, !done) })
            PillButton("Remove bookmark", { vm.deleteBookmark(book.document, b.id); onClose() }, danger = true)
        }
        VSpace(6)
        Quiet("Removing it removes it on your CrossPoint device too, and in KOReader through its sync.", color = c.faint)
    }
}

/** Every book's bookmarks, waiting ones first: a list to turn into highlights. */
@Composable
internal fun AllBookmarksSheet(vm: AppViewModel, state: Snapshot, onClose: () -> Unit) {
    val c = Cp.colors
    val rows = allBookmarks(state)
        .sortedWith(compareBy<Pair<Book, CrossPointClient.Bookmark>> { bookmarkDone(state, it.first.document, it.second) }.thenByDescending { it.second.updatedAt })
    var open by remember { mutableStateOf<Pair<Book, CrossPointClient.Bookmark>?>(null) }
    var addMsg by remember { mutableStateOf<String?>(null) }
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { addMsg = "Adding…"; vm.addBookFiles(uris) { addMsg = it } }
    }
    FlowSheet("Bookmarks", onClose) {
        Quiet("Bookmarks from your CrossPoint device (and KOReader's, once they're added to CrossPoint). Open one to see its page and turn it into a highlight.", color = c.faint)
        VSpace(12)
        if (rows.isEmpty()) {
            Quiet("None yet. They come in with each sync.", color = c.faint)
        } else {
            CpCard {
                rows.forEachIndexed { i, (bk, b) ->
                    if (i > 0) RowDivider()
                    val done = bookmarkDone(state, bk.document, b)
                    Row(
                        Modifier.fillMaxWidth().bouncyClickable(RectangleShape, pressedScale = 0.99f) { open = bk to b }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(width = 4.dp, height = 28.dp).background(if (done) c.divider else c.accent, CpShapes.pill))
                        HSpace(12)
                        Column(Modifier.weight(1f)) {
                            Text(bookmarkLabel(b), style = CpType.body, color = if (done) c.textMid else c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Quiet(bk.displayTitle + " · " + bookmarkWhere(b) + if (done) " · highlighted" else "", color = c.faint, maxLines = 1)
                        }
                    }
                }
            }
        }
        VSpace(16)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Remove once highlighted", style = CpType.body, color = c.text)
                Quiet("Else it stays, ticked off", color = c.faint)
            }
            CpToggle(state.bookmarkRemoveWhenDone, { vm.setBookmarkRemoveWhenDone(it) }, "Remove once highlighted")
        }
        VSpace(12)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Every new bookmark highlights its page", style = CpType.body, color = c.text)
                Quiet("For readers without touch: bookmark a page and it becomes a highlight by itself. Needs the book's file here.", color = c.faint)
            }
            CpToggle(state.bookmarkAutoHighlight, { vm.setBookmarkAutoHighlight(it) }, "Every new bookmark highlights its page")
        }
        VSpace(14)
        PillButton("Add book files", { runCatching { pickMany.launch(arrayOf("application/epub+zip", "application/octet-stream")) } })
        VSpace(6)
        Quiet(addMsg ?: "Pick EPUBs from your phone; each goes to its book by its file, or by title and author.", color = c.faint)
    }
    open?.let { (bk, b) -> BookmarkSheet(vm, state, bk, b) { open = null } }
}
