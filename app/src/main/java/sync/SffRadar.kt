package io.github.prithirajn.readync.sync

import io.github.prithirajn.readync.data.Book
import io.github.prithirajn.readync.data.BookFacts
import io.github.prithirajn.readync.data.ReadStatus
import io.github.prithirajn.readync.data.SffFeedItem
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.data.Store
import io.github.prithirajn.readync.source.SffSources
import io.github.prithirajn.readync.trackers.HardcoverTracker
import io.github.prithirajn.readync.trackers.SeekquelTracker
import io.github.prithirajn.readync.trackers.Session
import io.github.prithirajn.readync.trackers.StoryGraphTracker
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Multi-source Speculative Fiction Radar & Subgenre Star-Chart engine.
 *
 * Pulls SFF updates across every non-Libby source available in Tracker Sync:
 * - Hardcover GraphQL (active series horizon, upcoming sequels, trope/mood discovery)
 * - Seekquel Reading/Account API + SeekquelWeb (series & author matches, work enrichment tags)
 * - StoryGraph (SFF browse & author lookups when linked)
 * - Web Serials & Progression Fantasy (Royal Road Rising Stars / Best Rated / Latest + tracked WebNovels)
 * - SFF Magazines & Award News (Reactor, Locus, Transfer Orbit, File 770, Clarkesworld)
 * - SFF Community Pulse (r/Fantasy, r/PrintSF, r/ProgressionFantasy, r/litrpg, r/horrorlit, r/scifi, r/WeirdLit)
 * - Open Library, Google Books, KalpaDB (Indian & global SFF catalogues), and AniList (SFF Manga & Light Novels).
 */
object SffRadar {
    /** How long all the sources together may take in one refresh. */
    const val FETCH_BUDGET_MS = 45_000L


    /** The news is looked at about hourly (or whenever you pull to refresh). */
    const val REFRESH_INTERVAL_SEC = 3_300L
    /** Book finds (Hardcover, Seekquel, StoryGraph, catalogues) change slowly: every 6 hours. */
    const val DISCOVERY_INTERVAL_SEC = 6 * 3_600L
    /** Stories stay in the feed this long after they were first seen. */
    const val KEEP_SEC = 4 * 86_400L
    const val MAX_ITEMS = 260

    enum class Sector(
        val id: String,
        val title: String,
        val subtitle: String,
        val keywords: List<String>,
        val probeQueries: List<String>,
        val classSuffix: String,
    ) {
        ORBIT(
            id = "ORBIT",
            title = "Deep Orbit",
            subtitle = "Hard SF · Space Opera · First Contact · Cyberpunk",
            keywords = listOf("sci-fi", "science fiction", "space opera", "hard sf", "cyberpunk", "first contact", "space", "ai", "robot", "galactic", "mecha", "futuristic", "interstellar", "mars"),
            probeQueries = listOf("space opera science fiction", "first contact hard science fiction", "cyberpunk artificial intelligence", "interstellar ark science fiction"),
            classSuffix = "Orbit-Navigator",
        ),
        CITADEL(
            id = "CITADEL",
            title = "The Citadel",
            subtitle = "Epic Fantasy · Grimdark · Sword & Sorcery · Mythic",
            keywords = listOf("fantasy", "epic fantasy", "high fantasy", "grimdark", "sword", "magic", "dragon", "mythology", "political", "medieval", "urban fantasy", "historical fantasy"),
            probeQueries = listOf("epic fantasy magic system", "grimdark fantasy", "mythic fantasy gods", "high fantasy worldbuilding"),
            classSuffix = "Citadel-Chronicler",
        ),
        SPIRE(
            id = "SPIRE",
            title = "The Spire",
            subtitle = "Progression Fantasy · LitRPG · Cultivation · Serials",
            keywords = listOf("progression", "litrpg", "cultivation", "xianxia", "wuxia", "gamelit", "dungeon", "time loop", "portal", "isekai", "system", "royal road", "serial", "fast-paced"),
            probeQueries = listOf("progression fantasy time loop", "litrpg dungeon crawler", "cultivation fantasy", "progression fantasy magic academy"),
            classSuffix = "Spire-Ascendant",
        ),
        VOID(
            id = "VOID",
            title = "The Void",
            subtitle = "Weird Fiction · Cosmic Horror · Dark · New Weird",
            keywords = listOf("horror", "cosmic horror", "weird", "new weird", "dark", "creepy", "scary", "gothic", "eldritch", "supernatural", "occult", "tense", "mysterious", "ghost"),
            probeQueries = listOf("cosmic horror weird fiction", "new weird speculative fiction", "dark fantasy gothic horror", "weird science fiction"),
            classSuffix = "Void-Lorekeeper",
        ),
        CHRONOS(
            id = "CHRONOS",
            title = "Chronos & Alt",
            subtitle = "Time Travel · Dystopian · Post-Apocalyptic · Speculative",
            keywords = listOf("time travel", "dystopia", "dystopian", "post-apocalyptic", "apocalyptic", "alternate history", "speculative", "steampunk", "solarpunk", "biopunk", "reflective", "challenging"),
            probeQueries = listOf("time travel speculative fiction", "post-apocalyptic science fiction", "alternate history speculative", "solarpunk biopunk fiction"),
            classSuffix = "Chronos-Watcher",
        );

        companion object {
            fun byId(id: String?): Sector = entries.firstOrNull { it.id == id } ?: ORBIT
        }
    }

