package io.github.prithirajn.trackersync.source

import org.json.JSONArray
import org.json.JSONObject

/**
 * Which single issues each collected volume of a comic holds ("Saga, Vol. 1" collects #1–6), the
 * way book trackers list comics. Read from the volumes' own descriptions ("Collects SAGA #1-6"),
 * on Hardcover; when no tracker says, an estimate (six issues a volume, the usual trade
 * paperback) that you can change per series.
 */
object ComicCollections {

    data class Volume(val number: String, val issues: List<Double>)

    data class Map(
        val title: String,
        val volumes: List<Volume>,
        /** Where it came from: "hardcover", or "estimate" (so many issues a volume). */
        val source: String,
        /** Unix seconds. */
        val at: Long,
        val perVolume: Int? = null,
    ) {
        /** From a tracker's own volumes (not estimated, not yours): issues past them count singly. */
        val exact: Boolean get() = source != ESTIMATE && source != YOURS
        val lastIssue: Double? get() = volumes.flatMap { it.issues }.maxOrNull()
        fun volumeOf(issue: Double): Volume? = volumes.firstOrNull { v -> v.issues.any { kotlin.math.abs(it - issue) < 1e-6 } }

        fun toJson(): JSONObject = JSONObject().put("title", title).put("source", source).put("at", at).put("per", perVolume ?: JSONObject.NULL)
            .put("volumes", JSONArray(volumes.map { v -> JSONObject().put("n", v.number).put("i", JSONArray(v.issues)) }))

        companion object {
            fun fromJson(o: JSONObject) = Map(
                o.optString("title"),
                (0 until (o.optJSONArray("volumes")?.length() ?: 0)).mapNotNull { k ->
                    val v = o.getJSONArray("volumes").optJSONObject(k) ?: return@mapNotNull null
                    val a = v.optJSONArray("i") ?: JSONArray()
                    Volume(v.optString("n"), (0 until a.length()).map { a.optDouble(it) })
                },
                o.optString("source", ESTIMATE),
                o.optLong("at"),
                o.optInt("per", 0).takeIf { it > 0 },
            )
        }
    }

    const val ESTIMATE = "estimate"
    /** Volumes you set yourself. */
    const val YOURS = "you"

    /**
     * Two sources' volumes as one: [first]'s where both have a volume, the other's filling the
     * gaps, and none that overlap one already kept.
     */
    fun combine(first: Map?, second: Map?): Map? {
        if (first == null) return second
        if (second == null) return first
        val byNumber = LinkedHashMap<String, Volume>()
        for (v in second.volumes) byNumber[v.number] = v
        for (v in first.volumes) byNumber[v.number] = v
        val clean = ArrayList<Volume>()
        for (v in byNumber.values.sortedBy { it.number.toDoubleOrNull() ?: Double.MAX_VALUE }) if (clean.none { c -> c.issues.any { it in v.issues } }) clean += v
        return Map(first.title, clean, "${first.source}+${second.source}", maxOf(first.at, second.at))
    }

    /** Your volumes, carried on past the last one you set (each further volume the size of your last) to cover issue [upTo]. */
    fun extend(m: Map, upTo: Double): Map {
        val last = m.volumes.lastOrNull() ?: return m
        val size = last.issues.size.coerceAtLeast(1)
        var end = last.issues.max()
        var n = (last.number.toIntOrNull() ?: m.volumes.size)
        val out = m.volumes.toMutableList()
        while (end < upTo - 1e-6 && out.size < 500) {
            n++
            out += Volume(n.toString(), (1..size).map { end + it })
            end += size
        }
        return m.copy(volumes = out)
    }

    /** Where a map came from, said plainly: "Hardcover", "Hardcover and Seekquel", "you". */
    fun sourceName(m: Map): String = m.source.split('+').mapNotNull {
        when (it) { "hardcover" -> "Hardcover"; "seekquel" -> "Seekquel"; YOURS -> "you"; else -> null }
    }.joinToString(" and ")
    const val TYPICAL_PER_VOLUME = 6

    /** Volumes of [per] issues each, enough to cover issue [upTo] (Vol. 1 = #1–6, Vol. 2 = #7–12…). */
    fun estimate(title: String, upTo: Double, per: Int = TYPICAL_PER_VOLUME, now: Long = System.currentTimeMillis() / 1000): Map {
        val p = per.coerceIn(1, 50)
        val last = kotlin.math.ceil(upTo).toInt().coerceAtLeast(1)
        val count = (last + p - 1) / p
        return Map(title, (1..count).map { v -> Volume(v.toString(), ((v - 1) * p + 1..v * p).map { it.toDouble() }) }, ESTIMATE, now, p)
    }

