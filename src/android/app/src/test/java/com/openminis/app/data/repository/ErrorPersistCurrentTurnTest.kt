package com.openminis.app.data.repository

import com.openminis.app.ProductionSources
import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.GoalDao
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.db.MessageHeadRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * [T-android-error-persist-current-turn] GH#263 (iOS fd7ec79a2 / 910708619).
 *
 * A terminal error must be persisted onto the CURRENT turn. The old write
 * stamped the session's newest assistant row, which — when the failing turn had
 * persisted nothing — was the PREVIOUS turn's good reply, so after a reload the
 * banner sat under an answer that had worked.
 *
 * [ChatRepository.persistTurnError] is exercised for real against an in-memory
 * fake of the [ChatDao] methods it uses (a dynamic proxy; any other DAO call
 * fails the test). The ViewModel wiring is asserted against source, since the
 * VM needs a Context/DB/provider to construct.
 */
class ErrorPersistCurrentTurnTest {

    // ── fixtures ──────────────────────────────────────────────────────────

    private val userText = """[{"type":"text","value":"hello"}]"""
    private val toolResult =
        """[{"type":"toolResult","value":{"toolUseId":"t1","name":"shell","output":"ok","success":true,""" +
            """"snapshot":{"type":"text","text":"ok"}}}]"""
    private val assistantText = """[{"type":"text","value":"reply"}]"""
    private val assistantTool =
        """[{"type":"toolUse","value":{"toolUseId":"t1","name":"shell","input":"{}"}}]"""

    private class FakeDao {
        val rows = mutableListOf<MessageEntity>()
        val errorWrites = mutableListOf<Pair<String, String?>>()
        var headReads = 0

        fun add(id: String, role: String, parts: String, errorInfo: String? = null) {
            rows += MessageEntity(
                id = id, sessionId = "s", role = role, partsJson = parts,
                createdAt = 0L, sortOrder = rows.size, errorInfo = errorInfo,
            )
        }

        fun row(id: String) = rows.first { it.id == id }

        val dao: ChatDao = Proxy.newProxyInstance(
            ChatDao::class.java.classLoader, arrayOf(ChatDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "messageHeadNewestFirst" -> {
                    headReads++
                    val offset = args[1] as Int
                    rows.sortedByDescending { it.sortOrder }.getOrNull(offset)
                        ?.let { MessageHeadRow(it.id, it.role, it.partsJson) }
                }
                "updateMessageErrorInfo" -> {
                    val id = args[0] as String
                    val err = args[1] as String?
                    errorWrites += id to err
                    val i = rows.indexOfFirst { it.id == id }
                    if (i >= 0) rows[i] = rows[i].copy(errorInfo = err)
                    Unit
                }
                "nextSortOrder" -> (rows.maxOfOrNull { it.sortOrder } ?: -1) + 1
                "insertMessage" -> { rows += args[0] as MessageEntity; Unit }
                "updateLastAssistantError" -> {
                    val err = args[1] as String?
                    val i = rows.indexOfLast { it.role == "assistant" }
                    if (i >= 0) rows[i] = rows[i].copy(errorInfo = err)
                    Unit
                }
                "toString" -> "FakeDao"
                "hashCode" -> 0
                "equals" -> false
                else -> throw AssertionError("unexpected DAO call ${method.name}")
            }
        } as ChatDao

        // [T-android-goal-dao-ctor] ChatRepository 的构造在本移植新增了第 2 个参数
        // goalDao（goal 能力；上游 1.14 还是 ChatRepository(dao) 单参），所以本文件的
        // 每个构造点都要跟着补。本文件只跑 persistTurnError / 清错误的路径，从不读
        // session_goals：于是 goal 侧照抄上面 ChatDao 假件的写法——动态代理 + 意外调用
        // 即失败。任何 GoalDao 调用都算越界，直接炸掉，顺带守住"错误落盘不写 goal 行"。
        val goalDao: GoalDao = Proxy.newProxyInstance(
            GoalDao::class.java.classLoader, arrayOf(GoalDao::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "toString" -> "FakeGoalDao"
                "hashCode" -> 0
                "equals" -> false
                else -> throw AssertionError("unexpected GoalDao call ${method.name}")
            }
        } as GoalDao
    }

    // ── the bug: early failure on turn 2 ─────────────────────────────────

