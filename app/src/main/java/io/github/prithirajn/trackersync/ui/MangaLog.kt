package io.github.prithirajn.trackersync.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import io.github.prithirajn.trackersync.R
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.sync.MangaSources
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpType

/**
 * On a manga or comic volume's page: where you are in it, and logging the next bit without
 * leaving the library. "Log page" opens the log with this series filled in; "Done" is
 * one tap; "Volumes" sets which issues each volume of a comic holds.
 */
@Composable
fun MangaLogCard(vm: AppViewModel, state: Snapshot, book: Book, series: Triple<String, String, Boolean>) {
    val c = Cp.colors
    val (volumesKey, title, comic) = series
    val logKey = MangaSources.loggedKey(title)
    val log = state.logged[logKey]
    var logging by remember { mutableStateOf(false) }
    var volumes by remember { mutableStateOf(false) }
    var logAt by remember { mutableStateOf<Double?>(null) }
    var pagesSheet by remember { mutableStateOf(false) }
    var daysFor by remember { mutableStateOf<Double?>(null) }
    CpCard {
        Column(Modifier.padding(16.dp)) {
            Text("Your place in this volume", style = CpType.label, color = c.text)
            VSpace(4)
            Quiet(
                book.place?.takeIf { book.status != ReadStatus.FINISHED }
                    ?: if (book.status == ReadStatus.FINISHED) "Finished" else "Not logged yet",
                color = c.accentText,
            )
            val map = state.comicMaps[volumesKey]
            if (comic && map != null) {
                val v = map.volumes.firstOrNull { it.number == io.github.prithirajn.trackersync.sync.Matcher.volume(book.title ?: "") }
                v?.let { Quiet("This volume: issues #${io.github.prithirajn.trackersync.sync.ComicIssues.label(it.issues.min())}–${io.github.prithirajn.trackersync.sync.ComicIssues.label(it.issues.max())}" +
                    (state.comicCustom[volumesKey]?.let { " · set by you" } ?: if (map.exact) " · from ${io.github.prithirajn.trackersync.source.ComicCollections.sourceName(map)}" else " · estimated"), color = c.faint) }
            }
            VSpace(12)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Log page", { logging = true; logAt = null })
                // Finished the one you're part-way through.
                if (log != null && !log.finished) PillButton("Done", { vm.logNext(logKey) })
                PillButton(if (comic) "Issues in it" else "Chapters in it", { volumes = true })
            }
            // Pages in a chapter or issue, for the one you're part-way through.
            val per = state.pagesPer[volumesKey]
            val usual = if (comic) io.github.prithirajn.trackersync.sync.ComicIssues.TYPICAL_ISSUE_PAGES else io.github.prithirajn.trackersync.sync.MangaVolumes.TYPICAL_CHAPTER_PAGES
            val mdPages = !comic && state.mangaMaps[volumesKey]?.let { m -> m.source == "mangadex" && m.volumes.any { v -> v.chapters.any { it.pages != null } } } == true
            VSpace(10)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Quiet(
                    if (mdPages && per == null) "Pages per chapter: from MangaDex"
                    else "Pages per ${if (comic) "issue" else "chapter"}: ${per ?: usual}" + (if (per == null) " (usual)" else " (yours)"),
                    Modifier.weight(1f), color = c.faint,
                )
                PillButton("Change", { pagesSheet = true })
            }
            val est = !comic && state.mangaMaps[volumesKey]?.estimated == true
            if (est) { VSpace(6); Quiet("MangaDex doesn't list this series' volumes, so it's counted as ${io.github.prithirajn.trackersync.sync.MangaVolumes.TYPICAL_PER_VOLUME} chapters a volume. Set the real ones under Volumes.", color = c.faint) }
            // What's in this volume: each issue (with its cover) or chapter, and how far you are in it.
            val volNo = io.github.prithirajn.trackersync.sync.Matcher.volume(book.title ?: "")
            val numbers: List<Double> = if (comic) state.comicMaps[volumesKey]?.volumes?.firstOrNull { it.number == volNo }?.issues.orEmpty()
                else state.mangaMaps[volumesKey]?.volumes?.firstOrNull { it.number == volNo }?.chapters?.map { it.number }.orEmpty()
            if (numbers.isNotEmpty()) {
                VSpace(14)
                RowDivider()
                IssueList(vm, state, title, comic, numbers, state.seriesRead[volumesKey], log?.dates.orEmpty()) { n ->
                    // One you've read (or are in): its days. One further on: logging your place there.
                    val r = state.seriesRead[volumesKey]
                    if (r?.isRead(n) == true || (log != null && r?.current?.let { kotlin.math.abs(it - n) < 1e-6 } == true)) daysFor = n
                    else { logAt = n; logging = true }
                }
            }
        }
    }
    if (logging) {
        // The chapter or issue tapped, else the one you were on (from this volume's place line).
        val n = logAt?.let { io.github.prithirajn.trackersync.sync.ComicIssues.label(it) }
            ?: book.place?.let { Regex("""(\d+(?:\.\d+)?)""").find(it)?.groupValues?.get(1) }
        LogReadingSheet(vm, state, null, onClose = { logging = false }, prefillTitle = title, prefillComic = comic, prefillNumber = n)
    }
    if (volumes) {
        val volNo = io.github.prithirajn.trackersync.sync.Matcher.volume(book.title ?: "") ?: "1"
        VolumeRangeSheet(vm, state, volumesKey, volNo, comic) { volumes = false }
    }
    daysFor?.let { n -> IssueDaysSheet(vm, title, comic, n, log?.dates?.get(n)) { daysFor = null } }
    if (pagesSheet) PagesPerSheet(vm, state, volumesKey, comic) { pagesSheet = false }
}

