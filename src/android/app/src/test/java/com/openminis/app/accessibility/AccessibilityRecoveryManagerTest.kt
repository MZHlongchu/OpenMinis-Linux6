package com.openminis.app.accessibility

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-a11y-force-stop-recovery] Grant-parsing and timeout contract.
 *
 * [AccessibilityRecoveryManager.isRevokedIn] is deliberately pure so the
 * ENABLED_ACCESSIBILITY_SERVICES parsing rules can be exercised on the JVM —
 * there is no Robolectric in this module, so anything touching Context or
 * Settings.Secure is untestable here. The parsing is the part with real risk:
 * a false "revoked" reading puts a repair dialog in front of the user on every
 * single a11y call.
 */
class AccessibilityRecoveryManagerTest {

    private val PKG = "com.openminis.app"
    private val CLS = "com.openminis.app.accessibility.MinisAccessibilityService"

    private fun revoked(value: String?) =
        AccessibilityRecoveryManager.isRevokedIn(value, PKG, CLS)

    // ── The force-stop signature ─────────────────────────────────────────

    @Test
    fun `null value reads as revoked`() {
        // Exactly what force-stop leaves behind (verified on Pixel 4a / A13).
        assertTrue(revoked(null))
    }

    @Test
    fun `empty value reads as revoked`() {
        assertTrue(revoked(""))
        assertTrue(revoked("   "))
    }

    // ── Healthy grants must NOT read as revoked ──────────────────────────

    @Test
    fun `fully qualified entry is recognized`() {
        assertFalse(revoked("$PKG/$CLS"))
    }

    @Test
    fun `abbreviated entry is recognized`() {
        // The framework normalizes to this form when it rewrites the value.
        // This is the regression that would otherwise loop the repair prompt.
        assertFalse(revoked("$PKG/.accessibility.MinisAccessibilityService"))
    }

    @Test
    fun `case differences are tolerated`() {
        assertFalse(revoked("$PKG/$CLS".uppercase()))
    }

    @Test
    fun `entry surrounded by whitespace is recognized`() {
        assertFalse(revoked("  $PKG/$CLS  "))
    }

    // ── Coexistence with other services ──────────────────────────────────

    @Test
    fun `ours found among several enabled services`() {
        val talkback = "com.google.android.marvin.talkback/.TalkBackService"
        val bitwarden = "com.x8bit.bitwarden/com.x8bit.bitwarden.Accessibility.AccessibilityService"
        assertFalse(revoked("$talkback:$PKG/$CLS:$bitwarden"))
    }

    @Test
    fun `other services present but ours absent reads as revoked`() {
        // The exact post-force-stop state observed when two services were
        // enabled and only Minis was stopped: the framework surgically removed
        // ours and kept TalkBack.
        val talkback = "com.google.android.marvin.talkback/.TalkBackService"
        assertTrue(revoked(talkback))
    }

    @Test
    fun `a different package with a same-named class does not match`() {
        assertTrue(revoked("com.other.app/com.openminis.app.accessibility.MinisAccessibilityService"))
    }

    @Test
    fun `our package with a different service class does not match`() {
        assertTrue(revoked("$PKG/$PKG.accessibility.SomeOtherService"))
    }

    // ── Timeout contract ─────────────────────────────────────────────────

    @Test
    fun `prompt timeout is 60 seconds`() {
        // Spec'd value. Asserted so a casual edit to the constant trips a test
        // rather than silently changing how long an agent turn can stall.
        assertEquals(60_000L, AccessibilityRecoveryManager.PROMPT_TIMEOUT_MS)
    }

    // ── Idempotence of the UI callback ───────────────────────────────────

    @Test
    fun `respond without a pending prompt is a no-op`() {
        // The dialog host can fire respond() after a timeout already resolved
        // the continuation (button tap racing the 60s expiry). That must not
        // throw or resume a dead continuation.
        AccessibilityRecoveryManager.respond(AccessibilityRecoveryManager.Decision.CANCEL)
        AccessibilityRecoveryManager.respond(AccessibilityRecoveryManager.Decision.REPAIR)
        assertEquals(null, AccessibilityRecoveryManager.pendingPrompt.value)
    }

    // ── [T-a11y-prompt-single-flight] 并发调用共享同一次询问 ──────────────
    //
    // 并发入口真实存在：MCP 服务端 4 线程池并发处理 shell_exec / ui_action、
    // 并行子代理各跑一条 a11y 命令。旧实现是单槽 Continuation：后到者覆盖前者，
    // 且 invokeOnCancellation 无条件清空整个槽——先到者超时会把后到者的注册一起
    // 抹掉，用户点了"修复"也送不回去。

    private val repairDecision = AccessibilityRecoveryManager.Decision.REPAIR

    private fun prompt(shizuku: Boolean) =
        AccessibilityRecoveryManager.RepairPrompt(shizukuAvailable = shizuku)

    @Test
    fun `parallel callers share one prompt and one decision`() = runBlocking {
        // UNDISPATCHED：body 跑到第一个挂起点（对话框挂上）才交还控制权，
        // 于是断言不依赖 sleep 时序。
        val owner = async(start = CoroutineStart.UNDISPATCHED) {
            AccessibilityRecoveryManager.awaitDecision(prompt(shizuku = true))
        }
        assertEquals(true, AccessibilityRecoveryManager.pendingPrompt.value?.shizukuAvailable)

        // 后到者带 shizukuAvailable=false：如果它自己抢槽弹了第二个对话框，
        // StateFlow 的值就会变——这正是"覆盖前者"的旧行为。
        val joiner = async(start = CoroutineStart.UNDISPATCHED) {
            AccessibilityRecoveryManager.awaitDecision(prompt(shizuku = false))
        }
        assertEquals(true, AccessibilityRecoveryManager.pendingPrompt.value?.shizukuAvailable)

        AccessibilityRecoveryManager.respond(repairDecision)
        // 两个调用者拿到同一个决定（旧实现里后到者的槽被清掉，只能等满 60s 拿 CANCEL）。
        assertEquals(repairDecision, owner.await())
        assertEquals(repairDecision, joiner.await())
        assertEquals(null, AccessibilityRecoveryManager.pendingPrompt.value)
    }

    @Test
    fun `cancelling one caller keeps the shared prompt alive`() = runBlocking {
        val owner = async(start = CoroutineStart.UNDISPATCHED) {
            AccessibilityRecoveryManager.awaitDecision(prompt(shizuku = true))
        }
        val joiner = async(start = CoroutineStart.UNDISPATCHED) {
            AccessibilityRecoveryManager.awaitDecision(prompt(shizuku = true))
        }
        // 取消后到者：旧实现里它的 invokeOnCancellation 会无条件把对话框抹掉，
        // 用户的点击落空、owner 还要再等满 60s。
        joiner.cancel()
        joiner.join()
        assertTrue(AccessibilityRecoveryManager.pendingPrompt.value != null)

        AccessibilityRecoveryManager.respond(repairDecision)
        assertEquals(repairDecision, owner.await())
        assertEquals(null, AccessibilityRecoveryManager.pendingPrompt.value)
    }
}
