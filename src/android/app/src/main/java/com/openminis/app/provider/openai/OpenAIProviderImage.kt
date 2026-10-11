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
        // [T-codex-gpt-image25-android] (上游 1.14) 2.5 变体要在工具对象里自报
        // 型号；gpt-image-2 保持裸 {type:image_generation}。
        //
        // 裸对象让后端挑默认型号，那正是 gpt-image-2 一直依赖的行为 —— 给它补
        // 上 model 字段等于把一个当前属于后端的决定钉死，所以这里只做加法，那条
        // 路径逐字节不变。镜像 CLIProxyAPI PR #5642。
        //
        // 拆分丢的是这一半：集合与路由门控在 OpenAIProvider.kt 里，写字段的
        // 构造函数在 OpenAIProviderImage.kt 里，合并时只落了前者。
        val imageTool = JSONObject().put("type", "image_generation")
        if (model.id in OpenAIProvider.CODEX_IMAGE_25_MODEL_IDS) imageTool.put("model", model.id)
        put("tools", JSONArray().put(imageTool))
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
