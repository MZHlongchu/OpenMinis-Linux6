package com.openminis.app.ui.settings

import androidx.compose.ui.graphics.Color
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-color-split-legacy-clear] 外观颜色「模式分离」迁移的收尾回归。
 *
 * 缺陷：`clearColorOverride` 只把模式槽写成 NO_COLOR_OVERRIDE(-1)，而
 * `colorOverride` 只看**值**（非 -1 才直接返回）——「模式槽键不存在（该模式
 * 从未设置）」与「用户显式清除」不可区分。升级前用 `chat.userBubbleColor` /
 * `chat.bgColor` 单一键存过色的老用户，点「跟随主题」后被旧键回弹成升级前的
 * 旧色，反复点也无效；再次选色后清除同样回弹（只能清应用数据恢复）。
 *
 * 修法两条并用，本文件分别钉住：
 *
 * 1. 读侧用 `prefs.contains(modeKey)` 区分 absent / cleared —— 纯规则由生产
 *    函数 [resolveColorOverride] 承载，JVM 直接断言（cleared 压过 legacy）。
 * 2. 清除时一并作废旧单一键（迁移源），旧值不再永久残留、另一个未设置的模式
 *    槽也不会继续吃旧色。
 *
 * 本模块无 Robolectric，SharedPreferences 读写面测不了，故 contains 门控与旧键
 * 清除按仓库惯例（[PlanBoardColorBindingTest] / ChatConfigPrefsBindingTest）
 * 做源码级绑定检查。
 */
class AppearanceColorOverrideTest {

    private val legacyRed = 0xFFFF3B30L

    // ── 1. 纯决策：absent 才迁移，cleared 压过旧键 ──────────────────────

    @Test fun absentModeSlotMigratesLegacyValue() {
        // 老用户升级后首次打开：模式槽键不存在 → 迁移旧单一键（旧偏好存活）。
        assertEquals(legacyRed, resolveColorOverride(null, legacyRed))
    }

    @Test fun clearedModeSlotBeatsLegacyValue() {
        // 回归钉：显式「跟随主题」写入 -1 后，旧单一键不得再把它回弹成红色。
        assertEquals(NO_COLOR_OVERRIDE, resolveColorOverride(NO_COLOR_OVERRIDE, legacyRed))
        assertNull(
            "显式清除必须解析为「跟随主题」(null)",
            resolveColorOverride(NO_COLOR_OVERRIDE, legacyRed).takeOrNullIfNoOverride(),
        )
    }

    @Test fun explicitModeSlotWinsOverLegacyValue() {
        val pickedGreen = 0xFF34C759L
        assertEquals(pickedGreen, resolveColorOverride(pickedGreen, legacyRed))
        assertEquals(
            Color(0xFF34C759L),
            resolveColorOverride(pickedGreen, legacyRed).takeOrNullIfNoOverride(),
        )
    }

    @Test fun absentModeSlotWithAbsentLegacyFollowsTheme() {
        // 两侧都没设置 → 跟随主题(-1)，不是「回落到 0 / 纯黑」。
        assertEquals(NO_COLOR_OVERRIDE, resolveColorOverride(null, NO_COLOR_OVERRIDE))
        assertNull(resolveColorOverride(null, NO_COLOR_OVERRIDE).takeOrNullIfNoOverride())
    }

    /**
     * 老用户完整时间线：升级（只有旧键）→ 选色 → 跟随主题。第 3 步不得回弹，
     * 且清除同时作废旧键，故第 4 步（另一模式槽仍未设置）也跟随主题。
     */
    @Test fun migratedUserFollowThemeDoesNotRebound() {
        val pickedBlue = 0xFF007AFFL
        // 1) 从未设置过模式槽 → 旧键的红色。
        assertEquals(legacyRed, resolveColorOverride(null, legacyRed))
        // 2) 浅色模式选了蓝色 → 模式槽压过旧键。
        assertEquals(pickedBlue, resolveColorOverride(pickedBlue, legacyRed))
        // 3) 点「跟随主题」：模式槽 = -1（且旧键一并作废）→ 跟随主题。
        assertEquals(NO_COLOR_OVERRIDE, resolveColorOverride(NO_COLOR_OVERRIDE, NO_COLOR_OVERRIDE))
        // 4) 另一模式槽依旧 absent，但迁移源已作废 → 同样跟随主题。
        assertEquals(NO_COLOR_OVERRIDE, resolveColorOverride(null, NO_COLOR_OVERRIDE))
    }

