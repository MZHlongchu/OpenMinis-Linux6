package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.network.guardedDohDns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Buffer
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Fetch a public URL to text. SSRF via [FetchUrlGuard].
 *
 * Adapted from XINCODE-Public WebFetchTool (GPL-3.0-or-later).
 */
object WebFetchTool {
    const val NAME = "web_fetch"
    private const val MAX_OUTPUT = 8_000

    /**
     * [T-web-fetch-oom] 响应体字节硬上限（4 MiB）。
     *
     * web_fetch 曾是唯一没有体积护栏的网络工具：`resp.body?.string()` 把整个响应体
     * 一次读进内存（OkHttp 还会透明解压 gzip），大页面 / 压缩炸弹打穿的是
     * OutOfMemoryError——Error 不是 Exception，工具内的 catch(Exception)、ToolRetry、
     * agent 循环都拦不住。上限由 [readBody] 结构性保证，不依赖底层读取粒度。
     */
    const val MAX_BODY_BYTES: Long = 4L * 1024 * 1024

    /** [T-web-fetch-oom] 有界读取的结果：正文，以及正文是否被上限切断。 */
    data class CappedBody(val text: String, val truncated: Boolean)

    /**
     * [T-web-fetch-oom] 响应声明的 Content-Length 本身已超上限：不读正文，确定性失败。
     *
     * 刻意**不**继承 IOException：这不是网络瞬态，[errorCodeFor] 必须把它归到
     * EXECUTION_FAILED，否则 ToolRetry 会退避重试同一个大页面（确定性失败重试无意义）。
     */
    class ResponseTooLargeException(
        val declaredBytes: Long,
        val limitBytes: Long,
    ) : Exception("response body is too large: $declaredBytes bytes declared (limit $limitBytes)")

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // [T-doh-resolver-fallback] Composed, NOT replaced: the SSRF guard must keep
            // veto power over every address OkHttp would dial (incl. redirects),
            // while still getting the DoH fallback when system DNS is dead.
            .dns(guardedDohDns { !FetchUrlGuard.isUnsafeAddress(it) })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Fetch a public http(s) URL and return extracted text. Use web_search first to find URLs. Private/loopback hosts are blocked.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "url" to AgentToolParam("string", "http or https URL."),
        ),
        required = listOf("tool_title", "url"),
        propertyOrdering = listOf("tool_title", "url"),
    )

    fun execute(argsJson: String): ToolExecutionResult {
        val toolTitle = try { JSONObject(argsJson).optString("tool_title", NAME) } catch (_: Exception) { NAME }
        return try {
            val url = JSONObject(argsJson).optString("url", "").trim()
            val blocked = FetchUrlGuard.blockedReason(url)
            if (blocked != null) {
                // [T-retry-error-codes] P1-7: 失败路径补机器可读错误码——此前全部
                // errorCode=null，ToolRetry.isTransient（只认 NETWORK_ERROR/TIMEOUT）
                // 从未触发，统一重试层是死代码。SSRF 拒绝不是瞬态，标 PERMISSION_DENIED
                // 弹回模型换 URL。
                return ToolExecutionResult(
                    "Error: $blocked", false,
                    errorCode = ToolErrorCode.PERMISSION_DENIED,
                    toolTitle = toolTitle,
                )
            }
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) MinisUltra/web_fetch")
                .build()
            client.newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                FetchUrlGuard.blockedReason(finalUrl)?.let {
                    return ToolExecutionResult(
                        "Error after redirect: $it", false,
                        errorCode = ToolErrorCode.PERMISSION_DENIED,
                        toolTitle = toolTitle,
                    )
                }
                if (!resp.isSuccessful) {
                    // [T-retry-error-codes] 5xx/408/429 是瞬态（过载/限流/请求超时），
                    // 交给 ToolRetry 退避重试；其余 4xx 是确定性答复，重试也是同一个错。
                    val transient = resp.code == 408 || resp.code == 429 || resp.code in 500..599
                    return ToolExecutionResult(
                        "HTTP ${resp.code} for $url", false,
                        errorCode = if (transient) ToolErrorCode.NETWORK_ERROR else ToolErrorCode.EXECUTION_FAILED,
                        toolTitle = toolTitle,
                    )
                }
                // [T-web-fetch-oom] 有界读取 + [T-web-fetch-truncation] 显式截断标注：
                // 旧实现 `resp.body?.string()` 先物化整份正文，再 `.take(MAX_OUTPUT)`
                // 静默裁掉尾部，模型会把半页当整页。
                val capped = resp.body?.let { readBody(it) } ?: CappedBody("", false)
                val text = clampOutput(htmlToText(capped.text))
                val bodyNote = if (capped.truncated) {
                    "\n\n[truncated: the response body exceeded $MAX_BODY_BYTES bytes, so only its " +
                        "first part was parsed; the text above is incomplete]"
                } else {
                    ""
                }
                ToolExecutionResult("URL: $finalUrl\n\n$text$bodyNote", true, toolTitle = toolTitle)
            }
        } catch (e: Exception) {
            ToolExecutionResult(
                "Error fetching URL: ${e.message}", false,
                errorCode = errorCodeFor(e),
                toolTitle = toolTitle,
            )
        }
    }

    /**
     * [T-web-fetch-oom] / [T-web-fetch-cap-structural] 读响应体，返回的字节数**结构上**
     * 不超过 [cap]。
     *
     * 做法是显式循环，每轮只向底层要「距离 cap 还差多少、最多 cap+1」：多要的那 1 字节
     * 只用于确认「后面还有」，所以无论 Source 是一个 Buffer（`String.toResponseBody()`
     * 这种整体可用）、真实连接的分段读，还是会短读的自定义 Source，进内存的字节数都被
     * 这里钳住（OOM 不可能发生，而不是「通常不会」）。
     *
     * 刻意不用 `source.request(cap)` + `readByteArray(source.buffer.size)`：request 只是
     * 可用性检查、不搬数据，Buffer 承载时会把整份正文物化，短读的 Source 又只交出第一个
     * 分段——上限会退化成底层读取粒度，等于没护栏。
     *
     * 声明的 Content-Length 已超上限时抛 [ResponseTooLargeException]（读之前失败）。
     * 正文不被关闭：调用方（`Response.use`）持有生命周期。
     */
    internal fun readBody(body: ResponseBody, cap: Long = MAX_BODY_BYTES): CappedBody {
        val declared = body.contentLength()
        if (declared > cap) throw ResponseTooLargeException(declared, cap)

        val source = body.source()
        val buffer = Buffer()
        var total = 0L
        while (total <= cap) {
            val n = source.read(buffer, cap + 1 - total)
            // -1 = EOF（正文正好读完）；0 = 不推进的异常 Source，退出避免自旋。
            if (n <= 0L) break
            total += n
        }
        val truncated = total > cap
        val bytes = ByteArray(minOf(total, cap).toInt())
        buffer.readFully(bytes)
        // cap 切在多字节字符中间时，String 解码用替换字符（U+FFFD）收尾，不抛异常。
        return CappedBody(String(bytes, Charsets.UTF_8), truncated)
    }

    /**
     * [T-web-fetch-truncation] 字符层裁剪不再静默：被切断时明确标注原始长度与下一步
     * （用 browser_use 打开整页），标注很短，不构成第二份正文。
     */
    internal fun clampOutput(text: String, limit: Int = MAX_OUTPUT): String =
        if (text.length <= limit) {
            text
        } else {
            text.take(limit) +
                "\n\n[truncated: showing the first $limit of ${text.length} chars; " +
                "use browser_use to open the full page]"
        }

    /**
     * [T-retry-error-codes] P1-7: 异常 → 错误码分类。超时/IO 是瞬态（退避重试
     * 有意义）；畸形 URL / 烂 argsJson 是参数错；其余按执行失败弹回模型自查。
     */
    internal fun errorCodeFor(cause: Exception): ToolErrorCode = when (cause) {
        is SocketTimeoutException, is TimeoutException -> ToolErrorCode.TIMEOUT
        is IOException -> ToolErrorCode.NETWORK_ERROR
        is JSONException, is IllegalArgumentException -> ToolErrorCode.INVALID_ARGUMENTS
        else -> ToolErrorCode.EXECUTION_FAILED
    }

    internal fun htmlToText(raw: String): String {
        var s = raw
        s = Regex("(?is)<script[^>]*>.*?</script>").replace(s, " ")
        s = Regex("(?is)<style[^>]*>.*?</style>").replace(s, " ")
        s = Regex("(?is)<[^>]+>").replace(s, " ")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"")
        return s.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
    }
}
