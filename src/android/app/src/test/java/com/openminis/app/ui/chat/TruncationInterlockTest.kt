package com.openminis.app.ui.chat

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [D7][T-truncation-critical-section] 会话行剪裁的**共享临界区**回归。
 *
 * 残留现场（chat-vm#4）：`truncationJob` 只挡了 `sendMessage`，`retryFromMessage` /
 * `retryLast` / `rerunFromToolBlock` / CODE 回滚都不看它 —— 一次「从此处删除」的
 * `deleteMessagesAfter(sort_order >= cutoff)` 还在飞的时候，并发的重试/回滚把新行
 * 落库，落库的行号大于锚点 → 刚写进去的行被剪裁顺手删掉：气泡消失、agentHistory
 * 里没有这一行，模型对着不在上下文里的问题作答。
 *
 * 本测试钉住 [TruncationInterlock] 的两个语义（生产代码的每个入口都走它）：
 *  - 写入者 `awaitIdle`：**真的等**在飞剪裁收尾，等待期间自己那份行不受剪裁影响；
 *  - 剪裁者 `cutterMayProceed`：在飞剪裁期间拒绝并发，剪裁结束后（含取消）放行。
 *
 * [D8][T-truncation-critical-section] 但只测 helper 是**软**的：删掉任一调用点
 * （send/retry/rerun/resume/delete/rewind 里的 join 或登记）上面四个用例照旧全绿。
 * 因此文件末尾补了**调用点源码守卫**（结构判据，注释不可满足）+ 真实文件变异用例，
 * 详见那里的说明。
 */
class TruncationInterlockTest {

    @Test
    fun `a writer that waits for the in-flight cut keeps the row it appends`() = runBlocking {
        // 会话尾：锚点行 + 一个即将被剪裁掉的行。
        val rows = mutableListOf("anchor", "doomed")
        val cutEntered = CompletableDeferred<Unit>()
        val releaseCut = CompletableDeferred<Unit>()
        val cut = launch {
            cutEntered.complete(Unit)
            releaseCut.await()
            // 模拟 deleteMessagesAfter(cutoff = 1)：锚点之后的**一切**行都没了。
            while (rows.size > 1) rows.removeAt(rows.size - 1)
        }
        cutEntered.await()

        val writerStarted = CompletableDeferred<Unit>()
        val writer = launch {
            writerStarted.complete(Unit)
            // 重试/发送这一类写入者：先 join 剪裁，再落自己的行。
            TruncationInterlock.awaitIdle(cut)
            rows.add("retried-user-row")
        }
        writerStarted.await()
        // 给「不等就返回」的实现 50ms 去把新行落进 rows（正确实现此刻仍在挂起）。
        delay(50)
        releaseCut.complete(Unit)
        writer.join()

        assertEquals(
            "剪裁之后落库的行必须活着 —— 这正是 retry/send 丢行的现场",
            listOf("anchor", "retried-user-row"),
            rows,
        )
    }

    @Test
    fun `awaitIdle returns immediately when no cut is in flight`() = runBlocking {
        assertFalse("没有剪裁在飞时不应假装等过", TruncationInterlock.awaitIdle(null))

        val finished = launch { }
        finished.join()
        assertFalse("已完成的剪裁不再是临界区（isActive == false 的引用是惰性的）",
            TruncationInterlock.awaitIdle(finished))
    }

    @Test
    fun `a second cut is refused while one is in flight and admitted after it finishes`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch {
            entered.complete(Unit)
            release.await()
        }
        entered.await()
        assertFalse("在飞剪裁期间第二次剪裁必须被拒（两次交叠会锚在对方的中间态上）",
            TruncationInterlock.cutterMayProceed(first))

