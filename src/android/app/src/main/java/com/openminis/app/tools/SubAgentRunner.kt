package com.openminis.app.tools

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.LLMProvider
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Nested agent loop used by [spawn_agent]. Sub-agents do not receive the
 * parent conversation and must not spawn further sub-agents.
 */
object SubAgentRunner {

    const val MAX_TURNS = 60
    private const val MAX_REPORT_CHARS = 24_000

    suspend fun run(
        provider: LLMProvider,
        modelDisplayName: String,
        userPrompt: String,
        role: String?,
        skillsHint: String?,
        tools: List<AgentToolDefinition>,
        maxTokens: Int,
        executeTool: suspend (name: String, argsJson: String) -> ToolExecutionResult,
        onStep: suspend (turn: Int, toolName: String) -> Unit = { _, _ -> },
        kind: String = SubAgentKind.WORKER,
        writePaths: List<String> = emptyList(),
        maxTurns: Int = MAX_TURNS,
    ): ToolExecutionResult {
        val briefed = SubAgentBrief.wrap(userPrompt, kind = kind, role = role, writePaths = writePaths)
        val history = mutableListOf(
            LLMMessage(role = LLMMessage.Role.USER, content = briefed),
        )
        val turns = maxTurns.coerceIn(
            com.openminis.app.data.repository.MultiAgentSettings.MIN_SUBAGENT_TURNS,
            com.openminis.app.data.repository.MultiAgentSettings.MAX_SUBAGENT_TURNS,
        )
        val system = workerSystemPrompt(modelDisplayName, role, skillsHint, kind, writePaths)
        val report = StringBuilder()

        try {
            repeat(turns) { turnIdx ->
                val turn = turnIdx + 1
                runCatching { onStep(turn, "") }
                val textSb = StringBuilder()
                val toolCalls = mutableListOf<Triple<String, String, JSONObject>>()
                provider.streamMessage(
                    messages = history,
                    systemPrompt = system,
                    maxTokens = maxTokens.coerceIn(256, 8192),
                    tools = tools,
                    thinkingLevel = ThinkingLevel.OFF,
                ).collect { chunk ->
                    when (chunk) {
                        is LLMStreamChunk.Text -> textSb.append(chunk.text)
                        is LLMStreamChunk.ToolCallComplete ->
                            toolCalls.add(Triple(chunk.id, chunk.name, chunk.args))
                        else -> Unit
                    }
                }

                val text = textSb.toString().trim()
                if (text.isNotEmpty()) {
                    if (report.isNotEmpty()) report.append("\n\n")
                    report.append(text)
                    val snippet = text.replace('\n', ' ').trim().take(160)
                    if (snippet.isNotEmpty()) {
                        runCatching { onStep(turn, "") }
                    }
                }

                if (toolCalls.isEmpty()) {
                    runCatching { onStep(turn, "done") }
                    val out = report.toString().ifBlank { text.ifBlank { "(sub-agent finished with empty output)" } }
                    return ToolExecutionResult(truncate(out), true)
                }

                val assistantParts = mutableListOf<AgentContentPart>()
                if (text.isNotEmpty()) assistantParts.add(AgentContentPart.Text(text))
                for ((id, name, args) in toolCalls) {
                    assistantParts.add(AgentContentPart.ToolUse(id, name, input = args))
                }
                history.add(
                    LLMMessage(
                        role = LLMMessage.Role.ASSISTANT,
                        content = text,
                        contentParts = assistantParts,
                    ),
                )

                val resultParts = mutableListOf<AgentContentPart>()
                for ((id, name, args) in toolCalls) {
                    if (SubAgentKind.isSpawnTool(name) || SubAgentKind.blocks(kind, name)) {
                        resultParts.add(
                            AgentContentPart.ToolResult(
                                id = id,
                                name = name,
                                content = "Error: sub-agents cannot spawn further sub-agents or use blocked tools. Complete the assigned work yourself.",
                                isError = true,
                            ),
                        )
                        continue
                    }
                    val argsJson = args.toString()
                    runCatching { onStep(turn, name) }
                    val result = try {
                        executeTool(name, argsJson)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ToolExecutionResult("Error: ${e.message ?: e.javaClass.simpleName}", false)
                    }
                    runCatching { onStep(turn, name) }
                    resultParts.add(
                        AgentContentPart.ToolResult(
                            id = id,
                            name = name,
                            content = result.output,
                            isError = !result.success,
                            imageData = result.imageData,
                            imageMimeType = result.imageMimeType,
                            imageLinuxPath = result.imageLinuxPath,
                        ),
                    )
                }
                history.add(
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = "",
                        contentParts = resultParts,
                    ),
                )
            }
            val out = report.toString().ifBlank { "(sub-agent hit the $turns-turn cap without a final answer)" }
            return ToolExecutionResult(truncate(out), true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val prefix = report.toString().trim()
            val msg = buildString {
                if (prefix.isNotEmpty()) {
                    append(prefix)
                    append("\n\n")
                }
                append("Sub-agent failed: ${e.message ?: e.javaClass.simpleName}")
            }
            return ToolExecutionResult(truncate(msg), false)
        }
    }

