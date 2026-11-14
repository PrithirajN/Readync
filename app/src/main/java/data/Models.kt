package io.github.prithirajn.readync.data

import io.github.prithirajn.readync.util.arr
import io.github.prithirajn.readync.util.bool
import io.github.prithirajn.readync.util.int
import io.github.prithirajn.readync.util.jsonArrayOf
import io.github.prithirajn.readync.util.long
import io.github.prithirajn.readync.util.num
import io.github.prithirajn.readync.util.objects
import io.github.prithirajn.readync.util.str
import io.github.prithirajn.readync.util.strings
import org.json.JSONArray
import org.json.JSONObject

/** Reading status, shared by CrossPoint and every tracker. */
enum class ReadStatus(val label: String) {
    TO_READ("Want to Read"),
    READING("Reading"),
    PAUSED("Paused"),
    FINISHED("Finished"),
    DNF("Did Not Finish");

    companion object {
        fun fromCrossPoint(s: String?): ReadStatus? = when (s) {
            "reading" -> READING
            "paused" -> PAUSED
            "finished" -> FINISHED
            "dnf" -> DNF
            else -> null
        }
    }
}

/** Who can see progress updates the app posts to a tracker. */
enum class Visibility(val label: String) { PRIVATE("Only me"), PUBLIC("Everyone") }

/** The crosspoint-sync login. [key] is the MD5 of the password, what KOSync sends. */
data class Account(val server: String, val username: String, val key: String)

/**
 * A Libby (by OverDrive) sign-in, for your loans and borrow dates, and the reading progress and
 * highlights Libby hands over. [identity] is Libby's sign-in token (kept sealed on the phone).
 */
data class LibbyAccount(
    val identity: String,
    val chip: String? = null,
    val name: String? = null,
    val enabled: Boolean = true,
    val syncHighlights: Boolean = true,
    val syncBorrowedTag: Boolean = false,
) {
    fun toJson(sealedIdentity: String? = null): JSONObject = JSONObject()
        .put("identity", sealedIdentity ?: identity)
        .put("chip", chip ?: JSONObject.NULL)
        .put("name", name ?: JSONObject.NULL)
        .put("enabled", enabled)
        .put("syncHighlights", syncHighlights)
        .put("syncBorrowedTag", syncBorrowedTag)

    companion object {
        fun fromJson(o: JSONObject, unsealedIdentity: String? = null) = LibbyAccount(
            identity = unsealedIdentity ?: o.optString("identity"),
            chip = o.str("chip"),
            name = o.str("name"),
            enabled = o.optBoolean("enabled", true),
            syncHighlights = o.optBoolean("syncHighlights", true),
            syncBorrowedTag = o.optBoolean("syncBorrowedTag", false),
        )
    }
}

/** An Amazon Kindle account session for Whispersync progress and notebook highlights. */
/**
 * Audible: this phone registered as one of your Audible devices (on your store), with its own
 * access. The tokens are kept encrypted, like every other sign-in here.
 */
data class AudibleAccount(
    /** The store's domain ("in", "com", "co.uk"). */
    val domain: String,
    /** "India", "United States"… */
    val store: String,
    val accessToken: String,
    val refreshToken: String,
    /** Unix seconds when [accessToken] runs out (it's renewed from [refreshToken]). */
    val expiresAt: Long,
    val name: String? = null,
    val enabled: Boolean = true,
) {
    fun toJson(sealedRefresh: String? = null): JSONObject = JSONObject()
        .put("domain", domain).put("store", store)
        .put("refreshToken", sealedRefresh ?: refreshToken)
        .put("name", name ?: JSONObject.NULL)
        .put("enabled", enabled)

    companion object {
        /** The short-lived access isn't kept; it's renewed on the first sync after a restart. */
        fun fromJson(o: JSONObject, unsealedRefresh: String? = null) = AudibleAccount(
            domain = o.optString("domain", "in"),
            store = o.optString("store", "India"),
            accessToken = "",
            refreshToken = unsealedRefresh ?: o.optString("refreshToken"),
            expiresAt = 0L,
            name = o.str("name"),
            enabled = o.optBoolean("enabled", true),
        )
    }
}

/**
 * Mihon (or Tachiyomi): the folder its automatic backups go to, which Tracker Sync reads chapter
 * progress from, and which backup it read last.
 */
data class MihonSource(
    val folder: String,
    val enabled: Boolean = true,
    val backupName: String? = null,
    /** The last backup's own time (unix seconds). */
    val backupAt: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("folder", folder).put("enabled", enabled)
        .put("backupName", backupName ?: JSONObject.NULL).put("backupAt", backupAt)

    companion object {
        fun fromJson(o: JSONObject) = MihonSource(o.optString("folder"), o.optBoolean("enabled", true), o.str("backupName"), o.optLong("backupAt"))
    }
}

/** MangaUpdates: your username and its session (kept encrypted). */
data class MangaUpdatesAccount(val username: String, val token: String, val enabled: Boolean = true) {
    fun toJson(sealedToken: String? = null): JSONObject = JSONObject().put("username", username).put("token", sealedToken ?: token).put("enabled", enabled)

    companion object {
        fun fromJson(o: JSONObject, unsealedToken: String) = MangaUpdatesAccount(o.optString("username"), unsealedToken, o.optBoolean("enabled", true))
    }
}

/**
 * AniList: your own API client's id, the token it gave (kept encrypted), who you are there, and
 * whether your list is read here and whether chapters read here are sent there.
 */
data class AniListAccount(
    val clientId: String,
    val token: String,
    val userId: Long,
    val name: String,
    val read: Boolean = true,
    val send: Boolean = true,
) {
    fun toJson(sealedToken: String? = null): JSONObject = JSONObject().put("clientId", clientId).put("token", sealedToken ?: token)
        .put("userId", userId).put("name", name).put("read", read).put("send", send)

    companion object {
        fun fromJson(o: JSONObject, unsealedToken: String) = AniListAccount(
            o.optString("clientId"), unsealedToken, o.optLong("userId"), o.optString("name"), o.optBoolean("read", true), o.optBoolean("send", true),
        )
    }
}

/**
 * A manga or comic whose progress you tell the app yourself (read on a device it can't see):
 * the chapter or issue you've reached, and the page when you're part-way through it.
 */
data class LoggedSeries(
    val title: String,
    val comic: Boolean,
    /** The chapter or issue you're on (or finished last). */
    val at: Double,
    /** The page you're on in it, from 1; 0 when you finished it. */
    val page: Int = 0,
    /** Whether everything before it counts as read (else only this one). */
    val fromStart: Boolean = true,
    /** Where you were before this log, so chapters read since count as read now. */
    val before: Double = 0.0,
    /** When you logged it (unix seconds). */
    val loggedAt: Long = 0L,
    val author: String? = null,
    /** Start and finish days per chapter or issue (its number -> unix seconds), given by you or noted as you log. */
    val dates: Map<Double, Pair<Long?, Long?>> = emptyMap(),
) {
    val finished: Boolean get() = page <= 0

    fun toJson(): JSONObject = JSONObject().put("title", title).put("comic", comic).put("at", at).put("page", page)
        .put("fromStart", fromStart).put("before", before).put("loggedAt", loggedAt).put("author", author ?: JSONObject.NULL)
        .put("dates", JSONObject().also { o -> dates.forEach { (n, d) -> o.put(n.toString(), JSONObject().put("s", d.first ?: JSONObject.NULL).put("f", d.second ?: JSONObject.NULL)) } })

    companion object {
        fun fromJson(o: JSONObject) = LoggedSeries(
            o.optString("title"), o.optBoolean("comic"), o.optDouble("at", 0.0), o.optInt("page", 0), o.optBoolean("fromStart", true),
            o.optDouble("before", 0.0), o.optLong("loggedAt"), o.str("author"),
            o.optJSONObject("dates")?.let { d -> d.keys().asSequence().mapNotNull { k ->
                val v = d.optJSONObject(k) ?: return@mapNotNull null
                k.toDoubleOrNull()?.let { n -> n to ((if (v.isNull("s")) null else v.optLong("s")) to (if (v.isNull("f")) null else v.optLong("f"))) }
            }.toMap() } ?: emptyMap(),
        )
    }
}

/**
 * What you've read of a manga or comic series, from all its sources together: the chapters or
 * issues read (as ranges, "1–43"), and the one you're part-way through with its page.
 */
data class SeriesRead(
    val read: List<Pair<Double, Double>>,
    val current: Double? = null,
    /** The page you're on in [current], from 1. */
    val page: Int = 0,
) {
    fun isRead(n: Double) = read.any { (a, b) -> n >= a - 1e-6 && n <= b + 1e-6 }

    fun toJson(): JSONObject = JSONObject().put("read", JSONArray(read.map { JSONArray().put(it.first).put(it.second) }))
        .put("current", current ?: JSONObject.NULL).put("page", page)

    companion object {
        fun fromJson(o: JSONObject) = SeriesRead(
            (0 until (o.optJSONArray("read")?.length() ?: 0)).mapNotNull { i -> o.getJSONArray("read").optJSONArray(i)?.let { it.optDouble(0) to it.optDouble(1) } },
            o.optDouble("current").takeIf { !it.isNaN() && !o.isNull("current") },
            o.optInt("page"),
        )

        /** Numbers read as ranges: 1, 2, 3, 5 is "1–3, 5". */
        fun ranges(numbers: Collection<Double>): List<Pair<Double, Double>> {
            val out = ArrayList<Pair<Double, Double>>()
            for (n in numbers.sorted()) {
                val last = out.lastOrNull()
                if (last != null && n - last.second <= 1.0 + 1e-6) out[out.lastIndex] = last.first to n else out += n to n
            }
            return out
        }
    }
}

/**
 * Something new in a series you read: the next book or volume (out now or coming), a new manga
 * chapter or comic issue, new web-novel chapters. Found on Hardcover, Goodreads, MangaDex or the
 * story's site.
 */
data class Release(
    val key: String,
    /** "book", "volume", "chapter", "issue" or "webnovel". */
    val kind: String,
    val series: String,
    val title: String,
    /** Its number in the series ("5", "Chapter 120"), when it has one. */
    val number: String? = null,
    /** Its release day (unix seconds), when known. */
    val date: Long? = null,
    val source: String,
    val coverUrl: String? = null,
    val url: String? = null,
    val author: String? = null,
    /** When it was first seen here. */
    val seenAt: Long = 0L,
    /** What you've been told: null, "soon" (a reminder before it's out) or "out". */
    val notified: String? = null,
) {
    fun isOut(now: Long = System.currentTimeMillis() / 1000) = if (date == null) !announcedOnly else date <= now

    /**
     * Announced, nothing more: a stand-in title ("The Devils book 3", "Untitled", "TBA") and no day.
     * Listed under Coming ("not dated yet"), never as out now.
     */
    val announcedOnly: Boolean get() = date == null && (kind == "book" || kind == "volume") && placeholder(title)

    fun toJson(): JSONObject = JSONObject().put("key", key).put("kind", kind).put("series", series).put("title", title)
        .put("number", number ?: JSONObject.NULL).put("date", date ?: JSONObject.NULL).put("source", source)
        .put("coverUrl", coverUrl ?: JSONObject.NULL).put("url", url ?: JSONObject.NULL).put("author", author ?: JSONObject.NULL)
        .put("seenAt", seenAt).put("notified", notified ?: JSONObject.NULL)

    companion object {
        /** A listing's stand-in title for a book not written or named yet. */
        fun placeholder(title: String): Boolean =
            Regex("(?i)^\\s*(untitled|tba|tbd|tbc|to be announced)\\b|\\bbook\\s+\\d+\\s*$|\\b(untitled|tba|tbd)\\b").containsMatchIn(title)

        fun fromJson(o: JSONObject) = Release(
            o.optString("key"), o.optString("kind", "book"), o.optString("series"), o.optString("title"), o.str("number"),
            o.long("date"), o.optString("source"), o.str("coverUrl"), o.str("url"), o.str("author"), o.optLong("seenAt"), o.str("notified"),
        )
    }
}

