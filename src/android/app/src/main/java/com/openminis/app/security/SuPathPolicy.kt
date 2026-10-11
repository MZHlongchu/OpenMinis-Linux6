package com.openminis.app.security

import com.openminis.app.sandbox.SessionWorkspace

// [T-supath-glob-struct] 第四轮验证用真 shell 实测到的放行写法（现在全部拒）：
//   cat files/*/sess-b/memory/x.md
//   cat files/mini*/f2/workspace/a.md
//   cat files/[m]inis-sessions/sess-b/memory/x.md
//   cat files/minis[-]sessions/sess-b/memory/x.md
//   cat files/*sessions/sess-b/memory/x.md
//   cat files/minis-{sessions,}/sess-b/memory/x.md
//   su -c 'cat /data/user/0/com.openminis*/files/mini*/sess-b/memory/x.md'
//   f*/mini*/sess-b/memory/x.md、*/*/sess-b/memory/x.md —— 通配符把树名本身"拼"出来
//   花括号拼出的 `..`：f1/{..,..}/f2/…、f1/{.,.}./f2/…、f1/{.,}../f2/…
// 共性：展开后落在真实的树上，文本上却一个树名都没有。

/**
 * Host `su` shares the app UID, so mode 700 does not hide other chats or
 * `databases/minis.db`. Deny those paths before the host binary runs.
 * `android-su -c 'ls /data'` stays allowed: it does not name this app's tree.
 *
 * [T-supath-glob-struct] 判据是结构化的、保守的，不是"文本长得像树名才拒"：策略看到
 * 的是命令原文，而 shell 之后还要做词切分、去引号、去转义、变量/命令替换、花括号
 * 展开与通配符展开，任何"看着像才拒"的写法都能被绕开（见文件头的实测清单）。于是
 * 三条判据：
 *   1) shell 的词里含 glob/brace 元字符（`?`、`[]`、`{}` 与通配星号），只要这个词
 *      可能指向宿主树（词里出现 `files/`、`minis-`、`com.openminis`，或逐段 glob
 *      匹配得上树根）⇒ 直接拒（[hasWildcardTreeReference]）；
 *   2) 树名之后出现"上一级"的写法（`..` 段、花括号拼出的 `..`、`=` 之后的 `..`）
 *      ⇒ 拒（[hasTraversalSegment]）；
 *   3) 树名所在的那个词里出现展开点（`$`、反引号、转义用反斜杠），或之后还有"既像
 *      路径又带展开点"的词 ⇒ 判不出落到哪 ⇒ 拒（[ownedTreeId]）。展开点只算到这些
 *      词为止：同一命令里无关的参数（`…; echo $?`、`f1/my\ report.md`）不再连坐。
 */
object SuPathPolicy {
    // The name must end here. `minis-sessions-cli` is the supported tool, not a
    // directory read; the old pattern treated that hyphen as "end of token".
    //
    // [T-supath-dotdot] 目录名不再放进捕获组：旧写法 `(?:/([^/\s'"`]+))?` 只吃
    // 树名后的第一段路径，于是 `files/minis-workspaces/<自己>/../<他人>/workspace/a.md`
    // 被判成"自己的目录"直接放行（反斜杠、重复斜杠写法归一化后同样成立）——一条
    // 纯文本层面的越权读取。树名之后的整段文本改由 [ownedTreeId] 判定。
    private val sessionTree = Regex("""(?:^|[^\w.-])minis-sessions(?![\w.-])""")
    private val projectTree = Regex("""(?:^|[^\w.-])minis-workspaces(?![\w.-])""")
    // Longer names first so `minis-su` is not missed by the bare `su` token.
    private val hostSu = Regex("""(?:^|[^\w.-])(?:sudo|minis-su(?:-cli)?|android-su|su)(?![\w.-])""")

    // [T-supath-glob-struct] shell 会展开的元字符。`* ? [ ]` 是通配符，`{ }` 是花括号
    // 展开；两者都能把树名"拼"出来，所以"词里有元字符"本身就是要处理的形状。
    private val wildcardMetas = Regex("""[*?\[\]]""")
    private val wildcardOrBraceMetas = Regex("""[*?\[\]{}]""")
    // 词里"点名了宿主树"：`files/` 是 filesDir，`minis-` 是树（也是工具）的前缀，
    // `com.openminis` 是绝对路径里的包名。
    private val hostTreeMention = Regex("""files/|minis-|com\.openminis""")
    private val treeNames = listOf("minis-sessions", "minis-workspaces")

