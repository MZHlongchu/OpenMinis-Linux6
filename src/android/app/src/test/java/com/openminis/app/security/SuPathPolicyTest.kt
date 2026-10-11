package com.openminis.app.security

import com.openminis.app.sandbox.SessionWorkspace
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-supath-caller-folder] `minis-workspaces` 的隔离分支要求"自己的项目目录"
 * 参数真的被传进来。这里同时锁策略本身与闸门的接线：接线断掉时（callerFolderId
 * 恒为 null）本会话自有目录会被 hard deny，测试即红。
 *
 * [T-supath-dotdot] 另一侧同样要锁：只比"树名后的第一段"会被
 * `<自己>/../<他人>` 骗过，所以下面既锁穿越一律拒，也锁自有目录（深层、`.`、
 * 重复斜杠）不被连坐。
 *
 * [T-supath-glob-struct] 第四轮复核的三条新增契约同样成对锁：通配符/花括号写得
 * "不像树名"仍要拒（真 shell 展开后落在真实的树上），而"这个词会不会落在宿主树上"
 * 之外的通配符、以及同一命令里与展开点无关的参数，都不许连坐。
 *
 * [T-supath-escape-scope] 展开点（`$`、反引号、转义反斜杠）只算"树名所在的词"和
 * 之后"既像路径又带展开点"的词——`…; echo $?`、`f1/my\ report.md` 因此放行，
 * 而 `cd <树>/<自己> && cat $D/<他人>/…` 仍然拒（见
 * [escapedTraversalWritingsAreDeniedFailClosed] 里的 `$D` 用例）。
 */
class SuPathPolicyTest {

    @Test
    fun ownProjectFolderIsAllowedButOtherFoldersAreNot() {
        val cmd = "cat files/minis-workspaces/f1/workspace/a.md"
        assertNull(SuPathPolicy.denial(cmd, "sess-a", "f1"))
        assertNotNull(SuPathPolicy.denial(cmd, "sess-a", "f2"))
        // 未归档 / folderId 解析不到：保持 fail-closed，不放行。
        assertNotNull(SuPathPolicy.denial(cmd, "sess-a", null))
        // 裸目录名（列全部项目）依旧拒绝。
        assertNotNull(SuPathPolicy.denial("ls files/minis-workspaces", "sess-a", "f1"))
    }

    /** 子代理会话的 folderId 走 ownerSessionId 归一：继承所属聊天的项目目录。 */
    @Test
    fun subagentSessionResolvesTheOwnerFolderThroughTheGate() {
        val gate = SecurityGateImpl()
        SessionWorkspace.rememberFolder("supath-owner", "supath-folder")
        try {
            val own = gate.withCallerSession("subagent:supath-owner:1") {
                gate.decide(
                    gate.classify("shell_execute", """{"command":"cat files/minis-workspaces/supath-folder/a.md"}"""),
                    PermissionMode.ASK,
                )
            }
            assertTrue("subagent must inherit its owner's folder: $own", own !is Decision.Denied)

            val other = gate.withCallerSession("subagent:supath-owner:1") {
                gate.decide(
                    gate.classify("shell_execute", """{"command":"cat files/minis-workspaces/someone-else/a.md"}"""),
                    PermissionMode.ASK,
                )
            }
            assertTrue("another folder stays denied: $other", other is Decision.Denied)
        } finally {
            SessionWorkspace.rememberFolder("supath-owner", null)
        }
    }

    /**
     * 接线回归：闸门从 caller session 解析 folderId 后，自有项目目录的命令不能再
     * 被 SuPathPolicy 硬拒绝；别人的目录仍然拒。
     */
    @Test
    fun gateResolvesCallerFolderFromSession() {
        val gate = SecurityGateImpl()
        val session = "supath-wire-session"
        val folder = "supath-wire-folder"
        SessionWorkspace.rememberFolder(session, folder)
        try {
            val own = gate.withCallerSession(session) {
                gate.decide(
                    gate.classify("shell_execute", """{"command":"cat files/minis-workspaces/$folder/a.md"}"""),
                    PermissionMode.ASK,
                )
            }
            assertTrue("own project dir must not be denied: $own", own !is Decision.Denied)

            val other = gate.withCallerSession(session) {
                gate.decide(
                    gate.classify("shell_execute", """{"command":"cat files/minis-workspaces/other-folder/a.md"}"""),
                    PermissionMode.ASK,
                )
            }
            assertTrue("another project dir must stay denied: $other", other is Decision.Denied)
        } finally {
            SessionWorkspace.rememberFolder(session, null)
        }
    }

