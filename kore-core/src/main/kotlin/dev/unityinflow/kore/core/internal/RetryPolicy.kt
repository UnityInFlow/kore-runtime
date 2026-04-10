package dev.unityinflow.kore.core.internal

import kotlin.math.min
import kotlin.math.pow

/**
 * Configures exponential backoff retry behavior for LLM calls.
 * Per D-20: semaphore is released before delay — delay is OUTSIDE permit scope.
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 500L,
    val maxDelayMs: Long = 30_000L,
    val backoffMultiplier: Double = 2.0,
) {
    /** Returns the delay in milliseconds for the given attempt index (0-based). */
    fun delayFor(attempt: Int): Long =
        min(
            maxDelayMs,
            (initialDelayMs * backoffMultiplier.pow(attempt.toDouble())).toLong(),
        )
}
