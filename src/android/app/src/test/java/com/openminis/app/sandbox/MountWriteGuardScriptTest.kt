package com.openminis.app.sandbox

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * [T-s6-#6] 只读挂载写防护 wrapper 的回归测试。
 *
 * 旧实现把"任意位置的参数命中只读前缀"当成写，于是 `cp <mount>/a.md /root/`、
 * `cp -r <mount>/dir /tmp/`、`ln -s <mount>/a /root/a` 这些**纯读**操作被误拒
 * （报错文案却是 "writes are disabled"），同时 `dd of=<mount>/f` 因为不等于裸前缀
 * 而漏放。这里既断言逐命令的写目标语义，也在有 POSIX shell 的机器上真的把生成的
 * wrapper 跑一遍。
 *
 * shell 部分会先把脚本里两处环境绑定替换掉（`cfg=` 指向临时清单、`exec` 换成打印
 * 标记退出），被测的目标选择逻辑保持原样；替换失败即视为模板漂移并直接 fail，不会
 * 静默跳过。
 */
class MountWriteGuardScriptTest {

    private val RO = "/var/minis/mounts/ro"
    private val temps = mutableListOf<File>()

    @After
    fun cleanUp() {
        temps.forEach { runCatching { it.deleteRecursively() } }
    }

    // ── 语义表：每条命令的写目标位置 ────────────────────────────────────

    @Test
    fun writeTargetsAreDefinedPerCommand() {
        assertEquals(MountWriteGuardScript.TargetKind.DESTINATION, MountWriteGuardScript.targetKind("cp"))
        assertEquals(MountWriteGuardScript.TargetKind.LINK_TARGET, MountWriteGuardScript.targetKind("ln"))
        assertEquals(MountWriteGuardScript.TargetKind.MOVE, MountWriteGuardScript.targetKind("mv"))
        assertEquals(MountWriteGuardScript.TargetKind.DD_OUTPUT, MountWriteGuardScript.targetKind("dd"))
        for (cmd in listOf("touch", "tee", "mkdir", "rm", "rmdir")) {
            assertEquals(cmd, MountWriteGuardScript.TargetKind.ALL_POSITIONALS, MountWriteGuardScript.targetKind(cmd))
        }
        assertEquals(9, MountWriteGuardScript.commands.size)
        // 未登记的命令不装 wrapper（busybox `install` applet 未必存在，shadow 掉
        // 用户的 /usr/bin/install 代价更大）。见 MountWriteGuardScript KDoc。
        assertNull(MountWriteGuardScript.targetKind("install"))
    }

    @Test
    fun everyWrapperCarriesMarkerConfigAndDelegation() {
        for (cmd in MountWriteGuardScript.commands) {
            val script = MountWriteGuardScript.wrapperScript(cmd)
            assertTrue("$cmd: shebang", script.startsWith("#!/bin/sh\n"))
            assertTrue("$cmd: 卸载/覆盖判据依赖 MARKER", script.contains(MountWriteGuardScript.MARKER))
            assertTrue("$cmd: 清单路径", script.contains("cfg=${MountWriteGuardScript.CONFIG_PATH}"))
            assertTrue("$cmd: 委托 busybox", script.contains("exec /bin/busybox $cmd \"\$@\""))
        }
    }

    @Test
    fun onlyDestinationWrappersResolveASingleWriteTarget() {
        val cp = MountWriteGuardScript.wrapperScript("cp")
        assertTrue("cp 需要 -t/--target-directory 处理", cp.contains("--target-directory"))
        assertFalse("cp 不能把每个操作数都当写目标（源是读）", cp.contains("guard_reject \"\$arg\""))

        val ln = MountWriteGuardScript.wrapperScript("ln")
        assertFalse("ln 不能把每个操作数都当写目标（源是读）", ln.contains("guard_reject \"\$arg\""))

        val mv = MountWriteGuardScript.wrapperScript("mv")
        assertTrue("mv 的源被 unlink，也是写", mv.contains("guard_reject \"\$arg\""))

        val dd = MountWriteGuardScript.wrapperScript("dd")
        assertTrue("dd 只拦 of=", dd.contains("of=*"))
        assertFalse(dd.contains("guard_reject \"\$arg\""))
    }

    // ── 行为：真的跑 sh ───────────────────────────────────────────────

    @Test
    fun cpReadingOutOfAReadOnlyMountIsAllowed() {
        assertAllowed("cp", "$RO/a.md", "/root/")
        assertAllowed("cp", "-r", "$RO/dir", "/tmp/")
        assertAllowed("cp", "--", "$RO/a.md", "/root/b.md")
        assertAllowed("cp", "-t", "/tmp", "$RO/a.md")
    }

    @Test
    fun cpWritingIntoAReadOnlyMountIsRejected() {
        assertRejected("cp", "/root/a.md", RO)
        assertRejected("cp", "/root/a.md", "$RO/")
        assertRejected("cp", "/root/a.md", "$RO/b.md")
        assertRejected("cp", "--", "/root/a.md", "$RO/b.md")
        assertRejected("cp", "-t", RO, "/root/a.md")
        assertRejected("cp", "--target-directory=$RO", "/root/a.md")
    }

    @Test
    fun lnTreatsOnlyTheLinkNameAsAWrite() {
        assertAllowed("ln", "-s", "$RO/a", "/root/a")
        assertAllowed("ln", "-s", "$RO/a")
        assertRejected("ln", "-s", "/root/a", "$RO/a")
        assertRejected("ln", "-t", RO, "/tmp/a")
    }

    @Test
    fun mvChecksSourcesAndDestination() {
        assertRejected("mv", "$RO/a", "/tmp/")
        assertRejected("mv", "/tmp/a", "$RO/a")
        assertAllowed("mv", "/tmp/a", "/tmp/b")
    }

    @Test
    fun allOperandsCommandsRejectEveryTarget() {
        assertRejected("touch", "$RO/a")
        assertRejected("touch", "--", "$RO/a")
        assertRejected("mkdir", "-p", "$RO/d")
        assertRejected("rm", "-rf", RO)
        assertRejected("rmdir", "$RO/d")
        assertRejected("tee", "-a", "$RO/f")
        assertAllowed("touch", "/tmp/a")
        assertAllowed("mkdir", "/tmp/d")
    }

    /** [T-s6-#6] 附带发现（#5 同源）：`of=<mount>/f` 与裸前缀不相等，旧循环漏放。 */
    @Test
    fun ddChecksOnlyItsOutputOperand() {
        assertRejected("dd", "if=/dev/zero", "of=$RO/f", "bs=1", "count=0")
        assertAllowed("dd", "if=$RO/f", "of=/tmp/f")
    }

    // ── 测试脚手架 ────────────────────────────────────────────────────

    private data class RunResult(val exit: Int, val out: String, val err: String)

    private fun assertRejected(command: String, vararg args: String) {
        val r = run(command, args.toList())
        assertEquals(
            "$command ${args.joinToString(" ")} 必须被拒（exit=${r.exit} out=${r.out} err=${r.err}）",
            1,
            r.exit,
        )
        assertTrue(
            "$command ${args.joinToString(" ")} 的错误信息必须点明只读挂载：${r.err}",
            r.err.contains("read-only mounted folder"),
        )
    }

    private fun assertAllowed(command: String, vararg args: String) {
        val r = run(command, args.toList())
        assertEquals(
            "$command ${args.joinToString(" ")} 是读操作，必须放行（exit=${r.exit} err=${r.err}）",
            0,
            r.exit,
        )
        assertTrue("$command 放行后应委托给目标命令：${r.out}", r.out.contains(DELEGATED))
    }

    private fun run(command: String, args: List<String>): RunResult {
        val shell = shellPath()
        Assume.assumeTrue("主机上没有 POSIX sh，跳过 wrapper 行为验证", shell != null)
        val dir = tempDir()
        val cfg = File(dir, "prefixes").apply { writeText("$RO\n") }
        val execLine = "exec /bin/busybox $command \"\$@\""
        val template = MountWriteGuardScript.wrapperScript(command)
        val withCfg = template.replace(
            "cfg=${MountWriteGuardScript.CONFIG_PATH}",
            "cfg=${cfg.invariantSeparatorsPath}",
        )
        assertTrue("wrapper 模板必须仍然用 cfg=<CONFIG_PATH> 绑定清单", withCfg != template)
        val patched = withCfg.replace(execLine, "printf '$DELEGATED\\n'; exit 0")
        assertTrue("wrapper 模板必须仍然以 '$execLine' 委托", patched != withCfg)

        val script = File(dir, "$command.sh").apply { writeText(patched) }
        val outFile = File(dir, "stdout.txt")
        val errFile = File(dir, "stderr.txt")
        val process = try {
            ProcessBuilder(listOf(shell!!, script.invariantSeparatorsPath) + args)
                .redirectOutput(outFile)
                .redirectError(errFile)
                .start()
        } catch (t: Throwable) {
            // 沙箱/无 sh 的主机：跳过而不是把一个环境问题报成缺陷。
            Assume.assumeTrue("无法在主机上启动 $shell（${t.message}），跳过 wrapper 行为验证", false)
            throw t
        }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("$command wrapper 超时未退出")
        }
        return RunResult(process.exitValue(), outFile.readText(), errFile.readText())
    }

    private fun tempDir(): File =
        java.nio.file.Files.createTempDirectory("mount-write-guard").toFile().also { temps += it }

    private fun shellPath(): String? {
        for (candidate in listOf("sh", "/bin/sh", "/usr/bin/sh")) {
            try {
                val p = ProcessBuilder(candidate, "-c", "exit 0").start()
                if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) return candidate
            } catch (_: Throwable) {
                // 试下一个候选
            }
        }
        return null
    }

    private companion object {
        const val DELEGATED = "delegated"
    }
}
