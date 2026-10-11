package com.openminis.app.provider.openai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-android-openrouter-reasoning-details] Port of iOS ReasoningDetailsParseTests
 * (8d17198d6, GH#263): OpenRouter's structured `delta.reasoning_details` array
 * was never read, so reasoning sent only that way left the turn empty.
 */
class ReasoningDetailsTest {

    private fun parse(json: String) = ReasoningDetails.parse(JSONObject(json))

    /** Mirrors the provider: string fields first, then details. */
    private fun run(vararg chunks: String): String = buildString {
        for (c in chunks) {
            val d = JSONObject(c)
            val rc = listOf("reasoning_content", "reasoning", "reasoning_text")
                .map { (d.opt(it) as? String).orEmpty() }.firstOrNull { it.isNotEmpty() }.orEmpty()
            append(rc)
            ReasoningDetails.parse(d)?.let { append(it.text) }
        }
    }

    @Test
    fun `reasoning text items are accumulated across chunks`() {
        assertEquals(
            "Let me think. Done.",
            run(
                """{"reasoning_details":[{"type":"reasoning.text","text":"Let me think. ","index":0}]}""",
                """{"reasoning_details":[{"type":"reasoning.text","text":"Done.","index":0}]}""",
            ),
        )
    }

    @Test
    fun `summary is read from its own key`() {
        assertEquals("Plan: X", parse("""{"reasoning_details":[{"type":"reasoning.summary","summary":"Plan: X"}]}""")!!.text)
    }

    @Test
    fun `encrypted items add no text but count as reasoning`() {
        val p = parse(
            """{"reasoning_details":[{"type":"reasoning.encrypted","data":"gAAAA"},{"type":"reasoning.encrypted","data":"gBBBB"}]}""",
        )!!
        assertEquals("", p.text)
        assertEquals(2, p.encryptedCount)
        assertTrue(p.sawReasoning)
    }

    @Test
    fun `unknown item types are ignored`() {
        assertNull(parse("""{"reasoning_details":[{"type":"reasoning.future","x":1}]}"""))
    }

    @Test
    fun `mixed items keep their order`() {
        assertEquals(
            "S T",
            parse("""{"reasoning_details":[{"type":"reasoning.summary","summary":"S "},{"type":"reasoning.text","text":"T"}]}""")!!.text,
        )
    }

    @Test
    fun `a JSON null text is empty, not the word null`() {
        assertEquals("", parse("""{"reasoning_details":[{"type":"reasoning.text","text":null}]}""")!!.text)
    }

    @Test
    fun `string plus details for the same text counts it once`() {
        assertEquals(
            "Hmm. Ok.",
            run(
                """{"reasoning":"Hmm. ","reasoning_details":[{"type":"reasoning.text","text":"Hmm. "}]}""",
                """{"reasoning":"Ok.","reasoning_details":[{"type":"reasoning.text","text":"Ok."}]}""",
            ),
        )
        assertNull(parse("""{"reasoning_content":"A","reasoning_details":[{"type":"reasoning.text","text":"A"}]}"""))
    }

    @Test
    fun `an empty string field does not suppress the details`() {
        assertEquals("real", run("""{"reasoning":"","reasoning_details":[{"type":"reasoning.text","text":"real"}]}"""))
    }

    @Test
    fun `plain string reasoning is unchanged and no details means null`() {
        assertEquals("xy", run("""{"reasoning":"x"}""", """{"reasoning":"y"}"""))
        assertNull(parse("""{"content":"hi"}"""))
    }

    /**
     * [T-android-openai-rawstream-split] 上游 1.14 把 chat-completions 的 SSE
     * 解析留在 OpenAIProvider.kt 里；本移植把它拆进了同包的 OpenAIRawStream.kt
     * （[T-android-seam-extraction]：provider 只搬请求形状，流解析单独一片）。
     * 扫描面因此从「那一个文件」改成「provider/openai 包里，能同时看到两处接线的
     * 那个文件」—— 上游按文件名点名，本移植按内容点名。两处接线仍然必须同时存在，
     * 断言一条未删、语义未放宽。
     */
    @Test
    fun `the chat-completions parser is wired to ReasoningDetails`() {
        val dir = listOf(
            File("src/main/java/com/openminis/app/provider/openai"),
            File("app/src/main/java/com/openminis/app/provider/openai"),
        ).first { it.isDirectory }
        val parsers = dir.listFiles { f: File -> f.isFile && f.extension == "kt" }
            ?.sortedBy { it.name }.orEmpty()
        val src = parsers.joinToString("\n") { it.readText() }
        assertTrue(src.contains("ReasoningDetails.parse(d)?.let { rd ->"))
        assertTrue(src.contains("encryptedReasoningChunks=\$encryptedReasoningChunks"))
        // 两处接线必须在**同一个**解析器文件里：分开在两个文件里说明解析器被搬过，
        // 该文件自己已经不认识 ReasoningDetails（上游单文件扫描的等价强度）。
        val wired = parsers.filter { f ->
            val text = f.readText()
            text.contains("ReasoningDetails.parse(d)?.let { rd ->") &&
                text.contains("encryptedReasoningChunks=\$encryptedReasoningChunks")
        }
        assertTrue(
            "no single provider/openai file wires the parser to ReasoningDetails: " +
                parsers.joinToString { it.name },
            wired.isNotEmpty(),
        )
    }
}
