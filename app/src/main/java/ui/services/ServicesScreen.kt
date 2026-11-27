package io.github.prithirajn.readync.ui

import androidx.compose.foundation.layout.height
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.prithirajn.readync.R
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.data.Visibility
import io.github.prithirajn.readync.sync.Health
import io.github.prithirajn.readync.sync.AppUpdater
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.prithirajn.readync.trackers.DeviceLink
import io.github.prithirajn.readync.trackers.HighlightSupport
import io.github.prithirajn.readync.trackers.PrivacySupport
import io.github.prithirajn.readync.trackers.SignInKind
import io.github.prithirajn.readync.trackers.Tracker
import io.github.prithirajn.readync.trackers.Trackers
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.CpShapes
import io.github.prithirajn.readync.ui.theme.CpType

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
    // StoryGraph: its email-and-password sign-in, instead of the web one.
    var byPassword by remember { mutableStateOf(false) }
    var unlinking by remember { mutableStateOf<Tracker?>(null) }
    var settings by remember { mutableStateOf<Tracker?>(null) }
    var signingOut by remember { mutableStateOf(false) }
    var addingKosync by remember { mutableStateOf(false) }
    var removingKosync by remember { mutableStateOf(false) }
    var addingMoon by remember { mutableStateOf(false) }
    var removingMoon by remember { mutableStateOf(false) }
    var moonGuide by remember { mutableStateOf(false) }
    var addingKoHl by remember { mutableStateOf(false) }
    var removingKoHl by remember { mutableStateOf(false) }
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
    var linkingHoopla by remember { mutableStateOf(false) }
    var removingHoopla by remember { mutableStateOf(false) }
    var linkingEverand by remember { mutableStateOf(false) }
    var removingEverand by remember { mutableStateOf(false) }
    var removingLibby by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<String?>(null) }
    var readingLog by remember { mutableStateOf(false) }
    var appSettings by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    // Which kind the Add sheet is for: book trackers, or reading apps and servers.
    var addingApps by remember { mutableStateOf(false) }
    var servicesTab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    // Opened from elsewhere (the KOReader card on Reading, a book's page).
    val pendingAction by AppActions.pending.collectAsState()
    LaunchedEffect(pendingAction) {
        (pendingAction as? AppAction.ServicePage)?.let {
            // "share" and "log" are tabs of this page; the rest are its screens.
            when (it.page) { "share" -> servicesTab = 2; "log" -> servicesTab = 3; else -> page = it.page }
            AppActions.pending.value = null
        }
    }
    val c = Cp.colors
    val linked = Trackers.all.filter { state.service(it.id).linked }
    val more = Trackers.all.filter { !state.service(it.id).linked }
    val linkedTrackers = linked.filter { isBookTracker(it) }
    val linkedApps = linked.filter { !isBookTracker(it) }
    val moreTrackers = more.filter { isBookTracker(it) }
    val moreApps = more.filter { !isBookTracker(it) }
    val context = LocalContext.current
    val appVersion = remember(context) { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }

    val pageScroll = rememberScrollState()
    // Pull past the top to sync now.
    val pullSync = rememberPullToAct { if (!state.running) vm.syncNow() }
    PullFrame(pullSync, state.running, "Pull to sync", "Let go to sync", "Syncing…") { pm ->
        Column(
            pm
                .fillMaxSize()
                .fadeTopEdge(pageScroll.value > 0)
                .verticalScroll(pageScroll)
                .padding(bottom = 32.dp + TabBarSpace)
        ) {
            TabHeader("where your reading comes and goes", "Sync") {
                // The app's own settings (sync, AI, backup, updates) behind the gear.
                IconCircleButton(R.drawable.lucide_settings, "Settings", { appSettings = true })
            }
            VSpace(12)
            Column(Modifier.padding(horizontal = 16.dp)) {
                // Readync on another phone too, and that one sends: said here, with a way to take over.
                PostingDeviceCard(vm, state)
                // A glance before anything else, for the tab you're on: how many are on, how many want
                // you, a light each, and when it last did something (and, under Share & export, what posts next).
                fun trackerLight(t: Tracker): Pair<String, Int> {
                    val svc = state.service(t.id)
                    val wants = svc.needsReauth || svc.lastError != null || summary(state, t).second
                    return t.name to when { !svc.enabled -> 0; wants -> 2; else -> 1 }
                }
                fun level(h: Health.Status) = when (h.level) { Health.Level.OFF -> 0; Health.Level.GOOD -> 1; else -> 2 }
                val full = io.github.prithirajn.readync.Edition.full
                val (onLabel, lights, lastLine) = when (servicesTab) {
                    0, 3 -> Triple(
                        "Trackers on",
                        linkedTrackers.map { trackerLight(it) },
                        linkedTrackers.maxOfOrNull { state.service(it.id).lastSync }?.takeIf { it > 0 }?.let { "Synced ${ago(it)}" } ?: "Not synced yet",
                    )
                    1 -> Triple(
                        "Apps on",
                        listOfNotNull(
                            Health.source(state)?.let { "CrossPoint" to level(it) },
                            Health.kindle(state)?.takeIf { full }?.let { "Kindle" to level(it) },
                            Health.libby(state)?.takeIf { full }?.let { "Libby" to level(it) },
                            Health.audible(state)?.takeIf { full }?.let { "Audible" to level(it) },
                            Health.hoopla(state)?.takeIf { full }?.let { "hoopla" to level(it) },
                            Health.koSsh(state)?.let { "KOReader" to level(it) },
                            Health.everand(state)?.takeIf { full }?.let { "Everand" to level(it) },
                            (Health.mihon(state) ?: Health.anilist(state))?.let { "Manga" to level(it) },
                            Health.kosync(state)?.let { "KOSync" to level(it) },
                            Health.moon(state)?.let { "Moon+" to level(it) },
                        ) + linkedApps.map { trackerLight(it) },
                        (listOf(state.sourceOkAt, state.kosyncOkAt, state.moonOkAt) +
                            (if (full) listOf(state.kindleOkAt, state.libbyOkAt, state.audibleOkAt, state.hooplaOkAt, state.everandOkAt) else emptyList()) +
                            linkedApps.map { state.service(it.id).lastSync }).max().takeIf { it > 0 }?.let { "Checked ${ago(it)}" } ?: "Not checked yet",
                    )
                    else -> Triple(
                        "Connected",
                        SocialNets.all().filter { SocialNets.connected(state, it.id) }.map { it.name to 1 } +
                            listOfNotNull(
                                ("Readwise" to 1).takeIf { state.readwiseToken != null },
                                ("Notion" to 1).takeIf { state.notionToken != null },
                                ("Obsidian" to 1).takeIf { state.obsidianFolder != null },
                            ),
                        if (!io.github.prithirajn.readync.sync.PostingDevice.isThis(androidx.compose.ui.platform.LocalContext.current, state)) "Posting from your other phone"
                        else state.threadsPostLog.maxOrNull()?.let { "Posted ${ago(it * 1000)}" } ?: "Nothing posted yet",
                    )
                }
                run {
                    // The next 3 posts across your social networks (worked out off the main thread, again as things change).
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    var nextPosts by remember { mutableStateOf<List<UpcomingPosts.Upcoming>>(emptyList()) }
                    var openPost by remember { mutableStateOf<UpcomingPosts.Upcoming?>(null) }
                    LaunchedEffect(state.threadsPostLog, state.threadsBooks, state.threadsSeen, state.extras, state.books.size, state.threadsToken) {
                        nextPosts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            runCatching { UpcomingPosts.next(ctx, state) }.getOrDefault(emptyList())
                        }
                    }
                    openPost?.let { u -> UpcomingPostSheet(vm, state, u) { openPost = null } }
                    ServicesMasthead(
                        lights,
                        label = onLabel.lowercase(),
                        lastLine = lastLine,
                        running = state.running && servicesTab != 2,
                        next = if (servicesTab == 2) nextPosts else emptyList(),
                        modifier = Modifier.padding(bottom = 18.dp).animateContentSize(),
                        onOpenPost = { openPost = it },
                    )
                }
                PageTabs(listOf("Trackers", "Sources", "Sharing", "Log"), servicesTab) { servicesTab = it }
                // The tab's page slides in from the side you moved toward.
                androidx.compose.animation.AnimatedContent(
                    servicesTab,
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.spring(dampingRatio = 0.86f, stiffness = 420f)) { it / 5 * dir } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)))
                            .togetherWith(androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(180)) { -it / 6 * dir } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140)))
                            .using(androidx.compose.animation.SizeTransform(clip = false))
                    },
                    label = "servicesTab",
                ) { tab -> Column(Modifier.fillMaxWidth()) {
                when (tab) {
                    0 -> {
                    if (linkedTrackers.isNotEmpty()) {
                        VSpace(16)
                        RisoList {
                            linkedTrackers.forEachIndexed { i, t ->
                                if (i > 0) RowDivider()
                                Box(Modifier.riseIn(i + 1)) { LinkedRow(vm, state, t, onOpen = { settings = t }, onRelink = { signingIn = t }) }
                            }
                        }
                    }
                        // Everything not linked yet waits in the Add sheet instead of a long list here.
                        if (moreTrackers.isNotEmpty()) {
                            VSpace(12)
                            AddServiceRow(if (linkedTrackers.isEmpty()) "Link a book tracker" else "Add a book tracker") { addingApps = false; adding = true }
                        }
                        // Before highlights go out: off unless you want to look at each one first (for spoilers).
                        if (linkedTrackers.isNotEmpty()) {
                            VSpace(16)
                            DeviceLabel("Before highlights go out", color = c.quiet, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
                            CheckHighlightsCard(vm, state)
                        }
                    }
                    1 -> {
                    // Everything else as one short list of rows, each opening its own sheet, so the page
                    // stays a glance instead of a long scroll.
                    VSpace(16)
                    RisoList {
                        NavRow(
                            R.drawable.lucide_library_big, "CrossPoint",
                            listOfNotNull(state.account?.username, state.sourceOkAt.takeIf { it > 0 }?.let { "reached ${ago(it)}" }).joinToString(" · ").ifEmpty { null },
                            trailing = { Health.source(state)?.let { h -> HealthLight(h) { vm.checkCrossPoint() } } },
                        ) { page = "crosspoint" }
                        RowDivider()
                        // Kindle, Libby and Audible: only in the full edition (the shared one leaves them out).
                        if (io.github.prithirajn.readync.Edition.full) {
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
                            NavRow(
                                R.drawable.lucide_library_big, "hoopla",
                                when {
                                    state.hoopla == null -> "Not linked"
                                    state.hooplaError != null -> "Needs you to sign in again"
                                    else -> state.hooplaOkAt.takeIf { it > 0 }?.let { "Checked ${ago(it)}" } ?: "Linked"
                                },
                                summaryWarn = state.hoopla != null && state.hooplaError != null,
                                trailing = { Health.hoopla(state)?.let { h -> HealthLight(h) { vm.checkHoopla() } } },
                            ) { page = "hoopla" }
                            RowDivider()
                            NavRow(
                                R.drawable.lucide_headphones, "Everand",
                                when {
                                    state.everand == null -> "Not linked"
                                    state.everandError != null -> "Needs you to sign in again"
                                    else -> state.everandOkAt.takeIf { it > 0 }?.let { "Checked ${ago(it)}" } ?: "Linked"
                                },
                                summaryWarn = state.everand != null && state.everandError != null,
                                trailing = { Health.everand(state)?.let { h -> HealthLight(h) { vm.checkEverand() } } },
                            ) { page = "everand" }
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
                        RowDivider()
                        NavRow(
                            R.drawable.lucide_moon, "Moon+ Reader",
                            state.moon?.let { m -> m.username + " · " + m.server.substringAfter("://").substringBefore('/') } ?: "Not set up",
                            trailing = { Health.moon(state)?.let { h -> HealthLight(h) { vm.checkMoon() } } },
                        ) { page = "moon" }
                    }
                    // Reading apps and servers linked here: most go both ways, a few only bring your place in.
                    if (linkedApps.isNotEmpty()) {
                        VSpace(16)
                        RisoList {
                            linkedApps.forEachIndexed { i, t ->
                                if (i > 0) RowDivider()
                                Box(Modifier.riseIn(i + 1)) { LinkedRow(vm, state, t, onOpen = { settings = t }, onRelink = { signingIn = t }) }
                            }
                        }
                    }
                    if (moreApps.isNotEmpty()) {
                        VSpace(12)
                        AddServiceRow("Add a reading app or server") { addingApps = true; adding = true }
                    }
                    }
                    // The sync log (moved here from Activity): what each tracker and source did, and what went wrong.
                    3 -> { VSpace(12); SyncLogPanel(state) }
                    else -> {
                        VSpace(16)
                        // Social networks, as connectors: the connected ones, each with its own page; the rest under "Add".
                        DeviceLabel("Social media", color = c.quiet, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
                        SocialsList(vm, state) { id -> page = "social:$id" }
                        // Each place your highlights can go as text, with its own page of options.
                        VSpace(14)
                        DeviceLabel("Highlights as text", color = c.quiet, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
                        RisoList {
                            ExportRow(state, "readwise", "Readwise", state.readwiseToken != null,
                                if (state.readwiseToken != null) "${state.readwiseDone.size} sent" else "Daily review, and Readwise's own exports") { page = "readwise" }
                            RowDivider()
                            ExportRow(state, "notion", "Notion", state.notionToken != null,
                                if (state.notionToken != null) "${state.notionPages.size} book page${if (state.notionPages.size == 1) "" else "s"} · ${state.notionDone.size} highlights" else "A page per book, inside a page of yours") { page = "notion" }
                            RowDivider()
                            ExportRow(state, "obsidian", "Obsidian", state.obsidianFolder != null,
                                if (state.obsidianFolder != null) "${state.obsidianHashes.size} book note${if (state.obsidianHashes.size == 1) "" else "s"} in your vault" else "A Markdown note per book in your vault") { page = "obsidian" }
                        }
                        // Your own copy of everything: backups, moving phones, and history from other apps.
                        VSpace(14)
                        DeviceLabel("Your data", color = c.quiet, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
                        RisoList {
                            NavRow(R.drawable.lucide_download, "Backup & import", "Back up, move to a new phone, or bring in history from Goodreads, StoryGraph, Hardcover or KOReader") { page = "backup" }
                            RowDivider()
                            // Moved here from Activity › Your library, beside the app's other copies of your data.
                            NavRow(R.drawable.lucide_list_checks, "Reading log", "Every book as a spreadsheet (Excel or CSV): dates, rating, review, pages, series, genres, highlights") { readingLog = true }
                        }
                        if (readingLog) ExportSheet(state) { readingLog = false }
                    }
                }
                } }
            }
        }
    }

    if (appSettings) {
        SettingsSheet("Settings", onClose = { appSettings = false }) {
            RisoList {

                val s0 = state.settings
                val every = s0.intervalMinutes.let { m -> if (m < 60) "every $m min" else if (m == 60) "every hour" else "every ${m / 60} hours" }
                NavRow(
                    R.drawable.lucide_settings, "Sync settings",
                    "Syncs $every" + (if (s0.wifiOnly) " on Wi-Fi" else "") + (if (s0.quietFrom >= 0) " · quiet at night" else ""),
                ) { appSettings = false; page = "sync" }
                RowDivider()
                val model = remember(state.settings) { vm.aiModel() }
                NavRow(
                    R.drawable.lucide_sparkles, "On-device AI",
                    model?.first?.substringBeforeLast('.')?.let { "$it · on this phone" } ?: "Off · add a model to try it",
                ) { appSettings = false; page = "ai" }
                RowDivider()
                NavRow(R.drawable.lucide_hash, "Storage", "What the app keeps on this phone, and clearing it") { appSettings = false; page = "storage" }
                RowDivider()
                NavRow(R.drawable.lucide_refresh_cw, "App update", if (appVersion.isNotEmpty()) "Version $appVersion" else null) { appSettings = false; page = "update" }
            }
            VSpace(24)
            Text(
                "${io.github.prithirajn.readync.Edition.name} $appVersion · Fonts: Bodoni Moda, Literata, Lora, Inter, Caveat (OFL) · Icons: Lucide (ISC) · Requests based on ShelfSync (MIT)",
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
    if (adding) {
        SettingsSheet(if (addingApps) "Add a reading app or server" else "Add a book tracker", onClose = { adding = false }) {
            val group = if (addingApps) moreApps else moreTrackers
            if (addingApps) {
                Quiet("Two-way ones take your progress and send their own place back. Ones that read only bring your place in.", Modifier.padding(start = 4.dp, bottom = 10.dp), color = c.faint)
            }
            RisoList {
                group.forEachIndexed { i, t ->
                    if (i > 0) RowDivider()
                    AvailableRow(t, onServer = t.id in state.serverLinked, onLink = { adding = false; signingIn = t })
                }
            }
            VSpace(14)
        }
    }

    page?.let { p ->
        val socialPages = SocialNets.all().map { "social:${it.id}" }
        SettingsSheet(
            when (p) {
                "crosspoint" -> "CrossPoint account"; "kindle" -> "Amazon Kindle"; "libby" -> "Libby"; "audible" -> "Audible"; "hoopla" -> "hoopla"; "everand" -> "Everand"; "mihon" -> "Manga & comics"; "kosync" -> "KOSync server"; "moon" -> "Moon+ Reader"; "kohl" -> "KOReader highlights & bookmarks"
                "sync" -> "Sync settings"; "ai" -> "On-device AI"; "backup" -> "Backup & import"; "threads" -> "Threads"; "socials" -> "Post elsewhere"; in socialPages -> SocialNets.title(p.removePrefix("social:")); "exports" -> "Export highlights"; "readwise" -> "Readwise"; "notion" -> "Notion"; "obsidian" -> "Obsidian"; "storage" -> "Storage"; else -> "App update"
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
                    KoHlHint(state, "Reading in KOReader too? Your position comes through sync, but its highlights, notes and bookmarks don't. They can come from KOReader's own files instead, and your CrossPoint ones can go to KOReader.") { page = "kohl" }
                    RowDivider()
                    Box(Modifier.padding(16.dp)) { BigButton("Sign out", { page = null; signingOut = true }, filled = false, danger = true) }
                }
                "kindle" -> KindleCard(vm, state, onLink = { linkingKindle = true }, onRemove = { page = null; removingKindle = true })
                "mihon" -> MihonCard(vm, state, onRemove = { removingMihon = true }, onLinkMu = { linkingMu = true }, onRemoveMu = { removingMu = true }, onLinkAl = { linkingAl = true }, onRemoveAl = { removingAl = true })
                "audible" -> AudibleCard(vm, state, onLink = { linkingAudible = true }, onRemove = { page = null; removingAudible = true })
                "libby" -> LibbyCard(vm, state, onLink = { linkingLibby = true }, onRemove = { page = null; removingLibby = true })
                "hoopla" -> HooplaCard(vm, state, onLink = { linkingHoopla = true }, onRemove = { page = null; removingHoopla = true })
                "everand" -> EverandCard(vm, state, onLink = { linkingEverand = true }, onRemove = { page = null; removingEverand = true })
                "kosync" -> Column {
                    KosyncCard(vm, state, onAdd = { addingKosync = true }, onRemove = { page = null; removingKosync = true })
                    if (state.kosync != null) {
                        VSpace(12)
                        CpCard { KoHlHint(state, "KOSync carries only your reading position, so KOReader's highlights, notes and bookmarks don't come with it. They can come from KOReader's own files instead.") { page = "kohl" } }
                    }
                }
                "kohl" -> KoHlCard(vm, state, onAdd = { addingKoHl = true }, onRemove = { page = null; removingKoHl = true })
                "threads", "social:threads" -> ThreadsCard(vm, state)
                in socialPages -> SocialPage(vm, state, p.removePrefix("social:"))
                "exports" -> ExportsCard(vm, state)
                "readwise", "notion", "obsidian" -> ExportPage(vm, state, p)
                "moon" -> MoonCard(vm, state, onAdd = { addingMoon = true }, onGuide = { moonGuide = true }, onRemove = { page = null; removingMoon = true })
                "sync" -> SyncSettingsCard(vm, state)
                "ai" -> OnDeviceAiCard(vm, state)
                "backup" -> { BackupCard(vm); VSpace(16); HistoryImportCard(vm) }
                "storage" -> StorageCard(vm, onAi = { page = "ai" })
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
            SignInKind.WEBVIEW -> if (byPassword) PasswordSheet(vm, t) { signingIn = null; byPassword = false }
                else WebSignIn(vm, t, onPassword = if (t.passwordToo) ({ byPassword = true }) else null) { signingIn = null }
            SignInKind.TOKEN -> TokenSheet(vm, t) { signingIn = null }
            SignInKind.DEVICE_CODE -> PairSheet(vm, t) { signingIn = null }
            SignInKind.SERVER -> ServerSignInSheet(vm, t) { signingIn = null }
            SignInKind.OAUTH -> OAuthSignInSheet(vm, t) { signingIn = null }
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
            body = "${io.github.prithirajn.readync.Edition.name} stops checking Kindle reading progress and highlights. Your books remain in your library.",
            action = "Disconnect",
            onConfirm = { vm.removeKindle() },
            onClose = { removingKindle = false },
        )
    }
    if (linkingAudible) AudibleSignInSheet(vm) { linkingAudible = false }
    if (removingMihon) {
        ConfirmSheet(
            title = "Disconnect Mihon?",
            body = "${io.github.prithirajn.readync.Edition.name} stops reading its backups. Your volumes remain in your library.",
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
            body = "${io.github.prithirajn.readync.Edition.name} stops reading your Audible progress and removes itself from your Audible devices. Your books remain in your library.",
            action = "Disconnect",
            onConfirm = { vm.removeAudible() },
            onClose = { removingAudible = false },
        )
    }
    if (linkingLibby) LibbySignInSheet(vm) { linkingLibby = false }
    if (linkingHoopla) HooplaSignInSheet(vm) { linkingHoopla = false }
    if (linkingEverand) EverandWebDialog(vm) { linkingEverand = false }
    if (removingEverand) {
        ConfirmSheet(
            title = "Disconnect Everand?",
            body = "${io.github.prithirajn.readync.Edition.name} stops reading your Everand titles, and the highlights it brought from Everand are removed. Your books remain in your library.",
            action = "Disconnect",
            onConfirm = { vm.removeEverand() },
            onClose = { removingEverand = false },
        )
    }
    if (removingHoopla) {
        ConfirmSheet(
            title = "Disconnect hoopla?",
            body = "${io.github.prithirajn.readync.Edition.name} stops reading your hoopla borrows, and the highlights it brought from hoopla are removed. Your books remain in your library.",
            action = "Disconnect",
            onConfirm = { vm.removeHoopla() },
            onClose = { removingHoopla = false },
        )
    }
    if (removingLibby) {
        ConfirmSheet(
            title = "Disconnect Libby?",
            body = "${io.github.prithirajn.readync.Edition.name} stops checking your Libby loans. Your books and highlights remain in your library.",
            action = "Disconnect",
            onConfirm = { vm.removeLibby() },
            onClose = { removingLibby = false },
        )
    }
    if (addingKosync) KosyncSheet(vm) { addingKosync = false }
    if (addingMoon) MoonSheet(vm, onGuide = { moonGuide = true }) { addingMoon = false }
    if (moonGuide) MoonGuideSheet { moonGuide = false }
    if (addingKoHl) KoHlSheet(vm, state) { addingKoHl = false }
    if (removingKoHl) {
        ConfirmSheet(
            title = "Remove KOReader highlights?",
            body = "${io.github.prithirajn.readync.Edition.name} stops reading the plugin's files. Highlights already brought in stay, and nothing changes in KOReader, on Koofr or on your trackers.",
            action = "Remove",
            onConfirm = { vm.removeKoHighlights() },
            onClose = { removingKoHl = false },
        )
    }
    if (removingMoon) {
        ConfirmSheet(
            title = "Remove Moon+ Reader?",
            body = "${io.github.prithirajn.readync.Edition.name} stops reading its sync files. Books already brought in stay, and nothing changes in Moon+ Reader, on Koofr or on your trackers.",
            action = "Remove",
            onConfirm = { vm.removeMoon() },
            onClose = { removingMoon = false },
        )
    }
    if (removingKosync) {
        ConfirmSheet(
            title = "Remove the KOSync server?",
            body = "${io.github.prithirajn.readync.Edition.name} stops checking it. Nothing changes on the server or on your trackers.",
            action = "Remove",
            onConfirm = { vm.removeKosync() },
            onClose = { removingKosync = false },
        )
    }
    if (signingOut) {
        ConfirmSheet(
            title = "Sign out?",
            body = "${io.github.prithirajn.readync.Edition.name} forgets your CrossPoint account, every linked tracker and all book matches on this phone.",
            action = "Sign out",
            onConfirm = { vm.signOut() },
            onClose = { signingOut = false },
        )
    }
}

/** A settings page as a sheet over Services: its title, then its card(s). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSheet(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    val c = Cp.colors
    GuardedSheet(
        dragHandle = { SheetGrip() },
        onDismissRequest = onClose,
        sheetState = rememberSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .animateContentSize(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 260f))
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
        ) {
            Text(title, style = CpType.section, color = c.text, modifier = Modifier.riseIn(0))
            VSpace(14)
            Column(Modifier.riseIn(1)) { content() }
        }
    }
}

private fun isBeta(t: Tracker) = t.beta

/** One line on how a linked tracker is doing, and whether it's bad news. */
private fun summary(state: Snapshot, t: Tracker): Pair<String, Boolean> {
    val svc = state.service(t.id)
    val waiting = state.pending.values.count { it.service == t.id }
    val toMatch = io.github.prithirajn.readync.sync.Health.needsMatch(state, t.id)
    val review = toMatch.size
    val which = io.github.prithirajn.readync.sync.Health.titles(state, toMatch)
    return when {
        svc.needsReauth -> "Sign in again to keep syncing" to true
        !svc.enabled -> "Paused" to false
        svc.lastError != null -> "Last sync had a problem" to true
        waiting > 0 -> "$waiting waiting to sync" + (if (review > 0) " · match needed: $which" else "") to false
        review > 0 -> "Match needed: $which" to false
        svc.lastSync > 0 -> "Up to date · ${ago(svc.lastSync)}" to false
        else -> "Linked as ${svc.account ?: "you"}" to false
    }
}

@Composable
private fun TrackerName(t: Tracker) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(t.name, fontFamily = io.github.prithirajn.readync.ui.theme.DisplaySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 17.sp, lineHeight = 21.sp, color = Cp.colors.text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (!isBookTracker(t)) DirectionTag(t)
        if (isBeta(t)) Chip("Beta")
    }
}

/** Book trackers (and Micro.blog's shelves) only take your reading; everything else is a reading app or server. */
private val BOOK_TRACKERS = setOf("hardcover", "fable", "pagebound", "storygraph", "goodreads", "seekquel", "librarything", "microblog")
private fun isBookTracker(t: Tracker) = t.id in BOOK_TRACKERS

/** Which way a reading app goes: both ways, or only bringing your place in. */
@Composable
private fun DirectionTag(t: Tracker) {
    val c = Cp.colors
    val (text, col) = when {
        t.writes && t.bringsPositions -> "\u21C4 Two-way" to c.accentText
        !t.writes -> "\u2190 Reads" to c.textMid
        else -> "\u2192 Sends" to c.textMid
    }
    Chip(text, color = col)
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
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dim by androidx.compose.animation.core.animateFloatAsState(if (svc.enabled) 1f else 0.4f, Motion.medium(), label = "paused")
        val shake = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(svc.needsReauth) {
            if (svc.needsReauth) for (x in listOf(6f, -5f, 4f, -2f, 0f)) shake.animateTo(x, androidx.compose.animation.core.tween(55))
        }
        Box(Modifier.graphicsLayer { translationX = shake.value * density; rotationZ = shake.value * 0.8f }) { ServiceTile(t.name, 40.dp, muted = !svc.enabled) }
        HSpace(14)
        Column(Modifier.weight(1f)) {
            TrackerName(t)
            val tone by animateColorAsState(if (bad) c.errorText else c.quiet, Motion.medium(), label = "summary")
            AnimatedQuiet(line, color = tone, maxLines = 1)
        }
        // A light only when there's something to say: a paused tracker's switch says it already.
        Health.tracker(state, t.id, t.name)?.takeIf { it.level != Health.Level.OFF }?.let { h ->
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
private fun AvailableRow(t: Tracker, onServer: Boolean = false, onLink: () -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        ServiceTile(t.name)
        HSpace(12)
        Column(Modifier.weight(1f)) {
            TrackerName(t)
            Quiet(if (onServer) "On your CrossPoint server; link here for your other books" else t.hint ?: hints[t.id] ?: "", maxLines = 1)
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
    val toMatch = io.github.prithirajn.readync.sync.Health.needsMatch(state, t.id)
    val review = toMatch.size
    GuardedSheet(
        dragHandle = { SheetGrip() },
        onDismissRequest = onClose,
        sheetState = rememberSheetState(skipPartiallyExpanded = true),
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
                if (t.id in state.serverLinked) "Also linked on your CrossPoint server: it sends your CrossPoint books to ${t.name}, and Readync sends the rest (Kindle, Libby, Audible, KOReader, physical books), so nothing goes twice." else null,
                if (waiting > 0) "$waiting book${if (waiting == 1) "" else "s"} waiting to sync" + (if (failed > 0) ", $failed retrying" else "") else null,
                if (review > 0) "Match needed: ${io.github.prithirajn.readync.sync.Health.titles(state, toMatch)}. Open ${if (review == 1) "it" else "each"} in Library to pick one." else null,
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
                Option(if (t.writes) "Sync to ${t.name}" else "Read my place from ${t.name}", if (t.writes) null else t.privacyNote) {
                    CpToggle(svc.enabled, { vm.setEnabled(t, it) }, "Sync to ${t.name}")
                }
                if (t.writes) RowDivider()
                if (t.writes) Option("Who can see updates", t.privacyNote) {
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
                    // Turned on, only newer highlights go by themselves: the earlier ones can be sent too.
                    val earlier = remember(svc.highlights, svc.highlightsSince, state.sentClippings, state.resend, state.links) { vm.earlierUnsent(t).size }
                    if (svc.highlights && allowed && earlier > 0) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Quiet("$earlier highlight${if (earlier == 1) " was" else "s were"} made before you turned this on, so ${if (earlier == 1) "it isn't" else "they aren't"} sent.", Modifier.weight(1f), color = c.faint)
                            HSpace(8)
                            PillButton("Send ${if (earlier == 1) "it" else "them"} too", { vm.sendEarlier(t) }, outlined = false)
                        }
                    }
                }
                if (t.id == io.github.prithirajn.readync.trackers.SeekquelTracker.id) {
                    RowDivider()
                    SeekquelAccountOption(vm, svc.credential)
                }
                if (t.id == io.github.prithirajn.readync.trackers.FableTracker.id && svc.highlights) {
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
                    t.betaNote ?: if (t.signInKind == SignInKind.WEBVIEW) "Beta: ${t.name} has no public API, so the app works it like its website. It may break when the site changes."
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
    val session = remember(credential) { io.github.prithirajn.readync.trackers.Session(credential) }
    val F = io.github.prithirajn.readync.trackers.FableTracker
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
    val signedIn = remember(credential) { io.github.prithirajn.readync.trackers.SeekquelTracker.accountEmail(credential) }
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
    val webOn = remember(credential) { io.github.prithirajn.readync.trackers.Session(credential).data.optBoolean("webSignedIn") }
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
    Dialog(onDismissRequest = onClose, properties = FullScreen) {
        Column(Modifier.fillMaxSize().background(c.page).fullScreenSafe()) {
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
        SettingLine("Tell me about a different position", "When another source is further along than a book's main source. That book's trackers wait until you choose.") {
            CpToggle(s.notifyPositions, { on -> vm.updateSettings { it.copy(notifyPositions = on) } }, "Notify on different positions")
        }
        // Sources you said to always use, for every book: stop any of them here.
        if (state.trustedSources.isNotEmpty()) {
            RowDivider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Used without asking", style = CpType.label, color = c.text)
                Quiet("Their positions are taken for every book, even ahead of its main source.", color = c.faint)
                for (src in state.trustedSources.sorted()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(io.github.prithirajn.readync.sync.Primary.name(src), style = CpType.body, color = c.text, modifier = Modifier.weight(1f))
                        PillButton("Ask again", { vm.untrustSource(null, src) })
                    }
                }
            }
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

/**
 * Two kinds of file. A full backup: everything, sign-ins included, locked with a password, and
 * restoring it replaces what's on the phone. Reading data: the library, progress, highlights,
 * ratings and tracker matches without any sign-ins, merged into whatever's on a phone, from any
 * version of the app. One Import button takes either.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupCard(vm: AppViewModel) {
    val c = Cp.colors
    var mode by remember { mutableStateOf<String?>(null) } // "save" | "restore"
    var target by remember { mutableStateOf<Uri?>(null) }
    var incoming by remember { mutableStateOf<io.github.prithirajn.readync.sync.AccountData.Read?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val day = remember { java.time.LocalDate.now() }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) { target = uri; mode = "save" }
    }
    val createData = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) { error = null; vm.exportAccountData(uri) { e -> error = e } }
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; error = null
        vm.readIncoming(uri) { kind, e ->
            busy = false
            when (kind) {
                is AppViewModel.Incoming.FullBackup -> { target = uri; mode = "restore" }
                is AppViewModel.Incoming.Data -> incoming = kind.read
                null -> error = e
            }
        }
    }
    CpCard {
        Column(Modifier.padding(16.dp)) {
            Text("Full backup", style = CpType.label, color = c.text)
            Quiet("Everything, tracker sign-ins included, in one file locked with a password you choose. Restoring it replaces what's on the phone.")
            VSpace(10)
            PillButton("Back up to a file", { create.launch("readync-$day.tsbackup") })
            VSpace(18)
            Text("Reading data", style = CpType.label, color = c.text)
            Quiet("Your library, progress, highlights, ratings, reviews and tracker matches, without any sign-ins. Importing merges it into this phone (from any version of the app) and sends nothing to your trackers. Keep it private: it has your reading in it.")
            VSpace(10)
            PillButton("Export reading data", { createData.launch("readync-data-$day.json") })
            VSpace(18)
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillButton(if (busy) "Reading…" else "Import a file", { if (!busy) openDoc.launch(arrayOf("*/*")) }, outlined = false)
                HSpace(10)
                Quiet("Either kind; it's told apart for you.", color = c.faint)
            }
            error?.let { VSpace(8); Text(it, style = CpType.small, color = c.errorText) }
        }
    }
    val uri = target
    if (mode != null && uri != null) {
        BackupPasswordSheet(vm, mode == "save", uri) { mode = null; target = null }
    }
    incoming?.let { read ->
        var merging by remember(read) { mutableStateOf(false) }
        var mergeError by remember(read) { mutableStateOf<String?>(null) }
        GuardedSheet(
            dragHandle = { SheetGrip() },
            onDismissRequest = { incoming = null },
            sheetState = rememberSheetState(skipPartiallyExpanded = true),
            shape = CpShapes.sheet,
            containerColor = c.page,
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
                Text("Bring in reading data", style = CpType.section, color = c.text)
                VSpace(6)
                val when_ = read.exportedAt.takeIf { it > 0 }?.let {
                    java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))
                }
                Quiet(
                    "${read.books} book${if (read.books == 1) "" else "s"} and ${read.highlights} highlight${if (read.highlights == 1) "" else "s"}" +
                        (when_?.let { ", saved $it" } ?: "") + (read.fromVersion?.let { " by version $it" } ?: "") + ".",
                )
                if (read.newer) {
                    VSpace(6)
                    Quiet("It's from a newer version of the app: what this version doesn't know yet is left out.", color = c.faint)
                }
                VSpace(10)
                Quiet("It's merged with what's here: new books are added, books on both keep the more recent progress, and gaps (a rating, dates, a cover) are filled in. Your sign-ins and settings stay as they are, and nothing is sent to your trackers.", color = c.faint)
                mergeError?.let { VSpace(8); Text(it, style = CpType.small, color = c.errorText) }
                VSpace(16)
                BigButton("Merge into this phone", {
                    merging = true; mergeError = null
                    vm.mergeAccountData(read) { e -> merging = false; if (e == null) incoming = null else mergeError = e }
                }, busy = merging)
            }
        }
    }
}

/**
 * Reading history from elsewhere: a tracker's export, KOReader's reading statistics, or straight
 * from a linked tracker's shelves. Shown as a review first; nothing's brought in until you say so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryImportCard(vm: AppViewModel) {
    val c = Cp.colors
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var plan by remember { mutableStateOf<io.github.prithirajn.readync.sync.HistoryImport.Plan?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "file"; error = null
        vm.readHistoryFile(uri) { p, e -> busy = null; plan = p; error = e }
    }
    val fromTrackers = remember { vm.historyTrackers() }
    CpCard {
        Column(Modifier.padding(16.dp)) {
            Text("Import reading history", style = CpType.label, color = c.text)
            Quiet("A Goodreads, StoryGraph or Hardcover export (CSV), any other CSV of your books, KOReader's statistics.sqlite3 (in its settings folder) or a ZIP of it, up to 50 MB. With the on-device AI set up, a CSV with unfamiliar column names is worked out for you.")
            VSpace(6)
            Quiet("Books already here keep their progress, statuses, dates and stats; only a missing rating, review or dates are filled in. New books come in as history, sent to no tracker until you choose. You see what's in it before anything changes.", color = c.faint)
            VSpace(12)
            PillButton(if (busy == "file") "Reading…" else "Choose a file", { if (busy == null) pick.launch(arrayOf("*/*")) }, outlined = false)
            if (fromTrackers.isNotEmpty()) {
                VSpace(10)
                Quiet("Or straight from a linked tracker:", color = c.faint)
                VSpace(6)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (t in fromTrackers) {
                        PillButton(if (busy == t.id) "Reading…" else "From ${t.name}", {
                            if (busy == null) {
                                busy = t.id; error = null
                                vm.readHistoryFrom(t) { p, e -> busy = null; plan = p; error = e }
                            }
                        })
                    }
                }
            }
            VSpace(8)
            Quiet("StoryGraph: Manage Account › Export StoryGraph Library. Goodreads: My Books › Import and export › Export Library. Fable and Pagebound: their export, if it's a CSV.", color = c.faint)
            error?.let { VSpace(8); Text(it, style = CpType.small, color = c.errorText) }
        }
    }
    plan?.let { p ->
        var withWant by remember(p) { mutableStateOf(false) }
        var applying by remember(p) { mutableStateOf(false) }
        var applyError by remember(p) { mutableStateOf<String?>(null) }
        GuardedSheet(
            dragHandle = { SheetGrip() },
            onDismissRequest = { plan = null },
            sheetState = rememberSheetState(skipPartiallyExpanded = true),
            shape = CpShapes.sheet,
            containerColor = c.page,
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
                Text("Your ${p.source.name} history", style = CpType.section, color = c.text)
                VSpace(4)
                Quiet("${p.total} book${if (p.total == 1) "" else "s"} in it.")
                if (p.columnsByAi.isNotEmpty()) {
                    VSpace(10)
                    CpCard {
                        Column(Modifier.padding(16.dp)) {
                            Text("Columns worked out by the on-device AI", style = CpType.label, color = c.text)
                            Quiet("Its column names weren't ones Readync knows, so the model read a few rows. Each match was checked against the values; if one looks wrong, close this and fix that column's name in the file.", color = c.faint)
                            VSpace(8)
                            for ((field, column) in p.columnsByAi) {
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                    Text(field.replaceFirstChar { it.uppercase() }, style = CpType.body, color = c.textMid, modifier = Modifier.weight(1f))
                                    Text("\u201C$column\u201D", style = CpType.body, color = c.text)
                                }
                            }
                        }
                    }
                }
                VSpace(14)
                CpCard {
                    Column(Modifier.padding(16.dp)) {
                        val byStatus = p.fresh.groupingBy { it.status?.label ?: "No status" }.eachCount()
                        HistoryLine("New here", p.fresh.size, byStatus.entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.value} ${it.key.lowercase()}" })
                        HistoryLine("Already here, gaps filled", p.fills.size,
                            p.fills.flatMap { it.adds }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.value} ${it.key}" })
                        HistoryLine("Already here, nothing to add", p.same, null)
                        HistoryLine("Want to Read", p.wantToRead.size, if (withWant) "brought in" else "left out")
                    }
                }
                if (p.wantToRead.isNotEmpty()) {
                    VSpace(10)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Bring in Want to Read too", style = CpType.body, color = c.text, modifier = Modifier.weight(1f))
                        CpToggle(withWant, { withWant = it }, "Bring in Want to Read too")
                    }
                }
                VSpace(12)
                Quiet("Nothing is sent to your trackers. New books show under the library as history; open one and tap Send to my trackers to sync it.", color = c.faint)
                applyError?.let { VSpace(8); Text(it, style = CpType.small, color = c.errorText) }
                VSpace(16)
                val count = p.fresh.size + p.fills.size + (if (withWant) p.wantToRead.size else 0)
                BigButton(if (count == 0) "Nothing to bring in" else "Bring in $count book${if (count == 1) "" else "s"}", {
                    applying = true; applyError = null
                    vm.applyHistory(p, withWant) { e -> applying = false; if (e == null) plan = null else applyError = e }
                }, busy = applying, enabled = count > 0)
            }
        }
    }
}

