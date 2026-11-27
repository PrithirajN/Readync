package io.github.prithirajn.readync.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.prithirajn.readync.R
import io.github.prithirajn.readync.data.Release
import io.github.prithirajn.readync.data.ReleaseSettings
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.source.Opds
import io.github.prithirajn.readync.source.UpcomingSff
import io.github.prithirajn.readync.sync.Matcher
import io.github.prithirajn.readync.sync.ReleaseContext
import io.github.prithirajn.readync.sync.Releases
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.CpShapes
import io.github.prithirajn.readync.ui.theme.CpType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Discover › Releases, one page: the waiting room (the next book you're waiting for, or one you
 * picked, given the top of the page: days to go, the series as a strip, how long since the one
 * before, Goodreads and the shops, and everything you can do with it), your releases stood face-out
 * on two ledges (out now, coming), then Coming in SFF's weeks. Chips narrow it to yours or to SFF.
 */
@Composable
fun ReleasesCard(vm: AppViewModel, state: Snapshot, sortedOut: List<Release>? = null, sortedComing: List<Release>? = null) {
    val c = Cp.colors
    val context = LocalContext.current.applicationContext
    val set = state.releaseSettings
    var settings by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis() / 1000
    // Yours: your series, authors you've read and chapters, kept apart from Coming in SFF's weeks
    // (below them on the page), so the month's new books don't bury the next book you're waiting for.
    val shown = remember(state.releases) { state.releases.values.filter { !it.skipped && it.kind != "sff" } }
    val out = sortedOut?.filter { !it.skipped && it.kind != "sff" } ?: remember(shown) { shown.filter { it.isOut(now) }.sortedByDescending { it.date ?: it.seenAt } }
    val coming = sortedComing?.filter { !it.skipped && it.kind != "sff" } ?: remember(shown) { shown.filter { !it.isOut(now) }.sortedBy { it.date ?: Long.MAX_VALUE } }
    val sffAll = remember(state.releases) { state.releases.values.filter { !it.skipped && it.kind == "sff" }.sortedBy { it.date ?: Long.MAX_VALUE } }
    val sff = remember(sffAll, set.sffGenres) { sffAll.filter { (it.genre ?: UpcomingSff.NEWS) in set.sffGenres } }
    // What was found since you last looked is "new" (nothing is, the first time).
    val lookedAt = remember { vm.releasesLooked() }
    fun isNew(r: Release) = !r.seen && ((lookedAt != null && r.seenAt > lookedAt) || r.movedFrom != null)
    val newCount = (out + coming).count(::isNew)
    val view = androidx.compose.ui.platform.LocalView.current
    var filter by rememberSaveable { mutableStateOf("all") }
    // The release in the waiting room: one you tapped on a ledge, else the next you're waiting for.
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    // Your book servers, for "Find on my servers".
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { Opds.load(context) } } }
    val servers by Opds.servers.collectAsState()
    // Authors you've read, to mark their books under Coming in SFF.
    val readAuthors = remember(state.books) { Releases.readAuthors(state).map { Matcher.baseTitle(it) }.toSet() }

    Column(PagePadding) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionTitle("New & upcoming")
                Quiet(
                    when {
                        !set.enabled -> "Off. Turn it on to hear when the next book in your series is out."
                        newCount > 0 -> "$newCount new since you last looked."
                        else -> "The next in your series, new books by authors you've read, and what's coming in SFF."
                    },
                    color = if (newCount > 0 && set.enabled) c.accentText else c.faint,
                )
            }
            if (newCount > 0) { PillButton("Got them all", { vm.releaseSeen(null) }); HSpace(8) }
            PillButton("Settings", { settings = true })
        }
        if (!set.enabled) return@Column
        // One page: yours on top, the SFF weeks under them; the chips narrow it to either.
        if (set.sff) {
            VSpace(12)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((id, label) in listOf("all" to "All", "yours" to "Yours · ${out.size + coming.size}", "sff" to "Coming soon · ${sff.size}")) {
                    val on = filter == id
                    Text(
                        label, style = CpType.label, color = if (on) c.onAccent else c.textMid, maxLines = 1,
                        modifier = Modifier.clip(CpShapes.pill).background(if (on) c.accent else c.divider)
                            .bouncyClickable(CpShapes.pill, pressedScale = 0.94f, role = androidx.compose.ui.semantics.Role.Tab, onClickLabel = label) {
                                if (!on) { Haptics.detent(view); filter = id }
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
        val showYours = !set.sff || filter != "sff"
        val showSff = set.sff && filter != "yours"
        if (showYours) {
            if (out.isEmpty() && coming.isEmpty()) {
                VSpace(10)
                CpCard { Quiet("Nothing new yet. Series and authors you read are looked at during syncs.", Modifier.padding(16.dp), color = c.faint) }
            } else {
                val outRows = remember(out) { entries(out) }
                val comingRows = remember(coming) { entries(coming) }
                val all = outRows + comingRows
                val default = comingRows.firstOrNull { it.main.date != null } ?: outRows.firstOrNull() ?: comingRows.firstOrNull()
                val hero = all.firstOrNull { it.main.key == picked } ?: default
                hero?.let { e ->
                    WaitingRoom(vm, state, e.main, if (e.main.kind == "chapter") e.more.size + 1 else 0, now, servers.isNotEmpty(), isNew(e.main), picked == e.main.key)
                }
                val pick: (Release) -> Unit = { r ->
                    picked = if (picked == r.key) null else r.key
                    if (!r.seen) vm.releaseSeen(r.key)
                }
                if (outRows.isNotEmpty()) {
                    Stamp("Out now · ${outRows.size}", c.inks[0].text, Modifier.padding(top = 12.dp))
                    VSpace(2)
                    SplitLedges(outRows.map { it.main }, picked, emptySet(), ::isNew, YOURS_SCALE, pick)
                }
                if (comingRows.isNotEmpty()) {
                    Stamp("Coming · ${comingRows.size}", c.inks[2].text, Modifier.padding(top = 12.dp))
                    VSpace(2)
                    SplitLedges(comingRows.map { it.main }, picked, emptySet(), ::isNew, YOURS_SCALE, pick)
                }
            }
        }
        if (showSff) {
            if (showYours) {
                VSpace(22)
                SectionTitle("Coming soon")
                Quiet("${sff.size} new books, LitRPG, manga and comics, by the week.", color = c.faint)
            }
            SffShelves(vm, state, sff, sffAll.size, now, readAuthors, servers.isNotEmpty()) { isNew(it) }
        }
    }
    if (settings) ReleaseSettingsSheet(vm, state) { settings = false }
}

/**
 * One row of the page: a release, with the other new chapters of the same manga folded into it
 * ("3 new chapters, 120–122"), so a busy series is one row, not three.
 */
private data class Entry(val main: Release, val more: List<Release> = emptyList())

private fun entries(list: List<Release>): List<Entry> {
    val out = ArrayList<Entry>()
    val chapters = list.filter { it.kind == "chapter" }.groupBy { Matcher.baseTitle(it.series) }
    val placed = HashSet<String>()
    for (r in list) {
        if (r.kind != "chapter") { out += Entry(r); continue }
        val base = Matcher.baseTitle(r.series)
        if (!placed.add(base)) continue
        val all = chapters[base].orEmpty().sortedByDescending { chapterNumber(it) ?: 0.0 }
        out += Entry(all.first(), all.drop(1))
    }
    return out
}

private fun chapterNumber(r: Release) = r.number?.substringAfter("Chapter ")?.toDoubleOrNull()

internal fun localDay(sec: Long): LocalDate = Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault()).toLocalDate()

internal fun day(sec: Long?) = sec?.let { DateTimeFormatter.ofPattern("d MMM yyyy").format(localDay(it)) }
internal fun shortDay(sec: Long) = DateTimeFormatter.ofPattern("d MMM").format(localDay(sec))

/** A section's name, stamped in one of the spot inks. */
@Composable
internal fun Stamp(text: String, ink: Color, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = CpType.eyebrow, color = ink, modifier = modifier.padding(start = 2.dp))
}

