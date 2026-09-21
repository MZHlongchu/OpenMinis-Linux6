package com.openminis.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentActivityTrackerTest {
    @Test
    fun startAndFinishTrackMembers() {
        SubAgentActivityTracker.clearSession("s1")
        val id = SubAgentActivityTracker.start("s1", "Research", "worker", "gpt")
        assertEquals(1, SubAgentActivityTracker.membersFor("s1").size)
        assertEquals(SubAgentActivityTracker.Status.RUNNING, SubAgentActivityTracker.membersFor("s1").first().status)
        SubAgentActivityTracker.finish(id, true)
        assertEquals(SubAgentActivityTracker.Status.SUCCESS, SubAgentActivityTracker.membersFor("s1").first().status)
        SubAgentActivityTracker.clearSession("s1")
        assertTrue(SubAgentActivityTracker.membersFor("s1").isEmpty())
    }

    @Test
    fun updateStepShowsOnRunningMember() {
        SubAgentActivityTracker.clearSession("s2")
        val id = SubAgentActivityTracker.start("s2", "Research", "worker", "gpt")
        SubAgentActivityTracker.updateStep(id, "▶ shell_execute ls -la")
        assertEquals("▶ shell_execute ls -la", SubAgentActivityTracker.membersFor("s2").first().lastStep)
        SubAgentActivityTracker.clearSession("s2")
    }

    @Test
    fun updateProgressTracksTurnAndTool() {
        SubAgentActivityTracker.clearSession("s3")
        val id = SubAgentActivityTracker.start(
            parentSessionId = "s3",
            title = "slice",
            role = "coder",
            model = "gpt",
            index = 2,
            total = 3,
            kind = "worker",
            turnCap = 40,
        )
        SubAgentActivityTracker.updateProgress(id, 4, 40, "file_read")
        val m = SubAgentActivityTracker.membersFor("s3").first()
        assertEquals(2, m.index)
        assertEquals(3, m.total)
        assertEquals("worker", m.kind)
        assertEquals(4, m.turnIndex)
        assertEquals(40, m.turnCap)
        assertEquals("file_read", m.currentTool)
        assertEquals("turn 4/40 · file_read", m.lastStep)
        SubAgentActivityTracker.finish(id, false, "boom")
        assertEquals("boom", SubAgentActivityTracker.membersFor("s3").first().error)
        SubAgentActivityTracker.clearSession("s3")
    }

    @Test
    fun appendLogAndCombinedTranscriptCoverBatchAndSingle() {
        SubAgentActivityTracker.clearSession("s4")
        val a = SubAgentActivityTracker.start(
            parentSessionId = "s4",
            title = "A",
            role = "worker",
            model = "gpt",
            index = 1,
            total = 2,
            kind = "explore",
        )
        val b = SubAgentActivityTracker.start(
            parentSessionId = "s4",
            title = "B",
            role = "worker",
            model = "gpt",
            index = 2,
            total = 2,
            kind = "worker",
        )
        SubAgentActivityTracker.appendLog(a, "turn 1/40 · file_read · src/Foo.kt")
        SubAgentActivityTracker.appendLog(b, "turn 1/40 · grep · TODO")
        val combined = SubAgentActivityTracker.combinedTranscript("s4")
        assertTrue(combined.contains("子代理 1/2"))
        assertTrue(combined.contains("file_read"))
        assertTrue(combined.contains("子代理 2/2"))
        assertTrue(combined.contains("grep"))
        assertEquals("turn 1/40 · file_read · src/Foo.kt", SubAgentActivityTracker.membersFor("s4").first { it.id == a }.transcript)
        SubAgentActivityTracker.clearSession("s4")
        SubAgentActivityTracker.clearSession("s5")
        val one = SubAgentActivityTracker.start("s5", "solo", "worker", "gpt")
        SubAgentActivityTracker.appendLog(one, "turn 2/10 · done")
        val solo = SubAgentActivityTracker.combinedTranscript("s5")
        assertEquals("turn 2/10 · done", solo)
        assertFalse(solo.contains("## "))
        SubAgentActivityTracker.clearSession("s5")
    }
}
