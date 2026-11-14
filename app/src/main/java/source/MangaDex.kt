package io.github.prithirajn.readync.source

import io.github.prithirajn.readync.net.Http
import io.github.prithirajn.readync.sync.Matcher
import org.json.JSONArray
import org.json.JSONObject

/**
 * MangaDex's public catalogue (no sign-in): which chapters are in which volume, and how many
 * pages each chapter has. That's what turns "chapter 44, page 10" into "Volume 5, 49%".
 */
object MangaDex {
    var base = "https://api.mangadex.org"

    data class Chapter(val number: Double, val pages: Int?)
    data class Volume(val number: String, val chapters: List<Chapter>) {
        val pages: Int? get() = chapters.mapNotNull { it.pages }.takeIf { it.size == chapters.size }?.sum()
    }
    data class Map(
        val mangadexId: String,
        val title: String,
        val volumes: List<Volume>,
        /** "mangadex", "estimate" (so many chapters a volume, when MangaDex hasn't the series) or "you". */
        val source: String = "mangadex",
        /** When it was looked up (unix seconds); 0 for ones kept before this was noted. */
        val at: Long = 0L,
    ) {
        val lastChapter: Double? get() = volumes.flatMap { it.chapters }.maxOfOrNull { it.number }
        val estimated: Boolean get() = source == "estimate"
        /** Which volume a chapter is in, or null when MangaDex doesn't say. */
        fun volumeOf(chapter: Double): Volume? = volumes.firstOrNull { v -> v.chapters.any { kotlin.math.abs(it.number - chapter) < 1e-6 } }

        fun toJson(): JSONObject = JSONObject().put("id", mangadexId).put("title", title).put("source", source).put("at", at).put("volumes", JSONArray(volumes.map { v ->
            JSONObject().put("n", v.number).put("c", JSONArray(v.chapters.map { c -> JSONArray().put(c.number).put(c.pages ?: -1) }))
        }))

        companion object {
            fun fromJson(o: JSONObject) = Map(
                o.optString("id"), o.optString("title"),
                (0 until (o.optJSONArray("volumes")?.length() ?: 0)).mapNotNull { i ->
                    val v = o.getJSONArray("volumes").optJSONObject(i) ?: return@mapNotNull null
                    val cs = v.optJSONArray("c") ?: JSONArray()
                    Volume(v.optString("n"), (0 until cs.length()).mapNotNull { j ->
                        cs.optJSONArray(j)?.let { a -> Chapter(a.optDouble(0), a.optInt(1, -1).takeIf { it > 0 }) }
                    })
                },
                o.optString("source", "mangadex"),
                o.optLong("at"),
            )
        }
    }

    private fun get(path: String): JSONObject? = runCatching {
        val r = Http.request("GET", base + path, mapOf("Accept" to "application/json", "User-Agent" to "TrackerSync (Android; github.com/PrithirajN)"), timeoutMs = 12_000)
        if (r.ok) JSONObject(r.body) else null
    }.getOrNull()

    /**
     * The series on MangaDex: the one whose AniList, MyAnimeList or MangaUpdates link matches
     * (when Mihon knows those), else the one whose title or other titles match.
     */
    fun find(title: String, anilistId: Long? = null, malId: Long? = null, mangaUpdatesId: Long? = null): Pair<String, String>? {
        val o = get("/manga?limit=10&title=${Http.enc(Matcher.plainTitle(title))}") ?: return null
        val data = o.optJSONArray("data") ?: return null
        val all = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
        fun titles(m: JSONObject): List<String> {
            val a = m.optJSONObject("attributes") ?: return emptyList()
            val main = a.optJSONObject("title")?.let { t -> t.keys().asSequence().map { t.optString(it) }.toList() }.orEmpty()
            val alt = a.optJSONArray("altTitles")?.let { arr -> (0 until arr.length()).flatMap { i -> arr.optJSONObject(i)?.let { t -> t.keys().asSequence().map { t.optString(it) }.toList() }.orEmpty() } }.orEmpty()
            return (main + alt).filter { it.isNotBlank() }
        }
        val byLink = all.firstOrNull { m ->
            val links = m.optJSONObject("attributes")?.optJSONObject("links") ?: return@firstOrNull false
            (anilistId != null && links.optString("al") == anilistId.toString()) ||
                (malId != null && links.optString("mal") == malId.toString()) ||
                (mangaUpdatesId != null && links.optString("mu").let { it == mangaUpdatesId.toString() || it.equals(java.lang.Long.toString(mangaUpdatesId, 36), true) })
        }
        val pick = byLink ?: all.firstOrNull { m -> titles(m).any { Matcher.baseTitle(it) == Matcher.baseTitle(title) } } ?: return null
        val name = pick.optJSONObject("attributes")?.optJSONObject("title")?.let { t -> t.optString("en").ifBlank { t.keys().asSequence().firstOrNull()?.let { t.optString(it) } } } ?: title
        return pick.optString("id") to (name ?: title)
    }

