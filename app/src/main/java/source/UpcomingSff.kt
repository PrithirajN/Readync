package io.github.prithirajn.readync.source

import io.github.prithirajn.readync.data.SffFeedItem
import io.github.prithirajn.readync.net.Http
import io.github.prithirajn.readync.sync.Matcher
import io.github.prithirajn.readync.util.Html
import java.time.LocalDate
import java.time.ZoneId

/**
 * Upcoming speculative fiction beyond your own series: Reactor's monthly lists of every new
 * fantasy, science fiction, horror, YA, romantasy and crossover book (publisher copy, with the day
 * each is out), and books announced in the news and social posts the News tab already gathers (a
 * cover reveal, an excerpt, an announcement) when the post says the day it's out. Reactor covers
 * traditional publishing, so upcoming LitRPG and progression fantasy come from the publishers that
 * put most of it out (Aethon, Shadow Alley Press): their sites' open book catalogues give each
 * book's day, series and number, pages, cover and Kindle id.
 */
object UpcomingSff {
    /** Reactor's "new releases" tag, as a feed: the monthly lists, with the whole post in each item. */
    const val REACTOR_FEED = "https://reactormag.com/tag/new-releases/feed/"

    /** The genres Reactor lists by, then LitRPG, manga and comics (from their own sources), as shown here, in the order the chips show them. */
    val GENRES = listOf("Fantasy", "Science fiction", "Horror", "Young adult", "Romantasy", "Crossover", LITRPG, MANGA, COMICS)

    const val LITRPG = "LitRPG"
    const val MANGA = "Manga"
    const val COMICS = "Comics"

    /** The chip for books announced in the news and on socials (they have no genre). */
    const val NEWS = "In the news"

    /** A book due out: what it is, when, where it was seen, and the publisher's few lines about it. */
    data class Book(
        val title: String,
        val series: String?,
        val number: String?,
        val author: String?,
        val publisher: String?,
        /** Its day (unix seconds, 9 am local). */
        val day: Long,
        val genre: String?,
        val url: String?,
        val cover: String?,
        val blurb: String?,
        val source: String,
        /** Who translated it, when the list says ("…, translated by Sophia Hersi Smith"). */
        val translators: List<String> = emptyList(),
    )

    private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private val MONTH = MONTHS.joinToString("|")

    private fun dayOf(year: Int, month: Int, day: Int): Long? =
        runCatching { LocalDate.of(year, month, day).atTime(9, 0).atZone(ZoneId.systemDefault()).toEpochSecond() }.getOrNull()

    /** "SFF Young Adult" → "Young adult": Reactor's list names as the chips name them. */
    internal fun genreOf(listName: String): String? {
        val n = listName.lowercase()
        return when {
            "young adult" in n || n.endsWith(" ya") -> "Young adult"
            "romantasy" in n -> "Romantasy"
            "crossover" in n -> "Crossover"
            "horror" in n -> "Horror"
            "science fiction" in n -> "Science fiction"
            "fantasy" in n -> "Fantasy"
            else -> null
        }
    }

    /** A cover from the book's Amazon id (its ISBN-10 or ASIN, in the list's buy link). */
    internal fun coverFor(link: String?): String? =
        amazonId(link)?.let { "https://images-na.ssl-images-amazon.com/images/P/$it.01.LZZZZZZZ.jpg" }

    /** The ISBN-10 or Kindle id in an Amazon link: /dp/081124038X, /gp/product/081124038X, /product/B0…. */
    fun amazonId(link: String?): String? =
        link?.let { Regex("""/(?:dp|gp/product|product|ASIN)/([A-Z0-9]{10})(?:[/?]|$)""").find(it)?.groupValues?.get(1) }

