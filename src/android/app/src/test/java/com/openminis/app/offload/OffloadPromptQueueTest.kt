package com.openminis.app.offload

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-permission-prompt-queue] [T-android-prompt-identity] 提示槽的并发协议。
 *
 * [OffloadPromptQueue] / [SingleFlightSlot] 都不碰 Android API，所以能在纯 JVM
 * 上直接驱动（本模块没有 Robolectric，走 `OffloadPermissionManager.checkPermission`
 * 会先碰到 `SharedPreferences`）。
 *
 * 旧实现是"单槽 Continuation"：① 后到者覆盖先到者 → 先到者永久挂起；
 * ② 先到者超时/被取消时无条件清空整个槽 → 后到者的对话框一起消失。
 *
 * 应答身份（③）是第二轮补的：不带身份的 `respond(answer)` 会把"用户对旧提示的
 * 迟到点击"投给已经 promote 上屏的**下一张**提示 —— 用户没读过的工具白拿
 * ALLOW_SESSION（fail-open）。所以每个槽有自己的 id，UI 必须回传它。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OffloadPromptQueueTest {

    private fun queue(maxQueued: Int = MAX_QUEUED_PROMPTS) =
        OffloadPromptQueue<String, String>(maxQueued = maxQueued)

    /** UI 上那张提示的身份 —— 应答必须带回它。 */
    private fun OffloadPromptQueue<String, String>.visibleId(): Long = pending.value!!.id

    @Test
    fun `only one prompt is on screen and every caller keeps its own answer`() = runTest {
        val q = queue()
        // UNDISPATCHED：body 跑到挂起点（提示上屏）才交还控制权，断言不依赖 sleep。
        val first = async(start = CoroutineStart.UNDISPATCHED) { q.await("contacts") }
        assertEquals("contacts", q.pending.value?.request)

        val second = async(start = CoroutineStart.UNDISPATCHED) { q.await("calendar") }
        // 旧实现：后到者覆盖单槽 → 这里读到 "calendar"，而先到者再也没有 resume 来源。
        assertEquals("contacts", q.pending.value?.request)

        assertTrue(q.respond(q.visibleId(), "allow-once"))
        assertEquals("allow-once", first.await())
        // 先到者答完，第二张才上屏 —— 内容是第二个调用者自己的请求，不是被顶掉的。
        assertEquals("calendar", q.pending.value?.request)

        assertTrue(q.respond(q.visibleId(), "deny"))
        assertEquals("deny", second.await())
        assertNull(q.pending.value)
    }

    @Test
    fun `cancelling the visible caller promotes the queued one`() = runTest {
        val q = queue()
        val visible = async(start = CoroutineStart.UNDISPATCHED) { q.await("contacts") }
        val queued = async(start = CoroutineStart.UNDISPATCHED) { q.await("calendar") }
        assertEquals("contacts", q.pending.value?.request)

        // 模拟 OffloadGate 的 15s 预算超时：轮到 UI 的那次被取消。
        visible.cancelAndJoin()
        // 旧实现：取消时无条件清空整个槽 → 后到者的对话框一起消失，用户想批准
        // 也无从批准，只能等自己的超时。
        assertEquals("calendar", q.pending.value?.request)

        assertTrue(q.respond(q.visibleId(), "allow"))
        assertEquals("allow", queued.await())
        assertNull(q.pending.value)
    }

    @Test
    fun `an answer for a withdrawn prompt never approves the promoted one`() = runTest {
        val q = queue()
        val first = async(start = CoroutineStart.UNDISPATCHED) { q.await("contacts") }
        // 用户眼前那张是 contacts —— 它的身份只对这张有效。
        val staleId = q.visibleId()
        val second = async(start = CoroutineStart.UNDISPATCHED) { q.await("calendar") }
        assertEquals("contacts", q.pending.value?.request)

        // 15s 预算到点：在屏那张被撤下，队首 promote 上屏。
        first.cancelAndJoin()
        assertEquals("calendar", q.pending.value?.request)

        // 用户的手指还停在旧对话框上。迟到的"允许"带的是旧身份，必须被丢弃：
        // 旧实现会把它投给 calendar，让用户没读过的工具拿到 ALLOW_SESSION。
        assertFalse(q.respond(staleId, "allow-session"))
        assertEquals("calendar", q.pending.value?.request)

        // 新提示仍在等真正的应答。
        assertTrue(q.respond(q.visibleId(), "deny"))
        assertEquals("deny", second.await())
        assertNull(q.pending.value)
    }

    @Test
    fun `a repeated tap on the same prompt cannot approve two tools`() = runTest {
        val q = queue()
        val first = async(start = CoroutineStart.UNDISPATCHED) { q.await("contacts") }
        val second = async(start = CoroutineStart.UNDISPATCHED) { q.await("calendar") }
        val id = q.visibleId()

        assertTrue(q.respond(id, "allow-session"))
        assertEquals("allow-session", first.await())
        assertEquals("calendar", q.pending.value?.request)

        // 同一次点击的重复回调（双击、重组前挂着的旧 lambda）带的还是旧 id：
        // 旧实现会顺手把刚上屏的 calendar 也批准掉。
        assertFalse(q.respond(id, "allow-session"))
        runCurrent()
        assertFalse("calendar 不该被旧 id 的重复应答批准", second.isCompleted)

        assertTrue(q.respond(q.visibleId(), "deny"))
        assertEquals("deny", second.await())
        assertNull(q.pending.value)
    }

    @Test
    fun `a late tap after withdrawal is a no-op`() = runTest {
        val q = queue()
        val withdrawn = async(start = CoroutineStart.UNDISPATCHED) { q.await("contacts") }
        val staleId = q.visibleId()
        withdrawn.cancelAndJoin()
        assertNull(q.pending.value)

        // 对话框迟到的一次点击（槽早已撤下）：不得抛异常，也不得落到下一张提示上。
        assertFalse(q.respond(staleId, "allow"))
        assertNull(q.pending.value)

        val next = async(start = CoroutineStart.UNDISPATCHED) { q.await("photos") }
        assertEquals("photos", q.pending.value?.request)
        // 身份单调递增：新提示不会复用旧 id，旧的点击不可能命中它。
        assertTrue(q.visibleId() > staleId)
        assertTrue(q.respond(q.visibleId(), "allow-once"))
        assertEquals("allow-once", next.await())
        assertNull(q.pending.value)
    }

    @Test
    fun `a prompt queued behind an unanswered one fails closed`() = runTest {
        val q = queue()
        val owner = async(start = CoroutineStart.UNDISPATCHED) { q.await("contacts") }
        val queued = async(start = CoroutineStart.UNDISPATCHED) { q.await("calendar") }
        assertEquals("contacts", q.pending.value?.request)

        advanceTimeBy(QUEUED_PROMPT_TIMEOUT_MS + 1)
        runCurrent()
        // fail-closed，而不是被前面那张没人回答的提示无限期挂住。
        assertNull(queued.await())
        // 已经在屏上的那张不受影响（用户可能正看着它）。
        assertEquals("contacts", q.pending.value?.request)

        assertTrue(q.respond(q.visibleId(), "allow"))
        assertEquals("allow", owner.await())
        assertNull(q.pending.value)
    }

    @Test
    fun `the pending queue is bounded`() = runTest {
        assertEquals(8L, MAX_QUEUED_PROMPTS.toLong())

        val q = queue(maxQueued = 2)
        val active = async(start = CoroutineStart.UNDISPATCHED) { q.await("t0") }
        val queued = listOf("t1", "t2").map { t ->
            async(start = CoroutineStart.UNDISPATCHED) { q.await(t) }
        }
        // 队首 + 满队列：再来一个直接 fail-closed，不再排队（agent 重试循环不能把
        // 提示堆成无界内存）。
        assertNull(q.await("overflow"))
        assertEquals("t0", q.pending.value?.request)

        assertTrue(q.respond(q.visibleId(), "deny"))
        assertEquals("deny", active.await())
        for (i in queued.indices) {
            assertEquals("t${i + 1}", q.pending.value?.request)
            assertTrue(q.respond(q.visibleId(), "deny"))
            assertEquals("deny", queued[i].await())
        }
        assertNull(q.pending.value)
    }

    @Test
    fun `single-flight slot clears only its own registration`() = runTest {
        val slot = SingleFlightSlot<String, String>()
        val first = async(start = CoroutineStart.UNDISPATCHED) { slot.await("system-dialog") }
        assertEquals("system-dialog", slot.pending.value?.request)
        val firstId = slot.pending.value!!.id

        // 后来者占槽：系统对话框一次只弹得出一个，这一层行为保持不变。
        val second = async(start = CoroutineStart.UNDISPATCHED) { slot.await("settings-gate") }
        assertEquals("settings-gate", slot.pending.value?.request)
        val secondId = slot.pending.value!!.id
        assertTrue("新槽不复用旧身份", secondId > firstId)

        // 先到者超时/被取消：旧实现无条件清空整个槽 → 后到者刚挂上的对话框一起消失。
        first.cancelAndJoin()
        assertEquals("settings-gate", slot.pending.value?.request)

        // [T-android-prompt-identity] 用户手里那个旧对话框此刻才点的按钮带的是**旧身份**：
        // 必须丢弃 —— 旧实现会 resume 已经占槽的后来者（用户答 A、B 拿到 GRANTED）。
        assertFalse(slot.respond(firstId, "granted"))
        runCurrent()
        assertFalse("后来者不能被旧身份的应答唤醒", second.isCompleted)
        assertEquals("settings-gate", slot.pending.value?.request)

        assertTrue(slot.respond(secondId, "cancel"))
        assertEquals("cancel", second.await())
        assertNull(slot.pending.value)
    }

    @Test
    fun `a repeated or late single-flight answer is dropped`() = runTest {
        val slot = SingleFlightSlot<String, String>()
        val only = async(start = CoroutineStart.UNDISPATCHED) { slot.await("system-dialog") }
        val id = slot.pending.value!!.id

        assertTrue(slot.respond(id, "granted"))
        assertEquals("granted", only.await())
        assertNull(slot.pending.value)

        // 对话框的重复回调 / 迟到回调：槽已空，丢弃而不是抛异常。
        assertFalse(slot.respond(id, "granted"))
        assertFalse(slot.respond(id + 1, "granted"))
        assertNull(slot.pending.value)
    }

    @Test
    fun `an on-screen prompt nobody answers steps aside and fails closed`() = runTest {
        val q = queue()
        val held = async(start = CoroutineStart.UNDISPATCHED) { q.await("contacts") }
        assertEquals("contacts", q.pending.value?.request)

        // [T-android-prompt-starvation] 无预算的调用方（在屏的 SessionLookupTool）以前会
        // 永久占着这一槽；到点退位后它拿到的是"没人应答"（null），不是"用户拒绝"。
        advanceTimeBy(ACTIVE_PROMPT_TIMEOUT_MS + 1)
        runCurrent()
        assertNull(held.await())
        assertNull(q.pending.value)

        // 退位之后队列继续服务。
        val next = async(start = CoroutineStart.UNDISPATCHED) { q.await("calendar") }
        assertEquals("calendar", q.pending.value?.request)
        assertTrue(q.respond(q.visibleId(), "allow"))
        assertEquals("allow", next.await())
        assertNull(q.pending.value)
    }

    @Test
    fun `a stepped-aside prompt promotes the caller queued behind it`() = runTest {
        val q = OffloadPromptQueue<String, String>(queuedTimeoutMs = 600_000L, activeTimeoutMs = 10_000L)
        val stuck = async(start = CoroutineStart.UNDISPATCHED) { q.await("contacts") }
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { q.await("calendar") }
        assertEquals("contacts", q.pending.value?.request)

        advanceTimeBy(10_001L)
        runCurrent()
        assertNull(stuck.await())
        // 没人应答的那张退位 → 排队的人上屏（不是一起被清掉）。
        assertEquals("calendar", q.pending.value?.request)
        assertTrue(q.respond(q.visibleId(), "deny"))
        assertEquals("deny", waiting.await())
        assertNull(q.pending.value)
    }
}
