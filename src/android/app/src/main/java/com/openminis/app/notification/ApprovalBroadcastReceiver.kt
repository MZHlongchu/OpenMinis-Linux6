package com.openminis.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.openminis.app.service.ApprovalGate

/**
 * Receives the approve/deny button taps from the approval notification and
 * forwards them to [ApprovalGate] so the waiting tool dispatch coroutine wakes up.
 *
 * Registered in the manifest as a non-exported broadcast receiver — the system
 * CAN still deliver notification-action PendingIntents to it because
 * [PendingIntent.getBroadcast] explicitly targets this component class, which
 * overrides the exported flag for explicit intents. The action strings are
 * app-internal (not exported to other apps), so keeping this receiver
 * non-exported is safe and prevents spoofed approval intents.
 */
class ApprovalBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Reuse the exact same constants that ApprovalNotifier uses to build
        // the action intents — single source of truth via the shared companion.
        val id = intent.getStringExtra(ApprovalNotifier.EXTRA_REQUEST_ID) ?: return
        // [T-approval-owner-drift] 归属校验的第二道仍然用通知里登记的会话快照，
        // 但快照不再有一票否决权：gate 认的是「请求 id + 它归属过的会话键」
        // （归档时刻的 origin 与当前路由键）。draft→persisted 迁移
        // (ApprovalGate.migrateSession) 只改写路由键，快照仍落在 origin 上，
        // 所以用户点"同意"不会再被静默丢弃；两个并发审批之间「点 A 批准 B」
        // 仍被 id + PendingIntent 的 data 身份挡住
        // （T-approval-pendingintent-identity）。
        val snapshot = intent.getStringExtra(ApprovalNotifier.EXTRA_SESSION_ID)
        val tapLanded = when (intent.action) {
            ApprovalNotifier.ACTION_APPROVE -> {
                Log.d(TAG, "received approve for id=${id.take(8)}…")
                ApprovalGate.approve(id, snapshot)
            }
            ApprovalNotifier.ACTION_DENY -> {
                Log.d(TAG, "received deny for id=${id.take(8)}…")
                ApprovalGate.deny(id, snapshot)
            }
            else -> false
        }
        // Clear the bar entry only when the tap landed, or when the request is
        // already gone (a stale notification whose buttons wake nothing). If a
        // still-pending request rejected this tap we keep the notification and
        // say so — never swallow a real approval. See
        // ApprovalNotifier.shouldClearEntryAfterTap and its clearing contract.
        val stillPending = ApprovalGate.detailOf(id) != null
        if (ApprovalNotifier.shouldClearEntryAfterTap(tapLanded, stillPending)) {
            ApprovalNotifier.cancelApproval(context, id)
        } else {
            Log.w(
                TAG,
                "tap not applied while request id=${id.take(8)}… is still pending " +
                    "(action=${intent.action}); keeping the notification so the user can retry",
            )
        }
    }

    companion object {
        private const val TAG = "ApprovalBR"
    }
}
