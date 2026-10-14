package com.example.buddy.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.buddy.data.entity.ActionLog
import kotlinx.coroutines.flow.Flow

@Dao
interface ActionLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: ActionLog): Long

    @Query("SELECT * FROM action_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecentLogs(limit: Int = 100): Flow<List<ActionLog>>

    @Query("SELECT * FROM action_logs WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getLogsForSession(sessionId: String): List<ActionLog>
}
