package com.openminis.app.sandbox

import android.content.Context
import android.os.Process as AndroidProcess
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Host-side owner of every sandbox process we spawn.
 *
 * The brake does not depend on the command string. A guest can rename
 * itself, call setsid, raise a soft rlimit, or be reparented onto the app.
 * Each root is recorded, moved to the background cgroup, and remembered
 * with every descendant ever seen. Stop, deadline, hang, memory pressure
 * and the process cap all kill that set, not just the pid we started.
 */
internal object SandboxWorkload {

    private const val TAG = "SandboxWorkload"
    private const val SWEEP_MS = 400L
    private const val KILL_PASSES = 3

    private val CGROUP_FILES = listOf(
        "/dev/cpuctl/bg/cgroup.procs",
        "/dev/cpuctl/bg/tasks",
        "/dev/stune/background/cgroup.procs",
        "/dev/stune/background/tasks",
        "/dev/blkio/bg/cgroup.procs",
        "/dev/blkio/background/tasks",
    )

    /**
     * [T-d10-stat-split] `/proc/<pid>/stat` 的字段切分。原先写死在 [statStartTime]
     * 里，等于**每次调用**都重新 `Regex("\\s+")` 编译一遍：sweep 每 400ms 扫一次
     * 全机 `/proc`（见 [snapshotProc]，每个 pid 都要走一次），这条热路径上每轮都在
     * 重复编译同一个常量模式。Pattern 不可变，提到对象级只编译一次。
     */
    private val WHITESPACE = Regex("\\s+")

    private class Root(val pid: Int, val label: String) {
        val sticky = StickyTree(pid)
        val protected: Boolean = label.startsWith("terminal:")
        @Volatile var deadlineAt: Long = 0L

        /**
         * [T-b18-sandbox-sweep-cost] 上一轮已经 confine 过的进程。
         * confine 幂等，重复执行没有收益，只是每个 pid 6 次文件写 + 一次反射 +
         * 一次 setThreadPriority。sweep 只对差集下手。
         *
         * [T-b18-pid-reuse] 记的是**进程身份**而不是裸 pid：pid → `/proc/<pid>/stat`
         * 的 starttime（自开机起的时钟节拍）。pid 会在两次 sweep（400ms）之间被内核
         * 回收，旧实现（裸 pid 集合）会让复用同一 pid 的新子进程命中"上一轮已 confine"
         * 的旧结论，于是它整条命都不进后台 cgroup、优先级也不降——差集优化反而成了
         * 一条静默绕过资源策略的通道。starttime 变了就代表"这个 pid 现在是另一个
         * 进程"，必须重新 confine。判定见 [ConfinementLedger]。
         */
        @Volatile var confined: Map<Int, Long> = emptyMap()
    }

    private val roots = ConcurrentHashMap<Int, Root>()
    @Volatile private var supervisorStarted = false
    @Volatile private var appContext: Context? = null
    @Volatile var lastStopReason: String? = null
        private set

    // [T-b18-sandbox-sweep-cost] 反射结果缓存：原先每个 pid、每次 confine 都重新
    // getMethod("setProcessGroup")（400ms 一轮 × 上百 pid）。方法对象不可变，缓存安全。
    private val setProcessGroupMethod: java.lang.reflect.Method? by lazy {
        runCatching { AndroidProcess::class.java.getMethod("setProcessGroup", Integer.TYPE, Integer.TYPE) }
            .onFailure { Log.w(TAG, "setProcessGroup reflection unavailable: ${it.message}") }
            .getOrNull()
    }
    private val setpgidMethod: java.lang.reflect.Method? by lazy {
        runCatching { Os::class.java.getMethod("setpgid", Integer.TYPE, Integer.TYPE) }
            .onFailure { Log.w(TAG, "setpgid reflection unavailable: ${it.message}") }
            .getOrNull()
    }

    // [T-b18-sandbox-sweep-cost] 空闲期条件等待：原先 supervisor 无脑 400ms 空转
    // 一轮（roots 为空时 sweep 立刻早退）。改成等信号，trackPid 注册新 root 时唤醒。
    private val workLock = java.util.concurrent.locks.ReentrantLock()
    private val workSignal = workLock.newCondition()
    private var pendingWork = false

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun hasLiveWork(): Boolean = roots.isNotEmpty()

