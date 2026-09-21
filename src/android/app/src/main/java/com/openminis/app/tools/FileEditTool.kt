package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject

object FileEditTool {
    const val NAME = "file_edit"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Make targeted edits to an existing file using exact string replacement. ALWAYS use file_read first to see the current file contents before editing. Prefer file_edit over file_write when modifying existing files — only the changed part needs to be specified. The old_string must match exactly one location in the file (including whitespace/indentation), unless replace_all is true.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Fix typo in Python script', 'Update config value'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Absolute Linux path to the file to edit (e.g. /root/script.py)"),
            "old_string" to AgentToolParam("string", "The exact text to find in the file. Must match precisely including whitespace and indentation. Must be unique in the file unless replace_all is true."),
            "new_string" to AgentToolParam("string", "The replacement text. Use empty string to delete old_string."),
            "replace_all" to AgentToolParam("boolean", "If true, replace ALL occurrences of old_string (default: false)"),
        ),
        required = listOf("tool_title", "path", "old_string", "new_string"),
        propertyOrdering = listOf("tool_title", "path", "old_string", "new_string", "replace_all"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val path = args.optString("path", "")
            val oldString = args.optString("old_string", "")
            val newString = args.optString("new_string", "")
            val replaceAll = args.optBoolean("replace_all", false)
            val toolTitle = args.optString("tool_title", NAME)

            if (path.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }
            if (oldString.isEmpty()) {
                return ToolExecutionResult("Error: 'old_string' is required and cannot be empty", false, toolTitle = toolTitle)
            }

            // T219: read-only mount guard — see FileWriteTool for rationale.
            if (PRootKernel.isLinuxPathUnderReadOnlyMount(path)) {
                return ToolExecutionResult(
                    "Error: $path is inside a read-only mounted folder and cannot be modified. " +
                        "Toggle writability in Settings → Mount External Folders if this is a mistake.",
                    false, toolTitle = toolTitle,
                )
            }
            WritePathGuard.denyReason(path)?.let { msg ->
                return ToolExecutionResult(msg, false, toolTitle = toolTitle)
            }

            // T123: per-session resolver — see FileWriteTool for rationale.
            val file = PRootKernel.resolveSessionHostPath(sessionId, path, context)
                ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false, toolTitle = toolTitle)

            if (!file.exists()) {
                return ToolExecutionResult("Error: File not found: $path", false, toolTitle = toolTitle)
            }

            // T-tool-write-race: read, compute AND write under ONE lock.
            //
            // The old code read the file, computed the replacement, then called
            // writeText — all without holding anything. Two concurrent edits
            // each read v1 and each wrote back their own view of it, so the
            // first edit was silently discarded and BOTH calls returned
            // success, because writeText never throws. That is the reported
            // "reported success but nothing landed".
            //
            // readModifyWrite takes the read inside the lock, so the compute
            // always sees the bytes this write will replace.
            val outcome = AtomicFileWrite.readModifyWrite(file) { current ->
                var count = 0
                var searchFrom = 0
                while (true) {
                    val idx = current.indexOf(oldString, searchFrom)
                    if (idx < 0) break
                    count++
                    searchFrom = idx + oldString.length
                }

                if (count == 0) {
                    return@readModifyWrite null
                }
                if (count > 1 && !replaceAll) return@readModifyWrite null

                val newContent = if (replaceAll) {
                    current.replace(oldString, newString)
                } else {
                    current.replaceFirst(oldString, newString)
                }
                newContent to (if (replaceAll) count else 1)
            }

            when (outcome) {
                null -> return ToolExecutionResult(
                    if (replaceAll) {
                        "Error: old_string not found in $path"
                    } else {
                        // Ambiguous and not-found both landed here; the count was
                        // already reported distinctly by the old inline check, so
                        // re-derive it for the message without writing anything.
                        val found = AtomicFileWrite.read(file)?.let { cur ->
                            var c = 0; var from = 0
                            while (true) {
                                val i = cur.indexOf(oldString, from)
                                if (i < 0) break
                                c++; from = i + oldString.length
                            }
                            c
                        } ?: 0
                        if (found > 1) {
                            "Error: old_string found $found times in $path. " +
                                "Use replace_all=true to replace all occurrences, " +
                                "or provide a more specific old_string that matches exactly once."
                        } else {
                            "Error: old_string not found in $path"
                        }
                    },
                    false, toolTitle = toolTitle,
                )
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    val (newContent, replacements) = outcome as Pair<String, Int>
                    ToolExecutionResult(
                        "Edited $path ($replacements replacement(s), ${newContent.length} bytes)",
                        true, toolTitle = toolTitle,
                    )
                }
            }
        } catch (e: Exception) {
            ToolExecutionResult("Error editing file: ${e.message}", false)
        }
    }
}
