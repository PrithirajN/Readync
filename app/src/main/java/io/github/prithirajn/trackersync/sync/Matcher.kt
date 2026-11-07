package io.github.prithirajn.trackersync.sync

import io.github.prithirajn.trackersync.trackers.RemoteBook
import java.text.Normalizer

/**
 * Title/author matching, ported from crosspoint-sync's src/connectors/matching.ts so a book
 * matches here the same way it does on the server.
 */
object Matcher {
    private val marks = Regex("\\p{M}+")
    private val nonWord = Regex("[^\\p{L}\\p{N}\\s]")
    private val spaces = Regex("\\s+")
    private val subtitle = Regex("\\s*[:—-]\\s.*$")
    private val seriesSuffix = Regex("\\s*\\((?:[^)]*\\b(?:book|vol|volume|part|no)\\b[^)]*)\\)\\s*$", RegexOption.IGNORE_CASE)

    /** Fold diacritics, lowercase, drop punctuation, collapse whitespace. */
    fun normalizeText(s: String): String {
        var t = Normalizer.normalize(s, Normalizer.Form.NFKD)
        t = marks.replace(t, "").lowercase()
        t = nonWord.replace(t, " ")
        return spaces.replace(t, " ").trim()
    }

    /** Strip a subtitle after a colon or dash, and a trailing "(Series, Book 2)". */
    fun coreTitle(title: String): String = seriesSuffix.replace(subtitle.replace(title, ""), "").trim()

    private val bracketed = Regex("\\s*[\\[(][^\\])]*[\\])]")

    /** The title as written, without [tags] or (asides): "The Doomsday Reaver [LitRPG, …]" -> "The Doomsday Reaver". */
    fun plainTitle(title: String): String = bracketed.replace(title, " ").replace(spaces, " ").trim().ifEmpty { title.trim() }

    /** The title without any [tags] or (asides) and without a subtitle: "The Doomsday Reaver". */
    fun baseTitle(title: String): String = normalizeText(subtitle.replace(bracketed.replace(title, " "), ""))

    private val volumeWords = Regex(
        "(?i)(?:\\bvol(?:ume)?\\.?|\\bv\\.|\\bbook|\\btome|\\bpart|\\bissue|\\bno\\.|#)\\s*(\\d{1,4}(?:\\.\\d)?)\\b",
    )

    /**
     * The volume or book number in a title: "One Piece, Vol. 3" -> "3", "Attack on Titan: Volume
     * 12" -> "12", "Dungeon Crawler Carl Book 4" -> "4", "Saga #54" -> "54". Null when there's none.
     */
    fun volume(title: String): String? =
        volumeWords.findAll(title).lastOrNull()?.groupValues?.get(1)?.trimStart('0')?.ifEmpty { "0" }

    /** Two titles with different volume numbers are different books, however alike the rest is. */
    fun differentVolumes(a: String, b: String): Boolean {
        val va = volume(a) ?: return false
        val vb = volume(b) ?: return false
        return va != vb
    }

    /**
     * Whether two listings are the same book, however differently titled: a web novel as
     * "Title [LitRPG, tagline]" on one side and "Title: tagline" on the other. Same base title,
     * and the same author when both have one.
     */
    fun sameBook(titleA: String, authorA: String?, titleB: String, authorB: String?): Boolean {
        val a = baseTitle(titleA)
        val b = baseTitle(titleB)
        if (a.isEmpty() || b.isEmpty() || a != b) return false
        // Volume 3 of a manga isn't volume 1, though their titles match once trimmed.
        if (differentVolumes(titleA, titleB)) return false
        val wa = authorA?.let { normalizeAuthor(it) }.orEmpty()
        val wb = authorB?.let { normalizeAuthor(it) }.orEmpty()
        return wa.isEmpty() || wb.isEmpty() || overlap(wa, wb) >= 0.5
    }

    /**
     * "Bahadur, Bhoot" (how Kindle and library catalogs list authors) as "Bhoot Bahadur", the way
     * trackers' searches expect it. Anything else (several authors, "Jr.") is left as it is.
     */
    fun naturalAuthor(author: String?): String? {
        val t = author?.trim()?.trimEnd(':', ';', ',')?.trim() ?: return null
        val parts = t.split(',')
        if (parts.size != 2) return t.ifEmpty { null }
        val last = parts[0].trim()
        val first = parts[1].trim()
        fun words(x: String) = x.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
        if (last.isEmpty() || first.isEmpty() || words(last) > 3 || words(first) > 3) return t
        if (first.trimEnd('.').lowercase() in setOf("jr", "sr", "ii", "iii", "iv", "phd", "md")) return t
        return "$first $last"
    }

