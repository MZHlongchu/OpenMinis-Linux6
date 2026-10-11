package com.openminis.app.provider

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.openminis.app.logging.AppLogger
import java.io.ByteArrayOutputStream

/**
 * Per-message inline-image byte budget — mirrors iOS AIChatViewModel.swift
 * kPerImageMaxBytes / kMessageImageMaxBytes (commit b830360).
 *
 * Anthropic returns HTTP 413 ("Downloaded image content cannot exceed 30MB")
 * when the request's total inline image payload exceeds the provider cap.
 * Pre-T-imgsize we only resized each attachment to a max-edge of 2000px at
 * JPEG q=85, which is *resolution* budgeting and does nothing for byte size
 * when the source is a 12MP HEIC. We now enforce a real byte cap at two
 * layers:
 *
 *   1. Composer (ChatViewModel.prepareUserAttachments) — runs the user's
 *      brand-new attachments through [compressUnderBudget] so every part is
 *      ≤ MAX_PER_IMAGE_BYTES, then tallies cumulative bytes and drops the
 *      tail with a Snackbar notice once MAX_TOTAL_BYTES is exceeded.
 *   2. Provider boundary (AnthropicProvider / OpenAIProvider) — belt and
 *      braces for history image parts that bypass the composer (e.g. tool
 *      results screenshot bytes, restored sessions, retry-after-edit). Each
 *      oversize part is silently re-encoded in-place via [compressBytes].
 *
 * URL HEAD pre-check (spec §2.b) intentionally omitted — both Android
 * providers always base64-inline image bytes (no remote URL forwarding),
 * mirroring the iOS finding in commit b830360.
 */
object ImageBudget {
    /** Single image bytes ceiling before re-encode kicks in. */
    const val MAX_PER_IMAGE_BYTES = 5L * 1024 * 1024

    /** Cumulative inline-image bytes per user message. */
    const val MAX_TOTAL_BYTES = 25L * 1024 * 1024

    /**
     * Cumulative inline-image bytes across ALL messages in a single
     * request body. Mirrors the per-message cap but applies at the
     * request boundary so a long history accumulating images from
     * multiple turns (browser screenshots, attachments, read_image
     * results) cannot push the request past the cap that triggered
     * factory.pub / Anthropic gateways to silently return 200 +
     * empty SSE with `finish_reason=stop`. Eldest images are elided
     * to text placeholders first.
     */
    const val MAX_REQUEST_BYTES = 25L * 1024 * 1024

    /** Default re-encode target longest edge in pixels. */
    const val MAX_EDGE_PX = 2000

    /** Default re-encode JPEG quality (0-100). */
    const val JPEG_QUALITY = 80

    private const val TAG = "ImageBudget"

    /**
     * (maxEdge, quality) candidates the per-image compressor walks until the
     * output fits under [MAX_PER_IMAGE_BYTES]. Mirrors the iOS ladder from
     * AIChatViewModel.swift compressedImageDataUnderBudget(...).
     */
    private val LADDER: List<Pair<Int, Int>> = listOf(
        2000 to 80,
        1600 to 75,
        1280 to 70,
        1024 to 65,
        896 to 55,
        768 to 50,
        640 to 45,
    )

    // ─── [T-imgsize-recompress-cache] 压缩结果缓存 ───────────────────────────

    /**
     * 每个 agent iteration 都会用同一批历史图片重新构造请求体，而
     * [compressUnderBudget] 之前没有任何记忆化：一张超过 5MB 的历史图（恢复会话读到
     * 的是原始全尺寸字节、超大 PNG 工具结果）在每个请求里都要重跑两趟解码 + 缩放 +
     * JPEG 编码。这里按"内容哈希 + 目标字节数"缓存结果，命中即返回同一 ByteArray，
     * 跨 iteration / 跨并发子代理复用。
     *
     * 边界：
     *  - 只缓存**重编码成功**的结果（返回值与输入不是同一实例）——provider 侧用
     *    `safeBytes === part.data` 判断"是否被重编码"来决定 media_type，把原实例
     *    缓存后回给另一个等值输入会让该判断失真（PNG 被标成 image/jpeg）。
     *  - 有界：按条目数 + 总字节双上限做 LRU，最坏常驻 12MB。
     */
    private const val COMPRESS_CACHE_MAX_ENTRIES = 8
    private const val COMPRESS_CACHE_MAX_BYTES = 12L * 1024 * 1024

