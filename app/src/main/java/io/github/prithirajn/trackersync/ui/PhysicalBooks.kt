package io.github.prithirajn.trackersync.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.prithirajn.trackersync.R
import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.sync.PageReader
import io.github.prithirajn.trackersync.sync.Shelves
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import io.github.prithirajn.trackersync.ui.theme.CpType
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    val c = Cp.colors
    ModalBottomSheet(
        dragHandle = { SheetGrip() },
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
        ) {
            Text(title, style = CpType.section, color = c.text)
            content()
        }
    }
}

/**
 * Adding a physical book: searched on your Hardcover and Seekquel (your shelves ranked first), then their
 * catalogs as you search, each with its cover and details; or typed in when it's on neither.
 */
/**
 * Another way to add from the Add sheet, for a part of the app outside the public code: its own
 * title and button, choices to pick between, and what adding does (a picked book, or one typed in).
 */
class AddMode(
    val title: String,
    val button: String,
    val choices: List<String>,
    val add: (vm: AppViewModel, picked: AppViewModel.AddCandidate?, title: String?, author: String?, pages: Int?, isbn: String?, choice: String, done: (String?, String?) -> Unit) -> Unit,
)

@Composable
fun AddBookSheet(vm: AppViewModel, initialQuery: String = "", onClose: () -> Unit, onAdded: (String) -> Unit, onLogManga: (() -> Unit)? = null, mode: AddMode? = null) {
    var format by remember { mutableStateOf(mode?.choices?.firstOrNull() ?: "") }
    val c = Cp.colors
    val context = LocalContext.current
    val sources = remember { vm.addSources() }
    var query by remember { mutableStateOf(initialQuery) }
    var searched by remember { mutableStateOf(initialQuery) }
    var scanned by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var photo by remember { mutableStateOf<Pair<File, Uri>?>(null) }
    var results by remember { mutableStateOf<List<AppViewModel.AddCandidate>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<AppViewModel.AddCandidate?>(null) }
    var typing by remember { mutableStateOf(sources.isEmpty()) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(searched, scanned) {
        val isbn = scanned
        results = null; error = null
        if (isbn != null) {
            // A scanned barcode: that exact edition, from Hardcover, Seekquel or Open Library.
            vm.isbnCandidates(isbn) { r, e -> results = r; error = e }
        } else if (sources.isNotEmpty() && searched.isNotEmpty()) {
            // Only once you search: loading every shelf up front made the sheet slow to open.
            vm.addCandidates(searched) { r, e -> results = r; error = e }
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val p = photo ?: return@rememberLauncherForActivityResult
        if (!ok) { p.first.delete(); return@rememberLauncherForActivityResult }
        scanning = true
        vm.scanIsbn(p.second, p.first) { isbn, err ->
            scanning = false
            if (isbn != null) { scanned = isbn; query = isbn } else error = err
        }
    }
    val tick = rememberTick()
    /** The old way, kept for phones where the live scanner can't run: a photo of the barcode. */
    fun photoScan() {
        val p = PageScanner.newPhoto(context)
        photo = p
        try { camera.launch(p.second) } catch (e: ActivityNotFoundException) { error = "No camera app found. Search by title instead." }
    }
    /**
     * The live scanner: a framing box over the camera that reads the ISBN as soon as it's in view.
     * A shop's own barcode is skipped once, with the scanner opened again for the ISBN one.
     */
    fun liveScan(retried: Boolean = false) {
        error = null
        PageScanner.scanLive(context) { r ->
            when (r) {
                is PageScanner.LiveScan.Isbn -> { tick(); scanned = r.isbn; query = r.isbn }
                is PageScanner.LiveScan.NotIsbn ->
                    if (!retried) { vm.notify("That's not the ISBN barcode", "Try the one with 978 or 979 under it."); liveScan(true) }
                    else error = "That barcode isn't a book's ISBN. Look for the one starting 978 or 979, usually on the back cover."
                PageScanner.LiveScan.Cancelled -> Unit
                is PageScanner.LiveScan.Unavailable -> photoScan()
            }
        }
    }

    Sheet(mode?.title ?: "Add a book", onClose) {
        val pick = picked
        if (pick == null && onLogManga != null) {
            // Manga and comics read elsewhere: logged by chapter or issue, counted as volumes.
            VSpace(10)
            PillButton("Reading manga or a comic? Log a chapter or issue", onLogManga)
        }
        if (pick != null) {
            // Confirm: the cover and details as they'll be used, and the page count to count pages by.
            var pages by remember(pick) { mutableStateOf(pick.book.pages?.toString() ?: "") }
            VSpace(12)
            Row(verticalAlignment = Alignment.CenterVertically) {
                BookCover(pick.book.coverUrl, pick.book.title, 64.dp, 96.dp)
                HSpace(14)
                Column(Modifier.weight(1f)) {
                    Text(pick.book.title, style = CpType.bodyStrong, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    pick.book.author?.let { Quiet(it, maxLines = 1) }
                    Quiet("From ${pick.tracker?.name ?: "Open Library"}" + (pick.shelf?.let { " · $it" } ?: ""), color = c.faint)
                }
            }
            VSpace(16)
            CpTextField(pages, { v -> pages = v.filter { it.isDigit() }.take(5) }, "Pages in your copy", placeholder = "e.g. 352", keyboard = KeyboardType.Number)
            Quiet("Progress is counted against this. Without it you can still update by percentage.", Modifier.padding(top = 6.dp), color = c.faint)
            VSpace(16)
            if (mode != null && mode.choices.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (f in mode.choices) PillButton(f, { format = f }, outlined = format != f)
                }
                VSpace(12)
            }
            /** Adds it the way this sheet was opened for. */
            fun add(done: (String?, String?) -> Unit) =
                if (mode != null) mode.add(vm, pick, null, null, pages.toIntOrNull(), scanned, format, done)
                else vm.addPhysicalBook(pick, pages.toIntOrNull(), isbn = scanned, done = done)
            BigButton(mode?.button ?: "Add to my library", {
                busy = true
                add { doc, err -> busy = false; if (err == null && doc != null) onAdded(doc) }
            }, busy = busy)
            VSpace(8)
            if (scanned != null) {
                BigButton("Add & scan next barcode", {
                    busy = true
                    add { doc, err ->
                        busy = false
                        if (err == null && doc != null) {
                            picked = null
                            scanned = null
                            query = ""
                            searched = ""
                            liveScan()
                        }
                    }
                }, busy = busy, filled = false)
                VSpace(8)
            }
            BigButton("Back", { picked = null }, filled = false)
            return@Sheet
        }

        // The quickest way: the barcode on the back cover finds the exact edition.
        VSpace(8)
        BigButton(if (scanning) "Reading the barcode…" else "Scan the barcode", { liveScan() }, busy = scanning)
        if (scanned == null) {
            Quiet("Point at the barcode on the back cover; it's read the moment it's in the box.", Modifier.padding(top = 6.dp), color = c.faint)
        }
        scanned?.let { Quiet("ISBN $it", Modifier.padding(top = 6.dp), color = c.faint) }
        VSpace(14)
        if (sources.isNotEmpty() || scanned != null) {
            if (sources.isNotEmpty()) {
                Quiet("Or search your ${sources.joinToString(" and ") { it.name }} by title or author.")
                VSpace(12)
                Row(verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.weight(1f)) { CpTextField(query, { query = it }, "Search", placeholder = "Title or author") }
                    HSpace(8)
                    PillButton("Search", { scanned = null; searched = query.trim() })
                }
            }
            VSpace(16)
            val list = results
            val idle = scanned == null && searched.isEmpty()
            when {
                idle && error == null -> {}
                error != null -> Text(error ?: "", style = CpType.small, color = c.errorText)
                list == null -> Centered { CircularProgressIndicator(Modifier.size(24.dp), color = c.faint) }
                list.isEmpty() -> Quiet("Nothing found. Try just the title.")
                else -> CpCard {
                    list.forEachIndexed { i, cand ->
                        if (i > 0) RowDivider()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .bouncyClickable(RectangleShape, pressedScale = 0.985f) { picked = cand }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BookCover(cand.book.coverUrl, cand.book.title, 40.dp, 60.dp)
                            HSpace(12)
                            Column(Modifier.weight(1f)) {
                                Text(cand.book.title, style = CpType.bodyStrong, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Quiet(listOfNotNull(cand.book.author, cand.book.year?.toString(), cand.book.pages?.let { "$it pages" }).joinToString(" · "), maxLines = 1)
                                VSpace(4)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Chip(cand.tracker?.name ?: "Open Library")
                                    cand.shelf?.let { Chip(it, color = c.accentText, background = c.accentTint) }
                                    cand.fraction?.takeIf { it > 0 }?.let { Chip("${Math.round(it * 100)}%") }
                                }
                            }
                        }
                    }
                }
            }
            if (!idle || error != null) VSpace(16)
        } else {
            Quiet("Link Hardcover or Seekquel under Services to add books with their covers and details. Or type it in:")
            VSpace(12)
        }

        if (!typing) {
            BigButton("Not listed? Type it in", { typing = true }, filled = false)
        } else {
            var title by remember { mutableStateOf(query) }
            var author by remember { mutableStateOf("") }
            var pages by remember { mutableStateOf("") }
            CpTextField(title, { title = it }, "Title")
            VSpace(10)
            CpTextField(author, { author = it }, "Author")
            VSpace(10)
            CpTextField(pages, { v -> pages = v.filter { it.isDigit() }.take(5) }, "Pages", placeholder = "e.g. 352", keyboard = KeyboardType.Number)
            VSpace(14)
            BigButton(mode?.button ?: "Add to my library", {
                busy = true
                if (mode != null) mode.add(vm, null, title, author, pages.toIntOrNull(), null, format) { doc, err -> busy = false; if (err == null && doc != null) onAdded(doc) }
                else vm.addTypedBook(title, author, pages.toIntOrNull()) { doc, err -> busy = false; if (err == null && doc != null) onAdded(doc) }
            }, busy = busy, enabled = title.isNotBlank())
        }
    }
}

