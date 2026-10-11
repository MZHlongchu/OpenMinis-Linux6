package com.openminis.app.sandbox

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.sync.withLock
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.data.MountedFoldersStore
import java.io.File
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * PRoot configuration holder and command builder.
 * Corresponds to iOS ISHKernel — but since PRoot is process-per-command
 * (not a persistent kernel), this object holds configuration state
 * and builds proot command lines.
 */
object PRootKernel {

    private const val TAG = "PRootKernel"

    var isBooted: Boolean = false
        private set

    /** Path to native library directory (for LD_LIBRARY_PATH). */
    var nativeLibDir: String = ""
        private set

    /** Path to PRoot loader binary (64-bit). */
    var prootLoaderPath: String = ""
        private set

    /** Path to PRoot loader binary (32-bit). */
    var prootLoader32Path: String = ""
        private set

    private var rootfsManagerRef: RootfsManager? = null
    private val rootfsManager: RootfsManager get() = checkNotNull(rootfsManagerRef) { "PRootKernel.boot must be called first" }

    /**
     * [T-android-env-first-turn] Guards [boot]. See the comment there: the
     * `isBooted` flag alone is a check-then-act race because it flips only
     * after the slow install work has finished.
     */
    private val bootMutex = kotlinx.coroutines.sync.Mutex()

    /** Custom environment variables injected into every proot command. */
    val customEnvironment: MutableMap<String, String> = mutableMapOf()

    /**
     * Bind mounts: Linux path -> host filesystem path. GLOBAL mounts only —
     * `memory` is per-session and therefore NOT here; the global set is
     * `skills`, `shared`, `mcp-servers` and `mounts/<name>`
     * ([SessionWorkspace.GLOBAL_BIND_SUBDIRS]).
     *
     * [T-android-session-private-mounts] Per-session directories (workspace,
     * attachments, offloads, browser, memory) must never be in here: this map is
     * process-wide, so a session dir written into it is whichever session
     * wrote last. Those mounts live in each shell's own argv
     * ([SessionMounts]); [addBindMount] refuses them, and readers with no
     * session id fall back to the explicit [legacySessionId] hint.
     *
     * [T-b4-#2] 仍然用 ConcurrentHashMap：这张表会被主线程
     * （TerminalSession.buildTermuxArgs、WebAppPathResolver.inferScope）与 IO 线程
     * （建壳、applyMountedFoldersSnapshot）同时遍历/写入。原来的 linkedMapOf 会让
     * 遍历方抛 CME（Compose 路径上就是闪退），排序后按键取值还可能 NPE。
     * 插入顺序从来不是契约的一部分：需要顺序的调用方自行排序
     * （如 [resolveHostPath] 按 key 长度降序）。
     */
    val bindMounts: MutableMap<String, String> = ConcurrentHashMap()

    /**
     * [T-android-session-private-mounts] LEGACY fallback for readers that
     * resolve a per-session `/var/minis/...` path with no session id through
     * [resolveHostPath]. It reproduces exactly what they got before (the
     * session that most recently built a shell), but as an explicit, logged
     * hint instead of a side effect on the mount table. Session-aware callers
     * use [resolveSessionHostPath] and never read it.
     */
    @Volatile
    private var legacySessionId: String? = null

    @Volatile
    private var legacyFilesDir: File? = null

    fun noteLegacySession(context: Context, sessionId: String) = noteLegacySession(context.filesDir, sessionId)

    internal fun noteLegacySession(filesDir: File, sessionId: String) {
        legacyFilesDir = filesDir
        legacySessionId = sessionId
    }

    /** How many times the legacy fallback answered (diagnostics / tests). */
    @Volatile
    var legacyResolveCount: Long = 0
        private set


    /**
     * Initialize the PRoot environment: install rootfs and proot binary.
     */
    suspend fun boot(context: Context) {
        if (isBooted) {
            Log.d(TAG, "Already booted")
            return
        }
        // [T-android-env-first-turn] Serialize boot. `isBooted` is only set at
        // the END of this function, so a bare check-then-act let a second
        // caller walk straight past it while the first was still suspended
        // inside installIfNeeded(). Both then raced on, and whoever spawned a
        // shell first did it with nativeLibDir/prootLoaderPath still empty —
        // proot then bare-execve'd the rootfs busybox and SELinux killed it
        // with `execve("/bin/sh"): Permission denied`.
        bootMutex.withLock {
            // Re-check under the lock: the caller we queued behind may have
            // completed the boot while we waited.
            if (isBooted) {
                Log.d(TAG, "Already booted (post-lock)")
                return@withLock
            }
            bootLocked(context)
        }
    }

