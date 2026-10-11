package com.openminis.app.mcp.server

import com.openminis.app.sandbox.offload.OffloadCliSyntax
import com.openminis.app.security.Capability
import com.openminis.app.security.Decision
import com.openminis.app.security.GateCommand
import com.openminis.app.security.PermissionMode
import com.openminis.app.security.Reversibility
import com.openminis.app.security.RiskLevel
import com.openminis.app.security.SecurityGate
import com.openminis.app.tools.UiActionTool
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-d11-a11y-cli-syntax] MCP 侧的 `ui_read` / `ui_action` 必须与 agent 侧工具
 * **逐字节**生成同一条命令。
 *
 * 背景（工单 E3 第 3 条）：第四轮修了工具侧，MCP 侧却还在拼旧语法 ——
 * `read-screen | head -c 6000 || true`、裸 `tap/long-press/type/swipe/scroll/back`、
 * 以及一个并不存在的 `android-shizuku-cli launch` 组；预检还永远查 a11y 桩、
 * `|| true` 把失败洗成退出 0。两边各自演化过一次，所以这里不再"抄一份期望值"，
 * 而是**跑真实调用路径**再把结果与工具侧的输出对照：
 *
 *  1. 用 `SecurityGate` 拦下执行（测试不碰沙箱），从 classify 的参数里取回
 *     handler 真正交给 guest 的那段脚本 —— 走的是 `callTool` 的全流程；
 *  2. 脚本必须等于 `OffloadCliSyntax.guard(<工具侧同一条命令>)`：命令、外壳、
 *     缺桩预检的二进制名全部同一份来源；
 *  3. 坐标口径逐个取值与 `UiActionTool.plan` 对照：接受/拒绝必须一致，
 *     接受时渲染出的命令也必须一致。
 */
class McpUiArgvAlignmentTest {

    // ── ui_read ────────────────────────────────────────────────────────────

    @Test
    fun `ui_read uses the shared builder and stub guard`() {
        val script = capturedScript("ui_read", JSONObject())
        assertEquals(OffloadCliSyntax.guard(OffloadCliSyntax.readScreen()), script)
        val cmd = cliCommandIn(script)

        assertEquals("android-a11y-cli ui dump --compact", cmd)
        assertNull(OffloadCliSyntax.check(cmd))
        assertFalse("read-screen 不是子命令（真机恒退 2）", script.contains("read-screen"))
        // `| head -c N` 会把 CLI 的退出码换成 head 的 0：截断必须留在宿主侧
        assertFalse("退出码不能被管道顶掉", script.contains("| head"))
        assertFalse("CLI 的失败不能被 `|| true` 洗成成功", script.contains("|| true"))
        assertTrue("桩缺失必须显式 exit 127", script.contains("exit 127"))
        // 命令进的是长驻 bash 顶层：exit 127 必须在子 shell 里
        assertTrue(script.trimStart().startsWith("("))
        assertTrue(script.trimEnd().endsWith(")"))
    }

    // ── ui_action：每个动作与 agent 侧工具逐字节一致 ────────────────────────

    @Test
    fun `ui_action renders the same argv as the agent tool`() {
        val cases = listOf(
            """{"action":"tap","x":10,"y":20}""",
            """{"action":"long_press","x":10,"y":20}""",
            """{"action":"type","text":"hi"}""",
            """{"action":"type","text":"-5"}""",
            """{"action":"type","text":"--clear"}""",
            """{"action":"swipe","x":1,"y":2,"x2":3,"y2":4}""",
            """{"action":"scroll","x":5,"y":6,"direction":"down"}""",
            """{"action":"scroll","x":5,"y":6,"direction":"left"}""",
            """{"action":"back"}""",
            """{"action":"open_app","package_name":"com.android.settings"}""",
            """{"action":"open_url","url":"https://example.com/a?b=1&c=2"}""",
            """{"action":"tap","x":1,"y":2,"window_token":"a'b; rm -rf /"}""",
        )
        for (json in cases) {
            val obj = JSONObject(json)
            val want = when (val plan = UiActionTool.plan(obj.optString("action"), obj)) {
                is UiActionTool.Plan.Run -> plan.command
                is UiActionTool.Plan.Reject -> error("$json → agent 侧工具拒绝: ${plan.message}")
            }
            val script = capturedScript("ui_action", obj)
            assertEquals("$json → MCP 与工具生成的命令不一致", want, cliCommandIn(script))
            assertNull("$json → shim 不认识: ${OffloadCliSyntax.check(want)}", OffloadCliSyntax.check(want))
            assertEquals("$json → 外壳没走共享 guard", OffloadCliSyntax.guard(want), script)
        }
    }

