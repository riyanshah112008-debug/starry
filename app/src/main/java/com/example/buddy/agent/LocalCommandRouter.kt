package com.example.buddy.agent

import android.content.Context
import com.google.gson.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Answers the common, unambiguous commands with **zero API calls**.
 *
 * This is the single biggest fix for "the API got exhausted in 5 seconds":
 * "open chrome", "text 5551234 I'm late", "remember my wifi is blue123",
 * "what's my wifi password", "go home", "scroll down", "volume up",
 * "turn on the torch" and "what time is it" are all resolved on-device and
 * never touch a model.
 */
class LocalCommandRouter(context: Context) {

    private val app: Context = context.applicationContext

    sealed class Plan {
        /** Run this tool immediately - no model involved. */
        data class Tool(val tool: String, val args: JsonObject) : Plan()

        /** Buddy can answer this from local knowledge. */
        data class Reply(val text: String) : Plan()

        /** Needs a model. */
        object NeedsModel : Plan()
    }

    fun route(raw: String): Plan {
        val text = raw.trim()
        if (text.isEmpty()) return Plan.NeedsModel
        val lower = text.lowercase(Locale.ROOT)

        // ---- small talk -------------------------------------------------
        when (lower) {
            "hi", "hey", "hello", "yo", "hiya" -> return Plan.Reply("Hey boss. What're we up to?")
            "thanks", "thank you", "ty", "cheers" -> return Plan.Reply("Anytime, boss.")
            "good morning" -> return Plan.Reply("Morning, mate. What's first?")
            "good night", "goodnight" -> return Plan.Reply("Night, boss. I'll be here.")
            "how are you", "how's it going", "hows it going" ->
                return Plan.Reply("Running smooth. What do you need?")
        }

        // ---- clock / date ----------------------------------------------
        if (lower.contains("what time") || lower.contains("the time")) {
            val now = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            return Plan.Reply("It's $now, boss.")
        }
        if (lower.contains("what day") || lower == "date" || lower.contains("today's date")) {
            val day = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())
            return Plan.Reply("Today's $day.")
        }

        // ---- navigation ------------------------------------------------
        when (lower) {
            "back", "go back", "go backwards" -> return tool("back")
            "home", "go home", "home screen", "go to home" -> return tool("home")
            "scroll down", "scroll forward", "swipe up" ->
                return tool("scroll", "direction" to "forward")
            "scroll up", "scroll backward", "swipe down" ->
                return tool("scroll", "direction" to "backward")
        }

        // ---- volume / torch -------------------------------------------
        VOLUME.find(text)?.let { m ->
            return tool("volume", "action" to m.groupValues[2].lowercase(Locale.ROOT))
        }
        TORCH.find(text)?.let { m ->
            return tool("flashlight", "on" to m.groupValues[2].equals("on", ignoreCase = true))
        }

        // ---- remember / recall ----------------------------------------
        REMEMBER.find(text)?.let { m ->
            val key = m.groupValues[3].trim()
            val value = m.groupValues[5].trim()
            val explicit = lower.startsWith("remember") ||
                lower.startsWith("save") || lower.startsWith("note")
            val looksLikeFact = STRICT_FACT_KEYS.any { key.lowercase(Locale.ROOT).contains(it) } &&
                key.split(" ").size <= 4 && value.length <= 40
            if (key.isNotEmpty() && value.isNotEmpty() && (explicit || looksLikeFact)) {
                return tool("remember", "key" to key, "value" to value)
            }
        }
        RECALL.find(text)?.let { m ->
            val key = m.groupValues[2].trim()
            if (key.isNotEmpty()) return tool("recall", "key" to key)
        }

        // ---- open a URL ------------------------------------------------
        OPEN_URL.find(text)?.let { m ->
            return tool("open_url", "url" to m.groupValues[2])
        }

        // ---- send an SMS ----------------------------------------------
        SEND_SMS.find(text)?.let { m ->
            return tool(
                "send_sms",
                "number" to m.groupValues[2].trim().replace(Regex("[\\s-]"), ""),
                "text" to m.groupValues[3].trim()
            )
        }

