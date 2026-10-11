package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-usage-capsule-live] The usage capsule must be reachable on a
 * reply that JUST finished streaming, not only after the session is reopened.
 *
 * Reported: tapping blank space in a freshly streamed reply showed nothing;
 * leaving and re-entering the session made the capsule appear. Root cause:
 * `ChatMessage.tokenUsage` / `completedAt` were populated ONLY by the DB-load
 * path (`toChatMessages()`), never on the turn-finish path — so the live bubble
 * had `tokenUsage == null`, `buildFlatChatItems` emitted no AssistantUsage row,
 * and the blank-tap toggle had nothing to reveal. loadSession() parsed the row
 * from the DB on re-entry, which is why it "came back".
 *
 * Two layers are pinned here:
 *  - the flat build's contract (a non-streaming reply WITH usage yields exactly
 *    one AssistantUsage row; without usage, none) — this is the gate the live
 *    path was silently failing, and it documents the failure mode; and
 *  - a source assertion that `persistAssistantTurn` stamps the live bubble the
 *    same way the load path does, from both callers in the agent loop. The VM
 *    needs a Context/DB/provider to construct, so (as with SwitchModelGhostRetry
 *    and AgentHistoryRebuildIdempotence) the wiring is asserted against source.
 */
class UsageCapsuleLiveStampTest {

    private val usage = ChatTokenUsage(
        inputTokens = 120, outputTokens = 30,
        cacheCreationTokens = 0, cacheReadTokens = 0, latestContextTokens = 150,
    )

    private fun user(id: String) = ChatMessage(id = id, role = "user", content = "say ALPHA")

    private fun reply(id: String, streaming: Boolean, withUsage: Boolean) = ChatMessage(
        id = id, role = "assistant", content = "",
        isStreaming = streaming,
        toolBlocks = listOf(AssistantBlock(id = "$id-t", kind = "text", content = "ALPHA")),
        tokenUsage = if (withUsage) usage else null,
        completedAt = if (withUsage) 1_700_000_000_000L else null,
    )

    private fun usageRows(vararg msgs: ChatMessage) =
        buildFlatChatItems(msgs.toList()).filterIsInstance<FlatChatItem.AssistantUsage>()

    // ── the flat build's contract ──────────────────────────────────────────

    @Test
    fun `a finished reply with usage yields exactly one capsule row`() {
        val rows = usageRows(user("u1"), reply("a1", streaming = false, withUsage = true))
        assertEquals(1, rows.size)
        assertEquals("a1", rows[0].messageId)
        assertEquals(usage, rows[0].usage)
    }

    @Test
    fun `a finished reply WITHOUT usage yields no row — the pre-fix live state`() {
        // This is what every freshly streamed reply looked like before the fix:
        // streaming over, but tokenUsage never stamped. Nothing to reveal.
        val rows = usageRows(user("u1"), reply("a1", streaming = false, withUsage = false))
        assertTrue("no usage → no capsule row; this is the bug's failure mode", rows.isEmpty())
    }

    @Test
    fun `usage on a still-streaming reply is held back until the stream ends`() {
        // The tool-turn path stamps usage while the bubble is still streaming.
        // The gate must keep the row hidden then (a finish time for a turn that
        // has not finished would be wrong) and release it once streaming stops.
        assertTrue(usageRows(user("u1"), reply("a1", streaming = true, withUsage = true)).isEmpty())
        assertEquals(1, usageRows(user("u1"), reply("a1", streaming = false, withUsage = true)).size)
    }

    // ── the live path must stamp the bubble ────────────────────────────────

