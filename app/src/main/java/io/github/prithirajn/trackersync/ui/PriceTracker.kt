package io.github.prithirajn.trackersync.ui

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
import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.PricePoint
import io.github.prithirajn.trackersync.data.PriceWatch
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.source.BookPrices
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import io.github.prithirajn.trackersync.ui.theme.CpType
import io.github.prithirajn.trackersync.ui.theme.Lora

/** "₹1,299" (Indian grouping, no paise unless there are some). */
internal fun rupees(v: Double): String {
    val nf = java.text.NumberFormat.getNumberInstance(java.util.Locale("en", "IN")).apply { maximumFractionDigits = if (v % 1.0 == 0.0) 0 else 2 }
    return "₹" + nf.format(v)
}

/**
 * A book's prices, on its page: watch it, then each store's price now (against the printed one,
 * with what it's come down by), the lowest ever seen, how the best price has moved, the price you'd
 * buy at, and a store's exact page to point it to when it found the wrong edition.
 */
@Composable
fun PriceSection(vm: AppViewModel, state: Snapshot, book: Book) {
    val c = Cp.colors
    val context = LocalContext.current
    val w = state.priceWatch[book.document]
    var checking by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        if (w == null) {
            CpCard {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Watch its price", style = CpType.bodyStrong, color = c.text)
                        Quiet("Amazon.in, Bookswagon and Atlantic Books, checked twice a day. You hear when it drops.", maxLines = 3)
                    }
                    HSpace(10)
                    PillButton("Watch", { vm.watchPrice(book.document) }, outlined = false)
                }
            }
            return@Column
        }
        val best = w.best
        val lowest = w.lowest
        CpCard {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        DeviceLabel("Best price now", color = c.quiet)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(best?.price?.let { rupees(it) } ?: "–", fontFamily = Lora, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = c.text)
                            best?.let { b ->
                                HSpace(8)
                                Text(listOfNotNull(BookPrices.shop(b.store)?.name, b.format).joinToString(" · "), style = CpType.small, color = c.quiet, modifier = Modifier.padding(bottom = 6.dp))
                            }
                        }
                        val bits = listOfNotNull(
                            lowest?.let { "lowest seen ${rupees(it)}" },
                            w.drop?.takeIf { it >= 0.01 }?.let { "↓ ${Math.round(it * 100)}% since you started watching" },
                        )
                        if (bits.isNotEmpty()) Text(bits.joinToString(" · "), style = CpType.small, color = if ((w.drop ?: 0.0) >= 0.05) c.good else c.quiet)
                    }
                    if (checking || (w.checkedAt == 0L && w.history.isEmpty())) CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                }
                // How the best price has moved, one point per check.
                val line = remember(w.history) {
                    w.history.filter { it.price != null && it.inStock }.groupBy { it.at }.toSortedMap().map { (at, ps) -> at to ps.minOf { it.price!! } }.takeLast(60)
                }
                if (line.size >= 2) {
                    VSpace(10)
                    Sparkline(line.map { it.second }, Modifier.fillMaxWidth().height(44.dp))
                }
                VSpace(12)
                for (p in w.latest.sortedWith(compareBy({ it.price == null || !it.inStock }, { it.price ?: Double.MAX_VALUE }))) {
                    StoreRow(p) { p.url?.let { u -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } }
                }
                w.error?.let { VSpace(6); Quiet("Couldn't read the stores: $it", color = c.faint, maxLines = 3) }
                VSpace(12)
                TargetRow(w) { vm.setPriceTarget(book.document, it) }
                VSpace(10)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(if (checking) "Checking…" else "Check now", {
                        if (!checking) { checking = true; said = null; vm.checkPrice(book.document) { e -> checking = false; said = e?.let { "Couldn't read the stores: $it" } } }
                    })
                    PillButton("Wrong book?", { linking = !linking })
                    PillButton("Stop watching", { vm.unwatchPrice(book.document) })
                }
                said?.let { VSpace(6); AnimatedQuiet(it, color = c.faint) }
                if (linking) {
                    VSpace(10)
                    var url by remember { mutableStateOf("") }
                    Quiet("Paste the book's page from Amazon.in, Bookswagon or Atlantic Books, and that store follows exactly that one.")
                    VSpace(6)
                    CpTextField(url, { url = it }, "Store page", placeholder = "https://www.amazon.in/dp/…", keyboard = androidx.compose.ui.text.input.KeyboardType.Uri)
                    VSpace(6)
                    PillButton("Use this page", {
                        val err = vm.setPriceLink(book.document, url)
                        if (err == null) { linking = false; said = "Following that page now"; checking = true; vm.checkPrice(book.document) { checking = false } } else said = err
                    }, outlined = false)
                }
                VSpace(6)
                Quiet("Checked " + (if (w.checkedAt > 0) ago(w.checkedAt * 1000) else "not yet") + ". Prices are read from the stores' pages and can be off now and then.", color = c.faint)
            }
        }
    }
}

