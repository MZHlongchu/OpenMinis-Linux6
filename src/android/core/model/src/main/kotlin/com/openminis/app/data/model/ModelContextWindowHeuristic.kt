package com.openminis.app.data.model

/**
 * T-ctxslider 54ab8e93: context-window ceiling for the model-group slider.
 * Delegates to [LLMModel.contextWindowTokens] so the slider, the agent loop
 * and token accounting share one heuristic (the old copy here was stuck on
 * gpt-5 → 128K while the getter already knew 400K).
 */
internal fun inferContextWindowTokens(model: LLMModel): Int = model.contextWindowTokens

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
 */
fun applyUnrecognizedModelDefaults(model: LLMModel): LLMModel {
    return model.copy(
        contextWindow = model.contextWindow?.takeIf { it > 0 } ?: UNKNOWN_CONTEXT_WINDOW,
        maxOutputTokens = model.maxOutputTokens?.takeIf { it > 0 } ?: UNKNOWN_MAX_OUTPUT,
        supportsReasoning = model.supportsReasoning ?: true,
        reasoningEffortValues = model.reasoningEffortValues?.takeIf { it.isNotEmpty() }
            ?: UNKNOWN_REASONING_EFFORT,
        inputModalities = model.inputModalities ?: UNKNOWN_TEXT_MODALITY,
        outputModalities = model.outputModalities ?: UNKNOWN_TEXT_MODALITY,
    )
}

/** `gpt-6` / `GPT6` / `gpt-6免费`, but not `gpt-60`. */
internal fun hasGptFamily(haystack: String, version: Int): Boolean {
    if ("gpt-$version" in haystack) return true
    val compact = haystack.replace("-", "").replace("_", "").replace(" ", "")
    val needle = "gpt$version"
    val idx = compact.indexOf(needle)
    if (idx < 0) return false
    val after = compact.getOrNull(idx + needle.length)
    return after == null || !after.isDigit()
}
