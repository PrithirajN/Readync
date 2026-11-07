package io.github.prithirajn.trackersync.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.prithirajn.trackersync.R
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.data.Visibility
import io.github.prithirajn.trackersync.sync.Health
import io.github.prithirajn.trackersync.sync.AppUpdater
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.prithirajn.trackersync.trackers.DeviceLink
import io.github.prithirajn.trackersync.trackers.HighlightSupport
import io.github.prithirajn.trackersync.trackers.PrivacySupport
import io.github.prithirajn.trackersync.trackers.SignInKind
import io.github.prithirajn.trackersync.trackers.Tracker
import io.github.prithirajn.trackersync.trackers.Trackers
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import io.github.prithirajn.trackersync.ui.theme.CpType

private val hints = mapOf(
    "hardcover" to "API token from hardcover.app",
    "seekquel" to "Approve a code in the Seekquel app",
    "fable" to "Email and password",
    "pagebound" to "Email and password",
    "storygraph" to "Sign in on StoryGraph's own page",
    "goodreads" to "Sign in on Goodreads' own page",
)

@Composable
fun ServicesScreen(vm: AppViewModel, state: Snapshot) {
    var signingIn by remember { mutableStateOf<Tracker?>(null) }
    var unlinking by remember { mutableStateOf<Tracker?>(null) }
    var settings by remember { mutableStateOf<Tracker?>(null) }
    var signingOut by remember { mutableStateOf(false) }
    var addingKosync by remember { mutableStateOf(false) }
    var removingKosync by remember { mutableStateOf(false) }
    var linkingKindle by remember { mutableStateOf(false) }
    var removingKindle by remember { mutableStateOf(false) }
    var linkingLibby by remember { mutableStateOf(false) }
    var linkingAudible by remember { mutableStateOf(false) }
    var removingMihon by remember { mutableStateOf(false) }
    var linkingMu by remember { mutableStateOf(false) }
    var removingMu by remember { mutableStateOf(false) }
    var linkingAl by remember { mutableStateOf(false) }
    var removingAl by remember { mutableStateOf(false) }
    var removingAudible by remember { mutableStateOf(false) }
    var removingLibby by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<String?>(null) }
    val c = Cp.colors
    val linked = Trackers.all.filter { state.service(it.id).linked }
    val more = Trackers.all.filter { !state.service(it.id).linked }
    val context = LocalContext.current
    val appVersion = remember(context) { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }

    val pageScroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .fadeTopEdge(pageScroll.value > 0)
            .verticalScroll(pageScroll)
            .padding(bottom = 32.dp)
    ) {
        Column(PagePadding) {
            PageTitle("Services")
            Quiet("Where your reading progress goes.")
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            if (linked.isNotEmpty()) {
                VSpace(16)
                CpCard {
                    linked.forEachIndexed { i, t ->
                        if (i > 0) RowDivider()
                        LinkedRow(vm, state, t, onOpen = { settings = t }, onRelink = { signingIn = t })
                    }
                }
            }
            if (more.isNotEmpty()) {
                SectionTitle(if (linked.isEmpty()) "Link a tracker" else "More trackers")
                VSpace(12)
                CpCard {
                    more.forEachIndexed { i, t ->
                        if (i > 0) RowDivider()
                        AvailableRow(t, onLink = { signingIn = t })
                    }
                }
            }

            // Everything else as one short list of rows, each opening its own sheet, so the page
            // stays a glance instead of a long scroll.
            SectionTitle("Reading from")
            VSpace(12)
            CpCard {
                NavRow(
                    R.drawable.lucide_library_big, "CrossPoint",
                    listOfNotNull(state.account?.username, state.sourceOkAt.takeIf { it > 0 }?.let { "reached ${ago(it)}" }).joinToString(" · ").ifEmpty { null },
                    trailing = { Health.source(state)?.let { h -> HealthLight(h) { vm.checkCrossPoint() } } },
                ) { page = "crosspoint" }
                RowDivider()
                // Kindle, Libby and Audible: only in the full edition (the shared one leaves them out).
                if (io.github.prithirajn.trackersync.Edition.full) {
                    NavRow(
                        R.drawable.lucide_book_open, "Amazon Kindle",
                        when {
                            state.kindle == null -> "Not linked"
                            state.kindleError != null -> "Needs you to sign in again"
                            else -> state.kindleOkAt.takeIf { it > 0 }?.let { "Checked ${ago(it)}" } ?: "Linked"
                        },
                        summaryWarn = state.kindle != null && state.kindleError != null,
                        trailing = { Health.kindle(state)?.let { h -> HealthLight(h) { vm.checkKindle() } } },
                    ) { page = "kindle" }
                    RowDivider()
                    NavRow(
                        R.drawable.lucide_library_big, "Libby",
                        when {
                            state.libby == null -> "Not linked · or open a Reading Journey"
                            state.libbyError != null -> "Needs you to link again"
                            else -> state.libbyOkAt.takeIf { it > 0 }?.let { "Checked ${ago(it)}" } ?: "Linked"
                        },
                        summaryWarn = state.libby != null && state.libbyError != null,
                        trailing = { Health.libby(state)?.let { h -> HealthLight(h) { vm.checkLibby() } } },
                    ) { page = "libby" }
                    RowDivider()
                    NavRow(
                        R.drawable.lucide_headphones, "Audible",
                        when {
                            state.audible == null -> "Not linked"
                            state.audibleError != null -> "Needs you to sign in again"
                            else -> state.audibleOkAt.takeIf { it > 0 }?.let { "Checked ${ago(it)}" } ?: "Linked"
                        },
                        summaryWarn = state.audible != null && state.audibleError != null,
                        trailing = { Health.audible(state)?.let { h -> HealthLight(h) { vm.checkAudible() } } },
                    ) { page = "audible" }
                    RowDivider()
                }
                NavRow(
                    R.drawable.lucide_book_open, "Manga & comics",
                    when {
                        state.mihon == null && state.anilist == null -> "Not set up"
                        state.mihonError != null || state.anilistError != null -> "Needs attention"
                        else -> listOfNotNull(state.mihon?.let { "Mihon" }, state.anilist?.let { "AniList" }).joinToString(" + ")
                    },
                    summaryWarn = (state.mihon != null && state.mihonError != null) || (state.anilist != null && state.anilistError != null),
                    trailing = {
                        (Health.mihon(state)?.takeIf { it.level == Health.Level.BAD } ?: Health.anilist(state)?.takeIf { it.level == Health.Level.BAD } ?: Health.mihon(state) ?: Health.anilist(state))
                            ?.let { h -> HealthLight(h) { vm.checkMihon() } }
                    },
                ) { page = "mihon" }
                RowDivider()
                NavRow(
                    R.drawable.lucide_server, "KOSync server",
                    state.kosync?.let { k -> k.username + " · " + k.server.substringAfter("://") } ?: "Not set up",
                    trailing = { Health.kosync(state)?.let { h -> HealthLight(h) { vm.checkKosync() } } },
                ) { page = "kosync" }
            }
            SectionTitle("App")
            VSpace(12)
            CpCard {
                val s0 = state.settings
                val every = s0.intervalMinutes.let { m -> if (m < 60) "every $m min" else if (m == 60) "every hour" else "every ${m / 60} hours" }
                NavRow(
                    R.drawable.lucide_settings, "Sync settings",
                    "Syncs $every" + (if (s0.wifiOnly) " on Wi-Fi" else "") + (if (s0.quietFrom >= 0) " · quiet at night" else ""),
                ) { page = "sync" }
                RowDivider()
                val model = remember(state.settings) { vm.aiModel() }
                NavRow(
                    R.drawable.lucide_sparkles, "On-device AI",
                    model?.first?.substringBeforeLast('.')?.let { "$it · on this phone" } ?: "Off · add a model to try it",
                ) { page = "ai" }
                RowDivider()
                NavRow(R.drawable.lucide_download, "Backup", "Everything in one locked file") { page = "backup" }
                RowDivider()
                NavRow(R.drawable.lucide_refresh_cw, "App update", if (appVersion.isNotEmpty()) "Version $appVersion" else null) { page = "update" }
            }
            VSpace(24)
            Text(
                "${io.github.prithirajn.trackersync.Edition.name} $appVersion · Fonts: Inter, Lora, Caveat (OFL) · Icons: Lucide (ISC) · Requests based on ShelfSync (MIT)",
                style = CpType.small,
                color = c.faint,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            // Optional, no strings attached: everything works the same either way.
            VSpace(10)
            val links = androidx.compose.ui.platform.LocalUriHandler.current
            PillButton("\u2615 Buy me a coffee", { runCatching { links.openUri("https://buymeacoffee.com/prithi") } })
        }
    }

    page?.let { p ->
        SettingsSheet(
            when (p) {
                "crosspoint" -> "CrossPoint account"; "kindle" -> "Amazon Kindle"; "libby" -> "Libby"; "audible" -> "Audible"; "mihon" -> "Manga & comics"; "kosync" -> "KOSync server"
                "sync" -> "Sync settings"; "ai" -> "On-device AI"; "backup" -> "Backup"; else -> "App update"
            },
            onClose = { page = null },
        ) {
            when (p) {
                "crosspoint" -> CpCard {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        ServiceTile(state.account?.username ?: "?", 40.dp)
                        HSpace(12)
                        Column(Modifier.weight(1f)) {
                            Text(state.account?.username ?: "", style = CpType.bodyStrong, color = c.text)
                            Quiet(state.account?.server?.removePrefix("https://") ?: "", maxLines = 1)
                            val since = state.sourceOkAt.takeIf { it > 0 }?.let { "Last reached ${ago(it)}" }
                            since?.let { AnimatedQuiet(it, color = c.faint, maxLines = 1) }
                        }
                        Health.source(state)?.let { h -> HealthLight(h) { vm.checkCrossPoint() } }
                    }
                    RowDivider()
                    Box(Modifier.padding(16.dp)) { BigButton("Sign out", { page = null; signingOut = true }, filled = false, danger = true) }
                }
                "kindle" -> KindleCard(vm, state, onLink = { linkingKindle = true }, onRemove = { page = null; removingKindle = true })
                "mihon" -> MihonCard(vm, state, onRemove = { removingMihon = true }, onLinkMu = { linkingMu = true }, onRemoveMu = { removingMu = true }, onLinkAl = { linkingAl = true }, onRemoveAl = { removingAl = true })
                "audible" -> AudibleCard(vm, state, onLink = { linkingAudible = true }, onRemove = { page = null; removingAudible = true })
                "libby" -> LibbyCard(vm, state, onLink = { linkingLibby = true }, onRemove = { page = null; removingLibby = true })
                "kosync" -> KosyncCard(vm, state, onAdd = { addingKosync = true }, onRemove = { page = null; removingKosync = true })
                "sync" -> SyncSettingsCard(vm, state)
                "ai" -> OnDeviceAiCard(vm, state)
                "backup" -> BackupCard(vm)
                else -> AppUpdateCard(vm, state, appVersion)
            }
        }
    }

    // Only while still linked: unlinking from inside the sheet closes it.
    settings?.takeIf { state.service(it.id).linked }?.let { t ->
        TrackerSheet(vm, state, t, onClose = { settings = null }, onUnlink = { settings = null; unlinking = t })
    }
    signingIn?.let { t ->
        when (t.signInKind) {
            SignInKind.PASSWORD -> PasswordSheet(vm, t) { signingIn = null }
            SignInKind.WEBVIEW -> WebSignIn(vm, t) { signingIn = null }
            SignInKind.TOKEN -> TokenSheet(vm, t) { signingIn = null }
            SignInKind.DEVICE_CODE -> PairSheet(vm, t) { signingIn = null }
        }
    }
    unlinking?.let { t ->
        ConfirmSheet(
            title = "Unlink ${t.name}?",
            body = "Its book matches and any queued syncs are removed. Nothing is changed on ${t.name}.",
            action = "Unlink",
            onConfirm = { vm.unlinkTracker(t) },
            onClose = { unlinking = null },
        )
    }
    if (linkingKindle) KindleSignInSheet(vm) { linkingKindle = false }
    if (removingKindle) {
        ConfirmSheet(
            title = "Disconnect Amazon Kindle?",
            body = "${io.github.prithirajn.trackersync.Edition.name} stops checking Kindle reading progress and highlights. Your books remain in your library.",
            action = "Disconnect",
            onConfirm = { vm.removeKindle() },
            onClose = { removingKindle = false },
        )
    }
    if (linkingAudible) AudibleSignInSheet(vm) { linkingAudible = false }
    if (removingMihon) {
        ConfirmSheet(
            title = "Disconnect Mihon?",
            body = "${io.github.prithirajn.trackersync.Edition.name} stops reading its backups. Your volumes remain in your library.",
            action = "Disconnect",
            onConfirm = { vm.removeMihon() },
            onClose = { removingMihon = false },
        )
    }
    if (linkingMu) MangaUpdatesSheet(vm) { linkingMu = false }
    if (linkingAl) AniListSheet(vm, state) { linkingAl = false }
    if (removingAl) {
        ConfirmSheet(
            title = "Disconnect AniList?",
            body = "Your AniList list stops being read here, and chapters stop going there. Nothing is changed on AniList; your volumes remain in your library.",
            action = "Disconnect",
            onConfirm = { vm.removeAniList() },
            onClose = { removingAl = false },
        )
    }
    if (removingMu) {
        ConfirmSheet(
            title = "Disconnect MangaUpdates?",
            body = "Chapters stop going to MangaUpdates. Nothing is changed there.",
            action = "Disconnect",
            onConfirm = { vm.removeMangaUpdates() },
            onClose = { removingMu = false },
        )
    }
    if (removingAudible) {
        ConfirmSheet(
            title = "Disconnect Audible?",
            body = "${io.github.prithirajn.trackersync.Edition.name} stops reading your Audible progress and removes itself from your Audible devices. Your books remain in your library.",
            action = "Disconnect",
            onConfirm = { vm.removeAudible() },
            onClose = { removingAudible = false },
        )
    }
    if (linkingLibby) LibbySignInSheet(vm) { linkingLibby = false }
    if (removingLibby) {
        ConfirmSheet(
            title = "Disconnect Libby?",
            body = "${io.github.prithirajn.trackersync.Edition.name} stops checking your Libby loans. Your books and highlights remain in your library.",
            action = "Disconnect",
            onConfirm = { vm.removeLibby() },
            onClose = { removingLibby = false },
        )
    }
    if (addingKosync) KosyncSheet(vm) { addingKosync = false }
    if (removingKosync) {
        ConfirmSheet(
            title = "Remove the KOSync server?",
            body = "${io.github.prithirajn.trackersync.Edition.name} stops checking it. Nothing changes on the server or on your trackers.",
            action = "Remove",
            onConfirm = { vm.removeKosync() },
            onClose = { removingKosync = false },
        )
    }
    if (signingOut) {
        ConfirmSheet(
            title = "Sign out?",
            body = "${io.github.prithirajn.trackersync.Edition.name} forgets your CrossPoint account, every linked tracker and all book matches on this phone.",
            action = "Sign out",
            onConfirm = { vm.signOut() },
            onClose = { signingOut = false },
        )
    }
}

