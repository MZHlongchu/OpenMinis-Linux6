package com.openminis.app.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ApprovalGate: tracks pending tool-call approvals across process boundaries.
 *
 * ## Contract
 *
 * 1. Call `requestApproval` to reserve a slot and get an id.
 * 2. Call `approve(id, approved)` or `deny(id)` when the user taps the
 *    notification button — this resolves the slot and wakes the waiting
 *    coroutine (or discards it if already expired).
 * 3. Any pending call that outlives [TIMEOUT_MS] auto-deny's on next poll.
 *
 * ## Thread safety
 *
 * All public methods are safe to call from the FGS main thread (notification
 * callbacks) and IO threads (tool dispatch). Uses a ConcurrentHashMap and
 * a mutex-free design: the wait uses `withTimeout` + cancellation instead
 * of a blocking lock.
 *
 * ## Lifecycle
 *
 * Entries are auto-removed after resolution or timeout, so this object
 * can live on the process for its entire lifetime without memory leak.
 */
object ApprovalGate {

    private const val TAG = "ApprovalGate"

    /** Timeout per approval request in milliseconds. Users are slow but 90s is generous. */
    private const val TIMEOUT_MS = 90_000L

    /** Pending approvals keyed by id. Values are MutableStateFlow<Boolean?> — null means unresolved. */
    private val pending = ConcurrentHashMap<String, MutableStateFlow<Boolean?>>()

    fun isConfigured(): Boolean = true // gate always available; callers decide whether to use it

    /**
     * Register a pending approval request. Returns an opaque id to pass to
     * [approve] / [deny]. Call this from the tool-dispatch thread BEFORE
     * posting the notification so the user can respond in parallel.
     */
    fun requestApproval(): String {
        val id = UUID.randomUUID().toString()
        pending[id] = MutableStateFlow(null as Boolean?)
        Log.d(TAG, "approval requested id=$id")
        return id
    }

    /**
     * Wait for a decision on [id] with [TIMEOUT_MS] deadline.
     * @return true if approved, false if denied/expired.
     */
    suspend fun waitFor(id: String): Boolean {
        val flow = pending[id] ?: return false
        return try {
            withTimeout(TIMEOUT_MS) {
                flow.first { it != null } == true
            }
        } catch (e: TimeoutCancellationException) {
            Log.i(TAG, "approval ${id.take(8)}... timed out → denied")
            deny(id)
            false
        } catch (e: Exception) {
            Log.w(TAG, "approval wait threw: ${e.message}")
            deny(id)
            false
        }
    }

    /** Called when the user taps "Approve" on the notification. */
    fun approve(id: String) {
        resolve(id, true)
        Log.d(TAG, "approved id=$id")
    }

    /** Called when the user taps "Deny" on the notification. */
    fun deny(id: String) {
        resolve(id, false)
        Log.d(TAG, "denied id=$id")
    }

    private fun resolve(id: String, value: Boolean) {
        val flow = pending[id] ?: return
        if (flow.value != null) return // already resolved — ignore duplicate tap
        flow.value = value
        pending.remove(id)
    }

    /** Returns the number of currently pending approvals (for diagnostics). */
    fun pendingCount(): Int = pending.size
}
