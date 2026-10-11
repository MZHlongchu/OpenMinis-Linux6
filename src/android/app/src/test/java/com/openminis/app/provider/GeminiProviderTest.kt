package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.provider.gemini.GeminiProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeminiProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: GeminiProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = GeminiProvider(
            apiKey = "test-key",
            model = LLMModel.gemini25Flash,
            basePath = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // -- sendMessage response parsing --

    @Test
    fun `sendMessage parses Gemini response`() = runBlocking {
        val responseBody = """
        {
            "candidates": [{
                "content": {
                    "parts": [{"text": "Hello from Gemini!"}],
                    "role": "model"
                },
                "finishReason": "STOP"
            }],
            "usageMetadata": {"promptTokenCount": 8, "candidatesTokenCount": 4}
        }
        """.trimIndent()

        server.enqueue(MockResponse().setBody(responseBody))

        val response = provider.sendMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "Hi")),
            null, 1024,
        )

        assertEquals("Hello from Gemini!", response.text)
        assertEquals("end_turn", response.stopReason)
        assertEquals(8, response.usage?.inputTokens)
        assertEquals(4, response.usage?.outputTokens)
    }

    @Test
    fun `sendMessage maps STOP to end_turn`() = runBlocking {
        val responseBody = """
        {
            "candidates": [{
                "content": {"parts": [{"text": "done"}]},
                "finishReason": "STOP"
            }]
        }
        """.trimIndent()

        server.enqueue(MockResponse().setBody(responseBody))
        val response = provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 100)
        assertEquals("end_turn", response.stopReason)
    }

    @Test
    fun `sendMessage folds thinking tokens into output`() = runBlocking {
        val responseBody = """
        {
            "candidates": [{
                "content": {"parts": [{"text": "done"}], "role": "model"},
                "finishReason": "STOP"
            }],
            "usageMetadata": {
                "promptTokenCount": 8,
                "candidatesTokenCount": 4,
                "thoughtsTokenCount": 6,
                "cachedContentTokenCount": 3
            }
        }
        """.trimIndent()

        server.enqueue(MockResponse().setBody(responseBody))

        val response = provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024)
        // [T-gemini-usage-thoughts] 思考 token 单独计数，必须并入输出用量。
        assertEquals(10, response.usage?.outputTokens)
        // [T-b15-usage-fresh-input] promptTokenCount(8) 已含缓存(3)，inputTokens 只能
        // 报 fresh 部分（8-3=5）——写回 8 会让下游按 fresh+cache 不相交求和/求率时
        // 重复计入缓存前缀（命中率 3/8=37.5% 被压到 3/11=27%）。
        assertEquals(5, response.usage?.inputTokens)
        assertEquals(3, response.usage?.cacheReadInputTokens)
        // 上下文占用取完整 prompt —— dynamicMaxTokens / ContextPolicy 依赖它。
        assertEquals(8, response.usage?.latestContextTokens)
    }

    @Test
    fun `sendMessage does not double count a fully cached prompt`() = runBlocking {
        // 100% 命中：fresh 必须是 0，下游命中率才不会显示成 ~50%。
        val responseBody = """
        {
            "candidates": [{
                "content": {"parts": [{"text": "cached"}], "role": "model"},
                "finishReason": "STOP"
            }],
            "usageMetadata": {
                "promptTokenCount": 1200,
                "candidatesTokenCount": 4,
                "cachedContentTokenCount": 1200
            }
        }
        """.trimIndent()

        server.enqueue(MockResponse().setBody(responseBody))

        val usage = provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024).usage!!
        assertEquals("a fully cached prompt carries no fresh input", 0, usage.inputTokens)
        assertEquals(1200, usage.cacheReadInputTokens)
        assertEquals(1200, usage.latestContextTokens)
        // 下游 ChatViewModelAgentLoopExt 的 [T-cache-hit-rate] 就是这个比值。
        val total = usage.inputTokens + (usage.cacheReadInputTokens ?: 0)
        assertEquals(1.0, (usage.cacheReadInputTokens ?: 0).toDouble() / total, 0.0001)
    }

    @Test
    fun `sendMessage keeps fresh input non-negative when a relay over-reports the cache`() = runBlocking {
        // 网关把 cachedContentTokenCount 报得比 promptTokenCount 还大（实测有的
        // 中继会这样）—— 减出负数会把 inputTokens 变成负值，污染所有用量/预算
        // 记账，所以负数时回退成完整 prompt，与 OpenAIProviderUsage 的守卫一致。
        val responseBody = """
        {
            "candidates": [{
                "content": {"parts": [{"text": "odd"}], "role": "model"},
                "finishReason": "STOP"
            }],
            "usageMetadata": {
                "promptTokenCount": 5,
                "candidatesTokenCount": 1,
                "cachedContentTokenCount": 9
            }
        }
        """.trimIndent()

        server.enqueue(MockResponse().setBody(responseBody))

        val usage = provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024).usage!!
        assertEquals(5, usage.inputTokens)
        assertEquals(5, usage.latestContextTokens)
    }

    @Test
    fun `sendMessage maps MAX_TOKENS to max_tokens`() = runBlocking {
        val responseBody = """
        {
            "candidates": [{
                "content": {"parts": [{"text": "truncated"}]},
                "finishReason": "MAX_TOKENS"
            }]
        }
        """.trimIndent()

        server.enqueue(MockResponse().setBody(responseBody))
        val response = provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 10)
        assertEquals("max_tokens", response.stopReason)
    }

    @Test
    fun `sendMessage defaults to end_turn when no finishReason`() = runBlocking {
        val responseBody = """
        {
            "candidates": [{
                "content": {"parts": [{"text": "ok"}]}
            }]
        }
        """.trimIndent()

        server.enqueue(MockResponse().setBody(responseBody))
        val response = provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 100)
        assertEquals("end_turn", response.stopReason)
    }

    @Test
    fun `sendMessage parses multiple text parts`() = runBlocking {
        val responseBody = """
        {
            "candidates": [{
                "content": {
                    "parts": [{"text": "Hello "}, {"text": "world!"}]
                }
            }]
        }
        """.trimIndent()

        server.enqueue(MockResponse().setBody(responseBody))
        val response = provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 100)
        assertEquals("Hello world!", response.text)
    }

    // -- Request construction --

    @Test
    fun `sendMessage includes API key in URL`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""))

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)

        val request = server.takeRequest()
        assertTrue(request.path!!.contains("key=test-key"))
        assertTrue(request.path!!.contains("generateContent"))
    }

    @Test
    fun `sendMessage maps roles correctly`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""))

        provider.sendMessage(
            listOf(
                LLMMessage(LLMMessage.Role.USER, "hello"),
                LLMMessage(LLMMessage.Role.ASSISTANT, "hi"),
                LLMMessage(LLMMessage.Role.USER, "how are you"),
            ),
            null, 100,
        )

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val contents = body.getJSONArray("contents")
        assertEquals("user", contents.getJSONObject(0).getString("role"))
        assertEquals("model", contents.getJSONObject(1).getString("role"))
        assertEquals("user", contents.getJSONObject(2).getString("role"))
    }

    @Test
    fun `sendMessage includes system instruction`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""))

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), "Be concise", 100)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val sysInstruction = body.getJSONObject("systemInstruction")
        val text = sysInstruction.getJSONArray("parts").getJSONObject(0).getString("text")
        assertEquals("Be concise", text)
    }

    @Test
    fun `sendMessage omits system instruction when null`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""))

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertTrue(!body.has("systemInstruction"))
    }

    @Test
    fun `sendMessage includes temperature in generationConfig`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""))

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100, temperature = 0.9)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val config = body.getJSONObject("generationConfig")
        assertEquals(0.9, config.getDouble("temperature"), 0.001)
        assertEquals(100, config.getInt("maxOutputTokens"))
    }

    @Test
    fun `sendMessage omits temperature when null`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""))

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100, temperature = null)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val config = body.getJSONObject("generationConfig")
        assertTrue(!config.has("temperature"))
    }

    // -- [T-gemini-empty-part-oneof-400] empty text part never shipped --

    // Gemini rejects {"text": ""} with a 400: contents[N].parts[M].data:
    // required oneof field 'data' must have one initialized field. An empty
    // user/model text must be substituted (never an empty string, never an
    // empty parts[]). Regression for the Gemini 3.5 Flash "tool format errors".
    @Test
    fun `sendMessage never sends empty text part for empty content`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""))

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "")), null, 100)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val parts = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertTrue("parts must not be empty", parts.length() > 0)
        val text = parts.getJSONObject(0).getString("text")
        assertTrue("text part must never be empty (empty {\"text\":\"\"} → Gemini oneof 400)", text.isNotEmpty())
    }

    @Test
    fun `sendMessage keeps real content intact`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""))

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "hello world")), null, 100)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val text = body.getJSONArray("contents").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(0).getString("text")
        assertEquals("hello world", text)
    }

    // -- Streaming --

    @Test
    fun `streamMessage parses SSE chunks`() = runBlocking {
        val sseBody = buildString {
            appendLine("""data: {"candidates":[{"content":{"parts":[{"text":"Hello"}]}}]}""")
            appendLine()
            appendLine("""data: {"candidates":[{"content":{"parts":[{"text":" world"}]}}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":2}}""")
            appendLine()
        }

        server.enqueue(
            MockResponse()
                .setBody(sseBody)
                .setHeader("Content-Type", "text/event-stream")
        )

        val chunks = provider.streamMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "Hi")),
            null, 1024,
        ).toList()

        assertTrue(chunks.any { it is LLMStreamChunk.Started })
        val texts = chunks.filterIsInstance<LLMStreamChunk.Text>()
        assertEquals("Hello", texts[0].text)
        assertEquals(" world", texts[1].text)
        assertTrue(chunks.any { it is LLMStreamChunk.Finished })
    }

    @Test
    fun `streamMessage extracts finishReason from SSE`() = runBlocking {
        val sseBody = buildString {
            appendLine("""data: {"candidates":[{"content":{"parts":[{"text":"Hi"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":1}}""")
            appendLine()
        }

        server.enqueue(MockResponse().setBody(sseBody).setHeader("Content-Type", "text/event-stream"))

        val chunks = provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024).toList()

        val finished = chunks.filterIsInstance<LLMStreamChunk.Finished>()
        assertEquals(1, finished.size)
        assertEquals("end_turn", finished[0].stopReason)
    }

    @Test
    fun `streamMessage maps MAX_TOKENS finishReason`() = runBlocking {
        val sseBody = buildString {
            appendLine("""data: {"candidates":[{"content":{"parts":[{"text":"truncated"}]},"finishReason":"MAX_TOKENS"}]}""")
            appendLine()
        }

        server.enqueue(MockResponse().setBody(sseBody).setHeader("Content-Type", "text/event-stream"))

        val chunks = provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 10).toList()

        val finished = chunks.filterIsInstance<LLMStreamChunk.Finished>()
        assertEquals(1, finished.size)
        assertEquals("max_tokens", finished[0].stopReason)
    }

    @Test
    fun `streamMessage defaults to end_turn when no finishReason in SSE`() = runBlocking {
        val sseBody = buildString {
            appendLine("""data: {"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}""")
            appendLine()
        }

        server.enqueue(MockResponse().setBody(sseBody).setHeader("Content-Type", "text/event-stream"))

        val chunks = provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024).toList()

        val finished = chunks.filterIsInstance<LLMStreamChunk.Finished>()
        assertEquals(1, finished.size)
        assertEquals("end_turn", finished[0].stopReason)
    }

    @Test
    fun `streamMessage includes temperature in request`() = runBlocking {
        val sseBody = buildString {
            appendLine("""data: {"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}""")
            appendLine()
        }
        server.enqueue(MockResponse().setBody(sseBody).setHeader("Content-Type", "text/event-stream"))

        provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024, temperature = 0.3).toList()

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals(0.3, body.getJSONObject("generationConfig").getDouble("temperature"), 0.001)
        assertTrue(request.path!!.contains("streamGenerateContent"))
        assertTrue(request.path!!.contains("alt=sse"))
    }

    @Test
    fun `streamMessage parses usage metadata`() = runBlocking {
        val sseBody = buildString {
            appendLine("""data: {"candidates":[{"content":{"parts":[{"text":"Hi"}]}}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":3}}""")
            appendLine()
        }

        server.enqueue(MockResponse().setBody(sseBody).setHeader("Content-Type", "text/event-stream"))

        val chunks = provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024).toList()

        val usageChunks = chunks.filterIsInstance<LLMStreamChunk.Usage>()
        assertEquals(1, usageChunks.size)
        // 无缓存字段 → 与修复前逐字节一致（fresh 回退成完整 prompt）。
        assertEquals(10, usageChunks[0].usage.inputTokens)
        assertEquals(10, usageChunks[0].usage.latestContextTokens)
        assertEquals(3, usageChunks[0].usage.outputTokens)
    }

    // -- Error handling --

    @Test(expected = LLMError.InvalidApiKey::class)
    fun `sendMessage throws on 401`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)
        Unit
    }

    @Test(expected = LLMError.InvalidApiKey::class)
    fun `sendMessage throws on 403`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))
        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)
        Unit
    }

    @Test(expected = LLMError.RateLimited::class)
    fun `sendMessage throws RateLimited on 429`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("Rate limited"))
        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)
        Unit
    }

    @Test
    fun `sendMessage throws ProviderError on 429 with no_available_providers`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("no_available_providers"))
        try {
            provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)
        } catch (e: LLMError.ProviderError) {
            assertTrue(e.detail.contains("[429]"))
            return@runBlocking
        }
        throw AssertionError("Expected ProviderError")
    }

    @Test
    fun `sendMessage throws TransientError with message on 500`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        try {
            provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)
        } catch (e: LLMError.TransientError) {
            assertTrue(e.message!!.contains("500"))
            return@runBlocking
        }
        throw AssertionError("Expected TransientError")
    }

    // -- Provider metadata --

    @Test
    fun `provider name is Google`() {
        assertEquals("Google", provider.name)
    }
}
