package io.github.prithirajn.trackersync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.prithirajn.trackersync.data.Release
import io.github.prithirajn.trackersync.data.ReleaseSettings
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import io.github.prithirajn.trackersync.ui.theme.CpType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * New & upcoming, on the Activity tab: the next books and volumes in your series (out now, and
 * what's coming with its day), new manga chapters and web-novel chapters. Tap one to open it;
 * hide a series you don't want news of. Its settings say what to look for, where, and what to be told.
 */
@Composable
fun ReleasesCard(vm: AppViewModel, state: Snapshot) {
    val c = Cp.colors
    val set = state.releaseSettings
    var settings by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis() / 1000
    val out = state.releases.values.filter { it.isOut(now) }.sortedByDescending { it.date ?: it.seenAt }
    val coming = state.releases.values.filter { !it.isOut(now) }.sortedBy { it.date }
    var showAll by remember { mutableStateOf(false) }
    Column(PagePadding) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionTitle("New & upcoming")
                Quiet(
                    if (!set.enabled) "Off. Turn it on to hear when the next book in your series is out."
                    else "The next in your series, new chapters, and what's coming.",
                    color = c.faint,
                )
            }
            PillButton("Settings", { settings = true })
        }
        if (set.enabled) {
            VSpace(10)
            CpCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (out.isEmpty() && coming.isEmpty()) {
                        Quiet("Nothing new yet. Series you read are looked at about once a day.", Modifier.padding(vertical = 10.dp), color = c.faint)
                    }
                    if (out.isNotEmpty()) {
                        Text("Out now", style = CpType.label, color = c.textMid, modifier = Modifier.padding(top = 8.dp))
                        for (r in (if (showAll) out else out.take(4))) ReleaseRow(vm, r, now)
                    }
                    if (coming.isNotEmpty()) {
                        Text("Coming", style = CpType.label, color = c.textMid, modifier = Modifier.padding(top = 12.dp))
                        for (r in (if (showAll) coming else coming.take(4))) ReleaseRow(vm, r, now)
                    }
                    if (!showAll && (out.size > 4 || coming.size > 4)) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                            PillButton("Show all ${out.size + coming.size}", { showAll = true })
                        }
                    }
                }
            }
        }
    }
    if (settings) ReleaseSettingsSheet(vm, state) { settings = false }
}

private fun day(sec: Long?) = sec?.let { DateTimeFormatter.ofPattern("d MMM yyyy").format(Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault())) }

