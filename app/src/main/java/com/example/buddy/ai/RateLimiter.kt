package com.example.buddy.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sliding-window rate limiter enforcing a maximum of [maxCallsPerMinute] (default 30)
 * calls per 60-second window. Suspends the caller until a slot becomes available.
 */
class RateLimiter(
    private val maxCallsPerMinute: Int = 30,
    private val windowMillis: Long = 60_000L
) {
    private val mutex = Mutex()
    private val callTimestamps = ArrayDeque<Long>()

    suspend fun acquire() {
        while (true) {
            val waitTimeMs: Long = mutex.withLock {
                val now = System.currentTimeMillis()
                while (callTimestamps.isNotEmpty() && now - callTimestamps.first() >= windowMillis) {
                    callTimestamps.removeFirst()
                }

                if (callTimestamps.size < maxCallsPerMinute) {
                    callTimestamps.addLast(now)
                    0L
                } else {
                    val oldest = callTimestamps.first()
                    (windowMillis - (now - oldest)).coerceAtLeast(50L)
                }
            }

            if (waitTimeMs <= 0L) {
                return
            }
            delay(waitTimeMs)
        }
    }
}