    private suspend fun bootLocked(context: Context) {
        // [T-b4-fail-closed-rootfs] 赋给 rootfsManagerRef：rootfsManager 是带
        // checkNotNull 的 val getter（未 boot 就取用会给出可读错误），不能直接赋值。
        rootfsManagerRef = RootfsManager.getInstance(context)
        rootfsManager.installIfNeeded()
        rootfsManager.installProotIfNeeded()

        // Overlay assets/default_mount/ onto the rootfs on every boot so
        // updated profile scripts and URL-interception wrappers ship with
        // each app update. Mirrors iOS RootfsManager.applyDefaultMountOverlay.
        rootfsManager.applyDefaultMountOverlay()
        rootfsManager.applyHostTimezone()

        // Re-apply user mirror selections — the overlay above ships stock
        // config files (pip.conf, .npmrc, repositories) and would otherwise
        // revert user-chosen mirrors on every boot. Mirrors iOS ordering in
        // ISHTerminalView.startShell().
        com.openminis.app.ui.sandbox.MirrorSpeedTestViewModel.applyAllActiveMirrors(context)
        com.openminis.app.ui.sandbox.MirrorSpeedTestViewModel.autoDetectOnceIfNeeded(context)

        // Off the critical boot path: heal mirrors first so a dead apt
        // source cannot burn dpkg/pip retry strikes, then retry failed
        // restores, then snapshot. One IO job so these never interleave
        // (they also serialize on RootfsManager.aptMutex).
        (context.applicationContext as? com.openminis.app.MinisApp)
            ?.appCoroutineScopes?.io?.launch {
            runCatching { rootfsManager.runMinisMirrorAuto() }
                .onFailure { Log.e(TAG, "[boot] mirror auto failed", it) }
            runCatching { rootfsManager.seedNetworkTools() }
                .onFailure { Log.e(TAG, "[boot] seed network tools failed", it) }
            runCatching { rootfsManager.retryFailedDpkgWorld() }
                .onFailure { Log.e(TAG, "[boot] retry dpkg world failed", it) }
            runCatching { rootfsManager.retryFailedPipWorld() }
                .onFailure { Log.e(TAG, "[boot] retry pip world failed", it) }
            runCatching { rootfsManager.dumpDpkgWorld() }
                .onFailure { Log.e(TAG, "[boot] dump dpkg world failed", it) }
            runCatching { rootfsManager.dumpPipWorld() }
                .onFailure { Log.e(TAG, "[boot] dump pip world failed", it) }
        }

        // Refresh DNS from system (mirrors iOS ISHKernel.configureDns)
        rootfsManager.refreshDns()

        // LD_LIBRARY_PATH for the extracted native libs. talloc used to be
        // staged here under a versioned name; deps/build_proot.sh now links it
        // statically, so only the native lib dir is needed.
        nativeLibDir = rootfsManager.nativeLibDir.absolutePath

        // PROOT_LOADER / PROOT_LOADER_32 overrides. The loader is bundled into
        // the proot binary (extracted via /proc/self/fd at runtime), so these
        // files normally do not exist — kept only to honour a side-loaded
        // loader if one is present.
        val loaderPath = File(rootfsManager.nativeLibDir, "libproot-loader.so")
        val loader32Path = File(rootfsManager.nativeLibDir, "libproot-loader32.so")
        if (loaderPath.exists()) prootLoaderPath = loaderPath.absolutePath
        if (loader32Path.exists()) prootLoader32Path = loader32Path.absolutePath

        // Set default PATH for Ubuntu Linux
        customEnvironment.putIfAbsent(
            "PATH",
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/bin:" +
                "/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:" +
                "/opt/android-sdk/build-tools/35.0.2:/opt/android-sdk/cmake/3.22.1/bin:/opt/gradle/bin",
        )
        customEnvironment.putIfAbsent("DEBIAN_FRONTEND", "noninteractive")
        // pip/python block-buffer on a pipe. Without this, a package install
        // prints nothing until the process exits.
        customEnvironment.putIfAbsent("PYTHONUNBUFFERED", "1")
        customEnvironment.putIfAbsent("SHELL", "/bin/bash")
        customEnvironment.putIfAbsent("ANDROID_HOME", "/opt/android-sdk")
        customEnvironment.putIfAbsent("ANDROID_SDK_ROOT", "/opt/android-sdk")
        customEnvironment.putIfAbsent("HOME", "/root")

        // URL interception: seed $BROWSER directly into every process envp
        // so non-login shells (which never source /etc/profile.d/minis.sh)
        // still route webbrowser.open()/etc into the host OpenOffloadHandler.
        // Mirrors iOS ISHShellExecutor.m:333.
        customEnvironment["BROWSER"] = "/usr/local/bin/minis-open"   // T195: force override; user dotfile BROWSER= would otherwise win

        // ash-specific: ENV points at a file the shell sources on startup.
        // Our /etc/profile sources /etc/profile.d/*.sh, so non-login shells
        // (PersistentShell uses plain `/bin/sh`) still pick up HISTFILE,
        // aliases, and the rest of the shipped profile. Mirrors iOS
        // ISHShellExecutor.m:326.
        customEnvironment.putIfAbsent("ENV", "/etc/profile")

        // Character-set declaration for tools that probe $CHARSET instead of
        // $LANG (e.g. some ncurses builds, older Python scripts). iOS sets
        // this in ISHShellExecutor.m:325.
        customEnvironment.putIfAbsent("CHARSET", "UTF-8")

        // Tool-compat env — match iOS ISHShellExecutor.m:358-360 so scripts
        // that run identically in both sandboxes produce identical output.
        // NO_COLOR: suppress ANSI color in shell tools that honor the
        //   informal NO_COLOR standard (curl, ls --color=auto, etc.).
        // PYTHONDONTWRITEBYTECODE: don't litter the rootfs with .pyc files
        //   next to user scripts.
        // GOMAXPROCS=2: keep Go runtimes modest on mobile; iOS caps at 2
        //   for the iSH emulator, same cap is a sensible default under
        //   PRoot on Android where the sandbox is also IO-bound.
        customEnvironment.putIfAbsent("NO_COLOR", "1")
        customEnvironment.putIfAbsent("PYTHONDONTWRITEBYTECODE", "1")
        customEnvironment.putIfAbsent("GOMAXPROCS", "2")

        // T222: PRoot's link2symlink extension creates .l2s.* sentinel files
        // alongside every hardlinked file. uv's default `hardlink` mode tries
        // to re-link these sentinels when populating site-packages from cache,
        // and PRoot rejects the second link with EPERM. Force uv to symlink
        // package files instead so the sentinels are never used as link
        // sources. Reported as openminis/openminis#7.
        // Mirrored in default_mount/etc/profile.d/minis.sh for login shells.
        customEnvironment.putIfAbsent("UV_LINK_MODE", "symlink")

        // ProcessBuilder inherits Android's TMPDIR (typically
        // /data/user/0/<pkg>/cache). PRoot treats that as a *guest* path, so
        // it either does not exist or is the host cache Android may have
        // wiped. dpkg/apt then fail unpacking — first seen installing
        // ca-certificates. Always pin guest temp to /tmp. Mirrored in
        // default_mount/etc/profile.d/minis.sh and the setup scripts.
        customEnvironment["TMPDIR"] = "/tmp"
        customEnvironment["TMP"] = "/tmp"
        customEnvironment["TEMP"] = "/tmp"

        // Guest TLS: point every common client at the Android-injected bundle
        // so HTTPS works even when ubuntu-base CA hash-symlinks did not extract.
        customEnvironment["SSL_CERT_FILE"] = "/etc/ssl/certs/ca-certificates.crt"
        customEnvironment["SSL_CERT_DIR"] = "/etc/ssl/certs"
        customEnvironment["CURL_CA_BUNDLE"] = "/etc/ssl/certs/ca-certificates.crt"
        customEnvironment["REQUESTS_CA_BUNDLE"] = "/etc/ssl/certs/ca-certificates.crt"
        customEnvironment["GIT_SSL_CAINFO"] = "/etc/ssl/certs/ca-certificates.crt"
        customEnvironment["PIP_CERT"] = "/etc/ssl/certs/ca-certificates.crt"
        customEnvironment["NODE_EXTRA_CA_CERTS"] = "/etc/ssl/certs/ca-certificates.crt"
        customEnvironment["NODE_OPTIONS"] = GuestLimits.nodeOptions(customEnvironment["NODE_OPTIONS"])
        customEnvironment["BASH_ENV"] = "/etc/profile.d/minis-limits.sh"

        // Inject device timezone so Alpine userspace sees local time.
        // Mirrors iOS ISHShellExecutor.m:335-353 — uses POSIX TZ format with a
        // fixed name ("LCL") to avoid abbreviations like "GMT+8" which contain
        // +/- and confuse musl's TZ parser. POSIX sign is reversed from UTC
        // offset (UTC+8 → "LCL-8"), matching iOS exactly.
        customEnvironment["TZ"] = guestTz()

        // Propagate the Android system HTTP proxy into every sandboxed
        // process so curl/wget/pip/npm reuse the user's system or enterprise
        // proxy (and packet-capture tools like Charles / mitmproxy just work).
        // PROXY_CHANGE_ACTION is wired up in MinisApp so live shells pick up
        // proxy toggles without a restart.
        customEnvironment.putAll(systemProxyEnv(context))

        // Register global bind mounts so direct file I/O tools (file_read, file_edit)
        // can resolve /var/minis/{memory,skills,shared}/... (idempotent).
        registerGlobalBindMounts(context)

        // Start the native_offload server so the proot extension can reach it
        // over the abstract unix socket. Handlers must have been registered
        // via NativeOffloadServer.register() before this point.
        NativeOffloadServer.start(rootfsManager.rootfsDir)

        // Materialize stub binaries inside the rootfs for each handler so
        // /bin/sh's PATH search succeeds and triggers an execve the extension
        // can intercept. The stub's content is irrelevant — proot rewrites
        // the execve before it runs.
        installHandlerStubs(rootfsManager.rootfsDir)

        // T219-6: now that rootfs is on disk, materialize /var/minis/mounts/<name>
        // placeholder dirs that PRoot's `-b` needs as bind targets. The earlier
        // applyMountedFoldersSnapshot in MinisApp.onCreate ran before boot and
        // its mkdirs went nowhere; this re-run covers the mount entries that
        // were known at app launch.
        applyMountedFoldersSnapshot(context)

        isBooted = true
        HostStatusPublisher.start(context, rootfsManager.rootfsDir)
        Log.i(TAG, "PRoot kernel booted " +
            "rootfs=${rootfsManager.rootfsDir.absolutePath} " +
            "nativeLibDir=$nativeLibDir " +
            "loader=$prootLoaderPath " +
            "bindMounts=${bindMounts.size} " +
            "offloadHandlers=${NativeOffloadServer.registeredHandlers.sorted()}")
    }

    fun addBindMount(linuxPath: String, hostPath: String) {
        if (isPerSessionPath(linuxPath)) {
            // Would reintroduce last-writer-wins for every global reader.
            Log.e(TAG, "refusing per-session bind mount in the global table: $linuxPath -> $hostPath")
            return
        }
        bindMounts[linuxPath] = hostPath
    }

    /** True for `/var/minis/<attachments|offloads|workspace|browser>` and below. */
    fun isPerSessionPath(linuxPath: String): Boolean {
        if (!linuxPath.startsWith("/var/minis/")) return false
        val sub = linuxPath.removePrefix("/var/minis/").substringBefore('/')
        return sub in perSessionSubdirs
    }

    /**
     * Register the global (session-independent) Minis bind mounts so direct
     * file I/O tools (file_read, file_edit) can resolve
     * `/var/minis/{skills,shared}/...` without needing PRoot to be
     * booted or any shell to have started. Memory is per-session.
     * Safe to call repeatedly.
     */
    fun registerGlobalBindMounts(context: Context) {
        val globalBase = File(context.filesDir, SessionWorkspace.GLOBAL_DIR)
        // [T-mcp-integration-android] mcp-servers is global (like skills):
        // binding it here makes the in-PRoot minis-mcp-cli read/write the SAME
        // servers.json the Android Settings UI does (host: minis-global/mcp-servers).
        SessionWorkspace.GLOBAL_BIND_SUBDIRS.forEach { subdir ->
            val hostDir = File(globalBase, subdir).also { it.mkdirs() }
            bindMounts["/var/minis/$subdir"] = hostDir.absolutePath
        }
    }

    fun removeBindMount(linuxPath: String) {
        bindMounts.remove(linuxPath)
    }

    fun clearBindMounts() {
        bindMounts.clear()
    }

    // ── User-mounted external folders (T219) ──────────────────────────────
    //
    // Linux prefix every external mount lives under inside the rootfs.
    // PRoot's `-b host:linux` flag is per-invocation, so the diff-sync
    // applied via [applyMountedFoldersSnapshot] only affects subsequent
    // shell_execute calls — live processes won't observe a CRUD mid-flight.
    private const val MOUNTS_LINUX_PREFIX = "/var/minis/mounts/"

    // Sentinel in the read-only write-guard wrapper scripts so we can recognize
    // and remove our own wrappers (vs a user/busybox binary of the same name).
    // [T-s6-#6] 哨兵与脚本模板同源，见 [MountWriteGuardScript.MARKER]。
    private const val GUARD_MARKER = MountWriteGuardScript.MARKER

    /**
     * Reference to the user-mounted folders store, set by [MinisApp] at
     * launch (the file is owned by T219-1 / worker A — we cannot touch it
     * from here, so we receive the instance via this setter instead of a
     * top-level singleton). Nullable: tools and the file-mention index
     * tolerate `null` and treat it as "no mounts".
     */
    @Volatile
    var mountedFoldersStore: MountedFoldersStore? = null