@Composable
private fun HistoryLine(label: String, count: Int, detail: String?) {
    val c = Cp.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(label, style = CpType.body, color = c.text)
            detail?.takeIf { it.isNotBlank() && count > 0 }?.let { Quiet(it, color = c.faint) }
        }
        Text(count.toString(), style = CpType.bodyStrong, color = if (count > 0) c.text else c.faint)
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
    val supported = remember { io.github.prithirajn.readync.ai.LocalModel.supported() }
    // A download running in the background (it outlives this screen): shown here, and its ending said once.
    val dlState = io.github.prithirajn.readync.ai.ModelDownload.state.collectAsState()
    val dl = dlState.value
    LaunchedEffect(dl?.done, dl?.error) {
        val d = dl ?: return@LaunchedEffect
        if (d.done) {
            model = vm.aiModel(); choosing = false
            vm.notify("On-device AI is ready", d.name)
            io.github.prithirajn.readync.ai.ModelDownload.clear()
        } else if (d.error != null) {
            error = d.error
            io.github.prithirajn.readync.ai.ModelDownload.clear()
        }
    }
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
                dl != null && !dl.done && dl.error == null -> {
                    Text("Downloading ${dl.name}… " + (dl.progress?.let { "${(it * 100).toInt()}%" } ?: "starting"), style = CpType.small, color = c.accentText)
                    VSpace(6)
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { dl.progress ?: 0f }, modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.divider,
                    )
                    VSpace(6)
                    Quiet("It carries on while you use the rest of the app, or leave it: the notification shows how far it's got. If the network drops, it picks up where it stopped.", color = c.faint)
                    VSpace(8)
                    PillButton("Stop the download", { io.github.prithirajn.readync.ai.ModelDownload.stop(context) }, danger = true)
                }
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
                    val ram = remember { io.github.prithirajn.readync.ai.LocalModel.phoneRamGb(context) }
                    val best = remember { io.github.prithirajn.readync.ai.LocalModel.recommended(context) }
                    Quiet("This phone has $ram GB of memory: ${best.name} is the best it can run well.", color = c.faint)
                    VSpace(6)
                    for (choice in io.github.prithirajn.readync.ai.LocalModel.catalog) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                                Text("${choice.name} · ${choice.size}", style = CpType.body, color = c.text)
                                if (choice == best) Text("Recommended for this phone", style = CpType.small, color = c.accentText)
                                else if (choice.minRamGb > ram) Text("Needs ${choice.minRamGb} GB of memory; may not run here", style = CpType.small, color = c.errorText)
                                Quiet(choice.note, color = c.faint)
                            }
                            PillButton("Get", {
                                error = null
                                vm.downloadModel(choice)
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
                io.github.prithirajn.readync.ai.LocalModel.catalog.firstOrNull { e.startsWith(it.name) }?.let { ch ->
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
    var keepOther by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    GuardedSheet(
        dragHandle = { SheetGrip() },
        onDismissRequest = onClose,
        sheetState = rememberSheetState(skipPartiallyExpanded = true),
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
            } else {
                // Moving phones (the usual), or Readync on both: then only the other one sends, so nothing goes twice.
                VSpace(8)
                ThreadsSwitch("Readync stays on my other phone too", "Then that phone keeps sending to your trackers and posting; this one shows everything", keepOther) { keepOther = it }
            }
            error?.let { VSpace(10); Text(it, style = CpType.small, color = c.errorText) }
            VSpace(16)
            BigButton(
                if (saving) "Save backup" else "Restore",
                onClick = {
                    if (saving && password != again) { error = "The passwords don't match"; return@BigButton }
                    busy = true; error = null
                    val done: (String?) -> Unit = { e -> busy = false; if (e == null) onClose() else error = e }
                    if (saving) vm.exportBackup(uri, password, done) else vm.restoreBackup(uri, password, keepOther, done)
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
    GuardedSheet(
        dragHandle = { SheetGrip() },
        onDismissRequest = onClose,
        sheetState = rememberSheetState(skipPartiallyExpanded = true),
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
                for (item in io.github.prithirajn.readync.source.KindleClient.REGIONAL_DOMAINS) {
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
    vm.state.value.kindle?.domain ?: io.github.prithirajn.readync.source.KindleClient.DEFAULT_DOMAIN

@Composable
private fun KindleWebDialog(domain: String, onDone: (String, String) -> Unit, onClose: () -> Unit) {
    val c = Cp.colors
    val url = "https://$domain/notebook"
    var detectedCookies by remember { mutableStateOf<String?>(null) }
    var detectedUa by remember { mutableStateOf<String?>(null) }
    Dialog(
        onDismissRequest = onClose,
        properties = FullScreen,
    ) {
        Column(Modifier.fillMaxSize().background(c.page).fullScreenSafe()) {
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
                                val merged = io.github.prithirajn.readync.net.CookieJar.parse("$c2; $c1; $c3").header()
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

    GuardedSheet(
        dragHandle = { SheetGrip() },
        onDismissRequest = onClose,
        sheetState = rememberSheetState(skipPartiallyExpanded = true),
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
        properties = FullScreen,
    ) {
        Column(Modifier.fillMaxSize().background(c.page).fullScreenSafe()) {
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

/** Everand: your Saved titles and reading history, with progress, merged into the books you already have. */
@Composable
private fun EverandCard(vm: AppViewModel, state: Snapshot, onLink: () -> Unit, onRemove: () -> Unit) {
    val c = Cp.colors
    val e = state.everand
    CpCard {
        if (e == null) {
            Column(Modifier.padding(16.dp)) {
                Quiet("Read or listen on Everand? Link it and the titles in your Saved list and reading history come in with how far you are, and finished ones as finished, with your highlights and notes. A title you also have elsewhere stays one book, at whichever place is further along, and your trackers get it.")
                VSpace(12)
                PillButton("Link Everand", onLink)
            }
        } else {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ServiceTile("Everand", 40.dp, muted = !e.enabled)
                    HSpace(12)
                    Column(Modifier.weight(1f)) {
                        Text("Everand", style = CpType.bodyStrong, color = c.text)
                        val err = state.everandError
                        if (err != null) AnimatedQuiet(err, color = c.errorText, maxLines = 2)
                        else AnimatedQuiet(state.everandOkAt.takeIf { it > 0 }?.let { "Checked ${ago(it)}" } ?: "Not checked yet", color = c.faint, maxLines = 1)
                    }
                    Health.everand(state)?.let { st -> HealthLight(st) { vm.checkEverand() } }
                }
                VSpace(10)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.everandError != null) PillButton("Sign in again", onLink)
                    PillButton("Disconnect", onRemove, danger = true)
                }
                VSpace(10)
                RowDivider()
                VSpace(10)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sync from Everand", style = CpType.body, color = c.text)
                        Quiet("Read your titles and progress on each sync", color = c.faint)
                    }
                    CpToggle(e.enabled, { vm.setEverandEnabled(it) }, "Sync from Everand")
                }
                VSpace(10)
                RowDivider()
                VSpace(10)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sync highlights & notes", style = CpType.body, color = c.text)
                        Quiet("From Everand's reader, for ebooks and comics", color = c.faint)
                    }
                    CpToggle(e.syncHighlights, { vm.setEverandHighlights(it) }, "Sync highlights")
                }
            }
        }
    }
}

/**
 * everand.com in a web view: you sign in there (email, Google or Facebook, as on Everand), and the
 * sign-in the website keeps is used for reading your titles. Done appears once your Saved list answers.
 */
@Composable
private fun EverandWebDialog(vm: AppViewModel, onClose: () -> Unit) {
    val c = Cp.colors
    var loaded by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var signedIn by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        while (!signedIn) {
            kotlinx.coroutines.delay(2000)
            // Signed in once the Saved list answers from the page itself (a signed-out one answers 401).
            wv.evaluateJavascript(
                "(function(){try{var x=new XMLHttpRequest();x.open('GET','/saved/items/mixed/1',false);x.setRequestHeader('Accept','application/json');x.setRequestHeader('X-Requested-With','XMLHttpRequest');x.send();return x.status;}catch(e){return 0;}})()"
            ) { raw -> if (raw?.trim() == "200" && wv.url?.contains("everand.com") == true) signedIn = true }
        }
    }

    Dialog(onDismissRequest = onClose, properties = FullScreen) {
        Column(Modifier.fillMaxSize().background(c.page).fullScreenSafe()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Sign in to Everand", style = CpType.bodyStrong, color = c.text)
                    Quiet(
                        when {
                            error != null -> error!!
                            linking -> "Linking…"
                            signedIn -> "Signed in. Tap Done."
                            loaded -> "Sign in the way you do on Everand. Your password isn't seen or kept here."
                            else -> "Loading everand.com…"
                        },
                        color = if (error != null) c.errorText else c.quiet,
                    )
                }
                HSpace(8)
                if (signedIn && !linking) {
                    PillButton("Done", {
                        val cookies = CookieManager.getInstance().getCookie(io.github.prithirajn.readync.source.EverandClient.BASE)
                        if (cookies.isNullOrBlank()) { error = "Couldn't read the sign-in. Try signing in again."; return@PillButton }
                        linking = true
                        error = null
                        vm.connectEverand(cookies) { err -> linking = false; if (err == null) onClose() else error = err }
                    })
                    HSpace(6)
                }
                PillButton("Close", onClose)
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) { loaded = true }
                        }
                        webViewRef = this
                        loadUrl(io.github.prithirajn.readync.source.EverandClient.SIGN_IN_URL)
                    }
                },
            )
        }
    }
}

