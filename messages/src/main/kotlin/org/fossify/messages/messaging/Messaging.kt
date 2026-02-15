package org.fossify.messages.messaging

import android.content.Context
import android.telephony.SmsMessage
import android.util.Patterns
import android.widget.Toast.LENGTH_LONG
import com.klinker.android.send_message.Settings
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.getThreadId
import org.fossify.messages.extensions.messagingUtils
import org.fossify.messages.extensions.shortcutHelper
import org.fossify.messages.helpers.WalletTokenParser
import org.fossify.messages.helpers.E2eManager
import org.fossify.messages.messaging.SmsException.Companion.EMPTY_DESTINATION_ADDRESS
import org.fossify.messages.messaging.SmsException.Companion.ERROR_PERSISTING_MESSAGE
import org.fossify.messages.messaging.SmsException.Companion.ERROR_SENDING_MESSAGE
import org.fossify.messages.models.Attachment
import org.fossify.mesh.lxmf.LxmfAddress

@Deprecated("TODO: Move/rewrite messaging config code into the app.")
fun Context.getSendMessageSettings(): Settings {
    val settings = Settings()
    settings.useSystemSending = true
    settings.deliveryReports = config.enableDeliveryReports
    settings.sendLongAsMms = config.sendLongMessageMMS
    settings.sendLongAsMmsAfter = 1
    settings.group = config.sendGroupMessageMMS
    return settings
}

@Suppress("DEPRECATION")
fun Context.isLongMmsMessage(text: String, settings: Settings = getSendMessageSettings()): Boolean {
    val data = SmsMessage.calculateLength(text, false)
    val numPages = data.first()
    return numPages > settings.sendLongAsMmsAfter && settings.sendLongAsMms
}

/** Sends the message using the in-app SmsManager API wrappers if it's an SMS or using android-smsmms for MMS. */
@Suppress("DEPRECATION")
fun Context.sendMessageCompat(
    text: String,
    addresses: List<String>,
    subId: Int?,
    attachments: List<Attachment>,
    messageId: Long? = null
) {
    val maxLinkSmsParts = 6
    // Fedimint ecash tokens can be long and are frequently blocked/broken when converted to
    // "text-only MMS". Prefer segmented SMS for a bounded size to keep delivery reliable.
    val maxWalletTokenSmsParts = 12
    val cleanAddresses = addresses.filterNot { LxmfAddress.isMeshLike(it) }
    if (cleanAddresses.isEmpty()) {
        toast(id = R.string.mesh_disabled, length = LENGTH_LONG)
        return
    }
    val threadId = getThreadId(cleanAddresses.toSet())
    val canEncrypt = cleanAddresses.none { LxmfAddress.isMeshAddress(it) }
    val encryptedText = if (canEncrypt) E2eManager.encryptOutgoing(this, threadId, text) else null
    val outgoingText = encryptedText ?: text
    val settings = getSendMessageSettings()
    if (subId != null) {
        settings.subscriptionId = subId
    }

    val messagingUtils = messagingUtils
    // Some carriers/devices are unreliable with "text-only MMS" conversions, especially when the
    // message contains URLs (common for link shares). Prefer segmented SMS for URL-containing text.
    val smsParts = SmsMessage.calculateLength(outgoingText, false).first()
    val forceSmsForLink = attachments.isEmpty() &&
        cleanAddresses.size == 1 &&
        smsParts <= maxLinkSmsParts &&
        Patterns.WEB_URL.matcher(outgoingText).find()

    // If the thread is E2E-encrypted, outgoingText will not contain CPFM1 plaintext anymore.
    // Detect tokens in the original text, but apply the size check on the actual outgoing payload.
    val forceSmsForWalletToken = attachments.isEmpty() &&
        cleanAddresses.size == 1 &&
        smsParts <= maxWalletTokenSmsParts &&
        WalletTokenParser.parseFedimintEcashToken(text) != null

    val isMms = !forceSmsForLink && !forceSmsForWalletToken && (
        attachments.isNotEmpty() ||
            isLongMmsMessage(outgoingText, settings) ||
            cleanAddresses.size > 1 && settings.group
        )
    if (isMms) {
        // we send all MMS attachments separately to reduces the chances of hitting provider MMS limit.
        if (attachments.isNotEmpty()) {
            val lastIndex = attachments.lastIndex
            if (attachments.size > 1) {
                for (i in 0 until lastIndex) {
                    val attachment = attachments[i]
                    messagingUtils.sendMmsMessage("", cleanAddresses, attachment, settings, messageId)
                }
            }

            val lastAttachment = attachments[lastIndex]
            messagingUtils.sendMmsMessage(outgoingText, cleanAddresses, lastAttachment, settings, messageId)
        } else {
            messagingUtils.sendMmsMessage(outgoingText, cleanAddresses, null, settings, messageId)
        }
    } else {
        try {
            messagingUtils.sendSmsMessage(
                text = outgoingText,
                addresses = cleanAddresses.toSet(),
                subId = settings.subscriptionId,
                requireDeliveryReport = settings.deliveryReports,
                messageId = messageId
            )
        } catch (e: SmsException) {
            when (e.errorCode) {
                EMPTY_DESTINATION_ADDRESS -> toast(
                    id = R.string.empty_destination_address,
                    length = LENGTH_LONG
                )

                ERROR_PERSISTING_MESSAGE -> toast(
                    id = R.string.unable_to_save_message,
                    length = LENGTH_LONG
                )

                ERROR_SENDING_MESSAGE -> toast(
                    msg = getString(R.string.unknown_error_occurred_sending_message, e.errorCode),
                    length = LENGTH_LONG
                )
            }
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }
    ensureBackgroundThread {
        shortcutHelper.reportSendMessageUsage(threadId)
    }
}

/**
 * Check if a given "address" is a short code.
 * There's not much info available on these special numbers, even the wikipedia page (https://en.wikipedia.org/wiki/Short_code)
 * contains outdated information regarding max number of digits. The exact parameters for short codes can vary by country and by carrier.
 */
fun isShortCodeWithLetters(address: String): Boolean {
    if (LxmfAddress.isMeshAddress(address)) {
        return false
    }
    if (Patterns.EMAIL_ADDRESS.matcher(address).matches()) {
        // emails are not short codes: https://github.com/FossifyOrg/Messages/issues/115
        return false
    }

    return address.any { it.isLetter() }
}

fun isShortCode(address: String): Boolean {
    if (isShortCodeWithLetters(address)) {
        return true
    }
    if (LxmfAddress.isMeshAddress(address)) {
        return false
    }
    if (Patterns.EMAIL_ADDRESS.matcher(address).matches()) {
        return false
    }
    val trimmed = address.trim()
    if (trimmed.isEmpty() || trimmed.any { !it.isDigit() }) {
        return false
    }
    return trimmed.length in 3..6
}
