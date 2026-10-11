package com.openminis.app.service

import android.util.Log
import com.openminis.app.security.sessionAllowAllSkipsPrompt
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ApprovalGate {
    private const val TAG = "ApprovalGate"
    private const val TIMEOUT_MS = 90_000L
    private val pending = ConcurrentHashMap<String, MutableStateFlow<Boolean?>>()
    private val approvalDetails = ConcurrentHashMap<String, ApprovalRequest>()
    private val sessionAllowAll = ConcurrentHashMap.newKeySet<String>()
    private val sessionAllowedTools = ConcurrentHashMap<String, MutableSet<String>>()
    @Volatile private var legacyBoundSessionId: String? = null

    data class ApprovalRequest(
        val id: String,
        val toolName: String,
        val preview: String,
        val timestamp: Long = System.currentTimeMillis(),
        val sessionId: String? = null,
        /**
         * [T-approval-owner-drift] 该请求归属过的**全部**会话键：归档那一刻的键，
         * 以及每一次 draft→persisted 迁移后的新键（[migrateSession] 只改写
         * [sessionId] 这个路由键，并在这里追加）。已弹出的审批通知 extras 里带的
         * 正是归档键 —— 归属校验按这个集合判定，会话键漂移不会让一次真实批准失效
         * （receiver 丢弃 → 通知消失 → waitFor 挂到 90s 超时回给模型
         * "User REJECTED"）。
         */
        val ownedSessionIds: Set<String> = setOfNotNull(sessionId),
    )

    private fun legacySession(): String? = legacyBoundSessionId

    fun enableSessionAllowAll() { enableSessionAllowAll(legacySession()) }
    fun enableSessionAllowAll(sessionId: String?) {
        sessionId?.let { sessionAllowAll.add(it) }
        Log.i(TAG, "session allow-all enabled session=$sessionId")
    }

    fun allowToolForSession(toolName: String) { allowToolForSession(toolName, legacySession()) }
    fun allowToolForSession(toolName: String, sessionId: String?) {
        val sid = sessionId ?: return
        sessionAllowedTools.computeIfAbsent(sid) { ConcurrentHashMap.newKeySet() }.add(toolName)
    }

    fun isSessionAllowAll(): Boolean = isSessionAllowAll(legacySession())
    fun isSessionAllowAll(sessionId: String?): Boolean = sessionId?.let(sessionAllowAll::contains) == true

    fun isToolAllowedForSession(toolName: String): Boolean =
        isToolAllowedForSession(toolName, legacySession())
    fun isToolAllowedForSession(toolName: String, sessionId: String?): Boolean {
        val sid = sessionId ?: return false
        return sessionAllowAll.contains(sid) || sessionAllowedTools[sid]?.contains(toolName) == true
    }

    fun resetSessionAllowAll() { resetSessionAllowAll(legacySession()) }
    fun resetSessionAllowAll(sessionId: String?) {
        val sid = sessionId ?: return
        sessionAllowAll.remove(sid)
        sessionAllowedTools.remove(sid)
    }

    /** Compatibility default for old APIs; explicit session-aware calls never read this pointer. */
    fun bindSession(sessionId: String?) { legacyBoundSessionId = sessionId }

    private val _pendingApprovals = MutableStateFlow<Map<String, ApprovalRequest>>(emptyMap())
    val pendingApprovals: StateFlow<Map<String, ApprovalRequest>> = _pendingApprovals.asStateFlow()
    private val pendingBySession = ConcurrentHashMap<String, MutableStateFlow<Map<String, ApprovalRequest>>>()
    private val emptySessionApprovals = MutableStateFlow<Map<String, ApprovalRequest>>(emptyMap()).asStateFlow()

    fun pendingApprovals(sessionId: String?): StateFlow<Map<String, ApprovalRequest>> =
        sessionId?.let {
            pendingBySession.computeIfAbsent(it) { sid ->
                MutableStateFlow(approvalDetails.filterValues { request -> request.sessionId == sid })
            }.asStateFlow()
        } ?: emptySessionApprovals

    fun isConfigured(): Boolean = true

    fun requestApproval(): String = requestApproval(legacySession(), "", "", false)

    fun requestApproval(toolName: String, preview: String, mustPrompt: Boolean = false): String =
        requestApproval(legacySession(), toolName, preview, mustPrompt)

    fun requestApproval(sessionId: String?, toolName: String, preview: String, mustPrompt: Boolean = false): String {
        val sid = sessionId
        if (!mustPrompt && isToolAllowedForSession(toolName, sid)) return ""
        if (sessionAllowAllSkipsPrompt(isSessionAllowAll(sid), mustPrompt)) return ""
        val id = UUID.randomUUID().toString()
        pending[id] = MutableStateFlow(null)
        approvalDetails[id] = ApprovalRequest(id, toolName, preview, sessionId = sid)
        refreshPendingBroadcast()
        return id
    }

    /**
     * [T-approval-owner-drift] 归属判据：请求的 id 本身就是权威身份（gate 自产的
     * UUID，只有归档方拿得到），会话键只用来确认"这次调用确实来自请求归属过的
     * 会话"——[ApprovalRequest.ownedSessionIds]（归档键 + 历次迁移后的路由键）
     * 里的任意一个都算数。
     *
     * 这样判定的原因：调用方手里的键是某次读取时的快照（通知 extras 里的是归档
     * 键，站内 UI 读的是当时的路由键），而 [migrateSession] 可能在任何两次读取
     * 之间改写路由键。旧的单键比较因此会在"读键 → 迁移 → 落地"这个交错里产生
     * 假阴性：一次真实批准被静默丢弃。归属集合只增不减，交错不再有假阴性，
     * 「点 A 批准 B」仍被请求 id 挡住（id 与 PendingIntent 的 data 身份同源）。
     *
     * `sessionId == null` 表示调用方只按 id 归属（外部进程入口，如通知 receiver /
     * 前台服务）。
     */
    private fun belongsTo(detail: ApprovalRequest, sessionId: String?): Boolean =
        sessionId == null || detail.sessionId == sessionId || sessionId in detail.ownedSessionIds

    suspend fun waitFor(id: String, sessionId: String? = null): Boolean {
        if (id.isEmpty()) return true
        val detail = approvalDetails[id] ?: return false
        if (!belongsTo(detail, sessionId)) {
            // 显式失败而不是静默 return：这条路径回给模型的是"用户拒绝"，必须可追查。
            Log.w(TAG, "waitFor ownership mismatch id=${id.take(8)}… caller=$sessionId " +
                "owned=${detail.ownedSessionIds} current=${detail.sessionId}")
            return false
        }
        val flow = pending[id] ?: return false
        return try { withTimeout(TIMEOUT_MS) { flow.first { it != null } == true } }
        catch (_: TimeoutCancellationException) { deny(id, sessionId); false }
        catch (_: Exception) { deny(id, sessionId); false }
    }

    /**
     * @return `true` 只有在这种情形才返回：id 存在且归属匹配，本次批准**确实**
     *   落地（请求已从待审批表摘除、等待者被唤醒）。调用方（通知 receiver）
     *   必须据此决定是否清除通知条目，否则一次真实批准会连人带通知一起消失。
     *
     * [T-approval-single-winner] 并发语义：同一请求的并发 approve/deny 里**最多
     *   只有一个**返回 `true`（胜出 = 那一步原子摘除，见 [resolve]）；失败者返回
     *   `false` 且不写值。于是在等待者**仍在场**的常规路径上，调用方看到的 `true`
     *   与等待者收到的结果一致 —— 这是 best-effort，不是绝对保证：等待者可能已经
     *   先一步走完 90s 超时（[waitFor] 的超时分支自己 [deny] 并摘除请求），此时
     *   胜出者拿到的 `true` 只说明"这次点击确实落地过"，而等待者早就收到 `false`；
     *   超时与并发点击同刻还可能各自走到这两条路上。调用方的判据因此只能是
     *   "本次点击是否落地"，不能反推等待者的最终结果。
     */
    fun approve(id: String, sessionId: String? = null): Boolean = resolve(id, true, sessionId)
    fun deny(id: String, sessionId: String? = null): Boolean = resolve(id, false, sessionId)

    /** The filed request for [id], or null — lets a UI act with the session
     *  the request belongs to even when the current session id has drifted
     *  (draft `__new__…` → persisted UUID mid-turn). */
    fun detailOf(id: String): ApprovalRequest? = approvalDetails[id]

    /**
     * [T-draft-approval-key-drift] Re-key every piece of session-scoped gate
     * state when a draft session is persisted (`__new__…` → real UUID):
     * in-flight requests, allow-all and per-tool grants, and the per-session
     * broadcast flows. Without this, an allow-all granted during the draft
     * window stops matching the very next tool call.
     *
     * [T-approval-owner-drift] 改名后旧路由键仍留在
     * [ApprovalRequest.ownedSessionIds] 里 —— 已弹出的审批通知里带的正是旧键，
     * 抹掉它等于让用户手里的按钮失效。
     */
    fun migrateSession(oldSid: String?, newSid: String?) {
        if (oldSid.isNullOrBlank() || newSid.isNullOrBlank() || oldSid == newSid) return
        if (sessionAllowAll.remove(oldSid)) sessionAllowAll.add(newSid)
        sessionAllowedTools.remove(oldSid)?.let { tools ->
            sessionAllowedTools.computeIfAbsent(newSid) { ConcurrentHashMap.newKeySet() }.addAll(tools)
        }
        approvalDetails.keys.toList().forEach { id ->
            approvalDetails.computeIfPresent(id) { _, detail ->
                if (detail.sessionId == oldSid) {
                    detail.copy(sessionId = newSid, ownedSessionIds = detail.ownedSessionIds + newSid)
                } else {
                    detail
                }
            }
        }
        pendingBySession.remove(oldSid)
        refreshPendingBroadcast()
    }

    /**
     * @return `true` 当且仅当本次调用真的把请求从待审批表摘掉并唤醒了等待者。
     *   id 不存在（过期的通知）或归属不符时返回 `false` 且**不做任何修改** ——
     *   调用方据此区分"点击落地了"与"点击被丢弃"，不再无条件清理通知条目。
     *
     * [T-approval-single-winner] 胜出必须**原子**：初查（[belongsTo]）与摘除之间
     *   没有锁，两个真正并发的 approve/deny 可以同时通过初查。旧实现把
     *   `approvalDetails.remove(id)` 的返回值丢掉，于是两边都走到 `return true`；
     *   更糟的是『摘 details → 摘 pending 并写值』这两步可以交错：T1（同意）摘掉
     *   details 后被调度出去，T2（拒绝）摘到仍在表里的 pending flow 并把 `false`
     *   写进去 —— 一次**真实批准**被当成拒绝（等待者 timeout 前就收到 false，
     *   "User REJECTED" 回给模型并终止任务线）。
     *
     *   因此这里的判据就是那一步原子的 `remove`：拿到 `null` 说明并发的另一次
     *   resolve 已经赢走，本次直接失败返回、**绝不继续写值**。值只由胜出者写一次，
     *   所以调用方拿到 `true` 时，等待者不会因为**本次调用**而收到 `false`
     *   （best-effort：等待者仍可能已因 90s 超时先行 [deny] 并摘除请求，见
     *   [waitFor]；那种情况下胜出者的 `true` 只是"点击确实落地过"的记录）。
     */
    private fun resolve(id: String, value: Boolean, sessionId: String? = null): Boolean {
        val detail = approvalDetails[id] ?: return false
        if (!belongsTo(detail, sessionId)) {
            Log.w(TAG, "resolve ownership mismatch id=${id.take(8)}… caller=$sessionId " +
                "owned=${detail.ownedSessionIds} current=${detail.sessionId} value=$value")
            return false
        }
        // 原子摘除 = 唯一的胜出判据（ConcurrentHashMap.remove 原子）。返回 null 表示
        // 并发的另一次 resolve 已经赢走：本次不写 pending、不刷广播，直接失败。
        if (approvalDetails.remove(id) == null) {
            Log.d(TAG, "resolve lost the race id=${id.take(8)}… value=$value — another tap already landed")
            return false
        }
        pending.remove(id)?.value = value
        refreshPendingBroadcast()
        return true
    }

    private fun refreshPendingBroadcast() {
        _pendingApprovals.value = approvalDetails.toMap()
        pendingBySession.forEach { (sid, flow) ->
            flow.value = approvalDetails.filterValues { it.sessionId == sid }
        }
    }

    fun pendingIds(sessionId: String? = null): List<String> = approvalDetails.values
        .filter { sessionId == null || it.sessionId == sessionId }.map { it.id }

    fun cleanupAll(sessionId: String? = null) {
        val ids = pendingIds(sessionId)
        ids.forEach { resolve(it, false, sessionId) }
        if (sessionId == null) {
            approvalDetails.keys.toList().forEach { approvalDetails.remove(it) }
            pending.clear()
            sessionAllowAll.clear()
            sessionAllowedTools.clear()
            legacyBoundSessionId = null
        } else resetSessionAllowAll(sessionId)
        refreshPendingBroadcast()
    }

    fun pendingCount(): Int = pending.size
}
