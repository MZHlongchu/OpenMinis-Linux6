package com.openminis.app.sandbox

import android.content.Context
import com.openminis.app.R
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryDiaryMigrator
import java.io.File

/**
 * One-shot upgrade from the pre-1.36.13 global workspace:
 *
 *  - copy `minis-global/memory/YYYY-MM-DD.md` into each session's memory dir
 *  - file ungrouped sessions that have no isolated workspace files into the
 *    default project folder so they share one workspace
 *  - seed that project from leftover `minis-global/{workspace,attachments,...}`
 *
 * Sessions that already have per-session workspace files (1.36.13 isolation)
 * stay unfiled so their files keep being mounted.
 */
object LegacyWorkspaceMigrator {
    private const val PREFS = "workspace_layout"
    private const val KEY_DONE = "legacy_v1_done"

    fun sessionHasIsolatedFiles(filesDir: File, sessionId: String): Boolean {
        val root = SessionWorkspace.base(filesDir, sessionId)
        return SessionWorkspace.SHARED_SUBDIRS.any { sub ->
            File(root, sub).walkTopDown().any { it.isFile }
        }
    }

    fun seedProjectFromGlobal(filesDir: File, folderId: String) {
        SessionWorkspace.ensureProjectDirs(filesDir, folderId)
        for (sub in SessionWorkspace.SHARED_SUBDIRS) {
            val src = File(filesDir, "${SessionWorkspace.GLOBAL_DIR}/$sub")
            val dst = File(SessionWorkspace.projectBase(filesDir, folderId), sub)
            if (!src.isDirectory) continue
            val destEmpty = dst.listFiles().isNullOrEmpty()
            if (!destEmpty) continue
            src.copyRecursively(dst, overwrite = false)
        }
    }

    suspend fun run(context: Context, repo: ChatRepository) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return
        val filesDir = context.filesDir
        val globalMemory = File(filesDir, "${SessionWorkspace.GLOBAL_DIR}/memory")
        val sessions = repo.listSessions()
        for (s in sessions) {
            MemoryDiaryMigrator.copyInto(
                SessionWorkspace.memoryDir(filesDir, s.id),
                globalMemory,
            )
        }
        val defaultName = context.getString(R.string.group_default_workspace)
        val folder = repo.ensureDefaultWorkspace(defaultName)
        for (s in sessions) {
            if (s.folderId != null) continue
            if (sessionHasIsolatedFiles(filesDir, s.id)) continue
            repo.setFolderIfUnfiled(folder.id, s.id)
        }
        seedProjectFromGlobal(filesDir, folder.id)
        prefs.edit().putBoolean(KEY_DONE, true).apply()
    }
}
