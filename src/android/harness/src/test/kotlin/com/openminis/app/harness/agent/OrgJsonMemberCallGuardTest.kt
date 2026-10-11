package com.openminis.app.harness.agent

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * [CRASH-2026-10-10] 真机闪退守卫。
 *
 * 设备 ART core-libart 的 org.json 没有 `JSONArray.toList()` / `JSONObject.toMap()`
 * （org.json 桌面版 2016+ 才有），而 compileSdk 35 的 android.jar 有。Kotlin
 * 成员方法优先于同文件扩展函数——一旦写出 `jsonArray.toList()`，编译器解析到
 * android.jar 的成员并生成 invokevirtual，真机直接 NoSuchMethodError 闪退；
 * JVM 测试跑在 org.json:json（testImplementation）上成员存在，永远测不出。
 *
 * 同类第二陷阱（McpToolDispatcher 修复）：JDK 21 起本机编译时 `MutableList` 的
 * `removeFirst()/removeLast()` 解析到 java.util.SequencedCollection 成员
 * （invokeinterface java/util/List.removeFirst），Android 运行时无此方法 →
 * 同样 NoSuchMethodError。CI 用 JDK 17 编译时成员不存在、Kotlin 扩展获胜，
 * 所以 CI 恒绿——只有 JDK 21 本机构建会生成被禁引用，此时这里构建期就红。
 * 仓库约定：可变列表首/尾删除一律用 removeAt(0) / removeAt(size-1)，双端
 * 队列用 kotlin.collections.ArrayDeque 的自有 removeFirst/removeLast（成员
 * 属于 kotlin-stdlib，设备随包携带，安全）。
 *
 * 本测试解析编译产物 .class 常量池，钉死 Methodref/InterfaceMethodref 不指向
 * 这些设备缺失成员。以后任何人在这些接收者上写出被禁调用（或新增其他设备
 * 缺失 API），这里构建期就红，不炸手机。
 *
 * 扫描集合只有三处（都由本文件决定，解析内核见 OrgJsonBytecodeGuard）：
 *   1. harness 主产物（:harness:test 自己的 classes，必然在场）；
 *   2. app 的**设备变体** Kotlin 产物 build/tmp/kotlin-classes/<variant>；
 *   3. [T-e9-guard-javac] app 的**设备变体** javac 产物
 *      build/intermediates/javac/<variant>（.java 源与注解处理器生成的 Java 都走
 *      javac，不是第 2 处）。android.jar 的 org.json 同样带 toList()/toMap()，
 *      所以 Java 源里写出 `JSONObject.toMap()` 时 javac 编译成功、真机闪退；只扫
 *      Kotlin 产物的话 CI 全绿。本机实测该目录 fullRelease 233 个 class（含 Room
 *      生成的 *_Impl 与 javax/lang/model），纳入后探针扫 0 违规。
 *      [T-guard-app-root] `*UnitTest` / `*TestFixtures` 目录永不进扫描集合：它们
 *      跑在桌面 org.json 上、且永不进 APK，把夹具里合法的 toList()/toMap() 当
 *      违规只会让 PR 门禁永久变红（守卫没有也不该有"白名单"来消音真违规）。
 *
 * 已知边界（本文件不覆盖，工单范围之外）：core:model / core:common / provider:api /
 * hostcapability:api 这些纯 JVM 兄弟模块的产物也随 APK 打包，同一个"桌面 org.json
 * 有、设备 core-libart 没有"的陷阱对它们同样成立（本机实测这四份产物当前 0 违规）。
 * 要收进扫描集合，得同时在 harness/build.gradle.kts 的输入指纹与 pr-check.yml 的
 * 编译步骤里登记，否则又是一条"任务声明之外的输入"。
 *
 * [T-d8-guard-fresh] 第 2、3 处扫描属于"任务声明之外的输入"：它们是本测试运行期用
 * 相对路径读的普通文件，Gradle 的任务输入指纹与 build cache 默认看不见它们。所以
 * harness/build.gradle.kts 把 app 设备变体字节码（Kotlin 与 javac 两侧）声明成
 * :harness:test 的输入指纹，并让该任务永不 UP-TO-DATE、永不进 build cache；CI 侧再每
 * run 注入一次性随机数（-Popenminis.orgJsonGuard.runNonce=… → Test 的 systemProperty
 * → 本 JVM），本测试把它打进报告 <system-out>，pr-check.yml 的「守卫未被跳过」步骤要求
 * 报告里出现同一个值——UP-TO-DATE / 缓存还原出来的旧报告不可能带上它，门禁没法靠缓存
 * 产物变绿。产物缺失时 CI（strict，见 requireAppClasses）直接失败而不是跳过，本文件
 * 不存在"静默通过"的路径。
 *
 * [T-e9-guard-coverage] 「扫了」不等于「扫全了」：deviceRoots 非空只证明**至少一个**
 * 变体目录里有 class，既不证明 CI 要求的两个风味都在场，也不证明它们不是半成品。
 * 本机实测就存在 build/tmp/kotlin-classes/slimRelease 这样的空壳目录（0 个 class，
 * deviceClassRoots 直接过滤掉它），此时旧逻辑只扫 fullRelease 就绿。所以这里按 CI
 * 编译步骤的口径做完整性判据（strict 下不满足即红）：
 *   * 每个设备变体都在场，且 class 数不低于 minDeviceClassesPerVariant（粗粒度下限，
 *     专治"目录在、内容只写了一半"）；
 *   * javac 侧的每个设备变体也必须在场（Java 源被真正编译过、可被扫描）；
 *   * 扫到的根与 class 数写进报告（ORGJSON_GUARD_APP_ROOTS=name(count) /
 *     ORGJSON_GUARD_APP_CLASSES=<total> / ORGJSON_GUARD_APP_JAVAC_ROOTS=…），
 *     pr-check.yml 再用同一个数字设一道下限。
 */
