package com.openminis.app.provider.openai

import android.util.Base64
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.harness.agent.HttpRetryAfter
import com.openminis.app.provider.ProviderKeyGate
import com.openminis.app.data.model.LLMMediaAttachment
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.provider.thinking.ThinkingResolveContext
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.SamplingIdentity
import com.openminis.app.provider.SamplingPolicy
import com.openminis.app.provider.VendorMedia
import com.openminis.app.provider.VendorMediaKind
import com.openminis.app.provider.applyUserAgentOverride
import com.openminis.app.provider.safeOptString
import com.openminis.app.provider.ZenDisguise
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import com.openminis.app.provider.failOnSilentEmptyCompletion


/**
 * [T-android-stale-conn-retry-hang] Streaming time-to-first-byte
 * budget: response HEADERS must arrive within this window. Does NOT
 * bound the SSE body — a flowing stream stays unlimited.
 *
 * [T-android-ttfb-upload-split / #188] This window now starts at
 * `requestBodyEnd` (upload complete), NOT at call start — a large
 * multimodal body over a slow proxy could burn the whole budget just
 * uploading, so a healthy-but-slow server looked like a dead
 * connection. See [STREAM_UPLOAD_CAP_MS] for the upload-phase bound.
 *
 * Raised 30s -> 120s (user report, TG soyo): complex agent turns,
 * locally-hosted large models, and slow relay endpoints can legitimately
 * take well over 30s to emit the first response header, and the old
 * budget cancelled those healthy requests as false timeouts. readTimeout
 * is 600s, so 120s stays comfortably inside it while still catching a
 * genuinely dead connection.
 *
 * [T-stream-watchdog-budget-parity] `internal` (not file-private) so
 * [OpenAIProvider.streamFirstChunkBudgetMs] can declare this pair as the
 * provider's pre-first-chunk budget and keep the OUTER stream watchdog
 * from pre-empting it with its flat 45s/90s phase 1.
 */
internal const val STREAM_TTFB_TIMEOUT_MS = 120_000L

/**
 * [T-android-ttfb-upload-split / #188] Overall ceiling for the UPLOAD
 * phase (call start → requestBodyEnd). Keeps the watchdog effective if
 * the body upload itself wedges (writeTimeout is 30s per write op, but a
 * trickling proxy can dribble bytes forever without tripping it). Chosen
 * generous so a legitimately large body over a slow link isn't cut off:
 * the writeTimeout(30s) already bounds a fully-stalled socket; this only
 * catches the slow-but-never-idle case. Once upload completes the tighter
 * [STREAM_TTFB_TIMEOUT_MS] takes over.
 *
 * [T-stream-watchdog-budget-parity] `internal` so
 * [OpenAIProvider.streamFirstChunkBudgetMs] can add it to the TTFB budget
 * above — together they are the longest "no chunk yet" window a healthy
 * OpenAI request may legitimately need.
 */
internal const val STREAM_UPLOAD_CAP_MS = 120_000L

/**
 * [T-android-body-idle-watchdog] Post-header body watches. The TTFB watchdog
 * STOPS once headers arrive, and a flowing SSE stream has no total-duration
 * limit — so a body that stalls AFTER headers hangs on OkHttp's readTimeout
 * (600s = 10 stuck minutes) or, when the gateway keeps the tunnel warm with
 * keepalive pings, on nothing at all (70-minute hang observed in the
 * opencode2dsh reference; same failure shape: headers fine, quick test fine,
 * context fine, model "thinking" forever). Two watches cover the body:
 *  - RAW-BYTE idle (4 min): no bytes at all — dead tunnel. readTimeout already
 *    catches this at 600s; this tightens it and never false-trips a trickling
 *    line (ANY byte resets it).
 *  - CONTENT idle (10 min): bytes (keepalive pings) but no content/thinking/
 *    tool chunk — alive-but-silent. A legit thinking silence tops out ~3:10
 *    (documented; OpenAIProvider.kt T-android-openai-codex-timeout), so 10 min
 *    is headroom-safe.
 * Both cancel the call + evict the connection and surface as TransientError
 * with stalledAfterFirstEvent=true, so the retry ladder's stall-resume carries
 * the partial text/thinking forward instead of regenerating from scratch.
 */
private const val STREAM_BODY_IDLE_MS = 240_000L
private const val STREAM_CONTENT_IDLE_MS = 600_000L

/**
 * [T-openai-terminal-drain] How long a stream that ALREADY reached its
 * terminal event (Chat `finish_reason` / Responses `response.completed`) may
 * stay open without further content before we hang up.
 *
 * The `finish_reason` and `response.completed` handlers only recorded state —
 * they did not stop the read loop — so a relay that reports completion and
 * then leaves the socket open (some keep it warm with SSE comments) kept the
 * turn alive until the CONTENT-idle watch fired at 600s and classified a
 * COMPLETE response as a mid-stream stall: the retry ladder stall-resumed and
 * the turn was re-run / duplicated. Once the terminal event is in, silence
 * means "the relay is done talking", so the window shrinks to a trailing
 * usage-block drain (Chat Completions sends `usage` in the chunk AFTER the
 * `finish_reason` chunk under stream_options.include_usage). Any content
 * chunk still resets this window, so a relay that flushes trailing content
 * late is not cut off; `[DONE]` / EOF end the loop immediately as before.
 *
 * [T-openai-terminal-drain-anchor] The window is measured from the TERMINAL
 * EVENT (or a later content chunk) — see [terminalDrainExpired]. Measuring it
 * from `lastContentNanos` instead made the deadline already expired at the
 * instant a silent reasoning phase (minutes with no content chunk) ended in
 * `finish_reason`, so the trailing usage chunk was cut off. That is the
 * opposite of what this constant exists for.
 */
private const val STREAM_TERMINAL_DRAIN_MS = 15_000L

/**
 * [T-openai-terminal-drain-anchor] Has the trailing drain window expired?
 *
 * Anchor = the LATER of the terminal event and the last content chunk:
 *  - **terminal event** (not the last content chunk): a model that reasons
 *    silently for minutes and then emits `finish_reason` leaves the content
 *    timestamp far in the past, so anchoring on it tripped the drain the moment
 *    the terminal event arrived — cutting the very usage/tail `data:` lines the
 *    window exists to collect, i.e. the opposite of [StreamTerminalDrainTest]'s
 *    intent ("the trailing usage block must not be lost").
 *  - **content chunk after the terminal event** still pushes the anchor forward,
 *    preserving the documented "a relay flushing trailing content late is not
 *    cut off" behaviour.
 *
 * Pure, so the arithmetic is testable without a 15s wall clock.
 */
internal fun terminalDrainExpired(
    nowNanos: Long,
    terminalEventAtNanos: Long,
    lastContentNanos: Long,
    drainMs: Long = STREAM_TERMINAL_DRAIN_MS,
): Boolean {
    if (terminalEventAtNanos == 0L) return false
    val anchor = maxOf(terminalEventAtNanos, lastContentNanos)
    return (nowNanos - anchor) / 1_000_000L >= drainMs
}

/**
 * [T-stream-content-type-sniff] How many leading bytes are peeked (and pushed
 * back) to decide whether a body is SSE or a single JSON object.
 */
private const val SNIFF_BYTES = 256

/**
 * Read enough of [stream] to classify it, then push every byte back so the
 * caller can still consume the body normally — including incrementally.
 *
 * Two traps this avoids:
 * - **Byte-at-a-time reads.** Looping over `read()` for a single byte would
 *   issue one socket read per byte, so this does bulk reads instead.
 * - **Partial reads.** TCP may hand over `dat` of `data: {…}`; classifying on
 *   that would wrongly conclude "not SSE". So keep reading until the trimmed
 *   prefix is long enough to contain a full SSE field name, or a line break,
 *   or the stream ends.
 */
internal fun peekPrefix(stream: java.io.PushbackInputStream): String {
    val buf = ByteArray(SNIFF_BYTES)
    var n = 0
    while (n < buf.size) {
        val read = stream.read(buf, n, buf.size - n)
        if (read == -1) break
        n += read
        val trimmed = String(buf, 0, n, Charsets.UTF_8).trimStart()
        if (trimmed.length >= 6 || '\n' in trimmed) break
    }
    if (n > 0) stream.unread(buf, 0, n)
    return String(buf, 0, n, Charsets.UTF_8)
}

/**
 * Pure: does this body prefix carry SSE framing?
 *
 * The Content-Type header is not a substitute. Relays behind a proxy that hides
 * or rewrites the header answer `application/json` while streaming perfectly
 * valid `data: {…}` events, and feeding that body to `JSONObject()` produces
 * "Value data of type java.lang.String cannot be converted to JSONObject" —
 * the tokenizer's first value is the bare word `data`. The converse is just as
 * real: a gateway that ignores `stream=true` returns one JSON object with an
 * `text/event-stream`-less content type, which is why the JSON branch exists at
 * all. Both directions are decided by the bytes, not the header.
 */
internal fun looksLikeSse(prefix: String): Boolean {
    val head = prefix.trimStart()
    return head.startsWith("data:") ||
        head.startsWith("event:") ||
        head.startsWith("id:") ||
        head.startsWith("retry:") ||
        // An SSE comment / keep-alive line, e.g. ": ping".
        head.startsWith(":")
}


