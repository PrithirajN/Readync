package io.github.prithirajn.trackersync.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.Canvas
import io.github.prithirajn.trackersync.R
import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.LinkSource
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.sync.Highlights
import io.github.prithirajn.trackersync.sync.SyncEngine
import io.github.prithirajn.trackersync.trackers.ReviewSupport
import io.github.prithirajn.trackersync.trackers.Trackers
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import io.github.prithirajn.trackersync.ui.theme.CpType
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlowSheet(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    val c = Cp.colors
    ModalBottomSheet(
        dragHandle = { SheetGrip() },
        onDismissRequest = onClose,
        sheetState = rememberSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // When what's in it changes size (a list arriving), the sheet glides to the new height.
                .animateContentSize(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 260f))
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
        ) {
            Text(title, style = CpType.section, color = c.text)
            content()
        }
    }
}

/** A text box that grows with what's typed (the app's other fields are single-line). */
@Composable
fun MultiLineField(value: String, onChange: (String) -> Unit, label: String, placeholder: String, minHeight: Int = 120) {
    val c = Cp.colors
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = CpType.label, color = c.textMid)
        VSpace(6)
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight.dp)
                .background(c.surface, CpShapes.control)
                .border(1.dp, c.hairline, CpShapes.control)
                .padding(14.dp),
        ) {
            if (value.isEmpty()) Text(placeholder, style = CpType.body, color = c.faint)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = CpType.body.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Five stars in tenths, like Seekquel: tap or drag across the stars to set it (the stars fill
 * partly, so 4.3 shows as four and a third), − and + fine-tune by 0.1, Clear removes it. Each
 * tracker gets the nearest step it takes when it's sent.
 */
@Composable
fun StarPicker(value: Double?, onChange: (Double?) -> Unit) {
    val c = Cp.colors
    var width by remember { mutableStateOf(1) }
    fun at(x: Float): Double = (Math.round((x / width).coerceIn(0f, 1f) * 50.0) / 10.0).coerceIn(0.1, 5.0)
    Column {
        Row(
            Modifier
                .onSizeChanged { width = it.width.coerceAtLeast(1) }
                .pointerInput(Unit) { detectTapGestures { onChange(at(it.x)) } }
                .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> onChange(at(change.position.x)) } },
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in 1..5) {
                val fill = ((value ?: 0.0) - (i - 1)).coerceIn(0.0, 1.0).toFloat()
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    LucideIcon(R.drawable.lucide_star, Modifier.size(38.dp), c.faint)
                    if (fill > 0f) {
                        LucideIcon(
                            R.drawable.lucide_star_filled,
                            Modifier.size(38.dp).drawWithContent {
                                clipRect(right = size.width * fill) { this@drawWithContent.drawContent() }
                            },
                            c.warn,
                        )
                    }
                }
            }
        }
        VSpace(8)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("\u2212 0.1", { onChange(((value ?: 0.1) - 0.1).coerceAtLeast(0.1).let { Math.round(it * 10) / 10.0 }) })
            Text(value?.let { SyncEngine.stars(Math.round(it * 10) / 10.0) } ?: "Not rated", style = CpType.bodyStrong, color = c.textMid,
                modifier = Modifier.width(76.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            PillButton("+ 0.1", { onChange(((value ?: 0.0) + 0.1).coerceAtMost(5.0).let { Math.round(it * 10) / 10.0 }) })
            if (value != null) PillButton("Clear", { onChange(null) }, outlined = false)
        }
    }
}

/**
 * Rate and review once: it goes to every linked tracker that takes it (Hardcover, Goodreads,
 * StoryGraph, Fable and Pagebound take both; Seekquel takes the rating).
 */
@Composable
fun RateSheet(vm: AppViewModel, state: Snapshot, book: Book, onClose: () -> Unit) {
    val c = Cp.colors
    var rating by remember { mutableStateOf(book.rating) }
    var review by remember { mutableStateOf(book.review ?: "") }
    var busy by remember { mutableStateOf(false) }
    val aiReady = remember { vm.aiReady() }
    var drafting by remember { mutableStateOf(false) }
    var draftError by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var spoilers by remember { mutableStateOf<List<Int>?>(null) }
    var spoilersFor by remember { mutableStateOf("") }
    // Saved, for a book finished or stopped, with Threads connected: offered to post it there.
    var offerThreads by remember { mutableStateOf(false) }
    // Saved, for a finished book without Threads: offered a recap to share anywhere.
    var offerRecap by remember { mutableStateOf(false) }
    if (offerRecap) {
        RecapOfferSheet(vm, state, state.books[book.document] ?: book) { onClose() }
        return
    }
    if (offerThreads) {
        ReviewPostSheet(vm, state, state.books[book.document] ?: book) { onClose() }
        return
    }
    val takers = Trackers.all.filter { t ->
        val svc = state.service(t.id)
        val link = state.link(t.id, book.document)
        svc.linked && svc.enabled && t.reviews != ReviewSupport.NONE && t.id != book.origin && link?.source != LinkSource.NONE
    }
    FlowSheet(if (book.status == io.github.prithirajn.trackersync.data.ReadStatus.FINISHED) "You finished it" else "Rate & review", {
        if (book.rating == null && book.review == null) vm.dismissFinish(book.document)
        onClose()
    }) {
        Quiet(book.displayTitle + (book.author?.let { " · $it" } ?: ""), maxLines = 2)
        VSpace(16)
        // A little celebration: on opening for a book just finished, and for a rating of 4.5 or more.
        var burst by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) { if (book.status == io.github.prithirajn.trackersync.data.ReadStatus.FINISHED && book.rating == null) burst++ }
        Box {
            StarPicker(rating) { r -> if ((r ?: 0.0) >= 4.5 && (rating ?: 0.0) < 4.5) burst++; rating = r }
            Confetti(burst, Modifier.matchParentSize())
        }
        // Seekquel is the source of ratings: say what happens to one set here.
        val sq = state.service("seekquel")
        if (sq.linked && book.document in state.seekquelRatings) {
            VSpace(6)
            Quiet(
                if (sq.enabled && state.link("seekquel", book.document)?.externalId != null) "From Seekquel. A new rating here goes to Seekquel too."
                else "From Seekquel, which is where ratings come from. Change it there: Seekquel's sync is off, so a rating set here is replaced on the next sync.",
                color = c.faint,
            )
        }
        VSpace(16)
        MultiLineField(review, { review = it }, "Review (optional)", "What stayed with you?")
        // With an on-device model: a first draft from your rating, highlights and notes, to make your own.
        if (aiReady) {
            VSpace(8)
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillButton(if (drafting) "Drafting…" else if (review.isBlank()) "Draft from my highlights" else "Draft again", {
                    if (!drafting) {
                        drafting = true; draftError = null
                        vm.aiDraftReview(book.document, rating) { out, e -> drafting = false; if (out != null) review = out else draftError = e }
                    }
                })
                HSpace(8)
                Quiet("A starting point: edit it before you save.", color = c.faint)
            }
            draftError?.let { VSpace(4); Text(it, style = CpType.small, color = c.errorText) }
            // Before it goes to public trackers: which sentences give the story away, to hide them as ||spoilers||.
            if (review.isNotBlank()) {
                VSpace(8)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PillButton(if (checking) "Checking…" else "Check for spoilers", {
                        if (!checking) {
                            checking = true; draftError = null; spoilers = null
                            val text = review
                            vm.aiSpoilers(book.document, text) { out, e -> checking = false; spoilers = out; spoilersFor = text; if (out == null) draftError = e }
                        }
                    })
                }
                val found = spoilers?.takeIf { spoilersFor == review }
                if (found != null) {
                    VSpace(6)
                    if (found.isEmpty()) {
                        Quiet("No spoilers found. Small models miss things, so give it a read too.", color = c.faint)
                    } else {
                        val spans = remember(review, found) { io.github.prithirajn.trackersync.ai.Assistant.sentences(review) }
                        CpCard {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                Text("These may give the story away", style = CpType.small, color = c.accentText)
                                found.mapNotNull { spans.getOrNull(it) }.forEach { sp ->
                                    VSpace(4)
                                    Text("\u201C" + review.substring(sp.start, sp.end) + "\u201D", style = CpType.small, color = c.textMid)
                                }
                                VSpace(10)
                                PillButton("Hide them as spoilers", {
                                    review = io.github.prithirajn.trackersync.ai.Assistant.hideSpoilers(review, found)
                                    spoilers = null
                                }, outlined = false)
                            }
                        }
                    }
                }
            }
        }
        VSpace(6)
        Quiet("Markdown works: **bold**, *italic*, ~~strike~~, - lists, > quotes, [links](https://…), ||spoilers||", color = c.faint)
        if (review.isNotBlank() && review.contains(Regex("[*_~`>#|\\[]|^\\s*[-+]\\s|^\\s*\\d+[.)]\\s", RegexOption.MULTILINE))) {
            VSpace(10)
            Text("Preview", style = CpType.small, color = c.quiet)
            VSpace(4)
            CpCard { MarkdownText(review, Modifier.padding(14.dp)) }
            VSpace(4)
            Quiet("Goodreads and StoryGraph show the formatting; Hardcover, Fable and Pagebound get it as clean text. Spoilers are hidden on Goodreads; elsewhere the review is marked as having spoilers where the site allows.", color = c.faint)
        }
        VSpace(10)
        if (takers.isEmpty()) {
            Quiet("No linked tracker takes ratings for this book yet; it's kept here.", color = c.faint)
        } else {
            Quiet("Goes to " + takers.joinToString(", ") { t ->
                t.name + if (t.reviews == ReviewSupport.RATING) " (rating)" else ""
            } + " on the next sync.", color = c.faint)
        }
        VSpace(16)
        BigButton("Save", {
            busy = true
            vm.saveReview(book.document, rating, review) { err ->
                busy = false
                if (err == null) {
                    when {
                        state.threadsToken != null && ThreadsAuto.canReview(book) -> offerThreads = true
                        book.status == io.github.prithirajn.trackersync.data.ReadStatus.FINISHED -> offerRecap = true
                        else -> onClose()
                    }
                }
            }
        }, busy = busy, enabled = rating != null || review.isNotBlank())
        VSpace(8)
        BigButton("Not now", {
            vm.dismissFinish(book.document)
            onClose()
        }, filled = false)
    }
}

