package io.github.prithirajn.trackersync.trackers

import io.github.prithirajn.trackersync.data.Link
import io.github.prithirajn.trackersync.net.AuthException
import io.github.prithirajn.trackersync.net.HttpResponse
import io.github.prithirajn.trackersync.net.ServiceException
import org.json.JSONArray
import org.json.JSONObject

/*
 * Shared by the reading apps and servers linked like crosspoint-sync's connectors (Audiobookshelf,
 * Jellyfin, BookOrbit, BookFusion, Readest, Readwise Reader, Spotify, Micro.blog, another KOSync
 * server). Each one's own file follows crosspoint-sync's connector for the same service.
 */

/** Throws the right kind of error for a refused or failed answer from [name]. */
internal fun checkAnswer(r: HttpResponse, name: String, what: String = "", authOn403: Boolean = true) {
    when {
        r.code == 401 || (authOn403 && r.code == 403) -> throw AuthException("$name didn't accept the sign-in. Link it again.")
        r.code == 429 -> throw ServiceException("$name is busy (too many requests); will try again")
        !r.ok -> throw ServiceException("$name answered HTTP ${r.code}" + (if (what.isNotEmpty()) " $what" else "") +
            (serverReason(r.body)?.let { ": $it" } ?: ""))
    }
}

internal fun jsonOrNull(body: String): JSONObject? = runCatching { JSONObject(body) }.getOrNull()
internal fun jsonArrayOrNull(body: String): JSONArray? = runCatching { JSONArray(body) }.getOrNull()

/** Words of a title to tell whether a listed book could be it ("the", "of"… left out). */
internal fun titleWords(t: String): Set<String> =
    t.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in setOf("the", "and", "for", "book", "vol") }.toSet()

/** From a whole list of books (a library, a shelf): those sharing a title word with [title], for matching. */
internal fun likely(list: List<RemoteBook>, title: String): List<RemoteBook> {
    val want = titleWords(title)
    if (want.isEmpty()) return list.take(25)
    return list.filter { b -> titleWords(b.title).any { it in want } }
}

/** A position from 0–1, or from 0–100 when [hundred]. */
internal fun fraction(v: Double?, hundred: Boolean = false): Double? =
    v?.let { if (hundred) it / 100.0 else it }?.takeIf { !it.isNaN() }?.coerceIn(0.0, 1.0)

internal fun pct(f: Double?): Int? = f?.let { Math.round(it * 100).toInt().coerceIn(0, 100) }

/** Books a service has no EPUB for (audio only), so each sync doesn't ask again for an hour. */
private val noEpub = java.util.concurrent.ConcurrentHashMap<String, Long>()

/**
 * CrossPoint's exact place for [document] as a CFI in another copy of the book (downloaded with
 * [download], kept briefly under [key]), with the copy's own position. Null when there's no exact
 * place, no EPUB there, or the place lands somewhere else in that copy (another edition): then
 * only the percentage goes.
 */
internal fun exactIn(key: String, document: String, fraction: Double, download: () -> ByteArray?): EpubCfi.Position? {
    val exact = ExactPositions.of(document) ?: return null
    if ((noEpub[key] ?: 0L) > System.currentTimeMillis()) return null
    return runCatching {
        val book = EpubCfi.cached(key) { download() ?: throw EpubCfi.EpubException("no EPUB") }
        EpubCfi.position(book, exact.first).takeIf { kotlin.math.abs(it.inBook - fraction) <= 0.05 }
    }.onFailure { if (it.message?.contains("no EPUB") == true) noEpub[key] = System.currentTimeMillis() + 3_600_000 }.getOrNull()
}


/**
 * "Not the right spot?" for an audiobook: where your reading place matches a place in the
 * audiobook, kept per audiobook on the service's login. Reading and listening shares are then
 * mapped through it, in a straight line from the start to it and from it to the end: exact there,
 * proportional around it. (An EPUB's share counts every file by size, so on its own it runs ahead
 * of the narration by however much front matter there is.)
 */
object Anchors {
    data class Anchor(val text: Double, val audio: Double)

    /** A chapter (track) of an audiobook: where it starts and how long it is, in ms. */
    data class Track(val name: String, val startMs: Long, val durationMs: Long)

    /** An audiobook's tracks, its length, and where you're listening (ms into the book), when known. */
    data class Tracks(val tracks: List<Track>, val totalMs: Long, val positionMs: Long?)

    fun of(session: Session, link: Link): Anchor? {
        val o = session.data.optJSONObject("anchors")?.optJSONObject(link.externalId ?: return null) ?: return null
        val t = o.optDouble("text", -1.0)
        val a = o.optDouble("audio", -1.0)
        return if (t > 0 && t < 1 && a > 0 && a < 1) Anchor(t, a) else null
    }

