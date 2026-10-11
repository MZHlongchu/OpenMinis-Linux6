package com.openminis.app.notification

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [D7][T-approval-tap-must-land] Source guard:「落地才清通知」契约必须被**每一个**
 * 审批清除点执行。
 *
 * 回归现场：契约当初只落在 [ApprovalBroadcastReceiver]，而
 * `AgentForegroundService`（系统把 action intent 重路由到 onStartCommand 时）与
 * `ChatViewModel.approvePendingTool/denyPendingTool`（站内横幅）都**忽略返回值**
 * 并无条件 `cancelApproval` —— 归属不符或并发输掉的那一次点击会把通知条目连同
 * 用户的选择一起抹掉：等待者一路挂到 90s 超时，再把 "User REJECTED" 回给模型。
 *
 * [D8] 判据是**结构**的，不是行窗口：旧版要求「cancel 调用点前 25 行内出现过
 * [ApprovalNotifier.shouldClearEntryAfterTap] 的字样」，一句注释（或 KDoc 里对旧
 * 写法的说明）就能满足它 —— 实测假文件能通过。现在：先把注释与字符串字面量从源码
 * 里词法剥离，再对每个 `cancelApproval(` 调用点回溯定位它所在的 `if` 分支
 * （块形式看开括号前的语句头，无花括号形式看同一行的语句头），要求条件文本里就有
 * [ApprovalNotifier.shouldClearEntryAfterTap]（即「清不清」由契约决定）。
 *
 * 例外只有两处，见 [exempt]；将来真要新增例外，就在这里写下理由 —— 让例外成为
 * 一次刻意评审过的行为，而不是又一次静默的吞掉。同理，若将来把判据抽成局部变量
 * （`val ok = shouldClearEntryAfterTap(...); if (ok) { cancel }`），请先内联回条件
 * 或在这里写明理由：判据故意要求条件本身可读，不做数据流追踪。
 */
class ApprovalClearContractSourceTest {

    /** 允许不经契约直接清条目的文件 → 理由。 */
    private val exempt = mapOf(
        "ApprovalNotifier.kt" to
            "cancelApproval 的定义处（以及 cancelAll 的清理实现），不是点击路径",
        "SecurityGateHolder.kt" to
            "waitFor 之后：请求必然已解析或不存在（超时=gate 自己 deny 并摘除；" +
                "callerSessionId 就是归档键，belongsTo 不可能不符），清的是过期条目",
    )

    @Test
    fun `every approval clearing site sits inside an if branch gated by the landed-only contract`() {
        val root = File("src/main/java/com/openminis/app")
        assertTrue(
            "source root not found — this test locates sources relative to the " +
                "module dir (cwd=${File(".").absolutePath})",
            root.isDirectory,
        )

        val offenders = mutableListOf<String>()
        var guardedSites = 0

        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in exempt }
            .forEach { file ->
                val src = file.readText()
                val code = KotlinCode.blankCommentsAndLiterals(src)
                val raw = src.split('\n')
                for (call in KotlinCode.cancelApprovalCalls(code)) {
                    if (KotlinCode.guardingCondition(code, call) != null) {
                        guardedSites++
                    } else {
                        val line = KotlinCode.lineOf(code, call)
                        offenders += "${file.path}:$line: ${(raw.getOrNull(line - 1) ?: "").trim()}"
                    }
                }
            }

