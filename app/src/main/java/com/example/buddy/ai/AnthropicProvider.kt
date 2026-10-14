package com.example.buddy.ai

import com.example.buddy.security.SecureVault
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Anthropic's Messages API: different auth header, a separate `system` field,
 * and turns that must alternate and start with a `user` message.
 */
class AnthropicProvider : LlmProvider {

    override val kind: ProviderKind = ProviderKind.ANTHROPIC

    override suspend fun completeJson(
        model: String,
        systemPrompt: String,
        turns: List<ChatTurn>,
        maxOutputTokens: Int
    ): Result<BuddyDecision> {
        val key = SecureVault.getKey(kind.id)
        if (key.isNullOrBlank()) return Result.failure(MissingKeyException(kind.label))

        val messages = JsonArray()
        var lastRole: String? = null
        turns.forEach { turn ->
            val role = if (turn.role == "assistant") "assistant" else "user"
            // Anthropic rejects a leading assistant turn.
            if (messages.size() == 0 && role == "assistant") return@forEach

            if (role == lastRole && messages.size() > 0) {
                // Merge consecutive same-role turns.
                val last = messages.get(messages.size() - 1).asJsonObject
                last.addProperty("content", last.get("content").asString + "\n" + turn.content)
            } else {
                messages.add(JsonObject().apply {
                    addProperty("role", role)
                    addProperty("content", turn.content)
                })
                lastRole = role
            }
        }
        if (messages.size() == 0) {
            messages.add(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", "Hey Buddy")
            })
        }

        val body = JsonObject().apply {
            addProperty("model", model)
            addProperty("max_tokens", maxOutputTokens)
            addProperty("temperature", 0.25)
            addProperty("system", systemPrompt)
            add("messages", messages)
        }

        val headers = mapOf(
            "Content-Type" to "application/json",
            "x-api-key" to key,
            "anthropic-version" to "2023-06-01"
        )

        return Http.postJson("https://api.anthropic.com/v1/messages", headers, body.toString())
            .mapCatching { raw ->
                val root = JsonParser.parseString(raw).asJsonObject
                val text = root.getAsJsonArray("content")
                    ?.firstOrNull()
                    ?.asJsonObject
                    ?.get("text")?.asString
                    ?: throw IllegalStateException("Empty response from Anthropic")
                BuddyResponseParser.parse(text)
            }
    }
}
