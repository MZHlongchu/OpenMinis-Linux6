package com.openminis.app.mcp.client

import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.SharedStorageBindPlan
import java.io.File

/**
 * [T-mcp-session-bind] stdio MCP client 的挂载作用域策略。
 *
 * stdio client 是一个常驻 guest 进程，它的 `-b` argv 在 spawn 那一刻固化，所以一个
 * 进程只能服务**一个**会话的目录。作用域不同却复用同一个进程，会话 B 的工具调用就会
 * 读写会话 A 的 `/var/minis/{workspace,attachments,offloads,browser,memory}`——工单
 * `sandbox-env-offload#4`，正是 `[T-android-session-read-boundary]` 要守的边界。
 *
 * 作用域取"会话宿主"（`subagent:<owner>:<lane>` 归一到 `<owner>`）：子代理 lane 与父
 * 会话共用同一份目录（`SessionWorkspace.hostDir` 也是这么解析的），归一后两者复用同一个
 * 进程，而不是每个 lane 各起一个。
 *
 * 每个作用域都是一个真实的宿主进程，所以要有上限：[MAX_SCOPES] 之外按最近使用时间淘汰，
 * 并且只淘汰**空闲且没有在途调用**的作用域——[EVICT_IDLE_FLOOR_MS] 之外还要过 `busyScopes`
 * 那一关：stdio 单次调用超时 300s，只看时间戳的话，跑到第 31 秒的长构建/浏览器调用会被
 * 别的会话的开场调用当成"闲置作用域"关掉（调用方看到 CONNECTION_ERROR / TIMEOUT）。
 *
 * 最近使用时间与在途计数由 [McpScopeLeaseTable] 一起持有：取用缓存里的进程与登记在途是一次
 * 原子操作，淘汰摘除前还要在同一次临界区里复查在途计数，所以"victims 快照之后才开始调用"
 * 的作用域也不会被摘掉。
 */
internal object McpStdioScopePolicy {

    /** 同时保活的挂载作用域上限（每个作用域、每个服务器最多一个 guest 进程）。 */
    const val MAX_SCOPES = 4

    /**
     * 刚用过的作用域不淘汰的冷却窗口。上限因此是"最近 [EVICT_IDLE_FLOOR_MS] 内活跃的
     * 作用域数"，让步于不打断在途调用。
     */
    const val EVICT_IDLE_FLOOR_MS = 30_000L

    /**
     * [T-mcp-preview-scope] 设置面板「工具列表」预览用的合成作用域。它不是会话：没有
     * `sessionDidTerminate` 会来收它，也不该占用 [MAX_SCOPES] 里属于真会话的名额，更不该
     * 按会话布局在 `filesDir/minis-sessions/<scope>/` 下落盘（旧版经
     * `ExecutionCoordinator.sessionBindMounts(mkdirs = true)` 派生挂载点的副作用，就是面板
     * 用过一次留下一个空会话目录）。桥接层对它走"不缓存、不挂会话目录、调用结束即回收"的
     * 路径，见 `McpNativeBridge.clientFor`。
     */
    const val PREVIEW_SCOPE = "mcp-tools-ui"

    fun isPreviewScope(scope: String): Boolean = scope == PREVIEW_SCOPE

    /** 承载 [sessionId] 目录的会话（lane 归一到其父会话）。 */
    fun scopeOf(sessionId: String): String = ExecutionCoordinator.ownerSessionId(sessionId)