class OrgJsonMemberCallGuardTest {

    /** harness 自身的主产物：:harness:test 经 testClasses -> classes，必然在场。 */
    private val harnessRoot = File("build/classes/kotlin/main")

    /**
     * app 侧 Kotlin 产物：AGP 把 Kotlin 产物写在 build/tmp/kotlin-classes/<variant>
     * （fullRelease / slimRelease / fullReleaseUnitTest …）。
     */
    private val appClassRoot = File("../app/build/tmp/kotlin-classes")

    /**
     * [T-e9-guard-javac] app 侧 javac 产物：build/intermediates/javac/<variant>/
     * compile<Variant>JavaWithJavac/classes。变体名与 AGP 变体名一致（本机实测
     * fullRelease / release / debug），单测/夹具变体同样按标记排除。
     */
    private val appJavacRoot = File("../app/build/intermediates/javac")

    /**
     * [T-e9-guard-coverage] CI 编译步骤实际编译的设备变体（两个风味各一套 Kotlin+javac）。
     * 少一个就说明这次 run 扫的是半成品/上次残留，strict 下直接红。增删风味时要同时改
     * 这里和 pr-check.yml 的 "Compile Kotlin + Java (release variants)" 步骤
     * （OrgJsonGuardGateWiringTest 会盯着两头，防止只改一边）。
     */
    private val expectedDeviceVariants = listOf("fullRelease", "slimRelease")

    /**
     * [T-e9-guard-coverage] 每个设备变体的 class 数下限：只用来识别"目录在、内容只写了
     * 一半/几乎为空"的半成品，不是精确期望值。实测：本机一次完整构建的 fullRelease
     * Kotlin 产物 6752 个 class，而 app/src/main 有 897 个 .kt 文件（每个文件至少产出
     * 一个 class），所以 500 对任何真实变体都很宽松，对 0/几十个的半成品却是红。
     * pr-check.yml 里还有一道同值的下限判据（读报告里的 ORGJSON_GUARD_APP_CLASSES）。
     */
    private val minDeviceClassesPerVariant = 500

