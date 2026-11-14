package io.github.prithirajn.readync.sync

import io.github.prithirajn.readync.data.Book
import io.github.prithirajn.readync.data.ReadStatus
import io.github.prithirajn.readync.data.Store
import io.github.prithirajn.readync.net.ServiceException
import io.github.prithirajn.readync.source.Clipping
import org.json.JSONArray
import org.json.JSONObject

/**
 * Libby's own "Reading Journey" export (a title's Journey → Actions → Export → Data), saved as a
 * .json file and opened here: its reading position, highlights and notes, and borrow dates. This
 * is the route Libby offers for getting your reading out, so it needs no sign-in.
 */
object LibbyJourney {

    data class Entry(
        val titleId: String?,
        val title: String,
        val author: String?,
        val isbn: String?,
        val coverUrl: String?,
        val percent: Double?,
        val borrowedAt: Long?,
        val returnedAt: Long?,
        val highlights: List<Clipping>,
    )

    data class Result(val books: Int, val added: Int, val highlights: Int)

    /** Reads one export. Throws [ServiceException] when the file isn't a Libby Journey export. */
    fun parse(text: String): Entry {
        val o = runCatching { JSONObject(text.trim().removePrefix("﻿")) }.getOrNull()
            ?: throw ServiceException("That file isn't a Libby Reading Journey export")
        val j = o.optJSONObject("readingJourney")
            ?: throw ServiceException("That file isn't a Libby Reading Journey export")
        val t = j.opt("title")
        val title = (if (t is JSONObject) t.optString("text") else j.optString("title")).trim()
        if (title.isEmpty()) throw ServiceException("That Reading Journey has no title")
        val titleId = (t as? JSONObject)?.optString("titleId")?.takeIf { it.isNotBlank() }
            ?: (t as? JSONObject)?.optString("url")?.let { Regex("""/(\d+)/?$""").find(it)?.groupValues?.get(1) }
        val cover = j.opt("cover").let { c ->
            when (c) {
                is JSONObject -> c.optString("url").takeIf { it.startsWith("http") }
                is String -> c.takeIf { it.startsWith("http") }
                else -> null
            }
        }
        val percent = j.optDouble("percent", Double.NaN).takeIf { !it.isNaN() }?.let(::fraction)

        var borrowed: Long? = null
        var returned: Long? = null
        for (c in objects(o.optJSONArray("circulation"))) {
            val at = seconds(c.optLong("timestamp", 0L)) ?: continue
            when (c.optString("activity").lowercase()) {
                "borrowed" -> if (borrowed == null || at < borrowed) borrowed = at
                "returned" -> if (returned == null || at > returned) returned = at
            }
        }

        val key = titleId ?: title.lowercase().filter { it.isLetterOrDigit() }.take(40)
        val clips = objects(o.optJSONArray("highlights")).mapIndexedNotNull { i, h ->
            val quote = h.optString("quote").trim()
            val note = h.optString("note").trim().takeIf { it.isNotEmpty() }
            val body = quote.ifEmpty { note.orEmpty() }
            if (body.isEmpty()) return@mapIndexedNotNull null
            val at = seconds(h.optLong("timestamp", 0L)) ?: borrowed ?: (System.currentTimeMillis() / 1000L)
            Clipping(
                id = "libby-$key-j${h.optLong("timestamp", i.toLong())}-$i",
                document = "",
                text = body,
                note = if (quote.isNotEmpty()) note else null,
                chapter = h.optString("chapter").trim().takeIf { it.isNotEmpty() },
                createdAt = at,
                percent = h.optDouble("percent", Double.NaN).takeIf { !it.isNaN() }?.let(::fraction),
            )
        }
        return Entry(
            titleId = titleId,
            title = title,
            author = j.optString("author").trim().takeIf { it.isNotEmpty() },
            isbn = j.optString("isbn").filter { it.isDigit() || it == 'X' }.takeIf { it.length == 10 || it.length == 13 },
            coverUrl = cover,
            percent = percent,
            borrowedAt = borrowed,
            returnedAt = returned,
            highlights = clips,
        )
    }

