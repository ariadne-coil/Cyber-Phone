package org.fossify.mesh.wifiaware

import android.content.Context
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

class MeshWifiAwareController(
    private val context: Context,
    private val onPayload: (ByteArray) -> Unit
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

    fun start() {
        val aware = manager ?: return
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
    }

    fun send(raw: ByteArray) {
        val framed = ByteBuffer.allocate(2 + raw.size)
        framed.putShort(raw.size.toShort())
        framed.put(raw)
        val payload = framed.array()
        peers.values.forEach { peer ->
            publishSession?.sendMessage(peer, 0, payload)
        }
    }

    private fun startPublish(session: android.net.wifi.aware.WifiAwareSession) {
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
                peers[peerHandle.hashCode().toString()] = peerHandle
            }
        }, null)
    }

    private fun startSubscribe(session: android.net.wifi.aware.WifiAwareSession) {
        val config = SubscribeConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .build()
        session.subscribe(config, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                subscribeSession = session
            }

            override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray?, matchFilter: MutableList<ByteArray>?) {
                peers[peerHandle.hashCode().toString()] = peerHandle
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
            onPayload(payload)
        }
        val remaining = ByteArray(buffer.remaining())
        buffer.get(remaining)
        buffer.clear()
        buffer.put(remaining)
    }
}
