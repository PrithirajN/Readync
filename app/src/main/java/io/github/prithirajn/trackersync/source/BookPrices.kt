package io.github.prithirajn.trackersync.source

import io.github.prithirajn.trackersync.data.Book
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

    private fun get(url: String): String {
        val r = Http.request("GET", url, mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-IN,en;q=0.9",
        ), timeoutMs = 20_000)
        if (r.code == 503 || r.body.contains("api-services-support@amazon.com") || r.body.contains("Type the characters you see")) {
            throw PriceException("the store asked to check this isn't a robot; tried again later")
        }
        if (!r.ok) throw PriceException("HTTP ${r.code}")
        return r.body
    }

    private fun money(s: String?): Double? = s?.replace(",", "")?.replace(Regex("[^0-9.]"), "")?.trim('.')?.toDoubleOrNull()?.takeIf { it > 0 }

    private fun query(book: Book): String =
        book.isbn?.filter { it.isDigit() || it == 'X' }?.takeIf { it.length == 13 || it.length == 10 }
            ?: listOfNotNull(book.displayTitle, book.author).joinToString(" ")

    private fun matches(book: Book, title: String?): Boolean =
        title == null || book.isbn != null || Matcher.sameBook(book.displayTitle, book.author, title, null) ||
            Matcher.baseTitle(title).lowercase().contains(Matcher.baseTitle(book.displayTitle).lowercase().take(24))

    /** Every store's price for [book] now (one point per store, and Kindle beside print on Amazon). Stores that fail are left out. */
    fun check(book: Book, watch: PriceWatch, now: Long = System.currentTimeMillis() / 1000): Pair<List<PricePoint>, List<String>> {
        val out = ArrayList<PricePoint>()
        val problems = ArrayList<String>()
        for (s in shops) {
            try {
                out += when (s.id) {
                    "amazon" -> amazon(book, watch.links["amazon"], now)
                    "bookswagon" -> bookswagon(book, watch.links["bookswagon"], now)
                    else -> atlantic(book, watch.links["atlantic"], now)
                }
            } catch (e: Exception) {
                problems += "${s.name}: ${e.message ?: "couldn't read it"}"
            }
            Thread.sleep(400)
        }
        return out to problems
    }

    // ---- Atlantic Books (Shopify) ----

    private fun atlantic(book: Book, link: String?, now: Long): List<PricePoint> {
        val handle = link?.let { Regex("/products/([^/?#]+)").find(it)?.groupValues?.get(1) }
            ?: run {
                val q = URLEncoder.encode(query(book), "UTF-8")
                val o = JSONObject(get("https://atlanticbooks.com/search/suggest.json?q=$q&resources[type]=product&resources[limit]=6"))
                val products = o.optJSONObject("resources")?.optJSONObject("results")?.optJSONArray("products")
                    ?: return listOf(PricePoint("atlantic", now, null, inStock = false))
                (0 until products.length()).map { products.getJSONObject(it) }
                    .firstOrNull { matches(book, it.optString("title")) }
                    ?.optString("handle")?.takeIf { it.isNotBlank() }
                    ?: return listOf(PricePoint("atlantic", now, null, inStock = false))
            }
        val p = JSONObject(get("https://atlanticbooks.com/products/$handle.js"))
        val v = p.optJSONArray("variants")?.optJSONObject(0)
        // Shopify gives prices in paise.
        val price = (v?.optLong("price") ?: p.optLong("price")).takeIf { it > 0 }?.let { it / 100.0 }
        val mrp = (v?.optLong("compare_at_price") ?: p.optLong("compare_at_price")).takeIf { it > 0 }?.let { it / 100.0 }
        val available = v?.optBoolean("available") ?: p.optBoolean("available", true)
        return listOf(PricePoint("atlantic", now, price, mrp?.takeIf { price == null || it > price }, available, v?.optString("title")?.takeIf { it != "Default Title" }, "https://atlanticbooks.com/products/$handle", p.optString("title").ifBlank { null }))
    }

    // ---- Bookswagon ----

    private val BW_LINK = Regex("""href="((?:https://www\.bookswagon\.com)?/book/[^"]+/(\d{13}))"""")

    private fun bookswagon(book: Book, link: String?, now: Long): List<PricePoint> {
        val url = link ?: run {
            val q = URLEncoder.encode(query(book), "UTF-8").replace("+", "%20")
            val page = get("https://www.bookswagon.com/search-books/$q")
            val isbn = book.isbn?.filter { it.isDigit() }
            val hits = BW_LINK.findAll(page).map { it.groupValues[1] to it.groupValues[2] }.toList()
            val pick = hits.firstOrNull { isbn != null && it.second == isbn } ?: hits.firstOrNull()
                ?: return listOf(PricePoint("bookswagon", now, null, inStock = false))
            pick.first.let { if (it.startsWith("/")) "https://www.bookswagon.com$it" else it }
        }
        val html = get(url)
        val title = Regex("""<h1[^>]*>\s*([^<]+)</h1>""").find(html)?.groupValues?.get(1)?.trim()
        // Structured data first, then the page's own price labels.
        val ld = Regex(""""price"\s*:\s*"?([\d,.]+)"?""").find(html)?.groupValues?.get(1)
        val labelled = Regex("""(?is)(?:our\s*price|offer\s*price|lblOfferPrice|actualprice|sell(?:ing)?\s*price)[^₹]{0,200}₹\s*([\d,]+(?:\.\d+)?)""").find(html)?.groupValues?.get(1)
        val mrp = Regex("""(?is)(?:M\.?R\.?P\.?|lblMrp|mrp)[^₹]{0,120}₹\s*([\d,]+(?:\.\d+)?)""").find(html)?.groupValues?.get(1)
        val price = money(ld) ?: money(labelled)
        val out = Regex("""(?i)out\s+of\s+stock|currently\s+unavailable|not\s+available""").containsMatchIn(html) && price == null
        val binding = Regex("""(?i)\b(Paperback|Hardcover|Hardback|Board book|Mass Market)\b""").find(html)?.value
        return listOf(PricePoint("bookswagon", now, price, money(mrp)?.takeIf { price == null || it > price }, !out && price != null, binding, url, title))
    }

    // ---- Amazon.in ----

    private val ASIN = Regex("""data-asin="([A-Z0-9]{10})"""")

    private fun amazon(book: Book, link: String?, now: Long): List<PricePoint> {
        val asin = link?.let { Regex("/(?:dp|gp/product|d)/([A-Z0-9]{10})").find(it)?.groupValues?.get(1) }
            ?: run {
                val q = URLEncoder.encode(query(book), "UTF-8")
                val page = get("https://www.amazon.in/s?k=$q&i=stripbooks")
                ASIN.findAll(page).map { it.groupValues[1] }.firstOrNull { it.isNotBlank() }
                    ?: return listOf(PricePoint("amazon", now, null, inStock = false))
            }
        val url = "https://www.amazon.in/dp/$asin"
        val html = get(url)
        val title = Regex("""id="productTitle"[^>]*>\s*([^<]+)<""").find(html)?.groupValues?.get(1)?.trim()
        if (link == null && !matches(book, title)) return listOf(PricePoint("amazon", now, null, inStock = false))
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
            val book = store.snapshot.books[w.document] ?: continue
            val (points, problems) = runCatching { check(book, w, now) }.getOrElse { emptyList<PricePoint>() to listOf(it.message ?: "couldn't check") }
            store.update { s ->
                val cur = s.priceWatch[w.document] ?: return@update s
                // Pages the stores were found at are kept, so next time goes straight there.
                val links = cur.links + points.filter { it.url != null && it.price != null && it.store !in cur.links && it.format != "Kindle" }.associate { it.store to it.url!! }
                s.copy(priceWatch = s.priceWatch + (w.document to cur.copy(
                    history = (cur.history + points).takeLast(400),
                    checkedAt = now, links = links,
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
                    s.books[doc]?.let { out += it to best }
                    watch = watch + (doc to w.copy(told = p))
                }
            }
            s.copy(priceWatch = watch)
        }
        return out
    }
}
