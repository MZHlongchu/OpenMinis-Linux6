package com.openminis.app.harness.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-loop-context-cut] 上下文治理投影验收：三路切片、回走边界、
 * preAnchor 剪枝、角色对齐、摘要内联、配对修复。
 */
class CompactHistoryProjectorTest {

    private fun user(id: String? = null, text: String = "u") =
        LLMMessage(role = LLMMessage.Role.USER, content = text, dbMessageId = id)

    private fun assistant(id: String? = null, vararg parts: AgentContentPart) =
        LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "", contentParts = parts.toList(), dbMessageId = id)

    private fun toolUse(id: String) = AgentContentPart.ToolUse(id, "shell_execute", org.json.JSONObject("""{"command":"ls"}"""))
    private fun toolResult(id: String, content: String = "ok") =
        AgentContentPart.ToolResult(id, "shell_execute", content, isError = false)

    @Test
    fun `no summary or marker returns full history untouched`() {
        val history = listOf(user("1"), assistant("2"))
        assertTrue(CompactHistoryProjector.project(history, null, null, 60, 3) === history ||
            CompactHistoryProjector.project(history, null, null, 60, 3) == history)
        assertTrue(CompactHistoryProjector.project(history, "s", null, 60, 3) == history)
    }

    @Test
    fun `v2 anchor slices preanchor walkback and injects summary into first post-anchor user`() {
        // h0..h4 pre-anchor, h4 = anchor(被压缩的末条), h5 assistant, h6 user(新指令)
        val h = listOf(
            user("m0", "旧指令0"),
            assistant("m1", toolUse("t1"), toolUse("t2")),
            user("m2", ""),  // tool result 轮
            user("m3", "旧指令3"),
            assistant("m4"),  // anchor
            assistant("m5"),
            user("m6", "新指令"),
        )
        val marker = CompactHistoryProjector.Marker(id = "mk", version = 2, lastCompactedMessageId = "m4")
        val out = CompactHistoryProjector.project(h, "SUMMARY", marker, 60, 3)
        // 摘要必须内联进锚后首个 user（h6），不是独立消息
        val injected = out.lastOrNull { it.role == LLMMessage.Role.USER }
        assertNotNull(injected)
        // [T-compact-summary-single-carrier] 摘要在唯一载体（末尾 Text part）里；
        // content 保持用户原文，不再重复携带一份（否则投影截断会重建出两份）。
        assertTrue(
            injected!!.contentParts.filterIsInstance<AgentContentPart.Text>()
                .any { it.text.startsWith(CompactHistoryProjector.SUMMARY_OPEN_TAG) && it.text.contains("SUMMARY") },
        )
        assertEquals("新指令", injected.content)
        assertFalse(injected.content.contains(CompactHistoryProjector.SUMMARY_OPEN_TAG))
        // 首条必须是 user（角色对齐）
        assertEquals(LLMMessage.Role.USER, out.first().role)
        // 锚后 assistant 保留
        assertTrue(out.any { it.dbMessageId == "m5" })
    }

    @Test
    fun `v2 preanchor prune drops oversized tool results with paired uses`() {
        val big = "x".repeat(1500)
        val h = listOf(
            user("m0", "q"),
            assistant("m1", toolUse("t1"), toolUse("t2")),
            user("m2", ""),  // results
            assistant("m3"),  // anchor
            user("m4", "new"),
        )
        // 修正：tool results 放进 user 消息的 parts
        val h2 = listOf(
            h[0],
            h[1],
            LLMMessage(role = LLMMessage.Role.USER, content = "", contentParts = listOf(toolResult("t1", big), toolResult("t2", "ok"))),
            assistant("m3"),
            user("m4", "new"),
        )
        val marker = CompactHistoryProjector.Marker(id = "mk", version = 2, lastCompactedMessageId = "m3")
        val out = CompactHistoryProjector.project(h2, "S", marker, 60, 3)
        // >1000 字符的 t1 被剪掉，配对的 tool_use 也被剥；t2 保留
        val flat = out.flatMap { it.contentParts }
        assertTrue(flat.none { it is AgentContentPart.ToolResult && it.id == "t1" })
        assertTrue(flat.none { it is AgentContentPart.ToolUse && it.id == "t1" })
        assertTrue(flat.any { it is AgentContentPart.ToolResult && it.id == "t2" })
    }

    @Test
    fun `v2 detached anchor degrades to summary plus verbatim tail`() {
        val h = (0 until 80).map { user("m$it", "msg$it") }
        val marker = CompactHistoryProjector.Marker(id = "mk", version = 2, lastCompactedMessageId = "gone")
        val out = CompactHistoryProjector.project(h, "S", marker, detachedTailSize = 60, keepRecentUserTurns = 3)
        // 尾部 60 条逐字 + 摘要内联进首个 user
        assertEquals(60, out.size)
        // [T-compact-summary-single-carrier] 摘要载体唯一，content 就是用户原文
        // （旧用法 `content.substringAfterLast("\n\n")` 之所以成立，正是因为摘要被
        // 前置进了 content —— 那正是重复注入的一半）。
        assertTrue(
            out.first().contentParts.filterIsInstance<AgentContentPart.Text>()
                .any { it.text.startsWith(CompactHistoryProjector.SUMMARY_OPEN_TAG) },
        )
        assertEquals("msg20", out.first().content)
    }

    // ── [T-compact-summary-parts] 摘要在生产形状下必须进 contentParts ──

    private fun prodUser(id: String, text: String, vararg parts: AgentContentPart) =
        LLMMessage(
            role = LLMMessage.Role.USER,
            content = text,
            contentParts = if (parts.isEmpty()) listOf(AgentContentPart.Text(text)) else parts.toList(),
            dbMessageId = id,
        )

    @Test
    fun `v2 summary is injected into contentParts because providers ignore content`() {
        // 生产历史里 user 消息总是带 parts（发送路径 content + [Text(body)]），
        // 而三个 provider 在 parts 非空时只读 parts —— 只写 content 的摘要会静默丢失。
        val h = listOf(
            assistant("m3"),  // anchor
            prodUser("m4", "新指令"),
        )
        val marker = CompactHistoryProjector.Marker(id = "mk", version = 2, lastCompactedMessageId = "m3")
        val out = CompactHistoryProjector.project(h, "SUMMARY", marker, 60, 3)
        val injected = out.last { it.role == LLMMessage.Role.USER }
        val texts = injected.contentParts.filterIsInstance<AgentContentPart.Text>()
        assertTrue(
            "摘要必须作为 Text part 出现在 contentParts 里",
            texts.any { it.text.startsWith("<context-summary>") && it.text.endsWith("SUMMARY\n</context-summary>") },
        )
        // 追加在末尾：用户自己的文本 part 仍居首
        assertEquals("新指令", texts.first().text)
        // [T-compact-summary-single-carrier] content 不再重复携带摘要：宿主侧的
        // 巨型用户轮截断按 content 取正文、再重建 parts，两边都写就会截出两份
        // （且截断标记落进摘要内部）。摘要的唯一载体就是上面那个 Text part。
        assertFalse(injected.content.contains(CompactHistoryProjector.SUMMARY_OPEN_TAG))
        assertEquals("新指令", injected.content)
    }

    @Test
    fun `summary is carried exactly once across content and parts`() {
        // [T-compact-summary-single-carrier] 长会话压缩 + 巨型用户轮的生产组合：
        // 摘要出现次数必须是 1（旧实现 content 与 parts 各一份）。
        val h = listOf(
            assistant("m3"),  // anchor
            prodUser("m4", "新指令"),
        )
        val marker = CompactHistoryProjector.Marker(id = "mk", version = 2, lastCompactedMessageId = "m3")
        val out = CompactHistoryProjector.project(h, "SUMMARY", marker, 60, 3)
        val injected = out.last { it.role == LLMMessage.Role.USER }
        val carriers = injected.contentParts.filterIsInstance<AgentContentPart.Text>()
            .count { it.text.contains(CompactHistoryProjector.SUMMARY_OPEN_TAG) } +
            (if (injected.content.contains(CompactHistoryProjector.SUMMARY_OPEN_TAG)) 1 else 0)
        assertEquals("摘要只能有一个载体", 1, carriers)
        // 分离锚点（降级投影）路径同一条纪律。
        val detached = CompactHistoryProjector.project(
            (0 until 80).map { prodUser("m$it", "msg$it") },
            "S",
            CompactHistoryProjector.Marker(id = "mk2", version = 2, lastCompactedMessageId = "gone"),
            detachedTailSize = 60,
            keepRecentUserTurns = 3,
        )
        val first = detached.first()
        val detachedCarriers = first.contentParts.filterIsInstance<AgentContentPart.Text>()
            .count { it.text.contains(CompactHistoryProjector.SUMMARY_OPEN_TAG) } +
            (if (first.content.contains(CompactHistoryProjector.SUMMARY_OPEN_TAG)) 1 else 0)
        assertEquals("分离投影摘要也只能有一个载体", 1, detachedCarriers)
    }

    @Test
    fun `parts-less user turn keeps its body next to the summary part`() {
        // [T-compact-summary-single-carrier] parts 为空、正文只在 content 里的合成/遗留
        // 形状：摘要进 parts 之后 provider 只会读 parts，正文必须一起折进 parts，否则
        // 用户那句话对模型彻底消失。
        val h = listOf(
            assistant("m3"),  // anchor
            user("m4", "只有 content 的正文"),
        )
        val marker = CompactHistoryProjector.Marker(id = "mk", version = 2, lastCompactedMessageId = "m3")
        val out = CompactHistoryProjector.project(h, "S", marker, 60, 3)
        val injected = out.last { it.role == LLMMessage.Role.USER }
        val texts = injected.contentParts.filterIsInstance<AgentContentPart.Text>().map { it.text }
        assertEquals("正文必须先于摘要进 parts", "只有 content 的正文", texts.first())
        assertTrue(texts.last().startsWith(CompactHistoryProjector.SUMMARY_OPEN_TAG))
        assertEquals(2, texts.size)
    }

    @Test
    fun `summary part never displaces a leading tool_result`() {
        // Anthropic 要求 tool_result 居 user content 数组首位；锚后首个 user 恰好
        // 是 tool result 轮时，摘要要追加在它后面而不是插到前面。
        val h = listOf(
            assistant("m1"),  // anchor
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(toolResult("t1", "result")),
                dbMessageId = "m2",
            ),
            prodUser("m3", "next"),
        )
        val marker = CompactHistoryProjector.Marker(id = "mk", version = 2, lastCompactedMessageId = "m1")
        val out = CompactHistoryProjector.project(h, "S", marker, 60, 3)
        val injected = out.first { it.role == LLMMessage.Role.USER && it.dbMessageId == "m2" }
        assertTrue(injected.contentParts.first() is AgentContentPart.ToolResult)
        val summary = injected.contentParts.last()
        assertTrue(summary is AgentContentPart.Text && summary.text.contains("<context-summary>"))
    }

    @Test
    fun `detached projection also puts the summary in contentParts`() {
        val h = (0 until 80).map { prodUser("m$it", "msg$it") }
        val marker = CompactHistoryProjector.Marker(id = "mk", version = 2, lastCompactedMessageId = "gone")
        val out = CompactHistoryProjector.project(h, "S", marker, detachedTailSize = 60, keepRecentUserTurns = 3)
        val injected = out.first()
        val texts = injected.contentParts.filterIsInstance<AgentContentPart.Text>()
        assertTrue(texts.any { it.text.startsWith("<context-summary>") && it.text.endsWith("S\n</context-summary>") })
        assertEquals("msg20", texts.first().text)
    }

    @Test
    fun `v1 legacy marker keeps summary head plus slice from first kept`() {
        val h = listOf(user("m0"), user("m1"), user("m2"))
        val marker = CompactHistoryProjector.Marker(id = "mk", version = 1, firstKeptMessageId = "m1")
        val out = CompactHistoryProjector.project(h, "S", marker, 60, 3)
        assertEquals(3, out.size) // summaryHead + m1 + m2
        assertEquals(LLMMessage.Role.USER, out[0].role)
        assertTrue(out[0].content.contains("S"))
        assertEquals("m1", out[1].dbMessageId)
    }

    @Test
    fun `walkback stops at user text target and never splits a tool round`() {
        // anchor=4；user 文本轮：m3、m0（m2 是 tool result 轮不算轮头）
        val h = listOf(
            user("m0", "turn1"),
            assistant("m1"),
            LLMMessage(role = LLMMessage.Role.USER, content = "", contentParts = listOf(toolResult("t1"))),
            user("m3", "turn2"),
            assistant("m4"),
        )
        val wb = CompactHistoryProjector.walkBackUserTurnsBounded(h, anchorIdx = 4, maxUserTextTurns = 2, maxMessages = 100)
        assertEquals("userTextTargetMet", wb.stopReason)
        assertEquals(0, wb.priorIdx) // m0 与 m3 两轮都收进来
        // cap：maxMessages=1 时连首个候选（2 条）都装不下 → 空 preAnchor
        val wb2 = CompactHistoryProjector.walkBackUserTurnsBounded(h, anchorIdx = 4, maxUserTextTurns = 2, maxMessages = 1)
        assertEquals("messageCapWouldExceed", wb2.stopReason)
        assertNull(wb2.priorIdx)
    }

    @Test
    fun `tool round pairing drops dangling results and repairs unanswered uses`() {
        // use t1 无 result；result t2 无 use（悬空）；t3 配对正常
        val h = listOf(
            user("m0", "q"),
            assistant("m1", toolUse("t1"), toolUse("t3")),
            LLMMessage(role = LLMMessage.Role.USER, content = "", contentParts = listOf(toolResult("t2"), toolResult("t3"))),
            user("m4", "next"),
        )
        val out = com.openminis.app.harness.agent.ToolRoundPairing.dropOrphanedToolParts(h)
        val flat = out.flatMap { it.contentParts }
        // 悬空 t2 被丢
        assertTrue(flat.none { it is AgentContentPart.ToolResult && it.id == "t2" })
        // 未应答 t1 补了错误占位 result
        val repaired = flat.filterIsInstance<AgentContentPart.ToolResult>().first { it.id == "t1" }
        assertTrue(repaired.isError)
        // t3 配对保留
        assertTrue(flat.any { it is AgentContentPart.ToolUse && it.id == "t3" })
        assertTrue(flat.any { it is AgentContentPart.ToolResult && it.id == "t3" })
    }

    @Test
    fun `tail assistant awaiting execution is exempt from repair`() {
        val h = listOf(
            user("m0", "q"),
            assistant("m1"), // 尾部在飞轮：use 未应答是合法的
        )
        val h2 = listOf(
            h[0],
            LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "", contentParts = listOf(toolUse("t9")), dbMessageId = "m1"),
        )
        val out = com.openminis.app.harness.agent.ToolRoundPairing.dropOrphanedToolParts(h2)
        // 不补占位、不丢 use
        assertTrue(out.flatMap { it.contentParts }.any { it is AgentContentPart.ToolUse && it.id == "t9" })
        assertEquals(2, out.size)
    }
}
