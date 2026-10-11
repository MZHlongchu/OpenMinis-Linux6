package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge cases of a model switch that lands while a turn is running.
 *
 * Guards: a43ad4134 (T-android-switch-model-ghost-retry), 8aa6b373b
 * (T-android-switch-model-hang), aa3aab6bb (T-android-switch-model-seam-cleanup)
 * and ef4a06e4d (T-android-switch-model-let-stream-finish). Extends
 * [SwitchModelGhostRetryTest], which pins the seam's teardown scope and the
 * selector wiring; this file covers what that one does not:
 *
 *  1. The "bound to the abandoned model" predicate reads the retry-ladder
 *     counters, but `_autoRetryAttempt` stays non-zero for the WHOLE retried
 *     attempt — it is only cleared when that stream completes. A turn that
 *     needed one retry and is now streaming normally therefore still reads as
 *     a ghost retry, and a model switch cancels it into "tap Resume": the
 *     ef4a06e4d regression, on every turn that retried once. iOS clears the
 *     counter the moment the stream opens (streamWithAutoRetry).   (BUG today)
 *  2. `selectEntry` swaps the class-level `currentModel` immediately, while the
 *     running turn keeps its turn-local provider. The turn's thinking gate
 *     reads the class-level model, so the rest of the turn sends the NEW
 *     model's reasoning decision to the OLD model.                 (BUG today)
 *  3. Documented parity decision: Android applies a mid-turn switch at the
 *     next SEND (the turn-local provider is declared outside the turn loop);
 *     iOS (86a045284) applies it at the next REQUEST. Pinned so a port of the
 *     iOS behaviour updates this test deliberately.
 */
