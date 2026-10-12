package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * [T-truncation-critical-section] 会话历史行的**共享临界区**：一次「剪裁」
 * （[ChatViewModel.deleteFromMessage] / CODE 撤回）在飞期间，其它入口按角色交错：
 *
 *  - **写入者**——凡是会 append / 重发 user 行的路径（`sendMessage`、`retryLast`、
 *    [ChatViewModel.retryFromMessage]、[ChatViewModel.rerunFromToolBlock]、
 *    [ChatViewModel.resume] 的 `<system-reminder>` 行）：先
 *    [awaitIdle] 等剪裁收尾再动 DB（`sendMessage` 里是等价的 inline join：
 *    `truncationJob?.takeIf { it.isActive }?.join()`）。剪裁的
 *    `deleteMessagesAfter(sort_order >= cutoff)` 按行号删掉锚点之后的**一切**行，
 *    不等就会把自己刚落库的那一行一并删掉：气泡消失、agentHistory 里没有这一行，
 *    模型对着不在上下文里的问题作答（[T-delete-from-here-inflight] 的现场）。
 *    放弃一次用户明明点过的重试比等几百毫秒更糟，所以写入者选择**等待**。
 *  - **剪裁者**（[ChatViewModel.deleteFromMessage] / 撤回到此轮）：[cutterMayProceed]
 *    为 false 时**拒绝并留痕**（[admitCutter]）——两次交叠的剪裁会各自锚在对方半
 *    应用的中间态上（一次剪到一半的行号区间被另一次当成锚点）。
 *
 * 写入者等待是安全的、一次检查就够：所有写入口在调用 [awaitIdle] 之前都已经同步
 * 置位 `_isStreaming`，而剪裁入口一律以 `_isStreaming == false` 为前置条件 ——
 * 等待期间不可能冒出新的剪裁，因此不需要循环重试，也不会死锁（剪裁侧从不等待
 * streamJob / 写入者）。实现只用 [Job]：登记沿用
 * [ChatViewModel.truncationJob]（已完成的 Job 引用是惰性的，`isActive == false`），
 * 单测见 `TruncationInterlockTest`。
 *
 * [D8][T-truncation-critical-section] **登记时机**是这套协议的第二个不变量：剪裁者
 * 一律 `CoroutineStart.LAZY` + **先登记后 start**，登记因此早于本协程的任何挂起点。
 * 对 CODE 撤回尤其关键 —— 它的文件回滚（`RewindController.commit`）是数秒级的，
 * 登记若挪到回滚之后，整个窗口里第二个剪裁入口都拦不住，两次交叠的剪裁各自锚在
 * 对方半应用的 sort_order 区间上（行会丢）。收尾一律按 identity 摘除
 * （`if (truncationJob === coroutineContext[Job])`），别摘掉别人的剪裁。
 */
internal object TruncationInterlock {
    /** 剪裁者准入：临界区空闲（无在飞剪裁）才放行。 */
    fun cutterMayProceed(current: Job?): Boolean = current?.isActive != true

    /**
     * [T-truncation-critical-section] 剪裁者准入的**留痕**版本：拒绝是 fail-closed
     * 的正确行为（两次交叠剪裁会各锚在对方半应用的 sort_order 区间上，行会丢），
     * 但静默 `return` 让「点了没反应」既看不见也追不了因 —— 记下是哪个 [entry]
     * 被哪个在飞剪裁 [current] 挡下（Job.toString 带状态与身份，可与另一条入口区分）。
     */
    fun admitCutter(entry: String, current: Job?): Boolean {
        if (cutterMayProceed(current)) return true
        AppLogger.warning(
            ChatViewModel.TAG,
            "[T-truncation-critical-section] $entry refused: cut already in flight " +
                "(blocking truncationJob=$current); refusing is fail-closed, retry after it lands",
        )
        return false
    }

    /** 写入者准入：等在飞剪裁收尾；返回是否真的等过（无剪裁时立即返回 false）。 */
    suspend fun awaitIdle(current: Job?): Boolean {
        val inFlight = current?.takeIf { it.isActive } ?: return false
        inFlight.join()
        return true
    }
}

/**
 * [T-android-delete-from-here] Delete [messageId] and every message after
 * it, leaving the conversation as it stood immediately before that turn.
 * Mirrors iOS `6717c0ab0`.
 *
 * Deliberately close to — but not the same as — [retryFromMessage]. Both
 * rewind the conversation to a point, so this reuses that method's DB
 * anchoring: walk the persisted rows counting only user messages that have
 * VISIBLE text, because the agent loop also persists synthetic
 * `<system-reminder>` user rows that never rendered a bubble. Counting
 * those would anchor the cut one turn too early and silently take an extra
 * exchange with it (the iOS retry-anchor fix, ported here already).
 *
 * The one deliberate difference is the cut point: retry keeps the target
 * user message and re-sends it, so it cuts at `sortOrder + 1`. Delete From
 * Here removes the target too, so it cuts at `sortOrder`. Off-by-one in
 * either direction is silent and destructive — one strands the message the
 * user asked to delete, the other eats the preceding turn.
 *
 * No stream is started and `_isStreaming` is never claimed: this is a pure
 * truncation. It is still refused while a turn is in flight, because
 * deleting rows out from under a live agent loop would leave
 * [agentHistory] describing messages that no longer exist.
 *
 * [T-delete-from-here-inflight] The DB half runs asynchronously, so the job
 * is published as [ChatViewModel.truncationJob] for the whole critical
 * section. Without that handshake a send issued in the same window persisted
 * a user row with a HIGHER `sort_order` than the anchor, which the
 * truncation's `deleteMessagesAfter(sort_order >= cutoff)` then removed: the
 * bubble vanished and the model answered a question that was no longer in
 * context. Every other history writer now joins the same section via
 * [TruncationInterlock] (see its KDoc), and a second truncation is refused
 * while one is in flight (two overlapping cuts would each anchor on the
 * other's half-applied state).
 */