/** hoopla: your borrows, how far in you are, and your highlights, merged into the books you already have. */
@Composable
private fun HooplaCard(vm: AppViewModel, state: Snapshot, onLink: () -> Unit, onRemove: () -> Unit) {
    val c = Cp.colors
    val h = state.hoopla
    CpCard {
        if (h == null) {
            Column(Modifier.padding(16.dp)) {
                Quiet("Borrow ebooks, audiobooks or comics on hoopla? Link it and what you're reading comes in, with your highlights and notes. A title you also have elsewhere stays one book, at whichever place is further along, and your trackers get it.")
                VSpace(12)
                PillButton("Link hoopla", onLink)
            }
        } else {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ServiceTile("hoopla", 40.dp, muted = !h.enabled)
                    HSpace(12)
                    Column(Modifier.weight(1f)) {
                        Text("hoopla", style = CpType.bodyStrong, color = c.text)
                        val err = state.hooplaError
                        if (err != null) AnimatedQuiet(err, color = c.errorText, maxLines = 2)
                        else AnimatedQuiet(state.hooplaOkAt.takeIf { it > 0 }?.let { "Checked ${ago(it)}" } ?: "Not checked yet", color = c.faint, maxLines = 1)
                    }
                    Health.hoopla(state)?.let { st -> HealthLight(st) { vm.checkHoopla() } }
                }
                VSpace(10)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.hooplaError != null) PillButton("Sign in again", onLink)
                    PillButton("Disconnect", onRemove, danger = true)
                }
                VSpace(10)
                RowDivider()
                VSpace(10)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sync from hoopla", style = CpType.body, color = c.text)
                        Quiet("Read your borrows and progress on each sync", color = c.faint)
                    }
                    CpToggle(h.enabled, { vm.setHooplaEnabled(it) }, "Sync from hoopla")
                }
                VSpace(10)
                RowDivider()
                VSpace(10)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sync highlights & notes", style = CpType.body, color = c.text)
                        Quiet("From hoopla's reader, for ebooks and comics", color = c.faint)
                    }
                    CpToggle(h.syncHighlights, { vm.setHooplaHighlights(it) }, "Sync highlights")
                }
            }
        }
    }
}

