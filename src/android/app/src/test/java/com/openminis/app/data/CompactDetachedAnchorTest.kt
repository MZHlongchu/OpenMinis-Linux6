package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.harness.agent.CompactHistoryProjector
import com.openminis.app.harness.context.ContextWindowPolicy
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.chat.applyContextWindowPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-compact-detached-anchor] The regression under test: once a marker's
 * anchor falls out of the bounded agentHistory window, the old code threw
 * the summary away and sent the full history on every turn — a session could
 * never get back under budget. The fallback must instead deliver the summary
 * plus a verbatim tail, starting on a user turn.
 *
 * [T-compact-summary-single-carrier] 交付形状在第三轮定稿：摘要的唯一载体是
 * 首条 user 轮 `contentParts` **末尾**的 Text part（`SUMMARY_OPEN_TAG` 开头），
 * `content` 只留用户自己的正文。provider 在 parts 非空时只读 parts，所以写
 * `content` 的那份对模型本来就不可见；两边都写则会在宿主按 `content` 重建 parts
 * 的截断路径上变成两份。本文件按新契约断言，并对分离锚点路径单独钉住
 * 「摘要恰好一次」——不许把旧断言删掉换成一串与语义无关的检查。
 */
class CompactDetachedAnchorTest {

    private fun user(i: Int) = LLMMessage(role = LLMMessage.Role.USER, content = "u$i", dbMessageId = "id$i")
    private fun asst(i: Int) = LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "a$i", dbMessageId = "id${i}a")

    private fun history(n: Int): List<LLMMessage> =
        (1..n).flatMap { listOf(user(it), asst(it)) }

    /**
     * [T-compact-summary-single-carrier] 摘要的载体数：`contentParts` 里的 Text 部件
     * 加 `content`（parts 非空时后者不上线路，但「两边都写」正是旧实现的双份缺陷）。
     * 口径与 CompactHistoryProjectorTest 的 "carried exactly once" 用例一致。
     */
    private fun summaryCarriers(msg: LLMMessage, tag: String = "<summary/>"): Int =
        msg.contentParts.filterIsInstance<AgentContentPart.Text>().count { it.text.contains(tag) } +
            (if (msg.content.contains(tag)) 1 else 0)

    @Test
    fun `a short history is passed through with the summary inlined`() {
        val h = listOf(user(1), asst(1), user(2))
        val out = ChatViewModel.buildDetachedCompactHistory("<summary/>", h, tailSize = 60)
        assertEquals(3, out.size)
        val first = out.first()
        assertEquals(LLMMessage.Role.USER, first.role)
        // [T-compact-summary-single-carrier] 新契约：摘要内联为首条 user 轮 parts 末尾的
        // Text part，`content` 只留这一轮自己的正文（旧契约的 content 前缀已移除）。
        // parts 为空（合成/遗留形状）时正文先被折成一个 Text part，摘要跟在它后面。
        assertEquals("正文必须原样保留在 content 里", "u1", first.content)
        assertEquals(
            "首条 user = [正文, 摘要]，摘要挂在末尾且只此一份",
            listOf("u1", "<summary/>"),
            first.contentParts.filterIsInstance<AgentContentPart.Text>().map { it.text },
        )
        assertEquals("摘要只能有一个载体", 1, summaryCarriers(first))
        assertEquals("尾部逐字跟随", "u2", out.last().content)
    }

    @Test
    fun `an oversized history is cut down to the tail budget`() {
        val h = history(200) // 400 entries
        val out = ChatViewModel.buildDetachedCompactHistory("<summary/>", h, tailSize = 60)
        assertEquals(60, out.size)
        // Tail semantics: the newest entries survive, the oldest are dropped.
        assertEquals("u200", out.last { it.role == LLMMessage.Role.USER }.content)
    }

    @Test
    fun `leading assistant turns are peeled so the first message is user`() {
        val h = listOf(asst(1), asst(2), user(3), asst(3))
        val out = ChatViewModel.buildDetachedCompactHistory("<summary/>", h, tailSize = 60)
        assertEquals("头部 assistant 必须被剥掉，只留 user(3) 及其后的 assistant", 2, out.size)
        val first = out.first()
        assertEquals(LLMMessage.Role.USER, first.role)
        assertEquals("u3", first.content)
        // 摘要必须挂在这条剥完头部之后的首条 user 上（新契约下是 parts 尾部 Text）。
        assertEquals(
            listOf("u3", "<summary/>"),
            first.contentParts.filterIsInstance<AgentContentPart.Text>().map { it.text },
        )
        assertEquals("摘要只能有一个载体", 1, summaryCarriers(first))
    }

    @Test
    fun `a tail with no user turn still carries the summary`() {
        val h = listOf(asst(1), asst(2))
        val out = ChatViewModel.buildDetachedCompactHistory("<summary/>", h, tailSize = 60)
        assertEquals(3, out.size)
        assertEquals("<summary/>", out.last().content)
        assertEquals(LLMMessage.Role.USER, out.last().role)
    }

    @Test
    fun `the injected summary is strictly smaller than sending the whole history`() {
        // The point of the fix: bound what reaches the provider.
        val h = history(200)
        val out = ChatViewModel.buildDetachedCompactHistory("<summary/>", h, tailSize = 60)
        assertTrue(
            "expected ${out.size} sent messages vs ${h.size} in history",
            out.size < h.size,
        )
    }

    @Test
    fun `the detached summary survives the host rebuild exactly once`() {
        // [T-compact-summary-single-carrier] 分离锚点路径 × 宿主消费方
        // （applyContextWindowPolicy → projectUserTurnParts）：宿主按 `content` 取正文、
        // 再按 parts 重建。摘要只作为 parts 尾部部件时，巨型正文被截断也不会咬掉摘要、
        // 更不会在重建里变出第二份——这就是「两路径都只出现一次摘要」里分离锚点那一半
        // （v2 内联路径的同型纪律见 CompactHistoryProjectorTest）。
        val giant = "a".repeat(20_000) // ≈8000 token 的正文
        val summary = CompactHistoryProjector.SUMMARY_OPEN_TAG + "\n" +
            "The following is a summary of the earlier conversation.\n" +
            "摘".repeat(6_000) + // ≈3300 token 的摘要，足以把总账顶过预算
            "\n</context-summary>"
        val h = listOf(
            LLMMessage(role = LLMMessage.Role.USER, content = giant, dbMessageId = "id1"),
            asst(1),
            user(2),
        )
        val detached = ChatViewModel.buildDetachedCompactHistory(summary, h, tailSize = 60)
        assertEquals(
            "分离投影：摘要只能有一个载体",
            1,
            summaryCarriers(detached.first(), CompactHistoryProjector.SUMMARY_OPEN_TAG),
        )

        val out = applyContextWindowPolicy(detached, 8_500)
        val first = out.first()
        // 8500 的预算减去摘要（≈3300）后装不下 8000 token 的正文 → 必须截断；旧实现把
        // 摘要塞在 content 里会让「按 content 取正文」把摘要当正文切一刀，于是摘要落进
        // 截断结果、与 parts 里的那份重复。
        assertTrue(
            "摘要必须计入预算并触发正文截断（first=${first.content.length}）",
            first.content.length < giant.length,
        )
        assertTrue(
            "被截断的是正文（截断标记落在正文里）",
            first.content.contains(ContextWindowPolicy.USER_TRUNCATION_MARKER),
        )
        assertFalse(
            "摘要不许落进正文（单载体）",
            first.content.contains(CompactHistoryProjector.SUMMARY_OPEN_TAG),
        )
        assertEquals(
            "摘要必须逐字完整地留在 parts 尾部",
            listOf(summary),
            first.contentParts.filterIsInstance<AgentContentPart.Text>()
                .map { it.text }
                .filter { it.startsWith(CompactHistoryProjector.SUMMARY_OPEN_TAG) },
        )
        assertEquals(
            "重建后摘要仍然只有一个载体",
            1,
            summaryCarriers(first, CompactHistoryProjector.SUMMARY_OPEN_TAG),
        )
        assertEquals("尾部逐字内容不受重建影响", "u2", out.last().content)
    }
}
