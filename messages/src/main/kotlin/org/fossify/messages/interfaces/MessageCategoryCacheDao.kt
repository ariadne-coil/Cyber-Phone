package org.fossify.messages.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.fossify.messages.models.MessageCategoryCache

@Dao
interface MessageCategoryCacheDao {
    @Query("SELECT * FROM message_category_cache")
    fun getAll(): List<MessageCategoryCache>

    @Query("SELECT * FROM message_category_cache WHERE thread_id IN (:threadIds)")
    fun getByThreadIds(threadIds: List<Long>): List<MessageCategoryCache>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(item: MessageCategoryCache)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(items: List<MessageCategoryCache>)

    @Query("DELETE FROM message_category_cache")
    fun deleteAll()

    @Query("DELETE FROM message_category_cache WHERE thread_id NOT IN (:threadIds)")
    fun deleteMissingThreadIds(threadIds: List<Long>)
}
