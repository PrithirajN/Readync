package io.github.prithirajn.trackersync.ui

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.sync.Highlights
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import io.github.prithirajn.trackersync.ui.theme.CpType
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Five of your older highlights a day, to look back on: keep the ones you love, let go of the ones
 * you don't want to see again (they stay on the book, just out of the review), or edit one. What
 * you've seen isn't shown again for two months. Kept on this phone only.
 */
object DailyReview {
    private const val PER_DAY = 5
    private const val REST_DAYS = 60

    private fun prefs(context: Context) = context.getSharedPreferences("review", Context.MODE_PRIVATE)

    /** Today's picks: highlights a week old or more, not seen lately or let go, the same all day. */
    fun today(context: Context, s: Snapshot, day: LocalDate = LocalDate.now()): List<Highlights.Item> {
        val p = prefs(context)
        val seen = p.all.filterKeys { it.startsWith("seen:") }.mapNotNull { (k, v) -> (v as? Long)?.let { k.removePrefix("seen:") to it } }.toMap()
        val gone = p.getStringSet("gone", emptySet()).orEmpty()
        val todayN = day.toEpochDay()
        val pickedToday = p.getString("day:$todayN", null)?.split('\n')?.filter { it.isNotBlank() }
        val all = Highlights.all(s).filter { it.clip.text.isNotBlank() }
        if (pickedToday != null) return pickedToday.mapNotNull { id -> all.firstOrNull { it.clip.id == id } }
        val weekAgo = (System.currentTimeMillis() / 1000) - 7 * 86_400
        val pool = all.filter { it.clip.id !in gone && (seen[it.clip.id]?.let { d -> todayN - d >= REST_DAYS } ?: true) }
        val older = pool.filter { it.clip.createdAt in 1..weekAgo }.ifEmpty { pool }
        val rnd = java.util.Random(todayN * 31 + all.size)
        // Kept ones come round a little more often.
        val kept = p.getStringSet("kept", emptySet()).orEmpty()
        val picks = older.shuffled(rnd).sortedByDescending { if (it.clip.id in kept) rnd.nextInt(3) else 0 }.take(PER_DAY)
        p.edit().putString("day:$todayN", picks.joinToString("\n") { it.clip.id }).apply()
        return picks
    }

    /** How many of today's are still to look at. */
    fun left(context: Context, s: Snapshot): Int {
        val todayN = LocalDate.now().toEpochDay()
        val done = prefs(context).getStringSet("done:$todayN", emptySet()).orEmpty()
        return today(context, s).count { it.clip.id !in done }
    }

    fun mark(context: Context, id: String, keep: Boolean?) {
        val p = prefs(context)
        val todayN = LocalDate.now().toEpochDay()
        val e = p.edit().putLong("seen:$id", todayN)
            .putStringSet("done:$todayN", p.getStringSet("done:$todayN", emptySet()).orEmpty() + id)
        when (keep) {
            true -> e.putStringSet("kept", p.getStringSet("kept", emptySet()).orEmpty() + id)
            false -> e.putStringSet("gone", p.getStringSet("gone", emptySet()).orEmpty() + id)
            null -> {}
        }
        // A day of reviewing done counts toward the review streak.
        e.putLong("lastDay", todayN)
        if (p.getLong("lastDay", -1) == todayN - 1) e.putInt("streak", p.getInt("streak", 0) + 1)
        else if (p.getLong("lastDay", -1) != todayN) e.putInt("streak", 1)
        e.apply()
    }

    fun streak(context: Context): Int {
        val p = prefs(context)
        val last = p.getLong("lastDay", -1)
        return if (last >= LocalDate.now().toEpochDay() - 1) p.getInt("streak", 0) else 0
    }

    fun done(context: Context): Set<String> = prefs(context).getStringSet("done:${LocalDate.now().toEpochDay()}", emptySet()).orEmpty()
}

/**
 * The review: today's highlights as a stack of cards. Swipe right (or Keep) to keep one, left (or
 * Let go) to leave it out of future reviews; tap Edit to change it; Share makes it a picture.
 */