    fun previewToolArgs(argsJson: String): String {
        return try {
            val o = JSONObject(argsJson)
            val raw = when {
                o.has("command") -> o.optString("command")
                o.has("path") -> o.optString("path")
                o.has("query") -> o.optString("query")
                o.has("url") -> o.optString("url")
                o.has("pattern") -> o.optString("pattern")
                else -> argsJson
            }
            raw.replace('\n', ' ').trim().take(80)
        } catch (_: Exception) {
            argsJson.replace('\n', ' ').trim().take(80)
        }
    }

    private fun truncate(text: String): String {
        if (text.length <= MAX_REPORT_CHARS) return text
        return "…(truncated)\n" + text.takeLast(MAX_REPORT_CHARS)
    }

    private fun workerSystemPrompt(
        modelDisplayName: String,
        role: String?,
        skillsHint: String?,
        kind: String,
        writePaths: List<String>,
    ): String {
        val roleLine = role?.trim()?.takeIf { it.isNotEmpty() }?.let { "Assigned role: $it.\n" } ?: ""
        val skillsLine = skillsHint?.trim()?.takeIf { it.isNotEmpty() }?.let {
            "Read these skills first (file_read `/var/minis/skills/<id>/SKILL.md`): $it\n"
        } ?: ""
        val kindLine = "Kind: $kind.\n"
        val writeLine = if (writePaths.isNotEmpty()) {
            "You may only file_write/file_edit under: ${writePaths.joinToString()}. " +
                "Keep shell writes in those prefixes; MINIS_WRITE_PATHS is exported.\n"
        } else {
            ""
        }
        val toolLine = if (SubAgentKind.isReadOnly(kind)) {
            "- Read-only: do not modify files or run shell_execute. Use file_read, search_sessions, read_session, web_search, browser_use."
        } else {
            "- Use tools immediately. Prefer file_read / file_edit / file_write / shell_execute."
        }
        return """You are a sub-agent ($kind), not the session coordinator. Model: $modelDisplayName.
${roleLine}${skillsLine}${kindLine}${writeLine}You cannot see the parent conversation. The user prompt is a self-contained brief with ## Task / ## Expected result / ## Constraints / ## Workflow / ## Collaboration.

Rules:
- Complete ONLY the assigned slice. Do not rewrite unrelated files.
- Do not spawn further sub-agents. spawn_agent / run_subagent are not available and will error if you try.
$toolLine
- Follow the brief's Workflow, then return a concise report: what changed, files touched, leftover risks, and whether Expected result passed.
- If you cannot meet the acceptance criteria, say so explicitly and list what failed.
"""
    }
}