    @Test
    fun `an early failure on turn 2 does NOT stamp the previous reply - it gets a carrier`() = runBlocking {
        val f = FakeDao()
        f.add("u1", "user", userText)
        f.add("a1", "assistant", assistantText) // turn 1's good reply
        f.add("u2", "user", userText)           // turn 2 failed before any output
        val write = ChatRepository(f.dao, f.goalDao).persistTurnError("s", "HTTP 529 overloaded")

        assertNull("the previous turn's reply must stay clean", f.row("a1").errorInfo)
        assertTrue("expected a carrier, got $write", write is ChatRepository.TurnErrorWrite.Carrier)
        val carrier = f.rows.last()
        assertEquals((write as ChatRepository.TurnErrorWrite.Carrier).messageId, carrier.id)
        assertEquals("assistant", carrier.role)
        assertEquals("[]", carrier.partsJson)
        assertEquals("HTTP 529 overloaded", carrier.errorInfo)
        assertTrue("carrier sits after the turn's user row", carrier.sortOrder > f.row("u2").sortOrder)
        assertEquals("only the newest row needed reading", 1, f.headReads)
    }

    @Test
    fun `a first-turn failure also gets a carrier (was a silent no-op before)`() = runBlocking {
        val f = FakeDao()
        f.add("u1", "user", userText)
        val write = ChatRepository(f.dao, f.goalDao).persistTurnError("s", "boom")
        assertTrue(write is ChatRepository.TurnErrorWrite.Carrier)
        assertEquals("boom", f.rows.last().errorInfo)
    }

    @Test
    fun `a failure after tool rounds stamps THIS turn's newest assistant row`() = runBlocking {
        val f = FakeDao()
        f.add("u1", "user", userText)
        f.add("a1", "assistant", assistantText)
        f.add("u2", "user", userText)
        f.add("a2", "assistant", assistantTool)
        f.add("r2", "user", toolResult) // tool-result row does not start a turn
        val write = ChatRepository(f.dao, f.goalDao).persistTurnError("s", "boom")
        assertEquals(ChatRepository.TurnErrorWrite.Stamped("a2"), write)
        assertEquals("boom", f.row("a2").errorInfo)
        assertNull(f.row("a1").errorInfo)
        assertEquals("no carrier inserted", 5, f.rows.size)
    }

    @Test
    fun `a mid-reply failure stamps the reply the turn persisted`() = runBlocking {
        val f = FakeDao()
        f.add("u1", "user", userText)
        f.add("a1", "assistant", assistantText)
        val write = ChatRepository(f.dao, f.goalDao).persistTurnError("s", "stream dropped")
        assertEquals(ChatRepository.TurnErrorWrite.Stamped("a1"), write)
    }

    @Test
    fun `a second failure of the same turn re-stamps the carrier instead of adding another`() = runBlocking {
        val f = FakeDao()
        f.add("u1", "user", userText)
        f.add("a1", "assistant", assistantText)
        f.add("u2", "user", userText)
        val repo = ChatRepository(f.dao, f.goalDao)
        val first = repo.persistTurnError("s", "first") as ChatRepository.TurnErrorWrite.Carrier
        val second = repo.persistTurnError("s", "second")
        assertEquals(ChatRepository.TurnErrorWrite.Stamped(first.messageId), second)
        assertEquals(1, f.rows.count { ChatRepository.isEmptyAssistantCarrier(it.role, it.partsJson) })
        assertEquals("second", f.rows.last().errorInfo)
    }

    @Test
    fun `retry's clear path finds the carrier, and a successful retry leaves it hidden`() = runBlocking {
        val f = FakeDao()
        f.add("u1", "user", userText)
        f.add("a1", "assistant", assistantText)
        f.add("u2", "user", userText)
        val repo = ChatRepository(f.dao, f.goalDao)
        val carrier = repo.persistTurnError("s", "boom") as ChatRepository.TurnErrorWrite.Carrier
        // retryLast → clearPersistedLastAssistantError → updateLastAssistantError(sid, null)
        repo.updateLastAssistantError("s", null)
        assertNull("the clear must land on the carrier", f.row(carrier.messageId).errorInfo)
        assertNull(f.row("a1").errorInfo)
        // The retried turn's reply lands after it; a later failure of a NEW
        // turn must still skip both of these.
        f.add("a2", "assistant", assistantText)
        f.add("u3", "user", userText)
        assertTrue(repo.persistTurnError("s", "again") is ChatRepository.TurnErrorWrite.Carrier)
        assertNull(f.row("a2").errorInfo)
    }

