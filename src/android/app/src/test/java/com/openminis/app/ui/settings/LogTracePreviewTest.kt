package com.openminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 轨迹回放弹窗正文（[readTracePreview] / [formatTraceLine]）的有界读取与逐行摘要。
 *
 * 旧实现在组合期 `f.readLines()` 把整个文件读进主线程内存：轨迹文件每条 chunk 一行、
 * 无大小上限（ToolInputDelta 每帧写入累积全文，行体积近平方级增长），数十 MB 的
 * trace_*.jsonl 会直接卡 UI 线程。这里锁住两个不变量：只读尾部有界窗口、只渲染尾部 400 行。
 *
 * 另外锁住 S7 复核回归的兜底：末条记录本身 ≥ 读取上限时窗口整块落在它内部，正文不能空白。
 */
class LogTracePreviewTest {

    private fun tempDir(): File = File.createTempFile("trace-preview", "dir").apply {
        delete()
        mkdirs()
        deleteOnExit()
    }

    @Test
    fun `only the bounded tail is materialized`() {
        val dir = tempDir()
        val file = File(dir, "trace_big.jsonl")
        file.bufferedWriter().use { w ->
            w.write("HEAD-MARKER\n")
            // 约 1 MB 填充，远超读取上限 —— 头部内容绝不能进入弹窗正文。
            repeat(20_000) {
                w.write("x".repeat(50))
                w.write("\n")
            }
            w.write("TAIL-MARKER\n")
        }
        assertTrue("fixture must exceed the read cap", file.length() > TRACE_PREVIEW_MAX_BYTES)

        val preview = readTracePreview(file)
        assertTrue("tail must be visible", preview.contains("TAIL-MARKER"))
        assertFalse("head must never be read into the dialog", preview.contains("HEAD-MARKER"))
        assertTrue("preview must stay bounded, was ${preview.length}", preview.length < 200_000)
        // 正常窗口里有完整行，兜底标注绝不能误触发（否则会掩盖真实的尾部摘要）。
        assertFalse("截断兜底不该接管正常预览", preview.contains(TRACE_PREVIEW_TRUNCATED_MARKER))
    }

    @Test
    fun `renders at most the last 400 lines`() {
        val dir = tempDir()
        val file = File(dir, "trace_lines.jsonl")
        val lines = (1..500).map {
            """{"kind":"text","text":"line-${it.toString().padStart(4, '0')}"}"""
        }
        // 末尾不写换行，切分结果与平台无关。
        file.writeText(lines.joinToString("\n"))

        val preview = readTracePreview(file)
        val rendered = preview.lines()
        assertEquals(TRACE_PREVIEW_MAX_LINES, rendered.size)
        assertEquals("text: line-0101", rendered.first())
        assertEquals("text: line-0500", rendered.last())
        assertFalse("只有尾部 400 行该出现", preview.contains("line-0100"))
    }

    @Test
    fun `drops the partial first line of a truncated window`() {
        val dir = tempDir()
        val file = File(dir, "trace_partial.jsonl")
        // 每行 30 字节 + 换行 = 31 字节；maxBytes=60 让窗口从上一行中间切入。
        val lines = (1..200).map {
            """{"kind":"text","text":"n=${it.toString().padStart(3, '0')}"}"""
        }
        file.writeText(lines.joinToString("\n"))
        assertTrue(file.length() > 60)

        assertEquals("text: n=200", readTracePreview(file, maxBytes = 60))
    }

    @Test
    fun `small file keeps every line intact`() {
        val dir = tempDir()
        val file = File(dir, "trace_small.jsonl")
        file.writeText("""{"kind":"finished","stopReason":"end_turn"}""" + "\n" + "not json at all")

        assertEquals("finished: end_turn\nnot json at all", readTracePreview(file))
    }

    @Test
    fun `vanished file reports the failure text instead of an empty dialog`() {
        val dir = tempDir()
        val missing = File(dir, "rotated-away.jsonl")
        assertFalse(missing.exists())

        assertEquals("读取失败", readTracePreview(missing))
    }

