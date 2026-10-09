package org.fossify.mesh.rns

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.msgpack.core.MessagePack
import java.io.ByteArrayOutputStream

class RnsLinkResourceManagerTest {
    private fun link(): RnsLink {
        val owner = RnsDestination.create(RnsIdentity.generate(), RnsDestination.IN, RnsDestination.SINGLE, "test", listOf("resource"))
        val remote = RnsDestination.create(RnsIdentity.generate(), RnsDestination.OUT, RnsDestination.SINGLE, "test", listOf("resource"))
        return requireNotNull(RnsLink.createOutgoing(owner, remote))
    }

    private fun advertisement(
        transfer: Int, total: Int, parts: Int, hash: ByteArray = ByteArray(32),
        random: ByteArray = ByteArray(4), hashmap: ByteArray = ByteArray(0),
        flags: Int = 0, segments: Int = 1, index: Int = 1, original: ByteArray = hash,
    ): ByteArray = MessagePack.newDefaultBufferPacker().use { packer ->
        packer.packMapHeader(10)
        packer.packString("t").packInt(transfer)
        packer.packString("d").packInt(total)
        packer.packString("n").packInt(parts)
        for ((key, value) in listOf("h" to hash, "r" to random, "m" to hashmap, "o" to original)) {
            packer.packString(key).packBinaryHeader(value.size).writePayload(value)
        }
        packer.packString("f").packInt(flags)
        packer.packString("l").packInt(segments)
        packer.packString("i").packInt(index)
        packer.toByteArray()
    }

    @Test fun oversizedAndInconsistentAdvertisementsAreRejectedBeforeAllocation() {
        var sends = 0
        val manager = RnsLinkResourceManager { _, _, _ -> fail("Unexpected completion") }
        val link = link()
        for (payload in listOf(
            advertisement(1, 1, 8_000_000), advertisement(1, 1, -1),
            advertisement(Int.MAX_VALUE, 1, 1), advertisement(1, Int.MAX_VALUE, 1),
            advertisement(1, 1, 1, segments = Int.MAX_VALUE, flags = 4),
            advertisement(1, 1, 1, hash = ByteArray(1)), advertisement(1, 1, 1, index = -1),
        )) manager.handleAdvertisement(payload, link) { _, _, _ -> sends++ }
        for (nestedArray in listOf(false, true)) {
            val malformed = MessagePack.newDefaultBufferPacker().use { packer ->
                packer.packMapHeader(5)
                packer.packString("t").packInt(1)
                packer.packString("d").packInt(1)
                packer.packString("n").packInt(1)
                packer.packString("h")
                if (nestedArray) packer.packArrayHeader(Int.MAX_VALUE) else packer.packBinaryHeader(Int.MAX_VALUE)
                packer.toByteArray()
            }
            manager.handleAdvertisement(malformed, link) { _, _, _ -> sends++ }
        }
        assertEquals(0, sends)
    }

    @Test fun validResourceIsDeliveredAndProved() {
        var result: ByteArray? = null
        val manager = RnsLinkResourceManager { data, _, _ -> result = data }
        val link = link()
        val text = "mesh message".toByteArray()
        val part = ByteArray(4) + text
        val random = ByteArray(4)
        var proofs = 0
        manager.handleAdvertisement(advertisement(part.size, text.size, 1, RnsHash.sha256(text + random), random,
            RnsHash.sha256(part + random).copyOf(4)), link) { _, _, _ -> }
        manager.handlePart(part, link) { _, context, _ -> if (context == RnsPacket.RESOURCE_PRF) proofs++ }
        assertArrayEquals(text, result)
        assertEquals(1, proofs)
    }

    @Test fun compressedResourceCannotExpandPastTheLimit() {
        val expanded = ByteArray(2 * 1024 * 1024)
        val compressed = ByteArrayOutputStream().also { output ->
            BZip2CompressorOutputStream(output).use { it.write(expanded) }
        }.toByteArray()
        val part = ByteArray(4) + compressed
        val random = ByteArray(4)
        var completed = false
        val manager = RnsLinkResourceManager { _, _, _ -> completed = true }
        val link = link()
        manager.handleAdvertisement(advertisement(part.size, 1024 * 1024 - 1, 1, RnsHash.sha256(expanded + random), random,
            RnsHash.sha256(part + random).copyOf(4), flags = 2), link) { _, _, _ -> }
        manager.handlePart(part, link) { _, _, _ -> }
        assertFalse(completed)
    }

    @Test fun aggregateReservationsAreBounded() {
        val manager = RnsLinkResourceManager { _, _, _ -> }
        val link = link()
        val transfer = 1024 * 1024
        val sdu = link.mtu - RnsConstants.HEADER_MAX_SIZE - RnsConstants.IFAC_MIN_SIZE
        val parts = (transfer + sdu - 1) / sdu
        var accepted = 0
        repeat(40) { index ->
            manager.handleAdvertisement(advertisement(transfer, 1024 * 1024 - 1, parts, hash = ByteArray(32) { index.toByte() }), link) { _, _, _ -> accepted++ }
        }
        assertTrue(accepted in 1..31)
    }

    @Test fun negativeHashmapUpdateDoesNotCrash() {
        val manager = RnsLinkResourceManager { _, _, _ -> }
        val link = link()
        manager.handleAdvertisement(advertisement(10, 6, 1), link) { _, _, _ -> }
        val update = MessagePack.newDefaultBufferPacker().use { packer ->
            packer.packArrayHeader(2).packInt(-1).packBinaryHeader(4).writePayload(ByteArray(4))
            ByteArray(32) + packer.toByteArray()
        }
        manager.handleHmu(update, link) { _, _, _ -> fail("Invalid update accepted") }
        val oversized = MessagePack.newDefaultBufferPacker().use { packer ->
            packer.packArrayHeader(2).packInt(0).packBinaryHeader(Int.MAX_VALUE)
            ByteArray(32) + packer.toByteArray()
        }
        manager.handleHmu(oversized, link) { _, _, _ -> fail("Oversized update accepted") }
    }

    @Test fun hashmapRequestsAdvanceBeyondTheSecondSegment() {
        val manager = RnsLinkResourceManager { _, _, _ -> }
        val link = link()
        val capacity = ((link.mdu - 134) / 4).coerceAtLeast(1)
        val parts = capacity * 3
        val transfer = parts * (link.mtu - RnsConstants.HEADER_MAX_SIZE - RnsConstants.IFAC_MIN_SIZE)
        val hashes = List(parts) { java.nio.ByteBuffer.allocate(4).putInt(it).array() }
        var request: ByteArray? = null
        val send: (Int, Int, ByteArray) -> Unit = { _, _, data -> request = data }
        manager.handleAdvertisement(advertisement(transfer, transfer - 4, parts,
            hashmap = hashes.take(capacity).fold(ByteArray(0)) { all, hash -> all + hash }), link, send)
        assertArrayEquals(hashes[capacity - 1], requireNotNull(request).copyOfRange(1, 5))
        val nextMap = hashes.subList(capacity, capacity * 2).fold(ByteArray(0)) { all, hash -> all + hash }
        val update = MessagePack.newDefaultBufferPacker().use { packer ->
            packer.packArrayHeader(2).packInt(1).packBinaryHeader(nextMap.size).writePayload(nextMap)
            ByteArray(32) + packer.toByteArray()
        }
        manager.handleHmu(update, link, send)
        assertArrayEquals(hashes[capacity * 2 - 1], requireNotNull(request).copyOfRange(1, 5))
    }
}
