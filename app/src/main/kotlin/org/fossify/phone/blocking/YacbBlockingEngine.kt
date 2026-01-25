package org.fossify.phone.blocking

import android.content.Context
import org.fossify.commons.extensions.getBlockedNumbers
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.extensions.normalizePhoneNumber
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.phone.extensions.config

// Ported from YetAnotherCallBlocker (AGPL-3.0): NumberInfoService/BlacklistUtils ideas.
class YacbBlockingEngine(private val context: Context) {
    private val blacklistService = YacbBlacklistService(context)
    private val contactsProvider = YacbContactsProvider(context)

    fun evaluateCall(number: String?, callback: (BlockDecision) -> Unit) {
        val config = context.config
        val normalizedNumber = number?.normalizePhoneNumber()?.trim().orEmpty()
        val isHidden = number.isNullOrBlank() || normalizedNumber.isBlank()

        if (isHidden) {
            callback(
                BlockDecision(
                    shouldBlock = config.blockHiddenNumbers,
                    reason = if (config.blockHiddenNumbers) BlockingReason.HIDDEN_NUMBER else null,
                    callInfo = CallInfo(number = number, rating = null, displayName = null)
                )
            )
            return
        }

        if (blacklistService.isBlacklisted(normalizedNumber)) {
            callback(
                BlockDecision(
                    shouldBlock = true,
                    reason = BlockingReason.BLACKLISTED,
                    callInfo = CallInfo(number = number, rating = null, displayName = null)
                )
            )
            return
        }

        contactsProvider.isInContacts(number) { exists ->
            if (exists) {
                callback(
                    BlockDecision(
                        shouldBlock = false,
                        reason = null,
                        callInfo = CallInfo(number = number, rating = null, displayName = null)
                    )
                )
                return@isInContacts
            }

            val rating = YacbSiaManager.getRating(normalizedNumber)
            if (config.blockNegativeRatings && rating == YacbSiaManager.Rating.NEGATIVE) {
                callback(
                    BlockDecision(
                        shouldBlock = true,
                        reason = BlockingReason.NEGATIVE_RATING,
                        callInfo = CallInfo(number = number, rating = rating, displayName = null)
                    )
                )
                return@isInContacts
            }

            if (config.blockUnknownNumbers) {
                val displayName = YacbSiaManager.getFeaturedName(normalizedNumber)
                callback(
                    BlockDecision(
                        shouldBlock = true,
                        reason = BlockingReason.UNKNOWN_NUMBER,
                        callInfo = CallInfo(number = number, rating = rating, displayName = displayName)
                    )
                )
                return@isInContacts
            }

            val displayName = YacbSiaManager.getFeaturedName(normalizedNumber)
            callback(
                BlockDecision(
                    shouldBlock = false,
                    reason = null,
                    callInfo = CallInfo(number = number, rating = rating, displayName = displayName)
                )
            )
        }
    }
}

data class BlockDecision(
    val shouldBlock: Boolean,
    val reason: BlockingReason?,
    val callInfo: CallInfo?
)

enum class BlockingReason {
    HIDDEN_NUMBER,
    NEGATIVE_RATING,
    BLACKLISTED,
    UNKNOWN_NUMBER
}

data class CallInfo(
    val number: String?,
    val rating: YacbSiaManager.Rating?,
    val displayName: String?
)

private class YacbContactsProvider(private val context: Context) {
    fun isInContacts(number: String, callback: (Boolean) -> Unit) {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) {
            callback(false)
            return
        }

        val simpleContactsHelper = SimpleContactsHelper(context)
        val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        simpleContactsHelper.exists(number, privateCursor) { exists ->
            callback(exists)
        }
    }
}

private class YacbBlacklistService(private val context: Context) {
    fun isBlacklisted(number: String): Boolean {
        val blockedNumbers = context.getBlockedNumbers()
        if (blockedNumbers.isEmpty()) {
            return false
        }

        return context.isNumberBlocked(number, blockedNumbers)
    }
}
