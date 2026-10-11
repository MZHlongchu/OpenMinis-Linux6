package com.openminis.app.ui.settings

import com.openminis.app.text.BoundedText
import java.io.File

/**
 * 「浏览轨迹回放」弹窗的正文构建。从 LogManagementScreen.kt 拆出（架构门
 * maxFileLines 棘轮：该文件已在基线上，只许缩减）。
 *
 * [T-trace-preview-async] 旧实现在组合期 `f.readLines()` 把整个文件读进主线程内存：
 * 轨迹文件（`filesDir/diagnostics/stream-traces/trace_*.jsonl`）每条 chunk 一行且
 * 无大小上限，ToolInputDelta 每帧写入累积全文，行体积近平方级增长 —— 数十 MB
 * 完全现实，主线程大对象分配 + JSON 解析有 ANR/OOM 面。现在只按字节偏移读尾部
 * 有界窗口，并在 IO 线程调用（见 LogManagementScreen 的 LaunchedEffect）。
 */

/** 弹窗正文的读取上限：低于 [BoundedText.MAX_LOG_READ_BYTES] 的硬顶。 */
internal const val TRACE_PREVIEW_MAX_BYTES = 200_000

/** 弹窗最多渲染的尾部行数（与旧实现的 `takeLast(400)` 口径一致）。 */
internal const val TRACE_PREVIEW_MAX_LINES = 400

/** 单行摘要的字符上限（与旧实现的 `take(120)` 口径一致）；兜底片段按同一口径收口。 */
internal const val TRACE_PREVIEW_SUMMARY_CHARS = 120

/**
 * [T-trace-preview-oversized-line] 尾部窗口整块落在单条超长记录内部时的显式截断标注：
 * 此时窗口里没有任何完整行可渲染，正文会整块空白，必须让用户看到「有内容但被截断」。
 */
internal const val TRACE_PREVIEW_TRUNCATED_MARKER = "…[已截断]"

/**
 * 按字节偏移只读文件尾部的有界窗口，再逐行解析成短摘要。
 * 必须在 IO 线程调用。读取失败返回「读取失败」，与旧实现的可视化行为一致。
 */
internal fun readTracePreview(
    file: File,
    maxBytes: Int = TRACE_PREVIEW_MAX_BYTES,
): String {
    // 文件可能在弹窗打开与读取之间被日志轮转/清理删掉：旧实现（readLines 抛异常）
    // 会显示「读取失败」，这里显式保留 —— 否则空正文会被误读成「这次会话没有轨迹」。
    if (!file.exists()) return "读取失败"
    // 只有真的截断了才会从行中间切入；小文件整读，首行是完整记录。
    val truncated = runCatching { file.length() > maxBytes }.getOrDefault(false)
    val raw = runCatching { BoundedText.readFileTail(file, maxBytes) }.getOrNull()
        ?: return "读取失败"
    val lines = raw.lines()
    // 定长尾部读取会从某一行中间切开，首行是残行 —— 丢掉，避免展示半条轨迹。
    val usable = if (truncated) lines.drop(1) else lines
    val rendered = usable.takeLast(TRACE_PREVIEW_MAX_LINES)
        .joinToString("\n", transform = ::formatTraceLine)
    // [T-trace-preview-oversized-line] S7 复核回归：录制器按 `encodeChunk(chunk) + "\n"`
    // 写行，末条记录本身 ≥ maxBytes 时（轨迹按 JSON 写行，转义后没有真实换行），整个窗口
    // 都落在这条记录内部 —— `lines` 只剩该记录的尾部残行，外加末尾换行拆出的空串，
    // drop(1) 之后没有任何可渲染的内容（`[""]`，判定必须落在渲染结果上，不能只看列表是否
    // 为空），弹窗正文整块空白。旧实现（整读 + takeLast(400)）此时是有内容的，故回退成
    // 显式截断标注 + 该残行的尾部片段：既不空白，也不至于把「有轨迹但被截断」误读成
    // 「这次会话没有轨迹」。
    if (truncated && rendered.isBlank()) {
        val tail = lines.firstOrNull().orEmpty().takeLast(TRACE_PREVIEW_SUMMARY_CHARS).trim()
        return if (tail.isEmpty()) {
            TRACE_PREVIEW_TRUNCATED_MARKER
        } else {
            TRACE_PREVIEW_TRUNCATED_MARKER + "\n" + tail
        }
    }
    return rendered
}

/** 单行轨迹的短摘要；非 JSON 行退化为原文前 [TRACE_PREVIEW_SUMMARY_CHARS] 字符。 */
internal fun formatTraceLine(line: String): String = runCatching {
    val obj = org.json.JSONObject(line)
    when (obj.optString("kind")) {
        "text" -> "text: ${obj.optString("text").take(TRACE_PREVIEW_SUMMARY_CHARS)}"
        "thinking" -> "thinking: ${obj.optString("text").take(TRACE_PREVIEW_SUMMARY_CHARS)}"
        "reasoning" -> "reasoning: ${obj.optString("content").take(TRACE_PREVIEW_SUMMARY_CHARS)}"
        "tool_use_start" -> "tool_use_start: ${obj.optString("name")}"
        "tool_call_complete" -> "tool_call_complete: ${obj.optString("name")}"
        "finished" -> "finished: ${obj.optString("stopReason")}"
        else -> obj.optString("kind")
    }
}.getOrDefault(line.take(TRACE_PREVIEW_SUMMARY_CHARS))
