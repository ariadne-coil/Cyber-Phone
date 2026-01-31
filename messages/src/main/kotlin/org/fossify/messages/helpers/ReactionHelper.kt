package org.fossify.messages.helpers

import android.content.Context
import org.fossify.messages.extensions.messageReactionsDB
import org.fossify.messages.models.Message
import org.fossify.messages.models.MessageReaction

enum class ReactionType {
    LIKE,
    LOVE,
    LAUGH,
    EMPHASIZE,
    QUESTION,
    DISLIKE
}

data class Tapback(
    val type: ReactionType,
    val targetText: String,
    val isRemoval: Boolean
)

data class PendingTapback(
    val tapback: Tapback,
    val isMine: Boolean,
    val sender: String
)

object ReactionHelper {
    const val SENDER_ME = "__me__"

    private val actionToEmoji = mapOf(
        ReactionType.LIKE to "👍",
        ReactionType.LOVE to "❤️",
        ReactionType.LAUGH to "😂",
        ReactionType.EMPHASIZE to "‼️",
        ReactionType.QUESTION to "❓",
        ReactionType.DISLIKE to "👎"
    )

    private val actionToPhrase = mapOf(
        ReactionType.LIKE to "Liked",
        ReactionType.LOVE to "Loved",
        ReactionType.LAUGH to "Laughed at",
        ReactionType.EMPHASIZE to "Emphasized",
        ReactionType.QUESTION to "Questioned",
        ReactionType.DISLIKE to "Disliked"
    )

    fun emojiFor(type: ReactionType): String = actionToEmoji[type] ?: ""

    fun buildTapback(type: ReactionType, text: String): String {
        val phrase = actionToPhrase[type] ?: "Liked"
        return "$phrase “$text”"
    }

    fun parseTapback(body: String): Tapback? {
        val trimmed = body.trim()
        val quoted = Regex("^[“\\\"](.+)[”\\\"]$")
        val mainRegex = Regex("^(Liked|Loved|Laughed at|Emphasized|Questioned|Disliked)\\s+[“\\\"](.+)[”\\\"]$",
            RegexOption.IGNORE_CASE)
        val removalRegex = Regex(
            "^Removed\\s+a\\s+(like|heart|laugh|emphasis|question|dislike)\\s+from\\s+[“\\\"](.+)[”\\\"]$",
            RegexOption.IGNORE_CASE
        )

        mainRegex.find(trimmed)?.let { match ->
            val action = match.groupValues[1].lowercase()
            val text = match.groupValues[2]
            return Tapback(actionToType(action), text, false)
        }

        removalRegex.find(trimmed)?.let { match ->
            val action = match.groupValues[1].lowercase()
            val text = match.groupValues[2]
            return Tapback(actionToType(action), text, true)
        }

        quoted.find(trimmed)?.let { match ->
            if (match.groupValues[1].isNotEmpty()) return null
        }

        return null
    }

    private fun actionToType(action: String): ReactionType {
        return when (action) {
            "liked", "like" -> ReactionType.LIKE
            "loved", "heart" -> ReactionType.LOVE
            "laughed at", "laugh" -> ReactionType.LAUGH
            "emphasized", "emphasis" -> ReactionType.EMPHASIZE
            "questioned", "question" -> ReactionType.QUESTION
            "disliked", "dislike" -> ReactionType.DISLIKE
            else -> ReactionType.LIKE
        }
    }

    fun applyTapback(
        context: Context,
        targetMessageId: Long,
        threadId: Long,
        sender: String,
        isMine: Boolean,
        type: ReactionType,
        isRemoval: Boolean
    ) {
        if (isRemoval) {
            context.messageReactionsDB.delete(targetMessageId, sender)
            return
        }
        val reaction = MessageReaction(
            messageId = targetMessageId,
            threadId = threadId,
            sender = sender,
            emoji = emojiFor(type),
            isMine = if (isMine) 1 else 0,
            updatedAt = System.currentTimeMillis()
        )
        context.messageReactionsDB.insert(reaction)
    }

    fun findTargetMessage(
        messages: List<Message>,
        targetText: String,
        shouldBeReceived: Boolean
    ): Message? {
        return messages.asReversed().firstOrNull { message ->
            message.body == targetText && message.isReceivedMessage() == shouldBeReceived
        }
    }
}
