package com.openminis.app.sandbox

import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-b4-#6] 每条 `execute()` 的挂载陈旧检测原先都要重跑一次共享存储探测
 * （`Environment.isExternalStorageManager()` + `canRead()` stat）。
 * [SharedStorageProbeCache] 必须让连续调用只探测一次，并在已知失效点之后重新探测。
 *
 * [T-b4-#6-negative] 追加的判据：**未授权**的答案不能无条件缓存住——offload 路径
 * （`OffloadPermissionManager.requestAndroidPermission` → UI 应答）刚把权限给出时，
 * 缓存里的 false 必须立刻让位给新探测，否则 `/sdcard` 会被摘到下次前台。
 */
class SharedStorageProbeCacheTest {

    /** 复检回调：默认"没权限"，与缓存里的负数一致。 */
    private val denied = { false }

    @After
    fun clearCache() {
        SharedStorageProbeCache.invalidate()
    }

    @Test
    fun repeatedResolveProbesOnce() {
        SharedStorageProbeCache.invalidate()
        val probes = AtomicInteger()
        val first = SharedStorageProbeCache.resolve(denied) {
            probes.incrementAndGet()
            SharedStorageProbeCache.Probe("/storage/emulated/0", granted = true)
        }
        val second = SharedStorageProbeCache.resolve(denied) {
            probes.incrementAndGet()
            SharedStorageProbeCache.Probe(null, granted = false)
        }

        assertEquals(1, probes.get())
        assertTrue(first.granted)
        // 第二次命中缓存，不再执行（内容不同的）lambda。
        assertEquals("/storage/emulated/0", second.host)
        assertTrue(second.granted)
    }

    @Test
    fun invalidateForcesAFreshProbe() {
        SharedStorageProbeCache.resolve(denied) { SharedStorageProbeCache.Probe(null, granted = false) }

        SharedStorageProbeCache.invalidate()

        val probes = AtomicInteger()
        val after = SharedStorageProbeCache.resolve(denied) {
            probes.incrementAndGet()
            SharedStorageProbeCache.Probe("/storage/emulated/0", granted = true)
        }

        assertEquals(1, probes.get())
        assertTrue("刚授予的权限不能被缓存成未授权", after.granted)
    }

    /** 回归本体：缓存说"没权限"，而实时权限位已经给了 ⇒ 必须强制重探。 */
    @Test
    fun deniedHitYieldsToALivePermissionGrant() {
        SharedStorageProbeCache.invalidate()
        val probes = AtomicInteger()
        val permission = AtomicInteger(0) // 0 = 未授权，1 = 已授权

        val before = SharedStorageProbeCache.resolve({ permission.get() == 1 }) {
            probes.incrementAndGet()
            SharedStorageProbeCache.Probe("/storage/emulated/0", granted = false)
        }
        assertFalse(before.granted)

        // offload 路径刚授权：没有 invalidate 调用，只有权限位变了。
        permission.set(1)
        val after = SharedStorageProbeCache.resolve({ permission.get() == 1 }) {
            probes.incrementAndGet()
            SharedStorageProbeCache.Probe("/storage/emulated/0", granted = true)
        }

        assertEquals("负数命中必须复检权限并重探", 2, probes.get())
        assertTrue("授权成功后 /sdcard 必须立刻可用（不必等下次前台）", after.granted)
        assertEquals("/storage/emulated/0", after.host)
    }

    @Test
    fun deniedHitIsNotReprobedWhilePermissionStaysDenied() {
        SharedStorageProbeCache.invalidate()
        val probes = AtomicInteger()
        val checks = AtomicInteger()
        repeat(3) {
            SharedStorageProbeCache.resolve({
                checks.incrementAndGet()
                false
            }) {
                probes.incrementAndGet()
                SharedStorageProbeCache.Probe(null, granted = false)
            }
        }

        assertEquals("权限仍然没给：不能每条命令都重探", 1, probes.get())
        assertEquals("复检只查权限位，不做 canRead() stat", 2, checks.get())
    }

    @Test
    fun grantedHitNeverRechecksPermission() {
        SharedStorageProbeCache.invalidate()
        val probes = AtomicInteger()
        val checks = AtomicInteger()
        SharedStorageProbeCache.resolve(denied) {
            probes.incrementAndGet()
            SharedStorageProbeCache.Probe("/storage/emulated/0", granted = true)
        }
        repeat(3) {
            val hit = SharedStorageProbeCache.resolve({
                checks.incrementAndGet()
                false
            }) {
                probes.incrementAndGet()
                SharedStorageProbeCache.Probe(null, granted = false)
            }
            assertTrue(hit.granted)
        }

        assertEquals(1, probes.get())
        assertEquals("已授权的稳态必须零额外开销", 0, checks.get())
    }
}
