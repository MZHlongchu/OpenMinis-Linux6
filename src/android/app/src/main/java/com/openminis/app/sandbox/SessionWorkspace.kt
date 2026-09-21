package com.openminis.app.sandbox

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Host layout for chat workspaces.
 *
 * Per session (`filesDir/minis-sessions/<id>/`):
 *   memory  — daily logs, deleted with the session
 *
 * Per project folder (`filesDir/minis-workspaces/<folderId>/`), shared by every
 * session filed in that folder:
 *   attachments, offloads, workspace, browser
 *
 * Ungrouped sessions keep the 1.36.13 layout: those four dirs live under
 * `minis-sessions/<id>/` so isolation is preserved until they are filed.
 *
 * Shared across sessions (`filesDir/minis-global/`):
 *   skills, shared, mcp-servers
 *
 * SOUL.md / LEARNED.md stay under minis-global/memory (not bind-mounted as
 * `/var/minis/memory`). `/var/minis/memory` is this chat's daily logs.
 */
object SessionWorkspace {
    const val SESSIONS_DIR = "minis-sessions"
    const val WORKSPACES_DIR = "minis-workspaces"
    const val GLOBAL_DIR = "minis-global"

    val SHARED_SUBDIRS: List<String> = listOf(
        "attachments",
        "offloads",
        "workspace",
        "browser",
    )

    val SESSION_SUBDIRS: List<String> = SHARED_SUBDIRS + "memory"

    val GLOBAL_BIND_SUBDIRS: List<String> = listOf(
        "skills",
        "shared",
        "mcp-servers",
    )

    private val folderIds = ConcurrentHashMap<String, String>()

    fun rememberFolder(sessionId: String, folderId: String?) {
        if (!isSafeId(sessionId)) return
        if (folderId.isNullOrBlank()) folderIds.remove(sessionId)
        else if (isSafeId(folderId)) folderIds[sessionId] = folderId
    }

    fun folderIdFor(sessionId: String): String? {
        parseDraftFolderId(sessionId)?.let { return it }
        val owner = ownerSessionId(sessionId)
        return folderIds[owner]
    }

    fun parseDraftFolderId(sessionId: String): String? {
        val i = sessionId.indexOf("__fld__")
        if (i < 0) return null
        val rest = sessionId.substring(i + 7)
        val end = rest.indexOf("__grp__")
        val id = if (end >= 0) rest.substring(0, end) else rest
        return id.takeIf { isSafeId(it) }
    }

    fun isSafeId(id: String): Boolean {
        if (id.isBlank() || id == "." || id == "..") return false
        if (id.contains('/') || id.contains('\\')) return false
        return true
    }

    fun base(filesDir: File, sessionId: String): File =
        File(filesDir, "$SESSIONS_DIR/$sessionId")

    fun projectBase(filesDir: File, folderId: String): File =
        File(filesDir, "$WORKSPACES_DIR/$folderId")

    fun memoryDir(filesDir: File, sessionId: String): File =
        File(base(filesDir, sessionId), "memory")

    /**
     * Host directory mounted at `/var/minis/[subdir]`. Shared project dirs when
     * the session is filed in a folder; otherwise per-session.
     */
    fun hostDir(filesDir: File, sessionId: String, subdir: String): File {
        val folderId = folderIdFor(sessionId)
        return if (folderId != null && subdir in SHARED_SUBDIRS) {
            File(projectBase(filesDir, folderId), subdir)
        } else {
            File(base(filesDir, sessionId), subdir)
        }
    }

    fun ensureProjectDirs(filesDir: File, folderId: String) {
        if (!isSafeId(folderId)) return
        val root = projectBase(filesDir, folderId)
        for (subdir in SHARED_SUBDIRS) {
            File(root, subdir).mkdirs()
        }
    }

    fun ensureDirs(filesDir: File, sessionId: String) {
        val root = base(filesDir, sessionId)
        File(root, "memory").mkdirs()
        val folderId = folderIdFor(sessionId)
        if (folderId != null) {
            ensureProjectDirs(filesDir, folderId)
        } else {
            for (subdir in SHARED_SUBDIRS) {
                File(root, subdir).mkdirs()
            }
        }
    }

    /** Delete the whole session tree, including memory. Does not touch a shared project. */
    fun deleteEntire(filesDir: File, sessionId: String): Boolean {
        if (!isSafeId(sessionId)) return false
        folderIds.remove(sessionId)
        val dir = base(filesDir, sessionId)
        if (!dir.exists()) return true
        return dir.deleteRecursively()
    }

    fun deleteProject(filesDir: File, folderId: String): Boolean {
        if (!isSafeId(folderId)) return false
        val dir = projectBase(filesDir, folderId)
        if (!dir.exists()) return true
        return dir.deleteRecursively()
    }

    private fun ownerSessionId(sessionId: String): String {
        val prefix = "subagent:"
        if (!sessionId.startsWith(prefix)) return sessionId
        val rest = sessionId.substring(prefix.length)
        val cut = rest.lastIndexOf(':')
        return if (cut > 0) rest.substring(0, cut) else sessionId
    }
}
