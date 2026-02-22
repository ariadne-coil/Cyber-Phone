package org.fossify.mesh.wifiaware

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class MeshWifiAwareController(
    private val context: Context,
    private val onPayload: (ByteArray) -> Unit,
    private val onPeerDiscovered: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "MeshWifiAware"
        private const val SERVICE_NAME = "cyber-phone-mesh"
        private const val MAX_CHUNK = 240
    }

    private val manager: WifiAwareManager? =
        context.applicationContext.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private val peers = ConcurrentHashMap<String, PeerHandle>()
    private val buffers = ConcurrentHashMap<String, ByteBuffer>()
    private val messageCounter = AtomicInteger(1)

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun canUseWifiAware(): Boolean {
        if (!hasPermission(Manifest.permission.ACCESS_WIFI_STATE) || !hasPermission(Manifest.permission.CHANGE_WIFI_STATE)) {
            return false
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun start() {
        val aware = manager ?: return
        if (!canUseWifiAware()) {
            MeshWifiAwareState.setActive(false)
            MeshWifiAwareState.setPeers(0)
            return
        }
        aware.attach(object : AttachCallback() {
            override fun onAttached(session: android.net.wifi.aware.WifiAwareSession) {
                MeshWifiAwareState.setActive(true)
                startPublish(session)
                startSubscribe(session)
            }

            override fun onAttachFailed() {
                MeshWifiAwareState.setActive(false)
            }
        }, null)
    }

    fun stop() {
        publishSession?.close()
        subscribeSession?.close()
        MeshWifiAwareState.setActive(false)
        MeshWifiAwareState.setPeers(0)
        peers.clear()
    }

    fun send(raw: ByteArray) {
        if (!canUseWifiAware()) return
        MeshWifiAwareState.markTx()
        val framed = ByteBuffer.allocate(2 + raw.size)
        framed.putShort(raw.size.toShort())
        framed.put(raw)
        val payload = framed.array()
        val session = publishSession ?: subscribeSession
        if (session == null) return

        peers.values.forEach { peer ->
            var offset = 0
            while (offset < payload.size) {
                val end = (offset + MAX_CHUNK).coerceAtMost(payload.size)
                val chunk = payload.copyOfRange(offset, end)
                try {
                    session.sendMessage(peer, messageCounter.getAndIncrement(), chunk)
                } catch (_: Exception) {
                }
                offset = end
            }
        }
    }

    private fun startPublish(session: android.net.wifi.aware.WifiAwareSession) {
        if (!canUseWifiAware()) return
        val config = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .build()
        session.publish(config, object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) {
                publishSession = session
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                handleMessage(peerHandle, message)
            }

            override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray?, matchFilter: MutableList<ByteArray>?) {
                val key = peerHandle.hashCode().toString()
                val isNew = peers.put(key, peerHandle) == null
                MeshWifiAwareState.setPeers(peers.size)
                if (isNew) {
                    try {
                        onPeerDiscovered?.invoke()
                    } catch (_: Exception) {
                    }
                }
            }
        }, null)
    }

    private fun startSubscribe(session: android.net.wifi.aware.WifiAwareSession) {
        if (!canUseWifiAware()) return
        val config = SubscribeConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .build()
        session.subscribe(config, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                subscribeSession = session
            }

            override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray?, matchFilter: MutableList<ByteArray>?) {
                val key = peerHandle.hashCode().toString()
                val isNew = peers.put(key, peerHandle) == null
                MeshWifiAwareState.setPeers(peers.size)
                if (isNew) {
                    try {
                        onPeerDiscovered?.invoke()
                    } catch (_: Exception) {
                    }
                }
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                handleMessage(peerHandle, message)
            }
        }, null)
    }

    private fun handleMessage(peerHandle: PeerHandle, message: ByteArray) {
        val key = peerHandle.hashCode().toString()
        val buffer = buffers.getOrPut(key) { ByteBuffer.allocate(65536) }
        if (buffer.remaining() < message.size) {
            buffer.clear()
        }
        buffer.put(message)
        buffer.flip()
        while (buffer.remaining() >= 2) {
            buffer.mark()
            val len = buffer.short.toInt() and 0xFFFF
            if (buffer.remaining() < len) {
                buffer.reset()
                break
            }
            val payload = ByteArray(len)
            buffer.get(payload)
            MeshWifiAwareState.markRx()
            onPayload(payload)
        }
        val remaining = ByteArray(buffer.remaining())
        buffer.get(remaining)
        buffer.clear()
        buffer.put(remaining)
    }
}
