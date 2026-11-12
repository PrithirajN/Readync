package io.github.prithirajn.trackersync.source

import io.github.prithirajn.trackersync.data.SffFeedItem
import io.github.prithirajn.trackersync.net.Http
import io.github.prithirajn.trackersync.util.Html
import io.github.prithirajn.trackersync.util.arr
import io.github.prithirajn.trackersync.util.int
import io.github.prithirajn.trackersync.util.long
import io.github.prithirajn.trackersync.util.num
import io.github.prithirajn.trackersync.util.obj
import io.github.prithirajn.trackersync.util.objects
import io.github.prithirajn.trackersync.util.str
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Public and tracker-backed speculative fiction feeds: SFF magazines and award news (Reactor,
 * Locus, Transfer Orbit, File 770, Clarkesworld), web serial syndication (Royal Road Rising Stars
 * and Best Rated), SFF communities (r/Fantasy, r/PrintSF, r/ProgressionFantasy, r/litrpg,
 * r/horrorlit, r/scifi, r/WeirdLit), AniList/MangaDex SFF manga & light novels, Open Library,
 * Google Books, and KalpaDB (Indian speculative fiction).
 */
object SffSources {

    data class RssSource(
        val name: String,
        val url: String,
        val category: String,
        val defaultTags: List<String>,
        /** The Star Chart sector it mostly covers ("ORBIT", "CITADEL"…), when it has one. */
        val sector: String? = null,
        /** How much its stories count before your own taste (a vetted publisher's standing). */
        val weight: Double = 1.0,
        /** Only its stories that mention one of these (a general site's SFF pieces); null keeps all. */
        val keywords: List<String>? = null,
        /** Other addresses for the same feed, tried in turn when this one gives nothing. */
        val alt: List<String> = emptyList(),
    )

    private val EN_WORDS = setOf(
        "the", "and", "of", "to", "in", "is", "for", "on", "with", "by", "from", "this", "that", "new", "book", "books",
        "series", "at", "an", "are", "be", "his", "her", "you", "it", "my", "your", "what", "how", "why", "who", "best",
        "read", "review", "novel", "story", "out", "now", "about", "first", "last", "after", "into", "s",
    )
    private val OTHER_WORDS = setOf(
        "de", "la", "el", "los", "las", "del", "und", "der", "das", "den", "dem", "ist", "nicht", "le", "les", "des",
        "et", "est", "une", "que", "con", "para", "una", "ein", "eine", "il", "di", "della", "che", "em", "um", "uma",
        "não", "por", "het", "een", "van", "och", "är", "og", "er", "ikke", "się", "nie", "jest", "na", "ve", "bir", "ile", "dan", "yang",
    )

    /**
     * Whether a story is in English: its title and blurb mostly in Latin letters, and not
     * clearly in another language that uses them (Spanish, German, French, Portuguese…).
     */
    fun isEnglish(item: SffFeedItem): Boolean = isEnglish(listOfNotNull(item.title, item.summary?.take(300)).joinToString(" "))

    fun isEnglish(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return true
        val latin = letters.count { it.code < 0x250 }
        if (latin * 10 < letters.length * 8) return false
        val words = Regex("[\\p{L}']+").findAll(text.lowercase()).map { it.value.trim('\'') }.toList()
        val en = words.count { it in EN_WORDS }
        val other = words.count { it in OTHER_WORDS }
        return !(other >= 2 && other > en)
    }

    /**
     * Whether a story is news: from the vetted news sites, a subreddit, an author's or publisher's
     * own posts, or about your own series. Book listings (catalogues, trackers, AniList, Royal
     * Road's lists, your library's own entries) aren't.
     */
    fun isNews(item: SffFeedItem): Boolean {
        val src = item.source
        val name = src.substringBefore(" ·")
        return newsFeeds.any { it.name.equals(name, true) || it.name.equals(src, true) } ||
            src.startsWith("r/") || src.startsWith("New & upcoming") ||
            (item.category == "SOCIALS" && !name.equals("Royal Road", true))
    }

    /** Words that make a general site's story one about speculative fiction. */
    val SFF_WORDS = listOf(
        "fantasy", "science fiction", "sci-fi", "scifi", "sf ", "horror", "dragon", "space opera", "dystopia",
        "novel", "book", "author", "series", "adaptation", "hugo", "nebula", "tolkien", "sanderson", "dune",
        "witcher", "wheel of time", "rings of power", "game of thrones", "house of the dragon", "three-body",
        "foundation", "expanse", "murderbot", "silo", "fallout", "the last of us", "alien", "star wars", "star trek",
    )

