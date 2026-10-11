package com.openminis.app.data.model

/**
 * T-ctxslider 54ab8e93: heuristic context-window inference used by the
 * model-group "Limit Context Window" slider when a member's
 * `LLMModel.contextWindow` field is missing or non-positive.
 *
 * IMPORTANT: this is intentionally NOT folded into [LLMModel.contextWindowTokens]
 * — that getter is read by the agent loop / token accounting paths and we
 * don't want to silently overwrite source-of-truth metadata. This helper is
 * used ONLY when computing the slider's "Unlimited" ceiling so that a group
 * whose members lack contextWindow metadata still gets a reasonable cap
 * instead of degrading to the 64K floor.
 *
 * Both functions return the model's own `contextWindow` when it is set, so
 * neither body runs for a model models.dev (or OpenRouter / Codex / Copilot /
 * a manual edit) has already given a window. They only ever see an id no
 * catalog has shipped yet — a just-released model, or a name a relay invented.
 *
 * SEPARATE IMPLEMENTATIONS, MATCHING VALUES. Keeping the two bodies apart is
 * deliberate (above), but where both know a family they must return the same
 * number, and the slider must never exceed the loop: a ceiling above the real
 * window lets a user pick a limit the model will reject. `ContextWindowDefaultsTest`
 * asserts that agreement over every family, so adding a branch to one function
 * and not the other fails there instead of shipping.
 *
 * [T-core-model-relocation] 合并 1.14 的取舍：我们这一侧曾把它压成
 * `= model.contextWindowTokens`（一次委托，从结构上消灭"两份表漂移"）。上游
 * 1.14 保留独立实现，并用 `ContextWindowDefaultsTest` 把"两侧必须同值"钉成
 * 测试，还明确要求滑块侧比环路侧更严格——只认开头（startsWith / "/" 前缀），
 * 不认名字中段出现的家族标记（该测试的 `the slider does not claim a family
 * from a mid-name marker`）。委托版做不到这一条（会跟着环路侧的裸 contains
 * 走），故此处采纳上游的表；防漂移的职责交给那份测试。
 * 另外它必须 public：上游的测试在 :app 的测试源集里直接调用本函数，而
 * `internal` 只在模块内可见。
 */
fun inferContextWindowTokens(model: LLMModel): Int {
    model.contextWindow?.takeIf { it > 0 }?.let { return it }
    val idLower = model.id.lowercase()

    // 1M-class models — match before the generic claude-* / gemini-* fall-throughs.
    val millionClassPatterns = listOf(
        Regex("claude-.*-1m"),
        Regex("claude-opus-4-[5678]"),
        Regex("claude-sonnet-4-[567]"),
    )
    if (millionClassPatterns.any { it.containsMatchIn(idLower) }) return 1_000_000
    if (idLower.contains("gemini-2.5-pro") ||
        idLower.contains("gemini-2.0-pro") ||
        idLower.contains("gemini-1.5-pro") ||
        idLower.contains("gemini-2.5-flash") ||
        idLower.contains("gemini-3-pro") ||
        idLower.contains("gemini-3-flash")
    ) return 1_000_000

    if (idLower.startsWith("claude-") || idLower.contains("/claude-")) return 200_000

    if (idLower.startsWith("gpt-4o") || idLower.contains("/gpt-4o")) return 128_000
    if (idLower.startsWith("gpt-4-turbo") || idLower.contains("/gpt-4-turbo")) return 128_000
    // gpt-5 stays ahead of the bare gpt-4 / codex rows below, matching the
    // loop's order, so `gpt-5.3-codex` is a 400K gpt-5 rather than a 200K codex.
    if (idLower.startsWith("gpt-5") || idLower.contains("/gpt-5")) return 400_000

    // Bare gpt-4 is the 8K original. This row used to say 16_000 — the ONE
    // place the slider sat ABOVE the loop, i.e. offering a ceiling higher than
    // the real window, which is the harmful direction.
    if (idLower.startsWith("gpt-4") || idLower.contains("/gpt-4")) return 8_000
    if (idLower.startsWith("gpt-3.5") || idLower.contains("/gpt-3.5")) return 16_000

    // o-series reasoning models and Codex: 200K on both sides. Absent here
    // before, so an uncatalogued `o4-mini` collapsed to the trailing 128K
    // default and the slider could not be raised to the window the loop uses.
    if (idLower.startsWith("o3") || idLower.contains("/o3")) return 200_000
    if (idLower.startsWith("o4") || idLower.contains("/o4")) return 200_000
    if (idLower.startsWith("codex") || idLower.contains("/codex")) return 200_000

    if (idLower.startsWith("deepseek-") || idLower.contains("/deepseek-")) return 128_000

    // xAI Grok. [T-android-grok-context-underestimate] 33b028477 added this
    // branch to LLMModel.contextWindowTokens but not here, so the slider's
    // "Unlimited" ceiling for an uncatalogued Grok collapsed to the trailing
    // 128K default while the agent loop was already using 256K — a group limit
    // could not be raised to the window the loop actually honours. The two
    // heuristics are deliberately separate implementations (see the header),
    // but where they both know a family they must agree, so the values here
    // mirror the loop's exactly: Grok 2/3 are the 131K generation, Grok 4 and
    // later floor at 256K.
    if (idLower.startsWith("grok") || idLower.contains("/grok")) {
        if (idLower.contains("grok-2") || idLower.contains("grok-3")) return 131_072
        return 256_000
    }

    return 128_000
}

