package org.fossify.messages.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.fossify.messages.models.MessageReaction

@Dao
interface MessageReactionsDao {
    @Query("SELECT * FROM message_reactions WHERE thread_id = :threadId")
    fun getByThreadId(threadId: Long): List<MessageReaction>

    @Query("SELECT * FROM message_reactions WHERE message_id IN (:messageIds)")
    fun getByMessageIds(messageIds: List<Long>): List<MessageReaction>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(item: MessageReaction)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(items: List<MessageReaction>)

    @Query("DELETE FROM message_reactions WHERE message_id = :messageId AND sender = :sender")
    fun delete(messageId: Long, sender: String)

    @Query("DELETE FROM message_reactions WHERE thread_id = :threadId")
    fun deleteByThreadId(threadId: Long)
}
