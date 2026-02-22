package org.fossify.phone.mesh.voip

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import org.fossify.mesh.MeshConfig
import org.fossify.mesh.MeshMode
import org.fossify.mesh.call.MeshCallRouter
import org.fossify.mesh.lxmf.LxmfAddress
import org.fossify.mesh.rns.RnsHex
import org.fossify.phone.R

/**
 * Receives incoming mesh call invites and presents a full-screen call UI via notification.
 *
 * This is the in-app VoIP call path (no Telecom).
 */
object MeshVoipCallHandler : MeshCallRouter.Listener {
    private const val CHANNEL_ID = "mesh_calls"

    fun init(context: Context) {
        ensureChannel(context)
        MeshCallRouter.addListener(this)
    }

    override fun onIncomingInvite(session: MeshCallRouter.MeshCallSession) {
        val context = appContext ?: return
        val meshMode = MeshConfig.newInstance(context).getMeshMode()
        if (meshMode == MeshMode.STANDARD_ONLY) {
            // Mesh calling disabled by user setting.
            return
        }
        val meshAddress = if (session.remoteDeliveryHash.isNotEmpty()) {
            LxmfAddress.encode(session.remoteDeliveryHash)
        } else {
            ""
        }

        // If the app is already in the foreground, bring up the call UI directly to avoid
        // reliance on notification visibility/fullscreen-intent policies.
        val isForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (isForeground) {
            MeshVoipCallActivity.startIncoming(
                context = context,
                sessionId = session.sessionId,
                remoteDeliveryHash = session.remoteDeliveryHash,
                qualityId = session.quality.id,
                meshAddress = meshAddress
            )
            return
        }

        showIncomingCallNotification(context, session, meshAddress)
    }

    override fun onCallAccepted(sessionId: ByteArray) {
        cancel(sessionId)
    }

    override fun onCallDeclined(sessionId: ByteArray) {
        cancel(sessionId)
    }

    override fun onCallEnded(sessionId: ByteArray) {
        cancel(sessionId)
    }

    override fun onAudioFrame(sessionId: ByteArray, sequence: Int, payload: ByteArray) = Unit

    // --- internals ---

    @Volatile
    private var appContext: Context? = null

    private fun ensureChannel(context: Context) {
        appContext = context.applicationContext
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.ongoing_call),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), attrs)
        }
        nm.createNotificationChannel(channel)
    }

    @SuppressLint("MissingPermission")
    private fun showIncomingCallNotification(context: Context, session: MeshCallRouter.MeshCallSession, meshAddress: String) {
        if (!canPostNotifications(context)) return

        val contentIntent = Intent(context, MeshVoipCallActivity::class.java).apply {
            putExtra("mesh_voip_incoming", true)
            putExtra("mesh_voip_session_id", session.sessionId)
            putExtra("mesh_voip_remote_delivery_hash", session.remoteDeliveryHash)
            putExtra("mesh_voip_call_quality", session.quality.id)
            putExtra("mesh_voip_mesh_address", meshAddress)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val pending = PendingIntent.getActivity(
            context,
            notificationId(session.sessionId),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_phone_vector)
            .setContentTitle(context.getString(R.string.ongoing_call))
            .setContentText(context.getString(R.string.is_calling))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId(session.sessionId), notification)
    }

    private fun cancel(sessionId: ByteArray) {
        val context = appContext ?: return
        if (!canPostNotifications(context)) return
        NotificationManagerCompat.from(context).cancel(notificationId(sessionId))
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun notificationId(sessionId: ByteArray): Int {
        // Session ids are 8 bytes. Use low 4 bytes as stable notification id.
        val hex = RnsHex.encode(sessionId)
        return hex.takeLast(8).toLong(16).toInt()
    }
}
