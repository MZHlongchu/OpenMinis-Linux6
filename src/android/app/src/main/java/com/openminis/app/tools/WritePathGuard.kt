package com.openminis.app.tools

/**
 * Restricts [FileWriteTool] / [FileEditTool] to prefixes assigned via
 * `run_subagent.write_paths` (拾忆 spawn_agent write_paths). Empty or absent
 * prefixes mean unrestricted. Thread-local so parallel sub-agents do not share
 * each other's allow-list; callers must [swap] on the same thread that executes
 * the write.
 */
object WritePathGuard {

    private val allowed = ThreadLocal<List<String>?>()

    fun swap(prefixes: List<String>?): List<String>? {
        val previous = allowed.get()
        if (prefixes.isNullOrEmpty()) {
            allowed.remove()
        } else {
            allowed.set(prefixes.map(::normalize).filter { it.startsWith("/") })
        }
        return previous
    }

    fun restore(previous: List<String>?) {
        if (previous.isNullOrEmpty()) allowed.remove() else allowed.set(previous)
    }

    fun denyReason(linuxPath: String): String? {
        val prefixes = allowed.get() ?: return null
        if (prefixes.isEmpty()) return null
        val n = normalize(linuxPath)
        if (n.isEmpty()) return "Error: path is empty and write_paths is in effect."
        val ok = prefixes.any { n == it || n.startsWith("$it/") }
        if (ok) return null
        return "Error: path $linuxPath is outside assigned write_paths (${prefixes.joinToString()})."
    }

    fun parse(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(',', '\n', ';')
            .map { normalize(it) }
            .filter { it.startsWith("/") }
            .distinct()
    }

    fun normalize(path: String): String {
        var p = path.trim().replace('\\', '/')
        while (p.contains("//")) p = p.replace("//", "/")
        if (p.length > 1 && p.endsWith("/")) p = p.dropLast(1)
        return p
    }
}
