package io.github.prithirajn.trackersync.source

import io.github.prithirajn.trackersync.data.KindleAccount
import io.github.prithirajn.trackersync.net.ServiceException
import io.github.prithirajn.trackersync.trackers.LibraryBook

/**
 * This edition doesn't include Kindle: the same shape as the full app's client so the rest of the
 * app builds, with nothing behind it. The app never offers Kindle here, so none of this is reached.
 */
class KindleClient(@Suppress("unused") private val account: KindleAccount) {
    val notebookStamps = HashMap<String, String>()
    val lengths = HashMap<String, Int>()

    fun library(): List<LibraryBook> = throw ServiceException(NOT_HERE)
    fun progress(@Suppress("UNUSED_PARAMETER") asin: String): Double? = null
    fun clippings(@Suppress("UNUSED_PARAMETER") asin: String, @Suppress("UNUSED_PARAMETER") document: String, @Suppress("UNUSED_PARAMETER") locations: Int? = null, @Suppress("UNUSED_PARAMETER") pageCount: Int? = null): List<Clipping>? = null

    companion object {
        const val NOT_HERE = "Kindle isn't part of this edition"
        const val FINISHED_AT = 0.98
        const val LIVE_CHECKS = 4
        const val MAX_LIBRARY_PAGES = 10
        const val MAX_HIGHLIGHT_PAGES = 20
        const val POSITIONS_PER_LOCATION = 150.0
        fun stableId(location: String?, text: String): String {
            val md = java.security.MessageDigest.getInstance("SHA-1")
            val bytes = md.digest(((location ?: "") + "|" + text.trim()).toByteArray(Charsets.UTF_8))
            return bytes.take(8).joinToString("") { "%02x".format(it) }
        }
        const val DEFAULT_DOMAIN = "read.amazon.in"
        val REGIONAL_DOMAINS = emptyList<Pair<String, String>>()
    }
}