    // 通配符把树名本身"拼"出来（f*/mini*/… 、*/*/…）时，文本上一个树名都没有，只能靠
    // "这个词展开后能不能落在树根上"判。
    /**
     * [T-supath-glob-struct] 树根候选（按 `/` 分段）。宿主 shell 的 cwd 是应用数据
     * 目录——验证者用 files/<通配符>/sess-b/… 真读到了别人的文件，可见相对写法
     * `files/minis-sessions` 就是 filesDir 下的会话树；绝对写法挂在 [APP_DATA_ROOTS]
     * 之下。
     */
    private val treeRoots: List<List<String>> = buildList {
        for (name in treeNames) add(listOf("files", name))
        for (root in APP_DATA_ROOTS) {
            val base = root.split('/').filter { it.isNotEmpty() }
            for (name in treeNames) add(base + listOf("files", name))
        }
    }

    // [T-supath-escapes] shell 在解析阶段直接"吃掉"（不产生新字符）的引号。去掉后
    // 再切片：`<tree>/<自己>/".."/<他人>` 与 `<tree>/<自己>/'.'./<他人>` 落到同一
    // 个别人目录，后者只有去引号之后才看得出是 `..`。
    private val quoteChars = Regex("""['"]+""")
    // `$X` / `${X}` / `$1` / `$?`：删掉的是"展开点"本身，只为让
    // `databases/minis$X.db` 这类写法在下方的 db 子串检查里现形。删字符只会让文本
    // 更短 ⇒ 更容易命中 ⇒ 保守方向。
    private val varRef = Regex("""\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*|\$[0-9?*@#!$-]?|\$""")

    fun denial(command: String, callerSessionId: String?, callerFolderId: String? = null): String? {
        // [T-supath-escapes] 三份视图，任一命中即拒：
        //   1) 原文——唯一看得见 `\` / `$` / 反引号的视图；
        //   2) 反斜杠当分隔符（旧视图，保留 `files\minis-workspaces\…` 这类写法）；
        //   3) "shell 已处理"视图（去变量引用、去引号、去转义符）：`databases/minis\.db`
        //      与 `databases/minis'.'db` 在这里才变回 `databases/minis.db`，
        //      `minis\-sessions` 也才变回 `minis-sessions`。
        val views = listOf(command, command.replace('\\', '/'), shellSquashed(command))
        if (views.any { it.contains("databases/minis.db") } ||
            views.any { it.contains("minis.db") && it.contains("/databases/") }
        ) {
            return "拒绝读取应用数据库 databases/minis.db。会话内容请用 search_sessions / read_session，跨会话需要用户授权。"
        }
        if (views.any { view -> APP_DATA_ROOTS.any { view.contains(it) } && hostSu.containsMatchIn(view) }) {
            return "拒绝通过宿主 su 读取应用私有目录。本会话文件在 /var/minis；其他会话需要 session_read 授权。"
        }
        // [T-supath-glob-struct] 逐"词"判定（shell 的词：引号/反引号里的空白不切词，
        // `\ ` 也不切），三份视图各切一次，后面 [ownedTreeId] 复用同一份切分。
        val worded = views.map { it to words(it) }
        if (worded.any { (_, ws) -> ws.any { hasWildcardTreeReference(it.text) } }) {
            return "拒绝读取通配符写法下的会话/项目目录。请写出完整的 minis-sessions/<id> 或 minis-workspaces/<folder> 路径。"
        }
        val caller = callerSessionId?.takeIf { it.isNotBlank() }?.let { SessionWorkspace.ownerSessionId(it) }
        for ((view, ws) in worded) {
            for (match in sessionTree.findAll(view)) {
                val id = ownedTreeId(view, match, ws)
                if (id == null || caller == null || id != caller) {
                    val shown = if (id == null) "minis-sessions" else "minis-sessions/$id"
                    return "拒绝读取其他会话目录 $shown。"
                }
            }
            for (match in projectTree.findAll(view)) {
                val id = ownedTreeId(view, match, ws)
                if (id == null || callerFolderId.isNullOrBlank() || id != callerFolderId) {
                    val shown = if (id == null) "minis-workspaces" else "minis-workspaces/$id"
                    return "拒绝读取其他项目目录 $shown。"
                }
            }
        }
        return null
    }

