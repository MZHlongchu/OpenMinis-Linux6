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
 * [maxConcurrent] is the hard cap on simultaneous [run_subagent] calls in one
 * turn. [selectedModelEntryIds] is the pool of models those sub-agents may use;
 * its size is always clamped to [maxConcurrent] so the two settings stay linked.
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

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    fun setMaxConcurrent(value: Int) {
        val clamped = MultiAgentSettings.clampConcurrent(value)
        prefs.edit().putInt(KEY_MAX_CONCURRENT, clamped).apply()
        _maxConcurrent.value = clamped
        val trimmed = MultiAgentSettings.trimSelected(_selectedModelEntryIds.value, clamped)
        if (trimmed != _selectedModelEntryIds.value) {
            writeSelectedIds(trimmed)
        }
    }

    fun setSelectedModelEntryIds(ids: List<String>) {
        val trimmed = MultiAgentSettings.trimSelected(ids, _maxConcurrent.value)
        writeSelectedIds(trimmed)
    }

    fun toggleModelEntry(id: String) {
        val current = _selectedModelEntryIds.value
        val next = if (id in current) {
            current.filter { it != id }
        } else {
            MultiAgentSettings.trimSelected(current + id, _maxConcurrent.value)
        }
        writeSelectedIds(next)
    }

    private fun readSelectedIds(): List<String> {
        val raw = prefs.getString(KEY_MODEL_IDS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                }
            }.let { MultiAgentSettings.trimSelected(it, _maxConcurrent.value) }
        } catch (_: Exception) {
            emptyList()
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
        private const val DEFAULT_ENABLED = true
    }
}

object MultiAgentSettings {
    const val MIN_CONCURRENT = 1
    const val MAX_CONCURRENT = 8
    const val DEFAULT_CONCURRENT = 3

    fun clampConcurrent(n: Int): Int = n.coerceIn(MIN_CONCURRENT, MAX_CONCURRENT)

    fun trimSelected(ids: List<String>, max: Int): List<String> {
        val cap = clampConcurrent(max)
        return ids.filter { it.isNotBlank() }.distinct().take(cap)
    }

    /**
     * Pick a model-entry id from the configured pool.
     * Explicit [requested] wins when it is in the pool (or when the pool is empty
     * and a request was given). Otherwise round-robin over the pool.
     */
    fun pickModelId(
        selected: List<String>,
        requested: String?,
        roundRobinIndex: Int,
    ): String? {
        val pool = selected.filter { it.isNotBlank() }.distinct()
        val req = requested?.trim()?.takeIf { it.isNotEmpty() }
        if (req != null) {
            pool.firstOrNull { it.equals(req, ignoreCase = true) }?.let { return it }
            if (pool.isEmpty()) return req
        }
        if (pool.isEmpty()) return null
        return pool[Math.floorMod(roundRobinIndex, pool.size)]
    }
}
