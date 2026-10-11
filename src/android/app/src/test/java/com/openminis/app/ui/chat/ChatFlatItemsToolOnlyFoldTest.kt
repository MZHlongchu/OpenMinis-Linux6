package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the user report: with AI-process folding enabled, a turn
 * consisting ONLY of tool calls (no reply text) rendered a large blank band —
 * the standalone AssistantHeader row above an otherwise empty collapsed turn.
 * [T-process-run-card] The card now carries the turn, but the header rule
 * stays: a card-only turn hides the header row.
 */
class ChatFlatItemsToolOnlyFoldTest {

    private fun assistant(
        id: String = "m1",
        blocks: List<AssistantBlock>,
        role: String = "assistant",
    ) = ChatMessage(
        id = id,
        role = role,
        content = "",
        isStreaming = false,
        toolBlocks = blocks,
    )

    private fun tool(id: String, status: ToolBlockStatus = ToolBlockStatus.SUCCESS) = AssistantBlock(
        id = id,
        kind = "tool_use",
        content = "",
        toolName = "bash",
        toolTitle = "bash",
        toolStatus = status,
    )

    private fun build(message: ChatMessage) = buildFlatChatItems(
        listOf(message),
        foldAiProcess = true,
    )

    private fun kinds(items: List<FlatChatItem>): List<String> = items.map { item ->
        when (item) {
            is FlatChatItem.UserBubble -> "user"
            is FlatChatItem.AssistantHeader -> "header"
            is FlatChatItem.AssistantText -> "text"
            is FlatChatItem.AssistantMarkdownBlock -> "md"
            is FlatChatItem.ProcessRunCard -> "card"
            is FlatChatItem.AssistantThinking -> "thinking"
            is FlatChatItem.AssistantToolUse -> "tool:${item.block.toolName}"
            is FlatChatItem.AssistantInfo -> "info"
            is FlatChatItem.AssistantTyping -> "typing"
            is FlatChatItem.AssistantError -> "error"
            is FlatChatItem.AssistantLegacyContent -> "legacy"
            // [T-android-upstream-114-flat-item-variants] 上游 1.14 给密封类
            // FlatChatItem 补了三种卡片：排程卡 / 子代理回调卡 / 用量胶囊。
            // 本 when 是**表达式**（map 的返回类型是 List<String>），而密封类的
            // 子类是在别处（ChatFlatItems.kt）新增的——合并上游后这里就不再穷尽：
            // 缺失分支被推断成 Unit，于是同一条 when 先报 “must be exhaustive.
            // Add the 'is AgentCallbackCard', 'is AssistantUsage', 'is
            // ScheduledTaskCardItem' branches…”，紧接着报 “actual type is
            // kotlin.Unit, but kotlin.String was expected”。逐个子类显式列全，
            // 并各自用真实字段拼描述串（marker.taskId / callback.kind+jobId /
            // usage 计数）；刻意不用 else 兜底——将来再加子类仍会在编译期暴露，
            // 而不是被静默映射成一个永远匹配不上的占位串。
            is FlatChatItem.ScheduledTaskCardItem -> "scheduled:${item.marker.taskId}"
            is FlatChatItem.AgentCallbackCard -> "callback:${item.callback.kind.wire}:${item.callback.jobId}"
            is FlatChatItem.AssistantUsage -> "usage:${item.usage.inputTokens}/${item.usage.outputTokens}"
        }
    }

    @Test
    fun `collapsed tool-only turn renders card without header`() {
        val items = build(assistant(blocks = listOf(tool("t1"), tool("t2"))))
        val k = kinds(items)
        assertTrue("card expected, got $k", k.contains("card"))
        assertFalse("header must be hidden, got $k", k.contains("header"))
        assertFalse("tool cards must fold, got $k", k.any { it.startsWith("tool:") })
        assertEquals(listOf("t1", "t2"), items.filterIsInstance<FlatChatItem.ProcessRunCard>().first().blocks.map { it.id })
    }

    @Test
    fun `expanded turn keeps header hidden when it is still card-only`() {
        // Expanding the card shows the entries INSIDE the card; the turn
        // still has no other visible payload, so the header stays hidden.
        val items = buildFlatChatItems(
            listOf(assistant(blocks = listOf(tool("t1"), tool("t2")))),
            foldAiProcess = true,
            expandedProcessIds = setOf("m1"),
        )
        val k = kinds(items)
        assertFalse("header must stay hidden, got $k", k.contains("header"))
        assertTrue(k.contains("card"))
        assertTrue(items.filterIsInstance<FlatChatItem.ProcessRunCard>().first().expanded)
        assertFalse("tool rows stay inside the card, got $k", k.any { it.startsWith("tool:") })
    }

    @Test
    fun `turn with reply text keeps header when folded`() {
        val msg = assistant(
            blocks = listOf(tool("t1"), AssistantBlock(id = "x1", kind = "text", content = "done")),
        )
        val k = kinds(build(msg))
        assertTrue("header expected, got $k", k.contains("header"))
        assertTrue(k.contains("card"))
    }

    @Test
    fun `failed tool still flags card`() {
        val items = build(
            assistant(blocks = listOf(tool("t1", ToolBlockStatus.FAILED))),
        )
        assertTrue(items.filterIsInstance<FlatChatItem.ProcessRunCard>().first().hasFailure)
    }

    @Test
    fun `turn with error keeps header`() {
        val msg = assistant(blocks = listOf(tool("t1"))).copy(error = "boom")
        val k = kinds(build(msg))
        assertTrue("header expected with error, got $k", k.contains("header"))
        assertTrue(k.contains("card"))
    }
}
