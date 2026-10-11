package com.openminis.app.harness.agent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-d8-guard-fresh] 门禁接线测试。
 *
 * OrgJsonMemberCallGuardTest 的正确性有一半不在它自己身上：它在测试运行期按相对路径读
 * app 的编译产物，而 Gradle 的任务输入指纹/UP-TO-DATE 判定/build cache 默认对此一无所知。
 * 于是"PR 只改 app 主源码（比如引入 JSONObject.toMap()）→ :harness:test 命中
 * UP-TO-DATE / FROM-CACHE → 测试报告被整份还原（skipped="0" 与扫描标记俱全）→ 三步全绿
 * 而字节码根本没扫"这条假绿路径，只能靠在 build 脚本与工作流里堵。
 *
 * 这里把那几处接线钉成可执行判据（读代码、跑 :harness:test 都能确认），任何一次
 * "顺手简化"掉它们都会在这里红：
 *   1. harness/build.gradle.kts 把 app 设备变体字节码声明成 :harness:test 的输入指纹，
 *      并让该任务永不 UP-TO-DATE、永不进 build cache；
 *   2. 该脚本读的目录与守卫测试读的目录是同一个相对路径（模块搬家/改名时不会悄悄脱钩）；
 *   3. pr-check.yml 每 run 注入一次性随机数、要求报告里出现它——缓存/历史产物不可能满足。
 *
 * [T-e9-guard-coverage] 第四轮追加的三条，堵的是"扫了但没扫全"与"断言写成反向"：
 *   4. 指纹必须同时覆盖 javac 产物（build/intermediates/javac）——.java 源写出
 *      JSONObject.toMap() 时 android.jar 有该方法、javac 编译通过而真机闪退，只扫
 *      Kotlin 产物的话 CI 全绿；
 *   5. 「禁止写入 build cache」的判据必须是 doNotCacheIf 且谓词恒 true，脚本里出现
 *      cacheIf（它只会"开启"缓存）即判红——用 cacheIf 断言禁缓存是反向假绿；
 *   6. 守卫与工作流必须就"设备变体清单 + 每个变体的 class 数下限"保持一致：变体齐备性
 *      由守卫在 strict 下 fail-closed 判定，报告里再带 class 数供 CI 设下限，编译步骤
 *      必须排在 :harness:test 之前（同一 invocation 内两者没有顺序边）。
 *
 * 路径假设与守卫测试一致：Gradle Test 任务的工作目录是模块目录（harness/）。
 */
class OrgJsonGuardGateWiringTest {

    private val buildScript = File("build.gradle.kts")

    private val guardTest =
        File("src/test/kotlin/com/openminis/app/harness/agent/OrgJsonMemberCallGuardTest.kt")

    /** 仓库根 = 模块目录的上三级（src/android/harness）。 */
    private val workflow = File("../../../.github/workflows/pr-check.yml")

    @Test
    fun `harness test task fingerprints app device bytecode and forbids cache reuse`() {
        // 剥掉整行注释：注释里的字样不算数，判据必须落在真的配置代码里。
        val text = codeOnly(read(buildScript))
        requireToken(
            text, buildScript, "tasks.named<Test>(\"test\")",
            ":harness:test 必须被显式配置：app 设备字节码是它运行期读的输入",
        )
        // 判据限定在 :harness:test 自己的配置块内，别处（别的任务）写同样的东西不算数。
        val taskBlock = blockAfter(text, "tasks.named<Test>(\"test\")")
        assertTrue(
            "取不到 :harness:test 的配置块（花括号不配对？）——判据必须钉在这个任务的配置上",
            taskBlock != null,
        )
        val block = taskBlock!!
        requireRegex(
            block, buildScript, Regex("inputs\\.property\\(\\s*\"appDeviceBytecode\""),
            "app 设备变体字节码必须声明成 :harness:test 的输入指纹，" +
                "否则只改 app 主源码时该任务输入指纹不变（D8 假绿根因）",
        )
        requireToken(
            block, buildScript, "../app/build/tmp/kotlin-classes",
            "指纹声明的目录必须与守卫扫描的是同一个相对路径",
        )
        requireToken(
            block, buildScript, "../app/build/intermediates/javac",
            "[T-e9-guard-javac] javac 产物（.java 源 + 注解处理器生成的 Java）也必须进指纹，" +
                "否则只改 Java 源时指纹不变",
        )
        requireRegex(
            block, buildScript, Regex("outputs\\.upToDateWhen\\s*\\{\\s*false\\s*\\}"),
            "该任务必须永不 UP-TO-DATE（Gradle 文档：upToDateWhen 返回 false 时" +
                "no outputs will be loaded from the build cache）",
        )
        // [T-e9-cache-assert] 精确判据：doNotCacheIf 的谓词必须恒为 true。cacheIf 只是
        // "开启缓存"，拿它当"禁止写入 build cache"的证据是反的——所以它出现即判红。
        // 注：`[^\n]*` 允许说明文字里带右括号（贪婪回溯到本行最后一个 `)`）。
        requireRegex(
            block, buildScript, Regex("outputs\\.doNotCacheIf\\([^\\n]*\\)\\s*\\{\\s*true\\s*\\}"),
            "该任务必须被禁止写入 build cache（doNotCacheIf 且谓词恒 true），" +
                "否则别的 run/机器能还原同一份报告",
        )
        requireAbsent(
            text, buildScript, "outputs.cacheIf",
            "cacheIf 只会开启 build cache 复用，不能用来表达「禁止写入」；" +
                "该任务必须用 doNotCacheIf { true }",
        )
        requireToken(
            block, buildScript, "systemProperty(\"openminis.orgJsonGuard.runNonce\"",
            "CI 的一次性随机数必须经 Test 的 systemProperty 送进测试 JVM",
        )
    }

