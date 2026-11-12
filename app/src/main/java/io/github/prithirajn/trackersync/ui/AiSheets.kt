package io.github.prithirajn.trackersync.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.prithirajn.trackersync.ai.Assistant
import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.sync.Highlights
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import io.github.prithirajn.trackersync.ui.theme.CpType

/** A bottom sheet for the AI helpers, in the app's style. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiSheet(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    val c = Cp.colors
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
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
        ) {
            Text(title, style = CpType.section, color = c.text)
            VSpace(4)
            Quiet("Worked out on this phone by your on-device model. Check it before you use it: small models get things wrong.", color = Cp.colors.faint)
            VSpace(14)
            content()
        }
    }
}

/** "Thinking on this phone…", while the model works (it can take a little while). */
@Composable
fun AiThinking(text: String = "Thinking on this phone…") {
    val c = Cp.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
        HSpace(10)
        Quiet(text)
    }
}

/** An answer in a card, with what can be done with it. */
@Composable
private fun AiResult(label: String, text: String, actions: @Composable () -> Unit) {
    val c = Cp.colors
    CpCard {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = CpType.small, color = c.accentText)
            VSpace(4)
            Text(text, style = CpType.body, color = c.text)
            VSpace(10)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
    }
}

private fun share(context: android.content.Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(Intent.createChooser(send, subject)) }
}

/**
 * The book's description, tidied (review quotes and marketing taken out, its own words kept)
 * and in two sentences: use either in place of the current one, or keep it.
 */
