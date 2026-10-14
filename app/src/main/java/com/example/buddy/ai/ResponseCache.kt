package com.example.buddy.ai

/**
 * Tiny in-memory cache so asking the exact same thing twice costs nothing.
 * Keyed by provider + model + system prompt + conversation fingerprint.
 */
class ResponseCache(
    private val ttlMillis: Long = 5 * 60_000L,
    private val maxEntries: Int = 32
) {
    private data class Entry(val decision: BuddyDecision, val at: Long)

    private val map = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(key: String): BuddyDecision? {
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() - entry.at > ttlMillis) {
            map.remove(key)
            return null
        }
        return entry.decision
    }

    @Synchronized
    fun put(key: String, decision: BuddyDecision) {
        map[key] = Entry(decision, System.currentTimeMillis())
    }

    @Synchronized
    fun clear() = map.clear()

    companion object {
        fun key(
            providerId: String,
            model: String,
            systemPrompt: String,
            turns: List<ChatTurn>
        ): String {
            val sb = StringBuilder(providerId).append('|').append(model).append('|')
            sb.append(systemPrompt.hashCode()).append('|')
            turns.forEach { sb.append(it.role).append(':').append(it.content.hashCode()).append(';') }
            return sb.toString()
        }
    }
}