    fun track(process: java.lang.Process, label: String) {
        val pid = pidOf(process) ?: return
        trackPid(pid, label)
    }

    fun trackPid(pid: Int, label: String) {
        if (pid <= 0) return
        val root = roots.computeIfAbsent(pid) { Root(pid, label) }
        adopt(pid)
        confineTree(root)
        ensureSupervisor()
        signalWork()
        Log.i(TAG, "track pid=$pid label=$label live=${roots.size}")
    }

    /** Wall-clock backstop. Zero clears it. The guest cannot extend this. */
    fun armDeadline(process: java.lang.Process?, wallMs: Long) {
        val pid = process?.let { pidOf(it) } ?: return
        val root = roots[pid] ?: return
        root.deadlineAt = if (wallMs <= 0L) 0L else SystemClock.elapsedRealtime() + wallMs
    }

    fun clearDeadline(process: java.lang.Process?) {
        val pid = process?.let { pidOf(it) } ?: return
        roots[pid]?.deadlineAt = 0L
    }

    fun release(process: java.lang.Process?, kill: Boolean, reason: String) {
        val pid = process?.let { pidOf(it) } ?: return
        releasePid(pid, kill, reason)
        if (kill) runCatching { process.destroyForcibly() }
    }

    fun releasePid(pid: Int, kill: Boolean, reason: String) {
        if (pid <= 0) return
        if (kill) killOwned(pid, reason)
        roots.remove(pid)
    }

    fun stopAll(reason: String) {
        val snapshot = roots.keys.toList()
        if (snapshot.isEmpty()) return
        Log.w(TAG, "stopAll reason=$reason roots=${snapshot.size}")
        for (pid in snapshot) releasePid(pid, kill = true, reason = reason)
    }

    /** Hang and memory pressure. A `terminal:` root is the user's shell and is not a victim. */
    fun stopUnprotected(reason: String) {
        val snapshot = roots.entries.filter { !it.value.protected }.map { it.key }
        if (snapshot.isEmpty()) return
        Log.w(TAG, "stopUnprotected reason=$reason roots=${snapshot.size}")
        for (pid in snapshot) releasePid(pid, kill = true, reason = reason)
    }

    fun killOwned(pid: Int, reason: String) {
        lastStopReason = reason
        val sticky = roots[pid]?.sticky ?: StickyTree(pid)
        var victims = emptySet<Int>()
        repeat(KILL_PASSES) {
            val now = discover(pid)
            victims = sticky.observe(now, now)
            for (victim in victims) {
                if (victim == pid) continue
                signal(victim)
            }
        }
        Log.w(TAG, "kill reason=$reason root=$pid victims=${victims.joinToString(",")}")
        runCatching { Os.kill(-pid, OsConstants.SIGKILL) }
        for (victim in sticky.snapshot()) signal(victim)
    }

    private fun signal(pid: Int) {
        if (pid <= 0) return
        runCatching { Os.kill(pid, OsConstants.SIGKILL) }
        runCatching { AndroidProcess.killProcess(pid) }
    }

    private fun adopt(pid: Int) {
        val method = setpgidMethod ?: return
        val failed = runCatching { method.invoke(null, pid, pid) }.exceptionOrNull()
        if (failed != null) Log.w(TAG, "setpgid $pid failed: ${failed.message}")
    }

    private fun confine(pid: Int) {
        setProcessGroupMethod?.let { method ->
            runCatching { method.invoke(null, pid, 0) }
        }
        runCatching { AndroidProcess.setThreadPriority(pid, AndroidProcess.THREAD_PRIORITY_LOWEST) }
        val text = pid.toString()
        for (path in CGROUP_FILES) {
            val file = File(path)
            if (!file.isFile) continue
            runCatching { file.writeText(text) }
        }
    }

    private fun pidOf(process: java.lang.Process): Int? = runCatching {
        val raw = process.javaClass.getMethod("pid").invoke(process) as Number
        raw.toInt()
    }.getOrNull()?.takeIf { it > 0 }

