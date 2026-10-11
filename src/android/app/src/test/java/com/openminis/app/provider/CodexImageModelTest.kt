package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import com.openminis.app.provider.openai.buildCodexImageBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-codex-gpt-image25-android] Pins what actually goes on the wire for the
 * Codex image models.
 *
 * The distinction these cover is invisible in a diff: gpt-image-2 sends a bare
 * `{type:image_generation}` and lets the backend pick the model, while the 2.5
 * variants name themselves in that same object. Getting it backwards would
 * either pin gpt-image-2's behaviour to something the backend currently
 * chooses, or silently route sunburst/flare to the default model — and both
 * failures look like a correct request in the logs.
 *
 * `buildCodexImageBody` is internal to the module and lives in its own file
 * (`OpenAIProviderImage.kt`, part of the provider split) rather than in the
 * class, and the Codex constructor hardcodes chatgpt.com — so this calls the
 * constructor + the extension directly instead of going through MockWebServer.
 * That is the honest trade: it tests body construction, not the transport.
 */
class CodexImageModelTest {

    private fun imageModel(id: String) = LLMModel(
        id = id,
        displayName = id,
        provider = "OpenAI",
        inputModalities = listOf("text", "image"),
        outputModalities = listOf("image"),
    )

    private fun bodyFor(modelId: String): JSONObject {
        val provider = OpenAIProvider(
            oauthTokenProvider = { "test-token" },
            model = imageModel(modelId),
        )
        val messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = "a red bicycle"))
        // [T-u14-merge-codex-body-split] 直接调用，不再反射。
        //
        // 上游把这个 body 构造器放在 OpenAIProvider.kt 里、声明为 private，测试只能
        // `getDeclaredMethod("buildCodexImageBody", List::class.java)` 反射进去。本移植
        // 早已把 provider 拆成 OpenAIProviderImage.kt 等文件，它在那里是**文件级
        // internal 扩展函数** `internal fun OpenAIProvider.buildCodexImageBody(...)`：
        // 不再是 OpenAIProvider 类的方法，反射必然抛 NoSuchMethodException（本用例先前
        // 四条失败就是这么来的），但同模块的测试源集可以直接调用（AGP 把单测编译成
        // main 的 friend module，internal 可见 —— DetectImageMimeTest 也是这样调
        // detectImageMime 的）。
        //
        // 意图不变：这里钉的是**请求体构造**，不是传输层，所以仍然不用 MockWebServer
        // 去连那个硬编码的 chatgpt.com。
        return provider.buildCodexImageBody(messages)
    }

    private fun imageTool(body: JSONObject): JSONObject =
        body.getJSONArray("tools").getJSONObject(0)

    @Test
    fun `gpt-image-2 sends a bare image_generation tool`() {
        val tool = imageTool(bodyFor("gpt-image-2"))
        assertEquals("image_generation", tool.getString("type"))
        assertFalse(
            "gpt-image-2 must not pin a model — the backend picks it",
            tool.has("model"),
        )
    }

    @Test
    fun `the 2 point 5 variants name themselves in the tool`() {
        for (id in listOf("gpt-image-2.5-sunburst", "gpt-image-2.5-flare")) {
            val tool = imageTool(bodyFor(id))
            assertEquals("image_generation", tool.getString("type"))
            assertEquals("tool must name $id", id, tool.getString("model"))
        }
    }

    /**
     * The envelope is the same for all three: the wire model stays gpt-5.5 and
     * only the tool object varies. A change here would mean the 2.5 variants
     * took a different request shape, not just a different image model.
     */
    @Test
    fun `every codex image model keeps the same wire envelope`() {
        for (id in listOf("gpt-image-2", "gpt-image-2.5-sunburst", "gpt-image-2.5-flare")) {
            val body = bodyFor(id)
            assertEquals("wire model for $id", "gpt-5.5", body.getString("model"))
            assertTrue("$id must still stream", body.getBoolean("stream"))
            assertEquals("$id must send exactly one tool", 1, body.getJSONArray("tools").length())
        }
    }

    /** The prompt is carried through, so a body that builds is not an empty one. */
    @Test
    fun `the user prompt reaches the request`() {
        val input = bodyFor("gpt-image-2.5-flare").getJSONArray("input").getJSONObject(0)
        assertTrue(input.getString("content").contains("a red bicycle"))
    }
}