/**
 * Links hoopla through hoopladigital.com: you sign in there (with your library card or hoopla
 * email), and the sign-in the website gets is kept for reading your borrows. A token can be
 * pasted instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HooplaSignInSheet(vm: AppViewModel, onClose: () -> Unit) {
    val c = Cp.colors
    var inWeb by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var linking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun link(t: String) {
        linking = true
        error = null
        vm.connectHoopla(t) { err -> linking = false; if (err == null) onClose() else error = err }
    }
    if (inWeb) {
        HooplaWebDialog(onDone = { t -> inWeb = false; link(t) }, onClose = { inWeb = false })
    }
    GuardedSheet(
        dragHandle = { SheetGrip() },
        onDismissRequest = onClose,
        sheetState = rememberSheetState(skipPartiallyExpanded = true),
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
            Text("Link hoopla", style = CpType.section, color = c.text)
            VSpace(12)
            CpCard {
                Column(Modifier.padding(16.dp)) {
                    Text("1  Tap Sign in on hoopladigital.com below", style = CpType.body, color = c.text)
                    VSpace(6)
                    Text("2  Sign in the way you do in hoopla: your library card, or your hoopla email", style = CpType.body, color = c.text)
                    VSpace(6)
                    Text("3  When it says you're signed in, tap Done at the top", style = CpType.body, color = c.text)
                }
            }
            VSpace(8)
            Quiet("Your password is typed into hoopla's own page and isn't seen or kept here.", color = c.faint)
            VSpace(16)
            BigButton(if (linking) "Linking…" else "Sign in on hoopladigital.com", { inWeb = true }, busy = linking, enabled = !linking)
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
                CpTextField(token, { token = it; error = null }, "hoopla token", placeholder = "Bearer …", mono = true)
                VSpace(10)
                BigButton(
                    if (linking) "Linking…" else "Link",
                    { link(token.trim()) },
                    busy = linking,
                    enabled = io.github.prithirajn.readync.source.HooplaClient.tokenFrom(token) != null && !linking,
                )
            }
        }
    }
}

/**
 * hoopladigital.com in a web view. Once you're signed in, the website sends its sign-in with each
 * request to hoopla's gateway; that's caught here (and looked for in the site's saved sign-in).
 */
