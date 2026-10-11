package com.openminis.app.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-prompt-launch-ledger] 系统权限对话框的按 launch 记账。
 *
 * 复现的旧行为（1:1）："这次结果属于哪张提示"记在 launcher 级单一可变字段上
 * （`MainActivity.launchedPermissionPromptId` / `ChatScreen.currentPermissionRequestRef`），
 * 两次重叠的 launch 里后一次覆写字段 —— 用户答 A，B 拿到 GRANTED。
 *
 * [AndroidPermissionLaunchLedger] 不引用任何 Android API，所以能在纯 JVM 上直接驱动；
 * [FakeVault] 与金库（`SingleFlightSlot`）同构：只认在屏那张的身份，旧 id 的应答一律丢弃。
 */
class AndroidPermissionLaunchLedgerTest {

    /** 金库替身：[attempts] 记下每一次回传（含被丢弃的），错配正是靠它才看得出来。 */
    private class FakeVault {
        var onScreen: PendingPrompt<String>? = null
        val attempts = mutableListOf<Pair<Long, String>>()
        val accepted = mutableListOf<Pair<Long, String>>()

        fun show(id: Long, request: String = "req-$id"): PendingPrompt<String> =
            PendingPrompt(id, request).also { onScreen = it }

        fun respond(id: Long, answer: String): Boolean {
            attempts += id to answer
            val current = onScreen
            if (current == null || current.id != id) return false
            onScreen = null
            accepted += id to answer
            return true
        }
    }

    private class Harness(
        var clock: Long = 0L,
        maxQueued: Int = MAX_QUEUED_PROMPTS,
        failFirstLaunch: Boolean = false,
    ) {
        val vault = FakeVault()
        val launches = mutableListOf<PendingPrompt<String>>()
        private val maxQueued: Int = maxQueued
        private var failures: Int = if (failFirstLaunch) 1 else 0

        var ledger: AndroidPermissionLaunchLedger<String, String> = newLedger()

        private fun newLedger() = AndroidPermissionLaunchLedger(
            maxQueued = maxQueued,
            currentPrompt = { vault.onScreen },
            launch = { prompt ->
                if (failures > 0) {
                    failures -= 1
                    throw IllegalStateException("activity not ready")
                }
                launches += prompt
            },
            deliver = { id, answer -> vault.respond(id, answer) },
            launchFailedResult = "DENIED",
            now = { clock },
        )

        /** Activity 重建：金库（进程级）还在，账本是新的、要靠 saved state 认领在飞身份。 */
        fun recreate() {
            ledger = newLedger()
        }

        /** 收集器：金库上屏一张新提示 → 登记进账本（与真实收集器同序）。 */
        fun publish(id: Long, request: String = "req-$id"): PendingPrompt<String> {
            val prompt = vault.show(id, request)
            ledger.submit(prompt)
            return prompt
        }

        /** 系统对话框回调。 */
        fun answer(answer: String = "GRANTED") = ledger.onResult { answer }

        fun launchedIds(): List<Long> = launches.map { it.id }
    }

    @Test
    fun `the answer belongs to the dialog that was launched, not to the newest prompt`() {
        val h = Harness()
        h.publish(1L, "location")
        // 第二次请求在第一次的对话框还没回来时到达 —— 旧实现就是在这里覆写身份字段的。
        h.publish(2L, "contacts")
        assertEquals("同一时刻只能有一个系统对话框在飞", listOf(1L), h.launchedIds())
        assertEquals(1L, h.ledger.inFlightPromptId)

        h.answer("GRANTED")
        // 用户答的是第一张：回传必须带 1（旧实现带的是被覆写的 2 → 用户答 A、B 拿到 GRANTED）。
        assertEquals(listOf(1L to "GRANTED"), h.vault.attempts)
        // 第一张在金库已经过期（在屏的是 2），这一答被丢弃：fail-closed，而不是把
        // "用户答 A"变成"B 拿到授权"。
        assertEquals(emptyList<Pair<Long, String>>(), h.vault.accepted)

        // 答完之后才派发下一张，而且弹的是它自己请求的权限。
        assertEquals(listOf(1L, 2L), h.launchedIds())
        assertEquals("contacts", h.launches[1].request)
        h.answer("DENIED")
        assertEquals(listOf(2L to "DENIED"), h.vault.accepted)
        assertEquals(0L, h.ledger.inFlightPromptId)
    }

    @Test
    fun `a prompt superseded while queued is not launched`() {
        val h = Harness()
        h.publish(1L, "location")
        h.publish(2L, "contacts")
        h.publish(3L, "microphone")
        assertEquals(listOf(1L), h.launchedIds())

        h.answer()
        // 2 的应答在金库一定会被丢弃（在屏的是 3）→ 不弹它，直接弹最新的 3。
        assertEquals(listOf(1L, 3L), h.launchedIds())
        assertEquals("microphone", h.launches.last().request)
        assertEquals(3L, h.ledger.inFlightPromptId)
    }

    @Test
    fun `an in-flight launch whose caller is gone does not wedge the queue`() {
        val h = Harness()
        h.publish(1L, "location")
        // 调用方超时/取消：金库空了。
        h.vault.onScreen = null
        // 收集器此刻递进来的仍是它读到的快照（金库已经不认它了）。
        h.ledger.submit(PendingPrompt(2L, "contacts"))

        assertEquals("没人能接收结果的在飞槽要立刻解封", 0L, h.ledger.inFlightPromptId)
        assertEquals("陈旧登记不该弹对话框", listOf(1L), h.launchedIds())

        // 解封之后新的提示照常上屏。
        h.publish(3L, "microphone")
        assertEquals(listOf(1L, 3L), h.launchedIds())
    }