    /**
     * 需要关闭的作用域：存活作用域数超过 [cap] 时，按 [lastUsedMs] 升序取最旧的，最多取
     * 超出部分。[keep] 永不入选（刚被使用），[busyScopes]（正在服务调用的作用域）永不入选，
     * 空闲不足 [idleFloorMs] 的也不入选。没有时间戳的作用域按最旧处理。
     *
     * 因为跳过了在用/新建的作用域，实际关闭数可能少于超出部分——[cap] 是"最近活跃作用域数"
     * 的目标值，让步于不打断在途调用。
     */
    fun victims(
        lastUsedMs: Map<String, Long>,
        keep: String,
        nowMs: Long,
        busyScopes: Set<String> = emptySet(),
        cap: Int = MAX_SCOPES,
        idleFloorMs: Long = EVICT_IDLE_FLOOR_MS,
    ): List<String> {
        val excess = lastUsedMs.size - cap
        if (excess <= 0) return emptyList()
        return lastUsedMs.entries.asSequence()
            .filter { it.key != keep }
            // [T-mcp-inflight-evict] 在途调用免淘汰：一次 tools/call 可以合法地跑满 300s
            // 的读超时，远超过 30s 的空闲地板；只看时间戳会把它正在用的进程 close 掉，
            // 调用方拿到的是 CONNECTION_ERROR / TIMEOUT。
            .filter { it.key !in busyScopes }
            .filter { nowMs - it.value >= idleFloorMs }
            .sortedBy { it.value }
            .take(excess)
            .map { it.key }
            .toList()
    }

    /**
     * [T-mcp-preview-mounts] 合成预览作用域的 `-b` 集合 = 全局目录 + 用户 SAF 外部挂载
     * (`/var/minis/mounts/<name>`) + 共享存储，顺序与优先级和
     * `ExecutionCoordinator.buildSessionBindMounts`（真会话作用域）一致：用户挂载覆盖同名
     * 共享存储项（用户可以把一个挂载命名为 `sdcard`，见 `SharedStorageBindPlan.plan`）。
     *
     * 刻意**不含**会话目录（workspace/attachments/offloads/browser/memory）：预览不是会话，
     * 按会话布局派生就会给一个不存在的会话 mkdirs，`filesDir/minis-sessions/<scope>/` 于是
     * 留下一棵空树（旧版本的 [reapEmptyDirs] 就是给这个擦屁股的）。用户挂载是用户自己的
     * 目录、对所有会话都可见，挂上它不引入任何新的可见性；不挂它，预览里的服务器
     * （例如从挂载目录读配置的 server）行为就和真会话不一致。
     *
     * 这是一个纯函数，输入就是三类来源，所以"预览挂什么"这条边界可以直接被单测钉住。
     */
    fun mergePreviewBinds(
        globalBinds: Map<String, String>,
        userMountBinds: Map<String, String>,
        sharedStorageBinds: Map<String, String>,
    ): LinkedHashMap<String, String> {
        val mounts = LinkedHashMap<String, String>()
        mounts.putAll(globalBinds)
        mounts.putAll(userMountBinds)
        for ((linuxPath, host) in sharedStorageBinds) {
            // 同 ExecutionCoordinator：用户已经有一个叫 sdcard 的挂载时，别用共享存储盖掉它
            if (linuxPath == SharedStorageBindPlan.MOUNTS_SDCARD && linuxPath in mounts) continue
            mounts[linuxPath] = host
        }
        return mounts
    }

    /**
     * [T-mcp-preview-scope] 回收一棵"只剩空目录"的树：自底向上删空目录，返回删掉的目录数
     * （含因此变空的 [root]）。
     *
     * 任何一层出现文件（或删不掉的目录），那一层及其所有祖先一律保留——历史上预览进程往
     * `/var/minis/workspace` 写过东西时，进程/用户的数据比"目录干净"重要得多，所以这里只做
     * 删除空目录这一件不会丢数据的事。
     */
    fun reapEmptyDirs(root: File): Int {
        if (!root.isDirectory) return 0
        var removed = 0
        var hasFile = false
        for (child in root.listFiles().orEmpty()) {
            if (child.isDirectory) removed += reapEmptyDirs(child) else hasFile = true
        }
        if (hasFile) return removed
        return if (root.delete()) removed + 1 else removed
    }
}

