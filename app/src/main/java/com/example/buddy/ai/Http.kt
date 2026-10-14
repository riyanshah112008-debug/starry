package com.example.buddy.ai

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

class HttpException(val code: Int, override val message: String) : IOException(message)

/**
 * Shared, retrying HTTP JSON POST used by every provider.
 * Retries transient failures (timeouts, 429, 5xx) but never retries
 * authentication or bad-request errors — retrying those just wastes quota.
 */
object Http {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        bodyJson: String,
        retries: Int = 2
    ): Result<String> = withContext(Dispatchers.IO) {
        var backoff = 700L
        var last: Exception? = null

        for (attempt in 0..retries) {
            try {
                val builder = Request.Builder().url(url).post(bodyJson.toRequestBody(JSON))
                headers.forEach { (k, v) -> builder.addHeader(k, v) }

                client.newCall(builder.build()).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (response.isSuccessful) return@withContext Result.success(text)

                    val message = extractMessage(text) ?: "HTTP ${response.code}"
                    // Auth / bad request: pointless and expensive to retry
                    if (response.code == 400 || response.code == 401 ||
                        response.code == 403 || response.code == 404
                    ) {
                        return@withContext Result.failure(HttpException(response.code, message))
                    }
                    last = HttpException(response.code, message)
                }
            } catch (e: Exception) {
                last = e
            }
            if (attempt < retries) {
                delay(backoff)
                backoff *= 2
            }
        }
        Result.failure(last ?: IOException("Network request failed"))
    }

    private fun extractMessage(raw: String): String? = try {
        val obj = JsonParser.parseString(raw).asJsonObject
        val err = obj.get("error")
        when {
            err == null || err.isJsonNull -> obj.get("message")?.asString
            err.isJsonObject -> err.asJsonObject.get("message")?.asString
            else -> err.asString
        }
    } catch (_: Exception) {
        null
    }
}
