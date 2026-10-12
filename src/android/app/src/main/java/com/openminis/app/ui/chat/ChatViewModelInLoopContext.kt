package com.openminis.app.ui.chat

import com.openminis.app.data.ContextPolicy
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.chat.ChatViewModel.InLoopContextAction
import com.openminis.app.R

/**
 * [T-android-auto-compact-inloop] Max in-loop compactions per runAgentLoop.
 * Bounds compact-thrash within a single turn; the MAX_AGENT_TURNS ceiling is
 * never reset by compaction, so this is a second, tighter backstop.
 */
private val maxInLoopCompactions = 3

/**
 * [T-android-auto-compact-inloop] Re-evaluate [ContextPolicy] between agent
 * iterations and act on it (iOS f70ac173).
 *
 * Why this exists: [checkContextBeforeSend] only runs at the SEND entry
 * point. A single turn that fans out into many tool iterations can cross the
 * compact/exhausted thresholds mid-loop, and offload alone cannot recover
 * when the bulk is the model's own text — the turn then slams into the
 * provider's context ceiling.
 *
 * Blocks until the compaction attempt settles, because the next API call
 * must read the freshly-compacted history.
 */
internal suspend fun ChatViewModel.inLoopContextCheck(compactionsSoFar: Int): InLoopContextAction {
    // [T-ctx-measure-outbound] 判这一轮**要发出去**的请求，不是上一轮 provider 报回来
    // 的数。上游 1.14 `ChatViewModel.kt:5562-5634`：那个数在压缩 / 卸载一改历史的瞬间
    // 就过期了，而曾经用来补这个洞的一次性 stale 标记只存在于这条路上（发送前的压缩、
    // 手动压缩都不设），于是它们之后的第一次判断又按压缩前的数字压一次。
    val m = contextMeasurement()
    val tokens = m.measured
    if (tokens <= 0) return InLoopContextAction.PROCEED
    val window = effectiveContextWindowTokens() ?: return InLoopContextAction.PROCEED
    val policy = ContextPolicy.forContextWindow(window, effectiveCompactPercent())
    val verdict = policy.check(tokens, window)
    logContextDecision("in-loop", m, policy.compactThreshold, window, verdict.name)
    return when (verdict) {
        ContextPolicy.CheckResult.OK -> InLoopContextAction.PROCEED

        ContextPolicy.CheckResult.NEEDS_COMPACT -> {
            // [T-ctx-measure-outbound] 预算用完、或者上一次压缩根本没把请求压小，
            // 就不再压 —— 交给下面那张共享决策表定夺「发还是停」。
            val canCompact = compactionsSoFar < maxInLoopCompactions && !lastInLoopCompactionMadeNoProgress
            if (!canCompact) return settleWithoutCompacting(m, window, compactionsSoFar)
            // NOTE: deliberately NOT gated on AutoCompactPrefs. That flag
            // governs the SEND-time decision (compact silently vs. ask
            // first) — mid-loop there is nobody to ask, and the alternative
            // to compacting is aborting the user's turn outright. iOS makes
            // the same call: its in-loop branch
            // (AIChatViewModel.swift:4739) never consults
            // autoCompactEnabled either.
            AppLogger.info(
                ChatViewModel.TAG,
                "[AutoCompact] mid-loop compact #${compactionsSoFar + 1}: $tokens / $window tokens " +
                    "(autoCompactPref=${com.openminis.app.data.AutoCompactPrefs.isEnabled()}, not a gate here)",
            )
            appendSystemInfo(
                text = context.getString(R.string.vm_context_filling_compacting, tokens, window),
                iconKind = "compact",
            )
            val ok = awaitCompaction()
            // [T-ctx-measure-outbound] 压缩失败也算「没进展」（与 iOS 一致），按同一张
            // 表收口：还塞得下就发，塞不下就停。
            if (!ok) {
                lastInLoopCompactionMadeNoProgress = true
                return settleWithoutCompacting(m, window, compactionsSoFar)
            }
            // [T-ctx-measure-outbound] 下一轮会重新量**压缩之后**的历史，所以这里没有
            // 需要防的过期数字（老实现为此把 `_lastTurnContextTokens` 清零：3 秒内两次
            // 压缩、日志里同一个 66358）。要记的是这一压到底有没有把请求压小 ——
            // 没压小的那次不再重试。
            val after = measureOutboundContextTokens()
            lastInLoopCompactionMadeNoProgress = after >= tokens
            AppLogger.info(
                ChatViewModel.TAG,
                "[AutoCompact] mid-loop compact result: measured $tokens → $after" +
                    if (lastInLoopCompactionMadeNoProgress) " (no progress — will not retry)" else "",
            )
            InLoopContextAction.COMPACTED
        }

        // EXHAUSTED is only ever returned by `exhaustedOnly` tiers — windows
        // under 64K, where ContextPolicy sets compactThreshold = 0 precisely
        // BECAUSE the window is too small for auto-compact to pay for itself
        // (the summary plus re-appended recent turns would eat the headroom
        // it just freed). Attempting a "rescue" compaction here would
        // contradict the policy, so stop and let the user decide.
        ContextPolicy.CheckResult.EXHAUSTED -> {
            AppLogger.warning(
                ChatViewModel.TAG,
                "[AutoCompact] exhausted on a no-auto-compact tier ($tokens / $window) — stopping",
            )
            InLoopContextAction.STOP
        }
    }
}

