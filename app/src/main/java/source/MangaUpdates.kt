package io.github.prithirajn.readync.source

import io.github.prithirajn.readync.net.AuthException
import io.github.prithirajn.readync.net.Http
import io.github.prithirajn.readync.net.ServiceException
import io.github.prithirajn.readync.sync.Matcher
import org.json.JSONArray
import org.json.JSONObject

/**
 * MangaUpdates (Baka-Updates), a manga tracker that keeps your place by chapter. Signs in with
 * your username and password (once; only the session is kept), finds the series, puts it on your
 * Reading list, and sets the chapter you're on. Calls as Mihon's own MangaUpdates tracker makes them.
 */
object MangaUpdates {
    var base = "https://api.mangaupdates.com"

    const val READING_LIST = 0
    const val COMPLETE_LIST = 2

    private fun headers(token: String?) = buildMap {
        put("Accept", "application/json")
        put("Content-Type", "application/json")
        put("User-Agent", "TrackerSync (Android; github.com/PrithirajN)")
        token?.let { put("Authorization", "Bearer $it") }
    }

    /** Signs in; returns the session token. */
    fun login(username: String, password: String): String {
        val r = Http.request("PUT", "$base/v1/account/login", headers(null), JSONObject().put("username", username).put("password", password).toString())
        val o = runCatching { JSONObject(r.body) }.getOrNull()
        if (r.code == 401 || r.code == 400) throw AuthException(o?.optString("reason")?.takeIf { it.isNotBlank() } ?: "MangaUpdates didn't accept that username and password")
        if (!r.ok) throw ServiceException("MangaUpdates answered HTTP ${r.code}")
        return o?.optJSONObject("context")?.optString("session_token")?.takeIf { it.isNotBlank() }
            ?: throw ServiceException("MangaUpdates sent no session")
    }

    data class Series(val id: Long, val title: String, val type: String?, val year: String?)

    fun search(title: String): List<Series> {
        val body = JSONObject().put("search", Matcher.plainTitle(title)).put("filter_types", JSONArray(listOf("drama cd", "novel")))
        val r = Http.request("POST", "$base/v1/series/search", headers(null), body.toString())
        if (!r.ok) return emptyList()
        return parseSearch(r.body)
    }

    internal fun parseSearch(body: String): List<Series> {
        val a = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val rec = a.optJSONObject(i)?.optJSONObject("record") ?: return@mapNotNull null
            val id = rec.optLong("series_id", 0L).takeIf { it > 0 } ?: return@mapNotNull null
            Series(id, io.github.prithirajn.readync.util.Html.decode(rec.optString("title")), rec.optString("type").takeIf { it.isNotBlank() }, rec.optString("year").takeIf { it.isNotBlank() })
        }
    }

    /** The series that is [title], when one clearly is. */
    fun find(title: String): Long? {
        val hits = search(title)
        return (hits.firstOrNull { Matcher.baseTitle(it.title) == Matcher.baseTitle(title) } ?: hits.firstOrNull()?.takeIf { Matcher.score(title, null, io.github.prithirajn.readync.trackers.RemoteBook("", it.title, null)) >= 0.75 })?.id
    }

    /** Your list entry for the series: (list, chapter), or null when it's on none of your lists. */
    fun entry(token: String, seriesId: Long): Pair<Int, Int>? {
        val r = Http.request("GET", "$base/v1/lists/series/$seriesId", headers(token))
        if (r.code == 401) throw AuthException("MangaUpdates signed you out; sign in again")
        if (r.code == 404) return null
        if (!r.ok) throw ServiceException("MangaUpdates answered HTTP ${r.code}")
        val o = runCatching { JSONObject(r.body) }.getOrNull() ?: return null
        return o.optInt("list_id", READING_LIST) to (o.optJSONObject("status")?.optInt("chapter", 0) ?: 0)
    }

    /**
     * Puts the series on your Reading list (or Complete, when [finished]) at [chapter]. Adds it
     * when it isn't on a list yet; never moves your place back.
     */
    fun setChapter(token: String, seriesId: Long, chapter: Int, finished: Boolean = false) {
        val now = entry(token, seriesId)
        if (now != null && now.second >= chapter && (!finished || now.first == COMPLETE_LIST)) return
        val list = if (finished) COMPLETE_LIST else (now?.first?.takeIf { it != 1 } ?: READING_LIST) // off the Wish list once started
        val item = JSONObject().put("series", JSONObject().put("id", seriesId)).put("list_id", list)
            .put("status", JSONObject().put("chapter", maxOf(chapter, now?.second ?: 0)))
        val path = if (now == null) "/v1/lists/series" else "/v1/lists/series/update"
        val r = Http.request("POST", base + path, headers(token), JSONArray().put(item).toString())
        if (r.code == 401) throw AuthException("MangaUpdates signed you out; sign in again")
        if (!r.ok) throw ServiceException("MangaUpdates answered HTTP ${r.code}")
    }
}
