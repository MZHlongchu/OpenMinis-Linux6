package com.openminis.app.harness.checkpoint

import java.util.concurrent.ConcurrentHashMap

/**
 * Adapted from taixu CheckpointStore (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 *
 * 文件快照的按会话存储与重放规划。不触碰 git：
 * - 每个用户轮次 [beginTurn] 开启一个 checkpoint，仅记录该轮被 write/edit 触碰文件在触碰前的轮初内容；
 * - 同一轮同一路径去重（只保留第一次触碰的轮初内容）；
 * - [beginTurn] 切换时关闭上一轮 checkpoint，并保留最近 [MAX_KEPT] 轮；
 * - [planCodeRewind] 生成从目标轮起逐 path 取最早快照的恢复方案，供 RewindController 落盘。
 *
 * 落盘由 [Persistence] 接缝承担（默认实现见 [FileCheckpointPersistence]）：
 * 关轮时异步写入（失败仅放弃持久化，不影响内存态；进程在写入前被杀会丢最近一轮的持久化——
 * 安全网特性可接受，且该轮大概率会被重做）；启动后首次访问该会话时从磁盘恢复。
 * 未配置根目录时退化为纯内存（进程被杀后 rewind 丢失），行为与旧版一致。
 *
 * [T-c11-checkpoint-lock-order] 锁序约定：内存态互斥用**实例锁**（本对象的监视器），
 * 磁盘懒恢复另有一把按会话分片的恢复锁（[restoreLocks]）。取锁顺序恒为
 * 「会话恢复锁 → 实例锁」——[ensureRestored] 因此只能在不持有实例锁时调用，
 * 见 [planCodeRewind] 与 [ensureRestored] 的注释。
 *
 * [T-d10-lock-surface] 实例锁的实际锁面（旧注释称 "`@Synchronized` 覆盖全部方法"，
 * 已不成立，这里按实现写清）：
 *  - 整方法加锁（`@Synchronized`）：[capture]、[captureAfterImage]、[recordRewindUndo]、
 *    [takeRewindUndo]、[endTurn]、[dropSession]、[rebaseAfterImages]；
 *  - 只把状态读写段圈进 `synchronized(this)`：[beginTurn]、[latestAfterImage]、
 *    [checkpoints]、[anchorMessageIdOf]、[planCodeRewind] 以及 [ensureRestored] 的合并段。
 *    这些方法的慢 IO（[Persistence.readAll] / [Persistence.write] / [Persistence.delete]）
 *    一律在锁外，所以"慢盘不能阻塞其它会话的 capture"这条自述对它们成立。
 * 纪律：**持有实例锁时不得调用 [ensureRestored]**；上面第二类方法的锁内段都不调用它
 * （[planCodeRewind] / [beginTurn] / [latestAfterImage] / [checkpoints] /
 * [anchorMessageIdOf] 都在取实例锁**之前**先做恢复）。
 */
class CheckpointStore() {

    /** 持久化配置；null = 纯内存模式。由宿主在初始化期一次性注入。 */
    @Volatile
    var persistence: Persistence? = null