    private val RANGE = Regex("""#\s*(\d+(?:\.\d+)?)(?:\s*(?:-|–|—|to|through|thru)\s*#?\s*(\d+(?:\.\d+)?))?""", RegexOption.IGNORE_CASE)
    private val ISSUES_WORD = Regex("""\bissues?\s+(\d+)\s*(?:-|–|—|to|through|thru)\s*(\d+)""", RegexOption.IGNORE_CASE)

    /**
     * The issues a volume's description says it collects ("Collects SAGA #1-6", "Collecting issues
     * 13 through 18", "#19-24 and the one-shot #25"). Ranges named after another series ("Batman
     * #1-7 and Detective Comics #27") keep only the ones under [series] when it's named.
     */
    fun parseCollects(text: String?, series: String? = null): List<Double> {
        if (text.isNullOrBlank()) return emptyList()
        val flat = text.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ")
        // Look from the word "collect" on, when it's there (a blurb's story text can mention issues too).
        val at = Regex("""(?i)\bcollect(?:s|ing|ed)?\b|\breprint(?:s|ing)?\b""").find(flat)?.range?.first
        val part = if (at != null) flat.substring(at, minOf(flat.length, at + 400)) else flat
        val want = series?.let { io.github.prithirajn.trackersync.sync.Matcher.baseTitle(it) }?.takeIf { it.isNotBlank() }
        val found = ArrayList<Pair<String, ClosedFloatingPointRange<Double>>>()
        for (m in RANGE.findAll(part)) {
            val a = m.groupValues[1].toDouble()
            val b = m.groupValues[2].toDoubleOrNull() ?: a
            if (b < a || b - a > 60) continue
            val before = part.substring(maxOf(0, m.range.first - 40), m.range.first)
            found += before to (a..b)
        }
        for (m in ISSUES_WORD.findAll(part)) {
            val a = m.groupValues[1].toDouble(); val b = m.groupValues[2].toDouble()
            if (b >= a && b - a <= 60) found += "" to (a..b)
        }
        if (found.isEmpty()) return emptyList()
        // When some ranges name this series just before them, only those count.
        val named = if (want != null) found.filter { (before, _) ->
            val tail = before.split(Regex("""[,;.&]|\band\b""")).last()
            io.github.prithirajn.trackersync.sync.Matcher.baseTitle(tail).contains(want)
        } else emptyList()
        val use = named.ifEmpty { found }
        val out = sortedSetOf<Double>()
        for ((_, r) in use) {
            if (r.start == r.endInclusive) out += r.start
            else { var n = r.start; while (n <= r.endInclusive + 1e-9) { out += n; n += 1.0 } }
        }
        return out.toList()
    }

    /**
     * Volumes from a series' numbered books (position, title, description), as a tracker lists
     * them. Each position keeps the edition whose description names the fewest issues (the trade
     * paperback over a deluxe edition); positions that don't say which issues are left out.
     */
    fun fromSeriesBooks(title: String, books: List<Triple<Double, String?, String?>>, source: String, now: Long = System.currentTimeMillis() / 1000): Map? {
        val vols = books.filter { it.first > 0 && it.first == Math.floor(it.first) }
            .groupBy { it.first.toInt() }
            .toSortedMap()
            .mapNotNull { (pos, eds) ->
                // A collected volume holds several issues: a series of single issues ("#5" at
                // position 5) isn't volumes, nor is a title that only names its own number.
                val best = eds.filter { !io.github.prithirajn.trackersync.sync.Matcher.isSingleIssue(it.second.orEmpty()) }
                    .map { parseCollects(it.third, title) }
                    .filter { it.size >= 2 }.minByOrNull { it.size } ?: return@mapNotNull null
                Volume(pos.toString(), best)
            }
        // Volumes that overlap the one before (an omnibus numbered in line) aren't trusted.
        val clean = ArrayList<Volume>()
        for (v in vols) if (clean.none { c -> c.issues.any { it in v.issues } }) clean += v
        return if (clean.isEmpty()) null else Map(title, clean, source, now)
    }
}
