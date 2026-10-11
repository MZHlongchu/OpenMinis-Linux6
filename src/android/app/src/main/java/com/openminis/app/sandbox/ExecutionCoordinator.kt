package com.openminis.app.sandbox

import com.openminis.app.data.repository.MCPToolPolicy

import android.content.Context
import android.util.Log
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.notification.SandboxNotifyActions
import com.openminis.app.sandbox.SandboxResourceGate
import com.openminis.app.sandbox.kernel.BudgetClassifier
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages per-session persistent shell processes.
 *
 * Architecture:
 * - PRootKernel (rootfs + proot binary): global singleton, booted once
 * - PersistentShell: one per sessionId, owns its bind mounts and /bin/sh process
 * - Per-session Mutex: different sessions can run commands concurrently
 * - ConcurrentHashMap: thread-safe shell/mutex registry
 *
 * Concurrency guarantees:
 * - Same session: commands are serialized by the per-session Mutex
 * - Different sessions: run concurrently (each has its own Mutex)
 * - Shell registration: globalLock guards the shells[] decision/write only —
 *   the tree kill and the spawn run outside it, and a registered-but-spawning
 *   shell is reused, so no duplicate shells [T-b10-shell-create-off-lock]
 * - Shell death: detected on next command, shell is recreated with same bind mounts
 */
object ExecutionCoordinator {

    private const val TAG = "ExecutionCoordinator"
    private const val LANE_PREFIX = "subagent:"

    data class CommandResult(
        val output: String,
        val exitCode: Int,
        val durationMs: Long
    )

    private var appContextRef: Context? = null
    private val appContext: Context get() = checkNotNull(appContextRef) { "ExecutionCoordinator.init must be called first" }
    var envVarRepository: EnvVarRepository? = null

    /** Thread-safe per-session shell registry. */
    private val shells = ConcurrentHashMap<String, PersistentShell>()

