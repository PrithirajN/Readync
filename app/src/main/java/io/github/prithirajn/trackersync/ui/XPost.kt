package io.github.prithirajn.trackersync.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Posting to X by hand: X's composer opens with the pictures (up to four, X's limit for one post)
 * or the video attached and the words filled in, ready for you to post. Nothing goes up on its own.
 * The words are on the clipboard too, for when X drops them. Without the X app, X opens in the
 * browser with the words (a picture can't go that way).
 */
object XPost {
    /** The X app's package on Android (still Twitter's). */
    private const val X = "com.twitter.android"

    /** Pictures X takes in one post. */
    const val MAX_PICTURES = 4

    /** X's length for a post: longer words are cut at a word, with an ellipsis. */
    fun fit(text: String, max: Int = 280): String {
        val t = text.trim()
        if (t.length <= max) return t
        val cut = t.take(max - 1)
        val sp = cut.lastIndexOf(' ')
        return (if (sp > max / 2) cut.take(sp) else cut).trimEnd(',', ';', ':', ' ') + "…"
    }

    private fun clip(context: Context, text: String) = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Post", text))
    }

    private fun web(context: Context, text: String): String? {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://x.com/intent/post?text=" + java.net.URLEncoder.encode(text, "UTF-8")))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (runCatching { context.startActivity(i) }.isSuccess) "web" else null
    }

    /** Opens X with [pictures] (the first four) and [text]. Returns "app", "web" or null when neither opened. */
    fun pictures(context: Context, pictures: List<Bitmap>, text: String): String? {
        val words = fit(text)
        clip(context, words)
        val uris = pictures.take(MAX_PICTURES).map { QuoteCard.cacheUri(context, it) }
        if (uris.isEmpty()) return web(context, words)
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uris[0])
        } else Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.apply {
            type = "image/png"
            setPackage(X)
            putExtra(Intent.EXTRA_TEXT, words)
            clipData = ClipData.newRawUri("Post", uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(send) }.isSuccess) return "app"
        return web(context, words)
    }

    /** Opens X with the video [file] and [text]. Returns "app", "web" or null. */
    fun video(context: Context, file: File, text: String): String? {
        val words = fit(text)
        clip(context, words)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".pages", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            setPackage(X)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, words)
            clipData = ClipData.newRawUri("Post", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(send) }.isSuccess) return "app"
        return web(context, words)
    }

    /** What to tell you after [how] (from [pictures] or [video]). */
    fun said(how: String?, what: String = "the picture"): String = when (how) {
        "app" -> "Opened in X with $what. The words are copied too, if you need to paste them."
        "web" -> "X isn't installed, so it opened in the browser with the words (save $what and add it there)."
        else -> "Couldn't open X."
    }
}
