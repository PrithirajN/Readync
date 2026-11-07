package io.github.prithirajn.trackersync.ui

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Things asked of the app from outside a screen: a shortcut, a notification, a shared link,
 * the Quick Settings tile. MainActivity sets one; the app picks it up and clears it.
 */
sealed interface AppAction {
    data class Rate(val document: String) : AppAction
    data object Services : AppAction
    data object AddBook : AppAction
    data class AddFromShare(val query: String) : AppAction
    data object Snap : AppAction
    data object Sync : AppAction
    /** Open a book's page in the Library (from the Highlights tab). */
    data class OpenBook(val document: String) : AppAction
    /** New & upcoming, on the Activity tab (from its notification). */
    data object Releases : AppAction
    /** The SFF tab, opened at a story ([storyId]) when there is one (from its notification). */
    data class Sff(val storyId: String? = null) : AppAction
}

object AppActions {
    val pending = MutableStateFlow<AppAction?>(null)
}
