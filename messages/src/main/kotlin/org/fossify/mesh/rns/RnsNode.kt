package org.fossify.mesh.rns

import android.content.Context
import java.util.LinkedHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap

object RnsNode {
    private const val DEFAULT_UDP_PORT = 4242
    private const val MAX_HOPS = 128
    private const val ANNOUNCE_CACHE_LIMIT = 256
    private const val RESOURCE_CACHE_LIMIT = 256
    private const val RESOURCE_SEEN_LIMIT = 512
    private const val RESOURCE_REQUEST_LIMIT = 512
    private const val RESOURCE_REQUEST_COOLDOWN_MS = 15_000L
    private const val RESOURCE_ASSEMBLY_TIMEOUT_MS = 5 * 60 * 1000L

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
    private val pendingLinksById = ConcurrentHashMap<String, RnsLink>()
    private val pendingLinksByDestination = ConcurrentHashMap<String, String>()
    private val activeLinksById = ConcurrentHashMap<String, RnsLink>()
    private val activeLinksByDestination = ConcurrentHashMap<String, String>()
    private val pendingLinkResources = ConcurrentHashMap<String, CopyOnWriteArrayList<PendingLinkResource>>()

    private var routingEnabled = false

    fun start(context: Context, routing: Boolean) {
        if (running.setIfFalse()) {
            routingEnabled = routing
            val udpInterface = RnsUdpInterface(
                name = "udp",
                listenPort = DEFAULT_UDP_PORT,
                forwardAddress = "255.255.255.255",
                forwardPort = DEFAULT_UDP_PORT
            ) { raw, iface ->
                handleIncoming(raw, iface)
            }
            interfaces.add(udpInterface)
            interfaces.forEach { it.start() }
        } else {
            routingEnabled = routing
        }
    }

    fun stop() {
        if (running.setIfTrue()) {
            interfaces.forEach { it.stop() }
            interfaces.clear()
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
        }
    }

