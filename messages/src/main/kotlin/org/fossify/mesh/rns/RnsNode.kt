package org.fossify.mesh.rns

import android.content.Context
import java.util.LinkedHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer

object RnsNode {
    private const val DEFAULT_UDP_PORT = 4242
    private const val DEFAULT_MULTICAST_GROUP = "239.255.0.1"
    private const val LINK_REQUEST_RETRY_MS = 1_500L
    private const val LINK_REQUEST_RETRY_TICK_MS = 500L
    private const val PENDING_LINK_TIMEOUT_MS = 60_000L
    private const val MAX_HOPS = 128
    private const val ANNOUNCE_CACHE_LIMIT = 256
    private const val RESOURCE_CACHE_LIMIT = 256
    private const val RESOURCE_SEEN_LIMIT = 512
    private const val RESOURCE_REQUEST_LIMIT = 512
    private const val RESOURCE_REQUEST_COOLDOWN_MS = 15_000L
    private const val RESOURCE_ASSEMBLY_TIMEOUT_MS = 5 * 60 * 1000L
    private const val DIRECT_NEIGHBOR_TIMEOUT_MS = 5 * 60 * 1000L
    private const val ROUTING_ACTIVITY_WINDOW_MS = 2 * 60 * 1000L
    private const val ANNOUNCE_INTERVAL_MS = 10 * 60 * 1000L
    private const val PATH_REQUEST_MIN_INTERVAL_MS = 5_000L
    private const val PATH_REQUEST_MIN_INTERVAL_FLOOR_MS = 500L
    private const val PATH_REQUEST_TAG_LIMIT = 32_000
    private const val PATH_REQUEST_APP = "rnstransport"

    // Hard bounds/TTLs to prevent unbounded growth in hostile or lossy networks.
    // These values are intentionally conservative: they keep enough state for routing to work,
    // while ensuring a long-running node cannot OOM due to caches never being trimmed.
    private const val KNOWN_DESTINATION_TTL_MS = 24L * 60L * 60L * 1000L
    private const val KNOWN_DESTINATION_LIMIT = 4096
    private const val PATH_TABLE_TTL_MS = 60L * 60L * 1000L
    private const val PATH_TABLE_LIMIT = 4096
    private const val RECEIPT_TTL_MS = 60L * 60L * 1000L
    private const val RECEIPT_LIMIT = 4096

    private val interfaces = mutableListOf<RnsInterface>()
    private val running = AtomicBooleanState()
    private val pathTable = ConcurrentHashMap<String, PathEntry>()
    private val knownDestinations = ConcurrentHashMap<String, KnownDestination>()
    private val announceCache = LinkedHashMap<String, Long>()
    private val localDestinations = ConcurrentHashMap<String, LocalDestination>()
    private val resourceCache = ConcurrentHashMap<String, RnsResource>()
    private val resourceCacheOrder = LinkedHashMap<String, Long>()
    private val resourceSeen = LinkedHashMap<String, Long>()
    private val resourceRequests = ConcurrentHashMap<String, Long>()
    private val resourceAssemblers = ConcurrentHashMap<String, ResourceAssembler>()
    private val resourceListeners = CopyOnWriteArrayList<(RnsResource) -> Unit>()
    private val resourceAdvertisementListeners = CopyOnWriteArrayList<(RnsResourceAdvertisement) -> Unit>()
    private val linkResourceManager = RnsLinkResourceManager { data, link, meta ->
        if (meta.isRequest) {
            val requestId = meta.requestId ?: RnsHash.truncatedHash(RnsHash.sha256(data))
            link.handleRequest(data, link.owner, requestId, { linkInstance, packetType, context, payload ->
                sendLinkPacket(linkInstance, packetType, context, payload, null)
            }, { payload, responseId, isResponse ->
                sendLinkResource(link, payload, responseId, isResponse = isResponse, isRequest = false)
            })
            return@RnsLinkResourceManager
        }

        if (meta.isResponse) {
            link.handleResponse(data)
            return@RnsLinkResourceManager
        }

        val resource = RnsResource(
            hash = RnsHash.sha256(data),
            sourceHash = link.owner.hash,
            destinationHash = link.owner.hash,
            kind = RnsResourceKind.LXMF_MESSAGE,
            encrypted = false,
            timestamp = System.currentTimeMillis() / 1000L,
            data = data
        )
        resourceListeners.forEach { it(resource) }
    }
    private val pendingLinksById = ConcurrentHashMap<String, RnsLink>()
    private val pendingLinksByDestination = ConcurrentHashMap<String, String>()
    private val activeLinksById = ConcurrentHashMap<String, RnsLink>()
    private val activeLinksByDestination = ConcurrentHashMap<String, String>()
    private val pendingLinkResources = ConcurrentHashMap<String, CopyOnWriteArrayList<PendingLinkResource>>()
    private val pendingLinkPackets = ConcurrentHashMap<String, CopyOnWriteArrayList<PendingLinkPacket>>()
    private val lastRoutingActivityMs = AtomicLong(0L)
    private val pathRequestTags = LinkedHashMap<String, Long>()
    private val lastPathRequestMs = ConcurrentHashMap<String, Long>()
    private val announceListeners = CopyOnWriteArrayList<(RnsAnnounce, RnsPacket) -> Unit>()
    private val receipts = ConcurrentHashMap<String, RnsReceipt>()

    private val announceScheduler = Executors.newSingleThreadScheduledExecutor()
    private var announceFuture: ScheduledFuture<*>? = null

    private val unicastProbeScheduler = Executors.newSingleThreadScheduledExecutor()
    private var unicastProbeFuture: ScheduledFuture<*>? = null
    private val pendingLinkRetryScheduler = Executors.newSingleThreadScheduledExecutor()
    private var pendingLinkRetryFuture: ScheduledFuture<*>? = null
    @Volatile
    private var unicastProbeCursor = 0

    @Volatile
    private var networkConfig: RnsNetworkConfig? = null
    private var udpInterfaceRef: RnsUdpInterface? = null

    private var routingEnabled = false
    private val announceReceivedCount = AtomicLong(0L)
    private val lastPacketReceivedMs = AtomicLong(0L)
    private val rawPacketReceivedCount = AtomicLong(0L)
    private val lastPacketSentMs = AtomicLong(0L)
    private val rawPacketSentCount = AtomicLong(0L)

    fun start(context: Context, routing: Boolean, networkConfig: RnsNetworkConfig? = null) {
        if (running.setIfFalse()) {
            routingEnabled = routing
            this.networkConfig = networkConfig
            val broadcastAddress = networkConfig?.broadcastAddress?.hostAddress ?: "255.255.255.255"
            val preferredMcastInterface = networkConfig?.multicastInterface
            val udpInterface = RnsUdpInterface(
                name = "udp",
                listenPort = DEFAULT_UDP_PORT,
                forwardAddress = broadcastAddress,
                forwardPort = DEFAULT_UDP_PORT,
                inboundHandler = { raw, iface ->
                    handleIncoming(raw, iface)
                },
                preferredMulticastInterface = preferredMcastInterface,
                extraForwardAddresses = if (broadcastAddress != "255.255.255.255") listOf("255.255.255.255") else emptyList(),
                multicastGroupAddress = DEFAULT_MULTICAST_GROUP
            )
            udpInterfaceRef = udpInterface
            interfaces.add(udpInterface)
            interfaces.forEach { it.start() }
            registerPathRequestDestination()
            startAnnounceScheduler()
            startUnicastProbeScheduler()
            startPendingLinkRetryScheduler()
        } else {
            routingEnabled = routing
            this.networkConfig = networkConfig
        }
    }

