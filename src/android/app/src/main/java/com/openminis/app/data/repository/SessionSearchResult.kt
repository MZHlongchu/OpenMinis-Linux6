package com.openminis.app.data.repository

import com.openminis.app.data.db.ChatSessionEntity

/**
 * [T-search-truncation-visible] 会话搜索的结果页。
 *
 * 条数上限（[limit]，默认 `ChatRepository.MAX_SEARCH_SESSIONS`）是下推到 SQL 的
 * `LIMIT`，命中更多时**必须显式**告诉调用方 [truncated]：静默截断会让 UI（和用户）
 * 以为"历史里就这么多"——harness-core 的 `list` 5000 静默截断是同一类问题。
 *
 * 判定只需多取一行（`LIMIT limit + 1`），不为精确总数再跑一遍同样昂贵的 EXISTS
 * 扫描，所以截断时 [totalMatches] 是 null（只知道"至少还有更多"），未截断时才是
 * 精确值。
 */
data class SessionSearchResult(
    val sessions: List<ChatSessionEntity>,
    /** 本次生效的条数上限。 */
    val limit: Int,
    /** true = 命中数超过 [limit]，[sessions] 只是最近的一批，不是全部命中。 */
    val truncated: Boolean,
) {
    /** 命中总数：未截断时精确；截断时 null（不为计数再扫一遍库）。 */
    val totalMatches: Int? get() = if (truncated) null else sessions.size
}
