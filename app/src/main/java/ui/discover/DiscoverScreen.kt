package io.github.prithirajn.readync.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.foundation.layout.height
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.prithirajn.readync.R
import io.github.prithirajn.readync.data.PriceWatch
import io.github.prithirajn.readync.data.Release
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.source.Opds
import io.github.prithirajn.readync.sync.SffFeed
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.CpShapes
import io.github.prithirajn.readync.ui.theme.CpType
import io.github.prithirajn.readync.ui.theme.DisplaySmall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Discover: everything about books you don't have yet. One masthead with a pill per section (News,
 * Releases, Deals, Catalogs); each section is the screen it was before (the SFF news, the Releases
 * card given a page, the price tracker, the OPDS catalogs). It opens on News.
 */
@Composable
fun DiscoverScreen(vm: AppViewModel, state: Snapshot, active: Boolean = true) {
    var section by rememberSaveable { mutableStateOf(DiscoverSection.NEWS) }
    // Asked for from outside (a notification, a share).
    val asked by DiscoverGo.section.collectAsState()
    LaunchedEffect(asked) { asked?.let { section = it; DiscoverGo.section.value = null } }
    val header: @Composable () -> Unit = { DiscoverHeader(section) { section = it } }
    Crossfade(section, animationSpec = Motion.medium(), label = "discoverSection") { s ->
        when (s) {
            DiscoverSection.NEWS -> SffScreen(vm, state, active = active, header = header)
            DiscoverSection.RELEASES -> ReleasesPage(vm, state, header)
            DiscoverSection.DEALS -> CatalogsScreen(vm, state, header = header, modes = listOf("Deals"))
            DiscoverSection.CATALOGS -> CatalogsScreen(vm, state, header = header, modes = listOf("Search", "Posts"))
        }
    }
}

/** The tab's masthead and its sections as a row of pills, the chosen one inked. */
@Composable
private fun DiscoverHeader(section: DiscoverSection, onPick: (DiscoverSection) -> Unit) {
    val c = Cp.colors
    val view = LocalView.current
    Column {
        TabHeader("books beyond your shelves", "Discover")
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (s in DiscoverSection.entries) {
                val on = s == section
                Text(
                    s.label,
                    style = CpType.label, color = if (on) c.onAccent else c.textMid, maxLines = 1,
                    modifier = Modifier
                        .clip(CpShapes.pill)
                        .background(if (on) c.accent else c.divider)
                        .bouncyClickable(CpShapes.pill, pressedScale = 0.94f, role = Role.Tab, onClickLabel = s.label) {
                            if (!on) { Haptics.tick(view); onPick(s) }
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** Releases, given a page of its own: out now and coming, with the settings it already has. */
@Composable
private fun ReleasesPage(vm: AppViewModel, state: Snapshot, header: @Composable () -> Unit) {
    val listState = rememberLazyListState()
    val now = System.currentTimeMillis() / 1000
    // Pull the page down past its top to look for new releases now (as Settings' Look now does).
    var looking by remember { mutableStateOf(false) }
    val pull = rememberPullToAct {
        if (!looking && state.releaseSettings.enabled) {
            looking = true
            vm.checkReleasesNow { n -> looking = false; vm.notify(if (n == 0) "Nothing new found" else "$n new", "New & upcoming") }
        }
    }
    val out = remember(state.releases) { state.releases.values.filter { !it.skipped && it.isOut(now) }.sortedByDescending { it.date ?: it.seenAt } }
    val coming = remember(state.releases) { state.releases.values.filter { !it.skipped && !it.isOut(now) }.sortedBy { it.date ?: Long.MAX_VALUE } }
    PullFrame(pull, looking, "Pull to look for new releases", "Let go to look", "Looking for new releases…") { pm ->
        LazyColumn(
            pm.fillMaxSize().edgeBump().fadeTopEdge(listState.canScrollBackward),
            state = listState, contentPadding = PaddingValues(bottom = 32.dp + TabBarSpace),
        ) {
            item(key = "head") { header() }
            item(key = "releases") { Column { VSpace(8); ReleasesCard(vm, state, out, coming) } }
        }
    }
}
