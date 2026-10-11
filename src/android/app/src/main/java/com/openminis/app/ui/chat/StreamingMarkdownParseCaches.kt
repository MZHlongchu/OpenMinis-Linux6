package com.openminis.app.ui.chat

import androidx.compose.ui.text.AnnotatedString
import com.openminis.app.text.BoundedText
import java.util.LinkedHashMap

internal object MarkdownParseCaches {
    // [T-android-parse-lru-char-budget] (#759) Per-cache character budget,
    // replacing the previous fixed entry count of 768.
    //
    // The old cap-by-count rule blew up on Larky-class sessions: a 33KB
    // assistant message produces hundreds of cached entries (one per
    // paragraph / table / list item / math snippet), and 768 entries × an
    // average per-entry text length of 100KB+ of inline `AnnotatedString`
    // backing arrays cleared tens of megabytes of resident heap before
    // eviction kicked in. Cap on source characters instead so the cache
    // bytes scale with the source bytes, not with the entry count.
    //
    // Why 2 MB (= 2,000,000 chars) per cache:
    //   - For Larky's worst case (1.9 MB session, single message up to
    //     ~33 KB): the budget holds ~60 distinct 33 KB messages or
    //     ~1500 distinct 1 KB chat blocks — plenty for the tail-window
    //     scroll range (200 messages × ~50 paragraphs avg).
    //   - For typical sessions (1–2 KB messages): ~1000+ entries, ≥ the
    //     previous count-based cap; hit-rate effectively identical to
    //     the 768-entry cap.
    //   - Across the three caches that's 6 MB worst-case (chars only;
    //     AnnotatedString backing arrays are larger but scale with the
    //     same source). On a Pixel-class device with ~96 MB heap budget,
    //     6 MB is acceptable; was previously unbounded by chars on the
    //     33 KB tail of the distribution.
    private const val CHAR_BUDGET_PER_CACHE = 2_000_000

    /**
     * [T-android-incremental-cache-key] 增量拼接结果**自己一个缓存**，不与全量键共享。
     *
     * 旧实现（含 prewarmLiveTail 的显式写入）把「切点 + 拼接」得到的结果写进
     * `inlineLru[raw]` / `mathLru[raw]` —— 那正是 `inline()` / `mathLatex()` 的权威
     * 键。只要 [safeInlineSplitOffset] 与 parseInline 有一处判据不一致，错误结果就
     * 成了该文本的「全量解析」，冻结分支、复制、后续所有缓存命中的调用点都读到它，
     * 直到 LRU 淘汰（用户看到的就是样式定格在错误形态）。
     *
     * 现在权威键只由 [inline] / [mathLatex]（真全量解析）写；增量结果只进这两个
     * 缓存。预算刻意小：并存的活尾通常只有一条（流式最后一段），256K 字符足够
     * 覆盖「预热 + 主线程命中」的同一次 tick，且不会为历史片段长期占内存。
     */
    private const val CHAR_BUDGET_INCREMENTAL = 256_000

    /**
     * Access-order LinkedHashMap with eviction driven by a running
     * character total rather than entry count. [sizer] returns the number
     * of source characters each (key, value) pair contributes (we count
     * source-side bytes — the canonical input — rather than
     * AnnotatedString output, because output sizes are not easily
     * computable and source length is the dominant correlate anyway).
     *
     * `get` continues to refresh recency via LinkedHashMap's accessOrder;
     * `put` is overridden to maintain [totalChars] and trim trailing
     * eldest entries until the running total fits in [budget], while
     * always keeping at least one entry — see the put loop guard. This
     * preserves the "single oversized message lands in the cache without
     * an infinite eviction loop" invariant required by the spec.
     */
    private class Lru<K, V>(
        private val budget: Int,
        private val sizer: (K, V) -> Int,
    ) : LinkedHashMap<K, V>(64, 0.75f, true) {
        var totalChars: Int = 0
            private set

        override fun put(key: K, value: V): V? {
            val prior = super.put(key, value)
            // The map call above may have replaced an existing entry —
            // adjust the running total by the difference, not the new
            // value alone. Also defends against accidental double-count
            // when the same key is re-put with a different value.
            if (prior != null) totalChars -= sizer(key, prior).coerceAtLeast(0)
            totalChars += sizer(key, value).coerceAtLeast(0)
            // Trim eldest entries until under budget. Always keep at
            // least one entry alive so a single message larger than
            // budget still gets cached (its first parse is the
            // expensive one; we eat the over-budget transient until
            // any other put displaces it).
            while (totalChars > budget && size > 1) {
                val eldest = entries.iterator().next()
                totalChars -= sizer(eldest.key, eldest.value).coerceAtLeast(0)
                entries.remove(eldest)
            }
            return prior
        }

        override fun remove(key: K): V? {
            val v = super.remove(key) ?: return null
            totalChars -= sizer(key, v).coerceAtLeast(0)
            return v
        }

        override fun clear() {
            super.clear()
            totalChars = 0
        }

        // Suppress the count-based eviction path inherited from
        // LinkedHashMap; our own put() does the work and removeEldestEntry
        // would race with our totalChars bookkeeping if both fired.
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = false
    }

