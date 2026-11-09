package io.github.prithirajn.trackersync.trackers

import io.github.prithirajn.trackersync.util.Markdown
import io.github.prithirajn.trackersync.data.Link
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.Visibility
import io.github.prithirajn.trackersync.net.AuthException
import io.github.prithirajn.trackersync.net.Http
import io.github.prithirajn.trackersync.net.ServiceException
import io.github.prithirajn.trackersync.net.SkipException
import io.github.prithirajn.trackersync.source.Clipping
import io.github.prithirajn.trackersync.util.arr
import io.github.prithirajn.trackersync.util.idString
import io.github.prithirajn.trackersync.util.int
import io.github.prithirajn.trackersync.util.long
import io.github.prithirajn.trackersync.util.obj
import io.github.prithirajn.trackersync.util.objects
import io.github.prithirajn.trackersync.util.str
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hardcover's official GraphQL API with a personal API token. The write logic follows
 * crosspoint-sync's src/connectors/hardcover.ts (in production on sync.crosspointreader.com):
 * change the shelf only when it differs, never re-mark a Read book, update the open read
 * instead of adding another, and turn the percentage into a page of a real edition.
 * Search and shelves follow ShelfSync's hardcover/api.lua.
 */
object HardcoverTracker : Tracker {
    override val id = "hardcover"
    override val name = "Hardcover"
    override val signInKind = SignInKind.TOKEN
    override val privacy = PrivacySupport.PER_UPDATE_FLAG
    override val readsProgress = true
    override val statuses = ReadStatus.entries.toSet()
    override val tokenHelp = "Copy your API token from hardcover.app/account/api and paste it here."
    override val privacyNote =
        "Books the app adds to your shelves, and highlights, are Private with Only me or Public with Everyone. " +
            "Books already on your shelves keep their privacy."
    override val highlights = HighlightSupport.PRIVATE_JOURNAL
    override val highlightsNote = "Added as quotes in the book's reading journal, with the same privacy as above."

    /** The API (changed only by tests, to a stand-in server). */
    internal var API = "https://api.hardcover.app/v1/graphql"
    private const val PRIVATE = 3
    private const val PUBLIC = 1

    private val statusIds = mapOf(
        ReadStatus.TO_READ to 1, ReadStatus.READING to 2, ReadStatus.FINISHED to 3,
        ReadStatus.PAUSED to 4, ReadStatus.DNF to 5,
    )
    private val statusById = statusIds.entries.associate { (k, v) -> v to k }

    private fun gql(session: Session, query: String, variables: JSONObject = JSONObject()): JSONObject =
        gql(session.data.str("token") ?: throw AuthException("Sign in to Hardcover again"), query, variables)

    private fun gql(token: String, query: String, variables: JSONObject): JSONObject {
        val bearer = if (token.startsWith("Bearer ")) token else "Bearer $token"
        val r = Http.retryGateway {
            Http.request(
                "POST", API,
                mapOf(
                    "Authorization" to bearer,
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                    "User-Agent" to "TrackerSync (Android; github.com/PrithirajN)",
                ),
                JSONObject().put("query", query).put("variables", variables).toString(),
            )
        }
        if (r.code == 401) throw AuthException("Hardcover rejected the API token; paste a new one")
        if (r.code == 429) throw ServiceException("Hardcover is rate-limiting; will retry")
        val o = runCatching { JSONObject(r.body) }.getOrNull()
            ?: throw ServiceException("Hardcover answered HTTP ${r.code}")
        val errors = o.arr("errors")?.objects() ?: emptyList()
        if (errors.isNotEmpty()) {
            val msg = errors.first().str("message") ?: "Hardcover error"
            val lower = msg.lowercase()
            if ("jwt" in lower || "token" in lower || "unauthorized" in lower || "invalid_grant" in lower) {
                throw AuthException("Hardcover rejected the API token; paste a new one")
            }
            throw ServiceException("Hardcover: $msg")
        }
        return o.obj("data") ?: throw ServiceException("Hardcover sent no data (HTTP ${r.code})")
    }

    private fun me(data: JSONObject): JSONObject? = data.arr("me")?.optJSONObject(0) ?: data.obj("me")

    override fun signInWithToken(token: String): SignInResult {
        val clean = token.trim().removePrefix("Bearer ").trim()
        if (clean.isEmpty()) throw AuthException("Paste your Hardcover API token")
        val data = gql(clean, "query { me { id username account_privacy_setting_id } }", JSONObject())
        val me = me(data) ?: throw AuthException("Hardcover didn't recognise this token")
        val username = me.str("username") ?: throw AuthException("Hardcover didn't recognise this token")
        val session = Session(null)
        session.put("token", clean)
        session.put("userId", me.idString("id"))
        return SignInResult(session.encode(), username)
    }

    private fun authorOf(book: JSONObject): String? {
        val contributions = book.arr("contributions")?.objects() ?: return null
        // The writer, not a translator, illustrator, editor or narrator listed alongside.
        val roles = Regex("(?i)translat|illustrat|editor|narrat|introduc|foreword|afterword|cover|contributor")
        fun role(c: JSONObject) = c.optString("contribution").takeIf { !c.isNull("contribution") }?.trim().orEmpty()
        val main = contributions.firstOrNull { role(it).isEmpty() || role(it).equals("Author", true) }
            ?: contributions.firstOrNull { !roles.containsMatchIn(role(it)) }
            ?: contributions.firstOrNull()
        return main?.obj("author")?.str("name")
    }

    private const val BASIC_FIELDS = "id title pages users_read_count cached_image contributions { contribution author { name } }"
    private const val RICH_FIELDS = "$BASIC_FIELDS users_count rating ratings_count reviews_count release_year"

    private fun booksQuery(fields: String) =
        "query (\$ids: [Int!]) { books(where: { id: { _in: \$ids } }) { $fields } }"

    private fun toRemote(b: JSONObject): RemoteBook? {
        val id = b.idString("id") ?: return null
        return RemoteBook(
            id = id,
            title = b.str("title") ?: "Untitled",
            author = authorOf(b),
            coverUrl = b.obj("cached_image")?.str("url"),
            pages = b.int("pages"),
            popularity = b.int("users_read_count"),
            rating = b.firstNumber("rating")?.takeIf { it > 0.0 && it <= 5.0 },
            ratingsCount = b.firstNumber("ratings_count")?.toInt(),
            reviewsCount = b.firstNumber("reviews_count")?.toInt(),
            readers = b.firstNumber("users_count", "users_read_count")?.toInt(),
            year = b.firstNumber("release_year")?.toInt(),
        )
    }

    /**
     * Hardcover's details for a book (description, pages, cover), for books with none here: the
     * book this one is matched to on Hardcover, else a clear match from its search.
     */
    fun details(session: Session, link: Link?, title: String, author: String?): io.github.prithirajn.trackersync.source.BookDetails? {
        val id = link?.externalId?.toLongOrNull() ?: run {
            val plain = io.github.prithirajn.trackersync.sync.Matcher.plainTitle(title)
            val hits = runCatching { search(session, plain, author) }.getOrNull().orEmpty()
            val d = io.github.prithirajn.trackersync.sync.Matcher.decide(plain, author, hits)
            (d.best?.takeIf { d.accepted } ?: hits.firstOrNull { io.github.prithirajn.trackersync.sync.Matcher.sameBook(title, author, it.title, it.author) })
                ?.id?.toLongOrNull()
        } ?: return null
        val data = gql(
            session,
            """query (${"$"}id: Int!) { books_by_pk(id: ${"$"}id) { id title description pages cached_image contributions { contribution author { name } } } }""",
            JSONObject().put("id", id),
        )
        val b = data.obj("books_by_pk") ?: return null
        return io.github.prithirajn.trackersync.source.BookDetails(
            title = b.str("title") ?: title,
            authors = listOfNotNull(authorOf(b)),
            pages = b.int("pages"),
            description = b.str("description")?.trim()?.takeIf { it.isNotEmpty() },
            coverUrl = b.obj("cached_image")?.str("url"),
            source = "Hardcover",
        )
    }

