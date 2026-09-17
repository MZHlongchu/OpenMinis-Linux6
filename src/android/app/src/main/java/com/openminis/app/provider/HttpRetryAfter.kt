package com.openminis.app.provider

/**
 * Shared 429 Retry-After parsing and bounded exponential backoff for model calls.
 */
object HttpRetryAfter {
    const val DEFAULT_MAX_RETRIES = 5
    val DELAYS_SEC = intArrayOf(1, 2, 4, 8, 16)

    fun parseSeconds(header: String?, body: String? = null): Int? {
        header?.trim()?.toIntOrNull()?.let { return it.coerceIn(1, 120) }
        if (body.isNullOrBlank()) return null
        val match = RETRY_AFTER_IN_BODY.find(body) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 120)
    }

    fun delaySeconds(
        attemptZeroBased: Int,
        retryAfterSeconds: Int? = null,
        schedule: IntArray = DELAYS_SEC,
    ): Int {
        val backoff = when {
            schedule.isEmpty() -> 2
            attemptZeroBased < 0 -> schedule.first()
            attemptZeroBased < schedule.size -> schedule[attemptZeroBased]
            else -> (schedule.last() * 2).coerceAtMost(32)
        }
        return maxOf(backoff, retryAfterSeconds ?: 0).coerceIn(1, 120)
    }

    private val RETRY_AFTER_IN_BODY =
        Regex("""(?i)retry[_-]?after["'\s:=]+(\d+)""")
}
