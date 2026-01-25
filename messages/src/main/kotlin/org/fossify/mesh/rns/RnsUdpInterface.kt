package org.fossify.mesh.rns

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
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
    private var thread: Thread? = null

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        val bindAddr = InetAddress.getByName("0.0.0.0")
        socket = DatagramSocket(listenPort, bindAddr).apply {
            broadcast = true
            reuseAddress = true
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
}
