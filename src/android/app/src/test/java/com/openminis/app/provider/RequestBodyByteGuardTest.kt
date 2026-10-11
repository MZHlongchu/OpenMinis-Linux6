package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.harness.agent.CompactHistoryProjector
import com.openminis.app.harness.context.ContextWindowPolicy
import com.openminis.app.ui.chat.applyContextWindowPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-context-window-policy] 字节守卫与投影截断的宿主侧验收。
 */
class RequestBodyByteGuardTest {

    private fun bodyWithImage(dataChars: Int): JSONObject {
        val block = JSONObject()
            .put("type", "image")
            .put("source", JSONObject().put("type", "base64").put("data", "x".repeat(dataChars)))
        val text = JSONObject().put("type", "text").put("text", "look")
        val message = JSONObject().put("role", "user").put("content", JSONArray().put(text).put(block))
        return JSONObject().put("messages", JSONArray().put(message))
    }

    @Test
    fun `small bodies pass through byte identical`() {
        val body = bodyWithImage(10)
        val once = body.toString()
        assertEquals(once, RequestBodyByteGuard.enforce(JSONObject(once)))
    }

    @Test
    fun `over limit bodies get images stripped with a marker`() {
        val body = bodyWithImage(5 * 1024 * 1024)
        val out = RequestBodyByteGuard.enforce(body)
        assertTrue(out.length < 1_000_000)
        assertTrue(out.contains("张图片因请求体体积限制已从模型上下文省略"))
        assertTrue(!out.contains("base64"))
    }

    @Test
    fun `still over after stripping fails loud instead of shipping a 413`() {
        val body = bodyWithImage(100)
        body.getJSONArray("messages").getJSONObject(0)
            .getJSONArray("content").getJSONObject(0)
            .put("text", "y".repeat(5 * 1024 * 1024))
        val failure = runCatching { RequestBodyByteGuard.enforce(body) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("hard limit"))
    }

    @Test
    fun `projection truncation only touches user content over budget`() {
        val giant = (1..30_000).joinToString(" ") { "w$it" }
        val history = listOf(
            LLMMessage(role = LLMMessage.Role.USER, content = giant),
            LLMMessage(role = LLMMessage.Role.ASSISTANT, content = giant),
        )
        val out = applyContextWindowPolicy(history, 3_000)
        assertTrue("user turn must shrink", out[0].content.length < giant.length)
        assertTrue(out[0].content.contains("消息过长已截断"))
        assertEquals("assistant text must not be touched", giant, out[1].content)
    }