    /**
     * Words that make a film or TV site's story one about speculative fiction. Narrower than
     * [SFF_WORDS] ("series" and "book" are on every TV page), so a film site's other stories stay out.
     */
    val SCREEN_WORDS = listOf(
        "fantasy", "science fiction", "sci-fi", "scifi", "horror", "dragon", "dystopia", "dystopian", "space opera",
        "alien", "time travel", "time-travel", "vampire", "zombie", "apocalyp", "werewolf", "wizard", "witch", "sorcer",
        "superpower", "robot", "android", "cyberpunk", "starship", "spaceship", "astronaut", "fairy tale", "mythic",
        "adaptation", "based on the novel", "based on the book", "based on the bestselling", "novel by", "book series",
        "tolkien", "lord of the rings", "rings of power", "hobbit", "game of thrones", "house of the dragon",
        "knight of the seven kingdoms", "westeros", "wheel of time", "witcher", "dune", "three-body", "3 body",
        "foundation", "the expanse", "murderbot", "silo", "fallout", "the last of us", "star wars", "andor", "mandalorian",
        "star trek", "doctor who", "stranger things", "his dark materials", "shadow and bone", "percy jackson",
        "harry potter", "hogwarts", "narnia", "discworld", "earthsea", "sandman", "good omens", "outlander",
        "godzilla", "kong", "blade runner", "predator", "avatar", "the matrix", "severance", "black mirror",
        "twilight zone", "pluribus", "lockwood", "carnival row", "the peripheral", "station eleven", "mickey 17",
        "project hail mary", "red rising", "mistborn", "cosmere", "sanderson", "le guin", "octavia butler", "n.k. jemisin",
    )

