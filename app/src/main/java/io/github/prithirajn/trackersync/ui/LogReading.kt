package io.github.prithirajn.trackersync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.sync.ComicIssues
import io.github.prithirajn.trackersync.sync.MangaSources
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import io.github.prithirajn.trackersync.ui.theme.CpType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Logging where you are in a manga or comic: from Services, the Add button, or a volume's own page
 * (which fills in the series, and the chapter or issue you were on).
 *
 * The title is searched as you type (your series, then Hardcover, Seekquel and MangaDex), so the
 * series' real name is a tap away. Starting part-way through a volume (issue #5 of #1–5), the
 * issues before it in that volume are listed to mark read, each with the days you read it: the
 * volume starts when its first issue did and finishes when its last one did.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogReadingSheet(
    vm: AppViewModel, state: Snapshot, key: String?, onClose: () -> Unit,
    prefillTitle: String? = null, prefillComic: Boolean? = null, prefillNumber: String? = null,
) {
    val c = Cp.colors
    val l = (key ?: prefillTitle?.let { MangaSources.loggedKey(it) })?.let { state.logged[it] }
    var title by remember { mutableStateOf(l?.title ?: prefillTitle ?: "") }
    var comic by remember { mutableStateOf(l?.comic ?: prefillComic ?: false) }
    // The number you tapped wins over where the log is; the page only carries over for that same one.
    var number by remember { mutableStateOf(prefillNumber ?: l?.let { ComicIssues.label(it.at) } ?: "") }
    val samePlace = l != null && (prefillNumber == null || prefillNumber.toDoubleOrNull() == l.at)
    var finished by remember { mutableStateOf(if (samePlace) l!!.finished else true) }
    var page by remember { mutableStateOf(if (samePlace) l!!.page.takeIf { it > 0 }?.toString() ?: "" else "") }
    var fromStart by remember { mutableStateOf(l?.fromStart ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
    val titleFixed = l != null || prefillTitle != null

    // Suggestions as you type, a moment after you stop.
    var hits by remember { mutableStateOf<List<AppViewModel.SeriesHit>>(emptyList()) }
    var picked by remember { mutableStateOf(titleFixed) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(title, comic, picked) {
        if (picked || title.trim().length < 2) { hits = emptyList(); searching = false; return@LaunchedEffect }
        kotlinx.coroutines.delay(450)
        searching = true
        vm.seriesSuggestions(title, comic) { r -> hits = r; searching = false }
    }

    // The volume this one is in, and the issues before it there.
    val n = number.toDoubleOrNull()
    var volume by remember { mutableStateOf<Pair<String, List<Double>>?>(null) }
    LaunchedEffect(title, comic, n) {
        volume = null
        if (n == null || n <= 1 || title.isBlank()) return@LaunchedEffect
        kotlinx.coroutines.delay(500)
        vm.volumeFor(title, comic, n) { volume = it }
    }
    val base = io.github.prithirajn.trackersync.sync.Matcher.baseTitle(title)
    val seriesKey = (if (comic) state.comicMaps.entries.firstOrNull { io.github.prithirajn.trackersync.sync.Matcher.baseTitle(it.value.title) == base }?.key
        else state.mangaMaps.entries.firstOrNull { io.github.prithirajn.trackersync.sync.Matcher.baseTitle(it.value.title) == base }?.key)
    val alreadyRead = seriesKey?.let { state.seriesRead[it] }
    val earlier = if (fromStart && n != null) volume?.second.orEmpty().filter { it < n - 1e-6 && alreadyRead?.isRead(it) != true } else emptyList()

    // Days per chapter or issue: the ones you've logged before, and this one starting today.
    val days = remember { mutableStateMapOf<Double, Pair<LocalDate?, LocalDate?>>() }
    fun dayOf(sec: Long?) = sec?.let { Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).toLocalDate() }
    LaunchedEffect(l) { l?.dates?.forEach { (k, v) -> if (k !in days) days[k] = dayOf(v.first) to dayOf(v.second) } }
    var picking by remember { mutableStateOf<Pair<Double, Boolean>?>(null) } // (number, start?)
    val word = if (comic) "issue" else "chapter"
    fun lab(x: Double) = if (comic) "#${ComicIssues.label(x)}" else "Ch. ${ComicIssues.label(x)}"

    ModalBottomSheet(
        dragHandle = { SheetGrip() },
        onDismissRequest = onClose,
        sheetState = rememberSheetState(skipPartiallyExpanded = true),
        shape = CpShapes.sheet,
        containerColor = c.page,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
        ) {
            Text(l?.title ?: prefillTitle ?: "Log reading", style = CpType.section, color = c.text)
            VSpace(14)
            if (titleFixed && l == null) {
                Quiet(if (comic) "Comic · the issue you're on, and its page if you're part-way" else "Manga · the chapter you're on, and its page if you're part-way", color = c.faint)
                VSpace(12)
            } else if (!titleFixed) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((label, isComic) in listOf("Manga" to false, "Comic" to true)) {
                        val on = comic == isComic
                        Box(
                            Modifier.clip(CpShapes.pill).background(if (on) c.accentTint else c.divider)
                                .bouncyClickable(CpShapes.pill, pressedScale = 0.94f, role = Role.RadioButton, onClickLabel = label) { comic = isComic }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) { Text(label, style = CpType.label, color = if (on) c.accentText else c.textMid) }
                    }
                }
                VSpace(12)
                CpTextField(title, { title = it; picked = false; error = null }, "Title", placeholder = if (comic) "e.g. Something is Killing the Children" else "e.g. Chainsaw Man")
                if (searching && hits.isEmpty()) { VSpace(6); Quiet("Searching…", color = c.faint) }
                for (h in hits) {
                    VSpace(6)
                    Row(
                        Modifier.fillMaxWidth().clip(CpShapes.card)
                            .bouncyClickable(CpShapes.card, pressedScale = 0.98f, onClickLabel = "Use ${h.name}") {
                                title = h.name; picked = true; h.comic?.let { comic = it }
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BookCover(h.coverUrl, h.name, 32.dp, 48.dp)
                        HSpace(12)
                        Column(Modifier.weight(1f)) {
                            Text(h.name, style = CpType.body, color = c.text, maxLines = 2)
                            Quiet(if (h.source == "yours") "Already here" else "From ${h.source}", color = c.faint, maxLines = 1)
                        }
                    }
                }
                VSpace(12)
            }
            CpTextField(number, { v -> number = v.filter { it.isDigit() || it == '.' }; error = null }, if (comic) "Issue number" else "Chapter number")
            VSpace(12)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Finished it", style = CpType.body, color = c.text, modifier = Modifier.weight(1f))
                CpToggle(finished, { finished = it }, "Finished it")
            }
            if (!finished) {
                VSpace(8)
                CpTextField(page, { v -> page = v.filter { it.isDigit() }; error = null }, "Page you're on")
            }
            VSpace(8)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Read everything before it", style = CpType.body, color = c.text)
                    Quiet(if (comic) "Off for a single issue on its own" else "Off when you jumped in part-way", color = c.faint)
                }
                CpToggle(fromStart, { fromStart = it }, "Read everything before it")
            }

            // This one's days.
            if (n != null && n > 0) {
                VSpace(16)
                Text("${lab(n)} (this one)", style = CpType.label, color = c.text)
                VSpace(6)
                val d = days[n]
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DateField("Started", d?.first ?: (if (l?.dates?.get(n) == null) LocalDate.now() else null), Modifier.weight(1f)) { picking = n to true }
                    if (finished) DateField("Finished", d?.second ?: LocalDate.now(), Modifier.weight(1f)) { picking = n to false }
                }
            }
            // The ones before it in its volume: marked read, with their days.
            if (earlier.isNotEmpty() && volume != null) {
                VSpace(18)
                RowDivider()
                VSpace(14)
                val v = volume!!
                Text("Earlier ${word}s in Vol. ${v.first}", style = CpType.label, color = c.text)
                VSpace(4)
                Quiet(
                    "Vol. ${v.first} is ${lab(v.second.min())}–${lab(v.second.max())}. These are marked read. Set the days you read them if you know: the volume starts when its first $word did and finishes when its last one did. Wrong volume? Change it under Volumes on the volume's page.",
                    color = c.faint,
                )
                for (e in earlier) {
                    VSpace(10)
                    Text(lab(e), style = CpType.body, color = c.text)
                    VSpace(4)
                    val d = days[e]
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DateField("Started", d?.first, Modifier.weight(1f)) { picking = e to true }
                        DateField("Finished", d?.second, Modifier.weight(1f)) { picking = e to false }
                    }
                }
                val first = days[earlier.first()]
                if (earlier.size > 1 && (first?.first != null || first?.second != null)) {
                    VSpace(10)
                    PillButton("Use ${lab(earlier.first())}'s days for the rest", {
                        for (e in earlier.drop(1)) if (days[e] == null) days[e] = first!!
                    })
                }
            }

            error?.let { VSpace(8); Quiet(it, color = c.errorText) }
            VSpace(16)
            BigButton("Save", {
                val p = if (finished) 0 else (page.toIntOrNull() ?: 1).coerceAtLeast(1)
                val name = (l?.title ?: title).trim()
                when {
                    name.isBlank() -> error = "Give the title"
                    n == null || n <= 0 -> error = "Give the $word number"
                    days.any { (_, d) -> d.first != null && d.second != null && d.first!! > d.second!! } -> error = "A start day is after its finish day"
                    else -> {
                        fun sec(d: LocalDate?) = d?.atTime(12, 0)?.atZone(ZoneId.systemDefault())?.toEpochSecond()
                        val out = HashMap<Double, Pair<Long?, Long?>>()
                        for (e in earlier) days[e]?.let { out[e] = sec(it.first) to sec(it.second) }
                        // This one: the days shown (today unless changed).
                        val mine = days[n]
                        if (mine != null) out[n] = sec(mine.first ?: LocalDate.now()) to (if (finished) sec(mine.second ?: LocalDate.now()) else null)
                        vm.logReading(name, comic, n, p, fromStart, done = { e -> if (e == null) onClose() else error = e }, dates = out)
                    }
                }
            })
            val stopKey = key ?: l?.let { MangaSources.loggedKey(it.title) }
            if (stopKey != null) {
                VSpace(10)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PillButton("Stop logging this", { vm.removeLog(stopKey); onClose() }, danger = true) }
            }
        }
    }

    picking?.let { (which, isStart) ->
        val today = LocalDate.now()
        val cur = days[which]
        val initial = (if (isStart) cur?.first else cur?.second) ?: today
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    val day = picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    val was = days[which] ?: (null to null)
                    days[which] = if (isStart) day to was.second else was.first to day
                    picking = null
                }) { Text("OK", color = c.accentText) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val was = days[which] ?: (null to null)
                        days[which] = if (isStart) null to was.second else was.first to null
                        picking = null
                    }) { Text("Clear", color = c.errorText) }
                    TextButton(onClick = { picking = null }) { Text("Cancel", color = c.quiet) }
                }
            },
        ) { DatePicker(state = picker) }
    }
}

/** A day picker (no day after today), with Clear and Cancel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DayPicker(initial: LocalDate, onPick: (LocalDate?) -> Unit, onDismiss: () -> Unit) {
    val c = Cp.colors
    val today = LocalDate.now()
    val picker = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) =
                !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onPick(picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }) }) { Text("OK", color = c.accentText) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onPick(null) }) { Text("Clear", color = c.errorText) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = c.quiet) }
            }
        },
    ) { DatePicker(state = picker) }
}
