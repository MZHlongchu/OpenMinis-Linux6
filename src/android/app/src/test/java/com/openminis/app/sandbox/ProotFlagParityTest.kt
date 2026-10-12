package com.openminis.app.sandbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-fake-netlink-tty-parity] The two ways into the sandbox must build
 * the same proot command.
 *
 * There are two builders, and they are easy to edit in isolation:
 *   - `PRootKernel.buildProotCommand` — the agent's shell_execute / offload
 *     path, non-interactive, plain pipes.
 *   - `TerminalSession.buildTermuxArgs` — the Terminal screen, a real PTY.
 *
 * [T-android-proot-builder-scan-scope] 本移植把 Terminal 的 PTY 引擎从手写的
 * `PtyBridge` 换成了 Termux 终端引擎（`com.termux.terminal.TerminalSession`），
 * 于是上游那个 `private fun buildInteractiveCommand(sessionId)` 不存在了：
 * argv 现在由 `private fun buildTermuxArgs(sessionId, rootfsManager, proot)` 组装，
 * 而且它必须把 proot 二进制放在 argv[0]（Termux 的 JNI 直接 `execvp(cmd, argv)`，
 * PRoot 从 argv[1] 开始解析参数）。这只是同一件事换了个名字和宿主：
 * 本用例的意图——两条进沙箱的路必须带同一套隔离参数——一字未改，
 * 所以改的是扫描面（builder 名 + 取挂载的调用名），不是期望值。
 *
 * c9318f42f added `--fake-netlink` to the first one only. The sandbox then
 * behaved differently depending on which screen the command was launched
 * from: a tool run by the agent got proot's emulated (empty) rtnetlink reply,
 * while the same tool typed into the Terminal hit the real AF_NETLINK refusal
 * from Android's untrusted_app SELinux domain. That asymmetry is invisible
 * from either screen alone and makes any field report ambiguous — the first
 * question becomes "which shell did you use?".
 *
 * These are source-fact checks: both builders assemble an argv for a native
 * binary that cannot run in a JVM test, so what is verifiable here is that the
 * isolation-affecting flags stay in step.
 */
