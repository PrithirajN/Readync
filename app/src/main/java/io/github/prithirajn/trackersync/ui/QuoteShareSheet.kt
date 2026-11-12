package io.github.prithirajn.trackersync.ui

import android.graphics.Bitmap
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
import io.github.prithirajn.trackersync.R
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.AnimatedContent
import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.source.Clipping
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import io.github.prithirajn.trackersync.ui.theme.CpType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

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
    // No photo of its page: the words around it from the app's own copy of the book, when there is one.
    LaunchedEffect(clip.id, text) {
        if (page == null && book != null) {
            val around = withContext(Dispatchers.IO) {
                runCatching { io.github.prithirajn.trackersync.source.BookCopies.around(context, book.document, text, clip.spine) }.getOrNull()
            }
            if (page == null && around != null) page = around
        }
    }
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
        dragHandle = { SheetGrip() },
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
            Text(if (standalone) "Share this note" else "Share this passage", style = CpType.section, color = c.text, modifier = Modifier.riseIn(0))
            VSpace(14)
            // The Book photo has its own tile and studio, so it isn't one of the looks to flick through.
            val looks = QuoteCard.Look.entries.filter { it != QuoteCard.Look.PAGE }
            // Which way the last change of look went, so the picture slides the same way.
            var dir by remember { mutableStateOf(1) }
            fun pick(l: QuoteCard.Look) {
                if (l == look) return
                dir = if (l.ordinal > look.ordinal) 1 else -1
                look = l; tick()
            }
            // The device: the picture on its screen, with what's showing along the top and a dot per look.
            Box(
                Modifier.riseIn(1).fillMaxWidth()
                    .shadow(16.dp, RoundedCornerShape(28.dp), ambientColor = c.shade, spotColor = c.shade)
                    .clip(RoundedCornerShape(28.dp)).background(c.surface).padding(10.dp),
            ) {
                Column {
                    Screen(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DeviceLabel(look.label + if (look.sized) " · " + size.label else "", color = c.screenDim, modifier = Modifier.weight(1f))
                            Led(bmp != null && !failed, size = 6.dp)
                            Spacer(Modifier.width(6.dp))
                            DeviceLabel(if (bmp == null) "Drawing" else "Live", color = c.accent)
                        }
                        VSpace(10)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 280.dp)
                                // Swipe the picture for the next or the one before.
                                .pointerInput(look) {
                                    var drag = 0f
                                    detectHorizontalDragGestures(
                                        onDragStart = { drag = 0f },
                                        onHorizontalDrag = { ch, d -> ch.consume(); drag += d },
                                        onDragEnd = {
                                            val i = looks.indexOf(look).coerceAtLeast(0)
                                            if (drag < -60f) pick(looks[(i + 1) % looks.size])
                                            else if (drag > 60f) pick(looks[(i - 1 + looks.size) % looks.size])
                                        },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            AnimatedContent(
                                bmp,
                                transitionSpec = {
                                    (slideInHorizontally(spring(dampingRatio = 0.8f, stiffness = 380f)) { it / 3 * dir } + fadeIn(tween(200)) +
                                        scaleIn(spring(dampingRatio = 0.7f, stiffness = 380f), initialScale = 0.94f))
                                        .togetherWith(slideOutHorizontally(tween(180)) { -it / 4 * dir } + fadeOut(tween(140)))
                                        .using(SizeTransform(clip = false))
                                },
                                contentAlignment = Alignment.Center,
                                label = "quote",
                            ) { b ->
                                if (b == null && failed) {
                                    Text("Couldn't draw this one. Try another look or shape.", style = CpType.small, color = c.screenDim)
                                } else if (b == null) {
                                    Box(Modifier.fillMaxWidth(0.8f).aspectRatio(0.8f), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(Modifier.size(24.dp), color = c.accent, strokeWidth = 2.dp)
                                    }
                                } else {
                                    Image(
                                        b.asImageBitmap(),
                                        contentDescription = "The passage as a picture",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxWidth(if (b.height > b.width * 1.5f) 0.58f else 0.84f)
                                            .aspectRatio(b.width.toFloat() / b.height)
                                            .graphicsLayer { rotationZ = -1.2f }
                                            .shadow(18.dp, RoundedCornerShape(10.dp))
                                            .clip(RoundedCornerShape(10.dp))
                                            .then(if (look == QuoteCard.Look.PAGE) Modifier.bouncyClickable(RoundedCornerShape(10.dp), pressedScale = 0.98f, onClickLabel = "Open the studio") { studio = true } else Modifier),
                                    )
                                }
                            }
                        }
                        VSpace(10)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DeviceLabel("Swipe for another look", color = c.screenDim, modifier = Modifier.weight(1f))
                            for (l in looks) { Led(l == look, size = 5.dp); Spacer(Modifier.width(4.dp)) }
                        }
                    }
                    if (look.sized) {
                        VSpace(10)
                        // Post, square or story, each with a little frame of its shape.
                        Segmented(QuoteCard.Size.entries, size, { it.label }, { size = it }) { z, on ->
                            val h = when (z) { QuoteCard.Size.POST -> 12.dp; QuoteCard.Size.SQUARE -> 10.dp; QuoteCard.Size.STORY -> 15.dp }
                            val w = when (z) { QuoteCard.Size.POST -> 10.dp; QuoteCard.Size.SQUARE -> 10.dp; QuoteCard.Size.STORY -> 8.dp }
                            Box(Modifier.size(w, h).border(1.5.dp, if (on) Color.White else c.textMid, RoundedCornerShape(2.dp)))
                        }
                    }
                }
            }
            if (failed && bmp != null) { VSpace(6); AnimatedQuiet("Couldn't draw that one; this is the last picture. Try another look or shape.", color = c.faint) }
            VSpace(14)
            // The Book photo studio, offered on its own above the looks, with this very passage in it.
            run {
                BookPhotoTile(shotContent, Modifier.riseIn(2), onOpen = { studio = true }, onFromPhoto = onFromPhoto)
                VSpace(14)
            }
            // The looks as a row of keys, each with a swatch of itself; the chosen one stays pressed in, lit.
            val lookScroll = rememberScrollState()
            val keyStep = with(LocalDensity.current) { 84.dp.toPx() }
            LaunchedEffect(look) { lookScroll.animateScrollTo(((look.ordinal - 1).coerceAtLeast(0) * keyStep).toInt()) }
            Row(Modifier.riseIn(2).fillMaxWidth().horizontalScroll(lookScroll).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (l in looks) LookKey(l, l == look) { pick(l) }
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
                    shape = CpShapes.control,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.hairline, focusedTextColor = c.text, unfocusedTextColor = c.text,
                        focusedContainerColor = c.field, unfocusedContainerColor = c.field, focusedLabelColor = c.accentText, unfocusedLabelColor = c.textMid, cursorColor = c.accent),
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
            // Straight into a new Threads post, picture and words: the dark key, with a little burst.
            var burst by remember { mutableStateOf(0) }
            Box {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton("Post to Threads", {
                bmp?.let { b ->
                    val how = runCatching { QuoteCard.postToThreads(context, b, plain.take(500)) }.getOrNull()
                    if (how == "app" || how == "web") burst++
                    said = when (how) {
                        "app" -> "Opened in Threads with the picture. The words are copied too, if you need to paste them."
                        "web" -> "Threads isn't installed, so it opened in the browser with the words (a picture can't go that way; save it and add it there)."
                        else -> "Couldn't open Threads."
                    }
                }
            }, Modifier.weight(1.4f), look = KeyKind.INK, enabled = bmp != null)
            BigButton("Post to X", {
                bmp?.let { b ->
                    val how = runCatching { XPost.pictures(context, listOf(b), plain) }.getOrNull()
                    if (how == "app" || how == "web") burst++
                    said = XPost.said(how)
                }
            }, Modifier.weight(1f), look = KeyKind.INK, enabled = bmp != null)
            }
            Confetti(burst, Modifier.matchParentSize())
            }
            // The passage as a short video, made in the video studio.
            VSpace(8)
            var filming by remember { mutableStateOf(false) }
            var filmCover by remember { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(book?.coverUrl) {
                filmCover = withContext(Dispatchers.IO) { book?.coverUrl?.let { u -> runCatching { CoverCache.load(context, u, 1200) }.getOrNull() } }
            }
            BigButton("\u25B6 Make it a video", { filming = true }, filled = false)
            if (filming) VideoStudio(QuoteVideo.Input(content.text, content.note, content.title, content.author, content.where, filmCover), plain) { filming = false }
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

/** One look as a key: a little swatch of what it draws, its name, and the dock's orange light under it when it's the one. */
@Composable
private fun LookKey(l: QuoteCard.Look, on: Boolean, onClick: () -> Unit) {
    val c = Cp.colors
    val sink by androidx.compose.animation.core.animateDpAsState(if (on) 2.dp else 0.dp, androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 700f), label = "lookSink")
    Key(onClick, Modifier.size(width = 76.dp, height = 88.dp).offset(y = sink), shape = RoundedCornerShape(14.dp), ledge = if (on) 1.dp else 3.dp, role = Role.RadioButton, label = l.label) {
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(6.dp)).drawBehind { lookSwatch(l) })
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(l.label, style = CpType.small, color = if (on) c.text else c.textMid, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                // The chosen one is lit like the dock's tabs.
                GlowBar(on)
            }
        }
    }
}

