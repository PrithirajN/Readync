package io.github.prithirajn.readync.trackers

import io.github.prithirajn.readync.util.Markdown
import io.github.prithirajn.readync.data.Link
import io.github.prithirajn.readync.data.ReadStatus
import io.github.prithirajn.readync.data.Visibility
import io.github.prithirajn.readync.net.AuthException
import io.github.prithirajn.readync.net.CookieJar
import io.github.prithirajn.readync.net.Http
import io.github.prithirajn.readync.net.HttpResponse
import io.github.prithirajn.readync.net.ServiceException
import io.github.prithirajn.readync.util.Html
import io.github.prithirajn.readync.util.obj
import io.github.prithirajn.readync.util.str
import org.json.JSONArray
import org.json.JSONObject

/**
 * Goodreads, driven like its website with the cookie bundle from an in-app sign-in.
 * There's no way to read progress back, and sessions go stale when Goodreads' AWS bot
 * check fires; both are handled by asking the user to sign in again. Follows ShelfSync's
 * goodreads/api.lua.
 */
object GoodreadsTracker : Tracker {
    override val id = "goodreads"
    override val name = "Goodreads"
    override val ratingStep = 0.25
    override val signInKind = SignInKind.WEBVIEW
    override val privacy = PrivacySupport.SKIP_PROGRESS
    override val readsProgress = false
    override val readsLibrary = true
    override val statuses = ReadStatus.entries.toSet()
    override val signInUrl = "https://www.goodreads.com/user/sign_in"
    override val cookieUrl = "https://www.goodreads.com"
    override val sessionCookie = "_session_id2"
    override val privacyNote =
        "Only me sends shelf changes but no progress updates. Goodreads may still show " +
            "Currently Reading and Read to your friends."

    /** The site (changed only by tests, to a stand-in server). */
    internal var BASE = "https://www.goodreads.com"

    override val highlights = HighlightSupport.PUBLIC_POST
    override val highlightsIgnoreVisibility = true
    override val highlightsNote = "Posted as a progress update on Goodreads."

    private fun jar(session: Session) = CookieJar.parse(session.data.str("cookies") ?: "")

    /** This phone's own browser, as the site sees it when you use it yourself. */
    internal val WEB_USER_AGENT: String get() = Http.defaultUserAgent

    // Goodreads loops on redirects for requests that don't look like a browser navigation.
    private fun headers(session: Session, jar: CookieJar, extra: Map<String, String>): Map<String, String> {
        val h = linkedMapOf(
            "User-Agent" to (session.data.str("userAgent")?.takeIf { it.isNotBlank() } ?: WEB_USER_AGENT),
            "Cookie" to jar.header(),
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to "$BASE/",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-User" to "?1",
            "Upgrade-Insecure-Requests" to "1",
        )
        // The browser's own hints, matching its user agent (as the site sees on every visit).
        h.putAll(Http.clientHints(h.getValue("User-Agent")))
        h.putAll(extra)
        return h
    }

    private fun isSignIn(url: String) = url.lowercase().contains("/user/sign_in") || url.contains("/ap/signin")

    private fun request(
        session: Session,
        method: String,
        path: String,
        body: String? = null,
        extra: Map<String, String> = emptyMap(),
        raw: ByteArray? = null,
    ): HttpResponse {
        val jar = jar(session)
        var url = if (path.startsWith("http")) path else BASE + path
        var m = method
        var b = body
        var rb = raw
        repeat(6) {
            val r = Http.retryGateway { Http.request(m, url, headers(session, jar, extra), b, followRedirects = false, raw = rb) }
            if (jar.absorb(r)) session.put("cookies", jar.header())
            if (r.header("x-amzn-waf-action") != null || r.code == 202 && r.body.contains("awswaf", true)) {
                throw AuthException("Goodreads' bot check blocked the saved session; sign in again")
            }
            val location = r.header("location")
            if (r.isRedirect && location != null) {
                val next = if (location.startsWith("http")) location else BASE + location
                if (isSignIn(next)) throw AuthException("Goodreads signed you out; sign in again")
                if (!next.startsWith(BASE)) return r
                if (m != "GET") return r
                url = next; m = "GET"; b = null; rb = null
                return@repeat
            }
            if (r.code == 401) throw AuthException("Goodreads signed you out; sign in again")
            return HttpResponse(r.code, r.headers, r.body, url)
        }
        throw ServiceException("Goodreads redirected too many times")
    }

    // ---- finish flow: rating, review, finish date ----

    override val reviews = ReviewSupport.RATING_AND_TEXT
    override val takesDates = true

    /** The review editor's action id, kept while the page's scripts stay the same. */
    @Volatile private var actionCache: Pair<String, String>? = null

