package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-scheduled-resume-nudge] Port of iOS c9131e862's resume nudge: after
 * a scheduled fire inserted between tool calls is answered, one hidden
 * <system-reminder> continues the interrupted task.
 */
class ScheduledResumeNudgeTest {

    /**
     * [T-a7-package-surface] 扫描面：上游把整条循环放在单文件
     * `ui/chat/ChatViewModel.kt` 里，本移植按职责拆成了 `ChatViewModel.kt` 加几十个
     * `ChatViewModelXxxExt.kt`（循环体在 `ChatViewModelAgentLoopExt.kt`，`cleanStop`
     * 闸门与 `Auto-title after first exchange` 都在那里）。这条用例断言的是"循环里
     * 的那段 nudge 逻辑"，所以按**整个包面**读。断言文本一字未改。
     */
    private val vm by lazy { ProductionSources.readPackageSurface("ui/chat") }

    @Test fun `nudge text is the iOS text verbatim and hidden`() {
        val ios = File("../../ios/Agent/Chat/ChatStore.swift").takeIf { it.exists() }?.readText()
        if (ios != null) {
            val iosText = ios.substringAfter("static let scheduledResumeNudgeText =").substringAfter("\"").substringBefore("\"\n")
            assertTrue("iOS: $iosText", iosText == ChatMessage.SCHEDULED_RESUME_NUDGE_TEXT)
        }
        assertTrue(ChatMessage.SCHEDULED_RESUME_NUDGE_TEXT.startsWith("<system-reminder>"))
    }

    @Test fun `owed only after a scheduled insert, cleared by a user batch`() {
        assertTrue(vm.contains("var scheduledResumeNudgeOwed = false"))
        assertTrue(vm.contains("scheduledResumeNudgeOwed = scheduledFire != null"))
    }

    @Test fun `sent once, on a clean stop with content, not when a user prompt waits`() {
        val gate = vm.substringAfter("val cleanStop = turnFinishReason == \"stop\" || turnFinishReason == \"end_turn\"")
            .substringBefore("// Auto-title after first exchange")
        assertTrue(gate.contains("if (scheduledResumeNudgeOwed && !isEmptyTurn && cleanStop && !userPromptWaiting) {"))
        assertTrue(gate.contains("scheduledResumeNudgeOwed = false"))
        assertTrue(gate.contains("chatRepository.appendMessage(nudgeSid, \"user\""))
        assertTrue(gate.trimEnd().endsWith("continue\n                }") || gate.contains("continue"))
    }
}
