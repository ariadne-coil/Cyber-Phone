package org.fossify.mesh.wifidirect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log

class MeshWifiDirectController(
    private val context: Context,
    private val listener: (WifiP2pGroup) -> Unit
) {
    companion object {
        private const val TAG = "MeshWifiDirect"
    }

    private val manager: WifiP2pManager? =
        context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel: WifiP2pManager.Channel? =
        manager?.initialize(context, context.mainLooper, null)
    private var receiver: BroadcastReceiver? = null
    private var registered = false

    fun start() {
        if (manager == null || channel == null) return
        registerReceiver()
        createGroup()
        discoverPeers()
    }

    fun stop() {
        if (manager == null || channel == null) return
        try {
            manager.removeGroup(channel, null)
        } catch (_: Exception) {
        }
        unregisterReceiver()
    }

    private fun registerReceiver() {
        if (registered) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                when (action) {
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val info = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                        if (info?.isConnected == true) {
                            requestGroupInfo()
                        }
                    }
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                            discoverPeers()
                            requestGroupInfo()
                        }
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

    private fun createGroup() {
        try {
            manager?.createGroup(channel, null)
        } catch (e: Exception) {
            Log.w(TAG, "createGroup failed", e)
        }
    }

    private fun discoverPeers() {
        try {
            manager?.discoverPeers(channel, null)
        } catch (e: Exception) {
            Log.w(TAG, "discoverPeers failed", e)
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
