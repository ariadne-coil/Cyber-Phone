package org.fossify.mesh.rns

object RnsHex {
    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    fun decode(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0) { "Hex string must have even length" }
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < clean.length) {
            val hi = decodeNibble(clean[i])
            val lo = decodeNibble(clean[i + 1])
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }

    fun encode(bytes: ByteArray): String {
        // Hot path: used as map keys throughout the RNS/LXMF stack. Avoid String.format() to keep
        // allocations low and prevent OOMs on packet bursts.
        val out = CharArray(bytes.size * 2)
        var i = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out[i++] = HEX_CHARS[v ushr 4]
            out[i++] = HEX_CHARS[v and 0x0F]
        }
        return String(out)
    }

    private fun decodeNibble(c: Char): Int {
        return when (c) {
            in '0'..'9' -> c.code - '0'.code
            in 'a'..'f' -> c.code - 'a'.code + 10
            in 'A'..'F' -> c.code - 'A'.code + 10
            else -> throw IllegalArgumentException("Invalid hex char: $c")
        }
    }
}
