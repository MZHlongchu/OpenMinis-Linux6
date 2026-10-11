package com.openminis.app.data.model

/**
 * [T-llm-error-classification] Unified 11-kind error taxonomy modelled after
 * shiyi-agent's `llm_client.dart` error classification. Every variant carries
 * an [actionableHint] that tells the user (or the agent on retry) what concrete
 * next step restores progress — instead of dumping a raw status code.
 *
 * [T-core-model-relocation] 合并 1.14：本文件随数据模型搬进 `:core:model`
 * （纯 JVM）。上游 1.14 在同一处加了结构化 `httpStatus` 与
 * [httpServerErrorStatus] / [isHttpServerError]（`T-fallback-5xx-status` /
 * `T-android-503-fallback`），本仓库这套分类法（Timeout 分相、
 * ContextLengthExceeded、ContentFiltered、credentialAccepted、actionableHint）
 * 全部保留，取并集：结构化状态优先，旧的散文扫描只留在 [isFallbackable]
 * 里继续兼容。
 */
sealed class LLMError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** API key is missing, revoked, or expired. */
    class InvalidApiKey(
        val detail: String = "",
        /**
         * [T-llm-error-401-model-scope] True when the SAME credential was
         * accepted by this host recently (see
         * [com.openminis.app.provider.CredentialAcceptance]). The refusal is
         * then about *this model* — typically a gateway whose upstream channel
         * for it is broken — and not about the key, so both the message and the
         * hint must say so instead of sending the user to regenerate a
         * credential that was never the problem.
         */
        val credentialAccepted: Boolean = false,
    ) : LLMError(
        when {
            credentialAccepted && detail.isNotBlank() -> detail
            credentialAccepted ->
                "Model refused by the gateway (the credential itself was accepted)"
            detail.isBlank() -> "Invalid API key"
            else -> "Invalid API key: $detail"
        },
    )

    /** TCP/DNS layer failed before the HTTP request was sent. */
    class NetworkError(cause: Throwable) : LLMError("Network error: ${cause.message}", cause)

    /**
     * The request was sent but the server didn't respond within the deadline.
     * Distinct from [NetworkError] (which never left the client) and
     * [TransientError] (which is a server-returned 5xx / connection drop).
     * [phase] distinguishes connect vs read vs ttfb so the retry strategy can
     * decide whether same-provider retry is worthwhile.
     */
    class Timeout(
        val detail: String,
        val phase: TimeoutPhase = TimeoutPhase.READ,
    ) : LLMError("Request timed out: $detail") {
        enum class TimeoutPhase { CONNECT, READ, TTFB }
    }

    /**
     * The provider returned a 4xx / 5xx that doesn't match a more specific
     * category. Prefer [RateLimited], [ContextLengthExceeded],
     * [ContentFiltered], or [InvalidApiKey] when the server gives enough signal.
     *
     * [T-fallback-5xx-status] [httpStatus] carries the real response code when
     * the error came from an HTTP response, null for errors that did not
     * (decoding, safety rejections, non-JSON image bodies). Structured on
     * purpose: the mappers format their messages three different ways
     * (`[503] …` for OpenAI JSON bodies, `HTTP 522: …` for non-JSON bodies,
     * `Gemini API error 500: …`, `[overloaded_error] …` for Anthropic), and
     * parsing the code back out of prose is exactly how a Cloudflare 522 with
     * an HTML body stopped triggering group fallback.
     */
    class ProviderError(val detail: String, val httpStatus: Int? = null) :
        LLMError("Provider error: $detail")

    /** JSON parsing / SSE framing / schema mismatch on the response body. */
    class DecodingError(cause: Throwable) : LLMError("Decoding error: ${cause.message}", cause)

    class RateLimited(
        val retryAfterSeconds: Int? = null,
        val detail: String = "",
    ) : LLMError(rateLimitedMessage(retryAfterSeconds, detail))

    /**
     * The prompt (or current conversation) exceeds the model's context window.
     * Actionable: the agent or user can compact, offload, or truncate.
     */
    class ContextLengthExceeded(
        val detail: String,
        val requestedTokens: Int? = null,
        val maxTokens: Int? = null,
    ) : LLMError(contextLengthMessage(detail, requestedTokens, maxTokens))

    /**
     * The server rejected the request because of a content policy violation.
     * Not retryable on the same model — rephrase or switch providers.
     */
    class ContentFiltered(val detail: String) : LLMError(
        "Content filtered: $detail",
    )

    /**
     * Server-side hiccup (5xx, connection dropped mid-stream, empty response).
     * [stalledAfterFirstEvent] is true when the stream had already produced at
     * least one chunk and then went quiet past the idle budget — i.e. a
     * mid-stream stall rather than a never-started timeout. The retry layer
     * uses it to decide whether to resume from the partial text ([T-stall-resume])
     * or regenerate from scratch.
     *
     * [T-android-503-fallback] Retry-worthy failure. [httpStatus] carries the
     * response code ONLY when the failure was a real HTTP response from the
     * provider; it stays null for failures that never got one — a dropped
     * connection, a TTFB timeout, an empty stream. That distinction is what
     * lets group fallback treat a provider-side 5xx as "this model is down,
     * try the next one" without also treating the user's own dead network as
     * a reason to switch models (a switch that could not possibly help).
     *
     * Parameter order follows upstream 1.14 (`detail`, `httpStatus`), so a
     * positional `TransientError(msg, 503)` written against either side still
     * binds; our own call sites pass `stalledAfterFirstEvent` by name, so it
     * moving to third place changes nothing for them.
     */
    class TransientError(
        val detail: String,
        val httpStatus: Int? = null,
        val stalledAfterFirstEvent: Boolean = false,
    ) : LLMError("Transient error: $detail")

    /** Coroutine-level cancellation (user stopped, timeout, etc.). */
    class Cancelled : LLMError("Request was cancelled")

    /** Catch-all for unrecognised failures. */
    class Unknown(cause: Throwable?) : LLMError("Unknown error: ${cause?.message}", cause)

    // ── classification helpers ──────────────────────────────────────────

    /** Pure connectivity failure — the request didn't land at all. */
    val isNetworkError: Boolean get() = this is NetworkError || this is Timeout

    /** Worth retrying on the same provider (bounded exponential backoff). */
    val isRetryable: Boolean
        get() = this is NetworkError || this is Timeout ||
            this is TransientError || this is RateLimited

    /** Should immediately fall back to the next model in the group. */
    val isFallbackable: Boolean
        get() = when (this) {
            is RateLimited, is InvalidApiKey, is ContextLengthExceeded,
            is ContentFiltered -> true
            is Timeout -> true
            // [T-android-503-fallback-union] EVERY ProviderError falls back —
            // `this is RateLimited || this is InvalidApiKey || this is
            // ProviderError`, byte-for-byte what upstream 1.14 has
            // (upstream-1.14:src/android/app/src/main/java/com/openminis/app/
            // data/model/LLMError.kt:56) and what this port had before the
            // merge (`4ef29002` same file, same line).
            //
            // The merge narrowed this branch to "429 / structured 5xx / prose
            // 5xx", which silently dropped every provider-answered 4xx refusal
            // from group fallback — including GH#368's "[400] The
            // reasoning_text must be passed back to the API when tool calls are
            // present" — while the comment above it still promised that "an
            // error that used to engage fallback must keep doing so".
            //
            // Upstream's rationale for the wide branch still holds and is why
            // it exists at all (T-android-fallback-providererror): every
            // fallback candidate is a DIFFERENT ProviderInstance with its own
            // credential and upstream channel, so a refusal on instance A says
            // nothing about instance B; a revoked Anthropic key should move the
            // turn to the OpenAI member rather than kill it. When the chain is
            // exhausted the error is still re-thrown verbatim, so nothing is
            // swallowed. The structured status stays authoritative for
            // [httpServerErrorStatus] and for the same-provider retry ladder —
            // it is simply not a gate on fallback.
            is ProviderError -> true
            else -> false
        }

    /**
     * [T-android-503-fallback] A confirmed HTTP 5xx from the provider, as a
     * structured status rather than a substring of the message.
     *
     * Two shapes carry one. [ProviderError] is how a 5xx that is known to be
     * permanent for this model arrives (e.g. OpenAI's 503 +
     * `no_available_providers`), and its status is only recoverable from the
     * `[503] …` prefix its mapper writes. [TransientError] carries the code
     * directly in [TransientError.httpStatus].
     *
     * Anchored parsing matters here: the previous check scanned the whole
     * detail with `[5][0-9]{2}`, so a message mentioning "5000 tokens" read as
     * a server error. Only the leading `[<code>]` written by `mapHttpError` is
     * accepted.
     */
    val httpServerErrorStatus: Int?
        get() = when (this) {
            is TransientError -> httpStatus?.takeIf { it in 500..599 }
            // Structured code first; the `[<code>]` prefix is only a fallback
            // for a ProviderError built somewhere that did not thread the
            // status through. It never matches a bare number inside prose.
            is ProviderError -> httpStatus?.takeIf { it in 500..599 }
                ?: LEADING_STATUS.find(detail)
                    ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 500..599 }
            else -> null
        }

    /** True for a failure the provider answered with a 5xx status code. */
    val isHttpServerError: Boolean get() = httpServerErrorStatus != null

    private companion object {
        /**
         * Matches only a status code in the leading `[<code>]` position that
         * `mapHttpError` emits — never a number that merely appears somewhere
         * inside the provider's prose.
         */
        val LEADING_STATUS = Regex("""^\[(\d{3})\]""")
    }

    /** Short user-facing reason shown when a fallback engages. */
    val fallbackReason: String
        get() = when (this) {
            is RateLimited -> "Rate limited"
            is InvalidApiKey ->
                if (credentialAccepted) "Model refused by gateway" else "Invalid API key"
            is Timeout -> "Timed out"
            is ContextLengthExceeded -> "Context window exceeded"
            is ContentFiltered -> "Content filtered"
            is ProviderError -> "Provider error"
            is TransientError -> "Transient error"
            is NetworkError -> "Network error"
            is DecodingError -> "Decoding error"
            is Cancelled -> "Cancelled"
            is Unknown -> "Unknown error"
        }

    /**
     * One-sentence next step the user (or a self-correcting agent) can take.
     * Mirrors shiyi-agent's per-error guidance so the chat UI shows concrete
     * advice instead of a bare HTTP code.
     */
    val actionableHint: String
        get() = when (this) {
            is InvalidApiKey -> when {
                // Checked before the 403 branch: a credential we have seen
                // accepted is the stronger evidence, and its remedy (pick
                // another model) is different from either key branch.
                credentialAccepted ->
                    "Your API key is fine — this server accepted it moments ago. It refused THIS MODEL, " +
                        "which usually means the gateway's upstream channel for it is broken or the model " +
                        "is not on your plan. Pick a different model; only check the key if every model fails."
                detail.contains("403", ignoreCase = true) || detail.contains("forbidden", ignoreCase = true) ->
                    "The credential was accepted but access was denied (403): this model or region may not be allowed on your plan. Check the provider's model access settings."
                else -> "Check your API key in Settings → Providers, or regenerate it at your provider's dashboard."
            }
            is NetworkError -> "Check your internet connection and try again."
            is Timeout -> when (phase) {
                Timeout.TimeoutPhase.CONNECT -> "The server didn't respond — check your network or try a different provider."
                Timeout.TimeoutPhase.READ -> "The model is taking too long to finish — try a faster model or shorten your message."
                Timeout.TimeoutPhase.TTFB -> "The model hasn't started responding — the provider may be overloaded. Try again in a moment."
            }
            is RateLimited -> if (retryAfterSeconds != null) "Rate limited — retry after ${retryAfterSeconds}s." else "Rate limited — wait a moment and try again."
            is ContextLengthExceeded -> buildString {
                append("Your conversation is too long for this model")
                if (maxTokens != null) append(" ($maxTokens token limit)")
                append(". Use /compact to summarise older messages, or start a new chat.")
            }
            is ContentFiltered -> "The model's content policy blocked this request. Try rephrasing your message."
            is ProviderError -> "The provider returned an error. Tap to see details, or try again later."
            is TransientError -> "A temporary server error occurred. Retrying…"
            is DecodingError -> "The model's response couldn't be parsed. Retrying…"
            is Cancelled -> "The request was stopped."
            is Unknown -> "An unexpected error occurred. Tap for details."
        }
}

private fun contextLengthMessage(detail: String, requested: Int?, max: Int?): String {
    val sb = StringBuilder("Context length exceeded")
    if (requested != null) sb.append(" — requested $requested tokens")
    if (max != null) sb.append(" of $max max")
    val snip = detail.trim().replace('\n', ' ').replace(Regex("\\s+"), " ").take(160)
    if (snip.isNotBlank() && !snip.equals("context length exceeded", ignoreCase = true)) {
        sb.append(" — $snip")
    }
    return sb.toString()
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