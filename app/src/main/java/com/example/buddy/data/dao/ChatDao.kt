package com.example.buddy.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.buddy.data.entity.ChatMessage
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessage): Long

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC, id ASC")
    fun observeSessionMessages(sessionId: String): Flow<List<ChatMessage>>

    @Query(
        """
        SELECT * FROM chat_messages
        WHERE sessionId = :sessionId
          AND role IN ('user', 'assistant', 'tool_result')
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentConversationMessages(sessionId: String, limit: Int = 20): List<ChatMessage>

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId AND role = 'thinking'")
    suspend fun clearThinkingBubbles(sessionId: String)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: String)
}
