package io.github.prithirajn.trackersync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.prithirajn.trackersync.R
import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.source.BookDates
import io.github.prithirajn.trackersync.source.CoverCandidate
import io.github.prithirajn.trackersync.sync.BookEditor
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import io.github.prithirajn.trackersync.ui.theme.CpType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** What the ⋯ menu on a book opens. */
enum class BookAction { EDIT, COVER, DATES, PAGES, MERGE, SEPARATE, REMOVE, REREAD, WEBNOVEL, KOSYNC, DNF, PRICE, COPY }

/**
 * The companion app's ⋯ menu for a book: edit title & author, change cover, reading dates,
 * print pages, merge a duplicate (with how many look likely), separate merged copies, remove.
 */
@Composable
fun BookMenuButton(state: Snapshot, book: Book, webNovelLinked: Boolean = false, onPick: (BookAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val c = Cp.colors
    val likely = remember(state.books, book.document) {
        state.books.values.count { it.document != book.document && BookEditor.looksLikeSame(book, it) }
    }
    Box {
        IconCircleButton(R.drawable.lucide_ellipsis, "More", { open = true }, tint = c.textMid)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.surface) {
            val pick: (BookAction) -> Unit = { open = false; onPick(it) }
            val pagesTrailing: (@Composable () -> Unit)? = book.pageCount?.let { n ->
                @Composable { Text(n.toString(), style = CpType.body, color = c.quiet) }
            }
            val likelyTrailing: (@Composable () -> Unit)? = if (likely > 0) {
                @Composable { Chip("$likely likely", color = c.accentText, background = c.accentTint) }
            } else null
            if (book.status == io.github.prithirajn.trackersync.data.ReadStatus.FINISHED) {
                MenuItem(R.drawable.lucide_refresh_cw, "Read again") { pick(BookAction.REREAD) }
            }
            // Stopping a book you're not enjoying: its own action, not hidden in the progress sheet.
            if (book.status != io.github.prithirajn.trackersync.data.ReadStatus.DNF && book.status != io.github.prithirajn.trackersync.data.ReadStatus.FINISHED) {
                MenuItem(R.drawable.lucide_circle_x, "Did not finish") { pick(BookAction.DNF) }
            }
            MenuItem(R.drawable.lucide_pen_line, "Edit title & author") { pick(BookAction.EDIT) }
            MenuItem(R.drawable.lucide_image, "Change cover") { pick(BookAction.COVER) }
            MenuItem(R.drawable.lucide_calendar_days, "Reading dates") { pick(BookAction.DATES) }
            MenuItem(R.drawable.lucide_hash, "Print pages", trailing = pagesTrailing) { pick(BookAction.PAGES) }
            MenuItem(R.drawable.lucide_merge, "Merge a duplicate", trailing = likelyTrailing) { pick(BookAction.MERGE) }
            if (!webNovelLinked && !book.isMangaOrComic) MenuItem(R.drawable.lucide_globe, "Find it as a web novel") { pick(BookAction.WEBNOVEL) }
            // Its price, tracked on Catalogs › Deals (nothing shows on the book itself).
            val tracked = book.document in state.priceWatch
            MenuItem(R.drawable.lucide_bell, if (tracked) "Price tracked (on Deals)" else "Track its price") { pick(BookAction.PRICE) }
            // A copy of the book in the app's own storage (from your OPDS catalogs), for the words around quotes.
            if (!book.isMangaOrComic) {
                val copies by io.github.prithirajn.trackersync.source.BookCopies.copies.collectAsState()
                val busy by io.github.prithirajn.trackersync.source.BookCopies.busy.collectAsState()
                val has = book.document in copies
                MenuItem(
                    R.drawable.lucide_book_open,
                    when { book.document in busy -> "Getting a copy…"; has -> "Remove the app's copy"; else -> "Keep a copy for quotes" },
                ) { pick(BookAction.COPY) }
            }
            if (state.kosync != null) {
                val n = state.kosyncIds[book.document]?.size ?: 0
                val linkedTrailing: (@Composable () -> Unit)? = if (n > 0) { @Composable { Text(n.toString(), style = CpType.body, color = c.quiet) } } else null
                MenuItem(R.drawable.lucide_link, "KOSync documents", trailing = linkedTrailing) { pick(BookAction.KOSYNC) }
            }
            if (book.aliases.isNotEmpty()) {
                MenuItem(
                    R.drawable.lucide_split,
                    if (book.aliases.size == 1) "Separate merged copy" else "Separate ${book.aliases.size} merged copies",
                ) { pick(BookAction.SEPARATE) }
            }
            RowDivider()
            MenuItem(R.drawable.lucide_trash, "Remove from library", danger = true) { pick(BookAction.REMOVE) }
        }
    }
}