    /** Thread-safe per-session mutex registry. */
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    // [T-shell-perf] 后台写 scope（脱离会话锁）。[T-last-cmd-order] limitedParallelism(1) 保写序：交错落盘会让 .minis-last-cmd.sh 停在旧命令。
    private val recordScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1),
    )

    /**
     * Per-session snapshot of the env-var keys injected on the previous
     * `applyEnvironment` call. Used to issue `unset` for keys the user has
     * since deleted from EnvVarRepository. Long-lived shells would otherwise
     * keep the stale value around indefinitely.
     */
    private val lastInjectedKeys = ConcurrentHashMap<String, Set<String>>()

    /**
     * Global lock used only for the shells[] decision when a session's shell
     * must be created or recreated, preventing duplicate shells when the same
     * session's first command arrives concurrently. The blocking parts of that
     * path (killing the stale shell, spawning the new one) deliberately run
     * outside it. [T-b10-shell-create-off-lock]
     */
    private val globalLock = Mutex()
    private val lastIdle = ConcurrentHashMap<String, Long>()
    private val evictionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var evictionJob: Job? = null

    @Synchronized
    private fun scheduleIdleReap() {
        if (evictionJob?.isActive == true) return
        evictionJob = evictionScope.launch {
            while (shells.isNotEmpty()) {
                delay(30_000)
                reapIdleShells()
            }
        }
    }

    private suspend fun reapIdleShells() {
        // Conservative process-wide guard. A background job in ANY chat pins
        // shells until it exits. Never terminate a service to meet a cache cap.
        if (!ShellIdleSafety.canReap()) return
        val now = android.os.SystemClock.elapsedRealtime()
        val prefs = com.openminis.app.data.ToolLimitPrefs
        val idle = lastIdle.entries.sortedBy { it.value }
        var excess = (idle.size - prefs.idleShellCount()).coerceAtLeast(0)
        for ((id, timestamp) in idle) {
            if (excess <= 0 && now - timestamp < prefs.idleShellMinutes() * 60_000L) continue
            val mutex = mutexes[id] ?: continue
            if (!mutex.tryLock()) continue
            try {
                if (lastIdle[id] != timestamp || !ShellIdleSafety.canReap()) continue
                // Keep the per-session mutex: queued callers already reference it.
                globalLock.withLock {
                    shells.remove(id)?.stop()
                    lastInjectedKeys.remove(id)
                    lastIdle.remove(id)
                }
                excess--
            } finally { mutex.unlock() }
        }
    }

    fun init(context: Context) {
        appContextRef = context.applicationContext
    }

    /**
     * Execute a command in the session's persistent shell.
     *
     * Flow:
     * 1. Get or create per-session Mutex (thread-safe via ConcurrentHashMap)
     * 2. Acquire per-session Mutex (serializes commands within same session)
     * 3. Get or create PersistentShell (protected by globalLock on creation)
     * 4. Execute command
     */
    suspend fun execute(
        sessionId: String,
        command: String,
        // Ignored. Armed timeout is BudgetClassifier.classify(command).wallMs.
        timeout: Long = 600_000L,
        lineCallback: ((String) -> Unit)? = null,
        resourceClass: SandboxResourceGate.ResourceClass = SandboxResourceGate.ResourceClass.AUTO,
    ): CommandResult {
        MCPToolPolicy.blockedMessage(appContext, command)?.let { msg ->
            lineCallback?.invoke(msg)
            return CommandResult(output = msg, exitCode = 1, durationMs = 0)
        }
        SandboxJobKeepAlive.onStart(appContext, sessionId, command)
        try {
        // [T-e4-mutex-atomic] 同会话的第一条并发命令必须拿到**同一把** Mutex，否则类头
        // 承诺的"同会话命令串行"不成立（takeover/superseded 窗口也只有这一个入口）。
        // computeIfAbsent 在 ConcurrentHashMap 内原子地完成"取值或发布"，两个调用者拿到
        // 的是同一个实例；旧写法 `getOrPut { Mutex() }` 是靠 stdlib 的 ConcurrentMap 重载
        // 才返回 putIfAbsent 的赢家，读代码看不出这一点，且与它上面的注释互相矛盾。
        val mutex = mutexes.computeIfAbsent(sessionId) { Mutex() }

        return mutex.withLock {
        lastIdle.remove(sessionId)
        try {
        // [T-yoyo-root-deletion] Under YOYO the gate has already shown a
        // mustPrompt confirmation for a root deletion before the command reaches
        // this executor, so refusing it again here made that approval a dead
        // end: the user tapped through and the command still died with exit 126.
        // The guest rootfs is also rebuildable (`minis-dev-setup`), unlike a host
        // partition. Block-device writes stay refused in every mode.
        val yoyo = com.openminis.app.security.SecurityGateHolder
            .activeSessionMode(sessionId)
            .isYoyo()
        val refused = GuestWorkloadPolicy.hostRefusal(command, allowRootDeletion = yoyo)
            ?: DiskPressure.refusal(appContext, command)
            ?: run {
                // [T-memory-poison-guard] 工具路径之外的同一配额：/var/minis/memory
                // bind 的宿主目录就是本会话的 memory 目录，直接 shell 写语法在
                // 当日配额耗尽后一并拒绝（详见 GuestWorkloadPolicy KDoc）。
                val memoryHost = com.openminis.app.sandbox.SessionWorkspace.memoryDir(
                    appContext.filesDir,
                    ownerSessionId(sessionId),
                )
                GuestWorkloadPolicy.memoryQuotaRefusal(
                    command,
                    com.openminis.app.data.repository.MemoryRepository.dailyEntryCount(memoryHost),
                    com.openminis.app.data.repository.MemoryRepository.MAX_DAILY_ENTRIES,
                )
            }
        if (refused != null) {
            Log.w(TAG, "[$sessionId] refused before start: $refused")
            lineCallback?.invoke(refused)
            return@withLock CommandResult(output = refused, exitCode = 126, durationMs = 0)
        }
        SandboxResourceGate.withCommandLock(
            command,
            resourceClass = resourceClass,
            pressure = { SandboxMemoryPressure.reason(appContext) },
            limits = { SandboxMemoryPressure.executionLimits(appContext) },
            onWaiting = { lineCallback?.invoke(it) },
        ) {
            val budget = BudgetClassifier.classify(command, resourceClass)
            val armed = budget.wallMs
            if (timeout != armed) {
                Log.w(
                    TAG,
                    "[$sessionId] caller timeout ${timeout}ms ignored; armed ${armed}ms class=${budget.workClass}",
                )
            }
            val startTime = System.currentTimeMillis()

            // Auto-boot PRoot if not already booted
            if (!PRootKernel.isBooted) {
                Log.i(TAG, "[$sessionId] Auto-booting PRootKernel")
                PRootKernel.boot(appContext)
            }

            // [diag] trace the sessionId that shell_execute is dispatched with —
            // suspected source of the Chinese-emoji filename vanishing bug
            Log.w(TAG, "[diag] execute sessionId=$sessionId cmd=${command.take(120).replace('\n', ' ')}")

            // Get or create shell — protected by globalLock to avoid duplicate creation
            val shell = getOrCreateShell(sessionId)
            recordLastCommand(sessionId, command)

            // Inject user-defined environment variables as a *full snapshot*
            // (T124a). Pass the previously-injected key set so applyEnvironment
            // can `unset` anything the user has since removed from settings;
            // otherwise the long-lived shell would keep stale values.
            val envVars = envVarRepository?.allAsDict() ?: emptyMap()
            val previousKeys = lastInjectedKeys[sessionId] ?: emptySet()
            if (envVars.isNotEmpty() || previousKeys.isNotEmpty()) {
                shell.applyEnvironment(envVars, previousKeys = previousKeys)
                lastInjectedKeys[sessionId] = envVars.keys.toSet()
            }

            val (rawOutput, exitCode) = shell.executeCommand(
                command = command,
                timeout = armed,
                lineCallback = lineCallback,
                // [T-resource-class-honoured] Pass the budget this function
                // already computed. Re-deriving it downstream from the command
                // text alone silently dropped `resourceClass`.
                callerBudget = budget,
            )

            val durationMs = System.currentTimeMillis() - startTime
            val sanitized = TerminalSanitizer.sanitize(rawOutput)
            val (stripped, loggedExit) = TerminalSanitizer.dropOffloadTrace(sanitized)
            var effectiveExit = exitCode
            if (effectiveExit == 0 && loggedExit != null && loggedExit != 0) effectiveExit = loggedExit
            if (effectiveExit == 0 && stripped.contains("handler_timeout")) effectiveExit = 124
            val truncated = TerminalSanitizer.truncateIfNeeded(stripped)
            val output = if (effectiveExit != 0 && effectiveExit != 124) {
                "$truncated\n(exit code: $effectiveExit)"
            } else {
                truncated
            }

            CommandResult(
                output = MCPToolPolicy.filterToolsOutput(appContext, command, output),
                exitCode = effectiveExit,
                durationMs = durationMs,
            )
        }
        } finally {
            if (shells.containsKey(sessionId)) lastIdle[sessionId] = android.os.SystemClock.elapsedRealtime()
            scheduleIdleReap()
        }
        }
        } finally {
            SandboxJobKeepAlive.onEnd(appContext, sessionId)
        }
    }


    private fun recordLastCommand(sessionId: String, command: String) {
        if (sessionId.startsWith("__") || sessionId == "self-build") return
        if (!SandboxNotifyActions.shouldRecordLastCommand(command)) return
        // [T-mcp-session-bind] LAST_CMD_GUEST_PATH 在 /var/minis/workspace 下，是每会话
        // 路径：走全局 resolveHostPath 会把它写进"最近建壳的那个会话"的 workspace，
        // 于是上一轮命令提示出现在别的聊天里。按本会话解析。
        val host = PRootKernel.resolveSessionHostPath(
            sessionId,
            SandboxNotifyActions.LAST_CMD_GUEST_PATH,
            appContext,
        ) ?: return
        // [T-shell-perf] 每条命令的宿主文件写（mkdirs + writeText + setExecutable）
        // 原本同步跑在会话互斥锁内——移到后台 scope，命令延迟不再包含这次 IO。
        recordScope.launch {
            try {
                host.parentFile?.mkdirs()
                host.writeText("#!/bin/sh\n# recorded by ExecutionCoordinator\n$command\n")
                host.setExecutable(true, false)
            } catch (t: Throwable) {
                Log.w(TAG, "recordLastCommand failed: ${t.message}")
            }
        }
    }

    /**
     * Get the existing shell for this session, or create a new one.
     *
     * [T-b10-superseded-null] [startOrReuseShell] 的单次尝试可能在锁外窗口里被摘掉
     * （`stopCurrentCommand` / `sessionDidTerminate` 都不持 globalLock），此时它返回
     * null：那次 spawn 出来的实例已被 `stop()`，**绝不能**交回调用方 ——
     * `PersistentShell.executeCommand` 会先 `ensureStarted()`，把这个已停的壳重新
     * spawn 成一个不在 `shells[]` 里的 proot（孤儿进程：没人能停它，也没人会复用它），
     * 而同一会话的下一条命令又会建第二个壳，两个壳各有独立的 cd/env/后台作业。
     * 所以这里重试整条路径，连续 [ShellSupersedePolicy.MAX_START_ATTEMPTS] 次都被
     * 摘掉才判失败（fail-closed，调用方拿到明确错误而不是一个没人管的壳）。
     */
    private suspend fun getOrCreateShell(sessionId: String): PersistentShell {
        var attempt = 0
        while (true) {
            attempt++
            val shell = startOrReuseShell(sessionId)
            if (shell != null) return shell
            if (attempt >= ShellSupersedePolicy.MAX_START_ATTEMPTS) {
                throw ShellSupersedePolicy.exhausted(sessionId, attempt)
            }
            Log.w(TAG, "[$sessionId] shell registry emptied while starting; retrying (attempt $attempt)")
        }
    }

    /**
     * 单次尝试：复用注册表里的活壳，或注册并 spawn 一个新实例。
     * Uses globalLock to prevent two coroutines from simultaneously creating
     * a shell for the same session (e.g. if the old shell just died).
     *
     * [T-b10-shell-create-off-lock] The lock now covers only the registry
     * decision + the registration of the new instance; killing the stale shell
     * and spawning the new one happen outside it, because that work is
     * blocking and used to serialize every session that needed a (re)build —
     * see the slow path.
     *
     * 返回 null = 这一次拿到手的实例在锁外窗口里被摘掉（注册表已空，或接管者自己也
     * 被摘走，见 [T-d10-takeover-recheck]），调用方 [getOrCreateShell] 负责重试；
     * null 不代表"可以继续用这个壳"。
     */
    private suspend fun startOrReuseShell(sessionId: String): PersistentShell? {
        // Fast path: existing alive shell
        val existing = shells[sessionId]
        if (existing != null && existing.isAlive && !shellMountsStale(existing, sessionId)) {
            Log.w(TAG, "[diag] reuse existing shell for sessionId=$sessionId attachmentsMount=${existing.debugBindMount("/var/minis/attachments")}")
            return existing
        }

        // Slow path: need to create (or recreate after crash).
        //
        // [T-b10-shell-create-off-lock] This block used to also keep
        // recheck.stop() (SandboxWorkload.release → killOwned's three full
        // /proc sweeps → destroyForcibly → waitFor(500ms)) and
        // shell.ensureStarted() (startProcess sleeps 200ms, +200ms again on the
        // seccomp self-heal) behind globalLock. Any session that needed a
        // (re)build therefore queued behind another session's tree kill or
        // spawn, which is exactly the delay a user feels on the first command
        // after switching session or editing mounts. The fast path never took
        // the lock, so only those rebuilds were affected.
        //
        // Mutual exclusion is preserved without holding the lock across the
        // blocking work: the instance is registered while the lock is held, and
        // a caller that finds a registered-but-still-spawning instance reuses
        // it (ensureStarted is idempotent) instead of building a second shell.
        val plan = globalLock.withLock {
            // Double-check after acquiring lock
            val recheck = shells[sessionId]
            if (recheck != null && recheck.isAlive && !shellMountsStale(recheck, sessionId)) {
                Log.w(TAG, "[diag] reuse existing shell (post-lock) for sessionId=$sessionId attachmentsMount=${recheck.debugBindMount("/var/minis/attachments")}")
                return@withLock recheck to null
            }
            if (recheck != null && recheck.isStartingUp && !shellMountsStale(recheck, sessionId)) {
                Log.w(TAG, "[diag] reuse spawning shell for sessionId=$sessionId")
                return@withLock recheck to null
            }

            // Shell is dead or missing — register a fresh instance now; the old
            // one is killed outside the lock (stop() is idempotent).
            // buildSessionBindMounts publishes into PRootKernel.bindMounts, so
            // it stays inside the lock; constructing the shell is bookkeeping.
            val bindMounts = buildSessionBindMounts(sessionId)
            val fresh = PersistentShell(appContext, sessionId, bindMounts)
            shells[sessionId] = fresh
            Log.i(TAG, "[$sessionId] Shell created with ${bindMounts.size} bind mounts")
            Log.w(TAG, "[diag] new shell created sessionId=$sessionId attachmentsMount=${bindMounts["/var/minis/attachments"]}")
            fresh to recheck
        }
        val (shell, stale) = plan
        if (stale != null) {
            Log.w(TAG, "[$sessionId] Shell ${if (stale.isAlive) "mounts stale" else "died"}, recreating")
            // 先杀旧壳再启动新壳：release(kill=true) 的扫描若在新 shell 起来之后跑，
            // 可能把刚 spawn 的进程按旧归属杀掉。
            stale.stop()
        }
        shell.ensureStarted()
        // [T-b10-shell-create-off-lock] 这段锁外窗口里，stopCurrentCommand（它不看
        // globalLock）或一次挂载变更可能已经让另一个实例接管 registry：收掉刚起来的
        // 进程，别留下没人引用的 proot；有接管者就交给它（ensureStarted 幂等）。
        val current = shells[sessionId]
        when (ShellSupersedePolicy.verdict(current, shell)) {
            ShellSupersedePolicy.Verdict.REGISTERED -> return shell
            ShellSupersedePolicy.Verdict.SUPERSEDED -> {
                Log.w(TAG, "[$sessionId] shell superseded while starting; discarding the new instance")
                shell.stop()
                // verdict 已判定 current 非空；这里再读一次，防止判定与使用之间被摘掉。
                val takeover = current ?: return null
                takeover.ensureStarted()
                // [T-d10-takeover-recheck] 复核：takeover 是锁外窗口里的接管者，它自己也会被
                // stopCurrentCommand / sessionDidTerminate（都不持 globalLock）摘走并 stop()。
                // 那个窗口就在上面这一句 ensureStarted() 前后：若 takeover 已被摘走，这次
                // ensureStarted() 会把它重新 spawn 成一个**不在 shells[] 里的 proot**，交回
                // 调用方后 PersistentShell.executeCommand 还会再 ensureStarted 一次，而注册表
                // 里没有它 —— 与原回归同型（孤儿 proot + 同会话双壳）。所以返回前再读一次注册表：
                // 不是 takeover 就收掉它并返回 null，交给 [getOrCreateShell] 重试整条路径。
                val latest = shells[sessionId]
                if (ShellSupersedePolicy.verdict(latest, takeover) != ShellSupersedePolicy.Verdict.REGISTERED) {
                    Log.w(TAG, "[$sessionId] takeover instance left the registry while starting; discarding it")
                    takeover.stop()
                    return null
                }
                return takeover
            }
            // [T-b10-superseded-null] 注册表空了（条目在锁外被摘走）：刚起来的实例
            // 已经 stop()，交回调用方就等于把一个"已停的壳"重新 spawn 成孤儿 proot。
            // 返回 null 让 [getOrCreateShell] 重试整条路径。
            ShellSupersedePolicy.Verdict.REGISTRY_EMPTY -> {
                Log.w(TAG, "[$sessionId] shell registry emptied while starting; discarding the new instance")
                shell.stop()
                return null
            }
        }
    }

    /**
     * The bind set (`linuxPath -> hostPath`) this session's shell would use:
     * per-session subdirs, the global dirs, external mounts and shared storage.
     *
     * [T-mcp-session-bind] `publish = false` returns exactly the same map but does
     * NOT write the per-session subdirs into the process-wide
     * [PRootKernel.bindMounts] table. That table is last-writer-wins, so a reader
     * gets whichever session built a shell last; any caller that spawns its own
     * guest process *for a known session* — the MCP stdio bridge — must take its
     * `-b` argv from here instead, or its `/var/minis/{workspace,attachments,
     * offloads,browser,memory}` point at another chat. Host dirs are still created
     * either way: proot needs both sides of `-b host:linux` to exist.
     *
     * The per-session subdirs keep being published (publish = true) for the readers
     * that still resolve through the shared table and have no session at hand:
     * `TerminalSession.buildTermuxArgs`, `ShellExecutor`'s one-shot proot and
     * `WebAppPathResolver.inferScope`. Session-scoped callers must NOT consume it —
     * MCP spawn uses this accessor with `publish = false`, file tools use
     * [PRootKernel.resolveSessionHostPath].
     */
    fun sessionBindMounts(sessionId: String, publish: Boolean = true): Map<String, String> =
        buildSessionBindMounts(sessionId, publish = publish, mkdirs = true)

    internal fun hasLiveShell(sessionId: String): Boolean = shells[sessionId]?.isAlive == true

    private fun shellMountsStale(shell: PersistentShell, sessionId: String): Boolean {
        // Compare the whole argv, not only /sdcard. A user mount named sdcard,
        // a new /var/minis/mounts entry, or a filed-session hostDir change is
        // otherwise stuck on the shell that already started.
        return shell.bindSnapshot() != buildSessionBindMounts(sessionId, publish = false)
    }

    /**
     * Build bind mounts for a session:
     * - Session-level: workspace, attachments, offloads, browser → per-session dirs
     * - Global: memory, skills, shared → shared dirs across all sessions
     *
     * [T-mcp-session-bind] `publish` writes the session subdirs into the shared
     * [PRootKernel.bindMounts] table AND creates the host dirs; `mkdirs = false`
     * (used by the mount-staleness check, which runs on every command) keeps the
     * map computation free of IO.
     */
    private fun buildSessionBindMounts(
        sessionId: String,
        publish: Boolean = true,
        mkdirs: Boolean = publish,
    ): Map<String, String> {
        val filesDir = appContext.filesDir
        val mounts = linkedMapOf<String, String>()

        // [diag] previous attachments mount target — exposes cross-session
        // overwrite of the global bindMounts map (the suspected cause of
        // the "file disappears after first download" bug)
        val prevAttachments = PRootKernel.bindMounts["/var/minis/attachments"]

        // Session / project directories. Sub-agent lanes share the parent
        // session tree so files written by a teammate stay visible here.
        // Filed sessions mount attachments/workspace/offloads/browser from the
        // project folder; memory stays per-session.
        val owner = ownerSessionId(sessionId)
        SessionWorkspace.SESSION_SUBDIRS.forEach { subdir ->
            val hostDir = SessionWorkspace.hostDir(filesDir, owner, subdir).also {
                if (mkdirs) it.mkdirs()
            }
            val linuxPath = "/var/minis/$subdir"
            mounts[linuxPath] = hostDir.absolutePath
            if (publish) PRootKernel.addBindMount(linuxPath, hostDir.absolutePath)
        }

        Log.w(TAG, "[diag] buildSessionBindMounts sessionId=$sessionId " +
            "attachments: prev=$prevAttachments new=${mounts["/var/minis/attachments"]}")

        // Global shared directories.
        // [T-android-mcp-bind-mount] mcp-servers MUST be here, not only in
        // PRootKernel.registerGlobalBindMounts: PersistentShell builds PRoot's
        // `-b` argv from THIS map, so a subdir missing here is invisible to the
        // shell that runs minis-mcp-cli — /var/minis/mcp-servers/servers.json
        // then resolves to the empty rootfs placeholder and `minis-mcp-cli list`
        // returns {"servers": [], "count": 0} even though the UI wrote the
        // server (the UI / debug.ls read via resolveHostPath, a separate map,
        // which is why they disagreed). Same trap as the external-mounts note
        // below.
        val globalBase = File(filesDir, SessionWorkspace.GLOBAL_DIR)
        SessionWorkspace.GLOBAL_BIND_SUBDIRS.forEach { subdir ->
            val hostDir = File(globalBase, subdir).also {
                if (mkdirs) it.mkdirs()
            }
            val linuxPath = "/var/minis/$subdir"
            mounts[linuxPath] = hostDir.absolutePath
            if (publish) PRootKernel.addBindMount(linuxPath, hostDir.absolutePath)
        }

        // T277: user-mounted external folders (SAF-picked trees). PersistentShell
        // uses this map verbatim as PRoot's `-b` argv, so any mount missing here
        // is invisible to the shell — `ls /var/minis/mounts/<name>/` then shows
        // only the empty rootfs placeholder. PRootKernel.bindMounts is kept in
        // sync separately by applyMountedFoldersSnapshot for the resolveHostPath
        // path (debug.ls, file_read, …) but does NOT feed the live PRoot argv.
        // Skip entries whose SAF tree URI didn't decode to a POSIX path
        // (cloud providers, unmounted removable storage).
        PRootKernel.mountedFoldersStore?.entries?.value?.forEach { entry ->
            val host = entry.resolvedHostPath ?: return@forEach
            val linuxPath = "/var/minis/mounts/${entry.name}"
            mounts[linuxPath] = host
        }

        // Shared storage is not a MountedFoldersStore entry. Without this,
        // shell_execute and the interactive PTY never see /sdcard even when
        // All Files Access is granted (PRootKernel.bindMounts is a different map).
        for ((linuxPath, host) in PRootKernel.shellSharedStorageBinds(appContext, publish)) {
            if (linuxPath == SharedStorageBindPlan.MOUNTS_SDCARD && linuxPath in mounts) continue
            mounts[linuxPath] = host
        }

        return mounts
    }

    /**
     * Called when a session is closed. Stops and removes the shell.
     */
    fun sessionDidTerminate(sessionId: String) {
        terminateOne(sessionId)
        if (!isLaneSession(sessionId)) {
            val prefix = lanePrefix(sessionId)
            shells.keys.filter { it.startsWith(prefix) }.forEach { terminateOne(it) }
            // [T-mcp-session-bind] MCP stdio client 是按会话作用域派生的常驻 guest
            // 进程（缓存键见 McpNativeBridge.clientFor），会话真正结束时要一并收掉，
            // 否则每关一个聊天就漏一个进程。lane 结束不走这里：lane 与父会话同作用域，
            // 收掉会打断父会话正在进行的调用。
            runCatching { com.openminis.app.mcp.client.McpNativeBridge.closeSession(sessionId) }
                .onFailure { Log.w(TAG, "[$sessionId] closing MCP stdio clients failed: ${it.message}") }
        }
    }

    private fun terminateOne(sessionId: String) {
        val shell = shells.remove(sessionId)
        lastIdle.remove(sessionId)
        // T124a: drop the snapshot too — a future shell for the same id
        // restarts from a clean baseline, so the next applyEnvironment
        // shouldn't try to `unset` keys that don't exist in the new shell.
        lastInjectedKeys.remove(sessionId)
        shell?.stop()
        if (shell != null) Log.i(TAG, "[$sessionId] Shell terminated")
    }

    /**
     * Stop the shell for a specific session (e.g. user tapped cancel).
     * The shell process is killed; next command will recreate it.
     */
    fun stopCurrentCommand(sessionId: String? = null) {
        if (sessionId != null) {
            stopOne(sessionId)
            if (!isLaneSession(sessionId)) {
                val prefix = lanePrefix(sessionId)
                shells.keys.filter { it.startsWith(prefix) }.forEach { stopOne(it) }
            }
        } else {
            // Stop all sessions (legacy/fallback)
            // [T-e4-stop-all-detach-first] 先摘注册表再逐个 stop，与 stopOne/terminateOne
            // 同序。原先"先 stop、后 clear"：两次操作之间的窗口里 shells[] 仍引用已停实例，
            // getOrCreateShell 的快路径/启动后复核会把它交回调用方，而调用方
            // PersistentShell.executeCommand 的 ensureStarted 又把这个已停的壳重新 spawn
            // 成不在注册表里的 proot（孤儿进程 + 同会话双壳）。
            val doomed = shells.values.toList()
            shells.clear()
            lastInjectedKeys.clear()
            for (shell in doomed) shell.stop()
            ShellExecutor.destroyCurrent()
        }
    }

    /** Legacy overload for callers without sessionId. */
    fun stopCurrentCommand() = stopCurrentCommand(sessionId = null)

    /**
     * Propagate a system-timezone change to every live shell.
     *
     * - Updates [PRootKernel.customEnvironment]["TZ"] so future shells inherit
     *   the new value at spawn time.
     * - Exports the new TZ into every already-running [PersistentShell] via
     *   `export TZ=...` on stdin.
     * - Asks [TerminalSession] to do the same for every live interactive PTY.
     * - Rewrites guest `/etc/localtime`. Processes that ignore `TZ` still read it.
     *
     * Safe to call before PRoot has booted — it's a no-op in that case.
     */
    suspend fun broadcastTimezoneChange() {
        if (!PRootKernel.isBooted) return
        val tz = PRootKernel.updateTimezone()
        runCatching { PRootKernel.syncHostTimezoneFiles() }
            .onFailure { Log.w(TAG, "syncHostTimezoneFiles failed: ${it.message}") }
        val tzMap = mapOf("TZ" to tz)
        for ((_, shell) in shells) {
            if (shell.isAlive) shell.applyEnvironment(tzMap)
        }
        TerminalSession.broadcastTimezone(tz)
    }

    /**
     * Propagate a system-proxy change to every live shell. Exports all six
     * proxy keys as a block — empty strings when no proxy is configured, so
     * a disable transition clears the old values in-place without needing
     * a separate `unset`.
     *
     * Safe to call before PRoot has booted — it's a no-op in that case.
     */
    suspend fun broadcastProxyChange() {
        if (!PRootKernel.isBooted) return
        val env = PRootKernel.updateProxy(appContext)
        for ((_, shell) in shells) {
            if (shell.isAlive) shell.applyEnvironment(env)
        }
        TerminalSession.broadcastProxy(env)
    }

    private fun stopOne(sessionId: String) {
        val shell = shells.remove(sessionId)
        lastInjectedKeys.remove(sessionId)
        shell?.stop()
        Log.i(TAG, "[$sessionId] Shell stopped by user")
    }

    fun ownerSessionId(sessionId: String): String {
        if (!isLaneSession(sessionId)) return sessionId
        val rest = sessionId.substring(LANE_PREFIX.length)
        val cut = rest.lastIndexOf(':')
        return if (cut > 0) rest.substring(0, cut) else sessionId
    }

    fun isLaneSession(sessionId: String): Boolean = sessionId.startsWith(LANE_PREFIX)

    private fun lanePrefix(ownerSessionId: String): String = "$LANE_PREFIX$ownerSessionId:"
}

