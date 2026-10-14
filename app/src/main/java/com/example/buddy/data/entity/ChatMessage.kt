package com.example.buddy.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted chat history entry.
 *
 * Roles used across the app:
 * - "user"         : user's spoken or typed prompt
 * - "assistant"    : Buddy's conversational reply
 * - "thinking"     : transient/warm status bubble ("Hmm, let me think...")
 * - "tool_result"  : green bubble showing a tool output ("Okay, done: ...")
 * - "error"        : soft red bubble showing a friendly error ("Ah, that didn't work: ...")
 * - "confirmation" : warm heads-up bubble before a sensitive action
 */
@Entity(
    tableName = "chat_messages",
    indices = [Index(value = ["sessionId", "timestamp"])]
)
data class ChatMessage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val sessionId: String,
    val role: String,
    val content: String,
    val tool: String? = null,
    val args: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