/** What you can do with a release, your catch-up highlights, and the news that names it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReleaseMore(vm: AppViewModel, state: Snapshot, r: Release, now: Long, hasServers: Boolean, indent: androidx.compose.ui.unit.Dp = 52.dp, showBlurb: Boolean = true) {
    val c = Cp.colors
    val context = LocalContext.current
    val isBook = r.kind == "book" || r.kind == "volume" || r.kind == "author" || r.kind == "sff"
    val wanted = remember(r.key, state.books) {
        state.books.values.any { b -> !Releases.owned(b) && b.title != null && Matcher.sameBook(r.title, r.author, b.title, b.author) }
    }
    val watched = r.priceWatch != null && r.priceWatch in state.priceWatch
    val catchUp = remember(r.key, state.books, state.cpClips, state.paperClips) { ReleaseContext.catchUp(state, r, now) }
    val news = remember(r.key, state.sffItems) { ReleaseContext.news(state, r) }
    Column(Modifier.fillMaxWidth().padding(start = indent, bottom = 10.dp)) {
        if (showBlurb) r.blurb?.let { b ->
            Text(b, style = CpType.body, color = c.textMid)
            VSpace(10)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var n = 0
            fun next() = Modifier.staggerIn(n++)
            val page = pageFor(r)
            page?.let { (u, label) -> PillButton(label, { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u))) } }, next()) }
            if (isBook && !r.announcedOnly) {
                if (wanted) Chip("On your Want to Read") else PillButton("+ Want to read", { vm.wantRelease(r) }, next(), outlined = false)
                if (watched) PillButton("Price watched", { AppActions.pending.value = AppAction.Deals }, next())
                else PillButton("Watch price", { vm.watchReleasePrice(r) }, next())
                if (hasServers) PillButton("Find on my servers", {
                    CatalogsGo.query.value = r.title
                    DiscoverGo.section.value = DiscoverSection.CATALOGS
                }, next())
            }
            if (r.date != null && !r.isOut(now)) PillButton("Add to calendar", { addToCalendar(context, r) }, next())
            PillButton("Not this one", { vm.skipRelease(r.key) }, next())
            when {
                r.kind == "author" -> PillButton("Hide this author", { vm.hideReleaseSeries(r.series) }, next(), danger = true)
                r.kind == "sff" -> r.author?.let { a -> PillButton("Hide this author", { vm.hideReleaseSeries(a) }, next(), danger = true) }
                else -> PillButton("Hide series", { vm.hideReleaseSeries(r.series) }, next(), danger = true)
            }
        }
        catchUp?.let { cu ->
            VSpace(14)
            Column(
                Modifier.fillMaxWidth().clip(CpShapes.control).border(1.dp, c.divider, CpShapes.control).padding(12.dp),
            ) {
                Text(
                    if (r.isOut(now)) "Before you start: from ${cu.book.displayTitle}" else "Catch up before it's out: from ${cu.book.displayTitle}",
                    style = CpType.label, color = c.textMid,
                )
                for (h in cu.passages) {
                    VSpace(8)
                    Row {
                        Box(Modifier.padding(top = 3.dp).size(width = 3.dp, height = 18.dp).background(c.accent))
                        HSpace(10)
                        Text(
                            h.clip.text.trim().let { if (it.length > 260) it.take(257).trimEnd() + "…" else it },
                            style = CpType.reading.copy(fontStyle = FontStyle.Italic), color = c.text,
                        )
                    }
                }
                VSpace(10)
                PillButton(if (cu.total > cu.passages.size) "All ${cu.total} highlights" else "Open ${cu.book.displayTitle}", {
                    AppActions.pending.value = AppAction.OpenBook(cu.book.document)
                })
            }
        }
        if (news.isNotEmpty()) {
            VSpace(14)
            Text("In the news", style = CpType.label, color = c.textMid)
            for (n in news) {
                Row(
                    Modifier.fillMaxWidth()
                        .bouncyClickable(CpShapes.control, pressedScale = 0.98f, role = Role.Button, onClickLabel = "Read") { AppActions.pending.value = AppAction.Sff(n.id) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LucideIcon(R.drawable.lucide_globe, Modifier.size(14.dp), c.faint)
                    HSpace(8)
                    Column(Modifier.weight(1f)) {
                        Text(n.title, style = CpType.body, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Quiet(n.source, color = c.faint, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * Where a release opens, and the button's words: its Goodreads page whenever there is one (found
 * when it was filled in, else Goodreads' own page for its ISBN or Kindle id, which sends you to the
 * book, else a Goodreads search for it); a chapter or web novel at its own site.
 */