    /**
     * Reconcile [bindMounts] keys under `/var/minis/mounts/` with the
     * current snapshot of [mountedFoldersStore]. Removes stale entries,
     * adds new ones, updates host paths in place when the user re-points
     * a mount. Idempotent — call from boot + after every CRUD on the
     * store.
     *
     * Mounts whose tree URI doesn't decode to a POSIX path readable by
     * us (cloud-only providers, `Android/data/<otherPkg>` under strict
     * scoped storage, etc.) are skipped silently — surfacing an error
     * is the responsibility of the picker UI in T219-2.
     */
    fun applyMountedFoldersSnapshot(context: Context) {
        val store = mountedFoldersStore
        val desired = mutableMapOf<String, String>()
        // [T-b4-#4] store 里仍然存在的条目名。占位目录回收必须以它为准，而不是以
        // `desired`（本轮 SAF 解析成功的条目）为准——解析只是暂时失败时，旧逻辑
        // 会删掉仍在使用的挂载目标目录。
        val liveEntryNames = mutableSetOf<String>()
        if (store != null) {
            for (entry in store.entries.value) {
                liveEntryNames += entry.name
                val host = resolveTreeUriToHostPath(entry.treeUri, context) ?: continue
                desired["$MOUNTS_LINUX_PREFIX${entry.name}"] = host
            }
        }
        // [T-b4-#6] 重建挂载表是共享存储探测的既有失效点（启动 / 开机 / 挂载 CRUD），
        // 先失效缓存，下面派生出的计划才会拿到新鲜值。
        invalidateSharedStorageProbe()
        applySharedStorageBinds(context, desired)

        // Remove stale /var/minis/mounts/* keys not in desired.
        val stale = bindMounts.keys
            .filter { it.startsWith(MOUNTS_LINUX_PREFIX) }
            .filter { it !in desired }
        for (key in stale) bindMounts.remove(key)

        // Add or update.
        for ((linuxPath, hostPath) in desired) {
            bindMounts[linuxPath] = hostPath
        }

        // T219-6: PRoot's `-b host:linux` requires the linux target to exist
        // inside the rootfs as a real directory; otherwise PRoot silently skips
        // the bind. Materialize a placeholder dir for each /var/minis/mounts/<name>
        // (and clean up stale ones) so the bind actually takes effect.
        materializeMountTargets(context, desired.keys, liveEntryNames)

        // T219-4: raw shell writes (touch/cp/mv/tee/… and `>` redirects) bypass
        // the FileWriteTool/FileEditTool read-only guard because proot `-b` has no
        // read-only modifier. Install shell wrappers that reject writes whose
        // target lands under an effectively-read-only mount, so the agent gets a
        // clear "read-only mounted folder" error in the shell instead of a write
        // that silently no-ops (Android 10 shadow FUSE) or a confusing raw EACCES.
        // [T-s6-#5] 覆盖面仅限 wrapper 检查的 argv 目标（见
        // [installMountWriteGuards]）：`>` 重定向与非白名单命令仍写得进去。
        val readOnlyLinuxPrefixes = store?.entries?.value.orEmpty()
            .filter { it.name in desired.keys.map { k -> k.removePrefix(MOUNTS_LINUX_PREFIX) } }
            .filterNot { it.effectiveWritable }
            .map { "$MOUNTS_LINUX_PREFIX${it.name}" }
        installMountWriteGuards(context, readOnlyLinuxPrefixes)

        val entryCount = store?.entries?.value?.size ?: 0
        Log.i(
            TAG,
            "applyMountedFoldersSnapshot: entries=$entryCount active=${desired.size} removed=${stale.size} " +
                storageAccessDiag(context),
        )
        if (entryCount > desired.size) {
            Log.w(
                TAG,
                "applyMountedFoldersSnapshot: ${entryCount - desired.size} mount(s) dropped — host path " +
                    "unresolved or unreadable (see resolveTreeUriToHostPath warnings above)",
            )
        }
    }

    /**
     * Bind host shared storage into the guest when All Files Access (or
     * legacy WRITE_EXTERNAL_STORAGE on API 29) is granted. PRoot `-b` then
     * gives POSIX read/write without going back through SAF DocumentFile.
     * Does not override a user mount already named `sdcard`.
     */
    private fun applySharedStorageBinds(context: Context, desired: MutableMap<String, String>) {
        val userNamed = desired.keys.any { it.removePrefix(MOUNTS_LINUX_PREFIX) == "sdcard" }
        val plan = sharedStoragePlan(context, userNamed)
        syncSharedStorageBindMounts(plan, userNamed)
        val mounted = plan[SharedStorageBindPlan.MOUNTS_SDCARD]
        if (mounted != null) desired[SharedStorageBindPlan.MOUNTS_SDCARD] = mounted
        if (plan.isNotEmpty()) materializeSharedStorageTargets(context, plan.keys)
    }

    /**
     * Binds [PersistentShell] must pass on `-b`. [bindMounts] alone is not
     * that argv, so a grant that only updated the kernel map left `/sdcard`
     * as an empty rootfs directory inside `shell_execute`.
     */
    fun shellSharedStorageBinds(context: Context, publish: Boolean = true): Map<String, String> {
        val userNamed = mountedFoldersStore?.entries?.value?.any { it.name == "sdcard" } == true
        val plan = sharedStoragePlan(context, userNamed)
        if (!publish) return plan
        syncSharedStorageBindMounts(plan, userNamed)
        if (plan.isNotEmpty()) materializeSharedStorageTargets(context, plan.keys)
        return plan
    }

    private fun sharedStoragePlan(context: Context, userNamedSdcard: Boolean): Map<String, String> {
        // [T-b4-#6] 下面的探测（appops 权限查询 + canRead() stat）原先每条 execute
        // 的挂载陈旧检测都要跑一次，即使挂载集合一个字节都没变。改为缓存，失效契约
        // 见 [SharedStorageProbeCache]。
        // [T-b4-#6-negative] 但"未授权"的负数命中要先复检一次实时权限位：offload 路径
        // （`OffloadPermissionManager.requestAndroidPermission`）刚授权时缓存还是
        // granted=false，/sdcard 会被摘到下次前台。这里用同一个 hasSharedStorageAccess
        // 判据复检——"有权限但缓存说没有"就强制重探。已授权时零额外开销。
        val probe = SharedStorageProbeCache.resolve(
            permissionGranted = { hasSharedStorageAccess(context) },
            probe = { probeSharedStorage(context) },
        )
        return SharedStorageBindPlan.plan(if (probe.granted) probe.host else null, userNamedSdcard)
    }

    private fun probeSharedStorage(context: Context): SharedStorageProbeCache.Probe {
        val host = Environment.getExternalStorageDirectory()?.absolutePath
        val granted = !host.isNullOrBlank() &&
            hasSharedStorageAccess(context) &&
            File(host).canRead()
        return SharedStorageProbeCache.Probe(host, granted)
    }

    /**
     * [T-b4-#6] 失效共享存储探测缓存。所有"答案可能变化"的时点都要调用：挂载 CRUD /
     * 开机（经 [applyMountedFoldersSnapshot]）以及应用回到前台（用户可能刚在系统
     * 设置里改动「所有文件访问」，或刚答完存储运行时权限弹窗）。
     */
    fun invalidateSharedStorageProbe() {
        SharedStorageProbeCache.invalidate()
    }

    private fun syncSharedStorageBindMounts(plan: Map<String, String>, userNamedSdcard: Boolean) {
        for (linux in listOf(SharedStorageBindPlan.SDCARD, SharedStorageBindPlan.EMULATED)) {
            val host = plan[linux]
            if (host == null) bindMounts.remove(linux) else bindMounts[linux] = host
        }
        val mounted = SharedStorageBindPlan.MOUNTS_SDCARD
        if (mounted in plan) bindMounts[mounted] = plan.getValue(mounted)
        else if (!userNamedSdcard) bindMounts.remove(mounted)
    }

    private fun materializeSharedStorageTargets(context: Context, linuxPaths: Collection<String>) {
        materializeAbsoluteTargets(
            context,
            linuxPaths.filter { !it.startsWith(MOUNTS_LINUX_PREFIX) },
        )
        val rootfs = try {
            RootfsManager.getInstance(context).rootfsDir
        } catch (t: Throwable) {
            Log.w(TAG, "materializeSharedStorageTargets: rootfs not yet available: ${t.message}")
            return
        }
        for (linux in linuxPaths) {
            if (!linux.startsWith(MOUNTS_LINUX_PREFIX)) continue
            File(rootfs, linux.removePrefix("/")).mkdirs()
        }
    }

    private fun hasSharedStorageAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun materializeAbsoluteTargets(context: Context, linuxPaths: List<String>) {
        val rootfs = try {
            RootfsManager.getInstance(context).rootfsDir
        } catch (t: Throwable) {
            Log.w(TAG, "materializeAbsoluteTargets: rootfs not yet available: ${t.message}")
            return
        }
        for (linuxPath in linuxPaths) {
            File(rootfs, linuxPath.removePrefix("/")).mkdirs()
        }
    }

