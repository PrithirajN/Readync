package io.github.prithirajn.trackersync.source

import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.PriceListing
import io.github.prithirajn.trackersync.data.PricePoint
import io.github.prithirajn.trackersync.data.PriceWatch
import io.github.prithirajn.trackersync.data.Store
import io.github.prithirajn.trackersync.net.Http
import io.github.prithirajn.trackersync.sync.Matcher
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Book prices at the stores you buy from: Amazon.in, Bookswagon and Atlantic Books, read the way a
 * browser sees them. A book is found by its ISBN where it has one (else its title and author), or at
 * the exact page you pointed it to, which always wins. Atlantic runs on Shopify, so its prices come
 * from the store's own product data; Amazon and Bookswagon are read from their pages, and a change
 * to those pages can stop a price being found until it's caught up with here.
 */
object BookPrices {
    data class Shop(val id: String, val name: String, val home: String)

    val shops = listOf(
        Shop("amazon", "Amazon.in", "https://www.amazon.in"),
        Shop("bookswagon", "Bookswagon", "https://www.bookswagon.com"),
        Shop("atlantic", "Atlantic Books", "https://atlanticbooks.com"),
    )

    fun shop(id: String): Shop? = shops.firstOrNull { it.id == id }
        ?: id.takeIf { it.startsWith("site:") }?.removePrefix("site:")?.let { h -> Shop(id, siteName(h), "https://$h") }

    /** Other bookshops' own names, by address (any shop's page works; these are just named nicely). */
    private val SITE_NAMES = mapOf(
        "flipkart.com" to "Flipkart", "crossword.in" to "Crossword", "sapnaonline.com" to "Sapna Online",
        "bookchor.com" to "Bookchor", "notionpress.com" to "Notion Press", "harpercollins.co.in" to "HarperCollins India",
        "bookishsanta.com" to "Bookish Santa", "pothi.com" to "Pothi", "panmacmillan.co.in" to "Pan Macmillan India",
        "kitabay.com" to "Kitabay", "booksbywords.com" to "Books by Words", "blinkit.com" to "Blinkit",
    )

    /** Addresses that aren't shops (reading sites, social posts, link shorteners): never tracked as one. */
    private val NOT_SHOPS = setOf(
        "goodreads", "hardcover", "thestorygraph", "instagram", "instagr", "tiktok", "youtube", "youtu", "threads", "x", "twitter",
        "facebook", "fb", "google", "goo", "reddit", "fable", "openlibrary", "wikipedia", "royalroad", "archiveofourown", "t", "bit", "linktr", "pinterest", "pin",
    )

    /** An address's site, without www./m./dl. */
    fun host(url: String): String? = runCatching { java.net.URI(url.trim()).host }.getOrNull()?.lowercase()
        ?.removePrefix("www.")?.removePrefix("m.")?.removePrefix("dl.")?.ifBlank { null }

    /** A shop's name from its address: "Flipkart", else its main word ("shop.penguin.co.in" -> "Penguin"). */
    fun siteName(host: String): String = SITE_NAMES.entries.firstOrNull { host == it.key || host.endsWith("." + it.key) }?.value
        ?: host.split('.').firstOrNull { it !in setOf("www", "m", "shop", "store", "books", "in", "co", "com") }?.replaceFirstChar { it.uppercase() } ?: host

    /**
     * Which shop a pasted address is: one of the three followed closely, else "site:<address>" for any
     * other bookshop's book page (its price read from the page itself). Null for what isn't a shop's page.
     */
    fun trackable(url: String): String? {
        shopOf(url)?.let { return it }
        val u = url.trim()
        if (!u.startsWith("http")) return null
        val h = host(u) ?: return null
        if (h.split('.').dropLast(1).any { it in NOT_SHOPS }) return null
        // A page of the shop, not its front page.
        if (runCatching { java.net.URI(u).path }.getOrNull().orEmpty().trim('/').isEmpty()) return null
        return "site:$h"
    }

    /** A browser for shops that turn plain requests away (set by the app). */
    @Volatile var browser: ((String) -> String?)? = null

    /** The same unseen browser, waiting until the page shows what's wanted (results a shop's script fills in). */
    @Volatile var browserUntil: ((String, Regex?) -> String?)? = null

    /** Which store a pasted address belongs to. */
    fun shopOf(url: String): String? {
        val u = url.lowercase()
        return when {
            "amazon.in" in u || "amzn.in" in u || "amzn.to" in u -> "amazon"
            "bookswagon.com" in u -> "bookswagon"
            "atlanticbooks.com" in u -> "atlantic"
            else -> null
        }
    }

    class PriceException(message: String) : Exception(message)

    private fun get(url: String): String = fetch(url).body

