package com.openminis.app.i18n

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-mlkit-model-mgmt] 引擎纯逻辑测试：下载进度文本格式化（复用自 taixu
 * TranslationModelStatus.detailText）与 LRU 翻译缓存淘汰。
 */
class MlKitTranslationEngineTest {

    @Test
    fun `detailText byte fields are ignored - honest indeterminate stage only`() {
        val s = MlKitTranslationEngine.PackState.Downloading(
            progress = 0.5f, step = 1, totalSteps = 2,
            downloadedBytes = 12_582_912L, totalBytes = 29_882_912L,
        )
        // [T-pack-download-rework] ML Kit exposes no byte progress; stale byte
        // fields must NOT be rendered - only the stage remains.
        assertEquals("第 1/2 阶段", s.detailText)
    }

    @Test
    fun `detailText progress field is ignored - stage only`() {
        val s = MlKitTranslationEngine.PackState.Downloading(progress = 0.5f, step = 1, totalSteps = 2)
        assertEquals("第 1/2 阶段", s.detailText)
    }

    @Test
    fun `detailText connecting mode renders stage only`() {
        val s = MlKitTranslationEngine.PackState.Downloading(progress = null, step = 2, totalSteps = 2)
        assertEquals("第 2/2 阶段", s.detailText)
    }

    @Test
    fun `translation cache evicts eldest beyond 100 entries`() {
        val cache = MlKitTranslationEngine.translationCache
        cache.clear()
        repeat(101) { i -> cache[i] = "v$i" }
        assertEquals(100, cache.size)
        assertTrue("eldest evicted", !cache.containsKey(0))
        assertTrue("newest kept", cache.containsKey(100))
        cache.clear()
    }

    /**
     * [T-i18n-avail-fallback] 失败路径必须能把引擎翻成不可用：AUTO 档的
     * `streamModelOn()` 判 `!available`，这是「离线失败后回落用户模型」那条链的
     * 入口（旧实现里 available 唯一写入点在从没被调用的 probe 内，于是恒 true）。
     * full 变体初值 true → 这里必须变成 false；slim 存根（available 恒 false）
     * 调同一个 API 也必须是安全的幂等写——两 flavor 共用 src/main 的调用点。
     */
    @Test
    fun `markUnavailable turns the offline engine off`() {
        MlKitTranslationEngine.markUnavailable()
        assertFalse(MlKitTranslationEngine.available)
    }

    /**
     * [T-packstate-pure-read] 下载跑在引擎级 scope、脱离设置页：退出页面再进来
     * 重新读 packState 不得把在飞的 Downloading 冲成 Checking/NeedsDownload
     * （旧实现每次读取都无条件刷新：卡片谎报「未下载」、多给一个只能 join 的
     * 下载按钮）。断言必须在探测协程任意交错下都成立。
     */
    @Test
    fun `re-reading packState keeps an in-flight Downloading state`() = runBlocking {
        // 用可写视图模拟引擎级状态机：packState 对外只给 StateFlow（只读），
        // 而测试要注入「下载在飞」这个内部状态。
        val flow = MlKitTranslationEngine.packState("en", "zh") as MutableStateFlow<MlKitTranslationEngine.PackState>
        flow.value = MlKitTranslationEngine.PackState.Downloading()
        MlKitTranslationEngine.packState("en", "zh") // 设置页重进：同语言对再读
        assertEquals(MlKitTranslationEngine.PackState.Downloading(), flow.value)
        delay(100) // 旧实现会异步写回 Checking/NeedsDownload——留出窗口
        assertEquals(MlKitTranslationEngine.PackState.Downloading(), flow.value)
        // 复位，别把注入态留给其它用例（NeedsDownload 是 data object，无括号）。
        flow.value = MlKitTranslationEngine.PackState.NeedsDownload
    }

