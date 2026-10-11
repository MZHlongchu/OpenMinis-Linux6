package com.openminis.app.data

import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.mayImportLegacyProviderMirror
import com.openminis.app.data.repository.shouldRepairProviderMirror
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCredentialCapabilityTest {
    @Test
    fun `capability table accepts only implemented provider credential pairs`() {
        // 期望表按「实现的凭据对」逐个类型写死，枚举仍被穷举：新增一个 ProviderType
        // 而忘了归类，这里会显式报错，而不是被默认值蒙混过去。
        val both = setOf(ProviderCredential.apiKey, ProviderCredential.oauth)
        val apiKeyOnly = setOf(ProviderCredential.apiKey)
        val oauthOnly = setOf(ProviderCredential.oauth)
        val expected = mapOf(
            ProviderType.anthropic to both,
            ProviderType.openAI to both,
            ProviderType.openRouter to both,
            ProviderType.xAI to both,
            ProviderType.kimiCode to both,
            ProviderType.openAIResponses to both,
            ProviderType.gemini to apiKeyOnly,
            // [T-copilot-provider][T-android-copilot-oauth-only] 上游 1.14 新增的
            // githubCopilot 是 OAuth-ONLY：Copilot 的聊天端点没有用户可获取的
            // API key（粘 GitHub PAT 也会被拒）。这条用例原来断言「每个类型都支持
            // apiKey」——那是 githubCopilot 进枚举之前的形状；现在把 oauth-only
            // 显式列出来，与 AddProviderScreen 的 availableCredentials 分支、
            // ProviderRepository.refreshModels 的「Copilot holds NO api key」
            // 判断保持同一口径。
            ProviderType.githubCopilot to oauthOnly,
            ProviderType.antigravity to apiKeyOnly,
            ProviderType.unsupported to apiKeyOnly,
        )
        for (type in ProviderType.values()) {
            val want = expected[type]
                ?: throw AssertionError("ProviderType.$type 未在能力表期望里归类")
            for (credential in ProviderCredential.values()) {
                assertEquals(
                    "$type × $credential",
                    credential in want,
                    ProviderRepository.supportsCredential(type, credential),
                )
            }
        }
    }

    @Test
    fun `legacy JSON seeds only a confirmed empty database`() {
        assertTrue(mayImportLegacyProviderMirror(dbReadFailed = false, dbInstanceCount = 0))
        assertFalse(mayImportLegacyProviderMirror(dbReadFailed = true, dbInstanceCount = 0))
        assertFalse(mayImportLegacyProviderMirror(dbReadFailed = false, dbInstanceCount = 3))
    }

    @Test
    fun `hash mismatch requests DB mirror repair but never JSON import`() {
        assertFalse(shouldRepairProviderMirror(liveHash = "same", dbHash = "same"))
        assertTrue(shouldRepairProviderMirror(liveHash = "stale-json", dbHash = "new-db"))
        assertTrue(shouldRepairProviderMirror(liveHash = null, dbHash = "new-db"))
    }

    @Test
    fun `failed legacy migration is not accepted as a loaded config`() = runBlocking {
        try {
            com.openminis.app.data.repository.persistLegacyProviderMirror<Int> {
                error("simulated Room write failure")
            }
            throw AssertionError("migration failure must propagate")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("could not be persisted"))
            assertEquals("simulated Room write failure", e.cause?.message)
        }
    }

    @Test
    fun `capability list is stable for debug schema`() {
        assertEquals(
            listOf(ProviderCredential.apiKey, ProviderCredential.oauth),
            ProviderRepository.supportedCredentials(ProviderType.openRouter),
        )
        assertEquals(
            listOf(ProviderCredential.apiKey),
            ProviderRepository.supportedCredentials(ProviderType.unsupported),
        )
        assertFalse(ProviderRepository.supportsCredential(ProviderType.unsupported, ProviderCredential.oauth))
    }
}
