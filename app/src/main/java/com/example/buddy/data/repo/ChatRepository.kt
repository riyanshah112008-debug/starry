package com.example.buddy.data.repo

import com.example.buddy.data.dao.ChatDao
import com.example.buddy.data.entity.ChatMessage
import com.example.buddy.data.model.SessionRow
import kotlinx.coroutines.flow.Flow

/**
 * Repository managing chat messages for a session.
 */
class ChatRepository(private val chatDao: ChatDao) {

    fun observeMessages(sessionId: String): Flow<List<ChatMessage>> {
        return chatDao.observeSessionMessages(sessionId)
    }

    suspend fun addUserMessage(sessionId: String, text: String): Long {
        return chatDao.insert(
            ChatMessage(
                sessionId = sessionId,
                role = ROLE_USER,
                content = text.trim()
            )
        )
    }

    suspend fun addAssistantMessage(
        sessionId: String,
        content: String,
        tool: String? = null,
        args: String? = null
    ): Long {
        return chatDao.insert(
            ChatMessage(
                sessionId = sessionId,
                role = ROLE_ASSISTANT,
                content = content,
                tool = tool,
                args = args
            )
        )
    }

    suspend fun addEventBubble(
        sessionId: String,
        role: String,
        content: String,
        tool: String? = null,
        args: String? = null
    ): Long {
        return chatDao.insert(
            ChatMessage(
                sessionId = sessionId,
                role = role,
                content = content,
                tool = tool,
                args = args
            )
        )
    }

    suspend fun clearThinkingBubbles(sessionId: String) {
        chatDao.clearThinkingBubbles(sessionId)
    }

    // ---------------- ChatGPT-style history ----------------

    fun observeSessions(): Flow<List<SessionRow>> = chatDao.observeSessions()

    suspend fun isSessionEmpty(sessionId: String): Boolean =
        chatDao.countInSession(sessionId) == 0

    suspend fun deleteSession(sessionId: String) {
        chatDao.clearSession(sessionId)
    }

    /**
     * Returns recent conversation history in chronological order for LLM context.
     */
    suspend fun getRecentHistory(sessionId: String, limit: Int = 16): List<ChatMessage> {
        return chatDao.getRecentConversationMessages(sessionId, limit).reversed()
    }

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_THINKING = "thinking"
        const val ROLE_TOOL_RESULT = "tool_result"
        const val ROLE_ERROR = "error"
        const val ROLE_CONFIRMATION = "confirmation"
    }
}
