package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-cross-shard-copy-recycled] 跨分片选区复制在端点分片**已被 LazyColumn 回收**
 * 之后仍须复制出完整选区。
 *
 * 旧实现的漏点与钉住它的用例：
 *  1. `started` 门控：首分片缺席时中间分片被整段跳过，末分片却仍被无条件追加
 *     → 只剩末分片的半截前缀；两端都回收则复制出空串 —— 用例 1 / 2；
 *  2. 文档序兜底 `shardOrderKey` 取 `substringAfterLast(':')`，被 `MdText` 的
 *     `#<子序号>` 后缀（StreamingMarkdownMdText）打成 null → 兜底永久失效 ——
 *     用例 2 / 3 / 4；
 *  3. 走查为空时静默返回 ""（Copy 变 no-op）—— 用例 5。
 *
 * [T-cross-shard-copy-gap-notice] 取不到文本的分片必须显式提示
 * （`an evicted endpoint…` / `unordered endpoints…` / `a single-shard selection…`），
 * 缺口分支改走 markdown 选中段时提示同样要保留（`the gap notice survives…`）。
 * [T-cross-shard-copy-anchor-offset-match] 24 字符锚点重复时必须按选区偏移定位
 * （`a repeated 24-char head anchor…`）。
 *
 * 期望值不是「读代码推的」，是**跑出来的**：E6 把本类依赖的 `SelectionController`
 * 真实源码与 Compose 类型桩一起编译后，用 JUnit 直接跑**本类**（`JUnitCore`），
 * 下面每一例都按最终形态跑通（旧期望 `"一段\n第二段\n三"` 就是没跑过的产物：
 * 尾端点取的是该分片 `substring(0, offset)`，offset=1 → 「第」）。
 *
 * 用 [SelectionController.rememberShardText] 直接喂分片文本：这就是「分片已回收、
 * 只剩文本」的状态，而真实的 `TextShard` 需要 TextLayoutResult（Compose 里是
 * `final class`，JVM 单测构造不了），所以「注册了但坐标未挂载」那条分支不在本文件
 * 的覆盖范围内。
 */
class CrossShardCopyRecycledTest {

    /** [anchorFor] 的锚点上限正好 24 字符：重复出现时的消歧用例需要这个长度。 */
    private val dupAnchor = "DUPLICATE-ANCHOR-1234567"

    private fun shard(message: String, block: Int, sub: Int? = null) = TextShardId(
        messageId = message,
        shardId = if (sub == null) "mdblock:$message:$block" else "mdblock:$message:$block#$sub",
    )

    /** `text:<blockId>` 形态：取不到数字序号，只能靠窗口 y 序（JVM 单测里没有坐标）。 */
    private fun unkeyed(message: String, name: String) = TextShardId(message, "text:$name")

    private fun controllerWithText(vararg texts: Pair<TextShardId, String>) =
        SelectionController().apply {
            for ((id, text) in texts) rememberShardText(id, text)
        }

    @Test
    fun `recycled endpoints still copy the whole selection not the tail prefix`() {
        val s0 = shard("m1", 0)
        val s1 = shard("m1", 1)
        val s2 = shard("m1", 2)
        val c = controllerWithText(s0 to "第一段", s1 to "第二段", s2 to "第三段")
        c.selection.value = TextSelection(TextPosition(s0, 1), TextPosition(s2, 1))

        // 头端点取 `substring(1)` = "一段"，尾端点取 `substring(0, 1)` = "第"。
        assertEquals("一段\n第二段\n第", c.selectedPlainText())
    }

    @Test
    fun `sub-indexed shard ids are ordered by document key`() {
        // 同一片段的多个 MdText 子分片：块序号相同、子序号定序。旧实现里这些 id
        // 解析不出序号，端点一回收就什么都复制不出来。
        val s0 = shard("m1", 1, 0)
        val s1 = shard("m1", 1, 1)
        val s2 = shard("m1", 1, 2)
        val c = controllerWithText(s0 to "AAA", s1 to "BBB", s2 to "CCC")
        c.selection.value = TextSelection(TextPosition(s0, 1), TextPosition(s2, 1))

        assertEquals("AA\nBBB\nC", c.selectedPlainText())
    }