    @Test
    fun `an in-flight launch older than the caller's own budget is released`() {
        val h = Harness()
        h.publish(1L, "location")
        h.publish(2L, "contacts")
        assertEquals("预算内仍然一次只弹一个", listOf(1L), h.launchedIds())

        // 1 的对话框一直没人点：超过调用方自己的等待预算之后，继续占着这一槽只会把后面的
        // 提示一起堵死（而那个调用方的 `withTimeoutOrNull` 早就到点了）。
        h.clock = OffloadPermissionManager.SYSTEM_DIALOG_TIMEOUT_MS + 1
        h.publish(3L, "microphone")
        assertEquals(listOf(1L, 3L), h.launchedIds())
        assertEquals(3L, h.ledger.inFlightPromptId)
    }

    @Test
    fun `a restored in-flight launch is claimed without launching a second dialog`() {
        val h = Harness()
        val pending = h.publish(7L, "location")
        assertEquals(7L, h.ledger.inFlightPromptId)

        // Activity 重建：金库（进程级单例）还在等同一张，账本是全新的 —— 靠 saved state 认领。
        h.recreate()
        assertTrue("金库还在等这一张 → 认领", h.ledger.restore(7L))
        assertEquals(7L, h.ledger.inFlightPromptId)

        // 收集器启动后会把同一张提示再登记一次 —— 不能因此再弹一个对话框。
        h.ledger.submit(pending)
        assertEquals(listOf(7L), h.launchedIds())

        // 系统补投回来的回调按认领到的身份回传。
        h.answer("GRANTED")
        assertEquals(listOf(7L to "GRANTED"), h.vault.accepted)
        assertFalse("同一个 id 不能重复认领", h.ledger.restore(7L))
    }

    @Test
    fun `a claimed launch whose result never comes does not wedge the queue forever`() {
        val h = Harness()
        h.publish(7L, "location")
        h.recreate()
        assertTrue(h.ledger.restore(7L))

        // 重建后系统既没有补投结果、用户也没再点那张提示：新的请求不能永远排在它后面。
        h.clock = OffloadPermissionManager.SYSTEM_DIALOG_TIMEOUT_MS + 1
        h.ledger.submit(h.vault.show(8L, "contacts"))
        assertEquals(listOf(7L, 8L), h.launchedIds())
        assertEquals(8L, h.ledger.inFlightPromptId)
    }

    @Test
    fun `an id the vault no longer waits for is never claimed`() {
        val h = Harness()
        assertFalse("进程死亡后金库是全新的", h.ledger.restore(7L))

        h.publish(9L, "contacts")
        assertEquals(listOf(9L), h.launchedIds())
        assertFalse("身份对不上（金库等的是 9）", h.ledger.restore(7L))
        assertEquals(9L, h.ledger.inFlightPromptId)
    }

    @Test
    fun `a failed launch answers the prompt instead of stalling the queue`() {
        val h = Harness(failFirstLaunch = true)
        h.publish(1L, "location")
        // launch 抛异常必须自己收口：fail-closed 回 DENIED，队列继续服务。
        assertEquals(listOf(1L to "DENIED"), h.vault.accepted)
        assertEquals(0L, h.ledger.inFlightPromptId)

        h.publish(2L, "contacts")
        assertEquals(listOf(2L), h.launchedIds())
    }

    @Test
    fun `the launch queue drops the oldest registration when it overflows`() {
        val h = Harness(maxQueued = 2)
        h.publish(1L)
        h.publish(2L)
        h.publish(3L)
        h.publish(4L)
        assertEquals(listOf(1L), h.launchedIds())

        h.answer()
        assertEquals("被挤掉的 2 不弹，在册的最新一张上屏", listOf(1L, 4L), h.launchedIds())
    }

    @Test
    fun `a result with no launch in flight is dropped instead of approving anything`() {
        val h = Harness()
        h.publish(1L)
        h.answer()                       // 第一张答完，槽空了
        h.answer("GRANTED")              // 重复/迟到的回调
        assertEquals(listOf(1L to "GRANTED"), h.vault.attempts)
        assertNull(h.vault.onScreen)
    }

    @Test
    fun `every requested permission must be granted`() {
        val requested = listOf("A", "B")
        assertTrue(AndroidPermissionOutcome.allGranted(requested, mapOf("A" to true, "B" to true)) { false })
        // 多权限只要缺一就是未授予 —— 旧聊天页那份 any 判据在这里会回 true（fail-open）。
        assertFalse(AndroidPermissionOutcome.allGranted(requested, mapOf("A" to true, "B" to false)) { false })
        assertFalse(AndroidPermissionOutcome.allGranted(requested, mapOf("A" to true)) { false })
        // 已经授予、因此不弹框的权限，有些版本根本不出现在 map 里（甚至是空 map）→ 回落实时状态。
        assertTrue(AndroidPermissionOutcome.allGranted(requested, emptyMap()) { true })
        assertFalse(AndroidPermissionOutcome.allGranted(requested, emptyMap()) { it == "A" })
        // 空请求不算"全部授予"（fail-closed）。
        assertFalse(AndroidPermissionOutcome.allGranted(emptyList(), emptyMap()) { true })
    }
}
