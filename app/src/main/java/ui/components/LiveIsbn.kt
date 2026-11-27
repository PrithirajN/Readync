package io.github.prithirajn.readync.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.prithirajn.readync.sync.Isbn
import io.github.prithirajn.readync.ui.theme.Cp
import io.github.prithirajn.readync.ui.theme.CpType
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The camera, reading an ISBN as soon as it's in view: the barcode, or the number printed as text
 * (the copyright page, the back cover, a sticker), whichever comes first. No photo is taken or kept;
 * the frames are only read on the phone. Asks for the camera the first time. [onFound] gets the ISBN.
 */
@Composable
fun LiveIsbnReader(onFound: (String) -> Unit, onClose: () -> Unit, onNoCamera: (() -> Unit)? = null, onPrinted: ((Double) -> Unit)? = null) {
    val context = LocalContext.current
    val c = Cp.colors
    var allowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var refused by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        allowed = ok; refused = !ok
        // Not allowed: the caller's other way (Google's scanner, which needs no permission).
        if (!ok) onNoCamera?.invoke()
    }
    LaunchedEffect(Unit) { if (!allowed) ask.launch(Manifest.permission.CAMERA) }
    // What it last read that wasn't an ISBN yet, so you can see it's looking.
    var seen by remember { mutableStateOf<String?>(null) }
    // More than one printed edition's ISBN in view (a copyright page listing hardback, paperback,
    // collector's…): they're listed to tap, rather than one picked for you.
    var choices by remember { mutableStateOf<List<Isbn.Found>>(emptyList()) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    val view = LocalView.current
    LaunchedEffect(choices.size >= 2) { if (choices.size >= 2) Haptics.rise(view) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (allowed) IsbnCamera(onFound = onFound, onSeen = { seen = it }, onChoices = { choices = it }, onCamera = { camera = it; torch = false }, onPrinted = onPrinted)
            // The box to hold it in.
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width * 0.82f
                val h = size.height * 0.22f
                val tl = Offset((size.width - w) / 2, (size.height - h) / 2)
                drawRoundRect(Color.White.copy(alpha = 0.9f), tl, Size(w, h), CornerRadius(14.dp.toPx()), style = Stroke(2.5.dp.toPx()))
            }
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Point at the ISBN", style = CpType.bodyStrong, color = Color.White, modifier = Modifier.weight(1f))
                        // The torch, for a dim room or a glossy page.
                        val cam = camera
                        if (cam != null && cam.cameraInfo.hasFlashUnit()) {
                            Box(
                                Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(if (torch) Color(0xFFFFD45A) else Color.White.copy(alpha = 0.18f))
                                    .clickable(onClickLabel = if (torch) "Turn the light off" else "Turn the light on", role = androidx.compose.ui.semantics.Role.Switch) {
                                        torch = !torch; cam.cameraControl.enableTorch(torch); Haptics.tick(view)
                                    },
                                contentAlignment = Alignment.Center,
                            ) { LucideIcon(io.github.prithirajn.readync.R.drawable.lucide_zap, Modifier.size(22.dp), if (torch) Color.Black else Color.White) }
                        }
                    }
                    Text(
                        when {
                            refused -> "Readync needs the camera to read a printed ISBN. Allow it in your phone's settings, or type the ISBN in the search box."
                            else -> "The barcode, or the number printed on the copyright page or back cover. It's read the moment it's in the box."
                        },
                        style = CpType.small, color = Color.White.copy(alpha = 0.8f),
                    )
                }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (choices.size >= 2) {
                        // Every edition in view: tap yours.
                        Column(
                            Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.72f)).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("${choices.size} ISBNs here. Which is your copy?", style = CpType.bodyStrong, color = Color.White)
                            for (f in choices) {
                                Row(
                                    Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.12f))
                                        .clickable(onClickLabel = "This one") { Haptics.done(view); onFound(f.isbn) }
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(f.label ?: "Edition not named", style = CpType.body, color = Color.White, maxLines = 1)
                                        Text(Isbn.pretty(f.isbn), style = CpType.mono, color = Color.White.copy(alpha = 0.75f))
                                    }
                                    Text("This one", style = CpType.label, color = Color(0xFFFFD45A))
                                }
                            }
                        }
                    } else seen?.let { Text("Reading… $it", style = CpType.mono, color = Color.White.copy(alpha = 0.7f), maxLines = 1) }
                    Row(Modifier.padding(top = 12.dp)) { PillButton("Cancel", onClose) }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