    /** A book's main sector (uncharted books fall back to Deep Orbit here; the chart shows them apart). */
    fun sectorOf(book: Book, facts: BookFacts?, isWebNovel: Boolean): Sector =
        SffChart.sectorsOfBook(book, facts, isWebNovel).firstOrNull() ?: Sector.ORBIT

    /**
     * The Astral Probe's return: a book from the sector it went to (a sub-genre you haven't
     * revealed yet, looked up on Hardcover, then Seekquel, then the feed's own finds, then Google
     * Books), the sector's best unread stories, and a highlight of yours from there.
     */
    fun claimProbe(store: Store, now: Long = System.currentTimeMillis() / 1000) {
        val snap = store.snapshot
        val sky = snap.sffSky
        if (!SffChart.returned(sky, now)) return
        val sec = Sector.byId(sky.probeSector)
        val (sg, query) = SffChart.probeTarget(sky, sec)
        val have = snap.books.values.map { Matcher.baseTitle(it.displayTitle) }.toSet()
        val found = ArrayList<SffFeedItem>()
        val hc = snap.service(HardcoverTracker.id)
        if (hc.linked && !hc.needsReauth) {
            found += runCatching { HardcoverTracker.discoverBooks(Session(hc.credential), query, limit = 8, now = now) }.getOrDefault(emptyList())
        }
        val sq = snap.service(SeekquelTracker.id)
        if (found.none { Matcher.baseTitle(it.title) !in have } && sq.linked && !sq.needsReauth) {
            found += runCatching { SeekquelTracker.search(Session(sq.credential), query, null) }.getOrDefault(emptyList()).take(6).map { rb ->
                SffFeedItem(
                    id = "sq-probe-${rb.id}", category = "DISCOVERY", source = "Seekquel", title = rb.title,
                    subtitle = listOfNotNull(rb.author, rb.year?.toString()).joinToString(" · "), coverUrl = rb.coverUrl,
                    author = rb.author, pages = rb.pages, year = rb.year, tags = listOf(sg.name), externalId = rb.id, publishedAt = now,
                )
            }
        }
        if (found.none { Matcher.baseTitle(it.title) !in have }) {
            found += snap.sffItems.filter { it.category == "DISCOVERY" && sec in SffChart.sectorsOf(it) }
        }
        if (found.none { Matcher.baseTitle(it.title) !in have }) {
            found += runCatching { SffSources.fetchGoogleBooksSff(query, limit = 5, now = now) }.getOrDefault(emptyList())
        }
        val book = found.firstOrNull { Matcher.baseTitle(it.title) !in have && SffSources.isEnglish(it) }
            ?.let { it.copy(id = "probe-" + it.id, matchedLibraryBook = "Found in ${sec.title} · ${sg.name}", tags = (listOf(sg.name) + it.tags).distinct().take(5)) }
        store.update { st -> if (SffChart.returned(st.sffSky, now)) st.copy(sffSky = SffChart.completeProbe(st, now, book)) else st }
    }

