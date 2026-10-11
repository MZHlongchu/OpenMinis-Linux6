package com.openminis.app.mcp.client

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-mcp-supersede-lease] 第四轮验证残留(1)(2)：**替换/接管**与**显式失效**这两条路径上的
 * close，以及"在途登记之后的收尾"的兜底范围。
 *
 * 淘汰路径（`detachScope(onlyIfIdle = true)`）早就过了在途计数那一关，但"换缓存条目"这条路
 * 没有：并行 subagent lane 与父会话共用一个作用域，指纹一变（配置 / built-in loopback 接线
 * 变化）时 `publish` 会把上一版 client 记为 superseded 并异步 close —— 而在途簿记按作用域、
 * 不按 client，那个进程可能正被同作用域的另一次调用拿在手里等回复，close 掉就是 destroy 掉它，
 * 调用方拿到 TIMEOUT / CONNECTION_ERROR。修法：替换/接管前先问 `deferCloseIfBusy`，忙则挂到
 * 作用域上，由最后一个 `release` 把回收动作交回（都在 [McpScopeLeaseTable] 的一次临界区里）。
 *
 * 同一段的第二个残留是"兜底范围"：回收调度原来位于 try/catch **之前**，它一抛 `clientFor`
 * 就拿不到 Lease 返回，`withClient` 的 finally 便无从释放 → 在途计数永久 +1、该作用域再也
 * 不能被淘汰。所以这里既用真线程钉住租约表的语义，也用结构守卫钉住调用点。
 *
 * 与 `McpSpawnSessionScopeGuardTest` 同一手法：必须保持成对的结构用源码守卫钉住（比运行时
 * 断言更早、更稳地失败），纯 JVM 的那半边（租约表）用真线程直接钉住。
 */
class McpSupersedeCloseGuardTest {

    private val bridgePath = "src/main/java/com/openminis/app/mcp/client/McpNativeBridge.kt"
    private val policyPath = "src/main/java/com/openminis/app/mcp/client/McpStdioScopePolicy.kt"
    private val stdioPath = "src/main/java/com/openminis/app/mcp/client/McpStdioClient.kt"

    // -- 租约表语义（真线程，纯 JVM）------------------------------------------

    /**
     * 残留(1) 的核心交错：调用 X 命中缓存拿到旧进程（在途），调用 Y 因指纹变化 `publish` 换掉
     * 缓存条目并把旧进程记为 superseded —— 旧进程在 X 跑完之前一次都不能 close，最后一个
     * release 才把回收动作交回。
     */
    @Test
    fun theSupersededProcessSurvivesUntilTheConcurrentCallReleases() {
        val table = McpScopeLeaseTable()
        val closed = AtomicInteger()
        assertNotNull("X 命中缓存，拿到旧进程", table.acquire("S", 1L) { "old-process" })
        assertEquals("Y 因指纹变化换掉缓存条目", "new-process", table.publish("S", 2L) { "new-process" })

        val deferred = table.deferCloseIfBusy("S", own = 1) { closed.incrementAndGet() }
        assertTrue("同作用域还有别的调用 → 被顶替的进程要挂起回收", deferred)
        assertEquals("挂起期间一次都不能 close", 0, closed.get())

        assertTrue("X 结束但 Y 还在 → 不交回", table.release("S").isEmpty())
        assertEquals("还有调用在跑，回收动作不能提前执行", 0, closed.get())

        val pending = table.release("S")
        assertEquals("计数归零 → 交回恰好一个回收动作", 1, pending.size)
        assertEquals("交回之前仍然没有 close（由调用方在锁外执行）", 0, closed.get())
        for (recycle in pending) recycle()
        assertEquals("交回后 close 一次", 1, closed.get())
    }

