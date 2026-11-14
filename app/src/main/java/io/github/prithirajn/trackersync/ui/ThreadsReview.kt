package io.github.prithirajn.trackersync.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.unit.dp
import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import io.github.prithirajn.trackersync.ui.theme.CpType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlainSheet(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    val c = Cp.colors
    GuardedSheet(
        dragHandle = { SheetGrip() },
        onDismissRequest = onClose,
        sheetState = rememberSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
                Text(title, style = CpType.section, color = c.text)
                content()
            }
            SheetToaster()
        }
    }
}

/** On a book's page, with Threads connected, once it's finished or you've stopped: posting your review. */
@Composable
fun ThreadsBookCard(vm: AppViewModel, state: Snapshot, book: Book, onReview: () -> Unit) {
    if (state.threadsToken == null || !ThreadsAuto.canReview(book)) return
    val c = Cp.colors
    VSpace(16)
    CpCard {
        Column(Modifier.padding(16.dp)) {
            val posted = state.threadsReviewed[book.document]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(if (book.status == ReadStatus.DNF) "Your verdict on Threads" else "Your review on Threads", style = CpType.body, color = c.text)
                    Quiet(
                        when {
                            posted != null -> "Posted ${ago(posted * 1000)}."
                            book.rating == null && book.review.isNullOrBlank() -> "Rate it first, then post your stars and review."
                            else -> "Your stars and review as slides to swipe through."
                        },
                        color = c.faint,
                    )
                }
                PillButton(if (posted != null) "Post again" else "Post review", onReview, outlined = posted != null)
            }
        }
    }
}

/** On a book's Highlights tab, with Threads connected: whether its new highlights go to Threads (off until you turn it on). */
@Composable
fun ThreadsHighlightsToggle(vm: AppViewModel, state: Snapshot, book: Book) {
    if (state.threadsToken == null) return
    val c = Cp.colors
    val on = book.document in state.threadsBooks
    VSpace(12)
    CpCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Post to Threads", style = CpType.bodyStrong, color = c.text)
                Quiet(
                    if (on) "New highlights, quotes and notes from this book go to @${state.threadsUser ?: "you"} after each sync, each as a Book card."
                    else "Off for this book. Turn it on and its new highlights go to Threads by themselves (ones already here aren't posted).",
                    color = c.faint,
                )
            }
            CpToggle(on, { vm.setThreadsForBook(book.document, it) }, "Post this book's highlights to Threads")
        }
    }
}

/**
 * Posting a finished (or not finished) book's rating and review to Threads: the post's words with
 * your stars, and the review as square slides, previewed in a colour scheme you can change.
 */
@Composable
fun ReviewPostSheet(vm: AppViewModel, state: Snapshot, book: Book, onClose: () -> Unit) {
    val c = Cp.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    var theme by remember(book.document) { mutableStateOf((0 until QuoteCard.REVIEW_THEMES).random()) }
    // The post's words, worded one of many ways, picked at random (and changed with a tap).
    var wording by remember(book.document) { mutableStateOf((0 until ThreadsAuto.reviewWordings(book)).random()) }
    var slides by remember { mutableStateOf<List<Bitmap>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var recap by remember { mutableStateOf(false) }
    if (recap) { BookRecapSheet(vm, state, book, onClose); return }
    LaunchedEffect(theme, book.rating, book.review) { slides = null; vm.reviewSlides(book.document, theme) { slides = it } }
    PlainSheet(if (book.status == ReadStatus.DNF) "Post your verdict?" else "Post your review?", onClose) {
        Quiet("To @${state.threadsUser ?: "you"} on Threads.", color = c.faint)
        VSpace(14)
        // The post's words, as they'll read.
        CpCard {
            Text(ThreadsAuto.reviewText(book, wording), style = CpType.body, color = c.text, modifier = Modifier.padding(14.dp))
        }
        VSpace(12)
        val list = slides
        if (list == null) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), color = c.faint) }
        else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (b in list) Image(b.asImageBitmap(), "A slide of your review", Modifier.size(220.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, c.divider, RoundedCornerShape(12.dp)))
        }
        list?.let { VSpace(6); Quiet("${it.size} slide${if (it.size == 1) "" else "s"} to swipe through" + if (book.review.isNullOrBlank()) " (no review written, so just your stars)." else ".", color = c.faint) }
        VSpace(10)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Another style", { theme = (theme + 1) % QuoteCard.REVIEW_THEMES })
            PillButton("Reword", { wording = (wording + 1 + (0 until ThreadsAuto.reviewWordings(book) - 1).random()) % ThreadsAuto.reviewWordings(book) })
        }
        error?.let { VSpace(8); Text(it, style = CpType.small, color = c.errorText) }
        VSpace(16)
        BigButton("Post to Threads", {
            busy = true; error = null
            vm.postReviewToThreads(book.document, theme, wording) { e -> busy = false; if (e == null) onClose() else error = e }
        }, busy = busy, enabled = list != null && (book.rating != null || !book.review.isNullOrBlank()))
        // The same slides on X, by hand (four at most there).
        VSpace(8)
        var xSaid by remember { mutableStateOf<String?>(null) }
        BigButton("Post to X instead", {
            list?.let { xSaid = XPost.said(XPost.pictures(context, it, ThreadsAuto.reviewText(book, wording)), if (it.size > XPost.MAX_PICTURES) "the first ${XPost.MAX_PICTURES} slides" else "the slides") }
        }, filled = false, enabled = list != null)
        xSaid?.let { VSpace(6); Quiet(it, color = c.faint) }
        if (book.status == ReadStatus.FINISHED) {
            VSpace(8)
            BigButton("\u2726 Or a recap: video & stats carousel", { recap = true }, look = KeyKind.INK)
        }
        VSpace(8)
        BigButton("Not now", onClose, filled = false)
    }
}

/**
 * Did not finish: where you stopped (where you are now, or moved), your stars and why if you like.
 * It goes to your trackers as DNF; with Threads connected, you're offered to post your verdict.
 */
@Composable
fun DnfSheet(vm: AppViewModel, state: Snapshot, book: Book, onClose: () -> Unit, onMarked: () -> Unit) {
    val c = Cp.colors
    var at by remember { mutableStateOf(book.percent.coerceIn(0, 99)) }
    var rating by remember { mutableStateOf(book.rating) }
    var why by remember { mutableStateOf(book.review ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    PlainSheet("Did not finish", onClose) {
        Quiet(book.displayTitle + (book.author?.let { " · $it" } ?: ""), maxLines = 2)
        VSpace(6)
        Quiet("Life's too short for books that aren't for you. It moves to your DNF shelf and goes to your trackers as Did not finish.", color = c.faint)
        VSpace(16)
        Text("Stopped at $at%", style = CpType.label, color = c.text)
        Slider(
            value = at.toFloat(), onValueChange = { at = it.toInt() }, valueRange = 0f..99f,
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.divider),
        )
        VSpace(8)
        Text("Your rating (optional)", style = CpType.label, color = c.textMid)
        VSpace(6)
        StarPicker(rating) { rating = it }
        VSpace(12)
        MultiLineField(why, { why = it }, "Why you stopped (optional)", "It goes with your rating, as your review", minHeight = 90)
        error?.let { VSpace(8); Text(it, style = CpType.small, color = c.errorText) }
        VSpace(16)
        BigButton("Mark as Did not finish", {
            busy = true; error = null
            vm.markDnf(book.document, if (at != book.percent) at else null, rating, why) { e -> busy = false; if (e == null) onMarked() else error = e }
        }, busy = busy, danger = true)
        VSpace(8)
        BigButton("Cancel", onClose, filled = false)
    }
}
