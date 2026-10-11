package com.openminis.app.mcp.client

import android.content.Context
import android.os.SystemClock
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.MCPRepository.MCPServerConfig
import com.openminis.app.data.repository.MCPToolPolicy
import com.openminis.app.logging.AppLogger
import com.openminis.app.mcp.client.McpJsonRpc.McpError
import com.openminis.app.mcp.oauth.MCPOAuthStore
import com.openminis.app.mcp.oauth.MCPTokenBridge
import com.openminis.app.mcp.server.McpServerManager
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.sandbox.SessionWorkspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * [T-mcp-native] In-process MCP access for the agent — replaces the round trip
 * through `minis-mcp-cli` (shell_execute → PRoot bash → Python CLI → daemon
 * socket → server) with a direct Kotlin transport. The CLI/daemon stay in
 * place as a fallback and for interactive terminal use.
 *
 * Responsibilities:
 *  - resolve the effective server config (global servers.json + per-session
 *    toggles) through [MCPRepository];
 *  - expand `$$VAR` placeholders in url/headers/env from the app env-var
 *    store (parity with transport/http.py `expand_env`);
 *  - attach OAuth bearer tokens from [MCPOAuthStore], refreshing expired
 *    grants natively and keeping [MCPTokenBridge] in sync so the guest CLI
 *    sees the same rotated tokens;
 *  - enforce [MCPToolPolicy] (per-tool disable switches) on this path too —
 *    previously only the shell command parser enforced it;
 *  - cache one [McpClient] per server, rebuilt when the config fingerprint
 *    changes; stdio clients hold a live guest process and are additionally
 *    keyed by the calling session's mount scope [T-mcp-session-bind], since
 *    their bind mounts are fixed at spawn.
 *
 * All entry points are BLOCKING (OkHttp sync calls / process IO); callers
 * must run them on Dispatchers.IO.
 */
object McpNativeBridge {

    private const val TAG = "McpNativeBridge"

    /** Daemon-parity default when a stdio server sets no startupTimeoutSeconds. */
    private const val DEFAULT_STARTUP_TIMEOUT_SECONDS = 60

    /** Connect/write are bounded; read stays long for slow tool executions
     *  (parity with httpx.Timeout(connect=15, read=TIMEOUT_SECONDS, …)). */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            // MCP servers are long-lived API endpoints, not browsers.
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * [T-mcp-session-bind] Cache identity. `mountScope` is the session (host) whose
     * host directories the client was spawned against. It is part of the key because
     * a stdio client's `-b` argv is frozen at spawn, so a process built for one
     * session must never serve another — otherwise session B's MCP tools read and
     * write session A's `workspace`/`attachments`/`offloads`/`memory`
     * (ticket `sandbox-env-offload#4`). HTTP clients hold no process and no mounts,
     * so they keep one entry per server (`stdio = false`).
     */
    private data class CacheKey(val stdio: Boolean, val mountScope: String?, val server: String) {
        override fun toString(): String =
            if (mountScope == null) server else "$mountScope\u0000$server"
    }

    private data class Cached(val fingerprint: String, val client: McpClient)

    private val clients = ConcurrentHashMap<CacheKey, Cached>()

    /**
     * [T-mcp-inflight-lease] Mount scope → last use (`elapsedRealtime`) + number of MCP
     * calls currently running against that scope's stdio client, in one table.
     * [McpScopeLeaseTable] keeps the two in lockstep with [clients]: taking a cached
     * process out of [clients] *is* the in-flight registration (one critical section),
     * and eviction re-checks the count under the same lock, so neither a call that
     * started after the victim snapshot nor a call that is already running can have its
     * process closed underneath it.
     *
     * [McpStdioScopePolicy.victims] refuses to pick busy scopes, because a single
     * `tools/call` may legitimately run for the full 300s read timeout — far past the
     * 30s idle floor — and closing its process mid-call surfaces to the caller as
     * CONNECTION_ERROR/TIMEOUT (S2/S6: a long build or browser call used to be killed
     * by the next session's opening call).
     */
    private val scopeLeases = McpScopeLeaseTable()

    /** [T-mcp-session-bind] Reaps guest processes detached by [closeSession] and
     *  by eviction / preview-scope recycling. */
    private val closerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var envRepoRef: EnvVarRepository? = null

    private fun envRepo(context: Context): EnvVarRepository =
        envRepoRef ?: synchronized(this) {
            envRepoRef ?: EnvVarRepository(context.applicationContext).also { envRepoRef = it }
        }

    private fun envResolver(context: Context): (String) -> String? = { key ->
        envRepo(context).getValue(key)
    }

    // -- public surface --------------------------------------------------------

