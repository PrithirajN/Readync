package io.github.prithirajn.readync.source

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import io.github.prithirajn.readync.data.KoSshAccount
import io.github.prithirajn.readync.net.AuthException
import io.github.prithirajn.readync.net.NetworkException
import java.io.ByteArrayOutputStream
import java.io.Closeable

/**
 * KOReader on another device (Kindle, Kobo, PocketBook) through KOReader's own SSH server
 * (Tools › Network › SSH server, port 2222, user root): files read and written over SFTP, and a
 * few shell commands (finding files, renaming one into place). One connection per use.
 */
class KoSshClient(private val account: KoSshAccount) : Closeable {
    private var session: Session? = null
    private var sftp: ChannelSftp? = null

    fun connect(): KoSshClient {
        val jsch = JSch()
        account.privateKey?.let { jsch.addIdentity("readync", it.toByteArray(), null, null) }
        val s = jsch.getSession(account.user.ifBlank { "root" }, account.host.trim(), account.port)
        s.setConfig("StrictHostKeyChecking", "no")
        // KOReader's dropbear with "Login without password" takes any password; a key needs none.
        if (account.noPassword) s.setPassword("readync")
        s.setConfig("PreferredAuthentications", if (account.privateKey != null) "publickey,password,keyboard-interactive" else "password,keyboard-interactive")
        s.timeout = 15_000
        try {
            s.connect(8_000)
        } catch (e: JSchException) {
            val m = e.message.orEmpty()
            throw when {
                m.contains("Auth fail", true) || m.contains("auth cancel", true) ->
                    AuthException("KOReader didn't let Readync in. Add Readync's key to KOReader (settings/SSH/authorized_keys), or turn on its \"Login without password\".")
                else -> NetworkException("Couldn't reach KOReader at ${account.host}:${account.port} (${m.ifBlank { "no answer" }}). Is the device awake, on this Wi-Fi, with KOReader's SSH server on?", e)
            }
        }
        session = s
        return this
    }

    private fun channel(): ChannelSftp {
        sftp?.takeIf { it.isConnected }?.let { return it }
        val c = (session ?: throw IllegalStateException("Not connected")).openChannel("sftp") as ChannelSftp
        c.connect(8_000)
        sftp = c
        return c
    }

    /** Runs [command] in KOReader's device shell; its output (and the exit code). */
    fun exec(command: String, timeoutMs: Long = 60_000): Pair<String, Int> {
        val c = (session ?: throw IllegalStateException("Not connected")).openChannel("exec") as ChannelExec
        c.setCommand(command)
        val out = ByteArrayOutputStream()
        c.outputStream = out
        c.setErrStream(ByteArrayOutputStream())
        c.connect(8_000)
        val until = System.currentTimeMillis() + timeoutMs
        while (!c.isClosed && System.currentTimeMillis() < until) Thread.sleep(40)
        val code = c.exitStatus
        c.disconnect()
        return out.toString(Charsets.UTF_8.name()) to code
    }

    fun read(path: String): ByteArray {
        val out = ByteArrayOutputStream()
        channel().get(path, out)
        return out.toByteArray()
    }

    fun readOrNull(path: String): ByteArray? = runCatching { read(path) }.getOrNull()

    /** [length] bytes at [offset] (fewer at the end), for a file's KOReader ID without reading all of it. */
    fun readRange(path: String, offset: Long, length: Int): ByteArray? = runCatching {
        channel().get(path, null, offset).use { inp ->
            val buf = ByteArray(length)
            var n = 0
            while (n < length) {
                val r = inp.read(buf, n, length - n)
                if (r < 0) break
                n += r
            }
            if (n == length) buf else buf.copyOf(n)
        }
    }.getOrNull()

    /** Modified time (seconds) and size, or null when it isn't there. */
    fun stat(path: String): Pair<Long, Long>? = runCatching { channel().stat(path).let { it.mTime.toLong() to it.size } }.getOrNull()

    /** Writes [bytes] to [path] whole: to a part file first, then moved into place, so KOReader never reads half a file. */
    fun write(path: String, bytes: ByteArray) {
        val dir = path.substringBeforeLast('/')
        exec("mkdir -p ${q(dir)}")
        val part = "$path.readync-part"
        channel().put(bytes.inputStream(), part, ChannelSftp.OVERWRITE)
        val (_, code) = exec("mv -f ${q(part)} ${q(path)}")
        if (code != 0) {
            runCatching { channel().rm(path) }
            channel().rename(part, path)
        }
    }

    override fun close() {
        runCatching { sftp?.disconnect() }
        runCatching { session?.disconnect() }
        sftp = null
        session = null
    }

    companion object {
        /** A new key for Readync (ECDSA P-256): the private key (kept sealed) and the line for KOReader's authorized_keys. */
        fun newKey(): Pair<String, String> {
            val kp = KeyPair.genKeyPair(JSch(), KeyPair.ECDSA, 256)
            val priv = ByteArrayOutputStream().also { kp.writePrivateKey(it) }.toString(Charsets.UTF_8.name())
            val pub = ByteArrayOutputStream().also { kp.writePublicKey(it, "readync") }.toString(Charsets.UTF_8.name()).trim()
            kp.dispose()
            return priv to pub
        }

        /** [s] quoted for the device's shell. */
        fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    }
}