@Composable
private fun HooplaWebDialog(onDone: (String) -> Unit, onClose: () -> Unit) {
    val c = Cp.colors
    var found by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var wentToHistory by remember { mutableStateOf(false) }

    LaunchedEffect(webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        while (found == null) {
            kotlinx.coroutines.delay(1500)
            // The website keeps your sign-in in its "token" cookie once you've signed in.
            val t = io.github.prithirajn.readync.source.HooplaClient.tokenFromCookies(CookieManager.getInstance().getCookie("https://www.hoopladigital.com"))
            if (t != null) found = t
        }
    }

    Dialog(onDismissRequest = onClose, properties = FullScreen) {
        Column(Modifier.fillMaxSize().background(c.page).fullScreenSafe()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Sign in to hoopla", style = CpType.bodyStrong, color = c.text)
                    Quiet(
                        when {
                            found != null -> "Signed in. Tap Done."
                            loaded -> "Sign in with your library card or hoopla email."
                            else -> "Loading hoopladigital.com…"
                        }
                    )
                }
                HSpace(8)
                found?.let { t ->
                    PillButton("Done", { onDone(t) })
                    HSpace(6)
                }
                PillButton("Close", onClose)
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                                val r = request ?: return null
                                if (r.url?.host?.endsWith("hoopladigital.com") == true) {
                                    val auth = r.requestHeaders?.entries?.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }?.value
                                    io.github.prithirajn.readync.source.HooplaClient.tokenFrom(auth ?: "")?.let { t ->
                                        if (auth?.startsWith("Bearer", ignoreCase = true) == true) view?.post { if (found == null) found = t }
                                    }
                                }
                                return null
                            }
                            override fun onPageFinished(view: WebView?, url: String?) {
                                loaded = true
                                // Signed in and away from the sign-in page: open your history, which asks the gateway with the sign-in.
                                if (found == null && !wentToHistory && url != null && !url.contains("/login") && url.contains("hoopladigital.com")) {
                                    wentToHistory = true
                                    view?.loadUrl(io.github.prithirajn.readync.source.HooplaClient.HISTORY_URL)
                                }
                            }
                        }
                        webViewRef = this
                        loadUrl(io.github.prithirajn.readync.source.HooplaClient.SIGN_IN_URL)
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
    var signIn by remember { mutableStateOf<io.github.prithirajn.readync.source.AudibleClient.Companion.SignIn?>(null) }
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
    GuardedSheet(
        dragHandle = { SheetGrip() },
        onDismissRequest = onClose,
        sheetState = rememberSheetState(skipPartiallyExpanded = true),
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
            Quiet("Pick your Audible store, then sign in on Amazon's page. ${io.github.prithirajn.readync.Edition.name} is added to your Audible devices (as \u201CAudible for iPhone\u201D); your password isn't seen or kept.")
            VSpace(12)
            if (linking) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), color = c.faint, strokeWidth = 2.dp)
                    HSpace(10)
                    Quiet("Linking\u2026")
                }
            } else {
                CpCard {
                    io.github.prithirajn.readync.source.AudibleClient.STORES.forEachIndexed { i, st ->
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
    signIn: io.github.prithirajn.readync.source.AudibleClient.Companion.SignIn,
    onCode: (String) -> Unit,
    onClose: () -> Unit,
) {
    val c = Cp.colors
    var done by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = FullScreen) {
        Column(Modifier.fillMaxSize().background(c.page).fullScreenSafe()) {
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
                            val code = url?.let { io.github.prithirajn.readync.source.AudibleClient.codeFrom(it) } ?: return false
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
    fun lab(n: Double) = io.github.prithirajn.readync.sync.ComicIssues.label(n)
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
                        m.exact -> "From ${io.github.prithirajn.readync.source.ComicCollections.sourceName(m)} \u00B7 ${m.volumes.size} volume${if (m.volumes.size == 1) "" else "s"}"
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
                    m.lastIssue?.let { Quiet("Issues after #${io.github.prithirajn.readync.sync.ComicIssues.label(it)} count singly until their volume is out.", color = c.faint) }
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
                val what = (if (l.comic) "Issue #" else "Chapter ") + io.github.prithirajn.readync.sync.ComicIssues.label(l.at)
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
        io.github.prithirajn.readync.source.ComicCollections.Map(m.title, m.volumes.map { v -> io.github.prithirajn.readync.source.ComicCollections.Volume(v.number, v.chapters.map { it.number }) }, m.source, m.at)
    }
    val lab = { n: Double -> io.github.prithirajn.readync.sync.ComicIssues.label(n) }
    val rows = remember {
        androidx.compose.runtime.mutableStateListOf<Pair<String, String>>().apply {
            val vs = start?.volumes.orEmpty().take(40)
            if (vs.isEmpty()) add(if (comic) "1" to "6" else "1" to "9") else vs.forEach { v -> add(lab(v.issues.min()) to lab(v.issues.max())) }
        }
    }
    var error by remember { mutableStateOf<String?>(null) }
    GuardedSheet(
        dragHandle = { SheetGrip() },
        onDismissRequest = onClose,
        sheetState = rememberSheetState(skipPartiallyExpanded = true),
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
    GuardedSheet(
        dragHandle = { SheetGrip() },
        onDismissRequest = onClose,
        sheetState = rememberSheetState(skipPartiallyExpanded = true),
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
            Quiet("AniList gives every app its own key. Make one for ${io.github.prithirajn.readync.Edition.name} once; it takes a minute.")
            VSpace(14)
            Text("1. On anilist.co: Settings \u2192 Developer \u2192 Create New Client", style = CpType.label, color = c.text)
            VSpace(4)
            Quiet("Name: ${io.github.prithirajn.readync.Edition.name}. Redirect URL: the one below. Save.")
            VSpace(8)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(io.github.prithirajn.readync.source.AniList.PIN_REDIRECT, style = CpType.small, color = c.textMid, modifier = Modifier.weight(1f))
                PillButton("Copy", {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Redirect URL", io.github.prithirajn.readync.source.AniList.PIN_REDIRECT))
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
        url = io.github.prithirajn.readync.source.AniList.authorizeUrl(clientId),
        onToken = { t -> web = false; link(t) },
        onClose = { web = false },
    )
}

/** AniList's own sign-in and allow page; the token it then shows (or redirects with) is caught. */
@Composable
private fun AniListWebDialog(url: String, onToken: (String) -> Unit, onClose: () -> Unit) {
    val c = Cp.colors
    var done by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = FullScreen) {
        Column(Modifier.fillMaxSize().background(c.page).fullScreenSafe()) {
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
                            val tok = t?.trim()?.takeIf { io.github.prithirajn.readync.source.AniList.looksLikeToken(it) } ?: return false
                            if (!done) { done = true; onToken(tok) }
                            return true
                        }
                        fun check(u: String?): Boolean = take(u?.let { io.github.prithirajn.readync.source.AniList.tokenFrom(it) })
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean =
                                check(request?.url?.toString())
                            override fun onPageStarted(view: WebView?, u: String?, favicon: android.graphics.Bitmap?) {
                                if (check(u)) view?.stopLoading()
                            }
                            override fun onPageFinished(view: WebView?, u: String?) {
                                if (check(u) || u?.startsWith(io.github.prithirajn.readync.source.AniList.PIN_REDIRECT) != true) return
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
    GuardedSheet(
        dragHandle = { SheetGrip() },
        onDismissRequest = onClose,
        sheetState = rememberSheetState(skipPartiallyExpanded = true),
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

/** Moon+ Reader through its WebDAV sync (Koofr…): connected or not, where its files are, and how to set it up. */
@Composable
private fun MoonCard(vm: AppViewModel, state: Snapshot, onAdd: () -> Unit, onGuide: () -> Unit, onRemove: () -> Unit) {
    val c = Cp.colors
    val m = state.moon
    CpCard {
        if (m == null) {
            Column(Modifier.padding(16.dp)) {
                Quiet("Read in Moon+ Reader on your phone or tablet? Turn on its sync to a free WebDAV storage like Koofr, connect the same storage here, and your progress and highlights there reach your trackers too. Books you start in Moon+ are added to your library.")
                VSpace(12)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Connect", onAdd, outlined = false)
                    PillButton("How to set it up", onGuide)
                }
            }
        } else {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ServiceTile("M", 40.dp)
                    HSpace(12)
                    Column(Modifier.weight(1f)) {
                        Text(m.username, style = CpType.bodyStrong, color = c.text)
                        Quiet(m.server.removePrefix("https://").removePrefix("http://"), maxLines = 1)
                        state.moonOkAt.takeIf { it > 0 }?.let { AnimatedQuiet("Checked ${ago(it)}", color = c.faint, maxLines = 1) }
                    }
                    Health.moon(state)?.let { h -> HealthLight(h) { vm.checkMoon() } }
                }
                VSpace(10)
                val books = state.moonDocs.values.toSet().size
                Quiet(
                    (state.moonFolder?.let { "Sync files found in $it" } ?: "No sync files found yet") +
                        (if (books > 0) " · $books book${if (books == 1) "" else "s"} followed" else "") + ".",
                    color = c.faint,
                )
                state.moonError?.let { VSpace(6); Quiet(it, color = c.faint) }
                VSpace(12)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Check now", { vm.checkMoon() })
                    PillButton("How to set it up", onGuide)
                    PillButton("Remove", onRemove, danger = true)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoonSheet(vm: AppViewModel, onGuide: () -> Unit, onClose: () -> Unit) {
    var server by remember { mutableStateOf(KOOFR_WEBDAV) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val c = Cp.colors
    GuardedSheet(
        dragHandle = { SheetGrip() },
        onDismissRequest = onClose,
        sheetState = rememberSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(Modifier.fillMaxWidth().imePadding().padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            Text("Connect Moon+ Reader", style = CpType.section, color = c.text)
            Quiet("The same WebDAV storage Moon+ Reader syncs to. Koofr's address is filled in; for another service, use its WebDAV address.")
            VSpace(16)
            CpTextField(server, { server = it }, "WebDAV address", keyboard = KeyboardType.Uri)
            VSpace(12)
            CpTextField(email, { email = it }, "Email (your Koofr login)", keyboard = KeyboardType.Email)
            VSpace(12)
            CpTextField(password, { password = it }, "App password", password = true)
            error?.let { VSpace(10); Text(it, style = CpType.small, color = c.errorText) }
            VSpace(16)
            BigButton(
                "Connect",
                onClick = {
                    busy = true; error = null
                    vm.addMoon(server, email, password) { e -> busy = false; if (e == null) onClose() else error = e }
                },
                busy = busy,
                enabled = server.isNotBlank() && email.isNotBlank() && password.isNotBlank(),
            )
            VSpace(10)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { PillButton("How to set it up", onGuide) }
            VSpace(10)
            Quiet("Use an app password made for this, not your main Koofr password; it's kept encrypted on this phone. Readync only reads Moon+ Reader's sync files and never changes anything there.", color = c.faint)
        }
    }
}

/**
 * KOReader highlights, two ways in: KOReader's own sidecar files from a folder on this device
 * (KOReader on this phone or tablet), or a sync plugin's files on Koofr / WebDAV (KOReader
 * anywhere else). Either or both; the same highlight never comes in twice.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun KoHlCard(vm: AppViewModel, state: Snapshot, onAdd: () -> Unit, onRemove: () -> Unit) {
    val c = Cp.colors
    var guide by remember { mutableStateOf(false) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { vm.setKoLocal(it) } }
    Column {
        CpCard {
            Column(Modifier.padding(16.dp)) {
                Quiet("KOReader sends only your reading position to CrossPoint Sync or a KOSync server; its highlights, notes and bookmarks stay in KOReader. Bring them in here, from wherever KOReader runs.")
                VSpace(10)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Which do I need?", { guide = true }, outlined = false)
                    if (state.koClips.isNotEmpty()) {
                        val books = state.koClips.map { it.document }.toSet().size
                        Quiet("${state.koClips.size} highlight${if (state.koClips.size == 1) "" else "s"} on $books book${if (books == 1) "" else "s"}", color = c.faint)
                    }
                }
            }
        }
        VSpace(12)
        // ---- 1. KOReader on this device: its sidecar files ----
        CpCard {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("KOReader on this device", style = CpType.label, color = c.text, modifier = Modifier.weight(1f))
                    Health.koLocal(state)?.let { h -> HealthLight(h) { vm.checkKoHighlights() } }
                }
                VSpace(4)
                if (state.koLocal == null) {
                    Quiet("For KOReader on this phone or tablet (a Boox too, with ${io.github.prithirajn.readync.Edition.name} installed on it). Pick the folder your books are in: KOReader keeps each book's highlights in a “.sdr” folder next to it. No plugin needed.")
                    VSpace(10)
                    PillButton("Pick the books folder", { runCatching { pickFolder.launch(null) } }, outlined = false)
                    VSpace(6)
                    Quiet("If KOReader keeps book settings in its own folder instead (Settings › Document › Book metadata location), pick koreader/docsettings (or hashdocsettings).", color = c.faint)
                } else {
                    val folder = remember(state.koLocal) { android.net.Uri.parse(state.koLocal).lastPathSegment?.substringAfter(':')?.ifEmpty { "Main storage" } ?: "your folder" }
                    Quiet("Reading $folder" + (state.koLocalOkAt.takeIf { it > 0 }?.let { " · checked ${ago(it)}" } ?: "") + ".", color = c.faint)
                    state.koLocalError?.let { VSpace(6); Quiet(it, color = c.faint) }
                    VSpace(6)
                    Quiet(
                        state.koBooks.keys.count { it.startsWith("local:") }.let { n -> if (n > 0) "$n EPUB${if (n == 1) "" else "s"} found here too, so highlights can go on their exact words on a CrossPoint device." else null }
                            ?: "No EPUBs in this folder. To put highlights on their exact words on a CrossPoint device, pick the folder the books themselves are in.",
                        color = c.faint,
                    )
                    VSpace(10)
                    // Wraps on narrow phones instead of cutting the last button off.
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("Read now", { vm.checkKoHighlights() })
                        PillButton("Change folder", { runCatching { pickFolder.launch(null) } })
                        PillButton("Remove", { vm.removeKoLocal() }, danger = true)
                    }
                }
            }
        }
        VSpace(12)
        // ---- 2. KOReader elsewhere: a sync plugin's files on Koofr / WebDAV ----
        CpCard {
            Column(Modifier.padding(16.dp)) {
                val k = state.koHl
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("From Koofr / WebDAV", style = CpType.label, color = c.text, modifier = Modifier.weight(1f))
                    Health.koHl(state)?.let { h -> HealthLight(h) { vm.checkKoHighlights() } }
                }
                VSpace(4)
                if (k == null) {
                    Quiet("For KOReader on another device (Kindle, Kobo, another phone) with the HighlightSync or AnnotationSync plugin uploading to Koofr or any WebDAV.")
                    VSpace(10)
                    PillButton("Connect", onAdd, outlined = false)
                } else {
                    Text(k.username, style = CpType.body, color = c.text)
                    Quiet(k.server.removePrefix("https://").removePrefix("http://") + (state.koHlOkAt.takeIf { it > 0 }?.let { " · checked ${ago(it)}" } ?: ""), maxLines = 1)
                    VSpace(6)
                    Quiet(if (state.koHlFolders.isEmpty()) "No highlight files found yet." else "Files in " + state.koHlFolders.joinToString(", ") + ".", color = c.faint)
                    state.koHlError?.let { VSpace(6); Quiet(it, color = c.faint) }
                    VSpace(10)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("Check now", { vm.checkKoHighlights() })
                        PillButton("Remove", onRemove, danger = true)
                    }
                    // Your EPUBs on the same storage: for KOReader on another device, where this phone has no copy of the books.
                    VSpace(14)
                    RowDivider()
                    VSpace(12)
                    Text("Your books here (optional)", style = CpType.label, color = c.text)
                    VSpace(4)
                    val davBooks = state.koBooks.keys.count { it.startsWith("dav:") }
                    Quiet(
                        state.koDavBooks?.let { f -> "Reading EPUBs in $f" + (if (davBooks > 0) " · $davBooks found." else " · none read yet.") }
                            ?: "If your EPUBs are on this storage too (a Calibre library, say), pick that folder: books named only by KOReader's ID get their titles, and highlights can go on their exact words on a CrossPoint device.",
                        color = c.faint,
                    )
                    VSpace(8)
                    var editing by remember { mutableStateOf(false) }
                    var path by remember(state.koDavBooks) { mutableStateOf(state.koDavBooks ?: "") }
                    var finding by remember { mutableStateOf(false) }
                    if (editing) {
                        CpTextField(path, { path = it }, "Folder", placeholder = "/Calibre Library")
                        VSpace(8)
                    }
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (editing) {
                            PillButton("Save", { editing = false; vm.setKoDavBooks(path) }, outlined = false)
                            PillButton("Cancel", { editing = false; path = state.koDavBooks ?: "" })
                        } else {
                            PillButton(if (finding) "Looking…" else "Find it", { if (!finding) { finding = true; vm.findKoDavBooks { finding = false } } }, outlined = state.koDavBooks != null)
                            PillButton("Type it", { editing = true })
                            if (state.koDavBooks != null) PillButton("Stop", { vm.setKoDavBooks(null) }, danger = true)
                        }
                    }
                }
            }
        }
        if (state.koHlUnmatched.isNotEmpty()) {
            VSpace(10)
            // Named where the title is known; the rest are only KOReader document IDs, so they're counted, not listed.
            val named = state.koHlUnmatched.mapNotNull { io.github.prithirajn.readync.sync.KoHighlightSync.nameOf(state, it) }.distinct()
            val unnamed = state.koHlUnmatched.size - named.size
            val parts = ArrayList<String>()
            if (named.isNotEmpty()) parts += "Highlights waiting for their book: " + named.take(5).joinToString(", ") +
                (if (named.size > 5) " and ${named.size - 5} more" else "") + ". They come in once the book is in your library (or its KOSync document is linked to it)."
            if (unnamed > 0) parts += "$unnamed book${if (unnamed == 1) "" else "s"} with KOReader highlights can't be named yet: the plugin's files only carry KOReader's document ID. " +
                "Show Readync the same EPUB files, either in a folder on this phone (KOReader on this device) or on your storage (Your books here, under From Koofr / WebDAV), and they come in with their titles. Or turn on AnnotationSync's \u201CUse filename instead of hash\u201D."
            Quiet(parts.joinToString("\n\n"), Modifier.padding(horizontal = 4.dp), color = c.faint)
            if (unnamed > 0) {
                // Or from a list you have: IDs with names, or IDs with Calibre numbers plus Calibre's catalog.
                var namesMsg by remember { mutableStateOf<String?>(null) }
                val pickNames = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                    if (uris.isNotEmpty()) vm.importBookNames(uris) { namesMsg = it }
                }
                VSpace(8)
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PillButton("Name them from a file", { runCatching { pickNames.launch(arrayOf("*/*")) } })
                }
                VSpace(4)
                Quiet(namesMsg ?: "A list of KOReader document IDs with names, or with Calibre book numbers plus Calibre's CSV catalog. Pick several files at once if they go together.", Modifier.padding(horizontal = 4.dp), color = c.faint)
                // One by one: each with a few of its highlights, a guess from their words, or typed.
                val waiting = state.koHlUnmatched.filter { it in state.koUnnamed }
                if (waiting.isNotEmpty()) {
                    VSpace(12)
                    Text("Name them one by one", style = CpType.label, color = c.text, modifier = Modifier.padding(horizontal = 4.dp))
                    Quiet("Each with a few of its highlights. Guess looks their words up in Google Books (it searches inside books); pick the right one, or type the name.", Modifier.padding(horizontal = 4.dp), color = c.faint)
                    VSpace(8)
                    for (id in waiting.take(12)) UnnamedKoBook(vm, id, state.koUnnamed[id].orEmpty())
                    if (waiting.size > 12) Quiet("${waiting.size - 12} more after these.", Modifier.padding(horizontal = 4.dp), color = c.faint)
                }
            }
        }
        // ---- 3. KOReader on another device, over its own SSH server (no plugin) ----
        VSpace(12)
        KoSshCard(vm, state)
        // ---- KOReader's highlights on to a CrossPoint reader ----
        if (state.account != null && (state.koHl != null || state.koLocal != null || state.koSsh != null)) {
            VSpace(12)
            CpCard {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Also add them to CrossPoint", style = CpType.label, color = c.text)
                        Text(
                            "If you also read on a CrossPoint device (Xteink), KOReader's highlights and bookmarks go on to crosspoint-sync so the device has them too. " +
                                "When Readync has the book's EPUB (the same file as on the device), highlights go on their exact words and the device draws them on the page; otherwise they're listed under their chapter. Bookmarks need the EPUB, to know their page." +
                                (if (state.koSentCp.isNotEmpty()) " ${state.koSentCp.size} added so far" + (if (state.koPlaced.isNotEmpty()) ", ${state.koPlaced.size} on their exact words." else ".") else ""),
                            style = CpType.small, color = c.quiet,
                        )
                    }
                    CpToggle(state.koToCrossPoint, { vm.setKoToCrossPoint(it) }, "Also add KOReader highlights and bookmarks to CrossPoint")
                }
            }
        }
        // ---- CrossPoint's highlights on to KOReader (opt-in) ----
        if (state.account != null && state.koHl != null) {
            VSpace(12)
            CpCard {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Also give CrossPoint highlights and bookmarks to KOReader", style = CpType.label, color = c.text)
                            Text(
                                "Highlights you make on a CrossPoint device are added to KOReader's sync file for the book on your storage (AnnotationSync's or HighlightSync's), and KOReader takes them in when it syncs that book (with AnnotationSync: open the book and sync it). " +
                                    "Only for books whose EPUB is in KOReader's books folder (on this phone, or a books folder on that storage; the same file as on the device), and only when the exact place is sure. " +
                                    "Your CrossPoint bookmarks go too (into AnnotationSync's file), on the same page; one you delete on the device is deleted in KOReader as well. " +
                                    "A book with no AnnotationSync file on your storage yet: they wait, and go once you've synced that book in KOReader once (which makes the file). " +
                                    "Each goes once, so one you delete in KOReader stays deleted, and a copy of the file as it was is kept next to it.",
                                style = CpType.small, color = c.quiet,
                            )
                        }
                        CpToggle(state.cpToKo, { vm.setCpToKo(it) }, "Also give CrossPoint highlights and bookmarks to KOReader")
                    }
                    if (state.cpToKo) {
                        val status = state.cpToKoNote
                            ?: if (state.koLocal == null && state.koDavBooks == null) "Pick KOReader's books folder (on this phone, or on your storage) first."
                            else if (state.cpSentKo.isNotEmpty()) "${state.cpSentKo.size} CrossPoint highlight${if (state.cpSentKo.size == 1) "" else "s"} looked at so far" + (state.cpToKoOkAt.takeIf { it > 0 }?.let { " · checked ${ago(it)}." } ?: ".")
                            else null
                        status?.let { VSpace(8); Quiet(it, color = c.faint) }
                    }
                }
            }
        }
    }
    if (guide) KoHlGuideSheet { guide = false }
}

