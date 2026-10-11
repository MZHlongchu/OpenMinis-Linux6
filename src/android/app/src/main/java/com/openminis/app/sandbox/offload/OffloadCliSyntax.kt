package com.openminis.app.sandbox.offload

/**
 * [T-d11-a11y-cli-syntax] 「工具生成的 argv」与「guest CLI 真实子命令表」的唯一对齐点。
 *
 * 为什么需要这个对象：`/usr/local/bin/android-a11y-cli` 不是资产覆盖层里的脚本，
 * 也不是真二进制 —— 它是 `PRootKernel.installHandlerStubs()` 按
 * `NativeOffloadServer.registeredHandlers` 写出来的一个 `exit 0` 桩；proot 的
 * native_offload 扩展在 execve 处截获它，把 argv 交给 [AccessibilityOffloadHandler]。
 * 因此**唯一有效**的语法契约就是那个 handler 的 dispatch 表。
 *
 * 工具侧第一版凭印象拼了 `read-screen` / 裸 `tap <x> <y>` / `type` / `swipe` /
 * `scroll <dir>` / `back`，还调了一个并不存在的 shizuku `launch <pkg>` 组：这些在真机
 * 上一律落到 "unknown subcommand"（退出码 2），只是把「宿主里跑不了」换成了另一种恒
 * 失败。现在所有命令都由下面的 builder 生成，[check] 能对渲染结果做静态校验，
 * `A11yToolArgvAlignmentTest` 再用一份独立抄写的字面量表把它们钉住。
 *
 * 维护规则：[A11Y_SUBCOMMANDS] / [SHIZUKU_GROUPS] 逐条镜像
 * [AccessibilityOffloadHandler.handle] 与 [ShizukuOffloadHandler.handle] 的分支。
 * 在那边增删子命令时，同一次改动里改这里 —— `A11yToolArgvAlignmentTest` 直接**从
 * handler 源码里抽出** `when (sub)` / `when (group)` 与各组的位置参数分支来比对，
 * 不再是抄一份副本（副本只挡得住抄错，挡不住两边一起漂）。
 */
internal object OffloadCliSyntax {

    const val A11Y_TOOL = "android-a11y-cli"
    const val SHIZUKU_TOOL = "android-shizuku-cli"
    const val OPEN_TOOL = "android-open"

    /** 允许被 [guard] 包进 shell 的 CLI 白名单（工具名只从这里来）。 */
    val TOOLS: Set<String> = linkedSetOf(A11Y_TOOL, SHIZUKU_TOOL, OPEN_TOOL)

    /** `PRootKernel.installHandlerStubs()` 写桩的目录；工具用它做存在性预检。 */
    const val GUEST_BIN_DIR = "/usr/local/bin"

    /**
     * Android 包名。`android-shizuku-cli exec` 会把命令体交给 `sh -c`，包名在体内
     * 又经 [shellQuote] 包一层，所以这条正则不是防注入（那是 [launchPackage] 的
     * 职责），而是防止把**必然失败**的 argv 发出去：`monkey -p` 只接受真的包名。
     * agent 侧 `ui_action open_app` 与 MCP 侧 `ui_action` 共用这一份口径。
     */
    val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")

    /** `monkey -c <category>`：等价于点桌面图标，比 `am start -p <pkg>` 可靠。 */
    const val LAUNCH_CATEGORY = "android.intent.category.LAUNCHER"

    /** `android-a11y-cli <group> <subcommand>` —— 镜像 [AccessibilityOffloadHandler]。 */
    val A11Y_SUBCOMMANDS: Map<String, Set<String>> = linkedMapOf(
        "ui" to linkedSetOf("dump", "find", "info", "node", "screenshot"),
        "tap" to linkedSetOf("node", "xy", "text", "id"),
        "input" to linkedSetOf("text", "clear", "key"),
        "scroll" to linkedSetOf("node", "xy", "to-text"),
        "gesture" to linkedSetOf("swipe", "pinch", "path"),
        "wait" to linkedSetOf("appear", "disappear", "stable", "activity"),
        "event" to linkedSetOf("watch", "once"),
        "notify" to linkedSetOf("watch", "once"),
        "dialog" to linkedSetOf("detect", "watch", "once", "dismiss"),
        "extract" to linkedSetOf("text", "list", "table", "form"),
        "service" to linkedSetOf("status", "ping"),
    )

    /** `input key <KEY>` 的合法取值（AccessibilityService 能执行的全局动作）。 */
    val A11Y_INPUT_KEYS: Set<String> = linkedSetOf("BACK", "HOME", "RECENTS", "NOTIFICATIONS")

    /** `android-shizuku-cli <group>` 的顶层组（`exec` 是任意命令兜底）。 */
    val SHIZUKU_GROUPS: Set<String> = linkedSetOf(
        "package", "permission", "activity", "display", "settings", "user",
        "network", "input", "notification", "file", "device", "service", "exec",
    )

    /** `scroll xy --direction <d>` 的合法取值。 */
    val SCROLL_DIRECTIONS: Set<String> = linkedSetOf("up", "down", "left", "right")