fun ChatViewModel.deleteFromMessage(messageId: String) {
    if (_isStreaming.value) return
    // [T-truncation-critical-section] admitCutter = 准入 + 被拒留痕（fail-closed，
    // 但绝不静默：日志里能看出是哪个入口被哪个在飞剪裁挡下）。
    if (!TruncationInterlock.admitCutter("deleteFromMessage", truncationJob)) return
    _canResume.value = false
    val messages = _messages.value
    val index = messages.indexOfFirst { it.id == messageId }
    if (index < 0) return

    // Snapshot what's about to go so any memory_write tool blocks inside
    // can be revoked — otherwise the on-disk daily log keeps entries from
    // messages the user just deleted.
    //
    // [T-android-bubble-anchor-drain] 「从此处删除」从一个合并排队批次的**第一个**
    // 气泡起切：批内气泡共享同一行，删掉那一行就必须把兄弟气泡一起带走，
    // 否则屏幕上会留下模型已经没有的正文。单个气泡时 groupSpan 就是它自己，
    // 与旧写法逐字等价。上游 1.14 ChatViewModel.kt:8130-8131。
    val cutFrom = BubbleRowLocator.groupSpan(messages, index).first
    val deletedMessages = messages.subList(cutFrom, messages.size).toList()

    // Truncate the UI to everything BEFORE the target message, and drop
    // any queued prompts that belonged to the deleted range so a later
    // auto-drain can't resurrect them.
    // [T-android-uimessages-sublist-cme] `subList` 返回的是与父表共享 modCount 的
    // 活视图；下面要把它发布成 `_messages.value`（= Compose 状态），所以必须拷贝。
    // 与 truncateBeforeEdit / 上游 uiMessages 尾部窗口同一条纪律。
    val retainedHead = messages.subList(0, cutFrom).toList()
    for (m in deletedMessages) {
        // [T-queue-disk-persistence] forgetQueuedPrompt 连带撤掉磁盘镜像，
        // 否则重启会给这批已删除的消息还原出幽灵排队气泡。
        m.queuedPromptId?.let { pid -> forgetQueuedPrompt(pid) }
    }
    _messages.value = retainedHead

    // Scrub stream deltas pointing at truncated messages so they can't
    // resurface into a row that no longer exists.
    val keptIds = retainedHead.mapTo(mutableSetOf()) { it.id }
    retainStreamFlushStates(keptIds)
    if (_streamingById.value.isNotEmpty()) {
        _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
    }

    revokeMemoryWritesInDeletedMessages(deletedMessages)

    val sid = activeSessionId ?: return
    // [T-delete-from-here-inflight] LAZY + 先登记后 start：`truncationJob` 在临界区
    // 开始之前就一定可见，并发 sendMessage 的 join 不会漏掉这次截断。
    // [T-truncation-critical-section] CoroutineName 只为诊断：admitCutter 的拒绝日志
    // 打的是 Job.toString，带上名字就能一眼看出挡路的是「从此处删除」还是 CODE 回滚。
    // （只加元素、不动 dispatcher：本协程原先就跑在 viewModelScope 默认的 Main.immediate 上。）
    val job = viewModelScope.launch(
        CoroutineName("truncation:delete-from-here"),
        start = CoroutineStart.LAZY,
    ) {
        try {
            val target = messages[index]
            // [T-retry-cutoff-row-anchor] 截断锚点 = 目标消息**自身的 DB 行**（用户
            // 消息与 DB 行 1:1，id 全局唯一）。旧锚点用「UI 第 N 个用户消息」序号去
            // DB 数「有可见文本的用户行」——纯图片/无文本头的用户消息让两侧序号
            // 错位，visibleUserCutoff 返回 null/-1 → 不删任何行 → 下一轮从 DB 重建
            // 出完整旧历史，模型接到的就是旧上下文。sortOrderOf 直接命中，永不错位。
            val targetRowSort = runCatching { chatRepository.dao.sortOrderOf(messageId) }.getOrNull()
            val cutoffSortOrder = when {
                targetRowSort != null -> targetRowSort // delete removes the target too
                else -> resolveDeleteCutoffSortOrder(sid, messages, index, target)
            }
            if (cutoffSortOrder >= 0) {
                chatRepository.deleteMessagesAfter(sid, cutoffSortOrder)
            }

            // Rebuild agentHistory from what survived, so the next turn is
            // built on the truncated conversation rather than a stale list.
            val tail = awaitBoundedHistoryRebuild(sid)
            // [T-truncation-digest-clear] digest 摘录里是被裁掉行（含刚删除的轮次）
            // 的痕迹，不清空会继续注入已删上下文。
            clearHistoryDigestState()
            reconcileCompactMarkerAfterTruncation()
            // Refresh the session's last-message preview; otherwise the
            // session list keeps quoting a message that no longer exists.
            // An empty remainder clears it rather than leaving the stale text.
            runCatching {
                chatRepository.updateSessionPreview(sid, tail.lastOrNull()?.partsJson ?: "[]")
            }
            AppLogger.info(
                ChatViewModel.TAG,
                "deleteFromMessage: cut at sortOrder=$cutoffSortOrder, " +
                    "${deletedMessages.size} message(s) removed, ${tail.size} remain",
            )
        } finally {
            // [T-delete-from-here-inflight] 门必须复位（异常/取消也一样），否则
            // 后续的 deleteFromMessage 会被永久拒绝。identity 守卫与 streamJob
            // 同口径：只清自己那一份。
            if (truncationJob === coroutineContext[Job]) truncationJob = null
        }
    }
    truncationJob = job
    job.start()
}

