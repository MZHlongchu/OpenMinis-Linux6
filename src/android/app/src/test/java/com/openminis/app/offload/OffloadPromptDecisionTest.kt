package com.openminis.app.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-android-prompt-identity] ASK_ONCE 询问的三值结果，以及它在审计 / 错误码上的落点。
 *
 * 背景：`checkPermission` 以前返回 `Boolean`，`promptQueue.await` 拿不到应答时
 * `?: return false` —— 「排队超时 / 队列已满，提示根本没上屏」于是和「用户在对话框里
 * 点了拒绝」变成同一个 `false`。于是：
 *  - `SessionsOffloadHandler` 记的审计行读起来像"用户说了不"，CLI 回 PERMISSION_DENIED/126；
 *  - `OffloadGate.enforce` 让 agent 去改设置，而不是去请用户批准。
 * 这三值类型把那两种情况分开。
 *
 * [PromptDecision] 本身是纯 JVM 的，可以直接断言；而 `checkPermission` /
 * `crossSessionDenial` 要 `SharedPreferences` / Room 才能跑（本模块没有
 * Robolectric），所以"谁在哪一分支上用了哪一值"按仓库惯例对源码断言
 * （同 `SessionsOffloadInteractionBudgetTest`）。
 */
class OffloadPromptDecisionTest {

    private fun src(rel: String) = File("src/main/java/com/openminis/app/$rel").readText()

    @Test
    fun `only an explicit allow grants access`() {
        assertTrue(PromptDecision.ALLOWED.isAllowed)
        assertFalse(PromptDecision.DENIED.isAllowed)
        assertFalse(PromptDecision.UNANSWERED.isAllowed)
        // 调用方自己的预算耗尽用 null 表达：一样不能放行。
        assertFalse((null as PromptDecision?)?.isAllowed == true)
    }

    @Test
    fun `a dropped prompt is unanswered, not denied`() {
        assertTrue(PromptDecision.UNANSWERED.isUnanswered)
        assertFalse(PromptDecision.DENIED.isUnanswered)
        assertFalse(PromptDecision.ALLOWED.isUnanswered)
        assertNotEquals(PromptDecision.UNANSWERED, PromptDecision.DENIED)
    }

    @Test
    fun `checkPermission reports a dropped prompt as unanswered`() {
        val body = src("offload/OffloadPermissionManager.kt")
        val noAnswer = body
            .substringAfter("val response = promptQueue.await(")
            .substringBefore("when (response)")
        assertTrue(
            "排队超时/队列满必须返回 UNANSWERED（旧实现 `?: return false` 把它折成了拒绝）:\n$noAnswer",
            noAnswer.contains("?: return PromptDecision.UNANSWERED"),
        )
        val respond = body.substringAfter("fun respondToRequest(")
        assertTrue(
            "应答必须把身份交给队列校验，不能匿名投递:\n$respond",
            respond.contains("promptQueue.respond(requestId, response)"),
        )
    }

    @Test
    fun `the sessions CLI separates an unanswered prompt from a policy denial`() {
        val body = src("sandbox/offload/SessionsOffloadHandler.kt")
            .substringAfter("private suspend fun crossSessionDenial(")
            .substringBefore("private fun cmdList(")
        val unanswered = body
            .substringAfter("if (decision == null || decision.isUnanswered)")
            .substringBefore("val granted")
        assertTrue("无人应答要标记审计:\n$unanswered", unanswered.contains("promptUnanswered = true"))
        assertTrue("无人应答走 124:\n$unanswered", unanswered.contains("interactionRequiredResult("))
        assertFalse(
            "无人应答不是策略拒绝，不该报 PERMISSION_DENIED:\n$unanswered",
            unanswered.contains("PERMISSION_DENIED"),
        )
        val denied = body.substringAfter("if (granted) return null")
        assertTrue("策略拒绝才报 PERMISSION_DENIED:\n$denied", denied.contains("\"PERMISSION_DENIED\""))
        assertTrue("策略拒绝用 126:\n$denied", denied.contains("EXIT_PERMISSION_DENIED"))
    }

    @Test
    fun `the offload gate reports interaction_required when nobody answered`() {
        val enforce = src("sandbox/offload/OffloadGate.kt").substringAfter("fun enforce(")
        val denied = enforce
            .substringAfter("PromptDecision.DENIED ->")
            .substringBefore("null, PromptDecision.UNANSWERED ->")
        assertTrue("DENIED 才报 permission_denied:\n$denied", denied.contains("\"permission_denied\""))
        val unanswered = enforce.substringAfter("null, PromptDecision.UNANSWERED ->")
        assertTrue(
            "UNANSWERED（提示被队列丢弃）要和预算超时同走 interaction_required:\n$unanswered",
            unanswered.contains("\"interaction_required\""),
        )
        assertFalse(
            "UNANSWERED 不该报 permission_denied:\n$unanswered",
            unanswered.contains("\"permission_denied\""),
        )
    }

    @Test
    fun `the dialog always answers with the prompt identity`() {
        val body = src("ui/chat/OffloadPermissionDialog.kt")
        assertTrue(
            "渲染与应答必须取自同一个快照:\n$body",
            body.contains("val prompt = pending ?: return"),
        )
        val calls = Regex("respondToRequest\\(").findAll(body).count()
        val withId = Regex("respondToRequest\\(\\s*prompt\\.id,").findAll(body).count()
        assertTrue("四处应答（三键 + 点外关闭）都要带身份，实际 $calls 处", calls >= 4)
        assertEquals("每一处应答都要回传 prompt.id", calls, withId)
        assertFalse(
            "不带身份的应答（旧写法）不能复活:\n$body",
            Regex("respondToRequest\\(\\s*OffloadPermissionManager\\.Response\\.").containsMatchIn(body),
        )
    }
}
