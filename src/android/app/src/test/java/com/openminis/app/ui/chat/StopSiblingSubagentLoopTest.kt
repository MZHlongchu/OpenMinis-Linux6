package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import com.openminis.app.agent.jobs.AgentJobRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-stop-sibling-subagent-loop] Stopping a sub agent from its card
 * must stop the conversation that delegated it (iOS 59c1d7208).
 *
 * Android already muted the parent and cancelled the whole batch
 * (AgentJobRegistry.cancelSiblings), and the mute kept a finished sibling's
 * callback from waking the parent. But a parent turn that was itself waiting
 * on the batch came back to runAgentLoop with the stopped results and sent
 * them to the model, which then delegated again.
 */
class StopSiblingSubagentLoopTest {

    // [T-android-stop-sibling-subagent-loop] 本移植把 ChatViewModel 拆成了
    // ChatViewModel*.kt（见 src/main/java/.../ui/chat/），agent 循环现在在
    // ChatViewModelAgentLoopExt.kt —— 断言逐条保留，只把扫描面指向真正承载循环
    // 的文件（原 ChatViewModel.kt 里已经没有 runAgentLoop）。
    private val vm = ProductionSources.read("ui/chat/ChatViewModelAgentLoopExt.kt")
    private val monolith = ProductionSources.read("ui/chat/ChatViewModel.kt")

    @After fun reset() = AgentJobRegistry.resetForTest()

    @Test
    fun `the mute is what the loop and the result writer read`() {
        AgentJobRegistry.muteDelegationResults("parent")
        assertTrue(AgentJobRegistry.isDelegationMuted("parent"))
        assertFalse("a child's own loop is keyed on its own session", AgentJobRegistry.isDelegationMuted("child"))
        AgentJobRegistry.clearDelegationMute("parent")
        assertFalse(AgentJobRegistry.isDelegationMuted("parent"))
    }

    @Test
    fun `the parent loop stops after the results are recorded and before anything else is sent`() {
        // [T-android-stop-sibling-subagent-loop] 三处锚点跟着本移植的结构走，语义
        // 一条不少：
        //  - 工具结果进 history 在本移植是
        //    `conversationPort().append(ToolRoundOutcome.toolResultMessage(...))`
        //    （上游是就地构造 LLMMessage(contentParts = resultParts, dbMessageId = …)，
        //    本移植把那次构造收进了 ToolRoundOutcome）。
        //  - "队列可以注入新回合"的位置用 `injectQueuedPromptsAsNewTurn(` 的调用点
        //    表达，比上游原先钉的 `val insertBatch = …` 更直接：从 gate 之后找
        //    这一次调用，就是"还没有任何新东西被发出去"的那条线。
        val appended = vm.indexOf("conversationPort().append(ToolRoundOutcome.toolResultMessage(resultParts, toolResultDbId))")
        val gate = vm.indexOf("if (com.openminis.app.agent.jobs.AgentJobRegistry.isDelegationMuted(activeSessionId)) {")
        val inject = vm.indexOf("injectQueuedPromptsAsNewTurn(", gate)
        assertTrue("results appended", appended >= 0)
        assertTrue("gate after the results are appended", gate > appended)
        assertTrue("gate before the queue can inject a new turn", gate in 0 until inject)
        val body = vm.substring(gate, inject)
        assertTrue(body.contains("loopExitedNormally = true"))
        assertTrue(body.contains("break"))
        assertTrue("the bubble stops showing 'thinking'", body.contains("isAwaitingModelResponse = false"))
    }

    @Test
    fun `only queued delegation notices are dropped, never the user's own or a scheduled fire`() {
        val gate = vm.substringAfter("if (com.openminis.app.agent.jobs.AgentJobRegistry.isDelegationMuted(activeSessionId)) {")
            .substringBefore("loopExitedNormally = true")
        assertTrue(gate.contains("it.origin == QueuedPromptOrigin.PROGRAMMATIC && !it.isScheduledFire"))
        assertTrue(gate.contains("it.text.contains(\"<agent_callback\")"))
    }

    @Test
    fun `a stopped batch's final result is recorded but kept out of the model's history`() {
        val persist = vm.substringAfter("private suspend fun persistFinalDelegateResult(")
            .substringBefore("private fun executeAgentStatus(")
        val muted = persist.indexOf("val muted = com.openminis.app.agent.jobs.AgentJobRegistry.isDelegationMuted(activeSessionId)")
        val rewrite = persist.indexOf("if (!muted) withContext(Dispatchers.Main) {")
        val dbWrite = persist.indexOf("chatRepository.updateMessageParts(row.id, arr.toString())")
        assertTrue(muted >= 0 && rewrite > muted)
        assertTrue("the DB row is still written", dbWrite > rewrite)
    }
}