    private fun confineTree(root: Root) {
        val snapshot = snapshotProc()
        val current = snapshot.ownedBy(root.pid)
        val owned = root.sticky.observe(current, current)
        applyConfinement(root, current, owned, snapshot.starts)
    }

    /**
     * [T-b18-pid-reuse] "这一轮该 confine 谁"由纯逻辑 [ConfinementLedger] 判定
     * （见那里的三条判据），这里只负责执行与回写进程身份。
     */
    private fun applyConfinement(
        root: Root,
        current: Set<Int>,
        owned: Set<Int>,
        starts: Map<Int, Long>,
    ) {
        val decision = ConfinementLedger.decide(
            previous = root.confined,
            current = current,
            owned = owned,
            startTime = starts,
        )
        for (pid in decision.toConfine) confine(pid)
        root.confined = decision.confined
    }

    /**
     * `/proc/<pid>/stat` 的 starttime（整体第 22 个字段，自开机起的时钟节拍）。
     *
     * [T-b18-pid-reuse] `comm` 可能含空格与括号，所以从**最后一个** `)` 之后开始数：
     * 那里的第一个字段是 state（整体第 3 个），于是 starttime 的下标是 22 - 3 = 19。
     * 读不出来（hidepid 限制 / 进程刚消失）返回 null，调用方按"身份未知"保守处理。
     *
     * internal 而非 private：字段下标算错会静默退化成"每轮都 confine"或"永远不再
     * confine"，两种都看不出来，所以判据必须被单测钉住（见 `ConfinementLedgerTest`）。
     */
    internal fun statStartTime(stat: String): Long? {
        val close = stat.lastIndexOf(')')
        if (close < 0 || close + 2 >= stat.length) return null
        val fields = stat.substring(close + 2).trim().split(WHITESPACE)
        return fields.getOrNull(19)?.toLongOrNull()
    }

    /** 一次 `/proc` 快照（父子链 + 进程组 + 进程身份），供同一轮的所有 root 复用。 */
    private class ProcSnapshot(
        val links: List<ProcLink>,
        val groups: Map<Int, Int>,
        /** [T-b18-pid-reuse] pid → starttime。pid 被复用时该值必然不同。 */
        val starts: Map<Int, Long>,
    ) {
        fun ownedBy(rootPid: Int): Set<Int> =
            ProcessTree.collect(rootPid, links) + ProcessTree.sameGroup(rootPid, groups)
    }

    private fun snapshotProc(): ProcSnapshot {
        val links = ArrayList<ProcLink>()
        val groups = HashMap<Int, Int>()
        val starts = HashMap<Int, Long>()
        val proc = File("/proc")
        val dirs = proc.listFiles() ?: return ProcSnapshot(emptyList(), emptyMap(), emptyMap())
        for (dir in dirs) {
            val pid = dir.name.toIntOrNull() ?: continue
            val status = runCatching { File(dir, "status").readText() }.getOrNull() ?: continue
            val (ppid, tracer) = ProcessTree.parseStatusIds(status)
            links += ProcLink(pid, ppid, tracer)
            val stat = runCatching { File(dir, "stat").readText() }.getOrNull()
            if (stat != null) {
                groups[pid] = ProcessTree.parseStatPgrp(stat)
                statStartTime(stat)?.let { starts[pid] = it }
            }
        }
        return ProcSnapshot(links, groups, starts)
    }

    private fun discover(rootPid: Int): Set<Int> {
        val snapshot = snapshotProc()
        return snapshot.ownedBy(rootPid)
    }

    private fun ensureSupervisor() {
        if (supervisorStarted) return
        synchronized(this) {
            if (supervisorStarted) return
            supervisorStarted = true
            thread(name = "SandboxWorkload", isDaemon = true) {
                while (true) {
                    try {
                        // [T-b18-sandbox-sweep-cost] 没有 root 时条件等待（trackPid
                        // 唤醒）；有 root 时维持 400ms 的巡检节拍。
                        if (roots.isEmpty()) {
                            awaitWork()
                            continue
                        }
                        Thread.sleep(SWEEP_MS)
                        sweep()
                    } catch (t: Throwable) {
                        Log.w(TAG, "supervisor: ${t.message}")
                    }
                }
            }
        }
    }

