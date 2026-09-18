package com.openminis.app.ui.chat

import com.openminis.app.tools.SubAgentKind
import com.openminis.app.tools.WritePathGuard
import org.json.JSONObject

internal data class ChatSubAgentSpawn(
    val prompt: String,
    val role: String?,
    val skills: String?,
    val requestedModel: String?,
    val title: String,
    val kind: String,
    val writePaths: List<String>,
    val maxTurns: Int,
)

internal fun parseSubAgentSpawn(argsJson: String, defaultCap: Int): ChatSubAgentSpawn? {
    val args = try {
        JSONObject(argsJson)
    } catch (_: Exception) {
        return null
    }
    val prompt = args.optString("prompt", "").trim()
    if (prompt.isEmpty()) return null
    val kind = SubAgentKind.normalize(args.optString("kind", ""))
    return ChatSubAgentSpawn(
        prompt = prompt,
        role = args.optString("role", "").trim().ifEmpty { null },
        skills = args.optString("skills", "").trim().ifEmpty { null },
        requestedModel = args.optString("model", "").trim().ifEmpty { null },
        title = args.optString("tool_title", "").trim(),
        kind = kind,
        writePaths = WritePathGuard.parse(args.optString("write_paths", "")),
        maxTurns = SubAgentKind.clampTurns(
            kind,
            if (args.has("max_turns")) args.optInt("max_turns") else null,
            defaultCap,
        ),
    )
}