    /**
     * What readers say about a book on Hardcover: series, rating, year, and the moods, genres and
     * content warnings tagged most. The book matched there, else a clear match from its search.
     */
    fun facts(session: Session, link: Link?, title: String, author: String?, now: Long = System.currentTimeMillis() / 1000): io.github.prithirajn.trackersync.data.BookFacts? {
        val id = link?.externalId?.toLongOrNull() ?: run {
            val plain = io.github.prithirajn.trackersync.sync.Matcher.plainTitle(title)
            val hits = runCatching { search(session, plain, author) }.getOrNull().orEmpty()
            val d = io.github.prithirajn.trackersync.sync.Matcher.decide(plain, author, hits)
            (d.best?.takeIf { d.accepted } ?: hits.firstOrNull { io.github.prithirajn.trackersync.sync.Matcher.sameBook(title, author, it.title, it.author) })
                ?.id?.toLongOrNull()
        } ?: return null
        val vars = JSONObject().put("id", id)
        // Series first; if Hardcover ever rejects that part, the rest still comes through.
        val data = runCatching {
            gql(session, """query (${"$"}id: Int!) { books_by_pk(id: ${"$"}id) { rating ratings_count release_year cached_tags book_series(limit: 3) { position series { name } } } }""", vars)
        }.getOrElse {
            gql(session, """query (${"$"}id: Int!) { books_by_pk(id: ${"$"}id) { rating ratings_count release_year cached_tags } }""", vars)
        }
        val b = data.obj("books_by_pk") ?: return null
        return parseFacts(b, now)
    }

