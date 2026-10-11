package com.openminis.app.tools

import com.openminis.app.logging.AppLogger
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

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
/**
 * [T-file-edit-silent-drop] Result payloads that carry the text to persist.
 *
 * `readModifyWrite` historically understood only two shapes — a plain
 * String (full new content) and a Pair (String first + metadata second).
 * `FileEditTool`'s transform returned a `ReplaceOutcome` (a data class
 * wrapping `newContent`), which matched NEITHER branch, fell into the
 * `else` arm, and was returned verbatim WITHOUT any write. FileEditTool
 * then saw a non-null outcome and reported success — "Edited N
 * replacements" — while the file on disk never changed. That is the
 * exact reported failure: tools claim success, nothing landed.
 *
 * Any transform payload that wants to persist implements this interface.
 */
interface HasPersistableText {
    /** The exact text to write to the file, or null for "nothing to persist". */
    fun persistableText(): String?
}

object AtomicFileWrite {

    private const val TAG = "AtomicFileWrite"
    private const val TEMP_SUFFIX = ".minis-tmp"

    /** Unit tests set this so a failed replace can be forced on every OS. */
    internal var failReplaceForTest = false

    /**
     * One lock per canonical path. A session touches at most a few hundred
     * distinct files, so the map is capped and idle entries are reaped — see
     * [acquireLock] / [evictIdleLocks] for the invariant that makes reaping
     * safe (a reaped lock must never be one another writer is using, or about
     * to use: two mutexes for one path is the lost update this file exists to
     * prevent).
     */
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    // [T-fileedit-size-cap] readModifyWrite + verify each read the whole
    // file; without a cap, `file_edit` on a multi-GB log doubles resident
    // memory and can OOM. 2 MB covers every legitimate source edit.
    internal const val MAX_EDIT_BYTES = 2 * 1024 * 1024

    /** [T-fileedit-locks-bounded] Idle-eviction threshold for [locks]. */
    internal const val MAX_LOCK_ENTRIES = 256

    /**
     * Acquire the per-path mutex and return it **held**; [withFileLock]
     * releases it.
     *
     * [T-fileedit-locks-bounded] A per-process map keyed by absolute path has
     * no natural end on long sessions. The earlier cap policy was a blanket
     * clear() — which is exactly wrong for a MUTEX table: dropping a lock
     * another writer is holding (or queued on) lets a concurrent writer of the
     * SAME path take a fresh lock object and interleave its read-modify-write.
     * Idle entries are evicted instead (see [evictIdleLocks]).
     *
     * [T-fileedit-lock-eviction-race] Eviction alone is not enough: a lock that
     * `getOrPut` has just published is not locked *yet*, so it is
     * indistinguishable from an idle one and a reaper can drop it before its
     * owner acquires it — the owner then holds an orphan while the next writer
     * builds a second mutex for the same path, and both run their
     * read-modify-write at once. The fix is the identity check after the
     * acquire: only the lock that is still registered for [path] counts as the
     * mutex. A writer that lost its entry releases the orphan and retries.
     * The retry cannot spin: a reap only runs above the cap and takes the map
     * down to half of it.
     */
    private fun acquireLock(file: File): ReentrantLock {
        val path = file.canonicalPath
        while (true) {
            val lock = locks.getOrPut(path) { ReentrantLock() }
            lock.lock()
            if (locks[path] === lock) {
                evictIdleLocks(path)
                return lock
            }
            lock.unlock()
        }
    }

    /** [T-fileedit-locks-bounded] Drop entries nobody holds, down to half the cap. */
    private fun evictIdleLocks(keep: String) {
        if (locks.size <= MAX_LOCK_ENTRIES) return
        for (entry in locks.entries) {
            if (locks.size <= MAX_LOCK_ENTRIES / 2) return
            if (entry.key == keep) continue
            val victim = entry.value
            // Only ever reap a lock this thread can take without already holding
            // it — a held or queued one is in use, and a *reentrant* tryLock on
            // our own lock would succeed and tear an in-use mutex out of the
            // table ([T-fileedit-lock-eviction-self-hold]). `remove(key, value)`
            // so an entry re-created in the meantime is not deleted by mistake.
            if (!tryReapLock(victim)) continue
            try {
                locks.remove(entry.key, victim)
            } finally {
                victim.unlock()
            }
        }
    }