@Composable
private fun MenuItem(
    icon: Int,
    label: String,
    trailing: (@Composable () -> Unit)? = null,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val c = Cp.colors
    DropdownMenuItem(
        leadingIcon = { LucideIcon(icon, Modifier.size(18.dp), if (danger) c.errorText else c.quiet) },
        text = { Text(label, style = CpType.label, color = if (danger) c.errorText else c.text) },
        trailingIcon = trailing,
        onClick = onClick,
    )
}

/** Opens the sheet for [action]. [onRemoved] closes the book once it's gone. */
@Composable
fun BookActionSheet(vm: AppViewModel, state: Snapshot, book: Book, action: BookAction, onClose: () -> Unit, onRemoved: () -> Unit) {
    when (action) {
        BookAction.WEBNOVEL, BookAction.DNF -> {} // opened on the book itself
        BookAction.PRICE -> androidx.compose.runtime.LaunchedEffect(book.document) { vm.watchPrice(book.document); onClose() }
        BookAction.COPY -> androidx.compose.runtime.LaunchedEffect(book.document) { vm.toggleBookCopy(book.document); onClose() }
        BookAction.EDIT -> EditDetailsSheet(vm, book, onClose)
        BookAction.COVER -> CoverSheet(vm, book, onClose)
        BookAction.DATES -> DatesSheet(vm, book, onClose)
        BookAction.PAGES -> PagesSheet(vm, book, onClose)
        BookAction.KOSYNC -> KosyncLinkSheet(vm, state, book, onClose)
        BookAction.MERGE -> MergeSheet(vm, state, book, onClose)
        BookAction.SEPARATE -> ConfirmSheet(
            title = if (book.aliases.size == 1) "Separate the merged copy?" else "Separate ${book.aliases.size} merged copies?",
            body = "Each copy syncs as its own book again. What was already merged stays on this book; a copy shows up on its own once its reader syncs it.",
            action = "Separate",
            onConfirm = { for (alias in book.aliases) vm.separate(book.document, alias) {} },
            onClose = onClose,
        )
        BookAction.REREAD -> ConfirmSheet(
            title = "Read “${book.displayTitle}” again?",
            body = "Starts a new reading session at 0% and marks the book as Reading. Your previous review and rating stay safe.",
            action = "Read again",
            onConfirm = { vm.reReadBook(book.document) { if (it == null) onClose() } },
            onClose = onClose,
        )
        BookAction.REMOVE -> ConfirmSheet(
            title = "Remove “${book.displayTitle}” from your library?",
            body = (if (book.origin == null) "Its progress, highlights and reading stats are deleted from crosspoint-sync, and its tracker matches from this app. "
                else "It's removed from this app with its tracker matches. ") +
                "Nothing changes on the trackers. If you read further on your reader or Kindle, it comes back with your new progress; otherwise it stays removed.",
            action = "Remove",
            onConfirm = { vm.removeBook(book.document) { if (it == null) onRemoved() } },
            onClose = onClose,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MenuSheet(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
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

@Composable
private fun EditDetailsSheet(vm: AppViewModel, book: Book, onClose: () -> Unit) {
    var title by remember { mutableStateOf(book.title ?: "") }
    var author by remember { mutableStateOf(book.author ?: "") }
    var busy by remember { mutableStateOf(false) }
    val aiReady = remember { vm.aiReady() }
    var tidying by remember { mutableStateOf(false) }
    var tidyNote by remember { mutableStateOf<String?>(null) }
    MenuSheet("Edit title & author", onClose) {
        Quiet("Saved on crosspoint-sync, so every app sees it. The cover and description are looked up again, and trackers that couldn't find the book try again.")
        VSpace(16)
        CpTextField(title, { title = it }, "Title")
        VSpace(12)
        CpTextField(author, { author = it }, "Author")
        // With an on-device model: a file's messy title ("the_book_v2 [tags] - author") as a clean title and author.
        if (aiReady) {
            VSpace(10)
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillButton(if (tidying) "Tidying…" else "Tidy with AI", {
                    if (!tidying) {
                        tidying = true; tidyNote = null
                        vm.aiTidyTitle(title, author) { r, e ->
                            tidying = false
                            if (r != null) {
                                title = r.title
                                r.author?.let { author = it }
                                tidyNote = r.series?.let { "Series: $it" + (r.number?.let { n -> " #$n" } ?: "") + ". Check it before you save." } ?: "Check it before you save."
                            } else tidyNote = e
                        }
                    }
                })
            }
            tidyNote?.let { VSpace(4); Quiet(it, color = Cp.colors.faint) }
        }
        VSpace(20)
        BigButton("Save", {
            busy = true
            vm.editDetails(book.document, title, author) { err -> busy = false; if (err == null) onClose() }
        }, busy = busy, enabled = title.isNotBlank())
    }
}

@Composable
private fun CoverSheet(vm: AppViewModel, book: Book, onClose: () -> Unit) {
    val c = Cp.colors
    var query by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<CoverCandidate>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(searched) {
        items = null; error = null
        vm.coverCandidates(book.document, searched) { r, e -> items = r; error = e }
    }
    fun choose(url: String?) {
        busy = true
        vm.setCover(book.document, url) { err -> busy = false; if (err == null) onClose() }
    }
    MenuSheet("Choose a cover", onClose) {
        VSpace(12)
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) { CpTextField(query, { query = it }, "Search", placeholder = book.title ?: "A different title") }
            HSpace(8)
            PillButton("Search", { searched = query.trim().ifEmpty { null } })
        }
        VSpace(16)
        val list = items
        when {
            error != null -> Text(error ?: "", style = CpType.small, color = c.errorText)
            list == null -> Centered { CircularProgressIndicator(Modifier.size(24.dp), color = c.faint) }
            list.isEmpty() -> Quiet("No covers found. Try another search, or paste an image link.")
            else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (row in list.chunked(3)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (cand in row) {
                            Column(
                                Modifier
                                    .width(92.dp)
                                    .bouncyClickable(CpShapes.field, enabled = !busy, pressedScale = 0.95f) { choose(cand.url) }
                            ) {
                                BookCover(cand.url, cand.title ?: book.displayTitle, 92.dp, 138.dp)
                                cand.source?.let { Text(it, style = CpType.chip, color = c.quiet, maxLines = 1, modifier = Modifier.padding(top = 4.dp)) }
                            }
                        }
                    }
                }
            }
        }
        VSpace(20)
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) { CpTextField(link, { link = it }, "Or paste an image link", placeholder = "https://…", keyboard = KeyboardType.Uri) }
            HSpace(8)
            PillButton("Use", { if (link.trim().startsWith("http")) choose(link.trim()) })
        }
        VSpace(16)
        BigButton("Use automatic cover", { choose(null) }, filled = false, busy = busy)
    }
}

