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

    fun shop(id: String) = shops.firstOrNull { it.id == id }

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
        val same = b.contains(a) || a.contains(b) || letters(title).contains(a)
        val va = Matcher.volume(book.displayTitle); val vb = Matcher.volume(title)
        return same && (va == null || vb == null || va == vb)
    }

    /** Each store's listings found while looking for the book being checked (editions to choose from). */
    private val listings = ThreadLocal<MutableMap<String, List<PriceListing>>>()

    /** The listings the last [check] on this thread found, by store. */
    fun lastListings(): Map<String, List<PriceListing>> = listings.get().orEmpty()

    private val EDITION = Regex("(?i)\\b(deluxe|special|collector'?s|limited|anniversary|signed|illustrated|exclusive|sprayed[- ]edges?|gift|premium)\\b(?:\\s+(?:hardcover\\s+)?edition)?")

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
    private fun queries(book: Book): List<String> = listOfNotNull(
        book.isbn?.filter { it.isDigit() || it == 'X' }?.takeIf { it.length == 13 || it.length == 10 },
        listOfNotNull(
            book.displayTitle.substringBefore(":").substringBefore(" (").trim().ifEmpty { book.displayTitle },
            book.author?.split(",", "&", " and ")?.firstOrNull()?.trim()?.split(" ")?.lastOrNull(),
        ).joinToString(" "),
    ).distinct()

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
        fun titleOf(html: String) = Regex("""<h1[^>]*>\s*([^<]+)</h1>""").find(html)?.groupValues?.get(1)?.trim()
        var url: String? = link
        var page0: String? = null
        var rejected = false
        if (url != null) {
            page0 = get(url)
            // A page found earlier (not one you chose) that turns out to be another book is let go.
            if (!pinned && !matches(book, titleOf(page0!!), Regex("/(\\d{13})").find(url)?.groupValues?.get(1))) { url = null; page0 = null; rejected = true }
        }
        if (url == null) {
            for (qRaw in queries(book)) {
                val q = URLEncoder.encode(qRaw, "UTF-8").replace("+", "%20")
                val page = runCatching { get("https://www.bookswagon.com/search-books/$q") }.getOrNull() ?: continue
                val hits = BW_LINK.findAll(page).map { abs(it.groupValues[1]) to it.groupValues[2] }.distinctBy { it.second }.toList()
                // The exact ISBN first, then the first few results: each that is this book kept as a listing.
                val exact = hits.firstOrNull { isbn != null && it.second == isbn }
                val pages = HashMap<String, String>()
                val found = ArrayList<PriceListing>()
                for ((u, i) in listOfNotNull(exact) + hits.filter { it != exact }.take(5)) {
                    val h = runCatching { get(u) }.getOrNull() ?: continue
                    if (matches(book, titleOf(h), i)) { pages[u] = h; found += bookswagonListing(u, h) }
                }
                val chosen = pick("bookswagon", found)
                if (chosen != null) { url = chosen.url; page0 = pages[chosen.url] }
                if (url != null) break
            }
            if (url == null || page0 == null) return listOf(PricePoint("bookswagon", now, null, inStock = false, url = if (rejected) "drop" else null))
        }
        val html: String = page0!!
        val title = titleOf(html)
        // Structured data first, then the page's own price labels.
        val ld = Regex(""""price"\s*:\s*"?([\d,.]+)"?""").find(html)?.groupValues?.get(1)
        val labelled = Regex("""(?is)(?:our\s*price|offer\s*price|lblOfferPrice|actualprice|sell(?:ing)?\s*price)[^₹]{0,200}₹\s*([\d,]+(?:\.\d+)?)""").find(html)?.groupValues?.get(1)
        val mrp = Regex("""(?is)(?:M\.?R\.?P\.?|lblMrp|mrp)[^₹]{0,120}₹\s*([\d,]+(?:\.\d+)?)""").find(html)?.groupValues?.get(1)
        val price = money(ld) ?: money(labelled)
        val out = Regex("""(?i)out\s+of\s+stock|currently\s+unavailable|not\s+available""").containsMatchIn(html) && price == null
        val binding = bookswagonListing(url!!, html).format
        return listOf(PricePoint("bookswagon", now, price, money(mrp)?.takeIf { price == null || it > price }, !out && price != null, binding, url, title))
    }

    /** A Bookswagon page as a listing: binding, publisher, pages and price. */
    private fun bookswagonListing(url: String, html: String): PriceListing {
        val title = Regex("""<h1[^>]*>\s*([^<]+)</h1>""").find(html)?.groupValues?.get(1)?.trim()
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
        val core = Regex("""(?s)id="(?:corePrice_feature_div|corePriceDisplay_desktop_feature_div|apex_desktop|booksHeaderSection|tmm-grid-swatch-PAPERBACK|price)".{0,2500}""").find(html)?.value ?: html
        val price = money(Regex("""a-offscreen">\s*₹\s*([\d,]+(?:\.\d+)?)""").find(core)?.groupValues?.get(1))
            ?: money(Regex("""id="(?:price|newBuyBoxPrice|kindle-price)"[^>]*>\s*₹\s*([\d,]+(?:\.\d+)?)""").find(html)?.groupValues?.get(1))
            ?: money(Regex(""""priceAmount"\s*:\s*([\d.]+)""").find(html)?.groupValues?.get(1))
        val mrp = money(Regex("""(?s)M\.R\.P\.?:?.{0,300}?a-offscreen">\s*₹\s*([\d,]+(?:\.\d+)?)""").find(html)?.groupValues?.get(1))
        val unavailable = Regex("""(?i)Currently unavailable|Temporarily out of stock""").containsMatchIn(html) && price == null
        val format = Regex("""(?s)id="productSubtitle"[^>]*>\s*([A-Za-z ]+)""").find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.length < 30 }
        val out = arrayListOf(PricePoint("amazon", now, price, mrp?.takeIf { price == null || it > price }, !unavailable && price != null, format ?: "Print", url, title))
        // The Kindle edition's price, from the formats offered beside it.
        Regex("""(?s)Kindle Edition.{0,600}?₹\s*([\d,]+(?:\.\d+)?)""").find(html)?.groupValues?.get(1)?.let(::money)
            ?.takeIf { format?.contains("Kindle", true) != true }
            ?.let { out += PricePoint("amazon", now, it, null, true, "Kindle", url, title) }
        return out
    }

    /** An Amazon product page as a listing: its format, publisher, pages and price. */
    private fun amazonListing(asin: String, html: String, title: String?): PriceListing {
        val format = Regex("""(?s)id="productSubtitle"[^>]*>\s*([A-Za-z ]+)""").find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.length < 30 }
            ?: Regex("""(?s)id="(?:tmm-grid-swatch-(HARDCOVER|PAPERBACK))"[^>]*class="[^"]*selected""").find(html)?.groupValues?.get(1)?.lowercase()?.replaceFirstChar { it.uppercase() }
        val price = money(Regex("""a-offscreen">\s*₹\s*([\d,]+(?:\.\d+)?)""").find(html)?.groupValues?.get(1))
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
        val shop = shopOf(link) ?: throw PriceException("That isn't an Amazon.in, Bookswagon or Atlantic Books page")
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
        for (w in due) {
            val book = w.book(store.snapshot) ?: continue
            val (points, problems) = runCatching { check(book, w, now) }.getOrElse { emptyList<PricePoint>() to listOf(it.message ?: "couldn't check") }
            val found2 = lastListings().filterValues { it.isNotEmpty() }
            store.update { s ->
                val cur = s.priceWatch[w.document] ?: return@update s
                // Pages the stores were found at are kept, so next time goes straight there; a kept page
                // that turned out to be another book is let go, with the prices read from it.
                val wrong = points.filter { it.url == "drop" }.map { it.store }.toSet()
                val found = points.filter { it.url != null && it.url != "drop" && it.price != null && it.format != "Kindle" }
                val links = (cur.links - wrong) + found.filter { it.store !in cur.pinned }.associate { it.store to it.url!! }
                val keep = cur.history.filter { h ->
                    h.store !in wrong && (h.store in cur.pinned || h.title == null || matches(book, h.title, h.url?.let { u -> Regex("/(\\d{13})").find(u)?.groupValues?.get(1) }))
                }
                s.copy(priceWatch = s.priceWatch + (w.document to cur.copy(
                    history = (keep + points.map { if (it.url == "drop") it.copy(url = null) else it }).takeLast(400),
                    checkedAt = now, links = links, choices = cur.choices + found2,
                    error = problems.takeIf { it.isNotEmpty() && points.none { p -> p.price != null } }?.joinToString("; "),
                )))
            }
        }
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