/**
 * On a physical book's page: where you are, a photo of the page to update it, typing the
 * page instead, and the status.
 */
@Composable
fun ManualProgressCard(vm: AppViewModel, book: Book, onRemoved: () -> Unit, autoSnap: Boolean = false) {
    val c = Cp.colors
    val context = LocalContext.current
    var photo by remember { mutableStateOf<Pair<File, Uri>?>(null) }
    var reading by remember { mutableStateOf(false) }
    var entering by remember { mutableStateOf<PageReader.Guess?>(null) }
    var enterOpen by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val p = photo ?: return@rememberLauncherForActivityResult
        if (!ok) {
            p.first.delete()
            return@rememberLauncherForActivityResult
        }
        reading = true
        vm.readPage(p.second, p.first, book.document) { guess, err ->
            reading = false
            entering = guess
            note = when {
                err != null -> "Couldn't read the photo ($err). Type the page instead."
                guess == null -> "No page number found on that photo. Type it instead."
                guess.confident -> null
                else -> "Is this the page? Check the number."
            }
            enterOpen = true
        }
    }

    // From the Snap page shortcut: straight to the camera.
    LaunchedEffect(autoSnap) {
        if (autoSnap && book.pageCount != null && !reading) {
            val p = PageScanner.newPhoto(context)
            photo = p
            try { camera.launch(p.second) } catch (e: ActivityNotFoundException) { vm.notify("No camera app found", "Type the page instead.", error = true) }
        }
    }

    CpCard {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    book.currentPage?.let { "Page $it" } ?: "${book.percent}%",
                    style = CpType.pageTitle,
                    color = c.text,
                )
                HSpace(8)
                Quiet(
                    listOfNotNull(book.pageCount?.let { "of $it" }, if (book.currentPage != null) "${book.percent}%" else null).joinToString(" · "),
                    Modifier.padding(bottom = 6.dp),
                )
            }
            Quiet("A physical book. Updates go to your trackers.", color = c.faint)
            VSpace(14)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    BigButton(if (reading) "Reading the page…" else "Snap the page", {
                        val p = PageScanner.newPhoto(context)
                        photo = p
                        try {
                            camera.launch(p.second)
                        } catch (e: ActivityNotFoundException) {
                            vm.notify("No camera app found", "Type the page instead.", error = true)
                        }
                    }, busy = reading, enabled = book.pageCount != null)
                }
                PillButton(if (book.pageCount != null) "Type page" else "Set %", { entering = null; note = null; enterOpen = true })
            }
            if (book.pageCount == null) {
                Quiet("Add the page count (Edit details) to update by page or photo.", Modifier.padding(top = 8.dp), color = c.faint)
            }
            VSpace(14)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in Shelves.tabs) {
                    val on = Shelves.tabOf(book) == s
                    Box(Modifier.bouncyClickable(CpShapes.pill, pressedScale = 0.92f) { if (!on) vm.setManualStatus(book.document, s) }) {
                        Chip(s.label, color = if (on) c.accentText else c.quiet, background = if (on) c.accentTint else c.divider)
                    }
                }
            }
            VSpace(10)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Edit details", { editing = true }, outlined = false)
                PillButton("Remove", { removing = true }, danger = true)
            }
        }
    }

    if (enterOpen) EnterPageSheet(vm, book, entering, note) { enterOpen = false }
    if (editing) EditManualSheet(vm, book) { editing = false }
    if (removing) {
        ConfirmSheet(
            title = "Remove “${book.displayTitle}”?",
            body = "It's removed from ${io.github.prithirajn.trackersync.Edition.name}. Nothing changes on your trackers.",
            action = "Remove",
            onConfirm = { vm.removeManual(book.document) { if (it == null) onRemoved() } },
            onClose = { removing = false },
        )
    }
}

