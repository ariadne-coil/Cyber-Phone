package org.fossify.messages.receivers

import android.content.Context
import android.net.Uri
import com.bumptech.glide.Glide
import com.klinker.android.send_message.MmsReceivedReceiver
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.extensions.getConversations
import org.fossify.messages.extensions.getLatestMMS
import org.fossify.messages.extensions.getMessages
import org.fossify.messages.extensions.getNameFromAddress
import org.fossify.messages.extensions.insertOrUpdateConversation
import org.fossify.messages.extensions.markMessageRead
import org.fossify.messages.extensions.messageCategoryCacheDB
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.shouldUnarchive
import org.fossify.messages.extensions.showReceivedMessageNotification
import org.fossify.messages.extensions.updateConversationArchivedStatus
import org.fossify.messages.helpers.E2eManager
import org.fossify.messages.helpers.MeshDiscoveryManager
import org.fossify.messages.helpers.MessageCategorizer
import org.fossify.messages.helpers.ReactionHelper
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.models.Message
import org.fossify.messages.models.MessageCategoryCache

class MmsReceiver : MmsReceivedReceiver() {

    override fun isAddressBlocked(context: Context, address: String): Boolean {
        return false
    }

    override fun isContentBlocked(context: Context, content: String): Boolean {
        return false
    }

    override fun onMessageReceived(context: Context, messageUri: Uri) {
        val mms = context.getLatestMMS() ?: return
        val address = mms.getSender()?.phoneNumbers?.firstOrNull()?.normalizedNumber ?: ""
        val size = context.resources.getDimension(R.dimen.notification_large_icon_size).toInt()
        ensureBackgroundThread {
            handleMmsMessage(context, mms, size, address)
        }
    }

    override fun onError(context: Context, error: String) {
        context.showErrorToast(context.getString(R.string.couldnt_download_mms))
    }

    private fun handleMmsMessage(
        context: Context,
        mms: Message,
        size: Int,
        address: String
    ) {
        val glideBitmap = try {
            Glide.with(context)
                .asBitmap()
                .load(mms.attachment!!.attachments.first().getUri())
                .centerCrop()
                .submit(size, size)
                .get()
        } catch (e: Exception) {
            null
        }


        val isKnownContact = isKnownContactOrLookupUnavailable(context, address)
        val senderName = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true).use {
            context.getNameFromAddress(address, it)
        }

        val displayResult = E2eManager.getDisplayResult(context, mms.threadId, mms.body, mms.date.toLong())
        val displayBody = displayResult.body
        MeshDiscoveryManager.handleIncomingMeshAddress(
            context = context,
            address = address,
            threadId = mms.threadId,
            text = mms.body,
            subscriptionId = -1,
            allowAutoReply = true
        )
        val tapback = ReactionHelper.parseTapback(displayBody)
        if (tapback != null) {
            val sender = address
            val target = ReactionHelper.findTargetMessage(context.getMessages(mms.threadId), tapback.targetText, false)
            if (target != null) {
                ReactionHelper.applyTapback(
                    context = context,
                    targetMessageId = target.id,
                    threadId = target.threadId,
                    sender = sender,
                    isMine = false,
                    type = tapback.type,
                    isRemoval = tapback.isRemoval
                )
            }
            context.markMessageRead(mms.id, isMMS = true)
            refreshMessages()
            refreshConversations()
            return
        }
        val isBlocked = MessageCategorizer.isBlockedMessage(context, address, displayBody, isKnownContact)
        val category = MessageCategorizer.categorizeMessage(context, address, displayBody, isKnownContact, isBlocked)
        if (!isBlocked && category != org.fossify.messages.helpers.MessageCategory.SPAM) {
            context.showReceivedMessageNotification(
                messageId = mms.id,
                address = address,
                senderName = senderName,
                body = displayBody,
                threadId = mms.threadId,
                bitmap = glideBitmap
            )
        }
        ensureBackgroundThread {
            val categoryId = when (category) {
                org.fossify.messages.helpers.MessageCategory.MAIN -> 0
                org.fossify.messages.helpers.MessageCategory.OTP -> 1
                org.fossify.messages.helpers.MessageCategory.SPAM -> 2
            }
            val entry = MessageCategoryCache(
                threadId = mms.threadId,
                category = categoryId,
                isBlocked = if (isBlocked) 1 else 0,
                updatedAt = System.currentTimeMillis()
            )
            try {
                context.messageCategoryCacheDB.insert(entry)
            } catch (_: Exception) {
            }
        }
        if (isBlocked) {
            context.markMessageRead(mms.id, isMMS = true)
        }
        if (displayResult.shouldPersist && displayBody != mms.body) {
            context.messagesDB.updateBody(mms.id, displayBody)
        }

        val conversation = context.getConversations(mms.threadId).firstOrNull() ?: return
        runCatching { context.insertOrUpdateConversation(conversation) }
        if (context.shouldUnarchive()) {
            context.updateConversationArchivedStatus(mms.threadId, false)
        }
        refreshMessages()
        refreshConversations()
    }

    private fun isKnownContactOrLookupUnavailable(context: Context, address: String): Boolean {
        val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        val lookupResult = SimpleContactsHelper(context).existsSync(address, privateCursor)
        return lookupResult != ContactLookupResult.NotFound
    }
}
