package org.fossify.messages.helpers

import org.fossify.mesh.rns.RnsHkdf
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Wire encryption is independent of either phone's local database identifiers. */
object E2eCipher {
    const val PREFIX = "E2E2:"
    private const val NONCE_BYTES = 12
    private val context = "CyberPhone/SMS/E2E/v2".toByteArray(Charsets.UTF_8)

    class KeyUnavailableException : IllegalStateException("Encryption key unavailable")

    fun isEncrypted(text: String) = text.startsWith(PREFIX) || text.startsWith(E2E_ENCRYPTED_MESSAGE_PREFIX)

    fun prepareOutgoing(encrypted: Boolean, secret: ByteArray?, text: String): String {
        if (!encrypted || text.startsWith(E2E_KEY_MESSAGE_PREFIX) || isEncrypted(text) ||
            text.startsWith(MESH_ADDRESS_MESSAGE_PREFIX)
        ) {
            return text
        }
        if (secret == null || secret.isEmpty()) throw KeyUnavailableException()
        return encrypt(secret, text)
    }

    fun encrypt(secret: ByteArray, text: String): String {
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(secret), GCMParameterSpec(128, nonce))
        cipher.updateAAD(context)
        return PREFIX + Base64.getEncoder().encodeToString(nonce + cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    fun decrypt(secret: ByteArray, text: String, legacyThreadId: Long): String {
        val version2 = text.startsWith(PREFIX)
        require(version2 || text.startsWith(E2E_ENCRYPTED_MESSAGE_PREFIX)) { "Unknown encrypted message version" }
        val payload = Base64.getDecoder().decode(text.removePrefix(if (version2) PREFIX else E2E_ENCRYPTED_MESSAGE_PREFIX))
        require(payload.size >= NONCE_BYTES + 16) { "Truncated encrypted message" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val aesKey = if (version2) key(secret) else SecretKeySpec(
            RnsHkdf.derive(32, secret, context = legacyThreadId.toString().toByteArray(Charsets.UTF_8)), "AES"
        )
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, payload.copyOfRange(0, NONCE_BYTES)))
        if (version2) cipher.updateAAD(context)
        return String(cipher.doFinal(payload.copyOfRange(NONCE_BYTES, payload.size)), Charsets.UTF_8)
    }

    private fun key(secret: ByteArray) = SecretKeySpec(RnsHkdf.derive(32, secret, context = context), "AES")
}
