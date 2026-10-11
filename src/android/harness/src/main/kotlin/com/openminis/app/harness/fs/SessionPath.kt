package com.openminis.app.harness.fs

import java.io.File

/**
 * 会话 id → 路径段（文件名/目录名）的单射转义。
 *
 * 会话 id 不是可信输入：lane 会话 id 形如 `lane:<owner>:<name>`，deep link 里
 * 的 id 更任意。直接拿它当路径段有两个后果——两个不同 id 落到同一个文件
 * （原始 id `_2f` 与 `/` 的转义结果撞车，共享一份数据后互相误删），或 id 逃出
 * 持久化根目录（`deleteRecursively()` 一次就是整棵宿主私有树）。
 *
 * 规则（harness 的三个文件持久化统一口径）：
 *   安全字符（字母数字与 `.` `-`）原样；`_` → `__`；其余字节 → `_` + 两位 hex。
 * 转义 `_` 自身是单射性的关键——否则原始 id `_2f` 会与 `/` 的转义结果 `_2f`
 * 撞同一个名字。逐 UTF-8 字节处理，`/`、`\`、NUL 都不可能出现在结果里。
 */
object SessionPath {

    /** 单射转义；结果不含任何路径分隔符。空 id 得到空串（由 [resolve] 拒绝）。 */
    fun segment(sessionId: String): String = buildString(sessionId.length + 8) {
        for (b in sessionId.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            when {
                c == '_' -> append("__")
                c.isLetterOrDigit() || c == '.' || c == '-' -> append(c)
                else -> append('_').append("%02x".format(b))
            }
        }
    }

    /**
     * `<root>/<转义后的 id>`。
     *
     * 返回 null 表示该 id 不可用（空、`.`、`..`，或转义后仍越出 [root]）——
     * 调用方必须拒绝这次操作而不是退化成"整个 root"：`File(root, "")` 与
     * `File(root, "..")` 分别指向 root 自身与 root 的父目录，落到
     * `deleteRecursively()` 上就是删掉整棵私有树。
     */
    fun resolve(root: File, sessionId: String): File? {
        val safe = segment(sessionId)
        if (safe.isEmpty() || safe == "." || safe == "..") return null
        val target = File(root, safe)
        return if (staysInside(root, target)) target else null
    }

    /**
     * true = [candidate] 严格位于 [root] 之内；等于 [root] 自身也算越界
     * （`File(root, "")` 就是 root，落到 `deleteRecursively()` 上会清空整个根）。
     * 词法折叠 `.`/`..`，不解析符号链接：这里回答的是"路径写着要删谁"。
     */
    fun staysInside(root: File, candidate: File): Boolean {
        val base = normalize(root.absolutePath)
        if (base.isEmpty() || base == File.separator) return false
        val path = normalize(candidate.absolutePath)
        return path.length > base.length && path.startsWith(base + File.separator)
    }

    /** 词法折叠 `.` / `..`（不碰文件系统）。 */
    private fun normalize(path: String): String {
        val absolute = path.startsWith('/') || path.startsWith('\\')
        val parts = mutableListOf<String>()
        for (part in path.split('/', '\\')) {
            when (part) {
                "", "." -> Unit
                ".." -> {
                    if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                }
                else -> parts.add(part)
            }
        }
        val joined = parts.joinToString(File.separator)
        return if (absolute) File.separator + joined else joined
    }
}
