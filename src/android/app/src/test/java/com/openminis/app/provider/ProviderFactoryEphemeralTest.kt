package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.provider.openai.OpenAIProvider
import org.junit.After
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-model-use-provider-pollution] `ProviderFactory.create` 会记忆化
 * provider（[T-android-provider-memo]），而 `OpenAIProvider` 上挂着一批"每次调用"的
 * 可变请求参数（extra_body / extra_headers / endpoint 覆写 / 图像透传），请求构造器
 * 在发送时才读它们。`minis-model-use` 的 passthrough 调用因此会把覆写（可含被替换的
 * Authorization）留在**聊天侧同一个对象**上，污染该实例之后所有请求。
 *
 * 修复：改这些字段的调用方拿 `createEphemeral`（不进 memo、一次调用一个对象），并在
 * finally 里 `clearPerCallOverrides()`。本测试钉住这两条性质。
 */
class ProviderFactoryEphemeralTest {

    private val instance = ProviderInstance(
        id = "inst-ephemeral-test",
        label = "Ephemeral",
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        customBaseURL = "https://api.example.test/v1",
    )
    private val model = LLMModel.gpt4oMini

    @After
    fun tearDown() {
        ProviderFactory.invalidateAll()
    }

    @Test
    fun `createEphemeral is never the memoized instance and never replaces it`() {
        val memo = ProviderFactory.create(instance, "test-key", model)
        val ephemeral = ProviderFactory.createEphemeral(instance, "test-key", model)

        assertNotSame("ephemeral 必须是独立对象", memo, ephemeral)
        assertSame(
            "ephemeral 构建不得顶掉 memo 里的实例",
            memo,
            ProviderFactory.create(instance, "test-key", model),
        )
    }

    @Test
    fun `per-call overrides on an ephemeral provider never reach the memoized chat provider`() {
        val chat = ProviderFactory.create(instance, "test-key", model) as OpenAIProvider
        val ephemeral = ProviderFactory.createEphemeral(instance, "test-key", model) as OpenAIProvider

        // 与 ModelUseOffloadHandler 写入的字段一一对应（chat passthrough + 图像透传）
        ephemeral.chatExtraHeaders = mapOf("Authorization" to "Bearer poisoned")
        ephemeral.chatExtraBody = mapOf("plugins" to emptyList<String>())
        ephemeral.absoluteEndpointOverride = "/evil"
        ephemeral.imageExtraHeaders = mapOf("X-Poison" to "1")
        ephemeral.imagePathOverride = "/evil/images"

        assertTrue("聊天侧不得继承 extra_headers", chat.chatExtraHeaders.isEmpty())
        assertTrue(chat.chatExtraBody.isEmpty())
        assertNull(chat.absoluteEndpointOverride)
        assertTrue(chat.imageExtraHeaders.isEmpty())
        assertNull(chat.imagePathOverride)

        ephemeral.clearPerCallOverrides()
        assertTrue(ephemeral.chatExtraHeaders.isEmpty())
        assertTrue(ephemeral.chatExtraBody.isEmpty())
        assertNull(ephemeral.absoluteEndpointOverride)
        assertTrue(ephemeral.imageExtraHeaders.isEmpty())
        assertNull(ephemeral.imagePathOverride)
    }
}
