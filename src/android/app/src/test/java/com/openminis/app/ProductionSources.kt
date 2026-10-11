package com.openminis.app

import java.io.File

/**
 * Locates production Kotlin sources from a unit test so a test can add a
 * SOURCE-GREP DRIFT GUARD next to a ported decision: when the decision logic
 * is entangled in ChatViewModel (Context + DB + provider to construct), the
 * test ports the algorithm verbatim and then asserts the production file still
 * carries the load-bearing lines. If production changes without the port, the
 * guard fails instead of the port silently drifting.
 *
 * Gradle runs `:app:testDebugUnitTest` with the module directory as the
 * working directory, so `src/main/java/...` resolves directly; the fallback
 * walk covers an IDE runner started from the repository root or a subdir.
 *
 * Only `src/main` is consulted — never `build/`, which can hold stale
 * generated copies of deleted classes.
 */
object ProductionSources {

    private const val MAIN_ROOT = "src/main/java/com/openminis/app"

    /**
     * [T-architecture-modularization] `data/model` 已经迁进纯 Kotlin 模块 core/model，
     * 源码根也从 `src/main/java` 变成 `src/main/kotlin`：只按 MAIN_ROOT 找会把
     * `data/model` 下的文件读成「文件不存在」，守卫用例整条变红（合并后就是这样）。
     * （注意：注释里不要写裸的斜杠加星号组合，Kotlin 块注释可嵌套，会把 KDoc 撕开。）
     */
    private const val MODEL_ROOT = "core/model/src/main/kotlin/com/openminis/app"