/**
 * [T-b10-superseded-null] `ExecutionCoordinator.startOrReuseShell` 在锁外 spawn 结束后，
 * 注册表可能已经不再引用刚起来的那个实例。三种收尾互斥，判据只看"注册表当前引用了谁"：
 *
 * - [Verdict.REGISTERED]：注册表就是我们刚 spawn 的实例 —— 正常返回。
 * - [Verdict.SUPERSEDED]：别的实例接管了注册表 —— 收掉自己的进程，把接管者
 *   `ensureStarted()`（幂等）后返回。接管者在这段锁外窗口里同样可能被摘走
 *   （见 [T-d10-takeover-recheck]），所以 `ensureStarted()` 之后必须再复核一次注册表：
 *   不是它就把这个已被 stop 的实例收掉并返回 null 重试，**不能**把"已停的壳"交出去。
 * - [Verdict.REGISTRY_EMPTY]：注册表已空（`stopCurrentCommand` / `sessionDidTerminate`
 *   摘走了条目）—— **不能**把刚停掉的实例交回调用方：`PersistentShell.executeCommand`
 *   会 `ensureStarted()` 把它重新 spawn 成一个不在 `shells[]` 里的 proot（孤儿进程 +
 *   同会话双壳），这正是要修的回归。正确动作是重试整条路径，连续
 *   [MAX_START_ATTEMPTS] 次仍被摘掉才判失败。
 *
 * 抽成纯函数只有一个理由：这条回归的判据必须能被固定住（见 `ShellSupersedePolicyTest`），
 * 而调用它的 `startOrReuseShell` 需要真机 Context 与真实 proot 才能跑。
 */
internal object ShellSupersedePolicy {

    /** 连续被摘掉多少次就判失败。每次重试都要完整 spawn 一次，远慢于任何争用窗口。 */
    const val MAX_START_ATTEMPTS = 3

    enum class Verdict { REGISTERED, SUPERSEDED, REGISTRY_EMPTY }

    /** 按引用比较：注册表里还是不是我们刚 spawn 的那个实例。 */
    fun <T : Any> verdict(current: T?, mine: T): Verdict = when {
        current === mine -> Verdict.REGISTERED
        current != null -> Verdict.SUPERSEDED
        else -> Verdict.REGISTRY_EMPTY
    }

    fun exhausted(sessionId: String, attempts: Int): IllegalStateException = IllegalStateException(
        "session $sessionId: shell kept being removed from the registry while starting " +
            "($attempts attempts) — refusing to return a stopped shell",
    )
}
