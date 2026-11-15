package io.github.prithirajn.readync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.sync.Socials
import io.github.prithirajn.readync.sync.XQueue
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.CpType

/**
 * Services › Post elsewhere: Bluesky and Mastodon get the same posts as Threads, each
 * once signed in and switched on; X gets a daily notification that opens it filled in.
 */
@Composable
fun SocialsCard(vm: AppViewModel, state: Snapshot) {
    val c = Cp.colors
    val cfg = remember(state.extras) { Socials.config(state) }
    Quiet(
        "Bluesky and Mastodon get whatever goes up on Threads (highlights, reading sessions, reviews and recaps, with the same words and pictures), each once you switch it on. " +
            "They follow your Threads choices: which books, when, and your “Built with Readync” line." +
            if (state.threadsToken == null) " Connect Threads first; they follow it." else "",
        color = c.faint,
    )
    VSpace(16)
    BlueskySection(vm, cfg)
    VSpace(14); RowDivider(); VSpace(14)
    MastodonSection(vm, cfg)
    VSpace(14); RowDivider(); VSpace(14)
    // The full edition's own networks (nothing in the shared edition).
    if (ProPosts.available) { ProPosts.Settings(vm, state); VSpace(14); RowDivider(); VSpace(14) }
    XSection(vm, cfg)
}

@Composable
internal fun SocialHeader(title: String, signedIn: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Cp.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = CpType.bodyStrong, color = c.text)
            Quiet(signedIn ?: "Not connected", color = if (signedIn != null) c.textMid else c.faint, maxLines = 1)
        }
        if (signedIn != null) CpToggle(on, onChange, "Post to $title")
    }
}

@Composable
private fun BlueskySection(vm: AppViewModel, cfg: Socials.Config) {
    val c = Cp.colors
    val b = cfg.bluesky
    val connected = b.did != null && b.appPassword.isNotBlank()
    var handle by remember { mutableStateOf(b.handle) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    SocialHeader("Bluesky", if (connected) "@${b.handle.removePrefix("@")}" else null, b.on) { on -> vm.updateSocials { it.copy(bluesky = it.bluesky.copy(on = on)) } }
    if (!connected) {
        VSpace(8)
        Quiet("Use an app password, not your real one: in Bluesky, Settings › Privacy and security › App passwords › Add. Up to 4 pictures and 300 characters a post; longer words are cut short with the link kept.", color = c.faint)
        VSpace(8)
        CpTextField(handle, { handle = it.trim() }, "Handle", placeholder = "you.bsky.social")
        VSpace(6)
        CpTextField(password, { password = it.trim() }, "App password", placeholder = "xxxx-xxxx-xxxx-xxxx", password = true)
        VSpace(8)
        BigButton("Connect Bluesky", {
            busy = true; said = null
            vm.connectBluesky(handle, password) { err -> busy = false; said = err ?: "Connected"; if (err == null) password = "" }
        }, busy = busy, enabled = handle.isNotBlank() && password.isNotBlank())
    } else {
        VSpace(6)
        PillButton("Disconnect", { vm.updateSocials { it.copy(bluesky = Socials.Bluesky()) } })
    }
    said?.let { VSpace(6); Quiet(it, color = c.textMid) }
}

@Composable
private fun MastodonSection(vm: AppViewModel, cfg: Socials.Config) {
    val c = Cp.colors
    val m = cfg.mastodon
    val connected = m.account != null && m.token.isNotBlank()
    var server by remember { mutableStateOf(m.server.ifBlank { "" }) }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    SocialHeader("Mastodon", m.account, m.on) { on -> vm.updateSocials { it.copy(mastodon = it.mastodon.copy(on = on)) } }
    if (!connected) {
        VSpace(8)
        Quiet(
            "Which server: wandering.shop suits you best, a server for readers and writers of science fiction and fantasy, with many SFF authors and publishers on it. " +
                "For a general one, mastodon.social (the biggest) or bookstodon.com. Wherever you join, use the #Bookstodon tag and book people anywhere will find you.",
            color = c.textMid,
        )
        VSpace(8)
        Quiet("Then make a token there: Preferences › Development › New application, tick write:statuses, write:media and read:accounts, save, and copy “Your access token”.", color = c.faint)
        VSpace(8)
        CpTextField(server, { server = it.trim() }, "Server", placeholder = "wandering.shop", keyboard = KeyboardType.Uri)
        VSpace(6)
        CpTextField(token, { token = it.trim() }, "Access token", password = true)
        VSpace(8)
        BigButton("Connect Mastodon", {
            busy = true; said = null
            vm.connectMastodon(server, token) { err -> busy = false; said = err ?: "Connected"; if (err == null) token = "" }
        }, busy = busy, enabled = server.isNotBlank() && token.isNotBlank())
    } else {
        VSpace(6)
        Quiet("A post that gives the story away goes behind a content warning.", color = c.faint)
        VSpace(6)
        PillButton("Disconnect", { vm.updateSocials { it.copy(mastodon = Socials.Mastodon()) } })
    }
    said?.let { VSpace(6); Quiet(it, color = c.textMid) }
}

@Composable
private fun XSection(vm: AppViewModel, cfg: Socials.Config) {
    val c = Cp.colors
    val context = LocalContext.current
    val x = cfg.x
    var said by remember { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("X (one tap)", style = CpType.bodyStrong, color = c.text)
            Quiet(if (x.on) "Daily at ${"%02d:%02d".format(x.hour, x.minute)} · ${XQueue.count(context)} waiting" else "Off", color = if (x.on) c.textMid else c.faint, maxLines = 1)
        }
        CpToggle(x.on, { on -> vm.setXDaily(on, x.hour, x.minute) }, "X daily")
    }
    VSpace(6)
    Quiet("X doesn't let other apps post without its paid API, so each post waits here, and once a day a notification opens X with it filled in: tap Post and it's up.", color = c.faint)
    if (x.on) {
        VSpace(8)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("At", style = CpType.body, color = c.text)
            for (h in listOf(9, 13, 19, 21)) PillButton("%02d:00".format(h), { vm.setXDaily(true, h, 0) }, outlined = x.hour != h || x.minute != 0)
        }
        VSpace(8)
        PillButton("Open X with the next one now", { said = XQueue.open(context) })
        said?.let { VSpace(6); Quiet(it, color = c.textMid) }
    }
}
