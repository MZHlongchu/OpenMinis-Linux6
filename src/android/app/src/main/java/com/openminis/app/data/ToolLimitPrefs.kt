package com.openminis.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User-facing caps for shell timeout, file_read size, and sub-agent turns.
 * SharedPreferences; no Room bump. Unprimed reads return defaults so unit
 * tests and early tool calls stay safe.
 */
object ToolLimitPrefs {
    const val PREFS = "minis_tool_limits"

    const val DEFAULT_SHELL_TIMEOUT_SEC = 600
    const val MIN_SHELL_TIMEOUT_SEC = 30
    const val MAX_SHELL_TIMEOUT_SEC = 1_800
    const val SHELL_STEP_SEC = 30

    const val DEFAULT_FILE_READ_MAX_CHARS = 15_000
    const val MIN_FILE_READ_MAX_CHARS = 2_000
    const val MAX_FILE_READ_MAX_CHARS = 80_000
    const val FILE_CHARS_STEP = 1_000

    const val DEFAULT_FILE_READ_MAX_LINES = 0
    const val MAX_FILE_READ_MAX_LINES = 20_000
    const val FILE_LINES_STEP = 100

    const val DEFAULT_SUBAGENT_MAX_TURNS = 200
    const val MIN_SUBAGENT_MAX_TURNS = 10
    const val MAX_SUBAGENT_MAX_TURNS = 200
    const val TURNS_STEP = 10

    private const val KEY_SHELL = "shellTimeoutSec"
    private const val KEY_CHARS = "fileReadMaxChars"
    private const val KEY_LINES = "fileReadMaxLines"
    private const val KEY_TURNS = "subagentMaxTurns"

    @Volatile private var prefs: android.content.SharedPreferences? = null

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun prime(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _revision.value++
    }

    fun shellTimeoutSec(): Int = clampShell(prefs?.getInt(KEY_SHELL, DEFAULT_SHELL_TIMEOUT_SEC) ?: DEFAULT_SHELL_TIMEOUT_SEC)
    fun fileReadMaxChars(): Int = clampChars(prefs?.getInt(KEY_CHARS, DEFAULT_FILE_READ_MAX_CHARS) ?: DEFAULT_FILE_READ_MAX_CHARS)
    fun fileReadMaxLines(): Int = clampLines(prefs?.getInt(KEY_LINES, DEFAULT_FILE_READ_MAX_LINES) ?: DEFAULT_FILE_READ_MAX_LINES)
    fun subagentMaxTurns(): Int = clampTurns(prefs?.getInt(KEY_TURNS, DEFAULT_SUBAGENT_MAX_TURNS) ?: DEFAULT_SUBAGENT_MAX_TURNS)

    fun setShellTimeoutSec(value: Int) = put(KEY_SHELL, clampShell(value))
    fun setFileReadMaxChars(value: Int) = put(KEY_CHARS, clampChars(value))
    fun setFileReadMaxLines(value: Int) = put(KEY_LINES, clampLines(value))
    fun setSubagentMaxTurns(value: Int) = put(KEY_TURNS, clampTurns(value))

    fun clampShell(value: Int): Int = value.coerceIn(MIN_SHELL_TIMEOUT_SEC, MAX_SHELL_TIMEOUT_SEC)
    fun clampChars(value: Int): Int = value.coerceIn(MIN_FILE_READ_MAX_CHARS, MAX_FILE_READ_MAX_CHARS)
    fun clampLines(value: Int): Int = if (value <= 0) 0 else value.coerceIn(FILE_LINES_STEP, MAX_FILE_READ_MAX_LINES)
    fun clampTurns(value: Int): Int = value.coerceIn(MIN_SUBAGENT_MAX_TURNS, MAX_SUBAGENT_MAX_TURNS)

    fun resolveShellTimeoutSec(requested: Int?): Int {
        val cap = shellTimeoutSec()
        return if (requested == null || requested <= 0) cap else requested.coerceIn(1, cap)
    }

    private fun put(key: String, value: Int) {
        prefs?.edit()?.putInt(key, value)?.apply()
        _revision.value++
    }
}
