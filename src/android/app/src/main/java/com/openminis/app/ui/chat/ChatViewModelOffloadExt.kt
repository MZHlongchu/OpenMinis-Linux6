package com.openminis.app.ui.chat

import com.openminis.app.data.ContextOffload
import com.openminis.app.data.ContextPolicy
import com.openminis.app.data.ContextSizeMeter
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.model.AgentContentPart
import org.json.JSONObject

/**
 * Walk [agentHistory], identify large tool outputs in the older
 * (non-protected) message range, and offload the highest-token ones to
 * disk until we're back under [ContextPolicy.offloadTarget]. Mirrors iOS
 * `offloadContextIfNeeded(model:lastContextTokens:force:)` (line 7481).
 *
 * Protection rules (parity with iOS line 7535):
 *   - Last 4 messages are never offloaded — the model needs them
 *     verbatim to plan the current turn coherently.
 *   - Already-offloaded parts (prefix [ContextOffload.OFFLOADED_PREFIX])
 *     are skipped — second pass would rewrite the stub uselessly.
 *
 * Eligibility (parity with iOS lines 7556-7596):
 *   - `ToolResult` with content > 500 chars OR image data > 1 KB
 *   - `ToolUse` for `file_write` / `file_edit` whose `content` arg > 500 chars
 *   - bare `ImageData` part > 1 KB
 *
 * Candidates are sorted by token count descending and offloaded greedily
 * until current usage drops below [policy.offloadTarget] (or all
 * candidates are exhausted). When [force] is true, all eligible
 * candidates are offloaded regardless of remaining headroom — used by
 * post-compact code paths to slim down the kept-tail aggressively.
 */
/**
 * [T-android-offload-scan-range] The raw [ChatViewModel.agentHistory] index the
 * offload scan may start at: everything before the compact-v2 anchor never
 * reaches the model, so scanning it can only produce stubs that nothing sends.
 * Port of iOS 5a9c5bfcd / 上游 1.14 `ChatViewModel.kt:10055-10062`（合并时整块
 * 丢了；本移植把 offload 拆进本文件，语义逐条一致）。
 *
 * 0 whenever there is no compaction: no summary, no cached marker, a v1
 * marker, an unresolved anchor, or a blank anchor id.
 */
internal fun ChatViewModel.offloadScanStartIndex(): Int {
    if (_compactSummary.value.isNullOrBlank()) return 0
    val marker = _cachedLatestMarker ?: return 0
    if (marker.version < 2) return 0
    val anchorId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() } ?: return 0
    val anchorIdx = agentHistory.indexOfLast { it.dbMessageId == anchorId }
    return if (anchorIdx < 0) 0 else anchorIdx + 1
}

