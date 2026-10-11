package com.openminis.app.sandbox.offload

import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.offload.PromptDecision
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Permission-gated entry point for `android-*` offload CLIs. Sits at the
 * NativeOffloadHandler entry point so every shell invocation of a CLI —
 * regardless of how the LLM constructed the command — passes through
 * the same BYPASS / ASK_ONCE / NOT_ALLOWED policy stored in
 * [OffloadPermissionManager].
 *
 * Why a helper:
 *   * `NativeOffloadHandler.handle` is synchronous (the offload IPC
 *     server invokes it from a worker thread and writes the result
 *     into the guest's tmp file before replying). [checkPermission]
 *     is suspend (ASK_ONCE awaits user input on a coroutine), so we
 *     bridge with `runBlocking`. The block is fine on this thread —
 *     the IPC reply already gates further guest progress on its
 *     return.
 *   * Every handler shares the same prompt-and-cache flow, so the
 *     boilerplate lives here rather than 5+ copy-pastes.
 *
 * Session scope: T340 — prefer the chat session id forwarded by the
 * agent shell via the `MINIS_CHAT_SESSION_ID` env var (surfaced as
 * [NativeOffloadRequest.sessionId]). Falls back to
 * `OFFLOAD_GLOBAL_SESSION_ID` when the offload runs outside a chat
 * (e.g. interactive terminal). This keeps "Allow in this session"
 * grants from leaking across chat sessions.
 */
internal object OffloadGate {
    /**
     * @return true if the CLI may proceed; false → handler should
     * short-circuit with a PERMISSION_DENIED envelope pointing the
     * user to Settings → Permissions.
     *
     * [T-android-prompt-identity] 这是个二值出口：`false` 同时覆盖 DENIED 与
     * "没人应答"。需要按 [PromptDecision] 分开报错误码的 gate 用 [enforce]。
     */
    fun allow(toolName: String, displayName: String, sessionId: String? = null): Boolean =
        decide(toolName, displayName, sessionId)?.isAllowed == true

    /** Overload that pulls the chat session id straight off the request. */
    fun allow(toolName: String, displayName: String, request: NativeOffloadRequest): Boolean =
        allow(toolName, displayName, request.sessionId)

    /**
     * [T-android-offload-hang] Decision behind [allow]/[enforce]:
     *
     *   [PromptDecision.ALLOWED]    → allowed, proceed
     *   [PromptDecision.DENIED]     → denied by policy, caller should point at Settings
     *   [PromptDecision.UNANSWERED] → 提示被队列丢弃（排队超时 / 队列满）
     *   null                        → nobody answered the ASK_ONCE prompt within
     *                                 [OffloadPermissionManager.INTERACTIVE_BUDGET_MS]
     *
     * [T-android-prompt-identity] The last two are one case for the caller —
     * nobody answered — and `checkPermission` 旧的 Boolean 返回把它们并进了
     * DENIED，于是调用方被指去改设置，而不是去请用户批准。没人应答也是当年
     * 永久挂起的那条路径：`checkPermission` 挂在 UI 提示上，而这座桥会在 IPC
     * worker 线程上 `runBlocking` 到 120s 对话框超时 —— 没有 UI 能应答它，
     * 每次重试都往同一把锁后面再停一个线程。
     */
    private fun decide(toolName: String, displayName: String, sessionId: String?): PromptDecision? =
        runBlocking {
            withTimeoutOrNull(OffloadPermissionManager.INTERACTIVE_BUDGET_MS) {
                OffloadPermissionManager.checkPermission(
                    toolName,
                    displayName,
                    sessionId ?: OffloadPermissionManager.OFFLOAD_GLOBAL_SESSION_ID,
                )
            }
        }

    /**
     * Convenience: gate the call and return null when allowed, or a
     * pre-formatted envelope when not. Lets a handler write
     * `enforce(...)?.let { return it }` at the top of `handle`.
     */
    fun enforce(
        toolName: String,
        displayName: String,
        args: OffloadArgs,
        request: NativeOffloadRequest? = null,
    ): NativeOffloadResult? = when (decide(toolName, displayName, request?.sessionId)) {
        PromptDecision.ALLOWED -> null
        PromptDecision.DENIED -> {
            val body = JSONObject()
                .put("error", "permission_denied")
                .put("message",
                    "Agent is not allowed to use $displayName. Open Settings → Permissions to change.")
                .toString()
            NativeOffloadResult(126, OffloadOutput.formatBody(body, args) + "\n")
        }
        // Distinct code + message: this is not a policy denial, so telling the
        // agent to "change Settings" would send it down the wrong path.
        // [T-android-prompt-identity] UNANSWERED（提示被队列丢弃：排队超时 / 队列满）
        // 与 null（预算耗尽）同属"没人应答"，与 DENIED 分开报。
        null, PromptDecision.UNANSWERED -> {
            val body = JSONObject()
                .put("error", "interaction_required")
                .put("tool", displayName)
                .put("budget_ms", OffloadPermissionManager.INTERACTIVE_BUDGET_MS)
                .put("message",
                    "$displayName needs the user to approve a prompt in the app, and nobody " +
                        "answered within ${OffloadPermissionManager.INTERACTIVE_BUDGET_MS / 1000}s. " +
                        "No work was done. Ask the user to approve it, or open Settings → " +
                        "Permissions, then retry.")
                .toString()
            NativeOffloadResult(124, OffloadOutput.formatBody(body, args) + "\n")
        }
    }
}
