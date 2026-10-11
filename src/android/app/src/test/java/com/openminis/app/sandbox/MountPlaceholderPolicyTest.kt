package com.openminis.app.sandbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-b4-#4] `PRootKernel.materializeMountTargets` 数据丢失路径的回归测试：旧代码会删掉
 * 所有不在"本轮解析成功集合"里的 `/var/minis/mounts/<name>` 目录，于是仍在 store 里、
 * 只是暂时解析失败的挂载（卡还没挂好、readdir 报 EACCES、pre-R 卷拿不到 POSIX 路径）
 * 会连同 guest 已经写进去的内容一起被删。
 */
class MountPlaceholderPolicyTest {

    @Test
    fun liveMountWhoseResolutionFailedThisRoundIsKept() {
        assertFalse(
            "store 里还在的挂载不能因为本轮解析失败就被回收",
            MountPlaceholderPolicy.isRemovable(
                name = "sdcard-vault",
                desiredNames = emptySet(),
                liveEntryNames = setOf("sdcard-vault"),
                isEmpty = true,
            ),
        )
    }

    @Test
    fun resolvedMountTargetIsKept() {
        assertFalse(
            MountPlaceholderPolicy.isRemovable(
                name = "notes",
                desiredNames = setOf("notes"),
                liveEntryNames = setOf("notes"),
                isEmpty = true,
            ),
        )
    }

    @Test
    fun unknownEmptyPlaceholderIsRemoved() {
        assertTrue(
            MountPlaceholderPolicy.isRemovable(
                name = "removed-mount",
                desiredNames = setOf("notes"),
                liveEntryNames = setOf("notes"),
                isEmpty = true,
            ),
        )
    }

    @Test
    fun unknownNonEmptyDirIsKept() {
        assertFalse(
            "非空目录里可能有 guest 数据，绝不递归删除",
            MountPlaceholderPolicy.isRemovable(
                name = "removed-mount",
                desiredNames = emptySet(),
                liveEntryNames = emptySet(),
                isEmpty = false,
            ),
        )
    }
}
