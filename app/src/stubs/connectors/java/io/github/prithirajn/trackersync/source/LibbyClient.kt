package io.github.prithirajn.trackersync.source

import io.github.prithirajn.trackersync.data.LibbyAccount
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.net.ServiceException

/**
 * This edition doesn't include Libby: the same shape as the full app's client so the rest of the
 * app builds, with nothing behind it. The app never offers Libby here, so none of this is reached.
 */
class LibbyClient(@Suppress("unused") private val account: LibbyAccount) {
    data class CardInfo(val cardId: String, val websiteId: String?, val advantageKey: String?, val libraryName: String?)
    data class PossessionData(
        val percentage: Double?, val positionTimestamp: Long?, val createdTimestamp: Long?, val updatedTimestamp: Long?,
        val readingTimeSeconds: Long?, val highlights: List<Clipping>,
    )
    data class LibbyBookItem(
        val titleId: String, val cardId: String, val websiteId: String?, val libraryKey: String?, val format: String,
        val title: String, val author: String?, val isbn: String?, val asin: String?, val coverUrl: String?, val totalPages: Int?,
        val percentage: Double?, val status: ReadStatus, val startedAt: Long?, val finishedAt: Long?, val updatedAt: Long?,
        val readingTimeSeconds: Long?, val highlights: List<Clipping>, val isActiveLoan: Boolean,
    )
    data class LibraryResult(val account: LibbyAccount, val books: List<LibbyBookItem>)
    data class SetupCodeSession(val chip: String, val identity: String, val code: String, val expirySeconds: Int)

    fun fetchLibrary(@Suppress("UNUSED_PARAMETER") knownTitleIds: Set<String> = emptySet()): LibraryResult = throw ServiceException(NOT_HERE)

    companion object {
        const val NOT_HERE = "Libby isn't part of this edition"
        fun acquireChip(@Suppress("UNUSED_PARAMETER") existingIdentity: String? = null, @Suppress("UNUSED_PARAMETER") existingChip: String? = null): Pair<String, String> = throw ServiceException(NOT_HERE)
        fun startSetupCode(): SetupCodeSession = throw ServiceException(NOT_HERE)
        fun pollSetupCode(@Suppress("UNUSED_PARAMETER") session: SetupCodeSession): LibbyAccount? = throw ServiceException(NOT_HERE)
        fun connectWithSetupCode(@Suppress("UNUSED_PARAMETER") rawCode: String): LibbyAccount = throw ServiceException(NOT_HERE)
        fun connectWithToken(@Suppress("UNUSED_PARAMETER") rawInput: String, @Suppress("UNUSED_PARAMETER") chipHint: String? = null): LibbyAccount = throw ServiceException(NOT_HERE)
    }
}
