package com.openminis.app.tools

import com.openminis.app.data.FileMentionIndex
import com.openminis.app.sandbox.SessionWorkspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [T-tools-files-walk-jail] 递归读取的归属复检回归。
 *
 * 现场：guest 侧在工作区里 `ln -s ../../.. leak`（proot 的 `--link2symlink` 只翻译
 * link(2)，不改写 symlink 目标，所以这就是宿主上的真实软链），随后
 * `grep_source(path=/var/minis/workspace)` / `glob` 的 `**` 递归 / `code_graph` 的递归都会
 * 跟着软链走到别的会话甚至宿主目录——实测 `minis-sessions/<别的会话>/memory/secret.md`
 * 被读了出来，一趟命中 63 个敏感文件。根路径的归属是判定过的，**遍历出来的每一项**没有。
 *
 * 注：通配符的原始写法是「星号紧跟斜杠」的字面形式，那会把这段 KDoc 提前闭合、整个文件
 * 编不过，所以上面那句刻意改写成 `**` 递归；断言一字未动。
 *
 * 这里用真实的宿主布局（filesDir/minis-sessions/<会话>/{workspace,memory}）复刻，
 * 直接钉住三个工具的文件集合：越权软链不许出现在结果里，根内的软链不许被误杀。
 *
 * [T-tools-files-walk-realpath] 第二轮：遍历改为产出 **canonical（real）路径**，于是
 * 「复检通过 → 调用方 open」之间不再夹着一条可以被 guest 改写的软链；@-mention 索引
 * （FileMentionIndex）与 list_dir 的一级列举同样逐项复检。对应用例在本文件后半段。
 */
class SessionTreeWalkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** filesDir + 本会话工作区 + 另一个会话的 memory/secret.md。 */
    private data class Tree(val filesDir: File, val workspace: File, val secret: File)

    private fun tree(): Tree {
        val filesDir = File(tmp.root, "files")
        val workspace = File(filesDir, "minis-sessions/sess-a/workspace").apply { mkdirs() }
        val secret = File(filesDir, "minis-sessions/sess-b/memory/secret.md")
        secret.parentFile.mkdirs()
        secret.writeText("OTHER-SESSION-SECRET")
        return Tree(filesDir, workspace, secret)
    }

    private fun symlink(link: File, target: File): Boolean =
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            true
        } catch (_: Exception) {
            false
        }

    /** `ln -s ../../.. leak` 的等价物；宿主不支持软链时跳过用例。 */
    private fun leakInto(workspace: File, filesDir: File) {
        val link = File(workspace, "leak")
        Assume.assumeTrue("symlinks unavailable on this host", symlink(link, filesDir))
        Assume.assumeTrue("symlink was not created", Files.isSymbolicLink(link.toPath()))
    }

    // ---------- 三个工具的收集阶段 ----------

    @Test
    fun `grep_source never collects files outside the workspace`() {
        val t = tree()
        File(t.workspace, "app.kt").writeText("fun main() {}\n")
        leakInto(t.workspace, t.filesDir)

        val files = GrepSourceTool.searchableFiles(t.workspace)

        assertTrue(
            "the workspace's own file must still be searchable: ${files.map { it.path }}",
            files.any { it.name == "app.kt" },
        )
        assertFalse(
            "a symlinked directory must not let grep_source read another session: ${files.map { it.path }}",
            files.any { it.canonicalPath == t.secret.canonicalPath },
        )
        assertFalse(files.any { it.path.contains("sess-b") })
    }

    @Test
    fun `glob never lists files outside the root`() {
        val t = tree()
        File(t.workspace, "app.kt").writeText("x")
        leakInto(t.workspace, t.filesDir)

        val hits = GlobTool.collectHits(t.workspace, GlobTool.globToRegex("**/*"))

        assertTrue(hits.any { it.name == "app.kt" })
        assertFalse("glob escaped the root: ${hits.map { it.path }}", hits.any { it.path.contains("sess-b") })
    }

    @Test
    fun `the code indexer never walks outside the root`() {
        val t = tree()
        File(t.workspace, "app.kt").writeText("fun main() {}\n")
        leakInto(t.workspace, t.filesDir)

        val walked = CodeGraphIndexer.walkSourceFiles(t.workspace).map { it.path }.toList()

        assertTrue("the workspace's own source must still be indexed: $walked", walked.any { it.endsWith("app.kt") })
        assertFalse("the indexer escaped the root: $walked", walked.any { it.contains("sess-b") })
        assertFalse(walked.any { it.contains("secret.md") })
    }

    // ---------- 判定本身 ----------

    @Test
    fun `the guard rejects a canonical escape even on hosts without symlinks`() {
        val t = tree()
        val guard = SessionTreeGuard(t.workspace)
        val inside = File(t.workspace, "sub/inner.txt").apply {
            parentFile.mkdirs()
            writeText("x")
        }

        assertTrue(guard.owns(inside))
        assertTrue(guard.owns(File(t.workspace, "sub")))
        // `..` 爬出根：canonical 之后不在根内
        assertFalse(guard.owns(File(t.workspace, "../memory/secret.md")))
        assertFalse(guard.owns(t.secret))
        assertFalse(guard.owns(File(t.workspace, "../../../../etc/hostname")))
    }

    @Test
    fun `a symlink that stays inside the root is still followed`() {
        val t = tree()
        File(t.workspace, "real/inner.txt").apply {
            parentFile.mkdirs()
            writeText("x")
        }
        Assume.assumeTrue("symlinks unavailable on this host", symlink(File(t.workspace, "alias"), File(t.workspace, "real")))
        assertTrue("an in-root symlink is not an escape", SessionTreeGuard(t.workspace).owns(File(t.workspace, "alias")))

        val files = GrepSourceTool.searchableFiles(t.workspace)

        assertTrue(
            "an in-root symlink must not be over-blocked: ${files.map { it.path }}",
            files.any { it.path.endsWith("inner.txt") },
        )
    }

    @Test
    fun `a self-referential symlink cannot turn the walk into an infinite recursion`() {
        val t = tree()
        File(t.workspace, "a.txt").writeText("x")
        Assume.assumeTrue("symlinks unavailable on this host", symlink(File(t.workspace, "self"), t.workspace))

        // 用独立线程 + 超时：真的无穷递归会 StackOverflowError / 永远不返回，而不是静默通过。
        val pool = Executors.newSingleThreadExecutor()
        try {
            val walked = pool.submit<List<String>> {
                walkOwnedTree(t.workspace).map { it.path }.toList()
            }.get(20, TimeUnit.SECONDS)

            assertTrue("the real file must still be found: $walked", walked.any { it.endsWith("a.txt") })
            assertTrue("the walk must stay bounded, got ${walked.size} nodes", walked.size < 50)
        } finally {
            pool.shutdownNow()
        }
    }

    // ---------- [T-tools-files-walk-realpath] 产出 real 路径：复检 → open 无窗口 ----------

    /**
     * 现场：walk 旧实现产出**词法** child，调用方随后按词法路径 `open`。guest 只要在
     * 「复检通过」与「open」之间把软链改向根外，读到的就是根外文件（判定看的是那一刻的
     * canonical 目标，open 会重新解析一遍软链）——100% 可赢。
     *
     * 这里把遍历根本身设成软链，使每一个产出路径都必然经过它（不依赖 listFiles 顺序，
     * 用例是确定性的）：旧实现下产出的是 `.../alias/a.txt`，改向之后就能读出 SECRET；
     * 新实现产出 `.../real/a.txt`，改向软链对它没有任何影响。
     */
    @Test
    fun `the walk yields real paths so a flipped symlink cannot redirect a read`() {
        val t = tree()
        val realDir = File(t.workspace, "real").apply { mkdirs() }
        File(realDir, "a.txt").writeText("INSIDE")
        val outside = File(t.filesDir, "minis-sessions/sess-b/memory").apply { mkdirs() }
        File(outside, "a.txt").writeText("SECRET")
        val linkRoot = File(t.workspace, "alias")
        Assume.assumeTrue("symlinks unavailable on this host", symlink(linkRoot, realDir))

        val walked = walkOwnedTree(linkRoot).toList()

        // guest 在「复检 → open」窗口内把软链改向根外
        Files.delete(linkRoot.toPath())
        Assume.assumeTrue("could not re-point the symlink", symlink(linkRoot, outside))

        val contents = walked.mapNotNull { f -> runCatching { f.readText() }.getOrNull() }
        assertTrue(
            "the walk must yield the real path, not the rewriteable link path: ${walked.map { it.path }}",
            walked.none { it.path.contains("alias") },
        )
        assertFalse("a flipped symlink redirected a read: $contents", contents.any { it.contains("SECRET") })
        assertTrue("the root's own file must still be readable: $contents", contents.any { it.contains("INSIDE") })
    }

    /** 相对路径的基准必须是 canonical 根；根外必须返回 null（fail-closed）而不是 `../../…`。 */
    @Test
    fun `relative paths are measured against the canonical root`() {
        val t = tree()
        File(t.workspace, "sub/inner.txt").apply {
            parentFile.mkdirs()
            writeText("x")
        }
        val guard = SessionTreeGuard(t.workspace)
        val inner = walkOwnedTree(t.workspace, guard).first { it.name == "inner.txt" }

        assertEquals("sub/inner.txt", guard.relativePath(inner))
        assertEquals(2, guard.depthOf(inner))
        assertEquals(1, guard.depthOf(inner.parentFile!!))
        assertNull("no relative path may be produced for an out-of-root file", guard.relativePath(t.secret))
        assertNull(guard.depthOf(t.secret))
    }

    // ---------- [T-walk-cage-single-resolve] 复检过的根只解析一次 ----------

    /**
     * 现场（第四轮残留）：调用方经 `resolveSessionHostPath` → `acceptsResolved` 复检，
     * 但旧 API 只回 Boolean，工具侧（`SessionTreeGuard` / `walkOwnedTree`）拿到词法根之后
     * **又解析了一次**，并以解析出来的新目标为根。guest 只要在两次解析之间把中间分量
     * （这里是 `workspace/sub`）rename 掉、原地放一条指向根外的软链，守卫的新根就是根外
     * 目录，包含判定与新根自洽 —— 不报错、不丢弃，整棵根外子树被列出来。
     *
     * 修法：`resolveSessionHostPath` 回传复检时那条 canonical 路径，工具侧用
     * [SessionTreeGuard.verified] 把它钉成基准（[SessionTreeGuard.canonicalRoot] 不再解析），
     * [walkOwnedTree] 另外确认根此刻仍解析到同一目标，解析不到就整棵不产出。
     * 这样顶替分量之后的结果是「什么都不产出」（fail-closed 的少读），而不是「列出别人的树」。
     */
    @Test
    fun `a verified root is pinned even after its last component is swapped for a symlink`() {
        val t = tree()
        val sub = File(t.workspace, "sub").apply { mkdirs() }
        File(sub, "inside.txt").writeText("INSIDE")
        val outside = File(t.filesDir, "minis-sessions/sess-b/memory").apply { mkdirs() }
        File(outside, "secret.md").writeText("SECRET")

        // resolveSessionHostPath 的等价物：复检 + canonical 化，回传它判定时用的那条路径。
        val verified = SessionWorkspace.verifiedCanonical(t.filesDir, "sess-a", sub)
        assertNotNull("sub must be inside sess-a's own tree", verified)
        val root = verified!!

        // guest 在「复检」与「遍历」之间把最后一级目录分量换成软链。
        Assume.assumeTrue("could not move the directory aside", sub.renameTo(File(t.workspace, "sub-moved")))
        Assume.assumeTrue("symlinks unavailable on this host", symlink(sub, outside))

        val guard = SessionTreeGuard.verified(root)

        assertEquals(
            "a verified guard must pin the checked path instead of resolving it again",
            root.path,
            guard.canonicalRoot.path,
        )
        val walked = walkOwnedTree(root, guard).map { it.path }.toList()
        assertTrue("the swapped root must yield nothing, got $walked", walked.isEmpty())

        // 工具侧的调用形状一模一样（execute 需要 Context，这里直接钉住它交给助手函数的那个守卫）。
        val files = GrepSourceTool.searchableFiles(root, guard = guard)
        assertFalse("grep_source escaped through the swapped component: ${files.map { it.path }}", files.any { it.path.contains("sess-b") })
        assertFalse(files.any { it.name == "secret.md" })

        // 结构：四个助手都必须**用调用方给的那枚守卫**——在内部自己重建守卫就等于工具侧第二次
        // 解析。先做差异对照（默认守卫＝修复前的形状，会以新目标为根），再钉住钉子的效果。
        val globDefault = GlobTool.collectHits(root, GlobTool.globToRegex("**/*"))
        assertTrue(
            "control: the default guard still re-roots at the swapped target: ${globDefault.map { it.path }}",
            globDefault.any { it.path.contains("sess-b") },
        )
        val globPinned = GlobTool.collectHits(root, GlobTool.globToRegex("**/*"), guard = guard)
        assertTrue("glob must obey the caller's pinned guard: ${globPinned.map { it.path }}", globPinned.isEmpty())
        val indexed = CodeGraphIndexer.walkSourceFiles(root, guard).map { it.path }.toList()
        assertTrue("code_graph must obey the caller's pinned guard: $indexed", indexed.isEmpty())
        val kids = ListDirTool.ownedChildren(root, guard).map { it.name }
        assertTrue("list_dir must obey the caller's pinned guard: $kids", kids.isEmpty())
    }

    /** 钉子不能把正常树也挡掉：四个工具在「复检过的 canonical 根 + 钉住的守卫」下照常工作。 */
    @Test
    fun `a pinned guard still walks a healthy tree`() {
        val t = tree()
        File(t.workspace, "healthy/ok.kt").apply {
            parentFile.mkdirs()
            writeText("fun ok() {}\n")
        }
        val verified = SessionWorkspace.verifiedCanonical(t.filesDir, "sess-a", File(t.workspace, "healthy"))
        assertNotNull("the healthy dir must be inside sess-a's own tree", verified)
        val guard = SessionTreeGuard.verified(verified!!)

        assertTrue(
            "grep_source must still search a pinned tree",
            GrepSourceTool.searchableFiles(verified, guard = guard).any { it.name == "ok.kt" },
        )
        assertTrue(
            "glob must still match in a pinned tree",
            GlobTool.collectHits(verified, GlobTool.globToRegex("**/*"), guard = guard).any { it.name == "ok.kt" },
        )
        assertTrue(
            "code_graph must still walk a pinned tree",
            CodeGraphIndexer.walkSourceFiles(verified, guard).any { it.name == "ok.kt" },
        )
        assertTrue(
            "list_dir must still list a pinned tree",
            ListDirTool.ownedChildren(verified, guard).any { it.name == "ok.kt" },
        )
    }

    /**
     * 钉子只在 [SessionTreeGuard.verified] 上生效：默认工厂仍然自己解析（@-mention 索引的根
     * 没有经过归属复检，解析结果就是它的基准），否则 `walkOwnedTree(词法根)` 这种用法会
     * 在根是软链时被整棵挡掉。
     */
    @Test
    fun `the default guard still resolves a symlinked root`() {
        val t = tree()
        val realDir = File(t.workspace, "real").apply { mkdirs() }
        File(realDir, "a.txt").writeText("INSIDE")
        val linkRoot = File(t.workspace, "alias")
        Assume.assumeTrue("symlinks unavailable on this host", symlink(linkRoot, realDir))

        val guard = SessionTreeGuard(linkRoot)

        assertEquals(realDir.toPath().toRealPath().toFile().path, guard.canonicalRoot.path)
        assertTrue(
            "the default guard must keep following an in-root symlink root",
            walkOwnedTree(linkRoot, guard).any { it.name == "a.txt" },
        )
    }

    // ---------- [T-walk-root-self] 自指软链不能把根列成自己的子目录 ----------

    /**
     * 现场：工作区里 `ln -s . self`。`self` 的 canonical 就是工作区自身，能通过归属判定，
     * 于是旧实现把它当成子项产出——`list_dir` 里就多出一个「名字等于工作区 basename」的
     * 目录，看起来像是工作区里套着工作区。按 canonical 去重之后它必须消失。
     */
    @Test
    fun `list_dir never lists the root itself through a self-referential symlink`() {
        val t = tree()
        File(t.workspace, "app.kt").writeText("hello")
        Assume.assumeTrue("symlinks unavailable on this host", symlink(File(t.workspace, "self"), t.workspace))

        val names = ListDirTool.ownedChildren(t.workspace).map { it.name }

        assertTrue("the real file must still be listed: $names", names.contains("app.kt"))
        assertFalse(
            "the root must not appear as one of its own children: $names",
            names.contains(t.workspace.name),
        )
        assertEquals("only the real child may be listed: $names", 1, names.size)
    }

    /** 同一条规则也作用于 walk 本身：自指软链不产出，且不会让遍历无限递归。 */
    @Test
    fun `the walk never yields the root itself through a self-referential symlink`() {
        val t = tree()
        File(t.workspace, "app.kt").writeText("hello")
        Assume.assumeTrue("symlinks unavailable on this host", symlink(File(t.workspace, "self"), t.workspace))

        val guard = SessionTreeGuard(t.workspace)
        val walked = walkOwnedTree(t.workspace, guard).map { it.name }.toList()

        assertEquals("only the real child may be yielded: $walked", listOf("app.kt"), walked)
        assertTrue("the root must have depth 0", guard.depthOf(guard.canonicalRoot) == 0)
    }

    // ---------- @-mention 索引（FileMentionIndex.scanDir）----------

    private fun mentionIndex(filesDir: File) = FileMentionIndex(filesDir, scope = CoroutineScope(Job()))

    private fun mentionPaths(
        index: FileMentionIndex,
        root: File,
        maxDepth: Int = Int.MAX_VALUE,
        budget: Int = FileMentionIndex.GLOBAL_SCAN_BUDGET,
    ): List<String> = index.scanDir(
        root = root,
        linuxRoot = "/var/minis/workspace",
        scope = FileMentionIndex.Scope.WORKSPACE,
        mountName = null,
        maxDepth = maxDepth,
        budget = budget,
    ).map { it.linuxPath }

    @Test
    fun `the mention index never lists paths under an escaped symlink`() {
        val t = tree()
        File(t.workspace, "app.kt").writeText("x")
        leakInto(t.workspace, t.filesDir)

        val paths = mentionPaths(mentionIndex(t.filesDir), t.workspace)

        assertTrue("the workspace's own file must still be indexed: $paths", paths.contains("/var/minis/workspace/app.kt"))
        assertFalse("the index listed the escaped link itself: $paths", paths.any { it.contains("leak") })
        assertFalse("the index listed another session's files: $paths", paths.any { it.contains("sess-b") })
    }

    /**
     * 深度语义与旧 BFS 队列逐项对齐：深度 ≤ maxDepth 的目录会被展开，条目深度 ≤ maxDepth+1。
     * 会话层用的是 `Int.MAX_VALUE`——写成 `depth > maxDepth + 1` 时它会溢出成负数、把全量
     * 扫描变成空集，所以这里同时钉住「最深的那层必须还在」。
     */
    @Test
    fun `the mention index scans the whole tree at Int MAX_VALUE depth`() {
        val t = tree()
        for (rel in listOf("f0.txt", "d1/f1.txt", "d1/d2/f2.txt", "d1/d2/d3/f3.txt", "d1/d2/d3/d4/f4.txt")) {
            File(t.workspace, rel).apply {
                parentFile.mkdirs()
                writeText("x")
            }
        }
        val paths = mentionPaths(mentionIndex(t.filesDir), t.workspace)

        assertTrue("depth 5 must be visible at Int.MAX_VALUE: $paths", paths.any { it.endsWith("/d1/d2/d3/d4/f4.txt") })
    }

    @Test
    fun `mount scans stay within the configured depth`() {
        val t = tree()
        for (rel in listOf("f0.txt", "d1/f1.txt", "d1/d2/f2.txt", "d1/d2/d3/f3.txt", "d1/d2/d3/d4/f4.txt")) {
            File(t.workspace, rel).apply {
                parentFile.mkdirs()
                writeText("x")
            }
        }
        val index = mentionIndex(t.filesDir)
        val paths = index.scanDir(
            root = t.workspace,
            linuxRoot = "/var/minis/mounts/m",
            scope = FileMentionIndex.Scope.MOUNT,
            mountName = "m",
            maxDepth = FileMentionIndex.MOUNT_SCAN_MAX_DEPTH,
            budget = FileMentionIndex.GLOBAL_SCAN_BUDGET,
        ).map { it.linuxPath }

        assertTrue("depth 4 is still listed at maxDepth=3: $paths", paths.any { it.endsWith("/d1/d2/d3/f3.txt") })
        assertFalse("depth 5 must be cut off at maxDepth=3: $paths", paths.any { it.endsWith("/f4.txt") })
    }

    /** 预算不够时先保住顶层条目（旧实现是 BFS 队列，DFS 顺序会让一棵深子树吃光预算）。 */
    @Test
    fun `the mention index spends its budget on the shallowest entries first`() {
        val t = tree()
        for (rel in listOf("f0.txt", "d1/f1.txt", "d1/d2/f2.txt", "d1/d2/d3/f3.txt")) {
            File(t.workspace, rel).apply {
                parentFile.mkdirs()
                writeText("x")
            }
        }
        val paths = mentionPaths(mentionIndex(t.filesDir), t.workspace, budget = 2)

        assertEquals("budget must cap the result: $paths", 2, paths.size)
        assertTrue(
            "budget must go to depth-1 entries first: $paths",
            paths.all { '/' !in it.removePrefix("/var/minis/workspace/") },
        )
    }

    /**
     * [T-mention-budget-visible-nodes] 现场：工作区里 300 个点文件 + 一棵可见子树，
     * `budget = 10`。旧实现把**走过的每一个节点**都计进预算（`budget * 2 + 256` = 276），
     * 点文件把预算吃光之后可见子树整棵静默消失——用户看到的是「工作区里的文件不见了」。
     * 预算只数会入条目的节点之后，过滤掉的点文件不再挤占可见条目。
     *
     * （listFiles 的顺序由文件系统决定，所以这里钉的是「可见条目必须在结果里」这一不变式，
     * 而不是某个具体顺序；隐藏项无论落在哪个位置都不许入索引。）
     */
    @Test
    fun `hidden files never spend the visible entry budget`() {
        val t = tree()
        for (i in 0 until 300) File(t.workspace, ".hidden-$i").writeText("x")
        File(t.workspace, "visible/dir/inside.txt").apply {
            parentFile.mkdirs()
            writeText("x")
        }

        val paths = mentionPaths(mentionIndex(t.filesDir), t.workspace, budget = 10)

        assertTrue("the visible file must survive 300 hidden siblings: $paths", paths.any { it.endsWith("/dir/inside.txt") })
        assertTrue("its ancestor directories must be there too: $paths", paths.any { it.endsWith("/visible") })
        assertFalse("hidden entries are never indexed: $paths", paths.any { it.contains(".hidden-") })
    }

    // ---------- list_dir 的一级列举 ----------

    @Test
    fun `list_dir hides children that resolve outside the listed directory`() {
        val t = tree()
        File(t.workspace, "app.kt").writeText("hello")
        leakInto(t.workspace, t.filesDir)

        val kids = ListDirTool.ownedChildren(t.workspace)
        val names = kids.map { it.name }

        assertTrue("the directory's own file must still be listed: $names", names.contains("app.kt"))
        assertFalse("an escaped symlink must not be listed at all: $names", names.contains("leak"))
        assertEquals("an escaped symlink must not be stat'ed either", listOf(5L), kids.filter { it.name == "app.kt" }.map { it.length() })
    }

    /** 根内的软链不许被误杀：它的**目标**照常出现（canonical 化后与目标同一条），且不重复。 */
    @Test
    fun `list_dir lists an in-root symlink target exactly once`() {
        val t = tree()
        File(t.workspace, "app.kt").writeText("hello")
        Assume.assumeTrue("symlinks unavailable on this host", symlink(File(t.workspace, "link.txt"), File(t.workspace, "app.kt")))

        val names = ListDirTool.ownedChildren(t.workspace).map { it.name }

        assertEquals("the target must be listed once, not twice: $names", 1, names.count { it == "app.kt" })
    }
}
