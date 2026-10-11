package com.openminis.app.harness.checkpoint

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-d10-restore-single-flight] 同会话的磁盘懒恢复必须是 single-flight，**失败路径也一样**。
 *
 * 旧实现两处都会让同会话出现两把恢复锁：
 *  1. `restoreLocks.getOrPut(...)` 的"读-改-写"不是原子的（本仓其它处同样踩过），
 *     两个并发调用者可能各建一把锁；
 *  2. `finally` 无条件摘锁：恢复失败后后来者拿到一把**新**锁，与仍在重试的调用者
 *     并发真读盘。
 * 两次 readAll 的结果只要新旧不同（磁盘上刚多了一轮），合并段就会把更新的轮插到更旧的
 * 轮前面：`checkpoints` 的轮号升序不变量被破坏，而 `MAX_KEPT` 裁剪是 `removeAt(0)`
 * （丢最旧）——顺序一乱就把**最新**的那一轮丢掉。
 *
 * 判据都在本文件里：失败后锁必须留在表里（[CheckpointStore.holdsRestoreLock]）、
 * 成功后必须释放、drop 必须释放，且并发读盘的"同时在飞"峰值只能是 1。
 * 修复后这些断言都是确定性的（旧的 getOrPut/摘锁版本下第二条是概率性的）。
 *
 * 顶层 `checkpointFixture` 而不是类内 helper：`FakePersistence` 是嵌套类，
 * 看不到外层实例成员（与 `CheckpointStore` 无关，纯 Kotlin 可见性）。
 */
class CheckpointRestoreLockTest {

    /** 内存持久化：第 [failFirstReads] 次 readAll 抛异常，其余返回 [turns]。 */
    private class FakePersistence(
        private val turns: List<Int>,
        private val failFirstReads: Int = 0,
        private val readDelayMs: Long = 0L,
    ) : CheckpointStore.Persistence {
        val reads = AtomicInteger(0)
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)

        override fun write(sessionId: String, checkpoint: Checkpoint) = Unit

        override fun readAll(sessionId: String): List<Checkpoint> {
            val now = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { maxOf(it, now) }
            try {
                val attempt = reads.incrementAndGet()
                if (readDelayMs > 0) Thread.sleep(readDelayMs)
                if (attempt <= failFirstReads) throw java.io.IOException("disk hiccup #$attempt")
                return turns.map { checkpointFixture(it) }
            } finally {
                inFlight.decrementAndGet()
            }
        }

        override fun delete(sessionId: String) = Unit
    }

    @Test
    fun `a failed restore keeps its session lock so the retry cannot race a second reader`() {
        val store = CheckpointStore()
        val disk = FakePersistence(turns = listOf(0), failFirstReads = 1)
        store.persistence = disk

        assertTrue("恢复失败时按原语义返回空态", store.checkpoints("s").isEmpty())
        assertTrue(
            "恢复失败的会话必须钉住同一把恢复锁：旧实现在 finally 里无条件摘锁，" +
                "后来者会拿到新锁并与重试者并发读盘",
            store.holdsRestoreLock("s"),
        )

        assertEquals("第二次访问必须重试恢复", 1, store.checkpoints("s").size)
        assertFalse(
            "恢复成功后必须释放锁，锁表不能随访问过的会话数无界增长",
            store.holdsRestoreLock("s"),
        )
        assertEquals(2, disk.reads.get())
        assertEquals("同会话的两次读盘不得重叠", 1, disk.maxInFlight.get())
    }

    @Test
    fun `concurrent callers share one restore even after a failed attempt`() {
        val store = CheckpointStore()
        val disk = FakePersistence(turns = listOf(0, 1), failFirstReads = 1, readDelayMs = 20L)
        store.persistence = disk

        val start = CountDownLatch(1)
        val workers = (0 until 8).map {
            Thread {
                start.await()
                store.checkpoints("s")
            }.apply {
                isDaemon = true
                name = "ckpt-restore-$it"
                start()
            }
        }
        start.countDown()
        workers.forEach { it.join(10_000) }

        assertEquals(
            "同会话的磁盘恢复必须串行：旧实现（非原子 getOrPut + 失败即摘锁）会让两把锁" +
                "同时读盘，合并顺序随之出错",
            1,
            disk.maxInFlight.get(),
        )
        assertTrue("失败后必须有人重试", disk.reads.get() >= 2)
        assertEquals("恢复出来的轮必须按轮号升序", listOf(0, 1), store.checkpoints("s").map { it.turn })
    }

    @Test
    fun `dropping a session releases its restore lock`() {
        val store = CheckpointStore()
        store.persistence = FakePersistence(turns = listOf(0), failFirstReads = Int.MAX_VALUE)

        assertTrue(store.checkpoints("s").isEmpty())
        assertTrue("一直失败的会话会钉住锁（重试要串行）", store.holdsRestoreLock("s"))

        store.dropSession("s")
        assertFalse("已删除的会话不该继续钉着恢复锁", store.holdsRestoreLock("s"))
        assertTrue("drop 之后不得再从盘上恢复旧轮", store.checkpoints("s").isEmpty())
    }

    @Test
    fun `a session with no persistence never pins a restore lock`() {
        val store = CheckpointStore()
        assertTrue(store.checkpoints("s").isEmpty())
        assertFalse("纯内存模式下不涉及磁盘恢复，不该建锁", store.holdsRestoreLock("s"))
    }
}

/** 一轮带单文件快照的 checkpoint，供 [CheckpointRestoreLockTest] 的假持久化使用。 */
private fun checkpointFixture(turn: Int) = Checkpoint(
    turn = turn,
    time = turn.toLong(),
    prompt = "p$turn",
    files = listOf(FileSnap("f$turn.txt", "before-$turn")),
    anchorMessageId = null,
)
