package com.openminis.app.sandbox

import android.content.Context
import android.os.StatFs

/** Partition sample for [GuestWorkloadPolicy.diskRefusal]. */
internal object DiskPressure {
    // [T-shell-perf] 每条命令都 StatFs 探测——量级小但在会话互斥锁内串行执行。
    // 5s 缓存：磁盘压力不会秒级翻转，拒绝判定对时效不敏感。
    @Volatile
    private var cachedAt: Long = 0L
    @Volatile
    private var cached: Pair<Long, Long>? = null
    private const val CACHE_MS = 5_000L

    fun refusal(context: Context, command: String): String? {
        val sample = runCatching { sample(context) }.getOrNull() ?: return null
        return com.openminis.app.sandbox.kernel.IoGovernor.admit(sample.first, sample.second, command)
    }

    private fun sample(context: Context): Pair<Long, Long> {
        val now = android.os.SystemClock.elapsedRealtime()
        cached?.let { if (now - cachedAt < CACHE_MS) return it }
        val stat = StatFs(context.filesDir.absolutePath)
        val available = stat.availableBlocksLong * stat.blockSizeLong
        val total = stat.blockCountLong * stat.blockSizeLong
        val pair = available to total
        cached = pair
        cachedAt = now
        return pair
    }
}