    fun stop() {
        if (running.setIfTrue()) {
            synchronized(interfaces) {
                interfaces.forEach { it.stop() }
                interfaces.clear()
            }
            pathTable.clear()
            announceCache.clear()
            resourceCache.clear()
            resourceCacheOrder.clear()
            resourceSeen.clear()
            resourceRequests.clear()
            resourceAssemblers.clear()
            pendingLinksById.clear()
            pendingLinksByDestination.clear()
            activeLinksById.clear()
            activeLinksByDestination.clear()
            pendingLinkResources.clear()
            pendingLinkPackets.clear()
            localDestinations.clear()
            knownDestinations.clear()
            announceReceivedCount.set(0L)
            lastPacketReceivedMs.set(0L)
            rawPacketReceivedCount.set(0L)
            announceFuture?.cancel(true)
            announceFuture = null
            unicastProbeFuture?.cancel(true)
            unicastProbeFuture = null
            pendingLinkRetryFuture?.cancel(true)
            pendingLinkRetryFuture = null
            unicastProbeCursor = 0
            udpInterfaceRef = null
            networkConfig = null
        }
    }

    fun isRunning(): Boolean = running.get()

    fun getInterfaceNames(): List<String> {
        synchronized(interfaces) {
            return interfaces.map { it.name }.toList()
        }
    }

    fun setRoutingEnabled(enabled: Boolean) {
        routingEnabled = enabled
    }

    fun addAnnounceListener(listener: (RnsAnnounce, RnsPacket) -> Unit) {
        announceListeners.add(listener)
    }

    fun removeAnnounceListener(listener: (RnsAnnounce, RnsPacket) -> Unit) {
        announceListeners.remove(listener)
    }

    fun addInterface(interfaceRef: RnsInterface) {
        synchronized(interfaces) {
            if (interfaces.any { it.name == interfaceRef.name }) return
            interfaces.add(interfaceRef)
            interfaceRef.start()
        }
    }

    fun removeInterface(name: String) {
        synchronized(interfaces) {
            val iterator = interfaces.iterator()
            while (iterator.hasNext()) {
                val iface = iterator.next()
                if (iface.name == name) {
                    iface.stop()
                    iterator.remove()
                }
            }
        }
    }

    fun handleIncomingFromInterface(raw: ByteArray, iface: RnsInterface) {
        handleIncoming(raw, iface)
    }

    fun requestPath(destinationHash: ByteArray, minIntervalMs: Long = PATH_REQUEST_MIN_INTERVAL_MS) {
        val now = System.currentTimeMillis()
        val key = RnsHex.encode(destinationHash)
        val interval = minIntervalMs.coerceAtLeast(PATH_REQUEST_MIN_INTERVAL_FLOOR_MS)
        val last = lastPathRequestMs[key] ?: 0L
        if (now - last < interval) return
        lastPathRequestMs[key] = now

        val tag = RnsHash.truncatedHash((destinationHash + now.toString().toByteArray()))
        val payload = destinationHash + tag
        val dest = RnsDestination.createPlain(
            direction = RnsDestination.OUT,
            appName = PATH_REQUEST_APP,
            aspects = listOf("path", "request")
        )
        val packet = RnsPacket(
            destination = dest,
            data = payload,
            packetType = RnsPacket.DATA,
            transportType = RnsTransport.BROADCAST,
            headerType = RnsPacket.HEADER_1
        )
        send(packet)
    }

    private fun registerPathRequestDestination() {
        val destination = RnsDestination.createPlain(
            direction = RnsDestination.IN,
            appName = PATH_REQUEST_APP,
            aspects = listOf("path", "request")
        )
        registerDestination(destination, { packet, data ->
            handlePathRequest(packet, data)
        })
    }

    private fun handlePathRequest(packet: RnsPacket, data: ByteArray) {
        if (data.size < RnsConstants.TRUNCATED_HASH_LENGTH_BITS / 8) return
        val destHashLength = RnsConstants.TRUNCATED_HASH_LENGTH_BITS / 8
        val destinationHash = data.copyOfRange(0, destHashLength)
        val tagBytes = if (data.size > destHashLength * 2) {
            data.copyOfRange(destHashLength * 2, data.size)
        } else if (data.size > destHashLength) {
            data.copyOfRange(destHashLength, data.size)
        } else {
            ByteArray(0)
        }

        if (tagBytes.isNotEmpty()) {
            val tagKey = RnsHex.encode(destinationHash) + ":" + RnsHex.encode(tagBytes)
            synchronized(pathRequestTags) {
                if (pathRequestTags.containsKey(tagKey)) return
                pathRequestTags[tagKey] = System.currentTimeMillis()
                trimPathRequestTags()
            }
        }

        val destKey = RnsHex.encode(destinationHash)
        val local = localDestinations[destKey]
        if (local != null) {
            val config = local.announceProvider?.invoke()
            announce(
                destination = local.destination,
                appData = config?.appData,
                ratchetPublic = config?.ratchetPublic,
                context = RnsPacket.PATH_RESPONSE
            )
            return
        }

        val known = knownDestinations[destKey]
        if (known != null) {
            sendPathResponseFromKnown(known)
        }
    }

    private fun sendPathResponseFromKnown(known: KnownDestination) {
        val announceData = buildAnnounceData(known)
        val packet = RnsPacket(
            destination = RnsDestination.fromHash(known.destinationHash, RnsDestination.SINGLE),
            data = announceData,
            packetType = RnsPacket.ANNOUNCE,
            context = RnsPacket.PATH_RESPONSE,
            contextFlag = if (known.ratchet.isNotEmpty()) RnsPacket.FLAG_SET else RnsPacket.FLAG_UNSET
        )
        send(packet)
    }

    private fun buildAnnounceData(known: KnownDestination): ByteArray {
        val payload = ArrayList<Byte>()
        payload.addAll(known.publicKey.toList())
        payload.addAll(known.nameHash.toList())
        payload.addAll(known.randomHash.toList())
        payload.addAll(known.ratchet.toList())
        payload.addAll(known.signature.toList())
        known.appData?.let { payload.addAll(it.toList()) }
        return payload.toByteArray()
    }

    private fun trimPathRequestTags() {
        while (pathRequestTags.size > PATH_REQUEST_TAG_LIMIT) {
            val key = pathRequestTags.entries.firstOrNull()?.key ?: return
            pathRequestTags.remove(key)
        }
    }

