package org.fossify.phone.mesh

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.DisconnectCause
import android.telecom.TelecomManager
import org.fossify.mesh.call.MeshCallQuality
import org.fossify.mesh.call.MeshCallRouter
import org.fossify.mesh.lxmf.LxmfAddress
import org.fossify.mesh.rns.RnsHex
import java.util.concurrent.ConcurrentHashMap

object MeshCallController : MeshCallRouter.Listener {
    private val sessions = ConcurrentHashMap<String, MeshCallSessionState>()
    private var appContext: Context? = null

    data class MeshCallSessionState(
        val session: MeshCallRouter.MeshCallSession,
        var connection: MeshCallConnection? = null,
        var audioEngine: MeshAudioEngine? = null
    )

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            MeshCallRouter.addListener(this)
        }
    }

    fun shutdown() {
        MeshCallRouter.removeListener(this)
        sessions.clear()
        appContext = null
    }

    fun placeMeshCall(
        context: Context,
        remoteDeliveryHash: ByteArray,
        remoteCallHash: ByteArray,
        remoteDestination: org.fossify.mesh.rns.RnsDestination,
        quality: MeshCallQuality,
        displayName: String?,
        phoneNumber: String?
    ) {
        val session = MeshCallRouter.createOutgoingSession(
            remoteDeliveryHash = remoteDeliveryHash,
            remoteCallHash = remoteCallHash,
            remoteDestination = remoteDestination,
            quality = quality
        )
        sessions[RnsHex.encode(session.sessionId)] = MeshCallSessionState(session)
        val meshAddress = LxmfAddress.encode(remoteDeliveryHash)
        val uri = Uri.parse(meshAddress)
        val extras = Bundle().apply {
            putByteArray(MeshCallConstants.EXTRA_SESSION_ID, session.sessionId)
            putByteArray(MeshCallConstants.EXTRA_REMOTE_CALL_HASH, remoteCallHash)
            putByteArray(MeshCallConstants.EXTRA_REMOTE_DELIVERY_HASH, remoteDeliveryHash)
            putInt(MeshCallConstants.EXTRA_CALL_QUALITY, quality.id)
            putString(MeshCallConstants.EXTRA_DISPLAY_NAME, displayName)
            putString(MeshCallConstants.EXTRA_PHONE_NUMBER, phoneNumber)
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, MeshCallAccount.getHandle(context))
        }
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        telecom.placeCall(uri, extras)
        MeshCallRouter.sendInvite(session)
    }

    fun attachConnection(sessionId: ByteArray, connection: MeshCallConnection) {
        val key = RnsHex.encode(sessionId)
        sessions[key]?.connection = connection
    }

    fun answerCall(sessionId: ByteArray) {
        val session = sessions[RnsHex.encode(sessionId)] ?: return
        MeshCallRouter.sendAccept(sessionId)
        startAudio(session)
        session.connection?.setActive()
    }

    fun rejectCall(sessionId: ByteArray) {
        MeshCallRouter.sendDecline(sessionId)
        endSession(sessionId, DisconnectCause(DisconnectCause.REJECTED))
    }

    fun endCall(sessionId: ByteArray) {
        MeshCallRouter.sendEnd(sessionId)
        endSession(sessionId, DisconnectCause(DisconnectCause.LOCAL))
    }

    private fun endSession(sessionId: ByteArray, cause: DisconnectCause) {
        val key = RnsHex.encode(sessionId)
        val state = sessions.remove(key) ?: return
        state.audioEngine?.stop()
        state.connection?.setDisconnected(cause)
        state.connection?.destroy()
    }

    private fun startAudio(state: MeshCallSessionState) {
        if (state.audioEngine != null) return
        val engine = MeshAudioEngine(state.session.quality) { frame ->
            val seq = state.connection?.nextSequence() ?: 0
            MeshCallRouter.sendAudioFrame(state.session.sessionId, seq, frame)
        }
        state.audioEngine = engine
        engine.start()
    }

    override fun onIncomingInvite(session: MeshCallRouter.MeshCallSession) {
        val context = appContext ?: return
        val key = RnsHex.encode(session.sessionId)
        if (sessions.containsKey(key)) return
        sessions[key] = MeshCallSessionState(session)
        val meshAddress = LxmfAddress.encode(session.remoteDeliveryHash)
        val extras = Bundle().apply {
            putByteArray(MeshCallConstants.EXTRA_SESSION_ID, session.sessionId)
            putByteArray(MeshCallConstants.EXTRA_REMOTE_CALL_HASH, session.remoteCallHash)
            putByteArray(MeshCallConstants.EXTRA_REMOTE_DELIVERY_HASH, session.remoteDeliveryHash)
            putInt(MeshCallConstants.EXTRA_CALL_QUALITY, session.quality.id)
            putString(MeshCallConstants.EXTRA_PHONE_NUMBER, meshAddress)
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, MeshCallAccount.getHandle(context))
        }
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        telecom.addNewIncomingCall(MeshCallAccount.getHandle(context), extras)
    }

    override fun onCallAccepted(sessionId: ByteArray) {
        val state = sessions[RnsHex.encode(sessionId)] ?: return
        startAudio(state)
        state.connection?.setActive()
    }

    override fun onCallDeclined(sessionId: ByteArray) {
        endSession(sessionId, DisconnectCause(DisconnectCause.REMOTE))
    }

    override fun onCallEnded(sessionId: ByteArray) {
        endSession(sessionId, DisconnectCause(DisconnectCause.REMOTE))
    }

    override fun onAudioFrame(sessionId: ByteArray, sequence: Int, payload: ByteArray) {
        val state = sessions[RnsHex.encode(sessionId)] ?: return
        state.audioEngine?.enqueueFrame(payload)
    }
}
