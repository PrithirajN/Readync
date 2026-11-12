package io.github.prithirajn.trackersync.source

import android.content.Context
import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.sync.Matcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The app's own copies of books (EPUBs found on your OPDS catalogs), kept in its private storage
 * and never shown in Downloads or to other apps. They're read for the words around a passage: the
 * real text above and below a quote in Quote Studio's book photo, and Fable's book-photo quotes.
 *
 * Books you're reading get a copy on their own (one a sync, each tried at most every few days)
 * while "Keep copies of books you're reading" is on; any book can be given one from its ⋯ menu.
 */
object BookCopies {

    data class Copy(
        val document: String,
        val file: String,
        val server: String,
        val title: String,
        val size: Long,
        val at: Long,
    )

    /** Why a copy couldn't be had. */
    class CopyException(message: String) : Exception(message)

    private const val PREFS = "bookcopies"
    private const val RETRY_SEC = 3 * 24 * 3600L

    @Volatile var appContext: Context? = null

    private val _copies = MutableStateFlow<Map<String, Copy>>(emptyMap())
    val copies: StateFlow<Map<String, Copy>> = _copies
    private var loaded = false

    /** Books being fetched right now (by document), for the menus to say so. */
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy

    fun install(context: Context) {
        appContext = context.applicationContext
        load(context)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun dir(context: Context): File = File(context.filesDir, "book-copies").apply { mkdirs() }

    @Synchronized
    fun load(context: Context): Map<String, Copy> {
        if (loaded) return _copies.value
        val raw = prefs(context).getString("copies", null)
        val out = LinkedHashMap<String, Copy>()
        runCatching {
            val a = JSONArray(raw ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val c = Copy(o.getString("document"), o.getString("file"), o.optString("server"), o.optString("title"), o.optLong("size"), o.optLong("at"))
                if (File(dir(context), c.file).isFile) out[c.document] = c
            }
        }
        _copies.value = out
        loaded = true
        return out
    }

    @Synchronized
    private fun save(context: Context, all: Map<String, Copy>) {
        val a = JSONArray()
        for (c in all.values) a.put(JSONObject().put("document", c.document).put("file", c.file).put("server", c.server).put("title", c.title).put("size", c.size).put("at", c.at))
        prefs(context).edit().putString("copies", a.toString()).apply()
        _copies.value = all
    }

    /** Whether books you're reading get a copy on their own. On unless turned off. */
    fun auto(context: Context): Boolean = prefs(context).getBoolean("auto", true)

    fun setAuto(context: Context, on: Boolean) { prefs(context).edit().putBoolean("auto", on).apply() }

    fun get(context: Context, document: String): Copy? = load(context)[document]

    fun has(context: Context, document: String): Boolean = get(context, document) != null

    fun totalSize(context: Context): Long = load(context).values.sumOf { it.size }

    fun remove(context: Context, document: String) {
        val all = LinkedHashMap(load(context))
        val c = all.remove(document) ?: return
        File(dir(context), c.file).delete()
        synchronized(opened) { opened.remove(document) }
        save(context, all)
    }

    fun removeAll(context: Context) {
        for (d in load(context).keys.toList()) remove(context, d)
    }

    // ---- reading a copy ----

    private val opened = object : LinkedHashMap<String, EpubText.Book>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EpubText.Book>?) = size > 2
    }

    /** The copy of [document], opened (the last two are kept open). */
    fun open(context: Context, document: String): EpubText.Book? {
        synchronized(opened) { opened[document]?.let { return it } }
        val c = get(context, document) ?: return null
        val bytes = runCatching { File(dir(context), c.file).readBytes() }.getOrNull() ?: return null
        val book = EpubText.open(bytes) ?: return null
        synchronized(opened) { opened[document] = book }
        return book
    }

    /**
     * The text around [quote] in [document]'s copy: about [chars] characters before it, the quote,
     * and as much after, cut at sentence ends so it starts and stops cleanly. Null with no copy, or
     * when the passage isn't found exactly once. Blocking.
     */
    fun around(context: Context, document: String, quote: String, spine: Int? = null, chars: Int = 900): String? {
        val q = quote.trim()
        if (q.length < 12) return null
        val book = open(context, document) ?: return null
        val place = runCatching { EpubText.find(book, q, spine?.takeIf { it >= 0 }) }.getOrNull() ?: return null
        val ch = book.chapter(place.spine) ?: return null
        val beforeRaw = ch.textOf((place.start - chars).coerceAtLeast(0), place.start)
        val afterRaw = ch.textOf(place.end, (place.end + chars).coerceAtMost(ch.length))
        val middle = ch.textOf(place.start, place.end)
        // Start after a sentence end, stop at one.
        val before = if (place.start - chars > 0) {
            val cut = Regex("[.!?…][\"”’)]?\\s+").findAll(beforeRaw).firstOrNull()?.range?.last
            if (cut != null && cut < beforeRaw.length - 40) beforeRaw.substring(cut + 1) else beforeRaw.substringAfter(' ')
        } else beforeRaw
        val after = if (place.end + chars < ch.length) {
            val cut = Regex("[.!?…][\"”’)]?(?=\\s)").findAll(afterRaw).lastOrNull()?.range?.last
            if (cut != null && cut > 40) afterRaw.substring(0, cut + 1) else afterRaw.substringBeforeLast(' ')
        } else afterRaw
        fun tidy(s: String) = s.replace(Regex("\\s+"), " ")
        return (tidy(before) + tidy(middle) + tidy(after)).trim().takeIf { it.length > q.length + 20 }
    }

    // ---- finding and fetching a copy ----

    data class Found(val server: Opds.Server, val entry: Opds.Entry, val acquisition: Opds.Acquisition)

    private val OK_REL = Regex("(?i)acquisition(/open-access)?$|acquisition$")
    private val NOT_REL = Regex("(?i)buy|borrow|sample|subscribe|preview")

    /** The EPUB to get from [e], when it has one that's free to take. */
    fun epubOf(e: Opds.Entry): Opds.Acquisition? = e.acquisitions
        .filter { a -> (a.format == "EPUB" || a.format == "KEPUB") && (a.rel == null || (OK_REL.containsMatchIn(a.rel) && !NOT_REL.containsMatchIn(a.rel))) }
        .sortedBy { if (it.format == "EPUB") 0 else 1 }
        .firstOrNull()

    /** Whether catalog entry [e] is [book]: the same title, and an author in common when both say. */
    fun same(book: Book, e: Opds.Entry): Boolean {
        val want = Matcher.baseTitle(book.displayTitle)
        val got = Matcher.baseTitle(e.title)
        if (want.isEmpty() || got.isEmpty()) return false
        if (want != got && !(got.startsWith("$want ") && want.length >= 8)) return false
        val author = book.author?.takeIf { it.isNotBlank() } ?: return true
        if (e.authors.isEmpty()) return true
        val mine = surnames(author)
        return e.authors.any { a -> surnames(a).any { it in mine } }
    }

    private fun surnames(names: String): Set<String> =
        names.split(Regex("\\s*(?:,|&|\\band\\b|;)\\s*")).mapNotNull { n ->
            Matcher.normalizeText(n).split(' ').filter { it.length >= 2 }.lastOrNull()
        }.toSet() + Matcher.normalizeText(names).split(' ').filter { it.length >= 4 }

    /** Looks for [book] as an EPUB on every saved catalog at once; the first server in your list that has it wins. Blocking. */
    fun find(context: Context, book: Book): Found? {
        val servers = Opds.load(context)
        if (servers.isEmpty()) throw CopyException("Add an OPDS catalog first (Catalogs tab)")
        val query = book.displayTitle.substringBefore(':').trim().ifEmpty { book.displayTitle }
        val pool = Executors.newFixedThreadPool(minOf(4, servers.size))
        try {
            val tasks = servers.map { srv ->
                pool.submit(Callable {
                    runCatching { Opds.search(context, srv, query) }.getOrDefault(emptyList())
                        .firstNotNullOfOrNull { e -> if (same(book, e)) epubOf(e)?.let { Found(srv, e, it) } else null }
                })
            }
            for (t in tasks) {
                val got = runCatching { t.get(45, TimeUnit.SECONDS) }.getOrNull()
                if (got != null) return got
            }
            return null
        } finally {
            pool.shutdownNow()
        }
    }

    /** Fetches [found] into the app's storage as [book]'s copy. Blocking. */
    fun fetch(context: Context, book: Book, found: Found, cancel: AtomicBoolean = AtomicBoolean(false), progress: (Opds.Progress) -> Unit = {}): Copy {
        val name = "copy-" + Integer.toHexString(book.document.hashCode()) + "-" + System.currentTimeMillis() / 1000 + ".epub"
        val file = File(dir(context), name)
        val size = Opds.downloadTo(found.server, found.acquisition, file, cancel, progress)
        // Only a real EPUB is kept.
        val bytes = file.readBytes()
        if (EpubText.open(bytes) == null) { file.delete(); throw CopyException("${found.server.name}'s file isn't a readable EPUB") }
        val all = LinkedHashMap(load(context))
        all[book.document]?.let { old -> File(dir(context), old.file).delete() }
        val c = Copy(book.document, name, found.server.name, found.entry.title, size, System.currentTimeMillis() / 1000)
        all[book.document] = c
        synchronized(opened) { opened.remove(book.document) }
        save(context, all)
        return c
    }

    /** Finds [book] on your catalogs and keeps a copy. Blocking. */
    fun get(context: Context, book: Book, cancel: AtomicBoolean = AtomicBoolean(false), progress: (Opds.Progress) -> Unit = {}): Copy {
        _busy.value = _busy.value + book.document
        try {
            noteTried(context, book.document)
            progress(Opds.Progress("Looking on your catalogs"))
            val found = find(context, book) ?: throw CopyException("None of your catalogs has it as an EPUB")
            return fetch(context, book, found, cancel, progress)
        } finally {
            _busy.value = _busy.value - book.document
        }
    }

    private fun noteTried(context: Context, document: String) {
        prefs(context).edit().putLong("tried:$document", System.currentTimeMillis() / 1000).apply()
    }

    private fun triedRecently(context: Context, document: String): Boolean =
        System.currentTimeMillis() / 1000 - prefs(context).getLong("tried:$document", 0) < RETRY_SEC

    /**
     * For a sync: one book you're reading without a copy (not tried in the last few days) is looked
     * for and kept. Does nothing when it's turned off or there are no catalogs.
     */
    fun refresh(books: Collection<Book>) {
        val context = appContext ?: return
        if (!auto(context) || Opds.load(context).isEmpty()) return
        val have = load(context)
        val next = books
            .filter { it.status == io.github.prithirajn.trackersync.data.ReadStatus.READING && it.document !in have && !it.isMangaOrComic }
            .filter { !triedRecently(context, it.document) }
            .maxByOrNull { it.timestamp }
            ?: return
        runCatching { get(context, next) }
    }
}
