package com.openminis.app.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxNotifyActionsTest {

    @Test
    fun `known actions produce sandbox commands`() {
        for (key in listOf(
            SandboxNotifyActions.RETRY,
            SandboxNotifyActions.BACKUP,
            SandboxNotifyActions.CLEANUP,
        )) {
            val cmd = SandboxNotifyActions.commandFor(key)
            assertNotNull(cmd)
            assertTrue(cmd!!.contains("minis-notify-action"))
        }
    }

    @Test
    fun `unknown action is rejected`() {
        assertNull(SandboxNotifyActions.commandFor("rm -rf /"))
    }

    @Test
    fun `notify actions are not recorded as last command`() {
        val retry = SandboxNotifyActions.commandFor(SandboxNotifyActions.RETRY)!!
        assertFalse(SandboxNotifyActions.shouldRecordLastCommand(retry))
        assertTrue(SandboxNotifyActions.shouldRecordLastCommand("echo hi"))
        assertFalse(SandboxNotifyActions.shouldRecordLastCommand("sh /var/minis/workspace/.minis-last-cmd.sh"))
    }

    @Test
    fun `backup uses shell date substitution`() {
        val cmd = SandboxNotifyActions.commandFor(SandboxNotifyActions.BACKUP)!!
        assertTrue(cmd.contains("$(date"))
        assertFalse(cmd.contains("\\$(date"))
    }
}
