package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import com.openminis.app.SplitSourceScan
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review 2026-09-23 — source invariants for the sub agent job lifecycle and
 * the queued-prompt hand-off inside ChatViewModel.
 *
 * Guards: af10e3af8 (run summary stamped BEFORE registry.finish, because
 * finish builds the callback synchronously), 06b7ad2e8 + bf74eae2a
 * (RUNNING must be backed by a live job — which only holds if every job is
 * eventually finished), 955e76d6a (mid-task queued message lost across the
 * inject path's suspend points).
 *
 * The functions involved are private suspend members of a ViewModel that
 * needs a Context, Room and a provider, so, like QueuedPromptInjectRaceTest
 * and EvictionGuardTest, the wiring is pinned as source facts.
 *
 * [T-A5-split-scan] 本移植把上游那一个 ChatViewModel.kt 拆成了
 * ChatViewModelXxxExt.kt 一族（同包，且拆分仍在收敛），委派执行也走自己的批次
 * 执行器。所以四个用例的**查找面**改成「按函数定义在全树里定位」
 * （[SplitSourceScan]），**不变式与断言强度一条没减**；每一处与上游的差异都在方法
 * 上写明为什么、对应上游哪一段。
 */
class Review0923SubAgentQueueInvariantsTest {

    /** Body of `fun <name>(`, wherever the refactor has parked it. */
    private fun body(name: String): String = SplitSourceScan.memberBody(name)

    // ---- af10e3af8: summary before finish ----------------------------------

    /**
     * Every NON-failure `registry.finish` of a delegated run must be preceded
     * by `setSummaryLine` for the same job, or the completion callback card is
     * built without its "tools …" subline (finish -> runThen is synchronous;
     * the completion hook stamps the summary later on IO).
     *
     * [T-A5-split-scan] 上游有三个 finish 点（wait / background / scheduled），
     * 都在 ChatViewModel.kt 与 ScheduledAgentRunner.kt 里，所以原来写死这两个文件
     * 并期望 `checked >= 3`。本移植的委派生命周期**不经过 AgentJobRegistry**：
     * 卡片与子代理由 SubAgentBatchRegistry + SubAgentActivityTracker 驱动
     * （ChatViewModelSubAgentExt.kt），AgentJobRegistry 的 job 只剩定时任务那一条
     * （ScheduledAgentRunner），于是这两个文件里只有一个 finish 点。
     *
     * 不变式照旧一条不少，而且查得更宽：遍历整棵 src/main，**任何**新增的
     * `registry.finish(` 都会被「前面 8 行内必须有 setSummaryLine」覆盖；数量期望
     * 改成 >= 1 并点名现在由谁产生这些 job —— 上游那 3 个点缩减成 1 个是架构差异，
     * 不是把断言放宽到「一个都没有也能过」。
     */
    @Test
    fun `every successful finish of a delegated run stamps the summary first`() {
        val files = ProductionSources.allKotlinFiles()
        var checked = 0
        for (f in files) {
            val lines = f.readLines()
            lines.forEachIndexed { i, line ->
                if (!line.contains("registry.finish(")) return@forEachIndexed
                val call = lines.subList(i, minOf(i + 3, lines.size)).joinToString(" ")
                if (call.contains("AgentJobState.FAILED") || call.contains("AgentJobState.CANCELLED")) return@forEachIndexed
                checked++
                val before = lines.subList(maxOf(0, i - 8), i).joinToString("\n")
                assertTrue(
                    "${f.name}:${i + 1} finishes a run without setSummaryLine just before it",
                    before.contains("setSummaryLine("),
                )
            }
        }
        assertTrue(
            "expected at least the scheduled path (AgentJobRegistry is fed only by " +
                "ScheduledAgentRunner in this port; found $checked)",
            checked >= 1,
        )
    }

    // ---- resume path never closes its job ------------------------------------

    /**
     * A resumed sub agent run must be driven to a TERMINAL state.
     *
     * 上游（ChatViewModel.kt:2153-2312）：`resumeInterruptedChild` 注册一个
     * AgentJobRegistry job、写 `resumeNotice()`、submitPrompt，然后**必须**等 child
     * 空闲（`isStreaming.first { !it }`）并 `setSummaryLine` + `registry.finish`——
     * 少了这段收尾，job 会 RUNNING 一辈子：占着一个 MAX_CONCURRENT_CHILD_JOBS 名额、
     * 父会话永远拿不到最后的 <agent_callback>、`hasWorkInFlight()` 永真（VM 缓存
     * 再也逐不出）、卡片转圈不停 —— 正是 06b7ad2e8 消掉的那个空转，被另一条路径
     * 重新造了出来。
     *
     * [T-A5-split-scan] 本移植的续跑**不注册 job**：卡片上的 Resume 走
     * resumeInterruptedFromCard → resumeInterruptedChild → launchCardDelegation →
     * executeRunSubAgent（批次执行器），这条路上没有 AgentJobRegistry 的 job，所以
     * 「job 永远 RUNNING」这个 bug 在本移植不存在。原意图（续跑必须跑到终态）改钉在
     * **它会收尾的那条执行入口**上，并要求得二选一：「注册了 job 就必须关掉它，
     * 否则就别注册、把续跑交给会跑完的执行器」——两条都不满足才算违规。
     */
    @Test
    fun `a resumed sub agent run is finished when the child goes idle`() {
        val b = body("resumeInterruptedChild")
        // 上游这一段以 resumeNotice() 为标志；本移植从父块恢复参数再重开一次委派，
        // 对应的标志是 delegateBlockForChild()。两者都只用来确认取到的是续跑路径。
        assertTrue(
            "sanity: this is the resume path",
            b.contains("resumeNotice()") || b.contains("delegateBlockForChild("),
        )
        val registersJob = b.contains("registry.register(") || b.contains("AgentJobRegistry.register(")
        val closesJob = b.contains("isStreaming.first { !it }") ||
            b.contains("startBackgroundHelper(") ||
            b.contains("setCompletionHook(")
        // [T-A5-split-scan] 本移植的收尾点：交给批次执行器并 await 到整批结束
        // （executeRunSubAgent 不传 background 时跑完才返回，lane/batch 台账与
        // SubAgentActivityTracker 在同一个协程尾部收口）。
        val runsToTerminal = b.contains("launchCardDelegation(")
        assertTrue(
            "resumeInterruptedChild must close its job when the child finishes " +
                "(or not register one at all and instead run the resume to completion " +
                "through the batch executor); today it does neither",
            if (registersJob) closesJob else runsToTerminal,
        )
    }

    // ---- 955e76d6a sibling: dequeue before persist ---------------------------

    /**
     * The queue is emptied only after `appendMessage` returns.
     *
     * `injectQueuedPromptsAsNewTurn` used to empty `_promptQueue` on entry and only
     * persist the combined user row after four suspension points (ensureSession,
     * prepareUserAttachments, buildPastedParts, appendMessage) — the same ~5 s
     * window 955e76d6a measured. If the user taps Stop inside that window, streamJob
     * is cancelled at the next suspension; `cancelStream` then finds `_promptQueue`
     * empty, so `resumeQueueAfterCancel` never runs. The queued message is neither
     * sent nor persisted, and its dashed "queued" bubble stays on screen until the
     * session is reopened, where it silently disappears. cancelStream's own contract
     * (T189) is that queued prompts survive a Stop.
     *
     * [T-A5-split-scan] 上游把这两个函数都放进 ChatViewModel.kt
     * （injectQueuedPromptsAsNewTurn 8680-8910、drainQueuedPrompts 8912-…，修复见
     * 8821-8824 / 8929-8936 / 8977-8995）；本移植把 drain 侧的物化逻辑改名
     * `materializeQueuedPromptsAsUserTurn`（drain 与本移植自己的 retryLast 共用），
     * 函数体也随拆分搬到了各自的文件。函数按定义定位，**被钉住的顺序不变**：在这
     * 两个函数里，`chatRepository.appendMessage(` 必须出现在 `_promptQueue` 按 id
     * 清队之前，或者取消路径必须把队列放回去。
     */
    @Test
    fun `the mid-task inject path cannot lose queued prompts to a Stop`() {
        for (name in listOf("injectQueuedPromptsAsNewTurn", "materializeQueuedPromptsAsUserTurn")) {
            val b = body(name)
            assertTrue("$name: the queue must not be emptied wholesale", !b.contains("_promptQueue.value = emptyList()"))
            val persist = b.indexOf("chatRepository.appendMessage(")
            val dequeue = b.indexOf("_promptQueue.value = _promptQueue.value.filterNot { it.id in queuedIds }", persist)
            assertTrue("$name: sanity: persist present", persist >= 0)
            val restoresOnCancel = Regex("""catch \([^)]*CancellationException[^)]*\)[^}]*_promptQueue""")
                .containsMatchIn(b)
            assertTrue(
                "$name: queue is dequeued before the suspending persist and never restored on cancellation",
                dequeue > persist || restoresOnCancel,
            )
        }
    }

    /** Pins the 955e76d6a fix itself: the emit filters the LIVE list. */
    @Test
    fun `the inject emit filters the live message list, not a snapshot`() {
        // [T-A5-split-scan] 函数按定义定位（上游在 ChatViewModel.kt:8680-8910，修复是
        // 8715-8740 + 落点处过滤活列表）；两条断言原样保留：不得再有陈旧的
        // `msgsAfterUnqueue` 快照，落点必须过滤 `_messages.value` 本身。
        val b = body("injectQueuedPromptsAsNewTurn")
        assertTrue(b.contains("_messages.value = _messages.value.filterNot"))
        assertTrue("no stale snapshot variable", !b.contains("msgsAfterUnqueue ="))
    }
}
