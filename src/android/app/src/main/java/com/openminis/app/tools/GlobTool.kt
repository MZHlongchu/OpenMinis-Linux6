package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject
import java.io.File

/**
 * Workspace glob. Paths go through [PRootKernel].
 *
 * Adapted from XINCODE-Public GlobTool (GPL-3.0-or-later).
 */
object GlobTool {
    const val NAME = "glob"
    private const val MAX_HITS = 500
    private const val MAX_WALK = 4_000

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Find files by glob (e.g. **/*.kt) under a Linux path. Prefer this over find/ls pipelines.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "pattern" to AgentToolParam("string", "Glob pattern. * matches one path segment, ** matches recursively."),
            "path" to AgentToolParam("string", "Root Linux path (default /var/minis/workspace)."),
        ),
        required = listOf("tool_title", "pattern"),
        propertyOrdering = listOf("tool_title", "pattern", "path"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val toolTitle = try { JSONObject(argsJson).optString("tool_title", NAME) } catch (_: Exception) { NAME }
        return try {
            val args = JSONObject(argsJson)
            val pattern = args.optString("pattern", "")
            if (pattern.isBlank()) return ToolExecutionResult("Error: pattern required", false, toolTitle = toolTitle)
            val path = args.optString("path", "/var/minis/workspace").ifBlank { "/var/minis/workspace" }
            val root = PRootKernel.resolveSessionHostPath(sessionId, path, context)
                ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false, toolTitle = toolTitle)
            if (!root.exists()) return ToolExecutionResult("Error: not found: $path", false, toolTitle = toolTitle)
            val regex = globToRegex(pattern)
            // [T-walk-cage-single-resolve] root 是 resolveSessionHostPath 复检时用的那条
            // canonical 路径：同一枚守卫既用于收集命中，也用于算展示用的相对路径，
            // 工具侧不再解析第二次。
            val guard = SessionTreeGuard.verified(root)
            val sorted = collectHits(root, regex, guard = guard)
                .sortedByDescending { it.lastModified() }.take(MAX_HITS)
            // [T-tools-files-walk-realpath] 相对路径的基准是 root 的 canonical 路径：
            // walkOwnedTree 产出的就是 canonical 路径，用词法 root 去 relativeTo 会算出
            // `../../x`（root 自身含软链分量时）。
            val base = if (root.isDirectory) guard else null
            val body = buildString {
                append("${sorted.size} files for $pattern under $path\n")
                for (f in sorted) {
                    val rel = base?.relativePath(f)?.takeIf { it.isNotEmpty() } ?: f.name
                    append(rel)
                    append('\n')
                }
            }
            ToolExecutionResult(body, true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error glob: ${e.message}", false, toolTitle = toolTitle)
        }
    }

    /**
     * [T-tools-files-walk-jail] 命中集合：归属安全的遍历（软链目录剪枝 + 逐项 canonical
     * 复检，见 [walkOwnedTree]）+ glob 匹配。
     *
     * [T-tools-files-walk-realpath] [walkOwnedTree] 产出的是 canonical 路径，所以相对路径
     * 的基准必须是 [SessionTreeGuard.canonicalRoot]：拿调用方手里那个（可能含软链的）词法
     * root 去 `relativeTo` 会算出 `../../x`，而 `**` 恰好能匹配上这种串——等于把根外的
     * 名字放回来。模式匹配与 [execute] 的展示路径共用这一套基准。
     *
     * 独立成函数是为了能在纯 JVM 单测里直接钉住「工作区里的越权软链不会出现在结果里」
     * （execute 需要 Context，本模块单测没有 Robolectric）。旧实现是手写递归 +
     * `listFiles()`：`File.isDirectory` 会解引用软链，所以 `ln -s ../../.. leak` 之后
     * 工作区里那个 `leak` 目录下的递归通配匹配会被当成工作区文件列出来。
     * （注意：注释里不要出现裸的斜杠加星号组合，Kotlin 块注释可嵌套，会把 KDoc 撕开。）
     *
     * [T-walk-cage-single-resolve] [execute] 传入的 [guard] 是拿 `resolveSessionHostPath`
     * 回传的 canonical 根钉出来的（[SessionTreeGuard.verified]）；默认值只服务没有复检过
     * 的根（单测）。剩下的窗口见 [walkOwnedTree] 的 KDoc。
     */
    internal fun collectHits(
        root: File,
        pattern: Regex,
        maxHits: Int = MAX_HITS,
        maxWalk: Int = MAX_WALK,
        guard: SessionTreeGuard = SessionTreeGuard(root),
    ): List<File> {
        if (root.isFile) return if (pattern.matches(root.name)) listOf(root) else emptyList()
        val hits = mutableListOf<File>()
        val nodes = walkOwnedTree(root, guard, enter = { !it.name.startsWith(".git") })
        for (f in nodes.take(maxWalk)) {
            if (hits.size >= maxHits) break
            if (f.name.startsWith(".git")) continue
            val rel = guard.relativePath(f) ?: continue // fail-closed：不在根内就不匹配
            if (rel.isEmpty()) continue
            if (f.isFile && pattern.matches(rel)) hits += f
        }
        return hits
    }

    internal fun globToRegex(pattern: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        while (i < pattern.length) {
            when {
                pattern.startsWith("**/", i) -> {
                    sb.append("(?:.*/)?")
                    i += 3
                }
                pattern.startsWith("**", i) -> {
                    sb.append(".*")
                    i += 2
                }
                pattern[i] == '*' -> {
                    sb.append("[^/]*")
                    i++
                }
                pattern[i] == '?' -> {
                    sb.append("[^/]")
                    i++
                }
                else -> {
                    val c = pattern[i]
                    if (c in ".$()[]{}+^|\\") sb.append('\\')
                    sb.append(c)
                    i++
                }
            }
        }
        sb.append('$')
        return Regex(sb.toString())
    }
}
