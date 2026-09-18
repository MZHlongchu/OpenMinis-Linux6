package com.openminis.app.share

import com.openminis.app.ui.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCardShareTest {
    @Test
    fun `transcript includes title and roles`() {
        val md = ConversationCardShare.transcript(
            "Demo chat",
            listOf(
                ChatMessage(id = "1", role = "user", content = "hello"),
                ChatMessage(id = "2", role = "assistant", content = "hi there"),
            ),
        )
        assertTrue(md.contains("# Demo chat"))
        assertTrue(md.contains("**User**"))
        assertTrue(md.contains("hello"))
        assertTrue(md.contains("**Assistant**"))
        assertTrue(md.contains("OpenMinis-Linux"))
    }

    @Test
    fun hideToolsStripsSpillAndDropsEmpty() {
        val msgs = listOf(
            ChatMessage(id = "1", role = "user", content = "hi"),
            ChatMessage(
                id = "2",
                role = "assistant",
                content = "[tool-output-spill] shell produced 99999 chars. Full output saved to /var/minis/workspace/tool-spill/x.txt\n\n",
            ),
            ChatMessage(id = "3", role = "assistant", content = "done"),
        )
        val visible = ConversationCardShare.selectVisible(
            msgs,
            ConversationCardOptions(hideTools = true),
        )
        assertEquals(2, visible.size)
        assertFalse(visible.any { it.content.contains("tool-output-spill") })
    }

    @Test
    fun paginateChunksWhenEnabled() {
        val msgs = (1..8).map { ChatMessage(id = "$it", role = "user", content = "m$it") }
        val pages = ConversationCardShare.paginate(msgs, paginate = true)
        assertEquals(2, pages.size)
        assertEquals(6, pages[0].size)
        assertEquals(2, pages[1].size)
    }
}
