package org.fossify.mesh.rns

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.msgpack.core.MessagePack
import kotlin.math.floor

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

    fun isActive(): Boolean = status == ACTIVE

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
