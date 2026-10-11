package com.openminis.app.provider.openai

import com.openminis.app.data.model.LLMModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-b11-detect-image-mime-alloc] `detectImageMime` 只该读魔数的前 4 字节。
 *
 * 原实现 `data.map { it.toInt() and 0xFF }` 会走 `ByteArray.map` 的
 * `List<Int>` 重载：每个元素装箱、配一个引用数组并扩容。Codex 后端会回整张
 * ~8MB 的图（见 OpenAICodexImageStream 的注释），一次检测就多分配上百 MB。
 * 改成直接索引后**语义必须逐字节一致**，这些用例锁住全部五个分支 + 短输入回退，
 * 并把「只读前 4 字节」的契约用 8MB 输入钉住。
 */
class DetectImageMimeTest {

    private val provider = OpenAIProvider(apiKey = "test-key", model = LLMModel.gpt4oMini)

    private fun image(vararg magic: Int, totalBytes: Int = 4): ByteArray {
        val bytes = ByteArray(totalBytes)
        magic.forEachIndexed { i, v -> bytes[i] = v.toByte() }
        return bytes
    }

    @Test
    fun `png magic bytes`() {
        assertEquals("image/png", provider.detectImageMime(image(0x89, 0x50, 0x4E, 0x47)))
    }

    @Test
    fun `jpeg magic bytes`() {
        // FF D8 alone is the JPEG SOI marker; the 4th byte is not consulted.
        assertEquals("image/jpeg", provider.detectImageMime(image(0xFF, 0xD8, 0xFF, 0xE0)))
    }

    @Test
    fun `riff magic bytes map to webp`() {
        assertEquals("image/webp", provider.detectImageMime(image(0x52, 0x49, 0x46, 0x46)))
    }

    @Test
    fun `gif magic bytes`() {
        assertEquals("image/gif", provider.detectImageMime(image(0x47, 0x49, 0x46, 0x38)))
    }

    @Test
    fun `unknown magic falls back to png`() {
        // BMP — gpt-image-2 does not return it, so the caller keeps the old
        // "assume PNG" behaviour rather than inventing a new label.
        assertEquals("image/png", provider.detectImageMime(image(0x42, 0x4D, 0x00, 0x00)))
    }

    @Test
    fun `too short input falls back to png without indexing past the end`() {
        for (size in 0..3) {
            assertEquals(
                "size=$size must fall back, not throw",
                "image/png",
                provider.detectImageMime(ByteArray(size)),
            )
        }
    }

    @Test
    fun `8MB image is detected from its first bytes`() {
        val bytes = image(0x89, 0x50, 0x4E, 0x47, totalBytes = 8 * 1024 * 1024)
        assertEquals("image/png", provider.detectImageMime(bytes))
    }
}
