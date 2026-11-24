package io.github.prithirajn.readync.trackers

import io.github.prithirajn.readync.data.Link
import io.github.prithirajn.readync.data.ReadStatus
import io.github.prithirajn.readync.data.Visibility
import io.github.prithirajn.readync.net.Http
import io.github.prithirajn.readync.net.ServiceException
import io.github.prithirajn.readync.net.SkipException
import io.github.prithirajn.readync.util.arr
import io.github.prithirajn.readync.util.num
import io.github.prithirajn.readync.util.objects
import io.github.prithirajn.readync.util.str
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Readwise Reader (readwise.io/read), through its API v3: your place both ways (its
 * reading_progress), and a finished book is archived there (and marked seen). Books are matched by
 * title and author across the whole library, archived ones too, so a reread still matches. Reader's
 * own newer place isn't written over (the usual never-move-back rule, as it reads back), and PDFs
 * there take no progress. (After crosspoint-sync d899e02.) For highlights to Readwise, use Export highlights.
 */
object ReadwiseReaderTracker : Tracker {
    override val id = "readwise-reader"
    override val name = "Readwise Reader"
    override val signInKind = SignInKind.TOKEN
    override val privacy = PrivacySupport.PRIVATE_ONLY
    override val privacyNote = "Reader is your own library; nothing is shared."
    override val readsProgress = true
    override val statuses = setOf(ReadStatus.READING, ReadStatus.FINISHED)
    override val hint = "Syncs your place both ways and archives books you finish"
    override val tokenHelp = "Copy your access token from readwise.io/access_token and paste it here. Your place goes to Reader and comes back from it; finished books are archived there."
    override val tokenUrl = "https://readwise.io/access_token"
    override val tokenLabel = "Access token"
    override val bringsPositions = true

    private const val BASE = "https://readwise.io/api/v3"
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
        run {
            var cursor: String? = null
            for (page in 0 until MAX_PAGES * 3) {
                val q = "withHtmlContent=false" + (cursor?.let { "&pageCursor=" + Http.enc(it) } ?: "")
                val r = Http.request("GET", "$BASE/list/?$q", headers(session), timeoutMs = 30_000)
                if (r.code == 429) break
                checkAnswer(r, name)
                val o = jsonOrNull(r.body) ?: break
                for (d in o.arr("results")?.objects() ?: emptyList()) {
                    val id = d.str("id") ?: continue
                    // Your highlights and notes are documents there too: not books.
                    if (d.str("category") == "highlight" || d.str("category") == "note") continue
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

    /** Your place, as Reader's reading_progress (0–1). A PDF there takes none. */
    override fun pushProgress(session: Session, link: Link, percent: Int, visibility: Visibility) {
        val id = link.externalId ?: return
        val d = doc(session, link) ?: throw SkipException("This document is gone from Readwise Reader")
        if (d.str("category") == "pdf") return
        val update = JSONObject().put("id", id).put("reading_progress", percent.coerceIn(0, 100) / 100.0)
        val r = Http.request("PATCH", "$BASE/bulk_update/", headers(session), JSONObject().put("updates", JSONArray().put(update)).toString())
        if (r.code == 207) throw ServiceException("Readwise Reader didn't take the position; will try again")
        checkAnswer(r, name)
    }

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