/** A settings page as a sheet over Services: its title, then its card(s). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    val c = Cp.colors
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
        ) {
            Text(title, style = CpType.section, color = c.text)
            VSpace(14)
            content()
        }
    }
}

private fun isBeta(t: Tracker) = t.signInKind == SignInKind.WEBVIEW || t.signInKind == SignInKind.DEVICE_CODE

/** One line on how a linked tracker is doing, and whether it's bad news. */
private fun summary(state: Snapshot, t: Tracker): Pair<String, Boolean> {
    val svc = state.service(t.id)
    val waiting = state.pending.values.count { it.service == t.id }
    val review = state.links.values.count { it.service == t.id && ((it.externalId == null && it.note == "Needs a match") || (it.externalId != null && !it.confirmed && it.source != io.github.prithirajn.trackersync.data.LinkSource.NONE)) }
    return when {
        svc.needsReauth -> "Sign in again to keep syncing" to true
        !svc.enabled -> "Paused" to false
        svc.lastError != null -> "Last sync had a problem" to true
        waiting > 0 -> "$waiting waiting to sync" + (if (review > 0) " · $review need a match" else "") to false
        review > 0 -> "$review book${if (review == 1) "" else "s"} need a match" to false
        svc.lastSync > 0 -> "Up to date · ${ago(svc.lastSync)}" to false
        else -> "Linked as ${svc.account ?: "you"}" to false
    }
}

@Composable
private fun TrackerName(t: Tracker) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(t.name, style = CpType.bodyStrong, color = Cp.colors.text)
        if (isBeta(t)) Chip("Beta")
    }
}

@Composable
private fun LinkedRow(vm: AppViewModel, state: Snapshot, t: Tracker, onOpen: () -> Unit, onRelink: () -> Unit) {
    val c = Cp.colors
    val svc = state.service(t.id)
    val (line, bad) = summary(state, t)
    Row(
        Modifier
            .fillMaxWidth()
            .bouncyClickable(RectangleShape, pressedScale = 0.985f, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ServiceTile(t.name)
        HSpace(12)
        Column(Modifier.weight(1f)) {
            TrackerName(t)
            val tone by animateColorAsState(if (bad) c.errorText else c.quiet, Motion.medium(), label = "summary")
            AnimatedQuiet(line, color = tone, maxLines = 1)
        }
        Health.tracker(state, t.id, t.name)?.let { h ->
            HealthLight(h) { vm.notify(h.title, h.detail, error = h.level == Health.Level.BAD) }
        }
        HSpace(4)
        if (svc.needsReauth) {
            PillButton("Sign in", onRelink)
        } else {
            // Sync on/off right in the row; everything else is in the tracker's sheet.
            CpToggle(svc.enabled, { vm.setEnabled(t, it) }, "Sync to ${t.name}")
            HSpace(6)
            LucideIcon(R.drawable.lucide_chevron_right, Modifier.size(16.dp), tint = c.faint)
        }
    }
}

@Composable
private fun AvailableRow(t: Tracker, onLink: () -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        ServiceTile(t.name)
        HSpace(12)
        Column(Modifier.weight(1f)) {
            TrackerName(t)
            Quiet(hints[t.id] ?: "", maxLines = 1)
        }
        PillButton("Link", onLink)
    }
}

/** Everything about one linked tracker: what's happening, its settings, and unlinking. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackerSheet(vm: AppViewModel, state: Snapshot, t: Tracker, onClose: () -> Unit, onUnlink: () -> Unit) {
    val c = Cp.colors
    val svc = state.service(t.id)
    val waiting = state.pending.values.count { it.service == t.id }
    val failed = state.pending.values.count { it.service == t.id && it.tries > 0 }
    val review = state.links.values.count { it.service == t.id && ((it.externalId == null && it.note == "Needs a match") || (it.externalId != null && !it.confirmed && it.source != io.github.prithirajn.trackersync.data.LinkSource.NONE)) }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ServiceTile(t.name, 44.dp)
                HSpace(12)
                Column(Modifier.weight(1f)) {
                    Text(t.name, style = CpType.section, color = c.text)
                    Quiet("Linked as ${svc.account ?: "you"}", maxLines = 1)
                }
                IconCircleButton(R.drawable.ic_ribbon_sync, "Sync ${t.name} now", { vm.syncTracker(t) }, busy = state.running)
            }

            // What's happening, only when there's something to say.
            val status = listOfNotNull(
                if (waiting > 0) "$waiting book${if (waiting == 1) "" else "s"} waiting to sync" + (if (failed > 0) ", $failed retrying" else "") else null,
                if (review > 0) "$review need a match: tap the book in Library to pick one" else null,
            )
            AnimatedVisibility(
                visible = status.isNotEmpty() || svc.lastError != null,
                enter = fadeIn(Motion.medium()) + expandVertically(Motion.medium()),
                exit = fadeOut(Motion.fast()) + shrinkVertically(Motion.medium()),
            ) {
                CpCard(Modifier.padding(top = 16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        svc.lastError?.let { Text(it, style = CpType.body, color = c.errorText) }
                        status.forEach { Quiet(it) }
                    }
                }
            }

            VSpace(16)
            CpCard {
                Option("Sync to ${t.name}", null) {
                    CpToggle(svc.enabled, { vm.setEnabled(t, it) }, "Sync to ${t.name}")
                }
                RowDivider()
                Option("Who can see updates", t.privacyNote) {
                    if (t.privacy == PrivacySupport.PRIVATE_ONLY) {
                        // The tracker gives apps no say in who sees updates, so there's nothing to pick.
                        Text("Set in ${t.name}", style = CpType.body, color = c.quiet)
                    } else {
                        CpSelect(
                            value = svc.visibility,
                            options = Visibility.entries.toList(),
                            label = { it.label },
                            onChange = { vm.setVisibility(t, it) },
                        )
                    }
                }
                if (t.highlights != HighlightSupport.NONE) {
                    // Public-only trackers can't take highlights while updates are Only me.
                    val allowed = t.highlights != HighlightSupport.PUBLIC_POST || svc.visibility == Visibility.PUBLIC || t.highlightsIgnoreVisibility
                    RowDivider()
                    Option(
                        "Send highlights",
                        if (allowed) t.highlightsNote else "Needs Who can see updates set to Everyone. ${t.highlightsNote}",
                        enabled = allowed,
                    ) {
                        CpToggle(svc.highlights && allowed, { vm.setHighlights(t, it) }, "Send highlights to ${t.name}", enabled = allowed)
                    }
                }
                if (t.id == io.github.prithirajn.trackersync.trackers.SeekquelTracker.id) {
                    RowDivider()
                    SeekquelAccountOption(vm, svc.credential)
                }
                if (t.id == io.github.prithirajn.trackersync.trackers.FableTracker.id && svc.highlights) {
                    RowDivider()
                    FableQuoteOption(vm, svc.credential)
                }
                if (t.readsLibrary) {
                    RowDivider()
                    Option(
                        "Send my ${t.name} reading to other trackers",
                        "Books you're reading here that aren't on CrossPoint show up in your Library and sync to your other trackers. Past books (finished, paused, did not finish) come in too, and go out only when you pick them.",
                    ) {
                        CpToggle(svc.importReading, { vm.setImportReading(t, it) }, "Send ${t.name} reading to other trackers")
                    }
                }
            }

            if (isBeta(t)) {
                VSpace(12)
                Quiet(
                    if (t.signInKind == SignInKind.WEBVIEW) "Beta: ${t.name} has no public API, so the app works it like its website. It may break when the site changes."
                    else "Beta: uses ${t.name}'s new reading-app connection.",
                    Modifier.padding(horizontal = 4.dp),
                    color = c.faint,
                )
            }
            VSpace(24)
            BigButton("Unlink ${t.name}", onUnlink, filled = false, danger = true)
        }
    }
}

/** A setting: label and control on one line, an optional note under the label. */
@Composable
private fun Option(label: String, note: String?, enabled: Boolean = true, control: @Composable () -> Unit) {
    val c = Cp.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = CpType.label, color = if (enabled) c.text else c.faint)
            note?.let { Text(it, style = CpType.small, color = c.quiet, modifier = Modifier.padding(top = 2.dp)) }
        }
        control()
    }
}

