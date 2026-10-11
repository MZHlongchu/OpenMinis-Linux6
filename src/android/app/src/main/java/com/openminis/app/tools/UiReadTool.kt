package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.offload.OffloadCliSyntax
import org.json.JSONObject

/**
 * [T-ui-read] GUI-agent eyes: dump the accessibility tree of the foreground
 * screen as a bounded JSON snapshot. The agent then reasons about the UI
 * (what is on screen, where to tap/type) and acts through `ui_action` —
 * closing the see → decide → act → verify loop that Muse/Operit ship as
 * UiAgentRunner, but reusing our existing accessibility CLI instead of a
 * VirtualDisplay stack.
 *
 * [T-d11-a11y-cli-syntax] The command is `android-a11y-cli ui dump --compact`
 * — the real subcommand ([OffloadCliSyntax.A11Y_SUBCOMMANDS]). The previous
 * `read-screen` does not exist in the shim, so this tool returned exit 2 on
 * every real device. Command text now comes from [OffloadCliSyntax], and
 * `A11yToolArgvAlignmentTest` pins it.
 *
 * Requires the AccessibilityService permission; the CLI returns a clear
 * message + exit 77 when it is not granted.
 */
object UiReadTool {
    const val NAME = "ui_read"
    const val MAX_CHARS = 12_000

    /** guest 侧真实二进制名；失败文案与桩预检都取它，不写死字符串。 */
    private const val TOOL = OffloadCliSyntax.A11Y_TOOL

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read a bounded snapshot of the current foreground screen via the " +
            "device accessibility tree: compact JSON with observation_id, window_token, " +
            "node count and per-node nodeId/text/contentDesc/clickable/center. " +
            "Use with ui_action in a loop: read → decide → tap/type → read again to verify. " +
            "Coordinates come from a node's center; pass window_token to ui_action so a " +
            "coordinate gesture on a screen that changed since this snapshot is refused. " +
            // [T-d11-a11y-cli-guest-cost] 读屏走 guest（宿主进程没有 /bin/bash，CLI 只是
            // rootfs 里的 offload shim），代价写在描述里而不是藏起来：沙箱没起来时这次
            // 调用会把它拉起来。批量化读取比轮询便宜。
            "Runs inside the Linux sandbox guest (android-a11y-cli shim): if the sandbox is not " +
            "running yet this call boots it (proot + persistent shell + foreground service); " +
            "once booted, calls reuse that shell, so batch reads instead of polling. " +
            "Requires Accessibility permission; returns a hint when missing.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user, e.g. 'Read current screen'."),
            "max_chars" to AgentToolParam("integer", "Optional cap on returned characters (default 6000, max 12000)."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "max_chars"),
    )

    /**
     * [T-d11-a11y-cli-guest] 执行域是 guest：宿主 App 进程没有 /bin/bash，而
     * android-a11y-cli 只是 guest rootfs 里的 offload shim。命令也不再走
     * `… | head -c $cap`：管道会把退出码换成 head 的 0，配上一句
     * `success = true` 就成了"读屏失败却报成功"。截断改在 Kotlin 侧做，
     * 退出码保持 CLI 的真实值（0 成功 / 77 无障碍服务未运行 / 2 语法错 / 127 桩缺失）。
     *
     * 外壳由 [OffloadCliSyntax.guard] 生成：**整段在子 shell 里**，所以缺桩分支的
     * `exit 127` 只结束这条命令，不会打死会话持久 shell（顶层 exit 会让
     * `__minis_rc` 与 DONE 帧都执行不到，工具只看到 exit -1）。
     *
     * 纯字符串构造，便于 `A11yToolArgvAlignmentTest` 直接校验命令串。
     */
    internal fun buildScript(): String = OffloadCliSyntax.guard(OffloadCliSyntax.readScreen())

    suspend fun execute(argsJson: String, sessionId: String): ToolExecutionResult {
        val obj = runCatching { JSONObject(argsJson) }.getOrElse {
            return ToolExecutionResult("Error: invalid args: ${it.message}", false)
        }
        val cap = (obj.optInt("max_chars", 6_000)).coerceIn(1_000, MAX_CHARS)
        val toolTitle = obj.optString("tool_title", "ui_read")
        val result = runCatching {
            com.openminis.app.sandbox.GuestScriptRunner.run(sessionId, buildScript())
        }.getOrElse {
            return ToolExecutionResult(
                "Error: ${it.message}",
                false,
                errorCode = ToolErrorCode.EXECUTION_FAILED,
                recoveryHint = "The guest shell could not run $TOOL. " +
                    "Check that the sandbox is booted (Settings → Sandbox).",
                toolTitle = toolTitle,
            )
        }
        val trimmed = result.output.trim().take(cap)
        val body = trimmed.ifBlank { "（屏幕为空或读屏失败——确认已授予无障碍权限：Settings → 权限）" }
        // [T-d11-a11y-cli-exit-codes] 退出码契约（AccessibilityOffloadHandler +
        // PersistentShell 的 offload 包装）：0 = 成功（err() 永远 >= 1，所以 0 就是 ok），
        // 2 = 语法错，1 = handler 错误（错误码在 JSON error.code 里），
        // 77 = SERVICE_NOT_RUNNING（无障碍未启用/被 force-stop/受限设置），
        // 124 = handler_timeout，127 = 本脚本的桩缺失分支。
        // 先看退出码再看文本：屏幕上恰好显示 "SERVICE_NOT_RUNNING" 这种字面量时，
        // 一次成功的 dump 不该被判成权限错误。
        val code = when {
            result.exitCode == 0 -> null
            result.exitCode == 77 ||
                trimmed.contains("SERVICE_NOT_RUNNING") ||
                trimmed.contains("PERMISSION_DENIED") -> ToolErrorCode.PERMISSION_DENIED
            result.exitCode == 124 -> ToolErrorCode.TIMEOUT
            else -> ToolErrorCode.EXECUTION_FAILED
        }
        return ToolExecutionResult(
            body,
            result.exitCode == 0,
            errorCode = code,
            recoveryHint = when (code) {
                ToolErrorCode.PERMISSION_DENIED ->
                    "Enable Minis Ultra under Settings → Accessibility, then call ui_read again."
                ToolErrorCode.TIMEOUT ->
                    "The accessibility handler did not answer in time; retry ui_read."
                ToolErrorCode.EXECUTION_FAILED ->
                    "$TOOL returned exit ${result.exitCode}. " +
                        "Check the sandbox is booted (Settings → Sandbox); exit 2 means the CLI rejected the command."
                else -> null
            },
            toolTitle = toolTitle,
        )
    }
}