/** Top of the Library after you finish a book: rate it while it's fresh. */
@Composable
fun FinishPromptCard(book: Book, onRate: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = Cp.colors
    CpCard(modifier) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(book.coverUrl, book.displayTitle, 40.dp, 60.dp)
            HSpace(12)
            Column(Modifier.weight(1f)) {
                Eyebrow("You finished it!")
                Text(book.displayTitle, style = CpType.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Quiet("Rate it once; it goes to your trackers.", maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                PillButton("Rate", onRate)
                Text(
                    "Not now",
                    style = CpType.small,
                    color = c.quiet,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .bouncyClickable(CpShapes.pill, pressedScale = 0.9f, onClick = onDismiss)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * What each tracker says about this book, read live: its status and percentage there, what
 * this app last sent and when. A mismatch has a button to send this book there again.
 */
@Composable
fun CompareSheet(vm: AppViewModel, state: Snapshot, book: Book, onClose: () -> Unit) {
    val c = Cp.colors
    var rows by remember { mutableStateOf<List<AppViewModel.TrackerView>?>(null) }
    LaunchedEffect(book.document) { vm.compareTrackers(book.document) { rows = it } }
    FlowSheet("What each tracker says", onClose) {
        Quiet("Here: ${book.percent}% · ${book.status?.label ?: "Reading"}")
        VSpace(14)
        val list = rows
        if (list == null) {
            // A row standing in for each tracker while they're asked, so the sheet is near its size from the start.
            val n = Trackers.all.count { state.service(it.id).linked }.coerceIn(1, 8)
            val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "wait")
            val a by pulse.animateFloat(0.35f, 0.8f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(700), androidx.compose.animation.core.RepeatMode.Reverse), label = "waitA")
            CpCard {
                repeat(n) { i ->
                    if (i > 0) RowDivider()
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Box(Modifier.width(110.dp).height(12.dp).clip(CpShapes.pill).background(c.track.copy(alpha = a)))
                        VSpace(8)
                        Box(Modifier.fillMaxWidth(0.7f).height(10.dp).clip(CpShapes.pill).background(c.track.copy(alpha = a * 0.7f)))
                        VSpace(6)
                        Box(Modifier.fillMaxWidth(0.5f).height(10.dp).clip(CpShapes.pill).background(c.track.copy(alpha = a * 0.7f)))
                    }
                }
            }
            return@FlowSheet
        }
        if (list.isEmpty()) {
            Quiet("Link a tracker under Services first.")
            return@FlowSheet
        }
        CpCard {
            list.forEachIndexed { i, v ->
                if (i > 0) RowDivider()
                val link = v.link
                val theirs = when {
                    link?.source == LinkSource.NONE -> "Not synced (your choice)"
                    link?.externalId == null -> "Not matched yet"
                    v.error != null -> "Couldn't read: ${v.error}"
                    v.remote == null -> "—"
                    else -> listOfNotNull(v.remote.status?.label, v.remote.percent?.let { "$it%" }).joinToString(" · ").ifEmpty { "Not on a shelf" }
                }
                val sent = link?.let {
                    listOfNotNull(it.lastStatus?.label, it.lastPercent?.let { p -> "$p%" }).joinToString(" · ").ifEmpty { null }
                        ?.let { s -> "Sent $s" + if (it.updatedAt > 0) " · ${ago(it.updatedAt)}" else "" }
                }
                val ourStatus = book.status ?: io.github.prithirajn.trackersync.data.ReadStatus.READING
                val mismatch = v.remote != null && link?.externalId != null && link.source != LinkSource.NONE && (
                    (v.remote.status != null && v.remote.status != ourStatus && !(ourStatus == io.github.prithirajn.trackersync.data.ReadStatus.READING && v.remote.status == io.github.prithirajn.trackersync.data.ReadStatus.FINISHED)) ||
                        (v.remote.percent != null && kotlin.math.abs(v.remote.percent - book.percent) > 1 && v.remote.percent < book.percent)
                    )
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ServiceTile(v.tracker.name)
                    HSpace(12)
                    Column(Modifier.weight(1f)) {
                        Text(v.tracker.name, style = CpType.bodyStrong, color = c.text)
                        Text(theirs, style = CpType.small, color = if (mismatch) c.warn else c.textMid)
                        sent?.let { Text(it, style = CpType.small, color = c.faint) }
                        if (v.remote != null && link?.externalId != null && !v.tracker.readsProgress) {
                            Text(
                                if (v.remote.percent == null) "Couldn't find its percentage on the site this time."
                                else "Percentage read off its site.",
                                style = CpType.small, color = c.faint,
                            )
                        }
                    }
                    if (mismatch) {
                        PillButton("Send", { vm.sendTo(v.tracker, book.document) })
                    } else if (link?.externalId != null && link.source != LinkSource.NONE && v.error == null) {
                        LucideIcon(R.drawable.lucide_circle_check, Modifier.size(18.dp), c.good)
                    }
                }
            }
        }
        VSpace(10)
        Quiet("Trackers are read now, so this is what each shows this minute.", color = c.faint)
    }
}

/**
 * Snap a page: photograph a paper page or your e-reader's screen (or pick a screenshot). The text
 * is read on the phone, and a passage that's already marked (shaded, tinted or underlined) is
 * picked for you; otherwise tap its lines or type it. Save it as a highlight that goes to your
 * trackers, and make a book photo of it with the real page around it, from the same sheet.
 */
@Composable
fun PaperHighlightSheet(vm: AppViewModel, state: Snapshot, book: Book, onClose: () -> Unit) {
    val c = Cp.colors
    val context = LocalContext.current
    var photo by remember { mutableStateOf<Pair<File, Uri>?>(null) }
    var pending by remember { mutableStateOf<Uri?>(null) }
    var reading by remember { mutableStateOf(false) }
    var scan by remember { mutableStateOf<PageScanner.Scan?>(null) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    val picked = remember { mutableStateListOf<Int>() }
    var text by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var studio by remember { mutableStateOf<BookShot.Content?>(null) }
    var studioPage by remember { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val p = photo ?: return@rememberLauncherForActivityResult
        if (ok) pending = p.second else p.first.delete()
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) pending = uri }

    LaunchedEffect(pending) {
        val uri = pending ?: return@LaunchedEffect
        reading = true; error = null; said = null
        val r = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PageScanner.scan(context, uri) } }
        reading = false; pending = null
        photo?.first?.let { runCatching { it.delete() } }; photo = null
        r.onFailure { error = it.message ?: "Couldn't read that picture." }
        r.onSuccess { sc ->
            picked.clear()
            if (sc.lines.isEmpty()) { scan = null; lines = emptyList(); error = "No text found on that picture."; return@onSuccess }
            scan = sc
            lines = sc.lines.map { it.text.trim() }
            // A passage marked on the page (an e-reader highlight, a highlighter pen, an underline).
            val m = io.github.prithirajn.trackersync.sync.PageContext.marked(sc.words)
            if (m != null) {
                text = io.github.prithirajn.trackersync.sync.PageContext.textOf(sc.words, m)
                picked.addAll(m.map { sc.words[it].line }.distinct())
            } else text = ""
        }
    }

    val existing = state.paperClips.filter { it.document == book.document }.sortedByDescending { it.createdAt }
    // The highlight you already have for this passage (from your e-reader or an earlier snap), so it isn't added twice.
    val already = remember(text, state.cpClips, state.paperClips, state.kindleClips, state.libbyClips, state.moonClips, state.historyClips, state.koClips) {
        if (text.length < 8) null
        else Highlights.forBook(state, book.document).map { it.clip }.firstOrNull { cl ->
            val a = cl.text.lowercase().filter { it.isLetterOrDigit() }
            val b = text.lowercase().filter { it.isLetterOrDigit() }
            a.isNotEmpty() && b.isNotEmpty() && (a.contains(b) || b.contains(a) || (io.github.prithirajn.trackersync.sync.PageContext.find(text, cl.text)?.score ?: 0.0) >= 0.85)
        }
    }
    val takers = Trackers.all.filter { val s = state.service(it.id); s.linked && s.highlights }
    val aiReady = remember { vm.aiReady() }
    var hint by remember { mutableStateOf("") }
    var aiBusy by remember { mutableStateOf<String?>(null) }
    val pageText = scan?.text?.takeIf { it.isNotBlank() }
    val pageNo = remember(scan) { scan?.let { io.github.prithirajn.trackersync.sync.PageReader.guess(it.lines, book.pageCount, null)?.page } }
    fun shot(t: String, n: String?) = BookShot.Content(
        text = t, author = book.author, title = book.displayTitle, chapter = null, page = pageNo,
        others = emptyList(), note = n, sectors = BookShot.sectorsFor(state, book),
    )
    fun reset() { text = ""; note = ""; lines = emptyList(); picked.clear(); scan = null }

    FlowSheet("Snap a page", onClose) {
        Quiet("Photograph a page or your e-reader's screen, or pick a screenshot. A highlighted passage is picked for you; otherwise tap its lines. Save it as a highlight, make a book photo of it, or both.")
        VSpace(14)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton(if (reading) "Reading the page…" else if (lines.isEmpty()) "Photograph the page" else "Another photo", {
                val p = PageScanner.newPhoto(context)
                photo = p
                try { camera.launch(p.second) } catch (e: ActivityNotFoundException) { error = "No camera app found. Pick a screenshot, or type the passage." }
            }, Modifier.weight(1f), busy = reading, filled = lines.isEmpty())
            BigButton("Screenshot", {
                gallery.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, Modifier.weight(1f), filled = false, enabled = !reading)
        }
        error?.let { VSpace(8); Text(it, style = CpType.small, color = c.errorText) }
        if (lines.isNotEmpty()) {
            VSpace(14)
            Quiet(if (picked.isNotEmpty() && text.isNotBlank()) "Your highlighted passage is picked. Tap lines to change it:" else "Tap the lines to include:")
            VSpace(6)
            CpCard {
                lines.forEachIndexed { i, line ->
                    if (i > 0) RowDivider()
                    val on = i in picked
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .bouncyClickable(RectangleShape, pressedScale = 0.99f) {
                                if (on) picked.remove(i) else picked.add(i)
                                text = picked.sorted().joinToString(" ") { lines[it] }
                                    .replace(Regex("(\\w)- (\\w)"), "$1$2") // words split across lines
                            }
                            .background(if (on) c.accentTint else c.surface.copy(alpha = 0f))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.width(4.dp).size(width = 4.dp, height = 18.dp)
                                .background(if (on) c.accent else c.divider, CpShapes.pill)
                        )
                        HSpace(10)
                        Text(line, style = CpType.body, color = if (on) c.text else c.textMid)
                    }
                }
            }
        }
        // With an on-device model: find the passage from a few words of it.
        if (aiReady && lines.isNotEmpty()) {
            VSpace(12)
            Row(verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) { CpTextField(hint, { hint = it }, "Or say which passage", placeholder = "A few words you remember") }
                HSpace(8)
                PillButton(if (aiBusy == "find") "Finding…" else "Find it", {
                    if (aiBusy == null && hint.isNotBlank()) {
                        aiBusy = "find"; error = null
                        vm.aiFindPassage(lines.joinToString("\n"), hint) { out, e ->
                            aiBusy = null
                            if (out != null) { text = out; picked.clear() } else error = e
                        }
                    }
                }, outlined = false)
            }
        }
        VSpace(14)
        MultiLineField(text, { text = it }, "Passage", "Tap lines above, or type it", minHeight = 90)
        // The photo's reading errors (broken words, stray characters) fixed, wording kept.
        if (aiReady && text.isNotBlank()) {
            VSpace(6)
            PillButton(if (aiBusy == "clean") "Cleaning…" else "Clean up text", {
                if (aiBusy == null) {
                    aiBusy = "clean"; error = null
                    vm.aiCleanScan(text) { out, e -> aiBusy = null; if (out != null) text = out else error = e }
                }
            })
        }
        if (already == null) {
            VSpace(10)
            MultiLineField(note, { note = it }, "Your note (optional)", "Why it stood out", minHeight = 60)
        }
        VSpace(10)
        Quiet(
            when {
                already != null -> "You already have this highlight, so it isn't added again. Its book photos get the real page around it."
                takers.isEmpty() -> "Turn on Send highlights for a tracker under Services to send it; it's kept here meanwhile."
                else -> "Goes to ${takers.joinToString(", ") { it.name }} on the next sync."
            },
            color = c.faint,
        )
        VSpace(14)
        val t = text.trim()
        // Save (or reuse the one you have), then open the book photo with the page around it.
        BigButton(if (already != null) "Make a book photo" else "Save and make a book photo", {
            if (t.isEmpty()) return@BigButton
            val known = already
            if (known != null) {
                if (pageText != null) vm.setClipPage(known.id, pageText)
                studioPage = pageText; studio = shot(known.text, known.note)
            } else {
                busy = true
                vm.addPaperHighlight(book.document, t, note.ifBlank { null }, pageText) { err ->
                    busy = false
                    if (err != null) error = err else { studioPage = pageText; studio = shot(t, note.ifBlank { null }) }
                }
            }
        }, busy = busy, enabled = t.isNotEmpty())
        if (already == null) {
            VSpace(8)
            BigButton("Just save the highlight", {
                busy = true
                vm.addPaperHighlight(book.document, t, note.ifBlank { null }, pageText) { err ->
                    busy = false
                    if (err == null) { reset(); said = "Saved. Snap another page, or close." } else error = err
                }
            }, filled = false, enabled = t.isNotEmpty() && !busy)
        }
        VSpace(8)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            PillButton("Just the photo, don't save", {
                if (t.isNotEmpty()) { studioPage = pageText; studio = shot(t, note.ifBlank { null }) }
            })
        }
        said?.let { VSpace(8); Quiet(it, color = c.faint) }
        if (existing.isNotEmpty()) {
            VSpace(12)
            Quiet("${existing.size} from this book so far; they're under the book to edit or send again.", color = c.faint)
        }
    }

    studio?.let { base ->
        BookStudio(
            base = base,
            pageText = studioPage,
            onPageText = { studioPage = it },
            plain = io.github.prithirajn.trackersync.trackers.quoted(base.text) + "\n\n— " + book.displayTitle + (book.author?.let { ", $it" } ?: ""),
            onClose = { studio = null },
        )
    }
}