    private val inlineLru = Lru<Pair<String, MdColors>, AnnotatedString>(
        budget = CHAR_BUDGET_PER_CACHE,
        sizer = { k, _ -> k.first.length },
    )
    private val mathLru = Lru<String, List<String>>(
        budget = CHAR_BUDGET_PER_CACHE,
        sizer = { k, _ -> k.length },
    )
    private val blocksLru = Lru<String, List<MdBlock>>(
        budget = CHAR_BUDGET_PER_CACHE,
        sizer = { k, _ -> k.length },
    )

    /** [T-android-incremental-cache-key] 增量 inline / math 结果（见上）。 */
    private val incrInlineLru = Lru<Pair<String, MdColors>, AnnotatedString>(
        budget = CHAR_BUDGET_INCREMENTAL,
        sizer = { k, _ -> k.first.length },
    )
    private val incrMathLru = Lru<String, List<String>>(
        budget = CHAR_BUDGET_INCREMENTAL,
        sizer = { k, _ -> k.length },
    )

    // Double-checked get: the lock is held only for map access, never during a
    // parse — a slow parse on Default must not block a main-thread hit on a
    // DIFFERENT key. A concurrent miss on the same key computes twice and the
    // results are equal immutable values; harmless.
    fun inline(text: String, colors: MdColors): AnnotatedString {
        val key = text to colors
        synchronized(inlineLru) { inlineLru[key] }?.let { return it }
        val t0 = System.nanoTime()
        val computed = parseInline(text, colors)
        maybeLogSlowParse("inline", text.length, (System.nanoTime() - t0) / 1_000_000)
        synchronized(inlineLru) { inlineLru[key] = computed }
        return computed
    }

    fun mathLatex(text: String): List<String> {
        synchronized(mathLru) { mathLru[text] }?.let { return it }
        val t0 = System.nanoTime()
        val computed = collectInlineMathLatex(text)
        maybeLogSlowParse("math", text.length, (System.nanoTime() - t0) / 1_000_000)
        synchronized(mathLru) { mathLru[text] = computed }
        return computed
    }

    // ─── [T-android-streaming-incremental-inline] Live-tail incremental parse ──
    //
    // The streaming live fragment is (usually) a single growing paragraph. Its
    // `raw` changes every throttle tick, so `inline()` / `mathLatex()` MISS the
    // cache every tick and re-scan the WHOLE accumulated paragraph char-by-char
    // — the confirmed 81%-of-hang-stacks inline/math regex hotspot + Matcher
    // allocation GC storm (see minis-2026-07-06.log analysis).
    //
    // Incremental fix: find a SAFE closed boundary (a newline where every inline
    // construct — bold/italic/strike/code/math/link — is balanced), parse the
    // frozen prefix ONCE (it only advances in discrete jumps, so it's a cache
    // HIT across ticks), and re-parse only the short unclosed suffix each tick,
    // then splice. Correctness rests on `safeInlineSplitOffset` never splitting
    // inside an open construct, so parse(prefix) ++ parse(suffix) == parse(full).

    /** Below this length the whole-fragment parse is sub-ms; incremental
     *  splitting/splicing overhead isn't worth it. */
    private const val INCREMENTAL_MIN_CHARS = 1_500

