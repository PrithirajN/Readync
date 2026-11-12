package io.github.prithirajn.trackersync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.prithirajn.trackersync.data.SffFeedItem
import io.github.prithirajn.trackersync.data.SffRead
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.source.SffSources
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import io.github.prithirajn.trackersync.ui.theme.CpType
import io.github.prithirajn.trackersync.ui.theme.Lora

/**
 * Saved: the stories you kept for later, the ones you've read lately, the big-news alerts, where
 * the news comes from (and the outlets you've hidden), and the social accounts you follow.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SffSaved(
    vm: AppViewModel,
    state: Snapshot,
    onOpenRead: (SffRead) -> Unit,
    onOpenItem: (SffFeedItem) -> Unit,
) {
    val c = Cp.colors
    val sky = state.sffSky
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (sky.saved.isEmpty()) {
            CpCard {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("Nothing saved yet", style = CpType.bodyStrong, color = c.text)
                    Quiet("Save a story from its ⋯ menu or the reader, and it waits here.")
                }
            }
        } else {
            SectionTitle("Saved for later")
            Quiet("${sky.saved.size} stor${if (sky.saved.size == 1) "y" else "ies"}")
            VSpace(4)
            for (item in sky.saved.take(40)) {
                ItemRow(item.aiHeadline ?: item.title, item.source.substringBefore(" ·"), item.coverUrl) { onOpenItem(item) }
            }
        }

        val history = sky.history.filter { it.seconds >= 10 }
        if (history.isNotEmpty()) {
            VSpace(22)
            SectionTitle("Read lately")
            Quiet("${history.size} stories · ${history.sumOf { it.seconds } / 60} min reading")
            VSpace(4)
            for (h in history.take(20)) {
                ItemRow(h.title, "${h.source.substringBefore(" ·")} · ${ago(h.at * 1000)}", h.coverUrl) { onOpenRead(h) }
            }
        }

        VSpace(22)
        SectionTitle("Alerts")
        SettingRow("Big news about your series and authors") { CpToggle(sky.bigNews, { vm.sffAlerts(bigNews = it) }, "Big news alerts") }
        Quiet("Three a day at most, never spoilers.", color = c.faint)

        VSpace(22)
        Sources(vm, state)

        VSpace(18)
        SocialFollowCard(vm = vm, followed = state.settings.sffSocials)
    }
}

@Composable
private fun ItemRow(title: String, sub: String, cover: String?, onTap: () -> Unit) {
    val c = Cp.colors
    Row(
        Modifier.fillMaxWidth().clip(CpShapes.control).bouncyClickable(CpShapes.control, pressedScale = 0.99f, onClickLabel = "Read") { onTap() }.padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!cover.isNullOrBlank()) {
            StoryImage(cover, Modifier.size(60.dp).clip(RoundedCornerShape(12.dp)))
            HSpace(12)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = Lora, fontWeight = FontWeight.SemiBold, fontSize = 15.5.sp, lineHeight = 20.sp, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Quiet(sub, maxLines = 1)
        }
    }
}

/** Where the news comes from, how each source is doing, and the ones you've hidden. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Sources(vm: AppViewModel, state: Snapshot) {
    val c = Cp.colors
    var open by remember { mutableStateOf(false) }
    val sky = state.sffSky
    val names = SffSources.newsFeeds.map { it.name } + SffSources.subreddits.map { "r/${it.first}" }
    val ok = names.count { (sky.health[it]?.okAt ?: 0L) > 0 && (sky.health[it]?.okAt ?: 0L) >= (sky.health[it]?.failAt ?: 0L) }
    CpCard {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sources", style = CpType.bodyStrong, color = c.text)
                    Quiet("$ok of ${names.size} outlets answering, plus your social accounts, BookTube and your own series.")
                }
                PillButton(if (open) "Hide" else "Show", { open = !open })
            }
            if (sky.blockedSources.isNotEmpty()) {
                VSpace(10)
                Text("Hidden (tap to show again)", style = CpType.small, color = c.quiet)
                VSpace(6)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (b in sky.blockedSources) {
                        Box(Modifier.bouncyClickable(CpShapes.pill, pressedScale = 0.94f, onClickLabel = "Show $b") { vm.sffBlockSource(b) }) { Chip("$b ✕", color = c.accentText, background = c.accentTint) }
                    }
                }
            }
            if (open) {
                VSpace(10)
                for (n in names) {
                    val h = sky.health[n]
                    val status = when {
                        h == null -> "not tried yet"
                        h.okAt >= h.failAt && h.okAt > 0 -> "${h.items} stories · ${ago(h.okAt * 1000)}"
                        else -> "not answering (${ago(h.failAt * 1000)})"
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(if (h != null && h.okAt >= h.failAt && h.okAt > 0) c.good else if (h == null) c.faint else c.warn))
                        HSpace(8)
                        Text(n, style = CpType.small, color = if (n in sky.blockedSources) c.faint else c.text, modifier = Modifier.weight(1f))
                        Text(status, style = CpType.small, color = c.quiet)
                    }
                }
            }
        }
    }
}
