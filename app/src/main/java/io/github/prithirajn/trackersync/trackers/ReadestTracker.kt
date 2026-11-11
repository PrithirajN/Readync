package io.github.prithirajn.trackersync.trackers

import io.github.prithirajn.trackersync.data.Link
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.Visibility
import io.github.prithirajn.trackersync.net.AuthException
import io.github.prithirajn.trackersync.net.Http
import io.github.prithirajn.trackersync.net.ServiceException
import io.github.prithirajn.trackersync.net.SkipException
import io.github.prithirajn.trackersync.util.int
import io.github.prithirajn.trackersync.util.long
import io.github.prithirajn.trackersync.util.num
import io.github.prithirajn.trackersync.util.obj
import io.github.prithirajn.trackersync.util.str
import io.github.prithirajn.trackersync.util.arr
import io.github.prithirajn.trackersync.util.objects
import io.github.prithirajn.trackersync.source.Clipping
import org.json.JSONArray
import org.json.JSONObject

/**
 * Readest (readest.com, the reader app): your place both ways, through the KOSync routes Readest
 * serves for CrossPoint readers (web.readest.com/api/crosspoint). Readest knows books by the same
 * document hash CrossPoint's binary matching uses, so nothing is matched; it links a copy it
 * doesn't know to the library book with the same title and first author.
 *
 * Readest keeps exact places only (KOReader XPointers), so only CrossPoint books, whose exact
 * place crosspoint-sync has, are sent. Linked with Readest's device sign-in, approved once on
 * web.readest.com/link; the session that gives then approves a reader key of its own.
 */
object ReadestTracker : Tracker {
    override val id = "readest"
    override val name = "Readest"
    override val signInKind = SignInKind.DEVICE_CODE
    override val beta = true
    override val betaNote = "Beta: follows crosspoint-sync's Readest connection. Only CrossPoint books' places go to Readest (it needs their exact place); highlights need the book in your Readest Cloud library."
    override val highlights = HighlightSupport.PRIVATE_JOURNAL
    override val highlightsNote = "Added as highlights in Readest's copy of the book (it has to be in your Readest Cloud library), with your notes."
    override val oneEntryPerHighlight = true
    override val updatesHighlights = true
    override val privacy = PrivacySupport.PRIVATE_ONLY
    override val privacyNote = "Positions go to your own Readest library."
    override val readsProgress = true
    override val statuses = setOf(ReadStatus.READING, ReadStatus.FINISHED)
    override val matchesByDocument = true
    override val bringsPositions = true
    override val hint = "Syncs your place with Readest"
    override val pairingHelp = "Open web.readest.com/link, sign in to Readest, and enter this code."

    private const val BASE = "https://web.readest.com"
    private const val DEVICE = "Readync"
    private const val ACCEPT = "application/vnd.koreader.v1+json"
    private val json = mapOf("Accept" to "application/json", "Content-Type" to "application/json")

    private fun headers(session: Session) = mapOf(
        "x-auth-user" to (session.data.str("username") ?: DEVICE),
        "x-auth-key" to md5Hex(session.data.str("key") ?: ""),
        "Accept" to ACCEPT, "Content-Type" to "application/json",
    )
    private fun server(session: Session) = session.data.str("server") ?: throw AuthException("Link Readest again")

    private fun code(): JSONObject {
        val r = Http.request("POST", "$BASE/api/crosspoint/device/code", json, "{}")
        val o = jsonOrNull(r.body) ?: JSONObject()
        if (r.code != 200 || o.str("device_code") == null || o.str("user_code") == null) {
            throw ServiceException("Readest didn't start the sign-in (${o.str("error") ?: "HTTP ${r.code}"})")
        }
        return o
    }

    override fun beginDeviceLink(): DeviceLink {
        val o = code()
        return DeviceLink(
            deviceCode = o.str("device_code")!!,
            userCode = o.str("user_code")!!,
            verificationUri = o.str("verification_uri_complete") ?: o.str("verification_uri") ?: "$BASE/link",
            intervalSeconds = o.int("interval") ?: 5,
            expiresInSeconds = o.int("expires_in") ?: 600,
        )
    }

    private fun tokenPoll(body: JSONObject) = Http.request("POST", "$BASE/api/crosspoint/device/token", json, body.toString())

