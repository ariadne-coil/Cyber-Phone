package org.fossify.messages.helpers

import android.content.Context
import android.util.Patterns
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.extensions.normalizePhoneNumber
import org.fossify.messages.extensions.config as messagesConfig
import org.fossify.messages.models.Conversation
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

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
    private val yacbLoaded = AtomicBoolean(false)
    private var yacbInstance: Any? = null
    private var yacbInit: java.lang.reflect.Method? = null
    private var yacbGetRating: java.lang.reflect.Method? = null
    private var yacbGetRatingCounts: java.lang.reflect.Method? = null

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

    fun isSpamHeuristic(body: String, isKnownContact: Boolean): Boolean {
        if (isKnownContact || body.isBlank()) {
            return false
        }
        val normalized = body.lowercase(Locale.getDefault())
        val hasSpamKeyword = spamKeywords.any { normalized.contains(it) }
        val hasUrl = Patterns.WEB_URL.matcher(body).find()
        return hasSpamKeyword || hasUrl
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
        return if (isSpamHeuristic(body, isKnownContact)) {
            MessageCategory.SPAM
        } else {
            MessageCategory.MAIN
        }
    }

    private fun getYacbRating(context: Context, number: String): String? {
        if (!yacbLoaded.get()) {
            synchronized(this) {
                if (!yacbLoaded.get()) {
                    try {
                        val clazz = Class.forName("org.fossify.phone.blocking.YacbSiaManager")
                        yacbInstance = clazz.getField("INSTANCE").get(null)
                        yacbInit = runCatching { clazz.getMethod("init", Context::class.java) }.getOrNull()
                        yacbGetRating = clazz.getMethod("getRating", String::class.java)
                        yacbGetRatingCounts =
                            runCatching { clazz.getMethod("getRatingCounts", String::class.java) }.getOrNull()
                    } catch (_: Exception) {
                        yacbInstance = null
                        yacbInit = null
                        yacbGetRating = null
                        yacbGetRatingCounts = null
                    } finally {
                        yacbLoaded.set(true)
                    }
                }
            }
        }

        val instance = yacbInstance ?: return null
        try {
            yacbInit?.invoke(instance, context.applicationContext)
            val rating = yacbGetRating?.invoke(instance, number) ?: return null
            return rating.toString()
        } catch (_: Exception) {
            return null
        }
    }

    private fun getYacbRatingCounts(context: Context, number: String): IntArray? {
        if (!yacbLoaded.get()) {
            getYacbRating(context, number)
        }
        val instance = yacbInstance ?: return null
        val method = yacbGetRatingCounts ?: return null
        return try {
            yacbInit?.invoke(instance, context.applicationContext)
            method.invoke(instance, number) as? IntArray
        } catch (_: Exception) {
            null
        }
    }

    private data class ReputationThreshold(val minNegativeRatio: Double, val minVotes: Int)

    private fun getReputationThreshold(context: Context): ReputationThreshold {
        return when (context.messagesConfig.spamReputationThreshold) {
            SPAM_REPUTATION_AGGRESSIVE -> ReputationThreshold(minNegativeRatio = 0.4, minVotes = 3)
            SPAM_REPUTATION_CONSERVATIVE -> ReputationThreshold(minNegativeRatio = 0.7, minVotes = 5)
            SPAM_REPUTATION_VERY_CONSERVATIVE -> ReputationThreshold(minNegativeRatio = 0.85, minVotes = 10)
            else -> ReputationThreshold(minNegativeRatio = 0.55, minVotes = 5)
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
        if (!isKnownContact && isSpamHeuristic(body, isKnownContact)) {
            return true
        }
        val normalizedNumber = address.normalizePhoneNumber().trim()
        if (!isKnownContact && normalizedNumber.isNotEmpty()) {
            val counts = getYacbRatingCounts(context, normalizedNumber)
            if (counts != null && counts.size >= 3) {
                val negative = counts[0]
                val positive = counts[1]
                val neutral = counts[2]
                val total = negative + positive + neutral
                if (total > 0) {
                    val threshold = getReputationThreshold(context)
                    if (total >= threshold.minVotes) {
                        val ratio = negative.toDouble() / total.toDouble()
                        if (ratio >= threshold.minNegativeRatio) {
                            return true
                        }
                    }
                }
            } else {
                val rating = getYacbRating(context, normalizedNumber)
                if (rating == "NEGATIVE") {
                    return true
                }
            }
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
