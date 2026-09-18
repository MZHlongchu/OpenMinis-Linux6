package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition

/**
 * 拾忆 `spawn_agent` kinds, mapped onto OpenMinis `run_subagent`.
 *
 * - worker: full tools except nested sub-agents (existing behaviour)
 * - explore / plan: read-only — no file_write, file_edit, shell_execute
 */
object SubAgentKind {
    const val WORKER = "worker"
    const val EXPLORE = "explore"
    const val PLAN = "plan"

    private val READ_ONLY = setOf(EXPLORE, PLAN)
    const val RUN_SUBAGENT = "run_subagent"
    val BLOCKED_WHEN_READ_ONLY = setOf(
        FileWriteTool.NAME,
        FileEditTool.NAME,
        "shell_execute",
        "memory_write",
    )

    fun normalize(raw: String?): String {
        return when (raw?.trim()?.lowercase().orEmpty()) {
            EXPLORE, "read", "readonly", "read-only", "search" -> EXPLORE
            PLAN, "planner" -> PLAN
            else -> WORKER
        }
    }

    fun isReadOnly(kind: String): Boolean = kind in READ_ONLY

    fun blocks(kind: String, toolName: String): Boolean {
        if (toolName == RUN_SUBAGENT) return true
        return isReadOnly(kind) && toolName in BLOCKED_WHEN_READ_ONLY
    }

    fun filterTools(kind: String, tools: List<AgentToolDefinition>): List<AgentToolDefinition> {
        return tools.filter { def ->
            def.name != RUN_SUBAGENT && !(isReadOnly(kind) && def.name in BLOCKED_WHEN_READ_ONLY)
        }
    }

    fun clampTurns(kind: String, requested: Int?): Int {
        val cap = if (isReadOnly(kind)) 16 else 20
        val n = requested ?: SubAgentRunner.MAX_TURNS
        return n.coerceIn(1, cap)
    }
}
