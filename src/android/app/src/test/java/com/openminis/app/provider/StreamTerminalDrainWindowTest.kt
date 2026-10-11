package com.openminis.app.provider

import com.openminis.app.provider.openai.terminalDrainExpired
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-openai-terminal-drain-anchor]
 *
 * `StreamTerminalDrainTest` pins WHICH lines end a finished stream (only the
 * relay's keepalive comments; trailing `data:` lines are never cut off). This
 * one pins WHEN the drain window behind those rules starts counting — the half
 * that made the previous fix cut off the very thing it was written to collect.
 *
 * Regressed shape: the window was measured from `lastContentNanos`, the last
 * chunk that carried model OUTPUT. `finish_reason` / `response.completed` are
 * lifecycle chunks and never touch it, so a turn that reasoned silently for
 * minutes and then finished arrived with the content timestamp already far
 * outside the window: the first watchdog poll (250ms) tripped "terminal-drain",
 * cancelled the call and closed the socket. Everything the trailing drain
 * exists to collect — the Chat Completions `usage` chunk that arrives AFTER
 * `finish_reason` under `stream_options.include_usage`, a late tool-call item —
 * was dropped, i.e. the exact opposite of [StreamTerminalDrainTest]'s
 * "the trailing usage block must not be lost".
 *
 * The window arithmetic is asserted through the same pure helper the watchdog
 * calls, using the helper's real default window, so neither the 15s wall clock
 * nor the constant has to be duplicated here.
 */
class StreamTerminalDrainWindowTest {

    private fun ms(nanos: Long): Long = nanos * 1_000_000L

    /** The production trailing-drain window (15s), expressed in nanos. */
    private val drainNanos = ms(15_000L)

    @Test
    fun `a terminal event after a long silent reasoning phase gets the full window`() {
        val lastContentAt = ms(1_000)      // …then 10 minutes of silent reasoning
        val terminalAt = ms(601_000)       // finish_reason / response.completed

        assertFalse(
            "the drain must not be expired at the instant the terminal event lands",
            terminalDrainExpired(terminalAt, terminalAt, lastContentAt),
        )
        assertFalse(
            "the trailing usage chunk still has the window to arrive, counted from the terminal event",
            terminalDrainExpired(terminalAt + drainNanos - ms(100), terminalAt, lastContentAt),
        )
        assertTrue(
            "15s of silence AFTER the terminal event is what ends the drain",
            terminalDrainExpired(terminalAt + drainNanos, terminalAt, lastContentAt),
        )
    }

    @Test
    fun `silence before the terminal event cannot consume the window`() {
        // The old anchor turned this into an instant trip: sinceContent = 600s.
        val lastContentAt = ms(1_000)
        val terminalAt = ms(601_000)
        assertFalse(terminalDrainExpired(terminalAt, terminalAt, lastContentAt))
        assertFalse(terminalDrainExpired(terminalAt + drainNanos - ms(1), terminalAt, lastContentAt))
    }

    @Test
    fun `a content chunk flushed after the terminal event restarts the window`() {
        val terminalAt = ms(10_000)
        val lateContentAt = terminalAt + ms(14_000)

        assertFalse(
            "a late content chunk keeps the stream alive",
            terminalDrainExpired(lateContentAt, terminalAt, lateContentAt),
        )
        assertFalse(
            "…and the window then counts from that chunk",
            terminalDrainExpired(lateContentAt + drainNanos - ms(1), terminalAt, lateContentAt),
        )
        assertTrue(
            "15s after the last content chunk the relay is done talking",
            terminalDrainExpired(lateContentAt + drainNanos, terminalAt, lateContentAt),
        )
    }

    @Test
    fun `without a terminal event the drain window never applies`() {
        // A stream that has not reached finish_reason/response.completed is still
        // governed by the raw-byte and content-idle watches only — this helper
        // must not be the thing that closes it.
        assertFalse(terminalDrainExpired(ms(10_000_000), 0L, ms(1)))
    }
}
