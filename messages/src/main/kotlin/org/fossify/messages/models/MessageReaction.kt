package org.fossify.messages.models

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "message_reactions",
    primaryKeys = ["message_id", "sender"]
)
data class MessageReaction(
    @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "thread_id") val threadId: Long,
    @ColumnInfo(name = "sender") val sender: String,
    @ColumnInfo(name = "emoji") val emoji: String,
    @ColumnInfo(name = "is_mine") val isMine: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