    /**
     * 保证每个 `/var/minis/mounts/<name>` 在 rootfs 里都有一个真实（空）目录供
     * PRoot `-b` 绑定；同时清理 store 里确实已删除的陈旧占位目录，避免旧名字
     * 一直出现在 `ls /var/minis/mounts/` 里。
     *
     * [liveEntryNames] 是仍在 [mountedFoldersStore] 里的条目名：名字还在其中时，
     * 即使本轮解析宿主路径失败也绝不删除该目录（见 [MountPlaceholderPolicy]）。
     */
    private fun materializeMountTargets(
        context: Context,
        desiredLinuxPaths: Set<String>,
        liveEntryNames: Set<String>,
    ) {
        val rootfs = try {
            RootfsManager.getInstance(context).rootfsDir
        } catch (t: Throwable) {
            Log.w(TAG, "materializeMountTargets: rootfs not yet available: ${t.message}")
            return
        }
        val mountsRoot = File(rootfs, "var/minis/mounts").also { it.mkdirs() }

        // Create placeholder dirs for desired names.
        for (linuxPath in desiredLinuxPaths) {
            val name = linuxPath.removePrefix(MOUNTS_LINUX_PREFIX)
            File(mountsRoot, name).also { d ->
                if (!d.exists()) d.mkdirs()
            }
        }

        // Clean placeholder dirs that no longer correspond to a mount.
        // [T-b4-#4] 只删「store 里已无此条目」且为空的目录。旧写法
        // `f.name !in desiredNames → deleteRecursively()` 会把"本轮解析失败"的挂载
        // 目标目录（卡未挂好 / readdir EACCES / pre-R 卷取不到路径）连同 guest 已经
        // 写进去的文件一起删掉。
        val desiredNames = desiredLinuxPaths.map { it.removePrefix(MOUNTS_LINUX_PREFIX) }.toSet()
        mountsRoot.listFiles()?.forEach { f ->
            if (!f.isDirectory) return@forEach
            val known = f.name in desiredNames || f.name in liveEntryNames
            val empty = f.list()?.isEmpty() == true
            if (MountPlaceholderPolicy.isRemovable(f.name, desiredNames, liveEntryNames, empty)) {
                if (!f.delete()) {
                    Log.w(TAG, "materializeMountTargets: could not remove stale placeholder ${f.absolutePath}")
                }
            } else if (!known) {
                // 不是已知挂载、但里面有文件：保留，宁可留个空壳也不删数据。
                Log.w(TAG, "materializeMountTargets: keeping non-empty dir ${f.absolutePath} (not an empty placeholder)")
            }
        }
    }

    /**
     * True when [linuxPath] resolves under a `/var/minis/mounts/<name>`
     * mount whose effective writability is false. Used by [FileWriteTool]
     * and [FileEditTool] to short-circuit before touching disk; mirrors
     * iOS `MountedFolderCoordinator.isLinuxPathUnderReadOnlyMount`.
     *
     * The shell can technically still write through a PRoot bind because
     * `-b` has no read-only modifier — that's why the mount-detail screen
     * uses honest "Locked / Unlocked" wording, and why the shell-level
     * wrappers exist as argv-only best-effort ([installMountWriteGuards],
     * scope spelled out in [T-d10-os-ro]). [T-d10-doc] 旧注释还写着"shell 级
     * wrapper 是 T219-4 的可选后续项"——它早已随本函数一起装好，这里改成
     * 现在的真实状态（本方法仍是唯一对文件工具生效的硬约束）。
     */
    fun isLinuxPathUnderReadOnlyMount(linuxPath: String): Boolean {
        if (!linuxPath.startsWith(MOUNTS_LINUX_PREFIX)) return false
        val store = mountedFoldersStore ?: return false
        val rest = linuxPath.removePrefix(MOUNTS_LINUX_PREFIX)
        val name = rest.substringBefore('/')
        if (name.isEmpty()) return false
        val entry = store.entries.value.firstOrNull { it.name == name } ?: return false
        return !entry.effectiveWritable
    }

    /**
     * Snapshot of mount roots for the @-mention index. Skips entries
     * whose tree URI doesn't resolve to a POSIX path on this device.
     */
    fun mountEntriesForIndex(context: Context): List<FileMentionIndex.MountEntry> {
        val store = mountedFoldersStore ?: return emptyList()
        return store.entries.value.mapNotNull { entry ->
            val host = resolveTreeUriToHostPath(entry.treeUri, context) ?: return@mapNotNull null
            FileMentionIndex.MountEntry(name = entry.name, root = File(host))
        }
    }

