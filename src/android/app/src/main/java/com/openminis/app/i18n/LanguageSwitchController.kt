package com.openminis.app.i18n

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [T-lang-switch-txn] 语言切换事务：双槽状态机 + 取消回滚 + 串行下载。
 *
 * **双槽**：已生效槽（prefs 当前值，[readEffective] 读）与进行中槽
 * （[SwitchState.Pending]）。**rollbackTo 只认已生效槽**——连切场景下回滚
 * 基准永远是「用户切之前实际生效的语言对」，不是上一个被顶掉的 pending
 * （否则连切 N 次后取消会落到中间态）。
 *
 * **提交时机**：下载成功才写 prefs（确认前不写）——确认弹窗取消 = 事务未
 * 开启，天然无需回滚；下载中取消 = [rollback] abort 在飞下载 + 清半成品。
 *
 * **清半成品只清「本次缺失侧」**（[T-lang-switch-txn]）：事务在下载前用
 * [missingSides] 快照 `to` 尚缺的标签侧，回滚/失败清理只允许动这些侧。
 * 「只改目标语言」时源侧包仍被已生效语言对使用（en→zh 生效时切 en→ja，
 * 取消不该动 en）——原先无条件删两侧会让实时翻译静默失效。
 *
 * **锁分层**（关键）：[stateMutex] 只护状态迁移，**不跨下载**；下载在
 * [QUEUE] 全局串行队列里跑。否则 rollback 抢不到锁，「下载中取消」会阻塞
 * 到下载结束才生效。事务身份靠**代际令牌** [generation]：下载返回后比对
 * 令牌，被顶掉/被回滚的事务不得写 prefs 也不得改状态。
 *
 * **串行下载**（拍板）：[QUEUE] 全局单队列，多语言包请求排队逐个下。
 *
 * **回滚失败**：abort 后半成品清理抛异常 → [SwitchState.RollbackFailed]
 * （UI 落点态：设置页错误横幅，prefs 仍为 from 值，可重试）。
 *
 * 依赖全注入（无 Android 依赖，JVM 可测）。
 */
