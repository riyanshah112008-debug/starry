package com.example.buddy.ai

/**
 * A hard ceiling on what one conversation is allowed to spend, so a runaway
 * agent loop can never drain an API key again. Tokens are estimated from text
 * length, which is plenty accurate for guarding (and deliberately over-counts).
 */
class TokenBudget(
    maxCalls: Int = 8,
    maxTokens: Int = 9000
) {
    private var callLimit: Int = maxCalls
    private var tokenLimit: Int = maxTokens
    private var callsUsed = 0
    private var tokensUsed = 0

    /** Lets Settings change the ceiling at runtime. */
    @Synchronized
    fun setLimits(maxCalls: Int, maxTokens: Int) {
        callLimit = maxCalls
        tokenLimit = maxTokens
    }

    @Synchronized
    fun canSpend(estimatedTokens: Int): Boolean =
        callsUsed < callLimit && tokensUsed + estimatedTokens <= tokenLimit

    @Synchronized
    fun charge(estimatedTokens: Int) {
        callsUsed++
        tokensUsed += estimatedTokens
    }

    @Synchronized
    fun remainingCalls(): Int = (callLimit - callsUsed).coerceAtLeast(0)

    @Synchronized
    fun snapshot(): String = "$callsUsed/$callLimit calls - ~$tokensUsed/$tokenLimit tokens"

    @Synchronized
    fun reset() {
        callsUsed = 0
        tokensUsed = 0
    }

    companion object {
        /** Rough token estimate; over-counts a little on purpose. */
        fun estimate(vararg texts: String): Int {
            val chars = texts.sumOf { it.length }
            return chars / 3 + 64
        }
    }
}
