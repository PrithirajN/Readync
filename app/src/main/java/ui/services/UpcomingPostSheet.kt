package io.github.prithirajn.readync.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.sync.Highlights
import io.github.prithirajn.readync.sync.XQueue
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.CpType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One post on its way, opened from "next up": when and where it goes, a look at it as it will go
 * up (its cards, its words), every passage in it, and what you can do: edit a passage, leave one
 * out, post it now, or not post it at all. For X, every post waiting, each with its words to change
 * and its own "post now" (for when the day's notification went by).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpcomingPostSheet(vm: AppViewModel, state: Snapshot, u: UpcomingPosts.Upcoming, onClose: () -> Unit) {
    val c = Cp.colors
    val context = LocalContext.current.applicationContext
    val ref = u.ref
    var said by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Taken out here, so the sheet follows at once (the queue itself is read again behind it).
    var gone by remember { mutableStateOf(setOf<String>()) }
    var editing by remember { mutableStateOf<Highlights.Item?>(null) }
    editing?.let { EditHighlightSheet(vm, state, it) { editing = null } }

    val exact = remember(u.at) {
        java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, h:mm a").format(java.time.Instant.ofEpochSecond(u.at).atZone(java.time.ZoneId.systemDefault())).replace("AM", "am").replace("PM", "pm")
    }
    FlowSheet("To ${u.where}", onClose) {
        Text(
            (if (u.roughly) "Not before " else "") + exact + " · " + UpcomingPosts.whenText(u),
            style = CpType.mono, color = c.accentText,
        )
        VSpace(4)
        Text(u.what, style = CpType.body, color = c.textMid)
        when (ref) {
            is UpcomingPosts.Ref.X -> XQueuePosts(vm, ref.path) { said = it }
            is UpcomingPosts.Ref.Threads, is UpcomingPosts.Ref.Pro -> {
                val ids = when (ref) { is UpcomingPosts.Ref.Threads -> ref.ids; is UpcomingPosts.Ref.Pro -> ref.ids; else -> emptyList() }.filter { it !in gone }
                val items = remember(ids, state.clipEdits, state.cpClips) { Highlights.all(state).associateBy { it.clip.id }.let { m -> ids.mapNotNull { m[it] } } }
                // A look at it as it will go up: drawn off the main thread.
                val pictures by produceState<List<ImageBitmap>?>(null, ids, state.clipEdits) {
                    value = withContext(Dispatchers.IO) {
                        runCatching {
                            when (ref) {
                                is UpcomingPosts.Ref.Threads -> items.take(4).mapNotNull { h -> state.books[h.clip.document]?.let { b -> ThreadsAuto.preview(context, state, h, b, many = items.size > 1).asImageBitmap() } }
                                is UpcomingPosts.Ref.Pro -> ProPosts.preview(context, state, ref).mapNotNull { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                                else -> emptyList()
                            }
                        }.getOrDefault(emptyList())
                    }
                }
                VSpace(14)
                when {
                    pictures == null -> Quiet("Drawing it…", color = c.faint)
                    pictures!!.isNotEmpty() -> {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 8.dp)) {
                            itemsIndexed(pictures!!) { k, img ->
                                Image(
                                    img, "Card ${k + 1}",
                                    Modifier.staggerIn(k).liftable(maxTilt = 8f, lift = 1.04f).height(260.dp)
                                        .width(260.dp * (img.width.toFloat() / img.height.coerceAtLeast(1)))
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                        }
                        if (items.size > pictures!!.size) { VSpace(6); Quiet("+${items.size - pictures!!.size} more cards in the post", color = c.faint) }
                        if (ref is UpcomingPosts.Ref.Threads) Quiet("The card styles are picked again when it goes up.", color = c.faint)
                    }
                    ref is UpcomingPosts.Ref.Pro && items.isEmpty() -> Quiet("A reel or a picture of its own: it's made when it goes up.", color = c.faint)
                }
                // The words it goes up with.
                if (ref is UpcomingPosts.Ref.Threads) {
                    val words = remember(ids) {
                        items.firstOrNull()?.let { h -> state.books[h.clip.document]?.let { b -> ThreadsAuto.previewText(state, ThreadsAuto.Session(b, items)) } }
                    }
                    words?.let {
                        VSpace(14)
                        Text("The words", style = CpType.label, color = c.textMid)
                        VSpace(6)
                        Text(
                            it, style = CpType.body, color = c.text,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, c.divider, RoundedCornerShape(8.dp)).padding(12.dp),
                        )
                        if (items.size > 1) Quiet("A post of several is worded afresh when it goes up.", color = c.faint)
                    }
                }
                // Every passage in it, each one to edit or leave out.
                if (items.isNotEmpty()) {
                    VSpace(16)
                    Text(if (items.size == 1) "The passage" else "The ${items.size} passages", style = CpType.label, color = c.textMid)
                    for ((k, h) in items.withIndex()) {
                        VSpace(10)
                        Column(Modifier.fillMaxWidth().staggerIn(k)) {
                            Row {
                                Box(Modifier.padding(top = 3.dp).width(3.dp).height(18.dp).background(c.accent))
                                androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(h.clip.text.trim(), style = CpType.reading.copy(fontStyle = FontStyle.Italic), color = c.text, maxLines = 8, overflow = TextOverflow.Ellipsis)
                                    h.clip.note?.takeIf { it.isNotBlank() }?.let { Text("Your note: $it", style = CpType.small, color = c.textMid, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                                    Text(
                                        listOfNotNull(h.clip.chapter?.takeIf { it.isNotBlank() }, io.github.prithirajn.readync.sync.HighlightPosition.label(h.clip)).joinToString(" · "),
                                        style = CpType.small, color = c.faint, maxLines = 1,
                                    )
                                }
                            }
                            VSpace(6)
                            Row(Modifier.padding(start = 13.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PillButton("Edit", { editing = h })
                                if (items.size > 1) PillButton("Leave out", { gone = gone + h.clip.id; vm.leaveOutOfUpcoming(ref, h.clip.id) })
                            }
                        }
                    }
                }
                // Why a network puts up so much of one kind: what's switched off there.
                if (ref is UpcomingPosts.Ref.Pro) {
                    val off = remember(state.extras) { ProPosts.switchedOff(state) }
                    if (off.isNotEmpty()) {
                        VSpace(16)
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.accent.copy(alpha = 0.08f)).padding(12.dp)) {
                            Text("Switched off, so never posted", style = CpType.label, color = c.textMid)
                            VSpace(4)
                            Text(off.joinToString(" · "), style = CpType.small, color = c.text)
                            VSpace(8)
                            ProPosts.networks.firstOrNull()?.let { n -> PillButton("${n.name} settings", { onClose(); AppActions.pending.value = AppAction.ServicePage("social:${n.id}") }) }
                        }
                    }
                }
                VSpace(18)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigButton(if (busy) "Posting…" else "Post now", {
                        // Posted in the background (a reel can take minutes): the sheet goes down, and what
                        // came of it is said when it's done.
                        if (!busy) {
                            busy = true
                            vm.postUpcomingNow(ref) { r -> vm.notify(r, error = ref !is UpcomingPosts.Ref.X && !r.startsWith("Posted")) }
                            if (ref !is UpcomingPosts.Ref.X) vm.notify("Posting now…", "It goes on in the background; you'll see here when it's up.")
                            onClose()
                        }
                    }, Modifier.weight(1f), busy = busy)
                    BigButton("Don't post", { vm.skipUpcoming(ref); onClose() }, Modifier.weight(1f), filled = false, danger = true)
                }
            }
            null -> { VSpace(12); Quiet("Nothing more to show for this one.") }
        }
        said?.let { VSpace(8); AnimatedQuiet(it, color = c.faint) }
    }
}

