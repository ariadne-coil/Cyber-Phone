package org.fossify.messages.helpers

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts selected SharedPreferences values at rest with an Android Keystore AES-GCM key.
 *
 * We only use this for sensitive local material (E2E private/shared secrets).
 */
object SensitivePrefs {
    private const val TAG = "SensitivePrefs"
    private const val KEYSTORE_TYPE = "AndroidKeyStore"
    private const val KEY_ALIAS = "cyber_phone_messages_sensitive_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "enc:v1:"
    private const val TAG_BITS = 128
    private const val CRYPTO_RETRY_COUNT = 3
    private const val CRYPTO_RETRY_DELAY_MS = 80L

    private val lock = Any()

    fun getString(prefs: SharedPreferences, key: String, defaultValue: String = ""): String {
        val stored = prefs.getString(key, null) ?: return defaultValue
        if (stored.isEmpty()) return ""

        if (!stored.startsWith(PREFIX)) {
            // Legacy plaintext value: migrate in place on first read.
            runCatching { putString(prefs, key, stored) }
            return stored
        }

        val decoded = decryptString(stored.removePrefix(PREFIX))
        if (decoded != null) return decoded

        Log.w(TAG, "Failed decrypting sensitive preference value")
        return defaultValue
    }

    fun putString(prefs: SharedPreferences, key: String, value: String) {
        if (value.isEmpty()) {
            prefs.edit().putString(key, "").apply()
            return
        }

        val encrypted = encryptString(value)
        if (encrypted != null) {
            prefs.edit().putString(key, PREFIX + encrypted).apply()
            return
        }

        // Last-resort fallback to preserve functionality on unsupported keystore devices.
        Log.w(TAG, "Falling back to plaintext storage for sensitive preference value")
        prefs.edit().putString(key, value).apply()
    }

    private fun encryptString(value: String): String? {
        repeat(CRYPTO_RETRY_COUNT) { attempt ->
            val encoded = synchronized(lock) {
                runCatching {
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
                    val iv = cipher.iv
                    val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))

                    val payload = ByteArray(1 + iv.size + ciphertext.size)
                    payload[0] = iv.size.toByte()
                    System.arraycopy(iv, 0, payload, 1, iv.size)
                    System.arraycopy(ciphertext, 0, payload, 1 + iv.size, ciphertext.size)
                    Base64.encodeToString(payload, Base64.NO_WRAP)
                }.getOrElse {
                    Log.w(TAG, "encryptString failed (attempt ${attempt + 1}/$CRYPTO_RETRY_COUNT)", it)
                    null
                }
            }
            if (encoded != null) {
                return encoded
            }
            if (attempt < CRYPTO_RETRY_COUNT - 1) {
                try {
                    Thread.sleep(CRYPTO_RETRY_DELAY_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
        return null
    }

    private fun decryptString(value: String): String? {
        repeat(CRYPTO_RETRY_COUNT) { attempt ->
            val decoded = synchronized(lock) {
                runCatching {
                    val payload = Base64.decode(value, Base64.DEFAULT)
                    if (payload.isEmpty()) {
                        null
                    } else {
                        val ivSize = payload[0].toInt() and 0xFF
                        if (ivSize <= 0 || payload.size <= 1 + ivSize) {
                            null
                        } else {
                            val iv = payload.copyOfRange(1, 1 + ivSize)
                            val ciphertext = payload.copyOfRange(1 + ivSize, payload.size)

                            val cipher = Cipher.getInstance(TRANSFORMATION)
                            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(TAG_BITS, iv))
                            val plaintext = cipher.doFinal(ciphertext)
                            String(plaintext, Charsets.UTF_8)
                        }
                    }
                }.getOrElse {
                    Log.w(TAG, "decryptString failed (attempt ${attempt + 1}/$CRYPTO_RETRY_COUNT)", it)
                    null
                }
            }
            if (decoded != null) {
                return decoded
            }
            if (attempt < CRYPTO_RETRY_COUNT - 1) {
                try {
                    Thread.sleep(CRYPTO_RETRY_DELAY_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
        return null
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_TYPE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