/** Typing the page (or a percentage), or confirming what a photo read: other numbers it saw are one tap away. */
@Composable
private fun EnterPageSheet(vm: AppViewModel, book: Book, guess: PageReader.Guess?, note: String?, onClose: () -> Unit) {
    val c = Cp.colors
    val byPage = book.pageCount != null
    var value by remember { mutableStateOf((guess?.page ?: if (byPage) book.currentPage else book.percent)?.toString() ?: "") }
    var busy by remember { mutableStateOf(false) }
    Sheet(if (byPage) "Which page are you on?" else "How far are you?", onClose) {
        note?.let { Quiet(it, Modifier.padding(top = 4.dp), color = if (guess == null) c.errorText else c.quiet) }
        VSpace(16)
        CpTextField(
            value,
            { v -> value = v.filter { it.isDigit() }.take(5) },
            if (byPage) "Page (of ${book.pageCount})" else "Percent",
            keyboard = KeyboardType.Number,
        )
        val n = value.toIntOrNull()
        if (byPage && n != null && book.pageCount != null && n in 0..book.pageCount) {
            Quiet("${Math.round(n * 100.0 / book.pageCount)}% of the book", Modifier.padding(top = 6.dp))
        }
        guess?.others?.takeIf { it.isNotEmpty() }?.let { others ->
            VSpace(10)
            Quiet("Other numbers on the photo:")
            VSpace(6)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (o in others) {
                    Box(Modifier.bouncyClickable(CpShapes.pill, pressedScale = 0.92f) { value = o.toString() }) { Chip(o.toString()) }
                }
            }
        }
        VSpace(18)
        BigButton("Save", {
            val v = value.toIntOrNull() ?: return@BigButton
            busy = true
            val done: (String?) -> Unit = { err -> busy = false; if (err == null) onClose() }
            if (byPage) vm.setPage(book.document, v, done) else vm.setPercent(book.document, v, done)
        }, busy = busy, enabled = value.toIntOrNull() != null)
    }
}

