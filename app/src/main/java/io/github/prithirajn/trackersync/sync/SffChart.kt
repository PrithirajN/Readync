package io.github.prithirajn.trackersync.sync

import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.BookFacts
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.SffConstellation
import io.github.prithirajn.trackersync.data.SffExpedition
import io.github.prithirajn.trackersync.data.SffFeedItem
import io.github.prithirajn.trackersync.data.SffRead
import io.github.prithirajn.trackersync.data.SffSky as Sky
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.source.SffSources
import io.github.prithirajn.trackersync.sync.SffRadar.Sector
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The one taste model behind the SFF tab, and the Star Chart that shows it.
 *
 * The five sectors are the feed's topics. What you read, books and stories alike, builds your
 * strength in each sector, and that strength steers what the feed shows you. Stories you read
 * glow on the chart for a week; books stay; news about your books draws a line to them; the next
 * books in your series drift in as comets. The Astral Probe is charged by reading, goes out for an
 * hour into a sector you choose, and comes back with a briefing that reveals more of that sector.
 * XP comes only from things that mean something (finishing books, reading stories through,
 * exploring), with a daily cap on what stories can earn.
 */
object SffChart {
    const val DAY = 86_400L
    /** What stories, probes and exploring can earn in a day (books and expeditions aren't capped). */
    const val XP_DAILY_CAP = 250
    const val PROBE_TRAVEL_SEC = 3_600L
    /** About 17 pages, or three stories read through, fill the probe. */
    const val CHARGE_PER_PAGE = 6
    const val CHARGE_PER_STORY = 35
    /** Reading a story this long counts as reading it through. */
    const val READ_THROUGH_SEC = 40
    const val NEWS_STAR_DAYS = 7
    const val EXPEDITION_XP = 100
    const val PROBE_XP = 30

    data class SubGenre(val name: String, val words: List<String>)

    /** Each sector's sub-genres: hidden on the chart until something you read reveals them. */
    val SUBGENRES: Map<Sector, List<SubGenre>> = mapOf(
        Sector.ORBIT to listOf(
            SubGenre("Space opera", listOf("space opera", "galactic", "starship", "empire", "interstellar")),
            SubGenre("Hard SF", listOf("hard sf", "hard science fiction", "physics", "astrophysics")),
            SubGenre("First contact", listOf("first contact", "alien", "aliens", "xeno")),
            SubGenre("Cyberpunk", listOf("cyberpunk", "hacker", "megacorp", "neon")),
            SubGenre("Military SF", listOf("military sf", "military science fiction", "marines", "fleet")),
            SubGenre("AI & robots", listOf("artificial intelligence", "robot", "robots", "android", "murderbot", "ai")),
        ),
        Sector.CITADEL to listOf(
            SubGenre("Epic fantasy", listOf("epic fantasy", "high fantasy", "cosmere", "stormlight", "wheel of time", "malazan")),
            SubGenre("Grimdark", listOf("grimdark", "first law", "abercrombie", "dark fantasy")),
            SubGenre("Sword & sorcery", listOf("sword and sorcery", "sword & sorcery", "conan", "barbarian")),
            SubGenre("Mythic", listOf("mythic", "mythology", "myth", "gods", "retelling")),
            SubGenre("Urban fantasy", listOf("urban fantasy", "fae", "witches", "vampire")),
            SubGenre("Romantasy", listOf("romantasy", "fantasy romance", "fae romance")),
        ),
        Sector.SPIRE to listOf(
            SubGenre("Progression", listOf("progression", "progression fantasy", "cradle")),
            SubGenre("LitRPG", listOf("litrpg", "gamelit", "dungeon crawler", "system")),
            SubGenre("Cultivation", listOf("cultivation", "xianxia", "wuxia")),
            SubGenre("Dungeons", listOf("dungeon", "dungeon core", "tower")),
            SubGenre("Time loops", listOf("time loop", "groundhog", "mother of learning")),
            SubGenre("Isekai & portals", listOf("isekai", "portal fantasy", "reincarnation", "transported")),
        ),
        Sector.VOID to listOf(
            SubGenre("Cosmic horror", listOf("cosmic horror", "eldritch", "lovecraft", "lovecraftian")),
            SubGenre("New weird", listOf("new weird", "weird fiction", "vandermeer", "area x")),
            SubGenre("Gothic", listOf("gothic", "haunted house", "mansion")),
            SubGenre("Folk horror", listOf("folk horror", "pagan", "village")),
            SubGenre("Ghost stories", listOf("ghost", "ghosts", "haunting", "haunted")),
            SubGenre("Body horror", listOf("body horror", "flesh", "transformation")),
        ),
        Sector.CHRONOS to listOf(
            SubGenre("Time travel", listOf("time travel", "time traveler", "timeline")),
            SubGenre("Dystopian", listOf("dystopia", "dystopian", "totalitarian", "surveillance")),
            SubGenre("Post-apocalyptic", listOf("post-apocalyptic", "apocalypse", "apocalyptic", "wasteland")),
            SubGenre("Alternate history", listOf("alternate history", "alt-history", "what if")),
            SubGenre("Solarpunk", listOf("solarpunk", "hopepunk", "climate fiction", "cli-fi")),
            SubGenre("Steampunk", listOf("steampunk", "gaslamp", "clockwork")),
        ),
    )