        release.complete(Unit)
        first.join()
        assertTrue("剪裁收尾后放行", TruncationInterlock.cutterMayProceed(first))
        assertTrue("没有剪裁时放行", TruncationInterlock.cutterMayProceed(null))
    }

    @Test
    fun `a cancelled cut stops blocking writers and cutters`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cut = launch {
            entered.complete(Unit)
            awaitCancellation()
        }
        entered.await()
        assertFalse(TruncationInterlock.cutterMayProceed(cut))

        cut.cancel()
        cut.join()
        assertTrue("取消后的剪裁不得永久占住临界区", TruncationInterlock.cutterMayProceed(cut))
        assertFalse("取消后写入者也不再被挡住", TruncationInterlock.awaitIdle(cut))
    }

    // ------------------------------------------------------------------
    // [D8][T-truncation-critical-section] 调用点源码守卫
    //
    // 上面四个用例只钉住 [TruncationInterlock] 的**语义** —— 删掉任一入口里的调用
    // 它们照旧全绿（第四轮验证暴露的软肋：一次「行会丢」的回归可以在全绿的情况下
    // 上线）。照审批线（[com.openminis.app.notification.ApprovalClearContractSourceTest]）
    // 的做法补**结构判据**，但判据不是「附近出现过函数名」（那个能被一句注释蒙过），
    // 而是执行顺序：
    //   · 写入者 —— 函数体里第一处 join（`TruncationInterlock.awaitIdle(` 或
    //     `truncationJob…join()`）必须**排在**第一处行写（appendMessage /
    //     deleteMessagesAfter / 物化排队轮）之前；
    //   · 剪裁者 —— `truncationJob = <job>` 必须早于 `job.start()`，且要么落在整个
    //     协程体之外（LAZY + 先登记后 start），要么在协程体里早于不可逆的长耗时动作
    //     （`controller.commit`）：后者正是「数秒级文件回滚在飞时第二个剪裁入口不被拒」
    //     的漏洞；另须有 identity 守卫的摘除与 admitCutter 留痕（被拒不能静默）。
    // 判据跑在**词法剥离**过的源码上：注释与字符串/字符字面量先被抹掉，所以「写一句
    // 注释冒充调用点」不可能通过。最后两个用例对**真实文件**做变异（删掉 join、
    // 删掉登记）并断言守卫变红 —— 那就是「删掉任一调用点仍全绿」的反例。
    // ------------------------------------------------------------------

    @Test
    fun `every history writer joins the in-flight cut before its first row write`() {
        val root = sourceRoot()
        writers.forEach { site ->
            val verdict = CutCallSiteGuard.writerVerdict(root.resolve(site.file).readText(), site.decl)
            assertTrue("${site.file} :: ${site.decl} → ${verdict.detail}", verdict.ok)
        }
    }

    @Test
    fun `every cutter registers the truncation job before the long work and releases it by identity`() {
        val root = sourceRoot()
        cutters.forEach { site ->
            val verdict = CutCallSiteGuard.cutterVerdict(
                root.resolve(site.file).readText(),
                site.decl,
                site.registerBefore,
            )
            assertTrue("${site.file} :: ${site.decl} → ${verdict.detail}", verdict.ok)
        }
    }

    @Test
    fun `removing any real call site turns the guard red`() {
        val root = sourceRoot()
        // 写入者：删掉 join 那一行（等价于「这个入口不再进临界区」），守卫必须变红。
        writers.forEach { site ->
            val src = root.resolve(site.file).readText()
            val line = CutCallSiteGuard.writerVerdict(src, site.decl).keyLine
            assertNotNull("守卫没能在 ${site.file} :: ${site.decl} 里定位 join 行", line)
            assertFalse(
                "${site.file} :: ${site.decl} —— 删掉 join 之后守卫仍然全绿，说明它没钉在调用点上",
                CutCallSiteGuard.writerVerdict(CutCallSiteGuard.blankLine(src, line!!), site.decl).ok,
            )
        }
        // 剪裁者：删掉 `truncationJob = <job>` 登记那一行，守卫必须变红。
        cutters.forEach { site ->
            val src = root.resolve(site.file).readText()
            val line = CutCallSiteGuard.cutterVerdict(src, site.decl, site.registerBefore).keyLine
            assertNotNull("守卫没能在 ${site.file} :: ${site.decl} 里定位登记行", line)
            assertFalse(
                "${site.file} :: ${site.decl} —— 删掉登记之后守卫仍然全绿，说明它没钉在调用点上",
                CutCallSiteGuard.cutterVerdict(
                    CutCallSiteGuard.blankLine(src, line!!),
                    site.decl,
                    site.registerBefore,
                ).ok,
            )
        }
    }

    @Test
    fun `the guard is structural — comments and string literals cannot satisfy it`() {
        val decl = "fun ChatViewModel.sendMessage("
        val onlyComments = """
            fun ChatViewModel.sendMessage() {
                // TruncationInterlock.awaitIdle(truncationJob)
                /* truncationJob?.takeIf { it.isActive }?.join() */
                val note = "TruncationInterlock.awaitIdle(truncationJob)"
                chatRepository.appendMessage("s", "user", "[]")
            }
        """.trimIndent()
        val fake = CutCallSiteGuard.writerVerdict(onlyComments, decl)
        assertFalse("注释/字符串里的 join 不是调用点，守卫不得被它满足：${fake.detail}", fake.ok)

        val real = """
            fun ChatViewModel.sendMessage() {
                TruncationInterlock.awaitIdle(truncationJob)
                chatRepository.appendMessage("s", "user", "[]")
            }
        """.trimIndent()
        assertTrue("真调用点必须通过：${CutCallSiteGuard.writerVerdict(real, decl).detail}",
            CutCallSiteGuard.writerVerdict(real, decl).ok)

        val tooLate = """
            fun ChatViewModel.sendMessage() {
                chatRepository.appendMessage("s", "user", "[]")
                TruncationInterlock.awaitIdle(truncationJob)
            }
        """.trimIndent()
        assertFalse("join 排在行写之后 = 没起作用，守卫必须变红：${CutCallSiteGuard.writerVerdict(tooLate, decl).detail}",
            CutCallSiteGuard.writerVerdict(tooLate, decl).ok)
    }

    private data class WriterSite(val file: String, val decl: String)

    private data class CutterSite(val file: String, val decl: String, val registerBefore: String)

    /** 会话行的每个写入口：这里的 join 一旦消失就会丢行。 */
    private val writers = listOf(
        WriterSite("ChatViewModelSendExt.kt", "internal fun ChatViewModel.sendMessage("),
        WriterSite("ChatViewModelRetryExt.kt", "fun ChatViewModel.retryLast()"),
        WriterSite("ChatViewModel.kt", "override fun retryFromMessage(messageId: String)"),
        WriterSite(
            "ChatViewModel.kt",
            "override fun rerunFromToolBlock(assistantMessageId: String, blockId: String)",
        ),
        WriterSite("ChatViewModelResumeExt.kt", "fun ChatViewModel.resume()"),
    )

    /** 两个剪裁入口 + 各自「不可逆长耗时动作」的锚点。 */
    private val cutters = listOf(
        CutterSite(
            "ChatViewModelDeleteExt.kt",
            "fun ChatViewModel.deleteFromMessage(messageId: String)",
            "job.start()",
        ),
        CutterSite(
            "ChatViewModelCheckpointExt.kt",
            "internal fun ChatViewModel.rewindToMessage(",
            "controller.commit(",
        ),
    )

    private fun sourceRoot(): File {
        val root = File("src/main/java/com/openminis/app/ui/chat")
        assertTrue(
            "source root not found — this test locates sources relative to the " +
                "module dir (cwd=${File(".").absolutePath})",
            root.isDirectory,
        )
        return root
    }
}

