package com.example.buddy

import android.app.Application
import com.example.buddy.agent.AgentOrchestrator
import com.example.buddy.agent.ContextBuilder
import com.example.buddy.agent.ToolRegistry
import com.example.buddy.ai.LLMClient
import com.example.buddy.ai.RateLimiter
import com.example.buddy.data.AppDatabase
import com.example.buddy.data.repo.AuditRepository
import com.example.buddy.data.repo.ChatRepository
import com.example.buddy.data.repo.MemoryRepository
import com.example.buddy.security.SecureVault

/**
 * Application class that initializes [SecureVault], [AppDatabase], repositories,
 * and the [AgentOrchestrator] singleton graph.
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

    lateinit var agentOrchestrator: AgentOrchestrator
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1. Initialize encrypted key storage
        SecureVault.init(this)

        // 2. Initialize Room database & repositories
        database = AppDatabase.getInstance(this)
        chatRepository = ChatRepository(database.chatDao())
        auditRepository = AuditRepository(database.actionLogDao())
        memoryRepository = MemoryRepository(
            memoryDao = database.memoryDao(),
            preferenceDao = database.preferenceDao()
        )

        // 3. Wire AI & Agent layers
        val rateLimiter = RateLimiter(maxCallsPerMinute = 30)
        val llmClient = LLMClient(rateLimiter = rateLimiter)
        val contextBuilder = ContextBuilder(memoryRepository = memoryRepository)
        val toolRegistry = ToolRegistry(
            context = applicationContext,
            memoryRepository = memoryRepository
        )

        agentOrchestrator = AgentOrchestrator(
            contextBuilder = contextBuilder,
            llmClient = llmClient,
            toolRegistry = toolRegistry,
            chatRepository = chatRepository,
            auditRepository = auditRepository
        )
    }

    companion object {
        lateinit var instance: AiAgentApp
            private set
    }
}
