package com.openminis.app.sandbox

import com.openminis.app.ProductionSources
import com.openminis.app.data.ToolLimitPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * [T-android-parity-fixes] Shell-tool fixes from the iOS/Android parity review
 * of the scheduled prefilled-command work: the exit status is reported once,
 * the timeout ceiling is stated rather than silent, and a command reading
 * stdin gets EOF instead of hanging until the timeout.
 *
 * [T-merge-upstream-1-14] Two scan surfaces changed when this port split
 * `ChatViewModel` up and made the shell timeout user-configurable; both are
 * documented at the assertions that moved. The behavioural assertions
 * (ShellExitCode, the stdio setup) are unchanged.
 */
class ShellParityFixesTest {

    /**
     * [T-merge-upstream-1-14] The port runs the tool bodies as extension
     * functions in their own files (`ChatViewModelShellExt.kt`), and the cap
     * lives in [ToolLimitPrefs] instead of a `ChatViewModel` constant.
     * [ProductionSources.readFamily] is the house rule for that split ("扫描面从
     * 一个文件扩到「这个类的全部片段」，断言本身一个字不动"), so the drift guard
     * still fails if the old two-format exit annotation comes back, or if the
     * silent 900 s cap does.
     */
    private val shellToolSrc by lazy { ProductionSources.readFamily("ui/chat/ChatViewModel.kt") }

    // ── exit code: once, in one format ──────────────────────────────────────

    @Test
    fun `the exit status is appended once, as (exit code N)`() {
        // Coordinator layer, then chat layer — the path every failure takes.
        val fromCoordinator = ShellExitCode.ensureSuffix("boom", 1)
        assertEquals("boom\n(exit code: 1)", fromCoordinator)
        val seenByModel = ShellExitCode.ensureSuffix(fromCoordinator, 1)
        assertEquals("boom\n(exit code: 1)", seenByModel)
        assertEquals(1, Regex("""exit code""").findAll(seenByModel).count())
    }

    @Test
    fun `a timeout, which the coordinator leaves unannotated, is still labelled once`() {
        val out = ShellExitCode.ensureSuffix("partial\n[Command timed out after 5s]", 124)
        assertEquals("partial\n[Command timed out after 5s]\n(exit code: 124)", out)
        assertEquals(out, ShellExitCode.ensureSuffix(out, 124))
    }

    @Test
    fun `trailing whitespace does not defeat the duplicate check`() {
        assertEquals("x\n(exit code: 2)\n", ShellExitCode.ensureSuffix("x\n(exit code: 2)\n", 2))
    }

    @Test
    fun `neither layer writes the old second format`() {
        // The chat layer's call moved to ChatViewModelShellExt.kt with the tool
        // body; it is the same call the coordinator makes, so the model sees one
        // status in one format.
        assertFalse(
            "the old second format must not come back",
            shellToolSrc.contains("\" (exit code \${result.exitCode})\""),
        )
        assertTrue(
            shellToolSrc.contains("ShellExitCode.ensureSuffix(output, result.exitCode)"),
        )
        val coord = ProductionSources.read("sandbox/ExecutionCoordinator.kt")
        assertTrue(coord.contains("ShellExitCode.ensureSuffix(truncated, exitCode)"))
        assertFalse(coord.contains("\\n(exit code: \$exitCode)"))
    }

    // ── timeout ceiling ─────────────────────────────────────────────────────