    /**
     * Decode a SAF tree URI (the `content://com.android.externalstorage.documents/tree/<volume>:<rel>`
     * form returned by `ACTION_OPEN_DOCUMENT_TREE`) into an absolute POSIX
     * path on the host filesystem so PRoot's `-b` flag can bind it. This
     * is "Option A" from the T219 spec: the dominant Obsidian-vault /
     * Downloads / DCIM use cases all live under `/storage/emulated/0` or
     * a removable volume's mount directory and are openable directly by
     * our app uid.
     *
     * Returns null when:
     *   - the URI is from a non-`externalstorage` provider (Drive,
     *     Dropbox, OneDrive — those have no POSIX path),
     *   - the volume id can't be resolved (rare — likely a removed
     *     SD card),
     *   - the resolved path doesn't exist or isn't readable by us
     *     (e.g. another app's `Android/data/<pkg>` under strict scoped
     *     storage).
     *
     * Workers A's later `MountedFolderCoordinator` will likely hoist
     * this exact algorithm into the data layer with a `resolvedHostPath`
     * cache field on `MountedFoldersStore.Entry`. Kept inline here so
     * T219-3 can ship before A finishes.
     */
    private fun resolveTreeUriToHostPath(treeUriString: String, context: Context): String? = try {
        val treeUri = Uri.parse(treeUriString)
        if (treeUri.authority != "com.android.externalstorage.documents") {
            null
        } else {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val parts = docId.split(':', limit = 2)
            val volumeId = parts.getOrNull(0).orEmpty()
            val relPath = parts.getOrNull(1).orEmpty()
            val volumeRoot = resolveVolumeRoot(volumeId, context)
            if (volumeRoot == null) {
                // [T-b4-#9] 区分"没有这个卷"与"卷存在但拿不到 POSIX 路径"：旧文案
                // 把两者都写成 unknown volume，会把诊断引向错误方向。
                val reason = if (isKnownVolume(volumeId, context)) {
                    "volume '$volumeId' is recognized but has no POSIX path on this device " +
                        "(sdk=${Build.VERSION.SDK_INT}) — mount skipped"
                } else {
                    "unknown volume '$volumeId' — mount skipped"
                }
                Log.w(TAG, "resolveTreeUriToHostPath: $reason for $treeUriString")
                null
            } else {
                val candidate = if (relPath.isEmpty()) File(volumeRoot) else File(volumeRoot, relPath)
                // Diagnose the common "mounted folder reads empty" failure: the
                // resolved host dir exists but the app process (raw POSIX, via
                // proot) can't readdir it because the ROM only grants the
                // /mnt/runtime/default FUSE view. list() returning null == EACCES.
                if (candidate.exists() && candidate.canRead()) {
                    val childCount = candidate.list()?.size
                    if (childCount == null) {
                        Log.w(
                            TAG,
                            "resolveTreeUriToHostPath: host=${candidate.absolutePath} canRead=true " +
                                "but list() returned null (readdir EACCES / scoped-storage filtered) — " +
                                "mount will appear empty. ${storageAccessDiag(context)}",
                        )
                    } else {
                        Log.i(
                            TAG,
                            "resolveTreeUriToHostPath: host=${candidate.absolutePath} readable childCount=$childCount",
                        )
                    }
                    candidate.absolutePath
                } else {
                    Log.w(
                        TAG,
                        "resolveTreeUriToHostPath: host=${candidate.absolutePath} " +
                            "exists=${candidate.exists()} canRead=${candidate.canRead()} — bind skipped",
                    )
                    null
                }
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "resolveTreeUriToHostPath failed for $treeUriString: ${t.message}")
        null
    }

    /**
     * One-line summary of the app's effective external-storage access, for the
     * mount log trail. Explains *why* a resolved host dir might readdir empty:
     * on Android 11+ it's MANAGE_EXTERNAL_STORAGE; on Android 10 base ROMs
     * (HarmonyOS/EMUI) it's whether legacy READ_EXTERNAL_STORAGE was granted.
     */
    private fun storageAccessDiag(context: Context): String {
        val sdk = Build.VERSION.SDK_INT
        return when {
            sdk >= Build.VERSION_CODES.R ->
                "sdk=$sdk allFilesAccess=${Environment.isExternalStorageManager()}"
            else -> {
                val granted = context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                // Issue #118: Environment.isExternalStorageLegacy() is API 29.
                // This branch covers everything below R (30), so on our minSdk
                // 26..28 floor the unguarded call was a hard NoSuchMethodError —
                // and because it sits on the boot path it killed the process in
                // Application.onCreate, ~250ms restart loop, before any user data
                // was touched (hence "clear app data" not helping). Below 29 the
                // scoped-storage opt-out doesn't exist as a concept: storage is
                // unconditionally legacy, so report that statically.
                val legacyOptIn = if (sdk >= Build.VERSION_CODES.Q) {
                    Environment.isExternalStorageLegacy().toString()
                } else {
                    "n/a(pre-Q)"
                }
                "sdk=$sdk legacyStorageGranted=$granted legacyExtStorageOptIn=$legacyOptIn"
            }
        }
    }

    private fun resolveVolumeRoot(volumeId: String, context: Context): String? {
        if (volumeId.equals("primary", ignoreCase = true) || volumeId.isEmpty()) {
            return Environment.getExternalStorageDirectory()?.absolutePath
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return null
            for (volume in sm.storageVolumes) {
                if (volume.uuid?.equals(volumeId, ignoreCase = true) == true) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        volume.directory?.absolutePath?.let { return it }
                    }
                    // [T-b4-#9] StorageVolume.directory 是 API 30+ 成员（本仓 minSdk 26），
                    // 所以 Android 8–10 上旧代码对任何可移动卷都会落到 `return null`，
                    // SD 卡挂载被静默丢弃。这里复用 MountedFoldersStore.resolveVolumeRoot
                    // 的回退：uuid 已识别的卷，其挂载点就是 /storage/<uuid>。
                    val fallback = File("/storage/$volumeId")
                    return if (fallback.isDirectory) fallback.absolutePath else null
                }
            }
        }
        return null
    }

    /** [T-b4-#9] 只用于诊断，见 [resolveTreeUriToHostPath] 里的调用点。 */
    private fun isKnownVolume(volumeId: String, context: Context): Boolean {
        if (volumeId.equals("primary", ignoreCase = true) || volumeId.isEmpty()) return true
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return false
        return sm.storageVolumes.any { it.uuid?.equals(volumeId, ignoreCase = true) == true }
    }

    /**
     * Build a POSIX TZ string from the current system timezone.
     *
     * Matches iOS ISHShellExecutor.m:335-353 byte-for-byte:
     *   UTC+8:00 → "LCL-8"
     *   UTC+5:30 → "LCL-5:30"
     *   UTC-3:00 → "LCL+3"
     *
     * POSIX TZ sign is inverted relative to UTC offset, and we use the fixed
     * name "LCL" to avoid musl's confused parsing of abbreviations like
     * "GMT+8" (which contain an embedded sign).
     */
    /**
     * Zone id when the phone has one (`Asia/Shanghai`). POSIX `LCL-8` makes
     * `date +%Z` print LCL even after `/etc/localtime` is correct.
     */
    fun guestTz(): String {
        val id = TimeZone.getDefault().id
        return if (id.contains('/') && !id.startsWith("GMT")) id else posixTz()
    }

    fun posixTz(): String {
        val offsetMs = TimeZone.getDefault().getOffset(System.currentTimeMillis())
        val secs = offsetMs / 1000L
        val hrs = (secs / 3600).toInt()
        val mins = ((abs(secs) % 3600) / 60).toInt()
        // POSIX sign is reversed: UTC+8 → "LCL-8"
        val posixHrs = -hrs
        val sign = if (posixHrs >= 0) "+" else "-"
        return if (mins != 0) {
            "LCL$sign${abs(posixHrs)}:${"%02d".format(mins)}"
        } else {
            "LCL$sign${abs(posixHrs)}"
        }
    }

    /**
     * Recompute TZ and update customEnvironment. Returns the new TZ string.
     * Callers should also propagate the new value to live shells via
     * [ExecutionCoordinator.broadcastTimezoneChange].
     */
    fun updateTimezone(): String {
        val tz = guestTz()
        customEnvironment["TZ"] = tz
        Log.i(TAG, "Updated TZ=$tz")
        return tz
    }

    /**
     * Rewrite guest `/etc/localtime` after a timezone change. [updateTimezone]
     * only updates the env var used by new shells; glibc `date` and Python
     * fall back to the symlink, which otherwise stays on the zone from boot.
     */
    suspend fun syncHostTimezoneFiles() {
        if (rootfsManagerRef == null) return
        rootfsManager.applyHostTimezone()
    }

    /**
     * Env-variable keys for HTTP proxy injection. All six are always set as a
     * block — even when no system proxy is configured the values are empty
     * strings, which (a) signals "no proxy" to curl/wget/pip/npm (they treat
     * empty as unset) and (b) lets a proxy-disabled transition overwrite any
     * previously exported value in a live shell without a separate `unset`.
     */
    private val PROXY_KEYS = listOf(
        "http_proxy", "https_proxy",
        "HTTP_PROXY", "HTTPS_PROXY",
        "no_proxy", "NO_PROXY",
    )

    /** Standard bypass list for localhost traffic — matches the `curl` convention. */
    private const val PROXY_NO_PROXY = "localhost,127.0.0.1,::1"

    /**
     * Read the system HTTP proxy configuration and produce the env-var block
     * to inject into every sandboxed process. Returns all six keys even when
     * no proxy is set (values are empty strings) so callers can unconditionally
     * `export` the block and get the right behavior on both transitions:
     *
     *   proxy set → values carry http://host:port
     *   proxy unset → values are empty, tools fall back to direct connection
     */
    fun systemProxyEnv(context: Context): Map<String, String> {
        val proxyUri = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val info = cm?.defaultProxy
            if (info != null && info.host.isNotBlank() && info.port > 0) {
                "http://${info.host}:${info.port}"
            } else {
                ""
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read system proxy: ${t.message}")
            ""
        }

        val out = linkedMapOf<String, String>()
        for (key in PROXY_KEYS) {
            out[key] = when (key) {
                "no_proxy", "NO_PROXY" -> if (proxyUri.isNotEmpty()) PROXY_NO_PROXY else ""
                else -> proxyUri
            }
        }
        return out
    }

    /**
     * Recompute proxy env vars and update customEnvironment. Returns the map
     * that was written so callers can propagate to live shells via
     * [ExecutionCoordinator.broadcastProxyChange].
     */
    fun updateProxy(context: Context): Map<String, String> {
        val env = SandboxHttpProxy.envBlock() ?: systemProxyEnv(context)
        customEnvironment.putAll(env)
        val hasProxy = env["http_proxy"].orEmpty().isNotEmpty()
        Log.i(TAG, "Updated proxy — ${if (hasProxy) "active=${env["http_proxy"]}" else "cleared"}")
        return env
    }

    /**
     * Build the full proot command line for a given shell command.
     *
     * Produces:
     * ```
     * <proot> -0 --link2symlink -r <rootfs> -b /dev -b /proc -b /sys -w /root
     *         [-b <host>:<linux> ...for each bind mount]
     *         /bin/sh -c "<command>"
     * ```
     */
    fun buildProotCommand(shellCommand: String): List<String> {
        check(isBooted) { "PRootKernel.boot() must be called before building commands" }

        val cmd = mutableListOf<String>()

        cmd.add(rootfsManager.prootBinary.absolutePath)

        // Run as fake root (uid 0)
        cmd.add("-0")

        // T141: Translate hardlink() syscalls to symlink(). Android's /data
        // (ext4/F2FS as mounted by zygote) refuses cross-directory hardlinks
        // for app uids, so PRoot would otherwise pass link() straight through
        // to the host kernel and the host kernel rejects with EPERM. Alpine's
        // apk installs busybox-applet packages (binutils, gcc deps, etc.) by
        // hardlinking ar/ld/nm/strip → busybox; without this flag every such
        // install fails with "Permission denied". Symlinks are functionally
        // equivalent for the apk consumer.
        cmd.add("--link2symlink")
        // One-shot proot must reap the guest when the host process is killed.
        cmd.add("--kill-on-exit")

        // Set rootfs
        cmd.add("-r")
        cmd.add(rootfsManager.rootfsDir.absolutePath)

        // Bind essential pseudo-filesystems
        cmd.add("-b")
        cmd.add("/dev")
        cmd.add("-b")
        cmd.add("/proc")
        cmd.add("-b")
        cmd.add("/sys")

        // Working directory
        cmd.add("-w")
        cmd.add("/root")

        // User bind mounts
        for ((linuxPath, hostPath) in bindMounts) {
            cmd.add("-b")
            cmd.add("$hostPath:$linuxPath")
            // Log external-folder binds specifically — these are the ones that
            // read empty when the host view is filtered, and the proot child's
            // own EACCES on readdir is otherwise invisible in the log file.
            if (linuxPath.startsWith(MOUNTS_LINUX_PREFIX)) {
                Log.i(TAG, "proot bind mount: $hostPath -> $linuxPath")
            }
        }

        // Native offload: route registered handler names to the host-side
        // NativeOffloadServer over the abstract unix socket.
        val handlers = NativeOffloadServer.registeredHandlers
        if (handlers.isNotEmpty()) {
            cmd.add("--native-offload=${NativeOffloadServer.socketName}:${handlers.joinToString(",")}")
        }

        // [T-android-fake-netlink] Emulate rtnetlink inside the sandbox.
        //
        // Android's untrusted_app SELinux domain may not create an
        // AF_NETLINK/NETLINK_ROUTE socket, and the refusal comes from the real
        // kernel — so anything that enumerates interfaces dies on startup.
        // Go's net.Interfaces() reports
        //   route ip+net: netlinkrib: permission denied
        // which is what takes tailscale/tsnet-based tools out of service.
        // With this flag proot answers rtnetlink itself with an EMPTY
        // interface list, which is enough for those callers to start.
        //
        // ⚠️ INTERNAL TESTING ONLY, currently unconditional and with no user
        // control. Before this ships it needs a decision on exposure, because
        // it is a deliberate lie to guest programs about the machine they run
        // on: "no interfaces at all" satisfies a caller that only needs the
        // enumeration to succeed, but a caller that then picks a source
        // interface or binds a local address fails LATER instead of at
        // startup — a worse failure, not a better one.
        cmd.add("--fake-netlink")

        // Shell command
        cmd.add("/bin/bash")
        cmd.add("-c")
        cmd.add(GuestLimits.wrap(shellCommand))

        Log.d(TAG, "proot cmd: ${cmd.take(cmd.size - 1).joinToString(" ")} <shellCommand ${shellCommand.length} bytes>")
        return cmd
    }

    /** Subdirs that live under `minis-sessions/<sessionId>/` rather than the global pool. */
    private val perSessionSubdirs = SessionMounts.SESSION_SUBDIRS.toSet()

    /**
     * Resolve a `/var/minis/...` Linux path against the same host directory the
     * session shell bind-mounts. Use this when the caller knows the owning
     * session (file tools, chat link resolver, file preview). The global
     * [bindMounts] map is last-writer-wins and must not be used for per-session
     * paths — that split is what made `file_write` and `shell_execute` see two
     * different `/var/minis/workspace` trees.
     *
     * Falls back to [resolveHostPath] for paths this layout does not own
     * (rootfs files, external SAF mounts).
     *
     * [T-walk-cage-single-resolve] Returns the **canonical** path the ownership
     * check was made against ([SessionWorkspace.verifiedCanonical]), not the
     * lexical `/var/minis/...` translation. Callers that walk or open the result
     * must use it as-is: resolving it a second time is what let a guest re-point
     * an intermediate component between "verified" and "walked" and have the
     * tools operate on a root nobody had checked. For a path that does not exist
     * yet (creating a new file) canonicalisation is impossible, so the
     * lexical-normalised path is returned — same value as before this change.
     */
    fun resolveSessionHostPath(sessionId: String, linuxPath: String, context: Context): File? {
        val resolved = SessionWorkspace.resolveGuestPath(context.filesDir, sessionId, linuxPath)
            ?: resolveHostPath(linuxPath)
            ?: return null
        return SessionWorkspace.verifiedCanonical(
            context.filesDir,
            sessionId,
            resolved,
            externalHostRoots(),
        )
    }

    /**
     * [T-file-tool-symlink-jail] Host trees a resolved guest path may point into
     * besides `minis-sessions`/`minis-workspaces`/`minis-global`: the rootfs and
     * every bind-mount host base (external SAF folders, shared storage).
     *
     * Passed INTO [SessionWorkspace.acceptsResolved] instead of letting the
     * sandbox package call back into this object. The list is only consulted
     * for paths that fall outside every Minis tree, and each entry is
     * canonicalised at that point, so building it is allocation-cheap.
     */
    private fun externalHostRoots(): List<File> {
        // Snapshot for the same reason resolveHostPath does: the live map is
        // mutated by shell (re)creation and mount CRUD.
        val mounts = bindMounts.toMap()
        val roots = ArrayList<File>(mounts.size + 1)
        rootfsManagerRef?.let { roots.add(it.rootfsDir) }
        for (hostPath in mounts.values) roots.add(File(hostPath))
        return roots
    }

    /**
     * Resolve a Linux path to a host filesystem File by checking bind mounts.
     * Returns null if no matching mount is found, or if `..` walks out of the
     * matched bind root / rootfs (that is how one session's file tool used to
     * read another's private tree).
     */
    fun resolveHostPath(linuxPath: String): File? {
        // Check bind mounts (longest prefix match).
        // [T-b4-#2] 先做快照：其它线程（建壳 / 挂载 CRUD）仍可能在排序与取值之间改动
        // 这张表，旧写法 `bindMounts[mountPoint]!!` 会在这里抛 NPE。
        val snapshot = bindMounts.toMap()
        val sorted = snapshot.keys.sortedByDescending { it.length }
        for (mountPoint in sorted) {
            if (linuxPath == mountPoint || linuxPath.startsWith("$mountPoint/")) {
                val hostBase = snapshot[mountPoint] ?: continue
                val relativePath = linuxPath.removePrefix(mountPoint).removePrefix("/")
                val file = if (relativePath.isEmpty()) File(hostBase) else File(hostBase, relativePath)
                return file.takeIf { SessionWorkspace.staysInside(File(hostBase), it) }
            }
        }

        // [T-android-session-private-mounts] Per-session path with no session
        // context: the legacy "most recent session" answer (see
        // [legacySessionId]). Logged so the remaining callers stay findable.
        if (isPerSessionPath(linuxPath)) {
            val sid = legacySessionId
            val filesDir = legacyFilesDir
            if (sid != null && filesDir != null) {
                legacyResolveCount++
                val rest = linuxPath.removePrefix("/var/minis/")
                Log.d(TAG, "legacy session resolve $linuxPath -> session $sid")
                return File(filesDir, "minis-sessions/$sid/$rest")
            }
        }

        // Fallback: resolve relative to rootfs
        if (rootfsManagerRef == null) return null
        val stripped = linuxPath.removePrefix("/")
        if (stripped.isEmpty()) return rootfsManager.rootfsDir
        val file = File(rootfsManager.rootfsDir, stripped)
        return file.takeIf { SessionWorkspace.staysInside(rootfsManager.rootfsDir, it) }
    }

    /**
     * Create a stub executable inside the rootfs for every registered
     * native_offload handler. The shell needs to find *something* on PATH
     * to trigger an execve — proot's native_offload extension intercepts
     * that execve before the stub actually runs.
     */
    private fun installHandlerStubs(rootfsDir: File) {
        val binDir = File(rootfsDir, "usr/local/bin").also { it.mkdirs() }
        var created = 0
        var existed = 0
        for (name in NativeOffloadServer.registeredHandlers) {
            val stub = File(binDir, name)
            if (stub.exists()) { existed++; continue }
            stub.writeText("#!/bin/sh\nexit 0\n")
            stub.setExecutable(true, false)
            created++
        }
        Log.d(TAG, "installHandlerStubs dir=${binDir.absolutePath} created=$created existed=$existed")

        installShellWrappers(binDir)
    }

    /**
     * Install /usr/local/bin/ wrapper scripts that work around Android-specific
     * sandbox limitations. These take precedence over /bin/busybox in PATH.
     *
     * top: Android's hidepid=2 + SELinux blocks reads of /proc/<other_pid>/stat
     * for non-system UIDs, so plain `top` fails with "can't open 'stat': Permission
     * denied". Restrict it transparently to the PIDs we can actually read.
     *
     * Always rewritten so improvements ship with each app update.
     */
    private fun installShellWrappers(binDir: File) {
        // busybox `top` walks all of /proc and aborts on the first unreadable
        // /proc/<pid>/stat (Android sandbox blocks reads of other UIDs' procfs).
        // busybox in this rootfs doesn't accept `-p PIDS` either. Workaround:
        // simulate `top` with `ps` in a refresh loop, scoped to our session
        // (only PIDs whose /proc/<pid>/stat is actually readable by us).
        val topWrapper = File(binDir, "top")
        topWrapper.writeText(
            """#!/bin/sh
            |# Auto-installed by MinisApp PRootKernel.
            |# busybox top walks all of /proc and aborts on the first unreadable
            |# entry under Android's procfs hidepid restrictions, and this build
            |# of busybox doesn't accept `-p`. Emulate `top` with `ps` over the
            |# PIDs we can actually inspect.
            |
            |# Parse a few common top-style flags so existing muscle memory works.
            |delay=2
            |iters=-1
            |batch=0
            |while [ ${'$'}# -gt 0 ]; do
            |    case "${'$'}1" in
            |        -d) delay=${'$'}2; shift 2;;
            |        -n) iters=${'$'}2; shift 2;;
            |        -b) batch=1; shift;;
            |        -h|--help)
            |            echo "Usage: top [-b] [-n COUNT] [-d SECONDS]"
            |            exit 0;;
            |        *) shift;;
            |    esac
            |done
            |
            |saved_stty=""
            |on_exit() {
            |    [ ${'$'}batch -eq 0 ] && printf '\033[?25h'
            |    [ -n "${'$'}saved_stty" ] && stty "${'$'}saved_stty" 2>/dev/null
            |}
            |trap on_exit INT TERM EXIT
            |
            |list_readable_pids() {
            |    for d in /proc/[0-9]*; do
            |        [ -r "${'$'}d/stat" ] && printf '%s\n' "${'$'}{d##*/}"
            |    done
            |}
            |
            |render_once() {
            |    pids=${'$'}(list_readable_pids | tr '\n' ',' | sed 's/,${'$'}//')
            |    if [ -z "${'$'}pids" ]; then
            |        echo "top: no readable processes in /proc (Android sandbox)"
            |        return
            |    fi
            |    uptime_s=${'$'}(awk '{print int(${'$'}1)}' /proc/uptime 2>/dev/null)
            |    [ -z "${'$'}uptime_s" ] && uptime_s=0
            |    days=${'$'}((uptime_s / 86400))
            |    hours=${'$'}(((uptime_s % 86400) / 3600))
            |    mins=${'$'}(((uptime_s % 3600) / 60))
            |    visible=${'$'}(echo "${'$'}pids" | tr ',' '\n' | wc -l)
            |    printf 'top — up %dd %02d:%02d  visible processes: %d (own session only)\n' "${'$'}days" "${'$'}hours" "${'$'}mins" "${'$'}visible"
            |    printf '%s\n' '  PID USER     STAT  RSS  PPID COMMAND'
            |    /bin/busybox ps -o pid,user,stat,rss,ppid,comm 2>/dev/null \
            |        | awk -v pids=",${'$'}pids," 'NR==1 {next} {if (index(pids,","${'$'}1",")) print "  " ${'$'}0}'
            |}
            |
            |if [ ${'$'}batch -eq 1 ]; then
            |    count=0
            |    while [ ${'$'}iters -lt 0 ] || [ ${'$'}count -lt ${'$'}iters ]; do
            |        render_once
            |        count=${'$'}((count + 1))
            |        [ ${'$'}iters -ge 0 ] && [ ${'$'}count -ge ${'$'}iters ] && break
            |        sleep "${'$'}delay"
            |    done
            |    exit 0
            |fi
            |
            |# Interactive mode: clear screen + redraw each interval. Exits on
            |# Ctrl+C OR when the user presses 'q' / 'Q'. We put the TTY into
            |# raw, no-echo mode so single keypresses are read without Enter.
            |if [ -t 0 ]; then
            |    saved_stty=${'$'}(stty -g 2>/dev/null)
            |    stty -echo -icanon min 0 time 0 2>/dev/null
            |fi
            |printf '\033[?25l'
            |count=0
            |while [ ${'$'}iters -lt 0 ] || [ ${'$'}count -lt ${'$'}iters ]; do
            |    printf '\033[H\033[2J'
            |    render_once
            |    count=${'$'}((count + 1))
            |    [ ${'$'}iters -ge 0 ] && [ ${'$'}count -ge ${'$'}iters ] && break
            |    # Sleep in small slices so 'q' is responsive within ~0.1s.
            |    end=${'$'}((${'$'}(date +%s) + delay))
            |    while [ ${'$'}(date +%s) -lt ${'$'}end ]; do
            |        if [ -t 0 ]; then
            |            ch=${'$'}(dd bs=1 count=1 2>/dev/null)
            |            case "${'$'}ch" in q|Q) exit 0;; esac
            |        fi
            |        sleep 0.1
            |    done
            |done
            |""".trimMargin()
        )
        topWrapper.setExecutable(true, false)
        Log.d(TAG, "installShellWrappers: /usr/local/bin/top installed")
    }

    /**
     * T219-4: install /usr/local/bin/ wrappers for the common file-writing
     * commands so a write whose target is under an effectively-read-only mount
     * fails loudly in the shell (mirrors how iOS `MountedFolderCoordinator`
     * throws `ReadOnlyMountError` before touching disk), instead of the write
     * silently no-op'ing on ROMs that shadow blocked writes.
     *
     * The list of read-only linux prefixes is written to a config file the
     * wrappers source at run time, so re-mounting / toggling writability just
     * rewrites the config — no rootfs churn. When there are no read-only mounts
     * the wrappers are removed entirely so they add zero overhead in the common
     * case (every argument would pass the check anyway).
     *
     * [T-s6-#5] 这是 best-effort 的 argv 级防护，**不是权限边界**。旧注释声称
     * `>` 重定向"由 OS 返回 EACCES 所以已经真实失败"——那只对 **OS 级只读**
     * （SAF 授权本身不含写权限，宿主 open(2) 真的被拒）成立；用户级锁
     * （`effectiveWritable == false`）既不改宿主目录权限，PRoot `-b` 也没有只读
     * 修饰符，shell 照样写得进去（完整覆盖面清单见 [T-d10-os-ro]）。所以用户级锁实际
     * 只约束本 App 的文件工具（[isLinuxPathUnderReadOnlyMount]），UI 也如实披露了
     * 这一点（strings.xml `mount_add_allow_writes_desc`：the shell can still bypass this）。
     * 因此没有"按 store 收紧 `-b`"这条路：把 `effectiveWritable == false` 的挂载从
     * bind 集合里拿掉（或换成影子目录）会连**读**一起丢掉，而 Locked 挂载必须仍然
     * 可读；`setWritable(false,false)` 在 FUSE/emulated 存储上通常无效。
     *
     * [T-s6-#6] 写目标在 argv 里的位置逐命令定义——`cp`/`ln` 的源是读操作、`dd`
     * 只有 `of=` 是写——见 [MountWriteGuardScript]。
     *
     * [T-b4-#3] 只重写带 [GUARD_MARKER] 的自家 wrapper：用户 `make install` 放进
     * `/usr/local/bin`（PATH 中先于 `/bin`）的真实二进制绝不覆盖，与下面的删除
     * 路径判据一致。
     *
     * [T-b4-#3-cover] 但"同名即跳过"会让用户的二进制把这条命令的写防护整个吃掉，
     * 而失败面是**静默**的（写只读挂载不再报错）。所以安装位置改由纯逻辑
     * [MountWriteGuardTargets.pick] 决定，判据只有一条：**PATH 里更靠前的目录**有没有
     * 被别人的同名二进制占住。
     *
     * [T-d10-guard-pick-doc] 旧注释写成"两个目录都被占住才算这条命令的防护不可用"，
     * 与 `pick` 的实际判据不一致（文档漂移），这里按实现改正：
     *  - `/usr/local/bin` 被占住、`/usr/local/sbin` 可用 ⇒ 装进 `/usr/local/sbin`。
     *    sbin 在同一份默认 PATH 里排在前面，防护照样生效（上一轮静默失效的正是这条路径）；
     *  - `/usr/local/sbin` 被占住 ⇒ `pick` 直接返回 null，这条命令的防护**不可用**：
     *    装到 `/usr/local/bin` 也会被 sbin 里的同名二进制先命中。此时与 `/usr/local/bin`
     *    里是什么无关（那里空着也一样不可用），写 [MountWriteGuardStatus] 状态文件
     *    （guest 可 `cat`）并升级到 `Log.e`，不再只留一行 `Log.w`。
     *
     * [T-d10-os-ro] 关于"用户级锁（`userAllowWrite = false`）要不要加一层 OS 级只读兜底"
     * （对宿主挂载目录 chmod 0555 + 解锁/退出恢复 + 崩溃后启动期按持久化记录恢复）——
     * **评估结论是不实现**，理由三条：
     *  1. 覆盖不到真正需要它的路径。挂载只可能来自 SAF 的 externalstorage 树 URI
     *     （[resolveTreeUriToHostPath] 的 authority 判据），解出的宿主路径恒在
     *     `/storage/<volume>` 这类共享卷上，由 MediaProvider 的 FUSE 视图呈现，权限位是
     *     合成的、对持有它的应用不构成内核判据（同一原因使 `chmod +x` 在 /sdcard 上一直是
     *     空操作）——0555 只是把"能写"伪装成"只读"；
     *  2. 真生效就是全局副作用。那个目录同时属于用户和别的应用（文件管理器、相册），
     *     chmod 与恢复之间任何一次崩溃/被杀都让**用户自己**也写不进去，而 App 侧没有
     *     任何 UI 能把权限改回来；
     *  3. 恢复本身要靠"chmod 之前先落盘记录"，记录写失败或进程被杀就留下一个必须靠
     *     启动期修复的破坏性状态。用一次权限修改换一层不可靠的防护，风险大于收益。
     *
     * 于是，**用户级锁只有 argv 级 best-effort 防护**，这是本函数的边界，不是"还没做"：
     * 它拦不住红色 `>`/`>>` 重定向（重定向由 shell 在 execve **之前**自己 open(2)，
     * 无法从命令路径上拦）、`sed -i`/`truncate`/`rsync`/解释器内写、`/bin/busybox <cmd>`
     * 这类绝对路径绕过，以及相对路径目标（先 `cd` 进挂载目录再 `touch f`）。
     * 真正由内核拒绝的只有 **OS 级只读**（SAF 授权本身不含写权限，
     * `MountedFoldersStore` 的 `probeWritable` 为 false）——那一层与本函数无关。
     * UI 与文案如实披露同一件事（strings.xml `mount_add_allow_writes_desc`：
     * the shell can still bypass this）。
     */
    private fun installMountWriteGuards(context: Context, readOnlyLinuxPrefixes: List<String>) {
        val rootfs = try {
            RootfsManager.getInstance(context).rootfsDir
        } catch (t: Throwable) {
            return
        }
        val guardedCmds = MountWriteGuardScript.commands
        // [T-s6-#6] 脚本在 guest 命名空间里 source 这个路径，宿主侧由 CONFIG_PATH
        // 派生，避免两处各写一份常量。
        val configFile = File(rootfs, MountWriteGuardScript.CONFIG_PATH.removePrefix("/"))
        val binDir = File(rootfs, MountWriteGuardTargets.BIN).also { it.mkdirs() }
        val sbinDir = File(rootfs, MountWriteGuardTargets.SBIN).also { it.mkdirs() }

        if (readOnlyLinuxPrefixes.isEmpty()) {
            // No read-only mounts — remove the config + wrappers so plain busybox
            // commands run unimpeded.
            runCatching { configFile.delete() }
            for (dir in listOf(binDir, sbinDir)) {
                for (name in guardedCmds) clearOurWrapper(File(dir, name))
            }
            writeMountGuardStatus(
                rootfs,
                uncovered = emptyList(),
                total = guardedCmds.size,
                active = false,
            )
            Log.i(TAG, "installMountWriteGuards: no read-only mounts, guards cleared")
            return
        }

        configFile.parentFile?.mkdirs()
        configFile.writeText(readOnlyLinuxPrefixes.joinToString("\n", postfix = "\n"))

        val uncovered = mutableListOf<String>()
        for (name in guardedCmds) {
            val binFile = File(binDir, name)
            val sbinFile = File(sbinDir, name)
            // [T-b4-#3-cover] 见 [MountWriteGuardTargets]：优先历史位置 /usr/local/bin，
            // 被用户的同名二进制占住时兜底到 PATH 更靠前的 /usr/local/sbin。
            val target = MountWriteGuardTargets.pick(guardSlot(binFile), guardSlot(sbinFile))
            if (target == null) {
                uncovered += name
                Log.e(
                    TAG,
                    "installMountWriteGuards: $name is shadowed by a user binary that precedes every " +
                        "guard directory — writes into read-only mounts via $name are NOT blocked " +
                        "(see ${MountWriteGuardStatus.GUEST_PATH})",
                )
                continue
            }
            val wrapper = if (target == MountWriteGuardTargets.SBIN) sbinFile else binFile
            val other = if (target == MountWriteGuardTargets.SBIN) binFile else sbinFile
            // 另一个目录里自家留下的同名 wrapper 已被 PATH 上更靠前的那个盖住，留着
            // 只会让"防护装在哪"有两个答案；只删带哨兵的自家文件。
            clearOurWrapper(other)
            // [T-s6-#6] 每命令一套写目标语义（源 vs 目标、`dd of=`、`-t DIR`），
            // 模板与语义表都在 MountWriteGuardScript 里，便于单元测试。
            val script = MountWriteGuardScript.wrapperScript(name)
            val existing = if (wrapper.exists()) runCatching { wrapper.readText() }.getOrNull() else null
            // 内容没变就不重复写盘（9 个文件 × 每次挂载 CRUD），但要保证可执行位还在
            // （App 更新后可能丢）。
            if (existing == script) {
                wrapper.setExecutable(true, false)
                continue
            }
            wrapper.writeText(script)
            wrapper.setExecutable(true, false)
        }

        writeMountGuardStatus(rootfs, uncovered = uncovered, total = guardedCmds.size, active = true)
        // 先算好后缀：`+ if (…) … else … +` 的优先级会让 else 分支吞掉后半段日志。
        val uncoveredSuffix =
            if (uncovered.isEmpty()) "" else " — uncovered=${uncovered.joinToString(",")}"
        Log.i(
            TAG,
            "installMountWriteGuards: installed for ${guardedCmds.size - uncovered.size}/${guardedCmds.size} cmds" +
                "$uncoveredSuffix, readOnlyPrefixes=${readOnlyLinuxPrefixes.joinToString(",")}",
        )
    }

    /** 目录里同名文件与我们的关系：不存在 / 自家 wrapper / 别人的二进制。 */
    private fun guardSlot(file: File): MountWriteGuardTargets.Slot {
        if (!file.exists()) return MountWriteGuardTargets.Slot.FREE
        val text = runCatching { file.readText() }.getOrNull()
        return if (text != null && text.contains(GUARD_MARKER)) {
            MountWriteGuardTargets.Slot.OURS
        } else {
            // 读不出来 ⇒ 不是我们的。判据与 T-b4-#3 一致：绝不覆盖用户的同名二进制。
            MountWriteGuardTargets.Slot.FOREIGN
        }
    }

    /** 删掉自家哨兵 wrapper；用户的同名二进制一律不动。 */
    private fun clearOurWrapper(file: File) {
        if (guardSlot(file) != MountWriteGuardTargets.Slot.OURS) return
        runCatching { file.delete() }
    }

    /**
     * [T-b4-#3-cover] 把写防护的可用性写进 guest 可见的 `/run` 状态文件：某条命令的
     * wrapper 被用户二进制从 PATH 上整个盖住时，防护在那条命令上**不可用**，这件事
     * 必须能从 guest 侧查证（`cat /run/minis-mount-guards.txt`），而不是只留一行日志。
     * 与 HostStatusPublisher 的 `/run/minis-host-status.json` 同一约定。
     */
    private fun writeMountGuardStatus(
        rootfs: File,
        uncovered: List<String>,
        total: Int,
        active: Boolean,
    ) {
        val runDir = File(rootfs, "run").also { it.mkdirs() }
        val target = File(runDir, MountWriteGuardStatus.FILE_NAME)
        val text = MountWriteGuardStatus.text(total = total, uncovered = uncovered, active = active)
        runCatching {
            if (target.isFile && target.readText() == text) return@runCatching
            target.writeText(text)
        }.onFailure { Log.w(TAG, "writeMountGuardStatus failed: ${it.message}") }
    }

    /**
     * Get the cache directory for PROOT_TMP_DIR.
     * Must be called after boot().
     */
    internal fun getProotTmpDir(context: Context): File {
        val tmpDir = File(context.cacheDir, "proot-tmp")
        tmpDir.mkdirs()
        return tmpDir
    }
}

