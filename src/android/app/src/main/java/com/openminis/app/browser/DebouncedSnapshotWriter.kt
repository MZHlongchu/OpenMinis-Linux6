package com.openminis.app.browser

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-browser-save-single-writer-android] 单写者 debounce 落盘器，由
 * BrowserHistoryStore / BrowserTabPool 共用：两者都是"状态一变就请求落盘、
 * 500ms 内的连续变更合并成一次写"，也都要求写盘串行（同一份 JSON 不能被两个
 * 写者并发写坏）。两条契约对应 B6 复核回归：
 *
 * 1. 不丢更新：[request] 只更新"最新待写快照"，谁先请求谁起、且只起一个 drain
 *    job。drain 收尾（判定没有待写快照）与清空 [job] 句柄在**同一把锁**内完成，
 *    因此并发的 request() 要么看到 job 仍在（这份快照会被它写掉），要么看到 job
 *    已清空（它会自己起一个新 job）。即使请求正好落在"job 已越过 debounce、正在
 *    写旧快照"的窗口里也不会两边都不写 —— 旧实现先置快照再判 job?.isActive，
 *    那个窗口里快照无人落盘。
 * 2. 单写者：[write] 只在 [writeLock] 内被调用，任何时刻至多一个写者；[flush]
 *    也走这把锁，并保证自己是最后一个写者。收尾用的 cancel 拦不住已经进入
 *    write(writeText) 的写者，所以串行必须靠锁，而不是靠取消。
 */
internal class DebouncedSnapshotWriter<T>(
    private val scope: CoroutineScope,
    private val writeDispatcher: CoroutineDispatcher,
    private val debounceMs: Long,
    private val write: (T) -> Unit,
) {
    /** 保护 [pending] / [job]：request() / flush() 会从任意线程调用。 */
    private val lock = Any()

    /** 串行化写盘：调用 [write] 前必须持有它。 */
    private val writeLock = Any()

    private var pending: T? = null
    private var job: Job? = null

    /** 请求落盘。快照由调用者在自己线程上备好（调用方的数据未必线程安全）。 */
    fun request(snapshot: T) {
        synchronized(lock) {
            pending = snapshot
            if (job?.isActive == true) return
            job = scope.launch { drain() }
        }
    }

    /**
     * 同步落盘 [snapshot] 并保证它是最后一个写者：丢弃待写的 debounce 快照、取消
     * debounce job，然后等在飞的写者（[writeLock]）写完再写。给 clear()/dispose()
     * 这类"必须立刻持久化、且之后不能再被旧快照覆盖"的收尾用；会阻塞调用者线程
     * （只写一个小 JSON，与改造前的同步写等价）。
     */
    fun flush(snapshot: T) {
        synchronized(lock) {
            pending = null
            job?.cancel()
            job = null
        }
        synchronized(writeLock) { write(snapshot) }
    }

    private suspend fun drain() {
        val self = currentCoroutineContext()[Job] ?: return
        while (true) {
            delay(debounceMs)
            while (true) {
                val snapshot = synchronized(lock) {
                    // 已取消（flush / scope 取消）时绝不把快照取走：取走又不写
                    // 就是丢更新。
                    val queued = if (self.isActive) pending else null
                    if (queued == null) {
                        // 收尾：与"没有待写快照"的判定同锁清空句柄，见类注释 1。
                        // 只清自己的句柄 —— flush() 取消本 job 之后可能已经有新的
                        // drain job 接管，误清会把它的句柄抹掉、放两个写者进来。
                        if (job === self) job = null
                        return
                    }
                    pending = null
                    queued
                }
                withContext(writeDispatcher) {
                    synchronized(writeLock) {
                        // 在飞的写者被 flush() 取消后不再落盘：它手里的快照一定早于
                        // flush 的最终快照（调用者后取的），丢弃不构成丢更新；反过来
                        // 放它写下去，旧快照会盖掉刚写完的最终快照。
                        if (self.isActive) write(snapshot)
                    }
                }
                // 写盘期间到达的新快照：不等下一轮 debounce，立刻续写，免得它在
                // 窗口里被进程结束吞掉。
                if (synchronized(lock) { pending } == null) break
            }
        }
    }
}
