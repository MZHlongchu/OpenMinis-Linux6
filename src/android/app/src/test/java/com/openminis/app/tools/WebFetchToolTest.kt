package com.openminis.app.tools

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-web-fetch-oom] / [T-web-fetch-truncation]
 *
 * web_fetch 是唯一没有体积护栏的网络工具：`resp.body?.string()` 把整个响应体一次
 * 读进内存，OkHttp 还会透明解压 gzip——大页面 / 压缩炸弹打穿的 OutOfMemoryError 是
 * Error 不是 Exception，工具内的 catch(Exception)、ToolRetry、agent 循环都拦不住。
 * 另一头，8 000 字符的裁剪完全静默，模型会把半页当整页。
 *
 * 这里不碰网络：只验证纯粹的两段——上限读取与截断标注。
 */
class WebFetchToolTest {

    /** 没声明 Content-Length 的响应（gzip / chunked）：只有一个流。 */
    private fun streamedBody(vararg chunks: String): ResponseBody = object : ResponseBody() {
        override fun contentType() = "text/html; charset=utf-8".toMediaType()
        override fun contentLength(): Long = -1L
        override fun source(): BufferedSource = Buffer().apply { chunks.forEach { writeUtf8(it) } }
    }

    private fun bodyOf(text: String): ResponseBody = text.toResponseBody("text/html".toMediaType())

    @Test
    fun `a body under the cap is returned whole`() {
        assertEquals("<p>hi</p>", WebFetchTool.readBody(bodyOf("<p>hi</p>")).text)
    }

    @Test
    fun `a declared oversize body is refused before it is read`() {
        // 用一个极小的 cap 表达同一个判据（真上限是 4 MB，没必要在单测里分配它）。
        val e = runCatching { WebFetchTool.readBody(bodyOf("x".repeat(64)), cap = 8) }
            .exceptionOrNull()
        assertTrue("declared oversize must fail fast; got $e", e is WebFetchTool.ResponseTooLargeException)
        val tooLarge = e as WebFetchTool.ResponseTooLargeException
        assertEquals(64L, tooLarge.declaredBytes)
        assertEquals(8L, tooLarge.limitBytes)
        // 确定性失败：不是 NETWORK_ERROR（那会被 ToolRetry 退避重试同一个大页面）。
        assertEquals(ToolErrorCode.EXECUTION_FAILED, WebFetchTool.errorCodeFor(tooLarge))
        assertTrue(
            "the message must name the limit so the model can react",
            tooLarge.message.orEmpty().contains("(limit 8)"),
        )
    }

    @Test
    fun `an undeclared body is truncated at the cap instead of being materialized`() {
        val text = WebFetchTool.readBody(streamedBody("a".repeat(5_000)), cap = 100).text
        assertEquals(100, text.length)
        assertEquals("a".repeat(100), text)
    }

    // ── [T-web-fetch-cap-structural] 上限必须是代码保证，不是底层读取粒度 ──

    /** 一次只交出 [chunk] 字节的 Source：真实连接就是这样分段返回的。 */
    private class DripSource(private val data: ByteArray, private val chunk: Int) : okio.Source {
        private var pos = 0

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (pos >= data.size) return -1L
            val n = minOf(chunk.toLong(), byteCount, (data.size - pos).toLong())
            if (n <= 0L) return -1L
            sink.write(data, pos, n.toInt())
            pos += n.toInt()
            return n
        }

        override fun timeout(): okio.Timeout = okio.Timeout.NONE
        override fun close() = Unit
    }

    private fun dripBody(text: String, chunk: Int): ResponseBody = object : ResponseBody() {
        override fun contentType() = "text/html; charset=utf-8".toMediaType()
        override fun contentLength(): Long = -1L
        override fun source(): BufferedSource = DripSource(text.toByteArray(Charsets.UTF_8), chunk).buffer()
    }

    @Test
    fun `a short-reading source is drained to the cap, never one read at a time`() {
        // 旧实现 `source.request(cap)` + `readByteArray(buffer.size)` 的上限取决于底层
        // 读取粒度：Buffer 承载的响应体会被整体物化（上一个用例），短读的 Source 又只
        // 交出第一个分段。这里两者都要正好 cap。
        assertEquals("a".repeat(100), WebFetchTool.readBody(dripBody("a".repeat(5_000), chunk = 7), cap = 100).text)
    }

    @Test
    fun `a capped body reports that it was cut off`() {
        val over = WebFetchTool.readBody(streamedBody("b".repeat(500)), cap = 100)
        assertEquals(100, over.text.length)
        assertTrue("a truncated body must say so", over.truncated)

        val whole = WebFetchTool.readBody(bodyOf("short"), cap = 100)
        assertFalse("a body that fits must not be flagged", whole.truncated)
        assertEquals("short", whole.text)
    }

    @Test
    fun `the default cap is a real bound`() {
        assertTrue(
            "web_fetch must never buffer more than a few MB; cap=${WebFetchTool.MAX_BODY_BYTES}",
            WebFetchTool.MAX_BODY_BYTES in 1..(16L * 1024 * 1024),
        )
    }

    @Test
    fun `a short page is not annotated`() {
        val short = "hello"
        assertEquals(short, WebFetchTool.clampOutput(short))
    }

    @Test
    fun `a cut page says so and names the original length`() {
        val full = "y".repeat(12_345)

        val out = WebFetchTool.clampOutput(full)

        assertTrue("the visible text must be capped", out.startsWith("y".repeat(8_000)))
        assertTrue("the model must learn the page is longer than what it sees", out.contains("12345 chars"))
        assertTrue("and what to do about it", out.contains("browser_use"))
        assertFalse("the annotation is an annotation, not a second copy", out.length > 8_000 + 400)
    }

    @Test
    fun `html extraction still strips markup`() {
        val text = WebFetchTool.htmlToText("<html><style>p{color:red}</style><body><h1>Title</h1><p>Body &amp; more</p></body></html>")
        assertTrue(text, text.contains("Title"))
        assertTrue(text, text.contains("Body & more"))
        assertFalse(text, text.contains("<h1>"))
        assertFalse(text, text.contains("color:red"))
    }
}
