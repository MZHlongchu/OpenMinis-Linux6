package com.openminis.app.mcp.client

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-mcp-inflight-lease] 工单 D9 残留(1)：**在途登记的时点**。
 *
 * 淘汰是"快照（[McpStdioScopePolicy.victims]）+ 逐项摘除"两步；旧实现里调用方在
 * `clientFor` 返回**之后**才 `beginScopeCall`，于是快照算完、`detachScope` 之前到达的同
 * 作用域调用只刷新了时间戳、还没进在途集合 —— 它命中的常驻进程被摘掉并 `close()`，调用方
 * 拿到 CONNECTION_ERROR。这里用"假缓存进程 + 真线程"把那个窗口直接摆出来：
 *  - [aLeaseTakenAfterTheVictimSnapshotIsNeverDetached]：工单里的交错顺序（决定在前）；
 *  - [aDetachThatWinsTheRaceDoesNotSwallowTheNextCall]：反过来的顺序（摘除在前）；
 *  - [acquireAndDetachCannotInterleave]：机制本身——取用与摘除在同一把锁上串行。
 */
class McpScopeLeaseTableTest {

    /** 一次"用完即闲置"的调用：拿到内存里的假进程、登记在途，然后结束。 */
    private fun useOnce(
        table: McpScopeLeaseTable,
        processes: ConcurrentHashMap<String, String>,
        scope: String,
        at: Long,
    ) {
        processes[scope] = "process-$scope"
        assertNotNull("命中缓存要成功: $scope", table.acquire(scope, at) { processes[scope] })
        table.release(scope)
    }

    /**
     * 工单里的顺序：淘汰方先算 victims（S 闲置、不忙 → 在名单里），随后同作用域的调用
     * 命中缓存并开始工作，最后淘汰方走到摘除。摘除必须整项跳过，进程一根汗毛都不能动。
     */
    @Test
    fun aLeaseTakenAfterTheVictimSnapshotIsNeverDetached() {
        val table = McpScopeLeaseTable()
        val processes = ConcurrentHashMap<String, String>()
        // 五个作用域都有常驻进程，S 闲置最久 → 它就是这一轮 victims 名单里的那一个
        useOnce(table, processes, "S", at = 0L)
        for (name in listOf("b", "c", "d", "e")) useOnce(table, processes, name, at = 900_000L)
        assertEquals(
            "S 闲置最久且在途计数为 0 → 决策阶段会被选中",
            listOf("S"),
            McpStdioScopePolicy.victims(
                lastUsedMs = table.lastUsedSnapshot(),
                keep = "e",
                nowMs = 1_000_000L,
                busyScopes = table.busyScopes(),
            ),
        )

        // 快照算完之后、摘除之前：同作用域的新调用命中缓存（旧实现在这一步只 touch 时间戳，
        // 登记要等 clientFor 返回之后 —— 那正是它被摘掉并 close 的窗口）
        assertNotNull("快照之后到达的调用要能命中缓存", table.acquire("S", 1_000_000L) { processes["S"] })

        var closed = false
        assertFalse(
            "在途调用正在用的作用域不能被摘掉（否则调用方拿到 CONNECTION_ERROR）",
            table.detachIfIdle("S") {
                closed = true
                processes.remove("S")
            },
        )
        assertFalse("进程不能被 close", closed)
        assertNotNull("缓存里的常驻进程必须还在", processes["S"])

        // 拒绝不是永久的：调用结束后重新可淘汰
        table.release("S")
        assertTrue("调用结束后摘除照常进行", table.detachIfIdle("S") { closed = true })
        assertTrue(closed)
    }

    /**
     * 反过来的顺序：摘除先赢，之后到达的调用未命中缓存 → 重建 → `publish` 安装并登记在途。
     * 新进程写在摘除之后，不在那一轮的摘除集合里，所以不会被误伤；而它自己又开始保护新进程
     * （在途期间再摘会被拒）。
     */
    @Test
    fun aDetachThatWinsTheRaceDoesNotSwallowTheNextCall() {
        val table = McpScopeLeaseTable()
        val processes = ConcurrentHashMap<String, String>()
        processes["S"] = "old-process"
        assertNotNull(table.acquire("S", 0L) { processes["S"] })
        table.release("S")

        val detached = mutableListOf<String>()
        assertTrue(
            "闲置作用域可以被摘掉",
            table.detachIfIdle("S") { processes.remove("S")?.let(detached::add) },
        )
        assertEquals(listOf("old-process"), detached)

        assertEquals(
            "重建路径安装的新进程",
            "new-process",
            table.publish("S", 5L) {
                processes["S"] = "new-process"
                "new-process"
            },
        )
        var closed = false
        assertFalse("在途期间不能再摘", table.detachIfIdle("S") { closed = true })
        assertFalse(closed)
        assertEquals("new-process", processes["S"])

        table.release("S")
        assertTrue(table.detachIfIdle("S") { processes.remove("S")?.let(detached::add) })
        assertEquals(listOf("old-process", "new-process"), detached)
    }

