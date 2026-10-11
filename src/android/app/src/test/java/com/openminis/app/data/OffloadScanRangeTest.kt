package com.openminis.app.data

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.AgentContentPart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-offload-scan-range] Port of iOS 5a9c5bfcd + d302de846: the
 * context offload scans only what is sent (after the compact-v2 anchor), and
 * ranks / accounts with ContextSizeMeter's calibrated estimate instead of the
 * vocab-less BPETokenizer (a flat chars/3).
 */
class OffloadScanRangeTest {

    private fun result(text: String) = AgentContentPart.ToolResult(id = "t", name = "shell_execute", content = text)

    @Test fun `CJK output is no longer under-ranked against English of fewer tokens`() {
        val cjk = result("日志内容".repeat(250))            // 1000 CJK chars ≈ 1000 tokens
        val english = result("abcd efgh ".repeat(200))    // 2000 chars ≈ 450 tokens
        // The old ranking (chars/3) put English first ...
        assertTrue(BPETokenizer.countTokens(english.content) > BPETokenizer.countTokens(cjk.content))
        // ... the meter's estimate puts the CJK part, the larger one, first.
        assertTrue(ContextSizeMeter.estimateTokens(cjk) > ContextSizeMeter.estimateTokens(english))
    }

    @Test fun `freed tokens are on the calibrated scale`() {
        val part = result("x".repeat(4500))
        val est = ContextSizeMeter.estimateTokens(part)
        assertTrue(ContextSizeMeter.calibrated(est, 2.0) >= est * 2)
    }

    // [T-android-offload-scan-range] 上游 1.14 的 offload 是 ChatViewModel.kt 的私有成员
    // （offloadScanStartIndex 在 10055，offloadContextIfNeeded 在 10136）；本移植把它拆进
    // ui/chat/ChatViewModelOffloadExt.kt 的扩展函数，所以扫描面跟着搬家——被断言的语义
    // （扫描从压缩锚点之后开始、排序与计账用校准过的 ContextSizeMeter）一字未改。
    private val vm by lazy { ProductionSources.read("ui/chat/ChatViewModelOffloadExt.kt") }

    /**
     * 从 [signature] 起括号配平地截出函数体（含首尾花括号）。
     *
     * 上游测试用的是 `indexOf("\n    }\n")`：那依赖「函数是 4 空格缩进的成员、且体内
     * 没有更浅的收尾」。本移植的实现是**顶层扩展函数**（收尾 `\n}\n`），照抄会截出空串
     * 让断言在空气里变绿，所以改成配平截取。
     */
    private fun functionBody(signature: String): String {
        val start = vm.indexOf(signature)
        require(start >= 0) { "missing $signature" }
        val open = vm.indexOf('{', start)
        var depth = 0
        var i = open
        while (i < vm.length) {
            when (vm[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return vm.substring(open, i + 1)
            }
            i++
        }
        error("unbalanced $signature")
    }

    private fun offloadBody(): String =
        functionBody("internal fun ChatViewModel.offloadContextIfNeeded(")

    @Test fun `drift guard - the scan starts after the compaction anchor`() {
        val body = offloadBody()
        assertTrue(body.contains("val scanStart = minOf(offloadScanStartIndex(), candidateUpper)"))
        assertTrue(body.contains("for (msgIdx in warmUpIndices + (scanStart until candidateUpper))"))
        assertTrue(body.contains("agentHistory.subList(answeredFrom, agentHistory.size)"))
        // [T-android-offload-warmup-scan] pruned warm-up parts are skipped
        assertTrue(body.contains("if (prunedId != null && prunedId in warmUp.second) continue"))
        assertFalse(body.contains("for (msgIdx in 0 until candidateUpper)"))
    }

    @Test fun `drift guard - start index degrades to 0 exactly like the effective history`() {
        val fn = functionBody("internal fun ChatViewModel.offloadScanStartIndex(): Int {")
        assertTrue(fn.contains("_compactSummary.value.isNullOrBlank()) return 0"))
        assertTrue(fn.contains("?: return 0"))
        assertTrue(fn.contains("marker.version < 2) return 0"))
        assertTrue(fn.contains("if (anchorIdx < 0) 0 else anchorIdx + 1"))
    }

    @Test fun `drift guard - ranking and accounting use the calibrated meter, not BPE`() {
        val body = offloadBody()
        assertFalse(body.contains("countPartTokens("))
        assertTrue(body.contains("ContextSizeMeter.estimateTokens(part)"))
        assertTrue(body.contains("ContextSizeMeter.calibrated(candidate.tokens, calibrationRatio)"))
        assertTrue(body.contains("currentTokens -= candidateTokens"))
    }
}