/** Unix seconds (pinned to local noon by the server) → that local day. */
private fun dayOf(unix: Long?): LocalDate? = unix?.let { Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).toLocalDate() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatesSheet(vm: AppViewModel, book: Book, onClose: () -> Unit) {
    val c = Cp.colors
    var loaded by remember { mutableStateOf<BookDates?>(null) }
    var loading by remember { mutableStateOf(true) }
    var start by remember { mutableStateOf<LocalDate?>(null) }
    var finish by remember { mutableStateOf<LocalDate?>(null) }
    var picking by remember { mutableStateOf<String?>(null) } // "start" | "finish"
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(book.document) {
        vm.loadDates(book.document) { d, e ->
            loading = false; loaded = d; error = e
            start = dayOf(d?.started); finish = dayOf(d?.finished)
        }
    }
    MenuSheet("Reading dates", onClose) {
        Quiet("Dates you set here win over your reader's. Setting a finish date marks the book finished, here and on your trackers.")
        VSpace(16)
        if (loading) {
            Centered { CircularProgressIndicator(Modifier.size(24.dp), color = c.faint) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DateField("Started", start, Modifier.weight(1f)) { picking = "start" }
                DateField("Finished", finish, Modifier.weight(1f)) { picking = "finish" }
            }
            error?.let { VSpace(10); Text(it, style = CpType.small, color = c.errorText) }
            VSpace(20)
            BigButton("Save", {
                val s0 = dayOf(loaded?.started)
                val f0 = dayOf(loaded?.finished)
                if (start != null && finish != null && start!! > finish!!) {
                    error = "The start date is after the finish date."
                    return@BigButton
                }
                // Only what changed, so untouched dates keep following the reader.
                val changes = buildMap<String, String?> {
                    if (start != s0) put("start_date", start?.toString())
                    if (finish != f0) put("finished_date", finish?.toString())
                }
                if (changes.isEmpty()) { onClose(); return@BigButton }
                busy = true
                vm.setDates(book.document, changes) { err -> busy = false; if (err == null) onClose() else error = err }
            }, busy = busy)
            if (loaded?.startManual == true || loaded?.finishManual == true) {
                VSpace(8)
                BigButton("Use the reader's dates", {
                    busy = true
                    vm.setDates(book.document, mapOf("start_date" to null, "finished_date" to null)) { err -> busy = false; if (err == null) onClose() }
                }, filled = false)
            }
        }
    }

    picking?.let { which ->
        val today = LocalDate.now()
        val initial = (if (which == "start") start else finish) ?: today
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                // No later than today, as the server allows.
                override fun isSelectableDate(utcTimeMillis: Long) =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    val day = picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    if (which == "start") start = day else finish = day
                    picking = null
                }) { Text("OK", color = c.accentText) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        if (which == "start") start = null else finish = null
                        picking = null
                    }) { Text("Clear", color = c.errorText) }
                    TextButton(onClick = { picking = null }) { Text("Cancel", color = c.quiet) }
                }
            },
        ) {
            DatePicker(state = picker)
        }
    }
}

