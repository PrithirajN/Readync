package io.github.prithirajn.readync.ai

import android.content.Context
import android.net.Uri
import android.os.Build
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.SpeculativeDecodingConfig
import com.google.ai.edge.litertlm.SpeculativeDecodingMode
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The on-device model, run with Google's LiteRT-LM: a ".litertlm" model file (Gemma, Qwen, Llama,
 * Phi, DeepSeek… from Hugging Face's litert-community) downloaded here or chosen from the phone,
 * kept in the app's own storage. The file carries its own chat format, so prompts go in as plain
 * text. Nothing is sent anywhere. Loaded on first use (on the phone's GPU where it can, else its
 * CPU), kept loaded while the app runs, freed when you remove the model.
 */
object LocalModel {
    private const val DIR = "models"
    private val lock = Any()
    @Volatile private var engine: Engine? = null
    @Volatile private var loadedFrom: String? = null

    /**
     * A model that can be downloaded from the app, and the memory a phone needs for it ([minRamGb],
     * what the phone's box says).
     */
    data class Choice(val name: String, val file: String, val url: String, val size: String, val note: String, val page: String, val minRamGb: Int = 4)

    /** Ready-made models from Hugging Face's litert-community, best first. */
    val catalog = listOf(
        Choice(
            "Gemma 4 E4B", "gemma-4-E4B-it.litertlm",
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            "about 3.7 GB", "The best here for everything: highlight summaries, reviews, recaps, questions. Quick on the phone's GPU; needs 12 GB of memory.",
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm", minRamGb = 12,
        ),
        Choice(
            "Gemma 4 E2B", "gemma-4-E2B-it.litertlm",
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            "about 2.6 GB", "Nearly as good and lighter; for phones with 8 GB of memory.",
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm", minRamGb = 8,
        ),
        Choice(
            "Qwen3 1.7B", "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
            "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/main/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
            "about 0.9 GB", "Good for its size, fast on the GPU; for phones with 6 GB of memory.",
            "https://huggingface.co/litert-community/Qwen3-1.7B", minRamGb = 6,
        ),
        Choice(
            "Qwen3 0.6B", "Qwen3-0.6B.litertlm",
            "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
            "about 0.6 GB", "The smallest; fine for matching and tidying, plain writing. For older phones.",
            "https://huggingface.co/litert-community/Qwen3-0.6B", minRamGb = 4,
        ),
    )

    /** This phone's memory in GB, as its box would say it (the system reports a little less). */
    fun phoneRamGb(context: Context): Int {
        val mi = android.app.ActivityManager.MemoryInfo()
        context.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(mi)
        val gb = mi.totalMem / 1_000_000_000.0
        return listOf(4, 6, 8, 12, 16, 24).firstOrNull { it >= gb * 0.95 } ?: Math.ceil(gb).toInt()
    }

    /** The best model in [catalog] this phone has the memory for. */
    fun recommended(context: Context): Choice = phoneRamGb(context).let { ram -> catalog.firstOrNull { it.minRamGb <= ram } ?: catalog.last() }