    /**
     * 反过来的半边：没有别的在途调用时**不**挂起 —— `own` 把自己刚登记的那一次扣掉，被顶替的
     * 进程不可能有人持有（缓存条目已在同一临界区里换掉），调用方立刻回收，不白推迟。
     */
    @Test
    fun anIdleScopeRecyclesTheSupersededProcessImmediately() {
        val table = McpScopeLeaseTable()
        val closed = AtomicInteger()
        assertEquals("publish 登记了本次调用", "new-process", table.publish("S", 1L) { "new-process" })

        val deferred = table.deferCloseIfBusy("S", own = 1) { closed.incrementAndGet() }
        assertFalse("只有自己 → 不需要挂起", deferred)
        if (!deferred) closed.incrementAndGet()
        assertEquals("空闲作用域立刻回收一次", 1, closed.get())
        assertTrue("没有挂起项，release 无事可交", table.release("S").isEmpty())
        assertTrue("计数归零后作用域重新可淘汰", table.detachIfIdle("S") {})
    }

    /** 显式失效（`invalidate`，own = 0：没有自己的登记）同样要等最后一个调用结束。 */
    @Test
    fun anExplicitInvalidationWaitsForTheCallsUsingThatProcess() {
        val table = McpScopeLeaseTable()
        val closed = AtomicInteger()
        assertNotNull("调用命中缓存", table.acquire("S", 1L) { "old-process" })
        assertTrue("在途非零 → 挂起", table.deferCloseIfBusy("S", own = 0) { closed.incrementAndGet() })
        assertEquals("挂起期间没 close", 0, closed.get())
        val pending = table.release("S")
        for (recycle in pending) recycle()
        assertEquals("最后一次调用结束后才 close 一次", 1, closed.get())
    }

    /** 关停（`closeAll` → `clear()`）不能把挂起的回收动作随表丢掉 —— 那就是 guest 进程泄漏。 */
    @Test
    fun shutdownHandsBackTheDeferredCloses() {
        val table = McpScopeLeaseTable()
        val closed = AtomicInteger()
        assertNotNull(table.acquire("S", 1L) { "old-process" })
        assertTrue(table.deferCloseIfBusy("S", own = 0) { closed.incrementAndGet() })
        val pending = table.clear()
        assertEquals("clear 要把挂起项交回调用方", 1, pending.size)
        for (recycle in pending) recycle()
        assertEquals("关停时仍然 close 一次", 1, closed.get())
    }

    /**
     * 挂起与"最后一次 release"交错：两者在同一把锁上串行，所以回收**既不能丢**（挂起之后没人
     * 交回 = 永远不 close）**也不能重复**（挂起项与"空闲即回收"分支同时生效）。顺序按调度器
     * 的意愿，结论必须恒定：close 恰好一次。
     */
    @Test(timeout = 60_000)
    fun aDeferredCloseIsNeverLostOrDuplicated() {
        var brokenRounds = 0
        repeat(100) { round ->
            val table = McpScopeLeaseTable()
            val closed = AtomicInteger()
            assertNotNull(table.acquire("S", 1L) { "process" })
            val releaser = thread(name = "lease-releaser-$round") {
                for (recycle in table.release("S")) recycle()
            }
            val retire = thread(name = "lease-retire-$round") {
                // own = 0：模拟 invalidate 那条没有自己登记的路径，任何在途调用都算"别人"
                if (!table.deferCloseIfBusy("S", own = 0) { closed.incrementAndGet() }) {
                    closed.incrementAndGet()
                }
            }
            releaser.join(20_000)
            retire.join(20_000)
            if (closed.get() != 1) brokenRounds++
        }
        assertEquals("100 轮交错里 close 必须恰好一次（丢了=进程泄漏，重了=重复 destroy）", 0, brokenRounds)
    }

    // -- 调用点/结构守卫 ------------------------------------------------------

