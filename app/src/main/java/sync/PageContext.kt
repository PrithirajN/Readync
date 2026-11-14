package io.github.prithirajn.readync.sync

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The real text around a highlight, read off a photo of its page (an e-reader's screen or a paper
 * page): the photo's lines joined back into running text, and the highlight found in it, so a
 * book photo can show the passage among the words that are really around it.
 */
object PageContext {

    /** The page's text before the passage and after it. */
    data class Found(val before: String, val after: String, val score: Double)

    /**
     * The photo's lines as running text, in reading order: two columns (a spread) read left then
     * right; the running head, page numbers and status bars left out; words broken over a line
     * joined again.
     */
    fun join(lines: List<PageReader.Line>): String {
        if (lines.isEmpty()) return ""
        // Two pages side by side: lines that sit wholly in one half.
        val twoColumns = lines.count { it.right < 0.52f } >= 4 && lines.count { it.left > 0.48f } >= 4
        val ordered = lines.sortedWith(compareBy<PageReader.Line>({ if (twoColumns && it.left > 0.48f) 1 else 0 }, { it.top }, { it.left }))
        val heights = ordered.map { it.bottom - it.top }.sorted()
        val typical = heights[heights.size / 2]
        val kept = ordered.filter { l ->
            val t = l.text.trim()
            val words = t.split(Regex("\\s+")).size
            when {
                t.isEmpty() -> false
                // Page numbers, "12 / 340", "34%", clock and battery readouts.
                Regex("^[\\d\\s/%:.,|-]+$").matches(t) || Regex("(?i)^[ivxlc]{1,6}$").matches(t) -> false
                Regex("(?i)\\b(page|loc|location)\\s*\\d+|\\d+\\s*(min|mins|minutes)\\s+left|\\d+%$").containsMatchIn(t) && words <= 6 -> false
                // A short line at the very top or bottom: running head or footer.
                (l.top < 0.07f || l.bottom > 0.95f) && words <= 6 -> false
                // Much smaller or larger than the body text (captions, headings on the screen chrome).
                typical > 0f && (l.bottom - l.top) < typical * 0.55f && words <= 8 -> false
                else -> true
            }
        }
        val sb = StringBuilder()
        for (l in kept) {
            val t = l.text.trim()
            if (sb.isEmpty()) { sb.append(t); continue }
            val last = sb.last()
            if ((last == '-' || last == '­') && t.firstOrNull()?.isLowerCase() == true) {
                sb.setLength(sb.length - 1); sb.append(t)
            } else sb.append(' ').append(t)
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    /** Lines already in reading order (as the paper-highlight sheet has them) as running text. */
    fun joinText(lines: List<String>): String =
        join(lines.mapIndexed { i, t -> PageReader.Line(t, 0.1f + 0.8f * i / lines.size.coerceAtLeast(1), 0.1f + 0.8f * (i + 0.8f) / lines.size.coerceAtLeast(1)) })

    private data class Word(val norm: String, val start: Int, val end: Int)

    private fun words(s: String): List<Word> =
        Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’]*").findAll(s).map {
            Word(it.value.lowercase().replace("'", "").replace("’", ""), it.range.first, it.range.last + 1)
        }.toList()

