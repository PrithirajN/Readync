package io.github.prithirajn.trackersync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.prithirajn.trackersync.R
import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.source.BookCopies
import io.github.prithirajn.trackersync.sync.ManualBooks
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import io.github.prithirajn.trackersync.ui.theme.CpType

/**
 * Under the book on its page, one line of small labels instead of three cards: where its position
 * comes from, the app's copy of it, and whether you own it. Each says how things stand and opens
 * its full card in a sheet. A label only shows when it has something to say.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookChips(vm: AppViewModel, state: Snapshot, book: Book) {
    val context = LocalContext.current.applicationContext
    var open by remember(book.document) { mutableStateOf<String?>(null) }
    val P = io.github.prithirajn.trackersync.sync.Primary

    // Main source: when the book has more than one place its position can come from.
    val main = if (ManualBooks.isManual(book)) null else P.of(state, book.document)
    val mainLabel = main?.takeIf { P.candidates(state, book.document).size > 1 || P.ranking(state, book.document).size > 1 || book.document in state.pinnedPrimary }
        ?.let { "Main: ${P.name(it)}" + if (book.document in state.pinnedPrimary) " (yours)" else "" }

    // The copy for quotes: kept, coming, or why not.
    val copies by BookCopies.copies.collectAsState()
    val statuses by BookCopies.status.collectAsState()
    val busy by BookCopies.busy.collectAsState()
    val copy = copies[book.document]
    val live = statuses[book.document]
    val failed = if (copy == null && book.document !in busy) BookCopies.lastFailure(context, book.document) else null
    val reading = book.status == ReadStatus.READING
    val copyLabel = when {
        book.isMangaOrComic -> null
        book.document in busy || live?.state == BookCopies.Status.LOOKING || live?.state == BookCopies.Status.DOWNLOADING -> "Getting a copy…"
        copy != null -> "Copy kept · " + copyMb(copy.size)
        failed != null -> "No copy found"
        reading && BookCopies.auto(context) -> "Copy: on its way"
        else -> null
    }

    // Owned (where your collection is part of the app).
    val ownedLabel = if (CollectionHooks.available) CollectionHooks.ownedLabel(state, book) else null

    if (mainLabel == null && copyLabel == null && ownedLabel == null) return
    VSpace(12)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        mainLabel?.let { InfoChip(it, warn = false) { open = "main" } }
        copyLabel?.let { InfoChip(it, warn = failed != null && copy == null) { open = "copy" } }
        ownedLabel?.let { InfoChip(it, warn = false) { open = "owned" } }
    }
    when (open) {
        "main" -> PlainSheet("Main source", { open = null }) { VSpace(8); MainSourceCard(vm, state, book) }
        "copy" -> PlainSheet("Copy for quotes", { open = null }) { CopyCard(vm, book, alwaysShow = true) }
        "owned" -> PlainSheet("Your copy", { open = null }) { OwnedCard(vm, state, book, startOpen = true) }
    }
}

private fun copyMb(bytes: Long): String =
    if (bytes >= 1_000_000) String.format(java.util.Locale.US, "%.0f MB", bytes / 1_000_000.0) else "${(bytes / 1000).coerceAtLeast(1)} KB"

/** A small label you can tap: how something stands, with a chevron to open it. */
@Composable
private fun InfoChip(text: String, warn: Boolean, onClick: () -> Unit) {
    val c = Cp.colors
    Row(
        Modifier.clip(CpShapes.pill).background(c.divider.copy(alpha = 0.6f))
            .bouncyClickable(CpShapes.pill, pressedScale = 0.95f, onClickLabel = text) { onClick() }
            .padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = CpType.small, color = if (warn) c.errorText else c.textMid, maxLines = 1)
        Icon(painterResource(R.drawable.lucide_chevron_right), null, Modifier.padding(start = 2.dp).size(14.dp), tint = c.faint)
    }
}
