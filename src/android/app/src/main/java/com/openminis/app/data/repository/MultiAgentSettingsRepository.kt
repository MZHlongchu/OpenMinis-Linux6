package com.openminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * Persistence for multi-agent parallel dispatch.
 *
 * [maxConcurrent] is the hard cap on simultaneous spawn_agent members in one
 * turn. [selectedModelEntryIds] is a slot list of that same length: slot i is
 * the model for the i-th concurrent sub-agent. Empty slots reuse the main
 * session model. Duplicates are allowed so two teammates can share a model.
 */
class MultiAgentSettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _maxConcurrent = MutableStateFlow(
        MultiAgentSettings.clampConcurrent(
            prefs.getInt(KEY_MAX_CONCURRENT, MultiAgentSettings.DEFAULT_CONCURRENT),
        ),
    )
    val maxConcurrent: StateFlow<Int> = _maxConcurrent.asStateFlow()

    private val _selectedModelEntryIds = MutableStateFlow(readSelectedIds())
    val selectedModelEntryIds: StateFlow<List<String>> = _selectedModelEntryIds.asStateFlow()

    private val _subagentMaxTurns = MutableStateFlow(
        MultiAgentSettings.clampTurns(
            prefs.getInt(KEY_SUBAGENT_MAX_TURNS, MultiAgentSettings.DEFAULT_SUBAGENT_TURNS),
        ),
    )
    val subagentMaxTurns: StateFlow<Int> = _subagentMaxTurns.asStateFlow()

    private val _subagentMaxAttempts = MutableStateFlow(
        MultiAgentSettings.clampAttempts(
            prefs.getInt(KEY_SUBAGENT_MAX_ATTEMPTS, MultiAgentSettings.DEFAULT_SUBAGENT_ATTEMPTS),
        ),
    )
    val subagentMaxAttempts: StateFlow<Int> = _subagentMaxAttempts.asStateFlow()

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    fun setMaxConcurrent(value: Int) {
        val clamped = MultiAgentSettings.clampConcurrent(value)
        prefs.edit().putInt(KEY_MAX_CONCURRENT, clamped).apply()
        _maxConcurrent.value = clamped
        val resized = MultiAgentSettings.resizeSlots(_selectedModelEntryIds.value, clamped)
        if (resized != _selectedModelEntryIds.value) {
            writeSelectedIds(resized)
        }
    }

    fun setSubagentMaxTurns(value: Int) {
        val clamped = MultiAgentSettings.clampTurns(value)
        prefs.edit().putInt(KEY_SUBAGENT_MAX_TURNS, clamped).apply()
        _subagentMaxTurns.value = clamped
    }

    fun setSubagentMaxAttempts(value: Int) {
        val clamped = MultiAgentSettings.clampAttempts(value)
        prefs.edit().putInt(KEY_SUBAGENT_MAX_ATTEMPTS, clamped).apply()
        _subagentMaxAttempts.value = clamped
    }

    fun setSelectedModelEntryIds(ids: List<String>) {
        writeSelectedIds(MultiAgentSettings.resizeSlots(ids, _maxConcurrent.value))
    }

    fun setSlotModel(index: Int, id: String) {
        writeSelectedIds(
            MultiAgentSettings.setSlot(
                _selectedModelEntryIds.value,
                index,
                id,
                _maxConcurrent.value,
            ),
        )
    }

    /**
     * Blank ids whose providers were deleted or hidden. Slot positions stay
     * stable so sub-agent 2 does not inherit sub-agent 1's leftover pick.
     */
    fun retainLiveEntries(liveIds: Set<String>) {
        val next = MultiAgentSettings.retainLive(
            _selectedModelEntryIds.value,
            liveIds,
            _maxConcurrent.value,
        )
        if (next != _selectedModelEntryIds.value) writeSelectedIds(next)
    }

    private fun readSelectedIds(): List<String> {
        val raw = prefs.getString(KEY_MODEL_IDS, null) ?: return MultiAgentSettings.resizeSlots(
            emptyList(),
            _maxConcurrent.value,
        )
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    add(arr.optString(i))
                }
            }.let { MultiAgentSettings.resizeSlots(it, _maxConcurrent.value) }
        } catch (_: Exception) {
            MultiAgentSettings.resizeSlots(emptyList(), _maxConcurrent.value)
        }
    }

    private fun writeSelectedIds(ids: List<String>) {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        prefs.edit().putString(KEY_MODEL_IDS, arr.toString()).apply()
        _selectedModelEntryIds.value = ids
    }

    companion object {
        private const val PREFS_NAME = "multi_agent_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_MAX_CONCURRENT = "max_concurrent"
        private const val KEY_MODEL_IDS = "model_entry_ids"
        private const val KEY_SUBAGENT_MAX_TURNS = "subagent_max_turns"
        private const val KEY_SUBAGENT_MAX_ATTEMPTS = "subagent_max_attempts"
        private const val DEFAULT_ENABLED = true
    }
}

