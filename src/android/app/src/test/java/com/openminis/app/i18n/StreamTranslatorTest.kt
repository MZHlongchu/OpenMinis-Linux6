package com.openminis.app.i18n

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-offline-failure-breaker] 离线翻译失败预算的回归测试。
 *
 * 回归：旧实现里**任何一次**非取消异常都直接 [MlKitTranslationEngine.markUnavailable]——
 * 一句瞬时失败就把整会话的离线翻译关掉，而 `available` 再也翻不回来（离线路径被跳过，
 * 就没有下一次成功去自愈）。现在按**连续失败**计数：到阈值才关断，任何一次成功清零。
 */
class StreamTranslatorTest {

    @Test
    fun `a single failure does not trip the breaker`() {
        val breaker = OfflineFailureBreaker(threshold = 3)
        assertFalse("第 1 句失败不该关断", breaker.onFailure())
        assertFalse("第 2 句失败不该关断", breaker.onFailure())
        assertTrue("连续第 3 句失败才关断", breaker.onFailure())
    }

    @Test
    fun `a success clears the consecutive failure budget`() {
        val breaker = OfflineFailureBreaker(threshold = 3)
        assertFalse(breaker.onFailure())
        assertFalse(breaker.onFailure())
        breaker.onSuccess() // 成功一次 = 自愈（预算清零）
        assertFalse(breaker.onFailure())
        assertFalse(breaker.onFailure())
        assertTrue("清零后要重新攒满阈值", breaker.onFailure())
    }

    @Test
    fun `the default budget is three consecutive failures`() {
        assertEquals(3, OfflineFailureBreaker.DEFAULT_THRESHOLD)
    }

    /**
     * 端到端接线（[StreamTranslator] + 引擎）。full 变体里未命中缓存的句子会走真实
     * 推理，JVM 单测必然失败（MlKitContext 未初始化 → IllegalStateException，javap
     * 核实过 `Translation.getClient` → `MlKitContext.getInstance`）；slim 变体的存根
     * 恒返回空串，走同一条静默路径、不产生失败（语义保留）。
     *
     * 判据一（与用例顺序无关）：两句失败句恰好消耗 2 点预算——这里再记 1 次就到阈值，
     * 说明 feed 的失败确实计入了**会话级**预算，而不是「一次即关断」。
     * 判据二：单句失败不改可用性；连续 3 句失败才把引擎翻成不可用（AUTO 档回落模型；
     * JVM 里没有把 available 写回 true 的生产路径，故这条只在它初值为 true 时更强）。
     */
    @Test
    fun `failing sentences consume the session budget instead of flipping availability at once`() = runBlocking {
        StreamTranslator.failures.onSuccess()
        val availableBefore = MlKitTranslationEngine.available
        val translator = StreamTranslator(MlKitTranslationEngine, "en", "ja")

        assertEquals("失败句静默丢弃（不抛）", "", translator.feed("First sentence is not cached.\n"))
        assertEquals("单句失败不改可用性", availableBefore, MlKitTranslationEngine.available)
        assertEquals("", translator.feed("Second sentence is not cached.\n"))
        if (MlKitTranslationEngine.bundledEngine) {
            assertTrue("两次失败已计入预算（第 3 次即到阈值）", StreamTranslator.failures.onFailure())
        }

        assertEquals("", translator.feed("Third sentence is not cached.\n"))
        if (MlKitTranslationEngine.bundledEngine) {
            assertFalse("连续 3 句失败 ⇒ 关断（AUTO 档此时回落用户模型）", MlKitTranslationEngine.available)
        }
        StreamTranslator.failures.onSuccess() // 复位预算，别把阈值状态留给别的用例
    }
}
