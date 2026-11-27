package io.github.prithirajn.readync.trackers

import io.github.prithirajn.readync.data.Link
import io.github.prithirajn.readync.data.ReadStatus
import io.github.prithirajn.readync.data.Visibility
import io.github.prithirajn.readync.net.AuthException
import io.github.prithirajn.readync.net.CookieJar
import io.github.prithirajn.readync.net.Http
import io.github.prithirajn.readync.net.HttpResponse
import io.github.prithirajn.readync.net.ServiceException
import io.github.prithirajn.readync.net.SkipException
import io.github.prithirajn.readync.util.str
import org.json.JSONObject

/**
 * LibraryThing, after ShelfSync 1.8.0. It has no write API, so this makes the requests its own
 * pages make, signed in with your session cookies (from signing in on its site here). It tracks
 * books its own way:
 *
 * - A book is a "work"; yours is your copy of it, there only once it's in your library. Adding one
 *   goes through the site's express add. The copy is found by searching your catalogue, then kept.
 * - There's no reading status: a status is which collection the copy is in (Currently reading, To
 *   read), plus its reading dates; a copy whose last read is finished is Read. No Paused or DNF.
 * - There's no reading progress, so nothing goes while you read; a finish dates the read, and a
 *   reread gets reading dates of its own, the earlier ones kept.
 *
 * Its site sits behind Cloudflare, which can answer with a browser check; that's said as such,
 * and nothing tries to get around it. Work pages are checked that way, so the small requests its
 * own scripts make are what's used.
 */
object LibraryThingTracker : Tracker {
    override val id = "librarything"
    override val name = "LibraryThing"
    override val signInKind = SignInKind.WEBVIEW
    override val signInUrl = "https://www.librarything.com/"
    override val cookieUrl = "https://www.librarything.com"
    override val betaNote = "Beta: follows ShelfSync's LibraryThing sync. No reading progress there; status, reading dates, rating and review go."
    override val privacy = PrivacySupport.PRIVATE_ONLY
    override val privacyNote = "Your LibraryThing catalogue's own privacy applies."
    override val readsProgress = false
    override val takesProgress = false
    override val statuses = setOf(ReadStatus.TO_READ, ReadStatus.READING, ReadStatus.FINISHED)
    override val hint = "Your shelves, reading dates, ratings and reviews"
    override val reviews = ReviewSupport.RATING_AND_TEXT
    override val takesDates = true

    private const val BASE = "https://www.librarything.com"
    private val SESSION = listOf("cookie_userid", "cookie_usernum", "cookie_userchecksum")
    private const val UA = "Readync (Android; github.com/PrithirajN)"
    private val DEFAULT_IDS = mapOf("library" to 1, "to_read" to 3, "read_unowned" to 5, "reading" to 7)

    // ---- signing in, requests ----

    override fun signInWithCookies(cookieHeader: String, userAgent: String): SignInResult {
        val all = CookieJar.parse(cookieHeader)
        if (SESSION.any { all[it].isNullOrBlank() || all[it] == "deleted" }) throw AuthException("Finish signing in to LibraryThing first")
        val s = Session(null)
        s.put("cookies", SESSION.joinToString("; ") { "$it=${all[it]}" })
        s.put("user", all["cookie_userid"])
        s.put("usernum", all["cookie_usernum"])
        return SignInResult(s.encode(), all["cookie_userid"] ?: "LibraryThing")
    }

    private fun user(session: Session) = session.data.str("user") ?: throw AuthException("Sign in to LibraryThing again")
    private fun usernum(session: Session) = session.data.str("usernum") ?: throw AuthException("Sign in to LibraryThing again")

