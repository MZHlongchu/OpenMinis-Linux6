package com.openminis.app.sandbox

/**
 * [T-s6-#6] `/usr/local/bin` 只读挂载写防护 wrapper 的生成器。
 *
 * PRoot `-b` 没有只读修饰符，绕过 `FileWriteTool`/`FileEditTool` 的原生 shell 写
 * （touch/cp/mv/tee/…）会直接落到宿主目录，所以
 * [PRootKernel.installMountWriteGuards] 为这些命令装一层 argv 检查 wrapper：命中
 * "有效只读"挂载前缀就报错退出。
 *
 * 旧判据是"任意位置的参数命中前缀即拒绝"，把 `cp`/`ln` 的**源**也当成写目标：
 * `cp /var/minis/mounts/<name>/a.md /root/`、`ln -s <name>/a /root/a` 这类纯读操作
 * 被误拒，而报错文案却是 "writes are disabled"，agent 很容易据此判断"挂载坏了"；
 * 反向的漏洞是 `dd of=<name>/f` 因为不等于裸前缀而**漏放**。这里按命令定义写目标
 * 在 argv 里的位置语义。
 *
 * 纯 JVM：不引用任何 Android 类型，方便单元测试（见 `MountWriteGuardScriptTest`）。
 */
internal object MountWriteGuardScript {

    /** 自家 wrapper 的哨兵：安装判据（不覆盖用户二进制）与卸载判据共用。 */
    const val MARKER = "minis-mount-readonly-guard"

    /**
     * 只读挂载前缀清单（guest 路径）。wrapper 运行时 source 它，所以挂载增删 /
     * 解锁只需重写这一个文件，不用动 rootfs。
     */
    const val CONFIG_PATH = "/var/minis/.mount-readonly-prefixes"

    /**
     * 一条命令里"哪些 argv 是写目标"。不是所有位置参数都是写目标：`cp`/`ln` 的源
     * 是读操作，只有目标才是写；`mv` 相反，删掉源同样是写。
     */
    internal enum class TargetKind {
        /** touch/tee/mkdir/rmdir/rm：所有位置参数都是写目标。 */
        ALL_POSITIONALS,

        /** mv：源（删除源也是写）与目标都拦，另含 `-t DIR` / `--target-directory=DIR`。 */
        MOVE,

        /** cp：只有最后一个位置参数（或 `-t DIR`）是写目标，源全部放行。 */
        DESTINATION,

        /** ln：第二个位置参数（链接名）是写目标；只有一个操作数时不拦；`-t DIR` 时 DIR 是目标。 */
        LINK_TARGET,

        /** dd：只有 `of=` 的取值是写目标（`of=<mount>/f` 不等于裸前缀，旧循环漏放）。 */
        DD_OUTPUT,
    }

    private val KINDS: Map<String, TargetKind> = linkedMapOf(
        "touch" to TargetKind.ALL_POSITIONALS,
        "tee" to TargetKind.ALL_POSITIONALS,
        "mkdir" to TargetKind.ALL_POSITIONALS,
        "rm" to TargetKind.ALL_POSITIONALS,
        "rmdir" to TargetKind.ALL_POSITIONALS,
        "mv" to TargetKind.MOVE,
        "cp" to TargetKind.DESTINATION,
        "ln" to TargetKind.LINK_TARGET,
        "dd" to TargetKind.DD_OUTPUT,
    )

    /** 需要安装 / 卸载 wrapper 的命令集合（顺序稳定，便于日志比对）。 */
    val commands: List<String> = KINDS.keys.toList()

    /** 该命令的写目标语义；未登记的命令返回 null。 */
    fun targetKind(command: String): TargetKind? = KINDS[command]

    /**
     * 生成 [command] 的 wrapper 脚本。
     *
     * 只覆盖 argv 目标，是 best-effort（不是权限边界）：`>`/`>>` 重定向、`sed -i`/
     * `truncate`/`rsync`、解释器内的写、`/bin/busybox cp` 这类绝对路径绕过，以及
     * 相对路径目标都不在此列。原因见
     * [PRootKernel.installMountWriteGuards] 的 [T-s6-#5] 注释。
     */
    fun wrapperScript(command: String): String {
        val kind = KINDS[command] ?: TargetKind.ALL_POSITIONALS
        return script(head(command)) + script(targetCheck(kind)) + script(tail(command))
    }

    /** trimMargin 会去掉首尾空行，拼装时必须显式补回行尾换行。 */
    private fun script(raw: String): String = raw.trimMargin() + "\n"

    private fun head(command: String): String = """
        |#!/bin/sh
        |# $MARKER — reject writes into read-only mounted folders.
        |# Auto-installed by MinisApp PRootKernel (T219-4). Regenerated whenever the
        |# mount list or a mount's writability changes — local edits are lost.
        |cfg=$CONFIG_PATH
        |
        |# Reject one candidate write target (${'$'}1 = the path as the caller spelled it).
        |# Prefixes come from ${'$'}cfg (guest paths); the comparison is literal.
        |guard_reject() {
        |    p=${'$'}1
        |    # [T-d10-slash] 归一化尾斜杠：`/mnt/x/` → `/mnt/x`（多重尾斜杠同理）。
        |    # 这一步**不是**防绕过的必需判据 —— 下面的 `"${'$'}prefix"/*` 里 `*` 本来就匹配空串，
        |    # 所以 `/mnt/x/` 不归一化也命中 `"${'$'}prefix"/*`（dash 与 git-bash 的 sh 实测；
        |    # 旧注释称"尾斜杠会绕过前缀比对"是错的，已改正）。留着只是让 p 干净一点。
        |    while [ "${'$'}p" != "/" ] && [ "${'$'}{p%/}" != "${'$'}p" ]; do p="${'$'}{p%/}"; done
        |    [ -n "${'$'}p" ] || return 0
        |    # `|| [ -n "${'$'}prefix" ]`: also handle a final line without a trailing newline.
        |    while IFS= read -r prefix || [ -n "${'$'}prefix" ]; do
        |        [ -z "${'$'}prefix" ] && continue
        |        case "${'$'}p" in
        |            "${'$'}prefix"|"${'$'}prefix"/*)
        |                echo "$command: ${'$'}1: read-only mounted folder — writes are disabled (toggle in Settings → Mount External Folders)" >&2
        |                exit 1;;
        |        esac
        |    done < "${'$'}cfg"
        |    return 0
        |}
        |
        |if [ -f "${'$'}cfg" ]; then
        |""".trimMargin()

