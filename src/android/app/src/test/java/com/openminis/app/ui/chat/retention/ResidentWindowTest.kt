package com.openminis.app.ui.chat.retention

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model-context retention rule, and the reason the chat surface is not
 * part of it.
 *
 * The reported OOM was a 4,562-message session (5.4M chars, 4,288 tool
 * messages) whose oversized tool output sits in the model context. 2.0.10
 * also spilled the RENDERED messages, replacing terminal tool blocks with an
 * `[CONTEXT OFFLOADED] N chars at ...` stub on every send. Nothing renders
 * that stub back, so the user lost a chunk of the transcript on the next
 * message sent — a byte saving that is really silent data loss.
 */
class ResidentWindowTest {
    private fun text(body: String) = LLMMessage(
        role = LLMMessage.Role.ASSISTANT,
        content = body,
        contentParts = listOf(AgentContentPart.Text(body)),
    )

    private fun toolTurn(id: String, chars: Int) = LLMMessage(
        role = LLMMessage.Role.ASSISTANT,
        content = "",
        contentParts = listOf(
            AgentContentPart.ToolUse(id, "shell_execute", JSONObject().put("cmd", "run")),
            AgentContentPart.ToolResult(id, "shell_execute", "y".repeat(chars)),
        ),
    )

    private fun splitToolUse(id: String) = LLMMessage(
        role = LLMMessage.Role.ASSISTANT,
        content = "",
        contentParts = listOf(
            AgentContentPart.ToolUse(id, "shell_execute", JSONObject().put("cmd", "run")),
        ),
    )

