package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-ctx-usage-after-compact] After a manual or automatic compaction, the
 * composer placeholder kept showing the pre-compaction "Context NN% used"
 * line. The composer holds the last presented line until typing and ignores
 * a null hint, so only a NEW generation replaces it, and compaction never
 * issued one. The glow was already fine: [publishMeasuredContextUsage] sets
 * `_lastTurnContextTokens`, from which `contextUsage` is derived.
 *
 * Source guards on the wiring, plus the tier/percent the new line reports.
 * Mirrors iOS ContextUsageAfterCompactTests.
 */
class ContextUsageAfterCompactTest {

    /**
     * [T-linux-port-chatviewmodel-split] 上游 1.14 的 ChatViewModel 是**一个**文件
     * （17571 行），所以上游守卫只读 `ui/chat/ChatViewModel.kt`。本移植按职责把它拆成
     * `ChatViewModel.kt` + `ChatViewModelXxxExt.kt`（压缩流程在
     * ChatViewModelCompactExt.kt，用量提示在 ChatViewModel.kt）：只读单文件时，
     * `if (compactSucceeded) runCatching {` 的落点（ChatViewModel.kt 的压缩收尾入口）
     * 与终结锚点 `onFinished?.invoke(compactSucceeded)`（CompactExt 的 finally）分居两
     * 文件，窗口根本取不到。因此扫描面改为**整个 ChatViewModel* 文件集**，按文件名排序
     * 拼接：`ChatViewModel.kt` 排在所有 `ChatViewModelXxxExt.kt` 之前，第一个锚点仍在
     * 收尾入口处，窗口语义（先测量、后播报）与断言一条都不变。
     */
    private val vm by lazy {
        val dir = ProductionSources.mainRoot()?.let { File(it, "ui/chat") }
            ?: File("src/main/java/com/openminis/app/ui/chat")
        val files = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("ChatViewModel") && it.name.endsWith(".kt") }
            .orEmpty()
        require(files.isNotEmpty()) { "ChatViewModel sources not found under ${dir.absolutePath}" }
        files.sortedBy { it.name }
            .joinToString("\n// ── [T-linux-port-chatviewmodel-split] file boundary ──\n") { it.readText() }
    }

    private val announceBody: String by lazy {
        val start = vm.indexOf("private fun announceContextUsageAfterCompaction()")
        assertTrue("announceContextUsageAfterCompaction not found", start >= 0)
        val end = vm.indexOf("\n    }\n", start)
        vm.substring(start, end)
    }

    @Test
    fun `a successful compaction measures, then announces`() {
        val tail = vm.substringAfter("if (compactSucceeded) runCatching {")
            .substringBefore("onFinished?.invoke(compactSucceeded)")
        val measure = tail.indexOf("publishMeasuredContextUsage()")
        val announce = tail.indexOf("announceContextUsageAfterCompaction()")
        assertTrue("measure before announce", measure in 0 until announce)
    }

    @Test
    fun `the announcement is a new generation built from the measured size`() {
        assertTrue(announceBody.contains("usedTokens = _lastTurnContextTokens.value"))
        assertTrue(announceBody.contains("contextHintGeneration += 1"))
        assertTrue(announceBody.contains("_contextUsageHint.value = ContextUsageHint("))
    }

    @Test
    fun `it is shown at any tier, unlike the turn-end line`() {
        // publishContextUsage returns early at NORMAL; the post-compaction line
        // must not, or a drop to 20% would leave the stale 85% line on screen.
        assertFalse(announceBody.contains("Tier.NORMAL) return"))
    }

    @Test
    fun `it resets the tier baseline without markFired`() {
        assertTrue(announceBody.contains("contextTierTracker.observe(usage.tier, System.currentTimeMillis())"))
        assertFalse(announceBody.contains("markFired("))
    }

    @Test
    fun `helper view models stay silent`() {
        assertTrue(announceBody.contains("if (isHelper) return"))
    }

    @Test
    fun `the reported figure is the post-compaction size`() {
        val before = ContextUsage.from(usedTokens = 170_000, windowTokens = 200_000)!!
        val after = ContextUsage.from(usedTokens = 40_000, windowTokens = 200_000)!!
        assertEquals(ContextUsage.Tier.CRITICAL, before.tier)
        assertEquals(ContextUsage.Tier.NORMAL, after.tier)
        assertEquals(20, after.percent)
    }
}
