package io.github.prithirajn.trackersync.ui

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.source.Clipping
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import io.github.prithirajn.trackersync.ui.theme.CpType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Share a highlight as a picture: a live preview, the looks, your note on or off (and reworded for
 * the picture if you like), then share, copy or save it. Plain text is a tap away too.
 *
 * A note on its own (a note on a bookmark, with no passage) is shared as a note: in your hand,
 * without quote marks.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun QuoteShareSheet(
    clip: Clipping,
    book: Book?,
    text: String = clip.text,
    note: String? = clip.note,
    others: List<String> = emptyList(),
    /** The text of the passage's page, read off a photo of it earlier. */
    pageText: String? = null,
    onPageText: (String?) -> Unit = {},
    /** Opens "Book photo from a page" for this book (a passage photographed on your e-reader). */
    onFromPhoto: (() -> Unit)? = null,
    /** The book's kinds of speculative fiction, so its book photo suggests moods that suit it. */
    sectors: Set<String> = emptySet(),
    onClose: () -> Unit,
) {
    val c = Cp.colors
    val context = LocalContext.current
    val tick = rememberTick()
    var look by remember { mutableStateOf(QuoteCard.Look.SPOTLIGHT) }
    // The newer looks come in three shapes: a feed post, a square, a story.
    var size by remember { mutableStateOf(QuoteCard.Size.POST) }
    // A note with no passage of its own: the note is what's shared.
    val standalone = remember(clip.id, text, note) {
        io.github.prithirajn.trackersync.sync.KoHighlightSync.isNote(clip.id) || (text.isBlank() && !note.isNullOrBlank())
    }
    // Your note goes on the card by default (most looks give it its own place); one tap takes it off.
    var withNote by remember { mutableStateOf(!note.isNullOrBlank()) }
    // The note as it goes on the picture: yours to reword here (the highlight keeps its own).
    var noteDraft by remember(clip.id) { mutableStateOf(if (standalone) (note?.takeIf { it.isNotBlank() } ?: text) else note.orEmpty()) }
    var editingNote by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    var showMark by remember { mutableStateOf(BookShot.markOn(context)) }
    var studio by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(pageText) }
    val where = remember(clip) {
        clip.chapter?.takeIf { it.isNotBlank() } ?: io.github.prithirajn.trackersync.sync.HighlightPosition.label(clip)
    }
    val content = QuoteCard.Content(
        text = if (standalone) noteDraft.trim().ifEmpty { note ?: text } else text,
        note = if (standalone) null else noteDraft.trim().takeIf { withNote && it.isNotEmpty() },
        title = book?.displayTitle ?: "A book",
        author = book?.author,
        where = where,
        percent = clip.percent,
        page = clip.pageIn(book?.pageCount),
        standalone = standalone,
    )
    var seed by remember { mutableStateOf<Int?>(null) }
    // The book photo: the scene as you last left it in the studio.
    val shotContent = BookShot.Content(
        text = content.text, author = content.author, title = content.title,
        chapter = clip.chapter?.takeIf { it.isNotBlank() }, page = content.page,
        others = others, note = content.note, coverColor = seed, sectors = sectors, standalone = standalone,
    ).let { sc ->
        val found = if (standalone) null else page?.let { io.github.prithirajn.trackersync.sync.PageContext.find(it, text) }
        if (found != null) sc.copy(before = found.before, after = found.after) else sc
    }
    LaunchedEffect(content, look, size, showMark, studio, page) {
        if (studio) return@LaunchedEffect
        BookShot.setMark(context, showMark)
        // A moment's pause, so typing a note or flicking through looks draws only the one you stop on.
        if (bmp != null) delay(220)
        val drawn = runCatching {
            withContext(Dispatchers.IO) {
                val cover = book?.coverUrl?.let { runCatching { CoverCache.load(context, it, 1400) }.getOrNull() }
                seed = book?.coverUrl?.let { runCatching { CoverAccent.of(context, it) }.getOrNull() }
                withContext(Dispatchers.Default) {
                    if (look == QuoteCard.Look.PAGE) BookShot.render(context, shotContent.copy(coverColor = seed), BookShot.saved(context), mark = showMark)
                    else QuoteCard.render(context, content, look, cover, seed, mark = showMark, size = size)
                }
            }
        }
        // A look that can't be drawn (out of memory, say) keeps the last picture and says so, instead of closing.
        drawn.onSuccess { bmp = it; failed = false }
        drawn.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; failed = true }
    }
    val plain = buildString {
        if (standalone) {
            append(content.text.trim())
            append("\n\n— my note on ${book?.displayTitle ?: "a book"}")
            book?.author?.let { append(", $it") }
        } else {
            append(io.github.prithirajn.trackersync.trackers.quoted(text))
            append("\n\n— ${book?.displayTitle ?: "A book"}")
            book?.author?.let { append(", $it") }
            content.note?.let { append("\n\nMy note: $it") }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
        ) {
            Text(if (standalone) "Share this note" else "Share this passage", style = CpType.section, color = c.text)
            VSpace(14)
            Box(
                Modifier.fillMaxWidth().heightIn(min = 280.dp),
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(bmp, animationSpec = Motion.medium(), label = "quote") { b ->
                    if (b == null && failed) {
                        Quiet("Couldn't draw this one. Try another look or shape.", color = c.faint)
                    } else if (b == null) {
                        Box(Modifier.fillMaxWidth(0.8f).aspectRatio(0.8f), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), color = c.faint)
                        }
                    } else {
                        Image(
                            b.asImageBitmap(),
                            contentDescription = "The passage as a picture",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth(if (b.height > b.width * 1.5f) 0.58f else 0.82f)
                                .aspectRatio(b.width.toFloat() / b.height)
                                .clip(CpShapes.card)
                                .border(1.dp, c.divider, CpShapes.card)
                                .then(if (look == QuoteCard.Look.PAGE) Modifier.bouncyClickable(CpShapes.card, pressedScale = 0.98f, onClickLabel = "Open the studio") { studio = true } else Modifier),
                        )
                    }
                }
            }
            if (failed && bmp != null) { VSpace(6); AnimatedQuiet("Couldn't draw that one; this is the last picture. Try another look or shape.", color = c.faint) }
            VSpace(14)
            // The looks, as chips that wrap onto more lines, so every look is in sight.
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (l in QuoteCard.Look.entries) {
                    val on = l == look
                    Box(
                        Modifier
                            .clip(CpShapes.pill)
                            .background(if (on) c.accentTint else c.divider)
                            .bouncyClickable(CpShapes.pill, pressedScale = 0.94f, role = Role.RadioButton, onClickLabel = l.label) { look = l; tick() }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) { Text(l.label, style = CpType.label, color = if (on) c.accentText else c.textMid) }
                }
            }
            if (look.sized) {
                VSpace(10)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    for (z in QuoteCard.Size.entries) {
                        val on = z == size
                        Box(
                            Modifier
                                .clip(CpShapes.pill)
                                .border(1.dp, if (on) c.accent else c.divider, CpShapes.pill)
                                .bouncyClickable(CpShapes.pill, pressedScale = 0.94f, role = Role.RadioButton, onClickLabel = z.label) { size = z; tick() }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        ) { Text(z.label, style = CpType.small, color = if (on) c.accentText else c.textMid) }
                    }
                }
            }
            if (look == QuoteCard.Look.PAGE) {
                VSpace(12)
                BigButton("Open the studio", { studio = true }, filled = false)
                onFromPhoto?.let { go ->
                    VSpace(6)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { PillButton("\uD83D\uDCF7 From a photo of the page", go) }
                }
                Quiet(
                    if (page != null) "Light, desk, paper, type and your own marks. The real text of its page is around your passage."
                    else "Light, desk, paper, type and your own marks. Photograph the page there to put its real text around your passage.",
                    color = c.faint,
                )
            }
            VSpace(12)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${io.github.prithirajn.trackersync.Edition.name} mark", style = CpType.body, color = c.text, modifier = Modifier.weight(1f))
                CpToggle(showMark, { showMark = it }, "${io.github.prithirajn.trackersync.Edition.name} mark")
            }
            // Your note: on or off, and reworded for the picture if you like (the highlight keeps its own).
            VSpace(4)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (standalone) "Your note" else if (note.isNullOrBlank()) "Add a note" else "Include my note", style = CpType.body, color = c.text)
                    if (withNote || standalone) {
                        val shown = noteDraft.trim()
                        Text(
                            if (shown.isEmpty()) "Nothing written yet" else shown,
                            style = CpType.small, color = c.faint, maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
                if (withNote || standalone) PillButton(if (editingNote) "Done" else "Edit", { editingNote = !editingNote })
                if (!standalone) CpToggle(withNote, { withNote = it; if (it && noteDraft.isBlank()) editingNote = true }, "Include a note")
            }
            if (editingNote && (withNote || standalone)) {
                VSpace(8)
                OutlinedTextField(
                    value = noteDraft, onValueChange = { noteDraft = it.take(600) },
                    label = { Text(if (standalone) "Your note" else "Note on the picture") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.divider, focusedTextColor = c.text, unfocusedTextColor = c.text),
                )
                Quiet("Only for this picture: your highlight keeps its note as it is.", color = c.faint)
                if (!standalone && noteDraft.trim() != note.orEmpty().trim() && !note.isNullOrBlank()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { PillButton("Back to my note", { noteDraft = note.orEmpty() }) }
                }
            }
            VSpace(16)
            BigButton("Share", { bmp?.let { b -> runCatching { QuoteCard.share(context, b, plain) }.onFailure { said = "Couldn't open sharing; try Save image instead." } } }, enabled = bmp != null)
            VSpace(8)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton("Copy image", {
                    bmp?.let { runCatching { QuoteCard.copy(context, it) }.onSuccess { tick(); said = "Copied. Paste it into any app that takes pictures." }.onFailure { said = "Couldn't copy it; use Share instead." } }
                }, Modifier.weight(1f), filled = false, enabled = bmp != null)
                BigButton("Save image", {
                    bmp?.let {
                        val ok = runCatching { QuoteCard.save(context, it) }.getOrDefault(false)
                        said = if (ok) "Saved to Pictures › ${io.github.prithirajn.trackersync.Edition.name}." else "Couldn't save here; use Share and pick your gallery."
                        if (ok) tick()
                    }
                }, Modifier.weight(1f), filled = false, enabled = bmp != null)
            }
            VSpace(8)
            // Straight into a new Threads post, picture and words.
            BigButton("Post to Threads", {
                bmp?.let { b ->
                    val how = runCatching { QuoteCard.postToThreads(context, b, plain.take(500)) }.getOrNull()
                    said = when (how) {
                        "app" -> "Opened in Threads with the picture. The words are copied too, if you need to paste them."
                        "web" -> "Threads isn't installed, so it opened in the browser with the words (a picture can't go that way; save it and add it there)."
                        else -> "Couldn't open Threads."
                    }
                }
            }, filled = false, enabled = bmp != null)
            said?.let { VSpace(8); AnimatedQuiet(it, color = c.faint) }
            VSpace(12)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PillButton("Share as text instead", {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, plain)
                    }
                    runCatching { context.startActivity(android.content.Intent.createChooser(send, if (standalone) "Share note" else "Share quote")) }
                })
            }
        }
    }
    if (studio) {
        BookStudio(
            base = shotContent,
            pageText = page,
            onPageText = { page = it; onPageText(it) },
            plain = plain,
            onClose = { studio = false },
            fullNote = noteDraft.ifBlank { note },
        )
    }
}
