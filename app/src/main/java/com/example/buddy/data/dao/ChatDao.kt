package com.example.buddy.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.buddy.data.entity.ChatMessage
import com.example.buddy.data.model.SessionRow
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

    @Query("SELECT COUNT(*) FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun countInSession(sessionId: String): Int

    /**
     * History drawer rows. The title is the first thing the user said in that
     * conversation, pulled out with a correlated subquery so we get everything
     * in one reactive pass with no schema change.
     */
    @Query(
        """
        SELECT cm.sessionId AS sessionId,
               MAX(cm.timestamp) AS updatedAt,
               (SELECT c.content FROM chat_messages c
                 WHERE c.sessionId = cm.sessionId AND c.role = 'user'
                 ORDER BY c.timestamp ASC, c.id ASC LIMIT 1) AS title
        FROM chat_messages cm
        GROUP BY cm.sessionId
        ORDER BY updatedAt DESC
        """
    )
    fun observeSessions(): Flow<List<SessionRow>>
}
