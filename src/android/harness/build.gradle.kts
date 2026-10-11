import java.io.File
import java.security.MessageDigest
import org.gradle.api.tasks.testing.Test

plugins {
    id("minis.kotlin.library")
    kotlin("plugin.serialization")
}

dependencies {
    // [T-p0-1-extraction] 纯 agent 逻辑（中断尾检测 / 工具循环检测 / 消息变换）从
    // :app 迁入后需要核心消息模型；org.json 是 core:model 的 implementation 依赖
    // 不传递，ToolLoopDetector 直接用 JSONArray/JSONObject，故此处自行声明。
    implementation(project(":core:model"))
    implementation("org.json:json:20231013")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// ---------------------------------------------------------------------------
// [T-d8-guard-fresh] 门禁假绿的根因与堵法。
//
// OrgJsonMemberCallGuardTest 除了扫 harness 自己的产物，还要扫 app 的**设备变体字节码**：
// Kotlin 侧 app/build/tmp/kotlin-classes/<variant>，以及 [T-e9-guard-javac] javac 侧
// app/build/intermediates/javac/<variant>（.java 源与注解处理器生成的 Java）——两者都是
// 测试运行期用相对路径读的普通文件，Gradle 的任务输入/缓存完全不知道它们。只改 app 主
// 源码（例如引入 JSONObject.toMap()）时 :harness:test 的输入指纹不变，而仓库开着
// org.gradle.caching=true、CI 又缓存 ~/.gradle/caches → 任务直接 UP-TO-DATE / FROM-CACHE，
// 连测试报告一起还原（skipped="0" 与扫描标记俱全）→ 三步全绿而字节码根本没扫。
// 这里从 build 侧堵死：
//
// 1. app 设备变体字节码指纹（Kotlin + javac 两侧）声明为 inputs.property：app 字节码一变
//    指纹就变，缓存键与 UP-TO-DATE 判定跟着变。刻意不用 inputs.dir/inputs.files 直接指向
//    kotlin-classes/<variant>：那些目录是 :app:compile<Variant>Kotlin 的声明产物，
//    把它们当普通文件输入声明会进 Gradle 的 implicit_dependency 校验/隐式依赖推导，
//    而本模块既不该硬绑 :app 的编译任务（本机要能单独跑 :harness:test，app 没编译时
//    守卫按 strict 决定红/跳过），指纹属性对缓存键的效果与之等价。
// 2. outputs.upToDateWhen { false }：任务永不 UP-TO-DATE。Gradle 文档对 upToDateWhen
//    写明返回 false 时 "no outputs will be loaded from the build cache"——报告不可能
//    被缓存还原。
// 3. outputs.doNotCacheIf(...)：报告也不写进 build cache，别的 run/机器无从还原它。
// 4. CI 每 run 注入一次性随机数（-Popenminis.orgJsonGuard.runNonce=…，见 pr-check.yml），
//    经 systemProperty 进测试 JVM，测试把它打进报告 <system-out>；缓存产物不可能带上
//    本次 run 的随机数，所以「守卫未被跳过」那条判据无法被缓存产物满足。
//
// 说明：指纹只影响缓存键/UP-TO-DATE，门禁的 fail-closed 由 2/3/4 与守卫自身的 strict
// 模式保证；即使下面的排除规则将来与守卫的 TEST_ONLY_MARKERS 漂移，也只会多算/少算
// 一次指纹，不会造成假绿。
//
// [T-e9-guard-coverage] 「指纹变了」挡不住另一种假绿：「扫了但没扫全」。本任务与 app 的
// 编译任务之间没有顺序边（本脚本刻意不硬绑），同一 invocation + --parallel 或上次残留的
// 半成品产物都会让守卫只扫到一个风味（本机实测存在 tmp/kotlin-classes/slimRelease 这种
// 0 个 class 的空壳目录）。这件事不在 build 侧判定：守卫自己按 expectedDeviceVariants +
// class 数下限核对本次 run 的产物齐备性（strict 下不齐即红），pr-check.yml 把编译与测试
// 拆成两步、并读报告里的 class 数再设一道下限。本脚本只负责让指纹对两侧字节码都敏感。
// ---------------------------------------------------------------------------

tasks.named<Test>("test") {
    // (1) 输入指纹。整段逻辑内联在 provider 的 lambda 里、只读下面两个**局部** val：
    // build script 的顶层 val/fun 会被 Kotlin 编译成脚本类的字段/方法，lambda 一旦碰到
    // 它们就会捕获脚本实例（连带它的 Project 字段），configuration cache 序列化任务状态
    // 时会为此报问题。所以这里不抽 build script 级函数，只用局部变量 + stdlib。
    val appKotlinClassesDir: File = file("../app/build/tmp/kotlin-classes")
    // [T-e9-guard-javac] 守卫还扫 app 的 javac 产物（build/intermediates/javac/<variant>，
    // .java 源与注解处理器生成的 Java 都在这里）。那批字节码同样在 Gradle 的任务输入
    // 之外，所以一并进指纹：只改 .java 源（写出 JSONObject.toMap()）时指纹也必须变。
    val appJavacClassesDir: File = file("../app/build/intermediates/javac")
    inputs.property("appDeviceBytecode", providers.provider {
        // 设备变体字节码指纹：父目录名 + 变体名 + 每个 class 的相对路径/大小/最后修改时间，
        // 排序后取 SHA-256。只读元数据不读内容：任何一次重新编译都会改写 mtime，等长改动
        // （toList() → toMap()）同样逃不掉；app 从未编译过时返回常量 "absent"（本机单独
        // 跑 :harness:test 不受影响）。Gradle 在快照输入时惰性求值，所以 app 即使在本次
        // invocation 里才编译完，拿到的也是当前字节码的指纹。
        // 父目录名要进哈希行：kotlin-classes/fullRelease 与 javac/fullRelease 同名，不区分
        // 就会让两侧的内容拼成同一种行序列（同名不同内容的两次快照可能相互"顶替"）。
        val lines = StringBuilder()
        listOf(appKotlinClassesDir, appJavacClassesDir).forEach { parentDir ->
            if (!parentDir.isDirectory) return@forEach
            parentDir.listFiles().orEmpty()
                // 与 OrgJsonBytecodeGuard.TEST_ONLY_MARKERS 同源的排除规则：*UnitTest /
                // *TestFixtures 只跑桌面 org.json、永不进 APK，守卫不扫，指纹也不跟。
                .filter { dir ->
                    dir.isDirectory &&
                        !dir.name.contains("UnitTest", ignoreCase = true) &&
                        !dir.name.contains("TestFixtures", ignoreCase = true)
                }
                .sortedBy { it.name }
                .forEach { variant ->
                    val classes = variant.walkTopDown()
                        .filter { it.isFile && it.extension == "class" }
                        .map {
                            it.relativeTo(variant).invariantSeparatorsPath +
                                ":" + it.length() + ":" + it.lastModified()
                        }
                        .sorted()
                        .toList()
                    lines.append(parentDir.name).append('/').append(variant.name)
                        .append('=').append(classes.size).append('\n')
                    classes.forEach { lines.append(it).append('\n') }
                }
        }
        if (lines.isEmpty()) return@provider "absent"
        MessageDigest.getInstance("SHA-256")
            .digest(lines.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    })

    // (2) 永不 UP-TO-DATE（Gradle 文档保证此时也不会从 build cache 载入产物）。
    outputs.upToDateWhen { false }

    // (3) 报告不写进 build cache：它是"对某一份 app 字节码"的扫描结论，只对本次扫描有效。
    outputs.doNotCacheIf("OrgJsonMemberCallGuardTest 的结论只对本次扫描的 app 字节码有效") { true }

    // (4) CI 的一次性随机数：Test 的 systemProperty 必然进测试 JVM（显式 this.，免得解析到
    // Project.systemProperty）；测试把它写进报告，pr-check.yml 的「守卫未被跳过」步骤用
    // 同一个值校验（缓存产物不可能满足）。
    providers.gradleProperty("openminis.orgJsonGuard.runNonce").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { nonce -> this.systemProperty("openminis.orgJsonGuard.runNonce", nonce) }
}
