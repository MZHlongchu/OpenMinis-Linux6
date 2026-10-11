package com.openminis.app.data.body

import java.util.concurrent.ConcurrentHashMap

/**
 * [T-bodies-gc-fork-retain] 「最近被声明引用」的正文 ref 登记表（进程级）：ref → 声明时刻。
 *
 * 事故形态：`ChatRepository.copyMessageInto` 在 payload 未改写时直接照搬源行的 `body_ref`
 * —— 不写文件、不刷 mtime、不留任何引用痕迹，随后才把新行写进库。回收侧
 * （`ChatBodyGc.reclaimOrphanBodies`）完全可能已经读完「全库引用」（那时新行还不存在）：
 * fork 的副本是源会话的复制品，源会话一旦并发被删（删会话那轮回收的候选集正是它的 ref），
 * 这个 ref 在库里就只剩副本一行引用 —— 回收侧看不见它，unlink 掉的却是活引用：副本的气泡与
 * 模型上下文永久退回 stub（`body stored (N bytes)`）。
 *
 * 所以「照搬引用」必须显式声明（[retain]），并且与回收侧的判定共用 [BodyStore.withCommitLock]：
 * 锁内登记 ⇒ 回收侧的锁内逐文件确认（[isRecentlyReferenced]）必然看得见，否则回收侧会先把
 * 文件删掉（那时确实无文件可保）。只登记不锁挡不住「回收侧确认完之后、unlink 之前」插进来的
 * 落库 —— 那正是这份声明存在的理由。
 *
 * 登记只在 [WINDOW_MS] 内有效：新行落库之后回收侧从库里就读得到它，过期条目会被清掉，孤儿
 * 正文照常回收 —— 不会像 pin 那样把正文永久按住。
 *
 * 单独一个文件的原因：`BodyStore.kt` 已贴着 `architecture-policy.json` 的单文件行数上限
 * （存量文件只许缩减），这份与 [BodyGcPins] 同族的引用登记表塞进去必然超限。
 */
internal object BodyRefLedger {
    /**
     * 声明 [ref] 此刻仍被引用（fork 复制行在把 `body_ref` 落库之前调用）。
     *
     * @param nowMillis 仅测试/诊断用（与 `BodyStore.discardTemps` 的 `nowMillis` 同口径）。
     */
    fun retain(ref: String?, nowMillis: Long = System.currentTimeMillis()) {
        if (ref == null || !isBodyRefName(ref)) return
        BodyStore.withCommitLock { mark(ref, nowMillis) }
    }

    /**
     * 该 ref 是否刚被声明引用（新行还没落库的窗口）。
     *
     * **必须在 [BodyStore.withCommitLock] 内调用**：这是回收侧逐文件确认的一半（另一半是
     * [BodyGcPins.isPinned]），与 [retain] 的登记互斥，不能只看删除循环之前的快照。
     */
    fun isRecentlyReferenced(ref: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val at = declarations[ref] ?: return false
        if (nowMillis - at < WINDOW_MS) return true
        declarations.remove(ref, at)
        return false
    }

    /** 测试与异常路径的兜底（同 [BodyGcPins.clearForTests]）。 */
    fun clearForTests() {
        declarations.clear()
    }

    private fun mark(ref: String, nowMillis: Long) {
        if (declarations.size >= PRUNE_AT) {
            declarations.forEach { (name, at) ->
                if (nowMillis - at >= WINDOW_MS) declarations.remove(name, at)
            }
        }
        declarations[ref] = nowMillis
    }

    /** 内容寻址的提交文件名 = 64 位十六进制 sha（`BodyStore.read` 的入参校验同一口径）。 */
    private fun isBodyRefName(ref: String): Boolean =
        ref.length == 64 && ref.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * 登记窗口：与回收宽限期（`BODY_GC_GRACE_MS`）同口径 —— 任何一次「先落盘/先声明、后写
     * 指针」的引用都在这段时间内落库，读到新指针的一轮回收就走库里的引用集合判定了。
     */
    private const val WINDOW_MS = 10L * 60L * 1000L

    /** 登记表大到这个规模才做一次过期清理（平时只是一次 put）。 */
    private const val PRUNE_AT = 4096

    /** 并发集合：fork 复制在协程线程上登记，回收侧在提交锁内查询。 */
    private val declarations: MutableMap<String, Long> = ConcurrentHashMap()
}
