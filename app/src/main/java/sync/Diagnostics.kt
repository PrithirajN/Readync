package io.github.prithirajn.readync.sync

import android.content.Context
import android.os.Build
import io.github.prithirajn.readync.data.Store
import io.github.prithirajn.readync.trackers.Session
import io.github.prithirajn.readync.trackers.Trackers
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A plain-text picture of how syncing stands, to share when something isn't going through: the
 * app and phone, each tracker's state, every book's link to each tracker (what's matched, last
 * sent, notes, dates sent), what's queued, the send history, the sync log, and for the trackers
 * asked about, what each one says right now for the books being read. Never any password,
 * cookie, token or key: only what you'd see on the screen.
 */
object Diagnostics {
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
    private fun t(sec: Long?) = sec?.takeIf { it > 0 }?.let { fmt.format(Instant.ofEpochSecond(if (it > 10_000_000_000L) it / 1000 else it)) } ?: "-"

    /** Blocking (reads [probe] trackers over the network, a few books each). */
    fun build(context: Context, store: Store, probe: Set<String> = setOf("storygraph")): String {
        val s = store.snapshot
        val out = StringBuilder()
        fun line(x: String = "") { out.append(x).append('\n') }
        val version = runCatching {
            val p = context.packageManager.getPackageInfo(context.packageName, 0)
            "${p.versionName} (${if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()})"
        }.getOrDefault("?")
        line("Readync diagnostics · $version · ${io.github.prithirajn.readync.Edition.name}")
        line("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        line("Made: ${t(System.currentTimeMillis() / 1000)} · last sync ${t(s.lastRun / 1000)} · running ${s.running}")
        line("CrossPoint server account: ${if (s.account != null) "yes" else "no"} · server's own connectors: ${s.serverLinked.sorted().joinToString().ifEmpty { "none" }} (checked ${t(s.serverLinkedAt / 1000)})")
        line()
        line("== Trackers")
        for ((id, v) in s.services.toSortedMap()) {
            line("$id: linked=${v.linked} enabled=${v.enabled} visibility=${v.visibility} needsReauth=${v.needsReauth} importReading=${v.importReading} highlights=${v.highlights} lastSync=${t(v.lastSync / 1000)} lastError=${v.lastError ?: "-"}")
        }
        line()
        line("== Books (${s.books.size})")
        for (b in s.books.values.sortedByDescending { it.timestamp }) {
            line("[${b.document}] ${b.title} — ${b.author ?: "?"} · ${b.status} ${Math.round(b.percentage * 1000) / 10.0}% · origin=${b.origin ?: "crosspoint"} held=${b.held} updated=${t(b.timestamp)} started=${t(b.startedAt)} finished=${t(b.finishedAt)} pages=${b.pagesRead ?: "-"}/${b.pageCount ?: "-"}")
            for (l in s.links.values.filter { it.document == b.document }.sortedBy { it.service }) {
                line("    ${l.service}: id=${l.externalId ?: "none"} src=${l.source} confirmed=${l.confirmed} last=${l.lastPercent ?: "-"}% ${l.lastStatus ?: ""} updated=${t(l.updatedAt / 1000)} dates=${l.datesSent ?: "-"} review=${l.reviewSent?.take(12) ?: "-"} note=${l.note ?: "-"}")
            }
            for (p in s.pending.values.filter { it.document == b.document }) line("    queued for ${p.service}: since ${t(p.queuedAt / 1000)} tries=${p.tries} error=${p.lastError ?: "-"}")
            for ((k, ev) in s.sent.filterKeys { it.endsWith("|${b.document}") }) {
                line("    sent to ${k.substringBefore('|')}: " + ev.takeLast(6).joinToString(" ; ") { e -> "${t(e.at)} ${e.from ?: ""}→${e.to ?: ""}${e.other?.let { " $it" } ?: ""}${if (e.highlights > 0) " +${e.highlights}hl" else ""}" })
            }
        }
        line()
        line("== Held / jump / refusal marks")
        for ((k, v) in s.extras.filterKeys { it.startsWith("jump.") || it.startsWith("k:") || it.startsWith("metron.") || it.startsWith("feeds.") }) line("$k = ${v.take(80)}")
        line()
        // What the trackers asked about say right now, for the books being read (or touched in the last month).
        for (id in probe) {
            val tr = Trackers.byId(id) ?: continue
            val svc = s.service(id)
            line("== Live from ${tr.name}")
            if (!svc.linked) { line("not linked"); line(); continue }
            val session = Session(svc.credential)
            val now = System.currentTimeMillis() / 1000
            val links = s.links.values.filter { it.service == id && it.externalId != null }
                .mapNotNull { l -> s.books[l.document]?.let { b -> l to b } }
                .filter { (_, b) -> b.status == io.github.prithirajn.readync.data.ReadStatus.READING || now - b.timestamp < 30 * 86_400L }
                .sortedByDescending { it.second.timestamp }.take(12)
            if (links.isEmpty()) line("no matched books being read")
            for ((l, b) in links) {
                val r = runCatching { tr.state(session, l) }
                line("${b.title}: here ${b.status} ${b.percent}% · there " + r.fold({ "${it.status} ${it.percent ?: "-"}%" }, { "ERROR ${it.javaClass.simpleName}: ${it.message?.take(200)}" }))
            }
            line()
        }
        line("== Sync log (newest first, ${s.log.size})")
        for (e in s.log.sortedByDescending { it.time }.take(600)) line("${t(e.time / 1000)} ${if (e.error) "!" else " "} ${e.service ?: "-"}: ${e.message}")
        return out.toString()
    }
}
