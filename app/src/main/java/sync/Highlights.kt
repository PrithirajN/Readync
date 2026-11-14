package io.github.prithirajn.readync.sync

import io.github.prithirajn.readync.data.ClipEdit
import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.data.Store
import io.github.prithirajn.readync.net.ServiceException
import io.github.prithirajn.readync.source.Clipping
import io.github.prithirajn.readync.trackers.Trackers

/** A book's highlights and notes: listing, editing, and sending again. */
object Highlights {

    /** One highlight as shown under its book: your edited text, where it came from, where it went. */
    data class Item(
        val clip: Clipping,
        val fromPaper: Boolean,
        val edited: Boolean,
        /** Where it came from, to show: "CrossPoint", "Physical book", "Kindle", "Moon+ Reader", "Hardcover history"… */
        val from: String = if (fromPaper) "Physical book" else "CrossPoint",
        /** Trackers it was sent to, and those waiting to send it again. */
        val sentTo: List<String>,
        val resending: List<String>,
    )

    fun forBook(s: Snapshot, document: String): List<Item> {
        val paperIds = s.paperClips.map { it.id }.toSet()
        val kindleIds = s.kindleClips.map { it.id }.toSet()
        val libbyIds = s.libbyClips.map { it.id }.toSet()
        val moonIds = s.moonClips.map { it.id }.toSet()
        val historyIds = s.historyClips.map { it.id }.toSet()
        val koIds = s.koClips.map { it.id }.toSet()
        return (s.cpClips + s.paperClips + s.kindleClips + s.libbyClips + s.moonClips + s.historyClips + s.koClips).filter { it.document == document && it.id !in s.clipHidden }
            .distinctBy { it.id }
            .sortedByDescending { it.createdAt }
            .map { c ->
                Item(
                    clip = SyncEngine.edited(c, s.clipEdits),
                    fromPaper = c.id in paperIds,
                    from = when (c.id) {
                        in paperIds -> "Physical book"
                        in kindleIds -> "Kindle"
                        in libbyIds -> "Libby"
                        in moonIds -> "Moon+ Reader"
                        in historyIds -> historyFrom(c.id)
                        in koIds -> if (KoHighlightSync.isNote(c.id)) "KOReader note" else "KOReader"
                        else -> "CrossPoint"
                    },
                    edited = c.id in s.clipEdits,
                    sentTo = Trackers.all.filter { "${it.id}|${c.id}" in s.sentClippings }.map { it.id },
                    resending = Trackers.all.filter { "${it.id}|${c.id}" in s.resend }.map { it.id },
                )
            }
    }

    /** "Hardcover history" for an imported highlight, from its id ("hist-hardcover-…"). */
    private fun historyFrom(id: String): String {
        val src = id.removePrefix("hist-").substringBefore('-')
        return (HistoryImport.originName(HistoryImport.ORIGIN_PREFIX + src) ?: "Imported") + " history"
    }

    private val INVISIBLE = Regex("[\u200B\u200C\u200D\u2060\uFEFF\u00AD]")
    private val TAGS = Regex("(?i)</?(?:p|i|em|b|strong|span|div|a|sup|sub|small|u|font)\\b[^>]*>")
    private val BR = Regex("(?i)<br\\s*/?>")
    private val ENTITY = Regex("&(#\\d{1,7}|#x[0-9a-fA-F]{1,6}|amp|lt|gt|quot|apos|nbsp|mdash|ndash|hellip|lsquo|rsquo|ldquo|rdquo);")