/**
 * A book's highlights and notes, under the book: CrossPoint's and ones from paper, as you edited
 * them, with where each went. Tapping one opens it to edit and send again.
 */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun HighlightsSection(vm: AppViewModel, state: Snapshot, book: Book) {
    val c = Cp.colors
    val items = remember(state.cpClips, state.paperClips, state.kindleClips, state.libbyClips, state.moonClips, state.historyClips, state.koClips, state.clipEdits, state.sentClippings, state.resend, state.clipHidden, book.document) {
        Highlights.forBook(state, book.document)
    }
    var open by remember { mutableStateOf<String?>(null) }
    var summing by remember { mutableStateOf(false) }
    var catchingUp by remember { mutableStateOf(false) }
    val aiReady = remember { vm.aiReady() }
    // Read in KOReader without its highlights coming in: said where they'd show.
    if (book.document in state.koreaderDocs && io.github.prithirajn.trackersync.sync.KoreaderDevice.highlightsMissing(state)) {
        VSpace(20)
        CpCard {
            Column(Modifier.padding(14.dp)) {
                Quiet("You read this in KOReader. Its highlights and notes don't come through sync, but they can come from KOReader's own files.")
                VSpace(8)
                PillButton("Set up KOReader highlights", { vm.openKoHighlights() })
            }
        }
    }
    if (items.isEmpty()) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val unsent = remember(items, state.sentClippings, state.resend, state.links, state.services) { vm.unsentHighlights(book.document) }
    var sending by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf<String?>(null) }
    val canCatchUp = aiReady && book.status != io.github.prithirajn.trackersync.data.ReadStatus.FINISHED && book.percent in 1..97 && items.any { (it.clip.percent ?: 0.0) <= book.percentage + 0.005 }
    val withNotes = items.count { !it.clip.note.isNullOrBlank() }
    // Waiting to be checked (with Check highlights before sending on).
    val toCheck = remember(items, state.clipChecked, state.checkHighlights, state.sentClippings) {
        io.github.prithirajn.trackersync.sync.SpoilerCheck.waiting(state).let { w -> items.count { it.clip.id in w } }
    }
    var checking by remember { mutableStateOf(false) }
    if (checking) HighlightCheckSheet(vm, state, book.document) { checking = false }
    var selecting by remember(book.document) { mutableStateOf(false) }
    val picked = remember(book.document) { androidx.compose.runtime.mutableStateListOf<String>() }
    var sharingMany by remember { mutableStateOf(false) }

    // Everything you can do with them comes first, on a little screen with the count, then the highlights.
    VSpace(16)
    Column(
        Modifier.riseIn(0).fillMaxWidth()
            .shadow(14.dp, RoundedCornerShape(26.dp), ambientColor = c.shade, spotColor = c.shade)
            .clip(RoundedCornerShape(26.dp)).background(c.surface).padding(10.dp),
    ) {
        Screen(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    DeviceLabel("Highlights & notes", color = c.screenDim)
                    RollingNumber(items.size, CpType.screenNumber.copy(fontSize = 40.sp, lineHeight = 40.sp), c.screenText)
                }
                Column(horizontalAlignment = Alignment.End) {
                    DeviceLabel("$withNotes with notes", color = c.screenDim)
                    VSpace(4)
                    DeviceLabel(if (unsent.isEmpty()) "All sent" else "${unsent.maxOf { it.second }} not sent", color = if (unsent.isEmpty()) c.screenDim else c.accent)
                }
            }
            VSpace(10)
            // Where in the book they are: a mark for each along the length of it.
            Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                drawRoundRect(c.screenOff, size = androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx()), topLeft = Offset(0f, size.height / 2 - 1.5.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f))
                val read = (book.percentage).toFloat().coerceIn(0f, 1f)
                drawRoundRect(c.screenDim.copy(alpha = 0.5f), size = androidx.compose.ui.geometry.Size(size.width * read, 3.dp.toPx()), topLeft = Offset(0f, size.height / 2 - 1.5.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f))
                for (it in items) it.clip.percent?.let { p -> drawCircle(c.accent, 3.dp.toPx(), Offset(size.width * p.toFloat().coerceIn(0f, 1f), size.height / 2)) }
            }
        }
        VSpace(10)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (toCheck > 0) PillButton("Check $toCheck before sending", { checking = true }, outlined = false)
            if (unsent.isNotEmpty()) PillButton("Send to trackers", { sending = true }, outlined = false)
            // Several at once, as cards or on one sheet, to X, Threads or anywhere.
            // Share or export: pictures (cards or one sheet) or text, of all of them or the ones you pick.
            PillButton(if (selecting) "Cancel" else "Share or export", {
                selecting = !selecting; picked.clear()
                if (selecting) picked.addAll(items.map { it.clip.id })
            })
            // With an on-device model: what they add up to, and where you were.
            if (aiReady && items.size >= 2) PillButton("Sum up", { summing = true })
            if (canCatchUp) PillButton("Catch me up", { catchingUp = true })
        }
    }

    // Choosing several: how many, and on to sharing them.
    if (selecting) {
        VSpace(12)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Quiet(if (picked.isEmpty()) "Tap the highlights to share" else if (picked.size == items.size) "All ${items.size} chosen; tap any to leave it out" else "${picked.size} chosen", Modifier.weight(1f), color = c.textMid)
            if (picked.size < items.size) Text("All", style = CpType.small, color = c.accent, modifier = Modifier.clip(CpShapes.control).clickable { picked.clear(); picked.addAll(items.map { it.clip.id }) }.padding(6.dp))
            else Text("None", style = CpType.small, color = c.accent, modifier = Modifier.clip(CpShapes.control).clickable { picked.clear() }.padding(6.dp))
            HSpace(6)
            PillButton("Share ${picked.size}", { if (picked.isNotEmpty()) sharingMany = true }, outlined = false)
        }
    }
    // Every highlight in full, in order through the book.
    VSpace(18)
    items.forEachIndexed { i, item ->
        val on = item.clip.id in picked
        Box(if (selecting && on) Modifier.border(2.dp, c.accent, RoundedCornerShape(22.dp)) else Modifier) {
            HighlightCard(
                item, Modifier.riseIn(i + 1),
                onOpen = { if (selecting) { if (on) picked.remove(item.clip.id) else picked.add(item.clip.id) } else open = item.clip.id },
                onShare = { sharing = item.clip.id },
                check = { CheckChip(state, item.clip.id) { vm.markHighlights(listOf(item.clip.id), it, send = true) } },
            )
            if (selecting) Box(
                Modifier.align(Alignment.TopEnd).padding(10.dp).size(22.dp).clip(CircleShape)
                    .background(if (on) c.accent else c.surface).border(1.5.dp, if (on) c.accent else c.hairline, CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (on) Text("\u2713", style = CpType.small, color = c.page) }
        }
        VSpace(12)
    }
    if (sharingMany) {
        // In book order, whatever order they were tapped in.
        val chosenItems = items.filter { it.clip.id in picked }.sortedWith(compareBy({ it.clip.percent ?: 2.0 }, { it.clip.createdAt }))
        MultiShareSheet(book, state.facts[book.document], chosenItems.map { it.clip }, markdown = Highlights.toMarkdown(book, chosenItems)) { sharingMany = false; selecting = false }
    }
    open?.let { id ->
        items.firstOrNull { it.clip.id == id }?.let { EditHighlightSheet(vm, state, it) { open = null } }
    }
    sharing?.let { id ->
        items.firstOrNull { it.clip.id == id }?.let { item ->
            QuoteShareSheet(item.clip, state.books[item.clip.document], others = BookShot.othersFor(state, item.clip), pageText = state.clipPages[item.clip.id], onPageText = { vm.setClipPage(item.clip.id, it) }, sectors = BookShot.sectorsFor(state, state.books[item.clip.document]), facts = state.facts[item.clip.document]) { sharing = null }
        }
    }
    if (sending) SendHighlightsSheet(vm, book, unsent) { sending = false }
    if (catchingUp) CatchUpSheet(vm, book) { catchingUp = false }
    if (summing) HighlightSummarySheet(vm, state, book) { summing = false }
}