internal const val UNKNOWN_CONTEXT_WINDOW = 256_000
internal const val UNKNOWN_MAX_OUTPUT = 128_000
internal val UNKNOWN_REASONING_EFFORT = listOf("low", "medium", "high", "xhigh", "max")
internal val UNKNOWN_TEXT_MODALITY = listOf("text")

/**
 * Family-only max-output. Null means the id is not a known cloud family.
 */
internal fun inferredFamilyMaxOutputTokens(modelId: String, displayName: String = ""): Int? {
    val lid = "$modelId $displayName".lowercase()
    if ("claude" in lid) {
        return if ("haiku" in lid || "sonnet" in lid) 64_000 else 128_000
    }
    if ("gemini" in lid) return 65_536
    if (hasGptFamily(lid, 6) || "gpt-5" in lid || "o3" in lid || "o4" in lid || "codex" in lid) {
        return 128_000
    }
    if ("grok" in lid) {
        return if ("grok-2" in lid || "grok-3" in lid) 8_192 else 64_000
    }
    if ("deepseek" in lid) return 64_000
    if ("glm" in lid || "kimi" in lid || "moonshot" in lid) return 32_768
    if ("qwen" in lid || "minimax" in lid) return 32_768
    return null
}

internal fun isRecognizedModelFamily(modelId: String, displayName: String = ""): Boolean =
    inferredFamilyMaxOutputTokens(modelId, displayName) != null

/**
 * Last-resort max-output when models.dev / DataLearner have never heard of this id.
 * Unknown ids get 128k (with 256k context, thinking max, text modalities) rather
 * than the provider's 16k default.
 */
fun inferredMaxOutputTokens(modelId: String, displayName: String = ""): Int? {
    return inferredFamilyMaxOutputTokens(modelId, displayName) ?: UNKNOWN_MAX_OUTPUT
}

/**
 * Fill holes when the catalog / DataLearner / provider left a field empty.
 * Unknown ids and "we have an id but no params" share the same stamp:
 * 256k context, 128k output, thinking on (ceiling max), text modalities.
 * Never overwrites a field that is already set (catalog, family overlay, user).
 *
 * Video/image inference runs first. Stamping `["text"]` before that made a
 * catalog-silent Sora/Seedream look like an explicit text model, so chat never
 * took the generation path (1.36.4 / 1.36.6).
 */
fun applyUnrecognizedModelDefaults(model: LLMModel): LLMModel {
    val inferred = model
        .withInferredVoiceModality()
        .withInferredVideoModality()
        .withInferredImageModality()
    return inferred.copy(
        contextWindow = inferred.contextWindow?.takeIf { it > 0 } ?: UNKNOWN_CONTEXT_WINDOW,
        maxOutputTokens = inferred.maxOutputTokens?.takeIf { it > 0 } ?: UNKNOWN_MAX_OUTPUT,
        supportsReasoning = inferred.supportsReasoning ?: true,
        reasoningEffortValues = inferred.reasoningEffortValues?.takeIf { it.isNotEmpty() }
            ?: UNKNOWN_REASONING_EFFORT,
        inputModalities = inferred.inputModalities ?: UNKNOWN_TEXT_MODALITY,
        outputModalities = inferred.outputModalities ?: UNKNOWN_TEXT_MODALITY,
    )
}

/**
 * Undo the pre-1.36.50 text stamp on a dedicated generator whose other fields
 * are still the empty-catalog defaults. An explicit catalog modality, or any
 * non-default context/output budget, is left alone.
 */
fun stripDefaultedGeneratorModality(model: LLMModel): LLMModel {
    if (model.outputModalities != UNKNOWN_TEXT_MODALITY) return model
    if (model.contextWindow != null && model.contextWindow != UNKNOWN_CONTEXT_WINDOW) return model
    if (model.maxOutputTokens != null && model.maxOutputTokens != UNKNOWN_MAX_OUTPUT) return model
    val generator = VideoModality.looksLikeVideoGenerator(model.id, model.displayName) ||
        model.looksLikeImageGenerator
    if (!generator) return model
    return model.copy(outputModalities = null)
}

/** `gpt-6` / `GPT6` / `gpt-6免费`, but not `gpt-60`. */
fun hasGptFamily(haystack: String, version: Int): Boolean {
    val compact = haystack.lowercase().replace("-", "").replace("_", "").replace(" ", "")
    val needle = "gpt$version"
    val idx = compact.indexOf(needle)
    if (idx < 0) return false
    val after = compact.getOrNull(idx + needle.length)
    return after == null || !after.isDigit()
}