    /** One request as the site's own scripts make it. Redirects aren't followed (a sign-in one means the session ran out). */
    private fun call(session: Session, path: String, form: List<Pair<String, Any?>>? = null, ajax: Boolean = true, method: String? = null): HttpResponse {
        val cookies = listOfNotNull(session.data.str("cookies"), session.data.str("site")).joinToString("; ")
        val headers = buildMap {
            put("User-Agent", UA); put("Accept-Language", "en")
            put("Accept", if (ajax) "*/*" else "text/html,application/xhtml+xml")
            if (cookies.isNotEmpty()) put("Cookie", cookies)
            if (ajax) put("X-Requested-With", "XMLHttpRequest")
            if (form != null) put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        }
        val body = form?.joinToString("&") { (k, v) -> Http.enc(k) + "=" + Http.enc(v?.toString() ?: "") }
        val r = Http.request(method ?: if (body != null) "POST" else "GET", BASE + path, headers, body ?: if (method == "POST") "" else null, followRedirects = false, timeoutMs = 30_000)
        // The site's own session cookie (PHPSESSID and the like) is kept alongside the account's.
        val site = CookieJar.parse(session.data.str("site") ?: "")
        if (site.absorb(r)) session.put("site", site.header())
        if (r.header("cf-mitigated") == "challenge" || (r.code == 403 && r.body.contains("challenge", true))) {
            throw io.github.prithirajn.readync.net.BusyException("LibraryThing's Cloudflare check stopped the request; will try again later")
        }
        val signIn = r.isRedirect && (r.header("location") ?: "").contains("signin", true)
        if (r.code == 401 || signIn || (r.code == 200 && Regex("LibraryThing\\.is_signed_in\\s*=\\s*0").containsMatchIn(r.body))) {
            throw AuthException("LibraryThing's sign-in ran out; sign in again")
        }
        return r
    }

    private fun ok(r: HttpResponse, what: String) {
        if (r.code in 500..599) throw io.github.prithirajn.readync.net.BusyException("LibraryThing is having trouble (HTTP ${r.code}); will try again")
        if (r.code != 200) throw ServiceException("LibraryThing $what answered HTTP ${r.code}")
    }

    private fun text(html: String?): String? = html?.let {
        io.github.prithirajn.readync.util.Html.decode(it.replace(Regex("<[^>]*>"), " ")).replace(Regex("\\s+"), " ").trim()
    }?.takeIf { it.isNotEmpty() }

    private fun input(html: String, name: String): String? =
        Regex("<input[^>]*>").findAll(html).map { it.value }.firstOrNull { it.contains("name=\"$name\"") }
            ?.let { Regex("value=\"([^\"]*)\"").find(it)?.groupValues?.get(1)?.let { v -> io.github.prithirajn.readync.util.Html.decode(v) } }

    /** HTML LibraryThing returns inside JSON. */
    private fun unescape(s: String) = s.replace(Regex("\\\\u([0-9a-fA-F]{4})")) { it.groupValues[1].toInt(16).toChar().toString() }
        .replace("\\n", "\n").replace("\\t", "\t").replace("\\r", "").replace("\\\"", "\"").replace("\\/", "/").replace("\\\\", "\\")

    // ---- searching ----

    override fun search(session: Session, title: String, author: String?): List<RemoteBook> {
        val first = author?.split('\n', '&', ';')?.firstOrNull()?.replace(",", " ")?.trim()
        var found = works(session, listOfNotNull(title, first).joinToString(" "))
        if (found.isEmpty() && !first.isNullOrBlank()) found = works(session, title)
        return found
    }

