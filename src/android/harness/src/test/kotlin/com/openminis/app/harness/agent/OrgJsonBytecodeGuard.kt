package com.openminis.app.harness.agent

import java.io.File

/**
 * [CRASH-2026-10-10] 设备缺失成员守卫的字节码内核。
 *
 * 从 OrgJsonMemberCallGuardTest 抽出，好让「扫描根筛选」与「常量池解析」两件
 * 事能各自被单测直接钉住（见 OrgJsonBytecodeGuardTest）；守卫测试本身只负责
 * 决定扫哪些根、产物缺失时跳过还是失败。
 *
 * 规则细节与背景见 OrgJsonMemberCallGuardTest 的类注释。
 */
internal object OrgJsonBytecodeGuard {

    /** 类名（内部格式）→ 被禁成员方法名。 */
    val BANNED: Map<String, Set<String>> = mapOf(
        "org/json/JSONArray" to setOf("toList"),
        "org/json/JSONObject" to setOf("toMap"),
        // JDK 21 SequencedCollection 成员：JDK 21 本机编译解析为成员方法，
        // Android 运行时缺失。CI 的 JDK 17 不会生成该引用，故此规则只在
        // JDK 21 本机构建上生效——正是它的暴露窗口。reversed/getFirst/
        // getLast/addFirst/addLast 同属 SequencedCollection 家族（List 上的
        // Kotlin 扩展 reversed() 编译成 invokestatic CollectionsKt，不会误伤；
        // Comparator.reversed 是 Java 8 default 方法，API 24+ 设备有，不在此列）。
        "java/util/List" to setOf(
            "removeFirst", "removeLast", "reversed",
            "getFirst", "getLast", "addFirst", "addLast",
        ),
        "java/util/SequencedCollection" to setOf(
            "removeFirst", "removeLast", "reversed",
            "getFirst", "getLast", "addFirst", "addLast",
        ),
    )

    /**
     * [T-guard-app-root] 只跑 JVM、永不进 APK 的变体目录名标记（AGP 命名：
     * fullReleaseUnitTest / slimReleaseUnitTest / releaseUnitTest /
     * *TestFixtures）。这些产物跑在 testImplementation 的桌面 org.json 上，
     * 写 `JSONArray.toList()` / `JSONObject.toMap()` 完全合法，扫它们只会把
     * PR 门禁永久变红——所以它们永远不进扫描集合。
     *
     * 注意反向边界：*AndroidTest 是**设备**字节码（真机 core-libart org.json），
     * 出现时必须照常扫，故不在排除标记里。
     */
    private val TEST_ONLY_MARKERS = listOf("UnitTest", "TestFixtures")

    /** 该目录名是否属于「只跑 JVM」的测试/夹具变体。 */
    fun isTestOnlyClassRoot(name: String): Boolean =
        TEST_ONLY_MARKERS.any { name.contains(it, ignoreCase = true) }

    /** parent 下所有含 .class 的子目录，按名字排序（AGP 会留下空壳目录，不算根）。 */
    fun classRoots(parent: File): List<File> =
        parent.listFiles().orEmpty()
            .filter { it.isDirectory }
            .filter { dir -> dir.walkTopDown().any { it.extension == "class" } }
            .sortedBy { it.name }

    /** 设备变体产物根（排除单测/夹具目录）——守卫唯一允许扫的 app 侧输入。 */
    fun deviceClassRoots(parent: File): List<File> =
        classRoots(parent).filterNot { isTestOnlyClassRoot(it.name) }

    /** 扫描 roots 下全部 class，返回被禁引用描述（已排序）。 */
    fun scanAll(roots: List<File>): List<String> = roots
        .flatMap { root -> root.walkTopDown().filter { it.extension == "class" }.toList() }
        .flatMap { scan(it) }
        .sorted()

    /**
     * 最小常量池解析：返回本文件里指向被禁成员的方法引用描述。
     * 未知常量池 tag 直接报错（不静默跳过整个文件——那会让守卫悄悄漏掉产物）。
     */
    fun scan(f: File): List<String> {
        val b = f.readBytes()
        if (b.size < 12) return emptyList()
        fun u2(p: Int) = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
        val count = u2(8)
        val utf8 = HashMap<Int, String>()
        val cls = HashMap<Int, Int>()
        val nat = HashMap<Int, Pair<Int, Int>>()
        val refs = ArrayList<Pair<Int, Int>>() // classIndex → nameAndTypeIndex
        var i = 10
        var idx = 1
        while (idx < count) {
            when (val tag = b[i].toInt() and 0xFF) {
                1 -> { i++; val len = u2(i); i += 2; utf8[idx] = String(b, i, len, Charsets.UTF_8); i += len }
                7 -> { i++; cls[idx] = u2(i); i += 2 }
                9, 10, 11 -> { i++; refs.add(u2(i) to u2(i + 2)); i += 4 }
                12 -> { i++; nat[idx] = u2(i) to u2(i + 2); i += 4 }
                3, 4 -> i += 5
                5, 6 -> { i += 9; idx++ } // long/double 占双槽
                8, 16, 19, 20 -> i += 3
                15 -> i += 4
                17, 18 -> i += 5
                else -> error("unknown constant-pool tag $tag in ${f.path}")
            }
            idx++
        }
        val out = ArrayList<String>()
        for ((ci, ni) in refs) {
            val cn = utf8[cls[ci] ?: continue] ?: continue
            val names = BANNED[cn] ?: continue
            val (nameIdx, _) = nat[ni] ?: continue
            val name = utf8[nameIdx] ?: continue
            if (name in names) out += "${f.name}: $cn.$name"
        }
        return out
    }
}
