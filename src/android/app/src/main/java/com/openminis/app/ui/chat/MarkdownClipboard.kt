package com.openminis.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * Clipboard helpers that mirror the iOS chat-message context menu actions
 * (`SelectableMarkdownTextView` → `copyMarkdown` / `copyRichText`).
 *
 * iOS exports rich text as RTF via `UIPasteboard.setData(rtfData, forPasteboardType: "public.rtf")`.
 * Android's pasteboard equivalent is [ClipData.newHtmlText], which carries both
 * plain-text and HTML payloads — Notes, Gmail, Docs, etc. all consume the HTML
 * variant when pasting "with formatting." We render the markdown to a
 * lightweight HTML subset locally instead of pulling in a parser dependency.
 *
 * Three flavors:
 *   - [copyPlain]    — markdown stripped to readable plain text (default Copy)
 *   - [copyMarkdown] — raw markdown source verbatim
 *   - [copyRichText] — HTML+plain dual payload for paste-with-formatting
 */
object MarkdownClipboard {

    fun copyPlain(context: Context, markdown: String, label: String = "Message") {
        val plain = markdownToPlainText(markdown)
        clipboard(context).setPrimaryClip(ClipData.newPlainText(label, plain))
    }

    fun copyMarkdown(context: Context, markdown: String, label: String = "Markdown") {
        clipboard(context).setPrimaryClip(ClipData.newPlainText(label, markdown))
    }

    fun copyRichText(context: Context, markdown: String, label: String = "Rich Text") {
        val plain = markdownToPlainText(markdown)
        val html = markdownToHtml(markdown)
        clipboard(context).setPrimaryClip(ClipData.newHtmlText(label, plain, html))
    }

