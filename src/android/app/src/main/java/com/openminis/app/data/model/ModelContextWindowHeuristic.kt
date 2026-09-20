package com.openminis.app.data.model

/**
 * T-ctxslider 54ab8e93: context-window ceiling for the model-group slider.
 * Delegates to [LLMModel.contextWindowTokens] so the slider, the agent loop
 * and token accounting share one heuristic (the old copy here was stuck on
 * gpt-5 → 128K while the getter already knew 400K).
 */
internal fun inferContextWindowTokens(model: LLMModel): Int = model.contextWindowTokens

/**
 * Last-resort max-output when models.dev has never heard of this id.
 * Returns null so the provider's own default (Anthropic 64k, else 16k) can win
 * for unrecognized families — guessing 128k on an 8k model is a hard 400.
 */
internal fun inferredMaxOutputTokens(modelId: String): Int? {
    val lid = modelId.lowercase()
    if ("claude" in lid) {
        return if ("haiku" in lid || "sonnet" in lid) 64_000 else 128_000
    }
    if ("gemini" in lid) return 65_536
    if ("gpt-5" in lid || "o3" in lid || "o4" in lid || "codex" in lid) return 128_000
    if ("grok" in lid) {
        return if ("grok-2" in lid || "grok-3" in lid) 8_192 else 64_000
    }
    if ("deepseek" in lid) return 64_000
    if ("glm" in lid || "kimi" in lid || "moonshot" in lid) return 32_768
    if ("qwen" in lid || "minimax" in lid) return 32_768
    return null
}
