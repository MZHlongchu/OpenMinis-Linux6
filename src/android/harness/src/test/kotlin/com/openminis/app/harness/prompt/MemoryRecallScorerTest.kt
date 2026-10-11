package com.openminis.app.harness.prompt

import com.openminis.app.harness.prompt.MemoryRecallScorer.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-taixu-2.5] BM25 召回打分器验收：泛化抑制、CJK bigram、IDF 判别、
 * scope×新鲜度、top-5 预算。
 */
class MemoryRecallScorerTest {

    @Test
    fun `generic turns are suppressed`() {
        val candidates = listOf(Candidate("k", "调用链路分析", "global", 0))
        assertTrue(MemoryRecallScorer.selectRecall("继续", candidates).isEmpty())
        assertTrue(MemoryRecallScorer.selectRecall("好的", candidates).isEmpty())
        assertTrue(MemoryRecallScorer.selectRecall("ok", candidates).isEmpty())
        // 泛化 token 剔除后无有效词 → 空
        assertTrue(MemoryRecallScorer.selectRecall("请帮忙看一下", candidates).isEmpty())
        // 带信息词的长消息不受抑制
        assertTrue(MemoryRecallScorer.selectRecall("继续帮我改调用链路分析", candidates).isNotEmpty())
    }

    @Test
    fun `single char generic entries do not swallow two char queries`() {
        // [T-b15-generic-substring] 「运行 / 执行 / 银行 / 不行」曾因含单字「行」被
        // contains 整体判成泛化轮次 → 召回静默不触发；拉丁短词同理（"book" 含 "ok"）。
        val candidates = listOf(Candidate("k", "运行 执行 银行 记录", "global", 0))
        assertTrue(MemoryRecallScorer.effectiveQueryTerms("运行").isNotEmpty())
        assertTrue(MemoryRecallScorer.selectRecall("运行一下", candidates).isNotEmpty())
        assertTrue(MemoryRecallScorer.effectiveQueryTerms("book").isNotEmpty())
        // 单字/整词泛化条目本身仍被抑制
        assertTrue(MemoryRecallScorer.selectRecall("行", candidates).isEmpty())
        assertTrue(MemoryRecallScorer.effectiveQueryTerms("嗯").isEmpty())
        assertTrue(MemoryRecallScorer.effectiveQueryTerms("ok").isEmpty())
        // 多字 CJK 短语保留「包含即命中」
        assertTrue(MemoryRecallScorer.effectiveQueryTerms("继续吧").isEmpty())
    }

    @Test
    fun `cjk bigram lets word overlap hit while scattered chars do not`() {
        val tokens = MemoryRecallScorer.tokenize("调用链路分析")
        assertTrue(tokens.contains("调用"))
        assertTrue(tokens.contains("链路"))
        // 拉丁整词小写
        assertEquals(listOf("bm25", "recall"), MemoryRecallScorer.tokenize("BM25 Recall"))
    }

    @Test
    fun `single char cjk query falls back to unigram matching`() {
        // [T-cjk-unigram-recall] bigram-only 切词下，单字查询词（「问」）只作为
        // bigram 组成部分出现在文档侧，永远命不中——旧 contains 匹配能命中，
        // unigram 回退恢复它；不含该字的候选仍不命中。
        val doc = Candidate("k", "问题记录：重试后重复触发", "global", 0)
        assertTrue(MemoryRecallScorer.selectRecall("问", listOf(doc)).isNotEmpty())
        val other = Candidate("k2", "完全无关的另一段记录", "global", 0)
        assertTrue(MemoryRecallScorer.selectRecall("问", listOf(other)).isEmpty())
    }

    @Test
    fun `idf makes rare query terms outrank common ones`() {
        // 两行候选：一行只有常见词，一行含判别词
        val common = Candidate("k1", "我们一个这个 我们一个这个 我们一个这个", "global", 0)
        val rare = Candidate("k2", "BM25 召回打分", "global", 0)
        val scored = MemoryRecallScorer.selectRecall("BM25 打分", listOf(common, rare))
        assertEquals(1, scored.size)
        assertEquals("k2", scored[0].candidate.key)
    }

    @Test
    fun `scope and freshness apply their multipliers`() {
        val now = 1_700_000_000_000L
        val fresh = Candidate("k", "调用链路分析", "project", now)
        val old = Candidate("k", "调用链路分析", "session", now - 200L * 86_400_000L)
        val scored = MemoryRecallScorer.selectRecall("调用链路", listOf(old, fresh), now)
        assertEquals("k", scored[0].candidate.key) // 同文同 key，project+新鲜 > session+陈旧
        assertTrue(scored[0].score > scored[1].score)
    }

    @Test
    fun `budget caps the result at five facts`() {
        val candidates = (1..8).map { Candidate("k$it", "调用链路分析 $it", "global", 0) }
        val scored = MemoryRecallScorer.selectRecall("调用链路", candidates)
        assertEquals(MemoryRecallScorer.MAX_RECALL_FACTS, scored.size)
    }

    @Test
    fun `empty query or no candidates return empty`() {
        assertTrue(MemoryRecallScorer.selectRecall("", listOf(Candidate("k", "v"))).isEmpty())
        assertTrue(MemoryRecallScorer.selectRecall("调用链路", emptyList()).isEmpty())
    }
}
