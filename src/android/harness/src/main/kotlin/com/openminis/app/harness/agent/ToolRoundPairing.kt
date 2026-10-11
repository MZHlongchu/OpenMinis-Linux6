package com.openminis.app.harness.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [T-loop-context-cut] 工具轮有序配对修复（原 dropOrphanedToolParts 的纯
 * 半边，循环本体收官刀）。
 *
 * 按 wire 顺序校验工具轮：result 只有在跟在同 id 同名的未配对 use **之后**
 * 才有效——集合成员判定不安全（重复 id / 错名 / result 先于 use 都会以
 * 畸形请求打到 provider）。修复动作：
 * - 悬空 result（含 result 先于 use、错名、重复 result）→ 丢弃；
 * - 未应答 use → 在其 assistant 消息后补一条错误 result 占位（尾部等待
 *   执行的 use 豁免——那是合法的在飞轮）。
 *
 * 占位文案由宿主注入（stubNotes：进程中断 vs 用户停止 vs 不可重放说明，
 * 单一策略源在宿主的 DanglingToolCallPlanner）；无意见时用兜底文案。
 */
object ToolRoundPairing {

    /**
     * @param history 待修复的历史
     * @param stubNotes id → 占位文案（宿主策略源）
     * @param log 诊断日志回调（宿主接 AppLogger）
     * @param keyOf id → 配对身份。默认恒等（老行为），宿主必须传
     *   [com.openminis.app.provider.ToolPairing.key]。
     *
     *   [T-android-responses-tool-id-normalize] Responses 路径把 call id 与
     *   function-call id 合成 `"<call_id>|<fc_id>"` 存进历史，wire 上按 `|` 前的
     *   那一半配对；同一轮既有合成 id 又有裸 call id（换模型、回退到 Chat
     *   Completions、从库里重载历史、中转改写 id）时，**按原始字符串比较会把一次
     *   配对成功的调用判成孤儿**：真 result 被删、再补一条"interrupted"占位。
     *   因此身份必须走宿主那一份共享实现，而不是在这里再写一遍 `|` 切分 ——
     *   harness 模块依赖不到 app 模块的 `provider.ToolPairing`，所以按已在此处
     *   注入 `stubNotes` / `log` 的同款方式把它做成参数。
     */
    fun dropOrphanedToolParts(
        history: List<LLMMessage>,
        stubNotes: (toolCallId: String) -> String? = { null },
        log: (String) -> Unit = {},
        keyOf: (toolCallId: String) -> String = { it },
    ): List<LLMMessage> {
        data class Use(
            val id: String,
            val name: String,
            val messageIndex: Int,
            val partIndex: Int,
        )
        val pending = ArrayList<Use>()
        val unmatchedUses = ArrayList<Use>()
        val droppedResults = HashSet<Pair<Int, Int>>()

        history.forEachIndexed { messageIndex, message ->
            message.contentParts.forEachIndexed { partIndex, part ->
                when (part) {
                    is AgentContentPart.ToolUse -> {
                        val use = Use(part.id, part.name, messageIndex, partIndex)
                        pending += use
                        unmatchedUses += use
                    }
                    is AgentContentPart.ToolResult -> {
                        // [T-android-responses-tool-id-normalize] 按共享身份配对，
                        // 不按原始字符串 —— 合成 id（`call_a|fc_a`）与裸 call id
                        // （`call_a`）是同一次调用的两种写法。
                        val matchIndex = pending.indexOfFirst { keyOf(it.id) == keyOf(part.id) && it.name == part.name }
                        if (matchIndex >= 0) {
                            val use = pending.removeAt(matchIndex)
                            unmatchedUses.remove(use)
                        } else {
                            droppedResults += messageIndex to partIndex
                        }
                    }
                    else -> Unit
                }
            }
        }

        // 尾部 assistant 可能正在等执行——只有**恰好是最后一条消息**上的
        // use 豁免；更早的未应答 use 补错误 result。
        // [T-android-responses-tool-id-normalize] 豁免集同样按共享身份建键，
        // 否则尾部那次调用会因为 id 拼写不同（合成 vs 裸）被当成未应答。
        val tailUses = if (history.lastOrNull()?.role == LLMMessage.Role.ASSISTANT) {
            history.last().contentParts.mapIndexedNotNull { partIndex, part ->
                (part as? AgentContentPart.ToolUse)?.let { keyOf(it.id) to it.name to partIndex }
            }.toSet()
        } else emptySet()
        val repairUses = unmatchedUses.filterNot { use ->
            use.messageIndex == history.lastIndex &&
                (keyOf(use.id) to use.name to use.partIndex) in tailUses
        }
        if (droppedResults.isEmpty() && repairUses.isEmpty()) return history

        log("[CompactDiag] ordered tool pairing repair: droppedResults=${droppedResults.size} " +
            "unansweredUses=${repairUses.size} historyCount=${history.size}")
        val repairByMessage = repairUses.groupBy { it.messageIndex }
        val cleaned = ArrayList<LLMMessage>(history.size + repairUses.size)
        history.forEachIndexed { index, message ->
            val kept = message.contentParts.mapIndexedNotNull { partIndex, part ->
                if (part is AgentContentPart.ToolResult && (index to partIndex) in droppedResults) null else part
            }
            if (kept.isNotEmpty() || message.contentParts.isEmpty()) {
                cleaned += if (kept.size == message.contentParts.size) message else message.copy(contentParts = kept)
            }
            val repairs = repairByMessage[index].orEmpty()
            if (repairs.isNotEmpty()) {
                cleaned += LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = "",
                    contentParts = repairs.map {
                        AgentContentPart.ToolResult(
                            id = it.id,
                            name = it.name,
                            content = stubNotes(it.id) ?: "Tool execution was interrupted by an unexpected error.",
                            isError = true,
                        )
                    },
                )
            }
        }
        return cleaned
    }
}
