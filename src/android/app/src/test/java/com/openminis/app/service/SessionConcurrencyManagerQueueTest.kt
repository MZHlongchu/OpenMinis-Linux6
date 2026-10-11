package com.openminis.app.service

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-slot-queue-overshoot] 排队 turn 超时/被取消时，它自己**没有**交还名额
 * （`had=false`），只是从队列里消失。旧实现用 `had || removedWaiting` 决定补位，
 * 于是每来一次超时/取消就把队首凭空塞进 `_runningSessions`：running 5 → 6，
 * 队列非空期间每次再 +1，`MAX_CONCURRENT` 被逐次突破（每个越额 turn 还会真的
 * 拉起 guest shell / 浏览器）。
 *
 * 这里钉住的是修复后的语义：只有真正释放了名额才补位；排队者的退出只清理队列。
 */
class SessionConcurrencyManagerQueueTest {

    @Test
    fun `a cancelled queue head must not admit the next waiter without a freed slot`() = runBlocking {
        val holders = (0 until SessionConcurrencyManager.MAX_CONCURRENT).map { "holder-$it" }
        holders.forEach { SessionConcurrencyManager.acquireSlot(it) }
        try {
            var secondAcquired = false
            val first = launch { SessionConcurrencyManager.acquireSlot("waiter-first") }
            val second = launch {
                SessionConcurrencyManager.acquireSlot("waiter-second")
                secondAcquired = true
            }
            assertTrue("waiter-first must reach the queue", awaitSuspended("waiter-first"))
            assertTrue("waiter-second must reach the queue", awaitSuspended("waiter-second"))

            // 队首的 turn 超时/被取消 —— 真实路径是 withTimeout 到期（或用户停掉
            // 排队中的 turn）→ invokeOnCancellation → releaseSlot(自己)。
            first.cancelAndJoin()

            assertEquals(
                "a waiter leaving the queue (had=false) frees no slot, so the cap must hold",
                SessionConcurrencyManager.MAX_CONCURRENT,
                SessionConcurrencyManager.runningSessions.value.size,
            )
            assertTrue(
                "the queue head must NOT be admitted by another waiter's cancellation",
                "waiter-second" !in SessionConcurrencyManager.runningSessions.value,
            )
            assertEquals(
                listOf("waiter-second"),
                SessionConcurrencyManager.suspendedSessions.value,
            )

            // 真正的名额释放仍然要补位（队列没有被这次取消搞坏）。
            SessionConcurrencyManager.releaseSlot(holders[0])
            assertTrue("a real release must still admit the queue head", awaitAcquired { secondAcquired })
            assertEquals(
                SessionConcurrencyManager.MAX_CONCURRENT,
                SessionConcurrencyManager.runningSessions.value.size,
            )
            second.cancelAndJoin()
        } finally {
            holders.forEach { SessionConcurrencyManager.releaseSlot(it) }
            SessionConcurrencyManager.releaseSlot("waiter-first")
            SessionConcurrencyManager.releaseSlot("waiter-second")
        }
        assertEquals(0, SessionConcurrencyManager.runningSessions.value.size)
        assertEquals(emptyList<String>(), SessionConcurrencyManager.suspendedSessions.value)
    }

    private suspend fun awaitSuspended(sessionId: String): Boolean {
        repeat(200) {
            if (SessionConcurrencyManager.isSuspended(sessionId)) return true
            delay(5)
        }
        return SessionConcurrencyManager.isSuspended(sessionId)
    }

    private suspend fun awaitAcquired(isAcquired: () -> Boolean): Boolean {
        repeat(200) {
            if (isAcquired()) return true
            delay(5)
        }
        return isAcquired()
    }
}
