package io.github.prithirajn.trackersync.source

import io.github.prithirajn.trackersync.data.AudibleAccount
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.net.ServiceException

/**
 * This edition doesn't include Audible: the same shape as the full app's client so the rest of the
 * app builds, with nothing behind it. The app never offers Audible here, so none of this is reached.
 */
class AudibleClient(@Suppress("unused") private val account: AudibleAccount) {
    data class Title(
        val asin: String, val title: String, val author: String?, val narrator: String?, val percent: Double, val finished: Boolean,
        val finishedAt: Long?, val coverUrl: String?, val isbn: String?, val minutes: Int?, val series: String?, val seriesNumber: String?,
        val description: String?,
    ) {
        val status: ReadStatus get() = if (finished || percent >= 0.98) ReadStatus.FINISHED else ReadStatus.READING
    }
    data class Library(val account: AudibleAccount, val titles: List<Title>)

    fun library(@Suppress("UNUSED_PARAMETER") known: Set<String> = emptySet()): Library = throw ServiceException(NOT_HERE)
    fun deregister() {}

    companion object {
        const val NOT_HERE = "Audible isn't part of this edition"
        data class Store(val name: String, val country: String, val domain: String, val marketplace: String)
        val STORES = emptyList<Store>()
        data class SignIn(val store: Store, val url: String, val verifier: String, val serial: String)
        fun startSignIn(@Suppress("UNUSED_PARAMETER") store: Store): SignIn = throw ServiceException(NOT_HERE)
        fun codeFrom(@Suppress("UNUSED_PARAMETER") url: String): String? = null
        fun finishSignIn(@Suppress("UNUSED_PARAMETER") s: SignIn, @Suppress("UNUSED_PARAMETER") code: String): AudibleAccount = throw ServiceException(NOT_HERE)
    }
}
