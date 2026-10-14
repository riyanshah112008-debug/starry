package com.example.buddy.data.model

/**
 * One row in the ChatGPT-style history drawer.
 *
 * Derived straight from [com.example.buddy.data.entity.ChatMessage] rows, so no
 * schema change (and no migration) is needed to list a user's conversations.
 */
data class SessionRow(
    val sessionId: String,
    val updatedAt: Long,
    val title: String?
)
