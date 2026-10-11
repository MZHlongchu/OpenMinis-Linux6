package com.openminis.app.i18n

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-mlkit-offline-translate] ML Kit 离线翻译引擎：思考流/输出流的实时翻译。
 * 模型生命周期管理（状态机/字节级下载进度/删除/LRU 缓存）复用自
 * taixu TranslationManager（wkbin/taixu，core/common/translation）。
 *
 * - 推理完全在本机（不出设备、零隐私外泄），毫秒级每句；
 * - 语言包**不内置**：经 [downloadPack] 显式下载（~30MB/语言），下载进度
 *   经 [packState] StateFlow 供设置页展示——ML Kit 的 download Task 是黑盒，
 *   字节级进度靠轮询系统 DownloadManager（模型下载走系统通道）；
 * - **可用性判定**：[available]=false（无 GMS / 语言包缺失）时 AUTO 档回落用户
 *   模型；[available] 由冷启动 [probe] 与运行时结果共同维护（失败翻掉、成功翻回）；
 * - 翻译结果走 LRU 缓存（100 条）：流式重放/重复句零推理；
 * - 每语言对一个 Translator，缓存复用。
 */
object MlKitTranslationEngine {

    /** 对齐 taixu TranslationModelStatus；按语言对独立流转。 */
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
             * [T-mlkit-download-stall] ML Kit 的 download Task 不暴露字节进度，
             * 旧实现模拟/借用系统 DownloadManager 的百分比会卡在 91%——改为
             * 确定的「下载中」文案，进度条走不确定式（progress=null）。
             */
            val detailText: String
                get() = if (totalSteps > 1) "第 $step/$totalSteps 阶段" else ""
        }
        data object Ready : PackState()
        data class Failed(val message: String) : PackState()
    }

    @Volatile
    var available: Boolean = true
        private set

    /** [T-i18n-avail-fallback] 失败即翻掉可用性（AUTO 档据此回落用户模型）；[downloadPack]/[translate] 成功翻回 true。 */
    internal fun markUnavailable() {
        available = false
    }

    /**
     * [T-apk-flavors] bundled 引擎标记：full 变体 true、slim 存根 false。
     * 设置页用它决定 ML Kit 实时翻译区块是否渲染（slim 下显示需要 full 版提示，
     * 而不是渲染一个永远下载失败的语言包卡）。
     */
    const val bundledEngine: Boolean = true

    private val translators = ConcurrentHashMap<String, com.google.mlkit.nl.translate.Translator>()
    private val packStates = ConcurrentHashMap<String, MutableStateFlow<PackState>>()

    // [T-mlkit-download-detached] 引擎级下载 scope：下载脱离调用方生命周期，
    // 退出设置页不再杀掉在飞下载（重进页面 packState 仍显示 Downloading）。
    private val downloadScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
    )
    private val downloadJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /** [T-lang-switch-txn] 在飞下载 Task（per-pair），供 [cancelActiveDownload] 挂清理监听。 */
    private val activeDownloadTasks = ConcurrentHashMap<String, MutableList<Task<Void>>>()

    /**
     * [T-cancel-refcheck] 当前**已生效**语言对（`src>tgt`）。删除前复核「该语言码是否
     * 仍被别的在用语言对引用」时要用它：设置页提交切换时登记（[markEffectivePair]）、
     * 冷启动/开关探测时按 prefs 登记（[probe]）。只留一个槽——生效语言对只有一个，
     * 旧的留着只会让游离包永远清不掉。
     */
    @Volatile
    private var effectivePair: String? = null

    /**
     * [T-lang-switch-txn] 取消标记 + 下载代际。
     *
     * mlkit common 18.11.0 的 `RemoteModelManager.download` **只有两参重载**
     * （无 CancellationToken 版本，javap 核实），底层下载无法真中断。故取消是
     * **协作式**：置标记 → 分阶段循环下一步前退出 + 宿主吊销等待协程；若 GMS
     * 侧仍把模型下完，完成监听按**同代 + 引用复核**判定删掉游离包（代际不同 =
     * 用户已重新发起同语言对下载；仍被别的在用语言对引用 = 不能误删它要用的包，
     * 见 [T-cancel-refcheck]）。判据不含 [cancelRequested]（见其调用点）。
     */
    private val cancelRequested = ConcurrentHashMap.newKeySet<String>()
    private val downloadGen = ConcurrentHashMap<String, Long>()

    /** LRU 翻译缓存（复用 taixu：最多 100 条，访问序淘汰）。 */
    internal val translationCache = Collections.synchronizedMap(
        object : LinkedHashMap<Int, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?): Boolean {
                return size > 100
            }
        }
    )

    fun packState(src: String, tgt: String): StateFlow<PackState> =
        flowFor(key(src, tgt)).also { refreshStatus(src, tgt, it) }

    /** BCP-47 tag → ML Kit 语言码；不支持的 tag 返回 null。 */
    fun mlLang(tag: String): String? = try {
        TranslateLanguage.fromLanguageTag(tag)
    } catch (_: Throwable) {
        null
    }

    private fun key(src: String, tgt: String) = "$src>$tgt"

    private fun flowFor(k: String): MutableStateFlow<PackState> =
        packStates.getOrPut(k) { MutableStateFlow(PackState.Checking) }

    /**
     * [T-cancel-refcheck] 登记「已生效语言对」（设置页提交切换 / 冷启动探测调用）。
     * 删除前复核用它把「已生效语言对仍在用的侧」挡在清理面之外。
     */
    internal fun markEffectivePair(src: String, tgt: String) {
        effectivePair = key(src, tgt)
    }

    /**
     * [T-cancel-refcheck] 删除前复核：语言码 [code] 是否仍被**别的**在用语言对引用
     * （[exceptKey] = 正在取消/清理的语言对，它自己不算）。在用 =
     * 已生效语言对（[effectivePair]）、在飞下载（[downloadJobs]）、本地已就绪
     * （[PackState.Ready] 的语言对）。
     *
     * 只按代际 + 「下载前缺失」快照判定的旧实现会删掉共用侧：取消 en→ja 后紧接
     * 开 ja→zh（共用 ja）时旧监听照样删 ja；手动下载卡（设置页绕过事务直接
     * [downloadPack]）刚下好的侧也会被别的事务按下载前快照清掉，而那一对的 flow
     * 停在 Ready——显示已就绪但实时翻译静默失效。复核不过就跳过删除（宁可留游离包，
     * 也不误删在用包）。
     */
    internal fun codeStillInUse(code: String, exceptKey: String): Boolean {
        if (code.isBlank()) return false
        if (effectivePair?.let { it != exceptKey && belongsTo(it, code) } == true) return true
        if (downloadJobs.any { (pk, job) -> pk != exceptKey && job.isActive && belongsTo(pk, code) }) return true
        return packStates.any { (k, f) -> k != exceptKey && f.value is PackState.Ready && belongsTo(k, code) }
    }

    /** [T-cancel-refcheck] `src>tgt` 键（[key] 的格式）里是否含语言码 [code]。 */
    private fun belongsTo(pairKey: String, code: String): Boolean {
        val sep = pairKey.indexOf('>')
        if (sep <= 0 || sep == pairKey.lastIndex) return false
        return pairKey.substring(0, sep) == code || pairKey.substring(sep + 1) == code
    }

    /**
     * [T-packstate-settle] 取消/半成品清理后把状态机复位到**可被探测刷新**的
     * [PackState.NeedsDownload]：Failed("cancelled") 与陈旧的 Downloading 都不是结论，
     * 而 [refreshStatus] 的门禁不再刷新它们——没有别的写入点就会永久粘住，卡片一直
     * 显示「失败: cancelled」+ 一个多余的下载按钮。仍在飞的下载保留其 Downloading
     * （那是有结论的状态）；本地实况由下一次读 [packState] 的 [refreshStatus] 复核
     * （被引用保护、没被删掉的侧会自己回到 Ready）。
     */
    private fun settleState(k: String) {
        if (flowFor(k).value is PackState.Downloading && downloadJobs[k]?.isActive == true) return
        flowFor(k).value = PackState.NeedsDownload
    }

    /** 异步检测两个语言包的本地状态（复用 taixu refreshStatus）。
     * [T-packstate-pure-read] 只覆盖没结论的 Checking/NeedsDownload：不动在飞的
     * Downloading（重进设置页要能看到）与 Failed（留错误文案）；compareAndSet 落回。 */
    private fun refreshStatus(src: String, tgt: String, flow: MutableStateFlow<PackState>) {
        if (!refreshable(flow.value)) return
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            if (!refreshable(flow.value)) return@launch
            flow.value = PackState.Checking
            val next = try {
                if (isPairDownloaded(src, tgt)) PackState.Ready else PackState.NeedsDownload
            } catch (_: Throwable) {
                PackState.NeedsDownload
            }
            flow.compareAndSet(PackState.Checking, next)
        }
    }

    /** [T-packstate-pure-read] 是否允许被探测结果覆盖。 */
    private fun refreshable(s: PackState) = s is PackState.Checking || s is PackState.NeedsDownload

    private suspend fun isPairDownloaded(src: String, tgt: String): Boolean {
        val srcCode = mlLang(src) ?: return false
        val tgtCode = mlLang(tgt) ?: return false
        val downloaded = downloadedLanguages()
        return downloaded.contains(srcCode) && downloaded.contains(tgtCode)
    }

    /**
     * [T-lang-switch-txn] 语言对是否已就绪（两侧模型都在本地）。切换事务的
     * 就绪快路径判定——就绪则不下载直接提交。
     */
    suspend fun isPairReady(src: String, tgt: String): Boolean = isPairDownloaded(src, tgt)

    /**
     * [T-lang-switch-txn] 语言对中**本地尚缺**的标签侧（已在本地的侧不返回）。
     * 切换事务在下载前用它快照「本次可能新增的侧」，回滚只按这份快照清半成品，
     * 从而不会删掉已生效语言对仍在用的另一侧（en→zh 生效时切 en→ja，取消不该
     * 动 en）。
     *
     * 查询失败时保守返回空集（= 本次不清理）：宁可留下游离包，也不误删在用包。
     */
    suspend fun missingLanguages(src: String, tgt: String): Set<String> {
        val downloaded = queryDownloadedLanguages() ?: return emptySet()
        val missing = mutableSetOf<String>()
        if (mlLang(src)?.let { it in downloaded } != true) missing.add(src)
        if (mlLang(tgt)?.let { it in downloaded } != true) missing.add(tgt)
        return missing
    }

    /**
     * [T-lang-source-provider] 已下载模型的语言码集合（live 查询
     * RemoteModelManager）。语言清单数据源用它收窄 downloaded 标记；查询失败
     * 返回空集（调用方保持 stale 态）。
     */
    suspend fun downloadedLanguages(): Set<String> = queryDownloadedLanguages() ?: emptySet()

    /** [T-lang-switch-txn] 同 [downloadedLanguages]，但查询失败返回 null（与「确实为空」区分）。 */
    private suspend fun queryDownloadedLanguages(): Set<String>? {
        val manager = runCatching { RemoteModelManager.getInstance() }.getOrNull() ?: return null
        val models = runCatching {
            manager.getDownloadedModels(TranslateRemoteModel::class.java).await()
        }.getOrNull() ?: return null
        return models.map { it.language }.toSet()
    }

    /**
     * [T-i18n-probe-wiring] 可用性探测：按**当前语言对**厘清 [available]，已开启实时
     * 翻译时补下缺失语言包（设置页承诺的「language packs download on first use」）。
     * 调用点：`MinisApp.onCreate` 冷启动 + 设置页打开开关时。旧实现是死代码且硬编码
     * en→zh——初值 true、唯一写入点又在这个从没被调用的函数里，AUTO 档于是永远回落
     * 不到用户模型。未开启时只做只读复核（不替没开的用户下包）；调用方协程被取消
     * 原样抛出——那不是「引擎不可用」。
     */
    suspend fun probe(context: Context): Boolean = try {
        val src = TranslationPrefs.streamSource(context)
        val tgt = TranslationPrefs.streamTarget(context)
        // [T-cancel-refcheck] prefs 就是「已生效语言对」的事实源：探测时顺手登记，
        // 删除前复核据此保护生效语言对仍在用的侧（未开启实时翻译时也登记——设置页
        // 的手动下载卡/切换事务与开关无关）。
        markEffectivePair(src, tgt)
        available = if (TranslationPrefs.isStreamEnabled(context)) {
            downloadPack(context, src, tgt)
            translate("hi", src, tgt).isNotBlank()
        } else {
            isPairDownloaded(src, tgt)
        }
        available
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        available = false
        false
    }

    /**
     * 下载语言对模型。
     *
     * [T-mlkit-download-detached] 下载任务跑在**引擎级 scope**（脱离调用方）：
     * 退出设置页取消的是调用方协程，下载继续；重进页面 packState（引擎级
     * flow）仍显示 Downloading，同语言对再调 [downloadPack] 只是 join 在飞
     * 任务——修复「退出页面再进入又要重新下载」。
     *
     * [T-mlkit-download-stall] 进度改**不确定式**：ML Kit 的 download Task
     * 不暴露字节进度，旧实现轮询系统 DownloadManager——常常查不到（模拟
     * 进度封顶 90%）或查到别家应用的下载任务，百分比永远卡在 91%。现在
     * Downloading 只表达「进行中」（progress=null → UI 不确定式进度条），
     * 完成即 Ready，不再有假数字。
     *
     * 失败置 [PackState.Failed] 并抛；**用户取消**例外——落可被探测刷新的
     * [PackState.NeedsDownload]（[T-packstate-settle]：取消不是引擎故障，写 Failed
     * 会永久粘住）。
     */
    suspend fun downloadPack(context: Context, src: String, tgt: String) {
        val k = key(src, tgt)
        val existing = downloadJobs[k]
        if (existing?.isActive == true) {
            // [T-mlkit-download-detached] 同语言对已在飞：等待而不是静默返回
            // （旧 tryLock 直接 return，调用方误以为下载完成）。
            existing.join()
            return
        }
        val job = downloadScope.launch { runDownload(context, src, tgt, k) }
        downloadJobs[k] = job
        try {
            job.join()
        } catch (e: CancellationException) {
            // 调用方（页面）退出：下载任务继续跑，这里只是不再等。
            throw e
        }
    }

    private suspend fun runDownload(context: Context, src: String, tgt: String, k: String) {
        val flow = flowFor(k)
        try {
            val srcCode = mlLang(src) ?: throw IllegalArgumentException("unsupported source language: $src")
            val tgtCode = mlLang(tgt) ?: throw IllegalArgumentException("unsupported target language: $tgt")
            cancelRequested.remove(k)
            val gen = (downloadGen[k] ?: 0L) + 1L
            downloadGen[k] = gen
            if (flow.value is PackState.Ready && isPairDownloaded(src, tgt)) return
            val conditions = DownloadConditions.Builder().build()
            val manager = RemoteModelManager.getInstance()
            val queue = mutableListOf<TranslateRemoteModel>()
            val downloaded = runCatching {
                manager.getDownloadedModels(TranslateRemoteModel::class.java).await()
            }.getOrDefault(emptySet())
            if (downloaded.none { it.language == srcCode }) queue.add(TranslateRemoteModel.Builder(srcCode).build())
            if (downloaded.none { it.language == tgtCode }) queue.add(TranslateRemoteModel.Builder(tgtCode).build())
            if (queue.isEmpty()) {
                available = true // [T-i18n-avail-fallback] 两侧都在本地 = 引擎可用（自愈）
                flow.value = PackState.Ready
                return
            }
            flow.value = PackState.Downloading()
            withContext(Dispatchers.IO) {
                val pairTasks = activeDownloadTasks.getOrPut(k) { mutableListOf() }
                for (model in queue) {
                    // [T-lang-switch-txn] 取消检查点：两阶段之间退出（阶段内
                    // 的 await 由宿主吊销协程打断）。
                    if (cancelRequested.contains(k)) throw IllegalStateException("download cancelled")
                    val task = manager.download(model, conditions)
                    synchronized(pairTasks) { pairTasks.add(task) }
                    try {
                        task.await()
                    } finally {
                        synchronized(pairTasks) { pairTasks.remove(task) }
                    }
                }
            }
            // [T-cancel-refcheck] 末阶段 await 期间被取消同样要在这里退出：否则 Ready 会
            // 盖过取消，而取消监听随后仍会删掉游离包——「已就绪但包不在」的谎言。
            if (cancelRequested.contains(k)) throw IllegalStateException("download cancelled")
            available = true // [T-i18n-avail-fallback] 下载成功 = 引擎可用（自愈）
            flow.value = PackState.Ready
        } catch (t: Throwable) {
            // [T-packstate-settle] 用户取消不是引擎故障：写 Failed 会让卡片永久停在错误态
            // （[refreshStatus] 不刷新 Failed）。落 NeedsDownload —— 可被探测刷新，下一次读
            // packState 按本地实况收敛。
            if (cancelRequested.contains(k)) flow.value = PackState.NeedsDownload
            else flow.value = PackState.Failed(t.message ?: "download failed")
            throw t
        } finally {
            downloadJobs.remove(k)
            activeDownloadTasks.remove(k)
            // [T-packstate-settle] 取消标记的**清除者**负责最后落一次状态：取消可能刚好
            // 压在上面那句 `flow.value = Ready` 之后到达（那时取消方看到的还是在飞任务，
            // 写了 Failed，随即被 Ready 盖掉），而它挂的监听仍会照删游离包——必须让取消
            // 语义在最后生效，别留下「已就绪但包不在」。标志的 remove 返回值即「谁最后
            // 到」的仲裁，不需要额外加锁。
            if (cancelRequested.remove(k)) flow.value = PackState.NeedsDownload
        }
    }

    /**
     * [T-lang-switch-txn] 取消在飞下载（语言切换回滚路径）。
     *
     * 三：① 置取消标记（分阶段循环下个检查点退出）；
     * ② 给在飞 Task 挂完成监听——GMS 侧若仍下完，按同代 + 引用复核删除游离模型包；
     * ③ 等待中的协程由宿主吊销（`await()` 抛 CancellationException）。
     * 半成品清理由调用方（切换事务）跟进 [deletePackSides]（只清本次新增的侧；
     * 本监听按同代删的侧也由 [sides] 收窄，见 [T-cancel-orphan-cleanup]）。
     *
     * [T-cancel-failed-sticky] **没有在飞任务时不写 Failed("cancelled")**：那种「取消」其实
     * 无物可取消（确认前相位 / 下载已结束），写下去就是永久粘住——[refreshStatus] 的新门禁
     * 不再刷新 Failed，卡片于是永远显示「失败: cancelled」+ 多余的下载按钮。
     *
     * [T-cancel-cleanup-sides] [sides] = 本次事务新增的**标签侧**（与
     * [deletePackSides] 同口径）。null = 调用方没有侧信息，按旧行为删两侧；
     * 非空集合时只删集合内的侧——「只改目标语言」的切换取消时，源侧包仍被
     * 已生效语言对使用，删掉它会让实时翻译在 prefs 不变的情况下静默失效。
     */
    fun cancelActiveDownload(src: String, tgt: String, sides: Set<String>? = null) {
        val k = key(src, tgt)
        cancelRequested.add(k)
        val gen = downloadGen[k] ?: 0L
        val tasks = activeDownloadTasks[k]?.let { list -> synchronized(list) { list.toList() } }.orEmpty()
        if (downloadJobs[k]?.isActive == true || tasks.isNotEmpty()) {
            flowFor(k).value = PackState.Failed("cancelled")
        }
        val srcCode = if (sides == null || src in sides) mlLang(src) else null
        val tgtCode = if (sides == null || tgt in sides) mlLang(tgt) else null
        tasks.forEach { task ->
            runCatching {
                task.addOnCompleteListener {
                    // [T-cancel-orphan-cleanup] 判据只有代际：本回调由 GMS 投递到主
                    // 线程，执行时 runDownload 的 finally 可能已清掉 cancelRequested
                    // （拿它当判据会漏删游离包）。代际不同 = 已有新下载，不能误删。
                    if ((downloadGen[k] ?: 0L) != gen) return@addOnCompleteListener
                    val mgr = runCatching { RemoteModelManager.getInstance() }.getOrNull() ?: return@addOnCompleteListener
                    // [T-cancel-refcheck] 删前复核引用：本回调可能在用户已经切到共用该
                    // 模型的新语言对（ja→zh 共用 ja）之后才到，照着旧代际删就把新对
                    // 正在用的包删了。
                    srcCode?.takeIf { !codeStillInUse(it, k) }?.let { c ->
                        runCatching { mgr.deleteDownloadedModel(TranslateRemoteModel.Builder(c).build()) }
                    }
                    tgtCode?.takeIf { !codeStillInUse(it, k) }?.let { c ->
                        runCatching { mgr.deleteDownloadedModel(TranslateRemoteModel.Builder(c).build()) }
                    }
                }
            }
        }
    }

    /** 删除语言对模型释放空间（复用 taixu deleteModel）。 */
    suspend fun deletePack(src: String, tgt: String) {
        val srcCode = mlLang(src) ?: throw IllegalArgumentException("unsupported source language: $src")
        val tgtCode = mlLang(tgt) ?: throw IllegalArgumentException("unsupported target language: $tgt")
        val k = key(src, tgt)
        try {
            val manager = RemoteModelManager.getInstance()
            manager.deleteDownloadedModel(TranslateRemoteModel.Builder(srcCode).build()).await()
            manager.deleteDownloadedModel(TranslateRemoteModel.Builder(tgtCode).build()).await()
            translators.remove(k)
            translationCache.clear()
            flowFor(k).value = PackState.NeedsDownload
        } catch (t: Throwable) {
            flowFor(k).value = PackState.Failed(t.message ?: "delete failed")
            throw t
        }
    }

    /**
     * [T-lang-switch-txn] 只删语言对中指定**标签侧**的模型（切换事务回滚清半成品）：
     * 不在 `sides` 里的侧保持不动——「只改目标语言」时源侧包仍被已生效语言对使用，
     * 原先无条件按 [deletePack] 删两侧会把它在用的包一起删掉（实时翻译静默失效）。
     * 设置页的显式「删除」按钮仍走 [deletePack]（那是用户的整体删除意图）。
     *
     * [T-cancel-refcheck] 每个侧删前再复核一次引用（[codeStillInUse]）：快照记的是
     * 「下载前缺」，不排除别的消费者（手动下载卡刚下好的侧、已生效语言对、在飞下载）
     * ——照快照删会把它们正在用的包删掉。
     *
     * [T-packstate-settle] 没有可删的侧（本次无新增 / 全被引用保护）时也要复位状态机：
     * 旧实现直接 return，[PackState.Failed]("cancelled") 与陈旧 Downloading 就永久粘住。
     */
    suspend fun deletePackSides(src: String, tgt: String, sides: Set<String>) {
        val k = key(src, tgt)
        val codes = mutableListOf<String>()
        if (src in sides) mlLang(src)?.let { codes.add(it) }
        if (tgt in sides) mlLang(tgt)?.let { codes.add(it) }
        val deletable = codes.filterNot { codeStillInUse(it, k) }
        try {
            if (deletable.isNotEmpty()) {
                val manager = RemoteModelManager.getInstance()
                for (code in deletable) {
                    manager.deleteDownloadedModel(TranslateRemoteModel.Builder(code).build()).await()
                }
                translators.remove(k)
                translationCache.clear()
            }
            settleState(k)
        } catch (t: Throwable) {
            flowFor(k).value = PackState.Failed(t.message ?: "delete failed")
            throw t
        }
    }

    private fun translator(src: String, tgt: String): com.google.mlkit.nl.translate.Translator {
        val k = key(src, tgt)
        return translators.getOrPut(k) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(mlLang(src) ?: throw IllegalArgumentException("unsupported source language: $src"))
                .setTargetLanguage(mlLang(tgt) ?: throw IllegalArgumentException("unsupported target language: $tgt"))
                .build()
            Translation.getClient(options)
        }
    }

    /** 单句翻译（毫秒级，LRU 缓存命中零推理）。异常向上抛，由调用方决定降级。 */
    suspend fun translate(text: String, src: String, tgt: String): String {
        if (text.isBlank()) return ""
        translationCache[text.hashCode()]?.let { return it }
        val translated = translator(src, tgt).translate(text).await()
        // [T-i18n-avail-fallback] 真翻出来了 = 运行时与语言包都在位：恢复可用性。
        available = true
        translationCache[text.hashCode()] = translated
        return translated
    }
}