    /**
     * Brings exports into the library: the book is matched (same Libby title, ISBN, or title and
     * author) or added, its position only ever moves forward, and its highlights replace the
     * ones an earlier import of the same book brought.
     */
    fun import(store: Store, texts: List<String>, now: Long = System.currentTimeMillis()): Result {
        val entries = texts.map(::parse)
        var added = 0
        var clipCount = 0
        val toQueue = LinkedHashSet<String>()
        store.update { s ->
            val books = s.books.toMutableMap()
            val clips = ArrayList(s.libbyClips)
            val sec = now / 1000L
            for (e in entries) {
                val libbyDoc = "libby:${e.titleId ?: e.title.lowercase().filter { it.isLetterOrDigit() }.take(40)}"
                val existing = books[libbyDoc]
                    ?: books.values.firstOrNull { b -> libbyDoc in b.aliases }
                    ?: books.values.firstOrNull { b -> e.isbn != null && b.isbn == e.isbn }
                    ?: books.values.firstOrNull { b -> b.title != null && Matcher.sameBook(e.title, e.author, b.title, b.author) }
                val doc = existing?.document ?: libbyDoc
                val pct = maxOf(existing?.percentage ?: 0.0, e.percent ?: 0.0)
                val finished = existing?.status == ReadStatus.FINISHED || pct >= 0.98
                val status = when {
                    finished -> ReadStatus.FINISHED
                    pct > 0.0 || e.highlights.isNotEmpty() -> ReadStatus.READING
                    else -> existing?.status ?: ReadStatus.TO_READ
                }
                val moved = existing == null || pct > existing.percentage + 0.0005 || status != existing.status
                val base = existing ?: Book(
                    document = doc, title = e.title, author = e.author, percentage = 0.0,
                    status = status, timestamp = sec, origin = "libby",
                ).also { added++ }
                val pages = base.pageCount
                books[doc] = base.copy(
                    percentage = pct,
                    status = status,
                    timestamp = if (moved) maxOf(sec, base.timestamp + 1) else base.timestamp,
                    isbn = base.isbn ?: e.isbn,
                    coverUrl = base.coverUrl ?: e.coverUrl,
                    startedAt = listOfNotNull(base.startedAt, e.borrowedAt).minOrNull(),
                    finishedAt = base.finishedAt ?: if (finished && pct >= 0.98) e.returnedAt else null,
                    pagesRead = if (pages != null && pages > 0 && pct > 0) Math.round(pct * pages).toInt().coerceIn(0, pages) else base.pagesRead,
                    aliases = if (doc != libbyDoc && libbyDoc !in base.aliases) base.aliases + libbyDoc else base.aliases,
                )
                if (e.highlights.isNotEmpty()) {
                    val ids = e.highlights.map { it.id }.toSet()
                    clips.removeAll { it.id in ids }
                    clips.addAll(e.highlights.map { it.copy(document = doc) })
                    clipCount += e.highlights.size
                }
                if (moved) toQueue += doc
            }
            s.copy(books = books, libbyClips = clips)
        }
        for (svc in store.snapshot.services.values.filter { it.linked && it.enabled }) {
            if (toQueue.isNotEmpty()) SyncEngine.queue(store, svc.id, toQueue)
        }
        store.log(
            "libby",
            "Read ${entries.size} Libby Reading Journe${if (entries.size == 1) "y" else "ys"}" +
                (if (added > 0) " · $added new book${if (added == 1) "" else "s"}" else "") +
                (if (clipCount > 0) " · $clipCount highlight${if (clipCount == 1) "" else "s"}" else ""),
        )
        return Result(entries.size, added, clipCount)
    }

    private fun fraction(v: Double) = (if (v > 1.0) v / 100.0 else v).coerceIn(0.0, 1.0)

    /** Libby writes milliseconds; anything that looks like seconds is kept as is. */
    private fun seconds(v: Long): Long? = when {
        v <= 0L -> null
        v > 100_000_000_000L -> v / 1000L
        else -> v
    }

    private fun objects(a: JSONArray?): List<JSONObject> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}
