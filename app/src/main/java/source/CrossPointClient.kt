package io.github.prithirajn.readync.source

import io.github.prithirajn.readync.data.Account
import io.github.prithirajn.readync.data.ReadStatus
import io.github.prithirajn.readync.net.AuthException
import io.github.prithirajn.readync.net.Http
import io.github.prithirajn.readync.net.HttpResponse
import io.github.prithirajn.readync.net.NetworkException
import io.github.prithirajn.readync.net.ServiceException
import io.github.prithirajn.readync.util.arr
import io.github.prithirajn.readync.util.bool
import io.github.prithirajn.readync.util.int
import io.github.prithirajn.readync.util.long
import io.github.prithirajn.readync.util.num
import io.github.prithirajn.readync.util.obj
import io.github.prithirajn.readync.util.objects
import io.github.prithirajn.readync.util.str
import io.github.prithirajn.readync.util.strings
import org.json.JSONObject
import java.security.MessageDigest

/** One entry of GET /api/v1/progress/changes. */
data class Change(
    val document: String,
    val aliases: List<String>,
    val percentage: Double,
    val timestamp: Long,
    val device: String?,
    /** The reader's chapter (spine item) index at that position, when it sends one. */
    val spine: Int? = null,
    /** The sending app's own id ("crosspoint-reader" for a CrossPoint device, a random one for KOReader). */
    val deviceId: String? = null,
)

/** A highlight from GET /api/v1/clippings. [createdAt] is unix seconds. */
data class Clipping(
    val id: String,
    val document: String,
    val text: String,
    val note: String?,
    val chapter: String?,
    val createdAt: Long,
    /** The reader's chapter (spine item) index, from crosspoint-sync. */
    val spine: Int? = null,
    /** Where in the book it is (0–1): known for physical books, worked out for CrossPoint's. */
    val percent: Double? = null,
    /** Its print page, filled in when sending, for trackers that take one. */
    val page: Int? = null,
    /** CrossPoint's exact place in the chapter (visible code points), when the server has it. Not kept. */
    val start: Int? = null,
    val end: Int? = null,
    /** Marked a spoiler (by you, with Check highlights before sending): trackers with a spoiler setting get it on. Not kept. */
    val spoiler: Boolean = false,
    /** Going to a public tracker with no spoiler setting: a spoiler warning goes above the passage. Not kept. */
    val spoilerWarning: Boolean = false,
) {
    /**
     * Its page in a copy of [total] pages: the print page when known, else worked out from its exact
     * place (42.37%, not 42%), so two highlights a few pages apart don't land on the same page.
     */
    fun pageIn(total: Int?): Int? = page ?: percent?.let { p ->
        total?.takeIf { it > 0 }?.let { t -> Math.round(p.coerceIn(0.0, 1.0) * t).toInt().coerceIn(1, t) }
    }
}

data class ChangesPage(val cursor: Long, val more: Boolean, val changes: List<Change>)

/** One entry of GET /api/v1/progress (the library). */
data class LibraryItem(
    val document: String,
    val title: String?,
    val author: String?,
    val percentage: Double,
    val status: ReadStatus?,
    val timestamp: Long,
    val coverUrl: String? = null,
    /** Print-edition page count (looked up by the server, or set by hand). */
    val pageCount: Int? = null,
    /** The app that sent its newest position (see [Change.deviceId]). */
    val device: String? = null,
    val deviceId: String? = null,
)

/** A cover to choose from (GET /documents/{d}/cover/candidates). */
data class CoverCandidate(val url: String, val source: String?, val title: String?, val pages: Int?)

/** One book's reading activity: print pages read so far, and when it was finished (unix seconds). */
data class BookActivity(val document: String, val pagesRead: Int?, val finishedAt: Long?, val startedAt: Long? = null)

/** When a book was started and finished (unix seconds), and whether either was set by hand. */
data class BookDates(val started: Long?, val finished: Long?, val startManual: Boolean, val finishManual: Boolean)