    /** Works matching [query], from the search its own page loads (base64-wrapped HTML in JSON). */
    private fun works(session: Session, query: String): List<RemoteBook> {
        val q = query.replace(Regex("\\s+"), " ").trim()
        if (q.isEmpty()) return emptyList()
        val path = "/ajax_newsearch.php?search=${Http.enc(q)}&searchtype=newwork_titles&page=1&sortchoice=0&optionidpotential=0&optionidreal=0"
        var r = call(session, path, method = "POST")
        // Without a site session it only sets one and answers with an error: once more with it.
        if (r.code == 200 && !r.body.contains("\"text\"")) r = call(session, path, method = "POST")
        ok(r, "search")
        val encoded = Regex("\"text\"\\s*:\\s*\"(.*?)\"").find(r.body)?.groupValues?.get(1) ?: return emptyList()
        val html = runCatching { String(android.util.Base64.decode(unescape(encoded), android.util.Base64.DEFAULT)) }.getOrNull() ?: return emptyList()
        val out = LinkedHashMap<String, RemoteBook>()
        for (row in Regex("(?s)<tr[^>]*>(.*?)</tr>").findAll(html).map { it.groupValues[1] }) {
            val work = Regex("data-workid=\"(\\d+)\"").find(row)?.groupValues?.get(1) ?: continue
            val item = Regex("(?s)<p class=\"item\">(.*?)</p>").find(row)?.groupValues?.get(1) ?: continue
            if (work in out) continue
            val t = text(Regex("(?s)<a[^>]*data-workid=\"\\d+\"[^>]*>(.*?)</a>").find(item)?.groupValues?.get(1)) ?: continue
            val a = text(Regex("(?s)<a[^>]*href=\"/a/[^\"]*\"[^>]*>(.*?)</a>").find(item)?.groupValues?.get(1))
            val cover = Regex("<img[^>]*src=\"([^\"]+)\"").find(row)?.groupValues?.get(1)
            val members = Regex("([\\d,]+) members").find(row)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
            out[work] = RemoteBook(work, t, a, coverUrl = cover, readers = members, popularity = members)
            remember(session, work, t, a)
        }
        return out.values.toList()
    }

    // ---- your copy and its status ----

    private fun works(session: Session): JSONObject = session.data.optJSONObject("works") ?: JSONObject()

    private fun remember(session: Session, work: String, title: String? = null, author: String? = null, copy: String? = null, forgetCopy: Boolean = false) {
        val all = works(session)
        val w = all.optJSONObject(work) ?: JSONObject()
        title?.let { if (!w.has("title")) w.put("title", it) }
        author?.let { if (!w.has("author")) w.put("author", it) }
        copy?.let { w.put("copy", it) }
        if (forgetCopy) w.remove("copy")
        session.put("works", all.put(work, w))
    }

    private data class Copy(val work: String, val id: String, val collections: Set<Int>, val reads: List<Pair<String, String>>)

    /** Your copy of the work, by searching your catalogue for its title, then its author's surname. */
    private fun findCopy(session: Session, work: String): String? {
        works(session).optJSONObject(work)?.str("copy")?.let { return it }
        val w = works(session).optJSONObject(work) ?: JSONObject()
        val terms = listOfNotNull(w.str("title"), w.str("author")?.let { a -> if (',' in a) a.substringBefore(',').trim() else a.trim().substringAfterLast(' ') })
        for (term in terms) {
            val r = call(session, "/catalog_bottom.php?view=${Http.enc(user(session))}&collection=-1&deepsearch=${Http.enc(term)}", ajax = false)
            ok(r, "catalogue")
            Regex("/work/(\\d+)/book/(\\d+)").findAll(r.body).firstOrNull { it.groupValues[1] == work }?.groupValues?.get(2)?.let {
                remember(session, work, copy = it); return it
            }
        }
        return null
    }

    /** The collections your copy is in (an empty set: no such copy), learning the account's own ids by name. */
    private fun collections(session: Session, copy: String): Set<Int> {
        val r = call(session, "/ajax_collections_popup.php", listOf("view_usernum" to usernum(session), "target" to "readync", "bookid" to copy, "is_ajax" to 1))
        ok(r, "collections")
        val ids = JSONObject()
        val inIt = HashSet<Int>()
        for (m in Regex("(?s)<input([^<]*?data-cid=\"(\\d+)\"[^<]*?)<span>([^<]*)</span>").findAll(r.body)) {
            val id = m.groupValues[2].toInt()
            when (text(m.groupValues[3])?.lowercase()) {
                "your library" -> ids.put("library", id); "to read" -> ids.put("to_read", id)
                "currently reading" -> ids.put("reading", id); "read but unowned" -> ids.put("read_unowned", id)
            }
            if (m.groupValues[1].contains("checked=\"checked\"")) inIt += id
        }
        if (ids.length() > 0) session.put("collections", ids)
        return inIt
    }