    private val CONSTELLATION_NAMES: Map<Sector, List<String>> = mapOf(
        Sector.ORBIT to listOf("The Navigator", "Ark of Stars", "The Long Signal", "Dyson's Crown", "The Silent Fleet", "Lagrange Lantern", "The Outward Spiral", "Event Horizon"),
        Sector.CITADEL to listOf("The Crowned Wyrm", "Oathbreaker", "The Ninth Sword", "Hearthfire", "The Shattered Throne", "Runebearer", "The Last Bastion", "Dragon's Wake"),
        Sector.SPIRE to listOf("The Ascendant", "Ninefold Path", "The Iron Floor", "Core of Ten", "The Looping Hour", "Skyward Ladder", "The Unbroken Rank", "Summit Flame"),
        Sector.VOID to listOf("The Drowned Eye", "Lantern of Teeth", "The Hollow Choir", "Unwritten Door", "The Pale Host", "Moth Cathedral", "The Endless Stair", "Grey Tide"),
        Sector.CHRONOS to listOf("The Clockmaker", "Second Dawn", "The Paradox Wheel", "Ashfall", "The Green Engine", "Hourglass Gate", "The Rewound Sky", "Last Calendar"),
    )

    /** "#" sectors a source mostly covers, beyond the vetted list's own. */
    private fun sourceSector(source: String): Sector? {
        val s = source.lowercase()
        SffSources.newsFeeds.firstOrNull { it.name.equals(source, true) || it.name.equals(source.substringBefore(" ·"), true) }
            ?.sector?.let { return Sector.byId(it) }
        return when {
            "progressionfantasy" in s || "litrpg" in s || "royal road" in s -> Sector.SPIRE
            "horrorlit" in s || "weirdlit" in s -> Sector.VOID
            "printsf" in s || "r/scifi" in s -> Sector.ORBIT
            s == "r/fantasy" -> Sector.CITADEL
            else -> null
        }
    }

    /** A source's standing before your own taste: vetted publishers count more than open posts. */
    fun sourceWeight(source: String): Double {
        SffSources.newsFeeds.firstOrNull { it.name.equals(source, true) || it.name.equals(source.substringBefore(" ·"), true) }
            ?.let { return it.weight }
        val s = source.lowercase()
        return when {
            s.startsWith("new & upcoming") -> 1.6
            s.startsWith("r/") -> 0.8
            "royal road" in s -> 0.8
            s.startsWith("hardcover") || s.startsWith("seekquel") || s.startsWith("storygraph") -> 0.9
            s.startsWith("youtube") || s.startsWith("bluesky") || s.startsWith("x ·") || s.startsWith("instagram") || s.startsWith("mastodon") -> 0.85
            else -> 0.9
        }
    }

    private val wordRx = ConcurrentHashMap<String, Regex>()
    private fun has(text: String, word: String): Boolean =
        wordRx.getOrPut(word) { Regex("(?<![a-z0-9])" + Regex.escape(word) + "(?![a-z0-9])") }.containsMatchIn(text)

    /** How strongly [text] (lower case) belongs to each sector. */
    fun sectorScores(text: String): Map<Sector, Int> = Sector.entries.associateWith { sec ->
        sec.keywords.count { has(text, it) } + SUBGENRES[sec].orEmpty().sumOf { sg -> sg.words.count { has(text, it) } }
    }

    private fun pick(scores: Map<Sector, Int>): List<Sector> {
        val top = scores.values.maxOrNull() ?: 0
        if (top <= 0) return emptyList()
        return scores.entries.filter { it.value > 0 && it.value * 10 >= top * 6 }.sortedByDescending { it.value }.take(2).map { it.key }
    }