    /**
     * 机制本身：取用（`acquire` 的 lookup 在临界区内）与淘汰摘除必须串行。lookup 按住不放
     * 时，`detachIfIdle` 只能等锁，等到的结论是"这个作用域在途，整项跳过"。
     */
    @Test(timeout = 30_000)
    fun acquireAndDetachCannotInterleave() {
        val table = McpScopeLeaseTable()
        val processes = ConcurrentHashMap<String, String>(mapOf("S" to "process"))
        val inLookup = CountDownLatch(1)
        val holdLookup = CountDownLatch(1)
        val acquired = AtomicReference<String?>(null)
        val acquirer = thread(name = "lease-acquirer") {
            acquired.set(
                table.acquire("S", 10L) {
                    inLookup.countDown()
                    holdLookup.await(20, TimeUnit.SECONDS)
                    processes["S"]
                },
            )
        }
        assertTrue("取用要进入临界区", inLookup.await(20, TimeUnit.SECONDS))

        val detachStarted = CountDownLatch(1)
        val detached = AtomicReference<Boolean?>(null)
        val detacher = thread(name = "lease-detacher") {
            detachStarted.countDown()
            detached.set(table.detachIfIdle("S") { processes.remove("S") })
        }
        assertTrue(detachStarted.await(20, TimeUnit.SECONDS))
        // 给摘除线程一个"抢跑"的机会：它拿不到同一把锁，只能停在这里
        Thread.sleep(200)
        assertNull("摘除不能越过正在取用的临界区完成", detached.get())

        holdLookup.countDown()
        acquirer.join(20_000)
        detacher.join(20_000)
        assertEquals("process", acquired.get())
        assertEquals(false, detached.get())
        assertEquals("在途调用用的进程不能被摘掉", "process", processes["S"])
    }

    /** 同一作用域的并发调用：只要还有一个在途，就不许摘；最后一个结束才放行。 */
    @Test
    fun theScopeStaysBusyUntilTheLastCallFinishes() {
        val table = McpScopeLeaseTable()
        val processes = ConcurrentHashMap<String, String>(mapOf("S" to "process"))
        assertNotNull(table.acquire("S", 1L) { processes["S"] })
        assertNotNull("同作用域的第二个并发调用", table.acquire("S", 2L) { processes["S"] })
        table.release("S")
        assertFalse("还有一个在途调用 → 不能摘", table.detachIfIdle("S") {})
        table.release("S")
        assertTrue("两个都结束了 → 可摘", table.detachIfIdle("S") {})
        // 多余的 release 不会把计数带成负数、也不会重新把作用域标成忙
        table.release("S")
        assertTrue(table.busyScopes().isEmpty())
    }

    /** 会话结束走强制摘除：此刻的调用已经没有意义，进程必须收掉，名额必须放掉。 */
    @Test
    fun aTerminatedSessionIsDetachedEvenWithACallInFlight() {
        val table = McpScopeLeaseTable()
        val processes = ConcurrentHashMap<String, String>(mapOf("S" to "process"))
        assertNotNull(table.acquire("S", 1L) { processes["S"] })
        var closed = false
        table.detach("S") { closed = true }
        assertTrue("会话结束要强制摘除", closed)
        assertTrue("摘掉之后不再占 MAX_SCOPES 名额", table.lastUsedSnapshot().isEmpty())
        table.release("S")
        assertTrue(table.busyScopes().isEmpty())
        assertEquals("fresh", table.publish("S", 9L) { "fresh" })
        assertFalse("重建后仍在途 → 受保护", table.detachIfIdle("S") {})
    }

    /** 僵尸时间戳（已经没有缓存 client 的作用域）不能继续占名额。 */
    @Test
    fun zombieTimestampsAreDropped() {
        val table = McpScopeLeaseTable()
        val processes = ConcurrentHashMap<String, String>()
        useOnce(table, processes, "live", at = 1L)
        useOnce(table, processes, "zombie", at = 2L)
        table.retainScopes(setOf("live"))
        assertEquals(setOf("live"), table.lastUsedSnapshot().keys)
        table.clear()
        assertTrue(table.lastUsedSnapshot().isEmpty())
        assertTrue(table.busyScopes().isEmpty())
    }
}
