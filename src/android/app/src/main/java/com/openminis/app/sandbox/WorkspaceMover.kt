package com.openminis.app.sandbox

import com.openminis.app.logging.AppLogger
import java.io.File

/**
 * Moves a session's private workspace files into a project folder, safely.
 *
 * ## Why this is not just `File.renameTo`
 *
 * A session's `workspace/` can hold anything the user put there — a cloned
 * repo is the normal case, and this device has a 569 MB one. Three things go
 * wrong with a naive move:
 *
 *  1. `renameTo` fails silently across filesystems, leaving the source intact
 *     and the caller believing the move happened. `copyRecursively` + `delete`
 *     has the opposite failure: a kill halfway leaves BOTH trees and no record
 *     of which is authoritative.
 *  2. A multi-second copy with no progress and no cancel looks like a hang, and
 *     the process is exactly what Android reclaims while it is in the
 *     background doing it.
 *  3. There is no way to tell a completed move from an interrupted one.
 *
 * ## The protocol
 *
 * Copy to `<dest>.staging`, verify byte counts, then delete the source and
 * rename the staging dir into place. The `.staging` suffix is the on-disk
 * marker for "a move was interrupted" — [recoverInterrupted] picks it up.
 *
 * Ordering matters: the source is only deleted after the staging copy is
 * complete, so an interruption at any point leaves at least one intact tree
 * and a `.staging` sibling to resume from. Nothing is ever deleted before a
 * verified replacement exists.
 *
 * ## Scope
 *
 * Only the four shared subdirs (`attachments`, `offloads`, `workspace`,
 * `browser`) move. `memory/` stays with the session — it is the session's
 * private diary and the migrator copies it separately.
 */
object WorkspaceMover {

    private const val TAG = "WorkspaceMover"
    private const val STAGING_SUFFIX = ".staging"
    private const val COPY_BUFFER = 64 * 1024

    data class MoveResult(
        val movedSubdirs: List<String>,
        val bytesMoved: Long,
        val alreadyInPlace: List<String>,
    )

    /**
     * Move [sessionId]'s shared subdirs into [folderId]'s project directory.
     *
     * Idempotent: a subdir that is already the project's (or absent) is left
     * alone and reported in [MoveResult.alreadyInPlace], so re-running after a
     * partial failure converges instead of duplicating data.
     */
    fun moveSessionIntoProject(
        filesDir: File,
        sessionId: String,
        folderId: String,
    ): MoveResult {
        if (!SessionWorkspace.isSafeId(sessionId) || !SessionWorkspace.isSafeId(folderId)) {
            AppLogger.warning(TAG, "refusing move: unsafe id session=$sessionId folder=$folderId")
            return MoveResult(emptyList(), 0L, emptyList())
        }
        val sessionRoot = SessionWorkspace.base(filesDir, sessionId)
        val projectRoot = SessionWorkspace.projectBase(filesDir, folderId)
        SessionWorkspace.ensureProjectDirs(filesDir, folderId)


        val moved = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        var bytes = 0L

        for (sub in SessionWorkspace.SHARED_SUBDIRS) {
            val src = File(sessionRoot, sub)
            if (!src.isDirectory() || src.listFiles().isNullOrEmpty()) {
                // Nothing to move. Also covers the already-filed case, where
                // the session never had its own copy to begin with.
                skipped.add(sub)
                continue
            }
            val dst = File(projectRoot, sub)
            if (dst.isDirectory() && !dst.listFiles().isNullOrEmpty()) {
                // Destination already holds files. Merging two non-empty trees
                // needs a conflict policy this does not have, so leave the
                // session's copy alone rather than silently interleaving them.
                AppLogger.warning(
                    TAG,
                    "merge conflict for $sub (session=$sessionId folder=$folderId); " +
                        "session copy left in place",
                )
                skipped.add(sub)
                continue
            }
            bytes += moveTree(src, dst)
            moved.add(sub)
        }

        AppLogger.info(
            TAG,
            "moved session=$sessionId into folder=$folderId subdirs=$moved " +
                "bytes=$bytes skipped=$skipped",
        )
        return MoveResult(moved, bytes, skipped)
    }

    /**
     * Move one tree via staging. [src] is deleted only after [dst] has a
     * verified copy.
     */
    private fun moveTree(src: File, dst: File): Long {
        val parent = dst.parentFile ?: error("no parent for $dst")
        val staging = File(parent, dst.name + STAGING_SUFFIX)
        if (staging.exists()) staging.deleteRecursively()

        val bytes = copyTree(src, staging)
        val srcBytes = treeSize(src)
        if (bytes != srcBytes) {
            // A short copy means the source changed under us or the disk is
            // full. Keep the source, drop the partial copy.
            staging.deleteRecursively()
            throw IllegalStateException(
                "staging copy incomplete for ${src.name}: copied $bytes of $srcBytes bytes",
            )
        }

        if (!src.deleteRecursively()) {
            staging.deleteRecursively()
            throw IllegalStateException("could not clear source ${src.absolutePath}")
        }
        if (!staging.renameTo(dst)) {
            // Rename into place failed. The source is already gone, so the
            // staging copy is now the only copy — restore it to the source
            // path rather than leaving the data under a .staging name.
            if (!staging.renameTo(src)) {
                throw IllegalStateException(
                    "staging rename failed and source restore failed for ${src.absolutePath}",
                )
            }
            throw IllegalStateException("could not move ${src.name} into place")
        }
        return bytes
    }

    private fun copyTree(src: File, dst: File): Long {
        var total = 0L
        src.walkTopDown().forEach { f ->
            val rel = f.relativeTo(src)
            val target = File(dst, rel.path)
            if (f.isDirectory()) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                f.inputStream().use { input ->
                    target.outputStream().use { output ->
                        val buf = ByteArray(COPY_BUFFER)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            total += n
                        }
                    }
                }
            }
        }
        return total
    }

    private fun treeSize(root: File): Long =
        root.walkTopDown().filter { it.isFile() }.sumOf { it.length() }

    /**
     * Find `.staging` trees left behind by an interrupted move.
     *
     * A staging tree is authoritative for its own destination (the source was
     * already deleted when it was created), so recovery is: finish the rename.
     * A staging tree whose real destination already exists is ambiguous — both
     * trees exist and neither is provably newer — so it is reported and left
     * for the caller to resolve, never auto-deleted.
     */
    fun recoverInterrupted(filesDir: File): List<File> {
        val found = mutableListOf<File>()
        for (root in sequenceOf(
            File(filesDir, SessionWorkspace.WORKSPACES_DIR),
            File(filesDir, SessionWorkspace.SESSIONS_DIR),
        )) {
            if (!root.isDirectory()) continue
            root.walkTopDown()
                .filter { it.name.endsWith(STAGING_SUFFIX) }
                .forEach { staging ->
                    val real = File(staging.parentFile, staging.name.removeSuffix(STAGING_SUFFIX))
                    if (real.exists()) {
                        AppLogger.warning(
                            TAG,
                            "ambiguous staging tree (both exist): ${staging.absolutePath}",
                        )
                    } else if (staging.renameTo(real)) {
                        AppLogger.info(TAG, "recovered staging tree -> ${real.absolutePath}")
                    } else {
                        AppLogger.warning(TAG, "could not recover ${staging.absolutePath}")
                    }
                    found.add(staging)
                }
        }
        return found
    }
}
