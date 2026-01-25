package org.fossify.messages.helpers

import android.util.Patterns
import org.fossify.messages.models.Conversation
import java.util.Locale

enum class MessageCategory {
    MAIN,
    OTP,
    SPAM
}

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

    fun categorizeMessage(body: String, isKnownContact: Boolean): MessageCategory {
        if (body.isBlank()) {
            return MessageCategory.MAIN
        }
        if (isOtpMessage(body)) {
            return MessageCategory.OTP
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

    fun categorizeConversation(conversation: Conversation): MessageCategory {
        if (conversation.isGroupConversation) {
            return MessageCategory.MAIN
        }
        val isKnownContact =
            conversation.title.isNotBlank() && conversation.title != conversation.phoneNumber
        return categorizeMessage(conversation.snippet, isKnownContact)
    }
}
