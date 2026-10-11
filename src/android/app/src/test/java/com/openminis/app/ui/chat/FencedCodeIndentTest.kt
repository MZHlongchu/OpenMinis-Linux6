package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-codeblock-fence-indent] Copying a command from a code block that
 * sits inside a list item gave it leading spaces ("  npm install").
 */
class FencedCodeIndentTest {

    @Test fun `a fence under a bullet loses its two-space indent`() {
        assertEquals("npm install", fencedCodeContent("  ```bash", listOf("  npm install")))
    }

    @Test fun `a fence under a numbered item loses its three-space indent`() {
        assertEquals("git status\ngit pull", fencedCodeContent("   ```", listOf("   git status", "   git pull")))
    }

    @Test fun `deeper indentation inside the code is kept`() {
        val code = fencedCodeContent("  ```python", listOf("  def f():", "      return 1", ""))
        assertEquals("def f():\n    return 1\n", code)
    }

    @Test fun `a line indented less than the fence loses only what it has`() {
        assertEquals("a\nb", fencedCodeContent("    ```", listOf("  a", "b")))
    }

    @Test fun `an unindented fence leaves the content untouched`() {
        assertEquals("  keep\nme", fencedCodeContent("```", listOf("  keep", "me")))
    }

    /**
     * [T-merge-upstream-1-14-block-parser-split] 合并 1.14 后块解析器不在
     * `StreamingMarkdownText.kt` 里了 —— 本移植把它拆成了
     * `ui/chat/StreamingMarkdownParse.kt`（围栏分支在那里），`fencedCodeContent`
     * 留在 `StreamingMarkdownText.kt`。守卫的意图一字不改：**解析器必须经过
     * fencedCodeContent 才能建出代码块**。只是按拆分后的真实调用形状断言：
     * 本移植多一层 `addCodeChunks`（长代码要切块），它把 code 原样交给
     * `MdBlock.CodeBlock(raw, lang, code)`，所以这里把这条链也钉住 —— 否则
     * 「经过它」可能被中间层绕开而守卫看不见。
     */
    @Test fun `the parser builds code blocks through it`() {
        val parser = ProductionSources.read("ui/chat/StreamingMarkdownParse.kt")
        assertTrue(
            "the fence branch must hand fencedCodeContent's output to the block builder",
            parser.contains("addCodeChunks(rawLines.joinToString(\"\\n\"), lang, fencedCodeContent(line, codeLines))"),
        )
        val blocks = ProductionSources.read("ui/chat/StreamingMarkdownText.kt")
        assertTrue("addCodeChunks forwards the de-indented code to MdBlock.CodeBlock", blocks.contains("add(MdBlock.CodeBlock(raw, lang, code))"))
    }
}
