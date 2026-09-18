package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentKindTest {

    @Test
    fun normalizeAliases() {
        assertEquals(SubAgentKind.EXPLORE, SubAgentKind.normalize("read-only"))
        assertEquals(SubAgentKind.PLAN, SubAgentKind.normalize("planner"))
        assertEquals(SubAgentKind.WORKER, SubAgentKind.normalize(null))
    }

    @Test
    fun exploreDropsWriteTools() {
        val tools = listOf(
            AgentToolDefinition("file_read", "r", mapOf("x" to AgentToolParam("string", "x")), listOf("x")),
            AgentToolDefinition("file_write", "w", mapOf("x" to AgentToolParam("string", "x")), listOf("x")),
            AgentToolDefinition("shell_execute", "s", mapOf("x" to AgentToolParam("string", "x")), listOf("x")),
            AgentToolDefinition("web_search", "q", mapOf("x" to AgentToolParam("string", "x")), listOf("x")),
        )
        val filtered = SubAgentKind.filterTools(SubAgentKind.EXPLORE, tools).map { it.name }
        assertEquals(listOf("file_read", "web_search"), filtered)
        assertTrue(SubAgentKind.blocks(SubAgentKind.PLAN, "file_edit"))
        assertFalse(SubAgentKind.blocks(SubAgentKind.WORKER, "file_write"))
    }

    @Test
    fun clampTurns() {
        assertEquals(1, SubAgentKind.clampTurns(SubAgentKind.WORKER, 0))
        assertEquals(12, SubAgentKind.clampTurns(SubAgentKind.EXPLORE, 99))
        assertEquals(12, SubAgentKind.clampTurns(SubAgentKind.WORKER, null))
        assertEquals(30, SubAgentKind.clampTurns(SubAgentKind.WORKER, 99, 30))
        assertEquals(30, SubAgentKind.clampTurns(SubAgentKind.WORKER, null, 30))
        assertEquals(8, SubAgentKind.clampTurns(SubAgentKind.PLAN, 8, 30))
    }

    @Test
    fun alwaysDropsNestedSpawn() {
        val tools = listOf(
            AgentToolDefinition("run_subagent", "d", mapOf("x" to AgentToolParam("string", "x")), listOf("x")),
            AgentToolDefinition("file_read", "r", mapOf("x" to AgentToolParam("string", "x")), listOf("x")),
        )
        assertEquals(listOf("file_read"), SubAgentKind.filterTools(SubAgentKind.WORKER, tools).map { it.name })
        assertEquals(listOf("file_read"), SubAgentKind.filterTools(SubAgentKind.EXPLORE, tools).map { it.name })
        assertTrue(SubAgentKind.blocks(SubAgentKind.WORKER, "run_subagent"))
        assertTrue(SubAgentKind.blocks(SubAgentKind.PLAN, "run_subagent"))
    }
}