        assertTrue(
            "扫描疑似失效（没找到任何走契约的清除点），别让它静默通过；cwd=${File(".").absolutePath}",
            guardedSites >= 3,
        )
        assertTrue(
            buildString {
                append("审批通知的清除必须落在「由 ApprovalNotifier.shouldClearEntryAfterTap 决定的\n")
                append("if 分支」里（只按注释判定的旧判据已被一句注释骗过）：\n")
                append("只有点击真的落地、或请求已经不在待审批表里才清条目。\n")
                append("无条件 cancel 会把一次被拒的点击连通知一起吞掉，用户的选择消失、")
                append("请求挂到 90s 超时后才以 \"User REJECTED\" 回给模型。\n")
                append("若确有例外，请在 ApprovalClearContractSourceTest.exempt 里写明理由。\n\n")
                offenders.forEach { append("  ").append(it).append('\n') }
            },
            offenders.isEmpty(),
        )
    }

    @Test
    fun `an unguarded cancel cannot hide behind a comment or a bare if`() {
        val commentedBypass = """
            fun onReceive(ctx: Context, id: String) {
                // stillPending; ApprovalNotifier.shouldClearEntryAfterTap(tapLanded, stillPending)
                /* 旧写法走的就是这个契约 */
                ApprovalNotifier.cancelApproval(ctx, id)
            }
        """.trimIndent()
        assertTrue(
            "注释/字符串里的契约字样不能替代 if 分支（旧判据正是这样被蒙过的）",
            KotlinCode.unguardedSites(commentedBypass).size == 1,
        )

        val bareIf = """
            fun onReceive(ctx: Context, id: String) {
                if (tapLanded) {
                    ApprovalNotifier.cancelApproval(ctx, id)
                }
            }
        """.trimIndent()
        assertTrue(
            "条件里没有契约的 if 分支不算把关",
            KotlinCode.unguardedSites(bareIf).size == 1,
        )

        val guarded = """
            fun onReceive(ctx: Context, id: String) {
                val stillPending = ApprovalGate.detailOf(id) != null
                if (ApprovalNotifier.shouldClearEntryAfterTap(tapLanded, stillPending)) {
                    ApprovalNotifier.cancelApproval(ctx, id)
                } else {
                    Log.w(TAG, "keeping the notification so the user can retry")
                }
            }
        """.trimIndent()
        assertTrue("真把关的调用点必须通过", KotlinCode.unguardedSites(guarded).isEmpty())

        val guardedMultiLine = """
            fun onReceive(ctx: Context, id: String) {
                if (com.openminis.app.notification.ApprovalNotifier
                        .shouldClearEntryAfterTap(tapLanded, stillPending)
                ) {
                    com.openminis.app.notification.ApprovalNotifier.cancelApproval(this, id)
                }
            }
        """.trimIndent()
        assertTrue("跨行的 if 条件同样要看懂（AgentForegroundService 就是这种写法）",
            KotlinCode.unguardedSites(guardedMultiLine).isEmpty())

        val bracelessIf = """
            fun onReceive(ctx: Context, id: String) {
                val stillPending = ApprovalGate.detailOf(id) != null
                if (ApprovalNotifier.shouldClearEntryAfterTap(tapLanded, stillPending)) ApprovalNotifier.cancelApproval(ctx, id)
            }
        """.trimIndent()
        assertTrue("不带花括号的 if 分支同样是把关（合法的 Kotlin 写法，不该误报）",
            KotlinCode.unguardedSites(bracelessIf).isEmpty())
    }
}

/**
 * 结构判据的机械部分：**先词法剥离**（注释删除、字符串/字符字面量内容抹成空格，
 * 保留换行以便报行号），再做花括号回溯找 `cancelApproval(` 所在块的 `if` 头。
 */
private object KotlinCode {

    private const val CONTRACT = "shouldClearEntryAfterTap"
    private val IF_PREFIX = Regex("""\bif\s*\(""")

    /** 无花括号形式：`if (…)` 且语句以 `)` 收尾（head 已 trim）。 */
    private val BRACELESS_IF = Regex("""\bif\s*\(.*\)$""")

    /** 所有 `cancelApproval(` 调用点的绝对下标（定义处由 exempt 排除）。 */
    fun cancelApprovalCalls(code: String): List<Int> {
        val hits = mutableListOf<Int>()
        var from = 0
        while (true) {
            val at = code.indexOf("cancelApproval(", from)
            if (at < 0) return hits
            hits += at
            from = at + 1
        }
    }

    /** 未走契约的调用点（测试里用来验证判据本身）。 */
    fun unguardedSites(src: String): List<Int> {
        val code = blankCommentsAndLiterals(src)
        return cancelApprovalCalls(code).filter { guardingCondition(code, it) == null }
    }

