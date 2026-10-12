package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-switch-model-next-request] + [T-android-ctx-overflow-attribute-dispatch]
 * Ports of iOS 86a045284 (+ the d6e475a35 follow-ups) and 08f3feea2.
 *
 * Picking another model while a task runs used to take effect only after the
 * whole turn: runAgentLoop kept its own provider for every request of the turn,
 * so a task with 20 tool calls ran all of them on the old model. Now the next
 * REQUEST uses the new model, and the context calibration is credited to the
 * model a request actually went to.
 */
class SwitchModelNextRequestTest {

    /**
     * [T-merge-upstream-1-14] The port's agent loop lives in
     * `ChatViewModelAgentLoopExt.kt` ([T-android-split-chat] /
     * [T-android-seam-extraction]), so the guard reads the whole
     * `ChatViewModel*` family via [ProductionSources.readFamily] — the house rule
     * for a split class ("扫描面从一个文件扩到「这个类的全部片段」，断言本身一个字
     * 不动"). Only the anchors that name a construct this port does not have are
     * re-pointed, each with its reason inline.
     */
    private val vm = ProductionSources.readFamily("ui/chat/ChatViewModel.kt")

    private val a = Any()
    private val b = Any()

    @Test
    fun `a pending switch is applied once the picker has put a different provider in place`() {
        assertTrue(ChatViewModel.modelSwitchToApply(pending = true, chosen = b, loopProvider = a))
    }

    @Test
    fun `nothing is applied without a pending switch - a group fallback moving the provider is not a user switch`() {
        assertFalse(ChatViewModel.modelSwitchToApply(pending = false, chosen = b, loopProvider = a))
    }

    @Test
    fun `a switch the picker has not applied yet stays pending`() {
        // The flag is set before the picker rebinds; a loop head in between
        // must not consume it.
        assertFalse(ChatViewModel.modelSwitchToApply(pending = true, chosen = a, loopProvider = a))
        assertFalse(ChatViewModel.modelSwitchToApply(pending = true, chosen = null, loopProvider = a))
    }

    @Test
    fun `a healthy turn is flagged, not cancelled, and the retry ladder still is`() {
        val seam = vm.substringAfter("private fun cancelWorkBoundToPreviousModel(")
            .substringBefore("private var pendingModelSwitch")
        val healthy = seam.substringAfter("if (!isBoundToAbandonedModel()) {").substringBefore("return\n")
        assertTrue(healthy.contains("pendingModelSwitch = true"))
        assertFalse(healthy.contains("streamJob?.cancel()"))
        val ladder = seam.substringAfter("if (!isBoundToAbandonedModel()) {").substringAfter("return\n")
        assertTrue(ladder.contains("pendingModelSwitch = false"))
        assertTrue(ladder.contains("streamJob?.cancel()"))
    }

    @Test
    fun `the loop applies the switch at the top of every iteration, before the request`() {
        val loop = vm.substringAfter("for (turn in 0 until turnCap) {")
        // [T-merge-upstream-1-14] `modelSwitchToApply` lives in ChatViewModel's
        // companion, and an extension function cannot reach a companion member
        // unqualified — hence the `ChatViewModel.` prefix the merge needed.
        val apply = loop.indexOf("if (c != null && ChatViewModel.modelSwitchToApply(pendingModelSwitch, c, currentProvider)) {")
        val sanitize = loop.indexOf("sanitizeAgentHistory()")
        // The request now goes out through the [T-android-seam-extraction] seam
        // (LlmProviderStreamClient.streamRound) instead of a direct
        // `currentProvider.streamMessage(` call; the ordering is the point.
        val request = loop.indexOf(".streamRound(")
        assertTrue(apply in 0 until sanitize)
        assertTrue(sanitize < request)
        val body = loop.substring(apply, sanitize)
        assertTrue(body.contains("currentProvider = chosen"))
        assertTrue("a later fallback may rebind again", body.contains("classProviderAtSend = chosen"))
        // [T-android-switch-model-fallback-rebind] The fallback chain and its
        // strategy follow the pick, rebuilt in the same Main snapshot.
        assertTrue(body.contains("Triple(c, buildFallbackProviders(c), strategy)"))
        assertTrue(body.contains("remainingFallbacks.clear()"))
        assertTrue(body.contains("remainingFallbacks.addAll(switched.second)"))
        assertTrue(body.contains("loopFallbackStrategy = switched.third"))
        // [T-merge-upstream-1-14] The `== FallbackStrategy.always` comparison
        // itself moved into the harness policy with the rest of the decision
        // table ([T-loop-retry-cut]) and is covered by harness
        // StreamRetryPolicyTest; what the loop still has to prove is that BOTH
        // decisions are taken with the loop's strategy var — the one the switch
        // just re-read — and not with the send-time parameter.
        assertTrue(loop.contains("remainingFallbacks.size, loopFallbackStrategy,"))
        assertTrue(loop.contains("StreamRetryPolicy.shouldFallback(errorClass, loopFallbackStrategy)"))
        assertTrue(body.contains("loopSystemPrompt = systemPromptFor(chosen, loopSystemPrompt)"))
    }

    @Test
    fun `the request carries the loop's prompt, and a stale flag is cleared when the turn starts`() {
        // [T-merge-upstream-1-14] The loop's prompt is a var now, and the
        // request is assembled as a named-argument HarnessRoundRequest, so the
        // two facts are asserted where they live. (Upstream's single line also
        // carried `dispatchInputTokens` from `recordContextDispatch` — the
        // [T-ctx-measure-outbound] calibration item owned by the A2 ticket; the
        // prompt half is this test's.)
        assertTrue(vm.contains("systemPrompt = loopSystemPrompt,"))
        assertTrue(vm.contains("maxTokens = dynamicMaxTokens(currentProvider,"))
        // The turn-start reset reads the CLASS-level provider, so it cannot be
        // `this@ChatViewModel.currentProvider` here: this file is an extension
        // function (ChatViewModel.runAgentLoop) and has no such label. The local
        // snapshot taken above the turn-local `currentProvider` is the same read.
        assertTrue(vm.contains("val classProviderAtLoopStart = currentProvider"))
        assertTrue(vm.contains("if (classProviderAtLoopStart === provider) pendingModelSwitch = false"))
    }

    @Test
    fun `calibration and overflow are scored against the model the request went to`() {
        assertTrue(vm.contains("recordContextDispatch(outboundHistory, currentProvider.model)"))
        // [T-ctx-measure-outbound-scan-scope] 函数体锚点跟着可见性走：循环是扩展函数，
        // 这两个成员因此是 internal（上游是 private）。截取范围与断言不变。
        val calibrate = vm.substringAfter("internal fun calibrateContextSize(").substringBefore("private fun noteContextOverflow(")
        assertTrue(calibrate.contains("val model = lastDispatchModel?.id ?: currentModel?.id"))
        val overflow = vm.substringAfter("private fun noteContextOverflow(").substringBefore("private fun seedContextCalibration(")
        assertTrue(overflow.contains("val model = lastDispatchModel?.id ?: currentModel?.id"))
        assertTrue(overflow.contains("lastDispatchWindow"))
    }
}
