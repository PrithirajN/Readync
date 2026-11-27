package io.github.prithirajn.readync.trackers

import io.github.prithirajn.readync.data.Link
import io.github.prithirajn.readync.data.ReadStatus
import io.github.prithirajn.readync.data.Visibility
import io.github.prithirajn.readync.net.Http
import io.github.prithirajn.readync.util.arr
import io.github.prithirajn.readync.util.idString
import io.github.prithirajn.readync.util.int
import io.github.prithirajn.readync.util.num
import io.github.prithirajn.readync.util.objects
import io.github.prithirajn.readync.util.obj
import io.github.prithirajn.readync.util.str
import org.json.JSONObject

/**
 * BookFusion: your reading position there and here, both ways, for books in your BookFusion
 * library. Linked with BookFusion's device sign-in (the one its KOReader plugin uses), so only an
 * access token is kept. Positions go by percentage (BookFusion takes a 0–100 percentage).
 */
object BookFusionTracker : Tracker {
    override val id = "bookfusion"
    override val name = "BookFusion"
    override val signInKind = SignInKind.DEVICE_CODE
    override val beta = false
    override val privacy = PrivacySupport.PRIVATE_ONLY
    override val privacyNote = "Positions go to your own BookFusion library."
    override val readsProgress = true
    override val statuses = setOf(ReadStatus.READING, ReadStatus.FINISHED)
    override val hint = "Syncs your place with your BookFusion library"
    override val pairingHelp = "Open BookFusion, sign in, and enter this code."
    override val bringsPositions = true

    private const val BASE = "https://www.bookfusion.com"
    private const val API = "application/json; api_version=10"
    // BookFusion's public client for reading apps (its KOReader plugin's).
    private const val CLIENT_ID = "koreader"

    private fun headers(session: Session) = mapOf(
        "Authorization" to "Bearer ${session.data.str("access_token") ?: ""}", "Accept" to API, "Content-Type" to "application/json",
    )
    private val plain = mapOf("Accept" to API, "Content-Type" to "application/json")

    override fun beginDeviceLink(): DeviceLink {
        val r = Http.request("POST", "$BASE/api/user/auth/device", plain, JSONObject().put("client_id", CLIENT_ID).toString())
        checkAnswer(r, name, authOn403 = false)
        val o = jsonOrNull(r.body) ?: throw io.github.prithirajn.readync.net.ServiceException("BookFusion didn't start the sign-in")
        return DeviceLink(
            deviceCode = o.str("device_code") ?: throw io.github.prithirajn.readync.net.ServiceException("BookFusion didn't give a code"),
            userCode = o.str("user_code") ?: "",
            verificationUri = o.str("verification_uri_complete") ?: o.str("verification_uri") ?: "$BASE/device",
            intervalSeconds = o.int("interval") ?: 5,
            expiresInSeconds = o.int("expires_in") ?: 900,
        )
    }

    override fun pollDeviceLink(deviceCode: String): DevicePoll {
        val r = Http.request("POST", "$BASE/api/user/auth/token", plain, JSONObject()
            .put("grant_type", "urn:ietf:params:oauth:grant-type:device_code").put("client_id", CLIENT_ID).put("device_code", deviceCode).toString())
        val o = jsonOrNull(r.body) ?: JSONObject()
        o.str("access_token")?.let { token ->
            return DevicePoll.Linked(SignInResult(JSONObject().put("access_token", token).toString(), "BookFusion"))
        }
        return when (o.str("error")) {
            "authorization_pending", "slow_down" -> DevicePoll.Pending
            "access_denied" -> DevicePoll.Failed("You declined it on BookFusion.")
            "expired_token" -> DevicePoll.Failed("The code expired. Start again.")
            else -> if (r.code >= 500) DevicePoll.Pending else DevicePoll.Failed(o.str("error") ?: "BookFusion answered HTTP ${r.code}")
        }
    }

    private fun books(body: String): List<RemoteBook> {
        val arr = jsonArrayOrNull(body) ?: jsonOrNull(body)?.let { it.arr("books") ?: it.arr("results") } ?: return emptyList()
        return arr.objects().mapNotNull { b ->
            val id = b.idString("id") ?: b.idString("book_id") ?: return@mapNotNull null
            val title = b.str("title") ?: return@mapNotNull null
            val author = b.str("author") ?: b.arr("authors")?.let { a -> a.optJSONObject(0)?.str("name") ?: a.optString(0).takeIf { it.isNotBlank() } }
            RemoteBook(id, title, author, coverUrl = b.obj("cover")?.str("url") ?: b.str("cover_url"))
        }
    }

    override fun search(session: Session, title: String, author: String?): List<RemoteBook> {
        // On the title alone; the author only helps pick among the hits.
        val r = Http.request("POST", "$BASE/api/user/books/search", headers(session), JSONObject().put("query", title).put("per_page", 10).toString())
        checkAnswer(r, name)
        return books(r.body)
    }

    private fun readingPosition(session: Session, link: Link): Pair<Double, Long>? {
        val id = link.externalId ?: return null
        val r = Http.request("GET", "$BASE/api/user/books/${Http.enc(id)}/reading_position", headers(session))
        if (r.code == 404) return null
        checkAnswer(r, name)
        val o = jsonOrNull(r.body) ?: return null
        val p = fraction(o.num("percentage"), hundred = true) ?: return null
        return p to isoSeconds(o.opt("updated_at"))
    }

    override fun state(session: Session, link: Link): RemoteState {
        val (p, _) = readingPosition(session, link) ?: return RemoteState(null, null)
        return RemoteState(if (p >= 0.999) ReadStatus.FINISHED else ReadStatus.READING, pct(p))
    }

    override fun position(session: Session, link: Link): RemotePosition? {
        val (p, at) = readingPosition(session, link) ?: return null
        if (at <= 0) return null
        return RemotePosition(p, p >= 0.999, at)
    }

    /** BookFusion's copy of the book: a download link, then the file (the sign-in isn't sent to the file's host). */
    private fun download(session: Session, id: String): ByteArray? {
        val r = Http.request("POST", "$BASE/api/user/books/${Http.enc(id)}/download", headers(session), "{}")
        if (!r.ok) return null
        val url = jsonOrNull(r.body)?.str("url")?.takeIf { it.startsWith("https://") } ?: return null
        val host = runCatching { java.net.URL(url).host }.getOrNull() ?: return null
        if (listOf("bookfusion.com", "amazonaws.com", "cloudfront.net").none { host == it || host.endsWith(".$it") }) return null
        return Http.bytes(url, 200_000_000, timeoutMs = 60_000)
    }

    private fun put(session: Session, link: Link, percent: Int) {
        val id = link.externalId ?: return
        val f = percent.coerceIn(0, 100) / 100.0
        val body = JSONObject().put("percentage", percent.coerceIn(0, 100).toDouble())
        // CrossPoint's exact place in BookFusion's own copy, so its reader opens right there.
        if (percent < 100) exactIn("bookfusion|$id", link.document, f) { download(session, id) }?.let { p ->
            body.put("chapter_index", p.chapterIndex).put("page_position_in_book", p.inBook).put("cfi", p.cfi)
        }
        val r = Http.request("POST", "$BASE/api/user/books/${Http.enc(id)}/reading_position", headers(session), body.toString())
        checkAnswer(r, name)
    }

    override fun pushProgress(session: Session, link: Link, percent: Int, visibility: Visibility) = put(session, link, percent)

    override fun setStatus(session: Session, link: Link, status: ReadStatus) {
        if (status == ReadStatus.FINISHED) put(session, link, 100)
    }
}