/**
 * KOReader on a Kindle, Kobo or PocketBook, reached over your Wi-Fi through KOReader's own SSH
 * server: no plugin. Its highlights, notes and position come in, and CrossPoint highlights and
 * bookmarks go to it (KOReader takes them in when you next open the book).
 */
@Composable
private fun KoSshCard(vm: AppViewModel, state: Snapshot) {
    val c = Cp.colors
    val k = state.koSsh
    var setting by remember { mutableStateOf(false) }
    var copying by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf(false) }
    CpCard {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Over Wi-Fi, no plugin", style = CpType.label, color = c.text, modifier = Modifier.weight(1f))
                Health.koSsh(state)?.let { h -> HealthLight(h) { vm.checkKoSsh() } }
            }
            VSpace(4)
            if (k == null) {
                Quiet("For KOReader on a Kindle, Kobo or PocketBook at home: Readync reaches it through KOReader's own SSH server, so nothing needs installing. Its highlights, notes and where you are come in; your CrossPoint highlights and bookmarks go to it, and KOReader takes them in when you next open the book.")
                VSpace(10)
                PillButton("Set up", { setting = true }, outlined = false)
            } else {
                Text(k.host + ":" + k.port, style = CpType.body, color = c.text)
                Quiet(
                    listOfNotNull(
                        state.koSshInfo,
                        state.koSshOkAt.takeIf { it > 0 }?.let { "reached ${ago(it)}" } ?: "not reached yet",
                        if (k.noPassword) "signing in without a password" else "signing in with Readync's key",
                    ).joinToString(" \u00B7 "),
                    color = c.faint,
                )
                state.koSshError?.let { VSpace(6); Quiet(it, color = c.errorText) }
                VSpace(10)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sync with KOReader over Wi-Fi", style = CpType.body, color = c.text)
                        Quiet("Each sync, whenever the device is awake on this Wi-Fi", color = c.faint)
                    }
                    CpToggle(k.enabled, { vm.setKoSshEnabled(it) }, "Sync with KOReader over Wi-Fi")
                }
                VSpace(10)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Sync now", { vm.checkKoSsh() })
                    PillButton(if (copying) "Copying\u2026" else "Copy books here", {
                        if (!copying) { copying = true; msg = null; vm.copyBooksFromKoSsh(null) { m -> copying = false; msg = m } }
                    })
                }
                VSpace(8)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Change", { setting = true })
                    PillButton("Remove", { removing = true }, danger = true)
                }
                msg?.let { VSpace(8); Quiet(it, color = c.faint) }
                VSpace(10)
                Quiet("A book open in KOReader takes new highlights and bookmarks in once you close it and open it again. \u201CCopy books here\u201D keeps your books' files in Readync, for bookmarks' pages and exact places away from home.", color = c.faint)
            }
        }
    }
    if (setting) KoSshSheet(vm, state) { setting = false }
    if (removing) {
        ConfirmSheet(
            title = "Disconnect KOReader over Wi-Fi?",
            body = "Readync stops reaching KOReader on ${k?.host ?: "the device"}. Highlights already brought in stay, and so do book files copied here.",
            action = "Disconnect",
            onConfirm = { vm.removeKoSsh() },
            onClose = { removing = false },
        )
    }
}

/** Setting up KOReader over Wi-Fi: KOReader's SSH server on, its address, and how Readync signs in. */
@Composable
private fun KoSshSheet(vm: AppViewModel, state: Snapshot, onClose: () -> Unit) {
    val c = Cp.colors
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val k = state.koSsh
    var host by remember { mutableStateOf(k?.host ?: "") }
    var port by remember { mutableStateOf((k?.port ?: 2222).toString()) }
    var noPassword by remember { mutableStateOf(k?.noPassword ?: false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val pub = remember { vm.koSshKey() }
    val portNo = port.trim().toIntOrNull()?.takeIf { it in 1..65535 }
    val ready = host.trim().matches(Regex("[A-Za-z0-9.:\\-]{3,}")) && portNo != null && !busy
    FlowSheet("KOReader over Wi-Fi", onClose) {
        VSpace(8)
        Quiet("On the Kindle (or Kobo, PocketBook), in KOReader:")
        VSpace(8)
        CpCard {
            Column(Modifier.padding(16.dp)) {
                Text("1  Tools (\u2699) \u203A Network \u203A SSH server: switch it on. It shows the device's address, like 192.168.1.23", style = CpType.body, color = c.text)
                VSpace(6)
                Text("2  In the same menu, tick \u201CStart SSH server with KOReader\u201D, so it's on whenever KOReader is", style = CpType.body, color = c.text)
                VSpace(6)
                Text("3  Type that address below. The port is 2222 unless you changed it", style = CpType.body, color = c.text)
            }
        }
        VSpace(14)
        CpTextField(host, { host = it; result = null }, "Device address", placeholder = "192.168.1.23", keyboard = androidx.compose.ui.text.input.KeyboardType.Uri)
        VSpace(8)
        CpTextField(port, { port = it.filter { ch -> ch.isDigit() }.take(5); result = null }, "Port", placeholder = "2222", keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
        VSpace(16)
        Text("How Readync signs in", style = CpType.label, color = c.text)
        VSpace(8)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Readync's own key (safer)", style = CpType.body, color = c.text)
                Quiet("Only Readync gets in. Readync can put its key on the device for you: turn on \u201CLogin without password\u201D in KOReader's SSH menu for a moment, tap Put the key there, then turn it off again.", color = c.faint)
            }
            CpToggle(!noPassword, { noPassword = !it; result = null }, "Use Readync's key")
        }
        if (!noPassword) {
            VSpace(10)
            BigButton(if (busy) "Putting the key there\u2026" else "Put the key there and connect", {
                busy = true; result = null
                vm.installKoSshKey(host, portNo ?: 2222) { ok, m -> busy = false; result = ok to m }
            }, busy = busy, enabled = ready)
            VSpace(8)
            Quiet("Or add it yourself: put this line in a file named authorized_keys in koreader/settings/SSH on the device, then tap Connect.", color = c.faint)
            VSpace(6)
            Text(pub, style = CpType.small.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), color = c.textMid, maxLines = 3)
            VSpace(6)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Copy", { clipboard.setText(androidx.compose.ui.text.AnnotatedString(pub)) })
                PillButton("Share", {
                    runCatching {
                        context.startActivity(android.content.Intent.createChooser(
                            android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, pub), "Readync's key"))
                    }
                })
            }
        } else {
            VSpace(8)
            Quiet("Turn on \u201CLogin without password\u201D in KOReader's SSH menu. Anyone on your Wi-Fi could then get into the device, so a key is better.", color = c.faint)
        }
        VSpace(14)
        BigButton(if (busy && noPassword) "Connecting\u2026" else "Connect", {
            busy = true; result = null
            vm.connectKoSsh(host, portNo ?: 2222, noPassword) { ok, m -> busy = false; result = ok to m }
        }, filled = noPassword, busy = busy && noPassword, enabled = ready)
        result?.let { (ok, m) ->
            VSpace(10)
            Quiet(m, color = if (ok) c.accentText else c.errorText)
            if (ok) { VSpace(10); BigButton("Done", onClose, filled = false) }
        }
    }
}

/**
 * KOReader highlights' way in, on the cards of the servers KOReader syncs its position with (it
 * has no row of its own): what it's for until it's set up, then how it's doing.
 */
@Composable
private fun KoHlHint(state: Snapshot, text: String, onOpen: () -> Unit) {
    val c = Cp.colors
    val on = state.koHl != null || state.koLocal != null || state.koSsh != null
    Column(Modifier.padding(16.dp)) {
        if (on) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("KOReader highlights & bookmarks", style = CpType.label, color = c.text, modifier = Modifier.weight(1f))
                listOfNotNull(Health.koLocal(state), Health.koHl(state), Health.koSsh(state)).let { hs -> hs.firstOrNull { it.level == Health.Level.BAD } ?: hs.firstOrNull { it.level == Health.Level.WARN } ?: hs.firstOrNull() }
                    ?.let { h -> HealthLight(h) { onOpen() } }
            }
            Quiet(
                "${state.koClips.size} highlight${if (state.koClips.size == 1) "" else "s"} \u00B7 from " +
                    listOfNotNull(state.koLocal?.let { "this device" }, state.koHl?.let { it.server.substringAfter("://").substringBefore('/') }, state.koSsh?.let { "KOReader over Wi-Fi" }).joinToString(" and "),
                maxLines = 1,
            )
            VSpace(8)
            PillButton("KOReader highlights & bookmarks", onOpen)
        } else {
            Quiet(text)
            VSpace(8)
            PillButton("Set up KOReader highlights & bookmarks", onOpen)
        }
    }
}

