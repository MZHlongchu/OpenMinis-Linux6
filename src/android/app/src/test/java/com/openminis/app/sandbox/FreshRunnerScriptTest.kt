package com.openminis.app.sandbox

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * [T-android-fresh-exit-status-file] The fresh-process runner reports the
 * command's exit out of band, so completion no longer depends on the output
 * pipe reaching EOF.
 *
 * Reported on a Pixel 4a: `minis-mcp-cli tools amap-maps` printed its result,
 * yet the shell tool stayed "running" for minutes. On device: proot had no
 * children left, and the daemon the CLI detached (plus the amap MCP server it
 * started) had TracerPid = that proot. proot must keep tracing them for their
 * paths to work, and proot's own fd 1 was the only writer of the host pipe —
 * so EOF could never arrive, and only the timeout ended the call.
 *
 * These tests run the real [ForegroundCommandGroup.FRESH_RUNNER] under a host
 * POSIX shell (no proot, no setsid — neither changes what the script does with
 * its arguments or files). Which shell, and what happens when the host has
 * none that can carry the script faithfully, is [T-a1-host-posix-shell] below.
 */
class FreshRunnerScriptTest {

    private val dir: File = Files.createTempDirectory("fresh-runner").toFile()

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    /**
     * [T-a1-host-posix-shell] 本类要在"主机的 POSIX sh"下运行随 APK 下发的
     * `FRESH_RUNNER`。原来的写法把 `/bin/sh` 写死，在 Windows 主机上直接
     * `CreateProcess error=2`：整类 6 个进程测试报环境错误，而它们断言的是
     * 产品脚本的语义，不是主机环境。
     *
     * 仅把路径换成"能找到的 sh"还不够。Windows 上 Java 会把参数里的 `"`
     * 转义成 `\"`，MSYS/Git-Bash 的命令行解析会把这些引号吃掉，于是 runner
     * 文本（`> "$2.pid"`、`-c "$1"`）到达 shell 时引号已经没了，$1/$2 也被
     * 拆成多个词——测出来的是传参链路而不是脚本语义。所以这里先按候选顺序
     * 找 shell，再用一个**内容固定、形状与 runner 相同**的小脚本探针
     * （[HOST_SHELL_PROBE]：同样的 `"$1"`/`"$2"` 引号 + 一个带空格的参数）
     * 验证它能否忠实承载；找不到/承载不了就显式 Assume 跳过，并在报告里写明
     * 原因，而不是把环境问题报成缺陷。
     *
     * 探针刻意不用 [ForegroundCommandGroup.FRESH_RUNNER] 本身：若拿产品脚本
     * 当探针，runner 被改坏时测试会变成"跳过"而不是"失败"，正好把回归藏住。
     * 断言一条没删——在 Linux 主机/CI 上本类完整执行。
     */
    private fun requireHostShell(): String {
        val shell = hostShell
        Assume.assumeTrue(
            "主机上没有能忠实承载 FRESH_RUNNER 的 POSIX sh" +
                "（候选：${HOST_SHELL_CANDIDATES.joinToString(", ")}）：" +
                "该脚本面向 Android/Linux 侧的 /bin/sh，需在 POSIX 主机（如 Linux CI）上验证",
            shell != null,
        )
        return shell!!
    }

    private val hostShell: String? by lazy { HOST_SHELL_CANDIDATES.firstOrNull { canHostRunner(it) } }

    private fun canHostRunner(shell: String): Boolean = try {
        val status = File(dir, HOST_SHELL_PROBE_STATUS)
        status.delete()
        File(status.path + ".pid").delete()
        val p = ProcessBuilder(shell, "-c", HOST_SHELL_PROBE, "sh", HOST_SHELL_PROBE_ARG, status.path)
            .redirectErrorStream(true)
            .start()
        val finished = p.waitFor(10, TimeUnit.SECONDS)
        if (!finished) p.destroyForcibly()
        finished && p.exitValue() == 0 &&
            status.readText() == HOST_SHELL_PROBE_WANT &&
            File(status.path + ".pid").readText().trim().toIntOrNull() != null
    } catch (_: Throwable) {
        // 没有这个 shell、或它承载不了：试下一个候选。
        false
    }

    private fun start(command: String, status: File): Process =
        ProcessBuilder(requireHostShell(), "-c", ForegroundCommandGroup.FRESH_RUNNER, "sh", command, status.path)
            .redirectErrorStream(true)
            .start()

    private fun run(command: String): Triple<String, Int, File> {
        val status = File(dir, "s")
        val p = start(command, status)
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        return Triple(out, p.exitValue(), status)
    }

