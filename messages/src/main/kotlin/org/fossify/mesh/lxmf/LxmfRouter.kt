package org.fossify.mesh.lxmf

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import org.fossify.mesh.MeshConfig
import org.fossify.mesh.MeshIdentityStore
import org.fossify.mesh.MeshMode
import org.fossify.mesh.rns.RnsConstants
import org.fossify.mesh.rns.RnsDestination
import org.fossify.mesh.rns.RnsHash
import org.fossify.mesh.rns.RnsIdentity
import org.fossify.mesh.rns.RnsNode
import org.fossify.mesh.rns.RnsPacket
import org.fossify.mesh.rns.RnsResource
import org.fossify.mesh.rns.RnsResourceAdvertisement
import org.fossify.mesh.rns.RnsResourceKind
import org.fossify.mesh.rns.RnsHex
import org.msgpack.core.MessagePack
import org.msgpack.value.Value
import org.json.JSONObject
import kotlin.concurrent.thread
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.LinkedHashMap

object LxmfRouter {
    private const val DESTINATION_HASH_LEN = 16
    private const val SIGNATURE_LENGTH = 64
    private const val TIMESTAMP_SIZE = 8
    private const val STRUCT_OVERHEAD = 8
    private const val LXMF_OVERHEAD = (DESTINATION_HASH_LEN * 2) + SIGNATURE_LENGTH + TIMESTAMP_SIZE + STRUCT_OVERHEAD

    private const val PROPAGATION_COST_MIN = 13
    private const val PROPAGATION_COST = 16
    private const val PROPAGATION_COST_FLEX = 3
    private const val PROPAGATION_LIMIT_KB = 256
    private const val PROPAGATION_SYNC_LIMIT_KB = PROPAGATION_LIMIT_KB * 40
    private const val PEERING_COST = 18
    private const val PROPAGATION_OFFER_INTERVAL_MS = 60_000L
    private const val PROPAGATION_OFFER_JITTER_MS = 15_000L
    private const val PROPAGATION_OFFER_MAX_IDS = 512

    private const val ERROR_NO_IDENTITY = 0xF0
    private const val ERROR_NO_ACCESS = 0xF1
    private const val ERROR_INVALID_KEY = 0xF3
    private const val ERROR_INVALID_DATA = 0xF4
    private const val ERROR_INVALID_STAMP = 0xF5
    private const val ERROR_THROTTLED = 0xF6

    private const val OFFER_PATH = "/offer"
    private const val MESSAGE_GET_PATH = "/get"
    private val PROPAGATION_NAME_HASH = RnsHash.sha256("${LxmfConstants.APP_NAME}.propagation".toByteArray(Charsets.UTF_8))
        .copyOfRange(0, RnsConstants.NAME_HASH_LENGTH_BITS / 8)
    private val DELIVERY_NAME_HASH = RnsHash.sha256("${LxmfConstants.APP_NAME}.delivery".toByteArray(Charsets.UTF_8))
        .copyOfRange(0, RnsConstants.NAME_HASH_LENGTH_BITS / 8)

    private val listeners = CopyOnWriteArrayList<(LxmfMessage) -> Unit>()
    private var appContext: Context? = null
    private var localIdentity: RnsIdentity? = null
    private var deliveryDestination: RnsDestination? = null
    private var propagationDestination: RnsDestination? = null
    private var propagationEnabled = false
    private var propagationStore: LxmfPropagationStore? = null
    private var pendingPropagationSync = false
    private var offerSyncThread: Thread? = null
    private val offerSyncActive = AtomicBoolean(false)
    private val resourceListener: (RnsResource) -> Unit = { resource ->
        handleResource(resource)
    }
    private val resourceAdvertisementListener: (RnsResourceAdvertisement) -> Unit = { advertisement ->
        handleResourceAdvertisement(advertisement)
    }
    private var localRatchets: List<ByteArray> = emptyList()
    private val propagationPeers = ConcurrentHashMap<String, PropagationPeer>()
    private val propagationBuffer = ConcurrentHashMap<String, MutableMap<String, ByteArray>>()
    private val processedTransients = ConcurrentHashMap<String, Long>()
    private val outboundStampCosts = ConcurrentHashMap<String, Int>()
    private val announceListener: (org.fossify.mesh.rns.RnsAnnounce, org.fossify.mesh.rns.RnsPacket) -> Unit = { announce, _ ->
        handleAnnounce(announce)
    }

    private data class PropagationPeer(
        val destinationHash: ByteArray,
        val destination: RnsDestination,
        val nodeTimebase: Long,
        val nodeEnabled: Boolean,
        val transferLimitKb: Int,
        val syncLimitKb: Int,
        val stampCost: Int,
        val stampFlex: Int,
        val peeringCost: Int,
        val metadata: Map<Int, Any?>,
        @Volatile var lastOfferAt: Long = 0L
    )

    private data class PropagationAnnounceData(
        val nodeTimebase: Long,
        val nodeEnabled: Boolean,
        val transferLimitKb: Int,
        val syncLimitKb: Int,
        val stampCost: Int,
        val stampFlex: Int,
        val peeringCost: Int,
        val metadata: Map<Int, Any?>
    )

