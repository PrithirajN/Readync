package io.github.prithirajn.trackersync.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.prithirajn.trackersync.R
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.sync.HighlightPosition
import io.github.prithirajn.trackersync.sync.Highlights
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import io.github.prithirajn.trackersync.ui.theme.CpType

/** Every highlight and note in the library, kept fresh as they change. */
@Composable
fun rememberAllHighlights(state: Snapshot): List<Highlights.Item> =
    remember(state.cpClips, state.paperClips, state.kindleClips, state.libbyClips, state.moonClips, state.historyClips, state.koClips, state.clipEdits, state.clipHidden, state.sentClippings, state.resend, state.books) {
        Highlights.all(state)
    }

/** Books with highlights, the one highlighted in most recently first, with how many each. */
fun highlightBooks(all: List<Highlights.Item>): List<Pair<String, Int>> =
    all.groupBy { it.clip.document }.entries
        .sortedByDescending { e -> e.value.maxOf { it.clip.createdAt } }
        .map { it.key to it.value.size }

/** Sends every highlight out as Markdown, book by book. */
fun exportHighlights(context: android.content.Context, state: Snapshot, all: List<Highlights.Item>) {
    val md = buildString {
        for ((doc, _) in highlightBooks(all)) {
            val b = state.books[doc] ?: continue
            append(Highlights.toMarkdown(b, all.filter { it.clip.document == doc })).append("\n\n")
        }
    }.trim()
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, "My highlights")
        putExtra(android.content.Intent.EXTRA_TEXT, md)
    }
    context.startActivity(android.content.Intent.createChooser(send, "Export highlights"))
}

/**
 * One passage for today on the Library page: the same all day, ↻ for another. Tapping the
 * passage opens it; "All N" goes to the Highlights shelf.
 */
@Composable
fun DailyHighlightCard(
    state: Snapshot,
    all: List<Highlights.Item>,
    onOpenBook: (String) -> Unit,
    onOpen: (Highlights.Item) -> Unit,
    onShare: (Highlights.Item) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
    /** Highlights waiting to be checked before sending: while there are any, the card asks for them instead. */
    toCheck: List<Highlights.Item> = emptyList(),
    onCheck: () -> Unit = {},
) {
    val c = Cp.colors
    if (toCheck.isNotEmpty()) { ToCheckCard(state, toCheck, onCheck, modifier); return }
    var shuffle by remember { mutableStateOf(0) }
    val today = remember { java.time.LocalDate.now() }
    val daily = remember(all, shuffle) { Highlights.ofTheDay(all, today, shuffle) } ?: return
    CpCard(modifier) {
        Column(Modifier.padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Highlight of the day", Modifier.weight(1f))
                if (all.size > 1) {
                    Box(
                        Modifier
                            .clip(CpShapes.pill)
                            .bouncyClickable(CpShapes.pill, pressedScale = 0.9f, role = Role.Button, onClickLabel = "Show another") { shuffle++ }
                            .padding(8.dp),
                    ) { LucideIcon(R.drawable.lucide_refresh_cw, Modifier.size(16.dp), c.quiet) }
                }
                Box(
                    Modifier
                        .clip(CpShapes.pill)
                        .bouncyClickable(CpShapes.pill, pressedScale = 0.9f, role = Role.Button, onClickLabel = "Share as a picture") { onShare(daily) }
                        .padding(8.dp),
                ) { LucideIcon(R.drawable.lucide_upload, Modifier.size(16.dp), c.quiet) }
            }
            VSpace(6)
            AnimatedContent(daily, transitionSpec = { fadeIn(Motion.medium()) togetherWith fadeOut(Motion.fast()) }, label = "daily") { d ->
                Column(Modifier.padding(end = 8.dp)) {
                    Text(
                        io.github.prithirajn.trackersync.trackers.quoted(d.clip.text),
                        style = CpType.body.copy(fontFamily = CpType.section.fontFamily, fontStyle = FontStyle.Italic, fontSize = CpType.body.fontSize * 1.15f, lineHeight = CpType.body.lineHeight * 1.15f),
                        color = c.text,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.bouncyClickable(RectangleShape, pressedScale = 0.99f, role = Role.Button, onClickLabel = "Open highlight") { onOpen(d) },
                    )
                    VSpace(10)
                    val b = state.books[d.clip.document]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier
                                .weight(1f)
                                .bouncyClickable(CpShapes.control, pressedScale = 0.98f, role = Role.Button, onClickLabel = "Open the book") { onOpenBook(d.clip.document) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BookCover(b?.coverUrl, b?.displayTitle ?: "", 22.dp, 33.dp)
                            HSpace(10)
                            Text(
                                listOfNotNull(b?.displayTitle, b?.author).joinToString(" · "),
                                style = CpType.small, color = c.quiet, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        HSpace(8)
                        // Where all your highlights open (they're not a shelf tab).
                        Row(
                            Modifier
                                .clip(CpShapes.pill)
                                .background(c.accentTint)
                                .bouncyClickable(CpShapes.pill, pressedScale = 0.92f, role = Role.Button, onClickLabel = "See all highlights", onClick = onSeeAll)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LucideIcon(R.drawable.lucide_highlighter, Modifier.size(13.dp), c.accentText)
                            HSpace(5)
                            Text("All ${all.size}", style = CpType.small, color = c.accentText)
                        }
                    }
                }
            }
        }
    }
}