/**
 * How highlights go to Fable: as quote cards (the passage on Fable's own card, your note as the
 * post's caption) or as text posts; and the cards' style.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FableQuoteOption(vm: AppViewModel, credential: String?) {
    val c = Cp.colors
    val session = remember(credential) { io.github.prithirajn.trackersync.trackers.Session(credential) }
    val F = io.github.prithirajn.trackersync.trackers.FableTracker
    val style = F.highlightStyle(session)
    val theme = F.quoteTheme(session)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Send highlights as", style = CpType.label, color = c.text)
        Quiet(if (style == "quote") "Quote cards: the passage on Fable's own quote card, your note as its caption." else "Text posts: the passage in quotation marks, your note under it.", color = c.faint)
        VSpace(8)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, v) in listOf("Quote cards" to "quote", "Text posts" to "text")) {
                val on = style == v
                Box(
                    Modifier.clip(CpShapes.pill).background(if (on) c.accentTint else c.divider)
                        .bouncyClickable(CpShapes.pill, pressedScale = 0.94f, role = Role.RadioButton, onClickLabel = label) { vm.setFableHighlights(style = v) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) { Text(label, style = CpType.label, color = if (on) c.accentText else c.textMid) }
            }
        }
        if (style == "quote") {
            VSpace(12)
            Text("Card style", style = CpType.label, color = c.text)
            Quiet("Fable's six quote card looks; Mixed gives each highlight its own (the same one each time). Book photo sends the passage as a photo of it in a real-looking book, in the look you last set when sharing a highlight.", color = c.faint)
            VSpace(8)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, v) in listOf("Mixed" to "auto") + F.QUOTE_THEMES.mapIndexed { i, t -> "Style ${i + 1}" to t } + ("Book photo" to F.PAGE_STYLE)) {
                    val on = theme == v
                    Box(
                        Modifier.clip(CpShapes.pill).background(if (on) c.accentTint else c.divider)
                            .bouncyClickable(CpShapes.pill, pressedScale = 0.94f, role = Role.RadioButton, onClickLabel = label) { vm.setFableHighlights(theme = v) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) { Text(label, style = CpType.label, color = if (on) c.accentText else c.textMid) }
                }
            }
        }
    }
}

/**
 * Seekquel's account sign-in, beside its Reading API link: with it, books added to Seekquel from
 * here get their cover and full details (the Reading API alone can't set them).
 */
@Composable
private fun SeekquelAccountOption(vm: AppViewModel, credential: String?) {
    val c = Cp.colors
    val signedIn = remember(credential) { io.github.prithirajn.trackersync.trackers.SeekquelTracker.accountEmail(credential) }
    var open by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Option(
        "Seekquel account",
        if (signedIn != null) "Signed in as $signedIn. Books added to Seekquel from here get their cover and details."
        else "Sign in so books added to Seekquel get their cover, description and ISBNs. Uses email and password (set one in Seekquel if you use Google); only a sign-in token is kept.",
    ) {
        if (signedIn != null) PillButton("Sign out", { vm.seekquelAccountSignOut() })
        else if (!open) PillButton("Sign in", { open = true })
    }
    // The website, signed in like in a browser: where Seekquel shows a book's moods, tropes and warnings.
    val webOn = remember(credential) { io.github.prithirajn.trackersync.trackers.Session(credential).data.optBoolean("webSignedIn") }
    var web by remember { mutableStateOf(false) }
    Option(
        "Seekquel website",
        if (webOn) "Signed in. Each book's moods, tropes, pace and content warnings on Seekquel join Hardcover's and StoryGraph's."
        else "Sign in on seekquel.app, as in a browser, so each book's moods, tropes, pace and content warnings there are read too.",
    ) {
        if (webOn) PillButton("Sign out", { vm.seekquelWebSignedIn(false) })
        else PillButton("Sign in", { web = true })
    }
    if (web) SeekquelWebDialog(onDone = { web = false; vm.seekquelWebSignedIn(true) }, onClose = { web = false })
    if (signedIn == null && open) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
            CpTextField(email, { email = it; error = null }, "Email", keyboard = KeyboardType.Email)
            VSpace(8)
            CpTextField(password, { password = it; error = null }, "Password", password = true)
            error?.let { VSpace(6); Text(it, style = CpType.small, color = c.errorText) }
            VSpace(10)
            BigButton("Sign in to Seekquel", {
                busy = true; error = null
                vm.seekquelAccountSignIn(email, password) { e -> busy = false; if (e == null) { open = false; password = "" } else error = e }
            }, busy = busy, enabled = email.isNotBlank() && password.isNotEmpty())
        }
    }
}

/**
 * seekquel.app's sign-in in a web view; its sign-in stays in the app's browser (as Goodreads' does),
 * so the unseen browser can read book pages as you. Done once you're past the sign-in page.
 */
@Composable
private fun SeekquelWebDialog(onDone: () -> Unit, onClose: () -> Unit) {
    val c = Cp.colors
    var signedIn by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sign in to Seekquel", style = CpType.bodyStrong, color = c.text)
                    Quiet(if (signedIn) "Signed in. Tap Done." else "On seekquel.app itself")
                }
                if (signedIn) PillButton("Done", onDone) else PillButton("Close", onClose)
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                // Past the sign-in pages, on seekquel.app: signed in.
                                val u = url.orEmpty()
                                if (u.contains("seekquel.app") && !u.contains("/auth/")) {
                                    CookieManager.getInstance().flush()
                                    signedIn = true
                                }
                            }
                        }
                        loadUrl("https://seekquel.app/auth/login")
                    }
                },
            )
        }
    }
}

/** How often background syncs run, on which connection, quiet hours, and what to be told about. */
@Composable
private fun SyncSettingsCard(vm: AppViewModel, state: Snapshot) {
    val c = Cp.colors
    val s = state.settings
    val intervals = listOf(15, 30, 60, 180, 360)
    fun every(m: Int) = if (m < 60) "Every $m min" else if (m == 60) "Every hour" else "Every ${m / 60} hours"
    // Quiet hours: none, or common nights.
    val quiet = listOf(-1 to -1, 22 to 7, 23 to 7, 0 to 6)
    fun quietLabel(q: Pair<Int, Int>) = if (q.first < 0) "Off" else "%02d:00–%02d:00".format(q.first, q.second)
    CpCard {
        SettingLine("Sync in the background", "When the phone is online; opening the app always syncs.") {
            CpSelect(s.intervalMinutes.let { m -> intervals.minByOrNull { kotlin.math.abs(it - m) } ?: 15 }, intervals, ::every, onChange = { m ->
                vm.updateSettings { it.copy(intervalMinutes = m) }
            })
        }
        RowDivider()
        SettingLine("Wi-Fi only", "Background syncs wait for Wi-Fi. Sync now still uses any connection.") {
            CpToggle(s.wifiOnly, { on -> vm.updateSettings { it.copy(wifiOnly = on) } }, "Wi-Fi only")
        }
        RowDivider()
        SettingLine("Quiet hours", "No background syncs at night; everything catches up after.") {
            CpSelect(quiet.firstOrNull { it.first == s.quietFrom && it.second == s.quietTo } ?: quiet[0], quiet, ::quietLabel, onChange = { q ->
                vm.updateSettings { it.copy(quietFrom = q.first, quietTo = q.second) }
            })
        }
        RowDivider()
        SettingLine("Tell me when a sync fails", "Once per new problem, not every time.") {
            CpToggle(s.notifyFailures, { on -> vm.updateSettings { it.copy(notifyFailures = on) } }, "Notify on failures")
        }
        RowDivider()
        SettingLine("Tell me when I finish a book", "With a tap to rate it for your trackers.") {
            CpToggle(s.notifyFinished, { on -> vm.updateSettings { it.copy(notifyFinished = on) } }, "Notify on finished books")
        }
        RowDivider()
        SettingLine("Auto-sync new books", "When off, new books start paused so you can pick which trackers to sync.") {
            CpToggle(s.autoEnableNewBooks, { on -> vm.updateSettings { it.copy(autoEnableNewBooks = on) } }, "Auto-sync new books")
        }
        RowDivider()
        SettingLine(
            "Icon colour follows your book",
            "The app's icon takes the colour of the cover you're reading, changing after you leave the app. " +
                "Some home screens drop the icon when it changes; add it back from the app drawer if yours does.",
        ) {
            CpToggle(s.iconFollowsBook, { on -> vm.updateSettings { it.copy(iconFollowsBook = on) } }, "Icon colour follows your book")
        }
    }
}

@Composable
private fun SettingLine(label: String, note: String, control: @Composable () -> Unit) {
    val c = Cp.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = CpType.label, color = c.text)
            Text(note, style = CpType.small, color = c.quiet, modifier = Modifier.padding(top = 2.dp))
        }
        control()
    }
}

/** Everything in one password-protected file: to keep, or to move to a new phone. */
@Composable
private fun BackupCard(vm: AppViewModel) {
    val c = Cp.colors
    var mode by remember { mutableStateOf<String?>(null) } // "save" | "restore"
    var target by remember { mutableStateOf<Uri?>(null) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) { target = uri; mode = "save" }
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { target = uri; mode = "restore" }
    }
    CpCard {
        Column(Modifier.padding(16.dp)) {
            Quiet("Your accounts, tracker sign-ins, matches, physical books, highlights and settings, in one file locked with a password you choose. Use it to move to a new phone.")
            VSpace(12)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Back up to a file", {
                    val day = java.time.LocalDate.now()
                    create.launch("tracker-sync-$day.tsbackup")
                })
                PillButton("Restore", { openDoc.launch(arrayOf("*/*")) }, outlined = false)
            }
        }
    }
    val uri = target
    if (mode != null && uri != null) {
        BackupPasswordSheet(vm, mode == "save", uri) { mode = null; target = null }
    }
}

/**
 * The on-device model for the AI helpers: a ".litertlm" model (Gemma, Qwen, Llama, Phi…),
 * downloaded here or chosen from the phone, run on the phone with Google's LiteRT-LM.
 */