    private fun collectionId(session: Session, key: String): Int =
        session.data.optJSONObject("collections")?.optInt(key, 0)?.takeIf { it > 0 } ?: DEFAULT_IDS.getValue(key)

    /** The copy's reading dates as its edit form lists them (as entered, possibly empty). */
    private fun dateRows(session: Session, work: String, copy: String): MutableList<Pair<String, String>> {
        val r = call(session, "/ajax_lb_newwork_edit_bookfields.php?work=${Http.enc(work)}&book=${Http.enc(copy)}&type=reading_dates&book_usernum=${Http.enc(usernum(session))}",
            listOf("forceLT2" to 1, "is_lightbox_request" to 1, "is_ajax" to 1))
        ok(r, "reading dates")
        if (!r.body.contains("edit_type")) throw ServiceException("LibraryThing's reading dates couldn't be read")
        val rows = sortedMapOf<Int, Pair<String, String>>()
        for (tag in Regex("<input[^>]*>").findAll(r.body).map { it.value }) {
            val m = Regex("name=\"newdate(\\w+?)A\\[(\\d+)]\"").find(tag) ?: continue
            val i = m.groupValues[2].toInt().takeIf { it > 0 } ?: continue
            val v = io.github.prithirajn.readync.util.Html.decode(Regex("value=\"([^\"]*)\"").find(tag)?.groupValues?.get(1) ?: "")
            val row = rows[i] ?: ("" to "")
            rows[i] = when (m.groupValues[1]) { "started" -> v to row.second; "ended" -> row.first to v; else -> row }
        }
        return rows.values.toMutableList()
    }

    private fun saveRows(session: Session, work: String, copy: String, rows: List<Pair<String, String>>) {
        val form = mutableListOf<Pair<String, Any?>>("edit_work" to work, "edit_book" to copy, "book_usernum" to usernum(session), "edit_type" to "reading_dates")
        rows.forEachIndexed { i, (s, f) -> form += "newdatestartedA[${i + 1}]" to s; form += "newdateendedA[${i + 1}]" to f }
        form += "newdatestartedA[0]" to ""; form += "newdateendedA[0]" to ""
        val r = call(session, "/ajax_newwork_edit_bookfields_submit.php", form)
        ok(r, "reading dates")
        if (!r.body.contains("edit_field_reading_dates")) throw ServiceException("LibraryThing didn't save the reading dates")
    }

    private fun copyOf(session: Session, link: Link): Copy? {
        val work = link.externalId ?: return null
        val copy = findCopy(session, work) ?: return null
        val inIt = collections(session, copy)
        if (inIt.isEmpty()) { remember(session, work, forgetCopy = true); return null }
        return Copy(work, copy, inIt, dateRows(session, work, copy).filter { it.first.isNotEmpty() || it.second.isNotEmpty() })
    }

    override fun state(session: Session, link: Link): RemoteState {
        val c = copyOf(session, link) ?: return RemoteState(null, null)
        val last = c.reads.lastOrNull()
        val status = when {
            collectionId(session, "reading") in c.collections -> ReadStatus.READING
            last != null && last.second.isNotEmpty() -> ReadStatus.FINISHED
            collectionId(session, "to_read") in c.collections -> ReadStatus.TO_READ
            last != null -> ReadStatus.READING
            else -> null
        }
        return RemoteState(status, if (status == ReadStatus.FINISHED) 100 else null)
    }

    // ---- changing it ----

