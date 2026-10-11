package com.openminis.app.mcp.client

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-mcp-session-bind] 工单 `sandbox-env-offload#4` 的源码守卫。
 *
 * 根因是 MCP stdio spawn 从进程级、last-writer-wins 的 `PRootKernel.bindMounts` 里取
 * `-b`：那张表由"最近建壳的会话"写成，于是会话 B 的 MCP 进程挂到了会话 A 的
 * `/var/minis/{workspace,attachments,offloads,browser,memory}` 上（跨会话读写）。
 * 修复有两半，任何一半回退都会让越界读复现，所以这里把它们钉住：
 *  1. spawn 的 `-b` 只来自调用会话（`sessionBindMounts(scope, publish = false)`），
 *     且不再读全局表、不再改写共享表；
 *  2. client 缓存键带挂载作用域（会话 B 不会命中 A 的常驻进程），并且会话结束时会
 *     收掉这些进程。
 *
 * 与 `InAppThemeSourceGuardTest` 同一手法：这类"必须保持成对"的约束用源码守卫比
 * 用运行时断言更早、更稳地失败。
 */
class McpSpawnSessionScopeGuardTest {

    private val bridgePath = "src/main/java/com/openminis/app/mcp/client/McpNativeBridge.kt"
    private val coordinatorPath = "src/main/java/com/openminis/app/sandbox/ExecutionCoordinator.kt"
    private val toolsSheetPath = "src/main/java/com/openminis/app/ui/settings/MCPToolsSheet.kt"

    @Test
    fun stdioSpawnBindsComeFromTheCallingSessionOnly() {
        val body = functionBody(readSource(bridgePath), "private fun buildSpawnSpec(")
        assertTrue(
            "stdio spawn 必须按调用会话组装 -b（ExecutionCoordinator.sessionBindMounts）:\n$body",
            body.contains("ExecutionCoordinator.sessionBindMounts("),
        )
        assertTrue(
            "stdio spawn 不得再读进程级 bindMounts —— 那是跨会话读的根因:\n$body",
            !body.contains("PRootKernel.bindMounts"),
        )
        assertTrue(
            "spawn 用 publish = false，不能顺手改写共享的挂载表:\n$body",
            body.contains("publish = false"),
        )
    }

    @Test
    fun stdioClientsAreCachedPerMountScope() {
        val body = functionBody(readSource(bridgePath), "private fun clientFor(")
        assertTrue(
            "stdio client 的缓存身份要带调用会话的挂载作用域:\n$body",
            body.contains("McpStdioScopePolicy.scopeOf(sessionId)"),
        )
        assertTrue("缓存键按传输/作用域/服务器区分:\n$body", body.contains("CacheKey("))
        assertTrue("超出作用域上限的常驻进程要被淘汰:\n$body", body.contains("evictExcessScopes("))
    }

    @Test
    fun sessionTeardownClosesItsStdioClients() {
        val closeSession = functionBody(readSource(bridgePath), "fun closeSession(")
        assertTrue(
            "closeSession 要摘掉该作用域的 client（关掉 guest 进程）:\n$closeSession",
            closeSession.contains("detachScope("),
        )
        assertTrue(
            "摘除必须同步（否则之后新建的同作用域 client 会被这次关闭误伤），\n" +
                "进程回收才放后台:\n$closeSession",
            closeSession.contains("closerScope.launch"),
        )
        val teardown = functionBody(readSource(coordinatorPath), "fun sessionDidTerminate(")
        assertTrue(
            "会话结束必须调用 McpNativeBridge.closeSession，否则每个关掉的聊天\n" +
                "都会留下一个常驻 MCP 进程:\n$teardown",
            teardown.contains("McpNativeBridge.closeSession("),
        )
    }

