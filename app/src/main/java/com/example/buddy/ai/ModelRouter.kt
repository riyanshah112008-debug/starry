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
 * in front.
 *
 * Only providers the user has actually set up are ever considered. That matters:
 * an earlier version treated "needs no API key" as "is ready", so a local Ollama
 * on http://127.0.0.1 got tried on every single request and failed with
 * "CLEARTEXT communication to 127.0.0.1 not permitted".
 */
class ModelRouter(
    private val rateLimiter: RateLimiter = RateLimiter(maxCallsPerMinute = 12),
    private val budget: TokenBudget = TokenBudget(),
    private val cache: ResponseCache = ResponseCache()
) {

    private data class ModelChoice(val provider: ProviderKind, val model: String)

    fun resetBudget() = budget.reset()

    fun budgetSnapshot(): String = budget.snapshot()

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
            return@coroutineScope Result.failure(NoProviderConfiguredException())
        }

        val maxAttempts = when (strategy) {
            RoutingStrategy.AUTO -> 2
            RoutingStrategy.BEST -> 3
            RoutingStrategy.FALLBACK -> 4
            RoutingStrategy.ENSEMBLE -> 3
        }
        val picks = candidates.take(maxAttempts)
        val estimated = TokenBudget.estimate(
            systemPrompt,
            turns.joinToString(" ") { it.content }
        )

        if (strategy == RoutingStrategy.ENSEMBLE && picks.size > 1) {
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
        for (choice in picks) {
            val outcome = runOne(choice, systemPrompt, turns, maxOutputTokens, estimated)
            outcome.getOrNull()?.let { return@coroutineScope Result.success(it) }
            lastError = outcome.exceptionOrNull()
        }
        Result.failure(lastError ?: IllegalStateException("No model answered."))
    }

    /**
     * A provider counts as usable only when the user has deliberately set it up:
     * keyed providers need a key, keyless ones (Ollama / custom) need a base URL.
     */
    private fun isConfigured(kind: ProviderKind): Boolean =
        if (kind.requiresKey) {
            !SecureVault.getKey(kind.id).isNullOrBlank()
        } else {
            !SecureVault.getBaseUrl(kind.id).isNullOrBlank()
        }

    private fun candidateModels(strategy: RoutingStrategy): List<ModelChoice> {
        val preferredProvider = SecureVault.getProvider()
        val preferredModel = SecureVault.getModel()

        val configured = ProviderKind.values().filter { isConfigured(it) }
        if (configured.isEmpty()) return emptyList()

        val list = mutableListOf<ModelChoice>()
        fun add(kind: ProviderKind, model: String) {
            if (!isConfigured(kind)) return
            if (list.any { it.provider == kind && it.model == model }) return
            list.add(ModelChoice(kind, model))
        }

        // The user's explicit pick goes first when it's actually set up.
        if (isConfigured(preferredProvider)) add(preferredProvider, preferredModel)

        // Then the cheapest models of every other configured provider.
        ModelCatalog.ALL
            .filter { isConfigured(it.provider) }
            .sortedBy { it.tier.costRank }
            .forEach { add(it.provider, it.id) }

        // A configured provider with no catalogue entry still gets a slot.
        configured.forEach { kind ->
            if (list.none { it.provider == kind }) {
                list.add(ModelChoice(kind, ModelCatalog.defaultFor(kind)))
            }
        }

        return if (strategy == RoutingStrategy.BEST) {
            list.sortedByDescending { ModelCatalog.find(it.model)?.tier?.costRank ?: 1 }
        } else {
            list
        }
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

        // Resolve the provider BEFORE spending, so a misconfigured one is free.
        val provider = try {
            providerFor(choice.provider)
        } catch (t: Throwable) {
            return Result.failure(t)
        }

        if (!budget.canSpend(estimated)) {
            return Result.failure(
                IllegalStateException(
                    "Paused so we don't burn your key (${budget.snapshot()}). Raise the cap in Settings."
                )
            )
        }

        return try {
            rateLimiter.acquire()
            val result = provider.completeJson(choice.model, systemPrompt, turns, maxOutputTokens)
            budget.charge(estimated)
            result.onSuccess { cache.put(cacheKey, it) }
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