    @Test
    fun `the command's exit code is written and returned`() {
        val (out, code, status) = run("echo hi; exit 3")
        assertEquals("hi\n", out)
        assertEquals(3, code)
        assertEquals("3", status.readText())
    }

    @Test
    fun `a successful command reports 0`() {
        val (_, code, status) = run("true")
        assertEquals(0, code)
        assertEquals("0", status.readText())
    }

    @Test
    fun `exit inside the command still lets the runner write the status`() {
        // Why the command runs in a child shell: a bare `exit` would otherwise
        // end the runner before it could report anything.
        val (_, _, status) = run("exit 7; echo unreachable")
        assertEquals("7", status.readText())
    }

    @Test
    fun `a command killed by a signal reports 128 plus the signal`() {
        val (_, _, status) = run("kill -9 \$\$")
        assertEquals("137", status.readText())
    }

    @Test
    fun `the runner records its own pid for the group kill`() {
        val status = File(dir, "s")
        val p = start("true", status)
        p.inputStream.readBytes()
        p.waitFor(10, TimeUnit.SECONDS)
        val pid = File(status.path + ".pid").readText().trim().toIntOrNull()
        assertNotNull("pid file must hold a number", pid)
        assertTrue(pid!! > 1)
    }

    @Test
    fun `the command is passed through literally`() {
        val (out, _, _) = run("printf '%s|' 'a&b' \"x y\" '\$HOME'")
        assertEquals("a&b|x y|\$HOME|", out)
    }

    @Test
    fun `the status is written while a leftover process still holds stdout open`() {
        // The shape of the MCP hang: the command is finished, something it
        // left behind keeps the output pipe open, so EOF is far away. The
        // status file must not wait for it.
        val status = File(dir, "s")
        val p = start("(sleep 3; echo late) & echo done", status)
        val deadline = System.currentTimeMillis() + 1_500
        while (!status.exists() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("status must appear long before the pipe closes", status.exists())
        assertEquals("0", status.readText())
        val firstLine = p.inputStream.bufferedReader().readLine()
        assertEquals("done", firstLine)
        p.destroyForcibly()
    }

    // ── When the host may stop reading ────────────────────────────────────

    @Test
    fun `the stream is closed only once output has gone quiet after the status`() {
        val seen = 10_000L
        assertFalse(
            "not while output is still arriving",
            FreshProcessShell.outputSettled(now = seen + 100, statusSeenAt = seen, lastReadAt = seen + 90),
        )
        assertFalse(
            "not before the quiet window has passed since the status",
            FreshProcessShell.outputSettled(now = seen + 50, statusSeenAt = seen, lastReadAt = seen - 5_000),
        )
        assertTrue(
            FreshProcessShell.outputSettled(
                now = seen + FreshProcessShell.OUTPUT_QUIET_MS,
                statusSeenAt = seen,
                lastReadAt = seen,
            ),
        )
        assertTrue(
            "a detached process that keeps writing cannot hold the call past the cap",
            FreshProcessShell.outputSettled(
                now = seen + FreshProcessShell.OUTPUT_DRAIN_CAP_MS,
                statusSeenAt = seen,
                lastReadAt = seen + FreshProcessShell.OUTPUT_DRAIN_CAP_MS,
            ),
        )
    }

    private companion object {
        /**
         * [T-a1-host-posix-shell] 候选顺序沿用本移植既有约定（见
         * `MountWriteGuardScriptTest.shellPath`）：POSIX 主机先命中 "sh"。
         */
        val HOST_SHELL_CANDIDATES = listOf("sh", "/bin/sh", "/usr/bin/sh")

        /**
         * [T-a1-host-posix-shell] 承载能力探针：形状与 `FRESH_RUNNER` 相同
         * （引号包裹的 `"$1"`/`"$2"`、pid 文件、把 `$1` 原样写进状态文件），
         * 但内容固定，不引用产品脚本。参数带一个空格，用来暴露"参数被拆词"
         * 的宿主（Windows 上 Java→MSYS 的引号转义就是这样坏掉的）。
         */
        const val HOST_SHELL_PROBE =
            "printf '%s' \"\$\$\" > \"\$2.pid\"; printf 'ok:%s' \"\$1\" > \"\$2\""
        const val HOST_SHELL_PROBE_ARG = "probe arg"
        const val HOST_SHELL_PROBE_WANT = "ok:probe arg"
        const val HOST_SHELL_PROBE_STATUS = "host-shell-probe"
    }
}
