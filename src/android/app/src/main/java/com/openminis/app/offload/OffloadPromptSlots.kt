package com.openminis.app.offload

import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/**
 * [T-android-permission-prompt-queue] 权限提示的槽位：同一时刻只向 UI 发布一张，
 * 其余 FIFO 排队；应答和取消都做身份校验，只处置"自己那一槽"。
 *
 * 为什么单独成文件：`OffloadPermissionManager.kt` 已经占满架构棘轮的 439 行基线
 * （`.architecture-baseline.json`，只许下调），排队逻辑写在它内部必然超限。
 * 这两个类都不引用 Android API，可以在纯 JVM 单测里直接驱动。
 *
 * 修掉的旧行为（单槽 `Continuation` + 无条件清空）：
 *  ① 后到者覆盖先到者 → 先到者再也没有 resume 来源，永久挂起；
 *  ② 先到者被超时/取消时清空整个槽 → 后到者已经挂上的对话框一起消失，用户即使
 *     想批准也无从批准，只能等自己的超时；
 *  ③ 应答不带身份 → 在屏那张被超时撤下并 promote 下一张之后，用户对旧提示的迟到
 *     点击会落到**下一张**提示上（见 [PendingPrompt]：两个槽类的应答都必须回传身份 id）；
 *  ④ 在屏那张没有占位上限 → 没人应答时把两级队列饿死（见 [ACTIVE_PROMPT_TIMEOUT_MS]）。
 * 触发面真实存在：[PrivacyPermissionPolicy.toolNames] 的隐私工具默认 ASK_ONCE，
 * 离屏的 `android-*` CLI（OffloadGate / SessionsOffloadHandler）与在屏的
 * `SessionLookupTool` 会并发走同一个提示槽。
 */

/** 排队上限：超出即 fail-closed，避免 agent 重试循环把队列堆成无界内存。 */
internal const val MAX_QUEUED_PROMPTS: Int = 8

/**
 * 排队中的询问最多等多久才轮到自己上屏（[OffloadPromptQueue.await]）。
 *
 * 30s：够用户读完并点掉眼前那张提示，又不会让排队的人被前面那张无限期堵住；
 * 带 `OffloadGate`/handler 预算的调用方会先被自己的 15s 预算取消。
 */
internal const val QUEUED_PROMPT_TIMEOUT_MS: Long = 30_000L

/**
 * [T-android-prompt-starvation] 在屏那张询问最多占着槽多久，到点退位（[OffloadPromptQueue.await]）。
 *
 * 排队的那一层有 [QUEUED_PROMPT_TIMEOUT_MS]，"轮到自己之后没人应答"此前却没有上限：
 * 无预算的调用方（在屏的 `SessionLookupTool` → `SessionAccessPolicy` →
 * `checkPermission`）会一直挂在在屏那张上，而这张提示只在聊天页被 compose ——
 * 用户切走以后根本没人能应答它，后面的每个调用方都只能耗光自己的排队预算再
 * fail-closed，提示槽等于被永久占住。
 *
 * 120s 与系统对话框 / 设置页那两个人工预算（`SYSTEM_DIALOG_TIMEOUT_MS`）同量级：
 * 够读完再点；到点则撤下这一槽并 promote 下一张，调用方拿到的是"没人应答"
 * （`null` → `PromptDecision.UNANSWERED`），不是"用户拒绝"。
 */
internal const val ACTIVE_PROMPT_TIMEOUT_MS: Long = 120_000L

private const val TAG = "OffloadPermission"

/**
 * [T-android-prompt-identity] 一次询问：内容 + 只在这次询问内有效的身份。
 *
 * UI 必须把 [id] 一起回传给 [OffloadPromptQueue.respond] / [SingleFlightSlot.respond]。
 * 理由：提示被调用方超时撤下、队首被 promote 上屏之后，用户手里那个旧对话框才点的
 * "允许"带的是旧 [id]，身份不匹配就该丢弃 —— 否则它会落到用户没读过的那张提示上
 * （或落到已经占槽的后来者身上），让那个工具白拿 ALLOW_SESSION（fail-open）。
 */
internal data class PendingPrompt<R : Any>(val id: Long, val request: R)

/**
 * [T-android-prompt-identity] ASK_ONCE 询问的三值结果。
 *
 * 为什么不再是 `Boolean`：`false` 把「用户/策略拒绝」（[DENIED]）和「根本没人应答」
 * （[UNANSWERED]：排队超时或队列已满，提示压根没上屏）混成一种，而这两件事在审计
 * （`prompt_unanswered=true`）和错误码（`interaction_required` / 124 vs
 * `PERMISSION_DENIED` / 126）上必须分得开。调用方自己的预算耗尽用 `null` 表达，
 * 与 [UNANSWERED] 一样按「没人应答」处理 —— 判据是 `decision == null ||
 * decision.isUnanswered`（只有 [isAllowed] 才放行）。
 */