/**
 * A volume's issues (with their covers) or chapters, folded away until opened: each with whether
 * you've read it, or the page you're on. Tapping one logs it.
 */
@Composable
private fun IssueList(
    vm: AppViewModel, state: Snapshot, series: String, comic: Boolean, numbers: List<Double>,
    read: io.github.prithirajn.trackersync.data.SeriesRead?, dates: Map<Double, Pair<Long?, Long?>>, onPick: (Double) -> Unit,
) {
    val c = Cp.colors
    var open by remember { mutableStateOf(false) }
    val done = numbers.count { read?.isRead(it) == true }
    Row(
        Modifier.fillMaxWidth().bouncyClickable(CpShapes.card, pressedScale = 0.99f, onClickLabel = if (open) "Hide" else "Show") { open = !open }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${if (comic) "Issues" else "Chapters"} in this volume", style = CpType.label, color = c.text)
            Quiet("$done of ${numbers.size} read", color = c.faint)
        }
        Icon(
            painterResource(R.drawable.lucide_chevron_right), contentDescription = null, tint = c.textMid,
            modifier = Modifier.size(20.dp).rotate(if (open) -90f else 90f),
        )
    }
    AnimatedVisibility(open) {
        if (comic) LaunchedEffect(numbers) { vm.loadIssueCovers(series, numbers) }
        Column {
            val base = io.github.prithirajn.trackersync.sync.Matcher.baseTitle(series)
            for (n in numbers) {
                val label = io.github.prithirajn.trackersync.sync.ComicIssues.label(n)
                val isRead = read?.isRead(n) == true
                val onPage = read?.current?.takeIf { kotlin.math.abs(it - n) < 1e-6 }?.let { read.page }
                Row(
                    Modifier.fillMaxWidth().bouncyClickable(CpShapes.card, pressedScale = 0.99f, onClickLabel = "Log ${if (comic) "issue" else "chapter"} $label") { onPick(n) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (comic) {
                        BookCover(state.issueCovers["$base|$label"]?.takeIf { it.isNotBlank() }, "$series #$label", 36.dp, 54.dp)
                        HSpace(12)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if (comic) "$series #$label" else "Chapter $label", style = CpType.body, color = if (isRead) c.textMid else c.text, maxLines = 1)
                        // The days you read it, when known.
                        dates[n]?.let { (a, b) ->
                            val f = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")
                            fun d(x: Long?) = x?.let { f.format(java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault())) }
                            listOfNotNull(d(a)?.let { "Started $it" }, d(b)?.let { "finished $it" }).joinToString(" \u00B7 ").takeIf { it.isNotBlank() }
                                ?.let { Quiet(it, color = c.faint, maxLines = 1) }
                        }
                    }
                    HSpace(8)
                    when {
                        isRead -> Text("Read", style = CpType.label, color = c.accentText)
                        onPage != null && onPage > 0 -> Text("Page $onPage", style = CpType.label, color = c.accentText)
                        else -> Text("Not read", style = CpType.small, color = c.faint)
                    }
                }
            }
            VSpace(4)
            Quiet("Tap one you've read to set its days; tap a later one to log your place there.", color = c.faint)
        }
    }
}

