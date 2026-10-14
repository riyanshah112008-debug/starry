package com.example.buddy.agent

import com.example.buddy.ai.BuddyDecision
import com.example.buddy.ai.ChatTurn
import com.example.buddy.ai.FriendlyErrors
import com.example.buddy.ai.MissingKeyException
import com.example.buddy.ai.ModelRouter
import com.example.buddy.ai.NoProviderConfiguredException
import com.example.buddy.data.repo.AuditRepository
import com.example.buddy.data.repo.ChatRepository
import com.example.buddy.security.SecurityManager
import com.example.buddy.security.SecureVault
import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Events surfaced to the UI as warm chat bubbles. */
sealed class AgentEvent {
    object Thinking : AgentEvent()
    data class ToolCompleted(val toolName: String, val result: String) : AgentEvent()
    data class ConfirmationNeeded(val toolName: String, val args: JsonObject) : AgentEvent()
    data class AssistantReply(val summary: String) : AgentEvent()
    data class Error(val reason: String, val isMissingKey: Boolean = false) : AgentEvent()
}

/**
 * The agent loop, rewritten to be cheap:
 *
 * 1. A deterministic local router answers common commands with **zero** API calls.
 * 2. Anything else gets **one** model call. When the tool succeeds we already know
 *    what happened, so we phrase the reply locally instead of paying for a second
 *    round-trip. (Turn on "multi-step" in Settings to allow follow-up calls.)
 * 3. A hard token/call budget plus a response cache sit in front of the network.
 *
 * Destructive tools still pause for warm confirmation and are always audited.
 */
class AgentOrchestrator(
    private val contextBuilder: ContextBuilder,
    private val router: ModelRouter,
    private val toolRegistry: ToolRegistry,
    private val localRouter: LocalCommandRouter,
    private val chatRepository: ChatRepository,
    private val auditRepository: AuditRepository
) {

    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    fun budgetSnapshot(): String = router.budgetSnapshot()

    fun resetBudget() = router.resetBudget()

    suspend fun handleUserInput(
        sessionId: String,
        userText: String,
        confirmationGate: suspend (toolName: String, args: JsonObject) -> Boolean
    ) {
        val cleaned = userText.trim()
        if (cleaned.isEmpty()) return
        chatRepository.addUserMessage(sessionId, cleaned)

        // ---- 1. Local fast path: never touches the network ----
        when (val plan = localRouter.route(cleaned)) {
            is LocalCommandRouter.Plan.Reply -> {
                chatRepository.addAssistantMessage(sessionId, plan.text)
                _events.emit(AgentEvent.AssistantReply(plan.text))
                return
            }
            is LocalCommandRouter.Plan.Tool -> {
                val result = executeWithGate(sessionId, plan.tool, plan.args, confirmationGate)
                val reply = if (result == null) {
                    "No worries, boss - I held off on that."
                } else {
                    WarmVoice.summarize(plan.tool, result.success, result.output)
                }
                chatRepository.addAssistantMessage(sessionId, reply)
                _events.emit(AgentEvent.AssistantReply(reply))
                return
            }
            LocalCommandRouter.Plan.NeedsModel -> Unit
        }

        // ---- 2. Model path ----
        runModelLoop(sessionId, confirmationGate)
    }

    private suspend fun runModelLoop(
        sessionId: String,
        confirmationGate: suspend (toolName: String, args: JsonObject) -> Boolean
    ) {
        val turns = buildTurns(sessionId)
        val multiStep = SecureVault.isMultiStep()

        for (iteration in 1..MAX_ITERATIONS) {
            _events.emit(AgentEvent.Thinking)

            val prompt = contextBuilder.buildSystemPrompt(includeScreenSnapshot = true)
            val outcome = router.decide(prompt, turns)

            if (outcome.isFailure) {
                val error = outcome.exceptionOrNull()
                _events.emit(
                    AgentEvent.Error(
                        reason = FriendlyErrors.explain(error),
                        isMissingKey = error is MissingKeyException ||
                            error is NoProviderConfiguredException
                    )
                )
                return
            }

            when (val decision = outcome.getOrThrow()) {
                is BuddyDecision.Done -> {
                    val reply = decision.summary.ifBlank { "All set, boss." }
                    chatRepository.addAssistantMessage(sessionId, reply)
                    _events.emit(AgentEvent.AssistantReply(reply))
                    return
                }

                is BuddyDecision.ToolCall -> {
                    val result = executeWithGate(
                        sessionId, decision.tool, decision.args, confirmationGate
                    )
                    if (result == null) {
                        val reply = "No worries, boss - I held off on that."
                        chatRepository.addAssistantMessage(sessionId, reply)
                        _events.emit(AgentEvent.AssistantReply(reply))
                        return
                    }

                    val warm = WarmVoice.summarize(decision.tool, result.success, result.output)
                    if (!multiStep) {
                        // One model call per request - we already know the outcome.
                        chatRepository.addAssistantMessage(sessionId, warm)
                        _events.emit(AgentEvent.AssistantReply(warm))
                        return
                    }

                    turns.add(ChatTurn("assistant", decision.rawJson))
                    turns.add(
                        ChatTurn(
                            "user",
                            "Tool '${decision.tool}' finished (success=${result.success}): " +
                                "${result.output}. Reply with the next JSON action or " +
                                "{\"done\":true,\"summary\":\"...\"}."
                        )
                    )
                    delay(300L)
                }
            }
        }

        val wrap = "Whew, that took a few steps! Tell me if we should keep going, boss."
        chatRepository.addAssistantMessage(sessionId, wrap)
        _events.emit(AgentEvent.AssistantReply(wrap))
    }

    /** Runs a tool behind the confirmation gate and the audit log. Null = cancelled. */
    private suspend fun executeWithGate(
        sessionId: String,
        toolName: String,
        args: JsonObject,
        confirmationGate: suspend (toolName: String, args: JsonObject) -> Boolean
    ): ToolResult? {
        if (SecurityManager.requiresConfirmation(toolName, args)) {
            _events.emit(AgentEvent.ConfirmationNeeded(toolName, args))
            if (!confirmationGate(toolName, args)) {
                auditRepository.recordToolCall(
                    sessionId, toolName, args.toString(), false, "Cancelled by user"
                )
                return null
            }
        }

        val result = toolRegistry.execute(toolName, args)
        auditRepository.recordToolCall(
            sessionId, toolName, args.toString(), result.success, result.output
        )

        if (result.success) _events.emit(AgentEvent.ToolCompleted(toolName, result.output))
        else _events.emit(AgentEvent.Error(result.output))

        return result
    }

    /** Small, truncated conversation window - big prompts cost real money. */
    private suspend fun buildTurns(sessionId: String): MutableList<ChatTurn> {
        val turns = mutableListOf<ChatTurn>()
        chatRepository.getRecentHistory(sessionId, limit = 8).forEach { message ->
            when (message.role) {
                ChatRepository.ROLE_USER -> turns.add(ChatTurn("user", message.content.take(400)))
                ChatRepository.ROLE_ASSISTANT -> turns.add(ChatTurn("assistant", message.content.take(400)))
                else -> Unit
            }
        }
        return turns
    }

    companion object {
        const val MAX_ITERATIONS = 4
    }
}