    private fun startAnnounceScheduler() {
        announceFuture?.cancel(true)
        announceFuture = announceScheduler.scheduleAtFixedRate(
            { announceAll() },
            ANNOUNCE_INTERVAL_MS,
            ANNOUNCE_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun startUnicastProbeScheduler() {
        unicastProbeFuture?.cancel(true)
        // Small, battery-friendly LAN unicast probing to bootstrap in networks where broadcast/multicast is blocked.
        unicastProbeFuture = unicastProbeScheduler.scheduleAtFixedRate(
            { unicastProbeTick() },
            5_000L,
            3_000L,
            TimeUnit.MILLISECONDS
        )
    }

    private fun startPendingLinkRetryScheduler() {
        pendingLinkRetryFuture?.cancel(true)
        pendingLinkRetryFuture = pendingLinkRetryScheduler.scheduleAtFixedRate(
            { retryPendingLinks() },
            LINK_REQUEST_RETRY_MS,
            LINK_REQUEST_RETRY_TICK_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun retryPendingLinks() {
        if (!isRunning()) return
        val now = System.currentTimeMillis()
        val staleLinkIds = ArrayList<String>()

        pendingLinksById.forEach { (linkKey, link) ->
            if (!link.initiator) return@forEach
            val age = now - link.getLastRequestTimeMs()
            when {
                age > PENDING_LINK_TIMEOUT_MS -> staleLinkIds.add(linkKey)
                age > LINK_REQUEST_RETRY_MS -> {
                    try {
                        send(link.buildLinkRequestPacket())
                    } catch (_: Exception) {
                    }
                }
            }
        }

        if (staleLinkIds.isEmpty()) return
        staleLinkIds.forEach { linkKey ->
            pendingLinksById.remove(linkKey)
            pendingLinkResources.remove(linkKey)
            pendingLinkPackets.remove(linkKey)
            val iter = pendingLinksByDestination.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (entry.value == linkKey) {
                    iter.remove()
                    break
                }
            }
        }
    }

    private fun unicastProbeTick() {
        if (!isRunning()) return
        // Only probe when we have no known direct neighbors yet.
        if (getDirectNeighborCount() > 0) return
        val udp = udpInterfaceRef ?: return
        if (udp.getPeerCount() > 0) return
        val cfg = networkConfig ?: return
        val local = cfg.localAddress as? Inet4Address ?: return
        val prefix = cfg.networkPrefixLength
            ?: cfg.netmask?.let { mask -> prefixLengthFromNetmask(mask) }
            ?: 24
        val effectivePrefix = if (prefix in 0..32) {
            // Avoid scanning huge subnets; most home networks are /24.
            prefix.coerceAtLeast(24)
        } else {
            24
        }

        val targets = computeSubnetTargets(local, effectivePrefix)
        if (targets.isEmpty()) return

        val rawAnnounces = buildLocalAnnouncePacketsRaw()
        if (rawAnnounces.isEmpty()) return

        // Probe a handful of hosts per tick.
        repeat(8) {
            val idx = (unicastProbeCursor++ % targets.size)
            val addr = targets[idx]
            rawAnnounces.forEach { raw ->
                lastPacketSentMs.set(System.currentTimeMillis())
                rawPacketSentCount.incrementAndGet()
                udp.sendTo(raw, addr, DEFAULT_UDP_PORT)
            }
        }
    }

    private fun computeSubnetTargets(local: Inet4Address, prefixLength: Int): List<InetAddress> {
        val ipInt = ByteBuffer.wrap(local.address).int
        val mask = if (prefixLength == 0) 0 else -1 shl (32 - prefixLength)
        val network = ipInt and mask
        val broadcast = network or mask.inv()

        // effectivePrefix ensures we never scan massive networks.
        val out = ArrayList<InetAddress>(254)
        val start = network + 1
        val end = broadcast - 1
        var candidate = start
        while (candidate <= end) {
            if (candidate != ipInt) {
                out.add(InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(candidate).array()))
            }
            candidate++
        }
        // Prioritize nearby IPs first for faster discovery on typical networks.
        out.sortBy { addr ->
            kotlin.math.abs(ByteBuffer.wrap(addr.address).int - ipInt)
        }
        return out
    }

    private fun prefixLengthFromNetmask(mask: InetAddress): Int? {
        val bytes = mask.address
        if (bytes.size != 4) return null
        var count = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            for (i in 7 downTo 0) {
                if ((v and (1 shl i)) != 0) count++ else return count
            }
        }
        return count
    }

    private fun buildLocalAnnouncePacketsRaw(): List<ByteArray> {
        val packets = ArrayList<ByteArray>()
        localDestinations.values.forEach { local ->
            val dest = local.destination
            if (dest.identity == null || dest.type != RnsDestination.SINGLE) return@forEach
            val config = local.announceProvider?.invoke()
            val packet = RnsAnnounce.build(dest, config?.appData, config?.ratchetPublic, RnsPacket.NONE)
            try {
                packets.add(packet.pack())
            } catch (_: Exception) {
            }
        }
        return packets
    }

    fun getUdpPeerCount(): Int = udpInterfaceRef?.getPeerCount() ?: 0

    fun getDirectNeighborCount(timeoutMs: Long = DIRECT_NEIGHBOR_TIMEOUT_MS): Int {
        val now = System.currentTimeMillis()
        return pathTable.values.count { entry ->
            entry.hops <= 1 && now - entry.timestamp <= timeoutMs
        }
    }

    fun getAnnounceReceivedCount(): Long = announceReceivedCount.get()

    fun getLastPacketReceivedMs(): Long = lastPacketReceivedMs.get()

    fun getRawPacketReceivedCount(): Long = rawPacketReceivedCount.get()

    fun getLastPacketSentMs(): Long = lastPacketSentMs.get()

    fun getRawPacketSentCount(): Long = rawPacketSentCount.get()

    fun getActiveLinkCount(): Int = activeLinksById.size

    fun hasRecentRoutingActivity(windowMs: Long = ROUTING_ACTIVITY_WINDOW_MS): Boolean {
        val last = lastRoutingActivityMs.get()
        if (last <= 0L) {
            return false
        }
        return System.currentTimeMillis() - last <= windowMs
    }

    fun addResourceListener(listener: (RnsResource) -> Unit) {
        resourceListeners.add(listener)
    }

    fun removeResourceListener(listener: (RnsResource) -> Unit) {
        resourceListeners.remove(listener)
    }

    fun addResourceAdvertisementListener(listener: (RnsResourceAdvertisement) -> Unit) {
        resourceAdvertisementListeners.add(listener)
    }

    fun removeResourceAdvertisementListener(listener: (RnsResourceAdvertisement) -> Unit) {
        resourceAdvertisementListeners.remove(listener)
    }

    fun advertiseResource(resource: RnsResource) {
        cacheResource(resource)
        val totalParts = countParts(resource.data.size)
        val advertisement = RnsResourceAdvertisement(
            hash = resource.hash,
            sourceHash = resource.sourceHash,
            destinationHash = resource.destinationHash,
            kind = resource.kind,
            encrypted = resource.encrypted,
            timestamp = resource.timestamp,
            totalSize = resource.data.size,
            totalParts = totalParts
        )
        val packet = RnsPacket(
            destination = RnsDestination.fromHash(resource.destinationHash, RnsDestination.PLAIN),
            data = RnsResourceCodec.packAdvertisement(advertisement),
            context = RnsPacket.RESOURCE_ADV
        )
        send(packet)
    }

    fun requestResource(
        advertisement: RnsResourceAdvertisement,
        requesterHash: ByteArray,
        requestedIndices: List<Int>? = null
    ) {
        val requestKey = RnsHex.encode(advertisement.hash) + ":" + RnsHex.encode(requesterHash)
        val now = System.currentTimeMillis()
        val lastRequest = resourceRequests[requestKey]
        if (lastRequest != null && now - lastRequest < RESOURCE_REQUEST_COOLDOWN_MS) {
            return
        }
        resourceRequests[requestKey] = now
        trimResourceRequests()
        val request = RnsResourceRequest(
            hash = advertisement.hash,
            requesterHash = requesterHash,
            kind = advertisement.kind,
            requestedIndices = requestedIndices ?: emptyList()
        )
        val packet = RnsPacket(
            destination = RnsDestination.fromHash(advertisement.sourceHash, RnsDestination.PLAIN),
            data = RnsResourceCodec.packRequest(request),
            context = RnsPacket.RESOURCE_REQ
        )
        send(packet)
    }

    fun sendResourceTo(resource: RnsResource, targetHash: ByteArray, requestedIndices: List<Int>? = null) {
        cacheResource(resource)
        val indices = requestedIndices?.distinct()?.sorted() ?: emptyList()
        val totalParts = countParts(resource.data.size)
        val maxSize = RnsResourceCodec.maxChunkSize
        val iterable = if (indices.isEmpty()) (0 until totalParts).toList() else indices
        iterable.forEach { index ->
            if (index < 0 || index >= totalParts) return@forEach
            val offset = index * maxSize
            val end = (offset + maxSize).coerceAtMost(resource.data.size)
            val data = resource.data.copyOfRange(offset, end)
            val chunk = RnsResourceChunk(
                hash = resource.hash,
                sourceHash = resource.sourceHash,
                destinationHash = resource.destinationHash,
                kind = resource.kind,
                encrypted = resource.encrypted,
                index = index,
                totalParts = totalParts,
                data = data
            )
            val packet = RnsPacket(
                destination = RnsDestination.fromHash(targetHash, RnsDestination.PLAIN),
                data = RnsResourceCodec.packChunk(chunk),
                context = RnsPacket.RESOURCE
            )
            send(packet)
        }
    }

    fun sendResourceViaLink(owner: RnsDestination, destination: RnsDestination, resource: RnsResource): Boolean {
        val link = ensureLink(owner, destination) ?: return false
        if (link.isActive()) {
            maybeIdentifyActiveInitiatorLink(link)
            sendResourceToLink(link, resource, null)
        } else {
            cacheResource(resource)
            queueLinkResource(link, resource, null)
        }
        return true
    }

    fun sendPacketViaLink(
        owner: RnsDestination,
        destination: RnsDestination,
        payload: ByteArray,
        context: Int = RnsPacket.NONE,
        packetType: Int = RnsPacket.DATA
    ): Boolean {
        val link = ensureLink(owner, destination) ?: return false
        if (link.isActive()) {
            maybeIdentifyActiveInitiatorLink(link)
            return sendLinkPacket(link, packetType, context, payload, null) != null
        } else {
            queueLinkPacket(link, packetType, context, payload)
        }
        return true
    }

    /**
     * Best-effort send over a link, without ever queueing payload for later delivery.
     *
     * This is useful for real-time traffic (voice) where stale packets are worse than loss.
     */
    fun trySendPacketViaLink(
        owner: RnsDestination,
        destination: RnsDestination,
        payload: ByteArray,
        context: Int = RnsPacket.NONE,
        packetType: Int = RnsPacket.DATA
    ): Boolean {
        val link = ensureLink(owner, destination) ?: return false
        if (!link.isActive()) return false
        maybeIdentifyActiveInitiatorLink(link)
        return sendLinkPacket(link, packetType, context, payload, null) != null
    }

    fun sendPacketOnLink(
        linkId: ByteArray,
        payload: ByteArray,
        context: Int = RnsPacket.NONE,
        packetType: Int = RnsPacket.DATA
    ): Boolean {
        val link = activeLinksById[RnsHex.encode(linkId)] ?: return false
        return sendLinkPacket(link, packetType, context, payload, null) != null
    }

    fun identifyLink(owner: RnsDestination, destination: RnsDestination, identity: RnsIdentity): Boolean {
        val link = ensureLink(owner, destination) ?: return false
        if (!link.isActive()) return false
        return link.identify(identity) { linkInstance, packetType, context, payload ->
            sendLinkPacket(linkInstance, packetType, context, payload, null)
        }
    }

    fun requestOverLink(
        owner: RnsDestination,
        destination: RnsDestination,
        path: String,
        data: Any?,
        onResponse: ((RnsRequestReceipt) -> Unit)? = null,
        onFailure: ((RnsRequestReceipt) -> Unit)? = null
    ): RnsRequestReceipt? {
        val link = ensureLink(owner, destination) ?: return null
        maybeIdentifyActiveInitiatorLink(link)
        return link.request(path, data, { linkInstance, packetType, context, payload ->
            sendLinkPacket(linkInstance, packetType, context, payload, null)
        }, onResponse, onFailure, { payload, requestId, isResponse ->
            sendLinkResource(link, payload, requestId, isResponse = isResponse, isRequest = !isResponse)
        })
    }

    fun hasResource(resourceHash: ByteArray): Boolean {
        return resourceCache.containsKey(RnsHex.encode(resourceHash))
    }

    fun registerDestination(
        destination: RnsDestination,
        callback: (RnsPacket, ByteArray) -> Unit,
        announceProvider: (() -> RnsAnnounceConfig)? = null
    ) {
        localDestinations[RnsHex.encode(destination.hash)] = LocalDestination(destination, callback, announceProvider)
    }

    fun unregisterDestination(destinationHash: ByteArray) {
        localDestinations.remove(RnsHex.encode(destinationHash))
    }

    fun announce(
        destination: RnsDestination,
        appData: ByteArray? = null,
        ratchetPublic: ByteArray? = null,
        context: Int = RnsPacket.NONE
    ) {
        val packet = RnsAnnounce.build(destination, appData, ratchetPublic, context)
        send(packet)
    }

    fun announceAll() {
        localDestinations.values.forEach { local ->
            if (local.destination.identity == null || local.destination.type != RnsDestination.SINGLE) return@forEach
            val config = local.announceProvider?.invoke()
            announce(
                destination = local.destination,
                appData = config?.appData,
                ratchetPublic = config?.ratchetPublic,
                context = RnsPacket.NONE
            )
        }
    }

    fun send(packet: RnsPacket, interfaceName: String? = null) {
        lastPacketSentMs.set(System.currentTimeMillis())
        rawPacketSentCount.incrementAndGet()
        val raw = packet.pack()
        val iface = interfaceName?.let { name -> interfaces.firstOrNull { it.name == name } }
        if (iface != null) {
            iface.send(raw)
        } else {
            interfaces.forEach { it.send(raw) }
        }
    }

    fun sendWithReceipt(
        packet: RnsPacket,
        destinationHash: ByteArray,
        onDelivered: (() -> Unit)? = null
    ) {
        trimReceipts(System.currentTimeMillis())
        val raw = packet.pack()
        val hashable = RnsPacket.getHashablePart(raw)
        val fullHash = RnsHash.sha256(hashable)
        val truncated = RnsHash.truncatedHash(fullHash)
        val receipt = RnsReceipt(
            packetHash = fullHash,
            truncatedHash = truncated,
            destinationHash = destinationHash,
            createdAt = System.currentTimeMillis(),
            onDelivered = onDelivered
        )
        receipts[RnsHex.encode(truncated)] = receipt
        send(packet)
    }

    private fun handleIncoming(raw: ByteArray, iface: RnsInterface) {
        lastPacketReceivedMs.set(System.currentTimeMillis())
        rawPacketReceivedCount.incrementAndGet()
        val packet = try {
            RnsPacket.fromRaw(raw)
        } catch (_: Exception) {
            return
        }

        val destination = packet.destination ?: return
        val destHash = destination.hash
        val destKey = RnsHex.encode(destHash)

        if (packet.packetType == RnsPacket.PROOF && destination.type != RnsDestination.LINK) {
            handleProof(packet)
            return
        }

        if (packet.packetType == RnsPacket.ANNOUNCE) {
            handleAnnounce(raw, packet, iface)
            return
        }

        if (packet.packetType == RnsPacket.LINKREQUEST) {
            handleLinkRequest(raw, packet, iface)
            return
        }

        if (destination.type == RnsDestination.LINK) {
            handleLinkPacket(packet, iface)
            return
        }

        if (packet.context == RnsPacket.RESOURCE_ADV) {
            handleResourceAdvertisement(raw, packet, iface)
            return
        }

        if (packet.context == RnsPacket.RESOURCE_REQ) {
            handleResourceRequest(raw, packet, iface)
            return
        }

        if (packet.context == RnsPacket.RESOURCE) {
            if (!isLocalDestination(destHash)) {
                if (routingEnabled) {
                    forwardResource(raw, packet, iface)
                }
                return
            }
            handleResourceChunk(packet)
            return
        }

        val local = localDestinations[destKey]
        if (local != null) {
            val payload = decryptPayload(packet, local.destination) ?: return
            local.callback(packet, payload)
            maybeSendProof(packet, local)
            return
        }

        if (routingEnabled) {
            forwardPacket(raw, packet, iface)
        }
    }

    private fun handleLinkRequest(raw: ByteArray, packet: RnsPacket, iface: RnsInterface) {
        val destHash = packet.destination?.hash ?: return
        val local = localDestinations[RnsHex.encode(destHash)]
        if (local == null) {
            if (routingEnabled) {
                forwardPacket(raw, packet, iface)
            }
            return
        }
        val link = RnsLink.fromIncomingRequest(local.destination, packet, raw) ?: return
        val linkKey = RnsHex.encode(link.linkId)
        if (pendingLinksById.containsKey(linkKey) || activeLinksById.containsKey(linkKey)) {
            return
        }
        pendingLinksById[linkKey] = link
        val proofPacket = link.buildProofPacket() ?: return
        send(proofPacket, iface.name)
    }

    private fun handleLinkPacket(packet: RnsPacket, iface: RnsInterface) {
        val linkId = packet.destination?.hash ?: return
        val linkKey = RnsHex.encode(linkId)
        val link = activeLinksById[linkKey] ?: pendingLinksById[linkKey] ?: return

        if (packet.packetType == RnsPacket.PROOF) {
            if (packet.context == RnsPacket.LRPROOF) {
                handleLinkProof(link, packet, iface)
                return
            }
            if (packet.context == RnsPacket.RESOURCE_PRF) {
                val payload = link.decryptForPacket(packet.packetType, packet.context, packet.data) ?: return
                linkResourceManager.handleProof(payload, link) { packetType, context, data ->
                    sendLinkPacket(link, packetType, context, data, iface.name)
                }
                return
            }
        }

        val payload = link.decryptForPacket(packet.packetType, packet.context, packet.data) ?: return
        when (packet.context) {
            RnsPacket.LRRTT -> {
                if (link.handleRttPayload(payload)) {
                    activateLink(link)
                }
            }
            RnsPacket.REQUEST -> {
                val requestId = packet.raw?.let { raw ->
                    RnsHash.truncatedHash(RnsHash.sha256(RnsPacket.getHashablePart(raw)))
                } ?: return
                link.handleRequest(payload, link.owner, requestId, { target, packetType, context, data ->
                    sendLinkPacket(target, packetType, context, data, iface.name)
                }, { responsePayload, responseId, isResponse ->
                    sendLinkResource(link, responsePayload, responseId, isResponse = isResponse, isRequest = false)
                })
            }
            RnsPacket.RESPONSE -> link.handleResponse(payload)
            RnsPacket.LINKIDENTIFY -> link.handleIdentify(payload)
            RnsPacket.RESOURCE_ADV -> linkResourceManager.handleAdvertisement(payload, link) { packetType, context, data ->
                sendLinkPacket(link, packetType, context, data, iface.name)
            }
            RnsPacket.RESOURCE_REQ -> handleResourceRequestPayload(payload, link)
            RnsPacket.RESOURCE_HMU -> linkResourceManager.handleHmu(payload, link) { packetType, context, data ->
                sendLinkPacket(link, packetType, context, data, iface.name)
            }
            RnsPacket.RESOURCE -> linkResourceManager.handlePart(payload, link) { packetType, context, data ->
                sendLinkPacket(link, packetType, context, data, iface.name)
            }
            else -> {
                val ownerKey = RnsHex.encode(link.owner.hash)
                val local = localDestinations[ownerKey] ?: return
                local.callback(packet, payload)
            }
        }
    }

    private fun handleLinkProof(link: RnsLink, packet: RnsPacket, iface: RnsInterface) {
        if (!link.initiator) return
        val rtt = link.validateProof(packet) ?: return
        activateLink(link)
        val payload = RnsLink.packRttPayload(rtt)
        sendLinkPacket(link, RnsPacket.DATA, RnsPacket.LRRTT, payload, iface.name)
    }

    private fun handleAnnounce(raw: ByteArray, packet: RnsPacket, iface: RnsInterface) {
        val announce = RnsAnnounce.validate(packet) ?: return
        announceReceivedCount.incrementAndGet()
        val key = RnsHex.encode(announce.destinationHash)
        if (localDestinations.containsKey(key)) {
            return
        }
        val now = System.currentTimeMillis()

        knownDestinations[key] = KnownDestination(
            destinationHash = announce.destinationHash,
            publicKey = announce.publicKey,
            nameHash = announce.nameHash,
            randomHash = announce.randomHash,
            ratchet = announce.ratchet,
            signature = announce.signature,
            appData = announce.appData,
            lastSeen = now,
            hops = packet.hops,
            contextFlag = packet.contextFlag
        )

        if (announce.ratchet.isNotEmpty()) {
            RnsIdentity.rememberRatchet(announce.destinationHash, announce.ratchet)
        }

        announceListeners.forEach { listener ->
            try {
                listener(announce, packet)
            } catch (_: Exception) {
            }
        }

        pathTable[key] = PathEntry(iface, packet.hops, now)

        val announceId = key + ":" + RnsHex.encode(announce.randomHash)
        synchronized(announceCache) {
            if (announceCache.containsKey(announceId)) {
                return
            }
            announceCache[announceId] = now
            trimAnnounceCache()
        }

        if (routingEnabled) {
            forwardAnnounce(raw, packet, iface)
        }

        trimRoutingCaches(now)
    }

    private fun handleProof(packet: RnsPacket) {
        val data = packet.data
        val hashLength = 32
        val sigLength = 64
        if (data.size < hashLength + sigLength) return
        val proofHash = data.copyOfRange(0, hashLength)
        val signature = data.copyOfRange(hashLength, hashLength + sigLength)
        val truncated = RnsHash.truncatedHash(proofHash)
        val receiptKey = RnsHex.encode(truncated)
        val receipt = receipts[receiptKey] ?: return
        if (!proofHash.contentEquals(receipt.packetHash)) return

        val known = knownDestinations[RnsHex.encode(receipt.destinationHash)]
        val verified = if (known != null) {
            val identity = RnsIdentity.fromPublic(known.publicKey)
            identity.verify(proofHash, signature)
        } else {
            true
        }
        if (!verified) return
        receipts.remove(receiptKey)
        receipt.onDelivered?.invoke()
        trimReceipts(System.currentTimeMillis())
    }

    private fun trimRoutingCaches(now: Long) {
        trimKnownDestinations(now)
        trimPathTable(now)
        trimReceipts(now)
    }

    private fun trimKnownDestinations(now: Long) {
        if (knownDestinations.isEmpty()) return

        val cutoff = now - KNOWN_DESTINATION_TTL_MS
        for ((key, value) in knownDestinations) {
            if (value.lastSeen < cutoff) {
                knownDestinations.remove(key)
                // Drop any routing path associated with this destination as well.
                pathTable.remove(key)
            }
        }

        // Hard cap: remove arbitrary entries until below the limit (no sorting to avoid allocations).
        if (knownDestinations.size <= KNOWN_DESTINATION_LIMIT) return
        val it = knownDestinations.keys.iterator()
        while (knownDestinations.size > KNOWN_DESTINATION_LIMIT && it.hasNext()) {
            val k = it.next()
            knownDestinations.remove(k)
            pathTable.remove(k)
        }
    }

    private fun trimPathTable(now: Long) {
        if (pathTable.isEmpty()) return

        val cutoff = now - PATH_TABLE_TTL_MS
        for ((key, value) in pathTable) {
            if (value.lastSeen < cutoff) {
                pathTable.remove(key)
            }
        }

        if (pathTable.size <= PATH_TABLE_LIMIT) return
        val it = pathTable.keys.iterator()
        while (pathTable.size > PATH_TABLE_LIMIT && it.hasNext()) {
            pathTable.remove(it.next())
        }
    }

    private fun trimReceipts(now: Long) {
        if (receipts.isEmpty()) return

        val cutoff = now - RECEIPT_TTL_MS
        for ((key, receipt) in receipts) {
            if (receipt.createdAt < cutoff) {
                receipts.remove(key)
            }
        }

        if (receipts.size <= RECEIPT_LIMIT) return
        val it = receipts.keys.iterator()
        while (receipts.size > RECEIPT_LIMIT && it.hasNext()) {
            receipts.remove(it.next())
        }
    }

    private fun maybeSendProof(packet: RnsPacket, local: LocalDestination) {
        if (packet.packetType != RnsPacket.DATA) return
        if (packet.context >= RnsPacket.RESOURCE && packet.context <= RnsPacket.RESOURCE_RCL) return
        if (packet.context >= RnsPacket.KEEPALIVE && packet.context <= RnsPacket.LRPROOF) return
        if (local.destination.type == RnsDestination.PLAIN) return
        val identity = local.destination.identity ?: return
        if (identity.privateKey == null) return
        val raw = packet.raw ?: return
        val hashable = RnsPacket.getHashablePart(raw)
        val fullHash = RnsHash.sha256(hashable)
        val signature = identity.sign(fullHash)
        val proofData = fullHash + signature
        val proofDest = RnsDestination.fromHash(RnsHash.truncatedHash(fullHash), RnsDestination.PLAIN)
        val proofPacket = RnsPacket(
            destination = proofDest,
            data = proofData,
            packetType = RnsPacket.PROOF,
            context = RnsPacket.NONE
        )
        send(proofPacket)
    }

    private fun handleResourceAdvertisement(raw: ByteArray, packet: RnsPacket, iface: RnsInterface) {
        val advertisement = RnsResourceCodec.unpackAdvertisement(packet.data) ?: return
        val key = RnsHex.encode(advertisement.hash)
        val now = System.currentTimeMillis()
        synchronized(resourceSeen) {
            val lastSeen = resourceSeen[key]
            if (lastSeen != null && now - lastSeen < RESOURCE_REQUEST_COOLDOWN_MS) {
                return
            }
            resourceSeen[key] = now
            trimResourceSeen()
        }
        resourceAdvertisementListeners.forEach { it(advertisement) }
        if (routingEnabled) {
            forwardResource(raw, packet, iface)
        }
    }

    private fun handleResourceAdvertisementPayload(payload: ByteArray) {
        val advertisement = RnsResourceCodec.unpackAdvertisement(payload) ?: return
        val key = RnsHex.encode(advertisement.hash)
        val now = System.currentTimeMillis()
        synchronized(resourceSeen) {
            val lastSeen = resourceSeen[key]
            if (lastSeen != null && now - lastSeen < RESOURCE_REQUEST_COOLDOWN_MS) {
                return
            }
            resourceSeen[key] = now
            trimResourceSeen()
        }
        resourceAdvertisementListeners.forEach { it(advertisement) }
    }

    private fun handleResourceRequest(raw: ByteArray, packet: RnsPacket, iface: RnsInterface) {
        val request = RnsResourceCodec.unpackRequest(packet.data) ?: return
        val key = RnsHex.encode(request.hash)
        val resource = resourceCache[key]
        if (resource != null) {
            val indices = request.requestedIndices.takeIf { it.isNotEmpty() }
            sendResourceTo(resource, request.requesterHash, indices)
            return
        }
        if (routingEnabled) {
            forwardResource(raw, packet, iface)
        }
    }

    private fun handleResourceRequestPayload(payload: ByteArray, link: RnsLink) {
        linkResourceManager.handleRequest(payload, link) { packetType, context, data ->
            sendLinkPacket(link, packetType, context, data, null)
        }
    }

    private fun handleResourceChunk(packet: RnsPacket) {
        val chunk = RnsResourceCodec.unpackChunk(packet.data) ?: return
        val key = RnsHex.encode(chunk.hash)
        if (resourceCache.containsKey(key)) {
            return
        }

        val now = System.currentTimeMillis()
        val assembler = resourceAssemblers.getOrPut(key) {
            ResourceAssembler(
                hash = chunk.hash,
                sourceHash = chunk.sourceHash,
                destinationHash = chunk.destinationHash,
                kind = chunk.kind,
                encrypted = chunk.encrypted,
                totalParts = chunk.totalParts,
                createdAt = now
            )
        }
        assembler.lastUpdated = now
        assembler.parts[chunk.index] = chunk.data
        if (assembler.isComplete()) {
            val resource = assembler.buildResource()
            resourceAssemblers.remove(key)
            cacheResource(resource)
            resourceListeners.forEach { it(resource) }
        } else {
            maybeRequestMissingParts(assembler)
        }
        cleanupAssemblers(now)
    }

    private fun forwardAnnounce(raw: ByteArray, packet: RnsPacket, iface: RnsInterface) {
        if (packet.hops >= MAX_HOPS) return
        recordRoutingActivity()
        val forwarded = rawWithIncrementedHops(raw, packet.hops)
        interfaces.filter { it != iface }.forEach { it.send(forwarded) }
    }

    private fun forwardResource(raw: ByteArray, packet: RnsPacket, iface: RnsInterface) {
        if (packet.hops >= MAX_HOPS) return
        recordRoutingActivity()
        val forwarded = rawWithIncrementedHops(raw, packet.hops)
        interfaces.filter { it != iface }.forEach { it.send(forwarded) }
    }

    private fun forwardPacket(raw: ByteArray, packet: RnsPacket, iface: RnsInterface) {
        if (packet.hops >= MAX_HOPS) return
        val destKey = RnsHex.encode(packet.destination!!.hash)
        val path = pathTable[destKey] ?: return
        val forwarded = rawWithIncrementedHops(raw, packet.hops)
        if (path.interfaceRef != iface) {
            recordRoutingActivity()
            path.interfaceRef.send(forwarded)
        }
    }

    private fun recordRoutingActivity() {
        if (routingEnabled) {
            lastRoutingActivityMs.set(System.currentTimeMillis())
        }
    }

    private fun rawWithIncrementedHops(raw: ByteArray, currentHops: Int): ByteArray {
        val forwarded = raw.copyOf()
        val hops = (currentHops + 1).coerceAtMost(255)
        forwarded[1] = hops.toByte()
        return forwarded
    }

    private fun trimAnnounceCache() {
        while (announceCache.size > ANNOUNCE_CACHE_LIMIT) {
            val key = announceCache.entries.firstOrNull()?.key ?: return
            announceCache.remove(key)
        }
    }

    private fun cacheResource(resource: RnsResource) {
        if (!shouldCacheResource(resource)) return
        val key = RnsHex.encode(resource.hash)
        resourceCache[key] = resource
        synchronized(resourceCacheOrder) {
            resourceCacheOrder[key] = System.currentTimeMillis()
            trimResourceCache()
        }
    }

    private fun shouldCacheResource(resource: RnsResource): Boolean {
        // Cache inbound resources for us, and cache resources we originate so we can serve requests.
        return routingEnabled || isLocalDestination(resource.destinationHash) || isLocalDestination(resource.sourceHash)
    }

    private fun isLocalDestination(destinationHash: ByteArray): Boolean {
        return localDestinations.containsKey(RnsHex.encode(destinationHash))
    }

    private fun countParts(size: Int): Int {
        val maxSize = RnsResourceCodec.maxChunkSize
        return if (size <= 0) 1 else (size + maxSize - 1) / maxSize
    }

    private fun buildChunks(resource: RnsResource): List<RnsResourceChunk> {
        val maxSize = RnsResourceCodec.maxChunkSize
        val totalParts = countParts(resource.data.size)
        val chunks = ArrayList<RnsResourceChunk>(totalParts)
        var offset = 0
        for (index in 0 until totalParts) {
            val end = (offset + maxSize).coerceAtMost(resource.data.size)
            val data = resource.data.copyOfRange(offset, end)
            chunks.add(
                RnsResourceChunk(
                    hash = resource.hash,
                    sourceHash = resource.sourceHash,
                    destinationHash = resource.destinationHash,
                    kind = resource.kind,
                    encrypted = resource.encrypted,
                    index = index,
                    totalParts = totalParts,
                    data = data
                )
            )
            offset = end
        }
        return chunks
    }

    private fun trimResourceCache() {
        while (resourceCacheOrder.size > RESOURCE_CACHE_LIMIT) {
            val key = resourceCacheOrder.entries.firstOrNull()?.key ?: return
            resourceCacheOrder.remove(key)
            resourceCache.remove(key)
        }
    }

    private fun trimResourceSeen() {
        while (resourceSeen.size > RESOURCE_SEEN_LIMIT) {
            val key = resourceSeen.entries.firstOrNull()?.key ?: return
            resourceSeen.remove(key)
        }
    }

    private fun maybeRequestMissingParts(assembler: ResourceAssembler) {
        if (!isLocalDestination(assembler.destinationHash)) return
        val now = System.currentTimeMillis()
        if (now - assembler.lastRequestAt < RESOURCE_REQUEST_COOLDOWN_MS) return
        val missing = assembler.missingIndices()
        if (missing.isEmpty()) return
        val maxPerRequest = RnsResourceCodec.maxRequestIndices()
        if (maxPerRequest == 0) return
        missing.chunked(maxPerRequest).forEach { chunk ->
            val request = RnsResourceRequest(
                hash = assembler.hash,
                requesterHash = assembler.destinationHash,
                kind = assembler.kind,
                requestedIndices = chunk
            )
            val packet = RnsPacket(
                destination = RnsDestination.fromHash(assembler.sourceHash, RnsDestination.PLAIN),
                data = RnsResourceCodec.packRequest(request),
                context = RnsPacket.RESOURCE_REQ
            )
            send(packet)
        }
        assembler.lastRequestAt = now
    }

    private fun trimResourceRequests() {
        if (resourceRequests.size <= RESOURCE_REQUEST_LIMIT) return
        val sorted = resourceRequests.entries.sortedBy { it.value }
        val overflow = resourceRequests.size - RESOURCE_REQUEST_LIMIT
        for (i in 0 until overflow) {
            val key = sorted.getOrNull(i)?.key ?: break
            resourceRequests.remove(key)
        }
    }

    private fun cleanupAssemblers(now: Long) {
        resourceAssemblers.entries.toList().forEach { entry ->
            if (now - entry.value.lastUpdated > RESOURCE_ASSEMBLY_TIMEOUT_MS) {
                resourceAssemblers.remove(entry.key)
            }
        }
    }

    data class KnownDestination(
        val destinationHash: ByteArray,
        val publicKey: ByteArray,
        val nameHash: ByteArray,
        val randomHash: ByteArray,
        val ratchet: ByteArray,
        val signature: ByteArray,
        val appData: ByteArray?,
        val lastSeen: Long,
        val hops: Int,
        val contextFlag: Int
    )

    data class PathEntry(
        val interfaceRef: RnsInterface,
        val hops: Int,
        val timestamp: Long
    ) {
        val lastSeen: Long
            get() = timestamp
    }

    data class LocalDestination(
        val destination: RnsDestination,
        val callback: (RnsPacket, ByteArray) -> Unit,
        val announceProvider: (() -> RnsAnnounceConfig)? = null
    )

    data class RnsAnnounceConfig(
        val appData: ByteArray? = null,
        val ratchetPublic: ByteArray? = null
    )

    private data class PendingLinkResource(
        val resource: RnsResource,
        val requestedIndices: List<Int>?
    )

    private data class PendingLinkPacket(
        val packetType: Int,
        val context: Int,
        val payload: ByteArray
    )

    private data class ResourceAssembler(
        val hash: ByteArray,
        val sourceHash: ByteArray,
        val destinationHash: ByteArray,
        val kind: RnsResourceKind,
        val encrypted: Boolean,
        val totalParts: Int,
        val createdAt: Long,
        var lastUpdated: Long = createdAt,
        var lastRequestAt: Long = 0L,
        val parts: MutableMap<Int, ByteArray> = ConcurrentHashMap()
    ) {
        fun isComplete(): Boolean {
            if (parts.size < totalParts) return false
            for (index in 0 until totalParts) {
                if (!parts.containsKey(index)) return false
            }
            return true
        }

        fun buildResource(): RnsResource {
            var totalSize = 0
            for (index in 0 until totalParts) {
                totalSize += parts[index]?.size ?: 0
            }
            val data = ByteArray(totalSize)
            var offset = 0
            for (index in 0 until totalParts) {
                val part = parts[index] ?: ByteArray(0)
                System.arraycopy(part, 0, data, offset, part.size)
                offset += part.size
            }
            return RnsResource(
                hash = hash,
                sourceHash = sourceHash,
                destinationHash = destinationHash,
                kind = kind,
                encrypted = encrypted,
                timestamp = createdAt / 1000L,
                data = data
            )
        }

        fun missingIndices(): List<Int> {
            if (totalParts <= 0) return emptyList()
            val missing = ArrayList<Int>()
            for (index in 0 until totalParts) {
                if (!parts.containsKey(index)) {
                    missing.add(index)
                }
            }
            return missing
        }
    }

    private class AtomicBooleanState {
        @Volatile
        private var value = false

        fun setIfFalse(): Boolean {
            synchronized(this) {
                return if (!value) {
                    value = true
                    true
                } else {
                    false
                }
            }
        }

        fun setIfTrue(): Boolean {
            synchronized(this) {
                return if (value) {
                    value = false
                    true
                } else {
                    false
                }
            }
        }

        fun get(): Boolean = value
    }

    private fun decryptPayload(packet: RnsPacket, destination: RnsDestination): ByteArray? {
        return if (RnsPacket.shouldEncrypt(packet.packetType, packet.context, destination.type)) {
            destination.decrypt(packet.data)
        } else {
            packet.data
        }
    }

    private fun ensureLink(owner: RnsDestination, destination: RnsDestination): RnsLink? {
        if (destination.identity == null) return null
        val destKey = RnsHex.encode(destination.hash)
        val activeId = activeLinksByDestination[destKey]
        val active = activeId?.let { activeLinksById[it] }
        if (activeId != null && active == null) {
            activeLinksByDestination.remove(destKey, activeId)
        }
        if (active != null) return active

        // If this destination already has an incoming active link (reverse direction), reuse it.
        // Without this, one direction may repeatedly recreate links and suffer high setup latency.
        val remotePublicKey = destination.identity.publicKey
        val incomingMatch = activeLinksById.values.firstOrNull { link ->
            !link.initiator &&
                link.isActive() &&
                link.owner.hash.contentEquals(owner.hash) &&
                link.getRemoteIdentity()?.publicKey?.contentEquals(remotePublicKey) == true
        }
        if (incomingMatch != null) {
            val incomingKey = RnsHex.encode(incomingMatch.linkId)
            activeLinksByDestination[destKey] = incomingKey
            return incomingMatch
        }

        val pendingId = pendingLinksByDestination[destKey]
        val pending = pendingId?.let { pendingLinksById[it] }
        if (pendingId != null && pending == null) {
            pendingLinksByDestination.remove(destKey, pendingId)
        }
        if (pending != null) {
            // Link requests are sent over lossy transports. If the initial request gets dropped,
            // we must retry or the link will never become active.
            if (pending.initiator) {
                val age = System.currentTimeMillis() - pending.getLastRequestTimeMs()
                if (age > LINK_REQUEST_RETRY_MS) {
                    try {
                        send(pending.buildLinkRequestPacket())
                    } catch (_: Exception) {
                    }
                }
            }
            return pending
        }

        val link = RnsLink.createOutgoing(owner, destination) ?: return null
        val requestPacket = link.buildLinkRequestPacket()
        val linkKey = RnsHex.encode(link.linkId)
        pendingLinksById[linkKey] = link
        pendingLinksByDestination[destKey] = linkKey
        send(requestPacket)
        return link
    }

    private fun activateLink(link: RnsLink) {
        val linkKey = RnsHex.encode(link.linkId)
        pendingLinksById.remove(linkKey)
        activeLinksById[linkKey] = link
        val destHash = link.destination?.hash
        if (destHash != null) {
            val destKey = RnsHex.encode(destHash)
            pendingLinksByDestination.remove(destKey)
            activeLinksByDestination[destKey] = linkKey
        }
        flushPendingLinkResources(link)
        flushPendingLinkPackets(link)
    }

    private fun queueLinkResource(link: RnsLink, resource: RnsResource, requestedIndices: List<Int>?) {
        val linkKey = RnsHex.encode(link.linkId)
        val pending = pendingLinkResources.getOrPut(linkKey) { CopyOnWriteArrayList() }
        pending.add(PendingLinkResource(resource, requestedIndices))
    }

    private fun queueLinkPacket(link: RnsLink, packetType: Int, context: Int, payload: ByteArray) {
        val linkKey = RnsHex.encode(link.linkId)
        val pending = pendingLinkPackets.getOrPut(linkKey) { CopyOnWriteArrayList() }
        pending.add(PendingLinkPacket(packetType, context, payload))
    }

    private fun flushPendingLinkResources(link: RnsLink) {
        val linkKey = RnsHex.encode(link.linkId)
        val pending = pendingLinkResources.remove(linkKey) ?: return
        pending.forEach { sendResourceToLink(link, it.resource, it.requestedIndices) }
    }

    private fun flushPendingLinkPackets(link: RnsLink) {
        val linkKey = RnsHex.encode(link.linkId)
        val pending = pendingLinkPackets.remove(linkKey) ?: return
        pending.forEach { sendLinkPacket(link, it.packetType, it.context, it.payload, null) }
    }

    private fun sendResourceToLink(link: RnsLink, resource: RnsResource, requestedIndices: List<Int>?) {
        sendLinkResource(link, resource.data, requestId = null, isResponse = false, isRequest = false)
    }

    private fun sendLinkPacket(
        link: RnsLink,
        packetType: Int,
        context: Int,
        payload: ByteArray,
        interfaceName: String?
    ): ByteArray? {
        val encrypted = link.encryptForPacket(packetType, context, payload) ?: return null
        val packet = RnsPacket(
            destination = RnsDestination.fromHash(link.linkId, RnsDestination.LINK),
            data = encrypted,
            packetType = packetType,
            context = context
        )
        val raw = packet.pack()
        val iface = interfaceName?.let { name -> interfaces.firstOrNull { it.name == name } }
        if (iface != null) {
            iface.send(raw)
        } else {
            interfaces.forEach { it.send(raw) }
        }
        return raw
    }

    private fun sendLinkResource(
        link: RnsLink,
        payload: ByteArray,
        requestId: ByteArray?,
        isResponse: Boolean,
        isRequest: Boolean
    ): Boolean {
        return linkResourceManager.advertise(
            resourceData = payload,
            link = link,
            requestId = requestId,
            isResponse = isResponse,
            isRequest = isRequest
        ) { packetType, context, data ->
            sendLinkPacket(link, packetType, context, data, null)
        }
    }

    private fun maybeIdentifyActiveInitiatorLink(link: RnsLink) {
        if (!link.initiator || !link.isActive()) return
        val identity = link.owner.identity ?: return
        link.identify(identity) { linkInstance, packetType, context, payload ->
            sendLinkPacket(linkInstance, packetType, context, payload, null)
        }
    }

    fun recallIdentity(destinationHash: ByteArray): RnsIdentity? {
        val entry = knownDestinations[RnsHex.encode(destinationHash)] ?: return null
        return RnsIdentity.fromPublic(entry.publicKey)
    }

    fun getKnownDestination(destinationHash: ByteArray): KnownDestination? {
        return knownDestinations[RnsHex.encode(destinationHash)]
    }
}
