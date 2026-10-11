package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger

/**
 * Validate tool rounds in wire order. A result is valid only when it follows
 * an unmatched use with the same id and name; set membership alone is unsafe
 * because duplicate ids, wrong names, and result-before-use all reach providers
 * as malformed requests.
 */
internal fun dropOrphanedToolParts(history: List<LLMMessage>): List<LLMMessage> {
    // [T-loop-context-cut] 有序配对修复的纯半边进 harness
    // （ToolRoundPairing）；占位文案仍走宿主策略源（stubNotesFor：
    // 进程中断 vs 用户停止 vs 不可重放说明），日志接 AppLogger。
    //
    // [T-android-responses-tool-id-normalize] 配对身份也由宿主注入：harness
    // 模块依赖不到 app 模块的 provider.ToolPairing，而"哪两个 id 是同一次调用"
    // 只允许有一份实现（Responses 的合成 id `call|fc` 与裸 call id 必须配对，
    // 见 ToolPairing 的 KDoc）。上游 1.14 在本函数体内直接写
    // `ToolPairing.key(it.id)`（upstream ChatViewModel.kt:4934/4956/4983），
    // 本移植把函数体搬进 harness，于是这一份身份跟着变成参数。
    val stubNotes = stubNotesFor(history)
    return com.openminis.app.harness.agent.ToolRoundPairing.dropOrphanedToolParts(
        history = history,
        stubNotes = { id -> stubNotes[id] },
        log = { message -> AppLogger.warning(ChatViewModel.TAG, message) },
        keyOf = { id -> com.openminis.app.provider.ToolPairing.key(id) },
    )
}

/**
 * [T-context-window-policy] 工具 schema 预留：用我方自己的估算器对全量 schema
 * 实测一次并缓存（taixu 的校准口径——预留低于实测会从折叠线余量里挖走缺口）。
 */
private val SCHEMA_RESERVE_TOKENS: Int by lazy {
    com.openminis.app.tools.ToolSchemaResolver.schemas.values
        .sumOf { com.openminis.app.harness.context.ContextWindowPolicy.estimateTokens(it.toString()) }
}

/** 历史可用预算 = 模型窗口 - 输出预留 - schema 预留；窗口未知返回 0（不治理）。 */
internal fun ChatViewModel.contextWindowBudgetTokens(): Int {
    val window = currentProvider?.model?.contextWindow ?: return 0
    return window -
        com.openminis.app.harness.context.ContextWindowPolicy.RESERVED_OUTPUT_TOKENS -
        SCHEMA_RESERVE_TOKENS
}

/**
 * 巨型用户消息的投影级截断（[ContextWindowPolicy.truncateOversizedUserTurns]）：
 * 只改发给 provider 的正文，落库 transcript 与 UI 不变。预算非正时原样返回。
 *
 * [T-b1-user-turn-truncation-parts] 只改 `content` 等于没改：provider 在
 * `contentParts` 非空时**只**序列化 parts（AnthropicProvider.buildMessages /
 * OpenAIRequestBodies），而用户轮的正文恒有一个 Text 部件 —— 巨型粘贴因此
 * 原样进请求体，截断永不生效。截断结果与图片剥离都必须落到 parts 上。
 *
 * [T-context-window-part-accounting] 反向的一半同样要命：只在 parts 里、不在
 * `content` 里的文本（压缩摘要 / 附件 XML / 图片占位）必须计入估算，见下面
 * [unprojectedPartTokens]；哪些部件算「正文」由 [bodyPartCount] 的**结构性**判据
 * 决定（[T-context-window-body-part-identity]），两处共用同一条规则。
 */
