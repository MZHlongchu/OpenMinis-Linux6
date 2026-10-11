package com.openminis.app.backup

import com.openminis.app.data.body.BodyGcPins
import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.MessageVersionDao
import com.openminis.app.logging.AppLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-bodies-gc-backup-align] 备份与正文回收（`ChatRepository.collectOrphanBodies`）的
 * 时间点对齐。
 *
 * 事故形态：`BackupExporter` 先导出行、之后才回头读「哪些正文还被引用」并把正文文件拷进
 * 包。两个时间点之间用户删掉一个会话（或清存储 / 存储页对账）就会触发一轮回收：等读到
 * 引用集合时，被删会话的 ref 已经从库里消失，于是它们的正文被当作孤儿过滤掉 —— 包里留下
 * 引用它们的行，却没有正文。恢复后 `body_ref` 悬空，那些消息永久退回 stub。
 *
 * 对齐方式：**先冻结快照，再导出行**（[take] 必须在任何 JSONL 写出之前调用），并且
 *  1. 快照内的 ref 一律进包 —— 宁可多带一个没人引用的正文，也不能让包里缺正文；
 *  2. 导出过程中真正写进包的行引用并入同一个集合（[recordWritten]），所以"导出中途才
 *     落库的行"同样有正文；
 *  3. 整个窗口内这些 ref 被 pin 住（[BodyGcPins]）：快照对齐只解决"过滤"，不解决
 *     "unlink"—— 文件仍可能在拷贝前一刻被另一轮回收删掉。
 */
internal class BackupBodySnapshot private constructor(
    private val refs: MutableSet<String>?,
    private val handle: BodyGcPins.Handle?,
) {
    /** 引用集合读不出来（库不可用）时为 true —— 此时按「全部导出」处理。 */
    val isComplete: Boolean get() = refs == null

    /** 该正文文件是否属于本次快照。 */
    fun covers(ref: String): Boolean = refs == null || ref in refs

    /** 快照里的全部 ref；读失败（全部导出）时为 null。 */
    fun referencedNames(): Set<String>? = refs

    /** 记录一条真正写进包里的行的 `body_ref`。 */
    fun recordWritten(ref: String?) {
        val target = refs ?: return
        if (!ref.isNullOrEmpty()) target.add(ref)
    }

    /** 解除 pin。导出结束必须调用（`finally`），否则孤儿正文会被一直按住。 */
    fun release() {
        handle?.close()
    }

    companion object {
        /**
         * 冻结全库仍被引用的正文 ref = `messages.body_ref` ∪ `message_versions.body_ref`
         * （归档版本同样引用正文，见 MessageVersionEntity）。
         *
         * 读失败时返回"全部导出"的快照：宁可多带文件，也不能让备份缺正文。此时**不** pin
         * 任何集合 —— 空的登记表只会让回收侧白跑一次判定。
         */
        suspend fun take(chatDao: ChatDao, versionDao: MessageVersionDao?): BackupBodySnapshot {
            val refs = runCatching {
                // 并发集合：pin 登记的是对象本身，导出期间 recordWritten 可能随时加元素，
                // 回收侧同时在读。
                val live: MutableSet<String> = ConcurrentHashMap.newKeySet()
                live.addAll(chatDao.allBodyRefs())
                versionDao?.let { live.addAll(it.allBodyRefs()) }
                live
            }.getOrElse {
                AppLogger.warning(TAG, "[Backup] 正文引用集合读取失败，按全部导出: ${it.message}")
                return BackupBodySnapshot(null, null)
            }
            return BackupBodySnapshot(refs, BodyGcPins.pin(refs))
        }
    }
}

/** [T-bodies-gc-backup-align] 一次正文导出统计。 */
internal data class BodyExportResult(
    val included: Int,
    val skippedUnreferenced: Int,
    /** 快照里仍被引用、但盘上找不到文件的 ref 数（已经悬空的正文，无法补）。 */
    val missingReferenced: Int,
)

/**
 * 把 [BackupBodySnapshot] 覆盖到的正文文件交给 [blobStore]/[fileIndex]。
 *
 * 过滤只认快照，不看导出中途的库状态：`files/bodies/` 里删会话/重新生成留下的永久孤儿
 * 不该进入每一次备份（否则包体积只增不减），而快照覆盖的文件一律打包。
 */
internal fun exportBodyFiles(
    root: File,
    blobStore: BackupBlobStore,
    fileIndex: BackupFileIndexWriter,
    snapshot: BackupBodySnapshot,
): BodyExportResult {
    if (!root.isDirectory) {
        return BodyExportResult(0, 0, snapshot.referencedNames()?.size ?: 0)
    }
    var included = 0
    var skippedOrphans = 0
    val present = HashSet<String>()
    for (body in root.listFiles().orEmpty()) {
        if (!body.isFile || body.name.endsWith(".tmp")) continue
        present.add(body.name)
        if (!snapshot.covers(body.name)) {
            skippedOrphans++
            continue
        }
        runCatching {
            when (val outcome = blobStore.addFile(body, "chats/_bodies/${body.name}")) {
                is BackupBlobStore.Outcome.Stored, is BackupBlobStore.Outcome.Duplicate -> {
                    val sha = when (outcome) {
                        is BackupBlobStore.Outcome.Stored -> outcome.sha256
                        is BackupBlobStore.Outcome.Duplicate -> outcome.sha256
                        else -> return@runCatching
                    }
                    fileIndex.write(
                        BackupFileIndexEntry.file(
                            "chats/_bodies/${body.name}", body.length(), sha, BackupCategory.CHATS
                        )
                    )
                    included += 1
                }
                is BackupBlobStore.Outcome.SkippedTooLarge -> fileIndex.write(
                    BackupFileIndexEntry.sizeSkipped(
                        "chats/_bodies/${body.name}", body.length(), BackupCategory.CHATS
                    )
                )
            }
        }
    }
    if (skippedOrphans > 0) {
        AppLogger.info(TAG, "[Backup] skipped $skippedOrphans unreferenced body file(s)")
    }
    // [T-bodies-gc-backup-align] 快照说有引用、盘上没有文件 = 本来就悬空的正文（例如更早的
    // 一轮回收删错了、或覆盖安装后文件丢失）。这里补不回来，但必须说出来 —— 否则包"看起来
    // 完整"，问题只会在恢复之后的某条消息上以 stub 的形式冒出来。
    val missing = snapshot.referencedNames()?.let { refs ->
        refs.count { !it.endsWith(".tmp") && it !in present }
    } ?: 0
    if (missing > 0) {
        AppLogger.warning(
            TAG, "[Backup] $missing referenced body file(s) are missing on disk; package will carry dangling bodyRef"
        )
    }
    return BodyExportResult(included, skippedOrphans, missing)
}

private const val TAG = "Backup"