    /** "Last, First" -> "first last"; otherwise just normalized. */
    fun normalizeAuthor(author: String): String {
        val t = author.trim()
        val comma = t.indexOf(',')
        if (comma > 0 && comma == t.lastIndexOf(',')) {
            return normalizeText(t.substring(comma + 1).trim() + " " + t.substring(0, comma).trim())
        }
        return normalizeText(t)
    }

    /** Jaccard overlap of the two strings' word sets. */
    private fun overlap(a: String, b: String): Double {
        val sa = a.split(' ').filter { it.isNotEmpty() }.toSet()
        val sb = b.split(' ').filter { it.isNotEmpty() }.toSet()
        if (sa.isEmpty() || sb.isEmpty()) return 0.0
        val inter = sa.count { it in sb }
        return inter.toDouble() / (sa.size + sb.size - inter)
    }

    private val volumeWord = Regex("""(?i)\b(?:vol(?:ume)?\.?\s*\d|tpb|trade paperback|omnibus|compendium|deluxe|collection|book\s+\d)""")
    private val issueMark = Regex("""(?i)(?:#\s*\d|\bissue\s+\d|\(single\s+issues?\))""")

    /** A collected volume's title ("Saga, Vol. 9", "… Volume 2", "… TPB"). */
    fun isVolumeTitle(title: String): Boolean = volumeWord.containsMatchIn(title)

    /**
     * A single comic issue's listing ("Saga #54", "Saga Issue 54", a "(Single Issues)" series),
     * or one too short to be a volume: some trackers list single issues next to the volumes.
     */
    fun isSingleIssue(title: String, pages: Int? = null): Boolean {
        // "(Dungeon Crawler Carl, #4)" is a novel's place in its series, not an issue.
        val t = title.replace(Regex("""\([^()]*,\s*#\s*\d+(?:\.\d+)?\)"""), "")
        return (issueMark.containsMatchIn(t) && !volumeWord.containsMatchIn(t)) || (pages != null && pages in 1..60)
    }

    private val mangaVolume = Regex("""(?i),\s*vol\.\s*\d+(?:\.\d+)?\s*$""")

    fun score(wantTitle: String, wantAuthor: String?, cand: RemoteBook): Double {
        // A manga or comic volume ("Saga, Vol. 9") is never matched to a single issue, however alike the titles.
        if (mangaVolume.containsMatchIn(wantTitle) && isSingleIssue(cand.title, cand.pages)) return 0.0
        val wt = normalizeText(coreTitle(wantTitle))
        val ct = normalizeText(coreTitle(cand.title))
        var titleScore = if (wt.isNotEmpty() && ct.isNotEmpty()) overlap(wt, ct) else 0.0
        // Volumes: a different number is a different book (scored low); the same number, where
        // the rest of the title is close, is the one.
        if (differentVolumes(wantTitle, cand.title)) titleScore *= 0.3
        else if (volume(wantTitle) != null && volume(wantTitle) == volume(cand.title)) titleScore = minOf(1.0, titleScore + 0.15)
        val wa = wantAuthor?.let { normalizeAuthor(it) } ?: ""
        val ca = cand.author?.let { normalizeAuthor(it) } ?: ""
        if (wa.isEmpty() || ca.isEmpty()) return titleScore * 0.85
        return titleScore * 0.7 + overlap(wa, ca) * 0.3
    }

    data class Decision(val best: RemoteBook?, val score: Double, val accepted: Boolean)

    /**
     * Auto-accept only a clear winner: score >= [threshold] and ahead of the runner-up by
     * [margin], unless the runner-up is the same book (another edition).
     */
    fun decide(
        wantTitle: String,
        wantAuthor: String?,
        candidates: List<RemoteBook>,
        threshold: Double = 0.6,
        margin: Double = 0.15,
    ): Decision {
        if (candidates.isEmpty()) return Decision(null, 0.0, false)
        // Ties (editions with the same title and author) go to the more-rated one, as on the
        // server; after that the tracker's own ranking, since the sort is stable.
        val scored = candidates.map { it to score(wantTitle, wantAuthor, it) }
            .sortedWith(compareByDescending<Pair<RemoteBook, Double>> { it.second }.thenByDescending { it.first.popularity ?: 0 })
        val (best, bestScore) = scored[0]
        if (bestScore < threshold) return Decision(best, bestScore, false)
        val runner = scored.getOrNull(1) ?: return Decision(best, bestScore, true)
        val sameBook = normalizeText(coreTitle(best.title)) == normalizeText(coreTitle(runner.first.title)) &&
            normalizeAuthor(best.author ?: "") == normalizeAuthor(runner.first.author ?: "") &&
            !differentVolumes(best.title, runner.first.title)
        if (sameBook) return Decision(best, bestScore, true)
        return Decision(best, bestScore, bestScore - runner.second >= margin)
    }
}
