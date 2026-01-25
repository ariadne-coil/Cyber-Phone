package org.fossify.mesh.rns

import kotlin.math.ceil

object RnsHkdf {
    fun derive(length: Int, deriveFrom: ByteArray, salt: ByteArray? = null, context: ByteArray? = null): ByteArray {
        require(length > 0) { "Invalid output key length" }
        require(deriveFrom.isNotEmpty()) { "Cannot derive key from empty input material" }

        val hashLen = 32
        val actualSalt = if (salt == null || salt.isEmpty()) ByteArray(hashLen) else salt
        val actualContext = context ?: ByteArray(0)

        val pseudorandomKey = RnsHmac.sha256(actualSalt, deriveFrom)
        var block = ByteArray(0)
        val output = ArrayList<Byte>()

        val iterations = ceil(length / hashLen.toDouble()).toInt()
        for (i in 0 until iterations) {
            val input = ByteArray(block.size + actualContext.size + 1)
            var offset = 0
            block.copyInto(input, offset)
            offset += block.size
            actualContext.copyInto(input, offset)
            offset += actualContext.size
            input[offset] = ((i + 1) and 0xFF).toByte()
            block = RnsHmac.sha256(pseudorandomKey, input)
            block.forEach { output.add(it) }
        }

        val out = ByteArray(length)
        for (i in 0 until length) {
            out[i] = output[i]
        }
        return out
    }
}