    override fun pollDeviceLink(deviceCode: String): DevicePoll {
        val r = tokenPoll(JSONObject().put("device_code", deviceCode).put("grant", "session"))
        val o = jsonOrNull(r.body) ?: JSONObject()
        val access = o.str("access_token")
        if (r.code == 200 && access != null && o.str("refresh_token") != null) {
            return try {
                // With the session, a reader key for the KOSync routes: a second code, approved by the session itself.
                val c = code()
                val approve = Http.request("POST", "$BASE/api/crosspoint/device/approve", json + ("Authorization" to "Bearer $access"),
                    JSONObject().put("user_code", c.str("user_code")).toString())
                if (approve.code != 200) throw ServiceException("Readest didn't approve the reader key (HTTP ${approve.code})")
                val k = tokenPoll(JSONObject().put("device_code", c.str("device_code")))
                val ko = jsonOrNull(k.body)
                val key = ko?.str("access_token") ?: throw ServiceException("Readest didn't give a reader key (HTTP ${k.code})")
                val username = ko.str("username") ?: o.obj("user")?.str("email")
                val cred = JSONObject().put("server", "$BASE/api/crosspoint").put("key", key)
                    // The session too: highlights go through Readest's main sync, which takes nothing less.
                    .put("access_token", access).put("refresh_token", o.str("refresh_token"))
                    .put("expires_at", o.optLong("expires_at", 0).takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000 + o.optLong("expires_in", 3600)))
                ko.str("id")?.let { cred.put("keyId", it) }
                username?.let { cred.put("username", it) }
                DevicePoll.Linked(SignInResult(cred.toString(), username?.let { "Readest ($it)" } ?: "Readest"))
            } catch (e: Exception) {
                DevicePoll.Failed("${e.message ?: "Readest didn't finish linking"}. Start again.")
            }
        }
        return when (o.str("error")) {
            "authorization_pending", "slow_down" -> DevicePoll.Pending
            "access_denied" -> DevicePoll.Failed("It was declined in Readest.")
            "expired_token" -> DevicePoll.Failed("The code expired. Start again.")
            else -> if (r.code >= 500 || r.code == 429) DevicePoll.Pending else DevicePoll.Failed(o.str("error") ?: "Readest answered HTTP ${r.code}")
        }
    }

    override fun search(session: Session, title: String, author: String?): List<RemoteBook> = emptyList()

    private fun progress(session: Session, link: Link): JSONObject? {
        val r = Http.request("GET", server(session) + "/syncs/progress/" + Http.enc(link.document), headers(session))
        if (r.code == 404) return null
        checkAnswer(r, name, authOn403 = false)
        return jsonOrNull(r.body)?.takeIf { it.str("progress")?.startsWith("/body") == true }
    }

    override fun state(session: Session, link: Link): RemoteState {
        val p = progress(session, link) ?: return RemoteState(null, null)
        val f = fraction(p.num("percentage"))
        return RemoteState(if ((f ?: 0.0) >= 0.98) ReadStatus.FINISHED else ReadStatus.READING, pct(f))
    }

    override fun position(session: Session, link: Link): RemotePosition? {
        val p = progress(session, link) ?: return null
        val f = fraction(p.num("percentage")) ?: return null
        val at = p.long("timestamp")?.takeIf { it > 0 } ?: return null
        return RemotePosition(f, false, at)
    }

    override fun pushProgress(session: Session, link: Link, percent: Int, visibility: Visibility) {
        val exact = ExactPositions.of(link.document)
            ?: throw SkipException("Readest takes exact places only, which come from CrossPoint books")
        val body = JSONObject()
            .put("document", link.document)
            .put("progress", exact.first)
            .put("percentage", exact.second)
            .put("device", DEVICE)
            .put("device_id", "readync")
        val meta = JSONObject()
        link.title?.let { meta.put("title", it.take(500)) }
        link.author?.let { meta.put("authors", it.take(500)) }
        if (meta.length() > 0) body.put("metadata", meta)
        val r = Http.request("PUT", server(session) + "/syncs/progress", headers(session), body.toString())
        checkAnswer(r, name, authOn403 = false)
    }

    // ---- Highlights, through Readest's main sync (with the Readest session) ----

    // Readest's sign-in project and its public key, as its KOReader plugin ships them.
    private const val SUPABASE = "https://vbsyxfusjjqdxkjqlysc.supabase.co"
    private const val SUPABASE_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InZic3l4ZnVzampxZHhranFseXNjIiwicm9sZSI6ImFub24iLCJpYXQiOjE3MzQxMjM2NzEsImV4cCI6MjA0OTY5OTY3MX0.3U5Uqaou_1SgrVe1eo9rApc0uKjqhpQdUXhvwUHmUfg"

