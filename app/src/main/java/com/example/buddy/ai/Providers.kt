package com.example.buddy.ai

/** Thrown when a provider needs a key the user hasn't supplied yet. */
class MissingKeyException(val providerLabel: String) :
    IllegalStateException("No API key saved for $providerLabel")

/**
 * Every AI backend Buddy can talk to.
 *
 * - `requiresKey = false` means it works with no key at all (e.g. a local Ollama).
 * - `supportsJsonMode = false` means we skip the provider's native JSON flag and
 *   lean on the system prompt instead.
 *
 * Adding another backend is one line here.
 */
enum class ProviderKind(
    val id: String,
    val label: String,
    val requiresKey: Boolean,
    val defaultBaseUrl: String?,
    val supportsJsonMode: Boolean
) {
    OPENAI("openai", "OpenAI", true, "https://api.openai.com/v1", true),
    ANTHROPIC("anthropic", "Anthropic Claude", true, null, false),
    GEMINI("gemini", "Google Gemini", true, null, true),
    OPENROUTER("openrouter", "OpenRouter (hundreds of models)", true, "https://openrouter.ai/api/v1", true),
    GROQ("groq", "Groq (fast, free tier)", true, "https://api.groq.com/openai/v1", true),
    MISTRAL("mistral", "Mistral AI", true, "https://api.mistral.ai/v1", true),
    DEEPSEEK("deepseek", "DeepSeek", true, "https://api.deepseek.com/v1", true),
    TOGETHER("together", "Together AI", true, "https://api.together.xyz/v1", true),
    OLLAMA("ollama", "Ollama (local, no key)", false, "http://127.0.0.1:11434/v1", false),
    CUSTOM("custom", "Custom (OpenAI-compatible)", false, null, false);

    /** OpenAI-style backends all share a single implementation. */
    val isOpenAiCompatible: Boolean
        get() = this != ANTHROPIC && this != GEMINI

    companion object {
        fun fromId(id: String): ProviderKind =
            values().firstOrNull { it.id == id } ?: OPENAI
    }
}

enum class ModelTier(val costRank: Int, val label: String) {
    FREE(0, "free"),
    CHEAP(1, "cheap"),
    BALANCED(2, "balanced"),
    PREMIUM(3, "premium")
}

data class ModelSpec(
    val id: String,
    val provider: ProviderKind,
    val label: String,
    val tier: ModelTier
)

/**
 * A broad, editable catalogue. Model ids change over time, so Settings also
 * accepts any custom model id — and OpenRouter passes through hundreds of
 * `vendor/model` ids with a single key.
 */
object ModelCatalog {