/** Pick the trackers a book's not-yet-sent highlights go to. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendHighlightsSheet(vm: AppViewModel, book: Book, targets: List<Pair<io.github.prithirajn.trackersync.trackers.Tracker, Int>>, onClose: () -> Unit) {
    val c = Cp.colors
    val picked = remember { mutableStateMapOf<String, Boolean>().apply { targets.forEach { (t, _) -> put(t.id, true) } } }
    ModalBottomSheet(
        dragHandle = { SheetGrip() },
        onDismissRequest = onClose,
        sheetState = rememberSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            Text("Send to trackers", style = CpType.section, color = c.text)
            Quiet("${book.displayTitle}'s highlights that haven't gone there yet, older ones too. Each goes at its own place in the book, a few at a time.")
            VSpace(16)
            CpCard {
                targets.forEachIndexed { i, (t, n) ->
                    if (i > 0) RowDivider()
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = CpType.bodyStrong, color = c.text)
                            Quiet("$n highlight${if (n == 1) "" else "s"}", color = c.faint)
                        }
                        CpToggle(picked[t.id] == true, { picked[t.id] = it }, "Send to ${t.name}")
                    }
                }
            }
            VSpace(16)
            val chosen = targets.filter { picked[it.first.id] == true }
            BigButton(
                if (chosen.isEmpty()) "Pick a tracker" else "Send ${chosen.sumOf { it.second }}",
                { vm.sendBookHighlights(book.document, chosen.map { it.first.id }); onClose() },
                enabled = chosen.isNotEmpty(),
            )
        }
    }
}

/**
 * One highlight in full: where it is and when, the passage in the reading face under a big quote
 * mark, your note in your own hand beneath it, and where it's been sent. Tap to edit; the picture
 * key shares it as an image.
 */
