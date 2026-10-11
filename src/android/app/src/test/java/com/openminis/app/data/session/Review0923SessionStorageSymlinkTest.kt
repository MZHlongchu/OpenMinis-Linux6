package com.openminis.app.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Review 2026-09-23 — guards b8671a2a9 (#375, deleteFilesDetailed +
 * restoreWritable) against symlinks inside a session directory.
 *
 * directorySize() was taught (T-android-storage-symlink-inflation) that a
 * symlink's target is "someone else's bytes, which deleting this directory
 * would not free" and prunes directory links with onEnter. The delete path
 * does not share that rule: `File.deleteRecursively()` and the new
 * `restoreWritable()` both walk with `walkBottomUp()`, which DESCENDS into a
 * symlinked directory. The sandbox can create such links in its workspace
 * (a relative link that climbs out of `/var/minis/workspace` resolves on the
 * host relative to `minis-sessions/<sid>/workspace`).
 *
 * The invariant: clearing session A's files never removes or chmods anything
 * outside session A's own directories.
 */
class Review0923SessionStorageSymlinkTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun write(f: File, bytes: Int) { f.parentFile!!.mkdirs(); f.writeBytes(ByteArray(bytes)) }

    @Test
    fun `BUG a directory symlink in the workspace does not delete the target's contents`() {
        val filesDir = tmp.newFolder("files")
        val storage = SessionStorage(filesDir)
        write(File(filesDir, "minis-sessions/A/workspace/own.txt"), 10)
        val otherFile = File(filesDir, "minis-sessions/B/workspace/precious.txt")
        write(otherFile, 99)
        // A link pointing at another session's workspace, as a relative guest
        // link `ln -s ../../B/workspace peer` would resolve on the host.
        Files.createSymbolicLink(
            File(filesDir, "minis-sessions/A/workspace/peer").toPath(),
            File(filesDir, "minis-sessions/B/workspace").toPath(),
        )

        storage.deleteFilesDetailed("A")

        assertTrue("session B's file must survive clearing session A", otherFile.exists())
    }

    @Test
    fun `a file symlink is removed as a link, the target stays`() {
        val filesDir = tmp.newFolder("files")
        val storage = SessionStorage(filesDir)
        val target = File(filesDir, "minis-global/shared/keep.bin")
        write(target, 5)
        write(File(filesDir, "minis-sessions/A/workspace/own.txt"), 10)
        Files.createSymbolicLink(
            File(filesDir, "minis-sessions/A/workspace/link.bin").toPath(),
            target.toPath(),
        )

        val r = storage.deleteFilesDetailed("A")

        assertTrue(r.isComplete)
        assertTrue(target.exists())
        assertFalse(File(filesDir, "minis-sessions/A").exists())
        // freed must equal what the size screen showed (links count 0).
        assertEquals(10L, r.freed)
    }

    @Test
    fun `BUG restoreWritable does not chmod a read-only directory outside the session`() {
        val filesDir = tmp.newFolder("files")
        val storage = SessionStorage(filesDir)
        write(File(filesDir, "minis-sessions/A/workspace/own.txt"), 10)
        val outside = File(filesDir, "minis-global/skills/pinned")
        write(File(outside, "SKILL.md"), 3)
        // [T-A12-windows-readonly-dir] 前提：宿主文件系统要能表达「目录只读」。
        // Windows 11 / NTFS 上 Directory.setWritable(false) 直接返回 false —— DOS 只读位
        // 对目录不生效，canWrite() 恒为 true（JDK 21 实测）—— 于是
        // assertFalse(outside.canWrite()) 在那类宿主上恒假，测的是 OS 的权限模型，而不是
        // restoreWritable。前提必须在删除之前抓下来：删除之后再读一次的话，真出现
        // 「把 session 外的目录 chmod 回来」的回归时前提会变假、用例退化成 skip —— 那才是
        // 掩盖。能表达 POSIX 权限的宿主（Linux/Android CI、macOS）照常跑完全部断言。
        val readOnlyExpressed = outside.setWritable(false, false) && !outside.canWrite()
        try {
            Files.createSymbolicLink(
                File(filesDir, "minis-sessions/A/workspace/skills").toPath(),
                outside.toPath(),
            )
            storage.deleteFilesDetailed("A")
            // 这两句与权限模型无关，所以先跑：session 外的目录连内容都不许动。
            assertTrue("a directory outside the session must survive", outside.isDirectory)
            assertTrue("its contents must survive too", File(outside, "SKILL.md").isFile)
            Assume.assumeTrue(
                "host filesystem cannot mark a directory read-only (Windows ignores the DOS " +
                    "read-only bit on directories), so the mode assertion below would be testing " +
                    "the OS rather than restoreWritable",
                readOnlyExpressed,
            )
            assertFalse("a directory outside the session must keep its mode", outside.canWrite())
        } finally {
            outside.setWritable(true, true)
        }
    }

    @Test
    fun `freed never exceeds the size the screen reported`() {
        val filesDir = tmp.newFolder("files")
        val storage = SessionStorage(filesDir)
        write(File(filesDir, "minis-sessions/A/workspace/own.txt"), 10)
        write(File(filesDir, "media/2026/09/23/A/img.png"), 20)
        val outside = File(filesDir, "minis-sessions/B/workspace")
        write(File(outside, "big.bin"), 1000)
        Files.createSymbolicLink(File(filesDir, "minis-sessions/A/workspace/peer").toPath(), outside.toPath())

        val reported = storage.minisSize("A") + storage.mediaSize("A")
        val r = storage.deleteFilesDetailed("A")

        assertEquals(30L, reported)
        assertTrue("freed=${r.freed} must be <= reported=$reported", r.freed <= reported)
    }
}
