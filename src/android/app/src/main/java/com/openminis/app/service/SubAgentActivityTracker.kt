package com.openminis.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Live roster of concurrent spawn_agent members for the chat status bar.
 * UI shows 子代理 i/N plus turn and the in-flight tool.
 *
 * A member exists only for as long as it is RUNNING: [finish] removes the
 * entry outright. There is deliberately no terminal state — the transcript
 * that mattered is already on the tool result and folded into the process
 * summary before the entry goes away, so a sticky SUCCESS/FAILED chip carried
 * no information and read as "still going".
 */
object SubAgentActivityTracker {

    data class Member(
        val id: String,
        val parentSessionId: String,
        val title: String,
        val role: String?,
        val model: String?,
        val lastStep: String = "",
        val error: String? = null,
        val index: Int = 0,
        val total: Int = 0,
        val kind: String? = null,
        val turnIndex: Int = 0,
        val turnCap: Int = 0,
        val currentTool: String = "",
        val transcript: String = "",
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

    /**
     * Mark [id] finished and REMOVE it from the roster.
     *
     * The member used to be kept around with a SUCCESS/FAILED status, so
     * `members` never emptied and the live bar stayed on screen after the whole
     * run was over — including after every sub-agent had failed. The bar is a
     * LIVE indicator: a stuck-on chip reads as "still running" and there is
     * nothing left to tap into.
     *
     * The transcript is what survives, and it is already carried on the tool
     * result and folded into the process summary via [combinedTranscript]
     * before this point, so dropping the roster entry loses nothing.
     */
    /**
     * Drop [id] from the roster.
     *
     * [success] and [error] are accepted for call-site readability and carry no
     * state: the outcome is recorded on the tool result by the caller, and this
     * object is a live view only. Keeping them would leave two parameters that
     * look meaningful and change nothing.
     */
    fun finish(id: String, success: Boolean, error: String? = null) {
        _members.value = _members.value.filterNot { it.id == id }
    }

    fun appendLog(id: String, line: String) {
        if (line.isBlank()) return
        synchronized(this) {
            _members.value = _members.value.map { m ->
                if (m.id != id) m else {
                    val next = if (m.transcript.isEmpty()) line else m.transcript + "\n" + line
                    val clipped = if (next.length > 40_000) next.takeLast(32_000) else next
                    m.copy(transcript = clipped)
                }
            }
        }
    }

    fun combinedTranscript(parentSessionId: String): String {
        val list = membersFor(parentSessionId)
        if (list.isEmpty()) return ""
        if (list.size == 1) {
            val m = list.first()
            val body = m.transcript.ifBlank { m.lastStep }
            return if (m.error.isNullOrBlank()) body else body.trimEnd() + "\nerror: ${m.error}"
        }
        return list.joinToString("\n\n") { m ->
            buildString {
                append("## ")
                if (m.index > 0 && m.total > 0) append("子代理 ${m.index}/${m.total}")
                else append(m.title)
                m.kind?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                append(" · running")
                append('\n')
                val body = m.transcript.ifBlank { m.lastStep }
                if (body.isNotBlank()) append(body.trimEnd())
                m.error?.takeIf { it.isNotBlank() }?.let {
                    append("\nerror: ").append(it)
                }
            }
        }
    }

    fun clearSession(parentSessionId: String) {
        _members.value = _members.value.filter { it.parentSessionId != parentSessionId }
    }

    fun membersFor(parentSessionId: String): List<Member> =
        _members.value.filter { it.parentSessionId == parentSessionId }
}
