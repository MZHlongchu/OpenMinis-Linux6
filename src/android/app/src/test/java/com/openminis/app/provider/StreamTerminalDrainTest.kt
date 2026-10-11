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
 * [T-openai-terminal-drain] A stream that already reached its terminal event
 * (Chat `finish_reason` / Responses `response.completed`) must stop there instead
 * of waiting for a socket close. Pre-fix the loop kept reading, the body watches
 * kept counting silence, and a relay that holds a COMPLETE stream open got its
 * turn classified as a mid-stream stall: the retry ladder stall-resumed and
 * re-ran a finished reply.
 *
 * The trailing usage block still has to be accounted for (Chat Completions sends
 * it in the chunk AFTER the `finish_reason` chunk), which is why the loop stops on
 * the relay's comment lines — the point at which nothing but a `[DONE]` we no
 * longer need can still arrive — and not on the terminal event itself.
 *
 * [T-d4-terminal-break-narrowing] One comment line is tolerated and the SECOND one ends
 * the tail.
 *
 * [T-d4-terminal-tail-structural] That rule is now the same for BOTH API shapes. It used
 * to depend on where the usage block landed ("usage arrived in a chunk AFTER the terminal
 * event" ⇒ treat the stream as Chat-shaped ⇒ one comment line ends the tail), and that
 * proxy misjudges a relay which sends `response.completed` twice (the second one carrying
 * the usage): the first keep-alive then cut off the `response.output_item.done` the relay
 * flushed behind it — the exact accident this rule exists to prevent. The criterion is
 * purely structural now — the SECOND consecutive comment line, `[DONE]`, or an empty
 * `data:` line — so a single comment line never ends the tail for either shape, and the
 * Chat case below was updated accordingly (see
 * [ResponsesTerminalTailToolCallTest] for the Responses side).
 */
class StreamTerminalDrainTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun chunks(body: String, responsesApi: Boolean): List<LLMStreamChunk> {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body),
        )
        val provider = OpenAIProvider(
            apiKey = "test-key",
            model = LLMModel.gpt4oMini,
            basePath = server.url("/v1").toString().trimEnd('/'),
            useResponsesAPI = responsesApi,
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
    fun `responses completed with usage plus two keepalive comments stops at the second`() {
        // [T-d4-terminal-break-narrowing] ONE comment line must NOT end the tail on
        // the Responses path — that is exactly what cut off the late
        // `response.output_item.done` of a function_call. The second consecutive
        // comment is the real "nothing but keep-alive is coming" signal, and this
        // test pins that the turn still does not read on past it.
        val body = """
            data: {"type":"response.output_text.delta","delta":"complete reply"}

            data: {"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":10,"output_tokens":5}}}

            : keep-alive

            : keep-alive

            data: {"type":"response.output_text.delta","delta":" MUST-NOT-BE-READ"}

        """.trimIndent() + "\n"

        val chunks = chunks(body, responsesApi = true)

        assertEquals("complete reply", textOf(chunks))
        assertEquals(
            "the completed turn must be closed exactly once",
            1,
            chunks.filterIsInstance<LLMStreamChunk.Finished>().size,
        )
        assertEquals("stop", chunks.filterIsInstance<LLMStreamChunk.Finished>().first().stopReason)
        assertEquals(
            "the trailing usage block inside response.completed must not be lost",
            1,
            chunks.filterIsInstance<LLMStreamChunk.Usage>().size,
        )
    }

    @Test
    fun `chat finish reason plus trailing usage plus two keepalive comments stops at the second`() {
        // Chat Completions with stream_options.include_usage: the usage arrives in
        // its own chunk AFTER the finish_reason chunk, so the loop may only stop
        // once both are in.
        //
        // [T-d4-terminal-tail-structural] The break is no longer conditional on where
        // the usage landed: after the terminal event and its trailing accounting it is
        // the SECOND consecutive comment line for both API shapes. One keep-alive is
        // always tolerated (see the companion test below).
        val body = """
            data: {"choices":[{"delta":{"content":"complete reply"},"index":0}]}

            data: {"choices":[{"delta":{},"finish_reason":"stop","index":0}]}

            data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5}}

            : ping

            : ping

            data: {"choices":[{"delta":{"content":" MUST-NOT-BE-READ"},"index":0}]}

        """.trimIndent() + "\n"

        val chunks = chunks(body, responsesApi = false)

        assertEquals("complete reply", textOf(chunks))
        assertEquals(
            "the finished turn must be closed exactly once",
            1,
            chunks.filterIsInstance<LLMStreamChunk.Finished>().size,
        )
        assertEquals("stop", chunks.filterIsInstance<LLMStreamChunk.Finished>().first().stopReason)
        assertEquals(
            "the trailing usage-only chunk must be accounted for before hanging up",
            1,
            chunks.filterIsInstance<LLMStreamChunk.Usage>().size,
        )
    }

    @Test
    fun `chat one keepalive after the terminal event does not cut a late data line`() {
        // [T-d4-terminal-tail-structural] The structural rule kills the old
        // Chat-strength rule's failure mode at its root: "usage arrived after the
        // terminal event" is no longer accepted as proof that the relay is past the
        // terminal event, so a single comment line cannot end the tail and a chunk the
        // relay flushes behind it is read instead of dropped.
        val body = """
            data: {"choices":[{"delta":{"content":"complete reply"},"index":0}]}

            data: {"choices":[{"delta":{},"finish_reason":"stop","index":0}]}

            data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5}}

            : ping

            data: {"choices":[{"delta":{"content":" and read"},"index":0}]}

        """.trimIndent() + "\n"

        val chunks = chunks(body, responsesApi = false)

        assertEquals(
            "one keep-alive must not end the tail — the late chunk behind it is the " +
                "whole point of the second-comment rule",
            "complete reply and read",
            textOf(chunks),
        )
        assertEquals(
            "the finished turn must still be closed exactly once",
            1,
            chunks.filterIsInstance<LLMStreamChunk.Finished>().size,
        )
    }

    @Test
    fun `a late data line after the terminal event is still read`() {
        // The stop above is deliberately narrow: `data:` lines are never cut off
        // (relays do flush a last chunk late), only the relay's keepalive comments
        // end the turn early.
        val body = """
            data: {"type":"response.output_text.delta","delta":"complete"}

            data: {"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":1,"output_tokens":1}}}

            data: {"type":"response.output_text.delta","delta":" reply"}

        """.trimIndent() + "\n"

        val chunks = chunks(body, responsesApi = true)

        assertEquals("complete reply", textOf(chunks))
    }
}
