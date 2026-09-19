package com.openminis.app.tools

import android.util.Log
import com.openminis.app.data.db.CodeIndexDao
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

/**
 * `code_graph` — locate code symbols (definitions / callers / callees / file
 * listing) without reading whole files into context.
 *
 * This is the fallback implementation that works without a native AST library.
 * It builds a lightweight line-based index via [CodeGraphIndexer] the first
 * time a query is issued for a workspace root.
 *
 * ## Division of labour with grep_source
 *
 * | Question | Use |
 * | "Where is X defined" | code_graph |
 * | "Who calls X" | code_graph |
 * | "What does file X contain" | code_graph |
 * | "Find literal string Y" | grep_source |
 */
object CodeGraphTool {

    private const val TAG = "CodeGraphTool"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = DESCRIPTION,
        parameters = mapOf(
            "action" to AgentToolParam(
                type = "string",
                description = "define | callers | callees | file | status",
                enumValues = listOf("define", "callers", "callees", "file", "status"),
            ),
            "name" to AgentToolParam(
                type = "string",
                description = "Symbol name. Required for define/callers/callees.",
            ),
            "path" to AgentToolParam(
                type = "string",
                description = "File path. Required for file action.",
            ),
        ),
        required = listOf("action"),
        propertyOrdering = listOf("action", "name", "path"),
    )

    suspend fun execute(
        dao: CodeIndexDao,
        db: AppDatabase,
        params: Map<String, String>,
        resolvePath: (String) -> String?,
    ): String {
        val root = detectRoot(resolvePath) ?: return "无法确定工作区根目录"
        ensureIndex(dao, db, root)

        return when (params["action"]) {
            "status" -> status(dao, root)
            "define" -> define(dao, root, params["name"])
            "callers" -> callers(dao, root, params["name"])
            "callees" -> callees(dao, root, params["name"])
            "file" -> file(dao, root, params["path"])
            else -> "action 必须是 define / callers / callees / file / status 之一"
        }
    }

    // ---- actions ----

    private fun status(dao: CodeIndexDao, root: String): String {
        val files = dao.fileCount(root)
        val syms = dao.symbolCount(root)
        return if (syms == 0)
            "这个工作区还没有代码索引($root)。首次查询时会自动建索引（可能需要几秒）。"
        else
            "已索引 $files 个文件, $syms 个符号。工作区: $root"
    }

    private fun define(dao: CodeIndexDao, root: String, name: String?): String {
        val n = name?.trim().orEmpty()
        if (n.isBlank()) return "action=define 需要 name"
        val hits = dao.findByName(root, n, limit = 30)
        if (hits.isEmpty()) {
            val total = dao.symbolCount(root)
            return if (total == 0)
                "这个工作区没有索引, 第一次查询时自动建。"
            else "索引里没有「$n」。可能拼写不同、在未索引语言里, 或本来就不存在。"
        }
        return buildString {
            appendLine("找到 ${hits.size} 处定义:")
            for (s in hits) {
                append("- ${s.kind} ${s.name}")
                if (s.qualifiedName.isNotBlank()) append(" (${s.qualifiedName})")
                appendLine()
                append("  ${s.filePath}:${s.startLine}")
                if (s.endLine > s.startLine) append("-${s.endLine}")
                if (s.signature.isNotBlank()) { appendLine(); append("  ${s.signature}") }
                appendLine()
            }
        }
    }

    private fun callers(dao: CodeIndexDao, root: String, name: String?): String {
        val n = name?.trim().orEmpty()
        if (n.isBlank()) return "action=callers 需要 name"
        val hits = dao.callersOf(root, n, limit = 50)
        if (hits.isEmpty()) return "索引里没有引用「$n」的地方。"
        return buildString {
            appendLine("${hits.size} 处引用了「$n」:")
            for (e in hits) {
                appendLine("- ${e.kind}  ${e.filePath}:${e.line}")
            }
        }
    }

    private fun callees(dao: CodeIndexDao, root: String, name: String?): String {
        val n = name?.trim().orEmpty()
        if (n.isBlank()) return "action=callees 需要 name"
        val hits = dao.calleesOf(root, n, limit = 50)
        if (hits.isEmpty()) return "索引里没有「$n」引用别的符号的记录。"
        return buildString {
            appendLine("「$n」引用了:")
            for (e in hits) {
                appendLine("- ${e.kind} → ${e.toName}  (:${e.line})")
            }
        }
    }

    private fun file(dao: CodeIndexDao, root: String, path: String?, resolvePath: (String) -> String?): String {
        val p = path?.trim().orEmpty()
        if (p.isBlank()) return "action=file 需要 path"
        val resolved = resolvePath(p) ?: p
        val syms = dao.symbolsOf(resolved)
        if (syms.isEmpty()) return "这个文件没有索引记录: $p"
        return buildString {
            appendLine("$p 里的符号 (${syms.size} 个):")
            for (s in syms) {
                append("- ${s.startLine}: ${s.kind} ${s.name}")
                if (s.signature.isNotBlank()) append("  ${s.signature}")
                appendLine()
            }
        }
    }

    // ---- constants ----

    private const val NAME = "code_graph"

    private val DESCRIPTION = """
        查代码结构：符号定义在哪、谁调用了它、它依赖谁、某个文件里有什么。
        比 grep 准（基于语法树，不命中注释和字符串里的同名词），不用把文件读进上下文。
        回答「这个函数在哪」「改它会影响什么」「这个文件都有什么」时优先用它；
        找字面字符串或配置值仍然用 grep_source。首次查询时自动建索引。
    """.trimIndent()
}