    /**
     * 调用点的把关条件：所在 `{ ... }` 块的 `if` 头（块形式），或同一行上
     * `if (…) cancelApproval(…)` 的语句头（无花括号形式）；两者都没有契约时为 null。
     */
    fun guardingCondition(code: String, callIndex: Int): String? {
        val blockHead = enclosingBlockHead(code, callIndex)
        if (blockHead != null && IF_PREFIX.containsMatchIn(blockHead) && blockHead.contains(CONTRACT)) {
            return blockHead
        }
        // `if (cond) call()` 这种不带花括号的 if 分支：去掉 receiver 链之后，同一行的
        // 语句头必须正是一个以 `)` 收尾的 if 条件（否则 `if (…) … else call()` 之类
        // 会被误判成把关）。
        val head = bracelessIfHead(code, callIndex)
        return if (BRACELESS_IF.matches(head) && head.contains(CONTRACT)) head else null
    }

    /** 调用点所在 `{ ... }` 块的开括号之前那一段语句头；不在块里时为 null。 */
    private fun enclosingBlockHead(code: String, callIndex: Int): String? {
        var depth = 0
        var i = callIndex - 1
        while (i >= 0) {
            when (code[i]) {
                '}' -> depth++
                '{' -> {
                    if (depth == 0) return headBefore(code, i)
                    depth--
                }
            }
            i--
        }
        return null
    }

    /** 调用点所在行的语句头，末尾的 receiver 链（`Foo.`、`foo?.`）已剥掉。 */
    private fun bracelessIfHead(code: String, callIndex: Int): String {
        val raw = code.substring(code.lastIndexOf('\n', callIndex - 1) + 1, callIndex)
        val noReceiver = raw.trimEnd { ch -> ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '!' }
        return noReceiver.toString().trim()
    }

    /** 块开括号之前那一段**语句头**（上一处 `;`/`{`/`}` 之后）。 */
    private fun headBefore(code: String, braceIndex: Int): String {
        var i = braceIndex - 1
        while (i >= 0 && code[i] != ';' && code[i] != '{' && code[i] != '}') i--
        return code.substring(i + 1, braceIndex)
    }

    fun lineOf(code: String, index: Int): Int = 1 + code.take(index).count { it == '\n' }

    /**
     * 删除注释、把字符串/字符字面量内容抹成空格，**保留换行**（行号与原文一致）。
     * 非 Kotlin 生态的读者可以把它当作「只留下可能真的是代码的字符」。
     */
    fun blankCommentsAndLiterals(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        var blockDepth = 0
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else '\u0000'
            when {
                blockDepth > 0 -> when {
                    c == '/' && next == '*' -> { blockDepth++; out.append("  "); i += 2 }
                    c == '*' && next == '/' -> { blockDepth--; out.append("  "); i += 2 }
                    else -> { out.append(if (c == '\n') '\n' else ' '); i++ }
                }
                c == '/' && next == '/' -> { while (i < src.length && src[i] != '\n') { out.append(' '); i++ } }
                c == '/' && next == '*' -> { blockDepth++; out.append("  "); i += 2 }
                c == '"' && src.startsWith("\"\"\"", i) -> {
                    out.append("   "); i += 3
                    while (i < src.length && !src.startsWith("\"\"\"", i)) {
                        out.append(if (src[i] == '\n') '\n' else ' '); i++
                    }
                    if (i < src.length) { out.append("   "); i += 3 }
                }
                c == '"' -> {
                    out.append('"'); i++
                    while (i < src.length && src[i] != '"' && src[i] != '\n') {
                        if (src[i] == '\\') { out.append("  "); i += 2 } else { out.append(' '); i++ }
                    }
                    if (i < src.length && src[i] == '"') { out.append('"'); i++ }
                }
                c == '\'' -> {
                    out.append('\''); i++
                    while (i < src.length && src[i] != '\'' && src[i] != '\n') {
                        if (src[i] == '\\') { out.append("  "); i += 2 } else { out.append(' '); i++ }
                    }
                    if (i < src.length && src[i] == '\'') { out.append('\''); i++ }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}