/** What "New & upcoming" looks for, where, and what it tells you. */
data class ReleaseSettings(
    val enabled: Boolean = true,
    /** Kinds: the next novel in a series, manga (volumes and chapters), comics (volumes and issues), web-novel chapters. */
    val novels: Boolean = true,
    val manga: Boolean = true,
    val comics: Boolean = true,
    val webNovels: Boolean = true,
    /** Where to look. */
    val hardcover: Boolean = true,
    val goodreads: Boolean = true,
    val mangaDex: Boolean = true,
    /** Which series: "reading" (ones you're reading or have finished a book of) or "all" (anything in your library). */
    val which: String = "reading",
    /** Novellas and in-between books ("Book 2.5"). */
    val inBetween: Boolean = false,
    /** How far ahead to show what's coming, in months. */
    val aheadMonths: Int = 12,
    /** Tell me when one's out; and this many days before (0: no reminder). */
    val notifyOut: Boolean = true,
    val remindDays: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("enabled", enabled).put("novels", novels).put("manga", manga).put("comics", comics)
        .put("webNovels", webNovels).put("hardcover", hardcover).put("goodreads", goodreads).put("mangaDex", mangaDex)
        .put("which", which).put("inBetween", inBetween).put("aheadMonths", aheadMonths).put("notifyOut", notifyOut).put("remindDays", remindDays)

    companion object {
        fun fromJson(o: JSONObject) = ReleaseSettings(
            o.optBoolean("enabled", true), o.optBoolean("novels", true), o.optBoolean("manga", true), o.optBoolean("comics", true),
            o.optBoolean("webNovels", true), o.optBoolean("hardcover", true), o.optBoolean("goodreads", true), o.optBoolean("mangaDex", true),
            o.optString("which", "reading"), o.optBoolean("inBetween", false), o.optInt("aheadMonths", 12), o.optBoolean("notifyOut", true), o.optInt("remindDays", 0),
        )
    }
}

/**
 * One speculative-fiction update gathered from Hardcover, Seekquel, StoryGraph, Open Library,
 * KalpaDB, Royal Road, AO3, AniList, MangaDex, SFF magazines (Reactor, Locus, Transfer Orbit,
 * File 770, Clarkesworld) or SFF communities (r/Fantasy, r/PrintSF, r/ProgressionFantasy, etc.).
 */
data class SffFeedItem(
    val id: String,
    /** "SERIES_HORIZON", "SOCIALS", "DISCOVERY", "SERIALS_MANGA", "NEWS_AWARDS", "COMMUNITY". */
    val category: String,
    /** "Hardcover", "Seekquel", "Royal Road", "Reactor", "Locus", "r/PrintSF", "KalpaDB", "AniList"… */
    val source: String,
    val title: String,
    val subtitle: String? = null,
    val summary: String? = null,
    /** Full in-app readable article, thread, or chapter text (Markdown-formatted). */
    val fullContent: String? = null,
    val url: String? = null,
    val coverUrl: String? = null,
    val author: String? = null,
    val series: String? = null,
    val seriesNumber: String? = null,
    val rating: Double? = null,
    val ratingsCount: Int? = null,
    val pages: Int? = null,
    val year: Int? = null,
    val releaseDate: String? = null,
    val tags: List<String> = emptyList(),
    /** When this item connects directly to an author, book or series on your shelves. */
    val matchedLibraryBook: String? = null,
    /** True when the blurb or thread references a later book in a series you haven't finished yet. */
    val spoilerWarning: Boolean = false,
    /** Hardcover book id or Seekquel work id when known, for 1-tap shelving. */
    val externalId: String? = null,
    val publishedAt: Long = 0L,
    /** De-clickbaited headline generated by the on-device AI model (Artifact style). */
    val aiHeadline: String? = null,
    /** 3-bullet executive takeaways generated by the on-device AI model. */
    val aiBullets: List<String> = emptyList(),
    /** Personalized Artifact ranking score (computed from library + reading dwell telemetry + AI). */
    val artifactScore: Double = 0.0,
    /** The book in your library this story is about (its document), when it names one. */
    val matchedDocument: String? = null,
    /** When this app first saw the story (unix seconds): what's new since you last looked. */
    val firstSeenAt: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("category", category)
        .put("source", source)
        .put("title", title)
        .put("subtitle", subtitle ?: JSONObject.NULL)
        .put("summary", summary ?: JSONObject.NULL)
        .put("fullContent", fullContent?.take(8_000) ?: JSONObject.NULL)
        .put("url", url ?: JSONObject.NULL)
        .put("coverUrl", coverUrl ?: JSONObject.NULL)
        .put("author", author ?: JSONObject.NULL)
        .put("series", series ?: JSONObject.NULL)
        .put("seriesNumber", seriesNumber ?: JSONObject.NULL)
        .put("rating", rating ?: JSONObject.NULL)
        .put("ratingsCount", ratingsCount ?: JSONObject.NULL)
        .put("pages", pages ?: JSONObject.NULL)
        .put("year", year ?: JSONObject.NULL)
        .put("releaseDate", releaseDate ?: JSONObject.NULL)
        .put("tags", jsonArrayOf(tags))
        .put("matchedLibraryBook", matchedLibraryBook ?: JSONObject.NULL)
        .put("spoilerWarning", spoilerWarning)
        .put("externalId", externalId ?: JSONObject.NULL)
        .put("publishedAt", publishedAt)
        .put("aiHeadline", aiHeadline ?: JSONObject.NULL)
        .put("aiBullets", jsonArrayOf(aiBullets))
        .put("artifactScore", artifactScore)
        .put("matchedDocument", matchedDocument ?: JSONObject.NULL)
        .put("firstSeenAt", firstSeenAt)

    companion object {
        fun fromJson(o: JSONObject) = SffFeedItem(
            id = o.optString("id"),
            category = o.optString("category", "DISCOVERY"),
            source = o.optString("source", "Hardcover"),
            title = o.optString("title", "Untitled"),
            subtitle = o.str("subtitle"),
            summary = o.str("summary"),
            fullContent = o.str("fullContent"),
            url = o.str("url"),
            coverUrl = o.str("coverUrl"),
            author = o.str("author"),
            series = o.str("series"),
            seriesNumber = o.str("seriesNumber"),
            rating = if (o.isNull("rating")) null else o.optDouble("rating").takeIf { !it.isNaN() && it > 0 },
            ratingsCount = o.int("ratingsCount"),
            pages = o.int("pages"),
            year = o.int("year"),
            releaseDate = o.str("releaseDate"),
            tags = o.arr("tags")?.strings() ?: emptyList(),
            matchedLibraryBook = o.str("matchedLibraryBook"),
            spoilerWarning = o.bool("spoilerWarning"),
            externalId = o.str("externalId"),
            publishedAt = o.long("publishedAt") ?: 0L,
            aiHeadline = o.str("aiHeadline"),
            aiBullets = o.arr("aiBullets")?.strings() ?: emptyList(),
            artifactScore = o.optDouble("artifactScore", 0.0).let { if (it.isNaN()) 0.0 else it },
            matchedDocument = o.str("matchedDocument"),
            firstSeenAt = o.long("firstSeenAt") ?: 0L,
        )
    }
}

/**
 * Artifact-style reader interest profile: learns from which stories you open in the in-app reader,
 * how long you spend reading them (dwell time), and explicit "More like this" / "Less like this"
 * feedback, combined with your library and on-device AI ranking.
 */
data class SffArtifactProfile(
    val tagWeights: Map<String, Double> = emptyMap(),
    val sourceWeights: Map<String, Double> = emptyMap(),
    val authorWeights: Map<String, Double> = emptyMap(),
    val readIds: Set<String> = emptySet(),
    val dwellSeconds: Map<String, Int> = emptyMap(),
    val likedIds: Set<String> = emptySet(),
    val dislikedIds: Set<String> = emptySet(),
    val mutedTags: Set<String> = emptySet(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("tagWeights", JSONObject().also { r -> tagWeights.entries.take(120).forEach { (k, v) -> r.put(k, v) } })
        .put("sourceWeights", JSONObject().also { r -> sourceWeights.entries.take(60).forEach { (k, v) -> r.put(k, v) } })
        .put("authorWeights", JSONObject().also { r -> authorWeights.entries.take(80).forEach { (k, v) -> r.put(k, v) } })
        .put("readIds", jsonArrayOf(readIds.take(300).toList()))
        .put("dwellSeconds", JSONObject().also { r -> dwellSeconds.entries.take(200).forEach { (k, v) -> r.put(k, v) } })
        .put("likedIds", jsonArrayOf(likedIds.take(200).toList()))
        .put("dislikedIds", jsonArrayOf(dislikedIds.take(200).toList()))
        .put("mutedTags", jsonArrayOf(mutedTags.toList()))

    companion object {
        fun fromJson(o: JSONObject?): SffArtifactProfile {
            if (o == null) return SffArtifactProfile()
            fun mapDouble(k: String) = o.optJSONObject(k)?.let { r ->
                r.keys().asSequence().mapNotNull { key -> r.optDouble(key).takeIf { !it.isNaN() }?.let { key to it } }.toMap()
            } ?: emptyMap()
            fun mapInt(k: String) = o.optJSONObject(k)?.let { r ->
                r.keys().asSequence().associateWith { r.optInt(it, 0) }
            } ?: emptyMap()
            return SffArtifactProfile(
                tagWeights = mapDouble("tagWeights"),
                sourceWeights = mapDouble("sourceWeights"),
                authorWeights = mapDouble("authorWeights"),
                readIds = o.arr("readIds")?.strings()?.toSet() ?: emptySet(),
                dwellSeconds = mapInt("dwellSeconds"),
                likedIds = o.arr("likedIds")?.strings()?.toSet() ?: emptySet(),
                dislikedIds = o.arr("dislikedIds")?.strings()?.toSet() ?: emptySet(),
                mutedTags = o.arr("mutedTags")?.strings()?.toSet() ?: emptySet(),
            )
        }
    }
}

/**
 * State for the 3-hour Astral Probe expedition on the Subgenre Star-Chart.
 */
data class SffProbeState(
    /** "ORBIT", "CITADEL", "SPIRE", "VOID", "CHRONOS", or null when idle. */
    val activeSector: String? = null,
    val startedAt: Long = 0L,
    val endsAt: Long = 0L,
    val sectorXp: Map<String, Int> = emptyMap(),
    val claimedCount: Int = 0,
    val lastRelic: SffFeedItem? = null,
    val lastEchoQuote: String? = null,
    val lastEchoBook: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("activeSector", activeSector ?: JSONObject.NULL)
        .put("startedAt", startedAt)
        .put("endsAt", endsAt)
        .put("sectorXp", JSONObject().also { r -> sectorXp.forEach { (k, v) -> r.put(k, v) } })
        .put("claimedCount", claimedCount)
        .put("lastRelic", lastRelic?.toJson() ?: JSONObject.NULL)
        .put("lastEchoQuote", lastEchoQuote ?: JSONObject.NULL)
        .put("lastEchoBook", lastEchoBook ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject?): SffProbeState {
            if (o == null) return SffProbeState()
            return SffProbeState(
                activeSector = o.str("activeSector"),
                startedAt = o.long("startedAt") ?: 0L,
                endsAt = o.long("endsAt") ?: 0L,
                sectorXp = o.optJSONObject("sectorXp")?.let { r -> r.keys().asSequence().associateWith { r.optInt(it) } } ?: emptyMap(),
                claimedCount = o.optInt("claimedCount", 0),
                lastRelic = o.optJSONObject("lastRelic")?.let { SffFeedItem.fromJson(it) },
                lastEchoQuote = o.str("lastEchoQuote"),
                lastEchoBook = o.str("lastEchoBook"),
            )
        }
    }
}