@Composable
private fun EditManualSheet(vm: AppViewModel, book: Book, onClose: () -> Unit) {
    var title by remember { mutableStateOf(book.title ?: "") }
    var author by remember { mutableStateOf(book.author ?: "") }
    var pages by remember { mutableStateOf(book.pageCount?.toString() ?: "") }
    var busy by remember { mutableStateOf(false) }
    Sheet("Edit details", onClose) {
        VSpace(12)
        CpTextField(title, { title = it }, "Title")
        VSpace(10)
        CpTextField(author, { author = it }, "Author")
        VSpace(10)
        CpTextField(pages, { v -> pages = v.filter { it.isDigit() }.take(5) }, "Pages in your copy", keyboard = KeyboardType.Number)
        VSpace(16)
        BigButton("Save", {
            busy = true
            vm.editManual(book.document, title, author, pages.toIntOrNull()) { err -> busy = false; if (err == null) onClose() }
        }, busy = busy, enabled = title.isNotBlank())
    }
}

/**
 * Setting a tracked book's progress by hand: page (when the page count is known) or percentage,
 * and status. It goes out now and wins over CrossPoint, KOSync and Seekquel until you read
 * further there.
 */
@Composable
fun UpdateProgressSheet(vm: AppViewModel, book: Book, onClose: () -> Unit) {
    val c = Cp.colors
    val pages = book.pageCount
    var byPage by remember { mutableStateOf(pages != null) }
    var value by remember {
        mutableStateOf((if (pages != null) book.currentPage ?: Math.round(book.percentage * pages).toInt() else book.percent).toString())
    }
    var status by remember { mutableStateOf<ReadStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // A photo of the page reads the page number, as for physical books.
    val context = LocalContext.current
    var photo by remember { mutableStateOf<Pair<File, Uri>?>(null) }
    var reading by remember { mutableStateOf(false) }
    var others by remember { mutableStateOf<List<Int>>(emptyList()) }
    var photoNote by remember { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val p = photo ?: return@rememberLauncherForActivityResult
        if (!ok) { p.first.delete(); return@rememberLauncherForActivityResult }
        reading = true; photoNote = null
        vm.readPage(p.second, p.first, book.document) { guess, err ->
            reading = false
            if (guess != null) {
                byPage = true
                value = guess.page.toString()
                others = guess.others
                photoNote = if (guess.confident) "Read page ${guess.page} off the photo." else "Is this the page? Check the number."
            } else {
                photoNote = err?.let { "Couldn't read the photo ($it). Type the page instead." } ?: "No page number found on that photo. Type it instead."
            }
        }
    }
    val n = value.toIntOrNull()
    val pct = when {
        n == null -> null
        byPage && pages != null -> if (n in 0..pages) Math.round(n * 100.0 / pages).toInt() else null
        else -> n.takeIf { it in 0..100 }
    }
    Sheet("Update progress", onClose) {
        Quiet("Now ${book.percent}%" + (book.currentPage?.let { " · page $it" } ?: "") + " · ${book.status?.label ?: "Reading"}")
        VSpace(14)
        if (pages != null) {
            BigButton(if (reading) "Reading the page…" else "Snap the page", {
                val p = PageScanner.newPhoto(context)
                photo = p
                try { camera.launch(p.second) } catch (e: ActivityNotFoundException) { photoNote = "No camera app found. Type the page instead." }
            }, busy = reading, filled = false)
            photoNote?.let { Quiet(it, Modifier.padding(top = 6.dp)) }
            if (others.isNotEmpty()) {
                VSpace(6)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (o in others) Box(Modifier.bouncyClickable(CpShapes.pill, pressedScale = 0.92f) { byPage = true; value = o.toString() }) { Chip(o.toString()) }
                }
            }
            VSpace(14)
        } else {
            Quiet(
                if (book.origin == null) "No page count yet: set Print pages in the book's ⋯ menu to update by page or photo."
                else "No page count for this book, so update it by percentage.",
                color = c.faint,
            )
            VSpace(10)
        }
        if (pages != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, page) in listOf("Page" to true, "Percent" to false)) {
                    val on = byPage == page
                    Box(Modifier.bouncyClickable(CpShapes.pill, pressedScale = 0.92f) {
                        if (!on) {
                            val cur = value.toIntOrNull()
                            value = when {
                                cur == null -> ""
                                page -> Math.round(cur / 100.0 * pages).toInt().toString()
                                else -> Math.round(cur * 100.0 / pages).toInt().toString()
                            }
                            byPage = page
                        }
                    }) { Chip(label, color = if (on) c.accentText else c.quiet, background = if (on) c.accentTint else c.divider) }
                }
            }
            VSpace(10)
        }
        CpTextField(
            value,
            { v -> value = v.filter { it.isDigit() }.take(5); error = null },
            if (byPage && pages != null) "Page (of $pages)" else "Percent",
            keyboard = KeyboardType.Number,
        )
        pct?.let { p ->
            VSpace(8)
            ProgressBar(p / 100f)
            Quiet("$p% of the book", Modifier.padding(top = 6.dp))
        }
        VSpace(14)
        Quiet("Status")
        VSpace(6)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val auto = status == null
            Box(Modifier.bouncyClickable(CpShapes.pill, pressedScale = 0.92f) { status = null }) {
                Chip("Automatic", color = if (auto) c.accentText else c.quiet, background = if (auto) c.accentTint else c.divider)
            }
            for (s in Shelves.tabs) {
                val on = status == s
                Box(Modifier.bouncyClickable(CpShapes.pill, pressedScale = 0.92f) { status = if (on) null else s }) {
                    Chip(s.label, color = if (on) c.accentText else c.quiet, background = if (on) c.accentTint else c.divider)
                }
            }
        }
        Quiet(
            if (status == null) "Automatic: Finished from 98%, otherwise Reading (Paused and DNF stay)." else "Marked ${status!!.label}.",
            Modifier.padding(top = 6.dp), color = c.faint,
        )
        VSpace(12)
        Quiet(
            (if (book.origin == null) "It stays in this app and your trackers; CrossPoint isn't changed. " else "") +
            "This takes priority: it goes to your trackers now, and CrossPoint, KOSync and Seekquel don't change it " +
                "until you read further there. Hardcover, StoryGraph and Pagebound never move backwards, so a lower number won't lower them.",
            color = c.faint,
        )
        error?.let { VSpace(8); Text(it, style = CpType.small, color = c.errorText) }
        VSpace(16)
        BigButton("Save", {
            busy = true
            val v = value.toIntOrNull()
            vm.overrideProgress(
                book.document,
                percent = if (byPage && pages != null) null else v,
                page = if (byPage && pages != null) v else null,
                status = status,
            ) { err -> busy = false; if (err == null) onClose() else error = err }
        }, busy = busy, enabled = pct != null || status != null)
    }
}

