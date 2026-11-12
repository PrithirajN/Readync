package io.github.prithirajn.trackersync.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.sync.Highlights
import io.github.prithirajn.trackersync.sync.SpoilerCheck
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpType
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The highlights waiting to be checked, one card at a time: swipe right (or Fine) to send it,
 * left (or Spoiler) to send it as a spoiler, or Don't send to keep it here only. Closing sends
 * what was checked straight away. For a long list, the rest can be marked fine in one go.
 */
@Composable
fun HighlightCheckSheet(vm: AppViewModel, state: Snapshot, document: String? = null, onDone: () -> Unit) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    // The list as it was when opened, so a card marked here leaves and the rest keep their order.
    val ids = remember {
        SpoilerCheck.waiting(state).let { w -> if (document == null) w else w.filter { id -> Highlights.all(state).any { it.clip.id == id && it.clip.document == document } } }
    }
    val byId = remember(state.clipEdits, state.cpClips, state.paperClips, state.kindleClips, state.koClips) {
        Highlights.all(state).associateBy { it.clip.id }
    }
    val queue = ids.filter { it !in state.clipChecked }.mapNotNull { byId[it] }
    var marked by remember { mutableStateOf(0) }
    var spoilers by remember { mutableStateOf(0) }
    // Closing sends what was checked here right away, rather than waiting for the next sync.
    val onClose = { if (marked > 0) vm.sendChecked(marked); onDone() }
    val dx = remember { Animatable(0f) }
    fun decide(item: Highlights.Item, what: String) {
        if (what == SpoilerCheck.OK) Haptics.confirm(view) else Haptics.tick(view)
        scope.launch {
            dx.animateTo(if (what == SpoilerCheck.SPOILER) -1400f else 1400f, tween(220))
            vm.markHighlights(listOf(item.clip.id), what)
            marked++; if (what == SpoilerCheck.SPOILER) spoilers++
            dx.snapTo(0f)
        }
    }
    Dialog(onDismissRequest = onClose, properties = FullScreen) {
        val c = Cp.colors
        Column(Modifier.fillMaxSize().background(c.page).fullScreenSafe()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconCircleButton(io.github.prithirajn.trackersync.R.drawable.lucide_x, "Close", onClose, tint = c.text)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text("Check before sending", style = CpType.section, color = c.text)
                    DeviceLabel(if (queue.isEmpty()) "All checked" else "${queue.size} to check", color = c.quiet)
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 18.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                if (queue.isEmpty()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("✓", fontSize = 44.sp, color = c.accentText)
                        Spacer(Modifier.height(10.dp))
                        Text(if (marked == 0) "Nothing waiting" else "All checked", style = CpType.section, color = c.text)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (marked == 0) "New highlights show up here before they go to your trackers."
                            else "They're on their way to your trackers" + (if (spoilers > 0) ", ${spoilers} as spoiler${if (spoilers == 1) "" else "s"}." else "."),
                            style = CpType.body, color = c.quiet,
                        )
                        Spacer(Modifier.height(18.dp))
                        BigButton(if (marked > 0) "Send & close" else "Close", onClose, Modifier.width(200.dp), filled = marked > 0)
                    }
                } else {
                    queue.getOrNull(1)?.let { next ->
                        ReviewCard(state, next, Modifier.graphicsLayer { scaleX = 0.94f; scaleY = 0.94f; translationY = 34f; alpha = 0.7f })
                    }
                    val top = queue.first()
                    AnimatedContent(top.clip.id, transitionSpec = { (fadeIn(tween(200)) + scaleIn(spring(dampingRatio = 0.7f), initialScale = 0.94f)) togetherWith fadeOut(tween(1)) }, label = "check") { _ ->
                        ReviewCard(
                            state, top,
                            Modifier
                                .offset { IntOffset(dx.value.roundToInt(), 0) }
                                .graphicsLayer { rotationZ = dx.value / 40f }
                                .pointerInput(top.clip.id) {
                                    detectDragGestures(
                                        onDragEnd = {
                                            when {
                                                dx.value > 260f -> decide(top, SpoilerCheck.OK)
                                                dx.value < -260f -> decide(top, SpoilerCheck.SPOILER)
                                                else -> scope.launch { dx.animateTo(0f, spring(dampingRatio = 0.6f)) }
                                            }
                                        },
                                    ) { ch, drag -> ch.consume(); scope.launch { dx.snapTo(dx.value + drag.x) } }
                                },
                            hint = dx.value, leftTag = "SPOILER", rightTag = "FINE",
                        )
                    }
                }
            }
            if (queue.isNotEmpty()) {
                val top = queue.first()
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigButton("Spoiler", { decide(top, SpoilerCheck.SPOILER) }, Modifier.weight(1f), look = KeyKind.INK)
                    BigButton("Don't send", { decide(top, SpoilerCheck.SKIP) }, Modifier.weight(1f), filled = false)
                    BigButton("Fine", { decide(top, SpoilerCheck.OK) }, Modifier.weight(1f))
                }
                if (queue.size > 1) {
                    Box(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentAlignment = Alignment.Center) {
                        PillButton("Mark the other ${queue.size - 1} fine", {
                            Haptics.confirm(view)
                            vm.markHighlights(queue.drop(1).map { it.clip.id }, SpoilerCheck.OK)
                            marked += queue.size - 1
                        })
                    }
                }
                Text(spoilerLine(state), style = CpType.small, color = c.faint, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
            }
        }
    }
}

/** What a spoiler does, in a line, as the settings stand. */
internal fun spoilerLine(state: Snapshot): String =
    "A spoiler goes to Fable and Pagebound behind their spoiler tap, to private journals as it is, and to Goodreads and Micro.blog " +
        (if (state.spoilerPublic == "warn") "with a spoiler warning above it." else "not at all.") + " Swipe right for fine, left for spoiler."

/** A small mark on a highlight: what it was checked as (only when the check is on, or it was marked). */
@Composable
internal fun CheckChip(state: Snapshot, id: String, onPick: (String?) -> Unit) {
    if (!state.checkHighlights && id !in state.clipChecked) return
    val c = Cp.colors
    val m = state.clipChecked[id]
    val label = when (m) { SpoilerCheck.SPOILER -> "⚠ Spoiler"; SpoilerCheck.SKIP -> "Not sent"; SpoilerCheck.OK -> "Checked"; else -> "To check" }
    var open by remember { mutableStateOf(false) }
    Box {
        PillButton(label, { open = true }, outlined = m == null || m == SpoilerCheck.OK)
        androidx.compose.material3.DropdownMenu(open, { open = false }) {
            listOf(SpoilerCheck.OK to "Fine to send", SpoilerCheck.SPOILER to "Spoiler", SpoilerCheck.SKIP to "Don't send").forEach { (k, name) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text((if (m == k) "✓ " else "") + name, style = CpType.body, color = c.text) },
                    onClick = { open = false; onPick(k) },
                )
            }
        }
    }
}