    /**
     * [T-supath-dotdot] B17 复核回归：旧捕获组 `(?:/([^/\s'"`]+))?` 只吃树名后的
     * 第一段路径，于是"以自己的目录开头、再用 `..` 拐走"的命令被判成自有目录放行。
     * 三种分隔符写法在 shell 里落到同一个别人目录：`/`、`\`（策略内先归一化成
     * `/`）、重复斜杠 `//`；引号、空白夹住的 `..` 同理。全部必须拒绝。
     */
    @Test
    fun dotDotTraversalOutOfOwnProjectFolderIsDenied() {
        val attacks = listOf(
            "cat files/minis-workspaces/f1/../f2/workspace/a.md",
            """cat files/minis-workspaces/f1\..\f2\workspace\a.md""",
            "cat files/minis-workspaces/f1/..//f2/workspace/a.md",
            "cat files/minis-workspaces/f1/sub/../../f2/workspace/a.md",
            "cat \"files/minis-workspaces/f1/../f2/workspace/a.md\"",
            "cat files/minis-workspaces/f1/\"..\"/f2/workspace/a.md",
            "cat files/minis-workspaces/f1/my dir/../f2/workspace/a.md",
            // `..` 直接当尾段：读的是 minis-workspaces 根，会列出全部项目。
            "cat files/minis-workspaces/f1/..",
        )
        for (cmd in attacks) {
            assertNotNull("traversal must be denied: $cmd", SuPathPolicy.denial(cmd, "sess-a", "f1"))
        }
    }

    /** 穿越修复不能把自有目录一起砍掉：深层、`.` 段、重复斜杠都仍是自己的目录。 */
    @Test
    fun ownProjectFolderStaysReadableAfterTheTraversalFix() {
        val allowed = listOf(
            "cat files/minis-workspaces/f1/workspace/a.md",
            "cat files/minis-workspaces/f1/sub/deep/a.md",
            "cat files/minis-workspaces/f1/./workspace/a.md",
            "cat files/minis-workspaces//f1/workspace/a.md",
            // `a..b.md` 是文件名，不是向上走一段。
            "cat files/minis-workspaces/f1/a..b.md",
        )
        for (cmd in allowed) {
            assertNull("own folder must stay readable: $cmd", SuPathPolicy.denial(cmd, "sess-a", "f1"))
        }
    }

    /** [T-supath-dotdot] 会话树是同一条正则、同一个洞：`<自己>/../<他人>` 同样要拒。 */
    @Test
    fun dotDotTraversalOutOfOwnSessionFolderIsDenied() {
        val attacks = listOf(
            "cat files/minis-sessions/sess-a/../sess-b/memory/x.md",
            """cat files/minis-sessions/sess-a\..\sess-b\memory\x.md""",
            "cat files/minis-sessions/sess-a/..//sess-b/memory/x.md",
        )
        for (cmd in attacks) {
            assertNotNull("session traversal must be denied: $cmd", SuPathPolicy.denial(cmd, "sess-a"))
        }
        assertNull(
            "own session dir must stay readable",
            SuPathPolicy.denial("cat files/minis-sessions/sess-a/memory/x.md", "sess-a"),
        )
    }

    /**
     * 接线回归：穿越写法走完整闸门（JSON 解转义 → extractCommand → SuPathPolicy）
     * 必须落到 SuPathPolicy 的拒绝。断言 reason 而不是只断言 `is Denied`，否则被别的
     * 规则"顺带"拒掉也会绿，接线断了却看不出来。
     */
    @Test
    fun gateDeniesDotDotTraversalThroughSuPathPolicy() {
        val gate = SecurityGateImpl()
        val session = "supath-dot-session"
        val folder = "supath-dot-folder"
        SessionWorkspace.rememberFolder(session, folder)
        try {
            val attacks = listOf(
                """{"command":"cat files/minis-workspaces/$folder/../other/workspace/a.md"}""",
                """{"command":"cat files/minis-workspaces/$folder\\..\\other\\workspace\\a.md"}""",
                """{"command":"cat files/minis-workspaces/$folder/..//other/workspace/a.md"}""",
            )
            for (args in attacks) {
                val decision = gate.withCallerSession(session) {
                    gate.decide(gate.classify("shell_execute", args), PermissionMode.ASK)
                }
                val denied = decision as? Decision.Denied
                assertNotNull("traversal must be denied: $args got $decision", denied)
                assertTrue(
                    "must be the SuPathPolicy refusal, got: ${denied?.reason}",
                    denied?.reason?.contains("其他项目目录") == true,
                )
            }
        } finally {
            SessionWorkspace.rememberFolder(session, null)
        }
    }