/**
 * [T-mcp-inflight-lease] 作用域的"租约"表：把「从缓存取用某个作用域的常驻进程」和
 * 「记一次在途调用」绑成**一次原子操作**，并让淘汰摘除在同一次临界区里复查在途计数。
 *
 * 为什么必须是一个临界区：
 *  - 淘汰是"快照（[McpStdioScopePolicy.victims]）+ 逐项摘除"两步。旧实现里调用方先查表
 *    拿到 client、**返回之后**才 `beginScopeCall`，于是快照算完、`detachScope` 之前到达的
 *    同作用域调用只刷新了时间戳、还没进在途集合：它命中的进程被摘掉并 `close()`，调用方
 *    拿到 CONNECTION_ERROR。
 *  - 反过来只信快照也不够（同一窗口里冒出来的调用必须能被看见），所以摘除前要在同一把锁上
 *    复查在途计数（[detachIfIdle]）——先登记的调用一定被看见，后登记的调用则是在摘除**之后**
 *    才写进缓存，不会被这一轮摘除波及。
 *  - [T-mcp-supersede-lease] 被**替换/接管**（[publish] 的指纹变化分支）与显式失效摘掉的
 *    client 已经不在缓存里，但不等于没人在用：在途簿记按作用域、不按 client，别的在途调用
 *    可能正拿着它等回复。所以这条路径的 close 也要过 [deferCloseIfBusy] —— 作用域还忙就先
 *    挂起，由最后一个 [release]（计数归零）把回收动作交回调用方。
 *
 * 契约：只允许传**纯 map/计数**操作的 lambda（禁止 IO、禁止再进别的锁）；spawn 与 close
 * 都在锁外——挂起的回收动作因此以返回值交回，由调用方在锁外执行。纯 JVM（一把锁 + 三张
 * 哈希表），并发行为可以用真线程直接钉住。
 */
internal class McpScopeLeaseTable {

    private val gate = Any()
    private val inFlight = HashMap<String, Int>()
    private val lastUsed = HashMap<String, Long>()

    /**
     * [T-mcp-supersede-lease] 作用域 → "现在不能 close"的回收动作（见 [deferCloseIfBusy]）。
     * 不变量：某作用域在这里有挂起项 ⟹ [inFlight] 里它的计数 > 0；因此 [release] 归零那一刻
     * 一定会把它们交回调用方，不会永久滞留。
     */
    private val deferredClose = HashMap<String, MutableList<() -> Unit>>()

    /**
     * 命中缓存的取用。只有 [lookup]（无 IO）仍能给出目标时才登记在途并刷新时间戳，
     * 返回 null 表示未命中/指纹已变，调用方走重建路径。
     */
    fun <T : Any> acquire(scope: String, nowMs: Long, lookup: () -> T?): T? =
        synchronized(gate) {
            val target = lookup() ?: return null
            inFlight[scope] = (inFlight[scope] ?: 0) + 1
            lastUsed[scope] = nowMs
            target
        }

    /**
     * 重建路径的收尾：在临界区内安装（或接管并发赢家）并登记在途。[install] 只做 map
     * 操作，返回本次调用要用的目标。
     *
     * 安装与登记同锁，是为了和 [detachIfIdle] 的"查在途 + 摘缓存"互斥：先安装的调用会被
     * 看见（摘除跳过整项），后安装的调用则写在摘除之后（它的新进程不在被摘集合里）。
     */
    fun <T : Any> publish(scope: String, nowMs: Long, install: () -> T): T =
        synchronized(gate) {
            val target = install()
            inFlight[scope] = (inFlight[scope] ?: 0) + 1
            lastUsed[scope] = nowMs
            target
        }