    // ── 2. 源码级绑定：contains 门控 + 清除旧键 ─────────────────────────

    private val chatArea by lazy {
        // Tests run with the module dir as CWD.
        val f = File("src/main/java/com/openminis/app/ui/settings/AppearanceChatArea.kt")
        assertTrue("source not found: ${f.absolutePath}", f.isFile)
        f.readText()
    }

    /** 从 `fun <name>(` 切到下一个顶格 `}`。 */
    private fun functionBody(name: String): String {
        val at = chatArea.indexOf("fun $name(")
        assertTrue("no `fun $name(` in AppearanceChatArea.kt", at >= 0)
        val end = chatArea.indexOf("\n}", at)
        assertTrue("unterminated `fun $name(`", end > at)
        return chatArea.substring(at, end)
    }

    @Test fun readGatesOnKeyPresenceNotOnValue() {
        val read = functionBody("colorOverride")
        assertTrue(
            "colorOverride 必须用 prefs.contains(modeKey) 区分「从未设置」与「显式清除」，" +
                "否则「跟随主题」会被旧单一键回弹：\n$read",
            read.contains("prefs.contains(modeKey)"),
        )
        assertFalse(
            "旧门控（只看值）无法区分 absent / cleared，会造成回弹：\n$read",
            read.contains("if (mode != NO_COLOR_OVERRIDE) return mode"),
        )
        // 门控先于旧键回落：legacy 读取必须排在 contains 判定之后。
        val gate = read.indexOf("prefs.contains(modeKey)")
        val legacy = read.indexOf("prefs.getLong(KEY_CHAT_USER_BUBBLE_COLOR")
        assertTrue("legacy 分支必须在 contains 门控之后（gate=$gate legacy=$legacy）", gate in 0 until legacy)
        assertTrue(
            "colorOverride 的模式槽决策必须走纯函数 resolveColorOverride：\n$read",
            read.contains("resolveColorOverride(mode, legacy)"),
        )
    }

    @Test fun clearRetiresLegacySingleKeys() {
        val clear = functionBody("clearColorOverride")
        // 负向前瞻排除模式键（KEY_CHAT_USER_BUBBLE_COLOR_DARK / _LIGHT 前缀包含旧键名）。
        val userBubbleLegacy = Regex("KEY_CHAT_USER_BUBBLE_COLOR(?!_(DARK|LIGHT))")
        val bgLegacy = Regex("KEY_CHAT_BG_COLOR(?!_(DARK|LIGHT))")
        assertTrue(
            "clearColorOverride 必须一并作废旧单一键 chat.userBubbleColor（迁移源）：\n$clear",
            userBubbleLegacy.containsMatchIn(clear),
        )
        assertTrue(
            "clearColorOverride 必须一并作废旧单一键 chat.bgColor（迁移源）：\n$clear",
            bgLegacy.containsMatchIn(clear),
        )
        assertTrue(
            "清除写入 NO_COLOR_OVERRIDE（显式状态）：\n$clear",
            clear.contains("NO_COLOR_OVERRIDE"),
        )
        assertFalse(
            "不得改用 remove()：键不存在会被读侧当成「从未设置」而回落旧键：\n$clear",
            clear.contains(".remove("),
        )
    }

    @Test fun legacyConstantsStillDeclaredAsMigrationSource() {
        // 迁移源键名保持不变（旧版本写过这两个键，改名 = 老用户迁移失效）。
        val screen = File("src/main/java/com/openminis/app/ui/settings/AppearanceScreen.kt")
        assertTrue("source not found: ${screen.absolutePath}", screen.isFile)
        val text = screen.readText()
        assertTrue(text.contains("const val KEY_CHAT_USER_BUBBLE_COLOR = \"chat.userBubbleColor\""))
        assertTrue(text.contains("const val KEY_CHAT_BG_COLOR = \"chat.bgColor\""))
        assertTrue(text.contains("const val NO_COLOR_OVERRIDE = -1L"))
    }
}
