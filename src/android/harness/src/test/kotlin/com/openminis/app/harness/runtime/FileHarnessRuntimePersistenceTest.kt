package com.openminis.app.harness.runtime

import com.openminis.app.harness.model.HarnessEntryEntity
import com.openminis.app.harness.model.HarnessLaneEntity
import com.openminis.app.harness.model.HarnessLaneResultEntity
import com.openminis.app.harness.model.HarnessOperationEntity
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-operation-wiring] 磁盘运行时仓储验收：分支链、lane 指针、finish 清账、
 * 坏文件降级、单射转义（lane 会话 id 含冒号/斜杠不撞文件名）。
 */
class FileHarnessRuntimePersistenceTest {

    private val roots = mutableListOf<File>()

    @org.junit.After
    fun tearDown() {
        roots.forEach { it.deleteRecursively() }
    }

    private fun persistence(): FileHarnessRuntimePersistence {
        val root = Files.createTempDirectory("harness-runtime").toFile()
        roots += root
        return FileHarnessRuntimePersistence(root)
    }

    private fun entry(id: String, parentId: String?, sessionId: String = "s1") =
        HarnessEntryEntity(sequence = 0, id = id, sessionId = sessionId, parentId = parentId, createdAt = 1, entryType = "message", payloadJson = "{}")

    private fun operation(id: String, sessionId: String = "s1", laneName: String = "main") =
        HarnessOperationEntity(id = id, sessionId = sessionId, laneName = laneName, kind = "run", status = "running", phase = "provider_intent", startedAt = 1, updatedAt = 1, startLeafId = null, stateJson = "{}")

    @Test
    fun `branch walks the parent chain in order`() = runBlocking {
        val p = persistence()
        val lane = p.ensureLane("s1", "main")
        p.appendToLane("s1", "main", entry("e1", null))
        p.appendToLane("s1", "main", entry("e2", "e1"))
        p.appendToLane("s1", "main", entry("e3", "e2"))
        val chain = p.branch("s1", "e3")
        assertEquals(listOf("e1", "e2", "e3"), chain.map { it.id })
        assertEquals(2, p.branchTail("s1", "e3", 2).size)
        assertEquals("e3", p.findLane("s1", "main")!!.leafId)
    }

    @Test
    fun `moveLane rewinds the pointer without deleting entries`() = runBlocking {
        val p = persistence()
        p.ensureLane("s1", "main")
        p.appendToLane("s1", "main", entry("e1", null))
        p.appendToLane("s1", "main", entry("e2", "e1"))
        p.moveLane("s1", "main", "e1")
        assertEquals("e1", p.findLane("s1", "main")!!.leafId)
        assertEquals(2, p.branch("s1", "e2").size) // 被放弃的分支保留
    }

    @Test
    fun `finish removes the operation and records the result`() = runBlocking {
        val p = persistence()
        val lane = p.ensureLane("s1", "main")
        p.beginOperation(lane.copy(currentOperationId = "op1"), operation("op1"))
        assertEquals(1, p.listActiveOperations("s1").size)
        p.finishOperation(
            HarnessLaneResultEntity("s1", "main", "op1", "completed", null, null, 2),
            p.findLane("s1", "main")!!.copy(currentOperationId = null, updatedAt = 2),
        )
        assertTrue(p.listActiveOperations("s1").isEmpty())
        assertNull(p.findOperation("op1"))
    }

    @Test
    fun `settle appends entry usage and updates operation atomically`() = runBlocking {
        val p = persistence()
        val lane = p.ensureLane("s1", "main")
        p.beginOperation(lane.copy(currentOperationId = "op1"), operation("op1"))
        val e = entry("e1", null)
        p.settleEffect(e, null, operation("op1").copy(phase = "tool_settled"), p.findLane("s1", "main")!!.copy(leafId = "e1"))
        assertEquals("e1", p.findLane("s1", "main")!!.leafId)
        assertEquals("tool_settled", p.findOperation("op1")!!.phase)
        assertEquals(1, p.branch("s1", "e1").size)
    }

    @Test
    fun `unreadable file degrades to empty state`() = runBlocking {
        val root = Files.createTempDirectory("harness-runtime").toFile()
        roots += root
        val p = FileHarnessRuntimePersistence(root)
        p.ensureLane("s1", "main")
        // 写坏主文件
        File(root, firstFileName(root)).writeText("{broken")
        val p2 = FileHarnessRuntimePersistence(root)
        assertNull(p2.findLane("s1", "main"))
    }

    @Test
    fun `session ids with colons and slashes map to distinct files`() = runBlocking {
        val p = persistence()
        val a = p.ensureLane("lane:owner:name", "main")
        val b = p.ensureLane("lane/owner/name", "main")
        val c = p.ensureLane("lane_owner_name", "main")
        p.appendToLane("lane:owner:name", "main", entry("e1", null, "lane:owner:name"))
        // 互不污染：b/c 的 lane 上没有 e1
        assertNull(p.findLane("lane/owner/name", "main")!!.leafId)
        assertNull(p.findLane("lane_owner_name", "main")!!.leafId)
        assertEquals("e1", p.findLane("lane:owner:name", "main")!!.leafId)
    }

    private fun firstFileName(root: File): String {
        val names = root.listFiles { f -> f.name.endsWith(".json") }!!.map { it.name }
        return names.first()
    }

    // ─── [T-b18-op-lookup-session-domain] 会话域查询 + 每会话锁 ──────────────

    @Test
    fun `session-scoped operation lookup stays inside that session file`() = runBlocking {
        val p = persistence()
        val lane = p.ensureLane("s1", "main")
        p.beginOperation(lane.copy(currentOperationId = "op1"), operation("op1", sessionId = "s1"))
        val other = p.ensureLane("s2", "main")
        p.beginOperation(other.copy(currentOperationId = "op2"), operation("op2", sessionId = "s2"))

        // 会话域：只认自己那本账
        assertEquals("op1", p.findOperation("s1", "op1")!!.id)
        assertNull(p.findOperation("s2", "op1"))
        assertNull(p.findOperation("s1", "op2"))
        // 跨会话扫描仍然能找到（恢复期只有 operationId 的路径靠它）
        assertEquals("op1", p.findOperation("op1")!!.id)
        assertEquals("op2", p.findOperation("op2")!!.id)
    }

    @Test
    fun `concurrent writers on one session do not lose updates`() = runBlocking {
        val p = persistence()
        p.ensureLane("s1", "main")
        val writers = (1..32).map { i ->
            launch(Dispatchers.Default) { p.appendToLane("s1", "main", entry("e$i", null)) }
        }
        writers.forEach { it.join() }

        for (i in 1..32) {
            assertEquals("entry e$i must survive the concurrent rewrites", "e$i", p.findEntry("s1", "e$i")?.id)
        }
    }

    @Test
    fun `concurrent writers across sessions keep both files intact`() = runBlocking {
        val p = persistence()
        p.ensureLane("s1", "main")
        p.ensureLane("s2", "main")
        val writers = buildList {
            (1..16).forEach { i ->
                add(launch(Dispatchers.Default) { p.appendToLane("s1", "main", entry("a$i", null, "s1")) })
                add(launch(Dispatchers.Default) { p.appendToLane("s2", "main", entry("b$i", null, "s2")) })
            }
        }
        writers.forEach { it.join() }

        for (i in 1..16) {
            assertEquals("a$i", p.findEntry("s1", "a$i")?.id)
            assertEquals("b$i", p.findEntry("s2", "b$i")?.id)
        }
        assertNull(p.findEntry("s1", "b1"))
    }
}