    /** Equal, or one letter off for words of five letters or more (what a camera tends to misread). */
    private fun same(a: String, b: String): Boolean {
        if (a == b) return true
        if (min(a.length, b.length) < 5 || abs(a.length - b.length) > 1) return false
        var i = 0; var j = 0; var diff = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++diff > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> { i++; j++ }
            }
        }
        return diff + (a.length - i) + (b.length - j) <= 1
    }

    /**
     * Finds [passage] in [page] (text read off the photo). Null when it isn't there well enough
     * (fewer than six in ten of its words, in order, where it should be).
     */
    fun find(page: String, passage: String): Found? {
        val pw = words(page)
        val qw = words(passage)
        if (pw.isEmpty() || qw.size < 2) return null
        val n = qw.size
        var bestStart = -1
        var bestScore = 0
        // Line up the passage's words against each place on the page, allowing a word or two dropped.
        for (s in pw.indices) {
            var i = 0; var j = s; var score = 0; var first = -1
            while (i < n && j < pw.size && j < s + n + 4) {
                when {
                    same(qw[i].norm, pw[j].norm) -> { if (first < 0) first = j; score++; i++; j++ }
                    i + 1 < n && same(qw[i + 1].norm, pw[j].norm) -> i++ // a word the camera missed
                    j + 1 < pw.size && same(qw[i].norm, pw[j + 1].norm) -> j++ // a stray word read in
                    else -> { i++; j++ }
                }
            }
            if (score > bestScore) { bestScore = score; bestStart = first }
        }
        val ratio = bestScore.toDouble() / n
        if (bestStart < 0 || ratio < 0.6) return null
        // The end: where the passage's last words land, near where its length says.
        var end = min(pw.size - 1, bestStart + n - 1)
        val lastWord = qw.last().norm
        for (k in max(bestStart, bestStart + n - 4)..min(pw.size - 1, bestStart + n + 3)) {
            if (same(pw[k].norm, lastWord)) { end = k; break }
        }
        val startChar = pw[bestStart].start
        var endChar = pw[end].end
        // Keep closing punctuation with the passage.
        while (endChar < page.length && page[endChar] in ".,;:!?”\"'’)") endChar++
        return Found(page.substring(0, startChar).trim(), page.substring(endChar).trim(), ratio)
    }

    // ---- spotting the highlight on a photo of the page ----

    /**
     * One word read off the photo, with what its pixels look like: [bg] how light the paper behind
     * it is (0 black to 1 white), [sat] how coloured it is, [under] how much of a line runs under it.
     */
    data class Seen(val text: String, val line: Int, val bg: Float, val sat: Float, val under: Float)

    /**
     * The words that are marked: shaded grey (an e-ink highlight), tinted (a colour screen's
     * highlight or a highlighter pen) or underlined, against the rest of the page. The longest
     * marked run, allowing a single word the camera didn't catch; null when nothing stands out
     * (or everything does).
     */
    fun marked(words: List<Seen>): IntRange? {
        if (words.size < 4) return null
        val bgs = words.map { it.bg }.sorted()
        val sats = words.map { it.sat }.sorted()
        val refBg = bgs[bgs.size / 2]
        val refSat = sats[sats.size / 2]
        val flags = words.map { w ->
            w.bg < refBg - 0.07f || (w.sat > refSat + 0.1f && w.sat > 0.15f) || w.under > 0.6f
        }
        val count = flags.count { it }
        if (count < 2 || count > words.size * 0.7) return null
        var best: IntRange? = null
        var bestLen = 0
        var i = 0
        while (i < flags.size) {
            if (!flags[i]) { i++; continue }
            var end = i
            var j = i + 1
            while (j < flags.size) {
                if (flags[j]) { end = j; j++ }
                else if (j + 1 < flags.size && flags[j + 1]) j++ // one missed word inside the run
                else break
            }
            val len = (i..end).sumOf { words[it].text.length + 1 }
            if (len > bestLen) { bestLen = len; best = i..end }
            i = end + 1
        }
        return best?.takeIf { it.last > it.first }
    }

    /** The words of [range] as text, joining a word broken over two lines. */
    fun textOf(words: List<Seen>, range: IntRange): String {
        val sb = StringBuilder()
        for (k in range) {
            val t = words[k].text
            if (sb.isNotEmpty()) {
                val last = sb.last()
                if ((last == '-' || last == '\u00AD') && k > range.first && words[k - 1].line != words[k].line && t.firstOrNull()?.isLowerCase() == true) {
                    sb.setLength(sb.length - 1); sb.append(t); continue
                }
                sb.append(' ')
            }
            sb.append(t)
        }
        return sb.toString()
    }

}
