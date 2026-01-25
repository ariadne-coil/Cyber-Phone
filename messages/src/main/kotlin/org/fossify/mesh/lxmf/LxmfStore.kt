package org.fossify.mesh.lxmf

import android.content.Context
import android.provider.Telephony
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.getNotificationBitmap
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.showReceivedMessageNotification
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.models.Conversation
import org.fossify.messages.models.Message
import org.fossify.mesh.MeshContactHelper

object LxmfStore {
    fun storeIncoming(context: Context, message: LxmfMessage) {
        val sourceHash = message.sourceHash
        val address = LxmfAddress.encode(sourceHash)
        val threadId = LxmfAddress.threadIdForAddress(address)
        val body = message.content
        val timestamp = (message.timestamp ?: (System.currentTimeMillis() / 1000.0)).toInt()
        val messageId = message.hash?.let { LxmfAddress.messageIdForHash(it) }
            ?: LxmfAddress.messageIdForHash(sourceHash + timestamp.toString().toByteArray())

        val contact = MeshContactHelper.getSimpleContactForMeshAddress(context, address)
        val participant = contact ?: buildMeshParticipant(address)
        val participants = arrayListOf(participant)

        ensureBackgroundThread {
            val messageModel = Message(
                id = messageId,
                body = body,
                type = Telephony.Sms.MESSAGE_TYPE_INBOX,
                status = 0,
                participants = participants,
                date = timestamp,
                read = false,
                threadId = threadId,
                isMMS = false,
                attachment = null,
                senderPhoneNumber = address,
                senderName = participant.name,
                senderPhotoUri = participant.photoUri,
                subscriptionId = -1
            )
            context.messagesDB.insertOrIgnore(messageModel)
            upsertConversation(context, threadId, participant, body, timestamp, read = false)
            val bitmap = context.getNotificationBitmap(participant.photoUri)
            context.showReceivedMessageNotification(
                messageId = messageModel.id,
                address = address,
                senderName = participant.name,
                body = body,
                threadId = threadId,
                bitmap = bitmap
            )
            refreshMessages()
            refreshConversations()
        }
    }

    fun storeOutgoing(context: Context, destinationAddress: String, body: String) {
        val normalized = LxmfAddress.normalize(destinationAddress)
        val threadId = LxmfAddress.threadIdForAddress(normalized)
        val timestamp = (System.currentTimeMillis() / 1000L).toInt()
        val messageId = LxmfAddress.messageIdForHash(
            normalized.toByteArray(Charsets.UTF_8) + timestamp.toString().toByteArray()
        )
        val contact = MeshContactHelper.getSimpleContactForMeshAddress(context, normalized)
        val participant = contact ?: buildMeshParticipant(normalized)
        val participants = arrayListOf(participant)

        ensureBackgroundThread {
            val messageModel = Message(
                id = messageId,
                body = body,
                type = Telephony.Sms.MESSAGE_TYPE_SENT,
                status = 0,
                participants = participants,
                date = timestamp,
                read = true,
                threadId = threadId,
                isMMS = false,
                attachment = null,
                senderPhoneNumber = normalized,
                senderName = participant.name,
                senderPhotoUri = participant.photoUri,
                subscriptionId = -1
            )
            context.messagesDB.insertOrUpdate(messageModel)
            upsertConversation(context, threadId, participant, body, timestamp, read = true)
            refreshMessages()
            refreshConversations()
        }
    }

    fun ensureConversation(context: Context, address: String): Conversation {
        val normalized = LxmfAddress.normalize(address)
        val threadId = LxmfAddress.threadIdForAddress(normalized)
        val existing = context.conversationsDB.getConversationWithThreadId(threadId)
        if (existing != null) return existing
        val participant = MeshContactHelper.getSimpleContactForMeshAddress(context, normalized)
            ?: buildMeshParticipant(normalized)
        val conversation = Conversation(
            threadId = threadId,
            snippet = "",
            date = (System.currentTimeMillis() / 1000L).toInt(),
            read = true,
            title = participant.name,
            photoUri = participant.photoUri,
            isGroupConversation = false,
            phoneNumber = normalized,
            isScheduled = false,
            usesCustomTitle = false,
            isArchived = false,
            unreadCount = 0
        )
        context.conversationsDB.insertOrUpdate(conversation)
        return conversation
    }

    fun updateConversationFromDb(context: Context, threadId: Long) {
        val lastMessage = context.messagesDB.getThreadMessages(threadId)
            .maxByOrNull { it.date }
        val conversation = context.conversationsDB.getConversationWithThreadId(threadId) ?: return
        if (lastMessage != null) {
            context.conversationsDB.insertOrUpdate(
                conversation.copy(
                    snippet = lastMessage.body,
                    date = lastMessage.date
                )
            )
        }
    }

    private fun upsertConversation(
        context: Context,
        threadId: Long,
        participant: SimpleContact,
        snippet: String,
        date: Int,
        read: Boolean
    ) {
        val existing = context.conversationsDB.getConversationWithThreadId(threadId)
        val unreadCount = if (!read) (existing?.unreadCount ?: 0) + 1 else (existing?.unreadCount ?: 0)
        val conversation = Conversation(
            threadId = threadId,
            snippet = snippet,
            date = date,
            read = read && (existing?.read ?: true),
            title = existing?.title ?: participant.name,
            photoUri = existing?.photoUri ?: participant.photoUri,
            isGroupConversation = false,
            phoneNumber = existing?.phoneNumber ?: participant.phoneNumbers.first().value,
            isScheduled = false,
            usesCustomTitle = existing?.usesCustomTitle ?: false,
            isArchived = existing?.isArchived ?: false,
            unreadCount = if (read) 0 else unreadCount
        )
        context.conversationsDB.insertOrUpdate(conversation)
    }

    private fun buildMeshParticipant(address: String): SimpleContact {
        val phoneNumber = PhoneNumber(address, 0, "", address)
        return SimpleContact(
            rawId = 0,
            contactId = 0,
            name = address,
            photoUri = "",
            phoneNumbers = arrayListOf(phoneNumber),
            birthdays = ArrayList(),
            anniversaries = ArrayList()
        )
    }
}
