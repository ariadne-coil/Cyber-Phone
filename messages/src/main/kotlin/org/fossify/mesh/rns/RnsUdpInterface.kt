package org.fossify.mesh.rns

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class RnsUdpInterface(
    override val name: String,
    private val listenPort: Int,
    private val forwardAddress: String,
    private val forwardPort: Int,
    private val inboundHandler: (ByteArray, RnsInterface) -> Unit,
    private val bindAddress: InetAddress? = null,
    private val preferredMulticastInterface: NetworkInterface? = null,
    private val extraForwardAddresses: List<String> = emptyList(),
    // If set, we will join this multicast group (best-effort) and also forward packets to it.
    private val multicastGroupAddress: String? = null,
    // Test hook: allow remembering loopback peers on local JVM transports.
    private val allowLoopbackPeers: Boolean = false
) : RnsInterface {
    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var multicastGroup: InetAddress? = null
    private var multicastInterface: NetworkInterface? = null
    private var thread: Thread? = null
    private val peers = LinkedHashMap<InetAddress, Long>()
    private val rxCount = AtomicLong(0L)
    private val txCount = AtomicLong(0L)
    private val lastRxMs = AtomicLong(0L)
    private val lastTxMs = AtomicLong(0L)

    fun getPeerCount(): Int = synchronized(peers) { peers.size }

    fun getRxCount(): Long = rxCount.get()

    fun getTxCount(): Long = txCount.get()

    fun getLastRxMs(): Long = lastRxMs.get()

    fun getLastTxMs(): Long = lastTxMs.get()

    fun sendTo(raw: ByteArray, address: InetAddress, port: Int = forwardPort) {
        try {
            val packet = DatagramPacket(raw, raw.size, address, port)
            socket?.send(packet)
            txCount.incrementAndGet()
            lastTxMs.set(System.currentTimeMillis())
        } catch (_: Exception) {
        }
    }

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        val bindAddr = bindAddress ?: InetAddress.getByName("0.0.0.0")
        val groupInet = multicastGroupAddress?.let { InetAddress.getByName(it) }
        val forwardInet = InetAddress.getByName(forwardAddress)

        // Use a MulticastSocket if we need multicast reception (it still works for unicast/broadcast too).
        socket = if (groupInet != null && groupInet.isMulticastAddress) {
            MulticastSocket(null).apply {
                reuseAddress = true
                broadcast = true
                timeToLive = 1
                multicastGroup = groupInet
                // Always bind, even if joining fails.
                bind(InetSocketAddress(bindAddr, listenPort))
                try {
                    val iface = preferredMulticastInterface ?: findMulticastInterface()
                    multicastInterface = iface
                    if (iface != null) {
                        networkInterface = iface
                        joinGroup(InetSocketAddress(groupInet, listenPort), iface)
                    } else {
                        joinGroup(groupInet)
                    }
                } catch (_: Exception) {
                    // Best-effort: we can still function via unicast/broadcast.
                }
            }
        } else if (forwardInet.isMulticastAddress) {
            // Back-compat: if forwardAddress itself is multicast, join it.
            MulticastSocket(null).apply {
                reuseAddress = true
                broadcast = true
                timeToLive = 1
                multicastGroup = forwardInet
                bind(InetSocketAddress(bindAddr, listenPort))
                try {
                    val iface = preferredMulticastInterface ?: findMulticastInterface()
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
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(bindAddr, listenPort))
            }
        }

        thread = Thread {
            val buffer = ByteArray(2048)
            while (running.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    if (packet.length > 0) {
                        rememberPeer(packet.socketAddress)
                        val data = packet.data.copyOfRange(0, packet.length)
                        rxCount.incrementAndGet()
                        lastRxMs.set(System.currentTimeMillis())
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
            val addresses = LinkedHashSet<String>().apply {
                add(forwardAddress)
                addAll(extraForwardAddresses)
                multicastGroupAddress?.let { add(it) }
            }
            addresses.forEach { address ->
                val addr = InetAddress.getByName(address)
                val packet = DatagramPacket(raw, raw.size, addr, forwardPort)
                socket?.send(packet)
            }
            // Also send directly to peers we've seen on this interface. This makes LAN routing work even
            // on networks that block multicast/broadcast but still allow unicast between clients.
            val peerSnapshot = synchronized(peers) {
                val now = System.currentTimeMillis()
                trimPeersLocked(now)
                peers.keys.toList()
            }
            peerSnapshot.forEach { peer ->
                val packet = DatagramPacket(raw, raw.size, peer, listenPort)
                socket?.send(packet)
            }
            txCount.incrementAndGet()
            lastTxMs.set(System.currentTimeMillis())
        } catch (_: Exception) {
        }
    }

    private fun rememberPeer(socketAddress: SocketAddress?) {
        val remote = socketAddress as? InetSocketAddress ?: return
        val addr = remote.address ?: return
        if (addr.isAnyLocalAddress) return
        if (!allowLoopbackPeers && addr.isLoopbackAddress) return
        synchronized(peers) {
            peers[addr] = System.currentTimeMillis()
            trimPeersLocked(System.currentTimeMillis())
        }
    }

    private fun trimPeersLocked(now: Long) {
        // Drop stale peers and bound memory. This list is only used for best-effort LAN unicast.
        val iterator = peers.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > 5 * 60_000L) {
                iterator.remove()
            }
        }
        while (peers.size > 32) {
            val firstKey = peers.entries.firstOrNull()?.key ?: break
            peers.remove(firstKey)
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
