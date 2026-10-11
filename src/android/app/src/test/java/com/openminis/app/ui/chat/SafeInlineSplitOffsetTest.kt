package com.openminis.app.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-inline-code-poisons-split] `safeInlineSplitOffset` decides how much
 * of a streaming message can be frozen and reused instead of re-parsed on every
 * throttle tick. Returning 0 means "no safe boundary" → the WHOLE message is
 * re-parsed, every tick.
 *
 * The reported bug (2026-09-02): a long reply kept flickering between styled and
 * raw markdown — already-bold text dropped back to literal `**…**` and re-styled
 * a moment later, repeatedly. Cause: this scanner counted `**` by parity but did
 * not know that parseInline resolves an inline-code span FIRST. One stray
 * backtick-wrapped `**` (a glob, an `ls **` example, `` `a**b` ``) therefore
 * flipped boldStar with nothing to flip it back, so no later newline could ever
 * be marked safe and the offset was pinned at 0 for the rest of the stream.
 *
 * These tests pin both halves: the offset must recover after code spans, and the
 * split must stay SEMANTICALLY safe (the caller concatenates
 * `parse(prefix) + parse(suffix)`, so a boundary inside an open span would
 * corrupt the render).
 */
class SafeInlineSplitOffsetTest {

    /** Longer than INCREMENTAL_MIN_CHARS(1500) + INCR_TAIL_MARGIN(256). */
    private fun pad(lines: Int = 90) =
        (1..lines).joinToString("\n") { "$it. 这是一行足够长的正文内容用于撑开增量解析的阈值" } + "\n"

    @Test
    fun `inline code containing double star does not pin the offset at zero`() {
        val text = "1. 通配符写法 `a**b` 注意\n" + pad() + "z".repeat(300)
        val offset = safeInlineSplitOffset(text)
        assertTrue(
            "a `**` INSIDE inline code is a literal to parseInline and must not " +
                "leave the bold counter open — offset was $offset (0 means the " +
                "whole message re-parses every tick, which is the reported flicker)",
            offset > 0,
        )
    }

    @Test
    fun `offset advances as the stream grows`() {
        val head = "1. 通配符 `x**y` 说明\n" + pad()
        val short = head + "z".repeat(300)
        val long = head + pad(40) + "z".repeat(300)
        val a = safeInlineSplitOffset(short)
        val b = safeInlineSplitOffset(long)
        assertTrue("offset must be usable on the shorter buffer, got $a", a > 0)
        assertTrue(
            "a longer buffer must freeze at least as much as the shorter one " +
                "(short=$a long=$b); a shrinking boundary re-parses settled text",
            b >= a,
        )
    }

    @Test
    fun `unclosed backtick is treated as a literal, matching parseInline`() {
        // findInlineCodeClose stops at '\n', so this backtick never closes.
        // parseInline emits it literally; the scanner must agree and keep
        // scanning rather than swallowing the rest of the line.
        val text = "1. 孤立反引号 ` 后面还有 **加粗** 内容\n" + pad() + "z".repeat(300)
        val offset = safeInlineSplitOffset(text)
        assertTrue("an unclosed backtick must not pin the offset, got $offset", offset > 0)
    }

    @Test
    fun `cross-line bold span is not split mid-span`() {
        // 真正「开着」的粗体：闭合符在换行之后，parseInline 会跨行渲染成粗体，
        // 所以切点必须留在跨度之前。（找不到闭合的 `**` 不在此列——parseInline 逐字
        // 输出，切它是安全的，见下一个用例。）
        val text = spanOpensBeforeTailMargin("**这段加粗跨行还没结束", "**")
        val spanStart = text.indexOf("**")
        val offset = safeInlineSplitOffset(text)
        assertTrue(
            "a bold span closing after the newline must keep the boundary before it " +
                "(spanStart=$spanStart, offset=$offset)",
            offset in 1 until spanStart,
        )
    }