/**
 * 调用点结构守卫：在**词法剥离**过的源码上判定「A 必须排在 B 之前」。
 *
 * 为什么不用行窗口（审批线旧判据的 WINDOW=25）：窗口既能被注释满足，又会在调用点
 * 被搬远一格之后假绿/假红。这里比较词法位置 —— 但注释与字符串/字符字面量先被
 * 抹成空格（保留换行，行号与原文一致），因此位置只可能来自真实代码。
 */
private object CutCallSiteGuard {

    data class Body(val text: String, val start: Int) {
        val endExclusive: Int get() = start + text.length
    }

    data class Verdict(
        val ok: Boolean,
        /** 写入者：第一处 join 的行号；剪裁者：`truncationJob = <job>` 的行号。 */
        val keyLine: Int?,
        /** 写入者：第一处行写的行号；剪裁者：长耗时锚点的行号。 */
        val anchorLine: Int?,
        val detail: String,
    )

    /** 行写 = 决定 sort_order 的落库动作。 */
    private val ROW_WRITES = listOf(
        "chatRepository.appendMessage(",
        "chatRepository.deleteMessagesAfter(",
        "materializeQueuedPromptsAsUserTurn(",
    )

    /** `truncationJob = <某个 job>`；排除收尾复位用的 `truncationJob = null`。 */
    private val REGISTRATION = Regex("""truncationJob\s*=\s*(?!null\b)[A-Za-z_]""")

    private fun isJoin(line: String): Boolean =
        line.contains("TruncationInterlock.awaitIdle(") ||
            (line.contains("truncationJob") && line.contains(".join()"))

    private fun isRegistration(line: String): Boolean = REGISTRATION.containsMatchIn(line)

    private fun isIdentityRelease(line: String): Boolean = line.contains("truncationJob ===")

