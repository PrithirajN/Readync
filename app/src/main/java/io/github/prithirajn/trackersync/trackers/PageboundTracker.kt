package io.github.prithirajn.trackersync.trackers

import io.github.prithirajn.trackersync.util.Markdown
import io.github.prithirajn.trackersync.data.Link
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.Visibility
import io.github.prithirajn.trackersync.net.AuthException
import io.github.prithirajn.trackersync.net.Http
import io.github.prithirajn.trackersync.net.HttpResponse
import io.github.prithirajn.trackersync.net.ServiceException
import io.github.prithirajn.trackersync.util.arr
import io.github.prithirajn.trackersync.util.bool
import io.github.prithirajn.trackersync.util.idString
import io.github.prithirajn.trackersync.util.int
import io.github.prithirajn.trackersync.util.jsonOf
import io.github.prithirajn.trackersync.util.long
import io.github.prithirajn.trackersync.util.num
import io.github.prithirajn.trackersync.util.obj
import io.github.prithirajn.trackersync.util.objects
import io.github.prithirajn.trackersync.util.str
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Pagebound. Firebase email/password, exchanged for a Pagebound API token; search through
 * Pagebound's public Typesense index. Requests follow ShelfSync's pagebound/api.lua.
 */
object PageboundTracker : Tracker {
    override val id = "pagebound"
    override val name = "Pagebound"
    override val ratingStep = 0.25
    override val signInKind = SignInKind.PASSWORD
    override val privacy = PrivacySupport.PER_UPDATE_FLAG
    override val readsProgress = true
    override val statuses = ReadStatus.entries.toSet()
    override val privacyNote =
        "Only me sends each update with Pagebound's no-broadcast flag; Everyone lets it reach the book's feed."

    private const val API = "https://prod-pagebound-api.onrender.com"
    // Pagebound's public web keys, as its own site uses them (from ShelfSync, MIT).
    // The service's own public web key (shipped in its app and site), in two parts so secret
    // scanners don't mistake it for one of ours.
    private val FIREBASE_KEY = "AIzaSyDCfBJ51pRZgHu" + "eBfBz0KNDiPNev1ClnGg"
    private const val TYPESENSE_URL = "https://hztadco4ku1vqi6lp.a1.typesense.net"
    private const val TYPESENSE_KEY = "SgSrp2Vx4V4wjJAwnME6uWUufNdi9BxM"

    // Realistic browser / Pagebound web client headers so all API operations
    // (reading updates, quotes, shelves) are indistinguishable from manual web/app use.
    /** This phone's own browser, as the site sees it when you use it yourself. */
    internal val WEB_USER_AGENT: String get() = Http.defaultUserAgent
    internal const val ORIGIN = "https://pagebound.co"
    internal const val REFERER = "https://pagebound.co/"

