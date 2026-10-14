package com.example.buddy.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.buddy.data.entity.UserPreference
import kotlinx.coroutines.flow.Flow

@Dao
interface PreferenceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preference: UserPreference)

    @Query("SELECT * FROM user_preferences WHERE `key` = :key LIMIT 1")
    suspend fun getPreference(key: String): UserPreference?

    @Query("SELECT * FROM user_preferences")
    suspend fun getAllPreferences(): List<UserPreference>

    @Query("SELECT * FROM user_preferences")
    fun observePreferences(): Flow<List<UserPreference>>
}
