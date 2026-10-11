package com.openminis.app.evolution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [T-evolution-retention] `EvolutionStore` 的保留策略是纯函数，SQLite 那一段
 * 在无 Robolectric 的单测里跑不到，但"窗口多长、清哪两张表、删哪一列"可以在这里
 * 锁死：窗口被改小、表清单被换成 beliefs/proposals、或 SQL 被拼成非绑参形式都会红。
 */
class EvolutionRetentionTest {

    @Test
    fun `only the two evidence tables are pruned, over a 30 day window`() {
        val now = 1_700_000_000_000L
        assertEquals(30L * 24 * 60 * 60 * 1000, EvolutionRetention.WINDOW_MS)
        assertEquals(listOf("tool_fails", "observations"), EvolutionRetention.EVIDENCE_TABLES)
        assertEquals(now - EvolutionRetention.WINDOW_MS, EvolutionRetention.cutoff(now))
        // 用户可见的演化账本不在清理范围内。
        assertFalse("beliefs" in EvolutionRetention.EVIDENCE_TABLES)
        assertFalse("proposals" in EvolutionRetention.EVIDENCE_TABLES)
        assertFalse("harvest_watermarks" in EvolutionRetention.EVIDENCE_TABLES)
    }

    @Test
    fun `prune statements keep the cutoff as a bind argument`() {
        assertEquals(
            "DELETE FROM tool_fails WHERE created_at < ?",
            EvolutionRetention.pruneSql("tool_fails"),
        )
        assertEquals(
            "DELETE FROM observations WHERE created_at < ?",
            EvolutionRetention.pruneSql("observations"),
        )
    }
}
