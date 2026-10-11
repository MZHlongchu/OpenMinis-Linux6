package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

@Composable
internal fun MarkdownBlockBody(
    rawText: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    // For frozen blocks, parse once per distinct fragment text PROCESS-WIDE
    // ([MarkdownParseCaches.blocks]) — scroll-away/return and session re-entry
    // are cache hits instead of fresh main-thread parses. remember() keeps the
    // per-composition lookup free.
    //
    // [T-android-coldload-offmain-parse] Cold-load split: a cache HIT (or a
    // small fragment) still renders synchronously — no flicker on
    // scroll-back / re-entry, and small parses are sub-ms. A cache MISS on a
    // BIG fragment must NOT parse in composition: on session open the
    // viewport's fragments all miss at once and the synchronous
    // parseMarkdownBlocksBlocking + inline scans froze the main thread for
    // seconds (tester log: 3.5–8.5s on a 33K-char message; the streaming
    // breaker/degrade only cover isStreaming=true). Those parse off-main
    // with a bounded plain-text preview in the meantime — same structure as
    // the live branch below.
    // [T-android-freeze-edge-carryover] The live branch's most recent parse
    // and the text it was for, held ABOVE the live/frozen switch. The list key
    // (mdblock:<msg>:<block>:<index>) does not include isStreaming, so this
    // composable — and this remember — survive the flip.
    //
    // Why the cache deposit alone was not enough: the VM flushes on newline,
    // so a real model routinely lands a table's last row and the blank line
    // after it in ONE publish. The fragment then goes from "4 rows, live"
    // straight to "5 rows, frozen" — its final text is never composed live,
    // the deposit is keyed to the 4-row text, and the frozen lookup misses.
    // The miss then paints the plain-text preview for as long as the
    // off-main parse takes (that is the flash) — so the frozen branch below
    // falls back to this carry, exact-match first, then prefix-match.
    // A plain holder, not snapshot state: written from the parse coroutine,
    // read in composition, must not invalidate anything by itself.
    // （上游 1.14 StreamingMarkdownText.kt:1096-1112 原文。）
    val liveCarry = remember { arrayOf<Pair<String, List<MdBlock>>?>(null) }
    if (!isStreaming) {
        // [T-android-freeze-edge-offmain-inline] 主题色快照提到分支最前：冻结边的
        // 「blocks 缓存命中」分支要用同一份 palette 做 off-main 权威预暖（键必须与
        // RenderBlock 稍后查的完全一致），下面的冷 MISS 分支继续复用它。
        val mdColors = currentMdColors()
        val cached = remember(rawText) {
            MarkdownParseCaches.cachedBlocks(rawText)
                // [T-android-freeze-edge-carryover] 沉积可能还在飞，carry 就是同一次解析。
                ?: liveCarry[0]?.takeIf { it.first == rawText }?.second
        }
        // [T-android-longtext-anr] Synchronous render ONLY on a real cache HIT.
        // The old `|| rawText.length <= COLD_PARSE_OFFMAIN_THRESHOLD_CHARS` clause
        // let a small fragment parse (block-split + per-block inline regex) on the
        // main thread during composition. Individually sub-ms, but on session open
        // the first screen holds ~20 messages split into dozens of small fragments;
        // when the parallel viewport prewarm (ChatScreen) loses the race, every one
        // of those misses parsed synchronously in the same frame and the aggregate
        // froze the main thread for 30s+ → ANR (minis-2026-07-09-anr.log: all hang
        // stacks in Matcher/Pattern via the inline parser, right after first compose).
        // A cold MISS now always goes off-main with a plain-text preview, bounding
        // the first-frame main-thread cost to cheap Text layouts regardless of how
        // many fragments miss at once. Cache HITs (scroll-back, re-entry, prewarmed
        // rows) stay synchronous and flicker-free.
        if (cached != null) {
            // [T-android-freeze-edge-offmain-inline] 冻结边的**主线程全量 inline
            // 解析**必须消失。命中来源是流式分支的 `putBlocks` 交接：那一帧的
            // `prewarmLiveTail` 只暖了增量键（[T-android-incremental-cache-key] 规定
            // 增量结果不得占权威键），所以这里 `RenderBlock` 走权威路径时
            // `inline(raw)` / `mathLatex(raw)` 必 MISS → 合成线程跑完整 parseInline
            // （历史上 81% 卡顿栈就在这层）。两步收口：
            //   1) off-main 预暖权威键 —— `prewarm` 对已暖的块只是锁内查表，真正冷的
            //      只有流末那个 Paragraph，它的一次全量解析留在 Default 线程；
            //   2) 权威键补好之前，流末 Paragraph 这一帧走增量入口 —— 流末 tick 刚
            //      算过同样的 (raw, colors)，命中即零解析；画出来的对象与上一帧像素
            //      级一致（parity 由 safeInlineSplitOffset 镜像 parseInline 保证），
            //      所以既没有主线程解析，也没有 F2(a) 要消灭的纯文本预览闪一下。
            //      这里的键必然命中：沉积 blocks 的那次 off-main tick 在**同一个
            //      `also` 块里**先 `prewarmLiveTail`（写增量键）再 `putBlocks`
            //      （见本文件流式分支），而被命中的文本恒是最后那次 tick 的文本，
            //      所以增量条目就是最新条目、不会被 256K 预算挤掉；万一真被挤掉，
            //      `inlineIncremental` 也只是回落成「切点 + 前缀/尾段」解析（仍 ≤ 原
            //      全量解析），不会比修前更差。
            // `authoritativeReady` 只在组合里读，用来在预暖完成后触发一次重组，
            // 让该块改读权威值（[T-android-incremental-cache-key] 的「增量结果不当
            // 权威结果」在此仍然成立：增量值只经由增量入口送到渲染层）。
            var authoritativeReady by remember(rawText) { mutableStateOf(false) }
            LaunchedEffect(rawText) {
                withContext(Dispatchers.Default) {
                    MarkdownParseCaches.prewarm(cached, mdColors)
                }
                coroutineContext.ensureActive()
                authoritativeReady = true
            }
            Column(modifier = modifier) {
                val lastIdx = cached.size - 1
                cached.forEachIndexed { idx, block ->
                    val freezeEdgeHandoff = !authoritativeReady &&
                        idx == lastIdx &&
                        block is MdBlock.Paragraph &&
                        !MarkdownParseCaches.isAuthoritativeParseCached(block.raw, mdColors)
                    if (freezeEdgeHandoff) {
                        androidx.compose.runtime.CompositionLocalProvider(
                            LocalLiveIncremental provides true,
                        ) { RenderBlock(block) }
                    } else {
                        RenderBlock(block)
                    }
                }
            }
            return
        }
        var parsed by remember(rawText) { mutableStateOf<List<MdBlock>?>(null) }
        LaunchedEffect(rawText) {
            val tStartNs = System.nanoTime()
            val computed = withContext(Dispatchers.Default) {
                MarkdownParseCaches.blocks(rawText).also {
                    MarkdownParseCaches.prewarm(it, mdColors)
                }
            }
            coroutineContext.ensureActive()
            parsed = computed
            com.openminis.app.logging.AppLogger.info(
                "Perf",
                "[Perf][ColdParse] step=coldParse.offmain chars=${rawText.length} " +
                    "blocks=${computed.size} parseMs=${(System.nanoTime() - tStartNs) / 1_000_000}",
            )
        }
        // [T-android-freeze-edge-carryover] While the parse of the FINAL text
        // runs, keep painting the last parse this fragment showed while live,
        // provided that text is a prefix of what we now hold (the same-flush
        // case: 4 rows -> 5 rows). It is a rendered table missing its last
        // row for one parse, instead of raw pipes. `blocks ?: carry` keeps
        // both in ONE branch so the swap reuses the RenderBlock slots.
        // （上游 1.14 StreamingMarkdownText.kt:1171-1180 原文。）
        val blocks = parsed ?: liveCarry[0]?.takeIf { rawText.startsWith(it.first) }?.second
        Column(modifier = modifier) {
            if (blocks == null) {
                // Bounded plain-text preview while the off-main parse runs —
                // one cheap Text layout, no markdown/regex/AnnotatedString.
                Text(
                    text = rawText.take(COLD_PARSE_PREVIEW_CHARS),
                    fontSize = BaseFontSize,
                    lineHeight = BaseLineHeight,
                    color = currentMdColors().text,
                )
            } else {
                blocks.forEach { RenderBlock(it) }
            }
        }
        return
    }
    // [T-android-live-block-degrade] B-lite: a LIVE fragment that has grown
    // huge is almost always an unsplittable single block (the splitter keeps
    // tables/fences whole — exactly the MiniMax giant-table ANR load). Parsing
    // it in full on every publish is O(fragment) with no upper bound, so over
    // the threshold render a bounded plain-text tail instead and do the full
    // parse ONCE when the fragment freezes (isStreaming flips false above).
    if (rawText.length > LIVE_FRAGMENT_DEGRADE_CHARS) {
        Column(modifier = modifier) {
            Text(
                text = stringResource(R.string.chat_stream_degraded_notice),
                style = MaterialTheme.typography.labelSmall,
                color = currentMdColors().blockquote,
            )
            Text(
                text = "…" + rawText.takeLast(LIVE_FRAGMENT_TAIL_CHARS),
                fontSize = BaseFontSize,
                lineHeight = BaseLineHeight,
                color = currentMdColors().text,
            )
        }
        return
    }
    // Live (streaming tail) block.
    //
    // [T-android-stream-flush-dualpath] Throttling moved UP to the message
    // accumulation layer (ChatViewModel.updateAssistantMessage) where the
    // dual-path (time OR newline+chars) flush actually accumulates across the
    // high-frequency token calls. The earlier per-fragment throttle here was
    // structurally broken: streaming text is split into many short-lived
    // fragment items, so this produceState (and its lastFlushMs accumulator)
    // reset on every fragment rebuild and never throttled at all — diagnostics
    // showed every tick flushing. `rawText` arriving here is already paced by
    // the VM, so the fragment just renders it directly; parse stays off-main
    // below.
    val displayContent by produceState(initialValue = rawText, rawText) {
        snapshotFlow { rawText }.conflate().collect { value = it }
    }
    // [T-android-inline-parse-offmain] Snapshot the theme colors in
    // composition so the Default-thread parse below can PREWARM the inline
    // caches with the exact keys RenderBlock will look up — main-thread
    // composition of the live block becomes a pure cache hit.
    val mdColors = currentMdColors()
    var blocks by remember { mutableStateOf<List<MdBlock>>(emptyList()) }
    // [T-android-freeze-edge-flash] The key of this fragment's most recent
    // live deposit, so each tick can REPLACE it rather than add beside it.
    // A plain holder, not snapshot state: it is written from the parse
    // coroutine and must not invalidate anything.
    val liveDepositKey = remember { arrayOf<String?>(null) }
    LaunchedEffect(displayContent) {
        // [T-android-stream-render-profile] Time the whole off-main tick
        // (block split + prewarm/incremental inline+math) — this is what the
        // incremental optimization shrinks.
        val parseStartNs = System.nanoTime()
        val computed = withContext(Dispatchers.Default) {
            parseMarkdownBlocks(displayContent).also {
                // [T-android-streaming-incremental-inline] Prewarm the frozen
                // blocks (all but the last) normally. The last block is the
                // growing live tail: when it's a Paragraph, warm it
                // incrementally (closed prefix reused + tiny fresh suffix) so
                // the main-thread RenderBlock resolves to an exact HIT without
                // re-scanning the whole accumulated paragraph; when it's a
                // table/list/etc. (which RenderBlock parses non-incrementally)
                // fall back to the normal per-block prewarm for it.
                if (it.size > 1) MarkdownParseCaches.prewarm(it.dropLast(1), mdColors)
                if (it.lastOrNull() is MdBlock.Paragraph) {
                    MarkdownParseCaches.prewarmLiveTail(it, mdColors)
                } else {
                    it.lastOrNull()?.let { last -> MarkdownParseCaches.prewarm(listOf(last), mdColors) }
                }
                // [T-android-review-p1-fixes] F2(a): deposit the live parse
                // into the blocks cache so the freeze edge (isStreaming →
                // false recomposes into the frozen branch with this exact
                // text) HITs synchronously — no plain-text preview flash, no
                // off-main re-parse.
                //
                // [T-android-freeze-edge-flash] This used to be gated on
                // `length > COLD_PARSE_OFFMAIN_THRESHOLD_CHARS`, on the
                // reasoning that a small fragment would parse sub-ms
                // synchronously at freeze anyway. That was true once. The ANR
                // fix (T-android-longtext-anr) then made EVERY cold miss go
                // off-main behind a plain-text preview, and nobody revisited
                // this gate — so a fragment under 2000 chars was guaranteed to
                // miss at freeze and flash its raw markdown. A 462-char
                // weather table did exactly that: rendered, went to
                // `| 城市 | ... |---|` pipes for one parse, came back.
                //
                // Every tick deposits now, but each tick REMOVES the previous
                // tick's entry first: one live entry per fragment, so the
                // stream leaves no trail of intermediate texts in the LRU.
                // Both ops are O(1) and the sizer is key length, so a
                // replacement is budget-neutral to within one tick's growth.
                // （上游 1.14 StreamingMarkdownText.kt:1281-1307 原文照搬。）
                liveDepositKey[0]?.let { prev ->
                    if (prev != displayContent) MarkdownParseCaches.removeBlocks(prev)
                }
                MarkdownParseCaches.putBlocks(displayContent, it)
                liveDepositKey[0] = displayContent
            }
        }
        coroutineContext.ensureActive()
        StreamRenderProfiler.recordParse(displayContent.length, (System.nanoTime() - parseStartNs) / 1_000_000.0)
        blocks = computed
        // [T-android-freeze-edge-carryover] Record what this fragment last
        // rendered while live, for the freeze edge above to paint.
        // （上游 1.14 StreamingMarkdownText.kt:1312-1315 原文。）
        liveCarry[0] = displayContent to computed
    }
    // [T-android-stream-grow-anim] No height/scroll animation here. We tried
    // animateContentSize to ease the bottom-pinned item's exposed height into a
    // smooth viewport follow, but diagnostics showed the live fragment's
    // composable identity is NOT stable across parse ticks (markdown re-blocks
    // every tick — blocks.size flips 1↔2, last-block position churns), so the
    // animation reset to initialH=0 almost every tick and "popped from zero"
    // instead of gliding, AND dragged single-frame cost to ~750–950ms (Davey).
    // Net regression. The smooth feel comes from reverseLayout's native bottom
    // pin (no jump, no extra layout cost) plus the per-word fade. A genuine
    // iOS-style continuous flow would require token-incremental rendering of the
    // streaming tail, not a height animation on an unstable item.
    Column(modifier = modifier) {
        // [T-android-streaming-incremental-inline] Flag the LIVE tail so its
        // Paragraph inline/math parse goes through the incremental
        // (frozen-prefix + fresh-suffix) path — this is the only block whose
        // raw grows every tick — and so MdText fades in its newly-appended
        // words.
        // [T-android-stream-fade-seed] Same shape for every block — see the
        // matching note in StreamingMarkdownTextBody. This is the live
        // fragment, so there is no frozen path to protect; the provider is
        // always present and only its VALUE tracks the tail. Flipping a
        // value recomposes consumers; changing the group shape disposed them.
        // （上游 1.14 StreamingMarkdownText.kt:1328-1347 的结构。）
        val lastIdx = blocks.size - 1
        blocks.forEachIndexed { idx, block ->
            val isLast = idx == lastIdx
            androidx.compose.runtime.CompositionLocalProvider(
                LocalAppendOnlyFade provides isLast,
                LocalLiveIncremental provides isLast,
            ) { RenderBlock(block) }
        }
    }
}