    /**
     * 落盘执行器：单线程串行（写索引与内容文件、超龄清理无并发竞争）。写入绝不能在
     * [closeTurn] 的实例锁内同步执行——实例锁覆盖 [capture] 等每次 write/edit 前都会
     * 走的热路径（锁面见类头 [T-d10-lock-surface]），一次慢盘（listFiles +
     * deleteRecursively 清理）会串行阻塞其它会话的 capture。
     */
    @Volatile
    internal var diskWriteExecutor: java.util.concurrent.Executor =
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "checkpoint-disk").apply { isDaemon = true }
        }

    /** 磁盘恢复标记：会话首次访问时懒恢复，避免启动期全量 IO。 */
    private val restoredSessions = ConcurrentHashMap.newKeySet<String>()

    /**
     * [T-p2-checkpointstore-drop-race] 已 drop 的会话：dropSession 之后、异步
     * delete 执行之前，若同 id 的查询触发懒恢复，旧 checkpoint 会从盘上「复活」。
     * drop 后本集合钉住该 id，懒恢复跳过；进程内同 id 重建属于异例（UUID），
     * 钉住到进程结束无副作用（删除后盘上本就无数据可恢复）。
     */
    private val droppedSessions = ConcurrentHashMap.newKeySet<String>()

    /**
     * 每会话一把恢复锁：磁盘恢复（慢 IO）在会话间并行，不进实例锁。
     *
     * [T-d10-restore-single-flight] 锁只会在"该会话已恢复"或"已 drop"之后被摘掉
     * （见 [ensureRestored] 的 finally）：恢复失败时保留同一把锁，后来者才会与仍在
     * 重试的调用者串行；旧实现无条件摘锁，于是后来者拿到一把新锁，两次 readAll
     * 并发跑，合并段虽然按轮号过滤去重，却可能把更新的轮插到更旧的轮前面。
     * [dropSession] 会顺手摘掉这里的条目，钉住的会话不会长期占着这把锁。
     */
    private val restoreLocks = ConcurrentHashMap<String, Any>()

    private val sessions = ConcurrentHashMap<String, SessionState>()

    /** 每次 rewind 的撤销记录（单层级）；新写入/新 rewind/会话删除时失效。 */
    private val rewindUndoRecords = ConcurrentHashMap<String, RewindUndoRecord>()

    /** 磁盘持久化接缝：把轮 checkpoint 以索引 + 内容文件形式落盘/读回。 */
    interface Persistence {
        /** 落盘一个已关闭的轮 checkpoint（content 为 null 的快照不产生内容文件）。 */
        fun write(sessionId: String, checkpoint: Checkpoint)

        /** 读取该会话已落盘的全部轮 checkpoint（升序）。 */
        fun readAll(sessionId: String): List<Checkpoint>

        /** 删除该会话的全部持久化数据（会话删除时）。 */
        fun delete(sessionId: String)
    }

    private class SessionState {
        val checkpoints = mutableListOf<Checkpoint>()
        var active: MutableMap<String, FileSnap>? = null
        var activeTurn = 0
        var activePrompt = ""
        var activeAnchorMessageId: String? = null
        var open = false
        /** 已分配的最大轮号；内存裁剪（MAX_KEPT）后 size 会回绕，轮号必须独立单调递增。 */
        var lastTurn = -1
    }

    /** 开启一个新的用户轮次 checkpoint，并关闭上一轮（若有）。 */
    fun beginTurn(sessionId: String, prompt: String, anchorMessageId: String? = null) {
        ensureRestored(sessionId)
        synchronized(this) {
            val state = stateOf(sessionId)
            closeTurn(sessionId, state)
            state.activeTurn = state.lastTurn + 1
            state.activePrompt = prompt.ifBlank { "（空白输入）" }
            state.activeAnchorMessageId = anchorMessageId
            state.active = LinkedHashMap()
            state.open = true
        }
    }

    /** 记录某路径在该轮"触碰前"的内容；无活动轮或路径已记录时忽略。 */
    @Synchronized
    fun capture(sessionId: String, path: String, before: String?): Boolean {
        val state = sessions[sessionId] ?: return false
        val active = state.active ?: return false
        if (path in active) return false
        active[path] = FileSnap(path, before)
        // rewind 之后智能体又开始写文件：撤销窗口关闭（再 undo 会覆盖新写入）
        rewindUndoRecords.remove(sessionId)
        return true
    }

    /** 覆盖式记录某次 rewind 的撤销快照（同会话重复 rewind 以最后一次为准）。 */
    @Synchronized
    fun recordRewindUndo(sessionId: String, record: RewindUndoRecord) {
        rewindUndoRecords[sessionId] = record
    }

    /** 取出（消费）撤销记录；null = 当前没有可撤销的 rewind。 */
    @Synchronized
    fun takeRewindUndo(sessionId: String): RewindUndoRecord? = rewindUndoRecords.remove(sessionId)

    /**
     * 记录某路径**成功写入后**的内容（改动后凭据）。同轮同路径多次写入时后者覆盖前者，
     * 关轮时落盘的是最后一次写入后的状态。无活动轮或该路径无 pre-image（超大文件跳过）
     * 时忽略——改动后凭据永远与轮初快照成对。
     */
    @Synchronized
    fun captureAfterImage(sessionId: String, path: String, content: String): Boolean {
        val state = sessions[sessionId] ?: return false
        val active = state.active ?: return false
        val snap = active[path] ?: return false
        active[path] = snap.copy(afterContent = content)
        return true
    }

    /**
     * 某路径在本 store 记录中的**最后改动后凭据**：活动轮优先，其余按关闭轮新→旧取第一个。
     * restore 的冲突检测用它判断"当前文件是否被外部改动过"；null = 无凭据（不检测）。
     */
    fun latestAfterImage(sessionId: String, path: String): String? {
        ensureRestored(sessionId)
        synchronized(this) {
            val state = stateOf(sessionId)
            state.active?.get(path)?.afterContent?.let { return it }
            for (checkpoint in state.checkpoints.asReversed()) {
                checkpoint.files.firstOrNull { it.path == path }?.afterContent?.let { return it }
            }
            return null
        }
    }

    /** 强制关闭当前活动轮（无触碰则丢弃空轮）。 */
    @Synchronized
    fun endTurn(sessionId: String) {
        sessions[sessionId]?.let { closeTurn(sessionId, it) }
    }

    /** 会话删除/重建时清理。 */
    @Synchronized
    fun dropSession(sessionId: String) {
        // [T-p2-checkpointstore-drop-race] 先钉住再清：异步 delete 排队执行期间，
        // 任何懒恢复都不得把旧 checkpoint 从盘上带回来。
        droppedSessions.add(sessionId)
        sessions.remove(sessionId)
        restoredSessions.remove(sessionId)
        rewindUndoRecords.remove(sessionId)
        // [T-d10-restore-single-flight] 恢复失败时锁会保留（见 ensureRestored），已删的
        // 会话没必要再钉着它；正在持锁的那位由自己的 finally 收尾（remove(key, value)
        // 不会误删别人的新锁）。
        restoreLocks.remove(sessionId)
        // 删除走同一单线程执行器排队：先于它的待写任务先落盘、随后被整体删除——
        // 消除"异步写在 dropSession 之后执行、write() 的 mkdirs 复活已删目录"的竞态
        persistence?.let { disk -> diskWriteExecutor.execute { runCatching { disk.delete(sessionId) } } }
    }

    fun checkpoints(sessionId: String): List<CheckpointMeta> {
        ensureRestored(sessionId)
        synchronized(this) {
            return stateOf(sessionId).checkpoints.map {
                CheckpointMeta(it.turn, it.time, it.prompt, it.files.map { snap -> snap.path }, it.anchorMessageId)
            }
        }
    }

    /** 查询某轮 checkpoint 的用户消息锚点（供对话 fork 定位）。 */
    fun anchorMessageIdOf(sessionId: String, turn: Int): String? {
        ensureRestored(sessionId)
        synchronized(this) {
            return stateOf(sessionId).checkpoints.firstOrNull { it.turn == turn }?.anchorMessageId
        }
    }

    /**
     * 规划"代码回滚"：撤回到 [turn]，即撤销该轮及之后的所有写改动。
     * 对每路径取 [turn] 起最早的轮初内容；路径本身是 [turn] 前创建的、
     * 之后仅被改动，则该最早快照即 [turn] 的轮初内容。
     *
     * [T-c11-checkpoint-lock-order] 本方法**不得**标 `@Synchronized`，磁盘恢复也
     * 必须在取实例锁**之前**完成：本类是 `@Synchronized`（实例锁）+ 会话恢复锁
     * 两层，锁序恒为「恢复锁 → 实例锁」（见 [ensureRestored]）。若在实例锁内调用
     * [ensureRestored]，就与 beginTurn / checkpoints / latestAfterImage 的恢复路径
     * 构成 AB-BA 死锁：本方法持实例锁与会话恢复锁，对方持会话恢复锁等实例锁，
     * 双方永久挂死；且慢盘恢复的整段时间里实例锁被占住，其它会话的 capture
     * 一并被串行阻塞（与 diskWriteExecutor 注释的自述相反）。
     * 状态读取全部仍在下面的 `synchronized(this)` 内。
     */
    fun planCodeRewind(sessionId: String, turn: Int): List<FileSnap> {
        ensureRestored(sessionId)
        synchronized(this) {
            val state = stateOf(sessionId)
            val open = state.active
            // 轮号全局单调（活动轮 = activeTurn = lastTurn+1），MAX_KEPT 裁剪或空轮缺号后
            // 与 checkpoints.size/下标错位；必须按轮号比较，否则目标轮/活动轮会被错误纳入或漏掉。
            val newestClosedTurn = state.checkpoints.lastOrNull()?.turn ?: -1
            val newestTurn = if (open != null) maxOf(newestClosedTurn, state.activeTurn) else newestClosedTurn
            if (turn < 0 || turn > newestTurn) return emptyList()
            val merged = LinkedHashMap<String, FileSnap>()
            for (checkpoint in state.checkpoints) {
                if (checkpoint.turn >= turn) {
                    for (snap in checkpoint.files) {
                        merged.putIfAbsent(snap.path, snap)
                    }
                }
            }
            // 当前（尚未关闭）的轮次也纳入回滚范围：用 activeTurn 判断而非 checkpoints.size
            //（活动轮轮号是 lastTurn+1，裁剪后远大于 checkpoints.size，原条件会漏掉当前进行轮的改动）
            if (open != null && turn <= state.activeTurn) {
                for (snap in open.values) merged.putIfAbsent(snap.path, snap)
            }
            return merged.values.toList()
        }
    }

    /** 首次访问时从磁盘恢复该会话的已关闭轮；后续访问直接用内存态。 */
    private fun stateOf(sessionId: String): SessionState =
        sessions.getOrPut(sessionId) { SessionState() }

    /**
     * [T-p2-checkpointstore-lockio] 磁盘恢复在**实例锁之外**执行：旧实现把
     * readAll 埋在 @Synchronized 调用链里（stateOf），首次访问任一会话都会阻塞
     * 所有会话的 capture/beginTurn/planCodeRewind——与本类"慢 IO 不进实例锁"的
     * 自述（diskWriteExecutor 注释）直接矛盾。恢复锁按会话分片，恢复结果在
     * 实例锁内短暂合并；恢复失败不登记，允许重试（原语义保留）。
     *
     * [T-p2-checkpointstore-restore-gate] [restoredSessions] 登记的是恢复**完成**
     * 而不是恢复**开始**：旧实现在读盘之前就 `add`，并发调用者（同会话的第二次
     * beginTurn、rewind 的 checkpoints 查询）看到标记直接 return，拿到的却是还没
     * 合并磁盘态的空 [SessionState]——rewind 静默判定"无文件可回滚"，轮号也从 0
     * 重新分配（覆盖旧轮 0 的索引与快照）。现在标记只在 readAll 成功合并后置位，
     * 并在会话恢复锁内做双重检查：并发调用者在同一把锁上等待、复用同一份结果。
     *
     * [T-c11-checkpoint-lock-order] 锁序恒为「会话恢复锁 → 实例锁」：本方法是全类
     * **唯一**取恢复锁的地方（另见 [planCodeRewind]），因此它的所有调用方都必须
     * 在**未持有实例锁**的前提下调用它；持有实例锁再进来就会反序取锁。
     *
     * [T-d10-restore-single-flight] 两处并发收口：
     *  1. 恢复锁用 [ConcurrentHashMap.computeIfAbsent] 原子取得。旧写法
     *     `getOrPut` 的"读-改-写"不是原子的（本仓其它处也踩过，见
     *     `ExecutionCoordinator` 的同名注释），两个并发调用者可能各建一把锁 →
     *     同会话两次 readAll 并发跑；
     *  2. 恢复失败时**不摘锁**（旧实现 finally 无条件 remove）：后来者与仍在重试的
     *     调用者共用同一把锁，串行重试。摘锁只在"已恢复"或"已 drop"之后发生。
     * 两者合起来才保证同会话的磁盘恢复是 single-flight；合并段本身也改成按轮号归并
     * （见下面的 `sortBy`），即使将来再出现并发合并也不会打乱 [checkpoints] 的升序。
     */
    private fun ensureRestored(sessionId: String) {
        val disk = persistence ?: return
        if (droppedSessions.contains(sessionId)) return
        if (restoredSessions.contains(sessionId)) return
        val lock = restoreLocks.computeIfAbsent(sessionId) { Any() }
        try {
            synchronized(lock) {
                // 双重检查：等锁期间同会话的恢复可能已完成（或会话已被 drop）。
                if (droppedSessions.contains(sessionId)) return
                if (restoredSessions.contains(sessionId)) return
                val restored = runCatching { disk.readAll(sessionId) }
                    .onFailure {
                        System.err.println("Checkpoint restore failed for $sessionId; will retry: ${it.message}")
                    }
                    .getOrNull() ?: return
                synchronized(this@CheckpointStore) {
                    val state = sessions.getOrPut(sessionId) { SessionState() }
                    // 内存态非空说明本进程已有新轮（恢复发生在运行中），只补齐磁盘里更早的轮
                    val existingTurns = state.checkpoints.mapTo(hashSetOf()) { it.turn }
                    val missing = restored.filter { it.turn !in existingTurns }
                    if (missing.isNotEmpty()) {
                        state.checkpoints.addAll(missing)
                        // [T-d10-restore-single-flight] 按轮号升序归并，而不是"排好序插到队首"：
                        // 恢复出来的轮通常比内存态旧，但并发/分次合并时"插到队首"会让更新的
                        // 轮排到更旧的轮前面，破坏 closeTurn 维持的升序不变量——而 MAX_KEPT
                        // 裁剪是 removeAt(0)（丢最旧），顺序一乱就会把最新的一轮丢掉。
                        state.checkpoints.sortBy { it.turn }
                    }
                    // 恢复后裁剪到 MAX_KEPT（保留最新），与 write() 的 keptFloor 磁盘清理窗口对齐，
                    // 避免恢复列表超过 100 项且与磁盘清理错位
                    while (state.checkpoints.size > MAX_KEPT) state.checkpoints.removeAt(0)
                    restored.maxOfOrNull { it.turn }?.let { if (it > state.lastTurn) state.lastTurn = it }
                }
                // 合并完成才登记；必须在释放恢复锁之前置位（并发者据此跳过读盘）。
                restoredSessions.add(sessionId)
            }
        } finally {
            // [T-d10-restore-single-flight] 只有"已恢复 / 已 drop"才摘锁：恢复失败的会话
            // 保留同一把锁，重试者据此串行（不再各建一把锁并发读盘）。锁对象本身很小，
            // 数量上界是"本进程访问过的会话数"，[dropSession] 会再收一次。
            if (restoredSessions.contains(sessionId) || droppedSessions.contains(sessionId)) {
                restoreLocks.remove(sessionId, lock)
            }
        }
    }

    /**
     * [T-d10-restore-single-flight] 该会话当前是否钉着一把恢复锁（恢复了就释放，
     * 失败时保留到下次成功或 [dropSession]）。只给单测钉这条契约用。
     */
    internal fun holdsRestoreLock(sessionId: String): Boolean = restoreLocks.containsKey(sessionId)

    /**
     * [T-p2-checkpoint-restore-warmup] 预热一次磁盘恢复：宿主在会话装载时于后台
     * 线程调用，把"冷启动后首个 beginTurn 才在调用方线程同步读盘（索引 + 每条
     * 快照内容，可达几十 MB）"的成本提前付掉。幂等（已恢复/纯内存模式直接返回）；
     * 恢复失败不登记，仍由首个真正访问者重试（见 [ensureRestored]）。
     */
    fun prefetch(sessionId: String) {
        ensureRestored(sessionId)
    }

    /**
     * [T-p2-rewind-baseline] rewind 落盘后把冲突检测基线挪到恢复后的状态：
     * restore 的路径 after-image 更新为恢复内容；delete 的路径清空凭据
     * （无凭据 = 不检测）。不挪基线的话，对同一轮的**第二次** rewind 拿旧
     * after-image 与已被本 store 改写的盘面比较 → 全部误报「会话外被修改」并
     * 跳过——fail-safe 但判定与文案语义都是错的。
     */
    @Synchronized
    fun rebaseAfterImages(sessionId: String, images: Map<String, String?>) {
        val state = sessions[sessionId] ?: return
        for ((path, content) in images) {
            val active = state.active
            if (active != null && active.containsKey(path)) {
                active[path] = active.getValue(path).copy(afterContent = content)
                continue
            }
            for (i in state.checkpoints.indices.reversed()) {
                val checkpoint = state.checkpoints[i]
                if (checkpoint.files.none { it.path == path }) continue
                state.checkpoints[i] = checkpoint.copy(
                    files = checkpoint.files.map { if (it.path == path) it.copy(afterContent = content) else it },
                )
                break
            }
        }
    }

    private fun closeTurn(sessionId: String, state: SessionState) {
        val active = state.active ?: return
        if (active.isNotEmpty()) {
            val checkpoint = Checkpoint(
                turn = state.activeTurn,
                time = System.currentTimeMillis(),
                prompt = state.activePrompt,
                files = active.values.toList(),
                anchorMessageId = state.activeAnchorMessageId,
            )
            state.checkpoints.add(checkpoint)
            while (state.checkpoints.size > MAX_KEPT) state.checkpoints.removeAt(0)
            if (checkpoint.turn > state.lastTurn) state.lastTurn = checkpoint.turn
            // 异步落盘（checkpoint 不可变，可安全移交）：失败只放弃持久化不影响内存态
            persistence?.let { disk ->
                diskWriteExecutor.execute { runCatching { disk.write(sessionId, checkpoint) } }
            }
        }
        state.active = null
        state.activeAnchorMessageId = null
        state.open = false
    }

    companion object {
        const val MAX_KEPT = 100

        /** 单文件快照上限：超过即整体跳过捕获（pre-image 与改动后凭据同规则）。 */
        const val SNAPSHOT_MAX_BYTES = 1L * 1024L * 1024L
    }
}
