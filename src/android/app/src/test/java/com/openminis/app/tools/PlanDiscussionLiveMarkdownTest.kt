package com.openminis.app.tools

import org.junit.Assert.assertTrue
import org.junit.Test

class PlanDiscussionLiveMarkdownTest {

    @Test
    fun liveMarkdownIncludesStatusTaskAndBoard() {
        val md = PlanDiscussionOrchestrator.liveMarkdown(
            status = "第 1/3 轮",
            userText = "fix the storage scanner hang",
            board = "User request:\nfix the storage scanner hang\n",
        )
        assertTrue(md.contains("计划讨论（进行中）"))
        assertTrue(md.contains("**状态：** 第 1/3 轮"))
        assertTrue(md.contains("fix the storage scanner hang"))
        assertTrue(md.contains("User request:"))
    }

    @Test
    fun discussionMarkdownKeepsFullBoard() {
        val board = """
User request:
fix hang

### Proposal (main)
try A

### Round 1 · critic (skeptic)
refute A

### Synthesis (main)
do B
""".trimIndent()
        val md = PlanDiscussionOrchestrator.discussionMarkdown(board)
        assertTrue(md.contains("计划讨论（主会话 × 子 Agent）"))
        assertTrue(md.contains("### Round 1 · critic (skeptic)"))
        assertTrue(md.contains("### Synthesis (main)"))
        assertTrue(md.contains("do B"))
    }
}