    /** The session's access token, renewed when it's about to run out (refresh tokens change each time, so the new one is kept). */
    @Synchronized
    private fun access(session: Session): String {
        val refresh = session.data.str("refresh_token") ?: throw SkipException("Link Readest again to send highlights")
        if (session.data.optLong("expires_at", 0) - 300 > System.currentTimeMillis() / 1000) session.data.str("access_token")?.let { return it }
        val r = Http.request("POST", "$SUPABASE/auth/v1/token?grant_type=refresh_token",
            mapOf("apikey" to SUPABASE_KEY, "Content-Type" to "application/json"), JSONObject().put("refresh_token", refresh).toString())
        if (r.code in 400..403) throw AuthException("Readest's sign-in expired. Link Readest again.")
        checkAnswer(r, name)
        val o = jsonOrNull(r.body) ?: throw ServiceException("Readest didn't renew the sign-in")
        session.put("access_token", o.str("access_token"))
        session.put("refresh_token", o.str("refresh_token") ?: refresh)
        session.put("expires_at", o.optLong("expires_at", 0).takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000 + o.optLong("expires_in", 3600)))
        return session.data.str("access_token")!!
    }

    private fun sync(session: Session, method: String, query: String, body: JSONObject? = null): JSONObject {
        val r = Http.request(method, "$BASE/api/sync$query",
            mapOf("Authorization" to "Bearer ${access(session)}", "Accept" to "application/json", "Content-Type" to "application/json"), body?.toString(), timeoutMs = 30_000)
        checkAnswer(r, name)
        return jsonOrNull(r.body) ?: JSONObject()
    }

    private fun squash(v: String?) = (v ?: "").lowercase().filter { it.isLetterOrDigit() }

    /** The Readest library book for [link]: under the book's own id, else the uploaded EPUB with its title and first author. Remembered. */
    private fun readestBook(session: Session, link: Link): Pair<String, String?>? {
        val known = session.data.optJSONObject("books") ?: JSONObject()
        known.optJSONObject(link.document)?.let { k -> k.str("hash")?.let { return it to k.str("meta") } }
        fun usable(b: JSONObject) = b.str("book_hash") != null && b.str("deleted_at") == null && b.str("uploaded_at") != null &&
            (b.str("format") ?: "EPUB").equals("EPUB", true)
        var book = sync(session, "GET", "?since=0&type=books&book=${Http.enc(link.document)}").arr("books")?.objects()
            ?.firstOrNull { usable(it) && it.str("book_hash") == link.document }
        if (book == null) {
            val title = squash(link.title).ifEmpty { return null }
            val author = squash(link.author?.split(Regex("[,&;]| and "))?.firstOrNull())
            book = sync(session, "GET", "?since=0&type=books").arr("books")?.objects()
                ?.filter { usable(it) && squash(it.str("title")) == title && (author.isEmpty() || squash(it.str("author")).startsWith(author)) }
                ?.maxByOrNull { it.str("updated_at") ?: "" }
        }
        val hash = book?.str("book_hash") ?: return null
        val meta = book.str("meta_hash")
        session.put("books", known.put(link.document, JSONObject().put("hash", hash).put("meta", meta ?: JSONObject.NULL)))
        return hash to meta
    }

    /** Readest's copy of the book, through the reader key's download link. */
    private fun epub(session: Session, hash: String): EpubCfi.Book = EpubCfi.cached("readest|$hash") {
        val r = Http.request("GET", server(session) + "/books/" + Http.enc(hash), headers(session))
        if (r.code == 404) throw SkipException("This book isn't in your Readest library (upload the EPUB to Readest Cloud)")
        checkAnswer(r, name, authOn403 = false)
        val url = jsonOrNull(r.body)?.str("downloadUrl") ?: throw ServiceException("Readest gave no download link")
        Http.bytes(url, 200_000_000, timeoutMs = 60_000) ?: throw ServiceException("Couldn't download Readest's copy of the book")
    }

    /** One highlight as a Readest note, at its place in Readest's copy; the same id each time, so an edit updates it. */
    override fun postHighlight(session: Session, link: Link, percent: Int, clip: Clipping, previousId: String?): String? {
        val (hash, meta) = readestBook(session, link) ?: (link.document to null)
        val range = try {
            EpubCfi.highlightRange(epub(session, hash), clip.text, clip.spine, clip.start, clip.end)
        } catch (e: EpubCfi.EpubException) {
            throw SkipException("Couldn't place this highlight in Readest's copy of the book")
        }
        val id = previousId ?: md5Hex("readync:${link.document}:${clip.id}").take(7)
        val created = (clip.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis() / 1000) * 1000
        val note = JSONObject().put("bookHash", hash).put("id", id).put("type", "annotation").put("cfi", range.cfi)
            .put("text", clip.text).put("style", "highlight").put("color", "yellow")
            .put("createdAt", created).put("updatedAt", maxOf(created, System.currentTimeMillis()))
        meta?.let { note.put("metaHash", it) }
        clip.note?.trim()?.takeIf { it.isNotEmpty() }?.let { note.put("note", it) }
        sync(session, "POST", "", JSONObject().put("books", JSONArray()).put("configs", JSONArray()).put("notes", JSONArray().put(note)))
        return id
    }

    /** Readest has no separate finished mark: a finished book is its last place, sent as such. */
    override fun setStatus(session: Session, link: Link, status: ReadStatus) {
        if (status == ReadStatus.FINISHED) pushProgress(session, link, 100, Visibility.PRIVATE)
    }
}
