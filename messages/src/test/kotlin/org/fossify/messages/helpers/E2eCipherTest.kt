package org.fossify.messages.helpers

import org.fossify.mesh.rns.RnsHkdf
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class E2eCipherTest {
    @Test fun differentLocalThreadIdsCanExchangeMessages() {
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val alice = generator.generateKeyPair()
        val bob = generator.generateKeyPair()
        val aliceSecret = KeyAgreement.getInstance("ECDH").run {
            init(alice.private); doPhase(bob.public, true); generateSecret()
        }
        val bobSecret = KeyAgreement.getInstance("ECDH").run {
            init(bob.private); doPhase(alice.public, true); generateSecret()
        }
        val outgoing = E2eCipher.prepareOutgoing(true, aliceSecret, "private message")
        assertTrue(outgoing.startsWith("E2E2:"))
        assertEquals("private message", E2eCipher.decrypt(bobSecret, outgoing, 42L))
        assertEquals("reply", E2eCipher.decrypt(aliceSecret, E2eCipher.encrypt(bobSecret, "reply"), 7L))
    }

    @Test fun missingKeyCannotDowngradeAnEncryptedConversation() {
        assertThrows(E2eCipher.KeyUnavailableException::class.java) {
            E2eCipher.prepareOutgoing(true, null, "secret wallet token")
        }
        assertEquals("ordinary message", E2eCipher.prepareOutgoing(false, null, "ordinary message"))
    }

    @Test fun explicitKeyExchangeCanStillBeSentWithoutSharedSecret() {
        assertEquals("E2EKEY1:public", E2eCipher.prepareOutgoing(true, null, "E2EKEY1:public"))
    }

    @Test fun legacyCiphertextStillDecryptsWithItsOriginalContext() {
        val secret = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(RnsHkdf.derive(32, secret, context = "7".toByteArray()), "AES"), GCMParameterSpec(128, nonce))
        val legacy = "E2E1:" + Base64.getEncoder().encodeToString(nonce + cipher.doFinal("old message".toByteArray()))
        assertEquals("old message", E2eCipher.decrypt(secret, legacy, 7L))
    }

    @Test fun tamperedAndTruncatedCiphertextAreRejected() {
        val secret = ByteArray(32)
        val encrypted = E2eCipher.encrypt(secret, "hello")
        val payload = Base64.getDecoder().decode(encrypted.removePrefix(E2eCipher.PREFIX))
        payload[payload.lastIndex] = (payload.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) {
            E2eCipher.decrypt(secret, E2eCipher.PREFIX + Base64.getEncoder().encodeToString(payload), 1L)
        }
        assertThrows(IllegalArgumentException::class.java) { E2eCipher.decrypt(secret, "E2E2:AA==", 1L) }
    }
}