@Composable
fun DailyReviewSheet(vm: AppViewModel, state: Snapshot, onClose: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val picks = remember { DailyReview.today(context, state) }
    var done by remember { mutableStateOf(DailyReview.done(context)) }
    val queue = picks.filter { it.clip.id !in done }
    var editing by remember { mutableStateOf<Highlights.Item?>(null) }
    var sharing by remember { mutableStateOf<Highlights.Item?>(null) }
    val scope = rememberCoroutineScope()
    val dx = remember { Animatable(0f) }
    fun decide(item: Highlights.Item, keep: Boolean?) {
        if (keep == true) Haptics.confirm(view) else Haptics.tick(view)
        scope.launch {
            dx.animateTo(if (keep == false) -1400f else 1400f, tween(220))
            DailyReview.mark(context, item.clip.id, keep)
            done = DailyReview.done(context)
            dx.snapTo(0f)
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val c = Cp.colors
        Column(Modifier.fillMaxSize().background(c.page).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconCircleButton(io.github.prithirajn.trackersync.R.drawable.lucide_x, "Close", onClose, tint = c.text)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text("Daily review", style = CpType.section, color = c.text)
                    DeviceLabel(if (queue.isEmpty()) "Done for today" else "${picks.size - queue.size + 1} of ${picks.size}", color = c.quiet)
                }
                val streak = DailyReview.streak(context)
                if (streak >= 2) Text("🔥 $streak days", style = CpType.label, color = c.accentText)
            }
            Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 18.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                if (queue.isEmpty()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("✨", fontSize = 44.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(if (picks.isEmpty()) "No highlights to look back on yet" else "That's today's five", style = CpType.section, color = c.text)
                        Spacer(Modifier.height(6.dp))
                        Text(if (picks.isEmpty()) "Highlight a few passages and they'll come back here." else "Come back tomorrow for five more.", style = CpType.body, color = c.quiet)
                        Spacer(Modifier.height(18.dp))
                        BigButton("Close", onClose, Modifier.width(200.dp), filled = false)
                    }
                } else {
                    // The next card waits under this one, a little smaller.
                    queue.getOrNull(1)?.let { next ->
                        ReviewCard(state, next, Modifier.graphicsLayer { scaleX = 0.94f; scaleY = 0.94f; translationY = 34f; alpha = 0.7f })
                    }
                    val top = queue.first()
                    AnimatedContent(top.clip.id, transitionSpec = { (fadeIn(tween(200)) + scaleIn(spring(dampingRatio = 0.7f), initialScale = 0.94f)) togetherWith fadeOut(tween(1)) }, label = "card") { _ ->
                        ReviewCard(
                            state, top,
                            Modifier
                                .offset { IntOffset(dx.value.roundToInt(), 0) }
                                .graphicsLayer { rotationZ = dx.value / 40f }
                                .pointerInput(top.clip.id) {
                                    detectDragGestures(
                                        onDragEnd = {
                                            when {
                                                dx.value > 260f -> decide(top, true)
                                                dx.value < -260f -> decide(top, false)
                                                else -> scope.launch { dx.animateTo(0f, spring(dampingRatio = 0.6f)) }
                                            }
                                        },
                                    ) { ch, drag -> ch.consume(); scope.launch { dx.snapTo(dx.value + drag.x) } }
                                },
                            hint = dx.value,
                        )
                    }
                }
            }
            if (queue.isNotEmpty()) {
                val top = queue.first()
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigButton("Let go", { decide(top, false) }, Modifier.weight(1f), filled = false)
                    BigButton("Edit", { editing = top }, Modifier.weight(0.8f), filled = false)
                    BigButton("Share", { sharing = top }, Modifier.weight(0.8f), filled = false)
                    BigButton("Keep", { decide(top, true) }, Modifier.weight(1f))
                }
                Text("Let go only takes it out of these reviews; it stays on its book.", style = CpType.small, color = c.faint, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
            }
        }
        editing?.let { EditHighlightSheet(vm, state, it) { editing = null } }
        sharing?.let { item ->
            QuoteShareSheet(item.clip, state.books[item.clip.document], others = BookShot.othersFor(state, item.clip), pageText = state.clipPages[item.clip.id], onPageText = { vm.setClipPage(item.clip.id, it) }, sectors = BookShot.sectorsFor(state, state.books[item.clip.document])) { sharing = null }
        }
    }
}

/** One highlight as a card: the passage, your note in your hand, and its book with the cover. */
@Composable
private fun ReviewCard(state: Snapshot, item: Highlights.Item, modifier: Modifier, hint: Float = 0f) {
    val c = Cp.colors
    val b = state.books[item.clip.document]
    Box(
        modifier.fillMaxWidth().shadow(20.dp, RoundedCornerShape(28.dp), ambientColor = c.shade, spotColor = c.shade)
            .clip(RoundedCornerShape(28.dp)).background(c.surface).border(1.dp, c.hairline, RoundedCornerShape(28.dp)),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BookCover(b?.coverUrl, b?.displayTitle ?: "", 34.dp, 51.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(b?.displayTitle?.let { QuoteCard.shortTitle(it) } ?: "", style = CpType.bodyStrong, color = c.text, maxLines = 1)
                    DeviceLabel(listOfNotNull(io.github.prithirajn.trackersync.sync.HighlightPosition.label(item.clip), java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(item.clip.createdAt * 1000))).joinToString("  /  "), color = c.quiet)
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("“", style = CpType.reading.copy(fontSize = 60.sp, lineHeight = 40.sp), color = c.accent.copy(alpha = 0.3f))
            Text(item.clip.text.trim(), style = CpType.reading.copy(fontSize = 20.sp, lineHeight = 31.sp), color = c.text)
            item.clip.note?.takeIf { it.isNotBlank() }?.let { n ->
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.accentTint).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(n.trim(), style = CpType.eyebrow.copy(fontSize = 22.sp, lineHeight = 26.sp), color = c.accentText)
                }
            }
        }
        // Which way it's going as you drag: a tag fades in at the corner.
        if (abs(hint) > 40f) {
            val keep = hint > 0
            Text(
                if (keep) "KEEP" else "LET GO",
                style = CpType.label, color = if (keep) c.accentText else c.quiet,
                modifier = Modifier.align(if (keep) Alignment.TopStart else Alignment.TopEnd).padding(14.dp)
                    .graphicsLayer { alpha = ((abs(hint) - 40f) / 200f).coerceIn(0f, 1f); rotationZ = if (keep) -8f else 8f }
                    .border(2.dp, if (keep) c.accent else c.faint, CpShapes.control).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}
