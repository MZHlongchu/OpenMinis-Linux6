package com.openminis.app.data.repository

import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.GoalDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * [T-search-truncation-visible] 会话搜索的条数上限（`MAX_SEARCH_SESSIONS`）不许静默。
 *
 * 上限本身是对的（每次防抖击键不能把全部命中会话实体拉回来），但"命中被砍掉了"必须
 * 从返回值里看得出来 —— 静默截断会让 UI（和用户）以为"历史里就这么多"，这正是
 * harness-core `list` 5000 条上限暴露出来的同类问题。
 *
 * Room 的 DAO 在单测里没有实现（需要 Android），所以用 JDK 动态代理做一个只回应
 * `searchSessions` 的假 DAO（挂起方法直接返回值即可，与 Room 生成代码的快路径一致，
 * 见 MessageBodyRefTest 的同一说明）。
 */
class SessionSearchTruncationTest {

    /** 假 DAO：库里一共有 [total] 条命中，`searchSessions` 只受传入 limit 限制。 */
    private class FakeSessions(private val total: Int) {
        var lastPattern: String? = null
        var lastLimit: Int = Int.MIN_VALUE
        var calls = 0

        fun rows(): List<ChatSessionEntity> = (0 until total).map { index ->
            ChatSessionEntity(
                id = "s$index",
                title = "title $index",
                modelId = "model",
                createdAt = index.toLong(),
                updatedAt = index.toLong(),
            )
        }
    }

    private fun repository(fake: FakeSessions): ChatRepository {
        val dao = Proxy.newProxyInstance(
            ChatDao::class.java.classLoader,
            arrayOf(ChatDao::class.java),
            InvocationHandler { proxy, method, rawArgs ->
                val args: Array<Any?> =
                    if (rawArgs == null) emptyArray() else Array(rawArgs.size) { rawArgs[it] }
                when (method.name) {
                    "equals" -> proxy === args.getOrNull(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "fake:ChatDao"
                    "searchSessions" -> {
                        fake.calls++
                        fake.lastPattern = args[0] as String
                        val limit = args[1] as Int
                        fake.lastLimit = limit
                        fake.rows().take(limit)
                    }
                    else -> error("unexpected ChatDao call: ${method.name}")
                }
            },
        ) as ChatDao
        val goalDao = Proxy.newProxyInstance(
            GoalDao::class.java.classLoader,
            arrayOf(GoalDao::class.java),
            InvocationHandler { proxy, method, rawArgs ->
                val args: Array<Any?> =
                    if (rawArgs == null) emptyArray() else Array(rawArgs.size) { rawArgs[it] }
                when (method.name) {
                    "equals" -> proxy === args.getOrNull(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "fake:GoalDao"
                    else -> error("unexpected GoalDao call: ${method.name}")
                }
            },
        ) as GoalDao
        return ChatRepository(dao, goalDao)
    }

    @Test
    fun `a hit count above the cap is reported instead of silently dropped`() = runBlocking {
        val fake = FakeSessions(ChatRepository.MAX_SEARCH_SESSIONS + 5)

        val page = repository(fake).searchSessions("hello")

        assertTrue("命中超过上限必须显式标记，不能当成全部命中", page.truncated)
        assertEquals(ChatRepository.MAX_SEARCH_SESSIONS, page.sessions.size)
        assertEquals(ChatRepository.MAX_SEARCH_SESSIONS, page.limit)
        assertNull("截断时不该假装知道精确总数", page.totalMatches)
        // 多取一行就能判定"还有更多"，不必为计数再扫一遍同样昂贵的 EXISTS 查询
        assertEquals(ChatRepository.MAX_SEARCH_SESSIONS + 1, fake.lastLimit)
        assertEquals("%hello%", fake.lastPattern)
    }

    @Test
    fun `an exact-fit result set is not reported as truncated`() = runBlocking {
        val page = repository(FakeSessions(ChatRepository.MAX_SEARCH_SESSIONS))
            .searchSessions("hello")

        assertFalse("命中数正好等于上限时不是截断", page.truncated)
        assertEquals(ChatRepository.MAX_SEARCH_SESSIONS, page.sessions.size)
        assertEquals(ChatRepository.MAX_SEARCH_SESSIONS, page.totalMatches)
    }

    @Test
    fun `a smaller result set reports its exact total`() = runBlocking {
        val page = repository(FakeSessions(3)).searchSessions("hello")

        assertFalse(page.truncated)
        assertEquals(3, page.sessions.size)
        assertEquals("未截断时总数是精确值", 3, page.totalMatches)
    }

    @Test
    fun `an explicit limit is searched and probed without overflowing`() = runBlocking {
        val fake = FakeSessions(4)
        val page = repository(fake).searchSessions("hello", limit = Int.MAX_VALUE)

        assertFalse(page.truncated)
        assertEquals(4, page.sessions.size)
        // +1 越界会变成负数，SQLite 把 `LIMIT -1` 当"无上限"—— 上限会以另一种形式消失
        assertEquals(Int.MAX_VALUE, fake.lastLimit)
    }

    @Test
    fun `a zero or negative limit still searches with a positive limit`() = runBlocking {
        val page = repository(FakeSessions(2)).searchSessions("hello", limit = 0)

        assertEquals(1, page.sessions.size)
        assertEquals(1, page.limit)
    }
}
