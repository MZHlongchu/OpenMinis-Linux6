package com.openminis.app.sandbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-shell-timeout-pgid] Issue #358 — one command outrunning its
 * timeout wedged its whole session.
 *
 * Reported on a vivo V2301A (Android 16, PRoot Alpine): a long curl loop hit
 * the 180s timeout, and from then on every `shell_execute` in that session
 * timed out too — `echo alive` included — while other sessions and the
 * interactive Terminal kept working.
 *
 * The timeout path did nothing to the runaway:
 *
 *     // Timeout — cancel pending, but don't kill the shell
 *     pendingCallback = null
 *
 * so it kept running inside the session's long-lived, REUSED shell. `/bin/sh`
 * reads stdin serially, so later commands merely queued behind it and could
 * not start — hence even a no-op command burned its full timeout.
 *
 * The fix runs each foreground command under `setsid`, giving it its own
 * process group, and kills that group on timeout. These tests pin the
 * decisions; the wrapper's runtime behaviour was verified on device (see the
 * measurements quoted in ForegroundCommandGroup's KDoc).
 *
 * [T-a7-shell-scan] 本移植在这一块与上游有意不同，扫描面因此按真实实现校准
 * （每条断言的原意图都在用例注释里说明）：
 *  - 命令跑在持久 shell 自己的进程组里，不用 `setsid sh -c` 子 shell 包装
 *    （cwd/导出变量必须跨命令保留，见 PersistentShell 的
 *    [T-android-shell-timeout-pgid-decision]；GuestLimits.wrapChild 也以编译期
 *    ERROR 禁止持久 shell 用子 shell 包装），超时由 GuardianScript 的 supervisor
 *    （按 wallSeconds 自杀进程组）+ 宿主侧 ForegroundCommandGroup 的精确 kill
 *    共同保证；
 *  - 读循环的 carry-over 与协议行剥离收在 MarkerFramer（ShellStreamDecoder.kt），
 *    不再内联在 PersistentShell 里。
 * 其余用例（包装器字符串、pgid 解析、kill 升级、超时回收 shell）与上游一致，未动。
 */
class ForegroundCommandGroupTest {

    private val marker = "abc12345"

    // ── The wrapper ────────────────────────────────────────────────────────

    @Test
    fun `the command runs in its own session via setsid`() {
        val w = ForegroundCommandGroup.wrap("echo hi", marker)
        assertTrue("must invoke setsid", w.contains(ForegroundCommandGroup.SETSID))
        assertTrue("must still emit the completion marker", w.contains("__MINIS_DONE_${marker}_EXIT_"))
        assertTrue("must report its pgid first", w.contains("__MINIS_PGID_${marker}_"))
    }

    @Test
    fun `the pgid is read from procfs, not from ps`() {
        // busybox `ps` has no `-o pgid=`, so field 5 of /proc/self/stat is the
        // only portable source inside this rootfs.
        val w = ForegroundCommandGroup.wrap("echo hi", marker)
        assertTrue(w.contains("/proc/self/stat"))
    }

    @Test
    fun `command text is passed through untouched, never parsed`() {
        // The whole reason for using a process group: these all contain `&`
        // and none of them is a background job. A string matcher would have to
        // parse shell grammar to tell them apart; the kernel already knows.
        val tricky = listOf(
            """curl 'https://x.test/a?u=1&v=2'""",
            """grep 'a & b' file.txt""",
            """awk '{print ${'$'}1 & ${'$'}2}'""",
            """echo "a && b"""",
        )
        for (cmd in tricky) {
            val w = ForegroundCommandGroup.wrap(cmd, marker)
            // Every original character survives, single quotes escaped POSIX-style.
            val expected = cmd.replace("'", "'\\''")
            assertTrue("must embed `$cmd` verbatim", w.contains(expected))
        }
    }

    @Test
    fun `an embedded single quote is escaped the POSIX way`() {
        val w = ForegroundCommandGroup.wrap("echo 'hi'", marker)
        // '\'' — close, escaped quote, reopen. Anything else would break the
        // wrapper's own quoting and mangle the user's command.
        assertTrue(w.contains("""echo '\''hi'\''"""))
    }

    @Test
    fun `no setsid means no wrapper, not a broken command`() {
        // A guest without setsid still has to run commands. Losing the
        // targeted kill is survivable; losing execution is not.
        val w = ForegroundCommandGroup.wrap("echo hi", marker, setsidAvailable = false)
        assertFalse(w.contains(ForegroundCommandGroup.SETSID))
        assertTrue(w.startsWith("echo hi"))
        assertTrue(w.contains("__MINIS_DONE_${marker}_EXIT_"))
    }

    // ── Parsing the group id ───────────────────────────────────────────────