    /**
     * S2/S6：淘汰只看 `scopeLastUsed` 的空闲时长时，一次跑到第 31 秒的长构建/浏览器调用
     * （stdio 超时 300s，空闲地板只有 30s）用的进程会被别的会话的开场调用关掉。所以每个
     * 入口都要在调用期间把作用域标成"在途"，淘汰时必须把在途集合交给策略跳过。
     *
     * [T-mcp-inflight-lease] 工单 D9 残留(1)：登记的**时点**同样是判据。淘汰是"快照 + 逐项
     * 摘除"，所以"取用缓存里的进程"和"登记在途"必须在 `clientFor` 里的一次原子操作内完成
     * （租约即登记）；登记若回到 `clientFor` 返回之后，快照与摘除之间到达的调用就会被摘掉并
     * close（CONNECTION_ERROR，运行时回归见 McpScopeLeaseTableTest）。摘除那一侧则必须在同一
     * 把锁里复查在途计数（`onlyIfIdle`）。
     */
    @Test
    fun callsLeaseTheirScopeSoEvictionCannotKillThemMidCall() {
        val source = readSource(bridgePath)
        for (signature in listOf("fun listTools(", "fun callTool(")) {
            val body = functionBody(source, signature)
            assertTrue(
                "$signature 必须经 withClient 租用作用域（否则调用期间可被淘汰）:\n$body",
                body.contains("withClient("),
            )
        }
        val withClient = functionBody(source, "private fun <T> withClient(")
        assertTrue(
            "租约在 clientFor 里已登记，调用结束（含异常路径）必须释放:\n$withClient",
            withClient.contains("scopeLeases.release("),
        )
        assertTrue(
            "只释放已登记的租约（HTTP/预览没有登记，不能误减计数）:\n$withClient",
            withClient.contains("lease.registered"),
        )
        assertFalse(
            "登记不能再回到 clientFor 返回之后 —— 那正是被淘汰摘掉的窗口:\n$withClient",
            withClient.contains("beginScopeCall("),
        )
        val clientFor = functionBody(source, "private fun clientFor(")
        assertTrue(
            "命中缓存的取用与在途登记必须是一次原子操作（租约即登记）:\n$clientFor",
            clientFor.contains("scopeLeases.acquire("),
        )
        assertTrue(
            "重建路径的安装/接管与登记也要在同一次临界区内:\n$clientFor",
            clientFor.contains("scopeLeases.publish("),
        )
        val evict = functionBody(source, "private fun evictExcessScopes(")
        assertTrue(
            "淘汰要把在途作用域交给策略跳过，否则正在跑的调用拿到 CONNECTION_ERROR/TIMEOUT:\n$evict",
            evict.contains("scopeLeases.busyScopes()"),
        )
        assertTrue(
            "victims 只是快照：摘除要在同一把锁里复查在途计数（在途则整项跳过）:\n$evict",
            evict.contains("detachScope(scope, onlyIfIdle = true)"),
        )
        assertTrue(
            "淘汰的进程回收要放后台：destroy+waitFor(2s) 不能压到触发淘汰的新调用上:\n$evict",
            evict.contains("recycleAsync("),
        )
        val recycle = functionBody(source, "private fun recycleAsync(")
        assertTrue(
            "回收要真的落到后台线程（closerScope）:\n$recycle",
            recycle.contains("closerScope.launch"),
        )
    }

    /**
     * [T-mcp-close-async] 工单 D9 残留(2)：配置变更重建与 `invalidate` 上的 `close`。
     *
     * `McpStdioClient.close()` 与 `ensureInitialized()` 争同一把锁，而 `initialize` 最长持锁
     * startupTimeout（默认 60s）。这两处都在 MCP 调用线程上（重建就在 `clientFor` 里、
     * `invalidate` 在调用方的错误分支上），同步 close 就是一次最长一分钟的卡顿，所以进程回收
     * 只能交给 reaper：缓存条目同步摘掉（下一次调用不会再拿到它），进程由 `recycleAsync` 收。
     */
    @Test
    fun closesOnTheCallPathAreHandedToTheReaper() {
        val source = readSource(bridgePath)
        val clientFor = functionBody(source, "private fun clientFor(")
        assertFalse(
            "重建路径不能同步 close 被顶替的进程（会把这次调用压住 60s）:\n$clientFor",
            clientFor.contains(".client.close()"),
        )
        assertTrue("被顶替的进程交给 recycleAsync:\n$clientFor", clientFor.contains("recycleAsync("))
        val invalidate = functionBody(source, "fun invalidate(")
        assertFalse(
            "invalidate 不能同步 close（它就在调用路径的错误分支上）:\n$invalidate",
            invalidate.contains(".client.close()"),
        )
        assertTrue(
            "invalidate 的进程回收也要交给 reaper（条目仍然同步摘除）:\n$invalidate",
            invalidate.contains("recycleAsync("),
        )
        val recycle = functionBody(source, "private fun recycleAsync(")
        assertTrue("reaper 上真的 close:\n$recycle", recycle.contains("client.close()"))
    }

