package com.openminis.app.sandbox

import com.openminis.app.logging.AppLogger
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * Host layout for chat workspaces.
 *
 * Per session (`filesDir/minis-sessions/<id>/`):
 *   memory  — daily logs, deleted with the session
 *
 * Per project folder (`filesDir/minis-workspaces/<folderId>/`), shared by every
 * session filed in that folder:
 *   attachments, offloads, workspace, browser
 *
 * Ungrouped sessions keep the 1.36.13 layout: those four dirs live under
 * `minis-sessions/<id>/` so isolation is preserved until they are filed.
 *
 * Shared across sessions (`filesDir/minis-global/`):
 *   skills, shared, mcp-servers
 *
 * SOUL.md / LEARNED.md stay under minis-global/memory (not bind-mounted as
 * `/var/minis/memory`). `/var/minis/memory` is this chat's daily logs.
 */
// Cache-bust marker: the previous CI run compiled a stale source tree where
// File.isDirectory was still written as a property. This comment exists so the
// next run's source fingerprint differs from the cached one.
object SessionWorkspace {
    const val SESSIONS_DIR = "minis-sessions"
    const val WORKSPACES_DIR = "minis-workspaces"
    const val GLOBAL_DIR = "minis-global"

    val SHARED_SUBDIRS: List<String> = listOf(
        "attachments",
        "offloads",
        "workspace",
        "browser",
    )

    val SESSION_SUBDIRS: List<String> = SHARED_SUBDIRS + "memory"

    val GLOBAL_BIND_SUBDIRS: List<String> = listOf(
        "skills",
        "shared",
        "mcp-servers",
    )

    private val folderIds = ConcurrentHashMap<String, String>()

    /**
     * [T-android-startup-splash-hang] Released once this process has loaded the
     * folder map from the database (or tried and failed — see
     * `ChatRepository.warmupWorkspaceOwners`, which completes it in a `finally`).
     *
     * The warmup used to be a `runBlocking(Dispatchers.IO)` on the **main
     * thread** inside `Application.onCreate`, justified by "must finish before
     * the first file tool or shell". That made the first Room open, any pending
     * schema migrations, and a full hydration of the sessions table all happen
     * before `subsystemsInitialized` was set and before `MainActivity` even
     * existed — so the system splash (a white `windowBackground`) stayed up for
     * as long as the user's history took to load, and past ~5s it was an ANR.
     * Cost scaled with session count, which is why only *some* users saw it.
     *
     * The warmup now runs on `Dispatchers.IO` like everything else at startup,
     * and the ordering guarantee is this gate instead of a blocked UI thread:
     * the launch resolver awaits it before mounting any destination, so no chat
     * screen — and therefore no shell or file tool — can exist before the map
     * is populated.
     */
    private val warmupGate = kotlinx.coroutines.CompletableDeferred<Unit>()

    /** Idempotent. Safe to call from a `finally`, and safe to call twice. */
    fun completeWarmup() {
        warmupGate.complete(Unit)
    }

