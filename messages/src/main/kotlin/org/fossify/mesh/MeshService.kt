package org.fossify.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import org.fossify.messages.R
import org.fossify.mesh.MeshConfig
import org.fossify.mesh.call.MeshCallRouter
import org.fossify.mesh.lxmf.LxmfRouter
import org.fossify.mesh.rns.RnsNode
import org.fossify.mesh.rns.RnsUdpInterface
import org.fossify.mesh.wifidirect.MeshWifiDirectController
import org.fossify.mesh.wifidirect.MeshWifiDirectState
import org.fossify.mesh.ble.MeshBleController
import org.fossify.mesh.rns.RnsInterface
import org.fossify.mesh.wifiaware.MeshWifiAwareController
import org.fossify.mesh.wifiaware.MeshWifiAwareState

class MeshService : Service() {
    companion object {
        const val ACTION_START = "org.fossify.mesh.action.START"
        const val ACTION_STOP = "org.fossify.mesh.action.STOP"
        private const val CHANNEL_ID = "mesh_service"
        private const val NOTIFICATION_ID = 1401
        private const val TAG = "MeshService"
        private const val STATUS_UPDATE_INTERVAL_MS = 30_000L
    }

    private val lxmfListener: (org.fossify.mesh.lxmf.LxmfMessage) -> Unit = {
        org.fossify.mesh.lxmf.LxmfStore.storeIncoming(this, it)
    }
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusUpdater = object : Runnable {
        override fun run() {
            updateNotification()
            statusHandler.postDelayed(this, STATUS_UPDATE_INTERVAL_MS)
        }
    }
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiDirectController: MeshWifiDirectController? = null
    private var wifiDirectInterfaceAdded = false
    private var bleController: MeshBleController? = null
    private var bleInterface: RnsInterface? = null
    private var wifiAwareController: MeshWifiAwareController? = null
    private var wifiAwareInterface: RnsInterface? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                RnsNode.stop()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (!ensureForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        statusHandler.postDelayed(statusUpdater, STATUS_UPDATE_INTERVAL_MS)

        return try {
            acquireMulticastLock()
            MeshIdentityStore.getOrCreate(this)
            val routingEnabled = MeshConfig.newInstance(this).meshRoutingEnabled
            RnsNode.start(this, routingEnabled)
            LxmfRouter.start(this)
            LxmfRouter.addListener(lxmfListener)
            RnsNode.announceAll()
            MeshCallRouter.start(this)
            startWifiDirect()
            startBle()
            startWifiAware()
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
        MeshCallRouter.stop()
        stopWifiDirect()
        stopBle()
        stopWifiAware()
        RnsNode.stop()
        statusHandler.removeCallbacks(statusUpdater)
        releaseMulticastLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        ensureNotificationChannel()
        val statusText = buildStatusText()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.mesh_service_title))
            .setContentText(statusText)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun ensureForeground(): Boolean {
        return try {
            startForeground(NOTIFICATION_ID, buildNotification())
            true
        } catch (e: Exception) {
            if (isForegroundStartNotAllowed(e)) {
                Log.w(TAG, "Foreground service start not allowed", e)
                return false
            }
            Log.e(TAG, "Failed to start foreground service", e)
            false
        }
    }

    private fun isForegroundStartNotAllowed(e: Exception): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
    }

    private fun updateNotification() {
        val notification = buildNotification()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildStatusText(): String {
        val neighbors = RnsNode.getDirectNeighborCount()
        val routingEnabled = MeshConfig.newInstance(this).meshRoutingEnabled
        val routingStatus = if (routingEnabled && RnsNode.hasRecentRoutingActivity()) {
            getString(R.string.mesh_routing_in_use)
        } else {
            getString(R.string.mesh_routing_idle)
        }
        return getString(R.string.mesh_service_status, neighbors, routingStatus)
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

    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("mesh-multicast").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire multicast lock", e)
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.release()
        } catch (_: Exception) {
        } finally {
            multicastLock = null
        }
    }

    private fun startWifiDirect() {
        val config = MeshConfig.newInstance(this)
        if (!config.meshWifiDirectEnabled) return
        if (wifiDirectController != null) return
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(this, permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return
        }
        wifiDirectController = MeshWifiDirectController(this) { group ->
            if (!wifiDirectInterfaceAdded) {
                val iface = RnsUdpInterface(
                    name = "udp-wfd",
                    listenPort = 4243,
                    forwardAddress = "192.168.49.255",
                    forwardPort = 4243
                ) { raw, ifaceRef ->
                    RnsNode.handleIncomingFromInterface(raw, ifaceRef)
                }
                RnsNode.addInterface(iface)
                wifiDirectInterfaceAdded = true
            }
        }
        wifiDirectController?.start()
    }

    private fun stopWifiDirect() {
        wifiDirectController?.stop()
        wifiDirectController = null
        MeshWifiDirectState.clear()
        if (wifiDirectInterfaceAdded) {
            RnsNode.removeInterface("udp-wfd")
            wifiDirectInterfaceAdded = false
        }
    }

    private fun startBle() {
        val config = MeshConfig.newInstance(this)
        if (!config.meshBleEnabled) return
        if (bleController != null) return
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            android.Manifest.permission.BLUETOOTH_CONNECT
        } else {
            android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(this, permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return
        }
        bleController = MeshBleController(this) { raw ->
            bleInterface?.let { RnsNode.handleIncomingFromInterface(raw, it) }
        }.also { it.start() }
        bleInterface = object : RnsInterface {
            override val name: String = "ble"
            override fun start() {}
            override fun stop() {}
            override fun send(raw: ByteArray) {
                bleController?.send(raw)
            }
        }
        bleInterface?.let { RnsNode.addInterface(it) }
    }

    private fun stopBle() {
        bleController?.stop()
        bleController = null
        bleInterface?.let { RnsNode.removeInterface(it.name) }
        bleInterface = null
    }

    private fun startWifiAware() {
        val config = MeshConfig.newInstance(this)
        if (!config.meshWifiAwareEnabled) return
        if (wifiAwareController != null) return
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI_AWARE)) return
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(this, permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return
        }
        wifiAwareController = MeshWifiAwareController(this) { raw ->
            wifiAwareInterface?.let { RnsNode.handleIncomingFromInterface(raw, it) }
        }.also { it.start() }
        wifiAwareInterface = object : RnsInterface {
            override val name: String = "wifiaware"
            override fun start() {}
            override fun stop() {}
            override fun send(raw: ByteArray) {
                wifiAwareController?.send(raw)
            }
        }
        wifiAwareInterface?.let { RnsNode.addInterface(it) }
    }

    private fun stopWifiAware() {
        wifiAwareController?.stop()
        wifiAwareController = null
        MeshWifiAwareState.setActive(false)
        wifiAwareInterface?.let { RnsNode.removeInterface(it.name) }
        wifiAwareInterface = null
    }
}
