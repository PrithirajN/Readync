package io.github.prithirajn.trackersync.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpType
import io.github.prithirajn.trackersync.ui.theme.DarkGadget
import kotlinx.coroutines.delay

/**
 * A reading session with a paper book (or any book): a big timer in pixel digits on the device's
 * screen, and a page counter you tap on as you turn pages. The screen stays on while it runs.
 * When you finish, the minutes and pages go into the day's reading and the book moves to your
 * page. A session set going is kept if you step away from the app.
 */
@Composable
fun ReadingTimerSheet(vm: AppViewModel, book: Book, onClose: () -> Unit) {
    val view = LocalView.current
    val startPage = remember { book.currentPage ?: book.pageCount?.let { Math.round(book.percentage * it).toInt() } ?: 0 }
    var page by remember { mutableStateOf(startPage) }
    // Time counted while running, plus the stretch running now.
    var banked by remember { mutableLongStateOf(0L) }
    var since by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var running by remember { mutableStateOf(true) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var finishing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(running) { while (running) { now = System.currentTimeMillis(); delay(250) } }
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    val elapsed = banked + if (running) now - since else 0L
    val secs = (elapsed / 1000).toInt()
    val clock = if (secs >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d", secs / 3600, secs / 60 % 60, secs % 60)
        else String.format(java.util.Locale.US, "%02d:%02d", secs / 60, secs % 60)
    fun pause() { if (running) { banked += System.currentTimeMillis() - since; running = false } }
    fun resume() { if (!running) { since = System.currentTimeMillis(); running = true } }

    Dialog(onDismissRequest = { if (secs < 5) onClose() else { pause(); finishing = true } }, properties = FullScreen) {
        DarkGadget {
            val c = Cp.colors
            Column(Modifier.fillMaxSize().background(c.page).fullScreenSafe().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconCircleButton(io.github.prithirajn.trackersync.R.drawable.lucide_x, "Close", { if (secs < 5) onClose() else { pause(); finishing = true } }, tint = c.text)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        DeviceLabel("Reading session", color = c.quiet)
                        Text(QuoteCard.shortTitle(book.displayTitle), style = CpType.bodyStrong, color = c.text, maxLines = 1)
                    }
                    BookCover(book.coverUrl, book.displayTitle, 34.dp, 51.dp)
                }
                Spacer(Modifier.height(18.dp))
                // The timer on the device's screen; its colon blinks while running.
                Screen(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DeviceLabel(if (running) "Running" else "Paused", color = if (running) c.accent else c.screenDim, modifier = Modifier.weight(1f))
                        val blink = rememberInfiniteTransition(label = "rec").animateFloat(0.2f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "recA")
                        Box(Modifier.graphicsLayer { alpha = if (running) blink.value else 0.3f }) { Led(true, size = 7.dp) }
                    }
                    Spacer(Modifier.height(16.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(clock, style = CpType.screenNumber.copy(fontSize = 92.sp, lineHeight = 96.sp), color = if (running) c.screenText else c.screenDim)
                    }
                    Spacer(Modifier.height(16.dp))
                    // A segment for every five minutes of the first hour.
                    SegmentBar((secs / 3600f).coerceIn(0f, 1f), Modifier.fillMaxWidth(), segments = 12, height = 7.dp, on = c.accent, off = c.screenOff)
                    Spacer(Modifier.height(10.dp))
                    Row {
                        DeviceLabel("Pages ${(page - startPage).coerceAtLeast(0)}", color = c.screenDim, modifier = Modifier.weight(1f))
                        val pace = if (secs >= 120 && page > startPage) Math.round((secs / 60.0) / (page - startPage) * 10) / 10.0 else null
                        DeviceLabel(pace?.let { "$it min a page" } ?: "", color = c.screenDim)
                    }
                }
                Spacer(Modifier.height(26.dp))
                // The page you're on: big keys to tap as you turn.
                DeviceLabel("You're on page", color = c.quiet, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Key({ if (page > 0) page-- }, Modifier.size(64.dp, 66.dp), shape = CircleShape, label = "Back a page") { Text("−", style = CpType.section, color = c.text) }
                    Box(Modifier.width(150.dp), contentAlignment = Alignment.Center) {
                        AnimatedContent(page, transitionSpec = {
                            val up = if (targetState > initialState) 1 else -1
                            (fadeIn(tween(140)) + androidx.compose.animation.slideInVertically { it / 2 * up }) togetherWith (fadeOut(tween(100)) + androidx.compose.animation.slideOutVertically { -it / 2 * up })
                        }, label = "page") { p -> Text("$p", style = CpType.screenNumber.copy(fontSize = 56.sp, lineHeight = 60.sp), color = c.accentText) }
                    }
                    Key({ page++ ; Haptics.tick(view) }, Modifier.size(84.dp, 86.dp), kind = KeyKind.SIGNAL, shape = CircleShape, label = "Next page") { Text("+", style = CpType.pageTitle, color = androidx.compose.ui.graphics.Color.White) }
                }
                book.pageCount?.let { n -> Text("of $n", style = CpType.small, color = c.quiet, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp)) }
                Spacer(Modifier.weight(1f))
                if (!finishing) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BigButton(if (running) "Pause" else "Resume", { if (running) pause() else resume() }, Modifier.weight(1f), filled = false)
                        BigButton("Finish", { pause(); finishing = true }, Modifier.weight(1f), look = KeyKind.INK)
                    }
                } else {
                    // The summary, and saving it.
                    val mins = (secs + 30) / 60
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Text(
                            "$mins minute${if (mins == 1) "" else "s"}" + (if (page > startPage) ", ${page - startPage} page${if (page - startPage == 1) "" else "s"} (to page $page)" else ""),
                            style = CpType.bodyStrong, color = c.text,
                        )
                        Text("Goes into today's reading and your streak" + (if (page > startPage) "; your trackers move to page $page." else "."), style = CpType.small, color = c.quiet)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BigButton("Keep reading", { finishing = false; resume() }, Modifier.weight(1f), filled = false)
                            BigButton("Save", {
                                busy = true
                                vm.logTimedSession(book.document, mins.coerceAtLeast(1), startPage, page) { busy = false; onClose() }
                            }, Modifier.weight(1f), busy = busy)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Discard", style = CpType.label, color = c.errorText, modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp).bouncyClickable(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) { onClose() })
                    }
                }
            }
        }
    }
}

/** Who or what a name is, from only what you've read so far. */
@Composable
fun WhoIsSheet(vm: AppViewModel, book: Book, onClose: () -> Unit) {
    val c = Cp.colors
    var term by remember { mutableStateOf("") }
    var asked by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AiSheet("Who's this?", onClose) {
        Quiet("A name, a place or a word from the book. The answer comes only from what's before your place (${book.percent}%), so nothing ahead is given away.", color = c.faint)
        VSpace(12)
        CpTextField(term, { term = it.take(60); error = null }, "Name or word", placeholder = "e.g. Mordecai")
        VSpace(10)
        BigButton(if (busy) "Looking back…" else "Remind me", {
            busy = true; result = null; error = null; asked = term.trim()
            vm.aiWhoIs(book.document, term) { r, e -> busy = false; result = r; error = e }
        }, busy = busy, enabled = term.trim().length >= 2)
        VSpace(12)
        when {
            busy -> AiThinking("Going back through what you've read…")
            error != null -> Text(error ?: "", style = CpType.small, color = c.errorText)
            result != null -> AiResult(asked ?: "", result!!) {}
        }
    }
}