class LanguageSwitchController(
    private val readEffective: () -> LangPair,
    private val writeEffective: (LangPair) -> Unit,
    private val isPairReady: suspend (LangPair) -> Boolean,
    /**
     * [T-lang-switch-txn] 快照 `to` 中**本地尚缺**的标签侧（下载前调用；查询失败
     * 返回空集 = 本次不允许清理）。只有这些侧可能被本次事务新增。
     */
    private val missingSides: suspend (LangPair) -> Set<String>,
    private val downloadPack: suspend (LangPair) -> Unit,
    /**
     * [T-cancel-cleanup-sides] abort 在飞下载，第二参数 = 本次缺失侧（与
     * [clearPartial] 同口径）：实现方据此收窄"取消后仍下完"的游离包清理面，
     * 别删已生效语言对仍在用的侧。
     */
    private val abortDownload: (LangPair, Set<String>) -> Unit,
    /**
     * [T-lang-switch-txn] 清半成品，实参 = (to, 本次缺失侧)：**只清列出的侧**。
     * 空集 = 本次无新增包 ⇒ **不清任何语言包**；但实现方仍要把该语言对的状态机
     * 复位到「本地实况」（[T-packstate-settle]：Failed/陈旧 Downloading 否则永久
     * 粘住，卡片一直显示「失败: cancelled」+ 多余的下载按钮）。
     */
    private val clearPartial: suspend (LangPair, Set<String>) -> Unit,
) {

    data class LangPair(val src: String, val tgt: String)

    sealed class SwitchState {
        data object Idle : SwitchState()
        /** 进行中：from=已生效基准，to=目标，phase 区分确认前/下载中。 */
        data class Pending(
            val from: LangPair,
            val to: LangPair,
            val phase: Phase,
            /**
             * [T-lang-switch-txn] 下载前快照的「`to` 尚缺侧」：清理半成品只许动
             * 这些侧（确认前相位为空集——此时还没发起下载，本就无事可清）。
             */
            val partial: Set<String> = emptySet(),
        ) : SwitchState() {
            enum class Phase { CONFIRMING, DOWNLOADING }
        }
        /** 回滚失败（abort 后半成品清理抛异常）：UI 错误落点态。 */
        data class RollbackFailed(
            val from: LangPair,
            val to: LangPair,
            val message: String,
            /** [T-lang-switch-txn] 保留失败时的缺失侧快照，重试清理仍只动这些侧。 */
            val partial: Set<String> = emptySet(),
        ) : SwitchState()
    }

    enum class SwitchResult { COMMITTED, FAILED, ROLLED_BACK }

    private val _state = MutableStateFlow<SwitchState>(SwitchState.Idle)
    val state: StateFlow<SwitchState> = _state

    private val stateMutex = Mutex()

    /** 代际令牌：每次新事务自增；被顶掉的事务凭旧令牌自动放弃写权限。 */
    @Volatile
    private var activeGen = -1L
    private var generation = 0L

    /** 全局串行下载队列（拍板：排队不并发）。 */
    companion object {
        private val QUEUE = Mutex()
        private object Superseded : RuntimeException("superseded by newer switch")
    }

    /**
     * 切换事务：就绪直接提交；需下载则入全局串行队列，成功才写 prefs。
     * 连切：新请求先回滚在飞事务（abort + 清半成品），再开新事务——回滚
     * 基准仍是已生效槽。
     */
    suspend fun requestSwitch(to: LangPair): SwitchResult {
        val from = readEffective()
        if (from == to) return SwitchResult.COMMITTED
        val gen = stateMutex.withLock {
            rollbackLocked()
            val g = ++generation
            activeGen = g
            _state.value = SwitchState.Pending(from, to, SwitchState.Pending.Phase.CONFIRMING)
            g
        }
        if (isPairReady(to)) return commit(gen, to)
        // [T-lang-switch-txn] 下载前快照「尚缺侧」：只有这些侧可能被本次事务新增，
        // 故清理半成品只允许动它们——共用侧（只改目标语言时的源侧）绝不动。
        // 快照本身失败 = 不放行任何清理（保守：宁留游离包，不误删在用包）。
        val partial: Set<String> = runCatching { missingSides(to) }.getOrDefault(emptySet())
        stateMutex.withLock {
            if (activeGen == gen) {
                _state.value = SwitchState.Pending(from, to, SwitchState.Pending.Phase.DOWNLOADING, partial)
            }
        }
        val ok = try {
            QUEUE.withLock {
                if (activeGen != gen) throw Superseded
                downloadPack(to)
            }
            true
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce // 宿主吊销协程：照常传播，不吞取消
        } catch (_: Throwable) {
            false
        }
        return stateMutex.withLock {
            when {
                activeGen != gen -> SwitchResult.FAILED // 被顶掉/已回滚：不动状态不写 prefs
                ok && isPairReady(to) -> {
                    writeEffective(to)
                    activeGen = -1
                    _state.value = SwitchState.Idle
                    SwitchResult.COMMITTED
                }
                else -> {
                    activeGen = -1
                    _state.value = SwitchState.Idle
                    runCatching { clearPartial(to, partial) } // 失败路径尽力清本次新增的半成品
                    SwitchResult.FAILED
                }
            }
        }
    }

    /**
     * 取消回滚：丢弃 pending，abort 在飞下载，清半成品（只清本次缺失侧，
     * 见 [SwitchState.Pending.partial]）。prefs 未动（提交时机=下载成功后），
     * 无需写回。清理失败 → RollbackFailed 落点态。
     * 在飞事务凭代际令牌自动放弃提交，故本函数**不等下载结束**。
     */
    suspend fun rollback(): SwitchResult {
        val st = _state.value
        val pending = st as? SwitchState.Pending
        val failed = st as? SwitchState.RollbackFailed
        val to = pending?.to ?: failed?.to ?: return SwitchResult.ROLLED_BACK
        val from = pending?.from ?: failed?.from ?: readEffective()
        // [T-lang-switch-txn] 重试清理仍沿用原快照：只动本次缺失的侧。
        val partial = pending?.partial ?: failed?.partial ?: emptySet()
        return stateMutex.withLock {
            activeGen = -1 // 先吊销令牌：在飞事务回来即作废
            abortDownload(to, partial)
            runCatching { clearPartial(to, partial) }.fold(
                onSuccess = { _state.value = SwitchState.Idle; SwitchResult.ROLLED_BACK },
                onFailure = {
                    _state.value = SwitchState.RollbackFailed(from, to, it.message ?: "rollback failed", partial)
                    SwitchResult.FAILED
                },
            )
        }
    }

    /** 清 RollbackFailed 落点态（用户点「知道了」）。 */
    fun acknowledgeFailure() {
        if (_state.value is SwitchState.RollbackFailed) _state.value = SwitchState.Idle
    }

    /** 就绪快路径提交（无下载）；持锁比对令牌，被顶掉则放弃。 */
    private suspend fun commit(gen: Long, to: LangPair): SwitchResult = stateMutex.withLock {
        if (activeGen != gen) return@withLock SwitchResult.FAILED
        writeEffective(to)
        activeGen = -1
        _state.value = SwitchState.Idle
        SwitchResult.COMMITTED
    }

    /**
     * 回滚在飞事务（事务内部路径，调用方持 [stateMutex]）：吊销令牌 + abort +
     * 尽力清半成品——只清本次缺失侧（连切路径，旧事务清理失败不挡新事务；
     * 严格清理由 [rollback] 自己承担并落 RollbackFailed）。
     */
    private suspend fun rollbackLocked(): Boolean {
        activeGen = -1
        val pending = _state.value as? SwitchState.Pending ?: return false
        abortDownload(pending.to, pending.partial)
        runCatching { clearPartial(pending.to, pending.partial) }
        _state.value = SwitchState.Idle
        return true
    }
}