/** Where your highlights come from, by what you read on: which switches you need, and which you don't. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KoHlGuideSheet(onClose: () -> Unit) {
    val c = Cp.colors
    GuardedSheet(
        dragHandle = { SheetGrip() },
        onDismissRequest = onClose,
        sheetState = rememberSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            Text("Which do I need?", style = CpType.section, color = c.text)
            Quiet("It depends on what you read on. Highlights go a different way from your reading position.")
            VSpace(16)
            GuidePart("1", "A CrossPoint device (Xteink X3, X4…)", listOf(
                "Its highlights come through your CrossPoint account already. Nothing to turn on here.",
                "On the device: Settings › CrossPoint Sync › turn on Sync Clippings.",
            ))
            VSpace(10)
            GuidePart("2", "KOReader on this phone or tablet", listOf(
                "Using CrossPoint Sync or a KOSync server in KOReader only syncs your position, not highlights.",
                "Turn on KOReader on this device and pick your books folder. That's all.",
            ))
            VSpace(10)
            GuidePart("3", "KOReader on another device (Kindle, Kobo, a Boox without ${io.github.prithirajn.readync.Edition.name})", listOf(
                "Install the HighlightSync or AnnotationSync plugin in KOReader and point it at Koofr (or any WebDAV).",
                "Then connect the same storage under From Koofr / WebDAV.",
                "Or, with no plugin: switch on KOReader's own SSH server and set up Over Wi-Fi, no plugin. Highlights, notes and bookmarks go both ways while the device is on your Wi-Fi.",
            ))
            VSpace(10)
            GuidePart("4", "A CrossPoint device and KOReader together", listOf(
                "Set up 1, and 2 or 3: your CrossPoint highlights come through your account, KOReader's through the folder or Koofr. A passage you highlighted in both comes in once.",
                "Want KOReader's highlights and bookmarks on the CrossPoint device too? Turn on Also add them to CrossPoint.",
                "With the book's EPUB in Readync (the same file as on the device), they go on their exact words, so the device draws them on the page. With another edition, only where your own highlights or bookmarks on the device, before and after them in the chapter, confirm the place; the rest go under their chapter.",
                "The other way (CrossPoint highlights and bookmarks into KOReader): Over Wi-Fi, no plugin does it by itself; with a plugin on Koofr it's an opt-in (Also give CrossPoint highlights and bookmarks to KOReader). Only places that are sure are added.",
                "Read the same file on both so the book is the same here (or link its KOSync document from the book's ⋯ menu).",
            ))
            VSpace(10)
            GuidePart("5", "KOReader on this device and elsewhere", listOf(
                "Use both; a highlight found in both places comes in once.",
            ))
            VSpace(10)
            GuidePart("6", "Bookmarks between a CrossPoint device (Xteink) and KOReader", listOf(
                "From the device: it sends a book's bookmarks when it syncs that book (Sync in the reader menu, or Sync On Sleep with Wi-Fi). Each carries its place in KOReader's own form and the opening words of its page.",
                "Readync reads them on every sync for the 25 books you read most lately (or one book, with Check again on its Bookmarks tab). The first time for a book it reads them all; they show in the book's Bookmarks tab, and the sync log says how many came.",
                "With “every new bookmark highlights its page” on, only ones made after you switched it on become highlights; older ones stay bookmarks. Ones that came from KOReader, ones you marked done, and books without their file here are left alone.",
                "To KOReader with AnnotationSync on Koofr (Also give CrossPoint highlights and bookmarks to KOReader): they're added to the book's AnnotationSync file there (a copy of the file as it was is kept next to it), and KOReader takes them in when AnnotationSync syncs the open book.",
                "No AnnotationSync file for the book on Koofr yet? Nothing is lost: they wait in Readync, and go the first time the file is there. Open the book in KOReader and sync it with AnnotationSync once to make it. (Readync doesn't make the file itself: it's named after KOReader's ID for its own copy of the book, and a wrong name would never be read.)",
                "Over Wi-Fi, no plugin needs no such file: Readync leaves them beside the book on the Kindle (copying the book's file from there first when it needs it), and KOReader takes them in when the book is next opened. A book open in KOReader takes them in once you close it and open it again.",
                "From KOReader to the device (Also add them to CrossPoint): KOReader's bookmarks go to CrossPoint with KOReader's own place, and the device finds the page in its own file the next time it syncs that book.",
                "Each bookmark goes once, and one isn't added where the other side has a bookmark on the same page already. One you delete on the device is deleted in KOReader too (through AnnotationSync).",
                "Both directions need the book's file in Readync (for the page, its opening words and spotting duplicates). Places point at the right page when the Kindle and the device have the same edition.",
                "The device's bookmarks also help put highlights on their exact words: where its page starts is known, so a highlight that went under its chapter is tried again when new bookmarks arrive around it.",
                "Readync only reads bookmarks from the device; it never changes or removes anything there unless you remove a bookmark in Readync.",
            ))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KoHlSheet(vm: AppViewModel, state: Snapshot, onClose: () -> Unit) {
    var server by remember { mutableStateOf(KOOFR_WEBDAV) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val c = Cp.colors
    GuardedSheet(
        dragHandle = { SheetGrip() },
        onDismissRequest = onClose,
        sheetState = rememberSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            Text("Connect KOReader highlights & bookmarks", style = CpType.section, color = c.text)
            Quiet("The same storage the HighlightSync or AnnotationSync plugin syncs to in KOReader. Koofr's address is filled in.")
            state.moon?.let { m ->
                VSpace(10)
                PillButton("Use my Moon+ Reader Koofr account", { server = m.server; email = m.username; password = m.key })
            }
            VSpace(14)
            CpTextField(server, { server = it }, "WebDAV address", keyboard = KeyboardType.Uri)
            VSpace(12)
            CpTextField(email, { email = it }, "Email (your Koofr login)", keyboard = KeyboardType.Email)
            VSpace(12)
            CpTextField(password, { password = it }, "App password", password = true)
            VSpace(12)
            CpTextField(folder, { folder = it }, "Folder (optional)", placeholder = "The plugin's sync folder, e.g. /KOReader/highlights")
            error?.let { VSpace(10); Text(it, style = CpType.small, color = c.errorText) }
            VSpace(16)
            BigButton(
                "Connect",
                onClick = {
                    busy = true; error = null
                    vm.addKoHighlights(server, email, password, folder) { e -> busy = false; if (e == null) onClose() else error = e }
                },
                busy = busy,
                enabled = server.isNotBlank() && email.isNotBlank() && password.isNotBlank(),
            )
            VSpace(16)
            Text("Setting up the plugin in KOReader", style = CpType.label, color = c.text)
            VSpace(6)
            for (line in listOf(
                "HighlightSync: Tools \u203A Highlight Sync \u203A Sync Cloud: add WebDAV with the Koofr address, your email and an app password, and pick a folder.",
                "AnnotationSync: Tools \u203A Cloud storage+ (KOReader 2026.07 or later) to add Koofr, then Tools \u203A Annotation Sync \u203A Settings \u203A Cloud settings to choose it.",
                "Sync a book once in KOReader so its file appears, then connect here. Leave the folder empty and Readync looks for it; type it if it isn't found.",
                "Both of the plugin's naming styles work. AnnotationSync's default (KOReader's document ID) matches books exactly; file names are matched by title and author.",
                "Readync only reads the plugin's files, unless you turn on Also give CrossPoint highlights and bookmarks to KOReader: then it adds to the book's file there (a copy of it as it was is kept next to it). It never makes new files.",
            )) Text("\u2022 $line", style = CpType.body, color = c.textMid, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

/** Koofr's WebDAV address for your own files. */
private const val KOOFR_WEBDAV = "https://app.koofr.net/dav/Koofr"