    /**
     * 词法剥离：删除注释、把字符串/字符字面量内容抹成空格，**保留换行**。
     * 于是「注释里写过 join」「字符串里提过 awaitIdle」都不算代码。
     */
    fun stripped(src: String): String {
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

    /**
     * [decl] 对应函数的**函数体**：跳过参数表（`fun f(a: () -> Unit = {})` 里的默认
     * lambda 不算），取括号配平后的第一个 `{` 到与之配对的 `}`。
     */
    fun bodyOf(code: String, decl: String, from: Int = 0): Body? {
        val at = code.indexOf(decl, from)
        if (at < 0) return null
        var paren = 0
        var open = -1
        var i = at
        while (i < code.length && open < 0) {
            when (code[i]) {
                '(' -> paren++
                ')' -> paren--
                '{' -> if (paren <= 0) open = i
            }
            i++
        }
        if (open < 0) return null
        var depth = 0
        var j = open
        while (j < code.length) {
            when (code[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return Body(code.substring(open, j + 1), open)
                }
            }
            j++
        }
        return null
    }

    /** 函数体里第一处满足 [match] 的行 → 该行首字符在**整个文件**里的绝对下标。 */
    private fun firstHit(body: Body, match: (String) -> Boolean): Int? {
        var offset = 0
        for (line in body.text.split('\n')) {
            if (match(line)) return body.start + offset
            offset += line.length + 1
        }
        return null
    }

    private fun lineOf(code: String, index: Int): Int = 1 + code.take(index).count { it == '\n' }

    fun writerVerdict(src: String, decl: String): Verdict {
        val code = stripped(src)
        val fn = bodyOf(code, decl) ?: return Verdict(false, null, null, "找不到声明：$decl")
        val joinIdx = firstHit(fn, ::isJoin)
        val writeIdx = firstHit(fn) { line -> ROW_WRITES.any { line.contains(it) } }
        val joinLine = joinIdx?.let { lineOf(code, it) }
        val writeLine = writeIdx?.let { lineOf(code, it) }
        if (joinIdx == null) {
            return Verdict(false, null, writeLine, "函数体里没有任何 join（这个入口没进临界区）")
        }
        if (writeIdx == null) {
            return Verdict(false, joinLine, null, "没找到行写 —— 判据会空转，先核对 ROW_WRITES")
        }
        if (joinIdx > writeIdx) {
            return Verdict(
                false,
                joinLine,
                writeLine,
                "join 在第 $joinLine 行，晚于第 $writeLine 行的首次行写",
            )
        }
        return Verdict(true, joinLine, writeLine, "join@$joinLine 早于首次行写@$writeLine")
    }

    fun cutterVerdict(src: String, decl: String, anchorNeedle: String): Verdict {
        val code = stripped(src)
        val fn = bodyOf(code, decl) ?: return Verdict(false, null, null, "找不到声明：$decl")
        val registerIdx = firstHit(fn, ::isRegistration)
        val anchorIdx = firstHit(fn) { it.contains(anchorNeedle) }

        fun fail(detail: String) = Verdict(
            false,
            registerIdx?.let { lineOf(code, it) },
            anchorIdx?.let { lineOf(code, it) },
            detail,
        )

        val admitIdx = firstHit(fn) { it.contains("TruncationInterlock.admitCutter(") }
        val lazyIdx = firstHit(fn) { it.contains("CoroutineStart.LAZY") }
        if (admitIdx == null) {
            return fail("入口准入必须走 TruncationInterlock.admitCutter（被拒要留痕，不能静默 return）")
        }
        if (lazyIdx == null) {
            return fail("协程必须以 CoroutineStart.LAZY 创建，登记才能在 start 之前可见")
        }
        val startIdx = firstHit(fn) { it.contains("job.start()") }
            ?: return fail("没找到 job.start()：「先登记后 start」的模式不完整")
        val register = registerIdx ?: return fail("没有 `truncationJob = <job>` 登记")
        val releaseIdx = firstHit(fn, ::isIdentityRelease)
            ?: return fail("没有 identity 守卫的摘除（`truncationJob === `）—— 收尾会摘掉别人的剪裁")
        val anchor = anchorIdx ?: return fail("没找到锚点 `$anchorNeedle`")
        if (register > startIdx) {
            return fail(
                "登记在第 ${lineOf(code, register)} 行，晚于第 ${lineOf(code, startIdx)} 行的 job.start()",
            )
        }
        val lambdaEnd = bodyOf(code, "viewModelScope.launch(", fn.start)?.endExclusive
        if (register > anchor && lambdaEnd != null && register < lambdaEnd) {
            return fail(
                "登记在第 ${lineOf(code, register)} 行，晚于第 ${lineOf(code, anchor)} 行的 " +
                    "`$anchorNeedle`，且仍在协程体内 —— 长耗时动作已经在临界区之外",
            )
        }
        return Verdict(
            true,
            lineOf(code, register),
            lineOf(code, anchor),
            "登记@${lineOf(code, register)} 早于 start@${lineOf(code, startIdx)}/" +
                "锚点@${lineOf(code, anchor)}，identity 摘除@${lineOf(code, releaseIdx)}",
        )
    }

    /** 把第 [line] 行（1-based）整行清空 —— 用于「删掉调用点」变异。 */
    fun blankLine(src: String, line: Int): String {
        val lines = src.split('\n').toMutableList()
        if (line - 1 in lines.indices) lines[line - 1] = ""
        return lines.joinToString("\n")
    }
}