    /** Adds the work to your library through the site's express add, in [into]; returns your new copy. */
    private fun add(session: Session, work: String, into: List<Int>): String {
        val form = call(session, "/ajax_lb_addbooks_express_open.php", listOf("workcode" to work, "forceLT2" to 1, "is_lightbox_request" to 1, "is_ajax" to 1))
        ok(form, "add form")
        val key = input(form.body, "memcache_key")?.takeIf { it.isNotEmpty() } ?: throw ServiceException("LibraryThing's add form couldn't be read")
        val best = call(session, "/ajax_addbooks_express_show_bestwork.php?memcache_key=${Http.enc(key)}&workcode=${Http.enc(work)}&show_media_source=0",
            listOf("fancy_type[#abx_data_info]" to "full", "forceLT2" to 1, "is_ajax" to 1))
        ok(best, "edition")
        val ed = unescape(best.body)
        val q = listOf("tags" to "", "rating" to 0, "collection" to into.joinToString(","),
            "record_id" to (input(ed, "record_id") ?: ""), "source_type" to (input(ed, "source_type") ?: ""), "source_id" to (input(ed, "source_id") ?: ""),
            "workcode" to work, "isbn" to (input(ed, "isbn") ?: ""), "idealbook" to (input(ed, "idealbook") ?: ""))
            .joinToString("&") { (k, v) -> Http.enc(k) + "=" + Http.enc(v.toString()) }
        val added = call(session, "/action_addbooks_express_add_book.php?$q", listOf("forceLT2" to 1, "is_ajax" to 1))
        ok(added, "add")
        if (text(added.body)?.startsWith("Added") != true) throw ServiceException("LibraryThing couldn't add the book to your library")
        val recent = call(session, "/response_list_lt2.php", listOf("fragment" to 1, "limit" to 5, "forceLT2" to 1, "is_ajax" to 1))
        ok(recent, "recent books")
        val copy = Regex("/work/(\\d+)/book/(\\d+)").findAll(recent.body).firstOrNull { it.groupValues[1] == work }?.groupValues?.get(2)
            ?: throw ServiceException("The book was added to LibraryThing, but its copy couldn't be found")
        remember(session, work, copy = copy)
        return copy
    }

    private fun toggle(session: Session, copy: String, collection: Int, on: Boolean) {
        val r = call(session, "/ajax_collectionsToggleBook.php", listOf("bookid" to copy, "c_id" to collection, "addRemove" to if (on) 1 else 0,
            "returnUI" to 1, "containerID" to "collections$copy", "excludeContainer" to 1, "is_catalogB" to 0))
        ok(r, "collections")
        val now = Regex("collection=(\\d+)\"").findAll(r.body).any { it.groupValues[1].toInt() == collection }
        if (now != on) throw ServiceException("LibraryThing didn't update the collection")
    }

    private fun today() = java.time.LocalDate.now().toString()

    override fun setStatus(session: Session, link: Link, status: ReadStatus) {
        val work = link.externalId ?: throw SkipException("Link this book on LibraryThing first")
        val reading = collectionId(session, "reading"); val toRead = collectionId(session, "to_read"); val library = collectionId(session, "library")
        val keep = when (status) { ReadStatus.READING -> reading; ReadStatus.TO_READ -> toRead; ReadStatus.FINISHED -> library; else -> throw SkipException("LibraryThing has no ${status.label} status") }
        var copy = findCopy(session, work)
        var inIt = copy?.let { collections(session, it) }.orEmpty()
        if (copy == null || inIt.isEmpty()) {
            copy = add(session, work, if (keep == library) listOf(library) else listOf(library, keep))
            inIt = collections(session, copy)
        }
        if (keep !in inIt) toggle(session, copy, keep, true)
        for (other in listOf(reading, toRead)) if (other != keep && other in inIt) toggle(session, copy, other, false)
        when (status) {
            ReadStatus.READING -> {
                // A read starts today, unless one's open already.
                val rows = dateRows(session, work, copy)
                val last = rows.lastOrNull()
                if (!(last != null && last.first.isNotEmpty() && last.second.isEmpty())) { rows += today() to ""; saveRows(session, work, copy, rows) }
            }
            ReadStatus.FINISHED -> finish(session, work, copy, today())
            else -> Unit
        }
    }