@Composable
private fun OnDeviceAiCard(vm: AppViewModel, state: Snapshot) {
    val c = Cp.colors
    val context = LocalContext.current
    var model by remember { mutableStateOf(vm.aiModel()) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var working by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    val supported = remember { io.github.prithirajn.trackersync.ai.LocalModel.supported() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) cur.getString(0) else null
            }
        }.getOrNull() ?: "model.litertlm"
        error = null
        progress = 0f
        working = "Adding $name"
        vm.installModel(uri, name, { p -> progress = p }) { e ->
            progress = null; working = null
            error = e
            model = vm.aiModel()
            if (e == null) choosing = false
        }
    }
    CpCard {
        Column(Modifier.padding(16.dp)) {
            Quiet("A small language model running on this phone: it suggests matches the app isn't sure of, tidies descriptions, sums up your highlights, drafts reviews, finds passages on page photos and answers questions about your reading. Everything stays on the phone, and nothing it writes is sent anywhere until you choose to.")
            VSpace(10)
            val m = model
            when {
                !supported -> Quiet("This phone's processor can't run the on-device model (it needs a 64-bit phone).", color = c.errorText)
                progress != null -> {
                    Text("${working ?: "Getting the model"}… ${((progress ?: 0f) * 100).toInt()}%", style = CpType.small, color = c.accentText)
                    VSpace(4)
                    Quiet("Keep the app open. Then it's loaded once to check it runs on this phone.", color = c.faint)
                }
                m != null && !choosing -> {
                    Text(m.first, style = CpType.bodyStrong, color = c.text)
                    Quiet("%.1f GB · on this phone".format(java.util.Locale.US, m.second / 1_000_000_000.0), color = c.faint)
                    VSpace(10)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("Use another model", { choosing = true })
                        PillButton("Remove", { removing = true }, danger = true)
                    }
                }
                else -> {
                    Text("Choose a model", style = CpType.bodyStrong, color = c.text)
                    VSpace(6)
                    for (choice in io.github.prithirajn.trackersync.ai.LocalModel.catalog) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                                Text("${choice.name} · ${choice.size}", style = CpType.body, color = c.text)
                                Quiet(choice.note, color = c.faint)
                            }
                            PillButton("Get", {
                                error = null
                                progress = 0f
                                working = "Downloading ${choice.name}"
                                vm.downloadModel(choice, { p -> progress = p }) { e ->
                                    progress = null; working = null
                                    error = e
                                    model = vm.aiModel()
                                    if (e == null) choosing = false
                                }
                            }, outlined = false)
                        }
                    }
                    VSpace(8)
                    Quiet("Any .litertlm model works (Llama, Phi, DeepSeek and more are on Hugging Face's litert-community). Download one in your browser, then choose the file.", color = c.faint)
                    VSpace(8)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("Choose a model file", { pick.launch(arrayOf("*/*")) })
                        PillButton("Browse models", { openLink(context, "https://huggingface.co/litert-community") })
                        if (m != null) PillButton("Keep ${m.first}", { choosing = false })
                    }
                }
            }
            error?.let { e ->
                VSpace(8)
                Text(e, style = CpType.small, color = c.errorText)
                // A model behind a licence: its page, to accept and download in the browser.
                io.github.prithirajn.trackersync.ai.LocalModel.catalog.firstOrNull { e.startsWith(it.name) }?.let { ch ->
                    VSpace(6)
                    PillButton("Open ${ch.name}'s page", { openLink(context, ch.page) })
                }
            }
            if (m != null) {
                VSpace(10)
                RowDivider()
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Suggest unsure matches", style = CpType.body, color = c.text)
                        Quiet("When a sync can't tell which listing is the book, the AI picks one for you to accept on the book.", color = c.faint)
                    }
                    CpToggle(state.settings.aiMatching, { on -> vm.updateSettings { it.copy(aiMatching = on) } }, "Suggest unsure matches")
                }
            }
        }
    }
    if (removing) {
        ConfirmSheet(
            title = "Remove the on-device model?",
            body = "The model file is deleted from the app. The AI helpers are hidden until you add one again.",
            action = "Remove",
            onConfirm = { vm.removeModel { model = null } },
            onClose = { removing = false },
        )
    }
}

/** Opens Obtainium ready to add this app's repo; if Obtainium isn't installed, its download page. */
private fun openObtainium(context: android.content.Context, repo: String) {
    val add = Intent(Intent.ACTION_VIEW, Uri.parse(AppUpdater.obtainiumLink(repo))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(add)
    } catch (e: android.content.ActivityNotFoundException) {
        openLink(context, AppUpdater.OBTAINIUM_DOWNLOAD)
    }
}

private fun openLink(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun AppUpdateCard(vm: AppViewModel, state: Snapshot, appVersion: String) {
    val c = Cp.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AppUpdater.CheckResult?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var token by remember(state.settings.githubToken) { mutableStateOf(state.settings.githubToken.orEmpty()) }
    var repo by remember(state.settings.githubRepo) { mutableStateOf(state.settings.githubRepo ?: AppUpdater.DEFAULT_REPO) }

    CpCard {
        Column(Modifier.padding(16.dp)) {
            Text("Current version: v$appVersion", style = CpType.bodyStrong, color = c.text)
            VSpace(4)
            Quiet("Updates install through Obtainium, a free app that installs and updates apps from GitHub releases. Add this app there once (Set up in Obtainium); it then notifies you and installs each new version.", color = c.faint)
            VSpace(4)
            Quiet(if (state.settings.githubToken.isNullOrBlank()) "Check for update looks at your GitHub repository's releases (a private one needs a GitHub token)." else "Checking $repo with your GitHub token.")
            VSpace(12)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton("Check for update", {
                    checking = true
                    result = null
                    scope.launch(Dispatchers.IO) {
                        val res = AppUpdater.checkUpdate(repo, token.takeIf { it.isNotBlank() }, appVersion)
                        withContext(Dispatchers.Main) {
                            checking = false
                            result = res
                        }
                    }
                }, busy = checking)
                BigButton("Set up in Obtainium", { openObtainium(context, repo) }, filled = false)
                PillButton(if (showSettings) "Hide GitHub token" else "GitHub token", { showSettings = !showSettings })
            }

            if (showSettings) {
                VSpace(12)
                CpTextField(
                    repo,
                    { repo = it; vm.updateSettings { s -> s.copy(githubRepo = it.trim().ifEmpty { null }) } },
                    "Repository",
                    placeholder = "owner/repo",
                )
                VSpace(8)
                CpTextField(
                    token,
                    { token = it; vm.updateSettings { s -> s.copy(githubToken = it.trim().ifEmpty { null }) } },
                    "GitHub Personal Access Token",
                    placeholder = "ghp_...",
                    mono = true,
                )
                VSpace(4)
                Quiet("A fine-grained token with 'Contents: Read' access to your private repository, for checking here. Obtainium needs the same token in its own settings (GitHub source).", color = c.faint)
            }

            result?.let { res ->
                VSpace(12)
                when (res) {
                    is AppUpdater.CheckResult.Available -> {
                        Text("New version available: ${res.release.tagName}", style = CpType.bodyStrong, color = c.accentText)
                        if (res.release.notes.isNotBlank()) {
                            VSpace(4)
                            Quiet(res.release.notes.take(300), maxLines = 4)
                        }
                        VSpace(8)
                        // Installed by Obtainium (or by hand from the release page): this app doesn't
                        // install apps itself, which keeps Play Protect from flagging it.
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton("Update with Obtainium", { openObtainium(context, repo) }, outlined = false)
                            PillButton("Release page", { openLink(context, AppUpdater.releasesPage(repo)) })
                        }
                    }
                    is AppUpdater.CheckResult.UpToDate -> {
                        Text("You're on the latest version (v${res.currentVersion}).", style = CpType.body, color = c.accentText)
                    }
                    is AppUpdater.CheckResult.Error -> {
                        Text(res.message, style = CpType.small, color = c.error)
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupPasswordSheet(vm: AppViewModel, saving: Boolean, uri: Uri, onClose: () -> Unit) {
    val c = Cp.colors
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(Modifier.fillMaxWidth().imePadding().padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            Text(if (saving) "Lock the backup" else "Restore from backup", style = CpType.section, color = c.text)
            Quiet(
                if (saving) "Choose a password. Without it the file can't be opened, by anyone, including you."
                else "Enter the backup's password. Everything here is replaced by what's in the file.",
            )
            VSpace(16)
            CpTextField(password, { password = it }, "Password", password = true)
            if (saving) {
                VSpace(10)
                CpTextField(again, { again = it }, "Same password again", password = true)
            }
            error?.let { VSpace(10); Text(it, style = CpType.small, color = c.errorText) }
            VSpace(16)
            BigButton(
                if (saving) "Save backup" else "Restore",
                onClick = {
                    if (saving && password != again) { error = "The passwords don't match"; return@BigButton }
                    busy = true; error = null
                    val done: (String?) -> Unit = { e -> busy = false; if (e == null) onClose() else error = e }
                    if (saving) vm.exportBackup(uri, password, done) else vm.restoreBackup(uri, password, done)
                },
                busy = busy,
                enabled = password.length >= 6,
                danger = !saving,
                filled = saving,
            )
            Quiet("At least 6 characters.", Modifier.padding(top = 8.dp), color = c.faint)
        }
    }
}

/**
 * An extra KOSync server (KOReader's, or self-hosted). KOSync can't list books, so it's asked
 * about the books already here, and newer progress on it reaches the trackers.
 */
@Composable
private fun KindleCard(vm: AppViewModel, state: Snapshot, onLink: () -> Unit, onRemove: () -> Unit) {
    val c = Cp.colors
    val k = state.kindle
    CpCard {
        if (k == null) {
            Column(Modifier.padding(16.dp)) {
                Quiet("Read on Kindle or the Kindle app? Link your Amazon account to sync Whispersync reading progress and pull your highlights & notes directly into your library.")
                VSpace(12)
                PillButton("Link Amazon Kindle", onLink)
            }
        } else {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ServiceTile("Kindle", 40.dp)
                    HSpace(12)
                    Column(Modifier.weight(1f)) {
                        Text(k.name ?: "Amazon Kindle", style = CpType.bodyStrong, color = c.text)
                        Quiet(k.domain, maxLines = 1)
                        val err = state.kindleError
                        if (err != null) {
                            AnimatedQuiet(err, color = c.errorText, maxLines = 2)
                        } else {
                            val since = state.kindleOkAt.takeIf { it > 0 }?.let { "Checked ${ago(it)}" } ?: "Not checked yet"
                            AnimatedQuiet(since, color = c.faint, maxLines = 1)
                        }
                    }
                    Health.kindle(state)?.let { h -> HealthLight(h) { vm.checkKindle() } }
                    if (state.kindleError != null) {
                        PillButton("Sign in", onLink)
                        HSpace(6)
                    }
                    PillButton("Disconnect", onRemove, danger = true)
                }
                VSpace(10)
                RowDivider()
                VSpace(10)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sync highlights & notes", style = CpType.body, color = c.text)
                        Quiet("Pull your Kindle highlights into each book", color = c.faint)
                    }
                    CpToggle(k.syncHighlights, { vm.setKindleHighlights(it) }, "Sync highlights")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KindleSignInSheet(vm: AppViewModel, onClose: () -> Unit) {
    var domain by remember { mutableStateOf(stateDomainOrDefault(vm)) }
    var inWeb by remember { mutableStateOf(false) }
    val c = Cp.colors
    if (inWeb) {
        KindleWebDialog(domain, onDone = { cookies, ua ->
            inWeb = false
            vm.saveKindleAccount(domain, cookies, ua) { onClose() }
        }, onClose = { inWeb = false })
        return
    }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
        ) {
            Text("Link Amazon Kindle", style = CpType.section, color = c.text)
            VSpace(8)
            Quiet("Sign in to your Amazon account in a secure browser. Your session is encrypted with the phone's Keystore to sync your Whispersync progress, highlights and notes.")
            VSpace(14)
            Text("Your Amazon Store", style = CpType.label, color = c.textMid)
            VSpace(6)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (item in io.github.prithirajn.trackersync.source.KindleClient.REGIONAL_DOMAINS) {
                    val d = item.first
                    val label = item.second
                    val on = domain == d
                    Box(Modifier.bouncyClickable(CpShapes.pill, pressedScale = 0.92f) { domain = d }) {
                        Chip(label, color = if (on) c.accentText else c.quiet, background = if (on) c.accentTint else c.divider)
                    }
                }
            }
            VSpace(20)
            BigButton("Sign in to Amazon", { inWeb = true })
        }
    }
}

private fun stateDomainOrDefault(vm: AppViewModel): String =
    vm.state.value.kindle?.domain ?: io.github.prithirajn.trackersync.source.KindleClient.DEFAULT_DOMAIN

@Composable
private fun KindleWebDialog(domain: String, onDone: (String, String) -> Unit, onClose: () -> Unit) {
    val c = Cp.colors
    val url = "https://$domain/notebook"
    var detectedCookies by remember { mutableStateOf<String?>(null) }
    var detectedUa by remember { mutableStateOf<String?>(null) }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Sign in to Amazon", style = CpType.bodyStrong, color = c.text)
                    Quiet(if (detectedCookies != null) "Session detected! Tap Done to finish." else "Complete sign-in until your Kindle Notebook loads.")
                }
                HSpace(8)
                if (detectedCookies != null) {
                    PillButton("Done", { onDone(detectedCookies!!, detectedUa ?: "") })
                } else {
                    PillButton("Close", onClose)
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        val ua = settings.userAgentString
                        detectedUa = ua
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, currentUrl: String?) {
                                cm.flush()
                                val u = (currentUrl ?: "").lowercase()
                                val onKindle = u.startsWith("https://${domain.lowercase()}") &&
                                    !u.contains("/ap/signin") && !u.contains("/ap/mfa") && !u.contains("/ap/cvf")
                                val rootDomain = domain.removePrefix("read.")
                                val c1 = cm.getCookie("https://$domain") ?: ""
                                val c2 = cm.getCookie("https://www.$rootDomain") ?: ""
                                val c3 = currentUrl?.let { cm.getCookie(it) } ?: ""
                                val merged = io.github.prithirajn.trackersync.net.CookieJar.parse("$c2; $c1; $c3").header()
                                val hasAuth = merged.contains("at-main") || merged.contains("at-acb") ||
                                    merged.contains("sess-at-") || merged.contains("x-main") ||
                                    merged.contains("x-acb") || merged.contains("session-token")
                                if (onKindle && merged.contains("session-id") && hasAuth) {
                                    detectedCookies = merged
                                }
                            }
                        }
                        loadUrl(url)
                    }
                },
            )
        }
    }
}

