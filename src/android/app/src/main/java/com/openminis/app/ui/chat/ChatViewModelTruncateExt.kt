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

    val deletedMessages = messages.subList(index, messages.size).toList()
    // [T-android-uimessages-sublist-cme] Defensive: without `.toList()` this
    // stores a live subList VIEW as `_messages.value`.
    //
    // Reproduced on device with this `.toList()` reverted (Pixel 4a): the
    // truncation ran (8 messages → 4) and a further message was sent, and it
    // did NOT crash — the next `+` copies the view into a plain ArrayList
    // before anything can invalidate it. So this line is hardening, not the
    // proven cause of the reported CME. See the long note on `uiMessages`.
    // (`deletedMessages` above already copies; this line did not.)
    val kept = messages.subList(0, index).toList()
    _messages.value = kept
    if (_streamingById.value.isNotEmpty()) {
        val keptIds = kept.mapTo(mutableSetOf()) { it.id }
        retainStreamFlushStates(keptIds)
        _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
    }
    revokeMemoryWritesInDeletedMessages(deletedMessages)

    val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId
    // Visible-user index of the *edited* message — count user turns
    // strictly before `index`, which is the 0-based ordinal of the
    // edited turn itself.
    val visibleUserIndex = messages.subList(0, index).count { it.role == "user" }
    val cutoffSortOrder = visibleUserCutoff(sid, visibleUserIndex)?.sortOrder ?: -1
    if (cutoffSortOrder >= 0) {
        chatRepository.deleteMessagesAfter(sid, cutoffSortOrder)
    }
    val remaining = awaitBoundedHistoryRebuild(sid)
    // [T-truncation-digest-clear] 编辑截断同样清 digest 摘录。
    clearHistoryDigestState()
    AppLogger.info(
        ChatViewModel.TAG_STREAM,
        "✏️ truncateBeforeEdit cutoffSortOrder=$cutoffSortOrder remaining=${remaining.size}"
    )
}
