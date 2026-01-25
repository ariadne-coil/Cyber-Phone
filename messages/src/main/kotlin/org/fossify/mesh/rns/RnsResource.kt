package org.fossify.mesh.rns

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class RnsResourceKind(val id: Int) {
    LXMF_MESSAGE(0x01),
    LXMF_PROPAGATION(0x02);

    companion object {
        fun fromId(id: Int): RnsResourceKind {
            return entries.firstOrNull { it.id == id } ?: LXMF_MESSAGE
        }
    }
}

data class RnsResource(
    val hash: ByteArray,
    val sourceHash: ByteArray,
    val destinationHash: ByteArray,
    val kind: RnsResourceKind,
    val encrypted: Boolean,
    val timestamp: Long,
    val data: ByteArray
)

data class RnsResourceAdvertisement(
    val hash: ByteArray,
    val sourceHash: ByteArray,
    val destinationHash: ByteArray,
    val kind: RnsResourceKind,
    val encrypted: Boolean,
    val timestamp: Long,
    val totalSize: Int,
    val totalParts: Int
)

data class RnsResourceRequest(
    val hash: ByteArray,
    val requesterHash: ByteArray,
    val kind: RnsResourceKind,
    val requestedIndices: List<Int> = emptyList()
)

data class RnsResourceChunk(
    val hash: ByteArray,
    val sourceHash: ByteArray,
    val destinationHash: ByteArray,
    val kind: RnsResourceKind,
    val encrypted: Boolean,
    val index: Int,
    val totalParts: Int,
    val data: ByteArray
)

object RnsResourceCodec {
    const val VERSION: Byte = 0x01
    private const val HASH_SIZE = 32
    private const val ID_HASH_SIZE = RnsConstants.TRUNCATED_HASH_LENGTH_BITS / 8

    private const val ADV_HEADER_SIZE =
        1 + 1 + 1 + 1 + 8 + 4 + 2 + ID_HASH_SIZE + ID_HASH_SIZE + HASH_SIZE
    private const val REQ_HEADER_SIZE = 1 + 1 + ID_HASH_SIZE + HASH_SIZE
    private const val REQ_COUNT_SIZE = 2
    private const val CHUNK_HEADER_SIZE =
        1 + 1 + 1 + 1 + 2 + 2 + ID_HASH_SIZE + ID_HASH_SIZE + HASH_SIZE

    val maxChunkSize: Int = (RnsConstants.MDU - CHUNK_HEADER_SIZE).coerceAtLeast(1)

