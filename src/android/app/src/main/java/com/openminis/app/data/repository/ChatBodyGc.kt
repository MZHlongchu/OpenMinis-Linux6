package com.openminis.app.data.repository

import com.openminis.app.data.body.BodyGcPins
import com.openminis.app.data.body.BodyRefLedger
import com.openminis.app.data.body.BodyStore
import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.MessageVersionDao
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [T-bodies-gc] `files/bodies/` 回收的支撑函数。
 *
 * 单独一个文件的原因：`ChatRepository.kt` 已在 `.architecture-baseline.json` 的
 * 文件尺寸棘轮上（存量超限文件只许下调不许上涨，architectureCheck 会拦），而回收的
 * 判定与候选集收集既要给单会话删除用、也要给批量删除用 —— 堆在棘轮文件里必然超限，
 * 拆开也让两处共用同一套判定，不会漂移。
 */

private const val TAG = "ChatBodyGc"

/**
 * [T-bodies-gc] 孤儿正文的回收宽限期。`BodyStore.put` 先原子落盘、随后才把
 * `body_ref` 写回 DB；这个窗口里的新正文在"全库引用"看来是无人引用的。只回收早于
 * 该宽限期的文件，就不可能删掉刚写好、指针还没落库的正文。
 *
 * [T-bodies-gc-dedupe-touch] `BodyStore.put` 去重命中时会刷新 mtime，所以"盘上很旧、
 * 但此刻被重新引用"的正文同样落在宽限期内。
 */
internal const val BODY_GC_GRACE_MS = 10L * 60L * 1000L

/** [T-bodies-gc] BodyStore 的提交文件名就是 64 位十六进制 sha；其余（含 .tmp）不动。 */
internal fun isBodyRefName(name: String): Boolean =
    name.length == 64 && name.all { it in '0'..'9' || it in 'a'..'f' }

/**
 * [T-bodies-gc] 回收判定（纯函数，便于单测）：无人引用 **且** 已过宽限期才可删。
 *
 * `modifiedMillis <= 0`（stat 失败、mtime 读不出来）一律当成"刚被引用"：宁可留下
 * 一个孤儿文件，也不能删掉活正文 —— 删错一次，这条消息的气泡与模型上下文就永久
 * 退回 stub，而且不可恢复。
 */
internal fun shouldReclaimBody(
    ref: String,
    live: Set<String>,
    modifiedMillis: Long,
    nowMillis: Long,
): Boolean {
    if (!isBodyRefName(ref)) return false
    if (ref in live) return false
    if (modifiedMillis <= 0L) return false
    return nowMillis - modifiedMillis >= BODY_GC_GRACE_MS
}

/**
 * [T-bodies-gc] 全库仍被引用的正文 ref = `messages.body_ref` ∪
 * `message_versions.body_ref`（归档版本同样引用正文，见 MessageVersionEntity）。
 *
 * 读不出来时返回 null：调用方必须整轮放弃。把"读失败"当成"没人引用"就是删活正文。
 */
internal suspend fun liveBodyRefs(dao: ChatDao, versionDao: MessageVersionDao?): Set<String>? =
    runCatching {
        val refs = HashSet<String>(dao.allBodyRefs())
        versionDao?.let { refs.addAll(it.allBodyRefs()) }
        refs
    }.getOrElse {
        AppLogger.warning(TAG, "[T-bodies-gc] 引用集合读取失败，跳过本轮回收: ${it.message}")
        null
    }

/**
 * [T-bodies-gc] 一个会话引用到的正文 ref（删会话前的候选集）。
 *
 * 点查而不是全表读：`messages` 上有 `(session_id, sort_order)` 索引，`WHERE
 * session_id = ?` 走索引范围扫；批量删除时每个会话一次点查，远比每个会话各跑一遍
 * 无索引的 `SELECT DISTINCT body_ref`（全表）便宜。
 */
internal suspend fun referencedBodyRefsForSession(
    dao: ChatDao,
    versionDao: MessageVersionDao?,
    sessionId: String,
): Set<String> {
    val refs = HashSet<String>()
    runCatching { dao.bodyRefsForSession(sessionId) }
        .getOrDefault(emptyList())
        .forEach { refs.add(it) }
    versionDao?.let { vdao ->
        runCatching { vdao.bodyRefsForSession(sessionId) }
            .getOrDefault(emptyList())
            .forEach { refs.add(it) }
    }
    return refs
}

/** [T-bodies-gc] 启动期维护：清掉上次被杀掉的写入留下的临时文件。 */
internal suspend fun discardStoredBodyTemps(root: File) {
    // [T-bodies-gc-main-thread] 目录扫描同样是 File 层工作，不许留在调用者线程上。
    withContext(Dispatchers.IO) {
        runCatching { BodyStore(root).discardTemps() }
            .onFailure { AppLogger.warning(TAG, "[T-bodies-gc] discardTemps 失败: ${it.message}") }
    }
}