/** A story you read in the SFF tab: for your history, the fading news stars, and the monthly sky. */
data class SffRead(
    val id: String,
    val title: String,
    val source: String,
    /** Its sectors on the Star Chart ("ORBIT", "VOID"…). */
    val sectors: List<String>,
    val at: Long,
    val seconds: Int,
    val coverUrl: String? = null,
    val url: String? = null,
    /** The book in your library it's about. */
    val document: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("source", source)
        .put("sectors", jsonArrayOf(sectors)).put("at", at).put("seconds", seconds)
        .put("coverUrl", coverUrl ?: JSONObject.NULL).put("url", url ?: JSONObject.NULL).put("document", document ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = SffRead(
            o.optString("id"), o.optString("title"), o.optString("source"), o.arr("sectors")?.strings() ?: emptyList(),
            o.long("at") ?: 0L, o.optInt("seconds", 0), o.str("coverUrl"), o.str("url"), o.str("document"),
        )
    }
}

/** This week's expedition: stories and pages in one sector. */
data class SffExpedition(
    /** The week it's for ("2026-W45"). */
    val week: String,
    val sector: String,
    val articlesGoal: Int,
    val pagesGoal: Int,
    val articles: Int = 0,
    val pages: Int = 0,
    val startedAt: Long = 0L,
    /** Pages read of each book in the sector when it started (document -> pages). */
    val pagesStart: Map<String, Int> = emptyMap(),
    val doneAt: Long = 0L,
) {
    val done: Boolean get() = doneAt > 0
    fun toJson(): JSONObject = JSONObject().put("week", week).put("sector", sector).put("articlesGoal", articlesGoal)
        .put("pagesGoal", pagesGoal).put("articles", articles).put("pages", pages).put("startedAt", startedAt)
        .put("pagesStart", JSONObject().also { r -> pagesStart.forEach { (k, v) -> r.put(k, v) } }).put("doneAt", doneAt)

    companion object {
        fun fromJson(o: JSONObject) = SffExpedition(
            o.optString("week"), o.optString("sector"), o.optInt("articlesGoal", 2), o.optInt("pagesGoal", 50),
            o.optInt("articles", 0), o.optInt("pages", 0), o.long("startedAt") ?: 0L,
            o.optJSONObject("pagesStart")?.let { r -> r.keys().asSequence().associateWith { r.optInt(it) } } ?: emptyMap(),
            o.long("doneAt") ?: 0L,
        )
    }
}

/** A constellation named on your chart by finishing an expedition. */
data class SffConstellation(val name: String, val sector: String, val at: Long) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("sector", sector).put("at", at)
    companion object {
        fun fromJson(o: JSONObject) = SffConstellation(o.optString("name"), o.optString("sector"), o.long("at") ?: 0L)
    }
}

/** How a news source has been doing: when it last gave stories, how many, and when it last failed. */
data class SffSourceHealth(val okAt: Long = 0L, val items: Int = 0, val failAt: Long = 0L) {
    fun toJson(): JSONObject = JSONObject().put("okAt", okAt).put("items", items).put("failAt", failAt)
    companion object {
        fun fromJson(o: JSONObject) = SffSourceHealth(o.long("okAt") ?: 0L, o.optInt("items", 0), o.long("failAt") ?: 0L)
    }
}

/**
 * The SFF tab's own state, around the taste model that ranks the news: XP you've earned by
 * reading stories and exploring, the Astral Probe (charged by reading, out for an hour, back with
 * a briefing), the sub-genres you've revealed, this week's expedition, your constellations, what
 * you've read, saved and reacted to, what you follow and mute, and when you last looked.
 */
data class SffSky(
    /** XP earned in each sector by reading stories, probes and expeditions (books add theirs as they're read). */
    val xp: Map<String, Int> = emptyMap(),
    val xpDay: String = "",
    val xpToday: Int = 0,
    /** The probe's charge, 0 to 100, filled by reading. */
    val charge: Int = 0,
    /** Total pages read when the charge was last topped up (-1: not yet). */
    val pagesMark: Int = -1,
    val probeSector: String? = null,
    val probeLaunchedAt: Long = 0L,
    val probeReturnsAt: Long = 0L,
    val probes: Int = 0,
    /** What the last probe brought back. */
    val briefing: List<SffFeedItem> = emptyList(),
    val briefingSector: String? = null,
    val briefingAt: Long = 0L,
    val echoQuote: String? = null,
    val echoBook: String? = null,
    /** Sub-genres revealed in each sector (sector -> names). */
    val revealed: Map<String, List<String>> = emptyMap(),
    val expedition: SffExpedition? = null,
    val constellations: List<SffConstellation> = emptyList(),
    val history: List<SffRead> = emptyList(),
    val saved: List<SffFeedItem> = emptyList(),
    /** Story id -> your reaction ("❤️", "🔥", "🤯", "😂", "😢"). */
    val reactions: Map<String, String> = emptyMap(),
    val followed: Set<String> = emptySet(),
    val mutedSectors: Set<String> = emptySet(),
    val blockedSources: Set<String> = emptySet(),
    /** This visit began at [lastSeenAt]; the one before at [prevSeenAt] (what's new is since then). */
    val lastSeenAt: Long = 0L,
    val prevSeenAt: Long = 0L,
    val notified: List<String> = emptyList(),
    val notifyDay: String = "",
    val notifyCount: Int = 0,
    val health: Map<String, SffSourceHealth> = emptyMap(),
    /** Alerts for big news about your own series and authors (3 a day at most). */
    val bigNews: Boolean = true,
    /** An alert when the probe is back. */
    val probeAlerts: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("xp", JSONObject().also { r -> xp.forEach { (k, v) -> r.put(k, v) } })
        .put("xpDay", xpDay).put("xpToday", xpToday).put("charge", charge).put("pagesMark", pagesMark)
        .put("probeSector", probeSector ?: JSONObject.NULL).put("probeLaunchedAt", probeLaunchedAt).put("probeReturnsAt", probeReturnsAt)
        .put("probes", probes)
        .put("briefing", JSONArray().also { a -> briefing.take(8).forEach { a.put(it.copy(fullContent = it.fullContent?.take(3000)).toJson()) } })
        .put("briefingSector", briefingSector ?: JSONObject.NULL).put("briefingAt", briefingAt)
        .put("echoQuote", echoQuote ?: JSONObject.NULL).put("echoBook", echoBook ?: JSONObject.NULL)
        .put("revealed", JSONObject().also { r -> revealed.forEach { (k, v) -> r.put(k, jsonArrayOf(v)) } })
        .put("expedition", expedition?.toJson() ?: JSONObject.NULL)
        .put("constellations", JSONArray().also { a -> constellations.takeLast(60).forEach { a.put(it.toJson()) } })
        .put("history", JSONArray().also { a -> history.take(400).forEach { a.put(it.toJson()) } })
        .put("saved", JSONArray().also { a -> saved.take(150).forEach { a.put(it.copy(fullContent = it.fullContent?.take(3000)).toJson()) } })
        .put("reactions", JSONObject().also { r -> reactions.entries.take(400).forEach { (k, v) -> r.put(k, v) } })
        .put("followed", jsonArrayOf(followed.toList())).put("mutedSectors", jsonArrayOf(mutedSectors.toList()))
        .put("blockedSources", jsonArrayOf(blockedSources.toList()))
        .put("lastSeenAt", lastSeenAt).put("prevSeenAt", prevSeenAt)
        .put("notified", jsonArrayOf(notified.takeLast(200))).put("notifyDay", notifyDay).put("notifyCount", notifyCount)
        .put("health", JSONObject().also { r -> health.forEach { (k, v) -> r.put(k, v.toJson()) } })
        .put("bigNews", bigNews).put("probeAlerts", probeAlerts)

    companion object {
        fun fromJson(o: JSONObject?): SffSky {
            if (o == null) return SffSky()
            return SffSky(
                xp = o.optJSONObject("xp")?.let { r -> r.keys().asSequence().associateWith { r.optInt(it) } } ?: emptyMap(),
                xpDay = o.optString("xpDay", ""), xpToday = o.optInt("xpToday", 0),
                charge = o.optInt("charge", 0), pagesMark = o.optInt("pagesMark", -1),
                probeSector = o.str("probeSector"), probeLaunchedAt = o.long("probeLaunchedAt") ?: 0L, probeReturnsAt = o.long("probeReturnsAt") ?: 0L,
                probes = o.optInt("probes", 0),
                briefing = (o.optJSONArray("briefing")?.objects() ?: emptyList()).map(SffFeedItem::fromJson),
                briefingSector = o.str("briefingSector"), briefingAt = o.long("briefingAt") ?: 0L,
                echoQuote = o.str("echoQuote"), echoBook = o.str("echoBook"),
                revealed = o.optJSONObject("revealed")?.let { r -> r.keys().asSequence().associateWith { k -> r.optJSONArray(k)?.strings() ?: emptyList() } } ?: emptyMap(),
                expedition = o.optJSONObject("expedition")?.let { SffExpedition.fromJson(it) },
                constellations = (o.optJSONArray("constellations")?.objects() ?: emptyList()).map(SffConstellation::fromJson),
                history = (o.optJSONArray("history")?.objects() ?: emptyList()).map(SffRead::fromJson),
                saved = (o.optJSONArray("saved")?.objects() ?: emptyList()).map(SffFeedItem::fromJson),
                reactions = o.optJSONObject("reactions")?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } } ?: emptyMap(),
                followed = o.optJSONArray("followed")?.strings()?.toSet() ?: emptySet(),
                mutedSectors = o.optJSONArray("mutedSectors")?.strings()?.toSet() ?: emptySet(),
                blockedSources = o.optJSONArray("blockedSources")?.strings()?.toSet() ?: emptySet(),
                lastSeenAt = o.long("lastSeenAt") ?: 0L, prevSeenAt = o.long("prevSeenAt") ?: 0L,
                notified = o.optJSONArray("notified")?.strings() ?: emptyList(),
                notifyDay = o.optString("notifyDay", ""), notifyCount = o.optInt("notifyCount", 0),
                health = o.optJSONObject("health")?.let { r -> r.keys().asSequence().mapNotNull { k -> r.optJSONObject(k)?.let { k to SffSourceHealth.fromJson(it) } }.toMap() } ?: emptyMap(),
                bigNews = o.optBoolean("bigNews", true), probeAlerts = o.optBoolean("probeAlerts", true),
            )
        }
    }
}

data class KindleAccount(
    val domain: String = "read.amazon.in",
    val cookies: String,
    val userAgent: String? = null,
    val name: String? = null,
    val enabled: Boolean = true,
    val syncHighlights: Boolean = true,
) {
    fun toJson(sealedCookies: String? = null): JSONObject = JSONObject()
        .put("domain", domain)
        .put("cookies", sealedCookies ?: cookies)
        .put("userAgent", userAgent ?: JSONObject.NULL)
        .put("name", name ?: JSONObject.NULL)
        .put("enabled", enabled)
        .put("syncHighlights", syncHighlights)

    companion object {
        fun fromJson(o: JSONObject, unsealedCookies: String? = null) = KindleAccount(
            domain = o.optString("domain", "read.amazon.in"),
            cookies = unsealedCookies ?: o.optString("cookies"),
            userAgent = o.str("userAgent"),
            name = o.str("name"),
            enabled = o.optBoolean("enabled", true),
            syncHighlights = o.optBoolean("syncHighlights", true),
        )
    }
}