internal fun pageFor(r: Release): Pair<String, String>? {
    if (r.kind == "chapter" || r.kind == "webnovel") return r.url?.let { it to "Details" }
    r.facts?.goodreadsId?.let { return "https://www.goodreads.com/book/show/$it" to "Details" }
    r.url?.takeIf { it.contains("goodreads.com/book/show/") }?.let { return it to "Details" }
    io.github.prithirajn.readync.source.UpcomingSff.amazonId(r.url)?.let { return "https://www.goodreads.com/book/isbn/$it" to "Details" }
    val q = listOfNotNull(r.facts?.title ?: r.title, r.author?.split(",")?.first()).joinToString(" ")
    return "https://www.goodreads.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8") to "Details"
}

/** The release day into your calendar app, as an all-day event (nothing is written until you save it there). */
private fun addToCalendar(context: android.content.Context, r: Release) {
    val date = r.date ?: return
    val start = localDay(date).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
    val what = if (r.kind == "chapter" || r.kind == "webnovel") r.title else "${r.title} is out"
    val i = android.content.Intent(android.content.Intent.ACTION_INSERT)
        .setData(android.provider.CalendarContract.Events.CONTENT_URI)
        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, start + 86_400_000L)
        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_ALL_DAY, true)
        .putExtra(android.provider.CalendarContract.Events.TITLE, what)
        .putExtra(android.provider.CalendarContract.Events.DESCRIPTION, listOfNotNull(r.author?.let { "by $it" }, r.number?.let { "#$it in ${r.series}" }.takeIf { r.kind == "book" || r.kind == "volume" }, pageFor(r)?.first).joinToString("\n"))
    runCatching { context.startActivity(i) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ReleaseSettingsSheet(vm: AppViewModel, state: Snapshot, onClose: () -> Unit) {
    val c = Cp.colors
    val s = state.releaseSettings
    var checking by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    fun set(change: (ReleaseSettings) -> ReleaseSettings) = vm.setReleaseSettings(change)
    @Composable
    fun toggle(label: String, note: String?, on: Boolean, enabled: Boolean = true, flip: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = CpType.body, color = if (enabled) c.text else c.faint)
                note?.let { Quiet(it, color = c.faint) }
            }
            CpToggle(on, flip, label, enabled = enabled)
        }
    }
    @Composable
    fun <T> choices(options: List<Pair<String, T>>, picked: T, pick: (T) -> Unit) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, v) in options) {
                val on = v == picked
                Box(
                    Modifier.clip(CpShapes.pill).background(if (on) c.accentTint else c.divider)
                        .bouncyClickable(CpShapes.pill, pressedScale = 0.94f, role = Role.RadioButton, onClickLabel = label) { pick(v) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) { Text(label, style = CpType.label, color = if (on) c.accentText else c.textMid) }
            }
        }
    }
    GuardedSheet(
        dragHandle = { SheetGrip() },
        onDismissRequest = onClose,
        sheetState = rememberSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            Text("New & upcoming", style = CpType.section, color = c.text)
            VSpace(8)
            toggle("Look for new releases", "About once a day per series, during syncs.", s.enabled) { v -> set { it.copy(enabled = v) } }
            val on = s.enabled
            val hcOn = state.service("hardcover").linked
            val grOn = state.service("goodreads").linked

            VSpace(12); Text("What to look for", style = CpType.label, color = c.textMid)
            toggle("Next book in a series", "Novels: the book after the one you're on or finished", s.novels, on) { v -> set { it.copy(novels = v) } }
            toggle(
                "Authors you've read",
                when {
                    grOn -> "Their new books, in a series or not, from their Goodreads pages (each author about once a week)"
                    hcOn -> "Their new books, from Hardcover (link Goodreads for better dates)"
                    else -> "Link Goodreads under Sync to use it"
                },
                s.authors, on && (grOn || hcOn),
            ) { v -> set { it.copy(authors = v) } }
            toggle("Manga", "The next volume, and new chapters on MangaDex", s.manga, on) { v -> set { it.copy(manga = v) } }
            toggle("Comics", "The next collected volume", s.comics, on) { v -> set { it.copy(comics = v) } }
            toggle("Web novels", "New chapters of ones you've caught up on", s.webNovels, on) { v -> set { it.copy(webNovels = v) } }
            toggle("In-between books", "Novellas and side stories (Book 2.5)", s.inBetween, on) { v -> set { it.copy(inBetween = v) } }
            toggle("Coming soon", "Every new fantasy, SF, horror, YA, romantasy and crossover book from Reactor's monthly lists, upcoming LitRPG, manga and comics, and books announced in your News; shown apart from yours, never notified", s.sff, on) { v -> set { it.copy(sff = v) } }

            VSpace(12); Text("Where to look", style = CpType.label, color = c.textMid)
            toggle("Goodreads", if (grOn) "First choice: series pages and each book's expected publication day" else "Link Goodreads under Sync to use it", s.goodreads, on && grOn) { v -> set { it.copy(goodreads = v) } }
            toggle("Hardcover", if (hcOn) "Only when Goodreads has nothing for a series (its lists and days are less reliable)" else "Link Hardcover under Sync to use it", s.hardcover, on && hcOn) { v -> set { it.copy(hardcover = v) } }
            toggle("MangaDex", "New manga chapters in English", s.mangaDex, on) { v -> set { it.copy(mangaDex = v) } }

            MetronRow(vm, state)

            VSpace(12); Text("Which series", style = CpType.label, color = c.textMid)
            VSpace(6)
            choices(listOf("Ones I'm reading or have read" to "reading", "Everything in my library" to "all"), s.which) { v -> set { it.copy(which = v) } }

            VSpace(14); Text("Show what's coming up to", style = CpType.label, color = c.textMid)
            VSpace(6)
            choices(listOf("3 months" to 3, "6 months" to 6, "1 year" to 12, "2 years" to 24, "Any time" to 600), s.aheadMonths) { v -> set { it.copy(aheadMonths = v) } }

            VSpace(14); Text("Tell me", style = CpType.label, color = c.textMid)
            toggle("When it's out", "A notification on the day (or when a new chapter appears)", s.notifyOut, on) { v -> set { it.copy(notifyOut = v) } }
            toggle("Track its price when it's out", "Put on Discover › Deals on its release day, so you hear of the first good drop", s.watchPrices, on) { v -> set { it.copy(watchPrices = v) } }
            VSpace(4); Quiet("A reminder before it's out:", color = c.faint); VSpace(6)
            choices(listOf("None" to 0, "1 day" to 1, "3 days" to 3, "1 week" to 7, "1 month" to 30), s.remindDays) { v -> set { it.copy(remindDays = v) } }

            if (state.releasesHidden.isNotEmpty()) {
                VSpace(14); Text("Hidden series and authors", style = CpType.label, color = c.textMid)
                for (h in state.releasesHidden.sorted()) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(h.replaceFirstChar { it.uppercase() }, style = CpType.body, color = c.text, modifier = Modifier.weight(1f))
                        PillButton("Show", { vm.unhideReleaseSeries(h) })
                    }
                }
            }
            VSpace(16)
            BigButton(if (checking) "Looking…" else "Look now", {
                checking = true; said = null
                vm.checkReleasesNow { n -> checking = false; said = if (n == 0) "Nothing new found." else "$n new." }
            }, busy = checking, enabled = on && !checking, filled = false)
            said?.let { VSpace(6); Quiet(it, color = c.faint) }
        }
    }
}