    /**
     * [T-supath-escapes] C13 复核回归：上一版先 `replace('\\','/')` 再找 `..` 段，
     * 而 shell 的"去转义/展开"正好发生在这一步之前，于是
     * `f1/\.\./f2`（`\.` → `.`）、`f1/$X../f2`（`$X` 空 ⇒ `..`）、
     * `D=..; …/f1/$D/f2`、`f1/.$X./f2`、`${DOTDOT}`、`` f1/`echo ..`/f2 ``、
     * `f1/'.'./f2`（引号拼接）在真实 shell 里读的都是 `<自己>/../<他人>`，
     * 文本上却一个 `..` 段都看不见（本机 Git Bash 实测逐条确认）。
     *
     * 现在不再重放 shell 语义：树名之后的片段里出现 `\`、`$`、反引号，或任一段以
     * `..` 开头，一律拒。树名自身被转义（`minis\-sessions`）由"去转义视图"命中，
     * 把通配符写进树名（`minis-*`、`minis-sess*`）无法判定展开到哪棵树，也拒。
     */
    @Test
    fun escapedTraversalWritingsAreDeniedFailClosed() {
        val attacks = listOf(
            "cat files/minis-workspaces/f1/\\.\\./f2/workspace/a.md",
            "cat files/minis-workspaces/f1/\\.\\./\\.\\./f2/workspace/a.md",
            "cat files/minis-sessions/sess-a/\\.\\./sess-b/memory/x.md",
            // `$X` 展开成空 ⇒ `$X../` 就是 `../`。
            "cat files/minis-workspaces/f1/\$X../f2/workspace/a.md",
            // `D=..` ⇒ `$D` 就是 `..`。
            "D=..; cat files/minis-workspaces/f1/\$D/f2/workspace/a.md",
            // `.$X.` ⇒ `..`。
            "cat files/minis-workspaces/f1/.\$X./f2/workspace/a.md",
            "DOTDOT=..; cat files/minis-workspaces/f1/\${DOTDOT}/f2/workspace/a.md",
            // 命令替换造 `..`。
            "cat files/minis-workspaces/f1/`echo ..`/f2/workspace/a.md",
            // 变量写在后面的 argv 里，等价于 `cd f1 && cat ../f2/…`。
            "cd files/minis-workspaces/f1 && cat \$D/f2/workspace/a.md",
            // 树名自身被转义：`minis\-sessions` 在 shell 里就是 `minis-sessions`。
            "cat files/minis\\-sessions/sess-b/memory/x.md",
            "cat files/minis\\-workspaces/f2/workspace/a.md",
            // 引号拼接：`'.'.` 去引号后是 `..`。
            "cat files/minis-workspaces/f1/'.'./f2/workspace/a.md",
            // 通配符写在树名里：展开后落在真实树上，判不出是哪棵 ⇒ fail-closed。
            "cat files/minis-*/f2/workspace/a.md",
            "cat files/minis-sess*/sess-b/memory/x.md",
        )
        for (cmd in attacks) {
            assertNotNull(
                "escaped traversal must be denied: $cmd",
                SuPathPolicy.denial(cmd, "sess-a", "f1"),
            )
        }
    }

