package com.openminis.app.data

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-ctx-measure-outbound] Capacity decisions judge the request that is about
 * to be sent, not the provider's count for the previous one. Mirrors iOS
 * ContextMeasureOutboundTests.swift.
 *
 * The Android form of the bug: both guards read `_lastTurnContextTokens`,
 * which only a usage chunk ever replaced. The in-loop path had a one-shot
 * "stale" flag; the send-time and manual compactions did not, so the first
 * check of the next turn compacted AGAIN on the pre-compaction number — an
 * extra summary that folded the user's just-sent message in. After a revert it
 * kept the compacted context's count and under-read the restored history.
 *
 * [ContextSizeMeter] is pure and exercised directly. The ViewModel wiring needs
 * a live ViewModel, so the flow is modelled on its call order and the
 * load-bearing lines are pinned by source greps at the bottom.
 */
class ContextMeasureOutboundTest {

    // ── the estimator ──────────────────────────────────────────────────────

    @Test
    fun `CJK is read at its real size, not a third of it`() {
        // 64 characters; cl100k_base counts 61 tokens (tiktoken, measured).
        val zh = "我们需要排查自动压缩逻辑，如果会话消息触发自动压缩阈值并自动压缩后继续发消息，此时会更新之前成功对话消息的 usage 数据吗？"
        val real = 61
        val est = ContextSizeMeter.estimateTokens(zh)
        val ratio = est.toDouble() / real
        assertTrue("new estimate $est should be within 0.66–1.18x of $real", ratio in 0.66..1.18)
        val old = (zh.length / 3.5).toInt()
        assertTrue("OLD chars/3.5 read it at under a third ($old of $real)", old.toDouble() / real < 0.34)
        assertEquals(0, ContextSizeMeter.estimateTokens(""))
        assertTrue(ContextSizeMeter.estimateTokens("1234567890") > ContextSizeMeter.estimateTokens("abcdefghij"))
    }

    @Test
    fun `surrogate pairs count once, as iOS unicodeScalars do`() {
        // An emoji is one scalar (two UTF-16 units): must not be double-counted.
        assertEquals(1, ContextSizeMeter.estimateTokens("😀"))
    }

    @Test
    fun `a message is measured the way providers serialise it`() {
        val input = JSONObject().put("path", "/a")
        val parts = LLMMessage(
            LLMMessage.Role.ASSISTANT, content = "ignored when parts exist",
            contentParts = listOf(
                AgentContentPart.Text("hello"),
                AgentContentPart.ToolUse("t1", "file_read", input),
            ),
        )
        val expected = ContextSizeMeter.PER_MESSAGE_OVERHEAD +
            ContextSizeMeter.estimateTokens("hello") +
            ContextSizeMeter.estimateTokens("file_read") + ContextSizeMeter.estimateTokens(input.toString()) +
            ContextSizeMeter.PER_TOOL_PART_OVERHEAD
        assertEquals(expected, ContextSizeMeter.estimateTokens(parts))

        val plain = LLMMessage(LLMMessage.Role.USER, content = "just text")
        assertEquals(ContextSizeMeter.PER_MESSAGE_OVERHEAD + ContextSizeMeter.estimateTokens("just text"),
            ContextSizeMeter.estimateTokens(plain))
    }

    @Test
    fun `system prompt and tool schemas are part of the measurement`() {
        val tools = listOf(
            AgentToolDefinition(
                name = "shell_execute",
                description = "Run a shell command",
                parameters = mapOf("command" to AgentToolParam("string", "The command")),
            ),
        )
        val withTools = ContextSizeMeter.estimateFixedTokens("You are Minis.", tools)
        val promptOnly = ContextSizeMeter.estimateFixedTokens("You are Minis.", emptyList())
        assertTrue("tool schemas add to the fixed share", withTools > promptOnly)
        assertEquals(0, ContextSizeMeter.estimateFixedTokens(null, emptyList()))
    }

    // ── calibration ────────────────────────────────────────────────────────

    @Test
    fun `calibration pairs a report with the estimate of the same request`() {
        assertEquals(1.4, ContextSizeMeter.calibrationRatio(140, 100)!!, 1e-9)
        assertEquals("absurd reports are clamped high", 3.0, ContextSizeMeter.calibrationRatio(1_000_000, 100)!!, 1e-9)
        assertEquals("an under-reporting upstream cannot shrink the estimate by more than 20%",
            0.8, ContextSizeMeter.calibrationRatio(1, 100)!!, 1e-9)
        assertEquals("a genuine over-read (the estimator's worst, 1.18x) is still followed",
            100.0 / 118.0, ContextSizeMeter.calibrationRatio(100, 118)!!, 1e-9)
        assertNull(ContextSizeMeter.calibrationRatio(0, 100))
        assertNull(ContextSizeMeter.calibrationRatio(100, 0))
        assertEquals("with nothing changed the measurement reproduces the report",
            138_800, ContextSizeMeter.calibrated(100_000, ContextSizeMeter.calibrationRatio(138_800, 100_000)!!))
    }

