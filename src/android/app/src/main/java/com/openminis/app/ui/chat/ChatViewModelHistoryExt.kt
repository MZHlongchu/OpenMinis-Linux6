package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger

/**
 * [T-android-compact-orphan-toolcall] Last line of defence before a history
 * slice becomes a provider request: every ToolResult (function_call_output)
 * must have a matching ToolUse (function_call) in the same slice, and vice
 * versa.
 *
 * An unmatched pair is a hard 400 on OpenAI-compatible APIs —
 *     No tool call found for function call output with call_id …
 * — and because the slice is recomputed deterministically, it repeats on
 * every retry AND every fallback model, wedging the conversation until the
 * user clears the session. Port of iOS `dropOrphanedToolParts`
 * (AIChatViewModel+Persistence.swift, c7f6a299e).
 *
 * Any orphan reaching here is an upstream bug (the walk-back boundary is
 * supposed to preserve pairing), so this logs loudly rather than silently
 * papering over it:
 *   - orphaned result → drop it; its call is gone from the slice and
 *     nothing can reconstruct it.
 *   - orphaned call → synthesise an error result rather than deleting the
 *     call, because deleting would silently discard the assistant's own
 *     reasoning. The placeholder keeps the turn intact and tells the model
 *     that round failed.
 * Messages emptied by the drop are removed — a parts-less message is itself
 * invalid on several providers.
 */
internal fun dropOrphanedToolParts(history: List<LLMMessage>): List<LLMMessage> {
    val toolUseIds = HashSet<String>()
    val toolResultIds = HashSet<String>()
    for (msg in history) {
        for (part in msg.contentParts) {
            when (part) {
                is AgentContentPart.ToolUse -> toolUseIds.add(part.id)
                is AgentContentPart.ToolResult -> toolResultIds.add(part.id)
                else -> {}
            }
        }
    }
    val orphanedResults = toolResultIds - toolUseIds
    val orphanedUses = HashSet(toolUseIds - toolResultIds)

    // [T-android-compact-orphan-toolcall] IN-FLIGHT EXEMPTION (iOS
    // 5d346dc2e). The tool_uses in the FINAL assistant message are not
    // orphans while the loop sits between "model asked for tools" and
    // "results appended" — agentHistory legitimately looks unpaired for
    // that whole window (the assistant turn is appended at ~7500 and its
    // tool results only at ~7517). Any snapshot taken inside that gap would
    // otherwise carry fabricated "interrupted" results for tools that were
    // about to run normally, telling the model its tools had failed.
    // Trailing unanswered calls need no repair anyway: a request ending on
    // an assistant tool_use is exactly what the API expects mid-round.
    val last = history.lastOrNull()
    if (last != null && last.role == LLMMessage.Role.ASSISTANT) {
        for (part in last.contentParts) {
            if (part is AgentContentPart.ToolUse) orphanedUses.remove(part.id)
        }
    }

    if (orphanedResults.isEmpty() && orphanedUses.isEmpty()) return history

    AppLogger.warning(
        ChatViewModel.TAG,
        "[CompactDiag] orphan tool parts in OUTGOING history — repairing. " +
            "orphanedOutputs=${orphanedResults.size} [${orphanedResults.sorted().take(3).joinToString(",")}] " +
            "orphanedCalls=${orphanedUses.size} [${orphanedUses.sorted().take(3).joinToString(",")}] " +
            "historyCount=${history.size}",
    )

    val cleaned = ArrayList<LLMMessage>(history.size)
    for (msg in history) {
        val kept = msg.contentParts.filter { part ->
            if (part is AgentContentPart.ToolResult) !orphanedResults.contains(part.id) else true
        }
        // Only drop the message when it HAD parts and lost them all. A
        // plain text message legitimately carries no contentParts and must
        // survive untouched.
        if (kept.isEmpty() && msg.contentParts.isNotEmpty()) continue
        cleaned.add(if (kept.size == msg.contentParts.size) msg else msg.copy(contentParts = kept))

        // Follow an assistant turn holding orphaned calls with the
        // placeholder results it never got, so the pair is complete.
        if (msg.role != LLMMessage.Role.ASSISTANT) continue
        val unanswered = kept.filterIsInstance<AgentContentPart.ToolUse>()
            .filter { orphanedUses.contains(it.id) }
        if (unanswered.isNotEmpty()) {
            cleaned.add(
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = "",
                    contentParts = unanswered.map {
                        AgentContentPart.ToolResult(
                            id = it.id,
                            name = it.name,
                            content = "Tool execution was interrupted by an unexpected error.",
                            isError = true,
                        )
                    },
                ),
            )
        }
    }
    return cleaned
}