    @Test
    fun `stub precheck names the binary that actually runs`() {
        val app = capturedScript("ui_action", JSONObject("""{"action":"open_app","package_name":"com.android.settings"}"""))
        assertTrue("open_app 用的是 shizuku 桩:\n$app", app.contains("[ -x /usr/local/bin/android-shizuku-cli ]"))
        assertFalse("预检不能写死 a11y 桩:\n$app", app.contains("android-a11y-cli"))

        val url = capturedScript("ui_action", JSONObject("""{"action":"open_url","url":"https://example.com"}"""))
        assertTrue("open_url 用的是 android-open 桩:\n$url", url.contains("[ -x /usr/local/bin/android-open ]"))
    }

    // ── 坐标口径：与 UiActionTool 同进同出 ─────────────────────────────────

    @Test
    fun `coordinate validation matches the agent tool`() {
        val raws = listOf("0", "20000", "20001", "-1", "12.5", "\"abc\"", "null", "1000000000000000000", "\"10\"", "3.0")
        for (raw in raws) {
            val obj = JSONObject("""{"action":"tap","x":$raw,"y":20}""")
            val plan = UiActionTool.plan("tap", obj)
            if (plan is UiActionTool.Plan.Run) {
                assertEquals("x=$raw → 两边渲染不一致", plan.command, cliCommandIn(capturedScript("ui_action", obj)))
            } else {
                // 工具拒绝的坐标，MCP 必须同样拒绝，而且**在生成命令之前**拒绝
                val (d, gate) = dispatcher()
                val r = d.callTool("ui_action", obj)
                assertTrue("x=$raw → MCP 接受了工具拒绝的坐标（静默替换？）", r.isError)
                assertTrue("x=$raw → 拒绝发生在建命令之后：${firstText(r)}", gate.classified.isEmpty())
            }
        }
    }

    @Test
    fun `argument rejections match the agent tool`() {
        val cases = listOf(
            """{"action":"type"}""",
            """{"action":"type","text":""}""",
            """{"action":"scroll","direction":"down"}""",
            """{"action":"scroll","x":1,"y":2,"direction":"sideways"}""",
            """{"action":"open_app"}""",
            """{"action":"open_app","package_name":"com.x; rm -rf /"}""",
            """{"action":"open_url"}""",
            """{"action":"swipe","x":1,"y":2,"x2":3}""",
        )
        for (json in cases) {
            val obj = JSONObject(json)
            assertTrue("$json → agent 侧工具本该拒绝", UiActionTool.plan(obj.optString("action"), obj) is UiActionTool.Plan.Reject)
            val (d, gate) = dispatcher()
            val r = d.callTool("ui_action", obj)
            assertTrue("$json → MCP 必须同样拒绝", r.isError)
            assertTrue("$json → 拒绝必须发生在建命令/过 gate 之前：${firstText(r)}", gate.classified.isEmpty())
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * classify 记录 + 一律 Denied：`ui_action`/`ui_read` 在 gate 判决后**直接返回**，
     * 因此测试既拿到了真实脚本，又不会去碰 ExecutionCoordinator（JVM 里没有沙箱）。
     */
    private class CaptureGate : SecurityGate {
        val classified = mutableListOf<Pair<String, String>>()

        override fun setPermissionMode(mode: PermissionMode) {}
        override fun getPermissionMode(): PermissionMode = PermissionMode.ALLOW_ALL
        override fun classify(toolName: String, toolArgs: String): GateCommand {
            classified.add(toolName to toolArgs)
            return GateCommand(toolName, toolArgs, Capability.PROCESS, Reversibility.REVERSIBLE, "test")
        }
        override fun classifyRisk(command: String): RiskLevel = RiskLevel.NORMAL
        override fun decide(cmd: GateCommand, mode: PermissionMode): Decision = Decision.Denied("capture")
        override fun preview(cmd: GateCommand): String = "test preview"
        override fun audit(cmd: GateCommand, decision: Decision, result: String?) {}
        override fun getAuditTrail(): List<com.openminis.app.security.AuditEntry> = emptyList()
    }

    private fun dispatcher(): Pair<McpToolDispatcher, CaptureGate> {
        val gate = CaptureGate()
        return McpToolDispatcher(null as android.content.Context?, gate, { PermissionMode.ALLOW_ALL }) to gate
    }

    /** 跑一次 `callTool`，取回它交给 SecurityGate 的那段 shell 脚本（= 会在 guest 里跑的原文）。 */
    private fun capturedScript(tool: String, args: JSONObject): String {
        val (d, gate) = dispatcher()
        val result = d.callTool(tool, args)
        val call = gate.classified.singleOrNull { it.first == tool }
            ?: error("$tool 没有走到 SecurityGate（被更早的校验拦下了？）：${firstText(result)}")
        return JSONObject(call.second).getString("command")
    }

    /** 从 guard 外壳里取出真正执行 CLI 的那一行。 */
    private fun cliCommandIn(script: String): String = script.lineSequence()
        .map { it.trim() }
        .first { it.startsWith("android-") }
        .removeSuffix("2>&1")
        .trim()

    private fun firstText(r: McpServerCore.CallResult): String =
        r.content.getJSONObject(0).getString("text")
}
