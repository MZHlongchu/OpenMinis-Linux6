package com.openminis.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Live roster of concurrent [run_subagent] members for the chat status bar.
 * ChatViewModel publishes start/finish; ChatScreen renders chips.
 */
object SubAgentActivityTracker {

    enum class Status { RUNNING, SUCCESS, FAILED }

    data class Member(
        val id: String,
        val parentSessionId: String,
        val title: String,
        val role: String?,
        val model: String,
        val status: Status,
        val startedAtMs: Long,
        val finishedAtMs: Long? = null,
        val error: String? = null,
    )

    private val _members = MutableStateFlow<List<Member>>(emptyList())
    val members: StateFlow<List<Member>> = _members.asStateFlow()

    private val pruneAfterMs = 45_000L
    private val lastPruneAt = AtomicLong(0L)

    fun start(
        parentSessionId: String,
        title: String,
        role: String?,
        model: String,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        pruneLocked(nowMs)
        val id = UUID.randomUUID().toString()
        val member = Member(
            id = id,
            parentSessionId = parentSessionId,
            title = title.ifBlank { "Sub-agent" },
            role = role,
            model = model,
            status = Status.RUNNING,
            startedAtMs = nowMs,
        )
        _members.value = _members.value + member
        return id
    }

    fun finish(id: String, success: Boolean, error: String? = null, nowMs: Long = System.currentTimeMillis()) {
        _members.value = _members.value.map { m ->
            if (m.id != id) m
            else m.copy(
                status = if (success) Status.SUCCESS else Status.FAILED,
                finishedAtMs = nowMs,
                error = error,
            )
        }
        pruneLocked(nowMs)
    }

    fun clearSession(parentSessionId: String) {
        _members.value = _members.value.filterNot { it.parentSessionId == parentSessionId }
    }

    fun membersFor(parentSessionId: String, nowMs: Long = System.currentTimeMillis()): List<Member> {
        pruneLocked(nowMs)
        return _members.value.filter { it.parentSessionId == parentSessionId }
    }

    private fun pruneLocked(nowMs: Long) {
        if (nowMs - lastPruneAt.get() < 2_000L) return
        lastPruneAt.set(nowMs)
        _members.value = _members.value.filter { m ->
            m.status == Status.RUNNING ||
                (m.finishedAtMs != null && nowMs - m.finishedAtMs < pruneAfterMs)
        }
    }
}
