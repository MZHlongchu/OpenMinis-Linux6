package com.openminis.app.data.model

sealed class LLMError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidApiKey(val detail: String = "") : LLMError(if (detail.isBlank()) "Invalid API key" else "Invalid API key: $detail")
    class NetworkError(cause: Throwable) : LLMError("Network error: ${cause.message}", cause)
    class ProviderError(val detail: String) : LLMError("Provider error: $detail")
    class DecodingError(cause: Throwable) : LLMError("Decoding error: ${cause.message}", cause)
    class RateLimited(
        val retryAfterSeconds: Int? = null,
        val detail: String = "",
    ) : LLMError(rateLimitedMessage(retryAfterSeconds, detail))
    class TransientError(val detail: String) : LLMError("Transient error: $detail")
    class Cancelled : LLMError("Request was cancelled")
    class Unknown(cause: Throwable?) : LLMError("Unknown error: ${cause?.message}", cause)

    /** Pure connectivity failure — the request didn't land at all. */
    val isNetworkError: Boolean get() = this is NetworkError

    /** Worth retrying on the same provider (bounded backoff). */
    val isRetryable: Boolean get() = this is NetworkError || this is TransientError || this is RateLimited

    /** Should immediately fall back to the next model in the group — same model won't help. */
    val isFallbackable: Boolean get() = when (this) {
        is RateLimited, is InvalidApiKey -> true
        is ProviderError ->
            detail.contains("[429]") ||
                Regex("""\[5\d{2}\]""").containsMatchIn(detail)
        else -> false
    }

    /** Short user-facing reason shown when a fallback engages. */
    val fallbackReason: String
        get() = when (this) {
            is RateLimited -> "Rate limited"
            is InvalidApiKey -> "Invalid API key"
            is ProviderError -> "Provider error"
            is TransientError -> "Transient error"
            is NetworkError -> "Network error"
            is DecodingError -> "Decoding error"
            is Cancelled -> "Cancelled"
            is Unknown -> "Unknown error"
        }
}

private fun rateLimitedMessage(retryAfterSeconds: Int?, detail: String): String {
    val base = if (retryAfterSeconds != null) "Rate limited — retry after ${retryAfterSeconds}s"
    else "Rate limited — please try again later"
    val snip = detail.trim().replace('\n', ' ').replace('\r', ' ')
        .replace(Regex("\\s+"), " ").take(160)
    if (snip.isBlank()) return base
    if (snip.equals("rate limited", ignoreCase = true)) return base
    if (snip.equals("too many requests", ignoreCase = true)) return base
    return "$base — $snip"
}
