package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * `grep_source` — sub-agent-only fast source search.
 *
 * Returns every line matching a pattern plus its surrounding context in one
 * call, replacing the "file_read page 1 → page 2 → page 3 …" loop that burns
 * sub-agent turns. Cheaper than shell_execute (`grep -n`) because there is no
 * PersistentShell round-trip, and it can also fan out across a directory.
 *
 * Deliberately NOT registered in the main-session tool list: the coordinator
 * already has shell_execute for this. See AgentTools.makeSubAgentExtraTools.
 *
 * [T-tools-files-walk-jail] 目录分支的命中集合由 [searchableFiles] 产出
 * （[walkOwnedTree] 的软链剪枝 + 逐项 canonical 归属复检），不再手写递归。
 * [T-b11-grep-regex-wall-clock] `is_regex=true` 的整趟扫描跑在工作线程上并受
 * 墙钟与进程级预算 [RegexScanBudget] 约束；超时是弃车，不是中断。
 */
object GrepSourceTool {
    const val NAME = "grep_source"

    private const val DEFAULT_CONTEXT = 2
    private const val MAX_CONTEXT = 6
    private const val MAX_MATCHES = 50
    private const val MAX_FILES = 200
    private const val MAX_FILE_BYTES = 512 * 1024
    private const val OUTPUT_HARD_CAP = 40_000