    /**
     * Wait for the folder map, bounded. The timeout is a safety valve, not the
     * normal path: the warmup is one indexed two-column query, so it completes
     * in milliseconds. A stuck warmup must degrade to "session looks unfiled"
     * rather than hang the launcher forever.
     */
    suspend fun awaitWarmup(timeoutMillis: Long = 3_000L) {
        kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) { warmupGate.await() }
    }

    fun rememberFolder(sessionId: String, folderId: String?) {
        if (!isSafeId(sessionId)) return
        if (folderId.isNullOrBlank()) folderIds.remove(sessionId)
        else if (isSafeId(folderId)) folderIds[sessionId] = folderId
    }

    fun folderIdFor(sessionId: String): String? {
        parseDraftFolderId(sessionId)?.let { return it }
        val owner = ownerSessionId(sessionId)
        return folderIds[owner]
    }

    fun parseDraftFolderId(sessionId: String): String? {
        val i = sessionId.indexOf("__fld__")
        if (i < 0) return null
        val rest = sessionId.substring(i + 7)
        val end = rest.indexOf("__grp__")
        val id = if (end >= 0) rest.substring(0, end) else rest
        return id.takeIf { isSafeId(it) }
    }

    fun isSafeId(id: String): Boolean {
        if (id.isBlank() || id == "." || id == "..") return false
        if (id.contains('/') || id.contains('\\')) return false
        return true
    }

    fun base(filesDir: File, sessionId: String): File =
        File(filesDir, "$SESSIONS_DIR/$sessionId")

    fun projectBase(filesDir: File, folderId: String): File =
        File(filesDir, "$WORKSPACES_DIR/$folderId")

    fun memoryDir(filesDir: File, sessionId: String): File =
        File(base(filesDir, ownerSessionId(sessionId)), "memory")

    /**
     * Host directory mounted at `/var/minis/[subdir]`. Shared project dirs when
     * the session is filed in a folder; otherwise per-session.
     *
     * A filed session whose project directory is MISSING falls back to its own
     * private dir. Without this, the mount resolves to a path that does not
     * exist and the sandbox comes up with an empty `/var/minis` — which reads
     * to the user as "my files vanished", when in fact nothing was ever moved
     * (an interrupted [WorkspaceMover] run, or a project dir deleted behind
     * our back). The private copy is still the authoritative one in that case,
     * so preferring it is also the non-destructive choice. The fallback is
     * logged at warning level because it means the recorded layout and the
     * on-disk layout disagree.
     */
    fun hostDir(filesDir: File, sessionId: String, subdir: String): File {
        val folderId = folderIdFor(sessionId)
        if (folderId != null && subdir in SHARED_SUBDIRS) {
            val projectRoot = projectBase(filesDir, folderId)
            val shared = File(projectRoot, subdir)
            if (hasEntries(shared)) return shared
            val privateDir = File(base(filesDir, ownerSessionId(sessionId)), subdir)
            if (hasEntries(privateDir)) {
                AppLogger.warning(
                    "SessionWorkspace",
                    "project subdir '$subdir' empty for folder=$folderId " +
                        "session=$sessionId — using private copy",
                )
                return privateDir
            }
            if (shared.isDirectory()) return shared
            if (projectRoot.isDirectory()) {
                AppLogger.warning(
                    "SessionWorkspace",
                    "project dir missing subdir '$subdir' for folder=$folderId " +
                        "session=$sessionId — falling back to the private copy",
                )
            }
        }
        return File(base(filesDir, ownerSessionId(sessionId)), subdir)
    }

    private fun hasEntries(dir: File): Boolean =
        dir.isDirectory && dir.listFiles()?.isNotEmpty() == true

    /**
     * Resolve a `/var/minis/<subdir>/...` path to the same host file the shell
     * bind uses. Returns null for `..` or paths this layout does not own.
     */
    fun resolveGuestPath(filesDir: File, sessionId: String, linuxPath: String): File? {
        if (!linuxPath.startsWith("/var/minis/")) return null
        val rest = linuxPath.removePrefix("/var/minis/")
        if (rest.isEmpty()) return null
        val parts = rest.split('/')
        if (parts.any { it == ".." }) return null
        val subdir = parts[0]
        if (subdir.isEmpty()) return null
        val root = when (subdir) {
            in SESSION_SUBDIRS -> hostDir(filesDir, sessionId, subdir)
            in GLOBAL_BIND_SUBDIRS -> File(File(filesDir, GLOBAL_DIR), subdir)
            else -> return null
        }
        val tail = parts.drop(1).filter { it.isNotEmpty() }.joinToString("/")
        return if (tail.isEmpty()) root else File(root, tail)
    }

    fun ensureProjectDirs(filesDir: File, folderId: String) {
        if (!isSafeId(folderId)) return
        val root = projectBase(filesDir, folderId)
        for (subdir in SHARED_SUBDIRS) {
            File(root, subdir).mkdirs()
        }
    }

    fun ensureDirs(filesDir: File, sessionId: String) {
        val root = base(filesDir, sessionId)
        File(root, "memory").mkdirs()
        val folderId = folderIdFor(sessionId)
        if (folderId != null) {
            ensureProjectDirs(filesDir, folderId)
        } else {
            for (subdir in SHARED_SUBDIRS) {
                File(root, subdir).mkdirs()
            }
        }
    }

    /** Delete the whole session tree, including memory. Does not touch a shared project. */
    fun deleteEntire(filesDir: File, sessionId: String): Boolean {
        if (!isSafeId(sessionId)) return false
        folderIds.remove(sessionId)
        val dir = base(filesDir, sessionId)
        if (!dir.exists()) return true
        return dir.deleteRecursively()
    }

    fun deleteProject(filesDir: File, folderId: String): Boolean {
        if (!isSafeId(folderId)) return false
        val dir = projectBase(filesDir, folderId)
        if (!dir.exists()) return true
        return dir.deleteRecursively()
    }

    fun ownerSessionId(sessionId: String): String {
        val prefix = "subagent:"
        if (!sessionId.startsWith(prefix)) return sessionId
        val rest = sessionId.substring(prefix.length)
        val cut = rest.lastIndexOf(':')
        return if (cut > 0) rest.substring(0, cut) else sessionId
    }

    /**
     * True when [candidate] does not climb out of [root]. Symlinks are resolved.
     *
     * [T-symlink-realpath-failclosed] Both sides go through [normalized], so a
     * side whose existing part cannot be resolved to a real path is a **no**:
     * "cannot tell" must never read as "inside".
     */
    fun staysInside(root: File, candidate: File): Boolean {
        val realRoot = normalized(root) ?: return false
        val realCandidate = normalized(candidate) ?: return false
        return contains(realRoot, realCandidate)
    }

    /**
     * [T-file-tool-symlink-jail] Lexical containment — `..` is folded away but
     * symlinks are NOT resolved, so this answers "which tree owns the path".
     * [contains] over canonical paths then answers "may it be opened".
     */
    private fun staysInsideLexical(root: File, candidate: File): Boolean =
        contains(lexicalNormalize(root), lexicalNormalize(candidate))

    private fun contains(root: File, candidate: File): Boolean {
        val base = root.path.trimEnd('\\', '/')
        val file = candidate.path.trimEnd('\\', '/')
        return file == base || file.startsWith(base + File.separator)
    }

    /**
     * realpath（软链真正解析），解析不出来返回 null。
     *
     * [T-symlink-realpath] 用 toRealPath 而不是 canonicalFile：Windows 上
     * File.canonicalPath **不解软链**（探针实测 JDK17/21；只有 toRealPath 解），
     * 于是同一段归属判定在开发机上会把越权软链判成「仍在根内」——单测因此在
     * Windows 上假绿/假红。toRealPath 在 Android/Linux 与 canonicalPath 等价
     * （都是 realpath(3)），在 Windows 上也真正解析。
     *
     * [T-symlink-realpath-failclosed] 只解析**已经存在**的那一段：最深的存在祖先
     * 走 toRealPath（软链在这里被解掉），其后尚不存在的尾部分量按词法追加——不存在
     * 的分量不可能是软链，无法把路径改向。
     *
     * 旧写法是「整条路径 toRealPath，抛异常就退回 lexicalNormalize」，于是
     * `leak -> ../../..` 这类越权软链下 `leak/brand-new.txt`（`file_write` 建新文件
     * 的形状，整条路径都不存在）退回纯词法判定：「词法上在 sess-a 内」直接放行——
     * 这正是 `SessionWorkspaceTest:149` 的假绿。锚定存在祖先之后判定用的是 `leak`
     * 的**真实目标**（filesDir），越权在「建新文件」这条路径上同样被拒。
     *
     * 连最深的存在祖先都解析不出来（权限/IO 错误，或该分量本身是指向不存在目标的
     * 悬空软链——它的真实目标不可知，现在按词法放行就等于把目标留给判定之后去补）
     * ⇒ null，调用方一律按拒绝处理（fail-closed）。
     */
    private fun normalized(file: File): File? {
        var prefix: File = lexicalNormalize(file)
        val missing = ArrayDeque<String>()
        while (true) {
            try {
                val real = prefix.toPath().toRealPath().toFile()
                if (missing.isEmpty()) return real
                return File(real, missing.joinToString(File.separator))
            } catch (_: Exception) {
                // 悬空软链：真实目标不可知，绝不按词法路径放行。
                if (Files.isSymbolicLink(prefix.toPath())) return null
                if (prefix.name.isEmpty()) return null
                val parent = prefix.parentFile ?: return null
                missing.addFirst(prefix.name)
                prefix = parent
            }
        }
    }

    /**
     * File tools may read the caller's private tree, the caller's project
     * tree, rootfs, and global skills. They must not land in another
     * session's `minis-sessions/<other>` or another project's
     * `minis-workspaces/<other>`.
     *
     * [T-file-tool-symlink-jail] `externalRoots` is the whitelist of host trees
     * outside `minis-sessions`/`minis-workspaces` a resolved path may point
     * into (rootfs + bind-mount host bases); PRootKernel injects it so this
     * object stays free of a sandbox→PRootKernel dependency. Anything outside
     * every named tree is REFUSED.
     *
     * The old fallback here was `return true`. A guest `ln -s ../../.. leak`
     * inside the workspace is a real host symlink (proot's `--link2symlink`
     * translates link(2), it does not rewrite symlink targets), so
     * `workspace/leak/...` is lexically inside the session tree while its
     * canonical path is not — the two `staysInside` branches above both failed
     * and the fallback waved the escaped path through, and the file tools then
     * read/wrote straight through the link. Which tree owns the path is decided
     * lexically; the canonical path decides whether it is still inside it.
     *
     * [T-walk-cage-single-resolve] Callers that go on to **walk or open** the
     * accepted path must use [verifiedCanonical] instead: this Boolean overload
     * throws away the canonical path it just computed, so the tool side resolves
     * the same lexical path a second time and can end up rooted somewhere else.
     */
    fun acceptsResolved(
        filesDir: File,
        sessionId: String,
        candidate: File,
        externalRoots: List<File> = emptyList(),
    ): Boolean = verifiedCanonical(filesDir, sessionId, candidate, externalRoots) != null

    /**
     * [T-walk-cage-single-resolve] [acceptsResolved] as a resolve+verify pair:
     * returns **the canonical path this very check was made against**, or null
     * when the path is refused (fail-closed).
     *
     * Why the extra return value: which tree owns a path is decided lexically
     * (`..` folded, symlinks kept), whether it is still inside that tree is
     * decided canonically. The Boolean API left the caller holding the lexical
     * path, and the tool side (`SessionTreeGuard` / `walkOwnedTree` / `list_dir`)
     * then resolved it again — a guest that re-pointed an intermediate component
     * between the two resolutions made the walk adopt the *new* target as its
     * root, and the containment check, judged against that new root, happily
     * passed. Handing back the verified canonical path removes the second
     * resolution: the path the tool walks no longer contains any symlink
     * component at the moment it is handed over.
     *
     * Residual window (not closable here): replacing a component of that
     * canonical path with a symlink *after* this call makes the OS resolve the
     * new target on the next `listFiles()`/`open()` — `java.io.File` has no
     * fd-relative (`openat`) traversal. The callers pin this path as their
     * baseline and re-check every item, so that race degrades to "reads less"
     * instead of "reads someone else's tree". See `SessionTreeWalk`'s KDoc.
     */
    fun verifiedCanonical(
        filesDir: File,
        sessionId: String,
        candidate: File,
        externalRoots: List<File> = emptyList(),
    ): File? {
        val owner = ownerSessionId(sessionId)
        if (!isSafeId(owner)) return null
        val lexical = lexicalNormalize(candidate)
        // Canonical once: symlinks anywhere in the path are resolved here, and
        // every containment test below compares canonical paths.
        // [T-symlink-realpath-failclosed] 解析不出 real 路径 ⇒ 直接拒，不退回词法判定。
        val real = normalized(lexical) ?: return null
        val sessions = File(filesDir, SESSIONS_DIR)
        val projects = File(filesDir, WORKSPACES_DIR)
        val global = File(filesDir, GLOBAL_DIR)
        val accepted = when {
            staysInsideLexical(sessions, lexical) ->
                inside(File(sessions, owner), lexical, real)
            staysInsideLexical(projects, lexical) -> {
                val folder = folderIdFor(sessionId) ?: return null
                if (!isSafeId(folder)) return null
                inside(File(projects, folder), lexical, real)
            }
            // /var/minis/{skills,shared,mcp-servers}
            staysInsideLexical(global, lexical) ->
                contains(normalized(global) ?: return null, real)
            else -> externalRoots.any { root ->
                contains(normalized(root) ?: return@any false, real)
            }
        }
        return if (accepted) real else null
    }

    /** [T-file-tool-symlink-jail] Lexically inside [jail] and still there once symlinks resolve. */
    private fun inside(jail: File, lexical: File, real: File): Boolean {
        if (!staysInsideLexical(jail, lexical)) return false
        val realJail = normalized(jail) ?: return false
        return contains(realJail, real)
    }

    internal fun lexicalNormalize(file: File): File {
        val abs = file.absolutePath
        val prefix: String
        val rest: String
        when {
            abs.length >= 2 && abs[1] == ':' -> {
                prefix = abs.substring(0, 2)
                rest = abs.substring(2)
            }
            abs.startsWith("\\\\") -> {
                prefix = "\\\\"
                rest = abs.removePrefix("\\\\")
            }
            abs.startsWith("/") || abs.startsWith("\\") -> {
                prefix = File.separator
                rest = abs.trimStart('/', '\\')
            }
            else -> {
                prefix = ""
                rest = abs
            }
        }
        val parts = ArrayDeque<String>()
        for (seg in rest.split('/', '\\')) {
            when {
                seg.isEmpty() || seg == "." -> Unit
                seg == ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(seg)
            }
        }
        val body = parts.joinToString(File.separator)
        val path = when {
            prefix.endsWith(":") -> prefix + File.separator + body
            prefix == File.separator -> File.separator + body
            prefix == "\\\\" -> "\\\\$body"
            else -> body
        }
        return File(path)
    }
}
