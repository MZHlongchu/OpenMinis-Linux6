package com.openminis.app.harness.checkpoint

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Adapted from taixu CheckpointPersistenceTest (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 */
class CheckpointPersistenceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun storeWithDisk(): Pair<CheckpointStore, File> {
        val root = temporaryFolder.newFolder("ckpt")
        val store = CheckpointStore()
        store.persistence = FileCheckpointPersistence(root)
        // 落盘改异步后，测试用同步直执行保证确定性（写入即刻可见）
        store.diskWriteExecutor = java.util.concurrent.Executor { it.run() }
        return store to root
    }

    @Test
    fun `closed turns survive process restart via disk persistence`() {
        val (store, root) = storeWithDisk()
        store.beginTurn("s", "第一轮", "anchor-1")
        store.capture("s", "a.txt", "old-a")
        store.capture("s", "b.txt", null)
        store.beginTurn("s", "第二轮", "anchor-2") // 关闭 turn0 并落盘

        // 模拟进程重启：全新 store 挂同一磁盘目录
        val revived = CheckpointStore()
        revived.persistence = FileCheckpointPersistence(root)

        val metas = revived.checkpoints("s")
        assertEquals(1, metas.size)
        assertEquals("anchor-1", metas.single().anchorMessageId)
        assertEquals(listOf("a.txt", "b.txt"), metas.single().changedFiles)

        // 恢复后的 rewind 计划与内存时代一致：含 null 快照（该轮创建的文件 → 回滚即删除）
        val plan = revived.planCodeRewind("s", 0)
        assertEquals(2, plan.size)
        assertEquals("old-a", plan.first { it.path == "a.txt" }.content)
        assertNull(plan.first { it.path == "b.txt" }.content)
    }

    @Test
    fun `absent snapshots are distinguished from empty content files on restore`() {
        val (store, root) = storeWithDisk()
        store.beginTurn("s", "t", null)
        store.capture("s", "created.txt", null) // 该轮新建
        store.capture("s", "empty.txt", "") // 已存在但内容为空
        store.beginTurn("s", "t2") // 关闭 turn0

        val revived = CheckpointStore()
        revived.persistence = FileCheckpointPersistence(root)
        val plan = revived.planCodeRewind("s", 0)

        assertNull(plan.first { it.path == "created.txt" }.content)
        assertEquals("", plan.first { it.path == "empty.txt" }.content)
    }

    @Test
    fun `corrupted content files are dropped on restore instead of masquerading as absent`() {
        val (store, root) = storeWithDisk()
        store.beginTurn("s", "t", null)
        store.capture("s", "a.txt", "v-a")
        store.capture("s", "created.txt", null) // 该轮新建（真不存在）
        store.capture("s", "b.txt", "v-b") // seq 2 → 2.snap
        store.beginTurn("s", "t2") // 关闭并落盘

        // 模拟内容文件独立丢失（部分清理失败/文件系统损坏），索引文件仍完整：
        // 修复前 readAll 会把缺失内容映射为 null——"文件当时不存在"的语义，
        // rewind 据此删除现存文件（数据丢失）；正确行为是整体跳过该快照。
        assertTrue(File(root, "s/0/2.snap").delete())

        val revived = CheckpointStore()
        revived.persistence = FileCheckpointPersistence(root)
        val plan = revived.planCodeRewind("s", 0)

        assertEquals(listOf("a.txt", "created.txt"), plan.map { it.path })
        assertEquals("v-a", plan.first { it.path == "a.txt" }.content)
        assertNull("显式 absent 的语义保持不变", plan.first { it.path == "created.txt" }.content)
        assertFalse("损坏快照不得以 content=null 形态混入回滚方案", plan.any { it.path == "b.txt" })
    }

    @Test
    fun `dropSession removes both memory state and disk directory`() {
        val (store, root) = storeWithDisk()
        store.beginTurn("s", "t", null)
        store.capture("s", "a.txt", "x")
        store.beginTurn("s", "t2") // 关闭并落盘
        assertTrue(File(root, "s").isDirectory)

        store.dropSession("s")

        assertTrue(store.checkpoints("s").isEmpty())
        assertTrue(File(root, "s").isDirectory.not() || File(root, "s").listFiles().isEmpty())
        // 重启后也无残留
        val revived = CheckpointStore()
        revived.persistence = FileCheckpointPersistence(root)
        assertTrue(revived.checkpoints("s").isEmpty())
    }

    @Test
    fun `disk turns older than retention window are pruned on write`() {
        val (store, root) = storeWithDisk()
        repeat(CheckpointStore.MAX_KEPT + 5) { index ->
            store.beginTurn("s", "turn-$index", null)
            store.capture("s", "f-$index.txt", "v-$index")
        }
        store.beginTurn("s", "final") // 关闭最后一轮触发落盘+清理

        val indexFiles = File(root, "s").listFiles { f -> f.name.endsWith(".index.json") }!!
        assertEquals(CheckpointStore.MAX_KEPT, indexFiles.size)
        // 最老的超龄轮目录已删
        assertTrue(!File(root, "s/0").exists())
        assertTrue(indexFiles.all { it.name.removeSuffix(".index.json").toInt() >= 5 })
    }

    @Test
    fun `restored turns merge with newer in-memory turns without duplicates`() {
        val (store, root) = storeWithDisk()
        // 首轮落盘
        store.beginTurn("s", "第一轮", "a1")
        store.capture("s", "a.txt", "v1")
        store.beginTurn("s", "第二轮", "a2") // 关闭 turn0

        // 同一进程里继续（此时触发磁盘恢复 + 已有内存态）
        store.beginTurn("s", "第三轮", "a3")
        store.capture("s", "b.txt", "v2")
        store.beginTurn("s", "第四轮") // 关闭 turn（新轮）

        val metas = store.checkpoints("s")
        // turn0（磁盘恢复）+ 新内存轮，去重无重复
        assertEquals(metas.map { it.turn }.distinct().size, metas.size)
        assertTrue(metas.any { it.prompt == "第一轮" && it.changedFiles == listOf("a.txt") })
        assertTrue(metas.any { it.changedFiles == listOf("b.txt") })
        // 顺序保持
        assertEquals(metas.sortedBy { it.turn }, metas)
        assertNotNull(metas.firstOrNull { it.prompt == "第一轮" }?.anchorMessageId)
    }

    @Test
    fun `after-image persists and survives restart`() {
        val (store, root) = storeWithDisk()
        store.beginTurn("s", "第一轮")
        store.capture("s", "a.txt", "old")
        store.captureAfterImage("s", "a.txt", "new")
        store.capture("s", "b.txt", null)
        store.beginTurn("s", "第二轮") // 关闭 turn0 落盘

        val revived = CheckpointStore()
        revived.persistence = FileCheckpointPersistence(root)
        assertEquals("new", revived.latestAfterImage("s", "a.txt"))
        // 无凭据的路径返回 null（b.txt 只有 pre-image）
        assertNull(revived.latestAfterImage("s", "b.txt"))
    }

    /** 落盘一轮 turn0（同步直执行，写入即刻可见）并返回磁盘目录。 */
    private fun seedTurnOnDisk(name: String): File {
        val root = temporaryFolder.newFolder(name)
        val writer = CheckpointStore()
        writer.persistence = FileCheckpointPersistence(root)
        writer.diskWriteExecutor = java.util.concurrent.Executor { it.run() }
        writer.beginTurn("s", "第一轮", "a1")
        writer.capture("s", "a.txt", "v1")
        writer.beginTurn("s", "第二轮") // 关闭 turn0 落盘
        return root
    }

    /**
     * [T-p2-checkpointstore-restore-gate] 旧实现先登记"已恢复"再读盘，并发调用者
     * 直接跳过恢复、拿到空 SessionState（rewind 静默"无文件可回滚"，轮号从 0 重来）。
     * 本用例把第一个调用者钉在读盘中间，断言第二个调用者必须等它完成并看到同一份
     * 恢复结果，而不是立刻返回空表。
     */
    @Test
    fun `concurrent callers wait for an in-flight restore instead of seeing empty state`() {
        val root = seedTurnOnDisk("ckpt-race")
        val real = FileCheckpointPersistence(root)
        val entered = java.util.concurrent.CountDownLatch(1)
        val gate = java.util.concurrent.CountDownLatch(1)
        val reads = java.util.concurrent.atomic.AtomicInteger(0)
        val reader = CheckpointStore()
        reader.persistence = object : CheckpointStore.Persistence {
            override fun write(sessionId: String, checkpoint: Checkpoint) = real.write(sessionId, checkpoint)
            override fun readAll(sessionId: String): List<Checkpoint> {
                reads.incrementAndGet()
                entered.countDown()
                gate.await()
                return real.readAll(sessionId)
            }
            override fun delete(sessionId: String) = real.delete(sessionId)
        }

        // daemon：断言失败提前退出时，被 gate 卡住的线程不得把测试 JVM 一起吊死。
        val first = Thread { reader.checkpoints("s") }.apply { isDaemon = true; start() }
        assertTrue("第一个调用者没有进入读盘", entered.await(5, java.util.concurrent.TimeUnit.SECONDS))

        var secondSeen: List<CheckpointMeta>? = null
        val second = Thread { secondSeen = reader.checkpoints("s") }.apply { isDaemon = true; start() }
        second.join(300)
        assertTrue(
            "恢复尚未完成时第二个调用者不得提前返回（旧实现读到的就是空表）",
            second.isAlive,
        )

        gate.countDown()
        first.join(5_000)
        second.join(5_000)
        assertEquals("两个调用者必须复用同一次读盘", 1, reads.get())
        assertEquals(listOf(0), secondSeen?.map { it.turn })
        assertFalse("第一个调用者也不得拿到空表", first.isAlive)
    }

    /**
     * [T-p2-checkpointstore-restore-gate] 恢复失败不登记"已恢复"：下一个调用者必须
     * 重试并把磁盘态读回来（旧实现靠失败时移除标记保证同样语义，新实现不能丢）。
     */
    @Test
    fun `failed restore is not registered and the next access retries`() {
        val root = seedTurnOnDisk("ckpt-retry")
        val real = FileCheckpointPersistence(root)
        val attempts = java.util.concurrent.atomic.AtomicInteger(0)
        val reader = CheckpointStore()
        reader.persistence = object : CheckpointStore.Persistence {
            override fun write(sessionId: String, checkpoint: Checkpoint) = real.write(sessionId, checkpoint)
            override fun readAll(sessionId: String): List<Checkpoint> {
                if (attempts.incrementAndGet() == 1) throw java.io.IOException("disk hiccup")
                return real.readAll(sessionId)
            }
            override fun delete(sessionId: String) = real.delete(sessionId)
        }

        assertTrue("恢复失败时按原语义返回空态", reader.checkpoints("s").isEmpty())
        assertEquals("第二次访问必须重试恢复", 1, reader.checkpoints("s").size)
        assertEquals(2, attempts.get())
    }

    /**
     * [T-p2-checkpoint-restore-warmup] 预热后（会话装载期的后台调用）后续访问不再
     * 触发任何读盘——冷启动后首轮 beginTurn 不付恢复成本。
     */
    @Test
    fun `prefetch restores eagerly so later access reads no disk`() {
        val root = seedTurnOnDisk("ckpt-warmup")
        val real = FileCheckpointPersistence(root)
        val reads = java.util.concurrent.atomic.AtomicInteger(0)
        val reader = CheckpointStore()
        reader.persistence = object : CheckpointStore.Persistence {
            override fun write(sessionId: String, checkpoint: Checkpoint) = real.write(sessionId, checkpoint)
            override fun readAll(sessionId: String): List<Checkpoint> {
                reads.incrementAndGet()
                return real.readAll(sessionId)
            }
            override fun delete(sessionId: String) = real.delete(sessionId)
        }

        reader.prefetch("s")
        assertEquals(1, reads.get())
        assertEquals(1, reader.checkpoints("s").size)
        assertEquals("预热后 beginTurn 不得再读盘", 1, reads.get())
        reader.beginTurn("s", "第三轮")
        assertEquals(1, reads.get())
    }
}
