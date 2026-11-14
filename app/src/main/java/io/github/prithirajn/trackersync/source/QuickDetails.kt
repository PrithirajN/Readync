package io.github.prithirajn.trackersync.source

import io.github.prithirajn.trackersync.data.BookFacts
import io.github.prithirajn.trackersync.data.Snapshot
import io.github.prithirajn.trackersync.net.Http
import io.github.prithirajn.trackersync.sync.Facts
import io.github.prithirajn.trackersync.sync.Matcher
import io.github.prithirajn.trackersync.trackers.GoodreadsTracker
import io.github.prithirajn.trackersync.trackers.HardcoverTracker
import io.github.prithirajn.trackersync.trackers.SeekquelTracker
import io.github.prithirajn.trackersync.trackers.Session
import io.github.prithirajn.trackersync.trackers.StoryGraphTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLEncoder

/**
 * A quick look at a book that isn't in your library (one found in a post): a short description,
 * its rating on each site that has it and overall, and its genres. Asks Hardcover, Goodreads,
 * StoryGraph and Seekquel where you've linked them, and Google Books always, all at once.
 */
object QuickDetails {

    data class Info(
        val title: String,
        val author: String?,
        val cover: String?,
        val description: String?,
        /** The sites' ratings averaged, weighted by how many ratings each has. */
        val rating: Double?,
        val ratingsCount: Int?,
        /** Each site's average out of 5 ("Hardcover" -> 4.2). */
        val ratings: Map<String, Double>,
        val genres: List<String>,
        val pages: Int?,
        val year: Int?,
        val sources: List<String>,
    )

    private class Part(
        val site: String,
        val facts: BookFacts? = null,
        val description: String? = null,
        val rating: Double? = null,
        val count: Int? = null,
        val genres: List<String> = emptyList(),
        val cover: String? = null,
        val pages: Int? = null,
        val year: Int? = null,
    )

    suspend fun gather(s: Snapshot, title: String, author: String?, isbn: String?, cover: String?): Info = coroutineScope {
        val now = System.currentTimeMillis() / 1000
        fun linked(id: String) = s.service(id).takeIf { it.linked && !it.needsReauth }?.let { Session(it.credential) }
        fun job(block: () -> Part?) = async(Dispatchers.IO) { withTimeoutOrNull(20_000) { runCatching(block).getOrNull() } }
        val jobs = listOfNotNull(
            job { google(title, author, isbn) },
            linked("hardcover")?.let { se -> job {
                val f = HardcoverTracker.facts(se, null, title, author, now)
                val d = runCatching { HardcoverTracker.details(se, null, title, author) }.getOrNull()
                if (f == null && d == null) null else Part("Hardcover", f, d?.description, f?.rating, f?.ratingsCount, cover = d?.coverUrl, pages = d?.pages)
            } },
            linked("seekquel")?.let { se -> job {
                val f = SeekquelTracker.facts(se, null, title, author, now)
                val d = runCatching { SeekquelTracker.details(se, null, title, author) }.getOrNull()
                if (f == null && d == null) null else Part("Seekquel", f, d?.description, f?.rating, f?.ratingsCount, pages = d?.pages)
            } },
            linked("storygraph")?.let { se -> job {
                StoryGraphTracker.facts(se, null, title, author, now)?.let { Part("StoryGraph", it, rating = it.rating, count = it.ratingsCount) }
            } },
            linked("goodreads")?.let { se -> job {
                val hits = GoodreadsTracker.search(se, Matcher.plainTitle(title), author)
                val d = Matcher.decide(Matcher.plainTitle(title), author, hits)
                val b = d.best?.takeIf { d.accepted } ?: hits.firstOrNull { Matcher.sameBook(title, author, it.title, it.author) }
                b?.let { Part("Goodreads", rating = it.rating, count = it.ratingsCount, cover = it.coverUrl, pages = it.pages, year = it.year) }
            } },
        )
        val parts: List<Part> = jobs.awaitAll().filterNotNull()

        // Each site's rating once (a site's facts may carry ratings from elsewhere too).
        val ratings = LinkedHashMap<String, Pair<Double, Int?>>()
        for (p in parts) {
            p.rating?.takeIf { it in 0.5..5.0 }?.let { ratings.putIfAbsent(p.site, it to p.count) }
            p.facts?.ratings?.forEach { (site, r) -> if (r in 0.5..5.0) ratings.putIfAbsent(site, r to null) }
        }
        // Weighted by ratings count where known (a site with 40 ratings counts for less than one with 40,000).
        val overall = ratings.values.takeIf { it.isNotEmpty() }?.let { rs ->
            val w = rs.map { (r, n) -> r to Math.log10(((n ?: 100) + 10).toDouble()) }
            w.sumOf { it.first * it.second } / w.sumOf { it.second }
        }
        val merged = Facts.merge(parts.mapNotNull { it.facts }, now)
        val genres = (merged.genres + parts.flatMap { it.genres })
            .map { it.trim() }.filter { it.isNotEmpty() && !it.equals("General", true) }
            .distinctBy { it.lowercase().replace(Regex("[^a-z0-9]+"), "") }.take(6)
        // The fullest description, Hardcover's or Seekquel's before Google Books', tidied and kept short.
        val description = parts.sortedBy { if (it.site == "Google Books") 1 else 0 }
            .mapNotNull { it.description?.let(::plain)?.takeIf { d -> d.length >= 40 } }.firstOrNull()?.let(::short)
        Info(
            title = title,
            author = author,
            cover = cover ?: parts.firstNotNullOfOrNull { it.cover },
            description = description,
            rating = overall,
            ratingsCount = ratings.values.mapNotNull { it.second }.takeIf { it.isNotEmpty() }?.sum(),
            ratings = ratings.mapValues { it.value.first },
            genres = genres,
            pages = parts.firstNotNullOfOrNull { it.pages },
            year = merged.year ?: parts.firstNotNullOfOrNull { it.year },
            sources = parts.map { it.site },
        )
    }

