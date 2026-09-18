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
 * Shared-transcript plan discussion: main proposes, team models (or the
 * same model playing independent stances) do [ROUNDS] of agree/refute with
 * tools, then the main model synthesizes for the user to judge.
 */
object PlanDiscussionOrchestrator {
    const val ROUNDS = 3
    private const val MAX_TOOL_TURNS = 6

    data class Member(
        val displayName: String,
        val stance: String,
        val provider: LLMProvider,
        val maxTokens: Int,
    )

    data class Result(
        val markdown: String,
        val transcript: String,
    )

    suspend fun run(
        userText: String,
        conversationExcerpt: String,
        main: Member,
        members: List<Member>,
        tools: List<AgentToolDefinition>,
        executeTool: suspend (String, String) -> ToolExecutionResult,
        onProgress: suspend (String) -> Unit = {},
    ): Result {
        val board = StringBuilder()
        board.append("User request:\n").append(userText.trim()).append("\n")
        if (conversationExcerpt.isNotBlank()) {
            board.append("\nRecent conversation:\n").append(conversationExcerpt.take(6000)).append("\n")
        }

        suspend fun push(status: String) {
            onProgress(liveMarkdown(status, userText, board.toString()))
        }

        push("主会话正在提出实现方式…")
        val proposal = speak(
            member = main,
            tools = tools,
            executeTool = executeTool,
            system = facilitatorPrompt(main.displayName),
            user = "Propose the next implementation approach for the user request. Be concrete (files, steps, risks). Do not implement yet unless a tiny probe is required to decide.\n\n$board",
        )
        board.append("\n### Proposal (").append(main.displayName).append(")\n").append(proposal).append("\n")

        val panel = members.ifEmpty { listOf(main.copy(stance = "critic")) }
        repeat(ROUNDS) { round ->
            val n = round + 1
            push("计划讨论 第 $n/$ROUNDS 轮")
            for (m in panel) {
                push("${m.displayName}（${m.stance}）发言中…")
                val turn = speak(
                    member = m,
                    tools = tools,
                    executeTool = executeTool,
                    system = discussantPrompt(m.displayName, m.stance),
                    user = "Round $n of $ROUNDS. Read the shared board. Agree, refine, or refute with evidence. You may use tools to inspect the workspace. Do not claim the decision is final.\n\n$board",
                )
                board.append("\n### Round $n · ").append(m.displayName)
                    .append(" (").append(m.stance).append(")\n")
                    .append(turn).append("\n")
            }
        }

        push("主会话正在合成方案…")
        val synthesis = speak(
            member = main,
            tools = tools,
            executeTool = executeTool,
            system = synthesizerPrompt(main.displayName),
            user = "Synthesize the discussion into one plan the USER can accept or reject. List: recommended approach, dissent that was not adopted (and why), files/steps, risks. Do not start implementing.\n\n$board",
        )
        board.append("\n### Synthesis (").append(main.displayName).append(")\n").append(synthesis).append("\n")

        return Result(markdown = discussionMarkdown(board.toString()), transcript = board.toString())
    }

    fun discussionMarkdown(board: String): String {
        return buildString {
            append("## 计划讨论（主会话 × 子 Agent）\n\n")
            append("本轮在共享白板上进行：用户与主会话都能看见每一轮发言。讨论结束后主会话按 Synthesis 执行，不要再开一轮讨论。关闭入口：设置 → 多智能体。\n\n")
            append(board.trim())
            append("\n\n---\n讨论结束，本轮将按合成方案开始执行。\n")
        }
    }

    fun liveMarkdown(status: String, userText: String, board: String): String {
        return buildString {
            appendLine("## 计划讨论（进行中）")
            appendLine()
            appendLine("**状态：** $status")
            appendLine()
            appendLine("**任务：** ${userText.trim().take(500)}")
            appendLine()
            if (board.isNotBlank()) {
                appendLine(board.takeLast(12_000))
            }
        }
    }

    private suspend fun speak(
        member: Member,
        tools: List<AgentToolDefinition>,
        executeTool: suspend (String, String) -> ToolExecutionResult,
        system: String,
        user: String,
    ): String {
        val history = mutableListOf(
            LLMMessage(role = LLMMessage.Role.USER, content = user),
        )
        val report = StringBuilder()
        try {
            repeat(MAX_TOOL_TURNS) {
                val textSb = StringBuilder()
                val toolCalls = mutableListOf<Triple<String, String, JSONObject>>()
                member.provider.streamMessage(
                    messages = history,
                    systemPrompt = system,
                    maxTokens = member.maxTokens.coerceIn(256, 8192),
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
                }
                if (toolCalls.isEmpty()) {
                    return report.toString().ifBlank { text.ifBlank { "(empty)" } }
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
                    if (SubAgentKind.isSpawnTool(name)) {
                        resultParts.add(
                            AgentContentPart.ToolResult(
                                id = id,
                                name = name,
                                content = "Plan discussion members cannot spawn nested sub-agents.",
                                isError = true,
                            ),
                        )
                        continue
                    }
                    val result = try {
                        executeTool(name, args.toString())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ToolExecutionResult("Error: ${e.message ?: e.javaClass.simpleName}", false)
                    }
                    resultParts.add(
                        AgentContentPart.ToolResult(
                            id = id,
                            name = name,
                            content = result.output,
                            isError = !result.success,
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (report.isNotEmpty()) report.append("\n\n")
            report.append("(").append(member.displayName).append(" failed: ")
                .append(e.message ?: e.javaClass.simpleName).append(")")
        }
        return report.toString().trim().ifBlank { "(no statement)" }
    }

    private fun facilitatorPrompt(name: String) = """
        You are $name, the session facilitator for a PLAN DISCUSSION.
        Your statement is posted to a shared board visible to the USER and every discussant.
        Goal: propose the next implementation approach. Other models will see
        this board and may agree or refute. Use tools to inspect the real workspace.
        Do not implement the whole task. Stay in your own stance.
    """.trimIndent()

    private fun discussantPrompt(name: String, stance: String) = """
        You are $name in a shared plan discussion. Independent stance: $stance.
        You CAN see every previous statement on the board; the USER also sees this board live.
        Use tools (shell, files, skills) as needed.
        Agree, refine, or refute with specifics. Do not rubber-stamp. Do not implement the full task.
        Do not spawn sub-agents.
    """.trimIndent()

    private fun synthesizerPrompt(name: String) = """
        You are $name synthesizing a plan discussion for the USER.
        Produce one clear recommendation. Record dissent that you did not adopt.
        The full board is visible to the user. The user decides. Do not start coding the plan.
    """.trimIndent()
}