    private data class DeliveryAnnounceData(
        val displayName: String?,
        val stampCost: Int?
    )

    fun start(context: Context) {
        if (deliveryDestination != null) return
        appContext = context.applicationContext
        val meshIdentity = MeshIdentityStore.getOrCreate(context)
        val identity = meshIdentity.privateKey?.let { RnsIdentity.fromPrivate(it) }
            ?: RnsIdentity.fromPublic(meshIdentity.publicKey)
        localIdentity = identity
        val meshConfig = MeshConfig.newInstance(context)
        propagationEnabled = meshConfig.meshRoutingEnabled
        pendingPropagationSync = meshConfig.getMeshMode() != MeshMode.STANDARD_ONLY
        loadOutboundStampCosts(meshConfig)
        val destination = RnsDestination.create(
            identity = identity,
            direction = RnsDestination.IN,
            type = RnsDestination.SINGLE,
            appName = LxmfConstants.APP_NAME,
            aspects = listOf("delivery")
        )
        localRatchets = MeshIdentityStore.getRatchetPrivates(context)
        if (localRatchets.isNotEmpty()) {
            destination.setRatchets(localRatchets)
        }
        deliveryDestination = destination
        val announceProvider = {
            val current = MeshIdentityStore.ensureCurrentRatchet(context)
            localRatchets = MeshIdentityStore.getRatchetPrivates(context)
            if (localRatchets.isNotEmpty()) {
                destination.setRatchets(localRatchets)
            }
            val deliveryAppData = buildDeliveryAnnounceData(context)
            RnsNode.RnsAnnounceConfig(
                appData = deliveryAppData,
                ratchetPublic = RnsIdentity.ratchetPublicFromPrivate(current)
            )
        }
        RnsNode.registerDestination(destination, { packet, data ->
            handleIncoming(packet, data)
        }, announceProvider)
        val announceConfig = announceProvider()
        RnsNode.announce(destination, announceConfig.appData, announceConfig.ratchetPublic)

        if (propagationEnabled) {
            val propagation = RnsDestination.create(
                identity = identity,
                direction = RnsDestination.IN,
                type = RnsDestination.SINGLE,
                appName = LxmfConstants.APP_NAME,
                aspects = listOf("propagation")
            )
            propagationDestination = propagation
            propagationStore = LxmfPropagationStore(context.applicationContext)
            val propagationAnnounceProvider = {
                val config = buildPropagationAnnounceData()
                RnsNode.RnsAnnounceConfig(appData = config)
            }
            propagation.registerRequestHandler(OFFER_PATH) { pathHash, data, requestedAt, remoteIdentity, linkId ->
                handleOfferRequest(pathHash, data, requestedAt, remoteIdentity, linkId)
            }
            propagation.registerRequestHandler(MESSAGE_GET_PATH) { pathHash, data, requestedAt, remoteIdentity, linkId ->
                handleMessageGetRequest(pathHash, data, requestedAt, remoteIdentity, linkId)
            }
            RnsNode.registerDestination(propagation, { packet, data ->
                handlePropagationPacket(packet, data)
            }, propagationAnnounceProvider)
            RnsNode.announce(propagation, propagationAnnounceProvider().appData, null)
            startOfferSync()
        }
        RnsNode.addResourceListener(resourceListener)
        RnsNode.addResourceAdvertisementListener(resourceAdvertisementListener)
        RnsNode.addAnnounceListener(announceListener)
    }

    fun stop() {
        deliveryDestination?.let { RnsNode.unregisterDestination(it.hash) }
        deliveryDestination = null
        localIdentity = null
        propagationDestination?.let { RnsNode.unregisterDestination(it.hash) }
        propagationDestination = null
        propagationStore = null
        propagationEnabled = false
        stopOfferSync()
        appContext = null
        pendingPropagationSync = false
        RnsNode.removeResourceListener(resourceListener)
        RnsNode.removeResourceAdvertisementListener(resourceAdvertisementListener)
        RnsNode.removeAnnounceListener(announceListener)
        propagationPeers.clear()
        propagationBuffer.clear()
        processedTransients.clear()
        outboundStampCosts.clear()
    }