    /**
     * A highlight's text made clean for a tracker: no invisible characters (zero-width spaces, soft
     * hyphens), HTML entities (&amp;, &#8217;) turned back into characters, stray tags (<br>, <i>)
     * dropped, a word split across a line ("exam-\nple") joined, single line breaks inside a
     * paragraph made spaces, and runs of spaces made one. Paragraph breaks stay.
     */
    fun tidyText(raw: String): String {
        var t = raw.replace(INVISIBLE, "").replace(BR, "\n").replace(TAGS, "")
        t = ENTITY.replace(t) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
                "mdash" -> "\u2014"; "ndash" -> "\u2013"; "hellip" -> "\u2026"; "lsquo" -> "\u2018"; "rsquo" -> "\u2019"; "ldquo" -> "\u201C"; "rdquo" -> "\u201D"
                else -> runCatching { String(Character.toChars(if (e.startsWith("#x")) e.drop(2).toInt(16) else e.drop(1).toInt())) }.getOrDefault(m.value)
            }
        }
        t = t.replace('\u00A0', ' ').replace("\r\n", "\n").replace('\r', '\n')
        t = t.replace(Regex("(\\p{L})-\\n(\\p{Ll})"), "$1$2")
        t = t.replace(Regex("[ \\t]*\\n[ \\t]*\\n\\s*"), "\u0000").replace(Regex("\\s*\\n\\s*"), " ").replace('\u0000', '\n')
            .replace(Regex("\\n"), "\n\n")
        return t.replace(Regex("[ \\t]{2,}"), " ").trim()
    }

    /** [c] with its text and note tidied (see [tidyText]). */
    fun tidy(c: Clipping): Clipping {
        val text = tidyText(c.text)
        val note = c.note?.let { tidyText(it) }?.takeIf { it.isNotEmpty() }
        return if (text == c.text && note == c.note) c else c.copy(text = text, note = note)
    }

    /** A highlight's words for comparing across routes: letters and digits only, lower case. */
    fun words(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }.take(800)

    /** What a tracker got for a highlight (its words and note), to notice when either changes. */
    fun fingerprint(c: Clipping): Int = (words(c.text) + "\u0001" + (c.note ?: "").trim()).hashCode()

    /**
     * The same passage: the same words, or one inside the other, or a long shared start or end
     * (two devices drawing the edges a word apart, or a highlight extended).
     */
    fun samePassage(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val (short, long) = if (a.length <= b.length) a to b else b to a
        if (short.length < 30) return false
        if (short.length * 2 >= long.length && long.contains(short)) return true
        return short.length >= 40 && (long.startsWith(short.take(40)) || long.endsWith(short.takeLast(40)))
    }

    /** Every highlight and note across the library, newest first (books removed from it left out). */
    fun all(s: Snapshot): List<Item> {
        val docs = (s.cpClips + s.paperClips + s.kindleClips + s.libbyClips + s.moonClips + s.historyClips + s.koClips).map { it.document }.toSet().filter { it in s.books }
        return docs.flatMap { forBook(s, it) }.sortedByDescending { it.clip.createdAt }
    }

    /**
     * One highlight for the day: the same all day, a different one tomorrow, and not the newest
     * (you've just read that) when there's more than a handful to pick from.
     */
    fun ofTheDay(items: List<Item>, day: java.time.LocalDate, shuffle: Int = 0): Item? {
        if (items.isEmpty()) return null
        val pool = items.filter { it.clip.text.length in 40..420 }.ifEmpty { items }
            .sortedBy { it.clip.id }
        val seed = day.toEpochDay() * 31 + shuffle * 7919L
        val i = (((seed % pool.size) + pool.size) % pool.size).toInt()
        return pool[i]
    }

    /** Highlights whose text, note, chapter, book title or author has every word of [query]. */
    fun search(s: Snapshot, items: List<Item>, query: String): List<Item> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return items
        return items.filter { it ->
            val b = s.books[it.clip.document]
            val hay = listOfNotNull(it.clip.text, it.clip.note, it.clip.chapter, b?.displayTitle, b?.author).joinToString(" ").lowercase()
            words.all { w -> hay.contains(w) }
        }
    }

    /**
     * New text and note. A paper highlight is changed itself; a CrossPoint one keeps its original on
     * crosspoint-sync and your version is what's shown and sent from here.
     */
    fun edit(store: Store, clipId: String, text: String, note: String?) {
        val t = text.trim()
        if (t.isEmpty()) throw ServiceException("A highlight needs its text")
        val n = note?.trim()?.ifEmpty { null }
        store.update { s ->
            if (s.paperClips.any { it.id == clipId }) {
                s.copy(paperClips = s.paperClips.map { if (it.id == clipId) it.copy(text = t, note = n) else it })
            } else {
                val original = s.cpClips.firstOrNull { it.id == clipId } ?: throw ServiceException("That highlight is gone")
                val edits = if (original.text == t && original.note == n) s.clipEdits - clipId else s.clipEdits + (clipId to ClipEdit(t, n))
                s.copy(clipEdits = edits)
            }
        }
    }

    /** Back to CrossPoint's own text. */
    fun revert(store: Store, clipId: String) = store.update { s -> s.copy(clipEdits = s.clipEdits - clipId) }

    /**
     * Sends the highlight again to [trackers] on the next sync: it replaces the earlier entry
     * where the tracker allows (Seekquel, Hardcover, Pagebound), else it's posted again.
     */
    fun resend(store: Store, clipId: String, trackers: Collection<String>) = store.update { s ->
        s.copy(resend = s.resend + trackers.map { "$it|$clipId" })
    }

    /** Trackers where this highlight went as its own entry and can be deleted. */
    fun deletable(s: Snapshot, clipId: String): List<String> =
        Trackers.all.filter { it.deletesHighlights && s.clipRemote["${it.id}|$clipId"] != null }.map { it.id }

    /**
     * Deletes a highlight here. A CrossPoint one is hidden from now on (the caller deletes it on
     * crosspoint-sync too when asked); with [fromTrackers], its entries are deleted on the next
     * sync where the tracker allows. It's never sent again either way.
     */
    fun delete(store: Store, clipId: String, fromTrackers: Boolean) = store.update { s ->
        val remote = if (fromTrackers) deletable(s, clipId).mapNotNull { svc -> s.clipRemote["$svc|$clipId"]?.let { "$svc|$it" } } else emptyList()
        val isPaper = s.paperClips.any { it.id == clipId }
        s.copy(
            paperClips = s.paperClips.filter { it.id != clipId },
            cpClips = s.cpClips.filter { it.id != clipId },
            clipHidden = if (isPaper) s.clipHidden else s.clipHidden + clipId,
            clipEdits = s.clipEdits - clipId,
            resend = s.resend.filter { !it.endsWith("|$clipId") }.toSet(),
            clipRemote = s.clipRemote.filterKeys { !it.endsWith("|$clipId") },
            clipDeletes = s.clipDeletes + remote,
        )
    }


    /** crosspoint-sync now has [note] for this highlight: its original here follows. */
    fun originalNote(store: Store, clipId: String, note: String?) = store.update { s ->
        s.copy(cpClips = s.cpClips.map { if (it.id == clipId) it.copy(note = note) else it })
    }

    /** Formats a book's highlights and notes as clean Markdown for export or sharing. */
    fun toMarkdown(book: io.github.prithirajn.readync.data.Book, items: List<Item>): String = buildString {
        appendLine("# ${book.displayTitle}")
        book.author?.let { appendLine("By $it") }
        appendLine()
        for (item in items) {
            appendLine("> ${io.github.prithirajn.readync.trackers.quoted(item.clip.text)}")
            val where = listOfNotNull(
                item.clip.chapter?.trim()?.takeIf { it.isNotEmpty() },
                HighlightPosition.label(item.clip),
            ).joinToString(" · ")
            if (where.isNotEmpty()) appendLine("— $where")
            item.clip.note?.trim()?.takeIf { it.isNotEmpty() }?.let {
                appendLine()
                appendLine("**Note:** $it")
            }
            appendLine()
        }
    }
}
