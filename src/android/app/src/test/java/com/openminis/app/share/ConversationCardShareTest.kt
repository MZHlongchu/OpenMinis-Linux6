package com.openminis.app.share

import com.openminis.app.ui.chat.ChatMessage
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
}
