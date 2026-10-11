package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionWorkspaceTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun deleteEntireRemovesMemoryAndWorkspace() {
        val filesDir = tmp.root
        val sid = "sess-a"
        SessionWorkspace.ensureDirs(filesDir, sid)
        File(SessionWorkspace.memoryDir(filesDir, sid), "2026-09-21.md").writeText("note")
        File(SessionWorkspace.base(filesDir, sid), "workspace/a.txt").apply {
            parentFile?.mkdirs()
            writeText("ws")
        }
        assertTrue(SessionWorkspace.deleteEntire(filesDir, sid))
        assertFalse(SessionWorkspace.base(filesDir, sid).exists())
    }

    @Test
    fun deleteDoesNotTouchOtherSessionsOrGlobalSkills() {
        val filesDir = tmp.root
        SessionWorkspace.ensureDirs(filesDir, "a")
        SessionWorkspace.ensureDirs(filesDir, "b")
        File(SessionWorkspace.memoryDir(filesDir, "b"), "keep.md").writeText("b")
        val skills = File(filesDir, "${SessionWorkspace.GLOBAL_DIR}/skills/foo")
        skills.mkdirs()
        File(skills, "SKILL.md").writeText("tool")

        SessionWorkspace.deleteEntire(filesDir, "a")
        assertFalse(SessionWorkspace.base(filesDir, "a").exists())
        assertTrue(File(SessionWorkspace.memoryDir(filesDir, "b"), "keep.md").exists())
        assertTrue(File(skills, "SKILL.md").exists())
    }

    @Test
    fun rejectsPathTraversalIds() {
        val filesDir = tmp.root
        assertFalse(SessionWorkspace.deleteEntire(filesDir, "../escape"))
        assertFalse(SessionWorkspace.deleteEntire(filesDir, ""))
    }

    @Test
    fun filedSessionsShareProjectWorkspaceAndKeepOwnMemory() {
        val filesDir = tmp.root
        SessionWorkspace.rememberFolder("a", "proj")
        SessionWorkspace.rememberFolder("b", "proj")
        try {
            SessionWorkspace.ensureDirs(filesDir, "a")
            SessionWorkspace.ensureDirs(filesDir, "b")
            val sharedA = SessionWorkspace.hostDir(filesDir, "a", "workspace")
            val sharedB = SessionWorkspace.hostDir(filesDir, "b", "workspace")
            assertTrue(sharedA.absolutePath == sharedB.absolutePath)
            File(sharedA, "note.txt").writeText("hi")
            File(SessionWorkspace.memoryDir(filesDir, "a"), "a.md").writeText("a")
            File(SessionWorkspace.memoryDir(filesDir, "b"), "b.md").writeText("b")

            assertTrue(SessionWorkspace.deleteEntire(filesDir, "a"))
            assertTrue(File(sharedB, "note.txt").exists())
            assertFalse(SessionWorkspace.base(filesDir, "a").exists())
            assertTrue(File(SessionWorkspace.memoryDir(filesDir, "b"), "b.md").exists())
        } finally {
            SessionWorkspace.rememberFolder("a", null)
            SessionWorkspace.rememberFolder("b", null)
        }
    }

    @Test
    fun parseDraftFolderIdFromEncodedSessionId() {
        assertTrue(
            SessionWorkspace.parseDraftFolderId("__new__abc__fld__default-workspace")
                == "default-workspace",
        )
        assertTrue(
            SessionWorkspace.parseDraftFolderId("__new__abc__grp__g1__fld__f1") == "f1",
        )
        assertTrue(SessionWorkspace.parseDraftFolderId("plain-session") == null)
    }

    @Test
    fun dotDotCannotEnterAnotherSessionPrivateTree() {
        val filesDir = tmp.root
        SessionWorkspace.ensureDirs(filesDir, "sess-a")
        SessionWorkspace.ensureDirs(filesDir, "sess-b")
        val secret = File(SessionWorkspace.memoryDir(filesDir, "sess-b"), "secret.md")
        secret.writeText("nope")
        val escaped = File(SessionWorkspace.hostDir(filesDir, "sess-a", "workspace"), "../../sess-b/memory/secret.md")
        assertFalse(SessionWorkspace.acceptsResolved(filesDir, "sess-a", escaped))
        assertFalse(SessionWorkspace.staysInside(SessionWorkspace.hostDir(filesDir, "sess-a", "workspace"), escaped))
        assertTrue(SessionWorkspace.acceptsResolved(filesDir, "sess-b", secret))
    }

    @Test
    fun filedSessionMayReadOwnProjectNotAnother() {
        val filesDir = tmp.root
        SessionWorkspace.rememberFolder("sess-a", "folder-a")
        SessionWorkspace.ensureDirs(filesDir, "sess-a")
        try {
            val own = File(SessionWorkspace.projectBase(filesDir, "folder-a"), "workspace/ok.txt")
            own.parentFile.mkdirs()
            own.writeText("ok")
            val other = File(SessionWorkspace.projectBase(filesDir, "folder-b"), "workspace/no.txt")
            other.parentFile.mkdirs()
            other.writeText("no")
            assertTrue(SessionWorkspace.acceptsResolved(filesDir, "sess-a", own))
            assertFalse(SessionWorkspace.acceptsResolved(filesDir, "sess-a", other))
            val memory = File(SessionWorkspace.memoryDir(filesDir, "sess-a"), "note.md")
            memory.writeText("mine")
            assertTrue(SessionWorkspace.acceptsResolved(filesDir, "sess-a", memory))
        } finally {
            SessionWorkspace.rememberFolder("sess-a", null)
        }
    }

    // ── [T-file-tool-symlink-jail] ──

    @Test
    fun workspaceSymlinkCannotEscapeTheSessionJail() {
        val filesDir = tmp.root
        SessionWorkspace.ensureDirs(filesDir, "sess-a")
        SessionWorkspace.ensureDirs(filesDir, "sess-b")
        File(SessionWorkspace.memoryDir(filesDir, "sess-b"), "secret.md").writeText("nope")
        val ws = SessionWorkspace.hostDir(filesDir, "sess-a", "workspace")
        val leak = File(ws, "leak")
        Assume.assumeTrue(
            "symlinks unavailable on this host",
            symlink(leak, java.nio.file.Paths.get("../../..")),
        )

        // `leak` resolves to filesDir, so this path really is sess-b's private
        // tree even though it is written as one inside sess-a's workspace.
        val escaped = File(leak, "minis-sessions/sess-b/memory/secret.md")
        assertFalse(
            "a workspace symlink must not reach another session",
            SessionWorkspace.acceptsResolved(filesDir, "sess-a", escaped),
        )
        // Same escape for a path that does not exist yet — that is the shape
        // `file_write` uses to create a brand-new file through the link.
        assertFalse(
            SessionWorkspace.acceptsResolved(filesDir, "sess-a", File(leak, "brand-new.txt")),
        )
        // The jail itself still works.
        val ok = File(ws, "ok.txt").apply { writeText("ok") }
        assertTrue(SessionWorkspace.acceptsResolved(filesDir, "sess-a", ok))
    }

    @Test
    fun symlinkInsideTheJailIsStillAllowed() {
        val filesDir = tmp.root
        SessionWorkspace.ensureDirs(filesDir, "sess-a")
        val ws = SessionWorkspace.hostDir(filesDir, "sess-a", "workspace")
        val real = File(ws, "real.txt").apply { writeText("ok") }
        val alias = File(ws, "alias.txt")
        Assume.assumeTrue("symlinks unavailable on this host", symlink(alias, real.toPath()))
        assertTrue(SessionWorkspace.acceptsResolved(filesDir, "sess-a", alias))
    }

    /**
     * [T-symlink-realpath-failclosed] 悬空软链（目标不存在）的真实目标**不可知**：
     * 词法判定只会看到「在 sess-a 的 workspace 里」而放行，等于把「它到底指向哪」
     * 留给判定之后再决定（guest 期间补建目标即可换向）。现在解析不出 real 路径
     * 一律拒——读不到是可接受的，读错别人的树不是。
     */
    @Test
    fun danglingSymlinkIsRefusedInsteadOfJudgedLexically() {
        val filesDir = tmp.root
        SessionWorkspace.ensureDirs(filesDir, "sess-a")
        val ws = SessionWorkspace.hostDir(filesDir, "sess-a", "workspace")
        val dangling = File(ws, "dangling")
        Assume.assumeTrue(
            "symlinks unavailable on this host",
            symlink(dangling, java.nio.file.Paths.get("../../../../outside-sess-a")),
        )

        assertFalse(
            "a symlink whose target does not exist must not be judged by its lexical path",
            SessionWorkspace.acceptsResolved(filesDir, "sess-a", File(dangling, "brand-new.txt")),
        )
        assertFalse(
            "the dangling link itself is not resolvable either",
            SessionWorkspace.acceptsResolved(filesDir, "sess-a", dangling),
        )
    }

    @Test
    fun pathsOutsideEveryManagedTreeNeedAnInjectedRoot() {
        val filesDir = File(tmp.root, "files").apply { mkdirs() }
        val rootfs = File(tmp.root, "rootfs").apply { mkdirs() }
        val guest = File(rootfs, "etc/hostname").apply {
            parentFile?.mkdirs()
            writeText("minis")
        }
        // The old fallback returned true for anything outside the Minis trees,
        // which is how a symlink escape was waved through. Outside paths are
        // now allowed only when PRootKernel injects their root.
        assertFalse(SessionWorkspace.acceptsResolved(filesDir, "sess-a", guest))
        assertTrue(SessionWorkspace.acceptsResolved(filesDir, "sess-a", guest, listOf(rootfs)))
        val neighbour = File(tmp.root, "elsewhere.txt").apply { writeText("no") }
        assertFalse(SessionWorkspace.acceptsResolved(filesDir, "sess-a", neighbour, listOf(rootfs)))
    }

    @Test
    fun globalSkillsTreeResolvesWithoutAnyMountTable() {
        // /var/minis/{skills,shared,mcp-servers} live under minis-global and must
        // not depend on the bind-mount table being populated.
        val filesDir = File(tmp.root, "files").apply { mkdirs() }
        val skill = File(filesDir, "${SessionWorkspace.GLOBAL_DIR}/skills/demo/SKILL.md")
        skill.parentFile.mkdirs()
        skill.writeText("# tool")
        assertTrue(SessionWorkspace.acceptsResolved(filesDir, "sess-a", skill))
    }

    // ── [T-walk-cage-single-resolve] resolve + verify 一体 API ──

    /**
     * 现场：`acceptsResolved` 只回 Boolean，调用方手里留下的是**词法**路径，工具侧
     * （SessionTreeGuard / walkOwnedTree）会再解析一次。guest 在两次解析之间把中间分量换成
     * 软链，工具就以新目标为根列出/读取。[verifiedCanonical] 回传复检时用的那条 canonical
     * 路径，工具侧直接用就不会有第二次解析。
     */
    @Test
    fun verifiedCanonicalReturnsThePathItChecked() {
        val filesDir = tmp.root
        SessionWorkspace.ensureDirs(filesDir, "sess-a")
        val ws = SessionWorkspace.hostDir(filesDir, "sess-a", "workspace")
        val real = File(ws, "real.txt").apply { writeText("ok") }
        val alias = File(ws, "alias.txt")
        Assume.assumeTrue("symlinks unavailable on this host", symlink(alias, real.toPath()))

        val verified = SessionWorkspace.verifiedCanonical(filesDir, "sess-a", alias)

        assertEquals(
            "a verified path must be the canonical target it was checked against",
            real.toPath().toRealPath().toFile().path,
            verified?.path,
        )
        assertTrue("the lexical alias must not be handed back", verified?.path != alias.path)
        assertEquals(
            "verifiedCanonical must agree with acceptsResolved",
            SessionWorkspace.acceptsResolved(filesDir, "sess-a", alias),
            verified != null,
        )
    }

    @Test
    fun verifiedCanonicalIsFailClosedOnEscapesAndStillResolvesNewFiles() {
        val filesDir = tmp.root
        SessionWorkspace.ensureDirs(filesDir, "sess-a")
        SessionWorkspace.ensureDirs(filesDir, "sess-b")
        val secret = File(SessionWorkspace.memoryDir(filesDir, "sess-b"), "secret.md").apply { writeText("nope") }
        val ws = SessionWorkspace.hostDir(filesDir, "sess-a", "workspace")
        val leak = File(ws, "leak")
        Assume.assumeTrue(
            "symlinks unavailable on this host",
            symlink(leak, java.nio.file.Paths.get("../../..")),
        )

        // 越权路径一律 null（fail-closed）——绝不允许「通过但回传词法路径」。
        assertNull(SessionWorkspace.verifiedCanonical(filesDir, "sess-a", File(leak, "minis-sessions/sess-b/memory/secret.md")))
        assertNull(SessionWorkspace.verifiedCanonical(filesDir, "sess-a", secret))

        // 尚不存在的文件（file_write 建新文件的形状）：整条路径 canonical 化不可能。
        // [T-symlink-realpath-failclosed] 现在锚定「最深的存在祖先」：祖先走 realpath，
        // 尾部分量按词法追加，归属判定与修复前一致（仍然被接受），回传的就是这条锚定
        // 路径。无软链的临时目录里它与绝对路径逐字相同；以 real 祖先为基准断言是为了
        // 在 macOS（/var → /private/var）这类宿主上不比一个必然不同的字符串。
        val fresh = File(ws, "brand-new.txt")
        assertFalse(fresh.exists())
        val anchoredFresh = File(ws.toPath().toRealPath().toFile(), "brand-new.txt")
        assertEquals(
            anchoredFresh.path,
            SessionWorkspace.verifiedCanonical(filesDir, "sess-a", fresh)?.path,
        )
    }

    private fun symlink(link: File, target: java.nio.file.Path): Boolean =
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), target)
            true
        } catch (_: Exception) {
            false
        }
}
