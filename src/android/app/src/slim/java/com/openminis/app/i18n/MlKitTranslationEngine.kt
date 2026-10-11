package com.openminis.app.i18n

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-apk-flavors] slim 变体的翻译引擎存根：与 full 变体的
 * [com.google.mlkit.nl.translate] 引擎同公开 API，但整引擎恒不可用。
 *
 * slim APK 不带 ML Kit 依赖（libtranslate_jni.so arm64 15.6 MB，bundled 交付
 * 无法按需化，用户报告「安装包又变大了」）——src/main 零改动：流式翻译在
 * 调用点被 [available]=false 关闭（零渲染），设置页的 ML Kit 区块经
 * [bundledEngine]=false 换成需要 full 版的提示；气泡翻译仍走 LLM 路径。
 *
 * 存根永不写 available=true：没有推理运行时，任何"可用"都是谎言。
 */
object MlKitTranslationEngine {

    /** 与 full 变体同构（设置页/测试共用）；slim 下 flow 恒停在 NeedsDownload。 */
    sealed class PackState {
        data object Checking : PackState()
        data object NeedsDownload : PackState()
        data class Downloading(
            val progress: Float? = null,
            val step: Int = 1,
            val totalSteps: Int = 2,
            val downloadedBytes: Long = 0L,
            val totalBytes: Long = 0L,
        ) : PackState() {
            /**
             * 与 full 同构（[T-mlkit-download-stall]）：ML Kit download Task
             * 不暴露字节进度，模拟百分比会卡 91%——确定阶段文案，不确定式进度条。
             */
            val detailText: String
                get() = if (totalSteps > 1) "第 $step/$totalSteps 阶段" else ""
        }
        data object Ready : PackState()
        data class Failed(val message: String) : PackState()
    }

    @Volatile
    var available: Boolean = false
        private set

    /**
     * [T-i18n-avail-fallback] 与 full 同构的失败降级入口：[StreamTranslator] 的
     * 翻译失败分支在 flavor 间共用同一份 src/main 代码，故这里必须有同名 API。
     * slim 下 [available] 本就是 false（存根永不写 true），这次写入是无害的幂等操作。
     */
    internal fun markUnavailable() {
        available = false
    }

    /** [T-apk-flavors] slim 变体：无 bundled 推理运行时。 */
    const val bundledEngine: Boolean = false

    private val packStates = ConcurrentHashMap<String, MutableStateFlow<PackState>>()

    /**
     * [T-cancel-refcheck] 与 full 同构的「已生效语言对」登记槽（设置页提交切换 /
     * 冷启动探测写；slim 无包可删，删除前复核恒不命中）。
     */
    @Volatile
    private var effectivePair: String? = null