    /**
     * [T-u14-merge-vm-split] ChatViewModel 在本移植里不是**一个**文件，而是一族
     * `ChatViewModel*.kt`（ChatViewModel.kt + ChatViewModelAgentLoopExt.kt +
     * ChatViewModelPersistMessage.kt + …）。上游的每个 `File("…/ChatViewModel.kt")`
     * 断言都假定所有符号都住在那一个文件里，合并后它们会静默落空（`exists()` 通过、
     * 符号找不到），或者因为某个符号被搬走而整条变红——开工时本用例四条失败里就有一
     * 条是这么来的（agent 循环被拆进 ChatViewModelAgentLoopExt.kt）。
     *
     * 于是按「声明」在整个族里找，而不是按文件名找：所有 ChatViewModel*.kt 拼成一份
     * 文本，文件之间插入一个 4 空格缩进的哨兵成员行，保证「截到下一个成员」的函数体
     * 提取不会跨过文件边界把下一个文件的内容吞进 body。
     */
    private val vmSrc by lazy {
        val dir = File("src/main/java/com/openminis/app/ui/chat")
        assertTrue("missing chat source dir: ${dir.absolutePath}", dir.isDirectory)
        val files = dir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith("ChatViewModel") && it.name.endsWith(".kt") }
            .sortedBy { it.name }
        assertTrue(
            "expected the ChatViewModel source family, found ${files.map { it.name }}",
            files.size >= 2,
        )
        files.joinToString("\n    internal fun __vmSourceFileBoundary__() {}\n") { it.readText() }
    }

    /** The same concatenated family — the agent-loop exits are in one of its files. */
    private val agentLoopSrc by lazy { vmSrc }

    /**
     * [T-u14-merge-persist-anchor] 取 `persistAssistantTurn` 的函数体。
     *
     * 上游的取法是 `substringAfter("private suspend fun persistAssistantTurn(")`
     * 再 `substringBefore("@Deprecated")`。本移植里两个锚点都落空了：
     *  - 该函数是 `internal`（拆分后同包的 Ext 文件也要能调用），不再是 `private`；
     *  - 那个 `@Deprecated` 的 legacy `persistAssistantMessage` 已被拆进
     *    ChatViewModelPersistMessage.kt，本文件里再没有 `@Deprecated`。
     *
     * `substringAfter` 找不到分隔符时**原样返回整个字符串**，于是 body 变成整份源码，
     * 断言退化成「整个文件里有没有这句话」——比原意弱得多，而且会假绿：
     * `ChatTokenUsage.parse(entity.tokenUsage)` 在 toChatMessages 里也有一份，整份
     * 源码作 body 时这条断言会被**另一处**满足，哪怕 persistAssistantTurn 里根本没写。
     *
     * 所以这里改成「按声明找到起点 + 截到下一个 4 空格缩进的成员/KDoc」，只取这一个
     * 函数，可见性按本移植的真实写法匹配。
     */
    private fun persistBody(): String {
        val decl = Regex("(?:private|internal|public) suspend fun persistAssistantTurn\\(")
            .find(vmSrc)
        assertTrue(
            "persistAssistantTurn must be declared as a suspend fun in ChatViewModel.kt",
            decl != null,
        )
        val from = vmSrc.substring(decl!!.range.last + 1)
        val next = Regex("\n    (?:@|/\\*\\*|internal |private |public |suspend |fun |val |var |override )")
            .find(from)
        val body = if (next == null) from else from.substring(0, next.range.first)
        assertTrue(
            "extracted body must be the whole function, tail was: ...${body.takeLast(40)}",
            body.trimEnd().endsWith("}"),
        )
        return body
    }

    @Test
    fun `persistAssistantTurn stamps tokenUsage and completedAt onto the live bubble`() {
        val body = persistBody()
        assertTrue("must accept the UI bubble id", body.contains("uiAssistantId: String?"))
        assertTrue(
            "must derive usage from the persisted entity, exactly like toChatMessages()",
            body.contains("ChatTokenUsage.parse(entity.tokenUsage)"),
        )
        assertTrue(
            "must stamp both fields onto the matching live ChatMessage",
            body.contains("m.copy(tokenUsage = stampedUsage ?: m.tokenUsage, completedAt = entity.createdAt)"),
        )
        assertTrue("the _messages write must happen on Main", body.contains("withContext(Dispatchers.Main)"))
    }

    @Test
    fun `a turn without usage keeps the capsule an earlier turn stamped`() {
        // [T-android-usage-capsule-live-reload] The reload merge keeps
        // `msg.tokenUsage ?: prev.tokenUsage`; the live stamp must not assign a
        // null over an earlier tool turn's usage, or the capsule vanishes live
        // and reappears after a reload.
        val body = persistBody()
        assertTrue(body.contains("tokenUsage = stampedUsage ?: m.tokenUsage"))
        assertTrue("must not overwrite with a possibly-null value",
            !body.contains("m.copy(tokenUsage = stampedUsage,"))
        assertTrue("the reload merge applies the same rule",
            vmSrc.contains("tokenUsage = msg.tokenUsage ?: prev.tokenUsage,"))
    }

    /**
     * [T-u14-merge-persist-callers] 气泡 id 必须到达持久化/打戳步骤，**每一条**出口。
     *
     * 上游的断言扫的是两处字面量调用
     * `persistAssistantTurn(turnParts, lastUsage, turnReasoningContent, blockMeta…,
     * uiAssistantId = assistantId)`。本移植的形状不同，且不同的方式更严：
     *  - agent 循环被拆进 ChatViewModelAgentLoopExt.kt（RequestAgentLoop 的
     *    ChatViewModel 拆分系列），所以扫描面是那个文件；
     *  - 持久化入口多了一层包装 `persistAssistantTurnForRun(run, …)`：气泡 id 不再
     *    逐点写成字面量 `assistantId`，而是随 `run.assistantMessageId` 进入
     *    （默认参数 `uiMessageId: String? = run.assistantMessageId`），包装层再以
     *    `uiAssistantId = uiMessageId` 转交给打戳步骤；
     *  - 调用点从上游的 2 处（末轮 + tool 轮）变成 5 处：本移植另有 goal 续跑、
     *    stream-drop 续跑、sealed-turn 提交三条出口。上游只钉 2 处，正是「新增一条
     *    出口时没人提醒要交气泡 id」的缺口。
     *
     * 所以这里断言同一条产品性质、覆盖面更全：包装层必须把 run 上的气泡 id 转交，
     * 且每一条 agent 循环出口交出的都是一个携带气泡 id 的 run。少任何一处转交，那条
     * 出口的气泡就永远拿不到 capsule（正是本用例开头报告的症状）。
     */
    @Test
    fun `both agent-loop callers hand the bubble id to the persist step`() {
        val decl = Regex("suspend fun persistAssistantTurnForRun\\(").find(vmSrc)
        assertTrue("persistAssistantTurnForRun must be declared in ChatViewModel.kt", decl != null)
        val from = vmSrc.substring(decl!!.range.last + 1)
        val next = Regex("\n    (?:@|/\\*\\*|internal |private |public |suspend |fun |val |var |override )")
            .find(from)
        val wrapper = if (next == null) from else from.substring(0, next.range.first)
        assertTrue(
            "the wrapper must default the bubble id from the run",
            wrapper.contains("uiMessageId: String? = run.assistantMessageId"),
        )
        assertTrue(
            "the wrapper must hand that same id to the stamp step",
            wrapper.contains("uiAssistantId = uiMessageId"),
        )

        // Every persist exit of the agent loop: each must pass the run that
        // carries the bubble id (the first argument of the wrapper). Skip the
        // declaration itself — it is a `suspend fun`, not a call site.
        val firstArgs = Regex("persistAssistantTurnForRun\\(\\s*([^,\\n]+)")
            .findAll(agentLoopSrc)
            .filter { m ->
                val head = agentLoopSrc.substring((m.range.first - 20).coerceAtLeast(0), m.range.first)
                !head.contains("suspend fun ")
            }
            .map { it.groupValues[1].trim() }
            .toList()
        assertTrue(
            "expected the agent loop's persist exits (final turn, tool turn, " +
                "goal/stream-drop continuations, sealed-turn commit), found ${firstArgs.size}",
            firstArgs.size >= 2,
        )
        for (arg in firstArgs) {
            assertTrue(
                "every caller must hand over the run carrying the bubble id, got: $arg",
                arg.contains("Run"),
            )
        }
    }

    @Test
    fun `the live stamp mirrors the load path field for field`() {
        // If the load path ever changes how it derives these, the live path
        // must change with it — otherwise a reloaded bubble and a live one
        // would show different numbers for the same turn.
        //
        // [T-u14-merge-load-anchor] 上游的锚点是字面量
        // `"toChatMessages(): List<ChatMessage>"`。本移植的签名多了一个参数
        // （`toChatMessages(toolResultContext: List<MessageEntity> = emptyList())`，
        // 分页边界要用它拼 tool_result 上下文），那个字面量因此根本不存在：
        // `substringAfter` 落空时原样返回整份源码，两条断言就退化成「整个文件里有没有
        // 这句话」——而 persistAssistantTurn 里刚移植进来的同一段文本正好能满足它们，
        // 于是这两条会假绿。按声明定位 + 截到下一个成员，检查才真的落在载入路径上。
        val decl = Regex("fun List<MessageEntity>\\.toChatMessages\\(").find(vmSrc)
        assertTrue("toChatMessages must be declared in ChatViewModel.kt", decl != null)
        val from = vmSrc.substring(decl!!.range.last + 1)
        val next = Regex("\n    (?:@|/\\*\\*|internal |private |public |suspend |fun |val |var |override )")
            .find(from)
        val load = if (next == null) from else from.substring(0, next.range.first)
        assertTrue(
            "the extracted block must be the load path, tail was: ...${load.takeLast(40)}",
            load.trimEnd().endsWith("}"),
        )
        assertTrue(load.contains("ChatTokenUsage.parse(entity.tokenUsage)"))
        assertTrue(load.contains("completedAt = if (entity.role == \"assistant\") entity.createdAt else null"))
    }
}
