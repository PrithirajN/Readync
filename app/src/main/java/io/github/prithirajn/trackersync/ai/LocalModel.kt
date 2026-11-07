package io.github.prithirajn.trackersync.ai

import android.content.Context
import android.net.Uri
import android.os.Build
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
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

    /** A model that can be downloaded from the app. */
    data class Choice(val name: String, val file: String, val url: String, val size: String, val note: String, val page: String)

    /** Ready-made models from Hugging Face's litert-community (the files Google's own guides use). */
    val catalog = listOf(
        Choice(
            "Qwen3 0.6B", "Qwen3-0.6B.litertlm",
            "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
            "about 0.6 GB", "Small and quick; fine for matching and tidying, plainer writing.",
            "https://huggingface.co/litert-community/Qwen3-0.6B",
        ),
        Choice(
            "Gemma 3 1B", "gemma3-1b-it-int4.litertlm",
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm",
            "about 0.6 GB", "Small; Google's licence must be accepted on Hugging Face first, so download it in your browser.",
            "https://huggingface.co/litert-community/Gemma3-1B-IT",
        ),
        Choice(
            "Gemma 4 E2B", "gemma-4-E2B-it.litertlm",
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            "about 2.6 GB", "The best writing; needs a recent phone with plenty of memory, and is slower.",
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
        ),
    )

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
    fun download(context: Context, choice: Choice, onProgress: (Float) -> Unit = {}): File {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val part = File(dir, choice.file + ".part")
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
            val code = conn.responseCode
            if (code in 300..399 && hops < 6) {
                val next = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                url = URL(URL(url), next).toString()
                if (!url.startsWith("https://")) throw IllegalStateException("The download was redirected somewhere unsafe")
                hops++
                continue
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
            val total = conn.contentLengthLong.takeIf { it > 0 }
            conn.inputStream.use { input -> part.outputStream().use { out -> copy(input, out, total, onProgress) } }
            if (total != null && part.length() != total) throw IllegalStateException("The download stopped early; try again")
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        return place(dir, part, choice.file)
    }

    private fun copy(input: java.io.InputStream, out: java.io.OutputStream, total: Long?, onProgress: (Float) -> Unit) {
        val buf = ByteArray(1 shl 20)
        var done = 0L
        var last = -1
        while (true) {
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
        return object : TextModel {
            // One answer at a time, each in a fresh conversation (no memory of earlier ones).
            override fun generate(prompt: String): String = synchronized(lock) {
                val config = ConversationConfig(samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.3))
                val text = if (noThinking) "$prompt /no_think" else prompt
                eng.createConversation(config).use { conversation -> conversation.sendMessage(text).toString() }
            }
        }
    }

    /** The engine on [backend], or null when it can't start there (a missing GPU library, say). */
    private fun start(context: Context, f: File, backend: Backend): Engine? {
        var e: Engine? = null
        return try {
            e = Engine(EngineConfig(modelPath = f.path, backend = backend, cacheDir = context.cacheDir.path))
            e.initialize()
            e
        } catch (t: Throwable) {
            runCatching { e?.close() }
            null
        }
    }
}