    /**
     * The vetted news sources, Artifact-style: magazines and review sites, publishers, awards,
     * adaptations, authors' own news and manga news. Each has its standing ([RssSource.weight]) and
     * the sector it mostly covers; general sites keep only their speculative-fiction stories.
     */
    val newsFeeds = listOf(
        // Magazines and review sites
        RssSource("Reactor", "https://reactormag.com/feed/", "NEWS_AWARDS", listOf("SFF News"), null, 1.4),
        RssSource("Locus", "https://locusmag.com/feed/", "NEWS_AWARDS", listOf("Awards & Releases"), null, 1.5),
        RssSource("File 770", "https://file770.com/feed/", "NEWS_AWARDS", listOf("Fandom"), null, 1.1),
        RssSource("Transfer Orbit", "https://transfer-orbit.ghost.io/rss/", "NEWS_AWARDS", listOf("SFF Dispatch"), null, 1.0),
        RssSource("Clarkesworld", "https://clarkesworldmagazine.com/feed/", "NEWS_AWARDS", listOf("Short Fiction"), "ORBIT", 1.0),
        RssSource("Strange Horizons", "https://strangehorizons.com/feed/", "NEWS_AWARDS", listOf("Reviews"), null, 1.0),
        RssSource("Lightspeed", "https://www.lightspeedmagazine.com/feed/", "NEWS_AWARDS", listOf("Short Fiction"), null, 0.9, null, listOf("https://www.lightspeedmagazine.com/?feed=rss2", "https://www.lightspeedmagazine.com/feed/atom/")),
        RssSource("Uncanny", "https://www.uncannymagazine.com/feed/", "NEWS_AWARDS", listOf("Short Fiction"), null, 0.9),
        RssSource("Grimdark Magazine", "https://www.grimdarkmagazine.com/feed/", "NEWS_AWARDS", listOf("Grimdark"), "CITADEL", 1.1),
        RssSource("The Fantasy Hive", "https://fantasy-hive.co.uk/feed/", "NEWS_AWARDS", listOf("Reviews"), "CITADEL", 1.1),
        RssSource("Fantasy Book Café", "https://www.fantasybookcafe.com/feed/", "NEWS_AWARDS", listOf("Reviews"), "CITADEL", 1.0),
        RssSource("The Quill to Live", "https://thequilltolive.com/feed/", "NEWS_AWARDS", listOf("Reviews"), null, 1.1),
        RssSource("Fantasy-Faction", "https://fantasy-faction.com/feed", "NEWS_AWARDS", listOf("Fantasy"), "CITADEL", 1.0),
        RssSource("SFFWorld", "https://www.sffworld.com/feed/", "NEWS_AWARDS", listOf("SFF News"), null, 0.9),
        RssSource("Book Riot SFF", "https://bookriot.com/category/sci-fi-fantasy/feed/", "NEWS_AWARDS", listOf("SFF News"), null, 1.0, null, listOf("https://bookriot.com/category/science-fiction/feed/")),
        RssSource("The Wertzone", "https://thewertzone.blogspot.com/feeds/posts/default", "NEWS_AWARDS", listOf("SFF News", "Adaptation"), null, 1.1, null, listOf("https://thewertzone.blogspot.com/feeds/posts/default?alt=rss")),
        RssSource("Black Gate", "https://www.blackgate.com/feed/", "NEWS_AWARDS", listOf("SFF News", "Sword & Sorcery"), "CITADEL", 1.0, null, listOf("https://www.blackgate.com/?feed=rss2")),
        RssSource("FanFiAddict", "https://fanfiaddict.com/feed/", "NEWS_AWARDS", listOf("Reviews", "Most Anticipated"), null, 1.0),
        RssSource("Fantasy Book Critic", "https://fantasybookcritic.blogspot.com/feeds/posts/default", "NEWS_AWARDS", listOf("Reviews", "SPFBO"), "CITADEL", 1.0, null, listOf("https://fantasybookcritic.blogspot.com/feeds/posts/default?alt=rss")),
        RssSource("Fantasy Literature", "https://www.fantasyliterature.com/feed/", "NEWS_AWARDS", listOf("Reviews"), null, 0.9),
        RssSource("Nerds of a Feather", "https://www.nerds-feather.com/feeds/posts/default", "NEWS_AWARDS", listOf("Reviews", "Essays"), null, 0.9, null, listOf("https://www.nerds-feather.com/feeds/posts/default?alt=rss")),
        RssSource("Ancillary Review of Books", "https://ancillaryreview.com/feed/", "NEWS_AWARDS", listOf("Reviews", "Essays"), null, 0.9),
        RssSource("Apex Magazine", "https://apex-magazine.com/feed/", "NEWS_AWARDS", listOf("Short Fiction", "Dark"), "VOID", 0.8),
        RssSource("Tor Nightfire", "https://tornightfire.com/feed/", "NEWS_AWARDS", listOf("Horror"), "VOID", 1.0, null, listOf("https://www.tornightfire.com/feed/")),
        RssSource("SFFaudio", "https://www.sffaudio.com/feed/", "NEWS_AWARDS", listOf("Audiobooks"), null, 0.8),
        // Fandom news for the big series
        RssSource("TheOneRing.net", "https://www.theonering.net/torwp/feed/", "NEWS_AWARDS", listOf("Tolkien", "Adaptation"), "CITADEL", 1.0),
        RssSource("Dragonmount", "https://feeds.feedburner.com/dragonmount/news", "NEWS_AWARDS", listOf("Wheel of Time"), "CITADEL", 1.0, null, listOf("https://dragonmount.com/feed/")),
        // Publishers
        RssSource("Tor Publishing", "https://torpublishinggroup.com/feed/", "NEWS_AWARDS", listOf("Publisher News"), null, 1.2, null, listOf("https://torpublishinggroup.com/?feed=rss2", "https://www.torpublishinggroup.com/feed/")),
        RssSource("Orbit Books", "https://www.orbitbooks.net/feed/", "NEWS_AWARDS", listOf("Publisher News"), null, 1.2, null, listOf("https://www.orbitbooks.net/?feed=rss2", "https://www.orbitbooks.net/blog/feed/")),
        RssSource("Angry Robot", "https://angryrobotbooks.com/feed/", "NEWS_AWARDS", listOf("Publisher News"), null, 1.0),
        // Awards
        RssSource("Hugo Awards", "https://www.thehugoawards.org/feed/", "NEWS_AWARDS", listOf("Awards", "Hugo"), null, 1.4),
        RssSource("SFWA · Nebulas", "https://www.sfwa.org/feed/", "NEWS_AWARDS", listOf("Awards", "Nebula"), null, 1.2),
        RssSource("Clarke Award", "https://www.clarkeaward.com/feed/", "NEWS_AWARDS", listOf("Awards"), "ORBIT", 1.2, null, listOf("http://www.clarkeaward.com/feed/", "https://www.clarkeaward.com/?feed=rss2")),
        RssSource("BSFA", "https://www.bsfa.co.uk/feed/", "NEWS_AWARDS", listOf("Awards"), null, 1.0, null, listOf("https://www.bsfa.co.uk/?feed=rss2", "https://bsfa.co.uk/feed/")),
        // Adaptations (their speculative-fiction stories only)
        // SFF on screen: films and series (general film sites keep only their speculative-fiction stories)
        RssSource("io9 · Gizmodo", "https://gizmodo.com/feed", "NEWS_AWARDS", listOf("Film & TV", "Adaptation"), null, 1.0, SCREEN_WORDS, listOf("https://gizmodo.com/io9/feed")),
        RssSource("Winter Is Coming", "https://winteriscoming.net/feed/", "NEWS_AWARDS", listOf("Film & TV", "Adaptation"), "CITADEL", 0.8, SFF_WORDS),
        RssSource("Den of Geek", "https://www.denofgeek.com/feed/", "NEWS_AWARDS", listOf("Film & TV"), null, 0.8, SCREEN_WORDS),
        RssSource("/Film", "https://www.slashfilm.com/feed/", "NEWS_AWARDS", listOf("Film & TV"), null, 0.8, SCREEN_WORDS),
        RssSource("Collider", "https://collider.com/feed/", "NEWS_AWARDS", listOf("Film & TV"), null, 0.7, SCREEN_WORDS),
        RssSource("Screen Rant", "https://screenrant.com/feed/", "NEWS_AWARDS", listOf("Film & TV"), null, 0.6, SCREEN_WORDS),
        RssSource("Bleeding Cool TV", "https://bleedingcool.com/tv/feed/", "NEWS_AWARDS", listOf("Film & TV"), null, 0.6, SCREEN_WORDS),
        RssSource("Bleeding Cool Movies", "https://bleedingcool.com/movies/feed/", "NEWS_AWARDS", listOf("Film & TV"), null, 0.6, SCREEN_WORDS),
        RssSource("Polygon", "https://www.polygon.com/rss/index.xml", "NEWS_AWARDS", listOf("Film & TV"), null, 0.7, SCREEN_WORDS, listOf("https://www.polygon.com/feed/")),
        RssSource("Bloody Disgusting", "https://bloody-disgusting.com/feed/", "NEWS_AWARDS", listOf("Film & TV", "Horror"), "VOID", 0.7),
        RssSource("TrekMovie", "https://trekmovie.com/feed/", "NEWS_AWARDS", listOf("Film & TV", "Star Trek"), "ORBIT", 0.7),
        RssSource("Redanian Intelligence", "https://redanianintelligence.com/feed/", "NEWS_AWARDS", listOf("Film & TV", "Witcher"), "CITADEL", 0.6),
        // Authors' own news
        RssSource("Brandon Sanderson", "https://www.brandonsanderson.com/blogs/blog.atom", "SOCIALS", listOf("Author News", "Cosmere"), "CITADEL", 1.3),
        RssSource("John Scalzi", "https://whatever.scalzi.com/feed/", "SOCIALS", listOf("Author News"), "ORBIT", 0.9, SFF_WORDS),
        RssSource("Joe Abercrombie", "https://joeabercrombie.com/feed/", "SOCIALS", listOf("Author News", "Grimdark"), "CITADEL", 1.1),
        RssSource("Martha Wells", "https://marthawells.dreamwidth.org/data/rss", "SOCIALS", listOf("Author News", "Murderbot"), "ORBIT", 1.0, null, listOf("https://marthawells.dreamwidth.org/data/atom")),
        RssSource("George R.R. Martin", "https://georgerrmartin.com/notablog/feed/", "SOCIALS", listOf("Author News", "Westeros"), "CITADEL", 1.3),
        RssSource("Mark Lawrence", "https://mark---lawrence.blogspot.com/feeds/posts/default", "SOCIALS", listOf("Author News", "SPFBO"), "CITADEL", 0.9, null, listOf("https://mark---lawrence.blogspot.com/feeds/posts/default?alt=rss")),
        RssSource("Charles Stross", "https://www.antipope.org/charlie/blog-static/atom.xml", "SOCIALS", listOf("Author News"), "ORBIT", 0.8, SFF_WORDS),
        // Manga and light novels (their manga stories only)
        RssSource(
            "Anime News Network", "https://www.animenewsnetwork.com/news/rss.xml?ann-edition=w", "SERIALS_MANGA", listOf("Manga"), null, 0.9,
            listOf("manga", "light novel", "manhwa", "webtoon"),
        ),
    )

