package com.openminis.app.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-plan-board-color] 任务看板背景色 RGB 覆盖链的**源码级**绑定检查
 * （仿 [ChatConfigPrefsBindingTest]：本模块无 Robolectric，SharedPreferences
 * 读写面 JVM 测不了，但键绑定与迁移语义在源码里完全可见）。
 *
 * 2c1f1f2 引入三槽（user bubble / chat bg / **plan board**）浅深分离存储，
 * 此前零测试。钉住三件事：
 *
 * 1. PLAN_BOARD 槽绑 `.light` / `.dark` 双键，且 read / store / clear
 *    三个函数**全部**绑对——只绑读不绑写会出现「设置了删不掉」的半残链。
 * 2. PLAN_BOARD 是新槽，**无旧单一键迁移源**（legacy 分支硬编码
 *    NO_COLOR_OVERRIDE）——不会误吸 USER_BUBBLE / CHAT_BG 的旧值。
 *    USER_BUBBLE / CHAT_BG 保留 legacy 回退（旧偏好存活）。
 * 3. 看板卡片消费 `planBoardColorOverride(context, isDark) ?:
 *    ChatColors.secondaryBg`——null = 跟随主题中性 chrome 面，非 null
 *    覆盖。accent（monetIconTint）不受背景覆盖影响。
 */
class PlanBoardColorBindingTest {

    private fun source(relative: String): String {
        // Tests run with the module dir as CWD.
        val f = File("src/main/java/com/openminis/app/$relative")
        assertTrue("source not found: ${f.absolutePath}", f.isFile)
        return f.readText()
    }

    private val chatArea by lazy { source("ui/settings/AppearanceChatArea.kt") }
    private val boardCard by lazy { source("ui/chat/PlanBoardCard.kt") }

    @Test fun planBoardModeKeysBoundInAllThreeAccessors() {
        // colorOverride / storeColorOverride / clearColorOverride 各有一个
        // PLAN_BOARD 分支 → 每个模式键至少出现 3 次（读、写、清）。
        val dark = Regex("KEY_CHAT_PLAN_BOARD_COLOR_DARK").findAll(chatArea).count()
        val light = Regex("KEY_CHAT_PLAN_BOARD_COLOR_LIGHT").findAll(chatArea).count()
        assertTrue("dark key bound $dark times (want >=3: read/store/clear)", dark >= 3)
        assertTrue("light key bound $light times (want >=3: read/store/clear)", light >= 3)
        // 键常量本身成对声明（AppearanceScreen）。
        val screen = source("ui/settings/AppearanceScreen.kt")
        assertTrue(screen.contains("const val KEY_CHAT_PLAN_BOARD_COLOR_LIGHT = \"chat.planBoardColor.light\""))
        assertTrue(screen.contains("const val KEY_CHAT_PLAN_BOARD_COLOR_DARK = \"chat.planBoardColor.dark\""))
    }

    @Test fun planBoardHasNoLegacyFallback() {
        // 新槽无旧键：legacy 分支硬编码 NO_COLOR_OVERRIDE，不误吸他槽旧值。
        assertTrue(
            "PLAN_BOARD legacy branch must hardcode NO_COLOR_OVERRIDE",
            chatArea.contains("ColorSlot.PLAN_BOARD -> NO_COLOR_OVERRIDE"),
        )
        // 对照组：老两槽保留 legacy 回退（旧偏好存活是 2c1f1f2 的迁移承诺）。
        assertTrue(chatArea.contains("ColorSlot.USER_BUBBLE -> prefs.getLong(KEY_CHAT_USER_BUBBLE_COLOR"))
        assertTrue(chatArea.contains("ColorSlot.CHAT_BG -> prefs.getLong(KEY_CHAT_BG_COLOR"))
    }

    @Test fun boardCardConsumesOverrideWithNeutralFallback() {
        // 覆盖优先、null 回退中性 chrome 面；isDark 取自 ChatColors（跟 app
        // 主题，非系统深浅）。
        assertTrue(
            "board card must read planBoardColorOverride(context, isDark) ?: ChatColors.secondaryBg",
            boardCard.contains("planBoardColorOverride(context, isDark)"),
        )
        assertTrue(boardCard.contains("?: ChatColors.secondaryBg"))
        // accent 不受背景覆盖影响（monetIconTint 独立于 cardFill）。
        assertTrue(boardCard.contains("val accent = monetIconTint(Color(0xFF007AFF))"))
    }
}