object MultiAgentSettings {
    const val MIN_CONCURRENT = 1
    const val MAX_CONCURRENT = 8
    const val DEFAULT_CONCURRENT = 3
    const val MIN_SUBAGENT_TURNS = 1
    const val MAX_SUBAGENT_TURNS = 60
    const val DEFAULT_SUBAGENT_TURNS = 60
    const val MIN_SUBAGENT_ATTEMPTS = 1
    const val MAX_SUBAGENT_ATTEMPTS = 5
    const val DEFAULT_SUBAGENT_ATTEMPTS = 3

    fun clampConcurrent(n: Int): Int = n.coerceIn(MIN_CONCURRENT, MAX_CONCURRENT)

    fun clampTurns(n: Int): Int = n.coerceIn(MIN_SUBAGENT_TURNS, MAX_SUBAGENT_TURNS)

    fun clampAttempts(n: Int): Int = n.coerceIn(MIN_SUBAGENT_ATTEMPTS, MAX_SUBAGENT_ATTEMPTS)

    /**
     * Pad or truncate [ids] to [max] slots. Empty strings are kept (unassigned
     * → main session model). Duplicates are kept so two slots can share a model.
     */
    fun resizeSlots(ids: List<String>, max: Int): List<String> {
        val cap = clampConcurrent(max)
        return (0 until cap).map { i -> ids.getOrNull(i).orEmpty() }
    }

    fun setSlot(ids: List<String>, index: Int, id: String, max: Int): List<String> {
        val slots = resizeSlots(ids, max).toMutableList()
        if (index in slots.indices) slots[index] = id
        return slots
    }

    /** Keep slot positions; blank ids that are no longer in [liveIds]. */
    fun retainLive(stored: List<String>, liveIds: Set<String>, max: Int): List<String> {
        return resizeSlots(
            stored.map { id -> if (id.isNotBlank() && id in liveIds) id else "" },
            max,
        )
    }

    /**
     * Labels for the system-prompt "Team models:" line. Unassigned / stale
     * slots read as the main session model instead of raw UUIDs.
     */
    fun teamModelNames(stored: List<String>, liveNamesById: Map<String, String>): String {
        if (stored.isEmpty() || stored.all { id -> liveNamesById[id].isNullOrBlank() }) {
            return "the main session model"
        }
        return stored.mapIndexed { i, id ->
            val name = liveNamesById[id]?.takeIf { it.isNotBlank() } ?: "the main session model"
            "sub-agent ${i + 1}=$name"
        }.joinToString(", ")
    }

    /**
     * Pick a model-entry id from the configured slots.
     * Explicit [requested] wins when it matches a filled slot (or when every
     * slot is empty). Otherwise the i-th concurrent spawn uses slot i;
     * an empty slot means the main session model (`null`).
     */
    fun pickModelId(
        selected: List<String>,
        requested: String?,
        roundRobinIndex: Int,
    ): String? {
        val slots = selected
        val assigned = slots.filter { it.isNotBlank() }
        val req = requested?.trim()?.takeIf { it.isNotEmpty() }
        if (req != null) {
            assigned.firstOrNull { it.equals(req, ignoreCase = true) }?.let { return it }
            if (assigned.isEmpty()) return req
        }
        if (slots.isEmpty()) return req
        val slot = slots[Math.floorMod(roundRobinIndex, slots.size)]
        return slot.takeIf { it.isNotBlank() }
    }
}
