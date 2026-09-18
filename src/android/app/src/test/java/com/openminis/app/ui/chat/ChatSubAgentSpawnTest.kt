package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatSubAgentSpawnTest {

    @Test
    fun missingPromptIsNull() {
        assertNull(parseSubAgentSpawn("""{"kind":"worker"}""", 12))
        assertNull(parseSubAgentSpawn("not-json", 12))
    }

    @Test
    fun parsesKindAndClampsTurns() {
        val spawn = parseSubAgentSpawn(
            """{"prompt":"do it","kind":"explore","max_turns":99,"role":"search"}""",
            12,
        )!!
        assertEquals("do it", spawn.prompt)
        assertEquals("explore", spawn.kind)
        assertEquals(12, spawn.maxTurns)
        assertEquals("search", spawn.role)
    }
}

class ChatToolTitlesTest {
    @Test
    fun knownAndSnakeCase() {
        assertEquals("Sub-agent", friendlyToolTitle("run_subagent"))
        assertEquals("Write File", friendlyToolTitle("file_write"))
        assertEquals("Foo Bar", friendlyToolTitle("foo_bar"))
    }
}
