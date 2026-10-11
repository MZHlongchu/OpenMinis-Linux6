package com.openminis.app.offload

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-d10-prompt-slot] `SessionsOffloadHandler` 依赖的槽语义：**带预算的调用方不会永久占着
 * 在屏槽，也不会把整段预算烧在别人的提示后面**。
 *
 * 这里驱动真实的 [OffloadPromptQueue]（不碰 Android API），用
 * `withTimeoutOrNull(INTERACTIVE_BUDGET_MS) { await(...) }` 复现 handler 的调用形状：
 *
 *  1. 在屏那张的预算到点 ⇒ 槽**退位**：提示从屏上撤下、队首被 promote 上屏
 *     （"没人应答就退位"，这正是 `OffloadPromptQueue.await` 的 finally 动作）；
 *  2. 排在**一张没人管的提示后面**的调用方 ⇒ 自己的预算到点就退位，而不是占着队列
 *     一直等到 `QUEUED_PROMPT_TIMEOUT_MS`（30s）；队列里不留僵尸槽（前面那张被答掉时
 *     不会 promote 出一个早已超时的提示）。
 *
 * 与 handler 源码的对应关系由 `SessionsOffloadPromptSlotTest` 的源码断言钉住：那处
 * `checkPermission` 必须包在同一个预算里。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionsOffloadSlotRetirementTest {

    private val budgetMs = OffloadPermissionManager.INTERACTIVE_BUDGET_MS

    /** UI 上那张提示的身份 —— 应答必须带回它（见 [OffloadPromptQueue.respond]）。 */
    private fun OffloadPromptQueue<String, String>.visibleId(): Long = pending.value!!.id

    @Test
    fun `an expired on-screen caller steps down and promotes the queue`() = runTest {
        val q = OffloadPromptQueue<String, String>()
        // 与 SessionsOffloadHandler 同形：预算内的 prompt await。
        val budgeted = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(budgetMs) { q.await("session_read") }
        }
        assertEquals("提示应该已经上屏", "session_read", q.pending.value?.request)

        val queued = async(start = CoroutineStart.UNDISPATCHED) { q.await("contacts") }
        assertEquals("单槽：后到者只能在队列里等", "session_read", q.pending.value?.request)

        advanceTimeBy(budgetMs + 1)
        runCurrent()

        assertNull("预算耗尽必须 fail-closed（interaction_required），而不是永久挂起", budgeted.await())
        assertEquals(
            "没人应答的那张必须退位，把槽让给队首（否则后面的调用方被饿死到 30s 队列超时）",
            "contacts",
            q.pending.value?.request,
        )
        assertTrue(q.respond(q.visibleId(), "allow"))
        assertEquals("allow", queued.await())
        assertNull(q.pending.value)
    }

    @Test
    fun `a caller queued behind an unattended prompt retires at its own budget`() = runTest {
        val q = OffloadPromptQueue<String, String>()
        // 无预算的调用方（在屏工具路径）把槽占住，且永远不答。
        val unattended = async(start = CoroutineStart.UNDISPATCHED) { q.await("session_lookup") }
        assertEquals("session_lookup", q.pending.value?.request)

        val budgeted = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(budgetMs) { q.await("session_read") }
        }
        assertEquals("槽仍被那张没人管的提示占着", "session_lookup", q.pending.value?.request)

        advanceTimeBy(budgetMs + 1)
        runCurrent()
        assertNull("排在没有预算的提示后面：本调用方在自己预算内失败，不拖到 30s", budgeted.await())
        assertEquals("在屏那张不受影响（用户可能正看着它）", "session_lookup", q.pending.value?.request)

        // 队列里不留僵尸槽：前面那张被答掉时不能 promote 出一个早已超时的提示。
        assertTrue(q.respond(q.visibleId(), "allow-once"))
        assertEquals("allow-once", unattended.await())
        assertNull("超时的排队槽必须已经撤走", q.pending.value)

        // 槽空出来之后，下一次调用（例如 agent 重试）立刻就能上屏。
        val retry = async(start = CoroutineStart.UNDISPATCHED) { q.await("session_read") }
        assertEquals("session_read", q.pending.value?.request)
        assertTrue(q.respond(q.visibleId(), "deny"))
        assertEquals("deny", retry.await())
    }

    @Test
    fun `the queue never holds an answer nobody is waiting for`() = runTest {
        val q = OffloadPromptQueue<String, String>()
        val budgeted = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(budgetMs) { q.await("session_read") }
        }
        val staleId = q.visibleId()
        advanceTimeBy(budgetMs + 1)
        runCurrent()
        assertNull(budgeted.await())
        assertNull("撤槽后 UI 上不该还挂着一张提示", q.pending.value)
        assertFalse("迟到的点击（旧身份）必须被丢弃", q.respond(staleId, "allow-session"))
    }
}
