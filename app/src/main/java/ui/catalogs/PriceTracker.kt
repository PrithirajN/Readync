package io.github.prithirajn.readync.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.prithirajn.readync.data.Book
import io.github.prithirajn.readync.data.PriceListing
import io.github.prithirajn.readync.data.PricePoint
import io.github.prithirajn.readync.data.PriceWatch
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.source.BookPrices
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.CpShapes
import io.github.prithirajn.readync.ui.theme.CpType
import io.github.prithirajn.readync.ui.theme.Lora

/** "₹1,299" (Indian grouping, no paise unless there are some). */
internal fun rupees(v: Double): String {
    val nf = java.text.NumberFormat.getNumberInstance(java.util.Locale("en", "IN")).apply { maximumFractionDigits = if (v % 1.0 == 0.0) 0 else 2 }
    return "₹" + nf.format(v)
}

/**
 * One tracked book, in a sheet from Deals: each store's price now (against the printed one, with
 * what it's come down by), the lowest ever seen, how the best price has moved, the price you'd buy
 * at, a store's exact page to point it to when it found the wrong edition, and into your library.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DealSheet(vm: AppViewModel, state: Snapshot, key: String, onClose: () -> Unit) {
    val c = Cp.colors
    val context = LocalContext.current
    val w = state.priceWatch[key] ?: return
    val book = w.book(state)
    val inLibrary = key in state.books
    var checking by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    PlainSheet(book?.displayTitle ?: w.title ?: "Book", onClose) {
        (book?.author ?: w.author)?.let { Quiet(it) }
        VSpace(12)
        val best = w.best
        val lowest = w.lowest
        Row(verticalAlignment = Alignment.CenterVertically) {
            BookCover(book?.coverUrl ?: w.cover, book?.displayTitle ?: "", 56.dp, 84.dp)
            HSpace(14)
            Column(Modifier.weight(1f)) {
                DeviceLabel("Best price now", color = c.quiet)
                Text(best?.price?.let { rupees(it) } ?: "–", fontFamily = Lora, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = c.text)
                best?.let { b -> Text(listOfNotNull(BookPrices.shop(b.store)?.name, b.format).joinToString(" · "), style = CpType.small, color = c.quiet) }
                val bits = listOfNotNull(
                    lowest?.let { "lowest seen ${rupees(it)}" },
                    w.drop?.takeIf { it >= 0.01 }?.let { "↓ ${Math.round(it * 100)}% since you started" },
                )
                if (bits.isNotEmpty()) Text(bits.joinToString(" · "), style = CpType.small, color = if ((w.drop ?: 0.0) >= 0.05) c.good else c.quiet)
            }
            if (checking) CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
        }
        // How the best price has moved, one point per check.
        val line = remember(w.history) {
            w.history.filter { it.price != null && it.inStock }.groupBy { it.at }.toSortedMap().map { (_, ps) -> ps.minOf { it.price!! } }.takeLast(60)
        }
        if (line.size >= 2) { VSpace(12); Sparkline(line, Modifier.fillMaxWidth().height(44.dp)) }
        VSpace(12)
        // Each store: its price (and Kindle beside print on Amazon), which edition it is, and ways to change it:
        // pick another edition it lists, or paste the page yourself, found or not.
        val shopsInOrder = (BookPrices.shops + w.links.keys.filter { it.startsWith("site:") }.mapNotNull { BookPrices.shop(it) }).sortedBy { sh ->
            w.latest.filter { it.store == sh.id && it.price != null && it.inStock }.minOfOrNull { it.price!! } ?: Double.MAX_VALUE
        }
        for (sh in shopsInOrder) {
            val pts = w.latest.filter { it.store == sh.id }.sortedBy { if (it.format == "Kindle") 1 else 0 }
            // A page just chosen or pasted, not read yet: said so, not "not found".
            val shown = pts.ifEmpty { listOf(PricePoint(sh.id, 0, null, inStock = false, url = w.links[sh.id], title = if (w.links[sh.id] != null) "checking" else null)) }
            for (p in shown) {
                val listing = w.choices[sh.id]?.firstOrNull { it.url == p.url }
                StoreRow(p, listing) { p.url?.let { u -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } }
            }
            StoreActions(vm, key, w, sh.id, found = pts.any { it.url != null }) { msg -> said = msg; checking = true; vm.checkPrice(key) { checking = false } }
        }
        if (w.checkedAt == 0L) Quiet("Looking at the stores…", color = c.faint)
        w.error?.let { VSpace(6); Quiet("Couldn't read the stores: $it", color = c.faint, maxLines = 3) }
        VSpace(14)
        TargetRow(w) { vm.setPriceTarget(key, it) }
        VSpace(12)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(if (checking) "Checking…" else "Check now", {
                if (!checking) { checking = true; said = null; vm.checkPrice(key) { e -> checking = false; said = e?.let { "Couldn't read the stores: $it" } } }
            })
            // For a book none of the stores found (or found wrongly everywhere): one page for any store.
            if (w.latest.none { it.price != null }) PillButton("Wrong book?", { linking = !linking })
            if (inLibrary) PillButton("Open in library", { onClose(); AppActions.pending.value = AppAction.OpenBook(key) })
            else PillButton("+ Want to read", { vm.addTrackedToLibrary(key) { e -> said = e ?: "Added to Want to Read" } })
            PillButton("Stop tracking", { vm.unwatchPrice(key); onClose() }, danger = true)
        }
        said?.let { VSpace(6); AnimatedQuiet(it, color = c.faint) }
        if (linking) {
            VSpace(10)
            var url by remember { mutableStateOf("") }
            Quiet("Paste the book's page from any bookshop (Amazon.in, Flipkart, Bookswagon, Atlantic Books…), and that shop follows exactly that one.")
            VSpace(6)
            CpTextField(url, { url = it }, "Store page", placeholder = "https://www.amazon.in/dp/…", keyboard = androidx.compose.ui.text.input.KeyboardType.Uri)
            VSpace(6)
            PillButton("Use this page", {
                val err = vm.setPriceLink(key, url)
                if (err == null) { linking = false; said = "Following that page now"; checking = true; vm.checkPrice(key) { checking = false } } else said = err
            }, outlined = false)
        }
        VSpace(10)
        Quiet("Checked " + (if (w.checkedAt > 0) ago(w.checkedAt * 1000) else "not yet") + ", and twice a day during syncs. Prices are read from the stores' pages and can be off now and then.", color = c.faint)
    }
}

/** "Hardcover · Deluxe edition · Tor · 608 pages". */
internal fun listingLine(l: PriceListing): String =
    listOfNotNull(l.format, l.edition, l.publisher, l.pages?.let { "$it pages" }).joinToString(" · ")