    /** Whether this phone's processor can run the engine (it ships for 64-bit ARM and x86 only). */
    fun supported(): Boolean = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }

    /** The model file in use, or null when none is set up. */
    fun file(context: Context): File? =
        File(context.filesDir, DIR).listFiles()?.firstOrNull { it.isFile && !it.name.endsWith(".part") }

    private fun safeName(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "model.litertlm" }

    /** Puts a finished [part] file in place as the model (replacing any earlier one). */
    private fun place(dir: File, part: File, name: String): File {
        close()
        dir.listFiles()?.filter { it != part }?.forEach { it.delete() }
        val target = File(dir, safeName(name))
        if (!part.renameTo(target)) throw IllegalStateException("Couldn't save the model")
        return target
    }

    /**
     * Copies a model file chosen on the phone into the app. [onProgress] gets 0..1 when the size
     * is known. Blocking; call off the main thread.
     */
    fun install(context: Context, uri: Uri, name: String, onProgress: (Float) -> Unit = {}): File {
        if (!name.endsWith(".litertlm", ignoreCase = true)) {
            throw IllegalStateException(
                if (name.endsWith(".task", ignoreCase = true)) "That's an older MediaPipe .task file. Download the .litertlm version of the model instead."
                else "Choose a .litertlm model file (from Hugging Face's litert-community)."
            )
        }
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val part = File(dir, safeName(name) + ".part")
        val total = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()?.takeIf { it > 0 }
        context.contentResolver.openInputStream(uri)?.use { input ->
            part.outputStream().use { out -> copy(input, out, total, onProgress) }
        } ?: throw IllegalStateException("Couldn't open that file")
        return place(dir, part, name)
    }

    /**
     * Downloads [choice] into the app. A model whose licence must be accepted first (Gemma)
     * answers "not allowed": then it's downloaded in the browser and chosen as a file.
     */
    fun download(context: Context, choice: Choice, cancelled: () -> Boolean = { false }, onProgress: (Float) -> Unit = {}): File {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val part = File(dir, choice.file + ".part")
        // An earlier download that stopped (the network, the app closed) carries on where it was.
        val have = part.takeIf { it.exists() }?.length() ?: 0L
        var url = choice.url
        var conn: HttpURLConnection
        var hops = 0
        // Hugging Face sends the file from its storage: follow the redirects (https only).
        while (true) {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "TrackerSync")
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            val code = conn.responseCode
            if (code in 300..399 && hops < 6) {
                val next = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                url = URL(URL(url), next).toString()
                if (!url.startsWith("https://")) throw IllegalStateException("The download was redirected somewhere unsafe")
                hops++
                continue
            }
            if (code == 416) {
                // Already all there (or the file changed on Hugging Face): started over next time.
                conn.disconnect()
                part.delete()
                throw IllegalStateException("The download needs to start again; tap Get once more")
            }
            if (code == 401 || code == 403) {
                conn.disconnect()
                throw IllegalStateException("${choice.name} needs its licence accepted on Hugging Face first: open its page, accept, download the file in your browser, then choose it here.")
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IllegalStateException("Hugging Face answered HTTP $code")
            }
            break
        }
        try {
            val resumed = have > 0 && conn.responseCode == 206
            val rest = conn.contentLengthLong.takeIf { it > 0 }
            val total = rest?.let { if (resumed) it + have else it }
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, resumed).use { out -> copy(input, out, total, onProgress, if (resumed) have else 0L, cancelled) }
            }
            if (total != null && part.length() != total) throw IllegalStateException("The download stopped early; tap Get again to carry on")
        } catch (e: Exception) {
            // Kept, to carry on next time (a few GB shouldn't start over).
            throw e
        } finally {
            conn.disconnect()
        }
        return place(dir, part, choice.file)
    }

    private fun copy(input: java.io.InputStream, out: java.io.OutputStream, total: Long?, onProgress: (Float) -> Unit, from: Long = 0L, cancelled: () -> Boolean = { false }) {
        val buf = ByteArray(1 shl 20)
        var done = from
        var last = -1
        while (true) {
            if (cancelled()) throw IllegalStateException("Download stopped")
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            if (total != null) {
                val pct = (done * 100 / total).toInt()
                if (pct != last) { last = pct; onProgress((done.toDouble() / total).toFloat().coerceIn(0f, 1f)) }
            }
        }
    }

    /** Removes the model file and frees its memory. */
    fun remove(context: Context) {
        close()
        File(context.filesDir, DIR).listFiles()?.forEach { it.delete() }
    }

    fun close() = synchronized(lock) {
        runCatching { engine?.close() }
        engine = null
        loadedFrom = null
    }

    /**
     * The model, loaded on first use (GPU first, CPU when the GPU can't run it); null when none
     * is set up. Throws when this phone can't run it, with the reason.
     */
    fun get(context: Context): TextModel? {
        val f = file(context) ?: return null
        if (!supported()) throw IllegalStateException("This phone's processor isn't supported by the on-device model (it needs a 64-bit phone)")
        val eng = synchronized(lock) {
            if (engine == null || loadedFrom != f.path) {
                runCatching { engine?.close() }
                engine = null
                engine = start(context, f, Backend.GPU()) ?: start(context, f, Backend.CPU())
                    ?: throw IllegalStateException("The model didn't load on this phone")
                loadedFrom = f.path
            }
            engine
        } ?: return null
        // Qwen3 reasons out loud first unless asked not to; the answers here are short and direct.
        val noThinking = f.name.lowercase().contains("qwen3")
        // Gemma 4 runs with an 8,192-token context here: room for several pages of the book.
        val big = Regex("gemma-?4", RegexOption.IGNORE_CASE).containsMatchIn(f.name)
        val qwenMid = Regex("qwen3[-_]?1\\.7b", RegexOption.IGNORE_CASE).containsMatchIn(f.name)
        return object : TextModel {
            override val readChars: Int get() = if (big) 18_000 else if (qwenMid) 8_000 else 5_000

            // One answer at a time, each in a fresh conversation (no memory of earlier ones). Thinking
            // turned off (the answers here are short), and an answer stopped at [MAX_ANSWER] tokens.
            override fun generate(prompt: String): String = synchronized(lock) {
                val sampler = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.3)
                val text = if (noThinking) "$prompt /no_think" else prompt
                val tuned = thinkingOff != false
                val config = if (tuned) ConversationConfig(samplerConfig = sampler, thinkingConfig = ThinkingConfig(enableThinking = false), maxOutputToken = MAX_ANSWER)
                    else ConversationConfig(samplerConfig = sampler)
                try {
                    eng.createConversation(config).use { conversation -> conversation.sendMessage(text).toString() }.also { if (tuned) thinkingOff = true }
                } catch (e: Exception) {
                    // A model that doesn't take these settings: asked plainly, and from then on.
                    if (!tuned || thinkingOff == true) throw e
                    thinkingOff = false
                    eng.createConversation(ConversationConfig(samplerConfig = sampler)).use { conversation -> conversation.sendMessage(text).toString() }
                }
            }

            // A picture and a question (Gemma 4 only: the others read text alone).
            override fun look(imagePath: String, prompt: String): String? = synchronized(lock) {
                if (!seesImages) return@synchronized null
                val config = ConversationConfig(
                    samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.1),
                    thinkingConfig = ThinkingConfig(enableThinking = false), maxOutputToken = 200,
                )
                eng.createConversation(config).use { conversation ->
                    conversation.sendMessage(Message.of(Content.ImageFile(imagePath), Content.Text(prompt))).toString()
                }
            }
        }
    }

    /** Whether the loaded model was started with its picture reader (Gemma 4). */
    @Volatile private var seesImages = false

    /** Whether the loaded model takes "no thinking" and an answer cap (null: not tried yet). */
    @Volatile private var thinkingOff: Boolean? = null

    /** The longest answer asked for (a review or a recap is a few hundred). */
    private const val MAX_ANSWER = 1024

    /** Context for Gemma 4 (it allows far more; the prompts here fit, and a smaller cache is faster and lighter). */
    private const val GEMMA_CONTEXT = 8192

    /**
     * The engine on [backend], or null when it can't start there (a missing GPU library, say).
     * Gemma 4 starts with speculative decoding (its built-in drafter guesses a few words ahead, used
     * only while it's faster) and a smaller context; anything that won't start so starts plainly.
     * The GPU's compiled kernels are kept in the app's files, so later starts are quicker.
     */
    private fun start(context: Context, f: File, backend: Backend): Engine? {
        val cache = File(context.filesDir, "model-cache").apply { mkdirs() }.path
        val gemma4 = Regex("gemma-?4", RegexOption.IGNORE_CASE).containsMatchIn(f.name)
        val spec = SpeculativeDecodingConfig(SpeculativeDecodingMode.ADAPTIVE)
        // Gemma 4 also reads pictures (its picture reader is loaded only when one is asked about).
        val vision = if (backend is Backend.GPU) Backend.GPU() else Backend.CPU()
        val tries = buildList {
            if (gemma4) {
                add(EngineConfig(modelPath = f.path, backend = backend, visionBackend = vision, cacheDir = cache, maxNumTokens = GEMMA_CONTEXT, maxNumImages = 1, speculativeDecodingConfig = spec))
                add(EngineConfig(modelPath = f.path, backend = backend, visionBackend = vision, cacheDir = cache, maxNumTokens = GEMMA_CONTEXT, maxNumImages = 1))
                add(EngineConfig(modelPath = f.path, backend = backend, cacheDir = cache, maxNumTokens = GEMMA_CONTEXT, speculativeDecodingConfig = spec))
                add(EngineConfig(modelPath = f.path, backend = backend, cacheDir = cache, maxNumTokens = GEMMA_CONTEXT))
            }
            add(EngineConfig(modelPath = f.path, backend = backend, cacheDir = cache))
        }
        for (config in tries) {
            var e: Engine? = null
            try {
                e = Engine(config)
                e.initialize()
                thinkingOff = null
                seesImages = config.visionBackend != null
                return e
            } catch (t: Throwable) {
                runCatching { e?.close() }
            }
        }
        return null
    }

    /**
     * Loads the model ahead of its first use, off the main thread, on phones with room to keep it
     * (12 GB or more), so the first AI answer doesn't wait for it.
     */
    fun warm(context: Context) {
        if (file(context) == null || phoneRamGb(context) < 12) return
        Thread({ runCatching { get(context) } }, "model-warm").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /** Frees the model's memory (the app gone to the background on a phone that needs it back). */
    fun release() {
        if (engine != null) close()
    }
}
