package com.openminis.app.tools

import android.content.Context
import java.io.File

/**
 * Spill oversized tool results out of the LLM context into the session
 * workspace. Inspired by long-running Android agents that otherwise freeze
 * Compose and blow the context window when `shell_execute` dumps megabytes.
 *
 * The UI still shows a short tail; the model gets a preview plus a guest
 * path it can `file_read` if it actually needs more.
 */
object ToolOutputSpill {
    const val LIMIT = 16_000
    const val HEAD = 6_000
    const val TAIL = 4_000

    fun maybeSpill(
        context: Context,
        sessionId: String,
        toolName: String,
        toolId: String,
        output: String,
    ): String {
        if (output.length <= LIMIT || sessionId.isBlank()) return output
        val safeId = toolId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "tool" }
        val dir = File(
            com.openminis.app.sandbox.SessionWorkspace.hostDir(context.filesDir, sessionId, "workspace"),
            "tool-spill",
        )
        if (!dir.mkdirs() && !dir.isDirectory) return output
        val file = File(dir, "$safeId.txt")
        return try {
            file.writeText(output)
            formatPreview(
                toolName = toolName,
                guestPath = "/var/minis/workspace/tool-spill/${file.name}",
                output = output,
            )
        } catch (_: Exception) {
            output.take(LIMIT)
        }
    }

    fun formatPreview(toolName: String, guestPath: String, output: String): String {
        val head = output.take(HEAD)
        val tail = if (output.length > HEAD + TAIL) output.takeLast(TAIL) else ""
        return buildString {
            append("[tool-output-spill] $toolName produced ${output.length} chars. ")
            append("Full output saved to $guestPath — use file_read if you need more.\n")
            append("Open in app: minis://workspace/tool-spill/${guestPath.substringAfterLast('/')}\n\n")
            append(head)
            if (tail.isNotEmpty()) {
                append("\n\n…(${output.length - HEAD - TAIL} chars omitted)…\n\n")
                append(tail)
            }
        }
    }

    private val GUEST_PATH = Regex("/var/minis/workspace/tool-spill/([A-Za-z0-9._-]+)")

    fun parseGuestPath(content: String): String? {
        val m = GUEST_PATH.find(content) ?: return null
        return "/var/minis/workspace/tool-spill/${m.groupValues[1]}"
    }

    fun hostFile(context: Context, sessionId: String, guestPath: String): File? {
        val name = parseGuestPath(guestPath)?.substringAfterLast('/') ?: return null
        if (sessionId.isBlank() || name.isBlank()) return null
        val file = File(
            com.openminis.app.sandbox.SessionWorkspace.hostDir(context.filesDir, sessionId, "workspace"),
            "tool-spill/$name",
        )
        return file.takeIf { it.isFile }
    }
}