class ProotFlagParityTest {

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing source: ${f.absolutePath}", f.exists())
        return f.readText()
    }

    private val kernelSrc by lazy { src("src/main/java/com/openminis/app/sandbox/PRootKernel.kt") }
    private val terminalSrc by lazy { src("src/main/java/com/openminis/app/sandbox/TerminalSession.kt") }

    /** The body of one builder, so a flag mentioned elsewhere in the file does not count. */
    private fun builderBody(src: String, marker: String, end: String): String {
        val after = src.substringAfter(marker)
        assertTrue("could not locate $marker", after.isNotEmpty() && after != src)
        return after.substringBefore(end)
    }

    /**
     * `cmd.add("-0")` / `args.add("--fake-netlink")` — the local list's variable
     * name is an implementation detail (upstream's terminal builder called it
     * `cmd`, the Termux one calls it `args`), so the guard matches the call and
     * its quoted literal argument, not the receiver's spelling.
     */
    private fun addsFlag(body: String, flag: String): Boolean =
        Regex("""\b\w+\.add\("""" + Regex.escape(flag) + """"\)""").containsMatchIn(body)

    private val kernelBuilder by lazy {
        builderBody(kernelSrc, "fun buildProotCommand", "\n    fun ")
    }

    private val terminalBuilder by lazy {
        builderBody(terminalSrc, "private fun buildTermuxArgs(", "\n    private fun ")
    }

    @Test
    fun `both builders emulate rtnetlink`() {
        // The regression: one had it, the other did not, and `ssh` behaved
        // differently in the Terminal than under the agent.
        assertTrue(
            "PRootKernel.buildProotCommand must pass --fake-netlink",
            kernelBuilder.contains("""cmd.add("--fake-netlink")"""),
        )
        assertTrue(
            "TerminalSession.buildTermuxArgs must pass --fake-netlink too, " +
                "or the Terminal and the agent disagree about whether the sandbox " +
                "has network interfaces",
            addsFlag(terminalBuilder, "--fake-netlink"),
        )
    }

    @Test
    fun `the isolation flags match across both builders`() {
        // Beyond netlink: root emulation and the hardlink translation change
        // what guest programs can do at all. `apk add binutils` needs
        // --link2symlink; -0 is what makes the guest believe it is root.
        for (flag in listOf("-0", "--link2symlink")) {
            assertTrue("PRootKernel must pass $flag", addsFlag(kernelBuilder, flag))
            assertTrue("TerminalSession must pass $flag", addsFlag(terminalBuilder, flag))
        }
    }

    @Test
    fun `both builders bind the same kernel filesystems and the shared mounts`() {
        // A mount present in one and not the other means a path that resolves
        // under the agent and 404s in the Terminal (or the reverse).
        for (path in listOf("\"/dev\"", "\"/proc\"", "\"/sys\"")) {
            assertTrue("PRootKernel must bind $path", kernelBuilder.contains(path))
            assertTrue("TerminalSession must bind $path", terminalBuilder.contains(path))
        }
        // PRootKernel replays its (global-only) table. The terminal resolves its
        // mounts PER SESSION, through the same builder the agent's shells use —
        // so a mounted folder and a chat's workspace appear in both
        // ([T-android-session-private-mounts]).
        //
        // [T-android-proot-builder-scan-scope] 这条断言曾被整条删掉，此处按上游 1.14
        // 原文加回（消息字符串与匹配串一字未改）。它在当前生产上成立：本移植的
        // PRootKernel.buildProotCommand 仍在 kernelBuilder 切片内以
        // `for ((linuxPath, hostPath) in bindMounts)` 重放用户挂载
        // （PRootKernel.kt 的 “User bind mounts” 段）。
        // 判别力（已实测：把迭代源换成快照变量 `for ((k, v) in replayPlan)` 后，本用例
        // 即以这条断言红，且它是唯一的失败项）：agent 那条路若不再重放挂载表——循环被删、
        // 迭代源被换成 bindMounts 的副本/快照，或整个循环被挪到 buildProotCommand 之外——
        // 切片里就没有 "in bindMounts"，这条立刻红。
        // 它是「用户挂载在 agent 侧静默消失」的唯一守卫：本文件其余用例只扫
        // /dev、/proc、/sys 和会话挂载，FreshProcessShell 那条只看第三个 builder，
        // 都发现不了这一侧少了挂载重放。
        assertTrue(
            "PRootKernel must replay the user bind mounts",
            kernelBuilder.contains("in bindMounts"),
        )
        //
        // [T-android-proot-builder-scan-scope] 上游那条调用是
        // `SessionMounts.toProotArgs(SessionMounts.forContext(context, sessionId).mounts)`；
        // 本移植把 SessionMounts 的会话目录职责并进了
        // `ExecutionCoordinator.sessionBindMounts(sessionId)`（SessionWorkspace 那条链，
        // 见 SessionMounts.kt 顶部的说明），Terminal 改调后者。意图不变：
        // 会话挂载必须按 sessionId 现取。断言因此改成「取到了会话挂载」+
        // 「会话挂载排在全局表之后（覆盖它）」，而不是钉死上游的调用拼写。
        val globalAt = terminalBuilder.indexOf("PRootKernel.bindMounts")
        val sessionAt = terminalBuilder.indexOf("ExecutionCoordinator.sessionBindMounts(sessionId)")
        assertTrue(
            "TerminalSession must resolve its mounts per session, like the agent's shells",
            sessionAt >= 0,
        )
        assertTrue(
            "the session's own mounts must be merged AFTER the global table so they win",
            globalAt in 0 until sessionAt,
        )
        assertTrue(
            "TerminalSession must not iterate the global table as its final answer",
            !Regex("""for \(\(\w+, \w+\) in PRootKernel\.bindMounts\)""").containsMatchIn(terminalBuilder),
        )
    }

    @Test
    fun `both builders register the same native-offload handlers`() {
        for (body in listOf(kernelBuilder, terminalBuilder)) {
            assertTrue(
                "--native-offload must be wired in both, or minis-open / offload " +
                    "tools work in one shell only",
                body.contains("--native-offload="),
            )
        }
    }

    @Test
    fun `every place that assembles a proot argv is accounted for`() {
        // Drift guard. This used to scan only two files and assert "exactly
        // two builders" — which silently missed the third one added by
        // [T-android-shell-fresh-process] (FreshProcessShell.spawn), since a
        // file the test never read could not fail it. Enumerate the known
        // builders by name instead, so a NEW one has to be added here
        // deliberately rather than slipping past.
        //
        // [T-android-proot-builder-scan-scope] Terminal 的 builder 在本移植里叫
        // buildTermuxArgs（Termux 引擎，见文件头）；名字换了，枚举表跟着换，
        // 「新 builder 必须显式登记」这条规则不变。
        val known = listOf(
            "PRootKernel.kt" to "fun buildProotCommand",
            "TerminalSession.kt" to "fun buildTermuxArgs(",
            "FreshProcessShell.kt" to "fun spawn(",
        )
        for ((file, decl) in known) {
            val body = src("src/main/java/com/openminis/app/sandbox/$file")
            assertTrue("$file must still declare its builder ($decl)", body.contains(decl))
        }
        val all = kernelSrc + terminalSrc
        val builders = Regex("""fun build(ProotCommand|TermuxArgs)\(""").findAll(all).count()
        assertEquals("the two named builders", 2, builders)
    }

    @Test
    fun `the fresh-process builder carries the isolation flags too`() {
        // [T-android-shell-fresh-process] The third builder is subject to the
        // same rule as the other two: a sandbox that behaves differently
        // depending on which path launched the command is a debugging trap.
        val fresh = src("src/main/java/com/openminis/app/sandbox/FreshProcessShell.kt")
        for (flag in listOf("\"-0\"", "\"--link2symlink\"", "--fake-netlink", "--native-offload=")) {
            assertTrue("FreshProcessShell must pass $flag", fresh.contains(flag))
        }
        for (path in listOf("\"/dev\"", "\"/proc\"", "\"/sys\"")) {
            assertTrue("FreshProcessShell must bind $path", fresh.contains(path))
        }
        assertTrue(
            "and replay the session bind mounts",
            fresh.contains("in sessionBindMounts"),
        )
    }
}