/** One book as crosspoint-sync knows it. */
data class Book(
    val document: String,
    val title: String?,
    val author: String?,
    val percentage: Double,
    val status: ReadStatus?,
    val timestamp: Long,
    val aliases: List<String> = emptyList(),
    val coverUrl: String? = null,
    val description: String? = null,
    /** Where [description] came from ("Hardcover", "Open Library", "you"), for the credit line. */
    val descriptionSource: String? = null,
    /** True once a description lookup ran, found or not, so it isn't repeated on every open. */
    val detailsFetched: Boolean = false,
    /** The tracker this book was brought in from (e.g. "seekquel"); null for CrossPoint's own. */
    val origin: String? = null,
    /** Print-edition page count, from crosspoint-sync. */
    val pageCount: Int? = null,
    /** Print pages read so far (furthest position × page count), from crosspoint-sync's activity. */
    val pagesRead: Int? = null,
    /** When it was finished (unix seconds), from crosspoint-sync's activity or the source tracker. */
    val finishedAt: Long? = null,
    /**
     * Past reading brought in from a tracker's history: shown here, but sent to no tracker
     * until the user turns it on for this book.
     */
    val held: Boolean = false,
    /** For books tracked by hand: the page you're on. */
    val currentPage: Int? = null,
    /** When it was started (unix seconds), from crosspoint-sync's activity, the source tracker, or by hand. */
    val startedAt: Long? = null,
    /** Your rating out of 5 (half stars) and review, from the finish flow. */
    val rating: Double? = null,
    val review: String? = null,
    /** The finish flow was offered for this book (rated or dismissed), so it isn't offered again. */
    val reviewAsked: Boolean = false,
    /**
     * When you last set the progress by hand (unix seconds). It wins over CrossPoint, KOSync
     * and Seekquel until one of them reports reading done after it.
     */
    val manualAt: Long? = null,
    /** The ISBN, when known (a scanned barcode), for looking up the book's details. */
    val isbn: String? = null,
    /** For a manga or comic volume: where you are in it ("Issue #50, page 12", "Chapter 44 read"). */
    val place: String? = null,
) {
    val percent: Int get() = Math.round(percentage * 100).toInt().coerceIn(0, 100)
    /** A manga or comic volume (or single issue) counted from chapters: not a web novel, not a novel. */
    val isMangaOrComic: Boolean get() = (listOf(document) + aliases).any { it.startsWith("manga:") || it.startsWith("comic:") }
    /** Without web-novel tags: "The Doomsday Reaver [LitRPG, …]" shows as "The Doomsday Reaver". */
    val displayTitle: String get() = title?.let { t -> t.replace(Regex("\\s*\\[[^\\]]*\\]"), " ").replace(Regex("\\s+"), " ").trim().ifEmpty { t } }
        ?: "Untitled ($document)".take(24)
    /** The [tags] in the title ("LitRPG", "Progression"), for showing as genres. */
    val titleTags: List<String> get() = title?.let { t -> Regex("\\[([^\\]]*)\\]").findAll(t).flatMap { it.groupValues[1].split(",") }.map { it.trim() }.filter { it.isNotEmpty() && it.length < 30 }.toList() } ?: emptyList()

    fun toJson(): JSONObject = JSONObject()
        .put("document", document)
        .put("title", title ?: JSONObject.NULL)
        .put("author", author ?: JSONObject.NULL)
        .put("percentage", percentage)
        .put("status", status?.name ?: JSONObject.NULL)
        .put("timestamp", timestamp)
        .put("aliases", jsonArrayOf(aliases))
        .put("coverUrl", coverUrl ?: JSONObject.NULL)
        .put("description", description ?: JSONObject.NULL)
        .put("descriptionSource", descriptionSource ?: JSONObject.NULL)
        .put("detailsFetched", detailsFetched)
        .put("origin", origin ?: JSONObject.NULL)
        .put("pageCount", pageCount ?: JSONObject.NULL)
        .put("pagesRead", pagesRead ?: JSONObject.NULL)
        .put("finishedAt", finishedAt ?: JSONObject.NULL)
        .put("held", held)
        .put("currentPage", currentPage ?: JSONObject.NULL)
        .put("startedAt", startedAt ?: JSONObject.NULL)
        .put("rating", rating ?: JSONObject.NULL)
        .put("review", review ?: JSONObject.NULL)
        .put("reviewAsked", reviewAsked)
        .put("manualAt", manualAt ?: JSONObject.NULL)
        .put("isbn", isbn ?: JSONObject.NULL)
        .put("place", place ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = Book(
            document = o.optString("document"),
            title = o.str("title"),
            author = o.str("author"),
            percentage = o.num("percentage") ?: 0.0,
            status = o.str("status")?.let { runCatching { ReadStatus.valueOf(it) }.getOrNull() },
            timestamp = o.long("timestamp") ?: 0L,
            aliases = o.arr("aliases")?.strings() ?: emptyList(),
            coverUrl = o.str("coverUrl"),
            description = o.str("description"),
            descriptionSource = o.str("descriptionSource"),
            detailsFetched = o.bool("detailsFetched"),
            origin = o.str("origin"),
            pageCount = o.int("pageCount"),
            pagesRead = o.int("pagesRead"),
            finishedAt = o.long("finishedAt"),
            held = o.bool("held"),
            currentPage = o.int("currentPage"),
            startedAt = o.long("startedAt"),
            rating = o.num("rating"),
            review = o.str("review"),
            reviewAsked = o.bool("reviewAsked"),
            manualAt = o.long("manualAt"),
            isbn = o.str("isbn"),
            place = o.str("place"),
        )
    }
}

/** A tracker's saved settings and login. [credential] is held decrypted in memory only. */
data class ServiceState(
    val id: String,
    val linked: Boolean = false,
    val enabled: Boolean = true,
    val visibility: Visibility = Visibility.PRIVATE,
    val account: String? = null,
    val credential: String? = null,
    val needsReauth: Boolean = false,
    val lastSync: Long = 0L,
    val lastError: String? = null,
    /** Send CrossPoint highlights here. */
    val highlights: Boolean = false,
    /** Highlights made before this (unix seconds) are never sent, so turning it on doesn't flood. */
    val highlightsSince: Long = 0L,
    /** Bring books the user is reading on this tracker into the app, for the other trackers. */
    val importReading: Boolean = false,
) {
    fun toJson(sealedCredential: String?): JSONObject = JSONObject()
        .put("id", id)
        .put("linked", linked)
        .put("enabled", enabled)
        .put("visibility", visibility.name)
        .put("account", account ?: JSONObject.NULL)
        .put("credential", sealedCredential ?: JSONObject.NULL)
        .put("needsReauth", needsReauth)
        .put("lastSync", lastSync)
        .put("lastError", lastError ?: JSONObject.NULL)
        .put("highlights", highlights)
        .put("highlightsSince", highlightsSince)
        .put("importReading", importReading)

    companion object {
        fun fromJson(o: JSONObject, openedCredential: String?) = ServiceState(
            id = o.optString("id"),
            linked = o.bool("linked"),
            enabled = o.bool("enabled", true),
            visibility = o.str("visibility")?.let { runCatching { Visibility.valueOf(it) }.getOrNull() }
                ?: Visibility.PRIVATE,
            account = o.str("account"),
            credential = openedCredential,
            needsReauth = o.bool("needsReauth"),
            lastSync = o.long("lastSync") ?: 0L,
            lastError = o.str("lastError"),
            highlights = o.bool("highlights"),
            highlightsSince = o.long("highlightsSince") ?: 0L,
            importReading = o.bool("importReading"),
        )
    }
}

/** A highlight's text and note as you edited them. */
/** Seekquel's position for a book that differs from the one here. */
/**
 * What readers say about a book, gathered from Hardcover, StoryGraph and Seekquel (whichever are
 * linked) and combined: its series and number, the ratings, the year, pace, and the moods,
 * genres, tropes and content warnings readers tagged most. Kept per book, looked up again later.
 */
data class BookFacts(
    val series: String? = null,
    /** "4", "4.5". */
    val seriesNumber: String? = null,
    /** The rating shown first (Hardcover's, else StoryGraph's, else Seekquel's). */
    val rating: Double? = null,
    val ratingsCount: Int? = null,
    val year: Int? = null,
    /** "Fast-paced", "Medium-paced", "Slow-paced". */
    val pace: String? = null,
    val moods: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val tropes: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** The warnings readers called graphic (StoryGraph), shown first and marked. */
    val graphic: List<String> = emptyList(),
    /** Each site's average rating ("Hardcover" -> 4.4). */
    val ratings: Map<String, Double> = emptyMap(),
    /** Where these came from, in the order they're credited. */
    val sources: List<String> = emptyList(),
    /** Unix seconds when looked up (also when nothing was found). */
    val at: Long = 0,
    /** Which way of looking this came from; older lookups are redone. */
    val version: Int = VERSION,
) {
    val isEmpty: Boolean get() = series == null && rating == null && year == null && pace == null &&
        moods.isEmpty() && genres.isEmpty() && tropes.isEmpty() && warnings.isEmpty()

    /** "Hardcover", "Hardcover and StoryGraph", "Hardcover, StoryGraph and Seekquel". */
    val source: String get() = when (sources.size) {
        0 -> "Hardcover"
        1 -> sources[0]
        else -> sources.dropLast(1).joinToString(", ") + " and " + sources.last()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("series", series ?: JSONObject.NULL)
        .put("seriesNumber", seriesNumber ?: JSONObject.NULL)
        .put("rating", rating ?: JSONObject.NULL)
        .put("ratingsCount", ratingsCount ?: JSONObject.NULL)
        .put("year", year ?: JSONObject.NULL)
        .put("pace", pace ?: JSONObject.NULL)
        .put("moods", JSONArray(moods))
        .put("genres", JSONArray(genres))
        .put("tropes", JSONArray(tropes))
        .put("warnings", JSONArray(warnings))
        .put("graphic", JSONArray(graphic))
        .put("ratings", JSONObject().also { o -> ratings.forEach { (k, v) -> o.put(k, v) } })
        .put("sources", JSONArray(sources))
        .put("at", at)
        .put("version", version)

    companion object {
        /** 2: StoryGraph and Seekquel joined Hardcover. */
        const val VERSION = 2

        fun fromJson(o: JSONObject): BookFacts {
            fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } } ?: emptyList()
            fun str(k: String) = o.optString(k).takeIf { o.has(k) && !o.isNull(k) && it.isNotBlank() }
            val sources = list("sources").ifEmpty { listOfNotNull(str("source")) }
            return BookFacts(
                series = str("series"),
                seriesNumber = str("seriesNumber"),
                rating = if (o.isNull("rating")) null else o.optDouble("rating").takeIf { !it.isNaN() },
                ratingsCount = if (o.isNull("ratingsCount") || !o.has("ratingsCount")) null else o.optInt("ratingsCount"),
                year = if (o.isNull("year") || !o.has("year")) null else o.optInt("year"),
                pace = str("pace"),
                moods = list("moods"),
                genres = list("genres"),
                tropes = list("tropes"),
                warnings = list("warnings"),
                graphic = list("graphic"),
                ratings = o.optJSONObject("ratings")?.let { r -> r.keys().asSequence().mapNotNull { k -> r.optDouble(k).takeIf { !it.isNaN() }?.let { k to it } }.toMap() } ?: emptyMap(),
                sources = sources,
                at = o.optLong("at"),
                version = o.optInt("version", 1),
            )
        }
    }
}

/**
 * A web novel or fan fiction you read: where it's published (Royal Road, AO3, …) and what that
 * page said when last checked. An ongoing story isn't marked Finished when you catch up.
 */