    // ── the flow ───────────────────────────────────────────────────────────
    //
    // Sizes are what the MEASUREMENT sees; the "provider" reports
    // estimate × providerFactor for whatever is actually sent.

    private val window = 128_000
    private val policy = ContextPolicy.forContextWindow(window)
    private val providerFactor = 1.4
    private val fixed = 9_000

    /**
     * [T-ctx-percent-threshold] 本移植的压缩线不是上游的固定余量（ctx − 20k），而是
     * 窗口百分比、且用户可调（`AutoCompactPrefs.DEFAULT_THRESHOLD_PERCENT` = 90，
     * 见 `ContextPolicy.forContextWindow(window, compactPercent)`，合并时的取舍写在该
     * 函数上方）。上游这组手挑的会话规模（13×5500 + 1000 → 测量 114_100）是按
     * 108_000 那条线卡的，在本移植 90% 的线（115_200）下面就再也「过不了线」。
     *
     * 用例的意图是「刚发出去的请求已经越过压缩线、但仍在天花板以下」，所以规模改为
     * 按 [ContextPolicy.compactThreshold] 现算：任何阈值（含用户把它调高/调低）都成立，
     * 断言一条没动。
     */
    private fun bigSession(): Session {
        // measured = providerFactor × (history.sum() + fixed)
        val target = policy.compactThreshold + (window - policy.compactThreshold) / 2
        val perEntry = ((target / providerFactor).toInt() - fixed - 1_000) / 13 + 1
        return Session((List(13) { perEntry } + 1_000).toMutableList())
    }

    private inner class Session(var history: MutableList<Int>) {
        var saved: List<Int> = emptyList()
        var ratio = 1.0
        var apiCalls = 0
        var compactions = 0
        var stampedReport = 0
        var stampedEstimate = 0
        val estimate get() = history.sum() + fixed
        val measured get() = ContextSizeMeter.calibrated(estimate, ratio)

        fun dispatch() {
            val est = estimate
            apiCalls++
            val report = (est * providerFactor).toInt()
            ContextSizeMeter.calibrationRatio(report, est)?.let { ratio = it }
            stampedReport = report
            stampedEstimate = est
        }
        fun compact() {
            saved = history.toList()
            history = (listOf(3_000) + history.takeLast(2)).toMutableList()
            compactions++
        }
        fun revert() { history = (saved + history.drop(3)).toMutableList() }
        fun reload() { ratio = ContextSizeMeter.calibrationRatio(stampedReport, stampedEstimate) ?: 1.0 }

        /** inLoopContextCheck as fixed. */
        fun runTurn(): String {
            var noProgress = false
            while (true) {
                val tokens = measured
                when (policy.check(tokens, window)) {
                    ContextPolicy.CheckResult.OK -> { dispatch(); return "sent" }
                    else -> {
                        if (compactions >= 3 || noProgress) {
                            return if (tokens < window) { dispatch(); "sent" } else "exhausted"
                        }
                        compact()
                        noProgress = measured >= tokens
                    }
                }
            }
        }
    }

    @Test
    fun `compact then send - the next request goes out with no re-compaction`() {
        val s = bigSession()
        s.dispatch()
        assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, policy.check(s.measured, window))
        val staleReport = s.stampedReport
        s.compact()                                    // compactAndSendPending
        val calls = s.apiCalls
        assertEquals("sent", s.runTurn())
        assertEquals("exactly one API call", 1, s.apiCalls - calls)
        assertEquals("no in-loop re-compaction", 1, s.compactions)

