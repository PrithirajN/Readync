package io.github.prithirajn.readync.sync

import io.github.prithirajn.readync.data.Snapshot
import io.github.prithirajn.readync.source.EpubText

/** Which book in your library an EPUB is: by its KOReader document ID (the very file), else by its title and author when only one book fits. */
object BookFileMatch {
    fun match(s: Snapshot, bytes: ByteArray): String? {
        val id = EpubText.partialMd5(bytes.size.toLong()) { o, n -> if (o >= bytes.size) null else bytes.copyOfRange(o.toInt(), minOf(bytes.size, o.toInt() + n)) }
        s.books[id]?.let { return it.document }
        s.books.values.firstOrNull { id in it.aliases }?.let { return it.document }
        s.kosyncIds.entries.firstOrNull { id in it.value }?.let { e -> if (s.books.containsKey(e.key)) return e.key }
        val info = runCatching { EpubText.info(bytes) }.getOrNull() ?: return null
        val title = info.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val hits = s.books.values.filter { b -> b.title != null && !b.held && Matcher.sameBook(title, info.creator, b.title, b.author) }
        // Your e-reader's copy first; only a single clear match counts.
        val reader = hits.filter { it.origin == null }
        return (reader.singleOrNull() ?: hits.singleOrNull())?.document
    }
}
