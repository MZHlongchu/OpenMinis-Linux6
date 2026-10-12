package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.scheduled.PrefilledToolCall
import com.openminis.app.scheduled.ScheduledTaskMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-scheduled-preemptive-insert] A scheduled fire landing in a busy session is
 * slipped in at the next tool boundary — alone, as an inserted envelope, with
 * its prefilled command run as the following turn — instead of waiting for the
 * whole run to end and being merged with everything else that queued up.
 */
class ScheduledPreemptiveInsertTest {

    /**
     * [T-a7-package-surface] ChatViewModel 合并后被拆成整个 `ui/chat` 包的分片，
     * 成员多为扩展函数；上游那套"读 ChatViewModel.kt 单文件"的接线守卫已经不是
     * "这个类的源码面"。下面所有 vm.contains(...) 的断言文本一字未改。
     */
    private val vm by lazy { ProductionSources.readPackageSurface("ui/chat") }

    private fun user(id: String) = QueuedPrompt(id = id, text = "u$id", origin = QueuedPromptOrigin.USER)
    private fun callback(id: String) = QueuedPrompt(id = id, text = "cb$id", origin = QueuedPromptOrigin.PROGRAMMATIC)
    private fun fire(id: String, task: String = "T$id", prefill: Boolean = false) = QueuedPrompt(
        id = id,
        text = ScheduledTaskMarker(task, "L", null, "p").xml,
        origin = QueuedPromptOrigin.PROGRAMMATIC,
        prefill = if (prefill) listOf(PrefilledToolCall.shell("date")) else emptyList(),
        scheduledTaskId = task,
    )

    private fun ids(batch: List<QueuedPrompt>) = batch.map { it.id }

    // ── which prompts go in at a tool boundary ──────────────────────────────

    @Test
    fun `a scheduled fire goes in at the tool boundary, on its own`() {
        val q = listOf(callback("1"), fire("2"), user("3"), fire("4"))
        assertEquals(listOf("2"), ids(QueuedPromptBatching.nextInsertBatch(q)))
    }

    @Test
    fun `other programmatic prompts are still held until the loop ends`() {
        assertTrue(QueuedPromptBatching.nextInsertBatch(listOf(callback("1"), callback("2"))).isEmpty())
    }

    @Test
    fun `a user follow-up keeps its old batch, minus any scheduled fire`() {
        // Before: everything queued was merged into the injected message. The
        // held callbacks still ride along; scheduled fires never do.
        val q = listOf(callback("1"), user("2"), fire("3"), user("4"))
        assertEquals(listOf("1", "2", "4"), ids(QueuedPromptBatching.nextInsertBatch(q)))
    }

    @Test
    fun `fires are delivered oldest first, one per boundary`() {
        var q = listOf(fire("a"), fire("b"), fire("c"))
        val order = mutableListOf<String>()
        while (true) {
            val batch = QueuedPromptBatching.nextInsertBatch(q)
            if (batch.isEmpty()) break
            assertEquals(1, batch.size)
            order += batch.single().id
            q = q.filterNot { it.id in ids(batch) }
        }
        assertEquals(listOf("a", "b", "c"), order)
    }

    // ── the drain after the loop ends ───────────────────────────────────────

    @Test
    fun `the drain never merges a scheduled fire with anything`() {
        val q = listOf(fire("1"), user("2"), callback("3"), fire("4"))
        assertEquals(listOf("1"), ids(QueuedPromptBatching.nextDrainBatch(q)))
        val rest = q.drop(1)
        assertEquals(listOf("2", "3"), ids(QueuedPromptBatching.nextDrainBatch(rest)))
        assertEquals(listOf("4"), ids(QueuedPromptBatching.nextDrainBatch(listOf(fire("4")))))
        assertTrue(QueuedPromptBatching.nextDrainBatch(emptyList()).isEmpty())
    }

    @Test
    fun `a boundary and the drain never deliver the same prompt twice`() {
        // Each path dequeues exactly its batch by id; what one took, the other
        // cannot see.
        val q = listOf(fire("1"), user("2"))
        val inserted = QueuedPromptBatching.nextInsertBatch(q)
        val left = q.filterNot { it.id in ids(inserted) }
        val drained = QueuedPromptBatching.nextDrainBatch(left)
        assertTrue(ids(inserted).intersect(ids(drained).toSet()).isEmpty())
    }

    // ── repeated fires ──────────────────────────────────────────────────────

    @Test
    fun `a newer fire of the same task replaces the undelivered older one`() {
        val q = listOf(fire("1", task = "A"), fire("2", task = "B"), user("3"))
        assertEquals(listOf("1"), QueuedPromptBatching.supersededBy(q, "A"))
        assertTrue(QueuedPromptBatching.supersededBy(q, "C").isEmpty())
    }

