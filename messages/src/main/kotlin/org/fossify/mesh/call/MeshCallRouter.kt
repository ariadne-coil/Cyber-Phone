package org.fossify.mesh.call

import android.content.Context
import android.os.SystemClock
import org.fossify.mesh.MeshConfig
import org.fossify.mesh.MeshIdentityStore
import org.fossify.mesh.rns.RnsDestination
import org.fossify.mesh.rns.RnsHash
import org.fossify.mesh.rns.RnsHex
import org.fossify.mesh.rns.RnsIdentity
import org.fossify.mesh.rns.RnsNode
import org.fossify.mesh.rns.RnsPacket
import org.fossify.mesh.lxmf.LxmfConstants
import org.msgpack.core.MessagePack
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

object MeshCallRouter {
    private const val APP_NAME = "cyberphone"
    private const val PROBE_PATH = "/probe"
    private const val PAYLOAD_PREFIX: Byte = 0x7E
    private const val TYPE_CONTROL: Byte = 0x01
    private const val TYPE_AUDIO: Byte = 0x02
    private const val CMD_INVITE = 0x01
    private const val CMD_ACCEPT = 0x02
    private const val CMD_DECLINE = 0x03
    private const val CMD_END = 0x04

    private const val SESSION_ID_SIZE = 8
    private const val AUDIO_HEADER_SIZE = 1 + 1 + SESSION_ID_SIZE + 4

    data class MeshCallSession(
        val sessionId: ByteArray,
        val remoteDeliveryHash: ByteArray,
        val remoteCallHash: ByteArray,
        val remoteDestination: RnsDestination,
        val quality: MeshCallQuality,
        val outgoing: Boolean,
        var linkId: ByteArray? = null
    )

    data class ProbeResult(
        val success: Boolean,
        val quality: MeshCallQuality = MeshCallQuality.LOW,
        val remoteCallHash: ByteArray = ByteArray(0),
        val remoteDestination: RnsDestination? = null
    )

    interface Listener {
        fun onIncomingInvite(session: MeshCallSession)
        fun onCallAccepted(sessionId: ByteArray)
        fun onCallDeclined(sessionId: ByteArray)
        fun onCallEnded(sessionId: ByteArray)
        fun onAudioFrame(sessionId: ByteArray, sequence: Int, payload: ByteArray)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val sessions = ConcurrentHashMap<String, MeshCallSession>()
    private var callDestination: RnsDestination? = null
    private var localIdentity: RnsIdentity? = null
    private var localDeliveryHash: ByteArray? = null

    fun start(context: Context) {
        if (callDestination != null) return
        val meshIdentity = MeshIdentityStore.getOrCreate(context)
        val identity = meshIdentity.privateKey?.let { RnsIdentity.fromPrivate(it) }
            ?: RnsIdentity.fromPublic(meshIdentity.publicKey)
        localIdentity = identity
        localDeliveryHash = RnsDestination.hash(identity, LxmfConstants.APP_NAME, listOf("delivery"))
        val destination = RnsDestination.create(
            identity = identity,
            direction = RnsDestination.IN,
            type = RnsDestination.SINGLE,
            appName = APP_NAME,
            aspects = listOf("call")
        )
        callDestination = destination
        destination.registerRequestHandler(PROBE_PATH) { _, data, _, _, _ ->
            handleProbeRequest(context, data)
        }
        RnsNode.registerDestination(destination, { packet, payload ->
            handleCallPacket(packet, payload)
        })
        // Announce our call destination so peers can discover a routable path for mesh calls.
        RnsNode.announce(destination)
    }

