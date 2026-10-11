package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.harness.agent.CompactHistoryProjector
import com.openminis.app.harness.context.ContextWindowPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-context-window-part-accounting] 压缩摘要必须进 token 记账。
 *
 * 摘要在 [CompactHistoryProjector] 里被搬进用户轮的 **parts 尾部**（单载体，
 * `content` 不再带它），而 [applyContextWindowPolicy] 投影给 harness 的
 * `ProjectedUserTurn.text` 取的是 `content`。不补这一段，压缩后每轮都少算一个数千
 * token 的摘要：总量判定偏小 → 截断不触发 → 请求体带着摘要顶破窗口。
 *
 * 用例 1 就是这个形状（旧实现返回原文、截断不触发）；用例 2 是反向对照：预算宽裕时
 * 不许因为多算了摘要而误截；用例 3 钉住「已经折进 content 的正文部件不得重复计」。
 */
class ContextWindowSummaryAccountingTest {

    private val summaryTag = CompactHistoryProjector.SUMMARY_OPEN_TAG

    /** 8000 token 的正文（全 ascii 字母，20000/2.5）。 */
    private val giantBody = "a".repeat(20_000)

    /** 约 3340 token 的摘要（6000 个 CJK 字 + 包装标签）。 */
    private fun summary(): String =
        "$summaryTag\nThe following is a summary of the earlier conversation.\n" +
            "摘".repeat(6_000) +
            "\n</context-summary>"

    private fun userTurn(content: String, parts: List<AgentContentPart> = emptyList()) =
        LLMMessage(role = LLMMessage.Role.USER, content = content, contentParts = parts)

    @Test
    fun `an injected summary is counted so the projection still truncates`() {
        val summary = summary()
        val history = listOf(
            userTurn(giantBody),
            // 被注入摘要的那一轮：正文很短，摘要只在 parts 里。
            userTurn("短问", listOf(AgentContentPart.Text("短问"), AgentContentPart.Text(summary))),
        )

        // 8000(正文) + 1 + 摘要 ≈ 11340 token 的真实总量 > 8500：旧实现只数 8001，
        // 判定「没超」→ 原样发出。
        val out = applyContextWindowPolicy(history, 8_500)

        assertTrue(
            "摘要必须计入估算，否则截断不会触发（out=${out[0].content.length}）",
            out[0].content.length < giantBody.length,
        )
        assertTrue("截断标记必须落在正文里", out[0].content.contains("已截断"))
        val injected = out[1]
        assertEquals("注入轮的正文本体不许被改写", "短问", injected.content)
        assertEquals(
            "摘要仍必须只有一个载体、且逐字完整",
            listOf(summary),
            injected.contentParts.filterIsInstance<AgentContentPart.Text>()
                .map { it.text }
                .filter { it.startsWith(summaryTag) },
        )
    }

    @Test
    fun `a summary that fits the budget does not cause truncation`() {
        val history = listOf(
            userTurn(giantBody),
            userTurn("短问", listOf(AgentContentPart.Text("短问"), AgentContentPart.Text(summary()))),
        )
        // 200000 - 摘要 ≈ 196600 ≫ 8001 → 不该动任何东西，且必须原样返回同一个列表。
        assertSame(history, applyContextWindowPolicy(history, 200_000))
    }

    @Test
    fun `body parts already folded into content are not counted twice`() {
        // 恢复路径的形状（MessageEntity.toLLMMessage 的 pasted 分支）：粘贴展开出来的
        // 正文部件**同时**写进 content，所以它不算「非正文部件」，不能补算一次。
        val pasted = "b".repeat(20_000)
        val content = giantBody + pasted // 16000 token
        val history = listOf(
            userTurn(content, listOf(AgentContentPart.Text(giantBody), AgentContentPart.Text(pasted))),
        )
        // 16500 足以放下 16000；若把 pasted 部件当成「非正文」再补算 8000，
        // 有效预算会掉到 8500 → 误截断，这条断言就会炸。
        assertSame(history, applyContextWindowPolicy(history, 16_500))
    }

    @Test
    fun `the summary fixture is big enough for the accounting bug to matter`() {
        // 用例 1 的成立前提：摘要本身是「数千 token」量级（用户报的正是这个）。
        assertTrue(
            "摘要规模必须真的达到数千 token，否则这个回归测不出账目差异",
            ContextWindowPolicy.estimateTokens(summary()) > 2_000,
        )
    }
}
