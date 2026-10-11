package com.openminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.openminis.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Set error inline on the last assistant message (iOS: message.error).
 *
 *  Also clears [ChatMessage.isAwaitingModelResponse] — without this, an
 *  exception thrown after a tool turn (which sets isAwaitingModelResponse=
 *  true at runAgentLoop ~4015) leaves the "Minis is thinking" indicator
 *  on screen even though streaming is over. The flag is per-message and
 *  is not implicitly cleared by isStreaming=false. */
internal fun ChatViewModel.setInlineError(errorText: String) {
    // [T-error-persist-android] Never let an empty/blank error string reach
    // the banner. The UI gate is `message.error?.let { … }` — a non-null ""
    // would render an EMPTY error banner, and (now that errors persist) it
    // would stick across reloads. An exception with a blank `message`
    // (`e.message ?: "Unknown error"` only guards null, not "") is the
    // realistic source. Coalesce to a generic non-empty message.
    val safeError = errorText.ifBlank { context.getString(R.string.error_empty_response_generic) }
    // T-streaming-side-channel: before mutating the canonical message,
    // drain any in-flight streaming delta so the error frame carries
    // the actual accumulated content (otherwise the user sees content
    // snap back to a pre-stream prefix when the error banner appears).
    flushAllStreamingDeltas()
    val msgs = _messages.value.toMutableList()
    val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
    if (lastAssistantIdx >= 0) {
        val msg = msgs[lastAssistantIdx]
        msgs[lastAssistantIdx] = msg.copy(
            error = safeError,
            isStreaming = false,
            isAwaitingModelResponse = false,
        )
        _messages.value = msgs
        // [T-error-persist-android] Persist the terminal error so the inline
        // error + Retry button survive a session reload. The in-memory bubble
        // id differs from the persisted row id, so the row is found in the DB.
        //
        // [T-android-error-persist-current-turn] GH#263: 目标行必须属于**当前这一回合**。
        // 上游 1.14（ChatViewModel.kt:9518-9531）用 persistTurnError 替换了「更新会话里
        // 最后一条 assistant 行」的写法——后者在本回合还没落任何输出时，会把错误横幅盖在
        // 上一回合那条正常回复上。persistTurnError 盖本回合最新的 assistant 行，本回合没有
        // 行时插一条空载体行。清除路径仍用 updateLastAssistantError(null)。
        // 合并时这个接线被丢了（本文件此前还在调用 updateLastAssistantError 写错误）。
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val write = chatRepository.persistTurnError(sid, safeError)
                    Log.i(ChatViewModel.TAG, "[ErrorPersist] sid=${sid.take(8)} $write")
                } catch (e: Exception) { Log.w(ChatViewModel.TAG, "persist error_info failed: ${e.message}") }
            }
        }
    } else {
        // No assistant message yet — fall back to top-level error
        _error.value = safeError
    }
}

/**
 * [T-retry-marker-stale] 新发送前的兜底：上一回合若死在自动重试倒计时里，
 * 旧气泡上可能还盖着「— retrying (n/m)…」的瞬时错误横幅（倒计时取消路径
 * 已清，这里防的是任何漏网路径）。新回合开跑 = 旧标记作废。只清瞬时重试
 * 文案——真正的终局错误横幅是历史事实，保留。
 */
internal fun ChatViewModel.clearStaleTransientRetryMarker() {
    val lastAssistant = _messages.value.lastOrNull { it.role == "assistant" } ?: return
    if (lastAssistant.error?.contains("— retrying (") == true) {
        clearInlineError()
    }
}