private fun IsbnCamera(onFound: (String) -> Unit, onSeen: (String) -> Unit, onChoices: (List<Isbn.Found>) -> Unit, onCamera: (androidx.camera.core.Camera) -> Unit, onPrinted: ((Double) -> Unit)? = null) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    val done = remember { AtomicBoolean(false) }
    val busy = remember { AtomicBoolean(false) }
    val worker = remember { Executors.newSingleThreadExecutor() }
    val barcodes = remember {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_UPC_A, Barcode.FORMAT_EAN_8).build())
    }
    val text = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val main = remember { ContextCompat.getMainExecutor(context) }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            barcodes.close(); text.close(); worker.shutdown()
        }
    }
    // Printed ISBNs read so far, and when the first was: one alone is taken after a moment (in case
    // another edition's comes into view); two or more are offered to choose from.
    val pool = remember { java.util.concurrent.ConcurrentHashMap<String, Isbn.Found>() }
    val firstAt = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    fun found(isbn: String) {
        if (!done.compareAndSet(false, true)) return
        main.execute { Haptics.done(view); onFound(isbn) }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val preview = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
                val show = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(worker) { frame: ImageProxy ->
                    val media = frame.image
                    if (media == null || done.get() || !busy.compareAndSet(false, true)) { frame.close(); return@setAnalyzer }
                    val image = InputImage.fromMediaImage(media, frame.imageInfo.rotationDegrees)
                    fun finish() { busy.set(false); frame.close() }
                    // The barcode first (exact); else the printed number.
                    barcodes.process(image)
                        .addOnSuccessListener { codes ->
                            // (Not while you're choosing between the editions listed on a page.)
                            if (pool.size < 2) codes.firstNotNullOfOrNull { b -> b.rawValue?.let { Isbn.fromBarcode(it) } }?.let { isbn ->
                                // The price is usually printed by the barcode ("MRP ₹499"): read it off the same frame.
                                if (onPrinted == null) { found(isbn); finish(); return@addOnSuccessListener }
                                text.process(image)
                                    .addOnSuccessListener { t -> Isbn.printedPrice(t.text)?.let { p -> main.execute { onPrinted(p) } } }
                                    .addOnCompleteListener { found(isbn); finish() }
                                return@addOnSuccessListener
                            }
                            text.process(image)
                                .addOnSuccessListener { t ->
                                    val all = Isbn.allFromText(t.text, allowElectronic = false)
                                    for (f in all) pool.merge(f.isbn, f) { old, new -> if ((new.label != null && old.label == null) || new.score > old.score) new else old }
                                    if (all.isNotEmpty()) firstAt.compareAndSet(0L, System.currentTimeMillis())
                                    if (pool.size >= 2) {
                                        val list = pool.values.sortedByDescending { it.score }
                                        main.execute { onChoices(list) }
                                    } else if (pool.size == 1 && System.currentTimeMillis() - firstAt.get() >= 1200L) found(pool.values.first().isbn)
                                    else if (all.isEmpty()) t.text.lines().firstOrNull { Regex("(?i)isbn|97[89]").containsMatchIn(it) }?.let { l -> main.execute { onSeen(l.take(40)) } }
                                }
                                .addOnCompleteListener { finish() }
                        }
                        .addOnFailureListener { finish() }
                }
                runCatching {
                    provider.unbindAll()
                    val cam = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, show, analysis)
                    onCamera(cam)
                }
            }, main)
            preview
        },
    )
}
