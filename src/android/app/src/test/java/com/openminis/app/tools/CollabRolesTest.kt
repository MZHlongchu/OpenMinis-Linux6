package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollabRolesTest {

    @Test
    fun catalogLookupIsCaseInsensitive() {
        val role = CollabRoles.byName("产品经理")
        assertNotNull(role)
        assertEquals(role, CollabRoles.byName("产品经理"))
        assertNull(CollabRoles.byName("Android reviewer"))
        assertNull(CollabRoles.byName(""))
    }

    @Test
    fun productManagerCardHasRoutingAndDontDo() {
        val prompt = CollabRoles.promptFor("产品经理")!!
        assertTrue(prompt.contains("你盯着的东西"))
        assertTrue(prompt.contains("你不管的东西"))
        assertTrue(prompt.contains("什么时候不说话"))
        assertTrue(prompt.contains("@架构师"))
        assertFalse(prompt.contains("invoke_skill"))
    }

    @Test
    fun architectGetsShellButSecretaryDoesNot() {
        assertTrue("shell_execute" in CollabRoles.toolsFor("架构师")!!)
        assertFalse("shell_execute" in CollabRoles.toolsFor("秘书助理")!!)
        assertTrue("file_write" in CollabRoles.toolsFor("秘书助理")!!)
    }

    @Test
    fun filterToolsIntersectsRoleWhitelist() {
        val tools = AgentTools.makeAgentTools(
            supportsImageInput = false,
            visionGroupConfigured = false,
            memoryEnabled = true,
            subAgentEnabled = true,
        )
        val filtered = SubAgentKind.filterTools(SubAgentKind.WORKER, tools, "产品经理")
        val names = filtered.map { it.name }.toSet()
        assertTrue("file_read" in names)
        assertTrue("file_write" in names)
        assertFalse("shell_execute" in names)
        assertFalse("cronjob" in names)
        assertFalse("spawn_agent" in names)
    }
}