@Composable
internal fun DateField(label: String, day: LocalDate?, modifier: Modifier, onClick: () -> Unit) {
    val c = Cp.colors
    Column(modifier) {
        Text(label, style = CpType.label, color = c.textMid)
        VSpace(6)
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .bouncyClickable(CpShapes.control, pressedScale = 0.97f, onClick = onClick)
                .background(c.surface)
                .border(1.dp, c.hairline, CpShapes.control)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LucideIcon(R.drawable.lucide_calendar_days, Modifier.size(16.dp), c.quiet)
            HSpace(8)
            Text(
                day?.let { java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy").format(it) } ?: "Not set",
                style = CpType.body,
                color = if (day == null) c.faint else c.text,
            )
        }
    }
}

@Composable
private fun PagesSheet(vm: AppViewModel, book: Book, onClose: () -> Unit) {
    var pages by remember { mutableStateOf(book.pageCount?.toString() ?: "") }
    var busy by remember { mutableStateOf(false) }
    MenuSheet("Print pages", onClose) {
        Quiet("The print edition's page count, used for pages-read stats on crosspoint-sync. Fix it when the lookup found the wrong edition.")
        VSpace(16)
        CpTextField(pages, { v -> pages = v.filter { it.isDigit() }.take(6) }, "Pages", placeholder = "e.g. 384", keyboard = KeyboardType.Number)
        VSpace(20)
        BigButton("Save", {
            busy = true
            vm.setPages(book.document, pages.toIntOrNull()) { err -> busy = false; if (err == null) onClose() }
        }, busy = busy, enabled = pages.toIntOrNull()?.let { it > 0 } == true)
        if (book.pageCount != null) {
            VSpace(8)
            BigButton("Look it up again", {
                busy = true
                vm.setPages(book.document, null) { err -> busy = false; if (err == null) onClose() }
            }, filled = false)
        }
    }
}

