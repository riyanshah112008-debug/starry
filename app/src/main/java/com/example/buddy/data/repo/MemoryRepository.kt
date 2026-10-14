package com.example.buddy.data.repo

import com.example.buddy.data.dao.MemoryDao
import com.example.buddy.data.dao.PreferenceDao
import com.example.buddy.data.entity.Memory
import com.example.buddy.data.entity.UserPreference

/**
 * Manages Buddy's long-term memory facts and user preferences.
 */
class MemoryRepository(
    private val memoryDao: MemoryDao,
    private val preferenceDao: PreferenceDao
) {

    suspend fun remember(key: String, value: String, importance: Int = 2) {
        val normalizedKey = key.trim().lowercase()
        if (normalizedKey.isEmpty()) return
        memoryDao.upsert(
            Memory(
                key = normalizedKey,
                value = value.trim(),
                importance = importance.coerceIn(1, 5),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun recall(key: String): String? {
        val normalizedKey = key.trim().lowercase()
        return memoryDao.getByKey(normalizedKey)?.value
    }

    suspend fun getTopMemories(limit: Int = 40): List<Memory> {
        return memoryDao.getTopMemories(limit)
    }

    suspend fun setPreference(key: String, value: String) {
        preferenceDao.upsert(UserPreference(key = key.trim(), value = value.trim()))
    }

    suspend fun getPreference(key: String): String? {
        return preferenceDao.getPreference(key.trim())?.value
    }

    suspend fun getAllPreferences(): List<UserPreference> {
        return preferenceDao.getAllPreferences()
    }
}
