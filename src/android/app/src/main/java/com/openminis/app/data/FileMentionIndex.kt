package com.openminis.app.data

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.tools.SessionTreeGuard
import com.openminis.app.tools.walkOwnedTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * @-mention file index for the chat composer. Mirrors iOS `FileMentionIndex`:
 * a 3-layer scanner that publishes candidates to the UI **as each layer
 * finishes** so the user sees session-local files immediately, then shared
 * roots, then (if mounts are attached) external mounts.
 *
 * ### Layers
 *   1. **Session roots** — `workspace/<sid>` + `attachments/<sid>`.
 *   2. **Shared roots** — `shared/` only. Session `memory/` is layer 1.
 *      Skills are not an @ target; `/` is the skill picker.
 *   3. **Mount roots** — each entry in [mountsProvider] (e.g. SAF-attached
 *      folders). Each mount always gets a self-entry so `@<mountName>` works.
 *
 * ### Scoring (for [matches])
 *   - basename equals query → 1000
 *   - basename starts with query → 800
 *   - basename contains query → 600
 *   - any path component contains → 400
 *   - full path contains → 200
 *   - else → filtered out
 *
 * Adjustments: `.mount` scope loses 10 points (prefer session-local), top-level
 * scope roots gain 50 points. Ties broken by `modifiedAt` descending, capped
 * at [DEFAULT_MATCH_LIMIT].
 */
