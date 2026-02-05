package org.fossify.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
import org.fossify.mesh.rns.RnsNetworkConfig

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
            val networkConfig = buildNetworkConfig()
            RnsNode.start(this, routingEnabled, networkConfig)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
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

    private fun buildNetworkConfig(): RnsNetworkConfig? {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val dhcp = wifi.dhcpInfo ?: return null
        if (dhcp.ipAddress == 0) return null
        val ipAddr = inetFromDhcpInt(dhcp.ipAddress)
        val iface = try { NetworkInterface.getByInetAddress(ipAddr) } catch (_: Exception) { null }
        val ifaceAddr = iface?.interfaceAddresses?.firstOrNull { it.address is Inet4Address && it.address == ipAddr }

        val broadcastFromIface = ifaceAddr?.broadcast ?: run {
            val prefix = ifaceAddr?.networkPrefixLength?.toInt() ?: -1
            if (prefix in 0..32) {
                computeBroadcast(ipAddr as Inet4Address, prefix)
            } else {
                null
            }
        }

        val netmaskAddr = if (dhcp.netmask != 0) {
            inetFromDhcpInt(dhcp.netmask)
        } else {
            null
        }

        val broadcastAddr = when {
            netmaskAddr != null -> computeBroadcast(ipAddr, netmaskAddr)
            broadcastFromIface != null -> broadcastFromIface
            else -> InetAddress.getByName("255.255.255.255")
        }

        return RnsNetworkConfig(
            localAddress = ipAddr,
            broadcastAddress = broadcastAddr,
            netmask = netmaskAddr,
            networkPrefixLength = ifaceAddr?.networkPrefixLength?.toInt(),
            multicastInterface = iface
        )
    }

    private fun inetFromDhcpInt(value: Int): InetAddress {
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        return InetAddress.getByAddress(bytes)
    }

    private fun computeBroadcast(address: InetAddress, mask: InetAddress): InetAddress {
        val addrInt = ByteBuffer.wrap(address.address).int
        val maskInt = ByteBuffer.wrap(mask.address).int
        val broadcastInt = addrInt or maskInt.inv()
        return InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(broadcastInt).array())
    }

    private fun computeBroadcast(address: Inet4Address, prefixLength: Int): InetAddress {
        val addrInt = ByteBuffer.wrap(address.address).int
        val mask = if (prefixLength == 0) 0 else -1 shl (32 - prefixLength)
        val broadcastInt = addrInt or mask.inv()
        return InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(broadcastInt).array())
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
                    forwardPort = 4243,
                    inboundHandler = { raw, ifaceRef ->
                        RnsNode.handleIncomingFromInterface(raw, ifaceRef)
                    }
                )
                RnsNode.addInterface(iface)
                wifiDirectInterfaceAdded = true
                RnsNode.announceAll()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = listOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_ADVERTISE
            )
            if (needed.any { ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                return
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return
            }
        }
        bleController = MeshBleController(
            context = this,
            onPayload = { raw ->
                bleInterface?.let { RnsNode.handleIncomingFromInterface(raw, it) }
            },
            onPeerConnected = {
                // Kick an announce as soon as we have a neighbor over BLE.
                RnsNode.announceAll()
            }
        ).also { it.start() }
        if (!org.fossify.mesh.ble.MeshBleState.isActive()) {
            bleController = null
            return
        }
        bleInterface = object : RnsInterface {
            override val name: String = "ble"
            override fun start() {}
            override fun stop() {}
            override fun send(raw: ByteArray) {
                bleController?.send(raw)
            }
        }
        bleInterface?.let { RnsNode.addInterface(it) }
        RnsNode.announceAll()
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
        wifiAwareController = MeshWifiAwareController(
            context = this,
            onPayload = { raw ->
                wifiAwareInterface?.let { RnsNode.handleIncomingFromInterface(raw, it) }
            },
            onPeerDiscovered = {
                // Ensure peers receive our announce quickly after discovery, not only on the long interval.
                RnsNode.announceAll()
            }
        ).also { it.start() }
        wifiAwareInterface = object : RnsInterface {
            override val name: String = "wifiaware"
            override fun start() {}
            override fun stop() {}
            override fun send(raw: ByteArray) {
                wifiAwareController?.send(raw)
            }
        }
        wifiAwareInterface?.let { RnsNode.addInterface(it) }
        RnsNode.announceAll()
    }

    private fun stopWifiAware() {
        wifiAwareController?.stop()
        wifiAwareController = null
        MeshWifiAwareState.setActive(false)
        wifiAwareInterface?.let { RnsNode.removeInterface(it.name) }
        wifiAwareInterface = null
    }
}
