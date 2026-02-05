package org.fossify.mesh.rns

import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RnsUdpInterfacePeerPortTest {
    @Test
    fun peerPortIsStableAndAllowsMultipleMessages() {
        val listenPort = 4242
        val aAddr = InetAddress.getByName("127.0.0.1")
        val bAddr = InetAddress.getByName("127.0.0.2")

        val received = CopyOnWriteArrayList<ByteArray>()
        val latch = CountDownLatch(2)

        val ifaceB = RnsUdpInterface(
            name = "b",
            listenPort = listenPort,
            forwardAddress = "255.255.255.255",
            forwardPort = listenPort,
            inboundHandler = { raw, _ ->
                received.add(raw)
                latch.countDown()
            },
            bindAddress = bAddr,
            multicastGroupAddress = null
        )

        val ifaceA = RnsUdpInterface(
            name = "a",
            listenPort = listenPort,
            forwardAddress = "255.255.255.255",
            forwardPort = listenPort,
            inboundHandler = { _, _ -> },
            bindAddress = aAddr,
            multicastGroupAddress = null
        )

        ifaceB.start()
        ifaceA.start()
        try {
            // Prime peer discovery with a packet from an ephemeral source port.
            DatagramSocket(InetSocketAddress(bAddr, 0)).use { sock ->
                val prime = "prime".toByteArray(Charsets.UTF_8)
                sock.send(DatagramPacket(prime, prime.size, aAddr, listenPort))
            }

            val msg1 = "one".toByteArray(Charsets.UTF_8)
            val msg2 = "two".toByteArray(Charsets.UTF_8)
            ifaceA.send(msg1)
            ifaceA.send(msg2)

            val ok = latch.await(2, TimeUnit.SECONDS)
            assertTrue("Expected to receive 2 packets via peer unicast, got ${received.size}", ok)
            assertTrue("Expected to receive msg1", received.any { it.contentEquals(msg1) })
            assertTrue("Expected to receive msg2", received.any { it.contentEquals(msg2) })
        } finally {
            ifaceA.stop()
            ifaceB.stop()
        }
    }
}

