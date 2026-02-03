package org.fossify.mesh.lxmf

import org.fossify.mesh.rns.RnsHash
import org.fossify.mesh.rns.RnsHex
import java.nio.ByteBuffer

object LxmfAddress {
    const val PREFIX = "mesh:"
    private const val ALT_PREFIX = "lxm:"
    private const val ALT_PREFIX_SCHEME = "lxm://"
    private const val HASH_BYTES = 16

    fun encode(hash: ByteArray): String {
        require(hash.size == HASH_BYTES) { "LXMF destination hash must be 16 bytes" }
        return PREFIX + RnsHex.encode(hash)
    }

    fun decode(address: String): ByteArray? {
        val normalized = normalize(address)
        if (!normalized.startsWith(PREFIX)) return null
        val hex = normalized.removePrefix(PREFIX)
        return try {
            val bytes = RnsHex.decode(hex)
            if (bytes.size == HASH_BYTES) bytes else null
        } catch (_: Exception) {
            null
        }
    }

    fun normalize(address: String): String {
        val trimmed = address.trim().lowercase()
        val normalized = when {
            trimmed.startsWith(ALT_PREFIX_SCHEME) -> ALT_PREFIX + trimmed.removePrefix(ALT_PREFIX_SCHEME)
            trimmed.startsWith(ALT_PREFIX) -> trimmed
            trimmed.startsWith(PREFIX) -> trimmed
            else -> PREFIX + trimmed
        }
        return if (normalized.startsWith(ALT_PREFIX)) {
            PREFIX + normalized.removePrefix(ALT_PREFIX)
        } else {
            normalized
        }
    }

    fun isMeshAddress(address: String): Boolean {
        return decode(address) != null
    }

    fun threadIdForAddress(address: String): Long {
        val normalized = normalize(address)
        val digest = RnsHash.sha256(normalized.toByteArray(Charsets.UTF_8))
        val base = ByteBuffer.wrap(digest.copyOfRange(0, 8)).long
        val withSign = base or Long.MIN_VALUE
        return if (withSign == Long.MIN_VALUE) Long.MIN_VALUE + 1 else withSign
    }

    fun messageIdForHash(hash: ByteArray): Long {
        val digest = RnsHash.sha256(hash)
        val base = ByteBuffer.wrap(digest.copyOfRange(0, 8)).long
        val withSign = base or Long.MIN_VALUE
        return if (withSign == Long.MIN_VALUE) Long.MIN_VALUE + 1 else withSign
    }

    fun isMeshThreadId(threadId: Long): Boolean = threadId < 0
}
