package com.openminis.app.ui.chat

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-freeze-edge-offmain-inline] 流结束的冻结边不得在主线程做全量 inline
 * 解析。
 *
 * 成因链（本用例把前两环钉死）：
 *  1. 流式 tick 的 off-main 预暖走 [MarkdownParseCaches.prewarmLiveTail]，按
 *     [T-android-incremental-cache-key] **只**暖增量键；
 *  2. 冻结分支的 `RenderBlock` 走权威路径（`liveIncremental = false`），
 *     `inline(raw)` / `mathLatex(raw)` 于是必 MISS → 合成线程跑完整 parseInline
 *     （81% 卡顿栈所在的那一层）；
 *  3. 现在冻结分支用 [MarkdownParseCaches.isAuthoritativeParseCached] 看出「权威键
 *     还冷」，这一帧改走增量入口（键命中、零解析，值与真全量解析相同），同时把权威
 *     解析交给 off-main 预暖 [MarkdownParseCaches.prewarm]。
 *
 * 用例 1 证明「增量预暖之后权威键确实是冷的」（残留在），用例 2 证明「冻结边那次
 * off-main prewarm 之后权威键变暖且值与增量一致」（修复成立）。
 */
class StreamingMarkdownFreezeEdgeTest {

    private val colors = MdColors(
        text = Color(0xFF000000),
        codeText = Color(0xFF000000),
        codeBg = Color(0xFFEEEEEE),
        inlineCodeText = Color(0xFFAA0000),
        inlineCodeBg = Color(0xFFEEEEEE),
        link = Color(0xFF0000AA),
        blockquote = Color(0xFF666666),
        divider = Color(0xFFCCCCCC),
        tableBorder = Color(0xFFCCCCCC),
        tableHeaderBg = Color(0xFFF5F5F5),
    )

    /**
     * 超过 INCREMENTAL_MIN_CHARS(1500) + INCR_TAIL_MARGIN(256)，且有安全切点。
     * [tag] 让每个用例拿到**不同的 raw**：缓存是进程级的，断言「权威键是冷的」
     * 必须保证此前没有别的用例用同一串文本暖过它。
     */
    private fun liveParagraph(tag: String): String = buildString {
        append("$tag 开头 **加粗** 与 `代码` 混排\n")
        repeat(90) { append("$it. 这是 $tag 的一行足够长的正文内容用于撑开增量解析阈值\n") }
        append("z".repeat(300))
    }

    @Test
    fun `live tail prewarm leaves the authoritative key cold`() {
        val raw = liveParagraph("freeze-edge-probe-cold")
        MarkdownParseCaches.prewarmLiveTail(listOf(MdBlock.Paragraph(raw)), colors)

        assertFalse(
            "增量预暖后权威键仍必须是冷的：冻结分支若直接走 inline() 就会在主线程全量解析",
            MarkdownParseCaches.isAuthoritativeParseCached(raw, colors),
        )
        // 增量缓存里那个值就是对的值（parity），所以冻结边这一帧零解析也不会画错。
        assertEquals(parseInline(raw, colors), MarkdownParseCaches.inlineIncremental(raw, colors))
    }

    @Test
    fun `off-main authoritative prewarm flips the probe for the frozen tail`() {
        val raw = liveParagraph("freeze-edge-probe-warm")
        MarkdownParseCaches.prewarmLiveTail(listOf(MdBlock.Paragraph(raw)), colors)
        assertFalse(MarkdownParseCaches.isAuthoritativeParseCached(raw, colors))

        // 冻结分支的 off-main 预暖（LaunchedEffect + Dispatchers.Default）。
        MarkdownParseCaches.prewarm(listOf(MdBlock.Paragraph(raw)), colors)

        assertTrue(
            "预暖后权威键必须变暖，冻结边的重组才能零解析地改读权威值",
            MarkdownParseCaches.isAuthoritativeParseCached(raw, colors),
        )
        assertEquals(parseInline(raw, colors), MarkdownParseCaches.inline(raw, colors))
        assertEquals(collectInlineMathLatex(raw), MarkdownParseCaches.mathLatex(raw))
    }

    @Test
    fun `the probe needs both the inline and the math authority key`() {
        val raw = liveParagraph("freeze-edge-probe-math")
        // 只暖 inline、不暖 math：probe 必须仍然是冷 —— RenderBlock 的
        // rememberKatexInlineContent 会同步查 mathLatex(raw)，那是同一个主线程热点。
        MarkdownParseCaches.inline(raw, colors)

        assertFalse(
            "只用 inline() 暖是不够的：mathLatex(raw) 仍会在主线程全量扫描",
            MarkdownParseCaches.isAuthoritativeParseCached(raw, colors),
        )
        MarkdownParseCaches.mathLatex(raw)
        assertTrue(MarkdownParseCaches.isAuthoritativeParseCached(raw, colors))
    }

    @Test
    fun `the probe and the off-main prewarm are safe to overlap`() {
        val raw = liveParagraph("freeze-edge-probe-concurrent")
        MarkdownParseCaches.prewarmLiveTail(listOf(MdBlock.Paragraph(raw)), colors)

        // 冻结边的真实并发形状：Default 线程在 prewarm 权威键，主线程同时在探针与
        // 增量入口上读。每个调用点都只锁**一个**缓存（探针是两次顺序加锁，没有任何
        // 调用点同时持有两把锁），所以两条路径交叠不许抛异常；预暖结束后探针必须为真，
        // 且主线程读到的是同一个不可变结果。
        val writer = kotlin.concurrent.thread(name = "freeze-edge-prewarm") {
            repeat(3) { MarkdownParseCaches.prewarm(listOf(MdBlock.Paragraph(raw)), colors) }
        }
        repeat(200) { MarkdownParseCaches.isAuthoritativeParseCached(raw, colors) }
        writer.join()

        assertTrue(MarkdownParseCaches.isAuthoritativeParseCached(raw, colors))
        assertSame(
            "并发预暖之后主线程的读必须命中同一个不可变结果",
            MarkdownParseCaches.inline(raw, colors),
            MarkdownParseCaches.inline(raw, colors),
        )
    }
}
