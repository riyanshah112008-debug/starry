package com.example.buddy.ai

import com.example.buddy.security.SecureVault

/**
 * Turns raw network / HTTP failures into something Buddy would actually say.
 *
 * Before this, the user saw things like
 * "CLEARTEXT communication to 127.0.0.1 not permitted by network security policy".
 */
object FriendlyErrors {

    fun explain(error: Throwable?): String {
        val provider = SecureVault.getProvider().label

        return when (error) {
            null -> "Something went sideways. Want to try that again?"

            is NoProviderConfiguredException ->
                "I need an AI key for that one. Tap the gear up top and add one - " +
                    "or ask me something I can do on this phone."

            is MissingKeyException ->
                "I don't have a key for ${error.providerLabel} yet. Want to add one in Settings?"

            is HttpException -> when (error.code) {
                401, 403 -> "$provider says that key isn't valid. Mind checking it in Settings?"
                404 -> "$provider doesn't know that model name. Want to pick a different one?"
                429 -> "We're going a bit fast for $provider - give it a few seconds."
                in 500..599 -> "$provider is having a moment. Try again in a sec."
                else -> "$provider said: ${error.message}"
            }

            else -> {
                val raw = error.message.orEmpty()
                when {
                    raw.contains("CLEARTEXT", ignoreCase = true) ->
                        "I couldn't reach a local model server over plain HTTP. " +
                            "If you're not running one on this phone, pick a real provider in Settings."
                    raw.contains("No AI provider", ignoreCase = true) ->
                        "I need an AI key for that one. Tap the gear up top."
                    raw.contains("Unable to resolve host", ignoreCase = true) ||
                        raw.contains("UnknownHost", ignoreCase = true) ->
                        "I can't reach the internet right now, boss."
                    raw.contains("timeout", ignoreCase = true) ->
                        "That took too long - the network's being slow."
                    raw.isBlank() -> "Something went sideways. Want to try that again?"
                    else -> raw
                }
            }
        }
    }
}
