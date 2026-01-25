package org.fossify.mesh.lxmf

import android.content.Context
import org.fossify.mesh.MeshIdentityStore
import org.fossify.mesh.rns.RnsConstants
import org.fossify.mesh.rns.RnsDestination
import org.fossify.mesh.rns.RnsHash
import org.fossify.mesh.rns.RnsIdentity
import org.fossify.mesh.rns.RnsNode
import org.fossify.mesh.rns.RnsPacket
import org.fossify.mesh.rns.RnsResource
import org.fossify.mesh.rns.RnsResourceAdvertisement
import org.fossify.mesh.rns.RnsResourceKind
import org.msgpack.core.MessagePack
import java.util.concurrent.CopyOnWriteArrayList

object LxmfRouter {
    private const val DESTINATION_HASH_LEN = 16

    private val listeners = CopyOnWriteArrayList<(LxmfMessage) -> Unit>()
    private var localIdentity: RnsIdentity? = null
    private var deliveryDestination: RnsDestination? = null
    private val resourceListener: (RnsResource) -> Unit = { resource ->
        handleResource(resource)
    }
    private val resourceAdvertisementListener: (RnsResourceAdvertisement) -> Unit = { advertisement ->
        handleResourceAdvertisement(advertisement)
    }

    fun start(context: Context) {
        if (deliveryDestination != null) return
        val meshIdentity = MeshIdentityStore.getOrCreate(context)
        val identity = meshIdentity.privateKey?.let { RnsIdentity.fromPrivate(it) }
            ?: RnsIdentity.fromPublic(meshIdentity.publicKey)
        localIdentity = identity
        val destination = RnsDestination.create(
            identity = identity,
            direction = RnsDestination.IN,
            type = RnsDestination.SINGLE,
            appName = LxmfConstants.APP_NAME,
            aspects = listOf("delivery")
        )
        deliveryDestination = destination
        RnsNode.registerDestination(destination) { packet, data ->
            handleIncoming(packet, data)
        }
        RnsNode.announce(destination)
        RnsNode.addResourceListener(resourceListener)
        RnsNode.addResourceAdvertisementListener(resourceAdvertisementListener)
    }

    fun stop() {
        deliveryDestination?.let { RnsNode.unregisterDestination(it.hash) }
        deliveryDestination = null
        localIdentity = null
        RnsNode.removeResourceListener(resourceListener)
        RnsNode.removeResourceAdvertisementListener(resourceAdvertisementListener)
    }

    fun addListener(listener: (LxmfMessage) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (LxmfMessage) -> Unit) {
        listeners.remove(listener)
    }

    fun sendText(destinationHash: ByteArray, text: String): Boolean {
        val local = deliveryDestination ?: return false
        val remoteIdentity = RnsNode.recallIdentity(destinationHash) ?: return false
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
            content = text
        )
        val packed = message.pack()
        val resource = createResourceForMessage(destinationHash, remoteIdentity, packed)
        if (resource != null) {
            RnsNode.advertiseResource(resource)
        }
        if (packed.size > RnsConstants.MDU) {
            if (resource == null) return false
            val sentViaLink = RnsNode.sendResourceViaLink(local, remoteDestination, resource)
            if (sentViaLink) return true
            return try {
                RnsNode.sendResourceTo(resource, destinationHash)
                true
            } catch (_: Exception) {
                true
            }
        }
        val payload = packed.copyOfRange(DESTINATION_HASH_LEN, packed.size)
        val packet = RnsPacket(destination = remoteDestination, data = payload)
        return try {
            RnsNode.send(packet)
            true
        } catch (_: Exception) {
            resource != null
        }
    }

    private fun createResourceForMessage(
        destinationHash: ByteArray,
        remoteIdentity: RnsIdentity,
        packed: ByteArray
    ): RnsResource? {
        val local = deliveryDestination ?: return null
        val encrypted = try {
            remoteIdentity.encrypt(packed)
        } catch (_: Exception) {
            return null
        }
        val hash = RnsHash.sha256(encrypted)
        return RnsResource(
            hash = hash,
            sourceHash = local.hash,
            destinationHash = destinationHash,
            kind = RnsResourceKind.LXMF_MESSAGE,
            encrypted = true,
            timestamp = System.currentTimeMillis() / 1000L,
            data = encrypted
        )
    }

    private fun handleResourceAdvertisement(advertisement: RnsResourceAdvertisement) {
        val local = deliveryDestination ?: return
        if (!advertisement.destinationHash.contentEquals(local.hash)) return
        if (RnsNode.hasResource(advertisement.hash)) return
        RnsNode.requestResource(advertisement, local.hash)
    }

    private fun handleResource(resource: RnsResource) {
        val local = deliveryDestination ?: return
        if (!resource.destinationHash.contentEquals(local.hash)) return
        val payload = if (resource.encrypted) {
            localIdentity?.decrypt(resource.data)
        } else {
            resource.data
        } ?: return

        when (resource.kind) {
            RnsResourceKind.LXMF_MESSAGE -> deliverMessage(payload)
            RnsResourceKind.LXMF_PROPAGATION -> handlePropagationPayload(payload)
        }
    }

    private fun deliverMessage(payload: ByteArray) {
        val message = LxmfMessage.unpackFromBytes(payload, RnsNode::recallIdentity)
        listeners.forEach { it(message) }
    }

    private fun handlePropagationPayload(payload: ByteArray) {
        try {
            val unpacker = MessagePack.newDefaultUnpacker(payload)
            val outerSize = unpacker.unpackArrayHeader()
            if (outerSize < 2) {
                unpacker.close()
                return
            }
            unpacker.unpackValue()
            val value = unpacker.unpackValue()
            unpacker.close()
            if (!value.isArrayValue) return
            value.asArrayValue().list().forEach { entry ->
                if (entry.isBinaryValue) {
                    val bytes = entry.asBinaryValue().asByteArray()
                    deliverMessage(bytes)
                }
            }
        } catch (_: Exception) {
            // Ignore malformed propagation payloads
        }
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
