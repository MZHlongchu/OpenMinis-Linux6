package com.openminis.app.tools

import com.openminis.app.logging.AppLogger
import java.io.File

/**
 * [T-tool-output-policy] Unified post-processing for every tool result
 * before it reaches the model.
 *
 * 1. Environment-variable redaction (Kelivo-equivalent): any guest env var
 *    value of 6+ chars containing both letters and digits is replaced with
 *    `[REDACTED]` — the agent gets to *see* tool output without learning
 *    the API keys / tokens that happened to leak into it.
 * 2. Oversized output spill (RikkaHub-equivalent): instead of hard-truncating,
 *    the full text is written to `tool_outputs/<toolId>.txt` inside the
 *    sandbox workspace and the model is told how to page through it with
 *    cat/grep — context growth becomes model-driven retrieval, and the
 *    full content is never silently lost.
 */
object ToolOutputPolicy {
    const val TOOL_OUTPUT_CHAR_LIMIT = 12_000
    const val TOOL_OUTPUTS_DIR = "tool_outputs"
    /** 溢出目录在 guest 里的挂载视图前缀（[ToolOutputSpill] 口径一致）。 */
    private const val GUEST_WORKSPACE = "/var/minis/workspace"
    private const val TAG = "ToolOutputPolicy"
    private const val REDACTED = "[REDACTED]"
    private const val PREVIEW_CHARS = 2_000
    internal const val OMISSION_MARKER = "========== [以下内容被省略] =========="

    /**
     * substring() 会在 UTF-16 码元边界切割，正好落在代理对中间就会产生
     * 半个 emoji（孤立的高低代理符）。这里把边界回退到码点边界，
     * 保证 head/tail 预览不会切碎任何非 BMP 字符。
     */
    private fun safeSubstring(s: String, start: Int, endExclusive: Int): String {
        val startIdx = start.coerceIn(0, s.length)
        val endIdx = endExclusive.coerceIn(startIdx, s.length)
        if (startIdx == endIdx) return ""
        // 起点落在低代理符上 → 上一个码元是它的高代理，回退一格。
        val safeStart = if (startIdx > 0 && startIdx < s.length &&
            Character.isLowSurrogate(s[startIdx]) && Character.isHighSurrogate(s[startIdx - 1])
        ) startIdx - 1 else startIdx
        // 终点切在高代理符上 → 它的低代理被甩在区间外，收回一格。
        val safeEnd = if (endIdx > safeStart && endIdx < s.length &&
            Character.isHighSurrogate(s[endIdx - 1]) && Character.isLowSurrogate(s[endIdx])
        ) endIdx - 1 else endIdx
        return s.substring(safeStart, safeEnd)
    }

    fun redactEnvVars(text: String): String {
        val env = runCatching { System.getenv() }.getOrDefault(emptyMap())
        val sensitive = env.values.filter { v ->
            v.length >= 6 && v.any { it.isLetter() } && v.any { it.isDigit() }
        }
        return redactWith(text, sensitive)
    }

    /** Testable core: apply redaction against an explicit sensitive list. */
    internal fun redactWith(text: String, sensitive: List<String>): String {
        if (sensitive.isEmpty() || text.isBlank()) return text
        // Kelivo 式结构化脱敏：JSON 里字符串值经转义后纯文本 replace 会漏
        // （引号逃逸），先解析再递归替换每个字符串值，命中面完整得多。
        runCatching {
            val node = org.json.JSONTokener(text).nextValue()
            if (node is org.json.JSONObject || node is org.json.JSONArray) {
                return redactNode(node, sensitive, depth = 0).toString()
            }
        }
        // 非 JSON 输出：退回纯文本替换。
        var out = text
        for (v in sensitive) {
            if (out.contains(v)) out = redactInText(out, v)
        }
        return out
    }

    /**
     * [T-redact-boundary] Whole-substring `replace` rewrote ANY text that
     * happened to contain a sensitive value — `5up3r` inside `s5up3rhouse`,
     * a token-shaped string embedded in an identifier. The replacement is
     * boundary-aware: the value must not be glued to further alphanumerics
     * (SQL `LIKE '%token%'`-style context still matches).
     */
    private fun redactInText(s: String, v: String): String =
        Regex("(?<![A-Za-z0-9])" + Regex.escape(v) + "(?![A-Za-z0-9])").replace(s, REDACTED)

    /** [T-redact-depth] Hard recursion bound; a hostile deep-nested JSON must
     *  not be able to StackOverflow the redactor (the caller's `runCatching`
     *  around parsing does not cover a throw from inside redactNode's tree
     *  walk — and a StackOverflowError is an Error, not an Exception).
     *  Past the cap the subtree is FLATTENED to text and plain-text redacted
     *  (fail-closed): the previous version returned the raw node, so a
     *  sensitive string nested one level past the cap reached the model
     *  unredacted — the depth bound must not double as a leak channel. */
    private const val MAX_REDACT_DEPTH = 32

