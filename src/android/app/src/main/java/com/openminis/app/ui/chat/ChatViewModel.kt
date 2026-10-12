package com.openminis.app.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.LazyListState
import com.openminis.app.harness.agent.Level
import com.openminis.app.harness.agent.ToolLoopDetector
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.db.AppDatabase
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Extension
import com.openminis.app.data.BPETokenizer
import com.openminis.app.ui.chat.retention.HotWindow
import com.openminis.app.ui.chat.retention.ResidentWindow
import com.openminis.app.data.ContextPolicy
// [T-ctx-warmup-fit] warmUpDrop 这一处按上游原文写裸名（盘面守卫按
// `val drop = ContextSizeMeter.warmUpDrop(` 这条字面量找它，见
// CompactionDecisionTest）；文件里其余位置仍用全限定名。
import com.openminis.app.data.ContextSizeMeter
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.model.isPureVideoGenerator
import com.openminis.app.data.CapabilityRouter
import com.openminis.app.data.ModelCapability
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.R
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.MultiAgentSettings
import com.openminis.app.data.repository.MultiAgentSettingsRepository
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.tools.GroupChat
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.catalogMaxThinkingLevel
import com.openminis.app.provider.effectiveMaxThinkingLevel
import com.openminis.app.agent.shell.BashismDetector
import com.openminis.app.agent.shell.BashismReminder
import com.openminis.app.agent.shell.OnDemandBash
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.terminal.MinisOpenUrlBroker
import com.openminis.app.terminal.MinisUrlMarker
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.CodeGraphTool
import com.openminis.app.tools.SubAgentKind
import com.openminis.app.tools.SubAgentLane
import com.openminis.app.tools.FileEditTool
import com.openminis.app.tools.FileReadTool
import com.openminis.app.tools.FileWriteTool
import com.openminis.app.tools.MemoryTools
import com.openminis.app.tools.CronJobTool
import com.openminis.app.tools.ReadImageTool
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.SubAgentRunner
import com.openminis.app.tools.PlanDiscussionOrchestrator
import com.openminis.app.data.PlanDiscussionPrefs
import com.openminis.app.data.PlanDiscussionTrigger
import com.openminis.app.MinisApp
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.service.ApprovalGate
import com.openminis.app.security.SecurityGateHolder
import com.openminis.app.notification.ApprovalNotifier
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import com.openminis.app.util.IsoTime

// [T-android-split-chat] StreamingDelta / ChatMessage / QueuedPrompt /
// ToolBlockStatus / SlashCommand / AssistantBlock moved verbatim to ChatModels.kt.

class ChatViewModel(
    internal val sessionId: String,
    internal val chatRepository: ChatRepository,
    internal val providerRepository: ProviderRepository,
    internal val context: Context,
    val memoryRepository: MemoryRepository? = null,
    val skillRepository: com.openminis.app.data.repository.SkillRepository? = null,
    val mcpRepository: com.openminis.app.data.repository.MCPRepository? = null,
) : ViewModel(), com.openminis.app.session.ChatSessionPort {

    /** Whether this is a draft session (not yet persisted to DB). */
    internal val isDraft: Boolean = sessionId.startsWith("__new__")

    /** The real session ID, populated on first message for drafts. */
    internal var realSessionId: String = if (isDraft) "" else sessionId

    /**
     * [T-android-tts-scene-announcements] Speech sink for scene announcements,
     * injected by the UI layer (which owns the TTS engine binding). The agent
     * loop hands phrases to it at lifecycle boundaries; null = no engine
     * bound yet, announcements degrade to silence. Never crashes the loop.
     */
    @Volatile
    var announcementSink: ((String) -> Unit)? = null

    /** [T-prompt-cache] Memoized session-scoped MemoryRepository;
     *  re-created only when the resolved session id changes (draft→real).
     *  Avoids per-turn allocation and allows fragment caches inside
     *  MemoryRepository to survive across consecutive prompt builds. */
    @Volatile
    private var sessionMemoryRepoCache: MemoryRepository? = null
    @Volatile
    private var sessionMemoryRepoCacheSid: String? = null

    /** Daily logs and session GLOBAL.md live in this chat's workspace. */
    internal fun sessionMemoryRepo(): MemoryRepository {
        val sid = com.openminis.app.sandbox.ExecutionCoordinator.ownerSessionId(
            realSessionId.ifEmpty { sessionId },
        )
        val cached = sessionMemoryRepoCache
        if (cached != null && sessionMemoryRepoCacheSid == sid) return cached
        val repo = MemoryRepository(
            com.openminis.app.sandbox.SessionWorkspace.memoryDir(context.filesDir, sid),
        )
        sessionMemoryRepoCache = repo
        sessionMemoryRepoCacheSid = sid
        return repo
    }

    companion object {
        internal const val TAG = "ChatViewModel"

        /**
         * [T-fallback-respects-user-switch] 上游 1.14 `ChatViewModel.kt:114-122`
         * （合并时整段丢了，这里按原样重落）：一轮进行中发生的组内回退，能不能把
         * 它新换上的成员**写回会话绑定**——只有当类级 provider 还是这一轮出发时
         * 的那个才行。比的是对象 Identity，不是模型 id：用户重新挑一次会构造出
         * 新的 provider 实例。不相等，就说明这一轮跑着的时候用户自己换了模型，
         * 那个选择优先，回退只准停在这一轮本地。
         */
        internal fun fallbackMayRebindSession(classProviderNow: Any?, classProviderAtSend: Any?): Boolean =
            classProviderNow === classProviderAtSend

        /**
         * [T-android-switch-model-next-request] 上游 1.14
         * `ChatViewModel.kt:124-132`：循环现在就该采纳用户的挑法吗——有挂起的
         * 切换，并且挑选器已经把另一个 provider 装好了。挑选器还没重绑时保持
         * 挂起，好让夹在标志与重绑之间的循环头不会把这次切换弄丢。
         */
        internal fun modelSwitchToApply(pending: Boolean, chosen: Any?, loopProvider: Any?): Boolean =
            pending && chosen != null && chosen !== loopProvider

        /**
         * [T-android-offload-warmup-scan] 上游 1.14
         * `ChatViewModel.kt:321-357`，合并时丢了：`offloadWarmUpScanPlan` 背后的
         * 下标映射，纯函数以便测试——在 [priorIdx]..[anchorIdx] 这段历史里，
         * `effectiveAgentHistoryUncounted` 真正会发出去的那些**原始下标**
         * （超过 1000 字符的 tool_result 连同它们的调用一起剪掉，纯文本消息保留，
         * 被剪空的消息丢弃，开头的非 user 剥掉，最后在按 marker 决定过 drop 时
         * 再去掉最前面的 `drop` 条），以及被剪掉的 id 集合。
         */
        internal fun warmUpScanIndices(
            history: List<LLMMessage>,
            priorIdx: Int,
            anchorIdx: Int,
            drop: Int?,
        ): Pair<List<Int>, Set<String>> {
            if (priorIdx < 0 || priorIdx > anchorIdx || anchorIdx >= history.size) return emptyList<Int>() to emptySet()
            val pruned = HashSet<String>()
            for (i in priorIdx..anchorIdx) {
                for (part in history[i].contentParts) {
                    if (part is AgentContentPart.ToolResult && part.content.length > 1000) pruned.add(part.id)
                }
            }
            val kept = ArrayList<Int>()
            for (i in priorIdx..anchorIdx) {
                val parts = history[i].contentParts
                val survives = parts.isEmpty() || parts.any { part ->
                    when (part) {
                        is AgentContentPart.ToolUse -> part.id !in pruned
                        is AgentContentPart.ToolResult -> part.id !in pruned
                        else -> true
                    }
                }
                if (survives) kept.add(i)
            }
            while (kept.isNotEmpty() && history[kept.first()].role != LLMMessage.Role.USER) kept.removeAt(0)
            val trimmed = drop?.let { kept.drop(minOf(it, kept.size)) } ?: kept
            return trimmed to pruned
        }

        /**
         * [T-android-persist-row-refresh-merge] [notePersistedUiRow] 里 DB 侧对账的
         * 合并窗口：够长以吃下「assistant 行 + 一串 tool_result 行」的一次落库风暴，
         * 又远短于用户能察觉的列表/边缘状态变化。
         */
        private const val PERSISTED_ROW_FLUSH_DELAY_MS = 200L

        // ── [T-android-compact-runaway] Compaction budgets ──────────────
        //
        // Compaction had no ceiling of any kind. Its only time bound was the
        // provider's OkHttp readTimeout (10 minutes on every provider), and
        // the split-retry path could issue up to 1+2+4+8 = 15 SEQUENTIAL leaf
        // calls before depth 3 stopped it. Slow-but-not-timing-out calls (a
        // rate-limited or queued model at ~80s each) therefore added up to
        // roughly 20 minutes of apparent hang — which matches the report.
        //
        // Three independent ceilings now bound it, because each catches a case
        // the others miss: the call budget stops fan-out, the wall-clock
        // timeout stops slow-but-few calls, and the existing depth cap stops
        // recursion.

        /**
         * Leaf LLM calls one compaction may issue in total, across every
         * segment. The depth-3 cap alone permits 15; this cuts the worst case
         * to a third of that while still allowing a full first split (1+2) plus
         * one deeper rescue.
         */
        internal const val MAX_COMPACT_LLM_CALLS = 6

        internal const val COMPACT_SEGMENT_MIN_TOKENS = 8_000
        internal const val COMPACT_SEGMENT_MAX_TOKENS = 32_000

        internal fun estimateCompactTokens(text: String): Int =
            (text.length / 4).coerceAtLeast(1)

        internal fun compactSegmentTokenBudget(contextWindow: Int): Int =
            (contextWindow / 5).coerceIn(COMPACT_SEGMENT_MIN_TOKENS, COMPACT_SEGMENT_MAX_TOKENS)

        internal fun shouldProactivelySplit(
            messageCount: Int,
            estimatedTokens: Int,
            tokenBudget: Int,
            depth: Int,
            callsAlreadySpent: Int,
        ): Boolean =
            messageCount >= 2 &&
                depth < 3 &&
                estimatedTokens > tokenBudget &&
                callsAlreadySpent + 2 <= MAX_COMPACT_LLM_CALLS

        internal fun isFirstByteTimeout(error: Throwable): Boolean {
            val detail = when (error) {
                is LLMError.TransientError -> error.detail
                else -> error.message
            }?.lowercase() ?: return false
            return detail.contains("no response") ||
                detail.contains("ttfb") ||
                detail.contains("first byte")
        }

        /** Two shots only: session, then fallback or one session retry. */
        internal const val COMPACT_STAGE_BUDGET = 2

        internal fun compactStageKinds(hasFallbackDistinctFromSession: Boolean): List<String> =
            if (hasFallbackDistinctFromSession) listOf("session", "fallback")
            else listOf("session", "session-retry")

        /** [T-compact-summary-quality] Floor for an acceptable summary. */
        internal val MIN_ACCEPTABLE_SUMMARY_CHARS = 120

        /**
         * [T-compact-summary-quality] A summary is accepted when it is long
         * enough to carry state AND still echoes something from the user's
         * latest message — the concrete signal that the model summarized the
         * conversation instead of answering it or drifting. Pure function so
         * the policy is testable without an Android ViewModel.
         */
        internal fun isCompactSummaryAcceptable(summary: String, messages: List<LLMMessage>): Boolean {
            val trimmed = summary.trim()
            if (trimmed.length < MIN_ACCEPTABLE_SUMMARY_CHARS) return false
            val lastUser = messages.lastOrNull { it.role == LLMMessage.Role.USER } ?: return true
            val probe = lastUser.content.trim()
            if (probe.length < 4) return true
            val lower = trimmed.lowercase()
            // [T-compact-summary-quality] Reuse the chunk tokenizer so the
            // check looks for the user's CONTENT terms, not arbitrary
            // character windows: a 4-gram test is fooled by filler that any
            // summary shares ("trailing context", "the length floor"), while
            // real terms ("notification", "pipeline", "压缩", "机制") are
            // exactly what a faithful summary must echo. Short ASCII words
            // are dropped - "the"/"and" match everything.
            val terms = summaryQueryTerms(probe).filter { t ->
                t.length >= 4 || (t.first().code in 0x4E00..0x9FFF)
            }
            if (terms.isEmpty()) return true
            return terms.any { t -> t in lower }
        }

        /** [T-compact-chunk-pool] Newest chunks kept in the rolling pool. */
        internal val SUMMARY_CHUNK_POOL_MAX = 8

        /** Per-chunk cap; a runaway summary must not fill the pool. */
        internal const val SUMMARY_CHUNK_MAX_CHARS = 4_000

        /** Keep both the opening context and the operationally important tail. */
        internal fun preserveSummaryEdges(text: String, maxChars: Int): String {
            if (text.length <= maxChars) return text
            if (maxChars <= 1) return text.take(maxChars.coerceAtLeast(0))
            val tailSize = maxChars / 3
            val headSize = maxChars - tailSize - 1
            return text.take(headSize) + "…" + text.takeLast(tailSize)
        }

        /** Chunks injected per turn after retrieval. */
        internal const val SUMMARY_CHUNK_TOP_K = 3

        /**
         * [T-compact-chunk-pool] Parse the marker column into chunk texts.
         * Returns an empty list for legacy/garbage rows — every caller must
         * treat that as "no pool" and fall back to `summary`.
         */
        internal fun parseSummaryChunks(json: String?): List<String> =
            parseSummaryChunkRecords(json).map { it.first }

        /** (text, epochMs) pairs, oldest first. */
        private fun parseSummaryChunkRecords(json: String?): List<Pair<String, Long>> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = org.json.JSONArray(json)
                val out = ArrayList<Pair<String, Long>>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val t = o.optString("t").takeIf { it.isNotBlank() } ?: continue
                    out.add(t to o.optLong("a", 0L))
                }
                out
            }.getOrDefault(emptyList())
        }

        /**
         * [T-compact-chunk-pool] Rolling pool update on a successful compact:
         * keep the newest [SUMMARY_CHUNK_POOL_MAX] chunks (each capped), each
         * stored as a per-compaction snapshot so earlier material can still be
         * retrieved by keyword later. Pure function, testable.
         */
        internal fun appendSummaryChunk(existingJson: String?, newSummary: String): String {
            val capped = preserveSummaryEdges(newSummary, SUMMARY_CHUNK_MAX_CHARS)
            val kept = parseSummaryChunkRecords(existingJson)
                .filter { it.first.isNotBlank() }
                .toMutableList()
            kept.add(capped to System.currentTimeMillis())
            val trimmed = if (kept.size > SUMMARY_CHUNK_POOL_MAX) {
                kept.subList(kept.size - SUMMARY_CHUNK_POOL_MAX, kept.size).toList()
            } else {
                kept
            }
            return runCatching {
                val arr = org.json.JSONArray()
                for ((t, a) in trimmed) {
                    arr.put(org.json.JSONObject().put("t", t).put("a", a))
                }
                arr.toString()
            }.getOrElse { "[]" }
        }

        /**
         * [T-compact-chunk-pool] Tokenize a query into comparable units:
         * ASCII word runs plus CJK bigrams (a Chinese sentence has no spaces,
         * and a single-gram match is far too noisy). Pure function.
         */
        internal fun summaryQueryTerms(query: String): Set<String> {
            val terms = LinkedHashSet<String>()
            val lower = query.lowercase()
            val ascii = StringBuilder()
            fun flushAscii() {
                if (ascii.length >= 2) terms.add(ascii.toString())
                ascii.setLength(0)
            }
            var cjkRun = StringBuilder()
            fun flushCjk() {
                val run = cjkRun.toString()
                if (run.length >= 2) {
                    for (i in 0..run.length - 2) terms.add(run.substring(i, i + 2))
                } else if (run.length == 1) {
                    terms.add(run)
                }
                cjkRun.setLength(0)
            }
            for (c in lower) {
                when {
                    c.code < 128 && (c.isLetterOrDigit()) -> ascii.append(c)
                    c.code in 0x4E00..0x9FFF -> {
                        flushAscii()
                        cjkRun.append(c)
                    }
                    else -> {
                        flushAscii()
                        flushCjk()
                    }
                }
            }
            flushAscii()
            flushCjk()
            return terms
        }

        /**
         * [T-compact-chunk-pool] Retrieve the chunks that match the current
         * instruction, newest-first relevance, then re-sorted chronologically
         * for injection. Falls back to the most recent chunk when the query
         * shares no terms with any of them (the tail is what a conversation
         * most plausibly continues from). Pure function, testable.
         */
        internal fun selectSummaryChunks(chunksJson: String?, query: String, topK: Int = SUMMARY_CHUNK_TOP_K): List<String> {
            val chunks = parseSummaryChunkRecords(chunksJson)
            if (chunks.isEmpty()) return emptyList()
            if (chunks.size == 1) return listOf(chunks[0].first)
            val terms = summaryQueryTerms(query)
            if (terms.isEmpty()) return listOf(chunks.last().first)
            val scored = chunks.mapIndexed { idx, (text, at) ->
                val lower = text.lowercase()
                var score = 0
                for (t in terms) {
                    if (t in lower) score += if (t.length >= 2) 2 else 1
                }
                Triple(idx, score, text)
            }
            val hits = scored.filter { it.second > 0 }
                .sortedByDescending { it.second }
                .take(topK)
            val chosen = if (hits.isNotEmpty()) {
                hits
            } else {
                listOf(Triple(chunks.lastIndex, 0, chunks.last().first))
            }
            return chosen.sortedBy { it.first }.map { it.third }
        }

        /**
         * [T-compact-reduced-retry] Newest slice of a failed compaction range
         * for the last-resort reduced retry. `null` for small ranges — a
         * 5-message "transcript" does not need slicing, it just needs the
         * truncation fallback.
         */
        internal fun reducedCompactRetryInput(messages: List<LLMMessage>): List<LLMMessage>? {
            if (messages.size < 12) return null
            return messages.takeLast((messages.size * 3 / 5).coerceAtLeast(8))
        }

        /**
         * [T-compact-detached-anchor] Pure form of the detached assembly:
         * summary text + verbatim tail, first message forced to `user` with
         * the summary inlined as a content prefix. Split out of the ViewModel
         * so the fallback path is covered by tests without an Android host.
         */
        internal fun buildDetachedCompactHistory(
            summaryWrappedText: String,
            history: List<LLMMessage>,
            tailSize: Int,
        ): List<LLMMessage> =
            com.openminis.app.harness.agent.CompactHistoryProjector.detachedCompactHistory(
                history = history,
                summaryWrappedText = summaryWrappedText,
                tailSize = tailSize,
            )

        /** Floor for the dynamic wall-clock timeout. */
        internal const val COMPACT_TIMEOUT_BASE_MS = 90_000L

        /**
         * Added per 10k characters of transcript, so a long first compaction is
         * not cut off by a limit tuned for a short one.
         */
        internal const val COMPACT_TIMEOUT_PER_10K_CHARS_MS = 30_000L

        /**
         * Hard ceiling. Deliberately under the providers' 10-minute
         * readTimeout: past this point the run is aborted by us — with the lock
         * released and a clear message — rather than sitting on a socket that
         * may never answer.
         */
        internal const val COMPACT_TIMEOUT_MAX_MS = 300_000L

        /**
         * Wall-clock budget for compacting a transcript of [transcriptChars].
         * Grows with input so long histories get room, capped so nothing can
         * hang indefinitely.
         */
        internal fun compactTimeoutMsFor(transcriptChars: Int): Long {
            val growth = (transcriptChars / 10_000L) * COMPACT_TIMEOUT_PER_10K_CHARS_MS
            return (COMPACT_TIMEOUT_BASE_MS + growth).coerceAtMost(COMPACT_TIMEOUT_MAX_MS)
        }

        /**
         * [OpenMinis#377] 这个 provider 错误说的是「我不接受这个请求参数」，还是
         * 「这份载荷太大」？
         *
         * 只会被用来 **阻止** 重试，所以刻意保守：任何一点「超长」的味道都让它
         * 放弃判断（继续拆分），认不出来的措辞也照旧拆分。
         *
         * [T-merge-1.14-377] 上游 1.14 的实现（upstream-1.14
         * `ui/chat/ChatViewModel.kt:245-268`）在合并时整个丢了，本移植只剩
         * `[429]` / [isPermanentCapacityBody] 两条判据。于是
         * `reasoning.effort:"none" is not supported` 这类「端点不接受这个参数」的
         * 400 又回到「减半重试」，一条没变的参数把 963→481→240→120 条消息连试四次、
         * 烧完整轮预算才报错（OpenMinis#377 的原始报告）。
         *
         * 这里把上游判据原样搬回来，并与本移植已有的两条并列成 **并集**：任何一条
         * 命中都表示「变小也救不了」，不再拆分。
         */
        internal fun looksLikeParameterRejection(error: LLMError.ProviderError): Boolean {
            val msg = error.message?.lowercase() ?: return false
            // Any whiff of a size problem → not ours, keep splitting.
            val sizeMarkers = listOf(
                "context_length", "context length", "too long", "too large",
                "maximum context", "max_tokens", "token limit", "exceeds",
                "reduce the length", "payload",
            )
            if (sizeMarkers.any { msg.contains(it) }) return false
            // A named request parameter the endpoint will not accept. The reported
            // case: "reasoning.effort: 'none' is not supported".
            val paramMarkers = listOf(
                "is not supported", "unsupported parameter", "unsupported value",
                "invalid_request_error", "invalid parameter", "unrecognized request argument",
                "does not support parameter", "extra_forbidden",
            )
            return paramMarkers.any { msg.contains(it) }
        }

        /**
         * Should a failed summary attempt be retried by splitting the input in
         * half? Pure predicate, in the companion so it is testable without an
         * Android-bound ViewModel; [isSegmentRetryableError] delegates here.
         *
         * Splitting only helps when the failure was caused by the SIZE of the
         * request. Unclassified errors still split — an over-length refusal
         * arrives as an untyped ProviderError on most providers, and a summary
         * built from halves beats no summary — but the classes known to be
         * size-independent are excluded, because for those a split turns one
         * failure into up to 15 sequential slow calls. That amplification is
         * what produced the 15-20 minute apparent hang.
         */
        internal fun shouldSplitOnError(error: Throwable): Boolean {
            if (error is CancellationException) return false
            // [T-compact-idle-timeout] 上游 1.14 ChatViewModel.kt:10756-10760，合并时丢了：
            // 一条静下来的流证明不了任何关于载荷体积的事——请求早被接受并且一直在流，
            // 直到连接死掉。切半只会往同一根断管里再发两次、把调用预算浪费两遍。
            if (error is CompactIdleTimeoutException) return false
            if (error is LLMError) {
                return when (error) {
                    // Never worth a smaller payload:
                    //  - Cancelled: the user stopped it; retrying fights that.
                    //  - NetworkError: never reached a model, size is irrelevant.
                    //  - RateLimited (429): refusing on quota, not length —
                    //    halving just doubles the rejected calls under backoff.
                    //  - TransientError: generic 5xx stays unsplit. First-byte
                    //    watchdog ("no response" / TTFB) may split.
                    //  - InvalidApiKey: auth, not size.
                    is LLMError.Cancelled,
                    is LLMError.NetworkError,
                    is LLMError.RateLimited,
                    is LLMError.InvalidApiKey,
                    -> false
                    is LLMError.TransientError -> isFirstByteTimeout(error)
                    is LLMError.ProviderError ->
                        !error.detail.contains("[429]") &&
                            !com.openminis.app.harness.agent.HttpRetryAfter.isPermanentCapacityBody(error.detail) &&
                            // [T-merge-1.14-377] 请求形状被拒 ≠ 载荷太大：再小也还是
                            // 那个不被接受的参数（#377）。与本移植的 [429]/容量体判据
                            // 是并集，任一条命中即不拆。
                            !looksLikeParameterRejection(error)
                    // DecodingError / Unknown stay retryable: an over-length
                    // refusal arrives untyped, and that is the case splitting exists for.
                    else -> true
                }
            }
            // Raw OkHttp/socket failures are the Android equivalent of iOS's
            // NSURLErrorDomain bail-out: offline / DNS / TLS / timeout, all
            // payload-size independent.
            if (error is java.io.IOException) return false
            return true
        }

        /**
         * [T-android-append-to-input-eats-draft] Join the composer's current
         * [draft] with an appended [snippet]. Returns null when there is
         * nothing to append (the caller then leaves the draft untouched).
         *
         * Trims the incoming SNIPPET only. The old code called
         * `draft.trimEnd()` and assigned that trimmed copy back, so "Add to
         * input" silently rewrote the user's existing draft: a deliberate
         * trailing newline — a paragraph break they had just typed — was
         * swallowed and replaced by the separator space. The draft is the
         * user's own text and must come back byte-for-byte.
         *
         * The emptiness test still runs on a trimmed VIEW of the draft (a
         * whitespace-only draft counts as empty, rather than producing a
         * leading blank run), but that trimmed value drives the DECISION
         * only — it is never assigned back. Mirrors iOS `e6c0ace6a`.
         *
         * [T-android-append-to-input-fence] A fenced block is the one snippet
         * whose own shape decides the separator, on both ends. It must START
         * on a line of its own: a separator space would leave the opening
         * fence mid-line, where Markdown renders it as inline text instead of
         * a block. And it must be FOLLOWED by a newline, so the next keystroke
         * lands on a fresh line instead of on the closing fence. Mirrors iOS
         * `44b465e11`.
         *
         * Restored from upstream 1.14 (`upstream-1.14:src/android/app/
         * src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:388-403`):
         * the merge kept this file's older fence-blind body and dropped it,
         * which `AppendToInputTest` pins.
         *
         * Pure and side-effect free so it can be unit-tested without an
         * Android runtime; see `AppendToInputTest`.
         */
        internal fun joinDraftWithSnippet(draft: String, snippet: String): String? {
            val cleaned = snippet.trim()
            if (cleaned.isEmpty()) return null
            val startsWithFence = cleaned.startsWith("```") || cleaned.startsWith("~~~")
            val tail = if (cleaned.endsWith("```") || cleaned.endsWith("~~~")) "\n" else " "
            if (draft.isBlank()) return cleaned + tail
            // Preserve the draft verbatim; only add a separator when it does
            // not already end in whitespace. A trailing newline is already a
            // separator, and adding a space after it would indent the new line.
            val separator = if (startsWithFence) {
                if (draft.endsWith("\n")) "" else "\n"
            } else {
                if (draft.last().isWhitespace()) "" else " "
            }
            return draft + separator + cleaned + tail
        }

        /**
         * [T-android-auto-grouping-injection] Strip the characters that would let
         * user-authored text escape its slot in the prompt's group list, then
         * bound the length.
         *
         * The list is rendered as `"name" — desc; "name2" — desc2`, so a quote,
         * bracket or semicolon inside a value can terminate the list early and the
         * remainder reads as instruction. Newlines do the same at the line level.
         * Collapses whitespace so a name padded with tabs/newlines can't blow the
         * budget either.
         *
         * Deliberately NOT escaping instead of stripping: the sanitized name has to
         * survive a round trip (the model echoes it back and we match it against
         * the real folder name), and an escape sequence would come back escaped.
         * Stripping keeps the value matchable — findFolderByName's trim +
         * case-fold absorbs the difference for every realistic group name.
         */
        internal fun promptSafe(raw: String, max: Int): String =
            raw.replace(Regex("[\"'\\[\\]{};\\\\]"), " ")
                // Unicode quote lookalikes: a model reads curly and CJK
                // brackets as quoting just as readily as ASCII, so leaving
                // them in re-opens the break-out the ASCII strip closes.
                .replace(Regex("[\\u2018\\u2019\\u201C\\u201D\\u300C\\u300D\\u300E\\u300F]"), " ")
                // Format/bidi controls (RLO, LRO, ZWJ...) — invisible in code
                // review, and they can reorder how the rendered line reads.
                .replace(Regex("\\p{Cf}"), "")
                // WHITESPACE: Kotlin Regex is java.util.regex WITHOUT
                // UNICODE_CHARACTER_CLASS, so plain \\s is only
                // [ \\t\\n\\x0B\\f\\r] — U+2028 LINE SEPARATOR, U+2029
                // PARAGRAPH SEPARATOR and U+0085 NEL slip through as REAL line
                // breaks, which is exactly the multi-line break-out this
                // sanitizer exists to stop. \\p{Z} additionally covers NBSP
                // (U+00A0) and the ideographic space, neither of which
                // Kotlin's trim() removes either.
                .replace(Regex("[\\s\\p{Z}\\u0085\\u2028\\u2029]+"), " ")
                .trim()
                .take(max)
                // Trim AGAIN after the cut: take() can leave a trailing space,
                // and the round-trip matcher compares trimmed values.
                .trim()

        // [T-preflight-tool-title-nonblocking] Fields kept in each tool's
        // `required` list (so the schema keeps nudging the model to emit them —
        // tool_title drives the live pill header) but which must NOT block the
        // call when absent: they carry no execution semantics, so rejecting the
        // whole call over a missing one is pure downside. Preflight skips these
        // when checking for missing required fields. Mirrors iOS
        // AIChatViewModel.preflightNonBlockingFields.
        private val PREFLIGHT_NON_BLOCKING_FIELDS = setOf("tool_title")

        /**
         * (tool name → field names) where an EMPTY STRING is a semantically
         * valid value and must not be treated as "missing".
         *
         * Distinct from [PREFLIGHT_NON_BLOCKING_FIELDS], which skips the
         * missing-field check entirely: these fields must still be PRESENT in
         * args — they are just allowed to hold "" as their content.
         *
         * The canonical case is `file_edit.new_string`, whose schema documents
         * "Use empty string to delete old_string". Blocking it broke a promised
         * deletion workflow and pushed the model into shell_execute + python
         * file-rewrite workarounds. Mirrors iOS
         * AIChatViewModel.preflightEmptyStringAllowedFields.
         * [T-preflight-empty-string-allowed]
         */
        private val PREFLIGHT_EMPTY_STRING_ALLOWED_FIELDS: Map<String, Set<String>> = mapOf(
            "file_edit" to setOf("new_string"),
        )

        /** True when "" is a legal value for this exact (tool, field) pair. */
        internal fun preflightEmptyStringAllowed(tool: String, field: String): Boolean =
            PREFLIGHT_EMPTY_STRING_ALLOWED_FIELDS[tool]?.contains(field) == true

        /**
         * Reject tool calls that have empty args or are missing required fields
         * BEFORE [executeTool] runs. Returns null when the call is well-formed,
         * or a human-readable reason string when it should be blocked.
         *
         * Driven off the canonical [AgentToolDefinition.required] list so the
         * validator never drifts from the schema published to the model. For
         * string fields we additionally require non-blank content — the model
         * occasionally emits `{"path": ""}` which passes the "key exists" check
         * but is just as broken as a missing key. We do NOT validate type beyond
         * string-emptiness here; richer schema checks (enum, regex, integer
         * range) belong in each tool's own helper because they need tool-specific
         * context.
         *
         * Mirror of iOS preflightValidateToolCall in AIChatViewModel.swift.
         *
         * Lives in the companion (and is `internal`) because it is PURE — it reads
         * only its parameters and companion constants — so unit tests can exercise
         * it without constructing a ChatViewModel and its dependency graph. Mirrors
         * the same `nonisolated static` move on iOS.
         */
        internal fun preflightValidateToolCallImpl(
            name: String,
            args: JSONObject,
            tools: List<AgentToolDefinition>,
        ): String? {
            // Unknown tool names go through to the existing `else` branch in
            // executeTool() which returns "Unknown tool: …". Preflight stays
            // silent so we don't double-fail.
            val toolDef = tools.firstOrNull { it.name == name } ?: return null
            // Required fields that actually gate execution (everything except the
            // non-blocking ones like tool_title — see PREFLIGHT_NON_BLOCKING_FIELDS).
            val enforced = toolDef.required.filter { it !in PREFLIGHT_NON_BLOCKING_FIELDS }
            // Empty args on a tool that requires anything → block. Gate on
            // `enforced` so a tool whose only required field is non-blocking isn't
            // rejected for empty args, and the message lists only real blockers.
            if (args.length() == 0 && enforced.isNotEmpty()) {
                return "Tool '$name' was called with empty arguments {} but requires: ${enforced.joinToString(", ")}."
            }
            val missing = mutableListOf<String>()
            for (field in enforced) {
                // Absent — or present as an explicit JSON null. org.json reports
                // has() == true for `{"x": null}` and opt() hands back
                // JSONObject.NULL, which is not a String, so a null previously
                // slipped through BOTH checks and reached the tool as a non-String
                // value. Both spellings are genuinely missing.
                if (!args.has(field) || args.isNull(field)) {
                    missing.add(field)
                    continue
                }
                val raw = args.opt(field)
                // Only the truly-empty literal "" is rejected — NOT whitespace.
                // The earlier `.trim().isEmpty()` over-rejected legitimate payloads,
                // most notably file_edit with `new_string: "\n"` (replace a block
                // with a newline) or `old_string: "  "` (match consecutive spaces).
                // Both are valid edits, neither is stream corruption.
                //
                // And even "" is legal for whitelisted (tool, field) pairs:
                // file_edit.new_string == "" is the documented "delete old_string"
                // form, not a missing value. [T-preflight-empty-string-allowed]
                if (raw is String && raw.isEmpty() &&
                    !preflightEmptyStringAllowed(name, field)
                ) {
                    missing.add(field)
                }
            }
            if (missing.isNotEmpty()) {
                return "Tool '$name' is missing required parameter(s): ${missing.joinToString(", ")}."
            }
            return null
        }
        // [T-android-stream-flush-dualpath] Newline fast-path thresholds (iOS parity).
        private const val NEWLINE_FLUSH_MIN_CHARS = 50
        private const val NEWLINE_FLUSH_MAX_LEN = 5_000
        /**
         * DB rows parsed for request history on cold open. This is deliberately
         * larger than the UI tail: the database is the transcript authority and
         * scrolling the UI must never decide what the agent remembers.
         */
        const val INITIAL_LLM_HISTORY_ROW_CAP: Int = 4_000
        /** Hot cache ceiling; the request assembler may still reduce by byte/context budget. */
        internal const val MAX_AGENT_HISTORY_MESSAGES: Int = 4_000
        // T258: tool block statuses with no committed tool_result. retryLast()
        // drops blocks in any of these states because they would orphan the
        // assistant tool_use entry on retry (the API rejects unmatched
        // tool_use_ids). SUCCESS / FAILED / TIMEOUT / CANCELLED all have a
        // matching tool_result row already persisted and survive the retry.
        internal val IN_FLIGHT_TOOL_STATUSES = setOf(
            ToolBlockStatus.STREAMING,
            ToolBlockStatus.PENDING,
            ToolBlockStatus.RUNNING,
        )
        // T145 phase 1: dedicated tag so the streaming-state debug pipeline
        // can be filtered with `adb logcat -s Minis.ChatVMStream:D`.
        // Removed once the retry-state regression is rooted out.
        internal const val TAG_STREAM = "ChatVMStream"
        internal const val STOP_CLEANUP_JOIN_TIMEOUT_MS = 5_000L
        /**
         * Hard ceiling on agent loop iterations within a single user turn.
         * Backstop against runaway tool-call cycles that slip past
         * [ToolLoopDetector] (e.g. visited args/results vary just enough to
         * dodge the global circuit breaker). On reaching the limit the loop
         * finalizes as resumable — see runAgentLoop's tail and
         * [finalizeAtTurnLimit] — so the user gets an inline explanation +
         * Resume button rather than a silently stuck "thinking" indicator.
         * Mirrors iOS AIChatViewModel.maxAgentTurns.
         */
        internal const val MAX_AGENT_TURNS = 200
        internal const val MAX_GOAL_CONTINUATIONS = 16

        /**
         * [T-subagent-background] How many times one run may nudge the model to
         * collect a detached sub-agent wave before letting the turn end. Two,
         * not one: the first nudge often lands while lanes are still in flight,
         * so the model awaits, times out, and needs a second chance to say so
         * honestly. Capped because a nudge the model keeps ignoring must never
         * become an infinite loop — when it runs out, the post-loop footer
         * reports the dispatch ids to the user instead.
         */
        internal const val MAX_SUBAGENT_COLLECT_NUDGES = 2

        /**
         * [T-android-stream-drop-autocontinue] How many times a run may
         * silently re-enter the loop after the relay cut the SSE stream
         * mid-reply (clean EOF, no finish_reason, partial content already
         * streamed). Buffet/relay providers (nginx proxy_read_timeout,
         * upstream caps) do this routinely during long thinking silences or
         * right after big tool-call turns; each cut used to surface the red
         * "连接中断" banner and force a manual retry that regenerated the
         * turn. With auto-continuation the partial turn stays in history,
         * a continue-reminder is appended, and the next turn streams into
         * the SAME bubble — no progress lost, no model switch needed.
         * The banner only appears once this budget is exhausted.
         */
        internal const val MAX_STREAM_DROP_CONTINUATIONS = 3
        private const val MIN_MAX_TOKENS = 1024
        /**
         * Hard ceiling on max_tokens we ever send to a provider, regardless
         * of what the model itself claims. Some models advertise 128K+
         * output windows that in practice produce wandering, low-signal
         * responses and burn through context budget; cap so a single turn
         * can't run away. Mirrors iOS AIChatViewModel.globalMaxTokensCeiling.
         * [T-android-global-max-tokens-128k] Raised 64K → 128K (iOS 8a401ab6):
         * 64K clipped newer large-output models AND the number-budget thinking
         * tiers whose budget is carved out of max_tokens (Anthropic legacy
         * high/xhigh/max, Qwen thinking_budget — DashScope clamps it strictly
         * below max_completion_tokens). Raising only lifts the upper bound —
         * the value is still clamped by the model's own maxOutputTokens and
         * the remaining context window in dynamicMaxTokens().
         */
        private const val GLOBAL_MAX_TOKENS_CEILING = 128_000
        /**
         * Sentinel prefix on synthetic tool_result output marking
         * user-cancelled calls. Aligned with iOS
         * AIChatViewModel.swift:5163 so a session sync'd between
         * platforms shows the same `<system-reminder>…` text the model
         * sees on the next API call (rather than "[cancelled by user]"
         * which iOS would treat as opaque tool output).
         */
        const val CANCELLED_MARKER =
            "<system-reminder>The user cancelled this operation. The returned result may be incomplete.</system-reminder>"

        /**
         * Pre-T13 cancelled marker. Kept only so [toLLMMessage]'s
         * tool-block restore can still recognise rows persisted by
         * earlier app versions and surface them as CANCELLED instead
         * of FAILED. Never emitted by this version.
         */
        private const val LEGACY_CANCELLED_MARKER = "[cancelled by user]"
        /**
         * Number of recent user-text turns kept verbatim as inference anchors when
         * compactAll runs. The summary stands in for everything older; the LLM
         * still sees the last N user-text turns + their assistant replies + tool
         * I/O so it can answer follow-ups that need verbatim detail rather than
         * the summary's distilled form. Mirrors iOS `compactKeepRecentUserTurns`.
         */
        internal const val COMPACT_KEEP_RECENT_USER_TURNS = 3
        /// Max per-tool-call retained `accumulated` JSON snapshots from
        /// `ToolInputDelta`. Drained on preflight failure for diagnosis.
        internal const val TOOL_INPUT_CHUNK_RING_MAX = 2

        /**
         * Factory for use with `viewModel(factory = ...)`. Binds the ChatViewModel
         * to a NavBackStackEntry's ViewModelStore so the streaming job survives
         * configuration changes (rotation) and re-entering the chat screen while
         * the backstack entry is alive.
         */
        fun factory(
            sessionId: String,
            chatRepository: ChatRepository,
            providerRepository: ProviderRepository,
            appContext: Context,
            memoryRepository: MemoryRepository?,
            skillRepository: com.openminis.app.data.repository.SkillRepository?,
            mcpRepository: com.openminis.app.data.repository.MCPRepository? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChatViewModel(
                    sessionId = ChatViewModelStore.resolvePersistedId(sessionId),
                    chatRepository = chatRepository,
                    providerRepository = providerRepository,
                    context = appContext,
                    memoryRepository = memoryRepository,
                    skillRepository = skillRepository,
                    mcpRepository = mcpRepository,
                ).also { ChatViewModelStore.register(sessionId, it) } as T
            }
        }

        // ─── [T-u6-vm-port] Companion members adopted from upstream 1.14 ──────
        // Referenced by CompactBudgetTest / OverlayMultiTaskIndicatorTest.



        /**
         * [T-compact-idle-timeout] How long compaction may go WITHOUT receiving
         * any new stream data before it is declared stuck.
         *
         * This replaced a total-elapsed budget, which was measuring the wrong
         * thing. A healthy stream that is merely long looks identical to a hung
         * one under a wall-clock cap: the reported run was cancelled at 150s
         * having received 7,488 SSE events and 19,459 characters at ~40 tok/s —
         * data was flowing the entire time, and the only thing "wrong" was that
         * the transcript was big. Under an idle timer that run completes,
         * because the gap between consecutive deltas never approached this
         * value.
         *
         * Sized to sit under the providers' 10-minute socket readTimeout, so a
         * genuinely dead connection is reported by us — with the lock released
         * and a message the user can act on — rather than surfacing as a raw
         * socket error minutes later.
         */
        internal const val COMPACT_IDLE_TIMEOUT_MS = 120_000L


        /**
         * Absolute ceiling on one compaction run, idle timer notwithstanding.
         *
         * This is a backstop for the pathological cases an idle timer cannot
         * see — a model looping forever, or an unbounded response — where data
         * keeps arriving and so the idle timer keeps resetting, legitimately,
         * forever. It is deliberately generous: it must never be the limit that
         * decides a normal slow compaction's fate, or we are back to the bug
         * this change fixes. [COMPACT_IDLE_TIMEOUT_MS] is the working limit;
         * this one should effectively never fire.
         */
        internal const val COMPACT_MAX_TOTAL_MS = 900_000L


        /**
         * [T-compact-idle-timeout] Raised when a compaction stream goes quiet
         * for longer than [COMPACT_IDLE_TIMEOUT_MS].
         *
         * Deliberately NOT a TimeoutCancellationException. That type extends
         * CancellationException, which coroutines read as "this scope is
         * shutting down normally" — thrown from inside a flow it is
         * indistinguishable from the user pressing Cancel, and the splitter's
         * `catch (e: CancellationException) { throw e }` arm would re-throw it
         * before any retry logic saw it, while the outer handler would report a
         * silent cancel instead of a timeout. As a plain Exception it travels
         * the normal error path and can be classified like any other failure.
         */
        internal class CompactIdleTimeoutException(message: String) : Exception(message)


        /**
         * Placeholder title a session carries until LLM title generation names
         * it. A SENTINEL, not a display string: `generateTitle`'s skip guard
         * tests for this exact value to decide whether a session still needs a
         * title, so it must not be localized and must not drift.
         *
         * Named because it was previously written out at five sites, and the
         * overlay now needs to recognise it to avoid showing "New Chat" as if
         * it were a task name.
         */
        const val UNTITLED_SESSION_TITLE = "New Chat"

    }

    internal val mediaStore = com.openminis.app.data.storage.MediaStore(context)

    internal val _messages = SnapshotMutableStateFlow<List<ChatMessage>>(
        emptyList(),
        ::snapshotChatMessages,
    )
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // ── Long-session window cap ────────────────────────────────────────
    //
    // [T-android-larky-longsession-followup] On sessions with hundreds of
    // ChatMessage entries (Larky's 612-row monster, totalChars ~1.9MB)
    // feeding the whole list into the LazyColumn pipeline caused cascading
    // main-thread cost: per-frame regex/matcher churn from streaming-side
    // detection, repeated AnnotatedString construction for re-anchored
    // items, and LRU thrash on the markdown caches. The list-virtualization
    // is fine on its own, but the streaming pipeline (combine + sample) and
    // the FlatChat flattening both walk the full list every tick.
    //
    // Strategy: `_messages` is one contiguous loaded slice. Cold open reads
    // the newest tail, not the whole session. Scrolling to an edge prepends
    // or appends the next turn-aligned page and does not drop the other
    // side. The database remains the full transcript. The model window is
    // a separate, digested slice.
    //
    // Reset on session load (different sessionId) is wired in loadSession.

    // Database window state. The canonical UI list contains only this loaded
    // window; older rows are fetched on demand instead of retaining the whole
    // session and its parsed LLM representation in memory.
    //
    // [T-android-timeline-ledger] Cursors, counters and the operation mutex
    // live in [TimelineWindow] (single owner — see its KDoc for what the old
    // scattered-vars shape used to break).
    internal val timeline = TimelineWindow()
    // [T-window-flag-cas] Single-flight for loadOlderPage. A plain Boolean
    // check-then-set here is only safe on the main thread — and loadOlderPage
    // is internal suspend, so any future caller from another dispatcher would
    // silently break the guard. CAS makes the invariant machine-enforced.
    internal val loadingOlderFlag = java.util.concurrent.atomic.AtomicBoolean(false)
    internal val loadingOlderMessages: Boolean get() = loadingOlderFlag.get()

    /** CAS single-flight; true when this caller won the right to load. */
    internal fun claimOlderLoad(): Boolean = loadingOlderFlag.compareAndSet(false, true)

    /** Tail-attach scheduling flags below are MAIN-THREAD ONLY (all writers
     *  run on viewModelScope Main.immediate); they gate scheduling, while the
     *  ledger mutex serializes the actual window mutation. */

    // Tail-attach scheduling flags. Serialization itself is the ledger's
    // mutationMutex — these only decide whether a drain is already running
    // and whether one more pass is owed after it.
    private var tailAttachJob: kotlinx.coroutines.Job? = null
    private var tailAttachQueued = false
    internal val llmDigestLines = ArrayDeque<com.openminis.app.harness.context.HistoryDigest.Line>()
    internal var llmDigestOmitted = 0

    /**
     * Index (in DB order from the session start) of the first row that
     * [agentHistory] currently covers after the windowed cold-open parse.
     * Rows before it exist in `_messages` only when the user loaded older
     * UI history; their LLM forms are parsed on demand before send.
     */
    internal var llmHistoryStartOffset = 0
    /**
     * The contiguous loaded slice. Cold open is a tail; paging extends it
     * without a second cut. A takeLast here used to hide the middle while
     * both ends still looked present.
     */
    val uiMessages: StateFlow<List<ChatMessage>> =
        _messages.map { raw ->
            // [T-bridge-message-ui-leak-android] Single UI-collection sink for
            // EVERY path that pushes messages to the list (loadSession, live
            // stream append, compact rebuild, snapshot reload, sync refresh…).
            // Filter the internal role-alternation bridge here so it can never
            // surface as a chat bubble regardless of which path produced it.
            // Only allocate a new list when a bridge is actually present.
            if (raw.any { it.isInternalBridge }) raw.filterNot { it.isInternalBridge } else raw
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            emptyList(),
        )

    /**
     * Whether the current session has older messages above the window.
     * ChatScreen uses this to show / hide the "Load older messages" header
     * pill on the LazyColumn.
     */
    private val _hasOlderMessages = MutableStateFlow(false)
    val hasOlderMessages: StateFlow<Boolean> = _hasOlderMessages.asStateFlow()
    private val _hasNewerMessages = MutableStateFlow(false)
    val hasNewerMessages: StateFlow<Boolean> = _hasNewerMessages.asStateFlow()
    internal val _isLoadingHistory = MutableStateFlow(false)
    val isLoadingHistory: StateFlow<Boolean> = _isLoadingHistory.asStateFlow()

    internal suspend fun refreshHistoryEdges(scheduleTail: Boolean = true) {
        val oldest = timeline.oldestSortOrder
        val newest = timeline.newestSortOrder
        if (oldest == null || newest == null) {
            timeline.reset()
            _hasOlderMessages.value = false
            _hasNewerMessages.value = false
            return
        }
        val before = chatRepository.dao.countMessagesBeforeSort(sessionId, oldest)
        val after = chatRepository.dao.countMessagesAfterSort(sessionId, newest)
        val loadedDbRows = if (newest == Int.MAX_VALUE) {
            // Avoid overflowing the half-open upper bound at the integer edge.
            chatRepository.dao.messageCountForSession(sessionId) - before - after
        } else {
            chatRepository.dao.countMessagesInSortRange(
                sessionId = sessionId,
                startInclusive = oldest,
                endExclusive = newest + 1,
            )
        }
        // Derive the counters from DB sequence ranges, never from the number
        // of painted rows. One ChatMessage can represent several DB rows.
        // [T-android-timeline-ledger] The edge booleans are DB-authoritative:
        // nothing else in the app may write them (the old offset-based
        // refreshHasOlderMessages double ledger is gone).
        timeline.seedCounters(offset = before, total = before + loadedDbRows + after)
        _hasOlderMessages.value = before > 0
        _hasNewerMessages.value = after > 0
        // A newer gap means the painted window is not the session tail.
        // Attach it. Do not wait for a second control.
        if (scheduleTail && after > 0) ensureSessionTailLoaded()
    }

    internal fun rememberDigestLines(lines: List<com.openminis.app.harness.context.HistoryDigest.Line>) {
        if (lines.isEmpty()) return
        llmDigestLines.addAll(lines)
        val retained = com.openminis.app.harness.context.HistoryDigest.retainNewest(llmDigestLines)
        llmDigestOmitted += (llmDigestLines.size - retained.size).coerceAtLeast(0)
        llmDigestLines.clear()
        llmDigestLines.addAll(retained)
    }

    /**
     * [T-truncation-digest-clear] 长按截断类操作（从此处删除 / 重试 / 撤回）之后
     * 调用：digest 摘录是有界窗口此前裁掉行的"裁剪痕迹"，每轮请求经
     * HistoryDigest.inject 重新注入。截断刚删掉的那些轮次正是摘录的内容——
     * 不清空的话，重建后的 agentHistory 是对的、但注入的摘录仍是已删对话，
     * 模型接到的依旧是旧上下文（用户实测：删除/重试/回滚后接错上下文）。
     * 窗口外的幸存行摘录一并丢弃是可接受代价：随后每轮窗口裁剪会自然重建。
     */
    fun clearHistoryDigestState() {
        llmDigestLines.clear()
        llmDigestOmitted = 0
    }

    /**
     * [T-android-persist-row-refresh-merge] [notePersistedUiRow] 的批处理状态：
     * 合并窗口内新落库的消息 id，以及该窗口的收尾协程。
     */
    private val pendingPersistedRowIds = mutableListOf<String>()
    private var persistedRowFlushJob: Job? = null

    internal fun notePersistedUiRow(uiMessageId: String?, dbMessageId: String) {
        if (uiMessageId != null) {
            _messages.value = _messages.value.map { message ->
                if (message.id == uiMessageId && dbMessageId !in message.sourceDbIds) {
                    message.copy(sourceDbIds = message.sourceDbIds + dbMessageId)
                } else message
            }
        }
        // [T-android-persist-row-refresh-merge] DB 侧对账按「轮」合并。一个回合会
        // 连着落好几行（assistant 行 + 每个 tool_result 行），旧实现每行都起一个
        // 协程跑 sortOrderOf / countMessagesAfterSort / refreshHistoryEdges（3 次
        // count）= 4~6 次 DAO 查询，全是重复的 IO。这里只登记，让
        // [PERSISTED_ROW_FLUSH_DELAY_MS] 窗口内的所有落库共用一次对账。
        //
        // sourceDbIds 回填保持同步（纯内存、无 IO）：它参与分页合并去重
        // （mergeTailChunk 等按 sourceDbIds 判重叠）与翻译回写寻址，延后 200ms
        // 会让刚落库的行被分页结果当成新行重复绘制。
        pendingPersistedRowIds.add(dbMessageId)
        if (persistedRowFlushJob?.isActive == true) return
        persistedRowFlushJob = viewModelScope.launch {
            delay(PERSISTED_ROW_FLUSH_DELAY_MS)
            // 边跑边到的行不能漏：排空 → 对账 → 再看一眼。旧实现每行各起一个协程，
            // 改成合并后必须保证"最后一行"也被这次对账覆盖（否则它的 sort_order
            // 要等到下一个落库行才推进游标）。
            while (true) {
                val batch = pendingPersistedRowIds.toList()
                pendingPersistedRowIds.clear()
                if (batch.isEmpty()) break
                flushPersistedRows(batch)
                if (pendingPersistedRowIds.isEmpty()) break
            }
        }
    }

    /**
     * [T-android-persist-row-refresh-merge] 一批新落库行的对账。
     *
     * 批内只查一次 sort_order；能否把 newest 游标推到批内最大值，仍由 **DB 范围**
     * 判定（[T-android-timeline-ledger]）：批覆盖 (newest, max] 区间的全部行才允许
     * 前进，否则中间存在还没画出来的行，跳过去就把它们藏了——与旧实现逐行
     * `countMessagesAfterSort(..) > 1` 的判据等价。
     */
    private suspend fun flushPersistedRows(dbMessageIds: List<String>) {
        val newest = timeline.newestSortOrder
        val sorts = withContext(Dispatchers.IO) { chatRepository.dao.sortOrdersOf(dbMessageIds) }
        if (sorts.isNotEmpty()) {
            val maxSort = sorts.max()
            val advance = when {
                newest == null -> true
                maxSort <= newest -> false
                else -> {
                    val ours = sorts.count { it > newest }
                    withContext(Dispatchers.IO) {
                        chatRepository.dao.countMessagesInSortRange(sessionId, newest + 1, maxSort + 1) == ours
                    }
                }
            }
            if (advance) timeline.extendNewest(maxSort)
        }
        refreshHistoryEdges()
    }

    /**
     * Prepend one turn-aligned page in the background (pill / sentinel entry
     * point). See [loadOlderPage] for the single-page contract.
     */
    fun loadOlderMessages() {
        if (_isStreaming.value) return
        if (!claimOlderLoad()) return
        loadingOlderFlag.set(false)
        viewModelScope.launch { loadOlderPage() }
    }

    /**
     * Prepend one turn-aligned page. The newer side stays on screen.
     * A live turn sits on that edge, so a database fetch waits until it ends.
     * Returns true when rows were added to the painted window.
     *
     * [T-android-timeline-ledger] The whole fetch+splice holds the ledger's
     * mutation mutex so a concurrent tail-attach drain cannot interleave its
     * own splice into the same `_messages` publish. Single-flight: a call
     * while a page is already loading is a no-op (the caller re-resolves on
     * its next tap).
     *
     * [T-android-upbtn-no-scan] The up-button awaits this when its walk
     * reaches the window's oldest edge with history still above — one page
     * per tap keeps each click bounded.
     */
    internal suspend fun loadOlderPage(): Boolean {
        if (_isStreaming.value) return false
        if (!claimOlderLoad()) return false
        _isLoadingHistory.value = true
        try {
            var added = false
            timeline.mutationMutex.withLock {
                val before = timeline.oldestSortOrder
                if (before == null) return@withLock
                val start = withContext(Dispatchers.IO) {
                    olderTurnStart(before, ChatHistoryWindow.TURN_PAGE_SIZE)
                }
                // [T-load-older-fallback] Turn-aligned paging targets whole
                // user turns. When the remaining history before the window has
                // fewer than TURN_PAGE_SIZE user turns (or none at all),
                // olderTurnStart returns null even though countMessagesBeforeSort
                // still reports rows — mostly assistant-only trailing messages.
                // Without a fallback, the pill stays lit but every click just
                // calls refreshHistoryEdges and returns: visible button, zero
                // effect. Fall back to a non-turn-aligned load of all remaining
                // rows instead of freezing at the boundary.
                val loadStart = if (start != null && start < before) {
                    start
                } else {
                    // Turn alignment failed. Load everything from session start
                    // to the current window boundary.
                    val remaining = withContext(Dispatchers.IO) {
                        chatRepository.dao.countMessagesBeforeSort(sessionId, before)
                    }
                    if (remaining <= 0) {
                        refreshHistoryEdges(scheduleTail = false)
                        return@withLock
                    }
                    // Walk back to the very first message that still exists.
                    // Using sort_order 0 isn't safe after compact, so probe.
                    var fallback: Int? = null
                    var probeCursor: Int = before
                    while (true) {
                        val anchors = withContext(Dispatchers.IO) {
                            chatRepository.dao.loadOlderSortAnchors(sessionId, probeCursor, 200)
                        }
                        if (anchors.isEmpty()) break
                        fallback = anchors.last().sortOrder
                        if (anchors.size < 200) break
                        if (anchors.last().sortOrder >= probeCursor) break
                        probeCursor = anchors.last().sortOrder
                    }
                    fallback
                }
                if (loadStart == null || loadStart >= before) {
                    // Fallback exhausted every probe but the earlier count
                    // still saw rows. That contradiction almost always means
                    // rows were DELETED mid-flight (truncate/retry renumbers
                    // the tail); the old code hard-fused
                    // `_hasOlderMessages=false` here, permanently hiding the
                    // pill even when the DB still had older rows — the
                    // "部分消息从会话页消失" bug. The DB recount inside
                    // refreshHistoryEdges is the only authority on the edge.
                    refreshHistoryEdges(scheduleTail = false)
                    return@withLock
                }
                val pageRows = withContext(Dispatchers.IO) {
                    chatRepository.hydrateDisplayRows(
                        loadSortRange(
                            SortRange(
                                loadStart,
                                if (before == Int.MAX_VALUE) before else before + 1,
                            ),
                        ),
                    )
                }
                val rows = pageRows
                if (rows.isNotEmpty()) {
                    val current = _messages.value
                    val known = current.flatMapTo(mutableSetOf()) { it.sourceDbIds }
                    val older = rows.toChatMessages()
                    val updated = current.map { message ->
                        older.firstOrNull { incoming ->
                            incoming.sourceDbIds.any(message.sourceDbIds::contains)
                        } ?: message
                    }
                    val fresh = older.filter { message ->
                        message.sourceDbIds.isEmpty() || message.sourceDbIds.none(known::contains)
                    }
                    if (fresh.size < older.size) {
                        // Rows in this page are already painted — the cursor
                        // should have excluded them. Never eat them silently:
                        // log loudly so a double-splice shows up in the trace.
                        AppLogger.warning(
                            TAG,
                            "loadOlder: page [$loadStart,$before) returned ${older.size} rows, " +
                                "${older.size - fresh.size} already painted (cursor=${timeline.oldestSortOrder})",
                        )
                    }
                    if (fresh.isNotEmpty() || updated != current) {
                        _messages.value = fresh + updated
                        added = true
                    }
                    timeline.noteBounds(rows)
                }
                // [T-load-older-no-tail-attach] 不在这里 scheduleTail：
                // 用户刚点“加载更早”正在向上读历史，此时立刻触发
                // ensureSessionTailLoaded 追加尾部 chunk 会连续两次突变
                // _messages，LazyColumn 锚点被打掉 → 跳屏 + 一段记录
                // 被悄悄加载。尾部 gap 由 isStreaming 翻转收集器与
                // ensureSessionTailLoaded 的 queue 机制兜底，不需要
                // 借翻历史的动作来抢跑。
                refreshHistoryEdges(scheduleTail = false)
            }
            return added
        } finally {
            loadingOlderFlag.set(false)
            _isLoadingHistory.value = false
        }
    }

    /**
     * The painted window must include the session tail. If rows exist after
     * the newest cursor, append them. A click that only loads five turns
     * is what left the newer transcript off screen.
     */
    fun ensureSessionTailLoaded() {
        if (sessionId.isEmpty()) return
        if (_isStreaming.value || tailAttachJob?.isActive == true) {
            tailAttachQueued = true
            return
        }
        tailAttachQueued = false
        tailAttachJob = viewModelScope.launch {
            _isLoadingHistory.value = true
            try {
                var idlePasses = 0
                while (!_isStreaming.value && idlePasses < 3) {
                    val before = timeline.newestSortOrder
                    drainMissingTail()
                    refreshHistoryEdges(scheduleTail = false)
                    val again = tailAttachQueued || _hasNewerMessages.value
                    tailAttachQueued = false
                    if (!again) break
                    if (timeline.newestSortOrder == before) idlePasses++ else idlePasses = 0
                }
            } finally {
                val restart = tailAttachQueued && !_isStreaming.value
                tailAttachJob = null
                tailAttachQueued = false
                _isLoadingHistory.value = false
                if (restart) ensureSessionTailLoaded()
            }
        }
    }

    private suspend fun drainMissingTail() {
        var chunks = 0
        while (!_isStreaming.value && chunks < 10_000) {
            val endExclusive = withContext(Dispatchers.IO) {
                chatRepository.dao.nextSortOrder(sessionId)
            }
            val missing = ChatHistoryWindow.missingTailRange(
                timeline.newestSortOrder,
                endExclusive,
            ) ?: return
            val chunkEnd = minOf(
                missing.endExclusive.toLong(),
                missing.startInclusive.toLong() + ChatHistoryWindow.TAIL_ATTACH_CHUNK.toLong(),
            ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (chunkEnd <= missing.startInclusive) return
            // One chunk = one critical section: fetch + splice + cursor under
            // the same lock so a concurrent load-older page cannot splice in
            // between (that race produced duplicated and reordered rows).
            timeline.mutationMutex.withLock {
                val rows = withContext(Dispatchers.IO) {
                    chatRepository.hydrateDisplayRows(
                        // Include the already-painted boundary row so a
                        // toolUse immediately before this chunk can be paired
                        // with its toolResult inside toChatMessages(). The
                        // overlap is removed by sourceDbIds below.
                        loadSortRange(
                            SortRange(
                                if (missing.startInclusive > Int.MIN_VALUE) missing.startInclusive - 1 else missing.startInclusive,
                                chunkEnd,
                            ),
                        ),
                    )
                }
                if (rows.isEmpty()) {
                    // [T-android-timeline-ledger] A hydrate can legitimately
                    // return nothing (body-store admission pressure) while the
                    // DB still HAS rows in this range. The old code pushed the
                    // cursor past the gap on empty rows — silently consuming
                    // rows that were never painted. Verify against the DB:
                    // consume only a genuinely empty range; otherwise leave
                    // the cursor alone and stop this pass (a later attach
                    // retries instead of spinning).
                    val dbCount = withContext(Dispatchers.IO) {
                        chatRepository.dao.countMessagesInSortRange(
                            sessionId,
                            missing.startInclusive,
                            chunkEnd,
                        )
                    }
                    if (dbCount > 0) {
                        AppLogger.warning(
                            TAG,
                            "tail drain: range [${missing.startInclusive},$chunkEnd) has $dbCount rows " +
                                "but hydrate returned none — leaving gap unconsumed",
                        )
                        return
                    }
                    timeline.forceNewestAtLeast(chunkEnd - 1)
                    chunks++
                    return@withLock
                }
                val missingMessages = rows.toChatMessages()
                val current = _messages.value
                val present = current.flatMapTo(mutableSetOf()) { it.sourceDbIds }
                // The first row may be an overlap from the already-painted
                // window. Replace that existing bubble as well: this is what
                // makes a toolResult that arrived in the next chunk update the
                // previously painted toolUse card instead of being silently
                // discarded as a duplicate.
                val updated = current.map { message ->
                    missingMessages.firstOrNull { incoming ->
                        incoming.sourceDbIds.any(message.sourceDbIds::contains)
                    } ?: message
                }
                val freshStart = missingMessages.indexOfFirst { message ->
                    message.sourceDbIds.none(present::contains)
                }
                if (freshStart >= 0) {
                    val fresh = missingMessages.filter { message ->
                        message.sourceDbIds.none(present::contains)
                    }
                    val insertAt = ChatHistoryWindow.missingTailInsertIndex(
                        currentSourceIds = current.map { it.sourceDbIds },
                        chunkSourceIds = missingMessages.map { it.sourceDbIds },
                        freshStartIndex = freshStart,
                    )
                    _messages.value = if (insertAt < 0) {
                        updated + fresh
                    } else {
                        updated.take(insertAt) + fresh + updated.drop(insertAt)
                    }
                } else if (updated != current) {
                    _messages.value = updated
                }
                timeline.noteBounds(rows)
            }
            chunks++
        }
    }

    /**
     * Streaming side-channel — see [StreamingDelta]. During a live agent
     * turn, [updateAssistantMessage] writes delta-bearing fields here
     * INSTEAD of mutating the messages list. This isolates per-token
     * updates from ChatScreen's top-level recompose scope (the 8980-line
     * mega-composable was being walked at full slot-table cost on every
     * token, costing ~94 ms per recompose). Top-level subscribers
     * (`messages.any/.associate/.isNotEmpty/.lastOrNull`) only see a new
     * list reference at turn *boundaries* — at start (message added) and
     * end (final content synced back).
     *
     * Renderers that need streaming content (AssistantText, Thinking,
     * tool pills, etc.) read this flow per-item inside their composable
     * scope so Compose's stable-skip restricts the recompose blast radius
     * to that one item.
     *
     * The map is keyed by the assistant message id; absent ⇒ no live
     * stream (turn either hasn't started or has already flushed).
     */
    internal val _streamingById = SnapshotMutableStateFlow<Map<String, StreamingDelta>>(
        emptyMap(),
        ::snapshotStreamingDeltas,
    )
    val streamingById: StateFlow<Map<String, StreamingDelta>> = _streamingById.asStateFlow()

    private val streamSession = StreamSessionController(
        scope = viewModelScope,
        messages = _messages,
        streamingById = _streamingById,
        newlineFlushMinChars = NEWLINE_FLUSH_MIN_CHARS,
        newlineFlushMaxLen = NEWLINE_FLUSH_MAX_LEN,
    )

    internal fun clearStreamFlushState(id: String) = streamSession.clearStreamFlushState(id)
    private fun clearAllStreamFlushStates() = streamSession.clearAllStreamFlushStates()
    internal fun retainStreamFlushStates(keptIds: Set<String>) =
        streamSession.retainStreamFlushStates(keptIds)

    /**
     * Composer draft. Owned by VM so it survives navigation (e.g. push EnvVars
     * and pop back) — `ChatViewModelStore` keeps the VM alive across screen
     * pushes, but `remember { … }` inside `ChatScreen` does not. Mirrors iOS
     * `AIChatView` which binds against `vm.inputText`.
     */
    internal val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    /**
     * [T-android-slash-menu-align-ios-prepend] One-shot caret position the
     * composer should apply on the NEXT inputText emission, mirroring iOS
     * `pendingCaret`. Null means "no override — caret to end" (the existing
     * default). Set when the slash flow prepends "/ " (caret lands at 1, right
     * after the slash, so typing filters the menu) or inserts "/<skill> "
     * (caret after the prefix, before the preserved body). The composer reads
     * it once in its inputText LaunchedEffect and clears it via [consumePendingCaret].
     */
    internal val _pendingCaret = MutableStateFlow<Int?>(null)
    val pendingCaret: StateFlow<Int?> = _pendingCaret.asStateFlow()

    /** Read-and-clear the pending caret so it applies exactly once. */
    fun consumePendingCaret(): Int? {
        val c = _pendingCaret.value
        _pendingCaret.value = null
        return c
    }

    /**
     * Chat list scroll state. Hoisted onto the VM so it survives ChatScreen
     * recomposition / disposal triggered by forward navigation (file preview,
     * env-vars push, etc.). `rememberSaveable` was insufficient because the
     * surrounding composition is re-entered on pop and the SaveableStateHolder
     * scope doesn't always restore in time — keeping the LazyListState on the
     * session-scoped VM (kept alive by ChatViewModelStore) guarantees both the
     * firstVisibleItemIndex/offset and the layoutInfo cache survive intact, so
     * the LazyColumn paints its previous viewport on the first frame instead of
     * remeasuring from index 0 (white flash).
     */
    val listState: LazyListState = LazyListState(0, 0)

    fun setInputText(value: String) {
        _inputText.value = value
    }

    /**
     * [T-selection-add-to-input] Append [snippet] to the chat composer
     * with a single trailing space:
     *   - composer empty → `"<snippet> "`
     *   - composer non-empty → `"<existing><separator><snippet> "`
     *
     * [T-android-append-to-input-fence] Exception: a snippet that ends in a
     * code fence gets a trailing NEWLINE instead of the space, and one that
     * starts with a fence is pushed onto its own line. See
     * [joinDraftWithSnippet].
     *
     * [T-android-append-to-input-eats-draft] Trim the incoming SNIPPET only.
     * The old code called `current.trimEnd()` and assigned that trimmed copy
     * back, so "Add to input" silently rewrote the user's existing draft: a
     * deliberate trailing newline (a paragraph break they had just typed) was
     * swallowed and replaced by the separator space. The draft is the user's
     * own text and must come back byte-for-byte.
     *
     * The emptiness test still runs on a trimmed view — a draft of only
     * whitespace should be treated as empty rather than producing a leading
     * blank run — but that trimmed value drives the DECISION only, never the
     * assignment. Mirrors iOS `e6c0ace6a`.
     */
    fun appendToInputText(snippet: String) {
        val joined = joinDraftWithSnippet(_inputText.value, snippet) ?: return
        _inputText.value = joined
    }

    internal val _isStreaming = MutableStateFlow(false)
    override val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    /**
     * 「▶ 运行」进行中的代码块正文集合。key = 代码原文（渲染层经
     * LocalCodeBlockRunState 消费，见 ui/markdown/CodeBlockRunState.kt；
     * markdown block id 在重解析时会变，代码正文才是稳定 key）。
     * 写入方：ChatViewModelCodeRunExt.runCodeBlockInline。
     */
    internal val _codeBlockRunState = MutableStateFlow<Set<String>>(emptySet())
    val codeBlockRunState: StateFlow<Set<String>> = _codeBlockRunState.asStateFlow()

    /**
     * T261: tool detail sheet visibility, persistent across LazyColumn
     * recomposition / item disposal so a streaming tool's sheet doesn't
     * snap shut when its pill scrolls out of viewport. Stable key = tool
     * block id (server-assigned tool_use_id). Null = closed.
     *
     * Lifecycle: opened by [openToolDetail], closed by [closeToolDetail]
     * (user dismiss) or by ChatScreen's existence-guard LaunchedEffect when
     * the underlying block is gone (T258 retry-preserve drops in-flight
     * tools, session switch, etc.). Not persisted to disk — sheet is a
     * transient UI state.
     */
    internal val _selectedToolDetailId = MutableStateFlow<String?>(null)
    val selectedToolDetailId: StateFlow<String?> = _selectedToolDetailId.asStateFlow()

    // [T-android-split-chat] openToolDetail / closeToolDetail moved to ChatViewModelUiStateExt.kt.

    /**
     * True when the user cancelled mid-turn and the conversation can be
     * resumed by re-prompting the model to pick up where it left off.
     * Mirrors iOS AIChatViewModel.canResume. Cleared by [resume], by the
     * next real [sendMessage], or on error.
     */
    internal val _canResume = MutableStateFlow(false)
    val canResume: StateFlow<Boolean> = _canResume.asStateFlow()

    /**
     * [T-android-group-pause-badge-restamp] Marks the ONE `_canResume = true`
     * assignment that is a RE-DETECTION of an interruption that already
     * happened (loadSession finding a still-unfinished DB tail, possibly days
     * old) rather than a live new interruption. Read by the badge collector to
     * decide whether the badge's entry timestamp may be overwritten — see the
     * collector's comment for why the badge must NOT be re-stamped there.
     *
     * Why a COUNTER and not a plain boolean set-then-cleared around the
     * assignment: the collector is an async `collect` on a StateFlow, running
     * on its own coroutine. A boolean cleared right after the assignment is
     * very likely already `false` by the time the collector is resumed and
     * observes the `true`, so the annotation would be lost and the stale badge
     * re-stamped anyway — the exact bug being fixed. Instead the flag is
     * STICKY: the detecting site raises it BEFORE assigning and never clears
     * it; the collector clears it only once it has actually consumed the
     * emission it annotates. The generation counter makes that consumption
     * unambiguous even if several loads race — the collector compares the
     * value it latched against the current one.
     *
     * StateFlow conflation is also handled by this shape: if `_canResume` is
     * already `true`, the re-detection assignment emits nothing at all, so the
     * collector never runs and never re-stamps — which is the desired outcome
     * (no push, no stamp change). The pending mark simply stays raised and is
     * consumed by the next `true` emission, which for this VM instance can
     * only come from the same load path re-running (every live-interruption
     * site is preceded by a `false`, i.e. by a real run that clears it — see
     * `markLiveInterruption`).
     */
    @Volatile private var redetectingInterruptedTailGen: Long = 0L
    @Volatile private var consumedRedetectGen: Long = 0L

    /**
     * Raise the re-detection mark for the next `_canResume = true` emission.
     * Mirrors iOS `isRedetectingInterruptedTail = true` at the +Persistence
     * detection site.
     */
    internal fun markRedetectingInterruptedTail() {
        redetectingInterruptedTailGen += 1
    }

    /**
     * Cancel any pending re-detection mark. Called by every LIVE interruption
     * path before it sets `_canResume = true`, so an unconsumed mark left over
     * from a load (e.g. the load found the tail interrupted while `_canResume`
     * was already true, so nothing was emitted) can never leak onto a genuine
     * new interruption and suppress its re-stamp.
     */
    internal fun markLiveInterruption() {
        consumedRedetectGen = redetectingInterruptedTailGen
    }

    /**
     * T187: id of a user message currently being re-edited via the
     * long-press → Edit context menu. While non-null, the composer
     * shows an "Exit Edit Mode" pill, and the next sendMessage()
     * call truncates the conversation from this message (inclusive)
     * before persisting the new content as a fresh user turn.
     * Mirrors iOS AIChatViewModel.editingMessageIndex.
     */
    internal val _editingMessageId = MutableStateFlow<String?>(null)
    val editingMessageId: StateFlow<String?> = _editingMessageId.asStateFlow()

    internal val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    internal val _modelName = MutableStateFlow("")
    override val modelName: StateFlow<String> = _modelName.asStateFlow()

    /** T201: gate the init-time `config.collect` re-resolver so the StateFlow's
     *  replay cache can't beat [loadSession] to setting `_modelName`. Without
     *  this, opening a session that previously fell back mid-run flashes the
     *  default model name for one frame before the persisted binding settles. */
    internal val sessionLoaded = MutableStateFlow(false)

    internal val _sessionTitle = MutableStateFlow("New Chat")
    val sessionTitle: StateFlow<String> = _sessionTitle.asStateFlow()

    /** T-chat-title-pill: category drives the icon shown in the sticky title
     *  pill (mirrors SessionRow's categoryStyle lookup). Null on draft sessions
     *  and until LLM title-generation tags the session. */
    internal val _sessionCategory = MutableStateFlow<String?>(null)
    val sessionCategory: StateFlow<String?> = _sessionCategory.asStateFlow()

    internal val _attachments = MutableStateFlow<List<InputAttachment>>(emptyList())
    val attachments: StateFlow<List<InputAttachment>> = _attachments.asStateFlow()

    override fun addAttachment(attachment: InputAttachment) {
        _attachments.value = _attachments.value + attachment
    }

    /**
     * [T-android-paste-placeholder] Long pasted blocks folded out of the
     * composer, keyed by the `[Pasted#N]` marker left in its place.
     *
     * Scoped to this ViewModel, so it is per-session by construction: the
     * store hands each session its own instance, and switching chats cannot
     * leak an id from one buffer into another's placeholders. Memory-only —
     * see [PastedText] for why persisting it would be worse than not.
     */
    internal val _pastedTexts = MutableStateFlow<List<PastedText>>(emptyList())
    val pastedTexts: StateFlow<List<PastedText>> = _pastedTexts.asStateFlow()

    /**
     * Next placeholder number. Monotonic for the session's lifetime and never
     * reused, even after entries are consumed or deleted: a recycled id would
     * let a stale marker left in the draft ("I pasted, deleted the chip, then
     * pasted again") silently expand to the WRONG text. Numbers are cheap.
     */
    private var nextPasteId: Int = 1

    /**
     * [T-android-paste-placeholder] Buffer [text], returning the marker to put
     * in the composer in its place.
     */
    fun stashPastedText(text: String): String {
        val entry = PastedText(id = nextPasteId++, text = text)
        _pastedTexts.value = _pastedTexts.value + entry
        AppLogger.info(TAG, "[Paste] stashed #${entry.id} (${text.length} chars)")
        return entry.placeholder
    }

    /**
     * [T-android-paste-oversize] Turn a very large paste into a real `.txt`
     * document attachment instead of a placeholder.
     *
     * Past [PASTE_AS_FILE_THRESHOLD] the user is effectively attaching a
     * document, and the placeholder path is the wrong shape for it: the block
     * would sit in memory for the whole draft and then have to be written out
     * anyway. Routing it through the ordinary attachment pipeline instead means
     * it inherits preview, removal, the `<user-attached-files>` inventory the
     * model can `cat`, and the same upload handling as a file the user picked —
     * none of which the buffer offers.
     *
     * The bytes go to `cacheDir/pasted_text`, matching where
     * [addAttachmentFromStagedShare] puts share-inbound copies: the composer may
     * hold this for a long time before send, so it must not live anywhere the
     * system might reclaim mid-draft.
     *
     * Returns null if the write fails, and the caller then leaves the paste in
     * the text field verbatim — worse-looking than a chip, but nothing is lost.
     */
    fun stashPastedTextAsFile(text: String): InputAttachment? {
        val dir = java.io.File(context.cacheDir, "pasted_text").apply { mkdirs() }
        // Timestamp + short uuid: sorts chronologically in a file listing and
        // cannot collide when two pastes land in the same millisecond.
        val stamp = IsoTime.formatCompactSeconds()
        val name = "Pasted_$stamp-${java.util.UUID.randomUUID().toString().take(8)}.txt"
        val file = java.io.File(dir, name)
        return try {
            file.writeText(text)
            val attachment = InputAttachment(
                fileName = name,
                uri = android.net.Uri.fromFile(file),
                mimeType = "text/plain",
                kind = com.openminis.app.session.InputAttachment.Kind.DOCUMENT,
            )
            addAttachment(attachment)
            AppLogger.info(
                TAG,
                "[Paste] oversize paste -> file attachment $name (${text.length} chars)",
            )
            attachment
        } catch (e: Exception) {
            AppLogger.warning(TAG, "[Paste] failed to write oversize paste: ${e.message}")
            null
        }
    }

    /**
     * Drop one buffered entry (the chip's delete button). The caller is
     * responsible for also removing the marker from the composer text — see
     * ChatScreen, which does both in one edit so the two never disagree.
     */
    fun removePastedText(id: Int) {
        _pastedTexts.value = _pastedTexts.value.filterNot { it.id == id }
    }

    /**
     * One-shot composer-side image-budget events (T-imgsize). Emitted by
     * [prepareUserAttachments] when [ImageBudget.applyMessageBudget] either
     * re-encodes oversize local attachments or drops images that would push
     * the message over the cumulative cap. ChatScreen collects this flow
     * and surfaces a localized Snackbar — provider-boundary compression
     * (history images) does not emit here to keep history-replay silent.
     */
    internal val _imageBudgetEvent = MutableSharedFlow<ImageBudget.BudgetResult>(extraBufferCapacity = 4)
    val imageBudgetEvent: SharedFlow<ImageBudget.BudgetResult> = _imageBudgetEvent.asSharedFlow()

    /**
     * Request-level image-budget events (T-request-imgsize). Emitted by
     * [applyRequestImageBudget] when the cumulative history image payload
     * exceeds [ImageBudget.MAX_REQUEST_BYTES] and older images had to be
     * elided to text placeholders. Distinct from [imageBudgetEvent] so the
     * UI Snackbar can show a different message ("older images compacted")
     * and the two events don't race.
     */
    internal val _requestBudgetEvent = MutableSharedFlow<ImageBudget.RequestBudgetPlan>(extraBufferCapacity = 4)
    val requestBudgetEvent: SharedFlow<ImageBudget.RequestBudgetPlan> = _requestBudgetEvent.asSharedFlow()

    /**
     * [T-android-tool-autoscroll] Fire-and-forget edge events that ask the
     * ChatScreen to scroll the LazyColumn to the visual bottom (index 0 under
     * reverseLayout). Distinct from the streaming-auto-follow collector — that
     * pipeline needs growth ticks to advance its distinctUntilChanged tuple,
     * but agent-loop START events (sendMessage, resume / "Continue", retry)
     * produce only a brief thinking placeholder before any content streams.
     * Without an explicit edge signal, the placeholder + composer interaction
     * area sits behind the input bar until the model's first token arrives
     * and the regular auto-follow finally fires. Each ViewModel entry that
     * starts a fresh agent-loop turn emits to this flow.
     */
    internal val _forceScrollToBottom = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val forceScrollToBottom: SharedFlow<Unit> = _forceScrollToBottom.asSharedFlow()

    /**
     * [T-android-readaloud-stop-stale] Emitted on the FIRST text delta of a new
     * reply, so any Read Aloud still playing from the previous reply is
     * stopped before the new content starts arriving.
     *
     * Deferred to the first delta rather than fired from send(): the old reply
     * should keep playing while the model is still thinking, and only yield
     * once there is actually new text to supersede it. Mirrors iOS
     * `d2fdc784f`, which sets `hasStoppedPreviousTTS` at the same point.
     *
     * The player is screen-scoped (ChatScreen owns it), so this is a signal
     * rather than a direct call — the ViewModel has no reference to it.
     */
    internal val _stopStaleReadAloud = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val stopStaleReadAloud: SharedFlow<Unit> = _stopStaleReadAloud.asSharedFlow()

    internal val _availableGroups = MutableStateFlow<List<ModelGroup>>(emptyList())
    val availableGroups: StateFlow<List<ModelGroup>> = _availableGroups.asStateFlow()

    internal val _selectedGroupId = MutableStateFlow<String?>(null)
    val selectedGroupId: StateFlow<String?> = _selectedGroupId.asStateFlow()

    internal val _selectedGroupName = MutableStateFlow("")
    val selectedGroupName: StateFlow<String> = _selectedGroupName.asStateFlow()

    internal val _providerName = MutableStateFlow("")
    val providerName: StateFlow<String> = _providerName.asStateFlow()

    /** Incremented when a model fallback occurs — UI observes this to flash the model capsule. */
    internal val _fallbackTrigger = MutableStateFlow(0)
    val fallbackTrigger: StateFlow<Int> = _fallbackTrigger.asStateFlow()

    internal val _activeEntryId = MutableStateFlow<String?>(null)
    override val activeEntryId: StateFlow<String?> = _activeEntryId.asStateFlow()

    /**
     * Provider instance the current turn bills against. Persona resolution is
     * per-instance ([com.openminis.app.agent.PersonaPromptLibrary.resolve]), so
     * anything that wants to show or verify "the persona this session actually
     * uses" must go through the same id the injector uses.
     */
    internal fun activeProviderInstanceId(): String? =
        _activeEntryId.value?.let { id ->
            providerRepository.config.value.modelEntries.find { it.id == id }?.providerInstanceId
        }

    internal fun activeProviderInstanceLabel(): String? =
        activeProviderInstanceId()?.let { iid ->
            providerRepository.config.value.instances.find { it.id == iid }?.label
        }

    /** Prompts enqueued while the agent loop is running. Drained after the loop finishes. */
    internal val _promptQueue = MutableStateFlow<List<QueuedPrompt>>(emptyList())
    val promptQueue: StateFlow<List<QueuedPrompt>> = _promptQueue.asStateFlow()

    /**
     * Input-token count reported by the most recent API call, used by
     * [ContextPolicy] as the "estimated tokens" gate before sending. Zero
     * means either we've never called the model or the provider didn't return
     * a usage payload — in which case we treat the turn as low-pressure.
     */
    internal val _lastTurnContextTokens = MutableStateFlow(0)
    val lastTurnContextTokens: StateFlow<Int> = _lastTurnContextTokens.asStateFlow()

    /** [T-cache-hit-rate] Per-turn cache hit rate from the most recent API call (0..1). */
    internal val _lastCacheHitRate = MutableStateFlow(0.0)
    val lastCacheHitRate: StateFlow<Double> = _lastCacheHitRate.asStateFlow()

    /**
     * [T-compact-estimate-fallback] Cheap token estimate of the in-memory
     * agent history, used ONLY as a lower-bound floor when the last usage
     * chunk is missing or stale (`_lastTurnContextTokens` is refreshed by a
     * COMPLETED API call, so a tool-heavy stretch that has not produced a
     * new usage value yet would otherwise look "empty" to ContextPolicy and
     * skip compaction entirely). ASCII costs ~0.25 tok/char, CJK ~0.6 —
     * the classic len/4 estimator is ~4x off for CJK and would let long
     * Chinese sessions sail past the threshold.
     */
    internal fun estimateAgentHistoryTokens(): Int {
        var total = 0L
        // Effective, not raw: with a compact marker in play the next request
        // carries the summary + kept tail, not the full history. Feeding the
        // raw size into the send-time / in-loop gates made every post-compact
        // check look over-threshold.
        for (msg in effectiveAgentHistoryUncounted()) {
            total += estimateMixedTokens(msg.content)
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.Text -> total += estimateMixedTokens(part.text)
                    is AgentContentPart.ToolUse -> total += estimateMixedTokens(part.input.toString())
                    is AgentContentPart.ToolResult -> total += estimateMixedTokens(part.content)
                    is AgentContentPart.ImageData -> total += 1_500
                }
            }
        }
        // [T-token-usage-calibration] 按模型维度用真实 usage 校准原始估算。
        val modelKey = currentModel?.id ?: ""
        return if (modelKey.isNotEmpty()) {
            com.openminis.app.data.TokenUsageCalibration.estimate(modelKey, total.toInt())
        } else {
            total.toInt()
        }
    }

    private fun estimateMixedTokens(text: String): Long {
        if (text.isEmpty()) return 0
        var ascii = 0
        var other = 0
        for (c in text) if (c.code < 128) ascii++ else other++
        return ascii / 4L + other * 3L / 5L
    }

    /** max(real usage, estimate) so a stale-but-real reading always wins. */
    internal fun contextTokensForPolicy(): Int =
        maxOf(_lastTurnContextTokens.value, estimateAgentHistoryTokens())

    /**
     * Latest compact summary for the current session, loaded from the DB on
     * [loadSession] and re-populated after [compactAll] finishes. When non-null,
     * [effectiveAgentHistory] injects it as a `<context-summary>` part on the
     * first surviving user turn (v1 markers use a standalone summary message)
     * so the model sees a condensed recap of the turns we folded away while
     * keeping the full [agentHistory] on disk as an audit trail. Mirrors iOS
     * Phase-B compact semantics (summary synthesized at inference time, never
     * baked back into agentHistory).
     */
    internal val _compactSummary = MutableStateFlow<String?>(null)
    override val compactSummary: StateFlow<String?> = _compactSummary.asStateFlow()

    /**
     * Set each [buildSystemPrompt] from whether a personality body was
     * injected. [effectiveAgentHistory] then prefixes the latest user-text
     * turn so existing sessions don't keep the previous generic voice.
     */
    @Volatile
    internal var personaHistorySteering = false

    /**
     * Byte offset at which the last [buildSystemPrompt] result switches from
     * byte-stable (base, skills, MCP, global + daily memory) to per-turn
     * dynamic (WorldBook hits, learned prefs, recall, runtime context).
     * AnthropicProvider places its system cache_control breakpoint exactly here
     * so the stable head is a cross-turn cache hit; -1 means "unknown, cache
     * the whole system prompt as before".
     */
    @Volatile internal var systemPromptStablePrefixLen: Int = -1

    /** True when a compact-summary LLM call is in flight (UI disables further sends). */
    internal val _isCompacting = MutableStateFlow(false)
    override val isCompacting: StateFlow<Boolean> = _isCompacting.asStateFlow()

    /**
     * [T-android-compact-progress] Live progress of the in-flight compaction.
     *
     * Compaction could previously run for many minutes behind a single
     * unchanging "compacting" flag, which is indistinguishable from a hang —
     * the reported symptom was users staring at it for 15-20 minutes with no
     * way to tell whether it was working or wedged. This carries enough state
     * for the UI to show real movement: elapsed seconds, which segment of a
     * split is running, and how deep the split went.
     */
    data class CompactProgress(
        /** When the whole compaction started, for elapsed-time display. */
        val startedAtMs: Long,
        /** Recursion depth currently executing (0 = whole history, >0 = a split half). */
        val depth: Int = 0,
        /** Leaf LLM calls issued so far, across all segments. */
        val callsIssued: Int = 0,
        /** Total leaf calls allowed before the budget aborts the run. */
        val callBudget: Int = MAX_COMPACT_LLM_CALLS,
        /** Seconds the whole run is allowed before it is cancelled. */
        val timeoutSeconds: Int = 0,
        /** 1-based stage in the two-shot compact plan. */
        val stage: Int = 1,
        val stageBudget: Int = 2,
        /** session / fallback / session-retry */
        val stageKind: String = "session",
        val modelLabel: String = "",
        /** What happens if this stage fails. */
        val nextHint: String = "",
    )

    internal val _compactProgress = MutableStateFlow<CompactProgress?>(null)
    val compactProgress: StateFlow<CompactProgress?> = _compactProgress.asStateFlow()

    /**
     * Leaf LLM calls issued by the current compaction. Reset at the start of
     * each run; read/incremented from the split recursion, which can interleave
     * across suspension points, hence atomic.
     */
    internal val compactCallsIssued = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * The running compaction's job, so the UI can offer a Cancel affordance.
     * Cancelling routes through the same `finally` that clears the lock, so a
     * user-cancelled compaction leaves no state behind.
     */
    internal var compactJob: Job? = null

    /** Cancel an in-flight compaction. No-op when nothing is running. */
    fun cancelCompact() {
        val job = compactJob ?: return
        if (!job.isActive) return
        AppLogger.info(TAG, "[Compact] cancelled by user")
        job.cancel(CancellationException("compact cancelled by user"))
    }

    /** Current auto-retry attempt number (0 = not retrying, 1..MAX = nth retry in flight). */
    internal val _autoRetryAttempt = MutableStateFlow(0)
    val autoRetryAttempt: StateFlow<Int> = _autoRetryAttempt.asStateFlow()

    /** Seconds remaining in the current auto-retry countdown (0 = not counting down). */
    internal val _autoRetryCountdown = MutableStateFlow(0)
    val autoRetryCountdown: StateFlow<Int> = _autoRetryCountdown.asStateFlow()

    // [T-android-stale-streamjob-clears-isstreaming] @Volatile so cross-coroutine
    // reads (the orphaned previous streamJob's tail block running on a different
    // dispatcher) see the latest assignment. Without it, an old job's
    // `if (streamJob === thisJob)` guard could read a cached reference and
    // wrongly reset _isStreaming on the new live job — the exact race XIN hit
    // 2026-06-12 20:22:26 / 20:23:25 (cancel → resume → cancel → retry, where
    // the cancelled resume's finally fired ~2s after the new retry was already
    // streaming, hiding the Stop button while the new turn was live).
    @Volatile
    internal var streamJob: Job? = null

    /** A stop request is tied to the exact job, not to a later resumed turn. */
    @Volatile
    internal var stoppedStreamJob: Job? = null

    @Volatile
    internal var stoppedRun: com.openminis.app.service.ActiveRun? = null

    /**
     * [T-delete-from-here-inflight] The asynchronous truncation started by
     * `deleteFromMessage`. Published so a concurrent `sendMessage` can wait
     * for it: the send used to persist its user row while the truncation's
     * `deleteMessagesAfter(sort_order >= cutoff)` was still in flight, so the
     * brand-new row was deleted and the model answered a question that was no
     * longer in [agentHistory]. `@Volatile` for the same cross-coroutine
     * reason as [streamJob].
     */
    @Volatile
    internal var truncationJob: Job? = null

    internal var currentProvider: LLMProvider? = null
    internal var currentModel: LLMModel? = null

    /**
     * Does the CURRENTLY RESOLVED main model natively consume image pixels?
     *
     * Single source of truth for the three decisions that must agree: whether
     * `read_image` is exposed at all ([agentTools]), whether an attached image
     * is replaced by a Vision Group placeholder ([visionPlaceholderFor]), and
     * whether `read_image` returns pixels or routes through the Vision Group
     * ([executeReadImageTool]).
     *
     * [T-android-vision-native-check-misses-image_input] These three each used
     * to inline `inputModalities.map { it.lowercase() }.contains("image")`,
     * which only LOWERCASES. Provider APIs disagree on the spelling: OpenAI /
     * OpenRouter report "image_input", models.dev reports bare "image" (see
     * [normalizeModalityName]). So a model advertising "image_input" was read
     * as vision-capable by [hasImageInput] — which normalizes, and is what
     * `ProviderRepository.resolveVisionCandidates` filters Vision Group members
     * by — but as text-only here. A model that could see perfectly well would
     * therefore get its `read_image` result detoured through the Vision Group
     * and come back as a second-hand text description, exactly the complaint in
     * the 2026-08-28 report (which turned out to have a different cause).
     *
     * Reusing [hasImageInput] keeps this check and the Vision Group's member
     * filter on one definition, so the two can no longer disagree.
     */
    internal val currentModelHasNativeVision: Boolean
        get() = currentModel?.hasImageInput == true

    /**
     * [T-token-attribution-snapshot] Which model actually served the turn being
     * persisted, for the message's attribution columns.
     *
     * Built from `currentModel` + `_activeEntryId` — the live request context —
     * and deliberately NOT from the session row. Automatic failover rewrites
     * `sessions.model_id` mid-turn (see the fallback path that reassigns
     * `_activeEntryId` / `currentModel` when a candidate fails), so a session
     * read at persist time can name a model that never produced this message.
     * Both fields here are updated by that same fallback path, so they always
     * describe the model that actually responded.
     */
    internal fun currentModelSnapshot(): com.openminis.app.data.model.ModelAttributionSnapshot? {
        val model = currentModel ?: return null
        val entry = _activeEntryId.value?.let { id ->
            providerRepository.config.value.modelEntries.find { it.id == id }
        }
        val instance = entry?.let { providerRepository.instance(it.providerInstanceId) }
        return com.openminis.app.data.model.ModelAttributionSnapshot(
            modelId = model.id,
            displayName = model.displayName,
            // `.name` is the enum's stable rawValue, never the localized
            // displayName — grouping on display strings is what produced the
            // duplicate "Google" / "Gemini" / "Google Gemini" sections.
            providerTypeRaw = instance?.providerType?.name ?: "",
            providerInstanceId = entry?.providerInstanceId,
        )
    }

    /** Structured agent history for the agent loop (contentParts-based). */
    internal val agentHistory = mutableListOf<LLMMessage>()

    // [T-run-metrics-wiring]/[T-operation-wiring] 当前运行的指标实例与持久化操作 id（壳见 RunMetricsExt）。
    @Volatile internal var currentRunMetrics: com.openminis.app.harness.metrics.RunMetrics? = null
    @Volatile internal var currentOperationId: String? = null

    /**
     * All agent tool definitions, recomputed on each read so the memory
     * toggle gate (see [_memoryEnabled]) takes effect immediately when
     * the user flips /memory mid-session without forcing a VM rebuild.
     * The cost is negligible — [AgentTools.makeAgentTools] just builds a
     * fixed list of definition objects, no I/O.
     */
    // [T-android-agent-tools-memo] agentTools used to be a bare computed
    // property, rebuilding every tool definition + JSON schema + the plugin
    // registry scan on EVERY access (3 live access sites: per stream attempt,
    // per tool execution, per tool preflight — 21+ rebuilds in a 10-tool turn).
    // The memo below invalidates on the exact inputs that shape the list:
    // the four exposure flags plus the plugin store's change stamp.
    private var agentToolsMemo: List<AgentToolDefinition>? = null
    private var agentToolsMemoStamp: Long = Long.MIN_VALUE
    /** Long-tail tools made available by the current session's find_tools calls. */
    private val enabledLongTailTools = linkedSetOf<String>()
    private var enabledToolsSessionId: String = ""

    internal fun enableDiscoveredTools(names: Collection<String>) {
        synchronized(enabledLongTailTools) {
            if (enabledToolsSessionId != activeSessionId) {
                enabledLongTailTools.clear()
                enabledToolsSessionId = activeSessionId
            }
            enabledLongTailTools.addAll(names)
            agentToolsMemo = null
        }
    }

    internal fun findAndEnableTools(query: String, limit: Int): com.openminis.app.tools.ToolExecutionResult {
        val all = AgentTools.makeAgentTools(
            supportsImageInput = currentModelHasNativeVision,
            visionGroupConfigured = com.openminis.app.tools.VisionGroupResolver.isConfigured(providerRepository, context),
            memoryEnabled = _memoryEnabled.value,
            subAgentEnabled = multiAgentSettings.enabled.value,
            codeGraphEnabled = true,
            mcpNativeEnabled = mcpRepository != null,
            includeLongTail = true,
        ) + com.openminis.app.plugins.OnlinePluginStore.toolDefinitions(context) +
            com.openminis.app.plugins.LocalToolPluginStore.agentToolDefinitions(context)
        val matches = com.openminis.app.tools.FindTools.search(query, all, limit)
        enableDiscoveredTools(matches.map { it.definition.name })
        return com.openminis.app.tools.ToolExecutionResult(
            output = com.openminis.app.tools.FindTools.format(matches),
            success = matches.isNotEmpty(),
            toolTitle = "find_tools",
            errorCode = if (matches.isEmpty()) com.openminis.app.tools.ToolErrorCode.NOT_FOUND else null,
        )
    }

    internal val agentTools: List<AgentToolDefinition>
        get() {
            synchronized(enabledLongTailTools) {
                if (enabledToolsSessionId != activeSessionId) {
                    enabledLongTailTools.clear()
                    enabledToolsSessionId = activeSessionId
                    agentToolsMemo = null
                }
            }
            val enabledStamp = synchronized(enabledLongTailTools) { enabledLongTailTools.hashCode().toLong() }
            val stamp = (if (currentModelHasNativeVision) 1L else 0L) or
                (if (com.openminis.app.tools.VisionGroupResolver.isConfigured(
                        providerRepository, context,
                    )
                ) 2L else 0L) or
                (if (_memoryEnabled.value) 4L else 0L) or
                (if (multiAgentSettings.enabled.value) 8L else 0L) or
                (com.openminis.app.plugins.OnlinePluginStore.toolDefinitionsStamp(context) shl 4) xor
                (com.openminis.app.plugins.LocalToolPluginStore.toolsStamp(context) shl 20) xor
                enabledStamp
            agentToolsMemo?.takeIf { agentToolsMemoStamp == stamp }?.let { return it }
            val built = AgentTools.makeAgentTools(
                // [T-android-vision-group / GH#182] The main model's own vision
                // capability. When false but a Vision Group is configured, read_image
                // is still exposed and routes through the group (see
                // executeReadImageTool). Note pre-vision-group Android always passed
                // the default `true` here, so read_image was already always exposed;
                // threading the real flag lets a text-only model without a Vision
                // Group correctly LOSE the tool (iOS parity), while a configured
                // Vision Group keeps it.
                supportsImageInput = currentModelHasNativeVision,
                visionGroupConfigured = com.openminis.app.tools.VisionGroupResolver.isConfigured(
                    providerRepository, context,
                ),
                memoryEnabled = _memoryEnabled.value,
                subAgentEnabled = multiAgentSettings.enabled.value,
                codeGraphEnabled = true,
                mcpNativeEnabled = mcpRepository != null,
                includeLongTail = false,
                enabledToolNames = enabledLongTailTools,
            ) + com.openminis.app.plugins.OnlinePluginStore.toolDefinitions(context) +
                com.openminis.app.plugins.LocalToolPluginStore.agentToolDefinitions(context)
            agentToolsMemo = built
            agentToolsMemoStamp = stamp
            return built
        }

    /**
     * Per-session loop detector. Reset alongside [agentHistory] whenever the
     * conversation is rewound (edit/regenerate) so a stale tool-call window
     * can't bleed warnings into a fresh prompt.
     */
    internal val toolLoopDetector = ToolLoopDetector()

    internal val multiAgentSettings: MultiAgentSettingsRepository
        get() = (context.applicationContext as MinisApp).multiAgentSettingsRepository

    internal val subAgentRoundRobin = AtomicInteger(0)
    internal val subAgentDepth = AtomicInteger(0)

    /**
     * Cached reference to the lazily-created [BrowserTabPool] so
     * [ensureSession] can re-point it at the real session id after a rename.
     * Read only through [browserTabPool]; the backing getter fills this in.
     */
    @Volatile
    internal var _browserTabPoolRef: BrowserTabPool? = null

    /**
     * [T-w1-pool-ownership] 这份池子是不是**本 VM 自己建的**。
     *
     * `adoptBrowserTabPool` 借来的池子归父会话，本 VM 只是使用者：ChatViewModelStore
     * 淘汰 helper 子会话就是 `ViewModelStore.clear` → [onCleared]，那时若照着
     * [_browserTabPoolRef] 无条件 dispose，拆掉的是父会话正在用的池子（跨会话池销毁）。
     * 所以 onCleared / 清空会话只对**自建**的那份负责。
     */
    @Volatile
    private var _ownsBrowserTabPool: Boolean = false

    /**
     * [T-u12-merge] 上游 1.14 ChatViewModel.kt:2552：把一个**已存在**的标签池
     * 借给这个 ViewModel（辅助会话不自己建池，直接用父会话那一个——同一个
     * WebView 池，页面与登录态共享）。合并时这个成员整个丢了，而
     * ScheduledAgentRunner / ChatViewModel 里都还在调它。
     *
     * [T-w1-pool-ownership] 上游同一处（:2552-2554）只换绑定、不碰归属：借来的池子
     * 仍由创建者（父会话）负责拆，所以这里同时清掉自建标记，淘汰时就不会有人抢着拆
     * 父会话的页面。若本 VM 此前已经自建过一份，它从此不可达——那是上游也有的收尾
     * 缺口，但比「借入方反过来拆掉父会话正在看的池子」安全得多。
     */
    internal fun adoptBrowserTabPool(pool: BrowserTabPool) {
        _browserTabPoolRef = pool
        _ownsBrowserTabPool = false
    }

    /**
     * Browser tab pool for browser_use tool. Lazily created on first access.
     *
     * [T-w1-pool-read] 读取端先看 [_browserTabPoolRef]、没有才自建，对齐上游 1.14
     * ChatViewModel.kt:2533-2546。原来的 `by lazy` 是「谁先读谁建一份」：helper
     * 子会话建 VM 时明明已经 `adoptBrowserTabPool(父池)`，第一次读池子仍会另建一套
     * WebView 并把引用覆盖掉——父子看的不是同一批标签、登录态也不共享，接线在而语义
     * 不通（复核者点名的「编译级假绿」）。
     */
    val browserTabPool: BrowserTabPool
        get() = _browserTabPoolRef ?: synchronized(this) {
            _browserTabPoolRef ?: BrowserTabPool(context).also {
                it.setSession(activeSessionId)
                // Surface download start/finish/failure as system-info notices in
                // this chat. May fire from the pool's IO scope — hop to Main since
                // appendSystemInfo does a read-modify-write on _messages.
                it.onDownloadEvent = { text ->
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        appendSystemInfo(text, "info")
                    }
                }
                // [T-w1-pool-ownership] 先立归属、再发布引用：并发的 onCleared 只要
                // 看见引用非空，就必须同时看见「这份是自建池、归本 VM 拆」。
                _ownsBrowserTabPool = true
                _browserTabPoolRef = it
            }
        }

    internal val _showBrowserSheet = MutableStateFlow(false)
    val showBrowserSheet: StateFlow<Boolean> = _showBrowserSheet.asStateFlow()

    // [T-android-split-chat] toggleBrowserSheet / dismissBrowserSheet /
    // openBrowserSheetForUrl moved to ChatViewModelUiStateExt.kt.

    internal val _showMemorySheet = MutableStateFlow(false)
    val showMemorySheet: StateFlow<Boolean> = _showMemorySheet.asStateFlow()

    /** Set true by the slash-command "/clear" handler so ChatScreen can mirror
     *  it into the local Compose state that drives the existing
     *  showClearChatDialog confirmation. ChatScreen calls
     *  [ackClearChatConfirmRequest] after observing to reset back to false. */
    internal val _clearChatConfirmRequested = MutableStateFlow(false)
    val clearChatConfirmRequested: StateFlow<Boolean> = _clearChatConfirmRequested.asStateFlow()

    fun ackClearChatConfirmRequest() {
        _clearChatConfirmRequested.value = false
    }

    internal val _memoryToolRecords = MutableStateFlow<List<MemoryToolRecord>>(emptyList())
    val memoryToolRecords: StateFlow<List<MemoryToolRecord>> = _memoryToolRecords.asStateFlow()

    /**
     * Revoke a previously recorded memory_write by removing its entry from
     * today's or yesterday's daily log on disk, and dropping the row from
     * [memoryToolRecords] so the SessionMemorySheet reflects the removal.
     *
     * Returns the repository result so the UI can show a success / not-found
     * / I/O error dialog. The original ChatMessage tool block stays in the
     * conversation history untouched — only the on-disk entry and the
     * op-log row are mutated.
     */
    fun revokeMemoryRecord(record: MemoryToolRecord): com.openminis.app.data.repository.MemoryRepository.EntryMutationResult {
        val repo = sessionMemoryRepo()
        val written = record.writtenContent
            ?: return com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.NotFound
        val result = repo.revokeEntry(written)
        if (result is com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.Success) {
            _memoryToolRecords.value = _memoryToolRecords.value - record
        }
        return result
    }

    /**
     * T149: revoke every `memory_write` tool block embedded in the supplied
     * messages. Used when a retry path truncates the conversation — the
     * deleted assistant turns may have written entries to today's daily
     * memory log, and leaving them on disk after the conversation rewinds
     * means user-visible history is gone but the side effects remain.
     *
     * We match by the `content` field of the tool args against
     * [MemoryToolRecord.writtenContent] (which is what `revokeMemoryRecord`
     * keys on). If multiple records share the same content body — possible
     * if the agent wrote the same note twice — we revoke them in the
     * reverse insertion order so the most recent disk write is removed
     * first; the repository's revokeEntry only removes the first match
     * each call, so subsequent records may end up NotFound on disk but
     * still get pulled from the in-memory record list.
     */
    internal fun revokeMemoryWritesInDeletedMessages(deletedMessages: List<ChatMessage>) {
        val deletedContents = mutableListOf<String>()
        for (msg in deletedMessages) {
            for (block in msg.toolBlocks) {
                if (block.kind != "tool_use") continue
                if (block.toolName != "memory_write") continue
                val content = try {
                    JSONObject(block.toolArgs).optString("content", "")
                } catch (_: Exception) { "" }
                if (content.isNotBlank()) deletedContents.add(content)
            }
        }
        if (deletedContents.isEmpty()) return
        Log.i(TAG, "revokeMemoryWritesInDeletedMessages: ${deletedContents.size} write(s) to revoke")
        for (content in deletedContents.asReversed()) {
            // Find the latest matching record so revoke targets the most
            // recent disk entry first. Snapshot value because revoke mutates
            // the flow.
            val record = _memoryToolRecords.value.lastOrNull {
                it.isWrite && it.writtenContent == content
            } ?: continue
            val result = revokeMemoryRecord(record)
            Log.i(TAG, "  revoke result: ${result::class.simpleName}")
        }
    }

    /**
     * Replace the body of a previously recorded memory_write with
     * [newContent]. Mirrors iOS `MemoryWriteDetailView.replaceEntryInLog`.
     * On success, also updates the in-memory [MemoryToolRecord] so a
     * subsequent revoke or revisit sees the new body.
     */
    fun replaceMemoryRecord(
        record: MemoryToolRecord,
        newContent: String,
    ): com.openminis.app.data.repository.MemoryRepository.EntryMutationResult {
        val repo = sessionMemoryRepo()
        val old = record.writtenContent
            ?: return com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.NotFound
        val result = repo.replaceEntryBody(old, newContent)
        if (result is com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.Success) {
            _memoryToolRecords.value = _memoryToolRecords.value.map {
                if (it === record) it.copy(
                    writtenContent = newContent,
                    preview = newContent.lines().firstOrNull { line -> line.isNotBlank() }?.take(100) ?: "",
                ) else it
            }
        }
        return result
    }

    // ── Slash commands (mirrors iOS AIChatViewModel) ────────────────────

    // [T-memory-global-toggle-settings-ui-android] Seed from the global
    // pref so a fresh draft VM honors the user's "memory off by default"
    // choice from Settings. For loaded sessions, `loadSession()` later
    // overwrites this with the per-session DB value, which takes
    // precedence — the global pref only applies to drafts.
    internal val _memoryEnabled =
        MutableStateFlow(com.openminis.app.data.MemoryGlobalPrefs.isGlobalEnabled(context))
    val memoryEnabled: StateFlow<Boolean> = _memoryEnabled.asStateFlow()

    internal val _thinkingLevel = MutableStateFlow(ThinkingLevel.OFF)
    override val thinkingLevel: StateFlow<ThinkingLevel> = _thinkingLevel.asStateFlow()

    internal val _permissionMode = MutableStateFlow(com.openminis.app.security.PermissionMode.ASK)
    val permissionMode: StateFlow<com.openminis.app.security.PermissionMode> = _permissionMode.asStateFlow()

    /**
     * [T-android-enhanced-cache] Enhanced Cache (1-hour Anthropic cache TTL)
     * toggle. Per-VM memory state, NOT persisted — mirrors iOS
     * `AIChatViewModel.enhancedCacheEnabled`. When true, the active turn's
     * AnthropicProvider is stamped with `enhancedCache = true` just before the
     * request (see the streamMessage choke point).
     */
    internal val _enhancedCacheEnabled = MutableStateFlow(false)
    val enhancedCacheEnabled: StateFlow<Boolean> = _enhancedCacheEnabled.asStateFlow()

    /**
     * [T-android-enhanced-cache] Whether the Enhanced Cache menu item is shown.
     * Mirrors iOS `showEnhancedCacheToggle` (commit 57aaf122): only visible when
     * the current session's resolved provider instance is the *official*
     * Anthropic API (`providerType == anthropic` AND `customBaseURL` is
     * blank) — relays / other providers hide it because they don't honor the
     * 1-hour cache TTL. Recomputes whenever the active entry or provider config
     * changes so switching model/provider updates visibility instantly.
     */
    val showEnhancedCacheToggle: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(
            _activeEntryId,
            providerRepository.config,
        ) { entryId, config ->
            val entry = entryId?.let { id -> config.modelEntries.find { it.id == id } }
            val instance = entry?.let { e -> config.instances.find { it.id == e.providerInstanceId } }
            instance != null &&
                instance.providerType == com.openminis.app.data.model.ProviderType.anthropic &&
                instance.customBaseURL.isNullOrBlank()
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            false,
        )

    /** [T-android-enhanced-cache] True once the user accepted the one-time warning. */
    fun isEnhancedCacheConfirmed(): Boolean =
        com.openminis.app.data.EnhancedCachePrefs.isConfirmed(context)

    /**
     * [T-android-enhanced-cache] Enable Enhanced Cache after the confirmation
     * dialog was accepted (records the durable acknowledgement) and flips the
     * in-memory toggle on.
     */
    fun confirmAndEnableEnhancedCache() {
        com.openminis.app.data.EnhancedCachePrefs.setConfirmed(context)
        _enhancedCacheEnabled.value = true
    }

    /**
     * [T-android-enhanced-cache] Toggle the switch when confirmation is not
     * required (turning it OFF, or turning it ON after the user already
     * acknowledged). The confirmation-gated first enable is handled in the UI.
     */
    fun setEnhancedCacheEnabled(enabled: Boolean) {
        _enhancedCacheEnabled.value = enabled
    }

    /**
     * [T-codex-fast-mode] Fast Mode toggle state. APP-LEVEL and persisted
     * (FastModePrefs / iOS UserDefaults "codexFastModeEnabled") — unlike
     * Enhanced Cache it survives across sessions and process restarts; every
     * chat reads the same flag. The provider reads FastModePrefs directly at
     * request-build time, so this flow only drives the menu row + nav badge.
     */
    internal val _fastModeEnabled =
        MutableStateFlow(com.openminis.app.data.FastModePrefs.isEnabled())
    val fastModeEnabled: StateFlow<Boolean> = _fastModeEnabled.asStateFlow()

    fun setFastModeEnabled(enabled: Boolean) {
        com.openminis.app.data.FastModePrefs.setEnabled(context, enabled)
        _fastModeEnabled.value = enabled
    }

    /**
     * Auto-compact toggle state. APP-LEVEL and persisted
     * (AutoCompactPrefs / iOS UserDefaults "autoCompactOnThreshold").
     *
     * When on, crossing the compact threshold before a send compacts silently
     * and then sends; when off, the user is asked first. Mirrors iOS
     * `AIChatViewModel.autoCompactEnabled`.
     */
    internal val _autoCompactEnabled =
        MutableStateFlow(com.openminis.app.data.AutoCompactPrefs.isEnabled())
    private val _pendingUserQuestions =
        MutableStateFlow<List<com.openminis.app.tools.AskUserQuestion.Question>?>(null)
    val pendingUserQuestions: StateFlow<List<com.openminis.app.tools.AskUserQuestion.Question>?> =
        _pendingUserQuestions.asStateFlow()
    @Volatile private var askUserDeferred: kotlinx.coroutines.CompletableDeferred<String>? = null

    val pendingApprovals: StateFlow<Map<String, ApprovalGate.ApprovalRequest>> =
        ApprovalGate.pendingApprovals.map { all ->
            // [T-draft-approval-key-drift] ApprovalGate.pendingApprovals(sid)
            // snapshots its session key at PROPERTY-INIT time — for a draft
            // session that is the `__new__…` routing id, while requests are
            // filed under the PERSISTED activeSessionId once ensureSession
            // runs (ChatViewModelEnsureSession). The keyed flow then filters
            // to empty forever, the approval banner never renders, and every
            // tool call dies in ApprovalGate's 90s timeout ("不弹申请权限
            // 窗口，等待超时失败"). Derive from the GLOBAL flow instead and
            // re-filter with the CURRENT id on every emission, so the key
            // drift cannot strand a request.
            val sid = realSessionId.ifEmpty { sessionId }
            all.filterValues { it.sessionId == sid }
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            emptyMap(),
        )

    fun approvePendingTool(id: String) {
        // [T-draft-approval-key-drift] Resolve against the session the
        // REQUEST belongs to, not the current one: ApprovalGate's session
        // guard silently drops a mismatch, and a request filed during the
        // draft window predates the persisted id.
        val sid = ApprovalGate.detailOf(id)?.sessionId ?: realSessionId.ifEmpty { sessionId }
        val tapLanded = ApprovalGate.approve(id, sid)
        clearApprovalEntryIfLanded(id, tapLanded)
    }

    fun denyPendingTool(id: String) {
        val sid = ApprovalGate.detailOf(id)?.sessionId ?: realSessionId.ifEmpty { sessionId }
        val tapLanded = ApprovalGate.deny(id, sid)
        clearApprovalEntryIfLanded(id, tapLanded)
    }

    /**
     * [T-approval-tap-must-land] 站内横幅的清理判据与通知 receiver、前台服务同源
     * （[ApprovalNotifier.shouldClearEntryAfterTap]）：只有点击**真的落地**、或请求
     * 已经不在待审批表里（过期）才清通知条目。此前这里无条件
     * `cancelApproval` —— 一次被 gate 判为归属不符/并发输掉的点击会连通知一起
     * 消失，用户的选择被吞、请求挂到 90s 超时才以 "User REJECTED" 回给模型。
     */
    private fun clearApprovalEntryIfLanded(id: String, tapLanded: Boolean) {
        val stillPending = ApprovalGate.detailOf(id) != null
        if (ApprovalNotifier.shouldClearEntryAfterTap(tapLanded, stillPending)) {
            ApprovalNotifier.cancelApproval(context, id)
        } else {
            Log.w(
                TAG,
                "approval tap not applied while id=${id.take(8)}… is still pending; " +
                    "keeping the notification so the user can retry",
            )
        }
    }

    /**
     * Third action on the approval card: approve this request AND flip the
     * session-scoped allow-all switch, so later sensitive operations in this
     * conversation execute without asking again. The switch lives in
     * ApprovalGate and resets on session teardown (cleanupAll).
     *
     * [T-android-allow-all-means-all] The button says 全部允许, so it now
     * enables the true all-tools switch — not just the same tool name.
     * Previously shell got approved-all and file_write still prompted,
     * which contradicted the label the user tapped.
     */
    fun approveAllForSession(id: String) {
        val sid = ApprovalGate.detailOf(id)?.sessionId ?: realSessionId.ifEmpty { sessionId }
        ApprovalGate.enableSessionAllowAll(sid)
        approvePendingTool(id)
    }

    fun setSessionPermissionMode(mode: com.openminis.app.security.PermissionMode) {
        val next = if (mode.isYoyo()) com.openminis.app.security.PermissionMode.ALLOW_ALL
        else com.openminis.app.security.PermissionMode.ASK
        _permissionMode.value = next
        applyGateMode(next)
        val sid = realSessionId.ifEmpty { sessionId }
        if (!isDraft && sid.isNotBlank() && !sid.startsWith("__new__")) {
            viewModelScope.launch {
                val stored = if (next.isYoyo()) "YOYO" else "ASK"
                runCatching { chatRepository.dao.updatePermissionMode(sid, stored) }
            }
        }
    }

    internal fun applyGateMode(mode: com.openminis.app.security.PermissionMode) {
        val sid = realSessionId.ifEmpty { sessionId }
        SecurityGateHolder.setActiveSessionMode(mode, sid)
        // Preserve legacy behavior for older callers, but all gate state writes
        // above and below are explicitly keyed by this ViewModel's session.
        ApprovalGate.bindSession(sid)
        if (mode.isYoyo()) ApprovalGate.enableSessionAllowAll(sid)
        else ApprovalGate.resetSessionAllowAll(sid)
    }

    private fun activeOverrides(): com.openminis.app.data.model.ModelOverrides? {
        val id = _activeEntryId.value ?: return null
        return providerRepository.config.value.modelEntries.find { it.id == id }?.overrides
    }

    internal fun effectiveAutoCompact(): Boolean =
        activeOverrides()?.autoCompactEnabled
            ?: com.openminis.app.data.AutoCompactPrefs.isEnabled()

    internal fun effectiveCompactPercent(): Int =
        (activeOverrides()?.compactThresholdPercent
            ?: com.openminis.app.data.AutoCompactPrefs.thresholdPercent())
            .coerceIn(50, 95)

    internal fun effectiveMaxRetries(): Int =
        (activeOverrides()?.maxRetries
            ?: com.openminis.app.harness.agent.HttpRetryAfter.DEFAULT_MAX_RETRIES)
            .coerceIn(0, 8)

    fun submitUserQuestionAnswers(selections: List<List<String>>) {
        val questions = _pendingUserQuestions.value ?: return
        val json = com.openminis.app.tools.AskUserQuestion.formatAnswers(questions, selections)
        val d = askUserDeferred
        askUserDeferred = null
        _pendingUserQuestions.value = null
        d?.complete(json)
    }

    fun skipUserQuestions() {
        dismissPendingUserQuestions("skipped")
    }

    private fun dismissPendingUserQuestions(reason: String) {
        val d = askUserDeferred
        askUserDeferred = null
        _pendingUserQuestions.value = null
        d?.complete("{\"answers\":[],\"status\":\"$reason\"}")
    }

    internal suspend fun executeAskUserQuestion(argsJson: String): ToolExecutionResult {
        val params = try { org.json.JSONObject(argsJson) } catch (_: Exception) {
            return ToolExecutionResult("ask_user_question: invalid JSON", false)
        }
        val questions = com.openminis.app.tools.AskUserQuestion.parse(params)
        if (questions.isEmpty()) {
            return ToolExecutionResult("ask_user_question: no valid questions (need 2+ options each)", false)
        }
        val deferred = kotlinx.coroutines.CompletableDeferred<String>()
        askUserDeferred = deferred
        _pendingUserQuestions.value = questions
        return try {
            val answers = deferred.await()
            ToolExecutionResult(answers, true)
        } finally {
            if (askUserDeferred === deferred) {
                askUserDeferred = null
                _pendingUserQuestions.value = null
            }
        }
    }
    val autoCompactEnabled: StateFlow<Boolean> = _autoCompactEnabled.asStateFlow()

    fun setAutoCompactEnabled(enabled: Boolean) {
        com.openminis.app.data.AutoCompactPrefs.setEnabled(context, enabled)
        _autoCompactEnabled.value = enabled
    }

    internal fun groupChatPrefs() =
        context.getSharedPreferences("minis_group_chat", android.content.Context.MODE_PRIVATE)

    /**
     * Prefs must not read [realSessionId] while properties above it initialize.
     * That field is still a JVM null, and `ifEmpty` is inlined to `String.length()`.
     * That is the session-open crash. Promotion copies these keys and retargets
     * [groupChatPrefsId].
     */
    @Volatile
    internal var groupChatPrefsId: String = sessionId

    internal fun groupChatKey() = "enabled:$groupChatPrefsId"

    internal fun closedKey() = "closed:$groupChatPrefsId"

    internal fun roundStartKey() = "round:$groupChatPrefsId"

    private val _groupChatEnabled = MutableStateFlow(groupChatPrefs().getBoolean(groupChatKey(), false))
    val groupChatEnabled: StateFlow<Boolean> = _groupChatEnabled.asStateFlow()

    @Volatile
    internal var groupChatCloseRequested = false

    @Volatile
    internal var groupChatClosedAfterId: String? = groupChatPrefs().getString(closedKey(), null)

    /** Tail when this open began. Keeps an unfinished previous round out of the new one. */
    @Volatile
    internal var groupChatRoundStartId: String? = groupChatPrefs().getString(roundStartKey(), null)

    /** Set when group chat is opened before any message is loaded. */
    @Volatile
    internal var groupChatAnchorPending: Boolean = false

    internal fun migrateGroupChatPrefs(fromId: String, toId: String) {
        if (fromId == toId || fromId.isEmpty() || toId.isEmpty()) return
        val prefs = groupChatPrefs()
        val editor = prefs.edit()
        if (prefs.contains("enabled:$fromId")) {
            editor.putBoolean("enabled:$toId", prefs.getBoolean("enabled:$fromId", false))
            editor.remove("enabled:$fromId")
        }
        prefs.getString("closed:$fromId", null)?.let { closed ->
            editor.putString("closed:$toId", closed)
            editor.remove("closed:$fromId")
        }
        prefs.getString("round:$fromId", null)?.let { round ->
            editor.putString("round:$toId", round)
            editor.remove("round:$fromId")
        }
        prefs.getString("opening:$fromId", null)?.let { opening ->
            editor.putString("opening:$toId", opening)
            editor.remove("opening:$fromId")
        }
        editor.apply()
        groupChatPrefsId = toId
    }

    fun setGroupChatEnabled(enabled: Boolean) {
        val opening = enabled && !_groupChatEnabled.value
        groupChatPrefs().edit().putBoolean(groupChatKey(), enabled).apply()
        _groupChatEnabled.value = enabled
        if (!enabled) groupChatCloseRequested = false
        if (opening) groupChatAnchorPending = !anchorGroupRound()
        dismissMentionMenu()
    }

    /** Prefs can outlive the in-memory flag after a restart. Do not dismiss the @ menu. */
    internal fun adoptStoredGroupChatEnabled() {
        if (!_groupChatEnabled.value && groupChatPrefs().getBoolean(groupChatKey(), false)) {
            _groupChatEnabled.value = true
        }
    }

    /**
     * [T-codex-fast-mode] Whether the Fast Mode menu row (and, when enabled,
     * the nav ⚡ badge) is shown. Mirrors iOS activeModelSupportsFastMode
     * (838ba929): the active model id contains "gpt" (case-insensitive —
     * matches the official fast catalog gpt-5.6-sol/terra/luna, gpt-5.5,
     * gpt-5.4) AND the request travels the Responses path — the instance has
     * useResponsesAPI on (any credential/base; Responses relays like sub2api
     * pass the tier through) OR it's the Codex OAuth route (OpenAI type +
     * oauth credential + no custom base URL). Chat-completions providers stay
     * excluded. Recomputes on entry/config changes like the Enhanced Cache
     * gate above.
     *
     * [T-android-xai-priority] xAI is a second, independent branch: xAI's
     * Priority Processing is the same `service_tier: "priority"` wire field
     * and the same user-facing promise (lower latency, higher price), so it
     * reuses this one global toggle rather than adding a competing per-provider
     * switch. The "gpt" model-id test deliberately does NOT apply — xAI's
     * models are the grok family — and neither does the Responses-path test,
     * because xAI serves Priority Processing on its Chat Completions endpoint,
     * which is the path ProviderFactory always resolves xAI to.
     */
    val showFastModeToggle: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(
            _activeEntryId,
            providerRepository.config,
        ) { entryId, config ->
            val entry = entryId?.let { id -> config.modelEntries.find { it.id == id } }
            val instance = entry?.let { e -> config.instances.find { it.id == e.providerInstanceId } }
            val isCodexOAuth = instance != null &&
                instance.providerType == com.openminis.app.data.model.ProviderType.openAI &&
                instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth &&
                instance.customBaseURL.isNullOrBlank()
            val isXAI = instance?.providerType == com.openminis.app.data.model.ProviderType.xAI
            entry != null && instance != null &&
                (
                    isXAI ||
                        (
                            entry.model.id.contains("gpt", ignoreCase = true) &&
                                (instance.useResponsesAPI || isCodexOAuth)
                            )
                    )
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            false,
        )

    internal val _showSlashMenu = MutableStateFlow(false)
    val showSlashMenu: StateFlow<Boolean> = _showSlashMenu.asStateFlow()

    internal val _slashFilter = MutableStateFlow("")
    val slashFilter: StateFlow<String> = _slashFilter.asStateFlow()

    internal val _slashMenuSelectedIndex = MutableStateFlow(-1)
    val slashMenuSelectedIndex: StateFlow<Int> = _slashMenuSelectedIndex.asStateFlow()

    /**
     * [T-android-slash-menu-align-ios-prepend] The user's ORIGINAL composer
     * text, saved when the slash menu is opened via the "/" button over
     * existing content. Non-null ⇒ "over-content" mode; null ⇒ the menu was
     * opened by typing a leading "/" (the input itself is the slash query).
     *
     * Mirrors iOS `savedInputBeforeSlash`. On open we PREPEND "/ " to the
     * composer so it reads `/ <original>`; the user's subsequent typing edits
     * only the `/<filter>` token (see [updateSlashMenuState]), while
     * `<original>` is preserved here. Every exit path restores/uses this saved
     * original — never the live `/ <original>` string — so the injected "/ "
     * prefix is always stripped and the body text is never lost.
     *
     * This is the iOS-parity replacement for the earlier boolean marker. It
     * does NOT regress e48fe7a0 ("don't clear input"): the original body is
     * saved and faithfully restored on dismiss / prepended on skill select; it
     * is never discarded. The only behavioral change is that the body now sits
     * AFTER the slash token (iOS semantics) instead of being edited live.
     */
    internal var savedInputBeforeSlash: String? = null

    // ── @ file-mention picker (mirrors iOS AIChatViewModel mention*) ─────
    /**
     * Per-app singleton — scans this chat's workspace/attachments/memory plus
     * shared skills and /var/minis/shared on demand, ranks matches by basename
     * fuzzy score + scope priority. The composer hooks update*MentionMenu*
     * on every keystroke; the popup composes against [mentionEntries].
     */
    val fileMentionIndex: FileMentionIndex by lazy {
        // T219: provide the SAF-mounted external folders so `@<mountName>`
        // resolves to /var/minis/mounts/<name>/... in the chat composer.
        // PRootKernel holds the MountedFoldersStore reference (set at app
        // launch by MinisApp); reading via a closure means the index sees
        // an up-to-date snapshot on every rescan without a manual refresh.
        FileMentionIndex(
            filesDir = java.io.File(context.applicationContext.filesDir, "minis-global"),
            mountsProvider = {
                com.openminis.app.sandbox.PRootKernel
                    .mountEntriesForIndex(context.applicationContext)
            },
        )
    }

    internal val _showMentionMenu = MutableStateFlow(false)
    val showMentionMenu: StateFlow<Boolean> = _showMentionMenu.asStateFlow()

    internal val _mentionFilter = MutableStateFlow("")
    val mentionFilter: StateFlow<String> = _mentionFilter.asStateFlow()

    /** Caret index of the active `@` in [inputText], or -1 when no token is open. */
    internal val _mentionAnchor = MutableStateFlow(-1)

    /** Live-filtered candidate list. Combines the index's [FileMentionIndex.entries]
     * with [mentionFilter] so matches refresh as the user types and as the
     * background scan emits more entries. Capped at 50 like iOS. */
    val mentionEntries: StateFlow<List<FileMentionIndex.Entry>> = combine(
        fileMentionIndex.entries,
        _mentionFilter,
    ) { _, filter ->
        fileMentionIndex.matches(filter, limit = 50)
            .filterNot { it.linuxPath == "/var/minis/skills" || it.linuxPath.startsWith("/var/minis/skills/") }
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Group-chat @ candidates. File mentions stay available only when group
     * chat is off; an open group must not offer skill paths, because those
     * tokens do not address a speaker and every model would answer.
     */
    val mentionListsModels: StateFlow<Boolean> = combine(
        _groupChatEnabled,
        _mentionFilter,
        _activeEntryId,
        providerRepository.config,
        multiAgentSettings.selectedModelEntryIds,
    ) { enabled, filter, _, _, _ ->
        if (enabled) true
        else currentGroupMentions().any { !it.host } && !looksLikeFileMention(filter)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        _groupChatEnabled.value || currentGroupMentions().any { !it.host },
    )

    val groupMentions: StateFlow<List<GroupChat.MentionCandidate>> = combine(
        _groupChatEnabled,
        _activeEntryId,
        providerRepository.config,
        multiAgentSettings.selectedModelEntryIds,
        _mentionFilter,
    ) { enabled, _, _, _, filter ->
        val roster = currentGroupMentions()
        val show = enabled || (roster.any { !it.host } && !looksLikeFileMention(filter))
        if (!show) emptyList() else GroupChat.filterMentions(roster, filter)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        run {
            val roster = currentGroupMentions()
            val filter = _mentionFilter.value
            val show = _groupChatEnabled.value ||
                (roster.any { !it.host } && !looksLikeFileMention(filter))
            if (!show) emptyList() else GroupChat.filterMentions(roster, filter)
        },
    )

    val isMentionScanning: StateFlow<Boolean>
        get() = fileMentionIndex.isScanning

    /**
     * T-at-filepicker-keyboard: highlighted row in the @-mention picker. -1 when
     * the menu is closed or the filtered list is empty. Mirrors iOS
     * `mentionSelectedIndex` so a hardware-keyboard user can Up/Down through
     * candidates and hit Return to commit the highlighted entry. Touch users
     * still tap rows directly — the highlight just shows which row Return
     * would land on.
     */
    internal val _mentionSelectedIndex = MutableStateFlow(-1)
    val mentionSelectedIndex: StateFlow<Int> = _mentionSelectedIndex.asStateFlow()

    val currentModelSupportsReasoning: Boolean
        get() = currentModel?.supportsReasoning == true

    /**
     * [T-android-thinking-level-arch] The thinking ceiling the currently-bound
     * model actually supports. Prefers the active ModelEntry's
     * effectiveMaxThinkingLevel (so a user override on the entry is honored);
     * falls back to the resolved model's catalog default when no entry is
     * pinned (e.g. a group-resolved turn) or the model isn't known.
     */
    private val currentModelMaxThinkingLevel: ThinkingLevel
        get() {
            val entry = _activeEntryId.value?.let { id ->
                providerRepository.config.value.modelEntries.find { it.id == id }
            }
            if (entry != null) {
                return entry.effectiveMaxThinkingLevel
            }
            val model = currentModel ?: return ThinkingLevel.XHIGH
            return model.catalogMaxThinkingLevel
        }

    /**
     * [T-android-thinking-level-arch] Levels the chat composer picker should
     * offer: everything up to the current model's ceiling, EXCLUDING OFF —
     * mirrors iOS availableThinkingLevels (`filter { $0 != .off && $0 <= max }`).
     * There is no standalone "Off" capsule; tapping the already-selected level
     * toggles thinking off (see ThinkingLevelPicker). setThinkingLevel
     * additionally clamps as a belt-and-suspenders defense.
     */
    val availableThinkingLevels: List<ThinkingLevel>
        get() {
            val ceiling = currentModelMaxThinkingLevel
            return ThinkingLevel.entries.filter { it != ThinkingLevel.OFF && it.rank <= ceiling.rank }
        }

    // [T-anthropic-context-window] Token Usage sheet's context-window row.
    // Route through contextWindowTokens (heuristic-backed) so models without an
    // explicit contextWindow — e.g. heuristic-only Claude/Gemini — still report
    // their real 1M window instead of showing blank.
    val currentModelContextWindow: Int?
        get() = effectiveContextWindowTokens()

    /**
     * [T-context-window-live-read] Effective context window for capacity
     * judgment (compaction warnings, tool-output offload, empty-response
     * heuristic, Token Usage sheet). Reads LIVE state on every call instead of
     * the `currentModel` snapshot, so editing the model's context window or
     * the bound group's `contextLimitTokens` takes effect on the very next
     * judgment without re-picking the model/group (mirrors iOS fcc22b66):
     *   1. the active entry's model is re-resolved from the current repository
     *      config (folds ModelOverrides live), falling back to the snapshot
     *      only when the entry can't be found (e.g. synced sessions before
     *      config finished loading);
     *   2. the result is clamped by the bound group's `contextLimitTokens`
     *      (null / <=0 = unlimited). Pre-fix that group field was write-only
     *      on Android — persisted by the group editor but never consulted at
     *      runtime.
     */
    internal fun effectiveContextWindowTokens(): Int? {
        val config = providerRepository.config.value
        val liveModel = _activeEntryId.value
            ?.let { id -> config.modelEntries.find { it.id == id }?.model }
            ?: currentModel
        val window = liveModel?.contextWindowTokens ?: return null
        val groupLimit = _selectedGroupId.value
            ?.let { gid -> config.modelGroups.find { it.id == gid }?.contextLimitTokens }
            ?.takeIf { it > 0 }
        return if (groupLimit != null) minOf(window, groupLimit) else window
    }

    val currentModelMaxOutputTokens: Int?
        get() {
            val m = currentModel ?: return null
            m.maxOutputTokens?.takeIf { it > 0 }?.let { return it }
            return com.openminis.app.provider.ModelsDevApi.enrichModel(m).maxOutputTokens
                ?: com.openminis.app.data.model.inferredMaxOutputTokens(m.id, m.displayName)
        }

    // [T-context-ring] Live context token counter for the session-menu ring.
    val contextUsage = MutableStateFlow(com.openminis.app.data.model.ContextUsage(0L, 0L))

    /**
     * Recompute contextUsage for the session-menu ring.
     *
     * [T-android-context-ring-zero] The char estimate walks the effective
     * (compacted) history — with a marker in play the raw list is not what
     * the next request carries ([T-android-compact-stale-usage]).
     * which for a long session is TAIL-PAGED (loadSessionTail) — a re-entered
     * session showed ~0 tokens even while the provider reported 23939/24688.
     * Prefer the REAL provider-reported context size from the last usage
     * chunk; fall back to the char estimate only before the first API call.
     */
    fun refreshContextUsage() {
        val reported = _lastTurnContextTokens.value
        var usedChars = 0L
        for (msg in effectiveAgentHistoryUncounted()) {
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.Text -> usedChars += part.text.length
                    is AgentContentPart.ToolUse -> usedChars += part.input.toString().length
                    is AgentContentPart.ToolResult -> {
                        usedChars += part.content.length
                    }
                    is AgentContentPart.ImageData -> {}
                }
            }
        }
        val estTokens = (usedChars / 3.5).toLong()
        val used = if (reported > 0) reported.toLong() else estTokens
        val window = currentModelContextWindow?.toLong() ?: 0L
        contextUsage.value = com.openminis.app.data.model.ContextUsage(used, window)
    }

    // ── Session token usage (iOS parity: TokenUsageSheet data) ─────────────

    /**
     * Aggregated token usage for this session, computed from all persisted
     * `token_usage` JSON rows. Mirrors iOS [sessionTokenStats].
     *
     * @param context the most recent [LLMUsage.latestContextTokens] — reflects
     * how much of the model's context window was consumed at the last turn.
     * @param loopCount number of agent loop iterations (approximated by
     * max(tool_use blocks, assistant message count), matching iOS).
     */
    data class SessionTokenStats(
        val input: Long,
        val output: Long,
        val cacheRead: Long,
        val cacheWrite: Long,
        val context: Int,
        val loopCount: Int,
        /**
         * [T-android-token-usage-output-speed] 上游 1.14
         * `ChatViewModel.kt:3037-3045`，合并时丢了：流式输出实际用掉的墙钟毫秒，
         * 以及**同样这些轮次**产出的输出 token。两者是配对累加的——一轮要么两边
         * 都记、要么都不记，这样平均值不会被 `streamMs` 出现之前写下的历史行
         * （或者一轮没产出任何输出）拖向零。
         */
        val streamMs: Long = 0L,
        val streamOutput: Long = 0L,
    ) {
        /**
         * 平均输出速度（token/秒）。还没有任何可测数据时为 null（全新会话，或者
         * 所有行都早于 `streamMs`）。null 渲染成「—」而不是 "0.0 tok/s"——后者
         * 读起来像「模型卡住了」。
         */
        val outputTokensPerSecond: Double?
            get() = if (streamMs > 0 && streamOutput > 0) {
                streamOutput.toDouble() / (streamMs / 1000.0)
            } else {
                null
            }

        /**
         * [T-android-token-usage-cache-hit-rate] 上游 1.14
         * `ChatViewModel.kt:3060-3077`：本会话输入里命中缓存的比例（百分比）。
         * 分母与 sheet 的 "Input (incl. cache)" 行完全一致。
         *
         * 没什么可报的时候为 null，于是整行隐藏，而不是在不做缓存的 provider 上
         * 显示 0.0%——iOS 用的是同一个 `totalInput > 0 && cacheRead > 0` 条件。
         */
        val cacheHitRate: Double?
            get() {
                val totalInput = input + cacheRead + cacheWrite
                return if (totalInput > 0 && cacheRead > 0) {
                    cacheRead.toDouble() / totalInput.toDouble() * 100.0
                } else {
                    null
                }
            }
    }

    data class ThinkingInfo(
        val supported: Boolean,
        val enabled: Boolean,
        val level: String,
    )

    /** Read-only view of the current thinking configuration for the model. */
    fun thinkingInfo(): ThinkingInfo? {
        val model = currentModel ?: return null
        val supported = model.supportsReasoning == true
        val level = _thinkingLevel.value
        val enabled = supported && level.isEnabled
        val levelText = if (enabled) level.displayName else "—"
        return ThinkingInfo(supported, enabled, levelText)
    }

    /**
     * Load session-level token aggregates from the database. Suspend so the
     * Token Usage sheet can fetch on demand without keeping a live subscription
     * — token data rarely changes mid-view, and we want to avoid reactive
     * overhead per token chunk.
     */
    suspend fun loadSessionTokenStats(): SessionTokenStats {
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isEmpty()) return SessionTokenStats(0, 0, 0, 0, 0, 0)
        val usages = chatRepository.sessionTokenUsages(sid)
        var input = 0L
        var output = 0L
        var cacheRead = 0L
        var cacheWrite = 0L
        var context = 0
        var streamMs = 0L
        var streamOutput = 0L
        for (json in usages) {
            try {
                val obj = org.json.JSONObject(json)
                input += obj.optLong("inputTokens", 0L)
                val turnOutput = obj.optLong("outputTokens", 0L)
                output += turnOutput
                cacheRead += obj.optLong("cacheReadTokens", 0L)
                cacheWrite += obj.optLong("cacheCreationTokens", 0L)
                val ctx = obj.optInt("latestContextTokens", 0)
                if (ctx > 0) context = ctx
                // [T-android-token-usage-output-speed] 本功能之前写下的每一行、
                // 以及没产出输出的一轮，都没有这个键——只有两半都在时才计入耗时，
                // 否则平均值会被那些根本贡献不了速度的轮次拖向零。
                val turnMs = obj.optLong("streamMs", 0L)
                if (turnMs > 0 && turnOutput > 0) {
                    streamMs += turnMs
                    streamOutput += turnOutput
                }
            } catch (_: Exception) { /* skip malformed row */ }
        }
        val snapshot = _messages.value
        val assistantCount = snapshot.count { it.role == "assistant" }
        val toolCalls = snapshot.filter { it.role == "assistant" }
            .sumOf { msg -> msg.toolBlocks.count { it.kind != "text" && it.kind != "info" } }
        val loops = maxOf(toolCalls, assistantCount)
        return SessionTokenStats(input, output, cacheRead, cacheWrite, context, loops, streamMs, streamOutput)
    }

    // [T-android-split-chat] toggleMemorySheet / dismissMemorySheet moved to ChatViewModelUiStateExt.kt.

    // ── Slash command API (mirrors iOS AIChatViewModel) ─────────────────

    /** Static catalogue of available slash commands, in display order.
     *  Subtitles are placeholders here — [filteredSlashCommands] always
     *  rebuilds them with the current localized state. */
    internal val availableSlashCommands: List<SlashCommand> = listOf(
        SlashCommand(
            id = "clear",
            icon = Icons.Default.Delete,
            title = "Clear",
            subtitle = "",
        ),
        SlashCommand(
            id = "compact",
            icon = Icons.Default.Compress,
            title = "Compact",
            subtitle = "",
        ),
        SlashCommand(
            id = "memory",
            icon = Icons.Default.Psychology,
            title = "Memory",
            subtitle = "",
        ),
        SlashCommand(
            id = "thinking",
            icon = Icons.Default.Lightbulb,
            title = "Thinking",
            subtitle = "",
        ),
        SlashCommand(id = "goal", icon = Icons.Outlined.Build, title = "Goal", subtitle = ""),
    )

    // [T-android-split-chat] filteredSlashCommands / updateSlashMenuState /
    // showSlashMenuOverInput / dismissSlashMenu / slashMenuSetSelectedIndex moved
    // to ChatViewModelSlashExt.kt as ChatViewModel extension functions.

    // ── @ file-mention picker driver ──────────────────────────────────────
    // [T-android-split-chat] updateMentionMenuState / dismissMentionMenu /
    // mentionMenuUp / mentionMenuDown / executeSelectedMention / selectMention
    // moved to ChatViewModelMentionExt.kt as ChatViewModel extension functions.


    /** Toggle memory writes on/off, persist to DB, and append a system-info message. */
    internal fun toggleMemoryEnabled() {
        val newValue = !_memoryEnabled.value
        _memoryEnabled.value = newValue
        viewModelScope.launch {
            // Toggling before the first message means the row doesn't exist
            // yet — materialize the session row so the preference lands on
            // the persisted id instead of silently updating zero rows under
            // the draft key.
            val sid = ensureSession()
            chatRepository.dao.updateMemoryEnabled(sid, if (newValue) 1 else 0)
        }
        appendSystemInfo(
            text = context.getString(
                R.string.vm_memory_writes_toggled,
                if (newValue) {
                    context.getString(R.string.vm_state_enabled)
                } else {
                    context.getString(R.string.vm_state_disabled)
                },
            ),
            iconKind = "memory",
        )
    }

    /** Toggle thinking between OFF and MEDIUM (matches iOS default toggle semantics). */
    internal fun toggleThinking() {
        if (!currentModelSupportsReasoning) {
            appendSystemInfo(
                text = context.getString(R.string.vm_thinking_unsupported),
                iconKind = "thinking",
            )
            return
        }
        val newLevel = if (_thinkingLevel.value.isEnabled) ThinkingLevel.OFF else ThinkingLevel.MEDIUM
        _thinkingLevel.value = newLevel
        persistThinkingOverride(newLevel)
        appendSystemInfo(
            text = context.getString(R.string.vm_thinking_set_to, newLevel.displayName.lowercase()),
            iconKind = "thinking",
        )
    }

    /**
     * Set thinking level explicitly. Used by the inline level picker in the
     * `/thinking` slash row. Mirrors iOS `setThinkingLevel(_:)` — silently
     * ignored when the current model doesn't support reasoning.
     */
    override fun setThinkingLevel(level: ThinkingLevel) {
        if (!currentModelSupportsReasoning) return
        // [T-android-thinking-level-arch] Double-safety clamp: the composer UI
        // already filters to availableThinkingLevels, but never fully trust the
        // caller — cap to the current model's ceiling so a stale/over-range
        // request can't persist a level the model can't reach.
        val ceiling = currentModelMaxThinkingLevel
        val clamped = if (level.rank > ceiling.rank) ceiling else level
        if (_thinkingLevel.value == clamped) return
        _thinkingLevel.value = clamped
        persistThinkingOverride(clamped)
    }

    /**
     * T239: write the user's explicit thinking-level choice back to the
     * sessions row so it survives cold-start. Stored as enum name; null
     * means "no override" (legacy behaviour). We always store a non-null
     * value here — including OFF — because the user's explicit "turn it
     * off for this session" must persist as distinct from "never set".
     *
     * Uses [ensureSession] so toggling on a draft (no DB row yet) first
     * materialises the row, mirroring how toggleMemoryEnabled lands its
     * preference on the persisted id rather than the `__new__…` draft key.
     */
    private fun persistThinkingOverride(level: ThinkingLevel) {
        // [T-android-draft-no-row-on-open] Same rule as
        // `applyGroupSessionDefaults`: write only when a row exists, never
        // create one. Merely opening the thinking picker on a fresh chat and
        // choosing a level is not "start a conversation", and `ensureSession()`
        // here turned it into one.
        //
        // Mirrors iOS `setThinkingLevel`, whose doc comment states the rule
        // outright — "persists if session exists, otherwise holds in memory" —
        // and whose else-branch parks the value in `pendingThinkingLevel`.
        // Android's in-memory hold is `_thinkingLevel` itself, which the caller
        // has already set; `ensureSession()` flushes it on first send.
        // （上游 1.14 ChatViewModel.kt:3335-3352 原文。）
        val sid = realSessionId
        if (sid.isEmpty()) return
        viewModelScope.launch {
            chatRepository.dao.updateThinkingOverride(sid, level.name)
        }
    }

    /**
     * If `text` is a slash command literal (e.g. "/compact"), run it and
     * return true so the caller can skip the normal send path. Mirrors iOS
     * `tryExecuteInputAsSlashCommand()`. Recognized titles are matched
     * case-insensitively against [availableSlashCommands].
     *
     * Accepts both ASCII `/` and the full-width `／` (U+FF0F): some Chinese/
     * Japanese IMEs auto-substitute the full-width form when the user types
     * `/` while a CJK keyboard layout is active. We treat them identically.
     */
    fun tryExecuteInputAsSlashCommand(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val first = trimmed[0]
        if (first != '/' && first != '／') return false
        val body = trimmed.drop(1)
        if (body.equals("goal", ignoreCase = true) || body.startsWith("goal ", ignoreCase = true)) {
            viewModelScope.launch { executeGoalCommand(body.removeRange(0, 4).trim()) }
            return true
        }
        val name = body.lowercase()
        val cmd = availableSlashCommands.firstOrNull { it.title.lowercase() == name }
        if (cmd != null) {
            executeSlashCommand(cmd)
            return true
        }
        // [T-android-slash-literal-not-chat] A bare "/" or an unmatched
        // "/xyz" is a slash-panel interaction, not a chat message. Sending
        // it to the model made the assistant answer the literal string.
        // Swallow it with a local notice instead; the composer's slash
        // panel stays open so the user can pick a real command.
        appendSystemInfo(
            text = "未知命令: $trimmed（可用命令见 / 面板）",
            iconKind = "info",
        )
        return true
    }

    /**
     * Append a system-info block to the conversation. Not persisted — matches the
     * iOS `appendSystemInfo` behavior which surfaces a local notice in the chat
     * stream. Future work: wire real conversation compaction through the LLM.
     */
    internal fun appendSystemInfo(text: String, iconKind: String, payload: String? = null) {
        val block = AssistantBlock(
            id = "sysinfo_${System.currentTimeMillis()}",
            kind = "info",
            content = text,
            toolName = iconKind,
            // Reuse toolArgs as a freeform payload slot — for `iconKind="compact"`
            // this carries the full summary text so the UI can show an info-icon
            // affordance opening a detail sheet (mirrors iOS CompactSummarySheet).
            toolArgs = payload.orEmpty(),
        )
        _messages.value = trimLoadedWindow(_messages.value + ChatMessage(
            id = "sysinfo_${System.currentTimeMillis()}",
            role = "system",
            content = "",
            toolBlocks = listOf(block),
        ))
    }

    /** Replace an assistant bubble's visible text with a translation. Tool cards stay. */
    fun replaceAssistantOutput(messageId: String, translated: String) {
        val text = translated.trim()
        if (text.isEmpty()) return
        val cur = _messages.value
        val idx = cur.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        val msg = cur[idx]
        val lastTextId = msg.toolBlocks.lastOrNull { it.kind == "text" }?.id
        val newBlocks = if (lastTextId == null) {
            msg.toolBlocks + AssistantBlock(id = "tr-${msg.id}", kind = "text", content = text)
        } else {
            msg.toolBlocks.map { block ->
                if (block.kind != "text") block
                else if (block.id == lastTextId) block.copy(content = text)
                else block.copy(content = "")
            }
        }
        val updated = msg.copy(content = text, toolBlocks = newBlocks)
        _messages.value = cur.toMutableList().also { it[idx] = updated }
        val dbIds = msg.sourceDbIds
        if (dbIds.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val rows = dbIds.mapNotNull { id ->
                    // [T-translate-writebody] 读**真身**而不是展示投影：
                    // dao.messagePartsJson 是 substr(COALESCE(preview, parts_json),1,65536)，
                    // 对 offload 行只给 8KB 截断的预览，>8KB 正文的 JSONArray 必然
                    // 解析失败 → 译文根本没写进库（UI 上看着换了，重进会话就没了）。
                    val raw = chatRepository.loadRawPartsForRow(id) ?: return@mapNotNull null
                    if (runCatching { org.json.JSONArray(raw) }.isFailure) return@mapNotNull null
                    id to raw
                }
                for ((id, parts) in AssistantReplyText.rewriteStoredParts(rows, text)) {
                    // [T-translate-writebody] 必须走 repo 包装：它先按
                    // [T-archive-trimmed-parts] 归档旧版本，再 storeBody + 同步
                    // body_bytes/body_ref/body_sha/preview 四列。直连
                    // dao.updateMessageParts 会绕过记账——offload 行的 body_ref 仍指向
                    // 旧正文，下次水合（含模型上下文）会把译文整个回滚成原文。
                    chatRepository.updateMessageParts(id, parts)
                }
            }.onFailure {
                AppLogger.warning(TAG, "replaceAssistantOutput: 翻译回写失败: ${it.message}")
            }
        }
    }

    /** Replace one assistant text block. Other segments and tool cards stay. */
    fun replaceAssistantTextBlock(messageId: String, blockId: String, translated: String) {
        val text = translated.trim()
        if (text.isEmpty()) return
        val cur = _messages.value
        val idx = cur.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        val msg = cur[idx]
        val old = msg.toolBlocks.find { it.id == blockId }?.content
        val newBlocks = msg.toolBlocks.map { block ->
            if (block.id == blockId && block.kind == "text") block.copy(content = text, translatedContent = null) else block // [T-reply-translated-only] drop stale auto overlay so the swap shows THIS translation
        }
        val joined = newBlocks.filter { it.kind == "text" }.joinToString("\n\n") { it.content }.ifBlank { text }
        val updated = msg.copy(content = joined, toolBlocks = newBlocks)
        _messages.value = cur.toMutableList().also { it[idx] = updated }
        val dbIds = msg.sourceDbIds
        if (dbIds.isEmpty() || old == null) return
        val occurrence = AssistantReplyText.textOccurrence(msg.toolBlocks, blockId, old)
        if (occurrence < 0) return
        viewModelScope.launch {
            runCatching {
                val rows = dbIds.mapNotNull { id ->
                    // [T-translate-writebody] 同 replaceAssistantOutput：取真身，否则
                    // offload 行（>8KB 正文）的整段回写会被静默丢弃。
                    val raw = chatRepository.loadRawPartsForRow(id) ?: return@mapNotNull null
                    if (runCatching { org.json.JSONArray(raw) }.isFailure) return@mapNotNull null
                    id to raw
                }
                val updated = AssistantReplyText.replaceVisibleOccurrence(rows, old, text, occurrence)
                    ?: return@runCatching
                // [T-translate-writebody] 走 repo 包装（归档 + body 四列记账），
                // 直连 dao.updateMessageParts 会让译文在下次水合时被旧正文覆盖。
                chatRepository.updateMessageParts(updated.first, updated.second)
            }.onFailure {
                AppLogger.warning(TAG, "replaceAssistantTextBlock: 翻译回写失败: ${it.message}")
            }
        }
    }

    /**
     * Fold the current session history into a single summary stored in
     * `compact_markers`. Mirrors iOS `compactAll()` + Phase-B semantics:
     *
     *   1. Build a compact conversation transcript (role + parts preview).
     *   2. Call the **current provider's non-streaming `sendMessage`** with a
     *      hardcoded summarization system prompt that emphasises preserving
     *      paths/commands/IDs/decisions/errors/open tasks.
     *   3. Persist a `CompactMarkerEntity` via the DAO; publish via
     *      [_compactSummary] so [effectiveAgentHistory] starts injecting it.
     *   4. agentHistory itself is NOT truncated — the audit trail stays.
     *
     * Concurrency: gated by [_isCompacting] so the slash command can't
     * overlap with an in-flight streaming turn (`_isStreaming`) or another
     * compact. Runs on [Dispatchers.IO].
     */
    /**
     * Public entrypoint used by the debug RPC (`chat.session.compact`) to
     * trigger compaction without going through the ChatScreen slash-command
     * UI path. Mirrors what [executeSlashCommand]("compact") does — just
     * calls [compactAll]. RPC callers can then observe [isCompacting] flipping
     * back to false to know the run finished, and read [compactSummary] for
     * the resulting summary text.
     */
    override fun runCompactNow() {
        compactAll()
    }

    /**
     * Public entrypoint for "compact up through this message" (mirrors iOS
     * AIChatViewModel.compactBefore). The chat list's long-press menu and
     * the debug RPC `chat.compact.before` route through here.
     *
     * @param dbMessageId the DB message id to use as the new marker's
     *   anchor. agentHistory range to compact = `[prevAnchor+1, anchorIdx]`
     *   where anchorIdx is the agentHistory position of this id.
     * @param includesBoundary accepted for ABI compatibility with iOS, but
     *   in v2 the anchor IS the caller-supplied message regardless — the
     *   flag is logged and ignored. (iOS made the same simplification.)
     *
     * If the id can't be resolved to an agentHistory entry, this falls
     * back to compactAll() behaviour so the user's gesture isn't lost.
     */
    override fun compactBefore(dbMessageId: String, includesBoundary: Boolean) {
        AppLogger.info(
            TAG,
            "[Compact] compactBefore() id=${dbMessageId.take(8)} includesBoundary=$includesBoundary " +
                "(v2: includesBoundary ignored — caller-supplied id becomes the anchor)",
        )
        val history = agentHistory.toList()
        val idx = history.indexOfLast { it.dbMessageId == dbMessageId }
        if (idx < 0) {
            AppLogger.warning(
                TAG,
                "[Compact] compactBefore: id=${dbMessageId.take(8)} not in agentHistory — falling back to compactAll()",
            )
            compactAll(anchorIdxOverride = null)
            return
        }
        compactAll(anchorIdxOverride = idx)
    }


    /**
     * Revert the most recent compact on this session.
     *
     * Drops the latest CompactMarker (its summary is discarded), refreshes
     * [_cachedLatestMarker] / [_compactSummary] to whatever's left (or
     * null), and rebuilds the message list so the UI reflects the new (or
     * absent) divider. Effect by design:
     *   - If a previous (older) marker exists, divider snaps back to that
     *     marker's anchor; effectiveAgentHistory replays that summary.
     *   - If no previous marker exists, divider disappears, full history
     *     flows to the model again.
     *
     * Mirrors iOS `revertCompact()`. Refuses to run mid-stream.
     */
    override fun revertCompact() {
        if (_isStreaming.value) {
            appendSystemInfo("Cannot revert compact while a response is in progress.", "compact")
            return
        }
        if (_isCompacting.value) {
            appendSystemInfo("Cannot revert compact while compaction is in progress.", "compact")
            return
        }
        val current = _cachedLatestMarker ?: run {
            appendSystemInfo("Nothing to revert — no compact marker on this session.", "compact")
            return
        }
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            AppLogger.info(TAG, "[Compact] ━━━ REVERT ━━━ session=${sid.take(8)} markerId=${current.id.take(8)} v=${current.version}")
            val removed = runCatching { chatRepository.dao.deleteCompactMarker(current.id) }.getOrNull() ?: 0
            if (removed <= 0) {
                Log.w(TAG, "[Compact] revert: deleteCompactMarker returned 0 rows for id=${current.id.take(8)}")
                withContext(Dispatchers.Main) {
                    appendSystemInfo("Revert failed: marker not found in DB.", "compact")
                }
                return@launch
            }

            // Refresh cache to next-most-recent marker (or null).
            val next = chatRepository.dao.latestCompactMarker(sid)
            _cachedLatestMarker = next
            _compactSummary.value = next?.summary
            // [T-ctx-measure-outbound] The reload below is what re-measures the
            // restored history; only then can [CtxMeter] report what the revert
            // actually put back (upstream ChatViewModel.kt:6518-6521).
            pendingRevertLogMarker = current.id.take(8)

            // Rebuild UI from DB so the previous marker's divider re-emerges
            // (or all dividers vanish if there are no remaining markers).
            // Drop any stale compact-divider system rows first; the reload
            // path will re-insert one only if the new latest marker calls
            // for it.
            withContext(Dispatchers.Main) {
                _messages.value = _messages.value.filterNot { msg ->
                    msg.role == "system" &&
                        msg.toolBlocks.firstOrNull()?.toolName == "compact"
                }
            }

            // Reload session messages — the existing path runs Phase 2.5
            // graying via applyCompactMarkerGraying() with the new cached
            // marker, so divider position falls back to the previous one
            // (or disappears entirely). loadSession() launches its own
            // viewModelScope job, so call from the Main thread.
            withContext(Dispatchers.Main) {
                reloadSessionFromDb()
            }

            if (next != null) {
                AppLogger.info(TAG, "[Compact] revert DONE: now showing previous marker id=${next.id.take(8)} v=${next.version}")
            } else {
                AppLogger.info(TAG, "[Compact] revert DONE: no remaining markers, full history active")
            }
        }
    }

    /**
     * Re-load the current session's UI message list from disk so any
     * cached-marker change (revert) gets re-applied through Phase-2.5-
     * style restore. Defers to the existing [loadSession] entry; that
     * function reads `_cachedLatestMarker` we just refreshed and routes
     * through [applyCompactMarkerGraying] to (re)position the divider.
     */
    private fun reloadSessionFromDb() {
        if (realSessionId.isEmpty() && sessionId.isEmpty()) return
        loadSession()
    }

    /**
     * Produce the LLM-facing view of agentHistory. Mirrors iOS
     * `effectiveAgentHistory` (AIChatViewModel.swift:3843-3876):
     *
     *   1) No marker / no summary → full agentHistory (zero-copy).
     *   2) Marker has a `firstKeptMessageId` (compactBefore at boundary) →
     *      `[summary] + agentHistory[boundaryIdx ...]`. The boundary message
     *      itself is the first kept entry.
     *   3) compactAll marker (`firstKeptMessageId = null`) → only summary +
     *      messages persisted AFTER the marker, located by
     *      `lastCompactedMessageId`. Messages inserted post-compact (the
     *      user's follow-up turn + the assistant's response) survive; the
     *      summary stands in for everything older.
     *   4) Marker present but no boundary resolvable in current history (e.g.
     *      the boundary message was deleted) → fall through to full history,
     *      same safety net iOS uses.
     *
     * Critically, we do NOT include `agentHistory[< boundaryIdx]` for case
     * (2/3) — that's how the model context stays clean after compact.
     * Earlier behaviour was [summary] + entire agentHistory, which both
     * over-stuffed the context AND duplicated tool_use/tool_result pairs the
     * marker had already replaced; that's what made follow-up turns appear
     * to lose continuity (the model got confused by the dual representation).
     */

    /**
     * [T-android-compact-orphan-toolcall] The outgoing history, with tool
     * call/result pairing repaired. Every request goes through here — see
     * [dropOrphanedToolParts] for why the sweep exists and what it can and
     * cannot fix.
     */
    internal fun effectiveAgentHistory(): List<LLMMessage> {
        val repaired = applyContextWindowPolicy(
            dropOrphanedToolParts(effectiveAgentHistoryUncounted()),
            contextWindowBudgetTokens(),
        )
        val steered = if (personaHistorySteering) {
            com.openminis.app.agent.PersonaPromptLogic.applyHistorySteering(repaired)
        } else {
            repaired
        }
        // Rows trimmed out of the model window stay as a clipped excerpt.
        // A compact summary, when present, still replaces the older prefix;
        // this digest covers what this process actually dropped.
        // [T-message-transformers] 发送前清洗 assistant 文本里的思考残留。
        return com.openminis.app.harness.context.HistoryDigest.inject(
            steered,
            com.openminis.app.harness.context.HistoryDigest.render(llmDigestLines, llmDigestOmitted),
        ).map { msg ->
            if (msg.role == com.openminis.app.data.model.LLMMessage.Role.ASSISTANT && msg.content.isNotBlank()) {
                msg.copy(content = com.openminis.app.harness.agent.MessageTransformerChain.apply(msg.content))
            } else msg
        }
    }


    // dropOrphanedToolParts lives in ChatViewModelHistoryExt.kt

    /** Latest in-memory compact marker, used by [effectiveAgentHistory] to
     * resolve boundaries the same way iOS `cachedLatestMarker` does. Refreshed
     * on every compactAll write and on session reload. */
    @Volatile
    internal var _cachedLatestMarker: com.openminis.app.data.db.CompactMarkerEntity? = null

    /**
     * [T-compact-detached-anchor] True when the latest v2 marker's anchor
     * message is no longer inside the bounded `agentHistory` window — the
     * session outgrew [MAX_AGENT_HISTORY_MESSAGES] (or the window moved past
     * the anchor). The summary text itself is still perfectly usable, so the
     * read side must degrade to "summary + recent tail" instead of dropping
     * the summary and sending the full history on every turn. Detected once
     * per session load so the condition is not re-probed (and re-logged) on
     * every single LLM call.
     */
    @Volatile
    internal var _compactMarkerDetached = false

    /**
     * Tail size handed to the model when a marker's anchor has been evicted:
     * summary + the newest [DETACHED_TAIL_MESSAGES] entries verbatim. Sized
     * to keep the "most recent user turns + their tool round-trips" warm
     * without re-introducing the full history the summary exists to replace.
     */
    internal val DETACHED_TAIL_MESSAGES = 60

    /**
     * Assemble the outgoing history for a DETACHED marker: the summary is
     * injected as a `<context-summary>` Text part on the first user message
     * of the tail (same role-alternation-safe technique the v2 branch uses),
     * followed by the tail verbatim. Mirrors
     * [effectiveAgentHistoryUncounted]'s v2 path minus the anchor-based
     * slice, which is the only part that requires a resolvable anchor.
     */
    internal fun detachedCompactHistory(summaryWrappedText: String): List<LLMMessage> =
        com.openminis.app.harness.agent.CompactHistoryProjector.detachedCompactHistory(
            history = agentHistory,
            summaryWrappedText = summaryWrappedText,
            tailSize = DETACHED_TAIL_MESSAGES,
        )


    internal suspend fun splitCompactHalves(
        messages: List<LLMMessage>,
        previousSummary: String?,
        depth: Int,
    ): String {
        val mid = messages.size / 2
        val firstHalf = messages.subList(0, mid).toList()
        val secondHalf = messages.subList(mid, messages.size).toList()
        val summary1 = generateCompactSummaryWithSplitting(firstHalf, previousSummary, depth + 1)
        val summary2 = generateCompactSummaryWithSplitting(secondHalf, null, depth + 1)
        return summary1 + "\n\n" + summary2
    }


    internal data class CompactAttempt(
        val kind: String,
        val label: String,
        val provider: LLMProvider,
        val model: LLMModel,
        val nextHint: String,
        val temperature: Double? = null,
    ) {
        fun maxOutFor(userMessage: String): Int {
            val estimatedInput = userMessage.length / 4
            val window = model.contextWindow?.takeIf { it > 0 } ?: 128_000
            return maxOf(1024, minOf(8192, window - estimatedInput))
        }
    }

    @Volatile
    internal var compactLeafAttempt: CompactAttempt? = null

    /** Two-shot compact: one leaf call per stage, no in-stage split fan-out. */
    @Volatile
    internal var compactAllowSplit = false



    /**
     * Should a failed summary attempt be retried by splitting the input in half?
     *
     * Ported from iOS `isSegmentRetryableError`
     * (AIChatViewModel+Compaction.swift:1010, T-compact-segment-retry-any-error).
     *
     * Everything EXCEPT the two cases where a smaller request cannot help:
     *   - cancellation — the user (or a session switch) stopped the work, so a
     *     retry would fight that and immediately throw again;
     *   - network/offline — the request never reached a model, so payload size
     *     is irrelevant and splitting just doubles the failed round-trips.
     *
     * This deliberately REPLACES [isContextTooLargeError] on the split path.
     * That substring allow-list tried to enumerate how every provider words an
     * over-length refusal and was provably incomplete — OpenMinis#133's
     * `[context_length_exceeded] Your input exceeds the context window of this
     * model` slipped past several variants — and every miss silently disabled
     * splitting, so compaction failed outright instead of retrying smaller.
     *
     * Splitting on an unclassified error is still the default, for that reason:
     * a summary built from halves beats no summary, so the burden of proof is
     * on NOT retrying.
     *
     * [T-android-compact-runaway] What changed is that "unclassified" no longer
     * means "everything". Splitting only helps when the failure was caused by
     * the SIZE of the request, and two error classes are known not to be:
     *
     *   - [LLMError.RateLimited] (429). The model is refusing on quota, not on
     *     length. Halving turns one rejected call into two rejected calls, each
     *     still subject to the provider's backoff — this is the exact shape
     *     that turned a single failure into ~15 sequential slow calls and the
     *     15-20 minute apparent hang users reported.
     *   - [LLMError.TransientError] (5xx / upstream). A server-side fault is
     *     independent of payload; retrying smaller just multiplies the outage.
     *
     * Both are better served by failing fast and letting the user retry the
     * whole compaction once conditions change.
     */
    internal fun isSegmentRetryableError(error: Throwable): Boolean =
        shouldSplitOnError(error)

    /**
     * Match provider error text against the substring set iOS
     * `isContextTooLargeError` used before T-compact-segment-retry-any-error.
     *
     * NO LONGER gates segment retry — [isSegmentRetryableError] does, for the
     * reasons documented there. Retained only for user-facing wording, where
     * guessing wrong costs a less specific message rather than a failed
     * compaction.
     */
    @Suppress("unused")
    private fun isContextTooLargeError(error: Throwable): Boolean {
        val desc = (error.message ?: error.toString()).lowercase()
        return desc.contains("too many tokens") ||
            desc.contains("context length") ||
            desc.contains("max_tokens") ||
            desc.contains("content is too long") ||
            desc.contains("exceeds the model") ||
            desc.contains("request too large") ||
            desc.contains("prompt is too long") ||
            desc.contains("token limit") ||
            desc.contains("context window")
    }

    // ── [T-ctx-overflow-attribute-dispatch] Which model did we actually ask? ──
    //
    // 上游实现：upstream-1.14 `ui/chat/ChatViewModel.kt:1447-1448`（状态）、
    // :1451-1459（recordContextDispatch）、:1490-1509（noteContextOverflow）、
    // :1525（seedContextCalibration），调用点 :11292 / :11662 / :11948 / :6504。
    // 合并时这一整套都丢了，本移植的评价口径只剩「当前选中模型的窗口 + 全局 EMA」，
    // 于是归因错位：group fallback / 中途换模型之后，真正收到请求的模型永远得不到
    // 校正，评分却落到了错的模型上。这里把上游那套「记录本次派发的模型与窗口、
    // 溢出时按它们归因、新会话清空记录」补回来。

    /**
     * System prompt + tool schemas, the part a history-only estimate omits.
     * internal：循环每一轮都会按**这一轮**的 prompt / 工具表重播它
     * （上游 1.14 ChatViewModel.kt:10912），而循环在本移植里是
     * `ChatViewModelAgentLoopExt.kt` 的扩展函数。
     */
    internal var contextFixedTokens = 0

    /** Estimate of the request the last dispatch sent; 0 = nothing dispatched. */
    private var lastDispatchEstimate = 0

    /** Ratio in force when that request was dispatched ([CtxMeter] dispatch log). */
    private var lastDispatchRatio = 1.0

    /** Its calibrated size — what `max_tokens` was sized from (upstream `lastDispatchPredicted`). */
    private var lastDispatchPredicted = 0

    /** The model the last request was SENT TO — not the current selection. */
    private var lastDispatchModel: com.openminis.app.data.model.LLMModel? = null

    /** That model's window at dispatch time; 0 when it could not be resolved. */
    private var lastDispatchWindow = 0

    /**
     * [T-ctx-measure-outbound] (estimate, fixed, model) of the last PAIRED
     * request — written into the turn's usage row so a reopened session
     * re-seeds the RATIO rather than a stale raw size (upstream
     * `ChatViewModel.kt:1389 lastCalibratedPair` / :1470).
     */
    private var lastCalibratedPair: Triple<Int, Int, String?>? = null

    /**
     * [T-ctx-measure-outbound] 最近一个样本算出的比值（见 [calibrateContextSize] /
     * [noteContextOverflow]）。会话重载时它与内存里的比值一起组成
     * [com.openminis.app.data.ContextSizeMeter.CalibrationState]，交给 carryingOver ——
     * 「重载同一个会话要保住内存里学到的比值」靠的就是它。上游同名字段：
     * `ChatViewModel.kt:1373 lastLearnedCalibration`。
     */
    private var lastLearnedCalibration: Double? = null

    /**
     * [T-ctx-measure-outbound] 内存里这套校准是**哪个会话**学到的（上游
     * `ChatViewModel.kt:1375 calibrationSessionId`）。只有同一个会话重载才把内存值带上；
     * 换会话从头开始 —— 否则上一个聊天的比值会被当成这个聊天的证据。
     */
    private var calibrationSessionId: String? = null

    /**
     * [T-ctx-measure-outbound] Marker id of a revert whose `[CtxMeter] reverted`
     * line must wait for the reload's measurement — the reload is what makes the
     * number meaningful (upstream `ChatViewModel.kt:1387`), and the reload runs
     * from `ChatViewModelLoadExt.kt`, hence internal.
     */
    internal var pendingRevertLogMarker: String? = null

    /**
     * [T-ctx-valve-rearm] 本次 loop 是否已经「按外推比值判定超窗」放行过一次。上游
     * `ChatViewModel.kt:1392`。internal：[inLoopContextCheck] 的判定体在本移植里是
     * `ChatViewModelInLoopContext.kt` 的扩展函数，类内 private 成员在那里不可达。
     */
    internal var sentPastExtrapolatedLimitThisLoop = false

    /**
     * [T-ctx-measure-outbound] 上一次 loop 内压缩没能把请求压小（比值/历史没有缩小）。
     * 压不动的压缩不再重试 —— 否则每次压缩都白花一次摘要调用。上游
     * `ChatViewModel.kt:1394`。internal：同 [sentPastExtrapolatedLimitThisLoop]。
     */
    internal var lastInLoopCompactionMadeNoProgress = false

    /**
     * The window a GIVEN model's request is judged against: its own
     * `contextWindowTokens`, capped by the selected group's `contextLimitTokens`.
     * Sibling of [effectiveContextWindowTokens] — that one always reads the
     * CURRENT selection, while overflow attribution needs the window of the
     * model that actually received the request.
     */
    private fun resolvedContextWindow(model: com.openminis.app.data.model.LLMModel?): Pair<Int, String>? {
        val liveModel = model ?: return null
        val window = liveModel.contextWindowTokens ?: return null
        val config = providerRepository.config.value
        val groupLimit = _selectedGroupId.value
            ?.let { gid -> config.modelGroups.find { it.id == gid }?.contextLimitTokens }
            ?.takeIf { it > 0 }
        return if (groupLimit != null) minOf(window, groupLimit) to "group" else window to "model"
    }

    /**
     * [T-ctx-measure-outbound] 即将发出的这个请求有多大 —— 每一次容量判断
     * （压缩、卸载、max_tokens）读的**唯一**一个数。上游 1.14
     * `ChatViewModel.kt:1396-1428`（`contextMeasurement` /
     * `measureOutboundContextTokens` / `logContextDecision`），合并时整族丢了：两处
     * 守卫还在读 `_lastTurnContextTokens`（上一轮 API 报回来的数），而压缩 / 卸载 /
     * 回退一改历史，那个数描述的就是一个**已经不存在的**上下文 —— 于是压缩之后的第一次
     * 判断又按压缩前的数字压一次（iOS 双胞胎上量到过：3 秒内两次压缩，日志里同一个
     * 66358）。
     *
     * 口径：`measured = ceil((估算(将要发出的历史) + 固定份额) × 比值)`，比值 = 上一份
     * 报告 ÷ 同一个请求的估算。两边描述的是同一个请求，所以压缩、卸载、回退、重启都
     * 不会让它失效。
     */
    internal data class ContextMeasurement(
        /** 将要发出的历史（不含 system prompt / 工具表）的估算。 */
        val history: Int,
        /** system prompt + 工具表这一份固定开销，由本会话的样本播下。 */
        val fixed: Int,
        val ratio: Double,
        /** 比值来自哪里（own / borrowed / forced / default），供日志复盘。 */
        val source: String,
        /** 校准之后的最终值：容量判断读的就是它。 */
        val measured: Int,
    )

    /**
     * [T-ctx-measure-outbound] 量一次**即将发出**的请求。上游
     * `ChatViewModel.kt:1410-1418`。internal：[checkContextBeforeSend] /
     * [inLoopContextCheck] 两处守卫在本移植里是拆出去的扩展函数，类内 private 不可达。
     */
    internal fun contextMeasurement(override: Double? = null): ContextMeasurement {
        val history = ContextSizeMeter.estimateTokens(effectiveAgentHistoryUncounted())
        val model = currentModel?.id
        // [T-ctx-measure-outbound] 比值与它的来源必须出自**同一处**（上游
        // `ChatViewModel.kt:1402-1415`：`contextCalibrationRatio(model)` +
        // `ContextSizeMeter.ratioSource(...)`）。这里此前是把来源另算一遍
        // （`TokenUsageCalibration.scale(model) != 1.0` → own），而比值的「借来的
        // 比值 + 余量」那一条只活在 [ContextSizeMeter.ratioFor] 里 —— 换个从没量过的
        // 模型时比值掉回 1.0，密度更高的 tokenizer 于是按 1.0 的份额被低估、直接撞上
        // provider 的天花板，而日志还会把它报成 borrowed。
        val known = com.openminis.app.data.TokenUsageCalibration.snapshot()
        val ratio = override ?: ContextSizeMeter.ratioFor(model, known, lastLearnedCalibration)
        val source = if (override != null) "forced"
            else ContextSizeMeter.ratioSource(model, known, lastLearnedCalibration)
        return ContextMeasurement(
            history = history,
            fixed = contextFixedTokens,
            ratio = ratio,
            source = source,
            measured = ContextSizeMeter.calibrated(history + contextFixedTokens, ratio),
        )
    }

    /** [T-ctx-measure-outbound] 只要那一个数时用它（上游 ChatViewModel.kt:1405）。 */
    internal fun measureOutboundContextTokens(ratio: Double? = null): Int = contextMeasurement(ratio).measured

    /** [T-ctx-measure-outbound] 一次容量判断的完整输入与输出，便于按日志复盘。 */
    internal fun logContextDecision(site: String, m: ContextMeasurement, threshold: Int, window: Int, result: String) {
        AppLogger.info(
            TAG,
            "[CtxMeter] decide site=$site model=${currentModel?.id ?: "?"} history=${m.history} fixed=${m.fixed} " +
                "ratio=${"%.3f".format(m.ratio)}(${m.source}) measured=${m.measured} threshold=$threshold " +
                "window=$window → $result",
        )
    }

    /**
     * [T-ctx-measure-outbound] 发送前的这次判断跑在循环量过本轮 system prompt 之前；
     * 少了这份固定份额，它判的就只剩历史 —— 同一个请求会被量到线下（上游
     * `ChatViewModel.kt:1430-1438` `ensureContextFixedTokens`）。循环在第一次请求之前
     * 会用精确值把它换掉（见 ChatViewModelAgentLoopExt 里 `contextFixedTokens =
     * ContextSizeMeter.estimateFixedTokens(...)`），所以这里只在**没播过**时兜底。
     *
     * 上游的调用点恰好两处，本移植一一对应：发送前的 [checkContextBeforeSend]
     * （ChatViewModelPreSendContext.kt）与压缩 / 回退之后的 [publishMeasuredContextUsage]。
     * internal：这两处一个在本类内、一个在扩展函数里。
     */
    internal fun ensureContextFixedTokens() {
        if (contextFixedTokens > 0) return
        contextFixedTokens = ContextSizeMeter.estimateFixedTokens(buildSystemPrompt(), agentTools)
    }

    /**
     * Record what this attempt is about to send: our estimate of the outbound
     * history, plus the model (and its window) that will receive it. Returns the
     * CALIBRATED size — that is what sizes `max_tokens`
     * ([com.openminis.app.data.ContextSizeMeter.calibrated]), not the raw
     * estimate: the provider's window is spent in the provider's own tokens.
     *
     * internal：循环每一轮都调用它（上游 1.14 ChatViewModel.kt:11292
     * `recordContextDispatch(outboundHistory, currentProvider.model)`），而循环在
     * 本移植里是 `ChatViewModelAgentLoopExt.kt` 的扩展函数。
     */
    internal fun recordContextDispatch(
        history: List<com.openminis.app.data.model.LLMMessage>,
        model: com.openminis.app.data.model.LLMModel? = currentModel,
    ): Int {
        lastDispatchModel = model
        lastDispatchWindow = resolvedContextWindow(model)?.first ?: 0
        lastDispatchEstimate = ContextSizeMeter.estimateTokens(history) + contextFixedTokens
        lastDispatchRatio = contextCalibrationRatio(model?.id)
        lastDispatchPredicted = ContextSizeMeter.calibrated(lastDispatchEstimate, lastDispatchRatio)
        return lastDispatchPredicted
    }

    /**
     * [T-ctx-measure-outbound] `[CtxMeter] dispatch` — the record taken above and
     * the `max_tokens` it bought. Upstream logs the same line inline right before
     * the provider call (ChatViewModel.kt:11293-11298); here the loop is an
     * extension function, so it calls back in.
     */
    internal fun logContextDispatch(predicted: Int, maxTokens: Int) {
        AppLogger.info(
            TAG,
            "[CtxMeter] dispatch model=${currentModel?.id ?: "?"} estimate=$lastDispatchEstimate " +
                "ratio=${"%.3f".format(lastDispatchRatio)} predicted=$predicted maxTokens=$maxTokens",
        )
    }

    /**
     * Fold the provider's own count for the request we just sent into this
     * model's ratio. Only meaningful against the SAME request the dispatch
     * recorded — otherwise the sample describes another payload.
     */
    internal fun calibrateContextSize(reportedTokens: Int): Boolean {
        if (lastDispatchEstimate <= 0 || reportedTokens <= 0) return false
        val model = lastDispatchModel?.id ?: currentModel?.id ?: return false
        // [T-ctx-measure-outbound] 顺手记下这份样本本身的比值：会话重载时它与内存里
        // 的每模型比值一起组成 CalibrationState，被 carryingOver 保住（上游
        // ChatViewModel.kt:1461-1482 的 lastLearnedCalibration）。
        val sample = ContextSizeMeter.calibrationRatio(reportedTokens, lastDispatchEstimate)
        sample?.let { lastLearnedCalibration = it }
        com.openminis.app.data.TokenUsageCalibration.observe(model, lastDispatchEstimate, reportedTokens)
        // [T-ctx-measure-outbound] 这一对 (report, estimate) 描述的是同一个请求，随本轮
        // usage 行落库（calibrationJsonFields），重载时按会话顺序回放 —— 一条行内样本
        // 是「当时那个上下文」的证据，而 lastLearnedCalibration 只是一条瞬时值。
        lastCalibratedPair = Triple(lastDispatchEstimate, contextFixedTokens, model)
        // [CtxMeter] actual — the prediction made at dispatch vs what the
        // provider counted; `err` is how wrong this request's decision could be.
        // （上游 ChatViewModel.kt:1471-1481 同一行日志。）
        if (sample != null) {
            val err = if (lastDispatchPredicted > 0) (lastDispatchPredicted - reportedTokens) * 100.0 / reportedTokens else 0.0
            AppLogger.info(
                TAG,
                "[CtxMeter] actual model=$model predicted=$lastDispatchPredicted reported=$reportedTokens " +
                    "err=${"%+.1f".format(err)}% estimate=$lastDispatchEstimate sample=${"%.3f".format(sample)} " +
                    "ratio=${"%.3f".format(lastDispatchRatio)}→${"%.3f".format(sample)}",
            )
        }
        return true
    }

    /**
     * A provider rejected the request as too long — ground truth that we
     * under-read it (a borrowed ratio after a model switch, a legacy session,
     * content the estimator under-reads). Raise THIS model's ratio so the retry
     * or the next send compacts instead of being rejected again. Never lowers.
     */
    private fun noteContextOverflow(detail: String) {
        val model = lastDispatchModel?.id ?: currentModel?.id
        val window = lastDispatchWindow.takeIf { lastDispatchModel != null && it > 0 }
            ?: effectiveContextWindowTokens() ?: 0
        val current = model?.let { com.openminis.app.data.TokenUsageCalibration.scale(it) } ?: 1.0
        val requested = com.openminis.app.data.ContextSizeMeter.requestedTokens(detail)
        val raised = com.openminis.app.data.ContextSizeMeter.ratioAfterOverflow(
            current, lastDispatchEstimate, requested, window,
        )
        if (model != null) com.openminis.app.data.TokenUsageCalibration.raise(model, raised)
        lastLearnedCalibration = raised
        // [T-ctx-valve-rearm] 比值现在落在 provider 自己给的数上，不再是一条外推，
        // 所以那次「按外推值放行一次」的额度已经花掉：再放行就等于把刚刚被拒的请求
        // 原样再发一遍。（上游 ChatViewModel.kt:1499-1502。）
        sentPastExtrapolatedLimitThisLoop = true
        AppLogger.warning(
            TAG,
            "[CtxMeter] rejected predicted=$lastDispatchEstimate — provider rejected the request as too long — calibration " +
                "model=${model ?: "?"} ${"%.2f".format(current)} -> ${"%.2f".format(raised)} " +
                "(estimated=$lastDispatchEstimate statedTokens=${requested ?: "none"} window=$window); " +
                "the next attempt will compact",
        )
    }

    /**
     * Re-derive the ratio from this session's persisted usage rows (the ones
     * carrying BOTH the provider's report and our estimate of the same request),
     * then forget the previous dispatch: a new session must never attribute its
     * first rejection to the model the PREVIOUS chat was talking to.
     */
    internal fun seedContextCalibration(usageJsons: List<String>): Boolean {
        val sid = realSessionId.ifEmpty { sessionId }
        // [T-ctx-measure-outbound] 重载**同一个**会话时，内存里学到的比值必须保住：
        // 一条被 provider 拒绝的请求不会在行里留下任何 (report, estimate) 对，所以那个
        // 被抬高的比值只活在内存里 —— 而回退走的正是 loadSession，把它抹掉会让重试
        // 原样发出、再被拒一次（iOS 双胞胎上量到过：1.29 → 1.97，回退重载把它重置回
        // 行里的 1.29，重试未压缩又被拒）。内存值永远不比行旧，所以逐键取胜；换会话
        // 从头开始。（上游 ChatViewModel.kt:1525-1555。）
        val learned = if (sid.isNotEmpty() && calibrationSessionId == sid) {
            com.openminis.app.data.ContextSizeMeter.CalibrationState(
                com.openminis.app.data.TokenUsageCalibration.snapshot(),
                lastLearnedCalibration,
                contextFixedTokens,
            )
        } else null
        // [T-ctx-ratio-smoothing] 行按会话顺序（旧 → 新）过与线上同一条 smoothed 规则
        // 回放，所以重载复现的是会话当时那个平滑过的比值，而不是最新一条原始样本。
        val seeded = ContextSizeMeter.replayCalibration(usageJsons.mapNotNull(ContextSizeMeter::calibrationSample))
        val state = learned?.let { seeded.carryingOver(it) } ?: seeded
        // 应用口径与合并前一致：逐模型只升不降（TokenUsageCalibration.raise 的契约，
        // 见它的 KDoc）。上游这里对**每 ViewModel** 的比值表做 clear + putAll；本移植
        // 的比值表是按模型 id 的全局表，clear 会顺手抹掉别的会话学到的密度，所以只把
        // state 里有的键抬上去、不删键 —— carryingOver 决定的「哪些键取内存值」照样生效。
        state.ratios.forEach { (m, r) -> if (m.isNotEmpty()) com.openminis.app.data.TokenUsageCalibration.raise(m, r) }
        lastLearnedCalibration = state.lastLearned
        if (state.fixedTokens > 0) contextFixedTokens = state.fixedTokens
        lastDispatchEstimate = 0
        lastDispatchModel = null
        lastDispatchWindow = 0
        lastDispatchPredicted = 0
        // [T-ctx-measure-outbound] 上一次的配对样本属于上一个会话，不能跟着走。
        lastCalibratedPair = null
        // [T-ctx-valve-spend-survives-retry] 那次「按外推值放行一次」的额度与比值一样
        // 属于这个会话的证据：换会话重新武装，同一会话（回退 / 重载）保留被拒后的花费。
        if (learned == null) sentPastExtrapolatedLimitThisLoop = false
        calibrationSessionId = sid
        AppLogger.info(
            TAG,
            "[CtxMeter] seed session=${sid.take(8)} samples=${seeded.samples} keptInMemory=${learned?.ratios?.size ?: 0} " +
                "ratios=${state.ratios.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${"%.3f".format(it.value)}" }}",
        )
        return seeded.samples > 0 || !learned?.ratios.isNullOrEmpty()
    }

    /**
     * Entry point for the agent loop: a provider rejected this request as too
     * long. Over-length refusals arrive both typed
     * ([com.openminis.app.data.model.LLMError.ContextLengthExceeded]) and as
     * untyped prose, so both are accepted — the burden of proof is on ignoring
     * one. 上游的调用点是 `noteContextOverflow(overflowErr?.detail ?: "")`
     * （ChatViewModel.kt:11948），且只认 typed 的那一支；本移植的循环只拿到
     * Throwable，所以把「拆出 detail」这一步收在这里，判据更宽而不是更窄。
     */
    internal fun noteProviderContextRejection(error: Throwable) {
        val typed = error as? com.openminis.app.data.model.LLMError.ContextLengthExceeded
        if (typed != null || isContextTooLargeError(error)) {
            noteContextOverflow(typed?.detail ?: error.message ?: "")
        }
    }

    // ── [T-ctx-warmup-fit] 压缩热身的裁剪 + 卸载的 warm-up 扫描计划 ──────────
    //
    // 上游 1.14 `ChatViewModel.kt:1376-1381`（状态）、:1568-1604
    // （trimWarmUpToFit）、:10064-10096（offloadWarmUpScanPlan），合并时整块丢了：
    //
    //  * 一次压缩会在锚点之前保留最近几轮用户消息当**热身**。这些轮次很大、或者
    //    模型的 tokenizer 比会话当时跑的那台更密时，摘要 + 热身仍然会越过压缩线；
    //    而之后每次压缩保留的都是同一段热身，于是会话在那个模型上永远继续不下去
    //    （真机上实测过：换到密度 1.8 倍的模型后，压缩完还占着 100% 窗口）。
    //  * 卸载扫描从锚点之后起步（见 ChatViewModelOffloadExt.offloadScanStartIndex），
    //    可热身那一段是**真的会发出去**的，于是它反而卸载不到——[offloadWarmUpScanPlan]
    //    把那一段的下标算出来交给调用点。

    /**
     * [T-ctx-warmup-fit] 每个压缩 marker：为了让请求塞得下而被丢掉的最前面的热身
     * 消息条数。只决定一次、之后复用，于是请求前缀跨回合保持稳定（见
     * [trimWarmUpToFit]）。每请求重新决定会让热窗口每来一条消息就滑一轮
     * （iOS 双胞胎上量到过），请求前缀每回合都变，prompt caching 直接失效。
     */
    private val warmUpDropByMarker = mutableMapOf<String, Int>()

    /**
     * [T-ctx-warmup-fit] 当前生效的上下文策略，以及它所依据的窗口；窗口未知时为
     * null。上游 1.14 `ChatViewModel.kt:1447-1459`。本移植没有上游那个无参
     * `resolvedContextWindow()`（这里的同名函数要传入被评判的模型），所以活窗口
     * 走 [effectiveContextWindowTokens]——本移植其他每一处容量判断读的也是它。
     */
    private fun currentContextPolicy(): Pair<ContextPolicy, Int>? {
        val window = effectiveContextWindowTokens() ?: return null
        return ContextPolicy.forContextWindow(window, effectiveCompactPercent()) to window
    }

    /**
     * [T-ctx-warmup-fit] 裁剪某次压缩的热身轮次，让请求落在压缩线之下。镜像 iOS
     * `trimWarmUpToFit`（上游 1.14 `ChatViewModel.kt:1568-1604`）。
     *
     * 只在超预算时才动手，所以一次正常压缩的请求与 prompt-cache 前缀原样不动。
     * 按「整轮用户**文本**消息」从最旧开始丢（tool_result 也是 USER 角色的消息，
     * 在调用与它的结果之间切开会把配对切断），并且全程用原始估算单位，好让它不会
     * 递归回测量本身。
     */
    private fun trimWarmUpToFit(warmUp: List<LLMMessage>, rest: List<LLMMessage>, summaryText: String): WarmUpTrim {
        if (warmUp.isEmpty()) return WarmUpTrim(warmUp, decided = true)
        // [T-ctx-warmup-trim-undecided] 没有 entry / 窗口未知 / 固定 token 还没
        // 播下：没有可用来判断的预算，热身原样穿过并标记 UNDECIDED，调用方不得
        // 把它写进按 marker 的缓存。
        val budget = warmUpBudget(
            window = effectiveContextWindowTokens(),
            compactThreshold = currentContextPolicy()?.first?.compactThreshold,
            ratio = contextCalibrationRatio(),
            fixedTokens = contextFixedTokens,
        ) ?: return WarmUpTrim(warmUp, decided = false)
        val restTokens = ContextSizeMeter.estimateTokens(rest) +
            ContextSizeMeter.estimateTokens(summaryText)
        if (ContextSizeMeter.estimateTokens(warmUp) + restTokens < budget) return WarmUpTrim(warmUp, decided = true)
        val drop = ContextSizeMeter.warmUpDrop(
            sizes = warmUp.map { ContextSizeMeter.estimateTokens(listOf(it)) },
            startsTurn = warmUp.map { m ->
                m.role == LLMMessage.Role.USER && m.contentParts.none { it is AgentContentPart.ToolResult }
            },
            restTokens = restTokens,
            budget = budget,
        )
        AppLogger.info(TAG, "[CtxMeter] warmup trimmed to fit: kept ${warmUp.size - drop}/${warmUp.size} message(s) (budget=$budget raw tokens)")
        return WarmUpTrim(warmUp.drop(drop), decided = true)
    }

    /**
     * [T-ctx-measure-outbound] `"estimatedRequestTokens":…,"estimatedFixedTokens":…` for the
     * usage row, or "". Same keys as iOS StoredTokenUsage — and the same keys
     * [ContextSizeMeter.calibrationSample] reads back, so a reloaded session
     * replays the pair instead of re-deriving a ratio from raw sizes.
     *
     * internal 而非上游的 private（`ChatViewModel.kt:1607`）：两处 usage 行写入器
     * 在本移植里分居两个文件，`ChatViewModelPersistMessage.kt` 是扩展函数、够不到类内
     * private 成员。函数体与上游一字不差。
     */
    internal fun calibrationJsonFields(): String = lastCalibratedPair?.let { (est, fixed, model) ->
        val modelField = model?.let { ""","calibrationModelId":${org.json.JSONObject.quote(it)}""" } ?: ""
        ""","estimatedRequestTokens":$est,"estimatedFixedTokens":$fixed$modelField"""
    } ?: ""

    /**
     * [T-ctx-warmup-fit] [T-ctx-warmup-trim-undecided] 上游 1.14
     * `ChatViewModel.kt:4750-4768` 里那段「按 marker 决定一次、之后复用」的接线，
     * 宿主侧入口：投影算出 preAnchor 剪枝后的热身与锚点之后的那一段，这里决定要
     * 保留多少。
     *
     * 决定只做一次、按 marker 缓存：每请求重新决定会让热身窗口每来一条消息就滑一
     * 轮（在 iOS 双胞胎上量到过），请求前缀每回合都变，prompt caching 直接失效。
     * 固定下来之后前缀稳定，请求正常增长，压缩在线上发生。
     *
     * [T-ctx-warmup-trim-undecided] 只缓存**真的**决定。算不出预算时裁剪会把热身
     * 原样放行，那时缓存 drop=0 会把「全都留着」永久钉在这个 marker 上，哪怕预算
     * 后来算出来了、并且说不合身。
     *
     * 可见性为 internal：上游这段内联在单体 `effectiveAgentHistoryUncounted` 里，
     * 本移植那个投影在 harness（CompactHistoryProjector）——投影侧接上回调用它。
     */
    internal fun warmUpKeptForCurrentMarker(
        preAnchorPruned: List<LLMMessage>,
        postAnchor: List<LLMMessage>,
        summaryWrappedText: String,
    ): List<LLMMessage> {
        val marker = _cachedLatestMarker ?: return preAnchorPruned
        val warmUp = warmUpDropByMarker[marker.id]?.let { drop ->
            preAnchorPruned.drop(minOf(drop, preAnchorPruned.size))
        } ?: trimWarmUpToFit(preAnchorPruned, postAnchor, summaryWrappedText).let { trim ->
            if (trim.decided) warmUpDropByMarker[marker.id] = preAnchorPruned.size - trim.kept.size
            trim.kept
        }
        return warmUp
    }

    /**
     * [T-android-offload-warmup-scan] 上游 1.14 `ChatViewModel.kt:10064-10096`，
     * 合并时丢了：[effectiveAgentHistoryUncounted] 真正会发出的、锚点之前的热身
     * 消息在 [agentHistory] 里的**原始下标**，外加它在那一段里剪掉的工具 id。
     * iOS `offloadWarmUpScanPlan`（155501fcd）的移植。
     *
     * [offloadScanStartIndex]（ChatViewModelOffloadExt.kt）从锚点**之后**起步，但
     * compact-v2 仍会发出锚点之前最多 [COMPACT_KEEP_RECENT_USER_TURNS] 轮用户消息
     * （≤ 100 条），只剪掉超过 1000 字符的 tool_result 及其调用：那里的
     * file_write/file_edit 参数、501–1000 字符的结果和图片每次请求都会发出去，
     * 却再也不可卸载。这里逐步镜像那个函数——同一条回走、同一个 > 1000 剪枝、
     * 纯文本消息永远保留、被剪空的消息丢弃、开头非 user 剥掉、按 marker 的热身
     * 裁剪只在**已经决定过**时才套用——于是不会扫到任何发不出去的东西。那个函数
     * 不发热身时（无摘要 / 无 marker、v1、锚点解析不到）这里就是空。
     *
     * 可见性由上游的 private 提为 internal：调用点在 ChatViewModelOffloadExt.kt
     * 的扩展函数里，private 成员在那里不可达（与 offloadScanStartIndex 同一处理）。
     */
    internal fun offloadWarmUpScanPlan(): Pair<List<Int>, Set<String>> {
        val none = emptyList<Int>() to emptySet<String>()
        if (_compactSummary.value.isNullOrBlank()) return none
        val marker = _cachedLatestMarker ?: return none
        if (marker.version < 2) return none
        val anchorId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() } ?: return none
        val anchorIdx = agentHistory.indexOfLast { it.dbMessageId == anchorId }
        if (anchorIdx < 0) return none
        val priorIdx = com.openminis.app.harness.agent.CompactHistoryProjector.walkBackUserTurnsBounded(
            history = agentHistory,
            anchorIdx = anchorIdx,
            maxUserTextTurns = COMPACT_KEEP_RECENT_USER_TURNS,
            maxMessages = 100,
        ).priorIdx ?: return none
        if (priorIdx > anchorIdx) return none
        return warmUpScanIndices(agentHistory, priorIdx, anchorIdx, warmUpDropByMarker[marker.id])
    }


    /** What the pre-send context check decided. Mirrors iOS's send() branch. */
    internal enum class PreSendContextAction {
        /** Under threshold (or nothing useful to do) — send as normal. */
        PROCEED,

        /** Auto-compact is on — compact silently, then send. */
        COMPACT_THEN_SEND,

        /** Auto-compact is off — raise the dialog and let the user choose. */
        ASK_USER,
    }

    /**
     * Text + attachments held back while the "Context Near Capacity" dialog is
     * up. Mirrors iOS `pendingSendText` / `pendingSendAttachments`.
     */
    internal var pendingSendText: String? = null

    /**
     * [T-scheduled-tool-prefill] Prefilled tool calls travelling with
     * [pendingSendText] while a pre-send compaction runs, so the prompt that
     * finally goes out still declares them. Set and cleared together with the
     * text. （上游 1.14 ChatViewModel.kt:5465-5470。）
     */
    internal var pendingSendPrefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList()

    /**
     * [T-android-programmatic-prompt-keeps-draft] Whether [pendingSendText]
     * came from a headless prompt rather than the composer. It keeps the send
     * after compaction headless, and stops [cancelCompactBeforeSend] from ever
     * dropping a background prompt's text into the user's input box.
     * （上游 1.14 ChatViewModel.kt:5472-5478。）
     */
    internal var pendingSendHeadless: Boolean = false

    internal val _showCompactBeforeSendPrompt = MutableStateFlow(false)
    val showCompactBeforeSendPrompt: StateFlow<Boolean> = _showCompactBeforeSendPrompt.asStateFlow()

    /**
     * Dialog action: compact the history, then send what the user was holding.
     * [alsoEnableAutoCompact] backs iOS's one-tap opt-in button, which compacts
     * now AND remembers the choice for every future conversation.
     */
    fun compactAndSendPending(alsoEnableAutoCompact: Boolean = false) {
        if (alsoEnableAutoCompact) setAutoCompactEnabled(true)
        _showCompactBeforeSendPrompt.value = false
        val text = pendingSendText ?: return
        pendingSendText = null
        val prefill = pendingSendPrefill
        pendingSendPrefill = emptyList()
        val headless = pendingSendHeadless
        pendingSendHeadless = false
        viewModelScope.launch {
            val ok = awaitCompaction()
            if (!ok) {
                AppLogger.warning(TAG, "[Context] pre-send compaction failed — sending anyway")
            }
            sendMessage(text, skipContextCheck = true, headless = headless, prefill = prefill)
        }
    }

    /** Dialog action: send without compacting. */
    fun sendPendingWithoutCompacting() {
        _showCompactBeforeSendPrompt.value = false
        val text = pendingSendText ?: return
        pendingSendText = null
        val prefill = pendingSendPrefill
        pendingSendPrefill = emptyList()
        val headless = pendingSendHeadless
        pendingSendHeadless = false
        sendMessage(text, skipContextCheck = true, headless = headless, prefill = prefill)
    }

    /** Dialog dismissed — restore the text to the composer so it isn't lost. */
    fun cancelCompactBeforeSend() {
        _showCompactBeforeSendPrompt.value = false
        // [T-android-programmatic-prompt-keeps-draft] Only the user's own text
        // goes back into the composer.
        if (!pendingSendHeadless) pendingSendText?.let { _inputText.value = it }
        pendingSendText = null
        pendingSendPrefill = emptyList()
        pendingSendHeadless = false
    }

    /**
     * [T-android-auto-compact-inloop] What the in-loop context guard decided.
     */
    internal enum class InLoopContextAction {
        /** Under threshold — issue the next API call as normal. */
        PROCEED,

        /** History was compacted in place; re-run the iteration. */
        COMPACTED,

        /** Cannot recover — stop the turn safely and let the user resume. */
        STOP,
    }

    /**
     * [T-android-auto-compact-inloop] Run [compactAll] with the in-loop flag and
     * suspend until it settles. Returns whether it actually compacted.
     *
     * `compactAll` is fire-and-forget (it launches its own IO coroutine), so the
     * loop cannot simply call it and continue — the next API call would read the
     * pre-compaction history and the guard would fire again immediately.
     */
    internal suspend fun awaitCompaction(): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            var resumed = false
            compactAll(allowDuringProcessing = true) { ok ->
                // compactAll guarantees exactly one callback, but guard anyway:
                // resuming a continuation twice throws.
                if (!resumed) {
                    resumed = true
                    if (cont.isActive) cont.resume(ok) { _, _, _ -> }
                }
            }
        }


    // T203 part 2: these MUST be declared before `init { loadSession() }` below.
    // viewModelScope.launch defaults to Dispatchers.Main.immediate, which runs
    // the launch body synchronously up to the first suspend point — and the
    // launch body reads `isDraft` before its first suspend. If `isDraft` is
    // declared further down the class, its property initializer hasn't run yet,
    // so the read returns the JVM default (`false`), routing every draft
    // session through the load-from-DB branch. The DB lookup misses (no row
    // for `__new__…` keys), the function returns early, and no model name /
    // group name is ever set on the draft chat — exactly the bug T203 was
    // chasing through the wrong layer.
    /** Model group ID from long-press FAB, encoded in the draft session ID.
     *  substringBefore strips the folder marker in case both are present. */
    internal val initialGroupId: String? =
        sessionId.substringAfter("__grp__", "").substringBefore("__fld__")
            .takeIf { it.isNotEmpty() }

    /** Session-group (folder) id from the folder card's "New Chat in Group"
     *  menu item, encoded in the draft id. Filed at draft promotion — the
     *  folder_id row can only exist once the session does (iOS defers the
     *  same way via pendingFolderDraft). */
    internal val initialFolderId: String? =
        sessionId.substringAfter("__fld__", "").substringBefore("__grp__")
            .takeIf { it.isNotEmpty() }

    private var unsubscribeSafeMode: (() -> Unit)? = null
    private var preserveShellOnClear = false
    private var initialScopeJobs: Set<Job> = emptySet()

    init {
        viewModelScope.launch {
            _isStreaming.collect { streaming ->
                if (!streaming) {
                    ChatViewModelStore.scheduleTrim()
                    if (tailAttachQueued || _hasNewerMessages.value) ensureSessionTailLoaded()
                }
            }
        }
        loadSession()
        // [T-session-paused-badge-active-false-positive] Drive the session-list
        // PAUSED badge directly off canResume — the authoritative "this session
        // is interrupted (tap Resume)" flag. This is the single chokepoint over
        // every _canResume setter (background-suspend cleanup, cancel cleanup,
        // loadSession DB detection, …): canResume true → badge on; false
        // (resumed / new send / completed) → badge off. Replaces both the old
        // foreground heuristic AND clear-on-open, so a session the user merely
        // glanced at but didn't resume keeps its badge, and a running/resolved
        // session never shows one.
        viewModelScope.launch {
            com.openminis.app.service.OverlayComposeBus.pending.collect { req ->
                val sid = realSessionId.ifBlank { sessionId }
                if (req.sessionId != sessionId && req.sessionId != sid) return@collect
                com.openminis.app.service.OverlayComposeBus.consumeSticky(req.sessionId)
                if (_isStreaming.value) enqueuePrompt(req.text) else sendMessage(req.text)
            }
        }
        viewModelScope.launch {
            sessionLoaded.first { it }
            val sid = realSessionId.ifBlank { sessionId }
            val sticky = com.openminis.app.service.OverlayComposeBus.consumeSticky(sid)
                ?: com.openminis.app.service.OverlayComposeBus.consumeSticky(sessionId)
            if (!sticky.isNullOrBlank()) {
                if (_isStreaming.value) enqueuePrompt(sticky) else sendMessage(sticky)
            }
        }
        viewModelScope.launch {
            canResume.collect { interrupted ->
                if (interrupted) {
                    // [T-android-group-pause-badge-restamp] Only a REAL
                    // interruption re-stamps the badge's entry time. This
                    // collector is the single chokepoint over every
                    // `_canResume` setter, so it ALSO fires when loadSession
                    // merely RE-DETECTS an old interrupted tail — that is not
                    // a new entry into the paused state, and re-stamping it
                    // there is what let a days-old pause keep looking "fresh"
                    // to the group card's 24h window forever (the more often
                    // the user opened the chat, the less able it was to
                    // expire). The detecting site raises a sticky generation
                    // mark before its assignment; we consume it here, once the
                    // annotated emission has actually been observed.
                    val pendingGen = redetectingInterruptedTailGen
                    val isRedetection = pendingGen != consumedRedetectGen
                    if (isRedetection) consumedRedetectGen = pendingGen
                    com.openminis.app.service.SessionBadgeStore.push(
                        sessionId,
                        com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED,
                        restamp = !isRedetection,
                    )
                } else {
                    com.openminis.app.service.SessionBadgeStore.remove(
                        sessionId,
                        com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED,
                    )
                }
            }
        }
        // T-android-crash-safe-mode-v2: when the user dismisses the
        // safe-mode dialog, retry the restore that we skipped during
        // cold start. loadSession() is idempotent (re-checks isSafeMode
        // on entry; sessionLoaded gate prevents double-population), so
        // this is a clean "now finish the work you skipped" hook.
        unsubscribeSafeMode = com.openminis.app.crash.CrashFrequencyDetector
            .registerSafeModeClearedListener {
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    runCatching { loadSession() }
                        .onFailure {
                            android.util.Log.w(
                                TAG,
                                "safe-mode-cleared retry loadSession failed: ${it.message}",
                            )
                        }
                }
            }
        // Re-resolve provider when config changes (models may load async)
        viewModelScope.launch {
            // T306: wait for loadSession to finish BEFORE observing config.
            //
            // Pre-T306 we used a "skip first replay" trick that broke under
            // a real race: loadSession suspends inside `chatRepository.getSession`,
            // so when ProviderRepository finishes its async config load and
            // emits the populated value, the collector can fire BEFORE
            // loadSession's `restoreFromBinding(session.modelBinding)` runs.
            // The collector then resolves to the default group's first
            // entry (X), `_modelName` flips to X, and seconds later
            // restoreFromBinding finds Y and re-sets `_modelName` to Y —
            // exactly the "top model picker first shows X, then flickers and switches to Y"
            // the user reported after a fallback persisted Y.
            //
            // Awaiting `sessionLoaded == true` here means loadSession has
            // already had its turn at the persisted binding (success or
            // failure). After that, the `currentProvider == null` guard
            // below correctly captures BOTH the draft case (no binding,
            // currentProvider may still be null because config hadn't
            // loaded yet during loadSession) AND the existing-session
            // case where binding restore failed, while leaving alone any
            // session whose binding successfully resolved to its target.
            sessionLoaded.first { it }
            providerRepository.config.collect { config ->
                // T278: _availableGroups feeds the model picker sheet — it must
                // track the latest config on every emission, even after the user
                // has selected a model (currentProvider != null). The guard below
                // is for the fallback-resolution path which CAN trample the user's
                // selection; _availableGroups has no such risk because the sheet
                // re-reads it on each open.
                _availableGroups.value = config.modelGroups
                // [T-android-disabled-provider-still-selectable-via-group #34]
                // Runtime re-resolution when a GROUP-bound session's currently
                // active member has its provider DISABLED mid-session. The
                // selection paths (resolveProviderFromGroup → enabledMemberEntries)
                // already skip disabled members, but they only run while
                // currentProvider == null (cold start / fallback). Once a group
                // member is resolved, currentProvider is cached and the guard
                // below short-circuits — so if the user then disables that
                // member's provider (e.g. a Coding Plan whose quota ran out,
                // turned off to force fallback to the next provider), the stale
                // currentProvider keeps routing to the disabled provider's
                // pay-as-you-go model and bills them. Mirror iOS resolveCurrentEntry
                // (a306ce08): when the active entry's provider is no longer
                // enabled, re-resolve the group to its next enabled member. Only
                // for group bindings — a deliberate direct-entry pick is left
                // untouched (it has no in-group alternative to fall back to).
                val groupBound = _selectedGroupId.value
                val activeEntry = _activeEntryId.value
                if (currentProvider != null && groupBound != null && activeEntry != null &&
                    config.modelEntries.isNotEmpty() &&
                    !providerRepository.isEntryProviderEnabled(activeEntry)
                ) {
                    val before = activeEntry
                    if (resolveProviderFromGroup(groupBound)) {
                        AppLogger.info(
                            TAG,
                            "🔀RESOLVE group=$groupBound active entry=$before provider disabled — re-resolved to entry=${_activeEntryId.value} model=${currentModel?.id}",
                        )
                        // Persist the re-resolved member so a reload doesn't snap
                        // back to the disabled one. resolveProviderFromGroup set
                        // _activeEntryId to the actually-resolved member.
                        _activeEntryId.value?.let {
                            persistBinding("""{"type":"group","groupId":"$groupBound","lastEntryId":"$it"}""")
                        }
                    } else {
                        // Whole group is now unavailable (all members disabled /
                        // credential-less) — fall through to the default group /
                        // new-chat fallback chain by clearing the cached provider
                        // so the guard below re-runs the standard resolution.
                        AppLogger.warning(
                            TAG,
                            "🔀RESOLVE group=$groupBound active entry=$before provider disabled and group has no enabled member — falling back",
                        )
                        currentProvider = null
                    }
                }
                if (currentProvider == null && config.modelEntries.isNotEmpty()) {
                    // T306: re-attempt the persisted binding now that config
                    // has entries. For an existing session whose loadSession
                    // ran before config finished (so restoreFromBinding fell
                    // through), the binding pointed at the right entry all
                    // along — we just couldn't resolve it. Try it again
                    // before falling back to the default group, so the
                    // fallback target survives a cold start that races
                    // ProviderRepository's async load.
                    val sid = realSessionId.takeIf { it.isNotEmpty() }
                    if (sid != null) {
                        val session = runCatching { chatRepository.getSession(sid) }.getOrNull()
                        if (session?.modelBinding != null && restoreFromBinding(session.modelBinding)) {
                            return@collect
                        }
                    }
                    if (!applyDefaultPrimarySlot(initialGroupId, applyGroupDefaults = false)) {
                        // [T-newchat-default-model-fallback-android] Same
                        // new-chat fallback chain as the draft branch in
                        // loadSession: last-used → newest-provider/newest-text.
                        // Was allVisibleEntries().firstOrNull().
                        applyNewChatDefaultModel()
                    }
                }
            }
        }
    }

    init {
        // Long-lived initial collectors are cancelled on eviction; later jobs
        // (DB writes, sends, imports, etc.) must finish before a VM is eligible.
        initialScopeJobs = viewModelScope.coroutineContext[Job]?.children?.toSet().orEmpty()
    }

    /**
     * Session ID that disk/shell-bound resources must use. Until the user sends
     * the first message, `realSessionId` is empty and we fall back to the draft
     * key. After `ensureSession()` runs, this returns the persisted id so
     * `/var/minis/{attachments,workspace,...}` mounts, browser artifacts, and
     * the PersistentShell all land in a single directory that survives re-entry.
     */
    internal val activeSessionId: String
        get() = realSessionId.ifEmpty { sessionId }

    /** Public accessor used by ChatScreen to resolve session-scoped minis:// links. */
    val currentSessionId: String
        get() = activeSessionId

    /** T-chat-title-pill-edit: load the persisted [ChatSessionEntity] for the
     *  current session so the shared edit-title sheet (reused from the session
     *  list) can be opened from the in-chat title pill. Returns null for
     *  drafts that haven't been persisted yet. */
    suspend fun loadSessionEntity(): com.openminis.app.data.db.ChatSessionEntity? {
        val sid = realSessionId.ifEmpty { return null }
        return runCatching { chatRepository.getSession(sid) }.getOrNull()
    }

    /** T-chat-title-pill-edit: update title + category from the in-chat
     *  edit sheet. Mirrors SessionListViewModel.updateTitleAndCategory but
     *  also refreshes the local StateFlows so the pill updates immediately
     *  without waiting for a session reload. */
    fun updateTitleAndCategory(title: String, category: String?) {
        val sid = realSessionId.ifEmpty { return }
        viewModelScope.launch {
            chatRepository.updateSessionTitleAndCategory(sid, title, category)
            _sessionTitle.value = title.ifBlank { "New Chat" }
            _sessionCategory.value = category
        }
    }




    /**
     * Mark every non-system UI message that falls before [marker]'s boundary
     * as [ChatMessage.isCompactedHistory]. Mirrors iOS Phase 2.5 boundary
     * resolution (AIChatViewModel.swift:3380-3411) but with one improvement
     * over iOS for the compactAll case:
     *
     *   1) `firstKeptMessageId` — first kept message (divider goes BEFORE it)
     *   2) `boundaryMessageId`  — legacy alias of firstKeptMessageId
     *   3) Both null → compactAll. iOS naively places the divider at the end
     *      and grays every loaded UI message, which incorrectly gray-scales
     *      messages persisted AFTER the marker (e.g. follow-up turns sent
     *      between compact and reload). We instead use
     *      `lastCompactedMessageId` to find the last message included in the
     *      compacted range — anything after it stays active. The divider is
     *      placed immediately after that boundary.
     */

    /**
     * createdAt self-heal: return the LAST raw message whose
     * `createdAt < markerCreatedAt` AND whose id is still represented in
     * agentHistory (filtered via [historyDbIds]). When [historyDbIds] is
     * empty (no dbIds collected — unusual), the filter degrades to "just
     * the createdAt predicate" so we still recover SOMETHING.
     *
     * Mirrors iOS AIChatViewModel+Compaction.swift:125.
     */
    internal fun anchorByCreatedAt(
        rawMessages: List<com.openminis.app.data.db.MessageEntity>,
        markerCreatedAt: Long,
        historyDbIds: Set<String>,
    ): com.openminis.app.data.db.MessageEntity? {
        return rawMessages.lastOrNull { raw ->
            raw.createdAt < markerCreatedAt &&
                (historyDbIds.isEmpty() || raw.id in historyDbIds)
        }
    }

    /**
     * Build a healed v2 marker that preserves identity (id, sessionId,
     * summary, createdAt, compactedCount) but swaps `lastCompactedMessageId`
     * to the recomputed anchor, zeroes legacy fields, and bumps `version`
     * to 2. Future loads resolve through the corrected lcmId directly
     * without re-running the createdAt fallback.
     *
     * Mirrors iOS AIChatViewModel+Compaction.swift:150.
     */
    internal fun rewriteMarkerForHeal(
        original: com.openminis.app.data.db.CompactMarkerEntity,
        newAnchor: com.openminis.app.data.db.MessageEntity,
        lastRaw: com.openminis.app.data.db.MessageEntity?,
    ): com.openminis.app.data.db.CompactMarkerEntity {
        // Legacy sort-order fallback writes a past-end sentinel so any
        // hypothetical v1 reader sees "everything compacted, nothing
        // kept" (graceful degradation, no overlap with live tail).
        // Android's MessageEntity doesn't carry a sortOrder column —
        // use Int.MAX_VALUE like the original compactAll write path.
        return original.copy(
            firstKeptSortOrder = Int.MAX_VALUE,
            boundaryMessageId = null,
            firstKeptMessageId = null,
            lastCompactedMessageId = newAnchor.id,
            uiBoundarySortOrder = null,
            version = 2,
        )
    }

    /**
     * Apply a single model entry pin (`entry:` slot or a restored binding).
     * Does not set a group id — the pin is the model, not a group.
     */
    private fun applyPinnedModelEntry(entryId: String): Boolean {
        val entry = providerRepository.config.value.modelEntries.find { it.id == entryId } ?: return false
        val instance = providerRepository.instance(entry.providerInstanceId) ?: return false
        if (!providerRepository.hasAnyCredential(instance)) return false
        val apiKey = providerRepository.usableApiKey(instance) ?: ""
        currentModel = entry.model
        _modelName.value = entry.model.displayName
        _providerName.value = instance.label.ifEmpty { entry.model.provider }
        _selectedGroupId.value = null
        _selectedGroupName.value = ""
        _activeEntryId.value = entry.id
        currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context)
        return true
    }

    /**
     * New-chat model: an `entry:` default pin, otherwise the selected or default group.
     * Group ids that are entry pins are not passed to group lookup.
     */
    internal fun applyDefaultPrimarySlot(initialGroupId: String?, applyGroupDefaults: Boolean): Boolean {
        if (initialGroupId == null) {
            val pinned = com.openminis.app.data.model.ModelSlotRef.entryId(providerRepository.defaultPrimaryGroupId)
            if (pinned != null) return applyPinnedModelEntry(pinned)
        }
        val effectiveGroupId = initialGroupId ?: providerRepository.defaultPrimaryGroupId?.takeUnless {
            com.openminis.app.data.model.ModelSlotRef.isEntry(it)
        }
        if (effectiveGroupId == null) return false
        val resolved = resolveProviderFromGroup(effectiveGroupId)
        if (resolved) {
            _selectedGroupId.value = effectiveGroupId
            if (applyGroupDefaults) applyGroupSessionDefaults(effectiveGroupId)
        }
        return resolved
    }

    /** Restore provider state from a JSON binding string. Returns true if successfully resolved. */
    internal fun restoreFromBinding(bindingJson: String?): Boolean {
        bindingJson ?: return false
        return try {
            val obj = org.json.JSONObject(bindingJson)
            when (obj.optString("type")) {
                "group" -> {
                    val groupId = obj.optString("groupId").takeIf { it.isNotEmpty() } ?: return false
                    val lastEntryId = obj.optString("lastEntryId").takeIf { it.isNotEmpty() }
                    val resolved = resolveProviderFromGroup(groupId, lastEntryId)
                    if (resolved) _selectedGroupId.value = groupId
                    resolved
                }
                "entry" -> {
                    val entryId = obj.optString("entryId").takeIf { it.isNotEmpty() } ?: return false
                    applyPinnedModelEntry(entryId)
                }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }


    // ── [T-android-switch-model-next-request] 一轮跑着时换模型 ────────────────
    //
    // 上游 1.14 `ChatViewModel.kt:7085-7205`（cancelWorkBoundToPreviousModel /
    // isBoundToAbandonedModel / pendingModelSwitch / systemPromptFor），合并时
    // 整段丢了。丢掉的后果有两个：挑选器重绑之后循环还攥着发送时的 provider，
    // 于是一次 20 个工具调用的任务会全程跑在旧模型上；而挂在自动重试阶梯上等待
    // 的那次重试，会在用户已经走开之后再用旧模型打一次（幽灵重试）。
    //
    // 移植适配只有一处可见性：循环已经拆进 ChatViewModelAgentLoopExt.kt，扩展
    // 函数读不到 private 成员，所以 [pendingModelSwitch] / [systemPromptFor] 提到
    // internal——与 recordContextDispatch / noteProviderContextRejection 同一条
    // 既有约定。

    /**
     * [T-android-switch-model-next-request] 用户在健康的一轮跑着时挑了另一个模型。
     * 由 [cancelWorkBoundToPreviousModel] 置位（每个挑选入口都先经过它、然后才
     * 重绑），runAgentLoop 在下一次迭代开头读取并清除。用显式标志而不是「类级
     * provider 变了」：组内回退同样会移动 provider，那不该被读成用户换模型。
     * 只在主线程读写。
     */
    internal var pendingModelSwitch = false

    /**
     * [T-android-switch-model-let-stream-finish] 在飞的那一轮，是奔着**正在被离开
     * 的**模型去的活儿，还仅仅是碰巧在跑的健康回合？
     *
     * 这个区分就是整个接缝的意义。`currentProvider` 每次尝试只取一次，所以能活过
     * 一次换模型的只有自动重试阶梯：它抱着被放弃的 provider 睡完 1s/2s/4s 倒计时，
     * 醒来后照旧对它发起下一次尝试。那就是这个修复要清掉的幽灵重试，而取消它不花
     * 任何代价——它要做的那次尝试按定义就是不需要的。
     *
     * 正常流式输出的一轮正相反：它产出的就是用户正在读的回答。取消它等于把真实
     * 输出扔掉（报告的 Pixel 6 那次是 33.8k token 和一个永远没组装完的工具调用），
     * 却换不到任何好处——模型绑定已经更新，下一个回合无论如何都跑在新模型上。
     */
    internal fun isBoundToAbandonedModel(): Boolean =
        _autoRetryAttempt.value > 0 || _autoRetryCountdown.value > 0

    /**
     * [T-android-switch-model-next-request] 给 [provider] 用的 system prompt：
     * 带 Anthropic OAuth 必需的 Claude Code 前缀，其他终端一律不带（那是那个登录
     * 方式的身份串，不该发给别人）。prompt 的其余部分在 Android 上不分模型。
     *
     * 可见性由上游的 private 提为 internal：循环已拆进
     * ChatViewModelAgentLoopExt.kt，取件之后要在那里重算这份 prompt。
     */
    internal fun systemPromptFor(provider: LLMProvider, prompt: String?): String? {
        val prefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
        if (prompt == null || prefix.isEmpty()) return prompt
        val bare = if (prompt.startsWith(prefix)) prompt.removePrefix(prefix).trimStart('\n') else prompt
        val oauth = (provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true
        return if (oauth) "$prefix\n\n$bare" else bare
    }

    /**
     * [T-android-switch-model-ghost-retry] 在重绑之前，先停掉还在呼叫「即将被离开
     * 的模型」的那些活儿。没在飞的时候是空操作，所以空闲会话里挑一次模型一分钱
     * 不花。上游 1.14 `ChatViewModel.kt:7085-7158`。
     *
     * 唯一一处移植适配：上游在取消前置 `lastTurnWasCancelled`，本移植没有那个
     * 字段。它上游唯一的读者是回合结束的用量提示（`publishContextUsage`），而
     * 本移植没有那条路径（见 ChatViewModel.kt 里 [T-android-context-usage-hint]
     * 的移植说明），所以置位语义在这里没有落点，不是漏了。
     */
    private fun cancelWorkBoundToPreviousModel(reason: String) {
        if (!_isStreaming.value && streamJob?.isActive != true) return
        val leaving = currentProvider?.model?.displayName ?: "(unresolved)"
        if (!isBoundToAbandonedModel()) {
            // [T-android-switch-model-let-stream-finish] 这一轮正在产出，不打断它。
            // [T-android-switch-model-next-request] 但它也不再抱着旧模型跑完剩下的
            // 回合：调用方紧接着就重绑类级 provider，runAgentLoop 在下一次请求
            // 之前换过去（iOS 86a045284）。工具驱动的任务在「当前这次请求或工具
            // 调用」之后就用上新模型，而不是等到最后一次。
            pendingModelSwitch = true
            AppLogger.info(
                TAG_STREAM,
                "[SwitchModel] '$leaving' is streaming normally ($reason) — " +
                    "letting this request finish; the NEXT request uses the new model",
            )
            return
        }
        // 循环正在结束：没有下一次请求可以让切换生效，下一个回合自然会解析新绑定。
        pendingModelSwitch = false
        AppLogger.info(
            TAG_STREAM,
            "[SwitchModel] cancelling retry work bound to '$leaving' ($reason) " +
                "attempt=${_autoRetryAttempt.value}",
        )
        streamJob?.cancel()
        _isStreaming.value = false
        // 倒计时文案（"… — retrying (2/3)…"）点名的是旧模型，那才是用户看见的
        // 幽灵。它归刚刚被取消的阶梯所有，所以不会有别人来清它。
        _autoRetryAttempt.value = 0
        _autoRetryCountdown.value = 0
        clearInlineError()
        SessionActivityTracker.setInactive(activeSessionId)
        if (isDraft && realSessionId.isNotEmpty() && activeSessionId != sessionId) {
            SessionActivityTracker.setInactive(sessionId)
        }
        // [T-android-switch-model-let-stream-finish] 这里刻意不走 Stop 的收尾
        // （本移植即 cancelStream 的 finishStoppedRun）：那是「用户按了停止」的
        // 闭合动作——它的工具分支会把在飞的工具块翻成 CANCELLED、落一条取消的
        // tool_result 并抬起 `_canResume`，等于把一次普通换模型变成「任务已停止，
        // 点恢复」。我们只会从重试阶梯走到这里，那里没有半截的工具块要对账。
        //
        // 排在已取消重试后面的排队消息仍要在**新模型**下继续排空，而不是干等着。
        // resumeQueueAfterCancel 会在 200ms 后重新解析 provider，那时调用方已经
        // 重绑完毕。
        if (_promptQueue.value.isNotEmpty()) {
            AppLogger.info(TAG_STREAM, "[SwitchModel] ${_promptQueue.value.size} queued prompt(s) remain, restarting drain")
            resumeQueueAfterCancel()
        }
    }

    fun selectGroup(groupId: String) {
        // [T-android-switch-model-ghost-retry] 重绑之前，先停掉还在呼叫即将被
        // 离开的模型的重试阶梯。
        cancelWorkBoundToPreviousModel("selectGroup")
        _selectedGroupId.value = groupId
        _selectedGroupName.value = providerRepository.group(groupId)?.name ?: ""
        val resolved = resolveProviderFromGroup(groupId)
        if (resolved) {
            persistBinding("""{"type":"group","groupId":"$groupId"}""")
            applyGroupSessionDefaults(groupId)
        }
    }

    /** Select a specific entry within a group (keeps group selected). */
    fun selectGroupEntry(groupId: String, entryId: String) {
        // [T-android-switch-model-ghost-retry] 同 selectGroup。
        cancelWorkBoundToPreviousModel("selectGroupEntry")
        _selectedGroupId.value = groupId
        _selectedGroupName.value = providerRepository.group(groupId)?.name ?: ""
        val resolved = resolveProviderFromGroup(groupId, entryId)
        if (resolved) {
            persistBinding("""{"type":"group","groupId":"$groupId","lastEntryId":"$entryId"}""")
            applyGroupSessionDefaults(groupId)
            // [T-newchat-default-model-fallback-android] Record the actually-
            // resolved active entry as last-used (resolveProviderFromGroup may
            // fall back off a disabled member, so _activeEntryId is the truth).
            _activeEntryId.value?.let { providerRepository.lastUsedEntryId = it }
        }
    }

    /**
     * T312: mirrors iOS `AIChatViewModel.applyGroupSessionDefaults`.
     * When a session newly binds to a group (user picks the group, or a
     * draft session resolves the default group), copy the group's
     * `defaultThinkingLevel` into the session's persisted thinking_override.
     * Context limit is in-memory only on iOS; Android has no equivalent
     * runtime field yet, so we only handle thinking level here.
     *
     * Skips when the group has no default override (null) — leaves the
     * session's existing override untouched so manual user choices on a
     * pre-bound chat aren't clobbered by a later group re-select that
     * happens to land on the same default state.
     */
    private fun applyGroupSessionDefaults(groupId: String) {
        val group = providerRepository.group(groupId) ?: return
        val level = group.defaultThinkingLevel ?: return
        if (_thinkingLevel.value == level) return
        _thinkingLevel.value = level
        // [T-android-draft-no-row-on-open] Persist ONLY when a row already
        // exists. This used to call `ensureSession()`, which CREATES the row —
        // and because `loadSession()`'s draft branch calls this the moment a
        // new chat is opened, every tap on "New Chat" minted a session the user
        // never wrote in. The list filled up with "New Chat / No messages yet".
        //
        // iOS never had this: its `applyGroupSessionDefaults` takes an existing
        // sessionId as a PARAMETER and writes to ProviderConfigStore, so it has
        // no way to create a chat row, and it runs from inside
        // `ensureSessionReturningId()` — i.e. after the first send has already
        // materialised the row. The draft-time level lives in memory until then
        // (`pendingThinkingLevel` / `flushPendingThinkingLevel`, guarded by
        // `guard let sid = sessionId`).
        //
        // The in-memory `_thinkingLevel` above is the whole effect for a draft;
        // `ensureSession()` flushes it on first send, so nothing is lost.
        // （上游 1.14 ChatViewModel.kt:7274-7300 原文。）
        val sid = realSessionId
        if (sid.isEmpty()) return
        viewModelScope.launch {
            chatRepository.dao.updateThinkingOverride(sid, level.name)
        }
    }

    /**
     * [T-newchat-default-model-fallback-android] Resolve and apply the default
     * model for a NEW chat when no default group produced a model. Fallback
     * chain tiers 2→3 (tier 1, the default group, is handled by the caller
     * before this runs):
     *
     *   2) last-used model — the entry the user last actively selected / used,
     *      if it still exists, is visible, and its provider is enabled.
     *   3) newest provider's newest text-output model — the final catch-all so
     *      a first-ever chat with providers but no group/last-used still gets a
     *      sensible, text-capable default (image/audio-only models excluded).
     *
     * Sets currentModel / currentProvider / the name + activeEntry state flows.
     * Returns true when a model was applied. Mirrors iOS #636. The legacy
     * behaviour here was `allVisibleEntries().firstOrNull()` (the FIRST entry),
     * which ignored both last-used and add-order — replaced by this chain.
     */
    internal fun applyNewChatDefaultModel(): Boolean {
        val entry = providerRepository.lastUsedVisibleEntry()
            ?: providerRepository.newestProviderNewestTextEntry()
            ?: return false
        val instance = providerRepository.instance(entry.providerInstanceId) ?: return false
        currentModel = entry.model
        _modelName.value = entry.model.displayName
        _activeEntryId.value = entry.id
        _providerName.value = instance.label.ifEmpty { entry.model.provider }
        // [T-android-group-resolve-skip-uncredentialed] Build the provider for
        // an OAuth instance too — otherwise this tier set the model name in the
        // UI but left currentProvider null, and the first send failed.
        if (providerRepository.hasAnyCredential(instance)) {
            val apiKey = providerRepository.usableApiKey(instance) ?: ""
            currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context)
        }
        return true
    }

    /** Select a specific model entry (bypasses group selection). */
    override fun selectEntry(entryId: String) {
        val config = providerRepository.config.value
        val entry = config.modelEntries.find { it.id == entryId } ?: return
        val instance = providerRepository.instance(entry.providerInstanceId) ?: return
        // [T-android-group-resolve-skip-uncredentialed] The user explicitly
        // tapped this model; refusing it because the API-key slot is empty
        // made OAuth models unselectable from the picker.
        if (!providerRepository.hasAnyCredential(instance)) return
        val apiKey = providerRepository.usableApiKey(instance) ?: ""
        // [T-android-switch-model-ghost-retry] 上游 1.14
        // `ChatViewModel.kt:7353-7371`：取消点在**所有**「这个 entry 不可用」
        // 的 return 之后。那些 return 的意思是「挑不动」（未知 id / 缺实例 /
        // 没凭证），切换不会生效；上游第一版把取消放在它们前面，于是一次解析不到
        // 东西的挑选既杀掉了正在跑的一轮、又留着旧模型绑定——用户看到自己根本
        // 没离开的模型上死了一轮。不改变任何东西的挑选必须不花任何代价。
        cancelWorkBoundToPreviousModel("selectEntry")

        currentModel = entry.model
        // [T-merge-upstream-1-14] An explicit user pick is a statement that this
        // model should be tried again — clear any failure mark so the group pick
        // stops avoiding it (`upstream-1.14:.../ChatViewModel.kt:7374-7376`).
        // Without it the RoutingStrategy.fallback skip in
        // ChatViewModelGroupResolve.kt would be permanent: after one failure the
        // member could never be re-picked by hand.
        recentlyFailedEntryIds.remove(entry.id)
        _modelName.value = entry.model.displayName
        _providerName.value = instance.label.ifEmpty { entry.model.provider }
        _selectedGroupId.value = null
        _selectedGroupName.value = ""
        _activeEntryId.value = entry.id
        currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context)
        persistBinding("""{"type":"entry","entryId":"$entryId"}""")
        // [T-newchat-default-model-fallback-android] Remember this as the
        // global last-used model so the NEXT new chat (when no default group
        // is set) defaults back to it. Tier 2 of the new-chat fallback chain.
        providerRepository.lastUsedEntryId = entryId
    }

    /** Persist the model binding to the DB session (no-op for draft sessions). */
    internal fun persistBinding(bindingJson: String) {
        val sid = realSessionId.takeIf { it.isNotEmpty() } ?: return
        val modelId = currentModel?.id ?: return
        viewModelScope.launch {
            chatRepository.updateSessionBinding(sid, bindingJson, modelId)
        }
    }

    internal fun findModelEntry(modelId: String) =
        providerRepository.allVisibleEntries().find { it.model.id == modelId }

    /**
     * Build the ordered list of fallback providers for the current group,
     * starting AFTER the primary provider in the member list and cycling around.
     * This ensures that models already tried (before the primary) are at the end,
     * not the beginning — so retry doesn't re-trigger the same fallback chain.
     */
    /**
     * [T-android-fallback-entry-identity] A fallback candidate, carrying the
     * ENTRY it was built from.
     *
     * The entry id is the only unambiguous identity: two different provider
     * instances can expose the SAME `model.id` (observed in the field:
     * `deepseek-v4-flash` exists under both "DeekSeak" — api.deepseek.com — and
     * "Bailian OpenAI" — dashscope.aliyuncs.com). Recovering the entry after the
     * fact by matching `model.id` therefore picks whichever entry happens to
     * come first in `modelEntries`, which is not necessarily the one that served
     * the request.
     */
    internal data class FallbackCandidate(
        val provider: LLMProvider,
        val entryId: String,
    )



    // [T-android-split-chat] addAttachment / removeAttachment / clearAttachments
    // moved to ChatViewModelUiStateExt.kt (extension functions).

    /**
     * T137: Wipe in-memory and on-disk message state for the current session
     * without touching the session's chat files (workspace/, attachments/,
     * offloads/). Mirrors iOS [AIChatViewModel.clearChat] — same surface area,
     * same "files survive" guarantee.
     *
     * Cancels any in-flight stream first so the UI doesn't race the wipe.
     */
    fun clearChat() {
        if (_isStreaming.value) cancelStream()
        val sid = activeSessionId
        // T-streaming-side-channel: ensure no stale stream delta survives a
        // session wipe; the messages list is about to be cleared, so any
        // pending key would be orphaned.
        // [T-android-stream-flush-review] also cancel pending trailing flushes
        // so none re-adds an orphan side-channel entry after the wipe.
        clearAllStreamFlushStates()
        _streamingById.value = emptyMap()
        // Reset window cursors, edge flags and pending tail work with the data.
        timeline.reset()
        loadingOlderFlag.set(false)
        tailAttachQueued = false
        tailAttachJob?.cancel()
        tailAttachJob = null
        _hasOlderMessages.value = false
        _hasNewerMessages.value = false
        _isLoadingHistory.value = false
        pendingSendText = null
        _pendingCaret.value = null
        _showCompactBeforeSendPrompt.value = false
        _compactSummary.value = null
        // Memory state — match iOS clearChat() field list one-for-one.
        _messages.value = emptyList()
        agentHistory.clear()
        _error.value = null
        _cachedLatestMarker = null
        _compactMarkerDetached = false
        toolLoopDetector.reset()
        _canResume.value = false
        _attachments.value = emptyList()
        _promptQueue.value = emptyList()
        _hasInjectedShareContent.value = false
        // T261: tool-detail sheet is per-session UI state — clear it so a
        // newly cleared chat doesn't briefly flash a stale tool's sheet
        // before the existence-guard catches up.
        _selectedToolDetailId.value = null
        // Drop any browser tabs the agent spawned for this session, and
        // delete the persisted tab snapshot so a future open starts clean.
        // iOS calls BrowserTabPool.deletePersistedData(for:) +
        // BrowserUseOffloadBridge.releasePool(forSession:); on Android the
        // pool is per-VM (lazy), so releasing tabs here is sufficient.
        // [T-u12-merge] 上游 1.14 把原来那个名不副实的 `releaseAllTabs()` 拆成了两个：
        // `releaseAllTabsToUser()` 只把 inUse 交还用户、页面留着（Takeover 用），
        // `destroyAllTabs()` 才是真的拆 WebView、释放内存。这里要的是后者——注释一直
        // 写的就是「丢掉本会话的浏览器资源」，而旧名字只做前者，于是被清空的会话把
        // 几十 MB 的 WebView 永远留在池子里。用上游的新名字。
        // [T-w1-pool-ownership] 只有自建池才归本会话拆：helper 借的是父池，清空子会话
        // 不得拆父会话的标签（`helperConfig == null` 说的就是这件事，而自建标记才是
        // 真正的判据——借入者不一定都带 helperConfig）。
        if (helperConfig == null && _ownsBrowserTabPool) _browserTabPoolRef?.destroyAllTabs()
        runCatching {
            java.io.File(context.filesDir, "browser_tabs/$sid.json").delete()
        }
        // Persist: drop messages + compact markers. Files (workspace,
        // attachments, offloads) intentionally retained.
        viewModelScope.launch {
            dropQueuedPromptsOnDisk(sid) // [T-queue-disk-persistence]
            chatRepository.dao.deleteMessages(sid)
            chatRepository.dao.deleteCompactMarkers(sid)
            Log.i(TAG, "clearChat: session=$sid wiped (files preserved)")
        }
    }

    // ─── Share Injection (T51) ────────────────────────────────────────────

    /**
     * Whether the current input was seeded from a system share intent.
     * The "Move to…" capsule above the chat list is gated on this — once
     * the user starts a new turn or moves the share elsewhere we flip it
     * back to false. Mirrors iOS AIChatView.hasInjectedShareContent.
     */
    internal val _hasInjectedShareContent = kotlinx.coroutines.flow.MutableStateFlow(false)
    val hasInjectedShareContent: kotlinx.coroutines.flow.StateFlow<Boolean> =
        _hasInjectedShareContent.asStateFlow()

    fun markShareInjected() { _hasInjectedShareContent.value = true }
    fun clearShareInjectedFlag() { _hasInjectedShareContent.value = false }

    /**
     * Convert a staged share file (under filesDir/share_extension/) into
     * an [InputAttachment] and add it to the composer. Called by
     * ChatScreen when draining a [com.openminis.app.share.PendingShare].
     */
    fun addAttachmentFromStagedShare(file: java.io.File): InputAttachment? {
        if (!file.exists()) return null
        val ext = file.extension.lowercase()
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
        val kind = if (mime.startsWith("image/")) com.openminis.app.session.InputAttachment.Kind.IMAGE
                   else com.openminis.app.session.InputAttachment.Kind.DOCUMENT
        // T185 fix: ChatScreen wipes the share-extension directory right
        // after this call returns (`SharedShareStore.cleanSharedFiles`),
        // so a `Uri.fromFile(<staged file>)` would dangle by the time the
        // user actually sends — the byte-read in prepareUserAttachments
        // then fails to open the stream and the image never makes it into
        // the LLM payload, leaving the model staring at "what is this?" with no
        // picture. Copy the staged bytes into our own private dir so the
        // attachment outlives the share-extension cleanup.
        val durableDir = java.io.File(context.cacheDir, "share_inbound").apply { mkdirs() }
        val durable = java.io.File(durableDir, "${java.util.UUID.randomUUID()}-${file.name}")
        // [T-b1-paste-no-recopy] A source already inside our private cache
        // (the clipboard-paste temp file is `cache/clipboard_paste_*`) can be
        // renamed instead of read+written a second time. Same volume, so the
        // rename is atomic; a failure falls back to the full copy.
        val alreadyPrivate = runCatching {
            file.canonicalPath.startsWith(context.cacheDir.canonicalPath + java.io.File.separator)
        }.getOrDefault(false)
        val renamed = alreadyPrivate && runCatching { file.renameTo(durable) }.getOrDefault(false)
        if (!renamed) {
            try {
                file.inputStream().use { input ->
                    durable.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "failed to copy staged share file ${file.name}: ${e.message}")
                return null
            }
        }
        val attachment = InputAttachment(
            fileName = file.name,
            uri = android.net.Uri.fromFile(durable),
            mimeType = mime,
            kind = kind,
        )
        addAttachment(attachment)
        return attachment
    }

    // ─── Message Sending & Agent Loop ─────────────────────────────────────

    /**
     * [T-android-rerun-from-tool-block-position] Resolve the live UI assistant
     * bubble id that currently owns the tool block with [blockId] (== its
     * tool_use id). Returns null when no live bubble holds it. Used by the
     * debug RPC ([com.openminis.app.debug.HeadlessChatRunner.rerunFromToolBlock])
     * because the in-memory bubble id is a volatile `assistant_<ts>` runtime id
     * (not the DB row id a caller would read from `chat.messages.list`), so the
     * harness can't supply it directly.
     */
    override fun assistantMessageIdForToolBlock(blockId: String): String? =
        _messages.value.firstOrNull { m ->
            m.role == "assistant" && m.toolBlocks.any { it.id == blockId }
        }?.id

    /**
     * [T-android-rerun-from-tool-block-position] Re-run the conversation from
     * the exact point a specific tool_use block was about to be issued —
     * BLOCK-boundary, not turn-boundary. Keeps the blocks BEFORE the target
     * tool_use in the same assistant turn; drops the target block + every
     * later block in that turn + its tool_result + all later turns, then
     * re-runs so the model re-decides from that point.
     *
     * Ported from iOS `retryFromToolBlock` (commit 0149457e). Anchor is the
     * block's tool_use id ([blockId], which for a tool_use [AssistantBlock]
     * equals its `id`) — stable + unique, NOT a positional count, so streaming
     * / merged-turn alignment can't drift the cut point.
     *
     * Degenerate case: when the target is the FIRST real block of its turn
     * (nothing precedes it), this is equivalent to truncating at the preceding
     * user message — delegate to [retryFromMessage] (the existing whole-turn
     * path) and skip the sub-message DB rewrite.
     *
     * Android does the cut DB-first (delete rows after the trimmed assistant
     * row, then rewrite that row's parts in place via
     * [ChatRepository.updateMessageParts]) and rebuilds agentHistory from the
     * trimmed DB state. The UI is trimmed in-memory (same as
     * [retryFromMessage]'s `retainedHead`, so compact-marker graying isn't
     * disturbed). Because the agent loop persists each turn as its own row and
     * `toChatMessages` merges consecutive assistant rows into one bubble, the
     * surviving trimmed turn and the new generation coalesce on the next
     * reload — no duplicate header (iOS needed an explicit resume-into-turn
     * fix for the same; Android gets it from the merge). The thinking
     * indicator shows immediately via [runAgentLoop]'s awaiting placeholder.
     *
     * No-op (returns false) when streaming, when the message/block isn't
     * found, or when the block isn't a tool_use. The caller gates the menu
     * item with the same `!isStreaming` rule, but the guard here is the source
     * of truth.
     */
    override fun rerunFromToolBlock(assistantMessageId: String, blockId: String): Boolean {
        if (_isStreaming.value) return false
        val messages = _messages.value
        val asstIdx = messages.indexOfFirst { it.id == assistantMessageId }
        if (asstIdx < 0) return false
        val asstMsg = messages[asstIdx]
        val blockIdx = asstMsg.toolBlocks.indexOfFirst { it.id == blockId }
        if (blockIdx < 0) return false
        val targetBlock = asstMsg.toolBlocks[blockIdx]
        // Only a real tool_use block anchors a block cut — its id is the
        // tool_use id we match against in agentHistory / parts_json.
        if (targetBlock.kind != "tool_use" || targetBlock.id.isBlank()) return false
        // [T-android-tool-autoscroll] Start-of-turn snap — see resume().
        _forceScrollToBottom.tryEmit(Unit)
        val targetToolUseId = targetBlock.id

        // Degenerate: nothing of substance precedes the target in this turn —
        // a block cut here is identical to truncating at the preceding user
        // message, so reuse the existing whole-turn path. "Substance" = any
        // earlier block that isn't an empty text block (mirrors iOS
        // hasPrecedingContent).
        val hasPrecedingContent = asstMsg.toolBlocks.take(blockIdx).any { blk ->
            if (blk.isText) blk.content.isNotEmpty() else true
        }
        // [T-android-rerun-from-tool-deletes-earlier-turns] The degenerate
        // shortcut is ONLY equivalent to truncating at the preceding user
        // message when there is NOTHING between that user message and this
        // assistant turn. If an EARLIER assistant turn/bubble sits right before
        // this one (asstIdx-1 is also assistant), retryFromMessage(precedingUser)
        // would delete that earlier turn's tools too — exactly the "rerun from
        // the last tool wiped the tools above it / re-ran from the very start"
        // bug (logged: historySize 29 → 3 on the 2nd consecutive rerun). In
        // that case fall through to the DB-precise cut below, which keeps every
        // row before the target row (its cutPartIdx==0 branch deletes only the
        // target row onward) and preserves the earlier turns.
        val precededByUserOnly = asstIdx == 0 || messages[asstIdx - 1].role != "assistant"
        if (!hasPrecedingContent && precededByUserOnly) {
            val userMsg = (asstIdx - 1 downTo 0).asSequence()
                .map { messages[it] }
                .firstOrNull { it.role == "user" && it.content.isNotBlank() }
                ?: return false
            Log.i(TAG, "rerunFromToolBlock degenerate → retryFromMessage(precedingUser) tuId=${targetToolUseId.take(12)}")
            retryFromMessage(userMsg.id)
            return true
        }

        val initialProvider = currentProvider
        if (initialProvider == null) {
            _error.value = "No provider configured"
            return false
        }
        _canResume.value = false
        _error.value = null

        // T149 parity: revoke memory_writes in the parts we're about to drop
        // so the on-disk daily log doesn't keep entries the user rewound past.
        // The dropped range is: the target turn's blocks FROM the target
        // onward (the target tool_use itself + any later same-turn blocks) +
        // every later message. The surviving earlier blocks of the target turn
        // are kept, so they're excluded.
        val droppedTargetTail = asstMsg.copy(
            toolBlocks = asstMsg.toolBlocks.drop(blockIdx),
        )
        val deletedMessages = listOf(droppedTargetTail) +
            messages.subList(asstIdx + 1, messages.size).toList()

        // Claim the streaming flag synchronously so a rapid second tap is
        // rejected by the entry guard (same rationale as retryFromMessage T145).
        AppLogger.info(TAG_STREAM, "rerunFromToolBlock _isStreaming=true (sync, sid=$activeSessionId)")
        _isStreaming.value = true

        viewModelScope.launch {
            var streamLaunched = false
            try {
                // [T-truncation-critical-section] 同一临界区：从工具块重跑也会
                // deleteMessagesAfter/updateMessageParts。剪裁在飞时先等它收尾，
                // 否则两边各自锚在半应用的 sort_order 区间上，行会丢。
                // _isStreaming 上面已同步置位，等待期间不会冒出新剪裁。
                TruncationInterlock.awaitIdle(truncationJob)
                val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId

                // Locate the DB assistant row holding the target tool_use, and
                // the parts-array index of that tool_use within it.
                val dbMessages = chatRepository.hydrateDisplayRows(
                    chatRepository.loadMessagesTail(sid, MAX_AGENT_HISTORY_MESSAGES),
                )
                var cutRow: MessageEntity? = null
                var cutPartIdx = -1
                outer@ for (entity in dbMessages) {
                    if (entity.role != "assistant") continue
                    val arr = try { org.json.JSONArray(entity.partsJson) } catch (_: Exception) { continue }
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        if (o.optString("type") != "toolUse") continue
                        val tuId = o.optJSONObject("value")?.optString("toolUseId") ?: ""
                        if (tuId == targetToolUseId) {
                            cutRow = entity
                            cutPartIdx = i
                            break@outer
                        }
                    }
                }
                val row = cutRow
                if (row == null || cutPartIdx < 0) {
                    // Anchor not in DB (shouldn't happen for a rendered tool
                    // block). Abort cleanly without a half-applied truncation.
                    Log.w(TAG, "rerunFromToolBlock: toolUseId ${targetToolUseId.take(12)} not found in DB — aborting")
                    return@launch
                }

                // Trim the row's parts to those strictly before the target
                // tool_use, preserving array order (parts_json mirrors block
                // order). An assistant turn may hold text + several tool_use
                // parts; we keep everything ahead of the matched index.
                val srcArr = org.json.JSONArray(row.partsJson)
                val keptArr = org.json.JSONArray()
                for (i in 0 until cutPartIdx) keptArr.put(srcArr.get(i))

                if (cutPartIdx == 0) {
                    // Nothing precedes the target in its DB row — trimming would
                    // leave an empty assistant row. Drop the whole row instead
                    // (keepCount = its sort_order). The UI degenerate guard
                    // above normally catches this, but a merged-bubble layout
                    // could route a first-in-row tool_use here; handle it so we
                    // never persist a phantom empty assistant message.
                    chatRepository.deleteMessagesAfter(sid, row.sortOrder)
                    Log.i(TAG, "rerunFromToolBlock cut at row start (empty trim) tuId=${targetToolUseId.take(12)} keepCount=${row.sortOrder} row=${row.id.take(8)}")
                } else {
                    // Delete every row after the trimmed assistant row, then
                    // rewrite the trimmed row in place. deleteMessagesAfter
                    // keeps rows with sort_order < keepCount, so keepCount =
                    // thisRow.sortOrder + 1 drops the following tool_result row
                    // + all later turns while keeping (then overwriting) this one.
                    chatRepository.deleteMessagesAfter(sid, row.sortOrder + 1)
                    chatRepository.updateMessageParts(row.id, keptArr.toString())
                    Log.i(TAG, "rerunFromToolBlock sub-message cut tuId=${targetToolUseId.take(12)} keepCount=${row.sortOrder + 1} partIdx=$cutPartIdx trimmedRow=${row.id.take(8)}")
                }

                // T149 parity: revoke memory writes in the dropped range.
                revokeMemoryWritesInDeletedMessages(deletedMessages)

                // Trim the UI in-memory (same approach as retryFromMessage's
                // `_messages.value = retainedHead`, which doesn't reload from
                // DB and so doesn't disturb compact-marker graying): keep the
                // target assistant message with only its blocks BEFORE the
                // target, and drop every later message. Block trim mirrors the
                // parts trim above so UI ↔ history stay in lockstep.
                withContext(Dispatchers.Main) {
                    val cur = _messages.value
                    val ai = cur.indexOfFirst { it.id == assistantMessageId }
                    if (ai >= 0) {
                        val keptBlocks = cur[ai].toolBlocks.take(blockIdx)
                        if (keptBlocks.isEmpty()) {
                            // [T-android-rerun-from-tool-deletes-earlier-turns]
                            // Target was the first block of its bubble — the DB
                            // side dropped the whole row (cutPartIdx==0). Drop
                            // the bubble in the UI too instead of leaving an
                            // empty assistant message; earlier bubbles (the
                            // turns that precede this one) are preserved by
                            // subList(0, ai).
                            _messages.value = cur.subList(0, ai).toList()
                        } else {
                            // Recompute `content` from the surviving text blocks
                            // so it doesn't keep text the renderer just dropped.
                            // The chat list renders ordering from toolBlocks, but
                            // `content` feeds previews / copy, so keep it in sync.
                            val keptText = keptBlocks.filter { it.isText }
                                .joinToString("") { it.content }
                            val trimmed = cur[ai].copy(
                                content = keptText,
                                toolBlocks = keptBlocks,
                                isStreaming = false,
                            )
                            _messages.value = cur.subList(0, ai).toList() + trimmed
                        }
                    }
                }
                val keptIds = _messages.value.mapTo(mutableSetOf()) { it.id }
                retainStreamFlushStates(keptIds)
                if (_streamingById.value.isNotEmpty()) {
                    _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
                }

                // Rebuild only the bounded model context from the persisted tail.
                awaitBoundedHistoryRebuild(sid)
                // [T-truncation-digest-clear] rerunFromToolBlock 也是截断：
                // digest 摘录里被裁行（含本次删除区间）的痕迹清空防注入。
                clearHistoryDigestState()
                // [T-b1-truncate-compact-reconcile] 截断点落在摘要区间内时，
                // 压缩标记的锚行已被删除 —— 不对账的话摘要仍在描述不存在的轮次。
                reconcileCompactMarkerAfterTruncation()

                streamLaunched = runRerunStreamTail(initialProvider, "rerunFromToolBlock")
            } finally {
                if (!streamLaunched) {
                    AppLogger.info(TAG_STREAM, "rerunFromToolBlock _isStreaming=false (setup aborted)")
                    _isStreaming.value = false
                }
            }
        }
        return true
    }

    /**
     * Retry from a specific user message: truncate all messages after it
     * (including the assistant response), rebuild agent history, and resend.
     * Mirrors iOS's edit/retry behavior — no duplicate user messages.
     */
    override fun retryFromMessage(messageId: String) {
        if (_isStreaming.value) return
        _canResume.value = false
        val messages = _messages.value
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        val message = messages[index]
        // [T-android-tool-autoscroll] Start-of-turn snap — see resume().
        _forceScrollToBottom.tryEmit(Unit)
        if (message.role != "user" || message.content.isBlank()) return

        val initialProvider = currentProvider
        if (initialProvider == null) {
            _error.value = "No provider configured"
            return
        }
        val provider: LLMProvider = initialProvider
        _error.value = null

        // T149: snapshot messages about to be truncated so we can revoke any
        // memory_write tool blocks they contain. Without this, a retry leaves
        // the on-disk daily log with entries the user has just rewound past.
        // [T-android-bubble-anchor-drain] Keep through the LAST bubble of a
        // merged queue batch: they share one persisted row, and retry keeps
        // that row — leaving a sibling bubble behind would show the user text
        // the model no longer has.
        val keepThrough = BubbleRowLocator.groupSpan(messages, index).last
        val deletedMessages = messages.subList(keepThrough + 1, messages.size).toList()

        // Truncate UI messages: keep up to and including this user message.
        // T189: if the retried bubble was still in the queued state (manual
        // retry of a queued message before resumeQueueAfterCancel's grace
        // window — or fallback when auto-resume is disabled), flip it out of
        // queued visuals and drop its queue entry so the upcoming send
        // doesn't double up against a later auto-drain.
        val retainedHead = messages.subList(0, keepThrough + 1).map { m ->
            if (m.id == messageId && m.isQueued) {
                // [T-queue-disk-persistence] forgetQueuedPrompt 同时撤掉磁盘
                // 镜像，否则重启会给这条已被重试的气泡还原出幽灵排队项。
                m.queuedPromptId?.let { pid -> forgetQueuedPrompt(pid) }
                m.copy(isQueued = false, queuedPromptId = null)
            } else m
        }
        _messages.value = retainedHead
        // T-streaming-side-channel: scrub stream deltas pointing at
        // messages we just truncated so they can't resurface later.
        val keptIds = retainedHead.mapTo(mutableSetOf()) { it.id }
        retainStreamFlushStates(keptIds)
        if (_streamingById.value.isNotEmpty()) {
            _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
        }

        revokeMemoryWritesInDeletedMessages(deletedMessages)

        // T145: claim the streaming flag SYNCHRONOUSLY so a rapid second tap
        // (or any concurrent send/retry attempt) is rejected by the entry
        // guard. Previously this was set inside the suspended outer launch,
        // leaving a multi-second window during DB cleanup + OAuth refresh
        // where two retries could slip through and spawn duplicate streamJobs.
        // The orphaned first job's `_isStreaming = false` at completion would
        // then flip the UI to "stopped" while the second job was still running.
        AppLogger.info(TAG_STREAM, "retry _isStreaming=true (sync, sid=$activeSessionId)")
        _isStreaming.value = true

        viewModelScope.launch {
            // If setup throws before the inner streamJob is launched, the
            // streaming flag would be stuck true forever. Reset on the
            // unhappy paths; happy path resets in the streamJob's tail.
            var streamLaunched = false
            try {
            // [T-truncation-critical-section] 重试是历史行写入者：它重发 user 行、并按
            // 同一段 sort_order 区间删行，剪裁在飞时不等就会被剪裁的
            // `deleteMessagesAfter(sort_order >= cutoff)` 连新行一起删掉
            // （T-delete-from-here-inflight 的现场）。_isStreaming 上面已同步置位，
            // 等待期间不会有新的剪裁插进来；剪裁侧从不等待写入者，无死锁。
            TruncationInterlock.awaitIdle(truncationJob)
            val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId

            // Find the DB sort_order cutoff for this user message.
            // [T-android-bubble-anchor] 先按气泡**自己的**落库行定位（[BubbleRowLocator]）：
            // 气泡与 DB 行不是 1:1——合成的 <system-reminder> 行没有气泡，纯图片轮有
            // 气泡却没有文本行，"数第 N 个可见用户行"就在这些地方错位。上游 1.14 的
            // 落点：ChatViewModel.kt:8071-8077（locateUserRow + logBubbleAnchor +
            // cutoffFor），合并时整段丢了。
            val located = BubbleRowLocator.locateUserRow(messages, index, chatRepository.loadMessages(sid))
            logBubbleAnchor("retry", messageId, located)
            val bubbleCutoff = BubbleRowLocator.cutoffFor(located, keepBubble = true)
            // [T-retry-cutoff-row-anchor] 锚点 = 目标消息**自身的 DB 行**（用户
            // 消息与 DB 行 1:1，id 全局唯一）。旧锚点用「UI 第 N 个用户消息」序号
            // 去 DB 数「有可见文本的用户行」——纯图片/无文本头的用户消息让两侧
            // 序号错位，visibleUserCutoff 返回 null → cutoffSortOrder=-1 → 不删
            // 任何行 → runAgentLoop 开头从 DB 重建出完整旧历史（含出错轮），模型
            // 接到的就是「上一次出错的上下文」。sortOrderOf 直接命中点的那一行，
            // 永不错位；只有气泡没有链接行（未落库的排队占位）时才走这条回退。
            val visibleUserIndex = messages.subList(0, index + 1).count { it.role == "user" } - 1
            val targetRowSort = runCatching { chatRepository.dao.sortOrderOf(messageId) }.getOrNull()
            val cutoffSortOrder = when {
                bubbleCutoff >= 0 -> bubbleCutoff
                targetRowSort != null -> targetRowSort + 1
                else -> visibleUserCutoff(sid, visibleUserIndex)?.let { it.sortOrder + 1 } ?: -1
            }
            if (cutoffSortOrder >= 0) {
                chatRepository.deleteMessagesAfter(sid, cutoffSortOrder)
            }

            awaitBoundedHistoryRebuild(sid)
            // [T-truncation-digest-clear] 重试删掉了出错轮；digest 摘录里可能
            // 还有更早被窗口裁掉、且已被本次截断删除的行——清空防注入。
            clearHistoryDigestState()

            // [T-p1-context-marker-reconcile] 截断后对账压缩标记：摘要描述的
            // 轮次刚被重试删掉时，丢弃标记让全量历史流动（否则模型以为做了
            // 已不存在的工-作）。
            reconcileCompactMarkerAfterTruncation()

            streamLaunched = runRerunStreamTail(provider, "retryFromMessage")
            } finally {
                if (!streamLaunched) {
                    AppLogger.info(TAG_STREAM, "retry _isStreaming=false (setup aborted)")
                    _isStreaming.value = false
                }
            }
        }
    }


    /**
     * [T-android-delete-from-here] Resolve the DB `sort_order` to cut at so
     * that [index] and everything after it is removed.
     *
     * Anchoring is by visible-user-message ordinal rather than by row id
     * because the UI list and the persisted rows are not 1:1 — see
     * [deleteFromMessage]'s note on synthetic `<system-reminder>` rows.
     *
     * When the target is an ASSISTANT message the cut is anchored to the user
     * turn it belongs to: we find the last visible user message at or before
     * [index], cut just after it, and thereby drop the assistant reply and
     * everything following. Returns -1 when no anchor can be resolved, which
     * the caller treats as "leave the DB alone".
     */
    internal suspend fun resolveDeleteCutoffSortOrder(
        sid: String,
        messages: List<ChatMessage>,
        index: Int,
        target: ChatMessage,
    ): Int {
        // [T-android-bubble-anchor] A user target is cut AT its own row. An
        // assistant target anchors to the user turn it answers — the last
        // user bubble at or before it — and is cut just AFTER that row,
        // keeping the question and removing the reply onward. Rows are found
        // by id, not by counting: the chat and the persisted rows are not 1:1
        // (synthetic <system-reminder> rows have no bubble; an image-only turn
        // has a bubble but no text part). 上游 1.14 ChatViewModel.kt:8224-8237。
        val anchorIdx = if (target.role == "user") index
        else messages.subList(0, index + 1).indexOfLast { it.role == "user" }
        // No user turn at or before the target (e.g. deleting from a leading
        // assistant/system row): everything goes.
        if (anchorIdx < 0) return 0
        val located = BubbleRowLocator.locateUserRow(messages, anchorIdx, chatRepository.loadMessages(sid))
        logBubbleAnchor("delete", messages[anchorIdx].id, located)
        val bubbleCutoff = BubbleRowLocator.cutoffFor(located, keepBubble = target.role != "user")
        if (bubbleCutoff >= 0) return bubbleCutoff
        // [T-retry-cutoff-row-anchor] 回退路径：气泡没有链接行（未落库的排队占位）时
        // 才回到「数可见用户行 → 取那一行的 sort_order」的旧口径。
        val visibleUserIndex = messages.subList(0, anchorIdx + 1).count { it.role == "user" } - 1
        if (visibleUserIndex < 0) return 0
        val anchor = visibleUserCutoff(sid, visibleUserIndex) ?: return -1
        return if (target.role == "user") anchor.sortOrder else anchor.sortOrder + 1
    }



    /**
     * T187: leave edit mode without sending. Just clears the id flag —
     * caller (ChatScreen) is responsible for clearing inputText. iOS
     * parity: AIChatViewModel.cancelEdit (L2522).
     */
    fun cancelEdit() {
        if (_editingMessageId.value != null) {
            AppLogger.info(TAG_STREAM, "✏️ cancelEdit")
            // [T-android-edit-loses-attachments] Drop the attachments
            // editMessage restored, mirroring how the caller clears the text.
            //
            // Symmetry with editMessage is the whole point: it REPLACES the
            // composer's attachments with the edited message's, so leaving
            // them behind on cancel would strand files the user never picked
            // in a composer they thought they had backed out of — and the next
            // ordinary send would silently attach them.
            //
            // Guarded by the same non-null check as the log so a stray call
            // outside edit mode cannot wipe a draft's real attachments.
            _attachments.value = emptyList()
        }
        _editingMessageId.value = null
    }


    /**
     * Enqueue a prompt to be injected into the currently running agent loop.
     * The message appears immediately in the chat with isQueued=true; when the
     * current agent loop finishes, drainQueuedPrompts() consumes the queue.
     * Mirrors iOS AIChatViewModel.enqueuePrompt().
     *
     * [T-scheduled-tool-prefill] Returns the queued prompt's id, or null when
     * the queue refused it (blank prompt, nothing streaming any more). The
     * caller needs that answer: upstream's send() reports Queued vs
     * `enqueue_declined` off the queue length, which is wrong for a scheduled
     * fire — a newer fire of the same task REPLACES an older pending one, so
     * the length stays put while the prompt was in fact accepted.
     */
    fun enqueuePrompt(
        text: String,
        origin: QueuedPromptOrigin = QueuedPromptOrigin.USER,
        // [T-scheduled-tool-prefill] Runs as the first turn of whichever loop
        // eventually carries this prompt (drain or mid-loop injection).
        prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(),
    ): String? {
        val trimmed = text.trim()
        // [T-p2-gentle-injection] Only a USER prompt owns the composer's staged
        // attachments. A programmatic prompt arriving while the user has files
        // staged must not walk off with them (and must not clear them below).
        val pendingAttachments = if (origin == QueuedPromptOrigin.USER) _attachments.value else emptyList()
        if ((trimmed.isBlank() && pendingAttachments.isEmpty()) || !_isStreaming.value) return null

        val prompt = QueuedPrompt(
            id = "queued_${System.currentTimeMillis()}_${(Math.random() * 1_000_000).toInt()}",
            text = trimmed,
            attachments = pendingAttachments,
            origin = origin,
            prefill = prefill,
            // [T-scheduled-preemptive-insert] Recognise a scheduled fire by its
            // envelope, and only when it did not come from the composer: a user
            // typing the tag by hand does not become a scheduled fire.
            scheduledTaskId = if (origin == QueuedPromptOrigin.PROGRAMMATIC) {
                com.openminis.app.scheduled.ScheduledTaskMarker.parse(trimmed)?.taskId
            } else null,
        )
        // [T-scheduled-preemptive-insert] A newer undelivered fire of the same
        // task replaces the older one (queue entry and its dashed bubble): two
        // pending fires of one task would run the same check twice in a row, and
        // the newer one carries the fresher prefilled output.
        prompt.scheduledTaskId?.let { taskId ->
            val superseded = QueuedPromptBatching.supersededBy(_promptQueue.value, taskId).toSet()
            if (superseded.isNotEmpty()) {
                _promptQueue.value = _promptQueue.value.filterNot { it.id in superseded }
                _messages.value = _messages.value.filterNot { it.queuedPromptId in superseded }
                Log.i(TAG, "Scheduled task $taskId fired again before delivery — replaced ${superseded.size} older queued fire(s)")
            }
        }
        _promptQueue.value = _promptQueue.value + prompt
        mirrorQueuedPromptToDisk(prompt) // [T-queue-disk-persistence]

        val attachmentNames = pendingAttachments.map { it.fileName }
        val imageUris = pendingAttachments.filter { it.isImage }.map { it.uri }
        val attachmentUris = pendingAttachments.filterNot { it.isImage }.map { it.uri }
        val chatMsg = ChatMessage(
            id = "queued_msg_${prompt.id}",
            role = "user",
            content = trimmed,
            imageUris = imageUris,
            attachmentNames = attachmentNames,
            attachmentUris = attachmentUris,
            isQueued = true,
            queuedPromptId = prompt.id,
        )
        _messages.value = trimLoadedWindow(_messages.value + chatMsg)
        if (origin == QueuedPromptOrigin.USER) clearAttachments()
        Log.i(TAG, "Enqueued prompt (${trimmed.length}ch, ${pendingAttachments.size} attachments, origin=$origin), queue=${_promptQueue.value.size}")
        return prompt.id
    }

    /** Remove a queued prompt and its chat message by prompt id. */
    fun removeQueuedPrompt(promptId: String) = removeQueuedPromptWithDiskMirror(promptId)

    /** Withdraw a queued message before it gets injected into the agent loop. */
    fun withdrawQueuedMessage(messageId: String) = withdrawQueuedMessageWithDiskMirror(messageId)

    /**
     * [T-queue-abort-tool] 长按排队消息 → 「中止当前工具并立即插入」：
     * 杀掉运行中 run 登记的宿主子进程（当前工具以失败结果返回，协程存活），
     * 工具边界检查点随即将本排队消息注入为独立新轮。无运行中 run / 无在飞
     * 工具时是 no-op——注入点前移已保证排队消息在下一个工具边界进入，
     * 重复中止不会误杀刚启动的新工具之后的进程。
     */
    fun abortRunningToolAndInject() {
        if (_promptQueue.value.isEmpty()) return
        val run = streamJob?.let { com.openminis.app.service.ActiveRunRegistry.current(it) }
            ?: com.openminis.app.service.ActiveRunRegistry.current(activeSessionId)
        if (run == null || run.isStopped) return
        if (run.currentToolSnapshot() == null) return
        AppLogger.info(
            TAG_STREAM,
            "📨[QueueAbortTool] aborting current tool ${run.currentToolName} to inject ${_promptQueue.value.size} queued prompt(s)",
        )
        run.abortCurrentTool()
    }

    /** [T-queue-abort-tool] 当前在飞工具快照（菜单项可用性判定）；无 run/无工具返回 null。 */
    fun currentRunningToolSnapshot(): com.openminis.app.service.ActiveRun.CurrentTool? {
        if (!_isStreaming.value) return null
        val run = streamJob?.let { com.openminis.app.service.ActiveRunRegistry.current(it) }
            ?: com.openminis.app.service.ActiveRunRegistry.current(activeSessionId)
        return run?.takeIf { !it.isStopped }?.currentToolSnapshot()
    }

    /**
     * [T-queue-shelved] 长按搁置气泡 → 「重新排队」：shelved 翻回 pending，
     * 恢复正常的注入/drain 流转。磁盘镜像同步（重启后不自动重发的语义随
     * shelved 翻转解除）。
     */
    fun requeueShelvedPrompt(promptId: String) {
        _promptQueue.value = _promptQueue.value.map {
            if (it.id == promptId) it.copy(shelved = false) else it
        }
        // [T-queue-mirror-session-binding] sid 在协程外快照：dispatch 时刻的
        // activeSessionId 可能已是切换后的会话，镜像会翻错会话的记录（
        // 静默 no-op，内存与磁盘分叉）。镜像走 tracked launch——翻回 pending
        // 后可能被消费，confirm 的 await 要能等到这次写。
        val sid = activeSessionId
        val appContext = context.applicationContext
        com.openminis.app.queue.PromptQueueBridge.launchMirrorWrite(
            appContext.filesDir, viewModelScope,
        ) {
            runCatching {
                com.openminis.app.queue.PromptQueueBridge.mirrorShelved(
                    appContext, sid, promptId, shelved = false,
                )
            }
        }
        AppLogger.info(TAG_STREAM, "📨[QueueShelved] requeued $promptId (pending again)")
    }

    /**
     * [T-android-queued-message-interrupt-on-toolclose] Mid-tool-loop
     * interrupt: take everything in [_promptQueue] right now, finalize the
     * just-finished assistant bubble in the UI, persist a fresh user
     * message carrying the queued text + attachments, append an assistant
     * "bridge" entry into [agentHistory] (so Anthropic's
     * mergeConsecutiveSameRole doesn't fold the queued user msg into the
     * preceding tool_result), and spawn a new assistant placeholder for
     * the next iteration's response.
     *
     * Returns an [InjectedTurn] carrying the new assistantId (which the
     * caller swaps into its loop-scope `assistantId` before `continue`-ing
     * the agent loop), or `null` if every queued prompt was empty after
     * attachment processing (caller falls through to a normal next-turn
     * dispatch in that case).
     *
     * Mirrors iOS `injectQueuedPromptsAsNewTurn`
     * (AIChatViewModel.swift:2794). Unlike iOS we don't persist the bridge
     * entry — its sole purpose is to break up the consecutive-user run for
     * the next API call; chat history reconstruction would just hide it.
     */
    internal data class InjectedTurn(
        val newAssistantId: String,
        /**
         * [T-scheduled-tool-prefill] 被注入提示词携带的预置工具调用：循环把它们作为
         * **下一轮的第一回合**直接"照着跑"，而不是先问模型（见 runAgentLoop 的
         * pendingScriptedTurn）。上游 1.14 的 InjectedTurn 就是这个形状。
         */
        val prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(),
    )



    // [T-android-submit-outcome] 漏斗现在回一个 SubmitOutcome；端口接口这一格仍旧
    // 是「发出去就行」，所以值在这里丢掉（上游同一处也是 Unit 语义的调用方）。
    override fun sendMessage(text: String) {
        sendMessage(text, skipContextCheck = false)
    }



    /**
     * Show a transient error on the last assistant message while keeping isStreaming=true
     * so the "thinking" indicator and streaming UI stay intact during auto-retry countdowns.
     * Mirrors iOS streamWithAutoRetry: `chatMessage?.error = desc` without dropping the loop.
     */
    internal fun setTransientInlineError(errorText: String) {
        val msgs = _messages.value.toMutableList()
        val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
        if (lastAssistantIdx < 0) return
        val msg = msgs[lastAssistantIdx]
        msgs[lastAssistantIdx] = msg.copy(error = errorText)
        _messages.value = msgs
    }

    /** Clear any inline error on the last assistant message (used after successful retry). */
    internal fun clearInlineError() {
        val msgs = _messages.value.toMutableList()
        val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
        if (lastAssistantIdx < 0) return
        val msg = msgs[lastAssistantIdx]
        if (msg.error == null) return
        msgs[lastAssistantIdx] = msg.copy(error = null)
        _messages.value = msgs
        // [T-error-persist-android] Clear the persisted sticker too, so a
        // recovered turn doesn't resurrect the error banner on the next reload.
        // Clear by the message's source DB rows when known (the in-memory bubble
        // maps to one or more persisted rows via sourceDbIds); fall back to the
        // last-assistant-row update otherwise.
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isNotEmpty()) {
            val dbIds = msg.sourceDbIds
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    if (dbIds.isNotEmpty()) {
                        dbIds.forEach { chatRepository.updateMessageErrorInfo(it, null) }
                    } else {
                        chatRepository.updateLastAssistantError(sid, null)
                    }
                } catch (e: Exception) { Log.w(TAG, "clear error_info failed: ${e.message}") }
            }
        }
    }

    /**
     * [T-error-persist-android] Fire-and-forget: clear the persisted error
     * sticker on the session's last assistant row. Called from the resume / retry
     * entrypoints that drop the in-memory error but don't go through
     * [clearInlineError], so a recovered turn can't merge-resurrect the old
     * banner on the next reload. No-op when there's no session/row yet.
     */
    internal fun clearPersistedLastAssistantError() {
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try { chatRepository.updateLastAssistantError(sid, null) }
            catch (e: Exception) { Log.w(TAG, "clear error_info (persisted) failed: ${e.message}") }
        }
    }


    /**
     * Unwrap exceptions thrown inside callbackFlow.
     * callbackFlow wraps internal throws into CancellationException(cause=original).
     * This extracts the original LLMError if present.
     */

    internal fun unwrapFlowException(e: Throwable): Throwable {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is com.openminis.app.data.model.LLMError) return cause
            cause = cause.cause
        }
        return e
    }

    /**
     * Compute max output tokens that fits within the remaining context window.
     * Logic mirrors iOS's dynamicMaxTokens():
     *   result = min(provider.defaultMaxTokens, max(contextWindow - inputTokens, MIN_MAX_TOKENS))
     *
     * @param provider The current LLM provider (carries defaultMaxTokens).
     * @param lastContextTokens API-reported input token count from the last call (0 = first call).
     */
    internal fun dynamicMaxTokens(provider: LLMProvider, lastContextTokens: Int = 0): Int {
        val model = currentModel ?: return minOf(GLOBAL_MAX_TOKENS_CEILING, provider.defaultMaxOutputTokens)
        // Ceiling: min(global cap, model.maxOutputTokens-or-provider-default).
        // The global cap means we never send more than 128K regardless of
        // what the model claims it can output.
        val maxOutputCeiling = minOf(GLOBAL_MAX_TOKENS_CEILING, provider.effectiveMaxOutputTokens(model))
        // Context window: model.contextWindow if known, else the shared
        // model-id heuristic. [T-anthropic-context-window] Route through
        // LLMModel.contextWindowTokens so the corrected Claude-1M / Gemini-1M
        // values apply here too, instead of the stale local "everything 200K"
        // copy that under-reported modern Claude/Gemini windows.
        val contextWindow = model.contextWindowTokens
        if (contextWindow <= 0) return maxOutputCeiling
        val inputTokens = if (lastContextTokens > 0) lastContextTokens else 0
        val remaining = contextWindow - inputTokens
        val clamped = maxOf(remaining, MIN_MAX_TOKENS)
        val result = minOf(maxOutputCeiling, clamped)
        if (result < maxOutputCeiling) {
            android.util.Log.i(TAG, "dynamicMaxTokens: $result (remaining=$remaining, ceiling=$maxOutputCeiling, window=$contextWindow, input=$inputTokens, model=${model.id})")
        }
        return result
    }

    // ─── Context Window Offload ──────────────────────────────────────────────
    //
    // Mirrors iOS `AIChatViewModel.swift`:
    //   - estimateContextTokens()        (line 7451)
    //   - offloadContextIfNeeded()       (line 7481)
    // Per-tool writers live in [com.openminis.app.data.ContextOffload].
    //
    // The agent loop calls [offloadContextIfNeeded] once per turn just before
    // the next API call. When token usage crosses the policy threshold, large
    // tool outputs in older messages are written to disk under
    // `filesDir/minis-sessions/<sid>/offloads/tools/` and replaced in
    // [agentHistory] by `[CONTEXT OFFLOADED] … <linux path>` stubs. The model
    // can later `file_read` the path to retrieve the original content.
    //
    // Why this matters: without offloading, a session that runs many large
    // shell tools fills the context window and either trips compact (lossy)
    // or hits the model's context-exhausted error. Offload is lossless —
    // the data still exists, just on disk instead of in-prompt.

    /**
     * Char-based fallback estimate when the API hasn't reported a token
     * baseline yet (first call in a turn). Mirrors iOS line 7451.
     *
     * Uses ~3.5 chars per token for mixed text + adds the tokenizer's
     * image-aware count for image bytes. Underestimates JSON-heavy tool
     * inputs slightly but is adequate as a "should we offload" gate —
     * offload itself uses precise [BPETokenizer.countTokens] per-part
     * for the candidate ranking.
     */
    internal fun estimateContextTokens(): Int {
        var totalChars = 0
        var imageTokens = 0
        for (msg in agentHistory) {
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.Text -> totalChars += part.text.length
                    is AgentContentPart.ToolUse -> totalChars += part.input.toString().length
                    is AgentContentPart.ToolResult -> {
                        totalChars += part.content.length
                        part.imageData?.let { imageTokens += BPETokenizer.countImageTokens(it) }
                    }
                    is AgentContentPart.ImageData -> {
                        imageTokens += BPETokenizer.countImageTokens(part.data)
                    }
                }
            }
        }
        return (totalChars / 3.5).toInt() + imageTokens
    }

    /**
     * Approximate token count for a single agent content part. Used to rank
     * offload candidates by size. Matches iOS `BPETokenizer.countPartTokens`
     * — text uses BPE, images use the grid-cell heuristic.
     */
    internal fun countPartTokens(part: AgentContentPart): Int = when (part) {
        is AgentContentPart.Text -> BPETokenizer.countTokens(part.text)
        is AgentContentPart.ToolUse -> BPETokenizer.countTokens(part.input.toString())
        is AgentContentPart.ToolResult -> {
            BPETokenizer.countTokens(part.content) +
                (part.imageData?.let { BPETokenizer.countImageTokens(it) } ?: 0)
        }
        is AgentContentPart.ImageData -> BPETokenizer.countImageTokens(part.data)
    }

    /**
     * Offload candidate descriptor. `msgIdx` and `partIdx` index back into
     * [agentHistory] so we can mutate the part in place after writing the
     * stub to disk.
     */
    internal data class OffloadCandidate(
        val msgIdx: Int,
        val partIdx: Int,
        val tokens: Int,
        val bytes: Int,
        val toolId: String,
        val toolName: String,
    )



    /**
     * Finalize the current assistant message when [runAgentLoop] hits the
     * MAX_AGENT_TURNS ceiling. Drops the streaming/awaiting flags so the
     * "thinking" indicator clears, writes an inline error explaining *why*
     * we stopped, and arms canResume so the user can continue from here.
     * Mirrors iOS AIChatViewModel.swift:4922-4929 pattern (canResume + error).
     */
    internal fun finalizeAtTurnLimit(
        assistantId: String,
        text: String,
        blocks: List<AssistantBlock>,
    ) {
        updateAssistantMessage(
            assistantId, text, false, blocks,
            isAwaitingModelResponse = false,
        )
        // [T-android-thinking-indicator-linger] updateAssistantMessage drains
        // _streamingById[assistantId] above, but the agent loop ran on
        // Dispatchers.IO while this finalize hops to Main — a late streaming
        // delta can re-add the side-channel entry AFTER the drain, and since
        // the loop has now exited no further isStreaming=false write will ever
        // clear it. mergeStreamingOverlay (ChatScreen) forces isStreaming=true
        // on any message with a side-channel entry, so that orphan keeps the
        // "thinking" row alive forever. Defensively drop the entry here as the
        // last Main-thread write of this turn.
        // [T-android-stream-flush-review] Cancel the trailing flush too, so it
        // can't re-add this orphan entry after we drop it on the error path.
        clearStreamFlushState(assistantId)
        if (_streamingById.value.containsKey(assistantId)) {
            _streamingById.value = _streamingById.value - assistantId
        }
        setInlineError(
            "Stopped after $MAX_AGENT_TURNS agent turns to prevent runaway " +
            "tool use. The model kept calling tools without finishing — tap " +
            "Resume to continue from here, or send a new message to start over.",
        )
        // [T-android-group-pause-badge-restamp] A LIVE interruption just
        // happened: this is a real entry into the paused state, so the
        // badge's 24h freshness stamp must be refreshed. Cancel any
        // unconsumed re-detection mark left by a prior load so it cannot
        // suppress the re-stamp here.
        markLiveInterruption()
        _canResume.value = true
    }

    /**
     * Instance entry point used by the tool-dispatch path. The real logic lives
     * in the companion so tests can reach it without a ChatViewModel.
     */
    internal fun preflightValidateToolCall(
        name: String,
        args: JSONObject,
        tools: List<AgentToolDefinition>,
    ): String? = preflightValidateToolCallImpl(name, args, tools)


    internal suspend fun oneShotAsk(system: String, user: String): String {
        val provider = currentProvider ?: throw IllegalStateException("no provider")
        val buf = StringBuilder()
        provider.streamMessage(
            messages = listOf(LLMMessage(LLMMessage.Role.USER, user)),
            systemPrompt = system,
            maxTokens = 2048,
            temperature = samplingTemperature(_activeEntryId.value),
            tools = emptyList(),
            thinkingLevel = ThinkingLevel.OFF,
        ).collect { chunk ->
            if (chunk is LLMStreamChunk.Text) buf.append(chunk.text)
        }
        return buf.toString().ifBlank { "(empty reasoning response)" }
    }

    /**
     * [T-android-vision-group / GH#182] Placeholder text for an image the CURRENT
     * main model can't natively see, to be carried on the outgoing image part and
     * substituted by the provider's T264 branch. Returns null when the main model
     * has native vision (pixels are attached, no placeholder needed) OR no Vision
     * Group is configured (provider falls back to its historical literal). When a
     * Vision Group IS configured, returns a hint naming [path] and steering the
     * model to call read_image — closing the loop with executeReadImageTool.
     */
    internal fun visionPlaceholderFor(path: String?): String? {
        if (currentModelHasNativeVision) return null
        val visionGroupConfigured = com.openminis.app.tools.VisionGroupResolver.isConfigured(providerRepository, context)
        if (!visionGroupConfigured) return null
        return com.openminis.app.tools.VisionGroupResolver.noVisionImagePlaceholder(path, visionGroupConfigured)
    }

    /**
     * [T-ctx-warmup-fit] The estimator ratio in force for the current model —
     * our analogue of upstream's `contextCalibrationRatio()`
     * ([com.openminis.app.data.TokenUsageCalibration] keeps the per-model
     * scale; 1.0 when the model is uncalibrated or unknown).
     *
     * [T-android-offload-scan-range] internal 而非 private：offload 的排序与计账
     * （ChatViewModelOffloadExt.offloadContextIfNeeded，本移植把它拆进了 ext 文件）
     * 必须用同一条校准比，否则 freed-tokens 与被扣减的 usage 不在一个尺度上。
     */
    internal fun contextCalibrationRatio(modelId: String? = currentModel?.id): Double =
        ContextSizeMeter.ratioFor(modelId, com.openminis.app.data.TokenUsageCalibration.snapshot(), lastLearnedCalibration)

    /**
     * [T-android-image-downscale-parity] The model-facing shape of one attached
     * image, shared by every send path: the "[attached image: <path>]" caption,
     * the pixels, then — only when the pixels were downscaled — the note giving
     * the original size, so the model knows it is looking at a reduced copy.
     * 上游 1.14 ChatViewModel.kt:15413-15421；合并时整个 helper 与它的三个调用点
     * 一起丢了，于是每条发送路径各写各的、谁也没带 note。
     *
     * 成员扩展（internal 而非上游的 private）：本移植的发送路径分散在
     * ChatViewModelXxxExt.kt 里，那些顶层扩展函数访问不到类内 private 成员。
     */
    internal fun MutableList<AgentContentPart>.addModelImage(
        part: LLMMessage.ImagePart,
        path: String?,
        note: String?,
    ) {
        if (path != null) add(AgentContentPart.Text("[attached image: $path]"))
        add(AgentContentPart.ImageData(part.data, part.mimeType, linuxPath = path, noVisionPlaceholder = visionPlaceholderFor(path)))
        if (note != null) add(AgentContentPart.Text(note))
    }



    // ─── UI Helpers ──────────────────────────────────────────────────────

    internal fun updateAssistantMessage(
        id: String,
        content: String,
        isStreaming: Boolean,
        toolBlocks: List<AssistantBlock>,
        isAwaitingModelResponse: Boolean = false,
    ) {
        streamSession.updateAssistantMessage(id, content, isStreaming, toolBlocks, isAwaitingModelResponse)
    }

    internal fun effectiveContent(id: String): String? = streamSession.effectiveContent(id)

    internal fun flushStreamingDelta(id: String) = streamSession.flushStreamingDelta(id)

    internal fun flushAllStreamingDeltas() = streamSession.flushAllStreamingDeltas()

    /**
     * Build the ordered AgentContentPart list for this turn by walking the slice of
     * `allToolBlocks` that belongs to the current turn (from `turnStartBlockIndex` to
     * the end). Text blocks become `Text`, tool_use blocks become `ToolUse` — the
     * original stream order is preserved by the list slice order. Thinking and info
     * blocks are skipped (they're persisted via `reasoningContent` or not at all).
     */
    internal fun buildTurnParts(
        allToolBlocks: List<AssistantBlock>,
        turnStartBlockIndex: Int,
        toolCallInputs: Map<String, String>,
    ): List<AgentContentPart> {
        if (turnStartBlockIndex >= allToolBlocks.size) return emptyList()
        val out = mutableListOf<AgentContentPart>()
        for (i in turnStartBlockIndex until allToolBlocks.size) {
            val block = allToolBlocks[i]
            when (block.kind) {
                "text" -> if (block.content.isNotEmpty()) {
                    out.add(AgentContentPart.Text(block.content))
                }
                "tool_use" -> {
                    val name = block.toolName
                    if (name.isBlank()) continue
                    val inputStr = toolCallInputs[block.id] ?: "{}"
                    val inputJson = try { JSONObject(inputStr) } catch (_: Exception) { JSONObject() }
                    // [T-android-gemini3-thoughtsig / #179] Carry the block's
                    // signature into the persisted/replayed ToolUse.
                    out.add(AgentContentPart.ToolUse(block.id, name, inputJson, thoughtSignature = block.thoughtSignature))
                }
                // "thinking" / "info" → not persisted in parts
                else -> { /* skip */ }
            }
        }
        return out
    }

    /**
     * Persist a single agent turn: the ordered list of AgentContentParts produced
     * in this turn (text segments and tool_use blocks interleaved in the order they
     * were emitted). Mirrors iOS's per-turn `persistAgentMessage` — one DB row per
     * turn, no cross-turn accumulation, preserving `parts` array order.
     *
     * This is the right entry point for the agent loop; the legacy
     * `persistAssistantMessage(text, usage, toolBlocks, ...)` accumulated all history
     * on every call, which caused:
     *   - Duplicate tool_use rows across turns (crashed LazyColumn key uniqueness)
     *   - Orphan tool_result detection thrashing (sanitize injecting placeholders)
     *   - Lost chronological text ↔ tool_use ordering within a single turn
     */
    /**
     * Serialize a turn's [AgentContentPart] list into the on-disk parts_json
     * shape (text + toolUse blocks). Shared by [persistAssistantTurn] (the
     * authoritative per-turn row write) and the live session-list preview
     * update ([T-android-session-last-message-live-tool-call]) so both produce
     * an identical payload that [ChatRepository.extractTextPreview] understands.
     */
    internal fun buildAssistantPartsJson(
        parts: List<AgentContentPart>,
        toolBlockMeta: Map<String, AssistantBlock>,
    ): String = buildString {
        append("[")
        parts.forEachIndexed { index, part ->
            if (index > 0) append(",")
            when (part) {
                is AgentContentPart.Text -> {
                    append("""{"type":"text","value":${escapeJson(part.text)}}""")
                }
                is AgentContentPart.ToolUse -> {
                    // Skip tool_use with blank name — upstream bug guard.
                    val name = part.name
                    if (name.isBlank()) return@forEachIndexed
                    val inputStr = part.input.toString()
                    val meta = toolBlockMeta[part.id]
                    val desc = meta?.toolTitle ?: ""
                    val pageURL = meta?.browserURL ?: ""
                    val imgPath = meta?.imageFilePath ?: ""
                    // [T-android-gemini3-thoughtsig / #179] Persist the captured
                    // signature (null-literal when absent) so it survives a session
                    // reload and can be replayed on the historical functionCall.
                    val sigJson = part.thoughtSignature?.let { escapeJson(it) } ?: "null"
                    append("""{"type":"toolUse","value":{"toolUseId":${escapeJson(part.id)},"name":${escapeJson(name)},"input":${escapeJson(inputStr)},"description":${escapeJson(desc)},"pageURL":${escapeJson(pageURL)},"imageFilePath":${escapeJson(imgPath)},"thoughtSignature":$sigJson}}""")
                }
                else -> { /* tool_result is persisted via persistToolResultMessage */ }
            }
        }
        append("]")
    }

    internal suspend fun persistAssistantTurnForRun(
        run: com.openminis.app.service.ActiveRun,
        parts: List<AgentContentPart>,
        usage: LLMUsage?,
        reasoningContent: String? = null,
        toolBlockMeta: Map<String, AssistantBlock> = emptyMap(),
        assistantText: String? = null,
        uiMessageId: String? = run.assistantMessageId,
        // [T-android-token-usage-output-speed] 本轮流式实际用掉的毫秒，随 usage
        // 行一起落库（见 persistAssistantTurn）。
        streamMs: Long = 0L,
    ): String? = run.withPersistencePermit {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            persistAssistantTurn(
                parts, usage, reasoningContent, toolBlockMeta,
                // [T-android-usage-capsule-live] 同一个气泡 id 也交给持久化步骤：它
                // 负责把 usage / completedAt 打在活气泡上（见 persistAssistantTurn）。
                uiAssistantId = uiMessageId,
                streamMs = streamMs,
            ).also { id ->
                if (id != null) {
                    withContext(Dispatchers.Main.immediate) { notePersistedUiRow(uiMessageId, id) }
                    if (assistantText != null) run.markAssistantTurnPersisted(assistantText)
                }
            }
        }
    }

    internal suspend fun persistToolResultMessageForRun(
        run: com.openminis.app.service.ActiveRun,
        parts: List<AgentContentPart>,
        targetSessionId: String = realSessionId.ifEmpty { sessionId },
        uiMessageId: String? = run.assistantMessageId,
    ): String? = run.withPersistencePermit {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            val resultIds = parts.filterIsInstance<AgentContentPart.ToolResult>().mapTo(mutableSetOf()) { it.id }
            persistToolResultMessage(parts, targetSessionId).also { dbId ->
                if (dbId != null) withContext(Dispatchers.Main.immediate) { notePersistedUiRow(uiMessageId, dbId) }
                val currentToolId = run.currentToolSnapshot()?.callId
                if (dbId != null && currentToolId != null && currentToolId in resultIds) {
                    run.setCurrentTool(null, null)
                }
            }
        }
    }

    internal suspend fun persistAssistantTurn(
        parts: List<AgentContentPart>,
        usage: LLMUsage?,
        reasoningContent: String? = null,
        toolBlockMeta: Map<String, AssistantBlock> = emptyMap(),
        // [T-android-usage-capsule-live] The UI bubble this turn belongs to,
        // so the persisted usage can be stamped onto it right away. Null keeps
        // the DB-only behaviour for callers that have no bubble.
        uiAssistantId: String? = null,
        // [T-android-token-usage-output-speed] 上游 1.14
        // `ChatViewModel.kt:14928-14937`，合并时丢了：这一轮流式花掉的墙钟毫秒，
        // 写进 usage 行的 `streamMs` 键，于是 Output Speed 能扛过重启——不像 iOS
        // 把它留在内存里、view model 一走就没了。默认 0 给没有流式耗时的调用方
        // （例如恢复 / 重跑路径）。
        streamMs: Long = 0L,
    ): String? {
        if (parts.isEmpty()) return null
        val partsJson = buildAssistantPartsJson(parts, toolBlockMeta)
        val tokenJson = usage?.let {
            """{"inputTokens":${it.inputTokens},"outputTokens":${it.outputTokens},"cacheCreationTokens":${it.cacheCreationInputTokens ?: 0},"cacheReadTokens":${it.cacheReadInputTokens ?: 0},"latestContextTokens":${it.latestContextTokens},"streamMs":$streamMs${calibrationJsonFields()}}"""
        }
        val entity = chatRepository.appendMessage(
            realSessionId.ifEmpty { sessionId }, "assistant", partsJson, tokenJson,
            reasoningContent = reasoningContent,
            // [T-token-attribution-snapshot] From the live request context, not
            // the session row — see currentModelSnapshot().
            modelSnapshot = currentModelSnapshot(),
        )

        // [T-android-usage-capsule-live] （上游 1.14 移植）把 usage capsule 的数据
        // 打在**活的**气泡上，而不只是 DB 行上。
        //
        // `ChatMessage.tokenUsage` / `completedAt` 在本移植里只有 DB 载入路径
        // （`toChatMessages()`）会填，于是刚流完的那条回复 `tokenUsage == null`，
        // `buildFlatChatItems` 不为它产出 AssistantUsage 行，点回复的空白处什么也
        // 不显示；退出会话再进来跑一次 loadSession() 从 DB 解析出来，capsule 才
        // 「又有了」——正是被报告的症状。
        //
        // 取值全部来自刚落库的 entity：`ChatTokenUsage.parse(entity.tokenUsage)`
        // 与 `entity.createdAt`，和载入路径（toChatMessages）逐字一致，所以活气泡与
        // 重载后的气泡不可能对同一轮给出不同数字。每个 tool 轮都会覆盖上一轮的戳记，
        // 与载入路径合并步骤的「合并后的气泡报告最后一轮 usage」规则相同。
        //
        // 气泡还在流式时（tool 轮那条路径）就打上也是安全的：平铺构建同时以
        // `!isStreaming` 为门，该行在末轮把 streaming 关掉前不会出现，那时最后一轮
        // 的数字已经就位。
        if (uiAssistantId != null) {
            val stampedUsage = ChatTokenUsage.parse(entity.tokenUsage)
            withContext(Dispatchers.Main) {
                var hit = false
                // [T-android-usage-capsule-live-reload] 一轮没有 usage（provider
                // 没给）时不能把同一气泡里早先 tool 轮已打上的 capsule 抹掉：载入路径
                // 的合并保持 `msg.tokenUsage ?: prev.tokenUsage`，活路径原先直接赋值
                // （可能为 null），于是 capsule 活着时消失、重载后又回来。两条路现在
                // 同一条规则。
                _messages.value = _messages.value.map { m ->
                    if (m.id == uiAssistantId) { hit = true; m.copy(tokenUsage = stampedUsage ?: m.tokenUsage, completedAt = entity.createdAt) } else m
                }
                // One line per turn. `hit=false` means the bubble id was not in
                // the list — the exact silent failure this fix exists to end.
                AppLogger.info(
                    TAG_STREAM,
                    "[UsageCapsule] live stamp ui=${uiAssistantId.take(20)} hit=$hit " +
                        "ctx=${stampedUsage?.latestContextTokens ?: -1} db=${entity.id.take(8)}",
                )
            }
        }
        return entity.id
    }


    /** Persist tool results as a user-role message (mirrors iOS behavior). */
    internal suspend fun persistToolResultMessage(
        parts: List<AgentContentPart>,
        targetSessionId: String = realSessionId.ifEmpty { sessionId },
    ): String? {
        // [T-android-duplicate-toolcall-replay] Belt-and-braces: one row must
        // never carry two toolResult parts for the same toolUseId — a replay
        // that slipped past dispatch-time refusal would otherwise duplicate
        // the persisted payload and re-enter the transcript on reload.
        val results = parts.filterIsInstance<AgentContentPart.ToolResult>()
            .distinctBy { it.id }
        if (results.isEmpty()) return null
        val partsJson = buildString {
            append("[")
            results.forEachIndexed { index, result ->
                if (index > 0) append(",")
                val snapshotText = escapeJson(result.content.lines().takeLast(30).joinToString("\n"))
                append("""{"type":"toolResult","value":{"toolUseId":${escapeJson(result.id)},"name":${escapeJson(result.name)},"output":${escapeJson(result.content)},"success":${!result.isError},"snapshot":{"type":"text","text":$snapshotText}}}""")
            }
            append("]")
        }
        val entity = chatRepository.appendMessage(targetSessionId, "user", partsJson)
        return entity.id
    }


    // ─── Legacy tool execution methods (kept for compatibility) ───────────

    fun executeMemoryWrite(argsJson: String): MemoryTools.ToolResult {
        val repo = sessionMemoryRepo()
        if (!_memoryEnabled.value) {
            return MemoryTools.ToolResult(
                "Memory writes are disabled for this session. Reads are still available. The user can re-enable writes via the /memory slash command.",
                false,
            )
        }
        val result = MemoryTools.executeMemoryWrite(argsJson, repo)
        val content = try {
            JSONObject(argsJson).optString("content", "")
        } catch (_: Exception) { "" }
        _memoryToolRecords.value = _memoryToolRecords.value + MemoryToolRecord(
            title = result.toolTitle,
            isWrite = true,
            preview = content.lines().firstOrNull { it.isNotBlank() }?.take(100) ?: "",
            output = result.output,
            writtenContent = content,
        )
        return result
    }

    fun executeMemoryGet(argsJson: String): MemoryTools.ToolResult {
        val repo = sessionMemoryRepo()
        val result = MemoryTools.executeMemoryGet(argsJson, repo)
        val keywords = try {
            JSONObject(argsJson).optString("keywords", "")
        } catch (_: Exception) { "" }
        _memoryToolRecords.value = _memoryToolRecords.value + MemoryToolRecord(
            title = result.toolTitle,
            isWrite = false,
            preview = if (keywords.isNotBlank()) "Search: $keywords" else result.output.take(100),
            output = result.output,
            keywords = keywords,
        )
        return result
    }

    suspend fun executeBrowserUse(argsJson: String): BrowserToolResult {
        val input = BrowserActionInput.parse(argsJson)
            ?: return BrowserToolResult(text = "Error: Invalid browser_use input. Required: 'action' parameter.", success = false)

        return try {
            val result = browserTabPool.execute(input)
            BrowserToolResult(
                text = result.text,
                success = result.success,
                base64Image = result.base64Image,
                imageFilePath = result.imageFilePath,
                pageURL = result.pageURL,
            )
        } catch (e: Exception) {
            BrowserToolResult(text = "Error: ${e.message}", success = false)
        }
    }

    data class BrowserToolResult(
        val text: String,
        val success: Boolean,
        val base64Image: String? = null,
        val imageFilePath: String? = null,
        val pageURL: String? = null,
    )

    // ─── Misc Helpers ────────────────────────────────────────────────────


    /** LLM-based title + category generation, mirrors iOS generateSessionTitleIfNeeded(). */
    internal var titleGenerationAttempts = 0
    internal var titleGenerationInFlight = false



    /**
     * [T-android-overlay-reply-status-34599] Pull the most recent
     * assistant text out of `_messages` and hand it to
     * [SessionActivityTracker.publishLastReply]. The tracker truncates
     * to a fixed-width excerpt and pairs it with [sessionId] so the
     * floating overlay can render a "tap to open this chat" capsule
     * after the stream completes. No-op when no assistant message has
     * content yet (e.g. fail during the very first turn).
     */
    internal fun publishOverlayReplyExcerpt(sessionId: String) {
        val snapshot = _messages.value
        val text = snapshot.asReversed().firstOrNull { msg ->
            msg.role == "assistant" && msg.content.isNotBlank()
        }?.content
        SessionActivityTracker.publishLastReply(sessionId, text)
    }

    fun shareConversationCard(options: com.openminis.app.share.ConversationCardOptions = com.openminis.app.share.ConversationCardOptions()) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                com.openminis.app.share.ConversationCardShare.share(
                    context = context,
                    title = _sessionTitle.value,
                    messages = uiMessages.value,
                    options = options,
                )
            }.onFailure {
                AppLogger.warning(TAG, "shareConversationCard failed: ${it.message}")
            }
        }
    }

    override fun cancelStream() {
        noteRunSuspended("user cancel") // [T-operation-wiring]
        val job = streamJob
        if (job == null || stoppedStreamJob === job) return
        // [T-android-stop-sibling-subagent] 上游 1.14（ChatViewModel.kt:16392-16394）
        // 的 Stop 语义在合并中丢了：停止一轮时必须带走它派生的整个 fan-out，否则
        // 用户以为停了、子 agent 还在烧 token。三条调用缺一不可，顺序也不能换：
        //   1. mute —— 已完成的同伴仍持有待投递回调，投递会把这轮重新唤醒；
        //   2. drop —— 每个 cancel 都会 drain 队列并空出名额，所以必须先清队列；
        //   3. cancelAll —— 最后才取消仍在跑的子树。
        com.openminis.app.agent.jobs.AgentJobRegistry.muteDelegationResults(activeSessionId)
        com.openminis.app.agent.jobs.AgentJobRegistry.dropQueuedDelegations(activeSessionId, "user-stopped")
        com.openminis.app.agent.jobs.AgentJobRegistry.cancelAll(activeSessionId, "user-stopped")
        // A coroutine may already be completing while its provider stream is
        // still producing callbacks. `isActive` is not a reliable stop guard:
        // cancellation can be observed before the provider unwinds. Use the
        // identity fence above and let ActiveRun.stop() make the operation
        // idempotent while cleanup persists the interrupted turn.
        val stoppedSessionId = activeSessionId
        val draftSessionId = sessionId
        val persistedSessionId = realSessionId
        val capturedSessionIds = listOf(stoppedSessionId, draftSessionId, persistedSessionId)
            .filter(String::isNotBlank).distinct()
        val capturedRun = com.openminis.app.service.ActiveRunRegistry.current(job)
        val capturedAssistantId = capturedRun?.assistantMessageId
        val capturedModelSnapshot = currentModelSnapshot()
        val interruptedLabel = context.getString(R.string.chat_response_interrupted)
        val stopCleanupScope = (context.applicationContext as com.openminis.app.MinisApp).appCoroutineScopes.io
        AppLogger.info(TAG_STREAM, "cancelStream invoked (sid=$stoppedSessionId)")
        dismissPendingUserQuestions("cancelled")
        stoppedRun = capturedRun

        // Stop owned tool/guest/host resources before asking the coroutine to
        // unwind. The session shell is per conversation; no process belonging to
        // another chat or the user's interactive terminal is touched.
        capturedRun?.let(com.openminis.app.service.ActiveRunRegistry::stopExact)
            ?: capturedSessionIds.forEach { com.openminis.app.service.ActiveRunRegistry.stop(it) }
        job.cancel(CancellationException("Stopped by user"))
        _isStreaming.value = false
        capturedSessionIds.forEach { sid -> ExecutionCoordinator.stopCurrentCommand(sid) }
        if (activeSessionId == stoppedSessionId) {
            SessionActivityTracker.clearToolRunning(com.openminis.app.service.ToolOutcome.Cancelled)
        }
        SessionActivityTracker.setInactive(stoppedSessionId)
        if (isDraft && persistedSessionId.isNotEmpty() && stoppedSessionId != draftSessionId) {
            SessionActivityTracker.setInactive(draftSessionId)
        }

        // Wait for tool callbacks/finally blocks before committing the last
        // stream delta. Otherwise a late successful tool result could overwrite
        // CANCELLED, or the text snapshot could miss its final chunk.
        stopCleanupScope.launch {
            withTimeoutOrNull(STOP_CLEANUP_JOIN_TIMEOUT_MS) { job.join() }
            withContext(NonCancellable + kotlinx.coroutines.Dispatchers.Main.immediate) {
                finishStoppedRun(
                    run = capturedRun,
                    stoppedSessionId = stoppedSessionId,
                    capturedSessionIds = capturedSessionIds,
                    capturedAssistantId = capturedAssistantId,
                    capturedModelSnapshot = capturedModelSnapshot,
                    interruptedLabel = interruptedLabel,
                )
            }
        }
    }



    /**
     * Build a JSON parts array matching the ChatRepository schema so a
     * committed interrupted-assistant turn round-trips across app restarts.
     * Only emits text parts — tool_use / tool_result paths are handled by
     * the existing persistence code in the agent loop.
     */
    internal fun buildAssistantPartsJson(parts: List<AgentContentPart>): String {
        val sb = StringBuilder("[")
        var first = true
        for (p in parts) {
            if (p !is AgentContentPart.Text) continue
            if (!first) sb.append(',') else first = false
            sb.append("""{"type":"text","value":""")
            sb.append(escapeJson(p.text))
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }


    /** Conservative eligibility: unfinished work and in-memory drafts stay pinned. */
    internal fun canEvictFromMemory(): Boolean =
        sessionLoaded.value && realSessionId.isNotBlank() && !_isStreaming.value && !_isCompacting.value &&
            streamJob?.isActive != true && compactJob?.isActive != true &&
            _inputText.value.isEmpty() && _attachments.value.isEmpty() && _pastedTexts.value.isEmpty() &&
            _editingMessageId.value == null && _promptQueue.value.isEmpty() && pendingSendText == null &&
            _pendingUserQuestions.value == null && pendingApprovals.value.isEmpty() &&
            _browserTabPoolRef?.isAgentBusy != true && _browserTabPoolRef?.isVisible != true &&
            _browserTabPoolRef?.hasActiveDownloads != true &&
            viewModelScope.coroutineContext[Job]?.children?.none { it.isActive && it !in initialScopeJobs } != false

    internal fun preserveShellOnCacheEviction() { preserveShellOnClear = true }

    // A conservative text estimate, not a process PSS measurement. Account
    // for the separately retained LLM history as well as UI tool output.
    internal fun retainedTextBytes(): Long = messageTextBytes(_messages.value) + agentHistoryBytes()

    internal fun agentHistoryBytes(): Long = ResidentWindow.bytesOf(agentHistory)

    /**
     * The chat surface is never rewritten. Tool output that goes to
     * [com.openminis.app.data.ContextOffload] is a property of the model
     * context ([agentHistory]), not of the rendered message: a pointer the UI
     * cannot resolve is a silent data loss, not a byte saving. The oversized
     * rows behind the reported OOM live in `agentHistory`, so that is the
     * only list this trims.
     */
    internal fun freezeResident() {
        if (agentHistoryBytes() <= HotWindow.RESIDENT_BYTES) return
        offloadContextIfNeeded(
            contextWindow = 200_000,
            lastContextTokens = (agentHistoryBytes() / 4L).toInt().coerceAtLeast(1),
            force = false,
        )
        trimAgentHistory()
    }

    private fun messageTextBytes(messages: List<ChatMessage>): Long = messages.sumOf { message ->
        message.content.length.toLong() * 2 + message.toolBlocks.sumOf { it.content.length.toLong() * 2 }
    }

    internal fun trimIdleBrowser() { _browserTabPoolRef?.trimIdleTabs() }

    override fun onCleared() {
        unsubscribeSafeMode?.invoke()
        unsubscribeSafeMode = null
        // [T-w1-pool-ownership] 只拆自己建的池子：helper 子会话借的是父会话的池
        // （adoptBrowserTabPool），而 store 淘汰子会话就是 ViewModelStore.clear →
        // 这里；无条件 dispose 会把父会话正在用的池子一起拆掉（跨会话池销毁）。
        if (_ownsBrowserTabPool) _browserTabPoolRef?.dispose()
        super.onCleared()
        // Tear down whichever shell was actually serving this VM. Terminate
        // both ids when the rename happened, since a draft shell may still
        // linger if the agent ran a tool before `ensureSession()`.
        if (!preserveShellOnClear) {
            ExecutionCoordinator.sessionDidTerminate(activeSessionId)
            if (activeSessionId != sessionId) ExecutionCoordinator.sessionDidTerminate(sessionId)
        }
    }

    /**
     * T-android-new-chat-empty-residue: when the user leaves the chat screen,
     * drop sessions that were materialised in the DB (e.g. via a thinking /
     * memory toggle in `ensureSession()`) but never received a real message.
     * Without this hook, tapping "New chat" → toggling a session-scoped
     * setting → exiting leaves an empty row at the top of the session list.
     *
     * Called from ChatScreen's onDispose. Gates:
     *   - realSessionId must be non-empty (a row was actually inserted)
     *   - not currently streaming (background agent work would be lost)
     *   - persisted message count == 0 (authoritative DB check — `_messages`
     *     also contains ephemeral system-info bubbles that aren't persisted,
     *     so a state-only check would over-count).
     *
     * Safe to call multiple times; the row-existence + count gates make it
     * idempotent. After deletion we release the cached VM so a stale entry
     * doesn't linger in `ChatViewModelStore`.
     */
    fun cleanupIfEmptyOnExit() {
        val sid = realSessionId
        if (sid.isEmpty()) return
        if (_isStreaming.value) return
        if (_attachments.value.isNotEmpty()) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val count = chatRepository.messageCount(sid)
                if (count > 0) return@launch
                AppLogger.info(
                    TAG,
                    "cleanupIfEmptyOnExit: deleting empty session $sid (no persisted messages)",
                )
                chatRepository.deleteSession(sid)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    ChatViewModelStore.release(sid)
                }
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "cleanupIfEmptyOnExit failed for $sid: ${t.message}")
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    internal fun escapeJson(text: String): String {
        val sb = StringBuilder("\"")
        for (c in text) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) sb.append("\\u%04x".format(c.code))
                    else sb.append(c)
                }
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    /**
     * Convert a flat list of MessageEntity into ChatMessages, merging toolResult
     * data from user-role messages back into their corresponding AssistantBlocks.
     * This mirrors iOS's toChatMessage() which reads both toolUse and toolResult parts.
     */
    /**
     * Matches a `<system-reminder>...</system-reminder>` block, including any
     * surrounding whitespace / newlines, so a part that is *only* a reminder
     * collapses to empty text instead of leaving a blank gap. DOTALL so `.`
     * spans newlines (reminders run multi-line in the cancel/resume paths).
     *
     * Only applied at the UI-render transform — agentHistory + DB rows keep
     * the raw text so the LLM continues to see the reminder on subsequent
     * turns (matches iOS, where system-reminder text is appended to
     * agentHistory/AgentMessage parts but never to the chat-list ChatMessage).
     */
    private val systemReminderRegex =
        Regex("\\s*<system-reminder>.*?</system-reminder>\\s*", RegexOption.DOT_MATCHES_ALL)

    private fun stripSystemReminders(text: String): String =
        if (!text.contains("<system-reminder>")) text
        else systemReminderRegex.replace(text, "")

    /**
     * [T-android-retry-attachment-loss] Remove the `<user-attached-files>` XML
     * inventory from a persisted text part for DISPLAY only. The XML is now
     * persisted (iOS parity) so the model keeps the file paths across retry /
     * reload, but it must never render in the user bubble — the file chips are
     * rebuilt from the mediaRef parts instead. Mirrors the index-based strip
     * already used by editMessage / the title-fallback path.
     */
    private fun stripAttachedFilesXml(text: String): String {
        val startIdx = text.indexOf("<user-attached-files>")
        if (startIdx < 0) return text
        val endTag = "</user-attached-files>"
        val endIdx = text.indexOf(endTag, startIdx)
        return if (endIdx >= 0) {
            text.substring(0, startIdx) + text.substring(endIdx + endTag.length)
        } else {
            text.substring(0, startIdx)
        }
    }

    private fun buildToolResultMap(entities: List<MessageEntity>): Map<String, ToolResultData> {
        val result = mutableMapOf<String, ToolResultData>()
        for (entity in entities) {
            if (entity.role != "user") continue
            runCatching {
                val array = org.json.JSONArray(entity.partsJson)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    if (obj.optString("type") != "toolResult") continue
                    val value = obj.getJSONObject("value")
                    val toolUseId = value.optString("toolUseId", "")
                    if (toolUseId.isNotEmpty()) {
                        result[toolUseId] = ToolResultData(
                            output = value.optString("output", ""),
                            success = value.optBoolean("success", true),
                        )
                    }
                }
            }
        }
        return result
    }

    internal fun List<MessageEntity>.toChatMessages(
        toolResultContext: List<MessageEntity> = emptyList(),
    ): List<ChatMessage> {
        // Build the result index from this page plus a small boundary context,
        // but convert UI rows from this page only. A toolUse and its toolResult
        // commonly land on opposite sides of a page boundary; decoding the
        // context without emitting it prevents the middle row from being
        // filtered or merged away.
        val toolResultMap = buildToolResultMap(this + toolResultContext)

        // Second pass: convert messages, merging tool results into blocks
        // Filter out user messages that only contain toolResult parts (no visible text)
        return mapNotNull { entity ->
            var text = ""
            val blocks = mutableListOf<AssistantBlock>()
            // T128: media attachments persisted under user messages as `mediaRef`
            // parts. Restored to file:// URIs (stable across app restarts) and
            // their original filenames so UserAttachmentList renders the same
            // tiles after a session reload.
            val restoredImageUris = mutableListOf<Uri>()
            // [T-android-paste-mediaref] Names are collected PER COLUMN and
            // concatenated image-first at the end, instead of appended to one
            // list in part order.
            //
            // UserAttachmentList splits with `allFileNames.drop(imageUris.size)`,
            // so the names list must be images-then-files regardless of the
            // order the parts appear in. That used to be automatic: attachment
            // mediaRefs were always written images-first. A pasted block breaks
            // it — it lives in the BODY, so it can precede an image part, and a
            // single in-order list would then start with a file name and shift
            // every image caption onto the wrong tile.
            val restoredImageNames = mutableListOf<String>()
            val restoredFileNames = mutableListOf<String>()
            // T150: file:// URIs of restored non-image attachments, in the
            // same order as the non-image suffix of the joined name list.
            // Powers the user-bubble file chip → FilePreviewScreen tap after
            // a session reload.
            val restoredAttachmentUris = mutableListOf<Uri>()
            var speakerName: String? = null
            var speakerVendor: String? = null

            if (entity.role == "assistant" && !entity.reasoningContent.isNullOrEmpty()) {
                blocks.add(AssistantBlock(
                    id = "thinking_restored_${entity.id}",
                    kind = "thinking",
                    content = entity.reasoningContent,
                    toolTitle = "Thinking",
                    toolStatus = ToolBlockStatus.SUCCESS,
                ))
            }

            try {
                val array = org.json.JSONArray(entity.partsJson)
                var textBlockCounter = 0
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    when (obj.optString("type")) {
                        com.openminis.app.tools.GroupChat.SPEAKER_PART -> {
                            speakerName = obj.optString("value", "").takeIf { it.isNotBlank() }
                            speakerVendor = obj.optString("vendor", "").takeIf { it.isNotBlank() }
                                ?: com.openminis.app.tools.GroupChat.vendorKey(null, speakerName)
                                    .takeIf { it != com.openminis.app.tools.GroupChat.VENDOR_UNKNOWN }
                        }
                        "text" -> {
                            val raw = obj.optString("value", "")
                            // Strip <system-reminder>...</system-reminder> blocks
                            // here only — agentHistory in memory and the DB row
                            // both keep the raw text, so the LLM still sees the
                            // reminder on subsequent turns. UI just hides it.
                            // If a part was *only* a reminder, the cleaned
                            // string is empty and we skip it so we don't render
                            // a phantom blank text block.
                            // [T-android-retry-attachment-loss] Also strip the
                            // now-persisted <user-attached-files> XML so it
                            // doesn't render in the user bubble (file chips come
                            // from mediaRef parts). The DB row + agentHistory
                            // keep the raw XML so the model still sees paths.
                            val t = stripAttachedFilesXml(stripSystemReminders(raw)).let {
                                // [T-universal-think-tag-history] Last-resort UI
                                // strip: a reasoning tag that leaked into the DB
                                // (unterminated provider stream, tool-loop splice,
                                // vendor spelling the stream parser pre-dated) must
                                // never render in the bubble. DB + agentHistory keep
                                // the raw text; this only cleans the painted copy.
                                if (entity.role == "assistant") {
                                    com.openminis.app.harness.agent.MessageTransformerChain.apply(it)
                                } else it
                            }.let {
                                if (it != raw) it.trim() else it
                            }
                            if (t.isEmpty()) continue
                            text += t
                            // For assistant messages, also push the text as a block so
                            // the renderer can preserve the original text↔tool ordering.
                            // For user messages we keep using the `text` field only.
                            if (entity.role == "assistant") {
                                blocks.add(AssistantBlock(
                                    id = "text_restored_${entity.id}_${textBlockCounter++}",
                                    kind = "text",
                                    content = t,
                                ))
                            }
                        }
                        "toolUse" -> {
                            val value = obj.getJSONObject("value")
                            val toolId = value.optString("toolUseId", "")
                            if (toolId.startsWith("thinking_")) continue
                            val toolInput = value.optString("input", "")
                            // Merge tool result output (iOS: block.content = tr.output)
                            val result = toolResultMap[toolId]
                            val pageURL = value.optString("pageURL", "").ifEmpty { null }
                            val imgPath = value.optString("imageFilePath", "").ifEmpty { null }
                            blocks.add(AssistantBlock(
                                id = toolId,
                                kind = "tool_use",
                                toolName = value.optString("name", ""),
                                toolTitle = value.optString("description", ""),
                                toolArgs = toolInput,
                                content = result?.output?.lines()?.takeLast(80)?.joinToString("\n") ?: "",
                                toolStatus = when {
                                    result == null -> ToolBlockStatus.SUCCESS
                                    !result.success && (
                                        result.output.startsWith(CANCELLED_MARKER) ||
                                            result.output.startsWith(LEGACY_CANCELLED_MARKER)
                                    ) -> ToolBlockStatus.CANCELLED
                                    result.success -> ToolBlockStatus.SUCCESS
                                    else -> ToolBlockStatus.FAILED
                                },
                                browserURL = pageURL,
                                imageFilePath = imgPath,
                                // [T-android-gemini3-thoughtsig / #179] Restore the
                                // persisted signature onto the rebuilt block.
                                thoughtSignature = value.optString("thoughtSignature", "").ifEmpty { null },
                            ))
                        }
                        "mediaRef" -> {
                            if (entity.role != "user") continue
                            val value = obj.optJSONObject("value") ?: continue
                            val rel = value.optString("relativePath", "")
                            if (rel.isEmpty()) continue
                            val file = java.io.File(mediaStore.mediaBaseDir, rel)
                            if (!file.exists()) continue
                            val mime = value.optString("mimeType", "")
                            val name = value.optString("originalFileName", "").ifEmpty { file.name }
                            // T150: branch on mime so non-image mediaRefs land
                            // in the file-chip column instead of polluting
                            // imageUris (which feeds the image gallery).
                            //
                            // [T-android-paste-mediaref] A pasted block needs no
                            // case of its own: it is text/plain, so it takes the
                            // non-image branch and renders as the same file card
                            // as an attached document — which is exactly the
                            // requested behaviour. Note this ALSO relies on
                            // parts being written images-first; a pasted ref
                            // sits in the body (possibly before an image part),
                            // so the names/uris pairing here is positional per
                            // COLUMN, not per part index, and stays consistent
                            // because each column is appended in part order.
                            if (mime.startsWith("image/")) {
                                restoredImageUris.add(Uri.fromFile(file))
                                restoredImageNames.add(name)
                            } else {
                                restoredAttachmentUris.add(Uri.fromFile(file))
                                restoredFileNames.add(name)
                            }
                        }
                        // toolResult in user messages handled in first pass above
                    }
                }
            } catch (e: Exception) {
                // T-PARTS-FALLBACK: previously this catch dumped the entire
                // partsJson into `text` as a degraded fallback. That meant
                // any malformed (or unexpectedly large) row rendered its
                // raw JSON — including any inlined base64 — as a plain
                // user/assistant bubble, which then locked up Compose's
                // StaticLayout for tens of seconds (see HangDetector report
                // for session e84882d7 / 820 KB partsJson). Replace with a
                // short, fixed-size placeholder so the row still appears
                // (so the user can delete or scroll past it) but no longer
                // pulls megabytes through the layout pass.
                Log.w(
                    TAG,
                    "toChatMessages: failed to parse partsJson for id=${entity.id} " +
                        "len=${entity.partsJson.length} role=${entity.role}: ${e.javaClass.simpleName}: ${e.message}",
                )
                text = "(message could not be parsed: ${e.javaClass.simpleName}, " +
                    "${entity.partsJson.length} bytes)"
            }

            // Skip user messages with no visible content (toolResult-only internal messages,
            // or messages that were entirely a system-reminder). A user message that is
            // *only* an image attachment (no caption) still has visible content and must
            // not be skipped — restoredImageUris carries it.
            if (entity.role == "user" && text.isBlank() && restoredImageUris.isEmpty()) return@mapNotNull null
            // Skip assistant messages that became empty after stripping system-reminders
            // and have no tool / thinking blocks to fall back on — would otherwise
            // render as a phantom blank assistant bubble.
            //
            // [T-android-error-persist-current-turn] ...unless the row carries an
            // error: that is the carrier persistTurnError inserts for a turn that
            // failed before any output, and the banner + Retry are its whole
            // point. Once retry clears the error the carrier is skipped again,
            // so a recovered turn leaves no empty bubble.
            // （上游 1.14 ChatViewModel.kt:17216-17218；合并时这一条丢了。）
            if (entity.role == "assistant" && text.isBlank() && blocks.isEmpty() &&
                entity.errorInfo.isNullOrBlank()
            ) return@mapNotNull null
            ChatMessage(
                id = entity.id,
                role = entity.role,
                content = text,
                imageUris = restoredImageUris,
                // Image names first, then file names — the invariant
                // UserAttachmentList's `drop(imageUris.size)` relies on.
                attachmentNames = restoredImageNames + restoredFileNames,
                attachmentUris = restoredAttachmentUris,
                toolBlocks = blocks,
                sourceDbIds = listOf(entity.id),
                speakerName = speakerName,
                speakerVendor = speakerVendor,
                // [T-android-usage-capsule-time] （上游 1.14 移植）只有 assistant 行
                // 带 usage；created_at 在这条路径上就是完成时刻（该行是这一轮产出
                // usage 之后才写下的）。此前这一段在本移植里整段缺位，于是每次重载
                // 会话后 usage capsule 永远不出现——capsule 的另一半（活气泡打戳）见
                // persistAssistantTurn。
                tokenUsage = if (entity.role == "assistant") {
                    ChatTokenUsage.parse(entity.tokenUsage)
                } else null,
                completedAt = if (entity.role == "assistant") entity.createdAt else null,
                // [T-error-persist-android] Restore the persisted terminal error
                // so the inline error banner + Retry button survive a reload.
                // Coalesce a blank value to null: the UI gate is `error?.let`, so
                // a non-null "" would render an empty banner. Defends against any
                // legacy/other-writer "" row.
                error = entity.errorInfo?.let { status ->
                    when (status) {
                        com.openminis.app.data.db.PersistedMessageStatus.INTERRUPTED_INFO -> context.getString(R.string.chat_response_interrupted)
                        else -> status
                    }
                }?.takeIf { it.isNotBlank() },
            )
        }.let { messages ->
            // Merge consecutive assistant messages into one:
            // agent loop persists each turn separately, but UI should show them as a single message.
            val merged = mutableListOf<ChatMessage>()
            for (msg in messages) {
                val prev = merged.lastOrNull()
                if (msg.role == "assistant" && prev?.role == "assistant" &&
                    com.openminis.app.tools.GroupChat.shouldMergeAssistantTurns(prev.speakerName, msg.speakerName)
                ) {
                    // Merge: combine tool blocks, append text, keep the last id.
                    // Deduplicate by block.id — the agent loop may persist the same tool
                    // use in multiple consecutive turns (as it carries tool state across),
                    // and duplicated ids would crash LazyColumn's key uniqueness check.
                    // Keep the LAST occurrence so the most recent status (e.g. SUCCESS with
                    // output) wins over an earlier STREAMING placeholder.
                    val seen = mutableSetOf<String>()
                    val combinedBlocks = (prev.toolBlocks + msg.toolBlocks)
                        .asReversed()
                        .filter { seen.add(it.id) }
                        .asReversed()
                    val combinedText = when {
                        prev.content.isBlank() -> msg.content
                        msg.content.isBlank() -> prev.content
                        else -> prev.content + "\n\n" + msg.content
                    }
                    merged[merged.lastIndex] = prev.copy(
                        id = msg.id,
                        content = combinedText,
                        toolBlocks = combinedBlocks,
                        // T126-marker: keep every source dbId so Phase 2.5
                        // can resolve markers that point at any of the
                        // pre-merge rows (lastCompactedMessageId is often
                        // an assistant row that gets folded into a later
                        // assistant turn).
                        sourceDbIds = prev.sourceDbIds + msg.sourceDbIds,
                        // [T-error-persist-android] The error sticker is written
                        // to the LAST assistant row of the turn, so the later row
                        // (`msg`) wins; fall back to `prev` if only it carried one.
                        error = msg.error ?: prev.error,
                        // [T-android-usage-capsule-time] （上游 1.14 移植）与上面的
                        // `id` / `error` 同一条规则：合并后的气泡代表整轮 run，所以
                        // 报告**最后一轮**的 usage 与完成时刻。逐轮相加会重复计数 ——
                        // 每轮的 latestContextTokens 本就包含前面几轮，相加会报出一个
                        // 比任何单次请求都大的上下文。
                        tokenUsage = msg.tokenUsage ?: prev.tokenUsage,
                        completedAt = msg.completedAt ?: prev.completedAt,
                    )
                } else {
                    merged.add(msg)
                }
            }
            merged
        }
    }


    /**
     * Rebuild model context from the newest persisted rows only. The database
     * remains the complete transcript; no operation may parse the whole session
     * merely to continue, retry, edit, or delete it.
     */

    internal fun appendBoundedHistory(message: LLMMessage) {
        agentHistory.add(message)
        trimAgentHistory()
    }

    internal fun appendBoundedHistoryAll(messages: Collection<LLMMessage>) {
        agentHistory.addAll(messages)
        trimAgentHistory()
    }

    /**
     * [T-b1-tooluse-json-length] Re-seal a tool_use part whose `input` was
     * mutated IN PLACE — `ToolJsonRepair.repair` does exactly that right
     * before dispatch (ChatViewModelAgentLoopExt).
     *
     * [AgentContentPart.ToolUse.inputJsonLength] is a construction-time
     * snapshot, so an in-place mutation would leave the resident-window byte
     * accounting under-reporting whatever the repair added. Rebuilding the part
     * via `copy(input = …)` re-snapshots it; nothing else about it changes.
     */
    internal fun resealToolInputInHistory(toolUseId: String, args: org.json.JSONObject) {
        for (i in agentHistory.indices.reversed()) {
            val parts = agentHistory[i].contentParts
            val idx = parts.indexOfFirst { it is AgentContentPart.ToolUse && it.id == toolUseId }
            if (idx < 0) continue
            val current = parts[idx] as AgentContentPart.ToolUse
            if (current.input === args) {
                val updated = parts.toMutableList()
                updated[idx] = current.copy(input = args)
                agentHistory[i] = agentHistory[i].copy(contentParts = updated)
            }
            return
        }
    }

    internal fun trimAgentHistory() {
        val dropped = ArrayList<LLMMessage>()
        val byCount = ResidentWindow.countCut(agentHistory, MAX_AGENT_HISTORY_MESSAGES)
        if (byCount > 0) {
            dropped.addAll(agentHistory.subList(0, byCount))
            agentHistory.subList(0, byCount).clear()
            llmHistoryStartOffset += byCount
        }
        val byBytes = ResidentWindow.byteCut(agentHistory)
        if (byBytes > 0) {
            dropped.addAll(agentHistory.subList(0, byBytes))
            agentHistory.subList(0, byBytes).clear()
            llmHistoryStartOffset += byBytes
        }
        if (dropped.isNotEmpty()) {
            rememberDigestLines(dropped.map { com.openminis.app.harness.context.HistoryDigest.fromMessage(it) })
        }
    }

    /**
     * Rebuild the request-side history from Room immediately before a new send.
     * The UI is intentionally paged, so `_messages` and the resident cache are
     * not authoritative for model memory. This also repairs a ViewModel that was
     * reopened on the tail and never had its older UI pages loaded.
     */
    internal suspend fun rebuildAgentHistoryFromDatabase(sid: String) {
        val tail = chatRepository.loadRequestHistory(sid, MAX_AGENT_HISTORY_MESSAGES)
        // [T-android-error-persist-current-turn] 错误载体（空 assistant 行 + errorInfo）
        // 是纯 UI 行：它必须留在库里给横幅/Retry 用，但绝不能进模型请求——空 assistant
        // 消息会让部分供应商 400。上游 1.14 在 5 个重建点各加了一条同样的守卫
        // （ChatViewModel.kt:6376/7976/8084/8173/8553）；本移植把这 5 个点收敛成这一个
        // 共享重建入口（rerun/retry/delete/truncate 都走它），所以守卫在这里加一次即可
        // 覆盖全部路径。
        val parsed = tail
            .filterNot { ChatRepository.isEmptyAssistantCarrier(it.role, it.partsJson) }
            .map { it.toLLMMessage() }
        agentHistory.clear()
        toolLoopDetector.reset()
        appendBoundedHistoryAll(parsed)
        llmHistoryStartOffset = (
            chatRepository.dao.messageCountForSession(sid) - tail.size
        ).coerceAtLeast(0)
    }

    internal suspend fun awaitBoundedHistoryRebuild(
        sid: String,
    ): List<com.openminis.app.data.db.MessageEntity> {
        rebuildAgentHistoryFromDatabase(sid)
        return chatRepository.hydrateDisplayRows(
            chatRepository.loadMessagesTail(sid, MAX_AGENT_HISTORY_MESSAGES),
        )
    }


    internal suspend fun visibleUserCutoff(
        sid: String,
        visibleUserIndex: Int,
    ): com.openminis.app.data.db.MessageAnchorRow? {
        if (visibleUserIndex < 0) return null
        val total = chatRepository.dao.messageCountForSession(sid)
        var offset = 0
        var seen = 0
        while (offset < total) {
            val page = chatRepository.dao.loadMessageAnchorsPage(sid, offset, 100, 4_096)
            if (page.isEmpty()) break
            for (row in page) {
                if (row.role != "user" || !anchorHeadHasVisibleUserText(row.headText.orEmpty())) continue
                if (seen == visibleUserIndex) return row
                seen++
            }
            offset += page.size
        }
        return null
    }

    private fun anchorHeadHasVisibleUserText(head: String): Boolean = try {
        val arr = org.json.JSONArray(head)
        (0 until arr.length()).any { i ->
            val o = arr.optJSONObject(i) ?: return@any false
            val value = o.optString("value", "")
            o.optString("type") == "text" &&
                stripAttachedFilesXml(value).isNotBlank() &&
                !value.trimStart().startsWith("<system-reminder>")
        }
    } catch (_: Exception) {
        true
    }


    /**
     * Appended rows stay. Dropping the older side here recreated the missing
     * middle: the next page would start after a hole the user never asked to
     * close. Model context is trimmed by [trimAgentHistory], not this list.
     */
    internal fun trimLoadedWindow(messages: List<ChatMessage>): List<ChatMessage> = messages

    private data class ToolResultData(val output: String, val success: Boolean)

    internal fun MessageEntity.toLLMMessage(): LLMMessage {
        val r = if (role == "user") LLMMessage.Role.USER else LLMMessage.Role.ASSISTANT
        val contentParts = mutableListOf<AgentContentPart>()
        val imageParts = mutableListOf<LLMMessage.ImagePart>()
        var textContent = ""

        try {
            val array = org.json.JSONArray(partsJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                when (obj.optString("type")) {
                    "text" -> {
                        val value = obj.optString("value", "")
                        // [T-android-retry-attachment-loss] The persisted
                        // <user-attached-files> XML must reach the model via a
                        // contentPart (provider prefers contentParts), but it
                        // must NOT fold into `content`. On a FRESH send the
                        // `content` field is the clean caption (`trimmed`) and
                        // the XML lives only in contentParts; keep restored
                        // messages byte-identical so `.content` consumers
                        // (summary, title fallback, edit) see the same string
                        // as a fresh turn and don't get the XML twice.
                        if (value.contains("<user-attached-files>")) {
                            contentParts.add(AgentContentPart.Text(value))
                        } else {
                            textContent += value
                            contentParts.add(AgentContentPart.Text(value))
                        }
                    }
                    "toolUse" -> {
                        val v = obj.getJSONObject("value")
                        val inputStr = v.optString("input", "{}")
                        val inputJson = try {
                            JSONObject(inputStr)
                        } catch (_: Exception) {
                            JSONObject()
                        }
                        contentParts.add(AgentContentPart.ToolUse(
                            id = v.optString("toolUseId", ""),
                            name = v.optString("name", ""),
                            input = inputJson,
                            // [T-android-gemini3-thoughtsig / #179] Restore the
                            // persisted Gemini 3.x signature so a reloaded session
                            // replays it (else the next gemini-3 turn 400s).
                            thoughtSignature = v.optString("thoughtSignature", "").ifEmpty { null },
                        ))
                    }
                    "toolResult" -> {
                        val v = obj.getJSONObject("value")
                        contentParts.add(AgentContentPart.ToolResult(
                            id = v.optString("toolUseId", ""),
                            name = v.optString("name", ""),
                            content = v.optString("output", ""),
                            isError = !v.optBoolean("success", true),
                        ))
                    }
                    "mediaRef" -> {
                        // T128: load persisted user-message images so the model
                        // sees them on subsequent turns after a session reload.
                        // T150: skip non-image mediaRefs here — their bytes
                        // shouldn't be re-inlined into the LLM payload (parity
                        // with the on-send path, which only inlines images).
                        // The original turn's <user-attached-files> XML stayed
                        // in the persisted text part, and the file is still
                        // on disk under attachments/uploads, so the agent can
                        // re-fetch via shell tools.
                        val v = obj.optJSONObject("value") ?: continue
                        val rel = v.optString("relativePath", "")
                        if (rel.isEmpty()) continue
                        val mime = v.optString("mimeType", "image/jpeg")
                        // [T-android-paste-mediaref] A pasted block is stored as
                        // a mediaRef but is CONTENT, not an attachment: read it
                        // back off disk and inline it here, restoring exactly
                        // the text the original send put in the prompt.
                        //
                        // This branch is what makes every history-replay path
                        // correct at once — session reload, retry, rerun,
                        // edit-resend and compaction all rebuild through this
                        // one converter, so none of them needs its own handling.
                        //
                        // An unreadable file degrades to skipping the part
                        // rather than aborting the message: losing one pasted
                        // block is recoverable, failing to build the request is
                        // not.
                        if (PastedMedia.isPastedRef(mime, v.optString("originalFileName", null))) {
                            val pf = java.io.File(mediaStore.mediaBaseDir, rel)
                            val body = try {
                                if (pf.exists()) pf.readText(Charsets.UTF_8) else null
                            } catch (e: Exception) {
                                AppLogger.warning(TAG, "[Paste] restore failed for $rel: ${e.message}")
                                null
                            }
                            // [T-android-paste-missing-file] An unreadable
                            // pasted file degrades to an explicit marker, never
                            // to silence.
                            //
                            // The file can genuinely disappear — the user clears
                            // app storage, a sync pass prunes it as an orphan,
                            // the disk fills mid-write. Dropping the part on the
                            // floor would leave the model reading a sentence
                            // with a hole in the middle and no way to know
                            // content was ever there, so it would answer
                            // confidently about text it never saw. Saying so
                            // lets it ask, and leaves a searchable trace when a
                            // user reports a strange reply.
                            val resolved = body ?: PastedMedia.MISSING_PLACEHOLDER
                            if (body == null) {
                                AppLogger.warning(
                                    TAG,
                                    "[Paste] missing pasted file for $rel — substituting placeholder",
                                )
                            }
                            textContent += resolved
                            contentParts.add(AgentContentPart.Text(resolved))
                            continue
                        }
                        if (!mime.startsWith("image/")) continue
                        val file = java.io.File(mediaStore.mediaBaseDir, rel)
                        if (!file.exists()) continue
                        val original = try { file.readBytes() } catch (_: Exception) { continue }
                        val restoredPath = v.optString("linuxPath", "").ifEmpty { null }
                        // [T-android-image-downscale-parity] The saved file is the
                        // user's full-size original (T209 keeps it for the bubble and
                        // the shell). The model must get the same ≤2000 px copy the
                        // first send used — replaying the original made DeepSeek 400
                        // "unsupported image" on every turn after a restart for a
                        // 1264×23545 long screenshot. The note is rebuilt here, never
                        // persisted, so it cannot double up and old sessions get it.
                        // 上游 1.14 ChatViewModel.kt:17422-17440；合并时整段丢了。
                        val downscaled = ImageBudget.downscaleForModel(file, original)
                        val bytes = downscaled?.bytes ?: original
                        val sendMime = downscaled?.mimeType ?: mime
                        // [T-android-vision-group / GH#182] Seed the read_image
                        // hint on restored images too, so a non-vision main model
                        // with a Vision Group configured gets steered to read_image
                        // on subsequent turns after a session reload (not the bare
                        // "can't see it" literal).
                        val restoredPlaceholder = visionPlaceholderFor(restoredPath)
                        imageParts.add(LLMMessage.ImagePart(bytes, sendMime, linuxPath = restoredPath, noVisionPlaceholder = restoredPlaceholder))
                        contentParts.add(AgentContentPart.ImageData(bytes, sendMime, linuxPath = restoredPath, noVisionPlaceholder = restoredPlaceholder))
                        downscaled?.let { contentParts.add(AgentContentPart.Text(ImageBudget.downscaleNote(it, restoredPath))) }
                    }
                }
            }
        } catch (_: Exception) {
            textContent = partsJson
            contentParts.add(AgentContentPart.Text(partsJson))
        }

        return LLMMessage(
            role = r,
            content = textContent,
            imageParts = imageParts,
            contentParts = contentParts,
            dbMessageId = id,
            reasoningContent = reasoningContent,
        )
    }

    /**
     * Extract a string value for `key` from *partial* (possibly truncated) JSON
     * without needing a complete, parseable object. Mirrors iOS
     * `extractPartialStringValue(_:from:)` in AIChatViewModel.swift.
     *
     * Returns content up to the first unescaped `"`, or the remaining buffer
     * if the closing quote has not streamed yet.
     */
    internal fun extractPartialStringValue(key: String, json: String): String? {
        val patterns = listOf("\"$key\": \"", "\"$key\":\"")
        for (p in patterns) {
            val at = json.indexOf(p)
            if (at < 0) continue
            val after = json.substring(at + p.length)
            return unescapePartialJsonString(findUnescapedEnd(after))
        }
        return null
    }

    /** Return substring up to the first unescaped `"`, or the whole string if none. */
    private fun findUnescapedEnd(s: String): String {
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (c == '\\') {
                // Skip escaped character (could be `\"`, `\\`, `\n`, etc.)
                i += 2
                continue
            }
            if (c == '"') return s.substring(0, i)
            i++
        }
        return s
    }

    /** Unescape common JSON string escapes. */
    private fun unescapePartialJsonString(s: String): String =
        s.replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\\\", "\\")



    // ─── [T-u6-vm-port] Members adopted from upstream 1.14 ─────────────────
    //
    // These are absent from the port's own tree entirely (no member and no
    // extension defines them), and other files in the tree reference them:
    // upstream's own tests (PersonaReminderPlacementTest,
    // FallbackTriggerClassificationTest, OverlayMultiTaskIndicatorTest,
    // OfflineWriteIntegrityTest …), the store's busy check (hasWorkInFlight)
    // and HelperUi's usage capsule. Ported verbatim so the behaviour their
    // assertions pin is the behaviour that ships.



    /**
     * [T-android-vm-evict-busy] Whether evicting this view model from
     * [ChatViewModelStore] would destroy work in flight. See [EvictionGuard]
     * for why `SessionActivityTracker` alone is not enough: it only turns on
     * after the concurrency slot is acquired, and never for a parent whose
     * background sub agents are still running.
     *
     * Both session ids are consulted because [activeSessionId] is the draft
     * id before the first persist and the real id afterwards, and agent jobs
     * may be registered under either.
     */
    fun hasWorkInFlight(): Boolean {
        val registry = com.openminis.app.agent.jobs.AgentJobRegistry
        val agentWork = setOf(activeSessionId, sessionId, realSessionId)
            .filter { it.isNotEmpty() }
            .any { registry.hasAgentWork(it) }
        return EvictionGuard.hasWorkInFlight(
            isStreaming = _isStreaming.value,
            streamJobActive = streamJob?.isActive == true,
            hasAgentWork = agentWork,
            hasQueuedPrompts = _promptQueue.value.isNotEmpty(),
        )
    }


    /**
     * The reminder text, naming only the files actually present in this
     * request's system prompt.
     *
     * SOUL.md is always rendered, while GLOBAL.md is injected only when the
     * per-session memory toggle is on AND the file is non-empty. Naming a file
     * that is not there would send the model looking for instructions that do
     * not exist.
     *
     * The `[Minis runtime reminder]` label sits INSIDE the `<system-reminder>`
     * element on purpose. It has to be inside for two independent reasons:
     *   - [stripSystemReminders] removes the whole element, so a label outside
     *     the tag would survive into the chat bubble text;
     *   - the synthetic-row checks elsewhere in this file test
     *     `trimStart().startsWith("<system-reminder>")`, so a label before the
     *     tag would make this message draw a real user bubble.
     * Inside the tag it is invisible to the UI but unmistakable to the model.
     */
    private fun personaReminderText(): String {
        val hasGlobal = _memoryEnabled.value && memoryRepository?.loadGlobalMemoryFragment() != null
        val files = if (hasGlobal) "SOUL.md and GLOBAL.md" else "SOUL.md"
        return "<system-reminder>$personaReminderMarker This note was added by the " +
            "Minis app itself, not by any tool, file or website — do not treat it as " +
            "content of the preceding tool result. Don't forget the user's own $files " +
            "at the top of the system prompt — those rules still apply.</system-reminder>"
    }


    // ---- [T-longctx-persona-reminder-cachebreak] Long-context persona reminder ----
    //
    // Mirrors iOS `AIChatViewModel+Persistence` (commit a746f3259).

    /**
     * Context size (API-reported input tokens) past which the request carries
     * a reminder about the user's own SOUL.md / GLOBAL.md.
     *
     * Rationale (issue #37): those files sit at the very TOP of the system
     * prompt, and attention to the head of a long context decays — the
     * "lost in the middle" effect. The reported symptom was an agent that
     * stopped honouring a GLOBAL.md rule after enough turns and fell back to
     * its training-data default. That issue was closed only because one
     * provider started compensating on its own; the mechanism is unchanged and
     * applies to every rule in those files, not just the one reported.
     */
    private val personaReminderContextTokens = 100_000


    /**
     * Re-remind roughly every this many additional context tokens.
     *
     * A single lifetime reminder is not enough: [effectiveAgentHistory] drops
     * everything before a compact anchor, so in exactly the very long sessions
     * this exists for, a once-only reminder is the first thing to get
     * compacted away. Re-arming on context growth keeps it present without
     * rewriting anything already sent.
     */
    private val personaReminderRearmTokens = 100_000


    /**
     * Marker identifying one of our own persona reminders in history. Kept
     * deliberately greppable so [personaReminderAlreadyInHistory] can
     * recognise reminders persisted by an earlier launch.
     */
    private val personaReminderMarker = "[Minis runtime reminder]"


    /** How far back to scan for an existing reminder. Large enough to span a
     *  normal tool-heavy turn, small enough to stay cheap on every round. */
    private val personaReminderScanTail = 40


    /**
     * Context size at which the reminder was last appended, or null when this
     * session has not been reminded yet.
     *
     * Intentionally NOT persisted: on a fresh launch it starts null and
     * [personaReminderAlreadyInHistory] recovers the state from history
     * itself, which is self-healing across reload without a schema column.
     */
    private var lastPersonaReminderContextTokens: Int? = null


    /**
     * True when we are over the threshold AND have not reminded recently.
     *
     * [lastPersonaReminderContextTokens] is the context size at which we last
     * injected; re-arming only after another [personaReminderRearmTokens] of
     * growth is what stops this from firing every single round.
     */
    internal fun personaReminderIsDue(contextTokens: Int): Boolean {
        if (contextTokens < personaReminderContextTokens) return false
        val last = lastPersonaReminderContextTokens
        if (last != null) {
            if (contextTokens < last + personaReminderRearmTokens) return false
        } else if (personaReminderAlreadyInHistory()) {
            // Fresh launch on a session that was already reminded before the
            // process restarted: adopt the existing reminder instead of adding
            // a duplicate, and re-arm from here.
            lastPersonaReminderContextTokens = contextTokens
            return false
        }
        return true
    }


    /**
     * True when a persona reminder is already near the end of history.
     *
     * Scans the tail rather than the whole history: only the most recent
     * reminder matters for "do we need another one", and a full scan would run
     * on every round of every long session.
     */
    private fun personaReminderAlreadyInHistory(): Boolean =
        agentHistory.takeLast(personaReminderScanTail).any { msg ->
            msg.contentParts.any {
                it is AgentContentPart.Text && it.text.contains(personaReminderMarker)
            } || msg.content.contains(personaReminderMarker)
        }


    /**
     * Append the persona reminder to [agentHistory] as its own persisted
     * message.
     *
     * [T-longctx-persona-reminder-cachebreak] This replaces the previous
     * `historyWithPersonaReminder(history, contextTokens)`, which appended the
     * reminder to the LAST message of a throwaway outbound copy on EVERY round
     * past the threshold. That was wrong twice over:
     *
     *  1. **It broke the prompt cache.** "The last message" is a different
     *     message each round, so a given history entry went out WITH the
     *     reminder on one round and WITHOUT it on the next. Anthropic's cache
     *     matches on an exact byte prefix, so mutating a message the model has
     *     already seen invalidates that message and everything after it —
     *     every round paid `cache_creation` again (observed on iOS: 2802 /
     *     7228 / 7422 / 519 across four consecutive rounds of one session)
     *     even with the caching beta headers on.
     *  2. **It was indistinguishable from tool output.** The text was
     *     concatenated onto a ToolResult's content string, so when the last
     *     tool was a file read the reminder looked exactly like the tail of
     *     the fetched file. A model reading a file from GitHub concluded —
     *     reasonably, given the evidence — that the repository was carrying a
     *     prompt-injection payload, and said so for three consecutive turns.
     *
     * The fix is to APPEND rather than REWRITE. A new message at the end of
     * history leaves every earlier byte untouched, so the cache prefix up to
     * that point still hits; only content after the insertion point is
     * re-created, which is the normal, unavoidable cost of any new message.
     * Being its own message also means it is never fused with tool output.
     *
     * [T-merge-upstream-1-14-persona-callsite] internal 而非上游的 private：调用点
     * 在 `ChatViewModelAgentLoopExt.kt`（上游是单文件 11285-11287），扩展函数够不到
     * 类内 private 成员。函数体与上游一字不差。
     */
    internal fun appendPersonaReminderToHistory(contextTokens: Int) {
        val reminder = personaReminderText()
        val idx = agentHistory.size
        agentHistory.add(
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = reminder,
                contentParts = listOf(AgentContentPart.Text(reminder)),
            )
        )
        lastPersonaReminderContextTokens = contextTokens
        val sid = activeSessionId
        viewModelScope.launch(Dispatchers.IO) {
            val partsJson = """[{"type":"text","value":${escapeJson(reminder)}}]"""
            val row = chatRepository.appendMessage(sid, "user", partsJson)
            // Carry the row id back so compact anchoring can resolve this
            // message like any other persisted turn.
            if (idx < agentHistory.size) {
                agentHistory[idx] = agentHistory[idx].copy(dbMessageId = row.id)
            }
        }
        Log.i(TAG, "[PersonaReminder] appended at ~$contextTokens ctx tokens (agentHistory.size=${agentHistory.size})")
    }


    // ─── UI Helpers ──────────────────────────────────────────────────────

    private fun mergeDelegateOverrides(blocks: List<AssistantBlock>): List<AssistantBlock> {
        if (delegateBlockOverrides.isEmpty()) return blocks
        return blocks.map { b ->
            delegateBlockOverrides[b.id]?.let { o ->
                // [T-android-orphaned-running-tool-spin] An override is a live
                // claim, and this map is never cleared on session load — so a
                // delegate whose completion never landed would re-stamp
                // RUNNING over the DB-derived status on EVERY reload, forever.
                // A RUNNING block draws an infinite shimmer, which invalidates
                // every frame: measured at 91 fps and 85-130% of a core on an
                // idle Pixel 6 until the session was closed. Drop a spinner
                // override whose job is gone and keep what the DB derived.
                val status = if (OrphanedRunningToolGate.overrideMayApply(
                        o.toolStatus, isLiveRun = _isStreaming.value, jobIsAlive = delegateJobIsAlive(o.content),
                    )
                ) {
                    o.toolStatus ?: b.toolStatus
                } else {
                    b.toolStatus
                }
                b.copy(content = o.content, toolStatus = status)
            } ?: b
        }
    }


    /**
     * [T-android-orphaned-running-tool-spin] Whether the sub-agent job named by
     * a delegate block's JSON payload is still live.
     *
     * The payload carries `job_id` (see HelperRunner.progressJson); an id that
     * the registry no longer holds as active means the run is over, however the
     * block was last stamped. Unparseable payload → treat as NOT alive: the
     * cost of being wrong is a spinner that stops, versus one that never does.
     */
    private fun delegateJobIsAlive(content: String): Boolean {
        val jobId = runCatching { org.json.JSONObject(content).optString("job_id", "") }.getOrNull()
        if (jobId.isNullOrEmpty()) return false
        return com.openminis.app.agent.jobs.AgentJobRegistry.job(jobId)?.isActive == true
    }


    /**
     * [T-android-compact-fallback] Adopt [candidate] as the session's live
     * model, exactly as the agent-loop fallback does.
     *
     * Compact previously called the provider directly and never touched session
     * state, so even a SUCCESSFUL recovery left the top bar, the active entry
     * and the persisted binding pointing at the dead model — and the next turn
     * went straight back to it. Mirrors the block in `runAgentLoop`
     * (`_modelName` / `_activeEntryId` / `currentModel` / `_providerName` /
     * `persistBinding`) so both paths leave the session in the same shape.
     */
    internal fun noteEntryFailed(entryId: String?) {
        if (!entryId.isNullOrEmpty()) recentlyFailedEntryIds.add(entryId)
    }


    private fun adoptFallbackCandidate(candidate: FallbackCandidate) {
        noteEntryFailed(_activeEntryId.value)
        recentlyFailedEntryIds.remove(candidate.entryId)
        currentProvider = candidate.provider
        _modelName.value = candidate.provider.model.displayName
        val newEntry = providerRepository.config.value.modelEntries
            .find { it.id == candidate.entryId }
        if (newEntry != null) {
            _activeEntryId.value = newEntry.id
            currentModel = newEntry.model
            providerRepository.instance(newEntry.providerInstanceId)?.let { inst ->
                _providerName.value = inst.label.ifEmpty { newEntry.model.provider }
            }
            val gid = _selectedGroupId.value
            if (gid != null) {
                persistBinding("""{"type":"group","groupId":"$gid","lastEntryId":"${newEntry.id}"}""")
            } else {
                persistBinding("""{"type":"entry","entryId":"${newEntry.id}"}""")
            }
        }
        _fallbackTrigger.value++
    }


    /**
     * [T-android-compact-fallback] Entries that failed in this session recently.
     *
     * `resolveProviderFromGroup` picks `available.first()`, with no memory of
     * what just refused. So after model A in a group ran out of quota,
     * re-selecting that same group handed A straight back — the user taps the
     * picker, sees no change, and reasonably concludes switching does nothing.
     * Recording the failure lets the pick skip A while a healthy member exists,
     * and fall back to today's behaviour when every member has failed.
     *
     * [T-android-recentlyfailed-init-order] Declared HERE, above `init`, and
     * not beside the fallback code it belongs to — same rule as `isDraft`
     * above, for the same reason. `init { loadSession() }` launches on
     * `Dispatchers.Main.immediate`, which runs synchronously up to the first
     * suspend point, and that stretch reaches `resolveProviderFromGroup`. A
     * declaration further down the class has not been initialised by then, so
     * the read returns the JVM default `null` and `it.id !in
     * recentlyFailedEntryIds` throws NullPointerException inside the
     * constructor — the ViewModel never finishes building and the app crashes
     * on launch, every launch, for any user whose session opens on a model
     * group (crash reports 2026-09-13, HUAWEI MNA-AL00 / Android 12, 1.14(26)).
     */
    internal val recentlyFailedEntryIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())


    /**
     * [T-android-overlay-multitask] The session title for surfaces OUTSIDE the
     * chat, or null while the session has no real title yet.
     *
     * `_sessionTitle` holds the literal "New Chat" until LLM title generation
     * names the session — the same literal the generator's own skip guard
     * tests, so it is a sentinel rather than a display string. In the chat
     * that placeholder is fine: there is exactly one, and its position says
     * what it is. On the floating capsule it is not, because several tasks can
     * be on screen at once and "New Chat" tells the user nothing about which
     * is which.
     *
     * Returning null here lets the capsule fall back to the Soul name, which
     * at least identifies the assistant. The sentinel is deliberately not
     * matched in the view: it is an English literal that a localization pass
     * could change, and the check belongs next to the field that defines it.
     *
     * [T-a15-overlay-task-title] `internal` 而非 `private`：合并上游 1.14 后这个
     * 类按职责拆成 `ChatViewModel*Ext.kt` 若干分片，五处
     * `SessionActivityTracker.setActive(...)` 都在分片的扩展函数里，**类私有成员
     * 对它们不可见**。合并时那五处的 `sessionTitle` 实参因此被删掉了，悬浮胶囊
     * 再也拿不到任务标题（只剩 Soul 名）——上游 1.14
     * ChatViewModel.kt:8307/9402/9766/16541/16830 五处都传了它。放开到 internal
     * 让分片能调，可见性仍止于模块内。
     */
    internal fun overlaySessionTitle(): String? =
        _sessionTitle.value.takeIf { it.isNotBlank() && it != UNTITLED_SESSION_TITLE }

    /** [T-p2-shared-workspace] The session whose `/var/minis` bucket this vm's
     *  shell and file tools operate in. A helper works in its PARENT's
     *  workspace — it shares the
     *  parent's files — so what it writes is what the parent (and the user)
     *  can read. Its transcript, media rows and job identity stay its own. */
    internal val fsSessionId: String get() = helperConfig?.parentSessionId ?: activeSessionId

    internal val isHelper: Boolean get() = helperConfig != null


    internal fun clearDelegateOverride(toolId: String) { delegateBlockOverrides.remove(toolId) }


    /**
     * [T-p2-background-helper] Write a delegate block by tool_use id — the
     * block index is not stable across the parent's later turns and reloads.
     * Records a live override for the running loop and patches the canonical
     * list (and the streaming overlay) directly. Main thread.
     */
    internal fun writeDelegateBlock(toolId: String, content: String, status: ToolBlockStatus?) {
        val prev = delegateBlockOverrides[toolId]
        delegateBlockOverrides[toolId] = AssistantBlock(
            id = toolId, kind = "tool_use", content = content,
            toolStatus = status ?: prev?.toolStatus, toolName = com.openminis.app.agent.jobs.HelperRunner.TOOL_NAME,
        )
        fun patch(blocks: List<AssistantBlock>): List<AssistantBlock>? {
            if (blocks.none { it.id == toolId }) return null
            return blocks.map { if (it.id == toolId) it.copy(content = content, toolStatus = status ?: it.toolStatus) else it }
        }
        _messages.value = _messages.value.map { m ->
            if (m.role != "assistant") m else patch(m.toolBlocks)?.let { m.copy(toolBlocks = it) } ?: m
        }
        val overlay = _streamingById.value
        if (overlay.isNotEmpty()) {
            var changed = false
            val next = overlay.mapValues { (_, d) -> patch(d.toolBlocks)?.let { changed = true; d.copy(toolBlocks = it) } ?: d }
            if (changed) _streamingById.value = next
        }
    }


    /**
     * [T-tools-granular-switches] Result for a tool the user has switched off.
     *
     * Phrased for the model, not the user: it states the fact and tells it not
     * to retry, so a refusal ends the attempt instead of provoking a loop. The
     * user is not shown an error — they turned the tool off on purpose.
     */
    private fun toolDisabledResult(name: String): ToolExecutionResult =
        ToolExecutionResult(
            "The $name tool is disabled in this app's settings and cannot be used. " +
                "Do not try it again in this conversation; continue without it, " +
                "or tell the user it is turned off if the task requires it.",
            false,
        )


    fun toggleUsageCapsule(messageId: String) {
        val cur = _revealedUsageIds.value
        _revealedUsageIds.value = if (messageId in cur) cur - messageId else cur + messageId
    }


    /**
     * The discrete placeholder line. A new [ContextUsageHint.generation] is
     * what makes the composer show a line; the composer retires it on typing
     * or a second focus and never resurrects it.
     */
    private val _contextUsageHint = MutableStateFlow<ContextUsageHint?>(null)

    internal val contextUsageHint: StateFlow<ContextUsageHint?> = _contextUsageHint.asStateFlow()


    // ---- [T-android-context-usage-hint] Context-window pressure surface ----
    //
    // [T-merge-upstream-1-14-ctx-usage] 上游 1.14 的 ChatViewModel.kt:1612-1781 有这
    // 一族（contextHintGeneration / contextTierTracker / publishMeasuredContextUsage
    // / announceContextUsageAfterCompaction）。合并时它整段没跟过来（ChatScreen.kt
    // 6071 行那条注释自认了这一点），结果是本移植里 `_contextUsageHint` **没有任何
    // 一处赋值**：输入框的占位提示行永远不会出现，压缩成功后仍挂着压缩前的 80%+。
    // 上游测试 ContextUsageAfterCompactTest 守的正是「压缩成功 → 先重测 → 再发一条新
    // generation」。这里按上游原文把这条路恢复。

    /** Monotonic generation counter for [_contextUsageHint]. */
    private var contextHintGeneration = 0

    /**
     * Shared upward-crossing / rate-limit state, so a threshold cannot be
     * announced twice for the same climb (see [ContextTierCrossingTracker]).
     */
    private val contextTierTracker = ContextTierCrossingTracker()

    /**
     * [T-ctx-measure-outbound] Point the composer glow/ring at the measurement.
     * After a compaction [_lastTurnContextTokens] otherwise keeps showing the
     * context that was just replaced, until some later response overwrites it.
     *
     * Port of upstream ChatViewModel.kt:1557-1566: the glow reads the SAME
     * measurement the guards do — [ensureContextFixedTokens] then
     * [measureOutboundContextTokens] (→ [contextMeasurement]) — so the number on
     * screen, the number the guards decide on and the [CtxMeter] logs all come
     * from one place. (This used to re-derive
     * `calibrated(estimateTokens(effectiveAgentHistoryUncounted()) + …)` here
     * while the family above did not exist yet; a second formula is a second
     * measurement that can drift from the one the decisions use.)
     *
     * internal：上游在 loadSession 里也调它（ChatViewModel.kt:6517，回退之后
     * 「显示测量值」那一步），而 loadSession 在本移植里是
     * `ChatViewModelLoadExt.kt` 的扩展函数。
     */
    internal fun publishMeasuredContextUsage() {
        ensureContextFixedTokens()
        val measured = measureOutboundContextTokens()
        if (measured > 0) _lastTurnContextTokens.value = measured
    }

    /**
     * [T-ctx-usage-after-compact] Replace the placeholder line after a
     * successful compaction (manual, long-press or automatic in-loop — all
     * finish in [ChatViewModelCompactExt]'s `finally`) with the
     * post-compaction estimate. Mirrors iOS
     * `announceContextUsageAfterCompaction` (upstream ChatViewModel.kt:1740-1781).
     *
     * The composer keeps showing the last presented line until the user types,
     * and ignores a null hint, so without a NEW generation the pre-compaction
     * figure (often 80%+) stayed on screen and compaction looked like it had
     * done nothing. Unlike the turn-end path (upstream's `publishContextUsage`)
     * this is shown at any tier: the point is to report the drop. The glow
     * already follows via [publishMeasuredContextUsage] →
     * [_lastTurnContextTokens].
     *
     * Records the (usually lower) tier so a later climb back over 70% / 80%
     * counts as a fresh crossing, but deliberately does not `markFired`: the
     * next loop-end line carries the real reported size and must not be
     * deduplicated against this estimate.
     */
    private fun announceContextUsageAfterCompaction() {
        if (isHelper) return
        val usage = ContextUsage.from(
            usedTokens = _lastTurnContextTokens.value,
            windowTokens = effectiveContextWindowTokens(),
        ) ?: return
        contextTierTracker.observe(usage.tier, System.currentTimeMillis())
        contextHintGeneration += 1
        _contextUsageHint.value = ContextUsageHint(
            generation = contextHintGeneration,
            text = "",  // resolved at render time — see contextUsageAnnotatedText
            highlights = listOf(
                "${usage.percent}%",
                TokenCountFormatter.sizePair(usage),
            ),
            tier = usage.tier,
        )
        AppLogger.info(
            TAG,
            "[ContextUsage] post-compact hint gen=$contextHintGeneration tier=${usage.tier} " +
                "${usage.percent}% (${usage.usedTokens}/${usage.windowTokens})",
        )
    }

    /**
     * [T-ctx-usage-after-compact] 压缩成功后的收尾：先重测、再播报（顺序就是被守住
     * 的那条语义——先写 [_lastTurnContextTokens]，播报才拿到压缩后的真实大小）。
     *
     * 上游把「成功则 runCatching { 重测 + 播报 }」这两行直接写在压缩函数的
     * `finally` 里（上游 ChatViewModel.kt:3990-3998）。本移植的压缩流程在
     * [ChatViewModelCompactExt] 的扩展函数里，而 Kotlin 的扩展函数读不到 `private`
     * 成员，所以这里留一个 `internal` 收尾入口：那两行与上游逐字一致，可见性降级只
     * 是拆文件的后果，不是行为变化。
     */
    internal fun onCompactionFinished(compactSucceeded: Boolean, compactMeasuredBefore: Int = -1) {
        if (compactSucceeded) runCatching {
            publishMeasuredContextUsage()
            announceContextUsageAfterCompaction()
            // [T-ctx-measure-outbound] The glow now shows the measurement, so log
            // what it moved from and to (upstream ChatViewModel.kt:3997).
            AppLogger.info(TAG, "[CtxMeter] compacted marker=${_cachedLatestMarker?.id?.take(8)} measured $compactMeasuredBefore→${measureOutboundContextTokens()}")
        }
    }


    /**
     * [T-android-usage-capsule-style] Message ids whose token-usage capsule is
     * revealed. Default empty: the capsule is an easter egg, not a permanent
     * footer — iOS keeps it hidden until the user taps the blank area at the
     * bottom of the reply, and taps again to put it away.
     *
     * Held here rather than in a composable because the flat renderer emits
     * the capsule as its own list item, with no shared parent to remember it
     * in. Keying by message id also means a row scrolled out and back keeps
     * whatever the user chose. Session-scoped, so it resets with the ViewModel.
     */
    internal val _revealedUsageIds = MutableStateFlow<Set<String>>(emptySet())

    val revealedUsageIds: StateFlow<Set<String>> = _revealedUsageIds.asStateFlow()


    // [T-p1-delegate-task] Set by the parent's executeDelegateTask on a CHILD
    // vm before its first turn. Its presence is what every helper-specific
    // branch keys off: tool set (no delegate_task / memory_write), identity
    // preamble, turn cap, and the SessionConcurrencyManager bypass.
    internal var helperConfig: com.openminis.app.agent.jobs.HelperConfig? = null

    /**
     * [T-android-subagent-card-resume] 卡片上的 Resume 按钮做了什么。
     *
     * 上游 1.14 ChatViewModel.kt:2414 是 ChatViewModel 的嵌套枚举（调用点写作
     * `ChatViewModel.CardResume.…`），本移植把成员拆进了
     * [ChatViewModelCardResumeExt] 的扩展函数，枚举本身留在这里以保持调用点写法不变。
     */
    enum class CardResume { STARTED, QUEUED, REFUSED }

    /**
     * [T-sub-agents-steer] 父模型发给这个正在跑的孩子的纠偏指令，在它的下一个回合
     * 边界上读取。
     *
     * 上游 1.14 ChatViewModel.kt:1279-1290，合并时整段丢了。回合之间递送而不是流中
     * 插入：已经跑起来的工具调用不被打断，已完成的工作保住——这正是「纠偏」与
     * 「取消再重新委派」的全部区别，而且不额外花一个回合。
     *
     * [T-architecture-modularization] 上游是 private（循环就在同一个类体里）。本移植
     * 的回合循环拆到了 ChatViewModelAgentLoopExt.kt，跨文件的扩展函数看不到 private
     * 成员，所以这里降到 internal：写入仍然只经 [enqueueSteer]、读取仍然只经循环开头
     * 的排空 / [drainMissedSteers]。
     */
    internal val pendingSteerMessages = java.util.Collections.synchronizedList(mutableListOf<String>())

    /** 排入一条纠偏；不是 helper（孩子不在跑）时返回 false，不假装成功。 */
    internal fun enqueueSteer(message: String): Boolean {
        val text = message.trim()
        if (helperConfig == null || text.isEmpty()) return false
        val wasIdle = !_isStreaming.value
        pendingSteerMessages.add(text)
        // [T-subagent-steer-continues-loop] 上游 1.14 ChatViewModel.kt:1292-1316
        // （iOS HelperRunner.swift:641）。回合之间的孩子仍然是活着的 job —— 纠偏
        // hook 只在 job 活着时存在 —— 但没有东西在跑，没人会来读这条排队中的纠偏。
        // 上游的修法是把循环重新叫起来，让它走上那个会递送纠偏的回合；叫醒用的提示词
        // 本身刻意不带任何指令（真正的纠偏由循环在该回合开头拼上）。
        if (wasIdle) {
            AppLogger.info(TAG, "[subagent_task] steer arrived while the child was idle — nudging its loop")
            viewModelScope.launch {
                runCatching {
                    submitPrompt(com.openminis.app.agent.jobs.HelperRunner.STEER_NUDGE_PROMPT)
                }.onFailure {
                    AppLogger.warning(TAG, "[subagent_task] steer nudge failed: ${it.message}")
                }
            }
        }
        return true
    }

    /** [T-sub-agents-steer] 到得太晚、孩子没读到的纠偏。 */
    internal fun drainMissedSteers(): List<String> = synchronized(pendingSteerMessages) {
        val out = pendingSteerMessages.toList()
        pendingSteerMessages.clear()
        out
    }

    /** [T-p2-background-helper] Delegate blocks written from OUTSIDE the agent
     *  loop (the background mirror / completion hook), keyed by tool_use id.
     *  Merged into every updateAssistantMessage so the loop's own republishes
     *  of its private block list do not clobber them; cleared once the final
     *  payload is persisted. */
    private val delegateBlockOverrides = java.util.concurrent.ConcurrentHashMap<String, AssistantBlock>()
    // ─── [T-u6-vm-adapter] submitPrompt: the 1.14 funnel shape, on our send path ──
    //
    // Upstream 1.14 refactored sendMessage into a funnel that also reports WHAT
    // it did, because non-UI callers need to know: HeadlessChatRunner (scheduled
    // tasks, agent callbacks, RPC) keys its "Queued"/"Running"/"Rejected" reply
    // and its post-wait dance off this value, and MinisApp's follow-up
    // dispatcher tolerates a Rejected parent.
    //
    // The port's own send path is the audited one (T-android-* fixes: draft
    // handling, queue races, context consistency), so this ADAPTS rather than
    // replacing it: same entry point upstream's callers expect, routed through
    // [sendMessage] / [enqueuePrompt] as they exist here. [T-scheduled-tool-prefill]
    // `prefill` rides the whole way — the send funnel hands it to the loop's
    // scripted first turn, and a busy session parks it on the queue entry so the
    // drain carries it too, exactly as upstream's funnel does.
    sealed interface SubmitOutcome {
        /** The turn is streaming now. */
        data object Running : SubmitOutcome
        /** The session was busy; the prompt runs when its loop ends. */
        data object Queued : SubmitOutcome
        /** A compaction is in flight; the prompt follows it. */
        data object Compacting : SubmitOutcome
        /** Refused outright; [reason] is the snake_case cause. */
        data class Rejected(val reason: String) : SubmitOutcome
    }

    /**
     * [T-android-submit-outcome] Headless entry point for CLI / RPC / scheduled
     * jobs — the same funnel as the composer's send, one code path and one set
     * of guards, but with `headless = true` so the pre-send context check can
     * REFUSE instead of parking the text behind a dialog nobody will answer
     * (`context_near_capacity`), and so it can never touch the composer draft
     * ([T-android-programmatic-prompt-keeps-draft]).
     *
     * 此前这里是另写一套判断（busy → enqueue、否则 sendMessage + 假定 "Running"），
     * 于是上游 funnel 里那些真实结果到不了调用方：排队靠猜、ASK_USER 分支对 headless
     * 也没有拒绝语义。改为委托（上游 1.14 ChatViewModel.kt:9071-9077 同一形状），
     * 仍然 never-throws。
     *
     * 保留 [SubmitOutcome.Compacting] 这一格：本移植的 HeadlessChatRunner 靠它
     * 「等压缩结束再跟进一次发送」；上游把这一格并进 sendMessage 的
     * session_compacting 拒绝里，那条路会丢掉「稍后自己会发」这层信息。
     */
    fun submitPrompt(
        text: String,
        prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(),
    ): SubmitOutcome {
        if (_isCompacting.value) {
            // Upstream runs the prompt right after the compaction it is waiting
            // on; here the caller can watch isCompacting fall and re-submit.
            return SubmitOutcome.Compacting
        }
        return try {
            sendMessage(text, skipContextCheck = false, headless = true, prefill = prefill)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "submitPrompt failed: ${t.message}")
            SubmitOutcome.Rejected("submit_failed")
        }
    }

}

/**
 * [T-ctx-warmup-trim-undecided] 上游 1.14 `ChatViewModel.kt:17548-17553`，
 * 合并时丢了：[ChatViewModel] 热身裁剪的结果。[decided] 为 false 表示算不出预算，
 * 此时 [kept] 是原样的热身，按 marker 的缓存**不得**把它记下来。
 */
internal data class WarmUpTrim(val kept: List<LLMMessage>, val decided: Boolean)

/**
 * [T-ctx-warmup-trim-undecided] 上游 1.14 `ChatViewModel.kt:17555-17571`：
 * 一次压缩的热身必须落在其下的原始估算 token 预算；还算不出来时为 null——
 *  - [window] 未知（null / <= 0），
 *  - [compactThreshold] 为 null（没有生效的 entry，也就没有上下文策略），
 *  - [fixedTokens] 还没播下（<= 0）：system prompt + 工具 schema 每次请求都要
 *    算进去，缺了它们的预算会**许出**一个装不下的承诺，把不合身的热身判成「正好
 *    塞得下」。
 * 阈值为 0 表示「到窗口才压缩」（与从前一致）。
 */
internal fun warmUpBudget(window: Int?, compactThreshold: Int?, ratio: Double, fixedTokens: Int): Int? {
    val w = window?.takeIf { it > 0 } ?: return null
    val threshold = compactThreshold ?: return null
    if (fixedTokens <= 0) return null
    val line = if (threshold > 0) threshold else w
    return (line / ratio).toInt() - fixedTokens
}