    @Test
    fun `an empty session writes nothing`() = runBlocking {
        val f = FakeDao()
        val write = ChatRepository(f.dao, f.goalDao).persistTurnError("s", "boom")
        assertTrue(write is ChatRepository.TurnErrorWrite.Skipped)
        assertTrue(f.rows.isEmpty())
    }

    // ── the pure classification ──────────────────────────────────────────

    @Test
    fun `turn-start classification matches iOS currentTurnStartIndex`() {
        assertTrue(ChatRepository.isTurnStartingUserParts(userText))
        assertFalse("tool results continue a turn", ChatRepository.isTurnStartingUserParts(toolResult))
        // The snapshot's nested "type":"text" is inside a toolResult value, not a part.
        assertFalse(ChatRepository.isTurnStartingUserParts(toolResult))
        assertTrue("any non-tool-result part starts a turn",
            ChatRepository.isTurnStartingUserParts(
                "[" + toolResult.removePrefix("[").removeSuffix("]") + """,{"type":"text","value":"x"}]"""))
        assertTrue("image-only user turn starts a turn",
            ChatRepository.isTurnStartingUserParts("""[{"type":"mediaRef","value":{}}]"""))
        assertFalse("empty parts: nothing a user typed (iOS parity)", ChatRepository.isTurnStartingUserParts("[]"))
        assertTrue("unparseable counts as a turn start — never walk past it onto the previous reply",
            ChatRepository.isTurnStartingUserParts("not json"))
    }

    @Test
    fun `pure walk picks the same row as the repository`() {
        fun h(id: String, role: String, parts: String) = MessageHeadRow(id, role, parts)
        assertNull(ChatRepository.currentTurnErrorRowId(listOf(h("u2", "user", userText), h("a1", "assistant", assistantText))))
        assertEquals("a2", ChatRepository.currentTurnErrorRowId(
            listOf(h("r2", "user", toolResult), h("a2", "assistant", assistantTool), h("u2", "user", userText))))
        assertNull(ChatRepository.currentTurnErrorRowId(emptyList()))
    }

    @Test
    fun `only an empty assistant row is a carrier`() {
        assertTrue(ChatRepository.isEmptyAssistantCarrier("assistant", "[]"))
        assertTrue(ChatRepository.isEmptyAssistantCarrier("assistant", " [] "))
        assertFalse(ChatRepository.isEmptyAssistantCarrier("assistant", assistantText))
        assertFalse(ChatRepository.isEmptyAssistantCarrier("user", "[]"))
    }

    // ── ViewModel wiring (source facts) ──────────────────────────────────

    /**
     * [T-android-error-persist-current-turn] 本移植把巨型 `ChatViewModel.kt` 拆成了
     * `ui/chat/ChatViewModelXxxExt.kt`（约 60 个文件），所以「ViewModel 侧的源码事实」
     * 必须在整个 `ui/chat` 源码集上核查，而不是只读 `ChatViewModel.kt` 一个文件：
     * 上游 1.14 的单文件断言在本移植上会「扫不到就静默变绿」，那才是真的丢语义。
     * 被断言的语义一字未改，覆盖面只增不减。
     */
    private val chatSources by lazy {
        ProductionSources.allKotlinFiles()
            .filter { it.parentFile?.name == "chat" }
            .associate { it.name to it.readText() }
    }

    private val chatText by lazy { chatSources.values.joinToString("\n") }

    /**
     * [T-android-error-persist-current-turn] 定位「实现某一签名的那一个 ui/chat 源文件」。
     * 本移植（以及正在进行的 `ChatViewModel.kt` → `ChatViewModelXxxExt.kt` 拆分）会不断
     * 搬动实现，写死文件名就等于「搬一次红一次」，而写死 `ChatViewModel.kt` 更糟——文件
     * 不存在时会抛异常或（用 substringAfter 时）静默退化成整文件。所以按签名找，找不到
     * 或有歧义就报错并列出候选。
     */
    private fun chatSourceWith(signature: String): String {
        val hit = chatSources.filterValues { it.contains(signature) }
        assertEquals(
            "expected exactly one ui/chat source declaring `$signature`, found ${hit.keys}",
            1, hit.size,
        )
        return hit.values.first()
    }

