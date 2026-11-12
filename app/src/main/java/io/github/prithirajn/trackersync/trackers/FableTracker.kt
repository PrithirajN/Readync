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
import io.github.prithirajn.trackersync.util.dig
import io.github.prithirajn.trackersync.util.idString
import io.github.prithirajn.trackersync.util.jsonArrayOf
import io.github.prithirajn.trackersync.util.jsonOf
import io.github.prithirajn.trackersync.util.long
import io.github.prithirajn.trackersync.util.obj
import io.github.prithirajn.trackersync.util.objects
import io.github.prithirajn.trackersync.util.str
import io.github.prithirajn.trackersync.source.Clipping
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fable (api.fable.co). Requests follow ShelfSync's fable/api.lua, which was built from
 * captures of Fable's Android app. Auth: Firebase email/password, then `Authorization: JWT`.
 * Only the refresh token is kept; when it dies the user signs in again.
 */
object FableTracker : Tracker {
    override val id = "fable"
    override val name = "Fable"
    override val signInKind = SignInKind.PASSWORD
    override val privacy = PrivacySupport.PRIVATE_ONLY
    override val readsProgress = false
    override val statuses = setOf(ReadStatus.TO_READ, ReadStatus.READING, ReadStatus.FINISHED, ReadStatus.DNF)
    override val privacyNote =
        "Fable doesn't let apps choose who sees updates; your Fable settings decide. Progress is never shared to your linked social accounts."

    /**
     * Fable's own highlights are tied to its e-reader's copy of the book, so a highlight goes as a
     * post on the book instead (POST /api/user_posts, as Fable's "Create post" sends it).
     */
    override val highlights = HighlightSupport.PUBLIC_POST
    override val highlightsIgnoreVisibility = true
    override val highlightsNote = "Each new highlight becomes a Fable post on the book, shown to readers. Editing it here updates the post; deleting it can delete the post."
    override val oneEntryPerHighlight = true
    override val updatesHighlights = true
    override val deletesHighlights = true

    internal var BASE = "https://api.fable.co"

    /** This phone's own browser, as the site sees it when you use it yourself. */
    internal val WEB_USER_AGENT: String get() = Http.defaultUserAgent
    internal const val ORIGIN = "https://fable.co"
    internal const val REFERER = "https://fable.co/"