    /**
     * [T-guard-app-root-skip] 产物缺失时「失败」还是「跳过」：
     * CI（或显式 OPENMINIS_ORGJSON_GUARD_STRICT=1）必须失败——否则编译步骤一旦
     * 失效，守卫会靠"跳过"换来假绿门禁；本机/IDE 单独跑 :harness:test 时 app
     * 未必编译过（build.gradle.kts 只声明字节码指纹，不硬绑 :app 的编译任务，
     * 所以 build 图里仍没有顺序边），此时显式跳过并打印原因，而不是红。
     */
    private val requireAppClasses: Boolean =
        envFlag("OPENMINIS_ORGJSON_GUARD_STRICT") || envFlag("CI")

    @Test
    fun `harness bytecode never calls org-json desktop-only members`() {
        assertTrue(
            "harness 编译产物缺失：${harnessRoot.absolutePath}（cwd=${File(".").absolutePath}）",
            harnessRoot.walkTopDown().any { it.extension == "class" },
        )
        assertNoBannedRefs(listOf(harnessRoot))
    }

    @Test
    fun `app device bytecode never calls org-json desktop-only members`() {
        val deviceRoots = OrgJsonBytecodeGuard.deviceClassRoots(appClassRoot)
        if (deviceRoots.isEmpty()) {
            val testOnlyRoots = OrgJsonBytecodeGuard.classRoots(appClassRoot)
                .filter { OrgJsonBytecodeGuard.isTestOnlyClassRoot(it.name) }
            val reason = buildString {
                append("app 设备变体编译产物缺失：").append(appClassRoot.absolutePath)
                append(" 下没有含 class 的主变体目录")
                if (testOnlyRoots.isNotEmpty()) {
                    append("（只有单测/夹具目录 ")
                    append(testOnlyRoots.joinToString { it.name })
                    append("，它们跑桌面 org.json 且永不进 APK，已排除出扫描集合）")
                }
                append("。先编译 app 的设备变体（两个风味的 Kotlin 与 javac 产物，口径见 ")
                append("pr-check.yml 的编译步骤）再跑 :harness:test；CI 的 pr-check.yml 把 ")
                append("编译与测试拆成有序两步，并置 ")
                append("OPENMINIS_ORGJSON_GUARD_STRICT=1，此时这里直接失败而不是跳过。")
            }
            if (requireAppClasses) fail(reason)
            println("[OrgJsonMemberCallGuardTest] $reason")
            assumeTrue(reason, false)
        }
        val kotlinCounts = deviceRoots.map { it to classCount(it) }
        val javacCounts = OrgJsonBytecodeGuard.deviceClassRoots(appJavacRoot).map { it to classCount(it) }

        // [T-e9-guard-coverage] 完整性判据见类注释：非空 ≠ 扫全。这里只核对"本次 run 的
        // 产物是否覆盖 CI 编译步骤的口径"，不关心某个变体为什么缺（半成品、残留、
        // 编译被打断都一样：结论不可信 → strict 下红）。
        val coverageGaps = mutableListOf<String>()
        expectedDeviceVariants.forEach { variant ->
            val classes = kotlinCounts.firstOrNull { it.first.name == variant }?.second ?: 0
            if (classes < minDeviceClassesPerVariant) {
                coverageGaps += "kotlin-classes/$variant：本次只扫到 $classes 个 class，" +
                    "要求 >= $minDeviceClassesPerVariant"
            }
        }
        expectedDeviceVariants.forEach { variant ->
            val classes = javacCounts.firstOrNull { it.first.name == variant }?.second ?: 0
            if (classes == 0) {
                coverageGaps += "intermediates/javac/$variant：没有 javac 产物，" +
                    "该风味的 .java 源不会被扫描"
            }
        }
        if (coverageGaps.isNotEmpty()) {
            val reason = buildString {
                append("app 设备字节码扫描不完整——本次 run 的产物不齐，扫描结论不足以放行：\n  - ")
                append(coverageGaps.joinToString("\n  - "))
                append("\n先编译全部设备变体（:app:compileFullReleaseKotlin ")
                append(":app:compileSlimReleaseKotlin :app:compileFullReleaseJavaWithJavac ")
                append(":app:compileSlimReleaseJavaWithJavac），再跑 :harness:test。")
                append("CI 的 pr-check.yml 就是这么排的：编译与测试拆成两步，同一 invocation ")
                append("内 :harness:test 与 app 编译任务之间没有顺序边，--parallel 下会扫到半成品。")
            }
            if (requireAppClasses) fail(reason)
            println("[OrgJsonMemberCallGuardTest] WARN $reason")
        }

        // ASCII 标记 + 根名与 class 数会进 Gradle 测试报告的 <system-out>：CI 用它证明
        // app 侧真的扫过、每个风味都扫到了非零数量的 class（只靠 skipped 计数区分不出
        // "没跑"/"只扫了一个变体"和"跑了没问题"）。javac 侧即使为空也照常打印，
        // 免得 CI 的 grep 因为标记缺失而"查无此项"地放行。
        println(
            "[OrgJsonMemberCallGuardTest] 扫描 app 设备变体 ORGJSON_GUARD_APP_ROOTS=" +
                kotlinCounts.joinToString(",") { "${it.first.name}(${it.second})" },
        )
        println(
            "[OrgJsonMemberCallGuardTest] ORGJSON_GUARD_APP_CLASSES=" +
                kotlinCounts.sumOf { it.second },
        )
        println(
            "[OrgJsonMemberCallGuardTest] ORGJSON_GUARD_APP_JAVAC_ROOTS=" +
                javacCounts.joinToString(",") { "${it.first.name}(${it.second})" },
        )
        // [T-d8-guard-fresh] 本次 run 的一次性随机数（见 runNonce）：CI 用它证明这份报告
        // 是本次运行的产物，而不是 UP-TO-DATE / build cache 里还原出来的旧结论。
        println("[OrgJsonMemberCallGuardTest] ORGJSON_GUARD_RUN_NONCE=${runNonce()}")
        assertNoBannedRefs(deviceRoots + javacCounts.map { it.first })
    }

