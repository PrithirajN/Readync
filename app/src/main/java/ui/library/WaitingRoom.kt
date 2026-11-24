package io.github.prithirajn.readync.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.prithirajn.readync.data.Release
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.sync.ReleaseContext
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.CpShapes
import io.github.prithirajn.readync.ui.theme.CpType
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The waiting room: one of your releases given the whole top of the page. By default the next one
 * you're waiting for (else the newest that's out); tap any cover on the ledges below and it takes
 * this place. Its cover turns in, the days to go are printed big, the series is drawn as a strip
 * (what you've read inked, what you'd need first dashed, this one in the accent), how long since you
 * read the one before, what Goodreads and the shops say, then everything you can do with it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WaitingRoom(
    vm: AppViewModel, state: Snapshot, r: Release, chapters: Int, now: Long, hasServers: Boolean, new: Boolean, picked: Boolean,
) {
    val c = Cp.colors
    val f = r.facts
    // Goodreads facts for a book of yours too (series, rating, pages), the first time it's shown here.
    LaunchedEffect(r.key) { if (r.kind == "book" || r.kind == "volume" || r.kind == "author") vm.enrichRelease(r.key) }
    val animate = LocalAnimationsOn.current
    val turn = remember(r.key) { Animatable(if (animate) 70f else 0f) }
    LaunchedEffect(r.key) { turn.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = 260f)) }
    val place = remember(r.key, state.books, state.facts, state.seriesRead) { ReleaseContext.place(state, r) }
    val last = remember(r.key, state.books, state.facts) { ReleaseContext.lastRead(state, r) }
    val price = r.priceWatch?.let { state.priceWatch[it] }?.best?.price
    val out = r.isOut(now)
    val days = r.date?.let { ChronoUnit.DAYS.between(LocalDate.now(), localDay(it)) }
    val ink = c.inks[(io.github.prithirajn.readync.sync.Matcher.baseTitle(r.series).hashCode()).mod(c.inks.size)]

    var more by remember(r.key) { mutableStateOf(false) }
    CpCard(Modifier.padding(top = 12.dp)) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp)) {
            Text(
                when {
                    picked -> "Picked from the shelf"
                    out -> "Out now"
                    else -> "Next you're waiting for"
                }.uppercase(),
                style = CpType.eyebrow, color = ink.text,
            )
            VSpace(8)
            Row {
                Box {
                    BookCover(
                        r.coverUrl ?: f?.cover, if (r.kind == "chapter" || r.kind == "webnovel") r.series else (f?.title ?: r.title), 72.dp, 108.dp,
                        Modifier.graphicsLayer { rotationY = turn.value; cameraDistance = 12f * density; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }.liftable(),
                    )
                    if (new) Text(
                        "NEW", style = CpType.chip, color = c.onAccent,
                        modifier = Modifier.align(Alignment.TopStart).padding(start = 0.dp).stampIn(true).clip(CpShapes.pill).background(c.accent).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
                HSpace(14)
                Column(Modifier.weight(1f)) {
                    Text(
                        if (chapters > 1) "${r.series}: $chapters new chapters" else (f?.title ?: r.title),
                        style = CpType.bodyStrong, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    val what = when (r.kind) {
                        "author" -> "New from ${r.series}"
                        "chapter", "webnovel" -> r.author ?: r.series
                        else -> listOfNotNull((f?.series ?: r.series.ifBlank { null })?.let { s -> (f?.position ?: r.number)?.let { "Book $it of $s" } ?: s }, r.author).joinToString(" · ")
                    }
                    if (what.isNotBlank()) Quiet(what, color = c.textMid, maxLines = 1)
                    VSpace(4)
                    // The days to go, big; or that it's out.
                    when {
                        days != null && days > 0 -> Row(verticalAlignment = Alignment.Bottom) {
                            RollingNumber(days.toInt(), CpType.screenSmall.copy(fontSize = 34.sp, lineHeight = 36.sp), ink.text)
                            HSpace(6)
                            Text(if (days == 1L) "day" else "days", style = CpType.label, color = c.textMid, modifier = Modifier.padding(bottom = 4.dp))
                        }
                        days == 0L -> Text("Out today", style = CpType.screenSmall, color = c.accentText)
                        out -> Text(r.date?.let { "Out since ${shortDay(it)}" } ?: "Out now", style = CpType.bodyStrong, color = c.accentText)
                        else -> Text(if (r.announcedOnly) "Announced, no day yet" else "No day yet", style = CpType.bodyStrong, color = c.textMid)
                    }
                    r.date?.takeIf { days != null && days > 0 }?.let { Quiet(day(it) ?: "", color = c.faint) }
                }
            }
            // The series as a strip: read, to read first, this one.
            val n = r.number?.toDoubleOrNull()?.let { Math.ceil(it).toInt() }
            if (place != null && n != null && n in 2..24) {
                VSpace(10)
                SeriesStrip(n, place, ink.ink)
                VSpace(4)
                Text(ReleaseContext.placeLine(place, r.kind), style = CpType.small, color = if (place.gap.isNotEmpty()) c.warn else c.textMid)
            } else place?.let {
                VSpace(10)
                Text(ReleaseContext.placeLine(it, r.kind), style = CpType.small, color = if (it.gap.isNotEmpty()) c.warn else c.textMid)
            }
            last?.let { (b, num) ->
                val at = b.finishedAt ?: b.timestamp
                val months = ChronoUnit.MONTHS.between(localDay(at), LocalDate.now())
                Quiet(
                    "Book ${num.toInt()} " + when {
                        b.finishedAt == null -> "is the furthest you've got"
                        months < 1 -> "read this month"
                        months == 1L -> "read a month ago"
                        months < 24 -> "read $months months ago"
                        else -> "read ${months / 12} years ago"
                    },
                    color = c.faint,
                )
            }
            // What Goodreads and the shops say.
            val bits = listOfNotNull(
                f?.rating?.let { "★ %.1f".format(it) + (f.ratings?.let { n -> " · ${compact(n)}" } ?: "") },
                f?.pages?.let { "$it pages" },
                f?.format,
                price?.let { "${rupees(it)} now" },
            )
            if (bits.isNotEmpty()) {
                VSpace(8)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (b in bits) Chip(b)
                }
            }
            // What you can do with it (and the catch-up passages, the news), folded away until asked for.
            VSpace(8)
            PillButton(if (more) "Less" else "What you can do", { more = !more })
            androidx.compose.animation.AnimatedVisibility(more) {
                Column { VSpace(10); ReleaseMore(vm, state, r, now, hasServers, indent = 0.dp, showBlurb = true) }
            }
        }
    }
}

