package org.fossify.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.fossify.messages.R
import org.fossify.mesh.lxmf.LxmfRouter
import org.fossify.mesh.rns.RnsNode

class MeshService : Service() {
    companion object {
        const val ACTION_START = "org.fossify.mesh.action.START"
        const val ACTION_STOP = "org.fossify.mesh.action.STOP"
        private const val CHANNEL_ID = "mesh_service"
        private const val NOTIFICATION_ID = 1401
        private const val TAG = "MeshService"
    }

    private val lxmfListener: (org.fossify.mesh.lxmf.LxmfMessage) -> Unit = {
        org.fossify.mesh.lxmf.LxmfStore.storeIncoming(this, it)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                RnsNode.stop()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        return try {
            MeshIdentityStore.getOrCreate(this)
            val routingEnabled = MeshConfig.newInstance(this).meshRoutingEnabled
            RnsNode.start(this, routingEnabled)
            LxmfRouter.start(this)
            LxmfRouter.addListener(lxmfListener)
            START_STICKY
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mesh service", e)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        LxmfRouter.removeListener(lxmfListener)
        LxmfRouter.stop()
        RnsNode.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        ensureNotificationChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.mesh_service_title))
            .setContentText(getString(R.string.mesh_service_text))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.mesh_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }
}