/**
 * Under a store's price: its other editions to choose from (when it lists more than one), and a
 * page to paste for it, to fill in a store that didn't find the book or to override the one it did.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun StoreActions(vm: AppViewModel, key: String, w: PriceWatch, store: String, found: Boolean, onChanged: (String) -> Unit) {
    val c = Cp.colors
    val name = BookPrices.shop(store)?.name ?: store
    val options = w.choices[store].orEmpty()
    val current = w.links[store]
    var choosing by remember(store) { mutableStateOf(false) }
    var pasting by remember(store) { mutableStateOf(false) }
    var url by remember(store) { mutableStateOf("") }
    var err by remember(store) { mutableStateOf<String?>(null) }
    androidx.compose.foundation.layout.FlowRow(
        Modifier.padding(start = 40.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (options.size > 1) PillButton(if (choosing) "Hide editions" else "${options.size} editions · choose", { choosing = !choosing; pasting = false })
        PillButton(if (pasting) "Cancel" else if (found) "Paste a different page" else "Paste its page", { pasting = !pasting; choosing = false; err = null })
    }
    if (choosing) {
        for (o in options.sortedWith(compareBy<PriceListing>({ l -> if (Regex("(?i)hard").containsMatchIn(l.format ?: "")) 0 else if (Regex("(?i)paper").containsMatchIn(l.format ?: "")) 1 else 2 }, { l -> l.price ?: Double.MAX_VALUE }))) {
            val on = o.url == current
            Row(
                Modifier.fillMaxWidth().padding(start = 40.dp, bottom = 6.dp).clip(CpShapes.control)
                    .background(if (on) c.accentTint else c.divider)
                    .bouncyClickable(CpShapes.control, pressedScale = 0.98f, onClickLabel = "Track this edition") {
                        if (!on) {
                            vm.setPriceLink(key, o.url)
                            choosing = false
                            onChanged("Tracking that edition at $name")
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(listingLine(o).ifEmpty { o.title ?: "Edition" }, style = CpType.label, color = if (on) c.accentText else c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    o.title?.let { Text(it, style = CpType.small, color = c.quiet, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                HSpace(8)
                Text(o.price?.let { rupees(it) } ?: "–", style = CpType.bodyStrong, color = c.text)
                if (on) { HSpace(6); Text("tracking", style = CpType.small, color = c.accentText) }
            }
        }
    }
    if (pasting) {
        Column(Modifier.padding(start = 40.dp, bottom = 8.dp)) {
            CpTextField(url, { url = it; err = null }, "$name page", placeholder = when (store) {
                "amazon" -> "https://www.amazon.in/dp/… or amzn.in/d/…"
                "bookswagon" -> "https://www.bookswagon.com/book/…"
                "atlantic" -> "https://atlanticbooks.com/products/…"
                else -> "https://${store.removePrefix("site:")}/…"
            }, keyboard = androidx.compose.ui.text.input.KeyboardType.Uri)
            VSpace(6)
            PillButton("Track this page", {
                val shop = BookPrices.trackable(url)
                when {
                    url.isBlank() -> err = "Paste the book's page first."
                    shop != store -> err = "That's not a $name page."
                    else -> {
                        val e = vm.setPriceLink(key, url)
                        if (e == null) { pasting = false; url = ""; onChanged("Following that page at $name") } else err = e
                    }
                }
            }, outlined = false)
            err?.let { VSpace(4); Quiet(it, color = c.errorText) }
        }
    }
}

/** One store's price: its name and format, the price against the printed one, or out of stock. Tap to open the page. */
@Composable
private fun StoreRow(p: PricePoint, listing: PriceListing? = null, onOpen: () -> Unit) {
    val c = Cp.colors
    Row(
        Modifier.fillMaxWidth().clip(CpShapes.control).bouncyClickable(CpShapes.control, pressedScale = 0.99f, onClickLabel = "Open the store") { onOpen() }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(c.divider), contentAlignment = Alignment.Center) {
            Text((BookPrices.shop(p.store)?.name ?: p.store).take(1), style = CpType.label, color = c.text)
        }
        HSpace(10)
        Column(Modifier.weight(1f)) {
            Text(BookPrices.shop(p.store)?.name ?: p.store, style = CpType.body, color = c.text, maxLines = 1)
            Text(
                when {
                    p.price == null && p.title == "checking" -> "checking this page…"
                    p.price == null && p.url == null -> "not found"
                    !p.inStock -> "out of stock"
                    p.format == "Kindle" -> "Kindle edition"
                    listing != null -> listingLine(listing).ifEmpty { "in stock" }
                    else -> listOfNotNull(p.format, BookPrices.editionOf(p.title), p.title?.take(40)).joinToString(" · ").ifEmpty { "in stock" }
                },
                style = CpType.small, color = c.quiet, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(p.price?.let { rupees(it) } ?: "–", style = CpType.bodyStrong, color = if (p.inStock) c.text else c.faint)
            p.mrp?.let { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rupees(m), style = CpType.small.copy(textDecoration = TextDecoration.LineThrough), color = c.faint)
                    p.price?.let { pr -> if (m > pr) { HSpace(4); Text("−${Math.round((m - pr) / m * 100)}%", style = CpType.small, color = c.good) } }
                }
            }
        }
    }
}

