package com.openminis.app.harness.runtime

import com.openminis.app.harness.fs.SessionPath
import com.openminis.app.harness.model.HarnessEntryEntity
import com.openminis.app.harness.model.HarnessLaneEntity
import com.openminis.app.harness.model.HarnessLaneResultEntity
import com.openminis.app.harness.model.HarnessOperationEntity
import com.openminis.app.harness.model.HarnessUsageEntity
import com.openminis.app.harness.operation.OperationRuntimeRepository
import com.openminis.app.harness.session.SessionTreeRepository
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * [T-operation-wiring] 两道运行时接缝（[SessionTreeRepository] + [OperationRuntimeRepository]）
 * 的默认文件实现：`<root>/<sessionId>.json` 单文件承载 lane / entry / operation /
 * result / usage 五张表。
 *
 * 为什么合在一个文件：operation 的 settle/finish 事务同时动 entry、lane、operation
 * 三张表（接缝 KDoc 里「同进同出」的原子性约定），分文件就要跨文件事务；单文件 +
 * 每会话一把 Mutex（见 [fileLocks]）+ tmp/rename 原子改名，事务天然成立。队列持久化
 * （FilePromptQueuePersistence）同款模式，坏文件降级为空——运行时台账是 best-effort
 * 持久层，宁可丢台账不可挡聊天。
 *
 * 会话树在这里是**宿主侧效果的影子账**：Room 才是对话的事实源；这份 entry 链记录
 * 的是 harness 侧观察到的工具意图/落定与 lane 指针，供跨进程死亡的恢复判定
 * （模型已回 tool_use、Room 行还没落盘的崩溃窗口，只有这里有凭据）。
 */