    /** 根下 .class 个数：进报告给 CI 设下限，也用于 completeness 判据。 */
    private fun classCount(root: File): Int =
        root.walkTopDown().count { it.extension == "class" }

    /**
     * [T-d8-guard-fresh] 本次运行的一次性随机数：CI 用
     * -Popenminis.orgJsonGuard.runNonce=… 交给 harness/build.gradle.kts，后者经 Test 的
     * systemProperty 送进本 JVM（环境变量 ORGJSON_GUARD_RUN_NONCE 是同值的兜底通道）。
     * 取不到时返回空串：pr-check.yml 的「守卫未被跳过」步骤要求报告里出现本次 run 的
     * 非空随机数，缺失即判红（fail-closed），不会因为"没有随机数"而放行。
     */
    private fun runNonce(): String =
        System.getProperty("openminis.orgJsonGuard.runNonce")?.trim().orEmpty()
            .ifEmpty { System.getenv("ORGJSON_GUARD_RUN_NONCE")?.trim().orEmpty() }

    private fun assertNoBannedRefs(roots: List<File>) {
        val offenders = OrgJsonBytecodeGuard.scanAll(roots)
        assertTrue(
            "banned org.json / SequencedCollection member calls found " +
                "(would crash on device):\n" + offenders.joinToString("\n") +
                "\n扫描根：" + roots.joinToString { it.path },
            offenders.isEmpty(),
        )
    }

    private fun envFlag(name: String): Boolean {
        val v = System.getenv(name)?.trim()?.lowercase() ?: return false
        return v == "1" || v == "true" || v == "yes"
    }
}