@Composable
private fun ReleaseRow(vm: AppViewModel, r: Release, now: Long) {
    val c = Cp.colors
    val context = LocalContext.current
    val whenLine = when {
        r.date == null -> "out now"
        r.date > now -> {
            val days = ((r.date - now) / 86_400L).toInt()
            "out ${day(r.date)}" + when {
                days <= 0 -> " (tomorrow)"
                days < 60 -> " (in $days day${if (days == 1) "" else "s"})"
                else -> ""
            }
        }
        else -> "out ${day(r.date)}"
    }
    Row(
        Modifier.fillMaxWidth()
            .bouncyClickable(CpShapes.card, pressedScale = 0.99f, onClickLabel = "Open") {
                r.url?.let { u -> runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u))) } }
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(r.coverUrl, r.title, 36.dp, 54.dp)
        HSpace(12)
        Column(Modifier.weight(1f)) {
            Text(r.title, style = CpType.body, color = c.text, maxLines = 2)
            val what = when (r.kind) {
                "book", "volume" -> listOfNotNull(r.number?.let { "#$it in ${r.series}" } ?: r.series, r.author).joinToString(" · ")
                else -> r.series
            }
            Quiet(what, color = c.faint, maxLines = 1)
            Text("$whenLine · ${r.source}", style = CpType.small, color = if (r.isOut(now)) c.accentText else c.textMid, maxLines = 1)
        }
        PillButton("Hide", { vm.hideReleaseSeries(r.series) })
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ReleaseSettingsSheet(vm: AppViewModel, state: Snapshot, onClose: () -> Unit) {
    val c = Cp.colors
    val s = state.releaseSettings
    var checking by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    fun set(change: (ReleaseSettings) -> ReleaseSettings) = vm.setReleaseSettings(change)
    @Composable
    fun toggle(label: String, note: String?, on: Boolean, enabled: Boolean = true, flip: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = CpType.body, color = if (enabled) c.text else c.faint)
                note?.let { Quiet(it, color = c.faint) }
            }
            CpToggle(on, flip, label, enabled = enabled)
        }
    }
    @Composable
    fun <T> choices(options: List<Pair<String, T>>, picked: T, pick: (T) -> Unit) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, v) in options) {
                val on = v == picked
                Box(
                    Modifier.clip(CpShapes.pill).background(if (on) c.accentTint else c.divider)
                        .bouncyClickable(CpShapes.pill, pressedScale = 0.94f, role = Role.RadioButton, onClickLabel = label) { pick(v) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) { Text(label, style = CpType.label, color = if (on) c.accentText else c.textMid) }
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            Text("New & upcoming", style = CpType.section, color = c.text)
            VSpace(8)
            toggle("Look for new releases", "About once a day per series, during syncs.", s.enabled) { v -> set { it.copy(enabled = v) } }
            val on = s.enabled

            VSpace(12); Text("What to look for", style = CpType.label, color = c.textMid)
            toggle("Next book in a series", "Novels: the book after the one you're on or finished", s.novels, on) { v -> set { it.copy(novels = v) } }
            toggle("Manga", "The next volume, and new chapters on MangaDex", s.manga, on) { v -> set { it.copy(manga = v) } }
            toggle("Comics", "The next collected volume", s.comics, on) { v -> set { it.copy(comics = v) } }
            toggle("Web novels", "New chapters of ones you've caught up on", s.webNovels, on) { v -> set { it.copy(webNovels = v) } }
            toggle("In-between books", "Novellas and side stories (Book 2.5)", s.inBetween, on) { v -> set { it.copy(inBetween = v) } }

            VSpace(12); Text("Where to look", style = CpType.label, color = c.textMid)
            val hcOn = state.service("hardcover").linked
            val grOn = state.service("goodreads").linked
            toggle("Hardcover", if (hcOn) "Series lists with release days" else "Link Hardcover under Services to use it", s.hardcover, on && hcOn) { v -> set { it.copy(hardcover = v) } }
            toggle("Goodreads", if (grOn) "Series pages, including books announced but not out yet" else "Link Goodreads under Services to use it", s.goodreads, on && grOn) { v -> set { it.copy(goodreads = v) } }
            toggle("MangaDex", "New manga chapters in English", s.mangaDex, on) { v -> set { it.copy(mangaDex = v) } }

            VSpace(12); Text("Which series", style = CpType.label, color = c.textMid)
            VSpace(6)
            choices(listOf("Ones I'm reading or have read" to "reading", "Everything in my library" to "all"), s.which) { v -> set { it.copy(which = v) } }

            VSpace(14); Text("Show what's coming up to", style = CpType.label, color = c.textMid)
            VSpace(6)
            choices(listOf("3 months" to 3, "6 months" to 6, "1 year" to 12, "2 years" to 24, "Any time" to 600), s.aheadMonths) { v -> set { it.copy(aheadMonths = v) } }

            VSpace(14); Text("Tell me", style = CpType.label, color = c.textMid)
            toggle("When it's out", "A notification on the day (or when a new chapter appears)", s.notifyOut, on) { v -> set { it.copy(notifyOut = v) } }
            VSpace(4); Quiet("A reminder before it's out:", color = c.faint); VSpace(6)
            choices(listOf("None" to 0, "1 day" to 1, "3 days" to 3, "1 week" to 7, "1 month" to 30), s.remindDays) { v -> set { it.copy(remindDays = v) } }

            if (state.releasesHidden.isNotEmpty()) {
                VSpace(14); Text("Hidden series", style = CpType.label, color = c.textMid)
                for (h in state.releasesHidden.sorted()) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(h.replaceFirstChar { it.uppercase() }, style = CpType.body, color = c.text, modifier = Modifier.weight(1f))
                        PillButton("Show", { vm.unhideReleaseSeries(h) })
                    }
                }
            }
            VSpace(16)
            BigButton(if (checking) "Looking…" else "Look now", {
                checking = true; said = null
                vm.checkReleasesNow { n -> checking = false; said = if (n == 0) "Nothing new found." else "$n new." }
            }, busy = checking, enabled = on && !checking, filled = false)
            said?.let { VSpace(6); Quiet(it, color = c.faint) }
        }
    }
}
