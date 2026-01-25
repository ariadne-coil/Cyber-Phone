package org.fossify.mesh.lxmf

import org.fossify.mesh.rns.RnsDestination
import org.fossify.mesh.rns.RnsHash
import org.fossify.mesh.rns.RnsIdentity
import org.msgpack.core.MessagePack
import org.msgpack.value.Value

class LxmfMessage private constructor(
    val destinationHash: ByteArray,
    val sourceHash: ByteArray,
    val destination: RnsDestination?,
    val source: RnsDestination?,
    val fields: Map<Int, Any?>
) {
    var title: String = ""
    var content: String = ""
    var timestamp: Double? = null
    var signature: ByteArray? = null
    var hash: ByteArray? = null
    var packed: ByteArray? = null
    var incoming: Boolean = false
    var signatureValidated: Boolean = false

    fun pack(): ByteArray {
        if (packed != null) {
            return packed!!
        }
        val destination = destination ?: error("Destination required for packing")
        val source = source ?: error("Source required for packing")
        if (timestamp == null) {
            timestamp = System.currentTimeMillis() / 1000.0
        }

        val payload = listOf(
            timestamp!!,
            title.toByteArray(Charsets.UTF_8),
            content.toByteArray(Charsets.UTF_8),
            fields
        )
        val packedPayload = packPayload(payload)
        val hashedPart = destinationHash + sourceHash + packedPayload
        val messageHash = RnsHash.sha256(hashedPart)
        val signedPart = hashedPart + messageHash
        val signature = source.sign(signedPart) ?: error("Source identity missing for signature")
        val packedMessage = destinationHash + sourceHash + signature + packedPayload

        this.signature = signature
        this.hash = messageHash
        this.packed = packedMessage
        this.signatureValidated = true
        return packedMessage
    }

    companion object {
        private const val DESTINATION_LENGTH = 16
        private const val SIGNATURE_LENGTH = 64

        fun createOutgoing(
            destination: RnsDestination,
            source: RnsDestination,
            title: String,
            content: String,
            fields: Map<Int, Any?> = emptyMap()
        ): LxmfMessage {
            val message = LxmfMessage(
                destinationHash = destination.hash,
                sourceHash = source.hash,
                destination = destination,
                source = source,
                fields = fields
            )
            message.title = title
            message.content = content
            return message
        }

        fun unpackFromBytes(
            lxmfBytes: ByteArray,
            identityResolver: (ByteArray) -> RnsIdentity?
        ): LxmfMessage {
            val destinationHash = lxmfBytes.copyOfRange(0, DESTINATION_LENGTH)
            val sourceHash = lxmfBytes.copyOfRange(DESTINATION_LENGTH, DESTINATION_LENGTH * 2)
            val signatureStart = DESTINATION_LENGTH * 2
            val signatureEnd = signatureStart + SIGNATURE_LENGTH
            val signature = lxmfBytes.copyOfRange(signatureStart, signatureEnd)
            val packedPayload = lxmfBytes.copyOfRange(signatureEnd, lxmfBytes.size)
            val unpackedPayload = unpackPayload(packedPayload)

            val strippedPayload = if (unpackedPayload.size > 4) {
                unpackedPayload.subList(0, 4)
            } else {
                unpackedPayload
            }
            val repackedPayload = packPayload(strippedPayload)
            val hashedPart = destinationHash + sourceHash + repackedPayload
            val messageHash = RnsHash.sha256(hashedPart)
            val signedPart = hashedPart + messageHash

            val timestamp = coerceToDouble(strippedPayload[0])
            val titleBytes = strippedPayload[1] as? ByteArray ?: ByteArray(0)
            val contentBytes = strippedPayload[2] as? ByteArray ?: ByteArray(0)
            val fields = (strippedPayload[3] as? Map<*, *>)?.mapNotNull { entry ->
                val key = (entry.key as? Number)?.toInt() ?: return@mapNotNull null
                key to entry.value
            }?.toMap() ?: emptyMap()

            val sourceIdentity = identityResolver(sourceHash)
            val sourceDestination = sourceIdentity?.let {
                RnsDestination.create(it, RnsDestination.OUT, RnsDestination.SINGLE, LxmfConstants.APP_NAME, listOf("delivery"))
            }
            val destinationIdentity = identityResolver(destinationHash)
            val destination = destinationIdentity?.let {
                RnsDestination.create(it, RnsDestination.OUT, RnsDestination.SINGLE, LxmfConstants.APP_NAME, listOf("delivery"))
            }

            val message = LxmfMessage(
                destinationHash = destinationHash,
                sourceHash = sourceHash,
                destination = destination,
                source = sourceDestination,
                fields = fields
            )
            message.title = titleBytes.toString(Charsets.UTF_8)
            message.content = contentBytes.toString(Charsets.UTF_8)
            message.timestamp = timestamp
            message.signature = signature
            message.hash = messageHash
            message.packed = lxmfBytes
            message.incoming = true
            message.signatureValidated = sourceIdentity?.verify(signedPart, signature) == true
            return message
        }

        private fun packPayload(payload: List<Any?>): ByteArray {
            val packer = MessagePack.newDefaultBufferPacker()
            packer.packArrayHeader(payload.size)
            for (item in payload) {
                packAny(packer, item)
            }
            packer.close()
            return packer.toByteArray()
        }

        private fun packAny(packer: org.msgpack.core.MessagePacker, value: Any?) {
            when (value) {
                null -> packer.packNil()
                is String -> packer.packString(value)
                is ByteArray -> {
                    packer.packBinaryHeader(value.size)
                    packer.writePayload(value)
                }
                is Int -> packer.packInt(value)
                is Long -> packer.packLong(value)
                is Double -> packer.packDouble(value)
                is Float -> packer.packFloat(value)
                is Boolean -> packer.packBoolean(value)
                is Map<*, *> -> {
                    packer.packMapHeader(value.size)
                    for (entry in value.entries) {
                        packAny(packer, entry.key)
                        packAny(packer, entry.value)
                    }
                }
                is List<*> -> {
                    packer.packArrayHeader(value.size)
                    value.forEach { packAny(packer, it) }
                }
                else -> error("Unsupported msgpack value: ${value::class.java.simpleName}")
            }
        }

        private fun unpackPayload(payload: ByteArray): List<Any?> {
            val unpacker = MessagePack.newDefaultUnpacker(payload)
            val size = unpacker.unpackArrayHeader()
            val result = ArrayList<Any?>(size)
            for (i in 0 until size) {
                result.add(unpackAny(unpacker.unpackValue()))
            }
            unpacker.close()
            return result
        }

        private fun unpackAny(value: Value): Any? {
            return when {
                value.isNilValue -> null
                value.isBooleanValue -> value.asBooleanValue().boolean
                value.isIntegerValue -> value.asIntegerValue().toLong()
                value.isFloatValue -> value.asFloatValue().toDouble()
                value.isStringValue -> value.asStringValue().asString()
                value.isBinaryValue -> value.asBinaryValue().asByteArray()
                value.isArrayValue -> value.asArrayValue().list().map { unpackAny(it) }
                value.isMapValue -> value.asMapValue().map().map { entry ->
                    unpackAny(entry.key) to unpackAny(entry.value)
                }.toMap()
                else -> null
            }
        }

        private fun coerceToDouble(value: Any?): Double {
            return when (value) {
                is Double -> value
                is Float -> value.toDouble()
                is Long -> value.toDouble()
                is Int -> value.toDouble()
                else -> 0.0
            }
        }
    }
}