/**
 * [T-b4-#3-cover] 只读挂载写防护 wrapper 的安装位置（按 guest PATH 顺序）。
 *
 * 背景：`/usr/local/bin` 在默认 PATH 里先于 `/bin`，又是 `make install` 的默认落点，
 * 用户完全可能往那儿放一个真实的 `cp`/`rm`/`dd`。上一轮（[T-b4-#3]）为了避免销毁
 * 用户二进制改成"同名文件不是自己装的就跳过"，但那让这条命令的写防护**静默失效**
 * （写只读挂载照样成功，用户/agent 无从得知）。
 *
 * 这里恢复覆盖：`/usr/local/sbin` 在同一份默认 PATH 里排在 `/usr/local/bin` **之前**
 * （见 [PRootKernel.boot] 注入的 PATH、RootfsManager 的 default_mount、
 * McpNativeBridge 与 TerminalSession 的 env，四处一致），wrapper 兜底装到那里就既能
 * 保住用户的二进制、又保住防护。只有 PATH 里更靠前的目录被用户的同名二进制占住时，
 * 这条命令的写防护才是真的不可用——那时由 [MountWriteGuardStatus] 写成显式状态。
 *
 * 抽成纯逻辑是为了让"装到哪"能被测试固定住（见 `MountWriteGuardTargetsTest`）。
 */
