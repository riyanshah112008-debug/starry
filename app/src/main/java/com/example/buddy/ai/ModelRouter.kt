package com.example.buddy.ai

import com.example.buddy.security.SecureVault
import com.google.gson.JsonObject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

enum class RoutingStrategy(val id: String, val label: String) {
    AUTO("auto", "Smart + cheap (local first)"),
    BEST("best", "Best model available"),
    FALLBACK("fallback", "Try several until one works"),
    ENSEMBLE("ensemble", "Ask multiple models and vote");

    companion object {
        fun fromId(id: String): RoutingStrategy = values().firstOrNull { it.id == id } ?: AUTO
    }
}

/**
 * Picks which model(s) to ask, in which order, with a hard budget and a cache
 * in front. This is what makes "a combination of every AI model" real:
 *
 * - [RoutingStrategy.AUTO]      cheapest working model, preferring the user's pick
 * - [RoutingStrategy.BEST]      highest-tier model that has a key
 * - [RoutingStrategy.FALLBACK]  walk down the list until one answers
 * - [RoutingStrategy.ENSEMBLE]  ask up to 3 models in parallel and majority-vote
 *
 * Voting is applied to the *action* (tool + normalised args) because that's the
 * only part of a decision with a single right answer; chit-chat takes the first
 * reply.
 */
class ModelRouter(
    private val rateLimiter: RateLimiter = RateLimiter(maxCallsPerMinute = 12),
    private val budget: TokenBudget = TokenBudget(),
    private val cache: ResponseCache = ResponseCache()
) {

    private data class ModelChoice(val provider: ProviderKind, val model: String)

    fun resetBudget() = budget.reset()

    fun budgetSnapshot(): String = budget.snapshot()

    /** Settings can move the ceiling without restarting the app. */
    fun setLimits(maxCalls: Int, maxTokens: Int) = budget.setLimits(maxCalls, maxTokens)

    /** Cheapest possible round-trip to prove a key works. */
    suspend fun testConnection(): Result<String> = decide(
        systemPrompt = "Reply with exactly {\"done\":true,\"summary\":\"ok\"} and nothing else.",
        turns = listOf(ChatTurn("user", "ping")),
        maxOutputTokens = 32
    ).map { "ok" }

    suspend fun decide(
        systemPrompt: String,
        turns: List<ChatTurn>,
        maxOutputTokens: Int = 320
    ): Result<BuddyDecision> = coroutineScope {
        val strategy = RoutingStrategy.fromId(SecureVault.getStrategy())
        val candidates = candidateModels(strategy)

        if (candidates.isEmpty()) {
            return@coroutineScope Result.failure(
                IllegalStateException("No model is ready - add an API key in Settings.")
            )
        }

        val estimated = TokenBudget.estimate(
            systemPrompt,
            turns.joinToString(" ") { it.content }
        )

        if (strategy == RoutingStrategy.ENSEMBLE) {
            val picks = candidates.take(3)
            val results = picks
                .map { choice -> async { runOne(choice, systemPrompt, turns, maxOutputTokens, estimated) } }
                .map { it.await() }

            val good = results.mapNotNull { it.getOrNull() }
            if (good.isEmpty()) {
                return@coroutineScope Result.failure(
                    results.firstNotNullOfOrNull { it.exceptionOrNull() }
                        ?: IllegalStateException("Every model failed.")
                )
            }
            return@coroutineScope Result.success(vote(good))
        }

        var lastError: Throwable? = null
        for (choice in candidates.take(4)) {
            val outcome = runOne(choice, systemPrompt, turns, maxOutputTokens, estimated)
            outcome.getOrNull()?.let { return@coroutineScope Result.success(it) }
            lastError = outcome.exceptionOrNull()
        }
        Result.failure(lastError ?: IllegalStateException("No model answered."))
    }

    private fun candidateModels(strategy: RoutingStrategy): List<ModelChoice> {
        val preferred = SecureVault.getModel()
        val preferredProvider = SecureVault.getProvider()

        fun usable(kind: ProviderKind): Boolean {
            if (!kind.requiresKey) return true
            return !SecureVault.getKey(kind.id).isNullOrBlank()
        }

        val list = mutableListOf<ModelChoice>()
        fun add(kind: ProviderKind, model: String) {
            if (!usable(kind)) return
            if (list.any { it.provider == kind && it.model == model }) return
            list.add(ModelChoice(kind, model))
        }

        add(preferredProvider, preferred)

        val ranked = if (strategy == RoutingStrategy.BEST) {
            ModelCatalog.ALL.sortedByDescending { it.tier.costRank }
        } else {
            ModelCatalog.ALL.sortedBy { it.tier.costRank }
        }
        ranked.forEach { add(it.provider, it.id) }
        return list
    }

    private suspend fun runOne(
        choice: ModelChoice,
        systemPrompt: String,
        turns: List<ChatTurn>,
        maxOutputTokens: Int,
        estimated: Int
    ): Result<BuddyDecision> {
        val cacheKey = ResponseCache.key(choice.provider.id, choice.model, systemPrompt, turns)
        cache.get(cacheKey)?.let { return Result.success(it) }

        if (!budget.canSpend(estimated)) {
            return Result.failure(
                IllegalStateException(
                    "Paused so we don't burn your key (${budget.snapshot()}). Raise the cap in Settings."
                )
            )
        }

        return try {
            rateLimiter.acquire()
            budget.charge(estimated)
            val provider = providerFor(choice.provider)
            provider.completeJson(choice.model, systemPrompt, turns, maxOutputTokens)
                .onSuccess { cache.put(cacheKey, it) }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun providerFor(kind: ProviderKind): LlmProvider = when (kind) {
        ProviderKind.ANTHROPIC -> AnthropicProvider()
        ProviderKind.GEMINI -> GeminiProvider()
        else -> OpenAiCompatibleProvider(kind, resolveBaseUrl(kind))
    }

    private fun resolveBaseUrl(kind: ProviderKind): String {
        SecureVault.getBaseUrl(kind.id)?.let { if (it.isNotBlank()) return it }
        return kind.defaultBaseUrl
            ?: throw IllegalStateException("Set a base URL for ${kind.label} in Settings.")
    }

    private fun vote(decisions: List<BuddyDecision>): BuddyDecision {
        val toolCalls = decisions.filterIsInstance<BuddyDecision.ToolCall>()
        if (toolCalls.isEmpty()) return decisions.first()

        val groups = toolCalls.groupBy { it.tool to canonicalArgs(it.args) }
        val winner = groups.maxByOrNull { it.value.size } ?: return decisions.first()
        return winner.value.first()
    }

    private fun canonicalArgs(args: JsonObject): String =
        args.entrySet().sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value}" }
}
