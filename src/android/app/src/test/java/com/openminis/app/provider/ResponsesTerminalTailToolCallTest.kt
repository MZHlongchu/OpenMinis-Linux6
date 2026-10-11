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
import org.junit.Before
import org.junit.Test

/**
 * [T-d4-terminal-break-narrowing] / [T-d4-responses-done-id-drift] Responses-API
 * tool calls that reach the parser in the terminal tail.
 *
 * `response.completed` is the Responses terminal event AND it carries the usage
 * block, so "terminal event seen + usage seen" says nothing about the tail: relays
 * flush per-item events late, and a `response.output_item.done` for a function_call
 * can land after `response.completed`. The read loop used to stop at the FIRST SSE
 * comment line once both flags were set, so a single `: keep-alive` in between made
 * the whole tool call unreadable — it was dropped even though it was complete, the
 * turn could still report a tool turn (the response object's `output` list declares
 * the call), and the agent loop ended with `toolCalls` empty (blank reply, no
 * inline error). One comment line is tolerated now; the SECOND still ends the
 * tail, which StreamTerminalDrainTest pins.
 *
 * The second shape here is item_id drift: `output_item.added` announces a call
 * under one item_id and `output_item.done` reports another (same call_id). The
 * rebuild path then emitted start+complete for the done id while the accumulator
 * announced under the added id stayed in the map and was flushed as a SECOND
 * complete by the stream tail. Downstream de-dupes on the RAW id
 * (ToolCallIdDedupe / ToolReplayGuard), so `call_1|fc_A` and `call_1|fc_B` both
 * reached the dispatch list and the same logical tool call ran twice.
 *
 * [T-d4-one-card-per-call] The mirror image of that bug: a call announced TWICE (two
 * item_ids for one call_id, or the same item_id replayed) opened a SECOND card
 * downstream — ToolCallIdDedupe renames the second start to "<id>-2" — and no
 * ToolCallComplete ever matched it, so that card stayed STREAMING with its spinner
 * running forever. One logical call announces once; extra item_ids are aliases of the
 * announced identity.
 *
 * [T-d4-terminal-tail-structural] And the end-of-tail break is structural now: TWO
 * consecutive comment lines (or `[DONE]` / an empty `data:` line), never "the usage
 * block happened to arrive after the terminal event" — that proxy misjudged a relay
 * which sends `response.completed` twice, the second one carrying the usage.
 */
class ResponsesTerminalTailToolCallTest {

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

