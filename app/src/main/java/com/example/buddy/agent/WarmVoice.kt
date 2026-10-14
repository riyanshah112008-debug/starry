package com.example.buddy.agent

/**
 * Deterministic, warm one-liners.
 *
 * This exists purely to save money: when a tool succeeds we already know what
 * happened, so there's no reason to pay a model to phrase it.
 */
object WarmVoice {

    fun summarize(tool: String, success: Boolean, output: String): String {
        if (!success) return "Ah, that didn't work: $output"
        return when (tool) {
            "open_app" -> "Done - that's up, boss."
            "open_url" -> "Opening that for you."
            "click_text" -> "Tapped it."
            "type_text" -> "Typed it in."
            "scroll" -> "Done, scrolled."
            "back" -> "Went back."
            "home" -> "Home screen, mate."
            "volume" -> "Volume's handled."
            "flashlight" -> "Torch ${if (output.endsWith("on", ignoreCase = true)) "on" else "off"}."
            "set_alarm" -> "Alarm's set."
            "send_sms" -> "Sent it, boss."
            "remember" -> "Locked it in. Won't forget that."
            "recall" -> {
                val value = output.substringAfter(" = ", output)
                if (value == output) output else "It's $value, mate."
            }
            "run_shell" -> "Ran it: $output"
            else -> "Okay, done: $output"
        }
    }
}