    private fun google(title: String, author: String?, isbn: String?): Part? {
        val q = isbn?.let { "isbn:$it" } ?: buildString {
            append("intitle:").append(Matcher.plainTitle(title))
            author?.let { append(" inauthor:").append(it) }
        }
        val j = JSONObject(Http.request("GET", "https://www.googleapis.com/books/v1/volumes?maxResults=5&printType=books&q=" + URLEncoder.encode(q, "UTF-8"), timeoutMs = 12_000).body)
        val items = j.optJSONArray("items") ?: return null
        val vols = (0 until items.length()).mapNotNull { items.optJSONObject(it)?.optJSONObject("volumeInfo") }
            .filter { v -> isbn != null || Matcher.sameBook(title, author, v.optString("title"), v.optJSONArray("authors")?.optString(0)) }
        if (vols.isEmpty()) return null
        // Editions differ: the description from the one that has the longest, the rating from the most rated.
        val rated = vols.maxByOrNull { it.optInt("ratingsCount", 0) }?.takeIf { it.optInt("ratingsCount", 0) > 0 }
        val genres = vols.flatMap { v -> v.optJSONArray("categories")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty() }
            .flatMap { it.split('/').map { p -> p.trim() } }.filter { it.isNotEmpty() && !it.equals("Fiction", true) }
        return Part(
            "Google Books",
            description = vols.mapNotNull { it.optString("description").ifBlank { null } }.maxByOrNull { it.length },
            rating = rated?.optDouble("averageRating")?.takeIf { !it.isNaN() },
            count = rated?.optInt("ratingsCount"),
            genres = genres,
            cover = vols.firstNotNullOfOrNull { v -> v.optJSONObject("imageLinks")?.optString("thumbnail")?.ifBlank { null }?.replace("http://", "https://")?.replace("&edge=curl", "") },
            pages = vols.firstNotNullOfOrNull { it.optInt("pageCount").takeIf { n -> n > 0 } },
            year = vols.firstNotNullOfOrNull { it.optString("publishedDate").take(4).toIntOrNull() },
        )
    }

    private fun plain(s: String) = io.github.prithirajn.trackersync.util.Html.text(s).replace(Regex("\\s+"), " ").trim()

    /** A few sentences: up to about 420 characters, ending at a sentence. */
    private fun short(s: String): String {
        if (s.length <= 420) return s
        val cut = s.take(420)
        val end = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
        return if (end >= 200) cut.take(end + 1) else cut.substringBeforeLast(' ') + "…"
    }
}
