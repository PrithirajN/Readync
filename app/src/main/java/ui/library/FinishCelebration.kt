package io.github.prithirajn.readync.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.prithirajn.readync.data.Book
import io.github.prithirajn.readync.data.ReadStatus
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.ui.theme.Lora
import kotlinx.coroutines.delay

/**
 * Finishing a book is celebrated once, however it was finished: on your reader and synced, marked
 * here, or with a rating already given elsewhere (the rate-and-review sheet's confetti only showed for
 * books still without a rating, so a book rated on Seekquel first was never celebrated).
 */
object FinishCelebrations {
    private const val KEY = "finish.celebrated"
    /** Finishes from the last three days are celebrated; older ones (from before this) aren't. */
    private const val RECENT = 3 * 86_400L

    fun celebrated(s: Snapshot): Set<String> = s.extras[KEY].orEmpty().split('\n').filter { it.isNotBlank() }.toSet()

    /** The next book to celebrate, if any. */
    fun next(s: Snapshot, now: Long = System.currentTimeMillis() / 1000): Book? {
        val done = celebrated(s)
        return s.books.values.filter { b ->
            b.status == ReadStatus.FINISHED && !b.held && b.document !in done &&
                (b.finishedAt ?: 0L).let { it > 0 && now - it in 0..RECENT }
        }.maxByOrNull { it.finishedAt ?: 0L }
    }

    /** Marked as celebrated (the newest 200 kept). */
    fun mark(s: Snapshot, document: String): Snapshot {
        val list = (celebrated(s).toList() + document).distinct().takeLast(200)
        return s.copy(extras = s.extras + (KEY to list.joinToString("\n")))
    }
}

/**
 * The celebration: the cover rising in, confetti in two bursts, "Finished", how long it took and how
 * many pages, and your rating with its number when there is one. A tap anywhere closes it; "Open the
 * book" goes to it (to rate it, review it or make a recap).
 */
@Composable
fun FinishCelebrationOverlay(vm: AppViewModel, state: Snapshot) {
    val book = remember(state.books, state.extras) { FinishCelebrations.next(state) } ?: return
    var shown by remember(book.document) { mutableStateOf(true) }
    if (!shown) return
    fun close() { shown = false; vm.markFinishCelebrated(book.document) }
    var burst by remember(book.document) { mutableIntStateOf(0) }
    val rise = remember(book.document) { Animatable(0f) }
    LaunchedEffect(book.document) {
        burst++
        rise.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 140f))
        delay(700); burst++
        delay(6_000); close()
    }
    val days = book.startedAt?.let { s -> book.finishedAt?.let { f -> ((f - s) / 86_400L + 1).coerceAtLeast(1) } }
    val line = listOfNotNull(
        days?.let { "in $it day${if (it == 1L) "" else "s"}" },
        book.pageCount?.let { "$it pages" },
    ).joinToString(" · ")
    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xF20E0B08), Color(0xF21C140A), Color(0xF22A1C0C))))
                .clickable(MutableInteractionSource(), indication = null) { close() },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Box(Modifier.graphicsLayer {
                    val k = rise.value
                    alpha = k.coerceIn(0f, 1f)
                    translationY = (1f - k) * 120f
                    scaleX = 0.85f + 0.15f * k; scaleY = scaleX
                    rotationZ = (1f - k) * -6f
                }) {
                    BookCover(book.coverUrl, book.displayTitle, 150.dp, 225.dp)
                }
                androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 24.dp))
                Text("Finished!", fontFamily = Lora, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, color = Color(0xFFF6E7C8), textAlign = TextAlign.Center)
                Text(book.displayTitle, fontSize = 17.sp, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp), maxLines = 3)
                book.author?.let { Text(it, fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp)) }
                if (line.isNotEmpty()) Text(line, fontSize = 14.sp, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(top = 10.dp))
                book.rating?.let { r -> Text(starsWithNumber(r), fontSize = 20.sp, color = Color(0xFFF2B84B), modifier = Modifier.padding(top = 10.dp)) }
                androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Open the book", { close(); AppActions.pending.value = AppAction.OpenBook(book.document) }, outlined = false)
                    PillButton("Nice", { close() })
                }
            }
            Confetti(burst, Modifier.fillMaxSize())
        }
    }
}
