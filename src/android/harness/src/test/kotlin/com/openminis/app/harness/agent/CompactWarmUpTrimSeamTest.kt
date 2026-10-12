package com.openminis.app.harness.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-ctx-warmup-fit] 宿主热身裁剪钩子的**接缝**验收（[CompactHistoryProjector.project]
 * 的 `trimWarmUp`）。
 *
 * 上游把 `trimWarmUpToFit` 内联在单体 `effectiveAgentHistoryUncounted` 里
 * （ChatViewModel.kt:1583-1604 定义、:4750-4768 调用）。本移植把投影搬进了 harness，
 * 于是这个特性在某一轮里只剩死代码：`trimWarmUpToFit` 写好了、按 marker 的决策缓存
 * 也写好了，却没有任何请求路径会调用它。这一组用例钉住的正是那条接线——
 * 宿主按投影的回吐形状拿到「剪枝并对齐过角色、尚未裁的热身 / 锚后那一段 /
 * 真正会发出去的摘要文本」，回吐的热身就是**发出去的热身**。
 *
 * 宿主侧那一半（按 marker 只决定一次、算不出预算时不缓存）的守卫在 app 模块：
 * WarmUpTrimUndecidedTest、ContextMeasureOutboundTest、OffloadWarmUpScanTest。
 */
class CompactWarmUpTrimSeamTest {

    private fun user(id: String, text: String) =
        LLMMessage(role = LLMMessage.Role.USER, content = text, dbMessageId = id)

    private fun assistant(id: String, text: String = "") =
        LLMMessage(role = LLMMessage.Role.ASSISTANT, content = text, dbMessageId = id)

    /** 三个用户文本轮 + 锚（assistant）；锚后：一条 assistant + 一条新指令。 */
    private val history = listOf(
        user("w1", "第一轮"),
        assistant("a1", "ok1"),
        user("w2", "第二轮"),
        assistant("a2", "ok2"),
        user("w3", "第三轮"),
        assistant("anchor", "ok3"),
        assistant("p1", "锚后"),
        user("p2", "新指令"),
    )

    private val marker = CompactHistoryProjector.Marker(
        id = "mk",
        version = 2,
        lastCompactedMessageId = "anchor",
    )

    private fun project(
        trimWarmUp: ((List<LLMMessage>, List<LLMMessage>, String) -> List<LLMMessage>)?,
        m: CompactHistoryProjector.Marker? = marker,
    ): List<LLMMessage> = CompactHistoryProjector.project(
        history = history,
        summary = "SUMMARY",
        marker = m,
        detachedTailSize = 60,
        keepRecentUserTurns = 3,
        trimWarmUp = trimWarmUp,
    )

    @Test
    fun `without the hook the warm-up passes through untouched`() {
        val out = project(trimWarmUp = null)
        assertEquals(
            listOf("w1", "a1", "w2", "a2", "w3", "anchor"),
            out.take(6).map { it.dbMessageId },
        )
    }

    @Test
    fun `the hook sees the pruned warm-up, the post-anchor slice and the summary text`() {
        var seenWarmUp: List<String?>? = null
        var seenRest: List<String?>? = null
        var seenSummary: String? = null
        var calls = 0
        project(
            trimWarmUp = { warmUp, rest, summaryText ->
                calls += 1
                seenWarmUp = warmUp.map { it.dbMessageId }
                seenRest = rest.map { it.dbMessageId }
                seenSummary = summaryText
                warmUp
            },
        )
        assertEquals("v2 锚点路径上必须恰好问一次", 1, calls)
        assertEquals(listOf("w1", "a1", "w2", "a2", "w3", "anchor"), seenWarmUp)
        assertEquals(listOf("p1", "p2"), seenRest)
        assertNotNull(seenSummary)
        assertTrue(
            "摘要文本是**真正发出去**的那一份（带 <context-summary> 包裹）",
            seenSummary!!.startsWith(CompactHistoryProjector.SUMMARY_OPEN_TAG),
        )
        assertTrue(seenSummary!!.contains("SUMMARY"))
    }

    @Test
    fun `what the hook returns is exactly what goes out`() {
        // 丢掉最旧的一整轮（user + assistant）——上游 warmUpDrop 也只落在这个粒度上。
        val out = project(trimWarmUp = { warmUp, _, _ -> warmUp.drop(2) })
        assertEquals(
            listOf("w2", "a2", "w3", "anchor", "p1"),
            out.take(5).map { it.dbMessageId },
        )
        // 锚后那一段与摘要内联位置不受裁剪影响（角色交替仍然成立）。
        assertEquals(LLMMessage.Role.USER, out.first().role)
        val injected = out.last()
        assertEquals("p2", injected.dbMessageId)
        assertTrue(
            injected.contentParts.filterIsInstance<AgentContentPart.Text>()
                .any { it.text.startsWith(CompactHistoryProjector.SUMMARY_OPEN_TAG) },
        )
        assertTrue("裁剪不得动锚后消息", out.any { it.dbMessageId == "p1" })
    }

    @Test
    fun `the hook is not consulted outside the v2 anchored path`() {
        var calls = 0
        val count = { w: List<LLMMessage>, _: List<LLMMessage>, _: String -> calls += 1; w }

        // 无摘要 / 无标记 → 全量历史。
        project(trimWarmUp = count, m = null)
        assertEquals("无标记时不问", 0, calls)

        // v1 遗留标记 → 摘要头 + 切片，没有热身概念。
        project(
            trimWarmUp = count,
            m = CompactHistoryProjector.Marker(id = "mk", version = 1, firstKeptMessageId = "w3"),
        )
        assertEquals("v1 不问", 0, calls)

        // v2 但锚点被逐出有界窗 → 降级成「摘要 + 尾部逐字」，同样没有热身。
        project(
            trimWarmUp = count,
            m = CompactHistoryProjector.Marker(id = "mk", version = 2, lastCompactedMessageId = "gone"),
        )
        assertEquals("分离锚点降级路径不问", 0, calls)
    }
}
