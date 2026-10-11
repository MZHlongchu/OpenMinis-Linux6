package com.openminis.app.sandbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-b4-#3-cover] 回归判据：只读挂载写防护不能因为"用户自己装了一个同名二进制"就
 * 静默失效。
 *
 * 旧行为：`/usr/local/bin/<cmd>` 存在且不带 [MountWriteGuardScript.MARKER] 时跳过安装
 * （T-b4-#3 为了不销毁用户二进制），于是 `cp`/`rm` 这些命令写只读挂载不再被拦，
 * 而且唯一痕迹是一行 `Log.w`。
 *
 * 新行为：兜底装到 guest PATH 里更靠前的 `/usr/local/sbin`；**只有 sbin（PATH 里更靠前的
 * 那一格）被用户的同名二进制占住**、我们再没有更靠前的落点时才判"防护不可用"，并落成
 * guest 可见的显式状态（[MountWriteGuardStatus]）。只占住 bin 不算不可用 —— 那正是兜底
 * 路径本身（判据见 `MountWriteGuardTargets.pick`：sbin 被占 ⇒ null；仅 bin 被占 ⇒ SBIN）。
 */
class MountWriteGuardTargetsTest {

    private val ours = MountWriteGuardTargets.Slot.OURS
    private val free = MountWriteGuardTargets.Slot.FREE
    private val foreign = MountWriteGuardTargets.Slot.FOREIGN

    @Test
    fun defaultInstallLocationStaysInUsrLocalBin() {
        assertEquals(MountWriteGuardTargets.BIN, MountWriteGuardTargets.pick(free, free))
        assertEquals(MountWriteGuardTargets.BIN, MountWriteGuardTargets.pick(ours, free))
        assertEquals(MountWriteGuardTargets.BIN, MountWriteGuardTargets.pick(ours, ours))
    }

    /** 回归本体：用户二进制占住 /usr/local/bin 时必须兜底到 PATH 更靠前的 sbin。 */
    @Test
    fun userBinaryInBinFallsBackToSbin() {
        assertEquals(MountWriteGuardTargets.SBIN, MountWriteGuardTargets.pick(foreign, free))
        assertEquals(MountWriteGuardTargets.SBIN, MountWriteGuardTargets.pick(foreign, ours))
    }

    /** sbin 在 bin 之前：它被别人的同名二进制占住时装哪儿都会被盖掉 ⇒ 明确不可用。 */
    @Test
    fun foreignBinaryInSbinMakesTheGuardUnavailable() {
        assertNull(MountWriteGuardTargets.pick(free, foreign))
        assertNull(MountWriteGuardTargets.pick(ours, foreign))
        assertNull(MountWriteGuardTargets.pick(foreign, foreign))
    }

    @Test
    fun fullyCoveredStatusSaysSo() {
        val text = MountWriteGuardStatus.text(total = 9, uncovered = emptyList(), active = true)
        assertTrue(text.contains("guard_active=true"))
        assertTrue(text.contains("guard_command_total=9"))
        assertTrue(text.contains("guard_command_uncovered_count=0"))
        assertTrue(text.contains("guard_command_uncovered=\n"))
        assertFalse("没有未覆盖命令时不能出现 NOT blocked 的告警", text.contains("NOT blocked"))
        assertEquals("/run/minis-mount-guards.txt", MountWriteGuardStatus.GUEST_PATH)
        assertTrue(text.endsWith("\n"))
    }

    /** 没有只读挂载时 wrapper 全部卸掉：状态文件必须如实说"没装"。 */
    @Test
    fun inactiveStatusDoesNotClaimCoverage() {
        val text = MountWriteGuardStatus.text(total = 9, uncovered = emptyList(), active = false)
        assertTrue(text.contains("guard_active=false"))
        assertTrue("必须说明为什么没装（没有只读挂载）", text.contains("no read-only mounted folder"))
        assertFalse(text.contains("every guarded command resolves"))
    }

    @Test
    fun uncoveredCommandsAreNamedAndExplained() {
        val text = MountWriteGuardStatus.text(total = 9, uncovered = listOf("cp", "rm"), active = true)
        assertTrue(text.contains("guard_command_uncovered_count=2"))
        assertTrue(text.contains("guard_command_uncovered=cp,rm"))
        assertTrue("必须说明这些命令的写防护是不可用的", text.contains("NOT blocked"))
        assertTrue("必须说明原因是用户的同名二进制", text.contains("user binary"))
        // 逐行 key=value：任何一行都能被 grep 出来，且没有引号/转义歧义。
        for (line in text.trim().lines()) {
            assertTrue("每行要么是注释要么是 key=value：$line", line.startsWith("#") || line.contains('='))
        }
    }

    /**
     * 源守卫：[MountWriteGuardTargets.SBIN] 兜底只在"guest PATH 里 sbin 排在 bin 之前"
     * 时成立，而这个前提写在另外几个文件里（PRootKernel 注入的 PATH、RootfsManager 的
     * default_mount、McpNativeBridge 的 MCP spawn env）。PATH 顺序一旦被改回去，
     * 兜底 wrapper 就会静默失效——所以这里机械地钉住它。
     */
    @Test
    fun guestPathLiteralsKeepSbinBeforeBin() {
        val root = File("src/main/java/com/openminis/app")
        assertTrue(
            "source root not found — this test locates sources relative to the module dir " +
                "(cwd=${File(".").absolutePath})",
            root.isDirectory,
        )

        val offenders = mutableListOf<String>()
        var pathLiterals = 0
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { f ->
                f.readLines().forEachIndexed { i, line ->
                    // 注释里可以随便写；只看代码。
                    val code = line.substringBefore("//").substringBefore("/*")
                    val binAt = code.indexOf("/usr/local/bin:")
                    if (binAt < 0) return@forEachIndexed
                    pathLiterals++
                    val sbinAt = code.indexOf("/usr/local/sbin:")
                    if (sbinAt < 0 || sbinAt > binAt) {
                        offenders += "${f.path}:${i + 1}: ${line.trim()}"
                    }
                }
            }

        assertTrue(
            "没扫到任何 guest PATH 字面量 —— 守卫已失效，请更新本测试",
            pathLiterals >= 3,
        )
        assertTrue(
            buildString {
                append("/usr/local/sbin 必须排在 /usr/local/bin 之前：MountWriteGuardTargets 的")
                append("兜底安装位置（用户二进制占住 bin 时唯一还能生效的目录）依赖这个顺序。\n")
                offenders.forEach { append("  ").append(it).append('\n') }
            },
            offenders.isEmpty(),
        )
    }
}
