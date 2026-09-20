package com.openminis.app.provider

import java.util.Locale

/**
 * Relay stations publish ids like `GPT-6免费` / `免费GPT-6 Astra`. After
 * exact and normalized catalog lookup fail, pick the candidate whose id/name
 * shares the most distinctive characters (命中最多字) with the stripped query.
 *
 * Generic brand-only overlap (`GPT`, `Claude`) is rejected so a local `GPT聊天`
 * cannot inherit gpt-4o limits.
 */
internal object ModelAliasMatcher {

    private val NOISE = listOf(
        "非官方", "中转站", "中转", "免费", "付费", "高速", "官方", "最新",
        "特价", "稳定", "镜像", "逆向", "公益", "测试", "试用", "畅享",
        "无限", "企业", "国内", "海外", "直连", "原生", "限时", "优惠",
        "折扣", "便宜", "满血", "渠道", "线路", "特供",
        "unofficial", "unlimited", "official", "premium", "discount",
        "reverse", "mirror", "latest", "trial", "promo", "cheap", "free",
        "fast", "test",
    ).sortedByDescending { it.length }

    private val GENERIC = setOf(
        "gpt", "openai", "claude", "anthropic", "gemini", "google", "grok",
        "xai", "qwen", "llama", "mistral", "deepseek", "glm", "kimi",
        "moonshot", "model", "chat", "llm", "ai",
    )

    private val VARIANTS = listOf("pro", "instant", "mini", "lite", "nano", "flash")

    fun stripNoise(text: String): String {
        var s = text
        for (n in NOISE) {
            s = s.replace(n, " ", ignoreCase = true)
        }
        return s.replace(Regex("\\s+"), " ").trim().trim('-', '_', '/')
    }

    fun tokens(text: String): List<String> {
        val stripped = stripNoise(text).lowercase(Locale.US)
        val normalized = stripped
            .replace(Regex("""[./_]+"""), "-")
            .replace(Regex("""[^\p{L}\p{N}\-]+"""), " ")
        val parts = normalized.split(Regex("""[\s\-]+""")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        out.addAll(parts)
        for (i in 0 until parts.size - 1) {
            out.add(parts[i] + "-" + parts[i + 1])
        }
        if (parts.size >= 3) {
            out.add(parts.take(3).joinToString("-"))
            out.add(parts.takeLast(3).joinToString("-"))
        }
        return out.toList()
    }

    fun <T> pickBest(
        queryId: String,
        queryName: String,
        candidates: List<T>,
        tokensOf: (T) -> Set<String>,
        idOf: (T) -> String,
    ): T? {
        if (candidates.isEmpty()) return null
        val queryTokens = tokens("$queryId $queryName")
        if (queryTokens.isEmpty()) return null
        var best: T? = null
        var bestScore = Int.MIN_VALUE
        var bestExtra = Int.MAX_VALUE
        var bestLen = Int.MAX_VALUE
        for (c in candidates) {
            val ct = tokensOf(c)
            val matched = matchedTokens(queryTokens, ct)
            val score = matched.sumOf { it.length }
            if (!isAcceptable(matched, score)) continue
            val extra = extraAtomic(queryTokens, ct)
            val len = idOf(c).length
            val better = when {
                score > bestScore -> true
                score < bestScore -> false
                extra < bestExtra -> true
                extra > bestExtra -> false
                len < bestLen -> true
                else -> false
            }
            if (better) {
                best = c
                bestScore = score
                bestExtra = extra
                bestLen = len
            }
        }
        return best
    }

    internal fun matchedTokens(queryTokens: List<String>, candidateTokens: Set<String>): List<String> {
        val sorted = queryTokens.distinct().sortedByDescending { it.length }
        val matched = mutableListOf<String>()
        for (t in sorted) {
            if (t !in candidateTokens) continue
            if (matched.any { it.contains(t) }) continue
            matched.add(t)
        }
        return matched
    }

    internal fun isAcceptable(matched: List<String>, score: Int): Boolean {
        if (score < 5) return false
        val hasVersion = matched.any { tok -> tok.any { it.isDigit() } }
        val hasName = matched.any { '-' !in it && it.length >= 4 && it !in GENERIC }
        val hasCompound = matched.any { it.length >= 5 }
        return hasVersion || hasName || hasCompound
    }

    private fun extraAtomic(queryTokens: List<String>, candidateTokens: Set<String>): Int {
        val q = queryTokens.toSet()
        var extra = candidateTokens.count { t ->
            '-' !in t && t.length >= 3 && t !in q && t !in GENERIC
        }
        for (v in VARIANTS) {
            val qHas = v in q
            val cHas = v in candidateTokens
            if (qHas != cHas) extra += 5
        }
        return extra
    }
}
