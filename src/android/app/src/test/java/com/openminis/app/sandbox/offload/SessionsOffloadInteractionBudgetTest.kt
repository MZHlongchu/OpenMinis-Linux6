package com.openminis.app.sandbox.offload

import com.openminis.app.sandbox.SessionAccessAudit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [T-android-offload-hang] `minis-sessions-cli` 跨会话门控的两条契约：
 *
 *  1. 授权询问必须带 `INTERACTIVE_BUDGET_MS` 预算——否则没人回答时，offload
 *     worker 线程会永久停在 `runBlocking` 里等一个不会到来的 resume，而调用方
 *     20s 后已经拿到 `handler_timeout` 走人；
 *  2. 预算耗尽要和「用户拒绝」在结果与审计里都区分开：前者是
 *     `interaction_required` / exit 124（重试前先让人批准），后者才是
 *     `PERMISSION_DENIED` / exit 126。
 *
 * 本模块没有 Robolectric（同 SessionSearchQueryShapeTest 的说明），而
 * `SessionsOffloadHandler` 要 Room 的 ChatRepository 才能实例化，所以：
 *  - 结果契约直接对可执行的 [SessionsOffloadHandler.interactionRequiredResult] 断言；
 *  - 「预算确实套在 checkPermission 上」这条按仓库惯例对源码断言。
 */
class SessionsOffloadInteractionBudgetTest {

    private val handlerSource =
        File("src/main/java/com/openminis/app/sandbox/offload/SessionsOffloadHandler.kt").readText()

    private val auditDir: File = Files.createTempDirectory("session-access-audit").toFile()

    @After
    fun tearDown() {
        SessionAccessAudit.dir = null
        auditDir.deleteRecursively()
    }

    @Test
    fun `expired prompt answers interaction_required with exit 124`() {
        val args = OffloadArgs(listOf("messages", "--id", "other-session"))
        val result = SessionsOffloadHandler.interactionRequiredResult("messages", args)

        assertEquals(124, result.exitCode)
        val body = JSONObject(result.output.trim())
        assertEquals("minis-sessions-cli", body.getString("tool"))
        assertEquals("messages", body.getString("action"))
        assertFalse(body.getBoolean("ok"))
        val data = body.getJSONObject("data")
        assertEquals("interaction_required", data.getString("error"))
        // 不是策略拒绝：文案必须让 agent 去请用户批准后重试，而不是去改设置。
        val message = data.getString("message")
        assertTrue(message, message.contains("15s"))
        assertTrue(message, message.contains("retry"))
    }

    @Test
    fun `cross-session grant check is wrapped in the interactive budget`() {
        val body = handlerSource
            .substringAfter("private suspend fun crossSessionDenial(")
            .substringBefore("private fun cmdList(")
        val budget = body.indexOf("withTimeoutOrNull(OffloadPermissionManager.INTERACTIVE_BUDGET_MS)")
        val prompt = body.indexOf("OffloadPermissionManager.checkPermission(")
        assertTrue("跨会话门控没有预算包装:\n$body", budget >= 0)
        assertTrue("checkPermission 不在预算内:\n$body", prompt > budget)
        assertTrue("预算耗尽的分支缺失:\n$body", body.contains("interactionRequiredResult("))
        assertTrue("预算耗尽没有写审计:\n$body", body.contains("promptUnanswered = true"))
    }

    @Test
    fun `an unanswered prompt is distinguishable in the audit trail`() {
        SessionAccessAudit.dir = auditDir
        SessionAccessAudit.record(
            "caller-1", "messages", "other-session", false, false, promptUnanswered = true,
        )
        val line = File(auditDir, "session-access.log").readText()
        assertTrue(line, line.contains("allowed=false grant=false prompt_unanswered=true"))
    }

    @Test
    fun `a plain denial keeps the previous audit line shape`() {
        SessionAccessAudit.dir = auditDir
        SessionAccessAudit.record("caller-1", "messages", "other-session", false, false)
        val line = File(auditDir, "session-access.log").readText()
        assertTrue(line, line.contains("allowed=false grant=false"))
        assertFalse("普通拒绝不该带未回答标记: $line", line.contains("prompt_unanswered"))
    }
}
