package org.fossify.mesh.rns

import java.net.InetAddress
import java.net.NetworkInterface

data class RnsNetworkConfig(
    // IPv4 address assigned to the active network (Wi-Fi). Used for computing broadcast/subnet probing.
    val localAddress: InetAddress? = null,
    val broadcastAddress: InetAddress? = null,
    val netmask: InetAddress? = null,
    val networkPrefixLength: Int? = null,
    val multicastInterface: NetworkInterface? = null
)