    /** A story's sectors (one or two), from what it says and where it's from; empty for general news. */
    fun sectorsOf(item: SffFeedItem): List<Sector> {
        val text = listOfNotNull(item.title, item.subtitle, item.summary?.take(400), item.series, item.tags.joinToString(" "))
            .joinToString(" ").lowercase()
        return pick(sectorScores(text)).ifEmpty { listOfNotNull(sourceSector(item.source)) }
    }

    /** A book's sectors from its tags; empty means uncharted (not enough known yet). */
    fun sectorsOfBook(book: Book, facts: BookFacts?, isWebNovel: Boolean): List<Sector> {
        val text = buildList {
            addAll(facts?.genres.orEmpty()); addAll(facts?.moods.orEmpty()); addAll(facts?.tropes.orEmpty())
            addAll(book.titleTags); book.title?.let { add(it) }; book.description?.let { add(it.take(300)) }
        }.joinToString(" ").lowercase()
        val found = pick(sectorScores(text))
        return if (found.isEmpty() && isWebNovel) listOf(Sector.SPIRE) else found
    }

    private fun sectorsOf(s: Snapshot, b: Book) = sectorsOfBook(b, s.facts[b.document], s.webNovels.containsKey(b.document))

    fun today(now: Long): String = Instant.ofEpochSecond(now).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    fun week(now: Long): String {
        val d = Instant.ofEpochSecond(now).atZone(ZoneId.systemDefault()).toLocalDate()
        return "${d.get(IsoFields.WEEK_BASED_YEAR)}-W${d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR).toString().padStart(2, '0')}"
    }

    // ---- XP and levels ----

    /** XP each sector has from your books: finishing counts most, reading so far counts some. */
    fun bookXp(s: Snapshot): Map<Sector, Int> {
        val out = HashMap<Sector, Int>()
        for (b in s.books.values) {
            if (b.held) continue
            val pages = b.pageCount ?: b.pagesRead ?: 320
            val xp = when (b.status) {
                ReadStatus.FINISHED -> 45 + min(pages / 25, 40)
                ReadStatus.READING, null -> if (b.percent > 0) 10 + b.percent / 5 else 0
                ReadStatus.PAUSED, ReadStatus.DNF -> if (b.percent > 0) 5 + b.percent / 10 else 0
                ReadStatus.TO_READ -> 0
            }
            if (xp == 0) continue
            val secs = sectorsOf(s, b)
            if (secs.isEmpty()) continue
            for ((i, sec) in secs.withIndex()) out[sec] = (out[sec] ?: 0) + if (i == 0) xp - xp / secs.size * (secs.size - 1) else xp / secs.size
        }
        return out
    }

    fun sectorXp(s: Snapshot): Map<Sector, Int> {
        val books = bookXp(s)
        return Sector.entries.associateWith { (books[it] ?: 0) + (s.sffSky.xp[it.id] ?: 0) }
    }

    /** The XP a level starts at: 0, 100, 300, 600, 1000… */
    fun levelStart(level: Int): Int = 50 * level * (level - 1)
    fun levelOf(xp: Int): Int = floor((1 + sqrt(1 + 8.0 * xp / 100)) / 2).toInt().coerceAtLeast(1)

    fun rankName(xp: Int): String = when {
        xp >= 600 -> "Archon"
        xp >= 350 -> "Lorekeeper"
        xp >= 180 -> "Chronicler"
        xp >= 75 -> "Scout"
        else -> "Initiate"
    }

    /** Adds [xp] to [sectors], within the day's cap unless [capped] is false. */
    fun earn(sky: Sky, sectors: List<Sector>, xp: Int, now: Long, capped: Boolean = true): Sky {
        val day = today(now)
        val used = if (sky.xpDay == day) sky.xpToday else 0
        val give = if (capped) min(xp, XP_DAILY_CAP - used).coerceAtLeast(0) else xp
        if (sectors.isEmpty() || give <= 0) return sky.copy(xpDay = day, xpToday = used)
        val next = HashMap(sky.xp)
        val each = give / sectors.size
        for ((i, sec) in sectors.withIndex()) next[sec.id] = (next[sec.id] ?: 0) + if (i == 0) give - each * (sectors.size - 1) else each
        return sky.copy(xp = next, xpDay = day, xpToday = used + if (capped) give else 0)
    }

