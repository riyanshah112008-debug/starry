package com.example.buddy.data.repo

import com.example.buddy.data.dao.ActionLogDao
import com.example.buddy.data.entity.ActionLog
import kotlinx.coroutines.flow.Flow

/**
 * Repository for recording an audit trail of every tool call executed or blocked.
 */
class AuditRepository(private val actionLogDao: ActionLogDao) {

    suspend fun recordToolCall(
        sessionId: String,
        toolName: String,
        argsJson: String,
        success: Boolean,
        resultSummary: String
    ): Long {
        val entry = ActionLog(
            sessionId = sessionId,
            toolName = toolName,
            argsJson = argsJson,
            success = success,
            resultSummary = resultSummary
        )
        return actionLogDao.insert(entry)
    }

    fun observeRecentLogs(limit: Int = 100): Flow<List<ActionLog>> {
        return actionLogDao.observeRecentLogs(limit)
    }

    suspend fun getSessionLogs(sessionId: String): List<ActionLog> {
        return actionLogDao.getLogsForSession(sessionId)
    }
}