    /** Incremental inline parse for the live streaming tail. Returns the exact
     *  same AnnotatedString as `inline(text, colors)` would, but reuses a cached
     *  parse of the closed prefix and only scans the unclosed suffix. Falls back
     *  to the plain cached path for short text or when no safe boundary exists. */
    fun inlineIncremental(text: String, colors: MdColors): AnnotatedString {
        // Exact-match cache hit (e.g. a re-publish of the same content) — free.
        // 全量键命中同样是正确值：它只可能由 inline() 的真全量解析写入。
        synchronized(inlineLru) { inlineLru[text to colors] }?.let { return it }
        // [T-android-incremental-cache-key] 增量结果自己的键：每个 tick 的 raw 都不同，
        // 没有这一层，主线程每个 tick 都要重跑切点扫描 + 尾段解析（prewarm 白做）。
        synchronized(incrInlineLru) { incrInlineLru[text to colors] }?.let { return it }
        if (text.length < INCREMENTAL_MIN_CHARS) return inline(text, colors)
        val split = safeInlineSplitOffset(text)
        if (split <= 0) return inline(text, colors)
        // Prefix goes through the normal cache: identical across ticks until the
        // boundary advances, so this is a HIT on all but the (rare) advance tick.
        val prefixAnn = inline(text.substring(0, split), colors)
        val t0 = System.nanoTime()
        val suffixAnn = parseInline(text.substring(split), colors)
        maybeLogSlowParse("inline-incr", text.length - split, (System.nanoTime() - t0) / 1_000_000)
        val spliced = androidx.compose.ui.text.buildAnnotatedString {
            append(prefixAnn)
            append(suffixAnn)
        }
        synchronized(incrInlineLru) { incrInlineLru[text to colors] = spliced }
        return spliced
    }

    /** Incremental math-latex collection for the live streaming tail. Same
     *  contract as `mathLatex(text)`; reuses the closed prefix's list. */
    fun mathLatexIncremental(text: String): List<String> {
        synchronized(mathLru) { mathLru[text] }?.let { return it }
        synchronized(incrMathLru) { incrMathLru[text] }?.let { return it }
        if (text.length < INCREMENTAL_MIN_CHARS) return mathLatex(text)
        val split = safeInlineSplitOffset(text)
        if (split <= 0) return mathLatex(text)
        val prefix = mathLatex(text.substring(0, split))
        val suffix = collectInlineMathLatex(text.substring(split))
        val merged = if (suffix.isEmpty()) prefix else prefix + suffix
        synchronized(incrMathLru) { incrMathLru[text] = merged }
        return merged
    }

    /**
     * [T-android-jank-diag-logging] Threshold-gated slow-parse visibility:
     * silent in normal operation, one INFO line when a SINGLE parse exceeds
     * [SLOW_PARSE_LOG_MS]. thread=main is exactly the signal a future jank
     * report needs — it means a parse escaped every off-main path.
     */
    private const val SLOW_PARSE_LOG_MS = 80L
    private fun maybeLogSlowParse(layer: String, chars: Int, ms: Long, extra: String = "") {
        if (ms < SLOW_PARSE_LOG_MS) return
        val thread = if (android.os.Looper.getMainLooper().isCurrentThread) {
            "main"
        } else {
            Thread.currentThread().name
        }
        com.openminis.app.logging.AppLogger.info(
            "JankDiag",
            "[JankDiag] slow markdown parse layer=$layer chars=$chars ms=$ms thread=$thread$extra",
        )
    }

    /** [T-android-coldload-offmain-parse] Lock-only cache peek — never
     *  computes. Lets the frozen render path keep cache HITs synchronous
     *  while routing big MISSes off-main. */
    fun cachedBlocks(raw: String): List<MdBlock>? =
        synchronized(blocksLru) { blocksLru[raw] }

    /**
     * [T-android-freeze-edge-offmain-inline] Lock-only probe: 该 `raw` 的**权威**
     * inline / math 结果是否都已入缓存（`inline()` / `mathLatex()` 会同步命中）？
     *
     * 冻结分支为什么需要它：`prewarmLiveTail` 只暖增量键（见
     * [T-android-incremental-cache-key]），流末那一帧 `RenderBlock` 一旦从
     * liveIncremental 切回权威路径，`inline(raw)` / `mathLatex(raw)` 必 MISS，
     * 于是**主线程**跑一次全量 parseInline —— 81% 卡顿栈所在的那一层。
     * 有了这个探针，冻结分支可以在权威键仍冷的那一帧改走增量键（零解析），
     * 同时把权威解析交给 off-main 预暖。
     *
     * `containsKey` 不会刷新 accessOrder 的最近使用序（只有 `get` 会），所以探针
     * 不会把别处的热条目挤出 LRU。
     */
    fun isAuthoritativeParseCached(raw: String, colors: MdColors): Boolean =
        synchronized(inlineLru) { inlineLru.containsKey(raw to colors) } &&
            synchronized(mathLru) { mathLru.containsKey(raw) }