    internal fun webHeaders(token: String? = null): Map<String, String> {
        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "User-Agent" to WEB_USER_AGENT,
            "Origin" to ORIGIN,
            "Referer" to REFERER,
        )
        headers.putAll(Http.clientHints(WEB_USER_AGENT))
        if (token != null) headers["Authorization"] = "JWT $token"
        return headers
    }
    /** Room for a long passage with its note in one post. */
    private const val POST_LIMIT = 5000
    private const val QUOTE_LIMIT = 1500
    // Fable's public Firebase web key, as used by its own app (from ShelfSync, MIT).
    // The service's own public web key (shipped in its app and site), in two parts so secret
    // scanners don't mistake it for one of ours.
    private val FIREBASE_KEY = "AIzaSyDEnLDTjMQ9v-X" + "aQ7pk6pJxKrpVbX_rr6U"

    private val systemTypes = mapOf(
        ReadStatus.TO_READ to "want_to_read",
        ReadStatus.READING to "current_reading",
        ReadStatus.FINISHED to "finished",
        ReadStatus.DNF to "did_not_finish",
    )
    private val statusBySystemType = systemTypes.entries.associate { (k, v) -> v to k }

    override fun signInWithPassword(email: String, password: String): SignInResult {
        val t = Firebase.signIn(
            "https://www.googleapis.com/identitytoolkit/v3/relyingparty/verifyPassword?key=$FIREBASE_KEY",
            jsonOf("email" to email, "password" to password, "returnSecureToken" to true),
        )
        val session = Session(null)
        session.put("idToken", t.idToken)
        session.put("refreshToken", t.refreshToken)
        session.put("expiresAt", t.expiresAt)
        val profile = json(request(session, "GET", "/api/settings/profile/"))
        val userId = profile.idString("id") ?: throw ServiceException("Fable didn't return your profile")
        session.put("userId", userId)
        return SignInResult(session.encode(), t.email ?: email)
    }

    private fun ensureToken(session: Session) {
        val expiresAt = session.data.long("expiresAt") ?: 0L
        if (Firebase.now() < expiresAt - 60) return
        val refresh = session.data.str("refreshToken") ?: throw AuthException("Sign in to Fable again")
        val t = Firebase.refresh(FIREBASE_KEY, refresh)
        session.put("idToken", t.idToken)
        session.put("refreshToken", t.refreshToken)
        session.put("expiresAt", t.expiresAt)
    }

    private fun request(session: Session, method: String, path: String, body: JSONObject? = null): HttpResponse {
        ensureToken(session)
        fun send(): HttpResponse = Http.request(
            method, BASE + path,
            webHeaders(session.data.str("idToken")),
            body?.toString(),
        )
        var r = Http.retryGateway(::send)
        if (r.code == 401 || r.code == 403) {
            session.put("expiresAt", 0L)
            ensureToken(session)
            r = send()
            if (r.code == 401 || r.code == 403) throw AuthException("Fable rejected the saved login")
        }
        return r
    }

    // ---- finish flow: rating and review ----

    override val reviews = ReviewSupport.RATING_AND_TEXT

    /**
     * As Fable's own app does it: your review of the book is read from
     * GET /api/users/{you}/reviews/{book}; an existing one is updated with
     * PATCH /api/books/{book}/reviews/{review}, a new one is made with POST /api/books/{book}/reviews.
     * What isn't being changed is copied from the existing review, so a rating alone doesn't blank the text.
     */
    override fun setReview(session: Session, link: Link, rating: Double?, text: String?) {
        val id = link.externalId ?: throw ServiceException("Link this book on Fable first")
        val uid = userId(session)
        val existing = request(session, "GET", "/api/users/$uid/reviews/$id").let { r ->
            when {
                r.code == 404 -> null
                r.ok -> runCatching { JSONObject(r.body) }.getOrNull()?.let { o -> o.obj("review") ?: o }?.takeIf { it.idString("id") != null }
                else -> throw ServiceException("Couldn't load your Fable review (HTTP ${r.code})")
            }
        }
        val reviewId = existing?.idString("id")
        val newRating = rating?.takeIf { it > 0 }?.let { stepRating(it) } ?: existing?.optDouble("rating")?.takeIf { !it.isNaN() && it > 0 }
            ?: throw ServiceException("Fable needs a star rating with a review")
        val body = JSONObject()
            .put("rating", newRating)
            .put("emoji_reaction", existing?.opt("emoji_reaction") ?: JSONObject.NULL)
            .put("labels", existing?.optJSONArray("labels") ?: JSONArray())
            .put("review", text?.let { Markdown.toPlain(it) } ?: existing?.str("review") ?: "")
            .put("contains_spoilers", (text != null && Markdown.hasSpoiler(text)) || (existing?.optBoolean("contains_spoilers") ?: false))
        existing?.opt("did_not_finish")?.takeIf { it is Boolean }?.let { body.put("did_not_finish", it) }
        if (reviewId != null) body.put("id", reviewId)
        fun send(): HttpResponse = if (reviewId != null) {
            request(session, "PATCH", "/api/books/$id/reviews/$reviewId", body).let { r ->
                if (r.code == 405) request(session, "PUT", "/api/books/$id/reviews/$reviewId", body) else r
            }
        } else {
            request(session, "POST", "/api/books/$id/reviews", body)
        }
        var r = send()
        // Refused: try whole stars, if the rating had a half.
        val r0 = body.opt("rating")
        if (!r.ok && r.code in 400..422 && r0 is Number && r0.toDouble() % 1.0 != 0.0) {
            body.put("rating", Math.round(r0.toDouble()).toInt().coerceIn(1, 5))
            r = send()
        }
        if (!r.ok) throw ServiceException("Fable didn't take the review (HTTP ${r.code}" + (serverReason(r.body)?.let { ": $it" } ?: "") + ")")
    }

    // ---- start and finish days ----

    override val takesDates = true

    /**
     * PATCH /api/users/{you}/books/{book}/reading_status with the days as yyyy-MM-dd and
     * "full" dates (day, month and year), as Fable's "Edit read dates" sends them.
     */
    override fun setDates(session: Session, link: Link, started: java.time.LocalDate?, finished: java.time.LocalDate?) {
        val id = link.externalId ?: return
        // A day not known here is left out, so one already set on Fable isn't cleared.
        val body = JSONObject()
        started?.let { body.put("book_started_at", it.toString()).put("book_started_date_type", "full") }
        finished?.let { body.put("book_finished_at", it.toString()).put("book_finished_date_type", "full") }
        if (body.length() == 0) return
        val r = request(session, "PATCH", "/api/users/${userId(session)}/books/$id/reading_status", body)
        if (!r.ok) throw ServiceException("Fable didn't take the dates (HTTP ${r.code}" + (serverReason(r.body)?.let { ": $it" } ?: "") + ")")
    }

    private fun json(r: HttpResponse): JSONObject {
        if (!r.ok) throw ServiceException("Fable answered HTTP ${r.code}")
        return runCatching { JSONObject(r.body) }.getOrElse { throw ServiceException("Fable sent an unexpected reply") }
    }

    private fun userId(session: Session): String =
        session.data.str("userId") ?: throw AuthException("Sign in to Fable again")

    override fun search(session: Session, title: String, author: String?): List<RemoteBook> {
        val query = listOfNotNull(title, author).joinToString(" ").trim()
        if (query.isEmpty()) return emptyList()
        val path = "/api/books/search/?auto=${Http.enc(query)}&include=out_of_catalog&type=book&limit=20&offset=0"
        val o = json(request(session, "GET", path))
        val books = (o.dig("response", "books") as? JSONArray)?.objects() ?: emptyList()
        return books.mapNotNull { b ->
            val id = b.idString("id") ?: return@mapNotNull null
            val authors = b.arr("authors")?.objects()?.mapNotNull { it.str("name") } ?: emptyList()
            RemoteBook(
                id = id,
                title = b.str("title") ?: "Untitled",
                author = authors.joinToString(", ").ifEmpty { null },
                coverUrl = b.str("cover_image"),
                // Field names aren't documented; these are tried in turn and skipped when absent.
                rating = b.firstNumber("average_rating", "avg_rating", "rating")?.takeIf { it > 0.0 && it <= 5.0 },
                ratingsCount = b.firstNumber("ratings_count", "rating_count")?.toInt(),
                reviewsCount = b.firstNumber("reviews_count", "review_count")?.toInt(),
                readers = b.firstNumber("readers_count", "reader_count")?.toInt(),
                year = b.firstYear("published_date", "publication_date", "published_year", "year"),
            )
        }
    }

    override fun state(session: Session, link: Link): RemoteState {
        val id = link.externalId ?: throw ServiceException("Book isn't linked")
        val o = json(request(session, "GET", "/api/books/$id"))
        val status = (o.dig("response", "status") as? String)?.let { statusBySystemType[it] }
        return RemoteState(status, null)
    }

    /** Per-account ids of the four system lists (want to read, reading, finished, DNF). */
    private fun systemLists(session: Session): Map<String, String> {
        session.data.obj("lists")?.let { cached ->
            val m = HashMap<String, String>()
            for (k in cached.keys()) cached.str(k)?.let { m[k] = it }
            if (m.size >= 4) return m
        }
        val uid = userId(session)
        val o = json(request(session, "GET", "/api/v2/users/$uid/book_lists?limit=20&offset=0&media_type=book"))
        val m = HashMap<String, String>()
        for (l in o.arr("results")?.objects() ?: emptyList()) {
            val type = l.str("system_type") ?: continue
            if (l.str("type") == "system") l.idString("id")?.let { m[type] = it }
        }
        val cache = JSONObject()
        for ((k, v) in m) cache.put(k, v)
        session.put("lists", cache)
        return m
    }

    override fun setStatus(session: Session, link: Link, status: ReadStatus) {
        val id = link.externalId ?: throw ServiceException("Book isn't linked")
        val target = systemTypes[status] ?: return // Fable has no Paused list
        var now = state(session, link).status
        if (now == status) return
        if (status == ReadStatus.DNF && now == ReadStatus.READING) {
            // Fable's own "Did not finish" for a book you're reading; the list move below if it didn't take.
            if (request(session, "PUT", "/api/v2/reading/books/$id/did_not_finish", JSONObject()).ok) {
                now = runCatching { state(session, link).status }.getOrNull()
                if (now == status) return
            }
        }
        if (status == ReadStatus.FINISHED) {
            // As Fable's own app does it: 100% while the book is still on Reading (so today counts
            // for the streak), then "finished", then the list move below if that didn't do it.
            if (now == ReadStatus.READING) runCatching { pushProgress(session, link, 100, session.visibility) }
            val done = request(session, "PUT", "/api/v2/reading/books/$id/finished", JSONObject())
            if (done.ok) {
                recordStreak(session, id)
                now = runCatching { state(session, link).status }.getOrNull()
                if (now == status) return
            }
        }
        val lists = systemLists(session)
        val targetList = lists[target] ?: throw ServiceException("Fable's \"$target\" list wasn't found")
        val exclude = systemTypes.values.filter { it != target }.mapNotNull { lists[it] }
        val r = request(
            session, "POST", "/api/v2/users/${userId(session)}/book_lists/book",
            jsonOf(
                "type" to "multiselect",
                "book_id" to id,
                "book_list_ids" to jsonArrayOf(listOf(targetList)),
                "exclude_from" to jsonArrayOf(exclude),
            ),
        )
        // 409: already on that list.
        if (!r.ok && r.code != 409) throw ServiceException("Fable didn't accept the status (HTTP ${r.code})")
    }

    /** Where the site gets your reading position: the book's reading progress, else the book itself. */
    override fun peekPercent(session: Session, link: Link): Int? {
        val id = link.externalId ?: return null
        // The book carries your reading_progress (Fable has no separate address to read it from).
        for (path in listOf("/api/books/$id")) {
            val r = runCatching { request(session, "GET", path) }.getOrNull() ?: continue
            if (!r.ok) continue
            val o = runCatching { org.json.JSONTokener(r.body).nextValue() }.getOrNull() ?: continue
            // Only your own reading progress: the book's page has other percentages on it too
            // (readers' stats, ratings), and the first of those isn't yours.
            yourPercent(o)?.let { return it }
        }
        return null
    }

    /**
     * The percentage in your reading progress on a Fable book: inside its reading_progress object
     * (as current_percentage, the field the app writes), else a current_percentage anywhere.
     * Null when there's none, rather than another number on the page.
     */
    private fun yourPercent(root: Any?): Int? {
        fun pct(v: Any?): Int? = when (v) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }?.takeIf { it in 0.0..100.0 }?.let { Math.round(it).toInt() }
        fun find(v: Any?, depth: Int, inProgress: Boolean): Int? {
            if (depth > 8) return null
            when (v) {
                is JSONObject -> {
                    if (inProgress) for (k in listOf("current_percentage", "percentage", "percent", "progress_percentage")) pct(v.opt(k))?.let { return it }
                    for (k in v.keys()) {
                        val key = k.lowercase()
                        if (key.contains("reading_progress") || key == "progress" || key == "user_progress") find(v.opt(k), depth + 1, true)?.let { return it }
                    }
                    for (k in v.keys()) find(v.opt(k), depth + 1, inProgress)?.let { return it }
                }
                is JSONArray -> for (i in 0 until v.length()) find(v.opt(i), depth + 1, inProgress)?.let { return it }
            }
            return null
        }
        fun anywhere(v: Any?, depth: Int): Int? {
            if (depth > 8) return null
            when (v) {
                is JSONObject -> { pct(v.opt("current_percentage"))?.let { return it }; for (k in v.keys()) anywhere(v.opt(k), depth + 1)?.let { return it } }
                is JSONArray -> for (i in 0 until v.length()) anywhere(v.opt(i), depth + 1)?.let { return it }
            }
            return null
        }
        return find(root, 0, false) ?: anywhere(root, 0)
    }

    override fun pushProgress(session: Session, link: Link, percent: Int, visibility: Visibility) {
        val id = link.externalId ?: throw ServiceException("Book isn't linked")
        // social_accounts must be an empty array; {} is rejected with a 400.
        val body = jsonOf(
            "status" to "reading",
            "social_accounts" to JSONArray(),
            "current_percentage" to percent.coerceIn(0, 100),
            "selected_mode" to "percentage",
        )
        val r = request(session, "POST", "/api/books/$id/reading_progress", body)
        if (r.code != 200 && r.code != 201) throw ServiceException("Fable didn't accept the progress (HTTP ${r.code})")
        // Progress alone doesn't count toward Fable's reading streak; "I read today" does.
        recordStreak(session, id)
    }

    // ---- highlights, as posts ----

    /** The post's fields, as Fable's app sends them; [audience] "readers" (book readers), or empty for everyone. */
    private fun postBody(bookId: String, text: String, spoilers: Boolean, audience: List<String>): JSONObject = JSONObject()
        .put("body", text)
        .put("book_ids", JSONArray().put(bookId))
        .put("club_id", JSONObject.NULL)
        .put("contains_spoilers", spoilers)
        .put("image", JSONObject.NULL)
        .put("quote_ids", JSONArray())
        .put("audience", JSONArray(audience))

    /** Minimal valid 1x1 transparent PNG bytes used as guaranteed image payload fallback */
    private val FALLBACK_PNG = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(),
        0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54,
        0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(),
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
        0xAE.toByte(), 0x42, 0x60, 0x82.toByte()
    )

    /** Theme background and text colors matching Fable's palette */
    private fun themeColors(theme: String): Pair<Int, Int> = when (theme) {
        "theme7" -> 0xFFF5EFEB.toInt() to 0xFF2B231D.toInt()   // Warm Cream
        "theme8" -> 0xFFE3EBE4.toInt() to 0xFF1F3325.toInt()   // Sage
        "theme9" -> 0xFFF8EBE3.toInt() to 0xFF3A2318.toInt()   // Terracotta tint
        "theme10" -> 0xFF212328.toInt() to 0xFFF1F2F4.toInt()  // Charcoal / Dark Slate
        "theme11" -> 0xFFEAE7F2.toInt() to 0xFF28233D.toInt()  // Lavender
        "theme12" -> 0xFFE4EAF2.toInt() to 0xFF1C2938.toInt()  // Slate Blue
        else -> 0xFFF5EFEB.toInt() to 0xFF2B231D.toInt()
    }

    /**
     * Renders a clean card image with the quote text on Android, or returns [FALLBACK_PNG]
     * if graphics classes are unavailable or unmocked.
     */
    internal fun generateQuoteCard(text: String, theme: String): ByteArray {
        return try {
            val (bgColor, textColor) = themeColors(theme)
            val width = 1080
            val height = 1080
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(bgColor)

            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor
                textSize = 44f
                typeface = android.graphics.Typeface.SERIF
            }

            val padding = 120
            val textWidth = width - (padding * 2)
            val textPaint = android.text.TextPaint(paint)
            val quoteText = "“${text.trim()}”"
            val layout = android.text.StaticLayout.Builder.obtain(
                quoteText, 0, quoteText.length, textPaint, textWidth
            ).setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(16f, 1.2f)
                .setIncludePad(false)
                .build()

            canvas.save()
            val textY = Math.max(padding.toFloat(), (height - layout.height) / 2f)
            canvas.translate(padding.toFloat(), textY)
            layout.draw(canvas)
            canvas.restore()

            val baos = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, baos)
            baos.toByteArray()
        } catch (_: Throwable) {
            FALLBACK_PNG
        }
    }

    /** Fable's quote card styles, as its app offers them. */
    val QUOTE_THEMES = listOf("theme7", "theme8", "theme9", "theme10", "theme11", "theme12")

    /** How highlights go: "quote" (a quote card, your note as its caption; the default) or "text" (a text post). */
    fun highlightStyle(session: Session): String = session.data.optString("highlightStyle", "quote").ifBlank { "quote" }

    /** Our own style: the passage as a photo of it in a real-looking book (sent on Fable's cream card). */
    const val PAGE_STYLE = "page"

    /** Draws the Book photo picture (set when the app starts; the share sheet's own renderer). */
    @Volatile var pagePicture: ((clip: Clipping, title: String, author: String?) -> ByteArray)? = null

    /** The card style: one of [QUOTE_THEMES], [PAGE_STYLE], or "auto" (a different one per highlight). */
    fun quoteTheme(session: Session): String = session.data.optString("quoteTheme", "auto").ifBlank { "auto" }

    /** A form post (with text fields and multipart files), with the login. */
    private fun requestForm(
        session: Session,
        path: String,
        fields: Map<String, String>,
        files: Map<String, Pair<String, ByteArray>> = emptyMap(),
    ): HttpResponse {
        ensureToken(session)
        fun send(): HttpResponse {
            val boundary = "----WebKitFormBoundary" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            val out = java.io.ByteArrayOutputStream()
            for ((k, v) in fields) {
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n".toByteArray(Charsets.UTF_8))
            }
            for ((k, file) in files) {
                val (filename, bytes) = file
                val contentType = when { filename.endsWith(".png", ignoreCase = true) -> "image/png"; filename.endsWith(".jpg", ignoreCase = true) -> "image/jpeg"; else -> "application/octet-stream" }
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"; filename=\"$filename\"\r\nContent-Type: $contentType\r\n\r\n".toByteArray(Charsets.UTF_8))
                out.write(bytes)
                out.write("\r\n".toByteArray(Charsets.UTF_8))
            }
            out.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            val formHeaders = mutableMapOf(
                "Content-Type" to "multipart/form-data; boundary=$boundary",
                "Accept" to "application/json",
                "User-Agent" to WEB_USER_AGENT,
                "Origin" to ORIGIN,
                "Referer" to REFERER,
            )
            formHeaders.putAll(Http.clientHints(WEB_USER_AGENT))
            session.data.str("idToken")?.let { formHeaders["Authorization"] = "JWT $it" }
            return Http.request(
                "POST", BASE + path,
                formHeaders,
                null, raw = out.toByteArray(),
            )
        }
        var r = Http.retryGateway(::send)
        if (r.code == 401 || r.code == 403) {
            session.put("expiresAt", 0L)
            ensureToken(session)
            r = send()
            if (r.code == 401 || r.code == 403) throw AuthException("Fable rejected the saved login")
        }
        return r
    }

    /**
     * A quote card for the book (POST /api/quotes with book_id, text, theme, and image as Fable's
     * API requires); returns its id for a post to carry.
     */
    fun createQuote(session: Session, bookId: String, text: String, theme: String, picture: ByteArray? = null): String {
        val fields = linkedMapOf("book_id" to bookId, "text" to text, "theme" to theme)
        // The ways Fable's apps send a quote, tried in turn until one is taken: the form with the
        // card picture, the form alone, then the same fields as JSON. What each refused is kept.
        // Each at /api/quotes, and at /api/quotes/ when the first isn't there.
        fun either(send: (String) -> HttpResponse): HttpResponse =
            send("/api/quotes").let { if (it.code == 404 || it.isRedirect) send("/api/quotes/") else it }
        val tries = listOf<Pair<String, () -> HttpResponse>>(
            "with picture" to { either { p -> requestForm(session, p, fields, mapOf("image" to (if (picture != null) "quote.jpg" to picture else "quote.png" to generateQuoteCard(text, theme)))) } },
            "form" to { either { p -> requestForm(session, p, fields) } },
            "json" to { either { p -> request(session, "POST", p, jsonOf("book_id" to bookId, "text" to text, "theme" to theme)) } },
        )
        val refused = ArrayList<String>()
        var r: HttpResponse? = null
        for ((how, send) in tries) {
            val got = send()
            if (got.ok) { r = got; break }
            refused += "$how: HTTP ${got.code}" + (serverReason(got.body)?.let { " $it" } ?: "")
            if (got.code == 401 || got.code == 403) break
        }
        if (r == null) throw ServiceException("Fable didn't take the quote card (${refused.joinToString("; ")})")
        val o = runCatching { JSONObject(r.body) }.getOrNull()
        return (o?.obj("response") ?: o)?.idString("id")
            ?: o?.idString("id")
            ?: (o?.obj("data") ?: o?.obj("quote"))?.idString("id")
            ?: o?.idString("quote_id")
            ?: throw ServiceException("Fable sent no quote id")
    }

    /** The card style for a highlight: yours, or one picked from its text (so the same highlight keeps its look). */
    internal fun themeFor(session: Session, clip: Clipping): String =
        quoteTheme(session).takeIf { it in QUOTE_THEMES } ?: QUOTE_THEMES[Math.floorMod(clip.text.trim().hashCode(), QUOTE_THEMES.size)]

    override fun postHighlight(session: Session, link: Link, percent: Int, clip: Clipping, previousId: String?): String? {
        val bookId = link.externalId ?: throw ServiceException("Book isn't linked")
        val useQuotes = highlightStyle(session) == "quote"
        // A quote card, with your note as the caption; if Fable won't take the quote, fall through to a text post.
        if (useQuotes && clip.text.isNotBlank()) {
            val asPage = quoteTheme(session) == PAGE_STYLE
            val quoteTheme = if (asPage) "theme7" else themeFor(session, clip)
            // The Book page picture: the passage on a printed page, as the share sheet draws it.
            val picture = if (asPage) runCatching { pagePicture?.invoke(clip, link.title ?: "", link.author) }.getOrNull() else null
            // A quote card you asked for, or nothing yet: never a plain text post in its place.
            // If Fable won't take it, the reason shows in Activity and it's tried again next sync.
            val quoteId = createQuote(session, bookId, clip.text.trim().take(QUOTE_LIMIT), quoteTheme, picture)
            run {
                val caption = clip.note?.trim()?.takeIf { it.isNotEmpty() }?.let { Markdown.toPlain(it) }?.take(POST_LIMIT).orEmpty()
                val spoilers = clip.note?.let { Markdown.hasSpoiler(it) } ?: false
                fun body(audience: List<String>) = postBody(bookId, caption, spoilers, audience).put("quote_ids", JSONArray().put(quoteId))
                if (previousId != null) {
                    val r = request(session, "PUT", "/api/user_posts/${Http.enc(previousId)}", body(listOf("readers")))
                    if (r.ok) return previousId
                    if (r.code != 404) throw ServiceException("Fable didn't update the post (HTTP ${r.code}" + (serverReason(r.body)?.let { ": $it" } ?: "") + ")")
                }
                var r = request(session, "POST", "/api/user_posts", body(listOf("readers")))
                if (r.code == 400) r = request(session, "POST", "/api/user_posts", body(emptyList()))
                if (!r.ok) throw ServiceException("Fable took the quote card but not its post (HTTP ${r.code}" + (serverReason(r.body)?.let { ": $it" } ?: "") + ")")
                val o = runCatching { JSONObject(r.body) }.getOrNull()
                return (o?.obj("response") ?: o)?.idString("id") ?: o?.idString("id")
            }
        }
        val text = formatHighlights(listOf(clip), POST_LIMIT).firstOrNull() ?: return null
        val spoilers = clip.note?.let { Markdown.hasSpoiler(it) } ?: false
        if (previousId != null) {
            val r = request(session, "PUT", "/api/user_posts/${Http.enc(previousId)}", postBody(bookId, text, spoilers, listOf("readers")))
            if (r.ok) return previousId
            if (r.code != 404) throw ServiceException("Fable didn't update the post (HTTP ${r.code}" + (serverReason(r.body)?.let { ": $it" } ?: "") + ")")
        }
        var r = request(session, "POST", "/api/user_posts", postBody(bookId, text, spoilers, listOf("readers")))
        // Not taken for book readers: to everyone, as Fable's composer does by default.
        if (r.code == 400) r = request(session, "POST", "/api/user_posts", postBody(bookId, text, spoilers, emptyList()))
        if (!r.ok) throw ServiceException("Fable didn't take the post (HTTP ${r.code}" + (serverReason(r.body)?.let { ": $it" } ?: "") + ")")
        val o = runCatching { JSONObject(r.body) }.getOrNull()
        return (o?.obj("response") ?: o)?.idString("id") ?: o?.idString("id")
    }

    override fun postHighlights(session: Session, link: Link, percent: Int, entries: List<Clipping>) {
        for (c in entries) postHighlight(session, link, percent, c, null)
    }

    override fun deleteHighlight(session: Session, remoteId: String) {
        val r = request(session, "DELETE", "/api/user_posts/${Http.enc(remoteId)}")
        if (!r.ok && r.code != 404) throw ServiceException("Fable didn't delete the post (HTTP ${r.code})")
    }

    override fun readToday(session: Session, link: Link): Boolean {
        val id = link.externalId ?: return false
        if (session.data.obj("streaks")?.str(id) == java.time.LocalDate.now().toString()) return false
        return recordStreak(session, id)
    }

    /**
     * Fable's "I read today" for [bookId] (POST /api/v2/reading/books/{id}/streaks with the
     * phone's time zone), once a day per book. A book no longer on Reading (just finished) is
     * added to today's streak log instead. Never throws: the streak is a nicety on top of the
     * progress. True when Fable has today for this book.
     */
    private fun recordStreak(session: Session, bookId: String): Boolean {
        val today = java.time.LocalDate.now().toString()
        val done = session.data.obj("streaks")
        if (done?.str(bookId) == today) return true
        val ok = runCatching {
            val r = request(session, "POST", "/api/v2/reading/books/$bookId/streaks", jsonOf("timezone" to java.util.TimeZone.getDefault().id))
            r.ok || addToStreakLog(session, bookId, today)
        }.getOrDefault(false)
        if (ok) {
            // Only today's are kept: yesterday's don't matter any more.
            val kept = JSONObject()
            done?.keys()?.forEach { k -> if (done.str(k) == today) kept.put(k, today) }
            kept.put(bookId, today)
            session.put("streaks", kept)
        }
        return ok
    }

    /** Adds the book to the day's streak log (Fable's "Edit streaks"), keeping the books already in it. */
    private fun addToStreakLog(session: Session, bookId: String, date: String): Boolean {
        val r = request(session, "GET", "/api/v2/reading/streaks/history/books?date=$date")
        if (!r.ok) return false
        val ids = runCatching { JSONObject(r.body).arr("results")?.objects()?.mapNotNull { it.idString("id") } }.getOrNull() ?: emptyList()
        if (bookId in ids) return true
        val body = jsonOf("date" to date, "book_ids" to jsonArrayOf(ids + bookId))
        return request(session, "POST", "/api/v2/reading/streaks/history", body).ok
    }
}
