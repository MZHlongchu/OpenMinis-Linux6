package com.openminis.app.tools

import com.openminis.app.offload.PromptDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-android-prompt-identity] 「没人应答 ≠ 策略拒绝」在在屏工具这一侧的收口：
 * `SessionAccessPolicy` 的三值入口 + `SessionLookupTool` 的文案 / 审计 / 降级原因。
 *
 * 判据与 `SessionsOffloadHandler` 的 `prompt_unanswered` 先例同源（见 [PromptDecision]）：
 * 排队超时或队列满时提示**压根没上屏**，那不是用户点了拒绝，agent 该做的是请用户批准，
 * 而不是去改设置。
 *
 * `executeRead` / `executeSearch` 要 Room 的 `ChatRepository` 才能跑（本模块没有
 * Robolectric），所以纯文案抽成了可直接断言的函数，"谁在哪一分支上用了哪一值"按仓库惯例
 * 对源码断言（同 `OffloadPromptDecisionTest`）。
 */
class SessionLookupToolTest {

    private fun src(rel: String) = File("src/main/java/com/openminis/app/$rel").readText()

    @Test
    fun splitQueryDropsShortTokens() {
        assertEquals(listOf("backup", "WebDAV"), SessionLookupTool.splitQuery("  a backup WebDAV  x "))
    }

    @Test
    fun makeAgentToolsExposesSessionLookup() {
        val names = AgentTools.makeAgentTools().map { it.name }
        assertTrue(names.contains(SessionLookupTool.SEARCH))
        assertTrue(names.contains(SessionLookupTool.READ))
    }

    @Test
    fun `a prompt nobody answered is not reported as a policy denial`() {
        val unanswered = SessionLookupTool.foreignReadRefusal("other-session", PromptDecision.UNANSWERED)
        assertTrue("要说清是「没人应答」: $unanswered", unanswered.contains("nobody answered"))
        assertTrue("要给出下一步: $unanswered", unanswered.contains("retry"))
        assertFalse(
            "「没人应答」不能说成「会话属于别人、需要授权」（那是策略拒绝的文案）: $unanswered",
            unanswered.contains("belongs to another chat"),
        )

        val denied = SessionLookupTool.foreignReadRefusal("other-session", PromptDecision.DENIED)
        assertTrue("策略拒绝才指向设置页: $denied", denied.contains("belongs to another chat"))
        assertTrue("策略拒绝要给出设置入口: $denied", denied.contains("Read other chats"))
        assertNotEquals(unanswered, denied)
    }

    @Test
    fun `the search scope reason separates an unanswered prompt from a denial`() {
        assertNull(
            "已授权时 JSON 形状与原来一模一样",
            SessionLookupTool.searchScopeReason(PromptDecision.ALLOWED),
        )
        assertEquals("prompt_unanswered", SessionLookupTool.searchScopeReason(PromptDecision.UNANSWERED))
        assertEquals("permission_denied", SessionLookupTool.searchScopeReason(PromptDecision.DENIED))
    }

    @Test
    fun `the tool branches on the tri-state decision instead of a boolean`() {
        val body = src("tools/SessionLookupTool.kt")
        assertTrue(
            "两处门控都要走三值入口:\n$body",
            body.contains("SessionAccessPolicy.crossSessionDecision("),
        )
        assertFalse(
            "旧的布尔入口分不出「没人应答」，不能再用:\n$body",
            body.contains("isCrossSessionGranted("),
        )
        assertEquals(
            "search / read 两处审计都要把「没人应答」标出来",
            2,
            Regex("promptUnanswered = decision\\.isUnanswered").findAll(body).count(),
        )
        assertTrue(
            "读别的会话被拦下时按判定分流文案:\n$body",
            body.contains("foreignReadRefusal(sessionId, decision)"),
        )
        assertEquals(
            "两处降级都要把原因回给模型",
            2,
            Regex("\\.putOpt\\(\"scope_reason\", scopeReason\\)").findAll(body).count(),
        )
    }

    @Test
    fun `the policy keeps the boolean API as a strict view of the tri-state one`() {
        val policy = src("sandbox/SessionAccessPolicy.kt")
        assertTrue(
            "三值入口:\n$policy",
            policy.contains("suspend fun crossSessionDecision(caller: String): PromptDecision"),
        )
        assertTrue(
            "兼容入口（原 API）必须仍在，且只有明确的 ALLOWED 才放行:\n$policy",
            policy.contains("suspend fun isCrossSessionGranted(caller: String): Boolean") &&
                policy.contains("crossSessionDecision(caller).isAllowed"),
        )
    }
}
