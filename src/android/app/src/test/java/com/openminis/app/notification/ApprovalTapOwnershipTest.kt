package com.openminis.app.notification

import com.openminis.app.service.ApprovalGate
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B14 回归：审批归属校验一度以「发通知那一刻的会话快照」为**唯一**判据，而
 * [ApprovalGate.migrateSession]（ChatViewModelEnsureSession 的 draft→persisted
 * 迁移，`__new__…` → 真实 UUID）会改写在飞请求的 sessionId。于是快照过期：
 * [ApprovalGate.resolve] 静默 return，而 [ApprovalBroadcastReceiver] 又无条件
 * `cancelApproval` —— 用户点"同意"被丢弃、通知消失，
 * `SecurityGateHolder.waitFor` 一直阻塞到 90s 超时再把 "User REJECTED" 回给模型，
 * 一次真实批准被当成拒绝并终止任务线。
 *
 * 修法：归属判据改成「请求的 id（权威身份）+ 它归属过的会话键集合（归档键与
 * 历次迁移后的路由键）」，并把"点击是否落地"变成显式返回值。
 *
 * 本模块没有 Robolectric，构造不了 BroadcastReceiver，所以钉住 receiver 依赖的
 * 两个接缝：
 * ① gate 的归属/落地判据（approve/deny 的返回值、waitFor 的结果）；
 * ② [ApprovalNotifier.shouldClearEntryAfterTap] —— receiver 是否清除通知条目的判据。
 */
class ApprovalTapOwnershipTest {

    private val draft = "__new__draft-4f21c9"
    private val real = "9f1d2c3b-4a5e-4f60-8b71-2c3d4e5f6071"
    private val foreign = "session-that-never-filed-anything"

    @After
    fun cleanup() {
        ApprovalGate.cleanupAll()
    }

    @Test
    fun `migration rewrites the routing key but keeps the notification snapshot valid`() {
        val id = ApprovalGate.requestApproval(draft, "file_write", "preview", mustPrompt = false)
        assertTrue(id.isNotEmpty())
        assertEquals(draft, ApprovalGate.detailOf(id)?.sessionId)
        // 通知 extras 里带的就是这个键（归档时刻的快照）。
        assertEquals(setOf(draft), ApprovalGate.detailOf(id)?.ownedSessionIds)

        ApprovalGate.migrateSession(draft, real)

        assertEquals(real, ApprovalGate.detailOf(id)?.sessionId)
        assertEquals(
            "迁移只追加路由键；归档键被抹掉等于让用户手里的按钮失效",
            setOf(draft, real),
            ApprovalGate.detailOf(id)?.ownedSessionIds,
        )
        assertEquals("迁移后站内横幅仍看得到这条请求", listOf(id), ApprovalGate.pendingIds(real))
    }

    @Test
    fun `a request migrated twice still accepts every session key it owned`() {
        val forked = "9f1d2c3b-4a5e-4f60-8b71-000000000002"
        val id = ApprovalGate.requestApproval(draft, "file_write", "preview", mustPrompt = false)
        ApprovalGate.migrateSession(draft, real)
        ApprovalGate.migrateSession(real, forked)

        assertEquals(forked, ApprovalGate.detailOf(id)?.sessionId)
        assertEquals(setOf(draft, real, forked), ApprovalGate.detailOf(id)?.ownedSessionIds)
        assertTrue("中途用过的键仍然是归属键，落地的批准不能被判成别人的", ApprovalGate.approve(id, real))
    }

    @Test
    fun `the notification snapshot still lands after a second migration`() {
        val forked = "9f1d2c3b-4a5e-4f60-8b71-000000000002"
        val id = ApprovalGate.requestApproval(draft, "file_write", "preview", mustPrompt = false)
        val snapshot = ApprovalGate.detailOf(id)?.sessionId // == EXTRA_SESSION_ID
        ApprovalGate.migrateSession(draft, real)
        ApprovalGate.migrateSession(real, forked)
        assertTrue("通知里的归档键始终有效", ApprovalGate.approve(id, snapshot))
    }