    /**
     * [T-fileedit-lock-eviction-self-hold] 摘取判定：**本线程已经持有的锁绝不算空闲**。
     *
     * `ReentrantLock.tryLock()` 是可重入的——摘取者正好是持有者时它会成功，于是
     * [evictIdleLocks] 会把一笔**正在使用**的锁从表里删掉（[keep] 只挡住"当前这一条
     * 路径"，而同一线程在嵌套写里可能同时持有多条路径的锁：`file_edit` 的
     * read-modify-write 过程中再写第二个文件就是这种形状）。表里没有它之后，下一个写
     * 同一路径的线程会建出**第二把**互斥量，两条路径并发写同一个文件——正是本文件要
     * 防的丢更新。
     *
     * 返回 true 表示调用方**已经持有** [victim]（必须 unlock）；false 表示不该摘。
     */
    internal fun tryReapLock(victim: ReentrantLock): Boolean =
        !victim.isHeldByCurrentThread && victim.tryLock()

    /** Run [block] under the per-path mutex. */
    private inline fun <T> withFileLock(file: File, block: () -> T): T {
        val lock = acquireLock(file)
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Overwrite [file] with [content], atomically and verified.
     *
     * @param append when true, append instead of overwrite (verified, not atomic)
     * @return the byte length reported on disk, or null when the write did not
     *   verifiably land — callers must treat null as a failure, NOT as success.
     */
    fun write(file: File, content: String, append: Boolean = false): Long? =
        withFileLock(file) { writeLocked(file, content, append) }

    /**
     * [T-fileedit-symlink-target] Follow symlinks to the file whose bytes must
     * actually change, so the write goes *through* the link and the link survives.
     * `rename(2)` — and therefore `File.renameTo` — replaces a symlink itself,
     * not its target, while every read path ([verify], `readText`) follows it:
     * overwriting `link` left the target untouched and dropped a regular file
     * where the link had been, while [verify] re-read that same path (now a
     * regular file) and reported success.
     * [T-symlink-realpath] `toRealPath`, NOT `canonicalFile`: Windows
     * `File.canonicalPath` leaves reparse points unresolved (probed Windows 11,
     * JDK 21 — `link.canonicalFile` is the link itself), so canonicalising was a
     * no-op there and the rename still replaced the link; Android/Linux agree
     * (realpath(3)). A path that does not exist yet (toRealPath requires
     * existence) falls back to the caller's path.
     */
    private fun writeTarget(file: File): File =
        try {
            file.toPath().toRealPath().toFile()
        } catch (e: IOException) {
            AppLogger.debug(TAG, "could not resolve ${file.absolutePath}: ${e.message}")
            file
        } catch (e: SecurityException) {
            AppLogger.warning(TAG, "could not resolve ${file.absolutePath}: ${e.message}")
            file
        }

    /**
     * Move [tmp] onto [file] without deleting [file] first. A failed rename
     * copies over the destination; if that also fails the previous bytes stay.
     */
    private fun replaceSibling(tmp: File, file: File): Boolean {
        if (failReplaceForTest) return false
        if (tmp.renameTo(file)) return true
        return try {
            tmp.copyTo(file, overwrite = true)
            file.isFile && file.length() == tmp.length()
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Move an already-written [tmp] file onto [file] atomically, under the
     * same per-path lock every other writer takes.
     *
     * [T-checkpoint-restore-atomic] Callers that hand-roll `renameTo` tend to
     * "fix" a failed rename with `target.delete(); rename again` — and when
     * the retry also fails, the only copy of the user's file is gone. This
     * never deletes the target first: rename, or copy-over as a fallback, or
     * return false with the previous bytes intact.
     */
    fun moveIntoPlace(tmp: File, file: File): Boolean = withFileLock(file) {
        // [T-fileedit-symlink-target] Same reasoning as writeLocked: renaming
        // onto a symlink would replace the link, not the file it points at.
        val target = writeTarget(file)
        if (!replaceSibling(tmp, target)) {
            AppLogger.error(TAG, "replace ${tmp.name} -> ${target.name} failed; previous bytes kept")
            return@withFileLock false
        }
        if (tmp.exists()) tmp.delete()
        true
    }

    private fun writeLocked(file: File, content: String, append: Boolean): Long? {
        // [T-fileedit-symlink-target] Write to the resolved target, not the
        // caller's path — see writeTarget(). [acquireLock] still keys on the
        // canonical path, so a link and its target keep separate mutexes.
        val target = writeTarget(file)
        val parent = target.parentFile
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
                // [T-append-verify-accounting] 记账式校验：追加前先记下长度，追加后
                // 断言（长度 == before + UTF-8 字节数）并回读追加区间逐字节比对。
                // 旧代码把 appendText 之后刚读到的 file.length() 当期望长度传进
                // verify()——同一个值跟自己比——于是 append 分支实际只剩「文件存在」
                // 这一条检查：FUSE 影子视图把追加静默丢掉时，工具照旧报成功。
                val before = if (target.exists()) target.length() else 0L
                val added = content.toByteArray(Charsets.UTF_8)
                target.appendText(content)
                verifyAppend(target, before, added)
            } catch (e: IOException) {
                AppLogger.error(TAG, "append failed for ${target.name}: ${e.message}")
                null
            }
        }

        val tmp = File(parent, target.name + TEMP_SUFFIX)
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
            // Do not delete [target] before the new bytes are in place.
            // renameTo fails when the destination exists on some hosts; deleting
            // first then failing the rename discarded the only copy.
            if (!replaceSibling(tmp, target)) {
                AppLogger.error(TAG, "replace ${tmp.name} -> ${target.name} failed; previous bytes kept")
                tmp.delete()
                return null
            }
            if (tmp.exists()) tmp.delete()
            return verify(target, written, content)
        } catch (e: IOException) {
            AppLogger.error(TAG, "write failed for ${target.name}: ${e.message}")
            tmp.delete()
            return null
        } catch (e: SecurityException) {
            AppLogger.error(TAG, "write denied for ${target.name}: ${e.message}")
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
     *
     * [T-b15-diff-preimage-cap] [maxBytes] is for callers that only want a
     * *bounded* pre-image (file_write's 4 KB diff): an oversized file returns
     * null — checked under the lock, so a concurrent grow cannot slip past —
     * instead of being decoded into a String of the whole file. Default null
     * keeps the unlimited semantics every other caller relies on.
     */
    fun read(file: File, maxBytes: Long? = null): String? = withFileLock(file) {
        try {
            when {
                !file.exists() -> ""
                maxBytes != null && file.length() > maxBytes -> {
                    AppLogger.debug(TAG, "read of ${file.name} skipped: ${file.length()} bytes > $maxBytes")
                    null
                }
                else -> file.readText()
            }
        } catch (e: IOException) {
            AppLogger.error(TAG, "read failed for ${file.name}: ${e.message}")
            null
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
    fun <T> readModifyWrite(file: File, transform: (String) -> T): T? =
        withFileLock(file) {
            // [T-fileedit-binary-guard] Read BYTES and decode strictly. The
            // old `file.readText()` silently replaced malformed sequences
            // with U+FFFD and then wrote the CORRUPTED text back to disk;
            // verify() compared against the already-corrupted string and was
            // blind to the damage. NUL bytes and invalid UTF-8 are refused.
            // [T-fileedit-size-cap] The length check runs BEFORE readBytes:
            // checking after the read loaded a multi-GB file fully into
            // memory before refusing it — the cap existed to prevent exactly
            // that. The post-read `raw.size` check stays as a race guard
            // (the file can grow between length() and readBytes()).
            if (file.exists() && file.length() > MAX_EDIT_BYTES) {
                AppLogger.error(TAG, "file_edit refused: ${file.name} is ${file.length()} bytes (cap $MAX_EDIT_BYTES)")
                return null
            }
            val raw = try {
                if (file.exists()) file.readBytes() else ByteArray(0)
            } catch (e: IOException) {
                AppLogger.error(TAG, "read failed for ${file.name}: ${e.message}")
                return null
            }
            if (raw.size > MAX_EDIT_BYTES) {
                AppLogger.error(TAG, "file_edit refused: ${file.name} is ${raw.size} bytes (cap $MAX_EDIT_BYTES)")
                return null
            }
            if (raw.contains(0.toByte())) {
                AppLogger.error(TAG, "file_edit refused: ${file.name} looks binary (NUL byte)")
                return null
            }
            val hadBom = raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte()
            val current = try {
                java.nio.ByteBuffer.wrap(raw).let { buf ->
                    kotlin.text.Charsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(buf).toString()
                }
            } catch (e: java.nio.charset.CharacterCodingException) {
                AppLogger.error(TAG, "file_edit refused: ${file.name} is not valid UTF-8 (${e.message})")
                return null
            }.removePrefix("\uFEFF")

            val result = transform(current)

            // [T-fileedit-bom] A BOM was stripped before matching (an
            // old_string written without one used to fail all three match
            // tiers against a BOM-prefixed file); restore it on write so the
            // file's original encoding convention survives the edit.
            fun withBom(text: String): String =
                if (hadBom) "﻿" + text else text

            return when (result) {
                // A String payload is the plain "new content" shape; a Pair is
                // what file_edit returns so it can report the replacement count
                // alongside the text. Both are written; anything else is passed
                // through untouched (the caller ran the transform for its own
                // reasons and there is nothing to persist).
                is String -> writeLocked(file, withBom(result), append = false)?.let { result }
                is Pair<*, *> -> {
                    val text = result.first as? String
                    if (text == null) {
                        AppLogger.error(TAG, "readModifyWrite: pair without a String payload")
                        null
                    } else {
                        writeLocked(file, withBom(text), append = false)?.let { result }
                    }
                }
                is HasPersistableText -> {
                    val text = result.persistableText()
                    if (text == null) {
                        AppLogger.info(TAG, "readModifyWrite: payload declined persist, nothing written")
                        result
                    } else {
                        writeLocked(file, withBom(text), append = false)?.let { result }
                    }
                }
                else -> {
                    AppLogger.info(TAG, "readModifyWrite: non-string result, nothing written")
                    result
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
    private fun verify(file: File, expectedLen: Long, content: String): Long? {
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
        // Only the overwrite path gets here — the file was replaced wholesale,
        // so it must equal [content] byte for byte.
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
        return file.length()
    }

    /**
     * [T-append-verify-accounting] Verify an append against the length the file
     * had *before* it ([before]) plus the bytes that were meant to be added
     * ([added]) — never against a length read back after the write, which can
     * only ever agree with itself.
     *
     * Both halves matter: `appendText` returning normally proves nothing (the
     * shadow FUSE view behind external mounts can swallow the write), and a
     * length-only check cannot see a write that landed with different bytes.
     * Only the appended region is read back, so a huge append target does not
     * pull the whole file into memory. Internal so a test can drive the
     * mismatch directly.
     *
     * @return the resulting length, or null when the append did not verifiably
     *   land.
     */
    internal fun verifyAppend(file: File, before: Long, added: ByteArray): Long? {
        val expected = before + added.size
        if (!file.exists()) {
            AppLogger.error(TAG, "verify failed: ${file.absolutePath} does not exist after append")
            return null
        }
        if (file.length() != expected) {
            AppLogger.error(
                TAG,
                "verify failed: ${file.name} is ${file.length()} bytes after append, expected $expected",
            )
            return null
        }
        if (added.isEmpty()) return expected
        return try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(before)
                val buf = ByteArray(minOf(added.size, 64 * 1024))
                var off = 0
                while (off < added.size) {
                    val n = minOf(buf.size, added.size - off)
                    raf.readFully(buf, 0, n)
                    for (i in 0 until n) {
                        if (buf[i] != added[off + i]) {
                            AppLogger.error(
                                TAG,
                                "verify failed: ${file.name} differs from the appended bytes " +
                                    "at offset ${before + off + i}",
                            )
                            return null
                        }
                    }
                    off += n
                }
                expected
            }
        } catch (e: IOException) {
            AppLogger.error(TAG, "verify failed: cannot re-read ${file.name}: ${e.message}")
            null
        }
    }
}
