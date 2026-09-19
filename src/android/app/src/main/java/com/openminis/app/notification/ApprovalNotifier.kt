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
 *   companion [cancelApproval] after forwarding the result to [ApprovalGate].
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

        // Different requestCode per action so the system doesn't collapse them
        // into a single PendingIntent (which would make approve/deny indistinguishable).
        private const val REQ_APPROVE = 0x1A1
        private const val REQ_DENY    = 0x1B2
        private const val NOTIF_BASE = 0x2C300

        fun notificationId(requestId: String): Int = NOTIF_BASE xor requestId.hashCode()

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
     */
    fun notifyApproval(requestId: String, toolName: String, preview: String) {
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
                setPackage(context.packageName) // explicit package so the PendingIntent resolves reliably
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
