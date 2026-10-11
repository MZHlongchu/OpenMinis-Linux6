package com.openminis.app.tools

import com.openminis.app.sandbox.offload.OffloadArgs
import com.openminis.app.sandbox.offload.OffloadCliSyntax
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-d11-a11y-cli-syntax] 回归：工具生成的 argv 必须落在 guest shim 真正 dispatch 的
 * 子命令表里。
 *
 * 背景（工单 tools-agent#1）：`ui_read` / `ui_action` 已经从宿主 bash 改到 guest 执行
 * （方向对），但命令串仍是凭印象拼的 —— `read-screen` 不存在，8 个动作里 7 个会落到
 * `unknown subcommand`（退出码 2）。也就是说"在真机上恒失败"只是换了个理由。
 *
 * 第四轮把这份测试的判据从「抄一份 handler 分支的副本」升级为**从 handler 源码里抽
 * 出来**：副本只挡得住抄错，挡不住 handler 与工具表两边一起漂；源码抽取挡得住单边漂移
 * （谁改了 `when (sub)` 或某个 `*Sub` 的分支而没改 [OffloadCliSyntax]，这里就红）。
 * 抽取器是**结构**判据：锚点、缩进、闭合括号任一处对不上就直接 require 失败，
 * 不会静默抽到空集合然后"通过"。
 *
 * 覆盖：
 *  1. [OffloadCliSyntax] 的表 == 从 handler 源码抽出的 dispatch 表；
 *  2. `ui_read` 渲染出的脚本、`ui_action` 每个动作 plan 出的命令，过
 *     [OffloadCliSyntax.check] 必须是"会在真机上被接受"；
 *  3. 桩缺失外壳（[OffloadCliSyntax.guard]）必须把 127 关在子 shell 里 ——
 *     顶层 exit 会打死会话持久 shell，DONE 帧永不打印；
 *  4. `-` 开头的文本必须走 `--` 终止符，且真的能被 handler 用的解析器落回位置参数；
 *  5. 旧写法（裸 tap / long-press / type / swipe / scroll <dir> / back / read-screen /
 *     shizuku launch）必须被判为未知，并且调用点源码里不再出现这些字样。
 */
class A11yToolArgvAlignmentTest {

    /** 相对 `src/main/java` 的路径，`source()` 会自己拼前缀。 */
    private val a11yHandler = "com/openminis/app/sandbox/offload/AccessibilityOffloadHandler.kt"
    private val shizukuHandler = "com/openminis/app/sandbox/offload/ShizukuOffloadHandler.kt"
    private val mcpDispatcher = "com/openminis/app/mcp/server/McpToolDispatcher.kt"

    // ── 1. shim 分发表：从 handler 源码抽出（不再抄副本）────────────────────

    @Test
    fun `cli table mirrors the shim dispatch`() {
        val src = source(a11yHandler)
        val handleAt = src.indexOf("override fun handle(")
        require(handleAt >= 0) { "handler 源码里找不到 handle()（抽取器要跟着改）" }
        val branches = whenBranches(src, "when (sub) {", from = handleAt)
        // `--version` 在 handle() 里就地返回，没有对应的 *Sub 函数：它不是工具会发的组。
        val inlineGroups = branches.filter { (_, fn) -> fn == null || !src.contains("private fun $fn(") }
            .map { it.first }.toSet()
        assertEquals(setOf("--version"), inlineGroups)

        val groups = branches.map { it.first }.filterNot { it in inlineGroups }
        assertEquals(OffloadCliSyntax.A11Y_SUBCOMMANDS.keys.toList(), groups)

        val extracted = branches.filter { it.first !in inlineGroups }
            .associate { (group, fn) -> group to subcommandsIn(functionBody(src, fn!!)) }
        assertEquals(OffloadCliSyntax.A11Y_SUBCOMMANDS, extracted)

        // input key 的白名单同样是"从源码抽"，不是抄一份副本
        val keys = whenBranches(src, "when (keyName.uppercase()) {").map { it.first }.toSet()
        assertEquals(OffloadCliSyntax.A11Y_INPUT_KEYS, keys)

        assertEquals(setOf("up", "down", "left", "right"), OffloadCliSyntax.SCROLL_DIRECTIONS)
        assertEquals("/usr/local/bin", OffloadCliSyntax.GUEST_BIN_DIR)
    }