/**
 * Libby: loans brought in from a linked account, and Libby's own Reading Journey exports opened
 * from a file (position, highlights, borrow dates), which work with or without a link.
 */
@Composable
private fun LibbyCard(vm: AppViewModel, state: Snapshot, onLink: () -> Unit, onRemove: () -> Unit) {
    val c = Cp.colors
    val l = state.libby
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            importing = true
            importError = null
            vm.importLibbyJourney(uris) { err -> importing = false; importError = err }
        }
    }
    CpCard {
        if (l == null) {
            Column(Modifier.padding(16.dp)) {
                Quiet("Borrow ebooks or audiobooks on Libby? Link your account to bring in your loans and their borrow dates.")
                VSpace(12)
                PillButton("Link Libby", onLink)
            }
        } else {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ServiceTile("Libby", 40.dp)
                    HSpace(12)
                    Column(Modifier.weight(1f)) {
                        Text(l.name ?: "Libby", style = CpType.bodyStrong, color = c.text)
                        val err = state.libbyError
                        if (err != null) {
                            AnimatedQuiet(err, color = c.errorText, maxLines = 2)
                        } else {
                            val since = state.libbyOkAt.takeIf { it > 0 }?.let { "Checked ${ago(it)}" } ?: "Not checked yet"
                            AnimatedQuiet(since, color = c.faint, maxLines = 1)
                        }
                    }
                    Health.libby(state)?.let { h -> HealthLight(h) { vm.checkLibby() } }
                }
                VSpace(10)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.libbyError != null) PillButton("Link again", onLink)
                    PillButton("Disconnect", onRemove, danger = true)
                }
                VSpace(10)
                RowDivider()
                VSpace(10)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sync highlights & notes", style = CpType.body, color = c.text)
                        Quiet("When Libby hands them over with a loan", color = c.faint)
                    }
                    CpToggle(l.syncHighlights, { vm.setLibbyHighlights(it) }, "Sync highlights")
                }
                VSpace(10)
                RowDivider()
                VSpace(10)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Include borrowed history", style = CpType.body, color = c.text)
                        Quiet("Also bring in past loans you've tagged Borrowed", color = c.faint)
                    }
                    CpToggle(l.syncBorrowedTag, { vm.setLibbyBorrowedTag(it) }, "Include borrowed history")
                }
            }
        }
    }
    VSpace(16)
    SectionTitle("Reading Journey")
    VSpace(8)
    CpCard {
        Column(Modifier.padding(16.dp)) {
            Quiet("For a book's position and highlights: in Libby open the book's Reading Journey, tap Actions → Export Reading Journey → Data, and save the file. Open one or several here; position only moves forward.")
            VSpace(12)
            BigButton(
                if (importing) "Reading…" else "Open Journey files",
                { pick.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                filled = false,
                busy = importing,
                enabled = !importing,
            )
            importError?.let { VSpace(8); Quiet(it, color = c.errorText) }
        }
    }
}

/**
 * Links Libby through libbyapp.com, where Libby's own sign-in runs: a library card, or Libby's
 * "Copy from another device" with the code your phone's Libby app gives. A token can be pasted
 * instead. Libby on the phone is left as it is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibbySignInSheet(vm: AppViewModel, onClose: () -> Unit) {
    val c = Cp.colors
    var inWeb by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var linking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    if (inWeb) {
        LibbyWebDialog(
            onDone = { identity, chip ->
                inWeb = false
                linking = true
                vm.connectLibbyWithInput(identity, chip) { err ->
                    linking = false
                    if (err == null) onClose() else error = err
                }
            },
            onClose = { inWeb = false },
        )
        return
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
        ) {
            Text("Link Libby", style = CpType.section, color = c.text)
            VSpace(12)
            CpCard {
                Column(Modifier.padding(16.dp)) {
                    Text("1  Tap Sign in on libbyapp.com below", style = CpType.body, color = c.text)
                    VSpace(6)
                    Text("2  Add your library card there, or choose Copy From Another Device and follow Libby's steps with the Libby app on your phone", style = CpType.body, color = c.text)
                    VSpace(6)
                    Text("3  When your card shows, tap Done at the top", style = CpType.body, color = c.text)
                }
            }
            VSpace(16)
            BigButton(if (linking) "Linking…" else "Sign in on libbyapp.com", { inWeb = true }, busy = linking, enabled = !linking)
            error?.let {
                VSpace(8)
                Quiet(it, color = c.errorText)
            }
            VSpace(12)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PillButton(if (showToken) "Hide token" else "Have a token? Paste it", { showToken = !showToken })
            }
            if (showToken) {
                VSpace(12)
                CpTextField(token, { token = it; error = null }, "Libby identity token", placeholder = "eyJ…", mono = true)
                VSpace(10)
                BigButton(
                    if (linking) "Linking…" else "Link",
                    {
                        linking = true
                        error = null
                        vm.connectLibbyWithInput(token.trim()) { err ->
                            linking = false
                            if (err == null) onClose() else error = err
                        }
                    },
                    busy = linking,
                    enabled = token.trim().length > 20 && !linking,
                )
            }
        }
    }
}

/** libbyapp.com in a web view: once you're signed in there, its own saved sign-in links Libby here. */
@Composable
private fun LibbyWebDialog(onDone: (String, String?) -> Unit, onClose: () -> Unit) {
    val c = Cp.colors
    var detectedIdentity by remember { mutableStateOf<String?>(null) }
    var detectedChip by remember { mutableStateOf<String?>(null) }
    var hasCards by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(1500)
            wv.evaluateJavascript(
                "(function(){try{var id=localStorage.getItem('dewey:sentry.identity');var ch=localStorage.getItem('dewey:sentry.chip');var cards=localStorage.getItem('dewey:patron:card:all');return JSON.stringify({id:id?JSON.parse(id):null,chip:ch?JSON.parse(ch):null,cards:cards?JSON.parse(cards):null});}catch(e){return '';}})()"
            ) { raw ->
                if (!raw.isNullOrBlank() && raw != "null" && raw != "\"\"") {
                    runCatching {
                        val unquoted = org.json.JSONTokener(raw).nextValue()?.toString() ?: ""
                        if (unquoted.startsWith("{")) {
                            val obj = org.json.JSONObject(unquoted)
                            val id = obj.optString("id").takeIf { it.startsWith("eyJ") }
                            val ch = obj.optString("chip").takeIf { it.isNotBlank() && it != "null" }
                            val cardsArr = obj.optJSONObject("cards")?.optJSONArray("all")
                            if (id != null) {
                                detectedIdentity = id
                                detectedChip = ch
                                if (cardsArr != null && cardsArr.length() > 0) hasCards = true
                            }
                        }
                    }
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Sign in to Libby", style = CpType.bodyStrong, color = c.text)
                    Quiet(
                        when {
                            hasCards -> "Library card found. Tap Done."
                            detectedIdentity != null -> "Add your library card in Libby, then tap Done."
                            loaded -> "Add your card, or Copy From Another Device."
                            else -> "Loading libbyapp.com…"
                        }
                    )
                }
                HSpace(8)
                detectedIdentity?.let { id ->
                    PillButton("Done", { onDone(id, detectedChip) })
                    HSpace(6)
                }
                PillButton("Close", onClose)
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        // Full size from the start: Libby lays its screens out from the first size it sees.
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        @Suppress("DEPRECATION")
                        settings.databaseEnabled = true
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) { loaded = true }
                        }
                        webViewRef = this
                        // Libby's own start: add a library card, or copy from another device.
                        loadUrl("https://libbyapp.com/interview/welcome")
                    }
                },
            )
        }
    }
}