    /** 残留(1)：替换/接管与显式失效都必须先问租约表；release 的返回值必须就地执行。 */
    @Test
    fun theCloseCallSitesGoThroughTheLeaseTable() {
        val bridge = readSource(bridgePath)
        val clientFor = functionBody(bridge, "private fun clientFor(")
        assertTrue(
            "替换/接管前要先问作用域是否还忙（own = 1 = 自己刚在 publish 里登记的那一次）:\n$clientFor",
            clientFor.contains("scopeLeases.deferCloseIfBusy(leaseScope, own = 1)"),
        )
        val invalidate = functionBody(bridge, "fun invalidate(")
        assertTrue(
            "显式失效同样不能 close 掉别人正在用的进程（own = 0：没有自己的登记）:\n$invalidate",
            invalidate.contains("scopeLeases.deferCloseIfBusy(scope, own = 0)"),
        )
        val withClient = functionBody(bridge, "private fun <T> withClient(")
        assertTrue(
            "release 交回的回收动作必须就地执行，否则挂起项永远不 close（进程泄漏）:\n$withClient",
            withClient.contains("for (recycle in scopeLeases.release(scope)) recycle()"),
        )
        val release = functionBody(readSource(policyPath), "fun release(scope: String)")
        assertTrue(
            "计数归零那一刻才把挂起的动作交回:\n$release",
            release.contains("deferredClose.remove(scope)"),
        )
        assertTrue("挂起判定要与在途计数同锁:\n$release", release.contains("synchronized(gate)"))
    }

    /**
     * 残留(2)：在途登记之后**紧邻**的收尾（回收调度 + 淘汰）必须整段落在兜底范围内。守卫按
     * 位置断言，而不是按"某处有 try"——`recycleAsync(…)` 一回到 try 之前，调用方就可能拿不到
     * Lease，计数永久 +1。
     */
    @Test
    fun thePostRegistrationTailIsCoveredByTheFallback() {
        val clientFor = functionBody(readSource(bridgePath), "private fun clientFor(")
        val registered = clientFor.indexOf("scopeLeases.publish(")
        val guarded = clientFor.indexOf("try {")
        assertTrue("clientFor 里找不到 publish 的登记点:\n$clientFor", registered >= 0)
        assertTrue(
            "try 必须在登记之后：保护范围要包住登记之后的收尾，而不是把登记本身包进去:\n$clientFor",
            guarded > registered,
        )
        val catchMatch = Regex("catch\\s*\\([^)]*:\\s*Throwable\\s*\\)").find(clientFor.substring(guarded))
        assertNotNull("兜底必须是 catch (…: Throwable)，否则异常照旧扩散:\n$clientFor", catchMatch)
        // catchMatch == null 时上面的断言已经失败，这里退回 0 只为让类型收敛。
        val catchOffset = catchMatch?.range?.first ?: 0
        val guardedBody = clientFor.substring(guarded, guarded + catchOffset)
        assertTrue("被顶替进程的回收调度要在兜底范围内:\n$guardedBody", guardedBody.contains("recycleAsync("))
        assertTrue("淘汰也要在兜底范围内:\n$guardedBody", guardedBody.contains("evictExcessScopes("))
        assertTrue(
            "兜底之后照样要把 Lease 交出去（不能把返回也吞掉；取最后一处 return Lease，\n" +
                "前面还有命中缓存/预览作用域两个提前返回）:\n$clientFor",
            clientFor.lastIndexOf("return Lease(") > guarded + catchOffset,
        )
    }

    /**
     * 残留(3)：`invalidate()` / `closeAll()` 全仓无调用点，工单允许"写进 KDoc"这条出路，所以
     * 钉两件事：
     *  1. 两个入口的 KDoc 必须明说"当前无生产调用点"——否则下一个人会以为它们在某处接线，
     *     或者反过来当死代码删掉；
     *  2. KDoc 里"不需要外部显式失效"的结论要有代码支撑：配置变更由 `clientFor` 的指纹比较
     *     重建，死进程由 `McpStdioClient.ensureInitialized()` 的 `alive()` 检查原地重启。
     *     这两个前提有一个被改掉，KDoc 的结论就不再成立，守卫跟着失败。
     */
    @Test
    fun theEntriesWithoutACallSiteSaySoAndTheClaimIsBackedByCode() {
        val bridge = readSource(bridgePath)
        for (signature in listOf("fun invalidate(", "fun closeAll(")) {
            val doc = kdocBefore(bridge, signature)
            assertTrue(
                "$signature 的 KDoc 要写明『当前无生产调用点』（工单残留(3)的出路之一）:\n$doc",
                doc.contains("[T-mcp-no-callsite]") && doc.contains("无生产调用点"),
            )
        }
        val clientFor = functionBody(bridge, "private fun clientFor(")
        assertTrue(
            "指纹比较必须还在（配置变更自动重建 = invalidate 无调用点的前提之一）:\n$clientFor",
            clientFor.contains("it.fingerprint == fp"),
        )
        val stdio = functionBody(readSource(stdioPath), "override fun ensureInitialized(")
        assertTrue(
            "死掉的 guest 进程必须还能原地重启（前提之二）:\n$stdio",
            stdio.contains("alive()") && stdio.contains("startProcess()"),
        )
    }

