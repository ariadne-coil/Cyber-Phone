package org.fossify.mesh.rns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RnsPacketTest {
    @Test
    fun plainDestinationRoundTrip() {
        val destination = RnsDestination.createPlain(
            direction = RnsDestination.OUT,
            appName = "cyberphone",
            aspects = listOf("probe")
        )
        val payload = "hello-mesh".toByteArray(Charsets.UTF_8)
        val packet = RnsPacket(
            destination = destination,
            data = payload,
            packetType = RnsPacket.DATA,
            context = RnsPacket.NONE
        )
        val raw = packet.pack()
        val unpacked = RnsPacket.fromRaw(raw)
        assertEquals(RnsPacket.DATA, unpacked.packetType)
        assertEquals(RnsPacket.NONE, unpacked.context)
        assertArrayEquals(destination.hash, unpacked.destination?.hash)
        assertArrayEquals(payload, unpacked.data)
    }
}
