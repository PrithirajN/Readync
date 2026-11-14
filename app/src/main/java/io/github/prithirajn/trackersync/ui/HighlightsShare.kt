package io.github.prithirajn.trackersync.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import io.github.prithirajn.trackersync.R
import io.github.prithirajn.trackersync.data.Book
import io.github.prithirajn.trackersync.data.BookFacts
import io.github.prithirajn.trackersync.source.Clipping
import io.github.prithirajn.trackersync.sync.Highlights
import io.github.prithirajn.trackersync.sync.HighlightPosition
import io.github.prithirajn.trackersync.sync.KoHighlightSync
import io.github.prithirajn.trackersync.ui.theme.Cp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import android.graphics.Canvas as BitmapCanvas

/**
 * Several highlights as one page of annotations, the way a reader's own Annotations screen looks:
 * the book's title and author at the top, then each passage on a marker band with its chapter and
 * day above it and your note under it in your hand. As many pages as they need.
 */
object AnnotationSheet {
    private const val W = 1080

    data class Ink(val paper: Int, val text: Int, val dim: Int, val band: Int, val rule: Int)
    val PAPER = Ink(0xFFF3F1EC.toInt(), 0xFF1E1E22.toInt(), 0xFF7C7A76.toInt(), 0xFFD3D4D8.toInt(), 0x22000000)
    val NIGHT = Ink(0xFF17181B.toInt(), 0xFFEDEBE6.toInt(), 0xFF8E8C88.toInt(), 0xFF3C4048.toInt(), 0x22FFFFFF)

    private fun layout(text: String, size: Float, face: Typeface, color: Int, width: Int, spacing: Float = 1.25f): StaticLayout {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = face; this.color = color }
        return StaticLayout.Builder.obtain(text, 0, text.length, tp, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, spacing).build()
    }

    /** The pages, each [height] tall (1350 for a post, 1920 for a story). */
    fun render(context: Context, book: Book, clips: List<Clipping>, ink: Ink, height: Int, mark: Boolean = true): List<Bitmap> {
        val serif = runCatching { ResourcesCompat.getFont(context, R.font.lora) }.getOrNull() ?: Typeface.SERIF
        val serifBold = Typeface.create(serif, Typeface.BOLD)
        val sans = runCatching { ResourcesCompat.getFont(context, R.font.inter) }.getOrNull() ?: Typeface.SANS_SERIF
        val hand = runCatching { ResourcesCompat.getFont(context, R.font.caveat) }.getOrNull() ?: Typeface.SERIF
        val pad = 84
        val width = W - pad * 2
        val fmt = DateTimeFormatter.ofPattern("d MMM")
        val zone = ZoneId.systemDefault()

        /** One highlight laid out: its parts and how tall it is. */
        class Block(val meta: StaticLayout, val passage: StaticLayout?, val note: StaticLayout?) {
            val h: Int get() = meta.height + 14 + (passage?.height?.plus(16) ?: 0) + (note?.height?.plus(18) ?: 0) + 54
        }
        fun block(c: Clipping, maxPassage: Int): Block {
            val isNote = KoHighlightSync.isNote(c.id) || c.text.isBlank()
            val meta = listOfNotNull(c.chapter?.takeIf { it.isNotBlank() } ?: HighlightPosition.label(c), Instant.ofEpochSecond(c.createdAt).atZone(zone).format(fmt)).joinToString(" · ")
            var text = Highlights.tidyText(c.text)
            if (text.length > maxPassage) text = text.take(maxPassage).substringBeforeLast(' ') + "…"
            return Block(
                layout(meta, 30f, sans, ink.dim, width),
                if (isNote) null else layout(text, 42f, serif, ink.text, width - 16, 1.32f),
                c.note?.trim()?.takeIf { it.isNotEmpty() }?.let { layout(Highlights.tidyText(it), 44f, hand, ink.text, width - 56, 1.05f) },
            )
        }

        fun header(cv: BitmapCanvas, first: Boolean): Int {
            var y = pad
            val author = book.author?.uppercase()?.let { a -> if (a.length > 26) a.substringBefore(',').take(24) + " ET AL." else a }
            val authorL = author?.let { layout(it, 28f, sans, ink.dim, 340) }
            val titleW = if (authorL != null) width - 360 else width
            val titleL = layout(book.displayTitle, if (first) 64f else 40f, serifBold, ink.text, titleW, 1.12f)
            cv.save(); cv.translate(pad.toFloat(), y.toFloat()); titleL.draw(cv); cv.restore()
            authorL?.let { cv.save(); cv.translate((W - pad - 340).toFloat(), (y + 12).toFloat()); it.draw(cv); cv.restore() }
            y += titleL.height + 30
            val p = Paint().apply { color = ink.rule; strokeWidth = 2f }
            cv.drawLine(pad.toFloat(), y.toFloat(), (W - pad).toFloat(), y.toFloat(), p)
            return y + 40
        }

        val footer = 90
        val pages = ArrayList<Bitmap>()
        var cv: BitmapCanvas? = null
        var y = 0
        var bmp: Bitmap? = null
        fun newPage() {
            bmp = Bitmap.createBitmap(W, height, Bitmap.Config.ARGB_8888)
            cv = BitmapCanvas(bmp!!).also { it.drawColor(ink.paper) }
            y = header(cv!!, pages.isEmpty())
            pages += bmp!!
        }
        newPage()
        val bandP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink.band }
        for (c in clips) {
            var b = block(c, 900)
            // Too long for a page of its own: shortened to fit.
            val room = height - footer - (pad + 200)
            if (b.h > room) b = block(c, 420)
            if (y + b.h > height - footer) newPage()
            val canvas = cv!!
            canvas.save(); canvas.translate(pad.toFloat(), y.toFloat()); b.meta.draw(canvas); canvas.restore()
            y += b.meta.height + 14
            b.passage?.let { l ->
                // A marker band behind each line, like a highlight on the page.
                for (i in 0 until l.lineCount) {
                    val top = y + l.getLineTop(i) - 4f
                    val bottom = y + l.getLineBaseline(i) + l.paint.descent() + 6f
                    canvas.drawRoundRect(RectF(pad.toFloat() - 6, top, pad + 8 + l.getLineRight(i) + 6, bottom), 4f, 4f, bandP)
                }
                canvas.save(); canvas.translate(pad + 8f, y.toFloat()); l.draw(canvas); canvas.restore()
                y += l.height + 16
            }
            b.note?.let { l ->
                y += 2
                val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink.text; textSize = 34f; typeface = Typeface.DEFAULT_BOLD }
                canvas.drawText("✎", pad.toFloat() + 4, y + 36f, pen)
                canvas.save(); canvas.translate(pad + 56f, y.toFloat()); l.draw(canvas); canvas.restore()
                y += l.height + 18
            }
            y += 54
        }
        // Page numbers and the app's mark.
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink.dim; textSize = 26f; typeface = sans }
        pages.forEachIndexed { i, page ->
            val pc = BitmapCanvas(page)
            if (pages.size > 1) pc.drawText("${i + 1} / ${pages.size}", pad.toFloat(), height - 50f, p)
            if (mark) { p.textAlign = Paint.Align.RIGHT; pc.drawText("made with ${io.github.prithirajn.trackersync.Edition.name}", (W - pad).toFloat(), height - 50f, p); p.textAlign = Paint.Align.LEFT }
        }
        return pages
    }
}

