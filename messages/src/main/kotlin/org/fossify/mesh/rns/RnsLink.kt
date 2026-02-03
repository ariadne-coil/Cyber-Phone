package org.fossify.mesh.rns

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.msgpack.core.MessagePack
import org.msgpack.core.MessagePacker
import org.msgpack.core.MessageUnpacker
import kotlin.math.floor
import java.util.concurrent.ConcurrentHashMap

class RnsLink private constructor(
    val owner: RnsDestination,
    val destination: RnsDestination?,
    val initiator: Boolean,
    var mode: Int,
    private val localX25519Private: ByteArray,
    private val localX25519Public: ByteArray,
    private val localSigPublic: ByteArray,
    private val ownerIdentity: RnsIdentity
) {
    companion object {
        const val ECPUB_SIZE = 64
        private const val X25519_PUB_SIZE = 32
        private const val SIG_PUB_SIZE = 32
        private const val SIG_SIZE = 64
        private const val AES_BLOCK_SIZE = 16
        private const val LINK_MTU_SIZE = 3

        const val PENDING = 0x00
        const val HANDSHAKE = 0x01
        const val ACTIVE = 0x02
        const val CLOSED = 0x04

        const val MODE_AES128_CBC = 0x00
        const val MODE_AES256_CBC = 0x01

        private const val MTU_BYTEMASK = 0x1FFFFF
        private const val MODE_BYTEMASK = 0xE0

        private val ENABLED_MODES = setOf(MODE_AES256_CBC)

        fun createOutgoing(owner: RnsDestination, destination: RnsDestination): RnsLink? {
            val identity = owner.identity ?: return null
            val ephemeral = RnsIdentity.generate()
            val xPrivate = ephemeral.x25519Private ?: return null
            val xPublic = ephemeral.x25519Public
            val sigPublic = ephemeral.ed25519Public
            val mode = MODE_AES256_CBC
            return RnsLink(
                owner = owner,
                destination = destination,
                initiator = true,
                mode = mode,
                localX25519Private = xPrivate,
                localX25519Public = xPublic,
                localSigPublic = sigPublic,
                ownerIdentity = identity
            )
        }

        fun fromIncomingRequest(owner: RnsDestination, packet: RnsPacket, raw: ByteArray): RnsLink? {
            val identity = owner.identity ?: return null
            if (packet.data.size != ECPUB_SIZE && packet.data.size != ECPUB_SIZE + LINK_MTU_SIZE) {
                return null
            }
            val peerPub = packet.data.copyOfRange(0, X25519_PUB_SIZE)
            val peerSigPub = packet.data.copyOfRange(X25519_PUB_SIZE, ECPUB_SIZE)
            val ephemeral = RnsIdentity.generate()
            val xPrivate = ephemeral.x25519Private ?: return null
            val xPublic = ephemeral.x25519Public
            val mode = modeFromLinkRequest(packet)
            if (!ENABLED_MODES.contains(mode)) return null

            val link = RnsLink(
                owner = owner,
                destination = null,
                initiator = false,
                mode = mode,
                localX25519Private = xPrivate,
                localX25519Public = xPublic,
                localSigPublic = identity.ed25519Public,
                ownerIdentity = identity
            )
            link.linkId = linkIdFromRequest(raw, packet.data.size)
            link.peerPubBytes = peerPub
            link.peerSigPubBytes = peerSigPub
            link.mtu = mtuFromLinkRequest(packet) ?: RnsConstants.MTU
            link.updateMdu()
            link.requestTimeMs = System.currentTimeMillis()
            link.handshake()
            return link
        }

        fun signallingBytes(mtu: Int, mode: Int): ByteArray {
            if (!ENABLED_MODES.contains(mode)) {
                throw IllegalArgumentException("Unsupported link mode $mode")
            }
            val signallingValue = (mtu and MTU_BYTEMASK) + (((mode shl 5) and MODE_BYTEMASK) shl 16)
            val full = byteArrayOf(
                ((signallingValue shr 24) and 0xFF).toByte(),
                ((signallingValue shr 16) and 0xFF).toByte(),
                ((signallingValue shr 8) and 0xFF).toByte(),
                (signallingValue and 0xFF).toByte()
            )
            return full.copyOfRange(1, 4)
        }

        fun modeFromLinkRequest(packet: RnsPacket): Int {
            return if (packet.data.size > ECPUB_SIZE) {
                (packet.data[ECPUB_SIZE].toInt() and MODE_BYTEMASK) shr 5
            } else {
                MODE_AES256_CBC
            }
        }

        fun modeFromLinkProof(packet: RnsPacket): Int {
            val offset = SIG_SIZE + X25519_PUB_SIZE
            return if (packet.data.size > offset) {
                (packet.data[offset].toInt() and 0xFF) shr 5
            } else {
                MODE_AES256_CBC
            }
        }

        fun mtuFromLinkRequest(packet: RnsPacket): Int? {
            if (packet.data.size != ECPUB_SIZE + LINK_MTU_SIZE) return null
            val idx = ECPUB_SIZE
            val value = ((packet.data[idx].toInt() and 0xFF) shl 16) +
                ((packet.data[idx + 1].toInt() and 0xFF) shl 8) +
                (packet.data[idx + 2].toInt() and 0xFF)
            return value and MTU_BYTEMASK
        }

        fun mtuFromLinkProof(packet: RnsPacket): Int? {
            val offset = SIG_SIZE + X25519_PUB_SIZE
            if (packet.data.size != offset + LINK_MTU_SIZE) return null
            val value = ((packet.data[offset].toInt() and 0xFF) shl 16) +
                ((packet.data[offset + 1].toInt() and 0xFF) shl 8) +
                (packet.data[offset + 2].toInt() and 0xFF)
            return value and MTU_BYTEMASK
        }

        fun linkIdFromRequest(raw: ByteArray, dataSize: Int): ByteArray {
            var hashable = RnsPacket.getHashablePart(raw)
            if (dataSize > ECPUB_SIZE) {
                val diff = dataSize - ECPUB_SIZE
                if (diff > 0 && hashable.size > diff) {
                    hashable = hashable.copyOf(hashable.size - diff)
                }
            }
            return RnsHash.truncatedHash(hashable)
        }

        fun packRttPayload(seconds: Double): ByteArray {
            val packer = MessagePack.newDefaultBufferPacker()
            packer.packDouble(seconds)
            val bytes = packer.toByteArray()
            packer.close()
            return bytes
        }

        fun unpackRttPayload(data: ByteArray): Double? {
            return try {
                val unpacker = MessagePack.newDefaultUnpacker(data)
                val value = unpacker.unpackDouble()
                unpacker.close()
                value
            } catch (_: Exception) {
                null
            }
        }
    }

    var linkId: ByteArray = ByteArray(0)
        private set
    var status: Int = PENDING
        private set
    var mtu: Int = RnsConstants.MTU
        private set
    var mdu: Int = computeMdu(mtu)
        private set
    private var token: RnsToken? = null
    private var peerPubBytes: ByteArray? = null
    private var peerSigPubBytes: ByteArray? = null
    private var requestTimeMs: Long = 0L
    private var rttSeconds: Double? = null
    private val pendingRequests = ConcurrentHashMap<String, RnsRequestReceipt>()
    private var remoteIdentity: RnsIdentity? = null

    fun buildLinkRequestPacket(): RnsPacket {
        if (!initiator) error("Only initiator can build link requests")
        val signalling = signallingBytes(mtu, mode)
        val payload = localX25519Public + localSigPublic + signalling
        val packet = RnsPacket(
            destination = destination ?: error("Destination required for link request"),
            data = payload,
            packetType = RnsPacket.LINKREQUEST
        )
        val raw = packet.pack()
        linkId = linkIdFromRequest(raw, payload.size)
        requestTimeMs = System.currentTimeMillis()
        return packet
    }

    fun buildProofPacket(): RnsPacket? {
        if (initiator) return null
        if (linkId.isEmpty()) return null
        val signalling = signallingBytes(mtu, mode)
        val signedData = linkId + localX25519Public + localSigPublic + signalling
        val signature = ownerIdentity.sign(signedData)
        val payload = signature + localX25519Public + signalling
        return RnsPacket(
            destination = RnsDestination.fromHash(linkId, RnsDestination.LINK),
            data = payload,
            packetType = RnsPacket.PROOF,
            context = RnsPacket.LRPROOF
        )
    }

    fun validateProof(packet: RnsPacket): Double? {
        if (!initiator) return null
        val identity = destination?.identity ?: return null
        val data = packet.data
        if (data.size != SIG_SIZE + X25519_PUB_SIZE && data.size != SIG_SIZE + X25519_PUB_SIZE + LINK_MTU_SIZE) {
            return null
        }
        val proofMode = modeFromLinkProof(packet)
        if (proofMode != mode) return null

        var signalling = ByteArray(0)
        val confirmedMtu = mtuFromLinkProof(packet)
        if (confirmedMtu != null) {
            signalling = signallingBytes(confirmedMtu, proofMode)
        }
        val baseSize = SIG_SIZE + X25519_PUB_SIZE
        val truncated = data.copyOfRange(0, baseSize)
        val signature = truncated.copyOfRange(0, SIG_SIZE)
        val peerPub = truncated.copyOfRange(SIG_SIZE, baseSize)
        val peerSigPub = identity.ed25519Public

        peerPubBytes = peerPub
        peerSigPubBytes = peerSigPub
        handshake()

        val signedData = linkId + peerPub + peerSigPub + signalling
        val valid = identity.verify(signedData, signature)
        if (!valid) return null

        if (confirmedMtu != null) {
            mtu = confirmedMtu
            updateMdu()
        }
        status = ACTIVE
        val rtt = (System.currentTimeMillis() - requestTimeMs).toDouble() / 1000.0
        rttSeconds = rtt
        return rtt
    }

    fun handleRttPayload(payload: ByteArray): Boolean {
        val received = unpackRttPayload(payload) ?: return false
        val measured = (System.currentTimeMillis() - requestTimeMs).toDouble() / 1000.0
        rttSeconds = maxOf(measured, received)
        status = ACTIVE
        return true
    }

    fun encryptForPacket(packetType: Int, context: Int, payload: ByteArray): ByteArray? {
        if (!shouldEncrypt(packetType, context)) return payload
        val activeToken = token ?: return null
        return try {
            activeToken.encrypt(payload)
        } catch (_: Exception) {
            null
        }
    }

    fun decryptForPacket(packetType: Int, context: Int, payload: ByteArray): ByteArray? {
        if (!shouldEncrypt(packetType, context)) return payload
        val activeToken = token ?: return null
        return try {
            activeToken.decrypt(payload)
        } catch (_: Exception) {
            null
        }
    }

    fun encryptStream(payload: ByteArray): ByteArray? {
        val activeToken = token ?: return null
        return try {
            activeToken.encrypt(payload)
        } catch (_: Exception) {
            null
        }
    }

    fun decryptStream(payload: ByteArray): ByteArray? {
        val activeToken = token ?: return null
        return try {
            activeToken.decrypt(payload)
        } catch (_: Exception) {
            null
        }
    }

    fun isActive(): Boolean = status == ACTIVE

    fun request(
        path: String,
        data: Any?,
        sendPacket: (RnsLink, Int, Int, ByteArray) -> ByteArray?,
        onResponse: ((RnsRequestReceipt) -> Unit)? = null,
        onFailure: ((RnsRequestReceipt) -> Unit)? = null,
        sendResource: ((ByteArray, ByteArray, Boolean) -> Boolean)? = null
    ): RnsRequestReceipt? {
        if (!isActive()) return null
        val requestPayload = packRequest(path, data)
        val requestId = if (requestPayload.size <= mdu) {
            val raw = sendPacket(this, RnsPacket.DATA, RnsPacket.REQUEST, requestPayload) ?: run {
                val failed = RnsRequestReceipt(ByteArray(0), onResponse, onFailure)
                onFailure?.invoke(failed)
                return null
            }
            RnsHash.truncatedHash(RnsHash.sha256(RnsPacket.getHashablePart(raw)))
        } else {
            val computed = RnsHash.truncatedHash(RnsHash.sha256(requestPayload))
            val sent = sendResource?.invoke(requestPayload, computed, false) ?: false
            if (!sent) {
                val failed = RnsRequestReceipt(ByteArray(0), onResponse, onFailure)
                onFailure?.invoke(failed)
                return null
            }
            computed
        }
        val receipt = RnsRequestReceipt(requestId, onResponse, onFailure)
        pendingRequests[RnsHex.encode(requestId)] = receipt
        return receipt
    }

    fun handleRequest(
        payload: ByteArray,
        destination: RnsDestination,
        requestId: ByteArray,
        sendPacket: (RnsLink, Int, Int, ByteArray) -> Unit,
        sendResource: ((ByteArray, ByteArray, Boolean) -> Boolean)? = null
    ) {
        if (requestId.isEmpty()) return
        try {
            val unpacker = MessagePack.newDefaultUnpacker(payload)
            val arraySize = unpacker.unpackArrayHeader()
            if (arraySize < 3) {
                unpacker.close()
                return
            }
            val requestedAt = unpacker.unpackDouble()
            val pathHash = unpacker.unpackBinaryHeader().let { len ->
                val bytes = ByteArray(len)
                unpacker.readPayload(bytes)
                bytes
            }
            val dataValue = unpackAny(unpacker.unpackValue())
            unpacker.close()

            val handler = destination.getRequestHandler(pathHash) ?: return
            val response = handler.handle(pathHash, dataValue, requestedAt, remoteIdentity, linkId)
            if (response == null) return
            val responsePayload = packResponse(requestId, response)
            if (responsePayload.size <= mdu) {
                sendPacket(this, RnsPacket.DATA, RnsPacket.RESPONSE, responsePayload)
            } else {
                sendResource?.invoke(responsePayload, requestId, true)
            }
        } catch (_: Exception) {
        }
    }

    fun identify(identity: RnsIdentity, sendPacket: (RnsLink, Int, Int, ByteArray) -> ByteArray?): Boolean {
        if (!initiator || !isActive()) return false
        val signedData = linkId + identity.publicKey
        val signature = identity.sign(signedData)
        val payload = identity.publicKey + signature
        val raw = sendPacket(this, RnsPacket.DATA, RnsPacket.LINKIDENTIFY, payload)
        return raw != null
    }

    fun handleIdentify(payload: ByteArray): Boolean {
        if (initiator) return false
        val expectedSize = RnsIdentity.KEY_SIZE * 2
        if (payload.size != expectedSize) return false
        val publicKey = payload.copyOfRange(0, RnsIdentity.KEY_SIZE)
        val signature = payload.copyOfRange(RnsIdentity.KEY_SIZE, payload.size)
        val identity = RnsIdentity.fromPublic(publicKey)
        val signedData = linkId + publicKey
        return if (identity.verify(signedData, signature)) {
            remoteIdentity = identity
            true
        } else {
            false
        }
    }

    fun getRemoteIdentity(): RnsIdentity? = remoteIdentity

    fun handleResponse(payload: ByteArray) {
        try {
            val unpacker = MessagePack.newDefaultUnpacker(payload)
            val size = unpacker.unpackArrayHeader()
            if (size < 2) {
                unpacker.close()
                return
            }
            val requestId = unpacker.unpackBinaryHeader().let { len ->
                val bytes = ByteArray(len)
                unpacker.readPayload(bytes)
                bytes
            }
            val response = unpackAny(unpacker.unpackValue())
            unpacker.close()
            val key = RnsHex.encode(requestId)
            val receipt = pendingRequests.remove(key) ?: return
            receipt.response = response
            receipt.onResponse?.invoke(receipt)
        } catch (_: Exception) {
        }
    }

    private fun packRequest(path: String, data: Any?): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(3)
        packer.packDouble(System.currentTimeMillis() / 1000.0)
        val pathHash = RnsHash.truncatedHash(path.toByteArray(Charsets.UTF_8))
        packer.packBinaryHeader(pathHash.size)
        packer.writePayload(pathHash)
        packAny(packer, data)
        packer.close()
        return packer.toByteArray()
    }

    private fun packResponse(requestId: ByteArray, response: Any?): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(2)
        packer.packBinaryHeader(requestId.size)
        packer.writePayload(requestId)
        packAny(packer, response)
        packer.close()
        return packer.toByteArray()
    }

    private fun packAny(packer: MessagePacker, value: Any?) {
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
            is List<*> -> {
                packer.packArrayHeader(value.size)
                value.forEach { packAny(packer, it) }
            }
            is Map<*, *> -> {
                packer.packMapHeader(value.size)
                value.forEach { (k, v) ->
                    packAny(packer, k)
                    packAny(packer, v)
                }
            }
            else -> packer.packString(value.toString())
        }
    }

    private fun unpackAny(value: org.msgpack.value.Value): Any? {
        return when {
            value.isNilValue -> null
            value.isBooleanValue -> value.asBooleanValue().boolean
            value.isIntegerValue -> value.asIntegerValue().toLong()
            value.isFloatValue -> value.asFloatValue().toDouble()
            value.isStringValue -> value.asStringValue().asString()
            value.isBinaryValue -> value.asBinaryValue().asByteArray()
            value.isArrayValue -> value.asArrayValue().list().map { unpackAny(it) }
            value.isMapValue -> value.asMapValue().map().mapNotNull { entry ->
                unpackAny(entry.key) to unpackAny(entry.value)
            }.toMap()
            else -> null
        }
    }

    private fun handshake() {
        if (status != PENDING) return
        val peer = peerPubBytes ?: return
        status = HANDSHAKE
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(localX25519Private, 0))
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(peer, 0), shared, 0)
        val derivedLength = if (mode == MODE_AES128_CBC) 32 else 64
        val derived = RnsHkdf.derive(
            length = derivedLength,
            deriveFrom = shared,
            salt = linkId,
            context = null
        )
        token = RnsToken(derived)
    }

    private fun updateMdu() {
        mdu = computeMdu(mtu)
    }

    private fun computeMdu(currentMtu: Int): Int {
        val usable = currentMtu - RnsConstants.IFAC_MIN_SIZE - RnsConstants.HEADER_MIN_SIZE - RnsToken.TOKEN_OVERHEAD
        val blocks = floor(usable.toDouble() / AES_BLOCK_SIZE.toDouble()).toInt().coerceAtLeast(0)
        return (blocks * AES_BLOCK_SIZE - 1).coerceAtLeast(1)
    }

    private fun shouldEncrypt(packetType: Int, context: Int): Boolean {
        return RnsPacket.shouldEncryptForLink(packetType, context)
    }
}
