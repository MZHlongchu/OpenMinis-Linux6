package com.openminis.app.sandbox

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-session-private-mounts] Per-session `/var/minis` directories go
 * only into the owning shell's argv; the global mount table never holds them.
 *
 * The regression this guards: every shell build wrote its session's dirs into
 * the process-global PRootKernel.bindMounts, so the last session to run a
 * command decided what `/var/minis/workspace` meant for the terminal and for
 * every path lookup without a session.
 */
class SessionIsolationTest {

    private val filesDir: File = Files.createTempDirectory("minis-files").toFile()

    @After
    fun cleanUp() {
        filesDir.deleteRecursively()
        for (sub in SessionMounts.SESSION_SUBDIRS) PRootKernel.bindMounts.remove("/var/minis/$sub")
    }

    // ── SessionMounts ─────────────────────────────────────────────────────

    @Test
    fun `a session gets its own four dirs plus the four global ones, all created`() {
        val built = SessionMounts.build(filesDir, "A")
        assertEquals(emptyList<String>(), built.skipped)
        for (sub in SessionMounts.SESSION_SUBDIRS) {
            val host = File(built.mounts.getValue("/var/minis/$sub"))
            assertEquals(File(filesDir, "minis-sessions/A/$sub").absolutePath, host.absolutePath)
            assertTrue("$sub must exist before it is bound", host.isDirectory)
        }
        for (sub in SessionMounts.GLOBAL_SUBDIRS) {
            assertEquals(File(filesDir, "minis-global/$sub").absolutePath, built.mounts["/var/minis/$sub"])
        }
    }

    @Test
    fun `two sessions never share a per-session dir, and do share the global ones`() {
        val a = SessionMounts.build(filesDir, "A").mounts
        val b = SessionMounts.build(filesDir, "B").mounts
        for (sub in SessionMounts.SESSION_SUBDIRS) {
            assertNotEquals("/var/minis/$sub", a["/var/minis/$sub"], b["/var/minis/$sub"])
        }
        for (sub in SessionMounts.GLOBAL_SUBDIRS) {
            assertEquals(a["/var/minis/$sub"], b["/var/minis/$sub"])
        }
    }

    @Test
    fun `building mounts leaves the global table without per-session entries`() {
        SessionMounts.build(filesDir, "A")
        SessionMounts.build(filesDir, "B")
        assertTrue(PRootKernel.bindMounts.keys.none { PRootKernel.isPerSessionPath(it) })
    }

    @Test
    fun `a shell with no session gets the global dirs only`() {
        val built = SessionMounts.build(filesDir, null)
        assertTrue(built.mounts.keys.none { PRootKernel.isPerSessionPath(it) })
        assertEquals(SessionMounts.GLOBAL_SUBDIRS.map { "/var/minis/$it" }, built.mounts.keys.toList())
    }

    @Test
    fun `a dir that cannot be created is skipped and reported, not bound`() {
        // A regular file where the directory should be makes mkdirs fail.
        File(filesDir, "minis-sessions/A").mkdirs()
        File(filesDir, "minis-sessions/A/workspace").writeText("not a directory")
        val built = SessionMounts.build(filesDir, "A")
        assertEquals(listOf("/var/minis/workspace"), built.skipped)
        assertFalse(built.mounts.containsKey("/var/minis/workspace"))
        assertTrue("the others are still bound", built.mounts.containsKey("/var/minis/attachments"))
    }

    @Test
    fun `external mounts pass through unchanged`() {
        val ext = File(filesDir, "sd/photos").apply { mkdirs() }
        val built = SessionMounts.build(filesDir, "A", listOf("/var/minis/mounts/photos" to ext.absolutePath))
        assertEquals(ext.absolutePath, built.mounts["/var/minis/mounts/photos"])
    }

    @Test
    fun `argv pairs are -b host colon guest in bind order`() {
        val args = SessionMounts.toProotArgs(linkedMapOf("/var/minis/workspace" to "/h/ws", "/var/minis/shared" to "/h/sh"))
        assertEquals(listOf("-b", "/h/ws:/var/minis/workspace", "-b", "/h/sh:/var/minis/shared"), args)
    }

    // ── PRootKernel global table ──────────────────────────────────────────

    @Test
    fun `the global table refuses per-session mounts and accepts global ones`() {
        PRootKernel.addBindMount("/var/minis/workspace", "/h/ws")
        PRootKernel.addBindMount("/var/minis/attachments/sub", "/h/at")
        assertNull(PRootKernel.bindMounts["/var/minis/workspace"])
        assertNull(PRootKernel.bindMounts["/var/minis/attachments/sub"])
        PRootKernel.addBindMount("/var/minis/test-global-probe", "/h/g")
        assertEquals("/h/g", PRootKernel.bindMounts.remove("/var/minis/test-global-probe"))
    }

    @Test
    fun `per-session path classification`() {
        for (p in listOf("/var/minis/workspace", "/var/minis/attachments/a.png", "/var/minis/offloads/x", "/var/minis/browser/s.jpg")) {
            assertTrue(p, PRootKernel.isPerSessionPath(p))
        }
        for (p in listOf("/var/minis/shared/a", "/var/minis/memory", "/var/minis/mounts/x", "/var/minis/workspace2", "/root/workspace")) {
            assertFalse(p, PRootKernel.isPerSessionPath(p))
        }
    }