    val royalRoadFeeds = listOf(
        RssSource("Royal Road · Rising Stars", "https://www.royalroad.com/syndication/fictions-rising-stars", "SERIALS_MANGA", listOf("Rising Star", "Web Serial")),
        RssSource("Royal Road · Best Rated", "https://www.royalroad.com/syndication/fictions-best-rated", "SERIALS_MANGA", listOf("Top Rated", "Progression / LitRPG")),
        RssSource("Royal Road · Latest", "https://www.royalroad.com/syndication/fictions-latest-updates", "SERIALS_MANGA", listOf("New Chapter", "Web Serial")),
    )

    val subreddits = listOf(
        "Fantasy" to listOf("Epic & High Fantasy", "SFF"),
        "PrintSF" to listOf("Hard SF & Space Opera", "PrintSF"),
        "ProgressionFantasy" to listOf("Progression Fantasy", "Cultivation"),
        "litrpg" to listOf("LitRPG", "GameLit"),
        "horrorlit" to listOf("Cosmic & Weird Horror", "Dark"),
        "scifi" to listOf("Science Fiction", "Sci-Fi"),
        "WeirdLit" to listOf("Weird Fiction", "New Weird"),
        "sciencefiction" to listOf("Science Fiction", "Sci-Fi"),
        "Grimdark" to listOf("Grimdark", "Dark Fantasy"),
        "Cosmere" to listOf("Cosmere", "Sanderson"),
        "noveltranslations" to listOf("Light Novels", "Web Serial"),
    )

    /** Reads an RSS 2.0 or Atom XML feed into [SffFeedItem] entries. */
    fun fetchRss(feed: RssSource, limit: Int = 8, now: Long = System.currentTimeMillis() / 1000): List<SffFeedItem> {
        for (url in listOf(feed.url) + feed.alt) {
            val got = fetchRssAt(feed, url, limit, now)
            if (got.isNotEmpty()) return got
        }
        // A site that turns plain requests away (a bot check) is read the way a browser reads it.
        return viaBrowser(feed, limit, now)
    }

    /** Opens a page in the app's unseen browser and hands back what it shows (set by the app). */
    @Volatile var browser: ((String) -> String?)? = null

    /** One page at a time in the unseen browser. */
    private val browserTurn = java.util.concurrent.Semaphore(1)

