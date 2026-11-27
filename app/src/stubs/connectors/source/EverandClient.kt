package io.github.prithirajn.readync.source

import io.github.prithirajn.readync.data.EverandAccount
import io.github.prithirajn.readync.data.ReadStatus
import io.github.prithirajn.readync.net.ServiceException

/**
 * This edition doesn't include Everand: the same shape as the full app's client so the rest of the
 * app builds, with nothing behind it. The app never offers Everand here, so none of this is reached.
 */
class EverandClient(@Suppress("unused") private val account: EverandAccount) {
    data class Title(
        val id: String, val title: String, val author: String?, val kind: String, val percent: Double,
        val finished: Boolean, val coverUrl: String?, val url: String?, val highlights: List<Highlight> = emptyList(),
    ) {
        val audio: Boolean get() = kind == "audiobook"
        val status: ReadStatus get() = ReadStatus.TO_READ
    }
    data class Library(val titles: List<Title>)
    data class Highlight(val id: String, val text: String, val note: String?, val page: Int?, val createdAt: Long)

    fun library(): Library = throw ServiceException(NOT_HERE)

    companion object {
        const val NOT_HERE = "Everand isn't part of this edition"
        const val BASE = ""
        const val SIGN_IN_URL = ""
        fun connect(@Suppress("UNUSED_PARAMETER") cookies: String): EverandAccount = throw ServiceException(NOT_HERE)
    }
}
