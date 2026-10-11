package com.openminis.app.sandbox.offload

import com.openminis.app.offload.PromptDecision
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-d10-prompt-slot] `minis-sessions-cli` 跨会话门控的两条契约：
 *
 *  1. 提示槽是**全局单槽**（`OffloadPromptQueue`），在屏那一张只由调用方自己的预算让它
 *     退位；在屏工具（`SessionLookupTool` → `SessionAccessPolicy.isCrossSessionGranted`）
 *     没有预算。于是本调用方可能"排进 FIFO 却从未上屏"——这种失败**不能**报成
 *     "15s 内没人应答"（用户根本没看过这张提示，agent 会据此反复重试）。判据是
 *     [CrossSessionPromptSlot.outcome]，由 `crossSessionDenial` 里的在屏槽观测驱动。
 *  2. 两种失败都必须 fail-closed：exit 124 / `interaction_required`，只有明确的
 *     ALLOWED 才放行；文案要能区分"上过屏没人点"与"没上过屏"。
 *
 * 本模块没有 Robolectric（同 `SessionsOffloadInteractionBudgetTest` 的说明），而
 * `SessionsOffloadHandler` 要 Room 的 ChatRepository 才能实例化，所以：
 *  - 判定与结果契约直接对可执行的 [CrossSessionPromptSlot] /
 *    [SessionsOffloadHandler.slotBusyResult] 断言；
 *  - "在屏槽被观测、且提示调用只在预算内有一处"这条按仓库惯例对源码断言。
 */
class SessionsOffloadPromptSlotTest {

    private val handlerSource =
        File("src/main/java/com/openminis/app/sandbox/offload/SessionsOffloadHandler.kt").readText()

    @Test
    fun `a prompt that never reached the screen is not classified as unanswered`() {
        // 槽被别的提示占着 / 队列满 / 排队超时：我方提示没上屏 —— 没有预算期间的"没人应答"。
        assertEquals(
            CrossSessionPromptSlot.Outcome.DROPPED_WITHOUT_SCREEN,
            CrossSessionPromptSlot.outcome(null, sawOurPromptOnScreen = false),
        )
        assertEquals(
            CrossSessionPromptSlot.Outcome.DROPPED_WITHOUT_SCREEN,
            CrossSessionPromptSlot.outcome(PromptDecision.UNANSWERED, sawOurPromptOnScreen = false),
        )
        // 上过屏但预算内没人点：这才是"没人应答"。
        assertEquals(
            CrossSessionPromptSlot.Outcome.SHOWN_THEN_UNANSWERED,
            CrossSessionPromptSlot.outcome(null, sawOurPromptOnScreen = true),
        )
        // 明确答复（拒绝/允许）不是"没人应答"，文案由调用方按 isAllowed 决定。
        assertEquals(
            CrossSessionPromptSlot.Outcome.DECIDED,
            CrossSessionPromptSlot.outcome(PromptDecision.DENIED, sawOurPromptOnScreen = true),
        )
        assertEquals(
            CrossSessionPromptSlot.Outcome.DECIDED,
            CrossSessionPromptSlot.outcome(PromptDecision.ALLOWED, sawOurPromptOnScreen = false),
        )
    }

    @Test
    fun `the blocked-slot envelope keeps the fail-closed contract with the honest reason`() {
        val args = OffloadArgs(listOf("search", "--keywords", "budget"))
        val busy = SessionsOffloadHandler.slotBusyResult("search", args)

        assertEquals(124, busy.exitCode)
        val busyBody = JSONObject(busy.output.trim())
        assertEquals("minis-sessions-cli", busyBody.getString("tool"))
        assertEquals("search", busyBody.getString("action"))
        assertFalse(busyBody.getBoolean("ok"))
        val busyData = busyBody.getJSONObject("data")
        assertEquals("interaction_required", busyData.getString("error"))
        val busyMessage = busyData.getString("message")
        assertTrue(busyMessage, busyMessage.contains("never put on screen"))
        assertTrue("文案要给出下一步：$busyMessage", busyMessage.contains("retry"))
        assertFalse(
            "槽被占着 ≠ 用户没应答，不能把饥饿说成用户没点：$busyMessage",
            busyMessage.contains("Nobody answered"),
        )

        // 对照：预算耗尽（提示上过屏）那条文案保留原样，测试仍靠它区分两种失败。
        val shownMessage = JSONObject(
            SessionsOffloadHandler.interactionRequiredResult("search", args).output.trim(),
        ).getJSONObject("data").getString("message")
        assertTrue(shownMessage, shownMessage.contains("Nobody answered"))
        assertTrue(shownMessage, shownMessage.contains("15s"))
    }

    @Test
    fun `the handler observes the on-screen slot and branches on the outcome`() {
        val body = handlerSource
            .substringAfter("private suspend fun crossSessionDenial(")
            .substringBefore("private fun cmdList(")
        val observe = body.indexOf("OffloadPermissionManager.pendingRequest")
        val budget = body.indexOf("withTimeoutOrNull(OffloadPermissionManager.INTERACTIVE_BUDGET_MS)")
        val prompt = body.indexOf("OffloadPermissionManager.checkPermission(")

        assertTrue(
            "必须观测在屏槽，否则分不清'提示没上屏'与'没人应答':\n$body",
            observe >= 0,
        )
        assertTrue("跨会话门控没有预算包装:\n$body", budget >= 0)
        assertTrue("checkPermission 不在预算内:\n$body", prompt > budget)
        assertTrue(
            "预算内的提示调用只能有一处（同一文件里不允许出现第二个无预算的 prompt 路径）:\n$body",
            body.indexOf("OffloadPermissionManager.checkPermission(", prompt + 1) < 0,
        )
        assertTrue("必须按判定分流:\n$body", body.contains("CrossSessionPromptSlot.outcome("))
        assertTrue("没上屏的分支必须走 slotBusyResult:\n$body", body.contains("slotBusyResult("))
        assertTrue("上屏未答的分支必须保留 interactionRequiredResult:\n$body", body.contains("interactionRequiredResult("))
        assertTrue("两种失败都算'没人应答'写审计:\n$body", body.contains("promptUnanswered = true"))
    }

    @Test
    fun `the whole file has exactly one prompt call site`() {
        val calls = Regex("OffloadPermissionManager\\.checkPermission\\(").findAll(handlerSource).count()
        assertEquals(
            "本文件只允许一处 session_read 询问入口；新增的第二处极易漏掉预算或槽观测",
            1,
            calls,
        )
    }
}
