package com.openminis.app.tools

import com.openminis.app.sandbox.SessionWorkspace
import java.io.File

/**
 * [T-tools-files-walk-jail] 递归读取（grep_source / glob / code_graph / list_dir）的逐项归属复检。
 *
 * 根路径由 `PRootKernel.resolveSessionHostPath` → [SessionWorkspace.acceptsResolved]
 * 判定过归属，**遍历出来的每一项没有**：`FileTreeWalk` 与 `File.listFiles()` 都会跟随
 * 目录软链（`File.isDirectory` 解引用），而 guest 侧的 `ln -s ../../.. leak` 就是宿主上
 * 的真实软链（proot 的 `--link2symlink` 只翻译 link(2)，并不改写 symlink 目标）。
 * 于是 `workspace/leak/minis-sessions/<别的会话>/memory/secret.md` 会被当成工作区文件
 * 逐个读出来——实测一条软链能命中 63 个跨会话/宿主敏感文件。
 *
 * 修法：每一步都用 canonical 路径复跑「是否仍在根子树内」这一归属判定。根自身已由
 * 调用方判定过，子树才需要逐项复检；canonical 化失败一律按**不属于本树**处理
 * （fail-closed：读不到文件是可接受的，读到别人的文件不是）。
 *
 * [T-walk-cage-single-resolve] 第四轮：根只解析**一次**。
 * `PRootKernel.resolveSessionHostPath` 现在回传它复检时用的那条 canonical 路径，
 * 工具侧用 [SessionTreeGuard.verified] 把它**钉**成基准（不再自己解析第二次），
 * [walkOwnedTree] 在开始遍历前还会确认根路径此刻仍解析到同一个目标。旧写法里
 * 「复检」与「遍历」各解析一次：guest 只要在这两次之间把中间分量换成软链，守卫就会
 * **以新目标为根**做包含判定——判定与新根自洽，所以既不报错也不丢弃，直接把根外子树
 * 列出来。
 *
 * 仍然存在的窗口（**收窄，不是消灭**）：从上一次解析到真正的 `listFiles()` / `open()`
 * 之间，路径上的任何分量（目录分量，或末级文件本身）都可能被 rename 掉再放一条软链
 * 上去，操作系统每次都会重新解析。`java.io.File` 没有 fd 相对的 `openat` 遍历语义，
 * 本层挡不住这一手。本层保证的是：**判定基准不会在判定之后被悄悄换掉**（钉子 +
 * 逐项 canonical 复检），所以即便换掉了，产出集合也会落在旧基准之外而被丢弃——
 * 失败方向是「少读」，不是「读错」。
 */
