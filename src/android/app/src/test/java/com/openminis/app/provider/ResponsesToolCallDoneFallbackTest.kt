package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-android-responses-done-without-accumulator] A Responses-API function call
 * is announced at `response.output_item.added` and finalized at
 * `response.output_item.done`. The accumulator created by the former is what the
 * latter used to `remove`, and a missing accumulator meant `continue` — the whole
 * tool call disappeared, with no log line, while `response.completed` still
 * reported finish_reason=tool_use. The agent loop then saw toolCalls empty, ended
 * the turn, and the user got a blank reply with no inline error.
 *
 * The `done` item is self-contained (id / call_id / name / arguments), so a
 * relay that skips `added` — the same class of deviation T107 already logs on the
 * delta branch — must get its tool call rebuilt from it rather than dropped.
 */
class ResponsesToolCallDoneFallbackTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    /** Streams against a provider configured for /v1/responses. */
    private fun responsesChunks(body: String): List<LLMStreamChunk> {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body),
        )
        val provider = OpenAIProvider(
            apiKey = "test-key",
            model = LLMModel.gpt4oMini,
            basePath = server.url("/v1").toString().trimEnd('/'),
            useResponsesAPI = true,
        )
        return runBlocking {
            provider.streamMessageClamped(
                messages = listOf(LLMMessage(LLMMessage.Role.USER, "hi")),
                systemPrompt = null,
                maxTokens = 256,
                temperature = null,
                imageParts = emptyList(),
                tools = emptyList(),
                thinkingLevel = ThinkingLevel.OFF,
            ).toList()
        }
    }

    @Test
    fun `function call done without an added event is rebuilt, not dropped`() {
        // The deviant relay shape: no response.output_item.added at all, only the
        // self-contained `done` item. Pre-fix this produced zero ToolCallComplete.
        val body = """
            data: {"type":"response.output_item.done","item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"shell_execute","arguments":"{\"cmd\":\"ls\"}"}}

            data: {"type":"response.completed","response":{"status":"completed","output":[{"type":"function_call"}]}}

        """.trimIndent() + "\n"

        val chunks = responsesChunks(body)

        val calls = chunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>()
        assertEquals("the done item must yield exactly one tool call, got ${calls.size}", 1, calls.size)
        assertEquals("call_1|fc_1", calls.first().id)
        assertEquals("shell_execute", calls.first().name)
        assertEquals("ls", calls.first().args.optString("cmd"))
    }

    @Test
    fun `a rebuilt function call announces ToolUseStart before ToolCallComplete`() {
        // [T-android-responses-done-without-accumulator] Rebuilding the call from
        // the `done` item is only half the job: the UI creates the tool block
        // EXCLUSIVELY in its ToolUseStart branch (ChatViewModelAgentLoopExt —
        // a ToolCallComplete for an unknown id finds `idx < 0` and updates
        // nothing). Emitting only the complete meant the tool ran with no card at
        // all and the persisted blocks disagreed with the dispatch list.
        val body = """
            data: {"type":"response.output_item.added","item":{"type":"function_call","id":"fc_2"}}

            data: {"type":"response.output_item.done","item":{"type":"function_call","id":"fc_2","call_id":"call_2","name":"read_image","arguments":"{\"path\":\"/tmp/a.png\"}"}}

            data: {"type":"response.completed","response":{"status":"completed","output":[{"type":"function_call"}]}}

        """.trimIndent() + "\n"

        val chunks = responsesChunks(body)

        val startIdx = chunks.indexOfFirst { it is LLMStreamChunk.ToolUseStart }
        val completeIdx = chunks.indexOfFirst { it is LLMStreamChunk.ToolCallComplete }
        assertTrue(
            "a tool call that was never announced must be announced before it completes " +
                "(chunks=${chunks.map { it::class.simpleName }})",
            startIdx >= 0 && completeIdx >= 0 && startIdx < completeIdx,
        )
        val start = chunks[startIdx] as LLMStreamChunk.ToolUseStart
        val complete = chunks[completeIdx] as LLMStreamChunk.ToolCallComplete
        assertEquals("the start and the complete must key on the same id", complete.id, start.id)
        assertEquals("read_image", start.name)
        assertEquals(
            "exactly one start per rebuilt call, never two",
            1,
            chunks.count { it is LLMStreamChunk.ToolUseStart },
        )
    }

    @Test
    fun `added then deltas then done still yields exactly one tool call`() {
        // Control: the well-behaved relay. Exactly one complete, and the
        // authoritative `arguments` from the done item wins over the streamed
        // buffer.
        val body = """
            data: {"type":"response.output_item.added","item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"shell_execute"}}

            data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"{\"cmd\""}

            data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":":\"ls\"}"}

            data: {"type":"response.output_item.done","item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"shell_execute","arguments":"{\"cmd\":\"ls\"}"}}

            data: {"type":"response.completed","response":{"status":"completed","output":[{"type":"function_call"}]}}

        """.trimIndent() + "\n"

        val chunks = responsesChunks(body)

        val calls = chunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>()
        assertEquals("expected exactly one tool call, got ${calls.size}", 1, calls.size)
        assertEquals("call_1|fc_1", calls.first().id)
        assertEquals("ls", calls.first().args.optString("cmd"))
        // The normal path already announced it once at `added` — the rebuild
        // backfill must not add a second start for the same call.
        assertEquals(
            "announcing twice would create the card twice (or bump the id de-duper)",
            1,
            chunks.count { it is LLMStreamChunk.ToolUseStart && it.id == "call_1|fc_1" },
        )
    }

    @Test
    fun `function call done without a name is dropped without crashing the turn`() {
        // No `name` means nothing to dispatch: dropping is the only option, but it
        // must be a logged drop and the turn must still terminate normally.
        val body = """
            data: {"type":"response.output_item.done","item":{"type":"function_call","id":"fc_9","call_id":"call_9","arguments":"{}"}}

            data: {"type":"response.completed","response":{"status":"completed"}}

        """.trimIndent() + "\n"

        val chunks = responsesChunks(body)

        assertTrue(
            "an unnamed function_call must not be emitted as a tool call",
            chunks.none { it is LLMStreamChunk.ToolCallComplete },
        )
        val finished = chunks.filterIsInstance<LLMStreamChunk.Finished>().firstOrNull()
        assertEquals("stop", finished?.stopReason)
    }
}
