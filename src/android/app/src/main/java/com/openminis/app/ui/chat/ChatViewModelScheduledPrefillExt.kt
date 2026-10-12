package com.openminis.app.ui.chat

import com.openminis.app.logging.AppLogger
import com.openminis.app.scheduled.PrefilledToolCall
import com.openminis.app.scheduled.ScriptedToolTurn

/**
 * [T-scheduled-tool-prefill] The scripted first turn for [prefill], or null when
 * there is nothing to run. Calls to a tool this session does not offer are
 * dropped with a warning: a history showing the model calling a tool it was
 * never given is one it could not have produced, so the prompt is left to the
 * model instead — the behaviour a task had before prefill.
 *
 * 上游 1.14 的落点：`ChatViewModel.kt:10446-10467`（`private fun scriptedTurnFor`）。
 * 本移植把循环拆进了 ChatViewModelAgentLoopExt.kt，所以写成 ChatViewModel 的
 * internal 扩展函数；调用点与断言文本与上游一字不差。
 */
internal fun ChatViewModel.scriptedTurnFor(prefill: List<PrefilledToolCall>): ScriptedToolTurn? {
    if (prefill.isEmpty()) return null
    val available = agentTools.map { it.name }.toSet()
    val turn = ScriptedToolTurn.from(prefill, available)
    if (turn == null || turn.calls.size < prefill.size) {
        AppLogger.warning(
            ChatViewModel.TAG_STREAM,
            "[ScheduledPrefill] dropped ${prefill.size - (turn?.calls?.size ?: 0)} of ${prefill.size} " +
                "prefilled call(s) [${prefill.joinToString { it.toolName }}] — tool unavailable or invalid; " +
                "the model decides for itself",
        )
    }
    return turn
}