/** Audible: what you're listening to, merged into the books you already have. */
@Composable
private fun AudibleCard(vm: AppViewModel, state: Snapshot, onLink: () -> Unit, onRemove: () -> Unit) {
    val c = Cp.colors
    val a = state.audible
    CpCard {
        if (a == null) {
            Column(Modifier.padding(16.dp)) {
                Quiet("Listen on Audible? Link it and what you're listening to now comes in. A title you also have as an ebook stays one book, at whichever place is further along, and your trackers get it.")
                VSpace(12)
                PillButton("Link Audible", onLink)
            }
        } else {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ServiceTile("Audible", 40.dp)
                    HSpace(12)
                    Column(Modifier.weight(1f)) {
                        Text(a.name?.let { "$it \u00B7 Audible" } ?: "Audible", style = CpType.bodyStrong, color = c.text)
                        Quiet("Store: ${a.store}", maxLines = 1)
                        val err = state.audibleError
                        if (err != null) AnimatedQuiet(err, color = c.errorText, maxLines = 2)
                        else AnimatedQuiet(state.audibleOkAt.takeIf { it > 0 }?.let { "Checked ${ago(it)}" } ?: "Not checked yet", color = c.faint, maxLines = 1)
                    }
                    Health.audible(state)?.let { h -> HealthLight(h) { vm.checkAudible() } }
                }
                VSpace(10)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.audibleError != null) PillButton("Sign in again", onLink)
                    PillButton("Disconnect", onRemove, danger = true)
                }
                VSpace(10)
                Quiet("Brings in titles you're part-way through; one you finish is marked Finished. Your reader's own position isn't changed.", color = c.faint)
            }
        }
    }
}

/** Pick your Audible store, then sign in on Amazon's page for it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudibleSignInSheet(vm: AppViewModel, onClose: () -> Unit) {
    val c = Cp.colors
    var signIn by remember { mutableStateOf<io.github.prithirajn.trackersync.source.AudibleClient.Companion.SignIn?>(null) }
    var linking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    signIn?.let { si ->
        AudibleWebDialog(
            si,
            onCode = { code ->
                signIn = null
                linking = true
                vm.finishAudibleSignIn(si, code) { e -> linking = false; if (e == null) onClose() else error = e }
            },
            onClose = { signIn = null },
        )
        return
    }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
        ) {
            Text("Link Audible", style = CpType.section, color = c.text)
            VSpace(6)
            Quiet("Pick your Audible store, then sign in on Amazon's page. ${io.github.prithirajn.trackersync.Edition.name} is added to your Audible devices (as \u201CAudible for iPhone\u201D); your password isn't seen or kept.")
            VSpace(12)
            if (linking) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), color = c.faint, strokeWidth = 2.dp)
                    HSpace(10)
                    Quiet("Linking\u2026")
                }
            } else {
                CpCard {
                    io.github.prithirajn.trackersync.source.AudibleClient.STORES.forEachIndexed { i, st ->
                        if (i > 0) RowDivider()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .bouncyClickable(RectangleShape, pressedScale = 0.99f, role = androidx.compose.ui.semantics.Role.Button, onClickLabel = st.name) {
                                    error = null
                                    signIn = vm.startAudibleSignIn(st)
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(st.name, style = CpType.body, color = c.text, modifier = Modifier.weight(1f))
                            Quiet("audible.${st.domain}", color = c.faint)
                        }
                    }
                }
            }
            error?.let { VSpace(10); Quiet(it, color = c.errorText) }
        }
    }
}

/** Amazon's sign-in page in a web view; the code it lands on is caught and the page closes. */
@Composable
private fun AudibleWebDialog(
    signIn: io.github.prithirajn.trackersync.source.AudibleClient.Companion.SignIn,
    onCode: (String) -> Unit,
    onClose: () -> Unit,
) {
    val c = Cp.colors
    var done by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sign in to Audible ${signIn.store.name}", style = CpType.bodyStrong, color = c.text)
                    Quiet("On Amazon's own page")
                }
                PillButton("Close", onClose)
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                        CookieManager.getInstance().setAcceptCookie(true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        fun check(url: String?): Boolean {
                            val code = url?.let { io.github.prithirajn.trackersync.source.AudibleClient.codeFrom(it) } ?: return false
                            if (!done) { done = true; onCode(code) }
                            return true
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean =
                                check(request?.url?.toString())
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                if (check(url)) view?.stopLoading()
                            }
                        }
                        loadUrl(signIn.url)
                    }
                },
            )
        }
    }
}

/**
 * Mihon: its backup folder, what was read from it, and MangaUpdates for chapter-by-chapter
 * tracking beside the volumes your book trackers get.
 */
@Composable
private fun MihonCard(
    vm: AppViewModel, state: Snapshot, onRemove: () -> Unit, onLinkMu: () -> Unit, onRemoveMu: () -> Unit,
    onLinkAl: () -> Unit, onRemoveAl: () -> Unit,
) {
    val c = Cp.colors
    val m = state.mihon
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { vm.setMihonFolder(it) } }
    Quiet("Manga become volumes (\u201CChainsaw Man, Vol. 5\u201D at 49%), using MangaDex to know which chapters each volume holds. Western comics are counted by the collected volume that holds each issue (\u201CSaga, Vol. 9\u201D), as trackers list them.", color = c.faint)
    VSpace(12)
    SectionTitle("Log it yourself")
    VSpace(8)
    LoggedCard(vm, state)
    VSpace(16)
    SectionTitle("From any device")
    VSpace(8)
    AniListCard(vm, state, onLinkAl, onRemoveAl)
    VSpace(16)
    SectionTitle("On this phone")
    VSpace(8)
    CpCard {
        Column(Modifier.padding(16.dp)) {
            if (m == null) {
                Quiet("Mihon on this phone? Its automatic backups also say the page you're on, so a chapter you're part-way through counts too. (Reading on another device? AniList above covers it.)")
                VSpace(10)
                Text("In Mihon first:", style = CpType.label, color = c.text)
                Quiet("More \u2192 Settings \u2192 Data and storage: set a storage location, and Automatic backups to daily (or more often).")
                VSpace(12)
                PillButton("Choose Mihon's backup folder", { pick.launch(null) })
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ServiceTile("Mihon", 40.dp)
                    HSpace(12)
                    Column(Modifier.weight(1f)) {
                        Text("Mihon", style = CpType.bodyStrong, color = c.text)
                        Quiet(m.backupName ?: "No backup read yet", maxLines = 1)
                        val err = state.mihonError
                        if (err != null) AnimatedQuiet(err, color = c.errorText, maxLines = 3)
                        else if (m.backupAt > 0) AnimatedQuiet("Backup made ${ago(m.backupAt * 1000)}", color = c.faint, maxLines = 1)
                    }
                    Health.mihon(state)?.let { h -> HealthLight(h) { vm.checkMihon() } }
                }
                VSpace(10)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Change folder", { pick.launch(null) })
                    PillButton("Disconnect", onRemove, danger = true)
                }
                VSpace(10)
                Quiet("Series you've read in the last six weeks are kept in step. A volume you've finished comes in as Finished; your reader's own places aren't changed.", color = c.faint)
            }
        }
    }
    VSpace(16)
    SectionTitle("Chapter tracker")
    VSpace(8)
    CpCard {
        val mu = state.mangaUpdates
        Column(Modifier.padding(16.dp)) {
            if (mu == null) {
                Quiet("MangaUpdates keeps your place by chapter. Link it and the chapter you've reached (in Mihon here, or on AniList) goes to your Reading list there, beside the volumes your book trackers get.")
                VSpace(12)
                PillButton("Link MangaUpdates", onLinkMu)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ServiceTile("MangaUpdates", 40.dp)
                    HSpace(12)
                    Column(Modifier.weight(1f)) {
                        Text(mu.username, style = CpType.bodyStrong, color = c.text)
                        Quiet("MangaUpdates \u00B7 ${state.muSent.size} series sent", maxLines = 1)
                        state.mangaUpdatesError?.let { AnimatedQuiet(it, color = c.errorText, maxLines = 2) }
                    }
                    Health.mangaUpdates(state)?.let { h -> HealthLight(h) {} }
                }
                VSpace(10)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.mangaUpdatesError != null) PillButton("Sign in again", onLinkMu)
                    PillButton("Disconnect", onRemoveMu, danger = true)
                }
            }
        }
    }
    if (state.comicMaps.isNotEmpty()) {
        VSpace(16)
        SectionTitle("Comics as volumes")
        VSpace(8)
        ComicVolumesCard(vm, state)
    }
}

/** How "1, 2, 3, 5, 6" reads: "#1–3, #5–6". */
private fun issueRanges(issues: List<Double>): String {
    val out = ArrayList<String>()
    var start: Double? = null
    var prev: Double? = null
    fun lab(n: Double) = io.github.prithirajn.trackersync.sync.ComicIssues.label(n)
    fun flush() { val a = start ?: return; val b = prev!!; out += if (a == b) "#${lab(a)}" else "#${lab(a)}\u2013${lab(b)}" }
    for (n in issues.sorted()) {
        if (prev != null && n - prev!! <= 1.0 + 1e-6) { prev = n; continue }
        flush(); start = n; prev = n
    }
    flush()
    return out.joinToString(", ")
}

/**
 * Each comic you're reading and how its issues are bundled into volumes: from Hardcover's own
 * volumes when they say which issues they collect, else an estimate you can change.
 */
@Composable
private fun ComicVolumesCard(vm: AppViewModel, state: Snapshot) {
    val c = Cp.colors
    var editingVolumes by remember { mutableStateOf<String?>(null) }
    editingVolumes?.let { k -> ComicVolumesSheet(vm, state, k) { editingVolumes = null } }
    CpCard {
        Column(Modifier.padding(16.dp)) {
            Quiet("Single issues are counted toward the collected volume that holds them, the way book trackers list comics. When Hardcover's or Seekquel's volumes say which issues they collect (\u201CCollects #1\u20136\u201D), that's used; otherwise six issues a volume, the usual trade paperback. You can set each volume's issues yourself.", color = c.faint)
            for ((key, m) in state.comicMaps.entries.sortedBy { it.value.title.lowercase() }) {
                VSpace(12)
                RowDivider()
                VSpace(12)
                val custom = state.comicPerVolume[key]
                Text(m.title, style = CpType.bodyStrong, color = c.text)
                Quiet(
                    when {
                        state.comicCustom[key] != null -> "Set by you \u00B7 ${state.comicCustom[key]!!.volumes.size} volume${if (state.comicCustom[key]!!.volumes.size == 1) "" else "s"}, later ones the same size"
                        custom != null -> "Your setting: $custom issues a volume"
                        m.exact -> "From ${io.github.prithirajn.trackersync.source.ComicCollections.sourceName(m)} \u00B7 ${m.volumes.size} volume${if (m.volumes.size == 1) "" else "s"}"
                        else -> "Estimated: ${m.perVolume ?: 6} issues a volume"
                    },
                    color = c.faint,
                    maxLines = 1,
                )
                val shown = m.volumes.takeLast(3)
                Quiet(shown.joinToString("  \u00B7  ") { "Vol. ${it.number}: ${issueRanges(it.issues)}" }, maxLines = 2)
                VSpace(8)
                PillButton(if (state.comicCustom[key] != null) "Edit volumes (yours)" else "Set which issues each volume holds", { editingVolumes = key })
                if (state.comicCustom[key] != null) {
                    // Nothing more: your volumes, carried on at your last one's size.
                } else if (m.exact && custom == null) {
                    m.lastIssue?.let { Quiet("Issues after #${io.github.prithirajn.trackersync.sync.ComicIssues.label(it)} count singly until their volume is out.", color = c.faint) }
                    VSpace(8)
                    PillButton("Set issues a volume myself", { vm.setComicPerVolume(key, m.volumes.firstOrNull()?.issues?.size ?: 6) })
                } else {
                    VSpace(8)
                    val per = custom ?: m.perVolume ?: 6
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("\u2212", { if (per > 1) vm.setComicPerVolume(key, per - 1) })
                        Text("$per a volume", style = CpType.label, color = c.text)
                        PillButton("+", { if (per < 50) vm.setComicPerVolume(key, per + 1) })
                        if (custom != null) PillButton("Reset", { vm.setComicPerVolume(key, null) })
                    }
                }
            }
        }
    }
}

