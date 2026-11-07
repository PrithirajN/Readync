package io.github.prithirajn.trackersync.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.WebNovel
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpType

/**
 * A web novel's story on its site: status, chapters and words, its tags, a link to open it, and
 * whether you've caught up. [editing] shows the field for setting or changing the story's link.
 */
@Composable
fun WebNovelCard(vm: AppViewModel, book: Book, web: WebNovel?, grown: Boolean, editing: Boolean, onEditing: (Boolean) -> Unit) {
    val c = Cp.colors
    val context = LocalContext.current
    var link by remember(book.document) { mutableStateOf(web?.url ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // What the sites found for this book, looked for as soon as the card opens to pick or change.
    var found by remember(book.document) { mutableStateOf<List<io.github.prithirajn.trackersync.source.WebNovels.Found>?>(null) }
    var searching by remember(book.document) { mutableStateOf(false) }
    var pasting by remember(book.document) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(editing, book.document) {
        if (editing && found == null && !searching) {
            searching = true
            vm.webCandidates(book.document) { f -> found = f ?: emptyList(); searching = false }
        }
    }
    if (web == null && !editing) return
    VSpace(16)
    CpCard {
        Column(Modifier.padding(16.dp)) {
            Text(if (web?.fanfic == true) "Fan fiction" else "Web novel", style = CpType.bodyStrong, color = c.text)
            if (web != null) {
                Quiet(web.line())
                val tags = web.tags.ifEmpty { book.titleTags }
                if (tags.isNotEmpty()) Quiet(tags.take(8).joinToString(", "), color = c.faint)
                when {
                    web.ongoing && book.percent >= 98 -> Quiet("You're caught up with the latest chapter. It stays Reading on your trackers until the story is complete.", color = c.accentText)
                    web.ongoing -> Quiet("Ongoing: it isn't marked Finished when you reach the latest chapter.", color = c.faint)
                }
                if (grown) {
                    Quiet("It got longer while you read (new chapters), so its percentage is of the longer book now.", color = c.faint)
                }
                if (web.found && !editing) {
                    Quiet("Found by searching ${web.site}. Not this story? Tap Change.", color = c.faint)
                }
            } else {
                Quiet("Looked for on Royal Road, AO3, Scribble Hub and Wattpad. Pick the story; its details are used when adding it to a tracker, and an ongoing story isn't marked Finished when you catch up.")
            }
            if (editing) {
                VSpace(10)
                val list = found
                when {
                    searching || list == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), color = c.faint, strokeWidth = 2.dp)
                        HSpace(10)
                        Quiet("Searching Royal Road, AO3, Scribble Hub and Wattpad…")
                    }
                    list.isEmpty() -> {
                        // Say which sites couldn't be read, rather than that the story isn't there.
                        val trouble = io.github.prithirajn.trackersync.source.WebNovels.problems.entries
                            .filter { it.key in setOf("Royal Road", "AO3", "Scribble Hub", "Wattpad") }
                            .sortedBy { it.key }
                        if (trouble.isEmpty()) {
                            Quiet("None of the sites has a story by that title. Paste its link instead.", color = c.faint)
                        } else {
                            Quiet(
                                "Nothing found, but not every site could be searched: " +
                                    trouble.joinToString("; ") { "${it.key} (${it.value})" } +
                                    ". Search again in a minute, or paste the link.",
                                color = c.warn,
                            )
                        }
                    }
                    else -> CpCard {
                        list.take(6).forEachIndexed { i, f ->
                            if (i > 0) RowDivider()
                            val current = web?.url == f.url
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .bouncyClickable(androidx.compose.ui.graphics.RectangleShape, pressedScale = 0.99f, enabled = !busy && !current) {
                                        busy = true
                                        vm.setStoryLink(book.document, f.url) { e -> busy = false; error = e; if (e == null) onEditing(false) }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(f.title, style = CpType.bodyStrong, color = c.text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    Quiet(listOfNotNull(f.site, f.author?.let { "by $it" }).joinToString(" · "), maxLines = 1)
                                }
                                when {
                                    current -> Chip("Linked", color = c.accentText, background = c.accentTint)
                                    f.clear -> Chip("Best match", color = c.accentText, background = c.accentTint)
                                }
                            }
                        }
                    }
                }
                error?.let { VSpace(6); Text(it, style = CpType.small, color = c.errorText) }
                if (pasting || (list != null && list.isEmpty())) {
                    VSpace(10)
                    CpTextField(link, { link = it; error = null }, "Story link", placeholder = "royalroad.com/fiction/… or archiveofourown.org/works/…")
                }
                VSpace(10)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (pasting || (list != null && list.isEmpty())) {
                        PillButton(if (busy) "Reading the page…" else "Save link", {
                            if (busy) return@PillButton
                            busy = true
                            vm.setStoryLink(book.document, link) { e -> busy = false; error = e; if (e == null) onEditing(false) }
                        }, outlined = false)
                    } else {
                        PillButton("Paste a link instead", { pasting = true })
                    }
                    if (list != null && !searching) PillButton("Search again", {
                        searching = true
                        vm.webCandidates(book.document) { f -> found = f ?: emptyList(); searching = false }
                    })
                    PillButton("Cancel", { onEditing(false) })
                    if (web != null) PillButton("Not a web novel", { vm.setStoryLink(book.document, "") { onEditing(false) } }, danger = true)
                }
            } else if (web != null) {
                VSpace(10)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Open on ${web.site}", {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web.url))) }
                    })
                    PillButton("Change", { found = null; onEditing(true) })
                }
            }
        }
    }
}
