package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.harness.context.ContextWindowPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-context-window-body-part-identity] 用户轮的**正文部件怎么认**必须由结构决定，
 * 不能按文本内容嗅探。
 *
 * 旧实现把 `text.contains("<user-attached-files>")` 当「注入段」判据，而 fresh send
 * 的形状是 `content == modelBody == parts[0]`（ChatViewModelSendExt）——用户自己输入 /
 * 粘贴这个字面量时，正文部件被认成注入段，两个后果一起发生（本文件前两例就是实测
 * 形状：正文 ≈ 8001 token、预算 8500）：
 *  - 预算被多扣一整份正文 → 真实总量没超也被截断；
 *  - [projectUserTurnParts] 的正文段判据用的是**截断后**的 `turn.text`，与 parts 里的
 *    原正文逐字不同 → 旧正文段没被替换掉，截断后的短正文与未截断的原正文同时进请求体
 *    （provider 可见正文反而变长）。
 *
 * 结构性判据（[bodyPartCount]：以 `content` 为游标逐个前导 Text 部件做前缀匹配）
 * 对这两种形状都免疫，同时**不影响**真正的注入段：附件 XML / 图片占位 / 压缩摘要都
 * 排在正文段之后、且不在 `content` 里（后两例是这两条的非回归）。
 */
class ContextWindowBodyPartIdentityTest {

    private companion object {
        /** 20000 个 ascii 字母 ≈ 8000 token（ContextWindowPolicy 的口径）。 */
        val giantBody = "a".repeat(20_000)
        const val XML_LITERAL = "<user-attached-files>"
    }

    private fun userTurn(content: String, parts: List<AgentContentPart> = emptyList()) =
        LLMMessage(role = LLMMessage.Role.USER, content = content, contentParts = parts)

    /** provider 实际能看到的正文：parts 非空时它**只**序列化 parts。 */
    private fun visibleBody(m: LLMMessage): String =
        m.contentParts.filterIsInstance<AgentContentPart.Text>().joinToString("") { it.text }
            .ifEmpty { m.content }

    @Test
    fun `a body that literally contains the attachment tag is still the body`() {
        // 用户输入 / 粘贴里出现该字面量，正文部件仍必须算正文：
        // 真实总量 ≈ 8017 token < 预算 8500 → 不许截断（旧实现扣掉整份正文后误截）。
        val body = giantBody + XML_LITERAL + "</user-attached-files>"
        val history = listOf(userTurn(body, listOf(AgentContentPart.Text(body))))

        assertTrue(
            "字面量不得把正文部件判成注入段（否则预算被多扣一份正文）",
            applyContextWindowPolicy(history, 8_500) === history,
        )
    }

    @Test
    fun `the same body without the literal is untouched too`() {
        // 对照：同样规模、正文里没有该字面量 —— 两边的行为必须一致，差异只能来自
        // 「怎么认正文」，不能来自「正文大小」。
        val history = listOf(userTurn(giantBody, listOf(AgentContentPart.Text(giantBody))))

        assertTrue("基线同样不该被截断", applyContextWindowPolicy(history, 8_500) === history)
    }

    @Test
    fun `truncation replaces the folded body part instead of appending to it`() {
        // 真超预算时必须截，而且截断结果要**替换掉**被折进 content 的那一段。
        val body = giantBody + XML_LITERAL + "</user-attached-files>"
        val history = listOf(userTurn(body, listOf(AgentContentPart.Text(body))))

        val out = applyContextWindowPolicy(history, 4_000)[0]
        assertTrue("超预算要截断（len=${out.content.length}）", out.content.length < body.length)
        assertEquals(
            "重建后 parts 里只该剩截断后的正文（旧正文段必须被 drop 掉）",
            1,
            out.contentParts.size,
        )
        assertEquals(
            "部件文本必须与截断后的 content 一致",
            out.content,
            (out.contentParts[0] as AgentContentPart.Text).text,
        )
        assertTrue(
            "provider 可见正文不得比原文更长（visible=${visibleBody(out).length} " +
                "original=${body.length}）",
            visibleBody(out).length <= body.length,
        )
    }

    @Test
    fun `the attachment XML that lives only in parts is still counted`() {
        // 非回归：content 里没有的注入段必须照旧进估算，否则总量判定偏小、
        // 巨型用户轮不会被截断（紧预算下请求体顶破窗口）。
        val xmlBlock = "<user-attached-files>\n" +
            (1..40).joinToString("\n") { "<file>/tmp/attachments/report-$it.pdf ${it * 13}KB</file>" } +
            "\n</user-attached-files>"
        assertTrue(
            "夹具前提：附件 XML 得有量级（tokens=${ContextWindowPolicy.estimateTokens(xmlBlock)}）",
            ContextWindowPolicy.estimateTokens(xmlBlock) > 200,
        )
        val xmlTurn = userTurn(
            "看这个 ",
            listOf(AgentContentPart.Text("看这个 "), AgentContentPart.Text(xmlBlock)),
        )
        val history = listOf(
            userTurn(giantBody, listOf(AgentContentPart.Text(giantBody))),
            xmlTurn,
        )

        val out = applyContextWindowPolicy(history, 8_400)
        assertTrue(
            "附件 XML 仍须计入估算 → 巨型轮被截断（len=${out[0].content.length}）",
            out[0].content.length < giantBody.length,
        )
        assertTrue("注入轮本身不该被改写", out[1] === xmlTurn)
    }

    @Test
    fun `body segments folded into content are not counted twice`() {
        // 恢复路径的形状（MessageEntity.toLLMMessage 的 pasted 分支）：粘贴展开出来的
        // 正文部件**同时**写进 content，是正文的连续分段，不能重复补算。
        val pasted = "b".repeat(20_000)
        val content = giantBody + pasted
        val history = listOf(
            userTurn(content, listOf(AgentContentPart.Text(giantBody), AgentContentPart.Text(pasted))),
        )

        assertTrue(
            "16000 token 的正文配 16500 预算不该被截（重复补算会掉到 ~8500）",
            applyContextWindowPolicy(history, 16_500) === history,
        )
    }
}
