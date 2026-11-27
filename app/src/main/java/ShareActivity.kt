package io.github.prithirajn.readync

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Where shares from other apps land: invisible, in the app you shared from, never in recent apps.
 * A book post (TikTok, Instagram, Threads, YouTube) or a bookshop's page is handled in the
 * background with a notification when done, and this closes at once, so you're straight back
 * where you were (Back included). Anything that needs the app (a book link to add, screenshots or a
 * screen recording) is handed on to the one Readync window.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { route(intent) }.onFailure { open(intent) }
        finish()
        // No animation in or out: it's never seen.
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    private fun said(text: String) {
        runCatching { android.widget.Toast.makeText(applicationContext, text, android.widget.Toast.LENGTH_LONG).show() }
    }

    private fun route(intent: Intent?) {
        intent ?: return
        val isMedia = intent.action == Intent.ACTION_SEND_MULTIPLE || intent.type?.let { it.startsWith("image/") || it.startsWith("video/") } == true
        if (isMedia) { open(intent); return }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: runCatching { intent.clipData?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()
        val link = text?.let { io.github.prithirajn.readync.source.PostBooks.linkIn(it) }
        val platform = link?.let { io.github.prithirajn.readync.source.PostBooks.platformOf(it) }
        when {
            link != null && platform != null -> {
                io.github.prithirajn.readync.sync.ShareJobs.findPost(applicationContext, link)
                said("Looking for books in that $platform post… you'll get a notification")
            }
            link != null && io.github.prithirajn.readync.source.BookPrices.trackable(link) != null -> {
                io.github.prithirajn.readync.sync.ShareJobs.trackPrice(applicationContext, link)
                said("Tracking that book's price… you'll get a notification")
            }
            else -> open(intent)
        }
    }

    /** The share passed on to the one Readync window (its own task), with leave to read what was shared. */
    private fun open(intent: Intent?) {
        intent ?: return
        val fwd = Intent(intent).setClass(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(fwd) }
    }
}
