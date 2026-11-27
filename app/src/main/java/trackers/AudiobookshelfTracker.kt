package io.github.prithirajn.readync.trackers

import io.github.prithirajn.readync.data.Link
import io.github.prithirajn.readync.data.ReadStatus
import io.github.prithirajn.readync.data.Visibility
import io.github.prithirajn.readync.net.AuthException
import io.github.prithirajn.readync.net.Http
import io.github.prithirajn.readync.net.ServiceException
import io.github.prithirajn.readync.util.arr
import io.github.prithirajn.readync.util.bool
import io.github.prithirajn.readync.util.idString
import io.github.prithirajn.readync.util.long
import io.github.prithirajn.readync.util.num
import io.github.prithirajn.readync.util.obj
import io.github.prithirajn.readync.util.objects
import io.github.prithirajn.readync.util.str
import org.json.JSONObject

/**
 * Audiobookshelf, your own audiobook server: your place in the ebook moves the audiobook, and
 * listening ahead moves the book here. Places go by percentage: the audiobook's time is that share
 * of its length (approximate, since words and narration don't run exactly together). The item's
 * own ebook progress is set as well, for Audiobookshelf's reader.
 */
object AudiobookshelfTracker : Tracker {
    override val id = "audiobookshelf"
    override val name = "Audiobookshelf"
    override val signInKind = SignInKind.SERVER
    override val privacy = PrivacySupport.PRIVATE_ONLY
    override val privacyNote = "Your own server: nothing is shared."
    override val readsProgress = true
    override val statuses = setOf(ReadStatus.READING, ReadStatus.FINISHED)
    override val hint = "Keeps your place between the ebook and the audiobook"
    override val bringsPositions = true
    override val serverLogin = ServerLogin(
        serverPlaceholder = "https://abs.example.com",
        userLabel = null,
        secretLabel = "API key",
        secretIsPassword = false,
        help = "Your Audiobookshelf server's address, and an API key: in Audiobookshelf, Settings › API Keys › Add API key (or your user's API token on older servers).",
        keptNote = "The key is stored encrypted on this phone; delete it in Audiobookshelf to cut this off.",
    )

    private fun base(session: Session) = session.data.str("server") ?: throw AuthException("Link Audiobookshelf again")
    private fun headers(session: Session) = mapOf(
        "Authorization" to "Bearer ${session.data.str("token") ?: ""}", "Content-Type" to "application/json", "Accept" to "application/json",
    )

    private fun get(session: Session, path: String): JSONObject? {
        val r = Http.request("GET", base(session) + path, headers(session), timeoutMs = 30_000)
        if (r.code == 404) return null
        checkAnswer(r, name)
        return jsonOrNull(r.body)
    }

    override fun signInWithServer(server: String, user: String, secret: String): SignInResult {
        val b = serverBase(server)
        val s = Session(JSONObject().put("server", b).put("token", secret.trim().removePrefix("Bearer ").trim()).toString())
        val r = Http.request("GET", "$b/api/me", headers(s))
        if (r.code == 401 || r.code == 403) throw ServiceException("Audiobookshelf didn't accept that API key")
        if (!r.ok) throw ServiceException("The server answered HTTP ${r.code}; is that your Audiobookshelf address?")
        val me = jsonOrNull(r.body)?.str("username")
        return SignInResult(s.encode(), (me?.let { "$it @ " } ?: "") + b.removePrefix("https://").removePrefix("http://"))
    }

    private fun bookLibraries(session: Session): List<String> =
        get(session, "/api/libraries")?.arr("libraries")?.objects()
            ?.filter { it.str("mediaType") == "book" }?.mapNotNull { it.idString("id") } ?: emptyList()

    private fun toBook(item: JSONObject): RemoteBook? {
        val li = item.obj("libraryItem") ?: item
        val id = li.idString("id") ?: return null
        val media = li.obj("media")
        val meta = media?.obj("metadata") ?: return null
        val title = meta.str("title") ?: return null
        // The audiobook's length rides along as the match's second id.
        return RemoteBook(id, title, meta.str("authorName"), uuid = media.num("duration")?.toString(),
            year = meta.str("publishedYear")?.take(4)?.toIntOrNull(), format = if ((media.num("duration") ?: 0.0) > 0) "Audiobook" else "Ebook")
    }

    override fun search(session: Session, title: String, author: String?): List<RemoteBook> {
        // On the title alone: Audiobookshelf finds little for "title author".
        val out = ArrayList<RemoteBook>()
        for (lib in bookLibraries(session)) {
            val o = get(session, "/api/libraries/${Http.enc(lib)}/search?q=${Http.enc(title)}") ?: continue
            val items = o.arr("book") ?: o.arr("results") ?: continue
            items.objects().mapNotNullTo(out) { toBook(it) }
        }
        return out
    }

    override fun shelfCandidates(session: Session): List<RemoteBook> =
        get(session, "/api/me/items-in-progress")?.arr("libraryItems")?.objects()?.mapNotNull { toBook(it) } ?: emptyList()

    /** The audiobook's length in seconds: from the match, else asked (summing its files on older servers). */
    private fun duration(session: Session, link: Link): Double? {
        link.externalUuid?.toDoubleOrNull()?.takeIf { it > 0 }?.let { return it }
        val id = link.externalId ?: return null
        val media = get(session, "/api/items/${Http.enc(id)}?expanded=1")?.obj("media") ?: return null
        media.num("duration")?.takeIf { it > 0 }?.let { return it }
        return media.arr("audioFiles")?.objects()?.sumOf { it.num("duration") ?: 0.0 }?.takeIf { it > 0 }
    }

