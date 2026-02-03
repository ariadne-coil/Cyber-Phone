package org.fossify.phone.mesh

import android.telecom.Connection
import android.telecom.DisconnectCause
import java.util.concurrent.atomic.AtomicInteger

class MeshCallConnection(
    private val sessionId: ByteArray,
    private val outgoing: Boolean
) : Connection() {
    private val sequence = AtomicInteger(0)

    fun nextSequence(): Int = sequence.getAndIncrement()

    override fun onAnswer() {
        MeshCallController.answerCall(sessionId)
    }

    override fun onReject() {
        MeshCallController.rejectCall(sessionId)
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
    }

    override fun onDisconnect() {
        MeshCallController.endCall(sessionId)
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
    }

    override fun onAbort() {
        onDisconnect()
    }

    override fun onHold() {
        setOnHold()
    }

    override fun onUnhold() {
        setActive()
    }
}
