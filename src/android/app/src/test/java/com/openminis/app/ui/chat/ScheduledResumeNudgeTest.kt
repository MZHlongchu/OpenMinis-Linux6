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
        // [T-w2-nudge-continue-guard] 这一条原本写成
        //   `gate.trimEnd().endsWith("continue\n                }") || gate.contains("continue")`
        // 前半句钉的是**上游单文件**里那个分支的缩进（收尾花括号 16 空格），本移植的循环体在
        // ChatViewModelAgentLoopExt.kt、nudge 分支的收尾 `}` 只有 12 空格，于是前半句恒为假；
        // 整条断言的实际判据只剩后半句 `gate.contains("continue")` —— 而这是一个近 3000 字符的
        // 回合循环窗口（同一窗口里「空回合重试」那条分支也以 continue 收尾），**恒真**，
        // 「分支不落进回合收尾处理」这件事根本没被守住。
        // 改成先按下一段循环级语句把 nudge 分支切出来，再判它自己的结尾：
        //   会红的情形一：nudge 分支不再以 `continue` 收尾（改成 return / 落进下面那段
        //   「回合结束」的收尾），循环会把这一回合当成正常结束处理；
        //   会红的情形二：分支体的边界锚点不见了（切片会退化成整段窗口——正是要避免的恒真），
        //   所以边界先显式断言存在。
        val nudgeIf = "if (scheduledResumeNudgeOwed && !isEmptyTurn && cleanStop && !userPromptWaiting) {"
        val nextLoopIf = "if (com.openminis.app.agent.jobs.HelperRunner.shouldContinueForSteer("
        assertTrue(
            "the nudge branch must be bounded by the next loop-level block in the cleanStop window",
            gate.contains(nudgeIf) && gate.contains(nextLoopIf),
        )
        val branch = gate.substringAfter(nudgeIf).substringBefore(nextLoopIf)
            // 分支和下一段之间夹着它的说明注释；判「最后一条语句」时只看代码行。
            .lines().dropLastWhile { it.isBlank() || it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertTrue("nudge branch not found in the cleanStop window", branch.isNotBlank())
        assertTrue(
            "the nudge branch must end by continuing the turn loop, not by falling through into the turn-end handling",
            Regex("""continue\s*\}\s*$""").containsMatchIn(branch),
        )
    }
}
