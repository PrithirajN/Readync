package io.github.prithirajn.readync.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
import io.github.prithirajn.readync.R
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.sync.HighlightPosition
import io.github.prithirajn.readync.sync.Highlights
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.CpShapes
import io.github.prithirajn.readync.ui.theme.CpType

/** Every highlight and note in the library, kept fresh as they change. */
@Composable
fun rememberAllHighlights(state: Snapshot): List<Highlights.Item> {
    // Gathered off the screen's thread (every highlight from every source, tidied: slow with many),
    // so the Library shows up at once; the last ones gathered stand in for the moment until then.
    val key = listOf<Any?>(state.cpClips, state.paperClips, state.kindleClips, state.libbyClips, state.hooplaClips, state.everandClips, state.moonClips, state.historyClips, state.koClips, state.clipEdits, state.clipHidden, state.sentClippings, state.resend, state.books)
    val ready = AllHighlightsCache.get(key)
    return androidx.compose.runtime.produceState(ready ?: AllHighlightsCache.last, *key.toTypedArray()) {
        if (ready != null) { value = ready; return@produceState }
        val all = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { Highlights.all(state) }.getOrNull() } ?: return@produceState
        AllHighlightsCache.put(key, all)
        value = all
    }.value
}

/** The highlights last gathered, with what they were gathered from (compared by identity, not contents). */
private object AllHighlightsCache {
    @Volatile private var key: List<Any?>? = null
    @Volatile var last: List<Highlights.Item> = emptyList()
        private set

    fun get(k: List<Any?>): List<Highlights.Item>? {
        val have = key ?: return null
        return if (have.size == k.size && have.indices.all { have[it] === k[it] }) last else null
    }

    fun put(k: List<Any?>, v: List<Highlights.Item>) { key = k; last = v }
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
    /** The "All N" way to every highlight (left out on the Highlights tab itself). */
    showAll: Boolean = true,
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
                        io.github.prithirajn.readync.trackers.quoted(d.clip.text),
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
                        // Where all your highlights open.
                        if (showAll) Row(
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
                io.github.prithirajn.readync.trackers.quoted(first.clip.text),
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
        item(key = "clip-filter") { BookPicker(state, books, total, bookFilter, onFilter) }
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
                val ink = rememberBookInk(b?.coverUrl)
                val count = books.firstOrNull { it.first == h.clip.document }?.second
                // The book as a band of its own ink: cover, title, how many passages.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 2.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(ink.copy(alpha = 0.13f))
                        .bouncyClickable(RoundedCornerShape(14.dp), pressedScale = 0.98f, role = Role.Button, onClickLabel = "Open the book") { onOpenBook(h.clip.document) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.drawBehind { drawRoundRect(ink, topLeft = Offset(3.dp.toPx(), 3.dp.toPx()), size = size, cornerRadius = CornerRadius(4.dp.toPx())) }) {
                        BookCover(b?.coverUrl, b?.displayTitle ?: "", 34.dp, 51.dp)
                    }
                    HSpace(14)
                    Column(Modifier.weight(1f)) {
                        Text(b?.displayTitle ?: "", fontFamily = io.github.prithirajn.readync.ui.theme.DisplaySmall, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(b?.author, count?.let { "$it passage${if (it == 1) "" else "s"}" }).joinToString(" · "),
                            style = CpType.small.copy(fontStyle = FontStyle.Italic), color = c.textMid, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    LucideIcon(R.drawable.lucide_chevron_right, Modifier.size(16.dp), c.quiet)
                }
            }
        }
        item(key = "clip-${h.clip.id}") {
            HighlightRow(h, state.books[h.clip.document]?.coverUrl, onOpen = { onOpen(h) }, onShare = { onShare(h) })
        }
    }
}

/**
 * Which book's highlights: one line saying what's shown, opening a grid of covers (each with its
 * count) to pick from, instead of a long row of title pills.
 */
