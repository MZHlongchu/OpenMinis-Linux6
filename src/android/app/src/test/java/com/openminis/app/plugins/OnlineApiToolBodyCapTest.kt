package com.openminis.app.plugins

import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-online-api-body-cap] 插件响应体的上限读取契约。
 *
 * 插件目录来自远端市场，响应体是不可信输入：原先的 `body.string()` 无上限读进堆
 * （OOM 还会被 `execute` 的 runCatching 吞成误导性的 "Cannot reach plugin server"），
 * 而模型侧最终只用到 `take(4_000)` 字符。这里只测纯 IO 原语——`execute` 需要
 * Context，本模块单测没有 Robolectric。
 */
class OnlineApiToolBodyCapTest {

    private val cap = OnlineApiTool.MAX_RESPONSE_BYTES.toInt()

    @Test
    fun `body under the cap is returned verbatim`() {
        val text = """{"data":"ok"}"""
        assertEquals(text, OnlineApiTool.readBodyBounded(text.toResponseBody(null)))
    }

    @Test
    fun `body exactly at the cap is accepted`() {
        // 上限是"最多读这么多"，不是"必须小于"：等于上限的响应不能被误判成超大。
        val bytes = ByteArray(cap) { 'a'.code.toByte() }
        assertEquals(cap, OnlineApiTool.readBodyBounded(bytes.toResponseBody(null))?.length)
    }

    @Test
    fun `oversize body is rejected instead of being read into the heap`() {
        val bytes = ByteArray(cap + 1)
        assertNull(OnlineApiTool.readBodyBounded(bytes.toResponseBody(null)))
    }

    @Test
    fun `oversize body with unknown content length is still rejected`() {
        // gzip 透明解压 / chunked 响应 contentLength() == -1，Content-Length 预检
        // 拦不住，只能靠读取上限兜住（上限是读取上限，不是"响应声明的长度"）。
        val body = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source(): BufferedSource = Buffer().write(ByteArray(cap + 5))
        }
        assertNull(OnlineApiTool.readBodyBounded(body))
    }

    @Test
    fun `missing body reads as empty`() {
        assertEquals("", OnlineApiTool.readBodyBounded(null))
    }

    // ── [T-online-api-body-cap] 一次 read 不等于读完 ──

    /** 一次只交出 [chunk] 字节的 Source：真实连接就是按段返回的。 */
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
        override fun contentType() = null
        override fun contentLength(): Long = -1L
        override fun source(): BufferedSource = DripSource(text.toByteArray(Charsets.UTF_8), chunk).buffer()
    }

    @Test
    fun `a body delivered in short reads is drained to eof not just the first chunk`() {
        // 旧实现只调一次 `read(sink, cap + 1)`：okio 的契约是「**最多** n 字节」，不是
        // 「读满 n 字节」。于是 100KB 的响应只拿回 8192 字符、chunked/2MB 只拿回 4096
        // 字符，并且报告成功——比无上限读取更糟的静默截断。
        val text = """{"data":"0123456789abcdef"}"""
        assertEquals(text, OnlineApiTool.readBodyBounded(dripBody(text, chunk = 3)))
    }

    @Test
    fun `an oversize body delivered in short reads is still rejected`() {
        val bytes = ByteArray(cap + 5) { 'a'.code.toByte() }
        val body = dripBody(String(bytes, Charsets.US_ASCII), chunk = 1024)
        assertNull("short reads must not hide an oversize body", OnlineApiTool.readBodyBounded(body))
    }
}