    @Test
    fun `sibling sub-shards outside the selection are not swept in`() {
        // 头分片之前的兄弟（#0）与尾分片之后的兄弟（#5#1）都不在选区里，不能被
        // 「同一个块」的粗键收进来。
        val s0 = shard("m1", 1, 0)
        val head = shard("m1", 1, 1)
        val between = shard("m1", 3, 0)
        val tail = shard("m1", 5, 0)
        val after = shard("m1", 5, 1)
        val c = controllerWithText(
            s0 to "BEFORE",
            head to "HEAD",
            between to "MID",
            tail to "TAIL",
            after to "AFTER",
        )
        c.selection.value = TextSelection(TextPosition(head, 0), TextPosition(tail, 4))

        assertEquals("HEAD\nMID\nTAIL", c.selectedPlainText())
    }

    @Test
    fun `one block's sub-shards order by sub index without registration`() {
        // `text:<blockId>` 这类块 id 不是数字：同根之下子序号就是文档序（反向选区
        // 也要先按文档序排好再取片）。
        val c = SelectionController()
        val s0 = TextShardId("m1", "text:blk-7#0")
        val s1 = TextShardId("m1", "text:blk-7#1")
        c.rememberShardText(s0, "head")
        c.rememberShardText(s1, "tail")
        c.selection.value = TextSelection(TextPosition(s1, 2), TextPosition(s0, 1))

        assertEquals("ead\nta", c.selectedPlainText())
    }

    @Test
    fun `a selection that only covers a non-shard gap yields that markdown`() {
        // 选区从段落 1 末尾起、到段落 3 开头止：分片走查一个字都取不到，但用户选中
        // 的正是中间那段代码围栏。旧实现静默复制空串，Copy 变 no-op。
        val head = shard("m1", 0)
        val tail = shard("m1", 2)
        val c = controllerWithText(head to "第一段", tail to "第三段")
        c.rememberMessageMarkdown("m1", "第一段\n\n```kotlin\nval x = 1\n```\n\n第三段")
        c.selection.value = TextSelection(TextPosition(head, 3), TextPosition(tail, 0))

        assertEquals("```kotlin\nval x = 1\n```", c.selectedPlainText())
    }

    @Test
    fun `block keys keep sub-shard siblings out of the between test`() {
        // 高亮/命中测试共用的 isShardBetween：块序号 + 子序号严格比较，端点的兄弟
        // 子分片不算「在中间」，真正跨块的分片才算。
        val c = SelectionController()
        val head = shard("m1", 3, 1)
        val tail = shard("m1", 5, 0)

        assertFalse("头分片之前的兄弟", c.isShardBetween(head, tail, shard("m1", 3, 0)))
        assertFalse("尾分片之后的兄弟", c.isShardBetween(head, tail, shard("m1", 5, 1)))
        assertTrue("中间整块", c.isShardBetween(head, tail, shard("m1", 4, 0)))
        assertTrue("头块里更靠后的兄弟也确在跨度内", c.isShardBetween(head, tail, shard("m1", 3, 2)))
    }

    // ─── [T-cross-shard-copy-gap-notice] 取不到文本时的显式缺口 ──────────────

    @Test
    fun `an evicted endpoint is reported instead of silently dropping its segment`() {
        // 端点分片连文档级表都取不到（滚过 >MAX_DOCUMENT_SHARDS 个分片后被 LRU
        // 淘汰）时，旧实现 `textOf(...)?: continue` 静默丢掉整段尾片段——复制结果
        // 看起来完整、其实少了内容。现在必须能看见缺口。
        val head = shard("m1", 0)
        val missingTail = shard("m1", 2)
        val c = controllerWithText(head to "第一段")
        c.selection.value = TextSelection(TextPosition(head, 1), TextPosition(missingTail, 3))

        val out = c.selectedPlainText()
        assertTrue("已知的那段照常复制，out=<$out>", out.startsWith("一段"))
        assertTrue("缺口必须显式标注，out=<$out>", out.contains("已被回收"))
    }

