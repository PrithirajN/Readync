package io.github.prithirajn.readync.trackers

import io.github.prithirajn.readync.data.Link
import io.github.prithirajn.readync.data.ReadStatus
import io.github.prithirajn.readync.data.Visibility
import io.github.prithirajn.readync.net.AuthException
import io.github.prithirajn.readync.net.Http
import io.github.prithirajn.readync.net.HttpResponse
import io.github.prithirajn.readync.net.ServiceException
import io.github.prithirajn.readync.net.SkipException
import io.github.prithirajn.readync.source.Clipping
import io.github.prithirajn.readync.util.arr
import io.github.prithirajn.readync.util.idString
import io.github.prithirajn.readync.util.num
import io.github.prithirajn.readync.util.obj
import io.github.prithirajn.readync.util.objects
import io.github.prithirajn.readync.util.str
import org.json.JSONArray
import org.json.JSONObject

/**
 * BookOrbit (bookorbit, a self-hosted library with a web reader): your place both ways, through
 * the REST API its own reader uses (/api/v1). Books are matched by title and author; progress is
 * kept per EPUB file there. CrossPoint books also send KOReader's exact place, which BookOrbit
 * keeps for its KOReader devices. A finished book is marked Read.
 *
 * Signed in with your BookOrbit username and password once; only the refresh token it gives (as
 * a "native" app) is kept, renewed as it's used.
 */
object BookOrbitTracker : Tracker {
    override val id = "bookorbit"
    override val name = "BookOrbit"
    override val signInKind = SignInKind.SERVER
    override val beta = true
    override val betaNote = "Beta: follows crosspoint-sync's BookOrbit connection (BookOrbit 3.6 or newer works best)."
    override val highlights = HighlightSupport.PRIVATE_JOURNAL
    override val highlightsNote = "Added as highlights in BookOrbit's own copy of the book, at their exact place, with your notes."
    override val oneEntryPerHighlight = true
    override val updatesHighlights = true
    override val privacy = PrivacySupport.PRIVATE_ONLY
    override val privacyNote = "Your own server: nothing is shared."
    override val readsProgress = true
    override val statuses = setOf(ReadStatus.READING, ReadStatus.FINISHED)
    override val hint = "Syncs your place both ways with your BookOrbit library"
    override val bringsPositions = true
    override val serverLogin = ServerLogin(
        serverPlaceholder = "https://books.example.com",
        userLabel = "Username",
        secretLabel = "Password",
        help = "Your BookOrbit server's address and your BookOrbit sign-in.",
        keptNote = "Your password is used once to sign in. Only BookOrbit's sign-in token is kept, encrypted on this phone.",
    )

    private fun api(session: Session) = (session.data.str("server") ?: throw AuthException("Link BookOrbit again")) + "/api/v1"
    private val json = mapOf("Content-Type" to "application/json", "Accept" to "application/json")

