package com.openminis.app.ui.chat

import com.openminis.app.logging.AppLogger

/**
 * T187: drop the message at [messageId] *and* every later message
 * (in UI, in agentHistory, and on disk) so the new sendMessage()
 * call below this can persist the edited text as a fresh user
 * turn at the same position. Reuses the cutoff-search machinery
 * from retryFromMessage but offsets by `entity.sortOrder` (not
 * +1) — retry preserves the original turn, edit replaces it.
 */
internal suspend fun ChatViewModel.truncateBeforeEdit(messageId: String) {
    val messages = _messages.value
    val index = messages.indexOfFirst { it.id == messageId }
    if (index < 0) return

    // [T-android-bubble-anchor-drain] Remove from the FIRST bubble of a merged
    // queue batch: the edit removes the batch's shared row, so its sibling
    // bubbles must go with it. 单个气泡时与旧写法逐字等价。上游 1.14
    // ChatViewModel.kt:8515-8516。
    val cutFrom = BubbleRowLocator.groupSpan(messages, index).first
    val deletedMessages = messages.subList(cutFrom, messages.size).toList()
    // [T-android-uimessages-sublist-cme] Defensive: without `.toList()` this
    // stores a live subList VIEW as `_messages.value`.
    //
    // Reproduced on device with this `.toList()` reverted (Pixel 4a): the
    // truncation ran (8 messages → 4) and a further message was sent, and it
    // did NOT crash — the next `+` copies the view into a plain ArrayList
    // before anything can invalidate it. So this line is hardening, not the
    // proven cause of the reported CME. See the long note on `uiMessages`.
    // (`deletedMessages` above already copies; this line did not.)
    val kept = messages.subList(0, cutFrom).toList()
    _messages.value = kept
    if (_streamingById.value.isNotEmpty()) {
        val keptIds = kept.mapTo(mutableSetOf()) { it.id }
        retainStreamFlushStates(keptIds)
        _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
    }
    revokeMemoryWritesInDeletedMessages(deletedMessages)

    val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId
    // [T-android-bubble-anchor] Edit = delete from here + an ordinary send, so the
    // edited row itself goes: cut AT it. Found by id through [BubbleRowLocator],
    // not by counting visible user rows — counting could run off the end (an
    // image-only turn earlier in the chat is a bubble with no text part), leave
    // the pre-edit row in place, and send the edited text after it, so the model
    // still read the old message. 上游 1.14 ChatViewModel.kt:8542-8547。
    val located = BubbleRowLocator.locateUserRow(messages, index, chatRepository.loadMessages(sid))
    logBubbleAnchor("edit", messageId, located)
    val bubbleCutoff = BubbleRowLocator.cutoffFor(located, keepBubble = false)
    // Visible-user index of the *edited* message — count user turns
    // strictly before `index`, which is the 0-based ordinal of the
    // edited turn itself. 只有气泡没有链接行（未落库的排队占位）时才走这条回退。
    val visibleUserIndex = messages.subList(0, index).count { it.role == "user" }
    val cutoffSortOrder = if (bubbleCutoff >= 0) {
        bubbleCutoff
    } else {
        visibleUserCutoff(sid, visibleUserIndex)?.sortOrder ?: -1
    }
    if (cutoffSortOrder >= 0) {
        chatRepository.deleteMessagesAfter(sid, cutoffSortOrder)
    }
    val remaining = awaitBoundedHistoryRebuild(sid)
    // [T-truncation-digest-clear] 编辑截断同样清 digest 摘录。
    clearHistoryDigestState()
    // [T-b1-truncate-compact-reconcile] 编辑点落在摘要区间内时，锚行随
    // deleteMessagesAfter 一起被删，但压缩标记还挂着 → 摘要继续对模型宣称
    // 已完成那些刚被删掉的轮次。与 deleteFromMessage / 撤回共用同一收尾序列
    // （rebuild → clearHistoryDigestState → reconcile）。
    reconcileCompactMarkerAfterTruncation()
    AppLogger.info(
        ChatViewModel.TAG_STREAM,
        "✏️ truncateBeforeEdit cutoffSortOrder=$cutoffSortOrder remaining=${remaining.size}"
    )
}
