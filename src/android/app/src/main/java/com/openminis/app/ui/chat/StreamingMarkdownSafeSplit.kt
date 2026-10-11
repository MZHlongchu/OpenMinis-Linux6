package com.openminis.app.ui.chat

/**
 * [T-android-streaming-incremental-inline] Largest safe offset to split [text]
 * for incremental inline re-parse of a streaming tail. The result `p` satisfies:
 *   - `p` sits immediately AFTER a `\n`, and
 *   - `text[0, p)` has every newline-SPANNING inline construct CLOSED.
 *
 * [T-incremental-inline-parity-mirror] The scanner is a **mirror of parseInline's
 * outer loop**: the same branches in the same order, with the same close-search
 * and the same "consume the whole matched span" jumps. A newline is declared safe
 * exactly when the mirrored scan REACHES it as a plain character — if parseInline
 * consumed a span across that newline, the mirror jumped over it too and the
 * newline is never recorded. That equivalence is what makes
 * `parseInline(prefix) ++ parseInline(suffix) == parseInline(text)` hold:
 *   - a construct that parseInline matches cannot straddle the boundary (the
 *     boundary newline would have been consumed by the span), and
 *   - a construct parseInline leaves literal (no closer, `\(` without `\)`, a
 *     backtick whose close-scanner stops at `\n`, a rejected `$…$`) is literal
 *     on both sides of the boundary as well.
 *
 * The previous implementation tracked flat parity flags (`**`, `__`, `~~`) plus
 * independent "is an italic open" booleans and did NOT skip the contents of a
 * matched span. Two consequences, both real:
 *   - a delimiter INSIDE a matched span (`` `a**b` ``, `\(x *y\)`) was still
 *     counted, so it could cancel a genuinely open outer span and let a split
 *     land inside it — `*_*_` + `\n` … `_` and `\(x *y\) and *open` + `\n` … `*`
 *     are the minimal shapes;
 *   - conversely, an unclosed `**` pinned the boundary at 0 forever (the
 *     flicker class this file exists to prevent: parseInline renders it
 *     literally, so freezing the prefix is safe).
 *
 * Cost, stated as implemented. A close-search that FAILS is the trap: it walks to the
 * end of the buffer (or to the next `\n`) and the loop then advances a SINGLE
 * character, so a body that opens a construct the text never closes is quadratic —
 * and this scanner runs on the main thread, once per throttle tick. The shapes that
 * actually occur are `[[[[…` with no `]`, `](…](…` with no `)`, and `\( \( \( …`:
 *  - `\(` has the [parenCloseAhead] flag — after one failed search no later one runs;
 *  - the image (`![alt](url)`) and link (`[text](url)`) branches share one
 *    [ForwardCharSearch] per closer (`]` and `)`). Each holds a one-time
 *    `lastIndexOf` of its character plus the position of the last hit, so a run of
 *    openers costs ONE scan in total instead of one per opener (and the no-closer
 *    case never searches at all).
 * The remaining delimiters (`**`, `***`, `__`, `~~`, `*`, `_`) need no budget by a
 * different argument: a successful search consumes the whole matched span, and a
 * failed one proves that delimiter has no later occurrence, so it can fail at most
 * once per scan. Inline code and `$…$` stop at the next `\n`, which caps each of
 * their scans by the line length. Returns 0 when no safe split exists (caller then
 * parses the whole thing) — conservative by construction: any doubt about closure
 * keeps the boundary earlier, never inside an open marker.
 *
 * We keep a [INCR_TAIL_MARGIN] of trailing chars unsplit so the still-growing tail
 * (where the model may still be mid-token, mid-`**`, mid-`$`) is always fully
 * re-scanned; only well-settled earlier content is frozen.
 */
private const val INCR_TAIL_MARGIN = 256

/**
 * [T-android-streaming-split-bracket-budget] A memoised `text.indexOf([ch], from)`.
 *
 * Both call sites search forward for the same closer while walking a growing
 * buffer, so the same scan used to be repeated for every opener: `[`-dense text with
 * no (or one far-away) `]` made each of them walk to the end — O(n²) on the main
 * thread. Two facts fix that, and they are the whole class:
 *  - [lastAt] is a ONE-TIME budget (a single `lastIndexOf` in the constructor): a
 *    search starting to the right of the last occurrence cannot succeed;
 *  - searches only ever start further right, so a remembered hit stays the answer
 *    while the new start is still at or before it, and a remembered miss (`-1`)
 *    proves no later search can succeed either. A start that has moved PAST the
 *    remembered hit must search again (there may be another closer beyond it) —
 *    those rescans are disjoint, which is what keeps the total work linear.
 */
private class ForwardCharSearch(private val text: String, private val ch: Char) {
    /** One-time budget: the last occurrence in the whole text; -1 = there is none. */
    private val lastAt = text.lastIndexOf(ch)

    /** Start of the search that produced [foundAt]; -1 = nothing searched yet. */
    private var searchedFrom = -1
    private var foundAt = -1

    /** Same answer as `text.indexOf(ch, from)`, without rescanning what was scanned. */
    fun first(from: Int): Int {
        if (from > lastAt) return -1
        if (searchedFrom >= 0 && from >= searchedFrom) {
            if (foundAt < 0) return -1
            if (from <= foundAt) return foundAt
        }
        val idx = text.indexOf(ch, from)
        searchedFrom = from
        foundAt = idx
        return idx
    }
}

