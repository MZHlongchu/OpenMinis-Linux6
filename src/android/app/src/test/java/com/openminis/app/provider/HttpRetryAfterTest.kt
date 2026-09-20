package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpRetryAfterTest {

    @Test
    fun `parses integer Retry-After header`() {
        assertEquals(12, HttpRetryAfter.parseSeconds("12"))
        assertEquals(120, HttpRetryAfter.parseSeconds("999"))
        assertNull(HttpRetryAfter.parseSeconds("Wed, 21 Oct 2015 07:28:00 GMT"))
    }

    @Test
    fun `parses retry-after from JSON body`() {
        assertEquals(
            8,
            HttpRetryAfter.parseSeconds(null, """{"error":{"message":"retry_after=8"}}"""),
        )
        assertEquals(
            5,
            HttpRetryAfter.parseSeconds(null, """{"retryAfter": 5}"""),
        )
    }

    @Test
    fun `header wins over body`() {
        assertEquals(3, HttpRetryAfter.parseSeconds("3", """{"retry_after": 40}"""))
    }

    @Test
    fun `backoff follows schedule then honours Retry-After`() {
        assertEquals(1, HttpRetryAfter.delaySeconds(0, null))
        assertEquals(2, HttpRetryAfter.delaySeconds(1, null))
        assertEquals(16, HttpRetryAfter.delaySeconds(4, null))
        assertEquals(20, HttpRetryAfter.delaySeconds(0, 20))
        assertEquals(120, HttpRetryAfter.delaySeconds(0, 500))
    }

    @Test
    fun `plain 429 body is RateLimited`() {
        val err = HttpRetryAfter.map429("Rate limited", "12")
        assertTrue(err is LLMError.RateLimited)
        assertEquals(12, (err as LLMError.RateLimited).retryAfterSeconds)
    }

    @Test
    fun `quota and no-channel 429 bodies are ProviderError`() {
        val bodies = listOf(
            "无可用渠道",
            """{"error":{"message":"no_available_providers"}}""",
            "model_not_found for sn-deepseek-v4-1-flash",
            "insufficient_quota",
            "当前分组上游负载已饱和",
            "余额不足",
        )
        for (body in bodies) {
            val err = HttpRetryAfter.map429(body, "1")
            assertTrue("expected ProviderError for $body, got $err", err is LLMError.ProviderError)
            assertTrue((err as LLMError.ProviderError).detail.contains("[429]"))
        }
        assertFalse(HttpRetryAfter.isPermanentCapacityBody("Rate limited"))
        assertFalse(HttpRetryAfter.isPermanentCapacityBody("Too many requests"))
    }

    @Test
    fun `transient 429 message includes body snippet`() {
        val err = HttpRetryAfter.map429("try again in a few seconds", null)
        assertTrue(err is LLMError.RateLimited)
        assertTrue(err.message!!.contains("try again in a few seconds"))
        assertTrue(LLMError.RateLimited().isFallbackable)
        assertTrue(LLMError.InvalidApiKey().isFallbackable)
        assertTrue(LLMError.ProviderError("[429] 无可用渠道").isFallbackable)
        assertTrue(LLMError.ProviderError("[503] no_available_providers").isFallbackable)
        assertFalse(LLMError.ProviderError("The model does not exist").isFallbackable)
        assertFalse(LLMError.ProviderError("context window 512 tokens").isFallbackable)
        assertFalse(LLMError.TransientError("connection dropped").isFallbackable)
    }
}
