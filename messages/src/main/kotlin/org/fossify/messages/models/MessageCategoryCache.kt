package org.fossify.messages.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "message_category_cache")
data class MessageCategoryCache(
    @PrimaryKey
    @ColumnInfo(name = "thread_id")
    val threadId: Long,
    @ColumnInfo(name = "category")
    val category: Int,
    @ColumnInfo(name = "is_blocked")
    val isBlocked: Int,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
