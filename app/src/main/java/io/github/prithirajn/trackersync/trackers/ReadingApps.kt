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
}