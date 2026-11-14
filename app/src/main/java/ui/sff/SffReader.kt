package io.github.prithirajn.readync.ui

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.prithirajn.readync.R
import io.github.prithirajn.readync.data.ReadStatus
import io.github.prithirajn.readync.data.SffFeedItem
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.source.SffArticleReader
import io.github.prithirajn.readync.sync.Matcher
import io.github.prithirajn.readync.sync.SffArtifactAi
import io.github.prithirajn.readync.sync.SffRadar
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.CpShapes
import io.github.prithirajn.readync.ui.theme.CpType
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Full-Screen Native In-App Article, Thread & Video Reader Sheet (Artifact style):
 * - Multi-source perspective switcher at the top when stories are clubbed together
 * - 1-tap On-Device AI 3-Bullet Summary & Anti-Clickbait Headline Rewriter
 * - Clean native Markdown typography for articles, blockquotes, headings, and lists
 * - Full Reddit / Bluesky / Mastodon discussion threads & nested replies with spoiler tags
 * - Inline YouTube / BookTube player & optional "Original Web View" toggle so you never leave Tracker Sync
 * - Automatic dwell-time tracking to train your personal feed algorithm
 */
internal val SFF_REACTIONS = listOf("❤️", "🔥", "🤯", "😂", "😢")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SffArticleReaderSheet(
    vm: AppViewModel,
    state: Snapshot,
    cluster: SffArtifactAi.SffStoryCluster,
    initialPerspectiveIndex: Int,
    libraryBaseTitles: Map<String, io.github.prithirajn.readync.data.Book>,
    onSelectTag: (String) -> Unit,
    onClose: (Int) -> Unit,
) {
    val c = Cp.colors
    val openedAtMs = remember { System.currentTimeMillis() }
    var perspectiveIndex by rememberSaveable { mutableIntStateOf(initialPerspectiveIndex.coerceIn(0, cluster.perspectives.lastIndex)) }
    val currentItem = cluster.perspectives.getOrElse(perspectiveIndex) { cluster.lead }

    var article by remember(currentItem.id) { mutableStateOf<SffArticleReader.ReadableArticle?>(null) }
    var loadingArticle by remember(currentItem.id) { mutableStateOf(true) }
    var bullets by remember(cluster.id) { mutableStateOf(cluster.lead.aiBullets) }
    var bulletsFromLlm by remember(cluster.id) { mutableStateOf(false) }
    var summarizing by remember(cluster.id) { mutableStateOf(false) }
    var headlineOverride by remember(currentItem.id) { mutableStateOf(currentItem.aiHeadline) }
    var rewritingTitle by remember(currentItem.id) { mutableStateOf(false) }
    // Answers from Explain, Compare sources and Spoiler check, shown in a panel above the action bar.
    var aiInsightTitle by remember(cluster.id) { mutableStateOf<String?>(null) }
    var aiInsightBody by remember(cluster.id) { mutableStateOf<String?>(null) }
    var runningExtraAi by remember(cluster.id) { mutableStateOf(false) }
    /** False = Clean Native Reader View; True = Embedded In-App Live Page View (WebView inside modal). */
    var liveWebViewMode by remember(currentItem.id) { mutableStateOf(false) }
    /** The page "Page View" shows: the story's own page, or a link tapped inside the reader. */
    var pageUrl by remember(currentItem.id) { mutableStateOf(currentItem.url) }
    /** The reader's text size (Aa): a few steps, kept while the app is open. */
    var fontStep by rememberSaveable { mutableIntStateOf(readerFontStep) }
    val fontScale = READER_FONT_STEPS[fontStep.coerceIn(0, READER_FONT_STEPS.lastIndex)]
    var aiMenu by remember { mutableStateOf(false) }

    LaunchedEffect(currentItem.id) {
        loadingArticle = true
        vm.loadSffArticle(currentItem) { loaded ->
            article = loaded
            loadingArticle = false
        }
    }

    fun finishAndClose() {
        val dwellSec = ((System.currentTimeMillis() - openedAtMs) / 1000L).toInt().coerceIn(1, 1800)
        onClose(dwellSec)
    }

    // A full screen of its own, not a sheet: nothing around the article takes a swipe, so it
    // scrolls with one finger. Done or the back button closes it.
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { finishAndClose() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // The dialog's window exactly the screen: left to wrap its content, it came out taller than
        // the screen and the bar along the bottom (save, 👍 👎, AI, share) sat just below it, out of sight.
        val dialogView = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.SideEffect {
            (dialogView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { w ->
                w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .background(c.page)
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            // 1. The top bar: close, the outlet and how long it takes, text size, and the web page.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconCircleButton(io.github.prithirajn.readync.R.drawable.lucide_x, "Close", { finishAndClose() }, tint = c.text)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(currentItem.source.substringBefore(" ·"), style = CpType.bodyStrong, color = c.text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    article?.let { a -> Quiet("${a.readingTimeMinutes} min read" + (if (a.comments.isNotEmpty()) " · ${a.comments.size} replies" else ""), maxLines = 1) }
                }
                if (!liveWebViewMode) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(c.divider)
                            .bouncyClickable(CircleShape, pressedScale = 0.9f, onClickLabel = "Text size") {
                                fontStep = (fontStep + 1) % READER_FONT_STEPS.size; readerFontStep = fontStep
                            },
                        contentAlignment = Alignment.Center,
                    ) { Text("Aa", style = CpType.label.copy(fontSize = (12 + fontStep * 2).sp), color = c.text) }
                    HSpace(8)
                }
                if (!currentItem.url.isNullOrBlank()) {
                    PillButton(if (liveWebViewMode) "Reader" else "Web page", {
                        if (!liveWebViewMode) pageUrl = currentItem.url
                        liveWebViewMode = !liveWebViewMode
                    })
                }
            }

            // 2. When several outlets carry the story: which one you're reading.
            if (cluster.perspectives.size > 1) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    cluster.perspectives.forEachIndexed { idx, p ->
                        val on = idx == perspectiveIndex
                        Box(
                            Modifier.clip(CpShapes.pill).background(if (on) c.text else c.divider)
                                .bouncyClickable(CpShapes.pill, pressedScale = 0.94f, onClickLabel = p.source) { perspectiveIndex = idx; liveWebViewMode = false }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) { Text(p.source.substringBefore(" ·"), style = CpType.small, color = if (on) c.page else c.textMid, maxLines = 1) }
                    }
                }
            }

            RowDivider()

            // 3. Either Embedded Live In-App WebView Mode OR Clean Native Artifact Reader Mode
            val readerArticle = article
            if (liveWebViewMode && !pageUrl.isNullOrBlank()) {
                InAppArticleWebView(
                    url = pageUrl!!,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    blockAds = true,
                )
            } else if (!loadingArticle && readerArticle != null && readerArticle.bodyHtml.isNotBlank()) {
                // The ad-free reader, as Artifact showed a story: its topics, then the article on a card
                // (lead image across the top, publisher, big headline, summary card, the text with its
                // pictures and captions, replies on cards below), and the actions in a bar at the bottom.
                val doc = SffArticleReader.buildArtifactReaderDocument(
                    article = readerArticle,
                    item = currentItem.copy(aiHeadline = headlineOverride),
                    aiBullets = bullets,
                    bgHex = c.page.hex(),
                    surfaceHex = c.surface.hex(),
                    textHex = c.text.hex(),
                    textMidHex = c.textMid.hex(),
                    quietHex = c.quiet.hex(),
                    accentHex = c.accentText.hex(),
                    accentTintHex = c.accentTint.hex(),
                    borderHex = c.hairline.hex(),
                    aiBulletsFromModel = bulletsFromLlm,
                    fontScale = fontScale,
                    tags = cluster.canonicalTags,
                    others = cluster.perspectives.mapIndexedNotNull { idx, p ->
                        if (idx == perspectiveIndex) null else SffArticleReader.Others(idx, p.source, p.title, p.summary)
                    },
                )
                ArtifactReaderWebView(
                    html = doc,
                    baseUrl = readerArticle.originalUrl ?: currentItem.url,
                    background = c.page,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onOpenLink = { link ->
                        when {
                            link.startsWith("readync://perspective/") -> link.substringAfterLast('/').toIntOrNull()?.let { perspectiveIndex = it; liveWebViewMode = false }
                            link.startsWith("readync://tag/") -> onSelectTag(java.net.URLDecoder.decode(link.substringAfter("readync://tag/"), "UTF-8"))
                            else -> { pageUrl = link; liveWebViewMode = true }
                        }
                    },
                )
                // Bottom action bar.
                val context = androidx.compose.ui.platform.LocalContext.current
                Column(Modifier.fillMaxWidth().background(c.surface)) {
                    RowDivider()
                    if (summarizing || rewritingTitle || runningExtraAi) {
                        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                            AiThinking(
                                when {
                                    summarizing -> "Summing it up on this phone…"
                                    rewritingTitle -> "Rewriting the title on this phone…"
                                    else -> "Thinking it over on this phone…"
                                }
                            )
                        }
                    }
                    aiInsightBody?.let { insight ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 12.dp, top = 8.dp)
                                .clip(CpShapes.control)
                                .background(c.accentTint.copy(alpha = 0.55f))
                                .heightIn(max = 180.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(aiInsightTitle ?: "On-device AI", style = CpType.bodyStrong, color = c.accentText, modifier = Modifier.weight(1f))
                                Box(Modifier.bouncyClickable(CpShapes.pill, pressedScale = 0.92f) { aiInsightBody = null }) { Quiet("Dismiss") }
                            }
                            VSpace(4)
                            Text(insight, style = CpType.small, color = c.text)
                        }
                    }
                    // The bar: save, more or less like it, the AI's help, your shelf, share.
                    val isSaved = state.sffSky.saved.any { it.id == currentItem.id }
                    val existing = libraryBaseTitles[Matcher.baseTitle(currentItem.title)]
                    val shareUrl = readerArticle.originalUrl ?: currentItem.url
                    fun runAi(title: String, call: ((String, Boolean) -> Unit) -> Unit) {
                        if (runningExtraAi) return
                        runningExtraAi = true
                        call { text, fromLlm ->
                            aiInsightTitle = if (fromLlm) "On-device AI · $title" else title
                            aiInsightBody = text
                            runningExtraAi = false
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BarKey(io.github.prithirajn.readync.R.drawable.lucide_book_plus, if (isSaved) "Saved" else "Save", on = isSaved) { vm.sffSave(currentItem) }
                        BarKey(null, "👍", label = "More like this") { vm.sffFeedback(cluster, true) }
                        BarKey(null, "👎", label = "Less like this") { vm.sffFeedback(cluster, false); finishAndClose() }
                        Box {
                            BarKey(io.github.prithirajn.readync.R.drawable.lucide_sparkles, "AI", on = aiMenu) { aiMenu = true }
                            androidx.compose.material3.DropdownMenu(aiMenu, { aiMenu = false }) {
                                @Composable fun pick(text: String, act: () -> Unit) = androidx.compose.material3.DropdownMenuItem(text = { Text(text, style = CpType.body, color = c.text) }, onClick = { aiMenu = false; act() })
                                pick(if (bullets.isNotEmpty()) "Sum it up again" else "Sum it up") {
                                    if (!summarizing) {
                                        summarizing = true
                                        vm.summarizeSffCluster(cluster, readerArticle.bodyMarkdown) { res, fromLlm -> bullets = res; bulletsFromLlm = fromLlm; summarizing = false }
                                    }
                                }
                                pick(if (headlineOverride != null) "Original headline" else "Say what it's about (headline)") {
                                    if (headlineOverride != null) headlineOverride = null
                                    else if (!rewritingTitle) {
                                        rewritingTitle = true
                                        vm.rewriteSffClickbait(currentItem, readerArticle.bodyMarkdown) { newTitle, _ -> headlineOverride = newTitle; rewritingTitle = false }
                                    }
                                }
                                pick("Explain the background") { runAi("Background") { done -> vm.explainSffContext(cluster, readerArticle.bodyMarkdown, done) } }
                                if (cluster.perspectives.size >= 2) pick("Compare the ${cluster.perspectives.size} outlets") { runAi("How the outlets compare") { done -> vm.compareSffPerspectives(cluster, readerArticle.bodyMarkdown, done) } }
                                pick("Check for spoilers") { runAi("Spoiler check") { done -> vm.checkSffSpoilers(cluster, readerArticle.bodyMarkdown, done) } }
                            }
                        }
                        Box(Modifier.weight(1f))
                        if (existing != null) PillButton("In library", { finishAndClose(); AppActions.pending.value = AppAction.OpenBook(existing.document) })
                        else if (currentItem.category == "DISCOVERY" || currentItem.category == "SERIES_HORIZON") PillButton("+ Want to read", { vm.shelfSffItem(currentItem) })
                        if (!shareUrl.isNullOrBlank()) {
                            BarKey(io.github.prithirajn.readync.R.drawable.lucide_upload, null, label = "Share") {
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(android.content.Intent.EXTRA_SUBJECT, headlineOverride ?: currentItem.title)
                                    .putExtra(android.content.Intent.EXTRA_TEXT, "${headlineOverride ?: currentItem.title}\n$shareUrl")
                                runCatching { context.startActivity(android.content.Intent.createChooser(send, "Share story")) }
                            }
                        }
                    }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    // Canonical Tags Row
                    if (cluster.canonicalTags.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (t in cluster.canonicalTags) {
                                Box(Modifier.bouncyClickable(CpShapes.pill, pressedScale = 0.94f) { onSelectTag(t) }) {
                                    Chip("#$t", color = c.accentText, background = c.accentTint)
                                }
                            }
                        }
                        VSpace(12)
                    }

                    // Article Headline + Byline
                    Text(
                        headlineOverride ?: article?.title ?: currentItem.title,
                        style = CpType.pageTitle,
                        color = c.text,
                    )
                    if (headlineOverride != null) {
                        VSpace(4)
                        Quiet("Original headline: “${currentItem.title}”", color = c.faint)
                    }
                    VSpace(6)
                    Quiet(article?.byline ?: currentItem.subtitle ?: currentItem.source)

                    // Artifact On-Device AI Toolbar (3-Bullet Summary + De-Clickbait + Shelf + Feedback)
                    VSpace(14)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PillButton(
                            text = when {
                                summarizing -> "Summarizing…"
                                bullets.isNotEmpty() -> "↻ Refresh 3-Bullet Summary"
                                else -> "✨ 3-Bullet AI Summary"
                            },
                            onClick = {
                                if (!summarizing) {
                                    summarizing = true
                                    val body = article?.bodyMarkdown ?: currentItem.fullContent ?: currentItem.summary.orEmpty()
                                    vm.summarizeSffCluster(cluster, body) { res, fromLlm ->
                                        bullets = res
                                        bulletsFromLlm = fromLlm
                                        summarizing = false
                                    }
                                }
                            },
                            outlined = false,
                        )
                        PillButton(
                            text = if (rewritingTitle) "Rewriting…" else "✨ De-Clickbait Title",
                            onClick = {
                                if (!rewritingTitle) {
                                    rewritingTitle = true
                                    val body = article?.bodyMarkdown ?: currentItem.fullContent ?: currentItem.summary.orEmpty()
                                    vm.rewriteSffClickbait(currentItem, body) { newTitle, _ ->
                                        headlineOverride = newTitle
                                        rewritingTitle = false
                                    }
                                }
                            },
                        )
                        val existing = libraryBaseTitles[Matcher.baseTitle(currentItem.title)]
                        if (existing != null) {
                            PillButton("In Library", {
                                finishAndClose()
                                AppActions.pending.value = AppAction.OpenBook(existing.document)
                            })
                        } else {
                            PillButton("+ Want to Read", { vm.shelfSffItem(currentItem) })
                        }
                        PillButton("👍 More like this", { vm.sffFeedback(cluster, true) })
                        PillButton("👎 Less", {
                            vm.sffFeedback(cluster, false)
                            finishAndClose()
                        })
                    }

                    // AI 3-Bullet Summary Box (when generated)
                    if (summarizing) {
                        VSpace(14)
                        AiThinking("Distilling 3 key takeaways on this phone…")
                    } else if (bullets.isNotEmpty()) {
                        VSpace(14)
                        CpCard {
                            Column(Modifier.padding(14.dp)) {
                                Text(
                                    if (bulletsFromLlm) "✨ Key Takeaways (On-Device AI)" else "✨ Key Takeaways (Artifact Summary)",
                                    style = CpType.small,
                                    color = c.accentText,
                                )
                                VSpace(6)
                                for (b in bullets) {
                                    Text("• $b", style = CpType.body, color = c.text, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }

                    // Inline YouTube Video Player when this item is a BookTube / Author video
                    article?.youtubeVideoId?.let { vid ->
                        VSpace(16)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(CpShapes.card)
                                .border(1.dp, c.hairline, CpShapes.card)
                        ) {
                            InAppArticleWebView(
                                url = "https://www.youtube.com/embed/$vid?playsinline=1",
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }

                    // Hero / Cover Image
                    val hero = article?.heroImageUrl ?: currentItem.coverUrl
                    if (!hero.isNullOrBlank() && article?.youtubeVideoId == null) {
                        VSpace(16)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BookCover(hero, currentItem.title, 76.dp, 114.dp)
                            HSpace(14)
                            Column(Modifier.weight(1f)) {
                                currentItem.series?.let { s ->
                                    Text("$s" + (currentItem.seriesNumber?.let { " · Book $it" } ?: ""), style = CpType.bodyStrong, color = c.accentText)
                                }
                                currentItem.author?.let { Quiet("By $it") }
                                currentItem.rating?.let { r ->
                                    Quiet(String.format(Locale.US, "Rated %.1f ★", r) + (currentItem.pages?.let { " · $it pages" } ?: ""))
                                }
                            }
                        }
                    }

                    VSpace(18)
                    RowDivider()
                    VSpace(16)

                    // Full Article Body (Markdown rendered natively)
                    if (loadingArticle) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                            HSpace(10)
                            Quiet("Extracting full article text for in-app reading…")
                        }
                        VSpace(12)
                        currentItem.summary?.let { MarkdownText(it) }
                    } else {
                        val bodyMd = article?.bodyMarkdown?.takeIf { it.isNotBlank() } ?: currentItem.summary ?: currentItem.title
                        MarkdownText(bodyMd)
                    }

                    // Discussion Comments & Thread Replies (for Reddit, Bluesky, Mastodon)
                    val comments = article?.comments.orEmpty()
                    if (comments.isNotEmpty()) {
                        VSpace(24)
                        RowDivider()
                        VSpace(14)
                        Text("Community Discussion (${comments.size} top replies)", style = CpType.section, color = c.text)
                        Quiet("Read the thread directly inside ${io.github.prithirajn.readync.Edition.name}. Spoilers are shaded.")
                        VSpace(10)
                        for (cm in comments) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = (cm.depth * 14).dp, top = 8.dp)
                                    .clip(CpShapes.control)
                                    .background(c.surface)
                                    .border(1.dp, c.hairline, CpShapes.control)
                                    .padding(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(cm.author, style = CpType.label, color = c.accentText)
                                    cm.score?.let { sc ->
                                        HSpace(8)
                                        Quiet(sc)
                                    }
                                }
                                VSpace(4)
                                MarkdownText(cm.body)
                            }
                        }
                    }

                    // Other Perspectives in this Cluster at the bottom of the article
                    if (cluster.perspectives.size > 1) {
                        VSpace(24)
                        RowDivider()
                        VSpace(14)
                        Text("Other Perspectives on This Story", style = CpType.bodyStrong, color = c.text)
                        VSpace(8)
                        cluster.perspectives.forEachIndexed { idx, p ->
                            if (idx != perspectiveIndex) {
                                CpCard(
                                    Modifier
                                        .padding(bottom = 8.dp)
                                        .bouncyClickable(CpShapes.card, pressedScale = 0.99f) {
                                            perspectiveIndex = idx
                                            liveWebViewMode = false
                                        }
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(p.source, style = CpType.label, color = c.accentText)
                                        VSpace(2)
                                        Text(p.title, style = CpType.bodyStrong, color = c.text)
                                        p.summary?.let {
                                            VSpace(4)
                                            Quiet(it, maxLines = 2)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Embedded In-App WebView for "Page View" and inline BookTube videos: keeps navigation strictly
 * inside Tracker Sync (`shouldOverrideUrlLoading` returns false) so external apps never launch.
 */
/** A WebView in a sheet keeps its own scrolling and zooming: the views around it don't take the touch. */
@SuppressLint("ClickableViewAccessibility")
private fun keepGestures(w: WebView) {
    w.isNestedScrollingEnabled = true
    w.setOnTouchListener { v, e ->
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN || e.actionMasked == android.view.MotionEvent.ACTION_MOVE) {
            v.parent?.requestDisallowInterceptTouchEvent(true)
        }
        false
    }
}

private fun Color.hex(): String = String.format("#%06X", 0xFFFFFF and toArgb())

/**
 * The ad-free reader page ([html], from [SffArticleReader.buildArtifactReaderDocument]). Links
 * tapped in it open in Page View ([onOpenLink]) rather than replacing the article.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ArtifactReaderWebView(html: String, baseUrl: String?, background: Color, modifier: Modifier = Modifier, onOpenLink: (String) -> Unit) {
    var webView: WebView? by remember { mutableStateOf(null) }
    var shown by remember { mutableStateOf<String?>(null) }
    val openLink by androidx.compose.runtime.rememberUpdatedState(onOpenLink)
    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                webView = this
                keepGestures(this)
                setBackgroundColor(background.toArgb())
                settings.javaScriptEnabled = true // tap-to-reveal spoilers and the video player
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = true
                settings.allowFileAccess = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val target = request?.url?.toString() ?: return true
                        if (request?.isForMainFrame == true && (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("readync://"))) openLink(target)
                        return true
                    }
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        if (request == null) return null
                        // The app's own fonts for the page (Lora to read in, Inter for the small print).
                        SffArticleReader.fontFor(request.url.toString())?.let { id ->
                            return runCatching {
                                WebResourceResponse("font/ttf", null, ctx.resources.openRawResource(id)).apply {
                                    responseHeaders = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=31536000")
                                }
                            }.getOrNull()
                        }
                        return if (SffArticleReader.shouldBlockRequest(request)) SffArticleReader.emptyAdResponse() else null
                    }
                }
            }
        },
        update = { view ->
            if (shown != html) {
                shown = html
                view.setBackgroundColor(background.toArgb())
                view.loadDataWithBaseURL(baseUrl ?: "https://trackersync.invalid/", html, "text/html", "utf-8", null)
            }
        },
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun InAppArticleWebView(url: String, modifier: Modifier = Modifier, blockAds: Boolean = false) {
    var webView: WebView? by remember { mutableStateOf(null) }
    // Dark like the app when the app is dark: the page darkened by the WebView itself (pages with their
    // own dark look use it), on a dark background so it doesn't flash white while loading.
    val dark = io.github.prithirajn.readync.ui.theme.Cp.colors.isDark
    val pageBg = io.github.prithirajn.readync.ui.theme.Cp.colors.page
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    LaunchedEffect(Unit) { io.github.prithirajn.readync.source.AdBlockList.warm(appContext) }
    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // A dark theme for the WebView's own context, which is what tells it to darken pages.
            val themed = if (dark) android.view.ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault_NoActionBar) else ctx
            WebView(themed).apply {
                webView = this
                keepGestures(this)
                setBackgroundColor(pageBg.toArgb())
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING)) {
                    androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, dark)
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val target = request?.url?.toString() ?: return false
                        // Stay inside the in-app WebView for all http/https links
                        return !target.startsWith("http://") && !target.startsWith("https://")
                    }
                    // Page View without the ads, trackers and pop-ups; cookie and newsletter boxes hidden.
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                        if (blockAds && request != null && SffArticleReader.shouldBlockRequest(request)) SffArticleReader.emptyAdResponse() else null
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (blockAds && view != null) SffArticleReader.injectCleanPageScript(view)
                        // Older WebViews that can't darken a page: it asks for the site's own dark look,
                        // and a light page is turned dark (pictures and videos kept as they are).
                        if (dark && view != null && !androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING)) {
                            view.evaluateJavascript(SffArticleReader.DARK_PAGE_JS, null)
                        }
                    }
                }
                loadUrl(url)
            }
        },
        update = { view ->
            if (view.url != url) view.loadUrl(url)
        },
    )
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SocialFollowCard(
    vm: AppViewModel,
    followed: List<String>,
) {
    val c = Cp.colors
    val platforms = listOf(
        "x" to "X (Twitter)",
        "insta" to "Instagram",
        "bsky" to "Bluesky",
        "mastodon" to "Mastodon Tag",
        "threads" to "Threads",
        "yt" to "YouTube ID",
    )
    var selectedPlatform by rememberSaveable { mutableStateOf("x") }
    var handleInput by rememberSaveable { mutableStateOf("") }

    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
        CpCard {
            Column(Modifier.padding(16.dp)) {
                Text("Follow SFF Accounts & Tags", style = CpType.bodyStrong, color = c.text)
                Quiet("Built-in streams pull from Tor, Orbit, Del Rey, Dragonsteel, Brandon Sanderson, John Scalzi, Martha Wells, #BookSky, wandering.shop & BookTube. Add any extra handle or tag below.")
                VSpace(10)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((id, label) in platforms) {
                        val on = selectedPlatform == id
                        Box(Modifier.bouncyClickable(CpShapes.pill, pressedScale = 0.94f) { selectedPlatform = id }) {
                            Chip(
                                label,
                                color = if (on) c.accentText else c.quiet,
                                background = if (on) c.accentTint else c.divider,
                            )
                        }
                    }
                }
                VSpace(10)
                CpTextField(
                    value = handleInput,
                    onChange = { handleInput = it },
                    label = when (selectedPlatform) {
                        "x" -> "X / Twitter handle"
                        "insta" -> "Instagram username"
                        "bsky" -> "Bluesky handle or #hashtag"
                        "mastodon" -> "Mastodon hashtag (on wandering.shop)"
                        "threads" -> "Threads username"
                        else -> "YouTube Channel ID (UC…)"
                    },
                    placeholder = when (selectedPlatform) {
                        "x" -> "@BrandSanderson or @Gollancz"
                        "insta" -> "@thebrokenbinding or @illumicrate"
                        "bsky" -> "adriantchaikovsky.bsky.social or #BookSky"
                        "mastodon" -> "sff or progressionfantasy"
                        "threads" -> "@torbooks"
                        else -> "UC3g-w83Cb5pEAu5UmRrge-A"
                    },
                )
                VSpace(10)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PillButton(
                        text = "+ Follow on ${platforms.first { it.first == selectedPlatform }.second}",
                        onClick = {
                            val clean = handleInput.trim().removePrefix("@")
                            if (clean.isNotEmpty()) {
                                vm.addSffSocial("$selectedPlatform:$clean")
                                handleInput = ""
                            }
                        },
                        outlined = false,
                    )
                }
                if (followed.isNotEmpty()) {
                    VSpace(12)
                    Text("Your custom followed handles (tap to unfollow)", style = CpType.small, color = c.quiet)
                    VSpace(6)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (h in followed) {
                            Box(Modifier.bouncyClickable(CpShapes.pill, pressedScale = 0.94f) { vm.removeSffSocial(h) }) {
                                Chip("$h ✕", color = c.accentText, background = c.accentTint)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Text sizes for the reader's Aa, and the one last chosen (while the app is open). */
private val READER_FONT_STEPS = listOf(0.9f, 1f, 1.12f, 1.25f)
private var readerFontStep = 1

/** A key in the reader's bar: an icon or a short word (or both), lit when it's on. */
@Composable
private fun BarKey(icon: Int?, text: String?, on: Boolean = false, label: String? = null, onClick: () -> Unit) {
    val c = Cp.colors
    Row(
        Modifier.height(40.dp).clip(CpShapes.pill).background(if (on) c.accentTint else c.divider)
            .bouncyClickable(CpShapes.pill, pressedScale = 0.92f, onClickLabel = label ?: text) { onClick() }
            .padding(horizontal = if (text == null) 11.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { LucideIcon(it, Modifier.size(16.dp), if (on) c.accentText else c.text) }
        if (icon != null && text != null) HSpace(6)
        text?.let { Text(it, style = CpType.label, color = if (on) c.accentText else c.text) }
    }
}