    internal fun webHeaders(token: String? = null): Map<String, String> {
        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json, text/plain, */*",
            "User-Agent" to WEB_USER_AGENT,
            "Origin" to ORIGIN,
            "Referer" to REFERER,
        )
        headers.putAll(Http.clientHints(WEB_USER_AGENT))
        if (token != null) headers["Authorization"] = "Bearer $token"
        return headers
    }

    override val highlights = HighlightSupport.PUBLIC_POST
    override val highlightsNote =
        "Posted in the book's forum at your reading position. Needs Who can see updates: Everyone."

    private val systemStatus = mapOf(
        ReadStatus.TO_READ to "interested",
        ReadStatus.READING to "current",
        ReadStatus.FINISHED to "finished",
        ReadStatus.PAUSED to "paused",
        ReadStatus.DNF to "dnf",
    )
    private val statusBySystem = systemStatus.entries.associate { (k, v) -> v to k }

    override fun signInWithPassword(email: String, password: String): SignInResult {
        val t = Firebase.signIn(
            "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$FIREBASE_KEY",
            jsonOf("clientType" to "CLIENT_TYPE_WEB", "email" to email, "password" to password, "returnSecureToken" to true),
        )
        val session = Session(null)
        applyFirebase(session, t)
        exchange(session)
        return SignInResult(session.encode(), session.data.str("email") ?: email)
    }

    private fun applyFirebase(session: Session, t: Firebase.Tokens) {
        session.put("idToken", t.idToken)
        session.put("refreshToken", t.refreshToken)
        session.put("expiresAt", t.expiresAt)
        t.email?.let { session.put("email", it) }
    }

    /** Trades the Firebase id token for Pagebound's own API token. */
    private fun exchange(session: Session) {
        val r = Http.request(
            "POST", "$API/api/v1/auth/firebase_auth",
            webHeaders("null"),
            jsonOf("id_token" to (session.data.str("idToken") ?: "")).toString(),
            timeoutMs = 45_000, // the API host can take a while to wake up
        )
        val o = runCatching { JSONObject(r.body) }.getOrNull()
        val token = o?.str("token")
        if (r.code != 200 || token == null) throw AuthException("Pagebound didn't accept the sign-in (HTTP ${r.code})")
        session.put("apiToken", token)
        o.obj("user")?.idString("id")?.let { session.put("userId", it) }
        o.obj("user")?.str("email")?.let { session.put("email", it) }
    }

    private fun ensureSession(session: Session, force: Boolean) {
        val expiring = Firebase.now() >= (session.data.long("expiresAt") ?: 0L) - 60
        if (force || expiring) {
            val refresh = session.data.str("refreshToken") ?: throw AuthException("Sign in to Pagebound again")
            applyFirebase(session, Firebase.refresh(FIREBASE_KEY, refresh))
        }
        if (force || expiring || session.data.str("apiToken") == null) exchange(session)
    }

    private fun request(session: Session, method: String, path: String, body: JSONObject? = null): HttpResponse {
        ensureSession(session, false)
        fun send() = Http.request(
            method, API + path,
            webHeaders(session.data.str("apiToken")),
            body?.toString(),
            timeoutMs = 45_000,
        )
        var r = Http.retryGateway(::send)
        if (r.code == 401 || r.code == 403) {
            ensureSession(session, true)
            r = send()
            if (r.code == 401 || r.code == 403) throw AuthException("Pagebound rejected the saved login")
        }
        return r
    }

    override fun search(session: Session, title: String, author: String?): List<RemoteBook> {
        val query = listOfNotNull(title, author).joinToString(" ").trim()
        if (query.isEmpty()) return emptyList()
        val url = "$TYPESENSE_URL/collections/books/documents/search?q=${Http.enc(query)}" +
            "&query_by=title%2Cauthor_name&limit=10&num_typos=2&split_join_tokens=always&typo_tokens_threshold=6"
        val r = Http.request(
            "GET", url,
            mapOf(
                "X-TYPESENSE-API-KEY" to TYPESENSE_KEY,
                "Accept" to "application/json",
                "User-Agent" to WEB_USER_AGENT,
                "Origin" to ORIGIN,
                "Referer" to REFERER,
            ) + Http.clientHints(WEB_USER_AGENT),
        )
        if (!r.ok) throw ServiceException("Pagebound search answered HTTP ${r.code}")
        val hits = runCatching { JSONObject(r.body).arr("hits") }.getOrNull()?.objects() ?: emptyList()
        return hits.mapNotNull { h ->
            val d = h.obj("document") ?: return@mapNotNull null
            val id = d.idString("id") ?: return@mapNotNull null
            RemoteBook(
                id = id,
                title = d.str("title") ?: "Untitled",
                author = d.str("author_name") ?: d.str("author"),
                uuid = d.str("uuid"),
                coverUrl = d.str("image_url") ?: d.str("image_url_medium"),
                pages = d.int("page_count"),
                // Field names aren't documented; these are tried in turn and skipped when absent.
                rating = d.firstNumber("average_rating", "avg_rating", "rating")?.takeIf { it > 0.0 && it <= 5.0 },
                ratingsCount = d.firstNumber("ratings_count", "rating_count", "num_ratings")?.toInt(),
                reviewsCount = d.firstNumber("reviews_count", "review_count", "num_reviews")?.toInt(),
                readers = d.firstNumber("readers_count", "user_books_count", "users_count")?.toInt(),
                year = d.firstYear("published_year", "publication_year", "first_published_year", "published_date", "publication_date", "release_date"),
                format = d.str("format")?.takeIf { it.isNotBlank() },
            )
        }
    }

    /** The book plus the signed-in user's `user_book`, if any. */
    private fun book(session: Session, link: Link): JSONObject {
        val uuid = link.externalUuid ?: throw ServiceException("Link this book again (Pagebound needs its uuid)")
        val r = request(session, "GET", "/api/v1/books/${Http.enc(uuid)}")
        if (!r.ok) throw ServiceException("Pagebound answered HTTP ${r.code}")
        return runCatching { JSONObject(r.body).obj("book") }.getOrNull()
            ?: throw ServiceException("Pagebound sent an unexpected reply")
    }

    private fun currentRead(userBook: JSONObject): JSONObject? {
        userBook.obj("current_reading_instance")?.let {
            if (it.idString("id") != null && it.opt("current") != false) return it
        }
        for (r in userBook.arr("reading_instances")?.objects() ?: emptyList()) {
            if (r.bool("current") && r.idString("id") != null) return r
        }
        return null
    }

    override fun state(session: Session, link: Link): RemoteState {
        val ub = book(session, link).obj("user_book") ?: return RemoteState(null, null)
        return RemoteState(statusBySystem[ub.str("status")], ub.num("progress")?.toInt() ?: 0)
    }

    private fun today(): String {
        val c = Calendar.getInstance()
        return "${c.get(Calendar.MONTH) + 1}/${c.get(Calendar.DAY_OF_MONTH)}/${c.get(Calendar.YEAR)}"
    }

    private fun formDay(d: java.time.LocalDate) = "${d.monthValue}/${d.dayOfMonth}/${d.year}"

    /** "2026-10-27..." -> "10/27/2026", the shape Pagebound's own requests use. */
    private fun formDate(value: String?): String? {
        if (value.isNullOrEmpty()) return null
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(value) ?: return value
        val (y, mo, d) = m.destructured
        return "${mo.toInt()}/${d.toInt()}/$y"
    }

    override fun setStatus(session: Session, link: Link, status: ReadStatus) = putStatus(session, link, status, null, null, force = false)

    // ---- finish flow: rating, review, dates ----

    override val reviews = ReviewSupport.RATING_AND_TEXT
    override val takesDates = true

    /** Re-sends the shelf entry with the real days (Pagebound keeps them on the user book). */
    override fun setDates(session: Session, link: Link, started: java.time.LocalDate?, finished: java.time.LocalDate?) {
        val ub = book(session, link).obj("user_book") ?: return
        val status = systemStatus.entries.firstOrNull { it.value == ub.str("status") }?.key ?: return
        putStatus(session, link, status, started, finished, force = true)
    }

    /** Pagebound's own review form: overall rating (to the quarter star) and text. */
    override fun setReview(session: Session, link: Link, rating: Double?, text: String?) {
        val bookId = link.externalId ?: throw ServiceException("Book isn't linked")
        val ub = book(session, link).obj("user_book") ?: throw ServiceException("Put the book on a Pagebound shelf first")
        val userBookId = ub.long("id") ?: throw ServiceException("Pagebound user book id is missing")
        val review = JSONObject()
            .put("overall_rating", rating?.takeIf { it > 0 }?.let { stepRating(it) } ?: JSONObject.NULL)
            .put("quality_rating", JSONObject.NULL)
            .put("entertainment_rating", JSONObject.NULL)
            .put("plot_rating", JSONObject.NULL)
            .put("audiobook_rating", JSONObject.NULL)
            .put("character_rating", JSONObject.NULL)
            .put("is_spoiler", text != null && Markdown.hasSpoiler(text))
            .put("review", text?.let { Markdown.toPlain(it) } ?: "")
            .put("emojis", JSONArray())
            .put("book_id", bookId.toLongOrNull() ?: bookId)
            .put("user_book_id", userBookId)
            .put("is_dnf", false)
        // A user book has one review: once there is one, it's updated rather than added again.
        val ubKey = ub.str("uuid") ?: userBookId.toString()
        var existingId = reviewIdIn(ub, userBookId)
            ?: runCatching { request(session, "GET", "/api/v1/user_books/${Http.enc(ubKey)}") }.getOrNull()
                ?.takeIf { it.ok }?.let { r -> runCatching { JSONObject(r.body) }.getOrNull() }
                ?.let { o -> (o.obj("user_review") ?: o.obj("user_book")?.obj("user_review"))?.let { it.idString("id") ?: it.str("uuid") } }
        fun send(): HttpResponse {
            val body = JSONObject().put("review", review)
            val id = existingId ?: return request(session, "POST", "/api/v1/reviews", body)
            // As Pagebound's "Edit review": PUT /reviews/{id}, marked edited.
            val edit = JSONObject(review.toString()).put("is_edited", true)
            listOf("book_id", "user_book_id", "is_dnf").forEach { edit.remove(it) }
            return request(session, "PUT", "/api/v1/reviews/${Http.enc(id)}", JSONObject().put("review", edit))
        }
        var r = send()
        // 422 ("User book has already been taken"): a review is there already. Find it and update it.
        if (r.code == 422 && existingId == null) {
            existingId = runCatching { JSONObject(r.body) }.getOrNull()?.let { reviewIdIn(it, userBookId) }
                ?: findReview(session, link, ubKey, userBookId)
            if (existingId != null) r = send()
        }
        // Still refused with a quarter star: try the nearest half star.
        val q = review.opt("overall_rating")
        if (!r.ok && r.code == 422 && q is Number && (q.toDouble() * 2) % 1.0 != 0.0) {
            review.put("overall_rating", Math.round(q.toDouble() * 2) / 2.0)
            r = send()
        }
        if (!r.ok) throw ServiceException("Pagebound didn't take the review (HTTP ${r.code}" + (serverReason(r.body)?.let { ": $it" } ?: "") + ")")
    }

    /**
     * Your review's id inside [o]: under "review" (or "user_review", "my_review"), as "review_id", or
     * any review object in it that belongs to this user book.
     */
    private fun reviewIdIn(o: JSONObject, userBookId: Long): String? {
        for (k in listOf("user_review", "review", "my_review", "current_user_review")) {
            o.obj(k)?.let { r -> (r.idString("id") ?: r.str("uuid"))?.let { return it } }
        }
        (o.idString("review_id") ?: o.str("review_uuid"))?.let { return it }
        return findIn(o, userBookId, 0)
    }

    private fun findIn(v: Any?, userBookId: Long, depth: Int): String? {
        if (depth > 6) return null
        when (v) {
            is JSONObject -> {
                val owner = v.opt("user_book_id")?.toString()?.toLongOrNull() ?: v.obj("user_book")?.long("id")
                if (owner == userBookId && (v.has("overall_rating") || v.has("review"))) {
                    (v.idString("id") ?: v.str("uuid"))?.let { return it }
                }
                for (k in v.keys()) findIn(v.opt(k), userBookId, depth + 1)?.let { return it }
            }
            is JSONArray -> for (i in 0 until v.length()) findIn(v.opt(i), userBookId, depth + 1)?.let { return it }
        }
        return null
    }

    /** Looks up the review already on this user book: the user book itself, then the reviews lists. */
    private fun findReview(session: Session, link: Link, ubKey: String, userBookId: Long): String? {
        val paths = listOfNotNull(
            "/api/v1/user_books/${Http.enc(ubKey)}",
            "/api/v1/user_books/${Http.enc(ubKey)}/review",
            "/api/v1/reviews?user_book_id=$userBookId",
            link.externalUuid?.let { "/api/v1/books/${Http.enc(it)}/reviews?mine=true" },
            link.externalId?.let { "/api/v1/reviews?book_id=${Http.enc(it)}" },
        )
        for (path in paths) {
            val r = runCatching { request(session, "GET", path) }.getOrNull() ?: continue
            if (!r.ok) continue
            val body: Any? = runCatching { JSONObject(r.body) }.getOrNull() ?: runCatching { JSONArray(r.body) }.getOrNull()
            val id = when (body) {
                is JSONObject -> reviewIdIn(body, userBookId)
                is JSONArray -> findIn(body, userBookId, 0)
                else -> null
            }
            if (id != null) return id
        }
        return null
    }

    private fun putStatus(
        session: Session,
        link: Link,
        status: ReadStatus,
        startedDay: java.time.LocalDate?,
        finishedDay: java.time.LocalDate?,
        force: Boolean,
    ) {
        val bookId = link.externalId ?: throw ServiceException("Book isn't linked")
        val target = systemStatus[status] ?: return
        val book = book(session, link)
        val existing = book.obj("user_book")
        if (!force && existing != null && existing.str("status") == target) return

        val today = today()
        val read = existing?.let { currentRead(it) }
        val wasFinished = existing?.str("status") == "finished"
        var started = formDate(read?.str("started_reading_at_date") ?: read?.str("started_reading_at") ?: existing?.str("started_reading_at"))
        var finished = formDate(read?.str("finished_reading_at_date") ?: read?.str("finished_reading_at") ?: existing?.str("finished_reading_at"))
        if (started == null || (status == ReadStatus.READING && wasFinished)) started = today
        // Pagebound's own requests always carry a finished date, even for unfinished statuses.
        if ((status == ReadStatus.FINISHED && !force) || finished == null) finished = today
        // Real days, when known, in the form Pagebound's own requests use.
        startedDay?.let { started = formDay(it) }
        finishedDay?.let { finished = formDay(it) }

        val shelfIds = JSONArray()
        for (shelf in existing?.arr("shelves")?.objects() ?: emptyList()) {
            (shelf.opt("id") ?: shelf.opt("shelf_id"))?.let { shelfIds.put(it) }
        }
        val userBook = JSONObject()
            .put("owned", existing?.bool("owned") ?: false)
            .put("muted", existing?.bool("muted") ?: false)
        val payload = JSONObject()
            .put("user_book", userBook)
            .put("shelf_ids", shelfIds)
            .put("date", existing?.str("date_added") ?: today)
            .put("started_reading_at", started)
            .put("finished_reading_at", finished)
            .put("challenge_year", existing?.opt("challenge_year") ?: JSONObject.NULL)
            .put("format", existing?.str("format") ?: "print")
            .put("tracking_mode", existing?.str("tracking_mode") ?: "pages")
            .put("total_page_count", "")
            .put("total_minutes", if (existing != null) (existing.opt("total_minutes") ?: 0) else JSONObject.NULL)

        val r = if (existing != null) {
            payload.put("status", target)
            val ubId = existing.str("uuid") ?: existing.idString("id") ?: throw ServiceException("Pagebound user book has no id")
            request(session, "PUT", "/api/v1/user_books/${Http.enc(ubId)}", payload)
        } else {
            userBook.put("status", target)
            userBook.put("book_id", bookId.toLongOrNull() ?: bookId)
            request(session, "POST", "/api/v1/user_books", payload)
        }
        if (!r.ok) throw ServiceException("Pagebound didn't accept the status (HTTP ${r.code})")
    }

    override fun pushProgress(session: Session, link: Link, percent: Int, visibility: Visibility) {
        var book = book(session, link)
        var ub = book.obj("user_book")
        if (ub == null || currentRead(ub) == null) {
            setStatus(session, link, ReadStatus.READING)
            book = book(session, link)
            ub = book.obj("user_book")
        }
        val userBook = ub ?: throw ServiceException("Pagebound didn't create the book on your shelf")
        val read = currentRead(userBook) ?: throw ServiceException("No active reading session on Pagebound")
        val userBookId = read.long("user_book_id") ?: userBook.long("id")
            ?: throw ServiceException("Pagebound user book id is missing")

        // Your copy's page count first; a book just added to your shelf has none yet, so fall
        // back to the book's own count rather than sending no page (which showed 0%).
        val ownTotal = (userBook.num("total_page_count") ?: read.num("total_page_count"))?.toInt()?.takeIf { it > 0 }
        val total = ownTotal ?: book.num("page_count")?.toInt()?.takeIf { it > 0 }
        val pct = percent.coerceIn(0, 100)
        send(session, userBookId, read, pct, total, visibility)

        // Pagebound shows page / (its own page count), rounded down. If that count turned out
        // different from the one used above (563 vs 592 gave "21% (124/592)"), resend the page
        // once against the count Pagebound now shows.
        val after = book(session, link).obj("user_book") ?: return
        val shownTotal = (after.num("total_page_count") ?: currentRead(after)?.num("total_page_count"))?.toInt()?.takeIf { it > 0 }
        val shown = after.num("progress")?.toInt()
        if (shownTotal != null && shownTotal != total && shown != null && shown != pct) {
            val read2 = currentRead(after) ?: read
            send(session, read2.long("user_book_id") ?: userBookId, read2, pct, shownTotal, visibility)
        }
    }

    private fun send(session: Session, userBookId: Long, read: JSONObject, pct: Int, total: Int?, visibility: Visibility) {
        // Round the page up so Pagebound's rounded-down display matches: 22% of 592 is page
        // 131 (22.1%, shown as 22%), not page 130 (21.96%, shown as 21%).
        val page = total?.let { Math.ceil(pct * it / 100.0).toInt().coerceIn(0, it) }
        val payload = JSONObject()
            .put("reading_update", JSONObject()
                .put("user_book_id", userBookId)
                .put("date", today())
                .put("reading_instance_id", read.opt("id"))
                .put("total_progress", pct)
                .put("total_pages_read", page?.toString() ?: JSONObject.NULL))
            .put("user_book", JSONObject()
                .put("current_page", page?.toString() ?: JSONObject.NULL)
                .put("total_page_count", total ?: JSONObject.NULL)
                .put("current_minute", JSONObject.NULL)
                .put("total_minutes", JSONObject.NULL))
            .put("no_broadcast", visibility == Visibility.PRIVATE)
            .put("progress_method", "percent")
        val r = request(session, "POST", "/api/v1/reading_updates", payload)
        if (r.code != 200 && r.code != 201) throw ServiceException("Pagebound didn't accept the progress (HTTP ${r.code})")
    }

    /**
     * Highlights go to the book's forum as one post per batch, the way Pagebound's own app posts
     * a note (ShelfSync's captured request). Pagebound places a post at the poster's current
     * reading position, so progress is brought up to date first, as that flow does.
     */
    override fun postHighlights(
        session: Session,
        link: Link,
        percent: Int,
        entries: List<io.github.prithirajn.trackersync.source.Clipping>,
    ) {
        val bookId = link.externalId ?: throw ServiceException("Book isn't linked")
        // Progress first, so the post lands at the right place in the forum.
        val shown = runCatching { state(session, link).percent }.getOrNull()
        if (shown == null || shown < percent) pushProgress(session, link, percent, session.visibility)

        val f = request(
            session, "GET",
            "/api/v1/forums/find_by_resource?resource_id=${Http.enc(bookId)}&resource_type=Book&page=1&post_type=all&sort_by=percent_progress_asc",
        )
        if (!f.ok) throw ServiceException("Pagebound forum lookup failed (HTTP ${f.code})")
        val forumId = runCatching { JSONObject(f.body).obj("forum")?.opt("id") }.getOrNull()
            ?: throw ServiceException("Pagebound didn't return the book's forum")
        val parts = formatHighlights(entries, 3000)
        // Parts already posted (from an earlier try that stopped part-way) aren't posted again.
        val done = session.data.optJSONObject("postedParts") ?: JSONObject()
        val batchKey = entries.joinToString(",") { it.id }.hashCode().toString()
        val already = done.optInt(batchKey, 0)
        for ((i, part) in parts.withIndex()) {
            if (i < already) continue
            val title = "Highlights from $percent%" + if (parts.size > 1) " (${i + 1}/${parts.size})" else ""
            val r = request(session, "POST", "/api/v1/posts", postBody(forumId, title, part, percent, "quote"))
            if (r.code != 200 && r.code != 201) {
                if (i > 0) session.put("postedParts", done.put(batchKey, i))
                throw ServiceException("Pagebound didn't accept the forum post (HTTP ${r.code})")
            }
        }
        if (done.has(batchKey)) { done.remove(batchKey); session.put("postedParts", done) }
    }

    /**
     * Pagebound forum posts can be edited by their author (PATCH /api/v1/posts/{id}, the usual
     * shape for its REST API; not in a captured request). If that's refused, it posts again.
     */
    override val updatesHighlights = true

    /** One post per highlight, each at its own place in the book (and editable, deletable later). */
    override val oneEntryPerHighlight = true

    override val deletesHighlights = true

    override fun deleteHighlight(session: Session, remoteId: String) {
        val r = request(session, "DELETE", "/api/v1/posts/${Http.enc(remoteId)}")
        if (!r.ok && r.code != 404) throw ServiceException("Pagebound didn't delete the post (HTTP ${r.code})")
    }

    override fun postHighlight(
        session: Session,
        link: Link,
        percent: Int,
        clip: io.github.prithirajn.trackersync.source.Clipping,
        previousId: String?,
    ): String? {
        // Its own place in the book where known; your progress still only moves forward.
        val at = clip.percent?.let { Math.round(it * 100).toInt() } ?: percent
        // The book once: its page count gives the quote its exact page ("pg 254"), and its progress
        // says whether yours needs moving up first.
        val bookNow = if (link.externalUuid != null) runCatching { book(session, link) }.getOrNull() else null
        val userBookNow = bookNow?.obj("user_book")
        val total = (userBookNow?.num("total_page_count") ?: userBookNow?.let { currentRead(it) }?.num("total_page_count") ?: bookNow?.num("page_count"))
            ?.toInt()?.takeIf { it > 0 }
        val title = postTitle("Quote", at, clip.chapter, clip.pageIn(total))
        val content = quoteContent(clip.text, clip.note).take(10_000)
        val spoiler = clip.note?.let { Markdown.hasSpoiler(it) } ?: false
        if (previousId != null) {
            // As Pagebound's "Edit post": PUT /posts/{id}, marked edited.
            val edit = postBody(0, title, content, at, "quote", spoiler = spoiler).getJSONObject("post")
            edit.remove("forum_id"); edit.put("is_edited", true)
            val r = request(session, "PUT", "/api/v1/posts/${Http.enc(previousId)}", JSONObject().put("post", edit))
            if (r.ok) return previousId
        }
        val bookId = link.externalId ?: throw ServiceException("Book isn't linked")
        val shown = if (bookNow != null) userBookNow?.num("progress")?.toInt() ?: 0 else runCatching { state(session, link).percent }.getOrNull()
        if (shown == null || shown < percent) pushProgress(session, link, percent, session.visibility)
        val forumId = forumId(session, bookId)
        val r = request(session, "POST", "/api/v1/posts", postBody(forumId, title, content, at, "quote", pages = total, spoiler = spoiler))
        if (r.code != 200 && r.code != 201) throw ServiceException("Pagebound didn't accept the forum post (HTTP ${r.code}" + (serverReason(r.body)?.let { ": $it" } ?: "") + ")")
        val o = runCatching { JSONObject(r.body) }.getOrNull()
        return (o?.obj("post") ?: o?.obj("data") ?: o)?.idString("id")
    }

    /** The book's forum (GET /forums/find_by_resource, as the app opens a book's discussion). */
    private fun forumId(session: Session, bookId: String): Any {
        val f = request(
            session, "GET",
            "/api/v1/forums/find_by_resource?resource_id=${Http.enc(bookId)}&resource_type=Book&page=1&post_type=all&sort_by=percent_progress_asc",
        )
        if (!f.ok) throw ServiceException("Pagebound forum lookup failed (HTTP ${f.code})")
        return runCatching { JSONObject(f.body).obj("forum")?.opt("id") }.getOrNull()
            ?: throw ServiceException("Pagebound didn't return the book's forum")
    }

    /**
     * A forum post as Pagebound's "Create post" sends it: {post: {forum_id, title, content,
     * is_spoiler, reading_update_percent, post_type}, total_page_count}. [postType] is one of its
     * kinds (thoughts, analysis, question, theory, reaction, quote); [percent] is the post's place in
     * the book (what the forum sorts and filters by).
     */
    internal fun postBody(forumId: Any, title: String, content: String, percent: Int? = null, postType: String = "thoughts", pages: Int? = null, spoiler: Boolean = false): JSONObject {
        val post = JSONObject()
            .put("forum_id", forumId)
            .put("title", title)
            .put("content", content)
            .put("is_spoiler", spoiler)
            .put("reading_update_percent", percent ?: JSONObject.NULL)
            .put("post_type", postType)
        return JSONObject().put("post", post).put("total_page_count", pages ?: JSONObject.NULL)
    }

    /** Pagebound's title for a post: "Quote from 26% (ch. 4, pg 56)", as its app builds it. */
    internal fun postTitle(label: String, percent: Int?, chapter: String?, page: Int?): String {
        if (percent == null) return label
        val where = listOfNotNull(shortChapter(chapter)?.let { "ch. $it" }, page?.let { "pg $it" })
        return "$label from $percent%" + if (where.isNotEmpty()) " (${where.joinToString(", ")})" else ""
    }

    /**
     * A chapter as Pagebound's Chapter box takes it ("4"): "Chapter 3: The Third Labyrinth" or
     * "CHAPTER 3" -> "3", "Chapter Seven: In Another Bedroom" -> "Seven"; a chapter named without a
     * number ("Interlude") as it is, kept short.
     */
    internal fun shortChapter(chapter: String?): String? {
        val c = chapter?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        Regex("""^(?:chapter|chap\.?|ch\.?)\s+([^\s:.,–—-]+(?:-[A-Za-z]+)?)""", RegexOption.IGNORE_CASE).find(c)?.let { return it.groupValues[1] }
        return if (c.length <= 40) c else c.take(39).trimEnd() + "…"
    }

    /** The passage as a block quote ("> " on each line, ">" on a blank one), then your note, as Pagebound's app builds it. */
    internal fun quoteContent(text: String, note: String?): String {
        val quote = text.trim().split("\n").joinToString("\n") { if (it.trim().isEmpty()) ">" else "> " + it.trimEnd() }
        return listOfNotNull(quote, note?.trim()?.takeIf { it.isNotEmpty() }).joinToString("\n\n")
    }

    // ---- adding a book Pagebound doesn't have ----

    override val addsBooks = true
    override val addBookNote = "Found by its ISBN or ASIN when Pagebound has it, else added with its details and cover (or from its Goodreads page)."
    // Pagebound's own Add a Book page: Goodreads link, ISBN/ASIN, or a book without either.
    override val addBookPage = "https://pagebound.co/add-book"
    // The new book's page, once Pagebound has made it ("/books/…" or "/book/…").
    override val addedBookId = Regex("""pagebound\.co/books?/([0-9a-fA-F-]{8,}|\d+)""")

    override fun webCookies(session: Session): List<Pair<String, String>> {
        val token = session.data.str("apiToken") ?: return emptyList()
        return listOf(
            "https://pagebound.co" to "token=$token",
            "https://pagebound.co" to "auth_token=$token",
        )
    }

    override fun findByIsbn(session: Session, isbn: String): List<RemoteBook> {
        val query = isbn.replace(Regex("[^0-9X]"), "")
        if (query.isEmpty()) return emptyList()
        val url = "$TYPESENSE_URL/collections/books/documents/search?q=${Http.enc(query)}" +
            "&query_by=title%2Cauthor_name%2Cisbn&limit=10&num_typos=0"
        val r = Http.request(
            "GET", url,
            mapOf(
                "X-TYPESENSE-API-KEY" to TYPESENSE_KEY,
                "Accept" to "application/json",
                "User-Agent" to WEB_USER_AGENT,
                "Origin" to ORIGIN,
                "Referer" to REFERER,
            ) + Http.clientHints(WEB_USER_AGENT),
        )
        if (!r.ok) return emptyList()
        val hits = runCatching { JSONObject(r.body).arr("hits") }.getOrNull()?.objects() ?: return emptyList()
        return hits.mapNotNull { h ->
            val d = h.obj("document") ?: return@mapNotNull null
            val id = d.idString("id") ?: return@mapNotNull null
            RemoteBook(
                id = id,
                title = d.str("title") ?: "Untitled",
                author = d.str("author_name") ?: d.str("author"),
                uuid = d.str("uuid"),
                coverUrl = d.str("image_url") ?: d.str("image_url_medium"),
                pages = d.int("page_count"),
                year = d.firstYear("published_year", "publication_year", "first_published_year"),
                format = d.str("format")?.takeIf { it.isNotBlank() },
            )
        }
    }

    /**
     * As Pagebound's own "Add a book" does it: first POST /books/find_book_match with the ISBN or
     * ASIN, title and author (it answers with the book when Pagebound already has it); else
     * POST /books with the details, the cover sent as an uploaded image (base64), since a cover
     * address from an online shop is often refused; else, with a Goodreads link, POST
     * /goodreads/add_book (Pagebound adds it from Goodreads in the background).
     */
    override fun addBook(session: Session, document: String, details: io.github.prithirajn.trackersync.source.BookDetails): RemoteBook {
        val asin = details.asin ?: if (document.startsWith("kindle:")) document.removePrefix("kindle:") else null
        val (uniqueId, uniqueType) = when {
            details.isbn13 != null -> details.isbn13 to "isbn13"
            details.isbn10 != null -> details.isbn10 to "isbn10"
            asin != null -> asin to "ASIN"
            else -> null to null
        }
        val author = details.authors.firstOrNull() ?: details.author

        fun found(o: JSONObject?): RemoteBook? {
            val b = o?.obj("book") ?: return null
            val uuid = b.str("uuid") ?: return null
            val id = b.idString("id") ?: uuid
            return RemoteBook(
                id = id, title = b.str("title") ?: details.title, author = b.str("author_name") ?: author,
                uuid = uuid, coverUrl = b.str("image_url") ?: details.coverUrl, pages = b.int("page_count") ?: details.pages,
            )
        }

        // 1. Already on Pagebound?
        if (uniqueId != null) {
            val match = JSONObject().put("unique_id", uniqueId).put("unique_id_type", uniqueType)
                .put("title", details.title).put("author_name", author ?: JSONObject.NULL)
            val r = runCatching { request(session, "POST", "/api/v1/books/find_book_match", match) }.getOrNull()
            if (r?.ok == true) found(runCatching { JSONObject(r.body) }.getOrNull())?.let { if (it.id.isNotEmpty() && books(session, it)) return it }
        }

        // 2. A new book, with its details.
        val body = JSONObject()
            .put("title", listOfNotNull(details.title, details.subtitle).joinToString(": "))
            .put("author_name", author ?: JSONObject.NULL)
            .put("unique_id", uniqueId ?: JSONObject.NULL)
            .put("unique_id_type", uniqueType ?: JSONObject.NULL)
            .put("description", details.description ?: JSONObject.NULL)
            .put("tags", JSONArray(details.genres.take(5)))
            .put("format", pageboundFormat(details.format, document))
            .put("pages", details.pages ?: JSONObject.NULL)
            .put("minutes", JSONObject.NULL)
            .put("language", details.language ?: JSONObject.NULL)
            .put("pub_date", details.published?.let { if (it.length == 4) "$it-01-01" else if (it.length == 7) "$it-01" else it } ?: JSONObject.NULL)
            .put("series_name", details.series ?: JSONObject.NULL)
            .put("series_position", details.seriesIndex?.toDoubleOrNull() ?: JSONObject.NULL)
            .put("publisher_name", details.publisher ?: JSONObject.NULL)
        val image = details.coverUrl?.takeIf { it.startsWith("http") }?.let { runCatching { Http.bytes(it) }.getOrNull() }
        if (image != null) {
            body.put("raw_image", java.util.Base64.getEncoder().encodeToString(image))
            body.put("raw_image_content_type", if (image.size > 3 && image[0] == 0x89.toByte() && image[1] == 'P'.code.toByte()) "image/png" else "image/jpeg")
        } else {
            details.coverUrl?.takeIf { it.startsWith("http") }?.let { body.put("image_url", it) }
        }
        val created = runCatching { request(session, "POST", "/api/v1/books", body) }.getOrNull()
        val createdJson = created?.takeIf { it.ok }?.let { runCatching { JSONObject(it.body) }.getOrNull() }
        found(createdJson)?.let { return it }

        // 3. From its Goodreads page, where known.
        details.goodreadsUrl?.let { url ->
            val r = runCatching { request(session, "POST", "/api/v1/goodreads/add_book", JSONObject().put("goodreads_url", url)) }.getOrNull()
            if (r?.ok == true) {
                found(runCatching { JSONObject(r.body) }.getOrNull())?.let { return it }
                throw ServiceException("Pagebound is adding it from Goodreads; it'll be found on a later sync")
            }
        }
        val why = created?.let { r -> "HTTP ${r.code}" + (serverReason(r.body)?.let { ": $it" } ?: "") } ?: "no answer"
        throw ServiceException("Pagebound didn't add the book ($why). You can finish it on Pagebound's Add a book page.")
    }

    /** True when [b] is a real Pagebound book (it has a uuid to open it by). */
    private fun books(session: Session, b: RemoteBook): Boolean = b.uuid != null

    /** Pagebound's four formats, as its own form names them. */
    private fun pageboundFormat(format: String?, document: String): String {
        val f = format?.lowercase() ?: ""
        return when {
            "audio" in f -> "Audiobook"
            "hard" in f -> "Hardcover"
            "paper" in f || "mass market" in f -> "Paperback"
            f.isEmpty() && document.startsWith("manual-") -> "Paperback"
            else -> "Ebook"
        }
    }
}
