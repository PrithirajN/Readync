package io.github.prithirajn.trackersync.ui

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import kotlinx.coroutines.withContext

/**
 * Share a highlight as a picture: a live preview, three looks (paper, night, the cover's own
 * colour), your note on or off, then share, copy or save it. Plain text is a tap away too.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    var withNote by remember { mutableStateOf(false) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    var showMark by remember { mutableStateOf(BookShot.markOn(context)) }
    var studio by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(pageText) }
    val where = remember(clip) {
        clip.chapter?.takeIf { it.isNotBlank() } ?: io.github.prithirajn.trackersync.sync.HighlightPosition.label(clip)
    }
    val content = QuoteCard.Content(
        text = text,
        note = note?.takeIf { withNote && it.isNotBlank() },
        title = book?.displayTitle ?: "A book",
        author = book?.author,
        where = where,
        percent = clip.percent,
        page = clip.pageIn(book?.pageCount),
    )
    var seed by remember { mutableStateOf<Int?>(null) }
    // The book photo: the scene as you last left it in the studio.
    val shotContent = BookShot.Content(
        text = content.text, author = content.author, title = content.title,
        chapter = clip.chapter?.takeIf { it.isNotBlank() }, page = content.page,
        others = others, note = content.note, coverColor = seed, sectors = sectors,
    ).let { sc ->
        val found = page?.let { io.github.prithirajn.trackersync.sync.PageContext.find(it, text) }
        if (found != null) sc.copy(before = found.before, after = found.after) else sc
    }
    LaunchedEffect(content, look, showMark, studio, page) {
        if (studio) return@LaunchedEffect
        BookShot.setMark(context, showMark)
        bmp = withContext(Dispatchers.IO) {
            val cover = book?.coverUrl?.let { runCatching { CoverCache.load(context, it, 1400) }.getOrNull() }
            seed = book?.coverUrl?.let { runCatching { CoverAccent.of(context, it) }.getOrNull() }
            withContext(Dispatchers.Default) {
                if (look == QuoteCard.Look.PAGE) BookShot.render(context, shotContent.copy(coverColor = seed), BookShot.saved(context), mark = showMark)
                else QuoteCard.render(context, content, look, cover, seed, mark = showMark)
            }
        }
    }
    val plain = buildString {
        append(io.github.prithirajn.trackersync.trackers.quoted(text))
        append("\n\n— ${book?.displayTitle ?: "A book"}")
        book?.author?.let { append(", $it") }
        content.note?.let { append("\n\nMy note: $it") }
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
            Text("Share this passage", style = CpType.section, color = c.text)
            VSpace(14)
            Box(
                Modifier.fillMaxWidth().heightIn(min = 280.dp),
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(bmp, animationSpec = Motion.medium(), label = "quote") { b ->
                    if (b == null) {
                        Box(Modifier.fillMaxWidth(0.8f).aspectRatio(0.8f), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), color = c.faint)
                        }
                    } else {
                        Image(
                            b.asImageBitmap(),
                            contentDescription = "The passage as a picture",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth(if (look == QuoteCard.Look.PAGE && b.height > b.width * 1.5f) 0.62f else 0.82f)
                                .aspectRatio(b.width.toFloat() / b.height)
                                .clip(CpShapes.card)
                                .border(1.dp, c.divider, CpShapes.card)
                                .then(if (look == QuoteCard.Look.PAGE) Modifier.bouncyClickable(CpShapes.card, pressedScale = 0.98f, onClickLabel = "Open the studio") { studio = true } else Modifier),
                        )
                    }
                }
            }
            VSpace(14)
            // The three looks, as a little segmented row.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
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
            if (!note.isNullOrBlank()) {
                VSpace(4)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Include my note", style = CpType.body, color = c.text, modifier = Modifier.weight(1f))
                    CpToggle(withNote, { withNote = it }, "Include my note")
                }
            }
            VSpace(16)
            BigButton("Share", { bmp?.let { QuoteCard.share(context, it, plain) } }, enabled = bmp != null)
            VSpace(8)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton("Copy image", {
                    bmp?.let { runCatching { QuoteCard.copy(context, it) }.onSuccess { tick(); said = "Copied. Paste it into any app that takes pictures." } }
                }, Modifier.weight(1f), filled = false, enabled = bmp != null)
                BigButton("Save image", {
                    bmp?.let {
                        val ok = runCatching { QuoteCard.save(context, it) }.getOrDefault(false)
                        said = if (ok) "Saved to Pictures › ${io.github.prithirajn.trackersync.Edition.name}." else "Couldn't save here; use Share and pick your gallery."
                        if (ok) tick()
                    }
                }, Modifier.weight(1f), filled = false, enabled = bmp != null)
            }
            said?.let { VSpace(8); AnimatedQuiet(it, color = c.faint) }
            VSpace(12)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PillButton("Share as text instead", {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, plain)
                    }
                    context.startActivity(android.content.Intent.createChooser(send, "Share quote"))
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
        )
    }
}
