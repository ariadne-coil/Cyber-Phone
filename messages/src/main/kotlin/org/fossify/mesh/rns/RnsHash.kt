package org.fossify.mesh.rns

import java.security.MessageDigest

object RnsHash {
    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun sha512(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(data)

    fun truncatedHash(data: ByteArray): ByteArray {
        val full = sha256(data)
        return full.copyOfRange(0, RnsConstants.TRUNCATED_HASH_LENGTH_BITS / 8)
    }
}
