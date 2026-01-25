package org.fossify.messages.helpers

import android.content.Context
import android.util.Patterns
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.messages.models.Conversation
import java.util.Locale

enum class MessageCategory {
    MAIN,
    OTP,
    SPAM
}

data class MessageClassification(
    val category: MessageCategory,
    val isBlocked: Boolean
)

object MessageCategorizer {
    private val otpKeywordRegex =
        Regex("\\b(otp|one[- ]time|verification|verif|code|passcode|pin|security code|login code)\\b", RegexOption.IGNORE_CASE)
    private val otpCodeRegex = Regex("\\b\\d{4,8}\\b")
    private val otpSpacedCodeRegex = Regex("\\b\\d{3}[ -]\\d{3}\\b")
    private val spamKeywords = listOf(
        "winner",
        "win",
        "prize",
        "lottery",
        "claim",
        "urgent",
        "offer",
        "promo",
        "promotion",
        "free",
        "gift",
        "bonus",
        "credit",
        "loan",
        "casino",
        "bet",
        "adult",
        "unsubscribe",
        "click"
    )

    fun extractOtp(body: String): String? {
        val match = otpCodeRegex.find(body) ?: otpSpacedCodeRegex.find(body) ?: return null
        return match.value.replace(" ", "").replace("-", "")
    }

    fun isOtpMessage(body: String): Boolean {
        val code = extractOtp(body) ?: return false
        if (code.length < 4) {
            return false
        }
        return otpKeywordRegex.containsMatchIn(body)
    }

    fun categorizeMessage(
        body: String,
        isKnownContact: Boolean,
        isBlocked: Boolean
    ): MessageCategory {
        if (isOtpMessage(body)) {
            return MessageCategory.OTP
        }
        if (isBlocked) {
            return MessageCategory.SPAM
        }
        if (body.isBlank()) {
            return MessageCategory.MAIN
        }
        val normalized = body.lowercase(Locale.getDefault())
        val hasSpamKeyword = spamKeywords.any { normalized.contains(it) }
        val hasUrl = Patterns.WEB_URL.matcher(body).find()
        return if (!isKnownContact && (hasSpamKeyword || hasUrl)) {
            MessageCategory.SPAM
        } else {
            MessageCategory.MAIN
        }
    }

    fun isBlockedMessage(
        context: Context,
        address: String,
        body: String,
        isKnownContact: Boolean
    ): Boolean {
        if (ReceiverUtils.isMessageFilteredOut(context, body)) {
            return true
        }
        if (context.isNumberBlocked(address)) {
            return true
        }
        if (context.baseConfig.blockUnknownNumbers && !isKnownContact) {
            return true
        }
        return false
    }

    fun classifyMessage(
        context: Context,
        address: String,
        body: String,
        isKnownContact: Boolean
    ): MessageClassification {
        val isBlocked = isBlockedMessage(context, address, body, isKnownContact)
        val category = categorizeMessage(body, isKnownContact, isBlocked)
        return MessageClassification(category, isBlocked)
    }

    fun classifyConversation(
        context: Context,
        conversation: Conversation
    ): MessageClassification {
        val isKnownContact =
            conversation.title.isNotBlank() && conversation.title != conversation.phoneNumber
        return classifyMessage(context, conversation.phoneNumber, conversation.snippet, isKnownContact)
    }
}
