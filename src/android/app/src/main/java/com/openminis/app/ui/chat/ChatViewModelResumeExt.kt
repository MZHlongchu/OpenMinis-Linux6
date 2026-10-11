package com.openminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Resume an interrupted agent loop. Injects a `<system-reminder>` into
 * agentHistory so the model picks up where it left off, then re-enters
 * the agent loop in a fresh [streamJob]. Mirrors iOS
 * AIChatViewModel.resume().
 *
 * Safe to call only when [canResume] is true and [isStreaming] is false.
 * Clears [_canResume] on entry so repeated taps don't stack.
 */
fun ChatViewModel.resume() {
    if (_isStreaming.value || !_canResume.value) return
    // [T-android-mute-reset-on-drain] Resume is new work, as on iOS: 继续也是**新的一轮**，
    // 子代理 Stop 留下的委派静音只属于被中断的那一轮，这里必须解除。
    com.openminis.app.agent.jobs.AgentJobRegistry.clearDelegationMute(activeSessionId)
    val provider = currentProvider ?: run {
        _error.value = "No provider configured"
        return
    }
    _canResume.value = false
    _error.value = null
    // [T-error-persist-android] resume() follows finalizeAtTurnLimit's
    // setInlineError (which persisted an error sticker on the last assistant
    // row). Clear it now so a successful resume doesn't merge-resurrect the
    // turn-limit banner on the next reload.
    clearPersistedLastAssistantError()
    AppLogger.info(ChatViewModel.TAG, "▶️ resume: continuing partial assistant message (no new header emitted)")
    // [T-android-tool-autoscroll] Start-of-turn snap. The thinking
    // placeholder is the only visible delta until the model's first
    // token, and the auto-follow tuple won't advance until content
    // streams — ChatScreen would otherwise leave the placeholder
    // behind the input bar.
    _forceScrollToBottom.tryEmit(Unit)

    // [T-truncation-critical-section] resume 也是会话行的**写入者**：下面把
    // `<system-reminder>` 当 user 行追加进 agentHistory 与 DB。原先 `_isStreaming`
    // 的置位在协程里、还在 buildSystemPrompt() 之后 —— 剪裁在飞时点「继续」，这条
    // reminder 行的 sort_order 大于剪裁锚点，会被剪裁的
    // `deleteMessagesAfter(sort_order >= cutoff)` 顺手删掉（T-delete-from-here-inflight
    // 的现场：气泡消失、agentHistory 里没有这一行）。同步置位与 send/retry 的 T145
    // 同口径：剪裁入口一律以 `_isStreaming == false` 为前置条件，从这一刻起不会有
    // 新剪裁被放行；**已经**放行的那一次由下面协程的 awaitIdle 等它收尾（剪裁侧
    // 从不等待写入者，不会死锁）。
    AppLogger.info(ChatViewModel.TAG_STREAM, "resume _isStreaming=true (sync, sid=$activeSessionId)")
    _isStreaming.value = true

    // [T-resume-prompt-scope] systemPrompt 必须在 awaitIdle + reminder 追加**之后**
    // 才 build（buildSystemPrompt 读的是重建后的 agentHistory），但使用点在下面的
    // streamJob 里 —— 所以在这里声明、在 try 块里赋值，而不是在 try 内 `val`。
    var systemPrompt: String? = null

    viewModelScope.launch {
        try {
            // [T-truncation-critical-section] 写入者准入：等在飞剪裁收尾再碰
            // agentHistory / DB。没有剪裁时 awaitIdle 不挂起（本协程在
            // Main.immediate 上内联执行到第一个挂起点），所以常见的无剪裁路径
            // 不引入任何额外调度。
            TruncationInterlock.awaitIdle(truncationJob)

            // If history ends with assistant (Case 2: text-cancel committed a
            // partial assistant turn), append a continue reminder as a user
            // message. If it ends with user tool_result (Case 1), it's already
            // a valid starting point for the next API call — no reminder needed.
            // [T-truncation-critical-section] 判定 + 追加 + 落库全部挪到
            // awaitIdle 之后：剪裁收尾的 awaitBoundedHistoryRebuild 会从 DB 重建
            // agentHistory，先追加的 reminder 会被那次重建抹掉。
            if (agentHistory.lastOrNull()?.role == LLMMessage.Role.ASSISTANT) {
                val reminder =
                    "<system-reminder>The user stopped the previous response but now wants to continue. Pick up exactly where you left off.</system-reminder>"
                val parts = listOf<AgentContentPart>(AgentContentPart.Text(reminder))
                appendBoundedHistory(
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = reminder,
                        contentParts = parts,
                    )
                )
                // DB 侧落库是 best-effort（与改动前一致：写失败不该连带取消整次
                // resume —— reminder 已经在上下文里，这一轮照常继续）。
                runCatching {
                    withContext(Dispatchers.IO) {
                        val partsJson = """[{"type":"text","value":${escapeJson(reminder)}}]"""
                        val persisted = chatRepository.appendMessage(activeSessionId, "user", partsJson)
                        withContext(Dispatchers.Main.immediate) {
                            notePersistedUiRow(null, persisted.id)
                        }
                    }
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    AppLogger.warning(
                        ChatViewModel.TAG,
                        "resume: reminder row not persisted (${e.javaClass.simpleName}: ${e.message}) — continuing in-memory",
                    )
                }
            }

            val baseSystemPrompt = buildSystemPrompt()
            systemPrompt =
                if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
                    val prefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
                    if (baseSystemPrompt?.startsWith(prefix) == true) baseSystemPrompt
                    else "$prefix\n\n${baseSystemPrompt ?: ""}"
                } else baseSystemPrompt
        } catch (e: CancellationException) {
            // [T-truncation-critical-section] `_isStreaming` 是同步置位的（见函数头），
            // 前导阶段（等剪裁 / 追加 reminder / 建 prompt）任何失败或取消都必须把它
            // 复位：否则一次失败的 resume 会把会话永久锁在「进行中」——发送改走排队、
            // 剪裁入口全被拒。
            AppLogger.warning(
                ChatViewModel.TAG_STREAM,
                "resume preamble CANCELLED (${e.javaClass.simpleName}) — resetting _isStreaming",
            )
            _isStreaming.value = false
            throw e
        } catch (e: Exception) {
            AppLogger.error(
                ChatViewModel.TAG_STREAM,
                "resume preamble EXCEPTION ${e.javaClass.simpleName}: ${e.message}",
            )
            _isStreaming.value = false
            setInlineError(e.message ?: "Unknown error")
            return@launch
        }

        // [T-truncation-critical-section] `_isStreaming` 已在函数入口**同步**置位
        // （见函数头），这里不再重复 claim —— 只留一条日志。
        AppLogger.info(
            ChatViewModel.TAG_STREAM,
            "resume stream launch (sid=$activeSessionId, streaming flag already claimed)",
        )
        streamJob = launchActiveRun(activeSessionId, Dispatchers.IO, ownerSessionIds = setOf(activeSessionId, sessionId, realSessionId), beforeStart = { streamJob = it }) {
            AppLogger.info(ChatViewModel.TAG_STREAM, "resume streamJob ENTER sid=$activeSessionId")
            try {
                SessionConcurrencyManager.acquireSlot(activeSessionId)
                AppLogger.debug(ChatViewModel.TAG_STREAM, "resume streamJob slot acquired")
                // [T-a15-overlay-task-title] 上游 1.14 的五处 setActive 都带 sessionTitle：
                // 悬浮胶囊按**任务名**而不是助手名标识这一条。合并拆文件时该实参丢了，
                // tracker 的 upsertTask 收到 null，胶囊第一行只剩 Soul 名。
                SessionActivityTracker.setActive(
                    activeSessionId,
                    onStop = { cancelStream() },
                    sessionTitle = overlaySessionTitle(),
                )
                val activeFallbackStrategy = run {
                    val groupId = _selectedGroupId.value
                    groupId?.let {
                        providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy
                    } ?: com.openminis.app.data.model.FallbackStrategy.default
                }
                val fallbackProviders = buildFallbackProviders(provider)
                try {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resume runAgentLoop CALL")
                    runAgentLoop(
                        provider = provider,
                        systemPrompt = systemPrompt,
                        fallbackProviders = fallbackProviders,
                        fallbackStrategy = activeFallbackStrategy,
                    )
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resume runAgentLoop RETURN normal")
                    if (com.openminis.app.service.ActiveRunContext.current()?.isStopped == false) {
                        drainQueuedPrompts(provider, systemPrompt, fallbackProviders, activeFallbackStrategy)
                        AppLogger.info(ChatViewModel.TAG_STREAM, "resume drainQueuedPrompts RETURN")
                    }
                } catch (e: CancellationException) {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resume runAgentLoop CANCELLED")
                    Log.d(ChatViewModel.TAG, "Agent loop cancelled (resume)")
                } catch (e: Exception) {
                    AppLogger.error(ChatViewModel.TAG_STREAM, "resume runAgentLoop EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                    Log.e(ChatViewModel.TAG, "Agent loop error (resume)", e)
                    if (com.openminis.app.service.ActiveRunContext.current()?.isStopped == false) {
                        setInlineError(e.message ?: "Unknown error")
                    }
                } finally {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resume streamJob FINALLY enter")
                    // [T-android-overlay-reply-status-34599] Surface
                    // the assistant's most recent reply text to the
                    // overlay BEFORE setInactive so the post-completion
                    // overlay state (no-running, has-outcome) carries a
                    // non-null excerpt. Reading _messages here is safe:
                    // we're in the finally block of the agent loop and
                    // the stream has already flushed its last delta.
                    publishOverlayReplyExcerpt(activeSessionId)
                    SessionActivityTracker.setInactive(activeSessionId)
                    SessionConcurrencyManager.releaseSlot(activeSessionId)
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resume streamJob FINALLY exit")
                }
            } catch (e: com.openminis.app.service.SlotQueueTimeout) {
                if (com.openminis.app.service.ActiveRunContext.current()?.isStopped == false) {
                    setInlineError(e.message ?: "会话排队超时，名额已释放")
                }
            } catch (e: CancellationException) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "resume streamJob CANCELLED waiting for slot")
                Log.d(ChatViewModel.TAG, "Cancelled while waiting for concurrency slot (resume)")
            }
            // [T-android-stale-streamjob-clears-isstreaming] guard.
            if (streamJob === coroutineContext[Job]) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "resume _isStreaming=false (about to set)")
                _isStreaming.value = false
            } else {
                AppLogger.info(ChatViewModel.TAG_STREAM, "resume _isStreaming SKIPPED (stale job)")
            }
            AppLogger.info(ChatViewModel.TAG_STREAM, "resume streamJob EXIT")
        }
    }
}