    /** Text overview of configured servers for `mcp(action="servers")`. */
    fun serversOverview(repo: MCPRepository, sessionId: String): String {
        val overrides = repo.effectiveEnabledMap(sessionId)
        val all = repo.servers.value
        if (all.isEmpty()) {
            return "No MCP servers configured. Add one in Settings → MCP."
        }
        val sb = StringBuilder()
        for (cfg in all) {
            val enabled = overrides[cfg.id] ?: cfg.enabled
            sb.append("- ").append(cfg.id)
            sb.append(if (enabled) " [enabled]" else " [disabled]")
            sb.append(" (").append(cfg.transportSummary).append(')')
            cfg.note?.takeIf { it.isNotBlank() }?.let { sb.append(" — ").append(it) }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    /**
     * `tools/list` for [server], filtered through [MCPToolPolicy], formatted
     * for the agent. Throws [McpError] on transport failures.
     *
     * [T-mcp-inflight-evict] [T-mcp-inflight-lease] Runs inside [withClient], so the
     * stdio scope this call is using cannot be evicted underneath it.
     */
    fun listTools(context: Context, repo: MCPRepository, sessionId: String, server: String): String {
        return withClient(context, repo, sessionId, server) { client, cfg ->
            val tools = client.listTools()
            val disabled = MCPToolPolicy.disabled(context, cfg.id)
            val filtered = if (disabled.isEmpty()) tools else org.json.JSONArray().apply {
                for (i in 0 until tools.length()) {
                    val t = tools.optJSONObject(i) ?: continue
                    if (t.optString("name") !in disabled) put(t)
                }
            }
            val header = "MCP server '${cfg.id}' (${cfg.transportSummary}):\n"
            val body = McpJsonRpc.formatToolList(filtered)
            val hidden = tools.length() - filtered.length()
            val tail = if (hidden > 0) "\n($hidden tool(s) disabled in Settings → MCP)" else ""
            header + body + tail
        }
    }

    /**
     * `tools/call`. Enforces the per-tool policy BEFORE touching the network.
     * Returns the flattened outcome; `isError` reflects the server verdict.
     *
     * [T-mcp-inflight-evict] [T-mcp-inflight-lease] Also leased through [withClient]:
     * this is the call that can hold a stdio process for the full 300s timeout, so
     * eviction must see it as busy for as long as it runs.
     */
    fun callTool(
        context: Context,
        repo: MCPRepository,
        sessionId: String,
        server: String,
        tool: String,
        arguments: JSONObject,
    ): McpJsonRpc.CallOutcome {
        return withClient(context, repo, sessionId, server) { client, cfg ->
            if (MCPToolPolicy.isDisabled(context, cfg.id, tool)) {
                throw McpError(
                    "TOOL_DISABLED",
                    "MCP tool \"$tool\" on ${cfg.id} is disabled in Settings → MCP.",
                )
            }
            McpJsonRpc.formatCallResult(client.callTool(tool, arguments))
        }
    }

    /**
     * Drop the cached client for [server] (config changed / process died).
     *
     * [T-mcp-no-callsite] 当前无生产调用点（全仓检索 `McpNativeBridge.invalidate` 无命中）：
     * 配置变更由 [clientFor] 的指纹比较在**下一次调用**上自动重建，stdio 进程死掉由
     * [McpStdioClient.ensureInitialized] 的 `alive()` 检查原地重启，都不需要外部显式失效。
     * 所以这个入口今天只是留给"要立刻丢缓存"的显式钩子，[pruneScopeLastUsed] 因此也是惰性
     * 的——它只在这里被调用，别把它当成常驻的 LRU 清理。保留签名与行为，别当死代码删掉。
     */
    fun invalidate(server: String) {
        for (key in clients.keys.filter { it.server == server }) {
            // [T-mcp-close-async] 摘除是同步的（下一次调用不会再拿到这个 client），进程回收
            // 交给 closerScope：`McpStdioClient.close()` 与 `ensureInitialized()` 争同一把锁，
            // 而 initialize 最长持锁 startupTimeout（默认 60s），压在调用线程上就是一次
            // 最长一分钟的卡顿（invalidate 就在 MCP 调用路径的错误分支上）。
            val cached = clients.remove(key) ?: continue
            // [T-mcp-supersede-lease] 摘掉的 client 可能正被同作用域的在途调用使用（在途簿记
            // 按作用域、不按 client）：作用域还忙就挂起回收，等最后一个调用释放后再 close，
            // 别 destroy 掉别人正在等的进程。这里没有自己的登记，own = 0。
            val scope = key.mountScope
            val deferred = scope != null &&
                scopeLeases.deferCloseIfBusy(scope, own = 0) { recycleAsync(key.toString(), cached.client) }
            if (!deferred) recycleAsync(key.toString(), cached.client)
        }
        // [T-mcp-scope-lru] The stdio entries just removed may have been the only
        // client of their scope; a leftover timestamp would keep occupying one of
        // [McpStdioScopePolicy.MAX_SCOPES] slots with no process behind it.
        pruneScopeLastUsed()
    }

    /**
     * [T-mcp-inflight-lease] Run one MCP call against a leased client. The lease — and
     * with it the in-flight registration that keeps [evictExcessScopes] away — is taken
     * inside [clientFor] (租约即登记), because a registration made here would come too
     * late: eviction decides on a snapshot of the scope table, so the window between
     * "looked the client up" and "counted myself busy" is exactly the window in which a
     * concurrent eviction could close the process this call is about to use. This method
     * only releases the lease, on the error path too.
     *
     * A preview-scope client is private to this call and is recycled here.
     */
    private fun <T> withClient(
        context: Context,
        repo: MCPRepository,
        sessionId: String,
        server: String,
        block: (McpClient, MCPServerConfig) -> T,
    ): T {
        val lease = clientFor(context, repo, sessionId, server)
        try {
            return block(lease.client, lease.cfg)
        } finally {
            // [T-mcp-inflight-lease] 在途登记在 clientFor 里完成，这里必须无条件释放
            // （含异常路径），否则该作用域会永远不可淘汰。计数归零时 release 会交回被推迟的
            // 回收动作（[T-mcp-supersede-lease]：替换/接管那一刻同作用域还有别的调用，被顶替的
            // 进程当时不能 close），就地排给 reaper —— 这条线程上不做 IO。
            if (lease.registered) lease.scope?.let { scope ->
                for (recycle in scopeLeases.release(scope)) recycle()
            }
            if (lease.closeAfterUse) {
                // [T-mcp-preview-scope] Nobody else can reach this client (it never
                // entered [clients]), so detaching is implicit; the `destroy()` +
                // `waitFor(2s)` of close() goes to the reaper thread instead of
                // delaying the settings panel.
                recycleAsync("preview scope", lease.client)
            }
        }
    }

    /**
     * [T-mcp-session-bind] Drop every stdio client spawned for [sessionId]'s mount
     * scope. Called when the session ends (`ExecutionCoordinator.sessionDidTerminate`)
     * so a closed chat does not leave a guest server process behind.
     *
     * The cache entries are detached synchronously — a client created afterwards can
     * therefore never be caught by this close — while the processes themselves are
     * reaped on an IO thread, because `McpStdioClient.close()` waits for the guest to
     * exit (up to 2s) and this is reached from `ChatViewModel.onCleared()`.
     */
    fun closeSession(sessionId: String) {
        val detached = detachScope(McpStdioScopePolicy.scopeOf(sessionId))
        if (detached.isEmpty()) return
        closerScope.launch {
            for (client in detached) runCatching { client.close() }
        }
    }

    /**
     * Close every cached client — app shutdown / sandbox teardown.
     *
     * [T-mcp-no-callsite] 当前无生产调用点（全仓检索 `McpNativeBridge.closeAll` 无命中）：
     * 今天没有调用者在关停时显式收这些常驻进程。签名与行为保持可用（留给需要显式收进程的
     * 关停/测试），别当死代码删掉；也**不要**据此以为 MCP 常驻进程有一条统一的全局清理
     * 调用链 —— 别的地方没有替它兜底。
     *
     * [T-mcp-close-async] 这里保持同步：关停要真的把 guest 进程收掉，不能只交给 reaper。
     * 唯一的例外是 [T-mcp-supersede-lease] 挂起的那些被顶替进程 —— 它们不在 [clients] 里，
     * 只能从 [McpScopeLeaseTable.clear] 交回、经 reaper 收（总比随表丢掉、进程泄漏强）。
     */
    fun closeAll() {
        for ((key, cached) in clients) {
            runCatching { cached.client.close() }
                .onFailure { AppLogger.warning(TAG, "close($key): ${it.message}") }
        }
        clients.clear()
        // [T-mcp-supersede-lease] 挂起的回收动作不能随表一起丢：被顶替的进程不在 clients 里，
        // 丢掉就是一次 guest 进程泄漏。
        for (recycle in scopeLeases.clear()) recycle()
    }

    /**
     * [T-mcp-session-bind] Detach every client bound to one mount scope (no IO).
     *
     * [onlyIfIdle] 是淘汰路径用的：摘除与"取用缓存 + 在途登记"（[clientFor]）在同一次
     * 临界区里复查，在途调用期间整项跳过——见 [McpScopeLeaseTable.detachIfIdle]；返回空
     * 列表表示"这一轮没摘"（调用已经开始了）。会话结束走强制摘除：会话没了，调用也不再
     * 有意义，而且摘除必须同步，之后新建的同作用域 client 才不会被这次关闭误伤。
     */
    private fun detachScope(scope: String, onlyIfIdle: Boolean = false): List<McpClient> {
        val detached = mutableListOf<McpClient>()
        val remove = {
            for (key in clients.keys.filter { it.mountScope == scope }) {
                clients.remove(key)?.let { detached += it.client }
            }
        }
        val done = if (onlyIfIdle) {
            scopeLeases.detachIfIdle(scope, remove)
        } else {
            scopeLeases.detach(scope, remove)
            true
        }
        return if (done) detached else emptyList()
    }

    /**
     * [T-mcp-scope-lru] Drop timestamps of scopes that no longer hold a cached
     * client. Without this, `invalidate(server)` leaves "client-less scopes" in
     * the scope table: they still count against [McpStdioScopePolicy.MAX_SCOPES]
     * and can even be picked as victims, so a real session's process gets closed
     * while a scope with nothing left in it keeps the slot.
     */
    private fun pruneScopeLastUsed() {
        scopeLeases.retainScopes(clients.keys.mapNotNullTo(mutableSetOf()) { it.mountScope })
    }

    /**
     * [T-mcp-close-async] Recycle one detached client on the reaper instead of the
     * calling thread. `McpStdioClient.close()` takes the same monitor as
     * `ensureInitialized()`, which holds it across `initialize` for up to the startup
     * timeout (60s by default), so a synchronous close turns a config change /
     * invalidate / rebuild into a multi-second (worst case minute-long) stall on the MCP
     * call thread. The caller has already detached the cache entry, so no later call can
     * reach this client; deferring only means the guest process lives a little longer.
     *
     * [T-mcp-lease-guard] 调度本身不许抛：调用点之一在 [clientFor] 的**在途登记之后**，
     * 一旦从这里抛出去，调用方就拿不到 Lease，`withClient` 的 finally 也就无从释放 ——
     * 在途计数永久 +1，那个作用域再也不可能被淘汰。所以 launch 失败只记日志并退化成
     * 同步 close（调用线程本来就必须是 Dispatchers.IO，见文件头）：宁可这条"不该发生"的
     * 路径慢一点，也不能把 guest 进程泄漏掉。
     */
    private fun recycleAsync(what: String, client: McpClient) {
        val scheduled = runCatching {
            closerScope.launch {
                runCatching { client.close() }
                    .onFailure { AppLogger.warning(TAG, "close($what): ${it.message}") }
            }
        }
        if (scheduled.isFailure) {
            AppLogger.warning(
                TAG,
                "reaper unavailable, closing $what inline: ${scheduled.exceptionOrNull()?.message}",
            )
            runCatching { client.close() }
                .onFailure { AppLogger.warning(TAG, "close($what): ${it.message}") }
        }
    }

    /**
     * [T-mcp-session-bind] Enforce the per-scope process bound: every scope in the
     * scope table may hold one live guest process per stdio server, so scopes
     * beyond [McpStdioScopePolicy.MAX_SCOPES] are closed oldest-first. Scopes with
     * a call in flight, and scopes idle for less than the policy's floor, are never
     * picked (see [McpStdioScopePolicy.victims]); the bound is therefore
     * best-effort rather than a hard process cap.
     *
     * [T-mcp-inflight-lease] The victim list is a *snapshot*: a call may take a lease
     * on one of these scopes while we walk the list, so the detach re-checks the
     * in-flight count in the same critical section as the lease registration
     * ([detachScope] with `onlyIfIdle`). A scope that got leased in between is skipped
     * entirely instead of having its process closed under the caller.
     *
     * The entry is detached synchronously (so a client built right after cannot be
     * caught by this close) while the process is reaped on an IO thread: the close
     * does `destroy()` + `waitFor(2s)`, which must not be pushed onto the call that
     * triggered the eviction. Same shape as [closeSession].
     */
    private fun evictExcessScopes(keep: String) {
        val victims = McpStdioScopePolicy.victims(
            lastUsedMs = scopeLeases.lastUsedSnapshot(),
            keep = keep,
            nowMs = SystemClock.elapsedRealtime(),
            // Live key set: an entry only exists while a call is running.
            busyScopes = scopeLeases.busyScopes(),
        )
        for (scope in victims) {
            val detached = detachScope(scope, onlyIfIdle = true)
            if (detached.isEmpty()) continue
            AppLogger.info(TAG, "closing stdio MCP clients of idle mount scope '$scope'")
            for (client in detached) recycleAsync("scope $scope", client)
        }
    }

    // -- client construction ---------------------------------------------------

    private fun resolveConfig(
        repo: MCPRepository,
        sessionId: String,
        server: String,
    ): MCPServerConfig {
        val overrides = repo.effectiveEnabledMap(sessionId)
        val cfg = repo.servers.value.firstOrNull { it.id == server }
            ?: throw McpError(
                "NOT_FOUND",
                "No MCP server named '$server'. Configured: " +
                    repo.servers.value.joinToString(", ") { it.id }.ifEmpty { "(none)" },
            )
        val enabled = overrides[cfg.id] ?: cfg.enabled
        if (!enabled) {
            throw McpError(
                "NOT_FOUND",
                "MCP server '$server' is disabled" +
                    (if (overrides.containsKey(cfg.id)) " for this session" else " in Settings → MCP") + ".",
            )
        }
        return cfg
    }

    private fun fingerprint(cfg: MCPServerConfig): String = listOf(
        cfg.isStdio.toString(), cfg.url, cfg.command, cfg.args.toString(),
        cfg.env.toSortedMap().toString(), cfg.headers.toSortedMap().toString(),
        cfg.oauth?.clientId, cfg.oauth?.mode, cfg.startupTimeoutSeconds,
    ).joinToString("|")

    /**
     * Cache-key fragment for the built-in-server wiring (see
     * [BuiltinLoopbackResolver]): the effective URL while wired,
     * "builtin-stopped" while the entry targets the built-in port but the
     * server is down, and "external" otherwise. Any transition rebuilds
     * the client.
     */
    private fun loopbackFingerprint(context: Context, cfg: MCPServerConfig): String {
        if (cfg.isStdio) return "no-url"
        val raw = cfg.url ?: return "no-url"
        val expanded = runCatching { McpJsonRpc.expandEnv(raw, envResolver(context)) }.getOrDefault(raw)
        return when (val w = builtinWiring(expanded)) {
            is BuiltinLoopbackResolver.Outcome.Wired -> "wired:${w.effectiveUrl}"
            BuiltinLoopbackResolver.Outcome.ServerNotRunning -> "builtin-stopped"
            BuiltinLoopbackResolver.Outcome.NotBuiltin -> "external"
        }
    }

    /** Current wiring outcome for an env-expanded URL against the built-in MCP server. */
    private fun builtinWiring(url: String): BuiltinLoopbackResolver.Outcome =
        BuiltinLoopbackResolver.resolve(
            url = url,
            configuredPort = McpServerManager.currentPort(),
            actualPort = McpServerManager.actualPort(),
            running = McpServerManager.state.value.status == McpServerManager.Status.RUNNING,
        )

    /**
     * One call's hold on a client: the client, its server config, the stdio mount
     * scope it is bound to (null for HTTP, which holds no process and no mounts),
     * whether the caller must recycle it when done ([closeAfterUse] — only the
     * private preview-scope client, see [clientFor]), and whether this lease is
     * already counted as in-flight in [scopeLeases] ([registered] — the stdio paths
     * register inside [clientFor], so [withClient] only has to release it).
     */
    private class Lease(
        val client: McpClient,
        val cfg: MCPServerConfig,
        val scope: String?,
        val closeAfterUse: Boolean = false,
        val registered: Boolean = false,
    )

    private fun clientFor(
        context: Context,
        repo: MCPRepository,
        sessionId: String,
        server: String,
    ): Lease {
        val cfg = resolveConfig(repo, sessionId, server)
        // Include the built-in loopback wiring in the cache key: a port hop
        // or the server starting/stopping must rebuild the client, not reuse
        // a stale URL or a client without the built-in token.
        val fp = fingerprint(cfg) + "|" + loopbackFingerprint(context, cfg)
        // [T-mcp-session-bind] A stdio client is a long-lived guest process whose
        // `-b` argv was fixed when it spawned, so the calling session's mount scope
        // is part of its identity: session B must get its OWN process, not the one
        // session A started. Before this, the spawn spec read the process-wide
        // (last-writer-wins) PRootKernel.bindMounts, so B's `/var/minis/workspace`
        // and `/var/minis/memory` pointed into A's chat directory.
        val scope = McpStdioScopePolicy.scopeOf(sessionId)
        // [T-mcp-preview-scope] The Settings panel's synthetic scope is not a
        // session: no `sessionDidTerminate` will ever call closeSession for it, it
        // must not occupy one of the MAX_SCOPES slots that belong to real chats, and
        // it must not leave a fake session tree behind (see [buildSpawnSpec]). So it
        // bypasses the cache entirely: one private process per call, recycled by
        // [withClient]'s finally — the recycling is explicit and runs on the error
        // path too.
        if (cfg.isStdio && McpStdioScopePolicy.isPreviewScope(scope)) {
            reapPreviewDirs(context)
            return Lease(buildClient(context, cfg, scope), cfg, scope = null, closeAfterUse = true)
        }
        val key = CacheKey(cfg.isStdio, if (cfg.isStdio) scope else null, cfg.id)
        val leaseScope = if (cfg.isStdio) scope else null
        // [T-mcp-inflight-lease] Cache hit for a stdio scope: 取用与在途登记必须是一次
        // 原子操作（租约即登记）。旧实现把 beginScopeCall 放在本函数返回之后，于是
        // evictExcessScopes 的 victims 快照算完、detachScope 之前到达的同作用域调用只
        // 刷新了时间戳、还没进在途集合 —— 它命中的进程被摘掉并 close，调用方拿到
        // CONNECTION_ERROR。HTTP 无进程无挂载，不参与作用域记账。
        val hit: McpClient? = if (cfg.isStdio) {
            scopeLeases.acquire(scope, SystemClock.elapsedRealtime()) {
                clients[key]?.takeIf { it.fingerprint == fp }?.client
            }
        } else {
            clients[key]?.takeIf { it.fingerprint == fp }?.client
        }
        if (hit != null) return Lease(hit, cfg, leaseScope, registered = cfg.isStdio)
        // Config changed (or first use): rebuild. [T-mcp-no-callsite] A dead stdio
        // process does NOT arrive here through invalidate() — that entry has no
        // production call site (see [invalidate]); a crashed guest server is restarted
        // in place by `McpStdioClient.ensureInitialized()`'s `alive()` check.
        val client = buildClient(context, cfg, scope)
        val mine = Cached(fp, client)
        // [T-mcp-close-async] 被顶替的旧进程不在调用线程上 close：`McpStdioClient.close()`
        // 与 `ensureInitialized()` 争同一把锁（initialize 最长 60s），配置变更重建时同步
        // close 会把这次调用一起压住。摘除缓存条目仍然是同步的（见下面的安装/接管）。
        var superseded: McpClient? = null
        val leased: McpClient = if (cfg.isStdio) {
            // [T-mcp-inflight-lease] 安装/接管 + 登记在途同样在一次临界区内完成：这样
            // "在途则整项跳过"的淘汰摘除要么看见这次调用（我们已经在集合里），要么已经
            // 结束（我们写在它之后，新进程不在它的摘除集合里）。若只在这里 putIfAbsent、
            // 之后再登记，淘汰就可能正好把刚装好的进程摘走。
            scopeLeases.publish(scope, SystemClock.elapsedRealtime()) {
                val prev = clients[key]
                when {
                    // Rebuilds race (two parallel MCP calls in a fresh session): when
                    // another call won with the SAME config, drop the process this call
                    // started (nobody has used it yet) and serve from the winner — which
                    // is never closed out from under an in-flight call.
                    prev == null -> {
                        clients[key] = mine
                        client
                    }
                    prev.fingerprint == fp -> {
                        superseded = client
                        prev.client
                    }
                    // Config changed inside the race window: this call holds the newer
                    // build; the entry this call replaces is dead either way.
                    else -> {
                        clients[key] = mine
                        superseded = prev.client
                        client
                    }
                }
            }
        } else {
            val raced = clients.putIfAbsent(key, mine)
            when {
                raced == null -> client
                raced.fingerprint == fp -> {
                    superseded = client
                    raced.client
                }
                else -> {
                    clients[key] = mine
                    superseded = raced.client
                    client
                }
            }
        }
        // [T-mcp-lease-guard] 在途登记（上面的 acquire/publish）之后的收尾整段纳入兜底范围：
        // 这一段一旦抛出，本函数就拿不到 Lease 返回，withClient 的 finally 也就无从释放 ——
        // 在途计数永久 +1，该作用域再也不可能被淘汰（连挂起的回收动作也永远收不掉）。
        // 回收/淘汰本身是尽力而为：失败只记日志（"这一轮不回收/不淘汰"），下一个调用或会话
        // 结束照常回收，绝不能让它变成一次调用方看不见的计数泄漏。
        try {
            superseded?.let { retired ->
                val what = key.toString()
                // [T-mcp-supersede-lease] 被顶替的 client 可能正被同作用域的并发调用使用：
                // 并行 subagent lane 与父会话共用一个作用域，指纹一变（配置 / loopback 接线
                // 变化）就走"换缓存条目"这条路，而在途簿记按作用域、不按 client —— 只要还有
                // 别的调用在跑，那个进程就可能正在为它等回复，此刻 close 就是 destroy 掉它，
                // 调用方拿到 TIMEOUT / CONNECTION_ERROR。所以先过 deferCloseIfBusy：忙则挂起
                // （由最后一个 release 交回回收），空闲才立刻回收。own = 1 是本调用刚在
                // publish 里登记的那一次 —— 它拿的是新条目（或并发赢家），不可能持有被顶替的
                // 这个 client，要从计数里扣掉。
                val deferred = leaseScope != null &&
                    scopeLeases.deferCloseIfBusy(leaseScope, own = 1) { recycleAsync(what, retired) }
                if (!deferred) recycleAsync(what, retired)
            }
            if (cfg.isStdio) {
                // [T-mcp-inflight-lease] 淘汰必须在登记之后（否则它自己刚装好的进程会被上一轮
                // 决策摘走）。
                evictExcessScopes(keep = scope)
            }
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "post-registration bookkeeping failed for '$scope': ${e.message}")
        }
        return Lease(leased, cfg, leaseScope, registered = cfg.isStdio)
    }

    private fun buildClient(context: Context, cfg: MCPServerConfig, mountScope: String): McpClient {
        val resolve = envResolver(context)
        return if (!cfg.isStdio) {
            var url = cfg.url?.let { McpJsonRpc.expandEnv(it, resolve) }
                ?: throw McpError(McpError.PROTOCOL_ERROR, "http server '${cfg.id}' has no url")
            val headers = cfg.headers.mapValues { (_, v) -> McpJsonRpc.expandEnv(v, resolve) }
            // [T-mcp-loopback-wiring] An entry pointing at the built-in
            // server's configured loopback port follows the port the server
            // actually bound (it falls back when the port is squatted) and
            // carries the built-in bearer token — no hand-copied headers.
            val builtin = builtinWiring(url)
            when (builtin) {
                is BuiltinLoopbackResolver.Outcome.Wired -> url = builtin.effectiveUrl
                BuiltinLoopbackResolver.Outcome.ServerNotRunning -> throw McpError(
                    McpError.CONNECTION_ERROR,
                    "built-in MCP server is not running — enable it in Settings → MCP, then retry",
                )
                BuiltinLoopbackResolver.Outcome.NotBuiltin -> Unit
            }
            McpHttpClient(
                cfg.id,
                url,
                headers,
                if (builtin is BuiltinLoopbackResolver.Outcome.Wired) {
                    // Live read: a token regenerated in Settings applies to
                    // the next request without rebuilding the client.
                    { McpServerManager.configStore()?.currentToken() }
                } else {
                    oauthTokenProvider(context, cfg) ?: { null }
                },
                oauthConfigured = cfg.oauth != null && builtin !is BuiltinLoopbackResolver.Outcome.Wired,
                http = httpClient,
            )
        } else {
            val command = cfg.command?.takeIf { it.isNotBlank() }
                ?: throw McpError(McpError.PROTOCOL_ERROR, "stdio server '${cfg.id}' has no command")
            McpStdioClient(
                cfg.id,
                buildSpawnSpec(context, cfg, command, resolve, mountScope),
                startupTimeoutMs = (cfg.startupTimeoutSeconds ?: DEFAULT_STARTUP_TIMEOUT_SECONDS) * 1000L,
            )
        }
    }

    /**
     * Proot argv + environments for a guest stdio server. Mirrors
     * PersistentShell.startProcess for the proot prefix/env, and daemon.py
     * `MCPStdioServer.start` for the server-side env overlay. The command is
     * exec'd through `sh -c 'exec "$0" "$@"'` so PATH resolution happens
     * inside the rootfs (npx/python/uvx…), with no shell quoting hazards.
     *
     * [T-mcp-session-bind] `mountScope` (the calling session's host) supplies the
     * bind mounts, and `publish = false` keeps this spawn from writing them into the
     * shared table. Reading `PRootKernel.bindMounts` here was the bug: the table is
     * last-writer-wins, so this process got whichever chat built a shell most
     * recently and could read that chat's workspace/attachments/memory.
     *
     * [T-mcp-preview-scope] [T-mcp-preview-mounts] A synthetic scope
     * ([McpStdioScopePolicy.PREVIEW_SCOPE]) has no session to bind, so it gets the
     * three session-independent sources — the app-global directories
     * (`/var/minis/{skills,shared,mcp-servers}`), the user's SAF external mounts
     * (`/var/minis/mounts/<name>`) and shared storage — but **no session tree**:
     * nothing under `/var/minis/{workspace,attachments,offloads,browser,memory}` is
     * derived for it (so nothing is `mkdirs()`-ed on disk either), which is what keeps
     * the preview process from reading any chat's data. A real session gets those same
     * three sources *plus* its session tree (see
     * [ExecutionCoordinator.buildSessionBindMounts]); the exact preview set and its
     * merge precedence live in [previewBindMounts] and
     * [McpStdioScopePolicy.mergePreviewBinds].
     */
    private fun buildSpawnSpec(
        context: Context,
        cfg: MCPServerConfig,
        command: String,
        resolve: (String) -> String?,
        mountScope: String,
    ): McpStdioClient.SpawnSpec {
        if (!PRootKernel.isBooted) {
            throw McpError(
                McpError.CONNECTION_ERROR,
                "Sandbox not booted — run any shell_execute command once, then retry the MCP call.",
            )
        }
        val rm = RootfsManager.getInstance(context)
        val argv = mutableListOf(
            rm.prootBinary.absolutePath,
            "-0", "--link2symlink", "--kill-on-exit",
            "-r", rm.rootfsDir.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-w", "/root",
        )
        val sessionBinds = if (McpStdioScopePolicy.isPreviewScope(mountScope)) {
            previewBindMounts(context)
        } else {
            ExecutionCoordinator.sessionBindMounts(mountScope, publish = false)
        }
        for ((linuxPath, hostPath) in sessionBinds) {
            argv.add("-b")
            argv.add("$hostPath:$linuxPath")
        }
        argv.addAll(listOf("/bin/sh", "-c", "exec \"\$0\" \"\$@\"", command))
        argv.addAll(cfg.args)

        val processEnv = buildMap {
            put("PROOT_TMP_DIR", PRootKernel.getProotTmpDir(context).absolutePath)
            if (PRootKernel.nativeLibDir.isNotEmpty()) put("LD_LIBRARY_PATH", PRootKernel.nativeLibDir)
            if (PRootKernel.prootLoaderPath.isNotEmpty()) put("PROOT_LOADER", PRootKernel.prootLoaderPath)
            if (PRootKernel.prootLoader32Path.isNotEmpty()) put("PROOT_LOADER_32", PRootKernel.prootLoader32Path)
        }
        val guestEnv = buildMap {
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("HOME", "/root")
            put("TMPDIR", "/tmp")
            put("LANG", "C.UTF-8")
            put("TERM", "dumb")
            for ((k, v) in cfg.env) put(k, McpJsonRpc.expandEnv(v, resolve))
        }
        return McpStdioClient.SpawnSpec(argv, processEnv, guestEnv)
    }

    /**
     * [T-mcp-preview-scope] `-b` set for the Settings panel's synthetic preview
     * scope. 能力边界就是下面这三类，一个不多一个不少，而且**三类都与会话无关**：
     *  1. app 全局目录 `/var/minis/{skills,shared,mcp-servers}`（`servers.json` 就在最后一个
     *     里面）；
     *  2. 用户 SAF 外部挂载 `/var/minis/mounts/<name>`；
     *  3. 共享存储（`/sdcard`、`/storage/...`）。
     *
     * 这条边界的安全含义：预览进程因此能读到"和任何会话都一样能读到的东西"，但读不到任何
     * 聊天的会话目录（workspace/attachments/offloads/browser/memory）。任何按会话派生的路径
     * 都不许从这里流出去 —— 见下面的 [T-mcp-preview-mounts]。
     *
     * Going through [ExecutionCoordinator.sessionBindMounts] instead would derive
     * per-session paths for a session id that does not exist and `mkdirs()` them, so
     * simply opening the tools sheet left an empty `filesDir/minis-sessions/<scope>/`
     * tree behind. The globals are app-owned and hold no chat data, so the preview
     * process can neither litter nor read another session's files.
     *
     * [T-mcp-preview-mounts] 能力边界 = 全局目录 + 用户 SAF 外部挂载 + 共享存储，**没有**
     * 会话目录（workspace/attachments/offloads/browser/memory）。这三类来源都是会话无关的
     * （全局目录由 app 所有，用户挂载与共享存储是用户自己的存储、对每个会话都可见），挂上
     * 它们不引入任何新的可见性；漏挂用户挂载
     * (`/var/minis/mounts/<name>`) 却会让"从挂载目录读配置"的服务器在预览里行为与真会话不一致
     * （`ls /var/minis/mounts/<name>/` 只看到 rootfs 里的空占位目录）。合并规则与优先级在
     * [McpStdioScopePolicy.mergePreviewBinds] 里，和真会话的
     * `ExecutionCoordinator.buildSessionBindMounts` 同源。
     *
     * proot needs both sides of `-b host:guest` to exist (the same precondition
     * `ExecutionCoordinator`'s session bind builder documents), hence the `mkdirs()` —
     * these are the directories `MCPRepository` already writes into, not a fake session.
     * The user-mount side is created by `PRootKernel.applyMountedFoldersSnapshot`
     * (host placeholders and rootfs targets), same as for a real session.
     */
    private fun previewBindMounts(context: Context): Map<String, String> {
        val globalBase = File(context.filesDir, SessionWorkspace.GLOBAL_DIR)
        val globalBinds = linkedMapOf<String, String>()
        for (subdir in SessionWorkspace.GLOBAL_BIND_SUBDIRS) {
            val host = File(globalBase, subdir)
            if (!host.isDirectory && !host.mkdirs()) continue
            globalBinds["/var/minis/$subdir"] = host.absolutePath
        }
        return McpStdioScopePolicy.mergePreviewBinds(
            globalBinds = globalBinds,
            userMountBinds = userMountBinds(),
            sharedStorageBinds = PRootKernel.shellSharedStorageBinds(context.applicationContext, publish = false),
        )
    }

    /**
     * [T-mcp-preview-mounts] `/var/minis/mounts/<name>` → host path for the user's SAF
     * mounts (Settings → Mount External Folders), read from the same store and with the
     * same rule as `ExecutionCoordinator.buildSessionBindMounts`: entries whose tree URI
     * did not decode to a POSIX path (cloud providers, unmounted removable storage) are
     * skipped, because PRoot can only bind a real host directory.
     */
    private fun userMountBinds(): Map<String, String> = buildMap {
        PRootKernel.mountedFoldersStore?.entries?.value?.forEach { entry ->
            val host = entry.resolvedHostPath ?: return@forEach
            put("/var/minis/mounts/${entry.name}", host)
        }
    }

    /**
     * [T-mcp-preview-scope] Reclaim the empty session trees older builds left for the
     * synthetic scope (the `mkdirs = true` side effect of deriving session mounts for
     * it). Only empty directories are removed — anything a preview server wrote is
     * kept, which is why this delegates to [McpStdioScopePolicy.reapEmptyDirs].
     */
    private fun reapPreviewDirs(context: Context) {
        runCatching {
            McpStdioScopePolicy.reapEmptyDirs(
                File(context.filesDir, "${SessionWorkspace.SESSIONS_DIR}/${McpStdioScopePolicy.PREVIEW_SCOPE}"),
            )
        }.onSuccess { removed ->
            if (removed > 0) {
                AppLogger.info(TAG, "reclaimed $removed empty dir(s) of the MCP preview scope")
            }
        }
    }

    // -- OAuth ------------------------------------------------------------------

    /**
     * Bearer provider for OAuth-enabled servers: current access token, or a
     * natively refreshed one when the stored grant expired (60s early margin,
     * same as the CLI). Null when unauthorized/expired-unrefreshable — the
     * request then goes out bare and the server's 401 maps to AUTH_REQUIRED.
     */
    private fun oauthTokenProvider(context: Context, cfg: MCPServerConfig): (() -> String?)? {
        if (cfg.oauth == null) return null
        val appContext = context.applicationContext
        return provider@{
            val tokens = MCPOAuthStore.tokens(appContext, cfg.id) ?: return@provider null
            val now = System.currentTimeMillis()
            if (tokens.expiresAtMs <= 0 || now < tokens.expiresAtMs - 60_000) {
                tokens.accessToken
            } else {
                refreshTokens(appContext, cfg, tokens)?.accessToken
            }
        }
    }

    /**
     * refresh_token grant against the server's token endpoint. On success the
     * store AND the guest token bridge are updated (the bridge write merges
     * refresh tokens, so a response without one never downgrades the CLI's
     * copy). Best-effort: any failure returns null → AUTH_REQUIRED surfaces.
     */
    private fun refreshTokens(
        context: Context,
        cfg: MCPServerConfig,
        tokens: MCPOAuthStore.StoredTokens,
    ): MCPOAuthStore.StoredTokens? {
        val oauth = cfg.oauth ?: return null
        val refreshToken = tokens.refreshToken ?: return null
        if (oauth.tokenEndpoint.isBlank() || oauth.clientId.isBlank()) return null
        return try {
            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", oauth.clientId)
            MCPOAuthStore.clientSecret(context, cfg.id)?.let { form.add("client_secret", it) }
            // RFC 8707 resource indicator: reuse whatever the authorize flow
            // recorded in the bridge file (the store has no resource field).
            bridgeResource(context, cfg.id)?.let { form.add("resource", it) }
            val request = okhttp3.Request.Builder()
                .url(oauth.tokenEndpoint)
                .post(form.build())
                .build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    AppLogger.warning(TAG, "[${cfg.id}] token refresh HTTP ${resp.code}")
                    return null
                }
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                val access = json.optString("access_token", "")
                if (access.isEmpty()) return null
                val expiresIn = json.optLong("expires_in", 0L)
                val updated = MCPOAuthStore.StoredTokens(
                    accessToken = access,
                    refreshToken = json.optString("refresh_token", "").ifBlank { refreshToken },
                    expiresAtMs = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else 0L,
                )
                MCPOAuthStore.setTokens(context, cfg.id, updated)
                runCatching {
                    MCPTokenBridge.write(
                        context, cfg.id, updated, oauth.tokenEndpoint,
                        oauth.clientId, MCPOAuthStore.clientSecret(context, cfg.id),
                        bridgeResource(context, cfg.id),
                    )
                }
                AppLogger.info(TAG, "[${cfg.id}] access token refreshed natively")
                updated
            }
        } catch (e: Exception) {
            AppLogger.warning(TAG, "[${cfg.id}] token refresh failed: ${e.message}")
            null
        }
    }

    private fun bridgeResource(context: Context, server: String): String? = runCatching {
        JSONObject(File(MCPTokenBridge.oauthDir(context), "$server.json").readText())
            .optString("resource", "").ifBlank { null }
    }.getOrNull()
}
