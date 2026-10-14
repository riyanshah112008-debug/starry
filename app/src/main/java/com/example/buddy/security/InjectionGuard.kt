package com.example.buddy.security

/**
 * Sanitizes untrusted screen text captured by AccessibilityService before
 * passing it into the LLM prompt context.
 *
 * Strips common prompt-injection patterns (e.g., "ignore previous instructions",
 * fake system role headers, or embedded JSON tool payloads) and truncates
 * overly long node labels to keep token usage compact and safe.
 */
object InjectionGuard {

    private const val MAX_NODE_TEXT_LENGTH = 160
    private const val MAX_SNAPSHOT_LENGTH = 4500

    private val SUSPICIOUS_PATTERNS = listOf(
        Regex("(?i)ignore\\s+(all\\s+)?(previous|prior|above)\\s+instructions?"),
        Regex("(?i)disregard\\s+(all\\s+)?(previous|prior|above)\\s+instructions?"),
        Regex("(?i)system\\s*prompt\\s*:"),
        Regex("(?i)you\\s+are\\s+now\\s+"),
        Regex("(?i)new\\s+instructions?\\s*:"),
        Regex("(?i)\\{\\s*\"tool\"\\s*:"),
        Regex("(?i)<\\|im_start\\|>|<\\|im_end\\|>|\\[INST\\]|\\[/INST\\]")
    )

    /**
     * Sanitizes a single accessibility node's text or contentDescription.
     */
    fun sanitizeNodeText(raw: CharSequence?): String {
        if (raw.isNullOrBlank()) return ""
        var cleaned = raw.toString()
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        for (pattern in SUSPICIOUS_PATTERNS) {
            cleaned = pattern.replace(cleaned, "[filtered]")
        }

        if (cleaned.length > MAX_NODE_TEXT_LENGTH) {
            cleaned = cleaned.take(MAX_NODE_TEXT_LENGTH) + "..."
        }
        return cleaned
    }

    /**
     * Wraps a full screen snapshot JSON string in explicit untrusted-data markers
     * and enforces a hard character ceiling.
     */
    fun wrapUntrustedSnapshot(snapshotJson: String): String {
        val clipped = if (snapshotJson.length > MAX_SNAPSHOT_LENGTH) {
            snapshotJson.take(MAX_SNAPSHOT_LENGTH) + "...(truncated)"
        } else {
            snapshotJson
        }
        return "<untrusted_screen_data>\n$clipped\n</untrusted_screen_data>"
    }
}
