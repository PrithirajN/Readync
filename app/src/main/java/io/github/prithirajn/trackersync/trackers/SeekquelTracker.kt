package io.github.prithirajn.trackersync.trackers

import io.github.prithirajn.trackersync.data.Link
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.Visibility
import io.github.prithirajn.trackersync.net.AuthException
import io.github.prithirajn.trackersync.net.Http
import io.github.prithirajn.trackersync.net.HttpResponse
import io.github.prithirajn.trackersync.net.ServiceException
import io.github.prithirajn.trackersync.net.SkipException
import io.github.prithirajn.trackersync.source.Clipping
import io.github.prithirajn.trackersync.util.arr
import io.github.prithirajn.trackersync.util.int
import io.github.prithirajn.trackersync.util.idString
import io.github.prithirajn.trackersync.util.num
import io.github.prithirajn.trackersync.util.obj
import io.github.prithirajn.trackersync.util.objects
import io.github.prithirajn.trackersync.util.str
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Seekquel's Reading API (seekquel.app/for-developers): `https://api.seekquel.app/reading`,
 * `Authorization: Bearer <key>`. A book is a "document" keyed by our own id: CrossPoint's
 * document hash is sent as is, and the first progress push (with title and author) creates
 * it; Seekquel then matches it to a book, or the app links it with /documents/{id}/link.
 * Status, highlights and reading time need a matched document (409 until then).
 */
object SeekquelTracker : Tracker {
    override val id = "seekquel"
    override val name = "Seekquel"
    override val signInKind = SignInKind.DEVICE_CODE
    override val privacy = PrivacySupport.PRIVATE_ONLY
    override val readsProgress = true
    override val statuses = ReadStatus.entries.toSet()
    override val matchesByDocument = true
    override val pairingHelp =
        "In Seekquel, open Another reading app and enter this code. Case and dashes don't matter."
    override val privacyNote =
        "Seekquel doesn't let apps choose who sees updates. Progress goes to your own library, and highlights stay private until you share them from your notebook."
    override val readsLibrary = true
    override val reviews = ReviewSupport.RATING

    /** Tenths, for readers with precise ratings on (Pro); half stars otherwise, tried when tenths are refused. */
    override val ratingStep = 0.1

    /** PUT /documents/{d}/rating: stars out of 5 (Seekquel takes no review text from apps). */
    override fun setReview(session: Session, link: Link, rating: Double?, text: String?) {
        val r0 = rating?.takeIf { it > 0 } ?: return
        val path = "/documents/${Http.enc(link.document)}/rating"
        var r = send(session, "PUT", path, JSONObject().put("rating", stepRating(r0)))
        val half = (Math.round(r0 * 2) / 2.0).coerceIn(0.5, 5.0)
        if (!r.ok && r.code in 400..422 && !notLinked(r) && half != stepRating(r0)) {
            r = send(session, "PUT", path, JSONObject().put("rating", half))
        }
        if (notLinked(r)) throw SkipException(NOT_LINKED_NOTE)
        if (!r.ok) throw ServiceException("Seekquel didn't take the rating (${errorText(r)})")
    }
    override val highlights = HighlightSupport.PRIVATE_JOURNAL
    override val highlightsNote = "Added to the book's highlights in your Seekquel notebook, private until you share them."

    private const val BASE = "https://api.seekquel.app/reading"
    private const val APP = "Tracker Sync"
    private const val NOT_LINKED_NOTE =
        "Seekquel hasn't matched this book yet. Pick it in Seekquel, or tap Find match here."

    private val statusNames = mapOf(
        ReadStatus.TO_READ to "want_to_read", ReadStatus.READING to "reading", ReadStatus.PAUSED to "paused",
        ReadStatus.FINISHED to "read", ReadStatus.DNF to "did_not_finish",
    )

    private val json = mapOf("Accept" to "application/json", "Content-Type" to "application/json")

    private fun server(session: Session): String {
        val s = session.data.str("server") ?: BASE
        // 0.4.0 paired against the older /koreader interface; that key belongs to it.
        if (s.endsWith("/koreader")) throw AuthException("Seekquel moved to its Reading API; link it again")
        return s.trimEnd('/')
    }

    private fun send(session: Session, method: String, path: String, body: JSONObject? = null): HttpResponse {
        val key = session.data.str("key") ?: throw AuthException("Link Seekquel again")
        val r = Http.retryGateway {
            Http.request(method, server(session) + path, json + ("Authorization" to "Bearer $key"), body?.toString())
        }
        if (r.code == 401) throw AuthException("Seekquel disconnected this app; link it again")
        if (r.code == 429) throw ServiceException("Seekquel is rate-limiting; will retry")
        if (r.code == 503 && r.body.contains("feature_disabled")) throw ServiceException("Seekquel has reading apps switched off for now; will retry")
        return r
    }

    private fun errorText(r: HttpResponse): String {
        val e = runCatching { JSONObject(r.body).obj("error") }.getOrNull()
        return e?.str("message") ?: "HTTP ${r.code}"
    }

    private fun notLinked(r: HttpResponse) = r.code == 409

    // ---- your Seekquel account (optional): full details on books added here ----
    //
    // The Reading API can make a book your own private book but can't set its cover or details.
    // Seekquel's own app edits private books through its account API (api.seekquel.app/api,
    // a Bearer token from POST /login with email and password), so with the account signed in,
    // a book added from here gets its cover, description, ISBNs and the rest the same way.

