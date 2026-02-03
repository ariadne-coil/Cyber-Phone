package org.fossify.mesh.rns

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class RnsNodeLoopbackTest {
    @After
    fun tearDown() {
        RnsNode.stop()
    }

    @Test
    fun requestOverLinkReceivesResponse() {
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
        val remoteDestination = RnsDestination.create(
            identity = remoteIdentity,
            direction = RnsDestination.IN,
            type = RnsDestination.SINGLE,
            appName = "cyberphone",
            aspects = listOf("call")
        )

        remoteDestination.registerRequestHandler("/probe") { _, _, _, _, _ ->
            1
        }

        RnsNode.registerDestination(localDestination, { _, _ -> })
        val handlerMethod = RnsNode::class.java.getDeclaredMethod(
            "handleIncoming",
            ByteArray::class.java,
            RnsInterface::class.java
        ).apply { isAccessible = true }

        val remoteLinkRef = AtomicReference<RnsLink?>(null)
        lateinit var loopbackInterface: RnsInterface
        val loopback = object : RnsInterface {
            override val name: String = "loopback"

            override fun start() = Unit
            override fun stop() = Unit

            override fun send(raw: ByteArray) {
                val packet = RnsPacket.fromRaw(raw)
                if (packet.packetType == RnsPacket.LINKREQUEST) {
                    val remoteLink = RnsLink.fromIncomingRequest(remoteDestination, packet, raw) ?: return
                    remoteLinkRef.set(remoteLink)
                    val proof = remoteLink.buildProofPacket() ?: return
                    handlerMethod.invoke(RnsNode, proof.pack(), this)
                    return
                }

                if (packet.destination?.type == RnsDestination.LINK) {
                    val remoteLink = remoteLinkRef.get() ?: return
                    val payload = remoteLink.decryptForPacket(packet.packetType, packet.context, packet.data) ?: return
                    when (packet.context) {
                        RnsPacket.LRRTT -> {
                            remoteLink.handleRttPayload(payload)
                        }
                        RnsPacket.REQUEST -> {
                            val requestId = RnsHash.truncatedHash(
                                RnsHash.sha256(RnsPacket.getHashablePart(raw))
                            )
                            remoteLink.handleRequest(payload, remoteDestination, requestId) { link, packetType, context, data ->
                                val encrypted = link.encryptForPacket(packetType, context, data) ?: return@handleRequest
                                val responsePacket = RnsPacket(
                                    destination = RnsDestination.fromHash(link.linkId, RnsDestination.LINK),
                                    data = encrypted,
                                    packetType = packetType,
                                    context = context
                                )
                                kotlin.concurrent.thread(start = true, name = "rns-loopback-response") {
                                    handlerMethod.invoke(RnsNode, responsePacket.pack(), loopbackInterface)
                                }
                            }
                        }
                    }
                }
            }
        }

        loopbackInterface = loopback
        val interfacesField = RnsNode::class.java.getDeclaredField("interfaces").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val interfaces = interfacesField.get(RnsNode) as MutableList<RnsInterface>
        interfaces.clear()
        interfaces.add(loopback)

        val deadline = System.currentTimeMillis() + 3000
        var receipt: RnsRequestReceipt? = null
        while (receipt == null && System.currentTimeMillis() < deadline) {
            receipt = RnsNode.requestOverLink(localDestination, remoteDestination, "/probe", listOf(0))
            if (receipt == null) {
                Thread.sleep(50)
            }
        }
        assertNotNull(receipt)

        while (receipt?.response == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }

        val responseValue = receipt?.response as? Number
        assertNotNull(responseValue)
        assertEquals(1, responseValue?.toInt())
    }
}