    /**
     * [T-supath-escapes] 同一行的老洞：`databases/minis\.db` 被归一成
     * `databases/minis/.db`、`databases/minis'.'db` 连反斜杠都不需要，两者在 shell
     * 里读的都是同一个数据库文件（本机 Git Bash 实测读到真实行），子串检查却全都
     * 放行。断言 reason 落在数据库规则上，避免被"顺带"拒掉也算绿。
     */
    @Test
    fun databasePathWrittenWithEscapesIsDenied() {
        val attacks = listOf(
            "cat files/databases/minis.db",
            "cat files/databases/minis\\.db",
            "cat files/databases/minis'.'db",
            "cat files/databases/minis\$X.db",
            "cat \"files/databases/minis.db\"",
        )
        for (cmd in attacks) {
            val reason = SuPathPolicy.denial(cmd, "sess-a", null)
            assertNotNull("db read must be denied: $cmd", reason)
            assertTrue("must be the database rule, got: $reason", reason!!.contains("数据库"))
        }
    }

    /**
     * [T-supath-escapes] 只对"树名之后可能改变路径含义的字符"收口，普通写法不误伤：
     * 引号包住整个路径、路径里有空格、`.` 文件、`a..b`/`c..d` 这类点号在中间的名字、
     * 尾随斜杠、`&&` 接第二条命令，都仍是自己的目录。
     */
    @Test
    fun ordinaryOwnProjectReadsSurviveTheEscapeFix() {
        val allowed = listOf(
            "cat \"files/minis-workspaces/f1/workspace/a.md\"",
            "cat files/minis-workspaces/f1/my report.md",
            "cd files/minis-workspaces/f1 && cat workspace/a.md",
            "cat files/minis-workspaces/f1/workspace/a.md && echo done",
            "ls files/minis-workspaces/f1/",
            "cat files/minis-workspaces/f1/.env",
            "cat files/minis-workspaces/f1/a..b/c..d.md",
        )
        for (cmd in allowed) {
            assertNull(
                "ordinary own read must stay allowed: $cmd",
                SuPathPolicy.denial(cmd, "sess-a", "f1"),
            )
        }
    }

    /**
     * [T-supath-escapes] 接线回归：转义写法走完整闸门（JSON 解转义 → extractCommand
     * → SuPathPolicy）必须落到 SuPathPolicy 的拒绝；理由断言与 `..` 写法那条一致。
     */
    @Test
    fun gateDeniesEscapedTraversalThroughSuPathPolicy() {
        val gate = SecurityGateImpl()
        val session = "supath-esc-session"
        val folder = "supath-esc-folder"
        SessionWorkspace.rememberFolder(session, folder)
        try {
            val attacks = listOf(
                """{"command":"cat files/minis-workspaces/$folder/\\.\\./other/workspace/a.md"}""",
                """{"command":"cat files/minis-workspaces/$folder/'.'./other/workspace/a.md"}""",
            )
            for (args in attacks) {
                val decision = gate.withCallerSession(session) {
                    gate.decide(gate.classify("shell_execute", args), PermissionMode.ASK)
                }
                val denied = decision as? Decision.Denied
                assertNotNull("escaped traversal must be denied: $args got $decision", denied)
                assertTrue(
                    "must be the SuPathPolicy refusal, got: ${denied?.reason}",
                    denied?.reason?.contains("其他项目目录") == true,
                )
            }
        } finally {
            SessionWorkspace.rememberFolder(session, null)
        }
    }

    /**
     * [T-supath-glob-struct] 第四轮复核回归：策略看到的是命令原文，shell 之后还会做
     * 通配符与花括号展开，于是"文本上不像树名"的写法展开后落在真实的树上。验证者用
     * 真 shell 实测读到他人数据的形式全部列出：树名里插通配符或方括号的几种拼法
     * （`*`、`?`、`[…]`）、花括号展开出来的树名、`su -c '…'` 里连包名都带通配符的
     * 绝对路径，以及连 `files/` 都用通配符拼出来的写法。逐条字面写法见 SuPathPolicy.kt
     * 文件头的实测清单。
     *
     * 断言 reason 落在"通配符"这条规则上：被别的规则顺带拒掉也算绿的话，这条接线
     * 断了看不出来。
     */
    @Test
    fun wildcardAndBraceTreeWritingsAreDenied() {
        val attacks = listOf(
            "cat files/*/sess-b/memory/x.md",
            "cat files/mini*/f2/workspace/a.md",
            "cat files/[m]inis-sessions/sess-b/memory/x.md",
            "cat files/minis[-]sessions/sess-b/memory/x.md",
            "cat files/*sessions/sess-b/memory/x.md",
            "cat files/minis-{sessions,}/sess-b/memory/x.md",
            "su -c 'cat /data/user/0/com.openminis*/files/mini*/sess-b/memory/x.md'",
            // 花括号拼出的 `..`：`{.,.}.` 文本上连一个连续的 `..` 都没有。
            "cat files/minis-workspaces/f1/{..,..}/f2/workspace/a.md",
            "cat files/minis-workspaces/f1/{.,.}./f2/workspace/a.md",
            "cat files/minis-workspaces/f1/{.,}../f2/workspace/a.md",
            // 通配符把树名本身"拼"出来：文本上一个树名都没有。
            "cat f*/mini*/sess-b/memory/x.md",
            "cat */*/sess-b/memory/x.md",
            "cat f{iles,}/mini*/sess-b/memory/x.md",
        )
        for (cmd in attacks) {
            val reason = SuPathPolicy.denial(cmd, "sess-a", "f1")
            assertNotNull("wildcard tree path must be denied: $cmd", reason)
            assertTrue("must be the wildcard rule, got: $reason", reason!!.contains("通配符"))
        }
    }