    // ── builders：工具只准从这里拿命令串 ─────────────────────────────────

    /**
     * 读屏：`ui dump --compact`。compact 只留 text/contentDesc/clickable/center，
     * 正好是 ui_action 用来点按的字段，同样字符预算下能装下更多节点。
     */
    fun readScreen(): String = "$A11Y_TOOL ui dump --compact"

    fun tapXY(x: Int, y: Int, windowToken: String? = null): String =
        "$A11Y_TOOL tap xy $x $y${windowFlag(windowToken)}"

    /** 长按 = `tap xy` + `--long`（[AccessibilityOffloadHandler.tapXYRaw] 取 1000ms）。 */
    fun longPressXY(x: Int, y: Int, windowToken: String? = null): String =
        "$A11Y_TOOL tap xy $x $y --long${windowFlag(windowToken)}"

    /**
     * [T-d11-a11y-cli-dashdash] `input text <s>`。文本以 `-` 开头时先发 `--` 终止符，
     * 否则 [OffloadArgs] 会把它摘成 flag，`input text` 拿不到 positional[2] 而恒退 2
     * （`'--clear'` 还会被当成真的 clear 开关）。
     */
    fun inputText(text: String): String = "$A11Y_TOOL input text ${optionGuard(text)}${shellQuote(text)}"

    fun swipeXY(x1: Int, y1: Int, x2: Int, y2: Int, windowToken: String? = null): String =
        "$A11Y_TOOL gesture swipe $x1 $y1 $x2 $y2${windowFlag(windowToken)}"

    /**
     * 滚动：CLI 没有「只给方向」的形式（`scroll node|xy|to-text` 三选一都必须有
     * 目标），所以必须带一个滚动区间内的坐标点，否则模型只会拿到退出码 2。
     */
    fun scrollXY(x: Int, y: Int, direction: String, windowToken: String? = null): String {
        require(direction in SCROLL_DIRECTIONS) { "scroll direction must be one of $SCROLL_DIRECTIONS, got '$direction'" }
        return "$A11Y_TOOL scroll xy $x $y --direction $direction${windowFlag(windowToken)}"
    }

    fun pressBack(): String = "$A11Y_TOOL input key BACK"

    /**
     * 按包名启动应用。走 `android-shizuku-cli exec`（[ShizukuOffloadHandler.handleExec]
     * 把余下 argv 交给 `sh -c`），命令体是 monkey 的标准启动式。
     *
     * 不用 `activity start -p <pkg>`：那条路只设 package 限定、没有 action/component，
     * `am start` 在多数 ROM 上直接 "unable to resolve Intent" 退出 —— 又一个恒失败。
     *
     * 双层引用：包名在命令体内部再单引号一层，即使调用方漏了包名校验，payload 也
     * 不会因为包名里的 `;`/`$()` 在 `sh -c` 里被当成第二条命令。
     */
    fun launchPackage(packageName: String): String {
        val payload = "monkey -p ${shellQuote(packageName)} -c $LAUNCH_CATEGORY 1"
        return "$SHIZUKU_TOOL exec ${shellQuote(payload)}"
    }

    fun openUrl(url: String): String = "$OPEN_TOOL ${shellQuote(url)}"

    /** `--window <token>` 后缀；token 为空/缺失时不加，避免把 `--window` 当值吞掉。 */
    fun windowFlag(windowToken: String?): String =
        windowToken?.takeIf { it.isNotBlank() }?.let { " --window ${shellQuote(it)}" } ?: ""

    /** 单引号包住整个值，内嵌单引号变 `'\''`。任何插进命令串的值都必须过这里。 */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * [T-d11-a11y-cli-dashdash] 该值会不会被 [OffloadArgs] 摘成 flag（`-x`/`--xxx`，
     * 长度 > 1）。引用层帮不上忙：`'-5'` 过了 shell 之后 argv 仍然是 `-5`。
     */
    fun looksLikeOption(value: String): Boolean = value.length > 1 && value.startsWith("-")

    /** 需要时补 `--` 终止符（[OffloadArgs] 认它），让 [looksLikeOption] 的值落回位置参数。 */
    private fun optionGuard(value: String): String = if (looksLikeOption(value)) "-- " else ""

    /** 命令串的第一个 token = 实际会被执行的 guest CLI（失败文案与 [guard] 都取它）。 */
    fun toolName(command: String): String = command.substringBefore(' ')