    private fun keep(session: Session, o: JSONObject) {
        val access = o.str("accessToken") ?: throw ServiceException("BookOrbit gave no sign-in token")
        session.put("access", access)
        o.str("refreshToken")?.let { session.put("refresh", it) }
        val exp = isoSeconds(o.opt("accessTokenExpiresAt")).takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000 + 600)
        session.put("expires", exp)
    }

    override fun signInWithServer(server: String, user: String, secret: String): SignInResult {
        val b = serverBase(server)
        val s = Session(JSONObject().put("server", b).toString())
        val body = JSONObject().put("username", user).put("password", secret).put("clientKind", "native").put("deviceLabel", "Readync")
        var r = Http.request("POST", "$b/api/v1/auth/login", json, body.toString())
        // Older servers refuse the native-app fields: once more without them.
        if (r.code == 400 && r.body.contains("should not exist")) {
            r = Http.request("POST", "$b/api/v1/auth/login", json, JSONObject().put("username", user).put("password", secret).toString())
        }
        if (r.code == 401 || r.code == 403) throw ServiceException("BookOrbit didn't accept that username and password")
        if (!r.ok) throw ServiceException("The server answered HTTP ${r.code}; is that your BookOrbit address?")
        keep(s, jsonOrNull(r.body) ?: throw ServiceException("BookOrbit gave no sign-in token"))
        if (s.data.str("refresh") == null) throw ServiceException("This BookOrbit server doesn't give apps a lasting sign-in; update BookOrbit and try again")
        return SignInResult(s.encode(), "$user @ ${b.removePrefix("https://").removePrefix("http://")}")
    }

    private fun token(session: Session): String {
        val exp = session.data.optLong("expires", 0)
        session.data.str("access")?.takeIf { exp - 60 > System.currentTimeMillis() / 1000 }?.let { return it }
        val refresh = session.data.str("refresh") ?: throw AuthException("Sign in to BookOrbit again")
        val r = Http.request("POST", api(session) + "/auth/refresh", json, JSONObject().put("refreshToken", refresh).toString())
        if (r.code in 400..403) throw AuthException("BookOrbit's sign-in expired. Link it again.")
        checkAnswer(r, name)
        keep(session, jsonOrNull(r.body) ?: throw ServiceException("BookOrbit gave no sign-in token"))
        return session.data.str("access")!!
    }

    private fun call(session: Session, method: String, path: String, body: Any? = null): HttpResponse {
        val r = Http.request(method, api(session) + path, json + ("Authorization" to "Bearer ${token(session)}"), body?.toString(), timeoutMs = 30_000)
        if (r.code != 401) return r
        // A token cut short on the server: renewed once.
        session.put("expires", 0)
        return Http.request(method, api(session) + path, json + ("Authorization" to "Bearer ${token(session)}"), body?.toString(), timeoutMs = 30_000)
    }

    /** A 404 that means the server has no such route (an older BookOrbit), not a missing book. */
    private fun noRoute(r: HttpResponse) = r.code == 404 && (jsonOrNull(r.body)?.str("message")?.startsWith("Cannot ") == true)

    override fun search(session: Session, title: String, author: String?): List<RemoteBook> {
        val r = call(session, "GET", "/books/search?q=${Http.enc(title.take(500))}&limit=20")
        checkAnswer(r, name)
        return (jsonArrayOrNull(r.body)?.objects() ?: emptyList()).filter { b ->
            val formats = b.arr("formats")
            formats == null || formats.length() == 0 || (0 until formats.length()).any { formats.optString(it).equals("epub", true) }
        }.mapNotNull { b ->
            val id = b.idString("id") ?: return@mapNotNull null
            val t = b.str("title") ?: return@mapNotNull null
            val authors = b.arr("authors")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } }.orEmpty()
            RemoteBook(id, t, authors.joinToString(", ").ifEmpty { null })
        }
    }

    /** The book's EPUB file (progress is kept per file there): its main one first. Remembered. */
    private fun fileId(session: Session, link: Link): String? {
        val bookId = link.externalId ?: return null
        val files = session.data.optJSONObject("files") ?: JSONObject()
        files.str(bookId)?.let { return it }
        val r = call(session, "GET", "/books/${Http.enc(bookId)}")
        if (r.code == 404) return null
        checkAnswer(r, name)
        val epubs = jsonOrNull(r.body)?.arr("files")?.objects()?.filter { it.str("format").equals("epub", true) && it.idString("id") != null }.orEmpty()
        val chosen = (epubs.firstOrNull { it.str("role") == "primary" } ?: epubs.firstOrNull())?.idString("id") ?: return null
        session.put("files", files.put(bookId, chosen))
        return chosen
    }

    /** The file's position and when it was saved (unix seconds). */
    private fun fileProgress(session: Session, file: String): Pair<Double, Long>? {
        val r = call(session, "GET", "/reading-progress?fileId=${Http.enc(file)}")
        val row: JSONObject? = if (noRoute(r)) {
            val old = call(session, "GET", "/books/files/${Http.enc(file)}/progress")
            if (old.code == 404) return null
            checkAnswer(old, name)
            jsonOrNull(old.body)
        } else {
            checkAnswer(r, name)
            jsonOrNull(r.body)?.arr("items")?.optJSONObject(0)?.let { e ->
                JSONObject().put("percentage", e.opt("percentage")).put("updatedAt", e.opt("updatedAt")).put("textUpdatedAt", e.obj("text")?.opt("updatedAt"))
            }
        }
        row ?: return null
        val p = fraction(row.num("percentage"), hundred = true) ?: return null
        val at = isoSeconds(row.opt("textUpdatedAt")).takeIf { it > 0 } ?: isoSeconds(row.opt("updatedAt"))
        return p to at
    }

    override fun state(session: Session, link: Link): RemoteState {
        val file = fileId(session, link) ?: return RemoteState(null, null)
        val (p, _) = fileProgress(session, file) ?: return RemoteState(null, null)
        return RemoteState(if (p >= 0.999) ReadStatus.FINISHED else ReadStatus.READING, pct(p))
    }

    override fun position(session: Session, link: Link): RemotePosition? {
        val file = fileId(session, link) ?: return null
        val (p, at) = fileProgress(session, file) ?: return null
        if (at <= 0) return null
        return RemotePosition(p, p >= 0.999, at)
    }

    private fun put(session: Session, link: Link, percent: Int) {
        val file = fileId(session, link) ?: throw SkipException("This book has no EPUB in BookOrbit")
        val f = percent.coerceIn(0, 100) / 100.0
        // KOReader's exact place, for CrossPoint books, kept as BookOrbit keeps its KOReader devices'.
        val exact = ExactPositions.of(link.document)?.takeIf { percent < 100 }
        val pctOut = Math.round((exact?.second ?: f) * 1_000_000) / 10_000.0
        val write = JSONObject().put("fileId", file.toLongOrNull() ?: file).put("percentage", pctOut).put("editedAt", java.time.Instant.now().toString())
        exact?.let { write.put("koreader", it.first) }
        var r = call(session, "PUT", "/reading-progress", JSONObject().put("writes", JSONArray().put(write)))
        if (noRoute(r)) {
            val old = JSONObject().put("percentage", pctOut)
            exact?.let { old.put("koreaderProgress", it.first) }
            r = call(session, "POST", "/books/files/${Http.enc(file)}/progress", old)
        }
        checkAnswer(r, name)
    }

    override fun pushProgress(session: Session, link: Link, percent: Int, visibility: Visibility) = put(session, link, percent)

    /** BookOrbit's copy of the book (its EPUB file), kept briefly. */
    private fun epub(session: Session, file: String): EpubCfi.Book =
        EpubCfi.cached("bookorbit|${api(session)}|$file") {
            Http.bytes(api(session) + "/books/files/${Http.enc(file)}/serve", 200_000_000, mapOf("Authorization" to "Bearer ${token(session)}"), 60_000)
                ?: throw ServiceException("Couldn't download BookOrbit's copy of the book")
        }

    /**
     * A highlight placed in BookOrbit's own copy at its exact place (CrossPoint's offsets, else
     * found by its words). One already there (the same place, or the same words) gets your note
     * updated instead of a second copy. Returns BookOrbit's id for it.
     */
    override fun postHighlight(session: Session, link: Link, percent: Int, clip: Clipping, previousId: String?): String? {
        val bookId = link.externalId ?: return null
        val file = fileId(session, link) ?: throw SkipException("This book has no EPUB in BookOrbit")
        val range = try {
            EpubCfi.highlightRange(epub(session, file), clip.text, clip.spine, clip.start, clip.end)
        } catch (e: EpubCfi.EpubException) {
            throw SkipException("Couldn't place this highlight in BookOrbit's copy of the book")
        }
        val path = "/books/${Http.enc(bookId)}/annotations"
        val listed = call(session, "GET", path)
        checkAnswer(listed, name)
        val note = clip.note?.trim()?.takeIf { it.isNotEmpty() }
        val existing = jsonArrayOrNull(listed.body)?.objects().orEmpty()
        val same = existing.firstOrNull { it.idString("id") == previousId && previousId != null }
            ?: existing.firstOrNull { it.str("cfi") == range.cfi }
            ?: existing.firstOrNull { EpubCfi.normalizeQuote(it.str("text") ?: "") == EpubCfi.normalizeQuote(clip.text) && (it.idString("jumpFileId") ?: file) == file }
        if (same != null) {
            val id = same.idString("id")
            if (id != null && (same.str("note") ?: null) != note) {
                checkAnswer(call(session, "PATCH", "$path/${Http.enc(id)}", JSONObject().put("note", note ?: JSONObject.NULL)), name)
            }
            return id
        }
        val body = JSONObject().put("cfi", range.cfi).put("bookFileId", file.toLongOrNull() ?: file).put("text", clip.text)
        note?.let { body.put("note", it) }
        clip.chapter?.takeIf { it.isNotBlank() }?.let { body.put("chapterTitle", it.take(500)) }
        val r = call(session, "POST", path, body)
        checkAnswer(r, name)
        return jsonOrNull(r.body)?.idString("id")
    }

    override fun setStatus(session: Session, link: Link, status: ReadStatus) {
        val bookId = link.externalId ?: return
        when (status) {
            ReadStatus.FINISHED -> {
                put(session, link, 100)
                checkAnswer(call(session, "PATCH", "/books/${Http.enc(bookId)}/status", JSONObject().put("status", "read")), name)
            }
            // Reading follows from the position sent next (BookOrbit's other status names aren't known here).
            else -> Unit
        }
    }
}
