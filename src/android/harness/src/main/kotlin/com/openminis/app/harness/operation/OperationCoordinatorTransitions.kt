package com.openminis.app.harness.operation

import com.openminis.app.harness.HarnessMessage
import com.openminis.app.harness.ToolCall
import com.openminis.app.harness.ToolResult
import com.openminis.app.harness.events.HarnessEvent
import com.openminis.app.harness.model.HarnessUsageEntity

/**
 * [T-b18-op-lookup-session-domain] 运行链路的 provider / 工具转移入口。
 *
 * 从 [OperationCoordinator] 拆出（架构门 maxFileLines 棘轮：该文件已在基线上，只许缩减；
 * 与 LaneRunGate、本文件同属「为行数帽整体搬出」的既有做法）。转移的事务语义、
 * 锁序、事件投递都仍在 [OperationCoordinator] 里（`settle` / `transition` /
 * `requireOperation` / `emitFor` 为同模块可见），这里只是入口壳。
 *
 * sessionId 可选：带上它，查找只读该会话自己的台账文件（单文件）；不带则退化成
 * 跨会话扫描（改动前的语义）。宿主在每个转移挂点都有 activeSessionId，而
 * `findOperation` 原先每次都要「扫全目录 + 解码每个会话文件」——每个工具轮 8 次。
 */
suspend fun OperationCoordinator.providerIntent(
    operationId: String,
    effectId: String,
    round: Int,
    attempt: Int,
    maxAttempts: Int,
    sessionId: String? = null,
) {
    transition(
        operationId,
        OperationStatus.RUNNING,
        OperationSnapshot(
            phase = OperationPhase.PROVIDER_INTENT.id,
            round = round,
            effectKind = "provider",
            effectId = effectId,
            reservedEntryId = effectId,
            attempt = attempt,
            maxAttempts = maxAttempts,
        ),
        ReplayPolicy.NEVER,
        sessionId,
    )
    emitFor(operationId, sessionId) { sessionId, timestamp, _ ->
        HarnessEvent.ProviderRoundStarted(sessionId, timestamp, operationId, round, attempt)
    }
}

suspend fun OperationCoordinator.providerSettled(
    operationId: String,
    message: HarnessMessage?,
    usage: HarnessUsageEntity? = null,
    round: Int,
    sessionId: String? = null,
) {
    settle(
        operationId = operationId,
        message = message,
        usage = usage,
        snapshot = OperationSnapshot(phase = OperationPhase.PROVIDER_SETTLED.id, round = round),
        sessionId = sessionId,
    )
    emitFor(operationId, sessionId) { sessionId, timestamp, _ ->
        HarnessEvent.ProviderRoundSettled(
            sessionId, timestamp, operationId, round,
            entryId = message?.id,
            inputTokens = usage?.inputTokens ?: 0,
            outputTokens = usage?.outputTokens ?: 0,
        )
    }
}

suspend fun OperationCoordinator.toolIntent(
    operationId: String,
    message: HarnessMessage,
    payloadJson: String,
    replay: ReplayPolicy,
    round: Int,
    sessionId: String? = null,
) {
    val snapshot = OperationSnapshot(
        phase = OperationPhase.TOOL_INTENT.id,
        round = round,
        effectKind = "tool",
        effectId = message.id,
        effectPayloadJson = payloadJson,
        replayPolicy = replay.id,
    )
    settle(operationId, message, null, snapshot, replay, sessionId)
    emitFor(operationId, sessionId) { sessionId, timestamp, _ ->
        val toolCall = message as? ToolCall
        HarnessEvent.ToolCallStarted(
            sessionId, timestamp, operationId,
            toolCallId = message.id,
            toolName = toolCall?.rawToolName ?: "tool",
        )
    }
}

suspend fun OperationCoordinator.toolSettled(
    operationId: String,
    message: HarnessMessage,
    round: Int,
    toolName: String? = null,
    sessionId: String? = null,
) {
    settle(
        operationId = operationId,
        message = message,
        usage = null,
        snapshot = OperationSnapshot(phase = OperationPhase.TOOL_SETTLED.id, round = round),
        sessionId = sessionId,
    )
    emitFor(operationId, sessionId) { sessionId, timestamp, _ ->
        val result = message as? ToolResult
        HarnessEvent.ToolCallSettled(
            sessionId, timestamp, operationId,
            toolCallId = result?.toolCallId ?: message.id,
            toolName = toolName ?: "tool",
            success = result?.success ?: true,
            durationMs = result?.durationMs,
        )
    }
}
