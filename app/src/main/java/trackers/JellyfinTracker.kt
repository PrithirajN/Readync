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
import io.github.prithirajn.readync.util.num
import io.github.prithirajn.readync.util.obj
import io.github.prithirajn.readync.util.objects
import io.github.prithirajn.readync.util.str
import org.json.JSONObject

/**
 * Jellyfin's book library (ebooks and audiobooks it serves): your place both ways, as Jellyfin's
 * played percentage. Signed in once with your Jellyfin username and password; only the session
 * token Jellyfin gives is kept.
 */
object JellyfinTracker : Tracker {
    override val id = "jellyfin"
    override val name = "Jellyfin"
    override val signInKind = SignInKind.SERVER
    override val beta = true
    override val betaNote = "Beta: follows crosspoint-sync's Jellyfin connection, which needs Jellyfin 12 or newer."
    override val privacy = PrivacySupport.PRIVATE_ONLY
    override val privacyNote = "Your own server: nothing is shared."
    override val readsProgress = true
    override val statuses = setOf(ReadStatus.READING, ReadStatus.FINISHED)
    override val hint = "Syncs your place with your Jellyfin book library"
    override val bringsPositions = true
    override val serverLogin = ServerLogin(
        serverPlaceholder = "https://jellyfin.example.com",
        userLabel = "Username",
        secretLabel = "Password",
        help = "Your Jellyfin server's address and your Jellyfin sign-in. Books in its Books libraries are matched by title and author.",
        keptNote = "Your password is used once to sign in. Only the session token is kept, encrypted; sign it out under Devices in Jellyfin to cut this off.",
    )

    private const val CLIENT = "Readync"

    private fun base(session: Session) = session.data.str("server") ?: throw AuthException("Link Jellyfin again")
    private fun user(session: Session) = session.data.str("userId") ?: throw AuthException("Link Jellyfin again")
    private fun headers(session: Session) = mapOf(
        "Authorization" to "MediaBrowser Token=\"${session.data.str("token") ?: ""}\"", "Content-Type" to "application/json", "Accept" to "application/json",
    )

