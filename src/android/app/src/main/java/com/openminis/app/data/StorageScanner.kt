package com.openminis.app.data

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Host-side storage accounting. Walking a full Ubuntu rootfs on the UI
 * thread (or blocking first paint) can take minutes; this helper skips
 * virtual trees, prefers native `du`, and caches the last rootfs size.
 */
object StorageScanner {
    const val PREFS = "minis_storage_scan"
    const val KEY_ROOTFS_BYTES = "ubuntu_rootfs_bytes"

    val SKIP_DIR_NAMES: Set<String> = setOf(
        "proc", "sys", "dev", "run", "tmp",
        "node_modules", ".git", "__pycache__", ".gradle",
    )

    fun cachedRootfsBytes(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_ROOTFS_BYTES, 0L)

    fun saveRootfsBytes(context: Context, bytes: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_ROOTFS_BYTES, bytes)
            .apply()
    }

    /**
     * [T-android-storage-symlink-inflation] 这个 walk 曾经是本文件自己的一份
     * `walkTopDown { total += it.length() }`：`walkTopDown` 会跟进目录符号链接、
     * `isFile`/`length()` 会解析文件符号链接，于是 Alpine/Ubuntu rootfs 里成百上千
     * 个 busybox applet 链接与 `lib -> usr/lib` 之类的目录链接被重复计入，存储页
     * 报出的数字比 Android 自己的设置页大一个量级。skip/max 两个开关（虚拟目录、
     * 文件数上限）现在留在 [SessionStorage.directorySize] 的重载里，与唯一的实现
     * 放一起，这里只做转发——手写第二份正是这个 bug 反复出现的路径。
     */
    fun directorySize(
        dir: File,
        skipNames: Set<String> = SKIP_DIR_NAMES,
        maxFiles: Int = 80_000,
    ): Long = com.openminis.app.data.session.SessionStorage.directorySize(dir, skipNames, maxFiles)

    fun directorySizePreferDu(dir: File, timeoutSec: Long = 60L): Long {
        if (!dir.exists()) return 0L
        val path = dir.absolutePath
        val commands = listOf(
            listOf("du", "-sk", path),
            listOf("/system/bin/du", "-sk", path),
            listOf("/system/bin/toybox", "du", "-sk", path),
        )
        for (cmd in commands) {
            val parsed = runDuKilobytes(cmd, timeoutSec) ?: continue
            return parsed * 1024L
        }
        return directorySize(dir)
    }

    private fun runDuKilobytes(cmd: List<String>, timeoutSec: Long): Long? {
        return try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val line = p.inputStream.bufferedReader().use { it.readLine() }
            val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                p.destroyForcibly()
                return null
            }
            if (p.exitValue() != 0 || line.isNullOrBlank()) return null
            line.trim().substringBefore('\t').substringBefore(' ').toLongOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * [T-android-storage-symlink-inflation] 同上：按会话归集的媒体体量也只留一份
     * 实现（[SessionStorage.mediaSizesBySession]），这里转发。
     */
    fun mediaSizesBySession(mediaDir: File, sessionIds: Set<String>): Map<String, Long> =
        com.openminis.app.data.session.SessionStorage.mediaSizesBySession(mediaDir, sessionIds)
}