    /**
     * Your taste in each sector, 0 to 1 (the strongest is 1): your books, the XP you've earned,
     * and what you've read in the past month. The feed ranks by it.
     */
    fun strengths(s: Snapshot, now: Long): Map<Sector, Double> {
        val raw = HashMap<Sector, Double>()
        for ((sec, xp) in bookXp(s)) raw[sec] = (raw[sec] ?: 0.0) + xp
        for ((id, xp) in s.sffSky.xp) Sector.entries.firstOrNull { it.id == id }?.let { raw[it] = (raw[it] ?: 0.0) + xp * 0.8 }
        for (h in s.sffSky.history) {
            if (now - h.at > 30 * DAY) continue
            val w = min(h.seconds, 300) / 60.0 * 3
            for (id in h.sectors) Sector.entries.firstOrNull { it.id == id }?.let { raw[it] = (raw[it] ?: 0.0) + w / h.sectors.size }
        }
        val top = raw.values.maxOrNull() ?: 0.0
        return Sector.entries.associateWith { if (top <= 0) 0.0 else (raw[it] ?: 0.0) / top }
    }

    // ---- revealing sub-genres ----

    /** [sky] with the sub-genres [text] (lower case) mentions revealed; and which were new. */
    fun reveal(sky: Sky, text: String, only: Collection<Sector>? = null): Pair<Sky, List<String>> {
        val next = sky.revealed.toMutableMap()
        val fresh = ArrayList<String>()
        for ((sec, subs) in SUBGENRES) {
            if (only != null && sec !in only) continue
            val have = next[sec.id].orEmpty()
            val add = subs.filter { sg -> sg.name !in have && sg.words.any { has(text, it) } }.map { it.name }
            if (add.isNotEmpty()) { next[sec.id] = have + add; fresh += add }
        }
        return (if (fresh.isEmpty()) sky else sky.copy(revealed = next)) to fresh
    }

    private fun revealFromBooks(s: Snapshot, sky: Sky): Sky {
        var out = sky
        for (b in s.books.values) {
            if (b.held || (b.status != ReadStatus.FINISHED && b.percent <= 0)) continue
            val f = s.facts[b.document]
            val text = (f?.genres.orEmpty() + f?.tropes.orEmpty() + b.titleTags + listOfNotNull(b.title)).joinToString(" ").lowercase()
            out = reveal(out, text, sectorsOf(s, b)).first
        }
        return out
    }

    // ---- the regular update ----

    private fun pagesOf(b: Book): Int = b.pagesRead ?: b.pageCount?.let { (it * b.percent / 100.0).toInt() } ?: 0

    /**
     * Brings the sky up to date: reading since last time charges the probe, books reveal their
     * sub-genres, and this week's expedition moves on (or a new one starts).
     */
    fun tick(s: Snapshot, now: Long): Sky {
        var sky = s.sffSky
        val total = s.pagesTotal ?: Shelves.pagesRead(s.books.values) ?: 0
        sky = when {
            sky.pagesMark < 0 || total < sky.pagesMark -> sky.copy(pagesMark = total)
            total > sky.pagesMark -> sky.copy(charge = (sky.charge + (total - sky.pagesMark) * CHARGE_PER_PAGE).coerceAtMost(100), pagesMark = total)
            else -> sky
        }
        sky = revealFromBooks(s, sky)
        return expedition(s, sky, now)
    }

    /** A story read for [seconds] in the reader: history, XP and charge once it's read through, and the expedition. */
    fun recordRead(s: Snapshot, item: SffFeedItem, seconds: Int, now: Long): Sky {
        val secs = sectorsOf(item)
        var sky = s.sffSky
        val prev = sky.history.firstOrNull { it.id == item.id }
        val total = (prev?.seconds ?: 0) + seconds.coerceAtLeast(0)
        val entry = SffRead(item.id, item.aiHeadline ?: item.title, item.source, secs.map { it.id }, now, total, item.coverUrl, item.url, item.matchedDocument)
        sky = sky.copy(history = (listOf(entry) + sky.history.filter { it.id != item.id }).take(400))
        val through = (prev?.seconds ?: 0) < READ_THROUGH_SEC && total >= READ_THROUGH_SEC
        if (through) {
            val strengths = strengths(s, now)
            val exploring = secs.any { sec ->
                (strengths[sec] ?: 0.0) < 0.3 && s.sffSky.history.none { h -> h.id != item.id && sec.id in h.sectors && now - h.at < 7 * DAY }
            }
            sky = earn(sky, secs, 10 + if (exploring) 20 else 0, now)
            sky = sky.copy(charge = (sky.charge + CHARGE_PER_STORY).coerceAtMost(100))
            sky.expedition?.let { e -> if (!e.done && secs.any { it.id == e.sector }) sky = sky.copy(expedition = e.copy(articles = e.articles + 1)) }
            val text = listOfNotNull(item.title, item.summary, item.tags.joinToString(" ")).joinToString(" ").lowercase()
            sky = reveal(sky, text, secs.ifEmpty { null }).first
        } else if (prev == null && seconds >= 10) {
            sky = sky.copy(charge = (sky.charge + 10).coerceAtMost(100))
        }
        return expedition(s, sky, now)
    }