internal object MountWriteGuardTargets {

    /** 历史落点（T219-4 一直用这里），PATH 中先于 `/usr/bin:/bin`。 */
    const val BIN = "usr/local/bin"

    /** 兜底落点：同一份默认 PATH 里排在 [BIN] 之前。 */
    const val SBIN = "usr/local/sbin"

    /** 目录里同名文件与我们的关系。 */
    enum class Slot { OURS, FREE, FOREIGN }

    /**
     * 返回该命令的 wrapper 应该装到哪个目录（相对 rootfs 的路径）。
     * null = 这条命令的写防护在本机不可用：PATH 中更靠前的目录被用户的同名二进制
     * 占住，wrapper 装到哪儿都会被它盖掉。
     */
    fun pick(bin: Slot, sbin: Slot): String? = when {
        // sbin 先于 bin：它被别人的同名二进制占住时，我们没有更靠前的落点。
        sbin == Slot.FOREIGN -> null
        // bin 被占住 ⇒ 用更靠前的 sbin 兜底（正是 T-b4-#3 静默失效的那条路径）。
        bin == Slot.FOREIGN -> SBIN
        else -> BIN
    }
}

/**
 * [T-b4-#3-cover] 写防护可用性的 guest 可见状态（`/run/minis-mount-guards.txt`）。
 *
 * 为什么要有这个文件：防护被用户的同名二进制盖住时，唯一的失败面是"写只读挂载不再
 * 报错"——静默的 fail-open。日志在 App 侧，agent 与用户都看不到，所以把结论落到
 * guest 可读的文件里（与 [HostStatusPublisher] 的 `/run/minis-host-status.json` 同一
 * 约定），任何 `cat` 都能查证"哪些命令没有防护、为什么"。
 */