    /**
     * [T-supath-escapes] 视图 3：保守的"shell 已处理"文本——去掉 `$VAR` 引用、去掉
     * 引号、去掉转义用的反斜杠。不实现 shell 解析，只把最坏情况的文本摆出来给子串
     * 匹配看。
     */
    private fun shellSquashed(raw: String): String =
        raw.replace(varRef, "").replace(quoteChars, "").replace("\\", "")

    /**
     * [T-supath-glob-struct] shell 的"词"：只在不带引号、未被转义的空白处断开。
     * `'`、`"`、`` ` `` 都算引号（反引号里的命令替换在 shell 里也是同一个词的一部分），
     * `\X`（单引号内除外）整段粘在词里——`f1/my\ report.md` 因此是一个词，转义空格
     * 不会被误当成两个词。记录起止下标，供 [ownedTreeId] 把树名映射回它所在的词。
     */
    private class Word(val text: String, val start: Int, val endExclusive: Int)

    private fun words(raw: String): List<Word> {
        val out = mutableListOf<Word>()
        var start = -1
        var quote = '\u0000'
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' && quote != '\'' && i + 1 < raw.length -> {
                    if (start < 0) start = i
                    i += 2
                }
                quote != '\u0000' -> {
                    if (start < 0) start = i
                    if (c == quote) quote = '\u0000'
                    i++
                }
                c == '\'' || c == '"' || c == '`' -> {
                    if (start < 0) start = i
                    quote = c
                    i++
                }
                c.isWhitespace() -> {
                    if (start >= 0) {
                        out.add(Word(raw.substring(start, i), start, i))
                        start = -1
                    }
                    i++
                }
                else -> {
                    if (start < 0) start = i
                    i++
                }
            }
        }
        if (start >= 0) out.add(Word(raw.substring(start, raw.length), start, raw.length))
        return out
    }

    /**
     * [T-supath-glob-struct] 这个词会不会被 shell 展开成宿主树上的路径。
     *
     * 两条腿：词里点名了树（[hostTreeMention]），或者词里的通配符能把树名"拼"出来
     * （[couldNameTreeRoot] 逐段 glob 匹配 [treeRoots]）。花括号先展开
     * （[braceAlternatives]）——`files/minis-{sessions,}/…` 展开后就是树名本身；
     * 树名本身也用花括号拼、再用通配符 glob 出剩下部分的写法同理。
     * 落在树名**之后**的通配符同样拒：展开成哪一批路径，策略看不到（fail-closed）。
     */
    private fun hasWildcardTreeReference(token: String): Boolean {
        if (!wildcardOrBraceMetas.containsMatchIn(token)) return false
        if (hostTreeMention.containsMatchIn(token)) return true
        return braceAlternatives(token).any { alt ->
            wildcardMetas.containsMatchIn(alt) && couldNameTreeRoot(alt)
        }
    }

    /** 词按 `/` 分段后，前几段能不能 glob 匹配上某个树根（[treeRoots]）。 */
    private fun couldNameTreeRoot(word: String): Boolean {
        val segments = word.split('/').filter { it.isNotEmpty() }
        return treeRoots.any { root ->
            segments.size >= root.size &&
                root.indices.all { globMatchesSegment(segments[it], root[it]) }
        }
    }

    /**
     * [T-supath-glob-struct] 花括号展开的候选（`{a,b}` → a / b）。显式栈 + 工作量/条数
     * 上限，不用递归：闸门对每条 shell 命令都要跑一次，命令长度与花括号数量都由调用方
     * （模型）决定，深度递归会被超长输入顶爆栈。先出栈的是"全取第一候选"的那一支，也
     * 就是把树名"写出来"的读法（`{f}{i}{l}{e}{s}` → `files`）。嵌套/不闭合的花括号、以及
     * 超过上限的超长输入按字面量处理：那种写法里仍留着 `{`/`}`，由
     * [hasWildcardTreeReference] 的"含元字符 + 点名树"那一侧兜底。
     */
    private fun braceAlternatives(word: String): List<String> {
        val out = mutableListOf<String>()
        val stack = ArrayDeque<String>()
        stack.addLast(word)
        var work = 0
        val workCap = minOf(word.length, BRACE_WORK_CAP) + BRACE_QUEUE_CAP
        while (stack.isNotEmpty() && out.size < BRACE_LIMIT && work < workCap) {
            work++
            val current = stack.removeLast()
            val open = current.indexOf('{')
            val close = if (open < 0) -1 else current.indexOf('}', open + 1)
            if (open < 0 || close < 0) {
                out.add(current)
                continue
            }
            val body = current.substring(open + 1, close)
            if (body.contains('{') || body.contains('}')) {
                out.add(current)
                continue
            }
            val head = current.substring(0, open)
            val tail = current.substring(close + 1)
            val alts = body.split(',')
            // 倒序入栈 ⇒ 第一候选先出栈（深度优先，先给出"写出来"的读法）。
            for (i in alts.indices.reversed()) {
                if (stack.size >= BRACE_QUEUE_CAP) break
                stack.addLast(head + alts[i] + tail)
            }
        }
        return if (out.isEmpty()) listOf(word) else out
    }

    /** [T-supath-glob-struct] 单段的 shell glob 匹配：`*`（不跨 `/`）、`?`、`[abc]`/`[!abc]`。 */
    private fun globMatchesSegment(pattern: String, text: String): Boolean {
        var p = 0
        var t = 0
        var star = -1
        var resume = 0
        while (t < text.length) {
            val next =
                if (p < pattern.length && pattern[p] != '*') matchGlobUnit(pattern, p, text[t]) else -1
            when {
                next > 0 -> {
                    p = next
                    t++
                }
                p < pattern.length && pattern[p] == '*' -> {
                    star = p
                    p++
                    resume = t
                }
                star >= 0 -> {
                    p = star + 1
                    resume++
                    t = resume
                }
                else -> return false
            }
        }
        while (p < pattern.length && pattern[p] == '*') p++
        return p == pattern.length
    }

    /**
     * [T-supath-glob-struct] [pattern] 在下标 [p] 处的单元（普通字符、`?` 或 `[...]`）
     * 匹配 [c] 时返回该单元之后的下标，不匹配返回 -1；不闭合的 `[` 当字面量。
     */
    private fun matchGlobUnit(pattern: String, p: Int, c: Char): Int {
        val ch = pattern[p]
        if (ch == '?') return p + 1
        if (ch != '[') return if (ch == c) p + 1 else -1
        val close = pattern.indexOf(']', p + 2)
        if (close < 0) return if (ch == c) p + 1 else -1
        var i = p + 1
        var negate = false
        if (pattern[i] == '!' || pattern[i] == '^') {
            negate = true
            i++
        }
        var hit = false
        while (i < close) {
            if (i + 2 < close && pattern[i + 1] == '-') {
                if (c in pattern[i]..pattern[i + 2]) hit = true
                i += 3
            } else {
                if (pattern[i] == c) hit = true
                i++
            }
        }
        return if (hit != negate) close + 1 else -1
    }

    /** [T-supath-brace-glue] 花括号/逗号是"拼接元字符"。 */
    private val braceGlue = setOf('{', '}', ',')

    // [T-supath-glob-struct] 花括号展开的工作量/条数上限（[braceAlternatives]）。
    private const val BRACE_LIMIT = 8
    private const val BRACE_QUEUE_CAP = 64
    private const val BRACE_WORK_CAP = 512

    /**
     * [T-supath-dotdot] [T-supath-brace-glue] 树名之后那段文本里有没有"上一级"：
     *   `..` 开头的段（`..`、`../`、`..;`、被引号拆开的 `'.'.`）；
     *   拼接元字符 + 段内有 `.`：花括号/逗号只做拼接、不产生新字符，所以展开结果里的
     *   每个 `.` 都来自段内的字面 `.`——"能拼出 `..`"必然满足这条（必要条件，命中即拒
     *   是保守的）。`{..,..}`、`{.,.}.`、`{.,}..`、`{.,a}.`、`{.,}{.}` 全在这里兜住，
     *   后几种文本上连一个连续的 `..` 都没有；
     *   段内 `..` 左侧是边界或非单词字符——`D=..`、`DOT=..; …/$D/…` 这类赋值同样是
     *   "上一级"，而 `a..b.md`、`c..d.md` 这种文件名左侧是单词字符，不受影响。
     * 切片按 `/`、空白与引号边界切：引号在 shell 解析阶段就被去掉，`x y/../other`
     * 里 `..` 是独立一段；把被空白/引号分开的段折叠成 `/` 去解会把
     * `f1/sub/../../f2` 算到错的地方，宁可拒。`.` 段不算（`<tree>/./own/a.md` 仍是
     * 自己）。
     */
    private fun hasTraversalSegment(rest: String): Boolean =
        rest.replace(quoteChars, "").split('/', ' ', '\t', '\r', '\n').any { isTraversalSegment(it) }

    private fun isTraversalSegment(segment: String): Boolean {
        if (segment.isEmpty() || segment == ".") return false
        if (segment.startsWith("..")) return true
        if (segment.any { it in braceGlue } && segment.contains('.')) return true
        var i = segment.indexOf("..")
        while (i >= 0) {
            val left = segment.getOrNull(i - 1)
            if (left == null || !(left.isLetterOrDigit() || left == '_')) return true
            i = segment.indexOf("..", i + 1)
        }
        return false
    }

    /**
     * [T-supath-escape-scope] 展开点：`$`、反引号，或"转义了非空白字符"的反斜杠
     * （`f1/\.\./f2` 里的 `\.` 被 shell 吃成 `.`，实际读的是 `f1/../f2`）。`\ ` 只是
     * 把空格粘进同一个词，不改变路径含义，不算（`f1/my\ report.md` 因此放行）。
     */
    private fun hasExpansionPoint(fragment: String): Boolean {
        if (fragment.contains('$') || fragment.contains('`')) return true
        var i = fragment.indexOf('\\')
        while (i >= 0) {
            val next = fragment.getOrNull(i + 1)
            if (next != null && !next.isWhitespace()) return true
            i = fragment.indexOf('\\', i + 1)
        }
        return false
    }

    private fun looksLikePath(token: String): Boolean = token.contains('/') || token.contains('\\')

    /**
     * [T-supath-dotdot] [T-supath-escapes] [T-supath-escape-scope] `minis-<tree>`
     * 之后那段文本真正指向哪个目录；返回 null 表示判不出来，调用方按拒绝处理
     * （fail-closed）。
     *
     * 两棵树都是 `<tree>/<id>/…`，所以树名所在的那个词里第一段非空、非 `.` 的切片就是
     * 这条命令会读到的 id。归一化只在"文本层面"做，不重放 shell 的转义与展开：那正是
     * 上一版 `replace('\\','/')` 出错的地方。展开点只算"树名所在的词"以及之后"既像
     * 路径、又带展开点"的词——`cd <tree>/<自己> && cat $D/<他人>/…` 的 `$D` 判不出
     * 落到哪，仍拒；而 `…; echo $?`、末尾的 `my\ report.md` 不再连坐。空尾照样拒绝：
     * 裸树名会列出全部会话/项目。
     */
    private fun ownedTreeId(view: String, match: MatchResult, wordList: List<Word>): String? {
        val wi = wordList.indexOfFirst { match.range.first >= it.start && match.range.first < it.endExclusive }
        if (wi < 0) return null
        val word = wordList[wi]
        val afterTree = word.text.substring(match.range.last + 1 - word.start)
        if (hasExpansionPoint(afterTree)) return null
        for (i in wi + 1 until wordList.size) {
            val later = wordList[i].text
            if (looksLikePath(later) && hasExpansionPoint(later)) return null
        }
        if (hasTraversalSegment(view.substring(match.range.last + 1))) return null
        var head: String? = null
        for (segment in afterTree.replace(quoteChars, "").split('/', ' ', '\t', '\r', '\n')) {
            if (segment.isEmpty() || segment == ".") continue
            if (head == null) head = segment
        }
        return head
    }
}
