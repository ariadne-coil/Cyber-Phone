package org.fossify.mesh.rns

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.msgpack.core.MessagePack
import org.msgpack.value.Value
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.math.min

class RnsLinkResourceManager(
    private val onResourceComplete: (ByteArray, RnsLink, ResourceMeta) -> Unit
) {
    companion object {
        private const val HASH_LEN = 32
        private const val RANDOM_HASH_SIZE = 4
        private const val MAP_HASH_LEN = 4
        private const val ADV_OVERHEAD = 134
        private const val REQUEST_WINDOW = 32
        private const val MAX_EFFICIENT_SIZE = (1024 * 1024) - 1
        private const val STATE_TRIM_INTERVAL_MS = 30_000L
        private const val OUTGOING_TTL_MS = 10 * 60_000L
        private const val INCOMING_TTL_MS = 10 * 60_000L
        private const val SEGMENT_QUEUE_TTL_MS = 10 * 60_000L
        private const val SEGMENT_ASSEMBLY_TTL_MS = 10 * 60_000L

        // Hard bounds: large/segmented resources can hold many MB in memory. Keep the node from OOMing
        // if proofs never arrive or peers advertise lots of junk.
        private const val OUTGOING_LIMIT = 32
        private const val INCOMING_LIMIT = 32
        private const val SEGMENT_QUEUE_LIMIT = 8
        private const val INCOMING_SEGMENT_LIMIT = 8

        private fun hashmapMaxLen(link: RnsLink): Int {
            val max = floor((link.mdu - ADV_OVERHEAD).toDouble() / MAP_HASH_LEN.toDouble()).toInt()
            return max.coerceAtLeast(1)
        }

        private fun mapHash(randomHash: ByteArray, part: ByteArray): ByteArray {
            val full = RnsHash.sha256(part + randomHash)
            return full.copyOfRange(0, MAP_HASH_LEN)
        }
    }

    private data class OutgoingResource(
        val createdAt: Long = System.currentTimeMillis(),
        val link: RnsLink,
        val linkId: String,
        val hash: ByteArray,
        val randomHash: ByteArray,
        val originalHash: ByteArray,
        val segmentIndex: Int,
        val totalSegments: Int,
        val split: Boolean,
        val hasMetadata: Boolean,
        val requestId: ByteArray?,
        val isRequest: Boolean,
        val isResponse: Boolean,
        val dataPlain: ByteArray,
        val encryptedData: ByteArray,
        val expectedProof: ByteArray,
        val mapHashes: List<ByteArray>,
        val parts: List<ByteArray>,
        val transferSize: Int,
        val totalSize: Int
    )

    private data class IncomingResource(
        val createdAt: Long = System.currentTimeMillis(),
        val link: RnsLink,
        val hash: ByteArray,
        val randomHash: ByteArray,
        val originalHash: ByteArray,
        val segmentIndex: Int,
        val totalSegments: Int,
        val requestId: ByteArray?,
        val isRequest: Boolean,
        val isResponse: Boolean,
        val totalParts: Int,
        val transferSize: Int,
        val totalSize: Int,
        val encrypted: Boolean,
        val compressed: Boolean,
        val split: Boolean,
        val hasMetadata: Boolean,
        val hashmapMaxLen: Int
    ) {
        val parts: Array<ByteArray?> = arrayOfNulls(totalParts)
        val hashmap: Array<ByteArray?> = arrayOfNulls(totalParts)
        var receivedCount: Int = 0
        var awaitingHashmap: Boolean = false
    }

    private val rng = SecureRandom()
    private val outgoing = ConcurrentHashMap<String, OutgoingResource>()
    private val incoming = ConcurrentHashMap<String, IncomingResource>()
    private val segmentQueues = ConcurrentHashMap<String, SegmentQueue>()
    private val incomingSegments = ConcurrentHashMap<String, SegmentAssembly>()
    @Volatile
    private var lastTrimAt = 0L

    data class ResourceMeta(
        val requestId: ByteArray?,
        val isRequest: Boolean,
        val isResponse: Boolean,
        val hasMetadata: Boolean
    )

    fun advertise(
        resourceData: ByteArray,
        link: RnsLink,
        requestId: ByteArray? = null,
        isResponse: Boolean = false,
        isRequest: Boolean = false,
        send: (Int, Int, ByteArray) -> Unit
    ): Boolean {
        maybeTrim()
        if (resourceData.size > MAX_EFFICIENT_SIZE) {
            return advertiseSegmented(resourceData, link, requestId, isResponse, isRequest, send)
        }
        val outgoingResource = createOutgoingResource(
            segmentData = resourceData,
            link = link,
            segmentIndex = 1,
            totalSegments = 1,
            originalHash = null,
            totalSize = resourceData.size,
            requestId = requestId,
            isRequest = isRequest,
            isResponse = isResponse
        ) ?: return false
        outgoing[RnsHex.encode(outgoingResource.hash)] = outgoingResource
        maybeTrim()
        val advPayload = buildAdvertisementPayload(outgoingResource, link, segment = 0)
        send(RnsPacket.DATA, RnsPacket.RESOURCE_ADV, advPayload)
        return true
    }

    fun handleAdvertisement(payload: ByteArray, link: RnsLink, send: (Int, Int, ByteArray) -> Unit) {
        maybeTrim()
        val adv = parseAdvertisement(payload) ?: return
        val key = RnsHex.encode(adv.hash)
        if (incoming.containsKey(key)) return
        val incomingResource = IncomingResource(
            link = link,
            hash = adv.hash,
            randomHash = adv.randomHash,
            originalHash = adv.originalHash,
            segmentIndex = adv.segmentIndex,
            totalSegments = adv.totalSegments,
            requestId = adv.requestId,
            isRequest = adv.isRequest,
            isResponse = adv.isResponse,
            totalParts = adv.totalParts,
            transferSize = adv.transferSize,
            totalSize = adv.totalSize,
            encrypted = adv.encrypted,
            compressed = adv.compressed,
            split = adv.split,
            hasMetadata = adv.hasMetadata,
            hashmapMaxLen = hashmapMaxLen(link)
        )
        incoming[key] = incomingResource
        maybeTrim()
        applyHashmapSegment(incomingResource, 0, adv.hashmapSegment)
        requestMissingParts(incomingResource, link, send)
    }

    fun handleRequest(payload: ByteArray, link: RnsLink, send: (Int, Int, ByteArray) -> Unit) {
        maybeTrim()
        val request = parseRequest(payload) ?: return
        val key = RnsHex.encode(request.hash)
        val resource = outgoing[key] ?: return
        val mapHashList = resource.mapHashes
        request.requestedHashes.forEach { requested ->
            val index = mapHashList.indexOfFirst { it.contentEquals(requested) }
            if (index >= 0) {
                val part = resource.parts[index]
                send(RnsPacket.DATA, RnsPacket.RESOURCE, part)
            }
        }
        if (request.wantsMoreHashmap && request.lastMapHash != null) {
            val index = mapHashList.indexOfFirst { it.contentEquals(request.lastMapHash) }
            if (index >= 0) {
                val maxLen = hashmapMaxLen(link)
                val segment = (index + 1) / maxLen
                val segmentBytes = buildHashmapSegment(mapHashList, segment, maxLen)
                val hmuPayload = buildHmuPayload(resource.hash, segment, segmentBytes)
                send(RnsPacket.DATA, RnsPacket.RESOURCE_HMU, hmuPayload)
            }
        }
    }

    fun handlePart(payload: ByteArray, link: RnsLink, send: (Int, Int, ByteArray) -> Unit) {
        maybeTrim()
        val part = payload
        val hash = findIncomingByPart(part) ?: return
        val resource = incoming[hash] ?: return
        val randomHash = resource.randomHash
        val partHash = mapHash(randomHash, part)
        val index = resource.hashmap.indexOfFirst { it?.contentEquals(partHash) == true }
        if (index < 0) return
        if (resource.parts[index] == null) {
            resource.parts[index] = part
            resource.receivedCount++
        }
        if (resource.receivedCount >= resource.totalParts) {
            val assembled = assemble(resource, link) ?: return
            sendProof(resource.hash, assembled.proof, link, send)
            incoming.remove(hash)
            maybeTrim()
            handleCompletedResource(resource, assembled.data, link)
            return
        }
        requestMissingParts(resource, link, send)
    }

    fun handleHmu(payload: ByteArray, link: RnsLink, send: (Int, Int, ByteArray) -> Unit) {
        maybeTrim()
        if (payload.size < HASH_LEN) return
        val hash = payload.copyOfRange(0, HASH_LEN)
        val key = RnsHex.encode(hash)
        val resource = incoming[key] ?: return
        val unpacker = MessagePack.newDefaultUnpacker(payload.copyOfRange(HASH_LEN, payload.size))
        val size = unpacker.unpackArrayHeader()
        if (size < 2) {
            unpacker.close()
            return
        }
        val segment = unpacker.unpackInt()
        val hashmapValue = unpacker.unpackValue()
        unpacker.close()
        val hashmapBytes = when {
            hashmapValue.isBinaryValue -> hashmapValue.asBinaryValue().asByteArray()
            else -> return
        }
        applyHashmapSegment(resource, segment, hashmapBytes)
        resource.awaitingHashmap = false
        requestMissingParts(resource, link, send)
    }

    fun handleProof(payload: ByteArray, link: RnsLink, send: (Int, Int, ByteArray) -> Unit) {
        maybeTrim()
        if (payload.size < HASH_LEN * 2) return
        val hash = payload.copyOfRange(0, HASH_LEN)
        val proof = payload.copyOfRange(HASH_LEN, HASH_LEN * 2)
        val key = RnsHex.encode(hash)
        val resource = outgoing[key] ?: return
        if (proof.contentEquals(resource.expectedProof)) {
            outgoing.remove(key)
            maybeTrim()
            if (resource.totalSegments > 1 && resource.segmentIndex < resource.totalSegments) {
                val queue = segmentQueues[RnsHex.encode(resource.originalHash)] ?: return
                if (queue.nextIndex <= queue.segments.size) {
                    val segmentData = queue.segments[queue.nextIndex - 1]
                    val next = createOutgoingResource(
                        segmentData = segmentData,
                        link = resource.link,
                        segmentIndex = queue.nextIndex,
                        totalSegments = queue.segments.size,
                        originalHash = queue.originalHash,
                        totalSize = queue.totalSize,
                        requestId = queue.requestId,
                        isRequest = queue.isRequest,
                        isResponse = queue.isResponse
                    ) ?: return
                    outgoing[RnsHex.encode(next.hash)] = next
                    maybeTrim()
                    val advPayload = buildAdvertisementPayload(next, resource.link, segment = 0)
                    queue.nextIndex += 1
                    send(RnsPacket.DATA, RnsPacket.RESOURCE_ADV, advPayload)
                } else {
                    segmentQueues.remove(RnsHex.encode(queue.originalHash))
                }
            }
        }
    }

    private data class AdvertisementData(
        val transferSize: Int,
        val totalSize: Int,
        val totalParts: Int,
        val hash: ByteArray,
        val randomHash: ByteArray,
        val originalHash: ByteArray,
        val hashmapSegment: ByteArray,
        val flags: Int,
        val segmentIndex: Int,
        val totalSegments: Int,
        val requestId: ByteArray?,
        val isRequest: Boolean,
        val isResponse: Boolean
    ) {
        val encrypted: Boolean get() = flags and 0x01 == 0x01
        val compressed: Boolean get() = flags and 0x02 == 0x02
        val split: Boolean get() = flags and 0x04 == 0x04
        val hasMetadata: Boolean get() = flags and 0x20 == 0x20
    }

    private data class RequestData(
        val hash: ByteArray,
        val wantsMoreHashmap: Boolean,
        val lastMapHash: ByteArray?,
        val requestedHashes: List<ByteArray>
    )

    private data class RandomizedData(val hashRandom: ByteArray, val dataWithRandom: ByteArray)

    private data class SegmentQueue(
        val originalHash: ByteArray,
        val segments: List<ByteArray>,
        val totalSize: Int,
        val requestId: ByteArray?,
        val isRequest: Boolean,
        val isResponse: Boolean,
        val createdAt: Long = System.currentTimeMillis(),
        var nextIndex: Int
    )

    private data class SegmentAssembly(
        val originalHash: ByteArray,
        val totalSegments: Int,
        val totalSize: Int,
        val meta: ResourceMeta,
        val createdAt: Long = System.currentTimeMillis(),
        val segments: Array<ByteArray?> = arrayOfNulls(totalSegments),
        var receivedCount: Int = 0
    )

    private fun maybeTrim(now: Long = System.currentTimeMillis()) {
        val overLimit = outgoing.size > OUTGOING_LIMIT ||
            incoming.size > INCOMING_LIMIT ||
            segmentQueues.size > SEGMENT_QUEUE_LIMIT ||
            incomingSegments.size > INCOMING_SEGMENT_LIMIT
        if (!overLimit && now - lastTrimAt < STATE_TRIM_INTERVAL_MS) return
        lastTrimAt = now

        // TTL-based eviction.
        run {
            val iterator = outgoing.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value.createdAt > OUTGOING_TTL_MS) {
                    iterator.remove()
                }
            }
        }
        run {
            val iterator = incoming.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value.createdAt > INCOMING_TTL_MS) {
                    iterator.remove()
                }
            }
        }
        run {
            val iterator = segmentQueues.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value.createdAt > SEGMENT_QUEUE_TTL_MS) {
                    iterator.remove()
                }
            }
        }
        run {
            val iterator = incomingSegments.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value.createdAt > SEGMENT_ASSEMBLY_TTL_MS) {
                    iterator.remove()
                }
            }
        }

        // Hard bounds: drop oldest entries if still over limit.
        if (outgoing.size > OUTGOING_LIMIT) {
            outgoing.entries.sortedBy { it.value.createdAt }
                .take(outgoing.size - OUTGOING_LIMIT)
                .forEach { outgoing.remove(it.key) }
        }
        if (incoming.size > INCOMING_LIMIT) {
            incoming.entries.sortedBy { it.value.createdAt }
                .take(incoming.size - INCOMING_LIMIT)
                .forEach { incoming.remove(it.key) }
        }
        if (segmentQueues.size > SEGMENT_QUEUE_LIMIT) {
            segmentQueues.entries.sortedBy { it.value.createdAt }
                .take(segmentQueues.size - SEGMENT_QUEUE_LIMIT)
                .forEach { segmentQueues.remove(it.key) }
        }
        if (incomingSegments.size > INCOMING_SEGMENT_LIMIT) {
            incomingSegments.entries.sortedBy { it.value.createdAt }
                .take(incomingSegments.size - INCOMING_SEGMENT_LIMIT)
                .forEach { incomingSegments.remove(it.key) }
        }
    }

    private fun addRandomPrefix(data: ByteArray): RandomizedData {
        val prefixRandom = ByteArray(RANDOM_HASH_SIZE).also { rng.nextBytes(it) }
        val hashRandom = ByteArray(RANDOM_HASH_SIZE).also { rng.nextBytes(it) }
        return RandomizedData(hashRandom = hashRandom, dataWithRandom = prefixRandom + data)
    }

    private fun splitParts(data: ByteArray, link: RnsLink): List<ByteArray> {
        val sdu = (link.mtu - RnsConstants.HEADER_MAX_SIZE - RnsConstants.IFAC_MIN_SIZE).coerceAtLeast(1)
        val parts = ArrayList<ByteArray>()
        var offset = 0
        while (offset < data.size) {
            val end = min(offset + sdu, data.size)
            parts.add(data.copyOfRange(offset, end))
            offset = end
        }
        return parts
    }

    private fun buildAdvertisementPayload(resource: OutgoingResource, link: RnsLink, segment: Int): ByteArray {
        val maxLen = hashmapMaxLen(link)
        val segmentBytes = buildHashmapSegment(resource.mapHashes, segment, maxLen)
        var flags = 0x01
        if (resource.split) {
            flags = flags or 0x04
        }
        if (resource.isRequest) {
            flags = flags or 0x08
        }
        if (resource.isResponse) {
            flags = flags or 0x10
        }
        if (resource.hasMetadata) {
            flags = flags or 0x20
        }
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(11)
        packer.packString("t"); packer.packInt(resource.transferSize)
        packer.packString("d"); packer.packInt(resource.totalSize)
        packer.packString("n"); packer.packInt(resource.mapHashes.size)
        packer.packString("h"); packer.packBinaryHeader(resource.hash.size); packer.writePayload(resource.hash)
        packer.packString("r"); packer.packBinaryHeader(resource.randomHash.size); packer.writePayload(resource.randomHash)
        packer.packString("o"); packer.packBinaryHeader(resource.originalHash.size); packer.writePayload(resource.originalHash)
        packer.packString("i"); packer.packInt(resource.segmentIndex)
        packer.packString("l"); packer.packInt(resource.totalSegments)
        packer.packString("q")
        val requestId = resource.requestId
        if (requestId == null) {
            packer.packNil()
        } else {
            packer.packBinaryHeader(requestId.size)
            packer.writePayload(requestId)
        }
        packer.packString("f"); packer.packInt(flags)
        packer.packString("m"); packer.packBinaryHeader(segmentBytes.size); packer.writePayload(segmentBytes)
        packer.close()
        return packer.toByteArray()
    }

    private fun parseAdvertisement(payload: ByteArray): AdvertisementData? {
        return try {
            val unpacker = MessagePack.newDefaultUnpacker(payload)
            val mapSize = unpacker.unpackMapHeader()
            val entries = HashMap<String, Value>(mapSize)
            repeat(mapSize) {
                val key = unpacker.unpackString()
                val value = unpacker.unpackValue()
                entries[key] = value
            }
            unpacker.close()

            val transferSize = entries["t"]?.asIntegerValue()?.toInt() ?: return null
            val totalSize = entries["d"]?.asIntegerValue()?.toInt() ?: return null
            val totalParts = entries["n"]?.asIntegerValue()?.toInt() ?: return null
            val hash = entries["h"]?.asBinaryValue()?.asByteArray() ?: return null
            val randomHash = entries["r"]?.asBinaryValue()?.asByteArray() ?: return null
            val originalHash = entries["o"]?.asBinaryValue()?.asByteArray() ?: hash
            val segmentIndex = entries["i"]?.asIntegerValue()?.toInt() ?: 1
            val totalSegments = entries["l"]?.asIntegerValue()?.toInt() ?: 1
            val flags = entries["f"]?.asIntegerValue()?.toInt() ?: 0
            val requestValue = entries["q"]
            val requestId = when {
                requestValue == null || requestValue.isNilValue -> null
                requestValue.isBinaryValue -> requestValue.asBinaryValue().asByteArray()
                else -> null
            }
            val isRequest = flags and 0x08 == 0x08
            val isResponse = flags and 0x10 == 0x10
            val hashmapSegment = entries["m"]?.asBinaryValue()?.asByteArray() ?: ByteArray(0)
            AdvertisementData(
                transferSize = transferSize,
                totalSize = totalSize,
                totalParts = totalParts,
                hash = hash,
                randomHash = randomHash,
                originalHash = originalHash,
                hashmapSegment = hashmapSegment,
                flags = flags,
                segmentIndex = segmentIndex,
                totalSegments = totalSegments,
                requestId = requestId,
                isRequest = isRequest,
                isResponse = isResponse
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun buildHashmapSegment(mapHashes: List<ByteArray>, segment: Int, maxLen: Int): ByteArray {
        val start = segment * maxLen
        val end = min(start + maxLen, mapHashes.size)
        if (start >= end) return ByteArray(0)
        val bytes = ByteArray((end - start) * MAP_HASH_LEN)
        var offset = 0
        for (i in start until end) {
            val hash = mapHashes[i]
            System.arraycopy(hash, 0, bytes, offset, MAP_HASH_LEN)
            offset += MAP_HASH_LEN
        }
        return bytes
    }

    private fun applyHashmapSegment(resource: IncomingResource, segment: Int, segmentBytes: ByteArray) {
        val maxLen = resource.hashmapMaxLen
        val start = segment * maxLen
        val count = segmentBytes.size / MAP_HASH_LEN
        for (i in 0 until count) {
            val index = start + i
            if (index >= resource.hashmap.size) break
            val mapHash = segmentBytes.copyOfRange(i * MAP_HASH_LEN, i * MAP_HASH_LEN + MAP_HASH_LEN)
            resource.hashmap[index] = mapHash
        }
    }

    private fun requestMissingParts(resource: IncomingResource, link: RnsLink, send: (Int, Int, ByteArray) -> Unit) {
        if (resource.awaitingHashmap) return
        val maxLen = resource.hashmapMaxLen
        val knownHashes = resource.hashmap.filterNotNull()
        val missingHashes = ArrayList<ByteArray>()
        for (hash in knownHashes) {
            val idx = resource.hashmap.indexOfFirst { it?.contentEquals(hash) == true }
            if (idx >= 0 && resource.parts[idx] == null) {
                missingHashes.add(hash)
            }
            if (missingHashes.size >= REQUEST_WINDOW) break
        }

        val needsMoreHashmap = resource.hashmap.any { it == null }
        val lastMapHash = if (needsMoreHashmap) {
            val lastIndex = min(maxLen - 1, knownHashes.size - 1)
            if (lastIndex >= 0) knownHashes[lastIndex] else null
        } else {
            null
        }
        if (needsMoreHashmap) resource.awaitingHashmap = true
        val requestPayload = buildRequestPayload(resource.hash, needsMoreHashmap, lastMapHash, missingHashes)
        send(RnsPacket.DATA, RnsPacket.RESOURCE_REQ, requestPayload)
    }

    private fun buildRequestPayload(
        hash: ByteArray,
        wantsMoreHashmap: Boolean,
        lastMapHash: ByteArray?,
        requestedHashes: List<ByteArray>
    ): ByteArray {
        val extra = if (wantsMoreHashmap && lastMapHash != null) MAP_HASH_LEN else 0
        val payload = ByteArray(1 + extra + HASH_LEN + requestedHashes.size * MAP_HASH_LEN)
        payload[0] = if (wantsMoreHashmap && lastMapHash != null) 0xFF.toByte() else 0x00
        var offset = 1
        if (wantsMoreHashmap && lastMapHash != null) {
            System.arraycopy(lastMapHash, 0, payload, offset, MAP_HASH_LEN)
            offset += MAP_HASH_LEN
        }
        System.arraycopy(hash, 0, payload, offset, HASH_LEN)
        offset += HASH_LEN
        requestedHashes.forEach { hashBytes ->
            System.arraycopy(hashBytes, 0, payload, offset, MAP_HASH_LEN)
            offset += MAP_HASH_LEN
        }
        return payload
    }

    private fun parseRequest(payload: ByteArray): RequestData? {
        if (payload.size < 1 + HASH_LEN) return null
        val wantsMoreHashmap = payload[0].toInt() and 0xFF == 0xFF
        var offset = 1
        val lastMapHash = if (wantsMoreHashmap) {
            if (payload.size < offset + MAP_HASH_LEN + HASH_LEN) return null
            val hash = payload.copyOfRange(offset, offset + MAP_HASH_LEN)
            offset += MAP_HASH_LEN
            hash
        } else {
            null
        }
        if (payload.size < offset + HASH_LEN) return null
        val hash = payload.copyOfRange(offset, offset + HASH_LEN)
        offset += HASH_LEN
        val remaining = payload.size - offset
        val hashes = ArrayList<ByteArray>()
        val count = remaining / MAP_HASH_LEN
        for (i in 0 until count) {
            val start = offset + i * MAP_HASH_LEN
            hashes.add(payload.copyOfRange(start, start + MAP_HASH_LEN))
        }
        return RequestData(
            hash = hash,
            wantsMoreHashmap = wantsMoreHashmap,
            lastMapHash = lastMapHash,
            requestedHashes = hashes
        )
    }

    private fun findIncomingByPart(part: ByteArray): String? {
        for ((key, resource) in incoming.entries) {
            val partHash = mapHash(resource.randomHash, part)
            if (resource.hashmap.any { it?.contentEquals(partHash) == true }) {
                return key
            }
        }
        return null
    }

    private data class AssembledResource(val data: ByteArray, val proof: ByteArray)

    private fun assemble(resource: IncomingResource, link: RnsLink): AssembledResource? {
        val encryptedStream = resource.parts.filterNotNull().fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        val decrypted = if (resource.encrypted) {
            link.decryptStream(encryptedStream) ?: return null
        } else {
            encryptedStream
        }
        if (decrypted.size <= RANDOM_HASH_SIZE) return null
        var data = decrypted.copyOfRange(RANDOM_HASH_SIZE, decrypted.size)
        if (resource.compressed) {
            data = decompressBzip2(data) ?: return null
        }
        val calculated = RnsHash.sha256(data + resource.randomHash)
        if (!calculated.contentEquals(resource.hash)) return null
        val proof = RnsHash.sha256(data + resource.hash)
        return AssembledResource(data, proof)
    }

    private fun handleCompletedResource(resource: IncomingResource, data: ByteArray, link: RnsLink) {
        val meta = ResourceMeta(
            requestId = resource.requestId,
            isRequest = resource.isRequest,
            isResponse = resource.isResponse,
            hasMetadata = resource.hasMetadata
        )
        if (resource.totalSegments <= 1 && !resource.split) {
            onResourceComplete(data, link, meta)
            return
        }

        val key = RnsHex.encode(resource.originalHash)
        val assembly = incomingSegments.getOrPut(key) {
            SegmentAssembly(
                originalHash = resource.originalHash,
                totalSegments = resource.totalSegments,
                totalSize = resource.totalSize,
                meta = meta
            )
        }
        val index = (resource.segmentIndex - 1).coerceIn(0, assembly.totalSegments - 1)
        if (assembly.segments[index] == null) {
            assembly.segments[index] = data
            assembly.receivedCount += 1
        }
        if (assembly.receivedCount >= assembly.totalSegments) {
            val combined = assembly.segments.filterNotNull().fold(ByteArray(0)) { acc, bytes -> acc + bytes }
            incomingSegments.remove(key)
            onResourceComplete(combined, link, assembly.meta)
        }
    }

    private fun sendProof(
        hash: ByteArray,
        proof: ByteArray,
        link: RnsLink,
        send: (Int, Int, ByteArray) -> Unit
    ) {
        val payload = hash + proof
        send(RnsPacket.PROOF, RnsPacket.RESOURCE_PRF, payload)
    }

    private fun decompressBzip2(data: ByteArray): ByteArray? {
        return try {
            BZip2CompressorInputStream(data.inputStream()).use { stream ->
                stream.readBytes()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildHmuPayload(hash: ByteArray, segment: Int, hashmapSegment: ByteArray): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(2)
        packer.packInt(segment)
        packer.packBinaryHeader(hashmapSegment.size)
        packer.writePayload(hashmapSegment)
        packer.close()
        return hash + packer.toByteArray()
    }

    private fun advertiseSegmented(
        resourceData: ByteArray,
        link: RnsLink,
        requestId: ByteArray?,
        isResponse: Boolean,
        isRequest: Boolean,
        send: (Int, Int, ByteArray) -> Unit
    ): Boolean {
        val segments = splitSegments(resourceData)
        val first = createOutgoingResource(
            segmentData = segments.first(),
            link = link,
            segmentIndex = 1,
            totalSegments = segments.size,
            originalHash = null,
            totalSize = resourceData.size,
            requestId = requestId,
            isRequest = isRequest,
            isResponse = isResponse
        ) ?: return false
        outgoing[RnsHex.encode(first.hash)] = first
        if (segments.size > 1) {
            segmentQueues[RnsHex.encode(first.originalHash)] = SegmentQueue(
                originalHash = first.originalHash,
                segments = segments,
                totalSize = resourceData.size,
                requestId = requestId,
                isRequest = isRequest,
                isResponse = isResponse,
                nextIndex = 2
            )
        }
        val advPayload = buildAdvertisementPayload(first, link, segment = 0)
        send(RnsPacket.DATA, RnsPacket.RESOURCE_ADV, advPayload)
        return true
    }

    private fun splitSegments(data: ByteArray): List<ByteArray> {
        if (data.size <= MAX_EFFICIENT_SIZE) return listOf(data)
        val segments = ArrayList<ByteArray>()
        var offset = 0
        while (offset < data.size) {
            val end = min(offset + MAX_EFFICIENT_SIZE, data.size)
            segments.add(data.copyOfRange(offset, end))
            offset = end
        }
        return segments
    }

    private fun createOutgoingResource(
        segmentData: ByteArray,
        link: RnsLink,
        segmentIndex: Int,
        totalSegments: Int,
        originalHash: ByteArray?,
        totalSize: Int,
        requestId: ByteArray?,
        isRequest: Boolean,
        isResponse: Boolean
    ): OutgoingResource? {
        val randomized = addRandomPrefix(segmentData)
        val encrypted = link.encryptStream(randomized.dataWithRandom) ?: return null
        val randomHash = randomized.hashRandom
        val transferSize = encrypted.size
        val resourceHash = RnsHash.sha256(segmentData + randomHash)
        val expectedProof = RnsHash.sha256(segmentData + resourceHash)
        val parts = splitParts(encrypted, link)
        val mapHashes = parts.map { part -> mapHash(randomHash, part) }
        val effectiveOriginal = originalHash ?: resourceHash
        return OutgoingResource(
            link = link,
            linkId = link.linkIdHex(),
            hash = resourceHash,
            randomHash = randomHash,
            originalHash = effectiveOriginal,
            segmentIndex = segmentIndex,
            totalSegments = totalSegments,
            split = totalSegments > 1,
            hasMetadata = false,
            requestId = requestId,
            isRequest = isRequest,
            isResponse = isResponse,
            dataPlain = segmentData,
            encryptedData = encrypted,
            expectedProof = expectedProof,
            mapHashes = mapHashes,
            parts = parts,
            transferSize = transferSize,
            totalSize = totalSize
        )
    }
}

private fun RnsLink.linkIdHex(): String = RnsHex.encode(linkId)
