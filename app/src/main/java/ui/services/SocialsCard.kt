package io.github.prithirajn.readync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.sp
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.sync.Socials
import io.github.prithirajn.readync.sync.XQueue
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.CpType

/**
 * Social networks, as connectors (like trackers): Services › Share & export lists only the ones
 * connected, each opening its own page; the rest wait in "Add a social network". Threads posts your
 * highlights by itself; Bluesky and Mastodon get the same posts; X gets a daily one-tap; the full
 * edition adds its own (Instagram).
 */
internal object SocialNets {
    class Net(val id: String, val name: String, val hint: String)

    fun all(): List<Net> = listOf(
        Net("threads", "Threads", "Your new highlights, reviews and recaps, posted by themselves"),
        Net("bluesky", "Bluesky", "The same posts as Threads, with an app password"),
        Net("mastodon", "Mastodon", "The same posts as Threads, on any server (wandering.shop suits book people)"),
    ) + ProPosts.networks.map { Net(it.id, it.name, it.hint) } +
        Net("x", "X", "Each post waits, and a daily notification opens X with it filled in")

    fun connected(s: Snapshot, id: String): Boolean {
        val c = Socials.config(s)
        return when (id) {
            "threads" -> s.threadsToken != null
            "bluesky" -> c.bluesky.did != null && c.bluesky.appPassword.isNotBlank()
            "mastodon" -> c.mastodon.account != null && c.mastodon.token.isNotBlank()
            "x" -> c.x.on
            else -> ProPosts.connected(s, id)
        }
    }

    fun summary(s: Snapshot, id: String): String {
        val c = Socials.config(s)
        fun onOff(on: Boolean) = if (on) "posting" else "posting off"
        return when (id) {
            "threads" -> "@${s.threadsUser ?: "you"} · " + ThreadsAuto.postingCount(s).let { n -> if (n > 0) "$n book${if (n == 1) "" else "s"} posting" else "no books posting" }
            "bluesky" -> "@${c.bluesky.handle.removePrefix("@")} · ${onOff(c.bluesky.on)}"
            "mastodon" -> "${c.mastodon.account ?: c.mastodon.server} · ${onOff(c.mastodon.on)}"
            "x" -> "Daily at ${"%02d:%02d".format(c.x.hour, c.x.minute)}"
            else -> ProPosts.summary(s, id)
        }
    }

    fun title(id: String): String = all().firstOrNull { it.id == id }?.name ?: "Social network"
}

/** The connected networks (a row each, opening its page), and adding another. */
@Composable
fun SocialsList(vm: AppViewModel, state: Snapshot, onOpen: (String) -> Unit) {
    val c = Cp.colors
    val nets = SocialNets.all()
    val linked = nets.filter { SocialNets.connected(state, it.id) }
    val more = nets.filter { !SocialNets.connected(state, it.id) }
    var adding by remember { mutableStateOf(false) }
    if (linked.isNotEmpty()) {
        RisoList {
            linked.forEachIndexed { i, n ->
                if (i > 0) RowDivider()
                Row(
                    Modifier.fillMaxWidth().bouncyClickable(androidx.compose.ui.graphics.RectangleShape, pressedScale = 0.985f, onClickLabel = n.name) { onOpen(n.id) }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ServiceTile(n.name, 40.dp)
                    HSpace(14)
                    Column(Modifier.weight(1f)) {
                        Text(n.name, fontFamily = io.github.prithirajn.readync.ui.theme.DisplaySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 17.sp, color = c.text)
                        Quiet(SocialNets.summary(state, n.id), color = c.textMid, maxLines = 1)
                    }
                    LucideIcon(io.github.prithirajn.readync.R.drawable.lucide_chevron_right, Modifier.size(18.dp), c.faint)
                }
            }
        }
    }
    if (more.isNotEmpty()) {
        if (linked.isNotEmpty()) VSpace(12)
        AddServiceRow(if (linked.isEmpty()) "Connect a social network" else "Add a social network") { adding = true }
    }
    if (adding) {
        SettingsSheet("Add a social network", onClose = { adding = false }) {
            CpCard {
                more.forEachIndexed { i, n ->
                    if (i > 0) RowDivider()
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ServiceTile(n.name)
                        HSpace(12)
                        Column(Modifier.weight(1f)) {
                            Text(n.name, style = CpType.bodyStrong, color = c.text)
                            Quiet(n.hint, maxLines = 2)
                        }
                        PillButton("Connect", { adding = false; onOpen(n.id) })
                    }
                }
            }
            VSpace(14)
        }
    }
}

/** One network's own page: its connection and its settings. */
@Composable
fun SocialPage(vm: AppViewModel, state: Snapshot, id: String) {
    val c = Cp.colors
    val cfg = remember(state.extras) { Socials.config(state) }
    when (id) {
        "bluesky", "mastodon" -> {
            Quiet(
                "Gets whatever goes up on Threads (highlights, reading sessions, reviews and recaps, with the same words and pictures) once you switch it on, " +
                    "following your Threads choices: which books, when, and your “Built with Readync” line." +
                    if (state.threadsToken == null) " Connect Threads first; it follows it." else "",
                color = c.faint,
            )
            VSpace(14)
            if (id == "bluesky") BlueskySection(vm, cfg) else MastodonSection(vm, cfg)
        }
        "x" -> XSection(vm, cfg)
        else -> ProPosts.Page(vm, state, id)
    }
}

@Composable
internal fun SocialHeader(title: String, signedIn: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Cp.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            // The page is already headed with the network's name: the account, and whether it's posting.
            Text(signedIn ?: "Not connected", style = CpType.bodyStrong, color = c.text, maxLines = 1)
            if (signedIn != null) Quiet(if (on) "Posting" else "Paused: nothing goes up to $title", color = c.textMid, maxLines = 1)
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
            Text("One tap a day", style = CpType.bodyStrong, color = c.text)
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


/**
 * Readync on another phone too, and that one sends (progress, highlights, reviews, exports, posts):
 * said at the top of Services, with a way to make this phone the one. Nothing at all with one phone.
 */
@Composable
internal fun PostingDeviceCard(vm: AppViewModel, state: Snapshot) {
    val c = Cp.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    if (io.github.prithirajn.readync.sync.PostingDevice.isThis(context, state)) return
    var asking by remember { mutableStateOf(false) }
    val other = io.github.prithirajn.readync.sync.PostingDevice.name(state) ?: "your other phone"
    CpCard {
        Column(Modifier.padding(16.dp)) {
            Text("$other sends", style = CpType.bodyStrong, color = c.text)
            VSpace(4)
            Quiet("Readync is on more than one phone, so only $other sends your progress, highlights and posts; nothing goes out twice. This phone reads and shows everything.", color = c.textMid)
            VSpace(10)
            if (!asking) PillButton("Send from this phone instead", { asking = true })
            else {
                Quiet("Then remove your accounts on $other (or stop using Readync there): phones don't see each other's choice.", color = c.faint)
                VSpace(8)
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    PillButton("Send from this phone", { asking = false; vm.updateSnapshot { s -> io.github.prithirajn.readync.sync.PostingDevice.takeOver(context, s) } }, outlined = false)
                    PillButton("Cancel", { asking = false })
                }
            }
        }
    }
    VSpace(14)
}