    private val ROMAN = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100)

    /** "V" → 5, "12" → 12: a book's number as a list writes it. */
    internal fun numberOf(s: String): String? {
        s.toIntOrNull()?.let { return it.toString() }
        if (!Regex("^[IVXLC]+$").matches(s)) return null
        var total = 0; var prev = 0
        for (ch in s.reversed()) { val v = ROMAN[ch] ?: return null; if (v < prev) total -= v else { total += v; prev = v } }
        return total.toString()
    }

    /**
     * A LitRPG publisher on the ModFarm book plugin for WordPress: its open catalogue
     * (`/wp-json/wp/v2/book`), filtered by its own genre ids for LitRPG, progression, cultivation
     * and dungeon core.
     */
    data class LitRpgPress(val name: String, val site: String, val genres: List<Int>)

    val LITRPG_PRESSES = listOf(
        LitRpgPress("Aethon Books", "https://aethonbooks.com", listOf(20)),
        LitRpgPress("Shadow Alley Press", "https://shadowalleypress.com", listOf(164, 13, 28)),
    )

    /**
     * Upcoming (and the last few weeks') LitRPG: each press's catalogue, LitRPG Radar's release
     * calendar (every publisher and the self-published, ebook and print) and SequelWatch's dated
     * sequels. A source that doesn't answer is skipped; the same book from two of them is folded
     * into one when it's saved (Releases.checkSff).
     */
    fun fetchLitRpg(now: Long = System.currentTimeMillis() / 1000): List<Book> =
        LITRPG_PRESSES.flatMap { p -> runCatching { fetchPress(p, now) }.getOrDefault(emptyList()) } +
            runCatching { fetchRadar() }.getOrDefault(emptyList()) +
            runCatching { fetchSequelWatch() }.getOrDefault(emptyList())

    private fun get(url: String, accept: String = "text/html,*/*"): String? = runCatching {
        Http.request("GET", url, mapOf("Accept" to accept, "User-Agent" to DESKTOP), timeoutMs = 25_000)
    }.getOrNull()?.takeIf { it.ok }?.body

    private fun cells(row: String): List<String> =
        Regex("""(?s)<t[dh][^>]*>(.*?)</t[dh]>""").findAll(row).map { it.groupValues[1] }.toList()

    private fun plain(fragment: String): String = Html.decode(fragment.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()

    const val RADAR = "https://www.litrpgradar.com/release-calendar"

    /** LitRPG Radar's calendar: a table of day, book (linked), author, series, format. Audiobooks left out (the same book). */
    fun fetchRadar(): List<Book> = get(RADAR)?.let(::parseRadar).orEmpty()

    internal fun parseRadar(html: String): List<Book> {
        val seen = HashSet<String>()
        return Regex("""(?s)<tr[^>]*>(.*?)</tr>""").findAll(html).mapNotNull { m ->
            val c = cells(m.groupValues[1])
            if (c.size < 5) return@mapNotNull null
            val day = dayOfIso(plain(c[0])) ?: return@mapNotNull null
            if (plain(c[4]).contains("audio", true)) return@mapNotNull null
            val raw = plain(c[1]).ifBlank { return@mapNotNull null }
            val series = plain(c[3]).ifBlank { null }
            val (title, number) = cleanLitRpgTitle(raw, series)
            if (!seen.add("${title.lowercase()}|$day")) return@mapNotNull null
            val href = Regex("""href="(/book/[^"]+)"""").find(c[1])?.groupValues?.get(1)
            Book(
                title = title, series = series, number = number, author = plain(c[2]).ifBlank { null }, publisher = null,
                day = day, genre = LITRPG, url = href?.let { "https://www.litrpgradar.com$it" }, cover = null, blurb = null, source = "LitRPG Radar",
            )
        }.toList()
    }

    /**
     * A store title as a book's own: "Hell Difficulty Tutorial 8: A LitRPG Adventure" → ("Hell
     * Difficulty Tutorial 8", 8); "Backstreet Evolutionist (Book 6): A Progression Fantasy Adventure
     * Series" → ("Backstreet Evolutionist", 6). The selling line after the colon and the series in
     * brackets go; the number is taken from "Book n", "#n", "Vol. n" or a number right after the series.
     */
    internal fun cleanLitRpgTitle(raw: String, series: String?): Pair<String, String?> {
        val number = Regex("""(?i)\b(?:book|vol(?:ume)?\.?|part|#)\s*(\d{1,3})\b""").find(raw)?.groupValues?.get(1)
            ?: series?.let { sr -> Regex("(?i)^" + Regex.escape(sr) + """[\s,:]+(\d{1,3})\b""").find(raw)?.groupValues?.get(1) }
            ?: Regex("""^[^:(]*?\s(\d{1,3})\s*(?=[:(]|$)""").find(raw)?.groupValues?.get(1)
        var t = raw
        // "…: A LitRPG Adventure", "…: An Isekai Progression Fantasy" and the like.
        repeat(3) { t = t.replace(Regex("""(?i)\s*:\s*(?:an?|the)\s[^:]*(?:litrpg|gamelit|progression|fantasy|adventure|isekai|cultivation|xianxia|apocalypse|dungeon|series|epic|novel|nightmare|crafting|system|regressor|sci-fi|space|portal|harem)[^:]*$"""), "") }
        // "…: Book 5" at the end, then a selling line after a colon ("Infinite Forge 8: OP MC Cozy Crafting Slice of Life Litrpg").
        t = t.replace(Regex("""(?i)\s*:\s*(?:book|vol(?:ume)?\.?|part)\s*\d+\s*$"""), "")
        repeat(2) { t = t.replace(Regex("""(?i)^(.{3,}?):\s+[^:]*\b(?:litrpg|gamelit|progression|cultivation|isekai|xianxia|dungeon core|apocalypse|adventure|fantasy)\b[^:]*$"""), "$1") }
        t = t.replace(Regex("""\s*\([^)]*\)"""), "").replace(Regex("""\s+:\s+"""), ": ").replace(Regex("\\s+"), " ").trim().trimEnd(':', ',').trim()
        return t.ifBlank { raw } to number
    }

    /** SequelWatch's dated sequels ("Memory's Flame (The Wandering Inn #20)"), with each series' genre from its front page. */
    fun fetchSequelWatch(): List<Book> {
        val list = get("https://sequelwatch.com/releases/") ?: return emptyList()
        val genres = get("https://sequelwatch.com/")?.let { home ->
            Regex("""(?s)<a[^>]*class="card"[^>]*href="(/series/[^"]+)"[^>]*>(.*?)</a>|<a[^>]*href="(/series/[^"]+)"[^>]*class="card"[^>]*>(.*?)</a>""").findAll(home).mapNotNull { m ->
                val href = m.groupValues[1].ifBlank { m.groupValues[3] }
                val inner = m.groupValues[2].ifBlank { m.groupValues[4] }
                val g = Regex("""class="g"[^>]*>([^<]+)<""").find(inner)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
                val by = Regex("""<small>([^<]+)</small>""").find(inner)?.groupValues?.get(1)?.let { Html.decode(it).trim() }
                href to (g to by)
            }.toMap()
        }.orEmpty()
        return parseSequelWatch(list, genres)
    }

    internal fun parseSequelWatch(html: String, genres: Map<String, Pair<String, String?>>): List<Book> =
        Regex("""(?s)<li>\s*<time datetime="(\d{4}-\d{2}-\d{2})"[^>]*>.*?</time>\s*<div>(.*?)</div>\s*</li>""").findAll(html).mapNotNull { m ->
            val day = dayOfIso(m.groupValues[1]) ?: return@mapNotNull null
            val body = m.groupValues[2]
            if (Regex("(?i)·\\s*audiobook\\b").containsMatchIn(plain(body)) && !plain(body).contains("Ebook", true)) return@mapNotNull null
            val href = Regex("""href="(/series/[^"]+)"""").find(body)?.groupValues?.get(1)
            val full = Regex("""(?s)<strong>(.*?)</strong>""").find(body)?.groupValues?.get(1)?.let(::plain) ?: return@mapNotNull null
            val sm = Regex("""^(.*?)\s*\(([^()]+?)\s*#\s*([\d.]+)\)\s*$""").find(full)
            val title = sm?.groupValues?.get(1)?.trim() ?: full
            val series = sm?.groupValues?.get(2)?.trim() ?: Regex("""^(.*?)\s+(\d{1,3})$""").find(full)?.groupValues?.get(1)
            val number = sm?.groupValues?.get(3) ?: Regex("""\s(\d{1,3})$""").find(full)?.groupValues?.get(1)
            val g = href?.let { genres[it]?.first }
            val genre = when {
                g == null -> LITRPG
                g.contains("litrpg", true) || g.contains("progression", true) -> LITRPG
                else -> genreOf(g) ?: LITRPG
            }
            Book(
                title = title, series = series, number = number, author = href?.let { genres[it]?.second }, publisher = null, day = day, genre = genre,
                url = href?.let { "https://sequelwatch.com$it" }, cover = null, blurb = null, source = "SequelWatch",
            )
        // One book in several formats (ebook now, paperback next year): its first day only.
        }.toList().distinctBy { (it.series?.let { s -> Matcher.baseTitle(s) } ?: Matcher.baseTitle(it.title)) + "|" + (it.number ?: it.title) }

    /** Upcoming manga in English: VIZ's release calendar (this month and the next three) and Seven Seas' release dates. */
    fun fetchManga(now: Long = System.currentTimeMillis() / 1000): List<Book> {
        val month = java.time.Instant.ofEpochSecond(now).atZone(ZoneId.systemDefault()).toLocalDate().withDayOfMonth(1)
        val viz = (0..3).flatMap { i ->
            val m = month.plusMonths(i.toLong())
            get("https://www.viz.com/calendar/${m.year}/${"%02d".format(m.monthValue)}")?.let { parseViz(it, m.year, m.monthValue) }.orEmpty()
        }
        val seas = get("https://sevenseasentertainment.com/release-dates/")?.let(::parseSevenSeas).orEmpty()
        return viz + seas
    }

    private val MON = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** A volume title split: "Blue Box, Vol. 22" → ("Blue Box", "22"). */
    internal fun volumeOf(title: String): Pair<String?, String?> {
        val m = Regex("""(?i)^(.*?)[,:]?\s*(?:\((?:manga|manhwa|manhua|light novel|webtoon)\)\s*)?(?:,\s*)?vol(?:ume)?\.?\s*(\d{1,3})\b""").find(title) ?: return null to null
        return m.groupValues[1].replace(Regex("""\s*\((?:manga|manhwa|manhua|webtoon)\)\s*""", RegexOption.IGNORE_CASE), " ").trim().trimEnd(',', ':').trim() to m.groupValues[2]
    }

    /** VIZ's calendar table: cover (named by ISBN-10), title, "Nov 03". */
    internal fun parseViz(html: String, year: Int, month: Int): List<Book> {
        val table = html.substringAfter("id=\"m-table\"", "").ifBlank { return emptyList() }.substringBefore("</table>")
        return Regex("""(?s)<tr>(.*?)</tr>""").findAll(table).mapNotNull { m ->
            val row = m.groupValues[1]
            val link = Regex("""class="color-off-black[^"]*" href="([^"]+)">([^<]+)</a>""").find(row) ?: return@mapNotNull null
            val title = Html.decode(link.groupValues[2]).trim()
            val d = Regex("""(?s)<td class="product-table--primary">\s*([A-Z][a-z]{2})\s+(\d{1,2})\s*</td>""").find(row) ?: return@mapNotNull null
            val mo = MON.indexOf(d.groupValues[1]) + 1
            if (mo <= 0) return@mapNotNull null
            // A December list can run into January.
            val y = if (mo < month && month == 12) year + 1 else year
            val day = dayOf(y, mo, d.groupValues[2].toInt()) ?: return@mapNotNull null
            val cover = Regex("""data-original="([^"]+)"""").find(row)?.groupValues?.get(1)
            val isbn10 = cover?.let { Regex("""/products/(\d{9}[\dX])\.""").find(it)?.groupValues?.get(1) }
            val (series, number) = volumeOf(title)
            Book(
                title = title, series = series, number = number, author = null, publisher = "VIZ Media", day = day, genre = MANGA,
                url = isbn10?.let { "https://www.amazon.com/dp/$it" } ?: "https://www.viz.com" + link.groupValues[1],
                cover = cover, blurb = null, source = "VIZ Media",
            )
        }.toList()
    }

    /** Seven Seas' release table: day, title, format, ISBN. Only the comics (manga, manhwa, manhua, OEL). */
    internal fun parseSevenSeas(html: String): List<Book> =
        Regex("""(?s)<tr id="volumes">(.*?)</tr>""").findAll(html).mapNotNull { m ->
            val c = cells(m.groupValues[1])
            if (c.size < 3) return@mapNotNull null
            val format = plain(c[2])
            if (format !in setOf("Manga", "Manhwa", "Manhua", "OEL")) return@mapNotNull null
            val day = dayOfIso(plain(c[0])) ?: return@mapNotNull null
            val title = plain(c[1]).ifBlank { return@mapNotNull null }
            val link = Regex("""href="([^"]+)"""").find(c[1])?.groupValues?.get(1)
            val isbn = c.getOrNull(3)?.let(::plain)?.replace("-", "")?.takeIf { Regex("^97[89]\\d{10}$").matches(it) }
            val (series, number) = volumeOf(title)
            Book(
                title = title, series = series, number = number, author = null, publisher = "Seven Seas", day = day, genre = MANGA,
                url = isbn?.let { isbn10(it) }?.let { "https://www.amazon.com/dp/$it" } ?: link, cover = isbn?.let { isbn10(it) }?.let { coverFor("/dp/$it") },
                blurb = null, source = "Seven Seas",
            )
        }.toList()

    /** 978… → its ISBN-10 (Amazon's id for a print book). */
    internal fun isbn10(isbn13: String): String? {
        if (!isbn13.startsWith("978") || isbn13.length != 13) return null
        val core = isbn13.substring(3, 12)
        val sum = core.mapIndexed { i, ch -> (10 - i) * (ch - '0') }.sum()
        val check = (11 - sum % 11) % 11
        return core + if (check == 10) "X" else check.toString()
    }

    // ---- Metron (metron.cloud): the community comics database, with your own API token ----

    const val METRON = "https://metron.cloud/api"

    /** Thrown when Metron refuses the token, so the app can say so rather than finding nothing. */
    class MetronRefused : Exception("Metron didn't accept the API token")

    /**
     * Every page of a Metron list (it pages with a ready-made `next` address), waiting between
     * pages to stay well under its 20-a-minute limit, and stopping at [maxPages].
     */
    private fun metronPages(token: String, first: String, maxPages: Int): List<org.json.JSONObject> {
        val out = ArrayList<org.json.JSONObject>()
        var url: String? = first
        var n = 0
        while (url != null && n < maxPages) {
            if (n > 0) Thread.sleep(3_500)
            val r = Http.request(
                "GET", url,
                mapOf("Authorization" to "Bearer $token", "Accept" to "application/json", "User-Agent" to "Readync (+https://github.com/PrithirajN/Readync)"),
                timeoutMs = 25_000,
            )
            if (r.code == 401 || r.code == 403) throw MetronRefused()
            if (!r.ok) break
            val o = runCatching { org.json.JSONObject(r.body) }.getOrNull() ?: break
            o.optJSONArray("results")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(out::add) }
            url = o.optString("next").takeIf { it.startsWith("https://") }
            n++
        }
        return out
    }

    /** A Metron issue as a book due out (list and pull-list items share these fields). */
    internal fun metronIssue(o: org.json.JSONObject, genre: String?): Book? {
        val day = dayOfIso(o.optString("store_date").takeIf { it.isNotBlank() && it != "null" }) ?: return null
        val series = o.optJSONObject("series")
        val name = series?.optString("name")?.takeIf { it.isNotBlank() }
        val year = series?.optInt("year_began")?.takeIf { it > 0 }
        val number = o.optString("number").trim().ifBlank { null }
        val shown = o.optString("issue").trim().ifBlank { listOfNotNull(name?.let { n -> year?.let { "$n ($it)" } ?: n }, number?.let { "#$it" }).joinToString(" ") }
        if (shown.isBlank()) return null
        return Book(
            title = Html.decode(shown), series = name?.let { n -> year?.let { "$n ($it)" } ?: n }, number = number, author = null,
            publisher = o.optJSONObject("publisher")?.optString("name")?.ifBlank { null }, day = day, genre = genre,
            url = "https://metron.cloud/issue/${o.optInt("id")}/", cover = o.optString("image").takeIf { it.startsWith("http") }, blurb = null,
            source = "Metron",
        )
    }

    /**
     * New comic series starting from a week ago to six weeks on, every publisher (issue #1s only:
     * every issue of every series would be several hundred a week).
     */
    fun fetchMetronFirsts(token: String, now: Long = System.currentTimeMillis() / 1000): List<Book> {
        val today = java.time.Instant.ofEpochSecond(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val url = "$METRON/issue/?number=1&store_date_range_after=${today.minusDays(7)}&store_date_range_before=${today.plusWeeks(6)}"
        return metronPages(token, url, 8).mapNotNull { metronIssue(it, COMICS) }.filter { !it.title.contains("variant", true) }
    }

    /** The issues of the series on your Metron pull list, from a fortnight ago to four months on. */
    fun fetchMetronPullList(token: String, now: Long = System.currentTimeMillis() / 1000): List<Book> {
        val today = java.time.Instant.ofEpochSecond(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val url = "$METRON/pull_list/issues/?store_date_after=${today.minusDays(14)}&store_date_before=${today.plusMonths(4)}"
        return metronPages(token, url, 10).mapNotNull { metronIssue(it, null) }
    }

    /** Checks a token: one small, signed request. True when Metron takes it. */
    fun metronTokenWorks(token: String): Boolean = runCatching {
        Http.request("GET", "$METRON/pull_list/", mapOf("Authorization" to "Bearer $token", "Accept" to "application/json"), timeoutMs = 20_000).ok
    }.getOrDefault(false)

    /** Upcoming comics: Marvel's calendar (week by week, six weeks on) and Dark Horse's upcoming list; variant covers left out. */
    fun fetchComics(now: Long = System.currentTimeMillis() / 1000): List<Book> {
        val today = java.time.Instant.ofEpochSecond(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val marvel = (0..5).flatMap { w ->
            val from = today.minusDays(7).plusWeeks(w.toLong())
            get("https://www.marvel.com/comics/calendar?dateStart=$from&dateEnd=${from.plusDays(6)}")?.let(::parseMarvel).orEmpty()
        }.distinctBy { it.url ?: it.title }
        val dh = get("https://www.darkhorse.com/Comics/Upcoming")?.let(::parseDarkHorse).orEmpty()
        return marvel + dh
    }

    /** "Jean Grey (2026) #1" → series "Jean Grey (2026)", number "1". */
    internal fun issueOf(title: String): Pair<String?, String?> {
        val m = Regex("""^(.*?)\s*#\s*(\d{1,4})\b""").find(title) ?: return null to null
        return m.groupValues[1].trim() to m.groupValues[2]
    }

    /** Marvel's calendar: "ON SALE: 11/25/2026" headings, then comic cards (title, cover, creators). */
    internal fun parseMarvel(html: String): List<Book> {
        val out = ArrayList<Book>()
        val parts = Regex("""ON SALE:\s*(\d{2})/(\d{2})/(\d{4})""").findAll(html).toList()
        for ((i, p) in parts.withIndex()) {
            val day = dayOf(p.groupValues[3].toInt(), p.groupValues[1].toInt(), p.groupValues[2].toInt()) ?: continue
            val chunk = html.substring(p.range.last, parts.getOrNull(i + 1)?.range?.first ?: html.length)
            for (card in chunk.split("data-content-type=\"comic\"").drop(1)) {
                val t = Regex("""ComicCard__Meta__Title[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>([^<]+)</a>""").find(card) ?: continue
                val title = Html.decode(t.groupValues[2]).trim()
                if (title.contains("variant", true)) continue
                val cover = Regex("""<img[^>]*src="(https://cdn\.marvel\.com/[^"]+)"""").find(card)?.groupValues?.get(1)
                val creators = Regex("""Creators_List_Item_Anchor[^>]*>([^<]+)</a>""").findAll(card).map { Html.decode(it.groupValues[1]).trim() }.take(2).toList()
                val (series, number) = issueOf(title)
                out += Book(
                    title = title, series = series, number = number, author = creators.joinToString(", ").ifBlank { null }, publisher = "Marvel",
                    day = day, genre = COMICS, url = t.groupValues[1], cover = cover, blurb = null, source = "Marvel",
                )
            }
        }
        return out
    }

    /** Dark Horse's upcoming list: <h3> days, then product cards (cover, title). */
    internal fun parseDarkHorse(html: String): List<Book> {
        val out = ArrayList<Book>()
        val heads = Regex("""<h3>\s*($MONTH) (\d{1,2}), (\d{4})\s*</h3>""").findAll(html).toList()
        for ((i, h) in heads.withIndex()) {
            val day = dayOf(h.groupValues[3].toInt(), MONTHS.indexOf(h.groupValues[1]) + 1, h.groupValues[2].toInt()) ?: continue
            val chunk = html.substring(h.range.last, heads.getOrNull(i + 1)?.range?.first ?: html.length)
            for (card in chunk.split("class=\"product-result\"").drop(1)) {
                val t = Regex("""<div><a href="(/comics/[^"]+)">([^<]+)</a></div>""").find(card) ?: continue
                val title = Html.decode(t.groupValues[2]).trim()
                if (title.contains("variant", true)) continue
                val cover = Regex("""<img src="(https://images\.darkhorse\.com/covers/[^"]+)"""").find(card)?.groupValues?.get(1)
                val (series, number) = issueOf(title)
                out += Book(
                    title = title, series = series, number = number, author = null, publisher = "Dark Horse", day = day, genre = COMICS,
                    url = "https://www.darkhorse.com" + t.groupValues[1], cover = cover, blurb = null, source = "Dark Horse",
                )
            }
        }
        return out
    }

    private fun fetchPress(p: LitRpgPress, now: Long): List<Book> {
        val headers = mapOf("Accept" to "application/json", "User-Agent" to Http.defaultUserAgent)
        val fields = "id,link,title,meta.publication_date,meta.series_name,meta.series_position,meta.page_count," +
            "meta.asin,meta.asin_kindle,meta.coverart_source_url,meta.book_description,book-author"
        // Newest added first; books are put up a month or two before their day, so two pages reach far enough.
        val books = ArrayList<org.json.JSONObject>()
        for (page in 1..2) {
            val r = Http.request(
                "GET", "${p.site}/wp-json/wp/v2/book?book-genre=${p.genres.joinToString(",")}&per_page=100&page=$page&orderby=date&order=desc&_fields=$fields",
                headers, timeoutMs = 25_000,
            )
            if (!r.ok) break
            val arr = runCatching { org.json.JSONArray(r.body) }.getOrNull() ?: break
            val got = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            books += got
            // Stop once a page is all books out more than a month ago.
            if (got.isEmpty() || got.all { (dayOfIso(it.optJSONObject("meta")?.optString("publication_date")) ?: 0L) < now - 31 * 86_400L }) break
        }
        val ids = books.flatMap { b -> b.optJSONArray("book-author")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty() }.filter { it > 0 }.distinct()
        val names = HashMap<Int, String>()
        for (chunk in ids.chunked(100)) {
            val r = runCatching {
                Http.request("GET", "${p.site}/wp-json/wp/v2/book-author?include=${chunk.joinToString(",")}&per_page=100&_fields=id,name", headers, timeoutMs = 20_000)
            }.getOrNull()?.takeIf { it.ok } ?: continue
            val arr = runCatching { org.json.JSONArray(r.body) }.getOrNull() ?: continue
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { names[it.optInt("id")] = Html.decode(it.optString("name")) }
        }
        return parsePress(books, names, p.name)
    }

    /** The catalogue's books as upcoming LitRPG (those with a day; the publisher itself is skipped as an author). */
    internal fun parsePress(books: List<org.json.JSONObject>, authors: Map<Int, String>, press: String): List<Book> = books.mapNotNull { b ->
        val m = b.optJSONObject("meta") ?: return@mapNotNull null
        val day = dayOfIso(m.optString("publication_date")) ?: return@mapNotNull null
        val title = Html.decode(b.optJSONObject("title")?.optString("rendered").orEmpty()).trim().ifBlank { return@mapNotNull null }
        val series = Html.decode(m.optString("series_name")).trim().ifBlank { null }
        val number = m.optString("series_position").trim().ifBlank { null }?.let { numberOf(it) ?: it }
        val by = b.optJSONArray("book-author")?.let { a -> (0 until a.length()).mapNotNull { authors[a.optInt(it)] } }.orEmpty()
            .filter { !it.equals("Aethon", true) && !it.contains(press, true) }
        val asin = m.optString("asin").ifBlank { m.optString("asin_kindle") }.trim().takeIf { Regex("^[A-Z0-9]{10}$").matches(it) }
        Book(
            title = title, series = series, number = number, author = by.joinToString(", ").ifBlank { null }, publisher = press,
            day = day, genre = "LitRPG",
            url = asin?.let { "https://www.amazon.com/dp/$it" } ?: b.optString("link").ifBlank { null },
            cover = m.optString("coverart_source_url").ifBlank { null } ?: coverFor(asin?.let { "/dp/$it" }),
            blurb = m.optString("book_description").takeIf { it.isNotBlank() }?.let { Html.text(it).trim() }?.ifBlank { null },
            source = press,
        )
    }

    /** "2026-12-09" → that day, 9 am local. */
    private fun dayOfIso(s: String?): Long? {
        val m = s?.let { Regex("""^(\d{4})-(\d{2})-(\d{2})""").find(it.trim()) } ?: return null
        return dayOf(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }

    fun fetchReactor(): List<Book> {
        val r = Http.request(
            "GET", REACTOR_FEED,
            mapOf("Accept" to "application/rss+xml, application/xml, text/xml, */*", "User-Agent" to Http.defaultUserAgent),
            timeoutMs = 20_000,
        )
        if (!r.ok) return emptyList()
        return parseReactorFeed(r.body)
    }

    /** Every book in the feed's monthly lists ("All the New Fantasy Books Arriving in November 2026"). */
    internal fun parseReactorFeed(xml: String): List<Book> {
        val out = ArrayList<Book>()
        for (item in Regex("""(?s)<item>(.*?)</item>""").findAll(xml)) {
            val it = item.groupValues[1]
            val title = Html.decode(Regex("""(?s)<title>(.*?)</title>""").find(it)?.groupValues?.get(1)?.trim().orEmpty())
            val m = Regex("""(?i)All the New (.+?) Books Arriving in ($MONTH) (\d{4})""").find(title) ?: continue
            val genre = genreOf(m.groupValues[1])
            val month = MONTHS.indexOfFirst { mo -> mo.equals(m.groupValues[2], ignoreCase = true) } + 1
            val year = m.groupValues[3].toInt()
            val link = Regex("""(?s)<link>(.*?)</link>""").find(it)?.groupValues?.get(1)?.trim()
            val body = Regex("""(?s)<content:encoded><!\[CDATA\[(.*?)]]></content:encoded>""").find(it)?.groupValues?.get(1) ?: continue
            out += parseReactorPost(body, genre, year, month, link)
        }
        return out
    }

    /**
     * One monthly list: a heading for each day ("November 3"), then a paragraph per book:
     * *Title* (Series #2) — Author (Publisher), a line break, and the publisher's copy.
     */
    internal fun parseReactorPost(html: String, genre: String?, year: Int, month: Int, postUrl: String?): List<Book> {
        val out = ArrayList<Book>()
        var day: Long? = null
        val parts = Regex("""(?s)<h[2-4][^>]*>(.*?)</h[2-4]>|<p[^>]*>(.*?)</p>""").findAll(html)
        for (p in parts) {
            val heading = p.groupValues[1]
            if (heading.isNotEmpty()) {
                val h = Html.text(heading)
                val dm = Regex("""(?i)^($MONTH)\s+(\d{1,2})""").find(h)
                if (dm != null) {
                    val mo = MONTHS.indexOfFirst { it.equals(dm.groupValues[1], ignoreCase = true) } + 1
                    // A list can run into the next month (a December list's "January 2"), so the year follows.
                    val y = if (mo < month && month - mo > 6) year + 1 else year
                    day = dayOf(y, mo, dm.groupValues[2].toInt())
                }
                continue
            }
            val d = day ?: continue
            val para = p.groupValues[2]
            if (!para.trimStart().startsWith("<em") && !para.trimStart().startsWith("<strong><em") && !para.trimStart().startsWith("<a")) continue
            val first = para.substringBefore("<br")
            val em = Regex("""(?s)<em>(.*?)</em>""").find(first) ?: continue
            val title = Html.text(em.groupValues[1]).trim().takeIf { it.length >= 2 } ?: continue
            val rest = Html.text(first.substring(em.range.last + 1)).trim()
            // " (Pitiless Rain #2) — Glen Cook (Tor Books)"
            val rm = Regex("""^(?:\((.+?)\))?\s*[—–-]\s*(.+?)\s*(?:\(([^()]+)\))?\s*$""").find(rest) ?: continue
            val seriesFull = rm.groupValues[1].trim().ifBlank { null }
            // "(Book V)": a number with no series name (Goodreads fills it in); "(The Devils #2)",
            // "(The Devils, Book 2)": the series and its number.
            val bare = seriesFull?.let { Regex("""(?i)^(?:book|volume|vol\.?|part)\s+([0-9]+|[IVXLC]+)$""").find(it) }
            val series = if (bare != null) null else seriesFull?.replace(Regex("""(?i),?\s*(?:#|book\s+|volume\s+|vol\.?\s*)\s*[0-9IVXLC.]+\s*$"""), "")?.trim()?.ifBlank { null }
            val number = bare?.groupValues?.get(1)?.let(::numberOf)
                ?: seriesFull?.let { Regex("""(?i)(?:#|book\s+|volume\s+|vol\.?\s*)\s*(\d+(?:\.\d+)?|[IVXLC]+)\s*$""").find(it)?.groupValues?.get(1) }?.let { numberOf(it) ?: it }
            // "Solvej Balle, Jennifer Russell, translated by Sophia Hersi Smith": the authors, then who translated it.
            val people = rm.groupValues[2].trim()
            val tm = Regex("""(?i)[,;]?\s*(?:translated by|trans\.|tr\.)\s+""").find(people)
            val author = (if (tm != null) people.substring(0, tm.range.first) else people).trim().trimEnd(',').ifBlank { null }
            val translators = tm?.let { people.substring(it.range.last + 1).split(Regex("""\s*(?:,|&| and )\s*""")).map { t -> t.trim() }.filter { t -> t.length > 2 } }.orEmpty()
            val link = Regex("""href="([^"]+)"""").find(first)?.groupValues?.get(1)
            val blurb = para.substringAfter("<br", "").substringAfter(">", "").let { Html.text(it).trim() }
                .ifBlank { null }?.let { if (it.length > 320) it.take(317).trimEnd() + "…" else it }
            out += Book(title, series, number, author, rm.groupValues[3].trim().ifBlank { null }, d, genre, link ?: postUrl, coverFor(link), blurb, "Reactor", translators)
        }
        return out
    }

    // ---- Goodreads: series, rating, pages… ----

    private const val GOODREADS = "https://www.goodreads.com"
    private val DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131 Safari/537.36"

    private fun page(url: String): io.github.prithirajn.readync.net.HttpResponse? =
        runCatching { Http.request("GET", url, mapOf("User-Agent" to DESKTOP, "Accept" to "text/html", "Accept-Language" to "en-US,en;q=0.9"), timeoutMs = 15_000) }
            .getOrNull()?.takeIf { it.ok }

    /**
     * What Goodreads says of a book, signed out: its page by ISBN or Kindle id (Goodreads sends
     * /book/isbn/<id> to the book's page), else the first result of a title-and-author search.
     * Null when it couldn't be asked (no answer); [ReleaseFacts.missing] when it was and hasn't it.
     */
    fun goodreadsFacts(id: String?, title: String, author: String?, now: Long = System.currentTimeMillis() / 1000): io.github.prithirajn.readync.data.ReleaseFacts? {
        // By ISBN or Kindle id: Goodreads sends /book/isbn/<id> to the book's page.
        id?.let { page("$GOODREADS/book/isbn/$it") }?.body?.let(::parseGoodreadsBook)?.let { return it.copy(checkedAt = now) }
        // Else its autocomplete (what its search box asks as you type; the search page itself sits
        // behind a bot check): the first result whose title is this one, then that book's page.
        val q = java.net.URLEncoder.encode(listOfNotNull(title, author?.split(",")?.first()).joinToString(" "), "UTF-8")
        val r = runCatching {
            Http.request("GET", "$GOODREADS/book/auto_complete?format=json&q=$q", mapOf("User-Agent" to DESKTOP, "Accept" to "application/json"), timeoutMs = 15_000)
        }.getOrNull()?.takeIf { it.ok } ?: return null
        val want = io.github.prithirajn.readync.sync.Matcher.baseTitle(title)
        val hits = runCatching { org.json.JSONArray(r.body) }.getOrNull() ?: return null
        val hit = (0 until hits.length()).mapNotNull { hits.optJSONObject(it) }.firstOrNull { h ->
            val got = io.github.prithirajn.readync.sync.Matcher.baseTitle(h.optString("bookTitleBare").ifBlank { h.optString("title") })
            got.isNotBlank() && (got.startsWith(want) || want.startsWith(got))
        } ?: return io.github.prithirajn.readync.data.ReleaseFacts(checkedAt = now, missing = true)
        val show = hit.optString("bookUrl").takeIf { it.startsWith("/book/show/") }
        // The page has the most (genres, first published, translators); when it's behind the bot
        // check, the autocomplete's own answer (series, rating, pages) stands.
        return show?.let { page(GOODREADS + it) }?.body?.let(::parseGoodreadsBook)?.copy(checkedAt = now) ?: factsFromHit(hit, now)
    }

    /** A Goodreads book page's facts, by its address. */
    fun goodreadsPage(url: String, now: Long): io.github.prithirajn.readync.data.ReleaseFacts? =
        runCatching { page(url) }.getOrNull()?.body?.let(::parseGoodreadsBook)?.copy(checkedAt = now)

    /** What Goodreads' autocomplete says of a book: "Title (Series, #2)", its pages, rating and author. */
    internal fun factsFromHit(h: org.json.JSONObject, now: Long): io.github.prithirajn.readync.data.ReleaseFacts {
        val full = h.optString("title")
        val sm = Regex("""\(([^()]+?),?\s*#\s*([\d.]+)\)\s*$""").find(full)
        return io.github.prithirajn.readync.data.ReleaseFacts(
            goodreadsId = h.optString("bookId").ifBlank { null },
            title = h.optString("bookTitleBare").ifBlank { null } ?: full.ifBlank { null },
            series = sm?.groupValues?.get(1)?.trim(), position = sm?.groupValues?.get(2),
            rating = h.optString("avgRating").toDoubleOrNull()?.takeIf { it > 0 },
            ratings = h.optInt("ratingsCount").takeIf { it > 0 },
            pages = h.optInt("numPages").takeIf { it > 0 },
            author = h.optJSONObject("author")?.optString("name")?.ifBlank { null },
            cover = h.optString("imageUrl").replace(Regex("""\._S[XY]\d+_\."""), ".").takeIf { it.startsWith("https://") },
            checkedAt = now,
        )
    }

    /** A Goodreads book page's own data (its __NEXT_DATA__): the book, its work, series and people. */
    internal fun parseGoodreadsBook(html: String): io.github.prithirajn.readync.data.ReleaseFacts? {
        val json = Regex("""(?s)<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""").find(html)?.groupValues?.get(1) ?: return null
        val apollo = runCatching { org.json.JSONObject(json).getJSONObject("props").getJSONObject("pageProps").getJSONObject("apolloState") }.getOrNull() ?: return null
        fun ref(o: org.json.JSONObject?): org.json.JSONObject? = o?.optString("__ref")?.takeIf { it.isNotBlank() }?.let { apollo.optJSONObject(it) }
        val book = apollo.keys().asSequence().mapNotNull { apollo.optJSONObject(it) }
            .firstOrNull { it.optString("__typename") == "Book" && it.optString("title").isNotBlank() } ?: return null
        val details = book.optJSONObject("details")
        val work = ref(book.optJSONObject("work"))
        val stats = work?.optJSONObject("stats")
        val bs = book.optJSONArray("bookSeries")?.optJSONObject(0)
        val series = ref(bs?.optJSONObject("series"))?.optString("title")?.ifBlank { null }
        val author = ref(book.optJSONObject("primaryContributorEdge")?.optJSONObject("node"))?.optString("name")?.replace(Regex("\\s+"), " ")?.trim()
        val translators = book.optJSONArray("secondaryContributorEdges")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("role").equals("Translator", ignoreCase = true) }
                .mapNotNull { ref(it.optJSONObject("node"))?.optString("name")?.replace(Regex("\\s+"), " ")?.trim()?.ifBlank { null } }
        }.orEmpty()
        val genres = book.optJSONArray("bookGenres")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optJSONObject("genre")?.optString("name")?.ifBlank { null } }
        }.orEmpty().filter { it !in setOf("Fiction", "Novels", "Audiobook", "Adult", "Adult Fiction") }.take(4)
        fun year(ms: Long) = if (ms == 0L) null else java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).year
        // "Title (Series, #5)" and "Title (A S.C.Y.T.H.E. Mystery)" → "Title": the series is said apart.
        val title = book.optString("titleComplete").ifBlank { book.optString("title") }
            .replace(Regex("""\s*\([^()]*#\s*[\d.]+\)\s*$"""), "")
            .let { t ->
                val tail = Regex("""\s*\(([^()]+)\)\s*$""").find(t)
                val seriesLetters = series?.filter { it.isLetter() }?.lowercase()
                if (tail != null && seriesLetters != null && seriesLetters.length > 3 &&
                    tail.groupValues[1].filter { it.isLetter() }.lowercase().contains(seriesLetters.take(6))) t.substring(0, tail.range.first) else t
            }.trim()
        return io.github.prithirajn.readync.data.ReleaseFacts(
            goodreadsId = book.optString("legacyId").ifBlank { null } ?: Regex("""/book/show/(\d+)""").find(book.optString("webUrl"))?.groupValues?.get(1),
            title = title.ifBlank { null },
            series = series, position = bs?.optString("userPosition")?.ifBlank { null },
            rating = stats?.optDouble("averageRating")?.takeIf { !it.isNaN() && it > 0 },
            ratings = stats?.optInt("ratingsCount")?.takeIf { it > 0 },
            pages = details?.optInt("numPages")?.takeIf { it > 0 },
            format = details?.optString("format")?.ifBlank { null }?.takeIf { it != "null" },
            publisher = details?.optString("publisher")?.ifBlank { null }?.takeIf { it != "null" },
            firstYear = work?.optJSONObject("details")?.optLong("publicationTime")?.let(::year),
            genres = genres, author = author, translators = translators,
            cover = book.optString("imageUrl").takeIf { it.startsWith("https://") },
        )
    }

    /**
     * Books announced in the news and on socials (the News tab's items): a cover reveal, an excerpt,
     * an announcement or a preorder, named in the headline ("Read an Excerpt From King of Masks by
     * Petra Lord"), kept only when the post says the day it's out ("publishing … on January 26th,
     * 2027"). The post's own date ("Published on November 17") isn't the book's.
     */
    fun fromNews(items: List<SffFeedItem>, now: Long = System.currentTimeMillis() / 1000): List<Book> {
        val out = ArrayList<Book>()
        for (item in items) {
            val head = Html.decode(item.title)
            val named = Regex(
                """(?i)(?:cover reveal(?: for| of)?|revealing the cover (?:of|for)|reveal(?:ing)?|(?:read )?an? excerpt (?:from|of)|excerpt:|announcing|announced?:?|pre-?order)\s*[:—–-]?\s*["“‘*_]?(.+?)["”’*_]?\s+by\s+([A-Z][\p{L}.'’\- ]{2,40}?)\s*(?:[,:;(—–|]|$)""",
            ).find(head) ?: continue
            val title = named.groupValues[1].trim().trim('"', '“', '”', '‘', '’', '*', '_').takeIf { it.length >= 2 } ?: continue
            val author = named.groupValues[2].trim()
            val text = listOfNotNull(item.summary, item.fullContent, item.subtitle).joinToString(" ") { Html.text(it) }
            val day = releaseDayIn(text, now) ?: item.releaseDate?.let { parseIsoDay(it) } ?: continue
            out += Book(
                title, item.series, item.seriesNumber, author, null, day, null, item.url, item.coverUrl,
                item.summary?.let { Html.text(it).trim() }?.ifBlank { null }?.let { if (it.length > 320) it.take(317).trimEnd() + "…" else it },
                item.source,
            )
        }
        return out
    }

    private fun parseIsoDay(s: String): Long? =
        runCatching { LocalDate.parse(s.take(10)).atTime(9, 0).atZone(ZoneId.systemDefault()).toEpochSecond() }.getOrNull()

    /**
     * The day a post says a book is out: a date next to "publishing", "out", "on sale", "release"…
     * A date without a year is the next one to come (or one in the past fortnight).
     */
    internal fun releaseDayIn(text: String, now: Long): Long? {
        val cue = """(?:publish(?:ing|es)|out|available|releas(?:es|ing|e date|ed)|arriv(?:es|ing)|on sale|hits shelves|coming|due|pub(?:lication)? date|pre-?order)"""
        val date = """($MONTH)\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s*(\d{4}))?"""
        // Up to 90 characters between the word and the day; a full stop only ends the search where it
        // ends a sentence (". " then a capital), so "Henry Holt and Co. on January 26th" still counts.
        for (m in Regex("""(?i)\b$cue\b(?:[^.!?]|(?-i:\.(?!\s+[A-Z][a-z]))){0,90}?\b$date""").findAll(text)) {
            // A byline's "Published on November 17" is the post, not the book.
            val dateAt = m.groups[1]?.range?.first ?: continue
            if (Regex("""(?i)published\s+on\s*$""").containsMatchIn(text.substring(0, dateAt).takeLast(30))) continue
            val mo = MONTHS.indexOfFirst { it.equals(m.groupValues[1], ignoreCase = true) } + 1
            val d = m.groupValues[2].toIntOrNull() ?: continue
            val today = java.time.Instant.ofEpochSecond(now).atZone(ZoneId.systemDefault()).toLocalDate()
            val y = m.groupValues[3].toIntOrNull()
                ?: (if (runCatching { LocalDate.of(today.year, mo, d) }.getOrNull()?.isBefore(today.minusDays(14)) == true) today.year + 1 else today.year)
            return dayOf(y, mo, d) ?: continue
        }
        return null
    }
}
