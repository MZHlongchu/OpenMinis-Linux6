package com.openminis.app.sandbox

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-d10-takeover-recheck] `ExecutionCoordinator.startOrReuseShell` 的 SUPERSEDED 分支：
 * 接管者（takeover）本身也在锁外窗口里，会被 `stopCurrentCommand` / `sessionDidTerminate`
 * （都不持 globalLock）摘走并 `stop()`。
 *
 * 旧实现 `takeover.ensureStarted(); return takeover` 在这个窗口里会把已经没人引用的实例
 * 重新 spawn 成一个**不在 `shells[]` 里的 proot**；调用方
 * `PersistentShell.executeCommand` 随后还会对它 `ensureStarted()` 一次——与原回归同型
 * （孤儿 proot + 同会话双壳）。修法：`ensureStarted()` 之后**再读一次注册表**，不是
 * takeover 就 `stop()` 掉它并返回 null，交给 `getOrCreateShell` 的重试路径。
 *
 * `startOrReuseShell` 要真机 Context 与真实 proot 才能跑（同 [ShellSupersedePolicyTest]
 * 的说明），所以这条判据按仓库惯例对源码断言：分支内必须"先 ensureStarted、再复核
 * 注册表、再决定返回"，且复核失败时既收进程又返回 null。
 */
class ShellSupersedeTakeoverRecheckTest {

    private val source =
        File("src/main/java/com/openminis/app/sandbox/ExecutionCoordinator.kt").readText()

    /** SUPERSEDED 分支的源码片段（到下一个 verdict 分支为止）。 */
    private val branch: String = source
        .substringAfter("Verdict.SUPERSEDED -> {")
        .substringBefore("ShellSupersedePolicy.Verdict.REGISTRY_EMPTY ->")

    @Test
    fun `the takeover branch still exists and starts the takeover`() {
        assertTrue(
            "SUPERSEDED 分支的判据变了，本测试需要跟着改:\n$branch",
            branch.contains("discarding the new instance"),
        )
        assertTrue("接管者仍要 ensureStarted（幂等）:\n$branch", branch.contains("takeover.ensureStarted()"))
    }

    @Test
    fun `the registry is re-read after ensureStarted`() {
        val ensureStarted = branch.indexOf("takeover.ensureStarted()")
        val recheck = branch.indexOf("val latest = shells[sessionId]")
        val verdict = branch.indexOf("ShellSupersedePolicy.verdict(latest, takeover)")

        assertTrue("ensureStarted 之后必须复核注册表:\n$branch", ensureStarted >= 0 && recheck > ensureStarted)
        assertTrue("复核必须按引用判定当前注册的是不是 takeover:\n$branch", verdict > recheck)
        assertTrue(
            "复核失败要按 REGISTERED 之外处理（非本实例一律不返回）:\n$branch",
            branch.contains("!= ShellSupersedePolicy.Verdict.REGISTERED"),
        )
    }

    @Test
    fun `a lost takeover is stopped and never handed back to the caller`() {
        val ensureStarted = branch.indexOf("takeover.ensureStarted()")
        // 同一坐标系（ensureStarted 之后的片段）里比较，别把两套下标混用。
        val afterEnsure = branch.substring(ensureStarted.coerceAtLeast(0))
        val recheck = afterEnsure.indexOf("val latest = shells[sessionId]")
        val firstReturn = afterEnsure.indexOf("return takeover")
        val stop = afterEnsure.indexOf("takeover.stop()")
        val nullReturn = afterEnsure.indexOf("return null")

        assertTrue(
            "ensureStarted 之后不得越过复核直接 return takeover（这正是原回归）:\n$branch",
            firstReturn > recheck,
        )
        assertTrue("复核失败必须先收掉那个已没人引用的实例:\n$branch", stop in (recheck + 1) until firstReturn)
        assertTrue(
            "复核失败必须返回 null 交给重试，且发生在收进程之后（不能把已停的壳交出去）:\n$branch",
            nullReturn in (stop + 1) until firstReturn,
        )
    }

    @Test
    fun `both lost-instance paths stop the orphan the same way`() {
        // REGISTRY_EMPTY 分支是 when 的最后一条，后面紧跟本函数的 KDoc。
        val emptyBranch = source
            .substringAfter("ShellSupersedePolicy.Verdict.REGISTRY_EMPTY -> {")
            .substringBefore("\n    /**")
        assertTrue("注册表已空：必须收掉刚起来的实例:\n$emptyBranch", emptyBranch.contains("shell.stop()"))
        assertTrue("注册表已空：必须返回 null 让调用方重试:\n$emptyBranch", emptyBranch.contains("return null"))
    }
}