    // ---- expeditions ----

    /** This week's expedition, started if there isn't one, its pages counted, and finished when both goals are met. */
    fun expedition(s: Snapshot, sky0: Sky, now: Long): Sky {
        var sky = sky0
        val wk = week(now)
        var e = sky.expedition
        if (e == null || e.week != wk) {
            e = newExpedition(s, sky, now)
            sky = sky.copy(expedition = e)
        }
        if (e.done) return sky
        val sec = Sector.byId(e.sector)
        var pages = 0
        for (b in s.books.values) {
            if (b.held || sec !in sectorsOf(s, b)) continue
            val start = e.pagesStart[b.document]
            pages += when {
                start != null -> (pagesOf(b) - start).coerceAtLeast(0)
                (b.startedAt ?: 0L) >= e.startedAt -> pagesOf(b)
                else -> 0
            }
        }
        e = e.copy(pages = pages)
        if (e.articles >= e.articlesGoal && e.pages >= e.pagesGoal) {
            e = e.copy(doneAt = now)
            val names = CONSTELLATION_NAMES[sec].orEmpty()
            val taken = sky.constellations.map { it.name }.toSet()
            val name = names.firstOrNull { it !in taken } ?: "${names.firstOrNull() ?: sec.title} ${sky.constellations.count { it.sector == sec.id } + 1}"
            sky = earn(sky.copy(expedition = e, constellations = sky.constellations + SffConstellation(name, sec.id, now)), listOf(sec), EXPEDITION_XP, now, capped = false)
            return sky
        }
        return sky.copy(expedition = e)
    }

    /** A new week's expedition, into one of your two weakest sectors that aren't muted (you can read toward it in a book). */
    fun newExpedition(s: Snapshot, sky: Sky, now: Long): SffExpedition {
        val strengths = strengths(s, now)
        val withBooks = s.books.values.filter { !it.held }.flatMap { sectorsOf(s, it) }.toSet()
        val choices = Sector.entries.filter { it.id !in sky.mutedSectors }.ifEmpty { Sector.entries }
            .sortedWith(compareBy<Sector> { if (it in withBooks) 0 else 1 }.thenBy { strengths[it] ?: 0.0 })
        val wk = week(now)
        val sec = choices.take(2).let { it[(wk.hashCode() and 0x7fffffff) % it.size] }
        val hasBooks = sec in withBooks
        val start = s.books.values.filter { !it.held && sec in sectorsOf(s, it) }.associate { it.document to pagesOf(it) }
        return SffExpedition(
            week = wk, sector = sec.id, articlesGoal = if (hasBooks) 3 else 4, pagesGoal = if (hasBooks) 60 else 0,
            startedAt = now, pagesStart = start,
        )
    }

    // ---- the Astral Probe ----

    fun canLaunch(sky: Sky): Boolean = sky.charge >= 100 && sky.probeSector == null
    fun returned(sky: Sky, now: Long): Boolean = sky.probeSector != null && now >= sky.probeReturnsAt

    fun launch(sky: Sky, sector: Sector, now: Long): Sky =
        if (!canLaunch(sky)) sky else sky.copy(charge = 0, probeSector = sector.id, probeLaunchedAt = now, probeReturnsAt = now + PROBE_TRAVEL_SEC)

    /** What the probe looks for in [sector]: a sub-genre you haven't revealed yet (else the next in turn), and a search for it. */
    fun probeTarget(sky: Sky, sector: Sector): Pair<SubGenre, String> {
        val subs = SUBGENRES[sector].orEmpty()
        val have = sky.revealed[sector.id].orEmpty()
        val sg = subs.firstOrNull { it.name !in have } ?: subs[sky.probes % subs.size]
        return sg to "${sg.words.first()} ${if (sector == Sector.CITADEL || sector == Sector.SPIRE) "fantasy" else "novel"}"
    }

    /** Stories in [sector] you haven't read, best first (for the briefing). */
    fun briefingStories(s: Snapshot, sector: Sector, now: Long, max: Int = 3): List<SffFeedItem> {
        val read = s.sffSky.history.map { it.id }.toSet() + s.sffProfile.readIds
        val before = s.sffSky.briefing.map { it.id }.toSet()
        return s.sffItems.asSequence()
            .filter { it.id !in read && it.id !in before && sector in sectorsOf(it) && SffSources.isNews(it) }
            .sortedByDescending { sourceWeight(it.source) * recency(it, now) }
            .distinctBy { Matcher.baseTitle(it.title) }
            .take(max).toList()
    }