    override fun setReview(session: Session, link: Link, rating: Double?, text: String?) {
        val id = link.externalId ?: throw ServiceException("Link this book on Goodreads first")
        if (rating != null && rating > 0) {
            // Quarter stars, 0.25–5, through the rating endpoint the shelves still use.
            val stars = stepRating(rating)
            val token = csrf(session)
            val r = request(
                session, "POST",
                "/review/rate/$id?no_lightbox=true&queue=false&stars_click=true&rating=${stars.toString().removeSuffix(".0")}&ref=undefined",
                "",
                mapOf(
                    "X-CSRF-Token" to token, "X-Requested-With" to "XMLHttpRequest", "Accept" to "*/*",
                    "Referer" to "$BASE/review/edit/$id", "Origin" to BASE,
                    "Sec-Fetch-Mode" to "cors", "Sec-Fetch-Dest" to "empty",
                ),
            )
            if (r.code !in 200..399) throw ServiceException("Goodreads didn't take the rating (HTTP ${r.code})")
        }
        if (text != null) {
            val (editUrl, html, state) = editor(session, id)
            submit(session, id, editUrl, html, GoodreadsEditor.body(state, Markdown.toGoodreadsHtml(text), state.sessions, "${"$"}0:0:readingSessions"), needsReview = true)
        }
    }

    override fun setDates(session: Session, link: Link, started: java.time.LocalDate?, finished: java.time.LocalDate?) {
        val id = link.externalId ?: throw ServiceException("Link this book on Goodreads first")
        val day = finished ?: return // Goodreads' "date read" is the finish; a start rides along with it.
        val (editUrl, html, state) = editor(session, id)
        val sessions = GoodreadsEditor.finishedSessions(state, day, started, System.currentTimeMillis()) ?: return
        submit(session, id, editUrl, html, GoodreadsEditor.body(state, state.reviewText, sessions, state.sessions), needsReview = false)
        val check = editor(session, id).third
        if (!GoodreadsEditor.hasDay(check, day)) throw ServiceException("Goodreads didn't keep the finish date")
    }

    private fun editor(session: Session, id: String): Triple<String, String, GoodreadsEditor.State> {
        csrf(session) // refreshes the session cookies first
        val editUrl = "$BASE/review/edit/$id"
        val r = request(session, "GET", editUrl)
        if (!r.ok) throw ServiceException("Goodreads' review editor didn't open (HTTP ${r.code})")
        val state = GoodreadsEditor.state(r.body) ?: throw ServiceException("Couldn't read Goodreads' review editor")
        return Triple(editUrl, r.body, state)
    }

    private fun actionId(session: Session, html: String, referer: String): String {
        val paths = GoodreadsEditor.chunkPaths(html)
        val key = paths.joinToString("\n")
        actionCache?.let { (k, v) -> if (k == key) return v }
        for (path in paths) {
            val r = request(
                session, "GET", BASE + path, null,
                mapOf("Accept" to "*/*", "Referer" to referer, "Sec-Fetch-Mode" to "no-cors", "Sec-Fetch-Dest" to "script"),
            )
            if (r.code == 202) break // a bot check: every asset would fail the same way
            if (!r.ok) continue
            GoodreadsEditor.actionId(r.body)?.let { id ->
                actionCache = key to id
                return id
            }
        }
        throw ServiceException("Couldn't find Goodreads' current review-save action")
    }

    private fun submit(session: Session, id: String, editUrl: String, html: String, body: String, needsReview: Boolean) {
        val action = actionId(session, html, editUrl)
        val r = request(
            session, "POST", editUrl, body,
            mapOf(
                "Accept" to "text/x-component",
                "Content-Type" to "text/plain;charset=UTF-8",
                "Next-Action" to action,
                "Next-Router-State-Tree" to Http.enc(GoodreadsEditor.routerTree(id)),
                "Referer" to editUrl,
                "Origin" to BASE,
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Dest" to "empty",
            ),
        )
        val saved = r.code == 200 && GoodreadsEditor.noErrors(r.body) &&
            (!needsReview || Regex("\"legacyId\":\"\\d+\"").containsMatchIn(r.body))
        if (!saved) throw ServiceException("Goodreads didn't confirm saving (HTTP ${r.code})")
    }

    /** Loads the home page for a fresh CSRF token; also proves the session works. */
    private fun csrf(session: Session): String {
        val r = request(session, "GET", "/")
        val token = if (r.ok) Html.csrf(r.body) else null
        if (token == null || !r.body.contains("/user/show/")) {
            throw AuthException("Goodreads didn't show a signed-in page; sign in again")
        }
        return token
    }