    /**
     * [T-b1-user-turn-truncation-parts] provider 在 contentParts 非空时只序列化
     * parts，所以投影截断必须落在 parts 上，否则巨型用户轮原样进请求体。
     */
    @Test
    fun `projection truncation rewrites the user contentParts body`() {
        val giant = (1..30_000).joinToString(" ") { "w$it" }
        val history = listOf(
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = giant,
                contentParts = listOf(
                    AgentContentPart.Text(giant),
                    AgentContentPart.Text("<user-attached-files>/tmp/a.png</user-attached-files>"),
                ),
            ),
        )
        val out = applyContextWindowPolicy(history, 3_000)
        val textParts = out[0].contentParts.filterIsInstance<AgentContentPart.Text>()
        val body = textParts.filterNot { it.text.contains("<user-attached-files>") }
            .joinToString("") { it.text }
        assertTrue("provider-visible body must shrink", body.length < giant.length)
        assertTrue("provider-visible body must carry the marker", body.contains("消息过长已截断"))
        assertTrue(
            "attachment XML must survive the rewrite",
            textParts.any { it.text.contains("<user-attached-files>") },
        )
    }

    @Test
    fun `image stripping is reflected in contentParts too`() {
        val images = List(30) { LLMMessage.ImagePart(data = ByteArray(4), mimeType = "image/png") }
        val history = listOf(
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "hi",
                imageParts = images,
                contentParts = listOf(
                    AgentContentPart.Text("hi"),
                    AgentContentPart.Text("[attached image: /var/minis/a.png]"),
                ) + images.map { AgentContentPart.ImageData(it.data, it.mimeType) },
            ),
        )
        val out = applyContextWindowPolicy(history, 3_000)
        assertTrue("imageParts must be dropped", out[0].imageParts.isEmpty())
        assertTrue(
            "ImageData parts must be dropped with them",
            out[0].contentParts.none { it is AgentContentPart.ImageData },
        )
        assertTrue(out[0].content.contains("张图片因上下文预算已从模型上下文省略"))
        assertTrue(
            "the strip note must reach the provider-visible body",
            out[0].contentParts.filterIsInstance<AgentContentPart.Text>()
                .any { it.text.contains("张图片因上下文预算已从模型上下文省略") },
        )
        assertTrue(
            "image path markers must survive",
            out[0].contentParts.filterIsInstance<AgentContentPart.Text>()
                .any { it.text.startsWith("[attached image: ") },
        )
    }

    @Test
    fun `zero budget means no governance`() {
        val history = listOf(LLMMessage(role = LLMMessage.Role.USER, content = "x".repeat(50_000)))
        assertEquals(history, applyContextWindowPolicy(history, 0))
    }

    /**
     * [T-compact-summary-single-carrier] 压缩摘要 + 巨型用户轮的生产组合：投影顺序是
     * `CompactHistoryProjector.project` → `applyContextWindowPolicy`，而后者按
     * `content` 取正文、再用 `projectUserTurnParts` 重建 parts。摘要若同时写进
     * content 与 parts，截断重建就会产出两份摘要，截断标记还会落进摘要内部。
     *
     * 两种形状都验：① 正文 + 图片占位（bodyCount 只吃前导 run，摘要 part 会留在
     * 尾部 → 旧实现重复）；② 纯正文（bodyCount 会把摘要一起吞掉 → 旧实现丢摘要）。
     */
    @Test
    fun `compact summary survives projection truncation exactly once`() {
        val giant = (1..30_000).joinToString(" ") { "w$it" }
        val summaryTag = CompactHistoryProjector.SUMMARY_OPEN_TAG
        for (extraParts in listOf(
            emptyList(),
            listOf(AgentContentPart.Text("[attached image: /var/minis/a.png]")),
        )) {
            val history = listOf(
                LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "", dbMessageId = "m1"),
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = giant,
                    contentParts = listOf(AgentContentPart.Text(giant)) + extraParts,
                    dbMessageId = "m2",
                ),
            )
            val marker = CompactHistoryProjector.Marker(
                id = "mk", version = 2, lastCompactedMessageId = "m1",
            )
            val projected = CompactHistoryProjector.project(history, "S", marker, 60, 3)
            val out = applyContextWindowPolicy(projected, 3_000)
            val user = out.last { it.role == LLMMessage.Role.USER }
            val textParts = user.contentParts.filterIsInstance<AgentContentPart.Text>()
            assertEquals(
                "摘要只能有一个载体（parts=${textParts.size}, extra=${extraParts.size}）",
                1,
                textParts.count { it.text.contains(summaryTag) } +
                    (if (user.content.contains(summaryTag)) 1 else 0),
            )
            val summaryPart = textParts.first { it.text.contains(summaryTag) }
            assertTrue(
                "摘要必须完整（截断标记不能落进摘要内部）",
                summaryPart.text.endsWith("S\n</context-summary>"),
            )
            val body = textParts.filterNot { it.text.contains(summaryTag) }.joinToString("") { it.text }
            assertTrue("正文必须真的被截断", body.length < giant.length)
            assertTrue("截断标记必须落在正文里", body.contains("消息过长已截断"))
        }
    }

    @Test
    fun `schema reserve is measured from our own tool schemas`() {
        val reserve = com.openminis.app.tools.ToolSchemaResolver.schemas.values
            .sumOf { ContextWindowPolicy.estimateTokens(it.toString()) }
        assertTrue("reserve must be a real measured quantity, got $reserve", reserve in 1_000..50_000)
    }
}
