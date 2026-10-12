package com.openminis.app.scheduled

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.LLMStreamChunk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-scheduled-tool-prefill] 回归：定时任务「询问模型前先执行固定 shell 命令」的
 * **执行环节**。
 *
 * 缺口原来的样子：实体（[PrefilledToolCall]）、CLI（`minis-scheduled create
 * --command`）、持久化（`ScheduledTask.prefillToolCall`）、信封（[ScheduledTaskMarker]）
 * 全都在，但循环里**没有任何人消费** `pendingScriptedTurn` —— 预置命令被赋了值就
 * 再也读不到，实际发出去的只有一句纯提示词。上游 1.14 的落点是
 * `ChatViewModel.kt:11248-11265`（脚本回合的 chunk 源）与 `:12602-12611`
 * （循环检测豁免），本移植的循环在 `ChatViewModelAgentLoopExt.kt`。
 *
 * 这里钉住的是「执行序」本身，而不只是接线：
 *  - 脚本回合产出的是**普通工具调用 chunk**，因此它走的是同一个 collect、同一张
 *    工具卡、同一条 dispatch / 持久化路径 —— 命令真的被执行；
 *  - 命令失败时它作为 tool_result（isError）回到模型，和模型自己发出的失败调用
 *    一模一样，不是「跳过」；
 *  - 用不了的预置（工具不在本会话的工具表里 / 校验不通过）整条丢掉，退回
 *    「模型自己决定」的旧行为。
 */
class ScheduledPrefillExecutionTest {

    private val loop by lazy { ProductionSources.read("ui/chat/ChatViewModelAgentLoopExt.kt") }
    private val vm by lazy { ProductionSources.readClassSurface("ui/chat/ChatViewModel.kt") }

    // ── the command really runs: the scripted turn is an ordinary tool call ──

    @Test
    fun `the scripted turn is a normal tool-call stream, so the ordinary dispatch executes it`() {
        val chunks = ScriptedToolTurn.from(listOf(PrefilledToolCall.shell("curl -s wttr.in")), setOf("shell_execute"))!!
            .asStreamChunks()
        // Start → (ToolUseStart, ToolCallComplete) per call → Finished(tool_use).
        assertEquals(4, chunks.size)
        assertTrue(chunks[0] is LLMStreamChunk.Started)
        val start = chunks[1] as LLMStreamChunk.ToolUseStart
        val complete = chunks[2] as LLMStreamChunk.ToolCallComplete
        assertEquals("shell_execute", start.name)
        assertEquals(start.id, complete.id)
        assertEquals("curl -s wttr.in", complete.args.optString("command"))
        // tool_use finish: the loop treats the turn as "the model asked for tools",
        // which is what routes it into the SAME dispatch the provider stream uses.
        assertEquals(ScriptedToolTurn.STOP_REASON, (chunks[3] as LLMStreamChunk.Finished).stopReason)
    }

    @Test
    fun `the loop streams the scripted calls into its one collector instead of a provider request`() {
        assertTrue(loop.contains("kotlinx.coroutines.flow.flowOf(*scriptedTurn.asStreamChunks().toTypedArray())"))
        // Exactly one collector, shared by both chunk sources: a prefilled call
        // cannot diverge from a model-emitted one after this point.
        assertEquals(1, Regex("""turnChunks\.collect \{ chunk ->""").findAll(loop).count())
        // Consumed on the first attempt and only there, so a retry of this turn
        // can never replay the command (the scripted stream cannot fail).
        val hook = loop.substringAfter("val scriptedTurn = pendingScriptedTurn?.takeUnless { helperWrapUpInjected }")
        assertTrue(hook.trimStart().startsWith("pendingScriptedTurn = null"))
    }