    /** 空闲等待。与 [signalWork] 共用同一把锁，pendingWork 保证不会丢唤醒。 */
    private fun awaitWork() {
        workLock.withLock {
            if (!pendingWork) workSignal.await()
            pendingWork = false
        }
    }

    private fun signalWork() {
        workLock.withLock {
            pendingWork = true
            workSignal.signalAll()
        }
    }

    private fun sweep() {
        val live = roots.values.toList()
        if (live.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        // [T-b18-sandbox-sweep-cost] 每轮只扫一次 /proc（原先每个 root 一次），
        // 且只对「上一轮没 confine 过」的 pid 做 confine。
        val snapshot = snapshotProc()
        var owned = 0
        for (root in live) {
            if (!File("/proc/${root.pid}").exists()) {
                roots.remove(root.pid)
                continue
            }
            val current = snapshot.ownedBy(root.pid)
            val alive = current.filter { File("/proc/$it").exists() }.toSet()
            val set = root.sticky.observe(current, alive)
            owned += set.size
            applyConfinement(root, current, set, snapshot.starts)
            if (root.deadlineAt > 0L && now >= root.deadlineAt) {
                Log.w(TAG, "deadline pid=${root.pid} label=${root.label}")
                releasePid(root.pid, kill = true, reason = "deadline:${root.label}")
            }
        }
        if (GuestWorkloadPolicy.exceedsProcessCap(owned)) {
            stopUnprotected("process-cap owned=$owned")
            return
        }
        val ctx = appContext
        if (ctx != null && SandboxMemoryPressure.isCritical(ctx) && roots.isNotEmpty()) {
            stopUnprotected("memory-pressure")
        }
    }
}

/**
 * [T-b18-pid-reuse] confine 差集的纯逻辑：给定"上一轮记下的进程身份"、本轮 `/proc`
 * 快照里"属于本树（父子链 + 进程组）的 pid"、"sticky 记忆里的全部 pid"与
 * "pid → starttime"，算出这一轮该 confine 谁、以及该记下什么身份。
 *
 * 三条判据（见 `ConfinementLedgerTest`）：
 * 1. 同一个进程（pid 与 starttime 都没变，且它已经记号在案）不重复 confine；
 * 2. pid 被回收给本树的新子进程（starttime 变了，且它出现在本轮树里）必须重新
 *    confine —— 这正是旧实现（裸 pid 集合做差集）漏掉的那条；
 * 3. 只在 sticky 记忆里、身份却变了的 pid 不再当成我们的进程（既不 confine 也不
 *    记账）：pid 复用说明那个号已经属于别人，去改它的 cgroup / 优先级是误伤。
 * starttime 读不到（hidepid / 刚消失）的 pid：还在树里的按"每轮都 confine"保守
 * 处理且不记账，只在记忆里的跳过（无法证明它是我们的）。
 *
 * 抽成纯逻辑是为了让判据能被测试固定住：`SandboxWorkload` 本体要真机 Context。
 */
internal object ConfinementLedger {

    class Decision(
        /** 下一轮要沿用的身份表（只含本轮仍在记忆里、且身份可证的 pid）。 */
        val confined: Map<Int, Long>,
        /** 本轮需要执行 confine 的 pid。 */
        val toConfine: List<Int>,
    )

    fun decide(
        previous: Map<Int, Long>,
        current: Set<Int>,
        owned: Set<Int>,
        startTime: Map<Int, Long>,
    ): Decision {
        val next = HashMap<Int, Long>(owned.size)
        val fresh = ArrayList<Int>(4)
        for (pid in owned) {
            val start = startTime[pid]
            val inTree = pid in current
            if (start == null) {
                // 身份读不到：在树里就保守重做（下一轮再试），只在记忆里的不碰。
                if (inTree) fresh += pid
                continue
            }
            val sameProcess = previous[pid] == start
            if (inTree) {
                next[pid] = start
                if (!sameProcess) fresh += pid
            } else if (sameProcess) {
                next[pid] = start
            }
        }
        return Decision(next, fresh)
    }
}
