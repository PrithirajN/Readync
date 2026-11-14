package io.github.prithirajn.readync.sync

import io.github.prithirajn.readync.data.BookFacts

/**
 * Puts together what Hardcover, StoryGraph and Seekquel say about a book. A mood, genre or
 * warning more than one site agrees on comes first; otherwise each site's own order is kept,
 * Hardcover's first. Series, year and the rating shown come from the first site that has them.
 */
object Facts {

    /** [parts] in order of preference (Hardcover, StoryGraph, Seekquel). */
    fun merge(parts: List<BookFacts>, now: Long): BookFacts {
        val got = parts.filter { !it.isEmpty }
        if (got.isEmpty()) return BookFacts(at = now)
        fun combined(pick: (BookFacts) -> List<String>, max: Int): List<String> {
            // lower-case -> (as first written, how many sites, best position in any site)
            val seen = LinkedHashMap<String, Triple<String, Int, Int>>()
            got.forEachIndexed { siteIndex, f ->
                pick(f).forEachIndexed { i, v ->
                    val key = norm(v)
                    if (key.isEmpty()) return@forEachIndexed
                    val prev = seen[key]
                    val rank = i * 3 + siteIndex
                    seen[key] = if (prev == null) Triple(tidy(v), 1, rank) else Triple(prev.first, prev.second + 1, minOf(prev.third, rank))
                }
            }
            return seen.values.sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenBy { it.third })
                .map { it.first }.take(max)
        }
        val graphic = got.flatMap { it.graphic }.distinctBy { norm(it) }
        val graphicKeys = graphic.map { norm(it) }.toSet()
        val warnings = combined({ it.warnings }, 20).sortedBy { if (norm(it) in graphicKeys) 0 else 1 }.take(16)
        return BookFacts(
            series = got.firstNotNullOfOrNull { it.series },
            seriesNumber = got.firstOrNull { it.series != null }?.seriesNumber,
            rating = got.firstNotNullOfOrNull { it.rating },
            ratingsCount = got.firstOrNull { it.rating != null }?.ratingsCount,
            year = got.firstNotNullOfOrNull { it.year },
            pace = got.firstNotNullOfOrNull { it.pace },
            moods = combined({ it.moods }, 6),
            genres = combined({ it.genres }, 6),
            tropes = combined({ it.tropes }, 8),
            warnings = warnings,
            graphic = graphic.filter { g -> warnings.any { norm(it) == norm(g) } },
            ratings = got.flatMap { it.ratings.entries }.associate { it.key to it.value },
            sources = got.flatMap { it.sources }.distinct(),
            at = now,
        )
    }

    /** For telling two spellings apart: "Science-Fiction", "science fiction" are the same. */
    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun tidy(s: String) = s.trim().replaceFirstChar { it.uppercase() }
}
