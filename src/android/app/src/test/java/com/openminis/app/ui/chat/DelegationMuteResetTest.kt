package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-mute-reset-on-drain] Port of iOS f1f4f23ba sub-issue 3: every
 * way a new turn starts lifts a sub-agent stop's delegation mute, not only
 * the public sendMessage.
 */
class DelegationMuteResetTest {

    private val vm by lazy { ProductionSources.readPackageSurface("ui/chat") }
    private val clear = "AgentJobRegistry.clearDelegationMute(activeSessionId)"

    /**
     * [T-a7-vm-split] 本移植把 ChatViewModel 拆成了一整个 `ui/chat` 包的分片，成员
     * 多写成扩展函数（`fun ChatViewModel.retryLast()`），而且上游那套精确签名前缀
     * （`private suspend fun drainQueuedPrompts(`）在这里连签名都找不到。原断言的
     * 意图——每一种"新回合"的入口都必须先解除委派静音——一律保留：下面按**函数声明
     * 正则**在整个包面里定位，从声明处起取 [chars] 个字符作为窗口，窗口语义与上游
     * 相同（"该函数开头的一小段"）。
     */
    private fun head(pattern: Regex, chars: Int = 1500): String {
        val m = pattern.find(vm)
        assertTrue("missing ${pattern.pattern}", m != null)
        val i = m!!.range.first
        return vm.substring(i, minOf(vm.length, i + chars))
    }

    /** 本移植里这些入口的真实声明（见下面对应用例的注释）。 */
    private val drainFn = Regex("""fun\s+ChatViewModel\.drainQueuedPrompts\s*\(""")
    private val injectFn = Regex("""fun\s+ChatViewModel\.injectQueuedPromptsAsNewTurn\s*\(""")

    @Test fun `each drained batch starts unmuted`() {
        // [T-a7-vm-split] 取批点是 materializeQueuedPromptsAsUserTurn()（上游是
        // QueuedPromptBatching.nextDrainBatch）——两者都是"这一批从这里开始"；
        // 循环头是本移植的搁置态条件（[T-queue-shelved]），故显式断言它存在，
        // 而不是让 substringAfter 静默退化成整段窗口。
        val body = head(drainFn)
        val loopHeader = "while (_promptQueue.value.any { !it.shelved }) {"
        assertTrue("missing $loopHeader", body.contains(loopHeader))
        val loop = body.substringAfter(loopHeader)
        val cut = Regex("materializeQueuedPromptsAsUserTurn|nextDrainBatch").find(loop)?.range?.first ?: -1
        assertTrue("drain must take a batch", cut >= 0)
        assertTrue("每一批 drain 之前必须先解除委派静音", loop.substring(0, cut).contains(clear))
    }

    @Test fun `an in-loop injected prompt starts unmuted`() {
        assertTrue(head(injectFn).contains(clear))
    }

    @Test fun `retry, resume, retry-from-message and headless submits start unmuted`() {
        // [T-a7-vm-split] 四个入口在本移植里的真实声明，逐个显式锚定（不按"名字首次
        // 出现"撞运气：`sendMessage` 在本包里有第二个声明 —— 表达式体的公开 override
        // `override fun sendMessage(text: String) = sendMessage(…)`，它没有函数体，
        // 静音重置在它委派的漏斗里）：
        //  - retryLast / resume / sendMessage 漏斗：扩展函数 `fun ChatViewModel.xxx(`
        //  - retryFromMessage：不带接收者的 override（retry 的另一种入口）
        // headless 的 submitPrompt 也走 sendMessage 漏斗，覆盖面对齐上游、只多不少。
        val signatures = listOf(
            Regex("""fun\s+ChatViewModel\.retryLast\s*\("""),
            Regex("""fun\s+ChatViewModel\.resume\s*\("""),
            Regex("""fun\s+retryFromMessage\s*\("""),
            Regex("""fun\s+ChatViewModel\.sendMessage\s*\("""),
        )
        for (signature in signatures) {
            assertTrue(signature.pattern, head(signature, 900).contains(clear))
        }
    }
}
