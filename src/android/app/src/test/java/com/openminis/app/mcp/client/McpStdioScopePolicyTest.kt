package com.openminis.app.mcp.client

import com.openminis.app.sandbox.SharedStorageBindPlan
import com.openminis.app.sandbox.SessionWorkspace
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [T-mcp-session-bind] 工单 `sandbox-env-offload#4`：stdio MCP client 是一个常驻 guest
 * 进程，`-b` 在 spawn 时固化，所以它的缓存身份必须带"调用会话的挂载作用域"——否则会话 B
 * 直接复用会话 A 的进程，在 B 里读到 A 的 workspace/attachments/memory。作用域同时是
 * 进程数量的单位，所以要有上限（[McpStdioScopePolicy.MAX_SCOPES]）。
 */
class McpStdioScopePolicyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 1_000_000L

    @Test
    fun laneCollapsesToItsOwnerSession() {
        // lane 与父会话共用同一份目录（SessionWorkspace.hostDir 同样归一），
        // 归一后复用同一个进程而不是每个 lane 各起一个。
        assertEquals("chat-A", McpStdioScopePolicy.scopeOf("subagent:chat-A:2"))
        assertEquals("chat-A", McpStdioScopePolicy.scopeOf("chat-A"))
    }

    @Test
    fun differentSessionsNeverShareAScope() {
        // 作用域不同 → CacheKey 不同 → 不会命中别的会话 spawn 出来的进程
        assertNotEquals(
            McpStdioScopePolicy.scopeOf("chat-A"),
            McpStdioScopePolicy.scopeOf("chat-B"),
        )
    }

    @Test
    fun nothingIsClosedWhileAtOrUnderTheCap() {
        val lastUsed = mapOf(
            "a" to now - 400_000,
            "b" to now - 300_000,
            "c" to now - 200_000,
            "d" to now - 100_000,
        )
        assertTrue(McpStdioScopePolicy.victims(lastUsed, keep = "d", nowMs = now).isEmpty())
    }

    @Test
    fun scopesOverTheCapAreClosedOldestFirst() {
        val lastUsed = mapOf(
            "old" to now - 500_000,
            "mid" to now - 400_000,
            "new" to now - 300_000,
            "newer" to now - 200_000,
            "newest" to now - 100_000,
            "keep" to now,
        )
        // 6 个作用域、上限 4 → 关 2 个最旧的
        assertEquals(
            listOf("old", "mid"),
            McpStdioScopePolicy.victims(lastUsed, keep = "keep", nowMs = now),
        )
    }

    @Test
    fun keepIsNeverClosedEvenWhenItIsTheOldest() {
        val lastUsed = mapOf(
            "keep" to now - 900_000,
            "b" to now - 800_000,
            "c" to now - 700_000,
            "d" to now - 600_000,
            "e" to now - 500_000,
        )
        // keep 刚被使用/创建，即使时间戳最旧也不关；退到下一个最旧的
        assertEquals(
            listOf("b"),
            McpStdioScopePolicy.victims(lastUsed, keep = "keep", nowMs = now),
        )
    }

    @Test
    fun aScopeInActiveUseIsNotClosed() {
        val lastUsed = mapOf(
            "active" to now - 2_000,
            "b" to now - 900_000,
            "c" to now - 800_000,
            "d" to now - 700_000,
            "e" to now - 600_000,
        )
        // 上限 4、共 5 个 → 该关 1 个；"active" 闲置不足 idle floor（别的会话可能正在
        // 调用它），所以退到最旧的 b，而不是把在途调用打断。
        assertEquals(
            listOf("b"),
            McpStdioScopePolicy.victims(lastUsed, keep = "new", nowMs = now),
        )
    }

    @Test
    fun aScopeWithoutATimestampCountsAsOldest() {
        val lastUsed = mapOf(
            "no-timestamp" to 0L,
            "b" to now - 100_000,
            "c" to now - 90_000,
            "d" to now - 80_000,
            "e" to now - 70_000,
        )
        assertEquals(
            listOf("no-timestamp"),
            McpStdioScopePolicy.victims(lastUsed, keep = "e", nowMs = now),
        )
    }

    /**
     * S2/S6 回归：stdio 单次调用超时 300s，而空闲地板只有 30s —— 只看时间戳的话，一次跑到
     * 第 31 秒的长构建/浏览器调用用的进程会被别的会话的开场调用当成"闲置作用域"关掉，调用方
     * 拿到 CONNECTION_ERROR / TIMEOUT。有在途调用的作用域必须免淘汰。
     */
    @Test
    fun aScopeWithACallInFlightIsNeverClosed() {
        val lastUsed = mapOf(
            // 15 分钟没"碰过"——但正跑着一次 300s 的 tools/call
            "building" to now - 900_000,
            "b" to now - 800_000,
            "c" to now - 700_000,
            "d" to now - 600_000,
            "keep" to now,
        )
        // 上限 4、共 5 个 → 该关 1 个：跳过在途的 building，退到下一个最旧的 b
        assertEquals(
            listOf("b"),
            McpStdioScopePolicy.victims(
                lastUsed, keep = "keep", nowMs = now, busyScopes = setOf("building"),
            ),
        )
    }

    /** 只有在途作用域够旧时，宁可暂时超过上限，也不打断正在跑的调用。 */
    @Test
    fun aBusyScopeStaysAliveEvenWhenEveryOtherScopeIsFresh() {
        val lastUsed = mapOf(
            "building" to now - 900_000,
            "b" to now - 1_000,
            "c" to now - 1_000,
            "d" to now - 1_000,
            "e" to now - 1_000,
        )
        assertTrue(
            McpStdioScopePolicy.victims(
                lastUsed, keep = "e", nowMs = now, busyScopes = setOf("building"),
            ).isEmpty(),
        )
    }

    /**
     * 设置面板的合成作用域（[McpStdioScopePolicy.PREVIEW_SCOPE]）不是会话：桥接层按它决定
     * "不缓存、不挂会话目录、用完即回收"，所以这个常量就是那个契约的锚点。
     */
    @Test
    fun theSettingsPreviewIsASyntheticScope() {
        assertEquals("mcp-tools-ui", McpStdioScopePolicy.PREVIEW_SCOPE)
        assertTrue(McpStdioScopePolicy.isPreviewScope(McpStdioScopePolicy.PREVIEW_SCOPE))
        // 合成作用域不是 lane，scopeOf 不会把它归并到任何真实会话上
        assertEquals(
            McpStdioScopePolicy.PREVIEW_SCOPE,
            McpStdioScopePolicy.scopeOf(McpStdioScopePolicy.PREVIEW_SCOPE),
        )
        assertFalse(McpStdioScopePolicy.isPreviewScope("chat-A"))
        assertFalse(McpStdioScopePolicy.isPreviewScope("subagent:chat-A:1"))
    }

    /** 旧版本给合成作用域按会话布局 mkdirs 出的空树要被回收掉，否则面板用过一次就留一地空目录。 */
    @Test
    fun emptyPreviewDirsAreReclaimed() {
        val root = File(tmp.root, "minis-sessions/${McpStdioScopePolicy.PREVIEW_SCOPE}")
        for (subdir in listOf("workspace", "attachments", "offloads", "browser", "memory")) {
            File(root, subdir).mkdirs()
        }
        assertEquals(6, McpStdioScopePolicy.reapEmptyDirs(root))
        assertFalse("空的预览目录树要整个收掉: ${root.absolutePath}", root.exists())
    }

    /** 只删空目录：预览进程真写过东西时，数据比"目录干净"重要。 */
    @Test
    fun nonEmptyPreviewDirsAreKept() {
        val root = File(tmp.root, "minis-sessions/${McpStdioScopePolicy.PREVIEW_SCOPE}")
        File(root, "workspace/nested").mkdirs()
        File(root, "workspace/kept.txt").writeText("data")
        File(root, "memory").mkdirs()
        // nested（空）与 memory（空）各删一个，workspace 与作用域根目录因为有文件而保留
        assertEquals(2, McpStdioScopePolicy.reapEmptyDirs(root))
        assertTrue("有内容的文件不能动", File(root, "workspace/kept.txt").isFile)
        assertTrue("有内容的目录不能动", File(root, "workspace").isDirectory)
        assertFalse("空兄弟目录不用留", File(root, "memory").exists())
        assertFalse("深层空目录不用留", File(root, "workspace/nested").exists())
        assertTrue("还有内容时作用域根目录必须保留", root.isDirectory)
    }

    @Test
    fun reapingAMissingTreeIsANoOp() {
        assertEquals(0, McpStdioScopePolicy.reapEmptyDirs(File(tmp.root, "nope")))
    }

    /**
     * [T-mcp-preview-mounts] 工单 D9 残留(3)：预览作用域的挂载**边界**。
     *
     * 合成作用域不是会话，所以它只挂三类东西：全局目录（skills/shared/mcp-servers）、用户
     * SAF 外部挂载（`/var/minis/mounts/<name>`）、共享存储。曾经的实现只有前一类 + 共享存储，
     * 于是"从挂载目录读配置"的服务器在设置面板的预览里和真会话行为不一致（
     * `ls /var/minis/mounts/<name>/` 只看到 rootfs 里的空占位目录）。
     */
    @Test
    fun previewBindsCarryGlobalsUserMountsAndSharedStorage() {
        val merged = McpStdioScopePolicy.mergePreviewBinds(
            globalBinds = linkedMapOf(
                "/var/minis/skills" to "/data/minis-global/skills",
                "/var/minis/shared" to "/data/minis-global/shared",
                "/var/minis/mcp-servers" to "/data/minis-global/mcp-servers",
            ),
            userMountBinds = linkedMapOf(
                "/var/minis/mounts/vault" to "/storage/emulated/0/Documents/Vault",
            ),
            sharedStorageBinds = linkedMapOf(
                "/sdcard" to "/storage/emulated/0",
                "/storage/emulated/0" to "/storage/emulated/0",
                SharedStorageBindPlan.MOUNTS_SDCARD to "/storage/emulated/0",
            ),
        )
        assertEquals(
            "用户 SAF 外部挂载必须挂上（与真会话一致）",
            "/storage/emulated/0/Documents/Vault",
            merged["/var/minis/mounts/vault"],
        )
        assertEquals("/data/minis-global/mcp-servers", merged["/var/minis/mcp-servers"])
        assertEquals("/storage/emulated/0", merged["/sdcard"])
        assertEquals("/storage/emulated/0", merged[SharedStorageBindPlan.MOUNTS_SDCARD])
        // 能力边界的另一半：会话目录一个都不能出现 —— 预览不是会话，不能读到任何聊天的
        // workspace/attachments/offloads/browser/memory
        for (subdir in SessionWorkspace.SESSION_SUBDIRS) {
            assertFalse(
                "预览不能挂会话目录 /var/minis/$subdir",
                merged.containsKey("/var/minis/$subdir"),
            )
        }
    }

    /** 用户可以把挂载命名为 `sdcard`：它必须赢过共享存储的同名项（与真会话同规则）。 */
    @Test
    fun aUserMountNamedSdcardWinsOverSharedStorage() {
        val merged = McpStdioScopePolicy.mergePreviewBinds(
            globalBinds = emptyMap(),
            userMountBinds = linkedMapOf(
                SharedStorageBindPlan.MOUNTS_SDCARD to "/storage/emulated/0/MySdCard",
            ),
            sharedStorageBinds = linkedMapOf(
                SharedStorageBindPlan.MOUNTS_SDCARD to "/storage/emulated/0",
            ),
        )
        assertEquals("/storage/emulated/0/MySdCard", merged[SharedStorageBindPlan.MOUNTS_SDCARD])
    }

    /** 没有用户挂载、没有共享存储权限时，预览只剩全局目录（不能因此凭空多挂或报错）。 */
    @Test
    fun previewBindsDegradeToGlobalsOnly() {
        val merged = McpStdioScopePolicy.mergePreviewBinds(
            globalBinds = linkedMapOf("/var/minis/mcp-servers" to "/data/minis-global/mcp-servers"),
            userMountBinds = emptyMap(),
            sharedStorageBinds = emptyMap(),
        )
        assertEquals(
            mapOf("/var/minis/mcp-servers" to "/data/minis-global/mcp-servers"),
            merged,
        )
    }
}
