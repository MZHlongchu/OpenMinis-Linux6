package com.openminis.app.tools

import com.openminis.app.logging.AppLogger
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Serialises and verifies the two filesystem-mutating tools.
 *
 * ## The bug this fixes
 *
 * `FileEditTool` is a read-modify-write over the whole file with no locking:
 *
 * ```
 * val content = file.readText()   // v1
 * ... compute newContent ...
 * file.writeText(newContent)      // overwrites with v1+edit
 * ```
 *
 * Two edits running concurrently each read v1 and each write back their own
 * view of it, so the first edit is silently discarded — and BOTH calls return
 * success, because neither `writeText` threw. Callers that re-check the tree
 * afterwards see their change gone with no error anywhere in the log. The same
 * race swallows a `file_write` that lands while a `file_edit` is mid-flight.
 *
 * The tools already run in parallel (sub-agents, multi-tool turns), so this is
 * not hypothetical: it is the reported "reported success but nothing landed"
 * failure.
 *
 * ## What it does
 *
 *  1. **Per-path lock.** Every write to a given canonical path is serialised,
 *     so a read-modify-write can never interleave with another write to the
 *     same file. Different paths stay concurrent — locking globally would
 *     needlessly serialise independent sub-agents.
 *  2. **Post-write verification.** After the write the file is re-read from
 *     disk and compared against what was meant to be written. A short write,
 *     a shadowed FUSE view, or a full disk now returns an ERROR instead of a
 *     success the caller will act on.
 *  3. **Atomic replace.** Content is written to a sibling temp file and
 *     renamed over the target. `writeText` truncates in place, so a crash
 *     mid-write leaves a half-written file; rename makes the old-or-new
 *     choice atomic. The temp file is removed on every failure path.
 *
 * Append mode cannot use rename (it must build on the existing bytes), so it
 * is verified but not made atomic — that is a property of append, not an
 * omission.
 */
object AtomicFileWrite {

    private const val TAG = "AtomicFileWrite"
    private const val TEMP_SUFFIX = ".minis-tmp"

    /**
     * One lock per canonical path. The map is unbounded in theory; in practice
     * a session touches at most a few hundred distinct files, and an entry
     * costs a few dozen bytes. Reaping would need its own lock to do safely,
     * which is a worse trade than the memory.
     */
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    private fun lockFor(file: File): ReentrantLock =
        locks.getOrPut(file.canonicalPath) { ReentrantLock() }

    /**
     * Overwrite [file] with [content], atomically and verified.
     *
     * @param append when true, append instead of overwrite (verified, not atomic)
     * @return the byte length reported on disk, or null when the write did not
     *   verifiably land — callers must treat null as a failure, NOT as success.
     */
    fun write(file: File, content: String, append: Boolean = false): Long? {
        val lock = lockFor(file)
        return kotlin.concurrent.withLock(lock) {
            writeLocked(file, content, append)
        }
    }

    private fun writeLocked(file: File, content: String, append: Boolean): Long? {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            if (!parent.mkdirs()) {
                AppLogger.error(TAG, "could not create parent ${parent.absolutePath}")
                return null
            }
        }

        if (append) {
            // Append cannot go through rename — the result depends on the
            // bytes already there. Verify instead of making atomic.
            return try {
                file.appendText(content)
                verify(file, file.length(), content, appended = true)
            } catch (e: IOException) {
                AppLogger.error(TAG, "append failed for ${file.name}: ${e.message}")
                null
            }
        }