internal fun applyContextWindowPolicy(history: List<LLMMessage>, budgetTokens: Int): List<LLMMessage> {
    if (budgetTokens <= 0 || history.isEmpty()) return history
        val userIndexes = history.indices.filter { history[it].role == LLMMessage.Role.USER }
    if (userIndexes.isEmpty()) return history
    // [T-context-window-part-accounting] 摘要等「只存在于 parts、不在 content 里」的
    // 文本必须计入估算。压缩摘要由 CompactHistoryProjector.withInjectedSummary 追加
    // 成**一个 parts 尾部 Text 部件**，`content` 里已不含它（单载体）；而下面投影给
    // harness 的 ProjectedUserTurn.text 取的是 `content` —— 不补这一段，压缩后每次
    // 请求都少算一个数千 token 的摘要：总量判定偏小、截断不触发，紧预算下请求体带着
    // 摘要顶破窗口（provider 400）。
    //
    // 补法是把这部分 token 从预算里扣掉，而不是塞进某一轮的 text：预算扣除与
    // 「总 token ≤ 预算」在数学上等价（少了 E，就把上限降 E），且摘要仍然**不可被
    // 截断**——它属于压缩后的历史骨架，被 fitUserText 切一刀才是真丢历史。
    val uncountedNonBodyTokens = userIndexes.sumOf { index ->
        unprojectedPartTokens(history[index].contentParts, history[index].content)
    }
    val turns = userIndexes.map { index ->
        com.openminis.app.harness.context.ContextWindowPolicy.ProjectedUserTurn(
            id = index.toString(),
            text = history[index].content,
            imageCount = history[index].imageParts.size,
        )
    }
    // 上限至少留 1：否则 harness 的 `limitTokens <= 0` 早退会把治理整个关掉
    // （fail-open）。预算真被摘要吃光时，harness 会把可截断的正文压到
    // MIN_KEPT_USER_TURN_TOKENS，正是「按真实总量算」应有的结果。
    val effectiveBudget = if (uncountedNonBodyTokens > 0) {
        (budgetTokens - uncountedNonBodyTokens).coerceAtLeast(1)
    } else {
        budgetTokens
    }
    val truncated = com.openminis.app.harness.context.ContextWindowPolicy.truncateOversizedUserTurns(turns, effectiveBudget)
    if (truncated == turns) return history
    return history.toMutableList().also { out ->
        userIndexes.forEachIndexed { slot, index ->
            val original = out[index]
            val turn = truncated[slot]
            val imagesDropped = turn.imageCount == 0 && original.imageParts.isNotEmpty()
            if (turn.text == original.content && !imagesDropped) return@forEachIndexed
            out[index] = original.copy(
                content = turn.text,
                imageParts = if (turn.imageCount == 0) emptyList() else original.imageParts,
                // 正文段的判据用**原始** content（截断后的 turn.text 已与 parts 里的
                // 正文段逐字不同，拿它去匹配会把正文段判成注入段、于是重建时旧正文
                // 留在 parts 里，截断后的短正文与未截断的原正文同时进请求体）。
                contentParts = projectUserTurnParts(
                    parts = original.contentParts,
                    content = original.content,
                    body = turn.text,
                    dropImages = imagesDropped,
                ),
            )
        }
    }
}

/**
 * [T-b1-user-turn-truncation-parts] 用户轮的正文 = **被折进 `content` 的前导 Text
 * 部件**（[bodyPartCount]）；图片占位标记（`[attached image: …]`）与
 * `<user-attached-files>` XML 排在其后，必须原样保留（与
 * `MessageEntity.toLLMMessage` / `buildUserPartsJson` 同一形状）。
 * 截断后的正文折回一个 Text 部件，放在原正文位置上 —— 旧正文段随之被
 * `parts.drop(bodyCount)` 丢掉，请求体里只剩截断后的那一份
 * （[T-context-window-body-part-identity]）。
 *
 * [T-compact-summary-single-carrier] 摘要部件（`<context-summary>` 开头，由
 * [com.openminis.app.harness.agent.CompactHistoryProjector] 追加在 parts 末尾）
 * 必须同样排除在「正文前导段」之外：否则它会被 bodyCount 一起吞掉，正文截断后
 * 重建出来的 parts 里摘要整段消失——压缩过的历史在模型侧静默丢失。它也不重复注入：
 * 摘要只存在于这**一个**部件里（正文 `content` 不再带摘要前缀）。
 *
 * [T-context-window-body-part-identity] 正文段认哪几个部件由 [content] 决定，
 * 不是由 [body]（截断结果）：截断后的 [body] 已与 parts 里的正文段逐字不同，用它
 * 当判据会把正文段认成注入段，于是旧正文留在 parts 里、与新正文同时发出。
 */