    private fun redactString(s0: String, sensitive: List<String>): String {
        var s = s0
        for (v in sensitive) {
            if (s.contains(v)) s = redactInText(s, v)
        }
        return s
    }

    private fun redactNode(node: Any?, sensitive: List<String>, depth: Int): Any? {
        if (depth > MAX_REDACT_DEPTH) {
            val flat = runCatching { node.toString() }.getOrDefault(REDACTED)
            return redactString(flat, sensitive)
        }
        return when (node) {
            is org.json.JSONObject -> {
                val out = org.json.JSONObject()
                node.keys().forEach { k ->
                    // [T-redact-no-drop] The old runCatching { put } silently
                    // dropped a key whose transformed value was rejected by
                    // JSONObject (e.g. NaN) — the model saw quietly pruned
                    // JSON. On failure, fall back to the ORIGINAL value; the
                    // redaction pass is lossless with respect to structure.
                    val redacted = runCatching { redactNode(node.get(k), sensitive, depth + 1) }
                        .getOrElse { node.get(k) }
                    runCatching { out.put(k, redacted) }
                        .onFailure { runCatching { out.put(k, node.get(k)) } }
                }
                out
            }
            is org.json.JSONArray -> {
                val out = org.json.JSONArray()
                for (i in 0 until node.length()) {
                    val redacted = runCatching { redactNode(node.get(i), sensitive, depth + 1) }
                        .getOrElse { node.get(i) }
                    runCatching { out.put(redacted) }
                        .onFailure { runCatching { out.put(node.get(i)) } }
                }
                out
            }
            is String -> redactString(node, sensitive)
            else -> node
        }
    }

    /**
     * Apply both policies. [toolId] is the tool-call id; blank ids fall back
     * to a timestamped file name so no content is lost.
     *
     * [T-tool-output-host-dir] [hostWorkspaceDir] resolves the **host** directory
     * the guest sees as `/var/minis/workspace` (`SessionWorkspace.hostDir(...)`) —
     * NOT a guest path — and is only invoked when the text actually has to be
     * spilled (the steady-state call is under the limit). The old signature read a
     * mutable global `workspaceRoot` defaulting to `/var/minis/workspace`, which the
     * app process can never create on Android: both `mkdirs()` and `writeText`
     * failed, the function returned the raw text, and the 12 000-char ceiling
     * silently did not exist in production (between 12 000 and ToolOutputSpill's
     * 16 000 there was not even an omission marker). A null/failed write now
     * degrades to an explicitly annotated head/tail preview instead of the full
     * text, so the ceiling always holds.
     */
    fun apply(output: String, toolId: String, hostWorkspaceDir: () -> File?): String {
        var text = redactEnvVars(output)
        if (text.length <= TOOL_OUTPUT_CHAR_LIMIT) return text
        val safeId = toolId.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "out" }
        val fileName = "$safeId.txt"
        val hostDir = runCatching { hostWorkspaceDir() }.getOrNull()
        val file = hostDir?.let { File(File(it, TOOL_OUTPUTS_DIR), fileName) }
        val written = file != null && runCatching {
            val dir = file.parentFile
            if (dir != null && !dir.mkdirs() && !dir.isDirectory) return@runCatching false
            file.writeText(text)
            true
        }.getOrDefault(false)
        if (!written) {
            // 不再静默降级：落盘失败是本策略的能力缺失，必须留痕（原实现连一行日志都没有）。
            AppLogger.error(
                TAG,
                "tool output spill failed (toolId=$safeId, chars=${text.length}, " +
                    "hostDir=${hostDir?.absolutePath ?: "<null>"}) — " +
                    "returning annotated head/tail preview, the ${TOOL_OUTPUT_CHAR_LIMIT}-char cap still applies",
            )
        }
        val head = safeSubstring(text, 0, PREVIEW_CHARS)
        val tailStart = (text.length - PREVIEW_CHARS).coerceAtLeast(PREVIEW_CHARS)
        val tail = safeSubstring(text, tailStart, text.length)
        val omitted = tailStart - PREVIEW_CHARS
        return buildString {
            append(head)
            append("\n\n[工具输出过长：已省略中间 ")
            append(omitted)
            append(" 字符，仅保留首尾各 ")
            append(PREVIEW_CHARS)
            append(" 字符预览。全文 ")
            append(text.length)
            append(" 字符")
            if (written) {
                // 回给模型的是 guest 形式路径（/var/minis/workspace = hostWorkspaceDir 的挂载视图）。
                append("已写入沙箱 ${GUEST_WORKSPACE}/$TOOL_OUTPUTS_DIR/$fileName")
                append("，可用 shell_execute 的 cat/grep 分页检索，不用一次性读完]")
            } else {
                append("，但溢出文件写入失败（spill failed），本回合无法分页检索全文]")
            }
            append("\n\n")
            append(OMISSION_MARKER)
            append("\n\n")
            append(tail)
        }
    }
}