class FileHarnessRuntimePersistence(private val root: File) :
    SessionTreeRepository,
    OperationRuntimeRepository {

    @Serializable
    private data class SessionFile(
        val lanes: List<HarnessLaneEntity> = emptyList(),
        val entries: List<HarnessEntryEntity> = emptyList(),
        val operations: List<HarnessOperationEntity> = emptyList(),
        val results: List<HarnessLaneResultEntity> = emptyList(),
        val usages: List<HarnessUsageEntity> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    // [T-b18-op-lookup-session-domain] 锁拆两层：
    //  - 每会话一把锁（条纹化，容量有界），同一会话文件的读-改-写串行、tmp 名不撞；
    //  - 目录级短锁只护 mkdirs/listFiles，**不覆盖解码**。
    // 原实现只有一把全局 Mutex，而 scanAll 在锁内解码全部会话文件，于是
    // 任意会话的台账写都要排队等它——每个工具轮 8 次 findOperation（每次转移
    // requireOperation + emitFor 各一次）就成了全局串行点。写侧本来就是
    // tmp+rename 原子替换，读者不持锁也不会看到半截文件。
    private val fileLocks = List(LOCK_STRIPES) { Mutex() }
    private val dirLock = Mutex()

    private fun lockFor(sessionId: String): Mutex {
        val h = sessionId.hashCode()
        return fileLocks[(h xor (h ushr 16)) and (LOCK_STRIPES - 1)]
    }

    private fun fileFor(sessionId: String): File {
        // 与队列持久化同款单射转义（共用 [SessionPath]）：lane 会话 id 含冒号/斜杠，
        // 不能直接当文件名。
        return File(root, "${SessionPath.segment(sessionId)}.json")
    }

    private suspend fun <R> withFile(sessionId: String, block: suspend (SessionFile) -> Pair<R, SessionFile>): R =
        lockFor(sessionId).withLock {
            val file = fileFor(sessionId)
            val current = if (file.isFile) {
                runCatching { json.decodeFromString(SessionFile.serializer(), file.readText(Charsets.UTF_8)) }
                    .getOrElse {
                        System.err.println("Harness runtime file unreadable for $sessionId: ${it.message}")
                        SessionFile()
                    }
            } else {
                SessionFile()
            }
            val (result, next) = block(current)
            if (next != current) {
                dirLock.withLock { root.mkdirs() }
                val tmp = File(root, "${file.name}.tmp")
                tmp.writeText(json.encodeToString(SessionFile.serializer(), next), Charsets.UTF_8)
                if (!tmp.renameTo(file)) {
                    file.delete()
                    check(tmp.renameTo(file)) { "atomic rename failed for ${file.name}" }
                }
            }
            result
        }

    // ─── SessionTreeRepository ─────────────────────────────────────────

    override suspend fun ensureLane(sessionId: String, laneName: String): HarnessLaneEntity =
        withFile(sessionId) { f ->
            f.lanes.firstOrNull { it.name == laneName }?.let { it to f }
                ?: run {
                    val lane = HarnessLaneEntity(sessionId, laneName, null, updatedAt = 0L)
                    lane to f.copy(lanes = f.lanes + lane)
                }
        }

    override suspend fun findLane(sessionId: String, laneName: String): HarnessLaneEntity? =
        withFile(sessionId) { f -> f.lanes.firstOrNull { it.name == laneName } to f }

    override suspend fun branch(sessionId: String, leafId: String?): List<HarnessEntryEntity> =
        withFile(sessionId) { f ->
            if (leafId == null) return@withFile emptyList<HarnessEntryEntity>() to f
            val byId = f.entries.associateBy { it.id }
            val chain = ArrayList<HarnessEntryEntity>()
            var cursor: String? = leafId
            while (cursor != null) {
                val entry = byId[cursor] ?: error("Missing entry $cursor")
                chain += entry
                cursor = entry.parentId
            }
            chain.asReversed() to f
        }

    override suspend fun branchTail(sessionId: String, leafId: String?, limit: Int) =
        branch(sessionId, leafId).takeLast(limit)

    override suspend fun appendToLane(sessionId: String, laneName: String, entry: HarnessEntryEntity) {
        withFile(sessionId) { f ->
            val lanes = f.lanes.map { if (it.name == laneName) it.copy(leafId = entry.id, updatedAt = entry.createdAt) else it }
            Unit to f.copy(entries = f.entries + entry, lanes = lanes)
        }
    }

    override suspend fun moveLane(sessionId: String, laneName: String, leafId: String?) {
        withFile(sessionId) { f ->
            Unit to f.copy(lanes = f.lanes.map { if (it.name == laneName) it.copy(leafId = leafId) else it })
        }
    }

    override suspend fun findEntry(sessionId: String, entryId: String): HarnessEntryEntity? =
        withFile(sessionId) { f -> f.entries.firstOrNull { it.id == entryId } to f }

    override suspend fun deleteSessionData(sessionId: String) {
        lockFor(sessionId).withLock { fileFor(sessionId).delete() }
    }

    // ─── OperationRuntimeRepository ────────────────────────────────────

    override suspend fun clearLaneOperation(sessionId: String, laneName: String) {
        withFile(sessionId) { f ->
            Unit to f.copy(lanes = f.lanes.map { if (it.name == laneName) it.copy(currentOperationId = null) else it })
        }
    }

    /** 跨会话扫描：只留给真正的恢复期查询（调用方只有 operationId）。 */
    override suspend fun findOperation(operationId: String): HarnessOperationEntity? =
        scanAll().firstOrNull { it.id == operationId }

    /**
     * [T-b18-op-lookup-session-domain] 会话域查询：operation 行就在该会话自己的
     * 文件里，单文件读——这是协调器每轮 8 次查找的热路径，不再扫全目录。
     */
    override suspend fun findOperation(sessionId: String, operationId: String): HarnessOperationEntity? =
        withFile(sessionId) { f -> f.operations.firstOrNull { it.id == operationId } to f }

    override suspend fun listActiveOperations(sessionId: String): List<HarnessOperationEntity> =
        withFile(sessionId) { f -> f.operations.filter { it.sessionId == sessionId } to f }

    override suspend fun acceptOperation(
        entry: HarnessEntryEntity,
        lane: HarnessLaneEntity,
        operation: HarnessOperationEntity,
    ) {
        withFile(entry.sessionId) { f ->
            Unit to f.copy(
                entries = f.entries + entry,
                operations = f.operations + operation,
                lanes = f.lanes.map { if (it.name == lane.name) lane else it },
            )
        }
    }

    override suspend fun acceptQueuedOperation(
        queueItemId: String,
        entry: HarnessEntryEntity,
        lane: HarnessLaneEntity,
        operation: HarnessOperationEntity,
    ) {
        // 队列消费由 PromptQueuePersistence 自己记账；这里只落运行侧三表。
        acceptOperation(entry, lane, operation)
    }

    override suspend fun beginOperation(lane: HarnessLaneEntity, operation: HarnessOperationEntity) {
        withFile(operation.sessionId) { f ->
            Unit to f.copy(
                operations = f.operations + operation,
                lanes = f.lanes.map { if (it.name == lane.name) lane else it },
            )
        }
    }

    override suspend fun saveOperation(operation: HarnessOperationEntity) {
        withFile(operation.sessionId) { f ->
            Unit to f.copy(operations = f.operations.map { if (it.id == operation.id) operation else it })
        }
    }

    override suspend fun settleEffect(
        entry: HarnessEntryEntity?,
        usage: HarnessUsageEntity?,
        operation: HarnessOperationEntity,
        lane: HarnessLaneEntity,
    ) {
        withFile(operation.sessionId) { f ->
            Unit to f.copy(
                entries = if (entry != null) f.entries + entry else f.entries,
                usages = if (usage != null) f.usages + usage else f.usages,
                operations = f.operations.map { if (it.id == operation.id) operation else it },
                lanes = f.lanes.map { if (it.name == lane.name) lane else it },
            )
        }
    }

    override suspend fun finishOperation(result: HarnessLaneResultEntity, lane: HarnessLaneEntity) {
        withFile(result.sessionId) { f ->
            Unit to f.copy(
                // 完成的操作不留活动程序状态行（lane_results 的 KDoc 口径）
                operations = f.operations.filterNot { it.id == result.operationId },
                results = f.results.filterNot { it.sessionId == result.sessionId && it.laneName == result.laneName } + result,
                lanes = f.lanes.map { if (it.name == lane.name) lane else it },
            )
        }
    }

    /** 跨会话扫 operation 行（findOperation 只有 id）。文件数 = 会话数，量级可控。 */
    private suspend fun scanAll(): List<HarnessOperationEntity> {
        // [T-b18-op-lookup-session-domain] 目录列举在短锁内，解码在锁外：解码全部
        // 会话文件原先持有全局锁，正是跨会话写互相排队的根因；写侧是 tmp+rename
        // 原子替换，读者不持锁也不会读到半截。
        val files = dirLock.withLock {
            root.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.toList()
        } ?: return emptyList()
        return files.flatMap { file ->
            runCatching { json.decodeFromString(SessionFile.serializer(), file.readText(Charsets.UTF_8)).operations }
                .getOrDefault(emptyList())
        }
    }

    private companion object {
        /** 2 的幂：条纹索引用位与取模。 */
        const val LOCK_STRIPES = 64
    }
}