/** The price you'd buy it at: a few quick picks from the best price now, or any good drop. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun TargetRow(w: PriceWatch, onSet: (Double?) -> Unit) {
    val c = Cp.colors
    val now = w.best?.price
    Text("Tell me when it's", style = CpType.label, color = c.text)
    VSpace(6)
    val picks = listOfNotNull(
        null to "Any good drop",
        now?.let { Math.floor(it * 0.9) to "under ${rupees(Math.floor(it * 0.9))}" },
        now?.let { Math.floor(it * 0.75) to "under ${rupees(Math.floor(it * 0.75))}" },
        w.target?.takeIf { t -> now == null || (t != Math.floor(now * 0.9) && t != Math.floor(now * 0.75)) }?.let { it to "under ${rupees(it)}" },
    )
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((v, label) in picks) {
            val on = w.target == v
            Box(
                Modifier.clip(CpShapes.pill).background(if (on) c.accentTint else c.divider).border(1.dp, if (on) c.accentRing else c.divider, CpShapes.pill)
                    .bouncyClickable(CpShapes.pill, pressedScale = 0.94f, onClickLabel = label) { onSet(v) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) { Text(label, style = CpType.small, color = if (on) c.accentText else c.textMid) }
        }
    }
    // Or your own price.
    VSpace(8)
    var own by remember(w.document) { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            CpTextField(own, { v -> own = v.filter { it.isDigit() }.take(6) }, "Your price (₹)", placeholder = w.target?.let { "${Math.round(it)}" } ?: "e.g. 299", keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
        }
        HSpace(8)
        PillButton("Set", { own.toDoubleOrNull()?.takeIf { it > 0 }?.let { onSet(it); own = "" } }, outlined = false)
    }
}

/** The best price over time, a line with its lowest point marked. */
@Composable
internal fun Sparkline(values: List<Double>, modifier: Modifier) {
    val c = Cp.colors
    Canvas(modifier) {
        val lo = values.min(); val hi = values.max()
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        fun pt(i: Int) = Offset(size.width * i / (values.size - 1).coerceAtLeast(1), (size.height - 6.dp.toPx()) * (1 - ((values[i] - lo) / span).toFloat()) + 3.dp.toPx())
        val path = Path().apply { values.indices.forEach { i -> if (i == 0) moveTo(pt(i).x, pt(i).y) else lineTo(pt(i).x, pt(i).y) } }
        drawPath(path, c.accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        val iLow = values.indexOf(lo)
        drawCircle(c.good, 4.dp.toPx(), pt(iLow))
        drawCircle(c.accent, 3.dp.toPx(), pt(values.lastIndex))
    }
}

/**
 * Deals: add a book from a store link (or a whole Amazon wish list), then every tracked book, the
 * ones under your price first, then the biggest drops, each with its best price and where; tap one
 * for its stores, your price and the rest.
 */
@Composable
fun DealsList(vm: AppViewModel, state: Snapshot) {
    val c = Cp.colors
    var open by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    val rows = remember(state.priceWatch, state.books) {
        state.priceWatch.values.mapNotNull { w -> w.book(state)?.let { it to w } }
            .sortedWith(compareByDescending<Pair<Book, PriceWatch>> { (_, w) -> w.target?.let { t -> (w.best?.price ?: Double.MAX_VALUE) <= t } == true }
                .thenByDescending { (_, w) -> w.drop ?: -1.0 }.thenByDescending { (_, w) -> w.since })
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton("+ Add from a link", { adding = true }, Modifier.weight(1f))
            BigButton("Amazon wish list", { importing = true }, Modifier.weight(1f), filled = false)
        }
        // Shop pages shared to the app: read in the background, said here while they are and when done.
        val running by io.github.prithirajn.readync.sync.ShareJobs.running.collectAsState()
        val lastPrice by io.github.prithirajn.readync.sync.ShareJobs.lastPrice.collectAsState()
        val reading = running.filterKeys { it.startsWith("price:") }
        if (reading.isNotEmpty()) {
            VSpace(10)
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
                HSpace(8)
                Quiet(if (reading.size == 1) reading.values.first() + "…" else "Reading ${reading.size} shop pages…")
            }
        } else lastPrice?.let { r ->
            VSpace(10)
            Text(r.message, style = CpType.small, color = if (r.ok) c.good else c.errorText)
        }
        VSpace(14)
        if (rows.isEmpty()) {
            CpCard {
                Column(Modifier.padding(18.dp)) {
                    Text("No prices tracked yet", style = CpType.bodyStrong, color = c.text)
                    Quiet("Share or paste a book's page from any bookshop (Amazon.in, Flipkart, Bookswagon, Atlantic Books, Crossword…), import an Amazon wish list, or use Track its price in a book's ⋯ menu. Each is looked for at Amazon.in, Bookswagon and Atlantic Books too, checked twice a day, and you get a notification when one drops.")
                }
            }
        }
        for ((book, w) in rows) {
            val best = w.best
            val hit = w.target?.let { t -> (best?.price ?: Double.MAX_VALUE) <= t } == true
            Row(
                Modifier.fillMaxWidth().clip(CpShapes.control).bouncyClickable(CpShapes.control, pressedScale = 0.99f, onClickLabel = "Open ${book.displayTitle}") { open = w.document }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BookCover(book.coverUrl, book.displayTitle, 44.dp, 66.dp)
                HSpace(12)
                Column(Modifier.weight(1f)) {
                    Text(book.displayTitle, style = CpType.bodyStrong, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(best?.let { BookPrices.shop(it.store)?.name }, best?.format, w.target?.let { "your price ${rupees(it)}" }, w.lowest?.let { "lowest ${rupees(it)}" })
                            .joinToString(" · ").ifEmpty { if (w.checkedAt == 0L) "checking…" else "not found yet" },
                        style = CpType.small, color = if (hit) c.good else c.quiet, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (w.document !in state.books) Text("Not in your library", style = CpType.small, color = c.faint)
                }
                HSpace(8)
                Column(horizontalAlignment = Alignment.End) {
                    Text(best?.price?.let { rupees(it) } ?: "–", style = CpType.bodyStrong, color = if (hit || (w.drop ?: 0.0) >= 0.05) c.good else c.text)
                    w.drop?.takeIf { it >= 0.01 }?.let { Text("↓ ${Math.round(it * 100)}%", style = CpType.small, color = c.good) }
                }
                if (hit) { HSpace(6); Box(Modifier.size(8.dp).clip(CircleShape).background(c.good)) }
            }
            RowDivider()
        }
    }
    open?.let { k -> DealSheet(vm, state, k) { open = null } }
    if (adding) AddDealSheet(vm) { adding = false }
    if (importing) WishListSheet(vm) { importing = false }
}

