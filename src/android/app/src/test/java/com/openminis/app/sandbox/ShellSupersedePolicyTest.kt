package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-b10-superseded-null] 回归判据：`ExecutionCoordinator.startOrReuseShell` 在锁外
 * spawn 结束后必须只看注册表当前引用了谁。
 *
 * 旧实现在"注册表已空"时先 `shell.stop()` 再 `return shell`：调用方
 * `PersistentShell.executeCommand` 会 `ensureStarted()`，把这个已停的壳重新 spawn 成
 * 一个不在 `shells[]` 里的 proot（孤儿进程，`stopCurrentCommand` / `sessionDidTerminate`
 * 都找不到它），同一会话的下一条命令还会再建第二个壳。
 */
class ShellSupersedePolicyTest {

    @Test
    fun registryStillHoldingOurInstanceIsTheNormalPath() {
        val mine = "shell-instance"
        assertEquals(
            ShellSupersedePolicy.Verdict.REGISTERED,
            ShellSupersedePolicy.verdict(mine, mine),
        )
    }

    @Test
    fun anotherInstanceInTheRegistryIsATakeover() {
        assertEquals(
            ShellSupersedePolicy.Verdict.SUPERSEDED,
            ShellSupersedePolicy.verdict("someone-else", "mine"),
        )
    }

    /** 回归本体：注册表为空 ⇒ 重试，绝不等于"可以直接返回这个壳"。 */
    @Test
    fun emptyRegistryMeansRetryNotReturn() {
        val verdict = ShellSupersedePolicy.verdict<String>(null, "mine")
        assertEquals(ShellSupersedePolicy.Verdict.REGISTRY_EMPTY, verdict)
        assertTrue(
            "只有 REGISTERED / SUPERSEDED 才能把壳交给调用方",
            verdict != ShellSupersedePolicy.Verdict.REGISTERED &&
                verdict != ShellSupersedePolicy.Verdict.SUPERSEDED,
        )
    }

    @Test
    fun verdictComparesIdentityNotEquality() {
        // 内容相同但不是同一个对象：两把"看起来一样"的壳必须按引用区分
        // （StringBuilder 没有覆写 equals，正好用来代表"另一个实例"）。
        val mine = StringBuilder("same")
        val twin = StringBuilder("same")
        assertEquals(mine.toString(), twin.toString())
        assertEquals(ShellSupersedePolicy.Verdict.SUPERSEDED, ShellSupersedePolicy.verdict(twin, mine))
    }

    @Test
    fun attemptBudgetIsBoundedAndFailureIsExplicit() {
        assertTrue("重试必须有界，否则 stop 争用会变成活锁", ShellSupersedePolicy.MAX_START_ATTEMPTS in 2..8)
        val ex = ShellSupersedePolicy.exhausted("session-42", ShellSupersedePolicy.MAX_START_ATTEMPTS)
        val msg = ex.message.orEmpty()
        assertTrue("失败信息要能定位到会话：$msg", msg.contains("session-42"))
        assertTrue("失败信息要说明原因（注册表反复被摘）：$msg", msg.contains("registry"))
        assertTrue("失败信息要点明拒绝返回已停的壳：$msg", msg.contains("stopped shell"))
        assertTrue("失败计数要写进信息，便于和日志对齐：$msg", msg.contains("${ShellSupersedePolicy.MAX_START_ATTEMPTS}"))
    }
}