    // ── the inserted envelope ───────────────────────────────────────────────

    @Test
    fun `an inserted envelope says it was slipped in and to resume the prior task`() {
        val m = ScheduledTaskMarker("T1", "Disk check", null, "Summarise the usage", insertedMidTask = true)
        val xml = m.xml
        assertTrue(xml.contains(" inserted=\"1\""))
        val reminder = ScheduledTaskMarker.insertionReminder("Disk check", null)
        assertEquals(
            "<system-reminder>Scheduled task \"Disk check\" fired and was inserted here — not your last tool result, " +
                "not a user request. Handle briefly, then resume your prior task.</system-reminder>",
            reminder,
        )
        assertEquals(
            "a blank label still reads as a sentence",
            "<system-reminder>A scheduled task fired and was inserted here — not your last tool result, " +
                "not a user request. Handle briefly, then resume your prior task.</system-reminder>",
            ScheduledTaskMarker.insertionReminder("  ", null),
        )
        // Inside the envelope, never at the head of the message: a user row
        // that STARTS with <system-reminder> is treated as a hidden synthetic row.
        assertTrue(xml.startsWith("<scheduled_task "))
        assertTrue(xml.contains(">\n$reminder\nSummarise the usage\n</scheduled_task>"))
        // The card still shows only the prompt.
        val back = ScheduledTaskMarker.parse(xml)!!
        assertEquals(m, back)
        assertEquals("Summarise the usage", back.prompt)
    }

    @Test
    fun `with a prefill the reminder carries the already-ran note, and the plain note is not repeated`() {
        val m = ScheduledTaskMarker("T1", "Weather", null, "p", prefilledTool = "shell_execute", insertedMidTask = true)
        assertEquals(
            "<system-reminder>Scheduled task \"Weather\" fired and was inserted here — not your last tool result, " +
                "not a user request. Preset command already ran; read the result below, don't re-run it. " +
                "Handle briefly, then resume your prior task.</system-reminder>",
            ScheduledTaskMarker.insertionReminder("Weather", "shell_execute"),
        )
        assertTrue(m.xml.contains(ScheduledTaskMarker.insertionReminder("Weather", "shell_execute")))
        assertFalse(m.xml.contains(ScheduledTaskMarker.prefillNote("shell_execute")))
        assertEquals(m, ScheduledTaskMarker.parse(m.xml))
    }

    @Test
    fun `labels with quotes or line breaks still round-trip`() {
        for (label in listOf("say \"hi\"", "two\nlines", "")) {
            val m = ScheduledTaskMarker("T1", label, null, "p", insertedMidTask = true)
            assertEquals("p", ScheduledTaskMarker.parse(m.xml)!!.prompt)
        }
    }

    @Test
    fun `a fire delivered when the loop is idle keeps the plain envelope`() {
        // Only the tool-boundary path re-wraps; the drain and an idle send
        // deliver the envelope exactly as ScheduledAgentRunner built it.
        assertFalse(ScheduledTaskMarker("T1", "L", 5L, "p").xml.contains("inserted"))
    }

    // ── the bridge ──────────────────────────────────────────────────────────

    @Test
    fun `the scheduled bridge is a pause, and is kept out of the chat UI`() {
        val bridge = ChatMessage.SCHEDULED_INSERT_BRIDGE_TEXT
        assertTrue(bridge.contains("pick up the task I was working on"))
        assertTrue(ChatMessage(id = "b", role = "assistant", content = bridge).isInternalBridge)
        // The user-follow-up bridge is unchanged.
        assertTrue(ChatMessage.INTERNAL_BRIDGE_TEXT.startsWith("(Interrupted mid-task by a new user message."))
    }

    // ── the reply reported for the fire ─────────────────────────────────────

    private fun text(t: String) = """[{"type":"text","value":${org.json.JSONObject.quote(t)}}]"""

    @Test
    fun `the reply to an inserted fire is the answer to it, not the task that resumed after`() {
        val sent = ScheduledTaskMarker("T1", "L", null, "p").xml
        val inserted = ScheduledTaskMarker("T1", "L", null, "p", insertedMidTask = true).xml
        val rows = listOf(
            "user" to text("do the long thing"),
            "assistant" to text("working on it"),
            "user" to text(inserted),
            "assistant" to """[{"type":"toolUse","value":{"toolUseId":"call_sched_1","name":"shell_execute"}}]""",
            "assistant" to text("Disk is 40% full."),
            "assistant" to text("Long thing finished."),
        )
        assertEquals("Disk is 40% full.", HeadlessChatRunner.responseTextFor(sent, rows, fromIndex = 2))
    }

