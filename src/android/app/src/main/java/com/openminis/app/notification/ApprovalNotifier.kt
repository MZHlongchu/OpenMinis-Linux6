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
import kotlinx.coroutines.runBlocking
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
 */
class ApprovalNotifier(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "minis_approval"
        private const val TAG = "ApprovalNotifier"

        // Different requestCode per action so the system doesn't collapse them
        // into a single PendingIntent (which would make approve/deny indistinguishable).
        private const val REQ_APPROVE = 0x1A1
        private const val REQ_DENY    = 0x1B2
        private const val NOTIF_ID    = 0x2C3

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
            nm.notify(NOTIF_ID, notification)
            android.util.Log.d(TAG, "approval notified requestId=$requestId tool=$toolName")
        } catch (se: SecurityException) {
            android.util.Log.w(TAG, "approval notify denied: ${se.message}")
        }
    }

    fun cancelApproval() {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIF_ID)
        } catch (_: Exception) {}
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

    companion object {
        private const val ACTION_APPROVE = "com.openminis.app.APPROVE_TOOL"
        private const val ACTION_DENY    = "com.openminis.app.DENY_TOOL"
        private const val EXTRA_REQUEST_ID = "approval_request_id"
    }
}