    override fun signInWithServer(server: String, user: String, secret: String): SignInResult {
        val b = serverBase(server)
        val device = md5Hex("$b\n$user")
        val auth = "MediaBrowser Client=\"$CLIENT\", Device=\"$CLIENT\", DeviceId=\"$device\", Version=\"1.0\""
        val r = Http.request("POST", "$b/Users/AuthenticateByName",
            mapOf("Authorization" to auth, "Content-Type" to "application/json", "Accept" to "application/json"),
            JSONObject().put("Username", user).put("Pw", secret).toString())
        if (r.code == 401 || r.code == 403) throw ServiceException("Jellyfin didn't accept that username and password")
        if (r.code == 400) throw ServiceException("Jellyfin refused the sign-in (check the address; Jellyfin 12 or newer is needed)")
        if (!r.ok) throw ServiceException("The server answered HTTP ${r.code}; is that your Jellyfin address?")
        val o = jsonOrNull(r.body)
        val token = o?.str("AccessToken") ?: throw ServiceException("Jellyfin gave no session")
        val uid = o.obj("User")?.str("Id") ?: throw ServiceException("Jellyfin gave no session")
        val s = Session(JSONObject().put("server", b).put("token", token).put("userId", uid).toString())
        val info = Http.request("GET", "$b/System/Info", headers(s))
        val major = jsonOrNull(info.body)?.str("Version")?.substringBefore('.')?.toIntOrNull()
        if (major != null && major < 12) throw ServiceException("This needs Jellyfin 12 or newer (the server is ${jsonOrNull(info.body)?.str("Version")})")
        return SignInResult(s.encode(), "$user @ ${b.removePrefix("https://").removePrefix("http://")}")
    }

    private fun get(session: Session, path: String): JSONObject? {
        val r = Http.request("GET", base(session) + path, headers(session), timeoutMs = 30_000)
        if (r.code == 404) return null
        checkAnswer(r, name, authOn403 = false)
        return jsonOrNull(r.body)
    }

    private fun author(item: JSONObject): String? {
        val writers = item.arr("People")?.objects()?.filter { p ->
            listOfNotNull(p.str("Type"), p.str("Role")).any { it.lowercase().let { k -> "writer" in k || "author" in k } }
        }?.mapNotNull { it.str("Name") }.orEmpty()
        if (writers.isNotEmpty()) return writers.joinToString(", ")
        return item.arr("Artists")?.optString(0)?.takeIf { it.isNotBlank() }
    }

    private fun books(o: JSONObject?): List<RemoteBook> =
        o?.arr("Items")?.objects()?.mapNotNull { item ->
            val id = item.idString("Id") ?: return@mapNotNull null
            val title = item.str("Name") ?: return@mapNotNull null
            // Its length (in ticks) rides along as the match's second id.
            RemoteBook(id, title, author(item), uuid = item.num("RunTimeTicks")?.takeIf { it > 0 }?.toLong()?.toString(),
                year = item.num("ProductionYear")?.toInt())
        } ?: emptyList()

    override fun search(session: Session, title: String, author: String?): List<RemoteBook> =
        books(get(session, "/Users/${Http.enc(user(session))}/Items?Recursive=true&IncludeItemTypes=Book&SearchTerm=${Http.enc(title)}&Fields=People,RunTimeTicks&Limit=25"))

    override fun shelfCandidates(session: Session): List<RemoteBook> =
        books(get(session, "/UserItems/Resume?UserId=${Http.enc(user(session))}&IncludeItemTypes=Book&Fields=People,RunTimeTicks&Limit=50"))

    private fun itemPath(session: Session, link: Link) = "/Users/${Http.enc(user(session))}/Items/${Http.enc(link.externalId ?: "")}"

    private fun ticks(session: Session, link: Link): Long? =
        link.externalUuid?.toLongOrNull()?.takeIf { it > 0 }
            ?: get(session, itemPath(session, link) + "?Fields=RunTimeTicks")?.num("RunTimeTicks")?.toLong()?.takeIf { it > 0 }

    private fun userData(session: Session, link: Link): JSONObject? = get(session, itemPath(session, link) + "/UserData")

    private fun share(session: Session, link: Link, ud: JSONObject): Double? =
        fraction(ud.num("PlayedPercentage"), hundred = true)
            ?: ud.num("PlaybackPositionTicks")?.let { t -> ticks(session, link)?.let { fraction(t / it) } }

    override fun state(session: Session, link: Link): RemoteState {
        val ud = userData(session, link) ?: return RemoteState(null, null)
        if (ud.bool("Played")) return RemoteState(ReadStatus.FINISHED, 100)
        return RemoteState(ReadStatus.READING, pct(share(session, link, ud)))
    }

    override fun position(session: Session, link: Link): RemotePosition? {
        val ud = userData(session, link) ?: return null
        // No time, no trust: a position without one could be anything.
        val at = isoSeconds(ud.opt("LastPlayedDate")).takeIf { it > 0 } ?: return null
        val f = share(session, link, ud) ?: 0.0
        val done = ud.bool("Played") || f >= 0.999
        return RemotePosition(if (done) 1.0 else f, done, at)
    }

    private fun post(session: Session, link: Link, f: Double, finished: Boolean) {
        val body = JSONObject()
            .put("PlayedPercentage", if (finished) 100.0 else f * 100)
            .put("Played", finished)
            .put("LastPlayedDate", java.time.Instant.now().toString())
        ticks(session, link)?.let { body.put("PlaybackPositionTicks", Math.round((if (finished) 1.0 else f) * it)) }
        val r = Http.request("POST", base(session) + itemPath(session, link) + "/UserData", headers(session), body.toString())
        checkAnswer(r, name, authOn403 = false)
    }

    override fun pushProgress(session: Session, link: Link, percent: Int, visibility: Visibility) =
        post(session, link, percent.coerceIn(0, 100) / 100.0, false)

    override fun setStatus(session: Session, link: Link, status: ReadStatus) {
        when (status) {
            ReadStatus.FINISHED -> post(session, link, 1.0, true)
            ReadStatus.READING -> post(session, link, 0.0, false)
            else -> Unit
        }
    }
}
