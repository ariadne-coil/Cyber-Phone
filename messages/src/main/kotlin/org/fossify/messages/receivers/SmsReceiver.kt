package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.fossify.messages.extensions.getConversations
import org.fossify.messages.extensions.getNameFromAddress
import org.fossify.messages.extensions.getNotificationBitmap
import org.fossify.messages.extensions.getThreadId
import org.fossify.messages.extensions.insertNewSMS
import org.fossify.messages.extensions.insertOrUpdateConversation
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.messageCategoryCacheDB
import org.fossify.messages.extensions.shouldUnarchive
import org.fossify.messages.extensions.showReceivedMessageNotification
import org.fossify.messages.extensions.updateConversationArchivedStatus
import org.fossify.messages.helpers.E2eManager
import org.fossify.messages.helpers.MessageCategorizer
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.models.Message
import org.fossify.messages.models.MessageCategoryCache

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext

        ensureBackgroundThread {
            try {
                val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (parts.isEmpty()) return@ensureBackgroundThread

                // this is how it has always worked, but need to revisit this.
                val address = parts.last().originatingAddress.orEmpty()
                if (address.isBlank()) return@ensureBackgroundThread
                val subject = parts.last().pseudoSubject.orEmpty()
                val status = parts.last().status
                val body = buildString { parts.forEach { append(it.messageBody.orEmpty()) } }

                val subscriptionId = intent.getIntExtra("subscription", -1)
                val date = System.currentTimeMillis()
                val threadId = appContext.getThreadId(address)

                E2eManager.handleIncomingKeyExchange(
                    context = appContext,
                    threadId = threadId,
                    address = address,
                    text = body,
                    receivedAtMillis = date,
                    subscriptionId = subscriptionId
                )

                handleMessageSync(
                    context = appContext,
                    address = address,
                    subject = subject,
                    body = body,
                    date = date,
                    threadId = threadId,
                    subscriptionId = subscriptionId,
                    status = status
                )
            } finally {
                pending.finish()
            }
        }
    }

    private fun handleMessageSync(
        context: Context,
        address: String,
        subject: String,
        body: String,
        date: Long,
        threadId: Long,
        type: Int = Telephony.Sms.MESSAGE_TYPE_INBOX,
        subscriptionId: Int,
        status: Int
    ) {
        val photoUri = SimpleContactsHelper(context).getPhotoUriFromPhoneNumber(address)
        val bitmap = context.getNotificationBitmap(photoUri)

        val senderName = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true).use {
            context.getNameFromAddress(address, it)
        }
        val isKnownContact = senderName != address
        val displayResult = E2eManager.getDisplayResult(context, threadId, body, date / 1000L)
        val displayBody = displayResult.body
        val isBlocked = MessageCategorizer.isBlockedMessage(context, address, displayBody, isKnownContact)
        val readFlag = if (isBlocked) 1 else 0

        val newMessageId = context.insertNewSMS(
            address = address,
            subject = subject,
            body = body,
            date = date,
            read = readFlag,
            threadId = threadId,
            type = type,
            subscriptionId = subscriptionId
        )

        context.getConversations(threadId).firstOrNull()?.let { conv ->
            runCatching { context.insertOrUpdateConversation(conv) }
        }

        val participant = SimpleContact(
            rawId = 0,
            contactId = 0,
            name = senderName,
            photoUri = photoUri,
            phoneNumbers = arrayListOf(PhoneNumber(value = address, type = 0, label = "", normalizedNumber = address)),
            birthdays = ArrayList(),
            anniversaries = ArrayList()
        )

        val message = Message(
            id = newMessageId,
            body = body,
            type = type,
            status = status,
            participants = arrayListOf(participant),
            date = (date / 1000).toInt(),
            read = readFlag == 1,
            threadId = threadId,
            isMMS = false,
            attachment = null,
            senderPhoneNumber = address,
            senderName = senderName,
            senderPhotoUri = photoUri,
            subscriptionId = subscriptionId
        )

        context.messagesDB.insertOrUpdate(message)
        if (displayResult.shouldPersist && displayBody != body) {
            context.messagesDB.updateBody(newMessageId, displayBody)
        }

        if (context.shouldUnarchive()) {
            context.updateConversationArchivedStatus(threadId, false)
        }

        refreshMessages()
        refreshConversations()
        val category = MessageCategorizer.categorizeMessage(context, address, displayBody, isKnownContact, isBlocked)
        ensureBackgroundThread {
            val categoryId = when (category) {
                org.fossify.messages.helpers.MessageCategory.MAIN -> 0
                org.fossify.messages.helpers.MessageCategory.OTP -> 1
                org.fossify.messages.helpers.MessageCategory.SPAM -> 2
            }
            val entry = MessageCategoryCache(
                threadId = threadId,
                category = categoryId,
                isBlocked = if (isBlocked) 1 else 0,
                updatedAt = System.currentTimeMillis()
            )
            try {
                context.messageCategoryCacheDB.insert(entry)
            } catch (_: Exception) {
            }
        }
        if (!isBlocked && category != org.fossify.messages.helpers.MessageCategory.SPAM) {
            context.showReceivedMessageNotification(
                messageId = newMessageId,
                address = address,
                senderName = senderName,
                body = displayBody,
                threadId = threadId,
                bitmap = bitmap
            )
        }
    }
}
