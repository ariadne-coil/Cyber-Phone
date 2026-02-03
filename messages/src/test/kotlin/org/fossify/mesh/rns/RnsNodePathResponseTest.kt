package org.fossify.mesh.rns

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RnsNodePathResponseTest {
    @After
    fun tearDown() {
        RnsNode.stop()
    }

    @Test
    fun pathResponseAndDataDelivery() {
        RnsNode.stop()
        val localIdentity = RnsIdentity.generate()
        val remoteIdentity = RnsIdentity.generate()

        val localDestination = RnsDestination.create(
            identity = localIdentity,
            direction = RnsDestination.IN,
            type = RnsDestination.SINGLE,
            appName = "cyberphone",
            aspects = listOf("call")
        )
        val remoteDestinationPrivate = RnsDestination.create(
            identity = remoteIdentity,
            direction = RnsDestination.IN,
            type = RnsDestination.SINGLE,
            appName = "cyberphone",
            aspects = listOf("call")
        )
        val remoteDestinationPublic = RnsDestination.create(
            identity = RnsIdentity.fromPublic(remoteIdentity.publicKey),
            direction = RnsDestination.OUT,
            type = RnsDestination.SINGLE,
            appName = "cyberphone",
            aspects = listOf("call")
        )

        RnsNode.registerDestination(localDestination, { _, _ -> })

        val handlerMethod = RnsNode::class.java.getDeclaredMethod(
            "handleIncoming",
            ByteArray::class.java,
            RnsInterface::class.java
        ).apply { isAccessible = true }

        val destHashLen = RnsConstants.TRUNCATED_HASH_LENGTH_BITS / 8
        val delivered = ByteArrayHolder()

        val loopback = object : RnsInterface {
            override val name: String = "loopback"
            override fun start() = Unit
            override fun stop() = Unit

            override fun send(raw: ByteArray) {
                val packet = RnsPacket.fromRaw(raw)
                if (packet.packetType == RnsPacket.DATA &&
                    packet.context == RnsPacket.NONE &&
                    packet.destination?.type == RnsDestination.PLAIN
                ) {
                    if (packet.data.size >= destHashLen) {
                        val requested = packet.data.copyOfRange(0, destHashLen)
                        if (requested.contentEquals(remoteDestinationPrivate.hash)) {
                            val announce = RnsAnnounce.build(
                                destination = remoteDestinationPrivate,
                                context = RnsPacket.PATH_RESPONSE
                            )
                            handlerMethod.invoke(RnsNode, announce.pack(), this)
                        }
                    }
                    return
                }

                if (packet.packetType == RnsPacket.DATA &&
                    packet.context == RnsPacket.NONE &&
                    packet.destination?.hash?.contentEquals(remoteDestinationPrivate.hash) == true
                ) {
                    val decrypted = remoteDestinationPrivate.decrypt(packet.data)
                    if (decrypted != null) {
                        delivered.value = decrypted
                    }
                }
            }
        }

        val interfacesField = RnsNode::class.java.getDeclaredField("interfaces").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val interfaces = interfacesField.get(RnsNode) as MutableList<RnsInterface>
        interfaces.clear()
        interfaces.add(loopback)

        RnsNode.requestPath(remoteDestinationPrivate.hash)

        val deadline = System.currentTimeMillis() + 3000
        var identity: RnsIdentity? = null
        while (identity == null && System.currentTimeMillis() < deadline) {
            identity = RnsNode.recallIdentity(remoteDestinationPrivate.hash)
            if (identity == null) {
                Thread.sleep(50)
            }
        }
        assertNotNull(identity)

        val payload = "mesh-ping".toByteArray(Charsets.UTF_8)
        val packet = RnsPacket(
            destination = remoteDestinationPublic,
            data = payload,
            packetType = RnsPacket.DATA,
            context = RnsPacket.NONE
        )
        RnsNode.send(packet)

        while (delivered.value == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }

        assertArrayEquals(payload, delivered.value)
    }

    private class ByteArrayHolder {
        @Volatile
        var value: ByteArray? = null
    }
}
