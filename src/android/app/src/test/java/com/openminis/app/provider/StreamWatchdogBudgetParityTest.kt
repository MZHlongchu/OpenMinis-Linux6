package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.openai.OpenAIProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-stream-watchdog-budget-parity / #188] The OUTER `streamStallWatchdog` used to
 * arm phase 1 with a flat 45s/90s counted from subscription time, while the
 * provider runs its own pre-first-chunk watch inside the stream (OpenAI: a 120s
 * upload cap plus a 120s TTFB budget measured from upload completion). The outer
 * one therefore always fired first, and #188's raised TTFB — meant to stop slow
 * proxies from killing healthy requests — could never take effect: every attempt
 * of a slow-but-healthy relay was cancelled from scratch.
 *
 * These tests pin the shared budget: a provider that declares one widens phase 1,
 * a provider that does not keeps the caller's window, and phase 2 stays keyed on
 * the caller's base (asserted at the call sites, which pass
 * `baseFirstEventTimeoutMs * 2`).
 */
class StreamWatchdogBudgetParityTest {

    @Test
    fun `openai declares its upload plus ttfb budget to the outer watchdog`() {
        val provider = OpenAIProvider(
            apiKey = "test-key",
            model = LLMModel.gpt4oMini,
            basePath = "https://example.invalid/v1",
        )

        assertEquals(
            "the declared budget must be the sum of the two internal watches",
            120_000L + 120_000L,
            provider.streamFirstChunkBudgetMs,
        )
        // 45s/90s phase 1 is widened to the internal budget + slack so the
        // provider's own watch (which knows when the upload finished) wins.
        assertTrue(provider.streamFirstEventBudgetMs(45_000L) >= provider.streamFirstChunkBudgetMs)
        assertTrue(provider.streamFirstEventBudgetMs(90_000L) >= provider.streamFirstChunkBudgetMs)
        assertEquals(provider.streamFirstEventBudgetMs(45_000L), provider.streamFirstEventBudgetMs(90_000L))
    }

    @Test
    fun `a provider without its own budget keeps the caller's phase-1 window`() {
        val provider = AnthropicProvider(
            apiKey = "test-key",
            model = LLMModel.claudeHaiku45,
            basePath = "https://example.invalid",
        )

        assertEquals(0L, provider.streamFirstChunkBudgetMs)
        assertEquals(45_000L, provider.streamFirstEventBudgetMs(45_000L))
        assertEquals(90_000L, provider.streamFirstEventBudgetMs(90_000L))
    }
}