    private fun viaBrowser(feed: RssSource, limit: Int, now: Long): List<SffFeedItem> {
        val b = browser ?: return emptyList()
        if (!browserTurn.tryAcquire(20, java.util.concurrent.TimeUnit.SECONDS)) return emptyList()
        try {
            for (url in listOf(feed.url) + feed.alt.take(1)) {
                val page = runCatching { b(url) }.getOrNull() ?: continue
                // The feed as the browser shows it: its own XML, or that XML shown as text in a page.
                val xml = if (Regex("(?i)<(item|entry)\\b").containsMatchIn(page)) page else Html.decode(page)
                val got = parseRss(xml, feed, limit, now)
                if (got.isNotEmpty()) return got
            }
        } finally {
            browserTurn.release()
        }
        return emptyList()
    }

    private fun fetchRssAt(feed: RssSource, url: String, limit: Int, now: Long): List<SffFeedItem> {
        val xml = runCatching {
            val r = Http.request(
                "GET", url,
                mapOf(
                    "Accept" to "application/rss+xml, application/atom+xml, application/xml, text/xml, */*",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "User-Agent" to Http.defaultUserAgent,
                ),
                timeoutMs = 12_000,
            )
            if (r.ok && r.body.contains("<item", true) || r.ok && r.body.contains("<entry", true)) r.body else null
        }.getOrNull() ?: return emptyList()
        return parseRss(xml, feed, limit, now)
    }

    /** Whether [item] is about what [feed] is kept for (all of it, unless it lists words to look for). */
    internal fun wanted(feed: RssSource, item: SffFeedItem): Boolean {
        val words = feed.keywords ?: return true
        val text = " " + listOfNotNull(item.title, item.summary, item.tags.joinToString(" ")).joinToString(" ").lowercase() + " "
        return words.any { text.contains(it) }
    }