    @Test
    fun `an unclosed double star is literal on both sides so it must not pin the offset`() {
        // parseInline 对找不到闭合的 `**` 是逐字符字面输出（第一颗星按字面量前进 1，
        // 第二颗星再按单分隔符规则处理；这里后面同样没有任何 `*`）。所以切开它是
        // **语义安全**的。旧 scanner 的 `**` 奇偶开关一旦打开就再也关不上，offset 被
        // 永久钉在 0 —— 与 `2*3` / “未闭合反引号”同一类抖动，只是触发器是 `**`。
        val text = "1. 开头 **还没有闭合\n" + pad() + "z".repeat(300)
        val offset = safeInlineSplitOffset(text)
        assertTrue(
            "an unclosed `**` is literal on both sides and must not pin the boundary, got $offset",
            offset > 0,
        )
    }

    @Test
    fun `underscore run inside a star span does not fake a balanced boundary`() {
        // 最小反例之一：`*_*_` + 换行 … `_`。parseInline 在 `*` 处配对到下一个 `*`
        // （中间的 `_` 是被消费的**内容**，不是分隔符），紧跟其后的 `_` 才是新开的
        // 跨度，闭合在换行之后。旧 scanner 把内容里的 `_` 也当分隔符，于是那个换行
        // 被误判成安全切点：prefix 把 `_` 当字面量、整段却渲染成斜体。
        val text = spanOpensBeforeTailMargin("*_*_", "_")
        val spanStart = text.indexOf('*')
        val offset = safeInlineSplitOffset(text)
        assertTrue(
            "boundary must stay before the open `_…_` span (spanStart=$spanStart, offset=$offset)",
            offset in 1 until spanStart,
        )
    }

    @Test
    fun `delimiters inside inline paren math do not cancel an outer open span`() {
        // 最小反例之二：`\(x *y\) and *open` + 换行 … `*`。parseInline 把
        // `\(x *y\)` 整段当行内公式消费（内容里的 `*` 不是分隔符），`*open` 开的新
        // 跨度闭合在换行之后。旧 scanner 没跳过公式内容：它把公式里的 `*` 记成开、
        // 又把 `*open` 记成闭，于是换行被判成安全切点。
        val text = spanOpensBeforeTailMargin("\\(x *y\\) and *open", "*")
        val spanStart = text.indexOf("*open")
        val offset = safeInlineSplitOffset(text)
        assertTrue(
            "boundary must stay before the `*open` span (spanStart=$spanStart, offset=$offset)",
            offset in 1 until spanStart,
        )
    }

