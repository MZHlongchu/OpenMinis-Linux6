package com.openminis.app.ui.chat

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [T-android-incremental-cache-key] 增量（切点 + 拼接）结果不得占住全量解析的权威
 * 缓存键。
 *
 * 旧实现里 prewarmLiveTail 算完增量结果后直接把它写进 `inlineLru[raw]` /
 * `mathLru[raw]` —— 那正是 `inline()` / `mathLatex()` 的键。切点一旦选错
 * （[safeInlineSplitOffset] 与 parseInline 判据漂移），错误结果就成了该文本的
 * 「全量解析」：冻结分支与后续所有缓存命中都读到它，直到 LRU 淘汰（用户看到的就是
 * 样式定格在错误形态）。现在增量结果进它自己的缓存，权威键只由真全量解析写。
 */
class StreamingMarkdownIncrementalCacheTest {

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

    /** 超过 INCREMENTAL_MIN_CHARS(1500) + INCR_TAIL_MARGIN(256)，且有安全切点。 */
    private fun liveParagraph(): String = buildString {
        append("1. 开头 **加粗** 与 `代码` 混排\n")
        repeat(90) { append("$it. 这是一行足够长的正文内容用于撑开增量解析的阈值\n") }
        append("z".repeat(300))
    }

    @Test
    fun `prewarm live tail does not occupy the authoritative inline key`() {
        val raw = liveParagraph()
        MarkdownParseCaches.prewarmLiveTail(listOf(MdBlock.Paragraph(raw)), colors)

        val incremental = MarkdownParseCaches.inlineIncremental(raw, colors)
        val authoritative = MarkdownParseCaches.inline(raw, colors)

        // 1) 增量结果仍必须等于真全量解析 —— parity 是这套缓存成立的立足点。
        assertEquals(parseInline(raw, colors), incremental)
        assertEquals(parseInline(raw, colors), authoritative)
        // 2) 权威键里必须是 inline() 自己那次真全量解析的结果（对象不同即证明
        //    prewarm 没有把增量结果写进权威键）。
        assertNotSame(
            "增量结果不得成为权威全量键的值：切点选错就会让错误样式定格",
            incremental,
            authoritative,
        )
    }

    @Test
    fun `incremental inline is memoized under its own key`() {
        val raw = liveParagraph()
        val first = MarkdownParseCaches.inlineIncremental(raw, colors)
        val second = MarkdownParseCaches.inlineIncremental(raw, colors)
        // 没有 prewarm 时旧实现每个 tick 都要重跑切点扫描 + 尾段解析 + 拼接。
        assertSame("同一 raw 的增量结果要按自己的键命中", first, second)
        assertEquals(parseInline(raw, colors), first)
    }
}