@Composable
private fun HighlightCard(item: Highlights.Item, modifier: Modifier, onOpen: () -> Unit, onShare: () -> Unit, check: @Composable () -> Unit = {}) {
    val c = Cp.colors
    val isNote = io.github.prithirajn.trackersync.sync.KoHighlightSync.isNote(item.clip.id) || item.clip.text.isBlank()
    val where = io.github.prithirajn.trackersync.sync.HighlightPosition.label(item.clip)
    val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(item.clip.createdAt * 1000))
    Box(
        modifier.fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(22.dp), ambientColor = c.shade, spotColor = c.shade)
            .clip(RoundedCornerShape(22.dp))
            .background(c.surface)
            .border(1.dp, c.hairline, RoundedCornerShape(22.dp))
            .bouncyClickable(RoundedCornerShape(22.dp), pressedScale = 0.985f, onClickLabel = "Edit highlight") { onOpen() },
    ) {
        // The spine: orange for one you've edited, the book's ink otherwise.
        Box(Modifier.align(Alignment.CenterStart).width(4.dp).height(48.dp).clip(CpShapes.pill).background(if (item.edited) c.accent else c.track))
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 12.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeviceLabel(listOfNotNull(where, date).joinToString("  /  "), color = c.quiet, modifier = Modifier.weight(1f))
                // With Check highlights before sending: what it was checked as (tap to change).
                check()
                Spacer(Modifier.width(8.dp))
                Key(onShare, Modifier.size(width = 34.dp, height = 34.dp), shape = CircleShape, ledge = 2.dp, label = "Share as a picture") {
                    LucideIcon(R.drawable.lucide_image, Modifier.size(15.dp), c.textMid)
                }
            }
            if (!isNote) {
                Box {
                    Text("\u201C", style = CpType.reading.copy(fontSize = 54.sp, lineHeight = 54.sp), color = c.accent.copy(alpha = 0.28f), modifier = Modifier.offset(x = (-4).dp, y = (-6).dp))
                    Text(
                        item.clip.text.trim(),
                        style = CpType.reading.copy(fontSize = 17.sp, lineHeight = 27.sp),
                        color = c.text,
                        modifier = Modifier.padding(top = 18.dp),
                    )
                }
            }
            item.clip.note?.takeIf { it.isNotBlank() }?.let { note ->
                VSpace(if (isNote) 10 else 12)
                // Your note, in your hand, on a tinted slip with a pen mark.
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.accentTint).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text("\u270E", style = CpType.label, color = c.accentText, modifier = Modifier.padding(top = 3.dp, end = 8.dp))
                    Text(note.trim(), style = CpType.eyebrow.copy(fontSize = 21.sp, lineHeight = 25.sp), color = c.accentText)
                }
            }
            VSpace(12)
            // Where it came from, and the trackers that have it (a badge each).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.from + if (item.edited) " · edited" else "", style = CpType.small, color = c.faint, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                when {
                    io.github.prithirajn.trackersync.sync.KoHighlightSync.isNote(item.clip.id) -> Chip("Kept here")
                    item.resending.isNotEmpty() -> Chip("Sending again", color = c.accentText)
                    item.sentTo.isEmpty() -> Chip("Not sent yet", color = c.accentText)
                    else -> Row(horizontalArrangement = Arrangement.spacedBy((-5).dp)) {
                        for (id in item.sentTo.take(6)) Box(Modifier.border(1.5.dp, c.surface, RoundedCornerShape(7.dp))) { ServiceTile(Trackers.byId(id)?.name ?: id, 20.dp) }
                    }
                }
            }
        }
    }
}