    /** The `src/main/java/com/openminis/app` directory, or null if not found. */
    fun mainRoot(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val direct = File(dir, MAIN_ROOT)
            if (direct.isDirectory) return direct
            val viaModule = File(dir, "src/android/app/$MAIN_ROOT")
            if (viaModule.isDirectory) return viaModule
            val viaAndroid = File(dir, "app/$MAIN_ROOT")
            if (viaAndroid.isDirectory) return viaAndroid
            dir = dir.parentFile
        }
        return null
    }

    /** The `core/model` module source root, or null if not found. */
    fun modelRoot(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val direct = File(dir, MODEL_ROOT)
            if (direct.isDirectory) return direct
            val viaAndroid = File(dir, "src/android/$MODEL_ROOT")
            if (viaAndroid.isDirectory) return viaAndroid
            dir = dir.parentFile
        }
        return null
    }

    /**
     * Read one production file by its path relative to `com/openminis/app`.
     *
     * Falls back to the `core/model` module for the files that moved there
     * (`data/model/...`), so a caller keeps naming the class path it knows.
     */
    fun read(relPath: String): String {
        val root = mainRoot() ?: error("production source root not found from ${File("").absolutePath}")
        val f = File(root, relPath)
        if (f.isFile) return f.readText()
        val modelFile = File(modelRoot() ?: return missing(f), relPath)
        if (modelFile.isFile) return modelFile.readText()
        missing(modelFile)
    }

    private fun missing(f: File): Nothing =
        error("production source missing: ${f.absolutePath}")

    /**
     * [T-a7-vm-split] 整个"类源码面"：以 [relPath] 的主文件为基准，把同目录下所有
     * 同名前缀的 `.kt` 一起读出来（`ui/chat/ChatViewModel.kt` → `ChatViewModel.kt`
     * 加全部 `ChatViewModel*Ext.kt`）。
     *
     * 合并上游 1.14 后 ChatViewModel 被拆成几十个文件（见
     * [T-architecture-modularization]），而且多数成员是扩展函数
     * （`fun ChatViewModel.retryLast()`）。上游那套 `read("ui/chat/ChatViewModel.kt")`
     * 加精确签名前缀（`private suspend fun drainQueuedPrompts(`）的源码守卫，会在
     * "这个文件里没有这段字面量"上直接失败 —— 那是扫描面过期，不是行为回归。
     * 需要表达"ChatViewModel 这个类必须……"的守卫请用本函数，并在用例里注明原意图。
     *
     * [exclude] 里的文件名不参与拼接，用于"生产者文件之外还得有消费者"这类断言。
     */
    fun readClassSurface(relPath: String, exclude: Set<String> = emptySet()): String {
        val root = mainRoot() ?: error("production source root not found from ${File("").absolutePath}")
        val f = File(root, relPath)
        val base = f.name.removeSuffix(".kt")
        if (!f.isFile) {
            val modelFile = File(modelRoot() ?: return missing(f), relPath)
            if (modelFile.isFile) return modelFile.readText()
            // [T-a7-vm-split] 主文件可能正在被拆分（本仓库合并后 ChatViewModel.kt
            // 就被拆掉过）：退化成"同目录同前缀的全部 .kt"，只要还有分片就继续扫。
            return readPackageSurface(relDirOf(relPath), exclude, prefix = base, baseDir = root)
                .ifEmpty { missing(f) }
        }
        val dir = f.parentFile ?: return f.readText()
        val parts = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".kt") && it.name.startsWith(base) && it.name !in exclude }
            ?.sortedBy { it.name }
            .orEmpty()
        if (parts.isEmpty()) return f.readText()
        return parts.joinToString("\n") { it.readText() }
    }

    /**
     * [T-a7-package-surface] `ui/chat` 这样的**整包源码面**：该目录下所有 `.kt`
     * （不含子目录）按文件名排序拼起来。
     *
     * 合并上游 1.14 之后 ChatViewModel 这一类正在被拆成几十上百个文件，而且新分片
     * 不再保证以 `ChatViewModel` 开头（同目录里已经有 `ChatSession*` 之类的名字）。
     * 需要表达"这个类（现在分散在本包里）必须……"的源码守卫用本函数，[exclude]
     * 用于排除已知的生产者文件；用例里请注明原意图与为什么换扫描面。
     */
    fun readPackageSurface(
        dirRelPath: String,
        exclude: Set<String> = emptySet(),
        prefix: String = "",
        baseDir: File? = null,
    ): String {
        val root = baseDir ?: mainRoot() ?: error("production source root not found")
        val dir = File(root, dirRelPath)
        if (!dir.isDirectory) return ""
        return dir.listFiles()
            ?.filter {
                it.isFile && it.name.endsWith(".kt") &&
                    (prefix.isEmpty() || it.name.startsWith(prefix)) && it.name !in exclude
            }
            ?.sortedBy { it.name }
            ?.joinToString("\n") { it.readText() }
            .orEmpty()
    }

    private fun relDirOf(relPath: String): String =
        relPath.substringBeforeLast('/', missingSeparator(relPath))

    private fun missingSeparator(relPath: String): Nothing =
        error("class surface needs a directory: $relPath")

    /**
     * [T-a7-package-surface] 在 [surface]（通常来自 [readPackageSurface] /
     * [readClassSurface]）里按**函数名**定位函数声明（`fun X(` /
     * `fun Receiver.X(`，含 `suspend`/`override` 等修饰符 —— 匹配从 `fun` 开始），
     * 返回从声明处起 [chars] 个字符的窗口；找不到返回 null。
     *
     * 为什么不能照抄上游的签名前缀：本移植把 ChatViewModel 的成员写成扩展函数
     * （`fun ChatViewModel.retryLast()`），而且函数体分散在几十个分片里，参数表里
     * 还可能嵌着带括号的 KDoc，所以锚点只能落在"声明开头"这一层。若同一个名字在
     * 本包内有多个声明（例如表达式体的 override 与它委派的漏斗函数同名），调用方
     * 请改用显式正则并在注释里写明锚的是哪一个（见 DelegationMuteResetTest）。
     */
    fun functionWindow(surface: String, name: String, chars: Int): String? {
        val rx = Regex("fun\\s+(?:[\\w.]+\\.)?${Regex.escape(name)}\\s*\\(")
        val m = rx.find(surface) ?: return null
        return surface.substring(m.range.first, minOf(surface.length, m.range.first + chars))
    }

    /** Every `.kt` file under `src/main`, for whole-tree symbol scans. */
    fun allKotlinFiles(): List<File> {
        val root = mainRoot() ?: error("production source root not found")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private const val HARNESS_ROOT = "harness/src/main/kotlin/com/openminis/app/harness"

    /**
     * [T-android-seam-extraction] `:harness` 模块的源码根：主循环的纯逻辑半边
     * （工具调用拒绝、回合结果文案、队列、指标）在 [T-android-seam-extraction]
     * 里被拆进了独立 Gradle 模块，`src/main/java/...` 下**不存在**这些文件。
     * 守卫用例仍然按类路径点名它要读的文件，所以在这里补一条查找路径。
     */
    fun harnessRoot(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (candidate in listOf(
                HARNESS_ROOT,
                "src/main/kotlin/com/openminis/app/harness",
                "src/android/$HARNESS_ROOT",
                "src/$HARNESS_ROOT",
            )) {
                val f = File(dir, candidate)
                if (f.isDirectory) return f
            }
            dir = dir.parentFile
        }
        return null
    }

    /** Read one `:harness` file by its path relative to `com/openminis/app/harness`. */
    fun readHarness(relPath: String): String {
        val root = harnessRoot() ?: error("harness source root not found from ${File("").absolutePath}")
        val f = File(root, relPath)
        if (!f.isFile) error("harness source missing: ${f.absolutePath}")
        return f.readText()
    }

    /**
     * [T-architecture-modularization] 一个被拆开的类的全部源码。
     *
     * ChatViewModel 在本移植里拆成了 `ChatViewModel.kt` 加几十个
     * `ChatViewModelXxxExt.kt`（上游那版是单文件），守卫用例只要断言「这个类里还有
     * 这段代码」，按单文件读就会读到「文件里没有」的假红。给相对路径（如
     * `ui/chat/ChatViewModel.kt`），返回同目录下基名相同的所有文件拼起来的结果：
     * 扫描面从一个文件扩到「这个类的全部片段」，断言本身一个字不动。
     *
     * [T-a7-vm-split] 直接委托 [readClassSurface]：那个实现还额外容忍「主文件此刻
     * 不存在」——本仓库正在被拆分时 ChatViewModel.kt 真的从工作区消失过，此时退化成
     * 「同目录同前缀的全部 .kt」，而不是报「文件不在」。
     */
    fun readFamily(relPath: String): String = readClassSurface(relPath)
}
