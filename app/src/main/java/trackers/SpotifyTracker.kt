package io.github.prithirajn.readync.trackers

import io.github.prithirajn.readync.data.Link
import io.github.prithirajn.readync.data.ReadStatus
import io.github.prithirajn.readync.data.Visibility
import io.github.prithirajn.readync.net.AuthException
import io.github.prithirajn.readync.net.Http
import io.github.prithirajn.readync.net.HttpResponse
import io.github.prithirajn.readync.net.ServiceException
import io.github.prithirajn.readync.util.arr
import io.github.prithirajn.readync.util.bool
import io.github.prithirajn.readync.util.long
import io.github.prithirajn.readync.util.num
import io.github.prithirajn.readync.util.obj
import io.github.prithirajn.readync.util.objects
import io.github.prithirajn.readync.util.str
import org.json.JSONObject

/**
 * Spotify audiobooks, read only: when you listen further than you've read, the book moves ahead
 * here (through the main-source check, like any other source). Nothing is ever sent to Spotify.
 *
 * Spotify gives no time with a listening position, so each matched audiobook keeps the last place
 * seen: a move forward of 3 minutes or more (or into a later chapter) since then counts as
 * listening, dated when it's seen. Signed in with your own Spotify app (PKCE, no secret), whose
 * redirect is a loopback address the sign-in page here catches.
 */
object SpotifyTracker : Tracker {
    override val id = "spotify"
    override val name = "Spotify"
    override val signInKind = SignInKind.OAUTH
    override val beta = true
    override val betaNote = "Beta: Spotify sells audiobooks in a few countries only (US, UK, Canada, Ireland, New Zealand, Australia); elsewhere it has none to match."
    override val privacy = PrivacySupport.PRIVATE_ONLY
    override val privacyNote = "Nothing is sent to Spotify."
    override val readsProgress = false
    override val statuses = emptySet<ReadStatus>()
    override val writes = false
    override val bringsPositions = true
    override val hint = "Moves your progress forward when you listen ahead"
    override val oauthHelp = "Spotify needs an app of your own (it's free and takes a minute):\n" +
        "1. At developer.spotify.com/dashboard, sign in with your Spotify account and Create app.\n" +
        "2. Add the redirect URI below, tick Web API, and save.\n" +
        "3. Copy the app's Client ID from its Settings and paste it here."

    private const val AUTHORIZE = "https://accounts.spotify.com/authorize"
    private const val TOKEN = "https://accounts.spotify.com/api/token"
    private const val API = "https://api.spotify.com/v1"
    private const val REDIRECT = "http://127.0.0.1:43819/readync/spotify"
    // Changing playback only for Resume, which you tap: nothing plays by itself.
    private val SCOPES = listOf("user-library-read", "user-read-playback-position", "user-read-playback-state", "user-modify-playback-state")
    private val MARKETS = setOf("US", "GB", "CA", "IE", "NZ", "AU")
    private const val MIN_ADVANCE_MS = 3 * 60_000L

    override fun oauthStart(clientId: String): OAuthStart {
        val id = clientId.trim()
        if (!Regex("^[0-9a-fA-F]{32}$").matches(id)) throw ServiceException("A Spotify Client ID is 32 letters and digits; copy it again from your app's Settings")
        val verifier = randomUrlSafe(64)
        val state = randomUrlSafe(16)
        val url = AUTHORIZE + "?client_id=" + Http.enc(id) + "&response_type=code&redirect_uri=" + Http.enc(REDIRECT) +
            "&code_challenge_method=S256&code_challenge=" + pkceChallenge(verifier) + "&scope=" + Http.enc(SCOPES.joinToString(" ")) +
            "&state=" + state
        return OAuthStart(url, REDIRECT, verifier, state)
    }

    private fun tokenPost(fields: Map<String, String>): HttpResponse =
        Http.request("POST", TOKEN, mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json"),
            fields.entries.joinToString("&") { "${it.key}=${Http.enc(it.value)}" })

    private fun keep(session: Session, o: JSONObject) {
        session.put("access_token", o.str("access_token"))
        // Spotify may keep the same refresh token: the old one stays then.
        o.str("refresh_token")?.let { session.put("refresh_token", it) }
        session.put("expires_at", System.currentTimeMillis() / 1000 + (o.long("expires_in") ?: 3600))
    }

