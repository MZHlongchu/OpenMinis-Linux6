package com.openminis.app.provider

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import com.openminis.app.provider.openai.ResponsesToolPairing
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-responses-orphan-tool-output] + [T-android-responses-tool-id-normalize]
 * Ports of iOS bc142f750 / ffbed35ca.
 *
 * Field report (iOS): every send failed with
 *   No tool call found for function call output with call_id call_… .
 * and kept failing on every retry and fallback model until the chat was
 * cleared, because the Responses request is rebuilt from the same history each
 * time and nothing checked its tool pairing.
 *
 * The wire tests drive the real OpenAIProvider.buildResponsesAPIBody.
 */
class ResponsesToolPairingTest {

    private val model = LLMModel(id = "gpt-5", displayName = "GPT-5", provider = "test")

    private fun provider() = OpenAIProvider(apiKey = "k", model = model,
        basePath = "https://example.invalid/v1", useResponsesAPI = true)

    private fun use(id: String, name: String = "shell_execute") =
        AgentContentPart.ToolUse(id = id, name = name, input = JSONObject())

    private fun res(id: String, name: String = "shell_execute", content: String = "ok $id") =
        AgentContentPart.ToolResult(id = id, name = name, content = content)

    private fun assistant(vararg parts: AgentContentPart) =
        LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "", contentParts = parts.toList())

    private fun results(vararg parts: AgentContentPart) =
        LLMMessage(role = LLMMessage.Role.USER, content = "", contentParts = parts.toList())

    private fun user(text: String) = LLMMessage(LLMMessage.Role.USER, text)

    /** One token per input item: fc:<call>, out:<call>, or the item's role. */
    private fun wire(messages: List<LLMMessage>): List<String> {
        val input = provider().buildResponsesAPIBody(messages = messages, systemPrompt = null,
            maxTokens = 1024, stream = false).getJSONArray("input")
        return tokens(input)
    }

    private fun tokens(input: JSONArray): List<String> = (0 until input.length()).map { i ->
        val item = input.getJSONObject(i)
        when (item.optString("type")) {
            "function_call" -> "fc:" + item.getString("call_id")
            "function_call_output" -> "out:" + item.getString("call_id")
            else -> item.optString("role")
        }
    }

    private fun outputs(messages: List<LLMMessage>): List<JSONObject> {
        val input = provider().buildResponsesAPIBody(messages = messages, systemPrompt = null,
            maxTokens = 1024, stream = false).getJSONArray("input")
        return (0 until input.length()).map { input.getJSONObject(it) }
            .filter { it.optString("type") == "function_call_output" }
    }

    // ── on the wire ───────────────────────────────────────────────────────

    @Test
    fun `a stranded tool result never reaches the wire`() {
        // The reported shape: a result whose call is gone from the history.
        val seq = wire(listOf(
            user("hi"),
            results(res("call_gone|fc_gone")),
            user("next"),
        ))
        assertFalse("orphan output sent: $seq", seq.any { it.startsWith("out:") })
    }

    @Test
    fun `a call answered twice is sent with its first output only`() {
        val msgs = listOf(
            user("hi"),
            assistant(use("call_a|fc_a")),
            results(res("call_a|fc_a", content = "first")),
            results(res("call_a", content = "second")),
            user("next"),
        )
        val outs = outputs(msgs)
        assertEquals(1, outs.size)
        assertEquals("first", outs.single().getString("output"))
    }

    @Test
    fun `an unanswered call mid-history gets a placeholder after the whole call run`() {
        // Two parallel calls; only the second was answered, then the chat went on.
        val seq = wire(listOf(
            user("hi"),
            assistant(use("call_a|fc_a"), use("call_b|fc_b")),
            results(res("call_b|fc_b")),
            user("next"),
        ))
        assertEquals(listOf("user", "fc:call_a", "fc:call_b", "out:call_a", "out:call_b", "user"), seq)
    }

    @Test
    fun `the trailing call run is left unanswered`() {
        // The request that asks the model to continue after it called tools
        // but before results exist ends on the calls; no fake failure.
        val seq = wire(listOf(user("hi"), assistant(use("call_a|fc_a"), use("call_b|fc_b"))))
        assertEquals(listOf("user", "fc:call_a", "fc:call_b"), seq)
    }

    @Test
    fun `mixed id forms still pair on the wire`() {
        // Call stored combined, result stored with the bare call id (a model
        // switch or a relay): both are the same call on the wire.
        val seq = wire(listOf(user("hi"), assistant(use("call_a|fc_a")), results(res("call_a")), user("next")))
        assertEquals(listOf("user", "fc:call_a", "out:call_a", "user"), seq)
    }

    @Test
    fun `a well-formed history goes out unchanged`() {
        val seq = wire(listOf(
            user("hi"),
            assistant(use("call_a|fc_a"), use("call_b|fc_b")),
            results(res("call_a|fc_a"), res("call_b|fc_b")),
            user("next"),
        ))
        assertEquals(listOf("user", "fc:call_a", "fc:call_b", "out:call_a", "out:call_b", "user"), seq)
    }

    // ── the sanitizer on its own ──────────────────────────────────────────

    private fun fc(id: String) = JSONObject().put("type", "function_call").put("call_id", id).put("name", "t")
    private fun out(id: String, text: String = "ok") =
        JSONObject().put("type", "function_call_output").put("call_id", id).put("output", text)
    private fun msg(role: String) = JSONObject().put("role", role).put("content", "x")
    private fun arr(vararg items: JSONObject) = JSONArray().apply { items.forEach { put(it) } }

    @Test
    fun `nothing to repair returns the same array`() {
        val input = arr(msg("user"), fc("a"), out("a"), msg("user"))
        val r = ResponsesToolPairing.sanitize(input)
        assertSame(input, r.items)
        assertFalse(r.changed)
    }

    @Test
    fun `a placeholder lands before the run's real output, keeping outputs contiguous`() {
        val r = ResponsesToolPairing.sanitize(arr(fc("a"), fc("b"), out("b"), msg("user")))
        assertEquals(listOf("fc:a", "fc:b", "out:a", "out:b", "user"), tokens(r.items))
        assertEquals(listOf("a"), r.placeholderCalls)
        assertEquals(ResponsesToolPairing.PLACEHOLDER_OUTPUT,
            r.items.getJSONObject(2).getString("output"))
    }

    @Test
    fun `reasoning before the trailing calls keeps them exempt`() {
        val reasoning = JSONObject().put("type", "reasoning")
        val r = ResponsesToolPairing.sanitize(arr(msg("user"), reasoning, fc("a")))
        assertFalse(r.changed)
    }

    @Test
    fun `distinct parallel outputs are untouched while a duplicate is dropped`() {
        val r = ResponsesToolPairing.sanitize(arr(fc("a"), fc("b"), out("a", "1"), out("b", "2"), out("a", "3"), msg("user")))
        assertEquals(listOf("fc:a", "fc:b", "out:a", "out:b", "user"), tokens(r.items))
        assertEquals(listOf("a"), r.droppedDuplicateOutputs)
        assertEquals("1", r.items.getJSONObject(2).getString("output"))
    }

    @Test
    fun `an orphan output is dropped and reported`() {
        val r = ResponsesToolPairing.sanitize(arr(msg("user"), out("gone"), msg("user")))
        assertEquals(listOf("user", "user"), tokens(r.items))
        assertEquals(listOf("gone"), r.droppedOrphanOutputs)
    }

    // ── the history layer uses the same identity ─────────────────────────

    @Test
    fun `pairing key is the call id half, identity for ids without a bar`() {
        assertEquals("call_a", ToolPairing.key("call_a|fc_a"))
        assertEquals("call_a", ToolPairing.key("call_a"))
        assertEquals("toolu_01", ToolPairing.key("toolu_01"))
    }

    /**
     * [T-merge-upstream-1-14-scan-surface] 三处扫描面在 1.14 之后过期了，断言与
     * 语义一字未改，只换扫描面/锚点：
     *
     *  1. Responses 请求体的构建搬进了同包的 OpenAIRequestBodies.kt（上游如此，
     *     本移植同理），`ResponsesToolPairing.sanitize(input)` 现在在那里 ——
     *     还是"构建器必须过共享检查"这一条。
     *  2. ChatViewModel 被拆成几十个 `ChatViewModel*.kt`：`dropOrphanedToolParts`
     *     现在是 `internal fun dropOrphanedToolParts(`（无 receiver，落在
     *     ChatViewModelHistoryExt.kt），`sanitizeAgentHistory` 是
     *     `internal fun ChatViewModel.sanitizeAgentHistory()`（落在
     *     ChatViewModelSanitizeExt.kt）。上游的 `private fun …` 前缀一个都扫不到，
     *     而 `substringAfter` 找不到分隔符时**原样返回整段源码** —— 那两条断言会
     *     静默退化成"整类源码包含某字符串"。改为按声明名取函数体窗口。
     *  3. 有序配对修复的纯半边搬进了 :harness 模块
     *     （harness/agent/ToolRoundPairing.kt，[T-loop-context-cut]），所以"身份
     *     只有一份"要连 harness 一起看：app 侧把 ToolPairing.key 注入进去，
     *     harness 里不许再留一份裸 id 比较。
     */
    @Test
    fun `the builder and both history repairs go through the shared checks`() {
        val requestBodies = ProductionSources.read("provider/openai/OpenAIRequestBodies.kt")
        assertTrue(requestBodies.contains("ResponsesToolPairing.sanitize(input)"))
        val vm = ProductionSources.readClassSurface("ui/chat/ChatViewModel.kt")
        val drop = ProductionSources.functionWindow(vm, "dropOrphanedToolParts", 1_500).orEmpty()
        val sanitize = ProductionSources.functionWindow(vm, "sanitizeAgentHistory", 5_000).orEmpty()
        assertTrue("dropOrphanedToolParts window not found in the ChatViewModel surface", drop.isNotEmpty())
        assertTrue("sanitizeAgentHistory window not found in the ChatViewModel surface", sanitize.isNotEmpty())
        assertTrue("dropOrphanedToolParts must pair on the shared key", drop.contains("ToolPairing.key("))
        assertTrue("sanitizeAgentHistory must pair on the shared key", sanitize.contains("ToolPairing.key("))
        assertFalse("raw-id comparison left in dropOrphanedToolParts", drop.contains("toolUseIds.add(part.id)"))
        assertFalse(
            "raw-id comparison left in sanitizeAgentHistory",
            sanitize.contains("toolUses.map { it.id }.toSet()"),
        )
        // harness 半边：身份由 app 注入，且不许自己再实现一份裸比较。
        val harness = ProductionSources.readHarness("agent/ToolRoundPairing.kt")
        assertTrue("ToolRoundPairing must take the shared key", harness.contains("keyOf"))
        assertFalse(
            "raw-id comparison left in ToolRoundPairing: the harness must pair on the injected key",
            harness.contains("it.id == part.id && it.name == part.name"),
        )
    }
}