enum class PromptDecision {
    ALLOWED,
    DENIED,
    UNANSWERED;

    /** 只有拿到明确的 ALLOWED 才算授权（`null` 超时、[UNANSWERED] 都不算）。 */
    val isAllowed: Boolean get() = this == ALLOWED

    /** 提示被队列丢弃（排队超时 / 队列满）——没人看过这张提示，不是用户拒绝。 */
    val isUnanswered: Boolean get() = this == UNANSWERED
}

/**
 * FIFO 提示队列：一个在屏、其余排队，逐个得到各自的答复。
 *
 * 两个预算：轮到之前是 [queuedTimeoutMs]（排队超时），轮到之后是 [activeTimeoutMs]
 * （没人应答就退位，见 [ACTIVE_PROMPT_TIMEOUT_MS]）。两条路径都 fail-closed。
 *
 * @param R 请求类型（要展示给用户的提示）。
 * @param A 应答类型（用户在 UI 上给出的决定）。
 */
internal class OffloadPromptQueue<R : Any, A : Any>(
    /** 排队等待上屏的上限，见 [QUEUED_PROMPT_TIMEOUT_MS]。 */
    private val queuedTimeoutMs: Long = QUEUED_PROMPT_TIMEOUT_MS,
    /** 队列长度上限，见 [MAX_QUEUED_PROMPTS]。 */
    private val maxQueued: Int = MAX_QUEUED_PROMPTS,
    /** 日志里怎么称呼一个请求。 */
    private val label: (R) -> String = { it.toString() },
    /** 在屏占位上限，见 [ACTIVE_PROMPT_TIMEOUT_MS]。 */
    private val activeTimeoutMs: Long = ACTIVE_PROMPT_TIMEOUT_MS,
) {
    /** 一次询问：要么是 UI 上那张（[active]），要么在 [waiting] 里排队。 */
    private inner class Slot(val id: Long, val request: R) {
        /** 用户在对话框里给出的应答；由 [respond] 完成。 */
        val answer = CompletableDeferred<A>()

        /** 本槽被发布到 UI（轮到自己）时完成。 */
        val visible = CompletableDeferred<Unit>()
    }

    private val lock = Any()

    /** 询问身份发号器；只在 [lock] 内自增，因此每个槽的 id 唯一。 */
    private var nextId = 0L
    private var active: Slot? = null
    private val waiting = ArrayDeque<Slot>()

    private val _pending = MutableStateFlow<PendingPrompt<R>?>(null)

    /**
     * UI 上当前那张询问（null = 没有提示在等用户）。应答时把 [PendingPrompt.id]
     * 一起回传，见 [respond]。
     */
    val pending: StateFlow<PendingPrompt<R>?> = _pending.asStateFlow()

    /**
     * 发布 [request] 并等待用户应答。
     *
     * @return 用户的应答；null = 没拿到应答（排队超时 / 队列已满 / 上屏后无人应答），
     *   调用方必须 fail-closed，不能当作同意。
     */
    suspend fun await(request: R): A? {
        val slot = synchronized(lock) {
            val candidate = Slot(++nextId, request)
            when {
                active == null -> {
                    publish(candidate)
                    candidate
                }
                waiting.size >= maxQueued -> null
                else -> {
                    waiting.addLast(candidate)
                    candidate
                }
            }
        }
        // 队列已满：没有槽可以等，fail-closed（elvis 形式让下面的 admitted 一定非空）。
        val admitted = slot ?: run {
            AppLogger.warning(TAG, "prompt queue full — refusing ${label(request)}")
            return null
        }
        return try {
            // 已经上屏的那张 `visible` 早已完成，这里是立即返回。
            if (withTimeoutOrNull(queuedTimeoutMs) { admitted.visible.await() } == null) {
                AppLogger.warning(
                    TAG,
                    "queued prompt for ${label(request)} never got its turn " +
                        "in ${queuedTimeoutMs}ms — failing closed",
                )
                null
            } else {
                // [T-android-prompt-starvation] 轮到自己之后仍然可能"根本没人应答"：
                // 到点退位（finally 里的 withdraw 会 promote 下一张），调用方拿到
                // null = 没人应答，而不是被前面那张永久挂住。
                val answered = withTimeoutOrNull(activeTimeoutMs) { admitted.answer.await() }
                if (answered == null) {
                    AppLogger.warning(
                        TAG,
                        "on-screen prompt for ${label(request)} went unanswered in " +
                            "${activeTimeoutMs}ms — withdrawing it (failing closed)",
                    )
                }
                answered
            }
        } finally {
            // 幂等：已被 [respond] 撤下时是空操作；取消/排队超时/在屏超时都由这里撤下，
            // 并把排队的下一张提升上来（旧实现只会把别人的槽一起清掉）。
            withdraw(admitted)
        }
    }

    /**
     * UI 侧应答；必须带上它看到的提示的身份 [requestId]（[PendingPrompt.id]）。
     *
     * @return true = 这次应答被受理；false = 身份不匹配（提示早已被超时/取消撤下并
     *   promote 了下一张，或这是同一次点击的重复回调）→ 丢弃。不校验身份就等于让
     *   用户没读过的那张提示白拿 ALLOW_SESSION（fail-open）。
     */
    fun respond(requestId: Long, answer: A): Boolean {
        val slot = synchronized(lock) {
            val current = active
            if (current == null || current.id != requestId) {
                null
            } else {
                active = null
                _pending.value = null
                promote()
                current
            }
        }
        if (slot == null) {
            AppLogger.warning(TAG, "dropping answer for stale prompt id=$requestId")
            return false
        }
        // 锁外投递：complete 会唤醒调用方协程，不应在锁内运行它。
        slot.answer.complete(answer)
        return true
    }

    /** 发布 [slot] 为 UI 上那张。调用者须持有 [lock]。 */
    private fun publish(slot: Slot) {
        active = slot
        _pending.value = PendingPrompt(slot.id, slot.request)
        slot.visible.complete(Unit)
    }

    /** 提升队首。调用者须持有 [lock]。 */
    private fun promote() {
        waiting.removeFirstOrNull()?.let { publish(it) }
    }

    private fun withdraw(slot: Slot) {
        synchronized(lock) {
            if (active !== slot) {
                // 还在排队：只把自己摘掉，别动别人的槽。
                waiting.remove(slot)
            } else {
                active = null
                _pending.value = null
                promote()
            }
        }
    }
}

