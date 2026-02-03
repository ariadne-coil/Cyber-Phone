package org.fossify.messages.helpers

import android.content.Context
import android.provider.ContactsContract
import android.telephony.SmsManager
import org.fossify.commons.models.SimpleContact
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.getContactFromAddress
import org.fossify.messages.messaging.sendMessageCompat
import org.fossify.mesh.MeshContactHelper
import org.fossify.mesh.MeshIdentityStore
import org.fossify.mesh.lxmf.LxmfAddress
import org.fossify.mesh.lxmf.LxmfConstants
import org.fossify.mesh.rns.RnsDestination
import org.fossify.mesh.rns.RnsIdentity

object MeshDiscoveryManager {
    fun isMeshAddressMessage(text: String): Boolean {
        return text.startsWith(MESH_ADDRESS_MESSAGE_PREFIX)
    }

    fun buildMeshAddressMessage(context: Context): String? {
        val address = getLocalMeshAddress(context) ?: return null
        return MESH_ADDRESS_MESSAGE_PREFIX + address
    }

    fun extractMeshAddress(text: String): String? {
        val candidate = if (isMeshAddressMessage(text)) {
            text.removePrefix(MESH_ADDRESS_MESSAGE_PREFIX).trim()
        } else {
            text.trim()
        }
        val normalized = LxmfAddress.normalize(candidate)
        return if (LxmfAddress.isMeshAddress(normalized)) normalized else null
    }

    fun getLocalMeshAddress(context: Context): String? {
        val identity = MeshIdentityStore.getOrCreate(context)
        val rnsIdentity = identity.privateKey?.let { RnsIdentity.fromPrivate(it) }
            ?: RnsIdentity.fromPublic(identity.publicKey)
        val destination = RnsDestination.create(
            identity = rnsIdentity,
            direction = RnsDestination.OUT,
            type = RnsDestination.SINGLE,
            appName = LxmfConstants.APP_NAME,
            aspects = listOf("delivery")
        )
        return LxmfAddress.encode(destination.hash)
    }

    fun handleIncomingMeshAddress(
        context: Context,
        address: String,
        threadId: Long,
        text: String,
        subscriptionId: Int,
        allowAutoReply: Boolean = true
    ): Boolean {
        val meshAddress = extractMeshAddress(text) ?: return false
        storeMeshAddressForContact(context, address, meshAddress)
        if (allowAutoReply && shouldAutoReply(context, threadId)) {
            val reply = buildMeshAddressMessage(context)
            if (!reply.isNullOrBlank()) {
                val subId = if (subscriptionId >= 0) subscriptionId else SmsManager.getDefaultSmsSubscriptionId()
                context.sendMessageCompat(reply, listOf(address), subId, emptyList())
                markAutoReplySent(context, threadId)
            }
        }
        return true
    }

    fun getMeshAddressForContact(context: Context, contact: SimpleContact): String? {
        return when {
            contact.contactId > 0 -> MeshContactHelper.getMeshAddress(context, contact.contactId.toLong())
            contact.rawId > 0 -> MeshContactHelper.getMeshAddress(context, contact.rawId.toLong())
            else -> null
        }
    }

    private fun shouldAutoReply(context: Context, threadId: Long): Boolean {
        val key = threadId.toString()
        return !context.config.meshAddressReplyThreads.contains(key)
    }

    private fun markAutoReplySent(context: Context, threadId: Long) {
        val key = threadId.toString()
        context.config.meshAddressReplyThreads = context.config.meshAddressReplyThreads.plus(key)
    }

    private fun storeMeshAddressForContact(context: Context, address: String, meshAddress: String) {
        context.getContactFromAddress(address) { contact ->
            val rawId = resolveRawContactId(context, contact) ?: return@getContactFromAddress
            MeshContactHelper.upsertMeshAddressForRawContact(context, rawId, meshAddress)
        }
    }

    private fun resolveRawContactId(context: Context, contact: SimpleContact?): Long? {
        if (contact == null) return null
        if (contact.rawId > 0) {
            return contact.rawId.toLong()
        }
        if (contact.contactId <= 0) {
            return null
        }
        return context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID}=? AND ${ContactsContract.RawContacts.DELETED}=0",
            arrayOf(contact.contactId.toString()),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }
}
