package com.example.buddy.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Lightweight user preference key-value pair stored in Room.
 */
@Entity(tableName = "user_preferences")
data class UserPreference(
    @PrimaryKey
    val key: String,
    val value: String
)
