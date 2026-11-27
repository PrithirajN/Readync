package io.github.prithirajn.readync.source

import io.github.prithirajn.readync.data.HooplaAccount
import io.github.prithirajn.readync.data.ReadStatus
import io.github.prithirajn.readync.net.ServiceException

/**
 * This edition doesn't include hoopla: the same shape as the full app's client so the rest of the
 * app builds, with nothing behind it. The app never offers hoopla here, so none of this is reached.
 */
class HooplaClient(@Suppress("unused") private val account: HooplaAccount) {
    data class Highlight(val id: String, val text: String, val note: String?, val page: Int?, val createdAt: Long)
    data class Title(
        val titleId: String, val title: String, val author: String?, val kind: String, val percent: Double?,
        val borrowedAt: Long?, val coverUrl: String?, val highlights: List<Highlight>,
    ) {
        val audio: Boolean get() = kind == "AUDIOBOOK"
        val status: ReadStatus get() = ReadStatus.TO_READ
    }
    data class Library(val titles: List<Title>)

    fun library(@Suppress("UNUSED_PARAMETER") highlightsFor: Int = 20): Library = throw ServiceException(NOT_HERE)

    companion object {
        const val NOT_HERE = "hoopla isn't part of this edition"
        const val SIGN_IN_URL = ""
        const val HISTORY_URL = ""
        fun tokenFrom(@Suppress("UNUSED_PARAMETER") raw: String): String? = null
        fun tokenFromCookies(@Suppress("UNUSED_PARAMETER") cookies: String?): String? = null
        fun connect(@Suppress("UNUSED_PARAMETER") rawToken: String): HooplaAccount = throw ServiceException(NOT_HERE)
    }
}