    private fun progress(session: Session, link: Link): JSONObject? {
        val id = link.externalId ?: return null
        return get(session, "/api/me/progress/${Http.enc(id)}")
    }

    /**
     * Where reading and listening places map through for this audiobook: its chapters matched to
     * the book's (worked out once, from the server's own ebook or the copy on this phone; the audio
     * itself is never downloaded), and your "Not the right spot?" point.
     */
    private fun points(session: Session, link: Link): List<Anchors.Anchor> {
        Anchors.ensureChapters(session, link, tracks = { tracks(session, link, withPlace = false) }, epub = { epub(session, link) })
        return Anchors.points(session, link)
    }

    /** The book's EPUB: the copy kept on this phone, else the item's own ebook on the server. */
    fun epub(session: Session, link: Link): ByteArray? {
        Anchors.phoneCopy(link.document)?.let { return it }
        val id = link.externalId ?: return null
        return runCatching { Http.bytes(base(session) + "/api/items/${Http.enc(id)}/ebook", 200_000_000, mapOf("Authorization" to "Bearer ${session.data.str("token")}"), 60_000) }.getOrNull()
    }

    /** The reading share in [p]: the listening one (through the chapters and your calibration) for an audiobook, the reader's for an ebook-only item. */
    private fun share(session: Session, link: Link, p: JSONObject): Double? {
        val dur = p.num("duration") ?: 0.0
        if (dur <= 0) return fraction(p.num("ebookProgress")) ?: fraction(p.num("progress"))
        val audio = fraction(p.num("progress")).takeIf { (it ?: 0.0) > 0 } ?: p.num("currentTime")?.let { fraction(it / dur) }
        return audio?.let { Anchors.audioToText(it, points(session, link)) }
    }

    override fun state(session: Session, link: Link): RemoteState {
        val p = progress(session, link) ?: return RemoteState(null, null)
        if (p.bool("isFinished")) return RemoteState(ReadStatus.FINISHED, 100)
        return RemoteState(ReadStatus.READING, pct(share(session, link, p)))
    }

    override fun position(session: Session, link: Link): RemotePosition? {
        val p = progress(session, link) ?: return null
        val at = (p.long("lastUpdate") ?: 0L) / 1000
        if (at <= 0) return null
        val f = share(session, link, p) ?: return null
        return RemotePosition(if (p.bool("isFinished")) 1.0 else f, p.bool("isFinished"), at)
    }

    private fun patch(session: Session, link: Link, body: JSONObject) {
        val id = link.externalId ?: return
        val r = Http.request("PATCH", base(session) + "/api/me/progress/${Http.enc(id)}", headers(session), body.toString())
        checkAnswer(r, name)
    }

    override fun pushProgress(session: Session, link: Link, percent: Int, visibility: Visibility) {
        val f = percent.coerceIn(0, 100) / 100.0
        // The listening side through your calibration for this book; the ebook side stays your reading place.
        val audio = Anchors.textToAudio(f, points(session, link))
        // No isFinished here: with isFinished false, Audiobookshelf ignores the progress.
        val body = JSONObject().put("progress", audio).put("ebookProgress", f)
        duration(session, link)?.let { d -> body.put("duration", d).put("currentTime", (audio * d).coerceIn(0.0, d)) }
        // Audiobookshelf's reader resumes only from a CFI: CrossPoint's exact place, found in its own copy.
        if (percent < 100) {
            val id = link.externalId
            if (id != null) exactIn("abs|${base(session)}|$id", link.document, f) {
                Http.bytes(base(session) + "/api/items/${Http.enc(id)}/ebook", 200_000_000, mapOf("Authorization" to "Bearer ${session.data.str("token")}"), 60_000)
            }?.let { body.put("ebookLocation", it.cfi) }
        }
        patch(session, link, body)
    }

    /** The audiobook's chapters and where you're listening, for "Not the right spot?". */
    fun tracks(session: Session, link: Link, withPlace: Boolean = true): Anchors.Tracks? {
        val id = link.externalId ?: return null
        val media = get(session, "/api/items/${Http.enc(id)}?expanded=1")?.obj("media") ?: return null
        val total = (media.num("duration")?.takeIf { it > 0 } ?: media.arr("audioFiles")?.objects()?.sumOf { it.num("duration") ?: 0.0 } ?: 0.0)
        if (total <= 0) return null
        val chapters = media.arr("chapters")?.objects().orEmpty()
        val list = if (chapters.isEmpty()) listOf(Anchors.Track("Whole book", 0L, Math.round(total * 1000)))
            else chapters.mapIndexed { i, c ->
                val s = c.num("start") ?: 0.0
                Anchors.Track(c.str("title") ?: "Chapter ${i + 1}", Math.round(s * 1000), Math.round(((c.num("end") ?: s) - s) * 1000).coerceAtLeast(0))
            }
        val p = if (withPlace) progress(session, link) else null
        return Anchors.Tracks(list, Math.round(total * 1000), p?.num("currentTime")?.let { Math.round(it * 1000) })
    }

    override fun setStatus(session: Session, link: Link, status: ReadStatus) {
        when (status) {
            ReadStatus.FINISHED -> patch(session, link, JSONObject().put("isFinished", true).put("progress", 1).put("ebookProgress", 1))
            // Read again: unfinished, back at the start (the next progress moves it on).
            ReadStatus.READING -> patch(session, link, JSONObject().put("isFinished", false).put("progress", 0).put("currentTime", 0).put("ebookProgress", 0))
            else -> Unit
        }
    }
}
