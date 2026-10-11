package com.openminis.app.harness.checkpoint

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [T-c11-checkpoint-lock-order] 锁序回归：CheckpointStore 的锁序必须恒为
 * 「会话恢复锁 → 实例锁」。
 *
 * 旧实现把 `planCodeRewind` 标成 `@Synchronized`（**先取实例锁**），方法体内再调
 * `ensureRestored`（**先取会话恢复锁**）——全类唯一的反序取锁点。与
 * beginTurn / checkpoints / latestAfterImage / prefetch 的恢复路径（恢复锁 → 实例锁）
 * 交错时构成 AB-BA 死锁：planCodeRewind 持实例锁等恢复锁，对方持恢复锁等实例锁，
 * 双方永久挂死；死锁发生前的整段慢盘恢复里，实例锁也被占住，其它会话的全部
 * capture 被串行阻塞。
 *
 * 两个用例在旧代码下都会挂死/超时（线程一律 daemon：断言超时后测试 JVM 仍可退出）。
 */
class CheckpointStoreLockOrderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** 只在 [gatedSession] 的 readAll 上落闸；其它会话照常读盘（探针会用到）。 */
    private class GatedPersistence(
        private val real: CheckpointStore.Persistence,
        private val gatedSession: String,
    ) : CheckpointStore.Persistence {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val reads = AtomicInteger(0)

        override fun write(sessionId: String, checkpoint: Checkpoint) = real.write(sessionId, checkpoint)

        override fun delete(sessionId: String) = real.delete(sessionId)

        override fun readAll(sessionId: String): List<Checkpoint> {
            if (sessionId != gatedSession) return real.readAll(sessionId)
            reads.incrementAndGet()
            entered.countDown()
            gate.await()
            return real.readAll(sessionId)
        }
    }

    /**
     * 判据一：把会话恢复钉在读盘中间（该线程持有会话恢复锁），再让 planCodeRewind
     * 进来。修复后 planCodeRewind 只会等恢复锁、**不持有**实例锁——探针（另一会话的
     * capture）必须立刻成功，且放行读盘后两个线程都自行结束、回滚方案拿到恢复结果。
     * 旧实现下 planCodeRewind 先取实例锁（探针超时）再等恢复锁（AB-BA 死锁）。
     */
    @Test
    fun `planCodeRewind must not take the instance lock before the session restore lock`() {
        val root = temporaryFolder.newFolder("ckpt-lock-order")
        val real = FileCheckpointPersistence(root)
        // 磁盘上先落一轮 turn0：恢复之后 planCodeRewind 必须真有内容可回滚
        val seeder = CheckpointStore()
        seeder.persistence = real
        seeder.diskWriteExecutor = Executor { it.run() }
        seeder.beginTurn("s", "saved", null)
        seeder.capture("s", "a.txt", "before")
        seeder.beginTurn("s", "next", null) // 关闭 turn0 → 落盘

        val disk = GatedPersistence(real, "s")
        val store = CheckpointStore()
        store.persistence = disk
        store.diskWriteExecutor = Executor { it.run() }

        // 另一个会话：慢盘恢复期间实例锁必须保持可用（探针用）
        store.beginTurn("other", "p0")
        assertTrue(store.capture("other", "b.txt", "other-before"))

        // ① beginTurn("s") 的懒恢复被钉在读盘中间：持会话恢复锁，尚未取实例锁
        val beginFailure = AtomicReference<Throwable?>(null)
        val begin = Thread {
            runCatching { store.beginTurn("s", "turn-after-restore") }.onFailure { beginFailure.set(it) }
        }.apply { isDaemon = true; name = "ckpt-begin"; start() }
        assertTrue("beginTurn 未进入磁盘恢复", disk.entered.await(5, TimeUnit.SECONDS))

        // ② planCodeRewind("s") 必须卡在会话恢复锁上
        val planFailure = AtomicReference<Throwable?>(null)
        val plan = AtomicReference<List<FileSnap>?>(null)
        val planner = Thread {
            runCatching { store.planCodeRewind("s", 0) }
                .onSuccess { plan.set(it) }
                .onFailure { planFailure.set(it) }
        }.apply { isDaemon = true; name = "ckpt-plan"; start() }
        awaitBlockedOnRecoveryLock(planner)

        // ③ 探针：实例锁此刻必须空闲。旧实现里 ② 持实例锁等恢复锁 → 这里超时
        val probeDone = CountDownLatch(1)
        val probeCaptured = AtomicBoolean(false)
        Thread {
            probeCaptured.set(store.capture("other", "c.txt", "other-before-2"))
            probeDone.countDown()
        }.apply { isDaemon = true; name = "ckpt-probe"; start() }
        val probeFinished = probeDone.await(2, TimeUnit.SECONDS)

        // ④ 放行读盘：两个线程都必须自行结束（旧实现是 AB-BA 死锁，永久挂死）
        disk.gate.countDown()
        planner.join(10_000)
        begin.join(10_000)

        assertFalse(
            "planCodeRewind 与懒恢复死锁：前者持实例锁等恢复锁，后者持恢复锁等实例锁",
            planner.isAlive || begin.isAlive,
        )
        assertTrue("慢盘恢复期间实例锁被占住：其它会话的 capture 被串行阻塞", probeFinished)
        assertTrue("探针 capture 必须成功", probeCaptured.get())
        assertNull("planCodeRewind 不应抛异常", planFailure.get())
        assertNull("beginTurn 不应抛异常", beginFailure.get())
        assertEquals("恢复结果必须已经合并进回滚方案", listOf("a.txt"), plan.get()?.map { it.path })
        assertEquals("before", plan.get()?.first()?.content)
        assertEquals("恢复门语义不得因改锁序而丢失", listOf(0), store.checkpoints("s").map { it.turn })
        // 恢复门语义（T-p2-checkpointstore-restore-gate）：两个并发调用者复用同一次读盘
        assertEquals("并发调用者必须复用同一次读盘", 1, disk.reads.get())

        // 交错之后 store 仍然可用：新轮照常开启/关闭
        store.beginTurn("s", "after")
        store.capture("s", "d.txt", "d-after")
        store.endTurn("s")
        assertEquals(listOf(0, 1), store.checkpoints("s").map { it.turn })
    }

    /**
     * 判据二：[planCodeRewind] 与懒恢复在多个（冷）会话上真正交错：多线程
     * rewind 规划 + 轮次开关 + 撤销记录，不挂死、不抛异常、轮号不重复不倒退、
     * 回滚方案不出现未捕获路径（跨会话串味即可被检出）。
     */
    @Test
    fun `interleaved rewind planning and turn switches never deadlock`() {
        val root = temporaryFolder.newFolder("ckpt-lock-order-interleave")
        val store = CheckpointStore()
        store.persistence = FileCheckpointPersistence(root)

        val sessions = listOf("ix-0", "ix-1", "ix-2")
        val rounds = 30
        // 每个会话可能出现在回滚方案里的路径全集（会话独占命名 → 跨会话串味可检出）
        val universe = sessions.withIndex().associate { (index, session) ->
            session to (0 until rounds).map { "f-$index-$it.txt" }.toSet()
        }
        val failures = ConcurrentLinkedQueue<Throwable>()
        val start = CountDownLatch(1)
        val workers = mutableListOf<Thread>()

        sessions.forEachIndexed { index, session ->
            // 轮次线程：beginTurn → capture/captureAfterImage → 规划 → endTurn 交错
            workers += worker("ckpt-turn-$index", start, failures) {
                repeat(rounds) { round ->
                    store.beginTurn(session, "p-$round")
                    val path = "f-$index-$round.txt"
                    if (store.capture(session, path, "v-$index-$round")) {
                        store.captureAfterImage(session, path, "after-$index-$round")
                    }
                    store.planCodeRewind(session, round / 2)
                    store.recordRewindUndo(session, RewindUndoRecord(emptyList(), emptyList()))
                    store.takeRewindUndo(session)
                    store.endTurn(session)
                }
            }
            // 规划线程：懒恢复 + 规划 + 各类查询，全部与上面的轮次开关交错
            workers += worker("ckpt-plan-$index", start, failures) {
                repeat(rounds) { round ->
                    store.prefetch(session) // 冷会话首次访问会真的读盘（恢复锁路径）
                    val plan = store.planCodeRewind(session, round % 5)
                    val foreign = plan.map { it.path }.filterNot { it in universe.getValue(session) }
                    if (foreign.isNotEmpty()) {
                        failures.add(AssertionError("回滚方案含未捕获路径：$foreign"))
                    }
                    store.checkpoints(session)
                    store.anchorMessageIdOf(session, round % 5)
                    store.latestAfterImage(session, "f-$index-${round % 5}.txt")
                }
            }
        }

        start.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        workers.forEach { thread ->
            val remaining = deadline - System.nanoTime()
            if (remaining > 0) thread.join(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1))
        }

        val stuck = workers.filter { it.isAlive }.map { it.name }
        assertTrue("并发 rewind/plan 交错挂死：$stuck", stuck.isEmpty())
        assertTrue("并发期间抛出异常：${failures.firstOrNull()}", failures.isEmpty())
        for (session in sessions) {
            val turns = store.checkpoints(session).map { it.turn }
            assertTrue("$session 应至少有已关闭轮", turns.isNotEmpty())
            assertEquals("轮号必须严格升序且不重复（$session）", turns.sorted().distinct(), turns)
            assertTrue("$session 的 turn0 回滚方案不应为空", store.planCodeRewind(session, 0).isNotEmpty())
        }
    }

    /**
     * 等到 [thread] 卡在锁上（BLOCKED）。此刻它必然在等会话恢复锁（被上面的
     * beginTurn 持有，且尚未登记"已恢复"）。线程提前结束 = 场景不成立，直接判失败。
     */
    private fun awaitBlockedOnRecoveryLock(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            when (thread.state) {
                Thread.State.BLOCKED -> return
                Thread.State.TERMINATED -> fail("planCodeRewind 未在会话恢复锁上等待就返回了")
                else -> Thread.sleep(5)
            }
        }
        fail("planCodeRewind 未在会话恢复锁上等待（state=${thread.state}）")
    }

    private fun worker(
        threadName: String,
        startGate: CountDownLatch,
        failures: ConcurrentLinkedQueue<Throwable>,
        body: () -> Unit,
    ): Thread {
        val thread = Thread {
            startGate.await()
            runCatching(body).onFailure { failures.add(it) }
        }
        thread.isDaemon = true
        thread.name = threadName
        thread.start()
        return thread
    }
}
