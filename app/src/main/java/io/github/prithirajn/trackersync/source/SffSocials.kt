package io.github.prithirajn.trackersync.source

import io.github.prithirajn.trackersync.data.SffFeedItem
import io.github.prithirajn.trackersync.net.Http
import io.github.prithirajn.trackersync.util.Html
import io.github.prithirajn.trackersync.util.arr
import io.github.prithirajn.trackersync.util.int
import io.github.prithirajn.trackersync.util.long
import io.github.prithirajn.trackersync.util.obj
import io.github.prithirajn.trackersync.util.objects
import io.github.prithirajn.trackersync.util.str
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Speculative-fiction social pulse across X (Twitter), Instagram, Bluesky (#BookSky),
 * Mastodon (wandering.shop SFF server & mastodon.social), Threads, YouTube (BookTube & SFF
 * author channels), Tumblr, and SFF Author Substacks.
 *
 * Uses official public endpoints where available (Bluesky AT Protocol, Mastodon v1 REST,
 * YouTube Atom XML, X Syndication JSON, Instagram Web Profile / Embed HTML) and falls back
 * to the app's unseen WebView ([browser], installed by PageBrowser) when a site challenges
 * plain HTTP requests.
 */
object SffSocials {

    /** Set by [io.github.prithirajn.trackersync.ui.PageBrowser.install] to load pages in the unseen WebView. */
    @Volatile var browser: ((String) -> String?)? = null

    /** Default SFF publishers, imprints, and authors on X (Twitter). */
    val defaultXHandles = listOf(
        "torbooks" to "Tor Books / Reactor",
        "orbitbooks" to "Orbit Books",
        "delreybooks" to "Del Rey SFF",
        "AngryRobotBooks" to "Angry Robot SFF",
        "SubPress" to "Subterranean Press",
        "BrandSanderson" to "Brandon Sanderson",
        "scalzi" to "John Scalzi",
        "mattdinniman" to "Matt Dinniman",
        "WillWight" to "Will Wight",
    )

    /** Default SFF publishers, special-edition boxes, and authors on Instagram (#bookstagram). */
    val defaultInstaHandles = listOf(
        "torbooks" to "Tor Books",
        "orbitbooks" to "Orbit Books",
        "delreybooks" to "Del Rey Books",
        "dragonsteelbooks" to "Dragonsteel (Sanderson)",
        "thebrokenbinding" to "The Broken Binding SFF",
        "illumicrate" to "Illumicrate SFF",
    )

    /** Default SFF authors, critics, and imprints on Bluesky (#BookSky). */
    val defaultBlueskyActors = listOf(
        "scalzi.com" to "John Scalzi",
        "marthawells.com" to "Martha Wells",
        "reactormag.com" to "Reactor Mag",
        "jeffvandermeer.bsky.social" to "Jeff VanderMeer",
        "travisbaldree.bsky.social" to "Travis Baldree",
    )

    /** Default SFF BookTube & Author YouTube channels (channel_id to display name). */
    val defaultYouTubeChannels = listOf(
        "UC3g-w83Cb5pEAu5UmRrge-A" to "Brandon Sanderson",
        "UCwBV72JU29M4G1w5gC-nC3w" to "Daniel Greene",
        "UC1rFmaGLYr0Ve_Y_soxZNWQ" to "Quinn's Ideas",
        "UC6c0da11bf8f5G2n-lM2RBA" to "Petrik Leo",
        "UC7FW6FYqPLeQIXMSulBfOLw" to "Merphy Napier",
    )

    /** Default SFF Author Substacks & Tumblr blogs. */
    val defaultSubstackAndTumblr = listOf(
        SffSources.RssSource("Substack · Jeff VanderMeer", "https://jeffvandermeer.substack.com/feed", "SOCIALS", listOf("Weird Fiction", "Author Dispatch")),
        SffSources.RssSource("Substack · Counter Craft", "https://countercraft.substack.com/feed", "SOCIALS", listOf("Speculative Craft", "SFF")),
        SffSources.RssSource("Tumblr · Reactor SFF", "https://reactormag.tumblr.com/rss", "SOCIALS", listOf("Tumblr SFF", "Reactor")),
    )

    // ---- 1. X (Twitter) ----

    private val nitterMirrors = listOf(
        "https://xcancel.com",
        "https://nitter.poast.org",
        "https://nitter.privacydev.net",
    )

    private val xDateFmt = SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.US)

    /**
     * Fetches recent posts from an X (Twitter) handle using:
     * 1. Twitter's public Syndication Profile Timeline (`syndication.twitter.com/srv/timeline-profile/screen-name/<handle>`)
     * 2. Nitter RSS mirrors (`xcancel.com`, `nitter.poast.org`, `nitter.privacydev.net`)
     * 3. Unseen WebView fallback ([browser]) if plain HTTP is challenged.
     */
    fun fetchXHandle(handleRaw: String, label: String? = null, limit: Int = 4, now: Long = System.currentTimeMillis() / 1000): List<SffFeedItem> {
        val handle = handleRaw.trim().removePrefix("@").substringBefore("/")
        if (handle.isEmpty()) return emptyList()
        val display = label ?: "@$handle"

        // 1. Try X Syndication Timeline endpoint
        val syndUrl = "https://syndication.twitter.com/srv/timeline-profile/screen-name/${Http.enc(handle)}"
        val syndHtml = runCatching {
            val r = Http.request(
                "GET", syndUrl,
                mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/json",
                    "User-Agent" to Http.defaultUserAgent,
                ),
                timeoutMs = 10_000,
            )
            if (r.ok && r.body.contains("__NEXT_DATA__")) r.body else null
        }.getOrNull() ?: browser?.let { b -> runCatching { b(syndUrl) }.getOrNull()?.takeIf { it.contains("__NEXT_DATA__") } }

        if (syndHtml != null) {
            val parsed = parseXSyndication(syndHtml, handle, display, limit, now)
            if (parsed.isNotEmpty()) return parsed
        }

        // 2. Fallback to Nitter RSS mirrors
        for (mirror in nitterMirrors) {
            val rssUrl = "$mirror/${Http.enc(handle)}/rss"
            val items = SffSources.fetchRss(
                SffSources.RssSource("X · @$handle", rssUrl, "SOCIALS", listOf("X / Twitter", display)),
                limit = limit,
                now = now,
            )
            if (items.isNotEmpty()) {
                return items.map { it.copy(url = it.url?.replace(mirror, "https://x.com")) }
            }
        }
        return emptyList()
    }

    internal fun parseXSyndication(html: String, handle: String, display: String, limit: Int, now: Long): List<SffFeedItem> {
        val jsonStr = Regex("<script[^>]+id=[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(html)?.groupValues?.get(1) ?: return emptyList()
        val entries = runCatching {
            JSONObject(jsonStr).obj("props")?.obj("pageProps")?.obj("timeline")?.arr("entries")?.objects()
        }.getOrNull() ?: return emptyList()

        val out = ArrayList<SffFeedItem>()
        for (e in entries) {
            val tw = e.obj("content")?.obj("tweet") ?: continue
            val id = tw.str("id_str") ?: tw.opt("id")?.toString() ?: continue
            val fullText = (tw.str("full_text") ?: tw.str("text") ?: continue)
                .let { Html.text(it).replace(Regex("https://t\\.co/\\S+"), "").trim() }
            if (fullText.length < 12) continue
            val likes = tw.optInt("favorite_count", 0)
            val rts = tw.optInt("retweet_count", 0)
            val created = tw.str("created_at")?.let { runCatching { xDateFmt.parse(it)?.time?.div(1000) }.getOrNull() } ?: now
            val media = tw.obj("entities")?.arr("media")?.optJSONObject(0)?.str("media_url_https")
                ?: tw.obj("extended_entities")?.arr("media")?.optJSONObject(0)?.str("media_url_https")
            val firstLine = fullText.lineSequence().firstOrNull()?.take(96) ?: fullText.take(96)
            out.add(
                SffFeedItem(
                    id = "x-$handle-$id",
                    category = "SOCIALS",
                    source = "X · @$handle",
                    title = if (firstLine.length < fullText.length) "$firstLine…" else firstLine,
                    subtitle = listOfNotNull(
                        display,
                        if (likes > 0) "♥ $likes" else null,
                        if (rts > 0) "↻ $rts" else null,
                    ).joinToString(" · "),
                    summary = fullText.take(360),
                    url = "https://x.com/$handle/status/$id",
                    coverUrl = media,
                    author = display,
                    tags = listOf("X / Twitter", "@$handle"),
                    publishedAt = created,
                )
            )
            if (out.size >= limit) break
        }
        return out
    }

    // ---- 2. Instagram (#bookstagram & SFF Publishers/Authors) ----

    /**
     * Fetches recent public Instagram posts for [handleRaw] using:
     * 1. Instagram's public Web Profile info JSON endpoint (`i.instagram.com/api/v1/users/web_profile_info/?username=...`)
     * 2. Instagram's public `/embed/` HTML page (`www.instagram.com/<handle>/embed/`)
     * 3. Unseen WebView fallback ([browser]) when Instagram requests JS execution.
     */
    fun fetchInstagramHandle(handleRaw: String, label: String? = null, limit: Int = 4, now: Long = System.currentTimeMillis() / 1000): List<SffFeedItem> {
        val handle = handleRaw.trim().removePrefix("@").substringBefore("/")
        if (handle.isEmpty()) return emptyList()
        val display = label ?: "@$handle"

        // 1. Public web_profile_info JSON API
        val apiUrl = "https://www.instagram.com/api/v1/users/web_profile_info/?username=${Http.enc(handle)}"
        val apiBody = runCatching {
            val r = Http.request(
                "GET", apiUrl,
                mapOf(
                    "Accept" to "application/json",
                    "X-IG-App-ID" to "936619743392459",
                    "Referer" to "https://www.instagram.com/$handle/",
                    "User-Agent" to Http.defaultUserAgent,
                ),
                timeoutMs = 10_000,
            )
            if (r.ok && r.body.trim().startsWith("{")) r.body else null
        }.getOrNull()

        if (apiBody != null) {
            val parsed = parseInstagramProfileJson(apiBody, handle, display, limit, now)
            if (parsed.isNotEmpty()) return parsed
        }

        // 2. Public /embed/ page (plain HTTP first, then unseen WebView)
        val embedUrl = "https://www.instagram.com/${Http.enc(handle)}/embed/"
        val embedHtml = runCatching {
            val r = Http.request(
                "GET", embedUrl,
                mapOf("Accept" to "text/html", "User-Agent" to Http.defaultUserAgent),
                timeoutMs = 10_000,
            )
            if (r.ok) r.body else null
        }.getOrNull() ?: browser?.let { b -> runCatching { b(embedUrl) }.getOrNull() }

        if (embedHtml != null) {
            val parsed = parseInstagramEmbedHtml(embedHtml, handle, display, limit, now)
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    internal fun parseInstagramProfileJson(jsonStr: String, handle: String, display: String, limit: Int, now: Long): List<SffFeedItem> {
        val user = runCatching { JSONObject(jsonStr).obj("data")?.obj("user") }.getOrNull() ?: return emptyList()
        val edges = user.obj("edge_owner_to_timeline_media")?.arr("edges")?.objects() ?: return emptyList()
        val out = ArrayList<SffFeedItem>()
        for (edge in edges) {
            val n = edge.obj("node") ?: continue
            val shortcode = n.str("shortcode") ?: continue
            val caption = n.obj("edge_media_to_caption")?.arr("edges")?.optJSONObject(0)?.obj("node")?.str("text")
                ?.replace(Regex("\\s+"), " ")?.trim()
                ?: "New post from $display on Instagram"
            val likes = n.obj("edge_liked_by")?.optInt("count", 0) ?: n.obj("edge_media_preview_like")?.optInt("count", 0) ?: 0
            val thumb = n.str("thumbnail_src") ?: n.str("display_url")
            val ts = n.long("taken_at_timestamp") ?: now
            val title = caption.take(92).let { if (it.length < caption.length) "$it…" else it }
            out.add(
                SffFeedItem(
                    id = "ig-$handle-$shortcode",
                    category = "SOCIALS",
                    source = "Instagram · @$handle",
                    title = title,
                    subtitle = listOfNotNull(display, if (likes > 0) "♥ $likes" else null).joinToString(" · "),
                    summary = caption.take(340),
                    url = "https://www.instagram.com/p/$shortcode/",
                    coverUrl = thumb,
                    author = display,
                    tags = listOf("Instagram", "#bookstagram", "@$handle"),
                    publishedAt = ts,
                )
            )
            if (out.size >= limit) break
        }
        return out
    }

    internal fun parseInstagramEmbedHtml(html: String, handle: String, display: String, limit: Int, now: Long): List<SffFeedItem> {
        val unescaped = html.replace("\\\"", "\"").replace("\\/", "/")
        val shortcodes = Regex("/p/([A-Za-z0-9_-]{8,14})/").findAll(unescaped).map { it.groupValues[1] }.distinct().take(limit).toList()
        if (shortcodes.isEmpty()) return emptyList()
        val captions = Regex("\"caption\"\\s*:\\s*\"([^\"]{10,400})\"").findAll(unescaped).map { Html.text(it.groupValues[1]) }.toList()
        val images = Regex("\"(?:display_url|thumbnail_src)\"\\s*:\\s*\"(https://[^\"]+)\"").findAll(unescaped)
            .map { it.groupValues[1].replace("\\u0026", "&") }.distinct().toList()
        return shortcodes.mapIndexed { idx, code ->
            val cap = captions.getOrNull(idx)?.trim() ?: "Post from $display on Instagram (@$handle)"
            val img = images.getOrNull(idx)
            SffFeedItem(
                id = "ig-emb-$handle-$code",
                category = "SOCIALS",
                source = "Instagram · @$handle",
                title = cap.take(90).let { if (it.length < cap.length) "$it…" else it },
                subtitle = "$display · Instagram",
                summary = cap.take(320),
                url = "https://www.instagram.com/p/$code/",
                coverUrl = img,
                author = display,
                tags = listOf("Instagram", "@$handle"),
                publishedAt = now - idx * 600L,
            )
        }
    }

    // ---- 3. Bluesky (#BookSky & SFF Author Feeds via Public AT Protocol API) ----

    private const val BSKY_API = "https://public.api.bsky.app/xrpc"
    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    /** Searches public Bluesky posts (`app.bsky.feed.searchPosts`) for SFF hashtags, authors, or series. */
    fun fetchBlueskySearch(query: String, limit: Int = 6, now: Long = System.currentTimeMillis() / 1000): List<SffFeedItem> {
        val url = "$BSKY_API/app.bsky.feed.searchPosts?q=${Http.enc(query)}&sort=top&limit=$limit"
        val posts = runCatching {
            val r = Http.request("GET", url, mapOf("Accept" to "application/json", "User-Agent" to Http.defaultUserAgent), timeoutMs = 10_000)
            if (r.ok) JSONObject(r.body).arr("posts")?.objects() else null
        }.getOrNull() ?: return emptyList()
        return posts.mapNotNull { parseBlueskyPost(it, query, now) }
    }

    /** Fetches an SFF author or publisher's public Bluesky feed (`app.bsky.feed.getAuthorFeed`). */
    fun fetchBlueskyActor(actor: String, label: String? = null, limit: Int = 4, now: Long = System.currentTimeMillis() / 1000): List<SffFeedItem> {
        val clean = actor.trim().removePrefix("@")
        val url = "$BSKY_API/app.bsky.feed.getAuthorFeed?actor=${Http.enc(clean)}&filter=posts_no_replies&limit=$limit"
        val feed = runCatching {
            val r = Http.request("GET", url, mapOf("Accept" to "application/json", "User-Agent" to Http.defaultUserAgent), timeoutMs = 10_000)
            if (r.ok) JSONObject(r.body).arr("feed")?.objects() else null
        }.getOrNull() ?: return emptyList()
        return feed.mapNotNull { it.obj("post")?.let { p -> parseBlueskyPost(p, label ?: clean, now) } }
    }

    private fun parseBlueskyPost(post: JSONObject, tagContext: String, now: Long): SffFeedItem? {
        val uri = post.str("uri") ?: return null
        val rkey = uri.substringAfterLast("/")
        val authorObj = post.obj("author") ?: return null
        val handle = authorObj.str("handle") ?: return null
        val displayName = authorObj.str("displayName")?.takeIf { it.isNotBlank() } ?: "@$handle"
        val record = post.obj("record") ?: return null
        val text = record.str("text")?.trim()?.takeIf { it.length >= 12 } ?: return null
        val likes = post.optInt("likeCount", 0)
        val reposts = post.optInt("repostCount", 0)
        val replies = post.optInt("replyCount", 0)
        val created = record.str("createdAt")?.take(19)?.let { runCatching { isoFmt.parse(it)?.time?.div(1000) }.getOrNull() } ?: now
        val thumb = post.obj("embed")?.let { emb ->
            emb.arr("images")?.optJSONObject(0)?.str("thumb")
                ?: emb.obj("external")?.str("thumb")
                ?: emb.obj("media")?.arr("images")?.optJSONObject(0)?.str("thumb")
        } ?: authorObj.str("avatar")
        val firstLine = text.lineSequence().firstOrNull()?.take(92) ?: text.take(92)
        return SffFeedItem(
            id = "bsky-$handle-$rkey",
            category = "SOCIALS",
            source = "Bluesky · @$handle",
            title = if (firstLine.length < text.length) "$firstLine…" else firstLine,
            subtitle = listOfNotNull(
                displayName,
                if (likes > 0) "♥ $likes" else null,
                if (reposts > 0) "↻ $reposts" else null,
                if (replies > 0) "💬 $replies" else null,
            ).joinToString(" · "),
            summary = text.take(360),
            url = "https://bsky.app/profile/$handle/post/$rkey",
            coverUrl = thumb,
            author = displayName,
            tags = listOf("Bluesky", "#BookSky", tagContext).distinct().take(4),
            publishedAt = created,
        )
    }

    // ---- 4. Mastodon / Fediverse (wandering.shop SFF server & mastodon.social) ----

    /**
     * Fetches public hashtag timelines from `wandering.shop` (the flagship speculative-fiction
     * author and reader Mastodon server) or `mastodon.social`.
     */
    fun fetchMastodonTag(instance: String = "wandering.shop", tag: String = "sff", limit: Int = 6, now: Long = System.currentTimeMillis() / 1000): List<SffFeedItem> {
        val cleanTag = tag.trim().removePrefix("#")
        val url = "https://$instance/api/v1/timelines/tag/${Http.enc(cleanTag)}?limit=$limit"
        val arr = runCatching {
            val r = Http.request("GET", url, mapOf("Accept" to "application/json", "User-Agent" to Http.defaultUserAgent), timeoutMs = 10_000)
            if (r.ok) JSONArray(r.body).objects() else null
        }.getOrNull() ?: return emptyList()

        return arr.mapNotNull { st ->
            val id = st.str("id") ?: return@mapNotNull null
            val contentHtml = st.str("content") ?: return@mapNotNull null
            val text = Html.text(contentHtml).replace(Regex("\\s+"), " ").trim().takeIf { it.length >= 16 } ?: return@mapNotNull null
            val acct = st.obj("account")
            val username = acct?.str("display_name")?.takeIf { it.isNotBlank() } ?: acct?.str("username") ?: instance
            val handle = acct?.str("acct") ?: ""
            val favs = st.optInt("favourites_count", 0)
            val boosts = st.optInt("reblogs_count", 0)
            val media = st.arr("media_attachments")?.optJSONObject(0)?.let { it.str("preview_url") ?: it.str("url") }
            val created = st.str("created_at")?.take(19)?.let { runCatching { isoFmt.parse(it)?.time?.div(1000) }.getOrNull() } ?: now
            val postUrl = st.str("url") ?: "https://$instance/tags/$cleanTag"
            SffFeedItem(
                id = "masto-$instance-$id",
                category = "SOCIALS",
                source = "Mastodon · $instance",
                title = text.take(92).let { if (it.length < text.length) "$it…" else it },
                subtitle = listOfNotNull(
                    "$username (@$handle)",
                    if (favs > 0) "★ $favs" else null,
                    if (boosts > 0) "↻ $boosts" else null,
                ).joinToString(" · "),
                summary = text.take(360),
                url = postUrl,
                coverUrl = media,
                author = username,
                tags = listOf("Mastodon", "#$cleanTag", instance),
                publishedAt = created,
            )
        }
    }

    // ---- 5. YouTube / BookTube SFF & Author Channels (Official Public Atom XML) ----

    /**
     * Fetches the latest videos from an SFF BookTuber or Author YouTube channel via YouTube's
     * official public Atom feed (`youtube.com/feeds/videos.xml?channel_id=...`).
     */
    fun fetchYouTubeChannel(channelId: String, channelName: String, limit: Int = 3, now: Long = System.currentTimeMillis() / 1000): List<SffFeedItem> {
        val url = "https://www.youtube.com/feeds/videos.xml?channel_id=${Http.enc(channelId.trim())}"
        val xml = runCatching {
            val r = Http.request("GET", url, mapOf("Accept" to "application/atom+xml, text/xml", "User-Agent" to Http.defaultUserAgent), timeoutMs = 10_000)
            if (r.ok) r.body else null
        }.getOrNull() ?: return emptyList()

        val entries = Regex("<entry>(.*?)</entry>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(xml).map { it.groupValues[1] }.take(limit).toList()

        return entries.mapNotNull { chunk ->
            val videoId = Regex("<yt:videoId>([^<]+)</yt:videoId>").find(chunk)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            val title = Regex("<title>([^<]+)</title>").find(chunk)?.groupValues?.get(1)?.let { Html.text(it).trim() } ?: return@mapNotNull null
            val desc = Regex("<media:description>(.*?)</media:description>", RegexOption.DOT_MATCHES_ALL).find(chunk)?.groupValues?.get(1)
                ?.let { Html.text(it).replace(Regex("\\s+"), " ").trim() }?.take(280)
            val views = Regex("views=[\"'](\\d+)[\"']").find(chunk)?.groupValues?.get(1)?.toIntOrNull()
            val pub = Regex("<published>([^<]+)</published>").find(chunk)?.groupValues?.get(1)?.take(19)
                ?.let { runCatching { isoFmt.parse(it)?.time?.div(1000) }.getOrNull() } ?: now
            SffFeedItem(
                id = "yt-$videoId",
                category = "SOCIALS",
                source = "YouTube · $channelName",
                title = title,
                subtitle = listOfNotNull(
                    channelName,
                    views?.let { "%,d views".format(it) },
                ).joinToString(" · "),
                summary = desc,
                url = "https://www.youtube.com/watch?v=$videoId",
                coverUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                author = channelName,
                tags = listOf("YouTube", "BookTube SFF", channelName),
                publishedAt = pub,
            )
        }
    }

    // ---- 6. Threads (`threads.net/@<handle>`) ----

    /** Fetches public post previews from a Threads profile (`threads.net/@<handle>`). */
    fun fetchThreadsHandle(handleRaw: String, label: String? = null, now: Long = System.currentTimeMillis() / 1000): List<SffFeedItem> {
        val handle = handleRaw.trim().removePrefix("@").substringBefore("/")
        if (handle.isEmpty()) return emptyList()
        val display = label ?: "@$handle"
        val url = "https://www.threads.net/@${Http.enc(handle)}"
        val html = runCatching {
            val r = Http.request("GET", url, mapOf("Accept" to "text/html", "User-Agent" to Http.defaultUserAgent), timeoutMs = 10_000)
            if (r.ok) r.body else null
        }.getOrNull() ?: return emptyList()

        val unescaped = html.replace("\\\"", "\"").replace("\\/", "/")
        val texts = Regex("\"text\"\\s*:\\s*\"([^\"]{20,360})\"").findAll(unescaped)
            .map { Html.text(it.groupValues[1]).trim() }
            .filter { !it.startsWith("http") && !it.contains("Followers") }
            .distinct()
            .take(2)
            .toList()
        return texts.mapIndexed { idx, txt ->
            SffFeedItem(
                id = "threads-$handle-${txt.hashCode().toUInt().toString(16)}",
                category = "SOCIALS",
                source = "Threads · @$handle",
                title = txt.take(90).let { if (it.length < txt.length) "$it…" else it },
                subtitle = "$display · Threads",
                summary = txt,
                url = url,
                author = display,
                tags = listOf("Threads", "@$handle"),
                publishedAt = now - idx * 900L,
            )
        }
    }

    /**
     * Collects a balanced wave of SFF social posts across X, Instagram, Bluesky, Mastodon
     * (wandering.shop & mastodon.social), YouTube BookTube, Threads, Tumblr, and Author Substacks,
     * plus any custom handles configured by the user (`x:handle`, `insta:handle`, `bsky:handle`,
     * `mastodon:tag`, `yt:channelId`) and dynamic searches for the user's top library authors!
     */
    fun fetchAllSocials(
        topAuthors: List<String>,
        customHandles: List<String>,
        windowSeed: Int,
        now: Long = System.currentTimeMillis() / 1000,
    ): List<SffFeedItem> {
        val out = ArrayList<SffFeedItem>()

        // 1. Parse user custom handles first so their explicitly followed accounts always appear
        for (raw in customHandles) {
            val clean = raw.trim()
            val prefix = clean.substringBefore(":", "").lowercase()
            val target = clean.substringAfter(":", clean).trim().removePrefix("@")
            if (target.isEmpty()) continue
            when (prefix) {
                "x", "twitter" -> out += fetchXHandle(target, "@$target", limit = 3, now = now)
                "ig", "insta", "instagram" -> out += fetchInstagramHandle(target, "@$target", limit = 3, now = now)
                "bsky", "bluesky" -> {
                    if (target.startsWith("#")) out += fetchBlueskySearch(target, limit = 4, now = now)
                    else out += fetchBlueskyActor(if (target.contains(".")) target else "$target.bsky.social", "@$target", limit = 3, now = now)
                }
                "mastodon", "masto", "tag" -> out += fetchMastodonTag("wandering.shop", target, limit = 4, now = now)
                "threads" -> out += fetchThreadsHandle(target, "@$target", now = now)
                "yt", "youtube" -> out += fetchYouTubeChannel(target, target, limit = 3, now = now)
                else -> {
                    // Bare @handle: try X, Bluesky search, and Instagram
                    out += fetchXHandle(target, "@$target", limit = 2, now = now)
                    out += fetchInstagramHandle(target, "@$target", limit = 2, now = now)
                }
            }
        }

        // 2. X (Twitter) — rotate through 3 SFF publisher/author handles per 3-hour window
        val xStart = (windowSeed * 2) % defaultXHandles.size
        for (i in 0 until 3) {
            val (h, label) = defaultXHandles[(xStart + i) % defaultXHandles.size]
            out += fetchXHandle(h, label, limit = 3, now = now)
        }

        // 3. Instagram (#bookstagram & SFF Publishers/Special Editions) — rotate 2 handles per window
        val igStart = (windowSeed * 2) % defaultInstaHandles.size
        for (i in 0 until 2) {
            val (h, label) = defaultInstaHandles[(igStart + i) % defaultInstaHandles.size]
            out += fetchInstagramHandle(h, label, limit = 3, now = now)
        }

        // 4. Bluesky (#BookSky SFF tags + Author feeds + Dynamic search for reader's own library authors!)
        val bskyQueries = listOf(
            "#BookSky science fiction fantasy",
            "#ProgressionFantasy OR #LitRPG",
            "speculative fiction book review",
            "#SciFiBooks OR #FantasyBooks",
            "space opera OR grimdark",
            "weird fiction OR cosmic horror",
        )
        out += fetchBlueskySearch(bskyQueries[windowSeed % bskyQueries.size], limit = 6, now = now)
        for (author in topAuthors.take(2)) {
            out += fetchBlueskySearch("\"$author\"", limit = 3, now = now)
        }
        val (bskyActor, bskyName) = defaultBlueskyActors[windowSeed % defaultBlueskyActors.size]
        out += fetchBlueskyActor(bskyActor, bskyName, limit = 3, now = now)

        // 5. Mastodon (wandering.shop SFF community + mastodon.social)
        val mastoTags = listOf("sff", "scifi", "fantasy", "bookstodon", "weirdfiction", "litrpg")
        out += fetchMastodonTag("wandering.shop", mastoTags[windowSeed % mastoTags.size], limit = 5, now = now)
        out += fetchMastodonTag("mastodon.social", "scifi", limit = 4, now = now)

        // 6. YouTube (BookTube SFF & Brandon Sanderson updates)
        val ytStart = windowSeed % defaultYouTubeChannels.size
        for (i in 0 until 3) {
            val (chId, chName) = defaultYouTubeChannels[(ytStart + i) % defaultYouTubeChannels.size]
            out += fetchYouTubeChannel(chId, chName, limit = 2, now = now)
        }

        // 7. SFF Substacks, Tumblr & Threads
        for (rss in defaultSubstackAndTumblr) {
            out += SffSources.fetchRss(rss, limit = 3, now = now)
        }
        out += fetchThreadsHandle("torbooks", "Tor Books", now = now)

        return out
    }
}
