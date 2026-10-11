package com.openminis.app

import java.io.File

/**
 * [T-A5-split-scan] Locating one production symbol when the tree is being
 * re-shaped underneath the test.
 *
 * 上游 1.14 的这些守卫用例写死了「成员一定在
 * `src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt` 里、签名一定是
 * `private suspend fun …`」。本移植把那一个单体拆成了 ChatViewModelXxxExt.kt
 * 一族，而拆分本身还在收敛（有的成员正在被并回 ChatViewModel.kt、有的还留在各自的
 * Ext 文件里），于是写死文件名或写死可见性都会让守卫在**搬家**时报「被重命名了」。
 *
 * 这里换一种取法，语义不变而位置无关：按**签名**在 `src/main` 全树里找这个成员，
 * 只要求它作为函数定义存在（`fun <name>(` 带上 `fun `，所以调用点不会误命中），
 * 然后取它的函数体。文件顺序取绝对路径排序 —— ChatViewModel.kt 天然排在
 * ChatViewModelXxxExt.kt 之前，与上游案例的读取顺序一致。
 *
 * 断言本身（谁必须调谁、顺序如何、不能出现什么）一个字都没有改；改的只是
 * 「去哪儿读这段代码」。
 */
object SplitSourceScan {

    /** 所有 `src/main` 下的 `.kt`，按绝对路径排序，结果只算一次。 */
    private val sources: List<Pair<String, String>> by lazy {
        val root = ProductionSources.mainRoot() ?: error("production source root not found")
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.absolutePath to it.readText() }
            .sortedBy { it.first }
            .toList()
    }

    /** 含 [needle] 的第一个文件（绝对路径序）；找不到即失败。 */
    fun fileContaining(needle: String): String {
        for ((_, text) in sources) if (text.contains(needle)) return text
        error("no production source contains `$needle` — was it renamed?")
    }

    /** 含 [needle] 的文件的路径，供断言消息使用。 */
    fun pathContaining(needle: String): String {
        for ((path, text) in sources) if (text.contains(needle)) return path
        error("no production source contains `$needle` — was it renamed?")
    }

    /**
     * Body of the function named [name]: from its declaration to the next member
     * declaration (4-space member or top-level extension — the port has both
     * shapes).
     *
     * 名字两边都收：成员写法 `fun name(` 与扩展写法 `fun ChatViewModel.name(`
     * （本移植的同包扩展函数大量使用后者）。正则要求 `(` 紧跟函数名，所以
     * `ensureSession` 不会误命中 `ensureSessionTailLoaded`，调用点也不会命中。
     *
     * @param subdir 只在这个相对子目录里找。同名函数在别的包里也可能存在
     *   （`debug/HeadlessChatRunner.ensureSession` 就是一个），不限定范围时按路径序
     *   可能先命中那一个 —— 那会把断言读到别人的函数体上去。默认全树。
     * @param stripComments 去掉函数体里的注释行。KDoc 里往往会**点名**断言要查的
     *   符号，不去掉的话「解释为什么不能调 X」的那段散文会把 `!contains("X()")`
     *   直接判红 —— 断言要读的是代码，不是关于代码的论述。
     */
    fun memberBody(
        name: String,
        subdir: String? = null,
        stripComments: Boolean = false,
    ): String {
        val decl = Regex("""fun (?:[A-Za-z_][\w.]*\.)?${Regex.escape(name)}\(""")
        val scope = subdir?.let {
            File.separator + it.replace('/', File.separatorChar) + File.separator
        }
        for ((path, text) in sources) {
            if (scope != null && !path.contains(scope)) continue
            val match = decl.find(text) ?: continue
            val rest = text.substring(match.range.last + 1)
            val end = Regex("""\n(?:    )?(?:private |internal |public )?(?:suspend )?fun \w+\(""")
                .find(rest)?.range?.first ?: rest.length
            val body = rest.substring(0, end)
            return if (!stripComments) body else body.lines()
                .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
                .joinToString("\n")
        }
        error("no production source declares `$name(` under ${subdir ?: "src/main"} — was it renamed?")
    }
}
