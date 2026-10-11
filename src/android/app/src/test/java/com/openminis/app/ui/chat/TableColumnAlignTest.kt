package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-table-column-align] Chat tables follow the GFM separator row's
 * column alignment, like iOS (SelectableMarkdownView: `.center` / `.right`,
 * anything else left). The separator used to be skipped outright, so every
 * column rendered at start whatever the markdown asked for.
 */
class TableColumnAlignTest {

    @Test
    fun `separator row sets each column's alignment`() {
        assertEquals(
            listOf(MdTableAlign.START, MdTableAlign.START, MdTableAlign.CENTER, MdTableAlign.END),
            parseTableAlignments("| --- | :--- | :---: | ---: |"),
        )
        assertEquals(
            listOf(MdTableAlign.CENTER, MdTableAlign.END),
            parseTableAlignments(":-:|--:"),
        )
    }

    @Test
    fun `plain dashes and a lone colon default to start`() {
        assertEquals(listOf(MdTableAlign.START, MdTableAlign.START), parseTableAlignments("|---|:|"))
    }

    @Test
    fun `parseTable carries alignments with headers and rows`() {
        val t = parseTable(
            listOf(
                "| Name | Count | Status |",
                "|------|:-----:|-------:|",
                "| a | 1 | ok |",
                "| b | 2 | fail |",
            )
        )
        assertEquals(listOf("Name", "Count", "Status"), t.headers)
        assertEquals(2, t.rows.size)
        assertEquals(listOf(MdTableAlign.START, MdTableAlign.CENTER, MdTableAlign.END), t.alignments)
    }

    @Test
    fun `a separator-looking row below the body does not redefine alignment`() {
        val t = parseTable(listOf("| a | b |", "|:-:|:-:|", "| 1 | 2 |", "|---|--:|"))
        assertEquals(listOf(MdTableAlign.CENTER, MdTableAlign.CENTER), t.alignments)
    }

    @Test
    fun `cells apply the column alignment to both the box and the wrapped text`() {
        // [T-android-table-column-align-ext-split] 本移植把表格渲染器从上游的
        // `StreamingMarkdownText.kt`（单体 3600+ 行）拆成了独立文件
        // `StreamingMarkdownTable.kt`，函数也从 `private fun RenderTable` 变成
        // `internal fun RenderTable`（同 package）。扫描面随之改到这个文件——
        // 断言一条没动：这三行正是「列对齐同时作用于单元格 Box 和换行后的文本」
        // 的全部证据（上游对照：StreamingMarkdownText.kt:3506 / 3538 / 3559）。
        val src = File("src/main/java/com/openminis/app/ui/chat/StreamingMarkdownTable.kt").readText()
            .substringAfter("internal fun RenderTable(block: MdBlock.Table)")
        assertTrue(src.contains("val columnAlign = block.alignments.getOrElse(colIndex) { MdTableAlign.START }"))
        assertTrue(src.contains("MdTableAlign.CENTER -> Alignment.TopCenter"))
        assertTrue(src.contains("MdTableAlign.CENTER -> TextAlign.Center"))
    }
}