internal class SessionTreeGuard private constructor(
    /** 非 null ⇒ 调用方已复检过的 canonical 根：**不再解析第二次**（见 [verified]）。 */
    private val pinnedRoot: File?,
    root: File,
) {

    /**
     * 归属判定的基准。
     *
     * 走 [verified] 时就是调用方复检用的那条 canonical 路径（原样，不再解析）；
     * 否则构造时解析一次，拿不到就退化成绝对路径（判定只会更严不会更松）。
     *
     * [T-tools-files-walk-realpath] [walkOwnedTree] 产出的每一项都在它的子树内，
     * 所以调用方算**相对路径**（glob 的模式匹配、@-mention 拼 `/var/minis/...`）
     * 必须以它当基准，而不是调用方手里那个可能含软链的词法根：两者不一致时
     * `relativeTo(词法根)` 会算出 `../../x`，而 glob 的 `**` 恰好匹配得上，等于放行。
     */
    val canonicalRoot: File = pinnedRoot ?: canonicalOrNullOf(root) ?: root.absoluteFile

    /** [canonicalRoot] 去掉尾部分隔符后的路径（根是 `/` 或 `C:\` 时为空串）。 */
    private val basePath: String = canonicalRoot.path.trimEnd(File.separatorChar, '/')

    /** 子项前缀 = [basePath] + 分隔符（根是 `/` 时就是 `/`）。 */
    private val basePrefix: String = if (basePath.isEmpty()) File.separator else basePath + File.separator

    /** canonical 路径；失败返回 null（调用方按 fail-closed 处理）。 */
    fun canonicalOrNull(file: File): File? = canonicalOrNullOf(file)

    /**
     * [candidate] 是否仍在根的 canonical 子树内。
     *
     * 判定所用的 canonical 路径由调用方（或 [owns]）算出来；基准是构造时那条。
     */
    fun owns(candidate: File): Boolean {
        val real = canonicalOrNull(candidate) ?: return false
        return ownsCanonical(real)
    }

    /**
     * 已经 canonical 化过的 [real] 版本判定（遍历里省一次 realpath）。
     *
     * [T-walk-cage-single-resolve] 与 [depthOf] 是**同一个**前缀判定（`real` 不在基准
     * 子树内 ⇔ `depthOf` 为 null），所以 walk 产出的每一项都必然非 null。
     *
     * 这里刻意**不**再对基准跑一次 `SessionWorkspace.staysInside`：那个 API 会对两侧都做
     * canonical 化，于是「基准」会在每次判定时被重新解析一次——根被换成软链之后基准会
     * 悄悄跟着漂到新目标，新的目标子树随即全部判定为「在根内」。基准固定在构造时是
     * 钉子生效的前提。
     */
    fun ownsCanonical(real: File): Boolean = depthOf(real) != null

    /**
     * [T-tools-files-walk-realpath] 已 canonical 化的 [real] 相对 [canonicalRoot] 的深度：
     * 直接子项 = 1、根自身 = 0、不在根子树内 = null（调用方按 fail-closed 丢弃）。
     *
     * 与 [SessionWorkspace.staysInside]/`contains` 完全同一套分隔符语义。
     */
    fun depthOf(real: File): Int? {
        val path = real.path.trimEnd(File.separatorChar, '/')
        if (path == basePath) return 0
        if (!path.startsWith(basePrefix)) return null
        var depth = 1
        for (i in basePrefix.length until path.length) {
            if (path[i] == File.separatorChar) depth++
        }
        return depth
    }

    /**
     * [T-tools-files-walk-realpath] 已 canonical 化的 [real] 相对 [canonicalRoot] 的
     * 斜杠分隔相对路径（根自身 = 空串）；不在根子树内返回 null。
     *
     * 这就是「按 real 路径算出来的相对路径」：调用方拿它拼 `/var/minis/<scope>/<rel>`，
     * 或直接喂给 glob 模式，得到的都是真实存在的那个文件，而不是那条可以被改写的软链。
     */
    fun relativePath(real: File): String? {
        if (depthOf(real) == null) return null
        val path = real.path.trimEnd(File.separatorChar, '/')
        if (path == basePath) return ""
        return path.substring(basePrefix.length).replace(File.separatorChar, '/')
    }

    companion object {
        /**
         * 根由本层自己解析（测试、@-mention 索引，以及调用方没有复检过的根）。
         *
         * 这类调用方只解析这一次，所以不存在「两次解析之间被改向」的窗口；但解析结果
         * 也没有经过归属复检，不能当成 [verified] 用。
         */
        operator fun invoke(root: File): SessionTreeGuard = SessionTreeGuard(null, root)

        /**
         * [T-walk-cage-single-resolve] 调用方**已经复检过**的 canonical 根
         * （`PRootKernel.resolveSessionHostPath` 的回传值）。
         *
         * 守卫把这条路径原样当基准，不再解析第二次：否则「复检通过的 C1」与「守卫解析出来
         * 的 C2」又成了两次解析，而这正是本轮要关掉的那扇门。[walkOwnedTree] 另外会在遍历
         * 开始前确认根路径此刻仍解析到这条基准，不再解析到就整棵不产出（fail-closed）。
         */
        fun verified(canonicalRoot: File): SessionTreeGuard =
            SessionTreeGuard(canonicalRoot, canonicalRoot)

        /**
         * realpath；失败返回 null。
         *
         * [T-symlink-realpath] 用 toRealPath 而不是 canonicalFile：Windows 上
         * File.canonicalPath **不解软链**（探针实测 JDK17/21；只有 toRealPath 解），
         * 于是同一段归属判定在开发机上会把越权软链判成「仍在根内」——单测因此在
         * Windows 上假绿/假红。toRealPath 在 Android/Linux 与 canonicalPath 等价
         * （都是 realpath(3)），在 Windows 上也真正解析。
         */
        private fun canonicalOrNullOf(file: File): File? =
            try {
                file.toPath().toRealPath().toFile()
            } catch (_: Exception) {
                null
            }
    }
}