    @Test
    fun `spliced inline parse equals the whole parse for cross-line spans`() {
        val colors = MdColors(
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
        val samples = listOf(
            spanOpensBeforeTailMargin("*这段强调跨行还没结束", "跨度结束*"),
            spanOpensBeforeTailMargin("_这段强调跨行还没结束", "跨度结束_"),
            spanOpensBeforeTailMargin("\\(a + b", "公式结束\\)"),
            // [T-incremental-inline-parity-mirror] 旧 scanner 与 parseInline 判据漂移的
            // 两个最小反例：切点曾落在未闭合跨度内部。
            spanOpensBeforeTailMargin("*_*_", "_"),
            spanOpensBeforeTailMargin("\\(x *y\\) and *open", "*"),
            spanOpensBeforeTailMargin("**这段加粗跨行还没结束", "**"),
            // 找不到闭合的 `**`：两边都是字面量，切点可以推进
            "1. 开头 **还没有闭合\n" + pad() + "z".repeat(300),
            // 已闭合在正文中部（切点能推进）的对照样本
            "1. 开头 *跨行\n强调* 之后\n" + pad() + "z".repeat(300),
            "1. 开头 \\(a+b\n公式\\) 之后\n" + pad() + "z".repeat(300),
            // `$…$` 内容同样被 parseInline 消费：内容里的 `*` 不得参与奇偶判断
            "1. 开头 \$x *y\$ 与 *开着的跨度\n还没闭合* 之后\n" + pad() + "z".repeat(300),
        )
        for (text in samples) {
            val offset = safeInlineSplitOffset(text)
            assertTrue("expected a usable boundary in sample, got $offset", offset > 0)
            val whole = parseInline(text, colors)
            val spliced = buildAnnotatedString {
                append(parseInline(text.substring(0, offset), colors))
                append(parseInline(text.substring(offset), colors))
            }
            assertEquals(
                "parseInline(prefix) ++ parseInline(suffix) must equal parseInline(whole) " +
                    "at offset=$offset",
                whole,
                spliced,
            )
        }
    }

    @Test
    fun `offset always lands right after a newline`() {
        val text = "1. 代码 `a**b` 与 **加粗** 混排\n" + pad() + "z".repeat(300)
        val offset = safeInlineSplitOffset(text)
        assertTrue("expected a usable offset, got $offset", offset > 0)
        assertEquals(
            "the split must sit immediately after a '\\n' — inline constructs " +
                "reset at line boundaries, which is what makes the split safe",
            '\n',
            text[offset - 1],
        )
    }

    @Test
    fun `short input yields no split`() {
        assertEquals(0, safeInlineSplitOffset("1. 很短的内容\n"))
    }

    // ─── [T-incremental-inline-single-delims] ────────────────────────────────
    // parseInline 的单分隔符斜体（`*…*` / `_…_`）与 `\(…\)` 都用普通 indexOf
    // 找闭合，能跨 `\n`；而 `$…$` / 行内代码的闭合扫描止于换行。旧 scanner 只
    // 跟踪 `**`/`__`/`~~`/链接，于是切点可以落在未闭合的跨行跨度里：
    // parseInline(prefix) 把 `*` 当字面量、parseInline(full) 渲染成斜体，
    // `prefix ++ suffix != whole` —— 而且这个结果会被 prewarmLiveTail 写进
    // 全量键，冻结后定格。

    /** 跨度开在倒数 256 字符之前、闭合在尾窗内：旧实现会把切点放进跨度里。 */
    private fun spanOpensBeforeTailMargin(open: String, close: String) =
        pad() + "结尾 $open\n" + "填充".repeat(200) + close

    @Test
    fun `cross-line italic span is not split mid-span`() {
        val text = spanOpensBeforeTailMargin("*这段强调跨行还没结束", "跨度结束*")
        val spanStart = text.indexOf('*')
        val offset = safeInlineSplitOffset(text)
        assertTrue(
            "the boundary must stay BEFORE the open `*…*` span (spanStart=$spanStart, " +
                "offset=$offset): a boundary inside it renders the prefix as a literal `*` " +
                "while the whole-text parse renders italic",
            offset in 1 until spanStart,
        )
    }

    @Test
    fun `cross-line paren math is not split mid-span`() {
        val text = spanOpensBeforeTailMargin("\\(x + y", "公式结束\\)")
        val spanStart = text.indexOf("\\(")
        val offset = safeInlineSplitOffset(text)
        assertTrue(
            "the boundary must stay BEFORE the open `\\(…\\)` span (spanStart=$spanStart, " +
                "offset=$offset)",
            offset in 1 until spanStart,
        )
    }

    @Test
    fun `a lone delimiter without a closer does not pin the offset`() {
        // `2*3` / `foo_bar` 这类孤立分隔符在全文里找不到闭合，parseInline 的
        // indexOf 返回 -1，两边都按字面输出 —— 切开它是安全的，不能把切点永久
        // 钉死在 0（那正是 T-android-inline-code-poisons-split 修掉的抖动）。
        val text = "1. 计算 2*3=6 与 foo_bar 说明\n" + pad() + "z".repeat(300)
        val offset = safeInlineSplitOffset(text)
        assertTrue("a closer-less delimiter must not pin the boundary, got $offset", offset > 0)
    }
}