    /** The open read finished on [date], else a read finished then added; one already finished that day is left. */
    private fun finish(session: Session, work: String, copy: String, date: String) {
        val rows = dateRows(session, work, copy)
        val last = rows.lastOrNull()
        when {
            last != null && last.first.isNotEmpty() && last.second.isEmpty() -> rows[rows.lastIndex] = last.first to date
            last != null && last.second == date -> return
            else -> rows += "" to date
        }
        saveRows(session, work, copy, rows)
    }

    /** Read again: back to Currently reading with a new read of its own; the earlier reads kept. */
    override fun startReread(session: Session, link: Link) = setStatus(session, link, ReadStatus.READING)

    /** No reading progress on LibraryThing. */
    override fun pushProgress(session: Session, link: Link, percent: Int, visibility: Visibility) {}

    override fun setDates(session: Session, link: Link, started: java.time.LocalDate?, finished: java.time.LocalDate?) {
        val work = link.externalId ?: return
        val copy = findCopy(session, work) ?: return
        val rows = dateRows(session, work, copy)
        val last = rows.lastOrNull()
        if (last != null && last.second.isEmpty()) {
            rows[rows.lastIndex] = (started?.toString() ?: last.first) to (finished?.toString() ?: "")
        } else if (finished != null && last != null && last.second == finished.toString()) {
            if (started != null && last.first.isEmpty()) rows[rows.lastIndex] = started.toString() to last.second else return
        } else {
            rows += (started?.toString() ?: "") to (finished?.toString() ?: "")
        }
        saveRows(session, work, copy, rows)
    }

    override fun setReview(session: Session, link: Link, rating: Double?, text: String?) {
        val work = link.externalId ?: throw SkipException("Link this book on LibraryThing first")
        val copy = findCopy(session, work) ?: throw SkipException("Put the book on a LibraryThing shelf first")
        val half = rating?.let { Math.round(stepRating(it) * 2).toInt().coerceIn(0, 10) }
        if (text.isNullOrBlank()) {
            half ?: return
            val r = call(session, "/ajax_setBookRating.php", listOf("uid" to "readync", "book" to copy, "editable" to 1, "container" to "rate-readync",
                "style" to 306, "rating" to half, "is_catalogB" to 0, "is_addbooksB" to 0))
            ok(r, "rating")
            return
        }
        val form = call(session, "/ajax_lb_newwork_edit_bookfields.php?work=${Http.enc(work)}&book=${Http.enc(copy)}&type=review&book_usernum=${Http.enc(usernum(session))}",
            listOf("forceLT2" to 1, "is_lightbox_request" to 1, "is_ajax" to 1))
        ok(form, "review form")
        val language = Regex("(?s)<select[^>]*name=\"form_review_language\".*?<option[^>]*value=\"(\\w+)\"[^>]*selected").find(form.body)?.groupValues?.get(1)
            ?: input(form.body, "language") ?: "eng"
        val r = call(session, "/ajax_newwork_edit_bookfields_submit.php", listOf(
            "edit_work" to work, "edit_book" to copy, "book_usernum" to usernum(session), "edit_type" to "review",
            "form_review" to io.github.prithirajn.readync.util.Markdown.toPlain(text), "form_review_language" to language,
            "form_rating" to (half ?: input(form.body, "form_rating")?.toIntOrNull() ?: 0), "language" to language,
        ))
        ok(r, "review")
        if (!r.body.contains("edit_field_review")) throw ServiceException("LibraryThing didn't save the review")
    }
}