    override fun library(session: Session): List<LibraryBook> {
        val uid = session.data.str("userId") ?: run {
            val home = request(session, "GET", "/")
            val m = Regex("/user/show/(\\d+)").find(home.body) ?: return emptyList()
            session.put("userId", m.groupValues[1])
            m.groupValues[1]
        }
        val r = request(session, "GET", "/review/list/$uid?shelf=currently-reading")
        if (!r.ok) return emptyList()
        val out = ArrayList<LibraryBook>()
        val rowRegex = Regex("<tr[^>]*class=\"[^\"]*bookalike[^\"]*review[^\"]*\"[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL)
        for (m in rowRegex.findAll(r.body)) {
            val chunk = m.groupValues[1]
            val bookId = Regex("/book/show/(\\d+)").find(chunk)?.groupValues?.get(1) ?: continue
            val title = Regex("class=\"field title\"[^>]*>.*?<a[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(chunk)?.let { Html.text(it.groupValues[1]) }
                ?: continue
            val author = Regex("class=\"field author\"[^>]*>.*?<a[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(chunk)?.let { Html.text(it.groupValues[1]) }
            val cover = Regex("<img[^>]*src=\"([^\"]+)\"").find(chunk)?.groupValues?.get(1)
            val asin = Regex("class=\"field asin\"[^>]*>.*?<div[^>]*class=\"value\"[^>]*>(.*?)</div>", RegexOption.DOT_MATCHES_ALL).find(chunk)?.let { Html.text(it.groupValues[1]).trim() }
            val isbn = Regex("class=\"field isbn13\"[^>]*>.*?<div[^>]*class=\"value\"[^>]*>(.*?)</div>", RegexOption.DOT_MATCHES_ALL).find(chunk)?.let { Html.text(it.groupValues[1]).filter { c -> c.isDigit() } }

            val pctMatch = Regex("(\\d{1,3})%").find(chunk)
            val pageMatch = Regex("page (\\d+) of (\\d+)").find(chunk)
            val rowFraction = when {
                pctMatch != null -> pctMatch.groupValues[1].toDoubleOrNull()?.let { it / 100.0 }
                pageMatch != null -> {
                    val p = pageMatch.groupValues[1].toDoubleOrNull()
                    val total = pageMatch.groupValues[2].toDoubleOrNull()
                    if (p != null && total != null && total > 0) p / total else null
                }
                else -> null
            }
            val fraction = rowFraction ?: runCatching {
                peekPercent(session, Link(service = id, document = "", externalId = bookId))?.let { it / 100.0 }
            }.getOrNull()
            val keys = mutableSetOf("goodreads:$bookId", "book:$bookId")
            if (!asin.isNullOrBlank()) keys.add("asin:$asin")
            if (!isbn.isNullOrBlank()) keys.add("isbn:$isbn")

            out.add(
                LibraryBook(
                    id = bookId,
                    title = title,
                    author = author,
                    coverUrl = cover,
                    status = ReadStatus.READING,
                    fraction = fraction,
                    updatedAt = System.currentTimeMillis() / 1000,
                    keys = keys,
                )
            )
        }
        return out
    }

    override fun signInWithCookies(cookieHeader: String, userAgent: String): SignInResult {
        val session = Session(null)
        session.put("cookies", cookieHeader)
        session.put("userAgent", userAgent)
        val home = request(session, "GET", "/")
        val uid = Regex("/user/show/(\\d+)(?:-([a-z0-9-]+))?").find(home.body)
        if (!home.ok || uid == null) throw AuthException("Finish signing in to Goodreads first")
        val name = uid.groupValues[2].ifEmpty { "user ${uid.groupValues[1]}" }
        return SignInResult(session.encode(), name)
    }

    // ---- book details (Goodreads as a source of descriptions, page counts and ISBNs) ----

    /** The Goodreads book for a Kindle [asin]: its search goes straight to the book when it knows the ASIN. */
    fun bookIdForAsin(session: Session, asin: String): String? {
        if (!Regex("^[A-Z0-9]{10}$").matches(asin)) return null
        return bookIdFor(session, asin)
    }

    /** The Goodreads book for an ISBN or ASIN, when its search lands straight on one book. */
    fun bookIdFor(session: Session, identifier: String): String? {
        val q = identifier.trim().replace("-", "")
        if (!Regex("^([0-9]{9}[0-9X]|[0-9]{13}|[A-Z0-9]{10})$").matches(q)) return null
        val r = request(session, "GET", "/search?q=${Http.enc(q)}")
        if (!r.ok) return null
        return Regex("/book/show/(\\d+)").find(r.url)?.groupValues?.get(1)
    }

    /**
     * The book's Goodreads page address: by ISBN or ASIN (straight to the book), else a clear
     * match from a title and author search. For trackers that import a book from Goodreads.
     */
    fun bookUrlFor(session: Session, title: String, author: String?, isbn: String?, asin: String?): String? {
        val byId = listOfNotNull(isbn, asin).firstNotNullOfOrNull { runCatching { bookIdFor(session, it) }.getOrNull() }
        val id = byId ?: run {
            val plain = io.github.prithirajn.readync.sync.Matcher.plainTitle(title)
            val hits = runCatching { search(session, plain, author) }.getOrNull().orEmpty()
            val d = io.github.prithirajn.readync.sync.Matcher.decide(plain, author, hits)
            d.best?.takeIf { d.accepted }?.id
        } ?: return null
        return "https://www.goodreads.com/book/show/$id"
    }

    /**
     * A Goodreads book page's details: description, pages, ISBN, ASIN, cover, from the data the
     * page is built from (and its schema.org block as a fallback).
     */
    fun bookDetails(session: Session, bookId: String): io.github.prithirajn.readync.source.BookDetails? {
        val r = request(session, "GET", "/book/show/${Http.enc(bookId)}")
        if (!r.ok) return null
        return parseBookPage(r.body)
    }

    internal fun parseBookPage(html: String): io.github.prithirajn.readync.source.BookDetails? {
        var book: JSONObject? = null
        Regex("""<script[^>]*id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html)?.let { m ->
            val root = runCatching { JSONObject(m.groupValues[1]) }.getOrNull()
            // The book is the object marked "Book" that has a title (the page also lists other books).
            fun walk(v: Any?, depth: Int) {
                if (book != null || depth > 12) return
                when (v) {
                    is JSONObject -> {
                        if (v.optString("__typename") == "Book" && v.has("title") && v.keys().asSequence().any { it.startsWith("description") }) { book = v; return }
                        for (k in v.keys()) walk(v.opt(k), depth + 1)
                    }
                    is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i), depth + 1)
                }
            }
            walk(root, 0)
        }
        val ld = Regex("""<script[^>]*application/ld\+json[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).findAll(html)
            .mapNotNull { runCatching { JSONObject(it.groupValues[1].trim()) }.getOrNull() }.firstOrNull { it.optString("@type") == "Book" }
        val b = book
        val title = b?.str("title") ?: ld?.str("name") ?: return null
        // Prefer the plain-text description; else the HTML one, as text.
        val keys = b?.keys()?.asSequence()?.toList().orEmpty()
        val rawDesc = keys.firstOrNull { it.startsWith("description") && it.contains("stripped") }?.let { b?.str(it) }
            ?: keys.firstOrNull { it.startsWith("description") }?.let { b?.str(it) }
        val description = rawDesc?.let { d ->
            // Line breaks (as <br>, </p> or newlines) kept as paragraphs.
            d.split(Regex("(?i)<br\\s*/?>|</p>|\n")).joinToString("\n") { Html.text(it) }.replace(Regex("\n{3,}"), "\n\n").trim()
        }?.takeIf { it.isNotBlank() }
            ?: Regex("""<meta[^>]*property="og:description"[^>]*content="([^"]*)"""").find(html)?.groupValues?.get(1)?.let { Html.decode(it).trim() }?.takeIf { it.isNotBlank() }
        val details = b?.obj("details")
        val pages = details?.optInt("numPages")?.takeIf { it > 0 } ?: ld?.optInt("numberOfPages")?.takeIf { it > 0 }
        val isbn13 = details?.str("isbn13") ?: ld?.str("isbn")?.takeIf { it.length == 13 }
        val isbn10 = details?.str("isbn")?.takeIf { it.length == 10 } ?: ld?.str("isbn")?.takeIf { it.length == 10 }
        return io.github.prithirajn.readync.source.BookDetails(
            title = Html.decode(title),
            pages = pages,
            isbn13 = isbn13?.filter { it.isDigit() }?.takeIf { it.length == 13 },
            isbn10 = isbn10,
            publisher = details?.str("publisher"),
            language = details?.obj("language")?.str("name") ?: ld?.str("inLanguage"),
            description = description,
            coverUrl = b?.str("imageUrl") ?: ld?.str("image"),
            format = details?.str("format") ?: ld?.str("bookFormat"),
            source = "Goodreads",
            asin = details?.str("asin"),
        )
    }

    /** One book of a Goodreads series list: its number, id, title, cover and (when it says) release day. */
    data class SeriesBook(val position: Double, val id: String, val title: String, val coverUrl: String?, val date: Long?, val author: String?)

    /** The series page a book belongs to (its first series), from the book's page, or null. */
    fun seriesUrlForBook(session: Session, bookId: String): String? {
        val r = request(session, "GET", "/book/show/${Http.enc(bookId)}")
        if (!r.ok) return null
        return parseSeriesUrl(r.body)
    }

    internal fun parseSeriesUrl(html: String): String? =
        Regex("""https?://www\.goodreads\.com/series/\d+[^"'\s<\\]*""").find(html)?.value
            ?: Regex("""href="(/series/\d+[^"]*)"""").find(html)?.groupValues?.get(1)?.let { BASE + it }

    /** A book's release day from its page (its data's publication time), or null. */
    fun releaseDay(session: Session, bookId: String): Long? {
        val r = request(session, "GET", "/book/show/${Http.enc(bookId)}")
        if (!r.ok) return null
        return parseReleaseDay(r.body)
    }

    internal fun parseReleaseDay(html: String): Long? {
        Regex(""""publicationTime"\s*:\s*(-?\d{9,})""").find(html)?.groupValues?.get(1)?.toLongOrNull()?.let { return it / 1000 }
        // "Expected publication February 3, 2026" / "First published May 2, 2023".
        val m = Regex("""(?i)(?:expected publication|first published|published)\s+([A-Z][a-z]+ \d{1,2}, \d{4})""").find(html.replace(Regex("<[^>]+>"), " ")) ?: return null
        return runCatching {
            java.time.LocalDate.parse(m.groupValues[1], java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy", java.util.Locale.US))
                .atTime(9, 0).atZone(java.time.ZoneId.systemDefault()).toEpochSecond()
        }.getOrNull()
    }

    /** The books of a series page, in order ("Book 1", "Book 2"…), as the page lists them. */
    fun seriesBooks(session: Session, seriesUrl: String): List<SeriesBook> {
        val r = request(session, "GET", seriesUrl)
        if (!r.ok) return emptyList()
        return parseSeriesPage(r.body)
    }

    internal fun parseSeriesPage(html: String): List<SeriesBook> {
        val out = ArrayList<SeriesBook>()
        // The list is a React component whose data sits in the page: books, and a header per book ("Book 3").
        for (m in Regex("""data-react-props="([^"]*)"""").findAll(html)) {
            val o = runCatching { JSONObject(Html.decode(m.groupValues[1])) }.getOrNull() ?: continue
            val items = o.optJSONArray("series") ?: continue
            val headers = o.optJSONArray("seriesHeaders")
            for (i in 0 until items.length()) {
                val b = items.optJSONObject(i)?.optJSONObject("book") ?: continue
                val header = headers?.optString(i).orEmpty()
                val pos = Regex("""(?i)book\s+(\d+(?:\.\d+)?)""").find(header)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
                val id = b.optString("bookId").ifBlank { b.optString("id") }.ifBlank { Regex("""/book/show/(\d+)""").find(b.optString("bookUrl"))?.groupValues?.get(1).orEmpty() }
                val title = b.optString("bookTitleBare").ifBlank { b.optString("title") }.takeIf { it.isNotBlank() } ?: continue
                val dateText = listOf("publicationDate", "publishedDate", "publication_date").firstNotNullOfOrNull { k -> b.optString(k).takeIf { it.isNotBlank() && it != "null" } }
                    ?: Regex("""(?i)(?:expected publication|published)\s+([A-Z][a-z]+ \d{1,2}, \d{4}|\d{4})""").find(Html.text(b.optString("description", "")))?.groupValues?.get(1)
                val day = dateText?.let { t ->
                    runCatching { java.time.LocalDate.parse(t, java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy", java.util.Locale.US)) }.getOrNull()
                        ?: runCatching { java.time.LocalDate.parse(t, java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.US)) }.getOrNull()
                        ?: runCatching { java.time.LocalDate.parse(t.take(10)) }.getOrNull()
                }?.atTime(9, 0)?.atZone(java.time.ZoneId.systemDefault())?.toEpochSecond()
                val author = b.optJSONObject("author")?.optString("name")?.takeIf { it.isNotBlank() }
                out += SeriesBook(pos, id, Html.decode(title), b.optString("imageUrl").takeIf { it.startsWith("http") }, day, author)
            }
            if (out.isNotEmpty()) break
        }
        return out.groupBy { it.position }.map { it.value.first() }.sortedBy { it.position }
    }

    override fun search(session: Session, title: String, author: String?): List<RemoteBook> {
        val query = listOfNotNull(title, author).joinToString(" ").trim()
        if (query.isEmpty()) return emptyList()
        val r = request(session, "GET", "/search?q=${Http.enc(query)}")
        if (!r.ok) throw ServiceException("Goodreads search answered HTTP ${r.code}")
        val html = r.body
        // An exact match skips the results page and lands on the book itself.
        Regex("/book/show/(\\d+)").find(r.url)?.let { m ->
            return listOfNotNull(ldBook(m.groupValues[1], html))
        }
        val out = ArrayList<RemoteBook>()
        val seen = HashSet<String>()
        val titleRe = Regex(
            "data-testid=\"book-item-title\"><a href=\"/book/show/(\\d+)[^\"]*\">(.*?)</a>",
            RegexOption.DOT_MATCHES_ALL,
        )
        val matches = titleRe.findAll(html).toList()
        for ((i, m) in matches.withIndex()) {
            val id = m.groupValues[1]
            if (!seen.add(id)) continue
            // Look only at this result's own details, up to the next result.
            val end = matches.getOrNull(i + 1)?.range?.first ?: minOf(html.length, m.range.last + 3000)
            val after = html.substring(m.range.last, maxOf(m.range.last, end))
            val a = Regex("data-testid=\"name\">([^<]+)</span>").find(after)?.groupValues?.get(1)
            val before = html.substring(maxOf(0, m.range.first - 2000), m.range.first)
            out.add(withDetails(RemoteBook(id, Html.text(m.groupValues[2]), a?.let { Html.decode(it) }), after, before))
        }
        if (out.isEmpty()) {
            // Older results markup: <a class="bookTitle" href="/book/show/123..."><span>Title</span></a>
            val legacy = Regex(
                "class=\"bookTitle\"[^>]*href=\"/book/show/(\\d+)[^\"]*\"[^>]*>(.*?)</a>.*?class=\"authorName\"[^>]*>(.*?)</a>",
                RegexOption.DOT_MATCHES_ALL,
            )
            for (m in legacy.findAll(html)) {
                if (seen.add(m.groupValues[1])) {
                    val after = html.substring(m.range.last, minOf(html.length, m.range.last + 800))
                    val before = html.substring(maxOf(0, m.range.first - 2000), m.range.first)
                    out.add(withDetails(RemoteBook(m.groupValues[1], Html.text(m.groupValues[2]), Html.text(m.groupValues[3])), after, before))
                }
            }
        }
        return out
    }

    /** "313,194 ratings" -> 313194, so a 0-rating stray edition loses a tie to the main one. */
    private fun ratings(fragment: String): Int? =
        Regex("([\\d,.]+[kKmM]?)\\s+ratings?\\b").find(Html.text(fragment))?.let { parseCount(it.groupValues[1]) }

    /**
     * Fills what a search result shows next to the title: "4.25 avg rating — 12,345 ratings —
     * published 2016 — 74 editions", and the cover just before it.
     */
    internal fun withDetails(b: RemoteBook, after: String, before: String): RemoteBook {
        val text = Html.text(after)
        val count = ratings(after)
        val cover = Regex("<img[^>]+src=\"(https://[^\"]+)\"").findAll(before).lastOrNull()?.groupValues?.get(1)
        return b.copy(
            coverUrl = cover?.let { Html.decode(it) },
            popularity = count,
            ratingsCount = count,
            rating = Regex("(\\d(?:\\.\\d+)?)\\s*avg rating").find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 0.0..5.0 },
            reviewsCount = Regex("([\\d,.]+[kKmM]?)\\s+reviews?\\b").find(text)?.let { parseCount(it.groupValues[1]) },
            year = Regex("published\\s+(\\d{4})").find(text)?.groupValues?.get(1)?.toIntOrNull(),
            editions = Regex("([\\d,]+)\\s+editions?\\b").find(text)?.let { parseCount(it.groupValues[1]) },
        )
    }

    /** An exact search lands on the book's own page: its details come from the page's JSON-LD. */
    private fun ldBook(id: String, html: String): RemoteBook? {
        val block = Regex("<script type=\"application/ld\\+json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return null
        val o = runCatching { JSONObject(block) }.getOrNull() ?: return null
        val name = o.str("name") ?: return null
        val author = o.optJSONArray("author")?.optJSONObject(0)?.str("name")
        val agg = o.optJSONObject("aggregateRating")
        val count = agg?.firstNumber("ratingCount")?.toInt()
        return RemoteBook(
            id = id,
            title = Html.decode(name),
            author = author?.let { Html.decode(it) },
            coverUrl = o.str("image"),
            pages = o.firstNumber("numberOfPages")?.toInt(),
            popularity = count,
            rating = agg?.firstNumber("ratingValue")?.takeIf { it in 0.0..5.0 },
            ratingsCount = count,
            reviewsCount = agg?.firstNumber("reviewCount")?.toInt(),
            format = o.str("bookFormat")?.substringAfterLast('/')?.let { Html.decode(it) },
        )
    }

    /**
     * The books on your Currently Reading and Want to Read shelves, newest first. Goodreads
     * lists every edition under the same title and first author, so search alone picked an
     * audiobook edition over the one already on your shelf.
     */
    override fun shelfCandidates(session: Session): List<RemoteBook> {
        val home = request(session, "GET", "/")
        val uid = Regex("/user/show/(\\d+)").find(home.body)?.groupValues?.get(1) ?: return emptyList()
        val out = ArrayList<RemoteBook>()
        val seen = HashSet<String>()
        for (shelf in listOf("currently-reading", "to-read")) {
            val r = request(session, "GET", "/review/list/$uid?shelf=$shelf&per_page=100&sort=date_added&order=d")
            if (!r.ok) continue
            val rows = r.body.split("<tr id=\"review_").drop(1)
            for (row in rows) {
                val titleCell = Regex("class=\"field title\"(.*?)</td>", RegexOption.DOT_MATCHES_ALL).find(row)?.groupValues?.get(1)
                    ?: continue
                val link = Regex("<a[^>]*href=\"/book/show/(\\d+)[^\"]*\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(titleCell)
                    ?: continue
                val id = link.groupValues[1]
                if (!seen.add(id)) continue
                val author = Regex("/author/show/[^\"]*\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(row)
                    ?.groupValues?.get(1)?.let { Html.text(it) }
                out.add(RemoteBook(id, Html.text(link.groupValues[2]), author))
            }
        }
        return out
    }

    /** Finds the Apollo cache object that holds ROOT_QUERY.<query>. */
    private fun findCache(value: Any?, query: String): JSONObject? {
        when (value) {
            is JSONObject -> {
                if (value.obj("ROOT_QUERY")?.obj(query)?.has("__ref") == true) return value
                for (k in value.keys()) findCache(value.opt(k), query)?.let { return it }
            }
            is JSONArray -> for (i in 0 until value.length()) findCache(value.opt(i), query)?.let { return it }
        }
        return null
    }

    override fun state(session: Session, link: Link): RemoteState {
        val id = link.externalId ?: throw ServiceException("Book isn't linked")
        val r = request(session, "GET", "/book/show/$id")
        if (!r.ok) throw ServiceException("Goodreads answered HTTP ${r.code}")
        val query = "getBookByLegacyId({\"legacyId\":\"$id\"})"
        var found: JSONObject? = null
        for (m in Regex("<script[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).findAll(r.body)) {
            val script = m.groupValues[1]
            if (!script.contains("getBookByLegacyId")) continue
            val parsed = runCatching { JSONObject(script) }.getOrNull() ?: continue
            found = findCache(parsed, query)
            if (found != null) break
        }
        val cache = found ?: throw ServiceException("Couldn't read the shelf from the Goodreads book page")
        val ref = cache.obj("ROOT_QUERY")?.obj(query)?.str("__ref")
        val book = ref?.let { cache.obj(it) } ?: throw ServiceException("Couldn't read the shelf from the Goodreads book page")
        if (book.isNull("viewerShelving")) return RemoteState(null, null)
        val shelvingRef = book.obj("viewerShelving")?.str("__ref")
        val shelf = shelvingRef?.let { cache.obj(it)?.obj("shelf")?.str("name") }
        val status = when (shelf) {
            "to-read" -> ReadStatus.TO_READ
            "currently-reading" -> ReadStatus.READING
            "read" -> ReadStatus.FINISHED
            else -> null
        }
        return RemoteState(status, null)
    }

    /**
     * The progress the book page shows you: from its data (your shelving and status updates),
     * else the "x% done" text.
     */
    override fun peekPercent(session: Session, link: Link): Int? {
        val id = link.externalId ?: return null
        val r = request(session, "GET", "/book/show/$id")
        if (!r.ok) return null
        for (m in Regex("<script[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).findAll(r.body)) {
            val script = m.groupValues[1]
            if (!script.contains("viewerShelving")) continue
            val parsed = runCatching { JSONObject(script) }.getOrNull() ?: continue
            val cache = findCache(parsed, "getBookByLegacyId({\"legacyId\":\"$id\"})") ?: continue
            // Only the viewer's own objects: shelving, reading progress, status updates.
            for (k in cache.keys()) {
                val lk = k.lowercase()
                if (lk.startsWith("shelving") || lk.contains("userstatus") || lk.contains("readingprogress") || lk.contains("readingsession")) {
                    findPercent(cache.opt(k))?.let { return it }
                }
            }
        }
        return Regex("(\\d{1,3})\\s*%\\s*(?:done|complete)", RegexOption.IGNORE_CASE).find(r.body)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 }
    }

    // ---- adding a book Goodreads doesn't have ----

    override val addBookPage = "$BASE/book/new"
    override val addedBookId = Regex("goodreads\\.com/(?:book/show|review/edit)/(\\d+)")
    override fun webCookies(session: Session): List<Pair<String, String>> =
        session.data.optString("cookies").split(";").map { it.trim() }.filter { it.contains('=') }.map { BASE to it }

    override val addsBooks = true
    override val addBookNote = "Added through Goodreads' Manually add a book form; it's public, and may take a few minutes to show in search."

    /** Goodreads' "Manually add a book" form (/book/new), filled from the book's details, then created. */
    override fun addBook(session: Session, document: String, details: io.github.prithirajn.readync.source.BookDetails): RemoteBook {
        val page = request(session, "GET", "/book/new")
        if (!page.ok) throw ServiceException("Goodreads didn't open its add-a-book form (HTTP ${page.code})")
        val form = io.github.prithirajn.readync.util.HtmlForm.find(page.body, Regex("(?i)title"))
            ?: throw ServiceException("Couldn't find Goodreads' add-a-book form")
        form.fillBook(details)
        val action = form.action?.let { if (it.startsWith("http")) it else BASE + it } ?: "$BASE/book/create"
        // The cover goes up as a file when the form takes one (it's an upload, not an address).
        val image = form.coverField?.let { f -> details.coverUrl?.let { Http.bytes(it) }?.let { f to it } }
        val upload = image?.let { (f, bytes) -> form.multipart(f, bytes) }
        val r = request(session, form.method, action, if (upload == null) form.encoded() else null, mapOf(
            "Content-Type" to (upload?.second ?: "application/x-www-form-urlencoded"), "Origin" to BASE, "Referer" to "$BASE/book/new",
        ), raw = upload?.first)
        val where = r.header("location") ?: r.url
        val id = Regex("/book/show/(\\d+)").find(where)?.groupValues?.get(1)
            ?: Regex("/review/edit/(\\d+)").find(where)?.groupValues?.get(1)
            ?: Regex("/book/show/(\\d+)").find(r.body)?.groupValues?.get(1)
            ?: throw ServiceException(
                if (r.code in setOf(403, 429) || r.body.contains("captcha", true) || r.body.contains("Challenge", true))
                    "Goodreads requires anti-bot verification. Tap below to finish on their pre-filled page."
                else "Goodreads didn't create the book (HTTP ${r.code}" + (serverReason(r.body)?.let { ": $it" } ?: "") + ")"
            )
        return RemoteBook(id, details.title, details.author, coverUrl = details.coverUrl, pages = details.pages, year = details.year)
    }

    private fun ajax(csrf: String) = mapOf(
        "X-CSRF-Token" to csrf,
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "text/javascript, text/html, application/xml, text/xml, */*",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "Origin" to BASE,
        "Referer" to "$BASE/",
        "X-Prototype-Version" to "1.7",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Site" to "same-origin",
    )

    override fun setStatus(session: Session, link: Link, status: ReadStatus) {
        val id = link.externalId ?: throw ServiceException("Book isn't linked")
        val shelf = when (status) {
            ReadStatus.TO_READ -> "to-read"
            ReadStatus.READING -> "currently-reading"
            ReadStatus.FINISHED -> "read"
            ReadStatus.PAUSED -> "paused"
            ReadStatus.DNF -> "did-not-finish"
        }
        val form = Http.form("book_id" to id, "name" to shelf, "a" to "")
        var r = request(session, "POST", "/shelf/add_to_shelf", form, ajax(csrf(session)))
        // A stale CSRF token or a passing server hiccup: one retry with a fresh token, as ShelfSync does.
        if (!r.ok) r = request(session, "POST", "/shelf/add_to_shelf", form, ajax(csrf(session)))
        if (!r.ok) {
            val hint = when {
                shelf == "paused" || shelf == "did-not-finish" -> "; create a \"$shelf\" shelf on Goodreads first"
                else -> ""
            }
            throw ServiceException("Goodreads didn't accept the shelf change to $shelf (HTTP ${r.code})$hint")
        }
    }

    override fun pushProgress(session: Session, link: Link, percent: Int, visibility: Visibility) {
        val id = link.externalId ?: throw ServiceException("Book isn't linked")
        val token = csrf(session)
        val r = request(
            session, "POST", "/user_status.json",
            Http.form(
                "user_status[book_id]" to id,
                "user_status[percent]" to percent.coerceIn(0, 100).toString(),
                "user_status[body]" to "",
            ),
            ajax(token).plus("Accept" to "*/*"),
        )
        if (!r.ok) throw ServiceException("Goodreads didn't accept the progress (HTTP ${r.code})")
    }

    override val oneEntryPerHighlight = true
    override val highlightEntriesSetProgress = true

    /** Page counts of editions highlights went to, so each highlight doesn't fetch the book again. */
    private val pagesOf = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Highlights posted as individual status updates, each at its own place in the book. */
    override fun postHighlight(
        session: Session,
        link: Link,
        percent: Int,
        clip: io.github.prithirajn.readync.source.Clipping,
        previousId: String?,
    ): String? {
        val id = link.externalId ?: throw ServiceException("Book isn't linked")
        val note = formatHighlights(listOf(clip), 1000).firstOrNull() ?: return null
        // Its own place in the book (26%, 28%…), as worked out by the sync; your real position is
        // sent again after the entries (see highlightEntriesSetProgress).
        val shown = percent.coerceIn(0, 100)
        // By page where the edition's page count is known: its exact page, so two highlights in the
        // same percent still show on their own pages. Whole percent otherwise.
        val total = pagesOf[id] ?: runCatching { bookDetails(session, id)?.pages }.getOrNull()?.also { pagesOf[id] = it }
        val page = clip.pageIn(total)
        val token = csrf(session)
        fun post(byPage: Boolean) = request(
            session, "POST", "/user_status.json",
            Http.form(
                "user_status[book_id]" to id,
                if (byPage) "user_status[page]" to page.toString() else "user_status[percent]" to shown.toString(),
                "user_status[body]" to note,
            ),
            ajax(token).plus("Accept" to "*/*"),
        )
        var r = post(page != null)
        if (!r.ok && page != null && r.code !in setOf(401, 403)) r = post(false)
        if (!r.ok) throw ServiceException("Goodreads didn't accept the highlight (HTTP ${r.code})")
        return null
    }

    override fun postHighlights(
        session: Session,
        link: Link,
        percent: Int,
        entries: List<io.github.prithirajn.readync.source.Clipping>,
    ) {
        for (c in entries) {
            postHighlight(session, link, percent, c, null)
        }
    }
}
