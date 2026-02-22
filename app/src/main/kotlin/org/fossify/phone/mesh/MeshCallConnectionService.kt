package org.fossify.phone.mesh

import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import androidx.core.net.toUri
import org.fossify.mesh.lxmf.LxmfAddress

class MeshCallConnectionService : ConnectionService() {
    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle,
        request: ConnectionRequest
    ): Connection {
        val extras = request.extras ?: Bundle()
        val sessionId = extras.getByteArray(MeshCallConstants.EXTRA_SESSION_ID) ?: ByteArray(0)
        val remoteDeliveryHash = extras.getByteArray(MeshCallConstants.EXTRA_REMOTE_DELIVERY_HASH) ?: ByteArray(0)
        val displayName = extras.getString(MeshCallConstants.EXTRA_DISPLAY_NAME)
        val connection = buildConnection(sessionId, displayName, remoteDeliveryHash, outgoing = true)
        MeshCallController.attachConnection(sessionId, connection)
        connection.setDialing()
        return connection
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle,
        request: ConnectionRequest
    ): Connection {
        val extras = request.extras ?: Bundle()
        val sessionId = extras.getByteArray(MeshCallConstants.EXTRA_SESSION_ID) ?: ByteArray(0)
        val remoteDeliveryHash = extras.getByteArray(MeshCallConstants.EXTRA_REMOTE_DELIVERY_HASH) ?: ByteArray(0)
        val displayName = extras.getString(MeshCallConstants.EXTRA_DISPLAY_NAME)
        val connection = buildConnection(sessionId, displayName, remoteDeliveryHash, outgoing = false)
        MeshCallController.attachConnection(sessionId, connection)
        connection.setRinging()
        return connection
    }

    private fun buildConnection(
        sessionId: ByteArray,
        displayName: String?,
        remoteDeliveryHash: ByteArray,
        outgoing: Boolean
    ): MeshCallConnection {
        val connection = MeshCallConnection(sessionId, outgoing)
        connection.connectionCapabilities = Connection.CAPABILITY_MUTE or Connection.CAPABILITY_SUPPORT_HOLD
        val address = if (remoteDeliveryHash.isNotEmpty()) {
            LxmfAddress.encode(remoteDeliveryHash).toUri()
        } else {
            Uri.fromParts("mesh", "", null)
        }
        connection.setAddress(address, CallLog.Calls.PRESENTATION_ALLOWED)
        if (!displayName.isNullOrBlank()) {
            connection.setCallerDisplayName(displayName, CallLog.Calls.PRESENTATION_ALLOWED)
        }
        return connection
    }
}