/**
 * 单槽询问（系统运行时权限对话框、"去设置页"对话框这类一次只能有一个的流程）。
 *
 * 仍然是后到者占槽——系统一次只弹得出一个对话框——但**取消只清自己那一槽**：
 * 旧实现无条件清空 `_pending*` / continuation，先到者被超时取消时会把后来者刚
 * 挂上的对话框一起抹掉，用户即使想批准也无从批准。
 *
 * 调用方负责加预算（例如 `withTimeoutOrNull`），超时会走 [await] 的取消路径，
 * 槽位由 [clearIfMine] 自行清理。
 *
 * [T-android-prompt-identity] 这一层和 [OffloadPromptQueue] 一样带**应答身份**：
 * UI 必须把它看到的那张询问的身份（[PendingPrompt.id]）一起回传给 [respond]，
 * 身份不匹配即丢弃。不校验身份时，"先到者被撤销、后到者已占槽"会让用户对旧对话框的
 * 迟到应答 resume 后到者（用户答 A、B 拿到 GRANTED）—— fail-open。
 */
internal class SingleFlightSlot<R : Any, A : Any> {
    private val lock = Any()
    private var continuation: Continuation<A>? = null

    /** 在屏那张的身份；0L = 没有槽（id 从 1 开始发，见 [await]）。 */
    private var activeId: Long = 0L

    /** 身份发号器；只在 [lock] 内自增，因此每个槽的 id 唯一。 */
    private var nextId: Long = 0L

    private val _pending = MutableStateFlow<PendingPrompt<R>?>(null)

    /**
     * UI 上当前那张询问（null = 没有提示在等用户）。应答时把 [PendingPrompt.id]
     * 一起回传，见 [respond]。
     */
    val pending: StateFlow<PendingPrompt<R>?> = _pending.asStateFlow()

    /** 发布 [request] 并等待用户在 UI 上应答。 */
    suspend fun await(request: R): A = suspendCancellableCoroutine<A> { cont ->
        synchronized(lock) {
            val id = ++nextId
            continuation = cont
            activeId = id
            _pending.value = PendingPrompt(id, request)
        }
        cont.invokeOnCancellation { clearIfMine(cont) }
    }

    /**
     * UI 侧应答；必须带上它看到的提示的身份 [requestId]（[PendingPrompt.id]）。
     *
     * @return true = 这次应答被受理；false = 身份不匹配（提示已被超时/取消撤下，
     *   槽可能已经换人，或这是同一次点击的重复回调）→ 丢弃。
     */
    fun respond(requestId: Long, answer: A): Boolean {
        val cont = synchronized(lock) {
            val current = continuation
            if (current == null || activeId != requestId) {
                null
            } else {
                continuation = null
                activeId = 0L
                _pending.value = null
                current
            }
        }
        if (cont == null) {
            AppLogger.warning(TAG, "dropping answer for stale single-flight prompt id=$requestId")
            return false
        }
        // 锁外投递：resume 会唤醒调用方协程。
        cont.resume(answer)
        return true
    }

    /** 只在槽仍属于 [cont] 时清空（旧实现无条件清空会误伤后来者）。 */
    private fun clearIfMine(cont: Continuation<A>) {
        synchronized(lock) {
            if (continuation === cont) {
                continuation = null
                activeId = 0L
                _pending.value = null
            }
        }
    }
}