    /**
     * [T-cancel-refcheck] 删除前复核之一：**已生效语言对**仍在用的侧不能被别的事务
     * 当半成品清掉。取独立语言码（aa/bb/cc）以免与其它用例的 packStates 相互干扰。
     */
    @Test
    fun `sides of the effective pair are referenced`() {
        MlKitTranslationEngine.markEffectivePair("aa", "bb")
        assertTrue("生效对的源侧在用", MlKitTranslationEngine.codeStillInUse("aa", "aa>cc"))
        assertTrue("生效对的目标侧在用", MlKitTranslationEngine.codeStillInUse("bb", "cc>bb"))
        assertFalse("无关键名不算引用", MlKitTranslationEngine.codeStillInUse("cc", "aa>cc"))
        assertFalse("自己的语言对不算引用", MlKitTranslationEngine.codeStillInUse("aa", "aa>bb"))
    }

    /**
     * [T-cancel-refcheck] 删除前复核之二：**本地已就绪**的语言对同样算在用——手动
     * 下载卡（设置页绕过事务直接 downloadPack）刚下好的侧，不能被别的事务按「下载前
     * 缺失」快照删掉（删了那一对的 flow 会停在 Ready：显示已就绪但实时翻译静默失效）。
     */
    @Test
    fun `sides of a ready pair are referenced`() {
        val flow = MlKitTranslationEngine.packState("cc", "dd") as MutableStateFlow<MlKitTranslationEngine.PackState>
        flow.value = MlKitTranslationEngine.PackState.Ready
        assertTrue(MlKitTranslationEngine.codeStillInUse("cc", "aa>cc"))
        assertTrue(MlKitTranslationEngine.codeStillInUse("dd", "dd>ee"))
        assertFalse("自己那一对不算引用", MlKitTranslationEngine.codeStillInUse("cc", "cc>dd"))
        flow.value = MlKitTranslationEngine.PackState.NeedsDownload
    }

    /**
     * [T-packstate-settle] 空侧清理（本次事务没有新增的侧）也要复位状态机：Failed
     * ("cancelled") 与陈旧 Downloading 都没有别的写入点（refreshStatus 的门禁不再
     * 刷新 Failed），不复位就永久粘住——卡片一直显示「失败: cancelled」+ 多余的下载按钮。
     */
    @Test
    fun `cleanup without sides resets a sticky failed state`() = runBlocking {
        val flow = MlKitTranslationEngine.packState("ee", "ff") as MutableStateFlow<MlKitTranslationEngine.PackState>
        flow.value = MlKitTranslationEngine.PackState.Failed("cancelled")
        MlKitTranslationEngine.deletePackSides("ee", "ff", emptySet())
        assertEquals(MlKitTranslationEngine.PackState.NeedsDownload, flow.value)

        // 陈旧 Downloading（没有在飞任务）同样要复位。
        flow.value = MlKitTranslationEngine.PackState.Downloading()
        MlKitTranslationEngine.deletePackSides("ee", "ff", emptySet())
        assertEquals(MlKitTranslationEngine.PackState.NeedsDownload, flow.value)
    }

    /**
     * [T-cancel-refcheck] 被引用保护的侧**连删除尝试都不发生**：真实语言码（en/ja 能被
     * mlLang 解析）下若复核失效，full 变体会真的走 RemoteModelManager —— JVM 单测没有
     * MlKitContext，必然抛异常。本例能跑通本身就是「复核生效」的判据；状态机同时要复位。
     */
    @Test
    fun `cleanup skips a referenced side and still settles the state`() = runBlocking {
        MlKitTranslationEngine.markEffectivePair("en", "zh")
        val flow = MlKitTranslationEngine.packState("en", "ja") as MutableStateFlow<MlKitTranslationEngine.PackState>
        flow.value = MlKitTranslationEngine.PackState.Failed("cancelled")
        // en 被已生效语言对 en→zh 引用 ⇒ 跳过删除（full 下不碰 GMS）。
        MlKitTranslationEngine.deletePackSides("en", "ja", setOf("en"))
        assertTrue(MlKitTranslationEngine.codeStillInUse("en", "en>ja"))
        assertEquals(MlKitTranslationEngine.PackState.NeedsDownload, flow.value)
    }
}
