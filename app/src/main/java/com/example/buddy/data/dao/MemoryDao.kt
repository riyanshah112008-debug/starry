package com.example.buddy.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.buddy.data.entity.Memory
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: Memory)

    @Query("SELECT * FROM memories WHERE `key` = :key LIMIT 1")
    suspend fun getByKey(key: String): Memory?

    @Query("SELECT * FROM memories ORDER BY importance DESC, updatedAt DESC LIMIT :limit")
    suspend fun getTopMemories(limit: Int = 50): List<Memory>

    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    fun observeAllMemories(): Flow<List<Memory>>

    @Query("DELETE FROM memories WHERE `key` = :key")
    suspend fun deleteByKey(key: String)
}
