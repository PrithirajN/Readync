package io.github.prithirajn.trackersync.ui

import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.SffFeedItem
import io.github.prithirajn.trackersync.data.SffRead
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.source.SffSources
import io.github.prithirajn.trackersync.sync.SffRadar.Sector
import io.github.prithirajn.trackersync.sync.SffChart
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import io.github.prithirajn.trackersync.ui.theme.CpType
import io.github.prithirajn.trackersync.ui.theme.Lora
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.hypot

private val NIGHT = Color(0xFF0C1124)
private val GOLD = Color(0xFFF2C46B)
private val STARLIGHT = Color(0xFFDDE4FF)

/**
 * Profile: the Star Chart (sectors with their unexplored parts in fog, your books as stars, the
 * stories you read this week glowing and fading, lines from news to the books it's about, comets
 * for the next books in your series), your level, the Astral Probe, this week's expedition, your
 * constellations, the month's sky to share, what you've saved and read, and your sources.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SffProfile(
    vm: AppViewModel,
    state: Snapshot,
    chart: SffChart.Chart?,
    now: Long,
    onOpenRead: (SffRead) -> Unit,
    onOpenItem: (SffFeedItem) -> Unit,
    onSector: (Sector) -> Unit,
) {
    val c = Cp.colors
    val sky = state.sffSky
    Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
        if (chart == null) { Quiet("Charting your sky…"); return@Column }

        // Level and XP.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF2B1F4A), Color(0xFF5B8DEF)))), contentAlignment = Alignment.Center) {
                Text("${chart.level}", fontFamily = Lora, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
            }
            HSpace(12)
            Column(Modifier.weight(1f)) {
                Text(chart.className, style = CpType.section, color = c.text)
                Quiet("${chart.totalXp} XP · ${chart.nextLevelXp - chart.totalXp} to level ${chart.level + 1}" + if (sky.xpDay == SffChart.today(now)) " · ${sky.xpToday}/${SffChart.XP_DAILY_CAP} from stories today" else "")
            }
        }
        VSpace(8)
        ProgressBar(((chart.totalXp - chart.levelStartXp).toFloat() / (chart.nextLevelXp - chart.levelStartXp).coerceAtLeast(1)).coerceIn(0.03f, 1f), fillIn = true)

        VSpace(16)
        StarChart(chart)

        VSpace(18)
        ProbePanel(vm, state, chart, now)

        sky.expedition?.let { e ->
            VSpace(18)
            val sec = Sector.byId(e.sector)
            CpCard {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(sectorColor(sec)))
                        HSpace(8)
                        Text(if (e.done) "Expedition complete" else "This week's expedition", style = CpType.bodyStrong, color = c.text, modifier = Modifier.weight(1f))
                        Chip(sec.title, color = c.accentText, background = c.accentTint)
                    }
                    VSpace(4)
                    if (e.done) {
                        val named = sky.constellations.lastOrNull { it.sector == e.sector && it.at >= e.doneAt - 5 }
                        Quiet("You named ${named?.name ?: "a constellation"} in ${sec.title}. A new expedition starts on Monday.")
                    } else {
                        Quiet("Read ${e.articlesGoal} ${sec.title} stories" + (if (e.pagesGoal > 0) " and ${e.pagesGoal} pages of a ${sec.title} book" else "") + " this week to name a constellation (+${SffChart.EXPEDITION_XP} XP).")
                        VSpace(10)
                        Text("Stories ${e.articles.coerceAtMost(e.articlesGoal)}/${e.articlesGoal}", style = CpType.small, color = c.textMid)
                        VSpace(4)
                        ProgressBar((e.articles.toFloat() / e.articlesGoal).coerceIn(0.03f, 1f))
                        if (e.pagesGoal > 0) {
                            VSpace(8)
                            Text("Pages ${e.pages.coerceAtMost(e.pagesGoal)}/${e.pagesGoal}", style = CpType.small, color = c.textMid)
                            VSpace(4)
                            ProgressBar((e.pages.toFloat() / e.pagesGoal).coerceIn(0.03f, 1f))
                        }
                        VSpace(10)
                        PillButton("Read ${sec.title} stories", { onSector(sec) })
                    }
                }
            }
        }

        if (sky.constellations.isNotEmpty()) {
            VSpace(18)
            SectionTitle("Constellations")
            VSpace(8)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in sky.constellations.reversed()) {
                    val sec = Sector.byId(k.sector)
                    Row(Modifier.clip(CpShapes.pill).background(c.surface).border(1.dp, c.hairline, CpShapes.pill).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("✦", color = sectorColor(sec), style = CpType.label)
                        HSpace(6)
                        Text(k.name, style = CpType.label, color = c.text)
                    }
                }
            }
        }

        VSpace(18)
        MonthSky(state, now)

        if (sky.saved.isNotEmpty()) {
            VSpace(18)
            SectionTitle("Saved")
            VSpace(6)
            for (item in sky.saved.take(30)) {
                ItemRow(item.aiHeadline ?: item.title, item.source.substringBefore(" ·"), item.coverUrl) { onOpenItem(item) }
            }
        }

        val history = sky.history.filter { it.seconds >= 10 }
        if (history.isNotEmpty()) {
            VSpace(18)
            SectionTitle("Read recently")
            Quiet("${history.size} stories · ${history.sumOf { it.seconds } / 60} min reading")
            VSpace(6)
            for (h in history.take(20)) {
                ItemRow(h.title, "${h.source.substringBefore(" ·")} · ${ago(h.at * 1000)}", h.coverUrl) { onOpenRead(h) }
            }
        }

        VSpace(18)
        SectionTitle("Alerts")
        SettingRow("Big news about your series and authors") { CpToggle(sky.bigNews, { vm.sffAlerts(bigNews = it) }, "Big news alerts") }
        Quiet("Three a day at most, never spoilers.", color = c.faint)
        SettingRow("When the probe is back") { CpToggle(sky.probeAlerts, { vm.sffAlerts(probeAlerts = it) }, "Probe alerts") }

        VSpace(18)
        Sources(vm, state, now)

        VSpace(18)
        SocialFollowCard(vm = vm, followed = state.settings.sffSocials)
    }
}

@Composable
private fun ItemRow(title: String, sub: String, cover: String?, onTap: () -> Unit) {
    val c = Cp.colors
    Row(
        Modifier.fillMaxWidth().clip(CpShapes.control).bouncyClickable(CpShapes.control, pressedScale = 0.99f, onClickLabel = "Read") { onTap() }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!cover.isNullOrBlank()) {
            StoryImage(cover, Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)))
            HSpace(12)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = CpType.bodyStrong, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Quiet(sub, maxLines = 1)
        }
    }
}

private sealed interface Picked {
    data class BookStar(val s: SffChart.Star) : Picked
    data class News(val n: SffChart.NewsStar) : Picked
    data class CometPick(val c: SffChart.Comet) : Picked
}

/** The chart itself: a night sky, its five sectors in fog until explored, and everything on it tappable. */
@Composable
private fun StarChart(chart: SffChart.Chart) {
    val c = Cp.colors
    var picked by remember(chart) { mutableStateOf<Picked?>(null) }
    val twinkle by rememberInfiniteTransition(label = "twinkle").animateFloat(0.55f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse), label = "t")
    val stars = chart.stars + chart.uncharted
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(NIGHT)
            .pointerInput(chart) {
                detectTapGestures { tap ->
                    fun d(x: Float, y: Float) = hypot(tap.x - x * size.width, tap.y - y * size.height)
                    val all = stars.map { Picked.BookStar(it) to d(it.x, it.y) } +
                        chart.newsStars.map { Picked.News(it) to d(it.x, it.y) } +
                        chart.comets.map { Picked.CometPick(it) to d(it.x, it.y) }
                    picked = all.minByOrNull { it.second }?.takeIf { it.second < 60f }?.first
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            // Background stars, fixed so the sky doesn't jump.
            val rnd = java.util.Random(7)
            repeat(70) { drawCircle(STARLIGHT.copy(alpha = 0.10f + rnd.nextFloat() * 0.25f), radius = 0.6.dp.toPx() + rnd.nextFloat() * 1.dp.toPx(), center = Offset(rnd.nextFloat() * w, rnd.nextFloat() * h)) }
            // Sectors: a coloured glow, under a fog that thins as you explore.
            for (v in chart.sectors) {
                val (ax, ay) = SffChart.ANCHORS.getValue(v.sector)
                val center = Offset(ax * w, ay * h)
                val col = sectorColor(v.sector)
                drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.10f + 0.30f * v.strength.toFloat()), Color.Transparent), center, w * 0.22f), w * 0.22f, center)
                drawCircle(Brush.radialGradient(listOf(NIGHT.copy(alpha = 0.75f * (1f - v.explored)), Color.Transparent), center, w * 0.17f), w * 0.17f, center)
                if (v.followed) drawCircle(col.copy(alpha = 0.7f), w * 0.17f, center, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
            }
            val byId = stars.associateBy { it.document }
            for (l in chart.lines) {
                val b = byId[l.b] ?: continue
                val a = if (l.news) chart.newsStars.firstOrNull { it.id == l.a }?.let { it.x to it.y } else byId[l.a]?.let { it.x to it.y }
                a ?: continue
                drawLine(
                    if (l.news) GOLD.copy(alpha = 0.55f * twinkle) else STARLIGHT.copy(alpha = 0.18f),
                    Offset(a.first * w, a.second * h), Offset(b.x * w, b.y * h),
                    strokeWidth = if (l.news) 1.4.dp.toPx() else 1.dp.toPx(),
                    pathEffect = if (l.news) PathEffect.dashPathEffect(floatArrayOf(5f, 5f)) else null,
                )
            }
            for (s in stars) {
                val pos = Offset(s.x * w, s.y * h)
                val r = (2.2f + s.pages.coerceIn(100, 1200) / 300f).dp.toPx()
                val col = when {
                    s.sector == null -> Color(0xFF8A90A6)
                    s.status == ReadStatus.FINISHED -> GOLD
                    s.status == ReadStatus.READING || s.status == null -> sectorColor(s.sector)
                    else -> STARLIGHT.copy(alpha = 0.6f)
                }
                if (s.status == ReadStatus.READING) drawCircle(col.copy(alpha = 0.25f * twinkle), r * 2.6f, pos)
                drawCircle(col, r, pos)
                if ((picked as? Picked.BookStar)?.s?.document == s.document) drawCircle(Color.White, r * 2f, pos, style = Stroke(1.5.dp.toPx()))
            }
            for (n in chart.newsStars) {
                val pos = Offset(n.x * w, n.y * h)
                drawCircle(sectorColor(n.sector).copy(alpha = 0.35f * n.glow * twinkle), 7.dp.toPx(), pos)
                drawCircle(Color.White.copy(alpha = 0.4f + 0.6f * n.glow), 1.8.dp.toPx(), pos)
            }
            for (k in chart.comets) {
                val pos = Offset(k.x * w, k.y * h)
                val tail = Offset((k.x + (k.x - 0.5f) * 0.18f) * w, (k.y + (k.y - 0.5f) * 0.18f) * h)
                drawLine(Brush.linearGradient(listOf(Color(0xFF9FE7FF), Color.Transparent), pos, tail), pos, tail, strokeWidth = 3.dp.toPx())
                drawCircle(Color(0xFFBFF1FF), 3.5.dp.toPx(), pos)
            }
        }
        // Sector names and how much of each is explored.
        for (v in chart.sectors) {
            val (ax, ay) = SffChart.ANCHORS.getValue(v.sector)
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier.align(androidx.compose.ui.BiasAlignment(ax * 2 - 1, ((ay + if (ay < 0.5f) -0.12f else 0.13f) * 2 - 1).coerceIn(-1f, 1f))),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(v.sector.title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = sectorColor(v.sector))
                    Text("${(v.explored * 100).toInt()}% · ${v.rank}", fontSize = 9.sp, color = STARLIGHT.copy(alpha = 0.7f))
                }
            }
        }
        if (chart.uncharted.isNotEmpty()) {
            Text("Uncharted", fontSize = 9.sp, color = STARLIGHT.copy(alpha = 0.5f), modifier = Modifier.align(Alignment.Center).padding(top = 64.dp))
        }
    }
    VSpace(8)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Legend(GOLD, "Finished"); Legend(Color(0xFF5B8DEF), "Reading"); Legend(Color.White, "Stories this week"); Legend(Color(0xFFBFF1FF), "Coming")
    }
    picked?.let { p ->
        VSpace(10)
        CpCard {
            Column(Modifier.padding(14.dp)) {
                when (p) {
                    is Picked.BookStar -> {
                        Text(p.s.title, style = CpType.bodyStrong, color = c.text)
                        Quiet(listOfNotNull(p.s.author, p.s.sector?.title ?: "Uncharted: not enough known about it yet", p.s.status?.label).joinToString(" · "))
                        val links = chart.lines.filter { it.a == p.s.document || it.b == p.s.document }
                        links.firstOrNull { it.news }?.let { l -> chart.newsStars.firstOrNull { it.id == l.a }?.let { VSpace(4); Quiet("In the news: ${it.title}", color = c.accentText) } }
                        links.firstOrNull { !it.news }?.let { VSpace(4); Quiet("Linked by ${it.reason}", color = c.faint) }
                        VSpace(8)
                        PillButton("Open book", { AppActions.pending.value = AppAction.OpenBook(p.s.document) })
                    }
                    is Picked.News -> {
                        Text(p.n.title, style = CpType.bodyStrong, color = c.text)
                        Quiet("${p.n.source.substringBefore(" ·")} · read this week, fading as the week goes")
                    }
                    is Picked.CometPick -> {
                        Text(p.c.title, style = CpType.bodyStrong, color = c.text)
                        Quiet("Next in ${p.c.series} · arrives in ${p.c.days} day${if (p.c.days == 1) "" else "s"}")
                    }
                }
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        HSpace(4)
        Text(label, style = CpType.small, color = Cp.colors.quiet)
    }
}

/** The probe: charge (from reading), where to send it, the hour out, and bringing it in. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProbePanel(vm: AppViewModel, state: Snapshot, chart: SffChart.Chart, now: Long) {
    val c = Cp.colors
    val sky = state.sffSky
    val weakest = chart.sectors.filter { !it.muted }.minByOrNull { it.explored + it.strength.toFloat() }?.sector ?: Sector.VOID
    var target by rememberSaveable { mutableStateOf(weakest.id) }
    CpCard {
        Column(Modifier.padding(16.dp)) {
            Text("Astral Probe", style = CpType.bodyStrong, color = c.text)
            when {
                SffChart.returned(sky, now) -> {
                    Quiet("Back from ${Sector.byId(sky.probeSector).title}, with stories, a book and a sub-genre to reveal.")
                    VSpace(10)
                    PillButton("Bring it in (+${SffChart.PROBE_XP} XP)", { vm.claimSffProbe() }, outlined = false)
                }
                sky.probeSector != null -> {
                    val left = (sky.probeReturnsAt - now).coerceAtLeast(0)
                    Quiet("Exploring ${Sector.byId(sky.probeSector).title} · back in ${(left + 59) / 60} min")
                    VSpace(8)
                    ProgressBar((1f - left.toFloat() / SffChart.PROBE_TRAVEL_SEC).coerceIn(0.03f, 1f))
                }
                else -> {
                    Quiet(
                        if (sky.charge >= 100) "Charged. Send it somewhere you rarely go: it comes back in an hour with a briefing, and reveals a sub-genre."
                        else "Charging as you read: about 17 pages, or three stories read through. ${sky.charge}% now."
                    )
                    VSpace(8)
                    ProgressBar((sky.charge / 100f).coerceIn(0.03f, 1f))
                    VSpace(10)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (v in chart.sectors) {
                            val on = v.sector.id == target
                            Box(
                                Modifier.clip(CpShapes.pill).background(if (on) sectorColor(v.sector).copy(alpha = 0.22f) else c.divider)
                                    .bouncyClickable(CpShapes.pill, pressedScale = 0.94f, onClickLabel = v.sector.title) { target = v.sector.id }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                            ) {
                                Text("${v.sector.title} · ${v.hidden} hidden", style = CpType.small, color = if (on) c.text else c.textMid)
                            }
                        }
                    }
                    VSpace(10)
                    PillButton(
                        if (sky.charge >= 100) "Launch into ${Sector.byId(target).title}" else "Not charged yet",
                        { vm.launchSffProbe(Sector.byId(target)) }, outlined = sky.charge < 100,
                    )
                }
            }
            sky.echoQuote?.let { q ->
                VSpace(12)
                Text("“${q.take(240)}”", fontFamily = Lora, fontSize = 14.sp, lineHeight = 20.sp, color = c.text)
                sky.echoBook?.let { Quiet("— from $it, brought back by your last probe") }
            }
        }
    }
}

/** The month's sky: what you read, where, and the constellations you named; shareable as a picture. */
@Composable
private fun MonthSky(state: Snapshot, now: Long) {
    val c = Cp.colors
    val context = LocalContext.current
    var month by remember { mutableStateOf(YearMonth.from(SffChart.localDate(now))) }
    val r = remember(month, state.sffSky, state.books) { SffChart.recap(state, month) }
    val name = month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
    CpCard {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Your $name sky", style = CpType.bodyStrong, color = c.text, modifier = Modifier.weight(1f))
                PillButton("‹", { month = month.minusMonths(1) })
                HSpace(6)
                PillButton("›", { if (month < YearMonth.from(SffChart.localDate(now))) month = month.plusMonths(1) })
            }
            VSpace(6)
            Quiet(
                "${r.stories} stories (${r.minutes} min) · ${r.booksFinished.size} book${if (r.booksFinished.size == 1) "" else "s"} finished" +
                    (r.topSector?.let { " · mostly ${it.title}" } ?: "") +
                    (if (r.constellations.isNotEmpty()) " · named ${r.constellations.joinToString(", ") { it.name }}" else "")
            )
            r.topStories.firstOrNull()?.let { VSpace(4); Quiet("Longest read: ${it.title}", color = c.faint, maxLines = 1) }
            VSpace(10)
            PillButton("Share as a picture", {
                val bmp = recapPicture(r, name, month.year)
                QuoteCard.share(context, bmp, "My $name sky in ${io.github.prithirajn.trackersync.Edition.name}: ${r.stories} SFF stories, ${r.booksFinished.size} books finished" + (r.topSector?.let { ", mostly ${it.title}" } ?: ""))
            }, outlined = false)
        }
    }
}

