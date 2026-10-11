package com.openminis.app.data.body

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-admission-not-empty]：进程级预算只是并发提示，不是「这次操作没有数据」。
 * 把一次暂时拒绝当成空结果，正是 ChatRepository.loadBoundedMessageRange 注释里
 * 记下的那个坑（loadSession 因此清空两端游标、把长会话中段永久搁浅）。
 * [Admission.awaitAdmit] 给调用方一个"等一会儿再试"的机会；返回 false 时调用方
 * 必须继续跑有界查询，而不是返回空。
 */
class AdmissionWaitTest {

    @Test
    fun `awaitAdmit waits out the window before giving up`() = runBlocking {
        Admission.resetForTests()
        assertTrue(Admission.tryAdmit(ResourceLimits.ADMIT_BUDGET_BYTES))
        try {
            val started = System.currentTimeMillis()
            assertFalse(Admission.awaitAdmit(bytes = 1L, waitMillis = 100L, stepMillis = 20L))
            assertTrue(
                "必须真的等过窗口，而不是立刻放弃",
                System.currentTimeMillis() - started >= 100L,
            )
        } finally {
            Admission.release(ResourceLimits.ADMIT_BUDGET_BYTES)
            Admission.resetForTests()
        }
    }

    @Test
    fun `awaitAdmit succeeds as soon as the budget frees up`() = runBlocking {
        Admission.resetForTests()
        assertTrue(Admission.tryAdmit(ResourceLimits.ADMIT_BUDGET_BYTES))
        val releaser = launch(Dispatchers.Default) {
            delay(40L)
            Admission.release(ResourceLimits.ADMIT_BUDGET_BYTES)
        }
        try {
            assertTrue(Admission.awaitAdmit(bytes = 1L, waitMillis = 2_000L, stepMillis = 20L))
        } finally {
            releaser.join()
            Admission.resetForTests()
        }
    }

    @Test
    fun `awaitAdmit admits immediately when the budget is free`() = runBlocking {
        Admission.resetForTests()
        assertTrue(Admission.awaitAdmit(bytes = 1L, waitMillis = 1_000L, stepMillis = 20L))
        Admission.release(1L)
        Admission.resetForTests()
    }
}
