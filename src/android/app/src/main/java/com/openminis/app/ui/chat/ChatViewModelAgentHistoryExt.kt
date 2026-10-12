package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.harness.agent.CompactHistoryProjector
import com.openminis.app.logging.AppLogger

/**
 * [T-loop-context-cut] 上下文治理投影的宿主适配层（循环本体收官刀）。
 *
 * 三路切片（v2 锚点 / v1 遗留 / 分离锚点）、回走边界、preAnchor 剪枝、
 * 角色对齐、摘要内联——全部在 harness（CompactHistoryProjector）。这里
 * 只做状态读取（_compactSummary / _cachedLatestMarker / agentHistory）、
 * chunk 检索器接线（selectSummaryChunks）、日志路由（分离告警按
 * _compactMarkerDetached 一次语义降噪），以及 [T-ctx-warmup-fit] 的热身裁剪
 * 钩子（[ChatViewModel.warmUpKeptForCurrentMarker]：上游这段内联在单体
 * `effectiveAgentHistoryUncounted` 里，投影搬去 harness 后按投影的回吐形状
 * 接回来）。
 */
internal fun ChatViewModel.effectiveAgentHistoryUncounted(): List<LLMMessage> {
    val marker = _cachedLatestMarker
    return CompactHistoryProjector.project(
        history = agentHistory,
        summary = _compactSummary.value,
        marker = marker?.let {
            CompactHistoryProjector.Marker(
                id = it.id,
                version = it.version,
                lastCompactedMessageId = it.lastCompactedMessageId,
                firstKeptMessageId = it.firstKeptMessageId,
                boundaryMessageId = it.boundaryMessageId,
                summaryChunks = it.summaryChunks,
            )
        },
        detachedTailSize = DETACHED_TAIL_MESSAGES,
        keepRecentUserTurns = ChatViewModel.COMPACT_KEEP_RECENT_USER_TURNS,
        chunkRetriever = { chunksJson, query ->
            ChatViewModel.selectSummaryChunks(chunksJson, query)
        },
        // [T-ctx-warmup-fit] 热身裁剪的**真实调用点**。上游把 trimWarmUpToFit 内联在
        // 单体 effectiveAgentHistoryUncounted 里（ChatViewModel.kt:4750-4768），本移植
        // 的投影在 harness，所以按投影的回吐形状把宿主那半边钩回来：
        // (剪枝+对齐后的锚前热身, 锚后那一段, 真正发出去的摘要文本) → 要发出去的热身。
        // 决定按 marker 只下一次、由一个 marker 缓存复用（warmUpDropByMarker），于是
        // 请求前缀跨回合稳定；算不出预算时那次「不裁」不会被缓存，下一请求重判。
        // 同一个缓存也喂 offloadWarmUpScanPlan，两边必须读同一份决定。
        trimWarmUp = { warmUp, rest, summaryText ->
            warmUpKeptForCurrentMarker(
                preAnchorPruned = warmUp,
                postAnchor = rest,
                summaryWrappedText = summaryText,
            )
        },
        log = { message ->
            when {
                // 分离锚点告警：每次会话装载只记一次（原 _compactMarkerDetached
                // 语义——不是每轮 LLM 调用都刷一遍）。
                message.contains("evicted from history") -> {
                    if (!_compactMarkerDetached) {
                        _compactMarkerDetached = true
                        AppLogger.warning(ChatViewModel.TAG, message)
                    }
                }
                message.contains("unresolvable in history") ->
                    android.util.Log.w(ChatViewModel.TAG, message)
                else -> AppLogger.info(ChatViewModel.TAG, message)
            }
        },
    )
}