    /** 从 [signature] 起括号配平地截出函数体（含首尾花括号）；找不到就失败。 */
    private fun functionBody(src: String, signature: String): String {
        val start = src.indexOf(signature)
        require(start >= 0) { "missing $signature" }
        val open = src.indexOf('{', start)
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return src.substring(open, i + 1)
            }
            i++
        }
        error("unbalanced $signature")
    }

    @Test
    fun `setInlineError writes through persistTurnError, never the last-assistant UPDATE`() {
        // [T-android-error-persist-current-turn] 本移植把 setInlineError 拆成了
        // ui/chat 下的扩展函数（上游 1.14 是 ChatViewModel.kt:9491 的私有成员）；
        // 按签名定位、扫描面跟着搬家，语义不变。用括号配平的截取代替「截到下一个
        // 函数声明」，免得函数被搬走后截出空串假绿。
        val body = functionBody(
            chatSourceWith("fun ChatViewModel.setInlineError(errorText: String) {"),
            "fun ChatViewModel.setInlineError(errorText: String) {",
        )
        assertTrue(body.contains("chatRepository.persistTurnError(sid, safeError)"))
        assertFalse("a WRITE through updateLastAssistantError is the GH#263 bug",
            body.contains("updateLastAssistantError(sid, safeError)"))
        // 全 ui/chat 源码集：任何一处非 null 的 last-assistant 写入都是 GH#263 那个 bug。
        assertFalse("no non-null write anywhere",
            Regex("updateLastAssistantError\\(sid, (?!null)").containsMatchIn(chatText))
    }

    @Test
    fun `the load path shows an error-only carrier and hides a cleared one`() {
        // 载入路径的 UI 映射（上游 17216-17218）。按原文定位而不是按文件名：
        // 这段实现会随 ui/chat 的拆分搬家，且必须恰好出现在一处（漏掉 = 空载体
        // 变成幻影空气泡；多出来 = 有人复制了第二份判定）。
        val needle =
            "if (entity.role == \"assistant\" && text.isBlank() && blocks.isEmpty() &&\n" +
                "                entity.errorInfo.isNullOrBlank()\n" +
                "            ) return@mapNotNull null"
        val hits = chatSources.filterValues { it.contains(needle) }
        assertEquals(
            "the error-carrier exception must exist exactly once in ui/chat, found ${hits.keys}",
            1, hits.size,
        )
    }

    @Test
    fun `every agentHistory rebuild from rows skips carriers`() {
        // [T-android-error-persist-current-turn] 上游 1.14 在 5 个重建点各贴了一条
        // 同样的一行守卫（ChatViewModel.kt:6376 / 7976 / 8084 / 8173 / 8553）。
        // 本移植把这 5 个点收敛成一个共享入口 `rebuildAgentHistoryFromDatabase`
        // （rerun / retry / delete / truncate 都经 `awaitBoundedHistoryRebuild` 走它），
        // 外加 load 路径那个窗口解析循环。于是判据按本移植的形状表达，意图不变：
        //   1) 凡是把行翻成 LLMMessage 的源码文件，都必须带 carrier 过滤——
        //      守卫只挂在没人走的死路上，或某个重建点漏挂，都会红；
        //   2) 收敛后的共享入口必须真的被那些裁剪路径调用（≥5 个调用点，
        //      与上游「≥5 个重建点」同一量级），否则共享入口本身就是死代码。
        val rebuildFiles = chatSources.filterValues {
            Regex("""\.toLLMMessage\(\)""").containsMatchIn(it)
        }
        assertTrue("expected rebuild sites, got ${rebuildFiles.keys}", rebuildFiles.size >= 2)
        val unguarded = rebuildFiles.filterValues {
            !Regex("""isEmptyAssistantCarrier\((?:it|entity)\.role, (?:it|entity)\.partsJson\)""")
                .containsMatchIn(it)
        }.keys
        assertTrue("each toLLMMessage rebuild needs its carrier guard: $unguarded", unguarded.isEmpty())
        val sharedEntryCallers = Regex("""(?:awaitBoundedHistoryRebuild|rebuildAgentHistoryFromDatabase)\(""")
            .findAll(chatText).count()
        assertTrue("expected rebuild sites, got $sharedEntryCallers", sharedEntryCallers >= 5)
    }
}