    @Test
    fun `guard test scans the fingerprinted paths and prints this run's evidence`() {
        val text = codeOnly(read(guardTest))
        requireToken(
            text, guardTest, "File(\"../app/build/tmp/kotlin-classes\")",
            "守卫必须仍然扫 app 的 kotlin-classes（与 build 脚本指纹同一路径）",
        )
        requireToken(
            text, guardTest, "ORGJSON_GUARD_APP_ROOTS=",
            "CI 靠这个 ASCII 标记确认 app 侧真的扫过，不能改名",
        )
        requireToken(
            text, guardTest, "ORGJSON_GUARD_RUN_NONCE=",
            "报告必须带上本次运行的一次性随机数，否则无法自证不是缓存产物",
        )
        requireToken(
            text, guardTest, "System.getProperty(\"openminis.orgJsonGuard.runNonce\")",
            "随机数必须从 build 脚本注入的 system property 读取",
        )
    }

    @Test
    fun `guard coverage gate requires every device variant and its class-count floor`() {
        val text = codeOnly(read(guardTest))
        requireToken(
            text, guardTest, "private val appJavacRoot = File(\"../app/build/intermediates/javac\")",
            "[T-e9-guard-javac] 守卫必须扫 app 的 javac 产物：.java 源不产出 Kotlin 字节码，" +
                "而 android.jar 的 org.json 同样有 toList()/toMap()",
        )
        requireToken(
            text, guardTest, "expectedDeviceVariants = listOf(\"fullRelease\", \"slimRelease\")",
            "[T-e9-guard-coverage] 期望在场的设备变体清单必须与 pr-check.yml 编译的变体一致" +
                "（空壳/半成品目录会让「非空即通过」只扫到一个风味）",
        )
        requireRegex(
            text, guardTest, Regex("minDeviceClassesPerVariant = \\d+"),
            "[T-e9-guard-coverage] 必须有每个变体的 class 数下限：目录在但只写了一半时也得红",
        )
        val failClosed = Regex("if \\(requireAppClasses\\) fail\\(").findAll(text).count()
        assertTrue(
            "[T-e9-guard-coverage] 「产物缺失」与「扫描不完整」两条路径都必须 fail-closed" +
                "（strict 下 fail，而不是只打印 WARN）；当前 if (requireAppClasses) fail( 只有 $failClosed 处",
            failClosed >= 2,
        )
        requireRegex(
            text, guardTest, Regex("assertNoBannedRefs\\([^)]*javac"),
            "[T-e9-guard-javac] javac 产物必须真的进扫描调用，不能只统计数字",
        )
        requireToken(
            text, guardTest, "ORGJSON_GUARD_APP_CLASSES=",
            "[T-e9-guard-coverage] 扫到的 class 数必须写进报告，CI 才能用它设下限",
        )
        requireToken(
            text, guardTest, "ORGJSON_GUARD_APP_JAVAC_ROOTS=",
            "[T-e9-guard-javac] javac 侧的扫描集合也要进报告，否则 CI 无从判断它是否被扫过",
        )
    }

