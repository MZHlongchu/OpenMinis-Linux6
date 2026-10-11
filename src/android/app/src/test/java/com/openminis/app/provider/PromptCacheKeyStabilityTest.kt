package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-prompt-cache-key-truncation]
 *
 * Responses 路径的 `prompt_cache_key` 是给上游做路由亲和用的：同一个会话的相邻请求
 * 必须带同一个 key，否则 T126 想要的跨轮 prompt cache 命中率拿不到。旧指纹里塞了
 * `messages.size` 与「最后一条 user 文本」，而两者在同一次用户回合里每发起一次 LLM
 * 调用都会变（追加 assistant / tool_result；工具轮的 lastUser 是无正文的 tool_result）
 * ⇒ key 每个调用都翻转。
 *
 * 断言走真实请求体（`buildResponsesAPIBody`），并且复用**同一个 provider 实例**——
 * 真实对话里同一个 ProviderFactory memo 条目就是跨轮复用的那一个。
 */
class PromptCacheKeyStabilityTest {

    private val model = LLMModel(
        id = "gpt-5-codex",
        displayName = "Codex",
        provider = "test",
        inputModalities = listOf("text"),
    )

    private fun provider() = OpenAIProvider(
        apiKey = "test-key",
        model = model,
        basePath = "https://example.invalid/v1",
        useResponsesAPI = true,
    )

    private fun keyOf(p: OpenAIProvider, messages: List<LLMMessage>): String =
        p.buildResponsesAPIBody(
            messages = messages,
            systemPrompt = null,
            maxTokens = 1024,
            stream = true,
        ).optString("prompt_cache_key")

    private fun user(text: String) = LLMMessage(LLMMessage.Role.USER, text)

    private fun assistant(text: String) = LLMMessage(LLMMessage.Role.ASSISTANT, text)

