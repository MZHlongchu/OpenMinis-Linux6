package com.openminis.app.tools

import android.util.Log
import java.io.File

/**
 * Lightweight source-code indexer that builds a line-based symbol map without
 * a native AST library. Uses regex heuristics per language to extract
 * definitions and call-site references — fast, no JNI, best-effort.
 *
 * Coverage vs full tree-sitter AST:
 *  - defines: good for top-level functions/classes/methods
 *  - callers/callees: partial (line-based call-expression heuristics)
 *
 * The agent should treat results as "helpful hints", not ground truth. Use
 * grep_source for literal string matches; use this when asking "where is
 * X defined" or "what calls Y".
 */
object CodeGraphIndexer {

    private const val TAG = "CodeGraphIndexer"

    private const val MAX_FILES = 3_000
    private const val MAX_FILE_BYTES = 1_048_576

    private val SKIP_DIRS = setOf(
        ".git", ".svn", ".hg", "node_modules", "build", "out", "dist", "target",
        ".gradle", ".idea", "vendor", "__pycache__", ".venv", "venv",
        "Pods", ".next", ".nuxt", "coverage", ".cache", ".tox",
    )

    /** File extension → language label. */
    private val LANG_BY_EXT = mapOf(
        "kt" to "kotlin", "java" to "java", "groovy" to "java",
        "py" to "python",
        "ts" to "typescript", "tsx" to "typescript",
        "js" to "typescript", "jsx" to "typescript",
    )

    // Keywords / built-ins to ignore when scanning for call-site references.
    private val SKIP_REF = setOf(
        "if", "else", "when", "for", "while", "return", "try", "catch",
        "finally", "throw", "new", "class", "extends", "implements",
        "val", "var", "fun", "def", "const", "let", "static", "public",
        "private", "protected", "synchronized", "override", "suspend",
        "require", "check", "notNull", "println", "print", "printf",
        "true", "false", "null", "this", "super", "it", "unit",
    )

    suspend fun index(root: String, onProgress: ((scanned: Int, indexed: Int) -> Unit)? = null): Int {
        val dir = File(root)
        if (!dir.isDirectory) {
            Log.w(TAG, "not a directory: $root")
            return 0
        }
        val indexed = intArrayOf(0)
        val scanned = intArrayOf(0)
        val all = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()

        fun walk(d: File) {
            if (scanned[0] >= MAX_FILES) return
            val canonical = d.canonicalPath
            if (!seen.add(canonical)) return
            val children = d.listFiles() ?: return
            for (f in children) {
                if (scanned[0] >= MAX_FILES) break
                if (f.isDirectory) {
                    if (f.name.startsWith(".") || f.name in SKIP_DIRS) continue
                    walk(f)
                } else {
                    val ext = f.name.substringAfterLast('.', "").lowercase()
                    if (LANG_BY_EXT[ext] == null) continue
                    if (f.length() > MAX_FILE_BYTES) continue
                    val content = runCatching { f.readText() }.getOrNull() ?: continue
                    all.add(f.absolutePath to content)
                    scanned[0]++
                }
            }
        }
        walk(dir)

        if (all.isEmpty()) return 0
        for ((path, content) in all) {
            val lang = LANG_BY_EXT[path.substringAfterLast('.')] ?: continue
            val s = extract(path, content, lang, root)
            if (s > 0) onProgress?.invoke(scanned[0], indexed[0])
            indexed[0] += s
        }
        Log.i(TAG, "indexed ${all.size} files, ${indexed[0]} symbols in $root")
        return indexed[0]
    }

    /**
     * Extract symbols and references from a single file.
     * @return number of definitions found.
     */
    fun extract(path: String, content: String, lang: String, root: String): Int {
        val lines = content.split('\n')
        val defs = mutableListOf<SymbolDef>()
        val refs = mutableListOf<Ref>()
        val seen = mutableSetOf<String>() // dedup by name within one file

        fun addDef(name: String, kind: String, ln: Int, raw: String) {
            if (name.isBlank() || !seen.add(name)) return
            val sig = raw.trim().substringBefore('\n').take(120)
            defs.add(SymbolDef(
                filePath = path, name = name,
                qualifiedName = qualName(path, root), kind = kind,
                startLine = ln, endLine = ln, signature = sig,
            ))
        }

        when (lang) {
            "kotlin", "java" -> {
                val fn = Regex("""\s*(?:@[\w.]+\s+)*?(?:override\s+|suspend\s+|inline\s+|private\s+|public\s+|protected\s+|internal\s+|val\s+|var\s+|const\s+)?fun\s+(\w+)\s*\(""")
                val cls = Regex("""\s*(?:@[\w.]+\s+)*?(?:abstract\s+|open\s+|sealed\s+|internal\s+|private\s+|protected\s+|class\s+|object\s+|interface\s+)(\w+)""")
                for ((i, line) in lines.withIndex()) {
                    val ln = i + 1
                    fn.find(line)?.let { addDef(it.groupValues[1], "function", ln, line) }
                    cls.find(line)?.let { addDef(it.groupValues[1], "class", ln, line) }
                    scanRefs(line, ln, refs)
                }
            }
            "python" -> {
                val fn = Regex("""^\s*def\s+(\w+)\s*\(""")
                val cls = Regex("""^\s*class\s+(\w+)""")
                for ((i, line) in lines.withIndex()) {
                    val ln = i + 1
                    fn.find(line)?.let { addDef(it.groupValues[1], "function", ln, line) }
                    cls.find(line)?.let { addDef(it.groupValues[1], "class", ln, line) }
                    scanRefs(line, ln, refs)
                }
            }
            "typescript" -> {
                val fn1 = Regex("""\b(?:async\s+)?function\s+(\w+)\s*\(""")
                val fn2 = Regex("""(\w+)\s*[:=]\s*(?:async\s+)?\(""")
                val cls = Regex("""\b(?:export\s+)?class\s+(\w+)""")
                for ((i, line) in lines.withIndex()) {
                    val ln = i + 1
                    fn1.find(line)?.let { addDef(it.groupValues[1], "function", ln, line) }
                    fn2.find(line)?.let { addDef(it.groupValues[1], "function", ln, line) }
                    cls.find(line)?.let { addDef(it.groupValues[1], "class", ln, line) }
                    scanRefs(line, ln, refs)
                }
            }
        }
        return defs.size
    }

    private fun scanRefs(line: String, ln: Int, out: MutableList<Ref>) {
        // Skip comment / string / annotation lines — too many false positives
        val trimmed = line.trim()
        if (trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("*")) return
        if (trimmed.startsWith("@")) return

        Regex("""\b([A-Za-z_]\w*)\s*\(""")
            .findAll(line)
            .map { it.groupValues[1] }
            .filter { it !in SKIP_REF && it.length > 1 }
            .forEach { out.add(Ref(line = ln, name = it)) }
    }

    private fun qualName(path: String, root: String): String {
        val rel = path.removePrefix(root).replace(File.separatorChar, '/')
        val pkg = rel.substringBeforeLast('/').removePrefix("/")
            .replace('/', '.').trim('.')
        return if (pkg.isEmpty()) path.substringAfterLast('/').substringBeforeLast('.')
        else pkg
    }
}

private data class SymbolDef(
    val filePath: String,
    val name: String,
    val qualifiedName: String,
    val kind: String,
    val startLine: Int,
    val endLine: Int,
    val signature: String,
)

private data class Ref(
    val line: Int,
    val name: String,
)