    private fun textOf(chunks: List<LLMStreamChunk>): String =
        chunks.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text }

    @Test
    fun `a keep-alive between completed and a late function_call done is tolerated`() {
        // The deviant relay shape: the whole response object (output list included)
        // arrives in `response.completed`, and the per-item streamed events trail it
        // — with a keep-alive comment in between.
        val body = """
            data: {"type":"response.completed","response":{"status":"completed","output":[{"type":"function_call"}],"usage":{"input_tokens":10,"output_tokens":5}}}

            : keep-alive

            data: {"type":"response.output_item.done","item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"shell_execute","arguments":"{\"cmd\":\"ls\"}"}}

        """.trimIndent() + "\n"

        val chunks = responsesChunks(body)

        val calls = chunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>()
        assertEquals(
            "the late done item must be read past the keep-alive comment, got " +
                "${calls.map { it.id }}",
            1,
            calls.size,
        )
        assertEquals("call_1|fc_1", calls.first().id)
        assertEquals("shell_execute", calls.first().name)
        assertEquals("ls", calls.first().args.optString("cmd"))

        // [T-android-responses-done-without-accumulator] The recovered call must
        // still be announced first, on the same id the complete uses — downstream
        // creates the tool card ONLY in its ToolUseStart branch.
        val starts = chunks.filterIsInstance<LLMStreamChunk.ToolUseStart>()
        assertEquals("exactly one card per recovered call, got ${starts.map { it.id }}", 1, starts.size)
        assertEquals(
            "the start and the complete must key on the same id",
            starts.first().id,
            calls.first().id,
        )

        assertEquals(
            "the usage block inside response.completed must still be accounted for",
            1,
            chunks.filterIsInstance<LLMStreamChunk.Usage>().size,
        )
        val finished = chunks.filterIsInstance<LLMStreamChunk.Finished>()
        assertEquals("exactly one terminal chunk, got ${finished.size}", 1, finished.size)
        assertEquals("tool_use", finished.first().stopReason)
    }

    @Test
    fun `a late done is read even when completed does not spell out its output list`() {
        // The tolerated comment line is not conditioned on the terminal event
        // declaring its tool calls: relays that only report `response.output` in the
        // item events still must not have the call cut off by a keep-alive.
        val body = """
            data: {"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":10,"output_tokens":5}}}

            : keep-alive

            data: {"type":"response.output_item.done","item":{"type":"function_call","id":"fc_9","call_id":"call_9","name":"read_image","arguments":"{\"path\":\"/tmp/a.png\"}"}}

        """.trimIndent() + "\n"

        val chunks = responsesChunks(body)

        val calls = chunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>()
        assertEquals(
            "one keep-alive comment must not end the tail, got ${calls.size} tool call(s)",
            1,
            calls.size,
        )
        assertEquals("call_9|fc_9", calls.first().id)
        assertEquals("/tmp/a.png", calls.first().args.optString("path"))
    }

    @Test
    fun `an item_id drift between added and done dispatches the call exactly once`() {
        // added(id=fc_A) … done(id=fc_B), same call_id. Pre-fix the rebuild emitted
        // start+complete for fc_B and the stream tail flushed fc_A as a second
        // complete: two blocks, two dispatch entries, one tool execution too many.
        val body = """
            data: {"type":"response.output_item.added","item":{"type":"function_call","id":"fc_A","call_id":"call_1","name":"shell_execute"}}

            data: {"type":"response.function_call_arguments.delta","item_id":"fc_A","delta":"{\"cmd\":\"ls\"}"}

            data: {"type":"response.output_item.done","item":{"type":"function_call","id":"fc_B","call_id":"call_1","name":"shell_execute","arguments":"{\"cmd\":\"ls\"}"}}

            data: {"type":"response.completed","response":{"status":"completed","output":[{"type":"function_call"}]}}

        """.trimIndent() + "\n"

        val chunks = responsesChunks(body)

        val calls = chunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>()
        assertEquals(
            "the same logical call must complete exactly once, got ${calls.map { it.id }}",
            1,
            calls.size,
        )
        val starts = chunks.filterIsInstance<LLMStreamChunk.ToolUseStart>()
        assertEquals("one announced call means one card, got ${starts.map { it.id }}", 1, starts.size)
        assertEquals(
            "the complete must key on the id the call was announced with — that is the " +
                "id the UI block, the argument deltas and the next request's item_id use",
            starts.first().id,
            calls.first().id,
        )
        assertEquals("call_1|fc_A", calls.first().id)
        assertEquals("shell_execute", calls.first().name)
        assertEquals("ls", calls.first().args.optString("cmd"))
    }

    @Test
    fun `an empty data payload after the terminal event ends the tail`() {
        // [T-d4-terminal-break-narrowing] The third end-of-tail signal: a `data:`
        // line with no payload carries no event at all (JSONObject rejects the empty
        // string, so it was only ever a parse warning), which after the terminal
        // event and its usage is the relay saying it has nothing left to send.
        val body = """
            data: {"type":"response.output_text.delta","delta":"complete reply"}

            data: {"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":10,"output_tokens":5}}}

            data:

            data: {"type":"response.output_text.delta","delta":" MUST-NOT-BE-READ"}

        """.trimIndent() + "\n"

        val chunks = responsesChunks(body)

        assertEquals("complete reply", textOf(chunks))
        assertEquals(
            "exactly one terminal chunk",
            1,
            chunks.filterIsInstance<LLMStreamChunk.Finished>().size,
        )
    }

    @Test
    fun `an item_id drift with a leftover accumulator after completion flushes once`() {
        // Interaction case: the call is announced under fc_X, the terminal event
        // arrives mid-call, one keep-alive comment follows, and the done item reports
        // fc_Y with the authoritative arguments. Two things have to hold at once —
        // the tolerated comment must not swallow the done item, and the residual
        // fc_X accumulator must be consumed there instead of being re-emitted by the
        // stream tail (which would dispatch the call twice, the second time with only
        // the partial streamed arguments).
        val body = """
            data: {"type":"response.output_item.added","item":{"type":"function_call","id":"fc_X","call_id":"call_7","name":"file_read"}}

            data: {"type":"response.function_call_arguments.delta","item_id":"fc_X","delta":"{\"path\":\"/tmp/b."}

            data: {"type":"response.completed","response":{"status":"completed","output":[{"type":"function_call"}],"usage":{"input_tokens":3,"output_tokens":4}}}

            : keep-alive

            data: {"type":"response.output_item.done","item":{"type":"function_call","id":"fc_Y","call_id":"call_7","name":"file_read","arguments":"{\"path\":\"/tmp/b.txt\"}"}}

        """.trimIndent() + "\n"

        val chunks = responsesChunks(body)

        val calls = chunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>()
        assertEquals(
            "one dispatch only, got ${calls.map { it.id }}",
            1,
            calls.size,
        )
        assertEquals("call_7|fc_X", calls.first().id)
        assertEquals(
            "the done item's authoritative arguments must win over the partial stream",
            "/tmp/b.txt",
            calls.first().args.optString("path"),
        )
        assertEquals(
            "the announced card must not be left without a matching complete",
            1,
            chunks.count { it is LLMStreamChunk.ToolUseStart && it.id == calls.first().id },
        )
    }

    @Test
    fun `a second completed carrying the usage does not cut a done behind one keepalive`() {
        // [T-d4-terminal-tail-structural] The shape that broke the old criterion: the
        // relay announces the terminal event TWICE, and only the second
        // `response.completed` carries the usage block. "Usage came in a later event than
        // the terminal one" then looked like the Chat-Completions shape (where the usage
        // chunk legitimately trails `finish_reason`), and the FIRST keep-alive ended the
        // tail — dropping the `response.output_item.done` flushed behind it. The
        // criterion is structural now, so one comment line is tolerated no matter where
        // the usage landed.
        val body = """
            data: {"type":"response.completed","response":{"status":"completed"}}

            : keep-alive

            data: {"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":10,"output_tokens":5}}}

            : keep-alive

            data: {"type":"response.output_item.done","item":{"type":"function_call","id":"fc_d","call_id":"call_d","name":"shell_execute","arguments":"{\"cmd\":\"ls\"}"}}

        """.trimIndent() + "\n"

        val chunks = responsesChunks(body)

        val calls = chunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>()
        assertEquals(
            "a single keep-alive must not end the tail in this shape either, got " +
                "${calls.map { it.id }}",
            1,
            calls.size,
        )
        assertEquals("call_d|fc_d", calls.first().id)
        assertEquals("ls", calls.first().args.optString("cmd"))
        assertEquals(
            "the usage in the second completed must be accounted for",
            1,
            chunks.filterIsInstance<LLMStreamChunk.Usage>().size,
        )
        assertEquals(
            "exactly one terminal chunk",
            1,
            chunks.filterIsInstance<LLMStreamChunk.Finished>().size,
        )
    }

    @Test
    fun `the same call_id announced under two item_ids opens one card and completes once`() {
        // [T-d4-one-card-per-call] `output_item.added` for the same call_id twice (two
        // item_ids). Downstream creates a card ONLY in its ToolUseStart branch and
        // ToolCallIdDedupe renames the second announcement to "<id>-2", so a second start
        // opened a second card — which no ToolCallComplete ever matched (the call
        // completes once, under the announced identity) and which therefore stayed
        // STREAMING forever with its spinner running. One logical call announces once.
        val body = """
            data: {"type":"response.output_item.added","item":{"type":"function_call","id":"fc_A","call_id":"call_1","name":"shell_execute"}}

            data: {"type":"response.output_item.added","item":{"type":"function_call","id":"fc_B","call_id":"call_1","name":"shell_execute"}}

            data: {"type":"response.function_call_arguments.delta","item_id":"fc_B","delta":"{\"cmd\":\"ls\"}"}

            data: {"type":"response.output_item.done","item":{"type":"function_call","id":"fc_B","call_id":"call_1","name":"shell_execute"}}

            data: {"type":"response.completed","response":{"status":"completed","output":[{"type":"function_call"}]}}

        """.trimIndent() + "\n"

        val chunks = responsesChunks(body)

        val starts = chunks.filterIsInstance<LLMStreamChunk.ToolUseStart>()
        assertEquals(
            "one logical call means exactly one card (a second start becomes '<id>-2' " +
                "downstream and never gets completed), got ${starts.map { it.id }}",
            1,
            starts.size,
        )
        assertEquals("call_1|fc_A", starts.first().id)
        val calls = chunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>()
        assertEquals("one dispatch only, got ${calls.map { it.id }}", 1, calls.size)
        assertEquals(
            "the complete must key on the announced identity — the id the one card uses",
            starts.first().id,
            calls.first().id,
        )
        assertEquals(
            "the arguments streamed under the ALIAS item_id must still land on the " +
                "announced call (the done item carries none here)",
            "ls",
            calls.first().args.optString("cmd"),
        )
    }

    @Test
    fun `a repeated added for the same item_id opens one card and completes once`() {
        // [T-d4-one-card-per-call] A relay that replays `output_item.added` with the SAME
        // item_id used to overwrite the accumulator and emit a second ToolUseStart on the
        // same raw id, which downstream renamed to "<id>-2" → a second card that never
        // received a complete → spinner forever.
        val body = """
            data: {"type":"response.output_item.added","item":{"type":"function_call","id":"fc_A","call_id":"call_1","name":"shell_execute"}}

            data: {"type":"response.output_item.added","item":{"type":"function_call","id":"fc_A","call_id":"call_1","name":"shell_execute"}}

            data: {"type":"response.function_call_arguments.delta","item_id":"fc_A","delta":"{\"cmd\":\"ls\"}"}

            data: {"type":"response.output_item.done","item":{"type":"function_call","id":"fc_A","call_id":"call_1","name":"shell_execute","arguments":"{\"cmd\":\"ls\"}"}}

            data: {"type":"response.completed","response":{"status":"completed","output":[{"type":"function_call"}]}}

        """.trimIndent() + "\n"

        val chunks = responsesChunks(body)

        val starts = chunks.filterIsInstance<LLMStreamChunk.ToolUseStart>()
        assertEquals(
            "a replayed announcement must not open a second card, got ${starts.map { it.id }}",
            1,
            starts.size,
        )
        val calls = chunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>()
        assertEquals("one dispatch only, got ${calls.map { it.id }}", 1, calls.size)
        assertEquals("call_1|fc_A", calls.first().id)
        assertEquals("ls", calls.first().args.optString("cmd"))
    }

    @Test
    fun `a call announced twice is not re-flushed by the stream tail either`() {
        // The orphan-card shape without the `done`: the relay announces the same call
        // under two item_ids and the stream then closes mid-call. The tail flush must
        // dispatch the call ONCE (it iterates the announced identities only) — an alias
        // key flushed as a second complete would carry a different raw id and slip past
        // the downstream replay guard into a second execution.
        val body = """
            data: {"type":"response.output_item.added","item":{"type":"function_call","id":"fc_A","call_id":"call_1","name":"shell_execute"}}

            data: {"type":"response.output_item.added","item":{"type":"function_call","id":"fc_B","call_id":"call_1","name":"shell_execute"}}

            data: {"type":"response.function_call_arguments.delta","item_id":"fc_B","delta":"{\"cmd\":\"ls\"}"}

        """.trimIndent() + "\n"

        val chunks = responsesChunks(body)

        val calls = chunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>()
        assertEquals(
            "the tail flush must dispatch once, got ${calls.map { it.id }}",
            1,
            calls.size,
        )
        assertEquals("call_1|fc_A", calls.first().id)
        assertEquals(
            "one card for the flushed call as well",
            1,
            chunks.count { it is LLMStreamChunk.ToolUseStart && it.id == calls.first().id },
        )
    }
}
