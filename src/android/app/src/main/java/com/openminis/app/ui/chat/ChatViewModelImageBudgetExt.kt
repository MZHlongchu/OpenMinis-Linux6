package com.openminis.app.ui.chat

import com.openminis.app.logging.AppLogger
import com.openminis.app.data.BPETokenizer
import com.openminis.app.data.PayloadSizeAudit
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.provider.ImageBudget

/**
 * Apply the request-level image-byte budget to a fully-resolved
 * message list before handing it to a provider. Images that don't
 * fit under [ImageBudget.MAX_REQUEST_BYTES] (oldest first) are
 * replaced in-place with a text placeholder that, when the original
 * bytes were offloaded to disk, points the model back to the linux
 * path so it can re-fetch via `read_image` if needed. Images that
 * never had a linuxPath are spilled to
 * `attachments/spillover/<sha1>.<ext>` lazily so the placeholder
 * still carries an addressable reference.
 *
 * Returns the budgeted message list. When nothing was elided this
 * is the same instance as [messages].
 *
 * Emits a one-shot [requestBudgetEvent] for the UI Snackbar so the
 * user knows older images were compacted into placeholders.
 */
internal fun ChatViewModel.applyRequestImageBudget(messages: List<LLMMessage>): List<LLMMessage> {
    // Collect every image in chronological order so the planner can
    // walk in reverse and protect the most recent images.
    data class ImageRef(val msgIdx: Int, val partIdx: Int, val image: ImageBudget.BudgetImage)
    val images = mutableListOf<ImageRef>()
    messages.forEachIndexed { mi, msg ->
        msg.contentParts.forEachIndexed { pi, part ->
            when (part) {
                is AgentContentPart.ImageData -> {
                    images.add(
                        ImageRef(
                            mi, pi,
                            ImageBudget.BudgetImage(part.data, part.linuxPath, part.mimeType),
                        )
                    )
                }
                is AgentContentPart.ToolResult -> {
                    val img = part.imageData
                    if (img != null) {
                        images.add(
                            ImageRef(
                                mi, pi,
                                ImageBudget.BudgetImage(
                                    img,
                                    part.imageLinuxPath,
                                    part.imageMimeType ?: "image/jpeg",
                                ),
                            )
                        )
                    }
                }
                else -> Unit
            }
        }
    }
    if (images.isEmpty()) return messages

    val plan = ImageBudget.planRequestBudget(images.map { it.image })
    if (!plan.mutated) return messages

    // For dropped images without a linuxPath, lazily spill to disk so
    // the placeholder still gives the model an addressable reference.
    val attachmentsRoot = activeSessionId?.let { sid ->
        com.openminis.app.sandbox.SessionWorkspace.hostDir(context.filesDir, sid, "attachments")
    }
    val resolvedPaths = HashMap<ImageBudget.ImagePartId, String?>()
    for (ref in images) {
        val id = ImageBudget.ImagePartId.of(ref.image.data)
        if (id !in plan.droppedIds) continue
        val existing = ref.image.linuxPath
        if (existing != null) {
            resolvedPaths[id] = existing
        } else if (attachmentsRoot != null) {
            resolvedPaths[id] = ImageBudget.ensureSpillover(
                attachmentsRoot, ref.image.data, ref.image.mimeType,
            )
        } else {
            resolvedPaths[id] = null
        }
    }

    // Build a new message list with dropped image parts replaced by
    // text placeholders. Same-message multiple drops collapse cleanly
    // because we never touch parts whose ids weren't in droppedIds.
    val byMsg = images.groupBy { it.msgIdx }
    val mutated = messages.toMutableList()
    for ((mi, refs) in byMsg) {
        val msg = mutated[mi]
        val newParts = msg.contentParts.toMutableList()
        for (ref in refs) {
            val id = ImageBudget.ImagePartId.of(ref.image.data)
            if (id !in plan.droppedIds) continue
            val path = resolvedPaths[id]
            val placeholder = AgentContentPart.Text(ImageBudget.elidedImagePlaceholder(path))
            val originalPart = newParts[ref.partIdx]
            newParts[ref.partIdx] = when (originalPart) {
                is AgentContentPart.ImageData -> placeholder
                is AgentContentPart.ToolResult -> originalPart.copy(
                    // Strip the bytes but keep the structural ToolResult
                    // role; append the elision marker into content so
                    // the model sees it next to the rest of the tool
                    // output. linux path remains in the part for any
                    // subsequent diagnostic round-trip.
                    imageData = null,
                    imageMimeType = null,
                    content = originalPart.content +
                        (if (originalPart.content.isEmpty()) "" else "\n") +
                        ImageBudget.elidedImagePlaceholder(path),
                )
                else -> originalPart
            }
        }
        mutated[mi] = msg.copy(contentParts = newParts)
    }

    _requestBudgetEvent.tryEmit(plan)
    AppLogger.info(
        ChatViewModel.TAG,
        "applyRequestImageBudget: dropped=${plan.droppedCount}/${plan.totalCount} keptBytes=${plan.keptBytes}B elidedBytes=${plan.elidedBytes}B",
    )
    return mutated
}

