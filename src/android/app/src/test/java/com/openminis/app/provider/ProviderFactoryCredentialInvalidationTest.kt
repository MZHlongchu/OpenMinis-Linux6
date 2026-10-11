package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.junit.After
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [T-android-manual-bearer-memo-invalidation] 手动 bearer token 是**构造期**烘进
 * provider 的：ProviderFactory 的 openAI/xAI/kimiCode 分支读
 * `loadManualBearerToken()` 决定 `effectiveKey`，甚至决定走 Codex OAuth 形状还是
 * API-key 形状（:169-212、:235-264），而 memo key 只含 `instance.id / model /
 * fingerprint(apiKey) / instance.hashCode()` —— **不含** bearer。OAuth 实例在聊天
 * 侧拿到的 apiKey 是 `""`，所以改/删 bearer 后 key 完全不变，旧 provider 会一直
 * 拿旧令牌发请求，只能等进程重启或缓存被挤掉。
 *
 * 因此两个写入口（ProviderDetailScreen 的保存/删除手动 bearer）必须与
 * `saveApiKey`/`deleteApiKey` 一样调用 `ProviderFactory.invalidateInstance`。
 * 本测试钉住它依赖的那条契约：按实例失效会真的丢掉该实例的 memo 条目，并且不会
 * 波及别的实例。
 */
class ProviderFactoryCredentialInvalidationTest {

    private val instance = ProviderInstance(
        id = "inst-bearer-test",
        label = "Bearer",
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        customBaseURL = "https://api.example.test/v1",
    )
    private val other = ProviderInstance(
        id = "inst-bearer-other",
        label = "Other",
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
    fun `invalidating one instance drops only that instance's memoized provider`() {
        val before = ProviderFactory.create(instance, "test-key", model)
        val otherBefore = ProviderFactory.create(other, "test-key", model)

        // Control: the memo is what every chat turn reuses.
        assertSame(
            "未失效时同一实例必须命中同一个 memo 条目",
            before,
            ProviderFactory.create(instance, "test-key", model),
        )

        ProviderFactory.invalidateInstance(instance.id)

        assertNotSame(
            "失效后必须重建 —— 否则写入手动 bearer 的实例会继续用旧令牌",
            before,
            ProviderFactory.create(instance, "test-key", model),
        )
        assertSame(
            "按实例失效不得波及别的实例",
            otherBefore,
            ProviderFactory.create(other, "test-key", model),
        )
    }

    @Test
    fun `a blank instance id is a no-op rather than a cache-wide flush`() {
        val before = ProviderFactory.create(instance, "test-key", model)

        ProviderFactory.invalidateInstance("")

        assertSame(
            "空 id 不得清空缓存（否则每次误调用都会重建所有 OkHttpClient）",
            before,
            ProviderFactory.create(instance, "test-key", model),
        )
    }
}