/**
 * Manga and comics you log by hand: read on a device the app can't see, tell it the chapter or
 * issue (and the page), and the volume follows. "Done" when you finish the one you're in.
 */
@Composable
private fun LoggedCard(vm: AppViewModel, state: Snapshot) {
    val c = Cp.colors
    var editing by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    CpCard {
        Column(Modifier.padding(16.dp)) {
            if (state.logged.isEmpty()) {
                Quiet("Reading in Mihon on another device? Note the chapter or issue you're on here (and the page, if you're part-way), and the volume it's in is worked out and sent to your trackers.")
                VSpace(12)
            }
            for ((key, l) in state.logged.entries.sortedByDescending { it.value.loggedAt }) {
                val what = (if (l.comic) "Issue #" else "Chapter ") + io.github.prithirajn.trackersync.sync.ComicIssues.label(l.at)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).bouncyClickable(CpShapes.card, onClickLabel = "Change") { editing = key }) {
                        Text(l.title, style = CpType.bodyStrong, color = c.text, maxLines = 1)
                        Quiet(what + (if (l.finished) " \u00B7 finished" else " \u00B7 page ${l.page}") + " \u00B7 ${ago(l.loggedAt * 1000)}", maxLines = 1)
                    }
                    if (!l.finished) PillButton("Done", { vm.logNext(key) })
                }
                VSpace(10)
            }
            PillButton("Log reading", { adding = true })
        }
    }
    if (adding) LogReadingSheet(vm, state, null, onClose = { adding = false })
    editing?.let { k -> LogReadingSheet(vm, state, k, onClose = { editing = null }) }
}

/**
 * Which issues each volume of a comic holds, set by you: a first and last issue per volume. Starts
 * from what the app has now (a tracker's volumes or the estimate); volumes after your last one are
 * taken to be the same size.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComicVolumesSheet(vm: AppViewModel, state: Snapshot, key: String, comic: Boolean = true, onClose: () -> Unit) {
    val c = Cp.colors
    // A manga's volumes are chapters; shown the same way.
    val start = state.comicCustom[key] ?: state.comicMaps[key] ?: state.mangaMaps[key]?.let { m ->
        io.github.prithirajn.trackersync.source.ComicCollections.Map(m.title, m.volumes.map { v -> io.github.prithirajn.trackersync.source.ComicCollections.Volume(v.number, v.chapters.map { it.number }) }, m.source, m.at)
    }
    val lab = { n: Double -> io.github.prithirajn.trackersync.sync.ComicIssues.label(n) }
    val rows = remember {
        androidx.compose.runtime.mutableStateListOf<Pair<String, String>>().apply {
            val vs = start?.volumes.orEmpty().take(40)
            if (vs.isEmpty()) add(if (comic) "1" to "6" else "1" to "9") else vs.forEach { v -> add(lab(v.issues.min()) to lab(v.issues.max())) }
        }
    }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
        ) {
            Text(start?.title?.takeIf { it.isNotBlank() } ?: "Volumes", style = CpType.section, color = c.text)
            VSpace(6)
            Quiet(if (comic) "Which single issues each collected volume holds (it's on the volume's back cover or contents page: \u201CCollects #1\u20136\u201D). Volumes after your last one are taken to be the same size."
                else "Which chapters each volume holds (on the volume's contents page). Volumes after your last one are taken to be the same size.", color = c.faint)
            VSpace(14)
            rows.forEachIndexed { i, (a, b) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Vol. ${i + 1}", style = CpType.label, color = c.text, modifier = Modifier.width(56.dp))
                    Box(Modifier.weight(1f)) { CpTextField(a, { v -> rows[i] = v.filter { ch -> ch.isDigit() || ch == '.' } to rows[i].second; error = null }, if (comic) "From #" else "From ch.") }
                    Box(Modifier.weight(1f)) { CpTextField(b, { v -> rows[i] = rows[i].first to v.filter { ch -> ch.isDigit() || ch == '.' }; error = null }, if (comic) "To #" else "To ch.") }
                }
                VSpace(8)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Add a volume", {
                    // The next one: starting after the last, the same size.
                    val last = rows.lastOrNull()
                    val lo = last?.first?.toDoubleOrNull(); val hi = last?.second?.toDoubleOrNull()
                    if (lo != null && hi != null) rows.add(lab(hi + 1) to lab(hi + 1 + (hi - lo))) else rows.add("" to "")
                })
                if (rows.size > 1) PillButton("Remove last", { rows.removeAt(rows.lastIndex) })
            }
            error?.let { VSpace(8); Quiet(it, color = c.errorText) }
            VSpace(16)
            BigButton("Save", {
                val ranges = rows.map { (a, b) -> (a.toDoubleOrNull() ?: -1.0) to (b.toDoubleOrNull() ?: -1.0) }
                vm.setComicVolumes(key, ranges) { e -> if (e == null) onClose() else error = e }
            })
            if (state.comicCustom[key] != null) {
                VSpace(10)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PillButton(if (comic) "Use the trackers' volumes again" else "Use MangaDex's volumes again", { vm.setComicVolumes(key, null); onClose() })
                }
            }
        }
    }
}

/**
 * AniList: the bridge from Mihon on another device. Mihon there sends each chapter to AniList;
 * your list is read here. Chapters read in Mihon on this phone go back to it.
 */
@Composable
private fun AniListCard(vm: AppViewModel, state: Snapshot, onLink: () -> Unit, onRemove: () -> Unit) {
    val c = Cp.colors
    val a = state.anilist
    CpCard {
        Column(Modifier.padding(16.dp)) {
            if (a == null) {
                Quiet("Read in Mihon on a tablet or another phone? No plugin needed: link AniList in Mihon there (More \u2192 Settings \u2192 Tracking), and link the same AniList here. Each chapter Mihon sends to AniList reaches your book trackers as volumes and issues.")
                VSpace(12)
                PillButton("Link AniList", onLink)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ServiceTile("AniList", 40.dp)
                    HSpace(12)
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = CpType.bodyStrong, color = c.text)
                        Quiet("AniList" + (state.alSent.size.takeIf { it > 0 }?.let { " \u00B7 $it series sent" } ?: ""), maxLines = 1)
                        val err = state.anilistError
                        if (err != null) AnimatedQuiet(err, color = c.errorText, maxLines = 3)
                        else state.anilistOkAt.takeIf { it > 0 }?.let { AnimatedQuiet("List read ${ago(it)}", color = c.faint, maxLines = 1) }
                    }
                    Health.anilist(state)?.let { h -> HealthLight(h) { vm.checkAniList() } }
                }
                VSpace(12)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Read my AniList list", style = CpType.body, color = c.text)
                        Quiet("What you read on other devices", color = c.faint)
                    }
                    CpToggle(a.read, { vm.setAniListOptions(read = it) }, "Read my AniList list")
                }
                VSpace(8)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Send chapters to AniList", style = CpType.body, color = c.text)
                        Quiet("Read in Mihon on this phone; never moves you back", color = c.faint)
                    }
                    CpToggle(a.send, { vm.setAniListOptions(send = it) }, "Send chapters to AniList")
                }
                VSpace(10)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.anilistError != null) PillButton("Sign in again", onLink)
                    PillButton("Disconnect", onRemove, danger = true)
                }
            }
        }
    }
}

/**
 * Linking AniList: AniList gives each app its own client, so you register one (two fields, once),
 * then sign in on AniList's own page. The token AniList shows is caught; pasting it works too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AniListSheet(vm: AppViewModel, state: Snapshot, onClose: () -> Unit) {
    val c = Cp.colors
    val context = LocalContext.current
    var clientId by remember { mutableStateOf(state.anilistClientId ?: state.anilist?.clientId.orEmpty()) }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var web by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    fun link(t: String) {
        busy = true; error = null
        vm.linkAniList(t) { e -> busy = false; if (e == null) onClose() else error = e }
    }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
        ) {
            Text("Link AniList", style = CpType.section, color = c.text)
            VSpace(6)
            Quiet("AniList gives every app its own key. Make one for ${io.github.prithirajn.trackersync.Edition.name} once; it takes a minute.")
            VSpace(14)
            Text("1. On anilist.co: Settings \u2192 Developer \u2192 Create New Client", style = CpType.label, color = c.text)
            VSpace(4)
            Quiet("Name: ${io.github.prithirajn.trackersync.Edition.name}. Redirect URL: the one below. Save.")
            VSpace(8)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(io.github.prithirajn.trackersync.source.AniList.PIN_REDIRECT, style = CpType.small, color = c.textMid, modifier = Modifier.weight(1f))
                PillButton("Copy", {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Redirect URL", io.github.prithirajn.trackersync.source.AniList.PIN_REDIRECT))
                    said = "Redirect URL copied."
                })
            }
            VSpace(4)
            PillButton("Open AniList's developer page", {
                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://anilist.co/settings/developer"))) }
            })
            said?.let { VSpace(6); AnimatedQuiet(it, color = c.faint) }
            VSpace(14)
            Text("2. The client's ID (a number)", style = CpType.label, color = c.text)
            VSpace(6)
            CpTextField(clientId, { v -> clientId = v.filter { it.isDigit() }; error = null }, "Client ID")
            VSpace(12)
            BigButton(
                if (busy) "Checking\u2026" else "3. Sign in on AniList",
                { vm.setAniListClient(clientId); web = true },
                busy = busy,
                enabled = clientId.isNotBlank() && !busy,
            )
            error?.let { VSpace(8); Quiet(it, color = c.errorText) }
            VSpace(18)
            Quiet("Or paste the token AniList showed you:", color = c.faint)
            VSpace(6)
            CpTextField(token, { token = it.trim(); error = null }, "Token")
            VSpace(8)
            PillButton("Use this token", { if (token.isNotBlank() && !busy) link(token) })
        }
    }
    if (web) AniListWebDialog(
        url = io.github.prithirajn.trackersync.source.AniList.authorizeUrl(clientId),
        onToken = { t -> web = false; link(t) },
        onClose = { web = false },
    )
}

/** AniList's own sign-in and allow page; the token it then shows (or redirects with) is caught. */
@Composable
private fun AniListWebDialog(url: String, onToken: (String) -> Unit, onClose: () -> Unit) {
    val c = Cp.colors
    var done by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sign in to AniList", style = CpType.bodyStrong, color = c.text)
                    Quiet("Then tap Authorize")
                }
                PillButton("Close", onClose)
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                        CookieManager.getInstance().setAcceptCookie(true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        fun take(t: String?): Boolean {
                            val tok = t?.trim()?.takeIf { io.github.prithirajn.trackersync.source.AniList.looksLikeToken(it) } ?: return false
                            if (!done) { done = true; onToken(tok) }
                            return true
                        }
                        fun check(u: String?): Boolean = take(u?.let { io.github.prithirajn.trackersync.source.AniList.tokenFrom(it) })
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean =
                                check(request?.url?.toString())
                            override fun onPageStarted(view: WebView?, u: String?, favicon: android.graphics.Bitmap?) {
                                if (check(u)) view?.stopLoading()
                            }
                            override fun onPageFinished(view: WebView?, u: String?) {
                                if (check(u) || u?.startsWith(io.github.prithirajn.trackersync.source.AniList.PIN_REDIRECT) != true) return
                                // The token page fills its box after loading; look a few times.
                                fun look(left: Int) {
                                    view?.evaluateJavascript("(function(){var t=document.querySelector('textarea');return t?t.value:''})()") { raw ->
                                        val v = raw?.trim('"')?.replace("\\n", "")
                                        if (!take(v) && left > 0) view?.postDelayed({ look(left - 1) }, 500)
                                    }
                                }
                                look(10)
                            }
                        }
                        loadUrl(url)
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MangaUpdatesSheet(vm: AppViewModel, onClose: () -> Unit) {
    val c = Cp.colors
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
        ) {
            Text("Link MangaUpdates", style = CpType.section, color = c.text)
            VSpace(6)
            Quiet("Your MangaUpdates (Baka-Updates) username and password. The password is only used to sign in; just the session is kept, encrypted.")
            VSpace(14)
            CpTextField(user, { user = it; error = null }, "Username")
            VSpace(10)
            CpTextField(pass, { pass = it; error = null }, "Password", password = true)
            error?.let { VSpace(8); Quiet(it, color = c.errorText) }
            VSpace(14)
            BigButton(
                if (busy) "Signing in\u2026" else "Link",
                {
                    busy = true
                    vm.linkMangaUpdates(user, pass) { e -> busy = false; if (e == null) onClose() else error = e }
                },
                busy = busy,
                enabled = user.isNotBlank() && pass.isNotBlank() && !busy,
            )
        }
    }
}

