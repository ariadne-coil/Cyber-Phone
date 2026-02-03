package org.fossify.mesh.lxmf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LxmfAddressTest {
    @Test
    fun encodeDecodeRoundTrip() {
        val hash = ByteArray(16) { it.toByte() }
        val address = LxmfAddress.encode(hash)
        val decoded = LxmfAddress.decode(address)
        assertArrayEquals(hash, decoded)
    }

    @Test
    fun decodeRejectsInvalidLength() {
        val invalid = "mesh:1234"
        assertNull(LxmfAddress.decode(invalid))
    }

    @Test
    fun normalizeLowercasesAndPrefixes() {
        val normalized = LxmfAddress.normalize("ABCD")
        assertEquals("mesh:abcd", normalized)
    }

    @Test
    fun threadIdIsNegativeAndStable() {
        val address = "mesh:00112233445566778899aabbccddeeff"
        val first = LxmfAddress.threadIdForAddress(address)
        val second = LxmfAddress.threadIdForAddress(address)
        assertTrue(first < 0)
        assertEquals(first, second)
    }
}
