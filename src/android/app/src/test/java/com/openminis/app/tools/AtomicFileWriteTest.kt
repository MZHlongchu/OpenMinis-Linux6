package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * Pins the two properties that were missing when `file_write` / `file_edit`
 * "reported success but nothing landed".
 */
class AtomicFileWriteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun writeVerifiesAndReturnsLength() {
        val f = File(tmp.root, "a.txt")
        val n = AtomicFileWrite.write(f, "hello world")
        assertEquals(11L, n)
        assertEquals("hello world", f.readText())
    }

    @Test
    fun overwriteReplacesRatherThanAppends() {
        val f = File(tmp.root, "b.txt")
        AtomicFileWrite.write(f, "first")
        AtomicFileWrite.write(f, "second")
        assertEquals("second", f.readText())
    }

    @Test
    fun appendKeepsExistingBytes() {
        val f = File(tmp.root, "c.txt")
        AtomicFileWrite.write(f, "one")
        AtomicFileWrite.write(f, "-two", append = true)
        assertEquals("one-two", f.readText())
    }

    @Test
    fun concurrentWritesAllLand() {
        // N writers, distinct content, same file. Without the per-path lock a
        // writer can observe a snapshot another is mid-way through replacing.
        val f = File(tmp.root, "race.txt")
        AtomicFileWrite.write(f, "seed")
        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(40)
        val applied = AtomicInteger()
        repeat(40) { i ->
            pool.submit {
                try {
                    // read-modify-write: each task appends its own marker to
                    // whatever is currently there. A lost update shows up as a
                    // missing marker.
                    AtomicFileWrite.readModifyWrite(f) { cur ->
                        val next = if (cur.isEmpty()) "m$i" else "$cur,m$i"
                        AtomicFileWrite.write(f, next)
                        next
                    }
                    applied.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue("tasks did not finish", done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()

        val markers = f.readText().split(",").filter { it.startsWith("m") }.toSet()
        assertEquals("lost updates: ${f.readText()}", 40, markers.size)
        assertEquals(40, applied.get())
    }

    @Test
    fun concurrentEditsDoNotLoseEachOthersChange() {
        // The file_edit shape specifically: many readers computing a distinct
        // replacement over a shared base. Every change must survive.
        val f = File(tmp.root, "base.txt")
        val base = (1..50).joinToString("\n") { "line-$it" }
        AtomicFileWrite.write(f, base)

        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(50)
        repeat(50) { i ->
            pool.submit {
                try {
                    AtomicFileWrite.readModifyWrite(f) { cur ->
                        cur.replaceFirst("line-1", "line-1 edited-$i")
                    }
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue("tasks did not finish", done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()

        val lines = f.readText().lines()
        // Exactly one edited line-1 (the last writer to win), and every other
        // line still present — no edit may have reverted a sibling's change.
        assertEquals(1, lines.count { it.startsWith("line-1 edited-") })
        assertEquals(50, lines.size)
        (2..50).forEach { assertTrue("line-$it missing", lines.contains("line-$it")) }
    }

    @Test
    fun readModifyWriteReturnsNullWhenWriteCannotLand() {
        val f = File(tmp.root, "locked.txt")
        AtomicFileWrite.write(f, "orig")
        // A failed replace must not delete the destination first. OS read-only
        // bits are not reliable here (Windows ignores them), so force the miss.
        AtomicFileWrite.failReplaceForTest = true
        try {
            val out = AtomicFileWrite.readModifyWrite(f) { it + "!" }
            assertNull("a failed write must not look like success", out)
            assertEquals("original content must survive a failed write", "orig", f.readText())
        } finally {
            AtomicFileWrite.failReplaceForTest = false
        }
    }

    @Test
    fun readModifyWritePersistsPayloadImplementingHasPersistableText() {
        // The reported bug: FileEditTool's ReplaceOutcome fell through the
        // no-write branch — readModifyWrite returned it verbatim, the tool
        // reported "Edited N replacements", and the file never changed.
        val f = File(tmp.root, "payload.txt")
        AtomicFileWrite.write(f, "base")
        val out = AtomicFileWrite.readModifyWrite(f) { cur ->
            object : HasPersistableText {
                override fun persistableText(): String? = cur + " edited"
            }
        }
        assertNotNull(out)
        assertEquals("base edited", f.readText())
    }

    @Test
    fun readModifyWriteDoesNotTouchFileWhenPayloadDeclinesPersist() {
        // A failure payload (persistableText = null) must NOT write anything —
        // previously FileEditTool's failure path returned a bare String that
        // readModifyWrite interpreted as new content and wrote over the file.
        val f = File(tmp.root, "decline.txt")
        AtomicFileWrite.write(f, "orig")
        val out = AtomicFileWrite.readModifyWrite(f) { cur ->
            object : HasPersistableText {
                override fun persistableText(): String? = null
            }
        }
        assertNotNull("declined payload still returns itself", out)
        assertEquals("orig", f.readText())
    }

    @Test
    fun readReturnsCurrentBytesUnderLock() {
        val f = File(tmp.root, "r.txt")
        AtomicFileWrite.write(f, "v1")
        assertEquals("v1", AtomicFileWrite.read(f))
        AtomicFileWrite.write(f, "v2")
        assertEquals("v2", AtomicFileWrite.read(f))
        assertNotNull(AtomicFileWrite.read(File(tmp.root, "missing.txt")))
    }

    @Test
    fun readHonoursMaxBytesCap() {
        // [T-b15-diff-preimage-cap] file_write's diff pre-image asks for a bounded
        // read: an oversized file must come back as null (diff degrades to a byte
        // count) instead of being decoded into a String of the whole file.
        val f = File(tmp.root, "cap.txt")
        AtomicFileWrite.write(f, "0123456789")
        assertEquals("0123456789", AtomicFileWrite.read(f, maxBytes = 10L))
        assertNull(AtomicFileWrite.read(f, maxBytes = 9L))
        // Unlimited default keeps the previous semantics for every other caller.
        assertEquals("0123456789", AtomicFileWrite.read(f))
    }

    // ── [T-fileedit-binary-guard] / [T-fileedit-size-cap] / [T-fileedit-bom] ──

    @Test
    fun readModifyWriteRejectsBinaryContentWithoutTouchingTheFile() {
        // The old readText() path replaced the NUL with U+FFFD and wrote the
        // corrupted text back; verify() compared against the same corrupted
        // string and saw nothing wrong.
        val f = File(tmp.root, "bin.dat")
        val bytes = byteArrayOf(0x68, 0x69, 0x00, 0x62)
        f.writeBytes(bytes)
        val out = AtomicFileWrite.readModifyWrite(f) { "transform must not run: $it" }
        assertNull("binary file must be refused", out)
        assertTrue("file bytes must survive untouched", bytes.contentEquals(f.readBytes()))
    }

    @Test
    fun readModifyWriteRejectsInvalidUtf8WithoutTouchingTheFile() {
        val f = File(tmp.root, "invalid.txt")
        val bytes = byteArrayOf(0x68, 0xC3.toByte(), 0x28) // 0xC3 expects a continuation byte
        f.writeBytes(bytes)
        assertNull(AtomicFileWrite.readModifyWrite(f) { "x" })
        assertTrue(bytes.contentEquals(f.readBytes()))
    }

    @Test
    fun readModifyWriteRejectsOversizedFileBeforeReadingItIntoMemory() {
        val f = File(tmp.root, "big.txt")
        f.writeBytes(ByteArray(AtomicFileWrite.MAX_EDIT_BYTES + 1) { 'a'.code.toByte() })
        assertNull(AtomicFileWrite.readModifyWrite(f) { "x" })
        assertEquals(AtomicFileWrite.MAX_EDIT_BYTES + 1L, f.length())
    }

    @Test
    fun readModifyWriteStripsBomForMatchingAndRestoresItOnWrite() {
        // A BOM-prefixed file used to fail all three match tiers because the
        // old_string (written without a BOM) never matched the U+FEFF head.
        val f = File(tmp.root, "bom.txt")
        f.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hello world".toByteArray(Charsets.UTF_8))
        val out = AtomicFileWrite.readModifyWrite(f) { current ->
            assertEquals("transform sees BOM-stripped text", "hello world", current)
            current.replace("hello", "goodbye")
        }
        assertEquals("goodbye world", out)
        val onDisk = f.readBytes()
        assertEquals(0xEF.toByte(), onDisk[0])
        assertEquals(0xBB.toByte(), onDisk[1])
        assertEquals(0xBF.toByte(), onDisk[2])
        assertEquals("\uFEFFgoodbye world", String(onDisk, Charsets.UTF_8))
    }

    // ── [T-checkpoint-restore-atomic] moveIntoPlace ──

    @Test
    fun moveIntoPlaceReplacesTargetAndRemovesTmp() {
        val target = File(tmp.root, "t.txt").apply { writeText("old") }
        val staged = File(tmp.root, "t.tmp").apply { writeText("new-bytes") }
        assertTrue(AtomicFileWrite.moveIntoPlace(staged, target))
        assertEquals("new-bytes", target.readText())
        assertFalse(staged.exists())
    }

    @Test
    fun moveIntoPlaceFailureKeepsPreviousBytes() {
        // The old checkpoint-restore dance deleted the target before the retry
        // rename; when the retry failed too, the only copy was gone. This must
        // never happen: failure returns false and the previous bytes survive.
        val target = File(tmp.root, "k.txt").apply { writeText("precious") }
        val staged = File(tmp.root, "k.tmp").apply { writeText("replacement") }
        AtomicFileWrite.failReplaceForTest = true
        try {
            assertFalse(AtomicFileWrite.moveIntoPlace(staged, target))
        } finally {
            AtomicFileWrite.failReplaceForTest = false
        }
        assertEquals("precious", target.readText())
    }

    // ── [T-append-verify-accounting] append verification ──

    @Test
    fun appendVerificationComparesAgainstTheLengthBeforeTheAppend() {
        // The old code passed the length it had just read back into verify(),
        // so the append branch could only ever agree with itself: an append the
        // filesystem silently dropped still looked like a success.
        val f = File(tmp.root, "append-verify.log")
        f.writeText("head")
        val added = "-tail".toByteArray(Charsets.UTF_8)

        // Nothing was appended: the accounting must fail the write.
        assertNull(AtomicFileWrite.verifyAppend(f, 4L, added))

        f.appendText("-tail")
        assertEquals(9L, AtomicFileWrite.verifyAppend(f, 4L, added))

        // Right length, wrong bytes (the shadow-view case that keeps the size).
        f.writeText("head-XXXX")
        assertNull(AtomicFileWrite.verifyAppend(f, 4L, added))
    }

    @Test
    fun appendReportsAccumulatedByteLengthForMultiByteContent() {
        val f = File(tmp.root, "append-bytes.txt")
        AtomicFileWrite.write(f, "a")
        // '→' is one char but three UTF-8 bytes: the accounting must be bytes.
        val n = AtomicFileWrite.write(f, "→", append = true)
        assertEquals(4L, n)
        assertEquals("a→", f.readText())
        assertEquals(4L, f.length())
    }

    @Test
    fun appendToADirectoryIsNotReportedAsSuccess() {
        val dir = File(tmp.root, "append-dir").apply { mkdirs() }
        assertNull(AtomicFileWrite.write(dir, "nope", append = true))
    }

    // ── [T-fileedit-symlink-target] writes follow the link ──

    @Test
    fun overwriteAndMoveIntoPlaceFollowSymlinksAndKeepTheLink() {
        val real = File(tmp.root, "real.txt").apply { writeText("old-content") }
        val link = File(tmp.root, "link.txt")
        Assume.assumeTrue("symlinks unavailable on this host", symlink(link, real))
        Assume.assumeTrue("symlink was not created", isSymlink(link))

        assertEquals(11L, AtomicFileWrite.write(link, "new-content"))
        assertEquals("the link target must receive the bytes", "new-content", real.readText())
        assertTrue("the link itself must survive", isSymlink(link))
        assertEquals("new-content", AtomicFileWrite.read(link))

        val staged = File(tmp.root, "staged.tmp").apply { writeText("moved-bytes") }
        assertTrue(AtomicFileWrite.moveIntoPlace(staged, link))
        assertEquals("moved-bytes", real.readText())
        assertTrue("the link itself must survive a move", isSymlink(link))
    }

    // ── [T-fileedit-lock-eviction-race] lock table reaping ──

    @Test
    fun reapingIdleLocksDoesNotBreakSerialisationOfOnePath() {
        // Fill the lock table past its cap (so the reaper runs) and then hammer
        // one path. Two mutexes for the same path show up as lost
        // read-modify-write updates.
        repeat(AtomicFileWrite.MAX_LOCK_ENTRIES + 32) { i ->
            AtomicFileWrite.write(File(tmp.root, "filler-$i.txt"), "x")
        }
        val f = File(tmp.root, "hot.txt")
        AtomicFileWrite.write(f, "")

        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(60)
        repeat(60) { i ->
            pool.submit {
                try {
                    AtomicFileWrite.readModifyWrite(f) { cur ->
                        val next = if (cur.isEmpty()) "m$i" else "$cur,m$i"
                        // Nested acquire of the SAME path: where a reaped entry
                        // used to hand out a second lock.
                        AtomicFileWrite.write(f, next)
                        next
                    }
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue("tasks did not finish", done.await(60, TimeUnit.SECONDS))
        pool.shutdownNow()

        val markers = f.readText().split(",").filter { it.startsWith("m") }.toSet()
        assertEquals("lost updates: ${f.readText()}", 60, markers.size)
    }

    private fun symlink(link: File, target: File): Boolean =
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
            true
        } catch (_: Exception) {
            false
        }

    private fun isSymlink(file: File): Boolean =
        try {
            java.nio.file.Files.isSymbolicLink(file.toPath())
        } catch (_: Exception) {
            false
        }

    // ── [T-fileedit-lock-eviction-self-hold] 淘汰不得摘掉在用的锁 ──

    @Test
    fun reapingNeverTakesALockTheReapingThreadItselfHolds() {
        // 摘取判定本身：`ReentrantLock.tryLock()` 可重入——摘取者正好是持有者时会"成功"，
        // 于是淘汰逻辑会把一笔正在使用的锁从表里删掉。
        val mine = ReentrantLock()
        mine.lock()
        try {
            assertFalse("a lock this thread holds is not idle", AtomicFileWrite.tryReapLock(mine))
            assertEquals("the guard must not consume the hold", 1, mine.holdCount)
        } finally {
            mine.unlock()
        }

        // 空闲的锁可以摘：返回 true 即调用方已持有（evictIdleLocks 的契约），要负责 unlock。
        val idle = ReentrantLock()
        assertTrue(AtomicFileWrite.tryReapLock(idle))
        assertTrue(idle.isHeldByCurrentThread)
        idle.unlock()

        // 别的线程持有的锁同样摘不动。
        val other = ReentrantLock()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            other.lock()
            held.countDown()
            release.await()
            other.unlock()
        }
        holder.isDaemon = true
        holder.start()
        assertTrue("holder thread did not start", held.await(5, TimeUnit.SECONDS))
        try {
            assertFalse(AtomicFileWrite.tryReapLock(other))
        } finally {
            release.countDown()
            holder.join(5_000)
        }
    }

    @Test
    fun evictionDoesNotUnlockAPathThisThreadStillHolds() {
        // 端到端形状：本线程在 `readModifyWrite(A)` 里再写第二条路径 B（file_edit 的
        // read-modify-write 里再写一个文件就是这种嵌套）。B 的插入会把锁表顶过上限并
        // 触发淘汰——此时除 A 以外每一条锁都被别的线程持有，淘汰逻辑**必然**会看到 A
        // （没有可摘的条目，它必须扫完整个表），所以这个用例是确定性的：
        //   带 bug：A 的表项被摘走 → 另一个线程写 A 时建出第二把互斥量 → 立刻写成功；
        //   修好后：A 的表项还在 → 那个线程阻塞到本线程释放 A。
        val fillers = AtomicFileWrite.MAX_LOCK_ENTRIES + 1
        val gate = CountDownLatch(1)
        val heldCount = CountDownLatch(fillers)
        val pool = Executors.newFixedThreadPool(fillers)
        repeat(fillers) { i ->
            val f = File(tmp.root, "held-$i.txt")
            pool.execute {
                AtomicFileWrite.readModifyWrite(f) { cur ->
                    heldCount.countDown()
                    gate.await()
                    cur
                }
            }
        }
        var intruder: Thread? = null
        try {
            assertTrue("filler writers did not take their locks", heldCount.await(60, TimeUnit.SECONDS))

            val a = File(tmp.root, "nested-a.txt")
            AtomicFileWrite.write(a, "a0")
            val intruderDone = java.util.concurrent.atomic.AtomicBoolean(false)
            AtomicFileWrite.readModifyWrite(a) { cur ->
                // 插入一条新路径，顶过上限并触发淘汰（keep = trigger.txt）。
                AtomicFileWrite.write(File(tmp.root, "trigger.txt"), "t")
                intruder = Thread {
                    AtomicFileWrite.write(a, "intruder")
                    intruderDone.set(true)
                }.apply {
                    isDaemon = true
                    start()
                }
                intruder!!.join(2_000)
                assertFalse(
                    "a second writer reached a path this thread still holds — the lock was reaped",
                    intruderDone.get(),
                )
                cur
            }
            intruder?.join(10_000)
            assertTrue("the queued writer must finish once the lock is released", intruderDone.get())
        } finally {
            gate.countDown()
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS)
            intruder?.join(5_000)
        }
    }
}
