package org.fossify.mesh.lxmf

import android.content.Context
import android.provider.Telephony
import android.webkit.MimeTypeMap
import org.fossify.commons.extensions.getMyFileUri
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.getNotificationBitmap
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.shouldUnarchive
import org.fossify.messages.extensions.showReceivedMessageNotification
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.models.Conversation
import org.fossify.messages.models.Message
import org.fossify.mesh.MeshContactHelper
import java.io.File

object LxmfStore {
    private fun looksLikeGif(payload: LxmfAttachmentPayload): Boolean {
        val name = payload.filename.lowercase()
        if (name.endsWith(".gif")) return true
        val data = payload.data
        if (data.size < 6) return false
        // GIF87a / GIF89a
        return data[0] == 'G'.code.toByte() &&
            data[1] == 'I'.code.toByte() &&
            data[2] == 'F'.code.toByte() &&
            data[3] == '8'.code.toByte() &&
            (data[4] == '7'.code.toByte() || data[4] == '9'.code.toByte()) &&
            data[5] == 'a'.code.toByte()
    }

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

        val attachmentPayloads = LxmfAttachments.decode(message.fields)
        ensureBackgroundThread {
            val attachment = persistMeshAttachments(context, messageId, attachmentPayloads)
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
                attachment = attachment,
                senderPhoneNumber = address,
                senderName = participant.name,
                senderPhotoUri = participant.photoUri,
                subscriptionId = -1
            )
            // Mesh can deliver the same LXMF payload through multiple paths (direct packet, resource transfer,
            // propagation). Only notify/update unread counts if this is a *new* message for us.
            val inserted = context.messagesDB.insertOrIgnore(messageModel)
            if (inserted == -1L) {
                return@ensureBackgroundThread
            }
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

