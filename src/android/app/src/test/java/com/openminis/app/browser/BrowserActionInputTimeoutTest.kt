package com.openminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-browser-domstable-timeout-unit] `timeout` 字段的两个生产方单位不同：
 * agent 工具的 schema 声明秒（执行器把它按别名补成 `timeout_seconds`），
 * CLI offload 的 `--timeout <ms>` 声明毫秒。解析必须两边都对，且极端秒数
 * 不能把 Int 乘溢出。
 */
class BrowserActionInputTimeoutTest {

    @Test
    fun `agent seconds arrive as timeout_seconds and become milliseconds`() {
        // normalizedExecArgs 的别名规整：{"timeout":10} → 追加 "timeout_seconds":10。
        val input = BrowserActionInput.parse(
            """{"action":"wait_for_dom_stable","timeout":10,"timeout_seconds":10}"""
        )
        assertEquals(10_000, input?.timeoutMs)
    }

    @Test
    fun `cli offload timeout stays milliseconds without the alias`() {
        val input = BrowserActionInput.parse(
            """{"action":"wait_for_dom_stable","timeout":60000}"""
        )
        assertEquals(60_000, input?.timeoutMs)
    }

    @Test
    fun `extreme seconds are clamped before multiplying`() {
        val input = BrowserActionInput.parse(
            """{"action":"wait_for_dom_stable","timeout_seconds":2147483}"""
        )
        assertEquals(3_600_000, input?.timeoutMs)
    }

    @Test
    fun `non positive seconds fall back to no budget`() {
        val input = BrowserActionInput.parse(
            """{"action":"wait_for_dom_stable","timeout_seconds":0}"""
        )
        assertNull(input?.timeoutMs)
    }

    @Test
    fun `missing timeout stays absent`() {
        assertNull(BrowserActionInput.parse("""{"action":"screenshot"}""")?.timeoutMs)
    }
}
