package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sequential unique replacements on one file.
 *
 * Adapted from XINCODE-Public MultiEditTool (GPL-3.0-or-later).
 */
object MultiEditTool {
    const val NAME = "multi_edit"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Apply several unique old_string→new_string replacements to one file in order. Each old_string must match exactly once unless replace_all is set on that edit.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "path" to AgentToolParam("string", "Absolute Linux path."),
            "edits" to AgentToolParam(
                type = "array",
                description = "List of {old_string, new_string, replace_all?}.",
                items = AgentToolParam(
                    type = "object",
                    description = "One replacement",
                    properties = mapOf(
                        "old_string" to AgentToolParam("string", "Exact text to find"),
                        "new_string" to AgentToolParam("string", "Replacement"),
                        "replace_all" to AgentToolParam("boolean", "Replace every occurrence"),
                    ),
                    required = listOf("old_string", "new_string"),
                ),
            ),
        ),
        required = listOf("tool_title", "path", "edits"),
        propertyOrdering = listOf("tool_title", "path", "edits"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val toolTitle = try { JSONObject(argsJson).optString("tool_title", NAME) } catch (_: Exception) { NAME }
        return try {
            val args = JSONObject(argsJson)
            val path = args.optString("path", "")
            if (path.isBlank()) return ToolExecutionResult("Error: path required", false, toolTitle = toolTitle)
            if (PRootKernel.isLinuxPathUnderReadOnlyMount(path)) {
                return ToolExecutionResult("Error: $path is read-only mounted", false, toolTitle = toolTitle)
            }
            WritePathGuard.denyReason(path)?.let {
                return ToolExecutionResult(it, false, toolTitle = toolTitle)
            }
            val edits = args.optJSONArray("edits") ?: JSONArray()
            if (edits.length() == 0) return ToolExecutionResult("Error: edits required", false, toolTitle = toolTitle)
            var last: ToolExecutionResult? = null
            for (i in 0 until edits.length()) {
                val e = edits.getJSONObject(i)
                val one = JSONObject()
                    .put("tool_title", toolTitle)
                    .put("path", path)
                    .put("old_string", e.optString("old_string"))
                    .put("new_string", e.optString("new_string"))
                    .put("replace_all", e.optBoolean("replace_all", false))
                last = FileEditTool.execute(one.toString(), sessionId, context)
                if (last?.success != true) {
                    return ToolExecutionResult(
                        "multi_edit stopped at #${i + 1}: ${last?.output}",
                        false,
                        toolTitle = toolTitle,
                    )
                }
            }
            ToolExecutionResult("Applied ${edits.length()} edits to $path. ${last?.output.orEmpty()}", true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error multi_edit: ${e.message}", false, toolTitle = toolTitle)
        }
    }
}
