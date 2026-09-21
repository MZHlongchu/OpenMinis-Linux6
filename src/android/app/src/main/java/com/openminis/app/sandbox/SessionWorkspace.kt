package com.openminis.app.sandbox

import java.io.File

/**
 * Host layout for one chat = one workspace.
 *
 * Per session (`filesDir/minis-sessions/<id>/`):
 *   attachments, offloads, workspace, browser, memory
 *
 * Shared across sessions (`filesDir/minis-global/`):
 *   skills, shared, mcp-servers  — tools live here so every chat can call them.
 *
 * SOUL.md / LEARNED.md stay under minis-global/memory (not bind-mounted as
 * `/var/minis/memory`). `/var/minis/memory` is this chat's daily logs and is
 * deleted with the session.
 */
object SessionWorkspace {
    const val SESSIONS_DIR = "minis-sessions"
    const val GLOBAL_DIR = "minis-global"

    val SESSION_SUBDIRS: List<String> = listOf(
        "attachments",
        "offloads",
        "workspace",
        "browser",
        "memory",
    )

    val GLOBAL_BIND_SUBDIRS: List<String> = listOf(
        "skills",
        "shared",
        "mcp-servers",
    )

    fun base(filesDir: File, sessionId: String): File =
        File(filesDir, "$SESSIONS_DIR/$sessionId")

    fun memoryDir(filesDir: File, sessionId: String): File =
        File(base(filesDir, sessionId), "memory")

    fun ensureDirs(filesDir: File, sessionId: String) {
        val root = base(filesDir, sessionId)
        for (subdir in SESSION_SUBDIRS) {
            File(root, subdir).mkdirs()
        }
    }

    /** Delete the whole session tree, including memory. Idempotent. */
    fun deleteEntire(filesDir: File, sessionId: String): Boolean {
        if (sessionId.isBlank() || sessionId == "." || sessionId == "..") return false
        if (sessionId.contains('/') || sessionId.contains('\\')) return false
        val dir = base(filesDir, sessionId)
        if (!dir.exists()) return true
        return dir.deleteRecursively()
    }
}