@androidx.annotation.VisibleForTesting
internal fun safeInlineSplitOffset(text: String): Int {
    val limit = text.length - INCR_TAIL_MARGIN
    if (limit <= 0) return 0
    // Offset AFTER the last newline the mirrored scan reached as a plain char.
    var lastSafeNewlineEnd = 0
    // 惰性事实：全文是否还可能出现 `\)`。一旦某次查找失败，后续起点更靠后的查找
    // 同样会失败，把它记下来可避免 `\( \( \( …`（永远没有 `\)`）退化成 O(n²)。
    // 注意 parseInline 对“`\(` 无闭合”的处理是把 `\` 当字面量再 i++（不是吃掉
    // `(`），所以这里前进 1 而不是 2 —— 状态等价，但语义与 parseInline 逐字对齐。
    var parenCloseAhead = true
    // [T-android-streaming-split-bracket-budget] 链接 / 图片的闭合扫描同样不能每个
    // `[` 都重扫到串尾（`[` 密集无 `]`、或 `](` 密集无 `)`）。
    val closeBracket = ForwardCharSearch(text, ']')
    val closeParen = ForwardCharSearch(text, ')')

    var i = 0
    while (i < limit) {
        val c = text[i]
        when {
            // ── 1. `\( … \)` inline math（parseInline 第一分支）───────────────
            text.startsWith("\\(", i) -> {
                val end = if (parenCloseAhead) text.indexOf("\\)", i + 2) else -1
                if (end != -1) {
                    // 命中的整段（含内容）被 parseInline 当作 inline content 消费：
                    // 内容里的 `*`/`_` 一律不是分隔符，跨行也不成问题（换行被跳过，
                    // 永远不会被标记为安全）。
                    i = end + 2
                } else {
                    parenCloseAhead = false
                    i += 1
                }
            }
            // ── 2. 转义：`\*` `\_` `\[` …（跳过被转义字符）─────────────────
            c == '\\' && i + 1 < text.length -> i += 2
            // ── 3. 图片 `![alt](url)`：命中则整段消费，否则按字面量前进 1 ────
            text.startsWith("![", i) -> {
                val cb = closeBracket.first(i + 2)
                val cp = if (cb != -1 && cb + 1 < text.length && text[cb + 1] == '(') {
                    closeParen.first(cb + 2)
                } else {
                    -1
                }
                i = if (cp != -1) cp + 1 else i + 1
            }
            // ── 4. 粗斜体 `***` / 粗体 `**` `__` / 删除线 `~~` ───────────────
            text.startsWith("***", i) -> {
                val end = text.indexOf("***", i + 3)
                i = if (end != -1) end + 3 else i + 1
            }
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                i = if (end != -1) end + 2 else i + 1
            }
            text.startsWith("__", i) -> {
                val end = text.indexOf("__", i + 2)
                i = if (end != -1) end + 2 else i + 1
            }
            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                i = if (end != -1) end + 2 else i + 1
            }
            // ── 5. 泄漏进行内的 ``` 围栏：parseInline 原样输出 3 个字符 ───────
            text.startsWith("```", i) -> i += 3
            // ── 6. 行内数学 `$…$`（`$$` 与空格开头不开；闭合扫描止于换行）────
            c == '$' && i + 1 < text.length && text[i + 1] != '$' && text[i + 1] != ' ' -> {
                val end = findInlineMathClose(text, i + 1)
                if (end != -1) {
                    val latex = text.substring(i + 1, end)
                    // 被 looksLikeMath / isTablePipeArtifact 拒绝时 parseInline 只输出
                    // `$` 再前进 1（内容继续参与扫描）——镜像必须同样不消费。
                    i = if (looksLikeMath(latex) && !isTablePipeArtifact(latex)) end + 1 else i + 1
                } else {
                    i += 1
                }
            }
            // ── 7. 行内代码：闭合扫描止于 `\n`，未闭合即字面量 ───────────────
            c == '`' -> {
                val close = findInlineCodeClose(text, i + 1)
                i = if (close != -1) close + 1 else i + 1
            }
            // ── 8. 链接 `[text](url)`；parseInline 会跳过紧跟在 `!` 后的 `[` ──
            c == '[' && !(i > 0 && text[i - 1] == '!') -> {
                val cb = closeBracket.first(i + 1)
                val cp = if (cb != -1 && cb + 1 < text.length && text[cb + 1] == '(') {
                    closeParen.first(cb + 2)
                } else {
                    -1
                }
                i = if (cp != -1) cp + 1 else i + 1
            }
            // ── 9. 单分隔符斜体 `*…*` / `_…_`：闭合用普通 indexOf，能跨行 ────
            (c == '*' || c == '_') && i + 1 < text.length &&
                text[i + 1] != c && text[i + 1] != ' ' -> {
                val end = text.indexOf(c, i + 1)
                i = if (end != -1 && end > i + 1) end + 1 else i + 1
            }
            // ── 10. 走到换行 = parseInline 也把这里当普通字符：安全切点 ────────
            c == '\n' -> {
                lastSafeNewlineEnd = i + 1
                i += 1
            }
            else -> i += 1
        }
    }
    return lastSafeNewlineEnd
}
