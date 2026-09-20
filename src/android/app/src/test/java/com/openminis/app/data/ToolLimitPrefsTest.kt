package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolLimitPrefsTest {

    @Test
    fun clampsStayInRange() {
        assertEquals(ToolLimitPrefs.MIN_SHELL_TIMEOUT_SEC, ToolLimitPrefs.clampShell(1))
        assertEquals(ToolLimitPrefs.MAX_SHELL_TIMEOUT_SEC, ToolLimitPrefs.clampShell(99_000))
        assertEquals(600, ToolLimitPrefs.clampShell(600))
        assertEquals(0, ToolLimitPrefs.clampLines(0))
        assertEquals(0, ToolLimitPrefs.clampLines(-4))
        assertEquals(100, ToolLimitPrefs.clampLines(50))
        assertEquals(ToolLimitPrefs.MAX_FILE_READ_MAX_LINES, ToolLimitPrefs.clampLines(99_999))
        assertEquals(ToolLimitPrefs.MIN_SUBAGENT_MAX_TURNS, ToolLimitPrefs.clampTurns(1))
        assertEquals(ToolLimitPrefs.MAX_SUBAGENT_MAX_TURNS, ToolLimitPrefs.clampTurns(9_000))
    }

    @Test
    fun unprimedShellTimeoutUsesDefaultCap() {
        assertEquals(ToolLimitPrefs.DEFAULT_SHELL_TIMEOUT_SEC, ToolLimitPrefs.resolveShellTimeoutSec(null))
        assertEquals(30, ToolLimitPrefs.resolveShellTimeoutSec(30))
        assertEquals(ToolLimitPrefs.DEFAULT_SHELL_TIMEOUT_SEC, ToolLimitPrefs.resolveShellTimeoutSec(9_000))
    }
}