    @Test
    fun `the reported pgid is parsed`() {
        assertEquals(24027, ForegroundCommandGroup.parsePgid("__MINIS_PGID_${marker}_24027__\n", marker))
    }

    @Test
    fun `a pgid embedded in surrounding output is still found`() {
        val chunk = "noise\n__MINIS_PGID_${marker}_991__\nmore output\n"
        assertEquals(991, ForegroundCommandGroup.parsePgid(chunk, marker))
    }

    @Test
    fun `an unknown pgid is null, so nothing is killed`() {
        // "Don't know" must never become "kill something else".
        assertNull(ForegroundCommandGroup.parsePgid("no marker here", marker))
        assertNull("truncated", ForegroundCommandGroup.parsePgid("__MINIS_PGID_${marker}_240", marker))
        assertNull("not a number", ForegroundCommandGroup.parsePgid("__MINIS_PGID_${marker}_abc__", marker))
        assertNull("another command's marker", ForegroundCommandGroup.parsePgid("__MINIS_PGID_other_42__", marker))
    }

    @Test
    fun `pgid 0 and 1 are refused`() {
        // kill -9 -0 targets the caller's own group; -1 is init.
        assertNull(ForegroundCommandGroup.parsePgid("__MINIS_PGID_${marker}_0__", marker))
        assertNull(ForegroundCommandGroup.parsePgid("__MINIS_PGID_${marker}_1__", marker))
        assertEquals(2, ForegroundCommandGroup.parsePgid("__MINIS_PGID_${marker}_2__", marker))
    }

    // ── The kill ───────────────────────────────────────────────────────────

    @Test
    fun `the kill targets the group and escalates`() {
        val k = ForegroundCommandGroup.killGroupCommand(4242)
        assertTrue("negative pid targets the group", k.contains("-4242"))
        assertTrue("TERM first", k.contains("kill -TERM -4242"))
        assertTrue("then KILL for anything that ignored it", k.contains("kill -KILL -4242"))
        assertTrue(
            "TERM must precede KILL",
            k.indexOf("kill -TERM") < k.indexOf("kill -KILL"),
        )
    }

    @Test
    fun `the kill detaches so it survives the shell it is killing`() {
        // The shell asked to run this is the one whose foreground is wedged.
        val k = ForegroundCommandGroup.killGroupCommand(7)
        assertTrue("must detach", k.contains(ForegroundCommandGroup.SETSID))
        assertTrue("and not block the shell", k.trimEnd().endsWith("&"))
    }

    // ── Recycling ──────────────────────────────────────────────────────────

    @Test
    fun `a shell is never reused after a timeout`() {
        // Even a successful kill leaves bytes the runaway wrote before dying
        // in the shell's stdout; those would be attributed to the next command.
        assertFalse(ForegroundCommandGroup.shellIsReusableAfterTimeout())
    }

    // ── Wiring ─────────────────────────────────────────────────────────────

    private val shellSrc by lazy {
        val f = File("src/main/java/com/openminis/app/sandbox/PersistentShell.kt")
        assertTrue("missing ${f.absolutePath}", f.exists())
        f.readText()
    }

    /**
     * [T-a7-shell-scan] 本移植把上游读循环里内联的三件事 —— marker 匹配的
     * carry-over、`__MINIS_PGID__` 学习、stripControlMarkers —— 收进了
     * [MarkerFramer]（ShellStreamDecoder.kt），读循环只留
     * "Utf8ChunkDecoder + MarkerFramer" 一条路径（见 PersistentShell.readLoop 的
     * [T-android-stale-stream-gate + T-p1-3-shell-auth-frames] 注释）。所以下面
     * 两条"读循环"契约扫描的是真正实现它们的文件，而不是 PersistentShell.kt；
     * 每条断言的原意图都保留，见各用例内注释。
     */
    private val framerSrc by lazy {
        val f = File("src/main/java/com/openminis/app/sandbox/ShellStreamDecoder.kt")
        assertTrue("missing ${f.absolutePath}", f.exists())
        f.readText()
    }

    @Test
    fun `the do-nothing timeout branch is gone`() {
        assertFalse(
            "the old 'don't kill the shell' comment must not survive — it is the bug",
            shellSrc.contains("Timeout — cancel pending, but don't kill the shell"),
        )
    }

    @Test
    fun `timeout kills the group and recycles the shell`() {
        val body = shellSrc.substringAfter("if (result == null) {").substringBefore("} else {")
        assertTrue("must kill the group", body.contains("killGroupCommand(pgid)"))
        assertTrue("must recycle the shell", body.contains("stop()"))
        assertTrue(
            "must keep whatever the command produced before the deadline",
            body.contains("cb?.output?.toString()"),
        )
        assertTrue("must still report 124", body.contains("124"))
    }

