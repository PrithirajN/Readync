package io.github.prithirajn.readync.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.prithirajn.readync.ui.QuoteVideo.Backdrop
import io.github.prithirajn.readync.ui.QuoteVideo.Face
import io.github.prithirajn.readync.ui.QuoteVideo.Layout
import io.github.prithirajn.readync.ui.QuoteVideo.Motion
import io.github.prithirajn.readync.ui.QuoteVideo.Pace
import io.github.prithirajn.readync.ui.QuoteVideo.Shape
import io.github.prithirajn.readync.ui.QuoteVideo.Spec
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.CpType
import io.github.prithirajn.readync.ui.theme.DarkGadget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The video studio: the passage playing as a video in the viewfinder, live, while you choose how
 * it moves, where it sits and on what, the type, the pace and what else shows. Then Make video
 * encodes it at full size; you watch it, and share or save it.
 */
@Composable
fun VideoStudio(input: QuoteVideo.Input, plain: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var spec by remember { mutableStateOf(QuoteVideo.saved(context)) }
    var mode by remember { mutableStateOf("Motion") }
    var making by remember { mutableStateOf<Float?>(null) }
    var made by remember { mutableStateOf<File?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    // The scene for these choices, built off the main thread; the preview plays it.
    var scene by remember { mutableStateOf<QuoteVideo.Scene?>(null) }
    LaunchedEffect(spec) {
        QuoteVideo.save(context, spec)
        made = null
        // (The old scene is left to be collected: the preview may still be drawing it.)
        scene = withContext(Dispatchers.Default) { runCatching { QuoteVideo.Scene(context, input, spec) }.getOrNull() }
    }
    // The live preview: frames drawn small, about 15 a second, into two pictures taken in turn.
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var at by remember { mutableStateOf(0f) }
    LaunchedEffect(scene) {
        val sc = scene ?: return@LaunchedEffect
        val k = 0.34f
        val bufs = List(2) { Bitmap.createBitmap((sc.w * k).toInt(), (sc.h * k).toInt(), Bitmap.Config.ARGB_8888) }
        var which = 0
        val startAt = System.nanoTime()
        while (isActive) {
            val t = ((System.nanoTime() - startAt) / 1e9f) % (sc.duration + 0.6f)
            val b = bufs[which]
            withContext(Dispatchers.Default) { runCatching { val cv = Canvas(b); cv.save(); cv.scale(k, k); sc.draw(cv, t.coerceAtMost(sc.duration)); cv.restore() } }
            frame = b; at = t; tick++
            which = 1 - which
            delay(55)
        }
    }

    Dialog(onDismissRequest = onClose, properties = FullScreen) {
        DarkGadget {
            val c = Cp.colors
            Column(Modifier.fillMaxSize().background(c.page).fullScreenSafe()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconCircleButton(io.github.prithirajn.readync.R.drawable.lucide_x, "Close", onClose, tint = c.text)
                    Text("Video", style = CpType.section, color = c.text, modifier = Modifier.padding(start = 12.dp, end = 12.dp))
                    Segmented(Shape.entries, spec.shape, { it.label }, { spec = spec.copy(shape = it) }, Modifier.weight(1f))
                }
                // The viewfinder: the video playing, live, or the made video.
                Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp).clip(RoundedCornerShape(20.dp)).background(c.screen).padding(10.dp)) {
                    Column(Modifier.fillMaxSize()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                            DeviceLabel("${spec.motion.label} · ${spec.layout.label} · ${scene?.let { "${Math.round(it.duration)}s" } ?: ""}", color = c.screenDim, modifier = Modifier.weight(1f))
                            Led(true, size = 6.dp, color = if (made != null) androidx.compose.ui.graphics.Color(0xFF4ADE80) else c.accent)
                            Spacer(Modifier.width(6.dp))
                            DeviceLabel(if (made != null) "Made" else if (making != null) "Recording" else "Live", color = c.accent)
                        }
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            val aspect = 1080f / spec.shape.h
                            val file = made
                            if (file != null) VideoPreview(file, aspect, Modifier.fillMaxHeight())
                            else {
                                val f = frame
                                if (f == null) CircularProgressIndicator(Modifier.size(28.dp), color = c.accent, strokeWidth = 2.dp)
                                else ComposeCanvas(Modifier.fillMaxHeight().aspectRatio(aspect).clip(RoundedCornerShape(10.dp))) {
                                    tick.let { }
                                    drawImage(f.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                                }
                            }
                        }
                        // Where the preview is in the video.
                        scene?.takeIf { made == null }?.let { sc ->
                            Spacer(Modifier.height(8.dp))
                            ComposeCanvas(Modifier.fillMaxWidth().height(4.dp)) {
                                drawRoundRect(c.screenOff, size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                                drawRoundRect(c.accent, size = Size(size.width * (at / sc.duration).coerceIn(0f, 1f), size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                            }
                        }
                    }
                }
                if (made == null) {
                    // The choices, as modes.
                    val modes = listOf("Motion", "Scene", "Type", "Extras")
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        for (m in modes) {
                            val on = m == mode
                            Column(
                                Modifier.clip(RoundedCornerShape(10.dp)).bouncyClickable(RoundedCornerShape(10.dp), pressedScale = 0.94f, onClickLabel = m) { if (!on) { Haptics.tick(view); mode = m } }.padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(m.uppercase(), style = CpType.mono.copy(fontSize = 11.5.sp, letterSpacing = 1.sp), color = if (on) c.text else c.faint)
                                Spacer(Modifier.height(5.dp))
                                GlowBar(on, width = 18.dp)
                            }
                        }
                    }
                    AnimatedContent(
                        mode,
                        transitionSpec = {
                            val d = if (modes.indexOf(targetState) > modes.indexOf(initialState)) 1 else -1
                            (slideInHorizontally(spring(dampingRatio = 0.86f, stiffness = 420f)) { it / 4 * d } + fadeIn(tween(180))) togetherWith (slideOutHorizontally(tween(150)) { -it / 5 * d } + fadeOut(tween(110)))
                        },
                        label = "videoMode",
                    ) { m ->
                        Column(Modifier.fillMaxWidth().height(230.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp)) {
                            when (m) {
                                "Motion" -> {
                                    // A key for each way the passage can move, two to a row.
                                    for (pair in Motion.entries.chunked(2)) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                                            for (mo in pair) ChoiceKey(mo.label, mo.line, spec.motion == mo, Modifier.weight(1f)) { spec = spec.copy(motion = mo) }
                                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                                        }
                                    }
                                    DeviceLabel("Pace", color = c.quiet, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
                                    Segmented(Pace.entries, spec.pace, { it.label }, { spec = spec.copy(pace = it) })
                                }
                                "Scene" -> {
                                    DeviceLabel("Where the words sit", color = c.quiet, modifier = Modifier.padding(bottom = 6.dp))
                                    Segmented(Layout.entries, spec.layout, { it.label }, { spec = spec.copy(layout = it) })
                                    DeviceLabel("Behind them", color = c.quiet, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        for (b in Backdrop.entries) PillButton(b.label, { spec = spec.copy(backdrop = b) }, outlined = spec.backdrop != b)
                                    }
                                    StudioSwitch("Slow zoom", "The background drifts in as it plays", spec.zoom) { spec = spec.copy(zoom = it) }
                                }
                                "Type" -> {
                                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        for (fc in Face.entries) {
                                            val on = spec.face == fc
                                            Key({ if (!on) spec = spec.copy(face = fc) }, Modifier.size(width = 76.dp, height = 84.dp), shape = RoundedCornerShape(14.dp), ledge = if (on) 1.dp else 3.dp, label = fc.label) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Text("Ag", style = TextStyle(fontFamily = FontFamily(Font(fc.res)), fontSize = 26.sp, color = if (on) c.text else c.textMid))
                                                    Text(fc.label.uppercase(), style = CpType.mono.copy(fontSize = 8.5.sp), color = if (on) c.accent else c.quiet, maxLines = 1)
                                                    Spacer(Modifier.height(4.dp))
                                                    GlowBar(on, width = 18.dp)
                                                }
                                            }
                                        }
                                    }
                                    Text("The type sizes itself to fit the passage.", style = CpType.small, color = c.faint, modifier = Modifier.padding(top = 8.dp))
                                }
                                else -> {
                                    StudioSwitch("The book's cover", "Dropped in at the top (not with Subtitle, where it's the background)", spec.cover) { spec = spec.copy(cover = it) }
                                    StudioSwitch("My note", "Written in by hand under the passage", spec.note) { spec = spec.copy(note = it) }
                                    StudioSwitch("The title", "The book, its author and where the passage is", spec.title) { spec = spec.copy(title = it) }
                                    StudioSwitch("${io.github.prithirajn.readync.Edition.name} mark", "Small, at the foot, at the end", spec.mark) { spec = spec.copy(mark = it) }
                                }
                            }
                        }
                    }
                }
                said?.let { AnimatedQuiet(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = c.textMid) }
                // The bottom: make it, then share or save it.
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val file = made
                    if (file == null) {
                        BigButton(making?.let { "Recording… ${(it * 100).toInt()}%" } ?: "● Make video", {
                            if (scene == null || making != null) return@BigButton
                            scope.launch {
                                making = 0f; said = null
                                val out = withContext(Dispatchers.Default) {
                                    runCatching {
                                        // A fresh scene for the recording, so the preview can keep playing.
                                        val rec = QuoteVideo.Scene(context, input, spec)
                                        try { QuoteVideo.encode(context, rec) { p -> making = p } } finally { rec.recycle() }
                                    }.getOrNull()
                                }
                                making = null
                                made = out
                                if (out == null) said = "Couldn't make the video on this phone." else Haptics.confirm(view)
                            }
                        }, enabled = scene != null)
                    } else {
                        BigButton("Edit", { made = null }, Modifier.weight(0.8f), filled = false)
                        BigButton("Save", { said = if (QuoteVideo.save(context, file)) "Saved to Movies › ${io.github.prithirajn.readync.Edition.name}." else "Couldn't save here; use Share instead." }, Modifier.weight(0.9f), filled = false)
                        BigButton("X", { said = XPost.said(XPost.video(context, file, plain), "the video") }, Modifier.weight(0.6f), look = KeyKind.INK)
                        BigButton("Share", { runCatching { QuoteVideo.share(context, file, plain) } }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** A choice with a name and a line on what it does; orange when it's the one. */
@Composable
private fun ChoiceKey(title: String, line: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val kind = if (on) KeyKind.SIGNAL else KeyKind.PLAIN
    Key(onClick, modifier.height(74.dp), kind = kind, shape = RoundedCornerShape(16.dp), label = title) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Text(title, style = CpType.bodyStrong, color = keyText(kind), maxLines = 1)
            Text(line, style = CpType.small, color = if (on) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f) else Cp.colors.quiet, maxLines = 2)
        }
    }
}

@Composable
private fun StudioSwitch(title: String, line: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Cp.colors
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pressRow(source, title, { onChange(!checked) }).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = CpType.body, color = c.text)
            Text(line, style = CpType.small, color = c.quiet)
        }
        CpToggle(checked, onChange, title)
    }
}
