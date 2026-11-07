package io.github.prithirajn.trackersync.source

import io.github.prithirajn.trackersync.net.Http
import io.github.prithirajn.trackersync.sync.ComicIssues
import io.github.prithirajn.trackersync.sync.MangaSources
import io.github.prithirajn.trackersync.sync.Matcher
import org.json.JSONObject

/**
 * A single comic issue's cover ("Saga #1"): from Google Books, which lists single issues sold as
 * ebooks with their covers, else Hardcover when it's linked. Only a result that is that series and
 * that issue number counts.
 */
object IssueCovers {

    private fun json(url: String): JSONObject? = runCatching {
        val r = Http.request("GET", url, mapOf("Accept" to "application/json", "User-Agent" to "TrackerSync (Android; github.com/PrithirajN)"), timeoutMs = 12_000)
        if (r.ok) JSONObject(r.body) else null
    }.getOrNull()

    /** True when [title] is issue [n] of [series] ("Saga #1", "Saga (2012) #1", "Saga Issue 1"). */
    fun isIssue(title: String, series: String, n: Double): Boolean {
        val num = ComicIssues.issueNumber(title) ?: Regex("""(?i)\bissue\s+(\d+(?:\.\d+)?)""").find(title)?.groupValues?.get(1)?.toDoubleOrNull() ?: return false
        if (kotlin.math.abs(num - n) > 1e-6) return false
        val name = MangaSources.seriesTitleOf(title).replace(Regex("""\s*\(\d{4}[^)]*\)\s*$"""), "")
        return Matcher.baseTitle(name) == Matcher.baseTitle(series)
    }

    /** Google Books: "Saga #1" as an ebook. */
    fun google(series: String, n: Double): String? {
        val label = ComicIssues.label(n)
        val o = json("${BookInfo.googleBooks}/volumes?q=${Http.enc("intitle:\"$series\" #$label")}&maxResults=20&printType=books") ?: return null
        val items = o.optJSONArray("items") ?: return null
        for (i in 0 until items.length()) {
            val v = items.optJSONObject(i)?.optJSONObject("volumeInfo") ?: continue
            val title = listOfNotNull(v.optString("title").takeIf { it.isNotBlank() }, v.optString("subtitle").takeIf { it.isNotBlank() }).joinToString(" ")
            if (!isIssue(v.optString("title"), series, n) && !isIssue(title, series, n)) continue
            val img = v.optJSONObject("imageLinks")?.let { l -> l.optString("thumbnail").ifBlank { l.optString("smallThumbnail") } }?.takeIf { it.isNotBlank() } ?: continue
            return img.replace("http://", "https://").replace("&edge=curl", "")
        }
        return null
    }

    /** The cover for issue [n] of [series], or null when none was found. */
    fun find(series: String, n: Double, hardcover: ((String) -> List<io.github.prithirajn.trackersync.trackers.RemoteBook>)? = null): String? {
        google(series, n)?.let { return it }
        val label = ComicIssues.label(n)
        return hardcover?.let { search -> runCatching { search("$series #$label") }.getOrNull() }
            ?.firstOrNull { it.coverUrl != null && isIssue(it.title, series, n) }?.coverUrl
    }
}
