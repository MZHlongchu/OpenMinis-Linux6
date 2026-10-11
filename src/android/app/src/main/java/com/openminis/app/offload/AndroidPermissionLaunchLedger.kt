package com.openminis.app.offload

import com.openminis.app.logging.AppLogger

private const val LEDGER_TAG = "PermissionLaunch"

/**
 * [T-android-prompt-launch-ledger] 系统运行时权限对话框的**按 launch 记账**。
 *
 * 根因。`ActivityResultContracts.RequestMultiplePermissions` 的回调只给一张
 * `Map<String, Boolean>`，**拿不到 requestCode**，"这次结果属于哪张提示"只能我们自己记。
 * 旧实现把它记在 launcher 级的**单一可变字段**上（`MainActivity.launchedPermissionPromptId`、
 * `ChatScreen.currentPermissionRequestRef`）：两次重叠的 launch 里后一次覆写字段，前一次
 * 对话框的结果就落到了后一张提示上 —— 用户答 A、B 拿到 GRANTED（fail-open）。
 *
 * 修法。
 *  ① 同一时刻只允许**一个** launch 在飞：收集器每收到一张提示只登记（[submit]），真正弹框
 *     由 [pump] 派发；在飞那次的回调（[onResult]）按**弹出时的身份**回传，回传之后再派发
 *     下一张 —— 旧字段被覆写的那一步就此消失。
 *  ② 已经被更新的提示取代的排队项不弹：它们的应答带的是旧身份，金库（[SingleFlightSlot]）
 *     一定会丢弃，弹出来只是白占一次系统对话框。
 *  ③ 失效兜底：在飞那次如果已经没人能接收它的结果（金库空了 / 超过调用方自己的预算），
 *     立刻解封继续派发；`Activity` 重建时保存下来的在飞身份要经 [restore] 与金库核对，
 *     对不上（进程死亡后的全新金库）就不认领 —— 任何一条路径都不能把队列永久堵死。
 *
 * 不引用任何 Android API（弹框与应答都是调用方注入的），因此可在纯 JVM 单测里直接驱动。
 * 线程模型：[lock] 内只做状态判断与变更，[launch] / [deliver] / 日志一律在锁外执行；
 * 调用方（Activity 的提示收集器与 ActivityResult 回调）都在主线程。
 */