    /**
     * 残留(4)：`buildSpawnSpec` 的 KDoc 必须与预览实际挂的东西一致 —— 三类来源（全局目录 +
     * 用户 SAF 外部挂载 + 共享存储）都要点到，边界的那一半（没有会话目录）也要点到，别退回
     * 到"gets the global directories only"。
     */
    @Test
    fun thePreviewSpawnDocMatchesWhatThePreviewBinds() {
        val bridge = readSource(bridgePath)
        val spawnDoc = kdocBefore(bridge, "private fun buildSpawnSpec(")
        assertFalse(
            "预览不再只是『global directories only』（本轮给它加了用户挂载/共享存储）:\n$spawnDoc",
            spawnDoc.contains("global directories only"),
        )
        assertTrue("要写清用户 SAF 外部挂载:\n$spawnDoc", spawnDoc.contains("/var/minis/mounts/<name>"))
        assertTrue("要写清共享存储:\n$spawnDoc", spawnDoc.contains("shared storage"))
        assertTrue("也要写清边界的那一半（没有会话目录）:\n$spawnDoc", spawnDoc.contains("no session tree"))

        val binds = functionBody(bridge, "private fun previewBindMounts(")
        for (source in listOf("GLOBAL_BIND_SUBDIRS", "userMountBinds(", "shellSharedStorageBinds(")) {
            assertTrue("previewBindMounts 仍然从 $source 取挂载（= 文档里的三类来源）:\n$binds", binds.contains(source))
        }
        val boundary = kdocBefore(bridge, "private fun previewBindMounts(")
        assertTrue(
            "previewBindMounts 的 KDoc 要把能力边界写成三类会话无关的来源:\n$boundary",
            boundary.contains("会话无关") &&
                boundary.contains("SAF 外部挂载") &&
                boundary.contains("共享存储"),
        )
    }

    // -- helpers --------------------------------------------------------------

    private fun readSource(relativePath: String): String {
        val file = File(relativePath)
        assertTrue(
            "找不到 $relativePath —— 该测试按模块目录解析源码 " +
                "(cwd=${File(".").absolutePath})",
            file.isFile,
        )
        return file.readText()
    }

    /**
     * 从 [signature] 处截到该成员结束——第一个"恰好 4 空格缩进的 `}`"行，也就是顶层成员的收尾
     * 大括号（参数表与函数体内部都缩进 ≥8 空格，不会提前命中）。命中不到签名说明函数被改名了，
     * 守卫本身要一起更新。
     */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("源码里找不到 $signature —— 守卫测试需要跟着函数签名一起改", start >= 0)
        val rest = source.substring(start)
        val end = Regex("\n {4}\\}").find(rest)?.range?.first
        assertTrue("$signature 的收尾大括号没找到（缩进变了？）", end != null)
        // end == null 时上面的断言已经失败，这里退回整段只为让类型收敛。
        return rest.substring(0, end ?: rest.length)
    }

    /** [signature] 之前最近的一段 KDoc（从它的起始标记到签名）——KDoc 在签名之前，body 截取拿不到。 */
    private fun kdocBefore(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("源码里找不到 $signature —— 守卫测试需要跟着函数签名一起改", start >= 0)
        val docStart = source.lastIndexOf("/**", start)
        assertTrue("$signature 前面没有 KDoc（被删了？）", docStart >= 0)
        return source.substring(docStart, start)
    }
}