@Composable
private fun KosyncCard(vm: AppViewModel, state: Snapshot, onAdd: () -> Unit, onRemove: () -> Unit) {
    val c = Cp.colors
    val k = state.kosync
    CpCard {
        if (k == null) {
            Column(Modifier.padding(16.dp)) {
                Quiet("Read in KOReader with its own sync server? Add it here and newer progress there reaches your trackers too, for books already in your Library.")
                VSpace(12)
                PillButton("Add a KOSync server", onAdd)
            }
        } else {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                ServiceTile("K", 40.dp)
                HSpace(12)
                Column(Modifier.weight(1f)) {
                    Text(k.username, style = CpType.bodyStrong, color = c.text)
                    Quiet(k.server.removePrefix("https://").removePrefix("http://"), maxLines = 1)
                    state.kosyncOkAt.takeIf { it > 0 }?.let { AnimatedQuiet("Checked ${ago(it)}", color = c.faint, maxLines = 1) }
                }
                Health.kosync(state)?.let { h -> HealthLight(h) { vm.checkKosync() } }
                PillButton("Remove", onRemove, danger = true)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KosyncSheet(vm: AppViewModel, onClose: () -> Unit) {
    var server by remember { mutableStateOf(io.github.prithirajn.trackersync.source.KosyncClient.SUGGESTED_SERVER) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val c = Cp.colors
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(Modifier.fillMaxWidth().imePadding().padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            Text("Add a KOSync server", style = CpType.section, color = c.text)
            Quiet("The same server, username and password as in KOReader's Progress sync settings. KOReader's own server is filled in.")
            VSpace(16)
            CpTextField(server, { server = it }, "Server", keyboard = KeyboardType.Uri)
            VSpace(12)
            CpTextField(username, { username = it }, "Username")
            VSpace(12)
            CpTextField(password, { password = it }, "Password", password = true)
            error?.let { VSpace(10); Text(it, style = CpType.small, color = c.errorText) }
            VSpace(16)
            BigButton(
                "Add",
                onClick = {
                    busy = true; error = null
                    vm.addKosync(server, username, password) { e -> busy = false; if (e == null) onClose() else error = e }
                },
                busy = busy,
                enabled = username.isNotBlank() && password.isNotEmpty(),
            )
            VSpace(12)
            Quiet("Only a hash of the password is kept, encrypted on this phone, as KOReader does. Books are matched by KOReader's document ID, so set KOReader to the same \u201CDocument matching\u201D method as your CrossPoint (binary or filename).")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasswordSheet(vm: AppViewModel, t: Tracker, onClose: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val c = Cp.colors
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
        ) {
            Text("Link ${t.name}", style = CpType.section, color = c.text)
            Quiet("Your password is used once to sign in. Only the session token is kept, encrypted.")
            VSpace(16)
            CpTextField(email, { email = it }, "Email", keyboard = KeyboardType.Email)
            VSpace(12)
            CpTextField(password, { password = it }, "Password", password = true)
            error?.let { VSpace(10); Text(it, style = CpType.small, color = c.errorText) }
            VSpace(16)
            BigButton(
                "Link ${t.name}",
                onClick = {
                    busy = true; error = null
                    vm.linkTracker(t, { t.signInWithPassword(email.trim(), password) }) { e ->
                        busy = false
                        if (e == null) onClose() else error = e
                    }
                },
                busy = busy,
                enabled = email.isNotBlank() && password.isNotEmpty(),
            )
            VSpace(12)
            Quiet("Signed up with Google or Apple? Set a password for your email in ${t.name} first.")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TokenSheet(vm: AppViewModel, t: Tracker, onClose: () -> Unit) {
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val c = Cp.colors
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(Modifier.fillMaxWidth().imePadding().padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            Text("Link ${t.name}", style = CpType.section, color = c.text)
            Quiet(t.tokenHelp)
            VSpace(12)
            BigButton("Open hardcover.app/account/api", {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://hardcover.app/account/api")))
            }, filled = false)
            VSpace(12)
            CpTextField(token, { token = it }, "API token", "Bearer eyJ…", mono = true)
            error?.let { VSpace(10); Text(it, style = CpType.small, color = c.errorText) }
            VSpace(16)
            BigButton(
                "Link ${t.name}",
                onClick = {
                    busy = true; error = null
                    vm.linkTracker(t, { t.signInWithToken(token) }) { e ->
                        busy = false
                        if (e == null) onClose() else error = e
                    }
                },
                busy = busy,
                enabled = token.isNotBlank(),
            )
            VSpace(12)
            Quiet("The token is stored encrypted on this phone. You can revoke it on hardcover.app any time.")
        }
    }
}

/** Pairing: show the code, open the tracker's app to approve it, wait for the approval. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairSheet(vm: AppViewModel, t: Tracker, onClose: () -> Unit) {
    var link by remember { mutableStateOf<DeviceLink?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val c = Cp.colors
    val context = LocalContext.current
    LaunchedEffect(attempt) {
        link = null; error = null
        vm.startDeviceLink(t, onCode = { link = it }) { e -> if (e == null) onClose() else error = e }
    }
    ModalBottomSheet(
        onDismissRequest = { vm.cancelDeviceLink(); onClose() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            Text("Link ${t.name}", style = CpType.section, color = c.text)
            Quiet(t.pairingHelp.ifEmpty { "Approve this code in ${t.name}." } + " This sheet closes by itself once you do.")
            VSpace(20)
            val l = link
            when {
                error != null -> {
                    Text(error ?: "", style = CpType.body, color = c.errorText)
                    VSpace(16)
                    BigButton("Get a new code", { attempt++ })
                }
                l == null -> Centered { CircularProgressIndicator(Modifier.size(24.dp), color = c.faint) }
                else -> {
                    CpCard {
                        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Quiet("Your code")
                            VSpace(6)
                            Text(
                                l.userCode,
                                style = CpType.pageTitle.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, letterSpacing = 4.sp),
                                color = c.text,
                            )
                        }
                    }
                    VSpace(16)
                    BigButton("Open ${t.name}", {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(l.verificationUri)))
                    })
                    VSpace(12)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), color = c.faint, strokeWidth = 2.dp)
                        HSpace(8)
                        Quiet("Waiting for approval…")
                    }
                }
            }
        }
    }
}

/** Full-screen WebView on the tracker's own sign-in page; its cookies become the login. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebSignIn(vm: AppViewModel, t: Tracker, onClose: () -> Unit) {
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val c = Cp.colors

    fun finish() {
        val cookieUrl = t.cookieUrl ?: return
        val cookies = CookieManager.getInstance().getCookie(cookieUrl) ?: ""
        val ua = webView?.settings?.userAgentString ?: ""
        busy = true; error = null
        vm.linkTracker(t, { t.signInWithCookies(cookies, ua) }) { e ->
            busy = false
            if (e == null) onClose() else error = e
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(c.page)
                .systemBarsPadding()
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconCircleButton(R.drawable.lucide_x, "Cancel", onClose)
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text("Sign in to ${t.name}", style = CpType.bodyStrong, color = c.text)
                    Quiet("Sign in below, then tap Done.", maxLines = 1)
                }
                if (loading) CircularProgressIndicator(Modifier.size(16.dp), color = c.faint, strokeWidth = 2.dp)
                HSpace(8)
                PillButton(if (busy) "Checking…" else "Done", { if (!busy) finish() })
            }
            error?.let {
                Text(it, style = CpType.small, color = c.errorText, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            RowDivider()
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    CookieManager.getInstance().setAcceptCookie(true)
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { loading = true }
                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                CookieManager.getInstance().flush()
                                // StoryGraph sets this cookie only once you're signed in.
                                val sessionCookie = t.sessionCookie
                                val cookies = t.cookieUrl?.let { CookieManager.getInstance().getCookie(it) } ?: ""
                                if (t.id == "storygraph" && sessionCookie != null && cookies.contains("$sessionCookie=") &&
                                    url?.contains("sign_in") == false && !busy
                                ) finish()
                            }
                        }
                        loadUrl(t.signInUrl ?: "about:blank")
                        webView = this
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSheet(title: String, body: String, action: String, onConfirm: () -> Unit, onClose: () -> Unit) {
    val c = Cp.colors
    ModalBottomSheet(onDismissRequest = onClose, shape = CpShapes.sheet, containerColor = c.page) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            Text(title, style = CpType.section, color = c.text)
            VSpace(8)
            Text(body, style = CpType.body, color = c.quiet)
            VSpace(20)
            BigButton(action, { onConfirm(); onClose() }, filled = false, danger = true)
            VSpace(8)
            BigButton("Cancel", onClose, filled = false)
        }
    }
}
