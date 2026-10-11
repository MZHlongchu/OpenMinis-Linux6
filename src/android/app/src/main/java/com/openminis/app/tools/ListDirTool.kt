package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject
import java.io.File

/**
 * Workspace directory listing. Paths go through [PRootKernel], not host File.
 *
 * Adapted from XINCODE-Public ListDirTool (GPL-3.0-or-later).
 */
object ListDirTool {
    const val NAME = "list_dir"
    private const val MAX_ENTRIES = 400

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "List files and directories at a Linux path. Prefer this over shell ls — no PRoot round-trip.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "path" to AgentToolParam("string", "Absolute Linux path (default /var/minis/workspace)."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "path"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val toolTitle = try { JSONObject(argsJson).optString("tool_title", NAME) } catch (_: Exception) { NAME }
        return try {
            val path = JSONObject(argsJson).optString("path", "/var/minis/workspace").ifBlank { "/var/minis/workspace" }
            val dir = PRootKernel.resolveSessionHostPath(sessionId, path, context)
                ?: return ToolExecutionResult("Error: Cannot resolve path: $path${guestNamespaceHint(path)}", false, toolTitle = toolTitle)
            if (!dir.exists()) return ToolExecutionResult("Error: not found: $path${guestNamespaceHint(path)}", false, toolTitle = toolTitle)
            if (!dir.isDirectory) return ToolExecutionResult("Error: not a directory: $path", false, toolTitle = toolTitle)
            // [T-walk-cage-single-resolve] dir 是 resolveSessionHostPath 复检时用的那条
            // canonical 路径，直接钉成守卫基准（不再解析第二次）。
            val kids = ownedChildren(dir, SessionTreeGuard.verified(dir))
            val shown = kids.take(MAX_ENTRIES)
            val body = buildString {
                append("$path (${kids.size} entries)\n")
                for (f in shown) {
                    append(if (f.isDirectory) "d " else "f ")
                    append(f.name)
                    if (!f.isDirectory) append(" ${f.length()}")
                    append('\n')
                }
                if (kids.size > shown.size) append("… truncated\n")
            }
            ToolExecutionResult(body, true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error listing dir: ${e.message}", false, toolTitle = toolTitle)
        }
    }

    /**
     * [T-tools-files-walk-jail] 一级列举也要逐项复检归属。
     *
     * [dir] 自身的归属由 `PRootKernel.resolveSessionHostPath` → `acceptsResolved` 判定过，
     * 但它下面的**每一项**没有：`File.listFiles()` 只列名字，而
     * `f.isDirectory` / `f.length()` 都会解引用软链。guest 在工作区里
     * `ln -s ../../minis-sessions/sess-b/memory secret.md`（proot 的 `--link2symlink`
     * 只翻译 link(2)，不改写 symlink 目标，所以这就是宿主上的真实软链）之后，一条这样的
     * 链接就能把根外的**名字、类型和大小**读出来。
     *
     * 修法与递归工具完全同源：直接复用 [walkOwnedTree]（`enter` 恒 false ⇒ 只产出直接
     * 子项、不下降），归属复检、软链剪枝、canonical 化失败即丢弃全由它一手包办；遍历根用
     * [SessionTreeGuard.canonicalRoot]，保证「守卫的根」与「列目录的目标」是同一次解析的
     * 结果。产出的每一项都是 canonical 路径，后续 `isDirectory` / `length()` 因此不再有
     * 软链可穿。
     *
     * [T-walk-cage-single-resolve] 默认守卫会自己解析 [dir]；`execute` 传进来的是
     * `resolveSessionHostPath` 复检过的 canonical 路径，因此它显式传
     * [SessionTreeGuard.verified]（基准钉死，不再解析第二次）。剩下的窗口只剩「解析之后
     * 分量被 rename + link 顶替」，见 [walkOwnedTree] 的 KDoc。
     *
     * [T-walk-root-self] 自指软链（`ln -s . self`）canonical 之后就是根自身：按 canonical
     * 去重把根本身排除在外，否则它会被列成一个「名字等于根本身 basename」的子目录，看起来
     * 像是工作区里有自己的工作区。`walkOwnedTree` 已经丢掉深度 0 的节点，这里再按 canonical
     * 路径挡一次，保证 list_dir 自己也不会把它列出来。
     */
    internal fun ownedChildren(
        dir: File,
        guard: SessionTreeGuard = SessionTreeGuard(dir),
    ): List<File> {
        val out = ArrayList<File>()
        // 预置根本身：任何 canonical 之后等于根的条目（自指软链）都不是「子项」。
        val seen = hashSetOf(guard.canonicalRoot.path)
        for (real in walkOwnedTree(guard.canonicalRoot, guard, enter = { false })) {
            if (seen.add(real.path)) out += real
        }
        return out.sortedBy { it.name.lowercase() }
    }
}
