package io.github.prithirajn.trackersync.sync

import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.ReadStatus
import io.github.prithirajn.trackersync.data.Store
import io.github.prithirajn.trackersync.net.ServiceException

/** The finish flow: rate and review a book once; it goes to every tracker that takes it. */
object FinishFlow {
    /** Finished books from the last two weeks that haven't been offered the flow yet. */
    const val RECENT_SECONDS = 14 * 86_400L

    fun toRate(books: Collection<Book>, nowSeconds: Long = System.currentTimeMillis() / 1000): List<Book> =
        books.filter {
            it.status == ReadStatus.FINISHED && !it.reviewAsked && !it.held && it.rating == null &&
                nowSeconds - (it.finishedAt ?: it.timestamp) <= RECENT_SECONDS
        }.sortedByDescending { it.finishedAt ?: it.timestamp }

    /**
     * Saves the rating (0.5–5, half stars; null for none) and review. Trackers that refused an
     * earlier version get another try, since it changed.
     */
    fun save(store: Store, document: String, rating: Double?, review: String?) {
        store.update { s ->
            val b = s.books[document] ?: throw ServiceException("That book isn't in your library any more")
            // A rating you didn't change stays exact (Seekquel's 4.3 isn't turned into 4.5 by adding a review).
            // Tenths, like Seekquel; a rating you didn't change is kept exactly as it was.
            val r = if (rating != null && rating == b.rating) rating else rating?.let { Math.round(it * 10) / 10.0 }
            if (r != null && (r < 0.1 || r > 5.0)) throw ServiceException("Ratings go from 0.1 to 5 stars")
            s.copy(books = s.books + (document to b.copy(rating = r, review = review?.trim()?.ifEmpty { null }, reviewAsked = true)))
        }
    }

    /** "Not now": stop offering the flow for this book. It can still be rated from its page. */
    fun dismiss(store: Store, document: String) = store.update { s ->
        val b = s.books[document] ?: return@update s
        s.copy(books = s.books + (document to b.copy(reviewAsked = true)))
    }
}
