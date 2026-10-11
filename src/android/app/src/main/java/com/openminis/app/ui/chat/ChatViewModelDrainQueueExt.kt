package com.openminis.app.ui.chat

import android.util.Log
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.isPureVideoGenerator
import com.openminis.app.provider.LLMProvider
import com.openminis.app.queue.PromptQueueBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Drain queued prompts after an agent loop finishes. Each queued prompt is
 * appended to agentHistory, persisted, and re-runs the agent loop.
 * Mirrors iOS AIChatViewModel.drainQueuedPrompts().
 */
internal suspend fun ChatViewModel.drainQueuedPrompts(
    provider: LLMProvider,
    systemPrompt: String?,
    fallbackProviders: List<ChatViewModel.FallbackCandidate>,
    fallbackStrategy: com.openminis.app.data.model.FallbackStrategy,
) {
    // [T-queue-shelved] 搁置态的消息不自动 drain（打断/异常后不自动重发旧消息）。
    while (_promptQueue.value.any { !it.shelved }) {
        // [T-android-mute-reset-on-drain] 每一批 drain 都是**新的一轮**：上一轮里
        // "停掉某个子代理"留下的委派静音只属于那一轮。上游 1.14 之前，静音只由公开
        // 的 sendMessage 解除，于是 Stop 之后排队、轮末被 drain 的提示词整轮都是静音
        // 的——它启的子代理把结果投给没人。iOS f1f4f23ba 在这里也重置。
        // 位置对齐上游：取批之前、每轮循环入口。
        com.openminis.app.agent.jobs.AgentJobRegistry.clearDelegationMute(activeSessionId)
        if (!materializeQueuedPromptsAsUserTurn()) break

        try {
            if (groupChatEnabled.value && !provider.model.isPureVideoGenerator) {
                runGroupChat(provider, closing = false)
            } else {
            runAgentLoop(
                provider = provider,
                systemPrompt = systemPrompt,
                fallbackProviders = fallbackProviders,
                fallbackStrategy = fallbackStrategy,
            )
            }
        } catch (e: CancellationException) {
            Log.d(ChatViewModel.TAG, "Agent loop (queued-drain) cancelled")
            // Cancel mid-drain: cancelStream() will check _promptQueue
            // and call resumeQueueAfterCancel() if anything's still pending,
            // so just propagate.
            throw e
        } catch (e: Exception) {
            Log.e(ChatViewModel.TAG, "Agent loop (queued-drain) error", e)
            if (com.openminis.app.service.ActiveRunContext.current()?.isStopped == false) {
                setInlineError(e.message ?: "Unknown error")
            }
            break
        }
    }
}

/**
 * [T-retry-stale-context] 把幽灵队列物化成持久用户轮（**不跑回合**）。
 *
 * 供两条路径复用：
 *  - [drainQueuedPrompts]：物化后继续跑 agent loop（原行为）；
 *  - [retryLast]：重试前先物化——用户在错误横幅期间发的消息若还卡在
 *    _promptQueue（错误出口此前不排空），它们不在 agentHistory 里，直接
 *    重试会拿到「上一次错误时」的上下文，用户消息被跳过。
 *
 * @return true = 物化了至少一条排队提示词。
 */