/**
 * Read-only client for a crosspoint-sync server (sync.crosspointreader.com or self-hosted).
 * See crosspoint-sync docs/API.md. Auth is the KOSync pair: username + MD5 of the password.
 */
class CrossPointClient(private val account: Account) {

    private fun headers() = mapOf(
        "x-auth-user" to account.username,
        "x-auth-key" to account.key,
        "Accept" to "application/json",
    )

    private fun get(path: String): JSONObject {
        val r = Http.request("GET", account.server + path, headers())
        check(r)
        return try {
            JSONObject(r.body)
        } catch (e: Exception) {
            throw ServiceException("crosspoint-sync sent something that isn't JSON")
        }
    }

    private fun check(r: HttpResponse) {
        if (r.code == 401) throw AuthException("crosspoint-sync rejected the username or password")
        if (r.code == 429) throw ServiceException("crosspoint-sync is rate-limiting this account; try again later")
        if (!r.ok) throw ServiceException("crosspoint-sync answered HTTP ${r.code}")
    }

    /** The ids of the services linked on the server (its Connected services). */
    fun linkedConnectors(): Set<String> {
        val arr = get("/api/v1/connectors").optJSONArray("connectors") ?: return emptySet()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .filter { it.optBoolean("linked", false) }.mapNotNull { it.optString("id").takeIf { id -> id.isNotEmpty() } }.toSet()
    }

    /** True when the username and password are right. */
    fun verify(): Boolean {
        val r = Http.request("GET", account.server + "/users/auth", headers())
        if (r.code == 401) return false
        check(r)
        return true
    }

    /**
     * Books whose newest position changed after [since]. Pass a [device] id that never
     * writes progress, so no book is left out of the feed.
     */
    fun changes(since: Long, device: String, limit: Int = 50): ChangesPage {
        val o = get("/api/v1/progress/changes?since=$since&limit=$limit&device=${Http.enc(device)}")
        val changes = (o.arr("changes")?.objects() ?: emptyList()).mapNotNull { c ->
            val doc = c.str("document") ?: return@mapNotNull null
            Change(
                document = doc,
                aliases = c.arr("aliases")?.strings() ?: emptyList(),
                percentage = c.num("percentage") ?: 0.0,
                timestamp = c.long("timestamp") ?: 0L,
                device = c.str("device"),
                spine = c.obj("position")?.int("spine"),
                deviceId = c.str("device_id"),
            )
        }
        return ChangesPage(o.long("cursor") ?: since, o.bool("more"), changes)
    }

    /** Every synced book, newest first, with title, author and effective status. */
    fun library(limit: Int = 500): List<LibraryItem> {
        val o = get("/api/v1/progress?limit=$limit")
        return (o.arr("items")?.objects() ?: emptyList()).mapNotNull { i ->
            val doc = i.str("document") ?: return@mapNotNull null
            LibraryItem(
                document = doc,
                title = i.str("title"),
                author = i.str("author"),
                percentage = i.num("percentage") ?: 0.0,
                status = ReadStatus.fromCrossPoint(i.str("status")),
                timestamp = i.long("timestamp") ?: 0L,
                coverUrl = i.str("cover_url"),
                pageCount = i.int("page_count"),
                device = i.str("device"),
                deviceId = i.str("device_id"),
            )
        }
    }

    /** A write: the server's own error message when it refuses, so the app can show it. */
    private fun send(method: String, path: String, body: JSONObject? = null): JSONObject {
        val h = if (body != null) headers() + ("Content-Type" to "application/json") else headers()
        val r = Http.request(method, account.server + path, h, body?.toString())
        if (r.code == 401) throw AuthException("crosspoint-sync rejected the username or password")
        if (!r.ok) {
            val msg = runCatching { JSONObject(r.body).str("message") }.getOrNull()
            throw ServiceException(msg ?: "crosspoint-sync answered HTTP ${r.code}")
        }
        return runCatching { JSONObject(r.body) }.getOrDefault(JSONObject())
    }

