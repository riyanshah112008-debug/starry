package com.example.buddy.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Long-term key-value fact remembered by Buddy (e.g., wifi password, mom's number,
 * user's nickname). Injected into the system prompt by ContextBuilder.
 */
@Entity(tableName = "memories")
data class Memory(
    @PrimaryKey
    val key: String,
    val value: String,
    val importance: Int = 1,
    val updatedAt: Long = System.currentTimeMillis()
)