/** How many pages a chapter or issue of this series has, so a page part-way through counts right. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PagesPerSheet(vm: AppViewModel, state: Snapshot, key: String, comic: Boolean, onClose: () -> Unit) {
    val c = Cp.colors
    var pages by remember { mutableStateOf(state.pagesPer[key]?.toString() ?: "") }
    androidx.compose.material3.ModalBottomSheet(
        dragHandle = { SheetGrip() },
        onDismissRequest = onClose,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = io.github.prithirajn.trackersync.ui.theme.CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            Text("Pages per ${if (comic) "issue" else "chapter"}", style = CpType.section, color = c.text)
            VSpace(6)
            Quiet(if (comic) "Most single issues are 20\u201332 pages. Only the issue you're part-way through uses this; a finished one always counts in full."
                else "Only the chapter you're part-way through uses this; a finished one always counts in full. MangaDex's own count is used when it has one.", color = c.faint)
            VSpace(12)
            CpTextField(pages, { v -> pages = v.filter { it.isDigit() }.take(3) }, "Pages", placeholder = if (comic) "24" else "20")
            VSpace(14)
            BigButton("Save", { vm.setPagesPer(key, pages.toIntOrNull()); onClose() })
            if (state.pagesPer[key] != null) {
                VSpace(8)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PillButton("Back to the usual", { vm.setPagesPer(key, null); onClose() }) }
            }
        }
    }
}

/** The days you read one chapter or issue: changing them doesn't move your place. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun IssueDaysSheet(vm: AppViewModel, series: String, comic: Boolean, n: Double, saved: Pair<Long?, Long?>?, onClose: () -> Unit) {
    val c = Cp.colors
    fun day(x: Long?) = x?.let { java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
    var start by remember { mutableStateOf(day(saved?.first)) }
    var finish by remember { mutableStateOf(day(saved?.second)) }
    var picking by remember { mutableStateOf<Boolean?>(null) } // true: start
    var error by remember { mutableStateOf<String?>(null) }
    val label = io.github.prithirajn.trackersync.sync.ComicIssues.label(n)
    androidx.compose.material3.ModalBottomSheet(
        dragHandle = { SheetGrip() },
        onDismissRequest = onClose,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = io.github.prithirajn.trackersync.ui.theme.CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            Text(if (comic) "$series #$label" else "$series, chapter $label", style = CpType.section, color = c.text)
            VSpace(6)
            Quiet("When you read it. Your place in the series stays where it is.", color = c.faint)
            VSpace(14)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DateField("Started", start, Modifier.weight(1f)) { picking = true }
                DateField("Finished", finish, Modifier.weight(1f)) { picking = false }
            }
            error?.let { VSpace(8); Quiet(it, color = c.errorText) }
            VSpace(16)
            BigButton("Save", {
                val a = start; val b = finish
                if (a != null && b != null && a > b) { error = "The start day is after the finish day"; return@BigButton }
                fun sec(d: java.time.LocalDate?) = d?.atTime(12, 0)?.atZone(java.time.ZoneId.systemDefault())?.toEpochSecond()
                vm.setIssueDays(series, comic, n, sec(a), sec(b))
                onClose()
            })
        }
    }
    picking?.let { isStart ->
        DayPicker((if (isStart) start else finish) ?: java.time.LocalDate.now(),
            onPick = { d -> if (isStart) start = d else finish = d; picking = null },
            onDismiss = { picking = null })
    }
}

/** Which chapters or issues this one volume holds (each volume is its own book, so only this one). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun VolumeRangeSheet(vm: AppViewModel, state: Snapshot, key: String, volume: String, comic: Boolean, onClose: () -> Unit) {
    val c = Cp.colors
    val have = (state.comicCustom[key] ?: state.comicMaps[key])?.volumes?.firstOrNull { it.number == volume }?.issues
        ?: state.mangaMaps[key]?.volumes?.firstOrNull { it.number == volume }?.chapters?.map { it.number }
    val lab = { x: Double -> io.github.prithirajn.trackersync.sync.ComicIssues.label(x) }
    var from by remember { mutableStateOf(have?.minOrNull()?.let(lab) ?: "") }
    var to by remember { mutableStateOf(have?.maxOrNull()?.let(lab) ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    val word = if (comic) "issue" else "chapter"
    androidx.compose.material3.ModalBottomSheet(
        dragHandle = { SheetGrip() },
        onDismissRequest = onClose,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = io.github.prithirajn.trackersync.ui.theme.CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            Text("Vol. $volume", style = CpType.section, color = c.text)
            VSpace(6)
            Quiet(if (comic) "The single issues this volume collects (on its back cover or contents page: \u201CCollects #1\u20135\u201D)."
                else "The chapters this volume holds (on its contents page).", color = c.faint)
            VSpace(14)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { CpTextField(from, { v -> from = v.filter { it.isDigit() || it == '.' }; error = null }, "First $word") }
                Box(Modifier.weight(1f)) { CpTextField(to, { v -> to = v.filter { it.isDigit() || it == '.' }; error = null }, "Last $word") }
            }
            error?.let { VSpace(8); Quiet(it, color = c.errorText) }
            VSpace(16)
            BigButton("Save", {
                val a = from.toDoubleOrNull(); val b = to.toDoubleOrNull()
                if (a == null || b == null) { error = "Give the first and last $word"; return@BigButton }
                vm.setVolumeRange(key, volume, a, b) { e -> if (e == null) onClose() else error = e }
            })
        }
    }
}
