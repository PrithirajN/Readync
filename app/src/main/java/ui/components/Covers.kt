package io.github.prithirajn.readync.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.prithirajn.readync.net.Http
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.Lora
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * The accent colour of a cover, for the app's theme. Remembered per cover, and the last one
 * kept on the phone so the app opens in that colour instead of flashing green first.
 */
object CoverAccent {
    private val seeds = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val none = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** The colour the app last used, for the first frame. */
    fun last(context: Context): Int? {
        val prefs = context.getSharedPreferences("theme", Context.MODE_PRIVATE)
        return if (prefs.contains("seed")) prefs.getInt("seed", 0) else null
    }

    /** The cover whose colour the app wears: the book with a cover you last made progress in. */
    fun latestCover(books: Collection<io.github.prithirajn.readync.data.Book>): String? =
        books.filter { it.coverUrl != null }.maxByOrNull { it.timestamp }?.coverUrl

    /**
     * For the widget: the colour of a cover already on the phone, else the last colour the app
     * used. Never downloads, since a widget update can't wait.
     */
    fun forWidget(context: Context, url: String?): Int? {
        if (url != null) {
            seeds[url]?.let { return it }
            if (url !in none) {
                runCatching { CoverCache.loadCached(context, url, 160) }.getOrNull()?.let { bmp -> return pick(context, url, bmp) }
            }
        }
        return last(context)
    }

    /** Blocking: loads the cover (from the phone when cached) and picks its colour. */
    fun of(context: Context, url: String): Int? {
        seeds[url]?.let { return it }
        if (url in none) return null
        val bmp = runCatching { CoverCache.load(context, url, 160) }.getOrNull() ?: return null
        val before = last(context)
        val seed = pick(context, url, bmp)
        // The widget wears the same colour: redraw it when the colour changes.
        if (seed != before) io.github.prithirajn.trackersync.widget.Widgets.refreshAll(context)
        return seed
    }

    private fun pick(context: Context, url: String, bmp: Bitmap): Int? {
        val small = Bitmap.createScaledBitmap(bmp, 32, 48, true)
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        if (small !== bmp) small.recycle()
        val seed = io.github.prithirajn.readync.util.Palette.vibrant(px)
        if (seed == null) {
            none.add(url)
        } else {
            seeds[url] = seed
        }
        val prefs = context.getSharedPreferences("theme", Context.MODE_PRIVATE).edit()
        if (seed == null) prefs.remove("seed") else prefs.putInt("seed", seed)
        prefs.apply()
        return seed
    }
}

/** Cover images: a small memory cache in front of a disk cache in the app's cache folder. */
object CoverCache {
    // A sixth of the app's memory (up to 96 MB), so a scrolled list's covers stay in memory instead
    // of being read and decoded again each time they come back on screen.
    private val memory = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 6).coerceIn(16L * 1024 * 1024, 96L * 1024 * 1024).toInt(),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun cached(url: String): Bitmap? = memory.get(url)

    /** The cover in memory, when it's big enough for [heightPx] (a small thumbnail doesn't stand in for a big cover). */
    fun cached(url: String, heightPx: Int): Bitmap? = memory.get(url)?.takeIf { it.height >= heightPx * 0.7f }

    private fun key(url: String): String =
        MessageDigest.getInstance("MD5").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    /** A cover already on the phone (memory or disk), or null; never downloads. For the widget. */
    fun loadCached(context: Context, url: String, maxHeightPx: Int): Bitmap? {
        memory.get(url)?.let { return it }
        val file = File(File(context.cacheDir, "covers"), key(url))
        if (!file.exists()) return null
        return load(context, url, maxHeightPx)
    }

    /** Blocking. Downscales to about [maxHeightPx] tall so a list of covers stays light. */
    fun load(context: Context, url: String, maxHeightPx: Int): Bitmap? {
        cached(url, maxHeightPx)?.let { return it }
        val dir = File(context.cacheDir, "covers").apply { mkdirs() }
        val file = File(dir, key(url))
        val bytes = if (file.exists()) {
            file.readBytes()
        } else {
            val downloaded = Http.bytes(url) ?: return null
            runCatching { file.writeBytes(downloaded) }
            downloaded
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outHeight <= 0) {
            file.delete()
            return null
        }
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= maxHeightPx) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        // Down to the size it's shown at (halving steps can leave it up to twice as big): less to keep and draw.
        val bitmap = if (decoded.height > maxHeightPx * 1.25f && maxHeightPx > 0) {
            val w = (decoded.width * maxHeightPx.toFloat() / decoded.height).toInt().coerceAtLeast(1)
            runCatching { Bitmap.createScaledBitmap(decoded, w, maxHeightPx, true) }.getOrNull()?.also { if (it !== decoded) decoded.recycle() } ?: decoded
        } else decoded
        // Sent to the graphics chip now, off the screen's thread, so its first frame on screen doesn't stall.
        bitmap.prepareToDraw()
        memory.put(url, bitmap)
        return bitmap
    }
}

/**
 * A book cover, or until it loads (or when there's none) the companion app's placeholder:
 * a paper-coloured card with the title set in Lora.
 */
@Composable
fun BookCover(url: String?, title: String, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val heightPx = with(LocalDensity.current) { height.roundToPx() }
    // Keyed on the cover's address: when this spot shows a different book (a list reordered or
    // grew), the old picture is dropped first, so a cover never sticks to the wrong book.
    val image by produceState<ImageBitmap?>(url?.let { CoverCache.cached(it, heightPx)?.asImageBitmap() }, url, heightPx) {
        value = url?.let { CoverCache.cached(it, heightPx)?.asImageBitmap() }
        if (url != null && value == null) {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { CoverCache.load(context, url, heightPx) }.getOrNull()?.asImageBitmap()
            }
            value = loaded
        }
    }
    val shape = RoundedCornerShape(if (width < 64.dp) 4.dp else 6.dp)
    val c = Cp.colors
    Box(
        modifier
            .size(width, height)
            .clip(shape)
            .background(c.cover)
            .border(1.dp, c.hairline, shape),
        contentAlignment = Alignment.Center,
    ) {
        // The cover fades in over the placeholder once it loads (no fade when it was already cached).
        Crossfade(image, animationSpec = Motion.medium(), label = "cover") { img ->
        if (img != null) {
            Image(img, contentDescription = "Cover of $title", contentScale = ContentScale.Crop, modifier = Modifier.size(width, height))
        } else {
            Box(Modifier.size(width, height), contentAlignment = Alignment.Center) {
            Text(
                title,
                fontFamily = Lora,
                fontSize = if (width < 64.dp) 8.sp else 12.sp,
                lineHeight = if (width < 64.dp) 10.sp else 15.sp,
                color = c.quiet,
                textAlign = TextAlign.Center,
                maxLines = if (width < 64.dp) 4 else 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(4.dp),
            )
            }
        }
        }
    }
}