    val ALL: List<ModelSpec> = listOf(
        // ---- OpenAI ----
        ModelSpec("gpt-4o-mini", ProviderKind.OPENAI, "GPT-4o mini", ModelTier.CHEAP),
        ModelSpec("gpt-4o", ProviderKind.OPENAI, "GPT-4o", ModelTier.PREMIUM),
        ModelSpec("gpt-4.1-mini", ProviderKind.OPENAI, "GPT-4.1 mini", ModelTier.CHEAP),
        ModelSpec("gpt-4.1", ProviderKind.OPENAI, "GPT-4.1", ModelTier.PREMIUM),
        ModelSpec("o4-mini", ProviderKind.OPENAI, "o4-mini (reasoning)", ModelTier.BALANCED),

        // ---- Anthropic ----
        ModelSpec("claude-3-5-haiku-latest", ProviderKind.ANTHROPIC, "Claude 3.5 Haiku", ModelTier.CHEAP),
        ModelSpec("claude-3-5-sonnet-latest", ProviderKind.ANTHROPIC, "Claude 3.5 Sonnet", ModelTier.PREMIUM),
        ModelSpec("claude-3-7-sonnet-latest", ProviderKind.ANTHROPIC, "Claude 3.7 Sonnet", ModelTier.PREMIUM),

        // ---- Google Gemini ----
        ModelSpec("gemini-2.0-flash", ProviderKind.GEMINI, "Gemini 2.0 Flash", ModelTier.CHEAP),
        ModelSpec("gemini-2.5-flash", ProviderKind.GEMINI, "Gemini 2.5 Flash", ModelTier.CHEAP),
        ModelSpec("gemini-2.5-pro", ProviderKind.GEMINI, "Gemini 2.5 Pro", ModelTier.PREMIUM),

        // ---- Groq ----
        ModelSpec("llama-3.3-70b-versatile", ProviderKind.GROQ, "Llama 3.3 70B (Groq)", ModelTier.CHEAP),
        ModelSpec("llama-3.1-8b-instant", ProviderKind.GROQ, "Llama 3.1 8B (Groq)", ModelTier.FREE),

        // ---- Mistral ----
        ModelSpec("mistral-small-latest", ProviderKind.MISTRAL, "Mistral Small", ModelTier.CHEAP),
        ModelSpec("mistral-large-latest", ProviderKind.MISTRAL, "Mistral Large", ModelTier.BALANCED),

        // ---- DeepSeek ----
        ModelSpec("deepseek-chat", ProviderKind.DEEPSEEK, "DeepSeek V3", ModelTier.CHEAP),
        ModelSpec("deepseek-reasoner", ProviderKind.DEEPSEEK, "DeepSeek R1", ModelTier.BALANCED),

        // ---- OpenRouter: one key, hundreds of models ----
        ModelSpec("openrouter/auto", ProviderKind.OPENROUTER, "OpenRouter Auto", ModelTier.CHEAP),
        ModelSpec("meta-llama/llama-3.3-70b-instruct", ProviderKind.OPENROUTER, "Llama 3.3 70B", ModelTier.CHEAP),
        ModelSpec("qwen/qwen-2.5-72b-instruct", ProviderKind.OPENROUTER, "Qwen 2.5 72B", ModelTier.CHEAP),
        ModelSpec("deepseek/deepseek-chat", ProviderKind.OPENROUTER, "DeepSeek V3", ModelTier.CHEAP),
        ModelSpec("google/gemini-2.0-flash-001", ProviderKind.OPENROUTER, "Gemini 2.0 Flash", ModelTier.CHEAP),
        ModelSpec("anthropic/claude-3.5-sonnet", ProviderKind.OPENROUTER, "Claude 3.5 Sonnet", ModelTier.PREMIUM),
        ModelSpec("x-ai/grok-2-1212", ProviderKind.OPENROUTER, "Grok 2", ModelTier.BALANCED),

        // ---- Local: no internet, no key ----
        ModelSpec("llama3.2", ProviderKind.OLLAMA, "Llama 3.2 (local)", ModelTier.FREE),
        ModelSpec("qwen2.5", ProviderKind.OLLAMA, "Qwen 2.5 (local)", ModelTier.FREE)
    )

    fun forProvider(kind: ProviderKind): List<ModelSpec> = ALL.filter { it.provider == kind }

    fun find(id: String): ModelSpec? = ALL.firstOrNull { it.id == id }

    /** Cheapest sensible default for a provider. */
    fun defaultFor(kind: ProviderKind): String =
        forProvider(kind).minByOrNull { it.tier.costRank }?.id ?: fallbackModel(kind)

    private fun fallbackModel(kind: ProviderKind): String = when (kind) {
        ProviderKind.OPENAI -> "gpt-4o-mini"
        ProviderKind.ANTHROPIC -> "claude-3-5-haiku-latest"
        ProviderKind.GEMINI -> "gemini-2.0-flash"
        ProviderKind.OPENROUTER -> "openrouter/auto"
        ProviderKind.GROQ -> "llama-3.1-8b-instant"
        ProviderKind.MISTRAL -> "mistral-small-latest"
        ProviderKind.DEEPSEEK -> "deepseek-chat"
        ProviderKind.TOGETHER -> "meta-llama/Llama-3.3-70B-Instruct-Turbo"
        ProviderKind.OLLAMA -> "llama3.2"
        ProviderKind.CUSTOM -> "gpt-4o-mini"
    }
}