    private const val ACCOUNT_API = "https://api.seekquel.app/api"

    /** Signs in to the Seekquel account; the returned credential keeps only the token, not the password. */
    fun accountSignIn(credential: String?, email: String, password: String): String {
        val r = Http.request(
            "POST", "$ACCOUNT_API/login", json,
            JSONObject().put("email", email.trim()).put("password", password).toString(),
        )
        if (r.code == 401 || r.code == 422) throw ServiceException("Seekquel didn't accept that email and password" + (accountError(r)?.let { " ($it)" } ?: ""))
        if (!r.ok) throw ServiceException("Seekquel sign-in failed (${accountError(r) ?: "HTTP ${r.code}"})")
        val o = runCatching { JSONObject(r.body) }.getOrNull()
        val token = o?.str("token") ?: o?.obj("data")?.str("token") ?: throw ServiceException("Seekquel didn't return a sign-in token")
        val session = Session(credential)
        session.put("accountToken", token)
        session.put("accountEmail", email.trim())
        return session.encode()
    }

    /** Forgets the account sign-in (the Reading API link stays). */
    fun accountSignOut(credential: String?): String {
        val session = Session(credential)
        session.data.remove("accountEmail")
        session.put("accountToken", null)
        return session.encode()
    }

    /** The signed-in account's email, or null when only the Reading API is linked. */
    fun accountEmail(credential: String?): String? =
        Session(credential).let { if (it.data.str("accountToken") != null) it.data.str("accountEmail") ?: "your account" else null }

    private fun accountError(r: HttpResponse): String? = runCatching {
        val o = JSONObject(r.body)
        o.obj("error")?.str("message") ?: o.str("message")
    }.getOrNull()

    private fun account(session: Session, method: String, path: String, body: ByteArray? = null, contentType: String? = null): HttpResponse {
        val token = session.data.str("accountToken") ?: throw ServiceException("Not signed in to your Seekquel account")
        val headers = mapOf("Accept" to "application/json", "Authorization" to "Bearer $token") +
            (contentType?.let { mapOf("Content-Type" to it) } ?: emptyMap())
        val r = Http.retryGateway { Http.request(method, ACCOUNT_API + path, headers, null, raw = body, timeoutMs = 60_000) }
        if (r.code == 401) {
            // Expired or signed out elsewhere: forget it, so the switch shows it needs signing in again.
            session.put("accountToken", null)
            throw ServiceException("Your Seekquel account sign-in expired; sign in again in Services → Seekquel")
        }
        return r
    }