class SwitchModelMidTurnEdgeTest {

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing source: $path", f.exists())
        return f.readText()
    }

    // [T-android-switch-model-seam-cleanup] runAgentLoop 在本移植的合并收敛期里两个位置
    // 之间搬（ChatViewModel.kt 的成员 / ChatViewModelAgentLoopExt.kt 的顶层扩展），
    // 写死任一个都会在搬家时「missing source」——扫描面按签名定位，找不到才失败。
    // 被断言的语义（首个 chunk 清阶梯计数、思考门读本回合 provider、turn-local provider
    // 声明在回合循环之外）一字未改。
    private val vmSrc by lazy {
        val dir = File("src/main/java/com/openminis/app/ui/chat")
        val sig = "fun ChatViewModel.runAgentLoop("
        val hits = dir.listFiles { f -> f.isFile && f.extension == "kt" }
            ?.filter { it.readText().contains(sig) }
            ?.sortedBy { it.name }
            ?: emptyList()
        // 一个都没有时退回单体文件读取，让 src() 报出「missing source: …」这条更直白的失败。
        if (hits.isEmpty()) src("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt")
        else hits.first().readText()
    }

    private val agentLoop by lazy {
        val start = vmSrc.indexOf("fun ChatViewModel.runAgentLoop(")
        // 找不到就必须炸：substringAfter 在缺分隔符时原样返回整个文件，
        // 那样下面的断言会在空气里变绿。
        assertTrue("runAgentLoop signature not found", start >= 0)
        vmSrc.substring(start)
    }

    /**
     * [T-agent-wrapup-turn] 回合流调用的参数块：tools 参数之后、`.collect` 之前。
     * 合并收敛期两种拼法都出现过（上游 1.14 的 helper 收尾三元式 / 本移植早期的
     * `tools = agentTools,` 简写），而「思考门读哪个 model」这条不变量与拼法无关——
     * 所以两种都认，都找不到才失败（而不是让 substrin 退化成整文件后假绿）。
     */
    private fun streamCallArgs(): String {
        val delims = listOf(
            "tools = if (helperWrapUpInjected) emptyList() else agentTools,",
            "tools = agentTools,",
        )
        for (d in delims) {
            val after = agentLoop.substringAfter(d, "")
            if (after.isNotEmpty()) return after.substringBefore(".collect { chunk ->")
        }
        error("tools argument of the turn's stream call not found (tried ${delims.size} spellings)")
    }

    /** [T-subagent-turn-countdown] 回合循环头：上游用局部 `turnCap`，本移植一度直接写 MAX_AGENT_TURNS。 */
    private fun turnLoopIndex(code: String): Int =
        listOf(
            "for (turn in 0 until turnCap)",
            "for (turn in 0 until ChatViewModel.MAX_AGENT_TURNS)",
        ).map { code.indexOf(it) }.firstOrNull { it >= 0 } ?: -1

    private fun stripComments(s: String): String =
        s.lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

    // ---- 1. ladder counter vs. a healthy retried stream --------------------

    /** Timeline model of the ladder counter. `resetOnFirstChunk` = the fix. */
    private fun counterWhileRetriedStreamIsHealthy(resetOnFirstChunk: Boolean): Int {
        var attempt = 0
        attempt = 1                     // transient error → ladder step 1
        /* countdown ... then the retried stream opens and a chunk arrives */
        if (resetOnFirstChunk) attempt = 0
        return attempt                  // the value the seam sees mid-stream
    }

    @Test
    fun `model - a healthy retried stream must not look like a ghost retry`() {
        assertEquals(
            "shipping: counter still 1 while the retried attempt streams → a switch cancels it",
            1, counterWhileRetriedStreamIsHealthy(resetOnFirstChunk = false),
        )
        assertEquals(0, counterWhileRetriedStreamIsHealthy(resetOnFirstChunk = true))
    }

    @Test
    fun `the ladder counter is cleared once the retried attempt starts producing output`() {
        assertTrue("runAgentLoop not found", agentLoop.length > 1000)
        val firstChunkBlock = agentLoop
            .substringAfter("if (firstChunkSeen.compareAndSet(false, true)) {", missingDelimiterValue = "")
            .substringBefore("when (chunk) {")
        assertTrue("first-chunk hook not found", firstChunkBlock.isNotEmpty())
        assertTrue(
            "isBoundToAbandonedModel() reads _autoRetryAttempt, which today is only reset when the " +
                "retried stream COMPLETES. A model switch during a healthy retried stream is then " +
                "treated as a ghost retry and cancels the turn. Reset _autoRetryAttempt / " +
                "_autoRetryCountdown on the first chunk (iOS resets on stream open).",
            stripComments(firstChunkBlock).contains("_autoRetryAttempt.value = 0"),
        )
    }

    // ---- 2. thinking gate follows the provider serving the turn ------------

    @Test
    fun `the in-flight turn gates thinking on its own provider, not the freshly picked model`() {
        val call = streamCallArgs()
        assertTrue("stream call not found", call.contains("thinkingLevel ="))
        assertFalse(
            "runAgentLoop's thinking gate reads the class-level currentModel " +
                "(currentModelSupportsReasoning), which selectEntry swaps immediately, while the request " +
                "goes to the turn-local provider. Gate on currentProvider.model.supportsReasoning instead.",
            call.contains("currentModelSupportsReasoning"),
        )
    }

    // ---- 3. documented parity decision -------------------------------------

    @Test
    fun `a mid-turn switch applies at the next send (turn-local provider outlives every round)`() {
        val code = stripComments(agentLoop)
        val localProvider = code.indexOf("var currentProvider = provider")
        // [T-android-switch-model-next-request] 合并收敛期回合上限有两种写法（上游的局部
        // `turnCap` / 本移植的 `ChatViewModel.MAX_AGENT_TURNS`）；被断言的仍然是
        // 「turn-local provider 声明在回合循环之外」，所以两种都认。
        //
        // 注：本移植已经采纳了 iOS 86a045284 的「下一请求生效」（循环顶部的
        // pendingModelSwitch 分支，[T-android-switch-model-next-request]），但那个分支
        // 是**重新赋值**同一个循环外声明的 currentProvider，所以这条不变式依旧成立、
        // 依旧值得钉住：回合循环内部不会新建 provider，飞行中的请求不受影响。
        val turnLoop = turnLoopIndex(code)
        assertTrue("turn-local provider not found", localProvider >= 0)
        assertTrue("turn loop not found", turnLoop >= 0)
        assertTrue(
            "Android applies a user switch at the next SEND (ef4a06e4d). iOS applies it at the next " +
                "REQUEST (86a045284, pendingModelSwitch). If you are porting that, update this test.",
            localProvider < turnLoop,
        )
    }
}
