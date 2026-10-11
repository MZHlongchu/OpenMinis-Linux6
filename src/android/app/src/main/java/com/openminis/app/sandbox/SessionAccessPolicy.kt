package com.openminis.app.sandbox

import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.offload.PromptDecision
import java.io.File

/**
 * Cross-session chat reads stay behind an explicit `session_read` grant.
 * Audit lines record ids and the action only — never message bodies.
 */
object SessionAccessPolicy {
    const val GRANT = "session_read"

    fun owner(sessionId: String?): String? =
        sessionId?.takeIf { it.isNotBlank() }?.let { SessionWorkspace.ownerSessionId(it) }

    fun isForeign(caller: String?, target: String): Boolean {
        val owner = owner(target) ?: target
        val self = caller?.let { owner(it) } ?: return true
        return owner != self
    }

    /**
     * Whether [caller] may read chat sessions it does not own.
     *
     * Deliberately routed through [OffloadPermissionManager] instead of a
     * bespoke flag: the agent CLI and the in-app tools then share one prompt,
     * one remembered answer per chat session, and the same "deny in this
     * session" that stops an agent from nagging after the user said no.
     * `session_read` must stay registered in that manager's toolRegistry — an
     * unknown tool falls through to BYPASS there, which would make this a
     * silent no-op.
     */
    suspend fun crossSessionDecision(caller: String): PromptDecision =
        OffloadPermissionManager.checkPermission(GRANT, "Read other chats", caller)

    /**
     * 兼容入口（语义不变）：只有明确的 ALLOWED 才算拿到授权。
     *
     * [T-android-prompt-identity] `checkPermission` 返回三值，「没人应答」
     * （[PromptDecision.UNANSWERED]：排队超时 / 队列满，提示压根没上屏）在这里也是 false，
     * 在屏工具退回只读自己那一会话，fail-closed。需要把"用户拒绝"和"没人应答"分开说的调用方
     * （审计 `prompt_unanswered`、给 agent 的下一步提示）用 [crossSessionDecision] ——
     * 判据与 `SessionsOffloadHandler` 的 `prompt_unanswered` 先例同源。
     */
    suspend fun isCrossSessionGranted(caller: String): Boolean =
        crossSessionDecision(caller).isAllowed
}

object SessionAccessAudit {
    @Volatile
    var dir: File? = null

    /**
     * Append one cross-session read attempt.
     *
     * [T-android-offload-hang] A bare `allowed=false` cannot express why a
     * non-owner was turned away: the user answered "no", the tool is
     * NOT_ALLOWED (nobody was even asked), or the ASK_ONCE prompt expired
     * before anyone answered. Only the third case is knowable at the call
     * site, so it is the single opt-in flag ring-fenced here — it keeps the
     * old lines byte-identical for every other caller.
     *
     * [T-android-prompt-identity] 「没人应答」有两个来源：预算耗尽，或提示被
     * 提示队列丢弃（排队超时 / 队列已满）。两者都算这一例，都打这个标记。
     */
    fun record(
        caller: String?,
        action: String,
        target: String?,
        allowed: Boolean,
        viaGrant: Boolean,
        /** true only when nobody answered the interactive prompt. */
        promptUnanswered: Boolean = false,
    ) {
        val line = "caller=${caller ?: "-"} action=$action target=${target ?: "-"} " +
            "allowed=$allowed grant=$viaGrant" +
            (if (promptUnanswered) " prompt_unanswered=true" else "")
        runCatching { AppLogger.info("SessionAccess", line) }
        val root = dir ?: return
        runCatching {
            root.mkdirs()
            File(root, "session-access.log").appendText("${System.currentTimeMillis()} $line\n")
        }
    }
}
