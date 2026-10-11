package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.offload.OffloadCliSyntax
import org.json.JSONObject

/**
 * [T-ui-action] GUI-agent hands: a single typed entry point for the system
 * UI actions the accessibility CLI exposes, so the loop
 * `ui_read → decide → ui_action → ui_read (verify)` stays schema-validated
 * instead of the model hand-rolling `android-a11y-cli` flags in shell.
 *
 * [T-d11-a11y-cli-syntax] Every action is rendered by [OffloadCliSyntax]
 * builders, i.e. against the subcommand table the guest shim really
 * dispatches. The previous hand-rolled strings (bare `tap <x> <y>`,
 * `long-press`, `type`, `swipe`, `scroll <dir>`, `back`, and a shizuku
 * `launch <pkg>` group that does not exist) are all "unknown subcommand" on a
 * real device — only `open_url` worked. Validation is fail-closed: a missing
 * or non-integer coordinate is an INVALID_ARGUMENTS error, never a command
 * with a hole in it.
 *
 * Requires the AccessibilityService permission (same as [UiReadTool]).
 */
object UiActionTool {
    const val NAME = "ui_action"

    /** Same predicate as `McpToolDispatcher.validateCoord`: integers in [0, MAX_COORD]. */
    private const val MAX_COORD = 20_000

    /** 包名口径的唯一来源（MCP 侧 `ui_action open_app` 用同一份）。 */
    private val PACKAGE_NAME = OffloadCliSyntax.PACKAGE_NAME