    /**
     * A comic's collected volumes as Hardcover numbers them in the series, with which issues each
     * collects (from its description), or null when Hardcover has no such series.
     */
    /**
     * All numbered books in a Hardcover series, in reading order, with their rating, year, release
     * date, page count, cover, description and tags — for finding the next unread book or upcoming
     * sequel on the SFF Radar.
     */
    fun seriesEntries(session: Session, seriesName: String, now: Long = System.currentTimeMillis() / 1000): List<io.github.prithirajn.trackersync.data.SffFeedItem> {
        val q = seriesName.trim()
        if (q.isEmpty()) return emptyList()
        val ids = runCatching {
            gql(session, """query (${"$"}q: String!) { search(query: ${"$"}q, per_page: 5, page: 1, query_type: "Series") { ids } }""", JSONObject().put("q", q))
                .obj("search")?.arr("ids")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).toLongOrNull() } }.orEmpty()
        }.getOrDefault(emptyList())
        val fields = """id name book_series(where: {position: {_is_null: false}}, order_by: {position: asc}, limit: 40) {
            position book { id title slug description pages release_year release_date rating ratings_count users_read_count cached_image cached_tags contributions { contribution author { name } } }
        }"""
        val seriesList = ArrayList<JSONObject>()
        if (ids.isNotEmpty()) {
            runCatching {
                gql(session, """query (${"$"}ids: [Int!]) { series(where: {id: {_in: ${"$"}ids}}) { $fields } }""", JSONObject().put("ids", JSONArray(ids)))
                    .arr("series")?.objects()?.let { seriesList.addAll(it) }
            }
        }
        if (seriesList.isEmpty()) {
            runCatching {
                gql(session, """query (${"$"}n: String!) { series(where: {name: {_eq: ${"$"}n}}, limit: 3) { $fields } }""", JSONObject().put("n", q))
                    .arr("series")?.objects()?.let { seriesList.addAll(it) }
            }
        }
        val want = io.github.prithirajn.trackersync.sync.Matcher.baseTitle(q)
        val best = seriesList.firstOrNull { io.github.prithirajn.trackersync.sync.Matcher.baseTitle(it.str("name").orEmpty()) == want }
            ?: seriesList.firstOrNull() ?: return emptyList()
        val sName = best.str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: q
        return best.arr("book_series")?.objects().orEmpty().mapNotNull { bs ->
            val posRaw = bs.opt("position")?.toString()?.toDoubleOrNull() ?: return@mapNotNull null
            val posStr = if (posRaw == Math.floor(posRaw)) posRaw.toLong().toString() else posRaw.toString()
            val b = bs.obj("book") ?: return@mapNotNull null
            val id = b.idString("id") ?: return@mapNotNull null
            val title = b.str("title") ?: return@mapNotNull null
            val facts = parseFacts(b, now)
            val slug = b.str("slug")
            io.github.prithirajn.trackersync.data.SffFeedItem(
                id = "hc-series-$id",
                category = "SERIES_HORIZON",
                source = "Hardcover",
                title = title,
                subtitle = "$sName · Book $posStr",
                summary = b.str("description")?.let { io.github.prithirajn.trackersync.util.Html.text(it) }?.take(360),
                url = slug?.let { "https://hardcover.app/books/$it" } ?: "https://hardcover.app",
                coverUrl = b.obj("cached_image")?.str("url"),
                author = authorOf(b),
                series = sName,
                seriesNumber = posStr,
                rating = facts.rating,
                ratingsCount = facts.ratingsCount,
                pages = b.int("pages"),
                year = facts.year,
                releaseDate = b.str("release_date"),
                tags = (facts.genres + facts.moods).distinct().take(6),
                externalId = id,
                publishedAt = now,
            )
        }
    }

    /**
     * Searches Hardcover for books matching [query] (a subgenre, trope, mood or author) with full
     * SFF facts (series, tags, release date, description and rating).
     */
    fun discoverBooks(session: Session, query: String, limit: Int = 10, now: Long = System.currentTimeMillis() / 1000): List<io.github.prithirajn.trackersync.data.SffFeedItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val found = runCatching {
            gql(session, """query (${"$"}q: String!, ${"$"}n: Int!) { search(query: ${"$"}q, per_page: ${"$"}n, page: 1, query_type: "Book") { ids } }""", JSONObject().put("q", q).put("n", limit))
        }.getOrNull() ?: return emptyList()
        val idsJson = found.obj("search")?.arr("ids") ?: return emptyList()
        val ids = (0 until idsJson.length()).mapNotNull { idsJson.opt(it)?.toString()?.toDoubleOrNull()?.toLong() }
        if (ids.isEmpty()) return emptyList()
        val fields = """id title slug description pages release_year release_date rating ratings_count users_read_count cached_image cached_tags book_series(limit: 2) { position series { name } } contributions { contribution author { name } }"""
        val data = runCatching {
            gql(session, booksQuery(fields), JSONObject().put("ids", JSONArray(ids)))
        }.getOrElse {
            runCatching { gql(session, booksQuery(RICH_FIELDS), JSONObject().put("ids", JSONArray(ids))) }.getOrNull() ?: return emptyList()
        }
        val order = ids.withIndex().associate { (i, v) -> v.toString() to i }
        return data.arr("books")?.objects().orEmpty().mapNotNull { b ->
            val id = b.idString("id") ?: return@mapNotNull null
            val title = b.str("title") ?: return@mapNotNull null
            val facts = parseFacts(b, now)
            val slug = b.str("slug")
            io.github.prithirajn.trackersync.data.SffFeedItem(
                id = "hc-book-$id",
                category = "DISCOVERY",
                source = "Hardcover",
                title = title,
                subtitle = facts.series?.let { s -> s + (facts.seriesNumber?.let { " · Book $it" } ?: "") } ?: authorOf(b),
                summary = b.str("description")?.let { io.github.prithirajn.trackersync.util.Html.text(it) }?.take(360),
                url = slug?.let { "https://hardcover.app/books/$it" } ?: "https://hardcover.app",
                coverUrl = b.obj("cached_image")?.str("url"),
                author = authorOf(b),
                series = facts.series,
                seriesNumber = facts.seriesNumber,
                rating = facts.rating,
                ratingsCount = facts.ratingsCount,
                pages = b.int("pages"),
                year = facts.year,
                releaseDate = b.str("release_date"),
                tags = (facts.genres + facts.moods).distinct().take(6),
                externalId = id,
                publishedAt = now,
            )
        }.sortedBy { order[it.externalId] ?: Int.MAX_VALUE }
    }

    /** One book of a series as Hardcover lists it. */
    data class SeriesBook(val position: Double, val id: String, val title: String, val releaseDate: Long?, val coverUrl: String?, val author: String?)

    /**
     * The numbered books of the series called [name] (found however it's capitalised), with their
     * release days, one per number (the main edition, not a box set or omnibus). Empty when
     * Hardcover hasn't the series.
     */
    fun seriesBooks(session: Session, name: String): List<SeriesBook> {
        val plain = io.github.prithirajn.trackersync.sync.MangaSources.seriesTitleOf(name)
        val want = io.github.prithirajn.trackersync.sync.Matcher.baseTitle(plain)
        val fields = """id name book_series(where: {position: {_is_null: false}}, order_by: {position: asc}, limit: 250) { position book { id title release_date cached_image contributions(limit: 1) { author { name } } } }"""
        val candidates = LinkedHashMap<Long, JSONObject>()
        runCatching {
            val ids = gql(session, """query (${"$"}q: String!) { search(query: ${"$"}q, per_page: 8, page: 1, query_type: "Series") { ids } }""", JSONObject().put("q", plain))
                .obj("search")?.arr("ids")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).toLongOrNull() } }.orEmpty()
            if (ids.isNotEmpty()) gql(session, """query (${"$"}ids: [Int!]) { series(where: {id: {_in: ${"$"}ids}}) { $fields } }""", JSONObject().put("ids", JSONArray(ids)))
                .arr("series")?.objects()?.forEach { o -> candidates.putIfAbsent(o.optLong("id"), o) }
        }
        for (n in listOf(plain, plain.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }).distinct()) {
            runCatching {
                gql(session, """query (${"$"}n: String!) { series(where: {name: {_eq: ${"$"}n}}, limit: 5) { $fields } }""", JSONObject().put("n", n))
                    .arr("series")?.objects()?.forEach { o -> candidates.putIfAbsent(o.optLong("id"), o) }
            }
        }
        val best = candidates.values.filter { o ->
            io.github.prithirajn.trackersync.sync.Matcher.baseTitle(io.github.prithirajn.trackersync.sync.MangaSources.seriesTitleOf(o.str("name").orEmpty())) == want &&
                !Regex("""(?i)single\s+issue""").containsMatchIn(o.str("name").orEmpty())
        }.maxByOrNull { it.arr("book_series")?.length() ?: 0 } ?: return emptyList()
        return parseSeriesBooks(best)
    }

    internal fun parseSeriesBooks(series: JSONObject): List<SeriesBook> {
        val all = series.arr("book_series")?.objects().orEmpty().mapNotNull { bs ->
            val pos = bs.opt("position")?.toString()?.toDoubleOrNull() ?: return@mapNotNull null
            val b = bs.obj("book") ?: return@mapNotNull null
            val title = b.str("title") ?: return@mapNotNull null
            val day = b.str("release_date")?.let { d -> runCatching { java.time.LocalDate.parse(d.take(10)).atTime(9, 0).atZone(java.time.ZoneId.systemDefault()).toEpochSecond() }.getOrNull() }
            val cover = b.opt("cached_image")?.let { c -> if (c is JSONObject) c.str("url") else c.toString().takeIf { it.startsWith("http") } }
            val author = b.arr("contributions")?.objects()?.firstOrNull()?.obj("author")?.str("name")
            SeriesBook(pos, b.optString("id"), title, day, cover, author)
        }
        // One per number: skip box sets and omnibuses; the earliest-listed edition otherwise.
        return all.filter { !Regex("""(?i)\b(box(ed)? set|omnibus|collection|books? \d+\s*[-–]\s*\d+)\b""").containsMatchIn(it.title) }
            .groupBy { it.position }.map { (_, eds) -> eds.firstOrNull { it.releaseDate != null } ?: eds.first() }
            .sortedBy { it.position }
    }

    fun comicVolumes(session: Session, title: String, now: Long = System.currentTimeMillis() / 1000): io.github.prithirajn.trackersync.source.ComicCollections.Map? {
        val plain = io.github.prithirajn.trackersync.sync.MangaSources.seriesTitleOf(title)
        val want = io.github.prithirajn.trackersync.sync.Matcher.baseTitle(plain)
        val fields = """id name book_series(where: {position: {_is_null: false}}, order_by: {position: asc}, limit: 150) { position book { title description } }"""
        val candidates = LinkedHashMap<Long, JSONObject>()
        // Hardcover's own series search (finds it however you typed it), then the name as typed and in title case.
        runCatching {
            val ids = gql(session, """query (${"$"}q: String!) { search(query: ${"$"}q, per_page: 8, page: 1, query_type: "Series") { ids } }""", JSONObject().put("q", plain))
                .obj("search")?.arr("ids")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).toLongOrNull() } }.orEmpty()
            if (ids.isNotEmpty()) gql(session, """query (${"$"}ids: [Int!]) { series(where: {id: {_in: ${"$"}ids}}) { $fields } }""", JSONObject().put("ids", JSONArray(ids)))
                .arr("series")?.objects()?.forEach { o -> candidates.putIfAbsent(o.optLong("id"), o) }
        }
        val titleCase = plain.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        for (n in listOf(plain, titleCase).distinct()) {
            runCatching {
                gql(session, """query (${"$"}n: String!) { series(where: {name: {_eq: ${"$"}n}}, limit: 5) { $fields } }""", JSONObject().put("n", n))
                    .arr("series")?.objects()?.forEach { o -> candidates.putIfAbsent(o.optLong("id"), o) }
            }
        }
        // Only this series (not a spin-off), and not its run of single issues; the one with the most books first.
        val fitting = candidates.values.filter { o ->
            val name = o.str("name").orEmpty()
            !Regex("""(?i)single\s+issue""").containsMatchIn(name) &&
                io.github.prithirajn.trackersync.sync.Matcher.baseTitle(io.github.prithirajn.trackersync.sync.MangaSources.seriesTitleOf(name)) == want
        }.sortedByDescending { it.arr("book_series")?.length() ?: 0 }
        for (best in fitting) {
            val books = best.arr("book_series")?.objects().orEmpty().mapNotNull { bs ->
                val pos = bs.opt("position")?.toString()?.toDoubleOrNull() ?: return@mapNotNull null
                val b = bs.obj("book") ?: return@mapNotNull null
                Triple(pos, b.str("title"), b.str("description"))
            }
            io.github.prithirajn.trackersync.source.ComicCollections.fromSeriesBooks(best.str("name") ?: plain, books, "hardcover", now)?.let { return it }
        }
        return null
    }

    /** Reads a Hardcover book's rating, year, series and tags; tags readers rarely used are left out. */
    fun parseFacts(b: JSONObject, now: Long): io.github.prithirajn.trackersync.data.BookFacts {
        val tagsRaw = b.opt("cached_tags")
        val tags = when (tagsRaw) {
            is JSONObject -> tagsRaw
            is String -> runCatching { JSONObject(tagsRaw) }.getOrNull()
            else -> null
        }
        fun top(category: String, max: Int): List<String> {
            val arr = tags?.optJSONArray(category) ?: return emptyList()
            val all = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .mapNotNull { t -> t.optString("tag").trim().takeIf { it.isNotEmpty() }?.let { it to t.optInt("count", 0) } }
            val most = all.maxOfOrNull { it.second } ?: 0
            // A tag a handful of readers used against hundreds for the top one is noise.
            return all.filter { most < 10 || it.second * 10 >= most }
                .sortedByDescending { it.second }
                .map { it.first }
                .distinctBy { it.lowercase() }
                .take(max)
        }
        val series = b.optJSONArray("book_series")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optJSONObject("series")?.optString("name")?.isNotBlank() == true }
        }
        val pos = series?.opt("position")?.let { p ->
            when (p) {
                is Number -> p.toDouble().let { d -> if (d == Math.floor(d)) d.toLong().toString() else d.toString() }
                else -> p.toString().takeIf { it.isNotBlank() && it != "null" }
            }
        }
        return io.github.prithirajn.trackersync.data.BookFacts(
            series = series?.optJSONObject("series")?.optString("name")?.trim(),
            seriesNumber = pos,
            rating = b.optDouble("rating", Double.NaN).takeIf { !it.isNaN() && it > 0 },
            ratingsCount = b.optInt("ratings_count", 0).takeIf { it > 0 },
            year = b.optInt("release_year", 0).takeIf { it > 0 },
            moods = top("Mood", 6),
            genres = top("Genre", 6),
            warnings = top("Content Warning", 12),
            ratings = b.optDouble("rating", Double.NaN).takeIf { !it.isNaN() && it > 0 }?.let { mapOf("Hardcover" to it) } ?: emptyMap(),
            sources = listOf("Hardcover"),
            at = now,
        )
    }

    override fun search(session: Session, title: String, author: String?): List<RemoteBook> {
        // A subtitle after ":" hurts Hardcover's search, as ShelfSync found.
        val q = listOfNotNull(title.substringBefore(":").trim(), author).joinToString(" ").trim()
        if (q.isEmpty()) return emptyList()
        val found = gql(
            session,
            """query (${"$"}q: String!) { search(query: ${"$"}q, per_page: 25, page: 1, query_type: "Book") { ids } }""",
            JSONObject().put("q", q),
        )
        val idsJson = found.obj("search")?.arr("ids") ?: return emptyList()
        val ids = (0 until idsJson.length()).mapNotNull { idsJson.opt(it)?.toString()?.toDoubleOrNull()?.toLong() }
        if (ids.isEmpty()) return emptyList()
        val vars = JSONObject().put("ids", JSONArray(ids))
        // Ratings, reviews and year help pick by hand. If Hardcover ever rejects one of those
        // fields, search still works with the basic set.
        val data = try {
            gql(session, booksQuery(RICH_FIELDS), vars)
        } catch (e: ServiceException) {
            gql(session, booksQuery(BASIC_FIELDS), vars)
        }
        val books = data.arr("books")?.objects()?.mapNotNull(::toRemote) ?: emptyList()
        // Keep Hardcover's relevance order.
        val order = ids.withIndex().associate { (i, v) -> v.toString() to i }
        return books.sortedBy { order[it.id] ?: Int.MAX_VALUE }
    }

    /**
     * Every book on your Hardcover shelves, with its status, rating, review and latest read
     * (start and finish days, pages), for bringing your history in. Read only: nothing changes there.
     */
    fun history(session: Session): List<LibraryBook> {
        val full = """query (${"$"}o: Int!) { me { user_books(order_by: { id: asc }, limit: 200, offset: ${"$"}o) {
              id status_id rating review_raw updated_at
              book { id title pages cached_image contributions { contribution author { name } } }
              user_book_reads(order_by: { id: desc }, limit: 1) { started_at finished_at progress_pages edition { pages } }
            } } }"""
        // The same without the fields most likely to be renamed, if Hardcover refuses the full one.
        val plain = """query (${"$"}o: Int!) { me { user_books(order_by: { id: asc }, limit: 200, offset: ${"$"}o) {
              id status_id rating
              book { id title pages contributions { contribution author { name } } }
              user_book_reads(order_by: { id: desc }, limit: 1) { started_at finished_at progress_pages }
            } } }"""
        fun dayOf(d: String?): Long? = d?.takeIf { it.length >= 10 }?.let {
            runCatching { java.time.LocalDate.parse(it.take(10)).atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toEpochSecond() }.getOrNull()
        }
        val out = ArrayList<LibraryBook>()
        var query = full
        var offset = 0
        while (offset < 10_000) {
            val data = try {
                gql(session, query, JSONObject().put("o", offset))
            } catch (e: ServiceException) {
                if (query === full && offset == 0) { query = plain; continue } else throw e
            }
            val rows = me(data)?.arr("user_books")?.objects() ?: emptyList()
            for (ub in rows) {
                val b = ub.obj("book") ?: continue
                val title = b.str("title") ?: continue
                val read = ub.arr("user_book_reads")?.optJSONObject(0)
                val pages = read?.obj("edition")?.int("pages") ?: b.int("pages")
                val status = ub.int("status_id")?.let { statusById[it] }
                val progress = read?.int("progress_pages")
                out += LibraryBook(
                    id = b.idString("id") ?: continue,
                    title = title,
                    author = authorOf(b),
                    coverUrl = b.obj("cached_image")?.str("url"),
                    status = status,
                    fraction = when {
                        status == ReadStatus.FINISHED -> 1.0
                        progress != null && (pages ?: 0) > 0 -> (progress.toDouble() / pages!!).coerceIn(0.0, 1.0)
                        else -> null
                    },
                    updatedAt = dayOf(ub.str("updated_at")) ?: 0L,
                    keys = setOfNotNull(b.idString("id")?.let { "book:$it" }),
                    finishedAt = dayOf(read?.str("finished_at")),
                    startedAt = dayOf(read?.str("started_at")),
                    rating = ub.optDouble("rating", 0.0).takeIf { it > 0.0 && it <= 5.0 },
                    pageCount = pages?.takeIf { it > 0 },
                    review = ub.str("review_raw")?.takeIf { it.isNotBlank() },
                )
            }
            if (rows.size < 200) break
            offset += 200
        }
        // Your reading journal: quotes become highlights, dated progress updates become reading days.
        val journal = runCatching { journal(session) }.getOrDefault(emptyMap())
        if (journal.isEmpty()) return out
        return out.map { lb ->
            val j = journal[lb.id] ?: return@map lb
            lb.copy(quotes = j.first, days = j.second)
        }
    }

    /**
     * Your reading journal on Hardcover, per book id: its quotes (as highlights) and its progress
     * updates, as what you'd reached by the end of each day and the pages read since the day
     * before. Empty when Hardcover doesn't share it with this token.
     */
    internal fun journal(session: Session): Map<String, Pair<List<io.github.prithirajn.trackersync.data.ImportedQuote>, Map<String, io.github.prithirajn.trackersync.data.ReadDay>>> {
        val fields = "id event entry book_id action_at created_at metadata"
        val events = """["quote", "progress_updated"]"""
        val viaMe = """query (${"$"}o: Int!) { me { reading_journals(order_by: { id: asc }, limit: 200, offset: ${"$"}o, where: { event: { _in: $events } }) { $fields } } }"""
        val viaUser = """query (${"$"}o: Int!, ${"$"}u: Int!) { reading_journals(order_by: { id: asc }, limit: 200, offset: ${"$"}o, where: { user_id: { _eq: ${"$"}u }, event: { _in: $events } }) { $fields } }"""
        val rows = ArrayList<JSONObject>()
        var userId: Int? = null
        var useMe = true
        var offset = 0
        while (offset < 20_000) {
            val page = try {
                if (useMe) me(gql(session, viaMe, JSONObject().put("o", offset)))?.arr("reading_journals")?.objects()
                else gql(session, viaUser, JSONObject().put("o", offset).put("u", userId)).arr("reading_journals")?.objects()
            } catch (e: ServiceException) {
                if (!useMe || offset > 0) break
                // Not reachable from "me": by your user id instead.
                userId = runCatching { me(gql(session, "query { me { id } }"))?.int("id") }.getOrNull() ?: break
                useMe = false
                continue
            } ?: break
            rows += page
            if (page.size < 200) break
            offset += 200
        }
        val zone = java.time.ZoneId.systemDefault()
        fun timeOf(o: JSONObject): Long? = (o.str("action_at") ?: o.str("created_at"))?.let { t ->
            runCatching { java.time.OffsetDateTime.parse(t).toEpochSecond() }.getOrNull()
                ?: runCatching { java.time.LocalDateTime.parse(t.removeSuffix("Z")).toEpochSecond(java.time.ZoneOffset.UTC) }.getOrNull()
                ?: runCatching { java.time.LocalDate.parse(t.take(10)).atTime(12, 0).atZone(zone).toEpochSecond() }.getOrNull()
        }
        fun meta(o: JSONObject): JSONObject? = when (val m = o.opt("metadata")) {
            is JSONObject -> m
            is String -> runCatching { JSONObject(m) }.getOrNull()
            else -> null
        }
        val out = HashMap<String, Pair<MutableList<io.github.prithirajn.trackersync.data.ImportedQuote>, java.util.TreeMap<String, Pair<Int?, Double?>>>>()
        for (r in rows) {
            val book = r.idString("book_id") ?: continue
            val at = timeOf(r)
            val m = meta(r)
            val slot = out.getOrPut(book) { mutableListOf<io.github.prithirajn.trackersync.data.ImportedQuote>() to java.util.TreeMap() }
            when (r.str("event")) {
                "quote" -> {
                    val text = r.str("entry")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    slot.first += io.github.prithirajn.trackersync.data.ImportedQuote(
                        text = text,
                        note = m?.str("note") ?: m?.str("comment"),
                        createdAt = at,
                        page = m?.firstNumber("page", "pages", "page_number")?.toInt()?.takeIf { it > 0 },
                        percent = m?.firstNumber("percent", "percentage", "progress")?.let { if (it > 1.0) it / 100.0 else it }?.coerceIn(0.0, 1.0),
                    )
                }
                "progress_updated" -> {
                    val day = at?.let { java.time.Instant.ofEpochSecond(it).atZone(zone).toLocalDate().toString() } ?: continue
                    val pageNow = m?.firstNumber("page", "pages", "progress_pages", "current_page")?.toInt()
                    val pct = m?.firstNumber("percent", "percentage", "progress")?.let { if (it > 1.0) it / 100.0 else it }?.coerceIn(0.0, 1.0)
                    if (pageNow == null && pct == null) continue
                    // The furthest you'd got by the end of that day.
                    val prev = slot.second[day]
                    slot.second[day] = maxOf(pageNow ?: 0, prev?.first ?: 0).takeIf { it > 0 } to (listOfNotNull(pct, prev?.second).maxOrNull())
                }
            }
        }
        return out.mapValues { (_, v) ->
            var lastPage: Int? = null
            val days = LinkedHashMap<String, io.github.prithirajn.trackersync.data.ReadDay>()
            for ((day, reached) in v.second) {
                val (page, pct) = reached
                days[day] = io.github.prithirajn.trackersync.data.ReadDay(
                    pages = if (page != null && lastPage != null) (page - lastPage!!).takeIf { it > 0 } else null,
                    reached = pct,
                    source = "Hardcover",
                )
                if (page != null) lastPage = page
            }
            v.first.toList() to days
        }.filterValues { it.first.isNotEmpty() || it.second.isNotEmpty() }
    }

    override fun shelfCandidates(session: Session): List<RemoteBook> {
        val data = gql(
            session,
            """query { me { user_books(where: { status_id: { _in: [1, 2] } }, limit: 100) {
                 book { id title pages users_read_count cached_image contributions { contribution author { name } } }
               } } }""",
        )
        return me(data)?.arr("user_books")?.objects()?.mapNotNull { it.obj("book")?.let(::toRemote) } ?: emptyList()
    }

    /** The user's copy of a book (if shelved), its open reads, and editions with page counts. */
    private class Ctx(val userBook: JSONObject?, val openReads: List<JSONObject>, val edition: Pair<Long, Int>?)

    private fun ctx(session: Session, bookId: Long): Ctx {
        val data = gql(
            session,
            """query (${"$"}bookId: Int!) {
                 me {
                   user_books(where: { book_id: { _eq: ${"$"}bookId } }, limit: 1) {
                     id status_id privacy_setting_id
                     edition { id pages }
                     user_book_reads(where: { finished_at: { _is_null: true } }, order_by: { id: desc }, limit: 5) {
                       id started_at progress_pages edition { id pages }
                     }
                   }
                 }
                 books_by_pk(id: ${"$"}bookId) {
                   default_ebook_edition { id pages }
                   default_physical_edition { id pages }
                 }
                 editions(where: { book_id: { _eq: ${"$"}bookId }, pages: { _is_null: false } },
                          order_by: { users_count: desc_nulls_last }, limit: 20) { id pages }
               }""",
            JSONObject().put("bookId", bookId),
        )
        val ub = me(data)?.arr("user_books")?.optJSONObject(0)
        val reads = ub?.arr("user_book_reads")?.objects() ?: emptyList()
        val candidates = ArrayList<JSONObject?>()
        candidates.add(reads.firstOrNull()?.obj("edition"))
        candidates.add(ub?.obj("edition"))
        candidates.add(data.obj("books_by_pk")?.obj("default_ebook_edition"))
        candidates.add(data.obj("books_by_pk")?.obj("default_physical_edition"))
        candidates.addAll(data.arr("editions")?.objects() ?: emptyList())
        val edition = candidates.firstNotNullOfOrNull { e ->
            val id = e?.long("id")
            val pages = e?.int("pages")
            if (id != null && pages != null && pages > 0) id to pages else null
        }
        return Ctx(ub, reads, edition)
    }

    private fun bookId(link: Link): Long =
        link.externalId?.toLongOrNull() ?: throw ServiceException("Link this book again on Hardcover")

    override fun state(session: Session, link: Link): RemoteState {
        val c = ctx(session, bookId(link))
        val status = c.userBook?.int("status_id")?.let { statusById[it] }
        val pages = c.openReads.firstOrNull()?.int("progress_pages")
        val total = c.edition?.second
        val percent = if (pages != null && total != null && total > 0) pages * 100 / total else if (status != null) 0 else null
        return RemoteState(status, percent)
    }

    private fun privacyId(session: Session) = if (session.visibility == Visibility.PUBLIC) PUBLIC else PRIVATE

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    override fun setStatus(session: Session, link: Link, status: ReadStatus) {
        val id = bookId(link)
        val target = statusIds[status] ?: return
        val c = ctx(session, id)
        val ub = c.userBook
        val current = ub?.int("status_id")
        if (current == target) return
        // Never move a Read book back to Reading.
        if (current == 3 && target == 2) return
        if (ub == null) {
            val data = gql(
                session,
                """mutation (${"$"}o: UserBookCreateInput!) { insert_user_book(object: ${"$"}o) { error user_book { id } } }""",
                JSONObject().put("o", JSONObject().put("book_id", id).put("status_id", target).put("privacy_setting_id", privacyId(session))),
            )
            val res = data.obj("insert_user_book")
            res?.str("error")?.let { throw ServiceException("Hardcover: $it") }
            if (res?.obj("user_book")?.long("id") == null) throw ServiceException("Hardcover didn't confirm the shelf change")
        } else {
            val data = gql(
                session,
                """mutation (${"$"}id: Int!, ${"$"}s: Int!) { update_user_book(id: ${"$"}id, object: { status_id: ${"$"}s }) { error user_book { id } } }""",
                JSONObject().put("id", ub.long("id")).put("s", target),
            )
            data.obj("update_user_book")?.str("error")?.let { throw ServiceException("Hardcover: $it") }
        }
        // Finishing closes the open read at the last page, as the server connector does.
        if (status == ReadStatus.FINISHED) {
            val after = ctx(session, id)
            val read = after.openReads.firstOrNull() ?: return
            val edition = after.edition ?: return
            writeRead(session, read, null, edition, edition.second, finishedAt = today())
        }
    }

    private fun writeRead(
        session: Session,
        openRead: JSONObject?,
        userBookId: Long?,
        edition: Pair<Long, Int>,
        pages: Int,
        finishedAt: String?,
    ) {
        val vars = JSONObject().put("pages", pages).put("editionId", edition.first).put("finishedAt", finishedAt ?: JSONObject.NULL)
        val data = if (openRead != null) {
            // Read writes replace the record, so started_at must always be sent again.
            vars.put("id", openRead.long("id")).put("startedAt", openRead.str("started_at") ?: today())
            gql(
                session,
                """mutation (${"$"}id: Int!, ${"$"}pages: Int!, ${"$"}editionId: Int!, ${"$"}startedAt: date!, ${"$"}finishedAt: date) {
                     update_user_book_read(id: ${"$"}id, object: { progress_pages: ${"$"}pages, edition_id: ${"$"}editionId,
                       started_at: ${"$"}startedAt, finished_at: ${"$"}finishedAt }) { error user_book_read { id } } }""",
                vars,
            )
        } else {
            vars.put("id", userBookId ?: throw ServiceException("No Hardcover shelf entry to add a read to")).put("startedAt", today())
            gql(
                session,
                """mutation (${"$"}id: Int!, ${"$"}pages: Int!, ${"$"}editionId: Int!, ${"$"}startedAt: date!, ${"$"}finishedAt: date) {
                     insert_user_book_read(user_book_id: ${"$"}id, user_book_read: { progress_pages: ${"$"}pages,
                       edition_id: ${"$"}editionId, started_at: ${"$"}startedAt, finished_at: ${"$"}finishedAt }) {
                       error user_book_read { id } } }""",
                vars,
            )
        }
        val res = data.obj("update_user_book_read") ?: data.obj("insert_user_book_read")
        res?.str("error")?.let { throw ServiceException("Hardcover: $it") }
        // Hardcover sometimes throttles with an empty 200: only a returned read counts as written.
        if (res?.obj("user_book_read")?.long("id") == null) throw ServiceException("Hardcover didn't confirm the progress; will retry")
    }

    // ---- finish flow: rating, review, dates ----

    override val reviews = ReviewSupport.RATING_AND_TEXT
    override val takesDates = true

    /** The shelf entry and its newest read (open or finished). */
    private fun latestRead(session: Session, bookId: Long): Pair<Long?, JSONObject?> {
        val data = gql(
            session,
            """query (${"$"}bookId: Int!) { me { user_books(where: { book_id: { _eq: ${"$"}bookId } }, limit: 1) {
                 id user_book_reads(order_by: { id: desc }, limit: 1) { id started_at finished_at progress_pages edition { id pages } }
               } } }""",
            JSONObject().put("bookId", bookId),
        )
        val ub = me(data)?.arr("user_books")?.optJSONObject(0)
        return ub?.long("id") to ub?.arr("user_book_reads")?.optJSONObject(0)
    }

    override fun setReview(session: Session, link: Link, rating: Double?, text: String?) {
        val (userBookId, _) = latestRead(session, bookId(link))
        val id = userBookId ?: throw ServiceException("Put the book on a Hardcover shelf first")
        val decl = ArrayList<String>()
        val fields = ArrayList<String>()
        val vars = JSONObject().put("id", id)
        if (rating != null) {
            decl.add("${"$"}rating: numeric"); fields.add("rating: ${"$"}rating")
            vars.put("rating", if (rating <= 0) JSONObject.NULL else stepRating(rating))
        }
        if (text != null) {
            decl.add("${"$"}review: jsonb"); fields.add("review_slate: ${"$"}review")
            vars.put("review", slate(Markdown.toPlain(text)) ?: JSONObject.NULL)
        }
        if (fields.isEmpty()) return
        val data = gql(
            session,
            "mutation (${"$"}id: Int!, ${decl.joinToString(", ")}) { update_user_book(id: ${"$"}id, object: { ${fields.joinToString(", ")} }) { error user_book { id } } }",
            vars,
        )
        val res = data.obj("update_user_book")
        res?.str("error")?.let { throw ServiceException("Hardcover: $it") }
        if (res?.obj("user_book")?.long("id") == null) throw ServiceException("Hardcover didn't confirm the review")
    }

    /** Hardcover keeps reviews as a Slate document: one paragraph per blank-line-separated block. */
    private fun slate(text: String): JSONObject? {
        val paras = text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        if (paras.isEmpty()) return null
        val children = JSONArray()
        for (p in paras) {
            children.put(
                JSONObject().put("data", JSONObject()).put("type", "paragraph").put("object", "block")
                    .put("children", JSONArray().put(JSONObject().put("text", p).put("object", "text")))
            )
        }
        return JSONObject().put("document", JSONObject().put("object", "document").put("children", children))
    }

    override fun setDates(session: Session, link: Link, started: java.time.LocalDate?, finished: java.time.LocalDate?) {
        val (userBookId, read) = latestRead(session, bookId(link))
        if (read == null) {
            // No read yet (shelved, or marked finished without one): one is made with the days.
            val ub = userBookId ?: throw ServiceException("Put the book on a Hardcover shelf first")
            val edition = runCatching { ctx(session, bookId(link)).edition }.getOrNull()
            val vars = JSONObject().put("id", ub)
                .put("startedAt", (started ?: finished)?.toString() ?: today())
                .put("finishedAt", finished?.toString() ?: JSONObject.NULL)
            val decl = StringBuilder("${"$"}id: Int!, ${"$"}startedAt: date!, ${"$"}finishedAt: date")
            val extra = StringBuilder()
            if (edition != null) {
                vars.put("editionId", edition.first); decl.append(", ${"$"}editionId: Int"); extra.append(", edition_id: ${"$"}editionId")
                if (finished != null) { vars.put("pages", edition.second); decl.append(", ${"$"}pages: Int"); extra.append(", progress_pages: ${"$"}pages") }
            }
            val data = gql(
                session,
                "mutation ($decl) { insert_user_book_read(user_book_id: ${"$"}id, user_book_read: { started_at: ${"$"}startedAt, finished_at: ${"$"}finishedAt$extra }) { error user_book_read { id } } }",
                vars,
            )
            val res = data.obj("insert_user_book_read")
            res?.str("error")?.let { throw ServiceException("Hardcover: $it") }
            if (res?.obj("user_book_read")?.long("id") == null) throw ServiceException("Hardcover didn't confirm the dates; will retry")
            return
        }
        val r = read
        val start = started?.toString() ?: r.str("started_at") ?: finished?.toString() ?: today()
        val end = finished?.toString() ?: r.str("finished_at")
        val vars = JSONObject().put("id", r.long("id")).put("startedAt", start).put("finishedAt", end ?: JSONObject.NULL)
        val pages = r.int("progress_pages")
        val edition = r.obj("edition")?.long("id")
        // A read write replaces the record: carry its pages and edition along.
        val extra = StringBuilder()
        if (pages != null) { vars.put("pages", pages); extra.append(", progress_pages: ${"$"}pages") }
        if (edition != null) { vars.put("editionId", edition); extra.append(", edition_id: ${"$"}editionId") }
        val decl = StringBuilder("${"$"}id: Int!, ${"$"}startedAt: date!, ${"$"}finishedAt: date")
        if (pages != null) decl.append(", ${"$"}pages: Int")
        if (edition != null) decl.append(", ${"$"}editionId: Int")
        val data = gql(
            session,
            "mutation ($decl) { update_user_book_read(id: ${"$"}id, object: { started_at: ${"$"}startedAt, finished_at: ${"$"}finishedAt$extra }) { error user_book_read { id } } }",
            vars,
        )
        val res = data.obj("update_user_book_read")
        res?.str("error")?.let { throw ServiceException("Hardcover: $it") }
        // Hardcover sometimes throttles with an empty 200: only a returned read counts as written.
        if (res?.obj("user_book_read")?.long("id") == null) throw ServiceException("Hardcover didn't confirm the dates; will retry")
    }

    override fun findByIsbn(session: Session, isbn: String): List<RemoteBook> {
        val field = if (isbn.length == 10) "isbn_10" else "isbn_13"
        val data = gql(
            session,
            """query (${"$"}isbn: String!) { editions(where: { $field: { _eq: ${"$"}isbn } }, limit: 5) {
                 id pages release_year image { url } book { $BASIC_FIELDS }
               } }""",
            JSONObject().put("isbn", isbn),
        )
        return data.arr("editions")?.objects()?.mapNotNull { e ->
            val b = e.obj("book")?.let(::toRemote) ?: return@mapNotNull null
            // The scanned edition's own cover and page count, not the book's default.
            b.copy(
                coverUrl = e.obj("image")?.str("url") ?: b.coverUrl,
                pages = e.int("pages") ?: b.pages,
                year = e.int("release_year") ?: b.year,
            )
        } ?: emptyList()
    }

    override fun pushProgress(session: Session, link: Link, percent: Int, visibility: Visibility) {
        val id = bookId(link)
        var c = ctx(session, id)
        if (c.userBook?.int("status_id") == 3) return
        if (c.userBook == null) {
            setStatus(session, link, ReadStatus.READING)
            c = ctx(session, id)
        }
        val edition = c.edition ?: throw SkipException(
            "Hardcover has no page count for this book, so only its shelf syncs. Add one to any edition on hardcover.app."
        )
        val total = edition.second
        // Round up, so Hardcover's own rounding shows the same percentage as CrossPoint.
        val pages = Math.ceil(percent.coerceIn(0, 100) * total / 100.0).toInt().coerceIn(0, total)
        writeRead(session, c.openReads.firstOrNull(), c.userBook?.long("id"), edition, pages, finishedAt = null)
        // An older duplicate open read (a race with Hardcover's own auto-created read) is removed.
        for (dup in c.openReads.drop(1)) {
            dup.long("id")?.let { dupId ->
                runCatching {
                    gql(session, """mutation (${"$"}id: Int!) { delete_user_book_read(id: ${"$"}id) { id } }""", JSONObject().put("id", dupId))
                }
            }
        }
    }

    /** Each highlight becomes a "quote" reading-journal entry, dated when it was made. */
    override fun postHighlights(session: Session, link: Link, percent: Int, entries: List<Clipping>) {
        for (c in entries.sortedBy { it.createdAt }) insertQuote(session, bookId(link), c)
    }

    private fun quoteEntry(c: Clipping): String? {
        val text = c.text.trim()
        if (text.isEmpty()) return null
        return c.note?.trim()?.takeIf { it.isNotEmpty() }?.let { "$text\n\n$it" } ?: text
    }

    /** One journal quote; returns its id. */
    // ---- adding a book Hardcover doesn't have ----

    override val addsBooks = true
    override val addBookNote = "Open Hardcover to add the book with pre-filled details."
    override val addBookPage = "https://hardcover.app/books/new"
    // A book's page is hardcover.app/books/<its name, e.g. a-deadly-education> (not the add page itself).
    override val addedBookId = Regex("""hardcover\.app/books/(?!new(?:[/?#]|$))([a-z0-9][a-z0-9-]*)""")

    /** The book's id for its address name ("a-deadly-education" -> 123456). */
    override fun resolveAddedId(session: Session, found: String): String {
        if (found.all { it.isDigit() }) return found
        val data = gql(session, """query (${"$"}s: String!) { books(where: { slug: { _eq: ${"$"}s } }, limit: 1) { id } }""", JSONObject().put("s", found))
        return data.arr("books")?.optJSONObject(0)?.idString("id")
            ?: throw ServiceException("Couldn't find the new book on Hardcover yet; tap Change on the book to match it")
    }

    private val NO_ADD = "Hardcover requires adding books on site. Tap below to finish on their pre-filled page."

    /** A type reference from introspection, without its NON_NULL / LIST wrappers: (kind, name). */
    private fun named(t: JSONObject?): Pair<String?, String?> {
        var x = t
        while (x != null && (x.str("kind") == "NON_NULL" || x.str("kind") == "LIST") && x.obj("ofType") != null) x = x.obj("ofType")
        return x?.str("kind") to x?.str("name")
    }

    private val TYPE_REF = "kind name ofType { kind name ofType { kind name ofType { kind name } } }"

    /**
     * Logged-in readers can add books on hardcover.app; its API isn't documented for it, so the
     * create-book mutation is found by asking the API what it offers, and filled by field name.
     */
    override fun addBook(session: Session, document: String, details: io.github.prithirajn.trackersync.source.BookDetails): RemoteBook {
        val schema = runCatching {
            gql(session, "{ __schema { mutationType { fields { name args { name type { $TYPE_REF } } type { $TYPE_REF } } } } }")
        }.getOrNull() ?: throw ServiceException(NO_ADD)
        val fields = schema.obj("__schema")?.obj("mutationType")?.arr("fields")?.objects() ?: throw ServiceException(NO_ADD)
        val m = fields.firstOrNull { Regex("(?i)^(insert_book|create_book|createbook|book_create|insert_books_one|add_book|addbook)$").matches(it.str("name") ?: "") }
            ?: throw ServiceException(NO_ADD)
        val arg = m.arr("args")?.objects()?.firstOrNull { named(it.obj("type")).first == "INPUT_OBJECT" } ?: throw ServiceException(NO_ADD)
        val inputName = named(arg.obj("type")).second ?: throw ServiceException(NO_ADD)
        val input = gql(session, "query (${"$"}n: String!) { __type(name: ${"$"}n) { inputFields { name type { $TYPE_REF } } } }", JSONObject().put("n", inputName))
            .obj("__type")?.arr("inputFields")?.objects() ?: throw ServiceException(NO_ADD)
        val obj = JSONObject()
        for (f in input) {
            val name = f.str("name") ?: continue
            val (kind, type) = named(f.obj("type"))
            val isList = f.obj("type")?.let { t -> t.str("kind") == "LIST" || t.obj("ofType")?.str("kind") == "LIST" } == true
            val v: Any? = when {
                kind != "SCALAR" -> null
                name == "title" -> details.title
                name == "subtitle" -> details.subtitle
                name in setOf("pages", "page_count", "num_pages") -> details.pages
                name == "release_date" -> details.published?.let { if (it.length == 4) "$it-01-01" else if (it.length == 7) "$it-01" else it }
                name == "release_year" -> details.year
                name in setOf("description", "summary") -> details.description
                name == "isbn_13" -> details.isbn13
                name == "isbn_10" -> details.isbn10
                name in setOf("image_url", "cover_url", "image") && type == "String" -> details.coverUrl
                name in setOf("edition_format", "physical_format", "format") -> details.format
                name in setOf("author_names", "authors") && isList -> JSONArray(details.authors)
                name in setOf("author_name", "author") && type == "String" -> details.author
                name in setOf("publisher_name", "publisher") && type == "String" -> details.publisher
                name in setOf("series_name") -> details.series
                else -> null
            }
            if (v != null) obj.put(name, v)
        }
        if (!obj.has("title")) throw ServiceException(NO_ADD)
        // What comes back: an id, or the book (or edition) with one.
        val (_, returns) = named(m.obj("type"))
        val out = returns?.let { n ->
            gql(session, "query (${"$"}n: String!) { __type(name: ${"$"}n) { fields { name type { $TYPE_REF } } } }", JSONObject().put("n", n))
                .obj("__type")?.arr("fields")?.objects()
        } ?: emptyList()
        val pick = buildList {
            out.firstOrNull { it.str("name") == "id" }?.let { add("id") }
            out.firstOrNull { it.str("name") == "book_id" }?.let { add("book_id") }
            out.firstOrNull { it.str("name") == "book" && named(it.obj("type")).first == "OBJECT" }?.let { add("book { id }") }
            out.firstOrNull { it.str("name") == "errors" }?.let { add("errors") }
            out.firstOrNull { it.str("name") == "error" }?.let { add("error") }
        }.ifEmpty { listOf("__typename") }
        val res = gql(
            session,
            "mutation (${"$"}o: $inputName!) { ${m.str("name")}(${arg.str("name")}: ${"$"}o) { ${pick.joinToString(" ")} } }",
            JSONObject().put("o", obj),
        ).obj(m.str("name")!!)
        val id = res?.obj("book")?.idString("id") ?: res?.idString("book_id") ?: res?.idString("id")
            ?: throw ServiceException("Hardcover didn't add the book" + (res?.opt("errors")?.toString()?.take(160)?.let { ": $it" } ?: res?.str("error")?.let { ": $it" } ?: ""))
        return RemoteBook(id, details.title, details.author, coverUrl = details.coverUrl, pages = details.pages, year = details.year)
    }

    /** The edition you're reading on Hardcover (its id and page count): the current read's, else the shelf's. */
    private fun readingEdition(session: Session, bookId: Long): Pair<Long?, Int?> {
        val data = runCatching {
            gql(
                session,
                """query (${"$"}bookId: Int!) { me { user_books(where: { book_id: { _eq: ${"$"}bookId } }, limit: 1) {
                     edition { id pages } user_book_reads(order_by: { id: desc }, limit: 1) { edition { id pages } }
                   } } }""",
                JSONObject().put("bookId", bookId),
            )
        }.getOrNull() ?: return null to null
        val ub = me(data)?.arr("user_books")?.optJSONObject(0) ?: return null to null
        val ed = ub.arr("user_book_reads")?.optJSONObject(0)?.obj("edition") ?: ub.obj("edition")
        return ed?.long("id") to ed?.int("pages")?.takeIf { it > 0 }
    }

    /**
     * A journal quote the way Hardcover's own apps write one: the edition you're reading, the
     * page it's on (`metadata.position`, as the Hardcover KOReader plugin sends it), and when you
     * made it, to the minute.
     */
    private fun quoteObject(session: Session, bookId: Long, c: Clipping, entry: String): JSONObject {
        val obj = JSONObject()
            .put("book_id", bookId)
            .put("event", "quote")
            .put("entry", entry)
            .put("privacy_setting_id", privacyId(session))
            .put("tags", JSONArray())
        val (editionId, editionPages) = readingEdition(session, bookId)
        editionId?.let { obj.put("edition_id", it) }
        // The page in that edition, from where the highlight sits in the book.
        val page = when {
            c.percent != null && editionPages != null -> Math.round(c.percent * editionPages).toInt().coerceIn(1, editionPages)
            else -> c.page
        }
        if (page != null) {
            val position = JSONObject().put("type", "pages").put("value", page)
            editionPages?.let { position.put("possible", it) }
            obj.put("metadata", JSONObject().put("position", position))
        }
        if (c.createdAt > 0) {
            obj.put("action_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .format(Date(c.createdAt * 1000)))
        }
        return obj
    }

    private fun insertQuote(session: Session, bookId: Long, c: Clipping): String? {
        val entry = quoteEntry(c) ?: return null
        val obj = quoteObject(session, bookId, c, entry)
        val data = gql(
            session,
            """mutation (${"$"}o: ReadingJournalCreateType!) { insert_reading_journal(object: ${"$"}o) { id errors } }""",
            JSONObject().put("o", obj),
        )
        val res = data.obj("insert_reading_journal")
        val id = res?.idString("id")
        if (id == null) {
            val err = res?.arr("errors")?.let { if (it.length() > 0) it.opt(0)?.toString() else null }
            throw ServiceException("Hardcover didn't save the quote" + (err?.let { ": $it" } ?: ""))
        }
        return id
    }

    /** Journal quotes can be edited: a resend replaces the quote's text (else posts a new one). */
    override val updatesHighlights = true
    override val oneEntryPerHighlight = true

    override val deletesHighlights = true

    override fun deleteHighlight(session: Session, remoteId: String) {
        val id = remoteId.toLongOrNull() ?: throw ServiceException("Not a Hardcover journal entry")
        val data = gql(session, """mutation (${"$"}id: Int!) { delete_reading_journal(id: ${"$"}id) { id } }""", JSONObject().put("id", id))
        if (data.opt("delete_reading_journal") == null) throw ServiceException("Hardcover didn't delete the quote")
    }

    override fun postHighlight(session: Session, link: Link, percent: Int, clip: Clipping, previousId: String?): String? {
        val prev = previousId?.toLongOrNull()
        val entry = quoteEntry(clip) ?: return null
        if (prev != null) {
            val updated = runCatching {
                val data = gql(
                    session,
                    """mutation (${"$"}id: Int!, ${"$"}o: ReadingJournalUpdateType!) { update_reading_journal(id: ${"$"}id, object: ${"$"}o) { id errors } }""",
                    JSONObject().put("id", prev).put("o", quoteObject(session, bookId(link), clip, entry).apply { remove("book_id"); remove("event"); remove("tags") }),
                )
                data.obj("update_reading_journal")?.idString("id")
            }.getOrNull()
            if (updated != null) return updated
        }
        return insertQuote(session, bookId(link), clip)
    }
}
