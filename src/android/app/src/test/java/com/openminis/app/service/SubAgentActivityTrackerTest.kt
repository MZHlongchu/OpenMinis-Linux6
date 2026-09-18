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
}
