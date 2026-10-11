package com.openminis.app.provider.openai

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.provider.SamplingIdentity
import com.openminis.app.provider.SamplingPolicy
import com.openminis.app.provider.applyUserAgentOverride
import com.openminis.app.provider.thinking.ThinkingLadder
import com.openminis.app.provider.thinking.ThinkingResolveContext
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal class OpenAIRequestBodies(
    private val host: Host,
) {
    interface Host {
        val model: LLMModel
        val basePath: String
        val extraHeaders: Map<String, String>
        val codexAccountId: String?
        val useResponsesAPI: Boolean
        val forceChatCompletions: Boolean
        val customUserAgent: String?
        val isAzure: Boolean
        val isOAuth: Boolean
        val chatExtraBody: Map<String, Any?>
        val chatExtraHeaders: Map<String, String>
        /**
         * [T-android-model-custom-params] (上游 1.14) 该模型条目上的用户自定义
         * 请求参数（temperature / top_p / 自定义头 / 额外 body 字段）。null =
         * 无覆盖，绝大多数请求走这条早退路径，body 与本次改动前逐字节一致。
         * 与 [chatExtraBody] / [chatExtraHeaders] 刻意分开：那两个是"按次调用"
         * 的透传，归属 ModelUseOffloadHandler，合并会让两者按调用顺序互相覆盖。
         */
        val modelOverrides: com.openminis.app.data.model.ModelOverrides?
        /**
         * [T-android-copilot-per-request-headers] (上游 1.14) 由**本次请求体**
         * 推导的头（Copilot 的 X-Initiator / Copilot-Vision-Request /
         * X-Request-Id）。null = 不需要，多数 provider 都是 null。
         */
        val perRequestHeaders: ((org.json.JSONObject) -> Map<String, String>)?
        /**
         * [T-android-opencode-session-header] (上游 1.14) 本 provider 正在服务的
         * 会话 id，仅用于推导 OpenCode Go 的 `x-opencode-session`。晚读的原因：
         * provider 活得比这个 id 久 —— 新会话在仍是草稿时就建好 provider，真
         * UUID 要等首发的 ensureSession() 落库，构造期取值会让首发缺头。
         */
        val sessionId: String?
        val absoluteEndpointOverride: String?
        val isOpenRouter: Boolean
        val needsOpenRouterAnthropicCacheControl: Boolean
        val isMistral: Boolean
        val isDashScope: Boolean
        val isXAI: Boolean
        /**
         * [T-android-cerebras-reasoning-400] (上游 1.14) Cerebras 的助手消息
         * 是闭集 schema，且它托管 qwen 命名模型 —— 两处门控都需要这个标志，
         * 由 OpenAIProvider 的端口实现转发。
         */
        val isCerebras: Boolean
        val usesUnifiedReasoningEffort: Boolean
        val thinkingRuleInstanceId: String?
        /** True when this instance points at the Zen host with no API key —
         *  the bundled free lane, not a user's paid Zen provider. */
        val isZenFree: Boolean
        fun resolvedServiceTier(): String?
        fun endpointURL(defaultPath: String): String
        fun azureUrl(path: String): String?
        suspend fun getToken(): String
        fun explicitOffEffort(): String?
        fun applyKeyAuth(builder: Request.Builder, token: String): Request.Builder
        val codexClientVersion: String
        fun clampThinkingLevel(level: ThinkingLevel): ThinkingLevel
        val provider: com.openminis.app.provider.LLMProvider
    }

    private fun Request.Builder.applyKeyAuth(token: String): Request.Builder =
        host.applyKeyAuth(this, token)

    // `internal` rather than private so the serialization can be asserted
    // directly in unit tests. The tool-result-image regression this guards
    // (T-android-toolresult-image-dropped) is a property of the request BODY,
    // and going through MockWebServer to read it only adds a network dependency
    // to a question that is pure JSON construction.
    internal fun buildRequestBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        stream: Boolean,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): JSONObject {
        // T264: cross-provider image sanitization, mirrors iOS
        // OpenAIAgentProvider.swift:744-768 / 900-918. When the target model
        // doesn't declare "image" in inputModalities (e.g. DeepSeek V4 after
        // user sent image to GPT-5.5 then switched provider), serialize a
        // text placeholder instead of an image_url block — otherwise the
        // server returns "400 unknown variant `image_url`". Decided once
        // here so the structured-contentParts loop and the legacy
        // imageParts loop below stay consistent.
        // [T-android-vision-native-check-misses-image_input] hasImageInput, not a
        // raw membership test: this compared the catalog string EXACTLY, so
        // "image_input" (OpenAI/OpenRouter) and "Image" both read as "no vision"
        // and the pixels below were swapped for a text placeholder.
        val supportsImages = host.model.hasImageInput
        val body = JSONObject()
        body.put("model", host.model.id)
        if (host.isOpenRouter) {
            body.put("max_tokens", maxTokens)
        } else {
            body.put("max_completion_tokens", maxTokens)
        }
        // [T-zen-stream-only-lane] The Zen free lane REFUSES stream=false with
        // 403 FreeTierError — live-measured 2026-10-05 on-device via adb:
        // stream=true answers 200 on the same session id, model and body, while
        // the identical request with stream=false answers
        // {"type":"error","error":{"type":"FreeTierError",…}} — byte-identical
        // to a request carrying no disguise at all, which is why this read as a
        // session-identity wall. The non-streaming caller
        // [OpenAIProvider.sendMessageClamped] already concatenates SSE deltas
        // into a single response, so forcing stream=true here changes nothing
        // for the caller while passing the lane's only accepted shape.
        val streamForced = if (host.isZenFree) true else stream
        body.put("stream", streamForced)

        // [T-android-xai-priority] xAI Priority Processing, driven by the same
        // app-level Fast Mode toggle as Codex (FastModePrefs), read here at
        // request-build time so a flip applies to the very next request.
        // Emitted only for xAI-capable providers, so every other provider's
        // body is unchanged — `service_tier` is an xAI extension and a strict
        // OpenAI-compatible relay would 400 on the unknown key.
        host.resolvedServiceTier()?.let { body.put("service_tier", it) }

        // [T-A5-builder-writes-caller-temperature] 调用方给了什么就写什么 ——
        // builder 既不发明默认值，也不替调用方把值否掉。上一条 SamplingPolicy 门控
        // 是重复把关：LLMProvider.sendMessage / streamMessage 这两个公共入口
        // 已经用 SamplingPolicy.wire(...) 算好温度再交给 provider，思考开启 /
        // 家族拒收在这里再判一次，只会让**直接调 builder** 的路径（ModelUseOffload
        // Handler 的按次实参、单测）静默丢字段 —— 同一件事放在两处，两边必然漂移。
        // 上游 1.14 OpenAIProvider.kt:2201-2203 就是这两行。
        if (temperature != null) {
            body.put("temperature", temperature)
        }

        if (streamForced && !host.isOpenRouter) {
            body.put("stream_options", JSONObject().put("include_usage", true))
        }

        // Provider-specific thinking params. We always call this — some
        // models (e.g. DeepSeek V4) reason by default and need an explicit
        // `disabled` signal when the user toggles thinking off.
        //
        // [T-android-mistral-reasoning-422] …EXCEPT on Mistral, which rejects
        // the thinking request parameters outright with
        // `422 extra_forbidden body.reasoning`. Mirrors iOS
        // OpenAIAgentProvider.swift's `if !provider.isMistral` gate around this
        // same call (4592ca9b). Until now [isMistral] only suppressed the
        // message-level echo ([forbidReasoningField], 0839f019 / GH
        // OpenMinis#87) — the request-parameter half of that fix was never
        // ported, so an enabled thinking level still put `reasoning_effort` on
        // the wire to api.mistral.ai.
        //
        // [T-zen-free-thinking-budget] The Zen free lane is the exception
        // that replaces the field entirely: it ignores reasoning_effort
        // (live-measured: `low` produced MORE reasoning than `xhigh`, and
        // unknown fields occasionally surfaced as a 503). The only control
        // it enforces is max_completion_tokens, so the thinking level maps
        // to a real generation budget that REPLACES the caller's maxTokens
        // instead of a field the gateway drops. Only the bundled keyless
        // instance qualifies; a user's own paid Zen provider keeps the
        // normal reasoning_effort path untouched.
        if (host.isZenFree) {
            zenBudgetTokens(thinkingLevel, maxTokens)?.let { body.put("max_completion_tokens", it) }
        } else if (!host.isMistral) {
            injectThinkingParams(body, thinkingLevel, maxTokens)
        }

        // [OpenMinis#191] Opt this request into Anthropic prompt caching.
        // OpenRouter passes the field through to Anthropic but never injects it
        // for us, so without it Claude requests cache nothing at all.
        //
        // Top-level "automatic" form: the breakpoint advances to the last
        // cacheable block on its own as the conversation grows, which is what an
        // agent loop wants — the per-block form would need us to hand-manage a
        // 4-breakpoint budget across a mutating history.
        //
        // Gated on host + `anthropic/` prefix, so no other model's body changes.
        if (host.needsOpenRouterAnthropicCacheControl) {
            body.put("cache_control", JSONObject().put("type", "ephemeral"))
        }

        // OpenCode Zen's anonymous lane validates a small tool gate even when
        // the caller has no tools. Keep this scoped to the Zen host; ordinary
        // OpenAI-compatible providers must retain their existing body shape.
        val effectiveTools = if (com.openminis.app.provider.ZenDisguise.isZenHost(host.basePath)) {
            val names = tools.map { it.name }.toSet()
            val gateTools = listOf(
                AgentToolDefinition("bash", "Reserved for the host runtime; do not call it.", emptyMap()),
                AgentToolDefinition("read", "Reserved for the host runtime; do not call it.", emptyMap()),
            )
            tools + gateTools.filter { it.name !in names }
        } else tools

        // Tools
        if (effectiveTools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (tool in effectiveTools) {
                toolsArray.put(tool.toOpenAIJson())
            }
            body.put("tools", toolsArray)
            body.put("tool_choice", if (com.openminis.app.provider.ZenDisguise.isZenHost(host.basePath) && tools.isEmpty()) "none" else "auto")
        }

        val messagesArray = JSONArray()
        if (systemPrompt != null) {
            messagesArray.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
        }

        // Mirror iOS OpenAIAgentProvider.flattenChatCompletionsMessages —
        // echo reasoning_content on prior assistant turns when:
        //   - user requested thinking this turn, OR the model always reasons (forced); AND
        //   - the model isn't explicitly known to reject reasoning.
        // Prevents 400s from Kimi / DeepSeek / GLM / QwQ that reject
        // multi-turn history missing reasoning_content once thinking is on.
        val modelAlwaysReasons = host.model.supportsReasoning == true
        val modelMayReason = host.model.supportsReasoning ?: true
        // [T-android-mistral-reasoning-422] Mistral forbids reasoning_content on
        // assistant messages entirely (closed schema → HTTP 422
        // extra_forbidden), so suppress BOTH the captured echo and the ""
        // placeholder for that endpoint. This cannot be driven by capability
        // metadata: MiMo/DeepSeek require the field's PRESENCE on multi-turn
        // history while Mistral forbids it, and neither advertises
        // supportsReasoning via /v1/models — opposite requirements on the same
        // generic openAI provider path. Hence a spec-driven vendor flag.
        //
        // [T-android-cerebras-reasoning-400] (上游 1.14) Cerebras (OpenMinis#361)
        // 的助手 schema 同样是闭集：把已捕获的 reasoning_content 回显进历史会得到
        // `400 … property 'messages.N.assistant.reasoning_content' is unsupported`，
        // 于是第 1 轮成功、第 2 轮起必然失败。按端点限定，因此要求该字段"必须存在"
        // 的 MiMo / DeepSeek 完全不受影响。
        val forbidReasoningField = host.isMistral || host.isCerebras
        val includeReasoning =
            (thinkingLevel.isEnabled || modelAlwaysReasons) && modelMayReason && !forbidReasoningField
        val echoReasoning = includeReasoning
        // T-mimo-reasoning-echo-34671: Mimo V2.5 returns 400 Param Incorrect on
        // multi-turn tool-call history when any prior assistant turn (especially
        // a tool_calls-bearing one) omits `reasoning_content`. Mimo's docs say
        // the field MUST be present (empty string OK) whenever thinking is on
        // and a tool call is in history. We drop the previous interleaved-only
        // gate and always emit reasoning_content (possibly "") whenever the
        // echo gate (includeReasoning) is true. OpenAI o-series ignores
        // unknown message-level `reasoning_content` so this stays harmless
        // there; non-reasoning models gate this off via includeReasoning=false.
        val placeholderAllowed = includeReasoning

        val lastUserIndex = messages.indexOfLast { it.role == LLMMessage.Role.USER }
        for ((index, msg) in messages.withIndex()) {
            if (msg.contentParts.isNotEmpty()) {
                // Structured content parts
                when {
                    // Assistant with tool_use → emit assistant message with tool_calls
                    msg.role == LLMMessage.Role.ASSISTANT -> {
                        val obj = JSONObject()
                        obj.put("role", "assistant")
                        if (echoReasoning) {
                            val rc = msg.reasoningContent
                            if (rc != null) {
                                // Round-trip exactly what the server emitted,
                                // including empty strings. DeepSeek V4 emits
                                // `reasoning_content: ""` on non-thinking turns
                                // and accepts the same shape on input — the empty
                                // value is the field-presence guarantee that
                                // prevents 400s once thinking is on.
                                obj.put("reasoning_content", rc)
                            } else if (placeholderAllowed) {
                                // No captured reasoning for this turn (e.g. fallback
                                // to a non-thinking model, or message persisted before
                                // thinking was enabled). Send "" rather than a synthetic
                                // marker: prior placeholders ("[no prior reasoning]",
                                // T249; single space, T257) were in-context-learned by
                                // DeepSeek V4 and echoed back as the model's own
                                // reasoning. Empty string satisfies the field-presence
                                // check with no learnable pattern.
                                obj.put("reasoning_content", "")
                            }
                        }
                        val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                        if (textParts.isNotEmpty()) {
                            obj.put("content", textParts.joinToString("") { it.text })
                        }
                        val toolUseParts = msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
                        if (toolUseParts.isNotEmpty()) {
                            val toolCallsArr = JSONArray()
                            for (tu in toolUseParts) {
                                toolCallsArr.put(JSONObject().apply {
                                    put("id", capChatToolCallId(tu.id))
                                    put("type", "function")
                                    put("function", JSONObject().apply {
                                        put("name", tu.name)
                                        put("arguments", tu.input.toString())
                                    })
                                })
                            }
                            obj.put("tool_calls", toolCallsArr)
                        }
                        messagesArray.put(obj)
                    }
                    // User with tool_results → emit separate tool messages
                    msg.role == LLMMessage.Role.USER -> {
                        val toolResults = msg.contentParts.filterIsInstance<AgentContentPart.ToolResult>()
                        val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                        val imageParts = msg.contentParts.filterIsInstance<AgentContentPart.ImageData>()

                        // [T-android-parallel-toolresult-image-split] (上游 1.14)
                        // 每条 `tool` 回复先全部放完，图片载体统一放到**整段之后**。
                        //
                        // 这就是被报告的 bug：过去是"每条结果后面紧跟它自己的图片
                        // 载体 user 消息"。只有一个工具调用时载体落在唯一的 tool 消息
                        // 之后，不会拆开任何东西 —— 所以单图场景一直正常。两个并行
                        // 调用时它插在中间：
                        //
                        //   assistant  tool_calls:[call_01, call_02]
                        //   tool       call_01
                        //   user       [Image returned by read_image]   <-- 拆断整段
                        //   tool       call_02
                        //
                        // OpenAI 兼容端点要求回答同一组 tool_calls 的 `tool` 回复
                        // 紧邻跟随。插进来的 user 消息终结了整段，于是 call_02 被视作
                        // 未应答，DeepSeek 以 `[400] No tool output found for tool
                        // call call_…` 拒绝请求。各 provider 只是严格程度不同 —— GLM
                        // 容忍同一个 body，这正是一个模型能用、另一个不能用的原因；
                        // 但两种情况下请求都是畸形的。
                        //
                        // 因而先收集载体、循环结束后再追加，无论并行了多少个调用，
                        // tool 段都保持不被拆断。
                        val imageCarriers = mutableListOf<JSONObject>()
                        for (tr in toolResults) {
                            messagesArray.put(JSONObject().apply {
                                put("role", "tool")
                                put("tool_call_id", capChatToolCallId(tr.id))
                                put("content", tr.content)
                            })
                            // [T-android-toolresult-image-dropped] THE reported bug.
                            // read_image hands its pixels back on the ToolResult
                            // (ChatViewModel sets imageData on the part), but this
                            // loop only ever emitted `content`, so on a native-vision
                            // model the bytes were dropped at the provider boundary
                            // and the model answered "I can't actually see pixels" —
                            // with a green, successful-looking tool card above it.
                            //
                            // Chat Completions has no image block inside a `tool`
                            // message (the schema takes a plain string), so the
                            // pixels ride on a following USER message that references
                            // the call. Anthropic can nest the image directly in its
                            // tool_result and does (AnthropicProvider ~L600); this is
                            // the same intent expressed in the shape this API allows.
                            val trBytes = tr.imageData
                            if (trBytes != null && trBytes.isNotEmpty() && supportsImages) {
                                // [T-android-image-upload-format] (上游 1.14) 接受的
                                // 格式 + MIME 都取自真正发出去的字节；转码不出来时
                                // 用文字占位符，而不是把原格式塞进 data URL。
                                val upload = com.openminis.app.provider.ImageBudget
                                    .prepareForUpload(trBytes, tr.imageMimeType)
                                imageCarriers.add(JSONObject().apply {
                                    put("role", "user")
                                    put("content", JSONArray().apply {
                                        put(JSONObject().apply {
                                            put("type", "text")
                                            // 点名这次调用，而不只是工具名。两个并行的
                                            // read_image 结果过去都写作
                                            // "[Image returned by read_image]"，
                                            // 载体被归组到整段之后，模型就再无任何线索
                                            // 区分它们。
                                            put(
                                                "text",
                                                "[Image returned by ${tr.name} " +
                                                    "(tool_call_id: ${capChatToolCallId(tr.id)})]",
                                            )
                                        })
                                        put(
                                            if (upload != null) JSONObject().apply {
                                                put("type", "image_url")
                                                put("image_url", JSONObject().apply {
                                                    put("url", upload.dataUrl())
                                                })
                                            } else JSONObject().apply {
                                                put("type", "text")
                                                put("text", com.openminis.app.provider.ImageBudget.UNSENDABLE_IMAGE_PLACEHOLDER)
                                            },
                                        )
                                    })
                                })
                            }
                        }
                        // 载体放这里 —— 在每一条 `tool` 回复之后，回答该 assistant
                        // tool_calls 的整段因此保持连续。
                        for (carrier in imageCarriers) messagesArray.put(carrier)
                        // T132: emit text + image_url parts as a structured user
                        // message. The previous structured-contentParts branch
                        // dropped AgentContentPart.ImageData entirely — only the
                        // legacy non-contentParts path knew how to encode images,
                        // and that path is unreachable once contentParts is
                        // populated (which is always now). Mirrors iOS
                        // OpenAIAgentProvider.swift L732-738.
                        val hasImages = imageParts.isNotEmpty()
                        if (hasImages || textParts.isNotEmpty()) {
                            if (hasImages) {
                                val contentArray = JSONArray()
                                // Walk contentParts in original order so the
                                // [attached image: …] text caption that
                                // precedes each ImageData part stays adjacent
                                // to the right image, matching iOS.
                                for (part in msg.contentParts) {
                                    when (part) {
                                        is AgentContentPart.Text -> {
                                            if (part.text.isNotEmpty()) {
                                                contentArray.put(JSONObject().apply {
                                                    put("type", "text")
                                                    put("text", part.text)
                                                })
                                            }
                                        }
                                        is AgentContentPart.ImageData -> {
                                            if (supportsImages) {
                                                // T-imgsize + [T-android-image-upload-format]
                                                // (上游 1.14): 接受的格式、字节预算，以及
                                                // 与实际发出字节相符的 MIME。
                                                val upload = com.openminis.app.provider.ImageBudget.prepareForUpload(part.data, part.mimeType)
                                                contentArray.put(
                                                    if (upload != null) JSONObject().apply {
                                                        put("type", "image_url")
                                                        put("image_url", JSONObject().apply {
                                                            put("url", upload.dataUrl())
                                                        })
                                                    } else JSONObject().apply {
                                                        put("type", "text")
                                                        put("text", com.openminis.app.provider.ImageBudget.UNSENDABLE_IMAGE_PLACEHOLDER)
                                                    },
                                                )
                                            } else {
                                                // T264: target model has no vision modality —
                                                // emit a text placeholder in place of the pixels.
                                                // [T-android-vision-group / GH#182] Vision-Group
                                                // read_image hint when seeded (carries the path);
                                                // else the historical literal.
                                                contentArray.put(JSONObject().apply {
                                                    put("type", "text")
                                                    put("text", part.noVisionPlaceholder
                                                        ?: "[Image attached but this model does not support vision input]")
                                                })
                                            }
                                        }
                                        else -> Unit  // ToolUse/ToolResult never appear on user role here
                                    }
                                }
                                messagesArray.put(JSONObject().apply {
                                    put("role", "user")
                                    put("content", contentArray)
                                })
                            } else {
                                messagesArray.put(JSONObject().apply {
                                    put("role", "user")
                                    put("content", textParts.joinToString("") { it.text })
                                })
                            }
                        }
                    }
                }
            } else {
                // Legacy: plain text messages
                val obj = JSONObject()
                obj.put("role", msg.role.value)

                if (echoReasoning && msg.role == LLMMessage.Role.ASSISTANT) {
                    val rc = msg.reasoningContent
                    if (rc != null) {
                        // Round-trip exactly what the server emitted (including "").
                        // See structured-content branch above for the full rationale.
                        obj.put("reasoning_content", rc)
                    } else if (placeholderAllowed) {
                        // No captured reasoning — empty string satisfies the field-
                        // presence check without giving DeepSeek V4 a learnable
                        // marker to imitate (T249 / T257 history).
                        obj.put("reasoning_content", "")
                    }
                }

                val attachTopLevelImages =
                    index == lastUserIndex && msg.role == LLMMessage.Role.USER && imageParts.isNotEmpty()
                if (attachTopLevelImages || msg.audioParts.isNotEmpty()) {
                    val contentArray = JSONArray()
                    if (attachTopLevelImages) {
                        for (part in imageParts) {
                            if (supportsImages) {
                                // T-imgsize + [T-android-image-upload-format]
                                // (上游 1.14): provider 边界的格式、预算与 MIME。
                                val upload = com.openminis.app.provider.ImageBudget.prepareForUpload(part.data, part.mimeType)
                                contentArray.put(
                                    if (upload != null) JSONObject().apply {
                                        put("type", "image_url")
                                        put("image_url", JSONObject().put("url", upload.dataUrl()))
                                    } else JSONObject().apply {
                                        put("type", "text")
                                        put("text", com.openminis.app.provider.ImageBudget.UNSENDABLE_IMAGE_PLACEHOLDER)
                                    },
                                )
                                // [T-android-image-path-metadata] (上游 1.14) 告诉模型
                                // 它看到的图实际存在哪里，文件级追问（按全分辨率重读、
                                // 裁剪、OCR、EXIF、校验和）才成为可能。这句话明确说了
                                // "就是上面那张、不是又一张" —— 没有它，N 张图 + N 条
                                // 说明会被读成 2N 张。
                                com.openminis.app.tools.VisionGroupResolver
                                    .visionImagePathNote(part.linuxPath)
                                    ?.let { note ->
                                        contentArray.put(JSONObject().apply {
                                            put("type", "text")
                                            put("text", note)
                                        })
                                    }
                            } else {
                                // T264: target model has no vision modality — emit
                                // a text placeholder in place of the pixels.
                                // [T-android-vision-group / GH#182] When a Vision
                                // Group is configured, ChatViewModel seeds
                                // part.noVisionPlaceholder with a read_image call
                                // hint (carrying the image path) so the model
                                // routes the image through the group instead of
                                // being told it can't see it. Null → the historical
                                // iOS-parity literal (no Vision Group configured).
                                contentArray.put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", part.noVisionPlaceholder
                                        ?: "[Image attached but this model does not support vision input]")
                                })
                            }
                        }
                    }
                    // [GH#67] Official Chat Completions audio-input shape,
                    // forwarded verbatim (modality is gated at the call site).
                    for (audio in msg.audioParts) {
                        contentArray.put(JSONObject().apply {
                            put("type", "input_audio")
                            put("input_audio", JSONObject().apply {
                                put("data", audio.base64Data)
                                put("format", audio.format)
                            })
                        })
                    }
                    // Preserve the pre-GH#67 image-path behavior (text part
                    // always present); for audio-only messages skip an empty
                    // text block some servers reject.
                    if (attachTopLevelImages || msg.content.isNotEmpty()) {
                        contentArray.put(JSONObject().apply {
                            put("type", "text")
                            put("text", msg.content)
                        })
                    }
                    obj.put("content", contentArray)
                } else {
                    obj.put("content", msg.content)
                }

                messagesArray.put(obj)
            }
        }
        // [T-dedupe-toolcallid follow-up] Cross-message defense-in-depth:
        // rename any tool_call_id that collides with one already seen
        // elsewhere in this request. 9421990 covers the stream-time
        // collision; historical messages reloaded from the DB — or
        // messages produced by a different provider before the user
        // switched — bypass that pass, and DeepSeek (plus several
        // OpenAI-compat gateways) reject the assembled request with
        // "Duplicate value for tool_call_id ... in message[N]" whenever
        // any id repeats across the full messages array.
        globallyDedupeToolCallIds(messagesArray)
        body.put("messages", messagesArray)

        // [T-android-model-use-passthrough-mode GH#72] Merge user-supplied extra
        // body fields verbatim (no OpenAI→native conversion — callers own the
        // shape). User keys win over our defaults, but `model` is force-kept so a
        // stray override can't misroute. Mirrors generateImage's merge + iOS.
        // [T-android-model-custom-params] (上游 1.14) 条目覆盖先合并，按次透传
        // 后合并 —— 后者在同名键上仍然赢。
        mergeModelOverridesBody(body, temperature, thinkingLevel.isEnabled)
        mergeChatExtraBody(body)

        return body
    }

    /**
     * [T-android-model-use-passthrough-mode GH#72] Shared verbatim merge of
     * [chatExtraBody] into a request body, applied by BOTH the chat/completions
     * and responses builders so no endpoint can forget the passthrough. User
     * keys overwrite; `model` is force-restored last. Skipped for Codex OAuth
     * (its body is part of the client fingerprint and must stay untouched).
     */
    private fun mergeChatExtraBody(body: JSONObject) {
        if (host.chatExtraBody.isEmpty()) return
        if (host.isOAuth && !host.forceChatCompletions) return  // Codex OAuth exemption
        for ((k, v) in host.chatExtraBody) body.put(k, v ?: JSONObject.NULL)
        body.put("model", host.model.id)
    }

    /**
     * [T-android-model-custom-params] (上游 1.14) 把用户在该模型条目上设置的
     * 请求参数合并进 body。两个 builder 都调用，与 [mergeChatExtraBody] 对称。
     *
     * 上游把它写在 OpenAIProvider.kt 里；本移植把请求体的构造整体搬到了本文件，
     * 合并逻辑必须跟着来 —— 否则 [host.modelOverrides] 被 ProviderFactory 赋了
     * 值却**没有任何读取点**，用户在模型详情页设的 top_p / 自定义头 / 额外 body
     * 字段会静默失效（temperature 之所以看起来还有效，只是因为它另外经
     * ChatViewModel→SamplingPolicy 这条老路径走了出去）。
     *
     * @param explicitTemperature 调用方传入的 temperature。非 null 表示调用方
     *   指定了具体值（今天只有 ModelUseOffloadHandler 会，来自 minis-model-use
     *   自己的实参），按次指定的值必须压过条目上的默认值 —— 因此仅当其为 null
     *   时才套用覆盖。普通聊天路径一律传 null，正是用户设置存在的场景。
     * @param thinkingEnabled 本请求是否开启思考。覆盖值同样要过
     *   [SamplingPolicy]：把条目温度直接写进 body 会绕过审计修复
     *   （gpt-5 / o 系 / reasoner 系对 temperature 一律 400），所以这里选
     *   "更安全的一方" —— 保留上游的优先级，但采样开关仍由策略层决定。
     *
     * 顺序：本函数在 [mergeChatExtraBody] **之前**运行，因此显式的按次
     * `extra_body` 在同名键上仍然压过条目覆盖。
     *
     * Codex OAuth 与 [mergeChatExtraBody] 一样豁免：该 body 是 ChatGPT 后端校验
     * 的客户端指纹，塞用户键会让整条路径 400。
     */
    private fun mergeModelOverridesBody(
        body: JSONObject,
        explicitTemperature: Double?,
        thinkingEnabled: Boolean,
    ) {
        val o = host.modelOverrides ?: return
        if (host.isOAuth && !host.forceChatCompletions) return  // Codex OAuth exemption

        // null 表示"继承 / 不下发"，绝不等于 0.0 —— 0.0 是合法的全确定性温度，
        // 这正是该字段可空的原因。
        if (explicitTemperature == null) {
            SamplingPolicy.wire(
                SamplingIdentity.of(host.provider),
                host.model.id,
                o.temperature,
                thinkingEnabled,
            )?.let { body.put("temperature", it) }
        }
        // top_p 是按模型显式设置的旋钮，不经默认值路径，因此原样下发（上游语义）。
        o.topP?.let { body.put("top_p", it) }

        // extraBodyParams 是 kotlinx 的 JsonObject 而 body 是 org.json，所以按
        // 序列化形式往返 —— 导出路径（ProviderRepository ~2768）用的同一手法，
        // 它能保留嵌套结构，而不是被 toString() 拍平成字符串。
        o.extraBodyParams?.let { extra ->
            runCatching { JSONObject(extra.toString()) }.getOrNull()?.let { parsed ->
                for (k in parsed.keys()) body.put(k, parsed.get(k))
            }
        }
        // `model` 由我们决定，与 mergeChatExtraBody 末尾的恢复一致。
        body.put("model", host.model.id)
    }

    /**
     * Walk every assistant.tool_calls entry and every role:"tool"
     * tool_call_id in order, renaming any duplicate id to `{id}-{N}`.
     * The first occurrence keeps the raw id; subsequent collisions get
     * a numeric suffix starting at 2. Renames propagate to each pair's
     * matching role:"tool" reply by remembering the latest rename per
     * raw id (the reply is required to immediately follow its claiming
     * assistant tool_calls on this provider).
     */
    private fun globallyDedupeToolCallIds(messagesArray: JSONArray) {
        // raw id → max suffix already issued (0 = unused, 1 = raw kept,
        // 2+ = renamed copies).
        val seen = HashMap<String, Int>()
        // raw id → latest renamed id, so the next role:"tool" reply
        // claiming this raw id can pick up the same rewrite.
        val renameForPendingResults = HashMap<String, String>()
        var renamedCount = 0
        val n = messagesArray.length()
        for (i in 0 until n) {
            val msg = messagesArray.optJSONObject(i) ?: continue
            val role = msg.optString("role")
            when (role) {
                "assistant" -> {
                    val toolCalls = msg.optJSONArray("tool_calls") ?: continue
                    for (j in 0 until toolCalls.length()) {
                        val call = toolCalls.optJSONObject(j) ?: continue
                        val rawId = call.optString("id", "")
                        if (rawId.isEmpty()) continue
                        val used = seen[rawId] ?: 0
                        val renamedId: String
                        if (used == 0) {
                            renamedId = rawId
                            seen[rawId] = 1
                        } else {
                            val next = used + 1
                            renamedId = "$rawId-$next"
                            seen[rawId] = next
                            renamedCount += 1
                            call.put("id", renamedId)
                        }
                        renameForPendingResults[rawId] = renamedId
                    }
                }
                "tool" -> {
                    val rawId = msg.optString("tool_call_id", "")
                    if (rawId.isEmpty()) continue
                    val renamedId = renameForPendingResults[rawId] ?: continue
                    if (renamedId != rawId) {
                        msg.put("tool_call_id", renamedId)
                    }
                }
            }
        }
        if (renamedCount > 0) {
            android.util.Log.w("OpenAIProvider", "[dedupe-tool-call-id] renamed $renamedCount duplicate tool_call_id(s) across messages — likely DB-loaded history or cross-provider switch")
        }
    }

    /**
     * T302: takes the pre-serialized body string instead of the JSONObject so
     * the caller can serialize once and reuse the result for the debug log,
     * the OAuth byte build, and the OkHttp RequestBody. Per-call peak heap
     * dropped by ~2× the body size (often tens of MB on long agent loops).
     */
    internal suspend fun buildRequest(bodyStr: String): Request {
        val token = host.getToken()

        if (host.isOAuth && !host.forceChatCompletions) {
            // Codex OAuth — use ChatGPT backend Responses API
            // Use MediaType without charset — ChatGPT backend rejects "application/json; charset=utf-8"
            val jsonMediaType = "application/json".toMediaType()
            val bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
            val requestBody = object : okhttp3.RequestBody() {
                override fun contentType() = jsonMediaType
                override fun contentLength() = bodyBytes.size.toLong()
                override fun writeTo(sink: okio.BufferedSink) { sink.write(bodyBytes) }
            }
            val builder = Request.Builder()
                .url("https://chatgpt.com/backend-api/codex/responses")
                .post(requestBody)
                .header("Authorization", "Bearer $token")
                .header("Version", host.codexClientVersion)
                .header("Openai-Beta", "responses=experimental")
                .header("User-Agent", "codex_cli_rs/${host.codexClientVersion} (Android; arm64)")
                .header("Originator", "codex_cli_rs")
            host.codexAccountId?.let { builder.header("Chatgpt-Account-Id", it) }
            // [T-provider-custom-user-agent] Applied last so a non-blank
            // override wins over the Codex default UA. In practice null on
            // this OAuth path (the UI only exposes it for custom-base
            // instances), so this is a no-op there.
            // [T-android-default-ua] `defaultUserAgent = null` — keep the
            // codex_cli_rs fingerprint set above when no per-provider
            // override is configured. We must NOT fall back to the branded
            // Minis UA here: the ChatGPT OAuth backend validates the
            // client identity against this header.
            builder.applyUserAgentOverride(host.customUserAgent, defaultUserAgent = null)
            return builder.build()
        }

        val endpointPath = if (host.useResponsesAPI) "/responses" else "/chat/completions"
        // [T-android-azure-openai] Azure routes via the deployments path and
        // auths with the api-key header. azureUrl() returns null when not in
        // Azure mode / no base, so the standard basePath join stays the default.
        // [T-android-model-use-passthrough-mode] endpointURL() honors an
        // absolute-path override ("/...") that replaces the whole path on the
        // provider host; otherwise Azure/basePath as before.
        val requestUrl = when {
            host.absoluteEndpointOverride?.startsWith("/") == true -> host.endpointURL(endpointPath)
            host.isAzure -> host.azureUrl(endpointPath) ?: "${host.basePath}$endpointPath"
            else -> "${host.basePath}$endpointPath"
        }
        // T-responses-include: match iOS bare `application/json` Content-Type
        // by using a custom RequestBody (same workaround the OAuth branch
        // above already does). OkHttp's `String.toRequestBody(MediaType)`
        // helper attaches the MediaType to the body and several proxies/
        // backends end up seeing `application/json; charset=utf-8`. iOS sends
        // bare `application/json`; some third-party Responses-API proxies are
        // stricter and reject the charset suffix.
        val jsonMediaType = "application/json".toMediaType()
        val bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
        val requestBody = object : okhttp3.RequestBody() {
            override fun contentType() = jsonMediaType
            override fun contentLength() = bodyBytes.size.toLong()
            override fun writeTo(sink: okio.BufferedSink) { sink.write(bodyBytes) }
        }
        val builder = Request.Builder()
            .url(requestUrl)
            .post(requestBody)
            .applyKeyAuth(token)
            .header("Content-Type", "application/json")
        for ((key, value) in host.extraHeaders) {
            builder.header(key, value)
        }
        // [T-android-copilot-per-request-headers] (上游 1.14) 依赖**本次请求体**
        // 的头（见 Host.perRequestHeaders）。这里拿到的是已序列化的字符串，
        // 所以先解析；畸形 body 只可能来自上面的构造器，但"计算尺寸的辅助函数
        // 绝不能是抛异常的那一个"，因此解析失败就退化为"不追加派生头"而不是
        // 让整次发送失败。
        //
        // 本移植的端口早已把 CopilotDeviceFlow.perRequestHeaders 接到
        // OpenAIProvider.perRequestHeaders 上，缺的就是这一处应用点 —— 少了它，
        // X-Initiator 永远缺失、每个 agent 轮次都被标成人工流量。
        host.perRequestHeaders?.let { derive ->
            val parsed = runCatching { JSONObject(bodyStr) }.getOrNull()
            if (parsed != null) {
                for ((key, value) in derive(parsed)) builder.header(key, value)
            }
        }
        // [T-android-opencode-session-header] (上游 1.14) 每次请求都用当前
        // [Host.sessionId] 现算，草稿会话在对话中途拿到正式 id 后无需重建
        // provider 就会开始带头。按 requestUrl 而不是 basePath 判定，因为那才是
        // 实际拨号的地址 —— 绝对端点覆盖或 Azure 路由可能把请求发到配置的 base
        // 之外，id 必须跟着真实目的地走。非 OpenCode Go 主机、草稿/空 id 都为空。
        for ((key, value) in com.openminis.app.provider.OpenCodeSessionHeader
            .headersFor(requestUrl, host.sessionId)) {
            builder.header(key, value)
        }
        // [T-android-model-custom-params] (上游 1.14) 条目自定义头。位置在构造器
        // extraHeaders 之后（用户头可覆盖 provider 默认），但在 chatExtraHeaders
        // 之前 —— 显式的按次透传仍然赢。Authorization/Content-Type 只在用户
        // 点名时才会被碰，这正是 API key 与 OAuth 认证不受影响的原因。
        host.modelOverrides?.customHeaders?.let { headers ->
            for ((key, value) in headers) builder.header(key, value)
        }
        // [T-android-model-use-passthrough-mode] Per-call chat header overrides,
        // applied AFTER the ctor extraHeaders → same-name REPLACE over any
        // default (incl. Authorization/Content-Type). Empty on normal calls.
        for ((key, value) in host.chatExtraHeaders) {
            builder.header(key, value)
        }
        // [T-provider-custom-user-agent] Covers both chat/completions and
        // /responses (this builder serves both). Applied after extraHeaders
        // so the per-provider override wins. null/blank → default UA.
        //
        // [T-android-copilot-editor-ua] (上游 1.14) …除非 extraHeaders 里已经带了
        // User-Agent —— 那说明该 provider 的身份属于它的**协议**而不是品牌。
        // Copilot 就是这种情况：它拒绝不像编辑器插件的请求，而带品牌的
        // `Minis/…` 默认值会在每个 chat 请求上盖掉 `GitHubCopilotChat/…`
        // （抓包可见）。这与 Codex 路径数百行前用 `defaultUserAgent = null`
        // 表达的是同一件事，这里改成推导出来，任何 provider 都不必再记。
        // 显式的按实例覆盖依然优先；对 Copilot 而言也不可能发生
        // （supportsCustomUserAgent 为 false）。
        //
        // 这是本移植在拆分中丢掉的那一环：上游是"先写 Copilot 的 UA，随后
        // applyUserAgentOverride 覆盖"，我们的 OpenAIRequestBodies 只有后者。
        val providerOwnsUserAgent = host.extraHeaders.keys.any { it.equals("User-Agent", ignoreCase = true) }
        builder.applyUserAgentOverride(
            host.customUserAgent,
            defaultUserAgent = if (providerOwnsUserAgent) {
                null
            } else {
                com.openminis.app.provider.MinisUserAgent.DEFAULT
            },
        )
        return builder.build()
    }

    /**
     * [T-android-xhigh-effort-clamp] Clamp the reasoning-effort string for
     * model families whose backend only accepts low/medium/high and 400/422 on
     * our `xhigh` tier: MiMo-2.5/Pro and Agnes. For these, `xhigh` → `high`;
     * every other value passes through untouched, and every other model is
     * unaffected. Applied at the single point where each branch would emit an
     * effort string (Chat Completions reasoning_effort / reasoning.effort AND
     * the Responses API reasoning.effort) so no branch can leak a raw xhigh.
     * lowercase-contains match, mirroring the T-reasoning-effort-fallback keys.
     */
    private fun clampEffortForModel(effort: String): String {
        val lid = host.model.id.lowercase()
        // [T-fallback-thinking-preclamp] Match the FAMILY substring, not one
        // spelling: catalog docs say "MiMo-2.5" but the live API returns
        // "mimo-v2.5" / "mimo-v2.5-pro", which the old "mimo-2.5" match missed
        // (mirrors iOS 72968c4f).
        return if (effort == "xhigh" && (lid.contains("mimo") || lid.contains("agnes"))) "high" else effort
    }

    /**
     * [T-zen-free-thinking-budget] The generation ceiling one thinking level
     * carries on the Zen free lane. The gateway ignores every effort field it
     * is given and enforces ONLY max_completion_tokens, so a level here is a
     * real, enforced budget rather than a hint — a menu that did nothing
     * would be worse than no menu at all (design principle carried over from
     * the desktop reference implementation).
     *
     * The ceiling is shared by thinking and the visible answer: a lower level
     * shortens both. A model whose thinking cannot be switched off pays for
     * its reasoning out of the same ceiling before the answer starts, so its
     * rungs are scaled UP — measured on mimo-v2.6-flash-free over one day, 82%
     * of output tokens were reasoning, and the unscaled ceiling ended long
     * turns in `length` about every third request.
     *
     * Returns null when this model exposes no effort menu (supportsReasoning
     * is not true), so the caller's own maxTokens stays on the wire untouched.
     *
     * [T-thinking-ladder-shared] The ladder itself is NOT here any more — it
     * lives in [ThinkingLadder], shared with the Gemini / Qwen / Anthropic
     * numeric-budget sites. The old table lived here as seven ABSOLUTE
     * constants, which is precisely what cannot work on a lane whose models
     * have wildly different ceilings: on big-pickle / mimo-v2.5 / mimo-v2.6
     * (capacity 32000) and ling-3.1 (32768) — four REAL models in the device's
     * own Zen free catalogue — the top rungs all clipped to the same number,
     * so dragging the slider changed nothing on the wire.
     *
     * WHY PROPORTIONAL. Each rung is now a FRACTION of the model's OWN output
     * capacity (1/32, 2/32, 4/32, 8/32, 14/32, 22/32, 30/32) instead of a
     * constant. That is what makes the ladder strictly increasing for EVERY
     * capacity: a constant that sits below one model's ceiling is above
     * another's, but a fraction of that same ceiling is always below it.
     *
     * WHY THE FRACTIONS ARE UNEVEN. A linear ramp (1/7..6/7) would spend the
     * user's tokens almost evenly. Reasoning cost grows SUPERLINEARLY with
     * depth, so the ladder doubles early and tapers late — the same shape as
     * the measured must-think behaviour (82% of output tokens were reasoning).
     * ULTRA stops at 30/32 so it approaches the ceiling without ever being
     * pinned to it. OFF is the smallest rung and deliberately not a duplicate
     * of LOW: it exists to leave room for the ANSWER only, while LOW must buy
     * real reasoning room above it.
     *
     * MUST-THINK IS A RESCALE, NOT A MULTIPLICATION. `multiplier = 2` divides
     * the FRACTIONS, shifting the whole ladder down by one step's worth of
     * headroom instead of doubling the resulting numbers. Doubling the numbers
     * (`budget * 2`) would push XHIGH/MAX/ULTRA straight past the ceiling,
     * where `minOf(..., capacity)` clamps them all back to the same value —
     * reintroducing byte-for-byte the collapse this change removes, on exactly
     * the mimo models that motivated it.
     *
     * [UNMEASURED] The fractions are reasoned, not swept. The claim that THESE
     * steps raise reasoning quality on this lane is NOT measured — this
     * gateway may just truncate differently at a bigger ceiling. Strictly
     * increasing and strictly below capacity is the part that is provably
     * right; the exact steps need an on-device sweep (reasoning tokens and
     * `length` finish rate per level) before being called tuned.
     */
    private fun zenBudgetTokens(level: ThinkingLevel, maxTokens: Int): Int? {
        if (host.model.supportsReasoning != true) return null
        // [T-thinking-ladder-shared] capacity MUST be the model's own max
        // output tokens — scaling a ladder against the caller's arbitrary
        // maxTokens would make the same level produce a different budget
        // depending on who asked for it, and would destroy proportionality.
        val capacity = host.model.maxOutputTokens ?: maxTokens
        val lid = host.model.id.lowercase()
        val mustThink = lid.startsWith("mimo-v2.6") || lid.startsWith("mimo-v2.5")
        return ThinkingLadder.budgetFor(level, capacity, if (mustThink) 2 else 1)
    }

    /**
     * Inject the provider-specific thinking parameters for one request.
     *
     * [T-thinking-rules-phase1] The body of this function used to be an if-return chain
     * keyed on model-id substrings. That logic now lives in [ThinkingRuleResolver] as a
     * data-driven rule registry (design §4/§5); this remains as the call-site-compatible
     * entry point so every caller — and the golden snapshot that pins this exact
     * behaviour — is unchanged.
     *
     * Behaviour is byte-for-byte identical to the pre-refactor chain, enforced by
     * ThinkingWireGoldenSnapshotTest (119 rows generated against the old code and
     * committed before the refactor, fdc28e2b).
     *
     * PHASE 1 SCOPE: OpenAI-compatible endpoints only. Gemini and Anthropic keep their
     * own emitters and are not routed through the resolver yet.
     */
    private fun injectThinkingParams(body: JSONObject, level: ThinkingLevel, maxTokens: Int) {
        // [T-android-thinking-level-arch] `level` is already clamped to the model ceiling
        // by LLMProvider.streamMessage/sendMessage — do NOT re-clamp here.
        //
        // [T-A5-native-ignores-declaration] 这条注释此前与代码不符：上下文里又调了
        // 一次 `host.clampThinkingLevel(level)`，等于把天花板放到了第二处（而
        // ThinkingWireGoldenSnapshotTest 的文件头明确写着「不要靠在这里 clamp 来
        // 修快照 —— 那会让天花板有两个来源、两边漂移」）。后果之一就是上游
        // OpenAIFamilyGatesTest 钉住的行为被改掉：OpenAI 原生分支（o 系 / gpt-5 /
        // gpt-6）**按请求原样下发** reasoning_effort，声明集只对通用分支生效；
        // 多这一道 clamp 后，一个只声明 ["low","medium"] 的 gpt-5 模型会把用户要的
        // "high" 悄悄降成 "medium"。上游 1.14 OpenAIProvider.kt:2964-2981 里
        // `level = level`，天花板只由公共入口施加。
        val ctx = ThinkingResolveContext(
            modelId = host.model.id,
            instanceId = host.thinkingRuleInstanceId,
            supportsReasoning = host.model.supportsReasoning,
            declaredEffortValues = host.model.reasoningEffortValues,
            budgetTokensMin = host.model.budgetTokensMin,
            budgetTokensMax = host.model.budgetTokensMax,
            // [OpenMinis#163] null (catalog silent) must read as false here —
            // only an affirmative declaration may suppress the field.
            declaresNoEffortTiers = host.model.declaresNoEffortTiers == true,
            level = level,
            maxTokens = maxTokens,
            isOpenRouter = host.isOpenRouter,
            usesUnifiedReasoningEffort = host.usesUnifiedReasoningEffort,
            isMistral = host.isMistral,
            isDashScope = host.isDashScope,
            isXAI = host.isXAI,
            // [T-android-cerebras-reasoning-400] (上游 1.14) 少了这一项，`*qwen*`
            // 规则就会按模型名命中 Cerebras 托管的 qwen-3.8-27b 并塞进 Qwen 原生的
            // enable_thinking；Cerebras 不接受该字段，其文档给出的控制项是根级
            // reasoning_effort。
            isCerebras = host.isCerebras,
            offEffort = host.explicitOffEffort(),
        )
        val trace = ThinkingRuleResolver.apply(body, ctx)
        // [T-thinking-rules-observability] Design §8 / GH OpenMinis#100: which rule
        // actually won must be inspectable, or a rule layer just replaces one hidden
        // variable with a more complicated one. minis-config exposure is Phase 2.
        com.openminis.app.logging.AppLogger.info(
            "Thinking",
            "[resolve] model=${host.model.id} level=${level.name} ${trace.logLine}",
        )
    }

    // MARK: - Responses API (Codex OAuth)

    /**
     * Build request body for the Responses API format (used by Codex OAuth).
     * Uses `input` instead of `messages`, `instructions` instead of system prompt.
     */
    /**
     * [T-android-responses-toplevel-images] Encode one image as a Responses-API
     * content block, or as the no-vision text placeholder when the target model
     * cannot see pixels.
     *
     * Extracted so the two Responses call sites (structured `contentParts`
     * messages and legacy top-level `imageParts` messages) cannot drift apart —
     * the drift is exactly what produced the silent drop this fixes. Note the
     * Responses shape differs from Chat Completions: `image_url` is a bare
     * STRING here, not a `{"url": …}` object.
     */
    private fun responsesImageBlock(
        data: ByteArray,
        mimeType: String,
        supportsImages: Boolean,
        noVisionPlaceholder: String?,
    ): JSONObject = if (supportsImages) {
        // T-imgsize + [T-android-image-upload-format] (上游 1.14): provider 边界的
        // 格式、预算与 MIME。
        val upload = com.openminis.app.provider.ImageBudget.prepareForUpload(data, mimeType)
        if (upload != null) JSONObject().apply {
            put("type", "input_image")
            put("image_url", upload.dataUrl())
        } else JSONObject().apply {
            put("type", "input_text")
            put("text", com.openminis.app.provider.ImageBudget.UNSENDABLE_IMAGE_PLACEHOLDER)
        }
    } else {
        JSONObject().apply {
            put("type", "input_text")
            put(
                "text",
                noVisionPlaceholder
                    ?: "[Image attached but this model does not support vision input]",
            )
        }
    }

    // `internal` for the same reason as [buildRequestBody]: the tool-result-image
    // regression is asserted against the constructed JSON directly.
    internal fun buildResponsesAPIBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        stream: Boolean,
        /**
         * [T-android-responses-toplevel-images] Images passed as the top-level
         * argument rather than on `msg.contentParts`, attached to the LAST user
         * message — the same contract [buildRequestBody] implements.
         *
         * This parameter did not exist, and that was a silent data loss: every
         * caller that supplies images this way (minis-model-use's `image_url`
         * blocks, VisionGroupResolver.describeOnce, any direct
         * sendMessage(imageParts=…)) had its pixels dropped on the floor the
         * moment the provider was on the Responses path, with no error. The
         * user-visible symptom was a vision model replying "no image was
         * provided" — reported against a Vision Group whose describing model
         * ran on Responses.
         */
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
        temperature: Double? = null,
    ): JSONObject {
        // T264: same vision-capability gate as buildRequestBody. Responses API
        // path (Codex OAuth) is currently always wired to a vision-capable
        // GPT-5.x so this branch is defensive rather than load-bearing, but
        // keeping the two paths symmetric prevents future regressions when
        // a non-vision model gets routed through Responses (e.g. via
        // forceResponsesAPI on a custom provider).
        // [T-android-vision-native-check-misses-image_input] hasImageInput, not a
        // raw membership test: this compared the catalog string EXACTLY, so
        // "image_input" (OpenAI/OpenRouter) and "Image" both read as "no vision"
        // and the pixels below were swapped for a text placeholder.
        val supportsImages = host.model.hasImageInput
        val body = JSONObject()
        body.put("model", host.model.id)
        body.put("stream", stream)
        SamplingPolicy.wire(
            SamplingIdentity.of(host.provider), host.model.id, temperature, thinkingLevel.isEnabled,
        )?.let { body.put("temperature", it) }
        // [T-android-xai-priority] xAI documents service_tier for both text
        // inference endpoints. xAI currently always resolves to the Chat
        // Completions path (forceChatCompletions), so this is belt-and-braces
        // — but keeping the two builders in step means a future routing change
        // does not silently drop the user's Fast Mode choice.
        host.resolvedServiceTier()?.let { body.put("service_tier", it) }
        body.put("store", false)
        body.put("parallel_tool_calls", true)
        // Stable per-conversation cache key so the Responses API can hit prompt
        // cache across turns. Codex CLI sets this to its conversation_id; at
        // this layer we don't have one, so we hash the first user message —
        // re-sent verbatim every turn of the same chat → stable across turns,
        // distinct between chats. iOS does the same in
        // OpenAIAgentProvider.swift:325 + derivePromptCacheKey() at line 515.
        // Without this, each turn was treated as a separate prompt by the
        // Responses-API cache regardless of how byte-stable the prefix was —
        // that's the missing piece between Android (~70%) and iOS (90%+) on
        // the Codex OAuth / forceResponsesAPI path.
        body.put("prompt_cache_key", derivePromptCacheKey(messages))
        // T-responses-include: `include: ["reasoning.encrypted_content"]` is a
        // ChatGPT-backend-only field. Third-party Responses-API-compatible
        // proxies (non-OpenAI) don't recognize it and reject the request with
        // 400. Mirrors iOS OpenAIAgentProvider.swift:404 which gates this
        // strictly behind isCodexOAuth. OpenAI's first-party Responses API
        // also accepts the field, so we keep it on for OAuth (Codex) only —
        // the encrypted reasoning content is what lets the ChatGPT backend
        // re-attach prior reasoning across turns without store=true.
        if (host.isOAuth) {
            body.put("include", JSONArray().put("reasoning.encrypted_content"))
        }
        // Thinking level → Responses API `reasoning.effort`. Mirrors iOS
        // OpenAIAgentProvider.swift:327-338. Pre-T119 this was hardcoded to
        // "low" regardless of the user's setting, so toggling Thinking
        // High/Medium/Off had no effect on GPT-5.x via the Responses path.
        // - When the user has thinking enabled → map their level to the
        //   matching effort string.
        // - When off but using Codex OAuth → fall back to "low" because the
        //   ChatGPT backend rejects requests without a `reasoning` object.
        // - Else → omit the field so the upstream applies its own default.
        // [T-android-codex-thinking-summary] `summary: "auto"` opts in to
        // streaming the human-readable reasoning SUMMARY (delivered as
        // `response.reasoning_summary_text.delta` SSE events with non-empty
        // `delta`). Without it the Responses API / Codex backend returns ONLY
        // `encrypted_content` — the reasoning deltas arrive empty, so the
        // Thinking region never renders even though the model reasoned (token
        // usage shows it did). This was the Codex-OAuth "thinking on but UI
        // shows nothing" bug (XIN). Mirrors iOS OpenAIAgentProvider.swift:415
        // (`["effort": effort, "summary": "auto"]`). OpenAI ignores the variant
        // it doesn't support and falls back to an auto-equivalent, so it's safe
        // on every Responses-flavor endpoint.
        // [T-android-xhigh-effort-clamp] Also clamp on the Responses API path:
        // the reasoning.effort field is the same name/values as Chat Completions
        // and would send xhigh too. MiMo-2.5/Agnes normally use Chat Completions
        // (the reported 400/422), but a user could flip useResponsesAPI on, so
        // guard it here as well — only xhigh for those two families is affected.
        // [T-android-thinking-level-arch] `thinkingLevel` is already clamped by
        // LLMProvider.streamMessage/sendMessage before reaching here.
        val effort = if (thinkingLevel.isEnabled) {
            mapThinkingLevelToResponsesEffort(thinkingLevel)?.let { clampEffortForModel(it) }
        } else null
        when {
            // [T-android-mistral-reasoning-422] Mistral rejects the reasoning
            // request parameter outright (`422 extra_forbidden body.reasoning`,
            // GH OpenMinis#87). The gate added alongside injectThinkingParams
            // covers only the Chat Completions path; this builder is a SECOND,
            // independent injection site that a Mistral instance with
            // useResponsesAPI enabled reaches ungated. For Mistral the answer to
            // "should any thinking field be sent" is NEVER, on every request
            // path — so suppress the whole block. Must stay FIRST so it wins over
            // the isOAuth fallback below.
            host.isMistral -> {}
            effort != null -> body.put(
                "reasoning",
                JSONObject().put("effort", effort).put("summary", "auto"),
            )
            host.isOAuth -> body.put(
                "reasoning",
                JSONObject().put("effort", "low").put("summary", "auto"),
            )
            // [T-thinking-off-explicit] Thinking OFF on a reasoning-capable
            // model: send the explicit off tier instead of omitting `reasoning`
            // — omission lets the vendor default kick in. Same ALLOWLIST as the
            // Chat path (official OpenAI → "none", Volcano Ark → "minimal");
            // vendors with undocumented off semantics keep the historical
            // omission. No summary/include: nothing should stream back.
            // Mirrors iOS OpenAIAgentProvider ff60c818's Responses off branch.
            !thinkingLevel.isEnabled && host.model.supportsReasoning == true &&
                !host.model.id.lowercase().let { it.contains("mimo") || it.contains("agnes") } -> {
                host.explicitOffEffort()?.let { offEffort ->
                    // [OpenMinis#377] (上游 1.14) 把 off 档夹到该模型真正接受的
                    // 取值上。explicitOffEffort() 回答的是"这是哪个厂商"（官方
                    // OpenAI → "none"），从不回答"这个模型接受什么" —— 于是声明
                    // [low…max] 且拒绝 "none" 的 gpt-6-astra 每次压缩都吃
                    // HTTP 400 invalid_request_error，四次折半重试全废。上面的
                    // enabled 分支一直在夹，只有这一支漏了。
                    val safe = ThinkingRuleResolver.clampOffEffort(
                        offEffort,
                        host.model.reasoningEffortValues,
                    )
                    body.put("reasoning", JSONObject().put("effort", safe))
                }
            }
        }

        // [T-responses-max-output-tokens] The builder received maxTokens but
        // never wrote it into the body, so Responses-flavor vendors fell back
        // to their (often tiny) defaults and truncated. Same guard as iOS
        // 637cd890/5f148144: maxTokens > 0, and Codex OAuth excluded — the
        // codex_cli_rs body shape is a client fingerprint and must not carry
        // fields the real CLI doesn't send.
        if (maxTokens > 0 && !host.isOAuth) {
            body.put("max_output_tokens", maxTokens)
        }

        // [T-codex-fast-mode] Fast tier injection (mirrors iOS fb671083 +
        // 838ba929). Wire value verified against openai/codex source
        // (codex-rs/protocol config_types.rs): ServiceTier::Fast sends
        // service_tier="priority" — "fast" is only the UI name, so this stays
        // inside the codex_cli_rs client fingerprint on the OAuth route. Gate
        // is toggle + gpt-family model only: this builder IS the Responses
        // path, and Responses relays (e.g. sub2api) normalize/pass the tier
        // through, so no isOAuth narrowing. Ineligible upstreams ignore the
        // field or silently downgrade (receipt visible via the
        // response.completed service_tier log).
        //
        // [T-android-xai-priority] Guarded on the key being absent so this
        // cannot clobber a per-instance tier set above. The two gates are
        // disjoint today (that toggle is xAI-only, this one gpt-only, and xAI
        // never reaches this builder), so the guard changes no current
        // behaviour — it just means neither feature can silently overwrite the
        // other if either's routing widens later. Both want the same value
        // anyway, so "first writer wins" loses nothing.
        if (!body.has("service_tier") &&
            com.openminis.app.data.FastModePrefs.isEnabled() &&
            host.model.id.contains("gpt", ignoreCase = true)
        ) {
            body.put("service_tier", "priority")
        }

        if (systemPrompt != null) {
            body.put("instructions", systemPrompt)
        }

        // Tools — flat shape required by Responses API ({type, name, description,
        // parameters}), distinct from Chat Completions' wrapped {type, function:{...}}.
        // The Zen Responses lane requires tool_choice=auto.
        val effectiveResponseTools = if (com.openminis.app.provider.ZenDisguise.isZenHost(host.basePath)) {
            val names = tools.map { it.name }.toSet()
            tools + listOf(
                AgentToolDefinition("bash", "Reserved for the host runtime; do not call it.", emptyMap()),
                AgentToolDefinition("read", "Reserved for the host runtime; do not call it.", emptyMap()),
            ).filter { it.name !in names }
        } else tools
        if (effectiveResponseTools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (tool in effectiveResponseTools) {
                toolsArray.put(tool.toResponsesAPIJson())
            }
            body.put("tools", toolsArray)
            body.put("tool_choice", "auto")
        }

        // Mirrors iOS convertMessagesResponsesAPI (OpenAIAgentProvider.swift:895):
        // structured content parts become typed input items — function_call /
        // function_call_output — instead of free-text role/content pairs.
        val input = JSONArray()
        // [T-android-responses-toplevel-images] Same contract as
        // buildRequestBody: top-level images ride on the LAST user message.
        val lastUserIdx = messages.indexOfLast { it.role == LLMMessage.Role.USER }
        for ((msgIndex, msg) in messages.withIndex()) {
            val attachTopLevelImages =
                msgIndex == lastUserIdx && msg.role == LLMMessage.Role.USER && imageParts.isNotEmpty()
            if (msg.contentParts.isNotEmpty()) {
                when (msg.role) {
                    LLMMessage.Role.ASSISTANT -> {
                        val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                        if (textParts.isNotEmpty()) {
                            val text = textParts.joinToString("") { it.text }
                            if (text.isNotEmpty()) {
                                input.put(JSONObject().apply {
                                    put("role", "assistant")
                                    put("content", text)
                                })
                            }
                        }
                        for (tu in msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()) {
                            val (callId, fcId) = splitResponsesAPIIds(tu.id)
                            val safeCallId = capResponsesId(callId)
                            // Responses API requires both `id` (fc_…) and `call_id` (call_…).
                            // When the message was synthesized outside a Responses round-trip
                            // (e.g. injected from Chat Completions history) the fcId is null —
                            // generate a deterministic synthetic so the API still accepts it.
                            val safeFcId = fcId?.let { capResponsesId(it) }
                                ?: "fc_syn_${safeCallId.takeLast(24)}"
                            input.put(JSONObject().apply {
                                put("type", "function_call")
                                put("id", safeFcId)
                                put("call_id", safeCallId)
                                put("name", tu.name)
                                put("arguments", tu.input.toString())
                            })
                        }
                    }
                    LLMMessage.Role.USER -> {
                        // [T-android-responses-toolresult-image-split] (上游 1.14)
                        // 先放完全部 function_call_output，图片载体统一放到整段
                        // 之后 —— 这是 T-android-parallel-toolresult-image-split
                        // (030d059cb) 的 Responses 孪生兄弟，那一次只修了 Chat
                        // Completions 分支。
                        //
                        // 把某条结果的载体紧跟它的输出，会在并行输出之间插进一个
                        // `user` item：
                        //   function_call        call_00 (read_image)
                        //   function_call        call_01 (shell_execute)
                        //   function_call_output call_00
                        //   user  [Image returned by read_image]   <-- 拆断整段
                        //   function_call_output call_01
                        // 于是 DeepSeek 的 /v1/responses 以 `[400] No tool output
                        // found for tool call call_01_…` 拒绝请求（现场报告：
                        // deepseek-flash，read_image 与 shell 命令并行）。单个调用
                        // 永远不会拆断任何东西，这就是单次 read_image 一直能用的原因。
                        val imageCarriers = mutableListOf<JSONObject>()
                        for (tr in msg.contentParts.filterIsInstance<AgentContentPart.ToolResult>()) {
                            val (callId, _) = splitResponsesAPIIds(tr.id)
                            input.put(JSONObject().apply {
                                put("type", "function_call_output")
                                put("call_id", capResponsesId(callId))
                                put("output", tr.content)
                            })
                            // [T-android-toolresult-image-dropped] Same defect as the
                            // Chat Completions branch: function_call_output takes a
                            // string `output`, so read_image's pixels had nowhere to
                            // go and were silently dropped. Emit them as a following
                            // user turn carrying an input_image block.
                            val trBytes = tr.imageData
                            if (trBytes != null && trBytes.isNotEmpty() && supportsImages) {
                                imageCarriers.add(JSONObject().apply {
                                    put("role", "user")
                                    put("content", JSONArray().apply {
                                        put(JSONObject().apply {
                                            put("type", "input_text")
                                            put("text", "[Image returned by ${tr.name}]")
                                        })
                                        put(
                                            responsesImageBlock(
                                                trBytes,
                                                tr.imageMimeType ?: "image/jpeg",
                                                supportsImages,
                                                null,
                                            ),
                                        )
                                    })
                                })
                            }
                        }
                        // 载体放这里 —— 在每一条 function_call_output 之后，回答并行
                        // 调用的输出因此保持连续。
                        for (carrier in imageCarriers) input.put(carrier)
                        // T132: emit text + input_image content for the user
                        // turn so vision-capable Responses-API models actually
                        // see the bytes. Without the input_image branch the
                        // outer `content` was a flat concatenated string and
                        // image bytes never reached the wire (the textual
                        // [attached image: …] caption was the only hint, and
                        // the model fell back to read_image / shell_execute
                        // groping for a path it could see). Mirrors iOS
                        // convertMessagesResponsesAPI's image handling.
                        val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                        val imgParts = msg.contentParts.filterIsInstance<AgentContentPart.ImageData>()
                        if (imgParts.isNotEmpty()) {
                            val contentArray = JSONArray()
                            for (part in msg.contentParts) {
                                when (part) {
                                    is AgentContentPart.Text -> {
                                        if (part.text.isNotEmpty()) {
                                            contentArray.put(JSONObject().apply {
                                                put("type", "input_text")
                                                put("text", part.text)
                                            })
                                        }
                                    }
                                    is AgentContentPart.ImageData -> {
                                        if (supportsImages) {
                                            // T-imgsize + [T-android-image-upload-format]
                                            // (上游 1.14): Responses API 路径的
                                            // 格式、预算与 MIME。
                                            contentArray.put(
                                                responsesImageBlock(
                                                    part.data,
                                                    part.mimeType,
                                                    supportsImages = true,
                                                    noVisionPlaceholder = null,
                                                ),
                                            )
                                        } else {
                                            // T264: target model has no vision modality —
                                            // emit a text placeholder. [T-android-vision-group
                                            // / GH#182] Vision-Group read_image hint when
                                            // seeded (carries the path); else the historical
                                            // literal. Note: Responses API uses "input_text"
                                            // type (vs "text" on Chat Completions).
                                            contentArray.put(JSONObject().apply {
                                                put("type", "input_text")
                                                put("text", part.noVisionPlaceholder
                                                    ?: "[Image attached but this model does not support vision input]")
                                            })
                                        }
                                    }
                                    // [T-android-responses-toplevel-images]
                                    // ToolUse/ToolResult are handled above for
                                    // this role and legitimately don't belong
                                    // in the content array. Anything else is a
                                    // part type this converter has never been
                                    // taught to encode — the failure mode being
                                    // fixed here (content dropped with no error
                                    // and no log) is exactly what that produces,
                                    // so make it visible rather than silent.
                                    is AgentContentPart.ToolUse,
                                    is AgentContentPart.ToolResult -> Unit
                                    else -> com.openminis.app.logging.AppLogger.error(
                                        "OpenAIProvider",
                                        "[responses] DROPPED unconvertible content part " +
                                            "${part.javaClass.simpleName} on role=user — it will NOT " +
                                            "reach the model. Add an encoding branch for it.",
                                    )
                                }
                            }
                            // [T-android-responses-toplevel-images] Top-level
                            // images belong on this same turn.
                            if (attachTopLevelImages) {
                                for (p in imageParts) {
                                    contentArray.put(
                                        responsesImageBlock(p.data, p.mimeType, supportsImages, p.noVisionPlaceholder),
                                    )
                                }
                            }
                            input.put(JSONObject().apply {
                                put("role", "user")
                                put("content", contentArray)
                            })
                        } else if (attachTopLevelImages) {
                            // [T-android-responses-toplevel-images] Structured
                            // message with no ImageData parts, but images were
                            // supplied top-level (minis-model-use / Vision
                            // Group). Previously this fell into the text-only
                            // branch below and the pixels vanished.
                            val contentArray = JSONArray()
                            val text = textParts.joinToString("") { it.text }
                            if (text.isNotEmpty()) {
                                contentArray.put(JSONObject().apply {
                                    put("type", "input_text")
                                    put("text", text)
                                })
                            }
                            for (p in imageParts) {
                                contentArray.put(
                                    responsesImageBlock(p.data, p.mimeType, supportsImages, p.noVisionPlaceholder),
                                )
                            }
                            input.put(JSONObject().apply {
                                put("role", "user")
                                put("content", contentArray)
                            })
                        } else if (textParts.isNotEmpty()) {
                            input.put(JSONObject().apply {
                                put("role", "user")
                                put("content", textParts.joinToString("") { it.text })
                            })
                        }
                    }
                    else -> {
                        input.put(JSONObject().apply {
                            put("role", msg.role.value)
                            put("content", msg.content)
                        })
                    }
                }
            } else if (msg.audioParts.isNotEmpty()) {
                // [GH#67] Legacy (non-contentParts) message carrying audio —
                // the minis-model-use path. The Responses API keeps the SAME
                // nested input_audio shape as Chat Completions ({data,
                // format}), unlike input_image which flattens image_url to a
                // string. Text rides along as input_text.
                val contentArray = JSONArray()
                for (audio in msg.audioParts) {
                    contentArray.put(JSONObject().apply {
                        put("type", "input_audio")
                        put("input_audio", JSONObject().apply {
                            put("data", audio.base64Data)
                            put("format", audio.format)
                        })
                    })
                }
                if (msg.content.isNotEmpty()) {
                    contentArray.put(JSONObject().apply {
                        put("type", "input_text")
                        put("text", msg.content)
                    })
                }
                // [T-android-responses-toplevel-images] An audio-carrying turn
                // can also carry images.
                if (attachTopLevelImages) {
                    for (p in imageParts) {
                        contentArray.put(
                            responsesImageBlock(p.data, p.mimeType, supportsImages, p.noVisionPlaceholder),
                        )
                    }
                }
                input.put(JSONObject().apply {
                    put("role", msg.role.value)
                    put("content", contentArray)
                })
            } else if (attachTopLevelImages) {
                // [T-android-responses-toplevel-images] THE reported bug's path.
                // A plain (contentParts-free) user message plus top-level
                // images — what VisionGroupResolver.describeOnce and
                // minis-model-use's image_url blocks produce. This builder had
                // no imageParts parameter at all, so the message was emitted as
                // a bare text string and the pixels never reached the wire. The
                // vision model then answered "no image was provided", with no
                // error anywhere to explain it.
                val contentArray = JSONArray()
                if (msg.content.isNotEmpty()) {
                    contentArray.put(JSONObject().apply {
                        put("type", "input_text")
                        put("text", msg.content)
                    })
                }
                for (p in imageParts) {
                    contentArray.put(
                        responsesImageBlock(p.data, p.mimeType, supportsImages, p.noVisionPlaceholder),
                    )
                }
                input.put(JSONObject().apply {
                    put("role", msg.role.value)
                    put("content", contentArray)
                })
            } else {
                input.put(JSONObject().apply {
                    put("role", msg.role.value)
                    put("content", msg.content)
                })
            }
        }
        // [T-android-responses-orphan-tool-output] Last gate on the tool
        // pairing, over the ids this request actually carries (split on "|"
        // and capped). Without it a stranded or duplicated tool result 400s
        // every send until the chat is cleared. See ResponsesToolPairing.
        //
        // 本移植把上游 1.14 的调用点一并补回：ResponsesToolPairing.sanitize
        // 早已随合并进仓，但拆分时**没有任何调用点** —— 空转的守卫等于没有守卫。
        val paired = ResponsesToolPairing.sanitize(input)
        if (paired.changed) {
            android.util.Log.w(
                "OpenAIProvider",
                "[sanitize-responses] repaired outgoing tool pairing: " +
                    "droppedOrphanOutputs=${paired.droppedOrphanOutputs.size} " +
                    "droppedDuplicateOutputs=${paired.droppedDuplicateOutputs.size} " +
                    "placeholderCalls=${paired.placeholderCalls.size} items=${input.length()}",
            )
        }
        body.put("input", paired.items)

        // [T-android-model-custom-params] (上游 1.14) Responses 路径同样合并条目
        // 覆盖。上游这里写死 explicitTemperature = null（其签名没有 temperature
        // 参数）；本移植的 builder 有该参数且调用方（ChatViewModel）会传，因此
        // 按同一优先级语义把实际值传下去 —— 调用方指定了就不覆盖。
        mergeModelOverridesBody(body, temperature, thinkingLevel.isEnabled)

        // [T-android-model-use-passthrough-mode GH#72] Same verbatim merge as the
        // chat-completions builder. Skipped for Codex OAuth inside mergeChatExtraBody.
        mergeChatExtraBody(body)

        return body
    }

    private fun splitResponsesAPIIds(combined: String): Pair<String, String?> {
        val sep = combined.indexOf('|')
        return if (sep < 0) combined to null
        else combined.substring(0, sep) to combined.substring(sep + 1)
    }

    /** Responses-API ids must be ≤64 chars; truncate defensively to avoid 400s. */
    private fun capResponsesId(id: String): String =
        if (id.length <= 64) id else id.substring(0, 64)

    /**
     * [T-android-tool-call-id-too-long] Chat-Completions `tool_calls[].id` /
     * `tool_call_id` must be ≤64 chars — OpenAI-compatible endpoints reject longer
     * ids with a 400 ("string too long. Expected a string with maximum length 64").
     * Two ways an id gets over 64 here:
     *   - a Responses-origin history turn stores the combined "call_…|fc_…" id
     *     (splitResponsesAPIIds' form) which is replayed verbatim on a Chat
     *     Completions request;
     *   - memory-tool / synthetic ids that are long by construction.
     * We keep only the call_-id half (before any '|') and, if still >64, replace
     * it with a deterministic SHA-256-derived id. Determinism matters: the SAME
     * raw id must map to the SAME capped id so the assistant tool_call and its
     * matching tool result still pair up (a mismatch is its own 400). The
     * downstream dedupe pass then guarantees uniqueness within the request.
     */
    private fun capChatToolCallId(id: String): String {
        val callHalf = id.substringBefore('|')
        if (callHalf.length <= 64) return callHalf
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(callHalf.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        // "call_" + 56 hex chars = 61 chars, safely under 64 and clearly a call id.
        return "call_${hex.take(56)}"
    }

    /**
     * [T-prompt-cache-key-truncation] 每个会话的截断水位：[lastLen] 是上一次请求的历史
     * 长度（正常对话只往尾部追加，所以单调增长）。本次长度相对它变小 = 发生了截断 /
     * 回退 / 重放，[epoch] 这时才 +1，并把水位落到新长度——否则截断后历史每长一条都
     * 比旧水位短，会连着轮转很多次。
     *
     * 水位的作用域是 provider 实例（进程内）：重建实例 / 重启进程后 epoch 归零，key 会
     * 回到更早的值。分区键是 [conversationIdentity]（行 id + 首条消息结构指纹），
     * epoch 只是"截断后立刻断开 relay 旧 thread"的尽力而为。
     *
     * [T-prompt-cache-key-chat-identity] 这里的 getOrPut 只有在**分区键真的按会话分开**
     * 时才是「本会话水位」：分区键若退化成常量（旧实现里首条 user 文本为空时就是
     * `sha256("")`），不相关的会话会共用一条 lastLen——A 涨到 50 条后 B 的首次 3 条请
     * 求即触发 epoch++，B 的 key 在会话内部翻转。
     */
    private class CacheKeyWatermark(var lastLen: Int = 0, var epoch: Int = 0)

    /** [T-prompt-cache-key-truncation] 水位表容量，见 [cacheKeyWatermarks]。 */
    private val cacheKeyChatCap = 64

    /**
     * 有界（最近 [cacheKeyChatCap] 个会话）—— provider 实例被 ProviderFactory
     * 记忆化后跨会话共享（[T-android-provider-memo]），所以水位必须按会话指纹分开存。
     */
    private val cacheKeyWatermarks = object : LinkedHashMap<String, CacheKeyWatermark>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheKeyWatermark>): Boolean =
            size > cacheKeyChatCap
    }

    /**
     * [T-prompt-cache-key-truncation] 截断信号：只在历史长度相对本会话水位**变小**时
     * 轮转 epoch。
     */
    private fun truncationEpoch(chatId: String, historyLen: Int): Int =
        synchronized(cacheKeyWatermarks) {
            val mark = cacheKeyWatermarks.getOrPut(chatId) { CacheKeyWatermark(lastLen = historyLen) }
            if (historyLen < mark.lastLen) mark.epoch++
            mark.lastLen = historyLen
            mark.epoch
        }

    private fun sha256Hex(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** [T-prompt-cache-key-chat-identity] 二进制附件参与指纹的最大字节数。 */
    private val cacheKeyFingerprintBytes = 64 * 1024

    /**
     * [T-prompt-cache-key-chat-identity] 附件内容摘要有界化：只喂前
     * [cacheKeyFingerprintBytes] 字节。身份指纹每次请求都要算，而首条消息的图片在
     * 长会话里会被重算上千次——全量哈希几 MB 附件是把成本挂在每个请求上；「类型 +
     * 精确字节长度 + 前缀摘要」三者同时相同的两张真实图片实际不存在。
     */
    private fun sha256HexPrefix(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(bytes, 0, minOf(bytes.size, cacheKeyFingerprintBytes))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fingerprintOf(data: ByteArray): String =
        if (data.isEmpty()) "" else sha256HexPrefix(data).take(16)

    private fun fingerprintOf(text: String): String =
        if (text.isEmpty()) "" else sha256Hex(text.take(cacheKeyFingerprintBytes)).take(16)

    /**
     * [T-prompt-cache-key-chat-identity] 会话身份指纹——`prompt_cache_key` 与截断
     * 水位表 [cacheKeyWatermarks] 的分区键。
     *
     * 旧实现只哈希首条 user 消息的**文本**，于是「图片直发 / 无 caption」这类首条无
     * 文本的会话恒为 `sha256("")`：两张这样的会话拿到**完全相同**的 key，而且还共用
     * 同一份水位——A 涨到 50 条后，B 的首次 3 条请求读到的 lastLen 是 50 → epoch++
     * → B 的 key 在自己会话内部翻转。"distinct between chats" 与「同会话跨轮稳定」
     * 同时失效。
     *
     * 身份现在由两部分组成：
     *  - [LLMMessage.dbMessageId]：发送前就已落库的会话级行 id，同一会话跨轮不变、
     *    跨会话不可能相同；它让「把同一张截图分别发进两个 chat」这种结构完全一致的
     *    会话也不再撞 key / 撞水位。
     *  - 首条消息的结构指纹：文本 + 每个 part 的种类/类型/长度 + 二进制附件的有界内容
     *    摘要。它兜住合成消息（dbMessageId 为 null 的 tool_result、Continue 提醒）以及
     *    「首条消息被就地编辑」——签名变了 key 就跟着变，relay 把 key 当 thread id 时
     *    不会继续拿编辑前的上下文。
     *
     * 字段用 \u0001 分隔，用户可控文本无法伪造出字段边界。
     *
     * 稳定性约束（与 [T-prompt-cache-key-truncation] 同一诉求）：签名只能依赖首条
     * 消息自身，不能依赖 `messages.size` / 末条消息——那两者在同一次用户回合里每发起
     * 一次 LLM 调用都会变，会让 key 每个调用都翻转。
     */
    private fun conversationIdentity(messages: List<LLMMessage>): String {
        // 锚点与旧实现一致：首条 user 消息；没有 user 消息（纯 assistant 历史 /
        // 空列表）时退回首条消息，保证 key 仍是确定值而不是随机值。
        val anchor = messages.firstOrNull { it.role == LLMMessage.Role.USER }
            ?: messages.firstOrNull()
            ?: return "empty"
        val sb = StringBuilder(256)
        anchor.dbMessageId?.takeIf { it.isNotEmpty() }?.let {
            sb.append("db=").append(it).append('\u0001')
        }
        sb.append("role=").append(anchor.role.value).append('\u0001')
        val text = anchor.contentParts.filterIsInstance<AgentContentPart.Text>()
            .joinToString("") { it.text }
            .ifEmpty { anchor.content }
        sb.append("text=").append(text).append('\u0001')
        for (part in anchor.contentParts) {
            when (part) {
                // 已并入上面的 text，这里不再重复计入（否则同一段文本有两种编码）。
                is AgentContentPart.Text -> Unit
                is AgentContentPart.ImageData -> sb.append("image=").append(part.mimeType)
                    .append(':').append(part.data.size).append(':')
                    .append(fingerprintOf(part.data)).append('\u0001')
                is AgentContentPart.ToolUse -> sb.append("tool_use=").append(part.id)
                    .append(':').append(part.name).append('\u0001')
                is AgentContentPart.ToolResult -> sb.append("tool_result=").append(part.id)
                    .append(':').append(part.name).append(':').append(part.content.length)
                    .append('\u0001')
            }
        }
        // 顶层附件（legacy 形状：图片不走 contentParts，只挂在 LLMMessage 上）。
        for (p in anchor.imageParts) {
            sb.append("image_part=").append(p.mimeType).append(':').append(p.data.size)
                .append(':').append(fingerprintOf(p.data)).append('\u0001')
        }
        for (a in anchor.audioParts) {
            sb.append("audio=").append(a.format).append(':').append(a.base64Data.length)
                .append(':').append(fingerprintOf(a.base64Data)).append('\u0001')
        }
        return sb.toString()
    }

    /**
     * Derives a stable per-conversation `prompt_cache_key` for the Responses
     * API. Mirrors iOS derivePromptCacheKey (OpenAIAgentProvider.swift:515) in
     * intent: the conversation's opening turn is re-sent verbatim on every turn,
     * so its identity is stable across turns within the same chat and distinct
     * between chats.
     *
     * [T-prompt-cache-key-chat-identity] The identity is no longer "the first
     * user message's TEXT" alone — a text-less opening turn (image-only send
     * with no caption) degenerated to `sha256("")` for every such chat, identical
     * keys and a SHARED truncation watermark across unrelated conversations. See
     * [conversationIdentity] for the identity actually used.
     */
    private fun derivePromptCacheKey(messages: List<LLMMessage>): String {
        // [T-prompt-cache-key-truncation] 这里是「会话指纹 + 截断 epoch」，**不再**把
        // `messages.size` 与「最后一条 user 文本」拼进 key：
        //   - size 在同一次用户回合里每发起一次 LLM 调用就 +2 以上（追加 assistant 与
        //     tool_result），跨轮严格单调增长 ⇒ key 每个调用都翻转；
        //   - 工具轮的 `lastUser` 落到被转成 function_call_output 的 tool_result 消息上
        //     （无 Text 部件、content 也是空）⇒ 同样的不稳定因子。
        // 两者叠加把跨轮路由亲和性（T126 想要的 prompt cache 命中率）彻底打掉，而旧注释
        // 还宣称"轮间字节稳定"。截断敏感性改由 epoch 承担：只有历史变短才换 key，
        // relay 把 prompt_cache_key 当 thread id 时不会再拿到截断前的上下文。
        val chatId = sha256Hex(conversationIdentity(messages)).take(16)
        val epoch = truncationEpoch(chatId, messages.size)
        return "minis-" + sha256Hex("$chatId:$epoch").take(32)
    }

    /**
     * Map ThinkingLevel → Responses API `reasoning.effort` string. Mirrors
     * iOS reasoningEffort(for:level:) (OpenAIAgentProvider.swift:531).
     * Returns null when the level is OFF — caller decides whether to omit
     * the `reasoning` field entirely or fall back to "low" (Codex requires it).
     */
    private fun mapThinkingLevelToResponsesEffort(level: ThinkingLevel): String? = when (level) {
        ThinkingLevel.OFF -> null
        ThinkingLevel.LOW -> "low"
        ThinkingLevel.MEDIUM -> "medium"
        ThinkingLevel.HIGH -> "high"
        ThinkingLevel.XHIGH -> "xhigh"
        // [T-android-thinking-level-arch] MAX → "max"; ULTRA also → "max" —
        // the Responses/Codex endpoint rejects a literal "ultra"; ultra is a
        // client-side "Max + orchestration" concept only (mirrors iOS).
        ThinkingLevel.MAX, ThinkingLevel.ULTRA -> "max"
    }

    /**
     * Responses-API tool shape — flat {type, name, description, parameters},
     * NOT the Chat Completions wrapper {type, function:{...}}. Mirrors iOS
     * convertToolsResponsesAPI (OpenAIAgentProvider.swift:977).
     */
    private fun AgentToolDefinition.toResponsesAPIJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toJson())
        }
        val params = JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
        return JSONObject().apply {
            put("type", "function")
            put("name", name)
            put("description", description)
            put("parameters", params)
        }
    }

}