    @Test
    fun `shizuku groups mirror the shim dispatch`() {
        val src = source(shizukuHandler)
        val groups = whenBranches(src, "when (group) {").map { it.first }.toSet()
        // `service` 在 when 之前就被特判（不依赖 Shizuku READY），必须单独抽出来核对
        val early = Regex("""if \(group == "([a-z-]+)"\) return handle\w+""")
            .findAll(src).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("service"), early)
        assertEquals(OffloadCliSyntax.SHIZUKU_GROUPS, groups + early)
    }

    // ── 2. ui_read ──────────────────────────────────────────────────────────

    @Test
    fun `ui_read renders a subcommand the shim dispatches`() {
        val script = UiReadTool.buildScript()
        val cmd = generatedCommand(script)

        assertEquals("android-a11y-cli ui dump --compact", cmd)
        assertNull("ui_read 生成了 shim 不认识的命令: ${OffloadCliSyntax.check(cmd)}", OffloadCliSyntax.check(cmd))
        assertFalse("read-screen 不是 android-a11y-cli 的子命令", script.contains("read-screen"))
        // 管道会把 CLI 的退出码换成 head 的 0 —— 那样"读屏失败"会报成功。
        assertFalse("不允许用管道截断输出（退出码会被 head 顶掉）", script.contains("| head"))
        assertTrue(script.contains("[ -x /usr/local/bin/android-a11y-cli ]"))
        assertTrue("桩缺失必须显式 exit 127", script.contains("exit 127"))
    }

    // ── 3. 桩缺失分支不得打死持久 shell（四个调用点共用 guard）──────────────

    @Test
    fun `stub-missing 127 stays inside a subshell`() {
        val script = UiReadTool.buildScript()
        // [T-d11-a11y-cli-stub-guard] 命令进的是长驻 bash 的**顶层**
        // (GuardianScript.persistentCommand 明确 "not inside parentheses")：
        // 顶层 exit 127 之后 `__minis_rc=$?` 与 DONE 帧都执行不到，宿主只看到
        // "[Shell not running]" / exit -1，真正的原因（桩没装）反而丢了。
        assertTrue("整段脚本必须包在子 shell 里，否则 exit 127 会打死会话持久 shell:\n$script",
            script.trimStart().startsWith("("))
        assertTrue("子 shell 必须闭合:\n$script", script.trimEnd().endsWith(")"))
        val exitAt = script.indexOf("exit 127")
        assertTrue("缺桩分支必须显式 exit 127:\n$script", exitAt >= 0)
        assertTrue("exit 127 必须落在子 shell 内:\n$script", exitAt < script.lastIndexOf(')'))

        // 外壳只留一份：四个调用点都必须从 OffloadCliSyntax.guard 取
        for (rel in listOf(
            "com/openminis/app/tools/UiReadTool.kt",
            "com/openminis/app/tools/UiActionTool.kt",
            mcpDispatcher,
        )) {
            assertTrue("$rel 必须用共享外壳 OffloadCliSyntax.guard(...)",
                codeOnly(source(rel)).contains("OffloadCliSyntax.guard("))
        }

        // 外壳里的二进制名必须来自命令串本身（open_app 是 shizuku 桩、open_url 是 android-open）
        val shizuku = OffloadCliSyntax.guard(OffloadCliSyntax.launchPackage("com.android.settings"))
        assertTrue(shizuku.contains("[ -x /usr/local/bin/android-shizuku-cli ]"))
        assertFalse("预检不能写死 a11y 桩:\n$shizuku", shizuku.contains("android-a11y-cli"))
        assertTrue(OffloadCliSyntax.guard(OffloadCliSyntax.openUrl("https://e.com"))
            .contains("[ -x /usr/local/bin/android-open ]"))
        assertNull(OffloadCliSyntax.check(OffloadCliSyntax.readScreen()))
    }

    // ── 4. ui_action ────────────────────────────────────────────────────────

    @Test
    fun `every ui_action renders a command the shim dispatches`() {
        val expected = mapOf(
            """{"action":"tap","x":10,"y":20}""" to "android-a11y-cli tap xy 10 20",
            """{"action":"long_press","x":10,"y":20}""" to "android-a11y-cli tap xy 10 20 --long",
            """{"action":"type","text":"hi"}""" to "android-a11y-cli input text 'hi'",
            """{"action":"type","text":"-5"}""" to "android-a11y-cli input text -- '-5'",
            """{"action":"type","text":"--clear"}""" to "android-a11y-cli input text -- '--clear'",
            """{"action":"swipe","x":1,"y":2,"x2":3,"y2":4}""" to "android-a11y-cli gesture swipe 1 2 3 4",
            """{"action":"scroll","x":5,"y":6,"direction":"down"}""" to "android-a11y-cli scroll xy 5 6 --direction down",
            """{"action":"back"}""" to "android-a11y-cli input key BACK",
            """{"action":"open_url","url":"https://example.com/a?b=1&c=2"}""" to "android-open 'https://example.com/a?b=1&c=2'",
        )
        for ((json, want) in expected) {
            val cmd = commandOf(json)
            assertEquals(json, want, cmd)
            assertNull("$json → shim 不认识: ${OffloadCliSyntax.check(cmd)}", OffloadCliSyntax.check(cmd))
        }
    }

    @Test
    fun `open_app uses the shizuku exec fallback with monkey`() {
        val cmd = commandOf("""{"action":"open_app","package_name":"com.android.settings"}""")
        assertEquals(OffloadCliSyntax.launchPackage("com.android.settings"), cmd)
        assertNull(OffloadCliSyntax.check(cmd))

        val tokens = OffloadCliSyntax.tokens(cmd)
        assertEquals(listOf("android-shizuku-cli", "exec"), tokens.take(2))
        // 命令体是**一个** argv 元素（否则 sh -c 会在空格处重新分词），包名在体内再引一层。
        assertEquals(3, tokens.size)
        assertEquals(
            "monkey -p 'com.android.settings' -c android.intent.category.LAUNCHER 1",
            tokens[2],
        )
    }

    @Test
    fun `window token survives shell quoting`() {
        val token = "a'b; rm -rf /"
        val cmd = commandOf("""{"action":"tap","x":1,"y":2,"window_token":${JSONObject.quote(token)}}""")
        assertNull(OffloadCliSyntax.check(cmd))
        val tokens = OffloadCliSyntax.tokens(cmd)
        val i = tokens.indexOf("--window")
        assertTrue("--window 必须原样带上", i > 0)
        assertEquals(token, tokens[i + 1])
    }

    // ── 5. `-` 开头的文本：builder 发 `--`，handler 的解析器必须落回位置参数 ──

    @Test
    fun `dash-leading text goes through the -- terminator`() {
        // [T-d11-a11y-cli-dashdash] OffloadArgs 把任何 -x/--xxx（长度 > 1）摘成 flag，
        // `input text '-5'` 于是丢掉 positional[2] → handler 恒退 2；`'--clear'` 更糟：
        // 值本身会被当成真的 clear 开关。引用层帮不上忙（过了 shell 之后 argv 仍是 -5）。
        assertEquals("android-a11y-cli input text -- '-5'", commandOf("""{"action":"type","text":"-5"}"""))
        assertEquals("android-a11y-cli input text -- '--clear'", commandOf("""{"action":"type","text":"--clear"}"""))
        assertNotNull(OffloadCliSyntax.check("android-a11y-cli input text -5"))
        assertNotNull("`--` 后面没有值同样是语法错", OffloadCliSyntax.check("android-a11y-cli input text --"))

        // 端到端：builder 渲染 → shell 分词 → **handler 真正用的那个解析器**。
        // 这一步才是"修好了"的证据：positional[2] 必须拿到原文。
        for (text in listOf("-5", "--clear", "-", "hi", "a'b", "x; rm -rf /")) {
            val tokens = OffloadCliSyntax.tokens(OffloadCliSyntax.inputText(text)).drop(1)
            assertEquals("input", tokens[0])
            assertEquals("text", tokens[1])
            assertEquals(
                "'$text' 没落到 positional[2]，handler 会判成缺参数",
                text,
                OffloadArgs(tokens).positional.getOrNull(2),
            )
            assertNull(OffloadCliSyntax.check(OffloadCliSyntax.inputText(text)))
        }
    }

    // ── 6. fail-closed 参数校验 ────────────────────────────────────────────

    @Test
    fun `bad arguments are rejected instead of rendered`() {
        val bad = listOf(
            """{"action":"tap","y":20}""",                                   // 缺 x
            """{"action":"tap","x":null,"y":20}""",                          // 显式 null
            """{"action":"tap","x":-1,"y":20}""",                            // 负数
            """{"action":"tap","x":12.5,"y":20}""",                          // 非整数
            """{"action":"tap","x":20001,"y":20}""",                         // 越界
            """{"action":"tap","x":"abc","y":20}""",                         // 非数字
            """{"action":"swipe","x":1,"y":2,"x2":3}""",                     // 缺 y2
            """{"action":"scroll","direction":"down"}""",                    // CLI 没有"只给方向"的滚动
            """{"action":"scroll","x":1,"y":2,"direction":"down; rm -rf /"}""",
            """{"action":"type"}""",
            """{"action":"type","text":""}""",
            """{"action":"open_app"}""",
            """{"action":"open_app","package_name":"com.x; rm -rf /"}""",    // 包名会进 sh -c 命令体
            """{"action":"open_url"}""",
            """{"action":"dance"}""",
        )
        for (json in bad) {
            val rejected = rejection(json)
            assertTrue("$json → 空错误信息", rejected.message.isNotBlank())
            assertTrue("$json → 空恢复提示", rejected.hint.isNotBlank())
        }
    }

    @Test
    fun `input key only accepts the keys the shim implements`() {
        assertNull(OffloadCliSyntax.check("android-a11y-cli input key HOME"))
        assertNotNull(OffloadCliSyntax.check("android-a11y-cli input key ENTER"))
    }

    // ── 7. 失败文案必须跟着实际跑的二进制走 ────────────────────────────────

    @Test
    fun `exit-code hint names the binary that actually ran`() {
        // open_app 走 android-shizuku-cli、open_url 走 android-open：写死 a11y 名字会把
        // 排查引到一条根本没被执行的命令上。
        val action = codeOnly(source("com/openminis/app/tools/UiActionTool.kt"))
        assertFalse("失败文案写死了 android-a11y-cli", action.contains("android-a11y-cli exited"))
        assertTrue("必须从命令串取实际二进制名", action.contains("val tool = OffloadCliSyntax.toolName(cmd)"))
        assertTrue("失败文案必须用 \$tool 插值", action.contains("\$tool exited "))

        val read = codeOnly(source("com/openminis/app/tools/UiReadTool.kt"))
        assertFalse(read.contains("\"android-a11y-cli returned exit"))
        assertTrue(read.contains("\$TOOL returned exit "))
    }

    // ── 8. 旧写法既不可执行、也不再出现在调用点 ─────────────────────────────

    private val legacyForms = listOf(
        "android-a11y-cli read-screen",
        "android-a11y-cli tap ",
        "android-a11y-cli long-press",
        "android-a11y-cli type",
        "android-a11y-cli swipe",
        "android-a11y-cli scroll",
        "android-a11y-cli back",
        "android-shizuku-cli launch",
    )

    @Test
    fun `legacy command forms are unknown to the shim`() {
        for (cmd in listOf(
            "android-a11y-cli read-screen",
            "android-a11y-cli tap 100 200",
            "android-a11y-cli long-press 100 200",
            "android-a11y-cli type 'hi'",
            "android-a11y-cli swipe 1 2 3 4",
            "android-a11y-cli scroll down",
            "android-a11y-cli back",
            "android-shizuku-cli launch 'com.android.settings'",
        )) {
            assertNotNull("'$cmd' 被 check() 当成了合法命令", OffloadCliSyntax.check(cmd))
        }
    }

    @Test
    fun `no call site hand-writes a legacy command any more`() {
        val files = listOf(
            "com/openminis/app/tools/UiReadTool.kt",
            "com/openminis/app/tools/UiActionTool.kt",
            "com/openminis/app/accessibility/SkillReplayEngine.kt",
            "com/openminis/app/sandbox/offload/OffloadCliSyntax.kt",
            mcpDispatcher,
        )
        for (rel in files) {
            val code = codeOnly(source(rel))
            for (form in legacyForms) {
                assertFalse("$rel 里仍有旧命令串 '$form' —— 请改用 OffloadCliSyntax builder", code.contains(form))
            }
        }
    }

    // ── 抽取器：结构判据，抽不到就 require 失败（绝不静默通过）─────────────

    /**
     * 取 `anchor` 所在那一层缩进的 `"label" -> rhs` 分支。
     * `null`/`else` 不是字符串字面量，天然被排除；更深缩进的嵌套块不会被当成分支。
     */
    private fun whenBranches(
        src: String,
        anchor: String,
        from: Int = 0,
    ): List<Pair<String, String?>> {
        val at = src.indexOf(anchor, from.coerceAtLeast(0))
        require(at >= 0) { "源码里找不到分发锚点 '$anchor'" }
        val indent = indentAt(src, at)
        val close = "\n" + " ".repeat(indent) + "}"
        val end = src.indexOf(close, at)
        require(end > at) { "'$anchor' 的闭合括号找不到（缩进风格变了？）" }
        val block = src.substring(at, end)
        val branchIndent = indent + 4
        return Regex("^ {$branchIndent}\"([^\"]+)\"\\s*->\\s*(\\w+)?", RegexOption.MULTILINE)
            .findAll(block)
            .map { it.groupValues[1] to it.groupValues[2].ifEmpty { null } }
            .toList()
    }

    /** `private fun <name>(…) { … }`：到下一处 4 空格缩进的 `}`（成员函数结尾）为止。 */
    private fun functionBody(src: String, name: String): String {
        val at = src.indexOf("private fun $name(")
        require(at >= 0) { "源码里找不到 private fun $name(" }
        val end = src.indexOf("\n    }", at)
        require(end > at) { "找不到 $name 的函数结尾（缩进风格变了？）" }
        return src.substring(at, end)
    }

    /**
     * 一个 `*Sub` 函数认下的子命令。两种写法都要认：
     *  - `return when (args.positional.getOrNull(1)) { "dump" -> … }`
     *  - guard 式（notifySub）：`if (sub != "watch" && sub != "once") …`
     */
    private fun subcommandsIn(body: String): Set<String> {
        val fromWhen = runCatching { whenBranches(body, "when (args.positional.getOrNull(1)) {") }
            .getOrNull().orEmpty().map { it.first }
        val fromGuard = Regex("\\bsub\\s*(?:==|!=)\\s*\"([^\"]+)\"")
            .findAll(body).map { it.groupValues[1] }.toList()
        val all = (fromWhen + fromGuard).toSet()
        require(all.isNotEmpty()) {
            "没能从函数体抽出子命令分支，抽取器要跟着源码写法更新:\n${body.take(200)}"
        }
        return all
    }

    private fun indentAt(src: String, at: Int): Int {
        val lineStart = src.lastIndexOf('\n', at) + 1
        return src.substring(lineStart, at).takeWhile { it == ' ' }.length
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * 去掉整行注释后再扫：KDoc/注释里引用历史命令字样（说明为什么改）不算"还在拼命令"，
     * 否则文档一提到旧语法测试就红。
     */
    private fun codeOnly(text: String): String = text.lineSequence()
        .filterNot {
            val t = it.trim()
            t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
        }
        .joinToString("\n")

    /** 取脚本里实际执行 CLI 的那一行（`… 2>&1` 尾缀去掉）。 */
    private fun generatedCommand(script: String): String =
        script.lineSequence().map { it.trim() }
            .first { it.startsWith("android-") }
            .removeSuffix("2>&1")
            .trim()

    private fun commandOf(argsJson: String): String {
        val obj = JSONObject(argsJson)
        return when (val plan = UiActionTool.plan(obj.optString("action"), obj)) {
            is UiActionTool.Plan.Run -> plan.command
            is UiActionTool.Plan.Reject -> error("expected a command, got rejection: ${plan.message}")
        }
    }

    private fun rejection(argsJson: String): UiActionTool.Plan.Reject {
        val obj = JSONObject(argsJson)
        return when (val plan = UiActionTool.plan(obj.optString("action"), obj)) {
            is UiActionTool.Plan.Reject -> plan
            is UiActionTool.Plan.Run -> error("expected a rejection, got command: ${plan.command}")
        }
    }

    /** 读 app 模块的 main 源码，兼容 user.dir = 模块目录 / src/android / 仓库根。 */
    private fun source(relative: String): String {
        val candidates = listOf(
            "src/main/java/$relative",
            "app/src/main/java/$relative",
            "src/android/app/src/main/java/$relative",
        )
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir missing"))
        while (dir != null) {
            for (candidate in candidates) {
                val f = File(dir, candidate)
                if (f.isFile) return f.readText()
            }
            dir = dir.parentFile
        }
        error("source not found: $relative (user.dir=${System.getProperty("user.dir")})")
    }
}
