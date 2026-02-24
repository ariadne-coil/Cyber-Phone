package org.fossify.phone.wallet

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
class WalletBackupCryptoTest {
    private val passphrase = "correct horse battery staple".toCharArray()

    private fun sampleFederation(): FederationEntry {
        return FederationEntry(
            id = "fedimint-bitcoin-principles",
            name = "Bitcoin Principles",
            kind = "fedimint",
            invite = "fed11qgqzygrhwden5te0v9cxjtnzd96xxmmfdec8y6twvd5hqmr9wvhxuet59upqzg9jzp5v",
            network = "bitcoin",
            website = "https://meta.dev.fedibtc.com/meta.json",
            description = "Welcome to the Bitcoin Principles Federation!",
            esploraUrl = "https://blockstream.info/api",
            rgsUrl = "https://rapidsync.lightningdevkit.org/snapshot",
            lsps1NodeId = "03abc",
            lsps1Address = "1.2.3.4:9735",
            lsps1Token = "token-1",
        )
    }

    @Test
    fun v3_roundTrip_encryptParseDecrypt_succeeds() {
        val plaintext = "wallet-backup-secret".toByteArray()
        val envelopeJson = WalletBackupCrypto.createEnvelopeJson(
            passphrase = passphrase,
            walletType = "fedimint",
            federation = sampleFederation(),
            plaintext = plaintext,
        )

        val parsed = WalletBackupCrypto.parseEnvelope(envelopeJson.toString())
        assertNotNull(parsed)
        val decrypted = WalletBackupCrypto.decryptEnvelope(passphrase, parsed!!)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun v3_tamperedFederationMetadata_failsDecrypt() {
        val plaintext = "wallet-backup-secret".toByteArray()
        val envelopeJson = WalletBackupCrypto.createEnvelopeJson(
            passphrase = passphrase,
            walletType = "fedimint",
            federation = sampleFederation(),
            plaintext = plaintext,
        )

        envelopeJson.getJSONObject("federation").put("name", "Tampered Federation")
        val parsed = WalletBackupCrypto.parseEnvelope(envelopeJson.toString())
        assertNotNull(parsed)

        val failed = runCatching {
            WalletBackupCrypto.decryptEnvelope(passphrase, parsed!!)
        }.isFailure
        assertTrue(failed)
    }

    @Test
    fun parseEnvelope_acceptsLegacyV2_andDecryptsWithoutAad() {
        val plaintext = "legacy-wallet-backup-secret".toByteArray()
        val json = legacyV2EnvelopeJson(plaintext)
        val parsed = WalletBackupCrypto.parseEnvelope(json.toString())
        assertNotNull(parsed)
        val decrypted = WalletBackupCrypto.decryptEnvelope(passphrase, parsed!!)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun parseEnvelope_rejectsOutOfBoundsKdfIterations() {
        val low = validEnvelopeJson().apply {
            getJSONObject("kdf").put("iterations", 99_999)
        }
        val high = validEnvelopeJson().apply {
            getJSONObject("kdf").put("iterations", 500_001)
        }

        assertNull(WalletBackupCrypto.parseEnvelope(low.toString()))
        assertNull(WalletBackupCrypto.parseEnvelope(high.toString()))
    }

    @Test
    fun parseEnvelope_rejectsInvalidCryptoFieldSizes() {
        val invalidSalt = validEnvelopeJson().apply {
            getJSONObject("kdf").put("salt_b64", Base64.getEncoder().encodeToString(ByteArray(15)))
        }
        val invalidIv = validEnvelopeJson().apply {
            getJSONObject("cipher").put("iv_b64", Base64.getEncoder().encodeToString(ByteArray(11)))
        }
        val invalidTagBits = validEnvelopeJson().apply {
            getJSONObject("cipher").put("tag_bits", 120)
        }
        val emptyCiphertext = validEnvelopeJson().apply {
            put("ciphertext_b64", Base64.getEncoder().encodeToString(ByteArray(0)))
        }

        assertNull(WalletBackupCrypto.parseEnvelope(invalidSalt.toString()))
        assertNull(WalletBackupCrypto.parseEnvelope(invalidIv.toString()))
        assertNull(WalletBackupCrypto.parseEnvelope(invalidTagBits.toString()))
        assertNull(WalletBackupCrypto.parseEnvelope(emptyCiphertext.toString()))
    }

    private fun validEnvelopeJson(): JSONObject {
        return JSONObject().apply {
            put("version", 3)
            put("wallet_type", "fedimint")
            put("created_at", 1L)
            put(
                "federation",
                JSONObject().apply {
                    put("id", "fed")
                    put("name", "Fed")
                    put("kind", "fedimint")
                    put("invite", "fed11abc")
                },
            )
            put(
                "kdf",
                JSONObject().apply {
                    put("name", "PBKDF2WithHmacSHA256")
                    put("iterations", 210000)
                    put("salt_b64", Base64.getEncoder().encodeToString(ByteArray(16) { 1 }))
                },
            )
            put(
                "cipher",
                JSONObject().apply {
                    put("name", "AES-256-GCM")
                    put("iv_b64", Base64.getEncoder().encodeToString(ByteArray(12) { 2 }))
                    put("tag_bits", 128)
                },
            )
            put("ciphertext_b64", Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4)))
        }
    }

    private fun legacyV2EnvelopeJson(plaintext: ByteArray): JSONObject {
        val salt = ByteArray(16) { (it + 1).toByte() }
        val iv = ByteArray(12) { (it + 2).toByte() }
        val ciphertext = legacyV2Encrypt(passphrase, salt, iv, plaintext)

        return JSONObject().apply {
            put("version", 2)
            put("wallet_type", "fedimint")
            put("created_at", 1L)
            put(
                "federation",
                JSONObject().apply {
                    put("id", "fed")
                    put("name", "Fed")
                    put("kind", "fedimint")
                    put("invite", "fed11abc")
                },
            )
            put(
                "kdf",
                JSONObject().apply {
                    put("name", "PBKDF2WithHmacSHA256")
                    put("iterations", 210000)
                    put("salt_b64", Base64.getEncoder().encodeToString(salt))
                },
            )
            put(
                "cipher",
                JSONObject().apply {
                    put("name", "AES-256-GCM")
                    put("iv_b64", Base64.getEncoder().encodeToString(iv))
                    put("tag_bits", 128)
                },
            )
            put("ciphertext_b64", Base64.getEncoder().encodeToString(ciphertext))
        }
    }

    private fun legacyV2Encrypt(
        passphrase: CharArray,
        salt: ByteArray,
        iv: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, 210_000, 32 * 8)
        val key = try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(plaintext)
    }
}
