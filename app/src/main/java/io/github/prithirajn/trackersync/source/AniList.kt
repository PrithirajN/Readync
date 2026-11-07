package io.github.prithirajn.trackersync.source

import io.github.prithirajn.trackersync.net.AuthException
import io.github.prithirajn.trackersync.net.Http
import io.github.prithirajn.trackersync.net.ServiceException
import org.json.JSONArray
import org.json.JSONObject

/**
 * AniList: your manga list, read and kept in step. Mihon (on any device) sends each chapter you
 * finish to AniList, so reading your AniList list here sees what you read on your other phone or
 * tablet. Sign-in is AniList's own token page, through an API client you register on your account
 * (AniList gives every app its own); the token lasts a year.
 */
object AniList {
    var base = "https://graphql.anilist.co"
    var site = "https://anilist.co"

    /** The page that asks you to allow the app; AniList then shows (or redirects with) the token. */
    fun authorizeUrl(clientId: String) = "$site/api/v2/oauth/authorize?client_id=${Http.enc(clientId.trim())}&response_type=token"

    /** The redirect URL to register: AniList's own page that shows the token. */
    const val PIN_REDIRECT = "https://anilist.co/api/v2/oauth/pin"

    /** The token from where AniList sent the browser ("…#access_token=…&token_type=…"), or null. */
    fun tokenFrom(url: String): String? {
        val tail = url.substringAfter('#', "").ifEmpty { url.substringAfter('?', "") }
        return tail.split('&').firstOrNull { it.startsWith("access_token=") }?.substringAfter('=')
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }?.takeIf { looksLikeToken(it) }
    }

    /** AniList's tokens are long JWTs (three dot-separated parts). */
    fun looksLikeToken(s: String): Boolean {
        val t = s.trim()
        return t.length > 100 && t.count { it == '.' } == 2 && t.none { it.isWhitespace() }
    }

    private fun call(token: String?, query: String, variables: JSONObject = JSONObject()): JSONObject {
        val h = buildMap {
            put("Accept", "application/json")
            put("Content-Type", "application/json")
            token?.let { put("Authorization", "Bearer ${it.trim()}") }
        }
        val r = Http.request("POST", base, h, JSONObject().put("query", query).put("variables", variables).toString())
        val o = runCatching { JSONObject(r.body) }.getOrNull()
        val err = o?.optJSONArray("errors")?.optJSONObject(0)
        if (r.code == 401 || err?.optInt("status") == 401 || err?.optString("message")?.contains("Invalid token", true) == true) {
            throw AuthException("AniList signed you out; sign in again")
        }
        if (r.code == 429) throw ServiceException("AniList is busy; tried again next sync")
        if (!r.ok || o == null) throw ServiceException(err?.optString("message")?.takeIf { it.isNotBlank() } ?: "AniList answered HTTP ${r.code}")
        if (err != null && o.optJSONObject("data") == null) throw ServiceException(err.optString("message", "AniList refused that"))
        return o.optJSONObject("data") ?: JSONObject()
    }

    data class Viewer(val id: Long, val name: String)

    fun viewer(token: String): Viewer {
        val v = call(token, "query { Viewer { id name } }").optJSONObject("Viewer") ?: throw AuthException("AniList didn't accept that token")
        return Viewer(v.optLong("id"), v.optString("name"))
    }

    /** One title on your AniList manga list. */
    data class Entry(
        val mediaId: Long,
        val malId: Long?,
        val title: String,
        val otherTitles: List<String>,
        val author: String?,
        val coverUrl: String?,
        /** MANGA, ONE_SHOT, NOVEL… */
        val format: String?,
        /** JP, KR, CN, or elsewhere (US for comics). */
        val country: String?,
        /** CURRENT, REPEATING, COMPLETED, PAUSED, PLANNING, DROPPED. */
        val status: String,
        /** Chapters read. */
        val progress: Int,
        val progressVolumes: Int,
        /** Chapters in all, when it's finished publishing. */
        val chapters: Int?,
        /** Unix seconds. */
        val updatedAt: Long?,
    )

    private const val LIST_QUERY = """
query (${'$'}user: Int) {
  MediaListCollection(userId: ${'$'}user, type: MANGA, status_in: [CURRENT, REPEATING, COMPLETED]) {
    lists { entries {
      status progress progressVolumes updatedAt
      media {
        id idMal format countryOfOrigin chapters
        title { userPreferred english romaji }
        synonyms
        coverImage { large }
        staff(perPage: 4, sort: [RELEVANCE]) { edges { role node { name { full } } } }
      }
    } }
  }
}"""

    /** Your manga list: reading, rereading and finished. */
    fun list(token: String, userId: Long): List<Entry> = parseList(call(token, LIST_QUERY, JSONObject().put("user", userId)))

    internal fun parseList(data: JSONObject): List<Entry> {
        val lists = data.optJSONObject("MediaListCollection")?.optJSONArray("lists") ?: return emptyList()
        val out = LinkedHashMap<Long, Entry>()
        for (i in 0 until lists.length()) {
            val es = lists.optJSONObject(i)?.optJSONArray("entries") ?: continue
            for (j in 0 until es.length()) {
                val e = es.optJSONObject(j) ?: continue
                val m = e.optJSONObject("media") ?: continue
                val id = m.optLong("id").takeIf { it > 0 } ?: continue
                val t = m.optJSONObject("title")
                val title = listOf("english", "userPreferred", "romaji").firstNotNullOfOrNull { k -> t?.optString(k)?.takeIf { it.isNotBlank() && it != "null" } } ?: continue
                val others = (listOf("userPreferred", "romaji", "english").mapNotNull { k -> t?.optString(k)?.takeIf { it.isNotBlank() && it != "null" } } +
                    (m.optJSONArray("synonyms")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty())).filter { it != title }.distinct()
                val edges = m.optJSONObject("staff")?.optJSONArray("edges") ?: JSONArray()
                val people = (0 until edges.length()).mapNotNull { k -> edges.optJSONObject(k) }
                val author = (people.firstOrNull { it.optString("role").contains("Story", true) } ?: people.firstOrNull())
                    ?.optJSONObject("node")?.optJSONObject("name")?.optString("full")?.takeIf { it.isNotBlank() && it != "null" }
                out[id] = Entry(
                    mediaId = id,
                    malId = m.optLong("idMal").takeIf { it > 0 },
                    title = title,
                    otherTitles = others,
                    author = author,
                    coverUrl = m.optJSONObject("coverImage")?.optString("large")?.takeIf { it.startsWith("http") },
                    format = m.optString("format").takeIf { it.isNotBlank() && it != "null" },
                    country = m.optString("countryOfOrigin").takeIf { it.isNotBlank() && it != "null" },
                    status = e.optString("status"),
                    progress = e.optInt("progress"),
                    progressVolumes = e.optInt("progressVolumes"),
                    chapters = m.optInt("chapters").takeIf { it > 0 },
                    updatedAt = e.optLong("updatedAt").takeIf { it > 0 },
                )
            }
        }
        return out.values.toList()
    }

    /** The AniList series for a title (manga, manhwa, manhua or comic), or null. */
    fun find(title: String, malId: Long? = null): Long? {
        if (malId != null) {
            val d = runCatching { call(null, "query (${'$'}mal: Int) { Media(idMal: ${'$'}mal, type: MANGA) { id } }", JSONObject().put("mal", malId)) }.getOrNull()
            d?.optJSONObject("Media")?.optLong("id")?.takeIf { it > 0 }?.let { return it }
        }
        val d = call(null, "query (${'$'}q: String) { Page(perPage: 8) { media(search: ${'$'}q, type: MANGA) { id title { english romaji userPreferred } synonyms } } }", JSONObject().put("q", title))
        val hits = d.optJSONObject("Page")?.optJSONArray("media") ?: return null
        val want = io.github.prithirajn.trackersync.sync.Matcher.baseTitle(title)
        for (i in 0 until hits.length()) {
            val m = hits.optJSONObject(i) ?: continue
            val t = m.optJSONObject("title")
            val names = listOf("english", "romaji", "userPreferred").mapNotNull { t?.optString(it)?.takeIf { s -> s.isNotBlank() && s != "null" } } +
                (m.optJSONArray("synonyms")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty())
            if (names.any { io.github.prithirajn.trackersync.sync.Matcher.baseTitle(it) == want }) return m.optLong("id")
        }
        return null
    }

    /** Your entry for a series: (status, chapters read), or null when it isn't on your list. */
    fun entry(token: String, mediaId: Long): Pair<String, Int>? {
        val d = call(token, "query (${'$'}id: Int) { Media(id: ${'$'}id) { mediaListEntry { status progress } } }", JSONObject().put("id", mediaId))
        val e = d.optJSONObject("Media")?.optJSONObject("mediaListEntry") ?: return null
        return e.optString("status") to e.optInt("progress")
    }

    /**
     * Sets the chapters read on a series (adding it as Reading when it isn't on your list). Never
     * moves you back, and leaves a reread (REPEATING) as it is.
     */
    fun setProgress(token: String, mediaId: Long, chapter: Int, finished: Boolean = false) {
        val now = entry(token, mediaId)
        if (now != null && now.second >= chapter && (!finished || now.first == "COMPLETED")) return
        val status = when {
            finished -> "COMPLETED"
            now?.first == "REPEATING" -> "REPEATING"
            else -> "CURRENT"
        }
        call(
            token,
            "mutation (${'$'}id: Int, ${'$'}p: Int, ${'$'}s: MediaListStatus) { SaveMediaListEntry(mediaId: ${'$'}id, progress: ${'$'}p, status: ${'$'}s) { id progress } }",
            JSONObject().put("id", mediaId).put("p", maxOf(chapter, now?.second ?: 0)).put("s", status),
        )
    }
}