    /**
     * [T-supath-glob-struct] [T-supath-brace-glue] `..` 的判据也是结构化的：花括号/逗号
     * 能把上一级"拼"出来——`{..,..}`、`{.,.}.`、`{.,}..` 展开后是 `..`，`{.,a}.`、
     * `{.,}{.}`、`.{.,a}` 文本上连一个连续的 `..` 都没有、展开后同样是 `..`；赋值
     * `D=..` 之后 `$D` 同理。这些写法出现在树名之后、而通配符不在树名所在的那个词里时，
     * 由"其他项目目录"这条规则兜住——所以这里断言的是项目目录拒绝，不是通配符拒绝。
     */
    @Test
    fun braceGluedDotDotAfterTheTreeIsDenied() {
        val attacks = listOf(
            "cd files/minis-workspaces/f1 && cat {..,..}/f2/workspace/a.md",
            "cd files/minis-workspaces/f1 && cat {.,.}./f2/workspace/a.md",
            "cd files/minis-workspaces/f1 && cat {.,}../f2/workspace/a.md",
            // 拼接出来的 `..`：段里没有连续两点，只有"拼接元字符 + 一个 `.`"。
            "cd files/minis-workspaces/f1 && cat {.,a}./f2/workspace/a.md",
            "cd files/minis-workspaces/f1 && cat {.,}{.}/f2/workspace/a.md",
            "cd files/minis-workspaces/f1 && cat .{.,a}/f2/workspace/a.md",
            "cd files/minis-workspaces/f1 && D=../f2/workspace/a.md && cat \$D",
        )
        for (cmd in attacks) {
            val reason = SuPathPolicy.denial(cmd, "sess-a", "f1")
            assertNotNull("brace-glued traversal must be denied: $cmd", reason)
            assertTrue("must be the traversal rule, got: $reason", reason!!.contains("其他项目目录"))
        }
    }

    /**
     * [T-supath-escape-scope] 展开点只算"树名所在的词"与之后"既像路径又带展开点"的
     * 词。于是同一命令里无关的参数不再连坐：`my\ report.md` 的转义空格只是把空格粘进
     * 同一个词，`; echo $?` 只是退出码。反向那一侧（`cd <树>/<自己> && cat $D/<他人>/…`
     * 的 `$D` 带 `/`，判不出落到哪）仍由 [escapedTraversalWritingsAreDeniedFailClosed]
     * 钉住。
     */
    @Test
    fun unrelatedArgumentsAfterTheTreeAreNotDenied() {
        val allowed = listOf(
            "cat files/minis-workspaces/f1/my\\ report.md",
            "cat files/minis-workspaces/f1/a.md; echo \$?",
            "cat files/minis-workspaces/f1/a.md && echo \$?",
            "cd files/minis-workspaces/f1 && cat a.md && echo \$?",
            "cat \"files/minis-workspaces/f1/my report.md\"",
            "cat files/minis-workspaces/f1/a.md && git commit -m \"fix: a..b\"",
            // 拼接元字符本身不拒：段里没有 `.` 就拼不出上一级（`echo {a,b}` 是普通展开），
            // `,` 与 `.` 分处两段时（`--pretty=format:%h,%s` 对 `a.b`）也不连坐。
            "cd files/minis-workspaces/f1 && echo {a,b}",
            "cat files/minis-workspaces/f1/a.md && git log --pretty=format:%h,%s",
            "cat files/minis-workspaces/f1/a.md && git log --format=%h..%h",
            "cat files/minis-workspaces/f1/a.md && echo a.b",
        )
        for (cmd in allowed) {
            assertNull(
                "unrelated arg must not be denied: $cmd",
                SuPathPolicy.denial(cmd, "sess-a", "f1"),
            )
        }
    }

