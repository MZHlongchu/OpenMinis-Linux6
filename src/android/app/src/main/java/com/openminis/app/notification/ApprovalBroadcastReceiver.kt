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
 * Registered in the manifest as an exported broadcast receiver so the system
 * can deliver the notification action even when the app process is backgrounded.
 */
class ApprovalBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ApprovalBR"
        // Must match the companion in ApprovalNotifier — kept in sync by
        // convention rather than shared constant (receiver cannot import the
        // same class from the manifest).
        private const val ACTION_APPROVE = "com.openminis.app.APPROVE_TOOL"
        private const val ACTION_DENY    = "com.openminis.app.DENY_TOOL"
        private const val EXTRA_REQUEST_ID = "approval_request_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_REQUEST_ID)
        when (intent.action) {
            ACTION_APPROVE -> {
                if (id != null) {
                    ApprovalGate.approve(id)
                    Log.d(TAG, "received approve for id=${id.take(8)}…")
                }
            }
            ACTION_DENY -> {
                if (id != null) {
                    ApprovalGate.deny(id)
                    Log.d(TAG, "received deny for id=${id.take(8)}…")
                }
            }
        }
        // Always clear the notification — even if the id is missing, stale
        // notifications don't help anyone.
        ApprovalNotifier(context).cancelApproval()
    }
}
