package com.example.buddy.ai

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Data models for communicating with OpenAI's Chat Completions API and parsing
 * Buddy's strict JSON responses.
 */
data class ChatTurn(
    val role: String,
    val content: String
)

/**
 * Parsed representation of Buddy's JSON-only response:
 * Either a tool call:
 *   {"tool":"tool_name","args":{...}}
 * Or a final conversational reply:
 *   {"done":true,"summary":"<warm reply>"}
 */
sealed class BuddyDecision {
    data class ToolCall(
        val tool: String,
        val args: JsonObject,
        val rawJson: String
    ) : BuddyDecision()

    data class Done(
        val summary: String,
        val rawJson: String
    ) : BuddyDecision()
}

object BuddyResponseParser {

    /**
     * Parses the raw string returned by the LLM into a [BuddyDecision].
     * Gracefully handles markdown code fences if the model ever wraps the JSON,
     * and falls back to a Done summary if plain text was returned.
     */
    fun parse(rawContent: String): BuddyDecision {
        val trimmed = rawContent.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        return try {
            val element: JsonElement = JsonParser.parseString(trimmed)
            if (!element.isJsonObject) {
                return BuddyDecision.Done(summary = trimmed, rawJson = rawContent)
            }
            val obj = element.asJsonObject

            when {
                obj.has("tool") && !obj.get("tool").isJsonNull -> {
                    val toolName = obj.get("tool").asString.trim()
                    val argsObj = if (obj.has("args") && obj.get("args").isJsonObject) {
                        obj.getAsJsonObject("args")
                    } else {
                        JsonObject()
                    }
                    BuddyDecision.ToolCall(
                        tool = toolName,
                        args = argsObj,
                        rawJson = trimmed
                    )
                }

                obj.has("summary") && !obj.get("summary").isJsonNull -> {
                    BuddyDecision.Done(
                        summary = obj.get("summary").asString.trim(),
                        rawJson = trimmed
                    )
                }

                else -> {
                    BuddyDecision.Done(
                        summary = trimmed,
                        rawJson = trimmed
                    )
                }
            }
        } catch (_: Exception) {
            // If parsing fails for any reason, surface the text as a final reply
            BuddyDecision.Done(summary = trimmed, rawJson = rawContent)
        }
    }
}