data class WebNovel(
    val url: String,
    val site: String,
    /** "Ongoing", "Completed", "Hiatus", "Dropped", "Stub"… as the site says. */
    val status: String? = null,
    val chapters: Int? = null,
    /** AO3's planned total ("5/12" -> 12), when the author gave one. */
    val plannedChapters: Int? = null,
    val words: Int? = null,
    val fanfic: Boolean = false,
    val tags: List<String> = emptyList(),
    val checkedAt: Long = 0,
    /** Found by searching the sites (not a link you gave), so the book page offers to change it. */
    val found: Boolean = false,
    /** Chapters in the copy you're reading (when it was downloaded), to tell which chapter you're on. */
    val copyChapters: Int? = null,
    /** When [copyChapters] was last set (you downloaded it again: the copy grew). */
    val copyAt: Long = 0,
    /** The newest chapter, from the story's feed: its title, link and when it came out. */
    val latestTitle: String? = null,
    val latestUrl: String? = null,
    val latestAt: Long? = null,
    /** The newest chapter's link when the feed was last read, and when that was. */
    val feedSeen: String? = null,
    val feedAt: Long = 0,
    /** The story published as books (Royal Road stories often are): how many are out, and the next one. */
    val published: Int? = null,
    val pubSeries: String? = null,
    val pubNext: String? = null,
    val pubNextNo: String? = null,
    val pubNextAt: Long? = null,
    val pubUrl: String? = null,
    val pubCheckedAt: Long = 0,
) {
    /**
     * The chapter you're about on, from how far through your copy you are: [copyChapters] when
     * known, else the story's chapters. Null when neither is known.
     */
    fun chapterAt(percent: Double): Int? {
        val n = copyChapters ?: chapters ?: return null
        if (n <= 0) return null
        return Math.round(percent.coerceIn(0.0, 1.0) * n).toInt().coerceIn(if (percent > 0) 1 else 0, n)
    }

    /** How many chapters the story has past the one you're on (0 when caught up), or null. */
    fun behind(percent: Double): Int? {
        val at = chapterAt(percent) ?: return null
        val total = chapters ?: return null
        return (total - at).coerceAtLeast(0)
    }

    /** A fresh read of the story's page, keeping what only this app knows about it (your copy, the feed, its books). */
    fun refreshedFrom(f: WebNovel): WebNovel = f.copy(
        found = found, copyChapters = copyChapters, copyAt = copyAt,
        latestTitle = latestTitle, latestUrl = latestUrl, latestAt = latestAt, feedSeen = feedSeen, feedAt = feedAt,
        published = published, pubSeries = pubSeries, pubNext = pubNext, pubNextNo = pubNextNo, pubNextAt = pubNextAt, pubUrl = pubUrl, pubCheckedAt = pubCheckedAt,
    )

    /** True or false when the site says; null when it doesn't. */
    val complete: Boolean? get() = when {
        status == null -> null
        Regex("(?i)complete|finished|ended").containsMatchIn(status) -> true
        else -> false
    }
    val ongoing: Boolean get() = complete == false

    /** "Royal Road · Ongoing · 312 chapters · 1,204,000 words". */
    fun line(): String = listOfNotNull(
        site, status,
        chapters?.let { c -> "$c${plannedChapters?.let { "/$it" } ?: ""} chapter${if (c == 1 && plannedChapters == null) "" else "s"}" },
        words?.let { "%,d words".format(it) },
    ).joinToString(" · ")

    fun toJson(): JSONObject = JSONObject().put("url", url).put("site", site).put("status", status ?: JSONObject.NULL)
        .put("chapters", chapters ?: JSONObject.NULL).put("plannedChapters", plannedChapters ?: JSONObject.NULL)
        .put("words", words ?: JSONObject.NULL).put("fanfic", fanfic).put("tags", jsonArrayOf(tags)).put("checkedAt", checkedAt)
        .put("found", found)
        .put("copyChapters", copyChapters ?: JSONObject.NULL).put("copyAt", copyAt)
        .put("latestTitle", latestTitle ?: JSONObject.NULL).put("latestUrl", latestUrl ?: JSONObject.NULL).put("latestAt", latestAt ?: JSONObject.NULL)
        .put("feedSeen", feedSeen ?: JSONObject.NULL).put("feedAt", feedAt)
        .put("published", published ?: JSONObject.NULL).put("pubSeries", pubSeries ?: JSONObject.NULL).put("pubNext", pubNext ?: JSONObject.NULL)
        .put("pubNextNo", pubNextNo ?: JSONObject.NULL).put("pubNextAt", pubNextAt ?: JSONObject.NULL).put("pubUrl", pubUrl ?: JSONObject.NULL)
        .put("pubCheckedAt", pubCheckedAt)

    companion object {
        fun fromJson(o: JSONObject) = WebNovel(
            url = o.optString("url"), site = o.optString("site"), status = o.str("status"),
            chapters = o.int("chapters"), plannedChapters = o.int("plannedChapters"), words = o.int("words"),
            fanfic = o.bool("fanfic"), tags = o.arr("tags")?.strings() ?: emptyList(), checkedAt = o.long("checkedAt") ?: 0L,
            found = o.bool("found"),
            copyChapters = o.int("copyChapters"), copyAt = o.long("copyAt") ?: 0L,
            latestTitle = o.str("latestTitle"), latestUrl = o.str("latestUrl"), latestAt = o.long("latestAt"),
            feedSeen = o.str("feedSeen"), feedAt = o.long("feedAt") ?: 0L,
            published = o.int("published"), pubSeries = o.str("pubSeries"), pubNext = o.str("pubNext"), pubNextNo = o.str("pubNextNo"),
            pubNextAt = o.long("pubNextAt"), pubUrl = o.str("pubUrl"), pubCheckedAt = o.long("pubCheckedAt") ?: 0L,
        )
    }
}

/** A tracker's listing the on-device model thinks is the book; shown to accept or dismiss. */
data class AiSuggestion(val id: String, val title: String, val author: String?, val uuid: String? = null, val coverUrl: String? = null) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("author", author ?: JSONObject.NULL)
        .put("uuid", uuid ?: JSONObject.NULL).put("coverUrl", coverUrl ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = AiSuggestion(o.optString("id"), o.optString("title"), o.str("author"), o.str("uuid"), o.str("coverUrl"))
    }
}

data class ProgressAsk(val fraction: Double, val status: ReadStatus?, val updatedAt: Long, val source: String = "seekquel") {
    val percent: Int get() = Math.round(fraction * 100).toInt().coerceIn(0, 100)
}

/**
 * One update this app sent a tracker for a book: progress from [from]% to [to]% (with the book's
 * page count, for pages), how many highlights went, and anything else ("Finished", "rated 4★").
 */
data class SendEvent(
    val at: Long,
    val from: Int? = null,
    val to: Int? = null,
    val pageCount: Int? = null,
    val highlights: Int = 0,
    val other: String? = null,
) {
    /** "22% → 25% · 7 pages · 1 highlight · Finished" */
    fun describe(): String {
        val parts = ArrayList<String>()
        if (to != null) {
            parts += if (from != null && from != to) "$from% \u2192 $to%" else "$to%"
            // Just the pages read in between ("7 pages"), when the page count is known.
            if (pageCount != null && pageCount > 0 && from != null && to > from) {
                val pages = Math.round(to / 100.0 * pageCount).toInt() - Math.round(from / 100.0 * pageCount).toInt()
                if (pages > 0) parts += "$pages page${if (pages == 1) "" else "s"}"
            }
        }
        if (highlights > 0) parts += "$highlights highlight${if (highlights == 1) "" else "s"}"
        other?.let { parts += it }
        return parts.joinToString(" \u00B7 ")
    }

    fun toJson(): JSONObject = JSONObject().put("at", at).put("from", from ?: JSONObject.NULL).put("to", to ?: JSONObject.NULL)
        .put("pages", pageCount ?: JSONObject.NULL).put("hl", highlights).put("other", other ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = SendEvent(
            at = o.optLong("at"),
            from = if (o.isNull("from")) null else o.optInt("from"),
            to = if (o.isNull("to")) null else o.optInt("to"),
            pageCount = if (o.isNull("pages")) null else o.optInt("pages"),
            highlights = o.optInt("hl"),
            other = if (o.isNull("other")) null else o.optString("other"),
        )
    }
}

/** A position the reader reported for a book: when, how far (0–1), and in which chapter. */
data class PositionPoint(val timestamp: Long, val percentage: Double, val spine: Int?)

data class ClipEdit(val text: String, val note: String?)

/**
 * When and how background syncs run, and what the app tells you about. Quiet hours are local
 * hours [quietFrom, quietTo) during which scheduled syncs are skipped (-1 = off).
 */
data class SyncSettings(
    val wifiOnly: Boolean = false,
    val intervalMinutes: Int = 15,
    val quietFrom: Int = -1,
    val quietTo: Int = -1,
    val notifyFailures: Boolean = true,
    val notifyFinished: Boolean = true,
    /** A notification when another source has a different position for a book (its trackers wait until you choose). */
    val notifyPositions: Boolean = true,
    val autoEnableNewBooks: Boolean = false,
    /** Let the on-device model judge matches the rules aren't sure of (shown as suggestions to accept). */
    val aiMatching: Boolean = true,
    val githubToken: String? = null,
    val githubRepo: String? = null,
    /** Books to finish this year (0 = no goal). */
    val yearGoal: Int = 0,
    /** The home-screen icon takes the colour of the latest book's cover. */
    val iconFollowsBook: Boolean = false,
    /** Custom social handles/tags followed on the SFF tab ("x:handle", "insta:handle", "bsky:handle", "mastodon:tag"). */
    val sffSocials: List<String> = emptyList(),
) {
    fun isQuiet(hour: Int): Boolean = when {
        quietFrom < 0 || quietTo < 0 || quietFrom == quietTo -> false
        quietFrom < quietTo -> hour in quietFrom until quietTo
        else -> hour >= quietFrom || hour < quietTo // across midnight
    }

    fun toJson(): JSONObject = JSONObject()
        .put("wifiOnly", wifiOnly).put("intervalMinutes", intervalMinutes)
        .put("quietFrom", quietFrom).put("quietTo", quietTo)
        .put("notifyFailures", notifyFailures).put("notifyFinished", notifyFinished).put("notifyPositions", notifyPositions).put("autoEnableNewBooks", autoEnableNewBooks).put("aiMatching", aiMatching)
        .put("githubToken", githubToken ?: JSONObject.NULL).put("githubRepo", githubRepo ?: JSONObject.NULL)
        .put("yearGoal", yearGoal)
        .put("iconFollowsBook", iconFollowsBook)
        .put("sffSocials", jsonArrayOf(sffSocials))

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) SyncSettings() else SyncSettings(
            wifiOnly = o.bool("wifiOnly"),
            intervalMinutes = (o.int("intervalMinutes") ?: 15).coerceAtLeast(15),
            quietFrom = o.int("quietFrom") ?: -1,
            quietTo = o.int("quietTo") ?: -1,
            notifyFailures = o.bool("notifyFailures", true),
            notifyFinished = o.bool("notifyFinished", true),
            notifyPositions = o.bool("notifyPositions", true),
            autoEnableNewBooks = o.bool("autoEnableNewBooks", false),
            aiMatching = o.bool("aiMatching", true),
            githubToken = o.optString("githubToken").takeIf { it.isNotBlank() && it != "null" },
            githubRepo = o.optString("githubRepo").takeIf { it.isNotBlank() && it != "null" },
            yearGoal = (o.int("yearGoal") ?: 0).coerceIn(0, 999),
            iconFollowsBook = o.bool("iconFollowsBook", false),
            sffSocials = o.arr("sffSocials")?.strings() ?: emptyList(),
        )
    }
}

/** How a link was made. NONE means "never sync this book to this service". */
enum class LinkSource { AUTO, MANUAL, NONE }