    @Test
    fun `tap carrying the pre migration snapshot still lands after the draft is persisted`() =
        runTest(UnconfinedTestDispatcher()) {
            val id = ApprovalGate.requestApproval(draft, "file_write", "preview", mustPrompt = false)
            val snapshot = ApprovalGate.detailOf(id)?.sessionId // == EXTRA_SESSION_ID
            assertEquals(draft, snapshot)

            ApprovalGate.migrateSession(draft, real) // ChatViewModelEnsureSession

            // SecurityGateHolder.intercept 侧已经挂在同一请求上等待。
            val waiter = async { ApprovalGate.waitFor(id, real) }

            assertTrue(
                "点『同意』必须落地，而不是被归属校验静默丢弃",
                ApprovalGate.approve(id, snapshot),
            )
            assertTrue("waitFor 必须看到批准，而不是 90s 超时后的拒绝", waiter.await())

            // receiver 的清条目判据：点击落地 + 请求已摘除 → 清掉通知。
            assertTrue(
                ApprovalNotifier.shouldClearEntryAfterTap(
                    tapLanded = true,
                    requestStillPending = ApprovalGate.detailOf(id) != null,
                ),
            )
        }

    @Test
    fun `waiting with the pre migration snapshot still receives the approval`() =
        runTest(UnconfinedTestDispatcher()) {
            val id = ApprovalGate.requestApproval(draft, "shell_exec", "rm -rf /tmp/x", mustPrompt = true)
            val snapshot = ApprovalGate.detailOf(id)?.sessionId

            ApprovalGate.migrateSession(draft, real)

            // 反向交错：等待方手里还是旧键，站内 UI 用的是迁移后的键。
            val waiter = async { ApprovalGate.waitFor(id, snapshot) }
            assertTrue(ApprovalGate.approve(id, real))
            assertTrue("旧会话键同样是该请求归属过的键，不能骗成拒绝", waiter.await())
        }

    @Test
    fun `deny carrying the pre migration snapshot lands as a rejection`() =
        runTest(UnconfinedTestDispatcher()) {
            val id = ApprovalGate.requestApproval(draft, "file_write", "preview", mustPrompt = false)
            val snapshot = ApprovalGate.detailOf(id)?.sessionId
            ApprovalGate.migrateSession(draft, real)

            val waiter = async { ApprovalGate.waitFor(id, real) }
            assertTrue(ApprovalGate.deny(id, snapshot))
            assertFalse("拒绝必须让等待者拿到 false", waiter.await())
        }

    @Test
    fun `a foreign session key can neither resolve nor wait on the request`() =
        runTest(UnconfinedTestDispatcher()) {
            val id = ApprovalGate.requestApproval(draft, "file_write", "preview", mustPrompt = false)
            ApprovalGate.migrateSession(draft, real)

            assertFalse("外部会话键不得批准别人的请求", ApprovalGate.approve(id, foreign))
            assertNotNull("被拒绝的批准不得摘除请求", ApprovalGate.detailOf(id))
            assertFalse("外部会话同样不能替别人等（fail-closed，立即 false）", ApprovalGate.waitFor(id, foreign))
            assertTrue("请求仍在，真正的主人照样批准得了", ApprovalGate.approve(id, real))
        }

    @Test
    fun `a second tap on an already resolved request is a stale no-op whose entry may be cleared`() {
        val id = ApprovalGate.requestApproval(draft, "file_write", "preview", mustPrompt = false)
        assertTrue(ApprovalGate.approve(id, draft))
        assertFalse("同一请求不能被批准两次", ApprovalGate.approve(id, draft))
        assertFalse(ApprovalGate.deny(id, draft))
        assertTrue(
            "请求已不在待审批表 → 过期通知可以清",
            ApprovalNotifier.shouldClearEntryAfterTap(
                tapLanded = false,
                requestStillPending = ApprovalGate.detailOf(id) != null,
            ),
        )
    }

    @Test
    fun `the entry survives a tap that did not land while the request is still pending`() {
        assertTrue(
            "点击落地 → 清掉",
            ApprovalNotifier.shouldClearEntryAfterTap(tapLanded = true, requestStillPending = false),
        )
        assertTrue(
            "过期通知（请求已消失）→ 清掉",
            ApprovalNotifier.shouldClearEntryAfterTap(tapLanded = false, requestStillPending = false),
        )
        assertFalse(
            "请求还挂着但点击没落地 → 必须留着按钮，不能把用户的选择一起吞掉",
            ApprovalNotifier.shouldClearEntryAfterTap(tapLanded = false, requestStillPending = true),
        )
    }
}