internal object MountWriteGuardStatus {

    const val FILE_NAME = "minis-mount-guards.txt"
    const val GUEST_PATH = "/run/$FILE_NAME"

    /** 纯文本、逐行 key=value，便于 `grep`/`cut` 这类最小工具箱消费。 */
    fun text(total: Int, uncovered: List<String>, active: Boolean): String = buildString {
        append("# written by MinisApp PRootKernel.installMountWriteGuards — do not edit\n")
        append("guard_active=$active\n")
        append("guard_command_total=$total\n")
        append("guard_command_uncovered_count=${uncovered.size}\n")
        append("guard_command_uncovered=${uncovered.joinToString(",")}\n")
        append(
            "note=" + when {
                // 没有只读挂载时 wrapper 全部卸掉（见 installMountWriteGuards 的早退分支）：
                // 状态文件必须如实说明"没装"，不能写成"全都装好了"。
                !active -> "no read-only mounted folder: guards are not installed (nothing to protect)\n"
                uncovered.isEmpty() ->
                    "every guarded command resolves to a Minis write guard on the default PATH " +
                        "(/usr/local/sbin:/usr/local/bin:…)\n"
                else ->
                    // 说明为什么这些命令没有防护：PATH 里更靠前的同名用户二进制把 wrapper 盖住了。
                    "a user binary with the same name precedes /usr/local/sbin and " +
                        "/usr/local/bin, so writes into read-only mounts via those commands are NOT blocked\n"
            },
        )
    }
}