    fun stop() {
        callDestination?.let { RnsNode.unregisterDestination(it.hash) }
        callDestination = null
        localIdentity = null
        localDeliveryHash = null
        sessions.clear()
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun createOutgoingSession(
        remoteDeliveryHash: ByteArray,
        remoteCallHash: ByteArray,
        remoteDestination: RnsDestination,
        quality: MeshCallQuality
    ): MeshCallSession {
        val sessionId = ByteArray(SESSION_ID_SIZE).also { java.security.SecureRandom().nextBytes(it) }
        val session = MeshCallSession(
            sessionId = sessionId,
            remoteDeliveryHash = remoteDeliveryHash,
            remoteCallHash = remoteCallHash,
            remoteDestination = remoteDestination,
            quality = quality,
            outgoing = true
        )
        sessions[RnsHex.encode(sessionId)] = session
        return session
    }

    fun probe(
        context: Context,
        remoteDeliveryHash: ByteArray,
        preferredQuality: MeshCallQuality,
        timeoutMs: Long,
        callback: (ProbeResult) -> Unit
    ) {
        thread {
            val result = performProbe(context, remoteDeliveryHash, preferredQuality, timeoutMs)
            callback(result)
        }
    }

    private fun performProbe(
        context: Context,
        remoteDeliveryHash: ByteArray,
        preferredQuality: MeshCallQuality,
        timeoutMs: Long
    ): ProbeResult {
        val start = SystemClock.elapsedRealtime()
        // MeshService startup is async. If the UI triggers a call immediately after enabling mesh,
        // ensure the backend is actually running before we attempt path/link operations.
        val readyDeadline = start + timeoutMs.coerceAtMost(2_000L)
        while (SystemClock.elapsedRealtime() < readyDeadline) {
            if (RnsNode.isRunning() && callDestination != null && localIdentity != null) break
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                return ProbeResult(success = false)
            }
        }
        if (!RnsNode.isRunning() || callDestination == null || localIdentity == null) {
            return ProbeResult(success = false)
        }

        var identity = RnsNode.recallIdentity(remoteDeliveryHash)
        if (identity == null) {
            var lastPathRequestAt = 0L
            while (SystemClock.elapsedRealtime() - start < timeoutMs) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastPathRequestAt >= 750L) {
                    RnsNode.requestPath(remoteDeliveryHash, minIntervalMs = 1_000L)
                    // Also announce ourselves while probing so the peer can resolve our return path quickly.
                    RnsNode.announceAll()
                    lastPathRequestAt = now
                }
                identity = RnsNode.recallIdentity(remoteDeliveryHash)
                if (identity != null) break
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    return ProbeResult(success = false)
                }
            }
        }
        if (identity == null) {
            return ProbeResult(success = false)
        }

        val remoteCallHash = RnsDestination.hash(identity, APP_NAME, listOf("call"))
        val remoteCallDestination = buildCallDestination(identity, remoteCallHash)
        val owner = callDestination ?: return ProbeResult(success = false)
        val local = localIdentity ?: return ProbeResult(success = false)

        var lastCallPathRequestAt = 0L
        val requestDeadline = SystemClock.elapsedRealtime() + timeoutMs
        var receipt: org.fossify.mesh.rns.RnsRequestReceipt? = null
        while (SystemClock.elapsedRealtime() < requestDeadline && receipt == null) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastCallPathRequestAt >= 750L) {
                RnsNode.requestPath(remoteCallDestination.hash, minIntervalMs = 1_000L)
                lastCallPathRequestAt = now
            }
            RnsNode.identifyLink(owner, remoteCallDestination, local)
            receipt = RnsNode.requestOverLink(
                owner = owner,
                destination = remoteCallDestination,
                path = PROBE_PATH,
                data = listOf(preferredQuality.id),
                onResponse = null,
                onFailure = null
            )
            if (receipt == null) {
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    return ProbeResult(success = false)
                }
            }
        }

        receipt ?: return ProbeResult(success = false)

        while (SystemClock.elapsedRealtime() < requestDeadline) {
            val response = receipt.response
            if (response != null) {
                val qualityId = (response as? Number)?.toInt() ?: preferredQuality.id
                val finalQuality = MeshCallQuality.fromId(qualityId)
                return ProbeResult(
                    success = true,
                    quality = finalQuality,
                    remoteCallHash = remoteCallDestination.hash,
                    remoteDestination = remoteCallDestination
                )
            }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return ProbeResult(success = false)
            }
        }
        return ProbeResult(success = false)
    }

    private fun handleProbeRequest(context: Context, data: Any?): Any? {
        val requestedId = (data as? List<*>)?.firstOrNull() as? Number
        val requestedQuality = MeshCallQuality.fromId(requestedId?.toInt() ?: 0)
        val localQuality = MeshCallQuality.fromId(MeshConfig.newInstance(context).meshCallQuality)
        val chosen = if (requestedQuality.id <= localQuality.id) requestedQuality else localQuality
        return chosen.id
    }

    private fun buildCallDestination(identity: RnsIdentity, callHash: ByteArray): RnsDestination {
        return RnsDestination.createWithHash(
            identity = identity,
            direction = RnsDestination.OUT,
            type = RnsDestination.SINGLE,
            appName = APP_NAME,
            aspects = listOf("call"),
            hashOverride = callHash
        )
    }

    fun sendInvite(session: MeshCallSession) {
        val callerDeliveryHash = localDeliveryHash ?: ByteArray(0)
        val callerCallHash = callDestination?.hash ?: ByteArray(0)
        val payload = buildControlPayload(
            CMD_INVITE,
            session.sessionId,
            session.quality.id,
            callerDeliveryHash,
            callerCallHash
        )
        sendControlPayload(session, payload)
    }

    fun sendAccept(sessionId: ByteArray) {
        val session = getSession(sessionId) ?: return
        val payload = buildControlPayload(CMD_ACCEPT, session.sessionId, session.quality.id)
        sendControlPayload(session, payload)
    }

    fun sendDecline(sessionId: ByteArray) {
        val session = getSession(sessionId) ?: return
        val payload = buildControlPayload(CMD_DECLINE, session.sessionId, session.quality.id)
        sendControlPayload(session, payload)
    }

    fun sendEnd(sessionId: ByteArray) {
        val session = getSession(sessionId) ?: return
        val payload = buildControlPayload(CMD_END, session.sessionId, session.quality.id)
        // END is critical. Send a few times to survive lossy transports and interface switching.
        sendControlPayload(session, payload)
        // Also send directly to the remote call destination (non-link), as a fallback if the link
        // is flapping; this is low frequency so the extra crypto cost is fine.
        try {
            RnsNode.send(RnsPacket(destination = session.remoteDestination, data = payload))
        } catch (_: Exception) {
        }
        thread(name = "mesh-call-end-retry", start = true) {
            repeat(2) {
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    return@thread
                }
                try {
                    sendControlPayload(session, payload)
                    RnsNode.send(RnsPacket(destination = session.remoteDestination, data = payload))
                } catch (_: Exception) {
                }
            }
        }
        sessions.remove(RnsHex.encode(sessionId))
    }

    fun sendAudioFrame(sessionId: ByteArray, sequence: Int, opusFrame: ByteArray) {
        val session = getSession(sessionId) ?: return
        val buffer = ByteBuffer.allocate(AUDIO_HEADER_SIZE + opusFrame.size)
        buffer.put(PAYLOAD_PREFIX)
        buffer.put(TYPE_AUDIO)
        buffer.put(sessionId)
        buffer.putInt(sequence)
        buffer.put(opusFrame)
        val payload = buffer.array()
        val owner = callDestination ?: return
        // Prefer destination-based link routing so we use the freshest active link mapping instead of
        // pinning audio to the first observed link ID, which may go stale after link churn.
        if (session.remoteDestination.identity != null &&
            RnsNode.trySendPacketViaLink(owner, session.remoteDestination, payload, RnsPacket.NONE)
        ) {
            return
        }
        val linkId = session.linkId
        if (linkId != null && RnsNode.sendPacketOnLink(linkId, payload, RnsPacket.NONE)) {
            return
        }
        // Do not queue audio. If the link/path isn't ready, drop frames to avoid building up
        // seconds of latency ("slow-mo" audio) when the link becomes active again.
        RnsNode.trySendPacketViaLink(owner, session.remoteDestination, payload, RnsPacket.NONE)
    }

    private fun sendControlPayload(session: MeshCallSession, payload: ByteArray) {
        val owner = callDestination ?: return
        // For control packets, prefer destination-based link routing so retransmits follow the current
        // active link instead of a possibly stale cached link ID.
        if (session.remoteDestination.identity != null) {
            RnsNode.sendPacketViaLink(owner, session.remoteDestination, payload, RnsPacket.NONE)
            return
        }
        val linkId = session.linkId
        if (linkId != null && RnsNode.sendPacketOnLink(linkId, payload, RnsPacket.NONE)) {
            return
        }
    }

    private fun handleCallPacket(packet: RnsPacket, payload: ByteArray) {
        if (payload.size < 2) return
        if (payload[0] != PAYLOAD_PREFIX) return
        val linkId = if (packet.destination?.type == RnsDestination.LINK) {
            packet.destination.hash
        } else {
            null
        }
        when (payload[1]) {
            TYPE_AUDIO -> handleAudioPayload(payload, linkId)
            TYPE_CONTROL -> handleControlPayload(payload, linkId)
        }
    }

    private fun handleAudioPayload(payload: ByteArray, linkId: ByteArray?) {
        if (payload.size <= AUDIO_HEADER_SIZE) return
        val sessionId = payload.copyOfRange(2, 2 + SESSION_ID_SIZE)
        updateSessionLink(sessionId, linkId)
        val seqStart = 2 + SESSION_ID_SIZE
        val sequence = ByteBuffer.wrap(payload, seqStart, 4).int
        val frame = payload.copyOfRange(AUDIO_HEADER_SIZE, payload.size)
        listeners.forEach { it.onAudioFrame(sessionId, sequence, frame) }
    }

    private fun handleControlPayload(payload: ByteArray, linkId: ByteArray?) {
        val msgPackPayload = payload.copyOfRange(2, payload.size)
        try {
            val unpacker = MessagePack.newDefaultUnpacker(msgPackPayload)
            val size = unpacker.unpackArrayHeader()
            if (size < 2) {
                unpacker.close()
                return
            }
            val cmd = unpacker.unpackInt()
            val sessionId = unpacker.unpackBinaryHeader().let { len ->
                val bytes = ByteArray(len)
                unpacker.readPayload(bytes)
                bytes
            }
            val qualityId = if (size > 2) unpacker.unpackInt() else 0
            val deliveryHash = if (size > 3) {
                unpacker.unpackBinaryHeader().let { len ->
                    val bytes = ByteArray(len)
                    unpacker.readPayload(bytes)
                    bytes
                }
            } else {
                ByteArray(0)
            }
            val callHash = if (size > 4) {
                unpacker.unpackBinaryHeader().let { len ->
                    val bytes = ByteArray(len)
                    unpacker.readPayload(bytes)
                    bytes
                }
            } else {
                ByteArray(0)
            }
            unpacker.close()
            updateSessionLink(sessionId, linkId)
            val sessionKey = RnsHex.encode(sessionId)
            when (cmd) {
                CMD_INVITE -> handleInvite(sessionId, qualityId, deliveryHash, callHash, linkId)
                CMD_ACCEPT -> listeners.forEach { it.onCallAccepted(sessionId) }
                CMD_DECLINE -> {
                    listeners.forEach { it.onCallDeclined(sessionId) }
                    sessions.remove(sessionKey)
                }
                CMD_END -> {
                    listeners.forEach { it.onCallEnded(sessionId) }
                    sessions.remove(sessionKey)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun handleInvite(
        sessionId: ByteArray,
        qualityId: Int,
        deliveryHash: ByteArray,
        callHash: ByteArray,
        linkId: ByteArray?
    ) {
        if (sessionId.size != SESSION_ID_SIZE || callHash.isEmpty()) return
        val key = RnsHex.encode(sessionId)
        // Invites can be retried by the caller. Don't spam the UI / create multiple "incoming call"
        // presentations for the same session.
        if (sessions.containsKey(key)) return
        val identity = if (deliveryHash.isNotEmpty()) {
            RnsNode.recallIdentity(deliveryHash)
        } else {
            null
        }
        val remoteDest = if (identity != null) {
            buildCallDestination(identity, callHash)
        } else {
            RnsDestination.fromHash(callHash, RnsDestination.PLAIN)
        }
        val quality = MeshCallQuality.fromId(qualityId)
        val session = MeshCallSession(
            sessionId = sessionId,
            remoteDeliveryHash = deliveryHash,
            remoteCallHash = callHash,
            remoteDestination = remoteDest,
            quality = quality,
            outgoing = false,
            linkId = linkId
        )
        sessions[key] = session
        listeners.forEach { it.onIncomingInvite(session) }
    }

    private fun updateSessionLink(sessionId: ByteArray, linkId: ByteArray?) {
        if (linkId == null) return
        val session = sessions[RnsHex.encode(sessionId)] ?: return
        if (session.linkId == null || !session.linkId!!.contentEquals(linkId)) {
            session.linkId = linkId
        }
    }

    private fun buildControlPayload(
        cmd: Int,
        sessionId: ByteArray,
        qualityId: Int,
        deliveryHash: ByteArray? = null,
        callHash: ByteArray? = null
    ): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        val extra = when {
            deliveryHash != null && callHash != null -> 5
            else -> 3
        }
        packer.packArrayHeader(extra)
        packer.packInt(cmd)
        packer.packBinaryHeader(sessionId.size)
        packer.writePayload(sessionId)
        packer.packInt(qualityId)
        if (deliveryHash != null && callHash != null) {
            packer.packBinaryHeader(deliveryHash.size)
            packer.writePayload(deliveryHash)
            packer.packBinaryHeader(callHash.size)
            packer.writePayload(callHash)
        }
        packer.close()
        val payload = packer.toByteArray()
        val out = ByteArray(2 + payload.size)
        out[0] = PAYLOAD_PREFIX
        out[1] = TYPE_CONTROL
        System.arraycopy(payload, 0, out, 2, payload.size)
        return out
    }

    private fun getSession(sessionId: ByteArray): MeshCallSession? {
        return sessions[RnsHex.encode(sessionId)]
    }
}