    @Test
    fun `empty file renders empty rather than failing`() {
        val dir = tempDir()
        val file = File(dir, "trace_empty.jsonl").apply { writeText("") }

        assertEquals("", readTracePreview(file))
    }

    /**
     * S7 复核回归：录制器按 `encodeChunk(chunk).toString() + "\n"` 写行（StreamTraceRecorder），
     * 末条记录本体 ≥ maxBytes 时整条记录没有真实换行，200 KB 尾部窗口全落在它内部。此时
     * `lines()` = [残行, 空串]，drop(1) 之后只剩空串 —— 渲染结果整块空白（旧实现整读 +
     * takeLast(400) 此时有内容）。必须回退成显式截断标注 + 该记录的尾部片段。
     */
    @Test
    fun `oversized final record renders a truncated tail instead of a blank dialog`() {
        val dir = tempDir()
        val file = File(dir, "trace_giant_final.jsonl")
        file.writeText(
            """{"kind":"finished","stopReason":"end_turn"}""" + "\n" +
                """{"kind":"tool_input_delta","id":"call_1","accumulated":"${"a".repeat(300_000)}"}""" + "\n",
        )
        assertTrue("fixture must exceed the read cap", file.length() > TRACE_PREVIEW_MAX_BYTES)

        val preview = readTracePreview(file)

        assertTrue("正文不能整块空白", preview.isNotBlank())
        assertTrue("必须显式标注截断，was: $preview", preview.startsWith(TRACE_PREVIEW_TRUNCATED_MARKER))
        assertTrue("应展示该记录的尾部片段，was: …${preview.takeLast(30)}", preview.endsWith("\"}"))
        assertTrue(
            "兜底摘要必须有界，was ${preview.length}",
            preview.length <= TRACE_PREVIEW_TRUNCATED_MARKER.length + 1 + TRACE_PREVIEW_SUMMARY_CHARS,
        )
    }

    @Test
    fun `oversized final record without a trailing newline renders the same fallback`() {
        // 工单描述的形态：文件末尾没有换行，窗口里只剩一个残行，drop(1) 直接得到空列表。
        val dir = tempDir()
        val file = File(dir, "trace_giant_no_newline.jsonl")
        file.writeText("""{"kind":"tool_input_delta","id":"call_2","accumulated":"${"b".repeat(300_000)}"}""")
        assertTrue("fixture must exceed the read cap", file.length() > TRACE_PREVIEW_MAX_BYTES)

        val preview = readTracePreview(file)

        assertTrue("正文不能整块空白", preview.isNotBlank())
        assertTrue("必须显式标注截断，was: $preview", preview.startsWith(TRACE_PREVIEW_TRUNCATED_MARKER))
        assertTrue("应展示该记录的尾部片段，was: …${preview.takeLast(30)}", preview.endsWith("\"}"))
    }

    @Test
    fun `known kinds render as short summaries`() {
        assertEquals("text: hello", formatTraceLine("""{"kind":"text","text":"hello"}"""))
        assertEquals("thinking: hmm", formatTraceLine("""{"kind":"thinking","text":"hmm"}"""))
        assertEquals("reasoning: why", formatTraceLine("""{"kind":"reasoning","content":"why"}"""))
        assertEquals(
            "tool_use_start: read_file",
            formatTraceLine("""{"kind":"tool_use_start","name":"read_file"}"""),
        )
        assertEquals(
            "tool_call_complete: read_file",
            formatTraceLine("""{"kind":"tool_call_complete","name":"read_file"}"""),
        )
        assertEquals("finished: end_turn", formatTraceLine("""{"kind":"finished","stopReason":"end_turn"}"""))
        assertEquals("some_new_kind", formatTraceLine("""{"kind":"some_new_kind"}"""))
    }

    @Test
    fun `long text and non-JSON lines are truncated to 120 chars`() {
        val long = "z".repeat(500)
        assertEquals("text: ${"z".repeat(120)}", formatTraceLine("""{"kind":"text","text":"$long"}"""))
        // 非 JSON（含被窗口切开的残行）退化为原文前 120 字符，不抛异常。
        assertEquals("n".repeat(120), formatTraceLine("n".repeat(500)))
    }
}