    private fun tail(command: String): String = """
        |fi
        |exec /bin/busybox $command "${'$'}@"
        |""".trimMargin()

    private fun targetCheck(kind: TargetKind): String = when (kind) {
        TargetKind.ALL_POSITIONALS -> """
            |    # touch/tee/mkdir/rm/rmdir: every positional operand is a write target.
            |    # Option *values* are compared too (e.g. `touch -r REF f`) — conservative
            |    # by design: over-blocking a rare read-only reference beats under-blocking.
            |    end_opts=0
            |    for arg in "${'$'}@"; do
            |        if [ "${'$'}end_opts" = 0 ]; then
            |            case "${'$'}arg" in
            |                --) end_opts=1; continue;;
            |                -*) continue;;
            |            esac
            |        fi
            |        guard_reject "${'$'}arg"
            |    done
            |""".trimMargin()

        TargetKind.MOVE -> """
            |    # mv: the source is a write too (it is unlinked), so sources AND the
            |    # destination are checked, including `-t DIR` / `--target-directory`.
            |    end_opts=0
            |    want_t=0
            |    for arg in "${'$'}@"; do
            |        if [ "${'$'}want_t" = 1 ]; then guard_reject "${'$'}arg"; want_t=0; continue; fi
            |        if [ "${'$'}end_opts" = 0 ]; then
            |            case "${'$'}arg" in
            |                --) end_opts=1; continue;;
            |                --target-directory=*) guard_reject "${'$'}{arg#--target-directory=}"; continue;;
            |                --target-directory|-t) want_t=1; continue;;
            |                -t?*) guard_reject "${'$'}{arg#-t}"; continue;;
            |                -*) continue;;
            |            esac
            |        fi
            |        guard_reject "${'$'}arg"
            |    done
            |""".trimMargin()

        TargetKind.DESTINATION -> """
            |    # cp: only the destination is written — reading the source out of a
            |    # read-only mount (`cp <mount>/a.md /root/`) must stay allowed.
            |    # Destination = the last positional operand, or the `-t`/`--target-directory`
            |    # value (once given, the positional operands are all sources).
            |    end_opts=0
            |    have_t=0
            |    want_t=0
            |    target=
            |    for arg in "${'$'}@"; do
            |        if [ "${'$'}want_t" = 1 ]; then target="${'$'}arg"; have_t=1; want_t=0; continue; fi
            |        if [ "${'$'}end_opts" = 0 ]; then
            |            case "${'$'}arg" in
            |                --) end_opts=1; continue;;
            |                --target-directory=*) target="${'$'}{arg#--target-directory=}"; have_t=1; continue;;
            |                --target-directory|-t) want_t=1; continue;;
            |                -t?*) target="${'$'}{arg#-t}"; have_t=1; continue;;
            |                -*) continue;;
            |            esac
            |        fi
            |        if [ "${'$'}have_t" = 0 ]; then target="${'$'}arg"; fi
            |    done
            |    if [ -n "${'$'}target" ]; then guard_reject "${'$'}target"; fi
            |""".trimMargin()

        TargetKind.LINK_TARGET -> """
            |    # ln: the link name (second operand) is the write; the source is a read.
            |    # `ln <mount>/a` (single operand) links into the CWD — nothing to check.
            |    end_opts=0
            |    have_t=0
            |    want_t=0
            |    operands=0
            |    target=
            |    for arg in "${'$'}@"; do
            |        if [ "${'$'}want_t" = 1 ]; then target="${'$'}arg"; have_t=1; want_t=0; continue; fi
            |        if [ "${'$'}end_opts" = 0 ]; then
            |            case "${'$'}arg" in
            |                --) end_opts=1; continue;;
            |                --target-directory=*) target="${'$'}{arg#--target-directory=}"; have_t=1; continue;;
            |                --target-directory|-t) want_t=1; continue;;
            |                -t?*) target="${'$'}{arg#-t}"; have_t=1; continue;;
            |                -*) continue;;
            |            esac
            |        fi
            |        operands=${'$'}((operands + 1))
            |        if [ "${'$'}have_t" = 0 ]; then target="${'$'}arg"; fi
            |    done
            |    if [ "${'$'}have_t" = 1 ] || [ "${'$'}operands" -ge 2 ]; then
            |        if [ -n "${'$'}target" ]; then guard_reject "${'$'}target"; fi
            |    fi
            |""".trimMargin()

        TargetKind.DD_OUTPUT -> """
            |    # dd: only `of=` names a write target; `if=`/`skip=`/… are reads.
            |    for arg in "${'$'}@"; do
            |        case "${'$'}arg" in
            |            of=*) guard_reject "${'$'}{arg#of=}";;
            |        esac
            |    done
            |""".trimMargin()
    }
}
