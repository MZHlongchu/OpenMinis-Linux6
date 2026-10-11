package com.openminis.app.sandbox.offload

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-model-use-no-media-fails] A generation that had to produce media
 * and produced none exits non-zero and writes nothing — so the agent cannot
 * read "success" and reference an image that does not exist.
 *
 * The handler's runtime path needs a live provider, so this pins the
 * decisions in source.
 */
class ModelUseNoMediaFailsTest {

    private val src = File("src/main/java/com/openminis/app/sandbox/offload/ModelUseOffloadHandler.kt").readText()

    @Test
    fun `the chat path fails when media was required and none came back, before writing anything`() {
        // [T-android-model-use-provider-pollution] 扫描面跟着结构走：`cmdRun` 现在只是
        // per-call provider 卫生的薄包装（try/finally 复位 provider 覆写），真正的
        // chat 路径体在 `cmdRunInner` 里。原来切 `private fun cmdRun(` 只会拿到那三行
        // 包装，断言与产品行为无关地变红。切到下一个 `private fun ` 为止，仍限定在
        // **这条 chat 路径的函数体**内，意图（"要求出图却一张没出时，在写任何输出之前
        // 以非零退出"）一个字没变。
        val run = src.substringAfter("private fun cmdRunInner(")
            .substringBefore("private fun parseImageGenConfig(")
        assertTrue(
            "没定位到 cmdRunInner 的函数体（锚点需要跟着改）",
            run.isNotBlank() && run != src,
        )
        val check = run.indexOf("if (mediaRequired && response.mediaAttachments.isEmpty())")
        val write = run.indexOf("hostFile.writeText(response.text)")
        assertTrue("check present", check > 0)
        assertTrue("check precedes any write to --output", check < write)
        assertTrue(run.contains("isImageExt(outputExt) || isAudioExt(outputExt) || isVideoExt(outputExt)"))
        assertTrue("pure image generators count", run.contains("(\"image\" in outputs && \"text\" !in outputs)"))
    }

    @Test
    fun `the image endpoint path fails on an empty result instead of warning`() {
        val img = src.substringAfter("private fun writeImageResult(").substringBefore("private fun noMediaResult(")
        assertTrue(img.contains("if (firstMedia == null) {\n            return noMediaResult("))
        assertFalse("no more exit-0 with a warning", src.contains("Image endpoint returned no image data."))
    }

    @Test
    fun `the failure exits 1 with a machine-readable error`() {
        val fn = src.substringAfter("private fun noMediaResult(").substringBefore("\n    }\n")
        assertTrue(fn.contains("put(\"error\", \"no_media_generated\")"))
        assertTrue(fn.contains("NativeOffloadResult(1,"))
    }
}