        // ---- open an app ----------------------------------------------
        OPEN_APP.find(text)?.let { m ->
            val requested = m.groupValues[3].trim()
            val pkg = resolvePackage(requested)
            if (pkg != null) return tool("open_app", "package" to pkg)
            // Confident we understood, but the app isn't installed - answer
            // locally instead of paying for a model round-trip.
            val lowered = requested.lowercase(Locale.ROOT)
            val looksLikeAppName = requested.isNotEmpty() &&
                !lowered.startsWith("a ") &&
                !lowered.startsWith("the ") &&
                !lowered.startsWith("my ") &&
                !lowered.startsWith("command")
            if (looksLikeAppName && requested.split(" ").size <= 3) {
                return Plan.Reply("Hmm, I couldn't find \"$requested\" installed, boss.")
            }
        }

        return Plan.NeedsModel
    }

    private fun tool(name: String, vararg pairs: Pair<String, Any>): Plan.Tool {
        val obj = JsonObject()
        pairs.forEach { (k, v) ->
            when (v) {
                is Boolean -> obj.addProperty(k, v)
                is Number -> obj.addProperty(k, v)
                else -> obj.addProperty(k, v.toString())
            }
        }
        return Plan.Tool(name, obj)
    }

    /** Maps friendly names to package ids, then falls back to a label search. */
    private fun resolvePackage(requested: String): String? {
        val cleaned = requested.lowercase(Locale.ROOT).trim()
        KNOWN_APPS[cleaned]?.let { return it }

        return try {
            val pm = app.packageManager
            val installed = pm.getInstalledApplications(0)
            val exact = installed.firstOrNull { info ->
                val label = pm.getApplicationLabel(info).toString().lowercase(Locale.ROOT)
                label == cleaned || label.replace(" ", "") == cleaned.replace(" ", "")
            }
            val prefix = exact ?: installed.firstOrNull { info ->
                val label = pm.getApplicationLabel(info).toString().lowercase(Locale.ROOT)
                cleaned.length >= 3 && label.startsWith(cleaned)
            }
            prefix?.packageName
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val VOLUME =
            Regex("^(turn |set )?volume (up|down|max|mute)\$", RegexOption.IGNORE_CASE)
        private val TORCH =
            Regex("^(turn |switch )?(on|off) (the )?(flash|flashlight|torch)\$", RegexOption.IGNORE_CASE)
        private val REMEMBER =
            Regex("^(remember |save |note )?(that )?my (.+?) (is|=) (.+)\$", RegexOption.IGNORE_CASE)
        private val RECALL =
            Regex("^(what'?s|what is|whats|do you remember) my (.+?)\\??\$", RegexOption.IGNORE_CASE)
        private val OPEN_URL = Regex(
            "^(open |go to |launch |navigate to )(https?://\\S+|www\\.\\S+|\\S+\\.(com|org|net|io|dev|ai|co|app|me)(/\\S*)?)\$",
            RegexOption.IGNORE_CASE
        )
        private val SEND_SMS = Regex(
            "^(text|sms|message|send a text to|send a message to) ([+\\d][\\d\\s-]{5,}) (.+)\$",
            RegexOption.IGNORE_CASE
        )
        private val OPEN_APP =
            Regex("^(open|launch|start|run) (the )?(.+?)( app)?\$", RegexOption.IGNORE_CASE)

        /**
         * Only secrets/codes qualify for the implicit "my X is Y" form, so
         * "my phone is slow" isn't filed away as a memory. Anything at all can
         * still be stored by saying "remember ...".
         */
        private val STRICT_FACT_KEYS = listOf(
            "password", "passcode", "pin", "puk", "ssid",
            "wifi password", "wifi name", "network password",
            "birthday", "bday", "license plate", "licence plate"
        )

        /** The handful of apps people actually ask for by name. */
        val KNOWN_APPS: Map<String, String> = mapOf(
            "chrome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "google" to "com.google.android.googlequicksearchbox",
            "search" to "com.google.android.googlequicksearchbox",
            "whatsapp" to "com.whatsapp",
            "messages" to "com.google.android.apps.messaging",
            "sms" to "com.google.android.apps.messaging",
            "youtube" to "com.google.android.youtube",
            "maps" to "com.google.android.apps.maps",
            "gmail" to "com.google.android.gm",
            "email" to "com.google.android.gm",
            "photos" to "com.google.android.apps.photos",
            "gallery" to "com.google.android.apps.photos",
            "settings" to "com.android.settings",
            "calculator" to "com.google.android.calculator",
            "clock" to "com.google.android.deskclock",
            "calendar" to "com.google.android.calendar",
            "spotify" to "com.spotify.music",
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "telegram" to "org.telegram.messenger",
            "termux" to "com.termux",
            "play store" to "com.android.vending",
            "store" to "com.android.vending"
        )
    }
}
