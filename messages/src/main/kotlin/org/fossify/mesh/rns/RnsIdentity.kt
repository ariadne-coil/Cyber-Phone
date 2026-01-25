package org.fossify.mesh.rns

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

class RnsIdentity private constructor(
    val publicKey: ByteArray,
    val privateKey: ByteArray?
) {
    companion object {
        const val KEY_SIZE = 64
        private const val KEY_HALF = 32
        private const val DERIVED_KEY_LENGTH = 64

        fun generate(): RnsIdentity {
            val rng = SecureRandom()
            val xPrivate = X25519PrivateKeyParameters(rng)
            val xPublic = xPrivate.generatePublicKey()
            val edPrivate = Ed25519PrivateKeyParameters(rng)
            val edPublic = edPrivate.generatePublicKey()
            val publicKey = xPublic.encoded + edPublic.encoded
            val privateKey = xPrivate.encoded + edPrivate.encoded
            return RnsIdentity(publicKey = publicKey, privateKey = privateKey)
        }

        fun fromPublic(publicKey: ByteArray): RnsIdentity {
            require(publicKey.size == KEY_SIZE) { "Public key must be 64 bytes" }
            return RnsIdentity(publicKey = publicKey, privateKey = null)
        }

        fun fromPrivate(privateKey: ByteArray): RnsIdentity {
            require(privateKey.size == KEY_SIZE) { "Private key must be 64 bytes" }
            val xPrivate = X25519PrivateKeyParameters(privateKey, 0)
            val edPrivate = Ed25519PrivateKeyParameters(privateKey, KEY_HALF)
            val publicKey = xPrivate.generatePublicKey().encoded + edPrivate.generatePublicKey().encoded
            return RnsIdentity(publicKey = publicKey, privateKey = privateKey)
        }
    }

    val hash: ByteArray = RnsHash.truncatedHash(publicKey)
    val hexHash: String = RnsHex.encode(hash)

    val x25519Public: ByteArray = publicKey.copyOfRange(0, KEY_HALF)
    val ed25519Public: ByteArray = publicKey.copyOfRange(KEY_HALF, KEY_SIZE)
    val x25519Private: ByteArray? = privateKey?.copyOfRange(0, KEY_HALF)
    val ed25519Private: ByteArray? = privateKey?.copyOfRange(KEY_HALF, KEY_SIZE)

    fun sign(data: ByteArray): ByteArray {
        val edPrivate = ed25519Private ?: error("Identity does not contain private key")
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(edPrivate, 0))
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    fun verify(data: ByteArray, signature: ByteArray): Boolean {
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(ed25519Public, 0))
        signer.update(data, 0, data.size)
        return signer.verifySignature(signature)
    }

    fun deriveSharedSecret(peerPublicKey: ByteArray): ByteArray {
        val xPrivate = x25519Private ?: error("Identity does not contain private key")
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(xPrivate, 0))
        val secret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(peerPublicKey, 0), secret, 0)
        return secret
    }

    fun encrypt(plaintext: ByteArray, ratchetPublicKey: ByteArray? = null): ByteArray {
        val targetPublicKey = ratchetPublicKey ?: x25519Public
        val ephemeral = X25519PrivateKeyParameters(SecureRandom())
        val agreement = X25519Agreement()
        agreement.init(ephemeral)
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(targetPublicKey, 0), shared, 0)
        val derivedKey = RnsHkdf.derive(
            length = DERIVED_KEY_LENGTH,
            deriveFrom = shared,
            salt = hash,
            context = null
        )
        val token = RnsToken(derivedKey)
        val ciphertext = token.encrypt(plaintext)
        return ephemeral.generatePublicKey().encoded + ciphertext
    }

    fun decrypt(ciphertextToken: ByteArray, ratchets: List<ByteArray>? = null): ByteArray? {
        val xPrivate = x25519Private ?: error("Identity does not contain private key")
        if (ciphertextToken.size <= KEY_HALF) return null
        val peerPubBytes = ciphertextToken.copyOfRange(0, KEY_HALF)
        val ciphertext = ciphertextToken.copyOfRange(KEY_HALF, ciphertextToken.size)

        val keysToTry = mutableListOf<ByteArray>()
        if (!ratchets.isNullOrEmpty()) {
            keysToTry.addAll(ratchets)
        }
        keysToTry.add(xPrivate)

        for (privateKey in keysToTry) {
            try {
                val agreement = X25519Agreement()
                agreement.init(X25519PrivateKeyParameters(privateKey, 0))
                val shared = ByteArray(agreement.agreementSize)
                agreement.calculateAgreement(X25519PublicKeyParameters(peerPubBytes, 0), shared, 0)
                val derivedKey = RnsHkdf.derive(
                    length = DERIVED_KEY_LENGTH,
                    deriveFrom = shared,
                    salt = hash,
                    context = null
                )
                val token = RnsToken(derivedKey)
                return token.decrypt(ciphertext)
            } catch (_: Exception) {
                // Try next key
            }
        }
        return null
    }
}
