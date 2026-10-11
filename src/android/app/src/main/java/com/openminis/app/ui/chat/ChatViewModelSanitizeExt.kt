package com.openminis.app.ui.chat

import android.util.Log
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.provider.ToolPairing

/**
 * Sanitize agentHistory before each API call to ensure tool_use/tool_result pairing.
 * Mirrors iOS AIChatViewModel pre-API validation.
 *
 * Ensures: every assistant message with tool_use is immediately followed by a user
 * message containing the matching tool_result(s). Handles:
 * - Duplicate tool IDs across messages (from provider fallback/retry)
 * - Orphaned tool_use without any tool_result
 * - Orphaned tool_result without matching tool_use
 * - Assistant text after tool_use in the same message (Anthropic rejects this)
 */
internal fun ChatViewModel.sanitizeAgentHistory() {
    // Walk through history sequentially, checking each assistant message.
    // For each assistant message with tool_use blocks, verify the NEXT message
    // is a user message with matching tool_result blocks. If not, inject them.
    var i = 0
    while (i < agentHistory.size) {
        val msg = agentHistory[i]
        if (msg.role != LLMMessage.Role.ASSISTANT) { i++; continue }

        val toolUses = msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
        if (toolUses.isEmpty()) { i++; continue }

        // [T-android-responses-tool-id-normalize] 配对身份走共享的
        // [ToolPairing.key]，不按原始字符串：Responses 路径把 call id 与
        // function-call id 合成 `"<call_id>|<fc_id>"` 存进历史，wire 上按 `|` 前
        // 的那一半配对。裸 compare 会把"合成 id 的 use + 裸 call id 的 result"
        // 判成未配对，于是给一次已经成功的调用补一条 interrupted 占位、并把真
        // result 当孤儿删掉（上游 1.14 在 ChatViewModel.kt:9862-9909 逐处用
        // ToolPairing.key；本移植这段拆进本文件，改的是同一件事）。
        val toolUseIds = toolUses.map { ToolPairing.key(it.id) }.toSet()

        // Check next message for matching tool_results
        val next = agentHistory.getOrNull(i + 1)
        val nextResultIds = next?.contentParts
            ?.filterIsInstance<AgentContentPart.ToolResult>()
            ?.map { ToolPairing.key(it.id) }?.toSet() ?: emptySet()

        val missingIds = toolUseIds - nextResultIds
        if (missingIds.isEmpty()) { i++; continue }

        // Some tool_uses have no matching tool_result in the next message.
        // If next message is a user message, add the missing results to it.
        // Otherwise, inject a new user message with placeholder results.
        val placeholders = toolUses.filter { ToolPairing.key(it.id) in missingIds }.map { use ->
            AgentContentPart.ToolResult(
                id = use.id, name = use.name,
                content = "Tool execution was interrupted by an unexpected error.",
                isError = true,
            )
        }
        Log.w(ChatViewModel.TAG, "sanitize: injecting ${placeholders.size} placeholder tool_result(s) after history[$i]")

        if (next != null && next.role == LLMMessage.Role.USER &&
            next.contentParts.any { it is AgentContentPart.ToolResult }) {
            // Append missing results to the existing user message
            agentHistory[i + 1] = next.copy(
                contentParts = next.contentParts + placeholders
            )
        } else {
            // Insert a new user message with just the placeholder results
            agentHistory.add(i + 1, LLMMessage(
                role = LLMMessage.Role.USER, content = "",
                contentParts = placeholders,
            ))
            trimAgentHistory()
        }
        i++
    }

    // Remove orphaned tool_results (result IDs not found in any tool_use)
    // [T-android-responses-tool-id-normalize] 与上面同一份身份 —— 这里漏改会
    // 把一次配对成功的 result 当孤儿删掉（同一现象的另一半）。
    val allToolUseIds = agentHistory.flatMap { it.contentParts }
        .filterIsInstance<AgentContentPart.ToolUse>().map { ToolPairing.key(it.id) }.toSet()
    val iter = agentHistory.listIterator()
    while (iter.hasNext()) {
        val msg = iter.next()
        if (msg.role != LLMMessage.Role.USER) continue
        val cleaned = msg.contentParts.filter { part ->
            part !is AgentContentPart.ToolResult || ToolPairing.key(part.id) in allToolUseIds
        }
        if (cleaned.isEmpty() && msg.content.isBlank()) {
            iter.remove()
        } else if (cleaned.size < msg.contentParts.size) {
            iter.set(msg.copy(contentParts = cleaned))
        }
    }
}