private fun highlightLine(item: Highlights.Item): String {
    val parts = ArrayList<String>()
    parts += item.from
    if (item.edited) parts += "edited"
    io.github.prithirajn.trackersync.sync.HighlightPosition.label(item.clip)?.let { parts += it }
    parts += java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(item.clip.createdAt * 1000))
    parts += when {
        // A note on a bookmark has no passage to quote: it's kept here only.
        io.github.prithirajn.trackersync.sync.KoHighlightSync.isNote(item.clip.id) -> "kept here"
        item.resending.isNotEmpty() -> "sending again"
        item.sentTo.isEmpty() -> "not sent yet"
        else -> "sent to " + item.sentTo.joinToString(", ") { Trackers.byId(it)?.name ?: it }
    }
    return parts.joinToString(" · ")
}

/** Edit a highlight's text and note, and send it again: replacing the earlier entry where the tracker allows. */
@Composable
fun EditHighlightSheet(vm: AppViewModel, state: Snapshot, item: Highlights.Item, onClose: () -> Unit) {
    val c = Cp.colors
    val original = state.cpClips.firstOrNull { it.id == item.clip.id }
    var text by remember(item.clip.id) { mutableStateOf(item.clip.text) }
    var note by remember(item.clip.id) { mutableStateOf(item.clip.note ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val targets = remember(state.sentClippings, state.services) { vm.resendTargets(item.clip.id) }
    val postTo = remember(state.sentClippings, state.resend, state.services) { vm.postTargets(item.clip.id) }
    val changed = text.trim() != item.clip.text || (note.trim().ifEmpty { null }) != item.clip.note
    var deleting by remember { mutableStateOf(false) }
    var alsoCrossPoint by remember { mutableStateOf(true) }
    var sharing by remember { mutableStateOf(false) }
    if (sharing) {
        QuoteShareSheet(item.clip, state.books[item.clip.document], text = text, note = note.ifBlank { null }, others = BookShot.othersFor(state, item.clip), pageText = state.clipPages[item.clip.id], onPageText = { vm.setClipPage(item.clip.id, it) }, sectors = BookShot.sectorsFor(state, state.books[item.clip.document]), facts = state.facts[item.clip.document]) { sharing = false }
    }
    val deletable = remember(state.clipRemote) { Highlights.deletable(state, item.clip.id) }
    var alsoTrackers by remember { mutableStateOf(true) }
    val aiReady = remember { vm.aiReady() }
    var expanding by remember { mutableStateOf(false) }

    FlowSheet("Highlight", onClose) {
        Quiet(
            when (item.from) {
                "CrossPoint" -> "From CrossPoint. Your note is saved on crosspoint-sync too; an edited passage is kept here and sent from here."
                "Physical book" -> "From your physical book. Your changes replace it here."
                else -> "From ${item.from}. Your changes are kept here and sent from here."
            },
        )
        VSpace(14)
        MultiLineField(text, { text = it }, "Passage", "The highlighted text", minHeight = 90)
        VSpace(10)
        MultiLineField(note, { note = it }, "Your note (optional)", "Why it stood out", minHeight = 60)
        // With an on-device model: a quick note written out as a fuller journal entry, to edit.
        if (aiReady && note.isNotBlank()) {
            VSpace(8)
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillButton(if (expanding) "Writing…" else "Write out my note", {
                    if (!expanding) {
                        expanding = true; error = null
                        vm.aiExpandNote(item.clip.document, text, note) { out, e -> expanding = false; if (out != null) note = out else error = e }
                    }
                })
                HSpace(8)
                Quiet("You can edit it before saving.", color = c.faint)
            }
        }
        if (targets.isNotEmpty()) {
            VSpace(16)
            Text("Sending again", style = CpType.bodyStrong, color = c.text)
            VSpace(6)
            CpCard {
                targets.forEachIndexed { i, t ->
                    if (i > 0) RowDivider()
                    val replaces = t.updatesHighlights && state.clipRemote["${t.id}|${item.clip.id}"] != null
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        ServiceTile(t.name)
                        HSpace(12)
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = CpType.bodyStrong, color = c.text)
                            Quiet(
                                if (replaces) "Replaces the one sent before"
                                else "Posts a new one; the earlier one stays on ${t.name} (delete it there if you like)",
                                color = if (replaces) c.good else c.warn,
                            )
                        }
                    }
                }
            }
        } else if (item.sentTo.isEmpty()) {
            VSpace(10)
            Quiet("Not sent yet. It goes out as you leave it, on the next sync, to trackers with Send highlights on.", color = c.faint)
        }
        error?.let { VSpace(8); Text(it, style = CpType.small, color = c.errorText) }
        VSpace(16)
        if (targets.isNotEmpty()) {
            BigButton(if (changed) "Save & send again" else "Send again", {
                busy = true; error = null
                vm.editHighlight(item.clip.id, text, note, resend = true) { e -> busy = false; if (e == null) onClose() else error = e }
            }, busy = busy, enabled = text.isNotBlank())
            VSpace(8)
        }
        BigButton(if (targets.isNotEmpty()) "Save without sending" else "Save", {
            busy = true; error = null
            vm.editHighlight(item.clip.id, text, note, resend = false) { e -> busy = false; if (e == null) onClose() else error = e }
        }, filled = targets.isEmpty(), busy = busy, enabled = text.isNotBlank() && changed)
        VSpace(12)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Share", { sharing = true }, outlined = true)
            postTo.forEach { t ->
                PillButton("Post to ${t.name}", {
                    busy = true; error = null
                    vm.postHighlight(t, item.clip.id) { e -> busy = false; if (e == null) onClose() else error = e }
                })
            }
            if (item.edited && original != null) PillButton("Undo my edits", {
                vm.revertHighlight(item.clip.id) { e -> if (e == null) onClose() else error = e }
            })
            if (item.clip.note != null) PillButton("Delete note", {
                busy = true; error = null
                vm.deleteNote(item.clip.id) { e -> busy = false; if (e == null) note = "" else error = e }
            }, danger = true)
            PillButton("Delete", { deleting = !deleting }, danger = true)
        }
        if (item.from == "CrossPoint") {
            VSpace(6)
            Quiet("Note changes (and deleting it) go to CrossPoint too; the passage's text stays as it is there.", color = c.faint)
        }
        if (deleting) {
            VSpace(12)
            CpCard {
                Column(Modifier.padding(14.dp)) {
                    Text("Delete this highlight?", style = CpType.bodyStrong, color = c.text)
                    Quiet("It's removed from this book here and never sent again.")
                    if (item.from == "CrossPoint") {
                        VSpace(10)
                        CpToggle(alsoCrossPoint, { alsoCrossPoint = it }, "Also delete on CrossPoint (and your reader)")
                        if (!alsoCrossPoint) Quiet("Kept on crosspoint-sync and the reader; just hidden here.", color = c.faint)
                    }
                    if (deletable.isNotEmpty()) {
                        VSpace(10)
                        CpToggle(alsoTrackers, { alsoTrackers = it }, "Also delete on " + deletable.joinToString(", ") { Trackers.byId(it)?.name ?: it })
                    }
                    val staying = item.sentTo.filter { it !in deletable || !alsoTrackers }
                    if (staying.isNotEmpty()) {
                        VSpace(6)
                        Quiet("Stays on " + staying.joinToString(", ") { Trackers.byId(it)?.name ?: it } + "; delete it there if you like.", color = c.faint)
                    }
                    VSpace(12)
                    BigButton("Delete highlight", {
                        busy = true; error = null
                        vm.deleteHighlight(item.clip.id, onCrossPoint = item.from == "CrossPoint" && alsoCrossPoint, fromTrackers = alsoTrackers) { e ->
                            busy = false; if (e == null) onClose() else error = e
                        }
                    }, danger = true, busy = busy)
                }
            }
        }
        if (item.sentTo.isNotEmpty()) {
            VSpace(10)
            Quiet("Undoing your edits here doesn't change what's already on your trackers.", color = c.faint)
        }
    }
}

