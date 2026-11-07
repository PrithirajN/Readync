package io.github.prithirajn.trackersync.source

import io.github.prithirajn.trackersync.data.WebNovel
import io.github.prithirajn.trackersync.net.Http
import io.github.prithirajn.trackersync.sync.Matcher
import io.github.prithirajn.trackersync.trackers.RemoteBook
import io.github.prithirajn.trackersync.util.Html
import io.github.prithirajn.trackersync.util.arr
import io.github.prithirajn.trackersync.util.int
import io.github.prithirajn.trackersync.util.obj
import io.github.prithirajn.trackersync.util.objects
import io.github.prithirajn.trackersync.util.str
import io.github.prithirajn.trackersync.util.strings
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Web novels and fan fiction: the details of a story on Royal Road, AO3 (Archive of Our Own),
 * Scribble Hub, Wattpad and similar sites, read from the story's own public page, for books no
 * catalogue knows. A story is found from its link (pasted once) or by searching Royal Road and
 * AO3 by title. Pages are the site's own estimate, else the words at about 250 a page.
 */
object WebNovels {
    var royalRoad = "https://www.royalroad.com"
    var ao3 = "https://archiveofourown.org"
    var wattpad = "https://www.wattpad.com"
    var scribbleHub = "https://www.scribblehub.com"

    const val WORDS_PER_PAGE = 250

    // Sites that are fan fiction archives (an AO3 work in "Original Work" isn't fan fiction).
    private val fanficSites = setOf("AO3", "FanFiction.Net", "SpaceBattles", "Sufficient Velocity")

    /** The site a story link is on ("Royal Road"), or null for a link that isn't a story site this app knows. */
    fun site(url: String): String? {
        val u = url.lowercase()
        return when {
            "royalroad.com" in u -> "Royal Road"
            "archiveofourown.org" in u || "ao3.org" in u -> "AO3"
            "scribblehub.com" in u -> "Scribble Hub"
            "wattpad.com" in u -> "Wattpad"
            "webnovel.com" in u -> "Webnovel"
            "fanfiction.net" in u -> "FanFiction.Net"
            "spacebattles.com" in u -> "SpaceBattles"
            "sufficientvelocity.com" in u -> "Sufficient Velocity"
            "tapas.io" in u -> "Tapas"
            else -> null
        }
    }

    /** The story's own address, cleaned: "royalroad.com/fiction/21220/x/chapter/…" -> its fiction page. */
    fun canonical(url: String): String? {
        val u = url.trim().let { if (it.startsWith("http")) it else "https://$it" }
        Regex("royalroad\\.com/fiction/(\\d+)(/[\\w-]+)?").find(u)?.let { return "https://www.royalroad.com/fiction/${it.groupValues[1]}${it.groupValues[2]}" }
        Regex("(?:archiveofourown|ao3)\\.org/(?:collections/[^/]+/)?works/(\\d+)").find(u)?.let { return "https://archiveofourown.org/works/${it.groupValues[1]}" }
        Regex("wattpad\\.com/story/(\\d+)").find(u)?.let { return "https://www.wattpad.com/story/${it.groupValues[1]}" }
        Regex("scribblehub\\.com/series/(\\d+)(/[\\w-]+)?").find(u)?.let { return "https://www.scribblehub.com/series/${it.groupValues[1]}${it.groupValues[2]}/" }
        return if (site(u) != null) u.substringBefore("#") else null
    }

    /**
     * Opens a page in a real browser (set by the app): sites behind a bot check (Cloudflare's
     * "Just a moment…") turn plain requests away but let a browser through after a moment.
     */
    @Volatile var browser: ((String) -> String?)? = null

    /** Why each site came back empty last time ("Royal Road" -> "its bot check stopped the app"). */
    val problems = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** A bot-check page rather than the site itself. */
    internal fun looksBlocked(code: Int, body: String): Boolean =
        code == 403 || code == 503 || code == 429 ||
            body.contains("<title>Just a moment", ignoreCase = true) ||
            body.contains("cf-chl-", ignoreCase = true) || body.contains("challenge-platform", ignoreCase = true)

    /** Sites that didn't answer at all in this sync: not tried again until the next one. */
    val downThisRun: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** A new sync: every site gets a fresh try. */
    fun newRun() = downThisRun.clear()

