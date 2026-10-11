package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-b18-pid-reuse] 回归判据：`SandboxWorkload` 的 confine 差集记的必须是**进程身份**
 * （pid + `/proc/<pid>/stat` starttime），不能是裸 pid。
 *
 * 旧实现的失败面：pid 在两次 sweep（400ms）之间被回收时，复用同一 pid 的新子进程会
 * 命中"上一轮已 confine"的旧结论，于是它整条命都不进后台 cgroup、优先级也不降——
 * 差集优化成了一条静默绕过资源策略的通道。
 */
class ConfinementLedgerTest {

    private val pid = 4242

    private fun decide(
        previous: Map<Int, Long>,
        current: Set<Int>,
        owned: Set<Int>,
        startTime: Map<Int, Long>,
    ) = ConfinementLedger.decide(previous, current, owned, startTime)

    @Test
    fun sameProcessIsConfinedExactlyOnce() {
        val first = decide(emptyMap(), setOf(pid), setOf(pid), mapOf(pid to 100L))
        assertEquals(listOf(pid), first.toConfine)
        assertEquals(100L, first.confined.getValue(pid))

        val second = decide(first.confined, setOf(pid), setOf(pid), mapOf(pid to 100L))
        assertTrue("同一个进程不该被反复 confine（差集优化本身要保住）", second.toConfine.isEmpty())
        assertEquals(100L, second.confined.getValue(pid))
    }

    /** 回归本体：pid 被回收给本树的新子进程后必须重新 confine。 */
    @Test
    fun recycledPidInsideTheTreeIsConfinedAgain() {
        val seen = mapOf(pid to 100L)
        val after = decide(seen, current = setOf(pid), owned = setOf(pid), startTime = mapOf(pid to 200L))
        assertEquals("starttime 变了就是另一个进程，必须重新 confine", listOf(pid), after.toConfine)
        assertEquals(200L, after.confined.getValue(pid))
    }

    /** 反向误伤：只在 sticky 记忆里、身份已变的 pid 已经不是我们的进程。 */
    @Test
    fun recycledPidOutsideTheTreeIsLeftAlone() {
        val seen = mapOf(pid to 100L)
        val after = decide(seen, current = emptySet(), owned = setOf(pid), startTime = mapOf(pid to 200L))
        assertTrue("不能去改一个陌生进程的 cgroup / 优先级", after.toConfine.isEmpty())
        assertFalse("身份不可证就不该继续记账", after.confined.containsKey(pid))
    }

    @Test
    fun reparentedSameProcessKeepsItsRecordWithoutReconfining() {
        val seen = mapOf(pid to 100L)
        // 已 setsid / 被 reparent：不在本轮树里，但身份没变。
        val after = decide(seen, current = emptySet(), owned = setOf(pid), startTime = mapOf(pid to 100L))
        assertTrue(after.toConfine.isEmpty())
        assertEquals(100L, after.confined.getValue(pid))
    }

    @Test
    fun vanishedPidDropsOutOfTheLedger() {
        val seen = mapOf(pid to 100L, 5 to 1L)
        val after = decide(seen, current = setOf(pid), owned = setOf(pid), startTime = mapOf(pid to 100L))
        assertTrue(after.toConfine.isEmpty())
        assertFalse("pid 消失后必须立刻从账本里摘掉", after.confined.containsKey(5))
    }

    @Test
    fun unreadableStartTimeReconfinesConservatively() {
        val after = decide(
            previous = mapOf(pid to 100L),
            current = setOf(pid),
            owned = setOf(pid),
            // hidepid 限制 / 进程刚消失：拿不到身份。
            startTime = emptyMap(),
        )
        assertEquals("身份读不到就退回每轮 confine，不能凭旧结论跳过", listOf(pid), after.toConfine)
        assertTrue("身份未知不记账，下一轮继续尝试", after.confined.isEmpty())
    }

    @Test
    fun freshlySeenDescendantIsConfinedWithoutPreviousRecord() {
        val after = decide(emptyMap(), current = setOf(9), owned = setOf(9), startTime = mapOf(9 to 5L))
        assertEquals(listOf(9), after.toConfine)
        assertEquals(5L, after.confined.getValue(9))
    }

    // ── 身份来源：/proc/<pid>/stat 的字段下标 ──────────────────────────────
    //
    // 账本的正确性完全取决于 starttime 解析（字段下标 22 - 3 = 19）。算错的后果是
    // 静默的：偏左会退化成"每轮都 confine"（白烧 IO），偏右会读到别的字段、身份
    // 永远对不上或永远对上，两种都不会报错，所以这里把真实格式钉住。

    @Test
    fun starttimeParsesField22WithSpacesAndParenthesesInComm() {
        // 字段：pid (comm) state ppid pgrp session ... itrealvalue(21) starttime(22)
        val stat = "1234 (python 3) S 1 1234 1234 0 -1 4194560 1000 0 0 0 1 2 0 0 20 0 3 0 987654321 42 500"
        assertEquals(987654321L, SandboxWorkload.statStartTime(stat))
    }

    @Test
    fun starttimeHandlesCommContainingClosingParenthesis() {
        // 内核不会把 `)` 转义，所以必须按**最后一个** `)` 定位 comm 的结尾。
        val stat = "77 (sh -c echo \") \") S 1 77 77 0 -1 0 0 0 0 0 0 0 0 0 20 0 1 0 555 1 2"
        assertEquals(555L, SandboxWorkload.statStartTime(stat))
    }

    @Test
    fun truncatedOrMissingStatYieldsNull() {
        assertNull("空串不能抛异常", SandboxWorkload.statStartTime(""))
        assertNull("comm 之后没有字段", SandboxWorkload.statStartTime("1234 (bash)"))
        assertNull("字段不够 22 个（进程刚消失时可能只读到前缀）", SandboxWorkload.statStartTime("12 (x) S 1 12 12"))
        assertNull("starttime 不是数字", SandboxWorkload.statStartTime("12 (x) S 1 12 12 0 -1 0 0 0 0 0 0 0 0 0 20 0 1 0 abc"))
    }
}