    /** [T-android-review-p1-fixes] F2(a): freeze-edge handoff — the live
     *  branch deposits its parse result here so the frozen branch HITs
     *  synchronously when the segment freezes (stream end / live→frozen
     *  migration) instead of flashing the plain-text preview while an
     *  off-main re-parse runs. */
    fun putBlocks(raw: String, blocks: List<MdBlock>) {
        if (raw.length > BoundedText.MAX_MARKDOWN_PARSE_CHARS) return
        synchronized(blocksLru) { blocksLru[raw] = blocks }
    }

    /** Block-level parse for a FROZEN fragment. First parse may run on the
     *  caller's thread (once per distinct fragment text process-wide); scroll
     *  away/return and session re-entry are hits. */
    fun blocks(raw: String): List<MdBlock> {
        val cacheable = raw.length <= BoundedText.MAX_MARKDOWN_PARSE_CHARS
        if (cacheable) {
            synchronized(blocksLru) { blocksLru[raw] }?.let { return it }
        }
        val t0 = System.nanoTime()
        val computed = parseMarkdownBlocksBlocking(raw)
        maybeLogSlowParse(
            "blocks", raw.length, (System.nanoTime() - t0) / 1_000_000,
            extra = " blocks=${computed.size}",
        )
        if (cacheable) {
            synchronized(blocksLru) { blocksLru[raw] = computed }
        }
        return computed
    }

    /** Pre-compute everything RenderBlock will ask for, off-main. Walks the
     *  same texts the RenderBlock branches feed to inline()/mathLatex();
     *  anything missed simply computes lazily on first composition (once). */
    fun prewarm(blocks: List<MdBlock>, colors: MdColors) {
        for (b in blocks) {
            when (b) {
                is MdBlock.Paragraph -> { inline(b.raw, colors); mathLatex(b.raw) }
                is MdBlock.Heading -> { inline(b.text, colors); mathLatex(b.text) }
                is MdBlock.UnorderedList -> b.items.forEach {
                    inline(it.text, colors); mathLatex(it.text); prewarm(it.children, colors)
                }
                is MdBlock.OrderedList -> b.items.forEach {
                    inline(it.text, colors); mathLatex(it.text); prewarm(it.children, colors)
                }
                is MdBlock.TaskList -> b.items.forEach { inline(it.text, colors); mathLatex(it.text) }
                is MdBlock.Table -> {
                    b.headers.forEach { inline(it, colors); mathLatex(it) }
                    b.rows.forEach { row -> row.forEach { inline(it, colors); mathLatex(it) } }
                }
                is MdBlock.BlockQuote -> prewarm(b.innerBlocks, colors)
                else -> Unit // code blocks / media / HR / math-display don't inline-parse
            }
        }
    }

    /**
     * [T-android-streaming-incremental-inline] Off-main prewarm for the LIVE
     * tail block. Warms the closed-prefix inline/math cache so the main-thread
     * `inlineIncremental` / `mathLatexIncremental` (which RenderBlock calls for
     * the live block) resolve to a cache HIT instead of a scan + tail parse.
     * Only the LAST block is the live one; earlier blocks are frozen and handled
     * by the normal [prewarm] above. No-op for non-Paragraph tails (they don't
     * take the incremental path in RenderBlock).
     *
     * [T-android-incremental-cache-key] 这里**不再**把增量结果写进
     * `inlineLru[raw]` / `mathLru[raw]`：那是权威全量键，写进去等于让「切点选错的
     * 增量结果」冒充全量解析，冻结分支与后续所有 inline() 命中都会定格在错误形态
     * （本回归的成因之一）。增量结果进它自己的缓存，主线程照样是干净命中。
     */
    fun prewarmLiveTail(blocks: List<MdBlock>, colors: MdColors) {
        val last = blocks.lastOrNull() as? MdBlock.Paragraph ?: return
        // inlineIncremental/mathLatexIncremental internally split at the safe
        // boundary and reuse inline(prefix)/mathLatex(prefix), so this both warms
        // the prefix and memoizes the exact full-text result under the
        // incremental key.
        inlineIncremental(last.raw, colors)
        mathLatexIncremental(last.raw)
    }
}