    private fun doc(document: String) = Http.enc(document)

    /**
     * Manual fixes (PUT /documents/{d}/info): title, author, cover_url, page_count… Only the
     * keys in [patch] change; a JSON null clears a field so it's looked up again.
     */
    fun setInfo(document: String, patch: JSONObject): JSONObject = send("PUT", "/api/v1/documents/${doc(document)}/info", patch)

    /** Reading dates as "YYYY-MM-DD"; a JSON null hands a date back to the reader's own. */
    fun setDates(document: String, dates: JSONObject): JSONObject = send("PUT", "/api/v1/documents/${doc(document)}/dates", dates)

    fun coverCandidates(document: String, query: String?): List<CoverCandidate> {
        val q = query?.trim()?.takeIf { it.isNotEmpty() }?.let { "?q=${Http.enc(it)}" } ?: ""
        val o = get("/api/v1/documents/${doc(document)}/cover/candidates$q")
        return (o.arr("items")?.objects() ?: emptyList()).mapNotNull { c ->
            val url = c.str("url") ?: return@mapNotNull null
            CoverCandidate(url, c.str("source"), c.str("title"), c.int("pages"))
        }
    }

    /** Folds [document] into [into]: its progress (and stats, if [stats]) move there for good. */
    fun merge(document: String, into: String, stats: Boolean) {
        send("POST", "/api/v1/documents/merge", JSONObject().put("document", document).put("into", into).put("stats", stats))
    }

    /** Undoes a merge: [alias] syncs as its own book again. */
    fun unmerge(alias: String) {
        send("DELETE", "/api/v1/documents/merge/${doc(alias)}")
    }

    /** Removes the book and everything stored for it on the server. */
    fun remove(document: String) {
        send("DELETE", "/api/v1/progress/${doc(document)}")
    }

    /** Every book's pages read and finish date (GET /api/v1/stats/activity). */
    fun activity(tzOffsetMinutes: Int): List<BookActivity> {
        val o = get("/api/v1/stats/activity?tz=$tzOffsetMinutes")
        return (o.arr("books")?.objects() ?: emptyList()).mapNotNull { b ->
            val d = b.str("document") ?: return@mapNotNull null
            BookActivity(d, b.int("pages_read"), b.long("finished_at"), b.long("started_at"))
        }
    }

    /** The book's start and finish dates, from the server's reading activity. */
    fun dates(document: String, tzOffsetMinutes: Int): BookDates? {
        val o = get("/api/v1/stats/activity?tz=$tzOffsetMinutes")
        val b = o.arr("books")?.objects()?.firstOrNull { it.str("document") == document } ?: return null
        return BookDates(b.long("started_at"), b.long("finished_at"), b.bool("start_manual"), b.bool("finish_manual"))
    }

    /** Looks up a cover for a book without one (iTunes, then Open Library, on the server). */
    fun cover(document: String): String? =
        get("/api/v1/documents/${Http.enc(document)}/cover").str("url")

