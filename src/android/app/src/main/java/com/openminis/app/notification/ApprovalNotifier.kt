package com.openminis.app.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.openminis.app.R
import com.openminis.app.service.ApprovalGate

/**
 * Posts a one-shot "needs approval" notification for a sensitive tool call.
 * This is distinct from the ongoing agent-status notification — it sits at
 * the top of the shade with two action buttons (approve / deny) and
 * disappears once the user picks one or the timeout fires.
 *
 * Designed as a companion to [ApprovalGate]: call
 * `requestApproval` first to get an id, post this notification, then
 * `waitFor(id)` to block the tool dispatch until the user responds.
 *
 * If POST_NOTIFICATIONS is not granted, this silently no-ops (the tool
 * dispatch path should fall back to running the tool or returning a
 * user-error message).
 *
 * ## Notification clearing contract
 *
 * The approval notification is cleared on every resolution path:
 * - **Approve / Deny tap**: [ApprovalBroadcastReceiver.onReceive] calls the
 *   companion [cancelApproval] after forwarding the result to [ApprovalGate] —
 *   but only when the tap actually landed (or the request is already gone);
 *   see [shouldClearEntryAfterTap]. A live request whose tap was rejected keeps
 *   its buttons rather than vanishing with the user's approval.
 * - **Timeout**: [ApprovalGate.waitFor] returns `false`; callers in
 *   `ChatViewModel` must call [cancelApproval] after a `false` result so the
 *   stale buttons are removed. (The [ApprovalBroadcastReceiver] fallback —
 *   see AgentForegroundService ACTION_APPROVE/DENY branch — also clears it
 *   when an intent is routed to the service instead of the receiver.)
 *
 * The clearing responsibility is intentionally on the caller side for timeout:
 * [ApprovalGate] is process-local and cannot reach the notification manager,
 * so it cannot clear the bar on its own. [ApprovalBroadcastReceiver] clears
 * it on user tap because it runs in the broadcast path with a `Context`.
 */
class ApprovalNotifier(private val context: Context) {

    companion object {
        private const val TAG = "ApprovalNotifier"
        const val CHANNEL_ID = "minis_approval"

        const val ACTION_APPROVE = "com.openminis.app.APPROVE_TOOL"
        const val ACTION_DENY    = "com.openminis.app.DENY_TOOL"
        const val EXTRA_REQUEST_ID = "approval_request_id"
        // [T-approval-pendingintent-identity] 请求归属的会话，随按钮一起下发：
        // receiver 用它调 ApprovalGate.approve(id, sessionId) 做第二道归属校验。
        // 注意这是**发通知那一刻的快照**，不是一票否决的判据：draft→persisted
        // 迁移（ApprovalGate.migrateSession）会改写请求的路由键，gate 因此按
        // 「请求的 id + 它归属过的会话键（origin 或当前路由键）」判定归属，
        // 快照过期不会让一次真实批准失效。
        const val EXTRA_SESSION_ID = "approval_session_id"

        // Different requestCode per action so the system doesn't collapse them
        // into a single PendingIntent (which would make approve/deny indistinguishable).
        private const val REQ_APPROVE = 0x1A1
        private const val REQ_DENY    = 0x1B2
        private const val NOTIF_BASE = 0x2C300

        fun notificationId(requestId: String): Int = NOTIF_BASE xor requestId.hashCode()

        /**
         * [T-approval-pendingintent-identity] 按钮 PendingIntent 的 data，唯一随
         * [requestId] 变化、且参与 `Intent.filterEquals` 的成分。PendingIntent 的身份是
         * `(requestCode, filterEquals)`，而 filterEquals 只看 action/data/type/component/
         * categories —— **extras 不在其中**：两个并发审批若共用身份，后注册者的 extras
         * 会覆盖前者，点"同意"A 实际批准 B。requestId 是 app 自产的 UUID（无 URI 非法
         * 字符），因此这个映射是单射。纯字符串实现，便于 JVM 单测钉住该不变量。
         */
        fun approvalActionDataPath(requestId: String): String = "minis://approval/$requestId"

        /**
         * Creates the notification channel [CHANNEL_ID] if it hasn't been created yet.
         * Safe to call from any thread before posting the approval notification.
         */
        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = ContextCompat.getSystemService(ctx, NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            mgr.createNotificationChannel(
                android.app.NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.notif_approval_channel_name),
                    android.app.NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = ctx.getString(R.string.notif_approval_channel_desc)
                    setShowBadge(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            )
        }

        /**
         * [T-approval-tap-must-land] 一次按钮点击之后，通知条目到底该不该清。
         *
         * 只有两种情况可以清：点击**真的落地**了（gate 把请求从待审批表摘掉并
         * 唤醒了等待者），或者请求已经不在待审批表里（过期通知，按钮唤醒不了
         * 任何东西，留着只会误导）。
         *
         * 反过来，"请求还挂着、但这次点击被判归属不符" **必须留着按钮**：那正是
         * B14 回归的现场——receiver 无条件 cancel，用户点"同意"被静默丢弃、通知
         * 消失，SecurityGateHolder.waitFor 一直等到 90s 超时再把 "User REJECTED"
         * 回给模型，一次真实批准被当成拒绝并终止任务线。宁可让按钮留着重试，
         * 也不能把用户的选择吞掉。
         */
        fun shouldClearEntryAfterTap(tapLanded: Boolean, requestStillPending: Boolean): Boolean =
            tapLanded || !requestStillPending

        /**
         * Cancels the approval notification by [NOTIF_ID].
         *
         * **Clearing responsibility**: this is the single method that removes
         * the notification bar entry on every resolution path. It is called:
         *  - from [ApprovalBroadcastReceiver.onReceive] after a user tap
         *    (the receiver reaches the notification manager directly).
         *  - from ChatViewModel when [ApprovalGate.waitFor] returns `false`
         *    (timeout path) — ApprovalGate is process-local and cannot
         *    clear the notification itself, so the caller MUST call this.
         *
         * Passing a `Context` (rather than relying on the instance holder)
         * lets the caller invoke it from the receiver without constructing a
         * full ApprovalNotifier.
         */
        fun cancelApproval(ctx: Context, requestId: String) {
            try {
                NotificationManagerCompat.from(ctx).cancel(notificationId(requestId))
            } catch (_: Exception) { /* nothing to cancel */ }
        }

        /**
         * Cancels every approval notification whose id is in [requestIds].
         *
         * Used on the service teardown paths (ACTION_STOP / ACTION_INTERRUPT):
         * [ApprovalGate.cleanupAll] denies the waiting flows but cannot reach
         * the notification manager, so the caller snapshots
         * `ApprovalGate.pendingIds()` first, cleans up, then clears the bar
         * entries here. An empty/missing collection is a no-op.
         */
        fun cancelAll(ctx: Context, requestIds: Collection<String>) {
            if (requestIds.isEmpty()) return
            val nm = NotificationManagerCompat.from(ctx)
            requestIds.forEach { id ->
                try {
                    nm.cancel(notificationId(id))
                } catch (_: Exception) { /* nothing to cancel */ }
            }
        }
    }