/** Every post waiting for X, oldest (the next one) first: its pictures, its words to change, post now or take it out. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun XQueuePosts(vm: AppViewModel, next: String, onSaid: (String) -> Unit) {
    val c = Cp.colors
    val context = LocalContext.current
    var round by remember { mutableStateOf(0) }
    val entries by produceState(emptyList<XQueue.Entry>(), round) { value = withContext(Dispatchers.IO) { XQueue.all(context.applicationContext) } }
    if (entries.isEmpty()) { VSpace(12); Quiet("Nothing waiting for X."); return }
    Quiet("Post now opens X with it filled in: one tap there to post. Each waits until you do.", color = c.faint)
    for ((k, e) in entries.withIndex()) {
        key(e.dir.path) {
            VSpace(16)
            Column(Modifier.fillMaxWidth().staggerIn(k).clip(RoundedCornerShape(10.dp)).border(1.dp, if (e.dir.path == next) c.accent else c.divider, RoundedCornerShape(10.dp)).padding(12.dp)) {
                Text(
                    (if (e.dir.path == next) "Next · " else "") + e.label.ifBlank { "A post" }.replaceFirstChar { it.uppercase() },
                    style = CpType.label, color = if (e.dir.path == next) c.accentText else c.textMid,
                )
                val pics by produceState(emptyList<ImageBitmap>(), e.dir.path) {
                    value = withContext(Dispatchers.IO) { e.pictures.mapNotNull { f -> runCatching { android.graphics.BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull() } }
                }
                if (pics.isNotEmpty()) {
                    VSpace(8)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(pics) { i, img ->
                            Image(img, "Picture ${i + 1}", Modifier.liftable(maxTilt = 8f, lift = 1.04f).height(180.dp).width(180.dp * (img.width.toFloat() / img.height.coerceAtLeast(1))).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Fit)
                        }
                    }
                }
                VSpace(8)
                var text by remember(e.dir.path) { mutableStateOf(e.text) }
                MultiLineField(text, { text = it }, "Words", "What goes with it", minHeight = 80)
                Text("${text.length} / 280", style = CpType.small, color = if (text.length > 280) c.errorText else c.faint, modifier = Modifier.align(Alignment.End))
                VSpace(6)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (text != e.text) PillButton("Save words", { vm.editXPost(e.dir.path, text); round++; onSaid("Saved") }, outlined = false)
                    PillButton("Post on X now", {
                        if (text != e.text) vm.editXPost(e.dir.path, text)
                        vm.postUpcomingNow(UpcomingPosts.Ref.X(e.dir.path)) { r -> onSaid(r); round++ }
                    })
                    PillButton("Take out", { vm.skipUpcoming(UpcomingPosts.Ref.X(e.dir.path)); round++ }, danger = true)
                }
            }
        }
    }
}

@Composable
private fun key(k: Any, content: @Composable () -> Unit) = androidx.compose.runtime.key(k) { content() }
