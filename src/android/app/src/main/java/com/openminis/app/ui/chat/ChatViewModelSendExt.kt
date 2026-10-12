package com.openminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.isPureVideoGenerator
import com.openminis.app.data.CapabilityRouter
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.openminis.app.R
// [T-android-submit-outcome] 与上游同一写法：漏斗的返回值写成裸的 `SubmitOutcome`
// （上游它在 ChatViewModel.kt 里，是同一个类的嵌套 sealed class），所以按名字导入。
import com.openminis.app.ui.chat.ChatViewModel.SubmitOutcome

/**
 * @param skipContextCheck set by the pre-send context dialog's own actions,
 *   which have already made the compact decision. Without it the re-entrant
 *   send would re-evaluate the same (still stale until the next usage
 *   chunk) token count and pop the dialog again — iOS guards the identical
 *   re-entry with `skipCompactCheck`.
 * @param headless [T-android-programmatic-prompt-keeps-draft] True when this
 *   send came from a caller with no user behind it (a scheduled fire, a
 *   sub-agent callback, the sessions CLI — see ChatViewModel.submitPrompt).
 *   A prompt nobody typed must never touch the composer DRAFT: the
 *   auto-compact branch below used to clear `_inputText` unconditionally, so a
 *   background prompt that tripped the compact threshold erased what the user
 *   was typing (iOS 2a06de66a), and the ask-user dialog has nobody to answer.
 * @param prefill [T-scheduled-tool-prefill] Prefilled tool calls travelling
 *   with the prompt. Upstream hands them to `runAgentLoop`'s scripted first
 *   turn; this port's loop has no such entry point yet (see submitPrompt's
 *   [T-u6-vm-adapter] note), so they are carried through the pre-send
 *   compaction and then recorded as dropped — never silently.
 * @return [SubmitOutcome] — what actually happened, so a headless caller can
 *   report Queued/Compacting/Rejected instead of "Running" (upstream
 *   [T-android-submit-outcome], ChatViewModel.kt:9038-9059).
 */
