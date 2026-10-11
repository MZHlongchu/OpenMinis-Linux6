package com.openminis.app.sandbox

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-b4-#2] `PRootKernel.bindMounts` 会被主线程（`TerminalSession.buildTermuxArgs`、
 * `WebAppPathResolver.inferScope`——后者直接跑在 Compose onClick 里）遍历，同时 IO
 * 线程在建壳（`buildSessionBindMounts(publish = true)`）与挂载 CRUD 时重写它。
 *
 * 原来的 `linkedMapOf()` 会让遍历方抛 ConcurrentModificationException（Compose 那条
 * 路径就是用户可见的闪退），`resolveHostPath` 里的 `bindMounts[mountPoint]!!` 还会在
 * 排序与取值之间条目被移走时抛 NPE。
 */
class PRootKernelBindMountRaceTest {

    private var saved: Map<String, String> = emptyMap()

    @Before
    fun snapshotMounts() {
        saved = PRootKernel.bindMounts.toMap()
    }

    @After
    fun restoreMounts() {
        PRootKernel.clearBindMounts()
        saved.forEach { (linuxPath, hostPath) -> PRootKernel.addBindMount(linuxPath, hostPath) }
    }

    /** 确定性的一半：底层容器本身必须容忍并发遍历。 */
    @Test
    fun bindMountsBackingMapIsConcurrent() {
        assertTrue(
            "bindMounts must be safe to iterate while another thread mutates it, was " +
                PRootKernel.bindMounts.javaClass.name,
            PRootKernel.bindMounts is ConcurrentHashMap<*, *>,
        )
    }

    /** 行为的一半：写线程不停增删时，排序 + 取值不得抛异常。 */
    @Test
    fun iterationAndLookupSurviveConcurrentMountRebuild() {
        PRootKernel.clearBindMounts()
        for (i in 0 until 16) PRootKernel.addBindMount("/var/minis/m$i", "/host/m$i")

        val failures = CopyOnWriteArrayList<Throwable>()
        val start = CountDownLatch(1)
        val stop = AtomicBoolean(false)

        val writer = thread(name = "mount-writer") {
            start.await()
            var i = 0
            while (!stop.get()) {
                val slot = i % 64
                PRootKernel.addBindMount("/var/minis/m$slot", "/host/m$slot")
                PRootKernel.removeBindMount("/var/minis/m${(slot + 7) % 64}")
                i++
            }
        }
        val reader = thread(name = "mount-reader") {
            start.await()
            repeat(20_000) {
                try {
                    // 与 shell argv 构造器、WebApp 作用域解析器完全相同的遍历方式，
                    // 外加解析器自己的排序。
                    PRootKernel.bindMounts.keys.sortedByDescending { it.length }
                    PRootKernel.resolveHostPath("/var/minis/m1/file.txt")
                } catch (t: Throwable) {
                    failures += t
                    return@thread
                }
            }
        }

        start.countDown()
        reader.join(30_000)
        stop.set(true)
        writer.join(30_000)

        assertEquals(
            "concurrent mount rebuild must not break iteration",
            emptyList<Throwable>(),
            failures.toList(),
        )
    }
}
