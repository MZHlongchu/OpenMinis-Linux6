package com.openminis.app.provider.openai

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-android-sendmessage-stream-true] `sendMessageClamped` 是"非流式入口"，但它的
 * 契约是"内部始终发流式请求、再把 delta 拼回单个 LLMResponse"：
 *  - Codex/Responses 后端对 `stream=false` 直接 400 "Stream must be set to true"
 *    （同一后端的图像体硬编码 stream=true 可作旁证）；
 *  - Responses 路径没有 Chat Completions 那条"非 SSE 单 JSON"兜底，中转真返回
 *    JSON 时整条流静默产出空回复，调用方（minions-model-use 等）把空文本当成功。
 * 回归 2f45981d 同时引入了这两个问题，本文件把两半都钉住。
 */
class SendMessageClampedStreamingTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun chatProvider() = OpenAIProvider(
        apiKey = "test-key",
        model = LLMModel.gpt4oMini,
        basePath = server.url("/").toString().trimEnd('/'),
    )

    private fun responsesProvider() = OpenAIProvider(
        apiKey = "test-key",
        model = LLMModel.gpt4oMini,
        basePath = server.url("/").toString().trimEnd('/'),
        useResponsesAPI = true,
    )

    private fun call(provider: OpenAIProvider) = runBlocking {
        provider.sendMessageClamped(
            messages = listOf(LLMMessage(LLMMessage.Role.USER, "hi")),
            systemPrompt = null,
            maxTokens = 1024,
            temperature = null,
            imageParts = emptyList(),
            tools = emptyList(),
            thinkingLevel = ThinkingLevel.OFF,
        )
    }

    @Test
    fun `sendMessageClamped asks for a stream and reassembles the deltas`() {
        val sse = buildString {
            appendLine("data: {\"choices\":[{\"delta\":{\"content\":\"he\"},\"finish_reason\":null}]}")
            appendLine()
            appendLine("data: {\"choices\":[{\"delta\":{\"content\":\"llo\"},\"finish_reason\":null}]}")
            appendLine()
            appendLine("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}")
            appendLine()
            appendLine("data: [DONE]")
            appendLine()
        }
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))

        val response = call(chatProvider())

        assertEquals("hello", response.text)
        assertEquals("stop", response.stopReason)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue("非流式入口内部必须是流式请求: $body", body.getBoolean("stream"))
        assertTrue(body.getJSONObject("stream_options").getBoolean("include_usage"))
    }

    /**
     * 中转无视 `stream=true` 直接回单个 JSON（Responses 形态）时，旧实现返回
     * `LLMResponse("", null, null, [])` 且 RC=0 —— 静默空回复。现在必须硬失败，
     * 让调用方的重试/错误分支接管。
     */
    @Test(expected = LLMError.TransientError::class)
    fun `responses path fails loudly when the relay answers with plain json`() = runBlocking {
        repeat(2) {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"output":[],"status":"completed"}"""),
            )
        }
        call(responsesProvider())
        Unit
    }
}