    /**
     * [T-supath-glob-struct] 通配符规则只问"这个词会不会落在宿主树上"：与树无关的
     * 通配符照旧放行，不能因为命令里出现了 `*` 就一刀切（`find /var -name '*.db'`
     * 正是 SecurityGateImplTest 里"广搜不该被一刀拒"的那条）。
     */
    @Test
    fun wildcardsThatCannotNameTheTreesStayAllowed() {
        val allowed = listOf(
            "find /var -name '*.db'",
            "find /var -name *.db",
            "ls /tmp/*.log",
            "cat /var/minis/workspace/*.md",
            "ls /sdcard/*/",
            "ls /data/*/",
            "ls /data/user/0/*/*",
            "minis-sessions-cli search --keywords 'foo*'",
            "du -sh /var/minis/*",
        )
        for (cmd in allowed) {
            assertNull(
                "a glob that cannot name the trees must stay allowed: $cmd",
                SuPathPolicy.denial(cmd, "sess-a", "f1"),
            )
        }
    }

    /**
     * [T-supath-glob-struct] 树名之后的通配符同样判不出展开成哪一批路径（策略看不到
     * 展开结果），一律拒：要读文件就写出完整路径。这条是 fail-closed 的刻意收紧，
     * 连"自己目录里的通配符"也包含在内。
     */
    @Test
    fun wildcardsInsideTheTreeAreDeniedFailClosed() {
        val attacks = listOf(
            "cat files/minis-workspaces/f1/*.md",
            "cat files/minis-sessions/sess-a/memory/*.md",
            "ls files/minis-workspaces/f1/*/",
            "ls /data/user/0/com.openminis.linux/files/*",
        )
        for (cmd in attacks) {
            val reason = SuPathPolicy.denial(cmd, "sess-a", "f1")
            assertNotNull("wildcard inside the tree must be denied: $cmd", reason)
            assertTrue("must be the wildcard rule, got: $reason", reason!!.contains("通配符"))
        }
    }

    /**
     * 接线回归：通配符写法走完整闸门（JSON 解转义 → extractCommand → SuPathPolicy）
     * 必须落到 SuPathPolicy 的通配符拒绝；同一命令里无关的 `$?` 不能让自有目录被
     * 连坐（否则"修误伤"这一半就没接线）。
     */
    @Test
    fun gateDeniesWildcardTreePathsThroughSuPathPolicy() {
        val gate = SecurityGateImpl()
        val session = "supath-glob-session"
        val folder = "supath-glob-folder"
        SessionWorkspace.rememberFolder(session, folder)
        try {
            val attacks = listOf(
                """{"command":"cat files/*/sess-b/memory/x.md"}""",
                """{"command":"cat f*/mini*/sess-b/memory/x.md"}""",
                """{"command":"cat files/minis-{sessions,}/sess-b/memory/x.md"}""",
            )
            for (args in attacks) {
                val decision = gate.withCallerSession(session) {
                    gate.decide(gate.classify("shell_execute", args), PermissionMode.ASK)
                }
                val denied = decision as? Decision.Denied
                assertNotNull("wildcard tree path must be denied: $args got $decision", denied)
                assertTrue(
                    "must be the SuPathPolicy wildcard refusal, got: ${denied?.reason}",
                    denied?.reason?.contains("通配符") == true,
                )
            }

            val own = gate.withCallerSession(session) {
                gate.decide(
                    gate.classify(
                        "shell_execute",
                        """{"command":"cat files/minis-workspaces/$folder/a.md; echo $?"}""",
                    ),
                    PermissionMode.ASK,
                )
            }
            assertTrue("an unrelated \$? must not deny the caller's own dir: $own", own !is Decision.Denied)
        } finally {
            SessionWorkspace.rememberFolder(session, null)
        }
    }
}