    @Test
    fun `unordered endpoints with a missing sibling still copy what is known`() {
        // 端点没有数字序号（`text:<blockId>`）且都未注册 → orderedEndpoints 取不到
        // 文档序，走 crossShardSelectedText 的兜底路径；缺文本的那一段同样要标注。
        val head = TextShardId("m1", "text:blk-a")
        val tail = TextShardId("m1", "text:blk-b")
        val c = SelectionController().apply { rememberShardText(head, "HEADTEXT") }
        c.selection.value = TextSelection(TextPosition(head, 2), TextPosition(tail, 1))

        val out = c.selectedPlainText()
        assertTrue("已知的那段照常复制，out=<$out>", out.startsWith("ADTEXT"))
        assertTrue("缺口必须显式标注，out=<$out>", out.contains("已被回收"))
    }

    @Test
    fun `a single-shard selection with no text is not a silent empty copy`() {
        // 单分片选区的文本取不到时旧实现返回 ""：复制变成无声 no-op（连 toast 都不弹）。
        val s = shard("m1", 7)
        val c = SelectionController()
        c.selection.value = TextSelection(TextPosition(s, 0), TextPosition(s, 2))

        val out = c.selectedPlainText()
        assertTrue("空串是静默失败，out=<$out>", out.isNotEmpty())
        assertTrue("必须显式说明取不到文本，out=<$out>", out.contains("已被回收"))
    }

    // ─── [T-cross-shard-copy-gap-notice] 缺口分支走 markdown 选中段时不许吞提示 ──

    @Test
    fun `the gap notice survives the markdown-slice recovery`() {
        // 盲跨度（无序号 + 未注册）→ 计成缺口；两端锚点都能在该消息的 markdown 里
        // 定位 → 优先返回原文选中段。旧实现 `selectionMarkdownSlice(...)?.let { return it }`
        // 把这一份的完整性当成「数出来的」，静默丢掉缺口提示 —— 而完整性其实是
        // 「锚点定位成功」推出来的：锚点重复（见下一例）或端点被回收时都可能定位失败
        // 或落到错误区间，静默返回就等于把不确定当成确定。
        val head = unkeyed("m1", "blk-a")
        val middle = unkeyed("m1", "blk-b")
        val tail = unkeyed("m1", "blk-c")
        val c = controllerWithText(head to "第一段", middle to "中间", tail to "第三段")
        c.rememberMessageMarkdown("m1", "第一段\n\n中间\n\n第三段")
        c.selection.value = TextSelection(TextPosition(head, 0), TextPosition(tail, 3))

        val out = c.selectedPlainText()
        assertTrue("原文选中段照旧优先，out=<$out>", out.startsWith("第一段\n\n中间\n\n第三段"))
        assertTrue("缺口提示必须跟在选中段后面，out=<$out>", out.contains("已被回收"))
    }

    // ─── [T-cross-shard-copy-anchor-offset-match] 锚点重复时按选区偏移定位 ──────

    @Test
    fun `a repeated 24-char head anchor resolves at the selection offset`() {
        // [anchorFor] 最多取 24 字符，同一段文字在回复里出现两次时 `md.indexOf(headAnchor)`
        // 取的首个出现是更早的那一处 —— 切出来的正是用户没选的内容。分片文本在选区
        // 起点**前后**各是什么，就是消歧用的偏移信息（本例里正确区间整段等于分片文本，
        // 诱饵区间只有那 24 字符相同、续文是 DECOY-END）。
        val head = shard("m1", 0)
        val tail = shard("m1", 2)
        val headText = "PREFIX-TO-THE-SELECTION $dupAnchor REAL-BODY"
        val c = controllerWithText(head to headText, tail to "TAILPART-END")
        c.rememberMessageMarkdown(
            "m1",
            listOf(
                "EARLIER-REGION $dupAnchor DECOY-END",
                "",
                headText,
                "",
                "```kotlin",
                "val x = 1",
                "```",
                "",
                "TAILPART-END",
            ).joinToString("\n"),
        )
        // 选区从锚点起（= 分片偏移 24，等于锚点长度）到尾分片的第 8 个字符。
        c.selection.value = TextSelection(TextPosition(head, 24), TextPosition(tail, 8))

        assertEquals(
            "必须从正确的那一处锚点切起：诱饵区间的续文是 DECOY-END，正确区间是 REAL-BODY",
            "$dupAnchor REAL-BODY\n\n```kotlin\nval x = 1\n```\n\nTAILPART",
            c.selectedPlainText(),
        )
    }
}