    /** Series names matching what you typed (MangaDex's own search, which forgives typos), English first. */
    fun searchTitles(query: String): List<String> {
        val o = get("/manga?limit=8&title=${Http.enc(query.trim())}") ?: return emptyList()
        val data = o.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val t = data.optJSONObject(i)?.optJSONObject("attributes")?.optJSONObject("title") ?: return@mapNotNull null
            t.optString("en").ifBlank { t.keys().asSequence().firstOrNull()?.let { t.optString(it) } }?.takeIf { it.isNotBlank() }
        }
    }

    /** The newest chapter out in English: (its number, when it came out in unix seconds), or null. */
    fun latestChapter(id: String): Pair<Double, Long?>? {
        val o = get("/manga/$id/feed?limit=10&translatedLanguage%5B%5D=en&order%5Bchapter%5D=desc&includeEmptyPages=0") ?: return null
        return parseLatest(o)
    }

    internal fun parseLatest(o: JSONObject): Pair<Double, Long?>? {
        val d = o.optJSONArray("data") ?: return null
        for (i in 0 until d.length()) {
            val a = d.optJSONObject(i)?.optJSONObject("attributes") ?: continue
            val n = a.optString("chapter").toDoubleOrNull() ?: continue
            val at = a.optString("publishAt").ifBlank { a.optString("readableAt") }.takeIf { it.isNotBlank() }
                ?.let { runCatching { java.time.OffsetDateTime.parse(it).toEpochSecond() }.getOrNull() }
            return n to at
        }
        return null
    }

    /** Which chapters are in which volume, with each chapter's page count where MangaDex has it. */
    fun volumes(id: String, title: String): Map? {
        val agg = get("/manga/$id/aggregate") ?: return null
        val pages = HashMap<Double, Int>()
        var offset = 0
        while (offset < 1000) {
            val feed = get(
                "/manga/$id/feed?limit=500&offset=$offset&order%5Bchapter%5D=asc&includeEmptyPages=0" +
                    "&contentRating%5B%5D=safe&contentRating%5B%5D=suggestive&contentRating%5B%5D=erotica&contentRating%5B%5D=pornographic",
            ) ?: break
            val d = feed.optJSONArray("data") ?: break
            for (i in 0 until d.length()) {
                val a = d.optJSONObject(i)?.optJSONObject("attributes") ?: continue
                val n = a.optString("chapter").toDoubleOrNull() ?: continue
                val p = a.optInt("pages", 0)
                // Scans differ a page or two; the fullest one counts.
                if (p > 0) pages[n] = maxOf(pages[n] ?: 0, p)
            }
            if (d.length() < 500) break
            offset += 500
        }
        return parseAggregate(id, title, agg, pages)
    }

    internal fun parseAggregate(id: String, title: String, agg: JSONObject, pages: kotlin.collections.Map<Double, Int>): Map? {
        val vols = agg.optJSONObject("volumes") ?: return null
        val out = ArrayList<Volume>()
        for (k in vols.keys()) {
            val v = vols.optJSONObject(k) ?: continue
            val number = v.optString("volume", k)
            if (number.equals("none", true) || number.isBlank()) continue // chapters not in a volume yet
            val chs = v.optJSONObject("chapters") ?: continue
            val list = chs.keys().asSequence().mapNotNull { c -> (chs.optJSONObject(c)?.optString("chapter") ?: c).toDoubleOrNull() }
                .distinct().sorted().map { Chapter(it, pages[it]) }.toList()
            if (list.isNotEmpty()) out += Volume(number, list)
        }
        if (out.isEmpty()) return null
        return Map(id, title, out.sortedBy { it.number.toDoubleOrNull() ?: Double.MAX_VALUE }, "mangadex", System.currentTimeMillis() / 1000)
    }
}