/**
 * Metron, the community comics database: paste the API token from your metron.cloud account and
 * the series on your Metron pull list come into Yours (each issue, counted down), and new #1s from
 * every publisher into Coming soon. The token is checked before it's kept, and kept sealed.
 */
@Composable
private fun MetronRow(vm: AppViewModel, state: Snapshot) {
    val c = Cp.colors
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    val linked = state.metronToken != null
    val refused = state.extras["metron.refused"] != null
    VSpace(6)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Metron", style = CpType.body, color = c.text)
            Quiet(
                when {
                    refused -> "Metron stopped taking your token. Paste a new one."
                    linked -> "Your pull list's issues come into Yours; new #1s from every publisher into Coming soon"
                    else -> "Comics from every publisher, and your pull list, with your metron.cloud API token"
                },
                color = if (refused) c.warn else c.faint,
            )
        }
        PillButton(if (linked && !refused) "Change" else "Add token", { editing = !editing; said = null })
    }
    if (editing) {
        CpTextField(token, { token = it.trim() }, "Metron API token", placeholder = "From your metron.cloud account page", password = true, mono = true)
        VSpace(8)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(if (busy) "Checking…" else "Save", {
                if (!busy && token.isNotBlank()) {
                    busy = true
                    vm.setMetronToken(token) { ok -> busy = false; if (ok) { editing = false; token = ""; said = "Saved. Comics come in on the next sync." } else said = "Metron didn't take that token." }
                }
            }, outlined = false)
            PillButton("Get a token", { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://metron.cloud/accounts/tokens/"))) } })
            if (linked) PillButton("Remove", { vm.setMetronToken(null) { said = "Removed." }; editing = false }, danger = true)
        }
    }
    said?.let { Quiet(it, color = c.faint) }
}

/** Your own ledges are smaller than Coming soon's (a handful of known books, not a week to browse). */
private const val YOURS_SCALE = 0.62f
