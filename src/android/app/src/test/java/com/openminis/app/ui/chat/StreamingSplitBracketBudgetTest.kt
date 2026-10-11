package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-streaming-split-bracket-budget] 链接 / 图片的闭合查找必须带预算：
 * `[` 密集而没有 `]`（或 `](` 密集而没有 `)`）时，旧实现让每个 `[` 都 `indexOf` 到
 * 串尾，退化成 O(n²) —— 而 [safeInlineSplitOffset] 跑在主线程上，每个 throttle tick
 * 一次。
 *
 * 上界 1s 是**实测定标**的（独立 JVM，同一台机器，修复前 / 修复后）：
 *
 * | 输入                        | 修复前   | 修复后 |
 * |-----------------------------|----------|--------|
 * | `[` × 500k                  |  1571 ms |  25 ms |
 * | `[` × 1M                    |  5080 ms |  21 ms |
 * | `[` × 2M                    | 20619 ms |  42 ms |
 * | `[x](` × 125k / 250k / 500k | 320 / 1273 / 5135 ms | 19 / 22 / 43 ms |
 *
 * 修复前每翻倍 ×4（二次），修复后基本持平（线性）。所以 1s 既高于修复后三个数量级，
 * 又低于修复前 1M 输入的口径——它不是「差不多快」的阈值，而是两类复杂度之间的分界。
 * 万一将来回归，这个用例会跑满几秒然后失败，而不是挂住。
 */
class StreamingSplitBracketBudgetTest {

    /** 断言「快 + 语义不变」：这些输入里没有可切的换行，切点必须是 0。 */
    private fun assertFastAndUnsplit(name: String, text: String) {
        val t0 = System.nanoTime()
        val offset = safeInlineSplitOffset(text)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(
            "$name：没有换行可切，必须返回 0（返回 $offset 说明扫描语义变了）",
            0,
            offset,
        )
        assertTrue(
            "$name：${text.length} 字符用了 ${ms}ms —— 闭合查找退化回无预算的线性重扫了" +
                "（修复前 O(n²)：1M 的 `[` 要 ~5s，翻倍 ×4）",
            ms < 1_000,
        )
    }

    @Test
    fun `dense open brackets with no closer stay linear`() {
        assertFastAndUnsplit("`[` 密集且没有 `]`", "[".repeat(1_000_000))
    }

    @Test
    fun `a single far-away closer does not make every opener rescan it`() {
        // 只有一个 `]` 且在最右端：旧实现里每个 `[` 都要扫到它，仍是 O(n²)。
        assertFastAndUnsplit("`[` 密集、`]` 在最右端", "[".repeat(1_000_000) + "]")
    }

    @Test
    fun `dense link closers with no closing paren stay linear`() {
        // `](` 密集而没有 `)`：这次是闭合括号那一次查找被每个 `](` 重扫。
        assertFastAndUnsplit("`](` 密集且没有 `)`", "[x](".repeat(500_000))
    }

    @Test
    fun `dense brackets before a real body still split at the last safe newline`() {
        // 预算只影响成本，不影响语义：真正可切的换行照旧被选中。
        val text = "[".repeat(5_000) + "\n" + pad() + "z".repeat(300)
        assertEquals(7_512, safeInlineSplitOffset(text))
    }

    @Test
    fun `ordinary links and images keep their previous boundary`() {
        // 非回归：普通链接 / 图片（闭合都在近处）的切点与修复前逐个相同。
        val text = "1. 链接 [a](b) 与 ![img](c) 之后\n" + pad() + "z".repeat(300)
        assertEquals(2_539, safeInlineSplitOffset(text))
    }

    /** 与 SafeInlineSplitOffsetTest 同一份夹具：撑开增量解析阈值 + 尾窗。 */
    private fun pad(lines: Int = 90) =
        (1..lines).joinToString("\n") { "$it. 这是一行足够长的正文内容用于撑开增量解析的阈值" } + "\n"
}