    @Test
    fun `a command with no known pgid is not killed blindly`() {
        val body = shellSrc.substringAfter("if (result == null) {").substringBefore("} else {")
        assertTrue(
            "the kill must be guarded on a known pgid",
            body.contains("cb?.pgid?.let"),
        )
    }

    @Test
    fun `executeCommand wraps through the policy, not by hand`() {
        // [T-a7-shell-wrap-scan] 上游 1.14 的包装器是 ForegroundCommandGroup.wrap()
        // —— `setsid sh -c '…'` 子 shell 包装，让命令自成 session/pgid。本移植
        // **有意不这么做**：命令就跑在持久 shell 自己的进程组里，"跨命令保留 cwd
        // 与导出变量"正是会话模型的一部分（[T-android-ghost-cwd] 的自愈逻辑依赖
        // 它），GuestLimits.wrapChild 也以编译期 ERROR 禁止持久 shell 使用子 shell
        // 包装（"A subshell wrap is the failure."）。本移植对应的"包装策略单一
        // 来源"是 GuardianScript.persistentCommand（命令体 + 按 wallSeconds
        // 自杀进程组的 supervisor），超时侧的精确 kill 仍由 ForegroundCommandGroup
        // 提供。原断言的意图——包装/超时策略必须由共享策略对象产出，不在 shell 里
        // 手写——不变，只是换成这两个真实来源（断言只多不少）。
        assertTrue(
            "the command body and its timeout supervisor must come from the shared policy",
            shellSrc.contains("GuardianScript.persistentCommand(command, budget.wallSeconds)"),
        )
        assertTrue(
            "the timeout kill must go through the shared policy too",
            shellSrc.contains("ForegroundCommandGroup.killGroupCommand(pgid)"),
        )
    }

    @Test
    fun `the reader carries a tail so a split marker is still matched`() {
        // [T-a7-shell-scan] 上游 1.14 把 carry-over 内联在 PersistentShell 的读循环
        // 里（`carryOver` + `MARKER_CARRY(96)` 固定字节尾巴）。本移植有意不保留那个
        // 常量，而把同一件事收进 [MarkerFramer]：它按"可能是 marker 前缀"的精确
        // 长度把尾巴 hold 住（见 PersistentShell 里的
        // [T-android-shell-marker-chunk-split]），跨读分割同样不会漏帧，且不依赖
        // 固定字节数。原断言的意图——读循环必须留尾巴，被切开 marker 仍要匹配上
        // ——不变，所以改扫真正实现它的 framer，并额外钉住读循环确实走它。
        assertTrue("carry-over buffer exists", framerSrc.contains("private val carry = StringBuilder()"))
        assertTrue(
            "the scan must span the carry-over plus the new chunk",
            framerSrc.contains("if (text.isNotEmpty()) carry.append(text)"),
        )
        assertTrue(
            "and a hold-back window for a partial marker prefix",
            framerSrc.contains("private fun holdIndex(text: String, markerPrefix: String): Int"),
        )
        assertTrue(
            "the hold-back keeps a marker-prefix-sized tail",
            framerSrc.contains("if (markerPrefix.startsWith(text.substring(text.length - len)))"),
        )
        assertTrue(
            "the read loop must go through the framer",
            shellSrc.contains("val framer = MarkerFramer(marker, frameTag)"),
        )
    }

    @Test
    fun `our control markers never reach the caller`() {
        // [T-a7-shell-scan] 上游 1.14 的读循环里有一个 stripControlMarkers(…)，把
        // `__MINIS_PGID__` 这类控制行从可见输出里剥掉。本移植不产生 PGID 行
        // （包装器不是 setsid 子 shell，见上一条用例与本文件顶部的说明），控制行
        // 只剩 bootstrap 的两条握手行；剥离动作同样收在 [MarkerFramer] 里。原断言
        // 的意图——控制行绝不进入调用方看到的输出——不变，所以改扫真正做剥离的
        // framer，并把"BEGIN 行被吃掉""读循环走 framer"一并钉住（只强不弱）。
        assertTrue(
            "the handshake protocol line must be stripped from visible output",
            framerSrc.contains("private fun stripProtocolLine(range: IntRange)") &&
                framerSrc.contains("stripProtocolLine(it.range)"),
        )
        assertTrue(
            "the BEGIN line must be consumed, not passed through",
            framerSrc.contains("carry.delete(0, beginIdx + beginPrefix.length)"),
        )
        assertTrue(
            "the read loop must go through the framer",
            shellSrc.contains("val framer = MarkerFramer(marker, frameTag)"),
        )
    }
}