    /** `invalidate(server)` 摘掉 client 之后，作用域不能继续"占名额"（无 client 的僵尸作用域）。 */
    @Test
    fun invalidateDropsTimestampsOfScopesWithNoClientLeft() {
        val body = functionBody(readSource(bridgePath), "fun invalidate(")
        assertTrue(
            "invalidate 要同步清掉「无 client 的僵尸作用域」的时间戳，否则它们会顶掉真会话的名额:\n$body",
            body.contains("pruneScopeLastUsed("),
        )
    }

    /**
     * 设置面板的合成作用域 `mcp-tools-ui`：没有会话会来 closeSession 它，也不该按会话布局
     * 在 `filesDir/minis-sessions/` 下落盘。契约是"不缓存、只挂全局目录、用完即回收"，而
     * 面板必须传同一个常量——两边字面量一漂移就是进程/空目录泄漏。
     */
    @Test
    fun theSyntheticPreviewScopeIsNeverCachedAndNeverBindsSessionDirs() {
        val source = readSource(bridgePath)
        val clientFor = functionBody(source, "private fun clientFor(")
        assertTrue(
            "合成作用域要绕过缓存（否则没有 closeSession 会来收它）:\n$clientFor",
            clientFor.contains("isPreviewScope(scope)"),
        )
        assertTrue("绕缓存的那条路要标记用完即回收:\n$clientFor", clientFor.contains("closeAfterUse = true"))
        val spawn = functionBody(source, "private fun buildSpawnSpec(")
        assertTrue(
            "合成作用域的 -b 不能按会话布局派生（mkdirs 会在 minis-sessions 下留空目录）:\n$spawn",
            spawn.contains("isPreviewScope(mountScope)") && spawn.contains("previewBindMounts("),
        )
        val previewBinds = functionBody(source, "private fun previewBindMounts(")
        assertTrue(
            "合成作用域只挂全局目录（以及下面的用户挂载）:\n$previewBinds",
            previewBinds.contains("GLOBAL_BIND_SUBDIRS"),
        )
        // [T-mcp-preview-mounts] 工单 D9 残留(3)：预览的 -b 由合并规则给出，用户 SAF 外部
        // 挂载（/var/minis/mounts/<name>）必须在其中 —— 否则从挂载目录读配置的服务器在预览
        // 里只看到 rootfs 空占位目录，行为与真会话不一致（运行时判据见
        // McpStdioScopePolicyTest.previewBindsCarryGlobalsUserMountsAndSharedStorage）。
        assertTrue(
            "预览的 -b 要走 McpStdioScopePolicy.mergePreviewBinds（与真会话同优先级）:\n$previewBinds",
            previewBinds.contains("mergePreviewBinds("),
        )
        assertTrue(
            "用户 SAF 外部挂载必须挂上:\n$previewBinds",
            previewBinds.contains("userMountBinds("),
        )
        assertFalse(
            "预览仍然不能按会话布局派生挂载（mkdirs 会在 minis-sessions 下留空目录）:\n$previewBinds",
            previewBinds.contains("ExecutionCoordinator.sessionBindMounts(") ||
                previewBinds.contains("SESSION_SUBDIRS"),
        )
        val withClient = functionBody(source, "private fun <T> withClient(")
        assertTrue("私有预览 client 必须在 finally 里回收:\n$withClient", withClient.contains("closeAfterUse"))
        val sheet = readSource(toolsSheetPath)
        assertTrue(
            "设置面板要传 McpStdioScopePolicy.PREVIEW_SCOPE:\n$sheet",
            sheet.contains("McpStdioScopePolicy.PREVIEW_SCOPE"),
        )
        assertTrue(
            "设置面板不该再自己写死 \"mcp-tools-ui\"（字面量漂移 = 泄漏）:\n$sheet",
            !sheet.contains("\"mcp-tools-ui\""),
        )
    }

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
     * 从 [signature] 处截到该成员结束——第一个"恰好 4 空格缩进的 `}`"行，也就是顶层
     * 成员的收尾大括号（参数表与函数体内部都缩进 ≥8 空格，不会提前命中）。命中不到
     * 签名说明函数被改名了，守卫本身要一起更新。
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
}
