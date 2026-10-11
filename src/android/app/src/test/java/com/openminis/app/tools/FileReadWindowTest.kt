package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [T-fileread-read-cap] file_read 有界读取原语的回归：
 * - 行尾语义必须与 `File.readLines()`（BufferedReader.readLine）逐字一致，
 *   否则 totalLines / 截断口径会与旧实现分叉；
 * - head / tail 窗口只持有窗口内的行，任何体积的文件都不再整读；
 * - 行数统计的字节上限生效时返回值退化为下界（调用方据此打 "N+"）。
 *
 * 本模块没有 Robolectric，测的是纯 java.io 的读取原语（execute 需要 Context）。
 */
class FileReadWindowTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var fileSeq = 0

    private fun file(content: String): File =
        File(tmp.root, "read-${fileSeq++}.txt").apply { writeText(content) }

    private fun readAll(file: File): List<String> = file.inputStream().use { stream ->
        val r = BoundedLineReader(stream)
        generateSequence { r.readLine() }.toList()
    }

    // ---------- 行读取语义 ----------

    @Test
    fun `line terminators match readLines`() {
        val f = file("a\nb\r\nc\rd\n")
        assertEquals(listOf("a", "b", "c", "d"), readAll(f))
        assertEquals(f.readLines(), readAll(f))
    }

    @Test
    fun `trailing newline does not create a phantom line`() {
        assertEquals(listOf("a"), readAll(file("a\n")))
        assertEquals(listOf("a"), readAll(file("a")))
        assertTrue(readAll(file("")).isEmpty())
    }

    @Test
    fun `empty lines are preserved`() {
        val f = file("a\n\nb\n")
        assertEquals(listOf("a", "", "b"), readAll(f))
        assertEquals(f.readLines(), readAll(f))
    }

    // ---------- 行数统计 ----------

    @Test
    fun `scanLineCount equals readLines size`() {
        val f = file((1..500).joinToString("\n") + "\n")
        assertEquals(500, scanLineCount(f, FILE_READ_MAX_SCAN_BYTES))
        assertEquals(0, scanLineCount(file(""), FILE_READ_MAX_SCAN_BYTES))
    }

    @Test
    fun `scanLineCount stops at the byte cap and is only a lower bound`() {
        val f = file((1..200).joinToString("\n") { "0123456789$it" } + "\n")
        val partial = scanLineCount(f, capBytes = 1_000)
        assertTrue("cap must cut the scan short: $partial", partial in 1 until 200)
    }

    // ---------- head 窗口 ----------

    @Test
    fun `head window honours offset and lines and reports the exact total at eof`() {
        val f = file((1..10).joinToString("\n"))
        val w = readFileWindow(f, direction = "head", offset = 3, requestedLines = 4, maxLength = 15_000)
        assertEquals(listOf("3", "4", "5", "6"), w.lines)
        // 顺序读到窗口末尾就停（第 7 行还没读），但行数统计补上了确切总数
        assertEquals(10, scanLineCount(f, FILE_READ_MAX_SCAN_BYTES))
    }

    @Test
    fun `head window reaches eof without a line budget`() {
        val f = file("a\nb\nc")
        val w = readFileWindow(f, direction = "head", offset = 1, requestedLines = null, maxLength = 15_000)
        assertEquals(listOf("a", "b", "c"), w.lines)
        assertEquals(3, w.exactTotalLines)
    }

    @Test
    fun `head window of a big file stops at the char budget`() {
        // ~50k 行 × 12 字符 ≈ 600 KB：窗口必须远小于文件，且行数无法确知（下界）
        val f = file((1..50_000).joinToString("\n") { "line-%06d".format(it) })
        val maxLength = 15_000
        val w = readFileWindow(f, direction = "head", offset = 1, requestedLines = null, maxLength = maxLength)
        assertNull(w.exactTotalLines)
        assertTrue("window must stay bounded, got ${w.lines.size} lines", w.lines.size < 4_000)
        val content = w.lines.joinToString("\n")
        assertTrue("window chars ${content.length} must stay near 2×maxLength", content.length < 40_000)
        // 内容足够截断到 maxLength 并回退到完整行
        assertTrue(content.length > maxLength)
    }

    @Test
    fun `head window skips beyond eof without inventing lines`() {
        val f = file("a\nb\nc\n")
        val w = readFileWindow(f, direction = "head", offset = 99, requestedLines = null, maxLength = 15_000)
        assertTrue(w.lines.isEmpty())
        assertEquals(3, w.exactTotalLines)
    }

    // ---------- tail 窗口 ----------

    @Test
    fun `tail window keeps the last lines and the exact total`() {
        val f = file((1..10).joinToString("\n"))
        val w = readFileWindow(f, direction = "tail", offset = 1, requestedLines = 3, maxLength = 15_000)
        assertEquals(listOf("8", "9", "10"), w.lines)
        assertEquals(10, w.exactTotalLines)
    }

    @Test
    fun `tail ring is bounded by chars when the line count is unbounded`() {
        val f = file((1..1_000).joinToString("\n") { "line-%06d".format(it) })
        val maxLength = 100
        val w = readFileWindow(f, direction = "tail", offset = 1, requestedLines = null, maxLength = maxLength)
        assertEquals(1_000, w.exactTotalLines)
        assertTrue("ring must not hold the whole file, got ${w.lines.size}", w.lines.size < 200)
        assertTrue(w.lines.joinToString("\n").length >= maxLength)
        assertEquals("line-001000", w.lines.last())
    }

    // ---------- 单行硬帽 ----------

    @Test
    fun `oversized single line is clipped instead of materialised`() {
        val f = file("x".repeat(300_000))
        val w = readFileWindow(f, direction = "head", offset = 1, requestedLines = null, maxLength = 15_000)
        assertEquals(1, w.lines.size)
        assertEquals(FILE_READ_MAX_LINE_CHARS, w.lines.single().length)
    }

    @Test
    fun `head of an oversized single line keeps the line start`() {
        val f = file("HEAD-MARKER" + "x".repeat(300_000))
        val w = readFileWindow(f, direction = "head", offset = 1, requestedLines = null, maxLength = 15_000)
        assertTrue(w.lines.single().startsWith("HEAD-MARKER"))
    }

    @Test
    fun `tail of an oversized single line keeps the file end not its middle`() {
        // [T-fileread-tail-longline] 单行 300K > 单行硬帽 256K：截断必须丢掉**行首**。
        // 旧实现保留行首 256K，execute 再对它 takeLast(maxLength)，于是「读文件尾部」
        // 拿回的是文件 ~8% 处的行中段，文件末尾永远读不到。
        val marker = "THE-END-OF-FILE"
        val f = file("x".repeat(300_000) + marker)

        val w = readFileWindow(f, direction = "tail", offset = 1, requestedLines = null, maxLength = 15_000)
        val content = w.lines.joinToString("\n")

        assertTrue("tail must keep the file end, got ${content.length} chars", content.endsWith(marker))
        assertTrue("the retained line must stay bounded", content.length <= FILE_READ_MAX_LINE_CHARS)
        // execute 的 takeLast(maxLength) 取的仍是文件尾部
        assertTrue(content.takeLast(15_000).endsWith(marker))
    }

    @Test
    fun `tail window of several lines still ends at the last line`() {
        // 长行的**末尾**也要留在窗口里（否则 execute 的 takeLast(maxLength) 只会吃掉它）
        val marker = "TAILMARK"
        val f = file("x".repeat(300_000) + marker + "\nlast-line\n")
        val w = readFileWindow(f, direction = "tail", offset = 1, requestedLines = null, maxLength = 15_000)
        assertEquals(
            listOf("x".repeat(FILE_READ_MAX_LINE_CHARS - marker.length) + marker, "last-line"),
            w.lines,
        )
        assertEquals(2, w.exactTotalLines)
    }
}
