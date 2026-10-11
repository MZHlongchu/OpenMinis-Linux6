package com.openminis.app.agent

import com.openminis.app.harness.agent.ToolCallRecord
import com.openminis.app.harness.agent.ToolLoopDetector
import kotlinx.coroutines.*
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

/**
 * [T-android-concurrent-tools] ToolLoopDetector under concurrent callers.
 *
 * Regression test for a device failure, not a hypothetical: a sub agent on a
 * Pixel 6 died with "Attempt to invoke virtual method 'String
 * ToolCallRecord.getToolName()' on a null object reference" and the whole run
 * was lost. The detector's class doc said "serialize calls through the agent
 * loop's existing single-threaded dispatch" — and concurrent tool dispatch had
 * just removed that guarantee. Its ArrayDeque grew on one thread while another
 * scanned it, exposing a transiently-null slot.
 *
 * [T-p0-1-extraction] 本移植把 ToolLoopDetector 从 :app 抽进了 :harness
 * （com.openminis.app.harness.agent），而上游 1.14 的同名测试是在 app 包里裸引用
 * 该类的（上游当时只有 :app 一个模块），所以这里补上 harness 的 import。
 * 依赖无需新增：:harness 已经是 :app 的 implementation 依赖
 * （src/android/app/build.gradle.kts），而 testImplementation 继承
 * implementation，单测源集本来就能拿到它。
 *
 * 搬不过去的只有窗口自省：harness 里 `historySnapshot()` 是 internal，跨模块的
 * app 单测看不见，本工单又不允许改生产可见性。于是下面的 window() 按名字反射取
 * 一次那个方法——被测代码、断言文字、断言内容全部保持上游原样（见 diff：本文件
 * 相对 upstream-1.14 只有 import + 文档 + helper 是新增）。harness 侧日后若把
 * `historySnapshot()` 放开为 public，本 helper 同样命中（按名前缀取方法），也可以
 * 直接退回 `d.historySnapshot()` 并删掉 helper。
 */
class ToolLoopDetectorConcurrencyTest {

    /**
     * [T-p0-1-extraction] internal 成员函数在字节码里可能带模块名后缀
     * （historySnapshot$harness），所以按前缀 + 零参匹配，两种命名都取得到；
     * 取不到就直接构造失败——绝不静默降级成"没有断言"。
     */
    private val snapshotMethod: Method = ToolLoopDetector::class.java.declaredMethods
        .firstOrNull { it.parameterCount == 0 && it.name.startsWith("historySnapshot") }
        ?.apply { isAccessible = true }
        ?: throw AssertionError("ToolLoopDetector.historySnapshot() 不在了，请修本测试的反射取值")

    /** 反射版 historySnapshot()：仅用于跨模块读取那个 internal 窗口快照。 */
    @Suppress("UNCHECKED_CAST")
    private fun ToolLoopDetector.window(): List<ToolCallRecord> =
        snapshotMethod.invoke(this) as List<ToolCallRecord>

    @Test
    fun `concurrent check and record never expose a null record`() = runBlocking {
        repeat(12) {
            val d = ToolLoopDetector()
            coroutineScope {
                (0 until 24).map { i ->
                    async(Dispatchers.Default) {
                        repeat(40) { n ->
                            val params = mapOf("q" to "v$i-$n")
                            d.check("shell_execute", params)
                            d.record("shell_execute", params, result = "ok$n", toolCallId = "c$i-$n")
                            d.window().forEach { r ->
                                assertTrue("a record must never be null-ish", r.toolName.isNotEmpty())
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }
}
