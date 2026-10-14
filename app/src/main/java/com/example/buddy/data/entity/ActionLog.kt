package com.example.buddy.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Audit trail entry recording every tool invocation attempted by Buddy,
 * its arguments, whether it succeeded, and the result or failure detail.
 */
@Entity(
    tableName = "action_logs",
    indices = [Index(value = ["timestamp"])]
)
data class ActionLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val sessionId: String,
    val toolName: String,
    val argsJson: String,
    val success: Boolean,
    val resultSummary: String,
    val timestamp: Long = System.currentTimeMillis()
)
