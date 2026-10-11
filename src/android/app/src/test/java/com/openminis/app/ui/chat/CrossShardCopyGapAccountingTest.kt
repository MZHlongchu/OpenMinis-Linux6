package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-cross-shard-copy-span-blind] 跨分片复制里**枚举不到**的中间分片必须计成缺口。
 *
 * 已回收（不在注册表里）又取不到数字序号的分片既排不进文档序、也不在窗口 y 序里：
 * 旧实现只遍历当前注册的分片，这些分片连着「缺口」都计不进去 —— 复制结果少一段却
 * 没有任何提示（静默缺口只是换了个触发器）。现在 [SelectionController] 的跨度枚举
 * 会带一个「枚举是否可信」的判据，不可信就计缺口：能定位 markdown 锚点就改用原文
 * 选中段，否则把缺口写进结果。
 *
 * 同时钉住 [T-cross-shard-copy-docmap-char-budget]：文档级文本表不能只按条数淘汰。
 */
class CrossShardCopyGapAccountingTest {

    private fun shard(message: String, block: Int): TextShardId =
        TextShardId(message, "mdblock:$message:$block")

    /** `text:<blockId>` 形态：取不到数字序号，只能靠窗口 y 序。 */
    private fun unkeyed(message: String, name: String): TextShardId =
        TextShardId(message, "text:$name")

    private fun controllerWithText(vararg texts: Pair<TextShardId, String>) =
        SelectionController().apply {
            for ((id, text) in texts) rememberShardText(id, text)
        }

    @Test
    fun `a recycled middle shard we cannot order is reported as a gap`() {
        val head = unkeyed("m1", "blk-a")
        val middle = unkeyed("m1", "blk-b")
        val tail = unkeyed("m1", "blk-c")
        val c = controllerWithText(
            head to "HEADTEXT",
            middle to "MIDDLETEXT",
            tail to "TAILTEXT",
        )
        c.selection.value = TextSelection(TextPosition(head, 0), TextPosition(tail, 4))

        val out = c.selectedPlainText()
        assertTrue("已知的两端照常复制，out=<$out>", out.startsWith("HEADTEXT"))
        assertTrue(
            "中间分片看不见就必须标注缺口（旧实现静默少一段），out=<$out>",
            out.contains("已被回收"),
        )
    }

    @Test
    fun `unorderable endpoints with nothing in between do not claim a gap`() {
        // 反向对照：跨度里真的没有别的已知分片时不许谎报缺口（提示必须是可信的）。
        val head = unkeyed("m2", "blk-a")
        val tail = unkeyed("m2", "blk-b")
        val c = controllerWithText(head to "HEADTEXT", tail to "TAILTEXT")
        c.selection.value = TextSelection(TextPosition(head, 4), TextPosition(tail, 4))

        assertEquals("TEXT\nTAIL", c.selectedPlainText())
    }

    @Test
    fun `sub-indexed siblings still order without claiming a gap`() {
        // [T-cross-shard-copy-recycled] 同根 + 子序号是可信的文档序，不能因为「无块序号」
        // 就退化成缺口提示。
        val s0 = TextShardId("m3", "text:blk-7#0")
        val s1 = TextShardId("m3", "text:blk-7#1")
        val s2 = TextShardId("m3", "text:blk-7#2")
        val c = controllerWithText(s0 to "AAA", s1 to "BBB", s2 to "CCC")
        c.selection.value = TextSelection(TextPosition(s0, 1), TextPosition(s2, 1))

        assertEquals("AA\nBBB\nC", c.selectedPlainText())
    }

    @Test
    fun `the document text table evicts on a character budget not only an entry cap`() {
        // 201 条 × 4096 字符 = 823K > 512K 字符预算，但远低于 4096 条上限：旧实现
        // （只按条数淘汰）会一直攒着，最久未读的分片文本永远不释放。
        val c = SelectionController()
        val first = shard("m4", 0)
        c.rememberShardText(first, "A".repeat(4_096))
        for (i in 1..200) c.rememberShardText(shard("m4", i), "B".repeat(4_096))

        c.selection.value = TextSelection(TextPosition(first, 0), TextPosition(first, 8))
        val out = c.selectedPlainText()
        assertTrue(
            "字符预算必须真的淘汰最久未读的分片，out=<$out>",
            out.contains("已被回收"),
        )
    }

    @Test
    fun `a single shard larger than the character budget still survives`() {
        // 不变式（与 MarkdownParseCaches.Lru 同一条）：单条超大分片不能被自己的预算
        // 挤掉，否则端点落在它里面时，跨分片复制在回收后彻底取不到文本。
        val c = SelectionController()
        val huge = shard("m5", 0)
        c.rememberShardText(huge, "X".repeat(600_000))
        c.selection.value = TextSelection(TextPosition(huge, 0), TextPosition(huge, 5))

        assertEquals("XXXXX", c.selectedPlainText())
    }

    @Test
    fun `the gap notice text comes from the installed template`() {
        // [T-cross-shard-copy-gap-notice-i18n] 文案本体在 values/strings.xml（含 zh），
        // 由 RegisterSelectionShard 在组合期装进来；控制器只做 %1$d 占位替换。
        val c = SelectionController()
        c.gapNoticeTemplate = "MISSING %1\$d FRAGMENT(S)"
        val s = shard("m6", 7)
        c.selection.value = TextSelection(TextPosition(s, 0), TextPosition(s, 2))

        assertEquals("MISSING 1 FRAGMENT(S)", c.selectedPlainText())
    }

    @Test
    fun `the fallback template keeps the placeholder the resource must carry`() {
        assertEquals(
            "兜底串（无 Context 的 JVM 路径）与 values-zh 的资源串必须同形，只有占位符被换掉",
            "[…… 选区中有 7 个分片的文本已被回收，复制到的内容不完整 ……]",
            DEFAULT_GAP_NOTICE_TEMPLATE.replace("%1\$d", "7"),
        )
    }
}
