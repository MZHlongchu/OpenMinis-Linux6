package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMUsage

@Deprecated("Use persistAssistantTurn(parts, ...) for per-turn delta persistence")
internal suspend fun ChatViewModel.persistAssistantMessage(
    text: String,
    usage: LLMUsage?,
    toolBlocks: List<AssistantBlock>? = null,
    toolCallInputs: Map<String, String> = emptyMap()
    ,
    reasoningContent: String? = null,
) {
    if (text.isEmpty() && (toolBlocks == null || toolBlocks.isEmpty())) return

    val partsJson = buildString {
        append("[")
        var first = true
        if (text.isNotEmpty()) {
            append("""{"type":"text","value":${escapeJson(text)}}""")
            first = false
        }
        toolBlocks?.forEach { block ->
            // Only persist real tool-use blocks. text / thinking / info blocks are
            // either represented via the `text` parameter (accumulatedText) or
            // reconstructed from thinking metadata; persisting them as `toolUse`
            // produces empty-name records that Anthropic rejects with
            // "messages.N.content.M.tool_use.name: String should have at least 1 character".
            if (block.kind != "tool_use") return@forEach
            if (block.toolName.isBlank()) return@forEach  // extra safety
            if (!first) append(",")
            first = false
            val inputJson = toolCallInputs[block.id]?.let { escapeJson(it) } ?: "\"\""
            val pUrl = block.browserURL ?: ""
            val iPath = block.imageFilePath ?: ""
            append("""{"type":"toolUse","value":{"toolUseId":${escapeJson(block.id)},"name":${escapeJson(block.toolName)},"input":$inputJson,"description":${escapeJson(block.toolTitle)},"pageURL":${escapeJson(pUrl)},"imageFilePath":${escapeJson(iPath)},"thoughtSignature":null}}""")
        }
        append("]")
    }
    val tokenJson = usage?.let {
        // [T-ctx-measure-outbound] 第二个 usage 行写入器也要带上 (report, estimate) 那一对
        // ——上游 1.14 ChatViewModel.kt:15031 同一处；少了它，从这条路径落库的会话重载
        // 时回放不出比值（ContextSizeMeter.calibrationSample 读的正是这几个键）。
        """{"inputTokens":${it.inputTokens},"outputTokens":${it.outputTokens},"cacheCreationTokens":${it.cacheCreationInputTokens ?: 0},"cacheReadTokens":${it.cacheReadInputTokens ?: 0},"latestContextTokens":${it.latestContextTokens}${calibrationJsonFields()}}"""
    }
    chatRepository.appendMessage(
        realSessionId.ifEmpty { sessionId }, "assistant", partsJson, tokenJson,
        reasoningContent = reasoningContent,
        modelSnapshot = currentModelSnapshot(),
    )
}