internal class AndroidPermissionLaunchLedger<R : Any, A : Any>(
    /** 金库此刻在等的那张询问；null = 没有任何人在等系统对话框。 */
    private val currentPrompt: () -> PendingPrompt<R>?,
    /** 真正弹系统对话框；抛异常按 [launchFailedResult] 收口，不把队列卡死。 */
    private val launch: (PendingPrompt<R>) -> Unit,
    /** 按在飞那次的身份回传结果；false = 金库已经换人（陈旧应答被丢弃）。 */
    private val deliver: (Long, A) -> Boolean,
    /** [launch] 抛异常时替这次询问回的结果（fail-closed）。 */
    private val launchFailedResult: A,
    /** 排队上限，同 [MAX_QUEUED_PROMPTS]：陈旧登记很快会被下一个提示取代。 */
    private val maxQueued: Int = MAX_QUEUED_PROMPTS,
    /** 在飞那次的等待上限，默认等于调用方自己的预算 [OffloadPermissionManager.SYSTEM_DIALOG_TIMEOUT_MS]。 */
    private val inFlightBudgetMs: Long = OffloadPermissionManager.SYSTEM_DIALOG_TIMEOUT_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** [pump] 的一步：要不要弹、以及只为日志存在的两个 id（锁内只算状态，日志在锁外打）。 */
    private class Step<T : Any>(
        val next: PendingPrompt<T>?,
        val releasedId: Long = 0L,
        val droppedIds: List<Long> = emptyList(),
    )

    private val lock = Any()
    private var inFlight: PendingPrompt<R>? = null
    private var inFlightSince: Long = 0L
    private val waiting = ArrayDeque<PendingPrompt<R>>()

    /** 在飞那次的身份（0 = 没有在飞）。`onSaveInstanceState` 靠它把重建后的回调认回来。 */
    val inFlightPromptId: Long get() = synchronized(lock) { inFlight?.id ?: 0L }

    /** 收集器每收到一张提示就登记；只登记不弹（见类 KDoc ①）。 */
    fun submit(prompt: PendingPrompt<R>) {
        val overflowId = synchronized(lock) {
            if (alreadyTracked(prompt.id)) {
                null
            } else {
                waiting.addLast(prompt)
                if (waiting.size > maxQueued) waiting.removeFirst().id else null
            }
        }
        if (overflowId != null) {
            AppLogger.warning(LEDGER_TAG, "launch queue full — dropping prompt id=$overflowId")
        }
        pump()
    }

    /**
     * 系统对话框回调：按**在飞那次**的身份回传结果，然后派发下一张。
     *
     * [decide] 拿到的就是那次 launch 真正请求的权限（不是"最新那张提示"的），旧实现里
     * 被后一次 launch 覆写的那个字段正是这里要钉住的东西。
     */
    fun onResult(decide: (R) -> A) {
        val answered = synchronized(lock) { inFlight.also { inFlight = null } }
        if (answered == null) {
            AppLogger.warning(LEDGER_TAG, "dialog result with no launch in flight — dropped")
        } else if (!deliver(answered.id, decide(answered.request))) {
            // 金库已经换人：这次点击属于一张没人再等的提示，不能算到后来者头上（fail-closed）。
            AppLogger.warning(LEDGER_TAG, "dropping a stale answer for prompt id=${answered.id}")
        }
        pump()
    }

    /**
     * `Activity` 重建后的失效兜底：保存下来的在飞身份只有在**金库确实还在等这张**时才认领
     * —— 认领之后，重建时系统补投的那个回调才会落到正确的询问上。
     *
     * 对不上就不认领：进程死亡后金库是全新的（`pendingAndroidPermission` 也为空），认领一个
     * 永远不会回来的回调只会让后面每一张提示都排在这一槽后面 —— 那才是"永久卡住"。
     *
     * 必须在提示收集器启动**之前**调用，否则收集器会把同一张仍在等应答的提示再登记一次，
     * 从而在旧对话框的结果回来之前弹出第二个对话框。
     *
     * @return true = 认领成功。
     */
    fun restore(promptId: Long): Boolean {
        if (promptId == 0L) return false
        val pending = currentPrompt() ?: return false
        if (pending.id != promptId) return false
        return synchronized(lock) {
            if (inFlight != null) {
                false
            } else {
                inFlight = pending
                inFlightSince = now()
                true
            }
        }
    }

    private fun pump() {
        while (true) {
            val step = synchronized(lock) { step() }
            if (step.releasedId != 0L) {
                AppLogger.warning(
                    LEDGER_TAG,
                    "releasing in-flight prompt id=${step.releasedId} — nobody can receive its result any more",
                )
            }
            for (id in step.droppedIds) {
                AppLogger.warning(LEDGER_TAG, "dropping superseded prompt id=$id from the launch queue")
            }
            val next = step.next ?: return
            try {
                launch(next)
            } catch (t: Throwable) {
                // 有些 OEM 在 Activity 不可见时 launch 会抛；这一槽必须自己收口，否则队列堵死。
                AppLogger.warning(LEDGER_TAG, "launching the system dialog failed: ${t.message}")
                synchronized(lock) { if (inFlight?.id == next.id) inFlight = null }
                deliver(next.id, launchFailedResult)
            }
        }
    }

    /** 锁内：算出这一步该弹哪张（在飞那次还活着就谁都不弹）。 */
    private fun step(): Step<R> {
        val flying = inFlight
        if (flying != null && !inFlightIsDerelict()) return Step(null)
        if (flying != null) inFlight = null
        val liveId = currentPrompt()?.id
        val dropped = mutableListOf<Long>()
        while (waiting.isNotEmpty() && waiting.first().id != liveId) {
            dropped += waiting.removeFirst().id
        }
        val next = waiting.removeFirstOrNull()
        if (next != null) {
            inFlight = next
            inFlightSince = now()
        }
        return Step(next, releasedId = flying?.id ?: 0L, droppedIds = dropped)
    }

    /** 触发者须持有 [lock]：在飞那次是否已经不可能再被任何人需要（见类 KDoc ③）。 */
    private fun inFlightIsDerelict(): Boolean {
        // ① 金库空了：调用方已经超时/取消，这次结果投出去也没有接收方。
        if (currentPrompt() == null) return true
        // ② 已经超过调用方自己的等待预算：即便金库还有人等，那也一定是被更新的询问顶替过的
        //    一张（`SingleFlightSlot` 的旧 id 早已失效），继续占着这一槽只会把后面的提示一起
        //    堵死。注意这一条只在有新提示要派发时才会被求值，不会自起定时器。
        return now() - inFlightSince >= inFlightBudgetMs
    }

    /** 触发者须持有 [lock]。 */
    private fun alreadyTracked(id: Long): Boolean =
        inFlight?.id == id || waiting.any { it.id == id }
}

/**
 * [T-android-prompt-launch-ledger] 系统权限对话框结果的**唯一**判定口径：`all`。
 *
 * 旧实现两处口径不一样：MainActivity 那份收 `results.values.all { it }`，聊天页那份收
 * `perms.any { ... }` —— 后者在多权限请求里只要有一个权限已经授予就会回 GRANTED，
 * 把"只给了一个"当成"全给了"。现在只剩 [AndroidPermissionDialogBridge] 一处调用。
 */
internal object AndroidPermissionOutcome {

    /**
     * 多权限请求只要缺一就是未授予。
     *
     * [results] 是 `RequestMultiplePermissions` 回传的 map，**不能单独当真相**：多个 Android
     * 版本对"已经授予、因此不需要弹框"的权限会回 false，甚至整张 map 是空的。所以每一项都
     * 再查一次实时状态（[isGranted]），两边只要有一边说授予才算这一项通过 —— map 里的 true
     * 是用户刚点的"允许"，实时状态对"没弹框的已授予权限"才是真话；权限的最终执行点仍在系统
     * 调用处。空请求按未授予处理（fail-closed）。
     */
    fun allGranted(
        requested: List<String>,
        results: Map<String, Boolean>,
        isGranted: (String) -> Boolean,
    ): Boolean = requested.isNotEmpty() && requested.all { results[it] == true || isGranted(it) }
}