    private fun recency(item: SffFeedItem, now: Long): Double {
        val at = item.publishedAt.takeIf { it > 0 } ?: item.firstSeenAt.takeIf { it > 0 } ?: now
        return Math.pow(0.5, ((now - at).coerceAtLeast(0) / 3600.0) / 24.0)
    }

    /** The probe's return: its briefing (stories and a [book]), a highlight from that sector, the sub-genre revealed, and XP. */
    fun completeProbe(s: Snapshot, now: Long, book: SffFeedItem?): Sky {
        val sky = s.sffSky
        val sec = Sector.byId(sky.probeSector ?: return sky)
        val (sg, _) = probeTarget(sky, sec)
        val stories = briefingStories(s, sec, now)
        val items = (stories + listOfNotNull(book)).distinctBy { it.id }.map { it.copy(tags = (listOf("Probe briefing") + it.tags).distinct(), firstSeenAt = now) }
        val echo = echoFor(s, emptyList(), sec, now / DAY + sky.probes)
        var next = sky.copy(
            probeSector = null, probeLaunchedAt = 0L, probeReturnsAt = 0L, probes = sky.probes + 1,
            briefing = items, briefingSector = sec.id, briefingAt = now,
            echoQuote = echo?.first, echoBook = echo?.second,
            revealed = sky.revealed + (sec.id to (sky.revealed[sec.id].orEmpty() + sg.name).distinct()),
        )
        next = earn(next, listOf(sec), PROBE_XP, now)
        return next
    }

    /**
     * A highlight of yours to bring back: from one of [docs] (the books a story is about), else
     * from a book in [sector], else any; the same one for the same [seed].
     */
    fun echoFor(s: Snapshot, docs: List<String>, sector: Sector?, seed: Long): Pair<String, String>? {
        val clips = (s.cpClips + s.kindleClips + s.paperClips).filter { it.id !in s.clipHidden && it.text.trim().length in 24..320 }
        if (clips.isEmpty()) return null
        val pool = clips.filter { it.document in docs }.ifEmpty {
            sector?.let { sec -> clips.filter { c -> s.books[c.document]?.let { sec in sectorsOf(s, it) } == true } }.orEmpty()
        }.ifEmpty { clips }
        val c = pool[((seed % pool.size + pool.size) % pool.size).toInt()]
        return c.text.trim() to (s.books[c.document]?.displayTitle ?: "your library")
    }

    // ---- the Star Chart ----

    data class Star(
        val document: String, val title: String, val author: String?, val sector: Sector?, val status: ReadStatus?,
        val pages: Int, val x: Float, val y: Float, val tags: List<String>, val series: String?,
    )
    data class NewsStar(val id: String, val title: String, val source: String, val sector: Sector, val x: Float, val y: Float, val glow: Float, val document: String?, val url: String?)
    data class Line(val a: String, val b: String, val reason: String, val news: Boolean)
    data class Comet(val key: String, val title: String, val series: String, val sector: Sector?, val days: Int, val x: Float, val y: Float, val coverUrl: String?, val date: Long?)
    data class SectorView(
        val sector: Sector, val xp: Int, val rank: String, val strength: Double, val explored: Float,
        val revealed: List<String>, val hidden: Int, val followed: Boolean, val muted: Boolean, val books: Int,
    )
    data class Chart(
        val level: Int, val className: String, val totalXp: Int, val levelStartXp: Int, val nextLevelXp: Int,
        val dominant: Sector, val sectors: List<SectorView>, val stars: List<Star>, val uncharted: List<Star>,
        val newsStars: List<NewsStar>, val lines: List<Line>, val comets: List<Comet>,
    )

    val ANCHORS: Map<Sector, Pair<Float, Float>> = mapOf(
        Sector.ORBIT to (0.50f to 0.17f),
        Sector.CITADEL to (0.83f to 0.40f),
        Sector.SPIRE to (0.70f to 0.80f),
        Sector.VOID to (0.30f to 0.80f),
        Sector.CHRONOS to (0.17f to 0.40f),
    )

    private fun around(anchor: Pair<Float, Float>, i: Int, ring0: Float, step: Float, cap: Float): Pair<Float, Float> {
        val ring = i / 6 + 1
        val angle = i * 1.0472f + ring * 0.52f
        val r = min(ring0 + step * ring, cap)
        return (anchor.first + cos(angle) * r).coerceIn(0.05f, 0.95f) to (anchor.second + sin(angle) * r).coerceIn(0.06f, 0.94f)
    }