@Composable
private fun MergeSheet(vm: AppViewModel, state: Snapshot, book: Book, onClose: () -> Unit) {
    val c = Cp.colors
    var query by remember { mutableStateOf("") }
    var stats by remember { mutableStateOf(true) }
    var confirm by remember { mutableStateOf<Book?>(null) }
    val others = remember(state.books, book.document) {
        state.books.values
            .filter { it.origin == null && it.document != book.document }
            .sortedWith(compareByDescending<Book> { BookEditor.looksLikeSame(book, it) }.thenBy { it.displayTitle.lowercase() })
    }
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) others else others.filter { b ->
        listOfNotNull(b.title, b.author).any { it.lowercase().contains(q) }
    }
    MenuSheet("Merge a duplicate", onClose) {
        Quiet("When two readers identify the same book differently, it shows up twice. Pick the copy to fold into this one.")
        VSpace(12)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Combine reading stats", style = CpType.label, color = c.text, modifier = Modifier.weight(1f))
            CpToggle(stats, { stats = it }, "Combine reading stats")
        }
        VSpace(12)
        CpTextField(query, { query = it }, "Search", placeholder = "Title or author")
        VSpace(16)
        if (shown.isEmpty()) {
            Quiet("No book matches.")
        } else {
            CpCard {
                shown.forEachIndexed { i, b ->
                    if (i > 0) RowDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .bouncyClickable(RectangleShape, pressedScale = 0.985f) { confirm = b }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BookCover(b.coverUrl, b.displayTitle, 36.dp, 54.dp)
                        HSpace(12)
                        Column(Modifier.weight(1f)) {
                            Text(b.displayTitle, style = CpType.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Quiet(listOfNotNull(b.author, "${b.percent}%").joinToString(" · "), maxLines = 1)
                        }
                        if (BookEditor.looksLikeSame(book, b)) {
                            HSpace(8)
                            Chip("Likely duplicate", color = c.accentText, background = c.accentTint)
                        }
                    }
                }
            }
        }
    }
    confirm?.let { dupe ->
        ConfirmSheet(
            title = "Merge “${dupe.displayTitle}” into this book?",
            body = "Its progress and highlights" + (if (stats) " and reading stats" else "") +
                " move here, and future syncs of it land here too. Its tracker matches carry over where this book has none.",
            action = "Merge",
            onConfirm = { vm.merge(dupe.document, book.document, stats) { if (it == null) onClose() } },
            onClose = { confirm = null },
        )
    }
}