    @Test
    fun `ci compiles every device variant before the guard and re-checks the report`() {
        // 去掉注释行再断言：注释里的字样不算数，判据必须真的在 run 脚本里。
        val code = codeOnly(read(workflow))
        requireToken(
            code, workflow, "-Popenminis.orgJsonGuard.runNonce=",
            "测试步骤必须把一次性随机数交给 :harness:test",
        )
        requireToken(
            code, workflow, "ORGJSON_GUARD_RUN_NONCE=",
            "测试步骤必须导出本次 run 的随机数（写入 GITHUB_ENV）供校验步骤使用",
        )
        requireRegex(
            code, workflow, Regex("grep[^\\n]*ORGJSON_GUARD_RUN_NONCE"),
            "「守卫未被跳过」步骤必须校验报告里的随机数——缓存还原的报告不可能满足",
        )
        requireToken(code, workflow, "skipped=\"0\"", "必须保留 skipped=\"0\" 判据")
        requireToken(code, workflow, "ORGJSON_GUARD_APP_ROOTS=", "必须保留扫描标记判据")
        requireToken(
            code, workflow, "OPENMINIS_ORGJSON_GUARD_STRICT",
            "CI 必须置 strict：app 设备产物缺失时守卫 fail 而不是 skip",
        )

        // [T-e9-guard-coverage] 编译步骤必须覆盖两个风味的 Kotlin 与 javac 产物：守卫在
        // strict 下要求它们全部在场，并且必须**先编译完**才跑 :harness:test（同一
        // invocation 内 :harness:test 与 app 编译任务之间没有顺序边，--parallel 会扫到半成品）。
        listOf(
            ":app:compileFullReleaseKotlin", ":app:compileSlimReleaseKotlin",
            ":app:compileFullReleaseJavaWithJavac", ":app:compileSlimReleaseJavaWithJavac",
        ).forEach { task ->
            requireToken(
                code, workflow, task,
                "[T-e9-guard-coverage] 编译步骤必须编译 $task：守卫要求该变体产物在场且非空",
            )
        }
        val compiledAt = code.indexOf(":app:compileFullReleaseJavaWithJavac")
        val harnessTestAt = code.indexOf(":harness:test")
        assertTrue(
            "[T-e9-guard-coverage] 编译步骤必须排在 :harness:test 之前（否则守卫扫的是半成品）：" +
                "compileFullReleaseJavaWithJavac@$compiledAt harness:test@$harnessTestAt",
            compiledAt >= 0 && harnessTestAt >= 0 && compiledAt < harnessTestAt,
        )
        // 报告判据：每个风味都要有非零 class 数的扫描证据（Kotlin 与 javac 两侧），
        // 且总 class 数不低于下限——只靠"标记在场"仍然区分不出"只扫了一个风味"。
        requireToken(
            code, workflow, "for v in fullRelease slimRelease; do",
            "[T-e9-guard-coverage] 校验步骤必须逐个风味核对（清单与守卫的 expectedDeviceVariants 同步）",
        )
        requireRegex(
            code, workflow, Regex("grep[^\\n]*ORGJSON_GUARD_APP_ROOTS"),
            "必须校验 Kotlin 侧扫描根标记（含每个风味的 class 数）",
        )
        requireRegex(
            code, workflow, Regex("grep[^\\n]*ORGJSON_GUARD_APP_JAVAC_ROOTS"),
            "[T-e9-guard-javac] 必须校验 javac 侧扫描根标记——Java 源没被扫过时判红",
        )
        requireRegex(
            code, workflow, Regex("grep[^\\n]*ORGJSON_GUARD_APP_CLASSES="),
            "[T-e9-guard-coverage] 必须读报告里的总 class 数并设下限",
        )
    }

    @Test
    fun `class-count floor in the workflow cannot be satisfied by a single variant`() {
        val perVariant = requireNotNull(
            Regex("minDeviceClassesPerVariant = (\\d+)")
                .find(codeOnly(read(guardTest)))?.groupValues?.get(1)?.toInt(),
        ) { "解析不到守卫里的 minDeviceClassesPerVariant（见 OrgJsonMemberCallGuardTest）" }
        val workflowFloor = requireNotNull(
            Regex("\"\\\$total\"\\s+-lt\\s+(\\d+)")
                .find(codeOnly(read(workflow)))?.groupValues?.get(1)?.toInt(),
        ) { "解析不到工作流里的总 class 数下限判据（形如 [ \"\$total\" -lt N ]）" }
        assertTrue(
            "工作流的 class 数下限 $workflowFloor 必须 >= 两个风味的下限之和 ${perVariant * 2}" +
                "（= 2 x minDeviceClassesPerVariant）：否则「只扫了一个风味」的产物也能过这道下限",
            workflowFloor >= perVariant * 2,
        )
    }

    /** 去掉整行注释（`//` 与 YAML 的 `#`）：判据不许被注释里的字样满足。 */
    private fun codeOnly(text: String): String =
        text.lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("#") }
            .joinToString("\n")

    /**
     * 取出 header 之后第一个 `{` 到配对 `}` 之间的正文（调用方需先剥掉整行注释；
     * 本脚本的字符串字面量里没有花括号，故简单计数即可）。找不到返回 null。
     */
    private fun blockAfter(text: String, header: String): String? {
        val start = text.indexOf(header)
        if (start < 0) return null
        val open = text.indexOf('{', start)
        if (open < 0) return null
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, i)
                }
            }
        }
        return null
    }

    private fun read(f: File): String {
        assertTrue(
            "找不到 ${f.absolutePath}（工作目录=${File(".").absolutePath}）——" +
                "本测试与守卫测试一样假设 Gradle Test 的工作目录是 harness 模块目录",
            f.isFile,
        )
        return f.readText()
    }

    private fun requireToken(text: String, file: File, token: String, why: String) {
        assertTrue("$why；但 ${file.path} 里找不到：$token", text.contains(token))
    }

    private fun requireRegex(text: String, file: File, regex: Regex, why: String) {
        assertTrue("$why；但 ${file.path} 里找不到：/${regex.pattern}/", regex.containsMatchIn(text))
    }

    private fun requireAbsent(text: String, file: File, token: String, why: String) {
        assertTrue("$why；但 ${file.path} 里出现了：$token", !text.contains(token))
    }
}