    /** accessOrder=true → 迭代顺序即 LRU 顺序（最久未用在前）。 */
    private val compressCache = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var compressCacheBytes = 0L

    private fun compressCacheGet(key: String): ByteArray? = synchronized(compressCache) {
        compressCache[key]
    }

    private fun compressCachePut(key: String, value: ByteArray) {
        synchronized(compressCache) {
            if (compressCache.containsKey(key)) return
            compressCache[key] = value
            compressCacheBytes += value.size.toLong()
            val it = compressCache.entries.iterator()
            while (it.hasNext() &&
                (compressCache.size > COMPRESS_CACHE_MAX_ENTRIES ||
                    compressCacheBytes > COMPRESS_CACHE_MAX_BYTES)
            ) {
                val eldest = it.next()
                it.remove()
                compressCacheBytes -= eldest.value.size.toLong()
            }
        }
    }

    /** null = 无法计算哈希（平台异常）→ 本次不参与缓存。 */
    private fun compressCacheKey(input: ByteArray, targetMaxBytes: Long): String? = try {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
        digest.update(input)
        digest.digest().joinToString("") { "%02x".format(it) } + "|" + targetMaxBytes
    } catch (_: Exception) {
        null
    }

    /**
     * Re-encode [input] to a JPEG with max longest edge [maxEdge] at JPEG
     * quality [q]. Returns the original bytes on decode/encode failure
     * (caller's existing payload is always safer than dropping the image).
     */
    fun compressBytes(input: ByteArray, maxEdge: Int = MAX_EDGE_PX, q: Int = JPEG_QUALITY): ByteArray {
        if (input.isEmpty()) return input
        return try {
            // Two-pass decode mirroring PhotosOffloadHandler.copyResized — sampled
            // bounds first to keep the in-memory bitmap proportional to maxEdge,
            // then scaled to the exact target after decode.
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(input, 0, input.size, boundsOpts)
            val w0 = boundsOpts.outWidth
            val h0 = boundsOpts.outHeight
            if (w0 <= 0 || h0 <= 0) return input
            var sample = 1
            while (w0 / sample > maxEdge * 2 || h0 / sample > maxEdge * 2) sample *= 2
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeByteArray(input, 0, input.size, decodeOpts)
                ?: return input
            val scale = minOf(
                maxEdge.toFloat() / decoded.width,
                maxEdge.toFloat() / decoded.height,
                1f,
            )
            val out = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else decoded
            val baos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, q.coerceIn(1, 100), baos)
            if (out !== decoded) out.recycle()
            decoded.recycle()
            baos.toByteArray()
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "compressBytes failed (${input.size}B → keeping original): ${t.message}")
            input
        }
    }

    /**
     * Try increasingly aggressive (maxEdge, quality) candidates until the
     * re-encoded JPEG fits under [targetMaxBytes]. Returns the smallest
     * encoding produced when no candidate fits — never returns null because
     * "send something" beats "fail the request".
     *
     * Re-encodes from the original bytes on each candidate so the JPEG never
     * compounds artifacts.
     */
    fun compressUnderBudget(input: ByteArray, targetMaxBytes: Long = MAX_PER_IMAGE_BYTES): ByteArray {
        if (input.size <= targetMaxBytes) return input
        // [T-imgsize-recompress-cache] 同一张历史图在每个 iteration 都被重编码，
        // 按内容哈希命中上一轮的结果。
        val cacheKey = compressCacheKey(input, targetMaxBytes)
        if (cacheKey != null) compressCacheGet(cacheKey)?.let { return it }
        var best: ByteArray = input
        var bestSize = input.size
        for ((edge, q) in LADDER) {
            val candidate = compressBytes(input, edge, q)
            if (candidate.size < bestSize) {
                best = candidate
                bestSize = candidate.size
            }
            if (candidate.size.toLong() <= targetMaxBytes) {
                AppLogger.info(TAG, "compressUnderBudget hit: ${input.size}B → ${candidate.size}B (edge=$edge q=$q)")
                if (cacheKey != null) compressCachePut(cacheKey, candidate)
                return candidate
            }
        }
        AppLogger.warning(TAG, "compressUnderBudget exhausted ladder: ${input.size}B → ${best.size}B (target=${targetMaxBytes}B)")
        // 压缩失败（compressBytes 原样返回）时不入缓存，保持 `===` 语义。
        if (cacheKey != null && best !== input) compressCachePut(cacheKey, best)
        return best
    }

    /** Result of [applyMessageBudget]. */
    data class BudgetResult(
        /** Image bytes ready to send, in original order, dropped tail removed. */
        val keptBytes: List<ByteArray>,
        /** Number of parts whose bytes were re-encoded by the ladder. */
        val compressedCount: Int,
        /** Number of tail parts dropped because the cumulative cap was hit. */
        val droppedCount: Int,
        /** Final total payload bytes after compression + drop. */
        val totalBytes: Long,
    ) {
        val mutated: Boolean get() = compressedCount > 0 || droppedCount > 0
    }

    /**
     * Walk [bytesIn] and produce a budgeted output:
     *  - Each oversize part is run through [compressUnderBudget] first.
     *  - Then cumulative bytes are summed; once the running total would
     *    exceed [MAX_TOTAL_BYTES] the remaining tail is dropped.
     */
    fun applyMessageBudget(bytesIn: List<ByteArray>): BudgetResult {
        if (bytesIn.isEmpty()) return BudgetResult(emptyList(), 0, 0, 0L)
        val kept = ArrayList<ByteArray>(bytesIn.size)
        var compressed = 0
        var dropped = 0
        var running = 0L
        for (part in bytesIn) {
            val sized = if (part.size.toLong() > MAX_PER_IMAGE_BYTES) {
                val c = compressUnderBudget(part)
                if (c.size != part.size) compressed += 1
                c
            } else part
            if (running + sized.size.toLong() > MAX_TOTAL_BYTES) {
                dropped += 1
                continue
            }
            kept.add(sized)
            running += sized.size.toLong()
        }
        if (dropped > 0 || compressed > 0) {
            AppLogger.info(
                TAG,
                "applyMessageBudget: in=${bytesIn.size} kept=${kept.size} compressed=$compressed dropped=$dropped total=${running}B",
            )
        }
        return BudgetResult(kept, compressed, dropped, running)
    }

    // ─── Request-level budget ──────────────────────────────────────────────

    /**
     * Identifies a single image part inside an outgoing request payload.
     * Used by providers to look up whether a part has been marked for
     * elision by [planRequestBudget]. The identity hash of the ByteArray
     * is sufficient — same bytes only ever appear once per request, and
     * different bytes hash uniquely enough that the worst-case collision
     * is "we keep one extra image past the budget" (safe).
     */
    @JvmInline
    value class ImagePartId(val identityHash: Int) {
        companion object {
            fun of(data: ByteArray): ImagePartId = ImagePartId(System.identityHashCode(data))
        }
    }

    /**
     * Image part scoped for budget planning. Lets the planner stay generic
     * over `LLMMessage.ImagePart` (current-turn user attachments) and
     * `AgentContentPart.ImageData` / `AgentContentPart.ToolResult.imageData`
     * (history image bytes) without depending on either type directly.
     */
    data class BudgetImage(
        val data: ByteArray,
        val linuxPath: String?,
        val mimeType: String,
    )

    /**
     * Result of [planRequestBudget].
     */
    data class RequestBudgetPlan(
        /** Identity hashes of image bytes that providers must NOT inline. */
        val droppedIds: Set<ImagePartId>,
        /** Map from dropped identity → original linux path (null if none). */
        val droppedPaths: Map<ImagePartId, String?>,
        /** Total bytes kept after planning (sum of per-image cap-clamped). */
        val keptBytes: Long,
        /** Total bytes elided. */
        val elidedBytes: Long,
        /** Number of images elided. */
        val droppedCount: Int,
        /** Total images considered (kept + dropped). */
        val totalCount: Int,
    ) {
        val mutated: Boolean get() = droppedCount > 0
    }

    /**
     * Walk all candidate images in reverse chronological order (latest
     * first) and decide which can fit under [maxBytes]. Anything beyond
     * the cap is recorded in [RequestBudgetPlan.droppedIds] so providers
     * emit a text placeholder instead of base64 bytes.
     *
     * @param images Ordered eldest → latest. The planner reverses
     *   internally so the latest user input + most recent tool results
     *   are protected from elision.
     */
    fun planRequestBudget(
        images: List<BudgetImage>,
        maxBytes: Long = MAX_REQUEST_BYTES,
    ): RequestBudgetPlan {
        if (images.isEmpty()) {
            return RequestBudgetPlan(emptySet(), emptyMap(), 0L, 0L, 0, 0)
        }
        val dropped = HashSet<ImagePartId>()
        val droppedPaths = HashMap<ImagePartId, String?>()
        var kept = 0L
        var elided = 0L
        // Walk latest → eldest so most-recent images win the budget.
        for (img in images.asReversed()) {
            val id = ImagePartId.of(img.data)
            // Per-image cap-clamped size — same ceiling
            // `compressUnderBudget` would have produced if invoked.
            val effectiveSize = minOf(img.data.size.toLong(), MAX_PER_IMAGE_BYTES)
            if (kept + effectiveSize <= maxBytes) {
                kept += effectiveSize
            } else {
                dropped.add(id)
                droppedPaths[id] = img.linuxPath
                elided += effectiveSize
            }
        }
        if (dropped.isNotEmpty()) {
            AppLogger.info(
                TAG,
                "planRequestBudget: in=${images.size} kept=${images.size - dropped.size} dropped=${dropped.size} keptBytes=${kept}B elidedBytes=${elided}B cap=${maxBytes}B",
            )
        }
        return RequestBudgetPlan(
            droppedIds = dropped,
            droppedPaths = droppedPaths,
            keptBytes = kept,
            elidedBytes = elided,
            droppedCount = dropped.size,
            totalCount = images.size,
        )
    }

    /**
     * Build the text placeholder a provider emits in place of an elided
     * image. The model gets a clear, actionable hint: this image was
     * dropped to fit the budget, and (if known) the linux path where the
     * bytes are still readable via [read_image]. Without a path the model
     * just sees that an image was elided and can ask the user to re-attach.
     */
    fun elidedImagePlaceholder(linuxPath: String?): String {
        return if (linuxPath != null) {
            "[image elided to fit 25MB request budget. Original at $linuxPath — re-fetch with `read_image $linuxPath` if you need to see it.]"
        } else {
            "[image elided to fit 25MB request budget. Original bytes no longer addressable; ask the user to re-attach if needed.]"
        }
    }

    /**
     * Lazily persist [data] to a session-scoped spillover dir under
     * `attachments/spillover/<sha1>.<ext>` so an elided image without a
     * pre-existing linux path can still be referenced from the text
     * placeholder. The spillover dir is bind-mounted to
     * `/var/minis/attachments/spillover/` inside iSH (same mount as
     * `attachments/uploads/`). Returns the iSH-visible linux path on
     * success, or null if the write failed (in which case the placeholder
     * falls back to the no-path variant).
     *
     * Idempotent: if the same bytes already exist on disk under the
     * sha1 prefix, returns the existing path without re-writing.
     */
    fun ensureSpillover(
        sessionAttachmentsDir: java.io.File,
        data: ByteArray,
        mimeType: String,
    ): String? {
        if (data.isEmpty()) return null
        val ext = when (mimeType.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/heic", "image/heif" -> "heic"
            else -> "bin"
        }
        val sha = try {
            val md = java.security.MessageDigest.getInstance("SHA-1")
            md.update(data)
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            // SHA-1 is required by the platform; fallback is just an
            // identity hash — collisions are tolerable here.
            System.identityHashCode(data).toString(16)
        }
        val spilloverDir = java.io.File(sessionAttachmentsDir, "spillover")
        if (!spilloverDir.exists()) {
            try {
                spilloverDir.mkdirs()
            } catch (e: Exception) {
                AppLogger.warning(TAG, "ensureSpillover mkdirs failed: ${e.message}")
                return null
            }
        }
        val file = java.io.File(spilloverDir, "$sha.$ext")
        if (!file.exists()) {
            try {
                file.writeBytes(data)
            } catch (e: Exception) {
                AppLogger.warning(TAG, "ensureSpillover write failed: ${e.message}")
                return null
            }
        }
        // Mirrors uploads mount (see ChatViewModel.prepareUserAttachments).
        return "/var/minis/attachments/spillover/$sha.$ext"
    }
}
