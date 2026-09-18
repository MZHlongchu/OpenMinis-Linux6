package com.openminis.app.data

import com.openminis.app.data.repository.MultiAgentSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultiAgentSettingsTest {

    @Test
    fun clampConcurrent_staysInRange() {
        assertEquals(1, MultiAgentSettings.clampConcurrent(0))
        assertEquals(1, MultiAgentSettings.clampConcurrent(-3))
        assertEquals(8, MultiAgentSettings.clampConcurrent(99))
        assertEquals(3, MultiAgentSettings.clampConcurrent(3))
    }

    @Test
    fun trimSelected_capsToMaxAndDedupes() {
        val ids = listOf("a", "b", "a", "c", "d", "")
        assertEquals(listOf("a", "b", "c"), MultiAgentSettings.trimSelected(ids, 3))
        assertEquals(listOf("a"), MultiAgentSettings.trimSelected(ids, 1))
    }

    @Test
    fun pickModelId_prefersRequestedWhenInPool() {
        val pool = listOf("m1", "m2", "m3")
        assertEquals("m2", MultiAgentSettings.pickModelId(pool, "m2", 0))
        assertEquals("m1", MultiAgentSettings.pickModelId(pool, "missing", 0))
        assertEquals("m3", MultiAgentSettings.pickModelId(pool, null, 2))
        assertEquals("m1", MultiAgentSettings.pickModelId(pool, null, 3))
    }

    @Test
    fun pickModelId_emptyPoolReturnsRequestedOrNull() {
        assertEquals("x", MultiAgentSettings.pickModelId(emptyList(), "x", 0))
        assertNull(MultiAgentSettings.pickModelId(emptyList(), null, 0))
    }

    @Test
    fun retainLive_dropsDeletedProviderIdsSoCapFreesUp() {
        val stored = listOf("aa9ff554-gone", "live-a", "live-b")
        val live = setOf("live-a", "live-b", "live-c")
        assertEquals(listOf("live-a", "live-b"), MultiAgentSettings.retainLive(stored, live, 3))
        assertEquals(emptyList<String>(), MultiAgentSettings.retainLive(stored, emptySet(), 3))
    }

    @Test
    fun teamModelNames_omitsStaleIdsInsteadOfPrintingUuids() {
        val stored = listOf("aa9ff554-gone", "live-a")
        val names = mapOf("live-a" to "GPT")
        assertEquals("GPT", MultiAgentSettings.teamModelNames(stored, names))
        assertEquals("the main session model", MultiAgentSettings.teamModelNames(stored, emptyMap()))
    }
}