    @Test
    fun `an undelivered fire does not borrow an older fire's reply`() {
        val sent = ScheduledTaskMarker("T1", "L", null, "p").xml
        val rows = listOf("user" to text(sent), "assistant" to text("yesterday's answer"))
        assertNull(HeadlessChatRunner.responseTextFor(sent, rows, fromIndex = 2))
    }

    @Test
    fun `ordinary prompts still report the last assistant text`() {
        val rows = listOf("user" to text("hi"), "assistant" to text("a"), "assistant" to text("b"))
        assertEquals("b", HeadlessChatRunner.responseTextFor("hi", rows, fromIndex = -1))
    }

    // ── wiring ──────────────────────────────────────────────────────────────

    @Test
    fun `the tool boundary and the drain use the batching rules`() {
        assertTrue(vm.contains("val insertBatch = QueuedPromptBatching.nextInsertBatch(_promptQueue.value)"))
        assertTrue(vm.contains("batch = insertBatch,"))
        assertTrue(vm.contains("val queued = QueuedPromptBatching.nextDrainBatch(_promptQueue.value)"))
        // The injection works on its batch, never on the whole queue.
        // [T-a7-package-surface] 原写法用 `substringBefore("\n    private suspend fun
        // drainQueuedPrompts(")` 依赖 inject 与 drain 在同一个文件里相邻；合并后两者
        // 分属不同分片，那样切出来的是整段包面。断言的原意图是"断言
        // injectQueuedPromptsAsNewTurn 的函数体"，所以改为按函数名定位后取同量级窗口
        // （6000 字符，和本用例里其它窗口一致）。
        val inject = ProductionSources.functionWindow(vm, "injectQueuedPromptsAsNewTurn", 6000)
        assertTrue("missing fun injectQueuedPromptsAsNewTurn", inject != null)
        assertTrue(inject!!.contains("val queued = batch"))
        assertFalse(inject.contains("val queued = _promptQueue.value"))
        // A single scheduled fire is re-wrapped and bridged as an insertion.
        assertTrue(inject.contains("val scheduledInsert = queued.size == 1 && queued[0].isScheduledFire"))
        assertTrue(inject.contains("if (scheduledInsert) insertedScheduledEnvelope(prompt.text) else prompt.text"))
        // [T-plan-board-prompt] 用户跟进那一侧用的是本移植的 [BRIDGE_TEXT]，它比上游的
        // `ChatMessage.INTERNAL_BRIDGE_TEXT` 多一句「未完成项在 agent_plan 看板上，
        // 处理完用户消息后从看板续跑」——那是本移植刻意加的（[T-plan-board-prompt]），
        // 删掉它会让被打断的计划丢掉唯一的持久恢复点。本用例要钉的是「scheduled 插入
        // 用自己那句桥接、用户跟进用另一句」，这一点不变。
        assertTrue(inject.contains("if (scheduledInsert) ChatMessage.SCHEDULED_INSERT_BRIDGE_TEXT else BRIDGE_TEXT"))
        // Its prefill still re-arms the scripted turn after the injection.
        assertTrue(vm.contains("scriptedTurnFor(handled.prefill)?.let { pendingScriptedTurn = it }"))
    }

    @Test
    fun `only a programmatic envelope counts as a scheduled fire, and a repeat replaces the older one`() {
        assertTrue(
            vm.contains(
                "scheduledTaskId = if (origin == QueuedPromptOrigin.PROGRAMMATIC) {\n" +
                    "                com.openminis.app.scheduled.ScheduledTaskMarker.parse(trimmed)?.taskId",
            ),
        )
        assertTrue(vm.contains("QueuedPromptBatching.supersededBy(_promptQueue.value, taskId)"))
    }

    @Test
    fun `the subagent background switch still answers only a user follow-up`() {
        // A scheduled fire must not turn a synchronous subagent_task into a
        // background job — that is a reaction to the USER wanting an answer.
        //
        // [T-subagent-background] 扫描面/形态按本移植：上游那条「等待孩子跑完时
        // 收到 USER 跟进就把同步 job 转后台」的等待循环在本移植里不存在——本移植的
        // 委派走「波次 + dispatch_id」，同波次是否脱离调用方**只**由调用方或模型显式
        // 传的 `background` 参数决定，队列内容从不参与，所以「scheduled fire 不得把
        // 同步委派变成后台任务」这条不变式在这里是构造性成立的（第一条断言把它钉住）。
        // 第二条断言钉住同一件事的另一半：凡「有用户在等」的判断都必须以
        // QueuedPromptOrigin.USER 为准——scheduled fire 是 PROGRAMMATIC，不算数。
        assertTrue(vm.contains("val detached = background || runCatching {"))
        assertTrue(vm.contains("val userPromptWaiting = _promptQueue.value.any { it.origin == QueuedPromptOrigin.USER }"))
    }
}
