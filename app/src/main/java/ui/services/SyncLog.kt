package io.github.prithirajn.readync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.prithirajn.readync.R
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.sync.SyncEngine
import io.github.prithirajn.readync.trackers.Trackers
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.CpShapes
import io.github.prithirajn.readync.ui.theme.CpType
import kotlinx.coroutines.launch

/**
 * The sync log, as the Sync tab's Log page: a line on the last day's problems, filters (all,
 * problems, one service each), then the entries, newest first. The same message again and again
 * shows once, with how many times and when last. A long log shows its newest 150 until asked.
 */
@Composable
fun SyncLogPanel(state: Snapshot) {
    val c = Cp.colors
    var filterMode by rememberSaveable { mutableStateOf("all") }
    var showAll by remember { mutableStateOf(false) }
    fun nameOf(id: String) = Trackers.byId(id)?.name ?: if (id.equals("crosspoint", ignoreCase = true)) "CrossPoint" else id.replaceFirstChar { it.uppercase() }
    val servicesInLog = remember(state.log) { state.log.mapNotNull { it.service }.distinct().map { it to nameOf(it) } }
    val filtered = remember(state.log, filterMode) {
        val picked = when {
            filterMode == "errors" -> state.log.filter { it.error }
            filterMode.startsWith("service:") -> filterMode.removePrefix("service:").let { svc -> state.log.filter { it.service == svc } }
            else -> state.log
        }
        picked.groupBy { Triple(it.service, it.message, it.error) }.values
            .map { same -> same.maxBy { it.time } to same.size }
            .sortedByDescending { it.first.time }
    }
    val problemKinds = remember(state.log) { state.log.filter { it.error }.distinctBy { it.service to it.message }.size }
    val recentProblems = remember(state.log) {
        state.log.filter { it.error && System.currentTimeMillis() - it.time < 86_400_000L && it.message != SyncEngine.OFFLINE_NOTE }
            .distinctBy { it.service to it.message }.size
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var gathering by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        // Everything about how syncing stands (never passwords or keys) as a text file to send, for finding why something doesn't go through.
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, bottom = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Quiet("Something not syncing? Share a diagnostics file.", Modifier.weight(1f), color = c.faint)
            PillButton(if (gathering) "Gathering…" else "Share diagnostics", {
                if (!gathering) {
                    gathering = true
                    scope.launch {
                        val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                val app = context.applicationContext as io.github.prithirajn.readync.ReadyncApp
                                io.github.prithirajn.readync.sync.Diagnostics.build(context, app.store)
                            }.getOrElse { "Couldn't gather diagnostics: ${it.message}" }
                        }
                        gathering = false
                        runCatching {
                            val dir = java.io.File(context.cacheDir, "shares").apply { mkdirs() }
                            val f = java.io.File(dir, "readync-diagnostics.txt").apply { writeText(text) }
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".pages", f)
                            context.startActivity(android.content.Intent.createChooser(
                                android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                "Share diagnostics",
                            ).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }
                    }
                }
            })
        }
        Quiet(
            when {
                state.log.isEmpty() -> "Nothing yet; it fills in after the first sync."
                recentProblems > 0 -> "$recentProblems kind${if (recentProblems == 1) "" else "s"} of problem in the last day"
                else -> "${state.log.size} entries · last ${ago(state.log.maxOf { it.time })}"
            },
            Modifier.padding(start = 6.dp, bottom = 10.dp),
            color = if (recentProblems > 0) c.errorText else c.quiet,
        )
        if (state.log.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val allOn = filterMode == "all"
                Box(Modifier.bouncyClickable(CpShapes.pill, pressedScale = 0.92f) { filterMode = "all" }) {
                    Chip("All (${state.log.size})", color = if (allOn) c.accentText else c.quiet, background = if (allOn) c.accentTint else c.divider)
                }
                val errOn = filterMode == "errors"
                Box(Modifier.bouncyClickable(CpShapes.pill, pressedScale = 0.92f) { filterMode = if (errOn) "all" else "errors" }) {
                    Chip("Problems" + if (problemKinds > 0) " ($problemKinds)" else "", color = if (errOn || problemKinds > 0) c.errorText else c.quiet, background = if (errOn) c.accentTint else c.divider)
                }
                for ((id, name) in servicesInLog) {
                    val on = filterMode == "service:$id"
                    Box(Modifier.bouncyClickable(CpShapes.pill, pressedScale = 0.92f) { filterMode = if (on) "all" else "service:$id" }) {
                        Chip(name, color = if (on) c.accentText else c.quiet, background = if (on) c.accentTint else c.divider)
                    }
                }
            }
        }
        when {
            state.log.isEmpty() -> Unit
            filtered.isEmpty() -> Quiet(if (filterMode == "errors") "No problems reported: recent syncs went through." else "Nothing logged for this filter.", Modifier.padding(6.dp))
            else -> {
                val list = if (showAll) filtered else filtered.take(150)
                Column(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(16.dp))) {
                    list.forEachIndexed { i, (e, times) ->
                        if (i > 0) RowDivider()
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                            val offline = e.message == SyncEngine.OFFLINE_NOTE
                            LucideIcon(
                                when { offline -> R.drawable.lucide_wifi; e.error -> R.drawable.lucide_triangle_alert; else -> R.drawable.lucide_circle_check },
                                Modifier.padding(top = 2.dp).size(14.dp),
                                when { offline -> c.quiet; e.error -> c.errorText; else -> c.accentText },
                            )
                            HSpace(10)
                            Column(Modifier.weight(1f)) {
                                Text(e.message, style = CpType.body, color = c.text)
                                Quiet(listOfNotNull(e.service?.let { nameOf(it) }, if (times > 1) "$times times, last ${ago(e.time)}" else ago(e.time)).joinToString(" · "))
                            }
                        }
                    }
                }
                if (!showAll && filtered.size > 150) {
                    VSpace(10)
                    PillButton("Show all ${filtered.size}", { showAll = true })
                }
            }
        }
    }
}
