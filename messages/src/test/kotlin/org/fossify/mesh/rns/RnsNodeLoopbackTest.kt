package org.fossify.mesh.rns

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
                    RnsNode.handleIncomingFromInterface(proof.pack(), this)
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
                            remoteLink.handleRequest(
                                payload = payload,
                                destination = remoteDestination,
                                requestId = requestId,
                                sendPacket = { link, packetType, context, data ->
                                    val encrypted = link.encryptForPacket(packetType, context, data) ?: return@handleRequest
                                    val responsePacket = RnsPacket(
                                        destination = RnsDestination.fromHash(link.linkId, RnsDestination.LINK),
                                        data = encrypted,
                                        packetType = packetType,
                                        context = context
                                    )
                                    kotlin.concurrent.thread(start = true, name = "rns-loopback-response") {
                                        RnsNode.handleIncomingFromInterface(responsePacket.pack(), loopbackInterface)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        loopbackInterface = loopback
        RnsNode.addInterface(loopback)

        val responseRef = AtomicReference<Any?>(null)
        val responseLatch = CountDownLatch(1)

        val deadline = System.currentTimeMillis() + 3000
        var receipt: RnsRequestReceipt? = null
        while (receipt == null && System.currentTimeMillis() < deadline) {
            receipt = RnsNode.requestOverLink(
                owner = localDestination,
                destination = remoteDestination,
                path = "/probe",
                data = listOf(0),
                onResponse = { r ->
                    responseRef.set(r.response)
                    responseLatch.countDown()
                },
                onFailure = {
                    responseLatch.countDown()
                }
            )
            if (receipt == null) {
                Thread.sleep(50)
            }
        }
        assertNotNull(receipt)
        assertNotNull("Receipt should have a requestId", receipt?.requestId)
        val gotResponse = responseLatch.await(3, TimeUnit.SECONDS)
        assertEquals("Expected response callback to fire", true, gotResponse)

        val responseValue = responseRef.get() as? Number
        assertNotNull(responseValue)
        assertEquals(1, responseValue?.toInt())
    }
}