    @Test
    fun `a lookup with no session gets the legacy most-recent-session answer, explicitly`() {
        PRootKernel.noteLegacySession(filesDir, "B")
        val before = PRootKernel.legacyResolveCount
        val f = PRootKernel.resolveHostPath("/var/minis/attachments/cat.png")
        assertEquals(File(filesDir, "minis-sessions/B/attachments/cat.png").absolutePath, f?.absolutePath)
        assertEquals(before + 1, PRootKernel.legacyResolveCount)
        // A session-aware lookup is unaffected by the hint.
        val scoped = SessionMounts.sessionDir(filesDir, "A", "attachments")
        assertEquals(File(filesDir, "minis-sessions/A/attachments").absolutePath, scoped.absolutePath)
    }

    // ── Wiring ────────────────────────────────────────────────────────────

    private fun src(path: String) = File("src/main/java/com/openminis/app/$path").readText()

    @Test
    fun `the coordinator builds private mounts and no longer writes the global table`() {
        val coord = src("sandbox/ExecutionCoordinator.kt")
        // [T-android-session-private-mounts] 本移植的结构与上游不同，断言必须跟着
        // 结构走、但**不能变松**：
        //  - 协调器不再经 `SessionMounts.build/forContext` 构建，改用
        //    [SessionWorkspace]（filed session / project 目录、per-session memory）。
        //  - 它**必须**把 GLOBAL 子目录发布进 PRootKernel（
        //    [T-android-mcp-bind-mount]：PersistentShell 的 `-b` argv 取自
        //    PRootKernel 的注册，不发布的话会话里 `/var/minis/mcp-servers/…` 会落到
        //    rootfs 空地，`minis-mcp-cli list` 永远返回空）。
        // 所以上游那条全文级 `assertFalse(contains("PRootKernel.addBindMount("))`
        // 在本移植已不再成立，改成精确钉住它真正要守的原意：**per-session 的那一段
        // 绝不发布**，发布只能发生在 global 那一段。计数比较保证"在别处偷偷发布
        // per-session 路径"同样会红。
        val privateSection = coord.substringAfter("SessionWorkspace.SESSION_SUBDIRS.forEach")
        assertTrue("the per-session build loop must exist", privateSection.isNotEmpty())
        val globalAnchor = "SessionWorkspace.GLOBAL_BIND_SUBDIRS.forEach"
        val privateEnd = privateSection.indexOf(globalAnchor)
        assertTrue("the global loop must follow the private one", privateEnd > 0)
        assertFalse(
            "no per-session mount may reach the global table",
            privateSection.substring(0, privateEnd).contains("PRootKernel.addBindMount("),
        )
        // 发布的收口边界：T277 的 external-mount 段紧跟其后，拿它当结束锚；
        // 锚没了就让本条显式失败，而不是让下面的比较悄悄退化成扫到文件末尾。
        assertTrue("the publish section needs a stable end anchor", coord.contains("// T277:"))
        val globalSection = coord.substringAfter(globalAnchor).substringBefore("// T277:")
        val publishCalls = Regex("""PRootKernel\.addBindMount\(""").findAll(coord).count()
        assertEquals(
            "every publish call in the coordinator must be the global loop's",
            publishCalls,
            Regex("""PRootKernel\.addBindMount\(""").findAll(globalSection).count(),
        )
        assertTrue(
            "the global loop must actually publish, or the guard above is vacuous",
            globalSection.contains("PRootKernel.addBindMount("),
        )
        assertTrue(
            "the per-session dirs come from the workspace builder",
            coord.contains("SessionWorkspace.hostDir(filesDir, owner, subdir)"),
        )
        assertTrue(
            "legacy (session-less) readers get an explicit hint, not a table side effect",
            coord.contains("PRootKernel.noteLegacySession(appContext, sessionId)"),
        )
        // Both shell types take their -b argv from the private map only.
        assertTrue(src("sandbox/PersistentShell.kt").contains("for ((linuxPath, hostPath) in sessionBindMounts)"))
        assertTrue(src("sandbox/FreshProcessShell.kt").contains("for ((linuxPath, hostPath) in sessionBindMounts)"))
    }

    @Test
    fun `the terminal mounts its chat's dirs and says which files it sees`() {
        val term = src("sandbox/TerminalSession.kt")
        // [T-android-terminal-banner] 本移植的终端是 Termux 引擎（见
        // TerminalSession 的类注释），上游的 `buildInteractiveCommand` 已被
        // `buildTermuxArgs` 取代，所以扫描面跟着换；但两条原意一条都不能少：
        //  (1) 这个 PTY 的参数是按 sessionId 组装的，会话目录取自**同一个**
        //      builder（[ExecutionCoordinator.sessionBindMounts]），终端里的
        //      `/var/minis/*` 就是那个 chat 的目录；
        //  (2) 终端要把"它看见哪些文件"讲出来 —— 上游的 banner 文案逐字恢复
        //      到了 TerminalSession.banner() 并在 PTY 启动后写进终端。
        assertTrue(term.contains("buildTermuxArgs(sessionId, rootfsManager, proot)"))
        assertTrue(term.contains("for ((linuxPath, hostPath) in ExecutionCoordinator.sessionBindMounts(sessionId))"))
        assertTrue(term.contains("val bannerBytes = banner(sessionId).toByteArray()"))
        assertTrue(term.contains("_outputBytes.emit(bannerBytes)"))
        assertTrue(term.contains("Global terminal — not tied to any chat"))
    }
}