internal suspend fun ChatViewModel.materializeQueuedPromptsAsUserTurn(): Boolean {
    // [T-queue-shelved] 只物化 pending 态；搁置消息留在队列（不自动重发）。
    val queued = _promptQueue.value.filter { !it.shelved }
    if (queued.isEmpty()) return false

    // [T-truncation-digest-clear] drain 也是上下文重置点（打断后恢复/轮末
    // 消费）：digest 摘录里可能含已被用户删除/放弃的轮次，注入即串上下文。
    // [T-queue-digest-clear-after-consume] 清空点已后移到下面的「消费确定」处：
    // 上面那个空内容早退没有注入任何东西，抹掉摘录就是白丢窗口外上下文。
    Log.i(ChatViewModel.TAG, "📨[DRAIN] Draining ${queued.size} queued prompt(s): " +
        queued.joinToString(", ") { "${it.id}=\"${it.text.take(20)}...\"" })

    // [T-queue-inject-ghost-bubble] 与 mid-loop 注入同口径：清队与气泡翻转都推迟
    // 到「确定要物化」之后——纯文档附件且 XML 为空等边缘组合会在这里早退，早退时
    // 队列/气泡/磁盘镜像三方原样保留（不再产生幽灵气泡与丢词）。
    // Build a combined user message (text + images from all queued prompts).
    // Persist as a single row.
    val sid = ensureSession()
    val combinedAttachments = queued.flatMap { it.attachments }
    val prepared = prepareUserAttachments(combinedAttachments, sid)

    // T132: same shape as sendMessage — caption(s) first, then for each
    // image emit "[attached image: <path>]" + ImageData, finally the
    // <user-attached-files> XML. Keeps caption adjacent to image and
    // lets the agent re-read the file via read_image.
    val combinedParts = mutableListOf<AgentContentPart>()
    val combinedText = StringBuilder()
    for (prompt in queued) {
        if (prompt.text.isNotEmpty()) {
            if (combinedText.isNotEmpty()) combinedText.append("\n\n")
            combinedText.append(prompt.text)
            combinedParts.add(AgentContentPart.Text(prompt.text))
        }
    }
    prepared.imageParts.forEachIndexed { idx, part ->
        // [T-android-image-downscale-parity] 上游 1.14 ChatViewModel.kt:8762-8764。
        combinedParts.addModelImage(part, prepared.imageUploadPaths.getOrNull(idx), prepared.imageModelNotes.getOrNull(idx))
    }
    prepared.attachedFilesXml?.let { combinedParts.add(AgentContentPart.Text(it)) }

    if (combinedParts.isEmpty() && prepared.mediaRefPartsJson == null && prepared.attachedFilesXml == null) {
        Log.w(ChatViewModel.TAG, "📨[DRAIN] queued prompts materialized to empty content — leaving queue untouched")
        return false
    }

    // —— 消费确定：清队（按 id 精确移除，不吃并发新入队）+ 气泡翻转 + 落盘 ——
    //
    // [T-queue-dequeue-after-persist] 与 injectQueuedPromptsAsNewTurn 同一条纪律：
    // 提示词离开队列（气泡翻成「已发送」、摘录清空）必须发生在
    // `chatRepository.appendMessage` **返回之后**，中间不再有挂起点。旧顺序把清队
    // 放在落库之前，而落库是挂起调用：用户在这个窗口里点 Stop，协程在 appendMessage
    // 处被取消，队列已经是空的 —— cancelStream 随后查 _promptQueue 找不到东西，
    // resumeQueueAfterCancel 不会跑，这条消息既没发出去也没落库（它那条虚线「排队」
    // 气泡要等重进会话才无声消失）。cancelStream 的 T189 契约是「排队消息必须活过
    // Stop」，这正是它被违反的那条路径。按 id 精确移除（而非清空）保住并发新入队。
    // （上游 1.14 ChatViewModel.kt:8929-8936 / 8977-8995。）
    val queuedIds = queued.map { it.id }.toSet()

    val userText = combinedText.toString()
    // [T-android-paste-mediaref] Same marker handling as the mid-loop
    // inject path above — see the note there for why queued prompts
    // need it at all.
    val drainPaste = buildPastedParts(userText, sid)
    if (drainPaste != null) {
        _pastedTexts.value =
            _pastedTexts.value.filterNot { it.id in drainPaste.consumedIds }
    }
    val userPartsJson = buildUserPartsJson(
        userText,
        prepared.mediaRefPartsJson,
        prepared.attachedFilesXml,
        bodyPartsJson = drainPaste?.partsJson,
    )
    val persistedUser = chatRepository.appendMessage(sid, "user", userPartsJson)
    // [T-queue-dequeue-after-persist] 落库已返回：现在清队是确定的。
    _promptQueue.value = _promptQueue.value.filterNot { it.id in queuedIds }
    // [T-truncation-digest-clear][T-queue-digest-clear-after-consume] 清空点必须在
    // 「消费确定」之后：空内容早退与 prepare 异常都到不了这里，摘录因此不会在
    // 「一行都没注入」的尝试里被白抹掉。
    clearHistoryDigestState()
    _messages.value = _messages.value.map { m ->
        if (m.queuedPromptId != null && queuedIds.contains(m.queuedPromptId)) {
            m.copy(isQueued = false, queuedPromptId = null)
        } else m
    }
    // [T-queue-disk-persistence] drained prompts became real DB rows —
    // confirm their disk mirrors consumed (best-effort).
    runCatching {
        PromptQueueBridge.confirm(
            context.applicationContext,
            sid,
            queued.map { it.id },
        )
    }
    val queuedUiId = withContext(Dispatchers.Main.immediate) {
        _messages.value.firstOrNull { it.isQueued && it.content == userText }?.id
    }
    withContext(Dispatchers.Main.immediate) {
        notePersistedUiRow(queuedUiId, persistedUser.id)
    }

    appendBoundedHistory(LLMMessage(
        role = LLMMessage.Role.USER,
        content = drainPaste?.modelText ?: userText,
        imageParts = prepared.imageParts,
        contentParts = drainPaste?.let { p ->
            val bodyCount = combinedParts.takeWhile { it is AgentContentPart.Text }.size
            listOf(AgentContentPart.Text(p.modelText)) + combinedParts.drop(bodyCount)
        } ?: combinedParts,
        dbMessageId = persistedUser.id,
    ))
    return true
}
