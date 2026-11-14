package io.github.prithirajn.readync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.prithirajn.readync.data.Book
import io.github.prithirajn.readync.source.BookCopies
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.CpType

/**
 * On a book's page: the app's own copy of it (for the lines around its quotes), live. Being looked
 * for, downloading (how far), kept (from where, how big, when), or why it couldn't be had and when
 * it was last tried, with the button to try again or remove it. Shown once there's something to say
 * (a copy, a try, or a book you're reading with copies turned on).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CopyCard(vm: AppViewModel, book: Book, alwaysShow: Boolean = false) {
    if (book.isMangaOrComic) return
    val c = Cp.colors
    val context = LocalContext.current.applicationContext
    val copies by BookCopies.copies.collectAsState()
    val statuses by BookCopies.status.collectAsState()
    val busy by BookCopies.busy.collectAsState()
    val copy = copies[book.document]
    val live = statuses[book.document]
    val failed = if (copy == null && book.document !in busy) BookCopies.lastFailure(context, book.document) else null
    val reading = book.status == io.github.prithirajn.readync.data.ReadStatus.READING
    if (!alwaysShow && copy == null && live == null && failed == null && !(reading && BookCopies.auto(context))) return

    VSpace(16)
    CpCard {
        Column(Modifier.padding(16.dp)) {
            Text("Copy for quotes", style = CpType.bodyStrong, color = c.text)
            when {
                book.document in busy || live?.state == BookCopies.Status.LOOKING || live?.state == BookCopies.Status.DOWNLOADING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), color = c.accent, strokeWidth = 2.dp)
                        HSpace(8)
                        val pct = live?.takeIf { it.total > 0 }?.let { " · ${(it.done * 100 / it.total).coerceIn(0, 100)}%" } ?: ""
                        Quiet(
                            when (live?.state) {
                                BookCopies.Status.DOWNLOADING -> "Downloading " + (live.message?.replaceFirstChar { it.lowercase() } ?: "") + pct
                                else -> live?.message ?: "Looking on your catalogs"
                            }
                        )
                    }
                }
                copy != null -> {
                    Quiet("Kept in the app · from ${serverName(copy.server)} · ${sizeOf(copy.size)} · ${ago(copy.at * 1000)}")
                    // How it was checked to be this book (title, author, length, your highlights in it).
                    copy.checked?.let { ch ->
                        if (ch.startsWith("⚠")) Quiet(ch + ". Remove it and look again, or keep it if you know it's right.", color = c.errorText)
                        else Quiet("✓ Checked: $ch", color = c.good)
                    }
                    Quiet("Quote Studio uses it for the lines around your passages.", color = c.faint)
                    VSpace(10)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (copy.checked == null || copy.checked.startsWith("⚠")) PillButton("Check it's this book", { vm.recheckCopy(book.document) })
                        PillButton("Remove the copy", { vm.toggleBookCopy(book.document) })
                    }
                }
                failed != null -> {
                    Quiet("Couldn't get one: ${failed.message ?: "not found"}" + (failed.at.takeIf { it > 0 }?.let { " · ${ago(it * 1000)}" } ?: ""), color = c.errorText)
                    if (reading && BookCopies.auto(context)) Quiet("Tried again by itself in a few days.", color = c.faint)
                    VSpace(10)
                    PillButton("Try now", { vm.toggleBookCopy(book.document) })
                }
                else -> {
                    Quiet("Not yet: a sync looks for it on your catalogs (one book at a time).")
                    VSpace(10)
                    PillButton("Look now", { vm.toggleBookCopy(book.document) })
                }
            }
        }
    }
}

private fun sizeOf(bytes: Long): String =
    if (bytes >= 1_000_000) String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0) else "${(bytes / 1000).coerceAtLeast(1)} KB"

/**
 * A catalog's name to show: its own name without a link written into it (calibre's
 * "calibre Library [(http://…)](http://…/opds)"), else just the address's host.
 */
internal fun serverName(raw: String): String {
    val noLinks = raw.replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1").replace(Regex("""\(?https?://[^\s)]+\)?"""), "")
        .replace(Regex("""[\[\]()]"""), "").replace(Regex("\\s+"), " ").trim()
    if (noLinks.isNotEmpty()) return noLinks
    return Regex("""https?://([^/:\s]+)""").find(raw)?.groupValues?.get(1) ?: raw
}
