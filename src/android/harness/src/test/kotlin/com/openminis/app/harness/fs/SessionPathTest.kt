package com.openminis.app.harness.fs

import com.openminis.app.harness.checkpoint.Checkpoint
import com.openminis.app.harness.checkpoint.FileCheckpointPersistence
import com.openminis.app.harness.checkpoint.FileSnap
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [T-checkpoint-safe-session-dir] sessionId 是三个文件持久化的路径段来源，
 * 这里锁两件事：转义单射（不同 id 不撞同一份文件）、id 永远逃不出 root
 * （delete 是 deleteRecursively，越界就是删别人）。
 */
class SessionPathTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `segment is injective for the ids that used to collide`() {
        // 原始 id "_2f" 与 "/" 的转义以前都是 "_2f"：两个会话撞同一份数据。
        assertNotEquals(SessionPath.segment("_2f"), SessionPath.segment("/"))
        assertEquals("__2f", SessionPath.segment("_2f"))
        assertEquals("_2f", SessionPath.segment("/"))
        // lane 会话 id 与 subagent 会话 id 含冒号/斜杠，结果不得再含分隔符。
        for (id in listOf("lane:owner/name", "subagent:s:1", "..", ".", "a\\b", "a\u0000b", "会话/一")) {
            val safe = SessionPath.segment(id)
            assertFalse("$id -> $safe", safe.contains('/'))
            assertFalse("$id -> $safe", safe.contains('\\'))
            assertFalse("$id -> $safe", safe.contains('\u0000'))
        }
        // UUID 会话 id 转义后原样——磁盘布局对今天的 id 不变。
        val uuid = "8f14e45f-ceea-467a-9e4a-2b1c0d3e4f50"
        assertEquals(uuid, SessionPath.segment(uuid))
    }

    @Test
    fun `resolve refuses ids that would land on the root itself or outside it`() {
        val root = File(temporaryFolder.newFolder("root"), "checkpoints")
        for (bad in listOf("", ".", "..")) {
            assertNull("'$bad' must be refused", SessionPath.resolve(root, bad))
        }
        val ok = SessionPath.resolve(root, "s-1")
        assertEquals(File(root, "s-1"), ok)
        assertTrue(SessionPath.staysInside(root, ok!!))
        // root 自身与 root 之外都不算"在 root 之内"。
        assertFalse(SessionPath.staysInside(root, root))
        assertFalse(SessionPath.staysInside(root, File(root, "..")))
        assertFalse(SessionPath.staysInside(root, File(root.parentFile, "sibling")))
    }

    @Test
    fun `checkpoint delete never leaves the root`() {
        val root = temporaryFolder.newFolder("ckpt")
        val persistence = FileCheckpointPersistence(root)
        val canary = File(root.parentFile, "canary.txt").apply { writeText("keep") }

        persistence.write("lane:owner/name", checkpoint(turn = 0))
        // 路径段被转义：目录真的建在 root 里面，且能被同一个 id 读回。
        val dirs = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        assertEquals(1, dirs.size)
        assertTrue(SessionPath.staysInside(root, dirs.single()))
        assertEquals(1, persistence.readAll("lane:owner/name").size)
        // 另一个 id 不会读到它（单射：不串味）。
        assertTrue(persistence.readAll("lane:owner_name").isEmpty())

        // 越界 id：delete 什么都不删（root 与 root 外的金丝雀都在）。
        persistence.delete("..")
        persistence.delete(".")
        persistence.delete("")
        assertTrue(root.isDirectory)
        assertTrue(canary.isFile)

        persistence.delete("lane:owner/name")
        assertTrue(persistence.readAll("lane:owner/name").isEmpty())
        assertTrue(canary.isFile)
    }

    private fun checkpoint(turn: Int) = Checkpoint(
        turn = turn,
        time = 1_000L,
        prompt = "p",
        files = listOf(FileSnap("a.txt", "old-a")),
        anchorMessageId = "anchor-$turn",
    )
}