    fun setRoutingEnabled(enabled: Boolean) {
        routingEnabled = enabled
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
            sendResourceToLink(link, resource, null)
        } else {
            cacheResource(resource)
            queueLinkResource(link, resource, null)
        }
        return true
    }

    fun hasResource(resourceHash: ByteArray): Boolean {
        return resourceCache.containsKey(RnsHex.encode(resourceHash))
    }

    fun registerDestination(destination: RnsDestination, callback: (RnsPacket, ByteArray) -> Unit) {
        localDestinations[RnsHex.encode(destination.hash)] = LocalDestination(destination, callback)
    }

    fun unregisterDestination(destinationHash: ByteArray) {
        localDestinations.remove(RnsHex.encode(destinationHash))
    }

    fun announce(destination: RnsDestination, appData: ByteArray? = null) {
        val packet = RnsAnnounce.build(destination, appData)
        send(packet)
    }

    fun send(packet: RnsPacket, interfaceName: String? = null) {
        val raw = packet.pack()
        val iface = interfaceName?.let { name -> interfaces.firstOrNull { it.name == name } }
        if (iface != null) {
            iface.send(raw)
        } else {
            interfaces.forEach { it.send(raw) }
        }
    }

    private fun handleIncoming(raw: ByteArray, iface: RnsInterface) {
        val packet = try {
            RnsPacket.fromRaw(raw)
        } catch (_: Exception) {
            return
        }

        val destHash = packet.destination?.hash ?: return
        val destKey = RnsHex.encode(destHash)

        if (packet.packetType == RnsPacket.ANNOUNCE) {
            handleAnnounce(raw, packet, iface)
            return
        }

        if (packet.packetType == RnsPacket.LINKREQUEST) {
            handleLinkRequest(raw, packet, iface)
            return
        }

        if (packet.destination?.type == RnsDestination.LINK) {
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

        if (packet.packetType == RnsPacket.PROOF && packet.context == RnsPacket.LRPROOF) {
            handleLinkProof(link, packet, iface)
            return
        }

        val payload = link.decryptForPacket(packet.packetType, packet.context, packet.data) ?: return
        when (packet.context) {
            RnsPacket.LRRTT -> {
                if (link.handleRttPayload(payload)) {
                    activateLink(link)
                }
            }
            RnsPacket.RESOURCE_ADV -> handleResourceAdvertisementPayload(payload)
            RnsPacket.RESOURCE_REQ -> handleResourceRequestPayload(payload, link)
            RnsPacket.RESOURCE -> handleResourceChunk(packet.copy(data = payload))
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
        val key = RnsHex.encode(announce.destinationHash)
        if (localDestinations.containsKey(key)) {
            return
        }
        val now = System.currentTimeMillis()

        knownDestinations[key] = KnownDestination(
            destinationHash = announce.destinationHash,
            publicKey = announce.publicKey,
            nameHash = announce.nameHash,
            appData = announce.appData,
            lastSeen = now,
            hops = packet.hops
        )

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
        val request = RnsResourceCodec.unpackRequest(payload) ?: return
        val key = RnsHex.encode(request.hash)
        val resource = resourceCache[key] ?: return
        val indices = request.requestedIndices.takeIf { it.isNotEmpty() }
        sendResourceToLink(link, resource, indices)
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
        val forwarded = rawWithIncrementedHops(raw, packet.hops)
        interfaces.filter { it != iface }.forEach { it.send(forwarded) }
    }

    private fun forwardResource(raw: ByteArray, packet: RnsPacket, iface: RnsInterface) {
        if (packet.hops >= MAX_HOPS) return
        val forwarded = rawWithIncrementedHops(raw, packet.hops)
        interfaces.filter { it != iface }.forEach { it.send(forwarded) }
    }

    private fun forwardPacket(raw: ByteArray, packet: RnsPacket, iface: RnsInterface) {
        if (packet.hops >= MAX_HOPS) return
        val destKey = RnsHex.encode(packet.destination!!.hash)
        val path = pathTable[destKey] ?: return
        val forwarded = rawWithIncrementedHops(raw, packet.hops)
        if (path.interfaceRef != iface) {
            path.interfaceRef.send(forwarded)
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
        return routingEnabled || isLocalDestination(resource.destinationHash)
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
        val appData: ByteArray?,
        val lastSeen: Long,
        val hops: Int
    )

    data class PathEntry(
        val interfaceRef: RnsInterface,
        val hops: Int,
        val timestamp: Long
    )

    data class LocalDestination(
        val destination: RnsDestination,
        val callback: (RnsPacket, ByteArray) -> Unit
    )

    private data class PendingLinkResource(
        val resource: RnsResource,
        val requestedIndices: List<Int>?
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
        if (active != null) return active
        val pendingId = pendingLinksByDestination[destKey]
        val pending = pendingId?.let { pendingLinksById[it] }
        if (pending != null) return pending

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
    }

    private fun queueLinkResource(link: RnsLink, resource: RnsResource, requestedIndices: List<Int>?) {
        val linkKey = RnsHex.encode(link.linkId)
        val pending = pendingLinkResources.getOrPut(linkKey) { CopyOnWriteArrayList() }
        pending.add(PendingLinkResource(resource, requestedIndices))
    }

    private fun flushPendingLinkResources(link: RnsLink) {
        val linkKey = RnsHex.encode(link.linkId)
        val pending = pendingLinkResources.remove(linkKey) ?: return
        pending.forEach { sendResourceToLink(link, it.resource, it.requestedIndices) }
    }

    private fun sendResourceToLink(link: RnsLink, resource: RnsResource, requestedIndices: List<Int>?) {
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
            val payload = RnsResourceCodec.packChunk(chunk)
            sendLinkPacket(link, RnsPacket.DATA, RnsPacket.RESOURCE, payload, null)
        }
    }

    private fun sendLinkPacket(
        link: RnsLink,
        packetType: Int,
        context: Int,
        payload: ByteArray,
        interfaceName: String?
    ) {
        val encrypted = link.encryptForPacket(packetType, context, payload) ?: return
        val packet = RnsPacket(
            destination = RnsDestination.fromHash(link.linkId, RnsDestination.LINK),
            data = encrypted,
            packetType = packetType,
            context = context
        )
        send(packet, interfaceName)
    }

    fun recallIdentity(destinationHash: ByteArray): RnsIdentity? {
        val entry = knownDestinations[RnsHex.encode(destinationHash)] ?: return null
        return RnsIdentity.fromPublic(entry.publicKey)
    }

    fun getKnownDestination(destinationHash: ByteArray): KnownDestination? {
        return knownDestinations[RnsHex.encode(destinationHash)]
    }
}