    /** 工具轮的末条消息：有 role=USER 但没有正文，正是旧实现的第二个不稳定因子。 */
    private fun toolResult(id: String) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = "",
        contentParts = listOf(AgentContentPart.ToolResult(id = id, name = "shell_execute", content = "ok")),
    )

    /**
     * [T-prompt-cache-key-chat-identity] 「图片直发 / 无 caption」的首条 user 消息：
     * 没有文本、只有像素。旧指纹只看文本 ⇒ 恒为 `sha256("")`。
     */
    private fun imageOnly(dbId: String?, seed: Byte) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = "",
        contentParts = listOf(
            AgentContentPart.ImageData(data = ByteArray(64) { seed }, mimeType = "image/png"),
        ),
        dbMessageId = dbId,
    )

    @Test
    fun `the key does not rotate while the history only grows`() {
        val p = provider()
        val firstTurn = listOf(user("hello there"))
        val k0 = keyOf(p, firstTurn)

        // 同一回合的第二次调用：追加 assistant(tool_use) + tool_result。
        val afterTool = firstTurn + assistant("calling tool") + toolResult("call_1")
        assertEquals("same turn, second LLM call must reuse the key", k0, keyOf(p, afterTool))

        // 下一轮：又追加了一个真实用户回合。
        val nextTurn = afterTool + assistant("done") + user("now do this")
        assertEquals("next turn of the same chat must reuse the key", k0, keyOf(p, nextTurn))
    }

    @Test
    fun `the key rotates when the history is truncated`() {
        val p = provider()
        val full = listOf(user("truncate me")) + (1..5).map { assistant("step $it") }
        val before = keyOf(p, full)

        // 编辑 / 从此处删除 / 回退：历史变短，relay 不能再把截断前的上下文当同一 thread。
        val truncated = full.take(2)
        val after = keyOf(p, truncated)
        assertNotEquals("a truncated history must not keep the pre-truncation key", before, after)

        // 但截断后的追赶必须回到稳定：不能历史每长一条就又轮转一次。
        assertEquals("growth after a truncation is still the same thread", after, keyOf(p, truncated + assistant("step 9")))
    }

    @Test
    fun `different chats get different keys`() {
        val p = provider()
        assertNotEquals(keyOf(p, listOf(user("chat A"))), keyOf(p, listOf(user("chat B"))))
    }

    @Test
    fun `a history with no user text still gets one deterministic key`() {
        // 旧 KDoc 声称这里回退成随机 UUID，实现并不是——把真实口径钉住。
        val p = provider()
        val onlyAssistant = listOf(assistant("(attachments only)"))
        val key = keyOf(p, onlyAssistant)
        assertTrue("key shape: $key", key.startsWith("minis-"))
        assertEquals("no per-call randomness", key, keyOf(p, onlyAssistant))
    }

    // -- [T-prompt-cache-key-chat-identity] 会话身份 ---------------------------------

    @Test
    fun `two caption-less image chats do not collapse onto the empty-text key`() {
        // 旧指纹只哈希首条 user 文本；图片直发（无 caption）时那是空串 ⇒ 两张这样
        // 的会话拿到完全相同的 key，且共用同一份截断水位。
        val p = provider()
        val chatA = keyOf(p, listOf(imageOnly("db-a", 1)))
        val chatB = keyOf(p, listOf(imageOnly("db-b", 2)))
        assertNotEquals("distinct chats must not share one key", chatA, chatB)
        // 行 id 缺失时（合成消息 / 迁移前历史）结构指纹也要能分开两张图片。
        assertNotEquals(
            "the structural fingerprint alone must separate two caption-less chats",
            keyOf(p, listOf(imageOnly(null, 1))),
            keyOf(p, listOf(imageOnly(null, 2))),
        )
        // 同一会话重放 + 后续增长 = 同一个 key（身份不依赖 messages.size）。
        assertEquals(chatA, keyOf(p, listOf(imageOnly("db-a", 1), assistant("done"))))
    }

    @Test
    fun `two chats with the same opening turn are still two chats`() {
        // 首条消息结构完全一致（同一张截图分别发进两个 chat）时，行 id 是唯一的
        // 会话级标识：水位与 key 仍必须分开，否则 B 的 epoch 会被 A 的历史推着走。
        val p = provider()
        assertNotEquals(
            keyOf(p, listOf(imageOnly("db-a", 7))),
            keyOf(p, listOf(imageOnly("db-b", 7))),
        )
        assertNotEquals(
            keyOf(p, listOf(user("hi").copy(dbMessageId = "row-1"))),
            keyOf(p, listOf(user("hi").copy(dbMessageId = "row-2"))),
        )
    }

    @Test
    fun `another chat's growth does not rotate this chat's key`() {
        // provider 实例被 ProviderFactory 记忆化后跨会话共享，水位表也在实例上：
        // 分区键一旦退化成常量，A 涨到 50 条就把 lastLen 抬到 50，B 的首次 4 条请求
        // （4 < 51）立刻吃到一次 epoch++ ⇒ B 的 key 取决于「另一个会话跑没跑过」。
        val bHistory = listOf(imageOnly("db-b", 9)) + (1..3).map { assistant("b$it") }

        val freshInstance = provider()
        val keyWithoutA = keyOf(freshInstance, bHistory)

        val sharedInstance = provider()
        keyOf(sharedInstance, listOf(imageOnly("db-a", 8)) + (1..50).map { assistant("a$it") })
        val keyAfterA = keyOf(sharedInstance, bHistory)

        assertEquals(
            "a sibling chat's watermark must not rotate this chat's key",
            keyWithoutA,
            keyAfterA,
        )
        // B 自己继续增长也仍然稳定（水位回到 B 的 4 → 5，不触发 epoch）。
        assertEquals(keyAfterA, keyOf(sharedInstance, bHistory + assistant("b4")))
    }

    @Test
    fun `editing the opening turn rotates the key`() {
        // 身份仍对首条消息的签名（含行 id 与文本）敏感：relay 把 prompt_cache_key 当
        // thread id 时，编辑过的开场白不能继续沿用编辑前的上下文。
        val p = provider()
        val before = keyOf(p, listOf(user("draft the report").copy(dbMessageId = "row-1")))
        val after = keyOf(p, listOf(user("draft the memo").copy(dbMessageId = "row-1")))
        assertNotEquals("an edited opening turn is a different thread", before, after)
    }
}