    private fun get(url: String): String? {
        val name = when {
            url.startsWith(royalRoad) -> "Royal Road"
            url.startsWith(ao3) -> "AO3"
            url.startsWith(scribbleHub) -> "Scribble Hub"
            url.startsWith(wattpad) -> "Wattpad"
            else -> site(url) ?: url.substringAfter("://").substringBefore('/')
        }
        if (name in downThisRun) return null
        val r = runCatching {
            Http.request("GET", url, mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/json",
                "Accept-Language" to "en-US,en;q=0.9",
                "User-Agent" to Http.defaultUserAgent,
            ), timeoutMs = 12_000)
        }.getOrNull()
        if (r != null && r.ok && !looksBlocked(r.code, r.body)) { problems.remove(name); return r.body }
        // A site that didn't answer at all (unreachable, blocked by the network) isn't tried in the
        // browser either, nor again this sync: that only made syncs wait minutes.
        if (r == null) { downThisRun += name; problems[name] = "didn't answer"; return null }
        // Stopped by a bot check: the app's own browser usually gets through.
        val viaBrowser = browser?.let { b -> runCatching { b(url) }.getOrNull() }
            ?.takeIf { it.isNotBlank() && !looksBlocked(200, it) }
        if (viaBrowser != null) { problems.remove(name); return viaBrowser }
        problems[name] = when {
            r == null -> "didn't answer"
            looksBlocked(r.code, r.body) -> "its bot check stopped the app"
            else -> "answered HTTP ${r.code}"
        }
        return null
    }

    /** The story at [url], read from its page; null when it can't be read. */
    fun fetch(url: String): BookDetails? {
        val link = canonical(url) ?: return null
        val site = site(link) ?: return null
        return runCatching {
            when (site) {
                "Royal Road" -> {
                    val id = Regex("/fiction/(\\d+)").find(link)!!.groupValues[1]
                    get("$royalRoad/fiction/$id")?.let { parseRoyalRoad(it, link) }
                }
                "AO3" -> {
                    val id = Regex("/works/(\\d+)").find(link)!!.groupValues[1]
                    // Without view_adult, a Mature or Explicit work answers with a warning page.
                    get("$ao3/works/$id?view_adult=true&view_full_work=false")?.let { parseAo3(it, link) }
                }
                "Wattpad" -> {
                    val id = Regex("/story/(\\d+)").find(link)!!.groupValues[1]
                    (get("$wattpad/api/v3/stories/$id?fields=id,title,user(name),description,cover,completed,numParts,tags,language(name),mature,createDate")
                        ?.let { runCatching { JSONObject(it) }.getOrNull() })?.let { parseWattpad(it, link) }
                }
                else -> get(link)?.let { parseGeneric(it, link, site) }
            }
        }.getOrNull()
    }

    /** A story found by searching: where, its title and author, its page, and how well it matches. */
    data class Found(val site: String, val title: String, val author: String?, val url: String, val score: Double, val clear: Boolean)

    /**
     * Searches Royal Road, AO3, Scribble Hub and Wattpad for a story with this title (and author),
     * every site, and ranks what each found by how well it matches; the best first. A site that
     * doesn't answer is skipped.
     */
    fun candidates(title: String, author: String?): List<Found> {
        val plain = Matcher.plainTitle(title)
        val sites = listOf(
            "Royal Road" to { searchRoyalRoad(plain, author) },
            "AO3" to { searchAo3(plain, author) },
            "Scribble Hub" to { searchScribbleHub(plain, author) },
            "Wattpad" to { searchWattpad(plain, author) },
        )
        val out = ArrayList<Found>()
        for ((site, find) in sites) {
            val hits = runCatching { find() }.getOrNull() ?: continue
            for ((rawTitle, a, url) in hits.take(10)) {
                // Sites add tags to titles ("The Doomsday Reaver (A LitRPG Adventure)"): compared without them.
                val t = Matcher.plainTitle(rawTitle).ifBlank { rawTitle }
                val score = Matcher.score(plain, author, RemoteBook("", t, a))
                // The same author spelt as a site handle ("icohalliday" for "Ico Halliday") still counts.
                val sameAuthor = author != null && a != null && squash(author) == squash(a)
                val clear = Matcher.sameBook(plain, author, t, a) || score >= 0.85 || (sameAuthor && Matcher.score(plain, null, RemoteBook("", t, null)) >= 0.85)
                if (score >= 0.5 || clear) out += Found(site, rawTitle, a, url, if (sameAuthor) maxOf(score, 0.95) else score, clear)
            }
        }
        return out.distinctBy { it.url }.sortedWith(compareByDescending<Found> { it.clear }.thenByDescending { it.score })
    }

    /**
     * The story, when one site has a clear match: the best across all four sites, read from its
     * page, and only if the page's author doesn't contradict the one you have.
     */
    fun search(title: String, author: String?): BookDetails? {
        val plain = Matcher.plainTitle(title)
        for (f in candidates(title, author).filter { it.clear }.take(3)) {
            fetch(f.url)?.let { d ->
                if (author == null || d.author == null || squash(author) == squash(d.author ?: "") || Matcher.sameBook(plain, author, d.title, d.author) || Matcher.score(plain, author, RemoteBook("", d.title, d.author)) >= 0.7) return d
            }
        }
        return null
    }

    /** "Ico Halliday" and "icohalliday" alike. */
    private fun squash(name: String) = name.filter { it.isLetterOrDigit() }.lowercase()

    /** (title, author or null, link) for each result on Royal Road's search. */
    internal fun searchRoyalRoad(title: String, author: String?): List<Triple<String, String?, String>> {
        // The author filter wants Royal Road's own spelling of the name, which a book file may not
        // have ("icohalliday" for "Ico Halliday"): without hits, search by title alone.
        fun find(withAuthor: Boolean): List<Triple<String, String?, String>> {
            val q = "$royalRoad/fictions/search?globalFilters=false&title=${Http.enc(title)}" +
                (if (withAuthor && author != null) "&author=${Http.enc(author)}" else "")
            return get(q)?.let { parseRoyalRoadSearch(it) } ?: emptyList()
        }
        return find(true).ifEmpty { if (author != null) find(false) else emptyList() }
    }

    internal fun parseRoyalRoadSearch(html: String): List<Triple<String, String?, String>> =
        Regex("<h2[^>]*class=\"[^\"]*fiction-title[^\"]*\"[^>]*>\\s*<a[^>]*href=\"(/fiction/\\d+[^\"]*)\"[^>]*>(.*?)</a>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(html).map { m -> Triple(Html.text(m.groupValues[2]), null, "https://www.royalroad.com" + m.groupValues[1]) }.toList()
            // Newer layouts: any fiction link with a title inside a heading.
            .ifEmpty {
                Regex("<h[23456][^>]*>\\s*<a[^>]*href=\"(/fiction/\\d+/[^\"/#?]*)\"[^>]*>(.*?)</a>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                    .findAll(html).map { m -> Triple(Html.text(m.groupValues[2]), null, "https://www.royalroad.com" + m.groupValues[1]) }
                    .filter { it.first.isNotBlank() }.distinctBy { it.third }.toList()
            }

    internal fun searchAo3(title: String, author: String?): List<Triple<String, String?, String>> {
        val q = "$ao3/works/search?work_search%5Btitle%5D=${Http.enc(title)}" + (author?.let { "&work_search%5Bcreators%5D=${Http.enc(it)}" } ?: "")
        return parseAo3Search(get(q) ?: return emptyList())
    }

    internal fun parseAo3Search(html: String): List<Triple<String, String?, String>> =
        Regex("<h4[^>]*class=\"[^\"]*heading[^\"]*\"[^>]*>(.*?)</h4>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(html).mapNotNull { m ->
            val h = m.groupValues[1]
            val work = Regex("<a[^>]*href=\"(/works/\\d+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(h) ?: return@mapNotNull null
            val by = Regex("<a[^>]*rel=\"author\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(h)?.let { Html.text(it.groupValues[1]) }
            Triple(Html.text(work.groupValues[2]), by, "https://archiveofourown.org" + work.groupValues[1])
        }.toList()

    /** (title, author, link) for each story on Scribble Hub's search. */
    internal fun searchScribbleHub(title: String, author: String?): List<Triple<String, String?, String>> {
        val q = "$scribbleHub/?s=${Http.enc(title)}&post_type=fictionposts"
        return parseScribbleHubSearch(get(q) ?: return emptyList())
    }

    /**
     * Read by the story links themselves (/series/123/slug/) rather than the page's styling, so a
     * redesign doesn't break it; the author is the first profile link after each title.
     */
    internal fun parseScribbleHubSearch(html: String): List<Triple<String, String?, String>> {
        val link = Regex("<a[^>]*href=\"(https?://(?:www\\.)?scribblehub\\.com/series/(\\d+)/[^\"/]+/?)\"[^>]*>(.*?)</a>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val seen = HashSet<String>()
        val out = ArrayList<Triple<String, String?, String>>()
        for (m in link.findAll(html)) {
            val id = m.groupValues[2]
            val text = Html.text(m.groupValues[3])
            if (text.isBlank() || text.length > 200 || !seen.add(id)) continue
            val after = html.substring(m.range.last, minOf(html.length, m.range.last + 2500))
            val by = Regex("<a[^>]*href=\"https?://(?:www\\.)?scribblehub\\.com/profile/\\d+/[^\"]*\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(after)
                ?.let { Html.text(it.groupValues[1]) }?.takeIf { it.isNotBlank() }
            out += Triple(text, by, canonical(m.groupValues[1]) ?: m.groupValues[1])
        }
        return out
    }

    /** (title, author, link) for each story on Wattpad's search. */
    internal fun searchWattpad(title: String, author: String?): List<Triple<String, String?, String>> {
        val q = "$wattpad/v4/search/stories?query=${Http.enc(title)}&limit=15&fields=stories(id,title,user(name,username),url)"
        return parseWattpadSearch(get(q) ?: return emptyList())
    }

    internal fun parseWattpadSearch(json: String): List<Triple<String, String?, String>> {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val arr = o.optJSONArray("stories") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val st = arr.optJSONObject(i) ?: return@mapNotNull null
            val t = st.optString("title").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val id = st.opt("id")?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val user = st.optJSONObject("user")
            val by = (user?.optString("name")?.takeIf { it.isNotBlank() } ?: user?.optString("username"))?.takeIf { it.isNotBlank() }
            Triple(t, by, "https://www.wattpad.com/story/$id")
        }
    }

    // ---- Royal Road ----

    internal fun parseRoyalRoad(html: String, url: String): BookDetails? {
        val ld = ldBook(html)
        val title = ld?.str("name") ?: meta(html, "og:title") ?: tagText(html, "h1") ?: return null
        val author = (ld?.opt("author") as? JSONObject)?.str("name")
            ?: (ld?.opt("author") as? JSONArray)?.optJSONObject(0)?.str("name")
            ?: meta(html, "books:author")
            ?: Regex("<h4[^>]*>\\s*<span>\\s*by\\s*</span>\\s*<span>\\s*<a[^>]*>(.*?)</a>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(html)?.let { Html.text(it.groupValues[1]) }
        val tags = Regex("<a[^>]*class=\"[^\"]*fiction-tag[^\"]*\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).map { Html.text(it.groupValues[1]) }.toList()
            .ifEmpty { (ld?.opt("genre") as? JSONArray)?.strings() ?: emptyList() }
        val labels = Regex("<span[^>]*class=\"[^\"]*label[^\"]*\"[^>]*>\\s*([A-Za-z ]{3,20})\\s*</span>").findAll(html).map { it.groupValues[1].trim().uppercase() }.toList()
        val status = labels.firstOrNull { it in setOf("ONGOING", "COMPLETED", "HIATUS", "DROPPED", "STUB", "INACTIVE") }?.lowercase()?.replaceFirstChar { it.uppercase() }
        // The chapter table's rows; newer pages also list them in a script.
        val chapters = Regex("class=\"[^\"]*chapter-row").findAll(html).count().takeIf { it > 0 }
            ?: Regex("window\\.chapters\\s*=\\s*(\\[.*?\\]);", RegexOption.DOT_MATCHES_ALL).find(html)?.let { runCatching { JSONArray(it.groupValues[1]).length() }.getOrNull() }
            ?: stat(html, "Chapters")
        val pages = ld?.optInt("numberOfPages")?.takeIf { it > 0 } ?: stat(html, "Pages")
        val words = stat(html, "Words")
        val description = ld?.str("description")?.let { para(it) }
            ?: Regex("<div[^>]*class=\"[^\"]*description[^\"]*\"[^>]*>(.*?)</div>\\s*</div>", RegexOption.DOT_MATCHES_ALL).find(html)?.let { para(it.groupValues[1]) }
            ?: meta(html, "og:description")
        // The first chapter's date is when it started appearing.
        val firstDate = Regex("<time[^>]*datetime=\"(\\d{4}-\\d{2}-\\d{2})").find(html)?.groupValues?.get(1)
            ?: ld?.str("datePublished")?.take(10)
        val web = WebNovel(canonical(url) ?: url, "Royal Road", status, chapters, null, words, fanfic = tags.any { it.equals("Fan Fiction", true) }, tags = tags, checkedAt = now())
        return details(web, Matcher.plainTitle(Html.decode(title)), author, description, cover(ld?.str("image") ?: meta(html, "og:image")), pages, firstDate, "English", tags)
    }

    // ---- AO3 ----

    internal fun parseAo3(html: String, url: String): BookDetails? {
        val title = Regex("<h2[^>]*class=\"[^\"]*title heading[^\"]*\"[^>]*>(.*?)</h2>", RegexOption.DOT_MATCHES_ALL).find(html)?.let { Html.text(it.groupValues[1]) }
            ?: return null
        val byline = Regex("<h3[^>]*class=\"[^\"]*byline[^\"]*\"[^>]*>(.*?)</h3>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: ""
        val authors = Regex("<a[^>]*rel=\"author\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(byline).map { Html.text(it.groupValues[1]) }.toList()
            .ifEmpty { listOfNotNull(Html.text(byline).takeIf { it.isNotBlank() }) }
        fun dd(cls: String): String? = Regex("<dd[^>]*class=\"$cls\"[^>]*>(.*?)</dd>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
        fun tags(cls: String): List<String> = dd("$cls tags")?.let { d -> Regex("<a[^>]*class=\"tag\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(d).map { Html.text(it.groupValues[1]) }.toList() } ?: emptyList()
        val fandoms = tags("fandom")
        val freeform = tags("freeform")
        val words = dd("words")?.let { Html.text(it).filter { c -> c.isDigit() }.toIntOrNull() }
        val (done, planned) = dd("chapters")?.let { Html.text(it) }?.split("/")?.let { p -> p.getOrNull(0)?.trim()?.toIntOrNull() to p.getOrNull(1)?.trim()?.toIntOrNull() } ?: (null to null)
        // "Completed:" vs "Updated:" beside the date; a one-shot (1/1) is complete too.
        val statusLabel = Regex("<dt[^>]*class=\"status\"[^>]*>(.*?)</dt>", RegexOption.DOT_MATCHES_ALL).find(html)?.let { Html.text(it.groupValues[1]).trimEnd(':') }
        val status = when {
            statusLabel?.startsWith("Completed", true) == true -> "Completed"
            done != null && planned != null && done >= planned -> "Completed"
            done != null -> "Ongoing"
            else -> null
        }
        val summary = Regex("<div[^>]*class=\"summary module\"[^>]*>.*?<blockquote[^>]*>(.*?)</blockquote>", RegexOption.DOT_MATCHES_ALL).find(html)?.let { para(it.groupValues[1]) }
        val series = Regex("<span class=\"position\">\\s*Part\\s+(\\d+)\\s+of\\s+<a[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(html)
        val language = dd("language")?.let { Html.text(it) }
        val fanfic = fandoms.isNotEmpty() && fandoms.none { it.equals("Original Work", true) }
        val web = WebNovel(canonical(url) ?: url, "AO3", status, done, planned, words, fanfic, (fandoms + freeform).distinct(), now())
        return details(web, title, authors.firstOrNull(), summary, null, null, dd("published")?.let { Html.text(it) }, language, fandoms + freeform.take(6))
            .copy(authors = authors, series = series?.let { Html.text(it.groupValues[2]) }, seriesIndex = series?.groupValues?.get(1))
    }

    // ---- Wattpad ----

    internal fun parseWattpad(o: JSONObject, url: String): BookDetails? {
        val title = o.str("title") ?: return null
        val web = WebNovel(
            canonical(url) ?: url, "Wattpad", if (o.optBoolean("completed")) "Completed" else "Ongoing",
            o.int("numParts"), null, null, fanfic = false, tags = o.arr("tags")?.strings() ?: emptyList(), checkedAt = now(),
        )
        return details(web, title, o.obj("user")?.str("name"), o.str("description"), o.str("cover"), null, o.str("createDate")?.take(10), o.obj("language")?.str("name"), web.tags)
    }

    // ---- anything else: the page's own tags ----

    internal fun parseGeneric(html: String, url: String, site: String): BookDetails? {
        val ld = ldBook(html)
        val title = ld?.str("name") ?: meta(html, "og:title") ?: tagText(html, "title") ?: return null
        val author = (ld?.opt("author") as? JSONObject)?.str("name") ?: meta(html, "books:author") ?: meta(html, "author")
            ?: Regex("<span[^>]*class=\"auth_name_fic\"[^>]*>(.*?)</span>", RegexOption.DOT_MATCHES_ALL).find(html)?.let { Html.text(it.groupValues[1]) }
        val tags = Regex("<a[^>]*class=\"[^\"]*(fic_genre|stag|genre)[^\"]*\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).map { Html.text(it.groupValues[2]) }.toList().distinct()
        val status = Regex("(?i)\\b(Ongoing|Completed|Hiatus)\\b").find(Html.text(html.substringBefore("</header>").takeLast(20_000)))?.value?.replaceFirstChar { it.uppercase() }
        val web = WebNovel(url, site, status, stat(html, "Chapters"), null, stat(html, "Words"), fanfic = site in fanficSites, tags = tags, checkedAt = now())
        return details(web, Matcher.plainTitle(Html.decode(title)), author, ld?.str("description") ?: meta(html, "og:description"), cover(ld?.str("image") ?: meta(html, "og:image")), null, ld?.str("datePublished")?.take(10), null, tags)
    }

    // ---- shared ----

    private fun details(
        web: WebNovel, title: String, author: String?, description: String?, coverUrl: String?, pages: Int?,
        published: String?, language: String?, genres: List<String>,
    ): BookDetails {
        val link = "Read it on ${web.site}: ${web.url}"
        return BookDetails(
            title = title.trim(),
            authors = listOfNotNull(author?.trim()?.takeIf { it.isNotEmpty() }),
            pages = pages ?: web.words?.let { w -> ((w + WORDS_PER_PAGE - 1) / WORDS_PER_PAGE).coerceAtLeast(1) },
            publisher = if (web.site == "AO3") "Archive of Our Own" else web.site,
            published = published?.let { BookInfo.normaliseDate(it) ?: it },
            language = language?.let { BookInfo.languageName(it) ?: it },
            // The story's link goes with the description, so the entry points back to it.
            description = listOfNotNull(description?.trim()?.takeIf { it.isNotEmpty() }, link).joinToString("\n\n"),
            coverUrl = coverUrl?.takeIf { it.startsWith("http") },
            format = "Ebook",
            source = web.site,
            genres = genres.distinct(),
            web = web,
        )
    }

    private fun now() = System.currentTimeMillis() / 1000

    // Royal Road's placeholder cover isn't a cover.
    private fun cover(url: String?): String? = url?.takeIf { it.startsWith("http") && !it.contains("nocover", true) }

    private fun ldBook(html: String): JSONObject? =
        Regex("<script[^>]*application/ld\\+json[^>]*>(.*?)</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(html).firstNotNullOfOrNull { m ->
            val v = runCatching { JSONTokener(m.groupValues[1].trim()).nextValue() }.getOrNull()
            val list = when (v) {
                is JSONArray -> v.objects()
                is JSONObject -> listOf(v) + (v.arr("@graph")?.objects() ?: emptyList())
                else -> emptyList()
            }
            list.firstOrNull { o -> o.optString("@type").let { it == "Book" || it == "CreativeWork" || it == "CreativeWorkSeries" } }
        }

    private fun meta(html: String, name: String): String? {
        val n = Regex.escape(name)
        return (Regex("<meta[^>]*(?:property|name)=[\"']$n[\"'][^>]*content=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("<meta[^>]*content=[\"']([^\"']*)[\"'][^>]*(?:property|name)=[\"']$n[\"']", RegexOption.IGNORE_CASE).find(html))
            ?.groupValues?.get(1)?.let { Html.decode(it).trim() }?.takeIf { it.isNotEmpty() }
    }

    private fun tagText(html: String, tag: String): String? =
        Regex("<$tag\\b[^>]*>(.*?)</$tag>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(html)?.let { Html.text(it.groupValues[1]) }?.takeIf { it.isNotEmpty() }

    /** A number shown beside a label ("Pages :" then "2,604"), as Royal Road's stats list them. */
    private fun stat(html: String, label: String): Int? =
        Regex("$label\\s*:?\\s*(?:</[^>]+>\\s*)*(?:<[^/][^>]*>\\s*)*([\\d,]{1,12})", RegexOption.IGNORE_CASE).find(html)
            ?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()?.takeIf { it > 0 }

    /** An HTML block as paragraphs of text. */
    private fun para(fragment: String): String? =
        fragment.split(Regex("(?i)</p>|<br\\s*/?>")).map { Html.text(it) }.filter { it.isNotEmpty() }.joinToString("\n\n").takeIf { it.isNotEmpty() }
}
