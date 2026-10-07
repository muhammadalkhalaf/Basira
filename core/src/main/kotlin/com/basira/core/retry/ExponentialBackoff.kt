package com.basira.core.retry

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Bounded exponential backoff with full jitter.
 *
 * The delay for attempt `n` (starting at 1) is a random value in `[0, min(max, base * factor^(n-1))]`
 * when [jitter] is enabled, which spreads reconnect storms across clients.
 *
 * @property baseDelayMillis delay ceiling for the first retry.
 * @property maxDelayMillis absolute delay ceiling for any retry.
 * @property factor growth factor between attempts.
 * @property maxAttempts maximum number of retries; [delayForAttempt] returns `null` beyond it.
 * @property jitter whether full jitter is applied.
 * @property random randomness source, injectable for deterministic tests.
 */
class ExponentialBackoff(
    val baseDelayMillis: Long,
    val maxDelayMillis: Long,
    val factor: Double = 2.0,
    val maxAttempts: Int,
    val jitter: Boolean = true,
    private val random: Random = Random.Default,
) {
    init {
        require(baseDelayMillis > 0) { "baseDelayMillis must be positive" }
        require(maxDelayMillis >= baseDelayMillis) { "maxDelayMillis must be >= baseDelayMillis" }
        require(factor >= 1.0) { "factor must be >= 1" }
        require(maxAttempts >= 0) { "maxAttempts must not be negative" }
    }

    /**
     * Computes the delay before retry number [attempt].
     *
     * @param attempt 1-based retry number.
     * @return delay in milliseconds, or `null` when the retry budget is exhausted.
     */
    fun delayForAttempt(attempt: Int): Long? {
        if (attempt < 1 || attempt > maxAttempts) return null
        val exponential = baseDelayMillis * factor.pow(attempt - 1)
        val ceiling = min(maxDelayMillis.toDouble(), exponential).toLong()
        return if (jitter) random.nextLong(ceiling / 2, ceiling + 1) else ceiling
    }
}
