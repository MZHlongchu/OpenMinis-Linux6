package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONArray
import org.json.JSONObject

/**
 * Session-local plan board the model can set/advance.
 *
 * Adapted from XINCODE-Public AgentPlanTool (GPL-3.0-or-later).
 */
object AgentPlanStore {
    data class Plan(
        val title: String,
        val steps: List<String>,
        val index: Int = 0,
        val status: String = "active",
    )

    @Volatile
    var current: Plan? = null
        private set

    fun set(title: String, steps: List<String>) {
        current = Plan(title = title, steps = steps.filter { it.isNotBlank() }, index = 0, status = "active")
    }

    fun advance(): Plan? {
        val p = current ?: return null
        val next = (p.index + 1).coerceAtMost(p.steps.size)
        val status = if (next >= p.steps.size) "done" else "active"
        current = p.copy(index = next, status = status)
        return current
    }

    fun done() {
        current = current?.copy(status = "done")
    }

    fun fail() {
        current = current?.copy(status = "fail")
    }

    fun reset() {
        current = null
    }

    fun render(): String {
        val p = current ?: return "(no plan)"
        return buildString {
            append("plan: ${p.title} [${p.status}] ${p.index}/${p.steps.size}\n")
            p.steps.forEachIndexed { i, s ->
                val mark = when {
                    i < p.index -> "x"
                    i == p.index && p.status == "active" -> ">"
                    else -> " "
                }
                append("[$mark] ${i + 1}. $s\n")
            }
        }
    }
}

object AgentPlanTool {
    const val NAME = "agent_plan"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Maintain a short step plan for this session. op=set|advance|done|fail|reset|show.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "op" to AgentToolParam(
                "string",
                "set, advance, done, fail, reset, or show.",
                enumValues = listOf("set", "advance", "done", "fail", "reset", "show"),
            ),
            "title" to AgentToolParam("string", "Plan title when op=set."),
            "steps" to AgentToolParam(
                type = "array",
                description = "Step strings when op=set.",
                items = AgentToolParam("string", "One step"),
            ),
        ),
        required = listOf("tool_title", "op"),
        propertyOrdering = listOf("tool_title", "op", "title", "steps"),
    )

    fun execute(argsJson: String): ToolExecutionResult {
        val toolTitle = try { JSONObject(argsJson).optString("tool_title", NAME) } catch (_: Exception) { NAME }
        return try {
            val args = JSONObject(argsJson)
            when (args.optString("op", "show")) {
                "set" -> {
                    val steps = args.optJSONArray("steps") ?: JSONArray()
                    val list = (0 until steps.length()).map { steps.optString(it) }
                    AgentPlanStore.set(args.optString("title", "plan"), list)
                    ToolExecutionResult(AgentPlanStore.render(), true, toolTitle = toolTitle)
                }
                "advance" -> {
                    AgentPlanStore.advance()
                    ToolExecutionResult(AgentPlanStore.render(), true, toolTitle = toolTitle)
                }
                "done" -> {
                    AgentPlanStore.done()
                    ToolExecutionResult(AgentPlanStore.render(), true, toolTitle = toolTitle)
                }
                "fail" -> {
                    AgentPlanStore.fail()
                    ToolExecutionResult(AgentPlanStore.render(), true, toolTitle = toolTitle)
                }
                "reset" -> {
                    AgentPlanStore.reset()
                    ToolExecutionResult("(plan cleared)", true, toolTitle = toolTitle)
                }
                else -> ToolExecutionResult(AgentPlanStore.render(), true, toolTitle = toolTitle)
            }
        } catch (e: Exception) {
            ToolExecutionResult("Error agent_plan: ${e.message}", false, toolTitle = toolTitle)
        }
    }
}