    @Test
    fun `the timeout ceiling is one hour and the schema says so`() {
        // [T-merge-upstream-1-14] Upstream clamped the request in the tool body
        // against a `ChatViewModel.SHELL_TIMEOUT_MAX_SEC = 3600` constant. This
        // port resolves it through ToolLimitPrefs instead, because the ceiling is
        // user-configurable (Caps → Shell timeout). The invariant is the one
        // upstream's fix established and is asserted the same way: the request is
        // clamped against a NAMED ceiling rather than a silent literal, that
        // ceiling is one hour, and the schema states the real numbers.
        assertTrue(
            "the shell tool must clamp the requested timeout against the named ceiling",
            shellToolSrc.contains("ToolLimitPrefs.resolveShellTimeoutSec("),
        )
        assertEquals("the ceiling is one hour", 3_600L, ToolLimitPrefs.MAX_SHELL_TIMEOUT_SEC.toLong())
        assertEquals(
            "and the setting can actually hold it — the scheduler validates 1..3600",
            3_600L,
            ToolLimitPrefs.clampShell(3_600).toLong(),
        )
        assertFalse(
            "no silent 900 s cap left",
            shellToolSrc.contains("coerceIn(1, 900)"),
        )
        // The schema may not advertise a different default/cap than production
        // enforces — that mismatch is the bug upstream's wording fix removed.
        val tools = ProductionSources.read("tools/AgentTools.kt")
        assertTrue(
            "the schema must state this port's real default",
            tools.contains("Timeout in seconds (default: ${ToolLimitPrefs.DEFAULT_SHELL_TIMEOUT_SEC}, " +
                "maximum: ${ToolLimitPrefs.MAX_SHELL_TIMEOUT_SEC}"),
        )
        assertTrue(
            "the schema must point at the setting that enforces it",
            tools.contains("capped at the Caps → Shell timeout setting"),
        )
    }

    // ── stdin reads /dev/null ───────────────────────────────────────────────

    private fun run(vararg cmd: String): Pair<String, Int> {
        val pb = FreshProcessShell.configureStdin(ProcessBuilder(*cmd)).redirectErrorStream(true)
        val p = pb.start()
        val finished = p.waitFor(5, TimeUnit.SECONDS)
        if (!finished) {
            p.destroyForcibly()
            throw AssertionError("${cmd.toList()} was still waiting on stdin after 5 s")
        }
        return p.inputStream.bufferedReader().readText() to p.exitValue()
    }

    /**
     * [T-merge-upstream-1-14] These two run a REAL `/bin/sh` with stdin on
     * `/dev/null` — the app's target (Android) is POSIX, and that is what
     * [FreshProcessShell.configureStdin] redirects to. A JVM test host that is
     * Windows has neither, so `ProcessBuilder.start()` cannot even launch the
     * command. Skipped explicitly there rather than weakened: the assertions
     * below still run in full on any POSIX host (CI, Linux dev box), which is
     * where this port ships.
     */
    private fun assumePosixShell() {
        Assume.assumeTrue(
            "[T-merge-upstream-1-14] /dev/null and /bin/sh are POSIX-only; " +
                "the JVM test host is Windows, where this cannot be exercised",
            File("/dev/null").exists() && File("/bin/sh").canExecute(),
        )
    }

    @Test
    fun `a command that reads stdin gets EOF at once instead of hanging`() {
        assumePosixShell()
        // With ProcessBuilder's default stdin (an open pipe nobody writes to)
        // each of these blocks until the tool's timeout.
        assertEquals("" to 0, run("cat"))
        val (out, _) = run("/bin/sh", "-c", "read x; echo \"got:[\$x]\"")
        assertEquals("got:[]\n", out)
    }

    @Test
    fun `redirecting stdin leaves stdout, stderr and the exit status alone`() {
        assumePosixShell()
        val (out, code) = run("/bin/sh", "-c", "echo out; echo err 1>&2; exit 3")
        assertTrue(out.contains("out") && out.contains("err"))
        assertEquals(3, code)
    }

    @Test
    fun `the fresh-process spawn uses that stdio setup`() {
        val fresh = ProductionSources.read("sandbox/FreshProcessShell.kt")
        assertTrue(fresh.contains("val pb = ProcessBuilder(cmd)\n        configureStdin(pb)"))
        assertTrue(fresh.contains("pb.redirectInput(ProcessBuilder.Redirect.from(File(\"/dev/null\")))"))
    }
}
