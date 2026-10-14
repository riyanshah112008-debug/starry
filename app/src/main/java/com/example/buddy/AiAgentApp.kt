package com.example.buddy

import android.app.Application
import com.example.buddy.agent.AgentOrchestrator
import com.example.buddy.agent.ContextBuilder
import com.example.buddy.agent.LocalCommandRouter
import com.example.buddy.agent.ToolRegistry
import com.example.buddy.ai.ModelRouter
import com.example.buddy.ai.RateLimiter
import com.example.buddy.ai.ResponseCache
import com.example.buddy.ai.TokenBudget
import com.example.buddy.data.AppDatabase
import com.example.buddy.data.repo.AuditRepository
import com.example.buddy.data.repo.ChatRepository
import com.example.buddy.data.repo.MemoryRepository
import com.example.buddy.security.SecureVault

/**
 * Wires the whole graph: encrypted vault, Room, repositories, the multi-model
 * router, the local fast-path, and the agent orchestrator.
 */
class AiAgentApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var chatRepository: ChatRepository
        private set

    lateinit var auditRepository: AuditRepository
        private set

    lateinit var memoryRepository: MemoryRepository
        private set

    lateinit var modelRouter: ModelRouter
        private set

    lateinit var agentOrchestrator: AgentOrchestrator
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        SecureVault.init(this)

        database = AppDatabase.getInstance(this)
        chatRepository = ChatRepository(database.chatDao())
        auditRepository = AuditRepository(database.actionLogDao())
        memoryRepository = MemoryRepository(
            memoryDao = database.memoryDao(),
            preferenceDao = database.preferenceDao()
        )

        // Budget + rate limit come from the user's saved settings, so the app can
        // never quietly burn through an API key again.
        val budget = TokenBudget(
            maxCalls = SecureVault.getMaxCalls(),
            maxTokens = SecureVault.getMaxTokens()
        )
        modelRouter = ModelRouter(
            rateLimiter = RateLimiter(maxCallsPerMinute = 12),
            budget = budget,
            cache = ResponseCache()
        )

        agentOrchestrator = AgentOrchestrator(
            contextBuilder = ContextBuilder(memoryRepository),
            router = modelRouter,
            toolRegistry = ToolRegistry(applicationContext, memoryRepository),
            localRouter = LocalCommandRouter(applicationContext),
            chatRepository = chatRepository,
            auditRepository = auditRepository
        )
    }

    companion object {
        lateinit var instance: AiAgentApp
            private set
    }
}
