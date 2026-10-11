package com.openminis.app.provider

import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [T-imgsize-recompress-cache] `compressUnderBudget` 的边界契约。
 *
 * 这里只覆盖"走不到重编码"的分支：真正的重编码需要 android.graphics 的解码 +
 * JPEG 编码，本模块单测没有 Robolectric。但这条分支恰好是缓存的关键约束——
 * provider 侧用 `safeBytes === part.data` 判断"是否被重编码"来决定 media_type，
 * 一旦把"压缩失败、原样返回"的结果当作缓存值回给另一个等值输入，未压缩的 PNG
 * 字节就会被标成 image/jpeg（provider 传上去的 media_type 与实际字节不符）。
 */
class ImageBudgetCompressCacheTest {

    @Test
    fun `undecodable oversize bytes come back as the same instance`() {
        // 单测 JVM 里 BitmapFactory 返回 null → compressBytes 原样返回 →
        // compressUnderBudget 走"阶梯耗尽"分支。
        val big = ByteArray((ImageBudget.MAX_PER_IMAGE_BYTES + 1).toInt())
        assertSame(big, ImageBudget.compressUnderBudget(big))
    }

    @Test
    fun `failed compression is not cached across equal inputs`() {
        val a = ByteArray((ImageBudget.MAX_PER_IMAGE_BYTES + 1).toInt())
        val b = a.copyOf() // 内容相同、实例不同

        assertSame(a, ImageBudget.compressUnderBudget(a))
        // 若"原样返回"的结果进了缓存，这里会拿到 a 而不是 b。
        assertSame(b, ImageBudget.compressUnderBudget(b))
    }
}