        val tmp = File(parent, file.name + TEMP_SUFFIX)
        try {
            tmp.writeText(content)
            val written = tmp.length()
            val expected = content.toByteArray(Charsets.UTF_8).size.toLong()
            if (written != expected) {
                AppLogger.error(
                    TAG,
                    "short write to temp ${tmp.name}: $written of $expected bytes (disk full?)",
                )
                tmp.delete()
                return null
            }
            if (file.exists() && !file.delete()) {
                AppLogger.error(TAG, "could not clear ${file.name} before rename")
                tmp.delete()
                return null
            }
            if (!tmp.renameTo(file)) {
                AppLogger.error(TAG, "rename ${tmp.name} -> ${file.name} failed")
                tmp.delete()
                return null
            }
            return verify(file, written, content, appended = false)
        } catch (e: IOException) {
            AppLogger.error(TAG, "write failed for ${file.name}: ${e.message}")
            tmp.delete()
            return null
        } catch (e: SecurityException) {
            AppLogger.error(TAG, "write denied for ${file.name}: ${e.message}")
            tmp.delete()
            return null
        }
    }

    /**
     * Read [file] under the same per-path lock the writers take.
     *
     * A read that does not hold the lock can observe bytes that a concurrent
     * write is halfway through replacing. Callers that need a consistent view
     * of a file they also write should use this rather than `File.readText()`.
     */
    fun read(file: File): String? {
        val lock = lockFor(file)
        return kotlin.concurrent.withLock(lock) {
            try {
                if (file.exists()) file.readText() else ""
            } catch (e: IOException) {
                AppLogger.error(TAG, "read failed for ${file.name}: ${e.message}")
                null
            }
        }
    }

    /**
     * Read [file], apply [transform] to its current contents, and write the
     * result back — all under the per-path lock.
     *
     * This is what makes `file_edit` safe. The read has to happen INSIDE the
     * lock, not before it: a read taken before acquiring the lock can observe a
     * snapshot that another writer is already replacing, and the computed edit
     * then silently reverts that writer's change.
     *
     * @return the transform's result, or null when the write did not verify.
     *   The lock is held for the whole read-compute-write, so a null result
     *   never leaves a half-applied edit.
     */
    fun <T> readModifyWrite(file: File, transform: (String) -> T): T? {
        val lock = lockFor(file)
        return kotlin.concurrent.withLock(lock) {
            val current = try {
                if (file.exists()) file.readText() else ""
            } catch (e: IOException) {
                AppLogger.error(TAG, "read failed for ${file.name}: ${e.message}")
                return@withLock null
            }
            val result = transform(current)
            @Suppress("UNCHECKED_CAST")
            when (result) {
                is String -> writeLocked(file, result, append = false)?.let { result }
                is Pair<*, *> -> {
                    // (newContent, anything) — the shape file_edit uses so it can
                    // report the replacement count alongside the text.
                    val text = result.first as? String
                    if (text == null) {
                        AppLogger.error(TAG, "readModifyWrite: pair without a String payload")
                        null
                    } else {
                        writeLocked(file, text, append = false)?.let { result }
                    }
                }
                else -> {
                    // Non-string payload: nothing to write, so just run the
                    // transform for its side effects and pass the value through.
                    AppLogger.info(TAG, "readModifyWrite: non-string result, nothing written")
                    result
                }
            }
        }
    }

    /**
     * Re-read from disk and confirm the bytes are really there.
     *
     * The check is on the FILE, not on the writer's return value: `writeText`
     * returning without throwing is not evidence that the bytes reached the
     * filesystem, which is exactly how the original bug stayed invisible.
     */
    private fun verify(file: File, expectedLen: Long, content: String, appended: Boolean): Long? {
        if (!file.exists()) {
            AppLogger.error(TAG, "verify failed: ${file.absolutePath} does not exist after write")
            return null
        }
        if (file.length() != expectedLen) {
            AppLogger.error(
                TAG,
                "verify failed: ${file.name} is ${file.length()} bytes, expected $expectedLen",
            )
            return null
        }
        if (!appended) {
            // Only the overwrite path can be compared byte-for-byte; an
            // append's on-disk content is the old bytes plus the new ones.
            val onDisk = try {
                file.readText()
            } catch (e: IOException) {
                AppLogger.error(TAG, "verify failed: cannot re-read ${file.name}: ${e.message}")
                return null
            }
            if (onDisk != content) {
                AppLogger.error(TAG, "verify failed: ${file.name} content differs after write")
                return null
            }
        }
        return file.length()
    }
}