/** 一个部件在出站审计里的四项读数（上游 ChatViewModel 的私有 `Quad`）。 */
private data class AuditedPayloadPart(
    val bytes: Int,
    val estimatedTokens: Int,
    val kind: String,
    val id: String?,
)

/**
 * [T-android-payload-size-audit] (GH#352) 出站负载的**字节侧**交叉校验。
 *
 * 两个预算计量单位不同：[ImageBudget] 数**字节**（25 MB 请求上限），
 * [BPETokenizer.countImageTokens] 数**token**（视觉网格启发式，一张大图几千）。
 * 图片按结构化 `image_url` 走时两者都对；同一张图若在下游被当成**文本**计量，
 * 真实代价是几十万 token，而 app 里每一道闸门看到的数都小三个数量级 —— 没人
 * 会发现。这里按比值（[PayloadSizeAudit.SUSPICIOUS_BYTES_PER_TOKEN]）在两者
 * 明显不可能同时成立时落一条 warning，供下一次排查直接从日志读出结论。
 *
 * 上游 1.14 的实现：`git show upstream-1.14:.../ui/chat/ChatViewModel.kt` 的
 * 4205-4248，调用点在同一个文件的 11303 ——
 * `auditOutgoingPayload(applyRequestImageBudget(outboundHistory))`，注释写明
 * "outermost so it sees exactly what goes on the wire"。本移植把 ChatViewModel
 * 拆成多片、出站链路又多了接缝（LlmProviderStreamClient），所以函数落在
 * [applyRequestImageBudget] 旁边（同一个"出站负载"话题），调用点仍然贴着真正
 * 发请求的那一次调用，嵌套顺序与上游一致：**审计在外、预算在内**。
 *
 * 纯诊断：只读入参、只记日志，返回原列表（上游同样 `return messages`），
 * 不参与预算、不改任何部件。
 */
internal fun ChatViewModel.auditOutgoingPayload(messages: List<LLMMessage>): List<LLMMessage> {
    val window = effectiveContextWindowTokens() ?: 0
    for ((msgIdx, msg) in messages.withIndex()) {
        for (part in msg.contentParts) {
            val audited = when (part) {
                is AgentContentPart.ImageData ->
                    // 真正离开设备的是 base64，所以按 base64 量，不按原始字节数。
                    // 公式只有一份实现：ImageBudget.estimatedBase64Length。
                    AuditedPayloadPart(
                        ImageBudget.estimatedBase64Length(part.data.size).toInt(),
                        BPETokenizer.countImageTokens(part.data),
                        "image",
                        null,
                    )
                is AgentContentPart.ToolResult -> {
                    val imgBytes = part.imageData?.size ?: 0
                    AuditedPayloadPart(
                        part.content.length + ImageBudget.estimatedBase64Length(imgBytes).toInt(),
                        BPETokenizer.countTokens(part.content) +
                            (part.imageData?.let { BPETokenizer.countImageTokens(it) } ?: 0),
                        "tool_result",
                        part.id,
                    )
                }
                is AgentContentPart.Text ->
                    AuditedPayloadPart(part.text.length, BPETokenizer.countTokens(part.text), "text", null)
                is AgentContentPart.ToolUse -> {
                    val raw = part.input.toString()
                    AuditedPayloadPart(raw.length, BPETokenizer.countTokens(raw), "tool_use", part.id)
                }
            }
            val finding = PayloadSizeAudit.audit(audited.bytes, audited.estimatedTokens, window)
            if (!finding.suspicious && !finding.dangerous) continue
            AppLogger.warning(
                ChatViewModel.TAG,
                "[PayloadAudit] GH#352 oversized part msgIdx=$msgIdx role=${msg.role} " +
                    "kind=${audited.kind} id=${audited.id ?: "-"} bytes=${finding.bytes} " +
                    "estTokens=${finding.estimatedTokens} bytesPerToken=${finding.bytesPerToken} " +
                    "window=$window suspicious=${finding.suspicious} dangerous=${finding.dangerous}" +
                    if (finding.dangerous) {
                        " — this part alone could exhaust the window if measured as text; " +
                            "if the provider now 400s on context length the self-heal path " +
                            "([T-android-context-overflow-selfheal]) will offload it."
                    } else "",
            )
        }
    }
    return messages
}