    override fun oauthFinish(clientId: String, code: String, start: OAuthStart): SignInResult {
        val r = tokenPost(mapOf("grant_type" to "authorization_code", "code" to code, "redirect_uri" to start.redirectUri,
            "client_id" to clientId.trim(), "code_verifier" to start.verifier))
        val o = jsonOrNull(r.body) ?: JSONObject()
        if (o.str("access_token") == null) {
            val why = o.str("error_description") ?: o.str("error") ?: "HTTP ${r.code}"
            throw ServiceException(when {
                o.str("error") == "invalid_client" -> "Spotify doesn't know that Client ID. Copy it again from your app's Settings."
                why.contains("redirect", true) -> "Spotify refused the redirect URI. Add it to your Spotify app exactly as shown, then try again."
                else -> "Spotify didn't sign you in ($why)"
            })
        }
        val s = Session(JSONObject().put("client_id", clientId.trim()).toString())
        keep(s, o)
        val me = api(s, "/me")
        if (me.code == 403) throw ServiceException("This Spotify account can't use that app. Sign in with the account that made it, or add this one under User Management in the Spotify dashboard.")
        checkAnswer(me, name)
        val m = jsonOrNull(me.body)
        val who = m?.str("display_name") ?: m?.str("id") ?: "Spotify"
        val country = m?.str("country")
        return SignInResult(s.encode(), if (country != null && country !in MARKETS) "$who (no Spotify audiobooks in $country)" else who)
    }