    /**
     * Post a persistent approval notification with approve/deny buttons.
     * @param requestId  id returned by [com.openminis.app.service.ApprovalGate.requestApproval]
     * @param toolName   human-readable tool name for the notification title
     * @param preview    short tool input preview (e.g. first 200 chars)
     * @param sessionId  session the request belongs to (see [EXTRA_SESSION_ID]);
     *   may be null when the caller has no session binding.
     */
    fun notifyApproval(requestId: String, toolName: String, preview: String, sessionId: String? = null) {
        ensureChannel(context)
        val nm = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nm.areNotificationsEnabled()) {
            return
        }
        val launchIntent = buildLaunchIntent()

        fun actionPending(approved: Boolean): PendingIntent {
            val intent = Intent(context, ApprovalBroadcastReceiver::class.java).apply {
                action = if (approved) ACTION_APPROVE else ACTION_DENY
                putExtra(EXTRA_REQUEST_ID, requestId)
                putExtra(EXTRA_SESSION_ID, sessionId)
                setPackage(context.packageName) // explicit package so the PendingIntent resolves reliably
                // [T-approval-pendingintent-identity] PendingIntent 的身份是
                // (requestCode, Intent.filterEquals)，而 filterEquals **不含 extras**。
                // 两条并发审批原先共用 action+reqCode+组件，第二次 getBroadcast 命中的
                // 是同一个 PendingIntentRecord，FLAG_UPDATE_CURRENT 把 extras 覆盖成后
                // 注册者的 requestId —— 两条通知的按钮指向同一个 id，点"同意"A 实际批准 B。
                // data 参与 filterEquals：每个 requestId 一个独立身份，按钮各归其主。
                data = Uri.parse(approvalActionDataPath(requestId))
            }
            val reqCode = if (approved) REQ_APPROVE else REQ_DENY
            return PendingIntent.getBroadcast(
                context, reqCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("需要审批: $toolName")
            .setContentText(preview.take(200).ifBlank { "Agent 请求执行敏感操作" })
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(launchIntent)
            .addAction(android.R.drawable.ic_menu_send, "同意", actionPending(true))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "拒绝", actionPending(false))
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            nm.notify(notificationId(requestId), notification)
            android.util.Log.d(TAG, "approval notified requestId=$requestId tool=$toolName")
        } catch (se: SecurityException) {
            android.util.Log.w(TAG, "approval notify denied: ${se.message}")
        }
    }

    private fun buildLaunchIntent(): PendingIntent {
        val deepLink = Uri.parse("minis://chat")
        val launch = Intent(Intent.ACTION_VIEW, deepLink).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