class FileMentionIndex(
    private val filesDir: File,
    private val mountsProvider: () -> List<MountEntry> = { emptyList() },
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    constructor(context: Context) : this(File(context.filesDir, "minis-global"))

    /**
     * Scope priorities — `order` doubles as the empty-query default sort key
     * (lower wins) AND drives `rankBoost`'s scope tiebreaker on top of the
     * name-match score. The case order here mirrors the iOS counterpart in
     * `FileMentionIndex.swift`, except skills are omitted from @ entirely:
     *   attachments > mount > shared > workspace > memory.
     *
     * `rankBoost` is added to the per-name score with a max delta (600) that
     * is smaller than the gap between adjacent name-match tiers (1000), so a
     * stronger name match in *any* scope still beats a weak match in a
     * higher-priority scope.
     */
    enum class Scope(val displayLabel: String, val order: Int, val rankBoost: Int) {
        ATTACHMENTS("attachments", 0, 500),
        MOUNT("mount", 1, 400),
        SHARED("shared", 2, 300),
        WORKSPACE("workspace", 3, 200),
        MEMORY("memory", 4, 100),
    }

    data class MountEntry(val name: String, val root: File)

    data class Entry(
        /** Linux-visible path (what gets inserted into the composer). */
        val linuxPath: String,
        val scope: Scope,
        val mountName: String?,
        val modifiedAt: Long,
        val isDirectory: Boolean,
    ) {
        val basename: String get() = linuxPath.substringAfterLast('/').ifEmpty { linuxPath }
        val displayPath: String get() = linuxPath.removePrefix("/var/minis/")
    }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var lastScanSessionId: String? = null
    private var lastScanAt: Long = 0
    private var currentScanToken: UUID = UUID.randomUUID()
    private var currentJob: Job? = null

    /** Rescan if the session changed or the cache went stale. Idempotent. */
    fun refreshIfNeeded(sessionId: String) {
        val now = System.currentTimeMillis()
        val sameSession = lastScanSessionId == sessionId
        val fresh = now - lastScanAt < cacheTtlMs
        if (sameSession && fresh && _entries.value.isNotEmpty()) return

        lastScanSessionId = sessionId
        lastScanAt = now
        val token = UUID.randomUUID()
        currentScanToken = token
        currentJob?.cancel()
        currentJob = scope.launch { scan(sessionId, token) }
    }

    /** Force a rescan regardless of cache. */
    fun refresh(sessionId: String) {
        lastScanAt = 0
        refreshIfNeeded(sessionId)
    }

    private suspend fun scan(sessionId: String, token: UUID) {
        _isScanning.value = true
        val collected = mutableListOf<Entry>()
        try {
            // Layer 1: this chat's workspace.
            // SessionWorkspace.hostDir is the same resolver the shell bind and
            // file_write use. A filed session's workspace lives under
            // minis-workspaces/<folder>, not minis-sessions/<id>, so scanning the
            // private tree here listed files the shell could not see and hid the
            // ones it could. The old parentFile/minis-sessions path was wrong on
            // both counts: this class is constructed with filesDir/minis-global.
            val appFilesDir = filesDir.parentFile ?: filesDir
            layerEntries(
                sessionId = sessionId,
                layers = listOf(
                    com.openminis.app.sandbox.SessionWorkspace.hostDir(appFilesDir, sessionId, "workspace") to Scope.WORKSPACE,
                    com.openminis.app.sandbox.SessionWorkspace.hostDir(appFilesDir, sessionId, "attachments") to Scope.ATTACHMENTS,
                    com.openminis.app.sandbox.SessionWorkspace.hostDir(appFilesDir, sessionId, "memory") to Scope.MEMORY,
                ),
                linuxRootFor = { scope -> "/var/minis/${scope.displayLabel}" },
            ).let { newBatch ->
                collected += newBatch
                publish(token, collected)
            }

            // Layer 2: shared files only. skills/ is invoked with `/`, never `@`.
            // filesDir here IS minis-global (see the Context constructor), which
            // is where the shell bind actually mounts shared/.
            layerEntries(
                sessionId = sessionId,
                layers = listOf(
                    File(filesDir, "shared") to Scope.SHARED,
                ),
                linuxRootFor = { scope -> "/var/minis/${scope.displayLabel}" },
            ).let { newBatch ->
                collected += newBatch
                publish(token, collected)
            }

            // Layer 3: mounts (each published individually so one slow mount
            // doesn't block the rest).
            var budget = GLOBAL_SCAN_BUDGET - collected.size
            for (mount in mountsProvider()) {
                if (currentScanToken != token) return
                // T219: align with PRoot bind path + iOS prompt wording.
                // Each mount lives under /var/minis/mounts/<name> inside the
                // sandbox; inserting that exact path keeps the agent's mental
                // model coherent across @-mention, prompt copy, and shell.
                val mountLinuxRoot = "/var/minis/mounts/${mount.name}"
                // Always inject the mount root so `@<name>` works even when budget is gone.
                collected += Entry(
                    linuxPath = mountLinuxRoot,
                    scope = Scope.MOUNT,
                    mountName = mount.name,
                    modifiedAt = mount.root.lastModified(),
                    isDirectory = true,
                )
                if (budget > 0) {
                    val mountBatch = scanDir(
                        root = mount.root,
                        linuxRoot = mountLinuxRoot,
                        scope = Scope.MOUNT,
                        mountName = mount.name,
                        maxDepth = MOUNT_SCAN_MAX_DEPTH,
                        budget = budget,
                    )
                    collected += mountBatch
                    budget -= mountBatch.size
                }
                publish(token, collected)
            }
        } finally {
            _isScanning.value = false
        }
    }

    private fun layerEntries(
        sessionId: String,
        layers: List<Pair<File, Scope>>,
        linuxRootFor: (Scope) -> String,
    ): List<Entry> {
        val out = mutableListOf<Entry>()
        for ((dir, scope) in layers) {
            if (!dir.isDirectory) continue
            val linuxRoot = linuxRootFor(scope)
            // Always include the root itself so `@workspace` / `@shared` can be referenced.
            out += Entry(
                linuxPath = linuxRoot,
                scope = scope,
                mountName = null,
                modifiedAt = dir.lastModified(),
                isDirectory = true,
            )
            out += scanDir(
                root = dir,
                linuxRoot = linuxRoot,
                scope = scope,
                mountName = null,
                maxDepth = Int.MAX_VALUE,
                budget = GLOBAL_SCAN_BUDGET,
            )
        }
        return out
    }

    /**
     * 递归收集 `@` 候选。
     *
     * [T-tools-files-walk-jail] 走归属安全的 [walkOwnedTree]（软链目录剪枝 + 逐项
     * canonical 归属复检），**不再**用 `listFiles()` 队列递归。旧实现（`maxDepth =
     * Int.MAX_VALUE` 的队列）会把越权软链下的**路径名**一起列进索引：guest 在工作区里
     * `ln -s ../../.. leak`（proot 的 `--link2symlink` 只翻译 link(2)，不改写 symlink
     * 目标，所以这就是宿主上的真实软链）之后，
     * `/var/minis/workspace/leak/minis-sessions/sess-b/memory/` 下的 md 文件会以
     * `/var/minis/workspace/leak/…` 的形式入索引、吃掉扫描预算，并被 `matches()` 当成
     * 可引用的 `@` 目标交给模型——名字本身就是泄漏。
     * （注意：此处不能出现「斜杠紧跟星号」的字面写法，Kotlin 块注释可嵌套，会把这段
     * KDoc 撕成两半。）
     *
     * [T-tools-files-walk-realpath] 相对路径的基准是 [SessionTreeGuard.canonicalRoot]
     * （walk 产出 canonical 路径，用调用方手里的词法根去算会得到 `../..`）。
     *
     * [T-walk-cage-single-resolve] 这里的根来自 `SessionWorkspace.hostDir`（app 自己建的
     * bind 根），没有经过 `acceptsResolved` 复检，所以守卫用默认工厂、自己解析**一次**——
     * 本层只有这一次解析，不存在「复检」与「遍历」各解析一次之间的改向窗口。
     * 工具侧（grep_source / glob / code_graph / list_dir）的根是 `resolveSessionHostPath`
     * 复检过的 canonical 路径，那里走 [SessionTreeGuard.verified]。
     *
     * 可见范围与旧 BFS 队列逐项对齐：深度 ≤ [maxDepth] 的目录会被展开，条目深度 ≤
     * `maxDepth + 1`；隐藏目录与 [SKIP_DIR_NAMES] 既不列出也不进入。
     * 深度判定写成 `depth - 1 > maxDepth`（而不是 `depth > maxDepth + 1`）：后者在
     * `maxDepth = Int.MAX_VALUE`（layerEntries 的会话层就是这个值）时会溢出成负数，
     * 把本来该全量的会话扫描变成空集。
     *
     * 顺序保持「浅层优先」：walk 是前序 DFS，这里按深度分桶再按深度拼接，预算不够时先
     * 保住顶层条目（与旧 BFS 队列的截断结果一致）。
     *
     * [T-mention-budget-visible-nodes] 预算只数**会入条目的节点**：隐藏项、SKIP 目录、
     * 超深目录、根外项都不占。旧写法对「走过的每一个节点」计数
     * （`nodeBudget = budget * 2 + 256`），于是 300 个点文件就能把预算吃光——`budget = 10`
     * 时整棵可见子树被静默丢掉，用户看到的是「工作区里的文件不见了」。被过滤节点仍有
     * 一个兜底上限（防海量垃圾节点把整棵树走完），命中即记 WARN 日志，不再静默截断。
     */
    internal fun scanDir(
        root: File,
        linuxRoot: String,
        scope: Scope,
        mountName: String?,
        maxDepth: Int,
        budget: Int,
    ): List<Entry> {
        if (budget <= 0) return emptyList()
        val guard = SessionTreeGuard(root)
        val buckets = ArrayList<MutableList<Entry>>()
        // 入条目节点的预算：最终只交出 budget 条，这里留一倍余量给深度分桶。
        val entryBudget = budget * 2 + 256
        // [T-mention-budget-visible-nodes] 被过滤节点的兜底：只防「海量点文件/垃圾目录把
        // 整棵树走完」，与可见条目互不挤占。
        val junkBudget = entryBudget * 8 + 4096
        var junk = 0
        var counted = 0
        val walk = walkOwnedTree(
            root,
            guard,
            enter = { dir ->
                // 只在目录上调用：先复检深度，再套隐藏/SKIP 规则（两者都与旧实现一致地
                // 「不列出也不进入」）。depthOf 理论上不会为 null（walk 已复检归属），
                // 这里按 fail-closed 处理成不进入。
                val depth = guard.depthOf(dir) ?: return@walkOwnedTree false
                !dir.name.startsWith(".") && dir.name !in SKIP_DIR_NAMES && depth <= maxDepth
            },
        )
        for (real in walk) {
            val name = real.name
            // 隐藏项一律不列出；SKIP 名单只作用于目录（旧实现同一条规则：同名普通文件照列）。
            if (name.startsWith(".") || (real.isDirectory && name in SKIP_DIR_NAMES)) {
                if (++junk > junkBudget) {
                    warnTruncated(root, junk, counted, "filtered nodes")
                    break
                }
                continue
            }
            val depth = guard.depthOf(real) ?: continue
            if (depth < 1 || depth - 1 > maxDepth) continue
            val relative = guard.relativePath(real) ?: continue
            if (relative.isEmpty()) continue
            if (counted >= entryBudget) {
                warnTruncated(root, junk, counted, "entry budget")
                break
            }
            counted++
            while (buckets.size < depth) buckets.add(ArrayList())
            buckets[depth - 1] += Entry(
                linuxPath = "$linuxRoot/$relative",
                scope = scope,
                mountName = mountName,
                modifiedAt = real.lastModified(),
                isDirectory = real.isDirectory,
            )
        }
        val out = ArrayList<Entry>()
        for (bucket in buckets) {
            for (entry in bucket) {
                if (out.size >= budget) return out
                out += entry
            }
        }
        return out
    }

    /**
     * [T-mention-budget-visible-nodes] 截断不再是静默的：预算/兜底上限命中时留一条 WARN。
     *
     * 调用点在 `scanDir` 的 IO 线程上（[scan] 跑在 [Dispatchers.IO]），不做额外调度。
     */
    private fun warnTruncated(root: File, junk: Int, counted: Int, reason: String) {
        AppLogger.warning(
            LOG_CATEGORY,
            "mention scan stopped early on $reason for $root: " +
                "$counted entry candidate(s) collected, $junk filtered node(s) skipped — " +
                "the visible subtree may be incomplete",
        )
    }

    private suspend fun publish(token: UUID, collected: List<Entry>) {
        if (currentScanToken != token) return
        // Deduplicate by linuxPath (later scans may revisit) and sort per defaultOrdering.
        val deduped = collected
            .filterNot { isSkillPath(it.linuxPath) }
            .associateBy { it.linuxPath }
            .values
        val sorted = deduped.sortedWith(defaultOrdering)
        withContext(Dispatchers.Main) {
            if (currentScanToken != token) return@withContext
            _entries.value = sorted
        }
    }

    /**
     * Rank entries against [query]. Empty query returns the freshest entries
     * in `defaultOrdering` (already applied to `_entries.value`).
     *
     * Score components (added together, biggest first):
     *   • name-match tier (0 / 1000 … 10000) — drives the primary order
     *   • Scope.rankBoost (0–600) — secondary preference between scopes
     *   • top-level scope-root bonus (0 / 50) — keeps a whole skill or
     *     mount above its children
     *   • recency micro-bonus (0–80) — newer wins on otherwise-equal items
     *
     * The smallest gap between adjacent name tiers (1000) is greater than
     * the maximum sum of all secondary signals (600 + 50 + 80 = 730), so a
     * stronger name match always wins regardless of scope.
     */
    fun matches(query: String, limit: Int = DEFAULT_MATCH_LIMIT): List<Entry> {
        val all = _entries.value.filterNot { isSkillPath(it.linuxPath) }
        if (query.isBlank()) return all.take(limit)
        val q = query.lowercase()

        val now = System.currentTimeMillis()
        val recencyDayCapMs: Long = 56L * 24 * 60 * 60 * 1000 // 8 weeks → 0..80

        val scored = mutableListOf<Pair<Int, Entry>>()
        for (entry in all) {
            val base = entry.basename.lowercase()
            val path = entry.linuxPath.lowercase()
            val stem = base.substringBeforeLast('.', missingDelimiterValue = base)

            // Tier-based name match — pick the strongest applicable tier.
            val nameScore = when {
                base == q -> 10_000                                        // exact full name
                stem.isNotEmpty() && stem == q -> 9_000                    // exact stem (basename minus ext)
                base.startsWith(q) -> 7_000                                // prefix match
                basenameWordBoundaryContains(base, q) -> 6_000             // word-boundary contains
                q in base -> 4_000                                         // generic contains in basename
                anyPathComponentMatches(path, q) -> 2_000                  // ancestor directory contains
                q in path -> 1_000                                         // anywhere in full path
                else -> 0
            }
            if (nameScore == 0) continue

            // Scope priority (max 600 — well below the 1000 tier gap).
            val scopeScore = entry.scope.rankBoost

            // Top-level scope-root bonus: typing `@photos` should surface the
            // whole mount or shared directory above its children.
            val topLevelBonus = if (isTopLevelScopeRoot(entry)) 50 else 0

            // Recency micro-bonus (≤ 80). Linear decay over 8 weeks.
            val ageMs = (now - entry.modifiedAt).coerceAtLeast(0L)
            val recencyBonus = (80.0 *
                (1.0 - (ageMs.toDouble() / recencyDayCapMs.toDouble()).coerceIn(0.0, 1.0)))
                .toInt()
                .coerceAtLeast(0)

            scored += (nameScore + scopeScore + topLevelBonus + recencyBonus) to entry
        }
        return scored
            .sortedWith(
                compareByDescending<Pair<Int, Entry>> { it.first }
                    // Tiebreak: prefer higher-priority scope, then fresher,
                    // then shorter path (a "closer" file).
                    .thenBy { it.second.scope.order }
                    .thenByDescending { it.second.modifiedAt }
                    .thenBy { it.second.linuxPath.length }
            )
            .take(limit)
            .map { it.second }
    }

    /**
     * Word-boundary substring match for the basename. Returns true if
     * `query` appears at the start of a word fragment delimited by
     * `_`, `-`, ` `, `.`, `/` (e.g. matches `parser` in `markdown_parser.py`,
     * `web` in `image-web-search.md`). Helps elevate the kind of partial
     * match users intuitively expect over arbitrary-position substrings.
     */
    private fun basenameWordBoundaryContains(base: String, query: String): Boolean {
        if (query.isEmpty() || base.isEmpty()) return false
        val separators = setOf('_', '-', ' ', '.', '/')
        var i = 0
        while (i < base.length) {
            if (base.startsWith(query, startIndex = i)) return true
            // Advance to the next word boundary.
            val sep = (i until base.length).firstOrNull { separators.contains(base[it]) }
                ?: return false
            i = sep + 1
        }
        return false
    }

    /**
     * Substring search across path components — the same path may match
     * even when the basename doesn't (e.g. typing `@mounts` finds files
     * inside `/var/minis/mounts/...`).
     */
    private fun anyPathComponentMatches(path: String, query: String): Boolean {
        return path.split('/').any { it.contains(query) }
    }

    /**
     * Is this entry the immediate child of a scope root? e.g.
     * `/var/minis/mounts/bar`, `/var/minis/shared/baz`. These first-class
     * `@`-targets get a +50 ranking bonus so typing `@foo` surfaces the
     * directory above its individual files. Skill directories are not
     * `@` targets.
     */
    private fun isTopLevelScopeRoot(entry: Entry): Boolean {
        if (!entry.isDirectory) return false
        val tops = listOf(
            "/var/minis/mounts/",
            "/var/minis/shared/",
        )
        for (top in tops) {
            if (entry.linuxPath.startsWith(top)) {
                val rest = entry.linuxPath.removePrefix(top)
                // First-level child only — must not contain a further '/'.
                return rest.isNotEmpty() && '/' !in rest
            }
        }
        return false
    }

    private fun isSkillPath(linuxPath: String): Boolean =
        linuxPath == "/var/minis/skills" || linuxPath.startsWith("/var/minis/skills/")

    private val defaultOrdering: Comparator<Entry> = compareBy<Entry> { it.scope.order }
        .thenByDescending { isTopLevelScopeRoot(it) }
        .thenBy { it.isDirectory }
        .thenByDescending { it.modifiedAt }

    companion object {
        /**
         * [T-mention-budget-visible-nodes] 截断日志的分类（AppLogger 的 category）。
         */
        private const val LOG_CATEGORY = "FileMentionIndex"

        const val DEFAULT_CACHE_TTL_MS = 10L * 60 * 1000
        const val DEFAULT_MATCH_LIMIT = 100
        const val GLOBAL_SCAN_BUDGET = 5000
        const val MOUNT_SCAN_MAX_DEPTH = 3

        private val SKIP_DIR_NAMES = setOf(
            ".git", ".svn", ".hg",
            "node_modules", ".venv", "venv", "__pycache__",
            ".build", ".gradle", ".idea", ".tox",
            ".mypy_cache", ".pytest_cache", ".ruff_cache",
        )
    }
}
