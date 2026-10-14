package com.example.buddy.ai

/**
 * One concrete way of asking a model for Buddy's JSON decision.
 * Implementations differ only in transport/auth/JSON shape.
 */
interface LlmProvider {
    val kind: ProviderKind

    suspend fun completeJson(
        model: String,
        systemPrompt: String,
        turns: List<ChatTurn>,
        maxOutputTokens: Int
    ): Result<BuddyDecision>
}