    fun chart(s: Snapshot, now: Long): Chart {
        val sky = s.sffSky
        val xp = sectorXp(s)
        val total = xp.values.sum()
        val level = levelOf(total)
        val strengths = strengths(s, now)
        val dominant = xp.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: Sector.ORBIT
        val books = s.books.values.filter { !it.held && (it.status != ReadStatus.TO_READ || it.percent > 0) }.sortedByDescending { it.timestamp }.take(60)
        val perSector = HashMap<Sector, Int>()
        val stars = ArrayList<Star>()
        val uncharted = ArrayList<Star>()
        for (b in books) {
            val f = s.facts[b.document]
            val sec = sectorsOf(s, b).firstOrNull()
            val tags = (f?.genres.orEmpty() + f?.tropes.orEmpty() + b.titleTags).distinctBy { it.lowercase() }.take(5)
            val pages = b.pageCount ?: b.pagesRead ?: 320
            if (sec == null) {
                val (x, y) = around(0.5f to 0.5f, uncharted.size, 0.0f, 0.045f, 0.12f)
                uncharted += Star(b.document, b.displayTitle, b.author, null, b.status, pages, x, y, tags, f?.series)
                continue
            }
            val n = perSector[sec] ?: 0
            perSector[sec] = n + 1
            val (x, y) = around(ANCHORS.getValue(sec), n, 0.0f, 0.05f, 0.15f)
            stars += Star(b.document, b.displayTitle, b.author, sec, b.status, pages, x, y, tags, f?.series)
        }
        // Stories read this week: glowing, fading as the week goes.
        val news = ArrayList<NewsStar>()
        val newsPer = HashMap<Sector, Int>()
        for (h in sky.history) {
            val age = now - h.at
            if (age > NEWS_STAR_DAYS * DAY || h.seconds < 10) continue
            val sec = h.sectors.firstOrNull()?.let { Sector.byId(it) } ?: continue
            val i = newsPer[sec] ?: 0
            newsPer[sec] = i + 1
            val (x, y) = around(ANCHORS.getValue(sec), i + 3, 0.08f, 0.03f, 0.2f)
            news += NewsStar(h.id, h.title, h.source, sec, x, y, (1f - age.toFloat() / (NEWS_STAR_DAYS * DAY)).coerceIn(0.15f, 1f), h.document, h.url)
        }
        val lines = ArrayList<Line>()
        val all = stars + uncharted
        for (i in all.indices) for (j in i + 1 until all.size) {
            val a = all[i]; val b = all[j]
            val reason = when {
                !a.series.isNullOrBlank() && a.series.equals(b.series, true) -> "Series: ${a.series}"
                !a.author.isNullOrBlank() && a.author.equals(b.author, true) -> "Author: ${a.author}"
                a.tags.count { t -> b.tags.any { it.equals(t, true) } } >= 2 -> "Shared: " + a.tags.filter { t -> b.tags.any { it.equals(t, true) } }.take(2).joinToString(" · ")
                else -> null
            }
            if (reason != null && lines.size < 60) lines += Line(a.document, b.document, reason, news = false)
        }
        val docs = all.map { it.document }.toSet()
        for (n in news) if (n.document != null && n.document in docs) lines += Line(n.id, n.document, "News about it", news = true)
        // Comets: the next books in your series, coming closer as their day nears.
        val comets = ArrayList<Comet>()
        for (r in s.releases.values.sortedBy { it.date ?: Long.MAX_VALUE }) {
            if (r.kind != "book" && r.kind != "volume") continue
            val date = r.date ?: continue
            if (date <= now) continue
            val days = ((date - now) / DAY).toInt()
            val base = Matcher.baseTitle(r.series)
            val book = s.books.values.firstOrNull { b -> s.facts[b.document]?.series?.let { Matcher.baseTitle(it) == base } == true }
            val sec = book?.let { sectorsOf(s, it).firstOrNull() }
            val (ax, ay) = sec?.let { ANCHORS.getValue(it) } ?: (0.5f to 0.5f)
            val dx = ax - 0.5f; val dy = ay - 0.5f
            val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.01f)
            val dist = 0.33f + 0.14f * (min(days, 365) / 365f) + comets.size * 0.012f
            comets += Comet(r.key, r.title, r.series, sec, days, (0.5f + dx / len * dist).coerceIn(0.03f, 0.97f), (0.5f + dy / len * dist).coerceIn(0.03f, 0.97f), r.coverUrl, r.date)
            if (comets.size >= 8) break
        }
        val sectors = Sector.entries.map { sec ->
            val have = sky.revealed[sec.id].orEmpty()
            val subs = SUBGENRES[sec].orEmpty()
            SectorView(
                sec, xp[sec] ?: 0, rankName(xp[sec] ?: 0), strengths[sec] ?: 0.0,
                if (subs.isEmpty()) 0f else have.size.toFloat() / subs.size, have, subs.size - have.size,
                sec.id in sky.followed, sec.id in sky.mutedSectors, perSector[sec] ?: 0,
            )
        }
        return Chart(
            level, "Lv. $level ${dominant.classSuffix.replace('-', ' ')}", total, levelStart(level), levelStart(level + 1),
            dominant, sectors, stars, uncharted, news, lines, comets,
        )
    }

    // ---- the month's sky ----

    data class Recap(
        val month: YearMonth, val stories: Int, val minutes: Int, val booksFinished: List<String>,
        val topSector: Sector?, val bySector: Map<Sector, Int>, val topStories: List<SffRead>,
        val constellations: List<SffConstellation>, val revealed: Int,
    )

    fun recap(s: Snapshot, month: YearMonth, zone: ZoneId = ZoneId.systemDefault()): Recap {
        fun inMonth(at: Long?) = at != null && at > 0 && YearMonth.from(Instant.ofEpochSecond(at).atZone(zone)) == month
        val reads = s.sffSky.history.filter { inMonth(it.at) && it.seconds >= 10 }
        val finished = s.books.values.filter { !it.held && it.status == ReadStatus.FINISHED && inMonth(it.finishedAt) }
        val by = HashMap<Sector, Int>()
        for (r in reads) r.sectors.firstOrNull()?.let { Sector.byId(it) }?.let { by[it] = (by[it] ?: 0) + 1 }
        for (b in finished) sectorsOf(s, b).firstOrNull()?.let { by[it] = (by[it] ?: 0) + 3 }
        return Recap(
            month, reads.size, reads.sumOf { it.seconds } / 60, finished.map { it.displayTitle },
            by.maxByOrNull { it.value }?.key, by, reads.sortedByDescending { it.seconds }.take(3),
            s.sffSky.constellations.filter { inMonth(it.at) }, s.sffSky.revealed.values.sumOf { it.size },
        )
    }

    // ---- big news alerts ----

    /** Stories big enough to tell you about now: new, about your own books or authors, from a source that counts, no spoilers; three a day at most. */
    fun bigNews(s: Snapshot, now: Long): List<SffFeedItem> {
        val sky = s.sffSky
        if (!sky.bigNews) return emptyList()
        val left = 3 - if (sky.notifyDay == today(now)) sky.notifyCount else 0
        if (left <= 0) return emptyList()
        val read = sky.history.map { it.id }.toSet()
        return s.sffItems.filter { it ->
            val mine = it.matchedDocument != null || it.matchedLibraryBook?.let { m -> m.startsWith("Mentions your series") || m.startsWith("Mentions author") } == true
            mine && !it.spoilerWarning && it.id !in sky.notified && it.id !in read &&
                !it.source.startsWith("New & upcoming") && it.category != "DISCOVERY" &&
                now - (it.firstSeenAt.takeIf { f -> f > 0 } ?: 0L) < 3 * 3600 &&
                now - (it.publishedAt.takeIf { p -> p > 0 } ?: now) < 2 * DAY &&
                sourceWeight(it.source) >= 1.0
        }.sortedByDescending { sourceWeight(it.source) }.distinctBy { Matcher.baseTitle(it.title) }.take(left)
    }

    fun markNotified(sky: Sky, ids: List<String>, now: Long): Sky {
        val day = today(now)
        val count = if (sky.notifyDay == day) sky.notifyCount else 0
        return sky.copy(notified = (sky.notified + ids).takeLast(200), notifyDay = day, notifyCount = count + ids.size)
    }

    /** The visit starts: what came in since the last one is "new". Visits less than 10 minutes apart count as one. */
    fun visit(sky: Sky, now: Long): Sky =
        if (now - sky.lastSeenAt < 600) sky.copy(lastSeenAt = now) else sky.copy(prevSeenAt = sky.lastSeenAt, lastSeenAt = now)

    /** Today's date, for tests and labels. */
    fun localDate(now: Long): LocalDate = Instant.ofEpochSecond(now).atZone(ZoneId.systemDefault()).toLocalDate()
}