internal fun OpenAIProvider.rawStreamMessage(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
        stream: Boolean,
    ): Flow<LLMStreamChunk> = callbackFlow {
        // [T-android-body-idle-watchdog] Arrival timestamps, declared up front
        // so the send chokepoint below can update the content one. The raw one
        // is updated by a counting stream wrapper at the reader site (EVERY
        // read resets the idle window — a slow-trickling line never false-
        // trips); during a stalled body nobody updates it, which is exactly
        // the signal the watchdog polls.
        val lastByteNanos = java.util.concurrent.atomic.AtomicLong(System.nanoTime())
        val lastContentNanos = java.util.concurrent.atomic.AtomicLong(System.nanoTime())
        // Content-bearing send chokepoint: the CONTENT-idle watch polls
        // lastContentNanos, which updates ONLY on chunks that carry model
        // output (text/thinking/tool events) — SSE keepalive comments and
        // empty pings never touch it, so a tunnel kept warm-but-silent still
        // trips the watch. Started/Usage/Finished are lifecycle, not content.
        suspend fun sendTracked(chunk: LLMStreamChunk) {
            when (chunk) {
                is LLMStreamChunk.Text,
                is LLMStreamChunk.ThinkingDelta,
                is LLMStreamChunk.ReasoningContent,
                is LLMStreamChunk.ToolUseStart,
                is LLMStreamChunk.ToolInputDelta,
                is LLMStreamChunk.ToolCallComplete,
                is LLMStreamChunk.MediaAttachment,
                -> lastContentNanos.set(System.nanoTime())
                else -> Unit
            }
            send(chunk)
        }
        val body = if (isCodexImageModel) {
            // [T-gpt-image2-codex-backend-route-android] gpt-image-2 on an
            // OpenAI OAuth (Codex) instance is driven through the Codex backend
            // image_generation tool (chatgpt.com/backend-api/codex/responses),
            // NOT the public /v1/images/generations Images API — a Codex OAuth
            // token lacks the api.model.images.request scope and the Images API
            // 401s with "Missing scopes: api.model.images.request" (see
            // codex_oauth_image_generation_summary.md §11.1). Aligns with iOS
            // commit 2dd35a14. The Codex-backend route is selected purely by
            // `isCodexImageModel` (isOAuth + model id) — it does NOT require a
            // codexAccountId (the Chatgpt-Account-Id header is optional and its
            // absence doesn't 401), which is exactly the iOS fall-through bug
            // this avoids. Android has no Images-API path at all, so an OAuth
            // gpt-image-2 request can never reach /v1/images/generations.
            //
            // Special image-generation body — not Chat Completions, not the
            // normal Responses tool shape.
            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[ModelUseRoute] gpt-image-2 → route=codex-backend " +
                    "url=chatgpt.com/backend-api/codex/responses isOAuth=$isOAuth " +
                    "hasAccountId=${codexAccountId != null}",
            )
            buildCodexImageBody(messages)
        } else if (usesChatCompletionsAPI) {
            buildRequestBody(messages, systemPrompt, maxTokens, stream = stream, temperature = temperature, imageParts = imageParts, tools = tools, thinkingLevel = thinkingLevel)
        } else {
            buildResponsesAPIBody(
                messages, systemPrompt, maxTokens, stream = stream,
                imageParts = imageParts, tools = tools, thinkingLevel = thinkingLevel,
                temperature = temperature,
            )
        }
        // T302: serialize the request body exactly once. Pre-T302 we called
        // body.toString() three times per request (debug log + OAuth byte
        // build + non-OAuth RequestBody), each materialising a fresh 30+ MB
        // string for long agent loops with heavy tool outputs. Stacked, that
        // pushed memory-tight devices (HONOR PTP-AN00) past the OOM line.
        // [T-android-mem-probe-trust] Bracket the serialisation itself. The
        // 2026-08-15 field log recorded `bodyLen=2342987` on the request before
        // a process death but nothing about its memory cost, so "did building
        // this body kill us?" could not be answered from the log. We measure
        // around the call and report the realised length, so an OOM thrown here
        // now arrives with an attributed stack instead of anonymously.
        val memBefore = com.openminis.app.diagnostics.MemorySnapshot.capture()
        val serStartNs = System.nanoTime()
        val bodyStr = try {
            body.toString()
        } catch (t: Throwable) {
            com.openminis.app.diagnostics.LargeAllocProbe.report(
                "openai.body.toString", -1, "model=${model.id} messages=${messages.size}",
                memBefore, serStartNs, failure = t,
            )
            throw t
        }
        if (bodyStr.length >= com.openminis.app.diagnostics.LargeAllocProbe.NOTABLE_BYTES) {
            com.openminis.app.diagnostics.LargeAllocProbe.report(
                "openai.body.toString", bodyStr.length.toLong(),
                "model=${model.id} messages=${messages.size}",
                memBefore, serStartNs, failure = null,
            )
        }
        var request = buildRequest(bodyStr)
        if (com.openminis.app.provider.ZenDisguise.isZenHost(basePath)) {
            // [T-zen-session-seed] Seed from the typed message list, not the
            // serialized body. The old applyToBody path re-parsed the full
            // body (a second multi-MB materialisation per request on long
            // loops — the exact cost the T302 single-serialization rule exists
            // to prevent), its role mapping folded the JSON "system" message
            // into USER so the seed was actually the system prompt (drifting
            // every turn on a dynamic one, contrary to sessionId's contract),
            // and Responses bodies (no "messages" array) collapsed onto one
            // constant seed shared by every such request. The typed list's
            // first USER message is the seed for both body shapes.
            //
            // [T-zen-ua-preserve] buildRequest already applied the
            // per-provider customUserAgent, so the disguise UA is applied only
            // when the user configured none — a paid Zen instance keeps its
            // own UA. The x-opencode-* headers are always applied: they have
            // no user-configurable counterpart, and Authorization is not among
            // them.
            request = ZenDisguise.apply(
                request.newBuilder(),
                messages,
                disguiseUserAgent = customUserAgent.isNullOrBlank(),
            ).build()
        }
        val headerMap = mutableMapOf<String, String>()
        for (name in request.headers.names()) {
            headerMap[name] = request.headers[name] ?: ""
        }
        val startTime = System.currentTimeMillis()

        // T321: request-side diagnostic log. Header *keys* + Authorization
        // presence (no token values), and a body summary (counts only — never
        // the message text/images/tool-result bytes).
        run {
            val authPresent = request.headers["Authorization"] != null
            val msgsLen = body.optJSONArray("messages")?.length()
                ?: body.optJSONArray("input")?.length() ?: 0
            val toolsLen = body.optJSONArray("tools")?.length() ?: 0
            val temp = if (body.has("temperature")) body.optDouble("temperature") else null
            val maxTok = body.optInt("max_completion_tokens", body.optInt("max_tokens", -1))
            val hasSystem = body.has("instructions") ||
                (body.optJSONArray("messages")?.let { arr ->
                    var found = false
                    for (i in 0 until arr.length()) {
                        if (arr.optJSONObject(i)?.optString("role") == "system") { found = true; break }
                    }
                    found
                } ?: false)
            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[T321] → REQ url=${request.url} model=${model.id} stream=${body.optBoolean("stream", false)} " +
                    "headerKeys=${request.headers.names()} authPresent=$authPresent " +
                    "messages=$msgsLen tools=$toolsLen temp=$temp maxTokens=$maxTok hasSystem=$hasSystem " +
                    "useResponsesAPI=${!usesChatCompletionsAPI} bodyLen=${bodyStr.length}"
            )
        }

        // [T-android-ttfb-upload-split / #188] Attach per-call state the trace
        // listener fills in (upload-done timestamp + physical connection) so the
        // watchdog below can (a) start the TTFB clock only after upload and
        // (b) evict THIS ONE connection on timeout.
        val watchState = CallWatchState()
        val call = client.newCall(request.newBuilder().tag(CallWatchState::class.java, watchState).build())
        // [T-android-stale-conn-retry-hang] Time-to-first-byte watchdog. A
        // request written into a dead pooled h2 tunnel (local proxy socket
        // survives a network flap) produces NO further events — no headers,
        // no failure — until the 600s read timeout, so the UI showed
        // "thinking" forever.
        //
        // [T-android-ttfb-upload-split / #188] The window is split in two so
        // slow-but-healthy uploads aren't mistaken for a dead connection:
        //   1. UPLOAD phase (call start → requestBodyEnd): bounded loosely by
        //      STREAM_UPLOAD_CAP_MS. writeTimeout(30s) already catches a fully
        //      stalled socket; this only catches slow-trickle-forever.
        //   2. TTFB phase (requestBodyEnd → response headers): the tight
        //      STREAM_TTFB_TIMEOUT_MS budget, measured FROM upload completion.
        // On timeout we cancel the call AND evict just this connection so the
        // auto-retry gets a fresh one (a sibling session's connection is never
        // touched — no evictAll). Once headers arrive the watchdog stops and a
        // flowing SSE stream has NO total-duration limit, as before.
        val ttfbTimedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val headersArrived = java.util.concurrent.atomic.AtomicBoolean(false)
        // [T-android-body-idle-watchdog] The watchdog's post-header trip phase
        // ("body-idle" = raw-byte watch, "content-idle" = content watch), read
        // by the read loop's catch to map the socket failure to a
        // stall-resume-carrying TransientError.
        val bodyIdlePhase = java.util.concurrent.atomic.AtomicReference<String?>(null)
        // [T-openai-terminal-drain] Nonzero once the stream reached its terminal
        // event (Chat `finish_reason`, Responses `response.completed`). From
        // then on the response is complete and only the short
        // [STREAM_TERMINAL_DRAIN_MS] trailing drain applies — see the constant.
        val terminalEventAtNanos = java.util.concurrent.atomic.AtomicLong(0L)
        // [T-openai-terminal-drain] drain 自己把 socket 关掉时置位（区别于 relay 主动
        // 关闭）：读取循环无论把这次关闭读成 IOException 还是干净的 EOF，尾部冲刷
        // 都要能报出「尾部是被我们砍断的」，而不是把可能丢掉的事件当正常收尾。
        // Set when the terminal drain ITSELF tore the socket down (rather than the relay
        // closing it) — the stream tail reports whatever that cut off.
        val terminalDrainClosedSocket = java.util.concurrent.atomic.AtomicBoolean(false)
        val callStartNanos = System.nanoTime()
        val ttfbWatchdog = launch {
            val pollMs = 250L
            var timedOutPhase: String? = null
            while (!headersArrived.get()) {
                val uploadDoneAt = watchState.uploadDoneAtNanos.get()
                val nowNanos = System.nanoTime()
                if (uploadDoneAt == 0L) {
                    // Still uploading (or connecting) — loose upload-phase cap.
                    val elapsedMs = (nowNanos - callStartNanos) / 1_000_000L
                    if (elapsedMs >= STREAM_UPLOAD_CAP_MS) { timedOutPhase = "upload"; break }
                } else {
                    // Upload finished — tight TTFB budget measured from that point.
                    val sinceUploadMs = (nowNanos - uploadDoneAt) / 1_000_000L
                    if (sinceUploadMs >= STREAM_TTFB_TIMEOUT_MS) { timedOutPhase = "ttfb"; break }
                }
                delay(pollMs)
            }
            if (timedOutPhase != null && !headersArrived.get()) {
                ttfbTimedOut.set(true)
                val budgetS = if (timedOutPhase == "ttfb") STREAM_TTFB_TIMEOUT_MS / 1000 else STREAM_UPLOAD_CAP_MS / 1000
                com.openminis.app.logging.AppLogger.warning(
                    "OpenAIProvider",
                    "[T-android-ttfb-upload-split] no response headers ($timedOutPhase phase, ${budgetS}s) — cancelling call + evicting connection (stale pooled connection?)",
                )
                call.cancel()
                // Targeted eviction: close ONLY this call's physical connection so
                // the retry can't reuse it. Never evictAll — concurrent sessions
                // may hold healthy connections in the shared pool.
                try {
                    watchState.connection.get()?.socket()?.close()
                } catch (_: Throwable) {
                    // Best-effort; call.cancel() already unblocks execute().
                }
                return@launch
            }
            // [T-android-body-idle-watchdog] Headers arrived — keep watching the
            // BODY (the old watchdog stopped here and a stalled body hung on
            // readTimeout 600s — or forever when the gateway's keepalive pings
            // kept resetting it). RAW-idle catches the dead tunnel; CONTENT-idle
            // catches the warm-but-silent one. Either trip → cancel + evict →
            // the read loop's socket failure maps to a stall-resume
            // TransientError and the retry ladder carries the partial output.
            while (true) {
                val now = System.nanoTime()
                val sinceByteMs = (now - lastByteNanos.get()) / 1_000_000L
                val sinceContentMs = (now - lastContentNanos.get()) / 1_000_000L
                if (sinceByteMs >= STREAM_BODY_IDLE_MS) { timedOutPhase = "body-idle"; break }
                // [T-openai-terminal-drain] After the terminal event silence is
                // no longer "alive but silent" — the response is finished, the
                // relay is just holding the socket. Bound the trailing drain
                // instead of waiting out the 600s content-idle (which killed a
                // complete turn as a stall and made the retry ladder re-run it).
                //
                // [T-openai-terminal-drain-anchor] The window is counted from the
                // TERMINAL EVENT, not from `lastContentNanos`: a turn that spent
                // minutes reasoning silently and then emitted `finish_reason` had
                // its content timestamp already older than the window, so the
                // drain tripped on the first poll and every trailing `data:` line
                // (the Chat Completions usage chunk, a late tool-call item) was
                // cut off. See [terminalDrainExpired].
                val terminalAt = terminalEventAtNanos.get()
                if (terminalAt != 0L) {
                    if (terminalDrainExpired(now, terminalAt, lastContentNanos.get())) {
                        timedOutPhase = "terminal-drain"; break
                    }
                } else if (sinceContentMs >= STREAM_CONTENT_IDLE_MS) {
                    timedOutPhase = "content-idle"; break
                }
                delay(pollMs)
            }
            if (timedOutPhase == "terminal-drain") {
                // [T-openai-terminal-drain] The turn's terminal event already
                // arrived, so this is NOT a stall: close the idle socket without
                // setting [bodyIdlePhase], which keeps the read loop's catch from
                // mapping it to a stall-resume TransientError (it ends the turn
                // cleanly on the finish reason it already has).
                com.openminis.app.logging.AppLogger.info(
                    "OpenAIProvider",
                    "[T-openai-terminal-drain] terminal event seen, no further content for " +
                        "${STREAM_TERMINAL_DRAIN_MS / 1000}s — closing the idle connection",
                )
                // [T-openai-terminal-drain] 先置位再 cancel：读取循环随后无论收到
                // IOException 还是 EOF，都能从这条标记知道尾部是自己砍断的。
                terminalDrainClosedSocket.set(true)
                call.cancel()
                try {
                    watchState.connection.get()?.socket()?.close()
                } catch (_: Throwable) {
                    // Best-effort; call.cancel() already unblocks the read.
                }
                return@launch
            }
            bodyIdlePhase.set(timedOutPhase)
            com.openminis.app.logging.AppLogger.warning(
                "OpenAIProvider",
                "[T-android-body-idle-watchdog] $timedOutPhase (" +
                    "${STREAM_BODY_IDLE_MS / 1000}s raw / ${STREAM_CONTENT_IDLE_MS / 1000}s content) — " +
                    "cancelling call + evicting connection; retry ladder takes over with stall-resume",
            )
            call.cancel()
            try {
                watchState.connection.get()?.socket()?.close()
            } catch (_: Throwable) {
                // Best-effort; call.cancel() already unblocks execute().
            }
        }
        val response = try {
            call.execute()
        } catch (e: IOException) {
            if (ttfbTimedOut.get()) {
                throw LLMError.TransientError(
                    "no response from server (${STREAM_TTFB_TIMEOUT_MS / 1000}s TTFB) — check network/proxy",
                )
            }
            throw e
        } finally {
            headersArrived.set(true)
            // [T-android-body-idle-watchdog] The watchdog is NOT cancelled here:
            // it transitions to the body phase (raw-byte idle + content idle)
            // and keeps watching until the stream ends — the read loop's
            // finally / awaitClose cancel it.
        }
        // T321: response-side diagnostic log (status + select header values).
        run {
            val rh = response.headers
            val ct = rh["content-type"] ?: ""
            val rid = rh["x-request-id"] ?: rh["openai-request-id"] ?: ""
            val openAiHdrs = rh.names().filter { it.lowercase().startsWith("openai-") }
            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[T321] ← RSP status=${response.code} content-type=$ct x-request-id=$rid " +
                    "headerKeys=${rh.names()} openAiHeaders=${openAiHdrs.associateWith { rh[it] ?: "" }}"
            )
        }
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            // T321: full error body — debug-only, but kept unconditional here
            // since non-2xx is rare and the body is critical for diagnosis.
            com.openminis.app.logging.AppLogger.error(
                "OpenAIProvider",
                "[T321] ← HTTP ${response.code} error body: $errorBody"
            )
            response.close()
            // T302: skip the LLMRequestLog write entirely on release builds —
            // not just to avoid the (already-truncated) retention cost, but to
            // dodge constructing the Entry / headerMap copies that go with it.
            if (com.openminis.app.BuildConfig.DEBUG) {
                com.openminis.app.debug.LLMRequestLog.add(
                    com.openminis.app.debug.LLMRequestLog.Entry(
                        provider = "openai",
                        requestURL = request.url.toString(),
                        requestHeaders = headerMap,
                        requestBody = bodyStr,
                        durationMs = System.currentTimeMillis() - startTime,
                        responseStatusCode = response.code,
                        responseBody = errorBody.take(2000),
                    )
                )
            }
            throw mapHttpError(response.code, errorBody, response.header("Retry-After"))
        }
        if (com.openminis.app.BuildConfig.DEBUG) {
            com.openminis.app.debug.LLMRequestLog.add(
                com.openminis.app.debug.LLMRequestLog.Entry(
                    provider = "openai",
                    requestURL = request.url.toString(),
                    requestHeaders = headerMap,
                    requestBody = bodyStr,
                    durationMs = System.currentTimeMillis() - startTime,
                    responseStatusCode = response.code,
                )
            )
        }

        // Some compatible gateways ignore `stream=true` and return regular
        // Chat Completions JSON. Parse it instead of feeding JSON to the SSE
        // parser and misclassifying the response as silently empty.
        //
        // [T-stream-content-type-sniff] The Content-Type header alone cannot
        // make that call. Relays fronted by a proxy that hides or rewrites the
        // header answer `application/json` while streaming perfectly good SSE
        // (`data: {…}` lines) — measured 6/6 on one public gateway. Trusting the
        // header sent that body into JSONObject(), whose tokenizer reads the
        // bare word `data` and dies with "Value data of type java.lang.String
        // cannot be converted to JSONObject", which escaped the agent loop as a
        // raw JSONException. So: sniff the body, and only take the JSON path
        // when the header AND the bytes both say JSON.
        //
        // The peeked bytes are pushed back, so the SSE path below still streams
        // incrementally instead of buffering the whole response.
        val responseContentType = response.header("Content-Type").orEmpty().lowercase()
        val pushback = response.body?.byteStream()
            ?.let { java.io.PushbackInputStream(it, SNIFF_BYTES) }
        val bodyLooksLikeSse = pushback?.let { looksLikeSse(peekPrefix(it)) } ?: false
        if (usesChatCompletionsAPI &&
            !responseContentType.contains("text/event-stream") &&
            !bodyLooksLikeSse
        ) {
            try {
                val raw = pushback?.reader()?.readText().orEmpty()
                val json = try {
                    JSONObject(raw)
                } catch (e: Exception) {
                    // Never let a raw JSONException reach the user: it names a
                    // tokenizer internals problem, not the actual mismatch.
                    throw LLMError.DecodingError(
                        IllegalStateException(
                            "expected a JSON object body (Content-Type: " +
                                "${responseContentType.ifBlank { "absent" }}) but got: " +
                                raw.take(120).replace('\n', ' '),
                            e,
                        ),
                    )
                }
                sendTracked(LLMStreamChunk.Started)
                val choice = json.optJSONArray("choices")?.optJSONObject(0)
                val message = choice?.optJSONObject("message")
                val text = message?.optString("content", "").orEmpty()
                if (text.isNotEmpty()) sendTracked(LLMStreamChunk.Text(text))
                json.optJSONObject("usage")?.let {
                    sendTracked(LLMStreamChunk.Usage(parseChatCompletionsUsage(it)))
                }
                sendTracked(LLMStreamChunk.Finished(choice?.optString("finish_reason", null)))
            } finally {
                response.close()
            }
            channel.close()
            awaitClose {
                try { call.cancel() } catch (_: Exception) {}
                try { response.close() } catch (_: Exception) {}
            }
            return@callbackFlow
        }

        if (pushback == null) {
            throw LLMError.DecodingError(
                IllegalStateException("streaming response had no body"),
            )
        }
        // [T-android-body-idle-watchdog] Counting stream wrapper: EVERY read
        // (any byte, however small) resets the raw-idle window; during a
        // stalled body nobody reads, which is exactly the signal the watchdog
        // polls.
        val trackedPushback = object : java.io.FilterInputStream(pushback) {
            override fun read(): Int = super.read().also { if (it >= 0) lastByteNanos.set(System.nanoTime()) }
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also { if (it > 0) lastByteNanos.set(System.nanoTime()) }
        }
        val reader = BufferedReader(InputStreamReader(trackedPushback))

        // [T-codex-gpt-image2-oauth-android] gpt-image-2: the Codex backend
        // streams the image as a base64 blob (PNG / JPEG / WebP) inside the SSE
        // `image_generation_call` output item; there's no incremental text/tool
        // stream to parse. handleCodexImageStream parses the SSE line-by-line,
        // pulls the image (or a structured failure), emits a MediaAttachment
        // chunk, and finishes — bypassing the chat/tool SSE state machine below.
        if (isCodexImageModel) {
            try {
                handleCodexImageStream(reader) { chunk -> trySend(chunk) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cancel("Image stream error", mapError(e))
            } finally {
                reader.close()
                response.close()
            }
            channel.close()
            awaitClose {
                try { call.cancel() } catch (_: Exception) {}
                try { response.close() } catch (_: Exception) {}
            }
            return@callbackFlow
        }

        // Chat Completions: tool calls are streamed as deltas keyed by index.
        data class ToolCallAccumulator(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder(), var started: Boolean = false, var lastSentArgsLen: Int = 0)
        val toolCallAccumulators = mutableMapOf<Int, ToolCallAccumulator>()
        // Responses API: function_call items are keyed by their item_id (fc_…). We store
        // the call_id separately because the agent loop needs the call_id to correlate
        // tool results, but the next request must echo back the item_id verbatim — so we
        // emit a combined "callId|fcId" identifier that splitResponsesAPIIds() unpacks.
        data class ResponsesToolCallAccumulator(var callId: String = "", var name: String = "", val args: StringBuilder = StringBuilder(), var started: Boolean = false, var lastSentArgsLen: Int = 0)
        val responsesToolCalls = mutableMapOf<String, ResponsesToolCallAccumulator>()
        // [T-d4-one-card-per-call 一条逻辑调用只宣告一次] 下游只在 ToolUseStart 分支
        // 建卡片，而 ToolCallIdDedupe 会把第二次同 id 宣告改名成 "<id>-2" **另建一张
        // 卡**；ToolCallComplete 只会为宣告时的身份发一次，那张多出来的卡片就永远停在
        // STREAMING（转圈不结束）。同一个 call_id 被 `output_item.added` 宣告两次（两个
        // item_id），或同一个 item_id 被重复宣告，都会踩到。所以累加器只按「已宣告的
        // item_id」存一份，其余 item_id 记为别名（别名 → 已宣告 id），参数增量与 done
        // 都落到已宣告的身份上。
        // Alias table: item_id → the item_id this logical call was actually announced
        // under. Never a second card, never a second dispatch.
        val responsesToolCallAliases = mutableMapOf<String, String>()
        // [T-d4-one-card-per-call] 消费掉一个已宣告身份，连同指向它的全部别名。
        fun consumeResponsesToolCall(key: String): ResponsesToolCallAccumulator? {
            val acc = responsesToolCalls.remove(key) ?: return null
            for (alias in responsesToolCallAliases.filterValues { it == key }.keys.toList()) {
                responsesToolCallAliases.remove(alias)
            }
            return acc
        }
        // [T-openai-terminal-drain] Responses 工具调用的可观测计数：`response.completed`
        // 的 output 里声明了几条 function_call、我们真正发了几条 ToolCallComplete。
        // 尾部被砍断时这两个数与冲刷数一起进 warn —— 从未 `added` 过的 done 丢掉时
        // declared=1 / completed=0，日志里一眼可见。
        var declaredFunctionCallItems = 0
        var responsesToolCallsCompleted = 0
        // One-shot info log the first time the Responses API streams a reasoning
        // delta — useful for confirming the Thinking pipeline is wired up when
        // diagnosing "I set thinking high but see nothing" reports.
        var sawReasoningDelta = false
        // Accumulates the opaque reasoning_content blob across SSE deltas so we
        // can echo the exact server-emitted value back on the next turn. Tracked
        // separately from ThinkingDelta concatenation because DeepSeek V4 emits
        // `reasoning_content: ""` legitimately (non-thinking turns) and the
        // empty string must round-trip — fabricating placeholder text causes
        // the model to in-context-learn it (see T249 / T257 history).
        val reasoningAccum = StringBuilder()
        var sawReasoningField = false
        // [T-android-think-prefix-stream] One parser per streaming turn. Handles
        // ALL models: a turn with no `<think>` prefix passes through verbatim, so
        // there is no vendor allowlist to keep in sync (the old `hasThinkTags`
        // heuristic only armed extraction for DashScope/qwen ids, which meant
        // MiniMax M3 relied on the mid-stream `text.contains("<think>")` fallback).
        val thinkParser = ThinkPrefixStreamParser()

        // T321: turn-level SSE counters for empty-response triage.
        var sseEventCount = 0
        var contentLen = 0
        // [T-android-incomplete-keep-partial] True once a text delta carried at
        // least one non-whitespace character. See the response.incomplete handler.
        var sawNonBlankText = false
        var reasoningLen = 0
        var toolCallEventCount = 0
        var sawFinishReason = false
        var sawUsageBlock = false
        // [T-d4-terminal-break-narrowing 收窄终态后的注释行 break] 终态事件之后
        // 见到的 SSE 注释行计数（任何真正的 `data:` 事件行都会清零），读取循环里
        // 收窄后的收尾 break 就看它。
        // Comment lines seen since the terminal event, reset by any real `data:`
        // event — the counter behind the narrowed end-of-tail break below.
        var postTerminalCommentLines = 0
        // [T-d4-terminal-tail-structural 结构性收尾判据] 这里曾用一个布尔标记记录
        // 「usage 是否落在终态事件之后的独立 chunk 里」并拿它当「Chat 形状」的代理，
        // 好让 Chat 形状一条注释行即收尾。这个代理是错的：
        // 判据挂在 usage 的**位置**上，relay 把 usage 放进第二个
        // `response.completed`（第一个不带）时形状即被误判，第一条 keep-alive 就会
        // 收尾，而 relay 随后补发的 `response.output_item.done`（整条工具调用）就此
        // 丢掉 —— 正是这条收尾规则要防的事故。现在判据纯结构：终态事件 + 尾部核算
        // （sawUsageBlock，只看「有没有」不看「在哪」）之后，**第二条连续注释行**才
        // 收尾；`[DONE]` 与空的 `data:` 行各自照旧。任何真实事件行都把注释计数清零，
        // 所以 `data:` 行永不因这条规则被截断。
        // The end-of-tail break is structural — TWO consecutive comment lines after the
        // terminal event, never "the usage block happened to land after the terminal
        // event" (see the read loop).
        // [T-openai-terminal-drain] 终态后的 socket 关闭属于预期收尾，但「尾部就此被
        // 切断」必须可观测：relay 还没来得及补发的 output_item.done（尤其是从未
        // `added` 宣告过的调用，连累加器都没有）会在这里丢失，此前只有一条 info
        // 日志，事后完全查不出工具调用为何没执行。
        // Set when a post-terminal read failed outright (as opposed to the relay closing
        // the body cleanly) — together with [terminalDrainClosedSocket] it drives the
        // tail-cut warning after the flush below.
        var postTerminalIoError = false
        // [T-android-responses-missing-finished] Hoisted out of the try block so
        // the stream tail can emit Finished for streams that end WITHOUT a
        // `data: [DONE]` sentinel — see the emission site after the read loop.
        var finishReason: String? = null
        // True once the [DONE] branch has emitted Finished, so the tail below
        // does not send a second one.
        var sentFinished = false

        try {
            sendTracked(LLMStreamChunk.Started)

            // Branch streaming parser based on API format
            val isResponsesAPI = !usesChatCompletionsAPI

            // [T-openai-terminal-drain] The read is wrapped so the terminal-drain
            // socket teardown (see the watchdog) reads as EOF instead of an error:
            // once the terminal event is in, a closed socket is the expected end of
            // an already-complete turn. A genuine body-idle trip still propagates
            // (bodyIdlePhase != null) so the stall-resume mapping below is intact.
            while (true) {
                val l = try {
                    reader.readLine()
                } catch (e: IOException) {
                    if (terminalEventAtNanos.get() == 0L || bodyIdlePhase.get() != null) throw e
                    // [T-openai-terminal-drain] 终态后的读取失败不是「正常 EOF」：relay
                    // 可能还没来得及补发 output_item.done（甚至是从未 added 过的调用，
                    // 连累加器都没有）。此前这里只有一条 info，事后查不出工具调用为何
                    // 静默消失，所以升到 warn 并把「尾巴被切断」的现场写清楚。
                    // A post-terminal read failure is NOT a clean EOF — say so at warn
                    // and name what was still owed.
                    postTerminalIoError = true
                    com.openminis.app.logging.AppLogger.warning(
                        "OpenAIProvider",
                        "[T-openai-terminal-drain] connection failed after the terminal event — " +
                            "ending the turn with finishReason=$finishReason " +
                            "cutByDrain=${terminalDrainClosedSocket.get()} " +
                            "pendingResponsesToolCalls=${responsesToolCalls.size} " +
                            "declaredFunctionCalls=$declaredFunctionCallItems " +
                            "completedFunctionCalls=$responsesToolCallsCompleted " +
                            "error=${e.javaClass.simpleName}: ${e.message} — an output_item.done " +
                            "the relay had not flushed yet (incl. a call it never announced) is lost",
                    )
                    null
                } ?: break
                // [T-d4-terminal-tail-structural 结构性收尾判据] 终态后的收尾只认两
                // 条**连续**注释行：以前按「usage 落在终态事件之后的独立 chunk」推断
                // Chat 形状，好让 Chat 一条注释行就收尾，但这是拿 usage 的**位置**当
                // 形状代理 —— relay 发两次 `response.completed`（第一次不带 usage、
                // 第二次带）时终态后的 usage 让判据误判成 Chat 形状，第一条 keep-alive
                // 即收尾，relay 随后补发的 `response.output_item.done`（整条工具调用）
                // 被丢掉，正是这条收尾规则要防的事故。现在两个形状统一按结构收尾：
                // 终态事件 + 尾部核算（sawUsageBlock）之后，第一条注释行永远容错，第二
                // 条连续注释行才 break；任何 `data:` 事件行（含空载荷）都会清零计数，
                // 所以真正的 data 行永不因这条规则被截断。
                // ONE comment line is always tolerated after the terminal event; the
                // SECOND consecutive one ends the tail. Never conditioned on where the
                // usage block landed.
                if (l.startsWith(":")) {
                    if (terminalEventAtNanos.get() != 0L && sawUsageBlock) {
                        postTerminalCommentLines++
                        if (postTerminalCommentLines >= 2) break
                    }
                    continue
                }
                // Tolerate `data:` with or without the optional space — the
                // HTML5 SSE spec only treats one leading space as ignorable,
                // and some OpenAI-compatible servers (e.g. China Telecom's
                // eaichat.ctyun.cn deepseek-v4-oc endpoint) emit `data:{...}`
                // with no space. Strict `data: ` matching dropped every
                // chunk on those providers, surfacing as empty-stream errors.
                if (!l.startsWith("data:")) continue
                val payload = l.removePrefix("data:").let {
                    if (it.startsWith(" ")) it.removePrefix(" ") else it
                }
                // [T-d4-terminal-break-narrowing] 空的 `data:` 载荷里没有任何事件
                // （JSONObject 会直接拒绝空串，此前只是打一条解析告警后丢弃），
                // 终态 + usage 之后它就是中转「没有别的要说了」的标记，直接收尾。
                // [T-d4-terminal-tail-structural] 这里的 sawUsageBlock 只是「尾部核算
                // 到位了没有」的**存在性**门槛，不再区分 usage 落在哪个事件之后（那是
                // 已删除的形状代理）；两个 API 形状的收尾判据完全一致。
                // An empty `data:` payload carries no event at all, so after the
                // terminal event and its usage it is the relay's "nothing left to
                // say" marker. Any real event line proves the relay is still
                // talking, so the comment counter restarts there.
                if (payload.isBlank()) {
                    if (terminalEventAtNanos.get() != 0L && sawUsageBlock) break
                    continue
                }
                postTerminalCommentLines = 0
                if (payload == "[DONE]") {
                    // [T-android-think-prefix-stream] Flush whatever the parser
                    // still holds (a cross-chunk tag tail, or an unterminated
                    // <think>). Idempotent, so the finish_reason path may also
                    // call it. Withheld trailing whitespace is dropped by design.
                    thinkParser.finishTurn().let { fin ->
                        if (fin.thinking.isNotEmpty()) {
                            reasoningAccum.append(fin.thinking)
                            sendTracked(LLMStreamChunk.ThinkingDelta(fin.thinking))
                        }
                        if (fin.visible.isNotEmpty()) sendTracked(LLMStreamChunk.Text(fin.visible))
                    }
                    // [T-android-think-prefix-stream] Persist reasoning captured
                    // EITHER from the `reasoning_content` field or from a
                    // `<think>` prefix. Gating solely on sawReasoningField would
                    // stream think-tag reasoning live and then drop it — the
                    // thinking bubble would vanish on session reload.
                    if (sawReasoningField || reasoningAccum.isNotEmpty()) {
                        sendTracked(LLMStreamChunk.ReasoningContent(reasoningAccum.toString()))
                    }
                    sendTracked(LLMStreamChunk.Finished(finishReason))
                    sentFinished = true
                    break
                }

                val event = try { JSONObject(payload) } catch (e: Exception) {
                    com.openminis.app.logging.AppLogger.warning(
                        "OpenAIProvider",
                        "[T321] SSE JSON parse failed: ${e.message} payload=${payload.take(300)}"
                    )
                    continue
                }
                if (android.util.Log.isLoggable("ToolChain[Provider]", android.util.Log.VERBOSE)) {
                    android.util.Log.d(
                        "ToolChain[Provider]",
                        "RAW SSE: ${com.openminis.app.text.BoundedText.clampSsePayload(payload)}",
                    )
                }
                sseEventCount++

                // T321: per-event delta-field summary. Only counts/lengths,
                // never the actual delta text — keeps log volume bounded.
                run {
                    val ev = event
                    val delta = ev.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                    val type = ev.optString("type", "")
                    if (delta != null) {
                        val cLen = delta.optString("content", "").length
                        val rcLen = delta.optString("reasoning_content", "").length
                        val rLen = delta.optString("reasoning", "").length
                        val tcLen = delta.optJSONArray("tool_calls")?.length() ?: 0
                        val role = delta.optString("role", "")
                        if ((cLen + rcLen + rLen + tcLen > 0 || delta.has("role")) &&
                            android.util.Log.isLoggable("OpenAIProvider", android.util.Log.VERBOSE)
                        ) {
                            com.openminis.app.logging.AppLogger.debug(
                                "OpenAIProvider",
                                "[T321] SSE delta: contentLen=$cLen rcLen=$rcLen rLen=$rLen toolCalls=$tcLen role='$role'"
                            )
                        }
                        contentLen += cLen
                        reasoningLen += rcLen + rLen
                        if (tcLen > 0) toolCallEventCount += tcLen
                    } else if (type.isNotEmpty()) {
                        // Responses API event-typed diagnostics.
                        // [T-android-responses-delta-log-noise] Responses events
                        // carry no `choices`, so EVERY delta lands in this branch:
                        // unconditional AppLogger.debug wrote one line per
                        // streamed token — each paying two IsoTime formats plus,
                        // with file logging on, an @Synchronized flush — from the
                        // very coroutine that reads the SSE socket. Gate it with
                        // the same Log.isLoggable(VERBOSE) as the Chat Completions
                        // delta log above.
                        val dLen = ev.optString("delta", "").length
                        if ((type.contains("delta") || type == "response.completed" || type == "response.output_item.added" || type == "response.output_item.done") &&
                            android.util.Log.isLoggable("OpenAIProvider", android.util.Log.VERBOSE)
                        ) {
                            com.openminis.app.logging.AppLogger.debug(
                                "OpenAIProvider",
                                "[T321] SSE responses type=$type deltaLen=$dLen"
                            )
                        }
                        if (type == "response.output_text.delta") {
                            contentLen += dLen
                            // [T-android-incomplete-keep-partial] contentLen alone
                            // can't distinguish " " from real text, and the
                            // response.incomplete handler needs that difference to
                            // decide between keeping a truncated answer and failing
                            // the turn. Track it here, where the delta text is in
                            // hand, rather than buffering the whole response.
                            if (!sawNonBlankText && ev.optString("delta", "").isNotBlank()) {
                                sawNonBlankText = true
                            }
                        }
                        if (type.startsWith("response.reasoning_")) reasoningLen += dLen
                    }
                }

                if (isResponsesAPI) {
                    // Responses API SSE parsing
                    val type = event.optString("type", "")
                    when {
                        // Reasoning text deltas — both event variants the API emits.
                        // For Codex OAuth the actual content is encrypted (echoed via
                        // include=reasoning.encrypted_content), so the .delta value
                        // is typically empty; for non-Codex Responses (forceResponsesAPI
                        // or custom base) it streams plaintext we can render.
                        // Mirrors iOS OpenAIAgentProvider.swift:374-382.
                        type == "response.reasoning_text.delta" ||
                            type == "response.reasoning_summary_text.delta" -> {
                            val delta = event.optString("delta", "")
                            if (delta.isNotEmpty()) {
                                if (!sawReasoningDelta) {
                                    com.openminis.app.logging.AppLogger.info(
                                        "OpenAIProvider",
                                        "Responses API: first reasoning delta arrived (type=$type) — streaming Thinking content"
                                    )
                                    sawReasoningDelta = true
                                }
                                sendTracked(LLMStreamChunk.ThinkingDelta(delta))
                            }
                        }
                        type == "response.output_text.delta" -> {
                            val delta = event.optString("delta", "")
                            if (delta.isNotEmpty()) sendTracked(LLMStreamChunk.Text(delta))
                        }
                        // function_call item announced — capture call_id + name, start accumulator.
                        type == "response.output_item.added" -> {
                            val item = event.optJSONObject("item") ?: continue
                            val itemType = item.optString("type", "")
                            if (itemType == "function_call") {
                                val itemId = item.optString("id", "")
                                val callId = item.optString("call_id", "")
                                val name = item.optString("name", "")
                                if (itemId.isNotEmpty() && callId.isNotEmpty() && name.isNotEmpty()) {
                                    // [T-d4-one-card-per-call] 一条逻辑调用（call_id）只能
                                    // 宣告一次：下游在 ToolUseStart 分支建卡片，第二次同
                                    // id 的宣告会被 ToolCallIdDedupe 改名成 "<id>-2" 另建
                                    // 一张 —— 而 ToolCallComplete 只会为宣告时的身份发一次，
                                    // 那张多出来的卡片就永远停在 STREAMING。所以这里先看
                                    // 这条调用是否已经宣告过（同一个 item_id 重复 added，
                                    // 或同一个 call_id 换个 item_id 再 added），已宣告过就
                                    // 只登记别名、不再发 start。
                                    // One announcement per logical call — a second one would
                                    // create a card nobody ever completes.
                                    val announcedKey = if (responsesToolCalls.containsKey(itemId)) {
                                        itemId
                                    } else {
                                        responsesToolCalls.entries
                                            .firstOrNull { it.value.callId == callId }?.key
                                    }
                                    if (announcedKey != null) {
                                        if (itemId != announcedKey) {
                                            responsesToolCallAliases[itemId] = announcedKey
                                        }
                                        val acc = responsesToolCalls.getValue(announcedKey)
                                        if (acc.name.isEmpty()) acc.name = name
                                        com.openminis.app.logging.AppLogger.warning(
                                            "OpenAIProvider",
                                            "Responses API: function_call announced twice — item_id=$itemId " +
                                                "call_id=$callId is already announced as $announcedKey; " +
                                                "reusing that identity and NOT opening a second card (T-d4)"
                                        )
                                    } else {
                                        responsesToolCalls[itemId] = ResponsesToolCallAccumulator(callId = callId, name = name)
                                        val combined = combineResponsesAPIIds(callId, itemId)
                                        android.util.Log.d("ToolChain[Provider]", "→ ToolUseStart (Responses) id=$combined name=$name")
                                        sendTracked(LLMStreamChunk.ToolUseStart(combined, name))
                                        responsesToolCalls[itemId]?.started = true
                                    }
                                }
                            }
                        }
                        type == "response.function_call_arguments.delta" -> {
                            val itemId = event.optString("item_id", "")
                            val delta = event.optString("delta", "")
                            // [T-d4-one-card-per-call] 别名 item_id 归到已宣告的身份上，
                            // 否则参数增量会带着一个下游没有卡片的 id（ToolInputDelta 就
                            // 找不到对应块，实时参数预览丢失）。
                            val announcedKey = responsesToolCallAliases[itemId] ?: itemId
                            val acc = responsesToolCalls[announcedKey]
                            if (acc != null && delta.isNotEmpty()) {
                                acc.args.append(delta)
                                val n = acc.args.length
                                if (com.openminis.app.text.BoundedText.shouldCommitLengthStride(n, acc.lastSentArgsLen)) {
                                    acc.lastSentArgsLen = n
                                    val combined = combineResponsesAPIIds(acc.callId, announcedKey)
                                    sendTracked(LLMStreamChunk.ToolInputDelta(combined, acc.args.toString()))
                                }
                            } else if (acc == null) {
                                // Pre-T107 this branch silently dropped the entire tool call
                                // because no accumulator was set up — leaving the model with
                                // no real tool channel and provoking <tool_call>{...} text
                                // hallucinations. Keep a warn so any future regression here
                                // surfaces in the daily log instead of a silent failure.
                                com.openminis.app.logging.AppLogger.warning(
                                    "OpenAIProvider",
                                    "Responses API: function_call_arguments.delta for unknown item_id=$itemId — dropping"
                                )
                            }
                        }
                        // The accumulator is finalized at response.output_item.done, when the
                        // arguments stream has flushed. The completed item carries `arguments`
                        // as a JSON string — we prefer that authoritative value over our own
                        // streamed buffer in case the API ever emits a corrected payload.
                        type == "response.output_item.done" -> {
                            val item = event.optJSONObject("item") ?: continue
                            val itemType = item.optString("type", "")
                            if (itemType == "function_call") {
                                val itemId = item.optString("id", "")
                                // [T-d4-responses-done-id-drift 按 call_id 消费残留累加器]
                                // 中转可能 `added` 用 id=fc_A 建累加器、`done` 却报
                                // id=fc_B（call_id 相同）：只按 raw item_id 取会取不到，
                                // 重建路径便为 fc_B 发 start+complete，而流尾冲刷又为残留
                                // 的 fc_A 再发一个 complete —— 下游去重按 raw id
                                // （`call_1|fc_A` ≠ `call_1|fc_B`），同一条逻辑调用于是被
                                // 派发两次。所以先按 item_id 取，取不到再按 call_id 反查并
                                // 消费掉残留累加器，并沿用**它**的 item_id：那是这条调用已经
                                // 宣告过的身份（ToolUseStart / UI 卡片 / 参数增量都挂在它上面）。
                                // Resolve the accumulator by item_id first, then by
                                // call_id, and CONSUME what is found — the call is
                                // finished under the id it was announced with.
                                var announcedItemId = itemId
                                // [T-d4-one-card-per-call] 这个 item_id 可能只是同一调用的
                                // 第二个身份（别名），真正的累加器挂在已宣告的 id 上。
                                val aliasedKey = responsesToolCallAliases[itemId]
                                if (aliasedKey != null) announcedItemId = aliasedKey
                                var acc = consumeResponsesToolCall(announcedItemId)
                                if (acc == null) {
                                    val doneCallId = item.optString("call_id", "")
                                    val residualKey = if (doneCallId.isEmpty()) {
                                        null
                                    } else {
                                        responsesToolCalls.entries
                                            .firstOrNull { it.value.callId == doneCallId }?.key
                                    }
                                    if (residualKey != null) {
                                        acc = consumeResponsesToolCall(residualKey)
                                        announcedItemId = residualKey
                                        com.openminis.app.logging.AppLogger.warning(
                                            "OpenAIProvider",
                                            "Responses API: output_item.done item_id drift — done reports " +
                                                "item_id=$itemId but the accumulator is $residualKey " +
                                                "(call_id=$doneCallId); finishing the announced call and " +
                                                "consuming the residual (T-d4)"
                                        )
                                    }
                                }
                                // [T-android-responses-done-without-accumulator]
                                // The accumulator is only created at
                                // `response.output_item.added` (and only when
                                // id+call_id+name are all present), so a relay that
                                // skips `added`, or announces it with a missing
                                // field, reached this branch with nothing to remove
                                // and the whole function_call was dropped WITHOUT a
                                // trace: `response.completed` still reports
                                // finish_reason=tool_use, the agent loop sees
                                // toolCalls.isEmpty() and ends the turn — a blank
                                // reply and no inline error. The `done` item is the
                                // authoritative copy (it carries id/call_id/name/
                                // arguments), so rebuild from it instead of
                                // discarding. Mirrors the warn-only handling of the
                                // same relay deviation on the delta branch above.
                                val callId = acc?.callId?.takeIf { it.isNotEmpty() }
                                    ?: item.optString("call_id", "")
                                val name = acc?.name?.takeIf { it.isNotEmpty() }
                                    ?: item.optString("name", "")
                                if (acc == null) {
                                    com.openminis.app.logging.AppLogger.warning(
                                        "OpenAIProvider",
                                        "Responses API: output_item.done for function_call without an accumulator " +
                                            "(item_id=$itemId call_id=$callId name=$name) — rebuilding from the done item"
                                    )
                                }
                                if (callId.isEmpty() || name.isEmpty()) {
                                    // Nothing to correlate a tool result with (and no
                                    // name to dispatch): dropping is the only option,
                                    // but it must not be silent.
                                    com.openminis.app.logging.AppLogger.warning(
                                        "OpenAIProvider",
                                        "Responses API: dropping function_call at output_item.done — " +
                                            "item_id=$itemId call_id='$callId' name='$name' (missing required id/name)"
                                    )
                                    continue
                                }
                                // [T-d4-responses-done-id-drift] 同一个 call_id 下的其它
                                // 残留累加器都是这条调用的第二个身份（id 漂移、或同一个
                                // 调用被 `added` 宣告了两次）：现在就消费掉，流尾冲刷才不
                                // 会为它再发一个 complete。call_id 每条调用唯一，因此不会
                                // 误删真正并行的兄弟调用。
                                // Any other accumulator sharing this call_id is the same
                                // logical call under a second item_id — drop it now so
                                // the stream-tail flush cannot dispatch it twice.
                                val strays = responsesToolCalls.filterValues { it.callId == callId }.keys.toList()
                                if (strays.isNotEmpty()) {
                                    for (stray in strays) consumeResponsesToolCall(stray)
                                    com.openminis.app.logging.AppLogger.warning(
                                        "OpenAIProvider",
                                        "Responses API: consuming stale function_call accumulator(s) $strays " +
                                            "sharing call_id=$callId — the call completes once, as $announcedItemId"
                                    )
                                }
                                val argsStr = item.optString("arguments", acc?.args?.toString() ?: "")
                                val args = try { JSONObject(argsStr) } catch (_: Exception) { JSONObject() }
                                val combined = combineResponsesAPIIds(callId, announcedItemId)
                                // [T-android-responses-done-without-accumulator] A
                                // rebuilt call never went through `output_item.added`,
                                // and ToolUseStart is emitted ONLY there. Downstream
                                // creates a tool block exclusively in its ToolUseStart
                                // branch (ChatViewModelAgentLoopExt) — a
                                // ToolCallComplete for an unknown id updates nothing
                                // (`idx < 0`) — so the tool dispatched from the
                                // completed call had no card at all: it ran silently
                                // and the persisted blocks disagreed with the dispatch
                                // list. Backfill the start first, exactly once, so the
                                // recovered call is indistinguishable from a normal one.
                                if (acc?.started != true) {
                                    android.util.Log.d("ToolChain[Provider]", "→ ToolUseStart (Responses, rebuilt from done) id=$combined name=$name")
                                    sendTracked(LLMStreamChunk.ToolUseStart(combined, name))
                                    acc?.started = true
                                }
                                android.util.Log.d("ToolChain[Provider]", "→ ToolCallComplete (Responses) id=$combined name=$name args=${args.toString().take(300)}")
                                // [T-openai-terminal-drain] 计数供尾部「被砍断了什么」诊断。
                                responsesToolCallsCompleted++
                                sendTracked(LLMStreamChunk.ToolCallComplete(combined, name, args))
                            }
                        }
                        type == "response.failed" -> {
                            // [T-responses-terminal-events] Explicit terminal
                            // handling instead of the generic fallthrough: pull
                            // the structured error off the response object so
                            // the thrown LLMError carries the real reason (and
                            // so retry/fallback classification can act on it).
                            // Official shape: response.status == "failed",
                            // response.error = {code, message}. Mirrors iOS 637cd890.
                            val resp = event.optJSONObject("response")
                            val err = resp?.optJSONObject("error")
                            val code = err?.optString("code")?.takeIf { it.isNotEmpty() } ?: "unknown"
                            val message = err?.optString("message")?.takeIf { it.isNotEmpty() }
                                ?: "response.failed with no error detail"
                            com.openminis.app.logging.AppLogger.error(
                                "OpenAIProvider",
                                "Responses API response.failed — code=$code message=$message"
                            )
                            if (code == "server_error" || code == "rate_limit_exceeded") {
                                // Transient family: retry on the same model
                                // rather than falling back through the group.
                                throw LLMError.TransientError("[$code] $message")
                            }
                            throw LLMError.ProviderError("[$code] $message")
                        }
                        type == "response.incomplete" -> {
                            // [T-responses-terminal-events] The server ended the
                            // response early; incomplete_details.reason is
                            // "max_output_tokens" or "content_filter".
                            val reason = event.optJSONObject("response")
                                ?.optJSONObject("incomplete_details")
                                ?.optString("reason")?.takeIf { it.isNotEmpty() }
                                ?: "unknown"

                            // [T-android-incomplete-keep-partial] Only fail the
                            // turn when there is genuinely nothing to show.
                            //
                            // The old code threw unconditionally, and the comment
                            // above it ("Partial output has already been streamed
                            // — surface WHY") described an intent the code did not
                            // implement: throwing here discards the streamed text,
                            // so a truncated-but-useful answer was reported to the
                            // user as a hard failure with nothing rendered.
                            //
                            // Reported against a Responses-format relay proxying
                            // Claude: the model spent its whole budget in the
                            // reasoning phase and emitted one space of visible
                            // text, then `response.incomplete
                            // reason=max_output_tokens` with
                            // `usage.output_tokens=0`. Raising the setting could
                            // not help (the budget went to reasoning, and the
                            // relay reports output_tokens=0 regardless), so every
                            // retry failed the same way and the turn was lost.
                            //
                            // Truncation is a normal terminal condition, not an
                            // error: Chat Completions already models it as
                            // `finish_reason=length` and ends the stream
                            // normally. Treating the Responses spelling the same
                            // way keeps the two API flavours consistent and lets
                            // the agent loop persist what did arrive.
                            //
                            // `contentLen` counts text deltas actually forwarded
                            // downstream, so it is the honest test for "does the
                            // user have something to read". Whitespace-only output
                            // (the reported case) counts as nothing, since a bubble
                            // containing one space is indistinguishable from a bug.
                            val hasUsableOutput = sawNonBlankText
                            if (hasUsableOutput) {
                                com.openminis.app.logging.AppLogger.warning(
                                    "OpenAIProvider",
                                    "Responses API response.incomplete — reason=$reason; " +
                                        "keeping ${contentLen}ch of partial output (finish_reason=length)"
                                )
                                // Same terminal shape Chat Completions uses for a
                                // budget-truncated answer, so downstream code needs
                                // no new branch: the loop stops, the text persists,
                                // and the UI can mark it truncated.
                                finishReason = "length"
                                sawFinishReason = true
                                break
                            }

                            com.openminis.app.logging.AppLogger.error(
                                "OpenAIProvider",
                                "Responses API response.incomplete — reason=$reason (no usable output)"
                            )
                            throw LLMError.ProviderError(
                                "Response ended incomplete (reason: $reason)" +
                                    if (reason == "max_output_tokens") {
                                        // The old text told the user to raise Max
                                        // Output Tokens. When reasoning consumed
                                        // the budget that advice is actively
                                        // misleading — this user tried 128k, 32k
                                        // and 16k, all identical — so name the
                                        // real lever too.
                                        " — the model used its entire output budget before producing a reply" +
                                            " (often the thinking phase on a reasoning model). Try turning off or" +
                                            " lowering Deep Thinking, shortening the request, or raising the model's" +
                                            " Max Output Tokens."
                                    } else ""
                            )
                        }
                        type == "response.completed" -> {
                            val resp = event.optJSONObject("response")
                            val status = resp?.optString("status", "")
                            // When the model emitted tool calls the API returns status=completed
                            // with no stop_reason; surface "tool_use" so the agent loop knows to
                            // dispatch the calls instead of treating the turn as final.
                            // [T-openai-terminal-drain] 顺手数清楚 completed 的 output 里声明了
                            // 几条 function_call：尾部被砍断时 declared≠completed 就是「relay
                            // 还没来得及补发 done」的直接证据（从未 added 过的调用 declared=1、
                            // completed=0）。多次 completed 取最大值，别被后一个覆盖成 0。
                            val declaredCalls = resp?.optJSONArray("output")?.let { out ->
                                var found = 0
                                for (i in 0 until out.length()) {
                                    if (out.optJSONObject(i)?.optString("type") == "function_call") found++
                                }
                                found
                            } ?: 0
                            if (declaredCalls > declaredFunctionCallItems) declaredFunctionCallItems = declaredCalls
                            val sawToolCalls = responsesToolCalls.isNotEmpty() || declaredCalls > 0
                            finishReason = when {
                                sawToolCalls -> "tool_use"
                                status == "completed" -> "stop"
                                else -> status
                            }
                            if (!sawFinishReason) {
                                sawFinishReason = true
                                // [T-codex-fast-mode] The response object inside
                                // response.completed echoes the EFFECTIVE
                                // service_tier — "priority" here is definitive
                                // proof Fast Mode was honored; "default"/absent
                                // means requested-but-downgraded (OpenAI silently
                                // downgrades ineligible accounts). Mirrors iOS
                                // 63a71146.
                                val serviceTier = resp?.optString("service_tier", "")
                                    ?.takeIf { it.isNotEmpty() } ?: "n/a"
                                com.openminis.app.logging.AppLogger.info(
                                    "OpenAIProvider",
                                    "[T321] Responses finish_reason=$finishReason status=$status service_tier=$serviceTier contentLen=$contentLen reasoningLen=$reasoningLen toolCallAccumulators=${responsesToolCalls.size}"
                                )
                            }
                            resp?.optJSONObject("usage")?.let { usage ->
                                sawUsageBlock = true
                                com.openminis.app.logging.AppLogger.info(
                                    "OpenAIProvider",
                                    "[T321] Responses usage block: $usage"
                                )
                                sendTracked(LLMStreamChunk.Usage(parseResponsesAPIUsage(usage)))
                            }
                            // [T-openai-terminal-drain] `response.completed` IS the
                            // Responses terminal event (usage above is the last
                            // thing the turn needs). Mark it so the body watch stops
                            // treating a relay that leaves the socket open as
                            // "alive but silent" and stall-resuming a finished turn.
                            terminalEventAtNanos.compareAndSet(0L, System.nanoTime())
                        }
                        type == "response.output_text.done" -> {
                            // Text output complete, no action needed
                        }
                    }
                } else {
                    // Chat Completions API SSE parsing
                    // Check for inline error (OpenRouter sends error inside SSE with empty choices)
                    val inlineError = event.optJSONObject("error")
                    if (inlineError != null) {
                        val code = inlineError.optInt("code", 0)
                        val msg = inlineError.optString("message", "Unknown SSE error")
                        val err = mapHttpError(code, event.toString())
                        throw err
                    }
                    val choices = event.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val choice = choices.getJSONObject(0)
                        val delta = choice.optJSONObject("delta")

                        // Reasoning / thinking content (DeepSeek, Kimi, etc.)
                        delta?.let { d ->
                            // Track presence of either field — even an empty string
                            // counts so we can round-trip DeepSeek V4's `reasoning_content: ""`.
                            val hasRcKey = d.has("reasoning_content")
                            val hasReasoningKey = d.has("reasoning")
                            if (hasRcKey || hasReasoningKey) {
                                sawReasoningField = true
                            }
                            val rc = d.safeOptString("reasoning_content", "")
                                .ifEmpty { d.safeOptString("reasoning", "") }
                            if (rc.isNotEmpty()) {
                                reasoningAccum.append(rc)
                                if (!sawReasoningDelta) {
                                    sawReasoningDelta = true
                                    com.openminis.app.logging.AppLogger.info(
                                        "OpenAIProvider",
                                        "Chat Completions: first reasoning_content delta arrived on ${model.id} — streaming Thinking content"
                                    )
                                }
                                sendTracked(LLMStreamChunk.ThinkingDelta(rc))
                            }
                        }

                        // [T-android-think-prefix-stream] Text content. Models that
                        // embed reasoning as a `<think>…</think>` PREFIX of
                        // `content` (MiniMax M3, some Qwen/DeepSeek deployments)
                        // are split by ThinkPrefixStreamParser, which replaced the
                        // old extractThinkTags scanner. That scanner searched for
                        // `<think>` at ANY offset, so a reply merely explaining the
                        // tag had its prose swallowed into the thinking bubble, and
                        // it passed M3's post-`</think>` "\n\n" straight through so
                        // every such body began with a blank line.
                        delta?.safeOptString("content", "")?.let { text ->
                            if (text.isNotEmpty()) {
                                val out = thinkParser.feed(text)
                                if (out.thinking.isNotEmpty()) {
                                    reasoningAccum.append(out.thinking)
                                    sendTracked(LLMStreamChunk.ThinkingDelta(out.thinking))
                                }
                                if (out.visible.isNotEmpty()) sendTracked(LLMStreamChunk.Text(out.visible))
                            }
                        }

                        // Tool calls (parallel: keyed by index)
                        val toolCalls = delta?.optJSONArray("tool_calls")
                        if (toolCalls != null) {
                            for (i in 0 until toolCalls.length()) {
                                val tc = toolCalls.getJSONObject(i)
                                val idx = tc.optInt("index", 0)
                                val acc = toolCallAccumulators.getOrPut(idx) { ToolCallAccumulator() }

                                tc.safeOptString("id", "").let { if (it.isNotEmpty()) acc.id = it }
                                tc.optJSONObject("function")?.let { fn ->
                                    fn.safeOptString("name", "").let { if (it.isNotEmpty()) acc.name = it }
                                    fn.safeOptString("arguments", "").let { if (it.isNotEmpty()) acc.args.append(it) }
                                }

                                // Emit start exactly once per tool call
                                if (!acc.started && acc.id.isNotEmpty() && acc.name.isNotEmpty()) {
                                    acc.started = true
                                    android.util.Log.d("ToolChain[Provider]", "→ ToolUseStart id=${acc.id} name=${acc.name}")
                                    sendTracked(LLMStreamChunk.ToolUseStart(acc.id, acc.name))
                                }
                                // Emit input delta
                                if (acc.id.isNotEmpty() && acc.args.isNotEmpty()) {
                                    val n = acc.args.length
                                    if (com.openminis.app.text.BoundedText.shouldCommitLengthStride(n, acc.lastSentArgsLen)) {
                                        acc.lastSentArgsLen = n
                                        if (com.openminis.app.text.BoundedText.shouldLogLengthStride(n)) {
                                            android.util.Log.d("ToolChain[Provider]", "→ ToolInputDelta id=${acc.id} accumulated=${n}chars")
                                        }
                                        sendTracked(LLMStreamChunk.ToolInputDelta(acc.id, acc.args.toString()))
                                    }
                                }
                            }
                        }

                        // Finish reason
                        choice.safeOptString("finish_reason", "").let {
                            if (it.isNotEmpty()) {
                                finishReason = it
                                // [T-openai-terminal-drain] The choice is finished:
                                // from here on the stream only owes the trailing
                                // usage chunk (stream_options.include_usage) and a
                                // `[DONE]` the relay may never send. Mark the
                                // terminal event so a socket left open afterwards
                                // is drained and closed instead of being classified
                                // as a mid-stream stall.
                                terminalEventAtNanos.compareAndSet(0L, System.nanoTime())
                                if (!sawFinishReason) {
                                    sawFinishReason = true
                                    com.openminis.app.logging.AppLogger.info(
                                        "OpenAIProvider",
                                        "[T321] finish_reason=$it contentLen=$contentLen reasoningLen=$reasoningLen toolCallEvents=$toolCallEventCount accumulators=${toolCallAccumulators.size}"
                                    )
                                }
                            }
                        }
                    }

                    event.optJSONObject("usage")?.let { usage ->
                        sawUsageBlock = true
                        com.openminis.app.logging.AppLogger.info(
                            "OpenAIProvider",
                            "[T321] usage block: $usage"
                        )
                        sendTracked(LLMStreamChunk.Usage(parseChatCompletionsUsage(usage)))
                    }
                }
            }

            // [T-android-think-prefix-stream] Stream-end flush, for streams that
            // end without a `[DONE]` sentinel. finishTurn() is idempotent, so
            // running after the [DONE] path already flushed is a no-op.
            thinkParser.finishTurn().let { fin ->
                if (fin.thinking.isNotEmpty()) {
                    reasoningAccum.append(fin.thinking)
                    sendTracked(LLMStreamChunk.ThinkingDelta(fin.thinking))
                }
                if (fin.visible.isNotEmpty()) sendTracked(LLMStreamChunk.Text(fin.visible))
            }

            // Emit ToolCallComplete for all accumulated tool calls
            for ((_, acc) in toolCallAccumulators) {
                if (acc.id.isNotEmpty() && acc.name.isNotEmpty()) {
                    val args = try { JSONObject(acc.args.toString()) } catch (_: Exception) { JSONObject() }
                    android.util.Log.d("ToolChain[Provider]", "→ ToolCallComplete id=${acc.id} name=${acc.name} args=${args.toString().take(300)}")
                    sendTracked(LLMStreamChunk.ToolCallComplete(acc.id, acc.name, args))
                }
            }
            // Drain Responses-API tool accumulators that didn't get an output_item.done
            // before the stream closed. Without this, mid-tool-call truncation (server
            // closes connection while function_call_arguments is still streaming) leaves
            // ChatViewModel.toolCalls empty: the agent loop sees no tool calls, exits,
            // and the UI hangs with the tool thumbnail spinning while the stop button
            // disappears (T247 root cause; same path hit by T237 DeepSeek truncation).
            for ((itemId, acc) in responsesToolCalls) {
                if (acc.callId.isNotEmpty() && acc.name.isNotEmpty()) {
                    val args = try { JSONObject(acc.args.toString()) } catch (_: Exception) { JSONObject() }
                    val combined = combineResponsesAPIIds(acc.callId, itemId)
                    com.openminis.app.logging.AppLogger.warning(
                        "OpenAIProvider",
                        "Stream ended mid-tool-call id=$combined name=${acc.name} argsLen=${acc.args.length} — flushing as ToolCallComplete (T248)",
                    )
                    responsesToolCallsCompleted++
                    sendTracked(LLMStreamChunk.ToolCallComplete(combined, acc.name, args))
                }
            }
            val pendingAtTail = responsesToolCalls.size
            responsesToolCalls.clear()
            responsesToolCallAliases.clear()
            // [T-openai-terminal-drain] 尾部冲刷的可观测提示。终态后 socket 被我们自己
            // 的 drain 砍掉（或读取直接报错）时，relay 还没来得及补发的
            // `response.output_item.done` 根本到不了这里 —— 从未 `added` 过的调用
            // 连累加器都没有，上面的冲刷循环对它无话可说，于是整条工具调用静默消失，
            // 旧代码只留一条 info。这里按 declared/completed/pending 三个数把现场写进
            // warn：declared=1 而 completed=0 就是那条丢掉的调用。
            // Anything the relay still owed when the tail was cut is invisible to the
            // flush above — make the loss observable instead of an info-level footnote.
            if (terminalDrainClosedSocket.get() || postTerminalIoError) {
                com.openminis.app.logging.AppLogger.warning(
                    "OpenAIProvider",
                    "[T-openai-terminal-drain] tail cut after the terminal event without [DONE] " +
                        "(cutByDrain=${terminalDrainClosedSocket.get()} readError=$postTerminalIoError): " +
                        "finishReason=$finishReason declaredFunctionCalls=$declaredFunctionCallItems " +
                        "completedFunctionCalls=$responsesToolCallsCompleted pendingAtTail=$pendingAtTail — " +
                        "an output_item.done the relay had not flushed yet (incl. a function_call it never " +
                        "announced with output_item.added) is NOT part of this turn",
                )
            }

            // T321: stream ended — final tally + warning if we never saw a
            // finish_reason. The latter is the strongest signal of a server-
            // side truncation / connection-dropped scenario.
            // [T-android-responses-missing-finished] Emit the terminal chunk for
            // streams that end without a `data: [DONE]` sentinel.
            //
            // Finished was only ever sent from the [DONE] branch. Chat
            // Completions always sends that sentinel, but the Responses API
            // terminates with `response.completed` and many relays simply close
            // the socket afterwards — no [DONE] ever arrives. The read loop then
            // exits normally, the channel closes, and no Finished is emitted.
            //
            // Downstream, ChatViewModel.runAgentLoop only ever assigns
            // turnFinishReason from a Finished chunk, so it stayed null and the
            // turn was reported as "stream closed without a finish reason" —
            // the red "连接中断，此回复可能不完整" banner on a reply that was in
            // fact complete. It looked intermittent because it depends on the
            // relay: those that do append [DONE] worked, the rest did not, which
            // is why the same model on the same account failed only sometimes,
            // and why Chat Completions models (deepseek) never showed it.
            //
            // Gated on sawFinishReason: reaching here WITHOUT one is a genuine
            // truncation, and must keep falling through to the warning below so
            // the interrupted-reply UI still fires for real drops.
            if (sawFinishReason && !sentFinished) {
                // Mirror the [DONE] branch's ordering: flush the think-tag
                // parser and reasoning blob before the terminal chunk, or a
                // trailing <think> tail would be dropped and reasoning would
                // vanish on reload.
                thinkParser.finishTurn().let { fin ->
                    if (fin.thinking.isNotEmpty()) {
                        reasoningAccum.append(fin.thinking)
                        sendTracked(LLMStreamChunk.ThinkingDelta(fin.thinking))
                    }
                    if (fin.visible.isNotEmpty()) sendTracked(LLMStreamChunk.Text(fin.visible))
                }
                if (sawReasoningField || reasoningAccum.isNotEmpty()) {
                    sendTracked(LLMStreamChunk.ReasoningContent(reasoningAccum.toString()))
                }
                sendTracked(LLMStreamChunk.Finished(finishReason))
                sentFinished = true
                com.openminis.app.logging.AppLogger.info(
                    "OpenAIProvider",
                    "[T321] stream ended without [DONE] — emitted Finished(finishReason=$finishReason) from tail"
                )
            }

            if (!sawFinishReason) {
                com.openminis.app.logging.AppLogger.warning(
                    "OpenAIProvider",
                    "[T321] stream ended WITHOUT finish_reason: events=$sseEventCount " +
                        "contentLen=$contentLen reasoningLen=$reasoningLen " +
                        "toolCallEvents=$toolCallEventCount sawUsage=$sawUsageBlock model=${model.id}"
                )
            } else {
                com.openminis.app.logging.AppLogger.info(
                    "OpenAIProvider",
                    "[T321] stream complete: events=$sseEventCount contentLen=$contentLen " +
                        "reasoningLen=$reasoningLen toolCallEvents=$toolCallEventCount sawUsage=$sawUsageBlock"
                )
            }
        } catch (e: Exception) {
            // [T-android-body-idle-watchdog] The watchdog's trip (body-idle /
            // content-idle) closed the socket: surface as a stall-resume
            // TransientError so the retry ladder carries the partial
            // text/thinking forward instead of surfacing a plain NetworkError
            // (which regenerates from scratch).
            val idlePhase = bodyIdlePhase.get()
            if (idlePhase != null && e is IOException) {
                com.openminis.app.logging.AppLogger.warning(
                    "OpenAIProvider",
                    "[T-android-body-idle-watchdog] mapping socket failure to stall-resume retry (phase=$idlePhase, contentLen=$contentLen reasoningLen=$reasoningLen)"
                )
                cancel(
                    "Stream error",
                    LLMError.TransientError(
                        "stream body idle ($idlePhase) after $contentLen chars — resuming from partial output",
                        stalledAfterFirstEvent = true,
                    ),
                )
            }
            // T321: never silently swallow — log message + top-3 stack frames.
            val frames = e.stackTrace.take(3).joinToString(" | ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
            com.openminis.app.logging.AppLogger.error(
                "OpenAIProvider",
                "[T321] stream parse exception: ${e.javaClass.simpleName}: ${e.message} @ $frames " +
                    "(events=$sseEventCount contentLen=$contentLen reasoningLen=$reasoningLen)"
            )
            cancel("Stream error", mapError(e))
        } finally {
            ttfbWatchdog.cancel()
            reader.close()
            response.close()
        }
        channel.close()
        // T171: when the coroutine is cancelled (user tapped stop), the
        // reader loop above is suspended inside the OkHttp source — only
        // call.cancel() will tear the socket down promptly. response.close()
        // is also explicit so connection-pool leaks are impossible if cancel
        // races with the finally block.
        awaitClose {
            try { call.cancel() } catch (_: Exception) {}
            try { response.close() } catch (_: Exception) {}
        }
    }
