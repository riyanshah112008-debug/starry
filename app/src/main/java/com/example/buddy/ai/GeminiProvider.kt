package com.example.buddy.ai

import com.example.buddy.security.SecureVault
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Google Gemini `generateContent` API. */
class GeminiProvider : LlmProvider {

    override val kind: ProviderKind = ProviderKind.GEMINI

    override suspend fun completeJson(
        model: String,
        systemPrompt: String,
        turns: List<ChatTurn>,
        maxOutputTokens: Int
    ): Result<BuddyDecision> {
        val key = SecureVault.getKey(kind.id)
        if (key.isNullOrBlank()) return Result.failure(MissingKeyException(kind.label))

        val contents = JsonArray()
        turns.forEach { turn ->
            val role = if (turn.role == "assistant") "model" else "user"
            // Gemini expects a leading user turn.
            if (contents.size() == 0 && role == "model") return@forEach
            contents.add(
                JsonObject().apply {
                    addProperty("role", role)
                    add("parts", JsonArray().apply {
                        add(JsonObject().apply { addProperty("text", turn.content) })
                    })
                }
            )
        }
        if (contents.size() == 0) {
            contents.add(
                JsonObject().apply {
                    addProperty("role", "user")
                    add("parts", JsonArray().apply {
                        add(JsonObject().apply { addProperty("text", "Hey Buddy") })
                    })
                }
            )
        }

        val body = JsonObject().apply {
            add("systemInstruction", JsonObject().apply {
                add("parts", JsonArray().apply {
                    add(JsonObject().apply { addProperty("text", systemPrompt) })
                })
            })
            add("contents", contents)
            add("generationConfig", JsonObject().apply {
                addProperty("temperature", 0.25)
                addProperty("maxOutputTokens", maxOutputTokens)
                addProperty("responseMimeType", "application/json")
            })
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            "$model:generateContent?key=$key"

        return Http.postJson(url, mapOf("Content-Type" to "application/json"), body.toString())
            .mapCatching { raw ->
                val root = JsonParser.parseString(raw).asJsonObject
                val text = root.getAsJsonArray("candidates")
                    ?.firstOrNull()
                    ?.asJsonObject
                    ?.getAsJsonObject("content")
                    ?.getAsJsonArray("parts")
                    ?.firstOrNull()
                    ?.asJsonObject
                    ?.get("text")?.asString
                    ?: throw IllegalStateException("Empty response from Gemini")
                BuddyResponseParser.parse(text)
            }
    }
}