    private fun splitToolResult(id: String, chars: Int) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = "",
        contentParts = listOf(
            AgentContentPart.ToolResult(id, "shell_execute", "y".repeat(chars)),
        ),
    )

    /** A tool result with no matching use anywhere — an illegal boundary. */
    private fun bareResult(id: String, chars: Int) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = "",
        contentParts = listOf(
            AgentContentPart.ToolResult("call-$id", "shell_execute", "y".repeat(chars)),
        ),
    )

    @Test
    fun `history under the count cap is dropped when it exceeds the byte budget`() {
        // 40 oversized turns: far above the 8 MiB budget, far below any
        // message-count cap. The byte limit is the one that must bite.
        val history = (0 until 40).map { toolTurn("t$it", 1024 * 1024) }
        assertTrue(ResidentWindow.bytesOf(history) > HotWindow.RESIDENT_BYTES)

        val cut = ResidentWindow.byteCut(history)
        assertTrue("byte cap must drop something: $cut", cut > 0)
        val kept = history.subList(cut, history.size)
        assertTrue(
            "remaining history must fit the budget",
            ResidentWindow.bytesOf(kept) <= HotWindow.RESIDENT_BYTES,
        )
    }

    @Test
    fun `a cut never orphans a tool result from its tool use`() {
        // A self-contained tool turn is a legal boundary: it carries the
        // tool_use for the tool_result it holds, so the provider sees a
        // matched pair. Cutting here must be allowed.
        val history = mutableListOf(
            text("plain head"),
            toolTurn("a", 4096),
            toolTurn("b", 4096),
        )
        val cut = ResidentWindow.byteCut(history, budget = 1L)
        assertTrue("must cut: $cut", cut > 0)

        val kept = history.subList(cut, history.size)
        val firstParts = kept.first().contentParts
        assertTrue(
            "first retained entry must not be a bare tool result",
            firstParts.none { it is AgentContentPart.ToolResult } ||
                firstParts.any { it is AgentContentPart.ToolUse },
        )
    }

    @Test
    fun `a split tool round is never cut between use and result`() {
        val history = listOf(
            text("head"),
            splitToolUse("split-call"),
            splitToolResult("split-call", 4096),
            text("tail"),
        )
        val cut = ResidentWindow.byteCut(history, budget = 1L)
        assertTrue("must cut at least the head: $cut", cut > 0)
        val kept = history.subList(cut, history.size)
        val uses = kept.flatMap { message ->
            message.contentParts.filterIsInstance<AgentContentPart.ToolUse>().map { it.id to it.name }
        }.toSet()
        kept.flatMap { it.contentParts.filterIsInstance<AgentContentPart.ToolResult>() }.forEach { result ->
            assertTrue("result ${result.id} must retain its use", (result.id to result.name) in uses)
        }
    }

    @Test
    fun `a trailing run of bare results is not cuttable and the head is kept`() {
        // Only index 0 is a legal boundary, and cutting there drops nothing,
        // so the budget cannot be met. Returning 0 is correct: the alternative
        // is handing the provider a tool_result whose tool_use is gone.
        val history = (0 until 4).map { bareResult("t$it", 8192) }
        assertEquals(0, ResidentWindow.byteCut(history, budget = 1L))
    }

    @Test
    fun `a clean tail entry is a valid boundary after bare results`() {
        // A plain text entry after a run of bare results IS a legal boundary:
        // it carries no tool result, so the provider sees nothing orphaned.
        val history = (0 until 3).map { bareResult("t$it", 8192) } + listOf(text("tail"))
        val cut = ResidentWindow.byteCut(history, budget = 1L)
        assertEquals(3, cut)
        assertEquals(1, history.size - cut)
    }

    @Test
    fun `a turn that carries its own tool use is a legal boundary`() {
        // This is the regression behind the failing OOM case: a tool-heavy
        // history has a result in nearly every entry, so a rule that demands
        // "no tool result at the boundary" finds no legal cut anywhere and
        // the byte cap silently does nothing.
        val history = (0 until 6).map { toolTurn("t$it", 8192) }
        assertTrue(ResidentWindow.bytesOf(history) > 1L)

        val cut = ResidentWindow.byteCut(history, budget = 32L * 1024)
        assertTrue("a tool turn must be cuttable: $cut", cut > 0)
        val kept = history.subList(cut, history.size)
        assertTrue(ResidentWindow.bytesOf(kept) <= 32L * 1024)
        assertTrue(kept.isNotEmpty())
    }

    @Test
    fun `a history of bare results is kept whole rather than orphaned`() {
        // Every entry is a bare tool result, so no legal cut exists. Dropping
        // anything would hand the provider a result without its use, which it
        // rejects — worse than holding the memory for one more turn.
        val history = (0 until 6).map { bareResult("t$it", 4096) }
        assertEquals(0, ResidentWindow.byteCut(history, budget = 1L))
    }

    @Test
    fun `the count cut respects the same orphaning rule`() {
        // 10 self-contained tool turns plus a plain tail. Any of the tool
        // turns is a legal boundary because it carries its own tool use.
        val history = (0 until 10).map { toolTurn("t$it", 8) } + listOf(text("tail"))
        val cut = ResidentWindow.countCut(history, maxMessages = 4)
        assertTrue("must cut: $cut", cut > 0)
        val kept = history.subList(cut, history.size)
        assertTrue("kept=${kept.size}", kept.size <= 4)
        val first = kept.first().contentParts
        assertTrue(
            "first retained entry must not be a bare tool result",
            first.none { it is AgentContentPart.ToolResult } ||
                first.any { it is AgentContentPart.ToolUse },
        )
    }

    @Test
    fun `a history within budget is left alone`() {
        val history = listOf(text("short"), text("also short"))
        assertEquals(0, ResidentWindow.byteCut(history))
        assertEquals(0, ResidentWindow.countCut(history, maxMessages = 400))
    }

    // ─── [T-b1-bytesof-toolinput] 尺寸记账不再每次序列化 tool 输入 ──────────

    @Test
    fun `tool use input length is captured once and matches serialization`() {
        val input = JSONObject().put("cmd", "run").put("n", 42)
        val part = AgentContentPart.ToolUse("t1", "shell_execute", input)
        assertEquals(input.toString().length, part.inputJsonLength)
        // copy(input = …) builds a new instance → the cached length is recomputed.
        val bigger = JSONObject().put("cmd", "run").put("padding", "x".repeat(500))
        assertEquals(bigger.toString().length, part.copy(input = bigger).inputJsonLength)
        // bytesOf still charges 2 bytes per JSON char for the input.
        val msg = LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "",
            contentParts = listOf(part),
        )
        assertEquals(part.inputJsonLength.toLong() * 2, ResidentWindow.bytesOf(msg))
    }

    @Test
    fun `the prefix-sum byte cut returns the earliest fitting boundary`() {
        // 40 self-contained turns of ~2 MiB each. Budget for exactly the last
        // three: the cut must land on 37 and nothing earlier, i.e. it is still
        // the FIRST legal fit the old rescan-every-candidate loop computed.
        val history = (0 until 40).map { toolTurn("t$it", 1024 * 1024) }
        val threeTurns = ResidentWindow.bytesOf(history.subList(37, 40))
        assertTrue("sanity: the fixture must exceed the tiny budget", threeTurns > 1L)
        assertEquals(0, ResidentWindow.byteCut(history, budget = threeTurns * 100))
        assertEquals(37, ResidentWindow.byteCut(history, budget = threeTurns))
        assertTrue(
            "one turn earlier must still be over budget",
            ResidentWindow.bytesOf(history.subList(36, 40)) > threeTurns,
        )
    }
}
