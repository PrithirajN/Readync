package io.github.prithirajn.readync.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.prithirajn.readync.R
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.trackers.Trackers
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.CpShapes
import io.github.prithirajn.readync.ui.theme.CpType

/**
 * Your reading at a glance, then what Tracker Sync did: what needs you, this week's pages, the
 * year against your goal, what happened lately, and the sync log folded away at the bottom.
 */
@Composable
fun ActivityScreen(vm: AppViewModel, state: Snapshot) {
    val c = Cp.colors
    // Everything the sections show, worked out once in the background (see ActivityData).
    var reviewRefresh by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val data = rememberActivityData(state, reviewRefresh)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // A tap on the frame reading (when shown) scrolls this tab down and back at a steady fling's
    // speed, so how smoothly it scrolls can be measured without a finger.
    run {
        val bench = io.github.prithirajn.readync.FrameWatch.bench.intValue
        val visible = LocalTabVisible.current
        androidx.compose.runtime.LaunchedEffect(bench) {
            if (bench == 0 || !visible) return@LaunchedEffect
            listState.scrollToItem(0)
            repeat(2) {
                listState.animateScrollBy(6000f, androidx.compose.animation.core.tween(5000, easing = androidx.compose.animation.core.LinearEasing))
                listState.animateScrollBy(-6000f, androidx.compose.animation.core.tween(5000, easing = androidx.compose.animation.core.LinearEasing))
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize().edgeBump().fadeTopEdge(listState.canScrollBackward), state = listState, contentPadding = PaddingValues(bottom = 24.dp + TabBarSpace)) {
        item(key = "header", contentType = "header") {
            // Three quick taps on the title show or hide how smoothly the app draws and how long it
            // took to start, at the top of the screen.
            val taps = remember { LongArray(3) }
            val headerTap = Modifier.clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {
                val now = System.currentTimeMillis()
                taps[0] = taps[1]; taps[1] = taps[2]; taps[2] = now
                if (now - taps[0] < 900) {
                    taps.fill(0)
                    io.github.prithirajn.readync.FrameWatch.showBadge.value = !io.github.prithirajn.readync.FrameWatch.showBadge.value
                }
            }
            DebugTimed("header") { TabHeader("your reading in numbers", "Stats", headerTap) }
        }
        activityInsights(vm, state, data) { reviewRefresh++ }
        // Library checks, your rating rubric, the top-books image and the reading-log export.
        shelfprintTools(vm, state, data)
    }
}
