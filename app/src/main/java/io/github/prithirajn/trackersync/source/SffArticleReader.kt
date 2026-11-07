package io.github.prithirajn.trackersync.source

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import io.github.prithirajn.trackersync.data.SffFeedItem
import io.github.prithirajn.trackersync.net.Http
import io.github.prithirajn.trackersync.util.Html
import io.github.prithirajn.trackersync.util.arr
import io.github.prithirajn.trackersync.util.obj
import io.github.prithirajn.trackersync.util.objects
import io.github.prithirajn.trackersync.util.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Artifact-style Ad-Free Rich Article, Thread & WebView Reader.
 *
 * 1. **Network-Level Ad & Tracker Blocker**: [shouldBlockRequest] intercepts and drops requests to
 *    ad networks, tracking pixels, video ad injectors, and popup scripts in `WebViewClient.shouldInterceptRequest`.
 * 2. **Cosmetic Ad & Clutter Zapper + Lazy-Image Hydrator**: [injectCleanPageScript] hides leftover
 *    ad containers, cookie/newsletter overlays, and sticky banners while promoting `data-src` /
 *    `data-lazy-src` attributes so all images load at full resolution.
 * 3. **Artifact Rich HTML Formatter**: Preserves full article formatting (`<h1>`–`<h4>`, `<p>`,
 *    `<blockquote>`, `<figure>` + `<img>` + `<figcaption>`, `<table>`, `<ul>`/`<ol>`, `<pre>`,
 *    `<strong>`, `<em>`, links, and inline discussion comments) inside a clean, theme-aware HTML
 *    document with zero ads.
 */
object SffArticleReader {

    /** Set by [io.github.prithirajn.trackersync.ui.PageBrowser.install] for JS/Cloudflare pages. */
    @Volatile var browser: ((String) -> String?)? = null

    data class CommentItem(
        val author: String,
        val score: String?,
        val body: String,
        val depth: Int = 0,
    )

    data class ReadableArticle(
        val title: String,
        val byline: String?,
        val source: String,
        val readingTimeMinutes: Int,
        val wordCount: Int,
        val heroImageUrl: String?,
        val extraImages: List<String> = emptyList(),
        /** Plain/Markdown representation used for on-device AI summarization & de-clickbaiting. */
        val bodyMarkdown: String,
        /** Rich, sanitized HTML preserving original formatting, inline images, captions, and tables. */
        val bodyHtml: String,
        /** Discussion replies (for Reddit threads, Bluesky threads, Mastodon threads). */
        val comments: List<CommentItem> = emptyList(),
        /** Embedded YouTube video ID when the item is a BookTube / Author video. */
        val youtubeVideoId: String? = null,
        val originalUrl: String? = null,
    )

    private val cache = ConcurrentHashMap<String, ReadableArticle>()

    // ---- 1. Network-Level Ad, Tracker & Clutter Blocker ----

    private val AD_DOMAINS = setOf(
        // Sign-up and text-message pop-ups, consent walls, video ads, ad verification and ID syncing.
        "attn.tv", "attentivemobile.com", "attentive.com", "klaviyo.com", "fundingchoicesmessages.google.com",
        "imasdk.googleapis.com", "adsafeprotected.com", "doubleverify.com", "moatpixel.com", "id5-sync.com",
        "liadm.com", "permutive.com", "permutive.app", "aniview.com", "media.net", "adlightning.com",
        "confiant-integrations.net", "browsiprod.com", "browsi.com", "bounceexchange.com", "cdn.cxense.com",
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
        "googletagmanager.com", "googletagservices.com", "adnxs.com", "adsrvr.org", "adform.net",
        "taboola.com", "outbrain.com", "amazon-adsystem.com", "criteo.com", "criteo.net",
        "rubiconproject.com", "pubmatic.com", "openx.net", "casalemedia.com", "indexww.com",
        "moatads.com", "scorecardresearch.com", "quantserve.com", "smartadserver.com",
        "mediavine.com", "adthrive.com", "raptive.com", "cafeMedia.com", "freestar.com",
        "nitropay.com", "primis.tech", "connatix.com", "ex.co", "playwire.com", "vidoomy.com",
        "teads.tv", "sharethrough.com", "triplelift.com", "33across.com", "sovrn.com",
        "lijit.com", "gumgum.com", "kargo.com", "nativo.com", "revcontent.com", "mgid.com",
        "zergnet.com", "zemanta.com", "bidswitch.net", "contextweb.com", "yieldmo.com",
        "spotxchange.com", " spotx.tv", "springserve.com", "innovid.com", "flashtalking.com",
        "celtra.com", "serving-sys.com", "Turn.com", "mathtag.com", "rlcdn.com", "bluekai.com",
        "krxd.net", "demdex.net", "omtrdc.net", "everesttech.net", "chartbeat.com", "chartbeat.net",
        "parsely.com", "newrelic.com", "nr-data.net", "hotjar.com", "mouseflow.com", "fullstory.com",
        "clarity.ms", "onesignal.com", "pushengage.com", "pushowl.com", "izooto.com",
        "tinypass.com", "piano.io", "pelcro.com", "zephr.com", "optinmonster.com", "privy.com",
        "sumo.com", "wisepops.com", " Justuno.com", "bounceexchange.com", " Wunderkind.co",
        "connect.facebook.net", "analytics.twitter.com", "static.ads-twitter.com", "ads.tiktok.com",
        "ads.linkedin.com", "bat.bing.com", " trc.taboola.com", "widgets.outbrain.com",
    )

    private val AD_HOSTS = AD_DOMAINS.map { it.trim().lowercase() }.toSet()

    private val AD_PATH_REGEX = Regex(
        "(?i)(/pagead/|/ads/|/adserver|/adunit|/adframe|/banner[-_]?ad|/popunder|/prebid|\\.ads\\.|/gpt\\.js|/adsbygoogle|/show_ads|/sponsor|/dfp/|/gam-|/pixel\\.|/beacon\\.)"
    )

    /** Returns true if a WebView request URL belongs to an ad network, tracker, or popup script. */
    fun shouldBlockRequest(request: WebResourceRequest): Boolean {
        if (request.isForMainFrame) return false
        val uri = request.url ?: return false
        val host = uri.host?.lowercase()?.trimStart('.') ?: return false
        if (AD_HOSTS.any { d -> host == d || host.endsWith(".$d") }) {
            return true
        }
        val full = uri.toString()
        return AD_PATH_REGEX.containsMatchIn(full)
    }