    /** The book's description from Hardcover's catalog, or one set by hand. Null when there's none. */
    /**
     * Everything crosspoint-sync knows about a book on the reader: its title and author, the
     * language from the book file, description, genres, series and number, year, cover and
     * pages (some found by the server on Hardcover). Read with an empty edit, which the server
     * answers with the book's details without changing anything.
     */
    fun details(document: String): BookDetails? {
        val o = runCatching { setInfo(document, JSONObject()) }.getOrNull() ?: return null
        val title = o.str("title") ?: return null
        return BookDetails(
            title = title,
            authors = o.str("author")?.split(Regex("\\s*(?:,|&|\\band\\b)\\s*"))?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            pages = o.optInt("page_count").takeIf { it > 0 },
            published = o.optInt("release_year").takeIf { it > 0 }?.toString(),
            language = o.str("language")?.let { BookInfo.languageName(it) ?: it },
            description = o.str("description")?.trim()?.takeIf { it.isNotEmpty() },
            coverUrl = o.str("cover_url")?.takeIf { it.startsWith("http") },
            // Read on the CrossPoint reader: an ebook.
            format = "Ebook",
            series = o.str("series"),
            seriesIndex = o.opt("series_position").takeIf { it is Number }?.toString()?.removeSuffix(".0"),
            genres = o.optJSONArray("genres")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } } ?: emptyList(),
            source = "crosspoint-sync",
        )
    }

    fun about(document: String): Pair<String, Boolean>? {
        val o = get("/api/v1/documents/${Http.enc(document)}/about")
        val d = o.str("description")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return d to o.bool("manual")
    }

    /**
     * Every live highlight across all books, newest first. Only arrives from readers with
     * "Sync Clippings" turned on in their CrossPoint Sync settings.
     */
    /**
     * Sends a position set in this app (by hand, or Seekquel's) to crosspoint-sync, as a
     * percentage-only source, like the server's own imports: the reader takes it up on its next
     * sync as a newer position. [timestamp] is when it was set (unix seconds).
     */
    fun putProgress(document: String, percentage: Double, timestamp: Long, deviceId: String) {
        val p = percentage.coerceIn(0.0, 1.0)
        send(
            "PUT", "/api/v1/progress",
            JSONObject()
                .put("document", document)
                .put("progress", "tracker-sync:" + Math.round(p * 1_000_000))
                .put("percentage", p)
                .put("device", "Tracker Sync")
                .put("device_id", deviceId)
                .put("timestamp", timestamp),
        )
    }

    /** The book's reading status on crosspoint-sync ("reading", "finished", "dnf", "paused"). */
    fun putStatus(document: String, status: String) {
        send("PUT", "/api/v1/documents/${doc(document)}/status", JSONObject().put("status", status))
    }

    /** Deletes a highlight on crosspoint-sync; the reader drops it on its next sync. */
    fun deleteClipping(document: String, id: String) {
        send("PUT", "/api/v1/clippings/${doc(document)}", JSONObject().put("items", org.json.JSONArray().put(JSONObject().put("id", id).put("deleted", 1))))
    }

    /**
     * Changes (or with null, deletes) a highlight's note on crosspoint-sync. The server takes a
     * highlight whole, so it's read back first and sent with only the note changed.
     */
    /**
     * Adds highlights from elsewhere (KOReader) to a book on crosspoint-sync, so the reader has them
     * too: placed by chapter (spine), with their text and note. Returns each one's new id there
     * (by the input's id). The id follows crosspoint-sync's rule, so adding the same one twice is harmless.
     */
    /**
     * Adds (or updates) highlights on a book. With a [places] entry (by our id) a highlight is put
     * on its exact words, so the Xteink draws it on the page; without one it's listed under its
     * chapter. A highlight in [keepNote] leaves the note CrossPoint has alone (it's being placed
     * again, not changed).
     */
    fun addClippings(
        document: String, clips: List<Clipping>,
        places: Map<String, EpubText.Place> = emptyMap(), keepNote: Set<String> = emptySet(),
    ): Map<String, String> {
        val ids = HashMap<String, String>()
        for (batch in clips.filter { (it.spine != null || places[it.id] != null) && it.text.isNotBlank() }.chunked(50)) {
            val items = org.json.JSONArray()
            for (c in batch) {
                val id = clippingId(c.createdAt, c.text)
                val place = places[c.id]
                val item = JSONObject().put("id", id).put("spine", place?.spine ?: c.spine).put("text", c.text.take(4000))
                    .put("chapter", (c.chapter ?: "").take(64)).put("created_at", c.createdAt)
                if (place != null) item.put("start_offset", place.start).put("end_offset", place.end)
                if (c.id !in keepNote) item.put("note", c.note?.takeIf { it.isNotBlank() }?.take(4000) ?: JSONObject.NULL)
                items.put(item)
                ids[c.id] = id
            }
            send("PUT", "/api/v1/clippings/${doc(document)}", JSONObject().put("items", items))
        }
        return ids
    }

    fun setClippingNote(document: String, id: String, note: String?) {
        var cursor = 0L
        var found: JSONObject? = null
        var pages = 0
        while (found == null && pages < 40) {
            val o = get("/api/v1/clippings/${doc(document)}?cursor=$cursor&limit=500")
            val items = o.arr("items")?.objects() ?: emptyList()
            found = items.lastOrNull { it.str("id") == id }
            if (o.optBoolean("more") && items.isNotEmpty()) cursor = o.long("cursor") ?: break else break
            pages++
        }
        val c = found ?: throw io.github.prithirajn.readync.net.ServiceException("crosspoint-sync doesn't have that highlight any more")
        if (c.optInt("deleted") == 1 || c.optBoolean("deleted")) throw io.github.prithirajn.readync.net.ServiceException("That highlight was deleted on CrossPoint")
        val item = JSONObject().put("id", id)
        for (k in listOf("spine", "start_page", "end_page", "pages", "start_word", "end_word", "words", "para", "chapter", "text", "color",
            "created_at", "layout_signature", "start_offset", "end_offset")) {
            if (c.has(k)) item.put(k, c.get(k))
        }
        item.put("note", note?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
        send("PUT", "/api/v1/clippings/${doc(document)}", JSONObject().put("items", org.json.JSONArray().put(item)))
    }

    fun clippings(): List<Clipping> {
        val o = get("/api/v1/clippings")
        return (o.arr("items")?.objects() ?: emptyList()).mapNotNull { c ->
            val id = c.str("id") ?: return@mapNotNull null
            val doc = c.str("document") ?: return@mapNotNull null
            val text = c.str("text") ?: return@mapNotNull null
            val start = c.int("start_offset")
            val end = c.int("end_offset")?.takeIf { e -> start != null && e > start }
            Clipping(id, doc, text, c.str("note"), c.str("chapter"), c.long("created_at") ?: 0L, spine = c.int("spine"),
                start = start?.takeIf { end != null }, end = end)
        }
    }

    companion object {
        const val DEFAULT_SERVER = "https://sync.crosspointreader.com"

        /** crosspoint-sync's id for a highlight: the first 16 hex characters of SHA-256 of its creation time (decimal) and text. */
        fun clippingId(createdAt: Long, text: String): String =
            java.security.MessageDigest.getInstance("SHA-256").digest((createdAt.toString() + text).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }.take(16)

        fun md5(password: String): String {
            val digest = MessageDigest.getInstance("MD5").digest(password.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        /**
         * Finds the server the way the companion app does: a bare host:port tries https:// then
         * http://, and only a server that answers /healthz like crosspoint-sync is accepted.
         */
        fun resolveServer(input: String): String {
            val raw = input.trim().trimEnd('/').ifEmpty { DEFAULT_SERVER }
            val candidates = if (raw.startsWith("http://") || raw.startsWith("https://")) {
                listOf(raw)
            } else {
                listOf("https://$raw", "http://$raw")
            }
            var lastError: Exception? = null
            for (base in candidates) {
                try {
                    val r = Http.request("GET", "$base/healthz", timeoutMs = 10_000)
                    if (r.ok && runCatching { JSONObject(r.body).str("status") == "ok" }.getOrDefault(false)) {
                        return base
                    }
                } catch (e: NetworkException) {
                    lastError = e
                }
            }
            throw ServiceException(
                if (lastError != null) "Couldn't reach $raw (${lastError.message})"
                else "$raw doesn't look like a crosspoint-sync server"
            )
        }
    }
}
