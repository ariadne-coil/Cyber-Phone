package org.fossify.mesh.rns

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

class RnsUdpInterface(
    override val name: String,
    private val listenPort: Int,
    private val forwardAddress: String,
    private val forwardPort: Int,
    private val inboundHandler: (ByteArray, RnsInterface) -> Unit
) : RnsInterface {
    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var multicastGroup: InetAddress? = null
    private var multicastInterface: NetworkInterface? = null
    private var thread: Thread? = null

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        val bindAddr = InetAddress.getByName("0.0.0.0")
        val forwardInet = InetAddress.getByName(forwardAddress)
        socket = if (forwardInet.isMulticastAddress) {
            MulticastSocket(listenPort).apply {
                reuseAddress = true
                timeToLive = 1
                multicastGroup = forwardInet
                try {
                    val iface = findMulticastInterface()
                    multicastInterface = iface
                    if (iface != null) {
                        networkInterface = iface
                        joinGroup(InetSocketAddress(forwardInet, listenPort), iface)
                    } else {
                        joinGroup(forwardInet)
                    }
                } catch (_: Exception) {
                }
            }
        } else {
            DatagramSocket(listenPort, bindAddr).apply {
                broadcast = true
                reuseAddress = true
            }
        }

        thread = Thread {
            val buffer = ByteArray(2048)
            while (running.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    if (packet.length > 0) {
                        val data = packet.data.copyOfRange(0, packet.length)
                        inboundHandler(data, this)
                    }
                } catch (_: Exception) {
                    if (!running.get()) {
                        break
                    }
                }
            }
        }.apply { isDaemon = true }
        thread?.start()
    }

    override fun stop() {
        running.set(false)
        try {
            multicastGroup?.let { group ->
                val mcast = socket as? MulticastSocket
                val iface = multicastInterface
                if (mcast != null) {
                    if (iface != null) {
                        mcast.leaveGroup(InetSocketAddress(group, listenPort), iface)
                    } else {
                        mcast.leaveGroup(group)
                    }
                }
            }
            socket?.close()
        } catch (_: Exception) {
        }
        thread = null
        socket = null
    }

    override fun send(raw: ByteArray) {
        try {
            val addr = InetAddress.getByName(forwardAddress)
            val packet = DatagramPacket(raw, raw.size, addr, forwardPort)
            socket?.send(packet)
        } catch (_: Exception) {
        }
    }

    private fun findMulticastInterface(): NetworkInterface? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList().firstOrNull { iface ->
                iface.isUp && !iface.isLoopback && iface.supportsMulticast()
            }
        } catch (_: Exception) {
            null
        }
    }
}
