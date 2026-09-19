package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentKindTest {

    @Test
    fun normalizeKinds() {
        assertEquals(SubAgentKind.EXPLORE, SubAgentKind.normalize("read-only"))
        assertEquals(SubAgentKind.EXPLORE, SubAgentKind.normalize("recon"))
        assertEquals(SubAgentKind.PLAN, SubAgentKind.normalize("planner"))
        assertEquals(SubAgentKind.GENERAL, SubAgentKind.normalize("general-purpose"))
        assertEquals(SubAgentKind.GENERAL, SubAgentKind.normalize("gp"))
        assertEquals(SubAgentKind.WORKER, SubAgentKind.normalize(null))
    }

    @Test
    fun exploreDropsWriteToolsKeepsReadWhitelist() {
        val tools = listOf(
            AgentToolDefinition("file_read", "r", mapOf("x" to AgentToolParam("string", "x")), listOf("x")),
            AgentToolDefinition("file_write", "w", mapOf("x" to AgentToolParam("string", "x")), listOf("x")),
            AgentToolDefinition("shell_execute", "s", mapOf("x" to AgentToolParam("string", "x")), listOf("x")),
            AgentToolDefinition("web_search", "q", mapOf("x" to AgentToolParam("string", "x")), listOf("x")),
        )
        val names = SubAgentKind.filterTools(SubAgentKind.EXPLORE, tools).map { it.name }
        assertEquals(listOf("file_read", "web_search"), names)
        assertTrue(SubAgentKind.blocks(SubAgentKind.PLAN, "file_edit"))
        assertFalse(SubAgentKind.blocks(SubAgentKind.WORKER, "file_write"))
        assertFalse(SubAgentKind.blocks(SubAgentKind.GENERAL, "file_write"))
    }

    @Test
    fun clampAndInferTurns() {
        assertEquals(10, SubAgentKind.inferTurns(SubAgentKind.EXPLORE, "look at Foo.kt"))
        assertEquals(40, SubAgentKind.inferTurns(SubAgentKind.WORKER, "implement the login flow"))
        assertEquals(10, SubAgentKind.clampTurns(SubAgentKind.EXPLORE, null, 60, "look at Foo.kt"))
        assertEquals(99, SubAgentKind.clampTurns(SubAgentKind.EXPLORE, 99))
        assertEquals(20, SubAgentKind.clampTurns(SubAgentKind.WORKER, null, 60, "do the slice"))
        assertEquals(30, SubAgentKind.clampTurns(SubAgentKind.WORKER, 99, 30))
        assertEquals(8, SubAgentKind.clampTurns(SubAgentKind.PLAN, 8, 30))
        assertEquals(10, SubAgentKind.clampTurns(SubAgentKind.WORKER, 0, 60, "find the crash"))
    }

    @Test
    fun nestedSpawnAlwaysDropped() {
        val tools = listOf(
            AgentToolDefinition("spawn_agent", "d", mapOf("x" to AgentToolParam("string", "x")), listOf("x")),
            AgentToolDefinition("run_subagent", "d", mapOf("x" to AgentToolParam("string", "x")), listOf("x")),
            AgentToolDefinition("file_read", "r", mapOf("x" to AgentToolParam("string", "x")), listOf("x")),
            AgentToolDefinition("cronjob", "c", mapOf("x" to AgentToolParam("string", "x")), listOf("x")),
        )
        val names = SubAgentKind.filterTools(SubAgentKind.WORKER, tools).map { it.name }
        assertTrue(names.none { SubAgentKind.isSpawnTool(it) })
        assertFalse(names.contains("cronjob"))
        assertTrue(SubAgentKind.blocks(SubAgentKind.EXPLORE, CronJobTool.NAME))
        assertTrue(SubAgentKind.blocks(SubAgentKind.WORKER, "run_subagent"))
        assertTrue(SubAgentKind.blocks(SubAgentKind.PLAN, "spawn_agent"))
        assertTrue(SubAgentKind.blocks(SubAgentKind.GENERAL, "spawn_agent"))
        assertTrue(SubAgentKind.isSpawnTool("spawn_agent"))
        assertTrue(SubAgentKind.isSpawnTool("run_subagent"))
    }

    @Test
    fun parallelWorkersRequireWritePaths() {
        assertTrue(SubAgentKind.requiresWritePaths(SubAgentKind.WORKER, 2))
        assertFalse(SubAgentKind.requiresWritePaths(SubAgentKind.WORKER, 1))
        assertFalse(SubAgentKind.requiresWritePaths(SubAgentKind.EXPLORE, 4))
        assertFalse(SubAgentKind.requiresWritePaths(SubAgentKind.GENERAL, 3))
        assertTrue(SubAgentKind.canWrite(SubAgentKind.WORKER))
        assertTrue(SubAgentKind.canWrite(SubAgentKind.GENERAL))
        assertFalse(SubAgentKind.canWrite(SubAgentKind.EXPLORE))
    }

    @Test
    fun nestedObjectParamSerializesItems() {
        val param = AgentToolParam(
            type = "array",
            description = "tasks",
            items = AgentToolParam(
                type = "object",
                description = "task",
                properties = mapOf("prompt" to AgentToolParam("string", "brief")),
                required = listOf("prompt"),
            ),
        )
        val json = param.toJson().toString()
        assertTrue(json.contains("\"type\":\"array\""))
        assertTrue(json.contains("\"items\""))
        assertTrue(json.contains("\"prompt\""))
        val gemini = param.toGeminiJson().toString()
        assertTrue(gemini.contains("\"type\":\"ARRAY\""))
    }
}