/**
 * [T-tools-files-walk-jail] 归属安全的递归遍历（前序 DFS，惰性）。
 *
 * - 只产出 [root] 的**子树**节点（根自身不产出：它已由调用方判定过归属，自指软链解析回
 *   根的那种情形也不产出，见下）；
 * - 每个子项在产出前做 canonical 归属复检，根外的软链文件/目录一律不产出也不进入；
 * - [enter] 是各工具自己的目录跳过规则（`.git` / `node_modules` …），只在**目录**上调用；
 * - 每个目录按 canonical 路径只进入一次：`ln -s . self` 这类自指软链否则会让遍历变成
 *   无穷递归（「指向根内祖先」的软链是能通过归属判定的，只能靠去重挡住）。
 *
 * [T-tools-files-walk-realpath] 产出的是 **canonical（real）路径**，不是词法 child。
 * 旧实现产出词法路径，于是复检与「打开」之间夹着一个 100% 可赢的窗口：guest 在工作区里
 * 放一条 `alias -> /data/.../minis-sessions/sess-b/memory`（或干脆把遍历根本身换成软链）
 * 之后，`workspace/alias/secret.md` 这种**词法**路径在「复检通过」与「调用方 open」之间
 * 被改成别的目标就能读出去——复检判定的是当时那一刻的 canonical 目标，而 open 重新解析
 * 一遍软链。改成产出 real 路径之后，调用方打开的路径里已经没有任何软链分量可以被改写。
 *
 * [T-walk-cage-single-resolve] 根也只解析一次：[root] 必须是调用方复检过的 canonical 路径
 * （`PRootKernel.resolveSessionHostPath` 的回传值），配 [SessionTreeGuard.verified] 使用；
 * 本函数在开始遍历前确认 [root] 此刻仍解析到守卫的基准，解析不到别的目标就整棵不产出
 * （fail-closed）。旧写法在工具侧再解析一次，并以解析出来的新目标为根。
 *
 * [T-walk-root-self] 自指软链（`ln -s . self`、`ln -s <root> self`）canonical 之后就是根
 * 自身：它虽然通过归属判定，但按「只产出子树节点」的契约必须丢掉，否则 `list_dir` 会把根
 * 以自身 basename 列成一个子目录。
 *
 * 仍然存在的窗口（**收窄，不是消灭**）：从最后一次解析到具体的 `listFiles()` / `open()`
 * 之间，路径分量仍可被 rename + link 顶替——`java.io.File` 是按路径逐次解析的，没有
 * `openat` 那样的 fd 相对遍历，任何一次系统调用都会重新解析整条路径。下一轮若要彻底
 * 闭合，需要在遍历与打开两侧都改成 fd 相对（或改用 `Files.newDirectoryStream` +
 * `NOFOLLOW_LINKS` 并在每一步校验文件键），那超出 `java.io.File` 的表达能力。
 *
 * 调用方注意：算相对路径（`File.relativeTo`）必须用 [SessionTreeGuard.canonicalRoot] 当基准
 * （见 [SessionTreeGuard.relativePath]），不能用调用方自己的词法根。
 *
 * 用显式栈而不是递归：回环软链下递归会先把调用栈打穿。
 */
internal fun walkOwnedTree(
    root: File,
    guard: SessionTreeGuard = SessionTreeGuard(root),
    enter: (File) -> Boolean = { true },
): Sequence<File> = sequence {
    if (!root.isDirectory) return@sequence
    // [T-walk-cage-single-resolve] 根路径必须**此刻仍**解析到守卫的基准。被钉住的基准是
    // 调用方复检时那条 canonical 路径，这里解析不到它就只有两种可能：分量被换掉了，
    // 或者路径已经消失。两种都按 fail-closed 处理（整棵不产出），绝不拿新目标当根。
    val resolvedRoot = guard.canonicalOrNull(root)
    if (resolvedRoot != null && resolvedRoot.path != guard.canonicalRoot.path) return@sequence
    val visited = HashSet<String>()
    resolvedRoot?.let { visited.add(it.path) }

    // 栈里存的是「上一层还没走完的兄弟列表」，弹出顺序与 listFiles() 顺序一致。
    val parents = java.util.ArrayDeque<Iterator<File>>()
    var level: Iterator<File> = childrenOf(root)
    while (true) {
        if (!level.hasNext()) {
            if (parents.isEmpty()) break
            level = parents.removeLast()
            continue
        }
        val real = guard.canonicalOrNull(level.next()) ?: continue
        // 归属复检：canonical 之后不在根子树内（null）就丢弃；深度 0 说明它就是根本身
        // （自指软链），按契约不产出——`list_dir` 否则会把根列成自己的子目录。
        val depth = guard.depthOf(real) ?: continue
        if (depth == 0) continue
        yield(real)
        if (!real.isDirectory) continue
        if (!enter(real)) continue
        if (!visited.add(real.path)) continue
        parents.addLast(level)
        // 用 real 目录列子项：列目录本身也不再经过软链（词法路径列目录同样可被改向）。
        level = childrenOf(real)
    }
}

private fun childrenOf(dir: File): Iterator<File> =
    (try {
        dir.listFiles()
    } catch (_: Exception) {
        null // 权限/IO 失败与 FileTreeWalk 的默认 onFail 一致：当空目录处理
    })?.iterator() ?: emptyList<File>().iterator()
