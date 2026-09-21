package com.openminis.app.sandbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
            parentFile.mkdirs()
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
}