/**
 * Adding the book to trackers that don't have it: its public details (looked up from Open
 * Library and Google Books, with what's here) shown first, a switch per tracker with what adding
 * means there, then each is checked once more and the book added and matched.
 */
@Composable
fun AddToTrackerSheet(vm: AppViewModel, book: Book, first: String?, onClose: () -> Unit) {
    val c = Cp.colors
    // Opened from one tracker's Find match: just that tracker.
    // Opened from that tracker's search, it's offered even if the book was set to Don't sync there.
    val targets = remember {
        if (first != null) listOfNotNull(io.github.prithirajn.trackersync.trackers.Trackers.byId(first)?.takeIf { it.addsBooks })
        else vm.addTargets(book.document)
    }
    val blocked = remember { first?.let { vm.addBlocked(it) } }
    val chosen = remember { androidx.compose.runtime.mutableStateListOf<String>().also { l -> l.addAll(targets.map { it.id }.filter { first == null || it == first }) } }
    var details by remember { mutableStateOf<io.github.prithirajn.trackersync.source.BookDetails?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<io.github.prithirajn.trackersync.sync.AddToTracker.Outcome>?>(null) }
    var kalpaLink by remember { mutableStateOf("") }
    var onSite by remember { mutableStateOf<io.github.prithirajn.trackersync.trackers.Tracker?>(null) }
    // The site's own page, filled in with what was sent there (for Pagebound, with the book's Goodreads page).
    onSite?.let { t -> (results?.firstOrNull { it.tracker.id == t.id }?.details ?: details)?.let { d -> AddOnSiteScreen(vm, book, t, d) { onSite = null; onClose() } } }
    LaunchedEffect(book.document) { vm.bookDetails(book.document) { d, e -> details = d; error = e } }

    Sheet(targets.singleOrNull()?.let { "Add to ${it.name}" } ?: "Add to your trackers", onClose) {
        Quiet(
            (targets.singleOrNull()?.let { "${it.name} doesn't have this book yet. " } ?: "For trackers that don't have this book yet. ") +
                "It's filled in from Seekquel and public sources (KalpaDB for Indian books, Royal Road and AO3 for web novels, Google Books, Open Library), and this app.",
        )
        VSpace(14)
        val d = details
        when {
            error != null -> Text(error ?: "", style = CpType.small, color = c.errorText)
            d == null -> Centered { CircularProgressIndicator(Modifier.size(24.dp), color = c.faint) }
            else -> CpCard {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    BookCover(d.coverUrl ?: book.coverUrl, d.title, 52.dp, 78.dp)
                    HSpace(12)
                    Column(Modifier.weight(1f)) {
                        Text(d.title, style = CpType.bodyStrong, color = c.text)
                        d.subtitle?.let { Quiet(it) }
                        Quiet(d.authors.joinToString(", ").ifEmpty { "Author unknown" })
                        VSpace(6)
                        for (line in d.summary()) Quiet(line, color = c.faint)
                        VSpace(6)
                        Text(d.sourceLine(), style = CpType.small, color = if (d.found.isEmpty()) c.warn else c.accentText)
                    }
                }
            }
        }
        // An Indian book's KalpaDB entry, or a web novel's own page, gives the best details.
        VSpace(12)
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) {
                CpTextField(kalpaLink, { kalpaLink = it }, "KalpaDB or story link (optional)", placeholder = "kalpadb.com/works/… or royalroad.com/fiction/…")
            }
            HSpace(8)
            PillButton("Use", {
                details = null; error = null
                vm.bookDetails(book.document, kalpaLink.trim()) { d, e -> details = d; error = e }
            })
        }
        if (blocked != null) {
            VSpace(12)
            Text(blocked, style = CpType.small, color = c.errorText)
        } else if (targets.isEmpty()) {
            VSpace(12)
            Quiet("Every tracker that can take a new book already has this one.", color = c.faint)
        } else {
            VSpace(14)
            CpCard {
                targets.forEachIndexed { i, t ->
                    if (i > 0) RowDivider()
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ServiceTile(t.name, 32.dp)
                            HSpace(10)
                            Text(t.name, style = CpType.bodyStrong, color = c.text, modifier = Modifier.weight(1f))
                            val refused = details?.let { vm.addRefusal(t.id, it) }
                            CpToggle(t.id in chosen && refused == null, { on -> if (on && refused == null) chosen.add(t.id) else chosen.remove(t.id) }, t.name)
                        }
                        val refused = details?.let { vm.addRefusal(t.id, it) }
                        if (refused != null) Text(refused, style = CpType.small, color = c.warn)
                        else t.addBookNote?.let { Quiet(it, color = c.faint) }
                        results?.firstOrNull { it.tracker.id == t.id }?.let { r ->
                            Text(r.text, style = CpType.small, color = if (r.error != null) c.errorText else c.good)
                            // Its form couldn't be sent from here: open its own page, filled in.
                            if (r.canFinishOnSite && details != null) {
                                VSpace(6)
                                PillButton("Finish on ${t.name} (Pre-filled)", { onSite = t }, outlined = false)
                            }
                        }
                    }
                }
            }
            VSpace(10)
            Quiet("Each is searched once more first; if the book turns up, it's matched instead of added again.", color = c.faint)
            VSpace(14)
            BigButton(
                if (results == null) "Add to ${chosen.size} tracker${if (chosen.size == 1) "" else "s"}" else "Done",
                {
                    if (results != null) { onClose(); return@BigButton }
                    val dd = details ?: return@BigButton
                    busy = true
                    vm.addToTrackers(book.document, targets.filter { it.id in chosen && vm.addRefusal(it.id, dd) == null }, dd) { out -> busy = false; results = out }
                },
                busy = busy, enabled = blocked == null && details != null && (results != null || chosen.isNotEmpty()),
            )
        }
    }
}