/**
 * KOSync documents for this book: the same book read on a device that fingerprints it differently
 * (another copy of the file, or another matching method), linked by hand so its position counts.
 * An ID is checked against KOSync first; a file name can be turned into the ID KOReader gives it
 * when it matches by name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KosyncLinkSheet(vm: AppViewModel, state: Snapshot, book: Book, onClose: () -> Unit) {
    val c = Cp.colors
    var id by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<io.github.prithirajn.trackersync.source.KosyncProgress?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val linked = state.kosyncIds[book.document] ?: emptyList()
    val fmt = remember { java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm") }
    // The server's own list, where it can give one: pick instead of pasting.
    var docs by remember { mutableStateOf<List<io.github.prithirajn.trackersync.source.KosyncProgress>?>(null) }
    var listed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.kosyncDocuments { docs = it; listed = true } }
    // IDs some book here already uses (its own, or linked): not offered for this one.
    val taken = remember(state.books, state.kosyncIds) { state.books.keys + state.books.values.flatMap { it.aliases } + state.kosyncIds.values.flatten() }
    fun check(target: String) {
        busy = true; error = null; found = null
        vm.kosyncPeek(target) { p, e -> busy = false; found = p; error = e }
    }
    MenuSheet("KOSync documents", onClose) {
        Quiet("KOSync knows a book only by a fingerprint of its file, so the same book read on another device can turn up under a different ID. Link that ID here and its position counts for this book too.")
        VSpace(12)
        Text("This book on CrossPoint", style = CpType.small, color = c.quiet)
        Text(book.document, style = CpType.body, color = c.textMid)
        if (linked.isNotEmpty()) {
            VSpace(12)
            Text("Linked", style = CpType.small, color = c.quiet)
            for (l in linked) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(l, style = CpType.body, color = c.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    PillButton("Unlink", { vm.unlinkKosync(book.document, l) }, danger = true)
                }
            }
        }
        val choices = docs?.filter { it.document != book.document && it.document !in linked && it.document !in taken }
        if (!listed) {
            VSpace(14)
            Quiet("Looking for your documents on the KOSync server…", color = c.faint)
        } else if (choices != null) {
            VSpace(16)
            Text("On your KOSync server", style = CpType.label, color = c.text)
            Quiet(
                if (choices.isEmpty()) "Every document there already belongs to a book here."
                else "Not linked to any book here, newest first. The likeliest (a position close to this book's) are marked; tap one to check it.",
                color = c.faint,
            )
            VSpace(6)
            for (d in choices.take(30)) {
                val likely = kotlin.math.abs(d.percentage - book.percentage) <= 0.05
                Row(
                    Modifier.fillMaxWidth().clickable { id = d.document; found = d; error = null }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${Math.round(d.percentage * 1000) / 10.0}% \u00B7 ${d.device ?: "unknown device"}", style = CpType.body, color = c.text)
                        Text(
                            d.document.take(12) + "\u2026 \u00B7 " + (if (d.timestamp > 0) fmt.format(Instant.ofEpochSecond(d.timestamp).atZone(ZoneId.systemDefault())) else "no date"),
                            style = CpType.small, color = c.quiet,
                        )
                    }
                    if (likely) Chip("likely", color = c.accentText, background = c.accentTint)
                }
            }
        }
        VSpace(16)
        CpTextField(id, { id = it.trim(); found = null; error = null }, if (choices != null) "Or paste a document ID" else "Document ID", placeholder = "From your KOSync dashboard, e.g. 51e343…")
        VSpace(8)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(if (busy) "Checking…" else "Check it", { if (id.isNotBlank() && !busy) check(id) }, outlined = false)
        }
        found?.let { p ->
            VSpace(10)
            CpCard {
                Column(Modifier.padding(14.dp)) {
                    Text("${Math.round(p.percentage * 1000) / 10.0}% · ${p.device ?: "unknown device"}", style = CpType.bodyStrong, color = c.text)
                    Quiet("Updated " + fmt.format(Instant.ofEpochSecond(p.timestamp).atZone(ZoneId.systemDefault())) + ". This book is at ${book.percent}% here.", color = c.faint)
                    VSpace(10)
                    BigButton("Link to this book", { vm.linkKosync(book.document, p.document); onClose() }, enabled = p.document.lowercase() !in linked && p.document.lowercase() != book.document)
                }
            }
        }
        error?.let { VSpace(8); Text(it, style = CpType.small, color = c.errorText) }
        VSpace(18)
        Text("Or from the file's name", style = CpType.label, color = c.text)
        Quiet("If KOReader on that device matches by file name (Progress sync › Document matching › Filename), type the file's exact name, extension included.", color = c.faint)
        VSpace(8)
        CpTextField(fileName, { fileName = it; found = null; error = null }, "File name", placeholder = "The Gate of the Feral Gods - Matt Dinniman.epub")
        if (fileName.isNotBlank()) {
            val fromName = remember(fileName) { vm.kosyncIdForFileName(fileName) }
            VSpace(6)
            Quiet("Its ID: $fromName" + if (id.isNotBlank()) { if (id.equals(fromName, ignoreCase = true)) " · matches the ID above" else " · not the ID above" } else "", color = c.textMid)
            VSpace(6)
            PillButton(if (busy) "Checking…" else "Check this name's ID", { if (!busy) { id = fromName; check(fromName) } })
        }
    }
}