/** Several pictures to another app at once: Threads (as a carousel) or anywhere (the share sheet). */
object ManyPictures {
    private const val THREADS = "com.instagram.barcelona"

    private fun intent(context: Context, pictures: List<Bitmap>, text: String, pkg: String?): Intent {
        val uris = pictures.map { QuoteCard.cacheUri(context, it) }
        return (if (uris.size == 1) Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
        else Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) }).apply {
            type = "image/png"
            pkg?.let { setPackage(it) }
            putExtra(Intent.EXTRA_TEXT, text)
            clipData = ClipData.newRawUri("Highlights", uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Threads takes up to ten pictures in one post. "app", or null when Threads isn't there. */
    fun threads(context: Context, pictures: List<Bitmap>, text: String): String? {
        runCatching { (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(ClipData.newPlainText("Post", text)) }
        return if (runCatching { context.startActivity(intent(context, pictures.take(10), text, THREADS)) }.isSuccess) "app" else null
    }

    fun share(context: Context, pictures: List<Bitmap>, text: String) {
        context.startActivity(Intent.createChooser(intent(context, pictures, text, null), "Share highlights").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * The highlights you picked, to post or keep: each as its own card (in one look for all), or all
 * on one sheet of annotations. Posted to X (four pictures at most), Threads (ten), anywhere, or
 * saved to your photos. The words with them are the book and its tags, not the passages again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MultiShareSheet(book: Book, facts: BookFacts?, clips: List<Clipping>, markdown: String? = null, onClose: () -> Unit) {
    val c = Cp.colors
    val context = LocalContext.current
    var asSheet by remember { mutableStateOf(clips.size > 4) }
    var look by remember { mutableStateOf(QuoteCard.Look.SPOTLIGHT) }
    var night by remember { mutableStateOf(false) }
    var story by remember { mutableStateOf(false) }
    var pics by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var said by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(asSheet, look, night, story, clips) {
        busy = true
        pics = withContext(Dispatchers.IO) {
            runCatching {
                if (asSheet) AnnotationSheet.render(context, book, clips, if (night) AnnotationSheet.NIGHT else AnnotationSheet.PAPER, if (story) 1920 else 1350)
                else {
                    val cover = book.coverUrl?.let { runCatching { CoverCache.load(context, it, 1400) }.getOrNull() }
                    clips.map { cl ->
                        val isNote = KoHighlightSync.isNote(cl.id) || cl.text.isBlank()
                        QuoteCard.render(
                            context,
                            QuoteCard.Content(
                                text = if (isNote) cl.note.orEmpty() else Highlights.tidyText(cl.text), note = if (isNote) null else cl.note,
                                title = book.displayTitle, author = book.author,
                                where = cl.chapter?.takeIf { it.isNotBlank() } ?: HighlightPosition.label(cl), percent = cl.percent, standalone = isNote,
                            ),
                            look, cover, null, size = if (story) QuoteCard.Size.STORY else QuoteCard.Size.POST,
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }
        busy = false
    }
    val caption = SocialCaption.forPicture(book, facts, "A few lines I marked.", x = true)
    PlainSheet("Share ${clips.size} highlight${if (clips.size == 1) "" else "s"}", onClose) {
        // As text: Markdown for notes apps (Obsidian, Notion), mail or anywhere.
        markdown?.let { md ->
            VSpace(8)
            PillButton("Export as text", {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Highlights from ${book.displayTitle}")
                    putExtra(Intent.EXTRA_TEXT, md)
                }
                context.startActivity(Intent.createChooser(send, "Export highlights").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
            Quiet("Markdown, for notes apps like Obsidian or Notion. Or as pictures:", color = c.faint)
        }
        VSpace(10)
        Segmented(listOf(false, true), asSheet, { if (it) "One sheet" else "Separate cards" }, { asSheet = it }, Modifier.fillMaxWidth())
        VSpace(8)
        if (asSheet) Segmented(listOf(false, true), night, { if (it) "Night" else "Paper" }, { night = it }, Modifier.fillMaxWidth())
        else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (l in QuoteCard.Look.entries.filter { it != QuoteCard.Look.PAGE && it != QuoteCard.Look.WORDPRINT }) PillButton(l.label, { look = l }, outlined = look != l)
        }
        VSpace(8)
        Segmented(listOf(false, true), story, { if (it) "Story" else "Post" }, { story = it }, Modifier.fillMaxWidth())
        VSpace(12)
        if (busy) Quiet("Drawing them…", color = c.faint)
        else if (pics.isEmpty()) Quiet("Couldn't draw them.", color = c.errorText)
        else {
            Quiet(if (pics.size == 1) "1 picture" else "${pics.size} pictures" + if (pics.size > XPost.MAX_PICTURES) " (X takes the first ${XPost.MAX_PICTURES}; Threads up to 10)" else "", color = c.faint)
            VSpace(6)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(pics.size) { i ->
                    val b = pics[i]
                    Image(b.asImageBitmap(), contentDescription = "Picture ${i + 1}", modifier = Modifier.height(320.dp).width((320f * b.width / b.height).dp).clip(RoundedCornerShape(10.dp)))
                }
            }
            VSpace(12)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Post to X", { said = XPost.said(XPost.pictures(context, pics, caption), if (pics.size == 1) "the picture" else "the pictures") }, outlined = false)
                PillButton("Post to Threads", {
                    said = if (ManyPictures.threads(context, pics, SocialCaption.forPicture(book, facts, "A few lines I marked.", x = false)) != null) "Opened in Threads with the pictures. The words are copied too." else "Threads isn't installed."
                })
                PillButton("Share", { runCatching { ManyPictures.share(context, pics, caption) } })
                PillButton("Save all", {
                    val ok = pics.count { runCatching { QuoteCard.save(context, it) }.getOrDefault(false) }
                    said = if (ok == pics.size) "Saved ${pics.size} to your photos" else "Couldn't save them here; use Share instead."
                })
            }
        }
        said?.let { VSpace(8); Quiet(it, color = c.textMid) }
    }
}