/** A CrossPoint document matched to a book on one tracker. */
data class Link(
    val service: String,
    val document: String,
    val externalId: String?,
    val externalUuid: String? = null,
    val title: String? = null,
    val author: String? = null,
    val source: LinkSource = LinkSource.AUTO,
    /**
     * The app found this match itself, for a book's first sync to this tracker: nothing is sent
     * until you confirm it's the right book. Matches you picked, and ones already in use, are confirmed.
     */
    val confirmed: Boolean = true,
    val lastPercent: Int? = null,
    val lastStatus: ReadStatus? = null,
    val note: String? = null,
    val updatedAt: Long = 0L,
    /**
     * For a source tracker (Seekquel): the ids its library uses for this CrossPoint book, so
     * the book isn't brought back in as a second copy.
     */
    val libraryKeys: List<String> = emptyList(),
    /** The dates last put on the tracker ("start|finish"), so they're sent once. */
    val datesSent: String? = null,
    /** The rating and review last sent (a fingerprint), so they're sent once. */
    val reviewSent: String? = null,
) {
    val key: String get() = key(service, document)

    fun toJson(): JSONObject = JSONObject()
        .put("service", service)
        .put("document", document)
        .put("externalId", externalId ?: JSONObject.NULL)
        .put("externalUuid", externalUuid ?: JSONObject.NULL)
        .put("title", title ?: JSONObject.NULL)
        .put("author", author ?: JSONObject.NULL)
        .put("source", source.name)
        .put("confirmed", confirmed)
        .put("lastPercent", lastPercent ?: JSONObject.NULL)
        .put("lastStatus", lastStatus?.name ?: JSONObject.NULL)
        .put("note", note ?: JSONObject.NULL)
        .put("updatedAt", updatedAt)
        .put("libraryKeys", jsonArrayOf(libraryKeys))
        .put("datesSent", datesSent ?: JSONObject.NULL)
        .put("reviewSent", reviewSent ?: JSONObject.NULL)

    companion object {
        fun key(service: String, document: String) = "$service|$document"

        fun fromJson(o: JSONObject) = Link(
            service = o.optString("service"),
            document = o.optString("document"),
            externalId = o.str("externalId"),
            externalUuid = o.str("externalUuid"),
            title = o.str("title"),
            author = o.str("author"),
            source = o.str("source")?.let { runCatching { LinkSource.valueOf(it) }.getOrNull() } ?: LinkSource.AUTO,
            // Links from before confirming existed are already in use.
            confirmed = if (o.has("confirmed")) o.optBoolean("confirmed", true) else true,
            lastPercent = o.int("lastPercent"),
            lastStatus = o.str("lastStatus")?.let { runCatching { ReadStatus.valueOf(it) }.getOrNull() },
            note = o.str("note"),
            updatedAt = o.long("updatedAt") ?: 0L,
            libraryKeys = o.arr("libraryKeys")?.strings() ?: emptyList(),
            datesSent = o.str("datesSent"),
            reviewSent = o.str("reviewSent"),
        )
    }
}

/** A book whose latest state hasn't reached a tracker yet. One per (service, document). */
data class Pending(
    val service: String,
    val document: String,
    val queuedAt: Long,
    val tries: Int = 0,
    val lastError: String? = null,
) {
    val key: String get() = Link.key(service, document)

    fun toJson(): JSONObject = JSONObject()
        .put("service", service)
        .put("document", document)
        .put("queuedAt", queuedAt)
        .put("tries", tries)
        .put("lastError", lastError ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = Pending(
            service = o.optString("service"),
            document = o.optString("document"),
            queuedAt = o.long("queuedAt") ?: 0L,
            tries = o.int("tries") ?: 0,
            lastError = o.str("lastError"),
        )
    }
}

data class LogEntry(val time: Long, val service: String?, val message: String, val error: Boolean) {
    fun toJson(): JSONObject = JSONObject()
        .put("time", time)
        .put("service", service ?: JSONObject.NULL)
        .put("message", message)
        .put("error", error)

    companion object {
        fun fromJson(o: JSONObject) = LogEntry(
            time = o.long("time") ?: 0L,
            service = o.str("service"),
            message = o.optString("message"),
            error = o.bool("error"),
        )
    }
}

/** Everything the app keeps, as one immutable value. */
/**
 * One day's reading of one book: the pages read that day, the minutes when known, how far through
 * the book you were by the end of it (0–1) when that's what's known, and where it came from.
 */
/** One price seen at one store: what it cost, against its printed price, and whether it was in stock. */
data class PricePoint(
    val store: String,
    val at: Long,
    val price: Double?,
    val mrp: Double? = null,
    val inStock: Boolean = true,
    /** "Paperback", "Kindle"… where the store says. */
    val format: String? = null,
    val url: String? = null,
    val title: String? = null,
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject().put("s", store).put("at", at).put("p", price ?: org.json.JSONObject.NULL)
        .put("m", mrp ?: org.json.JSONObject.NULL).put("in", inStock).put("f", format ?: org.json.JSONObject.NULL).put("u", url ?: org.json.JSONObject.NULL)
        .put("t", title ?: org.json.JSONObject.NULL)

    companion object {
        fun from(o: org.json.JSONObject) = PricePoint(
            o.optString("s"), o.optLong("at"), if (o.isNull("p")) null else o.optDouble("p"), if (o.isNull("m")) null else o.optDouble("m"),
            o.optBoolean("in", true), if (o.isNull("f")) null else o.optString("f"), if (o.isNull("u")) null else o.optString("u"), if (o.isNull("t")) null else o.optString("t"),
        )
    }
}

/**
 * One listing of a book at a store, for choosing between editions: its page, format, edition
 * (deluxe, special…), publisher, page count and price when it was found.
 */
data class PriceListing(
    val store: String,
    val url: String,
    val title: String? = null,
    val format: String? = null,
    val publisher: String? = null,
    val pages: Int? = null,
    val edition: String? = null,
    val price: Double? = null,
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject().put("s", store).put("u", url)
        .put("t", title ?: org.json.JSONObject.NULL).put("f", format ?: org.json.JSONObject.NULL)
        .put("pub", publisher ?: org.json.JSONObject.NULL).put("pg", pages ?: org.json.JSONObject.NULL)
        .put("e", edition ?: org.json.JSONObject.NULL).put("p", price ?: org.json.JSONObject.NULL)

    companion object {
        fun from(o: org.json.JSONObject) = PriceListing(
            o.optString("s"), o.optString("u"),
            if (o.isNull("t")) null else o.optString("t"), if (o.isNull("f")) null else o.optString("f"),
            if (o.isNull("pub")) null else o.optString("pub"), if (o.isNull("pg")) null else o.optInt("pg"),
            if (o.isNull("e")) null else o.optString("e"), if (o.isNull("p")) null else o.optDouble("p"),
        )
    }
}

/**
 * A book's price being watched: the price you'd buy it at, a store page you pointed it to (by
 * store), every price seen (newest last, a few hundred kept), when it was last looked at, and the
 * lowest price you were told about (so a drop is said once).
 */
data class PriceWatch(
    val document: String,
    val target: Double? = null,
    val links: Map<String, String> = emptyMap(),
    val history: List<PricePoint> = emptyList(),
    val checkedAt: Long = 0,
    val told: Double? = null,
    val since: Long = 0,
    val error: String? = null,
    /** Stores whose page you chose yourself (Wrong book?): followed as they are, never second-guessed. */
    val pinned: Set<String> = emptySet(),
    /** The book itself, for one tracked without being in the library (and kept for those that are). */
    val title: String? = null,
    val author: String? = null,
    val isbn: String? = null,
    val cover: String? = null,
    /** Each store's listings of it found when it was looked for (editions to choose between). */
    val choices: Map<String, List<PriceListing>> = emptyMap(),
) {
    /** The book to look for: from the library when it's there, else as the tracker knows it. */
    fun book(s: Snapshot): Book? = s.books[document]
        ?: title?.let { Book(document = document, title = it, author = author, percentage = 0.0, status = null, timestamp = since, coverUrl = cover, isbn = isbn) }

    /** The newest price at each store. */
    val latest: List<PricePoint> get() = history.groupBy { it.store + "|" + (it.format ?: "") }.values.map { it.maxBy { p -> p.at } }
    /** The cheapest in-stock price now, anywhere. */
    val best: PricePoint? get() = latest.filter { it.price != null && it.inStock }.minByOrNull { it.price!! }
    /** The lowest in-stock price ever seen. */
    val lowest: Double? get() = history.filter { it.inStock }.mapNotNull { it.price }.minOrNull()
    /** The first best price seen, to say how far it's come down. */
    val first: Double? get() = history.filter { it.inStock && it.price != null }.groupBy { it.at }.minByOrNull { it.key }?.value?.minOf { it.price!! }
    /** How far the best price now is under the first one seen (0.25 = 25% less); null without both. */
    val drop: Double? get() { val f = first ?: return null; val b = best?.price ?: return null; return if (f > 0) (f - b) / f else null }

    fun toJson(): org.json.JSONObject = org.json.JSONObject().put("d", document).put("target", target ?: org.json.JSONObject.NULL)
        .put("links", org.json.JSONObject(links)).put("history", org.json.JSONArray().also { a -> history.forEach { a.put(it.toJson()) } })
        .put("checkedAt", checkedAt).put("told", told ?: org.json.JSONObject.NULL).put("since", since).put("error", error ?: org.json.JSONObject.NULL)
        .put("pinned", org.json.JSONArray(pinned.toList()))
        .put("title", title ?: org.json.JSONObject.NULL).put("author", author ?: org.json.JSONObject.NULL)
        .put("isbn", isbn ?: org.json.JSONObject.NULL).put("cover", cover ?: org.json.JSONObject.NULL)
        .put("choices", org.json.JSONObject().also { o -> choices.forEach { (k, v) -> o.put(k, org.json.JSONArray().also { a -> v.forEach { a.put(it.toJson()) } }) } })

    companion object {
        fun from(o: org.json.JSONObject): PriceWatch {
            val links = o.optJSONObject("links")?.let { l -> l.keys().asSequence().associateWith { l.optString(it) } } ?: emptyMap()
            val h = o.optJSONArray("history")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(PricePoint::from) } } ?: emptyList()
            return PriceWatch(
                o.optString("d"), if (o.isNull("target")) null else o.optDouble("target"), links, h, o.optLong("checkedAt"),
                if (o.isNull("told")) null else o.optDouble("told"), o.optLong("since"), if (o.isNull("error")) null else o.optString("error"),
                o.optJSONArray("pinned")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet(),
                if (o.isNull("title")) null else o.optString("title"), if (o.isNull("author")) null else o.optString("author"),
                if (o.isNull("isbn")) null else o.optString("isbn"), if (o.isNull("cover")) null else o.optString("cover"),
                o.optJSONObject("choices")?.let { c ->
                    c.keys().asSequence().associateWith { k ->
                        c.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(PriceListing::from) } } ?: emptyList()
                    }
                } ?: emptyMap(),
            )
        }
    }
}

data class ReadDay(val pages: Int? = null, val minutes: Int? = null, val reached: Double? = null, val source: String) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        pages?.let { put("p", it) }; minutes?.let { put("m", it) }; reached?.let { put("r", it) }; put("s", source)
    }

    companion object {
        fun fromJson(o: org.json.JSONObject) = ReadDay(
            pages = if (o.has("p")) o.optInt("p") else null,
            minutes = if (o.has("m")) o.optInt("m") else null,
            reached = if (o.has("r")) o.optDouble("r").takeIf { !it.isNaN() } else null,
            source = o.optString("s", "imported"),
        )
    }
}