/**
 * In the Highlight of the day's place while highlights wait to be checked: the newest one waiting,
 * its book, and the way to check them all (they go to your trackers once you have).
 */
@Composable
private fun ToCheckCard(state: Snapshot, toCheck: List<Highlights.Item>, onCheck: () -> Unit, modifier: Modifier) {
    val c = Cp.colors
    val first = toCheck.first()
    val b = state.books[first.clip.document]
    CpCard(modifier) {
        Column(
            Modifier
                .bouncyClickable(CpShapes.card, pressedScale = 0.99f, role = Role.Button, onClickLabel = "Check highlights", onClick = onCheck)
                .padding(start = 18.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Check before sending", Modifier.weight(1f))
                Text("${toCheck.size} waiting", style = CpType.small, color = c.accentText)
            }
            VSpace(6)
            Text(
                io.github.prithirajn.trackersync.trackers.quoted(first.clip.text),
                style = CpType.body.copy(fontFamily = CpType.section.fontFamily, fontStyle = FontStyle.Italic, fontSize = CpType.body.fontSize * 1.15f, lineHeight = CpType.body.lineHeight * 1.15f),
                color = c.text, maxLines = 4, overflow = TextOverflow.Ellipsis,
            )
            VSpace(10)
            Row(verticalAlignment = Alignment.CenterVertically) {
                BookCover(b?.coverUrl, b?.displayTitle ?: "", 22.dp, 33.dp)
                HSpace(10)
                Text(
                    if (toCheck.size == 1) listOfNotNull(b?.displayTitle, b?.author).joinToString(" · ")
                    else "${b?.displayTitle?.let { QuoteCard.shortTitle(it) } ?: ""} and ${toCheck.size - 1} more",
                    style = CpType.small, color = c.quiet, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                HSpace(8)
                PillButton("Review ${toCheck.size}", onCheck, outlined = false)
            }
        }
    }
}

/**
 * The Highlights shelf in the Library's list: book filter chips, then highlights grouped under
 * each book (tap the book to open it, a highlight to edit it, the arrow to share a picture).
 */
fun LazyListScope.highlightShelf(
    state: Snapshot,
    shown: List<Highlights.Item>,
    books: List<Pair<String, Int>>,
    total: Int,
    bookFilter: String?,
    query: String,
    onFilter: (String?) -> Unit,
    onOpenBook: (String) -> Unit,
    onOpen: (Highlights.Item) -> Unit,
    onShare: (Highlights.Item) -> Unit,
) {
    if (books.size > 1) {
        item(key = "clip-filter") {
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterPill("All", total, bookFilter == null) { onFilter(null) }
                for ((doc, n) in books) {
                    val title = state.books[doc]?.displayTitle ?: continue
                    FilterPill(if (title.length > 26) title.take(24).trimEnd() + "\u2026" else title, n, bookFilter == doc) {
                        onFilter(if (bookFilter == doc) null else doc)
                    }
                }
            }
        }
    }
    if (shown.isEmpty()) {
        item(key = "clip-empty") {
            val c = Cp.colors
            Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Eyebrow(if (query.isNotBlank()) "No matches" else "Nothing yet")
                VSpace(4)
                Text(
                    if (query.isNotBlank()) "No highlight has \u201C${query.trim()}\u201D" else "No highlights yet",
                    style = CpType.section, color = c.text,
                )
                VSpace(6)
                Quiet(
                    if (query.isNotBlank()) "Search looks in passages, notes, chapters, titles and authors."
                    else "Highlights from CrossPoint, Kindle and Libby land here on their own. For a paper book, open it and use Snap page.",
                )
            }
        }
        return
    }
    var lastDoc: String? = null
    for (h in shown) {
        val newBook = h.clip.document != lastDoc
        lastDoc = h.clip.document
        if (newBook) {
            item(key = "clip-book-${h.clip.document}-${h.clip.id}") {
                val c = Cp.colors
                val b = state.books[h.clip.document]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, top = 12.dp)
                        .bouncyClickable(CpShapes.control, pressedScale = 0.98f, role = Role.Button, onClickLabel = "Open the book") { onOpenBook(h.clip.document) }
                        .padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BookCover(b?.coverUrl, b?.displayTitle ?: "", 32.dp, 48.dp)
                    HSpace(12)
                    Column(Modifier.weight(1f)) {
                        Text(b?.displayTitle ?: "", style = CpType.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        b?.author?.let { Quiet(it, maxLines = 1) }
                    }
                    LucideIcon(R.drawable.lucide_chevron_right, Modifier.size(16.dp), c.faint)
                }
            }
        }
        item(key = "clip-${h.clip.id}") {
            HighlightRow(h, onOpen = { onOpen(h) }, onShare = { onShare(h) })
        }
    }
}

