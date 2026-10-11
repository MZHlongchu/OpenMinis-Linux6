package com.openminis.app.logging

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-log-hotpath] Diagnostics that fire per streamed event / chunk
 * must cost nothing when nobody can read them (release build at the default
 * Info level), and AppLogger must not format timestamps for a line it does
 * not write. Source guards: unit tests run the debug variant, where
 * BuildConfig.DEBUG keeps the trace gate open.
 */
class LogHotPathTest {

    private val logger by lazy { ProductionSources.read("logging/AppLogger.kt") }

    @Test
    fun `timestamps are formatted only for a line that is written`() {
        // [T-u14-merge-isotime] 断言的是同一条产品性质（时间戳只在真的要写一行时
        // 才格式化），但取值点按本移植改写：上游用两个共享的 SimpleDateFormat
        // （`dateFormat.format(now)` / `val now = Date()`），AppLogger 是从任意线程
        // 调的，SimpleDateFormat 不是线程安全的 —— 本移植改用 IsoTime（纯函数、
        // 无共享状态）。两处格式化仍然排在 enabled / DEBUG-verbose 两道早退之后，
        // 所以「不写的行不付格式化 + 不分配」这条性质原样成立，且时钟读取
        // （System.currentTimeMillis）也在检查之后。
        val body = logger.substringAfter("private fun log(level: String, category: String, message: String) {")
            .substringBefore("\n    }\n")
        val format = body.indexOf("IsoTime.format")
        assertTrue("formats exist", format > 0)
        assertTrue("after the enabled check", format > body.indexOf("if (!enabled) return"))
        assertTrue("after the verbose check", format > body.indexOf("if (level == \"DEBUG\" && !verbose) return"))
        assertTrue(
            "the clock is read after the checks too",
            body.indexOf("val ms = System.currentTimeMillis()") > body.indexOf("if (!enabled) return"),
        )
    }

    @Test
    fun `trace builds its message only when someone can read it`() {
        assertTrue(logger.contains("get() = com.openminis.app.BuildConfig.DEBUG || (enabled && verbose)"))
        val trace = logger.substringAfter("inline fun trace(category: String, message: () -> String) {").substringBefore("\n    }")
        assertTrue(trace.trimStart().startsWith("if (!traceEnabled || isDebugMuted(category)) return"))
    }

    @Test
    fun `per-chunk ToolInputDelta logs are gated in every provider and the view model`() {
        for (path in listOf(
            // [T-u14-merge-file-split] 站点按本移植的拆分搬家：OpenAI 的 SSE 解析在
            // OpenAIRawStream.kt（OpenAIProvider.kt 只剩 provider 外壳），agent 循环
            // 在 ChatViewModelAgentLoopExt.kt（ChatViewModel.kt 是拆分后剩下的主体）。
            // 断言的产品性质不变：三个逐 chunk 的 ToolInputDelta 日志都必须有门禁。
            "provider/openai/OpenAIRawStream.kt",
            "provider/anthropic/AnthropicProvider.kt",
            "ui/chat/ChatViewModelAgentLoopExt.kt",
        )) {
            val src = ProductionSources.read(path)
            val sites = Regex("""Log\.d\("ToolChain\[(Provider|VM)\]", "[^"]*ToolInputDelta""").findAll(src).toList()
            assertEquals("$path: one ToolInputDelta log", 1, sites.size)
            // [T-u14-merge-log-gates] 邻接窗口 160 → 420：本移植在这行日志上留了
            // **两道**门（上游的 AppLogger.traceEnabled + 我们审计的长度步长节流），
            // 门禁文本因此离调用点更远。断言本身比上游更严：两个 token 都必须出现，
            // 上游只要求 traceEnabled 一个。
            val before = src.substring(0, sites[0].range.first).takeLast(420)
            assertTrue("$path: gated by traceEnabled", before.contains("AppLogger.traceEnabled"))
            assertTrue("$path: gated by the length-stride throttle", before.contains("shouldLogLengthStride"))
        }
    }

    @Test
    fun `per-event SSE summaries go through trace`() {
        // [T-u14-merge-file-split] 上游把 SSE 解析留在 OpenAIProvider.kt，本移植拆进了
        // OpenAIRawStream.kt；扫描面随之搬家，断言不变（逐事件的 SSE 摘要走
        // AppLogger.trace，消息只在有人能读时才构建）。
        val src = ProductionSources.read("provider/openai/OpenAIRawStream.kt")
        assertTrue(src.contains("AppLogger.trace(\"OpenAIProvider\") {\n                                \"[T321] SSE delta:"))
        assertTrue(src.contains("AppLogger.trace(\"OpenAIProvider\") {\n                                \"[T321] SSE responses type="))
        assertTrue("no eager per-event debug left", !src.contains("AppLogger.debug(\n                                \"OpenAIProvider\",\n                                \"[T321] SSE"))
    }

    @Test
    fun `raw SSE payload logging stays debug-only`() {
        for (path in listOf("provider/openai/OpenAIRawStream.kt", "provider/anthropic/AnthropicProvider.kt")) {
            val src = ProductionSources.read(path)
            // [T-u14-merge-raw-sse] OpenAI 的站点同样搬到了 OpenAIRawStream.kt；字符串
            // 也不再是裸 `"RAW SSE: $payload"` —— 我们审计的
            // BoundedText.clampSsePayload 会把它按长度截断（一行日志 1.4 MB 的问题）。
            // 于是断言分两步：站点还在、payload 仍被截断，门禁仍是「debug 才算」。
            val at = src.indexOf("\"RAW SSE: ")
            assertTrue("$path: the RAW SSE log must still exist", at > 0)
            assertTrue(
                "$path: ours clamps the payload; upstream interpolated it raw",
                src.substring(at, at + 120).contains("clampSsePayload(payload)"),
            )
            val before = src.substring(0, at).takeLast(400)
            assertTrue("$path: debug-only, so BuildConfig.DEBUG must gate it", before.contains("BuildConfig.DEBUG"))
            assertTrue("$path: and logcat VERBOSE must gate it", before.contains("android.util.Log.VERBOSE"))
        }
    }
}
