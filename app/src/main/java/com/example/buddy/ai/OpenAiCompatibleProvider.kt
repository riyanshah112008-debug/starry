package com.example.buddy.ai

import com.example.buddy.security.SecureVault
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Speaks the OpenAI `/chat/completions` dialect. This one class covers OpenAI,
 * OpenRouter, Groq, Mistral, DeepSeek, Together, a local Ollama, and any custom
 * OpenAI-compatible endpoint the user points at.
 */
class OpenAiCompatibleProvider(
    override val kind: ProviderKind,
    private val baseUrl: String
) : LlmProvider {

    override suspend fun completeJson(
        model: String,
        systemPrompt: String,
        turns: List<ChatTurn>,
        maxOutputTokens: Int
    ): Result<BuddyDecision> {
        val key = SecureVault.getKey(kind.id)
        if (kind.requiresKey && key.isNullOrBlank()) {
            return Result.failure(MissingKeyException(kind.label))
        }

        val body = JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", 0.25)
            addProperty("max_tokens", maxOutputTokens)
            if (kind.supportsJsonMode) {
                add("response_format", JsonObject().apply { addProperty("type", "json_object") })
            }
            add("messages", JsonArray().apply {
                add(message("system", systemPrompt))
                turns.forEach { add(message(it.role, it.content)) }
            })
        }

        val headers = mutableMapOf("Content-Type" to "application/json")
        if (!key.isNullOrBlank()) headers["Authorization"] = "Bearer $key"
        if (kind == ProviderKind.OPENROUTER) {
            headers["HTTP-Referer"] = "https://github.com/riyanshah112008-debug/starry"
            headers["X-Title"] = "Buddy"
        }

        val url = baseUrl.trimEnd('/') + "/chat/completions"
        return Http.postJson(url, headers, body.toString()).mapCatching { raw ->
            val root = JsonParser.parseString(raw).asJsonObject
            val content = root.getAsJsonArray("choices")
                ?.firstOrNull()
                ?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString
                ?: throw IllegalStateException("Empty response from ${kind.label}")
            BuddyResponseParser.parse(content)
        }
    }

    private fun message(role: String, content: String): JsonObject = JsonObject().apply {
        addProperty("role", role)
        addProperty("content", content)
    }
}