/** A book from a store page: paste the link, optionally your price, and it's tracked at all three. */
@Composable
private fun AddDealSheet(vm: AppViewModel, onClose: () -> Unit) {
    val c = Cp.colors
    var url by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    PlainSheet("Add from a link", onClose) {
        Quiet("A book's page from any bookshop: Amazon.in, Flipkart, Bookswagon, Atlantic Books, Crossword… (or share it to Readync from the shop's app). The book is read from it, followed there, found at Amazon.in, Bookswagon and Atlantic Books too, and tracked, whether or not it's in your library.")
        VSpace(14)
        CpTextField(url, { url = it.trim() }, "Store page", placeholder = "https://www.flipkart.com/… or amazon.in/dp/…", keyboard = androidx.compose.ui.text.input.KeyboardType.Uri)
        VSpace(10)
        CpTextField(price, { v -> price = v.filter { it.isDigit() }.take(6) }, "Tell me when it's under ₹ (optional)", placeholder = "e.g. 299", keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
        said?.let { (m, ok) -> VSpace(10); Text(m, style = CpType.small, color = if (ok) c.good else c.errorText) }
        VSpace(16)
        BigButton(if (busy) "Reading the page…" else "Track it", {
            if (busy) return@BigButton
            busy = true; said = null
            vm.trackFromLink(url, price.toDoubleOrNull()) { m, ok -> busy = false; said = m to ok; if (ok) { url = ""; price = "" } }
        }, enabled = url.isNotBlank() && BookPrices.trackable(url) != null)
        if (url.isNotBlank() && BookPrices.trackable(url) == null) { VSpace(6); Quiet("That isn't a bookshop's book page.", color = c.faint) }
    }
}

/** An Amazon.in wish list's books, all tracked at once. */
@Composable
private fun WishListSheet(vm: AppViewModel, onClose: () -> Unit) {
    val c = Cp.colors
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    PlainSheet("Import an Amazon wish list", onClose) {
        Quiet("Open the list on Amazon, Share › Copy link, and paste it here. The list has to be Public or Shared. Its books (paperbacks, hardcovers, Kindle books) are tracked; anything else on it is left out.")
        VSpace(14)
        CpTextField(url, { url = it.trim() }, "Wish list link", placeholder = "https://www.amazon.in/hz/wishlist/ls/…", keyboard = androidx.compose.ui.text.input.KeyboardType.Uri)
        said?.let { (m, ok) -> VSpace(10); Text(m, style = CpType.small, color = if (ok) c.good else c.errorText) }
        VSpace(16)
        BigButton(if (busy) "Reading the list…" else "Import", {
            if (busy) return@BigButton
            busy = true; said = null
            vm.importWishList(url) { m, ok -> busy = false; said = m to ok }
        }, enabled = url.isNotBlank() && BookPrices.shopOf(url) == "amazon")
    }
}