    private fun access(session: Session): String {
        val exp = session.data.optLong("expires_at", 0)
        session.data.str("access_token")?.takeIf { exp - 60 > System.currentTimeMillis() / 1000 }?.let { return it }
        val refresh = session.data.str("refresh_token") ?: throw AuthException("Link Spotify again")
        val r = tokenPost(mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to (session.data.str("client_id") ?: "")))
        if (r.code == 400 || r.code == 401) throw AuthException("Spotify's sign-in expired or was revoked. Link Spotify again.")
        checkAnswer(r, name)
        keep(session, jsonOrNull(r.body) ?: throw ServiceException("Spotify didn't renew the sign-in"))
        return session.data.str("access_token")!!
    }

    private fun api(session: Session, path: String, method: String = "GET", body: JSONObject? = null): HttpResponse {
        val h = linkedMapOf("Authorization" to "Bearer ${access(session)}", "Accept" to "application/json")
        if (body != null) h["Content-Type"] = "application/json"
        val r = Http.request(method, if (path.startsWith("http")) path else API + path, h, body?.toString() ?: if (method == "PUT") "" else null)
        if (r.code == 401) throw AuthException("Spotify's sign-in expired. Link Spotify again.")
        return r
    }

    /** A paged list ({ items, next }) to the end, up to [max] pages. 403/404: not in this market. */
    private fun pages(session: Session, path: String, max: Int = 20): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var next: String? = path
        var i = 0
        while (next != null && i++ < max) {
            val r = api(session, next)
            if (r.code == 429 || r.code >= 500) throw ServiceException("Spotify is busy (HTTP ${r.code}); will try again")
            if (r.code != 200) break
            val o = jsonOrNull(r.body) ?: break
            o.arr("items")?.objects()?.let { out.addAll(it) }
            next = o.str("next")
        }
        return out
    }

    private fun toBook(raw: JSONObject): RemoteBook? {
        val a = raw.obj("audiobook") ?: raw
        val id = a.str("id") ?: return null
        val title = a.str("name") ?: return null
        val cover = a.arr("images")?.objects()?.firstOrNull()?.str("url")
        return RemoteBook(id, title, a.arr("authors")?.optJSONObject(0)?.str("name"), coverUrl = cover, format = "Audiobook")
    }

    override fun search(session: Session, title: String, author: String?): List<RemoteBook> {
        val q = listOfNotNull(title, author).joinToString(" ")
        val r = api(session, "/search?type=audiobook&limit=10&q=${Http.enc(q)}")
        if (r.code != 200) return emptyList()
        return jsonOrNull(r.body)?.obj("audiobooks")?.arr("items")?.objects()?.mapNotNull { toBook(it) } ?: emptyList()
    }

    override fun shelfCandidates(session: Session): List<RemoteBook> = pages(session, "/me/audiobooks?limit=50").mapNotNull { toBook(it) }

    /** Where the listener is: chapter (index, id, uri, name), ms into it and into the whole book, its share, finished. */
    data class Place(
        val chapter: Int, val offsetMs: Long, val share: Double, val finished: Boolean,
        val chapterId: String = "", val chapterUri: String = "", val chapterName: String = "", val positionMs: Long = 0,
    )

    /**
     * The furthest chapter with any listening, at its resume point (or the start of the next once
     * it's played through); a matching player now playing wins, as resume points lag.
     */
    private fun place(chapters: List<JSONObject>, player: JSONObject?): Place? {
        if (chapters.isEmpty()) return null
        fun rp(c: JSONObject) = c.obj("resume_point")
        val started = chapters.indices.filter { i -> rp(chapters[i])?.let { it.bool("fully_played") || (it.long("resume_position_ms") ?: 0) > 0 } == true }
        val furthest = started.maxOrNull() ?: -1
        val done = furthest >= 0 && rp(chapters[furthest])?.bool("fully_played") == true
        val finished = done && furthest == chapters.size - 1
        var index = if (finished) furthest else if (done) furthest + 1 else maxOf(0, furthest)
        val dur = { c: JSONObject -> c.long("duration_ms") ?: 0L }
        var at = when {
            finished -> dur(chapters[index])
            done -> 0L
            else -> rp(chapters[index])?.long("resume_position_ms") ?: 0L
        }
        val playingId = player?.obj("item")?.str("id")
        val playing = if (playingId != null) chapters.indexOfFirst { it.str("id") == playingId } else -1
        if (playing >= 0) { index = playing; at = player?.long("progress_ms") ?: 0L }
        val total = chapters.sumOf(dur)
        val before = chapters.take(index).sumOf(dur)
        val c = chapters[index]
        return Place(index, before + at, if (total > 0) ((before + at).toDouble() / total).coerceIn(0.0, 1.0) else 0.0, finished && playing < 0,
            c.str("id") ?: "", c.str("uri") ?: "", c.str("name") ?: "Chapter ${index + 1}", at)
    }

    /** The track and time a listening share lands on. */
    private fun placeAt(chapters: List<JSONObject>, share: Double): Place? {
        if (chapters.isEmpty()) return null
        val dur = { c: JSONObject -> c.long("duration_ms") ?: 0L }
        val total = chapters.sumOf(dur)
        val target = (share.coerceIn(0.0, 1.0) * total).toLong()
        var before = 0L
        var i = 0
        while (i < chapters.size - 1 && before + dur(chapters[i]) <= target) before += dur(chapters[i++])
        val c = chapters[i]
        val at = (target - before).coerceIn(0, dur(c))
        return Place(i, before + at, if (total > 0) (before + at).toDouble() / total else 0.0, false, c.str("id") ?: "", c.str("uri") ?: "", c.str("name") ?: "Chapter ${i + 1}", at)
    }

    private fun chapters(session: Session, id: String) =
        pages(session, "/audiobooks/${Http.enc(id)}/chapters?limit=50").filter { it.str("id") != null && it.str("uri") != null }

    private fun player(session: Session) = api(session, "/me/player?additional_types=episode").takeIf { it.code == 200 }?.let { jsonOrNull(it.body) }

    /** Chapter-by-chapter points (from the book's copy on this phone) and your "Not the right spot?" one. */
    private fun spotPoints(session: Session, link: Link): List<Anchors.Anchor> {
        val id = link.externalId
        if (id != null) Anchors.ensureChapters(session, link, tracks = {
            val ch = chapters(session, id)
            var start = 0L
            val list = ch.mapIndexed { i, c -> val d = c.long("duration_ms") ?: 0L; Anchors.Track(c.str("name") ?: "Chapter ${i + 1}", start, d).also { start += d } }
            if (list.isEmpty()) null else Anchors.Tracks(list, start, null)
        }, epub = { Anchors.phoneCopy(link.document) })
        return Anchors.points(session, link)
    }

    /** The audiobook's tracks and where you're listening, for "Not the right spot?". */
    fun tracks(session: Session, link: Link): Anchors.Tracks? {
        val id = link.externalId ?: return null
        val ch = chapters(session, id)
        if (ch.isEmpty()) return null
        var start = 0L
        val list = ch.mapIndexed { i, c ->
            val d = c.long("duration_ms") ?: 0L
            Anchors.Track(c.str("name") ?: "Chapter ${i + 1}", start, d).also { start += d }
        }
        return Anchors.Tracks(list, start, place(ch, player(session))?.offsetMs)
    }

    /** What Resume did: started playing at [place], or not (no Premium, or no device), with links to open Spotify there. */
    data class Resume(val playing: Boolean, val place: Place, val reason: String?, val appUri: String, val webUrl: String)

    /**
     * Starts the audiobook where you are: your reading place (through your calibration) when it's
     * further along than the listening one, else where you stopped listening. Only from a tap.
     */
    fun resume(session: Session, link: Link, readerShare: Double?): Resume? {
        val id = link.externalId ?: return null
        val ch = chapters(session, id)
        val listening = place(ch, player(session)) ?: return null
        val reading = readerShare?.let { placeAt(ch, Anchors.textToAudio(it, spotPoints(session, link))) }
        val target = if (reading != null && !listening.finished && reading.offsetMs > listening.offsetMs) reading else listening
        val r = api(session, "/me/player/play", "PUT", JSONObject().put("uris", org.json.JSONArray().put(target.chapterUri)).put("position_ms", target.positionMs))
        if (r.ok) return Resume(true, target, null, target.chapterUri, "")
        if (r.code == 403 || r.code == 404) {
            return Resume(false, target, if (r.code == 404) "NO_ACTIVE_DEVICE" else "PREMIUM_REQUIRED", target.chapterUri,
                // Spotify serves audiobook chapters as episodes: this page is the track itself.
                "https://open.spotify.com/episode/${Http.enc(target.chapterId)}?t=${target.positionMs / 1000}")
        }
        checkAnswer(r, name)
        return null
    }

    /**
     * After Resume found no device and Spotify is being opened: start playback at the exact spot
     * once this phone shows up as a device (a few tries, right after your tap). False when it didn't.
     */
    fun playWhenDeviceAppears(session: Session, place: Place): Boolean {
        repeat(8) {
            Thread.sleep(2_000)
            val list = runCatching { api(session, "/me/player/devices") }.getOrNull()?.takeIf { it.code == 200 }?.let { jsonOrNull(it.body) }
            val devices = list?.arr("devices")?.objects().orEmpty()
            val device = devices.firstOrNull { it.bool("is_active") } ?: devices.firstOrNull { it.str("type") == "Smartphone" } ?: devices.firstOrNull()
            val dev = device?.str("id") ?: return@repeat
            val r = runCatching {
                api(session, "/me/player/play?device_id=${Http.enc(dev)}", "PUT", JSONObject().put("uris", org.json.JSONArray().put(place.chapterUri)).put("position_ms", place.positionMs))
            }.getOrNull()
            if (r?.ok == true) return true
            if (r?.code == 403) return false
        }
        return false
    }

    override fun position(session: Session, link: Link): RemotePosition? {
        val id = link.externalId ?: return null
        val snaps = session.data.optJSONObject("seen") ?: JSONObject()
        val prev = snaps.optJSONObject(id)
        if (prev?.optBoolean("finished") == true) return null
        val now = place(chapters(session, id), player(session)) ?: return null
        val moved = prev != null && (now.chapter > prev.optInt("chapter") || now.offsetMs - prev.optLong("offset") >= MIN_ADVANCE_MS ||
            (now.finished && !prev.optBoolean("finished")))
        // Small moves keep the old mark so they add up; a move back (listening again) resets it.
        if (prev == null || moved || now.offsetMs < prev.optLong("offset")) {
            session.put("seen", snaps.put(id, JSONObject().put("chapter", now.chapter).put("offset", now.offsetMs).put("finished", now.finished)))
        }
        if (!moved) return null
        return RemotePosition(if (now.finished) 1.0 else Anchors.audioToText(now.share, spotPoints(session, link)), now.finished, System.currentTimeMillis() / 1000)
    }

    override fun state(session: Session, link: Link) = RemoteState(null, null)

    /** Where you are in the audiobook, for "What each tracker says" (nothing remembered). */
    override fun peekPercent(session: Session, link: Link): Int? {
        val id = link.externalId ?: return null
        return place(chapters(session, id), player(session))?.let { pct(if (it.finished) 1.0 else Anchors.audioToText(it.share, spotPoints(session, link))) }
    }
    override fun setStatus(session: Session, link: Link, status: ReadStatus) {}
    override fun pushProgress(session: Session, link: Link, percent: Int, visibility: Visibility) {}
}
