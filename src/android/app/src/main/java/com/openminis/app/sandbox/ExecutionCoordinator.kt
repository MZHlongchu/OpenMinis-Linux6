package com.openminis.app.sandbox

import com.openminis.app.data.repository.MCPToolPolicy

import android.content.Context
import android.util.Log
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.notification.SandboxNotifyActions
import com.openminis.app.sandbox.SandboxResourceGate
import com.openminis.app.sandbox.kernel.BudgetClassifier
import com.openminis.app.sandbox.kernel.ProcessBudget
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
 * - PersistentShell: a POOL per session (`"<sessionId>#<slot>"`, up to
 *   [MAX_SHELLS_PER_SESSION]), each owning its own bind mounts and /bin/sh
 *   process. [T-android-concurrent-shell] The pool replaced the single
 *   session-keyed shell so N commands of one session can run at once; the
 *   serialisation that remains is PER SHELL, because a PersistentShell writes
 *   one long-lived /bin/sh's stdin and reads its stdout through a single
 *   `pendingCallback` slot.
 * - Per-slot Mutex: different sessions — and different slots of one session —
 *   can run commands concurrently
 * - ConcurrentHashMap: thread-safe shell/mutex registry
 *
 * Concurrency guarantees:
 * - Same shell slot: commands are serialized by that slot's Mutex
 * - Different slots/sessions: run concurrently
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
        val durationMs: Long,
        /**
         * [T-android-shell-process-budget] A <system-reminder> for the model
         * when the command waited in [GlobalShellThrottle]'s queue; null when
         * it started at once. Kept apart from [output] so the caller can put
         * it after everything else it appends (exit status, redaction).
         */
        val queueNote: String? = null,
    )

    private var appContextRef: Context? = null
    private val appContext: Context get() = checkNotNull(appContextRef) { "ExecutionCoordinator.init must be called first" }
    /**
     * [T-android-shell-fresh-process] Which mechanism runs commands.
     *
     * Defaults to the warm shell so step 1-3 change nothing for users; step 4
     * flips it once the comparison harness shows the two paths agree. Kept
     * mutable so a debug build can switch at runtime without a reinstall,
     * which is how the step-3 comparison is driven.
     */
    @Volatile
    internal var executionStrategy: ShellExecutionStrategy = ShellExecutionStrategy.DEFAULT

    /**
     * [T-android-shell-fresh-process] One PRoot process per command.
     *
     * No lease, no pool slot, no mutex: a command that owns its own process
     * and pipes cannot be interfered with by another, so the serialisation the
     * pooled path needs does not apply. That also means commands in one
     * session now run genuinely concurrently rather than queueing — the
     * behaviour the tool contract already promises ("Each invocation spawns a
     * fresh process — there is no shared terminal session").
     */
    private suspend fun executeFresh(
        sessionId: String,
        command: String,
        timeout: Long,
        lineCallback: ((String) -> Unit)?,
        fsSessionId: String?,
    ): CommandResult {
        val startTime = System.currentTimeMillis()
        val bindMounts = buildSessionBindMounts(fsSessionId ?: sessionId)
        val envVars = envVarRepository?.allAsDict() ?: emptyMap()

        suspend fun attempt(noSeccomp: Boolean): Pair<String, Int> {
            val shell = FreshProcessShell(
                context = appContext,
                sessionId = sessionId,
                sessionBindMounts = bindMounts,
                useNoSeccomp = noSeccomp,
            )
            // [T-android-shell-fresh-process] Step 5. Register before running so
            // stopCurrentCommand() can reach this command. A fresh shell is owned
            // by this call frame, unlike a pooled PersistentShell, so without the
            // registry the Stop button would have nothing to kill and a running
            // command would ignore it.
            // [T-android-fresh-shells-concurrent] The inner set is a concurrent
            // key set: it is mutated here and in the finally below under the
            // map's bin lock, but iterated by stopFreshShells() from the Stop /
            // terminate thread WITHOUT that lock, so a plain LinkedHashSet
            // could throw ConcurrentModificationException mid-Stop.
            freshShells.compute(sessionId) { _, existing ->
                (existing ?: newFreshShellSet()).also { it.add(shell) }
            }
            return try {
                shell.execute(
                    command = command,
                    timeout = timeout,
                    envVars = envVars,
                    lineCallback = lineCallback,
                )
            } finally {
                freshShells.computeIfPresent(sessionId) { _, set ->
                    set.remove(shell)
                    if (set.isEmpty()) null else set
                }
            }
        }

        var (rawOutput, exitCode) = attempt(noSeccomp = false)
        // [T-android-fresh-seccomp-selfheal / GH#186] The warm path
        // (PersistentShell), ShellExecutor and TerminalSession all retry once
        // with PROOT_NO_SECCOMP=1 when the child dies on an early fatal signal
        // with no output. The fresh path became the default without that
        // self-heal, so on a GH#186 device every shell_execute died with
        // 135/139. Same narrow gate as the others (SeccompFallbackPolicy):
        // a normal failure, a timeout (124) or a user Stop (130) never
        // qualifies, and it fires at most once per command.
        val firstDurationMs = System.currentTimeMillis() - startTime
        if (SeccompFallbackPolicy.shouldRetryWithoutSeccomp(
                exitCode = exitCode,
                durationMs = firstDurationMs,
                producedOutput = rawOutput.isNotEmpty(),
                alreadyRetried = false,
            )
        ) {
            com.openminis.app.logging.AppLogger.warning(
                TAG,
                SeccompFallbackPolicy.retryLogLine(exitCode, firstDurationMs, "fresh shell command"),
            )
            // No output came out of the first attempt (a precondition of the
            // retry), so reusing lineCallback cannot duplicate anything.
            val retried = attempt(noSeccomp = true)
            rawOutput = retried.first
            exitCode = retried.second
        }
        val durationMs = System.currentTimeMillis() - startTime
        val sanitized = TerminalSanitizer.sanitize(rawOutput)
        val truncated = TerminalSanitizer.truncateIfNeeded(sanitized)
        val output = if (exitCode != 0 && exitCode != 124) {
            ShellExitCode.ensureSuffix(truncated, exitCode)
        } else {
            truncated
        }
        return CommandResult(output = output, exitCode = exitCode, durationMs = durationMs)
    }

    var envVarRepository: EnvVarRepository? = null

    /** Thread-safe per-session shell registry. */
    private val shells = ConcurrentHashMap<String, PersistentShell>()

    /**
     * [T-android-shell-fresh-process] Live fresh-process commands per session.
     *
     * A set rather than a single entry: a session runs several commands
     * concurrently (the same reason the warm path holds a pool), and Stop must
     * mean "stop everything this conversation is running", not just the most
     * recent one. Entries are removed in a finally block, so a crashing
     * command cannot leak one.
     */
    private val freshShells = ConcurrentHashMap<String, MutableSet<FreshProcessShell>>()

    /** [T-android-fresh-shells-concurrent] Thread-safe per-session set for [freshShells]. */
    private fun newFreshShellSet(): MutableSet<FreshProcessShell> = ConcurrentHashMap.newKeySet()

    /** Thread-safe per-session mutex registry. */
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    // [T-shell-perf] 后台写 scope（脱离会话锁）。[T-last-cmd-order] limitedParallelism(1) 保写序：交错落盘会让 .minis-last-cmd.sh 停在旧命令。
    private val recordScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1),
    )

    /**
     * [T-android-a11y-helper-mount] dispatch session id -> the session whose
     * `/var/minis` directories its shell binds (fsSessionId), recorded only
     * when they differ (a helper / sub agent sharing its parent's workspace,
     * T-p2-shared-workspace). Dropped in [sessionDidTerminate].
     */
    private val mountedSessionIds = ConcurrentHashMap<String, String>()

    /**
     * [T-android-a11y-helper-mount] The session id whose per-session dirs are
     * mounted at `/var/minis/...` for a command dispatched as [sessionId].
     *
     * The shell exports `MINIS_CHAT_SESSION_ID=<dispatch id>`, but for a
     * helper the bind mounts come from `fsSessionId ?: sessionId` (the
     * PARENT). A native offload that resolves `/var/minis/attachments` with
     * the env id therefore writes into the helper's own directory, which
     * neither the helper's shell nor read_image (called with the parent id)
     * can see. Offloads map the env id through here first — the same
     * `fsSessionId ?: sessionId` rule [executeFresh] mounts with.
     */
    fun mountedSessionIdFor(sessionId: String): String = mountedSessionIds[sessionId] ?: sessionId

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
     * Execute a command in the session's shell.
     *
     * Flow:
     * 1. Refuse what the guest must not run (workload / disk / memory quota)
     * 2. Admit it through [SandboxResourceGate] (heavy-command budget + apt lock)
     * 3. Arm the wall clock from [BudgetClassifier] — the caller's `timeout` is
     *    logged and ignored, see the parameter comment
     * 4. Boot PRoot if needed, then route by [executionStrategy] through
     *    [GlobalShellThrottle]: a pooled warm shell ([executeWarm]) or one
     *    process per command ([executeFresh])
     */
    suspend fun execute(
        sessionId: String,
        command: String,
        // Ignored. Armed timeout is BudgetClassifier.classify(command).wallMs.
        timeout: Long = 600_000L,
        lineCallback: ((String) -> Unit)? = null,
        resourceClass: SandboxResourceGate.ResourceClass = SandboxResourceGate.ResourceClass.AUTO,
        /**
         * [T-p2-shared-workspace] Session whose `/var/minis` dirs the shell
         * binds (a helper's PARENT). The shell process itself stays keyed by
         * [sessionId], so stop / cleanup never reach the parent's shell.
         */
        fsSessionId: String? = null,
    ): CommandResult {
        MCPToolPolicy.blockedMessage(appContext, command)?.let { msg ->
            lineCallback?.invoke(msg)
            return CommandResult(output = msg, exitCode = 1, durationMs = 0)
        }
        SandboxJobKeepAlive.onStart(appContext, sessionId, command)
        // [T-u12-merge] 上游 1.14 的结果分支在函数体末尾直接 return；并进我们的
        // try/finally（SandboxJobKeepAlive）后不能再写裸 return —— 它位于
        // SandboxResourceGate.withCommandLock 的 lambda 里（return 被禁止）。
        // withCommandLock 是 `<T> … : T`，lambda 的值会传出来，所以改成
        // 「try 作为函数返回值」的形式，语义与上游等价。
        return try {
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
                return CommandResult(output = refused, exitCode = 126, durationMs = 0)
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

                // [T-android-env-first-turn] Boot BEFORE taking the lease, not after.
                //
                // acquireShellLease() spawns the shell, and it used to run while
                // PRootKernel had not booted yet — so PROOT_LOADER was still empty,
                // proot bare-execve'd the rootfs busybox and SELinux killed it on
                // Android 10+, and the user's env was exported into that dead first
                // shell and lost when executeCommand() respawned it. Booting first
                // means the very first spawn already has its loader.
                if (!PRootKernel.isBooted) {
                    Log.i(TAG, "[$sessionId] Auto-booting PRootKernel")
                    PRootKernel.boot(appContext)
                }

                // [T-android-a11y-helper-mount] Remember whose /var/minis dirs this
                // dispatch id is mounted with, so native offloads (which only learn
                // the MINIS_CHAT_SESSION_ID = dispatch id) resolve guest paths
                // against the directory the guest actually sees. See
                // [mountedSessionIdFor].
                if (fsSessionId != null && fsSessionId != sessionId) {
                    mountedSessionIds[sessionId] = fsSessionId
                }

                // [T-shell-perf] 每条命令只在后台 scope 记一次账（不进会话锁，
                // 写序由 recordScope 的 limitedParallelism(1) 保证）。
                // 用 mountedSessionIdFor：helper 的 /var/minis/workspace 是父会话的
                // 目录，写进 helper 自己的目录等于写到一个 guest 看不见的地方。
                recordLastCommand(mountedSessionIdFor(sessionId), command)

                // [T-android-shell-fresh-process] Step 3: route through the strategy.
                // FRESH_PROCESS needs no lease, no pool slot and no mutex — a command
                // that owns its own process cannot collide with another one, so the
                // serialisation the warm path requires is simply absent here.
                // [T-android-global-shell-throttle] Commands are admitted against a
                // budget of live Minis child processes across every session; the rest
                // queue. Taken BEFORE the pool lease, so a queued command holds no
                // shell slot.
                // [T-android-shell-process-budget] The queue does not eat the
                // command's timeout: once admitted it runs with its full budget. A
                // fresh spawn that failed ran nothing, so it re-queues and retries.
                val outcome = GlobalShellThrottle.run(
                    tag = sessionId,
                    isStartFailure = ::isStartFailure,
                ) {
                    if (executionStrategy == ShellExecutionStrategy.FRESH_PROCESS) {
                        executeFresh(sessionId, command, armed, lineCallback, fsSessionId)
                    } else {
                        executeWarm(sessionId, command, armed, lineCallback, fsSessionId, budget)
                    }
                }
                // [T-u12-merge] 见函数头的说明：这里只写 when（作为
                // withCommandLock 的 lambda 值 → try 的值 → 函数返回值）。
                when (outcome) {
                    is GlobalShellThrottle.Outcome.Ran -> {
                        if (!outcome.queued) outcome.value
                        else outcome.value.copy(queueNote = queueNote(outcome.queuedMs, outcome.startRetries))
                    }
                    is GlobalShellThrottle.Outcome.QueueTimedOut -> CommandResult(
                        output = "Not run: waited ${outcome.waitedMs / 1000}s for Android's per-app process limit to " +
                            "free up (${outcome.lastLiveCount} Minis processes running, budget " +
                            "${GlobalShellThrottle.PROCESS_BUDGET}), or for the shell to start. Nothing was executed; " +
                            "retry once other commands or background processes have finished.",
                        exitCode = 124,
                        durationMs = outcome.waitedMs,
                    )
                }
            }
        } finally {
            SandboxJobKeepAlive.onEnd(appContext, sessionId)
        }
    }

    /**
     * A fresh process that could not be spawned (see FreshProcessShell's
     * spawn catch) ran nothing and is safe to retry. Everything else - a
     * warm shell that died, a command killed mid-run - may have had effects.
     */
    internal fun isStartFailure(result: CommandResult): Boolean =
        result.exitCode == -1 && result.output.startsWith(FreshProcessShell.SPAWN_FAILED_PREFIX)

    /**
     * [T-android-shell-process-budget] Tell the model a command sat in the
     * queue, so a slow result is not read as a slow command.
     */
    internal fun queueNote(queuedMs: Long, startRetries: Int): String {
        val secs = String.format(java.util.Locale.US, "%.1f", queuedMs / 1000.0)
        val retried = if (startRetries > 0) " Its process failed to start $startRetries time(s) and was retried." else ""
        return "<system-reminder>This command was queued for ${secs}s before it started, because Android " +
            "limits how many processes an app may run at once.$retried It then ran to completion and its output " +
            "is complete, but its measured duration may be inaccurate.</system-reminder>"
    }

    /** The pooled warm-shell path (ShellExecutionStrategy.WARM_SHELL). */
    private suspend fun executeWarm(
        sessionId: String,
        command: String,
        armed: Long,
        lineCallback: ((String) -> Unit)?,
        fsSessionId: String?,
        budget: ProcessBudget,
    ): CommandResult {
        val lease = acquireShellLease(sessionId, fsSessionId ?: sessionId)

        return try {
            // [T-idle-reap-slot] 本槽位马上要用，别让空闲回收在命令启动前后摘掉它。
            lastIdle.remove(lease.key)
            val startTime = System.currentTimeMillis()

            // [diag] trace the sessionId that shell_execute is dispatched with —
            // suspected source of the Chinese-emoji filename vanishing bug
            Log.w(TAG, "[diag] execute sessionId=$sessionId cmd=${command.take(120).replace('\n', ' ')}")

            // The lease already resolved (and started) this slot's shell.
            val shell = lease.shell

            // Inject user-defined environment variables as a *full snapshot*
            // (T124a). Pass the previously-injected key set so applyEnvironment
            // can `unset` anything the user has since removed from settings;
            // otherwise the long-lived shell would keep stale values.
            // [T-android-concurrent-shell] Keyed by SLOT, not by session: each
            // pooled shell is its own process with its own environment, so a
            // session-wide snapshot would tell slot 2 that keys slot 1 already
            // exported are present when slot 2 has never seen them.
            // [T-android-env-first-turn] applyEnvironment now also REMEMBERS
            // this snapshot on the shell, so that if the process turns out to
            // be dead (or dies and is respawned by executeCommand below), the
            // exports are replayed into the incarnation that actually runs the
            // command. Before that, a cold-start shell death between here and
            // executeCommand silently dropped the whole environment and the
            // user's first tool call ran bare.
            val envVars = envVarRepository?.allAsDict() ?: emptyMap()
            val previousKeys = lastInjectedKeys[lease.key] ?: emptySet()
            if (envVars.isNotEmpty() || previousKeys.isNotEmpty()) {
                shell.applyEnvironment(envVars, previousKeys = previousKeys)
                lastInjectedKeys[lease.key] = envVars.keys.toSet()
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
            // [T-offload-real-exit] proot 的 native_offload trace 行里带着宿主 handler
            // 的真实退出码：guest stub 可能 exit 0，直接采信 exitCode 会把失败当成功；
            // handler_timeout 同理要还原成 124。trace 行本身还得从输出里摘掉（内部日志）。
            val (stripped, loggedExit) = TerminalSanitizer.dropOffloadTrace(sanitized)
            var effectiveExit = exitCode
            if (effectiveExit == 0 && loggedExit != null && loggedExit != 0) effectiveExit = loggedExit
            if (effectiveExit == 0 && stripped.contains("handler_timeout")) effectiveExit = 124
            val truncated = TerminalSanitizer.truncateIfNeeded(stripped)
            val output = if (effectiveExit != 0 && effectiveExit != 124) {
                // 上游的 [T-android-parity-fixes] 单点标注：重复的 "(exit code: N)" 只在这里拦。
                ShellExitCode.ensureSuffix(truncated, effectiveExit)
            } else {
                truncated
            }

            CommandResult(
                output = MCPToolPolicy.filterToolsOutput(appContext, command, output),
                exitCode = effectiveExit,
                durationMs = durationMs,
            )
        } finally {
            // [T-idle-reap-slot] 空闲回收按**槽位**记账：池化后一个会话可能有多把壳，
            // 按会话记账会让回收器看不到具体哪把壳闲着。unlock 必须最后做——它是
            // 同一槽位下一个命令的放行信号（lease 在 acquireShellLease 里已经持有）。
            if (shells.containsKey(lease.key)) {
                lastIdle[lease.key] = android.os.SystemClock.elapsedRealtime()
            }
            scheduleIdleReap()
            lease.mutex.unlock()
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
     * [T-android-concurrent-shell] Upper bound on shells per session.
     *
     * Each is a proot process holding the session's bind mounts, so this is
     * real memory and real PIDs on a phone — not a free knob. Four covers the
     * observed fan-out (this device's history: of 88,839 tool-carrying turns,
     * the widest were a handful of 5-call turns, and shell_execute appears in
     * at most a few of those at once) while staying well under
     * ChatViewModel.MAX_CONCURRENT_TOOLS, which bounds the whole turn anyway.
     * A 5th concurrent shell command simply waits for a slot — the pre-change
     * behaviour, applied at a much higher threshold.
     */
    private const val MAX_SHELLS_PER_SESSION = 4

    /** One pooled shell plus the lock that serializes ITS pipe. */
    private data class ShellLease(
        /** "<sessionId>#<slot>" — the key for [shells], [mutexes], [lastInjectedKeys]. */
        val key: String,
        val shell: PersistentShell,
        val mutex: Mutex,
    )

    /**
     * [T-android-concurrent-shell] Find a shell for this session whose lock is
     * free, creating one if the pool has room.
     *
     * Deliberately best-effort rather than a strict allocator: `tryLock` is
     * only a hint (the winner takes it for real inside `withLock`), so two
     * callers can pick the same slot and one will wait. That is correct and
     * cheap — the alternative, holding a global allocation lock across the
     * whole command, would reintroduce the serialization this exists to remove.
     *
     * Falls back to slot 0 when every slot is busy and the pool is full, so a
     * 5th concurrent command queues instead of failing.
     */
    private suspend fun acquireShellLease(sessionId: String, fsSessionId: String): ShellLease {
        // Claim a slot ATOMICALLY, before doing anything slow.
        //
        // The first cut here scanned for a slot whose Mutex was not `isLocked`
        // and returned it. That is a race, and the device proved it: three
        // concurrent commands all reached the scan before ANY of them had
        // entered `withLock`, so every one saw slot 0 free, all three picked
        // it, and they queued exactly as before — the logs even show slot 0
        // being created twice. `isLocked` answers about the past, not about
        // what the caller is going to do next.
        //
        // `tryLock` is the fix: it CLAIMS the slot in the same operation that
        // tests it, so exactly one caller can win a given slot. The lock is
        // held from here until the command finishes and is released in the
        // caller's `finally` — see [ShellLease.release].
        for (slot in 0 until MAX_SHELLS_PER_SESSION) {
            val key = "$sessionId#$slot"
            // [T-e4-mutex-atomic] computeIfAbsent 在 ConcurrentHashMap 内原子地
            // 完成"取值或发布"：两个调用者拿到的是**同一把** Mutex。旧写法
            // `getOrPut { Mutex() }` 是靠 stdlib 的 ConcurrentMap 重载才返回
            // putIfAbsent 的赢家，读代码看不出这一点。
            val mutex = mutexes.computeIfAbsent(key) { Mutex() }
            if (!mutex.tryLock()) continue
            // Won this slot. Reuse its shell when warm; otherwise spawn one,
            // still holding the claim so no one else spawns into it too.
            val existing = shells[key]
            val shell = if (existing != null && existing.isAlive) existing
            else getOrCreateShell(key, fsSessionId, chatSessionId = sessionId)
            return ShellLease(key, shell, mutex)
        }
        // Pool exhausted: every slot is genuinely busy. Wait for slot 0 — the
        // pre-pooling behaviour, now at a 4x higher threshold.
        val key = "$sessionId#0"
        // [T-e4-mutex-atomic] 同槽位必须是同一把锁，见上面 tryLock 分支的说明。
        val mutex = mutexes.computeIfAbsent(key) { Mutex() }
        mutex.lock()
        val existing = shells[key]
        val shell = if (existing != null && existing.isAlive) existing
        else getOrCreateShell(key, fsSessionId, chatSessionId = sessionId)
        return ShellLease(key, shell, mutex)
    }

    /**
     * Get the existing shell for this POOL SLOT, or create a new one.
     *
     * [T-b10-superseded-null] [startOrReuseShell] 的单次尝试可能在锁外窗口里被摘掉
     * （`stopCurrentCommand` / `sessionDidTerminate` 都不持 globalLock），此时它返回
     * null：那次 spawn 出来的实例已被 `stop()`，**绝不能**交回调用方 ——
     * `PersistentShell.executeCommand` 会先 `ensureStarted()`，把这个已停的壳重新
     * spawn 成一个不在 `shells[]` 里的 proot（孤儿进程：没人能停它，也没人会复用它），
     * 而同一会话的下一条命令又会建第二个壳，两个壳各有独立的 cd/env/后台作业。
     * 所以这里重试整条路径，连续 [ShellSupersedePolicy.MAX_START_ATTEMPTS] 次都被
     * 摘掉才判失败（fail-closed，调用方拿到明确错误而不是一个没人管的壳）。
     *
     * @param key the POOL KEY ("<chatSessionId>#<slot>"), which is what [shells]
     *   is registered under.
     * @param fsSessionId the session whose `/var/minis` dirs the shell binds
     *   (a helper's PARENT when they differ).
     * @param chatSessionId the real chat session id. Kept separate because it
     *   is exported into the shell as MINIS_CHAT_SESSION_ID, which the native
     *   offload layer reads to scope ASK_ONCE permission grants per chat.
     *   Handing it the slotted key would make every slot a different "session"
     *   to the permission manager, so a user who granted a permission once
     *   would be prompted again on the next slot.
     */
    private suspend fun getOrCreateShell(
        key: String,
        fsSessionId: String,
        chatSessionId: String,
    ): PersistentShell {
        var attempt = 0
        while (true) {
            attempt++
            val shell = startOrReuseShell(key, fsSessionId, chatSessionId)
            if (shell != null) return shell
            if (attempt >= ShellSupersedePolicy.MAX_START_ATTEMPTS) {
                throw ShellSupersedePolicy.exhausted(key, attempt)
            }
            Log.w(TAG, "[$key] shell registry emptied while starting; retrying (attempt $attempt)")
        }
    }

    /**
     * 单次尝试：复用注册表里的活壳，或注册并 spawn 一个新实例。
     * Uses globalLock to prevent two coroutines from simultaneously creating
     * a shell for the same pool slot (e.g. if the old shell just died).
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
    private suspend fun startOrReuseShell(
        key: String,
        fsSessionId: String,
        chatSessionId: String,
    ): PersistentShell? {
        // Fast path: existing alive shell
        val existing = shells[key]
        if (existing != null && existing.isAlive && !shellMountsStale(existing, fsSessionId)) {
            Log.w(TAG, "[diag] reuse existing shell for sessionId=$key attachmentsMount=${existing.debugBindMount("/var/minis/attachments")}")
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
            val recheck = shells[key]
            if (recheck != null && recheck.isAlive && !shellMountsStale(recheck, fsSessionId)) {
                Log.w(TAG, "[diag] reuse existing shell (post-lock) for sessionId=$key attachmentsMount=${recheck.debugBindMount("/var/minis/attachments")}")
                return@withLock recheck to null
            }
            if (recheck != null && recheck.isStartingUp && !shellMountsStale(recheck, fsSessionId)) {
                Log.w(TAG, "[diag] reuse spawning shell for sessionId=$key")
                return@withLock recheck to null
            }

            // Shell is dead or missing — register a fresh instance now; the old
            // one is killed outside the lock (stop() is idempotent).
            // buildSessionBindMounts 只碰本会话自己的目录（per-session mounts
            // 不再写进全局表，见 [sessionBindMounts]），所以留在锁内；
            // 构造实例本身只是记账。
            val bindMounts = buildSessionBindMounts(fsSessionId)
            val fresh = PersistentShell(appContext, chatSessionId, bindMounts)
            shells[key] = fresh
            Log.i(TAG, "[$key] Shell created with ${bindMounts.size} bind mounts")
            Log.w(TAG, "[diag] new shell created sessionId=$key attachmentsMount=${bindMounts["/var/minis/attachments"]}")
            fresh to recheck
        }
        val (shell, stale) = plan
        if (stale != null) {
            Log.w(TAG, "[$key] Shell ${if (stale.isAlive) "mounts stale" else "died"}, recreating")
            // 先杀旧壳再启动新壳：release(kill=true) 的扫描若在新 shell 起来之后跑，
            // 可能把刚 spawn 的进程按旧归属杀掉。
            stale.stop()
        }
        shell.ensureStarted()
        // [T-b10-shell-create-off-lock] 这段锁外窗口里，stopCurrentCommand（它不看
        // globalLock）或一次挂载变更可能已经让另一个实例接管 registry：收掉刚起来的
        // 进程，别留下没人引用的 proot；有接管者就交给它（ensureStarted 幂等）。
        val current = shells[key]
        when (ShellSupersedePolicy.verdict(current, shell)) {
            ShellSupersedePolicy.Verdict.REGISTERED -> return shell
            ShellSupersedePolicy.Verdict.SUPERSEDED -> {
                Log.w(TAG, "[$key] shell superseded while starting; discarding the new instance")
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
                val latest = shells[key]
                if (ShellSupersedePolicy.verdict(latest, takeover) != ShellSupersedePolicy.Verdict.REGISTERED) {
                    Log.w(TAG, "[$key] takeover instance left the registry while starting; discarding it")
                    takeover.stop()
                    return null
                }
                return takeover
            }
            // [T-b10-superseded-null] 注册表空了（条目在锁外被摘走）：刚起来的实例
            // 已经 stop()，交回调用方就等于把一个"已停的壳"重新 spawn 成孤儿 proot。
            // 返回 null 让 [getOrCreateShell] 重试整条路径。
            ShellSupersedePolicy.Verdict.REGISTRY_EMPTY -> {
                Log.w(TAG, "[$key] shell registry emptied while starting; discarding the new instance")
                shell.stop()
                return null
            }
        }
    }

    /**
     * The bind set (`linuxPath -> hostPath`) this session's shell would use:
     * per-session subdirs (`workspace`, `attachments`, `offloads`, `browser` and
     * the per-session `memory`), the global dirs, external mounts and shared
     * storage. This is exactly what goes into that shell's PRoot `-b` argv.
     *
     * [T-mcp-session-bind] Any caller that spawns its own guest process *for a
     * known session* — the MCP stdio bridge — must take its `-b` argv from here
     * instead of the process-wide [PRootKernel.bindMounts] table. That table is
     * last-writer-wins, so a reader gets whichever session built a shell last,
     * and `/var/minis/{workspace,attachments,offloads,browser,memory}` would
     * point at another chat. Host dirs are created either way: proot needs both
     * sides of `-b host:linux` to exist.
     *
     * [T-android-session-private-mounts] The per-session subdirs are NEVER
     * written into the shared table: [PRootKernel.addBindMount] refuses them, so
     * `publish = true` now only re-registers the global dirs (idempotent — they
     * are already registered by `PRootKernel.registerGlobalBindMounts` at boot).
     * Readers with no session id keep their old answer through the explicit
     * [PRootKernel.noteLegacySession] hint that [buildSessionBindMounts] sets,
     * and session-scoped callers use [PRootKernel.resolveSessionHostPath].
     *
     * The `publish` parameter is kept because it still drives the shared-storage
     * binds and keeps existing callers (`McpNativeBridge` passes
     * `publish = false`) source-compatible.
     */
    fun sessionBindMounts(sessionId: String, publish: Boolean = true): Map<String, String> =
        buildSessionBindMounts(sessionId, publish = publish, mkdirs = true)

    /**
     * [T-android-concurrent-shell] True when at least one pooled shell of this
     * session is alive. [shells] is keyed `"<sessionId>#<slot>"` since pooling,
     * so the old exact-key lookup would always answer false — and `#` anchors
     * the prefix test so a session whose id is a prefix of another cannot see
     * its neighbour's shells.
     */
    internal fun hasLiveShell(sessionId: String): Boolean {
        val prefix = "$sessionId#"
        return shells.any { (key, shell) ->
            shell.isAlive && (key == sessionId || key.startsWith(prefix))
        }
    }

    private fun shellMountsStale(shell: PersistentShell, sessionId: String): Boolean {
        // Compare the whole argv, not only /sdcard. A user mount named sdcard,
        // a new /var/minis/mounts entry, or a filed-session hostDir change is
        // otherwise stuck on the shell that already started.
        return shell.bindSnapshot() != buildSessionBindMounts(sessionId, publish = false)
    }

    /**
     * Build bind mounts for a session:
     * - Session-level: workspace, attachments, offloads, browser, memory →
     *   per-session dirs ([SessionWorkspace.SESSION_SUBDIRS]; memory stays
     *   per-session on purpose — the daily memory quota is per chat)
     * - Global: skills, shared, mcp-servers → shared dirs across all sessions
     *   ([SessionWorkspace.GLOBAL_BIND_SUBDIRS])
     *
     * [T-android-session-private-mounts] The per-session entries go into the
     * shell's own PRoot argv and are NOT published: [PRootKernel.addBindMount]
     * refuses them, because the shared table is last-writer-wins and publishing
     * them is how one chat's `/var/minis/workspace` used to become every other
     * reader's. [PRootKernel.noteLegacySession] records the explicit hint for
     * the remaining readers that resolve a per-session path without a session id.
     *
     * `mkdirs = false` (used by the mount-staleness check, which runs on every
     * command) keeps the map computation free of IO.
     */
    private fun buildSessionBindMounts(
        sessionId: String,
        publish: Boolean = true,
        mkdirs: Boolean = publish,
    ): Map<String, String> {
        val filesDir = appContext.filesDir
        val mounts = linkedMapOf<String, String>()

        // [T-android-session-private-mounts] Legacy readers (no session id at
        // hand) keep the answer they had before — "the session that built a
        // shell most recently" — but as an explicit, logged hint instead of a
        // side effect on the shared mount table.
        PRootKernel.noteLegacySession(appContext, sessionId)

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
            // [T-android-session-private-mounts] deliberately NOT published: the
            // per-session dirs live in this shell's argv only.
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
     * [T-android-concurrent-shell] Tear down every pooled shell belonging to
     * [sessionId] and drop its per-slot bookkeeping.
     *
     * Stops each one here rather than returning them, so no caller can forget a
     * slot; the return value is only the FIRST shell removed, kept so the
     * existing callers' "did anything exist?" logging still works — and it is
     * returned UNSTOPPED, so the caller decides (see [sessionDidTerminate]).
     *
     * Matching is on the "<sessionId>#<slot>" key prefix, anchored with the
     * separator so a session id that is a prefix of another cannot sweep its
     * neighbour's shells.
     */
    private fun removeSessionShells(sessionId: String): PersistentShell? {
        val prefix = "$sessionId#"
        var first: PersistentShell? = null
        for (key in shells.keys.toList()) {
            if (key != sessionId && !key.startsWith(prefix)) continue
            val shell = shells.remove(key) ?: continue
            // T124a: the env snapshot belongs to the now-dead shell.
            lastInjectedKeys.remove(key)
            // [T-android-concurrent-shell] The Mutex is deliberately NOT removed.
            // A command may be running on this slot right now and holding it;
            // dropping it from the map would let the next command create a FRESH
            // Mutex for the same key and run concurrently with the in-flight one
            // on a shell being torn down. Leaving it means the in-flight holder
            // still unlocks the object it locked, and the next caller waits
            // correctly. The map is bounded by (sessions x 4), not a real leak.
            if (first == null) first = shell else shell.stop()
        }
        // Legacy un-slotted key, from a build before pooling.
        lastInjectedKeys.remove(sessionId)
        return first
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
        // [T-android-concurrent-shell] Every slot of this session goes, not just
        // the un-slotted key: a closed chat must not leave pooled shells (and
        // their bind-mounted dirs) running.
        val shell = removeSessionShells(sessionId)
        lastIdle.keys.removeIf { it == sessionId || it.startsWith("$sessionId#") }
        // T124a: drop the snapshot too — a future shell for the same id
        // restarts from a clean baseline, so the next applyEnvironment
        // shouldn't try to `unset` keys that don't exist in the new shell.
        lastInjectedKeys.remove(sessionId)
        mountedSessionIds.remove(sessionId)
        shell?.stop()
        if (shell != null) Log.i(TAG, "[$sessionId] Shell terminated")
        // [T-android-fresh-session-terminate] Fresh-process commands are not in
        // the pooled `shells` map, so the lines above never reached them: a
        // closed session's running command kept going (and holding its
        // bind-mounted workspace) until it finished or timed out. Same path as
        // the Stop button — FreshProcessShell.stop() kills the whole process
        // group — and keyed on this exact session id, so other sessions'
        // commands are untouched.
        val stoppedFresh = stopFreshShells(sessionId)
        if (stoppedFresh > 0) Log.i(TAG, "[$sessionId] $stoppedFresh fresh-process command(s) killed on terminate")
    }

    /**
     * Stop the shell for a specific session (e.g. user tapped cancel).
     * The shell process is killed; next command will recreate it.
     */
    fun stopCurrentCommand(sessionId: String? = null) {
        if (sessionId != null) {
            // [T-android-concurrent-shell] Stop EVERY shell in the session's
            // pool, not one. A session now holds several, and Stop must mean
            // "stop what this conversation is running" — killing slot 0 while
            // slots 1-3 kept working would leave commands the user believes
            // they cancelled still executing, with no way to reach them.
            val shell = removeSessionShells(sessionId)
            shell?.stop()
            // [T-android-lane-teardown] A chat's sub-agent lanes own their own
            // pool keys ("subagent:<owner>:<n>#<slot>"), so stopping a chat from
            // the UI must sweep them too — otherwise a lane's shell keeps
            // running after the user cancelled. A lane's own id is not expanded
            // (that is what isLaneSession guards): a lane and its parent share
            // one scope, and expanding would stop the parent mid-call.
            if (!isLaneSession(sessionId)) {
                val lanePfx = lanePrefix(sessionId)
                shells.keys.filter { it.startsWith(lanePfx) }.forEach { stopOne(it) }
            }
            lastIdle.keys.removeIf { it == sessionId || it.startsWith("$sessionId#") }
            stopFreshShells(sessionId)
            Log.i(TAG, "[$sessionId] Shells stopped by user")
        } else {
            // Stop all sessions (legacy/fallback)
            // [T-e4-stop-all-detach-first] 先摘注册表再逐个 stop，与 stopOne/terminateOne
            // 同序。原先"先 stop、后 clear"：两次操作之间的窗口里 shells[] 仍引用已停实例，
            // getOrCreateShell 的快路径/启动后复核会把它交回调用方，而调用方
            // PersistentShell.executeCommand 的 ensureStarted 又把这个已停的壳重新 spawn
            // 成不在注册表里的 proot（孤儿进程 + 同会话双壳）。
            val doomed = shells.values.toList()
            shells.clear()
            // [T-android-fresh-shells-concurrent] Snapshot via ArrayList, see stopFreshShells.
            ArrayList(freshShells.keys).forEach { stopFreshShells(it) }
            lastInjectedKeys.clear()
            for (shell in doomed) shell.stop()
            ShellExecutor.destroyCurrent()
        }
    }

    /**
     * Stop every fresh-process command running for [sessionId].
     *
     * The entries are NOT removed here — each execute() call removes its own
     * in a finally block. Removing them here too would race that cleanup and
     * could drop a set another command had just re-created.
     */
    private fun stopFreshShells(sessionId: String): Int {
        // [T-android-fresh-shells-concurrent] Iterate a snapshot: a command
        // finishing concurrently removes itself from the live set. ArrayList(c)
        // copies via toArray(), which is weakly consistent on a concurrent set;
        // Kotlin's toList() is NOT — for a size-1 collection it calls
        // iterator().next() after reading size, and throws
        // NoSuchElementException if the element was removed in between.
        val snapshot = freshShells[sessionId]?.let { ArrayList(it) } ?: return 0
        snapshot.forEach { runCatching { it.stop() } }
        return snapshot.size
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

    /** Stop every pooled shell of one session (or of one lane id). */
    private fun stopOne(sessionId: String) {
        // [T-android-concurrent-shell] Prefix sweep, not an exact-key remove:
        // [sessionId] may be a chat id (its slots are "<id>#<slot>") or a single
        // pool key, and the sweep handles both.
        val shell = removeSessionShells(sessionId)
        lastIdle.keys.removeIf { it == sessionId || it.startsWith("$sessionId#") }
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
