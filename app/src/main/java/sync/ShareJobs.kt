package io.github.prithirajn.readync.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.prithirajn.readync.ReadyncApp
import io.github.prithirajn.readync.source.BookPrices
import io.github.prithirajn.readync.source.PostBooks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Links shared to the app (or pasted), worked on in the background so leaving the app doesn't stop
 * them: a social post's books found, or a bookshop's page tracked for its price. Each ends with a
 * notification saying what came of it; tapping it opens Catalogs › Posts or › Deals.
 */
object ShareJobs {
    /** What's being worked on now ("post:<link>" or "price:<link>") and how far it's got. */
    val running = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Set when a post's books were saved: its link and when, for the Posts view to reload and mark it. */
    val lastPost = MutableStateFlow<Pair<String, Long>?>(null)

    /** A post that couldn't be read: its link and why. */
    val postError = MutableStateFlow<Pair<String, String>?>(null)

    /** The last price link worked on: what happened, for the Deals view. */
    val lastPrice = MutableStateFlow<BookPrices.Tracked?>(null)

    fun findPost(context: Context, url: String) = enqueue(context, "post", url, null)

    fun trackPrice(context: Context, url: String, target: Double? = null) = enqueue(context, "price", url, target)

    private fun enqueue(context: Context, kind: String, url: String, target: Double?) {
        val key = "$kind:$url"
        running.update { if (key in it) it else it + (key to "Waiting for the network") }
        val req = OneTimeWorkRequestBuilder<ShareWorker>()
            .setInputData(workDataOf("kind" to kind, "url" to url, "target" to (target ?: -1.0)))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag("shared-link")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("share-$kind-" + Integer.toHexString(url.hashCode()), ExistingWorkPolicy.KEEP, req)
    }

    /** Stops the work on a link ("post:<link>" or "price:<link>"). */
    fun stop(context: Context, key: String) {
        val kind = key.substringBefore(':')
        val url = key.substringAfter(':')
        WorkManager.getInstance(context).cancelUniqueWork("share-$kind-" + Integer.toHexString(url.hashCode()))
        running.update { it - key }
    }

    internal fun progress(key: String, text: String) = running.update { it + (key to text) }
}

/** One shared link's work (see [ShareJobs]). */
class ShareWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val kind = inputData.getString("kind") ?: return Result.success()
        val url = inputData.getString("url") ?: return Result.success()
        val key = "$kind:$url"
        val app = applicationContext as ReadyncApp
        try {
            when (kind) {
                "post" -> post(app, key, url)
                "price" -> price(app, key, url, inputData.getDouble("target", -1.0).takeIf { it >= 0 })
            }
        } finally {
            ShareJobs.running.update { it - key }
            app.store.flush()
        }
        return Result.success()
    }

    private suspend fun post(app: ReadyncApp, key: String, url: String) {
        ShareJobs.progress(key, "Reading the post")
        val r = runCatching { PostBooks.find(app, url) { ShareJobs.progress(key, it) } }
        r.onSuccess { t ->
            synchronized(PostBooks) {
                PostBooks.save(app, listOf(t) + PostBooks.saved(app).filter { it.post.url != t.post.url })
            }
            ShareJobs.lastPost.value = t.post.url to t.at
            runCatching { Notices.postBooks(app, t) }
        }.onFailure { e ->
            val why = e.message ?: "Couldn't read that post"
            ShareJobs.postError.value = url to why
            runCatching { Notices.sharedFailed(app, "Couldn't read that post", why, deals = false, id = url) }
        }
    }

    private fun price(app: ReadyncApp, key: String, url: String, target: Double?) {
        ShareJobs.progress(key, "Reading the shop's page")
        val r = BookPrices.track(app.store, url, target)
        ShareJobs.lastPrice.value = r
        runCatching {
            if (r.ok) Notices.priceTracked(app, app.store, r)
            else Notices.sharedFailed(app, "Couldn't track that link", r.message, deals = true, id = url)
        }
    }
}
