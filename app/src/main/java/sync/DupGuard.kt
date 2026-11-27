package io.github.prithirajn.readync.sync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.text.Normalizer
import kotlin.math.abs

/**
 * Never the same post twice. Each phone keeps its own record of what it has posted, so a second
 * phone (a test phone, a restored backup) can't know what the first one put up. Before anything
 * goes out, the account's own recent posts are read back from the service itself, and a post that's
 * already there isn't sent again:
 *
 *  - words: the same passage (a picture's description) or the same words, ignoring case, accents,
 *    punctuation, links and hashtags;
 *  - Instagram stories (which have no words): the same picture, judged on a small copy of the whole
 *    story (the book's cover) and of its top band (the moment's label: "Halfway there" isn't
 *    "Three-quarters of the way").
 *
 * When the service can't be asked (no connection, a field it doesn't know), the post goes out as
 * before: the check never stops posting on its own.
 */
object DupGuard {
    /** How far back plain words count (a reworded "still reading" line can repeat on another day). */
    const val WORDS_DAYS = 4L
    /** How far back a passage counts (the same passage is the same post however long ago). */
    const val PASSAGE_DAYS = 120L

    /** [s] reduced to what it says: no accents, links, hashtags, mentions or punctuation; lower case. */
    fun key(s: String?): String {
        if (s.isNullOrBlank()) return ""
        val plain = Normalizer.normalize(s, Normalizer.Form.NFKD)
            .replace(Regex("https?://\\S+"), " ")
            .replace(Regex("(?<![\\w/])[#@][\\p{L}\\d_.]+"), " ")
        val sb = StringBuilder(plain.length)
        var space = false
        for (ch in plain) {
            when {
                Character.getType(ch) == Character.NON_SPACING_MARK.toInt() -> {}
                ch.isLetterOrDigit() -> { sb.append(ch.lowercaseChar()); space = false }
                !space && sb.isNotEmpty() -> { sb.append(' '); space = true }
            }
        }
        return sb.toString().trim()
    }

    /** Whether [a] and [b] say the same: equal, or one long enough (60+) found whole in the other. */
    fun same(a: String?, b: String?): Boolean {
        val x = key(a); val y = key(b)
        if (x.length < 24 || y.length < 24) return false
        if (x == y) return true
        val (short, long) = if (x.length <= y.length) x to y else y to x
        return short.length >= 60 && long.contains(short)
    }

    /** Whether [text] carries the passage [q] whole (a caption quoting it). */
    fun holds(text: String?, q: String?): Boolean {
        val k = key(q)
        return k.length >= 24 && key(text).contains(k)
    }

    /** A service's timestamp ("2026-11-24T17:31:02+0000" or ISO) as unix seconds; null if unreadable. */
    fun time(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return runCatching { java.time.OffsetDateTime.parse(s, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")).toEpochSecond() }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(s).toEpochSecond() }.getOrNull()
            ?: runCatching { java.time.Instant.parse(s).epochSecond }.getOrNull()
    }

    /** One post already on a service: its id, words, picture descriptions and when (unix seconds). */
    class Seen(val id: String, val text: String?, val alts: List<String> = emptyList(), val at: Long? = null)

    /**
     * The first of [seen] that is this post: its [passages] (picture descriptions, the quote) against
     * theirs and their words, within [PASSAGE_DAYS]; its [words] against their words, within [WORDS_DAYS].
     */
    fun find(seen: List<Seen>, words: String?, passages: List<String?> = emptyList(), now: Long = System.currentTimeMillis() / 1000): Seen? =
        seen.firstOrNull { p ->
            val age = p.at?.let { now - it } ?: 0L
            val passageHit = age <= PASSAGE_DAYS * 86_400 && passages.any { q -> !q.isNullOrBlank() && (p.alts.any { same(q, it) } || holds(p.text, q)) }
            val wordsHit = age <= WORDS_DAYS * 86_400 && same(words, p.text)
            passageHit || wordsHit
        }

    // ---- story pictures ----

    /** A story picture in small: the whole (27×48 grey) and its top band where the moment's label sits (108×8). */
    class Look(val whole: IntArray, val band: IntArray)

    fun look(bytes: ByteArray): Look? = runCatching {
        val o = BitmapFactory.Options().apply { inSampleSize = 4 }
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o) ?: return null
        look(b).also { b.recycle() }
    }.getOrNull()

    fun look(b: Bitmap): Look {
        fun grey(src: Bitmap, w: Int, h: Int): IntArray {
            val s = Bitmap.createScaledBitmap(src, w, h, true)
            val px = IntArray(w * h); s.getPixels(px, 0, w, 0, 0, w, h)
            if (s !== src) s.recycle()
            return IntArray(px.size) { val p = px[it]; ((p shr 16 and 0xFF) * 3 + (p shr 8 and 0xFF) * 6 + (p and 0xFF)) / 10 }
        }
        val whole = grey(b, 27, 48)
        // The label band: rows 240..334 of a 1920-tall story, whatever size the copy is.
        val y0 = (b.height * 240f / 1920f).toInt().coerceIn(0, b.height - 2)
        val y1 = (b.height * 334f / 1920f).toInt().coerceIn(y0 + 1, b.height)
        val crop = Bitmap.createBitmap(b, 0, y0, b.width, y1 - y0)
        val band = grey(crop, 108, 8)
        if (crop !== b) crop.recycle()
        return Look(whole, band)
    }

    private fun mad(a: IntArray, b: IntArray): Float = if (a.size != b.size || a.isEmpty()) 255f else a.indices.sumOf { abs(a[it] - b[it]) }.toFloat() / a.size

    /** Whether two story pictures are the same story (the same book and the same moment). */
    fun sameStory(a: Look, b: Look): Boolean = mad(a.whole, b.whole) < 12f && mad(a.band, b.band) < 10f
}
