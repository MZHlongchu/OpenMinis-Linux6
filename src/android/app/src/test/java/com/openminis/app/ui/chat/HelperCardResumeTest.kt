package com.openminis.app.ui.chat

import com.openminis.app.SplitSourceScan
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-subagent-card-resume] An interrupted sub agent's card offers a
 * Resume button (iOS HelperBlockView: `isInterrupted` → arrow.clockwise, same
 * slot as Stop), wired to the restart the model's `resume` call uses.
 *
 * "Interrupted" is not a status anything writes: it is a persisted `running`
 * payload whose block is no longer live, i.e. the app stopped mid-run. These
 * pin which cards get the button, and that the button goes through the same
 * slot/queue decision as the model rather than starting past the cap.
 */
class HelperCardResumeTest {

    private fun block(status: String, toolStatus: ToolBlockStatus) = AssistantBlock(
        id = "tool1",
        kind = "tool_use",
        content = """{"status":"$status","child_session_id":"child-1","title":"Research"}""",
        toolName = "subagent_task",
        toolTitle = "Research",
        toolStatus = toolStatus,
    )

    /** The card's condition for the Resume button, from HelperToolBlock. */
    private fun offersResume(b: AssistantBlock) = parseHelperBlock(b).finished?.status == "interrupted"

    @Test
    fun `a run the app lost mid-way is interrupted and offers Resume`() {
        val lost = block("running", ToolBlockStatus.SUCCESS)
        assertEquals("interrupted", parseHelperBlock(lost).finished?.status)
        assertTrue(offersResume(lost))
        assertEquals("child-1", parseHelperBlock(lost).childSessionId)
    }

    @Test
    fun `a live run offers Stop, not Resume`() {
        val live = block("running", ToolBlockStatus.RUNNING)
        assertTrue(parseHelperBlock(live).isRunning)
        assertTrue(!offersResume(live))
    }

    @Test
    fun `finished runs offer nothing to resume`() {
        for (s in listOf("completed", "failed", "cancelled", "timeout")) {
            assertNotEquals(s, true, offersResume(block(s, ToolBlockStatus.SUCCESS)))
        }
    }

    // ── Wiring ────────────────────────────────────────────────────────────

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing ${f.absolutePath}", f.exists())
        return f.readText()
    }

    @Test
    fun `the card shows Resume only for an interrupted run, in Stop's slot`() {
        val ui = src("src/main/java/com/openminis/app/ui/chat/HelperUi.kt")
        val resume = ui.indexOf("info.finished?.status == \"interrupted\" && onResume != null")
        val stop = ui.indexOf("info.isRunning && onStop != null")
        assertTrue(resume in 0 until stop)
        assertTrue("disabled while a resume is pending", ui.contains(".clickable(enabled = !resuming"))
    }

    @Test
    fun `the chat wires the button to the view model and reports queued or refused`() {
        val cs = src("src/main/java/com/openminis/app/ui/chat/ChatScreen.kt")
        assertTrue(cs.contains("viewModel.resumeInterruptedFromCard(item.block)"))
        assertTrue(cs.contains("R.string.helper_resume_queued") && cs.contains("R.string.helper_resume_failed"))
    }

    @Test
    fun `the button takes the same slot or queue decision as the model's resume`() {
        // [T-w2-card-resume-split-scan] 扫描面：卡片续跑这两个入口在本移植里被拆进了
        // ChatViewModelCardResumeExt.kt（同包的 ChatViewModel 一族扩展函数），上游那套
        // 「读 ui/chat/ChatViewModel.kt、再 substringAfter 函数签名」的取法在这里找不到
        // 锚点 —— substringAfter 会静默退化成**整份文件**，下面三条顺序断言随即失去判别
        // 力（indexOf 全落回 -1）。改成按**函数定义**在全树里定位（[SplitSourceScan]，
        // 与 Review0923SubAgentQueueInvariantsTest 同一取法）：签名被改名/搬走会直接报
        // 「没有这个声明」，而不是在空气里变红。三条顺序断言一个字都没有改。
        val body = SplitSourceScan.memberBody("resumeInterruptedFromCard", subdir = "ui/chat")
        val queuedCheck = body.indexOf("isQueuedLive(resumeQueueKey(childId))")
        val interruptedCheck = body.indexOf("interruptedChildIds(activeSessionId)")
        val cap = body.indexOf("canStartChildJob")
        val start = body.indexOf("resumeInterruptedChild(childId)")
        val queue = body.indexOf("enqueueResume(childId)")
        assertTrue("already-queued and not-interrupted are checked first", queuedCheck in 0 until interruptedCheck)
        assertTrue("the cap is checked before starting", interruptedCheck < cap && cap < start)
        assertTrue("a full set of slots queues instead", queue > start)
    }

    /**
     * [T-w2-start-guard] 卡片上的 Start 是队列自己 drain 时用的**同一个入口**，所以那两条
     * 「别启动」的守卫必须留在它里面：参数缺了没得跑；停掉的是一次控制调用
     * （status/steer/cancel/resume）时句柄本身没有任务，`cancel` 更是重放不得 —— 启动它
     * 等于把用户刚撤掉的东西又跑起来。`HelperRunner.isControlOnly` 的判据本身由
     * NeverStartedNotRunningTest 直接测，这里钉的是**调用点**：不满足就返回 false，
     * 让卡片维持原样，而不是报一次没发生的启动。
     *
     * 会红的情形：任一守卫被摘掉（例如只留 `args.isBlank()` 而放过 control-only，
     * 于是「停」按钮的卡片又长出「启动」），或者 Start 不再走 launchCardDelegation
     * 那条与卡片续跑共用的执行入口；函数被改名/搬走时 [SplitSourceScan] 直接报
     * 「没有这个声明」，不会在空气里变绿。
     */
    @Test
    fun `Start refuses a delegation with no arguments and one that is a control call`() {
        val family = SplitSourceScan.memberBody("startNeverStartedDelegation", subdir = "ui/chat")
        // memberBody 的结束锚点要求函数名紧跟 `(`，扩展函数的接收者写法它认不出，
        // 所以这里再按下一段声明的签名收一刀，读到的是这一个函数自己。
        val body = family.substringBefore("fun ChatViewModel.resumeInterruptedFromCard(")
        assertTrue("blank arguments must not start", body.contains("if (args.isBlank()) return false"))
        assertTrue(
            "a control-only call must not start",
            Regex(
                """if \(com\.openminis\.app\.agent\.jobs\.HelperRunner\.isControlOnly\(""" +
                    """args, block\.content\.ifEmpty \{ null \}\)\) \{\s*\n\s*return false""",
            ).containsMatchIn(body),
        )
        assertTrue(
            "the start still goes through the card delegation launcher",
            body.contains("return launchCardDelegation(args, block.id)"),
        )
    }
}