    private fun clipboard(context: Context): ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /**
     * Strip markdown syntax, leaving the visible reading order intact. Goals:
     *   - emphasis/strong/strike markers removed, content kept
     *   - inline code / fenced code unwrapped (content preserved verbatim)
     *   - link `[text](url)` → `text` (drop URL, matches iOS Copy behavior)
     *   - images `![alt](url)` → `alt`
     *   - heading `#`, blockquote `>`, list bullets `- * +` and ordered `1.` stripped
     *   - tables flattened to ` | ` separated lines (good enough for paste targets that don't render markdown)
     */
    fun markdownToPlainText(markdown: String): String {
        val out = StringBuilder()
        val lines = markdown.lines()
        var i = 0
        var inFence = false
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence
                i++; continue
            }
            if (inFence) {
                out.append(line).append('\n')
                i++; continue
            }
            // Heading
            val heading = Regex("^(#{1,6})\\s+(.*)$").find(trimmed)
            if (heading != null) {
                out.append(stripInline(heading.groupValues[2])).append('\n')
                i++; continue
            }
            // Blockquote
            if (trimmed.startsWith(">")) {
                out.append(stripInline(trimmed.removePrefix(">").trimStart())).append('\n')
                i++; continue
            }
            // Unordered list
            val ul = Regex("^([-*+])\\s+(.*)$").find(trimmed)
            if (ul != null) {
                out.append("• ").append(stripInline(ul.groupValues[2])).append('\n')
                i++; continue
            }
            // Ordered list
            val ol = Regex("^(\\d+)\\.\\s+(.*)$").find(trimmed)
            if (ol != null) {
                out.append(ol.groupValues[1]).append(". ")
                    .append(stripInline(ol.groupValues[2])).append('\n')
                i++; continue
            }
            // Horizontal rule
            if (Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(trimmed)) {
                out.append('\n'); i++; continue
            }
            // Table separator row (|---|---|): drop entirely.
            if (Regex("^\\|?\\s*:?-{3,}.*$").matches(trimmed) && trimmed.contains('-')) {
                i++; continue
            }
            // Generic line — strip inline markers
            out.append(stripInline(line)).append('\n')
            i++
        }
        return out.toString().trimEnd('\n')
    }

    /**
     * Minimal markdown → HTML for paste-with-formatting. Covers the syntax
     * the chat actually produces. Not a full CommonMark renderer; we lean on
     * reader-side leniency (Gmail/Notes/Docs all tolerate sparse HTML).
     */
    fun markdownToHtml(markdown: String): String {
        val sb = StringBuilder()
        sb.append("<html><body>")
        val lines = markdown.lines()
        var i = 0
        var inFence = false
        var fenceLang: String? = null
        val fenceBuf = StringBuilder()
        var listType: String? = null  // "ul" | "ol"
        fun closeList() {
            if (listType != null) {
                sb.append("</$listType>")
                listType = null
            }
        }
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            // Fenced code
            val fenceMatch = Regex("^(```|~~~)(.*)$").find(trimmed)
            if (fenceMatch != null) {
                if (!inFence) {
                    closeList()
                    inFence = true
                    fenceLang = fenceMatch.groupValues[2].trim().ifEmpty { null }
                    fenceBuf.clear()
                } else {
                    inFence = false
                    sb.append("<pre><code")
                    fenceLang?.let { sb.append(" class=\"language-").append(escapeHtml(it)).append("\"") }
                    sb.append(">").append(escapeHtml(fenceBuf.toString().trimEnd('\n'))).append("</code></pre>")
                    fenceBuf.clear()
                }
                i++; continue
            }
            if (inFence) {
                fenceBuf.append(line).append('\n')
                i++; continue
            }
            // Blank line: close paragraph context
            if (trimmed.isEmpty()) {
                closeList()
                i++; continue
            }
            // Heading
            val heading = Regex("^(#{1,6})\\s+(.*)$").find(trimmed)
            if (heading != null) {
                closeList()
                val level = heading.groupValues[1].length
                sb.append("<h").append(level).append(">")
                    .append(inlineToHtml(heading.groupValues[2]))
                    .append("</h").append(level).append(">")
                i++; continue
            }
            // Blockquote
            if (trimmed.startsWith(">")) {
                closeList()
                sb.append("<blockquote>")
                    .append(inlineToHtml(trimmed.removePrefix(">").trimStart()))
                    .append("</blockquote>")
                i++; continue
            }
            // Horizontal rule
            if (Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(trimmed)) {
                closeList(); sb.append("<hr/>"); i++; continue
            }
            // Unordered list
            val ul = Regex("^([-*+])\\s+(.*)$").find(trimmed)
            if (ul != null) {
                if (listType != "ul") { closeList(); sb.append("<ul>"); listType = "ul" }
                sb.append("<li>").append(inlineToHtml(ul.groupValues[2])).append("</li>")
                i++; continue
            }
            // Ordered list
            val ol = Regex("^(\\d+)\\.\\s+(.*)$").find(trimmed)
            if (ol != null) {
                if (listType != "ol") { closeList(); sb.append("<ol>"); listType = "ol" }
                sb.append("<li>").append(inlineToHtml(ol.groupValues[2])).append("</li>")
                i++; continue
            }
            // Plain paragraph line
            closeList()
            sb.append("<p>").append(inlineToHtml(line)).append("</p>")
            i++
        }
        closeList()
        if (inFence && fenceBuf.isNotEmpty()) {
            sb.append("<pre><code>").append(escapeHtml(fenceBuf.toString().trimEnd('\n'))).append("</code></pre>")
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    // ─── Inline marker patterns (shared by both copy flavors) ───────────────
    //
    // The `*`/`_` emphasis rules carry flanking conditions — the OUTER side must
    // be line-start/whitespace/punctuation, the INNER side non-whitespace —
    // instead of the old "not `**`/`__`" lookarounds. Without them a Copy ate
    // the middle of identifiers: `last_mouse_click_ms` came back as
    // `lastmouse_click_ms`, `my_file_name.kt` as `myfile_name.kt`, and the
    // asterisks in `a*b*c` vanished. Dropping characters from the clipboard
    // payload is silent corruption: the paste target shows no sign it happened.
    // [T-b10-copy-inline-emphasis-flanking]
    private val IMAGE_RE = Regex("!\\[([^\\]]*)\\]\\([^)]*\\)")
    private val LINK_RE = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")
    private val BOLD3_STAR_RE = Regex("\\*\\*\\*(.+?)\\*\\*\\*")
    private val BOLD3_UNDER_RE = Regex("___(.+?)___")
    private val BOLD_STAR_RE = Regex("\\*\\*(.+?)\\*\\*")
    private val BOLD_UNDER_RE = Regex("__(.+?)__")
    private val ITALIC_STAR_RE = Regex("(?<![\\w*])\\*(?!\\s)([^*\\n]*[^\\s*])\\*(?![\\w*])")
    private val ITALIC_UNDER_RE = Regex("(?<![\\w_])_(?!\\s)([^_\\n]*[^\\s_])_(?![\\w_])")
    private val STRIKE_RE = Regex("~~(.+?)~~")

    /** Delimiter wrapping the code-span placeholders below; NUL cannot appear
     *  in chat markdown, so it can't be forged by a reply. Same sentinel the
     *  previous inline-code extraction used. */
    private const val CODE_SENTINEL = '\u0000'

    private val CODE_RE = Regex("`([^`]+)`")
    private val CODE_PLACEHOLDER_RE = Regex("$CODE_SENTINEL(\\d+)$CODE_SENTINEL")

    /**
     * Pull inline `code` spans out into placeholders so no marker rule can
     * rewrite literal text.
     *
     * Previously the backticks were stripped FIRST and the emphasis/link rules
     * then ran over the code body, so `` `last_mouse_click_ms` `` lost its
     * underscores and `[text](url)` inside a span was resolved — the copy
     * rewrote text the user had explicitly marked as literal.
     * [T-b10-copy-inline-code-literal]
     */
    private fun extractCodeSpans(s: String, codes: MutableList<String>): String =
        CODE_RE.replace(s) { m ->
            codes.add(m.groupValues[1])
            "$CODE_SENTINEL${codes.size - 1}$CODE_SENTINEL"
        }

    /** Inverse of [extractCodeSpans]; [wrap] renders each code body verbatim. */
    private fun restoreCodeSpans(
        s: String,
        codes: List<String>,
        wrap: (String) -> String,
    ): String = CODE_PLACEHOLDER_RE.replace(s) { m -> wrap(codes[m.groupValues[1].toInt()]) }

    /** Strip inline markdown markers, return readable text. */
    private fun stripInline(s: String): String {
        val codes = mutableListOf<String>()
        var t = extractCodeSpans(s, codes)
        // Images: ![alt](url) → alt
        t = IMAGE_RE.replace(t, "$1")
        // Links: [text](url) → text
        t = LINK_RE.replace(t, "$1")
        // Bold/italic/strike — order matters: longest delimiter first.
        t = BOLD3_STAR_RE.replace(t, "$1")
        t = BOLD3_UNDER_RE.replace(t, "$1")
        t = BOLD_STAR_RE.replace(t, "$1")
        t = BOLD_UNDER_RE.replace(t, "$1")
        t = ITALIC_STAR_RE.replace(t, "$1")
        t = ITALIC_UNDER_RE.replace(t, "$1")
        t = STRIKE_RE.replace(t, "$1")
        // Inline code: `code` → code (verbatim, never re-marked-up).
        return restoreCodeSpans(t, codes) { it }
    }

    /** Inline markdown → HTML, escaping non-markdown characters. */
    private fun inlineToHtml(s: String): String {
        // Pull code spans out first so their contents aren't re-escaped/processed.
        val codes = mutableListOf<String>()
        var work = escapeHtml(extractCodeSpans(s, codes))
        // Images: ![alt](url)
        work = Regex("!\\[([^\\]]*)\\]\\(([^)]+)\\)").replace(work) { m ->
            "<img alt=\"${m.groupValues[1]}\" src=\"${m.groupValues[2]}\"/>"
        }
        // Links: [text](url)
        work = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)").replace(work) { m ->
            "<a href=\"${m.groupValues[2]}\">${m.groupValues[1]}</a>"
        }
        // Bold ***x*** / ___x___
        work = BOLD3_STAR_RE.replace(work, "<strong><em>$1</em></strong>")
        work = BOLD3_UNDER_RE.replace(work, "<strong><em>$1</em></strong>")
        // Bold **x**
        work = BOLD_STAR_RE.replace(work, "<strong>$1</strong>")
        work = BOLD_UNDER_RE.replace(work, "<strong>$1</strong>")
        // Italic *x* / _x_ — same flanking conditions as stripInline, so an
        // identifier never picks up a spurious <em> that pastes as italics.
        work = ITALIC_STAR_RE.replace(work, "<em>$1</em>")
        work = ITALIC_UNDER_RE.replace(work, "<em>$1</em>")
        // Strikethrough ~~x~~
        work = STRIKE_RE.replace(work, "<del>$1</del>")
        // Restore code spans (and HTML-escape their inside), re-wrapping them in
        // the `<code>` element — that wrapper IS the code span on this path:
        // Copy Rich Text exists so the paste target renders the span as code
        // instead of as loose prose.
        // [T-f6-rich-text-code-span-wrapper] 把两处代码跨度还原合并进
        // restoreCodeSpans 时只留下了 escapeHtml，包裹标签被丢掉，行内代码在
        // HTML 载荷里退化成普通文字（纯文本路径的 `{ it }` 才是正确的）。
        return restoreCodeSpans(work, codes) { "<code>${escapeHtml(it)}</code>" }
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