/** 12,400 → "12.4k ratings". */
private fun compact(n: Int): String = when {
    n >= 1_000_000 -> "%.1fm ratings".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk ratings".format(n / 1_000.0).replace(".0k", "k")
    else -> "$n rating${if (n == 1) "" else "s"}"
}

/**
 * A series as a strip of little slips, one per book up to this one: the ones you've read inked
 * solid, the ones you'd need first outlined in dashes, the rest faint, and this one tall in its ink.
 */
@Composable
private fun SeriesStrip(count: Int, place: ReleaseContext.Place, ink: androidx.compose.ui.graphics.Color) {
    val c = Cp.colors
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 1..count) {
            val read = place.read.any { Math.floor(it).toInt() == i }
            val gap = place.gap.any { it.toInt() == i }
            val me = i == count
            Box(
                Modifier.width(if (count > 14) 10.dp else 16.dp).height(if (me) 22.dp else 14.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        when {
                            me -> ink
                            read -> c.text.copy(alpha = 0.85f)
                            gap -> androidx.compose.ui.graphics.Color.Transparent
                            else -> c.divider
                        },
                    )
                    .drawBehind {
                        if (gap) drawRoundRect(
                            c.warn, cornerRadius = CornerRadius(2.dp.toPx()),
                            style = Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
                        )
                    },
            )
        }
    }
}
