package com.openminis.app.sandbox

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-e4-#1-#2-call-site] 第四轮验证暴露的两条并发残留都落在 `ExecutionCoordinator` 的
 * **调用点**上，而不是某个纯函数 helper 里：
 *
 * 1. `execute` 取每会话 Mutex：注释写着"getOrPut 不是原子的，要用 putIfAbsent 模式"，
 *    代码却是 `getOrPut { Mutex() }`。同会话首次两条命令并发时必须拿到**同一把**锁，
 *    否则类头承诺的"同会话命令串行"不成立（takeover/superseded 窗口也只有这个入口）。
 * 2. `stopCurrentCommand(sessionId = null)` 的"停全部"分支：必须**先摘注册表、再逐个
 *    `stop()`**。反序（先 stop、后 clear）留下的窗口里 `shells[]` 仍引用已停实例，
 *    `getOrCreateShell` 的快路径与启动后复核会把它交回调用方，
 *    `PersistentShell.executeCommand` 再 `ensureStarted()` 就把一个没人引用的实例起回来
 *    —— 与 [ShellSupersedePolicyTest] 描述的孤儿 proot + 同会话双壳同型。
 *
 * 这两处都要真机 `Context` 与真实 proot 才能跑（`ExecutionCoordinator.init` 依赖
 * Android），所以按仓库惯例（见 [ShellSupersedeTakeoverRecheckTest]）直接对源码断言
 * **调用点结构**：判据是"锁怎么取到的""谁在什么时候被摘和被停"，注释不算数。
 */
class ExecutionCoordinatorRegistrySourceTest {

    private val source = File(
        "src/main/java/com/openminis/app/sandbox/ExecutionCoordinator.kt",
    ).readText()

    /** 只看代码：解释性注释里可以随便写 `getOrPut`，断言只认代码行。 */
    private fun code(text: String): String = text.lineSequence()
        .joinToString("\n") { it.substringBefore("//").substringBefore("/*") }

    /** `execute` 入口 → 命令体进入会话锁为止；每会话锁的取用点就在这一段里。 */
    private val mutexSite: String = code(
        source
            .substringAfter("MCPToolPolicy.blockedMessage")
            .substringBefore("return mutex.withLock {"),
    )

    /** "停掉全部会话"分支（到它释放 ShellExecutor 为止）。 */
    private val stopAllSite: String = code(
        source
            .substringAfter("// Stop all sessions (legacy/fallback)")
            .substringBefore("ShellExecutor.destroyCurrent()"),
    )

    @Test
    fun `the per-session mutex is taken atomically and locks the command`() {
        assertTrue(
            "没定位到每会话锁的取用点，本测试的锚点需要跟着改:\n$mutexSite",
            mutexSite.contains("SandboxJobKeepAlive.onStart") &&
                !mutexSite.contains("SandboxResourceGate.withCommandLock"),
        )
        assertTrue(
            "每会话 Mutex 必须在 ConcurrentHashMap 内原子取用，" +
                "两个并发调用者才拿到同一个实例：用 computeIfAbsent(sessionId) { Mutex() }。" +
                "手写 putIfAbsent 只有在返回它的赢家（`?: 自己刚建的那个`）时才对，" +
                "改成那种写法要同步改本守卫:\n$mutexSite",
            mutexSite.contains("mutexes.computeIfAbsent(sessionId)"),
        )
        assertFalse(
            "getOrPut 是先读再写两步，读代码看不出返回的是不是赢家——不能再用在这一处:\n$mutexSite",
            mutexSite.contains("getOrPut"),
        )
        assertTrue(
            "取到的那把锁必须真的包住命令体，否则“同会话串行”只是拿到了锁没用:\n$source",
            source.contains("return mutex.withLock {"),
        )
    }

    @Test
    fun `stop-all detaches the registry before it stops anything`() {
        assertTrue("没定位到 stop-all 分支，本测试的锚点需要跟着改:\n$stopAllSite", stopAllSite.isNotBlank())

        val snapshot = stopAllSite.indexOf("shells.values")
        val clear = stopAllSite.indexOf("shells.clear()")
        val stop = stopAllSite.indexOf(".stop()")

        assertTrue(
            "先取出注册表里的实例，否则 clear 之后就漏停了（进程还在，只是没人引用）:\n$stopAllSite",
            snapshot >= 0,
        )
        assertTrue("停全部必须清空注册表:\n$stopAllSite", clear > snapshot)
        assertTrue(
            "必须先把注册表摘干净再逐个 stop：反序的窗口里 shells[] 仍引用已停实例，" +
                "会被 getOrCreateShell 交回调用方并重生孤儿 proot:\n$stopAllSite",
            stop > clear,
        )
    }
}