/** A tiny impression of each look, for its key. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.lookSwatch(l: QuoteCard.Look) {
    val w = size.width; val h = size.height
    fun lines(color: Color, x0: Float, y0: Float, n: Int, gap: Float, width: Float = w * 0.6f) {
        for (i in 0 until n) drawRect(color, Offset(x0, y0 + i * gap), Size(width * (if (i == n - 1) 0.6f else 1f), 2.5f))
    }
    when (l) {
        QuoteCard.Look.SPOTLIGHT -> { drawRect(Color(0xFF1C1B19)); drawCircle(Color(0x33F1D36B), h * 0.7f, Offset(w * 0.5f, h * 0.45f)); lines(Color(0xFFF4EFE6), w * 0.2f, h * 0.35f, 3, 6f) }
        QuoteCard.Look.HIGHLIGHTER -> { drawRect(Color.White); drawRect(Color(0xFFFFF176), Offset(w * 0.12f, h * 0.38f), Size(w * 0.7f, h * 0.24f)); lines(Color(0xFF333333), w * 0.15f, h * 0.46f, 1, 6f) }
        QuoteCard.Look.SPINE -> { drawRect(Color(0xFFF4EEE2)); drawRect(Color(0xFF7A3524), size = Size(w * 0.22f, h)); lines(Color(0xFF3A2A20), w * 0.32f, h * 0.3f, 4, 6f) }
        QuoteCard.Look.PAGE -> { drawRect(Color(0xFF2A211A)); drawRect(Color(0xFFEDE6D8), Offset(w * 0.15f, h * 0.12f), Size(w * 0.7f, h * 0.8f)); drawRect(Color(0x55FF7A9C), Offset(w * 0.22f, h * 0.5f), Size(w * 0.5f, 4f)); lines(Color(0xFF6B6255), w * 0.22f, h * 0.25f, 3, 5f, w * 0.55f) }
        QuoteCard.Look.EDITORIAL -> { drawRect(Color(0xFFF2EFE8)); drawRect(Color(0xFF111111), Offset(w * 0.12f, h * 0.22f), Size(w * 0.5f, 5f)); lines(Color(0xFF555555), w * 0.12f, h * 0.48f, 3, 5f) }
        QuoteCard.Look.TYPEWRITER -> { drawRect(Color(0xFFEDE3CC)); lines(Color(0xFF2B2B2B), w * 0.14f, h * 0.25f, 5, 5f, w * 0.7f) }
        QuoteCard.Look.INDEX_CARD -> { drawRect(Color(0xFFFFFDF6)); drawRect(Color(0xFFE57373), Offset(0f, h * 0.24f), Size(w, 1.5f)); for (i in 1..4) drawRect(Color(0xFF9EC5E8), Offset(0f, h * 0.24f + i * h * 0.17f), Size(w, 1f)) }
        QuoteCard.Look.WORDPRINT -> { drawRect(Color(0xFF14213D)); for (i in 0 until 24) { val a = i / 24f * 6.283f; val r = h * (0.18f + (i * 37 % 11) / 40f); drawLine(Color(0xFFFFC46B), Offset(w / 2, h / 2), Offset(w / 2 + kotlin.math.cos(a) * r, h / 2 + kotlin.math.sin(a) * r), 1.5f) } }
        QuoteCard.Look.SYSTEM -> { drawRect(Color(0xFF0B1630)); drawRect(Color(0xFF44AADD), Offset(3f, 3f), Size(w - 6f, h - 6f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f)); drawRect(Color(0xFF44AADD), Offset(w * 0.15f, h * 0.7f), Size(w * 0.5f, 3f)); lines(Color(0xFFBFE6FF), w * 0.15f, h * 0.3f, 2, 6f) }
        QuoteCard.Look.BOOK -> { drawRect(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFFFFCF9C), Color(0xFFFF9A6B)))); drawRect(Color(0xFF3A2A40), Offset(w * 0.12f, h * 0.15f), Size(w * 0.22f, h * 0.7f)); lines(Color(0xFF2A1A10), w * 0.42f, h * 0.3f, 3, 6f, w * 0.45f) }
    }
}

/**
 * The way into the Book photo studio: a small live preview of this passage as a photo of a real
 * book, tilted on the tile, with what it is beside it. Tapping anywhere opens the studio.
 */