@Composable
private fun FilterPill(label: String, count: Int, on: Boolean, onClick: () -> Unit) {
    val c = Cp.colors
    Row(
        Modifier
            .clip(CpShapes.pill)
            .background(if (on) c.accentTint else c.divider)
            .bouncyClickable(CpShapes.pill, pressedScale = 0.94f, role = Role.Tab, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = CpType.label, color = if (on) c.accentText else c.textMid, maxLines = 1)
        HSpace(6)
        Text(count.toString(), style = CpType.small, color = if (on) c.accentText else c.faint)
    }
}

@Composable
private fun HighlightRow(item: Highlights.Item, onOpen: () -> Unit, onShare: () -> Unit) {
    val c = Cp.colors
    Row(
        Modifier
            .fillMaxWidth()
            .bouncyClickable(RectangleShape, pressedScale = 0.99f, role = Role.Button, onClickLabel = "Edit highlight", onClick = onOpen)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(width = 3.dp, height = 40.dp).background(if (item.edited) c.accent else c.accentRing, CpShapes.pill))
        HSpace(12)
        Column(Modifier.weight(1f)) {
            Text(item.clip.text, style = CpType.body.copy(fontFamily = CpType.section.fontFamily), color = c.text, maxLines = 6, overflow = TextOverflow.Ellipsis)
            item.clip.note?.let {
                VSpace(4)
                Text("Note: $it", style = CpType.small, color = c.textMid, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            VSpace(4)
            val where = listOfNotNull(
                item.clip.chapter?.takeIf { it.isNotBlank() },
                HighlightPosition.label(item.clip),
                java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(item.clip.createdAt * 1000)),
            ).joinToString(" · ")
            Quiet(where, color = c.faint, maxLines = 1)
        }
        Box(
            Modifier
                .clip(CpShapes.pill)
                .bouncyClickable(CpShapes.pill, pressedScale = 0.9f, role = Role.Button, onClickLabel = "Share as a picture", onClick = onShare)
                .padding(10.dp),
        ) { LucideIcon(R.drawable.lucide_upload, Modifier.size(18.dp), c.quiet) }
    }
}