/**
 * [T-bodies-gc] `files/bodies/` 的回收实现（[ChatRepository.collectOrphanBodies] 的实体）。
 *
 * 内容寻址的正文文件此前只增不减，删会话/重新生成都留下永久孤儿，`BackupExporter` 还会把
 * 整个目录打进每一次备份，包体积只增不减。
 *
 * 引用判定 = `messages.body_ref` ∪ `message_versions.body_ref`（归档版本同样引用正文，见
 * MessageVersionEntity）。[candidates] 给出「本次操作可能变成孤儿」的 ref 集合（删会话时
 * 取该会话的引用集，由 [referencedBodyRefsForSession] 点查）；null 表示全库对账，扫描整个
 * 目录（清存储 / 启动期维护用）。
 *
 * 五道保险：
 * 1. [T-bodies-gc-main-thread] File 层（扫描 / stat / unlink）一律在 [Dispatchers.IO]：
 *    调用方 `SessionListViewModel.deleteSession` / `deleteFolderWithSessions` 都在
 *    `Main.immediate` 上 await 本函数，不能让一次删会话卡在目录遍历上；
 * 2. 引用集合读不出来时整轮放弃（宁可留着文件，也不能删掉还在用的正文）；
 * 3. 只删「最后修改早于 [BODY_GC_GRACE_MS]」的文件，mtime 读不出来同样保留
 *    （[shouldReclaimBody]）：`BodyStore.put` 是先落盘、之后才把 body_ref 写回 DB；
 * 4. [T-bodies-gc-recheck] unlink 前重读引用集合 + 重看 mtime（两遍之间可能有新行提交），
 *    且这一次「确认-删除」与 `BodyStore.put` 的引用刷新共用一把锁
 *    （[BodyStore.withCommitLock]），把 TOCTOU 压成原子；
 * 5. [T-bodies-gc-pin-lock] 锁内**逐文件**确认「此刻仍被按住 / 刚被声明引用」
 *    （[BodyGcPins.isPinned] / [BodyRefLedger.isRecentlyReferenced]）：删除循环之前取的快照
 *    抓不到导出途中 `recordWritten` 加进来的 ref，也抓不到 fork 复制（[BodyRefLedger.retain]）
 *    刚落下的声明 —— 只认快照就会在「确认完之后、unlink 之前」删掉活引用。
 *
 * @return 真正删掉的文件数。
 */
internal suspend fun reclaimOrphanBodies(
    filesDir: File?,
    dao: ChatDao,
    versionDao: MessageVersionDao?,
    candidates: Collection<String>? = null,
): Int = withContext(Dispatchers.IO) {
    val root = filesDir?.let { File(it, "bodies") } ?: return@withContext 0
    if (!root.isDirectory) return@withContext 0
    val live = liveBodyRefs(dao, versionDao) ?: return@withContext 0
    val targets = if (candidates == null) {
        root.listFiles().orEmpty().filter { it.isFile }
    } else {
        candidates.map { File(root, it) }
    }
    // 第一遍只挑候选，真正的删除留到二次确认之后。
    val now = System.currentTimeMillis()
    // [T-bodies-gc-pin] 备份导出的快照按住的正文不算孤儿：回收只看"此刻的库引用"，而备份
    // 手里那份快照可能比库里多（导出期间被删的会话）。见 BodyGcPins。
    // 这里只是省事的早筛（省掉一次二次确认读取 + 循环）；权威判定在锁内逐文件确认。
    val held = BodyGcPins.snapshot()
    val doomed = targets.filter { shouldReclaimBody(it.name, live + held, it.lastModified(), now) }
    if (doomed.isEmpty()) return@withContext 0
    // [T-bodies-gc-recheck] 二次确认：只在"确实有文件要删"的这一轮多付一次引用集合读取
    // （空转的回收零额外开销），读不出来就整轮放弃 —— fail-closed，宁可留下孤儿也不能删掉
    // 还在用的正文。重看 mtime 是因为 BodyStore 去重命中会刷新它（宽限期从"最后一次被引用"
    // 起算），删除前再看一眼才看得见刚发生的引用。
    val late = liveBodyRefs(dao, versionDao) ?: return@withContext 0
    val nowLate = System.currentTimeMillis()
    var removed = 0
    var removedBytes = 0L
    for (file in doomed) {
        // [T-bodies-gc-toctou] 「重看 mtime → unlink」与 BodyStore.put 的引用刷新互斥（同一
        // 把锁）：否则 put 能在我们看完 mtime 之后刷新引用，我们再把这个活引用删掉。锁只按
        // 单个文件持有 —— 需要原子的是「确认-删除」这一对，不是整轮清理，不能把一批文件的
        // 删除时间都算到 put 的阻塞上。
        BodyStore.withCommitLock {
            // [T-bodies-gc-pin-lock] 逐文件确认"此刻仍被按住 / 刚被声明引用"，而不是只认循环
            // 之前取的快照：pin 的登记集合会被导出途中的 recordWritten 加元素，fork 复制也会
            // 在循环进行中声明引用（BodyRefLedger.retain）—— 快照抓不到这两类，锁内实时确认才能
            // 保证「确认无人引用」与 unlink 之间没有新引用插进来。
            if (BodyGcPins.isPinned(file.name) || BodyRefLedger.isRecentlyReferenced(file.name)) {
                return@withCommitLock
            }
            if (shouldReclaimBody(file.name, late, file.lastModified(), nowLate)) {
                val size = file.length()
                if (file.delete()) {
                    removed++
                    removedBytes += size
                }
            }
        }
    }
    if (removed > 0) {
        AppLogger.info(TAG, "[T-bodies-gc] 回收孤儿正文 $removed 个 / $removedBytes 字节")
    }
    removed
}
