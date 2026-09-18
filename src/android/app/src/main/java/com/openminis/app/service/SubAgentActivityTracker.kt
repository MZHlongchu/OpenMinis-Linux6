package com.openminis.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Live roster of concurrent spawn_agent members for the chat status bar.
 * UI shows 子代理 i/N plus turn and the in-flight tool.
 */
object SubAgentActivityTracker {

    enum class Status { RUNNING, SUCCESS, FAILED }

    data class Member(
        val id: String,
        val parentSessionId: String,
        val title: String,
        val role: String?,
        val model: String?,
        val status: Status,
        val lastStep: String = "",
        val error: String? = null,
        val index: Int = 0,
        val total: Int = 0,
        val kind: String? = null,
        val turnIndex: Int = 0,
        val turnCap: Int = 0,
        val currentTool: String = "",
    )

    private val _members = MutableStateFlow<List<Member>>(emptyList())
    val members: StateFlow<List<Member>> = _members.asStateFlow()

    fun start(
        parentSessionId: String,
        title: String,
        role: String?,
        model: String?,
        index: Int = 0,
        total: Int = 0,
        kind: String? = role,
        turnCap: Int = 0,
    ): String {
        val id = UUID.randomUUID().toString()
        val member = Member(
            id = id,
            parentSessionId = parentSessionId,
            title = title,
            role = role,
            model = model,
            status = Status.RUNNING,
            index = index,
            total = total,
            kind = kind,
            turnCap = turnCap,
        )
        _members.value = _members.value + member
        return id
    }

    fun updateStep(id: String, step: String) {
        _members.value = _members.value.map { m ->
            if (m.id == id) m.copy(lastStep = step) else m
        }
    }

    fun updateProgress(id: String, turn: Int, cap: Int, tool: String) {
        val step = buildString {
            append("turn $turn/$cap")
            if (tool.isNotBlank()) append(" · $tool")
        }
        _members.value = _members.value.map { m ->
            if (m.id == id) {
                m.copy(
                    lastStep = step,
                    turnIndex = turn,
                    turnCap = cap,
                    currentTool = tool,
                )
            } else {
                m
            }
        }
    }

    fun finish(id: String, success: Boolean, error: String? = null) {
        val status = if (success) Status.SUCCESS else Status.FAILED
        _members.value = _members.value.map { m ->
            if (m.id == id) m.copy(status = status, error = error) else m
        }
    }

    fun clearSession(parentSessionId: String) {
        _members.value = _members.value.filter { it.parentSessionId != parentSessionId }
    }

    fun membersFor(parentSessionId: String): List<Member> =
        _members.value.filter { it.parentSessionId == parentSessionId }
}
