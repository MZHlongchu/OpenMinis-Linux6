package com.openminis.app.data.db

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-search-exists-limit] 会话搜索的 SQL 形状守卫。
 *
 * 本模块没有 Robolectric（见 RestrictedSettingsManagerTest 的同一说明），跑不了真
 * 实的 Room 查询；而这条结论的收益全在 SQL 形状上，所以与 ResourceBoundaryTest
 * 同口径直接对源码断言：
 *  - `LEFT JOIN messages` + `DISTINCT`（把命中行按消息数放大、逐行读 fat 的
 *    parts_json，且无法在「本会话已命中」处提前结束）不许回来；
 *  - 每条会话的短路（EXISTS）与下推到 SQL 的 LIMIT 必须在；
 *  - repository 必须真的把 limit 传下去（否则 DAO 有了 LIMIT 也没人给值）；
 *  - [T-search-truncation-visible] 这个 LIMIT 必须是显式的：仓库标记 → VM 消费 →
 *    UI 说明，缺一环就又变成静默截断（harness-core `list` 5000 的同类问题）。
 */
class SessionSearchQueryShapeTest {

    private val daoSource = File("src/main/java/com/openminis/app/data/db/ChatDao.kt").readText()
    private val repoSource =
        File("src/main/java/com/openminis/app/data/repository/ChatRepository.kt").readText()
    private val vmSource =
        File("src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt").readText()
    private val screenSource =
        File("src/main/java/com/openminis/app/ui/sessions/SessionListScreen.kt").readText()

    /** The @Query body of `searchSessions`, whitespace-normalized. */
    private val searchSql: String = daoSource
        .substringBefore("suspend fun searchSessions")
        .substringAfterLast("@Query(\"\"\"")
        .substringBefore("\"\"\")")
        .replace(Regex("\\s+"), " ")
        .trim()

    @Test
    fun `session search short circuits per session instead of joining every hit row`() {
        assertTrue("EXISTS 短路缺失: $searchSql", searchSql.contains("EXISTS ("))
        assertTrue("messages 侧匹配条件缺失: $searchSql", searchSql.contains("m.parts_json LIKE :pattern"))
        assertTrue("标题侧匹配条件缺失: $searchSql", searchSql.contains("s.title LIKE :pattern"))
        assertFalse("LEFT JOIN 会把命中行按消息数放大: $searchSql", searchSql.contains("LEFT JOIN"))
        assertFalse("EXISTS 后一行一会话，DISTINCT 是多余开销: $searchSql", searchSql.contains("DISTINCT"))
    }

    @Test
    fun `session search bounds the result set in SQL`() {
        assertTrue("LIMIT 未下推到 SQL: $searchSql", searchSql.contains("LIMIT :limit"))
        assertTrue(
            "repository 未把上限（+1 行探针）传给 DAO",
            repoSource.contains("dao.searchSessions(\"%\$query%\", probe)"),
        )
        assertTrue(
            "repository 缺少默认上限（MAX_SEARCH_SESSIONS）",
            repoSource.contains("limit: Int = MAX_SEARCH_SESSIONS"),
        )
    }

    /**
     * [T-search-truncation-visible] 上限不许静默：仓库要显式标记，VM 要消费标记，
     * UI 要把它说出来。任何一环缺失，用户都会以为"历史里就这么多命中"。
     */
    @Test
    fun `the search cap is explicit end to end instead of a silent truncation`() {
        assertTrue(
            "searchSessions 必须返回带截断标记的结果页",
            repoSource.contains("): SessionSearchResult {"),
        )
        assertTrue(
            "探针必须多取一行才判定得出「还有更多」",
            repoSource.contains("else bounded + 1"),
        )
        assertTrue(
            "截断必须写进返回值",
            repoSource.contains("SessionSearchResult(rows.take(bounded), bounded, truncated)"),
        )
        assertTrue(
            "VM 必须消费截断标记（否则上限仍然是静默的）",
            vmSource.contains("searchTruncated.value = page.truncated"),
        )
        assertTrue(
            "搜索 UI 必须把截断说出来",
            screenSource.contains("search_truncated_notice"),
        )
    }
}