/** One store's price: its name and format, the price against the printed one, or out of stock. Tap to open the page. */
@Composable
private fun StoreRow(p: PricePoint, onOpen: () -> Unit) {
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
                    p.price == null && p.url == null -> "not found"
                    !p.inStock -> "out of stock"
                    else -> listOfNotNull(p.format, p.title?.take(40)).joinToString(" · ").ifEmpty { "in stock" }
                },
                style = CpType.small, color = c.quiet, maxLines = 1, overflow = TextOverflow.Ellipsis,
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
private fun TargetRow(w: PriceWatch, onSet: (Double?) -> Unit) {
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
}

/** The best price over time, a line with its lowest point marked. */
@Composable
private fun Sparkline(values: List<Double>, modifier: Modifier) {
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

/** On a book's card: its best price, green with how far it's come down when it has. */
@Composable
fun PriceTag(w: PriceWatch?) {
    val c = Cp.colors
    val p = w?.best?.price ?: return
    val drop = w.drop?.takeIf { it >= 0.05 }
    val hit = w.target?.let { p <= it } == true
    Row(
        Modifier.clip(CpShapes.pill).background(if (drop != null || hit) c.good.copy(alpha = 0.16f) else c.divider).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(rupees(p), style = CpType.small.copy(fontWeight = FontWeight.SemiBold), color = if (drop != null || hit) c.good else c.textMid)
        drop?.let { HSpace(4); Text("↓${Math.round(it * 100)}%", style = CpType.small, color = c.good) }
    }
}

/**
 * Deals: every watched book, the ones under your price first, then the biggest drops, each with its
 * best price and where; tap one to open it.
 */
@Composable
fun DealsList(state: Snapshot, onOpen: (String) -> Unit) {
    val c = Cp.colors
    val rows = remember(state.priceWatch, state.books) {
        state.priceWatch.values.mapNotNull { w -> state.books[w.document]?.let { it to w } }
            .sortedWith(compareByDescending<Pair<Book, PriceWatch>> { (_, w) -> w.target?.let { t -> (w.best?.price ?: Double.MAX_VALUE) <= t } == true }
                .thenByDescending { (_, w) -> w.drop ?: -1.0 })
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (rows.isEmpty()) {
            CpCard {
                Column(Modifier.padding(18.dp)) {
                    Text("No prices watched yet", style = CpType.bodyStrong, color = c.text)
                    Quiet("Open a book (Want to Read is a good place) and tap Watch under its price. Amazon.in, Bookswagon and Atlantic Books are checked twice a day, and you hear when one drops.")
                }
            }
            return@Column
        }
        for ((book, w) in rows) {
            val best = w.best
            val hit = w.target?.let { t -> (best?.price ?: Double.MAX_VALUE) <= t } == true
            Row(
                Modifier.fillMaxWidth().clip(CpShapes.control).bouncyClickable(CpShapes.control, pressedScale = 0.99f, onClickLabel = "Open ${book.displayTitle}") { onOpen(book.document) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BookCover(book.coverUrl, book.displayTitle, 44.dp, 66.dp)
                HSpace(12)
                Column(Modifier.weight(1f)) {
                    Text(book.displayTitle, style = CpType.bodyStrong, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(best?.let { BookPrices.shop(it.store)?.name }, best?.format, w.lowest?.let { "lowest ${rupees(it)}" }, if (hit) "under your price" else null).joinToString(" · ").ifEmpty { if (w.checkedAt == 0L) "checking…" else "not found yet" },
                        style = CpType.small, color = if (hit) c.good else c.quiet, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
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
}