    private fun fetch(url: String): io.github.prithirajn.trackersync.net.HttpResponse {
        val r = Http.request("GET", url, mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-IN,en;q=0.9",
        ), timeoutMs = 20_000)
        if (r.code == 503 || r.body.contains("api-services-support@amazon.com") || r.body.contains("Type the characters you see")) {
            throw PriceException("the store asked to check this isn't a robot; tried again later")
        }
        if (!r.ok) throw PriceException("HTTP ${r.code}")
        return r
    }

    private fun money(s: String?): Double? = s?.replace(",", "")?.replace(Regex("[^0-9.]"), "")?.trim('.')?.toDoubleOrNull()?.takeIf { it > 0 }

    private fun query(book: Book): String =
        book.isbn?.filter { it.isDigit() || it == 'X' }?.takeIf { it.length == 13 || it.length == 10 }
            ?: listOfNotNull(book.displayTitle, book.author).joinToString(" ")

    private fun letters(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** The book's ISBN-10 (from a 978 ISBN-13), which is a printed book's ASIN on Amazon, or null. */
    private fun isbn10(book: Book): String? {
        val d = book.isbn?.filter { it.isDigit() || it == 'X' || it == 'x' }?.uppercase() ?: return null
        if (d.length == 10) return d
        if (d.length != 13 || !d.startsWith("978")) return null
        val core = d.substring(3, 12)
        val sum = core.mapIndexed { i, ch -> (ch - '0') * (10 - i) }.sum()
        val check = (11 - sum % 11) % 11
        return core + if (check == 10) "X" else check.toString()
    }

    /** The book's ISBN as 13 digits (an ISBN-10 converted), or null. */
    private fun isbn13(book: Book): String? {
        val d = book.isbn?.filter { it.isDigit() || it == 'X' || it == 'x' } ?: return null
        if (d.length == 13) return d
        if (d.length != 10) return null
        val core = "978" + d.take(9)
        val sum = core.mapIndexed { i, ch -> (ch - '0') * if (i % 2 == 0) 1 else 3 }.sum()
        return core + ((10 - sum % 10) % 10)
    }

    /**
     * Whether a store's [title] (and the [isbn] its page carries, when it does) is this book. The ISBN
     * decides when both are known; otherwise the titles must agree (the main title, without series or
     * subtitle, inside the store's), and a book number, where either has one, must be the same.
     */
    internal fun matches(book: Book, title: String?, isbn: String? = null): Boolean {
        val mine = isbn13(book)
        if (mine != null && isbn != null && isbn.length == 13) {
            if (isbn == mine) return true
            // A book followed from a store's page is that edition; a library book is any printing of it.
            if (strictEdition.get() == true) return false
        }
        if (title.isNullOrBlank()) return false
        if (Matcher.sameBook(book.displayTitle, book.author, title, null)) return true
        val a = letters(Matcher.baseTitle(book.displayTitle)); val b = letters(Matcher.baseTitle(title))
        if (a.length < 4 || b.length < 4) return false
        // The store's title starts with the book's (so "El Portal de… / The Gate of…" isn't the English book).
        val full = letters(title)
        val same = b.startsWith(a) || a.startsWith(b) || full.startsWith(a)
        val va = Matcher.volume(book.displayTitle); val vb = Matcher.volume(title)
        return same && (va == null || vb == null || va == vb)
    }

    /** [block] with any printing of the book counting, not only the edition followed. */
    private fun <T> loose(block: () -> T): T {
        val was = strictEdition.get()
        strictEdition.set(false)
        try { return block() } finally { strictEdition.set(was) }
    }

    /** Each store's listings found while looking for the book being checked (editions to choose from). */
    private val listings = ThreadLocal<MutableMap<String, List<PriceListing>>>()

    /** The listings the last [check] on this thread found, by store. */
    fun lastListings(): Map<String, List<PriceListing>> = listings.get().orEmpty()

    private val EDITION = Regex("(?i)\\b(deluxe|special|collector'?s|limited|anniversary|signed|illustrated|exclusive|sprayed[- ]edges?|gift|premium)\\b(?:\\s+(?:hardcover\\s+)?edition)?")

    private val SMALL = setOf("a", "an", "and", "as", "at", "but", "by", "for", "in", "of", "on", "or", "the", "to", "with", "from", "into")

    /**
     * A shop's shouting, shortened title made readable: "LEVIATHAN WAKES 10TH ANNIV ED" becomes
     * "Leviathan Wakes 10th Anniversary Edition". Titles already in normal case keep their case.
     */
    fun tidyShopTitle(raw: String): String {
        var t = raw.trim().replace(Regex("\\s+"), " ")
        val letters = t.filter { it.isLetter() }
        val shouting = letters.length >= 6 && letters.count { it.isUpperCase() } >= letters.length * 0.8
        // Shop shorthand spelled out.
        val words = mapOf(
            "anniv" to "Anniversary", "annv" to "Anniversary", "edn" to "Edition", "spl" to "Special", "spcl" to "Special",
            "ltd" to "Limited", "coll" to "Collector's", "collectors" to "Collector's", "illus" to "Illustrated", "dlx" to "Deluxe",
        )
        t = t.split(' ').joinToString(" ") { w ->
            val bare = w.trim('(', ')', ',', '.').lowercase()
            words[bare]?.let { w.replace(Regex("(?i)" + Regex.escape(bare)), it) } ?: w
        }
        // "ED" after an edition word (or at the very end after one): "Edition".
        t = t.replace(Regex("(?i)\\b(Anniversary|Special|Deluxe|Collector's|Limited|Illustrated|Signed|\\d+(?:st|nd|rd|th))\\s+ED\\b\\.?"), "$1 Edition")
        // "(PB)", "HB", "Paperback" tags off the end.
        t = t.replace(Regex("(?i)[\\s(\\[-]*\\b(PB|HB|HBK|PBK)\\b[)\\]]?\\s*$"), "").trim()
        if (!shouting) return t
        return t.split(' ').mapIndexed { i, w ->
            val lower = w.lowercase()
            when {
                Regex("^\\d+(st|nd|rd|th)$").matches(lower) -> lower
                Regex("^[ivxlc]+$").matches(lower) && lower.length <= 4 && i > 0 -> w.uppercase()
                i > 0 && lower in SMALL -> lower
                else -> lower.replaceFirstChar { it.uppercase() }.replace(Regex("(?<=['’-])(\\p{L})")) { m -> m.value.uppercase() }.replace("'S", "'s").replace("’S", "’s")
            }
        }.joinToString(" ")
    }

    /** A title without its edition words, for searching ("Leviathan Wakes 10th Anniversary Edition" -> "Leviathan Wakes"). */
    fun withoutEdition(title: String): String =
        title.replace(Regex("(?i)[(\\[]?\\s*\\b(\\d+(st|nd|rd|th)\\s+)?(anniversary|deluxe|special|collector'?s|limited|illustrated|signed|exclusive|gift|premium)(\\s+(hardcover\\s+)?edition)?\\b\\s*[)\\]]?"), " ")
            .replace(Regex("(?i)\\b(hardcover|paperback|edition)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim().trimEnd(':', '-', '–', ',').trim()
            .ifBlank { title }

    /**
     * A tracked book's missing pieces (a shop's shouty title, no cover, no author) filled in from
     * Google Books: by its ISBN when known (then the ISBN is this edition's), else by its title. Blocking.
     */
    fun fillIn(w: PriceWatch): PriceWatch {
        val title = w.title ?: return w
        val tidy = tidyShopTitle(title)
        val needs = w.cover == null || w.author == null || tidy != title
        if (!needs) return w
        val q = w.isbn?.filter { it.isDigit() || it == 'X' }?.takeIf { it.length == 13 || it.length == 10 }?.let { "isbn:$it" }
            ?: ("intitle:" + withoutEdition(tidy) + (w.author?.let { " inauthor:$it" } ?: ""))
        val v = runCatching {
            JSONObject(get("https://www.googleapis.com/books/v1/volumes?maxResults=3&printType=books&q=" + URLEncoder.encode(q, "UTF-8")))
                .optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optJSONObject("volumeInfo") } }
                ?.firstOrNull { v -> q.startsWith("isbn:") || letters(Matcher.baseTitle(v.optString("title"))) == letters(Matcher.baseTitle(withoutEdition(tidy))) }
        }.getOrNull()
        val name = v?.optString("title")?.ifBlank { null }
        // The edition the shop page is for stays in the name ("Leviathan Wakes (10th Anniversary Edition)").
        val edition = Regex("(?i)\\b(\\d+(?:st|nd|rd|th)\\s+)?(anniversary|deluxe|special|collector's|limited|illustrated|signed)\\s+edition\\b").find(tidy)?.value
        val shown = when {
            name == null -> tidy
            edition != null && !name.contains(edition, true) -> "$name ($edition)"
            else -> name
        }
        return w.copy(
            title = shown,
            author = w.author ?: v?.optJSONArray("authors")?.optString(0)?.ifBlank { null },
            cover = w.cover ?: v?.optJSONObject("imageLinks")?.let { it.optString("thumbnail").ifBlank { it.optString("smallThumbnail") } }
                ?.ifBlank { null }?.replace("http://", "https://")?.replace("&edge=curl", ""),
        )
    }

    /** "Deluxe edition", "Special edition"… when the title says so. */
    fun editionOf(title: String?): String? = title?.let { EDITION.find(it)?.groupValues?.get(1) }
        ?.replace('-', ' ')?.lowercase()?.replaceFirstChar { it.uppercase() }?.let { if (it.endsWith("edges")) it else "$it edition" }

    /** Hardcovers first, then paperbacks, then anything else. */
    private fun formatRank(format: String?): Int = when {
        format == null -> 2
        Regex("(?i)hard ?(cover|back)").containsMatchIn(format) -> 0
        Regex("(?i)paper ?back|mass market").containsMatchIn(format) -> 1
        else -> 2
    }

    private fun pagesIn(text: String): Int? =
        Regex("""(?i)(?:print length|no\.? of pages|number of pages|pages)\s*(?:&rlm;|&lrm;|\u200f|\u200e|[:\s]|</?[a-z][^>]*>)*(\d{2,4})""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(?i)\b(\d{2,4})\s*pages\b""").find(text)?.groupValues?.get(1)?.toIntOrNull()

    private fun publisherIn(html: String): String? =
        Regex("""(?is)Publisher\s*(?:&rlm;|&lrm;|\u200f|\u200e|[:\s])*</span>\s*<span[^>]*>\s*([^<(;]{2,80})""").find(html)?.groupValues?.get(1)
            ?: Regex("""(?is)Publisher\s*(?:&rlm;|&lrm;|[:\s]|</?[a-z][^>]*>){1,12}([A-Za-z][^<(;]{1,80})""").find(html)?.groupValues?.get(1)
                ?.let { it.trim() }?.takeIf { it.isNotBlank() }

    /** The listing to follow among [found]: hardcovers before paperbacks, otherwise the stores' own order. */
    private fun pick(store: String, found: List<PriceListing>): PriceListing? {
        if (found.isEmpty()) return null
        listings.get()?.put(store, found)
        return found.withIndex().minWith(compareBy({ formatRank(it.value.format) }, { it.index })).value
    }

    /** Whether the book being checked is one exact edition (it was added from a store's page). */
    private val strictEdition = ThreadLocal<Boolean>()

    /** What to search a store for: the ISBN, then the title (without its subtitle) and the author's surname. */
    private fun queries(book: Book): List<String> {
        val surname = book.author?.split(",", "&", " and ")?.firstOrNull()?.trim()?.split(" ")?.lastOrNull()
        val title = tidyShopTitle(book.displayTitle).substringBefore(":").substringBefore(" (").trim().ifEmpty { book.displayTitle }
        return listOfNotNull(
            book.isbn?.filter { it.isDigit() || it == 'X' }?.takeIf { it.length == 13 || it.length == 10 },
            listOfNotNull(title, surname).joinToString(" "),
            // Without "10th Anniversary Edition" and the like, which shops word differently.
            listOfNotNull(withoutEdition(title), surname).joinToString(" "),
        ).distinct()
    }

    /** Every store's price for [book] now (one point per store, and Kindle beside print on Amazon). Stores that fail are left out. */
    fun check(book: Book, watch: PriceWatch, now: Long = System.currentTimeMillis() / 1000): Pair<List<PricePoint>, List<String>> {
        val out = ArrayList<PricePoint>()
        val problems = ArrayList<String>()
        strictEdition.set(watch.document.startsWith("price:"))
        listings.set(HashMap())
        for (s in shops) {
            try {
                out += when (s.id) {
                    "amazon" -> amazon(book, watch.links["amazon"], now, "amazon" in watch.pinned)
                    "bookswagon" -> bookswagon(book, watch.links["bookswagon"], now, "bookswagon" in watch.pinned)
                    else -> atlantic(book, watch.links["atlantic"], now, "atlantic" in watch.pinned)
                }
            } catch (e: Exception) {
                problems += "${s.name}: ${e.message ?: "couldn't read it"}"
            }
            Thread.sleep(400)
        }
        // Other shops (Flipkart, Crossword…): the pages you gave, read as they are.
        for ((id, link) in watch.links.filterKeys { it.startsWith("site:") }) {
            try {
                val p = sitePage(link)
                if (p.title == null && p.price == null) throw PriceException("couldn't read the page")
                out += PricePoint(id, now, p.price, null, p.inStock && p.price != null, p.format, link, p.title)
            } catch (e: Exception) {
                problems += "${shop(id)?.name ?: id}: ${e.message ?: "couldn't read it"}"
            }
        }
        return out to problems
    }

    // ---- Atlantic Books (Shopify) ----

    private fun atlantic(book: Book, link: String?, now: Long, pinned: Boolean = false): List<PricePoint> {
        val handle = link?.let { Regex("/products/([^/?#]+)").find(it)?.groupValues?.get(1) }
            ?: run {
                // Every listing of it (each edition read for its format, publisher and pages); a hardcover is followed first.
                val found = ArrayList<PriceListing>()
                for (qRaw in queries(book)) {
                    val q = URLEncoder.encode(qRaw, "UTF-8")
                    val o = runCatching { JSONObject(get("https://atlanticbooks.com/search/suggest.json?q=$q&resources[type]=product&resources[limit]=10")) }.getOrNull() ?: continue
                    val products = o.optJSONObject("resources")?.optJSONObject("results")?.optJSONArray("products") ?: continue
                    val handles = (0 until products.length()).map { products.getJSONObject(it) }
                        .filter { matches(book, it.optString("title")) }
                        .mapNotNull { it.optString("handle").takeIf { h -> h.isNotBlank() } }
                        .filter { h -> found.none { it.url.endsWith("/$h") } }
                        .take(6)
                    for (h in handles) {
                        val pj = runCatching { JSONObject(get("https://atlanticbooks.com/products/$h.js")) }.getOrNull() ?: continue
                        found += atlanticListing(h, pj)
                    }
                    if (found.isNotEmpty()) break
                }
                pick("atlantic", found)?.url?.substringAfterLast("/products/") ?: return listOf(PricePoint("atlantic", now, null, inStock = false))
            }
        val p = JSONObject(get("https://atlanticbooks.com/products/$handle.js"))
        if (!pinned && !matches(book, p.optString("title"))) return listOf(PricePoint("atlantic", now, null, inStock = false, url = if (link != null) "drop" else null))
        val v = p.optJSONArray("variants")?.optJSONObject(0)
        // Shopify gives prices in paise.
        val price = (v?.optLong("price") ?: p.optLong("price")).takeIf { it > 0 }?.let { it / 100.0 }
        val mrp = (v?.optLong("compare_at_price") ?: p.optLong("compare_at_price")).takeIf { it > 0 }?.let { it / 100.0 }
        val available = v?.optBoolean("available") ?: p.optBoolean("available", true)
        return listOf(PricePoint("atlantic", now, price, mrp?.takeIf { price == null || it > price }, available, atlanticListing(handle, p).format, "https://atlanticbooks.com/products/$handle", p.optString("title").ifBlank { null }))
    }

    /** An Atlantic product (Shopify's .js) as a listing: its binding from the variant, type or tags; its publisher (the vendor); pages from its description. */
    private fun atlanticListing(handle: String, p: JSONObject): PriceListing {
        val v = p.optJSONArray("variants")?.optJSONObject(0)
        val tags = p.optJSONArray("tags")?.let { t -> (0 until t.length()).map { t.optString(it) } }.orEmpty()
        val body = p.optString("description").ifBlank { p.optString("body_html") }
        val format = listOfNotNull(v?.optString("title")?.takeIf { it != "Default Title" }, p.optString("type").ifBlank { null })
            .plus(tags).firstOrNull { Regex("(?i)hard ?(cover|back)|paper ?back|mass market|board book").containsMatchIn(it) }
            ?.let { Regex("(?i)hard ?(cover|back)|paper ?back|mass market|board book").find(it)!!.value.replaceFirstChar { c -> c.uppercase() } }
            ?: Regex("(?i)\\b(hardcover|hardback|paperback)\\b").find(body)?.value?.replaceFirstChar { it.uppercase() }
        val pages = tags.firstNotNullOfOrNull { Regex("(?i)pages?\\D{0,3}(\\d{2,4})").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: pagesIn(body)
        val title = p.optString("title").ifBlank { null }
        val price = ((v?.optLong("price") ?: p.optLong("price")).takeIf { it > 0 })?.let { it / 100.0 }
        return PriceListing("atlantic", "https://atlanticbooks.com/products/$handle", title, format, p.optString("vendor").ifBlank { null }, pages, editionOf(title), price)
    }

    // ---- Bookswagon ----

    private val BW_LINK = Regex("""href="((?:https://www\.bookswagon\.com)?/book/[^"]+/(\d{13}))"""")

    private fun bookswagon(book: Book, link: String?, now: Long, pinned: Boolean = false): List<PricePoint> {
        val isbn = isbn13(book)
        fun abs(u: String) = if (u.startsWith("/")) "https://www.bookswagon.com$u" else u
        fun titleOf(html: String) = bwTitle(html)
        var url: String? = link
        var page0: String? = null
        var rejected = false
        if (url != null) {
            page0 = get(url)
            // A page found earlier (not one you chose) that turns out to be another book is let go.
            // (Any printing of it counts here: Bookswagon often has another edition than the one followed.)
            val linkIsbn = link?.let { Regex("/(\\d{13})").find(it)?.groupValues?.get(1) }
            val linkTitle = titleOf(page0!!)
            if (!pinned && !loose { matches(book, linkTitle, linkIsbn) }) { url = null; page0 = null; rejected = true }
        }
        // Bookswagon's own search first (/search-books/leviathan-wakes): one page lists every edition it
        // has, each with its price, so nothing more needs opening to compare them.
        var tile: BwTile? = null
        if (url == null) {
            val title = withoutEdition(tidyShopTitle(book.displayTitle)).substringBefore(":").substringBefore(" (").trim()
            val surname = book.author?.split(",", "&", " and ")?.firstOrNull()?.trim()?.split(" ")?.lastOrNull()
            val searches = listOfNotNull(title, surname?.let { "$title $it" }, isbn).distinct()
            for (qRaw in searches) {
                val slug = qRaw.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                val page = bwGet("https://www.bookswagon.com/search-books/$slug", BW_LINK)?.takeIf { BW_LINK.containsMatchIn(it) } ?: continue
                val tiles = bwTiles(page)
                // Your exact edition first, then the others that are this book, in Bookswagon's order.
                val ours = tiles.filter { t -> (isbn != null && t.isbn == isbn) || loose { matches(book, t.title, t.isbn) } }
                    .sortedBy { if (isbn != null && it.isbn == isbn) 0 else 1 }
                if (ours.isEmpty()) continue
                val found = ours.map { t -> PriceListing("bookswagon", t.url, t.title, t.format, null, null, editionOf(t.title), t.price) }
                val chosen = if (isbn != null && ours.first().isbn == isbn) found.first().also { listings.get()?.put("bookswagon", found) } else pick("bookswagon", found)
                if (chosen != null) {
                    tile = ours.firstOrNull { it.url == chosen.url }
                    url = chosen.url
                    page0 = bwGet(chosen.url)
                    break
                }
            }
        }
        // Then its pages addressed by ISBN (/book/<name>/<isbn>): the book's own ISBN, then the ISBNs
        // Google Books knows for the title (its other printings), each opened straight away.
        if (url == null) {
            val found = ArrayList<PriceListing>()
            val pages = HashMap<String, String>()
            fun tryIsbn(i: String) {
                for (u in bwAddresses(book, i)) {
                    val h = bwGet(u) ?: continue
                    val t = titleOf(h) ?: continue
                    if (matches(book, t, i)) { pages[u] = h; found += bookswagonListing(u, h) }
                    return
                }
            }
            isbn?.let { tryIsbn(it) }
            if (found.isEmpty()) {
                loose { for (i in googleIsbns(book).filter { it != isbn }.take(5)) tryIsbn(i) }
            }
            val best = pick("bookswagon", found)
            if (best != null) { url = best.url; page0 = pages[best.url] }
        }
        if (url == null) return listOf(PricePoint("bookswagon", now, null, inStock = false, url = if (rejected) "drop" else null))
        // Found in the search but its own page wouldn't open: the search's price stands.
        if (page0 == null) {
            val t = tile ?: return listOf(PricePoint("bookswagon", now, null, inStock = false, url = url))
            return listOf(PricePoint("bookswagon", now, t.price, t.mrp?.takeIf { t.price == null || it > t.price }, t.price != null, t.format, url, t.title))
        }
        var html: String = page0!!
        // A page whose price is filled in by its script: read as a browser would.
        if (bwPrice(html) == null) browser?.invoke(url!!)?.takeIf { bwPrice(it) != null }?.let { html = it }
        val title = titleOf(html)
        // Structured data first, then the page's own price labels.
        val ld = Regex(""""price"\s*:\s*"?([\d,.]+)"?""").find(html)?.groupValues?.get(1)
        val labelled = Regex("""(?is)(?:our\s*price|offer\s*price|lblOfferPrice|actualprice|sell(?:ing)?\s*price)[^₹]{0,200}₹\s*([\d,]+(?:\.\d+)?)""").find(html)?.groupValues?.get(1)
        val mrp = Regex("""(?is)(?:M\.?R\.?P\.?|lblMrp|mrp)[^₹]{0,120}₹\s*([\d,]+(?:\.\d+)?)""").find(html)?.groupValues?.get(1)
        val price = money(ld) ?: money(labelled) ?: bwPrice(html) ?: tile?.price
        val out = Regex("""(?i)out\s+of\s+stock|currently\s+unavailable|not\s+available""").containsMatchIn(html) && price == null
        val binding = bookswagonListing(url!!, html).format
        val listPrice = money(mrp) ?: tile?.mrp
        return listOf(PricePoint("bookswagon", now, price, listPrice?.takeIf { price == null || it > price }, !out && price != null, binding ?: tile?.format, url, title ?: tile?.title))
    }

    /** One book in Bookswagon's search results: its page, ISBN, title, and the prices shown under it. */
    private data class BwTile(val url: String, val isbn: String, val title: String?, val price: Double?, val mrp: Double?, val format: String?)

    /**
     * The books on a Bookswagon search page, each with its price and list price (the struck-out one),
     * read from the part of the page between its link and the next book's.
     */
    private fun bwTiles(page: String): List<BwTile> {
        val links = BW_LINK.findAll(page).toList()
        val out = LinkedHashMap<String, BwTile>()
        for ((k, m) in links.withIndex()) {
            val isbn = m.groupValues[2]
            if (isbn in out) continue
            val end = links.drop(k + 1).firstOrNull { it.groupValues[2] != isbn }?.range?.first ?: (m.range.last + 6000).coerceAtMost(page.length)
            val chunk = page.substring(m.range.first, end)
            val parts = chunk.replace(Regex("(?is)<(script|style)\\b.*?</\\1>"), " ").split(Regex("<[^>]+>"))
                .map { unescape(it).replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
            val amounts = Regex("(?:₹|Rs\\.?)\\s*([\\d,]+(?:\\.\\d+)?)").findAll(parts.joinToString(" ")).mapNotNull { money(it.groupValues[1]) }.toList()
            val (mrp, price) = when {
                amounts.size >= 2 -> if (amounts[0] >= amounts[1]) amounts[0] to amounts[1] else amounts[1] to amounts[0]
                else -> null to amounts.firstOrNull()
            }
            // Its title: the line just before "By:", else the picture's description.
            val byAt = parts.indexOfFirst { it.startsWith("By", true) && (it.length <= 3 || it[2] == ':' || it[2] == ' ') }
            val title = parts.getOrNull(byAt - 1)?.takeIf { byAt > 0 && !it.endsWith("%") && !it.contains('₹') }
                ?: Regex("""(?i)(?:alt|title)="([^"]{3,200})"""").find(chunk)?.groupValues?.get(1)?.let(::unescape)
            val format = Regex("(?i)\\b(Paperback|Hardcover|Hardback|Board book|Mass Market)\\b").find(chunk)?.value?.replaceFirstChar { it.uppercase() }?.replace("Hardback", "Hardcover")
            val u = m.groupValues[1].let { if (it.startsWith("/")) "https://www.bookswagon.com$it" else it }
            out[isbn] = BwTile(u, isbn, title?.trim(), price, mrp, format)
        }
        return out.values.toList()
    }

    /** A Bookswagon page: fetched plainly, else through the unseen browser (its bot check turns some requests away). */
    private fun bwGet(u: String, ready: Regex? = BW_PAGE): String? =
        runCatching { get(u) }.getOrNull()?.takeIf { it.length > 2000 && !it.contains("Just a moment", true) && (ready == null || ready.containsMatchIn(it)) }
            // Not there in the plain page (turned away, or filled in by the shop's script): opened in the
            // app's unseen browser, which waits for it.
            ?: browserUntil?.invoke(u, ready) ?: browser?.invoke(u)

    /** A Bookswagon page's title: its heading (tags inside it dropped), else its og:title. */
    private fun bwTitle(html: String): String? =
        Regex("""(?is)<h1[^>]*>(.*?)</h1>""").find(html)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), " ")?.let(::unescape)
            ?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotBlank() }
            ?: meta(html, "og:title")?.let(::unescape)?.substringBefore(" | ")?.removePrefix("Buy ")?.substringBefore(" by ")?.trim()

    /** A Bookswagon book page that has loaded: its title heading. */
    private val BW_PAGE = Regex("<h1[^>]*>\\s*[^<\\s]")

    /** The selling price on a Bookswagon page, however it's marked up. */
    private fun bwPrice(html: String): Double? =
        money(Regex(""""price"\s*:\s*"?([\d,.]+)"?""").find(html)?.groupValues?.get(1))
            ?: money(Regex("""itemprop=["']price["'][^>]*content=["']([\d.,]+)""").find(html)?.groupValues?.get(1))
            ?: money(Regex("""(?is)(?:our\s*price|offer\s*price|lblOfferPrice|lblourprice|actualprice|sell(?:ing)?\s*price|price-?box|sellingprice)[^₹]{0,240}₹\s*([\d,]+(?:\.\d+)?)""").find(html)?.groupValues?.get(1))

    /**
     * Bookswagon's address for a book's page: /book/<the title's and author's main words>/<ISBN>
     * ("infinite-state-richard-swan/9781250386229"), as the shop writes it, then a plain one.
     */
    private fun bwAddresses(book: Book, isbn: String): List<String> {
        val stop = setOf("the", "a", "an", "of", "and", "in", "to", "for", "on", "at", "by", "with", "from", "edition", "book", "novel")
        fun words(t: String?) = t.orEmpty().lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(' ').filter { it.isNotBlank() && it !in stop }
        val title = withoutEdition(tidyShopTitle(book.displayTitle)).substringBefore(":")
        val slug = (words(title).take(4) + words(book.author?.substringBefore(',')?.substringBefore('&'))).take(5).joinToString("-").ifBlank { "book" }
        return listOf("https://www.bookswagon.com/book/$slug/$isbn", "https://www.bookswagon.com/book/book/$isbn").distinct()
    }

    /** The ISBN-13s Google Books knows for this title (its printings), for opening Bookswagon's pages directly. */
    private fun googleIsbns(book: Book): List<String> = runCatching {
        val q = "intitle:" + withoutEdition(tidyShopTitle(book.displayTitle)).substringBefore(":").trim() +
            (book.author?.substringBefore(',')?.trim()?.split(' ')?.lastOrNull()?.let { " inauthor:$it" } ?: "")
        val items = JSONObject(get("https://www.googleapis.com/books/v1/volumes?maxResults=10&printType=books&q=" + URLEncoder.encode(q, "UTF-8"))).optJSONArray("items")
            ?: return@runCatching emptyList<String>()
        (0 until items.length()).mapNotNull { items.optJSONObject(it)?.optJSONObject("volumeInfo") }
            .filter { v -> matches(book.copy(isbn = null), v.optString("title")) }
            .flatMap { v -> v.optJSONArray("industryIdentifiers")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty() }
            .filter { it.optString("type") == "ISBN_13" }.map { it.optString("identifier") }.filter { it.length == 13 }.distinct()
    }.getOrDefault(emptyList())

    /** A Bookswagon page as a listing: binding, publisher, pages and price. */
    private fun bookswagonListing(url: String, html: String): PriceListing {
        val title = bwTitle(html)
        val binding = Regex("""(?i)(?:binding|format)\s*(?:[:\s]|</?[a-z][^>]*>){0,8}(Paperback|Hardcover|Hardback|Board book|Mass Market)""").find(html)?.groupValues?.get(1)
            ?: Regex("""(?i)\b(Paperback|Hardcover|Hardback|Board book|Mass Market)\b""").find(html)?.value
        val price = money(Regex(""""price"\s*:\s*"?([\d,.]+)"?""").find(html)?.groupValues?.get(1))
        return PriceListing("bookswagon", url, title, binding, publisherIn(html), pagesIn(html), editionOf(title), price)
    }

    // ---- Amazon.in ----

    private val ASIN = Regex("""data-asin="([A-Z0-9]{10})"""")

    private fun amazon(book: Book, link: String?, now: Long, pinned: Boolean = false): List<PricePoint> {
        fun titleOf(html: String) = Regex("""id="productTitle"[^>]*>\s*([^<]+)<""").find(html)?.groupValues?.get(1)?.trim()
        // A pasted short link (amzn.in/d/…) is followed to its product.
        val linked = link?.let { Regex("/(?:dp|gp/product|d)/([A-Z0-9]{10})").find(it)?.groupValues?.get(1) ?: amazonAsin(it) }
        var asin: String? = null
        var html: String? = null
        var title: String? = null
        if (linked != null) {
            asin = linked
            html = get("https://www.amazon.in/dp/$linked")
            title = titleOf(html)
            if (!pinned && !matches(book, title, isbnIn(html))) {
                // The saved page is no longer this book: look again below, and drop the old one.
                asin = null; html = null; title = null
            }
        }
        if (asin == null) {
            // A printed book's ASIN is its ISBN-10: that exact edition first.
            val candidates = ArrayList<String>()
            isbn10(book)?.let { candidates += it }
            // Then search, by ISBN and by title and author (an ISBN Amazon doesn't list finds nothing),
            // reading the first few results (sponsored ones left out) until one is this book.
            for (qRaw in (listOfNotNull(isbn13(book)) + queries(book)).distinct()) {
                if (candidates.size >= 6) break
                val page = runCatching { get("https://www.amazon.in/s?k=${URLEncoder.encode(qRaw, "UTF-8")}&i=stripbooks") }.getOrNull() ?: continue
                candidates += Regex("""(?s)<div[^>]+data-asin="([A-Z0-9]{10})"[^>]*data-component-type="s-search-result"(.{0,1500})""")
                    .findAll(page).filter { !it.groupValues[2].contains("AdHolder") && !it.groupValues[2].contains("Sponsored") }
                    .map { it.groupValues[1] }.toList()
                    .ifEmpty { ASIN.findAll(page).map { it.groupValues[1] }.filter { it.isNotBlank() }.toList() }
                    .take(3)
            }
            val pages = HashMap<String, String>()
            val found = ArrayList<PriceListing>()
            for (a in candidates.distinct().take(6)) {
                val h = runCatching { get("https://www.amazon.in/dp/$a") }.getOrNull() ?: continue
                val t = titleOf(h)
                if (pinned || matches(book, t, isbnIn(h))) {
                    val l = amazonListing(a, h, t)
                    // Kindle editions are priced beside print; the listings to choose from are printed ones.
                    if (l.format?.contains("Kindle", true) == true) continue
                    pages[a] = h; found += l
                }
            }
            val chosen = pick("amazon", found)
            if (chosen != null) { asin = chosen.url.substringAfterLast("/dp/"); html = pages[chosen.url.substringAfterLast("/dp/")]; title = chosen.title }
            if (asin == null) return listOf(PricePoint("amazon", now, null, inStock = false, url = if (link != null) "drop" else null))
        }
        val url = "https://www.amazon.in/dp/$asin"
        html!!
        val price = amazonPrice(html)
        val mrp = amazonMrp(html)
        val unavailable = Regex("""(?i)Currently unavailable|Temporarily out of stock""").containsMatchIn(html) && price == null
        val format = amazonFormat(html)
        val out = arrayListOf(PricePoint("amazon", now, price, mrp?.takeIf { price == null || it > price }, !unavailable && price != null, format ?: "Print", url, title))
        // The Kindle edition's price, from the formats offered beside it.
        Regex("""(?s)Kindle Edition.{0,600}?₹\s*([\d,]+(?:\.\d+)?)""").find(html)?.groupValues?.get(1)?.let(::money)
            ?.takeIf { format?.contains("Kindle", true) != true }
            ?.let { out += PricePoint("amazon", now, it, null, true, "Kindle", url, title) }
        return out
    }

    /**
     * The price you'd pay on an Amazon page for the format it's open on: the buying options' own
     * data (a "NEW" offer), the hidden base price, then the buy box's "price to pay" (never the M.R.P.,
     * an EMI, a cashback or another product's price elsewhere on the page). A reading far under the
     * page's M.R.P. is taken as a misread and dropped.
     */
    private fun amazonPrice(html: String): Double? {
        val mrp = amazonMrp(html)
        val buyBox = Regex("""(?s)id="(?:corePriceDisplay_desktop_feature_div|corePrice_feature_div|apex_offerDisplay_desktop|apex_desktop)".{0,3000}""").find(html)?.value.orEmpty()
        val candidates = sequence {
            Regex("""(?s)twister-plus-buying-options-price-data[^>]*>\s*(\{.*?\}|\[.*?\])\s*</""").find(html)?.groupValues?.get(1)?.let { j ->
                Regex(""""displayPrice"\s*:\s*"[^"\d]*([\d,]+(?:\.\d+)?)"[^}]*"buyingOptionType"\s*:\s*"NEW"""").find(j)?.groupValues?.get(1)?.let { yield(money(it)) }
                Regex(""""priceAmount"\s*:\s*([\d.]+)""").find(j)?.groupValues?.get(1)?.let { yield(money(it)) }
            }
            Regex("""id="(?:attach-base-product-price|twister-plus-price-data-price)"[^>]*value="([\d.]+)"""").find(html)?.groupValues?.get(1)?.let { yield(money(it)) }
            Regex("""(?s)class="[^"]*(?:priceToPay|apexPriceToPay)[^"]*".{0,600}?a-offscreen">\s*₹\s*([\d,]+(?:\.\d+)?)""").find(buyBox)?.groupValues?.get(1)?.let { yield(money(it)) }
            Regex("""(?s)class="a-price-whole">\s*([\d,]+)""").find(buyBox)?.groupValues?.get(1)?.let { yield(money(it)) }
            Regex("""id="(?:price|newBuyBoxPrice|price_inside_buybox)"[^>]*>\s*₹\s*([\d,]+(?:\.\d+)?)""").find(html)?.groupValues?.get(1)?.let { yield(money(it)) }
        }
        return candidates.filterNotNull().firstOrNull { mrp == null || it >= mrp * 0.25 }
    }

    /** The page's M.R.P. (the printed price, struck through). */
    private fun amazonMrp(html: String): Double? =
        money(Regex("""(?s)M\.R\.P\.?:?.{0,300}?a-offscreen">\s*₹\s*([\d,]+(?:\.\d+)?)""").find(html)?.groupValues?.get(1))

    /** The format the Amazon page is for: its subtitle, the "Format:" line, the selected format swatch, or the binding in its data. */
    private fun amazonFormat(html: String): String? =
        Regex("""(?s)id="productSubtitle"[^>]*>\s*([A-Za-z ]+)""").find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.length in 3..29 }
            ?: Regex("""(?s)Format:\s*(?:</[^>]+>\s*)*(?:<[^>]+>\s*)*([A-Za-z][A-Za-z ]{2,28})""").find(html)?.groupValues?.get(1)?.trim()
            ?: Regex("""id="tmm-grid-swatch-([A-Z_]+)"[^>]*class="[^"]*\bselected""").find(html)?.groupValues?.get(1)?.let(::swatchName)
            ?: Regex("""class="[^"]*\bselected[^"]*"[^>]*id="tmm-grid-swatch-([A-Z_]+)"""").find(html)?.groupValues?.get(1)?.let(::swatchName)
            ?: Regex("""(?s)class="[^"]*swatchElement selected[^"]*".{0,400}?<span>\s*([A-Za-z][A-Za-z ]{2,28})\s*</span>""").find(html)?.groupValues?.get(1)?.trim()
            ?: Regex(""""binding"\s*:\s*"([A-Za-z ]{3,28})"""").find(html)?.groupValues?.get(1)

    private fun swatchName(id: String) = id.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** An Amazon product page as a listing: its format, publisher, pages and price. */
    private fun amazonListing(asin: String, html: String, title: String?): PriceListing {
        val format = amazonFormat(html)
        val price = amazonPrice(html)
        return PriceListing("amazon", "https://www.amazon.in/dp/$asin", title, format, publisherIn(html)?.substringBefore(";")?.trim(), pagesIn(html), editionOf(title), price)
    }

    // ---- a book from a store's page ----

    /** A book as one store's page describes it. */
    data class Found(
        val store: String, val url: String, val title: String, val author: String?, val isbn: String?, val cover: String?,
        val points: List<PricePoint>,
    )

    private fun meta(html: String, prop: String): String? =
        Regex("""<meta[^>]+(?:property|name)="$prop"[^>]+content="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: Regex("""<meta[^>]+content="([^"]+)"[^>]+(?:property|name)="$prop"""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)

    private fun unescape(s: String) = android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()

    private fun isbnIn(html: String): String? =
        Regex("""(?is)ISBN-13[^0-9]{0,40}(97[89][-\s]?[0-9][-0-9\s]{8,14})""").find(html)?.groupValues?.get(1)?.filter { it.isDigit() }?.takeIf { it.length == 13 }
            ?: Regex("""(?is)ISBN-10[^0-9]{0,40}([0-9]{9}[0-9Xx])""").find(html)?.groupValues?.get(1)

    /**
     * Reads the book on a pasted Amazon.in, Bookswagon or Atlantic Books page: its title, author,
     * ISBN, cover and price there. Short links (amzn.in…) are followed. Blocking.
     */
    fun identify(link: String, now: Long = System.currentTimeMillis() / 1000): Found {
        val shop = shopOf(link) ?: if (trackable(link) != null) return identifySite(link, now) else throw PriceException("That isn't a bookshop's page")
        return when (shop) {
            "atlantic" -> {
                val handle = Regex("/products/([^/?#]+)").find(link)?.groupValues?.get(1) ?: throw PriceException("That Atlantic Books address isn't a book's page")
                val p = JSONObject(get("https://atlanticbooks.com/products/$handle.js"))
                val v = p.optJSONArray("variants")?.optJSONObject(0)
                val barcode = v?.optString("barcode")?.filter { it.isDigit() }?.takeIf { it.length == 13 || it.length == 10 }
                    ?: v?.optString("sku")?.filter { it.isDigit() }?.takeIf { it.length == 13 && it.startsWith("97") }
                val author = p.optJSONArray("tags")?.let { t -> (0 until t.length()).map { t.optString(it) }.firstOrNull { it.startsWith("author:", true) || it.startsWith("Author_", true) } }
                    ?.substringAfter(':')?.substringAfter('_')?.trim()
                val cover = p.optString("featured_image").ifBlank { null }?.let { if (it.startsWith("//")) "https:$it" else it }
                val title = p.optString("title").ifBlank { throw PriceException("No book on that page") }
                val fake = Book(document = "x", title = title, author = author, percentage = 0.0, status = null, timestamp = 0, isbn = barcode)
                Found("atlantic", "https://atlanticbooks.com/products/$handle", title, author, barcode, cover, atlantic(fake, link, now, pinned = true))
            }
            "bookswagon" -> {
                val r = fetch(link)
                val url = r.url.ifBlank { link }
                val html = r.body
                val title = Regex("""<h1[^>]*>\s*([^<]+)</h1>""").find(html)?.groupValues?.get(1)?.let(::unescape)
                    ?: meta(html, "og:title")?.let(::unescape) ?: throw PriceException("No book on that page")
                val author = Regex("""(?is)(?:\bby\b|author)[^<]{0,30}<a[^>]*>([^<]{2,80})</a>""").find(html)?.groupValues?.get(1)?.let(::unescape)
                val isbn = Regex("/(\\d{13})").find(url)?.groupValues?.get(1) ?: isbnIn(html)
                val fake = Book(document = "x", title = title, author = author, percentage = 0.0, status = null, timestamp = 0, isbn = isbn)
                Found("bookswagon", url, title, author, isbn, meta(html, "og:image"), bookswagon(fake, url, now, pinned = true))
            }
            else -> {
                // A short link (amzn.in/d/…, amzn.to/…): its redirects followed one by one, so the
                // product's ASIN is read from the address even when the page itself is a robot check.
                val asin = amazonAsin(link) ?: throw PriceException("That Amazon address isn't a product's page")
                val url = "https://www.amazon.in/dp/$asin"
                fun titleIn(html: String): String? =
                    Regex("""id="productTitle"[^>]*>\s*([^<]+)<""").find(html)?.groupValues?.get(1)?.let(::unescape)?.takeIf { it.isNotBlank() }
                        ?: Regex("""id="title"[^>]*>\s*(?:<span[^>]*>)?\s*([^<]{3,})<""").find(html)?.groupValues?.get(1)?.let(::unescape)?.takeIf { it.isNotBlank() }
                        ?: meta(html, "title")?.let(::unescape)?.substringBefore(" : ")?.substringBefore(": Amazon")?.takeIf { it.isNotBlank() && !it.contains("Amazon.in", true) }
                        ?: Regex("""(?is)<title>\s*(?:Amazon\.in\s*:\s*(?:Buy\s+)?)?([^<]+?)\s*(?:Book Online at.*|:\s*Amazon\.in.*)?</title>""").find(html)?.groupValues?.get(1)
                            ?.let(::unescape)?.takeIf { it.isNotBlank() && !it.contains("Robot", true) && !it.equals("Amazon.in", true) }
                // The product page, then its mobile page (a robot check on one often lets the other through).
                var html = ""
                var title: String? = null
                for (u in listOf(url, "https://www.amazon.in/gp/aw/d/$asin")) {
                    val h = runCatching { get(u) }.getOrNull() ?: continue
                    titleIn(h)?.let { html = h; title = it }
                    if (title != null) break
                }
                var author: String? = null
                var cover: String? = null
                var isbn: String? = null
                if (title != null) {
                    author = Regex("""(?s)class="author[^"]*".{0,400}?<a[^>]*>([^<]{2,80})</a>""").find(html)?.groupValues?.get(1)?.let(::unescape)
                    cover = Regex("""data-old-hires="(https[^"]+)"""").find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                        ?: Regex(""""hiRes":"(https[^"]+)"""").find(html)?.groupValues?.get(1)
                        ?: Regex("""id="(?:landingImage|imgBlkFront|ebooksImgBlkFront)"[^>]*src="(https[^"]+)"""").find(html)?.groupValues?.get(1)
                    isbn = isbnIn(html)
                } else if (Regex("^[0-9]{9}[0-9X]$").matches(asin)) {
                    // Amazon wouldn't show the page, but a printed book's ASIN is its ISBN: the book from Google Books.
                    val v = runCatching {
                        JSONObject(get("https://www.googleapis.com/books/v1/volumes?q=isbn:$asin")).optJSONArray("items")?.optJSONObject(0)?.optJSONObject("volumeInfo")
                    }.getOrNull()
                    title = v?.optString("title")?.ifBlank { null }?.let { t -> v.optString("subtitle").ifBlank { null }?.let { "$t: $it" } ?: t }
                    author = v?.optJSONArray("authors")?.optString(0)?.ifBlank { null }
                    cover = v?.optJSONObject("imageLinks")?.optString("thumbnail")?.ifBlank { null }?.replace("http://", "https://")
                    isbn = asin
                }
                val t = title ?: throw PriceException("Amazon asked to check this isn't a robot, so the page couldn't be read. Try again in a minute, or paste the book's Bookswagon or Atlantic page.")
                val fake = Book(document = "x", title = t, author = author, percentage = 0.0, status = null, timestamp = 0, isbn = isbn)
                // Its price now when Amazon shows it; otherwise on the next check.
                val points = runCatching { amazon(fake, url, now, pinned = true) }.getOrDefault(emptyList())
                Found("amazon", url, t, author, isbn, cover, points)
            }
        }
    }

    // ---- any other bookshop's page ----

    /** What a shop's page says of its book. */
    private data class SitePage(val url: String, val title: String?, val author: String?, val isbn: String?, val cover: String?, val price: Double?, val inStock: Boolean, val format: String?)

    /** A book from another shop's page (Flipkart, Crossword…): read, its ISBN filled in from Google Books where the page has none. */
    private fun identifySite(link: String, now: Long): Found {
        val p = sitePage(link)
        val id = trackable(p.url) ?: trackable(link) ?: throw PriceException("That isn't a bookshop's page")
        var title = p.title
        var author = p.author
        var isbn = p.isbn
        var cover = p.cover
        // The address names it when the page wouldn't show (flipkart.com/last-song-of-ash-paperback-adrian-tchaikovsky/p/itm…).
        if (title == null) slugBook(p.url)?.let { (t, a) -> title = t; if (author == null) author = a }
        val t = title ?: throw PriceException("Couldn't read the book on that page (the shop may have turned the app away). Try its page on Amazon.in, Bookswagon or Atlantic Books.")
        if (isbn == null || author == null) {
            runCatching { PostBooks.lookUp(t, author) }.getOrNull()?.takeIf { it.from != "typed" }?.let { f ->
                if (isbn == null) isbn = f.isbn
                if (author == null) author = f.author
                if (cover == null) cover = f.cover
            }
        }
        return Found(id, p.url, t, author, isbn, cover, listOf(PricePoint(id, now, p.price, null, p.inStock && p.price != null, p.format, p.url, t)))
    }

    /** A shop's page: its schema.org data (what shops give search engines), else its tags, else its words. */
    private fun sitePage(link: String): SitePage {
        val r = runCatching { fetch(link) }.getOrNull()
        val url = r?.url?.ifBlank { null } ?: link
        var html = r?.body.orEmpty()
        if (products(html).isEmpty() && meta(html, "og:title") == null) browser?.invoke(url)?.let { html = it }
        val ld = products(html)
        val named = ld.firstOrNull { it.optString("name").isNotBlank() }
        val offer = ld.firstNotNullOfOrNull { offerOf(it.opt("offers")) }
        val title = (named?.optString("name")?.ifBlank { null } ?: meta(html, "og:title")
            ?: Regex("(?is)<h1[^>]*>(.*?)</h1>").find(html)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), ""))
            ?.let(::unescape)?.let(::cleanTitle)?.takeIf { it.length >= 2 }
        val author = ld.firstNotNullOfOrNull { authorOf(it.opt("author")) }
            ?: meta(html, "book:author")?.takeIf { !it.startsWith("http") } ?: meta(html, "author")?.takeIf { it.length in 3..60 }
        val isbn = (ld.firstNotNullOfOrNull { o -> o.optString("isbn").ifBlank { o.optString("gtin13") }.ifBlank { null } } ?: meta(html, "book:isbn"))
            ?.filter { it.isDigit() || it == 'X' }?.takeIf { it.length == 13 || it.length == 10 } ?: isbnIn(html)
        val cover = ld.firstNotNullOfOrNull { imageOf(it.opt("image")) } ?: meta(html, "og:image")
        val price = offer?.let { o -> money(o.optString("price").ifBlank { o.optString("lowPrice") }) }
            ?: money(meta(html, "product:price:amount")) ?: money(meta(html, "og:price:amount"))
            ?: Regex("""itemprop="price"[^>]*content="([0-9.,]+)"""").find(html)?.groupValues?.get(1)?.let(::money)
        val availability = offer?.optString("availability")?.ifBlank { null } ?: meta(html, "product:availability") ?: meta(html, "og:availability")
        val inStock = availability?.let { a -> !Regex("(?i)out.?of.?stock|sold.?out|discontinued|unavailable").containsMatchIn(a) } ?: true
        val format = ld.firstNotNullOfOrNull { it.optString("bookFormat").substringAfterLast('/').ifBlank { null } }
            ?.replace("EBook", "Ebook")?.replace("Hardback", "Hardcover")
            ?: Regex("(?i)\\b(paperback|hardcover|hardback|board book)\\b").find(title.orEmpty() + " " + url)?.value?.lowercase()?.replaceFirstChar { it.uppercase() }?.replace("Hardback", "Hardcover")
        return SitePage(url, title, author, isbn, cover, price, inStock, format)
    }

    /** The page's schema.org Book and Product entries. */
    private fun products(html: String): List<JSONObject> {
        val found = ArrayList<JSONObject>()
        fun types(o: JSONObject): List<String> = when (val t = o.opt("@type")) {
            is String -> listOf(t)
            is org.json.JSONArray -> (0 until t.length()).map { t.optString(it) }
            else -> emptyList()
        }
        fun walk(x: Any?, depth: Int) {
            if (depth > 6) return
            when (x) {
                is JSONObject -> {
                    if (types(x).any { it == "Book" || it == "Product" || it == "ProductGroup" }) found += x
                    x.keys().forEach { k -> walk(x.opt(k), depth + 1) }
                }
                is org.json.JSONArray -> for (i in 0 until x.length()) walk(x.opt(i), depth + 1)
            }
        }
        Regex("""(?is)<script[^>]*application/ld\+json[^>]*>(.*?)</script>""").findAll(html).forEach { m ->
            runCatching { org.json.JSONTokener(m.groupValues[1].trim()).nextValue() }.getOrNull()?.let { walk(it, 0) }
        }
        return found
    }

    /** An offer with a price, in rupees where there's a choice. */
    private fun offerOf(x: Any?): JSONObject? = when (x) {
        is JSONObject -> if (x.has("price") || x.has("lowPrice")) x else if (x.has("offers")) offerOf(x.opt("offers")) else null
        is org.json.JSONArray -> {
            val all = (0 until x.length()).mapNotNull { offerOf(x.opt(it)) }
            all.firstOrNull { o -> o.optString("priceCurrency").let { it.isBlank() || it == "INR" } } ?: all.firstOrNull()
        }
        else -> null
    }

    private fun authorOf(x: Any?): String? = when (x) {
        is String -> x.ifBlank { null }
        is JSONObject -> x.optString("name").ifBlank { null }
        is org.json.JSONArray -> (0 until x.length()).mapNotNull { authorOf(x.opt(it)) }.take(2).joinToString(", ").ifBlank { null }
        else -> null
    }?.let(::unescape)

    private fun imageOf(x: Any?): String? = when (x) {
        is String -> x.ifBlank { null }
        is JSONObject -> x.optString("url").ifBlank { x.optString("contentUrl") }.ifBlank { null }
        is org.json.JSONArray -> (0 until x.length()).firstNotNullOfOrNull { imageOf(x.opt(it)) }
        else -> null
    }?.let { if (it.startsWith("//")) "https:$it" else it }

    /** A shop's page title without the shop's words: "Buy X Book Online at…", "X (Paperback) | Flipkart". */
    private fun cleanTitle(t: String): String = t
        .replace(Regex("(?i)^buy\\s+"), "")
        .replace(Regex("(?i)\\s+(?:book\\s+)?online\\s+at\\s+.*$"), "")
        .replace(Regex("(?i)\\s*[|–—-]\\s*(?:buy|shop|order|online|flipkart|amazon|crossword|sapna|bookchor|best price).*$"), "")
        .replace(Regex("(?i)\\s*:\\s*buy\\b.*$"), "")
        .replace(Regex("(?i)\\s*\\((?:paperback|hardcover|hardback|english|board book)[^)]*\\)"), "")
        .trim().trim('-', '|', ':').trim()

    /** A book named by a shop's address: "last-song-of-ash-paperback-adrian-tchaikovsky" -> Last Song of Ash, Adrian Tchaikovsky. */
    private fun slugBook(url: String): Pair<String, String?>? {
        val path = runCatching { java.net.URI(url).path }.getOrNull() ?: return null
        val seg = path.split('/').filter { it.contains('-') && it.count { c -> c.isLetter() } >= 4 }.maxByOrNull { it.length } ?: return null
        val words = seg.lowercase().split('-', '_').filter { it.isNotBlank() && !(it.all { c -> c.isDigit() } && it.length >= 5) }
        val fmt = setOf("paperback", "hardcover", "hardback", "english", "ebook", "kindle", "edition", "book", "books", "buy", "online")
        val cut = words.indexOfFirst { it in fmt }
        val tw = if (cut > 0) words.take(cut) else words.filter { it !in fmt }
        if (tw.isEmpty()) return null
        val aw = if (cut > 0) words.drop(cut).filter { it !in fmt && it.all { c -> c.isLetter() || c == '.' } } else emptyList()
        val small = setOf("of", "the", "a", "an", "and", "in", "on", "to", "for", "at", "or")
        fun cap(ws: List<String>) = ws.mapIndexed { i, w -> if (i > 0 && w in small) w else w.replaceFirstChar { it.uppercase() } }.joinToString(" ")
        return cap(tw) to aw.takeIf { it.size in 1..4 }?.let { cap(it) }
    }

    private val ASIN_IN = Regex("/(?:dp|gp/product|gp/aw/d|d|o/ASIN)/([A-Z0-9]{10})(?:[/?#]|$)")

    /** The ASIN an Amazon address leads to: read from it, or from each redirect a short link makes. */
    private fun amazonAsin(link: String): String? {
        ASIN_IN.find(link)?.groupValues?.get(1)?.let { return it }
        var url = link.trim()
        repeat(6) {
            val r = runCatching {
                Http.request("GET", url, mapOf("Accept" to "text/html", "Accept-Language" to "en-IN,en;q=0.9"), followRedirects = false, timeoutMs = 15_000)
            }.getOrNull() ?: return null
            val loc = r.headers.entries.firstOrNull { it.key.equals("Location", true) }?.value?.firstOrNull()
                ?.let { runCatching { java.net.URL(java.net.URL(url), it).toString() }.getOrNull() }
            if (loc == null) {
                // No more redirects: the page itself names its product.
                return ASIN_IN.find(r.url.ifBlank { url })?.groupValues?.get(1)
                    ?: Regex("""name="ASIN"[^>]*value="([A-Z0-9]{10})"""").find(r.body)?.groupValues?.get(1)
                    ?: Regex(""""asin"\s*:\s*"([A-Z0-9]{10})"""").find(r.body)?.groupValues?.get(1)
                    ?: Regex("""<link rel="canonical" href="[^"]*/dp/([A-Z0-9]{10})""").find(r.body)?.groupValues?.get(1)
            }
            ASIN_IN.find(java.net.URLDecoder.decode(loc, "UTF-8"))?.groupValues?.get(1)?.let { return it }
            url = loc
        }
        return null
    }

    /** One book on an Amazon wish list. */
    data class Wished(val asin: String, val title: String, val author: String?, val format: String?, val price: Double?, val cover: String?)

    /**
     * The books on a public Amazon.in wish list (other things on it left out: a book is one whose
     * line says Paperback, Hardcover, Kindle and the like). Follows its pages, up to a few hundred items.
     */
    fun wishList(link: String): List<Wished> {
        val first = fetch(link)
        if (!first.url.contains("wishlist", true) && !first.url.contains("/hz/", true) && !link.contains("wishlist", true)) {
            throw PriceException("That isn't a wish list's address (open the list, then Share › Copy link)")
        }
        val out = LinkedHashMap<String, Wished>()
        var html = first.body
        var pages = 0
        while (true) {
            // Each item: its id, then its name (with the product link), its byline, its price.
            val ids = Regex("""id="itemName_([A-Z0-9]+)"""").findAll(html).map { it.groupValues[1] }.toList()
            if (ids.isEmpty() && pages == 0 && html.contains("signin", true) && !html.contains("itemName_")) {
                throw PriceException("That list is private: make it Public (or Shared) on Amazon, then paste its link again")
            }
            for (id in ids) {
                // The name is the link's title (attributes in either order), else its text.
                val name = Regex("""<a[^>]*id="itemName_$id"[^>]*>""").find(html)?.value
                val titleAttr = name?.let { Regex("""title="([^"]*)"""").find(it)?.groupValues?.get(1) }
                val title = titleAttr?.takeIf { it.isNotBlank() }?.let(::unescape)
                    ?: Regex("""(?s)id="itemName_$id"[^>]*>([^<]+)<""").find(html)?.groupValues?.get(1)?.let(::unescape) ?: continue
                val href = name?.let { Regex("""href="([^"]+)"""").find(it)?.groupValues?.get(1) }
                val asin = href?.let { Regex("/dp/([A-Z0-9]{10})").find(it)?.groupValues?.get(1) } ?: continue
                val byline = Regex("""(?s)id="item-byline-$id"[^>]*>(.*?)</span>""").find(html)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), " ")?.let(::unescape)?.replace(Regex("\\s+"), " ")
                val format = byline?.let { Regex("""\(([^)]*)\)\s*$""").find(it)?.groupValues?.get(1) }
                val bookish = format?.let { Regex("(?i)paperback|hardcover|hardback|kindle|mass market|board book|audible|audiobook|library binding|flexibound").containsMatchIn(it) } == true
                if (!bookish) continue
                val author = byline?.removePrefix("by ")?.substringBefore(" (")?.trim()?.takeIf { it.isNotBlank() }
                val price = Regex("""(?s)id="itemPrice_$id".{0,300}?a-offscreen">\s*₹\s*([\d,]+(?:\.\d+)?)""").find(html)?.groupValues?.get(1)?.let(::money)
                    ?: Regex("""data-price="([\d.]+)"[^>]*data-itemid="$id"""").find(html)?.groupValues?.get(1)?.let(::money)
                val cover = Regex("""(?s)id="itemImage_$id".{0,400}?src="(https[^"]+)"""").find(html)?.groupValues?.get(1)
                out[asin] = Wished(asin, title, author, format, price, cover)
            }
            // More of the list, a page at a time.
            val more = Regex("""name="showMoreUrl"[^>]*value="([^"]+)"""").find(html)?.groupValues?.get(1)
                ?: Regex(""""showMoreUrl"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            if (more.isNullOrBlank() || ++pages >= 15) break
            html = runCatching { get(if (more.startsWith("/")) "https://www.amazon.in" + unescape(more) else unescape(more)) }.getOrNull() ?: break
            Thread.sleep(500)
        }
        return out.values.toList()
    }

    /** What tracking a pasted page did: what to say, whether it worked, and the tracked book's key. */
    data class Tracked(val message: String, val ok: Boolean, val document: String? = null, val title: String? = null)

    /**
     * A book from a pasted shop page: read, matched to your library when it's there (else tracked on
     * its own), followed at that page, looked for at Amazon.in, Bookswagon and Atlantic Books, with
     * [target] as the price you'd buy at. Blocking.
     */
    fun track(store: Store, url: String, target: Double?): Tracked = runCatching {
        val f = identify(url.trim())
        val s0 = store.snapshot
        val inLibrary = s0.books.values.firstOrNull { b ->
            (f.isbn != null && b.isbn?.filter { it.isDigit() } == f.isbn) || io.github.prithirajn.trackersync.sync.Matcher.sameBook(f.title, f.author, b.title ?: "", b.author)
        }
        val key = inLibrary?.document
            ?: s0.priceWatch.values.firstOrNull { w -> (f.isbn != null && w.isbn == f.isbn) || (w.title != null && io.github.prithirajn.trackersync.sync.Matcher.sameBook(f.title, f.author, w.title, w.author)) }?.document
            ?: ("price:" + java.util.UUID.randomUUID().toString())
        val already = key in s0.priceWatch
        val now = System.currentTimeMillis() / 1000
        store.update { s ->
            val old = s.priceWatch[key]
            val w = (old ?: PriceWatch(key, since = now)).copy(
                title = old?.title ?: inLibrary?.displayTitle ?: f.title, author = old?.author ?: inLibrary?.author ?: f.author,
                isbn = old?.isbn ?: inLibrary?.isbn ?: f.isbn, cover = old?.cover ?: inLibrary?.coverUrl ?: f.cover,
                links = (old?.links ?: emptyMap()) + (f.store to f.url), pinned = (old?.pinned ?: emptySet()) + f.store,
                history = ((old?.history ?: emptyList()).filter { it.store != f.store } + f.points).takeLast(400),
                target = target ?: old?.target, told = null,
            )
            s.copy(priceWatch = s.priceWatch + (key to w))
        }
        fillInWatch(store, key)
        // The other stores, now.
        refresh(store, force = key)
        val title = inLibrary?.displayTitle ?: f.title
        val msg = when {
            already -> "Already tracking “$title”: now followed at ${shop(f.store)?.name ?: f.store} too"
            inLibrary != null -> "Tracking “$title” (from your library)"
            else -> "Tracking “$title”"
        }
        Tracked(msg, true, key, title)
    }.getOrElse { Tracked(it.message ?: "Couldn't read that page", false) }

    // ---- keeping watch ----

    /**
     * Looks at a few watched books' prices (the longest since looked at first, each at most every
     * [EVERY_SEC]), keeping what's found. Blocking; spread over syncs so the stores aren't asked much.
     */
    fun refresh(store: Store, force: String? = null, perRun: Int = 5) {
        val now = System.currentTimeMillis() / 1000
        val s0 = store.snapshot
        val due = if (force != null) listOfNotNull(s0.priceWatch[force])
            else s0.priceWatch.values.filter { now - it.checkedAt >= EVERY_SEC }.sortedBy { it.checkedAt }.take(perRun)
        for (w0 in due) {
            fillInWatch(store, w0.document)
            val w = store.snapshot.priceWatch[w0.document] ?: continue
            val book = w.book(store.snapshot) ?: continue
            val (points, problems) = runCatching { check(book, w, now) }.getOrElse { emptyList<PricePoint>() to listOf(it.message ?: "couldn't check") }
            val found2 = lastListings().filterValues { it.isNotEmpty() }
            // A print price under 40% of every other store's is a misread (another product's price,
            // an EMI…), not a deal: kept without a price, so it neither leads nor sets off an alert.
            val others = points.filter { it.price != null && it.inStock && it.format != "Kindle" }
            val points2 = points.map { p ->
                val rest = others.filter { it.store != p.store }.mapNotNull { it.price }
                if (p.price != null && p.format != "Kindle" && rest.isNotEmpty() && p.price < rest.min() * 0.4) p.copy(price = null, inStock = false) else p
            }
            store.update { s ->
                val cur = s.priceWatch[w.document] ?: return@update s
                // Pages the stores were found at are kept, so next time goes straight there; a kept page
                // that turned out to be another book is let go, with the prices read from it.
                val wrong = points.filter { it.url == "drop" }.map { it.store }.toSet()
                val found = points.filter { it.url != null && it.url != "drop" && it.price != null && it.format != "Kindle" }
                val links = (cur.links - wrong) + found.filter { it.store !in cur.pinned }.associate { it.store to it.url!! }
                // Earlier misreads (a price under 40% of every other store's at the same check) are let go too.
                val byCheck = cur.history.groupBy { it.at }
                val keep = cur.history.filter { h ->
                    val rest = byCheck[h.at].orEmpty().filter { it.store != h.store && it.price != null && it.inStock && it.format != "Kindle" }.mapNotNull { it.price }
                    h.price == null || h.format == "Kindle" || rest.isEmpty() || h.price >= rest.min() * 0.4
                }.filter { h ->
                    h.store !in wrong && (h.store in cur.pinned || h.title == null || (if (h.store == "bookswagon") loose { matches(book, h.title, h.url?.let { u -> Regex("/(\\d{13})").find(u)?.groupValues?.get(1) }) } else matches(book, h.title, h.url?.let { u -> Regex("/(\\d{13})").find(u)?.groupValues?.get(1) })))
                }
                s.copy(priceWatch = s.priceWatch + (w.document to cur.copy(
                    history = (keep + points2.map { if (it.url == "drop") it.copy(url = null) else it }).takeLast(400),
                    checkedAt = now, links = links, choices = cur.choices + found2,
                    error = problems.takeIf { it.isNotEmpty() && points.none { p -> p.price != null } }?.joinToString("; "),
                )))
            }
        }
    }

    /** [fillIn] for one tracked book that isn't in the library (a library book has its own details). */
    private fun fillInWatch(store: Store, key: String) {
        val w = store.snapshot.priceWatch[key] ?: return
        if (key in store.snapshot.books) return
        val filled = runCatching { fillIn(w) }.getOrNull() ?: return
        if (filled != w) store.update { s -> s.priceWatch[key]?.let { cur -> s.copy(priceWatch = s.priceWatch + (key to cur.copy(title = filled.title, author = filled.author, cover = filled.cover))) } ?: s }
    }

    /** At most this often per book on its own (twice a day). */
    const val EVERY_SEC = 12 * 3600L

    /**
     * Drops worth saying: under your price, or 10% or more under the lowest seen before, and not
     * said already at this price or lower. Marks them said.
     */
    fun newDrops(store: Store): List<Pair<Book, PricePoint>> {
        val out = ArrayList<Pair<Book, PricePoint>>()
        store.update { s ->
            var watch = s.priceWatch
            for ((doc, w) in s.priceWatch) {
                val best = w.best ?: continue
                val p = best.price ?: continue
                val earlier = w.history.filter { it.at < best.at && it.inStock }.mapNotNull { it.price }.minOrNull()
                val under = w.target?.let { p <= it } == true
                val fell = earlier != null && p <= earlier * 0.9
                if ((under || fell) && (w.told == null || p < w.told)) {
                    w.book(s)?.let { out += it to best }
                    watch = watch + (doc to w.copy(told = p))
                }
            }
            s.copy(priceWatch = watch)
        }
        return out
    }
}