internal fun ChatViewModel.sendMessage(
    text: String,
    skipContextCheck: Boolean,
    internalGoalRun: Boolean = false,
    headless: Boolean = false,
    prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(),
): SubmitOutcome {
    // [T-android-paste-mediaref] `[Pasted#N]` markers are NOT expanded here
    // any more.
    //
    // They used to be: this funnel substituted the full text inline, so the
    // persisted message held one enormous `text` part. That is the same
    // shape that made huge tool results freeze the app — every time the
    // bubble scrolled into view, TextKit had to lay out the whole block on
    // the main thread. The markers now survive down to the parts-building
    // step below, where each becomes its own `text/plain` mediaRef; the full
    // content is re-attached to the REQUEST from disk (see toLLMMessage and
    // the fresh-send contentParts), so the model still sees everything while
    // the bubble stays small.
    //
    // The buffer is cleared where the mediaRefs are actually written, not
    // here — clearing at this point would strip the content out from under
    // a send that then bails on the context-check paths below.
    val trimmed = text.trim()
    // While streaming, enqueue instead of silently dropping (iOS: send vs enqueuePrompt).
    if (_isStreaming.value) {
        // [T-p2-gentle-injection] A headless send is PROGRAMMATIC: it waits for
        // the running loop to finish instead of cutting a plan short at the next
        // tool gap. [T-scheduled-tool-prefill] The prefilled calls ride the queue
        // entry, so the loop that finally carries the prompt still runs them.
        val queuedId = enqueuePrompt(text, if (headless) QueuedPromptOrigin.PROGRAMMATIC else QueuedPromptOrigin.USER, prefill)
        return if (queuedId != null) SubmitOutcome.Queued
        else SubmitOutcome.Rejected("enqueue_declined")
    }
    // T180: allow attachments-only sends (no caption). Mirrors iOS, where
    // an empty text + non-empty attachments still produces a valid user
    // message. Without this an image-only "look at this" send dropped.
    if (!internalGoalRun && trimmed.isBlank() && _attachments.value.isEmpty()) {
        return SubmitOutcome.Rejected("empty_prompt")
    }
    if (_isCompacting.value) {
        appendSystemInfo(
            text = context.getString(R.string.vm_wait_compact_before_send),
            iconKind = "compact",
        )
        return SubmitOutcome.Rejected("session_compacting")
    }
    // Context pressure check. Unlike before, needsCompact now HOLDS the
    // send: either compact silently (auto-compact on) or ask first. The
    // whole point is that the request which tripped the threshold must not
    // be the one that goes out over-length.
    if (!skipContextCheck && !internalGoalRun) {
        when (checkContextBeforeSend()) {
            ChatViewModel.PreSendContextAction.PROCEED -> {}
            ChatViewModel.PreSendContextAction.COMPACT_THEN_SEND -> {
                pendingSendText = text
                pendingSendPrefill = prefill
                pendingSendHeadless = headless
                // [T-android-programmatic-prompt-keeps-draft] Only the
                // user's own send owns the composer. A headless prompt (a
                // scheduled fire, a sub-agent callback, the sessions CLI)
                // carries its text in `text`; clearing here erased whatever
                // the user was typing whenever such a prompt tripped the
                // auto-compact threshold (iOS 2a06de66a).
                if (!headless) _inputText.value = ""
                compactAndSendPending()
                return SubmitOutcome.Compacting
            }
            ChatViewModel.PreSendContextAction.ASK_USER -> {
                // [T-android-submit-outcome] No user behind a headless
                // prompt → nobody to answer the dialog. Refuse loudly
                // rather than park the text and report it as running.
                if (headless) return SubmitOutcome.Rejected("context_near_capacity")
                // Park the text on the VM (not the composer) so the dialog
                // owns it; cancelCompactBeforeSend puts it back.
                pendingSendText = text
                pendingSendPrefill = prefill
                pendingSendHeadless = false
                _inputText.value = ""
                _showCompactBeforeSendPrompt.value = true
                return SubmitOutcome.Rejected("awaiting_user_compact_decision")
            }
        }
    }
    // A fresh send supersedes any pending resume — mirror iOS which clears
    // canResume at the top of send().
    _canResume.value = false
    // T185: clear the share-injected flag the moment the user actually
    // sends. Without this, the "Move to…" capsule (gated on
    // hasInjectedShareContent) keeps floating over the user-message row
    // after the share content has been committed — it then visually
    // collides with the user-attachment chips, which renders as the
    // "image attachment shows up as Move to" symptom in T185. Mirrors
    // iOS AIChatView.swift:2255 (`hasInjectedShareContent = false`
    // inside the send button's tap closure).
    if (!internalGoalRun && _hasInjectedShareContent.value) _hasInjectedShareContent.value = false

    val initialProvider = currentProvider
    if (initialProvider == null) {
        _error.value = "No provider configured"
        return SubmitOutcome.Rejected("no_provider")
    }
    var provider: LLMProvider = initialProvider

    _error.value = null

    val currentAttachments = if (internalGoalRun) emptyList() else _attachments.value
    val groupIdForCaps = _selectedGroupId.value
    if (!groupIdForCaps.isNullOrBlank()) {
        val neededCaps = CapabilityRouter.neededForTask(
            trimmed,
            currentAttachments.any { it.isImage },
        )
        resolveProviderFromGroup(
            groupIdForCaps,
            _activeEntryId.value,
            neededCaps,
            pinActiveEntry = neededCaps.isEmpty(),
        )
        currentProvider?.let { provider = it }
    }
    if (internalGoalRun && provider.model.isPureVideoGenerator) {
        appendSystemInfo("Goal execution needs a text-capable model. Select one and resume the Goal.", "info")
        return SubmitOutcome.Rejected("needs_text_model")
    }
    if (!internalGoalRun) clearAttachments()

    // [T-retry-marker-stale] 清掉旧气泡上遗留的瞬时重试横幅（见 InlineError 扩展）。
    clearStaleTransientRetryMarker()

    // T145: claim _isStreaming synchronously so a rapid second tap can't
    // slip past the entry guard during DB/OAuth setup. See retryFromMessage.
    AppLogger.info(ChatViewModel.TAG_STREAM, "send _isStreaming=true (sync, sid=$activeSessionId)")
    _isStreaming.value = true

    // [T-android-instant-thinking] REMOVED: the placeholder inserted here
    // was meant to give instant "thinking" feedback before DB writes complete,
    // but it broke live streaming rendering — buildFlatChatItems frozen/live
    // split + reverseLayout keying prevented the streaming-side-channel delta
    // from applying to the pre-inserted placeholder bubble. Regression tracked
    // to 89896de (introduced) + 7c89d38 (order fix exposed it). Reverted to
    // runAgentLoop's own message creation (overrideAssistantId=null below).
    // The scroll-retention benefit of 89896de was in ChatScreen and is kept.

    // [T-android-thinking-indicator-linger] Invariant sweep: a fresh send
    // only reaches here when no turn is streaming (the _isStreaming guard
    // at the top routes mid-stream sends to enqueuePrompt). So any residual
    // _streamingById entry is an orphan stranded by a prior turn that
    // exited without draining it (e.g. a late delta re-added the entry
    // after finalizeAtTurnLimit / cancel cleared it). mergeStreamingOverlay
    // forces isStreaming=true on any message holding such an entry, so an
    // orphan would render a second "thinking" row alongside the new turn's.
    // Flush them into the canonical messages (isStreaming=false) before the
    // new streaming message is created — no two messages ever stream at once.
    if (_streamingById.value.isNotEmpty()) {
        AppLogger.warning(ChatViewModel.TAG_STREAM, "send: sweeping ${_streamingById.value.size} orphan streaming delta(s) before new turn")
        flushAllStreamingDeltas()
    }

    // T187: when the user is editing a previous message, truncate the
    // conversation from that message (inclusive) before persisting the
    // edited text as a fresh user turn. Snapshot + clear the id here so
    // any error in the truncate path doesn't leave the composer stuck
    // in edit mode.
    val editingId = if (internalGoalRun) null else _editingMessageId.value
    if (editingId != null) _editingMessageId.value = null

    // [T-scheduled-tool-prefill] The prefilled calls are NOT run here: they are
    // handed to [runAgentLoop] below, which streams them as this loop's FIRST
    // turn in place of a provider request (see `pendingScriptedTurn`). The
    // command therefore executes through the ordinary tool path — same tool
    // card, same dispatch, same persisted tool_use/tool_result rows — and the
    // model's first real request already carries its output. A prefill that
    // names a tool this session does not offer is dropped (with a warning) by
    // `scriptedTurnFor`, leaving the old behaviour: the model decides for itself.

    viewModelScope.launch {
        var streamLaunched = false
        try {
        // [T-android-send-before-load] Never write this turn into the transcript
        // while loadSession is still reading it. loadSession snapshots the DB,
        // then REPLACES agentHistory and _messages with that snapshot; a send
        // landing in between (a session opened and prompted at once: debug
        // prompts, scheduled fires, sub agents, a quick tap on a long chat) had
        // its user message wiped from the screen — the chat showed replies with
        // no question — or from agentHistory, so the model never saw it. Title
        // generation then found no user message and the chat stayed "New Chat".
        // Safe mode skips the load entirely, so there is nothing to wait for.
        //
        // [T-merge-1.14-send-before-load] 上游实现：upstream-1.14
        // `ui/chat/ChatViewModel.kt:9232-9245`（`sendMessage` 的
        // `viewModelScope.launch` 体首）。合并时整段丢了，本移植的 send 路径
        // （本文件）一处都没有，于是回归到「发送落进 loadSession 的读窗口、
        // 用户消息被旧快照覆盖」的竞态。这里照上游原样补回：门控必须在
        // `ensureSession()`（会话行）、`chatRepository.appendMessage`（落库）
        // 和 `_messages.value`（上屏）之前。
        if (!sessionLoaded.value && !com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
            AppLogger.info(ChatViewModel.TAG_STREAM, "send waiting for session load (sid=${activeSessionId})")
            sessionLoaded.first { it }
        }        // [T-delete-from-here-inflight] 「从此处删除」的 DB 截断是异步的，而它
        // 的 deleteMessagesAfter 按 `sort_order >= cutoff` 删行：本函数此前会在这
        // 段窗口里把新 user 行落库，落库的 sort_order 大于锚点 → 刚发出的消息连
        // 同气泡一起被删掉，且不进 agentHistory（模型对着不在上下文里的问题作答）。
        // 先等截断 Job 收尾（历史已按截断后的内容重建）再动 DB，发送既不丢也不
        // 会落到旧上下文里。截断任务本身不需要等待 streamJob，无死锁。
        truncationJob?.takeIf { it.isActive }?.join()
        // Ensure session exists in DB (creates on first message for draft sessions)
        val activeSessionId = ensureSession()

        if (editingId != null) {
            truncateBeforeEdit(editingId)
        }

        val prepared = prepareUserAttachments(currentAttachments, activeSessionId)

        // [T-android-paste-mediaref] Fold `[Pasted#N]` markers out to disk
        // BEFORE persisting, so the stored message carries a mediaRef per
        // paste instead of one huge text part.
        val pasted = buildPastedParts(trimmed, activeSessionId)
        if (pasted != null) {
            // Safe to clear now: the content is on disk and the parts JSON
            // below references it, so nothing depends on the buffer any more.
            _pastedTexts.value = _pastedTexts.value.filterNot { it.id in pasted.consumedIds }
        }

        // Save user message — text + persisted mediaRef parts so images survive
        // a session reload (T128). Non-image attachments still only contribute
        // their name (rendered as a file tile) and are not persisted.
        val userPartsJson = buildUserPartsJson(
            trimmed,
            prepared.mediaRefPartsJson,
            prepared.attachedFilesXml,
            bodyPartsJson = pasted?.partsJson,
        )
        val persistedUser = if (internalGoalRun) null else
            chatRepository.appendMessage(activeSessionId, "user", userPartsJson)

        if (persistedUser != null) {
            val userMsg = ChatMessage(
                id = persistedUser.id,
                role = "user",
                content = pasted?.let { p ->
                    p.consumedIds.fold(trimmed) { acc, id -> acc.replace(PastedText.placeholderFor(id), "") }
                        .trim()
                } ?: trimmed,
                imageUris = prepared.imageUris,
                attachmentNames = prepared.attachmentNames + (pasted?.uiNames ?: emptyList()),
                attachmentUris = prepared.nonImageUris + (pasted?.uiUris ?: emptyList()),
                sourceDbIds = listOf(persistedUser.id),
            )
            _messages.value = trimLoadedWindow(_messages.value + userMsg)
            notePersistedUiRow(persistedUser.id, persistedUser.id)
        }
        val imageParts = prepared.imageParts

        // T132: build the user contentParts in iOS order — caption first
        // (only if non-empty), then per image emit
        //   text("[attached image: /var/minis/attachments/uploads/<f>]")
        //   ImageData(<bytes>, <mime>)
        // so the caption sits adjacent to the image in the wire payload,
        // and the agent's read_image tool can resolve the same path back
        // to bytes. Trailing <user-attached-files> XML block lets the
        // model see filenames/sizes without needing tool calls.
        // [T-android-paste-mediaref] The MODEL gets the fully expanded body
        // even though the bubble and the DB row do not. This is the whole
        // point of the split: local rendering stays cheap, the prompt is
        // unchanged from what it used to be.
        //
        // On later turns the same expansion is rebuilt from disk by
        // toLLMMessage's mediaRef branch, so history replay (retry, rerun,
        // session reload, compaction) sees the identical text.
        val modelBodyRaw = pasted?.modelText ?: trimmed
        // [T-user-transformers-wired] The user-transformer chain actually runs
        // here (the 257faf1 commit message claimed this wiring; the code never
        // had it). Enrichment is request-side only — the bubble and the DB row
        // keep the raw text — so retries/reloads re-inject exactly once.
        val workspaceHint = runCatching {
            val dir = com.openminis.app.sandbox.SessionWorkspace
                .hostDir(context.filesDir, activeSessionId, "workspace")
            dir.absolutePath.takeIf { dir.isDirectory }
        }.getOrNull()
        val modelBody = com.openminis.app.harness.agent.MessageTransformerChain.applyUser(
            modelBodyRaw,
            com.openminis.app.harness.agent.UserTransformContext(workspaceHint = workspaceHint),
        )

        val userContentParts = mutableListOf<AgentContentPart>()
        if (modelBody.isNotEmpty()) userContentParts.add(AgentContentPart.Text(modelBody))
        imageParts.forEachIndexed { idx, part ->
            // [T-android-image-downscale-parity] 三条发送路径共用同一个成形函数
            // （上游 1.14 ChatViewModel.kt:9321-9323）：说明文字 + 像素 + 被缩过时的
            // 原始尺寸提示。以前这里内联拼 ImageData，note 整条信息没有出口。
            userContentParts.addModelImage(part, prepared.imageUploadPaths.getOrNull(idx), prepared.imageModelNotes.getOrNull(idx))
        }
        prepared.attachedFilesXml?.let { userContentParts.add(AgentContentPart.Text(it)) }

        if (persistedUser != null) {
            appendBoundedHistory(LLMMessage(
                role = LLMMessage.Role.USER,
                content = modelBody,
                imageParts = imageParts,
                contentParts = userContentParts,
                dbMessageId = persistedUser.id,
            ))
            // [T-context-ring] Refresh after user appends.
            refreshContextUsage()
        }

        // [T-oauth-refresh-dedupe] 刷新块收口进 ChatViewModelOAuthExt（与
        // resumeQueueAfterCancel 共用一份）。
        provider = refreshOAuthProviderIfNeeded(provider)

        // Build system prompt
        // Anthropic OAuth requires the Claude Code prefix in the system prompt
        val baseSystemPrompt = buildSystemPrompt()
        val systemPrompt = if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
            val prefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
            if (baseSystemPrompt?.startsWith(prefix) == true) baseSystemPrompt
            else "$prefix\n\n${baseSystemPrompt ?: ""}"
        } else baseSystemPrompt

        // Start agent loop with fallback. _isStreaming was set synchronously at top.
        streamLaunched = true
        val runSessionId = activeSessionId
        streamJob = launchActiveRun(
            runSessionId,
            Dispatchers.IO,
            ownerSessionIds = setOf(runSessionId, activeSessionId, sessionId),
            beforeStart = { streamJob = it },
        ) { run ->
            AppLogger.info(ChatViewModel.TAG_STREAM, "send streamJob ENTER sid=$runSessionId")
            try {
                // [T-STALL-DIAG] Snapshot BEFORE the (possibly blocking)
                // acquire. If the log shows this line and then no "slot
                // acquired", the turn is parked in acquireSlot — and this
                // line already records who was holding the slots, so the
                // leak is diagnosable from a single log.
                println(
                    "[T-STALL-DIAG] send PRE-ACQUIRE sid=$activeSessionId " +
                        SessionConcurrencyManager.diagSnapshot(),
                )
                // Acquire concurrency slot (suspends if at max)
                SessionConcurrencyManager.acquireSlot(activeSessionId)
                AppLogger.debug(ChatViewModel.TAG_STREAM, "send streamJob slot acquired")
                // [T-a15-overlay-task-title] 上游 1.14 的五处 setActive 都带 sessionTitle：
                // 悬浮胶囊按**任务名**而不是助手名标识这一条。合并拆文件时该实参丢了，
                // tracker 的 upsertTask 收到 null，胶囊第一行只剩 Soul 名。
                SessionActivityTracker.setActive(
                    activeSessionId,
                    onStop = { cancelStream() },
                    sessionTitle = overlaySessionTitle(),
                )


                // Resolve the active group's fallback strategy
                val activeFallbackStrategy = run {
                    val groupId = _selectedGroupId.value
                    groupId?.let { providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy }
                        ?: com.openminis.app.data.model.FallbackStrategy.default
                }

                // Build full fallback provider list upfront (mirrors iOS triedEntries approach)
                val fallbackProviders = buildFallbackProviders(provider)
                val groupChat = groupChatEnabled.value &&
                    !internalGoalRun &&
                    !provider.model.isPureVideoGenerator

                try {
                    if (groupChat) {
                        AppLogger.info(ChatViewModel.TAG_STREAM, "send runGroupChat CALL")
                        groupChatCloseRequested = false
                        runGroupChat(provider, closing = false)
                        if (!run.isStopped && !groupChatCloseRequested) {
                            drainQueuedPrompts(provider, systemPrompt, fallbackProviders, activeFallbackStrategy)
                        }
                        AppLogger.info(ChatViewModel.TAG_STREAM, "send runGroupChat RETURN")
                    } else if (provider.model.isPureVideoGenerator) {
                        AppLogger.info(ChatViewModel.TAG_STREAM, "send runVideoGenerationTurn CALL")
                        runVideoGenerationTurn(provider, modelBody, activeSessionId)
                        AppLogger.info(ChatViewModel.TAG_STREAM, "send runVideoGenerationTurn RETURN")
                    } else {
                        AppLogger.info(ChatViewModel.TAG_STREAM, "send runAgentLoop CALL")
                        runAgentLoop(
                            provider = provider,
                            systemPrompt = systemPrompt,
                            fallbackProviders = fallbackProviders,
                            fallbackStrategy = activeFallbackStrategy,
                            goalExecutionRun = internalGoalRun,
                            // [T-scheduled-tool-prefill] Already-decided tool
                            // calls run as this loop's first turn.
                            prefill = prefill,
                        )
                        AppLogger.info(ChatViewModel.TAG_STREAM, "send runAgentLoop RETURN normal")
                        // Drain any prompts the user queued while this loop was running.
                        // Skipped on cancel: cancelled job won't reach here.
                        if (!run.isStopped) {
                            drainQueuedPrompts(provider, systemPrompt, fallbackProviders, activeFallbackStrategy)
                            AppLogger.info(ChatViewModel.TAG_STREAM, "send drainQueuedPrompts RETURN")
                        }
                    }
                } catch (e: CancellationException) {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "send runAgentLoop CANCELLED")
                    Log.d(ChatViewModel.TAG, "Agent loop cancelled")
                } catch (e: Exception) {
                    AppLogger.error(ChatViewModel.TAG_STREAM, "send runAgentLoop EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                    Log.e(ChatViewModel.TAG, "Agent loop error (all fallbacks exhausted)", e)
                    if (!run.isStopped) {
                        setInlineError(e.message ?: "Unknown error")
                        // T298: completion notifier should show the ❌ variant.
                        SessionActivityTracker.markStreamError(activeSessionId)
                        // [T-queue-shelved] 终局错误：排队消息转搁置态（不再
                        // 自动 drain 重发——用户实测抱怨新轮次完成后旧消息被
                        // 自动塞给智能体）。气泡保留：撤回 / 长按重新排队。
                        // 内存+磁盘镜像同步（见 shelveAllQueuedPrompts）。
                        shelveAllQueuedPrompts()
                    }
                } finally {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "send streamJob FINALLY enter")
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
                    AppLogger.info(ChatViewModel.TAG_STREAM, "send streamJob FINALLY exit")
                }
            } catch (e: com.openminis.app.service.SlotQueueTimeout) {
                if (!run.isStopped) {
                    setInlineError(e.message ?: "会话排队超时，名额已释放")
                    // [T-queue-shelved] 同上：排队消息转搁置态（含磁盘镜像）。
                    shelveAllQueuedPrompts()
                }
            } catch (e: CancellationException) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "send streamJob CANCELLED waiting for slot")
                Log.d(ChatViewModel.TAG, "Cancelled while waiting for concurrency slot")
            }
            // [T-android-stale-streamjob-clears-isstreaming] guard — see
            // `var streamJob` KDoc; identical pattern as runRerunStreamTail.
            if (streamJob === coroutineContext[Job]) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "send _isStreaming=false (about to set)")
                _isStreaming.value = false
            } else {
                AppLogger.info(ChatViewModel.TAG_STREAM, "send _isStreaming SKIPPED (stale job)")
            }
            // [T-stream-end-running-state] 流结束兜底：任何出口（错误/取消/轮限）
            // 没走到 happy-path 终态刷新时，_streamingById 的残留 entry 会让
            // mergeStreamingOverlay 把该气泡的 isStreaming 锁在 true——过程卡
            // 转圈、"运行中" pill 不消失，直到下次发送才被入口 sweep 清掉。
            // flushAllStreamingDeltas 把残留 delta 物化为 canonical
            // isStreaming=false（ awaiting 默认 false），运行状态立即复位。
            flushAllStreamingDeltas()
            AppLogger.info(ChatViewModel.TAG_STREAM, "send streamJob EXIT")
        }
        } finally {
            if (!streamLaunched) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "send _isStreaming=false (setup aborted)")
                _isStreaming.value = false
            }
        }
    }
    // [T-android-submit-outcome] Upstream's `Sent` (ChatViewModel.kt:9481): the
    // turn is streaming now, and the caller is told so instead of assuming it.
    return SubmitOutcome.Running
}