/**
 * [T-ctx-measure-outbound] 压缩已经帮不上这个请求了（预算用尽 / 压不动 / 压缩失败）。
 * 压缩**线**以上的请求照样发得出去 —— 那条线本来就压在窗口之下 —— 而一个只靠比值撑起来
 * 的「超窗」判定，值得放行一次真请求，让 provider 的答复或拒绝来纠正比值。判定表在
 * [ContextPolicy.inLoopStep]，用例在 CompactionDecisionTest。
 *
 * 上游 1.14 `ChatViewModel.kt:5636-5680`（`settleWithoutCompacting`）。两条出口都
 * 必须走这张表，所以调用点按原样出现两次。
 */
private fun ChatViewModel.settleWithoutCompacting(
    m: ChatViewModel.ContextMeasurement,
    window: Int,
    compactionsSoFar: Int,
): ChatViewModel.InLoopContextAction {
    val step = ContextPolicy.inLoopStep(
        verdict = ContextPolicy.CheckResult.NEEDS_COMPACT,
        measured = m.measured,
        rawTokens = m.history + m.fixed,
        window = window,
        canCompact = false,
        ratio = m.ratio,
        uncalibratedSendUsed = sentPastExtrapolatedLimitThisLoop,
    )
    return when (step) {
        ContextPolicy.InLoopStep.SEND_WITHIN_WINDOW -> {
            AppLogger.info(
                ChatViewModel.TAG,
                "[AutoCompact] above threshold after $compactionsSoFar compaction(s) " +
                    "(noProgress=$lastInLoopCompactionMadeNoProgress) but within the window " +
                    "(${m.measured} / $window) — sending",
            )
            InLoopContextAction.PROCEED
        }
        ContextPolicy.InLoopStep.SEND_UNCALIBRATED_ONCE -> {
            sentPastExtrapolatedLimitThisLoop = true
            AppLogger.warning(
                ChatViewModel.TAG,
                "[AutoCompact] calibrated size over the window but the raw estimate fits " +
                    "(${m.history + m.fixed} / $window, ratio=${"%.2f".format(m.ratio)}) — sending once for the provider to decide",
            )
            InLoopContextAction.PROCEED
        }
        else -> {
            AppLogger.warning(
                ChatViewModel.TAG,
                "[AutoCompact] still over the window after $compactionsSoFar compaction(s) — stopping",
            )
            InLoopContextAction.STOP
        }
    }
}