@Composable
fun AiDescriptionSheet(vm: AppViewModel, book: Book, onClose: () -> Unit) {
    val c = Cp.colors
    var result by remember { mutableStateOf<Pair<String?, String?>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    LaunchedEffect(book.document) { vm.aiDescriptions(book.document) { r, e -> result = r; error = e; busy = false } }
    AiSheet("Tidy the description", onClose) {
        when {
            busy -> AiThinking()
            error != null -> Text(error ?: "", style = CpType.small, color = c.errorText)
            else -> {
                result?.first?.let { tidy ->
                    AiResult("Tidied: review quotes and marketing taken out", tidy) {
                        PillButton("Use this", { vm.useDescription(book.document, tidy, "you (tidied by AI)"); onClose() }, outlined = false)
                    }
                    VSpace(12)
                }
                result?.second?.let { short ->
                    AiResult("In two sentences", short) {
                        PillButton("Use this", { vm.useDescription(book.document, short, "you (shortened by AI)"); onClose() }, outlined = false)
                    }
                    VSpace(12)
                }
                if (result?.first == null) Quiet("The tidied version kept changing the wording, so it isn't offered.", color = c.faint)
                VSpace(4)
                Quiet("The description you use here is also the one sent when you add this book to a tracker.", color = c.faint)
            }
        }
    }
}

/** Where you were in a book you're coming back to, from your own highlights so far (nothing past them). */
@Composable
fun CatchUpSheet(vm: AppViewModel, book: Book, onClose: () -> Unit) {
    val c = Cp.colors
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    LaunchedEffect(book.document) { vm.aiCatchUp(book.document) { r, e -> result = r; error = e; busy = false } }
    AiSheet("Catch me up", onClose) {
        when {
            busy -> AiThinking("Going over your highlights so far…")
            error != null -> Text(error ?: "", style = CpType.small, color = c.errorText)
            else -> result?.let { r ->
                AiResult("Where you were, at ${book.percent}%", r) {}
                VSpace(6)
                Quiet("Only from your highlights and notes up to where you are, so nothing ahead is given away.", color = c.faint)
            }
        }
    }
}

/** What your highlights in a book add up to, and the themes they fall under. */
@Composable
fun HighlightSummarySheet(vm: AppViewModel, state: Snapshot, book: Book, onClose: () -> Unit) {
    val c = Cp.colors
    val context = LocalContext.current
    // In the same order the model was given them, so its numbers point at the right ones.
    val clips = remember(book.document) { Highlights.forBook(state, book.document).map { it.clip }.sortedBy { it.percent ?: 0.0 } }
    var result by remember { mutableStateOf<Assistant.HighlightSummary?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    LaunchedEffect(book.document) { vm.aiSummarizeHighlights(book.document) { r, e -> result = r; error = e; busy = false } }
    AiSheet("Your highlights, summed up", onClose) {
        val r = result
        when {
            busy -> AiThinking("Reading your ${clips.size} highlights…")
            error != null -> Text(error ?: "", style = CpType.small, color = c.errorText)
            r != null -> {
                AiResult("What you noticed", r.summary) {
                    PillButton("Share", {
                        val text = buildString {
                            appendLine("My highlights in ${book.displayTitle}" + (book.author?.let { " by $it" } ?: ""))
                            appendLine()
                            appendLine(r.summary)
                            for ((theme, nums) in r.themes) {
                                appendLine()
                                appendLine("$theme:")
                                nums.mapNotNull { clips.getOrNull(it - 1) }.forEach { appendLine("> ${io.github.prithirajn.trackersync.trackers.quoted(it.text)}") }
                            }
                        }
                        share(context, "Highlights from ${book.displayTitle}", text)
                    })
                }
                if (r.themes.isNotEmpty()) {
                    VSpace(14)
                    Text("Themes", style = CpType.bodyStrong, color = c.text)
                    for ((theme, nums) in r.themes) {
                        VSpace(10)
                        Text(theme, style = CpType.body, color = c.accentText)
                        for (n in nums) {
                            clips.getOrNull(n - 1)?.let { clip ->
                                Text(
                                    io.github.prithirajn.trackersync.trackers.quoted(clip.text),
                                    style = CpType.small, color = c.textMid, maxLines = 3,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Questions about your reading ("what did I highlight about grief?", "which fantasy books did I
 * abandon?"), answered on the phone from your library and highlights.
 */
@Composable
fun AskLibrarySheet(vm: AppViewModel, onClose: () -> Unit) {
    val c = Cp.colors
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf<String?>(null) }
    var asked by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun ask(q: String) {
        if (q.isBlank() || busy) return
        busy = true; error = null; answer = null; asked = q
        vm.aiAsk(q) { a, e -> busy = false; answer = a; error = e }
    }
    val context = LocalContext.current
    var recap by remember { mutableStateOf<String?>(null) }
    var picks by remember { mutableStateOf<List<Assistant.NextRead>?>(null) }
    var working by remember { mutableStateOf<String?>(null) }
    AiSheet("Ask about your reading", onClose) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Recap my month", {
                if (working == null && !busy) {
                    working = "Writing up your month…"; error = null; answer = null; picks = null; recap = null
                    vm.aiMonthRecap { r, e -> working = null; recap = r; error = e }
                }
            }, outlined = false)
            PillButton("Pick my next read", {
                if (working == null && !busy) {
                    working = "Looking over your shelves…"; error = null; answer = null; recap = null; picks = null
                    vm.aiNextReads { r, e -> working = null; picks = r; error = e }
                }
            }, outlined = false)
        }
        working?.let { VSpace(12); AiThinking(it) }
        recap?.let { r ->
            VSpace(12)
            AiResult("Your month in reading", r) { PillButton("Share", { share(context, "My month in reading", r) }) }
        }
        picks?.let { list ->
            VSpace(12)
            Text("From your to-read and paused shelves", style = CpType.bodyStrong, color = c.text)
            list.forEach { p ->
                VSpace(8)
                CpCard {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(p.book.displayTitle, style = CpType.bodyStrong, color = c.text)
                        p.book.author?.let { Quiet(it) }
                        VSpace(4)
                        Text(p.why, style = CpType.body, color = c.textMid)
                    }
                }
            }
        }
        VSpace(16)
        CpTextField(question, { question = it }, "Your question", placeholder = "What did I highlight about grief?")
        VSpace(10)
        BigButton("Ask", { ask(question) }, busy = busy, enabled = question.isNotBlank())
        VSpace(10)
        // Questions to try, as tappable lines (a sideways row cut them off at the edge).
        if (answer == null && !busy) {
            Text("Try asking", style = CpType.small, color = c.quiet)
            VSpace(4)
            CpCard {
                listOf("Which books did I abandon?", "What did I rate highest this year?", "Which highlights are about loss?", "What should I pick up again?")
                    .forEachIndexed { i, example ->
                        if (i > 0) RowDivider()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .bouncyClickable(androidx.compose.ui.graphics.RectangleShape, pressedScale = 0.99f) { question = example; ask(example) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(example, style = CpType.body, color = c.textMid, modifier = Modifier.weight(1f))
                            LucideIcon(io.github.prithirajn.trackersync.R.drawable.lucide_chevron_right, Modifier.size(14.dp), c.faint)
                        }
                    }
            }
        }
        VSpace(14)
        when {
            busy -> AiThinking("Looking through your library…")
            error != null -> Text(error ?: "", style = CpType.small, color = c.errorText)
            answer != null -> {
                CpCard {
                    Column(Modifier.fillMaxWidth().background(c.surface).padding(14.dp)) {
                        asked?.let { Text(it, style = CpType.small, color = c.accentText); VSpace(4) }
                        Text(answer ?: "", style = CpType.body, color = c.text)
                    }
                }
                VSpace(6)
                Quiet("Answered from your books, their statuses and ratings, and the highlights that relate to the question.", color = c.faint)
            }
        }
    }
}