    /**
     * Refreshes the unified SFF feed from all sources in parallel, annotating items that match
     * the reader's authors, books, or series, and flagging potential spoilers.
     */
    fun refresh(store: Store, force: Boolean = false, now: Long = System.currentTimeMillis() / 1000): List<SffFeedItem> {
        val snap = store.snapshot
        if (!force && snap.sffItems.isNotEmpty() && now - snap.sffUpdatedAt < REFRESH_INTERVAL_SEC) {
            return snap.sffItems
        }
        store.update { it.copy(sffLoading = true, sffError = null) }

        val pool = Executors.newFixedThreadPool(8)
        // Book finds change slowly: looked for every few hours; the news every time.
        val discover = force || now - (snap.sffSky.health[DISCOVERY_KEY]?.okAt ?: 0L) >= DISCOVERY_INTERVAL_SEC
        val health = java.util.concurrent.ConcurrentHashMap<String, Int>()
        try {
            // Gather reader profile: active series, highest book number per series, favorite authors, top tropes/moods
            val libraryTitles = snap.books.values.map { Matcher.baseTitle(it.displayTitle) }.filter { it.length >= 3 }.toSet()
            val seriesMaxBook = LinkedHashMap<String, Double>()
            val seriesDisplayName = LinkedHashMap<String, String>()
            for ((doc, f) in snap.facts) {
                val sName = f.series?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                val key = Matcher.baseTitle(sName)
                if (key.isEmpty()) continue
                seriesDisplayName.putIfAbsent(key, sName)
                val n = f.seriesNumber?.toDoubleOrNull() ?: 1.0
                val prev = seriesMaxBook[key] ?: 0.0
                if (n > prev) seriesMaxBook[key] = n
            }
            // Also detect series from book titles like "Title (Series, #2)"
            for (b in snap.books.values) {
                val t = b.title ?: continue
                val m = Regex("\\(([^(),]+?)(?:,\\s*|\\s+)(?:Book\\s+|Vol\\.?\\s+|#)(\\d+(?:\\.\\d+)?)\\)", RegexOption.IGNORE_CASE).find(t) ?: continue
                val sName = m.groupValues[1].trim()
                val num = m.groupValues[2].toDoubleOrNull() ?: continue
                val key = Matcher.baseTitle(sName)
                if (key.length >= 3) {
                    seriesDisplayName.putIfAbsent(key, sName)
                    seriesMaxBook[key] = maxOf(seriesMaxBook[key] ?: 0.0, num)
                }
            }

            val topAuthors = snap.books.values
                .filter { !it.held && !it.author.isNullOrBlank() }
                .groupBy { it.author!!.substringBefore(",").trim() }
                .entries
                .sortedByDescending { (_, list) -> list.count { it.status == ReadStatus.FINISHED || it.status == ReadStatus.READING } * 2 + list.size }
                .map { it.key }
                .filter { it.length >= 4 }
                .take(6)

            val topTags = snap.facts.values
                .flatMap { it.genres + it.tropes + it.moods }
                .groupingBy { it.trim() }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .map { it.key }
                .filter { it.isNotEmpty() }
                .take(6)

            // Rotating 3-hour window seed so every 2-3 hours a fresh set of discovery queries runs
            val windowSeed = ((now / 10_800L) % 6).toInt()
            val rotatingSffQueries = listOf(
                listOf("progression fantasy", "space opera", "first contact"),
                listOf("hard science fiction", "grimdark fantasy", "time loop"),
                listOf("cyberpunk", "cosmic horror", "epic fantasy"),
                listOf("litrpg dungeon", "weird fiction", "dystopian science fiction"),
                listOf("cultivation fantasy", "artificial intelligence sci-fi", "sword and sorcery"),
                listOf("time travel fiction", "post-apocalyptic", "mythic fantasy"),
            )[windowSeed]

            val tasks = ArrayList<Future<List<SffFeedItem>>>()

            // Hardcover, Seekquel and StoryGraph aren't news sources: they're your trackers. (The probe
            // still looks there for a book to bring back.)

            // News only: your library's web novels and manga, AniList and Royal Road's lists are
            // books, not news, so they aren't looked at here.

            // 6. The vetted news sources (magazines, publishers, awards, adaptations, authors, manga),
            // a few to a thread so they're read side by side; each one's result kept for its health.
            for (group in SffSources.newsFeeds.chunked(3)) {
                tasks += pool.submit<List<SffFeedItem>> {
                    group.flatMap { feed ->
                        val got = runCatching { SffSources.fetchRss(feed, limit = 6, now = now) }.getOrDefault(emptyList())
                        health[feed.name] = got.size
                        got
                    }
                }
            }

            // 7. SFF Community Pulse (7 Subreddits: r/Fantasy, r/PrintSF, r/ProgressionFantasy, r/litrpg, r/horrorlit, r/scifi, r/WeirdLit)
            // Reddit turns away quick runs of requests: four subreddits each hour, in turn, one at a
            // time and a moment apart (what each gave stays in the feed for days).
            val subs = SffSources.subreddits
            val first = ((now / REFRESH_INTERVAL_SEC) % subs.size).toInt()
            val turn = (0 until minOf(4, subs.size)).map { subs[(first + it) % subs.size] }
            tasks += pool.submit<List<SffFeedItem>> {
                turn.flatMapIndexed { i, (sub, tags) ->
                    if (i > 0) Thread.sleep(2_500)
                    val got = runCatching { SffSources.fetchSubreddit(sub, tags, limit = 6, now = now) }.getOrDefault(emptyList())
                    health["r/$sub"] = got.size
                    got
                }
            }

            // 9. Social Pulse: X (Twitter), Instagram, Bluesky (#BookSky), Mastodon (wandering.shop), YouTube BookTube, Threads, Tumblr & Substacks
            tasks += pool.submit<List<SffFeedItem>> {
                io.github.prithirajn.readync.source.SffSocials.fetchAllSocials(
                    topAuthors = topAuthors,
                    customHandles = snap.settings.sffSocials,
                    windowSeed = windowSeed,
                    now = now,
                )
            }

            // Every source within one time limit, so a slow site can't hold the sync up.
            val deadline = System.currentTimeMillis() + FETCH_BUDGET_MS
            val rawItems = tasks.flatMap { f ->
                runCatching { f.get((deadline - System.currentTimeMillis()).coerceAtLeast(1), java.util.concurrent.TimeUnit.MILLISECONDS) }
                    .getOrElse { f.cancel(true); emptyList() }
            } + releaseItems(snap, now)
            val deduped = rawItems
                .map { annotateWithLibrary(it, snap, seriesDisplayName, seriesMaxBook) }
                .distinctBy { "${it.category}|${Matcher.baseTitle(it.title)}|${it.source}" }

            // Run Artifact clustering & canonical tagging so items carry enriched tags & personalized scores
            val clusters = SffArtifactAi.clusterAndRank(deduped, snap, now)
            val aiTopIds = if (discover && snap.settings.aiMatching) runCatching { SffArtifactAi.aiCurateTopPicks(snap, clusters) }.getOrNull().orEmpty() else emptySet()

            val enrichedById = HashMap<String, SffFeedItem>()
            for (c in clusters) {
                val aiBoosted = c.lead.id in aiTopIds
                for (p in c.perspectives) {
                    val mergedTags = (c.canonicalTags + p.tags).distinctBy { it.lowercase() }.take(7)
                    val matchLine = when {
                        aiBoosted && p.id == c.lead.id -> "✨ On-Device AI Pick · " + (p.matchedLibraryBook ?: c.topicBadge)
                        p.matchedLibraryBook != null -> p.matchedLibraryBook
                        else -> null
                    }
                    enrichedById[p.id] = p.copy(
                        tags = mergedTags,
                        matchedLibraryBook = matchLine,
                        artifactScore = c.score + (if (aiBoosted) 8.0 else 0.0),
                    )
                }
            }
            val fresh = deduped.map { enrichedById[it.id] ?: it }
            var kept: List<SffFeedItem> = emptyList()
            store.update { s ->
                // New stories join what's already here (kept for a few days), each keeping when it was first seen
                // and what you've had done to it (a rewritten headline, a summary).
                val old = s.sffItems.associateBy { it.id }
                val merged = fresh.map { n ->
                    val o = old[n.id]
                    n.copy(
                        firstSeenAt = o?.firstSeenAt?.takeIf { it > 0 } ?: now,
                        aiHeadline = n.aiHeadline ?: o?.aiHeadline,
                        aiBullets = n.aiBullets.ifEmpty { o?.aiBullets.orEmpty() },
                        fullContent = if ((o?.fullContent?.length ?: 0) > (n.fullContent?.length ?: 0)) o?.fullContent else n.fullContent,
                    )
                }
                val ids = merged.map { it.id }.toSet()
                val carried = s.sffItems.filter { it.id !in ids && now - (it.firstSeenAt.takeIf { f -> f > 0 } ?: it.publishedAt) < KEEP_SEC }
                kept = (merged + carried)
                    .filter { SffSources.isNews(it) && SffSources.isEnglish(it) }
                    .distinctBy { it.id }
                    .distinctBy { "${it.category}|${Matcher.baseTitle(it.title)}|${it.source}" }
                    .sortedByDescending { it.firstSeenAt.coerceAtLeast(it.publishedAt) }
                    .take(MAX_ITEMS)
                val h = HashMap(s.sffSky.health)
                for ((name, n) in health) {
                    val was = h[name] ?: io.github.prithirajn.readync.data.SffSourceHealth()
                    h[name] = if (n > 0) was.copy(okAt = now, items = n) else was.copy(failAt = now)
                }
                if (discover) h[DISCOVERY_KEY] = io.github.prithirajn.readync.data.SffSourceHealth(okAt = now)
                s.copy(
                    sffItems = kept,
                    sffUpdatedAt = now,
                    sffLoading = false,
                    sffError = if (kept.isEmpty()) "Couldn't reach SFF sources right now; check your connection and try again." else null,
                    sffSky = SffChart.tick(s.copy(sffSky = s.sffSky.copy(health = h)), now),
                )
            }
            return kept
        } catch (e: Exception) {
            store.update { s ->
                s.copy(
                    sffLoading = false,
                    sffError = e.message ?: "Couldn't refresh SFF feeds",
                )
            }
            return store.snapshot.sffItems
        } finally {
            pool.shutdownNow()
        }
    }