    fun packAdvertisement(advertisement: RnsResourceAdvertisement): ByteArray {
        val buffer = ByteBuffer.allocate(ADV_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.put(VERSION)
        buffer.put(flags(encrypted = advertisement.encrypted))
        buffer.put(advertisement.kind.id.toByte())
        buffer.put(0)
        buffer.putLong(advertisement.timestamp)
        buffer.putInt(advertisement.totalSize)
        buffer.putShort(advertisement.totalParts.toShort())
        buffer.put(fixedSize(advertisement.sourceHash, ID_HASH_SIZE))
        buffer.put(fixedSize(advertisement.destinationHash, ID_HASH_SIZE))
        buffer.put(fixedSize(advertisement.hash, HASH_SIZE))
        return buffer.array()
    }

    fun unpackAdvertisement(data: ByteArray): RnsResourceAdvertisement? {
        if (data.size < ADV_HEADER_SIZE) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get().toInt()
        if (version != VERSION.toInt()) return null
        val flags = buffer.get()
        val kind = RnsResourceKind.fromId(buffer.get().toInt() and 0xFF)
        buffer.get()
        val timestamp = buffer.long
        val totalSize = buffer.int
        val totalParts = buffer.short.toInt() and 0xFFFF
        val sourceHash = ByteArray(ID_HASH_SIZE)
        buffer.get(sourceHash)
        val destinationHash = ByteArray(ID_HASH_SIZE)
        buffer.get(destinationHash)
        val hash = ByteArray(HASH_SIZE)
        buffer.get(hash)
        return RnsResourceAdvertisement(
            hash = hash,
            sourceHash = sourceHash,
            destinationHash = destinationHash,
            kind = kind,
            encrypted = isEncrypted(flags),
            timestamp = timestamp,
            totalSize = totalSize,
            totalParts = totalParts
        )
    }

    fun packRequest(request: RnsResourceRequest): ByteArray {
        val indices = request.requestedIndices
        val extraSize = if (indices.isNotEmpty()) REQ_COUNT_SIZE + (indices.size * 2) else 0
        val buffer = ByteBuffer.allocate(REQ_HEADER_SIZE + extraSize).order(ByteOrder.BIG_ENDIAN)
        buffer.put(VERSION)
        buffer.put(request.kind.id.toByte())
        buffer.put(fixedSize(request.requesterHash, ID_HASH_SIZE))
        buffer.put(fixedSize(request.hash, HASH_SIZE))
        if (indices.isNotEmpty()) {
            buffer.putShort(indices.size.toShort())
            indices.forEach { buffer.putShort(it.toShort()) }
        }
        return buffer.array()
    }

    fun unpackRequest(data: ByteArray): RnsResourceRequest? {
        if (data.size < REQ_HEADER_SIZE) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get().toInt()
        if (version != VERSION.toInt()) return null
        val kind = RnsResourceKind.fromId(buffer.get().toInt() and 0xFF)
        val requesterHash = ByteArray(ID_HASH_SIZE)
        buffer.get(requesterHash)
        val hash = ByteArray(HASH_SIZE)
        buffer.get(hash)
        val requestedIndices = ArrayList<Int>()
        if (buffer.remaining() >= REQ_COUNT_SIZE) {
            val count = buffer.short.toInt() and 0xFFFF
            val maxCount = buffer.remaining() / 2
            val safeCount = count.coerceAtMost(maxCount)
            repeat(safeCount) {
                requestedIndices.add(buffer.short.toInt() and 0xFFFF)
            }
        }
        return RnsResourceRequest(
            hash = hash,
            requesterHash = requesterHash,
            kind = kind,
            requestedIndices = requestedIndices
        )
    }

    fun maxRequestIndices(): Int {
        val available = RnsConstants.MDU - REQ_HEADER_SIZE - REQ_COUNT_SIZE
        return (available / 2).coerceAtLeast(0)
    }

    fun packChunk(chunk: RnsResourceChunk): ByteArray {
        val buffer = ByteBuffer.allocate(CHUNK_HEADER_SIZE + chunk.data.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(VERSION)
        buffer.put(chunk.kind.id.toByte())
        buffer.put(flags(encrypted = chunk.encrypted))
        buffer.put(0)
        buffer.putShort(chunk.index.toShort())
        buffer.putShort(chunk.totalParts.toShort())
        buffer.put(fixedSize(chunk.sourceHash, ID_HASH_SIZE))
        buffer.put(fixedSize(chunk.destinationHash, ID_HASH_SIZE))
        buffer.put(fixedSize(chunk.hash, HASH_SIZE))
        buffer.put(chunk.data)
        return buffer.array()
    }

    fun unpackChunk(data: ByteArray): RnsResourceChunk? {
        if (data.size < CHUNK_HEADER_SIZE) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get().toInt()
        if (version != VERSION.toInt()) return null
        val kind = RnsResourceKind.fromId(buffer.get().toInt() and 0xFF)
        val flags = buffer.get()
        buffer.get()
        val index = buffer.short.toInt() and 0xFFFF
        val totalParts = buffer.short.toInt() and 0xFFFF
        val sourceHash = ByteArray(ID_HASH_SIZE)
        buffer.get(sourceHash)
        val destinationHash = ByteArray(ID_HASH_SIZE)
        buffer.get(destinationHash)
        val hash = ByteArray(HASH_SIZE)
        buffer.get(hash)
        val remaining = ByteArray(buffer.remaining())
        buffer.get(remaining)
        return RnsResourceChunk(
            hash = hash,
            sourceHash = sourceHash,
            destinationHash = destinationHash,
            kind = kind,
            encrypted = isEncrypted(flags),
            index = index,
            totalParts = totalParts,
            data = remaining
        )
    }

    private fun flags(encrypted: Boolean): Byte {
        return if (encrypted) 0x01 else 0x00
    }

    private fun isEncrypted(flags: Byte): Boolean = (flags.toInt() and 0x01) == 0x01

    private fun fixedSize(data: ByteArray, size: Int): ByteArray {
        return if (data.size == size) data else data.copyOf(size)
    }
}
