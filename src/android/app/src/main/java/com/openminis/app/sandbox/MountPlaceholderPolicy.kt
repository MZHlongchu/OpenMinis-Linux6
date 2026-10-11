package com.openminis.app.sandbox

/**
 * [T-b4-#4] rootfs 内 `/var/minis/mounts/<name>` 占位目录的回收判据（PRoot 的 `-b`
 * 要求这个目标目录真实存在）。
 *
 * 旧判据是"名字不在本轮 `desired` 里就 deleteRecursively()"，而 `desired` 只包含本轮
 * SAF 解析成功的条目：仍在 store 里、只是暂时解析失败的挂载（卡还没挂好、readdir 报
 * EACCES、pre-R 卷拿不到 POSIX 路径）会因此丢掉目标目录——bind 缺席时那就是 rootfs 里
 * 一个真实可写目录，guest 已经写进去的内容会一起被静默删掉。
 *
 * 所以删除必须以 `MountedFoldersStore` 里仍然存在的条目名为准。
 */
internal object MountPlaceholderPolicy {

    /**
     * true 表示 [name] 这个占位目录可以安全删除：既不是本轮解析成功的挂载
     * （[desiredNames]），也不是 store 里仍然存在的条目（[liveEntryNames]），并且
     * 目录为空。其余一律保留——非空目录里可能有 guest 的数据。
     */
    fun isRemovable(
        name: String,
        desiredNames: Set<String>,
        liveEntryNames: Set<String>,
        isEmpty: Boolean,
    ): Boolean = isEmpty && name !in desiredNames && name !in liveEntryNames
}