        // Falsification: the old guard read the stale report, with no stale
        // flag set by a send-time compaction.
        assertEquals("OLD: the first in-loop check compacted again",
            ContextPolicy.CheckResult.NEEDS_COMPACT, policy.check(staleReport, window))
    }

    @Test
    fun `reopening a compacted session re-seeds the ratio, not the stale size`() {
        val s = bigSession()
        s.dispatch()
        s.compact()
        s.reload()
        assertEquals(ContextPolicy.CheckResult.OK, policy.check(s.measured, window))
        assertEquals(providerFactor, s.ratio, 0.01)
    }

    @Test
    fun `revert restores the full size instead of keeping the compacted count`() {
        val s = bigSession()
        s.dispatch()
        s.compact()
        s.history.addAll(listOf(2_000, 2_000)); s.dispatch()   // turns on the compacted context
        val compactedReport = s.stampedReport
        s.revert(); s.reload()                                   // revertCompact → loadSession
        assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, policy.check(s.measured, window))
        assertEquals("OLD: the compacted context's report under-read it",
            ContextPolicy.CheckResult.OK, policy.check(compactedReport, window))
    }

    @Test
    fun `offload is seen by the very next decision`() {
        val s = bigSession()
        s.dispatch()
        val staleReport = s.stampedReport
        for (i in 0 until 6) s.history[i] = 120               // stubs replace tool results in place
        assertEquals(ContextPolicy.CheckResult.OK, policy.check(s.measured, window))
        assertEquals("OLD: the pre-offload report still forced a compaction",
            ContextPolicy.CheckResult.NEEDS_COMPACT, policy.check(staleReport, window))
    }

    @Test
    fun `a compaction that cannot shrink the request is not retried`() {
        // [T-ctx-percent-threshold] 同上：规模按本移植的压缩线现算，让「过线 → 压一次
        // → 压不动 → 直接发」这三段仍然成立（线以上、窗口以下）。three 条断言原样。
        val need = (policy.compactThreshold / 1.1).toInt() + 2_000
        val within = Session(mutableListOf(3_000, need - 42_000, 30_000)).apply { ratio = 1.1 }
        assertEquals("above threshold but within the window → sent", "sent", within.runTurn())
        assertEquals(1, within.compactions)
        val over = Session(mutableListOf(3_000, 90_000, 40_000)).apply { ratio = 1.1 }
        assertEquals("exhausted", over.runTurn())
        assertEquals(1, over.compactions)
    }

    // ── persistence ────────────────────────────────────────────────────────

    @Test
    fun `usage rows carry the pair and a reload replays them in order`() {
        fun seed(rows: List<String>) = ContextSizeMeter.replayCalibration(rows.mapNotNull(ContextSizeMeter::calibrationSample))
        val legacy = """{"inputTokens":10,"outputTokens":5,"cacheCreationTokens":0,"cacheReadTokens":0,"latestContextTokens":120000}"""
        assertEquals("rows written before the pair do not seed", 0, seed(listOf(legacy)).samples)
        val older = """{"latestContextTokens":100000,"estimatedRequestTokens":100000,"estimatedFixedTokens":8000}"""
        val newer = """{"latestContextTokens":140000,"estimatedRequestTokens":100000,"estimatedFixedTokens":9000,"streamMs":12}"""
        val state = seed(listOf(older, newer, legacy))
        assertEquals(2, state.samples)
        assertEquals("model-less rows set only the newest-learned ratio", 1.4, state.lastLearned!!, 1e-9)
        assertEquals(9_000, state.fixedTokens)
    }

    // ── optimisation round: per-model ratios, rejections, valve, cache ──────

    @Test
    fun `a model is judged by its own ratio, or a margin over a borrowed one`() {
        val known = mapOf("gpt-5" to 1.05, "claude-5" to 1.30)
        assertEquals(1.30, ContextSizeMeter.ratioFor("claude-5", known, 1.05), 1e-9)
        assertEquals("switching back uses that model's own ratio", 1.05, ContextSizeMeter.ratioFor("gpt-5", known, 1.30), 1e-9)
        assertEquals("unseen model: borrowed with the margin", 1.05 * 1.2, ContextSizeMeter.ratioFor("gemini-4", known, 1.05), 1e-9)
        assertEquals("a borrowed ratio below 1 does not shrink", 1.2, ContextSizeMeter.ratioFor("x", emptyMap(), 0.85), 1e-9)
        assertEquals("ceiling respected", 3.0, ContextSizeMeter.ratioFor("x", emptyMap(), 2.9), 1e-9)
        assertEquals("no usage yet", 1.0, ContextSizeMeter.ratioFor("x", emptyMap(), null), 1e-9)
    }

    @Test
    fun `scenario 7 - switching to a denser tokenizer compacts first instead of overflowing`() {
        val estimate = 95_000
        val realOnB = (estimate * 1.36).toInt()
        assertTrue("B's real count is over its window", realOnB > window)
        val oldJudged = (estimate * 1.05).toInt()      // A's count carried over
        assertEquals("OLD: judged ok and sent over B's window", ContextPolicy.CheckResult.OK, policy.check(oldJudged, window))
        val newJudged = ContextSizeMeter.calibrated(estimate, ContextSizeMeter.ratioFor("B", mapOf("A" to 1.05), 1.05))
        assertEquals("NEW: the margin compacts first", ContextPolicy.CheckResult.NEEDS_COMPACT, policy.check(newJudged, window))
    }

    @Test
    fun `a context-length rejection raises the ratio from the provider's own count`() {
        val openai = "[400] This model's maximum context length is 128000 tokens. However, your messages resulted in 131,244 tokens."
        val anthropic = "prompt is too long: 205000 tokens > 200000 maximum"
        assertEquals(131_244, ContextSizeMeter.requestedTokens(openai))
        assertEquals(205_000, ContextSizeMeter.requestedTokens(anthropic))
        assertNull(ContextSizeMeter.requestedTokens("Your input exceeds the context window of this model"))

        val raised = ContextSizeMeter.ratioAfterOverflow(1.05, 100_000, 131_244, 128_000)
        assertEquals(1.31244, raised, 1e-9)
        assertEquals("that request now measures over the compact line",
            ContextPolicy.CheckResult.NEEDS_COMPACT, policy.check(ContextSizeMeter.calibrated(100_000, raised), window))
        assertEquals("no count stated: just past the window", 1.3056,
            ContextSizeMeter.ratioAfterOverflow(1.05, 100_000, null, 128_000), 1e-9)
        assertEquals("never lowered", 1.5, ContextSizeMeter.ratioAfterOverflow(1.5, 100_000, 120_000, 128_000), 1e-9)
        assertEquals("an implausible number (request id) is ignored", 1.3056,
            ContextSizeMeter.ratioAfterOverflow(1.0, 100_000, 123_456_789, 128_000), 1e-9)
    }

    @Test
    fun `an offload that rewrites a persisted message in place is re-measured`() {
        val big = "x".repeat(40_000)
        val before = LLMMessage(LLMMessage.Role.USER, content = "", dbMessageId = "m1",
            contentParts = listOf(AgentContentPart.ToolResult("t1", "shell_execute", big)))
        val after = before.copy(contentParts = listOf(AgentContentPart.ToolResult("t1", "shell_execute", "[offloaded] saved to /var/minis/offloads/a")))
        val sizeBefore = ContextSizeMeter.estimateTokens(listOf(before))
        assertEquals("cache hit is stable", sizeBefore, ContextSizeMeter.estimateTokens(listOf(before)))
        val sizeAfter = ContextSizeMeter.estimateTokens(listOf(after))
        assertTrue("same id, new content shape → not the cached size ($sizeAfter vs $sizeBefore)", sizeAfter < sizeBefore / 10)
    }

    @Test
    fun `warm-up is trimmed only when it keeps the request over the line`() {
        val sizes = listOf(10, 9_000, 10, 9_000, 10, 300, 200)
        val starts = listOf(true, false, true, false, true, false, false)
        assertEquals("under budget → untouched", 0, ContextSizeMeter.warmUpDrop(sizes, starts, 500, 40_000))
        val drop = ContextSizeMeter.warmUpDrop(sizes, starts, 500, 12_000)
        assertEquals("the oldest turn is dropped, and no more than needed", 2, drop)
        assertTrue("kept slice starts on a user-text turn", starts[drop])
        assertEquals("nothing fits → warm-up dropped entirely", sizes.size, ContextSizeMeter.warmUpDrop(sizes, starts, 500, 100))
    }

    // ── drift guard ────────────────────────────────────────────────────────

    /**
     * [T-ctx-measure-outbound-scan-scope] 本移植把上游那个单体 ChatViewModel.kt 拆成了
     * `ui/chat/ChatViewModel.kt` + 一批 `ChatViewModel*Ext.kt`（守卫、压缩、发车、循环
     * 各在一处），只读单文件会把「这条线还在不在」判成不存在。所以守卫改成扫整个
     * `ui/chat` 包：**意图不变**（下面每一条都必须在 chat 层真实存在、且计数精确），
     * 变的只是「在哪个文件里」。拆分后扩展函数里的缩进是 4 空格（类成员是 8），
     * 因此对缩进敏感的计数改成缩进容忍的正则，但次数仍然是卡片式的精确值。
     */
    private val chatSrc: String by lazy {
        ProductionSources.allKotlinFiles()
            .filter { it.parentFile?.name == "chat" && it.parentFile?.parentFile?.name == "ui" }
            .joinToString("\n") { it.readText() }
    }

    @Test
    fun `drift guard - every size decision reads the outbound measurement`() {
        val vm = chatSrc
        val meter = ProductionSources.read("data/ContextPolicy.kt")
        assertTrue(meter.contains("val tokens = letters / 4.5 + digits / 2.0 + asciiOther * 0.35 + nonAscii"))
        assertTrue(meter.contains("const val CALIBRATION_MIN = 0.8"))
        assertTrue(meter.contains("const val CALIBRATION_MAX = 3.0"))

        // Both guards judge the measurement; the stale flag is gone.
        // [T-ctx-measure-outbound-scan-scope] 计数仍是精确的 2 处，只是不再要求
        // 「8 空格缩进」——拆分后这两处在 Ext 文件里是 4 空格缩进。
        assertEquals(2, Regex("val m = contextMeasurement\\(\\)\\s*\\n\\s*val tokens = m\\.measured").findAll(vm).count())
        assertFalse(vm.contains("private var lastTurnContextTokensStale"))
        assertTrue(vm.contains("val history = ContextSizeMeter.estimateTokens(effectiveAgentHistoryUncounted())"))
        assertTrue(vm.contains("ContextSizeMeter.calibrated(history + contextFixedTokens, ratio)"))

        // In-loop: progress-gated retry; everything else goes through the
        // shared decision table (CompactionDecisionTest).
        assertTrue(vm.contains("lastInLoopCompactionMadeNoProgress = after >= tokens"))
        assertTrue(vm.contains("val canCompact = compactionsSoFar < maxInLoopCompactions && !lastInLoopCompactionMadeNoProgress"))

        // Dispatch: estimate recorded on the exact pre-budget history, and it sizes max_tokens.
        // [T-ctx-measure-outbound-scan-scope] 上游的入口是类内成员
        // `private suspend fun runAgentLoop(`；本移植的循环是扩展函数
        // （`internal suspend fun ChatViewModel.runAgentLoop(`，ChatViewModelAgentLoopExt.kt），
        // 锚点跟着写成它的真实声明。
        val loop = vm.substring(vm.indexOf("suspend fun ChatViewModel.runAgentLoop("))
        val record = loop.indexOf("val dispatchInputTokens = recordContextDispatch(outboundHistory, currentProvider.model)")
        // [T-ctx-measure-outbound-scan-scope] 上游直接调
        // `currentProvider.streamMessage(`；本移植的循环走接缝一
        // （ProviderStreamClient）——同一处「请求发出去」的位置，锚点换成
        // `.streamRound(`。断言与次序要求（记账在发请求之前）一字未动。
        val send = loop.indexOf(".streamRound(")
        assertTrue("dispatch recorded before the provider call", record in 1 until send)
        // [T-scheduled-tool-prefill] One level deeper: the provider request now
        // sits in the non-scripted branch of the turn's chunk source.
        // [T-ctx-measure-outbound-scan-scope] 上游写成多行嵌套调用
        // （`applyRequestImageBudget(\n outboundHistory,`）；本移植在同一处只写一行，
        // 而且这份历史在循环里叫 `requestHistory`（记账那一行按上游的名字取了别名
        // `outboundHistory = requestHistory`）。语义不变：记账看到的是**未经**图片预算
        // 裁剪的历史。
        assertTrue(loop.contains("applyRequestImageBudget(requestHistory)"))
        assertTrue(loop.contains("dynamicMaxTokens(currentProvider, dispatchInputTokens)"))
        assertTrue(loop.contains("lastContextTokens = measureOutboundContextTokens(),"))
        assertTrue(loop.contains("contextFixedTokens = ContextSizeMeter.estimateFixedTokens("))

        // Calibration on arrival, persisted under the iOS key names, seeded on load.
        assertTrue(vm.contains("calibrateContextSize(lastContextTokens)"))
        assertTrue(vm.contains("\"\"\",\"estimatedRequestTokens\":\$est,\"estimatedFixedTokens\":\$fixed\$modelField\"\"\""))
        assertTrue("model id travels with the pair", vm.contains("\"calibrationModelId\":"))
        assertEquals("both usage-row writers carry the pair",
            2, vm.split("\${calibrationJsonFields()}}\"\"\"").size - 1)
        assertTrue(vm.contains("seedContextCalibration(chatRepository.sessionTokenUsages(realSessionId.ifEmpty { sessionId }))"))
        // Seeding replays the rows through the smoothing in order, so they must
        // come back in conversation order, not storage order.
        val dao = ProductionSources.read("data/db/ChatDao.kt")
        assertTrue(dao.contains("AND token_usage IS NOT NULL ORDER BY sort_order ASC\")"))

        // Optimisation round.
        // [T-ctx-measure-outbound] 上游把每模型比值留在 ViewModel 上
        // （`private val contextCalibrationRatios = mutableMapOf<String, Double>()`）；
        // 本移植把它放在 [TokenUsageCalibration]（按模型 id 的全局表，跨会话共享密度），
        // 并把 (report, estimate) 对回放到同一张表。意图不变：比值是**按模型**存的，
        // 而测量（比值 + 来源）读的就是它 —— 见 contextMeasurement 里的
        // `ContextSizeMeter.ratioFor(model, known, lastLearnedCalibration)`。
        assertTrue(vm.contains("ContextSizeMeter.ratioFor(model, known, lastLearnedCalibration)"))
        assertTrue(vm.contains("ContextSizeMeter.ratioSource(model, known, lastLearnedCalibration)"))
        assertTrue(ProductionSources.read("data/TokenUsageCalibration.kt")
            .contains("private val scales = ConcurrentHashMap<String, Double>()"))
        // [T-ctx-overflow-attribute-dispatch] 上游在同一层写
        // `noteContextOverflow(overflowErr?.detail ?: "")`（ChatViewModel.kt:11948），
        // 且只认 typed 的 ContextLengthExceeded；本移植的循环只拿到 Throwable，于是
        // 「拆 detail + 判据（typed 或未类型化的 prose）」收进
        // noteProviderContextRejection。两者的共同点被下面两条钉住：调用点是这一处，
        // 抬比值的那一句用的仍是 provider 给的 detail。
        assertTrue("every context-length rejection raises the ratio", vm.contains("noteProviderContextRejection(actual)"))
        assertTrue(vm.contains("noteContextOverflow(typed?.detail ?: error.message ?: \"\")"))
        assertTrue("valve is once per loop", vm.contains("uncalibratedSendUsed = sentPastExtrapolatedLimitThisLoop,")
            && vm.contains("sentPastExtrapolatedLimitThisLoop = false"))
        assertTrue("seeding replays the rows through the shared replay",
            vm.contains("ContextSizeMeter.replayCalibration(usageJsons.mapNotNull(ContextSizeMeter::calibrationSample))"))
        assertTrue(meter.contains("val id = message.dbMessageId ?: return estimateTokens(message)"))

        // A pinned model's overflow is recovered by compaction, never by
        // answering from another model (parity with iOS).
        // [T-ctx-overflow-no-cross-fallback] 上游在判定里显式排除「直连（非组）会话 +
        // 上下文溢出」这一格（ChatViewModel.kt:11991-11994 `!isDirectEntryOverflow`）；
        // 本移植的候选门本身就要求 `_selectedGroupId.value != null`，于是直连会话对
        // **任何**错误都不回退——同一条语义更强地成立，锚点落在这道门 + 回退判定上。
        assertTrue(vm.contains("val shouldFallback = com.openminis.app.harness.agent.StreamRetryPolicy.shouldFallback(errorClass, loopFallbackStrategy)"))
        assertTrue(vm.contains("val nextCandidate = if (shouldFallback && _selectedGroupId.value != null) {"))

        // Same-session reload keeps learned (incl. overflow-raised) ratios.
        // (Behaviour: CompactionDecisionTest `revert keeps a ratio that exists only in memory`.)
        assertTrue(vm.contains("val learned = if (sid.isNotEmpty() && calibrationSessionId == sid) {"))
        assertTrue(vm.contains("val state = learned?.let { seeded.carryingOver(it) } ?: seeded"))

        // Warm-up trimming.
        assertTrue(vm.contains("} ?: trimWarmUpToFit(preAnchorPruned, postAnchor, summaryWrappedText).let { trim ->"))
        // [T-android-seam-extraction] 热身投影（`result.addAll(warmUp)` 那一句）在合并时
        // 被拆进 `:harness` 模块（CompactHistoryProjector），`ui/chat` 里不再有这行；
        // 断言改为读它真正的落点——「决定过的热身就是被发出去的那一段」这句话没变。
        assertTrue(ProductionSources.readHarness("agent/CompactHistoryProjector.kt")
            .contains("result.addAll(warmUp)"))
        assertTrue("warm-up trim is decided once per marker (stable prompt-cache prefix)",
            vm.contains("val warmUp = warmUpDropByMarker[marker.id]?.let { drop ->")
                && vm.contains("if (trim.decided) warmUpDropByMarker[marker.id] = preAnchorPruned.size - trim.kept.size"))
        assertTrue(vm.contains("if (ContextSizeMeter.estimateTokens(warmUp) + restTokens < budget) return WarmUpTrim(warmUp, decided = true)"))

        // The glow follows the measurement after a compaction and on (re)load.
        // [T-ctx-measure-outbound-scan-scope] 上游那段 `if (compactSucceeded)
        // runCatching { … }` 内联在压缩函数里（缩进 20 空格）；本移植的压缩在
        // ChatViewModelCompactExt，收尾入口是 ChatViewModel.onCompactionFinished，
        // 缩进因此不同——锚点改成缩进容忍的正则，次数与语义不变。
        assertTrue(Regex("if \\(compactSucceeded\\) runCatching \\{\\s*publishMeasuredContextUsage\\(\\)").containsMatchIn(vm))
        assertTrue(vm.contains("runCatching { publishMeasuredContextUsage() }"))
    }

    // ── the fixed share both consumers must seed ───────────────────────────

    /**
     * [T-ctx-measure-outbound] 测量 = (历史 + 固定份额) × 比值，而固定份额
     * （system prompt + 工具表）在**循环**里每一轮按本轮 prompt 重算
     * （ChatViewModelAgentLoopExt 的 `contextFixedTokens = …estimateFixedTokens(`）。
     * 于是循环之外的两个消费点必须自己先播一次 —— 上游 `ChatViewModel.kt:1430-1438`
     * 的 `ensureContextFixedTokens()`，调用点恰好两处：:1563（压缩 / 回退之后把辉光
     * 指向测量）与 :5399（发送前的那次判断）。少了它，发送前的判断只按历史算，
     * 同一个请求会被量到线下 —— 判定说「没事」，请求出门就撞天花板。
     */
    @Test
    fun `both consumption points seed the fixed share before they measure`() {
        val vm = chatSrc
        // 定义一处、调用两处（与上游同形）：定义在 ChatViewModel.kt，两个消费点分别在
        // 类内的 publishMeasuredContextUsage 与 ChatViewModelPreSendContext.kt。
        assertEquals("exactly one definition", 1, Regex("fun ensureContextFixedTokens\\(\\)").findAll(vm).count())
        assertEquals("definition + exactly two consumption sites", 3,
            Regex("ensureContextFixedTokens\\(\\)").findAll(vm).count())
        // 已经播过就不再动它：循环每轮量出的精确值不能被一次旧估算盖掉。
        assertTrue("only an unseeded share is filled in", vm.contains("if (contextFixedTokens > 0) return"))
        assertTrue("seeded from the same inputs the loop measures",
            vm.contains("contextFixedTokens = ContextSizeMeter.estimateFixedTokens(buildSystemPrompt(), agentTools)"))

        assertTrue("pre-send seeds before it measures",
            ProductionSources.read("ui/chat/ChatViewModelPreSendContext.kt")
                .contains("ensureContextFixedTokens()\n    val m = contextMeasurement()"))
        assertTrue("the glow seeds, then reads the shared measurement",
            vm.contains("ensureContextFixedTokens()\n        val measured = measureOutboundContextTokens()"))
        // 辉光不再自己重写那条公式：整族只有一个测量入口（source 与比值也才同出一处）。
        val glow = vm.substringAfter("internal fun publishMeasuredContextUsage() {").substringBefore("\n    }")
        assertTrue("glow body window (${glow.length} chars)", glow.length in 1..400)
        assertFalse("the glow must not re-derive the measurement", glow.contains("ContextSizeMeter.calibrated("))
        assertTrue("the glow reads measureOutboundContextTokens()", glow.contains("measureOutboundContextTokens()"))

        // 这一份份额本身就参与判定：同一个请求，少了它在线下，带上它过线。
        val share = ContextSizeMeter.estimateFixedTokens(
            "You are Minis, a capable AI assistant running on the user's Android device.",
            listOf(
                AgentToolDefinition(
                    name = "shell_execute",
                    description = "Run a shell command inside the session sandbox and return its output.",
                    parameters = mapOf("command" to AgentToolParam("string", "The command line to run.")),
                ),
            ),
        )
        assertTrue("a prompt plus one tool schema is a real share (got $share)", share > 0)
        val historyOnly = policy.compactThreshold - share
        assertEquals("the same request WITHOUT the share reads as under the line",
            ContextPolicy.CheckResult.OK, policy.check(historyOnly, window))
        assertEquals("…and over it once the share is added, so the share decides the verdict",
            ContextPolicy.CheckResult.NEEDS_COMPACT, policy.check(historyOnly + share, window))
    }

    // [T-ctx-measure-outbound] 比值与它的**来源**出自同一处：source 描述的就是
    // [ContextSizeMeter.ratioFor] 实际走的那条分支。此前本移植把来源另算一遍
    // （`scale(model) != 1.0` → own），于是一个「没量过的模型 + 内存里有旧比值」的
    // 请求会报成 borrowed，比值却是 1.0 —— 日志与判定说的不是同一件事。
    @Test
    fun `the measurement source names the branch ratioFor actually took`() {
        val known = mapOf("gpt-5" to 1.05)
        assertEquals("own", ContextSizeMeter.ratioSource("gpt-5", known, 1.30))
        assertEquals(1.05, ContextSizeMeter.ratioFor("gpt-5", known, 1.30), 1e-9)
        assertEquals("borrowed", ContextSizeMeter.ratioSource("gemini-4", known, 1.05))
        assertEquals("a borrowed ratio carries the margin — 1.0 would under-read a denser tokenizer",
            1.05 * ContextSizeMeter.UNCALIBRATED_MODEL_MARGIN,
            ContextSizeMeter.ratioFor("gemini-4", known, 1.05), 1e-9)
        assertEquals("default", ContextSizeMeter.ratioSource("gemini-4", known, null))
        assertEquals(1.0, ContextSizeMeter.ratioFor("gemini-4", known, null), 1e-9)
        // No model selected at all takes the same two branches.
        assertEquals("default", ContextSizeMeter.ratioSource(null, emptyMap(), null))
        assertEquals("borrowed", ContextSizeMeter.ratioSource(null, emptyMap(), 1.4))
    }

    // [T-ctx-measure-outbound] 消费点一致：测量只在**真的发请求**的那一支被记账，
    // 而且记账的返回值就是这次请求的 max_tokens 输入。脚本回合（预置工具调用）不发
    // 请求，所以它既不记账也不改 max_tokens —— 否则一次预置命令会把上一轮请求的
    // 估算与窗口记录冲掉，溢出归因从此落到错的一轮上。
    @Test
    fun `the dispatch record is taken only for a real request, and it sizes max_tokens`() {
        val loop = chatSrc.substringAfter("suspend fun ChatViewModel.runAgentLoop(")
        val branchStart = loop.indexOf("val turnChunks: kotlinx.coroutines.flow.Flow<LLMStreamChunk> = if (scriptedTurn != null) {")
        assertTrue("the scripted branch exists", branchStart > 0)
        val elseStart = loop.indexOf("} else {", branchStart)
        val scripted = loop.substring(branchStart, elseStart)
        val real = loop.substring(elseStart, loop.indexOf("turnChunks.collect { chunk ->", elseStart))
        assertFalse("a scripted turn makes no request", scripted.contains("recordContextDispatch("))
        assertFalse("and it must not size a request it never makes", scripted.contains("dynamicMaxTokens("))
        assertTrue("recorded on the request branch",
            real.contains("val dispatchInputTokens = recordContextDispatch(outboundHistory, currentProvider.model)"))
        assertTrue("max_tokens is sized from THIS dispatch's measurement",
            real.contains("maxTokens = dynamicMaxTokens(currentProvider, dispatchInputTokens),"))
        // …and the recorder returns the CALIBRATED size: the provider's window is
        // spent in the provider's own tokens, not in our raw character estimate.
        assertTrue(chatSrc.contains("lastDispatchPredicted = ContextSizeMeter.calibrated(lastDispatchEstimate, lastDispatchRatio)"))
        assertTrue(chatSrc.contains("return lastDispatchPredicted"))
        // The pair is persisted with the keys the reload parses back.
        val row = """{"latestContextTokens":140000,"estimatedRequestTokens":100000,"estimatedFixedTokens":9000,"calibrationModelId":"gpt-5"}"""
        val sample = ContextSizeMeter.calibrationSample(row)!!
        assertEquals(140_000, sample.reported)
        assertEquals(100_000, sample.estimated)
        assertEquals(9_000, sample.fixedTokens)
        assertEquals("gpt-5", sample.modelId)
    }

    // [T-ctx-ratio-smoothing] A rise applies at once (under-counting is what
    // overflows); a fall moves 30% of the way, so one cache-heavy or
    // image-light turn cannot drag the ratio down by itself.
    @Test
    fun calibrationRatioSmoothingIsAsymmetric() {
        assertEquals(1.4, ContextSizeMeter.smoothed(null, 1.4), 1e-9)
        assertEquals(1.6, ContextSizeMeter.smoothed(1.2, 1.6), 1e-9)
        assertEquals(1.4 + 0.3 * (1.0 - 1.4), ContextSizeMeter.smoothed(1.4, 1.0), 1e-9)
        // A sustained fall still converges: five low samples close most of the gap.
        var r = 1.8
        repeat(5) { r = ContextSizeMeter.smoothed(r, 1.0) }
        assertTrue("converges toward the new level (r=$r)", r < 1.15 && r > 1.0)
        // One outlier among steady samples costs a fraction, not the whole jump.
        assertEquals(1.33, ContextSizeMeter.smoothed(1.36, 1.26), 1e-9)
    }

    @Test
    fun ctxMeterLogsCoverEveryStage() {
        val vm = chatSrc
        for (tag in listOf("[CtxMeter] decide site=", "[CtxMeter] dispatch model=", "[CtxMeter] actual model=",
            "[CtxMeter] rejected", "[CtxMeter] seed session=", "[CtxMeter] warmup trimmed",
            "[CtxMeter] compacted marker=", "[CtxMeter] reverted marker=")) {
            assertTrue("missing log $tag", vm.contains(tag))
        }
        assertTrue(vm.contains("logContextDecision(\"pre-send\", m, policy.compactThreshold, window, verdict.name)"))
        assertTrue(vm.contains("logContextDecision(\"in-loop\", m, policy.compactThreshold, window, verdict.name)"))
        assertTrue("revert log waits for the reload's measurement", vm.contains("pendingRevertLogMarker = current.id.take(8)"))
    }
}
