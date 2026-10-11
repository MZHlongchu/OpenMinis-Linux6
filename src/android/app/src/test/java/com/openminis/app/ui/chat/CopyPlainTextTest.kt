package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-copy-full-plain-text] "Copy Full Reply ▸ Plain Text" copies the
 * RENDERED text, not the raw markdown with its markers deleted.
 *
 * The distinction from iOS `1b4353367`: headings, lists and quotes land the
 * way the user SEES them — no `#`, `-`, `**`, `>` left behind — but tables and
 * math still expand, because dropping those loses *content*, not formatting.
 * A table that came back as an empty line would be a silent data loss in the
 * paste target.
 */
class CopyPlainTextTest {

    private fun plain(md: String) = MarkdownClipboard.markdownToPlainText(md)

    // ─── Markers are stripped ────────────────────────────────────────────

    @Test
    fun `heading markers are removed but the text stays`() {
        val out = plain("# Title\n## Subtitle")
        assertTrue("heading text kept", out.contains("Title"))
        assertTrue("subheading text kept", out.contains("Subtitle"))
        assertFalse("no hash markers", out.contains("#"))
    }

    @Test
    fun `emphasis markers are removed but the words stay`() {
        val out = plain("Some **bold** and *italic* and ~~struck~~ text.")
        assertTrue(out.contains("bold"))
        assertTrue(out.contains("italic"))
        assertTrue(out.contains("struck"))
        assertFalse("no asterisks", out.contains("*"))
        assertFalse("no tildes", out.contains("~"))
    }

    @Test
    fun `blockquote marker is removed but the quote stays`() {
        val out = plain("> quoted wisdom")
        assertTrue(out.contains("quoted wisdom"))
        assertFalse(out.trimStart().startsWith(">"))
    }

    @Test
    fun `link text is kept and the URL is dropped`() {
        val out = plain("See [the docs](https://example.com/page) for more.")
        assertTrue("anchor text kept", out.contains("the docs"))
        assertFalse("url dropped", out.contains("example.com"))
        assertFalse(out.contains("]("))
    }

    // ─── Content is NOT lost ─────────────────────────────────────────────

    @Test
    fun `table cell content survives`() {
        // The separator row is dropped as pure formatting, but every cell
        // value must still be present — losing them is losing content.
        val out = plain(
            """
            | Col A | Col B |
            |-------|-------|
            | 1     | 2     |
            | 3     | 4     |
            """.trimIndent(),
        )
        listOf("Col A", "Col B", "1", "2", "3", "4").forEach {
            assertTrue("table cell '$it' survives, got:\n$out", out.contains(it))
        }
        assertFalse("separator row dropped", out.contains("---"))
    }

    @Test
    fun `math content survives`() {
        val out = plain("Math: \$x^2 + y^2 = z^2\$")
        assertTrue("formula body kept, got: $out", out.contains("x^2"))
        assertTrue(out.contains("z^2"))
    }

    @Test
    fun `fenced code block content survives verbatim`() {
        val out = plain("```kotlin\nval x = 1\n```")
        assertTrue("code line kept", out.contains("val x = 1"))
        assertFalse("fence markers dropped", out.contains("```"))
    }

    @Test
    fun `list item text survives with the bullet normalized`() {
        val out = plain("- first\n- second")
        assertTrue(out.contains("first"))
        assertTrue(out.contains("second"))
        // Raw markdown bullets are replaced by a rendered bullet glyph.
        assertFalse("no raw dash bullets", out.lines().any { it.trimStart().startsWith("- ") })
    }

    @Test
    fun `ordered list keeps its numbering`() {
        val out = plain("1. alpha\n2. beta")
        assertTrue(out.contains("1."))
        assertTrue(out.contains("alpha"))
        assertTrue(out.contains("2."))
        assertTrue(out.contains("beta"))
    }

    // ─── Plain text differs from raw markdown ────────────────────────────

    @Test
    fun `plain text is not merely the raw markdown`() {
        // Guards against a regression where the Plain Text action is wired to
        // the same source as Copy Markdown — the two must produce different
        // output for marked-up input, or the menu item is a lie.
        val md = "# Title\n\nSome **bold** text."
        assertFalse(plain(md) == md)
    }

    // ─── [T-b10-copy-inline-emphasis-flanking] Identifiers survive a Copy ──
    //
    // The `_`/`*` emphasis rules used to fire on any pairing delimiter, so
    // copying a reply containing `last_mouse_click_ms` produced
    // `lastmouse_click_ms` — characters silently deleted from the clipboard
    // payload. Flanking conditions (outer side line-start/whitespace/punctuation)
    // keep such identifiers intact.

    @Test
    fun `snake_case identifiers keep every underscore`() {
        val out = plain("Set last_mouse_click_ms and open my_file_name.kt")
        assertTrue("underscores kept, got: $out", out.contains("last_mouse_click_ms"))
        assertTrue("filename kept, got: $out", out.contains("my_file_name.kt"))
    }

    @Test
    fun `intraword asterisks are not eaten as emphasis`() {
        val out = plain("a*b*c and 2 * 3 * 4")
        assertTrue("got: $out", out.contains("a*b*c"))
        assertTrue("multiplication kept, got: $out", out.contains("2 * 3 * 4"))
    }

    @Test
    fun `genuine emphasis is still stripped`() {
        val out = plain("Some *italic*, _also italic_, **bold** and ~~struck~~.")
        assertTrue("got: $out", out.contains("italic"))
        assertTrue("got: $out", out.contains("also italic"))
        assertTrue("got: $out", out.contains("bold"))
        assertTrue("got: $out", out.contains("struck"))
        assertFalse("no asterisks left, got: $out", out.contains("*"))
        assertFalse("no underscores left, got: $out", out.contains("_"))
        assertFalse("no tildes left, got: $out", out.contains("~"))
    }

    // ─── [T-b10-copy-inline-code-literal] Code spans stay literal ─────────

    @Test
    fun `inline code content is copied verbatim`() {
        // The backticks go away (they are markers), but the body must not be
        // re-interpreted: `_`/`*` inside a span are literal by definition, and
        // a link-looking span must not lose its brackets to the link rule.
        val out = plain("Run `last_mouse_click_ms` then `[text](url)`")
        assertTrue("underscores kept, got: $out", out.contains("last_mouse_click_ms"))
        assertTrue("brackets kept, got: $out", out.contains("[text](url)"))
        assertFalse("backticks dropped, got: $out", out.contains("`"))
    }

    @Test
    fun `rich text does not italicize identifiers`() {
        val html = MarkdownClipboard.markdownToHtml("Call last_mouse_click_ms and a*b*c")
        assertFalse("no bogus <em>, got: $html", html.contains("<em>"))
        assertTrue("identifier kept, got: $html", html.contains("last_mouse_click_ms"))
    }

    @Test
    fun `rich text keeps a code span literal`() {
        val html = MarkdownClipboard.markdownToHtml("Run `last_mouse_click_ms` now")
        assertTrue("code span kept, got: $html", html.contains("<code>last_mouse_click_ms</code>"))
        assertFalse("no emphasis inside code, got: $html", html.contains("<em>"))
    }
}
