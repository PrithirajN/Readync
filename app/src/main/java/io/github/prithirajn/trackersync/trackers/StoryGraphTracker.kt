package io.github.prithirajn.trackersync.trackers

import io.github.prithirajn.trackersync.util.Markdown
import io.github.prithirajn.trackersync.data.Link
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.Visibility
import io.github.prithirajn.trackersync.net.AuthException
import io.github.prithirajn.trackersync.net.CookieJar
import io.github.prithirajn.trackersync.net.Http
import io.github.prithirajn.trackersync.net.HttpResponse
import io.github.prithirajn.trackersync.net.ServiceException
import io.github.prithirajn.trackersync.util.Html
import io.github.prithirajn.trackersync.util.str

/**
 * The StoryGraph (app.thestorygraph.com), driven like its website: the session cookie from
 * an in-app sign-in, a CSRF token from each book page, then form posts. Endpoints and HTML
 * patterns follow ShelfSync's storygraph/api.lua.
 */
object StoryGraphTracker : Tracker {
    override val id = "storygraph"
    override val name = "StoryGraph"
    override val ratingStep = 0.25
    override val signInKind = SignInKind.WEBVIEW
    override val privacy = PrivacySupport.SKIP_PROGRESS
    override val readsProgress = true
    override val statuses = ReadStatus.entries.toSet()
    override val signInUrl = "https://app.thestorygraph.com/users/sign_in"
    override val cookieUrl = "https://app.thestorygraph.com"
    override val sessionCookie = "remember_user_token"
    override val privacyNote =
        "Only me sends status changes but no progress updates. StoryGraph may still show " +
            "Currently Reading and Read to your friends."

    /** The site (changed only by tests, to a stand-in server). */
    internal var BASE = "https://app.thestorygraph.com"

    override val highlights = HighlightSupport.PRIVATE_JOURNAL
    override val highlightsNote = "Added to the book's reading journal, which only you can see."

    private fun jar(session: Session) = CookieJar.parse(session.data.str("cookies") ?: "")

    /** This phone's own browser, as the site sees it when you use it yourself. */
    internal val WEB_USER_AGENT: String get() = Http.defaultUserAgent

    private fun headers(session: Session, jar: CookieJar, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val h = linkedMapOf(
            "User-Agent" to (session.data.str("userAgent")?.takeIf { it.isNotBlank() } ?: WEB_USER_AGENT),
            "Cookie" to jar.header(),
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "same-origin",
            "Upgrade-Insecure-Requests" to "1",
        )
        // The browser's own hints, matching its user agent (as the site sees on every visit).
        h.putAll(Http.clientHints(h.getValue("User-Agent")))
        h.putAll(extra)
        return h
    }