    /**
     * [T-d11-a11y-cli-stub-guard] 桩缺失时的统一外壳，四个调用点
     * （agent 侧 `ui_read` / `ui_action` + MCP 的 `ui_read` / `ui_action`）共用，
     * 避免各自演化。
     *
     * 两个必须点：
     *
     * 1. **整段包在子 shell 里**。命令进的是会话长驻 bash 的**顶层**
     *    （`GuardianScript.persistentCommand` 明确 "The command itself is not inside
     *    parentheses"），顶层 `exit 127` 会直接打死这个持久 shell：后面
     *    `__minis_rc=$?` 与 `echo "__MINIS_DONE_…"` 都执行不到，宿主只能看到
     *    "[Shell not running]" / exit -1 —— 真正的原因（桩没装）反而丢了。
     *    `( … )` 之后 127 只是**这条命令**的 `$?`，shell 活着，DONE 帧照常打印。
     * 2. 缺桩必须显式退 127，而不是 echo 一行了事：只 echo 的话 `$?` 是 0，
     *    工具会把"根本没跑"报成成功。
     */
    fun guard(command: String): String {
        val tool = toolName(command)
        require(tool in TOOLS) { "guard() 只包装已知的 offload CLI，收到 '$tool'" }
        return buildString {
            append("( if [ -x $GUEST_BIN_DIR/$tool ]; then\n")
            append("  $command 2>&1\n")
            append("else\n")
            append("  echo \"$tool not available in the sandbox (guest offload shim missing)\"\n")
            append("  exit 127\n")
            append("fi )")
        }
    }

    // ── 校验 ─────────────────────────────────────────────────────────────

    /**
     * 把渲染好的命令拆成 guest 侧真正会看到的 argv。只实现我们自己生成的引用形式
     * （裸词 + 单引号段 + `'\''` + 反斜杠转义），够做静态校验，不是通用 shell 解析。
     * 未闭合的引号按「余下都是字面量」收尾。
     */
    fun tokens(command: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var started = false
        var inQuote = false
        var i = 0
        while (i < command.length) {
            val c = command[i]
            when {
                inQuote -> if (c == '\'') inQuote = false else sb.append(c)
                c == '\'' -> { inQuote = true; started = true }
                c == '\\' && i + 1 < command.length -> { sb.append(command[i + 1]); i++; started = true }
                c.isWhitespace() -> if (started) { out.add(sb.toString()); sb.clear(); started = false }
                else -> { sb.append(c); started = true }
            }
            i++
        }
        if (started) out.add(sb.toString())
        return out
    }

    /**
     * 静态校验一条命令在真机 shim 上是否可达：null = 会被 handler 接受，非 null =
     * 人类可读的问题描述（测试直接断言 null）。只覆盖 group/subcommand 与两处有
     * 枚举语义的取值，不做完整参数校验。
     */
    fun check(command: String): String? {
        val t = tokens(command)
        if (t.isEmpty()) return "empty command"
        return when (t[0]) {
            A11Y_TOOL -> checkA11y(t)
            SHIZUKU_TOOL -> {
                val group = t.getOrNull(1) ?: return "$SHIZUKU_TOOL: missing <group>"
                if (group !in SHIZUKU_GROUPS) {
                    "$SHIZUKU_TOOL: unknown group '$group' (known: ${SHIZUKU_GROUPS.joinToString("|")})"
                } else null
            }
            OPEN_TOOL -> if (t.size < 2 || t[1].isBlank()) "$OPEN_TOOL: missing <url>" else null
            else -> "not an offload CLI invocation: '${t[0]}'"
        }
    }

    private fun checkA11y(t: List<String>): String? {
        val group = t.getOrNull(1) ?: return "$A11Y_TOOL: missing <group>"
        val subs = A11Y_SUBCOMMANDS[group]
            ?: return "$A11Y_TOOL: unknown group '$group' (known: ${A11Y_SUBCOMMANDS.keys.joinToString("|")})"
        val sub = t.getOrNull(2) ?: return "$A11Y_TOOL $group: missing <subcommand>"
        if (sub !in subs) {
            return "$A11Y_TOOL $group: unknown subcommand '$sub' (known: ${subs.joinToString("|")})"
        }
        if (group == "input" && sub == "key") {
            val key = t.getOrNull(3)?.uppercase() ?: return "$A11Y_TOOL input key: missing <KEY>"
            if (key !in A11Y_INPUT_KEYS) {
                return "$A11Y_TOOL input key: unsupported key '$key' (known: ${A11Y_INPUT_KEYS.joinToString("|")})"
            }
        }
        // [T-d11-a11y-cli-dashdash] 手写的 `input text -5` 会被 OffloadArgs 摘成 flag
        // → handler 恒退 2；builder 会发 `input text -- '-5'`，这里挡住绕过 builder 的写法。
        if (group == "input" && sub == "text") {
            when (val v = t.getOrNull(3)) {
                null -> return "$A11Y_TOOL input text: missing <text>"
                "--" -> if (t.size < 5) return "$A11Y_TOOL input text: '--' without a value"
                else -> if (looksLikeOption(v)) {
                    return "$A11Y_TOOL input text: '$v' starts with '-' and would be parsed as a flag" +
                        " — use the '--' terminator (input text -- '$v')"
                }
            }
        }
        if (group == "scroll" && sub == "xy") {
            val idx = t.indexOf("--direction")
            if (idx >= 0) {
                val d = t.getOrNull(idx + 1) ?: return "$A11Y_TOOL scroll xy: --direction without value"
                if (d !in SCROLL_DIRECTIONS) {
                    return "$A11Y_TOOL scroll xy: bad --direction '$d' (known: ${SCROLL_DIRECTIONS.joinToString("|")})"
                }
            }
        }
        return null
    }
}