    /**
     * [T-mcp-supersede-lease] 替换/接管（或显式失效）前的收尾决定。[own] 是调用方自己在这把
     * 锁里已经登记、但**不持有**被顶替 client 的在途次数（重建路径刚 [publish] 过，正常是 1；
     * 显式失效没登记，是 0）。作用域里除它以外还有别的在途调用时，把 [close] 按作用域挂起并
     * 返回 true —— 调用方不要再 close；没有别人在跑时返回 false，调用方立刻回收。
     *
     * 为什么只能看作用域计数：在途簿记按作用域、不按 client，而"别人会不会正用着这个被顶替的
     * 进程"是真实存在的 —— 它们是在缓存里取到它之后才登记的，只要还在跑，那个进程就可能正在
     * 为它们等回复；此刻 close 就是 destroy 掉它们正在等的进程，调用方拿到 TIMEOUT /
     * CONNECTION_ERROR（与 [detachIfIdle] 防的是同一类事故，只是这条路径换的是缓存条目而不是
     * 摘除整项）。相反地，没有别的在途调用时不可能有人持有它：缓存条目已经在同一个临界区里
     * 换掉了，之后取用的人拿到的是新条目。
     *
     * 挂起的动作由最后一个 [release] 交回调用方，在锁外执行。
     */
    fun deferCloseIfBusy(scope: String, own: Int = 0, close: () -> Unit): Boolean = synchronized(gate) {
        if ((inFlight[scope] ?: 0) - own <= 0) return false
        deferredClose.getOrPut(scope) { mutableListOf() }.add(close)
        true
    }

    /**
     * 一次调用结束；计数归零即摘掉条目，并把 [deferCloseIfBusy] 挂起的回收动作交回调用方
     * （锁外执行——契约不变：临界区内只做 map/计数操作）。异常路径也必须走到，否则作用域
     * 永远不可淘汰、挂起的进程也永远收不掉。
     */
    fun release(scope: String): List<() -> Unit> {
        synchronized(gate) {
            val running = inFlight[scope] ?: return emptyList()
            if (running > 1) {
                inFlight[scope] = running - 1
                return emptyList()
            }
            inFlight.remove(scope)
            return deferredClose.remove(scope) ?: emptyList()
        }
    }

    /** 淘汰名单的输入快照（拷贝，调用方在锁外算 [McpStdioScopePolicy.victims]）。 */
    fun lastUsedSnapshot(): Map<String, Long> = synchronized(gate) { HashMap(lastUsed) }

    /** 正在服务调用的作用域集合 —— 与 [lastUsed] 的键集合同锁读取。 */
    fun busyScopes(): Set<String> = synchronized(gate) { HashSet(inFlight.keys) }

    /**
     * 淘汰摘除：在途计数非 0 时拒绝（返回 false，什么都不改）——快照之后才开始调用的作用域
     * 因此不会被摘掉。成功时连时间戳一起摘掉，并执行 [body]（摘缓存条目，无 IO）。
     */
    fun detachIfIdle(scope: String, body: () -> Unit): Boolean = synchronized(gate) {
        if ((inFlight[scope] ?: 0) > 0) return false
        lastUsed.remove(scope)
        body()
        true
    }

    /** 会话结束/关停：无视在途计数强制摘除（会话没了，这次调用也不再有意义）。 */
    fun detach(scope: String, body: () -> Unit) {
        synchronized(gate) {
            lastUsed.remove(scope)
            body()
        }
    }

    /** 只保留仍有缓存 client 的作用域，清掉"僵尸时间戳"（摘掉 client 之后不再占名额）。 */
    fun retainScopes(live: Set<String>) {
        synchronized(gate) { lastUsed.keys.retainAll(live) }
    }

    /**
     * 关停：清空三张表，并把 [deferCloseIfBusy] 挂起的回收动作交回调用方 —— 被顶替的 client
     * 不在缓存里，随表一起丢掉就等于进程泄漏（[T-mcp-supersede-lease]）。
     */
    fun clear(): List<() -> Unit> {
        synchronized(gate) {
            inFlight.clear()
            lastUsed.clear()
            val pending = deferredClose.values.flatten()
            deferredClose.clear()
            return pending
        }
    }
}
