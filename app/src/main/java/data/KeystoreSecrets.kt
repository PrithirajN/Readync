package io.github.prithirajn.readync.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM with a key that lives in the Android Keystore and never leaves it. Sealed values
 * are "v1:" + base64(iv + ciphertext). If the key is gone (app data cleared, device reset)
 * [open] returns null and the user signs in again.
 */
class KeystoreSecrets : Secrets {
    private val alias = "tracker-sync-secrets"

    /** The key, looked up in the Keystore once (each lookup is a call to the system's keystore). */
    @Volatile private var cachedKey: SecretKey? = null

    private fun key(): SecretKey = cachedKey ?: synchronized(this) { cachedKey ?: lookUp().also { cachedKey = it } }

    private fun lookUp(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    // Each sealing or opening is a round trip to the system's keystore: values already opened or
    // sealed this run are remembered (they're in memory as plain text in the app's state anyway), so
    // starting reads each once and saving, which happens on every change, doesn't seal them all again.
    private val opened = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val sealedOf = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun seal(plain: String): String {
        sealedOf[plain]?.let { return it }
        val t0 = System.nanoTime()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val sealed = "v1:" + Base64.getEncoder().encodeToString(iv + data)
        sealedOf[plain] = sealed
        opened[sealed] = plain
        spentNanos.addAndGet(System.nanoTime() - t0)
        return sealed
    }

    override fun open(sealed: String): String? {
        opened[sealed]?.let { return it }
        val t0 = System.nanoTime()
        return try {
            if (!sealed.startsWith("v1:")) {
                null
            } else {
                val bytes = Base64.getDecoder().decode(sealed.substring(3))
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
                String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8).also { plain ->
                    opened[sealed] = plain
                    sealedOf[plain] = sealed
                }
            }
        } catch (e: Exception) {
            null
        } finally {
            spentNanos.addAndGet(System.nanoTime() - t0)
        }
    }

    companion object {
        /** Time spent in the keystore so far (for the start's timings). */
        val spentNanos = java.util.concurrent.atomic.AtomicLong()
    }
}
