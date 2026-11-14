package io.github.prithirajn.trackersync.ui

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.prithirajn.trackersync.sync.PageReader
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Photos of a page and the text on them. The camera app saves the photo into the app's cache
 * through a FileProvider; ML Kit reads the text on the phone (nothing is uploaded); the photo
 * is deleted once read.
 */
object PageScanner {
    /** A fresh file for the camera to save into, and the content Uri to hand it. */
    fun newPhoto(context: Context): Pair<File, Uri> {
        val dir = File(context.cacheDir, "pages").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only ever one photo at a time
        val file = File(dir, "page-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, context.packageName + ".pages", file)
        return file to uri
    }

    /** What a live scan came back with. */
    sealed interface LiveScan {
        data class Isbn(val isbn: String) : LiveScan
        /** A barcode that isn't a book's ISBN (a shop's own code, say). */
        data class NotIsbn(val code: String) : LiveScan
        data object Cancelled : LiveScan
        /** The live scanner can't run here (no Play services, or its part not downloaded yet). */
        data class Unavailable(val reason: String) : LiveScan
    }

    /**
     * Google's live scanner: the camera with a framing box, which reads the barcode the moment it's
     * in view (no shutter button, no photo saved). Book barcodes only (EAN-13, plus UPC-A and
     * EAN-8, which some books carry); it zooms in by itself on a small barcode.
     */
    fun scanLive(context: Context, done: (LiveScan) -> Unit) {
        val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_UPC_A, Barcode.FORMAT_EAN_8)
            .enableAutoZoom()
            .build()
        val scanner = try {
            com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context, options)
        } catch (e: Throwable) {
            done(LiveScan.Unavailable(e.message ?: "The scanner isn't available")); return
        }
        scanner.startScan()
            .addOnSuccessListener { b ->
                val raw = b.rawValue.orEmpty()
                val isbn = io.github.prithirajn.trackersync.sync.Isbn.fromBarcode(raw)
                done(if (isbn != null) LiveScan.Isbn(isbn) else LiveScan.NotIsbn(raw))
            }
            .addOnCanceledListener { done(LiveScan.Cancelled) }
            .addOnFailureListener { e -> done(LiveScan.Unavailable(e.message ?: "The scanner didn't open")) }
    }

    /** Book barcodes on the photo (EAN-13 is where ISBNs live), as their digits. */
    suspend fun barcodes(context: Context, uri: Uri): List<String> {
        val image = InputImage.fromFilePath(context, uri)
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_UPC_A, Barcode.FORMAT_EAN_8)
                .build()
        )
        try {
            val codes = suspendCancellableCoroutine { cont ->
                scanner.process(image)
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
            }
            return codes.mapNotNull { it.rawValue }
        } finally {
            scanner.close()
        }
    }

    /** The photo's lines of text, with boxes as fractions of the upright photo. Blocking-free. */
    suspend fun read(context: Context, uri: Uri): List<PageReader.Line> {
        val image = InputImage.fromFilePath(context, uri)
        // Boxes come in upright coordinates; the raw size is sideways when the photo is turned.
        val turned = image.rotationDegrees == 90 || image.rotationDegrees == 270
        val w = (if (turned) image.height else image.width).toFloat().coerceAtLeast(1f)
        val h = (if (turned) image.width else image.height).toFloat().coerceAtLeast(1f)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val text = suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
            }
            return text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                PageReader.Line(line.text, box.top / h, box.bottom / h, box.left / w, box.right / w)
            }
        } finally {
            recognizer.close()
        }
    }

    /** A page read word by word, with each word's look (for spotting a highlight), in reading order. */
    class Scan(val lines: List<PageReader.Line>, val words: List<io.github.prithirajn.trackersync.sync.PageContext.Seen>) {
        /** The page's running text. */
        val text: String get() = io.github.prithirajn.trackersync.sync.PageContext.join(lines)
    }

    /** The photo upright (as the camera held it) and no bigger than [max] pixels on its long side. */
    private fun upright(context: Context, uri: Uri, max: Int): android.graphics.Bitmap? {
        val cr = context.contentResolver
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
        val bmp = cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val degrees = runCatching {
            cr.openInputStream(uri)?.use { android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull().let {
            when (it) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }
        if (degrees == 0f) return bmp
        val turned = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(degrees) }, true)
        if (turned !== bmp) bmp.recycle()
        return turned
    }

    /** How a word's box looks: the paper behind it, its colour, and a line under it. */
    private fun look(bmp: android.graphics.Bitmap, box: android.graphics.Rect): Triple<Float, Float, Float> {
        val l = box.left.coerceIn(0, bmp.width - 1); val r = box.right.coerceIn(l + 1, bmp.width)
        val t = box.top.coerceIn(0, bmp.height - 1); val b = box.bottom.coerceIn(t + 1, bmp.height)
        val w = r - l; val h = b - t
        val stepX = maxOf(1, w / 24); val stepY = maxOf(1, h / 8)
        val lum = ArrayList<Float>(200)
        val sats = ArrayList<Pair<Float, Float>>(200)
        var y = t
        while (y < b) {
            var x = l
            while (x < r) {
                val p = bmp.getPixel(x, y)
                val rr = (p shr 16 and 0xFF) / 255f; val gg = (p shr 8 and 0xFF) / 255f; val bb = (p and 0xFF) / 255f
                val mx = maxOf(rr, gg, bb); val mn = minOf(rr, gg, bb)
                val lm = 0.3f * rr + 0.59f * gg + 0.11f * bb
                lum += lm
                sats += lm to (if (mx > 0f) (mx - mn) / mx else 0f)
                x += stepX
            }
            y += stepY
        }
        lum.sort()
        // The paper is what's between the letters: the lighter part of the box.
        val bg = lum[(lum.size * 0.85f).toInt().coerceAtMost(lum.size - 1)]
        val sat = sats.filter { it.first >= lum[lum.size / 2] }.map { it.second }.average().toFloat().takeIf { !it.isNaN() } ?: 0f
        // An underline: a row just under the word where most of the width is dark.
        var under = 0f
        val y0 = (b - h * 0.12f).toInt().coerceIn(0, bmp.height - 1)
        val y1 = (b + h * 0.35f).toInt().coerceIn(0, bmp.height - 1)
        val dark = bg - 0.35f
        var yy = y0
        while (yy <= y1) {
            var n = 0; var d = 0; var x = l
            while (x < r) {
                val p = bmp.getPixel(x, yy)
                val lm = (0.3f * (p shr 16 and 0xFF) + 0.59f * (p shr 8 and 0xFF) + 0.11f * (p and 0xFF)) / 255f
                if (lm < dark) d++
                n++; x += maxOf(1, w / 40)
            }
            if (n > 0) under = maxOf(under, d.toFloat() / n)
            yy++
        }
        return Triple(bg, sat, under)
    }

    /**
     * Reads a photo of a page (an e-reader's screen or paper) word by word, noting how each word
     * looks so the marked passage can be found. Nothing leaves the phone.
     */
    suspend fun scan(context: Context, uri: Uri): Scan {
        val bmp = upright(context, uri, 2200) ?: throw IllegalStateException("Couldn't open the picture")
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val image = InputImage.fromBitmap(bmp, 0)
            val text = suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
            }
            val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
            val all = text.textBlocks.flatMap { it.lines }.mapNotNull { ln -> ln.boundingBox?.let { ln to it } }
            // Reading order: a spread's left page first, then top to bottom.
            val twoColumns = all.count { it.second.right / w < 0.52f } >= 4 && all.count { it.second.left / w > 0.48f } >= 4
            val ordered = all.sortedWith(compareBy({ if (twoColumns && it.second.left / w > 0.48f) 1 else 0 }, { it.second.top }, { it.second.left }))
            val lines = ArrayList<PageReader.Line>()
            val words = ArrayList<io.github.prithirajn.trackersync.sync.PageContext.Seen>()
            ordered.forEachIndexed { li, (ln, box) ->
                lines += PageReader.Line(ln.text, box.top / h, box.bottom / h, box.left / w, box.right / w)
                for (el in ln.elements) {
                    val eb = el.boundingBox ?: continue
                    val (bg, sat, under) = look(bmp, eb)
                    words += io.github.prithirajn.trackersync.sync.PageContext.Seen(el.text, li, bg, sat, under)
                }
            }
            return Scan(lines, words)
        } finally {
            recognizer.close()
            bmp.recycle()
        }
    }

}