/** A highlight that comes with a reading history (a Hardcover quote…), before it's put on a book. Times are unix seconds. */
data class ImportedQuote(val text: String, val note: String? = null, val createdAt: Long? = null, val page: Int? = null, val percent: Double? = null)

data class Snapshot(
    val account: Account? = null,
    val cursor: Long = 0L,
    val books: Map<String, Book> = emptyMap(),
    val services: Map<String, ServiceState> = emptyMap(),
    val links: Map<String, Link> = emptyMap(),
    val pending: Map<String, Pending> = emptyMap(),
    val log: List<LogEntry> = emptyList(),
    val lastRun: Long = 0L,
    val running: Boolean = false,
    /** What the running sync is on ("Reading AniList"), shown beside "Syncing…"; not saved. */
    val syncStep: String? = null,
    /** Highlights already sent, as "service|clippingId". */
    val sentClippings: Set<String> = emptySet(),
    /** When crosspoint-sync was last read without trouble (millis), and what went wrong since. */
    val sourceOkAt: Long = 0L,
    val sourceError: String? = null,
    /** An extra KOSync server checked for newer progress on books already here. */
    val kosync: Account? = null,
    val kosyncOkAt: Long = 0L,
    val kosyncError: String? = null,
    /** Where the round-robin check of books that aren't in progress continues next pass. */
    val kosyncRotation: Int = 0,
    /**
     * Other KOSync document IDs for a book (document -> IDs), linked by hand: the same book read on
     * a device that fingerprints it differently (another copy of the file, another matching method).
     */
    val kosyncIds: Map<String, List<String>> = emptyMap(),
    /**
     * Moon+ Reader, through the WebDAV storage its sync writes to (Koofr, Nextcloud…): the sign-in
     * (server = the WebDAV address, key = an app password), the folder its ".po" files were
     * found in, and when it last worked or what went wrong.
     */
    val moon: Account? = null,
    val moonFolder: String? = null,
    val moonOkAt: Long = 0L,
    val moonError: String? = null,
    /** Each Moon+ sync file's last position brought in (path -> saved at, millis), and which book each file is (file name -> document). */
    val moonSeen: Map<String, Long> = emptyMap(),
    val moonDocs: Map<String, String> = emptyMap(),
    /**
     * KOReader highlights from its sync plugins' files (HighlightSync, AnnotationSync) on WebDAV
     * storage like Koofr: the sign-in, the folders the files are in, when it last worked or what
     * went wrong, each file's "last modified" when read, which book each file is, the files that
     * matched no book, and the highlights themselves.
     */
    val koHl: Account? = null,
    val koHlFolders: List<String> = emptyList(),
    val koHlOkAt: Long = 0L,
    val koHlError: String? = null,
    val koHlMods: Map<String, String> = emptyMap(),
    val koHlDocs: Map<String, String> = emptyMap(),
    val koHlUnmatched: List<String> = emptyList(),
    val koClips: List<io.github.prithirajn.readync.source.Clipping> = emptyList(),
    /** KOReader highlights are also added to CrossPoint (books read on CrossPoint, where the chapter is known); which went (id here -> id there). */
    val koToCrossPoint: Boolean = false,
    /** KOReader's sidecar files from a folder on this device (Android's folder access, as a link), when it was last read or what went wrong. */
    val koLocal: String? = null,
    val koLocalOkAt: Long = 0L,
    val koLocalError: String? = null,
    val koSentCp: Map<String, String> = emptyMap(),
    /**
     * EPUBs found in that folder (path -> "last modified|size|document ID"), so a KOReader
     * highlight can be put on its exact words on CrossPoint; the ones sent placed like that, and
     * the ones whose words couldn't be found just once.
     */
    val koBooks: Map<String, String> = emptyMap(),
    /** Titles and authors of KOReader's books (document ID -> "title<TAB>author"), from the EPUBs and sidecars in that folder. */
    val koTitles: Map<String, String> = emptyMap(),
    /**
     * KOReader books known only by their document ID: how many highlights each has and a few of
     * them ("count\u001Ftext\u001Ftext…"), to show them and to guess the book from its words.
     */
    val koUnnamed: Map<String, String> = emptyMap(),
    /**
     * Web novel EPUBs: the story's link found inside each (by KOReader document ID -> "link<TAB>chapters
     * in that copy"; empty when the EPUB has none), so the book links to its story and a downloaded-again
     * copy (a new ID) stays the same book.
     */
    val koStoryLinks: Map<String, String> = emptyMap(),
    /** KOReader document IDs known to be another copy of a book here (ID -> the book's document). */
    val koSameAs: Map<String, String> = emptyMap(),
    // ---- Threads: new highlights posted there by themselves ----
    /** Your Threads access token (from your own Meta app), its account, and when it was last renewed. */
    val threadsToken: String? = null,
    val threadsUser: String? = null,
    val threadsUserId: String? = null,
    val threadsTokenAt: Long = 0,
    /** Post each new highlight, quote and note to Threads by itself, as a picture in a look picked at random. */
    val threadsAuto: Boolean = false,
    /** At most this many posts a day (the rest wait for the next day). */
    val threadsPerDay: Int = 5,
    /** Notes on their own (no passage) are posted too. */
    val threadsNotes: Boolean = true,
    /** Your note goes on the picture and in the post with its passage. */
    val threadsWithNote: Boolean = true,
    /** Highlights there were when posting was turned on (not posted), and ones posted (id -> when). */
    val threadsSeen: Set<String> = emptySet(),
    val threadsPosted: Map<String, Long> = emptyMap(),
    /** Tries that failed, per highlight (given up after a few). */
    val threadsFails: Map<String, Int> = emptyMap(),
    val threadsError: String? = null,
    /** The look used last, so the next one is different. */
    val threadsLastLook: String? = null,
    /** Any of the looks at random, rather than the Book card's layouts (the book's cover with the passage). */
    val threadsAnyLook: Boolean = false,
    /** Books whose new highlights go to Threads (each turned on on its own page; off to start with). */
    val threadsBooks: Set<String> = emptySet(),
    /** Books whose review was posted to Threads, and when. */
    val threadsReviewed: Map<String, Long> = emptyMap(),
    /** A reading session's highlights from one book go up together as one post (else one post each). */
    val threadsPerSession: Boolean = true,
    /** The post's topic tag: "" none, "genre" from the book (LitRPG, Fantasy…, else Books), or your own. */
    val threadsTopic: String = "genre",
    /** When each highlight waiting to be posted first came in (to tell when a session's over). */
    val threadsArrived: Map<String, Long> = emptyMap(),
    /** When each post went up (the daily limit counts posts, not highlights). */
    val threadsPostLog: List<Long> = emptyList(),
    /**
     * With an on-device model: posts that give the story away go up hidden behind Threads' spoiler
     * tap, and a book that isn't speculative fiction gets its topic picked (Romance, Thriller…).
     */
    val threadsAi: Boolean = true,
    /** With an on-device model: the opening line written by it, in your voice (checked; else the usual wording). */
    val threadsAiCaptions: Boolean = false,
    /** Topics picked for books (by the on-device model), so each is asked once. */
    val threadsTopics: Map<String, String> = emptyMap(),
    /**
     * Services linked on your crosspoint-sync server too (its "Connected services", by id), so
     * CrossPoint's books aren't sent to them twice: the server sends those, this app the rest.
     */
    val serverLinked: Set<String> = emptySet(),
    val serverLinkedAt: Long = 0L,
    // ---- Highlights, quotes and notes exported to Readwise, Notion and Obsidian ----
    /** Readwise's access token (readwise.io/access_token), and the highlights already sent there. */
    val readwiseToken: String? = null,
    val readwiseDone: Set<String> = emptySet(),
    /** Notion: your integration's secret, the page books go under, each book's page there, and what's been added. */
    val notionToken: String? = null,
    val notionParent: String? = null,
    val notionPages: Map<String, String> = emptyMap(),
    val notionDone: Set<String> = emptySet(),
    /** Obsidian: the folder in your vault (Android folder access), and each book file's last contents (a hash), so unchanged ones aren't written again. */
    val obsidianFolder: String? = null,
    val obsidianHashes: Map<String, String> = emptyMap(),
    /** Each export's last problem ("readwise" -> why), and when it last worked. */
    val exportErrors: Map<String, String> = emptyMap(),
    val exportOkAt: Map<String, Long> = emptyMap(),
    /** How many pages KOReader lays each book out in (book document -> pages), to place its highlights in the book. */
    val koPages: Map<String, Int> = emptyMap(),
    /** A folder of EPUBs on that WebDAV storage (a Calibre library, say), for KOReader on another device: titles and exact places come from there. */
    val koDavBooks: String? = null,
    val koPlaced: Set<String> = emptySet(),
    val koUnplaced: Set<String> = emptySet(),
    /**
     * CrossPoint highlights are also given to KOReader, through the HighlightSync plugin's file on
     * WebDAV (needs the book's EPUB in that folder); which went (CrossPoint ids), when it last
     * ran or what went wrong.
     */
    val cpToKo: Boolean = false,
    /**
     * Books whose position came from KOReader (or another app using KOReader's sync), seen in
     * CrossPoint's feed or on a KOSync server; when that was first seen, and whether you said
     * "Not now" to bringing in KOReader's highlights.
     */
    val koreaderDocs: Set<String> = emptySet(),
    val koSeenAt: Long = 0L,
    val koNudgeOff: Boolean = false,
    val cpSentKo: Set<String> = emptySet(),
    val cpToKoOkAt: Long = 0L,
    val cpToKoNote: String? = null,
    val kindle: KindleAccount? = null,
    val kindleOkAt: Long = 0L,
    val kindleError: String? = null,
    val kindleClips: List<io.github.prithirajn.readync.source.Clipping> = emptyList(),
    /** Mihon: where its backups are, when they were last read, or what went wrong. */
    val mihon: MihonSource? = null,
    val mihonOkAt: Long = 0L,
    val mihonError: String? = null,
    /** Each manga series' volumes and chapters from MangaDex (Mihon series key -> map). */
    val mangaMaps: Map<String, io.github.prithirajn.readync.source.MangaDex.Map> = emptyMap(),
    /** Series MangaDex had no volumes for (series key -> unix seconds), tried again later. */
    val mangaMissing: Map<String, Long> = emptyMap(),
    /** MangaUpdates: the sign-in, its series for each Mihon series, and the chapter last sent. */
    val mangaUpdates: MangaUpdatesAccount? = null,
    val mangaUpdatesOkAt: Long = 0L,
    val mangaUpdatesError: String? = null,
    val muSeries: Map<String, Long> = emptyMap(),
    val muSent: Map<String, Int> = emptyMap(),
    /** AniList: the sign-in, when it last worked or what went wrong, its series for each series here, and the chapter last sent. */
    val anilist: AniListAccount? = null,
    /** An AniList client id typed in but not signed in yet. */
    val anilistClientId: String? = null,
    val anilistOkAt: Long = 0L,
    val anilistError: String? = null,
    val alSeries: Map<String, Long> = emptyMap(),
    val alSent: Map<String, Int> = emptyMap(),
    /** Each comic's collected volumes (series key -> which issues each holds), and when a lookup found none. */
    val comicMaps: Map<String, io.github.prithirajn.readync.source.ComicCollections.Map> = emptyMap(),
    val comicMissing: Map<String, Long> = emptyMap(),
    /** Issues a volume you set for a comic yourself (series key -> count); beats any lookup. */
    val comicPerVolume: Map<String, Int> = emptyMap(),
    /** Which issues each volume holds, as you set them for a comic (series key -> volumes). */
    val comicCustom: Map<String, io.github.prithirajn.readync.source.ComicCollections.Map> = emptyMap(),
    /** Per manga or comic series (series key): what you've read, for its volumes' issue lists. */
    val seriesRead: Map<String, SeriesRead> = emptyMap(),
    /** Pages in a chapter or issue of a series, as you set it (series key -> pages), for the one you're part-way through. */
    val pagesPer: Map<String, Int> = emptyMap(),
    /** Single-issue covers found ("series|issue" -> image link, or "" when none was found). */
    val issueCovers: Map<String, String> = emptyMap(),
    /** New & upcoming: what's been found, its settings, series you've hidden, and when each series was last looked at. */
    val releases: Map<String, Release> = emptyMap(),
    val releaseSettings: ReleaseSettings = ReleaseSettings(),
    val releasesHidden: Set<String> = emptySet(),
    val releasesChecked: Map<String, Long> = emptyMap(),
    /** Manga and comics you log by hand (key "logged:<title>" -> where you are). */
    val logged: Map<String, LoggedSeries> = emptyMap(),
    /** Audible: the device sign-in, and when it last worked or what went wrong. */
    val audible: AudibleAccount? = null,
    val audibleOkAt: Long = 0L,
    val audibleError: String? = null,
    /** Libby: the sign-in, when it last worked or what went wrong, and highlights it handed over. */
    val libby: LibbyAccount? = null,
    val libbyOkAt: Long = 0L,
    val libbyError: String? = null,
    val libbyClips: List<io.github.prithirajn.readync.source.Clipping> = emptyList(),
    /** Highlights from Moon+ Reader's sync files (its ".an" files), on the books they're from. */
    val moonClips: List<io.github.prithirajn.readync.source.Clipping> = emptyList(),
    /**
     * Highlights brought in with a reading history (Hardcover's quotes…). Already on the tracker
     * they came from, so they're marked as sent there and never sent back.
     */
    val historyClips: List<io.github.prithirajn.readync.source.Clipping> = emptyList(),
    /**
     * Day by day: what you read of each book on each day, from wherever it's known (KOReader's
     * statistics, Hardcover's reading journal…). document -> ("2024-03-09" -> that day).
     */
    val readingDays: Map<String, Map<String, ReadDay>> = emptyMap(),
    /** Each Moon+ sync file's "last modified" when it was last read (path -> as the server gives it), so unchanged ones aren't fetched again. */
    val moonMods: Map<String, String> = emptyMap(),
    /** Libby titles whose page count couldn't be found, so they aren't looked up on every sync. */
    val libbyNoPages: Set<String> = emptySet(),
    /** When Kindle was last asked (ms), worked or not: Amazon is asked at most every few hours. */
    val kindleTriedAt: Long = 0L,
    /**
     * Each Kindle book's highlights as last fetched (ASIN -> the notebook's "last annotated" line,
     * or "t:<seconds>" when it shows none): fetched again only when that changes.
     */
    val kindleSeen: Map<String, String> = emptyMap(),
    /** Kindle book lengths in locations (ASIN -> locations), for placing highlights in the book. */
    val kindleLengths: Map<String, Int> = emptyMap(),
    /** Highlights photographed from paper pages, sent like CrossPoint's. */
    val paperClips: List<io.github.prithirajn.readync.source.Clipping> = emptyList(),
    /** CrossPoint's highlights as last read from crosspoint-sync, to show under each book. */
    val cpClips: List<io.github.prithirajn.readync.source.Clipping> = emptyList(),
    /** Your edits to CrossPoint highlights (by clipping id): what's shown and sent instead. */
    val clipEdits: Map<String, ClipEdit> = emptyMap(),
    /** The text of a highlight's page, read off a photo of it (for book photos), by highlight id. */
    val clipPages: Map<String, String> = emptyMap(),
    /** Where a highlight went, per tracker ("service|clipId" -> the entry's id there), to update it later. */
    val clipRemote: Map<String, String> = emptyMap(),
    /** The words each highlight had when it went to a tracker ("service|clipId" -> their fingerprint), to notice edits. */
    val sentHash: Map<String, Int> = emptyMap(),
    /** Each sent highlight's words (clipId -> letters and digits), so a copy that grew out of one since gone is known. */
    val sentWords: Map<String, String> = emptyMap(),
    /** Highlights a tracker refused ("service|clipId" -> times); after 3 they're left out so the rest still go. */
    val clipFails: Map<String, Int> = emptyMap(),
    /** Highlights to send again ("service|clipId"), replacing the earlier entry where the tracker allows. */
    val resend: Set<String> = emptySet(),
    /** CrossPoint highlights you deleted here only (by id): not shown or sent. */
    val clipHidden: Set<String> = emptySet(),
    /**
     * Check highlights before sending: when on, a new highlight goes to no tracker (and isn't
     * posted to Threads) until you've marked it fine, a spoiler, or not to send.
     */
    val checkHighlights: Boolean = false,
    /** For trackers that post publicly with no spoiler setting (Goodreads, Micro.blog): "hold" a spoiler back, or "warn" above it. */
    val spoilerPublic: String = "hold",
    /** What you made of each highlight (by id): "ok", "spoiler" or "skip" (kept here, sent nowhere). */
    val clipChecked: Map<String, String> = emptyMap(),
    /** Books whose price you're watching (by document): where, what you'd pay, and every price seen. */
    val priceWatch: Map<String, PriceWatch> = emptyMap(),
    /** Your rating rubric: the things you score books on ("Plot", "Characters"…), up to eight. Empty: off. */
    val rubric: List<String> = emptyList(),
    /** Each book's rubric scores (by document, then category), 1 to 10. */
    val rubricScores: Map<String, Map<String, Int>> = emptyMap(),
    /** Positions the reader reported per book (newest last, a few hundred kept), to place highlights. */
    val positions: Map<String, List<PositionPoint>> = emptyMap(),
    /** What was sent to each tracker for each book ("service|document"), newest last. */
    val sent: Map<String, List<SendEvent>> = emptyMap(),
    /** CrossPoint books given Seekquel's position from its prompt, to send back to crosspoint-sync. */
    val toCrossPoint: Set<String> = emptySet(),
    /** The same books, to send to the KOSync server. */
    val toKosync: Set<String> = emptySet(),
    /** Entries on trackers to delete on the next sync ("service|entry id"). */
    val clipDeletes: Set<String> = emptySet(),
    /** Each book's Seekquel rating as last seen (document -> stars), to notice when you change it there. */
    val seekquelRatings: Map<String, Double> = emptyMap(),
    /** Books whose start and finish days come from Seekquel (document -> "started|finished" as last seen). */
    val seekquelDates: Map<String, String> = emptyMap(),
    /** Books where Seekquel has a different position than here, waiting for you to choose. */
    val progressAsks: Map<String, ProgressAsk> = emptyMap(),
    /** Seekquel positions you chose not to use (document -> percent), so they aren't asked again. */
    val progressDenied: Map<String, Int> = emptyMap(),
    /** Finished books a source reports as being read again (past 5%): "Reading it again?" on the book, asked once. */
    val rereadAsks: Map<String, ProgressAsk> = emptyMap(),
    /** "No" to that (document -> the book's finish time then), so that read isn't asked about again. */
    val rereadDenied: Map<String, Long> = emptyMap(),
    /** Books you moved back yourself (document -> unix seconds): trackers further along are brought back to it. */
    val backAt: Map<String, Long> = emptyMap(),
    /** Pairs of books you said aren't the same ("docA|docB", sorted), so they aren't asked about again. */
    val keptApart: Set<String> = emptySet(),
    /** What parts of the app outside the public code keep (name -> their JSON), saved with the rest. */
    val extras: Map<String, String> = emptyMap(),
    /** How many books were waiting to be turned on when you said "Not now" (asked again when more come in). */
    val waitingSeen: Int = 0,
    /**
     * Each book's main source (see Primary): the one it first came from unless another took over;
     * its other sources by rank (secondary, tertiary…); each other source's run of days reading
     * further ("doc|source" -> "day:count"); and sources to set back to the position here even
     * though they're further along ("service|doc").
     */
    val primaries: Map<String, String> = emptyMap(),
    val sourceRanks: Map<String, List<String>> = emptyMap(),
    val sourceStreaks: Map<String, String> = emptyMap(),
    val moveBack: Set<String> = emptySet(),
    /**
     * Main sources you picked yourself (no automatic takeover for these books); sources whose
     * positions are taken without asking, for every book or one ("doc|source"); and the
     * different-position cards already notified ("doc" -> "source|percent").
     */
    val pinnedPrimary: Set<String> = emptySet(),
    val trustedSources: Set<String> = emptySet(),
    val trustedFor: Set<String> = emptySet(),
    val asksNotified: Map<String, String> = emptyMap(),
    /** Physical books now also on CrossPoint (physical document -> CrossPoint document), offered to continue there. */
    val handoffs: Map<String, String> = emptyMap(),
    /** The library's pages-read total as last counted, and today's starting point, for "today". */
    val pagesTotal: Int? = null,
    val pagesDay: String? = null,
    val pagesDayStart: Int = 0,
    /**
     * Where each book stood when today began, and when last seen (document -> 0–1), so today's
     * pages are what each book moved today: a book imported, a page count set or a copy merged
     * adds nothing.
     */
    val pagesStartF: Map<String, Double> = emptyMap(),
    val pagesLastF: Map<String, Double> = emptyMap(),
    /** Days you said weren't reading days (cleared on the calendar), so they aren't counted again. */
    val pagesCleared: Set<String> = emptySet(),
    /** Pages read each day ("2026-10-28" -> pages), the last two months. */
    val pagesByDay: Map<String, Int> = emptyMap(),
    /** Pairs you chose to keep apart ("physical|crosspoint"). */
    val handoffDenied: Set<String> = emptySet(),
    /** Books that are web novels or fan fiction (document -> where and what). */
    val webNovels: Map<String, WebNovel> = emptyMap(),
    /** When each book was last searched for on the web novel sites (document -> unix seconds). */
    val webSearched: Map<String, Long> = emptyMap(),
    /**
     * When a book got longer under you (new chapters downloaded: the percentage fell while you
     * read on), document -> unix seconds. A tracker further along than the new percentage is
     * then brought to it, instead of being left as "further along".
     */
    val grewAt: Map<String, Long> = emptyMap(),
    /** Books removed from library (document -> percentage when removed). Not re-added unless further progress occurs. */
    val removedBooks: Map<String, Double> = emptyMap(),
    /**
     * Books you chose to read again (document -> unix seconds). Trackers that still have them as
     * Finished are moved back to Reading once, instead of being left alone.
     */
    val rereads: Map<String, Long> = emptyMap(),
    /**
     * When a book's description was last looked for and none was found (document -> unix seconds):
     * looked for again after a few days, so a lookup that found nothing isn't final.
     */
    val detailsTried: Map<String, Long> = emptyMap(),
    /** Series, community rating, year, moods, genres and content warnings per book (document -> facts). */
    val facts: Map<String, BookFacts> = emptyMap(),
    /** Where a book's description was looked for and not found (document -> "Seekquel, Hardcover, …"). */
    val detailsLooked: Map<String, String> = emptyMap(),
    /** Matches the on-device model suggests for books the rules couldn't match (tracker|document -> its pick). */
    val aiSuggestions: Map<String, AiSuggestion> = emptyMap(),
    /** Speculative-fiction radar updates from all sources, and the Star-Chart probe state. */
    val sffItems: List<SffFeedItem> = emptyList(),
    val sffUpdatedAt: Long = 0L,
    val sffLoading: Boolean = false,
    val sffError: String? = null,
    val sffProbe: SffProbeState = SffProbeState(),
    val sffProfile: SffArtifactProfile = SffArtifactProfile(),
    val sffSky: SffSky = SffSky(),
    val settings: SyncSettings = SyncSettings(),
) {
    fun service(id: String): ServiceState = services[id] ?: ServiceState(id)
    fun link(service: String, document: String): Link? = links[Link.key(service, document)]
}
