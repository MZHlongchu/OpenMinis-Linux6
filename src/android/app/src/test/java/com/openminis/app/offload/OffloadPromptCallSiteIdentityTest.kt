package com.openminis.app.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-android-prompt-identity] [T-android-prompt-launch-ledger] 两个槽类都要求应答回传身份，
 * UI 侧的调用点必须真的带上它 —— 而且系统运行时权限对话框只能有**一个**驱动方。
 *
 * 这些调用点活在 MainActivity / ChatScreen / AndroidPermissionDialogBridge 里（要 Android
 * 运行时 + Compose 才能跑），本模块没有 Robolectric，所以按仓库惯例（同
 * [OffloadPromptDecisionTest]）对源码断言：
 *  - 单槽 / 队列的 respond 都校验身份；
 *  - 不允许再出现"不带身份"的应答调用（旧写法一旦复活就失败）；
 *  - 系统权限对话框由 `AndroidPermissionDialogBridge` 独占：MainActivity / ChatScreen 里
 *    不许再出现第二份 launcher（两份会为同一张提示各弹一次，后弹的还覆写身份字段）。
 */
class OffloadPromptCallSiteIdentityTest {

    private fun src(rel: String) = File("src/main/java/com/openminis/app/$rel").readText()

    /**
     * 旧写法：`respond(result)` / `respond(granted)` / `respond(Decision.X)` —— 不带身份。
     * 这种调用在金库里没有身份可比，只能落到"当前在屏的那一槽"上，也就是票里那个
     * "用户答 A、B 拿到 GRANTED" 的错配。
     */
    private val anonymousAnswer = Regex(
        "respondTo(?:AndroidPermission|SettingsGate)\\(\\s*" +
            "(?:OffloadPermissionManager\\.)?(?:AndroidPermissionResult|SettingsGateDecision)\\.|" +
            "respondToAndroidPermission\\(\\s*(?:true|false|grantedNow|allGranted)"
    )

    @Test
    fun `MainActivity delegates the system dialog and still answers with gate identities`() {
        val body = src("MainActivity.kt")
        assertTrue(
            "系统权限对话框必须交给唯一驱动方:\n$body",
            body.contains("AndroidPermissionDialogBridge(this)") &&
                body.contains(".register(savedInstanceState?.getLong(KEY_PERMISSION_IN_FLIGHT_ID, 0L))"),
        )
        assertFalse(
            "MainActivity 不能再自己注册/应答系统权限对话框（双驱动就是这样来的）:\n$body",
            body.contains("RequestMultiplePermissions") || body.contains("respondToAndroidPermission"),
        )
        assertTrue(
            "在飞身份要随 saved state 带走（Activity 重建的失效兜底）:\n$body",
            body.contains("outState.putLong(KEY_PERMISSION_IN_FLIGHT_ID, it.inFlightPromptId)"),
        )
        assertTrue(
            "设置页对话框（OPEN）也要带身份:\n$body",
            body.contains("gateId, OffloadPermissionManager.SettingsGateDecision.OPEN"),
        )
        assertTrue(
            "设置页对话框（CANCEL）也要带身份:\n$body",
            body.contains("gateId, OffloadPermissionManager.SettingsGateDecision.CANCEL"),
        )
        assertNoAnonymousAnswer(body)
    }

    @Test
    fun `ChatScreen no longer drives or answers the system dialog`() {
        val body = src("ui/chat/ChatScreen.kt")
        assertFalse(
            "聊天页那份 launcher 会和 Activity 那份为同一张提示各弹一次:\n$body",
            body.contains("RequestMultiplePermissions") ||
                body.contains("respondToAndroidPermission") ||
                body.contains("pendingAndroidPermission"),
        )
    }

    @Test
    fun `the bridge is the single place that launches and answers the system dialog`() {
        val bridge = src("offload/AndroidPermissionDialogBridge.kt")
        assertEquals(
            "系统对话框的注册只能有一处:\n$bridge",
            1,
            Regex("ActivityResultContracts\\.RequestMultiplePermissions\\(\\)").findAll(bridge).count(),
        )
        assertTrue("弹框只能由账本派发（一次一个在飞）:\n$bridge", bridge.contains("ledger.submit(pending)"))
        assertTrue("回调按在飞那次的身份回传:\n$bridge", bridge.contains("ledger.onResult {"))
        assertTrue(
            "应答要带身份交给金库:\n$bridge",
            bridge.contains("deliver = { id, result -> OffloadPermissionManager.respondToAndroidPermission(id, result) }"),
        )
        assertTrue(
            "判定口径统一为 all:\n$bridge",
            bridge.contains("AndroidPermissionOutcome.allGranted(request.permissions, results)"),
        )
        assertFalse(
            "any 判据（多权限里只要有一个已授予就回 GRANTED）不能复活:\n$bridge",
            Regex("permissions\\.any \\{ granted").containsMatchIn(bridge),
        )
        for (rel in listOf("MainActivity.kt", "ui/chat/ChatScreen.kt")) {
            assertFalse(
                "$rel 不该再注册系统权限对话框（唯一驱动方是 bridge）",
                src(rel).contains("RequestMultiplePermissions"),
            )
        }
        assertNoAnonymousAnswer(bridge)
    }

    @Test
    fun `the single-flight slot and the queue both validate the identity`() {
        val slots = src("offload/OffloadPromptSlots.kt")
        val single = slots.substringAfter("internal class SingleFlightSlot<")
        assertTrue(
            "单槽的 respond 必须收身份:\n$single",
            single.contains("fun respond(requestId: Long, answer: A): Boolean"),
        )
        assertTrue(
            "身份不匹配即丢弃（不能投给接替者）:\n$single",
            single.contains("current == null || activeId != requestId"),
        )
        assertTrue(
            "单槽也要把在屏身份发给 UI（否则 UI 无从回传）:\n$single",
            single.contains("_pending.value = PendingPrompt(id, request)"),
        )
        val manager = src("offload/OffloadPermissionManager.kt")
        assertTrue(
            "两个槽的 UI 出口都要把身份透传下去:\n$manager",
            manager.contains("androidPermissionSlot.respond(requestId, result)") &&
                manager.contains("settingsGateSlot.respond(requestId, decision)"),
        )
    }

    private fun assertNoAnonymousAnswer(body: String) {
        val hit = anonymousAnswer.find(body)?.value
        assertFalse("不带身份的应答调用不能复活: $hit", anonymousAnswer.containsMatchIn(body))
    }
}
