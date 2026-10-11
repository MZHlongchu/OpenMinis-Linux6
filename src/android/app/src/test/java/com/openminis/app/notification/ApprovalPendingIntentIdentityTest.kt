package com.openminis.app.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * [T-approval-pendingintent-identity] 钉住"按钮 PendingIntent 身份随 requestId 变化"
 * 这一不变量：PendingIntent 的身份是 (requestCode, Intent.filterEquals)，extras 不参与，
 * 唯一能区分两个并发审批的成分就是 data —— 同 action + 同 requestCode + 同组件时，
 * data 相同即身份相同，`FLAG_UPDATE_CURRENT` 会让后一条覆盖前一条的 extras。
 *
 * 本模块没有 Robolectric，所以只测纯字符串成分（不能构造真实 PendingIntent）。
 */
class ApprovalPendingIntentIdentityTest {

    @Test
    fun `distinct request ids never share the same pending intent identity`() {
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        assertNotEquals(
            ApprovalNotifier.approvalActionDataPath(a),
            ApprovalNotifier.approvalActionDataPath(b),
        )
    }

    @Test
    fun `same request id keeps a stable identity`() {
        val id = UUID.randomUUID().toString()
        assertEquals(
            ApprovalNotifier.approvalActionDataPath(id),
            ApprovalNotifier.approvalActionDataPath(id),
        )
    }

    @Test
    fun `identity is a well formed minis uri carrying the request id`() {
        val id = "3f1c2a64-0000-4444-8888-abcdefabcdef"
        val path = ApprovalNotifier.approvalActionDataPath(id)
        assertTrue(path.startsWith("minis://approval/"))
        assertEquals(id, path.removePrefix("minis://approval/"))
    }
}
