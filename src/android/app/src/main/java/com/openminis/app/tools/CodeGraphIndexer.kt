package com.openminis.app.tools

import android.util.Log
import com.openminis.app.data.db.CodeEdgeEntity
import com.openminis.app.data.db.CodeIndexDao
import com.openminis.app.data.db.CodeSymbolEntity
import java.io.File

/** Lightweight, no-JNI source indexer. Results are hints; grep_source remains the literal-search fallback. */
object CodeGraphIndexer {
    private const val TAG = "CodeGraphIndexer"
    private const val MAX_FILES = 3_000
    private const val MAX_FILE_BYTES = 1_048_576L
    private val skipDirs = setOf(".git", ".svn", ".hg", "node_modules", "build", "out", "dist", "target", ".gradle", ".idea", "vendor", "__pycache__", ".venv", "venv", "Pods", ".next", ".nuxt", "coverage")
    private val languages = mapOf("kt" to "kotlin", "java" to "java", "py" to "python", "ts" to "typescript", "tsx" to "typescript", "js" to "typescript", "jsx" to "typescript")
    private val ignoredCalls = setOf("if", "else", "when", "for", "while", "return", "try", "catch", "throw", "class", "fun", "new", "require", "check", "print", "println", "super", "this")

    suspend fun rebuild(root: String, dao: CodeIndexDao): Int {
        // [T-walk-cage-single-resolve] root 已经是复检 + canonical 化过的宿主路径
        // （CodeGraphTool.detectRoot 拿的是 PRootKernel.resolveSessionHostPath 的回传值）。
        // 这里**不再**自己 `canonicalFile` 一次：第二次解析正是「复检通过 → 以新目标为根
        // 遍历索引」那扇门（guest 只要在两次之间把分量换成软链）。
        val dir = File(root)
        if (!dir.isDirectory) return 0
        dao.clearSymbols(dir.path)
        dao.clearEdges(dir.path)
        var files = 0
        var total = 0
        walkSourceFiles(dir, SessionTreeGuard.verified(dir)).forEach { file ->
            if (files >= MAX_FILES || !file.isFile || file.length() > MAX_FILE_BYTES) return@forEach
            val lang = languages[file.extension.lowercase()] ?: return@forEach
            val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
            files++
            val (symbols, edges) = extract(file.canonicalPath, text, lang, dir.path)
            if (symbols.isNotEmpty()) dao.insertSymbols(symbols)
            if (edges.isNotEmpty()) dao.insertEdges(edges)
            total += symbols.size
        }
        Log.i(TAG, "indexed $files files, $total symbols in ${dir.path}")
        return total
    }

    /**
     * [T-tools-files-walk-jail] 索引遍历：软链目录剪枝 + 逐项 canonical 归属复检
     * （见 [walkOwnedTree]），外加 `.git` / `build` / `node_modules` … 的跳过规则。
     *
     * 旧实现用的 `FileTreeWalk` 会跟随目录软链（`File.isDirectory` 解引用），工作区里
     * 一条 `ln -s ../../.. leak` 就足以让索引器把别的会话的文件读出来并写进索引库
     * （`code_graph` 的查询结果随后会把内容/符号名回给模型）。
     *
     * [T-tools-files-walk-realpath] 产出的是 **canonical 路径**（不再是词法 child），
     * [rebuild] 的 `file.length()` / `readText()` / `extract(file.canonicalPath, …)` 用的都是
     * 它，所以「复检通过 → 打开」之间没有软链分量可被 guest 改写。
     *
     * [T-walk-cage-single-resolve] [rebuild] 传入的 [guard] 钉住了 `resolveSessionHostPath`
     * 复检时用的那条 canonical 根；默认值只服务没有复检过的根（单测）。
     *
     * 独立成函数是为了能在纯 JVM 单测里钉住这一点（[rebuild] 还需要 Room DAO）。
     */
    internal fun walkSourceFiles(
        dir: File,
        guard: SessionTreeGuard = SessionTreeGuard(dir),
    ): Sequence<File> =
        walkOwnedTree(
            dir,
            guard,
            // 根自身的名字不再需要例外：enter 只在子目录上调用。
            enter = { it.name !in skipDirs && !it.name.startsWith(".") },
        )

    private fun extract(path: String, content: String, lang: String, root: String): Pair<List<CodeSymbolEntity>, List<CodeEdgeEntity>> {
        val symbols = mutableListOf<CodeSymbolEntity>()
        val edges = mutableListOf<CodeEdgeEntity>()
        var current = "<file>"
        val seen = mutableSetOf<String>()
        val funRegex = when (lang) {
            "python" -> Regex("""^\s*def\s+(\w+)\s*\(""")
            "typescript" -> Regex("""(?:function\s+|(?:const|let|var)\s+)(\w+)\s*(?:=\s*(?:async\s*)?\([^)]*\)\s*=>|\()""")
            else -> Regex("""(?:fun\s+)(\w+)\s*\(""")
        }
        val classRegex = when (lang) {
            "python" -> Regex("""^\s*class\s+(\w+)""")
            else -> Regex("""\b(?:class|object|interface|enum\s+class)\s+(\w+)""")
        }
        content.lineSequence().forEachIndexed { index, line ->
            val lineNo = index + 1
            val found = funRegex.find(line)?.groupValues?.getOrNull(1)?.let { it to "function" }
                ?: classRegex.find(line)?.groupValues?.getOrNull(1)?.let { it to "class" }
            if (found != null) {
                current = found.first
                if (seen.add("${found.second}:${found.first}:$lineNo")) symbols += CodeSymbolEntity(
                    root = root, filePath = path, name = found.first, qualifiedName = path.removePrefix(root).trimStart('/').substringBeforeLast('.').replace('/', '.'),
                    kind = found.second, startLine = lineNo, endLine = lineNo, signature = line.trim().take(180)
                )
            }
            val trimmed = line.trim()
            if (!trimmed.startsWith("//") && !trimmed.startsWith("#") && !trimmed.startsWith("*") && !trimmed.startsWith("@")) {
                Regex("""\b([A-Za-z_]\w*)\s*\(""").findAll(line).map { it.groupValues[1] }
                    .filter { it !in ignoredCalls && it != current && it.length > 1 }
                    .distinct().forEach { target -> edges += CodeEdgeEntity(root = root, filePath = path, kind = "calls", fromName = current, toName = target, line = lineNo) }
            }
        }
        return symbols to edges
    }
}