/**
 * Seekquel has a different position for this book than CrossPoint or KOSync: use Seekquel's
 * (it goes to your trackers), or keep the one here (not asked again until Seekquel's changes).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SeekquelProgressCard(
    book: Book,
    ask: io.github.prithirajn.trackersync.data.ProgressAsk,
    onUse: () -> Unit,
    onKeep: () -> Unit,
    modifier: Modifier = Modifier,
    showBook: Boolean = true,
    kosyncOn: Boolean = false,
    onMoveBack: (() -> Unit)? = null,
    onAlways: ((allBooks: Boolean) -> Unit)? = null,
) {
    val c = Cp.colors
    val from = io.github.prithirajn.trackersync.sync.Primary.name(ask.source)
    // A jump back from the book's main source: the furthest position was kept.
    val back = ask.fraction < book.percentage
    CpCard(modifier) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showBook) {
                    BookCover(book.coverUrl, book.displayTitle, 34.dp, 51.dp)
                    HSpace(12)
                }
                Column(Modifier.weight(1f)) {
                    Eyebrow(if (back) "$from went back to ${ask.percent}%" else "$from has a different position")
                    if (showBook) Text(book.displayTitle, style = CpType.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val theirs = "${ask.percent}%" + (ask.status?.takeIf { it != book.status }?.let { " · ${it.label}" } ?: "")
                    Quiet("$from $theirs · here ${book.percent}%", maxLines = 1)
                    if (back) Quiet("Kept at ${book.percent}%, the furthest you've read. Your trackers stay there unless you use the new position.", color = c.faint)
                    else Quiet("Nothing goes to your trackers for this book until you choose." +
                        (if (book.origin == null && ask.source != "crosspoint") " Updating also updates CrossPoint" + (if (kosyncOn && ask.source != "kosync") " and KOSync" else "") + "." else ""),
                        color = c.faint)
                }
            }
            VSpace(10)
            // Wraps on narrow phones: keep, take the new one, or set that source back to this one.
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Keep ${book.percent}%", onKeep)
                PillButton(if (back) "Use this position (${ask.percent}%)" else "Update to ${ask.percent}%", onUse, outlined = false)
                if (onMoveBack != null && io.github.prithirajn.trackersync.sync.Primary.canMoveBack(ask.source))
                    PillButton(if (back) "Set $from to ${book.percent}%" else "Move $from back to ${book.percent}%", onMoveBack)
            }
            // A source you trust: its positions taken from now on, no card.
            if (onAlways != null) {
                VSpace(10)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Quiet("Always use $from:", color = c.faint)
                    HSpace(6)
                    Text("this book", style = CpType.small, color = c.accent, modifier = Modifier.clip(CpShapes.control).clickable { onAlways(false) }.padding(4.dp))
                    Quiet("·", color = c.faint)
                    Text("all books", style = CpType.small, color = c.accent, modifier = Modifier.clip(CpShapes.control).clickable { onAlways(true) }.padding(4.dp))
                }
            }
        }
    }
}

/** Two books that look like one: join them (nothing is joined by itself) or keep them apart. */
@Composable
fun SameBookCard(
    match: io.github.prithirajn.trackersync.sync.SameBook.Match,
    onJoin: () -> Unit,
    onApart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Cp.colors
    fun from(b: Book) = when {
        b.origin == null -> "CrossPoint"
        else -> io.github.prithirajn.trackersync.sync.Primary.name(b.origin)
    }
    fun line(b: Book) = "${b.displayTitle}" + (b.author?.let { " · $it" } ?: "") + " — ${b.percent}% · from ${from(b)}" + (if (b.held) " · not sent yet" else "")
    CpCard(modifier) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BookCover(match.keep.coverUrl ?: match.other.coverUrl, match.keep.displayTitle, 34.dp, 51.dp)
                HSpace(12)
                Column(Modifier.weight(1f)) {
                    Eyebrow("These look like the same book")
                    Quiet(line(match.keep), maxLines = 2)
                    Quiet(line(match.other), maxLines = 2)
                }
            }
            VSpace(6)
            Quiet("Joining keeps the furthest place and every highlight under one book, and future syncs of either land there.", color = c.faint)
            VSpace(10)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Keep apart", onApart)
                PillButton("Join", onJoin, outlined = false)
            }
        }
    }
}

