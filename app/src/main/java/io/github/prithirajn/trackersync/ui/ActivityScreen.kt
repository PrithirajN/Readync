package io.github.prithirajn.trackersync.ui

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
import io.github.prithirajn.trackersync.R
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.trackers.Trackers
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import io.github.prithirajn.trackersync.ui.theme.CpType

/**
 * Your reading at a glance, then what Tracker Sync did: what needs you, this week's pages, the
 * year against your goal, what happened lately, and the sync log folded away at the bottom.
 */
@Composable
fun ActivityScreen(vm: AppViewModel, state: Snapshot) {
    val c = Cp.colors
    // Folded away; its header says when something went wrong in the last day.
    var logOpen by rememberSaveable { mutableStateOf(false) }
    var filterMode by rememberSaveable { mutableStateOf("all") }
    val errorCount = remember(state.log) { state.log.count { it.error } }
    val servicesInLog = remember(state.log) {
        state.log.mapNotNull { it.service }.distinct().map { id ->
            id to (Trackers.byId(id)?.name ?: if (id.equals("crosspoint", ignoreCase = true)) "CrossPoint" else id.replaceFirstChar { it.uppercase() })
        }
    }

    val filteredLog = remember(state.log, filterMode) {
        val picked = when {
            filterMode == "errors" -> state.log.filter { it.error }
            filterMode.startsWith("service:") -> {
                val svc = filterMode.removePrefix("service:")
                state.log.filter { it.service == svc }
            }
            else -> state.log
        }
        // The same message again and again (a review refused on every sync) shows once, with how
        // many times and when last, in the place of its latest one.
        picked.groupBy { Triple(it.service, it.message, it.error) }.values
            .map { same -> same.maxBy { it.time } to same.size }
            .sortedByDescending { it.first.time }
    }
    val problemKinds = remember(state.log) { state.log.filter { it.error }.distinctBy { it.service to it.message }.size }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().fadeTopEdge(listState.canScrollBackward), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(PagePadding) {
                PageTitle("Activity")
                Quiet("Your reading lately, and what ${io.github.prithirajn.trackersync.Edition.name} did.")
            }
        }
        // What's new in your series: first, since it's the news.
        item(key = "releases") { VSpace(8); ReleasesCard(vm, state) }
        activityInsights(vm, state)
        item(key = "log-head") {
            // Kinds of problem, not every repeat of one (the same failure on each sync counts once),
            // and being offline isn't one.
            val recentProblems = state.log
                .filter { it.error && System.currentTimeMillis() - it.time < 86_400_000L && it.message != io.github.prithirajn.trackersync.sync.SyncEngine.OFFLINE_NOTE }
                .distinctBy { it.service to it.message }
                .size
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 20.dp, bottom = 2.dp)
                    .bouncyClickable(CpShapes.control, pressedScale = 0.98f, role = androidx.compose.ui.semantics.Role.Button, onClickLabel = if (logOpen) "Hide sync log" else "Show sync log") { logOpen = !logOpen }
                    // Room inside the rounded tap area, so its corners never clip the text.
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Sync log", style = CpType.bodyStrong, color = c.text)
                    Quiet(
                        when {
                            state.log.isEmpty() -> "Nothing yet; it fills in after the first sync."
                            recentProblems > 0 -> "$recentProblems kind${if (recentProblems == 1) "" else "s"} of problem in the last day · tap to see"
                            else -> "${state.log.size} entries · last ${ago(state.log.maxOf { it.time })}"
                        },
                        color = if (recentProblems > 0) c.errorText else c.quiet,
                    )
                }
                LucideIcon(
                    R.drawable.lucide_chevron_right,
                    Modifier.size(18.dp).graphicsLayer { rotationZ = if (logOpen) 90f else 0f },
                    c.faint,
                )
            }
        }
        if (logOpen) {
        if (state.log.isNotEmpty()) {
            item {
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val allOn = filterMode == "all"
                    Box(Modifier.bouncyClickable(CpShapes.pill, pressedScale = 0.92f) { filterMode = "all" }) {
                        Chip("All (${state.log.size})", color = if (allOn) c.accentText else c.quiet, background = if (allOn) c.accentTint else c.divider)
                    }

                    val errOn = filterMode == "errors"
                    Box(Modifier.bouncyClickable(CpShapes.pill, pressedScale = 0.92f) { filterMode = if (errOn) "all" else "errors" }) {
                        Chip(
                            // Kinds of problem, not every repeat of them.
                            "Problems" + if (errorCount > 0) " ($problemKinds)" else "",
                            color = if (errOn) c.errorText else if (errorCount > 0) c.errorText else c.quiet,
                            background = if (errOn) c.accentTint else c.divider,
                        )
                    }

                    for ((id, name) in servicesInLog) {
                        val svcOn = filterMode == "service:$id"
                        Box(Modifier.bouncyClickable(CpShapes.pill, pressedScale = 0.92f) { filterMode = if (svcOn) "all" else "service:$id" }) {
                            Chip(name, color = if (svcOn) c.accentText else c.quiet, background = if (svcOn) c.accentTint else c.divider)
                        }
                    }
                }
            }
        }
        if (state.log.isEmpty()) {
            item { Centered { Quiet("Nothing yet. Activity shows up after the first sync.") } }
        } else if (filteredLog.isEmpty()) {
            item {
                if (filterMode == "errors") {
                    CpCard(Modifier.padding(horizontal = 16.dp)) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp, horizontal = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            LucideIcon(R.drawable.lucide_circle_check, Modifier.size(28.dp), c.accentText)
                            VSpace(10)
                            Text("No problems reported", style = CpType.bodyStrong, color = c.text)
                            VSpace(4)
                            Text("Recent syncs completed without any errors.", style = CpType.small, color = c.quiet)
                        }
                    }
                } else {
                    Centered { Quiet("No activity logged for this filter.") }
                }
            }
        } else {
            // One list item per entry, so a long log scrolls smoothly (it was all drawn at once).
            itemsIndexed(filteredLog, key = { _, (e, _) -> "${e.time}|${e.service}|${e.error}|${e.message.hashCode()}" }) { i, (e, times) ->
                val first = i == 0
                val last = i == filteredLog.lastIndex
                val shape = androidx.compose.foundation.shape.RoundedCornerShape(
                    topStart = if (first) 16.dp else 0.dp, topEnd = if (first) 16.dp else 0.dp,
                    bottomStart = if (last) 16.dp else 0.dp, bottomEnd = if (last) 16.dp else 0.dp,
                )
                Column(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .background(c.surface, shape)
                ) {
                    if (!first) RowDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                        val offline = e.message == io.github.prithirajn.trackersync.sync.SyncEngine.OFFLINE_NOTE
                        LucideIcon(
                            when { offline -> R.drawable.lucide_wifi; e.error -> R.drawable.lucide_triangle_alert; else -> R.drawable.lucide_circle_check },
                            Modifier.padding(top = 2.dp).size(14.dp),
                            when { offline -> c.quiet; e.error -> c.errorText; else -> c.accentText },
                        )
                        HSpace(10)
                        Column(Modifier.weight(1f)) {
                            Text(e.message, style = CpType.body, color = c.text)
                            Quiet(
                                listOfNotNull(
                                    e.service?.let { id -> Trackers.byId(id)?.name ?: if (id.equals("crosspoint", ignoreCase = true)) "CrossPoint" else id.replaceFirstChar { it.uppercase() } },
                                    if (times > 1) "$times times, last ${ago(e.time)}" else ago(e.time),
                                ).joinToString(" · ")
                            )
                        }
                    }
                }
            }
        }
        }
    }
}