internal fun ChatViewModel.offloadContextIfNeeded(
    contextWindow: Int,
    lastContextTokens: Int,
    force: Boolean = false,
) {
    val sid = activeSessionId
    val policy = ContextPolicy.forContextWindow(contextWindow, effectiveCompactPercent())

    if (!force && policy.offloadThreshold == 0) {
        // Small-window tier: offload disabled — UI surfaces "exhausted"
        // when the user crosses the threshold. Nothing to do here.
        return
    }

    val effectiveTokens =
        if (lastContextTokens > 0) lastContextTokens else estimateContextTokens()

    if (!force && effectiveTokens < policy.offloadThreshold) {
        // Below threshold — no work needed. Caller logs at debug level
        // via dynamicMaxTokens; we stay silent to keep logs readable.
        return
    }

    val targetTokens = if (force) 0 else policy.offloadTarget
    val beforeTokens = effectiveTokens
    var currentTokens = effectiveTokens
    val pct = (effectiveTokens.toLong() * 100 / contextWindow.coerceAtLeast(1)).toInt()
    val remaining = contextWindow - beforeTokens

    AppLogger.info(ChatViewModel.TAG, "━━━ Context Offload Triggered ━━━")
    AppLogger.info(ChatViewModel.TAG, "  Window: $contextWindow tokens")
    AppLogger.info(ChatViewModel.TAG, "  Before: $beforeTokens tokens ($pct% of window, ~$remaining remaining)")
    if (force) {
        AppLogger.info(ChatViewModel.TAG, "  Mode: FORCE — offloading all eligible candidates")
    } else {
        AppLogger.info(ChatViewModel.TAG, "  Threshold: ${policy.offloadThreshold} → Target: $targetTokens")
        AppLogger.info(ChatViewModel.TAG, "  Need to free: ~${beforeTokens - targetTokens} tokens")
    }
    AppLogger.info(ChatViewModel.TAG, "  Agent history: ${agentHistory.size} messages")

    val protectedCount = minOf(4, agentHistory.size)
    val candidateUpper = agentHistory.size - protectedCount
    // [T-android-offload-scan-range] 上游 1.14 ChatViewModel.kt:10184-10192，
    // 合并时丢了：扫描从压缩锚点**之后**开始。锚点之前的内容根本不会发给模型
    // （见 offloadScanStartIndex），扫它只会把没人会发的部分换成会被发出去的 stub。
    val scanStart = minOf(offloadScanStartIndex(), candidateUpper)
    AppLogger.info(
        ChatViewModel.TAG,
        "  Scanning messages $scanStart..<$candidateUpper (last $protectedCount protected, " +
            "$scanStart before the compaction anchor skipped)",
    )
    // [T-android-offload-scan-range] 上游 1.14 ChatViewModel.kt:10193-10200，合并时
    // 丢了：排序与「释放够了没」用 ContextSizeMeter 的估计、并按
    // measureOutboundContextTokens 同一条校准比缩放到 lastContextTokens 的尺度上。
    // 老代码用 BPETokenizer.countTokens（没装词表时就是 chars/3）：CJK 被低估约 3 倍，
    // 于是英文上过早认为「释放够了」，中文上又过度离载。
    val calibrationRatio = contextCalibrationRatio()

    // [T-android-offload-warmup-scan] A compact-v2 marker still sends up to
    // three user turns of warm-up BEFORE its anchor, pruned only of tool
    // results over 1000 chars and their calls. Those parts never reach the
    // model, so offloading one would free nothing while replacing a part the
    // send-time prune drops with a stub that IS sent — a net loss. The plan's
    // second half is exactly that pruned id set; `warmUpScanIndices` is the
    // pure mapping behind it (upstream `offloadWarmUpScanPlan`, iOS 155501fcd).
    //
    // [T-android-offload-scan-range] 因为扫描现在从 scanStart 起步，锚点之前的
    // warm-up 下标必须显式加回扫描集合（上游同形）。
    val warmUp = offloadWarmUpScanPlan()
    val warmUpIndices = warmUp.first.filter { it < scanStart }
    val answeredFrom = minOf(warmUpIndices.firstOrNull() ?: scanStart, scanStart)
    if (warmUpIndices.isNotEmpty()) {
        AppLogger.info(
            ChatViewModel.TAG,
            "  Warm-up before the anchor: scanning ${warmUpIndices.size} sent message(s) " +
                "(${warmUp.second.size} pruned tool id(s) skipped)",
        )
    }

    val candidates = mutableListOf<ChatViewModel.OffloadCandidate>()
    var skippedAlreadyOffloaded = 0
    var skippedTooSmall = 0
    var skippedUnanswered = 0
    // [T-offload-toolinput-guards GH#374] 上游 1.14 ChatViewModel.kt:10210-10228，
    // 合并时丢了：带 tool_result 的 tool_use id 集合——能证明已经跑完的调用。
    // 只有它们的 arguments 允许被剪掉。收集自 answeredFrom（warm-up 的第一个下标
    // 或 scanStart）：区间内的调用由一条落在它之后的结果应答，从更早开始收集并无
    // 必要；从区间起点开始不会漏掉任何一条。
    val answeredToolUseIds: Set<String> = buildSet {
        for (m in agentHistory.subList(answeredFrom, agentHistory.size)) {
            for (pt in m.contentParts) {
                if (pt is AgentContentPart.ToolResult) add(pt.id)
            }
        }
    }

    for (msgIdx in warmUpIndices + (scanStart until candidateUpper)) {
        val msg = agentHistory[msgIdx]
        val inWarmUp = msgIdx < scanStart
        for ((partIdx, part) in msg.contentParts.withIndex()) {
            if (inWarmUp) {
                val prunedId = when (part) {
                    is AgentContentPart.ToolUse -> part.id
                    is AgentContentPart.ToolResult -> part.id
                    else -> null
                }
                if (prunedId != null && prunedId in warmUp.second) continue
            }
            when (part) {
                is AgentContentPart.ToolResult -> {
                    // [T-offload-stub-system-reminder GH#374] 上游 1.14
                    // ChatViewModel.kt:10273：靠共享谓词跳过，而不是裸的
                    // startsWith(OFFLOADED_PREFIX)——stub 格式改成
                    // <system-reminder> 之后，裸前缀检查会静默失配，已经离过的
                    // 结果被再离一次。
                    if (ContextOffload.isOffloadPlaceholder(part.content)) {
                        skippedAlreadyOffloaded++
                        continue
                    }
                    val hasLargeContent = part.content.length > 500
                    val hasLargeImage = (part.imageData?.size ?: 0) > 1024
                    if (!hasLargeContent && !hasLargeImage) {
                        skippedTooSmall++
                        continue
                    }
                    val tokens = ContextSizeMeter.estimateTokens(part)
                    val bytes = part.content.toByteArray(Charsets.UTF_8).size +
                        (part.imageData?.size ?: 0)
                    candidates.add(ChatViewModel.OffloadCandidate(msgIdx, partIdx, tokens, bytes, part.id, part.name))
                }
                is AgentContentPart.ToolUse -> {
                    if (part.name != "file_write" && part.name != "file_edit") continue
                    val content = part.input.optString("content", "")

                    // [T-offload-toolinput-guards GH#374] 上游 1.14
                    // ChatViewModel.kt:10278-10296，合并时整段丢了。ToolResult
                    // 分支一直有的两条守卫，ToolUse 分支也必须要有：
                    //
                    // 1. 已经剪过。来源标记是权威的（只有 offloader 会写它）；
                    //    文本判定覆盖标记出现之前持久化的历史以及旧对端同步过来的
                    //    stub。再剪一次会写出第二个文件、用新 notice 盖掉第一个，
                    //    真内容的路径就丢了。
                    if (part.isOffloadedArgument ||
                        ContextOffload.isOffloadPlaceholder(content)
                    ) {
                        skippedAlreadyOffloaded++
                        continue
                    }

                    // 2. 还没有结果。剪一个没有 tool_result 的调用，等于改写一件
                    //    还在飞的事情。两个 offload 触发点目前都在窗口之外，所以
                    //    今天咬不到；写在这里是为了让这条不变量留在扫描器本地，
                    //    而不是靠调用点的顺序——重构一次就会变。
                    if (part.id !in answeredToolUseIds) {
                        skippedUnanswered++
                        continue
                    }

                    if (content.length <= 500) continue
                    val tokens = ContextSizeMeter.estimateTokens(part)
                    val bytes = content.toByteArray(Charsets.UTF_8).size
                    candidates.add(ChatViewModel.OffloadCandidate(msgIdx, partIdx, tokens, bytes, part.id, part.name))
                }
                is AgentContentPart.ImageData -> {
                    if (part.data.size <= 1024) {
                        skippedTooSmall++
                        continue
                    }
                    val tokens = ContextSizeMeter.estimateTokens(part)
                    // Synthesize a tool id since bare images don't carry one.
                    val synthId = "img${msgIdx}_$partIdx"
                    candidates.add(ChatViewModel.OffloadCandidate(msgIdx, partIdx, tokens, part.data.size, synthId, "image"))
                }
                is AgentContentPart.Text -> Unit
            }
        }
    }

    candidates.sortByDescending { it.tokens }
    val totalCandidateTokens = candidates.sumOf { it.tokens }
    AppLogger.info(ChatViewModel.TAG, "  Candidates: ${candidates.size} parts (~$totalCandidateTokens tokens total)")
    AppLogger.info(
        ChatViewModel.TAG,
        "  Skipped: $skippedAlreadyOffloaded already offloaded, $skippedTooSmall too small, " +
            "$skippedUnanswered unanswered tool calls",
    )

    var offloadedCount = 0
    var freedTokens = 0

    for (candidate in candidates) {
        if (currentTokens <= targetTokens) break
        // [T-android-offload-scan-range] 上游 1.14 ChatViewModel.kt:10330-10334：与
        // 被扣减的 usage 同一个尺度（stub 文案里的 "~N tokens" 也用这个数）。
        val candidateTokens = ContextSizeMeter.calibrated(candidate.tokens, calibrationRatio)

        val msg = agentHistory[candidate.msgIdx]
        val parts = msg.contentParts.toMutableList()
        val part = parts[candidate.partIdx]
        var linuxPath = ""

        val newPart: AgentContentPart? = when (part) {
            is AgentContentPart.ToolResult -> {
                // [T-android-offload-readback] (GH#343) 上游 1.14
                // ChatViewModel.kt:10340-10362，合并时丢了：这条结果如果是 agent
                // 把我们已经离过的文件读回来，字节本来就在磁盘上、路径写在 file_read
                // 自己的头部里。按那个路径重新打 stub，而不是再写一份逐字节重复的
                // 新文件。
                val readbackPath = if (part.name == "file_read") {
                    ContextOffload.offloadReadbackPath(part.content)
                } else {
                    null
                }
                if (readbackPath != null) {
                    linuxPath = readbackPath
                    AppLogger.info(
                        ChatViewModel.TAG,
                        "  offload readback: reusing existing $readbackPath (no duplicate file written)",
                    )
                } else if (part.content.length > 500) {
                    linuxPath = ContextOffload.offloadContent(
                        context, sid, part.content,
                        toolId = part.id, toolName = part.name,
                    )
                }
                val imgPath = part.imageData?.let { data ->
                    if (data.size > 1024) {
                        ContextOffload.offloadImage(
                            context, sid, data,
                            toolId = part.id,
                            mimeType = part.imageMimeType ?: "image/png",
                        )
                    } else ""
                } ?: ""
                if (linuxPath.isEmpty()) linuxPath = imgPath
                val stub = ContextOffload.stub(candidateTokens, candidate.bytes, linuxPath)
                part.copy(content = stub, imageData = null, imageMimeType = null)
            }
            is AgentContentPart.ToolUse -> {
                val content = part.input.optString("content", "")
                linuxPath = ContextOffload.offloadContent(
                    context, sid, content,
                    toolId = part.id, toolName = part.name,
                )
                val newInput = org.json.JSONObject(part.input.toString())
                // [T-offload-stub-system-reminder GH#374] 上游 1.14
                // ChatViewModel.kt:10388-10398，合并时丢了：被剪的 ARGUMENT 拿到
                // 的是 <system-reminder> notice，不是内容形状的旧文案——这个值会
                // 作为模型自己先前的调用回到它面前，旧措辞等于邀请它把占位符抄进
                // 下一次写。来源标记 [isOffloadedArgument] 同时落上，扫描器据此
                // 不会二次剪枝。
                newInput.put(
                    "content",
                    ContextOffload.prunedArgumentNotice(candidateTokens, candidate.bytes, linuxPath),
                )
                part.copy(input = newInput, isOffloadedArgument = true)
            }
            is AgentContentPart.ImageData -> {
                linuxPath = ContextOffload.offloadImage(
                    context, sid, part.data,
                    toolId = candidate.toolId,
                    mimeType = part.mimeType,
                )
                // Bare ImageData has no toolUseId pairing — replace with a
                // text part carrying the stub. Mirrors iOS line 7653.
                AgentContentPart.Text(
                    ContextOffload.stub(candidateTokens, candidate.bytes, linuxPath),
                )
            }
            is AgentContentPart.Text -> null
        }

        if (newPart == null) continue
        parts[candidate.partIdx] = newPart
        agentHistory[candidate.msgIdx] = msg.copy(contentParts = parts)

        currentTokens -= candidateTokens
        freedTokens += candidateTokens
        offloadedCount++
        val afterPct = (currentTokens.toLong() * 100 / contextWindow.coerceAtLeast(1)).toInt()
        AppLogger.info(
            ChatViewModel.TAG,
            "  ✂ Offloaded #$offloadedCount: [${candidate.toolName}] id:${candidate.toolId.take(8)} ~$candidateTokens tokens (${candidate.bytes} bytes) → $linuxPath [now $currentTokens ($afterPct%)]",
        )
    }

    if (offloadedCount > 0) {
        val afterPct = (currentTokens.toLong() * 100 / contextWindow.coerceAtLeast(1)).toInt()
        AppLogger.info(ChatViewModel.TAG, "━━━ Context Offload Complete ━━━")
        AppLogger.info(ChatViewModel.TAG, "  Parts offloaded: $offloadedCount")
        AppLogger.info(ChatViewModel.TAG, "  Tokens freed: ~$freedTokens")
        AppLogger.info(ChatViewModel.TAG, "  Before: $beforeTokens/$contextWindow ($pct%)")
        AppLogger.info(ChatViewModel.TAG, "  After:  $currentTokens/$contextWindow ($afterPct%)")
        AppLogger.info(ChatViewModel.TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }
}