    private fun loadOutboundStampCosts(config: MeshConfig) {
        outboundStampCosts.clear()
        val raw = config.meshOutboundStampCosts ?: return
        try {
            val json = JSONObject(raw)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val cost = json.optInt(key, 0)
                if (cost > 0) {
                    outboundStampCosts[key] = cost
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun persistOutboundStampCosts() {
        val context = appContext ?: return
        try {
            val json = JSONObject()
            outboundStampCosts.forEach { (key, value) ->
                if (value > 0) {
                    json.put(key, value)
                }
            }
            MeshConfig.newInstance(context).meshOutboundStampCosts =
                if (json.length() == 0) null else json.toString()
        } catch (_: Exception) {
        }
    }

    private fun updateOutboundStampCost(destinationHash: ByteArray, stampCost: Int?) {
        val key = RnsHex.encode(destinationHash)
        if (stampCost == null || stampCost <= 0) {
            if (outboundStampCosts.remove(key) != null) {
                persistOutboundStampCosts()
            }
        } else {
            outboundStampCosts[key] = stampCost
            persistOutboundStampCosts()
        }
    }

    private fun buildDeliveryAnnounceData(context: Context): ByteArray? {
        val displayName = getProfileDisplayName(context)
        val stampCost: Int? = null
        if (displayName == null && stampCost == null) return null
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(2)
        if (displayName != null) {
            val bytes = displayName.toByteArray(Charsets.UTF_8)
            packer.packBinaryHeader(bytes.size)
            packer.writePayload(bytes)
        } else {
            packer.packNil()
        }
        if (stampCost != null) {
            packer.packInt(stampCost)
        } else {
            packer.packNil()
        }
        packer.close()
        return packer.toByteArray()
    }

    private fun parseDeliveryAnnounceData(appData: ByteArray): DeliveryAnnounceData? {
        return try {
            val unpacker = MessagePack.newDefaultUnpacker(appData)
            val size = unpacker.unpackArrayHeader()
            if (size <= 0) {
                unpacker.close()
                return null
            }
            val displayValue = unpacker.unpackValue()
            val stampValue = if (size > 1) unpacker.unpackValue() else null
            unpacker.close()

            val displayName = when {
                displayValue.isNilValue -> null
                displayValue.isBinaryValue -> displayValue.asBinaryValue().asByteArray().toString(Charsets.UTF_8)
                displayValue.isStringValue -> displayValue.asStringValue().asString()
                else -> null
            }

            val stampCost = if (stampValue != null && stampValue.isIntegerValue) {
                val cost = stampValue.asIntegerValue().toInt()
                if (cost in 1..254) cost else null
            } else {
                null
            }

            DeliveryAnnounceData(displayName = displayName, stampCost = stampCost)
        } catch (_: Exception) {
            null
        }
    }

    private fun getProfileDisplayName(context: Context): String? {
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
        if (permission != PackageManager.PERMISSION_GRANTED) return null
        return context.contentResolver.query(
            ContactsContract.Profile.CONTENT_URI,
            arrayOf(ContactsContract.Profile.DISPLAY_NAME_PRIMARY),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    fun addListener(listener: (LxmfMessage) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (LxmfMessage) -> Unit) {
        listeners.remove(listener)
    }

    fun sendText(
        destinationHash: ByteArray,
        text: String,
        fields: Map<Int, Any?> = emptyMap(),
        onDelivered: (() -> Unit)? = null
    ): Boolean {
        val local = deliveryDestination ?: return false
        val remoteIdentity = RnsNode.recallIdentity(destinationHash) ?: run {
            RnsNode.requestPath(destinationHash)
            return false
        }
        val stampCost = outboundStampCosts[RnsHex.encode(destinationHash)]
        val remoteDestination = RnsDestination.createWithHash(
            identity = remoteIdentity,
            direction = RnsDestination.OUT,
            type = RnsDestination.SINGLE,
            appName = LxmfConstants.APP_NAME,
            aspects = listOf("delivery"),
            hashOverride = destinationHash
        )
        val message = LxmfMessage.createOutgoing(
            destination = remoteDestination,
            source = local,
            title = "",
            content = text,
            fields = fields,
            stampCost = stampCost
        )
        val packed = message.pack()
        val resource = createResourceForMessage(destinationHash, packed)
        if (resource != null) {
            RnsNode.advertiseResource(resource)
        }
        if (packed.size > RnsConstants.MDU) {
            if (resource == null) return false
            val sentViaLink = RnsNode.sendResourceViaLink(local, remoteDestination, resource)
            if (sentViaLink) return true
            return sendPropagationCopy(packed, destinationHash, remoteIdentity)
        }
        val payload = packed.copyOfRange(DESTINATION_HASH_LEN, packed.size)
        val packet = RnsPacket(destination = remoteDestination, data = payload)
        return try {
            if (onDelivered != null) {
                RnsNode.sendWithReceipt(packet, destinationHash, onDelivered)
            } else {
                RnsNode.send(packet)
            }
            true
        } catch (_: Exception) {
            sendPropagationCopy(packed, destinationHash, remoteIdentity)
        }
    }

    private fun createResourceForMessage(
        destinationHash: ByteArray,
        packed: ByteArray
    ): RnsResource? {
        val local = deliveryDestination ?: return null
        val hash = RnsHash.sha256(packed)
        return RnsResource(
            hash = hash,
            sourceHash = local.hash,
            destinationHash = destinationHash,
            kind = RnsResourceKind.LXMF_MESSAGE,
            encrypted = false,
            timestamp = System.currentTimeMillis() / 1000L,
            data = packed
        )
    }

    private fun handleResourceAdvertisement(advertisement: RnsResourceAdvertisement) {
        val delivery = deliveryDestination
        val propagation = propagationDestination
        val requesterHash = when {
            delivery != null && advertisement.destinationHash.contentEquals(delivery.hash) -> delivery.hash
            propagation != null && advertisement.destinationHash.contentEquals(propagation.hash) -> propagation.hash
            else -> return
        }
        if (RnsNode.hasResource(advertisement.hash)) return
        RnsNode.requestResource(advertisement, requesterHash)
    }

    private fun handleResource(resource: RnsResource) {
        val local = deliveryDestination
        if (resource.kind == RnsResourceKind.LXMF_PROPAGATION || looksLikePropagation(resource.data)) {
            handlePropagationPayload(resource.data)
            return
        }
        if (local == null) return
        if (!resource.destinationHash.contentEquals(local.hash)) return
        val payload = if (resource.encrypted) {
            localIdentity?.decrypt(resource.data, localRatchets)
        } else {
            resource.data
        } ?: return

        when (resource.kind) {
            RnsResourceKind.LXMF_MESSAGE -> deliverMessage(payload)
            RnsResourceKind.LXMF_PROPAGATION -> handlePropagationPayload(payload)
        }
    }

    private fun looksLikePropagation(payload: ByteArray): Boolean {
        return try {
            val unpacker = MessagePack.newDefaultUnpacker(payload)
            val size = unpacker.unpackArrayHeader()
            if (size < 2) {
                unpacker.close()
                false
            } else {
                unpacker.unpackValue() // timestamp
                val value = unpacker.unpackValue()
                unpacker.close()
                value.isArrayValue
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun deliverMessage(payload: ByteArray) {
        val message = LxmfMessage.unpackFromBytes(payload, RnsNode::recallIdentity)
        val local = deliveryDestination
        if (local != null && !message.destinationHash.contentEquals(local.hash)) {
            bufferPropagation(message, payload)
            return
        }
        listeners.forEach { it(message) }
    }

    private fun handlePropagationPacket(packet: RnsPacket, data: ByteArray) {
        if (packet.destination?.type != RnsDestination.LINK) return
        val messages = decodePropagationMessages(data) ?: return
        processPropagationMessages(messages)
    }

    private fun handlePropagationPayload(payload: ByteArray) {
        val messages = decodePropagationMessages(payload) ?: return
        processPropagationMessages(messages)
    }

    private fun decodePropagationMessages(payload: ByteArray): List<ByteArray>? {
        return try {
            val unpacker = MessagePack.newDefaultUnpacker(payload)
            val outerSize = unpacker.unpackArrayHeader()
            if (outerSize < 2) {
                unpacker.close()
                return null
            }
            unpacker.unpackValue()
            val value = unpacker.unpackValue()
            unpacker.close()
            if (!value.isArrayValue) return null
            value.asArrayValue().list().mapNotNull { entry ->
                if (entry.isBinaryValue) entry.asBinaryValue().asByteArray() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun processPropagationMessages(messages: List<ByteArray>) {
        if (messages.isEmpty()) return
        val minAccepted = (PROPAGATION_COST - PROPAGATION_COST_FLEX).coerceAtLeast(0)
        val validated = LxmfStamper.validatePnStamps(messages, minAccepted, LXMF_OVERHEAD)
        if (validated.isEmpty()) return
        validated.forEach { entry ->
            handleValidatedPropagation(entry)
        }
    }

    private fun handleValidatedPropagation(entry: LxmfStamper.StampedMessage) {
        val transientKey = RnsHex.encode(entry.transientId)
        if (processedTransients.containsKey(transientKey)) return
        processedTransients[transientKey] = System.currentTimeMillis()

        if (entry.lxmfData.size < DESTINATION_HASH_LEN) return
        val destinationHash = entry.lxmfData.copyOfRange(0, DESTINATION_HASH_LEN)
        val local = deliveryDestination
        if (local != null && destinationHash.contentEquals(local.hash)) {
            val encrypted = entry.lxmfData.copyOfRange(DESTINATION_HASH_LEN, entry.lxmfData.size)
            val decrypted = localIdentity?.decrypt(encrypted, localRatchets)
            if (decrypted != null) {
                val full = destinationHash + decrypted
                deliverMessage(full)
                return
            }
        }

        if (propagationEnabled) {
            val store = propagationStore ?: return
            if (!store.hasEntry(entry.transientId)) {
                store.storeStampedMessage(entry.lxmfData, entry.stamp, entry.value)
            }
        }
    }

    private fun handleAnnounce(announce: org.fossify.mesh.rns.RnsAnnounce) {
        if (announce.nameHash.contentEquals(PROPAGATION_NAME_HASH)) {
            val appData = announce.appData ?: return
            val announceData = parsePropagationAnnounceData(appData) ?: return
            val identity = RnsIdentity.fromPublic(announce.publicKey)
            val destination = RnsDestination.createWithHash(
                identity = identity,
                direction = RnsDestination.OUT,
                type = RnsDestination.SINGLE,
                appName = LxmfConstants.APP_NAME,
                aspects = listOf("propagation"),
                hashOverride = announce.destinationHash
            )
            val peer = PropagationPeer(
                destinationHash = announce.destinationHash,
                destination = destination,
                nodeTimebase = announceData.nodeTimebase,
                nodeEnabled = announceData.nodeEnabled,
                transferLimitKb = announceData.transferLimitKb,
                syncLimitKb = announceData.syncLimitKb,
                stampCost = announceData.stampCost,
                stampFlex = announceData.stampFlex,
                peeringCost = announceData.peeringCost,
                metadata = announceData.metadata
            )
            propagationPeers[RnsHex.encode(announce.destinationHash)] = peer
            if (pendingPropagationSync) {
                pendingPropagationSync = false
                requestMessagesFromPropagationNode()
            }
        } else if (announce.nameHash.contentEquals(DELIVERY_NAME_HASH)) {
            announce.appData?.let { data ->
                val parsed = parseDeliveryAnnounceData(data)
                updateOutboundStampCost(announce.destinationHash, parsed?.stampCost)
            }
            forwardBufferedMessages(announce)
        }
    }

    private fun buildPropagationAnnounceData(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(7)
        packer.packBoolean(false)
        packer.packLong(System.currentTimeMillis() / 1000L)
        packer.packBoolean(propagationEnabled)
        packer.packInt(PROPAGATION_LIMIT_KB)
        packer.packInt(PROPAGATION_SYNC_LIMIT_KB)
        packer.packArrayHeader(3)
        packer.packInt(PROPAGATION_COST)
        packer.packInt(PROPAGATION_COST_FLEX)
        packer.packInt(PEERING_COST)
        packer.packMapHeader(0)
        packer.close()
        return packer.toByteArray()
    }

    private fun parsePropagationAnnounceData(appData: ByteArray): PropagationAnnounceData? {
        return try {
            val unpacker = MessagePack.newDefaultUnpacker(appData)
            val size = unpacker.unpackArrayHeader()
            if (size < 7) {
                unpacker.close()
                return null
            }
            val legacyValue = unpacker.unpackValue()
            val timebaseValue = unpacker.unpackValue()
            val nodeEnabledValue = unpacker.unpackValue()
            val transferLimitValue = unpacker.unpackValue()
            val syncLimitValue = unpacker.unpackValue()
            val stampValue = unpacker.unpackValue()
            val metadataValue = unpacker.unpackValue()
            unpacker.close()

            val nodeTimebase = valueToLong(timebaseValue) ?: return null
            val nodeEnabled = valueToBoolean(nodeEnabledValue) ?: return null
            val transferLimit = valueToLong(transferLimitValue)?.toInt() ?: return null
            val syncLimit = valueToLong(syncLimitValue)?.toInt() ?: return null
            if (!stampValue.isArrayValue) return null
            val stampList = stampValue.asArrayValue().list()
            if (stampList.size < 3) return null
            val stampCost = valueToLong(stampList[0])?.toInt() ?: return null
            val stampFlex = valueToLong(stampList[1])?.toInt() ?: return null
            val peeringCost = valueToLong(stampList[2])?.toInt() ?: return null
            val metadata = if (metadataValue.isMapValue) {
                metadataValue.asMapValue().map().mapNotNull { entry ->
                    val key = valueToLong(entry.key)?.toInt() ?: return@mapNotNull null
                    key to unpackAny(entry.value)
                }.toMap()
            } else {
                emptyMap()
            }

            PropagationAnnounceData(
                nodeTimebase = nodeTimebase,
                nodeEnabled = nodeEnabled,
                transferLimitKb = transferLimit,
                syncLimitKb = syncLimit,
                stampCost = stampCost,
                stampFlex = stampFlex,
                peeringCost = peeringCost,
                metadata = metadata
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun valueToLong(value: Value): Long? {
        return when {
            value.isIntegerValue -> value.asIntegerValue().toLong()
            value.isFloatValue -> value.asFloatValue().toDouble().toLong()
            else -> null
        }
    }

    private fun valueToBoolean(value: Value): Boolean? {
        return if (value.isBooleanValue) value.asBooleanValue().boolean else null
    }

    private fun unpackAny(value: Value): Any? {
        return when {
            value.isNilValue -> null
            value.isBooleanValue -> value.asBooleanValue().boolean
            value.isIntegerValue -> value.asIntegerValue().toLong()
            value.isFloatValue -> value.asFloatValue().toDouble()
            value.isStringValue -> value.asStringValue().asString()
            value.isBinaryValue -> value.asBinaryValue().asByteArray()
            value.isArrayValue -> value.asArrayValue().list().map { unpackAny(it) }
            value.isMapValue -> value.asMapValue().map().mapNotNull { entry ->
                val key = unpackAny(entry.key)
                key to unpackAny(entry.value)
            }.toMap()
            else -> null
        }
    }

    private fun handleOfferRequest(
        pathHash: ByteArray,
        data: Any?,
        requestedAt: Double,
        remoteIdentity: RnsIdentity?,
        linkId: ByteArray
    ): Any? {
        if (remoteIdentity == null) return ERROR_NO_IDENTITY
        val list = data as? List<*> ?: return ERROR_INVALID_DATA
        if (list.size < 2) return ERROR_INVALID_DATA
        val peeringKey = list[0] as? ByteArray ?: return ERROR_INVALID_DATA
        val transientIds = (list[1] as? List<*>)?.mapNotNull { it as? ByteArray } ?: return ERROR_INVALID_DATA
        val localIdentity = localIdentity ?: return ERROR_NO_IDENTITY
        val peeringId = localIdentity.hash + remoteIdentity.hash
        val validKey = LxmfStamper.validatePeeringKey(peeringId, peeringKey, PEERING_COST)
        if (!validKey) return ERROR_INVALID_KEY
        val store = propagationStore ?: return ERROR_NO_ACCESS
        val wanted = transientIds.filter { !store.hasEntry(it) }
        return when {
            wanted.isEmpty() -> false
            wanted.size == transientIds.size -> true
            else -> wanted
        }
    }

    private fun handleMessageGetRequest(
        pathHash: ByteArray,
        data: Any?,
        requestedAt: Double,
        remoteIdentity: RnsIdentity?,
        linkId: ByteArray
    ): Any? {
        if (remoteIdentity == null) return ERROR_NO_IDENTITY
        val store = propagationStore ?: return ERROR_NO_ACCESS
        val list = data as? List<*> ?: return ERROR_INVALID_DATA
        if (list.size < 2) return ERROR_INVALID_DATA

        val remoteDestination = RnsDestination.create(
            identity = remoteIdentity,
            direction = RnsDestination.OUT,
            type = RnsDestination.SINGLE,
            appName = LxmfConstants.APP_NAME,
            aspects = listOf("delivery")
        )

        val wants = list[0] as? List<*>
        val haves = list[1] as? List<*>
        val transferLimitKb = (list.getOrNull(2) as? Number)?.toDouble()?.times(1000.0)

        if (wants == null && haves == null) {
            return store.listEntriesForDestination(remoteDestination.hash)
                .sortedBy { it.sizeBytes }
                .map { it.transientId }
        }

        haves?.mapNotNull { it as? ByteArray }?.forEach { transientId ->
            val entry = store.getEntry(transientId) ?: return@forEach
            if (entry.destinationHash.contentEquals(remoteDestination.hash)) {
                store.removeEntry(transientId)
            }
        }

        val responseMessages = ArrayList<ByteArray>()
        var cumulativeSize = 24
        val perMessageOverhead = 16

        wants?.mapNotNull { it as? ByteArray }?.forEach { transientId ->
            val entry = store.getEntry(transientId) ?: return@forEach
            if (!entry.destinationHash.contentEquals(remoteDestination.hash)) return@forEach
            val lxmfData = store.readLxmfData(entry) ?: return@forEach
            val nextSize = cumulativeSize + (lxmfData.size + perMessageOverhead)
            if (transferLimitKb != null && nextSize > transferLimitKb) return@forEach
            responseMessages.add(lxmfData)
            cumulativeSize = nextSize
        }

        return responseMessages
    }

    private fun selectPropagationPeer(): PropagationPeer? {
        val enabledPeer = propagationPeers.values.firstOrNull { it.nodeEnabled }
        return enabledPeer ?: propagationPeers.values.firstOrNull()
    }

    private fun startOfferSync() {
        if (!offerSyncActive.compareAndSet(false, true)) return
        offerSyncThread = thread(start = true, name = "lxmf-offer-sync") {
            while (offerSyncActive.get()) {
                try {
                    performOfferSync()
                } catch (_: Exception) {
                }
                val delay = PROPAGATION_OFFER_INTERVAL_MS +
                    ThreadLocalRandom.current().nextLong(0, PROPAGATION_OFFER_JITTER_MS + 1)
                try {
                    Thread.sleep(delay)
                } catch (_: InterruptedException) {
                }
            }
        }
    }

    private fun stopOfferSync() {
        offerSyncActive.set(false)
        offerSyncThread?.interrupt()
        offerSyncThread = null
    }

    private fun performOfferSync() {
        if (!propagationEnabled) return
        val store = propagationStore ?: return
        if (store.listEntries().isEmpty()) return
        val peers = propagationPeers.values.toList()
        if (peers.isEmpty()) return
        val now = System.currentTimeMillis()
        peers.forEach { peer ->
            if (!peer.nodeEnabled) return@forEach
            if (now - peer.lastOfferAt < PROPAGATION_OFFER_INTERVAL_MS / 2) return@forEach
            offerToPeer(peer)
            peer.lastOfferAt = now
        }
    }

    private fun offerToPeer(peer: PropagationPeer) {
        val propagation = propagationDestination ?: return
        val store = propagationStore ?: return
        val local = localIdentity ?: return
        val remote = peer.destination.identity ?: return

        if (!RnsNode.identifyLink(propagation, peer.destination, local)) {
            thread {
                Thread.sleep(1500)
                offerToPeer(peer)
            }
            return
        }

        val peeringId = remote.hash + local.hash
        val peeringKey = LxmfStamper.generatePeeringKey(peeringId, peer.peeringCost) ?: return
        val transientIds = buildOfferList(store, peer)
        if (transientIds.isEmpty()) return

        RnsNode.requestOverLink(
            owner = propagation,
            destination = peer.destination,
            path = OFFER_PATH,
            data = listOf(peeringKey, transientIds),
            onResponse = { receipt ->
                handleOfferResponse(receipt, peer, transientIds)
            },
            onFailure = null
        )
    }

    private fun buildOfferList(store: LxmfPropagationStore, peer: PropagationPeer): List<ByteArray> {
        val entries = store.listEntries()
            .sortedByDescending { it.receivedAt }
        if (entries.isEmpty()) return emptyList()
        val maxIds = when {
            peer.syncLimitKb > 0 -> {
                val approx = (peer.syncLimitKb * 1000 / 24).toInt()
                approx.coerceIn(1, PROPAGATION_OFFER_MAX_IDS)
            }
            else -> PROPAGATION_OFFER_MAX_IDS
        }
        val result = ArrayList<ByteArray>(maxIds)
        for (entry in entries) {
            result.add(entry.transientId)
            if (result.size >= maxIds) break
        }
        return result
    }

    private fun handleOfferResponse(
        receipt: org.fossify.mesh.rns.RnsRequestReceipt,
        peer: PropagationPeer,
        offeredIds: List<ByteArray>
    ) {
        val response = receipt.response ?: return
        val wants: List<ByteArray> = when (response) {
            is Boolean -> if (response) offeredIds else emptyList()
            is List<*> -> response.mapNotNull { it as? ByteArray }
            else -> emptyList()
        }
        if (wants.isEmpty()) return
        val store = propagationStore ?: return
        wants.forEach { transientId ->
            val entry = store.getEntry(transientId) ?: return@forEach
            sendPropagationEntryToPeer(peer, entry)
        }
    }

    private fun sendPropagationEntryToPeer(peer: PropagationPeer, entry: LxmfPropagationStore.PropagationEntry) {
        val propagation = propagationDestination ?: return
        val store = propagationStore ?: return
        val stamped = store.readStampedData(entry) ?: return
        val payload = packPropagationPayload(listOf(stamped))
        if (payload.size <= RnsConstants.MDU) {
            RnsNode.sendPacketViaLink(propagation, peer.destination, payload)
        } else {
            val resource = RnsResource(
                hash = RnsHash.sha256(payload),
                sourceHash = propagation.hash,
                destinationHash = peer.destination.hash,
                kind = RnsResourceKind.LXMF_PROPAGATION,
                encrypted = false,
                timestamp = System.currentTimeMillis() / 1000L,
                data = payload
            )
            RnsNode.sendResourceViaLink(propagation, peer.destination, resource)
        }
    }

    fun requestMessagesFromPropagationNode(maxMessages: Int = 0) {
        val peer = selectPropagationPeer() ?: return
        val owner = deliveryDestination ?: return
        val identity = localIdentity ?: return

        if (!RnsNode.identifyLink(owner, peer.destination, identity)) {
            thread {
                Thread.sleep(1500)
                requestMessagesFromPropagationNode(maxMessages)
            }
            return
        }

        val receipt = RnsNode.requestOverLink(
            owner = owner,
            destination = peer.destination,
            path = MESSAGE_GET_PATH,
            data = listOf(null, null),
            onResponse = { responseReceipt ->
                handleMessageListResponse(responseReceipt, maxMessages, peer)
            },
            onFailure = null
        )

        if (receipt == null) {
            thread {
                Thread.sleep(1500)
                requestMessagesFromPropagationNode(maxMessages)
            }
        }
    }

    private fun handleMessageListResponse(
        receipt: org.fossify.mesh.rns.RnsRequestReceipt,
        maxMessages: Int,
        peer: PropagationPeer
    ) {
        val response = receipt.response
        val errorCode = (response as? Number)?.toInt()
        if (errorCode == ERROR_NO_IDENTITY || errorCode == ERROR_NO_ACCESS) return
        val list = response as? List<*> ?: return
        val wants = ArrayList<ByteArray>()
        val haves = ArrayList<ByteArray>()
        list.mapNotNull { it as? ByteArray }.forEach { transientId ->
            val key = RnsHex.encode(transientId)
            if (processedTransients.containsKey(key)) {
                haves.add(transientId)
            } else if (maxMessages == 0 || wants.size < maxMessages) {
                wants.add(transientId)
            }
        }
        if (wants.isEmpty()) return
        val owner = deliveryDestination ?: return
        val requestData = listOf(wants, haves, PROPAGATION_LIMIT_KB)
        RnsNode.requestOverLink(
            owner = owner,
            destination = peer.destination,
            path = MESSAGE_GET_PATH,
            data = requestData,
            onResponse = { responseReceipt ->
                handleMessageGetResponse(responseReceipt, peer)
            },
            onFailure = null
        )
    }

    private fun handleMessageGetResponse(
        receipt: org.fossify.mesh.rns.RnsRequestReceipt,
        peer: PropagationPeer
    ) {
        val response = receipt.response
        val errorCode = (response as? Number)?.toInt()
        if (errorCode == ERROR_NO_IDENTITY || errorCode == ERROR_NO_ACCESS) return
        val list = response as? List<*> ?: return
        val receivedIds = ArrayList<ByteArray>()
        list.mapNotNull { it as? ByteArray }.forEach { lxmfData ->
            handleUnstampedPropagationMessage(lxmfData)
            receivedIds.add(RnsHash.sha256(lxmfData))
        }
        if (receivedIds.isEmpty()) return
        val owner = deliveryDestination ?: return
        RnsNode.requestOverLink(
            owner = owner,
            destination = peer.destination,
            path = MESSAGE_GET_PATH,
            data = listOf(null, receivedIds),
            onResponse = null,
            onFailure = null
        )
    }

    private fun handleUnstampedPropagationMessage(lxmfData: ByteArray) {
        val transientId = RnsHash.sha256(lxmfData)
        val entry = LxmfStamper.StampedMessage(
            transientId = transientId,
            lxmfData = lxmfData,
            value = 0,
            stamp = ByteArray(0)
        )
        handleValidatedPropagation(entry)
    }

    private fun bufferPropagation(message: LxmfMessage, payload: ByteArray) {
        val destKey = RnsHex.encode(message.destinationHash)
        val msgKey = message.hash?.let { RnsHex.encode(it) } ?: "${destKey}:${message.timestamp ?: 0.0}"
        val bucket = propagationBuffer.getOrPut(destKey) { LinkedHashMap() }
        synchronized(bucket) {
            if (!bucket.containsKey(msgKey)) {
                if (bucket.size >= 64) {
                    val oldest = bucket.keys.firstOrNull()
                    if (oldest != null) bucket.remove(oldest)
                }
                bucket[msgKey] = payload
            }
        }
    }

    private fun forwardBufferedMessages(announce: org.fossify.mesh.rns.RnsAnnounce) {
        val destKey = RnsHex.encode(announce.destinationHash)
        val bucket = propagationBuffer[destKey] ?: return
        if (bucket.isEmpty()) return
        val identity = RnsIdentity.fromPublic(announce.publicKey)
        val destination = RnsDestination.createWithHash(
            identity = identity,
            direction = RnsDestination.OUT,
            type = RnsDestination.SINGLE,
            appName = LxmfConstants.APP_NAME,
            aspects = listOf("delivery"),
            hashOverride = announce.destinationHash
        )
        synchronized(bucket) {
            val iterator = bucket.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val packed = entry.value
                try {
                    sendPackedMessage(destination, announce.destinationHash, packed)
                    iterator.remove()
                } catch (_: Exception) {
                    // keep for later
                }
            }
        }
    }

    private fun sendPackedMessage(
        destination: RnsDestination,
        destinationHash: ByteArray,
        packed: ByteArray
    ) {
        val local = deliveryDestination ?: return
        val resource = createResourceForMessage(destinationHash, packed)
        if (resource != null) {
            RnsNode.advertiseResource(resource)
        }
        if (packed.size > RnsConstants.MDU) {
            if (resource == null) return
            val sentViaLink = RnsNode.sendResourceViaLink(local, destination, resource)
            if (!sentViaLink) {
                throw IllegalStateException("Resource link send failed")
            }
            return
        }
        val payload = packed.copyOfRange(DESTINATION_HASH_LEN, packed.size)
        val packet = RnsPacket(destination = destination, data = payload)
        RnsNode.send(packet)
    }

    private fun sendPropagationCopy(
        packed: ByteArray,
        destinationHash: ByteArray,
        remoteIdentity: RnsIdentity
    ): Boolean {
        val peer = selectPropagationPeer() ?: return false
        val local = deliveryDestination ?: return false
        thread {
            val payload = buildPropagationPayload(packed, destinationHash, remoteIdentity, peer.stampCost)
                ?: return@thread
            if (payload.size <= RnsConstants.MDU) {
                RnsNode.sendPacketViaLink(local, peer.destination, payload)
            } else {
                val resource = RnsResource(
                    hash = RnsHash.sha256(payload),
                    sourceHash = local.hash,
                    destinationHash = peer.destination.hash,
                    kind = RnsResourceKind.LXMF_PROPAGATION,
                    encrypted = false,
                    timestamp = System.currentTimeMillis() / 1000L,
                    data = payload
                )
                RnsNode.sendResourceViaLink(local, peer.destination, resource)
            }
        }
        return true
    }

    private fun buildPropagationPayload(
        packed: ByteArray,
        destinationHash: ByteArray,
        remoteIdentity: RnsIdentity,
        stampCost: Int
    ): ByteArray? {
        val encrypted = try {
            val ratchet = RnsIdentity.getRatchetForDestination(destinationHash)
            remoteIdentity.encrypt(packed.copyOfRange(DESTINATION_HASH_LEN, packed.size), ratchet)
        } catch (_: Exception) {
            return null
        }
        val lxmfData = destinationHash + encrypted
        val transientId = RnsHash.sha256(lxmfData)
        val targetCost = stampCost.coerceAtLeast(PROPAGATION_COST_MIN)
        val stampResult = LxmfStamper.generateStamp(transientId, targetCost)
        val stamped = if (stampResult != null && stampResult.stamp.isNotEmpty()) {
            lxmfData + stampResult.stamp
        } else {
            lxmfData
        }
        return packPropagationPayload(listOf(stamped))
    }

    private fun packPropagationPayload(messages: List<ByteArray>): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(2)
        packer.packDouble(System.currentTimeMillis() / 1000.0)
        packer.packArrayHeader(messages.size)
        messages.forEach { entry ->
            packer.packBinaryHeader(entry.size)
            packer.writePayload(entry)
        }
        packer.close()
        return packer.toByteArray()
    }

    private fun handleIncoming(packet: RnsPacket, data: ByteArray) {
        val destination = packet.destination ?: return
        val lxmfBytes = if (destination.type != RnsDestination.LINK) {
            destination.hash + data
        } else {
            data
        }
        val message = LxmfMessage.unpackFromBytes(lxmfBytes, RnsNode::recallIdentity)
        listeners.forEach { it(message) }
    }
}