    fun set(session: Session, externalId: String, anchor: Anchor?) {
        val all = session.data.optJSONObject("anchors") ?: JSONObject()
        if (anchor == null) all.remove(externalId) else all.put(externalId, JSONObject().put("text", anchor.text).put("audio", anchor.audio))
        session.put("anchors", all)
    }

    private fun through(x: Double, from: Double, to: Double): Double {
        if (!(from > 0 && from < 1 && to > 0 && to < 1)) return x
        return if (x <= from) x * to / from else to + (x - from) * (1 - to) / (1 - from)
    }

    fun textToAudio(f: Double, a: Anchor?) = if (a == null) f else through(f, a.text, a.audio)
    fun audioToText(f: Double, a: Anchor?) = if (a == null) f else through(f, a.audio, a.text)

    // ---- Chapter by chapter ----

    /**
     * One audiobook chapter matched to one chapter of the book: [text] where that chapter starts in
     * the book (0–1), [audio] where its track starts in the audiobook (0–1). Places are mapped
     * chapter by chapter through these, so an intro, credits or a long foreword on one side only
     * doesn't push everything after it off.
     */
    data class ChapterPair(val track: Int, val chapter: Int, val text: Double, val audio: Double)

    /** What's kept per audiobook: the matched chapters, whether you set them yourself, and when they were worked out. */
    data class ChapterMap(val pairs: List<ChapterPair>, val manual: Boolean, val at: Long, val none: Boolean = false)

    fun chapterMap(session: Session, externalId: String?): ChapterMap? {
        val o = session.data.optJSONObject("chapterMaps")?.optJSONObject(externalId ?: return null) ?: return null
        val arr = o.optJSONArray("pairs") ?: JSONArray()
        val pairs = (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONArray(i) ?: return@mapNotNull null
            ChapterPair(p.optInt(0), p.optInt(1), p.optDouble(2), p.optDouble(3)).takeIf { !it.text.isNaN() && !it.audio.isNaN() }
        }
        return ChapterMap(pairs, o.optBoolean("manual"), o.optLong("at"), o.optBoolean("none"))
    }

    fun setChapterMap(session: Session, externalId: String, map: ChapterMap?) {
        val all = session.data.optJSONObject("chapterMaps") ?: JSONObject()
        if (map == null) all.remove(externalId) else all.put(externalId, JSONObject()
            .put("pairs", JSONArray().also { a -> map.pairs.forEach { p -> a.put(JSONArray().put(p.track).put(p.chapter).put(p.text).put(p.audio)) } })
            .put("manual", map.manual).put("at", map.at).put("none", map.none))
        session.put("chapterMaps", all)
    }

    private val numberWords = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty")
    private val romans = Regex("^(?=[ivxlc]+$)c{0,3}(xc|xl|l?x{0,3})(ix|iv|v?i{0,3})$")

    private fun roman(s: String): Int? {
        if (!romans.matches(s)) return null
        val v = mapOf('i' to 1, 'v' to 5, 'x' to 10, 'l' to 50, 'c' to 100)
        var total = 0
        for (i in s.indices) { val a = v[s[i]]!!; val b = s.getOrNull(i + 1)?.let { v[it] } ?: 0; total += if (a < b) -a else a }
        return total.takeIf { it > 0 }
    }

    /** A chapter title as (its number, its other words): "04 - Chapter Four: The Storm" -> (4, {storm}). */
    internal fun key(title: String): kotlin.Pair<Int?, Set<String>> {
        val words = title.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        var number: Int? = null
        val rest = LinkedHashSet<String>()
        for ((i, w) in words.withIndex()) {
            val n = w.toIntOrNull() ?: numberWords.indexOf(w).takeIf { it >= 0 } ?: (if (i > 0 && words[i - 1] in setOf("chapter", "part", "book")) roman(w) else null)
            // The number after "Chapter" wins over a track's own leading number.
            if (n != null) { if (number == null || (i > 0 && words[i - 1] == "chapter")) number = n; continue }
            if (w !in setOf("chapter", "ch", "the", "a", "an", "of", "and", "track", "part")) rest += w
        }
        return number to rest
    }

    private val FILE_NAMED = Regex("(?i)^(track|file|disc|cd|part)\\s*\\d+\\b|\\b\\d+\\s*(of|/)\\s*\\d+\\b")
    private val AUDIO_ONLY = Regex("(?i)opening credits|closing credits|^credits$|introduction by the narrator|audible|copyright|end credits|dedication|epigraph|about the author|^intro$|^outro$")

