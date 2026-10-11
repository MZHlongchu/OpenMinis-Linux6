package com.openminis.app.webapp

import android.content.Context
import com.openminis.app.data.db.WebAppShortcutEntity
import com.openminis.app.data.repository.WebAppShortcutRepository
import com.openminis.app.sandbox.PRootKernel
import java.io.File

/**
 * T-pwa-1 (renamed Pwa → WebApp): resolve the stored
 * ([WebAppShortcutEntity.pathScope], [WebAppShortcutEntity.scopeContext],
 * [WebAppShortcutEntity.htmlPath]) triple back to a host File. Returns
 * null if the file no longer exists — caller should surface a "source
 * missing" UI instead of crashing.
 *
 *   - session_attachment → `<filesDir>/sessions/<sessionId>/attachments/<htmlPath>`
 *     (htmlPath relative to the session's attachments dir; absolute paths
 *      under /var/minis/<sub>/ are also accepted via resolveSessionHostPath)
 *   - shared             → resolved via [PRootKernel.resolveHostPath]
 *   - mount              → resolved via [PRootKernel.resolveHostPath]
 *                          (longest-prefix match against bindMounts)
 */
object WebAppPathResolver {

    fun resolve(context: Context, shortcut: WebAppShortcutEntity): File? {
        val file = when (shortcut.pathScope) {
            WebAppShortcutRepository.SCOPE_SESSION_ATTACHMENT -> resolveSession(context, shortcut)
            WebAppShortcutRepository.SCOPE_SHARED -> PRootKernel.resolveHostPath(shortcut.htmlPath)
            WebAppShortcutRepository.SCOPE_MOUNT -> PRootKernel.resolveHostPath(shortcut.htmlPath)
            else -> null
        }
        return file?.takeIf { it.exists() && it.isFile }
    }

    /**
     * T-pwa-3: reverse-resolve a host file path to a `(pathScope,
     * scopeContext, linuxPath)` triple suitable for
     * [WebAppShortcutRepository.create]. Walks the
     * [PRootKernel.bindMounts] map looking for an entry whose host
     * directory is a prefix of [hostFile]; returns null if none matches
     * (caller should hide the "Add to Home Screen" menu item).
     *
     * Mapping rules:
     *  - `/var/minis/shared` bind  → `pathScope = "shared"`,  `scopeContext = null`
     *  - `/var/minis/mounts/<n>`   → `pathScope = "mount"`,   `scopeContext = "<n>"`
     *  - everything else (incl. memory/skills, per-session subdirs, rootfs) → null
     */
    fun inferScope(hostFile: File): Triple<String, String?, String>? {
        val hostAbs = hostFile.absolutePath
        // Longest host-prefix wins, mirroring resolveHostPath's longest-key match.
        // [T-b4-#2] bindMounts 现在是 ConcurrentHashMap（IO 线程在建壳/改挂载时会写，
        // 这里跑在主线程），遍历顺序不再确定。多个 key 共享同一宿主根（`/sdcard`、
        // `/storage/emulated/0`、`/var/minis/mounts/sdcard` 都指向共享存储），而首个
        // 命中就决定结果，所以平局必须确定化：linux 路径短的优先——这正是旧
        // LinkedHashMap 插入序产生的顺序（`/sdcard` 最先插入）。
        val sorted = com.openminis.app.sandbox.PRootKernel
            .bindMounts.toList()
            .sortedWith(
                compareByDescending<Pair<String, String>> { it.second.length }
                    .thenBy { it.first.length },
            )
        for ((linuxPrefix, hostBase) in sorted) {
            val baseNorm = hostBase.trimEnd('/')
            if (hostAbs == baseNorm || hostAbs.startsWith("$baseNorm/")) {
                val tail = hostAbs.removePrefix(baseNorm).removePrefix("/")
                val linuxPath = if (tail.isEmpty()) linuxPrefix else "$linuxPrefix/$tail"
                return when {
                    linuxPrefix == "/var/minis/shared" ->
                        Triple(WebAppShortcutRepository.SCOPE_SHARED, null, linuxPath)
                    linuxPrefix.startsWith("/var/minis/mounts/") -> {
                        val mountName = linuxPrefix.removePrefix("/var/minis/mounts/")
                            .substringBefore('/')
                        Triple(WebAppShortcutRepository.SCOPE_MOUNT, mountName, linuxPath)
                    }
                    else -> null  // memory/skills/etc — no WebApp support
                }
            }
        }
        return null
    }

    private fun resolveSession(context: Context, shortcut: WebAppShortcutEntity): File? {
        val sessionId = shortcut.scopeContext ?: return null
        // Absolute /var/minis/<perSession>/... — go through PRootKernel which
        // knows how to map per-session subdirs to <filesDir>/minis-sessions/<id>/<sub>.
        if (shortcut.htmlPath.startsWith("/var/minis/")) {
            return PRootKernel.resolveSessionHostPath(sessionId, shortcut.htmlPath, context)
        }
        // Relative path — under the session's attachments dir.
        val attachmentsDir = File(context.filesDir, "sessions/$sessionId/attachments")
        return File(attachmentsDir, shortcut.htmlPath)
    }
}
