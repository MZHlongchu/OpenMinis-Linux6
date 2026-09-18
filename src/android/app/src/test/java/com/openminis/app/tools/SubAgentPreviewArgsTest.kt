package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentPreviewArgsTest {

    @Test
    fun prefersCommandPathQueryUrl() {
        assertEquals("ls -la", SubAgentRunner.previewToolArgs("""{"command":"ls -la"}"""))
        assertEquals("/tmp/a.txt", SubAgentRunner.previewToolArgs("""{"path":"/tmp/a.txt"}"""))
        assertEquals("openminis", SubAgentRunner.previewToolArgs("""{"query":"openminis"}"""))
        assertEquals("https://example.com", SubAgentRunner.previewToolArgs("""{"url":"https://example.com"}"""))
    }

    @Test
    fun truncatesLongArgs() {
        val long = "c".repeat(200)
        val preview = SubAgentRunner.previewToolArgs("""{"command":"$long"}""")
        assertEquals(80, preview.length)
        assertTrue(preview.all { it == 'c' })
    }
}
