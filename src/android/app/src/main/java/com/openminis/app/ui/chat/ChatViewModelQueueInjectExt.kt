package com.openminis.app.ui.chat

import com.openminis.app.logging.AppLogger
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.queue.PromptQueueBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val BRIDGE_TEXT =
    "(Interrupted mid-task by a new user message. Decide based on the new message and overall context " +
        "whether the prior task should continue — do not forget or abandon it unless the user explicitly " +
        "says to stop, or the new message makes clear it is no longer needed. Unfinished items live on the " +
        "agent_plan task list — after addressing the user's message, resume the pending items from the board " +
        "instead of restarting the plan.)"

internal suspend fun ChatViewModel.injectQueuedPromptsAsNewTurn(
    finishedAssistantId: String,
    finishedAccumulatedText: String,
    finishedAllToolBlocks: List<AssistantBlock>,
    /**
     * [T-queue-pre-dispatch-persist] pre-dispatch（工具执行前）注入点的落库钩子。
     * 本函数所有早退判定通过后、append bridge **之前**调用：调用方在此补落本回合
     * 的 assistant(tool_use) 行与 interrupted 的 tool_result 行，并返回后者的
     * history 消息（由本函数 append）。post-result 边界的注入点已自行落库，传 null。
     *
     * 为什么必须由本函数调度：wire/DB 顺序要求
     * `assistant(tool_use) → user(toolResult) → assistant(bridge) → user(queued)`。
     * 旧代码把 assistant 行整轮漏落、tool_result 又晚于 bridge 与 queued user 追加，
     * 于是 sanitize 在倒置历史上插占位结果、真结果被当重复丢弃，重载时孤儿剥离
     * 又把整条消息摘掉——用户屏幕上那一轮正文与工具卡永久消失。
     *
     * [T-queue-inject-atomic] 契约（提交点语义）：钩子是本函数**最后一次可以失败**的
     * 操作，而且它必须自己先构造后落库——落库失败即一行未落，此时抛出等价于
     * 「本次注入什么都没做」，调用方照常执行本回合的真工具（零副作用）。提交点
     * 之后的任何失败都在本函数内吸收并显式记录，不再上抛：调用方一旦把异常当成
     * 「没注入」去跑真工具，就会在 post-result 边界再落一条 assistant + 真
     * tool_result，库与 wire 同时出现重复/不一致。
     */
    commitPendingToolResult: (suspend () -> LLMMessage)? = null,
): ChatViewModel.InjectedTurn? {
    // [T-queue-shelved] 搁置消息不参与自动注入；只注入 pending 态。
    val queued = _promptQueue.value.filter { !it.shelved }
    if (queued.isEmpty()) return null

    // [T-android-queued-message-duplicated-on-inject] REMOVE the queued
    // placeholder bubbles (the ones enqueuePrompt added with
    // id="queued_msg_…") for the prompts we're injecting. Step (c) below
    // appends a single combined user bubble (id=userEntity.id) for the same
    // text — so flipping isQueued=false and KEEPING the placeholders (the
    // old behaviour) rendered the message TWICE: once as the un-queued
    // placeholder, once as the injected bubble. drainQueuedPrompts reuses
    // its placeholders and never re-appends, so it didn't dupe; this mid-
    // loop inject path appends a fresh bubble, so the placeholders must go.
    val queuedIds = queued.map { it.id }.toSet()
    val msgsAfterUnqueue = _messages.value.filterNot { m ->
        m.queuedPromptId != null && queuedIds.contains(m.queuedPromptId)
    }

    // Build the combined user message from all queued prompts.
    val sid = ensureSession()
    val combinedAttachments = queued.flatMap { it.attachments }
    val prepared = prepareUserAttachments(combinedAttachments, sid)

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
        val path = prepared.imageUploadPaths.getOrNull(idx)
        if (path != null) combinedParts.add(AgentContentPart.Text("[attached image: $path]"))
        combinedParts.add(AgentContentPart.ImageData(part.data, part.mimeType, linuxPath = path, noVisionPlaceholder = visionPlaceholderFor(path)))
    }
    prepared.attachedFilesXml?.let { combinedParts.add(AgentContentPart.Text(it)) }

    // Guard: every queued prompt produced no content (no text, no
    // image). An empty user msg is a 400 from every provider. Skip —
    // the caller falls through to a normal next-turn dispatch so the
    // loop doesn't spin.
    //
    // [T-queue-inject-ghost-bubble] Return WITHOUT having touched the
    // queue. The old code cleared _promptQueue up front, so this early
    // return (and any throw before the removal below) dropped the queued
    // prompts from memory while their placeholder bubbles stayed in the
    // UI and their disk mirrors stayed pending — a ghost bubble,
    // resurrected again on next boot. The queue removal now happens only
    // once consumption is certain (appendMessage below succeeded), so
    // every path above this line leaves queue, bubbles and mirrors in
    // step. The prompts stay queued: a transient prepare failure (file
    // read hiccup) gets another chance at the next tool boundary, or at
    // the post-loop drain.
    if (combinedParts.isEmpty()) {
        AppLogger.warning(
            ChatViewModel.TAG_STREAM,
            "injectQueuedPromptsAsNewTurn: ${queued.size} queued prompt(s) produced no content, skipping",
        )
        return null
    }

    // [T-queue-inject-atomic] ── 构造阶段（零副作用）──────────────────────────
    // 所有可能抛异常的构造都排到第一次落库之前：粘贴展开、落库用 JSON、下一轮
    // 气泡 id。旧实现把 buildPastedParts/buildUserPartsJson 放在钩子**之后**，
    // 于是钩子已经落了 assistant(tool_use) + interrupted tool_result 两行之后，
    // 构造失败仍能上抛给调用方 → handled=null → 真工具照跑 → 再落一条
    // assistant + 真 tool_result（本回归的重复源）。
    val userText = combinedText.toString()
    // [T-android-paste-mediaref] Queued prompts carry markers too.
    //
    // sendMessage hands off to enqueuePrompt whenever a turn is already
    // streaming, and it no longer expands markers before doing so — so
    // without this the queued row would persist a literal `[Pasted#3]` and
    // the model would receive the marker instead of the text.
    val queuedPaste = buildPastedParts(userText, sid)
    val userPartsJson = buildUserPartsJson(
        userText,
        prepared.mediaRefPartsJson,
        prepared.attachedFilesXml,
        bodyPartsJson = queuedPaste?.partsJson,
    )
    val newAssistantId = "assistant_${System.currentTimeMillis()}"

    // [T-queue-inject-atomic] ── 提交点 ──────────────────────────────────────
    // 工具结果必须先于 bridge 进 agentHistory——tail 变成 user(toolResult) 之后
    // bridge 才是合法的 assistant 间隔，否则 sanitize 会在 assistant(tool_use)
    // 后插占位结果。这一步（钩子）抛出 = 一行都没落= 本次注入未发生，调用方照常
    // 执行真工具是安全的（见上面契约）。
    commitPendingToolResult?.invoke()?.let { conversationPort().append(it) }

    // Bridge entry into agentHistory ONLY (not persisted). The tail
    // before this call is user(tool_result); without the bridge the
    // queued user message becomes two consecutive user roles and the
    // provider merges them — exactly the regression iOS hit at #579.
    // Empty/whitespace-only bridge text would itself be merged out by
    // some sanitizers; keep a small visible string for parity with iOS.
    //
    // [T-plan-board-prompt] 被打断的计划有持久恢复点：任务列表看板
    // （agent_plan）每轮注入系统提示——bridge 指向它，模型处理完新消息后
    // 按看板续跑未完成项，而不是把剩余计划丢在脑内（旧 bridge 只靠模型
    // 记住被打断前的进度）。
    val bridgeMessage = LLMMessage(
        role = LLMMessage.Role.ASSISTANT,
        content = BRIDGE_TEXT,
        contentParts = listOf(AgentContentPart.Text(BRIDGE_TEXT)),
    )
    appendBoundedHistory(bridgeMessage)

    // Persist the queued user message as its own DB row + append to
    // agentHistory so the next API call carries it.
    //
    // [T-queue-inject-atomic] 这一行落库失败**不上抛**（此刻 assistant 行已在库，
    // 上抛就等于把「已提交」谎报成「没注入」）。失败时的状态规则，逐条对齐
    // 「不静默丢、不重复落」：
    //   - 队列保持不动（提示气泡也留在 UI 上），与磁盘镜像一致 —— 会在下一个边界
    //     或 loop 末尾 drain 重试，用户那条消息最终**只送达一次**；
    //   - 本回合不把这条 user 消息放进 wire（否则重试时会再说一遍），桥接消息也
    //     一并摘掉：没有随后的 user 轮，它只会让模型对着「用户发了新消息」却看不到
    //     消息空转一轮；
    //   - digest 不清空（[T-queue-digest-clear-after-consume]：消费未确定就不动它）。
    val userEntity: MessageEntity? = try {
        chatRepository.appendMessage(sid, "user", userPartsJson)
    } catch (e: CancellationException) {
        // 取消不是落库失败：吞掉它会把「已提交的 assistant 行」和「调用方以为的
        // 没注入」扯成两半（真工具在已取消的协程里根本没有执行意义）。
        throw e
    } catch (e: Exception) {
        AppLogger.error(
            ChatViewModel.TAG_STREAM,
            "injectQueuedPromptsAsNewTurn: 排队消息落库失败，保留队列待下个边界重试（本回合不注入正文）: ${e.message}",
        )
        null
    }
    if (userEntity == null) {
        if (agentHistory.lastOrNull() === bridgeMessage) {
            agentHistory.removeAt(agentHistory.lastIndex)
        }
    } else {
        // [T-queue-inject-ghost-bubble] Consumption is now certain: the queued
        // prompts are real DB rows. Remove exactly the snapshot items — anything
        // enqueued while we were preparing/persisting stays queued. (The old
        // up-front clear-all could eat a concurrently enqueued prompt between
        // the snapshot and the wipe; the targeted removal can't.)
        _promptQueue.value = _promptQueue.value.filterNot { it.id in queuedIds }
        // 粘贴表消费同样推迟到「落库确定」之后：旧位置在 appendMessage 之前，
        // 落库失败就等于把用户粘贴的正文吞掉（还留在输入框里的那些找不到对应记录）。
        queuedPaste?.let { paste ->
            _pastedTexts.value = _pastedTexts.value.filterNot { it.id in paste.consumedIds }
        }
        // [T-queue-disk-persistence] the queued prompts are now real DB rows —
        // confirm their disk mirrors consumed. Failure leaves a ghost record
        // restored on next boot; harmless (duplicate bubble), self-healing via
        // removeQueuedPrompt. Best-effort, never blocks the loop.
        runCatching {
            PromptQueueBridge.confirm(
                context.applicationContext,
                sid,
                queuedIds.toList(),
            )
        }
        appendBoundedHistory(
            LLMMessage(
                role = LLMMessage.Role.USER,
                // Expanded for the model; the persisted row above stays small.
                content = queuedPaste?.modelText ?: userText,
                imageParts = prepared.imageParts,
                contentParts = queuedPaste?.let { p ->
                    // Replace the whole LEADING RUN of text parts with the one
                    // expanded body, then keep everything after it.
                    //
                    // Not "index 0": each queued prompt contributes its own text
                    // part, and combinedText joined them with blank lines —
                    // p.modelText is the expansion of that join, so it stands
                    // for all of them. The image and <user-attached-files> parts
                    // that follow must survive untouched.
                    val bodyCount = combinedParts.takeWhile { it is AgentContentPart.Text }.size
                    listOf(AgentContentPart.Text(p.modelText)) + combinedParts.drop(bodyCount)
                } ?: combinedParts,
                dbMessageId = userEntity.id,
            ),
        )
    }

    // Finalize the just-finished assistant bubble in the UI on Main:
    // (a) un-queue the queued chat bubbles, (b) flush the side-channel
    // delta into the canonical row and clear isStreaming /
    // isAwaitingModelResponse, then (c) append the freshly-created
    // queued user ChatMessage + a NEW empty assistant placeholder so
    // the next iteration's streaming writes target the new bubble.
    //
    // [T-queue-inject-atomic] 这一段同样不再上抛（提交点契约见函数头）：库里的
    // assistant / interrupted tool_result / queued user 行都已提交，上抛只会让调用
    // 方以为「没注入」再跑一遍真工具。UI 这次没接上，下一轮 updateAssistantMessage
    // 或重载会按库里的行校准。取消例外——取消不是失败，原样上抛。
    try {
        withContext(Dispatchers.Main) {
            // (a) + (b) one emit: build the post-finalize list.
            // 落库失败时保留队列占位气泡（与 _promptQueue / 磁盘镜像一致）。
            if (userEntity != null) {
                _messages.value = msgsAfterUnqueue
            }
            updateAssistantMessage(
                finishedAssistantId,
                finishedAccumulatedText,
                false,
                finishedAllToolBlocks,
                isAwaitingModelResponse = false,
            )
            // (c) — append the queued user bubble + the new assistant
            // placeholder. Mirrors sendMessage's user-bubble append shape so
            // attachments / images / file chips render the same.
            val nextAssistantMsg = ChatMessage(
                id = newAssistantId,
                role = "assistant",
                content = "",
                isStreaming = true,
                isAwaitingModelResponse = true,
                thinkingLevel = _thinkingLevel.value,
            )
            if (userEntity != null) {
                val queuedUserMsg = ChatMessage(
                    id = userEntity.id,
                    role = "user",
                    content = userText,
                    imageUris = prepared.imageUris,
                    attachmentNames = prepared.attachmentNames,
                    attachmentUris = prepared.nonImageUris,
                    sourceDbIds = listOf(userEntity.id),
                )
                _messages.value = trimLoadedWindow(_messages.value + queuedUserMsg + nextAssistantMsg)
                notePersistedUiRow(userEntity.id, userEntity.id)
            } else {
                _messages.value = trimLoadedWindow(_messages.value + nextAssistantMsg)
            }
            // Note: ChatScreen's `lastUserAppendMs` (the trailing-row
            // ScrollPin send-grace window) is updated reactively by
            // ChatScreen's `LaunchedEffect(messages.size)` user-send hook
            // when messages.size grows — appending the queuedUserMsg above
            // bumps the size, so the pin window opens just like a normal
            // send. No direct write needed from here (and we couldn't —
            // `lastUserAppendMs` lives in ChatScreen's composition scope).
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLogger.error(
            ChatViewModel.TAG_STREAM,
            "injectQueuedPromptsAsNewTurn: UI 收尾失败（数据已提交，不回退、不上抛）: ${e.message}",
        )
    }

    if (userEntity != null) {
        // [T-truncation-digest-clear] 打断 + 注入 = 上下文重置点：digest 摘录是
        // 有界窗口此前裁掉行的痕迹（可能含已被用户删除/放弃的轮次），不清空
        // 会随本轮请求继续注入——模型接到的仍是旧上下文（用户实测）。
        //
        // [T-queue-digest-clear-after-consume] 清空点必须在「消费确定」之后：早退
        // （未注入任何东西）、钩子抛出、appendMessage 失败都不清。旧位置在早退
        // 之前，等于一次没消费的尝试就把窗口外摘录一并抹掉——那些行不在
        // agentHistory 里、重建窗口也装不回来（rebuildAgentHistoryFromDatabase
        // 只装有界尾巴），模型从此永久丢掉这段上下文痕迹。与同一处
        // [T-queue-inject-ghost-bubble] 立下的「消费确定后才动状态」纪律对齐。
        //
        // 放在最后：它之后只剩日志与返回值，没有可失败的步骤。
        clearHistoryDigestState()
    }

    AppLogger.info(
        ChatViewModel.TAG_STREAM,
        "injectQueuedPromptsAsNewTurn: injected ${queued.size} queued prompt(s) as new turn, " +
            "finishedId=$finishedAssistantId newId=$newAssistantId userRow=${userEntity?.id ?: "none"}",
    )
    return ChatViewModel.InjectedTurn(newAssistantId)
}
