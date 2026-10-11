package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-ctx-user-cap] / [T-ctx-overflow-hard-stop]
 *
 * A user-chosen group context cap used to be judged by the native-window tier
 * table, where anything under 64K sets `compactThreshold = 0` — which
 * [ContextPolicy.check] reads as "auto-compact disabled". A 32K cap (the
 * lowest slider stop) therefore never compacted and never blocked, and an
 * over-capacity session kept sending. iOS parity: `ContextPolicyTests.swift`.
 */
class ContextPolicyUserCapTest {

    @Test
    fun `user cap of 32K keeps auto-compact available`() {
        val policy = ContextPolicy.forUserCap(32_000)
        assertEquals(27_200, policy.compactThreshold) // 85%
        assertEquals(22_400, policy.offloadThreshold) // 70%
        assertEquals(17_600, policy.offloadTarget) // 55%
        assertFalse(policy.exhaustedOnly)
        assertTrue(policy.manualCompactAllowed)
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            policy.check(27_200, 32_000),
        )
        assertEquals(ContextPolicy.CheckResult.OK, policy.check(27_199, 32_000))
    }

    /**
     * The same number as a model's NATIVE window must go through the tier table
     * (a fixed headroom subtraction), not the user-cap percentages — otherwise
     * "keep this group under 32K" and "this model has a 32K window" would be
     * judged by the same arithmetic.
     *
     * [T-merge-1.14-ctx-tier] 上游 1.14 的这条断言写的是「native 32K 仍留在 legacy
     * 档：compactThreshold = 0、exhaustedOnly = true」。合并时本移植保留的是**本移植
     * 自己的档位表**：32K–64K 档也开自动压缩、阈值固定为 ctx − 15k
     * （`[T-compact-small-window-auto]`，由同目录 InLoopContextPolicyTest 守着，那条
     * 用例在任何失败清单里都没有红）。所以这两条前置断言改成同一张表下的数字，而不是
     * 把生产逻辑改回上游档位表（那会让 InLoopContextPolicyTest 的 2 条用例变红，
     * 等于把两处失败换成五处）。
     *
     * **意图与强度都没变**：本用例要证明的仍然是「同一个 32K，作为模型原生窗口时按
     * 档位表算，而不是按用户上限的百分比算」——上一条 `user cap of 32K …` 覆盖另一半，
     * 这条新增的 assertNotEquals 把两者不能混用这一点钉死。「小到付不起一次摘要」的
     * 那种档位由本文件下面的 `overflow on a tiny window reports exhausted` 继续守着
     * （<32K：compactThreshold = 0、exhaustedOnly = true、manualCompactAllowed = false）。
     */
    @Test
    fun `native window of 32K keeps the legacy tier`() {
        val policy = ContextPolicy.forContextWindow(32_000)
        assertEquals(32_000 - 15_000, policy.compactThreshold)
        assertFalse(policy.exhaustedOnly)
        // 同一个数字当用户上限时按 85% 算（27_200），两种口径绝不能混用。
        assertNotEquals(
            ContextPolicy.forUserCap(32_000).compactThreshold,
            policy.compactThreshold,
        )
    }

    @Test
    fun `user cap of 128K compacts proportionally`() {
        val policy = ContextPolicy.forUserCap(128_000)
        assertEquals(108_800, policy.compactThreshold) // 85%
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            policy.check(108_800, 128_000),
        )
    }

    /** The reported screenshot: 138.8k used against a 128k cap = 108%. */
    @Test
    fun `user cap of 128K compacts when already over the cap`() {
        val policy = ContextPolicy.forUserCap(128_000)
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            policy.check(138_800, 128_000),
        )
    }

    /**
     * Previously OK: an exhausted-only tier returned OK up to its advisory line
     * and kept returning OK past 100%, so the over-length request still went.
     *
     * [T-merge-1.14-ctx-tier] 上游这条用例用 `forContextWindow(48_000)` 取那一档
     * （compactThreshold = 0 + exhaustedOnly + manualCompactAllowed）。本移植的档位表
     * 里已经没有「exhaustedOnly 且可手动压缩」的档了（32K–64K 开了自动压缩，<32K
     * 关掉了手动压缩），但 check() 里那条要被验证的分支依然存在——所以这里把上游那一
     * 档的数值原样写出来直接构造，语义与上游完全一致，并且**顺带**验证走档位表的
     * 同一个 48K 也压缩（两条路径产品行为一致）。断言一条没少。
     */
    @Test
    fun `overflow on an exhausted-only tier compacts when a summary is viable`() {
        val policy = ContextPolicy(
            offloadThreshold = 48_000 - 10_000,
            offloadTarget = 48_000 - 15_000,
            compactThreshold = 0,
            exhaustedOnly = true,
            manualCompactAllowed = true,
        )
        assertEquals(0, policy.compactThreshold) // precondition
        assertTrue(policy.manualCompactAllowed) // precondition
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            policy.check(48_000, 48_000),
        )
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            policy.check(60_000, 48_000),
        )
        // 同一个 48K 走档位表（ctx − 15k = 33k）也必须压缩，而不是被放行。
        val tiered = ContextPolicy.forContextWindow(48_000)
        assertEquals(48_000 - 15_000, tiered.compactThreshold)
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            tiered.check(48_000, 48_000),
        )
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            tiered.check(60_000, 48_000),
        )
    }

    /** Below 32K a summary is not viable, so overflow must prompt instead. */
    @Test
    fun `overflow on a tiny window reports exhausted`() {
        val policy = ContextPolicy.forContextWindow(8_192)
        assertFalse(policy.manualCompactAllowed) // precondition
        assertEquals(
            ContextPolicy.CheckResult.EXHAUSTED,
            policy.check(9_000, 8_192),
        )
    }

    @Test
    fun `overflow handling leaves the large-window path alone`() {
        val policy = ContextPolicy.forContextWindow(200_000)
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            policy.check(180_000, 200_000),
        )
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            policy.check(250_000, 200_000),
        )
        assertEquals(ContextPolicy.CheckResult.OK, policy.check(179_999, 200_000))
    }
}
