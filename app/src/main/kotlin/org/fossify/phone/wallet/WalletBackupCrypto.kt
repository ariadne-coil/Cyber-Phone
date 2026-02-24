package org.fossify.phone.wallet

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object WalletBackupCrypto {
    private const val CURRENT_VERSION = 3
    private const val LEGACY_VERSION = 2
    private const val METADATA_AAD_VERSION = 1
    private const val KDF_NAME = "PBKDF2WithHmacSHA256"
    private const val CIPHER_NAME = "AES-256-GCM"
    private const val KDF_ITERATIONS = 210_000
    private const val MIN_KDF_ITERATIONS = 100_000
    private const val MAX_KDF_ITERATIONS = 500_000
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
        val version: Int,
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
        val snapshot = normalizedSnapshotFromFederation(federation)
        val createdAtMs = System.currentTimeMillis()

        val salt = randomBytes(SALT_BYTES)
        val iv = randomBytes(IV_BYTES)
        val key = deriveKey(passphrase, salt, KDF_ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(buildMetadataAad(normalizedWalletType, createdAtMs, snapshot))
        val ciphertext = cipher.doFinal(plaintext)

        return JSONObject().apply {
            put("version", CURRENT_VERSION)
            put("wallet_type", normalizedWalletType)
            put("created_at", createdAtMs)
            put("federation", snapshot.toJson())
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
        val version = obj.optInt("version", -1)
        if (version != CURRENT_VERSION && version != LEGACY_VERSION) return null

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
        if (iterations < MIN_KDF_ITERATIONS || iterations > MAX_KDF_ITERATIONS) return null
        val salt = fromBase64(kdf.optString("salt_b64").orEmpty()) ?: return null
        if (salt.size != SALT_BYTES) return null

        val cipher = obj.optJSONObject("cipher") ?: return null
        if (!cipher.optString("name").equals(CIPHER_NAME, ignoreCase = true)) return null
        if (cipher.optInt("tag_bits", TAG_BITS) != TAG_BITS) return null
        val iv = fromBase64(cipher.optString("iv_b64").orEmpty()) ?: return null
        if (iv.size != IV_BYTES) return null
        val ciphertext = fromBase64(obj.optString("ciphertext_b64").orEmpty()) ?: return null
        if (ciphertext.isEmpty()) return null
        val createdAtMs = obj.optLong("created_at", 0L)
        if (createdAtMs <= 0L) return null

        return Envelope(
            version = version,
            walletType = walletType,
            createdAtMs = createdAtMs,
            federation = snapshot,
            kdfIterations = iterations,
            salt = salt,
            iv = iv,
            ciphertext = ciphertext,
        )
    }

    fun decryptEnvelope(passphrase: CharArray, envelope: Envelope): ByteArray {
        if (envelope.version != CURRENT_VERSION && envelope.version != LEGACY_VERSION) {
            throw IllegalArgumentException("Unsupported wallet backup version: ${envelope.version}")
        }
        val key = deriveKey(passphrase, envelope.salt, envelope.kdfIterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, envelope.iv))
        if (envelope.version >= CURRENT_VERSION) {
            cipher.updateAAD(
                buildMetadataAad(
                    walletType = envelope.walletType,
                    createdAtMs = envelope.createdAtMs,
                    snapshot = envelope.federation
                )
            )
        }
        return cipher.doFinal(envelope.ciphertext)
    }

    private fun normalizedSnapshotFromFederation(federation: FederationEntry): FederationSnapshot {
        fun normalized(value: String?): String? {
            val trimmed = value?.trim().orEmpty()
            return trimmed.ifBlank { null }
        }

        return FederationSnapshot(
            id = federation.id.trim(),
            name = federation.name.trim(),
            kind = federation.kind.trim().ifBlank { "ldk" },
            network = normalized(federation.network),
            invite = federation.invite.trim(),
            website = normalized(federation.website),
            description = normalized(federation.description),
            esploraUrl = normalized(federation.esploraUrl),
            rgsUrl = normalized(federation.rgsUrl),
            lsps1NodeId = normalized(federation.lsps1NodeId),
            lsps1Address = normalized(federation.lsps1Address),
            lsps1Token = normalized(federation.lsps1Token),
        )
    }

    private fun FederationSnapshot.toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("kind", kind)
            put("network", network)
            put("invite", invite)
            put("website", website)
            put("description", description)
            put("esplora_url", esploraUrl)
            put("rgs_url", rgsUrl)
            put("lsps1_node_id", lsps1NodeId)
            put("lsps1_address", lsps1Address)
            put("lsps1_token", lsps1Token)
        }
    }

    private fun buildMetadataAad(
        walletType: String,
        createdAtMs: Long,
        snapshot: FederationSnapshot,
    ): ByteArray {
        val out = ByteArrayOutputStream(512)
        writeAadField(out, "aad_version", METADATA_AAD_VERSION.toString())
        writeAadField(out, "wallet_type", walletType.trim().lowercase())
        writeAadField(out, "created_at", createdAtMs.toString())

        writeAadField(out, "fed_id", snapshot.id)
        writeAadField(out, "fed_name", snapshot.name)
        writeAadField(out, "fed_kind", snapshot.kind)
        writeAadField(out, "fed_invite", snapshot.invite)

        writeOptionalAadField(out, "fed_network", snapshot.network)
        writeOptionalAadField(out, "fed_website", snapshot.website)
        writeOptionalAadField(out, "fed_description", snapshot.description)
        writeOptionalAadField(out, "fed_esplora_url", snapshot.esploraUrl)
        writeOptionalAadField(out, "fed_rgs_url", snapshot.rgsUrl)
        writeOptionalAadField(out, "fed_lsps1_node_id", snapshot.lsps1NodeId)
        writeOptionalAadField(out, "fed_lsps1_address", snapshot.lsps1Address)
        writeOptionalAadField(out, "fed_lsps1_token", snapshot.lsps1Token)
        return out.toByteArray()
    }

    private fun writeOptionalAadField(out: ByteArrayOutputStream, name: String, value: String?) {
        if (value == null) {
            writeAadField(out, "${name}_set", "0")
            writeAadField(out, name, "")
        } else {
            writeAadField(out, "${name}_set", "1")
            writeAadField(out, name, value)
        }
    }

    private fun writeAadField(out: ByteArrayOutputStream, name: String, value: String) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val valueBytes = value.toByteArray(Charsets.UTF_8)
        writeInt(out, nameBytes.size)
        out.write(nameBytes)
        writeInt(out, valueBytes.size)
        out.write(valueBytes)
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
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