@Composable
private fun BookPhotoTile(content: BookShot.Content, modifier: Modifier, onOpen: () -> Unit, onFromPhoto: (() -> Unit)?) {
    val c = Cp.colors
    val context = LocalContext.current
    // Drawn once in the background, small, with the studio's last scene (no grain or blur, to be quick).
    val thumb by androidx.compose.runtime.produceState<Bitmap?>(null, content.text, content.note) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val o = BookShot.saved(context).let { it.copy(filmGrain = 0f, focus = 0f, format = BookShot.Format.POST) }
                val full = BookShot.render(context, content, o)
                val w = 300
                Bitmap.createScaledBitmap(full, w, (w.toFloat() * full.height / full.width).toInt().coerceAtLeast(1), true).also { if (it !== full) full.recycle() }
            }.getOrNull()
        }
    }
    val wait = androidx.compose.animation.core.rememberInfiniteTransition(label = "thumbWait")
    val shimmer by wait.animateFloat(0.3f, 0.7f, androidx.compose.animation.core.infiniteRepeatable(tween(800), androidx.compose.animation.core.RepeatMode.Reverse), label = "thumbA")
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .shadow(14.dp, RoundedCornerShape(24.dp), ambientColor = c.shade, spotColor = c.shade)
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(lerpColor(c.surface, c.accent, 0.10f), c.surface)))
                .border(1.dp, c.accent.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
                .bouncyClickable(RoundedCornerShape(24.dp), pressedScale = 0.98f, onClickLabel = "Open the Book photo studio") { onOpen() }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The preview, like a print laid on the tile.
            Box(
                Modifier.size(width = 86.dp, height = 106.dp)
                    .graphicsLayer { rotationZ = -4f }
                    .shadow(8.dp, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.track.copy(alpha = if (thumb == null) shimmer else 1f)),
            ) {
                androidx.compose.animation.AnimatedVisibility(thumb != null, enter = fadeIn(tween(400)) + scaleIn(initialScale = 1.08f)) {
                    thumb?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Make it a photo of the book", style = CpType.bodyStrong, color = c.text)
                Spacer(Modifier.height(3.dp))
                Text("Your passage on a real-looking page: light, desk, paper, type and your own pen marks.", style = CpType.small, color = c.quiet, maxLines = 3)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Open the studio", style = CpType.label, color = c.accentText)
                    Spacer(Modifier.width(4.dp))
                    LucideIcon(R.drawable.lucide_chevron_right, Modifier.size(14.dp), c.accentText)
                }
            }
        }
        onFromPhoto?.let { go ->
            VSpace(8)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                PillButton("\uD83D\uDCF7 Or start from a photo of the page", go)
            }
        }
    }
}
