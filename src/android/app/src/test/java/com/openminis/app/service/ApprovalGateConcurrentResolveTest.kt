package com.openminis.app.service

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * [D7][T-approval-single-winner] 审批胜出的**原子性**回归（`ApprovalGate.resolve`）。
 *
 * 旧实现把 `approvalDetails.remove(id)` 的返回值丢掉：两个真正并发的 approve/deny
 * 都能通过初查、都走到 `return true`；更糟的是『摘 details → 摘 pending 并写值』
 * 两步交错时，输家仍会摘到尚未被摘掉的 pending flow 并把自己的值写进去 —— 一次
 * **真实批准**被等待者当成拒绝（"User REJECTED" 回给模型、任务线终止）。
 *
 * 判据（读代码即可确认 + 本测试钉住）：
 *  ① 胜出 = 那一步原子的 `approvalDetails.remove(id) != null`；
 *  ② 同一请求的并发投票里**恰好一个**返回 `true`，落地即摘除（`detailOf == null`）；
 *  ③ 被唤醒的等待者收到的就是那个胜出者的一票（输家绝不写值）。
 *
 * 本测试是**压力型**回归：并发窗口只有几纳秒，单轮不一定撞上旧实现的交错，所以跑
 * [ROUNDS] 轮。正确实现下它恒过（原子摘除只可能有一个胜者），旧实现下大概率在某一
 * 轮出现两个 `true` 或"等待者收到输家那一票"。
 */
class ApprovalGateConcurrentResolveTest {

    @After
    fun cleanup() {
        ApprovalGate.cleanupAll()
    }

    @Test
    fun `concurrent approve and deny - exactly one lands and the waiter sees that vote`() {
        repeat(ROUNDS) { round ->
            val sid = "race-$round"
            val id = ApprovalGate.requestApproval(sid, "shell_execute", "rm -rf /tmp/x", mustPrompt = true)
            assertTrue("mustPrompt 的请求必须真的进待审批表", id.isNotEmpty())

            // 等待者先挂到 pending flow 上（SecurityGateHolder.intercept 的位置）。
            val waiterResult = AtomicReference<Boolean?>(null)
            val waiter = Thread { waiterResult.set(runBlocking { ApprovalGate.waitFor(id, sid) }) }
            waiter.isDaemon = true
            waiter.start()
            assertTrue(
                "等待者必须先挂在 flow.first 上，否则『等待者看到哪一票』的判据不成立",
                awaitParked(waiter),
            )

            val start = CountDownLatch(1)
            val winners = AtomicInteger(0)
            val winnerWasApprove = AtomicBoolean(false)
            val approve = Thread {
                start.await()
                if (ApprovalGate.approve(id, sid)) {
                    winners.incrementAndGet()
                    winnerWasApprove.set(true)
                }
            }
            val deny = Thread {
                start.await()
                if (ApprovalGate.deny(id, sid)) winners.incrementAndGet()
            }
            approve.isDaemon = true
            deny.isDaemon = true
            approve.start()
            deny.start()
            start.countDown()
            approve.join(5_000)
            deny.join(5_000)

            // 兜底：本轮若一个都没胜出（不该发生），别把等待者挂到 90s 超时才收场。
            if (winners.get() != 1) ApprovalGate.deny(id, sid)
            waiter.join(5_000)

            assertEquals(
                "并发的一对 approve/deny 必须恰好一个落地（旧实现两边都 return true）",
                1,
                winners.get(),
            )
            assertNull("落地即摘除，请求不得留在待审批表里", ApprovalGate.detailOf(id))
            assertFalse("等待者必须被唤醒，而不是卡到 90s 超时", waiter.isAlive)
            assertEquals(
                "等待者收到的必须是胜出者那一票（旧实现里输家也能写值 → 批准被当成拒绝）",
                winnerWasApprove.get(),
                waiterResult.get(),
            )
        }
    }

    @Test
    fun `a resolve that lost the race neither reports landed nor touches the landed value`() {
        val sid = "sequential-loser"
        val id = ApprovalGate.requestApproval(sid, "shell_execute", "rm -rf /tmp/x", mustPrompt = true)
        assertTrue(ApprovalGate.approve(id, sid))
        assertFalse("输家不得报落地（调用方据此决定是否清通知条目）", ApprovalGate.deny(id, sid))
        assertFalse("输家不得再次唤醒等待者", ApprovalGate.approve(id, sid))
        assertNull("输家不得把请求重新登记回待审批表", ApprovalGate.detailOf(id))
    }

    /** 轮询线程状态，确认等待者已经挂在 `flow.first` 上（而不是 sleep 一个固定时长）。 */
    private fun awaitParked(thread: Thread, budgetMs: Long = 2_000): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
        while (System.nanoTime() < deadline) {
            when (thread.state) {
                Thread.State.WAITING, Thread.State.TIMED_WAITING -> return true
                else -> if (!thread.isAlive) return false
            }
            Thread.sleep(1)
        }
        return false
    }

    private companion object {
        private const val ROUNDS = 64
    }
}
