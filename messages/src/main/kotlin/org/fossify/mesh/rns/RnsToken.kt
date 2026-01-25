package org.fossify.mesh.rns

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class RnsToken(key: ByteArray) {
    companion object {
        const val TOKEN_OVERHEAD = 48
    }

    private val signingKey: ByteArray
    private val encryptionKey: ByteArray
    private val rng = SecureRandom()

    init {
        when (key.size) {
            32 -> {
                signingKey = key.copyOfRange(0, 16)
                encryptionKey = key.copyOfRange(16, 32)
            }
            64 -> {
                signingKey = key.copyOfRange(0, 32)
                encryptionKey = key.copyOfRange(32, 64)
            }
            else -> error("Token key must be 128 or 256 bits, not ${key.size * 8}")
        }
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(16)
        rng.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintext)
        val signedParts = iv + ciphertext
        val hmac = RnsHmac.sha256(signingKey, signedParts)
        return signedParts + hmac
    }

    fun decrypt(token: ByteArray): ByteArray {
        if (token.size <= 32) {
            throw IllegalArgumentException("Token too short for HMAC verification")
        }
        val data = token.copyOfRange(0, token.size - 32)
        val receivedHmac = token.copyOfRange(token.size - 32, token.size)
        val expectedHmac = RnsHmac.sha256(signingKey, data)
        if (!MessageDigest.isEqual(receivedHmac, expectedHmac)) {
            throw IllegalArgumentException("Token HMAC was invalid")
        }
        val iv = data.copyOfRange(0, 16)
        val ciphertext = data.copyOfRange(16, data.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ciphertext)
    }
}
