package com.openminis.app.sandbox.offload

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review 2026-09-23 — guards eff4ac95f (T-android-a11y-screenshot-mount) for a
 * sub-agent / helper that shares its parent's workspace (T-p2-shared-workspace).
 *
 * For a helper, ExecutionCoordinator binds `/var/minis/attachments` to the
 * PARENT's `minis-sessions/<parent>/attachments` (fsSessionId), but exports
 * `MINIS_CHAT_SESSION_ID=<helper id>` (dispatchSessionId). The a11y handler
 * resolves the screenshot directory from that env id, so the PNG is written to
 * `minis-sessions/<helper>/attachments/screenshots/`, while the returned path
 * `/var/minis/attachments/screenshots/a11y_<ts>.png` is looked up by the
 * helper's shell (parent mount) and by ReadImageTool (called with fsSessionId =
 * parent). Neither finds it: the exact "File not found" eff4ac95f fixed for the
 * main chat comes back for every helper.
 *
 * Invariant: the id the handler resolves `/var/minis/attachments` with must be
 * the id whose attachments directory the guest has mounted.
 */
class Review0923A11yHelperSessionTest {

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing ${f.absolutePath}", f.exists())
        return f.readText()
    }

    private val coord by lazy { src("src/main/java/com/openminis/app/sandbox/ExecutionCoordinator.kt") }
    private val a11y by lazy { src("src/main/java/com/openminis/app/sandbox/offload/AccessibilityOffloadHandler.kt") }

    /**
     * [T-a15-vm-split-surface] `ui/chat/ChatViewModel.kt` 单文件 → ChatViewModel 的
     * **整个类源码面**（`ChatViewModel.kt` + 同目录 `ChatViewModel*Ext.kt` 等分片）。
     * 合并上游 1.14 后这个类按职责拆成几十个文件（本移植的 [T-architecture-modularization]），
     * `ReadImageTool.execute(argsJson, fsSessionId, context)` 落在
     * `ChatViewModelReadImage.kt`，单文件读法只会给出"这个文件里没有"的假红 ——
     * 扫描面过期，不是行为回归。断言本身不动：仍是"read_image 必须按**挂载时用的**
     * 会话 id 解析"（父会话 id），这正是 eff4ac95f / T-p2-shared-workspace 的不变量。
     */
    private val vm by lazy {
        com.openminis.app.ProductionSources.readClassSurface("ui/chat/ChatViewModel.kt")
    }

    @Test
    fun `premise - helpers read images and mount dirs by the parent's id`() {
        assertTrue(vm.contains("helperConfig?.parentSessionId ?: activeSessionId"))
        assertTrue(vm.contains("ReadImageTool.execute(argsJson, fsSessionId, context)"))
        assertTrue(coord.contains("buildSessionBindMounts(fsSessionId ?: sessionId)"))
    }

    @Test
    fun `BUG the screenshot directory is resolved with the mounted session id`() {
        // Currently: env carries the dispatch (helper) id, and the handler
        // resolves attachments with it. Either the coordinator must export the
        // fs session id (e.g. MINIS_FS_SESSION_ID) and the handler prefer it, or
        // the handler must map a helper id to its parent before resolving.
        val envIsDispatchId = coord.contains("sessionId = sessionId,") &&
            !coord.contains("MINIS_FS_SESSION_ID")
        val handlerKeysOnEnvId = a11y.contains("uiSub(args, request.sessionId)") &&
            a11y.contains("resolveSessionHostPath(it, VAR_MINIS_ATTACHMENTS, context)")
        assertFalse(
            "a helper's a11y screenshot lands in the helper's own attachments dir, " +
                "which neither its shell nor read_image can see",
            envIsDispatchId && handlerKeysOnEnvId,
        )
    }
}
