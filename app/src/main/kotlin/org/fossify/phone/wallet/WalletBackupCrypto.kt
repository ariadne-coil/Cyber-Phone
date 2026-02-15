package org.fossify.phone.wallet

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object WalletBackupCrypto {
    private const val VERSION = 2
    private const val KDF_NAME = "PBKDF2WithHmacSHA256"
    private const val CIPHER_NAME = "AES-256-GCM"
    private const val KDF_ITERATIONS = 210_000
    private const val KEY_BYTES = 32
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    data class FederationSnapshot(
        val id: String,
        val name: String,
        val kind: String,
        val network: String?,
        val invite: String,
        val website: String?,
        val description: String?,
        val esploraUrl: String?,
        val rgsUrl: String?,
        val lsps1NodeId: String?,
        val lsps1Address: String?,
        val lsps1Token: String?,
    ) {
        fun toFederationEntry(): FederationEntry {
            return FederationEntry(
                id = id,
                name = name,
                kind = kind,
                invite = invite,
                network = network,
                website = website,
                description = description,
                esploraUrl = esploraUrl,
                rgsUrl = rgsUrl,
                lsps1NodeId = lsps1NodeId,
                lsps1Address = lsps1Address,
                lsps1Token = lsps1Token,
            )
        }
    }

    data class Envelope(
        val walletType: String,
        val createdAtMs: Long,
        val federation: FederationSnapshot,
        val kdfIterations: Int,
        val salt: ByteArray,
        val iv: ByteArray,
        val ciphertext: ByteArray,
    )

    fun createEnvelopeJson(
        passphrase: CharArray,
        walletType: String,
        federation: FederationEntry,
        plaintext: ByteArray,
    ): JSONObject {
        val normalizedWalletType = walletType.trim().lowercase()
        require(normalizedWalletType == "ldk" || normalizedWalletType == "fedimint")

        val salt = randomBytes(SALT_BYTES)
        val iv = randomBytes(IV_BYTES)
        val key = deriveKey(passphrase, salt, KDF_ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)

        val snapshot = JSONObject().apply {
            put("id", federation.id)
            put("name", federation.name)
            put("kind", federation.kind)
            put("network", federation.network)
            put("invite", federation.invite)
            put("website", federation.website)
            put("description", federation.description)
            put("esplora_url", federation.esploraUrl)
            put("rgs_url", federation.rgsUrl)
            put("lsps1_node_id", federation.lsps1NodeId)
            put("lsps1_address", federation.lsps1Address)
            put("lsps1_token", federation.lsps1Token)
        }

        return JSONObject().apply {
            put("version", VERSION)
            put("wallet_type", normalizedWalletType)
            put("created_at", System.currentTimeMillis())
            put("federation", snapshot)
            put(
                "kdf",
                JSONObject().apply {
                    put("name", KDF_NAME)
                    put("iterations", KDF_ITERATIONS)
                    put("salt_b64", toBase64(salt))
                }
            )
            put(
                "cipher",
                JSONObject().apply {
                    put("name", CIPHER_NAME)
                    put("iv_b64", toBase64(iv))
                    put("tag_bits", TAG_BITS)
                }
            )
            put("ciphertext_b64", toBase64(ciphertext))
        }
    }

    fun parseEnvelope(jsonText: String): Envelope? {
        val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        if (obj.optInt("version", -1) != VERSION) return null

        val walletType = obj.optString("wallet_type").orEmpty().trim().lowercase()
        if (walletType != "ldk" && walletType != "fedimint") return null

        val fed = obj.optJSONObject("federation") ?: return null
        val snapshot = FederationSnapshot(
            id = fed.optString("id").orEmpty().trim(),
            name = fed.optString("name").orEmpty().trim(),
            kind = fed.optString("kind").orEmpty().trim().ifBlank { "ldk" },
            network = fed.optString("network").orEmpty().trim().ifBlank { null },
            invite = fed.optString("invite").orEmpty().trim(),
            website = fed.optString("website").orEmpty().trim().ifBlank { null },
            description = fed.optString("description").orEmpty().trim().ifBlank { null },
            esploraUrl = fed.optString("esplora_url").orEmpty().trim().ifBlank { null },
            rgsUrl = fed.optString("rgs_url").orEmpty().trim().ifBlank { null },
            lsps1NodeId = fed.optString("lsps1_node_id").orEmpty().trim().ifBlank { null },
            lsps1Address = fed.optString("lsps1_address").orEmpty().trim().ifBlank { null },
            lsps1Token = fed.optString("lsps1_token").orEmpty().trim().ifBlank { null },
        )
        if (snapshot.id.isBlank() || snapshot.name.isBlank()) return null

        val kdf = obj.optJSONObject("kdf") ?: return null
        if (!kdf.optString("name").equals(KDF_NAME, ignoreCase = true)) return null
        val iterations = kdf.optInt("iterations", -1)
        if (iterations < 100_000) return null
        val salt = fromBase64(kdf.optString("salt_b64").orEmpty()) ?: return null

        val cipher = obj.optJSONObject("cipher") ?: return null
        if (!cipher.optString("name").equals(CIPHER_NAME, ignoreCase = true)) return null
        val iv = fromBase64(cipher.optString("iv_b64").orEmpty()) ?: return null
        val ciphertext = fromBase64(obj.optString("ciphertext_b64").orEmpty()) ?: return null

        return Envelope(
            walletType = walletType,
            createdAtMs = obj.optLong("created_at", 0L),
            federation = snapshot,
            kdfIterations = iterations,
            salt = salt,
            iv = iv,
            ciphertext = ciphertext,
        )
    }

    fun decryptEnvelope(passphrase: CharArray, envelope: Envelope): ByteArray {
        val key = deriveKey(passphrase, envelope.salt, envelope.kdfIterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, envelope.iv))
        return cipher.doFinal(envelope.ciphertext)
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BYTES * 8)
        return try {
            val factory = SecretKeyFactory.getInstance(KDF_NAME)
            val encoded = factory.generateSecret(spec).encoded
            SecretKeySpec(encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun randomBytes(size: Int): ByteArray {
        val out = ByteArray(size)
        SecureRandom().nextBytes(out)
        return out
    }

    private fun toBase64(value: ByteArray): String {
        return Base64.encodeToString(value, Base64.NO_WRAP)
    }

    private fun fromBase64(value: String): ByteArray? {
        val text = value.trim()
        if (text.isBlank()) return null
        return runCatching { Base64.decode(text, Base64.DEFAULT) }.getOrNull()
    }
}

