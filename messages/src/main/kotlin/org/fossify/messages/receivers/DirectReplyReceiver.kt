package org.fossify.messages.receivers

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.RemoteInput
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.extensions.*
import org.fossify.messages.helpers.REPLY
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.helpers.THREAD_NUMBER
import org.fossify.messages.messaging.sendMessageCompat
import org.fossify.mesh.MeshConfig
import org.fossify.mesh.MeshMode
import org.fossify.mesh.MeshContactHelper
import org.fossify.mesh.lxmf.LxmfAddress
import org.fossify.mesh.lxmf.LxmfRouter
import org.fossify.mesh.lxmf.LxmfStore

class DirectReplyReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val address = intent.getStringExtra(THREAD_NUMBER)
        val threadId = intent.getLongExtra(THREAD_ID, 0L)
        var body = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REPLY)?.toString() ?: return

        body = context.removeDiacriticsIfNeeded(body)

        if (address != null) {
            if (LxmfAddress.isMeshAddress(address)) {
                ensureBackgroundThread {
                    val destinationHash = LxmfAddress.decode(address)
                    val meshMode = MeshConfig.newInstance(context).getMeshMode()
                    val sent = destinationHash != null &&
                        meshMode != MeshMode.STANDARD_ONLY &&
                        LxmfRouter.sendText(destinationHash, body)

                    if (sent) {
                        LxmfStore.storeOutgoing(context, address, body)
                    }

                    val photoUri = MeshContactHelper.getContactNameAndPhotoForMeshAddress(context, address).second
                    val bitmap = context.getNotificationBitmap(photoUri.orEmpty())
                    Handler(Looper.getMainLooper()).post {
                        context.notificationHelper.showMessageNotification(
                            messageId = 0L,
                            address = address,
                            body = body,
                            threadId = threadId,
                            bitmap = bitmap,
                            sender = null,
                            alertOnlyOnce = true
                        )
                    }

                    context.markThreadMessagesRead(threadId)
                    context.conversationsDB.markRead(threadId)
                }
                return
            }

            var subscriptionId: Int? = null
            val availableSIMs = context.subscriptionManagerCompat().activeSubscriptionInfoList
            if ((availableSIMs?.size ?: 0) > 1) {
                val currentSIMCardIndex = context.config.getUseSIMIdAtNumber(address)
                val wantedId = availableSIMs?.getOrNull(currentSIMCardIndex)
                if (wantedId != null) {
                    subscriptionId = wantedId.subscriptionId
                }
            }

            ensureBackgroundThread {
                var messageId = 0L
                try {
                    context.sendMessageCompat(body, listOf(address), subscriptionId, emptyList())
                    val message = context.getMessages(
                        threadId = threadId, includeScheduledMessages = false, limit = 1
                    ).lastOrNull()
                    if (message != null) {
                        context.messagesDB.insertOrUpdate(message)
                        messageId = message.id

                        context.updateLastConversationMessage(threadId)
                    }
                } catch (e: Exception) {
                    context.showErrorToast(e)
                }

                val photoUri = SimpleContactsHelper(context).getPhotoUriFromPhoneNumber(address)
                val bitmap = context.getNotificationBitmap(photoUri)
                Handler(Looper.getMainLooper()).post {
                    context.notificationHelper.showMessageNotification(
                        messageId = messageId,
                        address = address,
                        body = body,
                        threadId = threadId,
                        bitmap = bitmap,
                        sender = null,
                        alertOnlyOnce = true
                    )
                }

                context.markThreadMessagesRead(threadId)
                context.conversationsDB.markRead(threadId)
            }
        }
    }
}