private fun projectUserTurnParts(
    parts: List<AgentContentPart>,
    content: String,
    body: String,
    dropImages: Boolean,
): List<AgentContentPart> {
    val bodyCount = bodyPartCount(parts, content)
    val out = ArrayList<AgentContentPart>(parts.size)
    if (body.isNotEmpty()) out.add(AgentContentPart.Text(body))
    parts.drop(bodyCount).forEach { part ->
        if (dropImages && part is AgentContentPart.ImageData) return@forEach
        out.add(part)
    }
    return out
}

/**
 * [T-context-window-part-accounting] 用户轮的**正文前导段**：`parts` 开头那些
 * **确实被折进 `content`** 的 Text 部件，返回它们的条数。
 *
 * [T-context-window-body-part-identity] 判据必须是结构性的（游标 = `content`，
 * 逐个前导 Text 部件在游标处做前缀匹配），**不能按文本内容猜**：旧实现用
 * `text.contains("<user-attached-files>")` 把「注入段」认出来，而 fresh send 的
 * `content == modelBody == parts[0]`（ChatViewModelSendExt）——用户自己输入 / 粘贴
 * 这个字面量时，正文部件被判成注入段，两个后果一起发生：
 *  - 预算被多扣一整份正文（实测真实 8001 token < 预算 8500 仍触发截断）；
 *  - 重建时正文段没被替换掉，截断后的短正文与未截断的原正文**同时**留在 parts 里，
 *    provider 看到的正文反而变长。
 * 前缀匹配对这两种形状都免疫：`parts[0].text == content` 恒成立。
 *
 * 真实形状（写入侧，逐个核对过）都满足「正文部件是 content 的连续分段、注入段排在
 * 其后」：
 *  - fresh send：`[Text(modelBody)]`，`content = modelBody`；
 *  - 附件 / 图片占位：`[Text(caption), Text("[attached image: …]"), ImageData…,
 *    Text(attachedFilesXml)]`，`content = caption`；
 *  - toLLMMessage 恢复：正文分段（含粘贴展开段，同时写进 `content`）在前，附件 XML
 *    在后；图片走 ImageData、不带占位文本；
 *  - 压缩注入：摘要追加在 parts **末尾**（CompactHistoryProjector.withInjectedSummary），
 *    `content` 不含它。
 */
private fun bodyPartCount(parts: List<AgentContentPart>, content: String): Int {
    var cursor = 0
    var count = 0
    for (part in parts) {
        if (part !is AgentContentPart.Text) break
        val text = part.text
        if (!content.startsWith(text, cursor)) break
        cursor += text.length
        count++
    }
    return count
}

/**
 * [T-context-window-part-accounting] parts 里那些「进请求体但不进 `content`」的 token：
 * 正文前导段之后的部分。三类注入段**都不进 `content`**，只按 `content` 估算就漏掉
 * 它们：
 *  - `<context-summary>`：摘要单载体（CompactHistoryProjector.withInjectedSummary）；
 *  - `[attached image: …]`：图片路径占位，只在线路上发（ChatViewModelSendExt），
 *    content 是干净的 caption；
 *  - `<user-attached-files>` XML：persist/restore 都刻意不折进 content
 *    （MessageEntity.toLLMMessage 的 T-android-retry-attachment-loss 分支）。
 *
 * 反过来，被粘贴展开的正文部件不在排除之列：它在恢复路径上**同时**写进 `content`
 * （toLLMMessage 的 pasted 分支），按位置排在正文段里，重复计它就会误截断。
 */
private fun unprojectedPartTokens(parts: List<AgentContentPart>, content: String): Int {
    val bodyCount = bodyPartCount(parts, content)
    return parts.drop(bodyCount).sumOf { part ->
        if (part is AgentContentPart.Text) {
            com.openminis.app.harness.context.ContextWindowPolicy.estimateTokens(part.text)
        } else {
            0
        }
    }
}
