package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [T-b11-grep-regex-wall-clock] `grep` / `grep_source` 的 `is_regex=true` 墙钟兜底。
 *
 * java.util.regex 的灾难性回溯跑在 native 循环里：不计数、不响应 Thread.interrupt、
 * 也不响应协程取消，旧实现会把调用线程连同整个 agent 循环一起永久挂死。
 * 这里用一个**故意无视中断的自旋**模拟回溯（真 regex 在单测里烧起来会一直吃到
 * JVM 退出，且不可预期），验证的核心契约只有一条：预算用完必须把控制权交还调用方。
 *
 * [T-b11-grep-regex-budget] 第二组用例钉住弃车线程的**进程级预算**：墙钟只保证调用方
 * 不等，弃车线程还在烧核并持有该次扫描的 lines/out。同一个 pattern 重复调用必须短路
 * 拒绝，弃车线程数到顶后新的正则扫描也必须拒绝——而且这些拒绝都要发生在**起线程之前**。
 */
class GrepSourceWallClockTest {

    @Test
    fun `scan that outlives the wall clock is abandoned and reports timeout`() {
        val finished = AtomicBoolean(false)
        val startedAt = System.nanoTime()
        val result = GrepSourceTool.scanWithWallClock(200) {
            val deadline = System.nanoTime() + 1_500_000_000L
            while (System.nanoTime() < deadline) {
                // spin: ignores interruption exactly like the regex backtracker
            }
            finished.set(true)
            ToolExecutionResult("late", true)
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertNull("a scan past the wall clock must come back as null (timeout)", result)
        assertTrue("caller must regain control promptly, took ${elapsedMs}ms", elapsedMs < 1_500)
        assertFalse("the abandoned scan keeps burning its daemon thread", finished.get())
    }

    @Test
    fun `scan that finishes in budget returns its result unchanged`() {
        val result = GrepSourceTool.scanWithWallClock(10_000) {
            ToolExecutionResult("=== /w/a.kt ===\n>1: hit", true, toolTitle = "search")
        }
        assertEquals("=== /w/a.kt ===\n>1: hit", result?.output)
        assertEquals(true, result?.success)
        assertEquals("search", result?.toolTitle)
    }

    @Test
    fun `exception inside the scan is rethrown on the caller thread`() {
        // A StackOverflowError from a pathological pattern is the same shape:
        // whatever the scan throws must surface to the caller, not be swallowed
        // by the worker thread.
        assertThrows(IllegalStateException::class.java) {
            GrepSourceTool.scanWithWallClock(10_000) { error("boom") }
        }
    }

    // ---------- [T-b11-grep-regex-budget] 弃车线程的进程级预算 ----------

    @Test
    fun `a scan that finishes inside the wall clock releases its budget slot`() {
        val key = "unit-release-${System.nanoTime()}"
        val lease = GrepSourceTool.RegexScanBudget.acquire(key)
        assertNotNull("a fresh key must be admitted", lease)

        val result = GrepSourceTool.scanWithWallClock(10_000, lease) {
            ToolExecutionResult("ok", true)
        }

        assertEquals("ok", result?.output)
        // join 返回时工作线程已经终止，它的 finally 已经销账：这条路径不得占用预算。
        assertFalse(GrepSourceTool.RegexScanBudget.isBurning(key))
        val again = GrepSourceTool.RegexScanBudget.acquire(key)
        assertNotNull("the same key can run again", again)
        GrepSourceTool.RegexScanBudget.release(again!!)
    }

    @Test
    fun `a scan that outlives the wall clock books itself as abandoned and refuses a repeat`() {
        val key = "unit-abandon-${System.nanoTime()}"
        val lease = GrepSourceTool.RegexScanBudget.acquire(key)!!
        try {
            val result = GrepSourceTool.scanWithWallClock(50, lease) {
                val deadline = System.nanoTime() + 800_000_000L
                while (System.nanoTime() < deadline) {
                    // 模拟回溯：不响应中断
                }
                ToolExecutionResult("late", true)
            }

            assertNull(result)
            assertTrue(
                "a burning pattern must short-circuit a repeat without spawning a thread",
                GrepSourceTool.RegexScanBudget.isBurning(key),
            )
            assertNull(
                "and it must not be admitted a second time",
                GrepSourceTool.RegexScanBudget.acquire(key),
            )
        } finally {
            // 弃车线程结束后会自己销账；这里主动释放，避免污染其它用例。
            GrepSourceTool.RegexScanBudget.release(lease)
        }
        assertFalse(GrepSourceTool.RegexScanBudget.isBurning(key))
    }

    @Test
    fun `the abandoned-scan budget refuses new regex scans once it is exhausted`() {
        val base = GrepSourceTool.RegexScanBudget.abandonedCount()
        val keys = (0 until GrepSourceTool.RegexScanBudget.MAX_ABANDONED).map { "unit-budget-$it-${System.nanoTime()}" }
        val leases = keys.map { GrepSourceTool.RegexScanBudget.acquire(it) }
        assertTrue("all budget slots must be acquirable", leases.all { it != null })
        leases.forEach { GrepSourceTool.RegexScanBudget.markAbandoned(it!!) }
        try {
            assertTrue(
                "abandoned threads must be booked: ${GrepSourceTool.RegexScanBudget.abandonedCount()}",
                GrepSourceTool.RegexScanBudget.abandonedCount() >= base + GrepSourceTool.RegexScanBudget.MAX_ABANDONED,
            )
            assertNull(
                "at the cap, a new regex scan must be refused before any thread is spawned",
                GrepSourceTool.RegexScanBudget.acquire("unit-overflow-${System.nanoTime()}"),
            )
        } finally {
            leases.forEach { GrepSourceTool.RegexScanBudget.release(it!!) }
        }
        assertEquals(
            "released leases must be credited back",
            base,
            GrepSourceTool.RegexScanBudget.abandonedCount(),
        )
    }

    // ---------- [T-b11-grep-regex-slow-vs-backtrack] 文案区分 ----------

    @Test
    fun `nested quantifiers are told apart from a merely slow scan`() {
        assertTrue(GrepSourceTool.looksLikeCatastrophicBacktracking("(a+)+$"))
        assertTrue(GrepSourceTool.looksLikeCatastrophicBacktracking("(.*)*"))
        assertTrue(GrepSourceTool.looksLikeCatastrophicBacktracking("""(\w+\s?)*"""))
        assertTrue(GrepSourceTool.looksLikeCatastrophicBacktracking("(a+)*$"))
        // 单层量词只是「慢」，不该被说成回溯
        assertFalse(GrepSourceTool.looksLikeCatastrophicBacktracking("[a-z]+"))
        assertFalse(GrepSourceTool.looksLikeCatastrophicBacktracking("fun .*\\("))
        assertFalse(GrepSourceTool.looksLikeCatastrophicBacktracking("(a+)b"))
        assertFalse(GrepSourceTool.looksLikeCatastrophicBacktracking("TODO|FIXME"))
    }
}