    /** Sends one request, follows same-site redirects for GETs, keeps cookies fresh. */
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
            if (r.code == 401) throw AuthException("StoryGraph signed you out; sign in again")
            val location = r.header("location")
            if (r.isRedirect && location != null) {
                if (location.contains("/users/sign_in")) throw AuthException("StoryGraph signed you out; sign in again")
                if (m != "GET") return r // a redirect after a form post is the normal success reply
                url = if (location.startsWith("http")) location else BASE + location
                if (!url.startsWith(BASE)) return r
                m = "GET"; b = null; rb = null
                return@repeat
            }
            if (r.header("cf-mitigated") == "challenge") {
                throw ServiceException("StoryGraph's Cloudflare check blocked the request; try again later")
            }
            return r
        }
        throw ServiceException("StoryGraph redirected too many times")
    }

    override fun signInWithCookies(cookieHeader: String, userAgent: String): SignInResult {
        val jar = CookieJar.parse(cookieHeader)
        if (jar["_storygraph_session"] == null || jar["remember_user_token"] == null) {
            throw AuthException("Finish signing in to StoryGraph first")
        }
        val session = Session(null)
        session.put("cookies", jar.header())
        session.put("userAgent", userAgent)
        val home = request(session, "GET", "/")
        if (!home.ok) throw ServiceException("StoryGraph answered HTTP ${home.code}")
        val username = Regex("href=\"/profile/([^\"/?]+)\"").find(home.body)?.groupValues?.get(1)
        return SignInResult(session.encode(), username ?: "StoryGraph account")
    }

    override fun search(session: Session, title: String, author: String?): List<RemoteBook> {
        val query = listOfNotNull(title, author).joinToString(" ").trim()
        if (query.isEmpty()) return emptyList()
        val r = request(session, "GET", "/browse?search_term=${Http.enc(query)}")
        if (!r.ok) throw ServiceException("StoryGraph search answered HTTP ${r.code}")
        val html = r.body
        val starts = Regex("book-title-author-and-series").findAll(html).map { it.range.first }.toList()
        val seen = HashSet<String>()
        val out = ArrayList<RemoteBook>()
        for ((i, start) in starts.withIndex()) {
            val end = if (i + 1 < starts.size) starts[i + 1] else minOf(html.length, start + 6000)
            val chunk = html.substring(start, end)
            val t = Regex("<a[^>]*href=\"/books/([^\"/?]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(chunk) ?: continue
            val bookId = t.groupValues[1]
            if (!seen.add(bookId)) continue
            val a = Regex("<a[^>]*href=\"/authors/[^\"]+\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(chunk)
            if (Regex("Format:\\s*[^<]*audio", RegexOption.IGNORE_CASE).containsMatchIn(chunk)) continue
            val text = Html.text(chunk)
            // The cover sits just before the title block.
            val before = html.substring(maxOf(0, start - 2500), start)
            out.add(
                RemoteBook(
                    id = bookId,
                    title = Html.text(t.groupValues[2]).ifEmpty { "Untitled" },
                    author = a?.let { Html.text(it.groupValues[1]) },
                    pages = Regex("(\\d+) pages").find(chunk)?.groupValues?.get(1)?.toIntOrNull(),
                    coverUrl = Regex("<img[^>]+src=\"(https://[^\"]+)\"").findAll(before).lastOrNull()?.groupValues?.get(1)?.let { Html.decode(it) },
                    year = Regex("first pub\\s+(\\d{4})", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull(),
                    format = Regex("format:\\s*([A-Za-z][A-Za-z ]*?)\\s*(?:[•·|]|first pub|\\d|$)", RegexOption.IGNORE_CASE)
                        .find(text)?.groupValues?.get(1)?.trim()?.replaceFirstChar { it.uppercase() }?.takeIf { it.isNotEmpty() },
                    rating = Regex("(\\d\\.\\d{1,2})\\s*(?:average|avg)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it <= 5.0 },
                    reviewsCount = Regex("([\\d,.]+[kKmM]?)\\s+reviews?\\b").find(text)?.let { parseCount(it.groupValues[1]) },
                )
            )
        }
        return out
    }

    /**
     * What StoryGraph readers say about a book: the moods, pace and genres on its page, its
     * average rating, and the content warnings readers submitted (graphic ones marked). The book
     * it's matched to there, else a clear match from its search.
     */
    fun facts(session: Session, link: Link?, title: String, author: String?, now: Long = System.currentTimeMillis() / 1000): io.github.prithirajn.trackersync.data.BookFacts? {
        val id = link?.externalId ?: run {
            val plain = io.github.prithirajn.trackersync.sync.Matcher.plainTitle(title)
            val hits = runCatching { search(session, plain, author) }.getOrNull().orEmpty()
            val d = io.github.prithirajn.trackersync.sync.Matcher.decide(plain, author, hits)
            (d.best?.takeIf { d.accepted } ?: hits.firstOrNull { io.github.prithirajn.trackersync.sync.Matcher.sameBook(title, author, it.title, it.author) })?.id
        } ?: return null
        val page = request(session, "GET", "/books/$id").takeIf { it.ok }?.body ?: return null
        val tags = parseTags(page)
        val rating = runCatching {
            request(session, "GET", "/books/$id/community_reviews").takeIf { it.ok }?.body?.let { parseAverage(it) }
        }.getOrNull()
        val (warnings, graphic) = runCatching {
            request(session, "GET", "/books/$id/content_warnings").takeIf { it.ok }?.body?.let { parseWarnings(it) }
        }.getOrNull() ?: (emptyList<String>() to emptyList())
        return io.github.prithirajn.trackersync.data.BookFacts(
            rating = rating,
            pace = tags.pace,
            moods = tags.moods,
            genres = tags.genres,
            warnings = warnings,
            graphic = graphic,
            ratings = rating?.let { mapOf("StoryGraph" to it) } ?: emptyMap(),
            sources = listOf("StoryGraph"),
            at = now,
        )
    }

    /** StoryGraph's own mood words; anything else in a book's tags (bar the pace) is a genre. */
    private val MOODS = setOf(
        "adventurous", "challenging", "dark", "emotional", "funny", "hopeful", "informative",
        "inspiring", "lighthearted", "mysterious", "reflective", "relaxing", "sad", "tense",
    )

    data class Tags(val moods: List<String>, val pace: String?, val genres: List<String>)

    /** The tag row on a book's page ("fantasy  adventurous funny  fast-paced"), sorted into kinds. */
    fun parseTags(html: String): Tags {
        val start = html.indexOf("book-page-tag-section")
        if (start < 0) return Tags(emptyList(), null, emptyList())
        // The section ends where its block does; a few thousand characters is always enough.
        val chunk = html.substring(start, minOf(html.length, start + 6000))
        val end = Regex("</div>\\s*</div>|<h4|<turbo-frame|class=\"standard-pane").find(chunk, 30)?.range?.first ?: chunk.length
        val words = Regex("<span[^>]*>(.*?)</span>", RegexOption.DOT_MATCHES_ALL).findAll(chunk.substring(0, end))
            .map { io.github.prithirajn.trackersync.util.Html.text(it.groupValues[1]) }
            .filter { it.isNotBlank() && it.length < 40 }
            .distinctBy { it.lowercase() }
            .toList()
        val moods = words.filter { it.lowercase() in MOODS }
        val pace = words.firstOrNull { it.lowercase().endsWith("-paced") || it.lowercase().endsWith(" paced") }
            ?.replaceFirstChar { it.uppercase() }
        val genres = words.filter { it.lowercase() !in MOODS && it != pace && !it.lowercase().endsWith("paced") }
            .map { g -> g.split(' ').joinToString(" ") { w -> if (w.length > 2 || w == g) w.replaceFirstChar { it.uppercase() } else w } }
        return Tags(moods, pace, genres)
    }

    /** "4.12" from a book's community reviews page. */
    fun parseAverage(html: String): Double? =
        Regex("average-star-rating[^>]*>\\s*([0-9](?:\\.[0-9]+)?)").find(html)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 0.1..5.0 }

    /**
     * Reader-submitted content warnings: "Graphic" / "Moderate" / "Minor" headings, each warning
     * with how many readers flagged it. Rarely flagged ones are left out; graphic ones come first.
     * Returns (all warnings, the graphic ones).
     */
    fun parseWarnings(html: String): Pair<List<String>, List<String>> {
        val tokens = Regex(
            "<p[^>]*>\\s*(Graphic|Moderate|Minor)\\s*</p>|<div[^>]*>\\s*([^<>]{2,80}?)\\s*\\((\\d+)\\)\\s*</div>",
            RegexOption.IGNORE_CASE,
        ).findAll(html)
        val rank = mapOf("graphic" to 0, "moderate" to 1, "minor" to 2)
        var level = "graphic"
        // lower-case name -> (as written, level, readers who flagged it)
        val found = LinkedHashMap<String, Triple<String, String, Int>>()
        for (m in tokens) {
            val head = m.groupValues[1]
            if (head.isNotEmpty()) { level = head.lowercase(); continue }
            val name = io.github.prithirajn.trackersync.util.Html.text(m.groupValues[2])
            val n = m.groupValues[3].toIntOrNull() ?: continue
            if (name.isBlank()) continue
            val prev = found[name.lowercase()]
            // The same warning at two levels: the stronger level, with the readers added up.
            val keep = if (prev == null || (rank[level] ?: 3) < (rank[prev.second] ?: 3)) level else prev.second
            found[name.lowercase()] = Triple(prev?.first ?: name, keep, (prev?.third ?: 0) + n)
        }
        if (found.isEmpty()) return emptyList<String>() to emptyList()
        val most = found.values.maxOf { it.third }
        val kept = found.values.filter { most < 10 || it.third * 10 >= most }
            .sortedWith(compareBy<Triple<String, String, Int>> { rank[it.second] ?: 3 }.thenByDescending { it.third })
            .take(16)
        return kept.map { it.first } to kept.filter { it.second == "graphic" }.map { it.first }
    }

    private fun bookPage(session: Session, link: Link): String {
        val id = link.externalId ?: throw ServiceException("Book isn't linked")
        val r = request(session, "GET", "/books/$id")
        if (!r.ok) throw ServiceException("StoryGraph answered HTTP ${r.code}")
        return r.body
    }

    private fun statusFrom(html: String): ReadStatus? {
        val raw = Regex("class=\"read-status-label[^\"]*\"[^>]*>\\s*([^<]*?)\\s*</button>").find(html)
            ?.groupValues?.get(1) ?: return null
        val t = Html.decode(raw).lowercase()
        return when {
            "currently reading" in t || "rereading" in t -> ReadStatus.READING
            "to-read" in t || "to read" in t -> ReadStatus.TO_READ
            "paused" in t -> ReadStatus.PAUSED
            "did not finish" in t -> ReadStatus.DNF
            t == "read" || t.endsWith(" read") -> ReadStatus.FINISHED
            else -> null
        }
    }

    private fun percentFrom(html: String): Int {
        Regex("edit-progress[^>]*>\\s*<div[^>]*style=\"width:\\s*(\\d+)%\"").find(html)
            ?.let { return it.groupValues[1].toInt() }
        return field(html, "read_status[last_reached_percent]")?.toDoubleOrNull()?.toInt() ?: 0
    }

    private fun field(html: String, name: String): String? {
        val n = Regex.escape(name)
        return Regex("name=\"$n\"\\s+[^>]*value=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            ?: Regex("value=\"([^\"]+)\"\\s+[^>]*name=\"$n\"").find(html)?.groupValues?.get(1)
    }

    override fun state(session: Session, link: Link): RemoteState {
        val html = bookPage(session, link)
        return RemoteState(statusFrom(html), percentFrom(html))
    }

    private fun ajaxHeaders(csrf: String, referer: String) = mapOf(
        "X-CSRF-Token" to csrf,
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "text/javascript, application/javascript, application/ecmascript, application/x-ecmascript, */*; q=0.01",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "Origin" to BASE,
        "Referer" to referer,
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Site" to "same-origin",
    )

    // ---- finish flow: rating and review ----

    override val reviews = ReviewSupport.RATING_AND_TEXT

    /**
     * StoryGraph's review form (ShelfSync's capture): stars as whole + hundredths, the text as
     * "explanation". An existing review is edited through its own form; else a new one is made.
     */
    override fun setReview(session: Session, link: Link, rating: Double?, text: String?) {
        val id = link.externalId ?: throw ServiceException("Link this book on StoryGraph first")
        val page = bookPage(session, link)
        val existing = Regex("href=\"(/reviews/[0-9a-f-]{8,}[^\"/]*)(?:/edit)?\"").find(page)?.groupValues?.get(1)
        val formUrl = if (existing != null) "$BASE$existing/edit" else "$BASE/reviews/new?book_id=$id"
        val form = request(session, "GET", formUrl)
        val token = Html.csrf(form.body) ?: throw ServiceException("StoryGraph's review form didn't open (HTTP ${form.code})")
        val keepText = Html.decode(
            Regex("name=\"review\\[explanation\\]\"[^>]*value=\"([^\"]*)\"").find(form.body)?.groupValues?.get(1) ?: ""
        )
        val total = Math.round((rating?.takeIf { it > 0 }?.let { stepRating(it) } ?: 0.0) * 100).toInt()
        val fields = linkedMapOf(
            "authenticity_token" to token,
            "stars_integer" to (total / 100).toString(),
            "stars_decimal" to (total % 100).let { if (it == 0) "" else it.toString() },
            "review[explanation]" to (text?.let { Markdown.toTrixHtml(it) } ?: keepText),
            "review[book_id]" to id,
            "review[mood_ids][]" to "",
            "return_to" to "/books/$id",
            "button" to "",
        )
        if (existing != null) fields["_method"] = "patch"
        val r = request(
            session, "POST", if (existing != null) "$BASE$existing" else "$BASE/reviews",
            fields.entries.joinToString("&") { (k, v) -> Http.enc(k) + "=" + Http.enc(v) },
            mapOf(
                "X-CSRF-Token" to token,
                "Accept" to "text/vnd.turbo-stream.html, text/html, application/xhtml+xml",
                "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
                "Origin" to BASE,
                "Referer" to formUrl,
            ),
        )
        if (r.code != 200 && !r.isRedirect) throw ServiceException("StoryGraph didn't take the review (HTTP ${r.code})")
    }

    // ---- read dates ----

    override val takesDates = true

    /**
     * The start and finish days of your newest read, through the book's own read-dates form (its
     * "edit" link on the book page, or a new read's form when there's none). The form is read as
     * the site shows it: its day, month and year fields found by their names and filled the way
     * its menus list them; everything else on it is sent back as it was.
     */
    override fun setDates(session: Session, link: Link, started: java.time.LocalDate?, finished: java.time.LocalDate?) {
        if (started == null && finished == null) return
        val id = link.externalId ?: throw ServiceException("Book isn't linked")
        val page = bookPage(session, link)
        val path = readDatesFormPath(page, id)
        val formPage = request(session, "GET", path)
        if (!formPage.ok) throw ServiceException("StoryGraph's read dates didn't open (HTTP ${formPage.code})")
        val form = DateForm.parse(formPage.body) ?: throw ServiceException("StoryGraph's read-dates form wasn't found")
        val fields = form.fill(started, finished) ?: throw ServiceException("StoryGraph's read-dates form has no date fields")
        val token = fields["authenticity_token"] ?: Html.csrf(formPage.body) ?: throw AuthException("StoryGraph didn't show a signed-in page; sign in again")
        val formPagePath = path.substringBefore('?')
        val action = when {
            form.action.startsWith("http://") || form.action.startsWith("https://") -> form.action
            form.action.startsWith("/") -> BASE + form.action
            form.action.isNotBlank() && !form.action.contains(":") -> "$BASE/${form.action.removePrefix("/")}"
            else -> if (formPagePath.startsWith("http")) formPagePath else (if (formPagePath.startsWith("/")) BASE + formPagePath else "$BASE/$formPagePath")
        }
        val r = request(
            session, "POST", action,
            fields.entries.joinToString("&") { (k, v) -> Http.enc(k) + "=" + Http.enc(v) },
            mapOf(
                "X-CSRF-Token" to token,
                "Accept" to "text/vnd.turbo-stream.html, text/html, application/xhtml+xml",
                "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
                "Origin" to BASE,
                "Referer" to (if (path.startsWith("http")) path else BASE + path),
            ),
        )
        if (r.code != 200 && !r.isRedirect) throw ServiceException("StoryGraph didn't take the dates (HTTP ${r.code})")
    }

    /** Where the newest read's dates are edited (or a read is added), from the book page. */
    internal fun readDatesFormPath(page: String, bookId: String): String =
        Regex("href=\"(/read_instances/[^\"/?]+/edit[^\"]*)\"").find(page)?.groupValues?.get(1)?.let { Html.decode(it) }
            ?: Regex("href=\"(/read_instances/new[^\"]*)\"").find(page)?.groupValues?.get(1)?.let { Html.decode(it) }
            ?: "/read_instances/new?book_id=$bookId"

    /** A read-dates form as the site shows it: where it goes, and its fields with their values (and a menu's choices). */
    internal class DateForm(val action: String, val values: LinkedHashMap<String, String>, val options: Map<String, List<Pair<String, String>>>, val dateInputs: Set<String>) {
        /**
         * The fields to send: [started] into the start day, month and year (or a start date
         * field), [finished] into the others; a day not given stays as it was. Null when the form
         * has no date fields at all.
         */
        fun fill(started: java.time.LocalDate?, finished: java.time.LocalDate?): LinkedHashMap<String, String>? {
            val out = LinkedHashMap(values)
            var found = false
            for (name in values.keys.toList()) {
                val n = name.lowercase()
                val part = when {
                    name in dateInputs -> "date"
                    Regex("(^|[\\[_])day\\b|day\\]").containsMatchIn(n) -> "day"
                    "month" in n -> "month"
                    "year" in n -> "year"
                    else -> null
                } ?: continue
                found = true
                val isStart = "start" in n || "began" in n || "from" in n
                val d = (if (isStart) started else finished) ?: continue
                out[name] = when (part) {
                    "date" -> d.toString()
                    "day" -> pick(name, d.dayOfMonth.toString(), null)
                    "month" -> pick(name, d.monthValue.toString(), d.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH))
                    else -> d.year.toString()
                }
            }
            return if (found) out else null
        }

        /** The menu choice for [number] (or a month's [word]), in the form the menu uses. */
        private fun pick(name: String, number: String, word: String?): String {
            val opts = options[name] ?: return number
            opts.firstOrNull { it.first.trimStart('0') == number }?.let { return it.first }
            if (word != null) opts.firstOrNull { (v, t) -> v.startsWith(word.take(3), true) || t.startsWith(word.take(3), true) }?.let { return it.first }
            return number
        }

        companion object {
            /** Extract attribute [a] from an HTML tag string, ensuring [a] is strictly the attribute name. */
            internal fun attr(tag: String, a: String): String? {
                // Must be preceded by start or whitespace to prevent matching data-action, stimulus-action, etc.
                val regex = Regex("""(?i)(?:^|\s)${Regex.escape(a)}\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
                val m = regex.find(tag) ?: return null
                val raw = when {
                    m.groups[1] != null -> m.groups[1]!!.value
                    m.groups[2] != null -> m.groups[2]!!.value
                    m.groups[3] != null -> m.groups[3]!!.value
                    else -> ""
                }
                return Html.decode(raw)
            }

            /** The form holding the read's dates, or null. */
            fun parse(html: String): DateForm? {
                // Allow attribute values inside quotes to contain '>' characters (such as data-action="turbo:submit-end->window#...")
                val forms = Regex("""(?is)<form\b((?:[^"'>]|"[^"]*"|'[^']*')*)>(.*?)</form>""").findAll(html).toList()
                val m = forms.firstOrNull { "read_instance" in it.value } ?: forms.firstOrNull { f -> Regex("""(?i)name\s*=\s*["'][^"']*(day|month|year)""").containsMatchIn(f.value) } ?: return null
                val formTag = m.groupValues[1]
                val rawAction = attr(formTag, "action").orEmpty().trim()
                // Action must be a real address (starts with '/' or 'http'). If malformed or junk (like "submit-end->window"), leave empty to fallback to the page's own URL.
                val action = if (rawAction.startsWith("/") || rawAction.startsWith("http://") || rawAction.startsWith("https://")) rawAction else ""
                val body = m.groupValues[2]
                val values = LinkedHashMap<String, String>()
                val dateInputs = HashSet<String>()
                for (t in Regex("""(?is)<input\b((?:[^"'>]|"[^"]*"|'[^']*')*)>""").findAll(body)) {
                    val attrs = t.groupValues[1]
                    val name = attr(attrs, "name") ?: continue
                    val type = (attr(attrs, "type") ?: "text").lowercase()
                    if (type in setOf("submit", "button", "image", "file")) continue
                    if ((type == "checkbox" || type == "radio") && !Regex("""(?i)\bchecked\b""").containsMatchIn(attrs)) continue
                    if (type == "date") dateInputs += name
                    values[name] = attr(attrs, "value") ?: ""
                }
                val options = HashMap<String, List<Pair<String, String>>>()
                for (sel in Regex("""(?is)<select\b((?:[^"'>]|"[^"]*"|'[^']*')*)>(.*?)</select>""").findAll(body)) {
                    val attrs = sel.groupValues[1]
                    val name = attr(attrs, "name") ?: continue
                    val opts = Regex("""(?is)<option\b((?:[^"'>]|"[^"]*"|'[^']*')*)>(.*?)</option>""").findAll(sel.groupValues[2]).map { o ->
                        val optAttrs = o.groupValues[1]
                        val text = Html.text(o.groupValues[2])
                        Triple(attr(optAttrs, "value") ?: text, text, Regex("""(?i)\bselected\b""").containsMatchIn(optAttrs))
                    }.toList()
                    options[name] = opts.map { it.first to it.second }
                    values[name] = opts.firstOrNull { it.third }?.first ?: ""
                }
                for (ta in Regex("""(?is)<textarea\b((?:[^"'>]|"[^"]*"|'[^']*')*)>(.*?)</textarea>""").findAll(body)) {
                    val attrs = ta.groupValues[1]
                    val name = attr(attrs, "name") ?: continue
                    values[name] = Html.decode(ta.groupValues[2])
                }
                return DateForm(action, values, options, dateInputs)
            }
        }
    }

    override fun setStatus(session: Session, link: Link, status: ReadStatus) {
        val id = link.externalId ?: throw ServiceException("Book isn't linked")
        val html = bookPage(session, link)
        if (statusFrom(html) == status) return
        val csrf = Html.csrf(html) ?: throw AuthException("StoryGraph didn't show a signed-in page; sign in again")
        val slug = when (status) {
            ReadStatus.TO_READ -> "to-read"
            ReadStatus.READING -> "currently-reading"
            ReadStatus.FINISHED -> "read"
            ReadStatus.PAUSED -> "paused"
            ReadStatus.DNF -> "did-not-finish"
        }
        val body = Http.form("authenticity_token" to csrf)
        var r = request(session, "POST", "/update-status.js?book_id=$id&status=$slug", body, ajaxHeaders(csrf, "$BASE/books/$id"))
        if (status == ReadStatus.READING && r.code == 422) {
            r = request(session, "POST", "/update-status.js?book_id=$id&status=rereading", body, ajaxHeaders(csrf, "$BASE/books/$id"))
        }
        if (!(r.ok || r.isRedirect)) throw ServiceException("StoryGraph didn't accept the status (HTTP ${r.code})")
    }

    override fun pushProgress(session: Session, link: Link, percent: Int, visibility: Visibility) {
        val id = link.externalId ?: throw ServiceException("Book isn't linked")
        val html = bookPage(session, link)
        val csrf = Html.csrf(html) ?: throw AuthException("StoryGraph didn't show a signed-in page; sign in again")
        val pages = field(html, "read_status[book_num_of_pages]") ?: "0"
        val body = Http.form(
            "read_status[progress_number]" to percent.coerceIn(0, 100).toString(),
            "read_status[progress_type]" to "percentage",
            "read_status[book_num_of_pages]" to pages,
            "book_id" to id,
            "on_book_page" to "true",
            "authenticity_token" to csrf,
        )
        val r = request(session, "POST", "/update-progress", body, ajaxHeaders(csrf, "$BASE/books/$id"))
        if (!(r.ok || r.isRedirect)) throw ServiceException("StoryGraph didn't accept the progress (HTTP ${r.code})")
    }

    // ---- adding a book StoryGraph doesn't have ----

    override val addBookPage = "$BASE/books/new"
    override val addedBookId = Regex("thestorygraph\\.com/books/([0-9a-fA-F-]{36})")
    override fun webCookies(session: Session): List<Pair<String, String>> =
        session.data.optString("cookies").split(";").map { it.trim() }.filter { it.contains('=') }.map { BASE to it }

    override val addsBooks = true
    override val addBookNote = "Added through StoryGraph's Add a Book form (/books/new); volunteer librarians may complete or review it."

    /** StoryGraph's own add-a-book form, filled from the book's details. */
    override fun addBook(session: Session, document: String, details: io.github.prithirajn.trackersync.source.BookDetails): RemoteBook {
        val page = request(session, "GET", "/books/new")
        if (!page.ok) throw ServiceException("StoryGraph didn't open its add-a-book form (HTTP ${page.code})")
        val form = io.github.prithirajn.trackersync.util.HtmlForm.find(page.body, Regex("(?i)title"))
            ?: throw ServiceException("Couldn't find StoryGraph's add-a-book form")
        form.fillBook(details)
        val action = form.action?.let { if (it.startsWith("http")) it else BASE + it } ?: "$BASE/books"
        // The cover goes up as a file when the form takes one (it's an upload, not an address).
        val image = form.coverField?.let { f -> details.coverUrl?.let { Http.bytes(it) }?.let { f to it } }
        val upload = image?.let { (f, bytes) -> form.multipart(f, bytes) }
        val r = request(session, form.method, action, if (upload == null) form.encoded() else null, mapOf(
            "Content-Type" to (upload?.second ?: "application/x-www-form-urlencoded"), "Origin" to BASE, "Referer" to "$BASE/books/new",
        ), raw = upload?.first)
        val where = r.header("location") ?: r.url
        val id = Regex("/books/([0-9a-fA-F-]{8,})").find(where)?.groupValues?.get(1)
            ?: Regex("/books/([0-9a-fA-F-]{36})").find(r.body)?.groupValues?.get(1)
            ?: throw ServiceException(
                if (r.code in 400..499)
                    "StoryGraph requires verification. Tap below to review and save."
                else "StoryGraph didn't create the book (HTTP ${r.code}" + (serverReason(r.body)?.let { ": $it" } ?: "") + ")"
            )
        // StoryGraph may only take the description and cover on the book's edit page: added there
        // when the new book's page doesn't show them yet.
        runCatching { complete(session, id, details) }.getOrNull()?.let { added[id] = it }
        return RemoteBook(id, details.title, details.author, coverUrl = details.coverUrl, pages = details.pages, year = details.year)
    }

    /** What each book added from this app still lacks (StoryGraph id -> missing details). */
    private val added = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    override fun missingAfterAdd(session: Session, document: String, remoteId: String, details: io.github.prithirajn.trackersync.source.BookDetails): List<String>? =
        added.remove(remoteId)

    /** Whether the book's page shows [details]' description (its first words) and a cover of its own. */
    private fun shows(html: String, details: io.github.prithirajn.trackersync.source.BookDetails): Pair<Boolean, Boolean> {
        val words = details.description?.let { d -> Regex("[\\p{L}\\p{N}]+").findAll(d).take(6).map { it.value.lowercase() }.toList() }.orEmpty()
        // Compared word by word, so punctuation and line breaks don't matter.
        val text = Regex("[\\p{L}\\p{N}]+").findAll(Html.text(html)).joinToString(" ") { it.value.lowercase() }
        val hasDesc = words.isEmpty() || words.joinToString(" ").let { text.contains(it) }
        // A cover image of its own (StoryGraph's covers are stored uploads), not its placeholder.
        val hasCover = details.coverUrl == null || Regex("""<img[^>]*src="([^"]+)"[^>]*>""").findAll(html).any { m ->
            val src = m.groupValues[1].lowercase()
            (src.contains("cover") || src.contains("amazonaws") || src.contains("cloudfront") || src.contains("/uploads/")) &&
                !src.contains("placeholder") && !src.contains("default") && !src.contains("missing") && !src.contains("no-cover")
        }
        return hasDesc to hasCover
    }

    /**
     * Fills in the description and cover from the new book's edit page when its page doesn't
     * show them. Returns what is still missing afterwards ("description", "cover").
     */
    private fun complete(session: Session, id: String, details: io.github.prithirajn.trackersync.source.BookDetails): List<String> {
        val page = request(session, "GET", "/books/$id")
        if (!page.ok) return emptyList()
        var (hasDesc, hasCover) = shows(page.body, details)
        if (hasDesc && hasCover) return emptyList()
        val edit = request(session, "GET", "/books/$id/edit")
        val form = if (edit.ok) io.github.prithirajn.trackersync.util.HtmlForm.find(edit.body, Regex("(?i)description|summary|title|cover|image")) else null
        if (form != null) {
            if (!hasDesc) form.setDescription(details.description)
            val image = if (!hasCover) form.coverField?.let { f -> details.coverUrl?.let { Http.bytes(it) }?.let { f to it } } else null
            if (!hasCover && image == null) form.set(Regex("(?i)image_url|cover_url|cover_image_url|remote_image"), details.coverUrl)
            val upload = image?.let { (f, bytes) -> form.multipart(f, bytes) }
            val action = form.action?.let { if (it.startsWith("http")) it else BASE + it } ?: "$BASE/books/$id"
            request(session, form.method, action, if (upload == null) form.encoded() else null, mapOf(
                "Content-Type" to (upload?.second ?: "application/x-www-form-urlencoded"), "Origin" to BASE, "Referer" to "$BASE/books/$id/edit",
            ), raw = upload?.first)
            val again = request(session, "GET", "/books/$id")
            if (again.ok) shows(again.body, details).let { hasDesc = it.first; hasCover = it.second }
        }
        return listOfNotNull(if (!hasDesc) "description" else null, if (!hasCover) "cover" else null)
    }

    override val oneEntryPerHighlight = true
    override val progressCarriesHighlights = false
    override val highlightEntriesSetProgress = true

    override fun postHighlight(
        session: Session,
        link: Link,
        percent: Int,
        clip: io.github.prithirajn.trackersync.source.Clipping,
        previousId: String?,
    ): String? {
        val note = formatHighlights(listOf(clip), 2000).firstOrNull() ?: return null
        // A journal entry at the highlight's own place in the book (26%, 28%…); your real position
        // is sent again after the entries (see highlightEntriesSetProgress).
        journalEntry(session, link, percent.coerceIn(0, 100).takeIf { it > 0 }, note, clip)
        return null
    }

    private fun journalEntry(session: Session, link: Link, progress: Int?, note: String, clip: io.github.prithirajn.trackersync.source.Clipping? = null) {
        val id = link.externalId ?: throw ServiceException("Book isn't linked")
        val html = bookPage(session, link)
        val csrf = Html.csrf(html) ?: throw AuthException("StoryGraph didn't show a signed-in page; sign in again")
        val prevPercent = percentFrom(html)
        val number = progress ?: prevPercent
        val totalPagesStr = field(html, "read_status[book_num_of_pages]") ?: field(html, "book_num_of_pages") ?: "0"
        val totalPages = totalPagesStr.toIntOrNull() ?: 0
        val prevPagesStr = field(html, "read_status[last_reached_pages]")
            ?: field(html, "last_reached_pages")
            ?: if (totalPages > 0) Math.round(prevPercent / 100.0 * totalPages).toInt().toString() else "0"
        val prevPctStr = field(html, "read_status[last_reached_percent]")
            ?: field(html, "last_reached_percent")
            ?: prevPercent.toString()

        // By page where StoryGraph knows the page count: the highlight's exact page, so two in the
        // same percent don't show the same page. Whole percent otherwise.
        val page = clip?.pageIn(totalPages)
        val now = java.util.Calendar.getInstance()
        fun body(byPage: Boolean) = Http.form(
            "authenticity_token" to csrf,
            "progress_update_date[day]" to now.get(java.util.Calendar.DAY_OF_MONTH).toString(),
            "progress_update_date[month]" to (now.get(java.util.Calendar.MONTH) + 1).toString(),
            "progress_update_date[year]" to now.get(java.util.Calendar.YEAR).toString(),
            "progress_minutes" to "",
            "progress_number" to (if (byPage) page.toString() else number.toString()),
            "progress_type" to (if (byPage) "pages" else "percentage"),
            "last_reached_pages" to prevPagesStr,
            "book_num_of_pages" to totalPagesStr,
            "last_reached_percent" to prevPctStr,
            "note" to note,
            "book_id" to id,
            "return_to" to "",
            "button" to "",
        )
        val headers = ajaxHeaders(csrf, "$BASE/books/$id") +
            ("Accept" to "text/vnd.turbo-stream.html, text/html, application/xhtml+xml")
        var r = request(session, "POST", "/update-progress-with-note", body(page != null), headers)
        if (!(r.ok || r.isRedirect) && page != null && r.code !in setOf(401, 403)) r = request(session, "POST", "/update-progress-with-note", body(false), headers)
        if (!(r.ok || r.isRedirect)) throw ServiceException("StoryGraph didn't accept the journal entry (HTTP ${r.code})")
    }

    /**
     * A reading-journal entry via /update-progress-with-note, like ShelfSync. Uses the book's
     * current percent when higher than StoryGraph's so progress and highlights land together.
     */
    override fun postHighlights(
        session: Session,
        link: Link,
        percent: Int,
        entries: List<io.github.prithirajn.trackersync.source.Clipping>,
    ) {
        for (c in entries) {
            postHighlight(session, link, percent, c, null)
        }
    }
}