    @Test
    fun `a failing command comes back as a tool_result error, not as a dropped prefill`() {
        // A command that will fail at RUNTIME is still a valid prefill: nothing
        // about it is validated up front beyond its shape. That is what makes the
        // failure semantics match a model-emitted call — the tool runs, fails,
        // and its error travels back as this call's tool_result.
        val doomed = PrefilledToolCall.shell("exit 3", title = "Doomed")
        assertNull(doomed.validationError())
        val turn = ScriptedToolTurn.from(listOf(doomed), setOf(PrefilledToolCall.SHELL_TOOL))
        assertEquals(1, turn!!.calls.size)
        assertFalse(
            "the call is still dispatched — dropping it would hide the failure from the model",
            turn.calls.single().argsJson.isEmpty(),
        )
    }

    @Test
    fun `an unusable prefill falls back to the old behaviour instead of failing the run`() {
        // Tool this session does not offer (e.g. the scheduled fire landed in a
        // chat whose tool table lacks shell_execute).
        assertNull(ScriptedToolTurn.from(listOf(PrefilledToolCall.shell("ls")), setOf("file_read")))
        // Structurally invalid payload: refused up front, same degradation.
        val invalid = PrefilledToolCall.of("shell_execute", JSONObject())
        assertTrue(invalid.validationError() != null)
        assertNull(ScriptedToolTurn.from(listOf(invalid), setOf(PrefilledToolCall.SHELL_TOOL)))
        // Both funnels hand the RAW list to the loop and let scriptedTurnFor drop it.
        assertTrue(loop.contains("var pendingScriptedTurn: com.openminis.app.scheduled.ScriptedToolTurn? = scriptedTurnFor(prefill)"))
        assertTrue(ProductionSources.read("scheduled/ScheduledAgentRunner.kt")
            .contains("val prefill = listOfNotNull(task.prefillToolCall?.takeIf { it.validationError() == null })"))
    }

    @Test
    fun `a prefilled call is exempt from the loop-detector pre-check`() {
        // The same fixed command every day is the POINT, and the loop detector
        // counts identical (tool, args) pairs — without this exemption a
        // long-lived follow-up session would block its own scheduled command.
        assertTrue(loop.contains("val precheck = if (com.openminis.app.scheduled.ScriptedToolTurn.isScriptedId(id)) {"))
        val branch = loop.substringAfter("val precheck = if (com.openminis.app.scheduled.ScriptedToolTurn.isScriptedId(id)) {")
            .substringBefore("if (precheck.level == Level.CRITICAL) {")
        assertTrue(branch.contains("com.openminis.app.harness.agent.LoopCheckResult.NONE"))
        assertTrue(branch.contains("toolLoopDetector.check(name, paramsMap)"))
    }

    // ── the prefill survives every hop of the send path ────────────────────

    @Test
    fun `both loops that can carry the prompt receive the prefill`() {
        // Idle session: the send funnel hands it to the loop directly.
        assertTrue(vm.contains("prefill = prefill,"))
        // Busy session: the queued prompt keeps it and the drain carries it.
        assertTrue(vm.contains("prefill = queued.flatMap { it.prefill },"))
        // Mid-loop injection re-arms the one-shot for the turn it just started.
        assertTrue(vm.contains("scriptedTurnFor(handled.prefill)?.let { pendingScriptedTurn = it }"))
        // A rerun replays an existing message; there is nothing new to run.
        val runner = ProductionSources.read("scheduled/ScheduledAgentRunner.kt")
        assertEquals(
            "only the two prompting targets take a prefill",
            2, Regex("""prefill = prefill,""").findAll(runner).count(),
        )
    }

    @Test
    fun `the system prompt asks the model for the prefilled command`() {
        // The entry point of the whole feature: the CLI, the entity and the
        // executor exist — if the prompt never names `--command`, no task ever
        // carries one and the execution path stays dead.
        val prompt = ProductionSources.read("ui/chat/ChatViewModelPromptExt.kt")
        assertTrue(prompt.contains("add `--command \"<shell command>\"` (optionally `--command-timeout 2m`)"))
        assertTrue(prompt.contains("you receive its real output as an already-completed tool call"))
        assertTrue(prompt.contains("For a check that should happen AFTER this turn ends, register it by running `minis-scheduled create …` via shell_execute"))
    }
}