    internal fun parseRss(xml: String, feed: RssSource, limit: Int, now: Long): List<SffFeedItem> {
        // A general site's feed is read further, keeping only its speculative-fiction stories.
        val items = Regex("<(?:item|entry)\\b[^>]*>(.*?)</(?:item|entry)>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(xml).map { it.groupValues[1] }.take(if (feed.keywords != null) limit * 5 else limit).toList()
        val out = ArrayList<SffFeedItem>()
        for (chunk in items) {
            val rawTitle = tagValue(chunk, "title") ?: continue
            val title = Html.text(rawTitle).trim().takeIf { it.isNotEmpty() } ?: continue
            // Atom: the article's own link (rel="alternate", or no rel), not its comments or edit links (Blogger lists those first).
            val linkTags = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(chunk).map { it.value }.toList()
            fun hrefOf(tag: String) = Regex("href=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
            fun relOf(tag: String) = Regex("\\brel=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.lowercase()
            val link = (linkTags.firstOrNull { relOf(it) == "alternate" } ?: linkTags.firstOrNull { relOf(it) == null && hrefOf(it) != null })?.let { hrefOf(it) }
                ?: linkTags.firstNotNullOfOrNull { t -> hrefOf(t)?.takeIf { relOf(t) !in setOf("replies", "edit", "self", "enclosure") } }
                ?: tagValue(chunk, "link")?.trim()
                ?: tagValue(chunk, "guid")?.takeIf { it.startsWith("http") }
            val fullRaw = (tagValue(chunk, "content:encoded") ?: tagValue(chunk, "content") ?: tagValue(chunk, "description") ?: tagValue(chunk, "summary"))?.let { unescaped(it) }
            val descRaw = (tagValue(chunk, "description") ?: tagValue(chunk, "summary"))?.let { unescaped(it) } ?: fullRaw
            val cover = (fullRaw ?: descRaw)?.let { Regex("<img[^>]+src=[\"'](https?://[^\"']+)[\"']", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
                ?: Regex("<media:(?:content|thumbnail)[^>]+url=[\"'](https?://[^\"']+)[\"']", RegexOption.IGNORE_CASE).find(chunk)?.groupValues?.get(1)
            val summary = descRaw?.let { Html.text(it).replace(Regex("\\s+"), " ").trim() }?.takeIf { it.length > 12 }?.take(320)
            val fullMarkdown = fullRaw?.let { SffArticleReader.htmlToMarkdown(it, summary) }?.takeIf { it.length > 120 }
            val author = (tagValue(chunk, "dc:creator") ?: tagValue(chunk, "author") ?: tagValue(chunk, "name"))
                ?.let { Html.text(it).trim() }?.takeIf { it.isNotEmpty() && it.length < 60 }
            val cats = Regex("<category[^>]*>(.*?)</category>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .findAll(chunk).mapNotNull { Html.text(it.groupValues[1]).trim().takeIf { s -> s.isNotEmpty() && s.length < 32 } }.toList()
            val pub = (tagValue(chunk, "pubDate") ?: tagValue(chunk, "published") ?: tagValue(chunk, "updated"))?.let { parseRssTime(it) } ?: now
            val idKey = (link ?: title).hashCode().toUInt().toString(16)
            out.add(
                SffFeedItem(
                    id = "rss-${feed.name.lowercase().replace(Regex("[^a-z0-9]+"), "-")}-$idKey",
                    category = feed.category,
                    source = feed.name,
                    title = title,
                    subtitle = author?.let { "by $it" },
                    summary = summary,
                    fullContent = fullMarkdown ?: summary,
                    url = link?.let { Html.decode(it) },
                    coverUrl = cover?.let { Html.decode(it) },
                    author = author,
                    tags = (cats + feed.defaultTags).distinctBy { it.lowercase() }.take(5),
                    publishedAt = pub,
                )
            )
        }
        return out.filter { wanted(feed, it) }.take(limit)
    }

    /** HTML a feed sent escaped ("&lt;p&gt;", as Reddit's does), as HTML again. */
    internal fun unescaped(raw: String): String =
        if (raw.contains("&lt;") && !Regex("<[a-zA-Z/!]").containsMatchIn(raw)) Html.decode(raw) else raw

    private fun tagValue(xml: String, tag: String): String? {
        val m = Regex("<$tag(?:\\s[^>]*)?>(.*?)</$tag>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(xml) ?: return null
        val inner = m.groupValues[1].trim()
        return inner.removePrefix("<![CDATA[").removeSuffix("]]>").trim().takeIf { it.isNotEmpty() }
    }

    private val rssFormats = listOf(
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US),
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm Z", Locale.US),
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US),
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
    )

    private fun parseRssTime(raw: String): Long? {
        val s = raw.trim()
        // ISO dates with fractions of a second (Blogger's "2026-10-06T00:30:00.000-07:00") and plain RFC 1123.
        runCatching { return java.time.OffsetDateTime.parse(s).toEpochSecond() }
        runCatching { return java.time.Instant.parse(s).epochSecond }
        runCatching { return java.time.ZonedDateTime.parse(s, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() }
        for (fmt in rssFormats) {
            runCatching { fmt.parse(s)?.time?.div(1000) }.getOrNull()?.let { return it }
        }
        return null
    }

    /** Fetches hot discussions from an SFF subreddit (`r/Fantasy`, `r/PrintSF`, `r/ProgressionFantasy`, etc.). */
    fun fetchSubreddit(sub: String, defaultTags: List<String>, limit: Int = 8, now: Long = System.currentTimeMillis() / 1000): List<SffFeedItem> {
        val url = "https://www.reddit.com/r/$sub/hot.json?limit=${limit + 4}&raw_json=1"
        val r = runCatching {
            Http.request(
                "GET", url,
                mapOf(
                    "Accept" to "application/json",
                    "User-Agent" to "android:io.github.prithirajn.trackersync:1.28 (speculative fiction reader)",
                ),
                timeoutMs = 12_000,
            )
        }.getOrNull()
        if (r == null || !r.ok) {
            // Reddit's JSON turned us away (it limits quick runs): its RSS, from the old site, after a moment.
            runCatching { Thread.sleep(800) }
            return fetchRss(RssSource("r/$sub", "https://old.reddit.com/r/$sub/hot/.rss", "COMMUNITY", defaultTags, alt = listOf("https://www.reddit.com/r/$sub/hot/.rss")), limit, now)
        }
        val children = runCatching { JSONObject(r.body).obj("data")?.arr("children")?.objects() }.getOrNull() ?: return emptyList()
        val out = ArrayList<SffFeedItem>()
        for (wrapper in children) {
            val d = wrapper.obj("data") ?: continue
            if (d.optBoolean("stickied", false)) continue
            val title = d.str("title")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val id = d.str("id") ?: continue
            val flair = d.str("link_flair_text")?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            val score = d.optInt("score", 0)
            val comments = d.optInt("num_comments", 0)
            val rawSelftext = d.str("selftext")?.trim()?.takeIf { it.length > 15 }
            val selftext = rawSelftext?.let { Html.text(it).replace(Regex("\\s+"), " ").trim() }?.take(320)
            val permalink = d.str("permalink")?.let { "https://www.reddit.com$it" } ?: "https://www.reddit.com/r/$sub"
            val thumb = d.str("thumbnail")?.takeIf { it.startsWith("http") }
            val created = d.long("created_utc") ?: now
            val spoiler = d.optBoolean("spoiler", false) || Regex("(?i)\\bspoiler\\b").containsMatchIn(title) || (flair != null && flair.contains("spoiler", true))
            out.add(
                SffFeedItem(
                    id = "reddit-$sub-$id",
                    category = "COMMUNITY",
                    source = "r/$sub",
                    title = title,
                    subtitle = listOfNotNull(
                        flair,
                        if (score > 0) "▲ $score" else null,
                        if (comments > 0) "$comments comments" else null,
                    ).joinToString(" · ").ifEmpty { "r/$sub" },
                    summary = selftext,
                    fullContent = rawSelftext,
                    url = permalink,
                    coverUrl = thumb,
                    tags = (listOfNotNull(flair) + defaultTags).distinctBy { it.lowercase() }.take(4),
                    spoilerWarning = spoiler,
                    publishedAt = created,
                )
            )
            if (out.size >= limit) break
        }
        return out
    }

    /**
     * Queries AniList's public GraphQL API for trending Sci-Fi, Fantasy & Supernatural manga and
     * light novels (works with or without an AniList account).
     */
    fun fetchAniListSff(token: String?, limit: Int = 8, now: Long = System.currentTimeMillis() / 1000): List<SffFeedItem> {
        val query = """
            query (${"$"}perPage: Int) {
              Page(page: 1, perPage: ${"$"}perPage) {
                media(type: MANGA, genre_in: ["Sci-Fi", "Fantasy"], sort: [TRENDING_DESC, POPULARITY_DESC], isAdult: false) {
                  id
                  format
                  status
                  chapters
                  volumes
                  averageScore
                  startDate { year }
                  title { english romaji userPreferred }
                  description(asHtml: false)
                  genres
                  coverImage { large }
                  siteUrl
                  staff(perPage: 2, sort: [RELEVANCE]) { edges { node { name { full } } } }
                }
              }
            }
        """.trimIndent()
        val headers = buildMap {
            put("Accept", "application/json")
            put("Content-Type", "application/json")
            token?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer ${it.trim()}") }
        }
        val body = JSONObject().put("query", query).put("variables", JSONObject().put("perPage", limit)).toString()
        val r = runCatching { Http.request("POST", AniList.base, headers, body, timeoutMs = 12_000) }.getOrNull() ?: return emptyList()
        if (!r.ok) return emptyList()
        val media = runCatching { JSONObject(r.body).obj("data")?.obj("Page")?.arr("media")?.objects() }.getOrNull() ?: return emptyList()
        return media.mapNotNull { m ->
            val id = m.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
            val t = m.obj("title")
            val title = listOf("english", "userPreferred", "romaji").firstNotNullOfOrNull { k -> t?.str(k)?.takeIf { it.isNotBlank() } } ?: return@mapNotNull null
            val fmt = when (m.str("format")) {
                "NOVEL" -> "Light Novel"
                "ONE_SHOT" -> "One-shot"
                else -> "Manga"
            }
            val status = m.str("status")?.lowercase()?.replaceFirstChar { it.uppercase() }
            val ch = m.int("chapters")
            val score = m.int("averageScore")?.takeIf { it > 0 }?.let { it / 20.0 } // 0..100 -> 0..5 stars
            val author = m.obj("staff")?.arr("edges")?.optJSONObject(0)?.obj("node")?.obj("name")?.str("full")
            val genres = m.arr("genres")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } }.orEmpty()
            val desc = m.str("description")?.let { Html.text(it).replace(Regex("\\s+"), " ").trim() }?.take(300)
            SffFeedItem(
                id = "anilist-sff-$id",
                category = "SERIALS_MANGA",
                source = "AniList",
                title = title,
                subtitle = listOfNotNull(fmt, status, ch?.let { "$it ch" }, author).joinToString(" · "),
                summary = desc,
                url = m.str("siteUrl") ?: "https://anilist.co/manga/$id",
                coverUrl = m.obj("coverImage")?.str("large"),
                author = author,
                rating = score,
                year = m.obj("startDate")?.int("year"),
                tags = (listOf(fmt) + genres).distinct().take(5),
                externalId = id.toString(),
                publishedAt = now,
            )
        }
    }

    /**
     * Queries Open Library for newly catalogued works by the reader's favorite SFF authors or
     * trending speculative fiction subjects.
     */
    fun fetchOpenLibrarySff(authors: List<String>, subgenreQuery: String = "science fiction", now: Long = System.currentTimeMillis() / 1000): List<SffFeedItem> {
        val out = ArrayList<SffFeedItem>()
        for (author in authors.take(3)) {
            val clean = BookInfo.cleanAuthor(author)
            if (clean.isBlank()) continue
            val url = "${OpenLibrary.base}/search.json?author=${Http.enc(clean)}&sort=new&limit=4&fields=key,title,author_name,first_publish_year,cover_i,number_of_pages_median,subject"
            val docs = runCatching {
                val r = Http.request("GET", url, mapOf("Accept" to "application/json"), timeoutMs = 12_000)
                if (r.ok) JSONObject(r.body).arr("docs")?.objects() else null
            }.getOrNull() ?: continue
            for (d in docs.take(2)) {
                val key = d.str("key") ?: continue
                val title = d.str("title") ?: continue
                val year = d.int("first_publish_year")
                val coverId = d.optLong("cover_i", 0L).takeIf { it > 0 }
                val subjects = d.arr("subject")?.let { a -> (0 until minOf(8, a.length())).mapNotNull { a.optString(it).takeIf { s -> s.length in 3..28 } } }.orEmpty()
                out.add(
                    SffFeedItem(
                        id = "ol-${key.removePrefix("/works/")}",
                        category = "SERIES_HORIZON",
                        source = "Open Library",
                        title = title,
                        subtitle = listOfNotNull(clean, year?.toString()).joinToString(" · "),
                        url = "https://openlibrary.org$key",
                        coverUrl = coverId?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" },
                        author = clean,
                        pages = d.int("number_of_pages_median"),
                        year = year,
                        tags = subjects.ifEmpty { listOf("Speculative Fiction") }.take(4),
                        matchedLibraryBook = "By $clean (in your library)",
                        publishedAt = now,
                    )
                )
            }
        }
        if (out.size < 4) {
            val qUrl = "${OpenLibrary.base}/search.json?subject=${Http.enc(subgenreQuery)}&sort=new&limit=5&fields=key,title,author_name,first_publish_year,cover_i,number_of_pages_median"
            val docs = runCatching {
                val r = Http.request("GET", qUrl, mapOf("Accept" to "application/json"), timeoutMs = 12_000)
                if (r.ok) JSONObject(r.body).arr("docs")?.objects() else null
            }.getOrNull().orEmpty()
            for (d in docs.take(4)) {
                val key = d.str("key") ?: continue
                val title = d.str("title") ?: continue
                val author = d.arr("author_name")?.optString(0)?.takeIf { it.isNotBlank() }
                val year = d.int("first_publish_year")
                val coverId = d.optLong("cover_i", 0L).takeIf { it > 0 }
                out.add(
                    SffFeedItem(
                        id = "ol-sub-${key.removePrefix("/works/")}",
                        category = "DISCOVERY",
                        source = "Open Library",
                        title = title,
                        subtitle = listOfNotNull(author, year?.toString()).joinToString(" · "),
                        url = "https://openlibrary.org$key",
                        coverUrl = coverId?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" },
                        author = author,
                        pages = d.int("number_of_pages_median"),
                        year = year,
                        tags = listOf(subgenreQuery.replaceFirstChar { it.uppercase() }, "New in Catalogue"),
                        publishedAt = now,
                    )
                )
            }
        }
        return out
    }

    /**
     * Queries Google Books public volumes API for newly published or high-relevance SFF books
     * matching the reader's authors or subgenre query.
     */
    fun fetchGoogleBooksSff(query: String, limit: Int = 5, now: Long = System.currentTimeMillis() / 1000): List<SffFeedItem> {
        val url = "https://www.googleapis.com/books/v1/volumes?q=${Http.enc("$query subject:fiction")}&orderBy=newest&maxResults=$limit"
        val items = runCatching {
            val r = Http.request("GET", url, mapOf("Accept" to "application/json"), timeoutMs = 10_000)
            if (r.ok) JSONObject(r.body).arr("items")?.objects() else null
        }.getOrNull() ?: return emptyList()
        return items.mapNotNull { item ->
            val id = item.str("id") ?: return@mapNotNull null
            val v = item.obj("volumeInfo") ?: return@mapNotNull null
            val title = v.str("title") ?: return@mapNotNull null
            val author = v.arr("authors")?.optString(0)?.takeIf { it.isNotBlank() }
            val desc = v.str("description")?.let { Html.text(it).trim() }?.take(300)
            val cover = v.obj("imageLinks")?.let { it.str("thumbnail") ?: it.str("smallThumbnail") }?.replace("http://", "https://")
            val pub = v.str("publishedDate")
            val year = pub?.take(4)?.toIntOrNull()
            val cats = v.arr("categories")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } }.orEmpty()
            SffFeedItem(
                id = "gbooks-$id",
                category = "DISCOVERY",
                source = "Google Books",
                title = title,
                subtitle = listOfNotNull(author, pub).joinToString(" · "),
                summary = desc,
                url = v.str("infoLink") ?: "https://books.google.com/books?id=$id",
                coverUrl = cover,
                author = author,
                rating = v.num("averageRating")?.takeIf { it > 0 },
                ratingsCount = v.int("ratingsCount"),
                pages = v.int("pageCount")?.takeIf { it > 0 },
                year = year,
                releaseDate = pub,
                tags = (cats + listOf("Speculative Fiction")).distinct().take(4),
                publishedAt = now,
            )
        }
    }

    /**
     * Queries KalpaDB (kalpadb.com) for Indian speculative fiction works.
     */
    fun fetchKalpaDbSff(queries: List<String>, now: Long = System.currentTimeMillis() / 1000): List<SffFeedItem> {
        val out = ArrayList<SffFeedItem>()
        for (q in (queries + listOf("fantasy", "science")).distinct().take(2)) {
            val d = runCatching { KalpaDB.search(q, null) }.getOrNull() ?: continue
            val key = d.title.lowercase().hashCode().toUInt().toString(16)
            out.add(
                SffFeedItem(
                    id = "kalpa-$key",
                    category = "DISCOVERY",
                    source = "KalpaDB",
                    title = d.title,
                    subtitle = listOfNotNull(d.author, d.series?.let { s -> s + (d.seriesIndex?.let { " #$it" } ?: "") }, d.language).joinToString(" · "),
                    summary = d.description?.take(300),
                    url = "https://kalpadb.com",
                    coverUrl = d.coverUrl,
                    author = d.author,
                    series = d.series,
                    seriesNumber = d.seriesIndex,
                    pages = d.pages,
                    year = d.year,
                    releaseDate = d.published,
                    tags = listOfNotNull("Indian SFF", d.language, d.format).distinct(),
                    publishedAt = now,
                )
            )
        }
        return out.distinctBy { it.title.lowercase() }
    }
}
