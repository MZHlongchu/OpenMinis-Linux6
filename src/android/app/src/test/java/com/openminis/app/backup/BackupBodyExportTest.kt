package com.openminis.app.backup

import com.openminis.app.data.body.BodyGcPins
import com.openminis.app.data.body.BodyStore
import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.MessageVersionDao
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * [T-bodies-gc-backup-align] 备份正文与行必须在同一个时间点冻结。
 *
 * 事故形态（本测试锁住的就是它）：`BackupExporter` 先导出 `messages.jsonl`，之后才回头读
 * 「哪些正文还被引用」。两个时间点之间用户删掉一个会话会触发正文回收 —— 等到读引用集合时
 * 那些 ref 已经从库里消失，正文被当成孤儿跳过，于是包里留下引用它们的行却没有正文：恢复后
 * `body_ref` 悬空、那些消息永久退回 stub。
 *
 * 本模块没有 Robolectric，Room 的 DAO 跑不起来，所以用 JDK 动态代理做只回应本测试真正调用
 * 到的方法的假 DAO（与 MessageBodyRefTest 同口径）。
 */
class BackupBodyExportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun clearPinsBefore() {
        BodyGcPins.clearForTests()
    }

    @After
    fun clearPinsAfter() {
        BodyGcPins.clearForTests()
    }

    private fun <T> proxy(type: Class<T>, block: (String, Array<out Any?>) -> Any?): T {
        val handler = InvocationHandler { proxyRef, method, rawArgs ->
            val args: Array<Any?> = if (rawArgs == null) emptyArray() else Array(rawArgs.size) { rawArgs[it] }
            when (method.name) {
                "equals" -> proxyRef === args.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxyRef)
                "toString" -> "fake:${type.simpleName}"
                else -> block(method.name, args)
            }
        }
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(type.classLoader, arrayOf<Class<*>>(type), handler) as T
    }

    /** [refs] 可变：模拟"导出期间会话被删，库里的引用消失"。 */
    private class Refs {
        var messages: List<String> = emptyList()
        var versions: List<String> = emptyList()
        var failing = false
        var messageReads = 0
        var versionReads = 0
    }

    private fun chatDao(r: Refs): ChatDao = proxy(ChatDao::class.java) { name, _ ->
        when (name) {
            "allBodyRefs" -> {
                r.messageReads++
                if (r.failing) error("database is gone")
                r.messages
            }
            else -> error("unexpected ChatDao call: $name")
        }
    }

    private fun versionDao(r: Refs): MessageVersionDao = proxy(MessageVersionDao::class.java) { name, _ ->
        when (name) {
            "allBodyRefs" -> {
                r.versionReads++
                if (r.failing) error("database is gone")
                r.versions
            }
            else -> error("unexpected MessageVersionDao call: $name")
        }
    }

    private fun bodiesRoot(): File = File(tmp.root, "bodies").apply { mkdirs() }

    /** 真正落一条 framed 正文，返回它的 ref（文件名）。 */
    private fun body(root: File, text: String): String {
        val put = BodyStore(root).put(text.toByteArray(Charsets.UTF_8))
        assertTrue(put.error, put.ok)
        return put.ref!!
    }

    private fun exportOnce(root: File, snapshot: BackupBodySnapshot): Pair<BodyExportResult, String> {
        val indexFile = File(tmp.root, "files.index-${System.nanoTime()}.jsonl")
        val result = BackupFileIndexWriter(indexFile).use { writer ->
            exportBodyFiles(root, BackupBlobStore(tmp.root, null), writer, snapshot)
        }
        val index = if (indexFile.isFile) indexFile.readText() else ""
        return result to index
    }

    // ─── 时间点对齐 ───────────────────────────────────────────────────────

    @Test
    fun `a body referenced at snapshot time is packaged even if the session is deleted mid-export`() =
        runBlocking {
            val root = bodiesRoot()
            val kept = body(root, "被删掉的会话仍然在包里引用我")
            val orphan = body(root, "没人引用我")
            val refs = Refs().apply { messages = listOf(kept) }
            val snapshot = BackupBodySnapshot.take(chatDao(refs), versionDao(refs))
            // 导出中途用户删掉了那个会话：库里已经没有任何行引用 kept
            refs.messages = emptyList()

            val (result, index) = exportOnce(root, snapshot)

            assertEquals("快照内的正文必须进包", 1, result.included)
            assertTrue("files.index 必须记下它", index.contains(kept))
            assertFalse("没人引用的正文不进包（包体积只增不减的老问题）", index.contains(orphan))
            assertEquals(1, result.skippedUnreferenced)
            assertEquals("包里不该有悬空引用", 0, result.missingReferenced)
            snapshot.release()
        }

    @Test
    fun `the snapshot covers both live rows and archived versions`() = runBlocking {
        val root = bodiesRoot()
        val live = body(root, "活行引用")
        val archived = body(root, "只有归档版本引用")
        val refs = Refs().apply {
            messages = listOf(live)
            versions = listOf(archived)
        }

        val snapshot = BackupBodySnapshot.take(chatDao(refs), versionDao(refs))
        val (result, index) = exportOnce(root, snapshot)

        assertEquals(2, result.included)
        assertTrue(index.contains(live))
        assertTrue("message_versions 的引用同样是「仍被引用」", index.contains(archived))
        assertTrue("两个来源都必须被读到", refs.messageReads > 0 && refs.versionReads > 0)
        snapshot.release()
    }

    @Test
    fun `a ref written into the package after the snapshot is still packaged`() = runBlocking {
        val root = bodiesRoot()
        val atSnapshot = body(root, "快照时就在")
        val writtenLater = body(root, "导出中途才写进包的行引用")
        val refs = Refs().apply { messages = listOf(atSnapshot) }

        val snapshot = BackupBodySnapshot.take(chatDao(refs), versionDao(refs))
        snapshot.recordWritten(writtenLater)
        snapshot.recordWritten(null)
        val (result, index) = exportOnce(root, snapshot)

        assertEquals(2, result.included)
        assertTrue("包里每一行都要有正文", index.contains(writtenLater))
        snapshot.release()
    }

    @Test
    fun `an unreadable reference set fails open and packages every body`() = runBlocking {
        val root = bodiesRoot()
        val first = body(root, "a")
        val second = body(root, "b")
        val refs = Refs().apply { failing = true }

        val snapshot = BackupBodySnapshot.take(chatDao(refs), versionDao(refs))
        val (result, index) = exportOnce(root, snapshot)

        assertTrue(snapshot.isComplete)
        assertEquals("读不出引用就按全部导出：宁可多带，不能缺正文", 2, result.included)
        assertEquals(0, result.skippedUnreferenced)
        assertEquals(0, result.missingReferenced)
        assertTrue(index.contains(first) && index.contains(second))
        assertTrue("读失败时不 pin 任何集合", BodyGcPins.snapshot().isEmpty())
        snapshot.release()
    }

    @Test
    fun `a referenced body that is missing on disk is reported instead of shipped silently`() =
        runBlocking {
            val root = bodiesRoot()
            val present = body(root, "在盘上")
            val refs = Refs().apply { messages = listOf(present, "f".repeat(64)) }

            val snapshot = BackupBodySnapshot.take(chatDao(refs), versionDao(refs))
            val (result, _) = exportOnce(root, snapshot)

            assertEquals(1, result.included)
            assertEquals("本来就已经悬空的正文补不回来，但必须计数上报", 1, result.missingReferenced)
            snapshot.release()
        }

    // ─── pin 生命周期 ────────────────────────────────────────────────────

    @Test
    fun `taking a snapshot pins its refs so a concurrent reclaim cannot unlink them`() = runBlocking {
        val refs = Refs().apply { messages = listOf("a".repeat(64)) }

        val snapshot = BackupBodySnapshot.take(chatDao(refs), versionDao(refs))
        assertTrue("拷贝正文之前这些文件不许被回收", BodyGcPins.isPinned("a".repeat(64)))

        snapshot.release()
        assertFalse("导出结束必须解除 pin（否则孤儿正文再也不回收）", BodyGcPins.isPinned("a".repeat(64)))
        assertTrue(BodyGcPins.snapshot().isEmpty())
    }

    /**
     * [T-bodies-gc-pin] 回归：登记项必须是身份相等的稳定 key。曾经直接拿可变的 ref 集合当
     * key，`recordWritten` 改了它的内容 → `hashCode` 变 → close 时的 remove 落错桶 → pin 永
     * 远解除不掉（正是 JVM 探针把这个 bug 抓出来的）。
     */
    @Test
    fun `a snapshot that records more refs can still be released`() = runBlocking {
        val refs = Refs().apply { messages = listOf("a".repeat(64)) }

        val snapshot = BackupBodySnapshot.take(chatDao(refs), versionDao(refs))
        snapshot.recordWritten("b".repeat(64))
        snapshot.recordWritten("c".repeat(64))
        snapshot.release()

        assertFalse(BodyGcPins.isPinned("a".repeat(64)))
        assertFalse(BodyGcPins.isPinned("b".repeat(64)))
        assertTrue("集合内容变了也要能解除 pin", BodyGcPins.snapshot().isEmpty())
    }
}