/**
 * A tracker's own add-a-book page in the app's browser, signed in as you and filled in from the
 * book's details (for sites whose form is built by their scripts, so the app can't send it
 * itself). You check it and press the site's own save button; when the site moves to the new
 * book's page, the app matches the book to it.
 */
@Composable
fun AddOnSiteScreen(vm: AppViewModel, book: Book, tracker: io.github.prithirajn.trackersync.trackers.Tracker, details: io.github.prithirajn.trackersync.source.BookDetails, onClose: () -> Unit) {
    val c = Cp.colors
    val page = tracker.addBookPage ?: return
    val idPattern = tracker.addedBookId
    val cookies = remember { vm.webCookies(tracker) }
    var cover by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(details.coverUrl) { vm.coverForUpload(details.coverUrl) { cover = it } }
    val script = remember(cover) {
        io.github.prithirajn.trackersync.util.fillPageScript(io.github.prithirajn.trackersync.util.fillBookRules(details), cover?.first, cover?.second ?: "image/jpeg")
    }
    var web by remember { mutableStateOf<android.webkit.WebView?>(null) }
    // The cover arrives after the page: fill again with it.
    LaunchedEffect(cover) { if (cover != null) web?.let { w -> if (w.url?.startsWith(page) == true) w.evaluateJavascript(script, null) } }
    var matched by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = FullScreen,
    ) {
        Column(Modifier.fillMaxSize().background(c.page).fullScreenSafe()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Add on ${tracker.name}", style = CpType.bodyStrong, color = c.text)
                    Quiet(
                        if (matched) "Added and matched." else "Filled in from the book's details. Check it, then press ${tracker.name}'s own save button.",
                        maxLines = 2,
                    )
                }
                PillButton(if (matched) "Done" else "Close", onClose)
            }
            if (loading) Box(Modifier.fillMaxWidth().height(2.dp).background(c.accent))
            androidx.compose.ui.viewinterop.AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    val cm = android.webkit.CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    for ((url, cookie) in cookies) cm.setCookie(url, cookie)
                    cm.flush()
                    android.webkit.WebView(ctx).apply {
                        web = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        fun check(url: String?) {
                            val id = url?.let { u -> idPattern?.find(u)?.groupValues?.get(1) } ?: return
                            if (matched) return
                            matched = true
                            vm.linkCreated(book.document, tracker, id, details)
                        }
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun onPageFinished(view: android.webkit.WebView, url: String?) {
                                loading = false
                                // Fill the add-a-book form only (not the page shown after saving).
                                if (url != null && url.startsWith(page)) view.evaluateJavascript(script, null)
                                check(url)
                            }
                            // Sites that change pages without reloading (Hardcover) are noticed here too.
                            override fun doUpdateVisitedHistory(view: android.webkit.WebView, url: String?, isReload: Boolean) {
                                check(url)
                            }
                        }
                        loadUrl(page)
                    }
                },
            )
        }
    }
}
