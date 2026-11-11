package io.github.prithirajn.trackersync.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpType

/** Highlights, quotes and notes sent on to Readwise, Notion and an Obsidian vault after each sync. */
@Composable
fun ExportsCard(vm: AppViewModel, state: Snapshot) {
    val c = Cp.colors
    var busy by remember { mutableStateOf(false) }
    Quiet("Every highlight, quote and note (with your note on it) goes on after each sync, from every book and every source. Your trackers aren't involved.", color = c.faint)
    VSpace(16)
    ReadwiseExport(vm, state)
    VSpace(12)
    NotionExport(vm, state)
    VSpace(12)
    ObsidianExport(vm, state)
    if (state.readwiseToken != null || state.notionToken != null || state.obsidianFolder != null) {
        VSpace(16)
        BigButton("Export now", { busy = true; vm.exportNow { busy = false } }, busy = busy)
    }
}

@Composable
private fun ExportStatus(state: Snapshot, name: String, connected: String) {
    val c = Cp.colors
    Text(connected, style = CpType.bodyStrong, color = c.text)
    val err = state.exportErrors[name]
    if (err != null) Quiet(err, color = c.errorText, maxLines = 3)
    else state.exportOkAt[name]?.let { Quiet("Up to date ${ago(it * 1000)}", color = c.faint, maxLines = 1) }
}

@Composable
private fun ReadwiseExport(vm: AppViewModel, state: Snapshot) {
    val c = Cp.colors
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    CpCard {
        Column(Modifier.padding(16.dp)) {
            if (state.readwiseToken != null) {
                ExportStatus(state, "readwise", "Readwise")
                Quiet("${state.readwiseDone.size} sent. Each goes once, with your note; books are matched by title and author there.", color = c.faint)
                VSpace(10)
                PillButton("Stop", { vm.stopExport("readwise") }, danger = true)
            } else {
                Text("Readwise", style = CpType.bodyStrong, color = c.text)
                Quiet("Into your Readwise library (and Reader), for daily review and its own exports. Get your token at readwise.io/access_token.", color = c.faint)
                VSpace(10)
                CpTextField(token, { token = it.trim(); error = null }, "Access token", mono = true)
                error?.let { VSpace(6); Text(it, style = CpType.small, color = c.errorText) }
                VSpace(10)
                BigButton("Connect", { busy = true; vm.connectReadwise(token) { e -> busy = false; error = e; if (e == null) token = "" } }, busy = busy, enabled = token.length > 10)
            }
        }
    }
}

@Composable
private fun NotionExport(vm: AppViewModel, state: Snapshot) {
    val c = Cp.colors
    var token by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    CpCard {
        Column(Modifier.padding(16.dp)) {
            if (state.notionToken != null) {
                ExportStatus(state, "notion", "Notion")
                Quiet("${state.notionPages.size} book page${if (state.notionPages.size == 1) "" else "s"}, ${state.notionDone.size} highlights. A page per book, with its cover; new ones are added at the bottom.", color = c.faint)
                VSpace(10)
                PillButton("Stop", { vm.stopExport("notion") }, danger = true)
            } else {
                Text("Notion", style = CpType.bodyStrong, color = c.text)
                Quiet("A page per book, inside a page of yours.", color = c.faint)
                VSpace(8)
                Quiet("1. At notion.so/my-integrations, make a new internal integration and copy its secret.\n2. Make (or pick) a page for your highlights, then ⋯ › Connections › add that integration.\n3. Copy the page's link (Share › Copy link) and paste both here.", color = c.faint)
                VSpace(10)
                CpTextField(token, { token = it.trim(); error = null }, "Integration secret", placeholder = "ntn_…", mono = true)
                VSpace(8)
                CpTextField(link, { link = it.trim(); error = null }, "Page link", placeholder = "https://www.notion.so/…")
                error?.let { VSpace(6); Text(it, style = CpType.small, color = c.errorText) }
                VSpace(10)
                BigButton("Connect", { busy = true; vm.connectNotion(token, link) { e -> busy = false; error = e; if (e == null) { token = ""; link = "" } } }, busy = busy, enabled = token.length > 10 && link.isNotBlank())
            }
        }
    }
}

@Composable
private fun ObsidianExport(vm: AppViewModel, state: Snapshot) {
    val c = Cp.colors
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { vm.setObsidianFolder(it) } }
    CpCard {
        Column(Modifier.padding(16.dp)) {
            if (state.obsidianFolder != null) {
                ExportStatus(state, "obsidian", "Obsidian")
                Quiet("${state.obsidianHashes.size} book note${if (state.obsidianHashes.size == 1) "" else "s"} in ${folderName(state.obsidianFolder)}. Readync rewrites only its own part of each note; anything you write above or below it stays.", color = c.faint)
                VSpace(10)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Change folder", { pick.launch(null) })
                    PillButton("Stop", { vm.stopExport("obsidian") }, danger = true)
                }
            } else {
                Text("Obsidian", style = CpType.bodyStrong, color = c.text)
                Quiet("A Markdown note per book in your vault: highlights as quotes, your notes as callouts, with the book's details as properties. Pick a folder inside your vault (say, Books/Highlights); synced vaults (Obsidian Sync, Syncthing, Drive) carry it to your other devices.", color = c.faint)
                VSpace(10)
                PillButton("Choose a folder in your vault", { pick.launch(null) })
            }
        }
    }
}

private fun folderName(uri: String?): String =
    uri?.let { android.net.Uri.decode(it).substringAfterLast(':').substringAfterLast('/').ifBlank { null } }?.let { "“$it”" } ?: "your folder"