    /**
     * [T-b11-grep-regex-wall-clock] `is_regex=true` 的墙钟上限。
     *
     * java.util.regex 的灾难性回溯跑在 native 循环里：不计数、不响应
     * `Thread.interrupt`、也不响应协程取消，所以超时之后唯一能做的是**弃车**
     * （把整趟扫描连同它的 lines/out 留在 daemon 线程上）并把控制权交还调用方。
     */
    private const val REGEX_SCAN_WALL_CLOCK_MS = 2_000

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Search file(s) for a pattern and return matching lines with ±context in ONE call. Prefer this over paging file_read through a big file or running shell grep — it saves turns. Sub-agents only.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this search does, shown to the user."),
            "pattern" to AgentToolParam("string", "Text or regex to match (case-insensitive when is_regex=false)."),
            "path" to AgentToolParam("string", "Absolute file or directory path to search (default /var/minis/workspace)."),
            "context" to AgentToolParam("integer", "Lines of context around each match (0-6, default 2)."),
            "is_regex" to AgentToolParam("boolean", "Treat pattern as a regex (default false = literal substring)."),
            "max_matches" to AgentToolParam("integer", "Cap on matches returned (default 50)."),
        ),
        required = listOf("tool_title", "pattern"),
        propertyOrdering = listOf("tool_title", "pattern", "path", "context", "is_regex", "max_matches"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val toolTitle = try { JSONObject(argsJson).optString("tool_title", NAME) } catch (_: Exception) { NAME }
        return try {
            val args = JSONObject(argsJson)
            val pattern = args.optString("pattern", "")
            if (pattern.isBlank()) {
                return ToolExecutionResult("Error: 'pattern' is required", false, toolTitle = toolTitle)
            }
            val rawPath = args.optString("path", "/var/minis/workspace").ifBlank { "/var/minis/workspace" }
            val ctx = args.optInt("context", DEFAULT_CONTEXT).coerceIn(0, MAX_CONTEXT)
            val isRegex = args.optBoolean("is_regex", false)
            val maxMatches = args.optInt("max_matches", MAX_MATCHES).coerceIn(1, 200)

            val matcher: (String) -> Boolean = if (isRegex) {
                val re = try {
                    Regex(pattern, setOf(RegexOption.IGNORE_CASE))
                } catch (e: Exception) {
                    return ToolExecutionResult("Error: invalid regex: ${e.message}", false, toolTitle = toolTitle)
                }
                ({ line -> re.containsMatchIn(line) })
            } else {
                val needle = pattern.lowercase()
                ({ line -> line.lowercase().contains(needle) })
            }

            val root = PRootKernel.resolveSessionHostPath(sessionId, rawPath, context)
                ?: return ToolExecutionResult("Error: Cannot resolve path: $rawPath", false, toolTitle = toolTitle)
            if (!root.exists()) {
                return ToolExecutionResult("Error: Path not found: $rawPath", false, toolTitle = toolTitle)
            }

            val files: List<File> = when {
                root.isFile -> listOf(root)
                // [T-walk-cage-single-resolve] root 是 resolveSessionHostPath 复检时用的那条
                // canonical 路径：直接钉成守卫基准，工具侧不再解析第二次。
                root.isDirectory -> searchableFiles(root, guard = SessionTreeGuard.verified(root))
                else -> emptyList()
            }
            if (files.isEmpty()) {
                return ToolExecutionResult("No searchable files under: $rawPath", false, toolTitle = toolTitle)
            }

            val outcome: ScanOutcome = if (isRegex) {
                // [T-b11-grep-regex-budget] 预算在**起线程之前**就占：同一个 (pattern, root)
                // 已经在跑、或者弃车线程已经到顶，直接短路拒绝——否则每调一次就多一个
                // 永久烧 CPU 的线程（它持有 lines/out，且没有任何上限）。
                val lease = RegexScanBudget.acquire(scanKey(pattern, root))
                    ?: return ToolExecutionResult(budgetRefusalMessage(pattern, rawPath), false, toolTitle = toolTitle)
                // 扫描结果从工作线程带回来：scanWithWallClock 的返回值只用来区分「跑完」与「弃车」。
                val captured = AtomicReference<ScanOutcome?>()
                val completed = try {
                    scanWithWallClock(REGEX_SCAN_WALL_CLOCK_MS, lease) {
                        captured.set(scanFiles(files, matcher, ctx, maxMatches))
                        ToolExecutionResult("", true)
                    }
                } catch (e: StackOverflowError) {
                    // 病态 pattern 的回溯会把工作线程的栈打穿；这种 Error 不能逃出工具边界。
                    return ToolExecutionResult(
                        backtrackFailureMessage(pattern, rawPath, e), false, toolTitle = toolTitle)
                }
                if (completed == null) {
                    // 弃车：**不设** errorCode=TIMEOUT —— ToolRetry.isTransient 只认
                    // NETWORK_ERROR/TIMEOUT，设了框架层就会自动重试，而重试只会再弃车一个
                    // 线程。timedOut 只驱动 UI 的时钟图标，不参与重试判定。
                    return ToolExecutionResult(
                        wallClockMessage(pattern, rawPath), false, toolTitle = toolTitle, timedOut = true)
                }
                captured.get() ?: ScanOutcome(0, 0, 0, StringBuilder())
            } else {
                // 字面量匹配没有回溯风险：不占预算、不起线程，行为与修复前一致。
                scanFiles(files, matcher, ctx, maxMatches)
            }

            if (outcome.totalMatches == 0) {
                return ToolExecutionResult(
                    "No matches for \"$pattern\" in $rawPath (scanned ${outcome.scanned} file(s))",
                    true, toolTitle = toolTitle,
                )
            }

            var body = outcome.body.toString().trimEnd()
            var note = ""
            if (body.length > OUTPUT_HARD_CAP) {
                body = body.take(OUTPUT_HARD_CAP)
                note = "\n…[output truncated at ${OUTPUT_HARD_CAP} chars — narrow path or pattern]…"
            }
            val header = buildString {
                append("[grep_source: \"$pattern\" in $rawPath — ")
                append(outcome.totalMatches).append(" match(es) across ").append(outcome.hitFiles)
                append(" file(s), scanned ").append(outcome.scanned).append("]")
                if (outcome.totalMatches >= maxMatches) append(" (match cap reached)")
            }
            ToolExecutionResult(header + "\n\n" + body + note, true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error: ${e.message ?: e.javaClass.simpleName}", false, toolTitle = toolTitle)
        }
    }

    /**
     * [T-tools-files-walk-jail] 递归收集可搜索文件：走归属安全的 [walkOwnedTree]
     * （软链目录剪枝 + 逐项 canonical 复检），而**不是** `walkTopDown` / 手写 `listFiles` 递归。
     *
     * 旧实现用 `FileTreeWalk`：`File.isDirectory` 会解引用软链，guest 在工作区里
     * `ln -s ../../.. leak`（proot 的 `--link2symlink` 只翻译 link(2)，不改写 symlink 目标，
     * 所以这就是宿主上的真实软链）之后，`workspace/leak/minis-sessions/<别的会话>/memory/secret.md`
     * 会被当成工作区文件逐个读出来——实测一条软链命中 63 个跨会话/宿主敏感文件。
     * 根路径的归属由 `PRootKernel.resolveSessionHostPath` → `SessionWorkspace.acceptsResolved`
     * 判定过，遍历出来的**每一项**必须复检；根内的软链（canonical 仍在根内）不算越权，
     * 仍然照常跟随。
     *
     * [T-tools-files-walk-realpath] 收集到的是 **canonical 路径**，[scanFiles] 随后按它
     * `bufferedReader()`：复检与读取之间不再隔着一条可被 guest 改写的软链（旧实现交出去的是
     * 词法路径，`workspace/alias/secret.md` 在两步之间被改向就读到了根外）。
     *
     * [T-walk-cage-single-resolve] [execute] 传入的 [guard] 是拿 `resolveSessionHostPath`
     * 回传的 canonical 根钉出来的（[SessionTreeGuard.verified]），所以「复检」与「遍历」
     * 不再是两次解析；默认值只服务调用方没有复检过的根（单测）。剩下的窗口见
     * [walkOwnedTree] 的 KDoc。
     *
     * 独立成函数是为了能在纯 JVM 单测里直接钉住命中集合（[execute] 需要 Context）。
     */
    internal fun searchableFiles(
        root: File,
        maxFiles: Int = MAX_FILES,
        guard: SessionTreeGuard = SessionTreeGuard(root),
    ): List<File> {
        // 单文件根：归属已由调用方判定，这里不再重复（与修复前的行为一致）。
        if (root.isFile) return listOf(root)
        val files = ArrayList<File>()
        for (candidate in walkOwnedTree(root, guard)) {
            if (files.size >= maxFiles) break
            if (!candidate.isFile) continue
            if (candidate.length() !in 1..MAX_FILE_BYTES.toLong()) continue
            files += candidate
        }
        return files
    }

    /**
     * [T-b11-grep-regex-wall-clock] 整趟文件扫描（读行 → 匹配 → 拼输出）。
     *
     * 抽成独立函数是因为它必须能整体丢进工作线程：正则回溯一旦烧起来，只有让**整趟**
     * 扫描都在别的线程上，调用方才有机会在墙钟到点时拿回控制权。语义与修复前逐行一致
     * （`lines.indices.filter` + 去重上下文窗口 + 命中上限）。
     */
    private fun scanFiles(
        files: List<File>,
        matcher: (String) -> Boolean,
        ctx: Int,
        maxMatches: Int,
    ): ScanOutcome {
        val out = StringBuilder()
        var totalMatches = 0
        var hitFiles = 0
        var scanned = 0
        for (f in files) {
            if (totalMatches >= maxMatches) break
            scanned++
            val lines = try {
                f.bufferedReader().useLines { it.toList() }
            } catch (_: Exception) {
                continue // binary or unreadable — skip
            }
            val hitIdx = lines.indices.filter { matcher(lines[it]) }
            if (hitIdx.isEmpty()) continue
            hitFiles++
            out.append("=== ").append(f.absolutePath).append(" ===\n")
            var lastPrinted = -1
            for (i in hitIdx) {
                if (totalMatches >= maxMatches) {
                    out.append("  … more matches in this file (cap) …\n")
                    break
                }
                val lo = (i - ctx).coerceAtLeast(0)
                val hi = (i + ctx).coerceAtMost(lines.size - 1)
                if (lo > lastPrinted + 1 && lastPrinted >= 0) out.append("  --\n")
                for (n in lo..hi) {
                    if (n <= lastPrinted) continue
                    val mark = if (n == i) ">" else " "
                    out.append(mark).append(n + 1).append(": ").append(lines[n]).append('\n')
                    lastPrinted = n
                }
                totalMatches++
            }
        }
        return ScanOutcome(totalMatches, hitFiles, scanned, out)
    }

    /** [T-b11-grep-regex-wall-clock] 一趟扫描的结果（工作线程 → 调用方的搬运体）。 */
    private class ScanOutcome(
        val totalMatches: Int,
        val hitFiles: Int,
        val scanned: Int,
        val body: StringBuilder,
    )

    /**
     * [T-b11-grep-regex-wall-clock] 墙钟兜底：把 [scan] 放到 daemon 工作线程上跑，
     * 最多等 [timeoutMs]。
     *
     * - 跑完 ⇒ 返回它的结果；工作线程在 `finally` 里已经销账，正常路径**不占预算**；
     * - 抛异常 ⇒ 在**调用方线程**上原样重抛（回溯把栈打穿时表现为 StackOverflowError，
     *   不能让它被工作线程吞掉，否则调用方拿到的是一个假的「无匹配」）；
     * - 超时 ⇒ 弃车：给 [lease] 记一笔「已弃车」后返回 null。线程还在烧核且不响应
     *   interrupt，所以这里不做任何 `interrupt()`/`stop()` 的表演。
     *
     * [lease] 为 null 表示调用方不走预算（单测与无预算调用方）。
     */
    internal fun scanWithWallClock(
        timeoutMs: Int,
        lease: RegexScanBudget.Lease? = null,
        scan: () -> ToolExecutionResult,
    ): ToolExecutionResult? {
        val holder = AtomicReference<ToolExecutionResult?>()
        val failure = AtomicReference<Throwable?>()
        val worker = Thread({
            try {
                holder.set(scan())
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                // 正常跑完 / 抛异常 / 被弃车之后才跑完：都由这里销账（幂等）。
                lease?.let { RegexScanBudget.release(it) }
            }
        }, "grep-source-regex-scan")
        worker.isDaemon = true
        try {
            worker.start()
        } catch (t: Throwable) {
            // 线程起不来（OOM/线程数上限）：预算必须还回去，否则这个 key 被永久占死。
            lease?.let { RegexScanBudget.release(it) }
            throw t
        }
        try {
            worker.join(timeoutMs.toLong())
        } catch (e: InterruptedException) {
            // 调用方自己被取消：工作线程已经在跑且不响应中断，按弃车记账再把中断还回去。
            lease?.let { RegexScanBudget.markAbandoned(it) }
            Thread.currentThread().interrupt()
            return null
        }
        if (worker.isAlive) {
            lease?.let { RegexScanBudget.markAbandoned(it) }
            return null
        }
        failure.get()?.let { throw it }
        return holder.get()
    }

    /**
     * [T-b11-grep-regex-budget] 弃车线程的**进程级预算**。
     *
     * 墙钟只保证调用方不等：被弃车的线程还在烧核，还持有那趟扫描的 lines/out，
     * 旧实现没有任何上限，同一个病态 pattern 被模型重复调用就能把核烧光。这里把
     * 「同一 (pattern, root)」和「全进程弃车线程数」都挡在起线程之前：
     *
     * - [acquire] 用 `putIfAbsent` 占位：同一 key 还在跑（或被弃车后仍在烧）⇒ 返回 null；
     * - [MAX_ABANDONED] 个槽位被弃车线程占满后，**任何**新的正则扫描都不再放行；
     * - 账本是 0 运行中 / 1 已弃车 / 2 已结束 的 CAS 状态机（[Lease.state]），
     *   [release] 幂等：正常跑完、线程起不来、扫描抛异常都不会漏账或重复销账。
     */
    internal object RegexScanBudget {
        /** 允许同时弃车的线程数上限；到顶后新的正则扫描一律拒绝。 */
        const val MAX_ABANDONED = 2

        private const val RUNNING = 0
        private const val ABANDONED = 1
        private const val ENDED = 2

        private val leases = ConcurrentHashMap<String, Lease>()
        private val abandoned = AtomicInteger(0)

        /** 一次正则扫描的占位票据；[state] 见 [RUNNING] / [ABANDONED] / [ENDED]。 */
        class Lease internal constructor(val key: String) {
            val state = AtomicInteger(RUNNING)
        }

        /** 放行则返回票据，短路拒绝返回 null（一定发生在起线程之前）。 */
        fun acquire(key: String): Lease? {
            if (abandoned.get() >= MAX_ABANDONED) return null
            val lease = Lease(key)
            if (leases.putIfAbsent(key, lease) != null) return null
            // 占位之后再确认一次预算（另一个调用方可能刚把槽位烧掉）：不通过就撤销占位。
            if (abandoned.get() >= MAX_ABANDONED) {
                if (leases.remove(key, lease)) lease.state.set(ENDED)
                return null
            }
            return lease
        }

        /** 该 key 是否有一个已弃车、还在烧核的线程。 */
        fun isBurning(key: String): Boolean = leases[key]?.state?.get() == ABANDONED

        /** 运行中 ⇒ 已弃车（只算一次；已经结束的票据不会再被记账）。 */
        fun markAbandoned(lease: Lease) {
            if (lease.state.compareAndSet(RUNNING, ABANDONED)) abandoned.incrementAndGet()
        }

        /** 已弃车 ⇒ 已结束（归还预算），运行中 ⇒ 已结束（正常跑完）。幂等。 */
        fun release(lease: Lease) {
            while (true) {
                val state = lease.state.get()
                if (state == ENDED) return
                if (!lease.state.compareAndSet(state, ENDED)) continue
                leases.remove(lease.key, lease)
                if (state == ABANDONED) abandoned.decrementAndGet()
                return
            }
        }

        fun abandonedCount(): Int = abandoned.get()
    }

    /**
     * [T-b11-grep-regex-slow-vs-backtrack] 超时文案：先说「超了多少、已经弃车」，
     * 再按 pattern **自身的形状**区分两种完全不同的原因——
     *
     * - 有嵌套量词（[looksLikeCatastrophicBacktracking]）⇒ 真正的灾难性回溯，
     *   必须改写 pattern；
     * - 没有这个形状 ⇒ 可能只是这次扫得慢（目录大 / 文件多），该收窄 path 或降低
     *   max_matches。旧实现一律断言「回溯」，把一个慢查询说成病态 pattern，
     *   把模型引到错误的方向上。
     *
     * 墙钟本身只证明「慢」，不证明「为什么慢」——判据因此只看 pattern 形状。
     */
    private fun wallClockMessage(pattern: String, rawPath: String): String {
        val head = "Error: regex scan of \"$pattern\" in $rawPath exceeded ${REGEX_SCAN_WALL_CLOCK_MS}ms " +
            "and was abandoned (the scan thread is still running and cannot be interrupted)"
        return if (looksLikeCatastrophicBacktracking(pattern)) {
            "$head. The pattern contains nested quantifiers (a quantified group whose body is itself " +
                "quantified), the shape java.util.regex backtracks on catastrophically — rewrite it " +
                "(e.g. `(a+)+` to `a+`, `(.*)*` to `.*`) and retry, or use is_regex=false."
        } else {
            "$head. No nested-quantifier signature was found in the pattern, so this may simply be a " +
                "slow scan over many or large files: search a single file or a narrower `path`, lower " +
                "`max_matches`, or use is_regex=false."
        }
    }

    /** 回溯把工作线程的栈打穿（StackOverflowError）时的文案。 */
    private fun backtrackFailureMessage(pattern: String, rawPath: String, cause: Throwable): String =
        "Error: regex scan of \"$pattern\" in $rawPath blew the stack (${cause.javaClass.simpleName}) — " +
            "java.util.regex recursed while backtracking. Rewrite the pattern without nested quantifiers, then retry."

    /** 预算拒绝的文案（不是超时，也不是参数错：稍后重试或换 pattern/path）。 */
    private fun budgetRefusalMessage(pattern: String, rawPath: String): String =
        "Error: refusing to start a regex scan of \"$pattern\" in $rawPath — an identical scan is still running, " +
            "or ${RegexScanBudget.MAX_ABANDONED} abandoned scans are already burning CPU in this process. " +
            "Narrow the pattern or path, retry later, or use is_regex=false."

    /** [T-b11-grep-regex-budget] 预算键：同一个 (pattern, root) 才算「同一趟扫描」。 */
    private fun scanKey(pattern: String, root: File): String {
        val base = try {
            root.canonicalPath
        } catch (_: Exception) {
            root.absolutePath
        }
        return "$base\u0000$pattern"
    }

    /**
     * [T-b11-grep-regex-slow-vs-backtrack] 灾难性回溯的**判据**（只看 pattern 形状，
     * 不看墙钟）：一个分组**自身带量词**，而它的组内**已经出现过量词**——`(a+)+`、
     * `(.*)*`、`(\w+\s?)*` 属于这一类。
     *
     * 刻意不判的两种情况：单层量词（`[a-z]+`、`fun .*\(`）与只被量词用到的组但组内没有
     * 量词（`(a+)b`）——它们只是普通/稍慢的正则，说成回溯就是错误归因。
     * `\(` 与字符类里的 `+`/`(` 都是字面量，必须跳过，否则 `fun .*\(` 会被误判。
     */
    internal fun looksLikeCatastrophicBacktracking(pattern: String): Boolean {
        // 每个未闭合分组的「组内是否已经出现过量词」标记。
        val groups = ArrayDeque<Boolean>()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> i += 2 // 转义字符：`\(` / `\+` 是字面量

                c == '[' -> { // 字符类：[a-z]+ 里的 `+` 在类外，类内的 `+`/`(` 都是字面量
                    i++
                    while (i < pattern.length && pattern[i] != ']') i += (if (pattern[i] == '\\') 2 else 1)
                    i++
                }

                c == '(' -> {
                    i += (if (pattern.getOrNull(i + 1) == '?') groupPrefixLength(pattern, i) else 1)
                    groups.addLast(false)
                }

                c == ')' -> {
                    val bodyQuantified = groups.removeLastOrNull() ?: false
                    val groupQuantified = isQuantifierAt(pattern, i + 1)
                    if (bodyQuantified && groupQuantified) return true
                    // 组自身带量词时把「已量化」冒泡给外层，覆盖 ((a+))* 这类写法。
                    if ((bodyQuantified || groupQuantified) && groups.isNotEmpty()) {
                        groups[groups.size - 1] = true
                    }
                    i++
                }

                isQuantifierAt(pattern, i) -> {
                    if (groups.isNotEmpty()) groups[groups.size - 1] = true
                    i++
                }

                else -> i++
            }
        }
        return false
    }

    /** `(` 之后的构造前缀长度：`(?:` / `(?=` / `(?!` / `(?>` / `(?<=` / `(?<!` / `(?<name>`。 */
    private fun groupPrefixLength(pattern: String, open: Int): Int = when (val after = pattern.getOrNull(open + 2)) {
        null -> 1
        ':', '=', '!', '>' -> 3
        '<' -> {
            val next = pattern.getOrNull(open + 3)
            if (next == '=' || next == '!') {
                4
            } else {
                val close = pattern.indexOf('>', open + 3)
                if (close < 0) pattern.length - open else close - open + 1
            }
        }
        else -> 1 // (?i) 之类的标志组：当普通组处理，闭合前不会有误判
    }

    /** [idx] 处是不是量词（`{` 只在后面跟着数字/逗号时才算）。 */
    private fun isQuantifierAt(pattern: String, idx: Int): Boolean = when (pattern.getOrNull(idx)) {
        '*', '+', '?' -> true
        '{' -> pattern.getOrNull(idx + 1)?.let { it.isDigit() || it == ',' } == true
        else -> false
    }
}