/** The month's sky as a 1080×1350 picture: night, its sectors, the numbers, the constellations. */
private fun recapPicture(r: SffChart.Recap, monthName: String, year: Int): Bitmap {
    val W = 1080; val H = 1350
    val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    val cv = android.graphics.Canvas(bmp)
    cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, W.toFloat(), H.toFloat(), 0xFF0C1124.toInt(), 0xFF2B1F4A.toInt(), Shader.TileMode.CLAMP) })
    val rnd = java.util.Random(year * 13L + monthName.hashCode())
    val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    repeat(160) { dot.color = android.graphics.Color.argb(40 + rnd.nextInt(140), 221, 228, 255); cv.drawCircle(rnd.nextFloat() * W, rnd.nextFloat() * H, 1.5f + rnd.nextFloat() * 2.5f, dot) }
    // Each sector a glowing star, sized by how much of the month was there.
    val max = (r.bySector.values.maxOrNull() ?: 1).coerceAtLeast(1)
    for ((sec, n) in r.bySector) {
        val (ax, ay) = SffChart.ANCHORS.getValue(sec)
        val x = 140f + ax * (W - 280f); val y = 380f + ay * 560f
        val col = sectorColor(sec).toArgb()
        val rad = 18f + 46f * n / max
        dot.shader = android.graphics.RadialGradient(x, y, rad * 3, col, android.graphics.Color.TRANSPARENT, Shader.TileMode.CLAMP)
        cv.drawCircle(x, y, rad * 3, dot)
        dot.shader = null
        dot.color = col
        cv.drawCircle(x, y, rad * 0.45f, dot)
        val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; textSize = 32f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER }
        cv.drawText(sec.title, x, y + rad + 48f, lp)
    }
    val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2C46B.toInt(); textSize = 84f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
    cv.drawText("My $monthName sky", 90f, 190f, title)
    val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFDDE4FF.toInt(); textSize = 40f }
    cv.drawText("$year · speculative fiction", 92f, 252f, sub)
    val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 64f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB9C3E8.toInt(); textSize = 34f }
    val stats = listOf("${r.stories}" to "stories read", "${r.booksFinished.size}" to "books finished", "${r.minutes}" to "minutes")
    for ((i, st) in stats.withIndex()) {
        val x = 90f + i * 320f
        cv.drawText(st.first, x, 1100f, big)
        cv.drawText(st.second, x, 1148f, small)
    }
    val foot = if (r.constellations.isNotEmpty()) "Named: " + r.constellations.joinToString(" · ") { it.name } else r.topSector?.let { "Most of it in ${it.title}" } ?: ""
    cv.drawText(foot.take(52), 90f, 1240f, small)
    cv.drawText(io.github.prithirajn.trackersync.Edition.name, W - 90f - small.measureText(io.github.prithirajn.trackersync.Edition.name), 1300f, small)
    return bmp
}