    /** Your trackers: their books aren't news, so they're kept out of the feed. */
    val TRACKER_SOURCES = setOf("Hardcover", "Seekquel", "StoryGraph", "Goodreads", "Fable", "Pagebound")

    /** Not a source: when book finds were last looked for. */
    const val DISCOVERY_KEY = "_discovery"

    /**
     * Your own series' news, from New & upcoming, as stories: the next books coming (and out in
     * the past two weeks), linked to the book of yours they follow.
     */
    internal fun releaseItems(s: Snapshot, now: Long): List<SffFeedItem> = s.releases.values.mapNotNull { r ->
        if (r.kind != "book" && r.kind != "volume") return@mapNotNull null
        if (r.date != null && r.date < now - 14 * 86_400L) return@mapNotNull null
        val base = Matcher.baseTitle(r.series)
        val doc = s.books.values.firstOrNull { b -> s.facts[b.document]?.series?.let { Matcher.baseTitle(it) == base } == true }?.document
        val day = r.date?.let { java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
        val out = r.isOut(now)
        SffFeedItem(
            id = "rel-" + r.key.hashCode().toUInt().toString(16),
            category = "SERIES_HORIZON",
            source = "New & upcoming",
            title = if (out) "Out now: ${r.title}" else "Coming ${day?.let { java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy").format(it) } ?: "soon"}: ${r.title}",
            subtitle = listOfNotNull(r.number?.let { "#$it in ${r.series}" } ?: r.series, r.author).joinToString(" · "),
            summary = "The next in ${r.series}" + (r.author?.let { " by $it" } ?: "") + (if (out) ", out now." else "."),
            url = r.url, coverUrl = r.coverUrl, author = r.author, series = r.series, seriesNumber = r.number,
            releaseDate = day?.toString(), tags = listOf("Next in your series"),
            matchedLibraryBook = "Next in your series: ${r.series}", matchedDocument = doc,
            publishedAt = if (out) (r.date ?: r.seenAt) else r.seenAt,
        )
    }

    /**
     * Checks whether an SFF article, Reddit thread, or serial update mentions a book, author,
     * or series in the user's library, and sets [SffFeedItem.matchedLibraryBook] and
     * [SffFeedItem.spoilerWarning] when it references a book ahead of their current progress.
     */
    private fun annotateWithLibrary(
        item: SffFeedItem,
        snap: Snapshot,
        seriesDisplayName: Map<String, String>,
        seriesMaxBook: Map<String, Double>,
    ): SffFeedItem {
        if (item.matchedLibraryBook != null) return item
        val haystack = "${item.title} ${item.subtitle.orEmpty()} ${item.summary.orEmpty()}".lowercase()

        // 1. Check active series names
        for ((sKey, sName) in seriesDisplayName) {
            if (sName.length >= 4 && haystack.contains(sName.lowercase())) {
                val maxRead = seriesMaxBook[sKey] ?: 1.0
                val doc = snap.facts.entries.firstOrNull { (d, f) -> f.series?.let { Matcher.baseTitle(it) == sKey } == true && snap.books[d]?.held == false }?.key
                val mentionedNum = Regex("(?:book|vol\\.?|#)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(haystack)?.groupValues?.get(1)?.toDoubleOrNull()
                val spoiler = item.spoilerWarning || (mentionedNum != null && mentionedNum > maxRead)
                return item.copy(
                    matchedLibraryBook = "Mentions your series: $sName",
                    spoilerWarning = spoiler,
                    matchedDocument = item.matchedDocument ?: doc,
                )
            }
        }

        // 2. Check library authors and book titles
        for (b in snap.books.values) {
            if (b.held) continue
            val author = b.author?.substringBefore(",")?.trim()
            if (author != null && author.length >= 5 && haystack.contains(author.lowercase())) {
                return item.copy(matchedLibraryBook = "Mentions author in your library: $author", matchedDocument = item.matchedDocument ?: b.document)
            }
            val cleanTitle = b.displayTitle.substringBefore(":").trim()
            if (cleanTitle.length >= 6 && haystack.contains(cleanTitle.lowercase())) {
                val spoiler = item.spoilerWarning || (b.status != ReadStatus.FINISHED && b.percent < 100 && item.category == "COMMUNITY")
                return item.copy(
                    matchedLibraryBook = "Mentions ${b.displayTitle}",
                    spoilerWarning = spoiler,
                    matchedDocument = item.matchedDocument ?: b.document,
                )
            }
        }
        return item
    }
}