/** Step by step: a free Koofr account, Moon+ Reader syncing to it, and Readync reading it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoonGuideSheet(onClose: () -> Unit) {
    val c = Cp.colors
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val links = androidx.compose.ui.platform.LocalUriHandler.current
    var copied by remember { mutableStateOf(false) }
    GuardedSheet(
        dragHandle = { SheetGrip() },
        onDismissRequest = onClose,
        sheetState = rememberSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            Text("Moon+ Reader with Koofr", style = CpType.section, color = c.text)
            Quiet("Moon+ Reader saves where you are in each book to your Koofr storage; Readync reads it from there. Koofr's free plan is plenty: each book's sync file is a few bytes.")
            VSpace(16)
            GuidePart("1", "Make a Koofr account", listOf(
                "Sign up for free at koofr.eu (or in the Koofr app).",
                "Use any email; remember it, it's your WebDAV username.",
            ))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 34.dp)) {
                PillButton("Open koofr.eu", { runCatching { links.openUri("https://koofr.eu") } })
            }
            VSpace(14)
            GuidePart("2", "Make an app password", listOf(
                "In Koofr on the web: your profile picture › Preferences › Password.",
                "Under App passwords, type a name (“Moon+ and Readync”) and tap Generate.",
                "Copy the password it shows. It's shown once, so keep it until you've finished both apps. (You can make a separate one for each app if you like.)",
            ))
            VSpace(14)
            GuidePart("3", "Turn on sync in Moon+ Reader", listOf(
                "Open Moon+ Reader › Options (the menu) › Sync (in some versions: Misc › Sync).",
                "Turn on “Sync reading positions via” WebDAV, and fill in:",
                "WebDAV address: $KOOFR_WEBDAV",
                "Username: your Koofr email · Password: the app password.",
                "Tap Test, or save, then open any book and turn a page so it writes its first sync file.",
                "Older Moon+ Reader versions have cloud sync in Moon+ Reader Pro only; if you don't see WebDAV, that's why.",
            ))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 34.dp)) {
                PillButton(if (copied) "Copied" else "Copy the address", {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(KOOFR_WEBDAV)); copied = true
                })
            }
            VSpace(14)
            GuidePart("4", "Connect it in Readync", listOf(
                "Here, under Reading from › Moon+ Reader › Connect.",
                "The Koofr address is already filled in; add the same email and app password.",
                "Readync finds Moon+ Reader's folder on its own (in Koofr it's Apps › Books › .Moon+ › Cache, one \u201C.po\u201D file per book) and starts following your books on the next sync.",
            ))
            VSpace(14)
            GuidePart("5", "How it works from then on", listOf(
                "Each sync, Readync reads where you are in every book Moon+ Reader has synced, and your highlights and notes in them.",
                "Books already in your library are matched by title (Moon+ keeps the real one with your highlights) and author, and move ahead when you've read further in Moon+.",
                "Books you start in Moon+ that aren't in your library yet are added, and follow your “Auto-sync new books” setting.",
                "Readync never writes to Koofr or changes anything in Moon+ Reader.",
            ))
            VSpace(14)
            Quiet("Tip: book files named “Title - Author.epub” (how Calibre names them) match best. Using Nextcloud or another WebDAV service? The steps are the same with its WebDAV address.", color = c.faint)
        }
    }
}

@Composable
private fun GuidePart(n: String, title: String, lines: List<String>) {
    val c = Cp.colors
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp).clip(CpShapes.pill).background(c.accentTint), contentAlignment = Alignment.Center) {
            Text(n, style = CpType.label, color = c.accentText)
        }
        HSpace(10)
        Column(Modifier.weight(1f)) {
            Text(title, style = CpType.bodyStrong, color = c.text)
            VSpace(4)
            for (l in lines) Text("• $l", style = CpType.body, color = c.textMid, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
    VSpace(8)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KosyncSheet(vm: AppViewModel, onClose: () -> Unit) {
    var server by remember { mutableStateOf(io.github.prithirajn.readync.source.KosyncClient.SUGGESTED_SERVER) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val c = Cp.colors
    GuardedSheet(
        dragHandle = { SheetGrip() },
        onDismissRequest = onClose,
        sheetState = rememberSheetState(skipPartiallyExpanded = true),
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
    GuardedSheet(
        dragHandle = { SheetGrip() },
        onDismissRequest = onClose,
        sheetState = rememberSheetState(skipPartiallyExpanded = true),
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
    GuardedSheet(
        dragHandle = { SheetGrip() },
        onDismissRequest = onClose,
        sheetState = rememberSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(Modifier.fillMaxWidth().imePadding().padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            Text("Link ${t.name}", style = CpType.section, color = c.text)
            Quiet(t.tokenHelp)
            VSpace(12)
            t.tokenUrl?.let { url ->
                BigButton("Open ${url.removePrefix("https://").removePrefix("www.")}", {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }, filled = false)
            }
            VSpace(12)
            CpTextField(token, { token = it }, t.tokenLabel, t.tokenPlaceholder, mono = true)
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
            Quiet(t.tokenNote)
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
    GuardedSheet(
        dragHandle = { SheetGrip() },
        onDismissRequest = { vm.cancelDeviceLink(); onClose() },
        sheetState = rememberSheetState(skipPartiallyExpanded = true),
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
private fun WebSignIn(vm: AppViewModel, t: Tracker, onPassword: (() -> Unit)? = null, onClose: () -> Unit) {
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
                    onPassword?.let { go ->
                        Text("Or use your email and password", style = CpType.small, color = c.accentText,
                            modifier = Modifier.bouncyClickable(CpShapes.control) { go() }.padding(vertical = 2.dp))
                    }
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
    GuardedSheet(dragHandle = { SheetGrip() }, onDismissRequest = onClose, sheetState = rememberSheetState(), shape = CpShapes.sheet, containerColor = c.page) {
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


/** A KOReader book known only by its ID: how many highlights, two of them, and ways to name it. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun UnnamedKoBook(vm: AppViewModel, id: String, value: String) {
    val c = Cp.colors
    val parts = remember(value) { value.split('\u001F') }
    val count = parts.firstOrNull()?.toIntOrNull() ?: 0
    val texts = parts.drop(1)
    var guessing by remember(id) { mutableStateOf(false) }
    var guesses by remember(id) { mutableStateOf<List<io.github.prithirajn.readync.sync.BookGuess.Candidate>?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    var typing by remember(id) { mutableStateOf(false) }
    var title by remember(id) { mutableStateOf("") }
    var author by remember(id) { mutableStateOf("") }
    CpCard(Modifier.padding(bottom = 8.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("$count highlight${if (count == 1) "" else "s"} · ${id.take(8)}…", style = CpType.small, color = c.faint)
            for (t in texts.take(2)) {
                VSpace(6)
                Text("\u201C" + (if (t.length > 160) t.take(157).trimEnd() + "…" else t) + "\u201D", style = CpType.body, color = c.textMid, maxLines = 4)
            }
            VSpace(10)
            val g = guesses
            if (g != null) {
                if (g.isEmpty()) Quiet(if (typing) "Nothing found by that name. Save it as you typed it." else "Google Books didn't find these words (it rarely has web novels). Type the name, then Look it up.", color = c.faint)
                else {
                    Text("Is it one of these?", style = CpType.label, color = c.text)
                    for (cand in g) {
                        VSpace(6)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(cand.title, style = CpType.bodyStrong, color = c.text, maxLines = 2)
                                Text(
                                    listOfNotNull(cand.author, cand.year, cand.source).joinToString(" · ") +
                                        (if (cand.asked > 1) " · found ${cand.hits} of ${cand.asked} passages" else ""),
                                    style = CpType.small, color = c.faint, maxLines = 1,
                                )
                            }
                            PillButton("This one", { vm.nameKoBook(id, cand.title, cand.author) }, outlined = false)
                        }
                    }
                }
                VSpace(10)
            }
            if (failed) { Quiet("Couldn't look it up. Check the connection and try again.", color = c.faint); VSpace(8) }
            if (typing) {
                CpTextField(title, { title = it }, "Title")
                VSpace(6)
                CpTextField(author, { author = it }, "Author (optional)")
                VSpace(8)
            }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (typing) {
                    PillButton("Save", { vm.nameKoBook(id, title, author.ifBlank { null }) }, outlined = false)
                    // The name as the catalogues have it (Hardcover knows most self-published series), to pick instead of typing it all.
                    PillButton(if (guessing) "Looking…" else "Look it up", {
                        if (!guessing && title.isNotBlank()) { guessing = true; failed = false; vm.lookUpBookName(title + (if (author.isNotBlank()) " $author" else "")) { r -> guessing = false; if (r == null) failed = true else guesses = r } }
                    })
                    PillButton("Cancel", { typing = false })
                } else {
                    if (texts.isNotEmpty()) PillButton(if (guessing) "Looking…" else if (guesses == null) "Guess" else "Guess again", {
                        if (!guessing) { guessing = true; failed = false; vm.guessKoBook(id) { r -> guessing = false; if (r == null) failed = true else guesses = r } }
                    }, outlined = guesses != null)
                    PillButton("Type the name", { typing = true })
                }
            }
        }
    }
}


/**
 * Threads: your account (a token from your own Meta app), and new highlights posted there by
 * themselves, each as a picture in a look picked at random.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ThreadsCard(vm: AppViewModel, state: Snapshot) {
    val c = Cp.colors
    var token by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var showHow by remember { mutableStateOf(state.threadsToken == null) }
    CpCard {
        Column(Modifier.padding(16.dp)) {
            if (state.threadsToken == null) {
                Text("Connect your Threads", style = CpType.bodyStrong, color = c.text)
                Quiet("Threads lets an app post for you through Meta's Threads API, with a token from a Meta app of your own (free, a few minutes to set up). Only your posts go through it; nothing else is read.")
            } else {
                Text("@${state.threadsUser ?: "you"}", style = CpType.bodyStrong, color = c.text)
                Quiet("Connected" + (state.threadsTokenAt.takeIf { it > 0 }?.let { " · token renewed ${ago(it * 1000)}, renewed again by itself each month" } ?: ""), color = c.faint)
            }
            if (showHow) {
                VSpace(10)
                Text("Getting a token", style = CpType.label, color = c.text)
                Quiet(
                    "1. On developers.facebook.com, create an app and pick the \u201CAccess the Threads API\u201D use case.\n" +
                        "2. In the app, under App roles \u203A Roles, add your Threads account as a Threads Tester. Accept the invitation in Threads: Settings \u203A Account \u203A Website permissions \u203A Invites.\n" +
                        "3. In the use case, add the threads_basic and threads_content_publish permissions. Then on its Settings tab, scroll to the bottom and tap Generate access token (it starts with TH).\n" +
                        "4. Paste the token here. If it lasts only an hour, paste the app's Threads secret too and it's made into one that lasts 60 days.",
                    color = c.faint,
                )
            }
            if (state.threadsToken == null || showHow) {
                VSpace(10)
                CpTextField(token, { token = it; msg = null }, "Access token", password = true)
                VSpace(6)
                CpTextField(secret, { secret = it; msg = null }, "Threads app secret (optional)", password = true)
                VSpace(8)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(if (busy) "Checking…" else "Connect", {
                        if (!busy && token.isNotBlank()) { busy = true; vm.connectThreads(token, secret.ifBlank { null }) { e -> busy = false; msg = e; if (e == null) { token = ""; secret = ""; showHow = false } } }
                    }, outlined = false)
                    if (state.threadsToken != null) PillButton("Cancel", { showHow = false })
                }
            }
            if (state.threadsToken != null && !showHow) {
                val waiting = remember(state) { ThreadsAuto.waiting(state).size }
                val today = state.threadsPostLog.count { it > System.currentTimeMillis() / 1000 - 86_400 }
                VSpace(14)
                // How it's going, at a glance.
                Screen(Modifier.fillMaxWidth()) {
                    Row {
                        for ((label, n, hot) in listOf(
                            Triple("Posted", state.threadsPosted.size, false),
                            Triple("Today", today, false),
                            Triple("Waiting", waiting, waiting > 0),
                        )) Column(Modifier.weight(1f)) {
                            DeviceLabel(label, color = c.screenDim)
                            RollingNumber(n, CpType.screenSmall.copy(fontSize = 26.sp, lineHeight = 28.sp), if (hot) c.accent else c.screenText)
                        }
                    }
                }
                // Why what's waiting hasn't gone up yet, and when it will.
                ThreadsAuto.nextNote(state)?.let { VSpace(8); Text(it, style = CpType.small, color = c.quiet) }
                state.threadsError?.let { VSpace(8); Text(it, style = CpType.small, color = c.errorText) }

                ThreadsSection("Posting")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Posts a day", style = CpType.body, color = c.text)
                        Text("Never in your quiet hours", style = CpType.small, color = c.quiet)
                    }
                    Stepper(state.threadsPerDay, onMinus = { vm.setThreadsOptions(perDay = state.threadsPerDay - 1) }, onPlus = { vm.setThreadsOptions(perDay = state.threadsPerDay + 1) })
                }
                ThreadsSwitch("One post per reading session", "Highlights from one sitting go up together, a card each", state.threadsPerSession) { vm.setThreadsOptions(perSession = it) }
                Text("Turn posting on for a book from its page (Post to Threads). Only highlights made after that go up.", style = CpType.small, color = c.faint, modifier = Modifier.padding(top = 4.dp))

                ThreadsSection("On the card")
                Segmented(listOf(false, true), state.threadsAnyLook, { if (it) "Any look" else "Book card" }, { vm.setThreadsOptions(anyLook = it) })
                Text(
                    if (state.threadsAnyLook) "The Book card and the other looks, mixed at random." else "The book's cover with your passage, in one of 15 layouts.",
                    style = CpType.small, color = c.quiet, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                )
                ThreadsSwitch("Show my note", "With its passage, on the card or as the post's words", state.threadsWithNote) { vm.setThreadsOptions(withNote = it) }
                ThreadsSwitch("Post notes without a passage", "A note on a bookmark goes up as a handwritten card", state.threadsNotes) { vm.setThreadsOptions(notes = it) }
                // Your edition only: a line under each post pointing people to Readync's public repo.
                if (io.github.prithirajn.readync.Edition.full) {
                    ThreadsSwitch("Add \u201CBuilt with Readync\u201D", "A line with the link to Readync on GitHub under each post, worded a few ways", ThreadsAuto.credit(state)) { vm.setThreadsCredit(it) }
                }

                VSpace(6)
                RowDivider()
                // The finer choices, folded away until wanted.
                MoreOptions("Topic tag", summary = when (state.threadsTopic) { "genre" -> "By the book's genre"; "" -> "None"; else -> state.threadsTopic }) {
                    Text("Threads shows one topic with each post, so people following it find yours.", style = CpType.small, color = c.quiet)
                    VSpace(8)
                    var customTopic by remember { mutableStateOf(state.threadsTopic.takeIf { it != "genre" && it != "Books" && it != "BookThreads" }.orEmpty()) }
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((label, value) in listOf("By genre" to "genre", "Books" to "Books", "BookThreads" to "BookThreads", "None" to "")) {
                            PillButton(label, { vm.setThreadsOptions(topic = value) }, outlined = state.threadsTopic != value)
                        }
                    }
                    VSpace(10)
                    CpTextField(customTopic, { customTopic = it.take(50) }, "Or your own", placeholder = "e.g. Dungeon Crawler Carl",
                        trailing = { if (customTopic.isNotBlank()) PillButton("Use", { vm.setThreadsOptions(topic = customTopic.trim()) }, outlined = state.threadsTopic != customTopic.trim()) })
                    VSpace(6)
                    Text(if (state.threadsTopic == "genre") "LitRPG, Fantasy, Science Fiction, Horror or Web Novels from what the book is; Books otherwise." else if (state.threadsTopic.isEmpty()) "No topic." else "Every post: ${state.threadsTopic}.", style = CpType.small, color = c.faint)
                    VSpace(6)
                }
                RowDivider()
                val aiOn = remember { vm.aiReady() }
                MoreOptions("On-device AI", summary = if (!aiOn) "Add a model to use it" else listOfNotNull("spoilers hidden".takeIf { state.threadsAi }, "opening lines".takeIf { state.threadsAiCaptions }).joinToString(", ").ifEmpty { "Off" }.replaceFirstChar { it.uppercase() }) {
                    if (!aiOn) Text("Add a model under Settings \u203A On-device AI. It runs on your phone; nothing is sent anywhere.", style = CpType.small, color = c.quiet, modifier = Modifier.padding(bottom = 8.dp))
                    else {
                        ThreadsSwitch("Hide spoilers, pick topics", "Spoilers go behind tap-to-reveal; other genres get a fitting topic", state.threadsAi) { vm.setThreadsOptions(ai = it) }
                        ThreadsSwitch("Write an opening line", "A short line in your voice when a highlight has no note", state.threadsAiCaptions) { vm.setThreadsOptions(aiCaptions = it) }
                    }
                }
                RowDivider()

                msg?.let { VSpace(8); Quiet(it, color = c.faint) }
                VSpace(14)
                BigButton(if (busy) "Posting\u2026" else "Post my newest now", { if (!busy) { busy = true; msg = null; vm.postNewestToThreads { m -> busy = false; msg = m } } }, busy = busy)
                VSpace(8)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigButton("New token", { showHow = true }, Modifier.weight(1f), filled = false)
                    BigButton("Disconnect", { vm.disconnectThreads() }, Modifier.weight(1f), filled = false, danger = true)
                }
                VSpace(10)
                Text("Pictures go up briefly on a public file host (Litterbox, Uguu, tmpfiles.org, qu.ax or x0.at) for Threads to copy.", style = CpType.small, color = c.faint)
            } else msg?.let { VSpace(6); Text(it, style = CpType.small, color = c.errorText) }
        }
    }
}


/** The way into the Add sheet: one dashed row, instead of every unlinked service listed. */
@Composable
internal fun AddServiceRow(text: String, onClick: () -> Unit) {
    val c = Cp.colors
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val turn by androidx.compose.animation.core.animateFloatAsState(if (pressed) 90f else 0f, Motion.bouncy(), label = "plusTurn")
    val edge by animateColorAsState(if (pressed) c.accent else c.track, Motion.fast(), label = "addEdge")
    Row(
        Modifier.fillMaxWidth()
            .clip(CpShapes.card)
            // An empty slot waiting for a service: a dashed outline.
            .drawBehind {
                drawShape(CpShapes.card, edge, stroke = 1.5.dp.toPx(), dash = floatArrayOf(7.dp.toPx(), 5.dp.toPx()))
            }
            .pressRow(source, text, onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
            LucideIcon(R.drawable.lucide_plus, Modifier.size(16.dp).graphicsLayer { rotationZ = turn }, c.onAccent)
        }
        HSpace(12)
        Text(text, style = CpType.bodyStrong, color = c.text)
    }
}

/** One place highlights can go as text: its badge, how it's doing, and a light when it's on. */
@Composable
private fun ExportRow(state: Snapshot, key: String, name: String, on: Boolean, summary: String, onClick: () -> Unit) {
    val c = Cp.colors
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val err = state.exportErrors[key]
    Row(
        Modifier.fillMaxWidth().pressRow(source, name, onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.graphicsLayer { alpha = if (on) 1f else 0.55f }) { ServiceTile(name) }
        HSpace(12)
        Column(Modifier.weight(1f)) {
            Text(name, style = CpType.bodyStrong, color = c.text)
            Text(
                err?.let { "Needs a look · $it" } ?: if (on) summary + (state.exportOkAt[key]?.let { " · ${ago(it * 1000)}" } ?: "") else summary,
                style = CpType.small, color = if (err != null) c.errorText else c.quiet, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        if (on) { Led(err == null, color = if (err != null) c.error else Color(0xFF4ADE80), size = 7.dp); HSpace(10) }
        else { Chip("Set up"); HSpace(6) }
        LucideIcon(R.drawable.lucide_chevron_right, Modifier.size(16.dp), c.faint)
    }
}

/** A heading inside the Threads settings: a small label with a rule. */
@Composable
internal fun ThreadsSection(title: String) {
    val c = Cp.colors
    VSpace(22)
    Row(verticalAlignment = Alignment.CenterVertically) {
        DeviceLabel(title, color = c.quiet)
        Box(Modifier.padding(start = 10.dp).weight(1f).height(1.dp).background(c.divider))
    }
    VSpace(10)
}

/** One switch: a title, one short line under it, the switch beside it; the whole row flips it. */
@Composable
internal fun ThreadsSwitch(title: String, line: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Cp.colors
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().clip(CpShapes.control).pressRow(source, title, { onChange(!checked) }).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 14.dp)) {
            Text(title, style = CpType.body, color = c.text)
            line?.let { Text(it, style = CpType.small, color = c.quiet) }
        }
        CpToggle(checked, onChange, title)
    }
}

/** − n + : two small keys with the number rolling between them. */
@Composable
private fun Stepper(value: Int, onMinus: () -> Unit, onPlus: () -> Unit) {
    val c = Cp.colors
    Row(
        Modifier.clip(CpShapes.pill).background(c.field).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Key(onMinus, Modifier.size(34.dp, 36.dp), shape = CircleShape, ledge = 2.dp, label = "Fewer") { Text("\u2212", style = CpType.bodyStrong, color = c.text) }
        Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
            RollingNumber(value, CpType.screenSmall.copy(fontSize = 20.sp, lineHeight = 22.sp), c.accentText, countUp = false)
        }
        Key(onPlus, Modifier.size(34.dp, 36.dp), shape = CircleShape, ledge = 2.dp, label = "More") { Text("+", style = CpType.bodyStrong, color = c.text) }
    }
}

/**
 * Check highlights before sending: off for most, who let them go out by themselves. On, new
 * highlights wait to be marked fine, a spoiler or not to send, and what a spoiler does on the
 * trackers with no spoiler setting is chosen here.
 */
@Composable
private fun CheckHighlightsCard(vm: AppViewModel, state: Snapshot) {
    val c = Cp.colors
    var checking by remember { mutableStateOf(false) }
    val waiting = remember(state.checkHighlights, state.clipChecked, state.sentClippings, state.cpClips, state.paperClips, state.kindleClips, state.koClips) {
        io.github.prithirajn.readync.sync.SpoilerCheck.waiting(state).size
    }
    CpCard {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp).animateContentSize()) {
            ThreadsSwitch(
                "Check highlights before sending",
                if (state.checkHighlights) "New highlights wait until you mark them fine, a spoiler or not to send." else "Off: highlights go out by themselves.",
                state.checkHighlights,
            ) { vm.setCheckHighlights(it) }
            if (state.checkHighlights) {
                VSpace(6)
                Text("Spoilers on Goodreads and Micro.blog", style = CpType.label, color = c.text)
                Quiet("They post publicly and have no spoiler setting. Fable and Pagebound hide a spoiler behind their own tap; private journals take it as it is.", color = c.faint)
                VSpace(8)
                Segmented(listOf("hold", "warn"), state.spoilerPublic, { if (it == "hold") "Hold back" else "Send with a warning" }, { vm.setSpoilerPublic(it) }, Modifier.fillMaxWidth())
                VSpace(12)
                if (waiting > 0) BigButton("Check $waiting highlight${if (waiting == 1) "" else "s"}", { checking = true }, look = KeyKind.INK)
                else Quiet("Nothing waiting. New highlights show up here, on the book's Highlights tab and at the top of your library.", color = c.faint)
                VSpace(10)
            }
        }
    }
    if (checking) HighlightCheckSheet(vm, state) { checking = false }
}
