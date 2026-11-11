package io.github.prithirajn.trackersync.trackers

import io.github.prithirajn.trackersync.data.Link
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.Visibility
import io.github.prithirajn.trackersync.net.Http
import io.github.prithirajn.trackersync.net.ServiceException
import io.github.prithirajn.trackersync.util.arr
import io.github.prithirajn.trackersync.util.num
import io.github.prithirajn.trackersync.util.objects
import io.github.prithirajn.trackersync.util.str
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Readwise Reader (readwise.io/read), through its API v3: a finished book is archived there
 * (and marked seen), and its reading progress there can move the book here. Books are matched by
 * title and author among what's not archived yet. For highlights to Readwise, use Export highlights.
 */
object ReadwiseReaderTracker : Tracker {
    override val id = "readwise-reader"
    override val name = "Readwise Reader"
    override val signInKind = SignInKind.TOKEN
    override val privacy = PrivacySupport.PRIVATE_ONLY
    override val privacyNote = "Reader is your own library; nothing is shared."
    // Only its finish is sent, so its percentage isn't compared with yours.
    override val readsProgress = false
    override val statuses = setOf(ReadStatus.READING, ReadStatus.FINISHED)
    override val hint = "Archives books there when you finish them"
    override val tokenHelp = "Copy your access token from readwise.io/access_token and paste it here. Finished books are archived in Reader, and reading further there moves them here."
    override val tokenUrl = "https://readwise.io/access_token"
    override val tokenLabel = "Access token"
    override val bringsPositions = true
    override val takesProgress = false

    private const val BASE = "https://readwise.io/api/v3"
    private val LOCATIONS = listOf("new", "later", "shortlist", "feed")
    private const val MAX_PAGES = 20

    private fun headers(session: Session) = mapOf(
        "Authorization" to "Token ${session.data.str("token") ?: ""}",
        "Content-Type" to "application/json", "Accept" to "application/json",
    )

    override fun signInWithToken(token: String): SignInResult {
        val t = token.trim().removePrefix("Token ").trim()
        val s = Session(JSONObject().put("token", t).toString())
        val r = Http.request("GET", "$BASE/list/?withHtmlContent=false", headers(s))
        if (r.code == 401 || r.code == 403) throw ServiceException("Readwise didn't accept that token")
        checkAnswer(r, name)
        return SignInResult(s.encode(), "Readwise Reader")
    }

    /** What's not archived, cached for a few minutes (Readwise allows 20 requests a minute). */
    private val pools = ConcurrentHashMap<String, Pair<Long, List<RemoteBook>>>()

    private fun pool(session: Session): List<RemoteBook> {
        val key = session.data.str("token") ?: return emptyList()
        pools[key]?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000 }?.let { return it.second }
        val out = LinkedHashMap<String, RemoteBook>()
        for (loc in LOCATIONS) {
            var cursor: String? = null
            for (page in 0 until MAX_PAGES) {
                val q = "location=$loc&withHtmlContent=false" + (cursor?.let { "&pageCursor=" + Http.enc(it) } ?: "")
                val r = Http.request("GET", "$BASE/list/?$q", headers(session), timeoutMs = 30_000)
                if (r.code == 429) break
                checkAnswer(r, name)
                val o = jsonOrNull(r.body) ?: break
                for (d in o.arr("results")?.objects() ?: emptyList()) {
                    val id = d.str("id") ?: continue
                    val title = d.str("title") ?: continue
                    out.putIfAbsent(id, RemoteBook(id, title, d.str("author") ?: d.str("site_name"), coverUrl = d.str("image_url")))
                }
                cursor = o.str("nextPageCursor") ?: break
            }
        }
        return out.values.toList().also { pools[key] = System.currentTimeMillis() to it }
    }

    override fun search(session: Session, title: String, author: String?): List<RemoteBook> = likely(pool(session), title)
    override fun shelfCandidates(session: Session): List<RemoteBook> = pool(session)

    private fun doc(session: Session, link: Link): JSONObject? {
        val id = link.externalId ?: return null
        val r = Http.request("GET", "$BASE/list/?id=${Http.enc(id)}&withHtmlContent=false", headers(session))
        checkAnswer(r, name)
        return jsonOrNull(r.body)?.arr("results")?.optJSONObject(0)
    }

    override fun state(session: Session, link: Link): RemoteState {
        val d = doc(session, link) ?: return RemoteState(null, null)
        val p = fraction(d.num("reading_progress"))
        return RemoteState(if (d.str("location") == "archive") ReadStatus.FINISHED else ReadStatus.READING, pct(p))
    }

    override fun peekPercent(session: Session, link: Link): Int? = runCatching { state(session, link).percent }.getOrNull()

    override fun position(session: Session, link: Link): RemotePosition? {
        val d = doc(session, link) ?: return null
        val p = fraction(d.num("reading_progress")) ?: return null
        val at = isoSeconds(d.opt("updated_at")).takeIf { it > 0 } ?: return null
        return RemotePosition(p, d.str("location") == "archive" && p >= 0.98, at)
    }

    /** Reader has no progress apps can set: only finishing (archived) goes there. */
    override fun pushProgress(session: Session, link: Link, percent: Int, visibility: Visibility) {}

    override fun setStatus(session: Session, link: Link, status: ReadStatus) {
        val id = link.externalId ?: return
        val update = when (status) {
            ReadStatus.FINISHED -> JSONObject().put("id", id).put("location", "archive").put("seen", true)
            // Read again: back to Later.
            ReadStatus.READING -> JSONObject().put("id", id).put("location", "later")
            else -> return
        }
        val r = Http.request("PATCH", "$BASE/bulk_update/", headers(session), JSONObject().put("updates", JSONArray().put(update)).toString())
        // 207: the one update didn't apply.
        if (r.code == 207) throw ServiceException("Readwise Reader didn't apply the change; will try again")
        checkAnswer(r, name)
        pools.clear()
    }
}