    /** Empty 204 response returned for blocked ad/tracker requests in `shouldInterceptRequest`. */
    fun emptyAdResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "UTF-8", 204, "No Content", mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(ByteArray(0)))

    /**
     * Cosmetic CSS & JS injected into Live Page View to hide ad placeholders, cookie consent
     * banners, newsletter popups, and sticky clutter while hydrating lazy-loaded article images.
     */
    const val CLEAN_PAGE_CSS = """
        ins.adsbygoogle, [id^="google_ads"], [id*="dfp-ad"], [class*="dfp-ad"],
        [class*="ad-slot"], [class*="ad-wrapper"], [class*="ad-container"], [class*="ad-unit"],
        [class*="ad-banner"], [id*="ad-slot"], [id*="ad-container"], [id*="taboola"], [class*="taboola"],
        [id*="outbrain"], [class*="outbrain"], [class*="mediavine"], [class*="adthrive"],
        [data-ad], [data-ad-unit], [data-ad-slot], [aria-label="advertisement"], [aria-label="Advertisement"],
        .advertisement, .sponsored-content, .ad-placeholder, .ad-label, .pub-ad, .dfp-slot,
        #onetrust-consent-sdk, .onetrust-pc-dark-filter, .fc-consent-root, .qc-cmp2-container,
        #CybotCookiebotDialog, [class*="cookie-banner"], [id*="cookie-banner"], [class*="gdpr-"],
        [class*="newsletter-popup"], [class*="subscribe-modal"], [class*="paywall-modal"] {
            display: none !important;
            height: 0 !important;
            min-height: 0 !important;
            max-height: 0 !important;
            margin: 0 !important;
            padding: 0 !important;
            overflow: hidden !important;
            visibility: hidden !important;
            pointer-events: none !important;
        }
        html, body {
            overflow: auto !important;
            position: static !important;
        }
        article img, main img, figure img, .entry-content img, .post-content img {
            max-width: 100% !important;
            height: auto !important;
            border-radius: 8px;
        }
    """

    fun injectCleanPageScript(webView: WebView) {
        val js = """
            (function() {
                if (!document.getElementById('ts-adblock-style')) {
                    var st = document.createElement('style');
                    st.id = 'ts-adblock-style';
                    st.textContent = ${JSONObject.quote(CLEAN_PAGE_CSS)};
                    (document.head || document.documentElement).appendChild(st);
                }
                function hydrateImages() {
                    var imgs = document.querySelectorAll('img');
                    for (var i = 0; i < imgs.length; i++) {
                        var img = imgs[i];
                        var lazy = img.getAttribute('data-src') || img.getAttribute('data-lazy-src') ||
                                   img.getAttribute('data-original') || img.getAttribute('data-actualsrc') ||
                                   img.getAttribute('data-hi-res-src');
                        if (lazy && (!img.src || img.src.indexOf('data:') === 0 || img.src.indexOf('placeholder') !== -1 || img.src.indexOf('blank') !== -1)) {
                            img.src = lazy;
                        }
                        var lazySet = img.getAttribute('data-srcset') || img.getAttribute('data-lazy-srcset');
                        if (lazySet && !img.srcset) {
                            img.srcset = lazySet;
                        }
                        img.removeAttribute('loading');
                    }
                }
                hydrateImages();
                setTimeout(hydrateImages, 800);
                // Pop-ups, sign-up boxes, sticky ads and the grey sheets behind them: anything pinned
                // over the page that covers much of it, or that asks you to sign up; the page scrolls again.
                var nag = /(subscribe|newsletter|sign up|signup|get .* texts|text alerts|download (our|the) app|turn off your ad ?blocker|advertisement|sponsored)/i;
                function sweep() {
                    var vw = window.innerWidth, vh = window.innerHeight;
                    var all = document.body ? document.body.querySelectorAll('*') : [];
                    for (var i = 0; i < all.length; i++) {
                        var el = all[i];
                        var cs = window.getComputedStyle(el);
                        if (cs.position !== 'fixed' && cs.position !== 'sticky') continue;
                        if (el.tagName === 'HEADER' || el.tagName === 'NAV') continue;
                        var r = el.getBoundingClientRect();
                        var area = Math.max(0, r.width) * Math.max(0, r.height);
                        var big = area > vw * vh * 0.25;
                        var text = (el.innerText || '').slice(0, 400);
                        if (big || nag.test(text) || el.querySelector('iframe')) {
                            el.style.setProperty('display', 'none', 'important');
                        }
                    }
                    var blocks = document.querySelectorAll('div, section, aside');
                    for (var j = 0; j < blocks.length; j++) {
                        var b = blocks[j];
                        if (b.children.length > 40) continue;
                        var t = (b.innerText || '').slice(0, 300);
                        if (t.length < 300 && /^\s*(advertisement|sponsored|get .* texts)/i.test(t)) b.style.setProperty('display', 'none', 'important');
                    }
                    [document.documentElement, document.body].forEach(function(e) {
                        if (!e) return;
                        e.style.setProperty('overflow', 'auto', 'important');
                        e.style.setProperty('position', 'static', 'important');
                    });
                }
                sweep();
                setTimeout(sweep, 1000);
                setTimeout(sweep, 3000);
                var pending = false;
                new MutationObserver(function() {
                    if (pending) return;
                    pending = true;
                    setTimeout(function() { pending = false; sweep(); }, 600);
                }).observe(document.documentElement, { childList: true, subtree: true });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // ---- 2. Full Article & Thread Extraction ----

    /** Loads and caches the full in-app [ReadableArticle] for [item]. */
    fun load(item: SffFeedItem): ReadableArticle {
        cache[item.id]?.let { return it }
        val fallback = fallbackArticle(item)
        val built = runCatching { fetchFull(item) }.getOrElse { fallback }
        if (built.bodyMarkdown != fallback.bodyMarkdown || item.url.isNullOrBlank()) cache[item.id] = built
        return built
    }

    private fun fetchFull(item: SffFeedItem): ReadableArticle {
        val url = item.url?.trim()

        // 1. YouTube / BookTube Video
        if (item.source.startsWith("YouTube") || (url != null && (url.contains("youtube.com/watch") || url.contains("youtu.be/")))) {
            val vid = url?.let {
                Regex("[?&]v=([A-Za-z0-9_-]{11})").find(it)?.groupValues?.get(1)
                    ?: Regex("youtu\\.be/([A-Za-z0-9_-]{11})").find(it)?.groupValues?.get(1)
            } ?: item.id.removePrefix("yt-").takeIf { it.length == 11 }
            val body = item.fullContent ?: item.summary ?: "Watch this SFF video right inside the reader above."
            val wc = countWords(body)
            return ReadableArticle(
                title = item.aiHeadline ?: item.title,
                byline = item.subtitle ?: item.author,
                source = item.source,
                readingTimeMinutes = maxOf(1, wc / 200),
                wordCount = wc,
                heroImageUrl = item.coverUrl,
                bodyMarkdown = body,
                bodyHtml = markdownToHtml(body),
                youtubeVideoId = vid,
                originalUrl = url,
            )
        }

        // 2. Reddit Thread (`r/Fantasy`, `r/PrintSF`, `r/ProgressionFantasy`, etc.)
        if (url != null && url.contains("reddit.com/r/")) {
            loadRedditThread(item, url)?.let { return it }
        }

        // 3. Bluesky Post (`bsky.app/profile/<handle>/post/<rkey>`)
        if (url != null && url.contains("bsky.app/profile/")) {
            loadBlueskyThread(item, url)?.let { return it }
        }

        // 4. Mastodon Status (`wandering.shop` / `mastodon.social`)
        if (item.source.startsWith("Mastodon") && url != null) {
            loadMastodonThread(item, url)?.let { return it }
        }

        // 5. Web Article / Magazine / Royal Road / Substack / Book Page: fetch HTML for full formatting & images
        if (url != null && url.startsWith("http")) {
            val html = fetchHtml(url)
            if (html != null) {
                val extracted = extractArticleFromHtml(html, item, url)
                if (extracted.bodyMarkdown.length >= 120) {
                    return extracted
                }
            }
        }

        // 6. Fallback to RSS fullContent if the network page couldn't be fetched
        val rssFull = item.fullContent?.trim()
        if (rssFull != null && rssFull.length >= 100) {
            val wc = countWords(rssFull)
            return ReadableArticle(
                title = item.aiHeadline ?: item.title,
                byline = item.subtitle ?: item.author,
                source = item.source,
                readingTimeMinutes = maxOf(1, wc / 220),
                wordCount = wc,
                heroImageUrl = item.coverUrl,
                bodyMarkdown = rssFull,
                bodyHtml = if (rssFull.contains("<p", true) || rssFull.contains("<img", true)) sanitizeRichArticleHtml(rssFull, url ?: "https://example.invalid/", item.coverUrl).ifBlank { markdownToHtml(Html.text(rssFull)) } else markdownToHtml(rssFull),
                originalUrl = url,
            )
        }

        return fallbackArticle(item)
    }

    private fun fetchHtml(url: String): String? {
        val plain = runCatching {
            val r = Http.request(
                "GET", url,
                mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "User-Agent" to Http.defaultUserAgent,
                ),
                timeoutMs = 12_000,
            )
            if (r.ok && !WebNovels.looksBlocked(r.code, r.body)) r.body else null
        }.getOrNull()
        if (plain != null && plain.length > 800) return plain
        return browser?.let { b -> runCatching { b(url) }.getOrNull() }
    }

    /** Fetches a Reddit thread's full self-text (and HTML), images, and top community comments. */
    private fun loadRedditThread(item: SffFeedItem, permalinkUrl: String): ReadableArticle? {
        val cleanUrl = permalinkUrl.substringBefore("?").trimEnd('/')
        val jsonUrl = "$cleanUrl.json?limit=30&raw_json=1"
        val r = runCatching {
            Http.request(
                "GET", jsonUrl,
                mapOf(
                    "Accept" to "application/json",
                    "User-Agent" to "TrackerSync/1.26 (Android; Speculative Fiction Reader)",
                ),
                timeoutMs = 12_000,
            )
        }.getOrNull() ?: return null
        if (!r.ok) return null
        val root = runCatching { JSONArray(r.body) }.getOrNull() ?: return null
        val postData = root.optJSONObject(0)?.obj("data")?.arr("children")?.optJSONObject(0)?.obj("data")
        val selftext = postData?.str("selftext")?.trim()?.takeIf { it.isNotEmpty() }
            ?: item.fullContent
            ?: item.summary
            ?: ""
        val selftextHtml = postData?.str("selftext_html")?.trim()?.takeIf { it.isNotEmpty() }
        val linkedUrl = postData?.str("url_overridden_by_dest")?.takeIf { !it.contains("reddit.com") && !it.endsWith(".jpg") && !it.endsWith(".png") }
        val postImage = postData?.obj("preview")?.arr("images")?.optJSONObject(0)?.obj("source")?.str("url")
            ?: postData?.str("url_overridden_by_dest")?.takeIf { it.endsWith(".jpg", true) || it.endsWith(".png", true) || it.endsWith(".webp", true) }
            ?: item.coverUrl
        val author = postData?.str("author")?.let { "u/$it" } ?: item.author
        val score = postData?.optInt("score", 0) ?: 0
        val flair = postData?.str("link_flair_text")?.takeIf { it.isNotBlank() && it != "null" }

        // If the Reddit post links out to an external SFF article, extract that article's rich HTML & Markdown too!
        val externalArticle = if (selftext.length < 240 && linkedUrl != null) {
            fetchHtml(linkedUrl)?.let { extractArticleFromHtml(it, item, linkedUrl).takeIf { ex -> ex.bodyMarkdown.length > 250 } }
        } else null

        val comments = ArrayList<CommentItem>()
        val commentChildren = root.optJSONObject(1)?.obj("data")?.arr("children")?.objects().orEmpty()
        for (cWrap in commentChildren) {
            val cd = cWrap.obj("data") ?: continue
            val cAuthor = cd.str("author")?.takeIf { it != "AutoModerator" && it != "[deleted]" } ?: continue
            val cBody = cd.str("body")?.trim()?.takeIf { it.isNotEmpty() && it != "[removed]" && it != "[deleted]" } ?: continue
            val cScore = cd.optInt("score", 0)
            comments += CommentItem(
                author = "u/$cAuthor",
                score = if (cScore != 0) "▲ $cScore" else null,
                body = redditSpoilersToMarkdown(cBody),
                depth = 0,
            )
            val replies = cd.optJSONObject("replies")?.obj("data")?.arr("children")?.objects().orEmpty()
            for (repWrap in replies.take(2)) {
                val rd = repWrap.obj("data") ?: continue
                val rAuthor = rd.str("author")?.takeIf { it != "[deleted]" } ?: continue
                val rBody = rd.str("body")?.trim()?.takeIf { it.isNotEmpty() && it != "[removed]" } ?: continue
                val rScore = rd.optInt("score", 0)
                comments += CommentItem(
                    author = "u/$rAuthor",
                    score = if (rScore != 0) "▲ $rScore" else null,
                    body = redditSpoilersToMarkdown(rBody),
                    depth = 1,
                )
            }
            if (comments.size >= 20) break
        }

        val combinedMd = buildString {
            if (selftext.isNotBlank()) appendLine(redditSpoilersToMarkdown(selftext))
            if (externalArticle != null) {
                if (isNotEmpty()) appendLine("\n\n")
                appendLine("## Linked Article")
                appendLine(externalArticle.bodyMarkdown)
            }
            if (isEmpty()) appendLine(item.title)
        }.trim()

        val combinedHtml = buildString {
            if (selftextHtml != null) {
                appendLine(sanitizeRichArticleHtml(selftextHtml, cleanUrl, postImage))
            } else if (selftext.isNotBlank()) {
                appendLine(markdownToHtml(redditSpoilersToMarkdown(selftext)))
            }
            if (externalArticle != null) {
                appendLine("<hr/><h2>Linked Article</h2>")
                appendLine(externalArticle.bodyHtml)
            }
        }.trim().ifEmpty { "<p>${Html.text(item.title)}</p>" }

        val wc = countWords(combinedMd) + comments.sumOf { countWords(it.body) }
        return ReadableArticle(
            title = item.aiHeadline ?: item.title,
            byline = listOfNotNull(item.source, author, flair, if (score > 0) "▲ $score" else null).joinToString(" · "),
            source = item.source,
            readingTimeMinutes = maxOf(1, wc / 220),
            wordCount = wc,
            heroImageUrl = postImage ?: externalArticle?.heroImageUrl,
            extraImages = externalArticle?.extraImages.orEmpty(),
            bodyMarkdown = combinedMd,
            bodyHtml = combinedHtml,
            comments = comments,
            originalUrl = cleanUrl,
        )
    }

    private fun redditSpoilersToMarkdown(s: String): String =
        s.replace(Regex(">!\\s*(.*?)\\s*!<", RegexOption.DOT_MATCHES_ALL)) { "||${it.groupValues[1]}||" }

    /** Fetches a Bluesky post's full text, embedded images/cards, and thread replies via AT Protocol. */
    private fun loadBlueskyThread(item: SffFeedItem, postUrl: String): ReadableArticle? {
        val m = Regex("bsky\\.app/profile/([^/]+)/post/([A-Za-z0-9]+)").find(postUrl) ?: return null
        val actor = m.groupValues[1]
        val rkey = m.groupValues[2]
        val atUri = "at://$actor/app.bsky.feed.post/$rkey"
        val apiUrl = "https://public.api.bsky.app/xrpc/app.bsky.feed.getPostThread?uri=${Http.enc(atUri)}&depth=4"
        val r = runCatching {
            Http.request("GET", apiUrl, mapOf("Accept" to "application/json", "User-Agent" to Http.defaultUserAgent), timeoutMs = 10_000)
        }.getOrNull() ?: return null
        if (!r.ok) return null
        val thread = runCatching { JSONObject(r.body).obj("thread") }.getOrNull() ?: return null
        val post = thread.obj("post") ?: return null
        val record = post.obj("record")
        val mainText = record?.str("text")?.trim() ?: item.summary ?: item.title
        val externalUri = post.obj("embed")?.obj("external")?.str("uri")
        val externalDesc = post.obj("embed")?.obj("external")?.str("description")
        val embedImages = post.obj("embed")?.arr("images")?.objects().orEmpty().mapNotNull { it.str("fullsize") ?: it.str("thumb") }

        val linkedArticle = if (externalUri != null && externalUri.startsWith("http")) {
            fetchHtml(externalUri)?.let { extractArticleFromHtml(it, item, externalUri).takeIf { ex -> ex.bodyMarkdown.length > 250 } }
        } else null

        val replies = ArrayList<CommentItem>()
        for (rep in thread.arr("replies")?.objects().orEmpty().take(12)) {
            val rp = rep.obj("post") ?: continue
            val rAuthor = rp.obj("author")?.let { it.str("displayName")?.takeIf { n -> n.isNotBlank() } ?: "@${it.str("handle")}" } ?: continue
            val rText = rp.obj("record")?.str("text")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val rLikes = rp.optInt("likeCount", 0)
            replies += CommentItem(
                author = rAuthor,
                score = if (rLikes > 0) "♥ $rLikes" else null,
                body = rText,
                depth = 0,
            )
        }

        val fullMd = buildString {
            appendLine(mainText)
            if (!externalDesc.isNullOrBlank() && linkedArticle == null) {
                appendLine()
                appendLine("> $externalDesc")
            }
            if (linkedArticle != null) {
                appendLine()
                appendLine("## Shared Article")
                appendLine(linkedArticle.bodyMarkdown)
            }
        }.trim()

        val fullHtml = buildString {
            appendLine(markdownToHtml(mainText))
            for (img in embedImages) {
                appendLine("<figure><img src=\"$img\" alt=\"Post image\"/></figure>")
            }
            if (!externalDesc.isNullOrBlank() && linkedArticle == null) {
                appendLine("<blockquote><p>${escapeHtml(externalDesc)}</p></blockquote>")
            }
            if (linkedArticle != null) {
                appendLine("<hr/><h2>Shared Article</h2>")
                appendLine(linkedArticle.bodyHtml)
            }
        }.trim()

        val wc = countWords(fullMd) + replies.sumOf { countWords(it.body) }
        return ReadableArticle(
            title = item.aiHeadline ?: item.title,
            byline = item.subtitle ?: item.author,
            source = item.source,
            readingTimeMinutes = maxOf(1, wc / 200),
            wordCount = wc,
            heroImageUrl = embedImages.firstOrNull() ?: linkedArticle?.heroImageUrl ?: item.coverUrl,
            extraImages = embedImages.drop(1) + linkedArticle?.extraImages.orEmpty(),
            bodyMarkdown = fullMd,
            bodyHtml = fullHtml,
            comments = replies,
            originalUrl = postUrl,
        )
    }

    /** Fetches a Mastodon status and its full thread replies (`ancestors` + `descendants`). */
    private fun loadMastodonThread(item: SffFeedItem, statusUrl: String): ReadableArticle? {
        val statusId = item.id.substringAfterLast("-").takeIf { it.all { ch -> ch.isDigit() } } ?: return null
        val host = statusUrl.substringAfter("://").substringBefore("/")
        val ctxUrl = "https://$host/api/v1/statuses/$statusId/context"
        val r = runCatching {
            Http.request("GET", ctxUrl, mapOf("Accept" to "application/json", "User-Agent" to Http.defaultUserAgent), timeoutMs = 10_000)
        }.getOrNull() ?: return null
        if (!r.ok) return null
        val ctx = runCatching { JSONObject(r.body) }.getOrNull() ?: return null
        val replies = ArrayList<CommentItem>()
        for (st in (ctx.arr("ancestors")?.objects().orEmpty() + ctx.arr("descendants")?.objects().orEmpty()).take(14)) {
            val txt = st.str("content")?.let { Html.text(it).trim() }?.takeIf { it.isNotEmpty() } ?: continue
            val acct = st.obj("account")
            val who = acct?.str("display_name")?.takeIf { it.isNotBlank() } ?: acct?.str("acct") ?: host
            val favs = st.optInt("favourites_count", 0)
            replies += CommentItem(
                author = who,
                score = if (favs > 0) "★ $favs" else null,
                body = txt,
                depth = 0,
            )
        }
        val body = item.fullContent ?: item.summary ?: item.title
        val wc = countWords(body) + replies.sumOf { countWords(it.body) }
        return ReadableArticle(
            title = item.aiHeadline ?: item.title,
            byline = item.subtitle ?: item.author,
            source = item.source,
            readingTimeMinutes = maxOf(1, wc / 200),
            wordCount = wc,
            heroImageUrl = item.coverUrl,
            bodyMarkdown = body,
            bodyHtml = markdownToHtml(body),
            comments = replies,
            originalUrl = statusUrl,
        )
    }

    /**
     * Readability-style HTML article extractor: strips ads, scripts, and popups while preserving
     * rich HTML formatting (`<h1>`–`<h4>`, `<p>`, `<blockquote>`, `<figure>`, `<img>`, `<figcaption>`,
     * `<table>`, `<ul>`, `<ol>`, `<strong>`, `<em>`, `<a>`) and hydrating lazy-loaded images.
     */
    internal fun extractArticleFromHtml(html: String, item: SffFeedItem, url: String): ReadableArticle {
        val ogImage = item.coverUrl ?: Regex("<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"'](https?://[^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let { Html.decode(it) }
        val ogAuthor = item.author ?: Regex("<meta[^>]+name=[\"']author[\"'][^>]+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let { Html.text(it).trim() }

        // 1. Strip scripts, styles, ad iframes, nav, footer, aside, and ad/clutter containers
        var cleaned = html
            .replace(Regex("(?is)<(script|style|noscript|svg|iframe|form|nav|footer|header|aside|ins)\\b[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
            .replace(
                Regex("(?is)<(div|section|aside|ul)\\b[^>]+(?:id|class)=[\"'][^\"']*(?:ad-slot|ad-wrapper|ad-unit|ad-container|dfp-ad|taboola|outbrain|mediavine|adthrive|newsletter|subscribe|social-share|share-buttons|related-posts|related-articles|cookie-banner|paywall|comments-area)[^\"']*[\"'][^>]*>.*?</\\1>"),
                " "
            )

        // 2. Locate the richest article container
        val containerPatterns = listOf(
            Regex("(?is)<div[^>]+class=[\"'][^\"']*(?:chapter-inner|chapter-content|entry-content|post-content|article-content|article-body|story-body|rich-text|prose)[^\"']*[\"'][^>]*>(.*?)</div>"),
            Regex("(?is)<article\\b[^>]*>(.*?)</article>"),
            Regex("(?is)<main\\b[^>]*>(.*?)</main>"),
        )
        val mainChunk = contentDiv(cleaned)?.takeIf { it.length > 400 }
            ?: containerPatterns.drop(1).firstNotNullOfOrNull { rx ->
                rx.findAll(cleaned).map { it.groupValues[1] }.maxByOrNull { it.length }?.takeIf { it.length > 400 }
            } ?: cleaned

        val richHtml = sanitizeRichArticleHtml(mainChunk, url, ogImage)
        val md = htmlToMarkdown(mainChunk, fallbackSummary = item.fullContent ?: item.summary)

        val extraImages = Regex("<img[^>]+src=[\"'](https?://[^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .findAll(richHtml)
            .map { Html.decode(it.groupValues[1]) }
            .filter { it != ogImage }
            .distinct()
            .take(8)
            .toList()

        val wc = countWords(md)
        return ReadableArticle(
            title = item.aiHeadline ?: item.title,
            byline = listOfNotNull(ogAuthor ?: item.subtitle, item.releaseDate).distinct().joinToString(" · ").ifEmpty { item.source },
            source = item.source,
            readingTimeMinutes = maxOf(1, wc / 220),
            wordCount = wc,
            heroImageUrl = ogImage ?: extraImages.firstOrNull(),
            extraImages = extraImages,
            bodyMarkdown = md,
            bodyHtml = richHtml.ifBlank { markdownToHtml(md) },
            originalUrl = url,
        )
    }

    private val CONTENT_DIV = Regex("(?is)<div[^>]+class=[\"'][^\"']*(?:chapter-inner|chapter-content|entry-content|post-content|article-content|article-body|story-body|rich-text|prose)[^\"']*[\"'][^>]*>")

    /** The richest content <div> with everything inside it, nested divs included (counted to its own closing tag). */
    internal fun contentDiv(html: String): String? = CONTENT_DIV.findAll(html).mapNotNull { open ->
        val tags = Regex("(?i)<div\\b|</div\\s*>").findAll(html, open.range.last + 1)
        var depth = 1
        var end = -1
        for (t in tags) {
            depth += if (t.value.startsWith("</")) -1 else 1
            if (depth == 0) { end = t.range.first; break }
        }
        html.substring(open.range.last + 1, if (end >= 0) end else html.length)
    }.maxByOrNull { it.length }

    /** An attribute's own value ("src", not the end of "data-src"). */
    private fun attrValue(attrs: String, name: String): String? =
        Regex("(?i)(?<![\\w-])" + Regex.escape(name) + "\\s*=\\s*[\"']([^\"']+)[\"']").find(attrs)?.groupValues?.get(1)?.let { Html.decode(it) }

    /** The largest picture a srcset offers ("a.jpg 400w, b.jpg 1200w" -> b.jpg). */
    internal fun largestInSrcset(set: String): String? = set.split(Regex(",\\s+(?=\\S)")).mapNotNull { part ->
        val bits = part.trim().split(Regex("\\s+"))
        val url = bits.firstOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("data:") } ?: return@mapNotNull null
        val size = bits.getOrNull(1)?.let { d -> d.dropLast(1).toDoubleOrNull()?.let { if (d.endsWith("x")) it * 1000 else it } } ?: 1.0
        url to size
    }.maxByOrNull { it.second }?.first

    /** A tiny blurred stand-in a page shows while the real picture loads (or a tracking pixel). */
    internal fun looksLikePlaceholder(url: String): Boolean {
        val u = url.lowercase()
        return u.startsWith("data:") || "placeholder" in u || "lqip" in u || "blank." in u || "1x1" in u || "pixel" in u ||
            Regex("(?:e_blur|[?&]blur=|/blur/)").containsMatchIn(u) ||
            Regex("(?:[?&](?:w|width)=|[,/]w_)(\\d{1,2})(?!\\d)").containsMatchIn(u)
    }

    /** The same picture at full size, where its address says how small to make it (w_40, ?width=40, e_blur). */
    internal fun fullSize(url: String): String = url
        .replace(Regex("(?i),e_blur(?::\\d+)?"), "")
        .replace(Regex("(?i)e_blur(?::\\d+)?,?"), "")
        .replace(Regex("(?i)([,/])w_(\\d{1,2})(?!\\d)"), "$1w_1200")
        .replace(Regex("(?i)([?&](?:w|width)=)(\\d{1,2})(?!\\d)"), "$11200")
        .replace(Regex("(?i)([?&]blur=)\\d+"), "$10")

    /**
     * The picture an <img> really shows: the largest from its srcset (lazy or not), else its lazy
     * source, else its src; never a blurred stand-in when there's a real one, and a stand-in that's
     * the only choice asked for at full size.
     */
    internal fun bestImage(attrs: String): String? {
        val fromSets = listOf("data-srcset", "data-lazy-srcset", "srcset").mapNotNull { attrValue(attrs, it)?.let { set -> largestInSrcset(set) } }
        val singles = listOf("data-lazy-src", "data-src", "data-original", "data-actualsrc", "data-hi-res-src", "src").mapNotNull { attrValue(attrs, it) }
        val all = (fromSets + singles).filter { !it.startsWith("data:") }
        return all.firstOrNull { !looksLikePlaceholder(it) } ?: all.firstOrNull()?.let { fullSize(it) }?.takeIf { !looksLikePlaceholder(it) }
    }

    /** Avatars, icons, logos and ad images (as whole words in the path, so "uploads" or "silicon" pass). */
    private val JUNK_IMAGE = Regex("(?:^|[/._-])(?:avatars?|icons?|logos?|ads?|adserver|doubleclick|sprite|gravatar)(?:[/._-]|$)")

    /** Resolves a potentially relative image/link URL against the article's [baseUrl]. */
    private fun resolveUrl(raw: String, baseUrl: String): String? {
        val s = Html.decode(raw.trim())
        if (s.isEmpty() || s.startsWith("data:") || s.startsWith("javascript:")) return null
        if (s.startsWith("//")) return "https:$s"
        if (s.startsWith("http://")) return "https://" + s.removePrefix("http://")
        if (s.startsWith("https://")) return s
        return runCatching { URI(baseUrl).resolve(s).toString() }.getOrNull()
    }

    /**
     * Produces clean, ad-free rich HTML from [rawHtml] that retains all semantic formatting
     * (`<h2>`–`<h4>`, `<p>`, `<blockquote>`, `<figure>`, `<img>`, `<figcaption>`, `<ul>`, `<ol>`,
     * `<li>`, `<table>`, `<pre>`, `<code>`, `<strong>`, `<em>`, `<a href>`) and hydrates lazy-loaded
     * images (`data-src`, `data-lazy-src`, `data-original`, `srcset`).
     */
    internal fun sanitizeRichArticleHtml(rawHtml: String, baseUrl: String, heroImageUrl: String? = null): String {
        // 1. Hydrate lazy-loaded images into standard <img src="..." alt="..."> tags
        var html = Regex("(?is)<img\\b([^>]*)/?>").replace(rawHtml) { m ->
            val attrs = m.groupValues[1]
            val srcCandidate = bestImage(attrs) ?: return@replace ""
            val resolved = resolveUrl(srcCandidate, baseUrl) ?: return@replace ""
            val lower = resolved.lowercase()
            if (JUNK_IMAGE.containsMatchIn(lower) || lower.endsWith(".svg")) {
                return@replace ""
            }
            val alt = Regex("\\balt=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(1)?.let { escapeHtml(Html.text(it)) } ?: ""
            "<img src=\"$resolved\" alt=\"$alt\" loading=\"eager\" onerror=\"this.style.display='none'\"/>"
        }

        // 2. Clean <a> tags to keep only safe href
        html = Regex("(?is)<a\\b([^>]*)>(.*?)</a>").replace(html) { m ->
            val attrs = m.groupValues[1]
            val inner = m.groupValues[2]
            val href = Regex("\\bhref=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(1)
                ?.let { resolveUrl(it, baseUrl) }
            if (href != null) "<a href=\"$href\">$inner</a>" else inner
        }

        // 3. Extract structured rich blocks in document order
        val tokenRegex = Regex(
            "(?is)<(figure|h[1-4]|p|blockquote|ul|ol|table|pre|hr)\\b[^>]*>(.*?)</\\1>|<img\\b[^>]+/>"
        )
        val out = ArrayList<String>()
        val seenImages = HashSet<String>()
        heroImageUrl?.let { seenImages += it }

        for (m in tokenRegex.findAll(html)) {
            val whole = m.value
            if (whole.startsWith("<img", ignoreCase = true)) {
                val src = Regex("src=\"([^\"]+)\"").find(whole)?.groupValues?.get(1)
                if (src != null && seenImages.add(src)) {
                    out += "<figure>$whole</figure>"
                }
                continue
            }
            val tag = m.groupValues[1].lowercase()
            val inner = m.groupValues[2]
            when (tag) {
                "figure" -> {
                    val imgMatch = Regex("<img\\b[^>]+/>", RegexOption.IGNORE_CASE).find(inner)?.value ?: continue
                    val src = Regex("src=\"([^\"]+)\"").find(imgMatch)?.groupValues?.get(1) ?: continue
                    if (!seenImages.add(src)) continue
                    val cap = Regex("(?is)<figcaption\\b[^>]*>(.*?)</figcaption>").find(inner)?.groupValues?.get(1)
                        ?.let { Html.text(it).trim() }?.takeIf { it.isNotEmpty() }
                    out += if (cap != null) "<figure>$imgMatch<figcaption>${escapeHtml(cap)}</figcaption></figure>" else "<figure>$imgMatch</figure>"
                }
                "h1", "h2", "h3", "h4" -> {
                    val t = cleanInlineHtml(inner).trim()
                    if (Html.text(t).length >= 3) {
                        val hTag = if (tag == "h1") "h2" else tag
                        out += "<$hTag>$t</$hTag>"
                    }
                }
                "blockquote" -> {
                    val t = cleanInlineHtml(inner).trim()
                    if (Html.text(t).length >= 12) out += "<blockquote><p>$t</p></blockquote>"
                }
                "ul", "ol" -> {
                    val items = Regex("(?is)<li\\b[^>]*>(.*?)</li>").findAll(inner)
                        .mapNotNull { li -> cleanInlineHtml(li.groupValues[1]).trim().takeIf { Html.text(it).length >= 4 } }
                        .toList()
                    if (items.isNotEmpty()) {
                        out += "<$tag>" + items.joinToString("") { "<li>$it</li>" } + "</$tag>"
                    }
                }
                "table" -> {
                    val rows = Regex("(?is)<tr\\b[^>]*>(.*?)</tr>").findAll(inner).mapNotNull { tr ->
                        val cells = Regex("(?is)<(th|td)\\b[^>]*>(.*?)</\\1>").findAll(tr.groupValues[1]).map { c ->
                            val cTag = c.groupValues[1].lowercase()
                            "<$cTag>${cleanInlineHtml(c.groupValues[2]).trim()}</$cTag>"
                        }.toList()
                        if (cells.isEmpty()) null else "<tr>${cells.joinToString("")}</tr>"
                    }.toList()
                    if (rows.isNotEmpty()) out += "<div class=\"table-wrap\"><table>${rows.joinToString("")}</table></div>"
                }
                "pre" -> {
                    val code = escapeHtml(Html.text(inner).trim())
                    if (code.isNotEmpty()) out += "<pre><code>$code</code></pre>"
                }
                "p" -> {
                    // Check if paragraph contains an inline <img>
                    val inlineImg = Regex("<img\\b[^>]+/>", RegexOption.IGNORE_CASE).find(inner)?.value
                    if (inlineImg != null) {
                        val src = Regex("src=\"([^\"]+)\"").find(inlineImg)?.groupValues?.get(1)
                        if (src != null && seenImages.add(src)) out += "<figure>$inlineImg</figure>"
                    }
                    val t = cleanInlineHtml(inner.replace(Regex("<img\\b[^>]+/>", RegexOption.IGNORE_CASE), "")).trim()
                    val plain = Html.text(t).trim()
                    if (plain.length >= 16 && !isBoilerplateLine(plain)) {
                        out += "<p>$t</p>"
                    }
                }
            }
            if (out.size >= 120) break
        }
        return out.joinToString("\n")
    }

    /** Retains only safe inline formatting tags (`<strong>`, `<b>`, `<em>`, `<i>`, `<code>`, `<a href>`, `<br>`). */
    private fun cleanInlineHtml(raw: String): String {
        var s = raw
            .replace(Regex("(?is)<(script|style|svg|iframe|button|input)\\b[^>]*>.*?</\\1>"), "")
            .replace(Regex("(?is)<(?!/?(strong|b|em|i|code|a|br)\\b)[^>]+>"), "")
            // The formatting tags kept, without their attributes (no scripts riding along on them).
            .replace(Regex("(?i)<(strong|b|em|i|code|br)\\b[^>]*>")) { "<${it.groupValues[1].lowercase()}>" }
        // Handle Reddit/Markdown spoiler syntax ||...|| or >! ... !<
        s = s.replace(Regex("\\|\\|([^|]+)\\|\\|")) { "<span class=\"spoiler\" onclick=\"this.classList.toggle('revealed')\">${it.groupValues[1]}</span>" }
        return s.replace(Regex("\\s+"), " ").trim()
    }

    /** Converts Markdown text into rich HTML for the Artifact Reader WebView. */
    internal fun markdownToHtml(md: String): String {
        // Text that is really HTML (a feed's own markup, kept from before) is cleaned, not shown as tags.
        if (Regex("(?i)<(p|div|br|a|img|h[1-6]|ul|ol|blockquote)\\b|<!--").containsMatchIn(md)) {
            val clean = sanitizeRichArticleHtml(md.replace(Regex("(?s)<!--.*?-->"), ""), "https://example.invalid/")
            if (clean.isNotBlank()) return clean
        }
        val paras = md.split(Regex("\\n\\s*\\n"))
        return paras.mapNotNull { block ->
            val b = block.trim()
            if (b.isEmpty()) return@mapNotNull null
            when {
                b.startsWith("### ") -> "<h3>${formatInlineMd(b.removePrefix("### "))}</h3>"
                b.startsWith("## ") -> "<h2>${formatInlineMd(b.removePrefix("## "))}</h2>"
                b.startsWith("# ") -> "<h2>${formatInlineMd(b.removePrefix("# "))}</h2>"
                b.startsWith("> ") -> "<blockquote><p>${formatInlineMd(b.lines().joinToString(" ") { it.removePrefix("> ").trim() })}</p></blockquote>"
                b.lines().all { it.trim().startsWith("• ") || it.trim().startsWith("- ") } -> {
                    val lis = b.lines().map { it.trim().removePrefix("• ").removePrefix("- ").trim() }.filter { it.isNotEmpty() }
                    "<ul>" + lis.joinToString("") { "<li>${formatInlineMd(it)}</li>" } + "</ul>"
                }
                else -> "<p>${formatInlineMd(b.replace("\n", "<br/>"))}</p>"
            }
        }.joinToString("\n")
    }

    private fun formatInlineMd(s: String): String {
        var out = escapeHtml(s)
        out = out.replace(Regex("\\*\\*([^*]+)\\*\\*")) { "<strong>${it.groupValues[1]}</strong>" }
        out = out.replace(Regex("\\*([^*]+)\\*")) { "<em>${it.groupValues[1]}</em>" }
        out = out.replace(Regex("\\|\\|([^|]+)\\|\\|")) { "<span class=\"spoiler\" onclick=\"this.classList.toggle('revealed')\">${it.groupValues[1]}</span>" }
        return out
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * The story as Artifact showed one: the article on a card over the page, its lead image
     * full-width across the card's top, the publisher's badge and name, a big bold headline (with
     * "rewritten" and the original when the title was de-clickbaited), the AI summary in its own
     * tinted card ("generated on this phone"), the text in a comfortable serif with its pictures,
     * captions, tables and tap-to-reveal spoilers, and each reply on a card of its own below.
     * Cards ease in as the page opens. Colours follow the app's theme, light or dark.
     */
    fun buildArtifactReaderDocument(
        article: ReadableArticle,
        item: SffFeedItem,
        aiBullets: List<String>,
        bgHex: String,
        surfaceHex: String,
        textHex: String,
        textMidHex: String,
        quietHex: String,
        accentHex: String,
        accentTintHex: String,
        borderHex: String,
        aiBulletsFromModel: Boolean = false,
    ): String {
        val rewritten = item.aiHeadline?.takeIf { it.isNotBlank() && !it.equals(item.title, ignoreCase = true) }
        val titleHtml = escapeHtml(rewritten ?: article.title)
        val publisher = (article.source.ifBlank { item.source }).substringBefore(" ·").trim()
        val badge = publisher.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"
        val meta = listOfNotNull(
            "${article.readingTimeMinutes} min read",
            article.comments.size.takeIf { it > 0 }?.let { "$it replies" },
        ).joinToString(" · ")
        val byline = article.byline?.takeIf { it.isNotBlank() && !it.equals(publisher, ignoreCase = true) }
        val hero = article.heroImageUrl ?: item.coverUrl

        val mediaHtml = when {
            article.youtubeVideoId != null -> """<div class="media video"><iframe src="https://www.youtube.com/embed/${article.youtubeVideoId}?playsinline=1" frameborder="0" allowfullscreen></iframe></div>"""
            !hero.isNullOrBlank() -> """<div class="media"><img src="${escapeAttr(hero)}" alt="" onerror="this.parentElement.style.display='none'"/></div>"""
            else -> ""
        }
        val rewrittenHtml = if (rewritten != null) {
            """<div class="rewritten"><span class="spark">✨</span> Title rewritten to say what it's about · <span class="orig">Original: “${escapeHtml(item.title)}”</span></div>"""
        } else ""
        val summaryHtml = if (aiBullets.isNotEmpty()) {
            """
            <section class="summary rise" style="animation-delay:120ms">
                <div class="summary-label"><span class="spark">✨</span> Summary</div>
                <ul>${aiBullets.joinToString("") { "<li>${escapeHtml(it)}</li>" }}</ul>
                <div class="summary-note">${if (aiBulletsFromModel) "Generated by AI on this phone. Check the article for details." else "Picked from the article's key sentences."}</div>
            </section>
            """.trimIndent()
        } else ""
        val commentsHtml = if (article.comments.isNotEmpty()) {
            buildString {
                append("""<div class="section-title rise" style="animation-delay:160ms">Discussion <span>${article.comments.size}</span></div>""")
                for ((i, cm) in article.comments.withIndex()) {
                    val delay = 180 + minOf(i, 10) * 30
                    append(
                        """
                        <section class="card comment rise${if (cm.depth > 0) " reply" else ""}" style="animation-delay:${delay}ms">
                            <div class="comment-meta">
                                <span class="avatar">${escapeHtml(cm.author.removePrefix("u/").removePrefix("@").take(1).uppercase())}</span>
                                <span class="comment-author">${escapeHtml(cm.author)}</span>
                                ${cm.score?.let { "<span class=\"comment-score\">${escapeHtml(it)}</span>" } ?: ""}
                            </div>
                            <div class="comment-body">${formatInlineMd(cm.body).replace("\n", "<br/>")}</div>
                        </section>
                        """.trimIndent()
                    )
                }
            }
        } else ""

        val sans = "-apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, \"Helvetica Neue\", sans-serif"
        val serif = "\"Noto Serif\", Georgia, \"Times New Roman\", serif"
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8"/>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0"/>
                <style>
                    * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
                    html, body { margin: 0; padding: 0; background: $bgHex; color: $textHex; word-wrap: break-word; }
                    body { font-family: $serif; font-size: 18px; line-height: 1.7; }
                    .feed { max-width: 720px; margin: 0 auto; padding: 12px 12px 56px 12px; }
                    .card {
                        background: $surfaceHex;
                        border: 1px solid $borderHex;
                        border-radius: 20px;
                        box-shadow: 0 1px 2px rgba(0,0,0,.06), 0 8px 24px rgba(0,0,0,.06);
                        overflow: hidden;
                        margin-bottom: 12px;
                    }
                    .rise { opacity: 0; transform: translateY(14px); animation: rise .42s cubic-bezier(.2,.8,.2,1) forwards; }
                    @keyframes rise { to { opacity: 1; transform: none; } }
                    @media (prefers-reduced-motion: reduce) { .rise { animation: none; opacity: 1; transform: none; } }

                    .media { width: 100%; aspect-ratio: 16 / 9; background: $borderHex; overflow: hidden; }
                    .media img { width: 100%; height: 100%; object-fit: cover; display: block; border-radius: 0; margin: 0; }
                    .media.video { position: relative; background: #000; }
                    .media.video iframe { position: absolute; inset: 0; width: 100%; height: 100%; }
                    .pad { padding: 18px 20px 22px 20px; }

                    .publisher { display: flex; align-items: center; gap: 8px; font-family: $sans; font-size: 13px; color: $quietHex; margin-bottom: 10px; }
                    .pub-badge { width: 22px; height: 22px; border-radius: 6px; background: $textHex; color: $surfaceHex; display: inline-flex; align-items: center; justify-content: center; font-weight: 800; font-size: 12px; }
                    .pub-name { font-weight: 700; color: $textHex; letter-spacing: .01em; }
                    h1.headline { font-family: $sans; font-size: 27px; line-height: 1.22; font-weight: 800; letter-spacing: -.02em; margin: 0 0 8px 0; color: $textHex; }
                    .rewritten { font-family: $sans; font-size: 12.5px; line-height: 1.45; color: $quietHex; margin: 0 0 8px 0; }
                    .rewritten .orig { font-style: italic; }
                    .spark { color: $accentHex; }
                    .byline { font-family: $sans; font-size: 13.5px; color: $textMidHex; margin-bottom: 4px; }

                    .summary { background: $accentTintHex; border-radius: 16px; margin: 16px 0 4px 0; padding: 14px 16px; font-family: $sans; font-size: 15.5px; line-height: 1.55; }
                    .summary-label { font-weight: 800; font-size: 13px; letter-spacing: .04em; text-transform: uppercase; color: $accentHex; margin-bottom: 6px; }
                    .summary ul { margin: 0; padding-left: 18px; }
                    .summary li { margin: 0 0 6px 0; color: $textHex; }
                    .summary-note { margin-top: 8px; font-size: 12px; color: $textMidHex; opacity: .85; }

                    .body { margin-top: 16px; }
                    .body p { margin: 0 0 18px 0; }
                    .body h2, .body h3, .body h4 { font-family: $sans; color: $textHex; line-height: 1.3; letter-spacing: -.01em; margin: 28px 0 10px 0; }
                    .body h2 { font-size: 21px; font-weight: 800; }
                    .body h3 { font-size: 18.5px; font-weight: 700; }
                    .body figure { margin: 22px -20px; }
                    .body figure img { width: 100%; height: auto; display: block; background: $borderHex; }
                    .body figcaption { font-family: $sans; font-size: 13px; color: $quietHex; padding: 8px 20px 0 20px; line-height: 1.45; }
                    .body blockquote { margin: 22px 0; padding: 4px 0 4px 16px; border-left: 3px solid $accentHex; color: $textMidHex; font-size: 19px; font-style: italic; }
                    .body blockquote p { margin: 0; }
                    .body ul, .body ol { margin: 0 0 18px 0; padding-left: 22px; }
                    .body li { margin-bottom: 8px; }
                    .body a { color: $accentHex; text-decoration: none; border-bottom: 1px solid $accentTintHex; }
                    .body hr { border: none; border-top: 1px solid $borderHex; margin: 26px 0; }
                    .table-wrap { overflow-x: auto; margin: 18px 0; border-radius: 12px; border: 1px solid $borderHex; }
                    table { width: 100%; border-collapse: collapse; font-family: $sans; font-size: 14px; }
                    th, td { padding: 8px 12px; border-bottom: 1px solid $borderHex; text-align: left; }
                    th { background: $bgHex; font-weight: 700; }
                    pre { background: $bgHex; border-radius: 12px; padding: 12px; overflow-x: auto; font-size: 13px; }

                    .spoiler { background: $textHex; color: transparent; border-radius: 4px; padding: 0 4px; cursor: pointer; transition: all .2s ease; }
                    .spoiler.revealed { background: $accentTintHex; color: $textHex; }

                    .section-title { font-family: $sans; font-weight: 800; font-size: 17px; color: $textHex; margin: 22px 6px 10px 6px; }
                    .section-title span { color: $quietHex; font-weight: 600; margin-left: 4px; }
                    .comment { padding: 12px 16px 14px 16px; font-family: $sans; font-size: 15px; line-height: 1.55; border-radius: 16px; margin-bottom: 8px; }
                    .comment.reply { margin-left: 22px; }
                    .comment-meta { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; font-size: 13px; }
                    .avatar { width: 22px; height: 22px; border-radius: 50%; background: $accentTintHex; color: $accentHex; display: inline-flex; align-items: center; justify-content: center; font-weight: 800; font-size: 11px; }
                    .comment-author { font-weight: 700; color: $textHex; }
                    .comment-score { color: $quietHex; margin-left: auto; }
                </style>
            </head>
            <body>
                <main class="feed">
                    <article class="card rise">
                        $mediaHtml
                        <div class="pad">
                            <div class="publisher"><span class="pub-badge">${escapeHtml(badge)}</span><span class="pub-name">${escapeHtml(publisher)}</span><span>·</span><span>${escapeHtml(meta)}</span></div>
                            <h1 class="headline">$titleHtml</h1>
                            $rewrittenHtml
                            ${byline?.let { "<div class=\"byline\">${escapeHtml(it)}</div>" } ?: ""}
                            $summaryHtml
                            <div class="body">
                                ${article.bodyHtml}
                            </div>
                        </div>
                    </article>
                    $commentsHtml
                </main>
            </body>
            </html>
        """.trimIndent()
    }

    private fun escapeAttr(s: String): String = escapeHtml(s).replace("\"", "&quot;")

    /** Converts HTML blocks (`<h2>`, `<p>`, `<blockquote>`, `<li>`) into clean Markdown for AI prompts. */
    internal fun htmlToMarkdown(rawHtml: String, fallbackSummary: String? = null): String {
        val blockRegex = Regex("(?is)<(h[1-4]|p|blockquote|li)\\b[^>]*>(.*?)</\\1>")
        val blocks = ArrayList<String>()
        for (m in blockRegex.findAll(rawHtml)) {
            val tag = m.groupValues[1].lowercase()
            val inner = m.groupValues[2]
                .replace(Regex("(?is)<(strong|b)\\b[^>]*>(.*?)</\\1>")) { "**${Html.text(it.groupValues[2]).trim()}**" }
                .replace(Regex("(?is)<(em|i)\\b[^>]*>(.*?)</\\1>")) { "*${Html.text(it.groupValues[2]).trim()}*" }
                .replace(Regex("(?is)<br\\s*/?>"), "\n")
            val text = Html.text(inner).replace(Regex("[ \\t]+"), " ").trim()
            if (text.length < 18 && !tag.startsWith("h")) continue
            if (isBoilerplateLine(text)) continue
            when {
                tag == "h1" || tag == "h2" -> blocks += "## $text"
                tag == "h3" || tag == "h4" -> blocks += "### $text"
                tag == "blockquote" -> blocks += "> $text"
                tag == "li" -> blocks += "• $text"
                else -> blocks += text
            }
            if (blocks.size >= 85) break
        }
        val joined = blocks.distinct().joinToString("\n\n").trim()
        if (joined.length >= 160) return joined
        val plain = Html.text(rawHtml.replace(Regex("(?i)</p>|<br\\s*/?>"), "\n\n"))
            .lines()
            .map { it.trim() }
            .filter { it.length >= 25 && !isBoilerplateLine(it) }
            .take(50)
            .joinToString("\n\n")
        return plain.takeIf { it.length >= 80 } ?: fallbackSummary.orEmpty()
    }

    private fun isBoilerplateLine(line: String): Boolean {
        val l = line.lowercase()
        return l.startsWith("subscribe to ") ||
            l.startsWith("sign up for ") ||
            l.startsWith("share this ") ||
            l.startsWith("follow us on ") ||
            l.startsWith("copyright ©") ||
            l.startsWith("all rights reserved") ||
            l.contains("cookie policy") ||
            l.contains("privacy policy")
    }

    private fun fallbackArticle(item: SffFeedItem): ReadableArticle {
        val body = buildString {
            val main = item.fullContent?.takeIf { it.isNotBlank() } ?: item.summary?.takeIf { it.isNotBlank() }
            if (main != null) appendLine(main)
            if (item.series != null || item.pages != null || item.rating != null || item.releaseDate != null) {
                if (isNotEmpty()) appendLine()
                appendLine("### Book & Release Details")
                item.author?.let { appendLine("• **Author:** $it") }
                item.series?.let { s -> appendLine("• **Series:** $s" + (item.seriesNumber?.let { " · Book $it" } ?: "")) }
                item.rating?.let { r -> appendLine("• **Community Rating:** ${"%.1f".format(java.util.Locale.US, r)} ★" + (item.ratingsCount?.let { " ($it ratings)" } ?: "")) }
                item.pages?.let { p -> appendLine("• **Length:** $p print pages") }
                item.releaseDate?.let { rd -> appendLine("• **Release Date:** $rd") }
            }
            if (isEmpty()) appendLine(item.title)
        }.trim()
        val wc = countWords(body)
        return ReadableArticle(
            title = item.aiHeadline ?: item.title,
            byline = item.subtitle ?: item.author ?: item.source,
            source = item.source,
            readingTimeMinutes = maxOf(1, wc / 200),
            wordCount = wc,
            heroImageUrl = item.coverUrl,
            bodyMarkdown = body,
            bodyHtml = markdownToHtml(body),
            originalUrl = item.url,
        )
    }

    private fun countWords(s: String): Int =
        Regex("[\\p{L}\\p{N}']+").findAll(s).count()
}