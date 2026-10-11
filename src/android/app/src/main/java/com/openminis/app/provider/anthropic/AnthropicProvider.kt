package com.openminis.app.provider.anthropic

import android.util.Base64
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.sanitizeToolId
import com.openminis.app.data.model.LLMError
import com.openminis.app.harness.agent.HttpRetryAfter
import com.openminis.app.provider.CredentialAcceptance
import com.openminis.app.provider.ProviderKeyGate
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.SamplingIdentity
import com.openminis.app.provider.SamplingPolicy
import com.openminis.app.provider.applyUserAgentOverride
import com.openminis.app.provider.safeOptString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import com.openminis.app.provider.failOnSilentEmptyCompletion
import com.openminis.app.provider.thinking.ThinkingLadder
import com.openminis.app.provider.thinking.ThinkingRuleResolver

class AnthropicProvider(
    private val apiKey: String,
    override var model: LLMModel = LLMModel.claudeHaiku45,
    private val basePath: String = "https://api.anthropic.com",
    /** Whether this provider uses OAuth credentials (Bearer + beta header). */
    val isOAuth: Boolean = false,
    /**
     * [T-provider-custom-user-agent] Per-provider User-Agent override.
     * null/blank → default UA; non-blank → replaces User-Agent on the chat
     * request. Only set for custom-base Anthropic-compat instances.
     */
    private val customUserAgent: String? = null,
    private val oauthIdentifierPromptProvider: () -> String = {
        com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
    },
) : LLMProvider {
    override val name = "Anthropic"
    override val callGateKey: String
        get() = ProviderKeyGate.key(basePath, apiKey, model.id)
    override val defaultMaxOutputTokens: Int get() = 64_000

    /**
     * [T-android-enhanced-cache] Enhanced Cache toggle — when true, every
     * `cache_control: ephemeral` breakpoint carries `ttl:"1h"` (1-hour cache
     * TTL) instead of the default 5-minute TTL, and the
     * `extended-cache-ttl-2025-04-11` beta flag is attached to API-key requests
     * (OAuth already carries it). Set per chat turn from
     * `ChatViewModel.enhancedCacheEnabled`; defaults off so existing behaviour
     * is unchanged. Mirrors iOS `RequestBodyPatcher.setExtendedCacheTTL`.
     */
    var enhancedCache: Boolean = false

    /**
     * Build a `cache_control` object honoring [enhancedCache]. Default 5-minute
     * TTL is `{"type":"ephemeral"}`; enhanced is `{"type":"ephemeral","ttl":"1h"}`.
     * Every ephemeral breakpoint in the request body routes through here.
     */
    private fun ephemeralCacheControl(): JSONObject =
        JSONObject().put("type", "ephemeral").apply {
            if (enhancedCache) put("ttl", "1h")
        }

    /** Use Bearer auth for custom (non-Anthropic) base URLs or OAuth. */
    private val isCustomEndpoint: Boolean = basePath != "https://api.anthropic.com"

    private val client = OkHttpClient.Builder()
        // [T-doh-resolver-fallback] A custom base URL means an arbitrary host;
        // when the system resolver cannot name it, every provider-level retry
        // re-runs the same failed lookup and the chat just never starts.
        .dns(com.openminis.app.network.DohDns)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        // [T-android-stale-conn-retry-hang] Shared pool — see NetworkMonitor.
        // Network-transition eviction must reach provider connections.
        .connectionPool(com.openminis.app.network.NetworkMonitor.sharedLLMConnectionPool)
        .build()

    override suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse = withContext(Dispatchers.IO) {
        val body = buildRequestBody(messages, systemPrompt, maxTokens, stream = false, temperature = temperature, imageParts = imageParts, tools = tools, thinkingLevel = thinkingLevel)
        val request = buildRequest(body.toString(), body)
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw mapHttpError(response.code, responseBody, response.header("Retry-After"))
        }

        val json = JSONObject(responseBody)
        parseResponse(json)
    }

    override fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
        systemStablePrefixLen: Int,
    ): Flow<LLMStreamChunk> = rawStreamMessage(
        messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel,
        systemStablePrefixLen,
    ).failOnSilentEmptyCompletion(name)

    private fun rawStreamMessage(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
        systemStablePrefixLen: Int = -1,
    ): Flow<LLMStreamChunk> = callbackFlow {
        val body = buildRequestBody(messages, systemPrompt, maxTokens, stream = true, temperature = temperature, imageParts = imageParts, tools = tools, thinkingLevel = thinkingLevel, systemStablePrefixLen = systemStablePrefixLen)
        // T302: serialize the body exactly once and pass the string to
        // buildRequest. Pre-T302 body.toString() ran twice per call (once
        // here, once inside buildRequest); each emitted string was tens of
        // MB on long agent loops and stacked under GC pressure.
        val bodyStr = com.openminis.app.provider.RequestBodyByteGuard.enforce(body)
        val request = buildRequest(bodyStr, body)

        val startTime = System.currentTimeMillis()

        // Capture request headers as map
        val headerMap = mutableMapOf<String, String>()
        for (name in request.headers.names()) {
            headerMap[name] = request.headers[name] ?: ""
        }

        val response = client.newCall(request).execute()
        val durationMs = System.currentTimeMillis() - startTime

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            response.close()
            // T302: skip the LLMRequestLog write entirely on release builds —
            // see OpenAIProvider for the full rationale (release users never
            // reach debug.llmRequests, so retaining multi-MB request bodies
            // there is pure OOM risk for zero benefit).
            if (com.openminis.app.BuildConfig.DEBUG) {
                com.openminis.app.debug.LLMRequestLog.add(
                    com.openminis.app.debug.LLMRequestLog.Entry(
                        provider = "anthropic",
                        requestURL = request.url.toString(),
                        requestHeaders = headerMap,
                        requestBody = bodyStr,
                        durationMs = durationMs,
                        responseStatusCode = response.code,
                        responseBody = errorBody.take(2000),
                    )
                )
            }
            android.util.Log.e("AnthropicProvider", "Stream failed: ${response.code} isOAuth=$isOAuth body=${errorBody.take(300)}")
            throw mapHttpError(response.code, errorBody, response.header("Retry-After"))
        }

        // Log successful request (debug builds only — see above)
        if (com.openminis.app.BuildConfig.DEBUG) {
            com.openminis.app.debug.LLMRequestLog.add(
                com.openminis.app.debug.LLMRequestLog.Entry(
                    provider = "anthropic",
                    requestURL = request.url.toString(),
                    requestHeaders = headerMap,
                    requestBody = bodyStr,
                    durationMs = durationMs,
                    responseStatusCode = response.code,
                )
            )
        }

        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
        // Track current tool_use block being streamed
        var currentToolId: String? = null
        var currentToolName: String? = null
        val toolInputBuffer = StringBuilder()
        var lastSentToolInputLen = 0
        // [T-anthropic-usage-merge] message_start 的 usage 留底：旧形状中转的
        // message_delta 只带 output_tokens，合并时用它补齐 input/cache。
        var streamUsage: LLMUsage? = null

        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (!l.startsWith("data: ")) continue
                val payload = l.removePrefix("data: ")
                if (payload == "[DONE]") break

                val event = try { JSONObject(payload) } catch (_: Exception) { continue }
                // [T-android-vm-store-leak] (上游 1.14 收紧) 上游指出这里为每条 SSE
                // 事件都构造整段 payload——release 包也照付分配与 I/O，而 logcat 可能
                // 根本没在听；3 小时设备日志里 ~8.5k 行 / 1.4MB（占全文件 20%）。
                // 采纳它的 DEBUG-only 门禁，同时保留我们的 BoundedText 截断：即便在
                // DEBUG 包里 logcat 没开 verbose，也不会把整段 payload 灌进日志。
                if (com.openminis.app.BuildConfig.DEBUG &&
                    android.util.Log.isLoggable("ToolChain[Provider]", android.util.Log.VERBOSE)
                ) {
                    android.util.Log.d(
                        "ToolChain[Provider]",
                        "RAW SSE: ${com.openminis.app.text.BoundedText.clampSsePayload(payload)}",
                    )
                }
                val eventType = event.safeOptString("type", "")

                when (eventType) {
                    "message_start" -> {
                        send(LLMStreamChunk.Started)
                        event.optJSONObject("message")?.optJSONObject("usage")?.let { usage ->
                            val parsed = parseUsage(usage)
                            streamUsage = parsed
                            send(LLMStreamChunk.Usage(parsed))
                        }
                    }
                    "content_block_start" -> {
                        val contentBlock = event.optJSONObject("content_block")
                        if (contentBlock?.safeOptString("type", "") == "tool_use") {
                            currentToolId = contentBlock.safeOptString("id", "")
                            currentToolName = contentBlock.safeOptString("name", "")
                            toolInputBuffer.clear()
                            lastSentToolInputLen = 0
                            android.util.Log.d("ToolChain[Provider]", "→ ToolUseStart id=$currentToolId name=$currentToolName")
                            send(LLMStreamChunk.ToolUseStart(currentToolId!!, currentToolName!!))
                        }
                    }
                    "content_block_delta" -> {
                        val delta = event.optJSONObject("delta") ?: continue
                        when (delta.safeOptString("type", "")) {
                            "text_delta" -> {
                                val text = delta.safeOptString("text", "")
                                if (text.isNotEmpty()) send(LLMStreamChunk.Text(text))
                            }
                            "thinking_delta" -> {
                                val thinking = delta.safeOptString("thinking", "")
                                if (thinking.isNotEmpty()) send(LLMStreamChunk.ThinkingDelta(thinking))
                            }
                            "input_json_delta" -> {
                                val partial = delta.safeOptString("partial_json", "")
                                if (partial.isNotEmpty() && currentToolId != null) {
                                    toolInputBuffer.append(partial)
                                    // [T-android-log-hotpath] 上游给这行日志加了
                                    // AppLogger.traceEnabled 门禁；我们的审计修复另加了按
                                    // 长度步长节流——每个 partial_json 都重发整段累积 JSON
                                    // 是 O(n²) 流量——日志同样按步长节流。合并时只落下了
                                    // 后一半（步长），release 默认 Info 级下仍会照写，所以
                                    // 两道门现在都留着：traceEnabled 决定要不要写，步长
                                    // 决定在 debug/Verbose 下写多密。
                                    val n = toolInputBuffer.length
                                    if (com.openminis.app.text.BoundedText.shouldCommitLengthStride(n, lastSentToolInputLen)) {
                                        lastSentToolInputLen = n
                                        if (com.openminis.app.logging.AppLogger.traceEnabled &&
                                            com.openminis.app.text.BoundedText.shouldLogLengthStride(n)
                                        ) {
                                            android.util.Log.d("ToolChain[Provider]", "→ ToolInputDelta id=$currentToolId accumulated=${n}chars")
                                        }
                                        send(LLMStreamChunk.ToolInputDelta(currentToolId!!, toolInputBuffer.toString()))
                                    }
                                }
                            }
                        }
                    }
                    "content_block_stop" -> {
                        if (currentToolId != null && currentToolName != null) {
                            val args = try {
                                JSONObject(toolInputBuffer.toString())
                            } catch (_: Exception) {
                                JSONObject()
                            }
                            android.util.Log.d("ToolChain[Provider]", "→ ToolCallComplete id=$currentToolId name=$currentToolName args=${args.toString().take(300)}")
                            send(LLMStreamChunk.ToolCallComplete(currentToolId!!, currentToolName!!, args))
                            currentToolId = null
                            currentToolName = null
                            toolInputBuffer.clear()
                            lastSentToolInputLen = 0
                        }
                    }
                    "message_delta" -> {
                        event.optJSONObject("usage")?.let { usage ->
                            // [T-anthropic-usage-merge] 合并而非覆盖：官方端点的
                            // delta usage 是累计量（合并等价于原样发出），旧形状
                            // 中转只带 output_tokens（缺 input/cache，合并后由
                            // message_start 补齐）。消费侧 lastUsage 是整对象覆盖。
                            val merged = mergeUsage(streamUsage, parseUsage(usage))
                            streamUsage = merged
                            send(LLMStreamChunk.Usage(merged))
                        }
                        val stopReason = event.optJSONObject("delta")
                            ?.safeOptString("stop_reason", "")?.ifEmpty { null }
                        send(LLMStreamChunk.Finished(stopReason))
                    }
                    // [T-android-fallback-providererror] Anthropic can emit an
                    // `error` frame MID-STREAM (overloaded_error, rate-limit,
                    // an invalidated key) after tokens have already flowed.
                    // This `when` had no branch for it, so the frame was
                    // ignored, the loop ran to EOF and the flow completed
                    // NORMALLY — the turn looked successful, nothing threw, and
                    // the group-fallback decision was never reached. Throwing
                    // here routes it through the same `catch` below as any
                    // other stream failure (`cancel("Stream error", mapError(e))`),
                    // which is what carries the error to the fallback logic.
                    "error" -> {
                        val err = event.optJSONObject("error")
                        val errType = err?.safeOptString("type", "") ?: "error"
                        val errMessage = err?.safeOptString("message", "") ?: payload.take(500)
                        android.util.Log.w(
                            "AnthropicProvider",
                            "SSE error frame mid-stream: type=$errType message=${errMessage.take(200)}",
                        )
                        // Map the documented error `type` onto the same LLMError
                        // shapes mapHttpError produces for the equivalent HTTP
                        // status, so downstream classification (isFallbackable /
                        // isHttpServerError) behaves identically whether the
                        // failure arrived as a status code or as a frame.
                        throw when (errType) {
                            "overloaded_error" ->
                                LLMError.TransientError("[$errType] $errMessage", httpStatus = 529)
                            "api_error" ->
                                LLMError.TransientError("[$errType] $errMessage", httpStatus = 500)
                            "rate_limit_error" -> LLMError.RateLimited()
                            "authentication_error", "permission_error" ->
                                LLMError.InvalidApiKey("[$errType] $errMessage")
                            else -> LLMError.ProviderError("[$errType] $errMessage")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            cancel("Stream error", mapError(e))
        } finally {
            reader.close()
            response.close()
        }
        channel.close()
        awaitClose()
    }

    /**
     * Build the `system` field as a JSON array of content blocks.
     * Mirrors iOS AnthropicProvider.resolveSystemPrompt — two authentication paths:
     *
     * - **OAuth (isOAuth=true)**: must start with the Claude Code prefix block (uncached)
     *   so Anthropic's server-side OAuth check sees the exact expected prompt.
     * - **API key (isOAuth=false)**: no prefix requirement.
     *
     * Returns null when the prompt is null, or empty on the API-key path
     * (iOS parity — no empty `system` field).
     *
     * Breakpoint placement: [systemStablePrefixLen] is the offset up to which
     * the assembled prompt is byte-stable across turns (published by
     * ChatViewModel.buildSystemPrompt). The cache_control breakpoint goes at
     * the end of THAT block, not at the end of the whole prompt: the tail
     * carries per-turn WorldBook hits, scene-classified learned prefs, FTS
     * recall and the runtime context, so a breakpoint after it rewrote the
     * cached prefix on every turn — paid for on write, never read back.
     * With the split, the stable head (identity, tool docs, memory, skills,
     * MCP) hits across turns and only the small tail is re-ingested.
     *
     * Breakpoint budget: this contributes at most ONE cached system block;
     * with the last tool and the last two user messages that is exactly the
     * four breakpoints Anthropic honors.
     */
    internal fun resolveSystemPrompt(userPrompt: String?, systemStablePrefixLen: Int = -1): JSONArray? {
        if (userPrompt == null) return null
        if (!isOAuth && userPrompt.isEmpty()) return null
        val prefix = if (isOAuth) oauthIdentifierPromptProvider() else ""
        val arr = JSONArray()
        for (block in systemCacheBlocks(userPrompt, systemStablePrefixLen, isOAuth, prefix)) {
            arr.put(
                JSONObject().apply {
                    put("type", "text")
                    put("text", block.text)
                    if (block.cached) put("cache_control", ephemeralCacheControl())
                },
            )
        }
        return arr
    }

    private fun buildRequestBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        stream: Boolean,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition> = emptyList(),
        // [T-android-thinking-level-arch] Already clamped to the model ceiling by
        // LLMProvider.streamMessage/sendMessage before reaching here.
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
        systemStablePrefixLen: Int = -1,
    ): JSONObject {
        val body = JSONObject()
        body.put("model", model.id)
        body.put("max_tokens", maxTokens)
        body.put("stream", stream)

        SamplingPolicy.wire(
            SamplingIdentity.of(this), model.id, temperature, thinkingLevel.isEnabled,
        )?.let { body.put("temperature", it) }

        // Thinking / extended thinking. Two protocol shapes:
        //   - Claude 4.6+ (adaptive): thinking.type="adaptive" + output_config.effort.
        //     The legacy budget_tokens form is silently ignored on the wire, which is
        //     the exact "thinking enabled but no thoughts" symptom seen on 4.7.
        //   - Claude <=4.5 (legacy): thinking.type="enabled" + budget_tokens. For these
        //     Anthropic also requires temperature=1 on the request.
        // Claude 4.6+ rejects temperature outright (handled by the temperature guard
        // above; we don't re-inject it here).
        // [T-thinking-rules-phase2] WHICH shape applies is decided by
        // ThinkingRuleResolver.anthropicThinkingShape — one place owns every vendor's
        // thinking contract. Emission stays here because Android's Anthropic body also
        // carries `display`/`output_config`/`temperature` companions that the abstract
        // shape deliberately does not model. Behaviour is byte-for-byte unchanged, pinned
        // by ThinkingWireGeminiAnthropicSnapshotTest.
        val thinkShape = ThinkingRuleResolver.anthropicThinkingShape(
            model.id, model.supportsReasoning, thinkingLevel, maxTokens,
        )
        com.openminis.app.logging.AppLogger.info(
            "Thinking",
            "[resolve] provider=anthropic model=${model.id} level=${thinkingLevel.name} " +
                "shape=[${thinkShape.keys.sorted().joinToString(",")}]",
        )
        if (thinkingLevel.isEnabled) {
            if (modelUsesAdaptiveThinking(model.id)) {
                // [T-anthropic-thinking-display] Explicitly request summarized
                // thinking. This is the SECOND half of the "thinking on but no
                // thoughts shown" fix (first half: dropping the redact-thinking
                // beta, f24e3f07). adaptive thinking has a separate `display`
                // field: "summarized" returns readable thinking text; "omitted"
                // leaves the thinking block empty (only a signature) — identical
                // to being redacted. Newer models default `display` to "omitted"
                // (Opus 4.7+ / Fable-tier), so without setting it Claude Sonnet 5
                // etc. still show no thinking text even after the beta was
                // removed. Pin it to "summarized" regardless of the model default.
                body.put("thinking", JSONObject().apply {
                    put("type", "adaptive")
                    put("display", "summarized")
                })
                body.put("output_config", JSONObject().apply {
                    put("effort", thinkingEffort(thinkingLevel))
                })
            } else {
                val budgetTokens = thinkingBudget(maxTokens, thinkingLevel)
                if (budgetTokens > 0) {
                    body.put("thinking", JSONObject().apply {
                        put("type", "enabled")
                        put("budget_tokens", budgetTokens)
                    })
                    if (!modelRejectsTemperature(model.id)) {
                        body.put("temperature", 1)
                    }
                }
            }
        } else if (modelAcceptsExplicitThinkingDisabled(model.id)) {
            // Claude 4.6-4.x think by DEFAULT when the request carries no
            // thinking field at all — "off" must be sent explicitly, or
            // small-maxTokens calls burn the whole budget on thinking_tokens and
            // return zero text (iOS ea86dd8a observed: max_tokens=256 →
            // stop_reason=max_tokens, thinking_tokens=256/256, empty body).
            // Legacy (<=4.5) models default to no thinking, so absence is fine
            // there.
            //
            // [T-android-claude5-thinking-disabled-400] Note this asks
            // modelAcceptsExplicitThinkingDisabled, NOT modelUsesAdaptiveThinking:
            // the latter is true for 4.6+ AND 5+, but Claude 5 rejects the literal
            // with `"thinking.type.disabled" is not supported for this model` and
            // treats an absent field as adaptive — which is exactly what OFF
            // wants. Ported from iOS 69be65763.
            body.put("thinking", JSONObject().put("type", "disabled"))
        }

        // System prompt blocks — mirrors iOS AnthropicProvider.resolveSystemPrompt.
        val systemArray = resolveSystemPrompt(systemPrompt, systemStablePrefixLen)
        if (systemArray != null) {
            body.put("system", systemArray)
        }

        // Tools: enable eager_input_streaming on every tool (GA feature, no beta
        // header required) so the SDK streams partial tool input JSON as it's
        // generated — mirrors iOS `RequestBodyPatcher.injectEagerInputStreaming`.
        // cache_control: ephemeral goes on the *last* tool, matching the first of
        // the four prompt-cache breakpoints Anthropic honors.
        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for ((i, tool) in tools.withIndex()) {
                val toolJson = tool.toAnthropicJson()
                toolJson.put("eager_input_streaming", true)
                if (i == tools.lastIndex) {
                    toolJson.put("cache_control", ephemeralCacheControl())
                }
                toolsArray.put(toolJson)
            }
            body.put("tools", toolsArray)
            body.put("tool_choice", JSONObject().put("type", "auto"))
        }

        // Build and merge messages
        val rawMessages = buildMessages(messages, imageParts)
        val merged = mergeConsecutiveSameRole(rawMessages)

        // Inject cache_control on last 2 user messages
        injectMessageCacheControl(merged)

        body.put("messages", merged)

        return body
    }

    /**
     * Drop `tool_result` parts whose `tool_use_id` does not match a `tool_use`
     * in the most recent assistant message. Anthropic rejects orphan
     * tool_results with 400 (`unexpected tool_use_id ... no corresponding
     * tool_use block`). Operates on a copy so the caller's stored history
     * (DB rows / in-memory state) is untouched — only the outbound payload
     * is sanitized. Mirrors iOS AnthropicAgentProvider.stripOrphanToolResults.
     */
    private fun stripOrphanToolResults(messages: List<LLMMessage>): List<LLMMessage> {
        var liveToolUseIds: Set<String> = emptySet()
        val result = ArrayList<LLMMessage>(messages.size)
        for ((i, msg) in messages.withIndex()) {
            when (msg.role) {
                LLMMessage.Role.ASSISTANT -> {
                    liveToolUseIds = msg.contentParts
                        .filterIsInstance<AgentContentPart.ToolUse>()
                        .map { it.id }
                        .toSet()
                    result.add(msg)
                }
                LLMMessage.Role.USER -> {
                    val original = msg.contentParts
                    if (original.isEmpty()) {
                        result.add(msg)
                    } else {
                        val kept = original.filter { part ->
                            if (part is AgentContentPart.ToolResult) {
                                liveToolUseIds.contains(part.id)
                            } else true
                        }
                        if (kept.size != original.size) {
                            val dropped = original.size - kept.size
                            android.util.Log.i(
                                "AnthropicProvider",
                                "Stripped $dropped orphan tool_result block(s) from outbound payload (idx=$i)"
                            )
                            result.add(msg.copy(contentParts = kept))
                        } else {
                            result.add(msg)
                        }
                    }
                    // Clear so a later user turn can't match a stale assistant.
                    liveToolUseIds = emptySet()
                }
            }
        }
        // Drop user messages that became empty (only orphan tool_results).
        // An empty content array is itself a 400.
        return result.filter { m ->
            // Keep messages that still have either non-empty contentParts or a
            // non-empty `content` string (string-only messages never had parts
            // to strip in the first place).
            m.contentParts.isNotEmpty() || m.content.isNotEmpty()
        }
    }

    /**
     * [T-android-anthropic-thinking-echo] (issue #70) Whether to echo prior
     * assistant `thinking` content blocks back into the history.
     *
     * Anthropic-compat proxies that speak the Anthropic protocol but reason
     * with interleaved (unsigned) thinking — DeepSeek V4 / GLM-4.5+ / Kimi
     * K2.5+ / Nemotron etc. — reject a multi-turn tool-use history where a
     * previous assistant turn produced thinking but that block isn't passed
     * back: 400 `content[].thinking in the thinking mode must be passed back
     * to the API`. The captured reasoning already lives on
     * `LLMMessage.reasoningContent`; we replay it as a synthesized
     * `{"type":"thinking","thinking":…}` block (no `signature` — we never
     * capture one off the wire).
     *
     * Mirrors iOS AnthropicAgentProvider's `echoReasoning` gate exactly:
     *   echo = supportsReasoning != false        (model may reason)
     *          AND interleavedReasoningField != null  (needs + tolerates
     *              UNSIGNED interleaved echo)
     *          AND !official Anthropic endpoint.
     * The `interleavedReasoningField` gate is load-bearing: the real
     * api.anthropic.com and strict Claude-class proxies verify the
     * `signature` field and reject an unsigned thinking block with
     * `thinking.signature: invalid_request_error`, so we must NOT echo there
     * even when supportsReasoning=true. Independent of the user's thinking
     * toggle — DeepSeek V4 emits thinking even when we don't request it.
     */
    private fun shouldEchoInterleavedThinking(): Boolean {
        val modelMayReason = model.supportsReasoning != false
        val modelRequiresInterleave = model.interleavedReasoningField != null
        return modelMayReason && modelRequiresInterleave && isCustomEndpoint
    }

    /** Build the messages JSONArray from LLMMessage list. */
    private fun buildMessages(
        rawMessages: List<LLMMessage>,
        imageParts: List<LLMMessage.ImagePart>,
    ): JSONArray {
        // Sanitize orphan tool_results before serialization. Operates on a
        // copy — caller's stored history is untouched.
        val messages = stripOrphanToolResults(rawMessages)
        // [T-android-anthropic-thinking-echo] (issue #70) Compute once — the
        // gate depends only on the model + endpoint, not the message.
        val echoThinking = shouldEchoInterleavedThinking()
        val messagesArray = JSONArray()
        for ((index, msg) in messages.withIndex()) {
            val obj = JSONObject()
            obj.put("role", msg.role.value)

            if (msg.contentParts.isNotEmpty()) {
                val contentArray = JSONArray()
                // [T-android-anthropic-thinking-echo] (issue #70) Prepend the
                // synthesized thinking block FIRST so it precedes text/tool_use
                // (Anthropic requires the thinking block to lead the assistant
                // turn). Replay captured reasoning verbatim; fall back to an
                // empty string as a field-presence placeholder for turns whose
                // reasoning was never captured (interrupted stream, history from
                // before thinking was on, cross-provider session) — without it
                // the compat proxy still 400s. Mirrors iOS
                // injectThinkingBlocksForCompatProxy (unsigned block, no
                // signature). User turns never carry thinking.
                if (echoThinking && msg.role == LLMMessage.Role.ASSISTANT) {
                    contentArray.put(JSONObject().apply {
                        put("type", "thinking")
                        put("thinking", msg.reasoningContent ?: "")
                    })
                }
                for (part in msg.contentParts) {
                    when (part) {
                        is AgentContentPart.Text -> {
                            contentArray.put(JSONObject().apply {
                                put("type", "text")
                                put("text", part.text)
                            })
                        }
                        is AgentContentPart.ToolUse -> {
                            // Anthropic requires tool ids to match ^[a-zA-Z0-9_-]+$.
                            // OpenAI Responses can emit ids like `call_abc|fc_def`
                            // (pipe), so echo every id through sanitizeToolId.
                            contentArray.put(JSONObject().apply {
                                put("type", "tool_use")
                                put("id", sanitizeToolId(part.id))
                                put("name", part.name)
                                put("input", part.input)
                            })
                        }
                        is AgentContentPart.ToolResult -> {
                            val resultContent = JSONArray()
                            resultContent.put(JSONObject().apply {
                                put("type", "text")
                                put("text", part.content)
                            })
                            val imageData = part.imageData
                            val imageMimeType = part.imageMimeType
                            if (imageData != null && imageMimeType != null) {
                                // T-imgsize: belt-and-braces — history tool-result
                                // screenshots can be raw 12-megapixel PNGs that
                                // never went through the composer's budget pass.
                                // Re-encode in-place if a single part already blows
                                // the per-image cap so we don't 413 on replay.
                                // [T-android-image-upload-format] (上游 1.14) 改走
                                // imageBlock → ImageBudget.prepareForUpload：统一字节预算、
                                // 按真实字节声明 MIME，发不出去时降级成文字占位。比我们原来的
                                // compressUnderBudget +「字节没变就沿用原 MIME」更安全。
                                //
                                // [T-u12-merge] 传上面那两个局部 val，不要写回
                                // part.imageData/part.imageMimeType：AgentContentPart 在
                                // :core:model 里，跨模块的 val 属性拿不到这里的非空智能转换
                                // （编译器只认被判空的局部变量），写属性就会报
                                // ByteArray? ≠ ByteArray。
                                resultContent.put(imageBlock(imageData, imageMimeType))
                            }
                            contentArray.put(JSONObject().apply {
                                put("type", "tool_result")
                                put("tool_use_id", sanitizeToolId(part.id))
                                put("content", resultContent)
                                if (part.isError) put("is_error", true)
                            })
                        }
                        is AgentContentPart.ImageData -> {
                            // T-imgsize: provider-boundary backstop for history
                            // image bytes that bypassed the composer budget
                            // (restored sessions, retry-after-edit, agent-emitted
                            // ImageData). Composer already capped fresh sends.
                            contentArray.put(imageBlock(part.data, part.mimeType))
                        }
                    }
                }
                obj.put("content", contentArray)
            } else {
                val isLastUser = msg.role == LLMMessage.Role.USER &&
                    index == messages.indexOfLast { it.role == LLMMessage.Role.USER }
                if (isLastUser && imageParts.isNotEmpty()) {
                    val contentArray = JSONArray()
                    for (part in imageParts) {
                        // T-imgsize: backstop for the legacy ImagePart path used
                        // when contentParts is empty (older user-message shape
                        // pre-T132). Composer already runs ImageBudget but this
                        // arm is also reached on session restore where
                        // contentParts may not be populated.
                        contentArray.put(imageBlock(part.data, part.mimeType))
                    }
                    contentArray.put(JSONObject().apply {
                        put("type", "text")
                        put("text", msg.content)
                    })
                    obj.put("content", contentArray)
                } else if (echoThinking && msg.role == LLMMessage.Role.ASSISTANT) {
                    // [T-android-anthropic-thinking-echo] (issue #70) Assistant
                    // turn with no structured parts (plain-text reply, or history
                    // rehydrated without contentParts). Still must carry its
                    // thinking block, so materialize the string content into an
                    // array with the thinking block first.
                    val contentArray = JSONArray()
                    contentArray.put(JSONObject().apply {
                        put("type", "thinking")
                        put("thinking", msg.reasoningContent ?: "")
                    })
                    if (msg.content.isNotEmpty()) {
                        contentArray.put(JSONObject().apply {
                            put("type", "text")
                            put("text", msg.content)
                        })
                    }
                    obj.put("content", contentArray)
                } else {
                    obj.put("content", msg.content)
                }
            }

            messagesArray.put(obj)
        }
        return messagesArray
    }

    /**
     * Merge consecutive messages with the same role.
     * Anthropic API requires alternating user/assistant messages.
     * For assistant messages, reorder: text/image first, then tool_use (Anthropic
     * rejects text appearing after tool_use in the same message).
     */
    /**
     * [T-android-image-upload-format] One inline image as an Anthropic content
     * block. Format, byte budget and `media_type` all come from
     * [ImageBudget.prepareForUpload], so the label always matches the bytes;
     * an image that cannot be made sendable becomes a text block instead of a
     * request the API rejects on every retry.
     */
    private fun imageBlock(data: ByteArray, declaredMime: String?): JSONObject {
        val upload = ImageBudget.prepareForUpload(data, declaredMime)
            ?: return JSONObject().apply {
                put("type", "text")
                put("text", ImageBudget.UNSENDABLE_IMAGE_PLACEHOLDER)
            }
        return JSONObject().apply {
            put("type", "image")
            put("source", JSONObject().apply {
                put("type", "base64")
                put("media_type", upload.mimeType)
                put("data", upload.base64())
            })
        }
    }

    private fun mergeConsecutiveSameRole(messages: JSONArray): JSONArray {
        if (messages.length() <= 1) return messages
        val result = JSONArray()
        var i = 0
        while (i < messages.length()) {
            val current = messages.getJSONObject(i)
            val role = current.getString("role")
            // Collect all consecutive messages with same role
            val contentBlocks = mutableListOf<JSONObject>()
            collectContentBlocks(current, contentBlocks)
            var j = i + 1
            while (j < messages.length() && messages.getJSONObject(j).getString("role") == role) {
                collectContentBlocks(messages.getJSONObject(j), contentBlocks)
                j++
            }
            if (j > i + 1 || contentBlocks.isNotEmpty()) {
                // Reorder assistant content: thinking first, then text/image,
                // then tool_use last. Anthropic rejects text before thinking and
                // text after tool_use in the same turn; when several assistant
                // messages merge (T-android-anthropic-thinking-echo, issue #70)
                // this hoists every synthesized thinking block ahead of the text
                // so the merged turn still leads with thinking.
                if (role == "assistant") {
                    val thinking = contentBlocks.filter { it.optString("type") == "thinking" }
                    val other = contentBlocks.filter {
                        val t = it.optString("type")
                        t != "thinking" && t != "tool_use"
                    }
                    val toolUse = contentBlocks.filter { it.optString("type") == "tool_use" }
                    contentBlocks.clear()
                    contentBlocks.addAll(thinking)
                    contentBlocks.addAll(other)
                    contentBlocks.addAll(toolUse)
                }
                val merged = JSONObject().apply {
                    put("role", role)
                    put("content", JSONArray(contentBlocks.map { it }))
                }
                result.put(merged)
            } else {
                result.put(current)
            }
            i = j
        }
        return result
    }

    /** Extract content blocks from a message into a flat list. */
    private fun collectContentBlocks(msg: JSONObject, out: MutableList<JSONObject>) {
        val content = msg.opt("content")
        when (content) {
            is JSONArray -> {
                for (k in 0 until content.length()) {
                    out.add(content.getJSONObject(k))
                }
            }
            is String -> {
                out.add(JSONObject().apply {
                    put("type", "text")
                    put("text", content)
                })
            }
        }
    }

    /** Inject cache_control on the last 2 user messages' last content block. */
    private fun injectMessageCacheControl(messages: JSONArray) {
        var userCount = 0
        for (i in messages.length() - 1 downTo 0) {
            val msg = messages.getJSONObject(i)
            if (msg.getString("role") != "user") continue
            userCount++
            if (userCount > 2) break
            val content = msg.opt("content")
            if (content is JSONArray && content.length() > 0) {
                val lastBlock = content.getJSONObject(content.length() - 1)
                lastBlock.put("cache_control", ephemeralCacheControl())
            }
        }
    }

    companion object {
        /** One `system` content block, plus whether it carries a cache breakpoint. */
        internal data class SystemBlock(val text: String, val cached: Boolean)

        /**
         * Split an assembled system prompt into cache blocks. Pure, so the
         * breakpoint placement is testable without a provider instance.
         *
         * [stablePrefixLen] is the offset before which the prompt is byte-stable
         * across turns; everything at or after it varies per turn. Values outside
         * `1 until length` mean "no split known" and reproduce the previous
         * single-cached-block shape, so callers that do not track the boundary
         * (title generation, compaction, sub-agents) behave exactly as before.
         */
        internal fun systemCacheBlocks(
            userPrompt: String,
            stablePrefixLen: Int,
            isOAuth: Boolean,
            claudeCodePrefix: String,
        ): List<SystemBlock> {
            val split = stablePrefixLen in 1 until userPrompt.length
            val stable = if (split) userPrompt.substring(0, stablePrefixLen) else userPrompt
            val dynamic = if (split) userPrompt.substring(stablePrefixLen).trimStart('\n') else ""
            val blocks = mutableListOf<SystemBlock>()
            if (isOAuth) {
                // The OAuth gate compares this prefix verbatim: first block, and
                // never carrying a breakpoint of its own.
                blocks += SystemBlock(claudeCodePrefix, cached = false)
                val rest =
                    if (stable.startsWith(claudeCodePrefix)) {
                        stable.removePrefix(claudeCodePrefix).trimStart('\n')
                    } else {
                        stable
                    }
                if (rest.isNotEmpty()) blocks += SystemBlock(rest, cached = true)
            } else if (stable.isNotEmpty()) {
                blocks += SystemBlock(stable, cached = true)
            }
            if (dynamic.isNotEmpty()) blocks += SystemBlock(dynamic, cached = false)
            return blocks
        }
        /**
         * Parse the `-<major>-<minor>` (or `/<major>.<minor>`) version out of a Claude id.
         * [T-anthropic-temp-claude5-android] The minor segment is OPTIONAL and
         * defaults to 0: single-segment ids (claude-fable-5, claude-opus-5,
         * future claude-*-6) parse as (major, 0). Previously they returned
         * null, so modelRejectsTemperature said false and the API answered
         * 400 "temperature is deprecated for this model"; the same null also
         * blinded modelUsesAdaptiveThinking and supportsThinking for the
         * whole 5-series. The optional group is greedy, so a real pair like
         * claude-3-5-sonnet still wins as (3,5) — never (3,0).
         * Returns null for non-Claude ids or ids without any version digits.
         *
         * TWO POSITIONAL BUGS FIXED (mirrored on iOS AnthropicProvider.swift):
         *
         * 1. The minor group is capped at 1-2 digits. It was uncapped and
         *    greedy, so a SINGLE-segment version followed by a snapshot stamp
         *    swallowed the stamp: `claude-sonnet-4-20250514` (Claude Sonnet 4.0)
         *    parsed as (4, 20250514) and was therefore treated as >= 4.6 —
         *    temperature dropped, adaptive effort sent, and
         *    `thinking.type=disabled` sent, none of which a 4.0 model accepts.
         *    Two shipping catalog ids hit this (claude-sonnet-4-20250514,
         *    claude-opus-4-20250514, plus their `anthropic/` forms). No real
         *    Claude version has ever had a 3+ digit minor, and a version PAIR is
         *    unaffected either way (`claude-opus-4-8-20260115` -> (4,8)) because
         *    the pair consumes the separator first.
         *
         * 2. The search STARTS AFTER the word "claude". It used to take the
         *    first version-shaped run anywhere in the id, so a namespace
         *    carrying digits before the family name won: `v2-gateway/claude-opus-5`
         *    parsed as (2, 0), and `inst-53/claude-haiku-4-5` as (53, 0). Both
         *    would then take `temperature` plus legacy budget thinking — the
         *    exact 400 and silent no-op this commit family exists to prevent.
         *    Only digits AFTER the family name can be the version.
         */
        private fun parseClaudeVersion(modelId: String): Pair<Int, Int>? {
            val lower = modelId.lowercase()
            val claudeAt = lower.indexOf("claude")
            if (claudeAt < 0) return null
            val afterClaude = lower.substring(claudeAt + "claude".length)
            val regex = Regex("""[-/]?(\d+)(?:[-.](\d{1,2}))?(?:$|[^0-9])""")
            val match = regex.find(afterClaude) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: 0
            return major to minor
        }

        /**
         * Claude models from 4.6 onward reject the `temperature` parameter.
         * Returns true when the model id parses as Claude version >= 4.6.
         */
        fun modelRejectsTemperature(modelId: String): Boolean {
            val (major, minor) = parseClaudeVersion(modelId) ?: return false
            return major > 4 || (major == 4 && minor >= 6)
        }

        /**
         * Claude 4.6 and later use *adaptive* thinking (`thinking.type="adaptive"` plus
         * `output_config.effort = low|medium|high|xhigh|max`) and silently ignore the
         * older `thinking.type="enabled" + budget_tokens` form. Older Claude models still
         * need the legacy budget-based form.
         */
        fun modelUsesAdaptiveThinking(modelId: String): Boolean {
            val (major, minor) = parseClaudeVersion(modelId) ?: return false
            return major > 4 || (major == 4 && minor >= 6)
        }

        /**
         * [T-android-claude5-thinking-disabled-400] Whether this model accepts an
         * explicit `thinking: {"type": "disabled"}` on the wire. Ported from iOS
         * `AnthropicProvider.modelAcceptsExplicitThinkingDisabled` (69be65763).
         *
         * Claude 4.6-4.x adaptive models DO: they think by default when the field
         * is absent, so "thinking off" has to be stated, or a small-maxTokens call
         * burns its whole budget on thinking and returns empty text (the reason the
         * disabled literal exists at all).
         *
         * Claude 5+ does NOT. The API rejects the value outright:
         *
         *     [invalid_request_error] "thinking.type.disabled" is not supported
         *     for this model. Thinking defaults to adaptive mode when not
         *     specified; use "thinking.type.enabled" with "budget_tokens" for
         *     extended thinking.
         *
         * So on 5+ the correct encoding of "off" is to send NO thinking field —
         * the server's adaptive default handles it. Reported on iOS against fable 5
         * (Claude Opus 5, official OAuth) where every non-thinking message 400'd;
         * Android had the same conflation and the same exposure for claude-sonnet-5.
         *
         * This is the split iOS made: `modelUsesAdaptiveThinking` answers "does
         * this model think by default / take an effort tier" (4.6+ AND 5+), while
         * this predicate answers "may we send thinking.type=disabled" (4.6-4.x
         * only). They are NOT the same question.
         *
         * Deliberately keyed on the parsed version rather than a model allowlist:
         * this is a generation-wide API contract change, and an allowlist would
         * 400 again on the next Claude 5 variant nobody remembered to add.
         */
        fun modelAcceptsExplicitThinkingDisabled(modelId: String): Boolean {
            val (major, minor) = parseClaudeVersion(modelId) ?: return false
            return major == 4 && minor >= 6
        }

        /**
         * [T-android-claude-opus48-thinking-toggle] (Sow Sow 38845/38850) True
         * when this Claude model supports extended thinking — i.e. the Deep
         * Thinking toggle should appear for it. Anthropic added extended thinking
         * with Claude 3.7; every 4.x model (opus / sonnet / haiku) supports it.
         * Used to stamp `supportsReasoning = true` on models fetched from
         * `/v1/models` (which returns no capability metadata), so the toggle shows
         * on a direct-Anthropic instance even when models.dev hasn't catalogued
         * the model yet (e.g. brand-new Opus 4.8). The provider already builds the
         * correct `thinking` request for any such model (see
         * modelUsesAdaptiveThinking / the legacy budget path), so this is purely
         * the UI-capability flag those request paths assumed.
         */
        fun supportsThinking(modelId: String): Boolean {
            val (major, minor) = parseClaudeVersion(modelId) ?: return false
            return major > 4 || major == 4 || (major == 3 && minor >= 7)
        }

        /**
         * Calculate thinking budget tokens based on level (legacy <=4.5 protocol).
         *
         * [T-thinking-ladder-shared] Replaces a hardcoded absolute ladder
         * (OFF 0 / LOW 8192 / MEDIUM 32768 / HIGH min(mt,65536) / XHIGH·MAX·ULTRA mt)
         * that collapsed HIGH through ULTRA onto ONE number for every
         * maxTokens <= 65536 — measured BEFORE the change:
         *   maxTokens   8192 → 8191/8191/8191/8191/8191 (LOW..ULTRA, five identical)
         *   maxTokens  16384 → 8192/16383/16383/16383/16383
         *   maxTokens  32768 → 8192/32767/32767/32767/32767
         *   maxTokens  65536 → 8192/32768/65535/65535/65535
         *   maxTokens 131072 → 8192/32768/65536/131071/131071
         * A user dragging High → Ultra changed nothing on the wire.
         *
         * WHY `maxTokens` IS THE CORRECT CAPACITY HERE, unlike the other sites:
         * `budget_tokens` is inherently REQUEST-scoped — Anthropic validates it
         * against the `max_tokens` of the very request that carries it, and a budget
         * taken from a model-wide ceiling would be rejected as `budget_tokens must
         * be less than max_tokens` the moment the two diverge. The Zen lane can
         * scale against `LLMModel.maxOutputTokens` because its knob bounds the whole
         * response; this one cannot, so the request's own budget is the capacity.
         *
         * The OFF -> 0 mapping is exact and preserved (the caller only reaches this
         * for enabled levels, but anthropicThinkingShape and the tests do not, and
         * OFF must never emit a budget).
         */
        fun thinkingBudget(maxTokens: Int, level: ThinkingLevel): Int {
            if (level == ThinkingLevel.OFF) return 0
            // [T-thinking-ladder-shared] Non-positive maxTokens cannot host any
            // budget (Anthropic 400s on budget_tokens >= max_tokens, and 0 is the
            // vendor default). Previously `minOf(cap, 0)` produced 0 for every level
            // anyway, so this preserves the value while stating the intent.
            // [T-thinking-ladder-shared] `budgetFor` coerces into `[FLOOR, capacity-1]`
            // BEFORE its own degenerate branch, so a capacity at or below FLOOR (512)
            // throws instead of degrading. This subsumes the old `maxTokens < 2` guard
            // (and the `maxTokens > 1` condition on the pullback below): a
            // budget_tokens under the 512 floor cannot be expressed on this ladder at
            // all, so emit none — the caller's `if (budgetTokens > 0)` gate then leaves
            // `thinking` off entirely. Previously `minOf(cap, 0)` produced 0 for these
            // anyway, so the returned VALUE is unchanged for every input.
            if (maxTokens <= ThinkingLadder.FLOOR) return 0
            val cap = ThinkingLadder.budgetFor(level, maxTokens)
            // Anthropic requires budget_tokens STRICTLY LESS THAN max_tokens; an equal
            // value is a 400 ("thinking.budget_tokens must be less than
            // max_tokens"). [T-thinking-ladder-shared] ThinkingLadder already returns
            // a value < maxTokens (its top rung is 15/16 of capacity), so this
            // pullback is now a belt-and-braces assertion of an API invariant rather
            // than the mechanism that was silently collapsing four levels together.
            // It is kept deliberately: the requirement is the API's, not ours, and a
            // future ladder change must not be able to trip a 400.
            val clamped = minOf(cap, maxTokens)
            return if (clamped >= maxTokens) maxTokens - 1 else clamped
        }

        /**
         * Map ThinkingLevel onto Anthropic adaptive thinking effort strings (Claude 4.6+).
         * `xhigh` maps to "max" — both 4.6 and 4.7 advertise "max" universally; 4.7 also
         * lists "xhigh" but we standardize on "max" for cross-version compatibility.
         */
        fun thinkingEffort(level: ThinkingLevel): String = when (level) {
            ThinkingLevel.OFF -> "low"  // unreached: caller checks isEnabled
            ThinkingLevel.LOW -> "low"
            ThinkingLevel.MEDIUM -> "medium"
            ThinkingLevel.HIGH -> "high"
            // [T-android-thinking-level-arch] Anthropic tops out at "max"; XHIGH
            // and the new MAX/ULTRA tiers all collapse onto it.
            ThinkingLevel.XHIGH,
            ThinkingLevel.MAX,
            ThinkingLevel.ULTRA -> "max"
        }
    }

    /**
     * T302: takes the pre-serialized body string AND the JSONObject. The
     * string is used directly for the OkHttp RequestBody so we don't pay
     * for a second `body.toString()` (each call is tens of MB on long
     * agent loops). The JSONObject is still needed for header logic that
     * inspects fields like `thinking`. See OpenAIProvider.buildRequest
     * for the same pattern.
     */
    private fun buildRequest(bodyStr: String, body: JSONObject): Request {
        // T192: `basePath` may already end in `/v1` because
        // `ProviderInstance.effectiveBaseURL` appends `/v1` when
        // `appendV1Suffix=true` and the user-entered base doesn't end in `/v1`.
        // Without stripping here we'd produce `…/v1/v1/messages` for Anthropic-
        // compatible endpoints (DeepSeek, Bailian, etc.). Mirrors
        // `AnthropicProvider.swift::stripV1Suffix` (iOS) so the same custom
        // base URL works on both platforms.
        val cleanBase = basePath.trimEnd('/').let {
            if (it.endsWith("/v1")) it.dropLast(3).trimEnd('/') else it
        }
        val builder = Request.Builder()
            .url("$cleanBase/v1/messages")
            .post(bodyStr.toRequestBody("application/json".toMediaType()))
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")

        // OkHttp forbids multiple values for the same header slot via `.header(...)`,
        // so build a combined anthropic-beta string and set it once.
        //
        // OAuth (Claude Code) credentials are served with the Claude CLI's
        // request profile — the anthropic-beta set, User-Agent and X-Stainless-*
        // headers. Without it some features (e.g. thinking on 4.7) are not
        // enabled for these credentials. Values tracked from Wei-Shaw/sub2api
        // backend/internal/pkg/claude/constants.go.
        //
        // For API-key / custom endpoints we only carry the betas actually needed
        // by the request body; we must NOT include oauth-2025-04-20 or
        // claude-code-20250219 there because those signal Claude-Code-only
        // surface and get rejected on plain API-key auth.
        val betaFlags = mutableListOf<String>()
        if (isOAuth) {
            // [T-anthropic-redact-thinking] Deliberately OMIT
            // "redact-thinking-2026-02-12" from the Claude Code OAuth betas.
            // When present, Anthropic redacts the plaintext of `thinking` content
            // blocks (returns an empty `thinking` string with only a `signature`),
            // so a reasoning model runs (usage.thinking_tokens > 0) but the App
            // can't show any thinking text. The official Claude Code CLI only adds
            // this beta when `showThinkingSummaries` is unset/false (per
            // anthropics/claude-code#31326 and the
            // code.claude.com model-config docs); omitting it is equivalent to
            // `showThinkingSummaries: true` — pure UI visibility, no effect on
            // reasoning quality or token budget. All other OAuth betas stay.
            betaFlags.addAll(listOf(
                "claude-code-20250219",
                "oauth-2025-04-20",
                "interleaved-thinking-2025-05-14",
                "prompt-caching-scope-2026-01-05",
                "effort-2025-11-24",
                "context-management-2025-06-27",
                "extended-cache-ttl-2025-04-11",
            ))
        } else if (body.has("thinking")) {
            val isAdaptive = body.optJSONObject("thinking")?.optString("type") == "adaptive"
            if (isAdaptive) {
                betaFlags.add("effort-2025-11-24")
            } else {
                betaFlags.add("interleaved-thinking-2025-05-14")
            }
        }
        // [T-android-enhanced-cache] API-key path only carries the betas the
        // body needs; the 1-hour cache TTL requires this flag. OAuth already
        // lists it above, so guard on !isOAuth to avoid a duplicate token.
        if (enhancedCache && !isOAuth) {
            betaFlags.add("extended-cache-ttl-2025-04-11")
        }
        if (betaFlags.isNotEmpty()) {
            builder.header("anthropic-beta", betaFlags.joinToString(","))
        }

        // Stainless / CLI fingerprint headers — only on OAuth; bump in lockstep
        // with sub2api when the real CLI version moves.
        //
        // [T-anthropic-fable51-android] 2.1.195 -> 2.1.251. Anthropic gates
        // models on this version: below 2.1.251 a claude-fable-5-1 request is
        // refused with "Claude Code <ver> does not support this model; version
        // 2.1.251 or newer is required." 2.1.251 is the value CLIProxyAPI
        // adopted for exactly this reason (router-for-me/CLIProxyAPI#5405,
        // bumping their defaultClaudeFingerprintUserAgent from 2.1.220), not a
        // number picked to clear the error message.
        //
        // [T-anthropic-opus55-catalog] 2.1.251 -> 2.1.280, for the same class
        // of gate one generation later: Claude Opus 5.5 is refused below
        // 2.1.280, and the refusal is a plain 400 that reads like a bad model
        // id rather than a version problem. The floor only ever rises, so
        // every model that worked at 2.1.251 (Fable 5.1, Sonnet/Opus 4.x, …)
        // is unaffected — the gate is "at least", not "exactly".
        //
        // The X-Stainless-* values below are deliberately NOT touched: they
        // describe the SDK/runtime, not the CLI, and the backend pairs UA with
        // them as one registered client identity. Changing them speculatively
        // is how a working fingerprint gets broken.
        if (isOAuth) {
            builder.header("User-Agent", "claude-cli/2.1.280 (external, cli)")
            builder.header("X-Stainless-Lang", "js")
            builder.header("X-Stainless-Package-Version", "0.106.0")
            builder.header("X-Stainless-OS", "Linux")
            builder.header("X-Stainless-Arch", "arm64")
            builder.header("X-Stainless-Runtime", "node")
            builder.header("X-Stainless-Runtime-Version", "v24.18.0")
            builder.header("X-Stainless-Retry-Count", "0")
            builder.header("X-Stainless-Timeout", "600")
            builder.header("X-App", "cli")
            builder.header("Anthropic-Dangerous-Direct-Browser-Access", "true")
        }

        if (isOAuth) {
            builder.header("Authorization", "Bearer $apiKey")
        } else if (apiKey.isEmpty()) {
            // [T-empty-key-compat-endpoints] Keyless third-party
            // Anthropic-compatible endpoint: send NO auth header rather than
            // a malformed `Bearer ` / empty x-api-key that strict relays
            // reject. Only reachable for custom-endpoint instances — routing
            // never builds a keyless provider for the official API.
        } else if (isCustomEndpoint) {
            builder.header("Authorization", "Bearer $apiKey")
        } else {
            builder.header("x-api-key", apiKey)
        }

        // [T-provider-custom-user-agent] Applied last so a non-blank override
        // wins over the OAuth claude-cli UA above. null/blank → fall back to
        // the branded Minis UA on the regular apiKey path, but on the OAuth
        // path keep the claude-cli/2.1.280 fingerprint set above (the
        // Anthropic OAuth backend pairs UA + X-Stainless-* and rejects calls
        // whose UA doesn't match the registered client identity). T-android-
        // default-ua: pass defaultUserAgent=null on OAuth, branded default
        // everywhere else.
        builder.applyUserAgentOverride(
            customUserAgent,
            defaultUserAgent = if (isOAuth) null else com.openminis.app.provider.MinisUserAgent.DEFAULT,
        )
        return builder.build()
    }

    private fun parseResponse(json: JSONObject): LLMResponse {
        val content = json.optJSONArray("content")
        val text = buildString {
            if (content != null) {
                for (i in 0 until content.length()) {
                    val block = content.getJSONObject(i)
                    if (block.safeOptString("type", "") == "text") {
                        append(block.safeOptString("text", ""))
                    }
                }
            }
        }
        val stopReason = json.safeOptString("stop_reason", "").ifEmpty { null }
        val usage = json.optJSONObject("usage")?.let { parseUsage(it) }
        return LLMResponse(text, stopReason, usage)
    }

    private fun parseUsage(json: JSONObject): LLMUsage {
        val inputTokens = json.optInt("input_tokens", 0)
        val cacheCreationInputTokens = json.optInt("cache_creation_input_tokens").takeIf { it > 0 }
        val cacheReadInputTokens = json.optInt("cache_read_input_tokens").takeIf { it > 0 }
        return LLMUsage(
            inputTokens = inputTokens,
            outputTokens = json.optInt("output_tokens", 0),
            cacheCreationInputTokens = cacheCreationInputTokens,
            cacheReadInputTokens = cacheReadInputTokens,
            // [T-anthropic-cache-context] 官方语义里 input_tokens 是 fresh-only
            // （最后一个 cache breakpoint 之后的增量），被缓存的前缀单独计在
            // cache_read/cache_creation 里。而本类无条件打 cache 断点（system
            // 稳定段 / 最后一个 tool / 最后两条 user），所以第二轮起
            // latestContextTokens = inputTokens 会系统性低估真实上下文——system
            // prompt 与 tools schema 恰好都在被缓存的那块里，且
            // estimateAgentHistoryTokens() 不覆盖这两项，无兜底。上下文压力
            // 提示（contextTokensForPolicy）与 dynamicMaxTokens 的 remaining
            // 因此双双偏松。inputTokens 保持 fresh-only（SubAgentRunner 的共享
            // 预算按这个口径注释），只把 context 口径补回缓存部分。
            latestContextTokens = inputTokens + (cacheReadInputTokens ?: 0) + (cacheCreationInputTokens ?: 0),
        )
    }

    /**
     * [T-anthropic-usage-merge] 合并 message_start 与 message_delta 两次 usage。
     *
     * 官方端点的 message_delta usage 是**累计量**（含 input/cache），此时合并是
     * 恒等操作。但本 app 支持 customBaseURL 中转，部分 Anthropic 兼容端点沿用旧
     * 形状（message_delta 只带 output_tokens）；消费侧是纯字段覆盖赋值
     * （ChatViewModelAgentLoopExt `lastUsage = chunk.usage`），旧代码直接
     * `send(parseUsage(delta))` 会把 input/cache 清零。按"非零字段补齐"合并后两种
     * 形状都得到完整口径，官方端点行为不变。
     */
    private fun mergeUsage(base: LLMUsage?, delta: LLMUsage): LLMUsage {
        if (base == null) return delta
        return delta.copy(
            inputTokens = if (delta.inputTokens > 0) delta.inputTokens else base.inputTokens,
            cacheCreationInputTokens = delta.cacheCreationInputTokens ?: base.cacheCreationInputTokens,
            cacheReadInputTokens = delta.cacheReadInputTokens ?: base.cacheReadInputTokens,
            latestContextTokens = if (delta.latestContextTokens > 0) delta.latestContextTokens else base.latestContextTokens,
        )
    }

    private fun mapHttpError(statusCode: Int, body: String, retryAfterHeader: String? = null): LLMError {
        if (statusCode == 401 || statusCode == 403) {
            // [T-llm-error-401-model-scope] See OpenAIProviderErrors: an
            // upstream-pool gateway's 401 is often about the model, not the key.
            if (CredentialAcceptance.isAccepted(credentialGateKey)) {
                return LLMError.InvalidApiKey(
                    "HTTP $statusCode — the same credential was accepted by this host recently, " +
                        "so the refusal is about this model, not the key",
                    credentialAccepted = true,
                )
            }
            return LLMError.InvalidApiKey()
        }
        if (statusCode == 429) return HttpRetryAfter.map429(body, retryAfterHeader)

        val message = try {
            val json = JSONObject(body)
            val error = json.optJSONObject("error")
            val errorType = error?.safeOptString("type", "") ?: "error"
            val errorMessage = error?.safeOptString("message", "") ?: body
            "[$errorType] $errorMessage"
        } catch (_: Exception) {
            "HTTP $statusCode: ${body.take(500)}"
        }

        // [T-llm-error-classification] 细分 provider 错误，不再笼统地归 ProviderError。
        val lower = message.lowercase()
        when {
            // "max_tokens too large" 是输出参数超限（max_tokens 请求过大），
            // 不是上下文超长，保持 ProviderError。
            !lower.contains("max_tokens too large") && (
                lower.contains("context_length_exceeded") || lower.contains("maximum context length") ||
                lower.contains("too many tokens") || lower.contains("reduce the length") ||
                (statusCode == 400 && (lower.contains("token") && (lower.contains("max") || lower.contains("limit") || lower.contains("exceed"))))
            ) -> return LLMError.ContextLengthExceeded(message)
            lower.contains("content_filter") || lower.contains("content_policy") ||
            lower.contains("safety") || lower.contains("inappropriate") ||
            statusCode == 422 && lower.contains("content") ->
                return LLMError.ContentFiltered(message)
        }

        val transientCodes = setOf(500, 502, 503, 504, 529)
        if (statusCode in transientCodes) {
            // [T-android-503-fallback] See OpenAIProvider.mapHttpError — the
            // status must survive onto the error so fallback can act on it.
            return LLMError.TransientError(message, httpStatus = statusCode)
        }
        return LLMError.ProviderError(message, httpStatus = statusCode)
    }

    private fun mapError(error: Throwable): LLMError {
        if (error is LLMError) return error
        if (error is java.io.IOException) return LLMError.NetworkError(error)
        return LLMError.Unknown(error)
    }
}
