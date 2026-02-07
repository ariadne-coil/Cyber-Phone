package org.fossify.mesh.wifidirect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.WpsInfo
import android.util.Log

class MeshWifiDirectController(
    private val context: Context,
    private val listener: (WifiP2pGroup) -> Unit
) {
    companion object {
        private const val TAG = "MeshWifiDirect"
        private const val CONNECT_COOLDOWN_MS = 15_000L
    }

    private val manager: WifiP2pManager? =
        context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel: WifiP2pManager.Channel? =
        manager?.initialize(context, context.mainLooper, null)
    private var receiver: BroadcastReceiver? = null
    private var registered = false
    @Volatile
    private var isConnected = false
    @Volatile
    private var lastConnectAttemptMs = 0L

    fun start() {
        if (manager == null || channel == null) return
        registerReceiver()
        discoverPeers()
        requestGroupInfo()
    }

    fun stop() {
        if (manager == null || channel == null) return
        unregisterReceiver()
        // Be conservative on teardown. Some OEM stacks show disruptive user dialogs when we try to
        // forcibly remove the current P2P group ("Turn off Wi‑Fi Direct?" / "Turn off Sharing?").
        // Stopping discovery and canceling connect is sufficient to quiesce the controller.
        try {
            manager.stopPeerDiscovery(channel, null)
        } catch (_: Exception) {
        }
        try {
            manager.cancelConnect(channel, null)
        } catch (_: Exception) {
        }
    }

    private fun registerReceiver() {
        if (registered) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                when (action) {
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val info = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                        val connected = info?.isConnected == true
                        isConnected = connected
                        if (connected) {
                            requestGroupInfo()
                        } else {
                            // If we dropped the connection, restart discovery and try to reconnect.
                            discoverPeers()
                        }
                    }
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                            discoverPeers()
                            requestGroupInfo()
                        }
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        requestPeersAndMaybeConnect()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, filter)
        registered = true
    }

    private fun unregisterReceiver() {
        if (!registered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        } finally {
            registered = false
            receiver = null
        }
    }

    private fun discoverPeers() {
        try {
            manager?.discoverPeers(channel, null)
        } catch (e: Exception) {
            Log.w(TAG, "discoverPeers failed", e)
        }
    }

    private fun requestPeersAndMaybeConnect() {
        if (manager == null || channel == null) return
        if (isConnected) return
        val now = System.currentTimeMillis()
        if (now - lastConnectAttemptMs < CONNECT_COOLDOWN_MS) return

        try {
            manager.requestPeers(channel) { peers ->
                val devices = peers?.deviceList?.toList().orEmpty()
                val target = devices
                    .filter { it.status != WifiP2pDevice.CONNECTED }
                    .sortedBy { it.deviceAddress ?: it.deviceName ?: "" }
                    .firstOrNull()
                    ?: return@requestPeers

                connectTo(target)
            }
        } catch (e: Exception) {
            Log.w(TAG, "requestPeers failed", e)
        }
    }

    private fun connectTo(device: WifiP2pDevice) {
        if (manager == null || channel == null) return
        val address = device.deviceAddress ?: return
        lastConnectAttemptMs = System.currentTimeMillis()

        val config = WifiP2pConfig().apply {
            deviceAddress = address
            wps.setup = WpsInfo.PBC
            // Prefer being a client. If both sides do this, GO selection still works.
            groupOwnerIntent = 0
        }

        try {
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "connect() initiated to $address")
                }

                override fun onFailure(reason: Int) {
                    Log.w(TAG, "connect() failed to $address reason=$reason")
                    // Retry discovery, but keep cooldown.
                    discoverPeers()
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "connect failed", e)
        }
    }

    private fun requestGroupInfo() {
        try {
            manager?.requestGroupInfo(channel) { group ->
                if (group != null && group.isGroupOwner != null) {
                    MeshWifiDirectState.update(
                        ssid = group.networkName,
                        passphrase = group.passphrase,
                        isGroupOwner = group.isGroupOwner
                    )
                    listener(group)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "requestGroupInfo failed", e)
        }
    }
}
