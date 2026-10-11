package com.openminis.app.data.repository

import com.openminis.app.backup.BackupBodySnapshot
import com.openminis.app.data.body.Admission
import com.openminis.app.data.body.BodyGcPins
import com.openminis.app.data.body.BodyRefLedger
import com.openminis.app.data.body.BodyStore
import com.openminis.app.data.body.ResourceLimits
import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.GoalDao
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.db.MessageSearchRow
import com.openminis.app.data.db.MessageVersionDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch

/**
 * 正文（BodyStore）引用语义的回归测试：
 *
 * - [ChatRepository.copyMessageInto]（[T-fork-copy-body-ref]）：复制会话 / checkpoint
 *   fork 必须搬 `body_ref/body_sha/body_bytes/preview`，不能把 offload 后的展示
 *   stub 当正文重新落库——否则副本里所有 >2KB 的消息永久丢失，而且 stub 文本会
 *   直接进模型上下文；
 * - [ChatRepository.appendMessage]（[T-android-preview-real-body]）：`sessions.
 *   last_message` 必须取真身文本，不是 `body stored (N bytes)`；
 * - [ChatRepository.loadRawPartsForRow]（[T-translate-writebody]）：翻译回写要读真身，
 *   真身读不到时返回 null 而不是把 stub 当正文写回去；
 * - [ChatRepository.collectOrphanBodies] / [ChatRepository.deleteSession]
 *   （[T-bodies-gc]）：`files/bodies/` 按全库引用回收，宽限期内不误删；
 * - [ChatRepository.searchMessages]（[T-search-admission-not-empty]）：进程级预算被占
 *   不能变成「没有命中」。
 *
 * Room 的 DAO 在单测里没有实现（需要 Android），所以这里用 JDK 动态代理做一个只
 * 回应本测试真正调用到的方法的假 DAO——挂起方法由代理直接返回值即可（调用方只在
 * 收到 COROUTINE_SUSPENDED 时才进入挂起态，与 Room 生成代码的快路径一致）。
 */
class MessageBodyRefTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * pin 与「最近被声明引用」都是**进程级**登记表（[BodyGcPins] / [BodyRefLedger]）：
     * 测试之间必须清干净，否则上一条测试按住/声明的 ref 会让下一条测试的回收静默失效。
     */
    @Before
    fun clearBodyRegistriesBefore() {
        BodyGcPins.clearForTests()
        BodyRefLedger.clearForTests()
    }

    @After
    fun clearBodyRegistriesAfter() {
        BodyGcPins.clearForTests()
        BodyRefLedger.clearForTests()
    }

    private class Recorded {
        val inserted = mutableListOf<MessageEntity>()
        val lastMessages = mutableListOf<Pair<String, String?>>()
        val touched = mutableListOf<String>()
        var messageRows: Map<String, MessageEntity> = emptyMap()

        /** `allBodyRefs()`：全库仍被引用的正文。 */
        var allBodyRefs: List<String> = emptyList()

        /**
         * [T-bodies-gc-recheck] 按读取次数依次返回的 live 集合：用来模拟"第一遍判定
         * 与 unlink 之间另一条新行才提交"的窗口。用完后一直返回最后一个。
         */
        var allBodyRefsSequence: List<List<String>> = emptyList()
        var allBodyRefsReads = 0

        /** `bodyRefsForSession()`：某个会话引用到的正文（删除前的候选集）。 */
        var sessionBodyRefs: List<String> = emptyList()

        /** [T-bodies-gc-batch] 按会话给出的候选集；缺省回落到 [sessionBodyRefs]。 */
        var sessionBodyRefsBySession: Map<String, List<String>> = emptyMap()
        var sessionBodyRefsQueries = 0
        var searchRows: List<MessageSearchRow> = emptyList()
        var searchQueries = 0
        val deletedSessions = mutableListOf<String>()

        /**
         * [T-bodies-gc] `message_versions` 分支：归档版本同样引用正文
         * （`MessageVersionDao.allBodyRefs()`）。
         */
        var versionBodyRefs: List<String> = emptyList()

        /** `MessageVersionDao.bodyRefsForSession()`：某会话的归档版本引用。 */
        var versionSessionBodyRefs: List<String> = emptyList()
        var versionSessionBodyRefsBySession: Map<String, List<String>> = emptyMap()
        var versionAllBodyRefsReads = 0
        var versionSessionBodyRefsQueries = 0

        /** 回收侧真正执行 File 层工作时所在的线程名（用来证明它不在调用者线程上）。 */
        var liveRefsThread: String = ""
    }

    /** 只实现本测试用到的方法；其余调用直接失败，免得测试悄悄跑偏。 */
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
        val interfaces: Array<Class<*>> = arrayOf(type)
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(type.classLoader, interfaces, handler) as T
    }

    private fun chatDao(r: Recorded): ChatDao = proxy(ChatDao::class.java) { name, args ->
        when (name) {
            "appendMessage" -> {
                val message = args[0] as MessageEntity
                val stored = message.copy(sortOrder = r.inserted.size)
                r.inserted += stored
                stored
            }
            "updateLastMessage" -> {
                r.lastMessages += (args[0] as String) to (args[1] as String?)
                Unit
            }
            "touchSession" -> {
                r.touched += args[0] as String
                Unit
            }
            "messageById" -> r.messageRows[args[0] as String]
            "allBodyRefs" -> {
                r.allBodyRefsReads++
                r.liveRefsThread = Thread.currentThread().name
                val sequence = r.allBodyRefsSequence
                if (sequence.isEmpty()) {
                    r.allBodyRefs
                } else {
                    sequence[minOf(r.allBodyRefsReads - 1, sequence.size - 1)]
                }
            }
            "bodyRefsForSession" -> {
                r.sessionBodyRefsQueries++
                r.sessionBodyRefsBySession[args[0] as String] ?: r.sessionBodyRefs
            }
            "deleteMessages" -> Unit
            "deleteSession" -> {
                r.deletedSessions += args[0] as String
                Unit
            }
            "runMessageSearchQuery" -> {
                r.searchQueries++
                r.searchRows
            }
            else -> error("unexpected ChatDao call: $name")
        }
    }

    private fun goalDao(): GoalDao = proxy(GoalDao::class.java) { name, _ ->
        when (name) {
            "delete" -> Unit
            else -> error("unexpected GoalDao call: $name")
        }
    }

    /** [T-bodies-gc] `message_versions` 分支：归档版本同样引用正文。 */
    private fun versionDao(r: Recorded): MessageVersionDao =
        proxy(MessageVersionDao::class.java) { name, args ->
            when (name) {
                "allBodyRefs" -> {
                    r.versionAllBodyRefsReads++
                    r.liveRefsThread = Thread.currentThread().name
                    r.versionBodyRefs
                }
                "bodyRefsForSession" -> {
                    r.versionSessionBodyRefsQueries++
                    r.versionSessionBodyRefsBySession[args[0] as String] ?: r.versionSessionBodyRefs
                }
                else -> error("unexpected MessageVersionDao call: $name")
            }
        }

    private fun repository(r: Recorded, filesDir: File? = null): ChatRepository =
        ChatRepository(chatDao(r), goalDao(), filesDir)

    private fun repositoryWithVersions(r: Recorded, filesDir: File? = null): ChatRepository =
        ChatRepository(chatDao(r), goalDao(), filesDir, versionDao(r))

    private fun oldFile(root: File, name: String, text: String): File {
        val file = File(root, name).apply { writeText(text) }
        // 回收宽限期之外（BodyStore.put 先落盘、后写指针，宽限期内不动文件）
        assertTrue(file.setLastModified(System.currentTimeMillis() - 11L * 60L * 1000L))
        return file
    }

    // ─── data-db#2：fork 搬引用，不搬 stub ────────────────────────────────

    @Test
    fun `copyMessageInto carries the body reference instead of re-storing the stub`() = runBlocking {
        val r = Recorded()
        val ref = "a".repeat(64)
        val stub = """[{"type":"text","value":"body stored (9000 bytes)"}]"""
        val head = """[{"type":"text","value":"the real answer"}]"""
        val source = MessageEntity(
            id = "src",
            sessionId = "s1",
            role = "assistant",
            partsJson = stub,
            createdAt = 1L,
            sortOrder = 0,
            bodyBytes = 9000L,
            bodyRef = ref,
            bodySha = ref,
            preview = head,
        )

        val copy = repository(r, tmp.root).copyMessageInto("dup", source)

        assertEquals("dup", copy.sessionId)
        assertEquals(ref, copy.bodyRef)
        assertEquals(ref, copy.bodySha)
        assertEquals(9000L, copy.bodyBytes)
        assertEquals(head, copy.preview)
        // parts_json 原样搬（真身在 bodies/<ref>），没有被当作正文重新落盘
        assertEquals(stub, copy.partsJson)
        // 会话列表预览取 preview 列里的真身文本，不是 stub 文案
        assertEquals("the real answer", r.lastMessages.last().second)
    }

    @Test
    fun `copyMessageInto re-stores a rewritten payload instead of keeping a stale ref`() = runBlocking {
        val r = Recorded()
        val stub = """[{"type":"text","value":"body stored (9000 bytes)"}]"""
        val source = MessageEntity(
            id = "src",
            sessionId = "s1",
            role = "assistant",
            partsJson = stub,
            createdAt = 1L,
            sortOrder = 0,
            bodyBytes = 9000L,
            bodyRef = "a".repeat(64),
            bodySha = "a".repeat(64),
            preview = stub,
        )
        val rewritten = """[{"type":"text","value":"translated"}]"""

        val copy = repository(r, tmp.root).copyMessageInto("dup", source, partsJson = rewritten)

        assertEquals(rewritten, copy.partsJson)
        // payload 变了就必须重新落盘：不能继续指向旧正文
        assertNull(copy.bodyRef)
        assertNull(copy.bodySha)
        assertEquals(rewritten.toByteArray(Charsets.UTF_8).size.toLong(), copy.bodyBytes)
        assertEquals("translated", r.lastMessages.last().second)
    }

    /**
     * [T-bodies-gc-fork-retain] fork 复制行原样搬源行的 `body_ref`：不写文件、不刷 mtime。
     * 回收侧完全可能已经读完全库引用（副本行那时还没落库），而源会话一旦并发被删，这个 ref
     * 在库里就只剩副本一行引用 —— 不声明这次引用，unlink 掉的就是活引用：副本的大消息永久
     * 退回 stub。所以复制必须走 [BodyRefLedger.retain]（BodyStore 提交锁内登记），回收侧的
     * 锁内逐文件确认才看得见它。
     */
    @Test
    fun `a body copied by a fork survives a reclaim round that read the live set too early`() = runBlocking {
        val r = Recorded()
        val root = File(tmp.root, "bodies").apply { mkdirs() }
        val payload = """[{"type":"text","value":"${"k".repeat(4000)}"}]"""
        val put = BodyStore(root).put(payload.toByteArray(Charsets.UTF_8))
        assertTrue(put.error, put.ok)
        val ref = put.ref!!
        val file = File(root, ref)
        // 这条正文早就过了宽限期：源会话被删之后，它在库里看起来就是孤儿
        assertTrue(file.setLastModified(System.currentTimeMillis() - 11L * 60L * 1000L))
        val source = MessageEntity(
            id = "src",
            sessionId = "s1",
            role = "assistant",
            partsJson = """[{"type":"text","value":"body stored (4000 bytes)"}]""",
            createdAt = 1L,
            sortOrder = 0,
            bodyBytes = payload.toByteArray(Charsets.UTF_8).size.toLong(),
            bodyRef = ref,
            bodySha = ref,
            preview = payload,
        )
        // 这一轮回收读得太早：副本的新行还没提交（源会话已被并发删除）
        r.allBodyRefs = emptyList()

        val copy = repository(r, tmp.root).copyMessageInto("dup", source)

        assertEquals("复制行仍然照搬引用，不重新落盘", ref, copy.bodyRef)
        assertEquals("被副本引用的正文不许在这一轮里被回收", 0, repository(r, tmp.root).collectOrphanBodies())
        assertTrue("正文被删 = 副本 body_ref 悬空、气泡永久退回 stub", file.isFile)
    }

    // ─── data-db#3：列表预览取真身 ───────────────────────────────────────

    @Test
    fun `appendMessage previews the true body when the row is offloaded`() = runBlocking {
        val r = Recorded()
        val answer = "长回答" + "x".repeat(3000)
        val parts = """[{"type":"text","value":"$answer"}]"""

        val inserted = repository(r, tmp.root).appendMessage("s1", "assistant", parts)

        assertNotNull(">2048 字节的正文应被 offload", inserted.bodyRef)
        assertEquals(parts.toByteArray(Charsets.UTF_8).size.toLong(), inserted.bodyBytes)
        val preview = r.lastMessages.last().second
        assertNotNull("offload 行必须写下预览", preview)
        val text = requireNotNull(preview)
        assertTrue("预览必须是正文，实际=$text", text.startsWith("长回答"))
        assertFalse("预览不能是 offload stub", text.contains("body stored"))
    }

    // ─── data-db#4：翻译回写读真身 ──────────────────────────────────────

    @Test
    fun `loadRawPartsForRow reads the offloaded body and refuses the stub`() = runBlocking {
        val r = Recorded()
        val body = """[{"type":"text","value":"${"y".repeat(5000)}"}]"""
        val put = BodyStore(File(tmp.root, "bodies")).put(body.toByteArray(Charsets.UTF_8))
        assertTrue(put.ok)
        val ref = put.ref!!
        r.messageRows = mapOf(
            "m1" to MessageEntity(
                id = "m1",
                sessionId = "s1",
                role = "assistant",
                partsJson = """[{"type":"text","value":"body stored (5000 bytes)"}]""",
                createdAt = 1L,
                sortOrder = 0,
                bodyBytes = 5000L,
                bodyRef = ref,
                bodySha = ref,
                preview = body.take(ResourceLimits.PREVIEW_BYTES),
            ),
        )
        val repository = repository(r, tmp.root)

        assertEquals(body, repository.loadRawPartsForRow("m1"))

        // 真身文件读不到时返回 null（调用方跳过该行），绝不退化成把 stub 写回去
        assertTrue(File(File(tmp.root, "bodies"), ref).delete())
        assertNull(repository.loadRawPartsForRow("m1"))
    }

    // ─── data-db#8：孤儿正文回收 ─────────────────────────────────────────

    @Test
    fun `collectOrphanBodies removes only unreferenced files and spares fresh ones`() = runBlocking {
        val r = Recorded()
        val root = File(tmp.root, "bodies").apply { mkdirs() }
        val live = oldFile(root, "a".repeat(64), "live")
        val orphan = oldFile(root, "b".repeat(64), "orphan")
        val fresh = File(root, "c".repeat(64)).apply { writeText("fresh") }
        val noise = oldFile(root, "not-a-sha", "noise")
        val temp = oldFile(root, "d".repeat(64) + ".1234.tmp", "tmp")
        r.allBodyRefs = listOf(live.name)

        val removed = repository(r, tmp.root).collectOrphanBodies()

        assertEquals(1, removed)
        assertTrue("仍被引用的正文必须留下", live.isFile)
        assertFalse("孤儿正文应被回收", orphan.isFile)
        assertTrue("宽限期内的新文件不动（指针可能还没落库）", fresh.isFile)
        assertTrue("非 sha 命名的文件不动", noise.isFile)
        assertTrue("临时文件不动（由 discardTemps 负责）", temp.isFile)
    }

    @Test
    fun `collectOrphanBodies keeps candidates still referenced by another session`() = runBlocking {
        val r = Recorded()
        val root = File(tmp.root, "bodies").apply { mkdirs() }
        val shared = oldFile(root, "e".repeat(64), "shared")
        val exclusive = oldFile(root, "f".repeat(64), "exclusive")
        r.allBodyRefs = listOf(shared.name)

        // candidates = 刚删掉的会话引用过的 ref；shared 还有别的行引用 → 不删
        val removed = repository(r, tmp.root).collectOrphanBodies(listOf(shared.name, exclusive.name))

        assertEquals(1, removed)
        assertTrue(shared.isFile)
        assertFalse(exclusive.isFile)
    }

    @Test
    fun `collectOrphanBodies deletes nothing when the reference set cannot be read`() = runBlocking {
        val root = File(tmp.root, "bodies").apply { mkdirs() }
        val orphan = oldFile(root, "a".repeat(64), "orphan")
        // allBodyRefs 抛错（库不可用）→ 整轮放弃，而不是"看起来没人引用"就删
        val dao = proxy(ChatDao::class.java) { name, _ ->
            when (name) {
                "allBodyRefs" -> error("database is gone")
                else -> error("unexpected ChatDao call: $name")
            }
        }

        val removed = ChatRepository(dao, goalDao(), tmp.root).collectOrphanBodies()

        assertEquals(0, removed)
        assertTrue(orphan.isFile)
    }

    @Test
    fun `deleteSession reclaims the bodies only that session referenced`() = runBlocking {
        val r = Recorded()
        val root = File(tmp.root, "bodies").apply { mkdirs() }
        val exclusive = oldFile(root, "e".repeat(64), "x")
        val shared = oldFile(root, "f".repeat(64), "y")
        r.sessionBodyRefs = listOf(exclusive.name, shared.name)
        r.allBodyRefs = listOf(shared.name)

        repository(r, tmp.root).deleteSession("s1")

        assertEquals(listOf("s1"), r.deletedSessions)
        assertFalse("只有该会话引用的正文应随会话回收", exclusive.isFile)
        assertTrue("别处仍在引用的正文必须留下", shared.isFile)
    }

    // ─── data-db#8b：去重命中的正文不能被当成孤儿回收（B7/B12 回归）────────

    @Test
    fun `a de-duplicated put refreshes the body mtime so the gc grace restarts`() {
        val root = File(tmp.root, "bodies").apply { mkdirs() }
        val store = BodyStore(root)
        val payload = "同一个正文被存两次".toByteArray(Charsets.UTF_8)
        val first = store.put(payload)
        assertTrue(first.ok)
        val file = File(root, first.ref!!)
        // 盘上这条正文已经超过回收宽限期：没有行引用它，看起来就是孤儿
        assertTrue(file.setLastModified(System.currentTimeMillis() - 11L * 60L * 1000L))

        val second = store.put(payload)

        assertEquals(first.ref, second.ref)
        assertTrue(
            "去重命中必须刷新 mtime：否则回收侧只看到「文件很旧 + 新行还没提交」",
            System.currentTimeMillis() - file.lastModified() < 60_000L,
        )
    }

    @Test
    fun `a de-duplicated body survives a reclaim round that read the live set too early`() = runBlocking {
        val r = Recorded()
        val root = File(tmp.root, "bodies").apply { mkdirs() }
        val body = """[{"type":"text","value":"${"q".repeat(4000)}"}]"""
        val put = BodyStore(root).put(body.toByteArray(Charsets.UTF_8))
        assertTrue(put.ok)
        val ref = put.ref!!
        val file = File(root, ref)
        assertTrue(
            "正文要先处于「早就过了宽限期」的状态",
            file.setLastModified(System.currentTimeMillis() - 11L * 60L * 1000L),
        )
        // 另一个 GC（删会话 / 清存储 / 存储页对账）读到的引用集合里还没有"正要提交"的新行
        r.allBodyRefs = emptyList()

        // 新一轮消息与盘上内容完全相同 → storeBody 去重命中，随后才把 body_ref 落库
        val inserted = repository(r, tmp.root).appendMessage("s1", "assistant", body)
        assertEquals(ref, inserted.bodyRef)

        assertEquals(0, repository(r, tmp.root).collectOrphanBodies())

        assertTrue("去重命中的正文被删 = 新行 body_ref 悬空、气泡永久退回 stub", file.isFile)
        val bytes = body.toByteArray(Charsets.UTF_8)
        assertEquals(body, BodyStore(root).read(ref, bytes.size)!!.toString(Charsets.UTF_8))
    }

    /**
     * [T-bodies-gc-pin-lock] 逐文件确认必须认「此刻的登记表」，而不是删除循环之前的快照：
     * 声明一条引用的时刻完全可能落在快照之后（fork 复制、备份途中的 `recordWritten`）。
     */
    @Test
    fun `a reference declared after the snapshot still blocks the unlink`() = runBlocking {
        val r = Recorded()
        val root = File(tmp.root, "bodies").apply { mkdirs() }
        val payload = """[{"type":"text","value":"${"m".repeat(4000)}"}]"""
        val put = BodyStore(root).put(payload.toByteArray(Charsets.UTF_8))
        assertTrue(put.error, put.ok)
        val ref = put.ref!!
        val file = File(root, ref)
        assertTrue(file.setLastModified(System.currentTimeMillis() - 11L * 60L * 1000L))
        r.allBodyRefs = emptyList()
        // 声明发生在回收开始之前，但晚于它「循环前快照」能看到的东西：快照里没有这条登记
        BodyRefLedger.retain(ref)

        assertEquals(0, repository(r, tmp.root).collectOrphanBodies())

        assertTrue("只有锁内逐文件确认才看得见这条声明", file.isFile)
    }

    /**
     * [T-bodies-gc-fork-retain] 登记只在宽限窗口内有效：新行落库之后回收侧从库里就读得到它，
     * 过期登记必须放行回收 —— 否则每条被 fork 复制过的正文都会变成永久 pin（孤儿只增不减）。
     */
    @Test
    fun `an expired declaration does not hold an orphan body forever`() = runBlocking {
        val r = Recorded()
        val root = File(tmp.root, "bodies").apply { mkdirs() }
        val payload = """[{"type":"text","value":"${"n".repeat(4000)}"}]"""
        val put = BodyStore(root).put(payload.toByteArray(Charsets.UTF_8))
        assertTrue(put.error, put.ok)
        val ref = put.ref!!
        val file = File(root, ref)
        assertTrue(file.setLastModified(System.currentTimeMillis() - 11L * 60L * 1000L))
        r.allBodyRefs = emptyList()
        // 11 分钟前的声明（登记窗口已过）：新行早该落库了，库里查不到引用 = 确实是孤儿
        BodyRefLedger.retain(ref, System.currentTimeMillis() - 11L * 60L * 1000L)

        assertEquals(1, repository(r, tmp.root).collectOrphanBodies())

        assertFalse("过期的登记不许把孤儿正文永久按住", file.isFile)
    }

    @Test
    fun `a body referenced only after the first live-set read is not unlinked`() = runBlocking {
        val r = Recorded()
        val root = File(tmp.root, "bodies").apply { mkdirs() }
        val late = oldFile(root, "5".repeat(64), "committed while the round was running")
        // 第一遍判定时还没有这条引用，unlink 前再读才出现（新行在回收中途提交）
        r.allBodyRefsSequence = listOf(emptyList(), listOf(late.name))

        assertEquals(0, repository(r, tmp.root).collectOrphanBodies())

        assertTrue("unlink 前读到引用就必须留下", late.isFile)
        assertEquals("首查 + 二次确认", 2, r.allBodyRefsReads)
    }

    @Test
    fun `a reclaim round with nothing to delete reads the live set only once`() = runBlocking {
        val r = Recorded()
        val root = File(tmp.root, "bodies").apply { mkdirs() }
        val live = oldFile(root, "6".repeat(64), "still referenced")
        r.allBodyRefs = listOf(live.name)

        assertEquals(0, repository(r, tmp.root).collectOrphanBodies())

        assertTrue(live.isFile)
        assertEquals("没有候选要删时不该多付一次引用集合读取", 1, r.allBodyRefsReads)
    }

    @Test
    fun `an unreadable mtime is treated as fresh so unknown age never deletes a body`() {
        val ref = "a".repeat(64)
        val now = 1_000_000L
        val old = now - 11L * 60L * 1000L
        assertFalse("mtime 读不出来（0）时不能删", shouldReclaimBody(ref, emptySet(), 0L, now))
        assertFalse("仍被引用不能删", shouldReclaimBody(ref, setOf(ref), old, now))
        assertFalse("宽限期内不能删", shouldReclaimBody(ref, emptySet(), now - 60_000L, now))
        assertFalse("非 sha 文件名不动", shouldReclaimBody("not-a-sha", emptySet(), old, now))
        assertTrue("无人引用 + 过了宽限期才回收", shouldReclaimBody(ref, emptySet(), old, now))
    }

    // ─── data-db#8c：批量删会话只跑一轮回收 ────────────────────────────────

    @Test
    fun `deleteSessions reclaims a whole batch with one reclaim round`() = runBlocking {
        val r = Recorded()
        val root = File(tmp.root, "bodies").apply { mkdirs() }
        val first = oldFile(root, "1".repeat(64), "first")
        val second = oldFile(root, "2".repeat(64), "second")
        r.sessionBodyRefsBySession = mapOf("s1" to listOf(first.name), "s2" to listOf(second.name))
        r.allBodyRefs = emptyList()

        repository(r, tmp.root).deleteSessions(listOf("s1", "s2"))

        assertEquals(listOf("s1", "s2"), r.deletedSessions)
        assertFalse("只被 s1 引用的正文应回收", first.isFile)
        assertFalse("只被 s2 引用的正文应回收", second.isFile)
        assertEquals("候选集按会话点查（有索引，便宜）", 2, r.sessionBodyRefsQueries)
        // 一轮回收 = 首查 + unlink 前二次确认；逐个 deleteSession 会是 2 会话 × 2 次全表读
        assertEquals("批量删除的全表引用读只跑一轮", 2, r.allBodyRefsReads)
    }

    @Test
    fun `deleteSessions with a single id keeps the single-session path`() = runBlocking {
        val r = Recorded()
        val root = File(tmp.root, "bodies").apply { mkdirs() }
        val only = oldFile(root, "3".repeat(64), "only")
        r.sessionBodyRefs = listOf(only.name)
        r.allBodyRefs = emptyList()

        repository(r, tmp.root).deleteSessions(listOf("s7"))

        assertEquals(listOf("s7"), r.deletedSessions)
        assertFalse(only.isFile)
        assertEquals(1, r.sessionBodyRefsQueries)
        assertEquals(2, r.allBodyRefsReads)
    }

    // ─── data-db#5：预算被占 ≠ 没有结果 ─────────────────────────────────

    @Test
    fun `searchMessages does not report an empty result when the budget is busy`() = runBlocking {
        val r = Recorded()
        r.searchRows = listOf(
            MessageSearchRow("s1", "m1", "user", 5L, """[{"type":"text","value":"hello world"}]"""),
        )
        Admission.resetForTests()
        assertTrue(Admission.tryAdmit(ResourceLimits.ADMIT_BUDGET_BYTES))
        try {
            val matches = repository(r, tmp.root)
                .searchMessages(null, listOf("hello"), limit = 3, startMs = null, endMs = null)

            assertEquals("预算被占时仍要跑这条有界查询", 1, matches.size)
            assertEquals(1, r.searchQueries)
        } finally {
            Admission.release(ResourceLimits.ADMIT_BUDGET_BYTES)
            Admission.resetForTests()
        }
    }

    // ─── data-db#8d：message_versions 也是引用来源（补零覆盖的那条分支）──────

    @Test
    fun `collectOrphanBodies keeps a body referenced only by an archived message version`() = runBlocking {
        val r = Recorded()
        val root = File(tmp.root, "bodies").apply { mkdirs() }
        val archivedOnly = oldFile(root, "7".repeat(64), "archived copy")
        val orphan = oldFile(root, "8".repeat(64), "nobody references me")
        // 活行都不再引用它，但某个归档版本还引用
        r.allBodyRefs = emptyList()
        r.versionBodyRefs = listOf(archivedOnly.name)

        val removed = repositoryWithVersions(r, tmp.root).collectOrphanBodies()

        assertEquals(1, removed)
        assertTrue("归档版本仍引用的正文必须留下", archivedOnly.isFile)
        assertFalse(orphan.isFile)
        assertTrue("版本表必须参与引用判定", r.versionAllBodyRefsReads > 0)
    }

    @Test
    fun `deleteSession reclaims a body only this session's archived versions referenced`() = runBlocking {
        val r = Recorded()
        val root = File(tmp.root, "bodies").apply { mkdirs() }
        val archived = oldFile(root, "9".repeat(64), "archived by this session")
        r.sessionBodyRefs = emptyList()
        r.versionSessionBodyRefs = listOf(archived.name)
        r.allBodyRefs = emptyList()
        r.versionBodyRefs = emptyList()

        repositoryWithVersions(r, tmp.root).deleteSession("s1")

        assertEquals(listOf("s1"), r.deletedSessions)
        assertEquals("候选集必须含归档版本的引用", 1, r.versionSessionBodyRefsQueries)
        assertFalse("只有该会话的归档版本引用过 → 随会话回收", archived.isFile)
    }

    @Test
    fun `deleteSessions unions live and archived refs across the batch`() = runBlocking {
        val r = Recorded()
        val root = File(tmp.root, "bodies").apply { mkdirs() }
        val live = oldFile(root, "1".repeat(64), "live row of s1")
        val archived = oldFile(root, "2".repeat(64), "archived row of s2")
        val survivor = oldFile(root, "3".repeat(64), "archived row of another session")
        r.sessionBodyRefsBySession = mapOf("s1" to listOf(live.name), "s2" to emptyList())
        r.versionSessionBodyRefsBySession = mapOf("s1" to emptyList(), "s2" to listOf(archived.name))
        r.allBodyRefs = emptyList()
        r.versionBodyRefs = listOf(survivor.name)

        repositoryWithVersions(r, tmp.root).deleteSessions(listOf("s1", "s2"))

        assertFalse("只被 s1 的活行引用 → 回收", live.isFile)
        assertFalse("只被 s2 的归档引用 → 回收", archived.isFile)
        assertTrue("另一个会话的归档仍引用 → 留下", survivor.isFile)
        assertEquals(2, r.sessionBodyRefsQueries)
        assertEquals(2, r.versionSessionBodyRefsQueries)
    }

    // ─── data-db#8e：回收的 File 层不在调用者线程上 ─────────────────────────

    @Test
    fun `collectOrphanBodies moves its file layer off the caller thread`() = runBlocking {
        val r = Recorded()
        val root = File(tmp.root, "bodies").apply { mkdirs() }
        val orphan = oldFile(root, "4".repeat(64), "orphan")
        r.allBodyRefs = emptyList()
        // 调用方（SessionListViewModel.deleteSession / deleteFolderWithSessions）在
        // Main.immediate 上 await 到这里：目录扫描与 unlink 不能跑在那个线程上。
        val caller = Thread.currentThread().name

        val removed = repository(r, tmp.root).collectOrphanBodies()

        assertEquals(1, removed)
        assertFalse(orphan.isFile)
        assertNotEquals("回收的 File 层必须离开调用者线程", caller, r.liveRefsThread)
    }

    // ─── data-db#8f：备份快照按住正文 + put 与回收的 TOCTOU ─────────────────

    @Test
    fun `a body held by a backup snapshot survives a reclaim round and is reclaimed after release`() =
        runBlocking {
            BodyGcPins.clearForTests()
            val r = Recorded()
            val root = File(tmp.root, "bodies").apply { mkdirs() }
            val held = oldFile(root, "b".repeat(64), "being packaged by a backup")
            r.allBodyRefs = listOf(held.name)
            val snapshot = BackupBodySnapshot.take(chatDao(r), versionDao(r))
            // 导出中途用户删掉了引用它的会话：库里已经没有任何引用
            r.allBodyRefs = emptyList()
            try {
                assertEquals(0, repository(r, tmp.root).collectOrphanBodies())
                assertTrue("备份手上的快照必须挡住回收", held.isFile)
            } finally {
                snapshot.release()
            }

            assertEquals(1, repository(r, tmp.root).collectOrphanBodies())
            assertFalse("解除 pin 后照常回收（不能永久泄漏）", held.isFile)
            BodyGcPins.clearForTests()
        }

    /**
     * [T-bodies-gc-toctou] `put` 重新引用一个"盘上很旧"的正文，与同一时刻的回收赛跑：
     * 无论谁先拿到锁，结果都必须是「正文还在盘上 + mtime 是新的」。只靠 mtime 判定而不锁
     * 的话，回收侧能在看完 mtime 之后、unlink 之前被 put 刷新引用 —— 删掉的就是活引用。
     */
    @Test
    fun `a body re-referenced while a reclaim round runs never loses the file`() = runBlocking {
        val r = Recorded()
        val root = File(tmp.root, "bodies").apply { mkdirs() }
        val payload = "同一段正文被重新引用".toByteArray(Charsets.UTF_8)
        val put = BodyStore(root).put(payload)
        assertTrue(put.error, put.ok)
        val ref = put.ref!!
        val file = File(root, ref)
        // 另一轮 GC 读得太早：库里暂时看不到这条引用（指针还没落库）
        r.allBodyRefs = emptyList()

        repeat(40) {
            assertTrue(file.setLastModified(System.currentTimeMillis() - 11L * 60L * 1000L))
            val start = CountDownLatch(1)
            val reclaim = async(Dispatchers.IO) {
                start.await()
                repository(r, tmp.root).collectOrphanBodies(listOf(ref))
            }
            val writer = async(Dispatchers.IO) {
                start.await()
                BodyStore(root).put(payload)
            }
            start.countDown()
            reclaim.await()
            writer.await()

            assertTrue("被重新引用的正文不能消失", file.isFile)
            assertEquals(
                "正文必须仍然可读（内容寻址的摘要对得上）",
                payload.toList(),
                BodyStore(root).read(ref, payload.size)!!.toList(),
            )
            assertTrue(
                "引用刷新必须让回收侧看得见（mtime 新）",
                System.currentTimeMillis() - file.lastModified() < 60_000L,
            )
        }
    }

    // ─── 预览解析的严格版 ───────────────────────────────────────────────

    @Test
    fun `extractTextPreviewStrict rejects a truncated head instead of echoing raw json`() {
        val truncated = """[{"type":"text","value":"${"z".repeat(9000)}"""

        assertNull(ChatRepository.extractTextPreviewStrict(truncated))
        assertEquals(
            "the real answer",
            ChatRepository.extractTextPreviewStrict("""[{"type":"text","value":"the real answer"}]"""),
        )
        // 非严格版保持原策略（解析失败时退化为清理后的原始文本），此处只钉住差异
        assertNotNull(ChatRepository.extractTextPreview(truncated))
    }
}