    private const val COORD_HINT =
        "Coordinates are screen pixels; take them from a node's `center` in the last ui_read."

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Perform one UI action on the current foreground screen via the " +
            "accessibility service: tap coordinates, long-press, type text, swipe, scroll at a " +
            "point, press back, open an app by package, or launch an intent URL. " +
            "Use with ui_read in a loop: read the screen to find a target, act, then read again " +
            "to verify. Coordinates come from a node's `center` in the last ui_read; scroll needs " +
            "a point inside the scrollable area (the CLI has no direction-only scroll). " +
            // [T-d11-a11y-cli-guest-cost] 动作同样跑在 guest 里（CLI 只是 rootfs 里的 offload
            // shim）：沙箱没起来时这次调用会把它拉起来。写进描述，别让模型以为这是零成本调用。
            "Runs inside the Linux sandbox guest (android-a11y-cli / android-shizuku-cli shims); " +
            "if the sandbox is not running yet this call boots it. Requires Accessibility permission.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user, e.g. 'Tap the send button'."),
            "action" to AgentToolParam("string", "One of: tap, type, swipe, scroll, back, open_app, open_url, long_press."),
            "x" to AgentToolParam("integer", "X coordinate in screen pixels (required for tap, long_press, swipe start; for scroll, a point inside the scrollable area)."),
            "y" to AgentToolParam("integer", "Y coordinate in screen pixels (required for tap, long_press, swipe start; for scroll, a point inside the scrollable area)."),
            "x2" to AgentToolParam("integer", "Swipe end X (required for swipe)."),
            "y2" to AgentToolParam("integer", "Swipe end Y (required for swipe)."),
            "text" to AgentToolParam("string", "Text to type (required for type)."),
            "direction" to AgentToolParam("string", "up, down, left or right (required for scroll; the direction content moves, so 'down' reveals lower content)."),
            "package_name" to AgentToolParam("string", "Android package to launch (for open_app, e.g. com.android.settings)."),
            "url" to AgentToolParam("string", "Intent URL (for open_url, e.g. https://…, tel:, geo:, market:)."),
            "window_token" to AgentToolParam(
                "string",
                "Optional window token from the last ui_read / ui dump. When present, the coordinate gesture is " +
                    "refused if the foreground screen changed since that snapshot — coordinates are validated against " +
                    "window consistency only, so this stays usable on video / live streams where node ids churn.",
            ),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf(
            "tool_title", "action", "x", "y", "x2", "y2", "text",
            "direction", "package_name", "url", "window_token",
        ),
    )

    /** 计划结果：要么一条可直接执行的 CLI 命令，要么一个 fail-closed 的参数错误。 */
    internal sealed interface Plan {
        data class Run(val command: String) : Plan
        data class Reject(val message: String, val hint: String) : Plan
    }

    /**
     * action + 参数 → CLI 命令（纯函数，无 Android 依赖，可直接单测）。
     *
     * [T-ui-action-argguard] 这些值会被**直接拼进 guest shell 命令**：坐标是整数、
     * direction 走白名单、包名走正则、其余字符串过 [OffloadCliSyntax.shellQuote]。
     * 缺参数一律 Reject —— 旧写法用空串占位，`tap xy  200` 之类会在 CLI 侧错位。
     */
    internal fun plan(action: String, obj: JSONObject): Plan {
        val window = obj.optString("window_token").takeIf { it.isNotBlank() }

        fun coordsOrReject(vararg keys: String): Plan.Reject? {
            for (k in keys) {
                val problem = validateCoord(obj.opt(k), k).second ?: continue
                return Plan.Reject("$problem (action '$action')", COORD_HINT)
            }
            return null
        }

        fun ints(vararg keys: String): List<Int> = keys.map { validateCoord(obj.opt(it), it).first!! }

        return when (action) {
            "tap" -> {
                coordsOrReject("x", "y")?.let { return it }
                val (x, y) = ints("x", "y")
                Plan.Run(OffloadCliSyntax.tapXY(x, y, window))
            }
            "long_press" -> {
                coordsOrReject("x", "y")?.let { return it }
                val (x, y) = ints("x", "y")
                Plan.Run(OffloadCliSyntax.longPressXY(x, y, window))
            }
            "type" -> {
                val text = obj.optString("text")
                if (text.isEmpty()) {
                    return Plan.Reject("'text' is required for type", "Pass the string to enter into the focused field.")
                }
                Plan.Run(OffloadCliSyntax.inputText(text))
            }
            "swipe" -> {
                coordsOrReject("x", "y", "x2", "y2")?.let { return it }
                val (x, y, x2, y2) = ints("x", "y", "x2", "y2")
                Plan.Run(OffloadCliSyntax.swipeXY(x, y, x2, y2, window))
            }
            "scroll" -> {
                // direction 不转义就能拼命令（`down; …` 就是一条新命令），先白名单。
                val direction = obj.optString("direction", "down").lowercase()
                if (direction !in OffloadCliSyntax.SCROLL_DIRECTIONS) {
                    return Plan.Reject(
                        "invalid direction '$direction' (支持: up/down/left/right)",
                        "Choose one of: up, down, left, right.",
                    )
                }
                // CLI 的 scroll 三选一（node | xy | to-text）都必须带目标，没有
                // 「只给方向」的形式，所以坐标是必需项。
                coordsOrReject("x", "y")?.let { return it }
                val (x, y) = ints("x", "y")
                Plan.Run(OffloadCliSyntax.scrollXY(x, y, direction, window))
            }
            "back" -> Plan.Run(OffloadCliSyntax.pressBack())
            "open_app" -> {
                val pkg = obj.optString("package_name").trim()
                if (!PACKAGE_NAME.matches(pkg)) {
                    return Plan.Reject(
                        "'package_name' is not a valid Android package: '$pkg'",
                        "e.g. com.android.settings (list packages with `pm list packages` in shell_execute).",
                    )
                }
                Plan.Run(OffloadCliSyntax.launchPackage(pkg))
            }
            "open_url" -> {
                val url = obj.optString("url").trim()
                if (url.isEmpty()) {
                    return Plan.Reject("'url' is required for open_url", "e.g. https://…, tel:…, market:…")
                }
                Plan.Run(OffloadCliSyntax.openUrl(url))
            }
            else -> Plan.Reject(
                "unknown action '$action' (支持: tap/type/swipe/scroll/back/open_app/open_url/long_press)",
                "Choose one of: tap, type, swipe, scroll, back, open_app, open_url, long_press.",
            )
        }
    }

    /**
     * Same predicate as `McpToolDispatcher.validateCoord` — true integers in
     * [0, MAX_COORD]; anything else is a reason string, never a silent
     * substitution.
     */
    private fun validateCoord(raw: Any?, name: String): Pair<Int?, String?> {
        if (raw == null || raw == JSONObject.NULL) return null to "missing '$name' coordinate"
        val d: Double = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull() ?: return null to "'$name' is not a number: '$raw'"
            else -> return null to "'$name' has unexpected type: ${raw::class.simpleName}"
        }
        if (d != d.toLong().toDouble()) return null to "'$name' is not an integer: $d"
        val i = d.toLong()
        if (i < 0) return null to "'$name' is negative: $i"
        if (i > MAX_COORD) return null to "'$name' exceeds maximum $MAX_COORD: $i"
        return i.toInt() to null
    }

    suspend fun execute(argsJson: String, sessionId: String): ToolExecutionResult {
        val obj = runCatching { JSONObject(argsJson) }.getOrElse {
            return ToolExecutionResult("Error: invalid args: ${it.message}", false)
        }
        val action = obj.optString("action").lowercase()
        val toolTitle = obj.optString("tool_title", "ui_action")

        val cmd = when (val planned = plan(action, obj)) {
            is Plan.Reject -> return ToolExecutionResult(
                "Error: ${planned.message}",
                false,
                errorCode = ToolErrorCode.INVALID_ARGUMENTS,
                recoveryHint = planned.hint,
                toolTitle = toolTitle,
            )
            is Plan.Run -> planned.command
        }

        // [T-a11y-cli-guest] 执行域是 guest，不是宿主：stock Android 的 App 进程
        // 没有 /bin/bash，而 android-a11y-cli（以及这里的 android-shizuku-cli /
        // android-open）只是 guest rootfs 里的 offload shim。原先的
        // ProcessBuilder("/bin/bash", …) 在真机上永远失败；在恰好有 /bin/bash 的
        // 定制 ROM 上，else 分支只 echo 一行就退出 0，而 `|| true` 又把 CLI 的
        // ERROR/permission_denied 洗成退出 0 —— 两种情况都让这一格"假成功"。
        // 现在：命令进 guest 持久 shell（GuestScriptRunner），`|| true` 去掉，
        // 外壳由 [OffloadCliSyntax.guard] 统一生成 —— 缺桩分支显式 exit 127，
        // 并且**整段在子 shell 里**（顶层 exit 会打死会话持久 shell，DONE 帧
        // 永不打印，工具只看到 exit -1）。存在性预检按**实际要跑的那个 CLI**
        // 判定（open_app 用的是 shizuku 桩，open_url 用的是 android-open），
        // 否则报错信息会指向一个没被用到的二进制。
        val tool = OffloadCliSyntax.toolName(cmd)
        val script = OffloadCliSyntax.guard(cmd)

        val result = runCatching {
            com.openminis.app.sandbox.GuestScriptRunner.run(sessionId, script)
        }.getOrElse {
            return ToolExecutionResult(
                "Error: ${it.message}",
                false,
                errorCode = ToolErrorCode.EXECUTION_FAILED,
                recoveryHint = "The guest shell could not run $tool. " +
                    "Check that the sandbox is booted (Settings → Sandbox) and that the " +
                    "Accessibility permission is granted.",
                toolTitle = toolTitle,
            )
        }
        val trimmed = result.output.trim().ifBlank { "(无输出)" }
        // [T-d11-a11y-cli-exit-codes] 退出码契约：0 = 成功，2 = 语法错，
        // 77 = SERVICE_NOT_RUNNING（无障碍未启用/被 force-stop），1 = handler 错误
        // （错误码在 JSON 的 error.code：WINDOW_CHANGED / GESTURE_FAILED /
        // PERMISSION_DENIED / …），124 = handler_timeout，127 = 桩缺失（本脚本分支）。
        // 先看退出码：屏幕上恰好印着 "WINDOW_CHANGED" 这类字面量时，一次成功的动作
        // 不该被判成过期快照。
        val code = when {
            result.exitCode == 0 -> null
            trimmed.contains("WINDOW_CHANGED") -> ToolErrorCode.STALE_OBSERVATION
            result.exitCode == 77 ||
                trimmed.contains("SERVICE_NOT_RUNNING") ||
                trimmed.contains("PERMISSION_DENIED") -> ToolErrorCode.PERMISSION_DENIED
            result.exitCode == 124 -> ToolErrorCode.TIMEOUT
            else -> ToolErrorCode.EXECUTION_FAILED
        }
        return ToolExecutionResult(
            trimmed,
            result.exitCode == 0,
            errorCode = code,
            recoveryHint = when (code) {
                ToolErrorCode.STALE_OBSERVATION ->
                    "The screen changed since the snapshot. Call ui_read again, then retry with the fresh window_token."
                ToolErrorCode.PERMISSION_DENIED ->
                    "Enable Minis Ultra under Settings → Accessibility (or Settings → Permissions → Integrations for the agent permission)."
                ToolErrorCode.TIMEOUT ->
                    "The accessibility handler did not answer in time; call ui_read and retry."
                ToolErrorCode.EXECUTION_FAILED ->
                    // [T-a11y-cli-binary-name] 报实际跑的那个二进制：open_app 走
                    // android-shizuku-cli、open_url 走 android-open，写死 a11y 名字
                    // 会把排查引到一条根本没被执行的命令上。
                    "$tool exited ${result.exitCode}; see the error.code in the output."
                else -> null
            },
            toolTitle = toolTitle,
        )
    }
}
