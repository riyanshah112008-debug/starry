package com.example.buddy.agent

import com.example.buddy.ai.BuddyDecision
import com.example.buddy.ai.ChatTurn
import com.example.buddy.ai.LLMClient
import com.example.buddy.data.repo.AuditRepository
import com.example.buddy.data.repo.ChatRepository
import com.example.buddy.security.SecurityManager
import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * UI events emitted by [AgentOrchestrator] as Buddy thinks, calls tools,
 * asks for confirmation, and finishes or hits an error.
 */
sealed class AgentEvent {
    object Thinking : AgentEvent()
    data class ToolCompleted(val toolName: String, val result: String) : AgentEvent()
    data class ConfirmationNeeded(val toolName: String, val args: JsonObject) : AgentEvent()
    data class AssistantReply(val summary: String) : AgentEvent()
    data class Error(val reason: String, val isMissingKey: Boolean = false) : AgentEvent()
}

/**
 * Coordinates the multi-step agent loop (up to [MAX_ITERATIONS] = 12):
 * 1. Persists the user's message.
 * 2. Emits [AgentEvent.Thinking].
 * 3. Builds the system prompt (with long-term memory + sanitized screen snapshot).
 * 4. Calls OpenAI via [LLMClient] with JSON-only enforcement.
 * 5. If Buddy requests a tool:
 *    - Checks [SecurityManager.requiresConfirmation]; if sensitive, pauses and requests
 *      user confirmation via [confirmationGate].
 *    - Executes the tool via [ToolRegistry].
 *    - Logs the outcome to [AuditRepository].
 *    - Emits [AgentEvent.ToolCompleted] or [AgentEvent.Error] and feeds the tool result
 *      back into the next iteration so Buddy can finish or take the next step.
 * 6. If Buddy returns `{"done": true, "summary": "..."}`, persists and emits the warm reply.
 */
class AgentOrchestrator(
    private val contextBuilder: ContextBuilder,
    private val llmClient: LLMClient,
    private val toolRegistry: ToolRegistry,
    private val chatRepository: ChatRepository,
    private val auditRepository: AuditRepository
) {

    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    suspend fun handleUserInput(
        sessionId: String,
        userText: String,
        confirmationGate: suspend (toolName: String, args: JsonObject) -> Boolean
    ) {
        val cleaned = userText.trim()
        if (cleaned.isEmpty()) return

        chatRepository.addUserMessage(sessionId, cleaned)

        // Build rolling conversation history for the session
        val storedHistory = chatRepository.getRecentHistory(sessionId, limit = 16)
        val workingTurns = mutableListOf<ChatTurn>()
        for (msg in storedHistory) {
            val mappedRole = when (msg.role) {
                ChatRepository.ROLE_USER -> "user"
                ChatRepository.ROLE_ASSISTANT -> "assistant"
                ChatRepository.ROLE_TOOL_RESULT -> "user"
                else -> null
            }
            if (mappedRole != null) {
                val content = if (msg.role == ChatRepository.ROLE_TOOL_RESULT) {
                    "Tool result (${msg.tool ?: "tool"}): ${msg.content}"
                } else {
                    msg.content
                }
                workingTurns.add(ChatTurn(role = mappedRole, content = content))
            }
        }

        for (iteration in 1..MAX_ITERATIONS) {
            _events.emit(AgentEvent.Thinking)

            val systemPrompt = contextBuilder.buildSystemPrompt(includeScreenSnapshot = true)
            val result = llmClient.completeJson(
                systemPrompt = systemPrompt,
                messages = workingTurns
            )

            if (result.isFailure) {
                val ex = result.exceptionOrNull()
                val isMissingKey = ex is LLMClient.MissingApiKeyException
                val reason = ex?.message ?: "Connection hiccup"
                _events.emit(AgentEvent.Error(reason = reason, isMissingKey = isMissingKey))
                return
            }

            when (val decision = result.getOrThrow()) {
                is BuddyDecision.Done -> {
                    val reply = decision.summary.ifBlank { "All set, boss." }
                    chatRepository.addAssistantMessage(sessionId, reply)
                    _events.emit(AgentEvent.AssistantReply(reply))
                    return
                }

                is BuddyDecision.ToolCall -> {
                    val toolName = decision.tool
                    val args = decision.args
                    val argsString = args.toString()

                    // Gate destructive or sensitive tools on user confirmation
                    if (SecurityManager.requiresConfirmation(toolName, args)) {
                        _events.emit(AgentEvent.ConfirmationNeeded(toolName, args))
                        val approved = confirmationGate(toolName, args)
                        if (!approved) {
                            val cancelNote = "User cancelled $toolName"
                            auditRepository.recordToolCall(
                                sessionId = sessionId,
                                toolName = toolName,
                                argsJson = argsString,
                                success = false,
                                resultSummary = cancelNote
                            )
                            val friendlyCancel = "No worries, boss - I cancelled that."
                            chatRepository.addAssistantMessage(sessionId, friendlyCancel)
                            _events.emit(AgentEvent.AssistantReply(friendlyCancel))
                            return
                        }
                    }

                    // Execute tool
                    val toolResult = toolRegistry.execute(toolName, args)
                    auditRepository.recordToolCall(
                        sessionId = sessionId,
                        toolName = toolName,
                        argsJson = argsString,
                        success = toolResult.success,
                        resultSummary = toolResult.output
                    )

                    if (toolResult.success) {
                        _events.emit(
                            AgentEvent.ToolCompleted(
                                toolName = toolName,
                                result = toolResult.output
                            )
                        )
                    } else {
                        _events.emit(
                            AgentEvent.Error(
                                reason = toolResult.output,
                                isMissingKey = false
                            )
                        )
                    }

                    // Append assistant tool call + tool observation to working context
                    workingTurns.add(
                        ChatTurn(
                            role = "assistant",
                            content = decision.rawJson
                        )
                    )
                    workingTurns.add(
                        ChatTurn(
                            role = "user",
                            content = "Tool '$toolName' finished (success=${toolResult.success}): ${toolResult.output}. Respond with next JSON action or {\"done\":true,\"summary\":\"...\"}."
                        )
                    )

                    // Brief pause so UI changes settle before the next screen snapshot
                    delay(350L)
                }
            }
        }

        // If we hit MAX_ITERATIONS without a Done reply, wrap up warmly
        val wrapUp = "Whew, that took a bunch of steps! Let me know if we need to keep going, boss."
        chatRepository.addAssistantMessage(sessionId, wrapUp)
        _events.emit(AgentEvent.AssistantReply(wrapUp))
    }

    companion object {
        const val MAX_ITERATIONS = 12
    }
}