@Composable
private fun BookPicker(state: Snapshot, books: List<Pair<String, Int>>, total: Int, bookFilter: String?, onFilter: (String?) -> Unit) {
    val c = Cp.colors
    var open by remember { mutableStateOf(false) }
    val picked = bookFilter?.let { state.books[it] }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(CpShapes.control)
                .background(c.divider)
                .bouncyClickable(CpShapes.control, pressedScale = 0.98f, role = Role.Button, onClickLabel = "Choose a book") { open = !open }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (picked != null) {
                BookCover(picked.coverUrl, picked.displayTitle, 22.dp, 33.dp)
                HSpace(10)
                Column(Modifier.weight(1f)) {
                    Text(picked.displayTitle, style = CpType.label, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${books.firstOrNull { it.first == bookFilter }?.second ?: 0} of $total highlights", style = CpType.small, color = c.quiet)
                }
                Box(
                    Modifier.clip(CpShapes.pill).bouncyClickable(CpShapes.pill, pressedScale = 0.9f, role = Role.Button, onClickLabel = "Show every book") { onFilter(null); open = false }.padding(6.dp),
                ) { LucideIcon(R.drawable.lucide_x, Modifier.size(16.dp), c.quiet) }
            } else {
                // The newest few covers fanned out, then what's shown.
                Box(Modifier.size(width = (22 + 12 * (books.size.coerceAtMost(4) - 1)).dp, height = 33.dp)) {
                    books.take(4).forEachIndexed { i, (doc, _) ->
                        val b = state.books[doc]
                        Box(Modifier.padding(start = (12 * i).dp)) { BookCover(b?.coverUrl, b?.displayTitle ?: "", 22.dp, 33.dp) }
                    }
                }
                HSpace(10)
                Column(Modifier.weight(1f)) {
                    Text("All books", style = CpType.label, color = c.text)
                    Text("$total highlights from ${books.size} books · tap to pick one", style = CpType.small, color = c.quiet, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HSpace(6)
            LucideIcon(R.drawable.lucide_chevron_right, Modifier.size(16.dp).graphicsLayer { rotationZ = if (open) -90f else 90f }, c.quiet)
        }
        androidx.compose.animation.AnimatedVisibility(open) {
            // A wall of covers, four across, each with its count.
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (row in books.chunked(4)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for ((doc, n) in row) {
                            val b = state.books[doc]
                            val on = doc == bookFilter
                            Column(
                                Modifier.weight(1f)
                                    .bouncyClickable(RoundedCornerShape(8.dp), pressedScale = 0.94f, role = Role.Tab, onClickLabel = b?.displayTitle ?: "Book") {
                                        onFilter(if (on) null else doc); open = false
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box {
                                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
                                        BookCover(b?.coverUrl, b?.displayTitle ?: "", maxWidth, maxHeight)
                                    }
                                    Text(
                                        n.toString(), style = CpType.small, color = if (on) c.onAccent else c.text,
                                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(CpShapes.pill)
                                            .background(if (on) c.accent else c.page.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 1.dp),
                                    )
                                }
                                Text(
                                    b?.displayTitle?.let { QuoteCard.shortTitle(it) } ?: "", style = CpType.small, color = if (on) c.accentText else c.textMid,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp),
                                )
                            }
                        }
                        repeat(4 - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
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

/**
 * One passage, set like a pull quote: a large opening mark in the book's ink, the words in the
 * serif at reading size, any note of yours under a thin rule, and where it is (chapter, place, day)
 * as a quiet printed line. An edited passage keeps a filled mark.
 */
@Composable
private fun HighlightRow(item: Highlights.Item, coverUrl: String?, onOpen: () -> Unit, onShare: () -> Unit) {
    val c = Cp.colors
    val ink = rememberBookInk(coverUrl)
    Row(
        Modifier
            .fillMaxWidth()
            .bouncyClickable(RectangleShape, pressedScale = 0.99f, role = Role.Button, onClickLabel = "Edit highlight", onClick = onOpen)
            .padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            "\u201C", fontFamily = io.github.prithirajn.readync.ui.theme.Display, fontWeight = FontWeight.Black, fontSize = 40.sp, lineHeight = 40.sp,
            color = if (item.edited) ink else ink.copy(alpha = 0.75f), modifier = Modifier.padding(top = 0.dp).size(width = 22.dp, height = 34.dp),
        )
        HSpace(8)
        Column(Modifier.weight(1f)) {
            Text(
                item.clip.text.trim().removePrefix("\u2018").removePrefix("\u201C").removeSuffix("\u2019").removeSuffix("\u201D"),
                style = CpType.body.copy(fontFamily = CpType.section.fontFamily, fontSize = CpType.body.fontSize * 1.08f, lineHeight = CpType.body.lineHeight * 1.12f),
                color = c.text, maxLines = 7, overflow = TextOverflow.Ellipsis,
            )
            item.clip.note?.let {
                VSpace(6)
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 3.dp).size(width = 2.dp, height = 14.dp).background(ink.copy(alpha = 0.6f)))
                    HSpace(8)
                    Text(it, style = CpType.small.copy(fontStyle = FontStyle.Italic), color = c.textMid, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            VSpace(6)
            Row(verticalAlignment = Alignment.CenterVertically) {
                item.clip.chapter?.takeIf { it.isNotBlank() }?.let { ch ->
                    Text(
                        ch, style = CpType.mono.copy(fontSize = 11.sp), color = c.textMid, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(5.dp)).background(ink.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                    HSpace(8)
                }
                Text(
                    listOfNotNull(
                        HighlightPosition.label(item.clip),
                        java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(item.clip.createdAt * 1000)),
                    ).joinToString(" · "),
                    style = CpType.small, color = c.faint, maxLines = 1,
                )
            }
        }
        Box(
            Modifier
                .clip(CpShapes.pill)
                .bouncyClickable(CpShapes.pill, pressedScale = 0.9f, role = Role.Button, onClickLabel = "Share as a picture", onClick = onShare)
                .padding(10.dp),
        ) { LucideIcon(R.drawable.lucide_upload, Modifier.size(18.dp), c.quiet) }
    }
}