/** Books that came in but don't go to your trackers yet: turned on together, or left for now. */
@Composable
fun WaitingBooksCard(books: List<Book>, onTurnOn: () -> Unit, onLater: () -> Unit, modifier: Modifier = Modifier) {
    val c = Cp.colors
    CpCard(modifier) {
        Column(Modifier.padding(14.dp)) {
            Eyebrow("${books.size} book${if (books.size == 1) " isn't" else "s aren't"} going to your trackers yet")
            Quiet(books.take(3).joinToString(", ") { it.displayTitle } + (if (books.size > 3) " and ${books.size - 3} more" else ""), maxLines = 2)
            Quiet("They came from a reader's highlights or library, or arrived while new books start turned off. Each book's page can also turn it on alone.", color = c.faint)
            VSpace(10)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Not now", onLater)
                PillButton("Turn them all on", onTurnOn, outlined = false)
            }
        }
    }
}

/** A finished book a source says you're reading again: it stays finished until you say yes. */
@Composable
fun RereadAskCard(
    book: Book,
    ask: io.github.prithirajn.trackersync.data.ProgressAsk,
    onYes: () -> Unit,
    onNo: () -> Unit,
    modifier: Modifier = Modifier,
    showBook: Boolean = true,
) {
    val c = Cp.colors
    val from = io.github.prithirajn.trackersync.sync.Primary.name(ask.source)
    CpCard(modifier) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showBook) {
                    BookCover(book.coverUrl, book.displayTitle, 34.dp, 51.dp)
                    HSpace(12)
                }
                Column(Modifier.weight(1f)) {
                    Eyebrow("Reading it again?")
                    if (showBook) Text(book.displayTitle, style = CpType.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Quiet("$from has it at ${ask.percent}%. Here it stays finished, and nothing goes to your trackers, until you say yes.", color = c.faint)
                }
            }
            VSpace(10)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("No, it's finished", onNo)
                PillButton("Yes, reading again", onYes, outlined = false)
            }
        }
    }
}

/**
 * A book's main source and the others by rank, on its page: change it yourself (it then stays),
 * or leave it automatic. Sources used without asking for this book can be stopped here.
 */
@Composable
fun MainSourceCard(vm: AppViewModel, state: Snapshot, book: Book) {
    val c = Cp.colors
    val P = io.github.prithirajn.trackersync.sync.Primary
    val doc = book.document
    val main = P.of(state, doc) ?: return
    val rest = P.ranking(state, doc).drop(1)
    val pinned = doc in state.pinnedPrimary
    val auto = "Automatic"
    CpCard {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Eyebrow("Main source")
                    Text(P.name(main) + if (pinned) " · chosen by you" else "", style = CpType.bodyStrong, color = c.text)
                    Quiet(
                        if (rest.isEmpty()) "Positions from other sources are asked about first."
                        else "Then " + rest.joinToString(", ") { P.name(it) } + ". Others' positions are asked about first.",
                        color = c.faint,
                    )
                }
                val options = listOf(auto) + P.candidates(state, doc)
                CpSelect(if (pinned) main else auto, options, { if (it == auto) "Automatic" else P.name(it) }, onChange = { v ->
                    vm.chooseMainSource(doc, v.takeIf { it != auto })
                })
            }
            val trustedHere = state.trustedFor.filter { it.startsWith("$doc|") }.map { it.substringAfter('|') } + state.trustedSources.toList()
            if (trustedHere.isNotEmpty()) {
                VSpace(8)
                for (src in trustedHere.distinct()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Quiet("${P.name(src)}: used without asking" + if (src in state.trustedSources) " (all books)" else "", Modifier.weight(1f), color = c.faint)
                        Text("Stop", style = CpType.small, color = c.accent, modifier = Modifier.clip(CpShapes.control)
                            .clickable { vm.untrustSource(if (src in state.trustedSources) null else doc, src) }.padding(6.dp))
                    }
                }
            }
        }
    }
}

/** Offered once a book is seen being read in KOReader: bring KOReader's highlights in too. */
@Composable
fun KoreaderNudgeCard(onSetUp: () -> Unit, onLater: () -> Unit, modifier: Modifier = Modifier) {
    val c = Cp.colors
    CpCard(modifier) {
        Column(Modifier.padding(14.dp)) {
            Eyebrow("Reading in KOReader?")
            Text("Bring its highlights in too", style = CpType.bodyStrong, color = c.text)
            VSpace(4)
            Quiet("Your position comes through sync, but KOReader's highlights and notes don't. They can come from KOReader's own files on this phone, or from its HighlightSync or AnnotationSync plugin on Koofr.", color = c.faint)
            VSpace(10)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton("Set up", onSetUp, outlined = false)
                PillButton("Not now", onLater)
            }
        }
    }
}

/**
 * A physical book you've started on your CrossPoint reader: continue there (its highlights,
 * notes, review, rating, dates and matches move to the reader's copy, which keeps the furthest
 * position), or keep both.
 */
@Composable
fun HandoffCard(physical: Book, reader: Book, onContinue: () -> Unit, onKeep: () -> Unit, modifier: Modifier = Modifier) {
    val c = Cp.colors
    val furthest = maxOf(physical.percent, reader.percent)
    CpCard(modifier) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BookCover(reader.coverUrl ?: physical.coverUrl, physical.displayTitle, 34.dp, 51.dp)
                HSpace(12)
                Column(Modifier.weight(1f)) {
                    Eyebrow("Continue on CrossPoint?")
                    Text(physical.displayTitle, style = CpType.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Quiet("Physical book ${physical.percent}% · on your reader ${reader.percent}%", maxLines = 1)
                }
            }
            VSpace(6)
            Quiet("Moves its highlights, notes, review, rating and dates to the reader's copy, which carries on from $furthest%. The physical entry is removed.", color = c.faint)
            VSpace(10)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton("Continue on CrossPoint", onContinue, outlined = false)
                PillButton("Keep both", onKeep)
            }
        }
    }
}
