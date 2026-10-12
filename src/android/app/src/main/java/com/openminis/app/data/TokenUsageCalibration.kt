package com.openminis.app.data

import java.util.concurrent.ConcurrentHashMap

/**
 * [T-token-usage-calibration] Runtime estimator calibration.
 *
 * Every static heuristic (len/4, ASCII-3/CJK-1, etc.) drifts under real
 * workloads — different models tokenise differently, code blocks are denser
 * than prose, and a single session can span all three. This keeps a
 * per-model EMA scale factor that steers the estimate toward the API's
 * reported usage without ever letting one outlier jerk the lever too far.
 *
 * When the provider reports usage (the [LLMStreamChunk.Usage] event), the
 * caller passes `observe(modelKey, estimated, actual)`; the estimator
 * multiplies its raw heuristic by the stored scale.
 *
 * Thread-safe. No Android dependency (pure JVM testable).
 */
object TokenUsageCalibration {
    private const val MIN_SCALE = 1.0
    private const val MAX_SCALE = 8.0
    private const val ALPHA = 0.3 // EMA weight for new sample

    private val scales = ConcurrentHashMap<String, Double>()

    /** Current scale factor for this model (1.0 = uncalibrated). */
    fun scale(modelKey: String): Double =
        scales[modelKey] ?: 1.0

    /**
     * Feed one observation: [estimated] was the heuristic guess, [actual]
     * is the provider-reported context token count for the same call.
     */
    fun observe(modelKey: String, estimated: Int, actual: Int) {
        if (modelKey.isBlank() || estimated <= 0 || actual <= 0) return
        val ratio = (actual.toDouble() / estimated).coerceIn(MIN_SCALE, MAX_SCALE)
        scales.compute(modelKey) { _, prev ->
            val base = prev ?: 1.0
            base * (1.0 - ALPHA) + ratio * ALPHA
        }
    }

    /**
     * [T-ctx-overflow-attribute-dispatch] Raise one model's scale to at least
     * [ratio] — direct evidence (a provider rejecting a request as too long, or
     * a persisted usage row of this session) rather than an EMA sample, so it is
     * applied at full strength. Never lowers: a rejection is ground truth about
     * THIS model, and [observe]'s 0.3 weight would dilute it right back down.
     */
    fun raise(modelKey: String, ratio: Double) {
        if (modelKey.isBlank() || ratio <= 0.0) return
        val clamped = ratio.coerceIn(MIN_SCALE, MAX_SCALE)
        scales.compute(modelKey) { _, prev -> maxOf(prev ?: 1.0, clamped) }
    }

    /**
     * [T-ctx-measure-outbound] 当前全部比值的一份快照。会话重载（loadSession /
     * 回退）要判断「内存里这套校准还算不算这个会话的证据」，并把内存值交给
     * `ContextSizeMeter.CalibrationState.carryingOver` —— 一条被 provider 拒绝的请求
     * 不会在 usage 行里留下 (report, estimate) 对，那个被抬高的比值只活在这里，抹掉它
     * 会让重试原样发出、再被拒一次。按模型 id 的只读副本，改不到表本身。
     */
    fun snapshot(): Map<String, Double> = scales.toMap()

    /**
     * Apply the calibration to a raw heuristic estimate.
     * Returns the same value when uncalibrated (scale = 1.0).
     */
    fun estimate(modelKey: String, rawEstimate: Int): Int =
        (rawEstimate * scale(modelKey)).toInt().coerceAtLeast(1)
}