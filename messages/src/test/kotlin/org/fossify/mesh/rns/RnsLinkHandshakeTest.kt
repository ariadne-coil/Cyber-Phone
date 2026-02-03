package org.fossify.mesh.rns

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RnsLinkHandshakeTest {
    @Test
    fun handshakeActivatesLink() {
        val ownerIdentity = RnsIdentity.generate()
        val remoteIdentity = RnsIdentity.generate()
        val ownerDestination = RnsDestination.create(
            identity = ownerIdentity,
            direction = RnsDestination.IN,
            type = RnsDestination.SINGLE,
            appName = "cyberphone",
            aspects = listOf("call")
        )
        val remoteDestination = RnsDestination.create(
            identity = remoteIdentity,
            direction = RnsDestination.IN,
            type = RnsDestination.SINGLE,
            appName = "cyberphone",
            aspects = listOf("call")
        )

        val outgoing = RnsLink.createOutgoing(ownerDestination, remoteDestination)
        requireNotNull(outgoing)
        val requestPacket = outgoing.buildLinkRequestPacket()
        val raw = requestPacket.raw ?: requestPacket.pack()
        val incoming = RnsLink.fromIncomingRequest(remoteDestination, requestPacket, raw)
        requireNotNull(incoming)
        val proof = incoming.buildProofPacket()
        requireNotNull(proof)

        val rtt = outgoing.validateProof(proof)
        assertNotNull(rtt)
        assertTrue(outgoing.isActive())
    }
}