    fun storeOutgoing(
        context: Context,
        destinationAddress: String,
        body: String,
        timestampOverride: Int? = null,
        messageIdOverride: Long? = null,
        attachmentPayloads: List<LxmfAttachmentPayload> = emptyList()
    ): Long {
        val normalized = LxmfAddress.normalize(destinationAddress)
        val threadId = LxmfAddress.threadIdForAddress(normalized)
        val nowMs = System.currentTimeMillis()
        val timestamp = timestampOverride ?: (nowMs / 1000L).toInt()
        val messageId = messageIdOverride ?: LxmfAddress.messageIdForHash(
            normalized.toByteArray(Charsets.UTF_8) + nowMs.toString().toByteArray()
        )
        val contact = MeshContactHelper.getSimpleContactForMeshAddress(context, normalized)
        val participant = contact ?: buildMeshParticipant(normalized)
        val participants = arrayListOf(participant)

        ensureBackgroundThread {
            val attachment = persistMeshAttachments(context, messageId, attachmentPayloads)
            val messageModel = Message(
                id = messageId,
                body = body,
                type = Telephony.Sms.MESSAGE_TYPE_SENT,
                status = Telephony.Sms.STATUS_PENDING,
                participants = participants,
                date = timestamp,
                read = true,
                threadId = threadId,
                isMMS = false,
                attachment = attachment,
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
        return messageId
    }

    fun markDelivered(context: Context, messageId: Long) {
        ensureBackgroundThread {
            context.messagesDB.updateStatus(messageId, Telephony.Sms.STATUS_COMPLETE)
            refreshMessages()
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

    fun backfillMissingConversations(context: Context) {
        val latestByThread = try {
            context.messagesDB.getLatestMeshMessages()
                .sortedByDescending { it.date }
                .distinctBy { it.threadId }
        } catch (_: Exception) {
            emptyList()
        }
        if (latestByThread.isEmpty()) return

        latestByThread.forEach { message ->
            val threadId = message.threadId
            if (!LxmfAddress.isMeshThreadId(threadId)) {
                return@forEach
            }

            val meshAddress = resolveMeshAddressFromMessage(message) ?: return@forEach
            val existing = context.conversationsDB.getConversationWithThreadId(threadId)
            if (existing != null) {
                val shouldUnarchiveConversation = context.shouldUnarchive()
                val normalizedAddress = LxmfAddress.normalize(meshAddress)
                val updated = existing.copy(
                    snippet = if (message.date >= existing.date) message.body else existing.snippet,
                    date = maxOf(existing.date, message.date),
                    read = existing.read && message.read,
                    phoneNumber = if (LxmfAddress.isMeshLike(existing.phoneNumber)) {
                        existing.phoneNumber
                    } else {
                        normalizedAddress
                    },
                    isArchived = if (shouldUnarchiveConversation) false else existing.isArchived,
                    unreadCount = when {
                        message.read -> existing.unreadCount
                        existing.unreadCount > 0 -> existing.unreadCount
                        else -> 1
                    }
                )
                if (updated != existing) {
                    context.conversationsDB.insertOrUpdate(updated)
                }
                return@forEach
            }

            val participant = MeshContactHelper.getSimpleContactForMeshAddress(context, meshAddress)
                ?: buildMeshParticipant(meshAddress)

            val conversation = Conversation(
                threadId = threadId,
                snippet = message.body,
                date = message.date,
                read = message.read,
                title = participant.name,
                photoUri = participant.photoUri,
                isGroupConversation = false,
                phoneNumber = meshAddress,
                isScheduled = false,
                usesCustomTitle = false,
                isArchived = false,
                unreadCount = if (message.read) 0 else 1,
            )
            context.conversationsDB.insertOrUpdate(conversation)
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
        val shouldUnarchiveConversation = context.shouldUnarchive()
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
            isArchived = if (shouldUnarchiveConversation) false else (existing?.isArchived ?: false),
            unreadCount = if (read) 0 else unreadCount
        )
        context.conversationsDB.insertOrUpdate(conversation)
    }

    private fun resolveMeshAddressFromMessage(message: Message): String? {
        val sender = message.senderPhoneNumber
            .takeIf { LxmfAddress.isMeshLike(it) }
            ?.let { LxmfAddress.normalize(it) }
            ?.takeIf { LxmfAddress.isMeshAddress(it) }
        if (sender != null) {
            return sender
        }

        val participantAddress = message.participants
            .asSequence()
            .flatMap { it.phoneNumbers.asSequence() }
            .map { it.value }
            .firstOrNull { LxmfAddress.isMeshLike(it) }
            ?.let { LxmfAddress.normalize(it) }
            ?.takeIf { LxmfAddress.isMeshAddress(it) }
        if (participantAddress != null) {
            return participantAddress
        }

        return null
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

    private fun persistMeshAttachments(
        context: Context,
        messageId: Long,
        attachments: List<LxmfAttachmentPayload>
    ): org.fossify.messages.models.MessageAttachment? {
        if (attachments.isEmpty()) return null
        val dir = File(context.filesDir, "mesh_attachments")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val stored = ArrayList<org.fossify.messages.models.Attachment>()
        attachments.forEachIndexed { index, payload ->
            try {
                // Attachment filenames come from untrusted peers. Keep them in-bounds and filesystem-safe.
                val baseName = payload.filename.ifBlank { "attachment_$index" }
                val safeBaseName = baseName
                    .replace(Regex("[\\\\/]+"), "_")
                    .replace("..", "_")
                    .trim()
                    .ifBlank { "attachment_$index" }
                val extension = MimeTypeMap.getSingleton()
                    .getExtensionFromMimeType(payload.mimeType)
                    ?.takeIf { it.isNotBlank() }
                val fileName = if (extension != null && !safeBaseName.endsWith(".$extension")) {
                    "${messageId}_${index}_$safeBaseName.$extension"
                } else {
                    "${messageId}_${index}_$safeBaseName"
                }
                val file = File(dir, fileName)
                file.outputStream().use { it.write(payload.data) }
                val normalizedMime = if (payload.mimeType.lowercase().startsWith("image") && looksLikeGif(payload)) {
                    "image/gif"
                } else {
                    payload.mimeType
                }
                stored.add(
                    org.fossify.messages.models.Attachment(
                        id = null,
                        messageId = messageId,
                        // Use FileProvider so tapping the attachment does not crash with FileUriExposedException.
                        uriString = context.getMyFileUri(file).toString(),
                        mimetype = normalizedMime,
                        width = 0,
                        height = 0,
                        filename = safeBaseName
                    )
                )
            } catch (_: Exception) {
            }
        }
        if (stored.isEmpty()) return null
        return org.fossify.messages.models.MessageAttachment(messageId, "", stored)
    }
}