    /**
     * Audiobook tracks matched to the book's chapters, in order, by their titles: the same number
     * (and a shared word when both have more than the number), or the same words ("Prologue").
     * Null when fewer than two match (tracks named "Track 01" or "Part 1 of 12" say nothing).
     */
    fun matchChapters(tracks: Tracks, headings: List<io.github.prithirajn.trackersync.source.EpubText.Heading>): List<ChapterPair>? {
        if (tracks.totalMs <= 0 || tracks.tracks.size < 2 || headings.size < 2) return null
        val keys = headings.map { key(it.title) }
        val out = ArrayList<ChapterPair>()
        var from = 0
        for ((ti, t) in tracks.tracks.withIndex()) {
            // Credits and the like, and tracks named by file ("Track 07", "Part 1 of 12", "Disc 2"), which say nothing about chapters.
            if (AUDIO_ONLY.containsMatchIn(t.name) || FILE_NAMED.containsMatchIn(t.name.trim())) continue
            val (tn, tw) = key(t.name)
            if (tn == null && tw.isEmpty()) continue
            val hit = (from until headings.size).firstOrNull { hi ->
                val (hn, hw) = keys[hi]
                when {
                    tn != null && hn != null -> tn == hn && (tw.isEmpty() || hw.isEmpty() || tw.any { it in hw })
                    tn == null && hn == null -> tw.isNotEmpty() && tw == hw
                    else -> false
                }
            } ?: continue
            val audio = t.startMs.toDouble() / tracks.totalMs
            // A match that puts the two far apart is a wrong one (the same number in another part of the book).
            if (kotlin.math.abs(audio - headings[hit].share) > 0.2) continue
            // Both sides keep moving forward.
            if (out.isNotEmpty() && (audio <= out.last().audio || headings[hit].share <= out.last().text)) continue
            out += ChapterPair(ti, headings[hit].spine, headings[hit].share, audio)
            from = hit + 1
        }
        return out.takeIf { it.size >= 2 }
    }

    /** Every point places are mapped through for this audiobook: its matched chapters and your "Not the right spot?" one. */
    fun points(session: Session, link: Link): List<Anchor> {
        val chapters = chapterMap(session, link.externalId)?.pairs.orEmpty().map { Anchor(it.text, it.audio) }
        val mine = of(session, link)
        // Yours wins: chapter points that would cross it are left out.
        val kept = if (mine == null) chapters else chapters.filter { (it.text < mine.text && it.audio < mine.audio) || (it.text > mine.text && it.audio > mine.audio) } + mine
        return kept.filter { it.text > 0 && it.text < 1 && it.audio > 0 && it.audio < 1 }.sortedBy { it.text }
            .fold(ArrayList<Anchor>()) { acc, a -> if (acc.isEmpty() || (a.text > acc.last().text && a.audio > acc.last().audio)) acc += a; acc }
    }

    private fun piecewise(x: Double, xs: List<Double>, ys: List<Double>): Double {
        val px = listOf(0.0) + xs + 1.0
        val py = listOf(0.0) + ys + 1.0
        val v = x.coerceIn(0.0, 1.0)
        for (i in 1 until px.size) if (v <= px[i]) {
            val span = px[i] - px[i - 1]
            return if (span <= 0) py[i] else py[i - 1] + (v - px[i - 1]) * (py[i] - py[i - 1]) / span
        }
        return 1.0
    }

    fun textToAudio(f: Double, points: List<Anchor>) = if (points.isEmpty()) f else piecewise(f, points.map { it.text }, points.map { it.audio })
    fun audioToText(f: Double, points: List<Anchor>) = if (points.isEmpty()) f else piecewise(f, points.map { it.audio }, points.map { it.text })

    /** Worked out again after this long when nothing matched (a copy of the book may have come in since). */
    private const val RETRY_SEC = 3 * 24 * 3600L

    /**
     * Matches this audiobook's chapters to the book's, once (kept on the login), from [tracks] and
     * the book's EPUB from [epub] (the audiobook server's own ebook, or the copy kept on this phone).
     * Nothing is downloaded from the audiobook. Chapters you matched yourself are left as they are.
     */
    fun ensureChapters(session: Session, link: Link, tracks: () -> Tracks?, epub: () -> ByteArray?) {
        val id = link.externalId ?: return
        val now = System.currentTimeMillis() / 1000
        val have = chapterMap(session, id)
        if (have != null && (have.manual || !have.none || now - have.at < RETRY_SEC)) return
        val pairs = runCatching {
            val bytes = epub() ?: return@runCatching null
            val t = tracks() ?: return@runCatching null
            matchChapters(t, io.github.prithirajn.trackersync.source.EpubText.outline(bytes))
        }.getOrNull()
        setChapterMap(session, id, ChapterMap(pairs.orEmpty(), manual = false, at = now, none = pairs == null))
    }

    /** The copy of [document] kept on this phone (Catalogs › keep a copy), if there is one. */
    fun phoneCopy(document: String): ByteArray? {
        val ctx = io.github.prithirajn.trackersync.source.BookCopies.appContext ?: return null
        val c = io.github.prithirajn.trackersync.source.BookCopies.get(ctx, document) ?: return null
        return runCatching { java.io.File(io.github.prithirajn.trackersync.source.BookCopies.dir(ctx), c.file).readBytes() }.getOrNull()
    }
}