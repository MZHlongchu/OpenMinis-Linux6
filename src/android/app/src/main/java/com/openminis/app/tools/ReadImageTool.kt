package com.openminis.app.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject
import java.io.ByteArrayOutputStream

object ReadImageTool {
    const val NAME = "read_image"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read an image file from the Linux filesystem and return it for visual analysis. Supports PNG, JPEG, GIF, WEBP, and other common image formats. Use this to inspect generated charts, downloaded images, screenshots, or any visual output. If you natively support vision the image is returned directly for your analysis; if you do not, it is routed through a configured Vision Group that returns a text description — in that case pass a `prompt` to focus the description on what you actually need (you cannot see the pixels yourself, so this is how you 'ask' about the image). Metadata (dimensions, file size) is always included.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'View generated bar chart', 'Inspect downloaded screenshot'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Linux path (e.g. /var/minis/attachments/chart.png) or minis:// URL (e.g. minis://attachments/chart.png)"),
            "prompt" to AgentToolParam("string", "Optional. A custom instruction describing what you want to understand from the image (e.g. 'transcribe the table text', 'describe the people and their expressions', 'what error message is in this screenshot'). Most useful when you lack native vision and the image is described by a Vision Group — it steers that description toward your question. If omitted, a generic 'describe this image in detail' instruction is used."),
        ),
        required = listOf("tool_title", "path"),
        propertyOrdering = listOf("tool_title", "path", "prompt"),
    )

    /**
     * T178: when the caller knows the owning session, prefer
     * [PRootKernel.resolveSessionHostPath] so per-session subdirs
     * (`/var/minis/{attachments,workspace,offloads,browser}/...`) resolve
     * directly against this session's host dir instead of consulting the
     * global, last-writer-wins `bindMounts` map. Without this, an agent
     * loop in session A that calls `read_image` after session B booted
     * its PRoot reads from session B's host dir — confirmed leak per
     * docs/parity/cross-session-isolation-audit.md.
     *
     * The legacy single-arg overload is preserved for callers that don't
     * know the session id (and falls back to the global map). Mirror iOS
     * `ReadImageTool` which carries `sessionId` through its tool-call
     * pipeline.
     */
    fun execute(argsJson: String, sessionId: String? = null, context: Context? = null): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val rawPath = args.optString("path", "")
            val toolTitle = args.optString("tool_title", NAME)

            if (rawPath.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }

            val path = if (rawPath.startsWith("minis://")) {
                "/var/minis/" + java.net.URLDecoder.decode(rawPath.removePrefix("minis://"), "UTF-8")
            } else rawPath

            val file = if (sessionId != null && context != null) {
                // A null here is the session jail. Falling back to the global
                // bind map would reopen the other session's file.
                PRootKernel.resolveSessionHostPath(sessionId, path, context)
            } else {
                PRootKernel.resolveHostPath(path)
            } ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false, toolTitle = toolTitle)

            if (!file.exists()) {
                return ToolExecutionResult("Error: File not found: $path", false, toolTitle = toolTitle)
            }

            // [T-p1-read-image-oom] 两段式解码：先 inJustDecodeBounds 取尺寸，再按 2 的
            // 幂 inSampleSize 解码。原实现直接 decodeFile 全尺寸解码后才 createScaledBitmap，
            // 大图（相机原图/生成的大 PNG）在 decodeFile 里就 OOM——而 OOM 是 Error，
            // 下面的 catch(Exception) 接不住，会一路穿过工具执行链杀掉进程。
            // 与同包 OcrTool.decodeForOcr 同款两段式。
            val maxEdge = 2000
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return ToolExecutionResult("Error: Cannot decode image: $path", false, toolTitle = toolTitle)
            }
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > maxEdge || bounds.outHeight / sampleSize > maxEdge) {
                sampleSize *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize.coerceAtLeast(1) }
            val original = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
                ?: return ToolExecutionResult("Error: Cannot decode image: $path", false, toolTitle = toolTitle)

            val scaled = if (original.width > maxEdge || original.height > maxEdge) {
                val scale = maxEdge.toFloat() / maxOf(original.width, original.height)
                val w = (original.width * scale).toInt()
                val h = (original.height * scale).toInt()
                Bitmap.createScaledBitmap(original, w, h, true)
            } else {
                original
            }

            try {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                val imageBytes = out.toByteArray()

                // 元数据用 bounds（真实原始尺寸）：recycle 之后再读 bitmap.width 不可靠。
                val metadata = "[$path | ${bounds.outWidth}x${bounds.outHeight} | ${file.length()} bytes]"
                ToolExecutionResult(
                    output = metadata,
                    success = true,
                    imageData = imageBytes,
                    imageMimeType = "image/jpeg",
                    toolTitle = toolTitle,
                    // Surface the source file for inline preview in the tool result UI
                    // (mirrors iOS ToolLiveSheet.readImageTool case).
                    imageFilePath = file.absolutePath,
                )
            } finally {
                if (scaled !== original) scaled.recycle()
                original.recycle()
            }
        } catch (t: Throwable) {
            // Throwable 而非 Exception：解码/缩放的 OOM 也必须变成工具错误回给模型，
            // 而不是冒泡成未捕获 Error 崩掉 app 进程。
            ToolExecutionResult("Error reading image: ${t.message ?: t.javaClass.simpleName}", false)
        }
    }
}