    /** A multipart form (text fields, then an optional cover image), as Seekquel's app sends a private book. */
    private fun form(fields: List<Pair<String, String>>, cover: ByteArray?): Pair<ByteArray, String> {
        val boundary = "----TrackerSync" + java.util.UUID.randomUUID().toString().replace("-", "")
        val out = java.io.ByteArrayOutputStream()
        fun w(t: String) = out.write(t.toByteArray(Charsets.UTF_8))
        for ((k, v) in fields) w("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
        if (cover != null) {
            val mime = when {
                cover.size > 3 && cover[0] == 0x89.toByte() && cover[1] == 'P'.code.toByte() -> "image/png"
                cover.size > 12 && String(cover, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
                else -> "image/jpeg"
            }
            w("--$boundary\r\nContent-Disposition: form-data; name=\"cover\"; filename=\"cover.${mime.substringAfter('/')}\"\r\nContent-Type: $mime\r\n\r\n")
            out.write(cover); w("\r\n")
        }
        w("--$boundary--\r\n")
        return out.toByteArray() to "multipart/form-data; boundary=$boundary"
    }

    /** The private book's fields, named as Seekquel's private-book form sends them. */
    internal fun privateBookFields(d: io.github.prithirajn.trackersync.source.BookDetails): List<Pair<String, String>> {
        val f = ArrayList<Pair<String, String>>()
        f += "title" to d.title
        d.subtitle?.let { f += "subtitle" to it }
        d.description?.let { f += "description" to it.take(20_000) }
        val authors = JSONArray()
        d.authors.ifEmpty { listOfNotNull(d.author) }.forEach { authors.put(JSONObject().put("author_id", JSONObject.NULL).put("name", it).put("role", "author")) }
        if (authors.length() > 0) f += "authors" to authors.toString()
        d.isbn13?.let { f += "isbn_13" to it }
        d.isbn10?.let { f += "isbn_10" to it }
        d.asin?.let { f += "asin" to it }
        d.pages?.let { f += "page_count" to it.toString() }
        d.publisher?.let { f += "publisher" to it }
        d.published?.let { f += "publish_date" to it }
        d.year?.let { f += "first_publish_year" to it.toString() }
        d.goodreadsUrl?.let { f += "goodreads_url" to it }
        d.web?.url?.let { f += "source_url" to it }
        d.format?.lowercase()?.let { fmt ->
            when {
                "audio" in fmt -> "audiobook"
                "hard" in fmt -> "hardcover"
                "paper" in fmt -> "paperback"
                "ebook" in fmt || "kindle" in fmt || "epub" in fmt || "digital" in fmt -> "ebook"
                else -> null
            }
        }?.let { f += "format" to it }
        return f
    }

    /** Books whose details went in through the account this run (so none are reported missing). */
    private val detailsSet = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val detailsWhy = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Finds the private book that [title] became (GET /private-books?search=) and fills in its
     * details and cover (PUT /private-books/{id}, as the app's "Edit book" sends it). True when done.
     */
    private fun fillPrivateBook(session: Session, document: String, title: String, details: io.github.prithirajn.trackersync.source.BookDetails): Boolean {
        if (session.data.str("accountToken") == null) return false
        val id = findPrivateBook(session, title) ?: return false
        val cover = details.coverUrl?.takeIf { it.startsWith("http") }?.let { runCatching { Http.bytes(it) }.getOrNull() }
        val fields = privateBookFields(details).toMutableList()
        if (cover == null) details.coverUrl?.takeIf { it.startsWith("http") }?.let { fields += "cover_url" to it }
        val (body, type) = form(fields, cover)
        val r = account(session, "PUT", "/private-books/${Http.enc(id)}", body, type)
        if (!r.ok) throw ServiceException("Seekquel didn't take the book's details (${accountError(r) ?: "HTTP ${r.code}"})")
        detailsSet += document
        return true
    }

    private fun findPrivateBook(session: Session, title: String): String? {
        val r = account(session, "GET", "/private-books?search=${Http.enc(title.take(80))}")
        if (!r.ok) return null
        val o = runCatching { JSONObject(r.body) }.getOrNull() ?: return null
        val list = (o.opt("data") as? JSONArray)?.objects()
            ?: o.obj("data")?.let { d -> (d.opt("data") as? JSONArray ?: d.arr("books") ?: d.arr("items"))?.objects() }
            ?: emptyList()
        fun titleOf(b: JSONObject) = b.str("title") ?: b.obj("book")?.str("title") ?: b.obj("work")?.str("title")
        val want = io.github.prithirajn.trackersync.sync.Matcher.normalizeText(title)
        val hit = list.firstOrNull { b -> titleOf(b)?.let { io.github.prithirajn.trackersync.sync.Matcher.normalizeText(it) == want } == true }
            ?: list.firstOrNull { b -> titleOf(b)?.let { io.github.prithirajn.trackersync.sync.Matcher.normalizeText(it).startsWith(want) } == true }
        return hit?.let { it.idString("id") ?: it.obj("book")?.idString("id") }
    }

    // ---- pairing ----

    override fun beginDeviceLink(): DeviceLink {
        val r = Http.request(
            "POST", "$BASE/pair/start", json,
            JSONObject().put("device_name", APP).put("app", APP).put("platform", "android").toString(),
        )
        val o = runCatching { JSONObject(r.body) }.getOrNull()
        val deviceCode = o?.str("device_code")
        val userCode = o?.str("user_code")
        if (r.code != 201 || deviceCode == null || userCode == null) {
            throw ServiceException(
                if (r.code == 429) "Too many link attempts; wait a minute and try again"
                else "Seekquel didn't start the link (${errorText(r)})"
            )
        }
        return DeviceLink(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = "https://seekquel.app/settings/integrations",
            intervalSeconds = o.int("interval") ?: 2,
            expiresInSeconds = o.int("expires_in") ?: 900,
        )
    }

    override fun pollDeviceLink(deviceCode: String): DevicePoll {
        val r = Http.request("POST", "$BASE/pair/poll", json, JSONObject().put("device_code", deviceCode).toString())
        val o = runCatching { JSONObject(r.body) }.getOrNull() ?: JSONObject()
        val key = o.str("key")
        if (r.code == 200 && key != null) {
            val session = Session(null)
            session.put("server", (o.str("api_url") ?: BASE).trimEnd('/'))
            session.put("key", key)
            o.idString("device_id")?.let { session.put("deviceId", it) }
            return DevicePoll.Linked(SignInResult(session.encode(), "Seekquel"))
        }
        val type = o.obj("error")?.str("type") ?: o.str("error")
        return when {
            type == "authorization_pending" || type == "slow_down" || r.code == 429 -> DevicePoll.Pending
            type == "access_denied" -> DevicePoll.Failed("The request was declined in Seekquel.")
            type == "expired_token" -> DevicePoll.Failed("The code expired. Get a new one.")
            type == "invalid_grant" -> DevicePoll.Failed("Seekquel didn't recognise the code. Get a new one.")
            r.code >= 500 -> DevicePoll.Pending
            else -> DevicePoll.Failed("Seekquel answered HTTP ${r.code}")
        }
    }

    // ---- books ----

    /** Seekquel's catalogue search, for picking the book by hand: results are work ids. */
    override fun search(session: Session, title: String, author: String?): List<RemoteBook> {
        val q = listOfNotNull(title, author).joinToString(" ").trim().take(200)
        if (q.length < 2) return emptyList()
        val r = send(session, "GET", "/books/search?q=${Http.enc(q)}")
        if (!r.ok) throw ServiceException("Seekquel search failed (${errorText(r)})")
        val root = runCatching { JSONObject(r.body) }.getOrNull()
        val items = root?.arr("data")?.objects()
            ?: runCatching { JSONArray(r.body).objects() }.getOrNull()
            ?: emptyList()
        return items.mapNotNull { b ->
            val work = b.idString("work_id") ?: return@mapNotNull null
            descriptionOf(b)?.let { searchDescriptions[work] = it }
            RemoteBook(work, b.str("title") ?: "Untitled", b.str("authors"), coverUrl = b.str("cover_url"), pages = b.int("page_count"), year = b.firstYear("year"))
        }
    }

    /** Descriptions that came with search results (work id -> text), when the search sends them. */
    private val searchDescriptions = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun descriptionOf(o: JSONObject): String? =
        (o.str("description") ?: o.str("synopsis") ?: o.str("summary") ?: o.str("blurb"))
            ?.let { io.github.prithirajn.trackersync.util.Html.text(it.replace(Regex("(?i)<br\\s*/?>|</p>"), "\n\n")) }
            ?.trim()?.takeIf { it.isNotEmpty() }

    /** A catalogue book's description: from its search result, else its own page in the API (when it has one). */
    private fun workDescription(session: Session, work: String): String? {
        searchDescriptions[work]?.let { return it }
        for (path in listOf("/books/${Http.enc(work)}", "/works/${Http.enc(work)}")) {
            val r = runCatching { send(session, "GET", path) }.getOrNull() ?: continue
            if (!r.ok) continue
            val root = runCatching { JSONObject(r.body) }.getOrNull() ?: continue
            descriptionOf(root.obj("data") ?: root)?.let { return it }
        }
        return null
    }

    /**
     * What Seekquel knows about a book, for adding it elsewhere: your library's entry when it's
     * on your shelves (title, authors, pages, cover), else its catalogue's best match (title,
     * authors, pages, year). Works with Seekquel's sync paused. Null when Seekquel hasn't got it.
     */
    fun details(session: Session, link: Link?, title: String, author: String?): io.github.prithirajn.trackersync.source.BookDetails? {
        val plain = io.github.prithirajn.trackersync.sync.Matcher.plainTitle(title)
        fun same(t: String, a: String?) = io.github.prithirajn.trackersync.sync.Matcher.sameBook(title, author, t, a) ||
            io.github.prithirajn.trackersync.sync.Matcher.score(plain, author, RemoteBook("", t, a)) >= 0.8
        val lib = runCatching { library(session) }.getOrNull().orEmpty()
        val mine = lib.firstOrNull { b -> link != null && link.libraryKeys.any { it in b.keys } }
            ?: lib.firstOrNull { same(it.title, it.author) }
        if (mine != null) {
            // Your shelf entry, with the catalogue book's description when the entry has none.
            val workId = mine.keys.firstOrNull { it.startsWith("work:") }?.removePrefix("work:")
            val desc = mine.description ?: workId?.let { w -> runCatching { workDescription(session, w) }.getOrNull() }
            return io.github.prithirajn.trackersync.source.BookDetails(
                title = mine.title, authors = mine.author?.split(Regex("\\s*,\\s*"))?.filter { it.isNotBlank() } ?: emptyList(),
                pages = mine.pageCount, coverUrl = mine.coverUrl, description = desc, source = "Seekquel",
                format = mine.mediaKind?.let { io.github.prithirajn.trackersync.source.BookInfo.formatName(it) ?: if (it.contains("phys", true) || it.contains("print", true)) "Paperback" else null },
            )
        }
        val hit = runCatching { search(session, plain, author) }.getOrNull()?.firstOrNull { same(it.title, it.author) } ?: return null
        return io.github.prithirajn.trackersync.source.BookDetails(
            title = hit.title, authors = hit.author?.split(Regex("\\s*,\\s*"))?.filter { it.isNotBlank() } ?: emptyList(),
            pages = hit.pages, published = hit.year?.toString(), coverUrl = hit.coverUrl, source = "Seekquel",
            // The catalogue book's description, which the shelf entry doesn't carry.
            description = runCatching { workDescription(session, hit.id) }.getOrNull(),
        )
    }

    /**
     * A comic's collected volumes as Seekquel's catalogue has them ("Saga, Vol. 1"…), with which
     * issues each collects, read from their descriptions ("Collects SAGA #1-6"). Null when none say.
     */
    fun comicVolumes(session: Session, title: String, now: Long = System.currentTimeMillis() / 1000): io.github.prithirajn.trackersync.source.ComicCollections.Map? {
        val plain = io.github.prithirajn.trackersync.sync.Matcher.plainTitle(title)
        val want = io.github.prithirajn.trackersync.sync.Matcher.baseTitle(io.github.prithirajn.trackersync.sync.MangaSources.seriesTitleOf(title))
        val hits = LinkedHashMap<String, RemoteBook>()
        for (q in listOf("$plain vol", "$plain volume", plain)) {
            runCatching { search(session, q, null) }.getOrNull().orEmpty().forEach { hits.putIfAbsent(it.id, it) }
        }
        val books = hits.values.mapNotNull { b ->
            // Single issues listed next to the volumes aren't volumes.
            if (io.github.prithirajn.trackersync.sync.Matcher.isSingleIssue(b.title, b.pages)) return@mapNotNull null
            val v = io.github.prithirajn.trackersync.sync.Matcher.volume(b.title)?.toDoubleOrNull() ?: return@mapNotNull null
            if (io.github.prithirajn.trackersync.sync.Matcher.baseTitle(io.github.prithirajn.trackersync.sync.MangaSources.seriesTitleOf(b.title)) != want) return@mapNotNull null
            b to v
        }.sortedBy { it.second }.take(30).map { (b, v) ->
            Triple(v, b.title, runCatching { workDescription(session, b.id) }.getOrNull())
        }
        return io.github.prithirajn.trackersync.source.ComicCollections.fromSeriesBooks(title, books, "seekquel", now)
    }

    /**
     * What Seekquel says about a book: its genres, and the moods, tropes, pace and content
     * warnings it keeps for the work. The work on your shelves, else a clear catalogue match.
     */
    fun facts(session: Session, link: Link?, title: String, author: String?, now: Long = System.currentTimeMillis() / 1000): io.github.prithirajn.trackersync.data.BookFacts? {
        val plain = io.github.prithirajn.trackersync.sync.Matcher.plainTitle(title)
        fun same(t: String, a: String?) = io.github.prithirajn.trackersync.sync.Matcher.sameBook(title, author, t, a) ||
            io.github.prithirajn.trackersync.sync.Matcher.score(plain, author, RemoteBook("", t, a)) >= 0.8
        val lib = runCatching { library(session) }.getOrNull().orEmpty()
        val mine = lib.firstOrNull { b -> link != null && link.libraryKeys.any { it in b.keys } } ?: lib.firstOrNull { same(it.title, it.author) }
        val work = mine?.keys?.firstOrNull { it.startsWith("work:") }?.removePrefix("work:")
            ?: runCatching { search(session, plain, author) }.getOrNull()?.firstOrNull { same(it.title, it.author) }?.id
            ?: run { factsWhy = "not found in Seekquel's catalogue or your library"; return null }
        val tried = ArrayList<String>()
        // With your Seekquel account signed in, the app's own API too (where Seekquel's app reads a book's page).
        val signedIn = session.data.str("accountToken") != null
        val paths = listOf("/works/${Http.enc(work)}", "/books/${Http.enc(work)}").map { false to it } +
            (if (signedIn) listOf("/works/${Http.enc(work)}", "/books/${Http.enc(work)}", "/works/${Http.enc(work)}/details").map { true to it } else emptyList())
        for ((viaAccount, path) in paths) {
            val r = runCatching { if (viaAccount) account(session, "GET", path) else send(session, "GET", path) }.getOrNull()
            val where = (if (viaAccount) "account " else "") + path
            if (r == null) { tried += "$where: no answer"; continue }
            if (!r.ok) { tried += "$where: HTTP ${r.code}"; continue }
            val root = runCatching { JSONObject(r.body) }.getOrNull()
            if (root == null) { tried += "$where: not JSON"; continue }
            val w = root.obj("data") ?: root
            val f = parseFacts(w, now)
            if (!f.isEmpty) { factsWhy = null; return f }
            // What the work did have, so the right field names can be read next time.
            val keys = w.keys().asSequence().toList().sorted().joinToString(",")
            val enrich = w.obj("enrichment")?.keys()?.asSequence()?.toList()?.sorted()?.joinToString(",")
            tried += "$where: fields [$keys]" + (enrich?.let { " enrichment [$it]" } ?: "")
        }
        // Signed in on the website: the book's own page there, as Goodreads and StoryGraph are read.
        if (session.data.optBoolean("webSignedIn")) {
            io.github.prithirajn.trackersync.source.SeekquelWeb.facts(work, now)?.let { factsWhy = null; return it }
            tried += "website: " + (io.github.prithirajn.trackersync.source.SeekquelWeb.why ?: "nothing")
        }
        factsWhy = "work $work had no tags (" + tried.joinToString("; ") + ")" +
            (if (!session.data.optBoolean("webSignedIn")) "; not signed in on the Seekquel website" else "")
        return null
    }

    /** Why the last [facts] call found nothing, for the sync log; null when it found tags. */
    @Volatile var factsWhy: String? = null

    /** "dark-academia" -> "Dark academia"; an object's name or label when it's one. */
    private fun label(v: Any?): String? {
        val raw = when (v) {
            is String -> v
            is JSONObject -> v.str("name") ?: v.str("label") ?: v.str("title") ?: v.str("slug")
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        val spaced = if (raw.contains(' ')) raw else raw.replace('_', ' ').replace('-', ' ')
        return spaced.replaceFirstChar { it.uppercase() }
    }

    private fun labels(a: Any?, max: Int): List<String> {
        val arr = a as? org.json.JSONArray ?: return emptyList()
        return (0 until arr.length()).mapNotNull { label(arr.opt(it)) }.distinctBy { it.lowercase() }.take(max)
    }

    /** Reads a Seekquel work's genres and its enrichment (mood, tropes or themes, pace, content warnings). */
    fun parseFacts(w: JSONObject, now: Long): io.github.prithirajn.trackersync.data.BookFacts {
        val e = w.obj("enrichment") ?: JSONObject()
        val pace = (e.opt("pace") ?: w.opt("pace"))?.let { label(it) }?.let { p ->
            val l = p.lowercase()
            when {
                l.endsWith("paced") -> p.replace(" paced", "-paced")
                l in setOf("fast", "medium", "slow") -> "${p}-paced"
                else -> p
            }
        }
        val rating = listOf("average_rating", "rating_average", "avg_rating", "rating")
            .firstNotNullOfOrNull { k -> w.opt(k).let { it as? Number }?.toDouble()?.takeIf { r -> r in 0.1..5.0 } }
        return io.github.prithirajn.trackersync.data.BookFacts(
            rating = rating,
            pace = pace,
            moods = labels(e.opt("mood") ?: w.opt("mood") ?: w.opt("moods"), 6),
            genres = labels(w.opt("genres") ?: w.opt("genre"), 6),
            tropes = labels(e.opt("tropes") ?: e.opt("themes") ?: w.opt("tropes"), 8),
            warnings = labels(e.opt("content_warnings") ?: w.opt("content_warnings"), 16),
            ratings = rating?.let { mapOf("Seekquel" to it) } ?: emptyMap(),
            sources = listOf("Seekquel"),
            at = now,
        )
    }

    /** Seekquel's catalogue search takes an ISBN as the query. */
    override fun findByIsbn(session: Session, isbn: String): List<RemoteBook> = search(session, isbn, null)

    /** Points this CrossPoint document at the chosen Seekquel book (creating the document first). */
    // ---- a book not in Seekquel's catalogue: your own private book ----

    override val addsBooks = true
    override val addBookNote = "Becomes your own private book in Seekquel (the catalogue isn't changed), with its details."

    /**
     * The book's details (PUT /documents/{d}/metadata), then linked to nothing, which makes it
     * your own private book (PUT /documents/{d}/link with null, as the Reading API describes).
     */
    override fun addBook(session: Session, document: String, details: io.github.prithirajn.trackersync.source.BookDetails): RemoteBook {
        val title = listOfNotNull(details.title, details.subtitle).joinToString(": ")
        val authors = details.authors.joinToString(", ").ifEmpty { null }
        val meta = bookMetadata(details)
        // 1. The document, with every detail in the progress update's metadata (the way title and
        //    author already reach Seekquel), cover included.
        putProgress(session, document, title, authors, 0.0, meta)
        // 2. Its own details call too, where Seekquel has one (best effort: some details may only go this way).
        val full = JSONObject(meta.toString()).put("title", title).also { m -> authors?.let { m.put("authors", it) } }
        val metaCall = runCatching { send(session, "PUT", "/documents/${Http.enc(document)}/metadata", full) }.getOrNull()
        // 3. Linked to nothing: your own private book.
        val r = send(session, "PUT", "/documents/${Http.enc(document)}/link", JSONObject().put("work_id", JSONObject.NULL))
        if (!r.ok) throw ServiceException("Seekquel didn't make it a private book (${errorText(r)})")
        // 4. The details once more, now that the private book exists, so they land on it.
        runCatching { putProgress(session, document, title, authors, 0.0, meta) }
        if (metaCall?.ok == true) runCatching { send(session, "PUT", "/documents/${Http.enc(document)}/metadata", full) }
        // 5. With your Seekquel account signed in: its cover and full details, as Seekquel's app edits a private book.
        //    It's added either way; why the details didn't go is told with what's missing.
        runCatching { fillPrivateBook(session, document, title, details) }.onFailure { e ->
            detailsWhy[document] = e.message?.replaceFirstChar { it.lowercase() } ?: "the details didn't go through"
        }
        return RemoteBook("", details.title, details.author, coverUrl = details.coverUrl, pages = details.pages, year = details.year)
    }

    /** The book's details as Seekquel's metadata fields (named as its library and search send them). */
    internal fun bookMetadata(d: io.github.prithirajn.trackersync.source.BookDetails): JSONObject {
        val m = JSONObject()
        listOfNotNull(d.isbn13, d.isbn10).joinToString(" ").ifEmpty { null }?.let { m.put("identifiers", it) }
        d.isbn13?.let { m.put("isbn", it) }
        d.pages?.let { m.put("page_count", it) }
        d.language?.let { m.put("language", it) }
        d.description?.let { m.put("description", it.take(20_000)) }
        d.series?.let { m.put("series", it) }
        d.seriesIndex?.toDoubleOrNull()?.let { m.put("series_index", it) }
        d.format?.let { m.put("format", it) }
        d.coverUrl?.takeIf { it.startsWith("http") }?.let { m.put("cover_url", it) }
        d.publisher?.let { m.put("publisher", it) }
        d.published?.let { m.put("published", it) }
        d.year?.let { m.put("year", it) }
        if (d.genres.isNotEmpty()) m.put("genres", org.json.JSONArray(d.genres.take(10)))
        return m
    }

    /**
     * Which of the book's details Seekquel shows after adding it ("cover", "description"…), read
     * back from the document, so a detail it didn't keep is named in the activity log.
     */
    override fun missingAfterAdd(session: Session, document: String, remoteId: String, details: io.github.prithirajn.trackersync.source.BookDetails): List<String>? {
        if (detailsSet.remove(document)) return emptyList()
        val why = detailsWhy.remove(document)
            ?: if (session.data.str("accountToken") == null) "sign in to your Seekquel account in Services → Seekquel to send them" else null
        val r = runCatching { send(session, "GET", "/documents/${Http.enc(document)}") }.getOrNull() ?: return null
        if (!r.ok) return null
        val o = runCatching { JSONObject(r.body).let { it.obj("data") ?: it } }.getOrNull() ?: return null
        val all = o.toString()
        val book = o.obj("book") ?: o.obj("metadata") ?: o
        fun has(vararg keys: String) = keys.any { k -> book.has(k) && !book.isNull(k) && book.optString(k).isNotBlank() } ||
            keys.any { k -> all.contains("\"$k\":\"") || Regex("\"$k\":\\d").containsMatchIn(all) }
        return listOfNotNull(
            if (details.coverUrl != null && !has("cover_url", "cover", "image_url")) "cover" else null,
            if (details.description != null && !has("description", "synopsis")) "description" else null,
            if (details.pages != null && !has("page_count", "pages")) "page count" else null,
            if ((details.isbn13 ?: details.isbn10) != null && !has("isbn", "identifiers", "isbn13")) "ISBN" else null,
        ).let { missing -> if (missing.isNotEmpty() && why != null) missing.dropLast(1) + "${missing.last()} ($why)" else missing }
    }

    override fun linkToBook(session: Session, document: String, title: String?, author: String?, book: RemoteBook, percent: Int) {
        var r = send(session, "PUT", "/documents/${Http.enc(document)}/link", JSONObject().put("work_id", book.id))
        if (r.code == 404) {
            putProgress(session, document, title, author, percent / 100.0)
            r = send(session, "PUT", "/documents/${Http.enc(document)}/link", JSONObject().put("work_id", book.id))
        }
        if (!r.ok) throw ServiceException("Seekquel didn't link the book (${errorText(r)})")
    }

    /**
     * The reader's whole Seekquel library (GET /library, cursor-paged): every book with its
     * status and progress, whichever app or person put it there.
     */
    override fun library(session: Session): List<LibraryBook> {
        val out = ArrayList<LibraryBook>()
        var cursor: String? = null
        var pages = 0
        do {
            val r = send(session, "GET", "/library" + (cursor?.let { "?cursor=${Http.enc(it)}" } ?: ""))
            if (!r.ok) throw ServiceException("Seekquel didn't send your library (${errorText(r)})")
            val root = JSONObject(r.body)
            for (b in root.arr("data")?.objects() ?: emptyList()) {
                val id = b.idString("id") ?: b.idString("work_id") ?: continue
                val title = b.str("title") ?: continue
                out.add(
                    LibraryBook(
                        id = id,
                        title = title,
                        author = authorsOf(b),
                        coverUrl = b.str("cover_url"),
                        status = b.str("status")?.let { s -> statusNames.entries.firstOrNull { it.value == s }?.key },
                        fraction = fractionOf(b),
                        updatedAt = b.str("updated_at")?.let { parseTime(it) } ?: 0L,
                        keys = keysOf(b, "book"),
                        finishedAt = dateOf(b, "finished_at", "finished_on", "date_finished", "finish_date", "read_at", "completed_at"),
                        startedAt = dateOf(b, "started_at", "started_on", "date_started", "start_date"),
                        rating = b.num("rating")?.takeIf { it > 0 },
                        pageCount = b.num("page_count")?.toInt()?.takeIf { it > 0 },
                        mediaKind = b.str("media_kind"),
                        description = b.str("description") ?: b.str("synopsis"),
                    )
                )
            }
            val next = root.obj("meta")?.str("next_cursor")
            cursor = next?.takeIf { it.isNotBlank() && it != cursor }
            pages++
        } while (cursor != null && pages < 30)
        return out
    }

    /**
     * The ids Seekquel uses for a book, tagged by kind so different id spaces can't collide.
     * [ownKind] is what a bare "id" means in this object.
     */
    private fun keysOf(o: JSONObject, ownKind: String): Set<String> {
        val out = HashSet<String>()
        o.idString("id")?.let { out.add("$ownKind:$it") }
        o.idString("book_id")?.let { out.add("book:$it") }
        o.idString("work_id")?.let { out.add("work:$it") }
        o.idString("private_book_id")?.let { out.add("private:$it") }
        o.idString("edition_id")?.let { out.add("edition:$it") }
        return out
    }

    override fun libraryKeys(session: Session, link: Link): Set<String> {
        val r = send(session, "GET", "/documents/${Http.enc(link.document)}")
        if (!r.ok) return emptySet()
        val o = runCatching { JSONObject(r.body) }.getOrNull()?.let { it.obj("data") ?: it } ?: return emptySet()
        val keys = HashSet<String>()
        // The document's own id is ours (CrossPoint's hash), so only the book's ids count.
        o.obj("book")?.let { keys.addAll(keysOf(it, "book")) }
        o.idString("book_id")?.let { keys.add("book:$it") }
        o.idString("work_id")?.let { keys.add("work:$it") }
        o.idString("private_book_id")?.let { keys.add("private:$it") }
        o.idString("edition_id")?.let { keys.add("edition:$it") }
        return keys
    }

    /** `authors` arrives as a string, a list of names, or a list of {name}. */
    private fun authorsOf(b: JSONObject): String? {
        val list = b.optJSONArray("authors")
            ?: return (b.opt("authors") as? String)?.takeIf { it.isNotBlank() }
        val names = (0 until list.length()).mapNotNull { i ->
            when (val v = list.opt(i)) {
                is String -> v
                is JSONObject -> v.str("name")
                else -> null
            }?.takeIf { it.isNotBlank() }
        }
        return names.joinToString(", ").ifEmpty { null }
    }

    /** `progress_percent` is 0–100; falls back to current page over page count. */
    private fun fractionOf(b: JSONObject): Double? {
        b.num("progress_percent")?.let { return (it / 100.0).coerceIn(0.0, 1.0) }
        val page = b.num("current_page")
        val total = b.num("page_count")
        if (page != null && total != null && total > 0) return (page / total).coerceIn(0.0, 1.0)
        return null
    }

    /**
     * A time Seekquel sends: a full timestamp ("2024-02-10T18:20:00Z"), or a plain day
     * ("2024-02-10", how reading dates usually come), read as noon that day here so it stays that day.
     */
    internal fun parseTime(s: String): Long? {
        val t = s.trim()
        return runCatching { java.time.OffsetDateTime.parse(t).toEpochSecond() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(t.removeSuffix("Z")).toEpochSecond(java.time.ZoneOffset.UTC) }.getOrNull()
            ?: runCatching { java.time.LocalDate.parse(t.take(10)).atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toEpochSecond() }.getOrNull()
            ?: t.toLongOrNull()?.let { if (it > 100_000_000_000L) it / 1000 else it }?.takeIf { it > 0 }
    }

    /** The first of [keys] that holds a time: at the top, or in a "dates" / "read" / "reading" object inside. */
    private fun dateOf(b: JSONObject, vararg keys: String): Long? {
        val places = listOfNotNull(b, b.obj("dates"), b.obj("read"), b.obj("reading"), b.obj("latest_read"), b.optJSONArray("reads")?.optJSONObject(0))
        for (o in places) for (k in keys) {
            val v = o.opt(k) ?: continue
            if (v == JSONObject.NULL) continue
            parseTime(v.toString())?.let { return it }
        }
        return null
    }

    override fun state(session: Session, link: Link): RemoteState {
        val r = send(session, "GET", "/documents/${Http.enc(link.document)}")
        // Not created yet: the first progress push creates it.
        if (r.code == 404) return RemoteState(null, null)
        if (!r.ok) throw ServiceException("Seekquel answered ${errorText(r)}")
        val o = JSONObject(r.body).let { it.obj("data") ?: it }
        val pct = o.num("percentage")?.let { Math.round(it * 100).toInt() }
        // The document carries no reading status; assume Reading unless we've marked it otherwise.
        val status = link.lastStatus?.takeIf { it != ReadStatus.READING } ?: ReadStatus.READING
        return RemoteState(status, pct)
    }

    private fun putProgress(session: Session, document: String, title: String?, author: String?, fraction: Double, extra: JSONObject? = null) {
        val body = JSONObject()
            .put("document", document)
            .put("progress", "%.4f".format(Locale.US, fraction))
            .put("percentage", fraction.coerceIn(0.0, 1.0))
            .put("device", APP)
        val meta = JSONObject()
        title?.let { meta.put("title", it) }
        author?.let { meta.put("authors", it) }
        // More of the book's details (adding a book): they ride in the same metadata block.
        extra?.let { e -> for (k in e.keys()) meta.put(k, e.get(k)) }
        if (meta.length() > 0) body.put("metadata", meta)
        val r = send(session, "PUT", "/progress", body)
        if (!r.ok) throw ServiceException("Seekquel didn't accept the progress (${errorText(r)})")
    }

    override fun pushProgress(session: Session, link: Link, percent: Int, visibility: Visibility) {
        putProgress(session, link.document, link.title, link.author, percent.coerceIn(0, 100) / 100.0)
    }

    override fun setStatus(session: Session, link: Link, status: ReadStatus) {
        val name = statusNames[status] ?: return
        if (status == ReadStatus.READING) {
            // Reading is what a progress push says already; creating the document is enough.
            return
        }
        if (status == ReadStatus.FINISHED) putProgress(session, link.document, link.title, link.author, 1.0)
        var r = send(session, "PUT", "/documents/${Http.enc(link.document)}/status", JSONObject().put("status", name))
        if (r.code == 404) {
            putProgress(session, link.document, link.title, link.author, 0.0)
            r = send(session, "PUT", "/documents/${Http.enc(link.document)}/status", JSONObject().put("status", name))
        }
        if (notLinked(r)) throw SkipException(NOT_LINKED_NOTE)
        if (!r.ok) throw ServiceException("Seekquel didn't accept ${status.label} (${errorText(r)})")
    }

    /** Seekquel keys highlights by our external_id, so sending again replaces the entry. */
    override val updatesHighlights = true
    override val oneEntryPerHighlight = true

    override fun postHighlight(session: Session, link: Link, percent: Int, clip: Clipping, previousId: String?): String? {
        postHighlights(session, link, percent, listOf(clip))
        return "tracker-sync:${clip.id}"
    }

    override fun postHighlights(session: Session, link: Link, percent: Int, entries: List<Clipping>) {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        // The book's page count, where Seekquel has one, so each highlight gets its exact page.
        val total = if (entries.any { it.page == null && it.percent != null }) runCatching {
            val r = send(session, "GET", "/documents/${Http.enc(link.document)}")
            if (!r.ok) null else JSONObject(r.body).let { it.obj("data") ?: it }.let { o -> o.num("page_count") ?: o.obj("book")?.num("page_count") }?.toInt()
        }.getOrNull() else null
        for (chunk in entries.chunked(500)) {
            val list = JSONArray()
            for (c in chunk) {
                val h = JSONObject()
                    // Stable per highlight: a resend updates the entry instead of duplicating it.
                    .put("external_id", "tracker-sync:${c.id}")
                    .put("text", c.text.take(20_000))
                if (c.createdAt > 0) h.put("created_at", iso.format(Date(c.createdAt * 1000)))
                c.note?.takeIf { it.isNotBlank() }?.let { h.put("note", it) }
                c.chapter?.takeIf { it.isNotBlank() }?.let { h.put("chapter", it) }
                // Its place in the book, as a print page.
                c.pageIn(total)?.let { h.put("page", it) }
                list.put(h)
            }
            val r = send(session, "POST", "/highlights", JSONObject().put("document", link.document).put("highlights", list))
            if (notLinked(r) || r.code == 404) throw ServiceException(NOT_LINKED_NOTE)
            if (!r.ok) throw ServiceException("Seekquel didn't accept the highlights (${errorText(r)})")
        }
    }
}
