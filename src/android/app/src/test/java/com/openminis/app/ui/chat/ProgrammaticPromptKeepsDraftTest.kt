package com.openminis.app.ui.chat

import com.openminis.app.SplitSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-programmatic-prompt-keeps-draft] A prompt nobody typed (a
 * scheduled fire, a sub-agent callback, the sessions CLI) must never touch the
 * composer draft (iOS 2a06de66a).
 *
 * Android's headless path already carried its text as an argument, but the
 * pre-send context check's auto-compact branch cleared `_inputText`
 * unconditionally, so a background prompt that tripped the compact threshold
 * erased what the user was typing.
 *
 * [T-A5-split-scan] 上游把整条 send 漏斗放在单体的
 * `src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt` 里；本移植把
 * `sendMessage` 拆了出去（预发送上下文检查的两个分支随之离开那个文件），而拆分仍在
 * 收敛。所以窗口按**锚点所在的文件**取（[SplitSourceScan.fileContaining]：锚点唯一，
 * 落到哪个文件都能读到同一段代码）；`java.io.File` + 写死路径的取法一旦搬家就报
 * 「文件不存在」，那不是断言要表达的意思。断言与期望一个字没改。
 */
class ProgrammaticPromptKeepsDraftTest {

    private fun branch(start: String, end: String) =
        SplitSourceScan.fileContaining(start).substringAfter(start).substringBefore(end)

    @Test
    fun `the auto-compact branch clears the composer only for the user's own send`() {
        val compactBranch = branch(
            "PreSendContextAction.COMPACT_THEN_SEND -> {",
            "PreSendContextAction.ASK_USER -> {",
        )
        assertTrue(compactBranch.contains("if (!headless) _inputText.value = \"\""))
        assertFalse(
            "an unconditional clear is back",
            compactBranch.lines().any { it.trim() == "_inputText.value = \"\"" },
        )
        assertTrue(compactBranch.contains("pendingSendHeadless = headless"))
    }

    @Test
    fun `the send after compaction stays headless`() {
        val compactAndSend = branch("fun compactAndSendPending(", "fun sendPendingWithoutCompacting(")
        assertTrue(compactAndSend.contains("sendMessage(text, skipContextCheck = true, headless = headless, prefill = prefill)"))
        val sendWithout = branch("fun sendPendingWithoutCompacting(", "fun cancelCompactBeforeSend(")
        assertTrue(sendWithout.contains("headless = headless"))
    }

    @Test
    fun `cancel never puts a background prompt into the composer`() {
        // [T-A5-split-scan] 结束锚点不再写死可见性：这个枚举在本移植里是 `internal`
        // （上游是 `private`），锚点写错会让窗口一路拉到文件末尾 —— 断言照样绿，却
        // 不再是「只看这一段」。改成 `enum class InLoopContextAction` 后窗口重新收紧，
        // 断言不动。
        val cancel = branch("fun cancelCompactBeforeSend(", "enum class InLoopContextAction")
        assertTrue(cancel.contains("if (!pendingSendHeadless) pendingSendText?.let { _inputText.value = it }"))
    }

    @Test
    fun `a headless prompt still refuses the ask-user dialog before touching anything`() {
        val askBranch = branch(
            "PreSendContextAction.ASK_USER -> {",
            "_showCompactBeforeSendPrompt.value = true",
        )
        val refuse = askBranch.indexOf("if (headless) return SubmitOutcome.Rejected(\"context_near_capacity\")")
        val clear = askBranch.indexOf("_inputText.value = \"\"")
        assertTrue(refuse >= 0 && clear > refuse)
    }
}