    /** 翻译缓存在 slim 下照常存在（测试断言淘汰行为；永不命中推理路径）。 */
    internal val translationCache = Collections.synchronizedMap(
        object : LinkedHashMap<Int, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?): Boolean {
                return size > 100
            }
        }
    )

    fun packState(src: String, tgt: String): StateFlow<PackState> =
        packStates.getOrPut(key(src, tgt)) { MutableStateFlow(PackState.NeedsDownload) }

    /** slim 无 ML Kit 语言目录：任何 tag 都不受支持（语言清单收窄为空集）。 */
    fun mlLang(tag: String): String? = null

    private fun key(src: String, tgt: String) = "$src>$tgt"

    /**
     * [T-cancel-refcheck] 与 full 同构：登记「已生效语言对」（设置页提交切换调用）。
     * slim 无包可删，复核结果恒为「未引用」——保留同构 API 让 src/main 的调用点
     * （[com.openminis.app.ui.settings.TranslationSwitchPanel]）与单测两 flavor 通用。
     */
    internal fun markEffectivePair(src: String, tgt: String) {
        effectivePair = key(src, tgt)
    }

    /**
     * [T-cancel-refcheck] 与 full 同构的删除前复核：语言码是否仍被**别的**在用语言对
     * 引用（已生效语言对 / 本地已就绪的语言对）。slim 没有语言包与下载，[mlLang]
     * 恒 null ⇒ 清理面本就为空，这里只为语义与单测断言同构。
     */
    internal fun codeStillInUse(code: String, exceptKey: String): Boolean {
        if (code.isBlank()) return false
        if (effectivePair?.let { it != exceptKey && belongsTo(it, code) } == true) return true
        return packStates.any { (k, f) -> k != exceptKey && f.value is PackState.Ready && belongsTo(k, code) }
    }

    /** [T-cancel-refcheck] `src>tgt` 键（[key] 的格式）里是否含语言码 [code]。 */
    private fun belongsTo(pairKey: String, code: String): Boolean {
        val sep = pairKey.indexOf('>')
        if (sep <= 0 || sep == pairKey.lastIndex) return false
        return pairKey.substring(0, sep) == code || pairKey.substring(sep + 1) == code
    }

    /**
     * [T-packstate-settle] 与 full 同构：把状态机复位到可被探测刷新的
     * [PackState.NeedsDownload]——Failed("...") 没有别的写入点就会永久粘住
     * （slim 的卡片不渲染，但切换事务的回滚语义与断言要一致）。slim 无在飞下载，
     * 不需要 full 那边的「在飞 Downloading 优先」判断。
     */
    private fun settleState(k: String) {
        flowFor(k).value = PackState.NeedsDownload
    }

    suspend fun isPairReady(src: String, tgt: String): Boolean = false

    /**
     * slim 无 ML Kit 语言目录（[mlLang] 恒 null）：两侧都视为缺失。切换事务
     * 只把这些侧交给 [deletePackSides]（slim 下无包可删，仅复位状态机）。
     */
    suspend fun missingLanguages(src: String, tgt: String): Set<String> = setOf(src, tgt)

    suspend fun downloadedLanguages(): Set<String> = emptySet()

    /** slim 下探测恒失败（available 已是 false，永不翻转）。 */
    suspend fun probe(context: Context): Boolean = false

    /** slim 下无推理运行时：显式失败而不是假装下载。 */
    suspend fun downloadPack(context: Context, src: String, tgt: String) {
        flowFor(key(src, tgt)).value = PackState.Failed("translation engine requires the full build")
        throw IllegalStateException("translation engine requires the full build")
    }

    /**
     * [T-cancel-cleanup-sides] [sides] 只在 full 引擎用于收窄清理面；slim 无包可删。
     * [T-cancel-failed-sticky] 与 full 同口径：取消**不写** Failed("cancelled")（那是会
     * 永久粘住的状态），只落可被探测刷新的 NeedsDownload。
     */
    fun cancelActiveDownload(src: String, tgt: String, sides: Set<String>? = null) {
        flowFor(key(src, tgt)).value = PackState.NeedsDownload
    }

    suspend fun deletePack(src: String, tgt: String) {
        translationCache.clear()
        flowFor(key(src, tgt)).value = PackState.NeedsDownload
    }

    /**
     * [T-lang-switch-txn] 与 full 同形：只复位状态机（slim 无包可删，无所谓侧）。
     * [T-cancel-refcheck] 删除前复核也照 full 走一遍：slim 的 [mlLang] 恒 null ⇒
     * `codes` 恒空（无包可删），复核恒不命中；保留同构链路是为了两 flavor 共用同一份
     * 切换事务语义与同一份单测断言（slim 的 [translate] 不读翻译缓存，缓存清理本就
     * 无可观测效果）。[T-packstate-settle] `sides` 为空（本次无新增）时同样要复位状态机。
     */
    suspend fun deletePackSides(src: String, tgt: String, sides: Set<String>) {
        val k = key(src, tgt)
        val codes = mutableListOf<String>()
        if (src in sides) mlLang(src)?.let { codes.add(it) }
        if (tgt in sides) mlLang(tgt)?.let { codes.add(it) }
        val deletable = codes.filterNot { codeStillInUse(it, k) }
        if (deletable.isNotEmpty()) translationCache.clear()
        settleState(k)
    }

    /** slim 下无推理：返回空串，调用方 takeIf { isNotBlank() } 自然零渲染。 */
    suspend fun translate(text: String, src: String, tgt: String): String = ""

    private fun flowFor(k: String): MutableStateFlow<PackState> =
        packStates.getOrPut(k) { MutableStateFlow(PackState.NeedsDownload) }
}
