package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import kotlin.random.Random

/**
 * Shared 429 Retry-After parsing, capacity-body classification, and bounded
 * exponential backoff for model calls.
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

    fun jitterMs(maxExclusive: Long = 800L): Long =
        if (maxExclusive <= 1L) 0L else Random.nextLong(0, maxExclusive)

    /**
     * Relay 429s are often permanent (quota, no channel, unknown model) rather
     * than a 60s window. Those must not be retried on the same key.
     */
    fun isPermanentCapacityBody(body: String): Boolean {
        if (body.isBlank()) return false
        val t = body.lowercase()
        return PERMANENT_CAPACITY_MARKERS.any { t.contains(it) }
    }

    fun map429(body: String, retryAfterHeader: String? = null): LLMError {
        val err = if (isPermanentCapacityBody(body)) {
            LLMError.ProviderError("[429] ${snippet(body, 240)}")
        } else {
            LLMError.RateLimited(parseSeconds(retryAfterHeader, body), snippet(body, 160))
        }
        android.util.Log.w(
            "Minis.HTTP",
            "HTTP 429 → ${err.javaClass.simpleName}: ${err.message}",
        )
        return err
    }

    private fun snippet(body: String, max: Int): String =
        body.trim().replace('\n', ' ').replace('\r', ' ').take(max)

    private val PERMANENT_CAPACITY_MARKERS = listOf(
        "no_available_providers",
        "no available providers",
        "no available channel",
        "no available channels",
        "no available",
        "model_not_found",
        "model not found",
        "insufficient_quota",
        "insufficient quota",
        "quota exceeded",
        "quota_exceeded",
        "billing",
        "payment required",
        "无可用渠道",
        "无可用",
        "额度",
        "余额",
        "负载已饱和",
    )

    private val RETRY_AFTER_IN_BODY =
        Regex("""(?i)retry[_-]?after["'\s:=]+(\d+)""")
}