/** Where the news comes from, how each source is doing, and the ones you've hidden. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Sources(vm: AppViewModel, state: Snapshot, now: Long) {
    val c = Cp.colors
    var open by remember { mutableStateOf(false) }
    val sky = state.sffSky
    val names = SffSources.newsFeeds.map { it.name } + SffSources.subreddits.map { "r/${it.first}" }
    val ok = names.count { (sky.health[it]?.okAt ?: 0L) > 0 && (sky.health[it]?.okAt ?: 0L) >= (sky.health[it]?.failAt ?: 0L) }
    CpCard {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sources", style = CpType.bodyStrong, color = c.text)
                    Quiet("$ok of ${names.size} vetted sources answering, plus your social accounts, BookTube and your own series.")
                }
                PillButton(if (open) "Hide" else "Show", { open = !open })
            }
            if (sky.blockedSources.isNotEmpty()) {
                VSpace(10)
                Text("Hidden (tap to show again)", style = CpType.small, color = c.quiet)
                VSpace(6)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (b in sky.blockedSources) {
                        Box(Modifier.bouncyClickable(CpShapes.pill, pressedScale = 0.94f, onClickLabel = "Show $b") { vm.sffBlockSource(b) }) { Chip("$b ✕", color = c.accentText, background = c.accentTint) }
                    }
                }
            }
            if (open) {
                VSpace(10)
                for (n in names) {
                    val h = sky.health[n]
                    val status = when {
                        h == null -> "not tried yet"
                        h.okAt >= h.failAt && h.okAt > 0 -> "${h.items} stories · ${ago(h.okAt * 1000)}"
                        else -> "not answering (${ago(h.failAt * 1000)})"
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(if (h != null && h.okAt >= h.failAt && h.okAt > 0) c.good else if (h == null) c.faint else c.warn))
                        HSpace(8)
                        Text(n, style = CpType.small, color = if (n in sky.blockedSources) c.faint else c.text, modifier = Modifier.weight(1f))
                        Text(status, style = CpType.small, color = c.quiet)
                    }
                }
            }
        }
    }
}
