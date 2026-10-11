package com.openminis.app.provider.openai

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONArray
import org.json.JSONObject

// MARK: - Codex image generation (gpt-image-2)

/**
 * [T-codex-gpt-image2-oauth-android] Build the Codex image_generation
 * request body. The wire model is gpt-5.5 (the Codex backend invokes the
 * underlying gpt-image-2 via the built-in image_generation tool); the user
 * turn is the fixed "Use the image generation tool to create: <prompt>"
 * instruction. The <prompt> is the latest user text — plain string content
 * or the concatenated text parts of the last user message.
 */
internal fun OpenAIProvider.buildCodexImageBody(messages: List<LLMMessage>): JSONObject {
    val lastUser = messages.lastOrNull { it.role == LLMMessage.Role.USER }
    val prompt = lastUser?.let { m ->
        m.content.takeIf { it.isNotBlank() }
            ?: m.contentParts.filterIsInstance<AgentContentPart.Text>()
                .joinToString(" ") { it.text }.trim()
    }.orEmpty()
    return JSONObject().apply {
        put("model", "gpt-5.5")
        put("instructions", "You are a helpful assistant. Use tools when available.")
        put("input", JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("content", "Use the image generation tool to create: $prompt")
        }))
        put("store", false)
        put("tools", JSONArray().put(JSONObject().put("type", "image_generation")))
        put("reasoning", JSONObject().put("effort", "low"))
        put("include", JSONArray())
        put("tool_choice", "auto")
        put("parallel_tool_calls", true)
        put("stream", true)
    }
}

/**
 * [T-android-codex-image-stream-parse-fix] Detect an image's MIME type from
 * its magic bytes. Mirrors iOS `detectImageMime`. gpt-image-2 can return
 * PNG, JPEG, or WebP, so the previous hardcoded "image/png" mislabeled
 * non-PNG output. Falls back to image/png when too short / unrecognized.
 */
internal fun OpenAIProvider.detectImageMime(data: ByteArray): String {
    if (data.size < 4) return "image/png"
    // [T-b11-detect-image-mime-alloc] 只看前 4 字节，不能用 `data.map{}`：那会按
    // ByteArray.map 解析成 List<Int>，每个元素装箱 + ArrayList 扩容，8MB 图像（Codex
    // 后端确实会回整张 ~8MB 图）瞬时多分配上百 MB。直接索引原数组，零分配。
    val b0 = data[0].toInt() and 0xFF
    val b1 = data[1].toInt() and 0xFF
    val b2 = data[2].toInt() and 0xFF
    val b3 = data[3].toInt() and 0xFF
    return when {
        b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47 -> "image/png"
        b0 == 0xFF && b1 == 0xD8 -> "image/jpeg"
        b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46 -> "image/webp" // RIFF (WebP)
        b0 == 0x47 && b1 == 0x49 && b2 == 0x46 -> "image/gif"
        else -> "image/png"
    }
}