/**
 * [T-p1-context-marker-reconcile] 长按截断类操作（重试 / 从此处删除 / 撤回到
 * 此轮）之后的压缩标记对账。
 *
 * 三种操作都把历史截断到某个点，但**压缩标记不随之对账**：标记的摘要描述的是
 * 被截断掉的轮次（「已完成了 X/Y/Z」），而那些轮次刚刚被用户删除/回滚——
 * effectiveAgentHistory 的投影仍然把摘要注入模型上下文，模型以为做了已经不
 * 存在的工作 → 「接入模型的上下文与实际上下文不对」。
 *
 * [T-truncation-marker-partial] 判定规则（v2 语义：摘要覆盖
 * [会话起点 .. lastCompactedMessageId]，见 CompactMarkerEntity）：
 *  - v2 标记（lastCompactedMessageId 非空）：**以 DB 为准**——该行仍在 → 摘要
 *    覆盖的区间完整幸存 → 保留；该行已不在（被本次截断删除）→ 摘要描述的
 *    轮次已不存在 → 丢弃。旧的 anyAnchorAlive 判定有洞：boundary 幸存而
 *    lastCompacted 被删时（删除点落在摘要区间中段），摘要仍在注入已删内容。
 *  - v1 / 无 id 锚（anchors 为空）：截断后无法核验摘要范围 → 保守丢弃
 *    （自动压缩会在需要时重建标记）。
 *  - 其余（id 锚齐全且 lastCompacted 幸存）：标记有效保留。detached-anchor
 *    的 _compactMarkerDetached 是窗口外不是不存在，不在此列。
 */
internal suspend fun ChatViewModel.reconcileCompactMarkerAfterTruncation() {
    val marker = _cachedLatestMarker ?: return
    val lastCompacted = marker.lastCompactedMessageId
    if (lastCompacted != null) {
        val survived = runCatching {
            chatRepository.dao.sortOrderOf(lastCompacted)
        }.getOrNull() != null
        if (survived) return
        val sid = realSessionId.ifEmpty { sessionId }
        AppLogger.warning(
            ChatViewModel.TAG,
            "[Compact] truncation removed lastCompacted anchor $lastCompacted — " +
                "dropping compact state; summary described turns that no longer exist",
        )
        runCatching { chatRepository.dao.deleteCompactMarker(marker.id) }
        _cachedLatestMarker = null
        _compactSummary.value = null
        // UI 分隔行（compact 系统行）随标记消失；真实消息不动。
        _messages.value = _messages.value.filterNot { msg ->
            msg.role == "system" && msg.toolBlocks.firstOrNull()?.toolName == "compact"
        }
        return
    }
    val history = agentHistory.toList()
    val anchors = listOfNotNull(
        marker.boundaryMessageId,
        marker.firstKeptMessageId,
        marker.lastCompactedMessageId,
    )
    if (anchors.isNotEmpty()) {
        val anchorIds = anchors.toSet()
        val anyAnchorAlive = history.any { it.dbMessageId != null && it.dbMessageId in anchorIds }
        if (anyAnchorAlive) return
    }
    // v1 / 无 id 锚：截断后无法核验摘要覆盖范围，保守丢弃。
    val sid = realSessionId.ifEmpty { sessionId }
    AppLogger.warning(
        ChatViewModel.TAG,
        "[Compact] truncation with a legacy/no-anchor marker — dropping compact state " +
            "(cannot verify the summarized range still exists)",
    )
    runCatching { chatRepository.dao.deleteCompactMarker(marker.id) }
    _cachedLatestMarker = null
    _compactSummary.value = null
    _messages.value = _messages.value.filterNot { msg ->
        msg.role == "system" && msg.toolBlocks.firstOrNull()?.toolName == "compact"
    }
}
