package com.example.buddy.ai

import com.example.buddy.security.SecureVault
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenAI Chat Completions client using OkHttp with:
 * - RateLimiter (30 calls/min)
 * - 3 retries with exponential backoff for transient network / 429 / 5xx errors
 * - Strict JSON-only output via `response_format: { "type": "json_object" }`
 */
class LLMClient(
    private val rateLimiter: RateLimiter = RateLimiter(maxCallsPerMinute = 30)
) {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun completeJson(
        systemPrompt: String,
        messages: List<ChatTurn>
    ): Result<BuddyDecision> = withContext(Dispatchers.IO) {
        val apiKey = SecureVault.getApiKey()
        if (apiKey.isNullOrBlank()) {
            return@withContext Result.failure(MissingApiKeyException())
        }

        val model = SecureVault.getModelName()
        val payloadJson = buildRequestBody(model, systemPrompt, messages)

        var lastException: Exception? = null
        val maxRetries = 3
        var backoffMs = 800L

        for (attempt in 1..maxRetries) {
            try {
                rateLimiter.acquire()

                val request = Request.Builder()
                    .url(OPENAI_CHAT_URL)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(payloadJson.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string().orEmpty()

                    if (response.isSuccessful) {
                        val content = extractAssistantContent(bodyStr)
                        val decision = BuddyResponseParser.parse(content)
                        return@withContext Result.success(decision)
                    }

                    // Do not retry unauthorized / invalid key errors
                    if (response.code == 401 || response.code == 403) {
                        return@withContext Result.failure(
                            IOException("Invalid API key (HTTP ${response.code}). Check Settings.")
                        )
                    }

                    // Retry on rate limit (429) or server errors (5xx)
                    val apiErrorMsg = parseOpenAiErrorMessage(bodyStr)
                        ?: "HTTP ${response.code}"
                    lastException = IOException(apiErrorMsg)
                }
            } catch (e: IOException) {
                lastException = e
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }

            if (attempt < maxRetries) {
                delay(backoffMs)
                backoffMs *= 2
            }
        }

        Result.failure(lastException ?: IOException("Network request failed after $maxRetries tries."))
    }

    private fun buildRequestBody(
        model: String,
        systemPrompt: String,
        turns: List<ChatTurn>
    ): String {
        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", 0.4)

            val responseFormat = JsonObject().apply {
                addProperty("type", "json_object")
            }
            add("response_format", responseFormat)

            val messagesArray = JsonArray()
            messagesArray.add(
                JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", systemPrompt)
                }
            )
            for (turn in turns) {
                messagesArray.add(
                    JsonObject().apply {
                        addProperty("role", turn.role)
                        addProperty("content", turn.content)
                    }
                )
            }
            add("messages", messagesArray)
        }
        return root.toString()
    }

    private fun extractAssistantContent(rawResponse: String): String {
        val root = JsonParser.parseString(rawResponse).asJsonObject
        val choices = root.getAsJsonArray("choices")
        if (choices == null || choices.size() == 0) {
            throw IOException("Empty choices from OpenAI.")
        }
        val firstChoice = choices[0].asJsonObject
        val messageObj = firstChoice.getAsJsonObject("message")
        return messageObj.get("content")?.asString
            ?: throw IOException("Missing message content in OpenAI response.")
    }

    private fun parseOpenAiErrorMessage(rawResponse: String): String? {
        return try {
            val root = JsonParser.parseString(rawResponse).asJsonObject
            root.getAsJsonObject("error")?.get("message")?.asString
        } catch (_: Exception) {
            null
        }
    }

    class MissingApiKeyException : IllegalStateException("Missing OpenAI API key")

    companion object {
        private const val OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
