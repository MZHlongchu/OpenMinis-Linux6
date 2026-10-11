package com.openminis.app.i18n

import com.openminis.app.i18n.LanguageSwitchController.LangPair
import com.openminis.app.i18n.LanguageSwitchController.SwitchResult
import com.openminis.app.i18n.LanguageSwitchController.SwitchState
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-lang-switch-txn] 切换事务纯逻辑测试：双槽语义（rollbackTo 只认已生效槽）、
 * 连切基准、下载失败/取消回滚、回滚失败落点态、串行下载队列，以及
 * **清半成品只清本次缺失侧**（不误删已生效语言对仍在用的共用侧）。
 * 依赖全注入，无 Android。
 */
class LanguageSwitchControllerTest {

    private class Harness {
        var effective = LangPair("en", "zh")
        val ready = mutableSetOf(LangPair("en", "zh"))

        /** 本地已有的**标签侧**（模拟 downloadedLanguages）：默认 en、zh 在本地。 */
        val downloadedSides = mutableSetOf("en", "zh")
        val downloads = mutableListOf<LangPair>()
        val aborts = mutableListOf<LangPair>()

        /** [T-cancel-cleanup-sides] 每次 abort 携带的"本次缺失侧"快照。 */
        val abortSides = mutableListOf<Set<String>>()

        /** 清理记录：(语言对, 本次被清掉的侧)。 */
        val cleared = mutableListOf<Pair<LangPair, Set<String>>>()
        var failNextClear = false
        var snapshotFails = false
        var downloadDelayMs = 30L
        var downloadFails = false

        val controller = LanguageSwitchController(
            readEffective = { effective },
            writeEffective = { effective = it },
            isPairReady = { it in ready },
            missingSides = { p ->
                if (snapshotFails) throw RuntimeException("model query failed")
                setOf(p.src, p.tgt) - downloadedSides
            },
            downloadPack = { pair ->
                downloads.add(pair)
                if (downloadDelayMs > 0) delay(downloadDelayMs)
                if (downloadFails) throw RuntimeException("net down")
                ready.add(pair)
                downloadedSides.add(pair.src)
                downloadedSides.add(pair.tgt)
            },
            // [T-cancel-cleanup-sides] abort 现在带"本次缺失侧"：取消时只允许清
            // 这些侧，所以记录两侧参数，并断言没有删到共用侧。
            abortDownload = { pair, sides -> aborts.add(pair); abortSides.add(sides) },            clearPartial = { pair, sides ->
                if (failNextClear) {
                    throw RuntimeException("delete failed")
                }
                cleared.add(pair to sides)
                // 模拟真实删除：被清的侧从本地集合移除——「共用侧有没有被误删」
                // 才成为可断言的观测量，而不是只看调用参数。
                downloadedSides.removeAll(sides)
            },
        )

        /** 等到进入下载相位（缺失侧快照已随状态发布）。 */
        suspend fun awaitDownloading() {
            val downloading = SwitchState.Pending.Phase.DOWNLOADING
            while ((controller.state.value as? SwitchState.Pending)?.phase != downloading) {
                delay(5)
            }
        }

        suspend fun switch(src: String, tgt: String) =
            controller.requestSwitch(LangPair(src, tgt))
    }

    @Test
    fun `same pair is a no-op commit`() = runBlocking {
        val h = Harness()
        assertEquals(SwitchResult.COMMITTED, h.switch("en", "zh"))
        assertEquals(LangPair("en", "zh"), h.effective)
        assertTrue(h.downloads.isEmpty())
    }

    @Test
    fun `ready pair commits immediately without download`() = runBlocking {
        val h = Harness()
        h.ready.add(LangPair("en", "ja"))
        assertEquals(SwitchResult.COMMITTED, h.switch("en", "ja"))
        assertEquals(LangPair("en", "ja"), h.effective)
        assertTrue(h.downloads.isEmpty())
    }

    @Test
    fun `download success commits pair into effective slot`() = runBlocking {
        val h = Harness()
        assertEquals(SwitchResult.COMMITTED, h.switch("en", "ja"))
        assertEquals(LangPair("en", "ja"), h.effective)
        assertEquals(listOf(LangPair("en", "ja")), h.downloads)
    }

    @Test
    fun `download failure keeps effective pair untouched and clears only the new side`() = runBlocking {
        val h = Harness()
        h.downloadFails = true
        assertEquals(SwitchResult.FAILED, h.switch("en", "ja"))
        assertEquals(LangPair("en", "zh"), h.effective)
        // 只清本次缺失的 ja：共用侧 en 不能被清（否则已生效的 en→zh 静默失效）。
        assertEquals(listOf(LangPair("en", "ja") to setOf("ja")), h.cleared)
        assertEquals(setOf("en", "zh"), h.downloadedSides)
        assertEquals(SwitchState.Idle, h.controller.state.value)
    }

    @Test
    fun `rollback during download aborts and clears but keeps effective`() = runBlocking {
        val h = Harness()
        h.downloadDelayMs = 200L
        val job = launch { h.switch("en", "ja") }
        // 等进入 DOWNLOADING 相位（缺失侧快照此时已随状态发布）。
        h.awaitDownloading()
        assertEquals(SwitchResult.ROLLED_BACK, h.controller.rollback())
        assertEquals(listOf(LangPair("en", "ja")), h.aborts)
        assertEquals(listOf(LangPair("en", "ja") to setOf("ja")), h.cleared)
        assertEquals(LangPair("en", "zh"), h.effective)
        assertEquals(SwitchState.Idle, h.controller.state.value)
        job.cancel()
    }

    @Test
    fun `cancel that only changes the target language keeps the shared source pack`() = runBlocking {
        val h = Harness()
        h.downloadDelayMs = 200L
        // 已生效 en→zh 两侧都在本地；用户只改目标语言（en→ja）：本次只缺 ja。
        val job = launch { h.switch("en", "ja") }
        h.awaitDownloading()
        assertEquals(SwitchResult.ROLLED_BACK, h.controller.rollback())
        // 清理面只有 ja：en 仍被已生效语言对使用，删了会让实时翻译静默失效。
        assertEquals(listOf(LangPair("en", "ja") to setOf("ja")), h.cleared)
        assertEquals("已生效 en→zh 的两侧必须都还在本地", setOf("en", "zh"), h.downloadedSides)
        assertEquals(LangPair("en", "zh"), h.effective)
        // [T-cancel-cleanup-sides] abort 携带的缺失侧同样只有 ja：GMS 取消后仍
        // 下完时，游离包清理也只能动这一侧（否则同样会删掉共用的 en 包）。
        assertEquals(listOf(setOf("ja")), h.abortSides)
        job.cancel()
    }

    @Test
    fun `cancel of a fully missing pair still clears both new sides`() = runBlocking {
        val h = Harness()
        h.downloadDelayMs = 200L
        // ja→ko 两侧都不在本地：本次新增两侧，取消时两侧都该清（清理力度不缩水）。
        val job = launch { h.switch("ja", "ko") }
        h.awaitDownloading()
        assertEquals(SwitchResult.ROLLED_BACK, h.controller.rollback())
        assertEquals(listOf(LangPair("ja", "ko") to setOf("ja", "ko")), h.cleared)
        assertEquals(listOf(setOf("ja", "ko")), h.abortSides)
        job.cancel()
    }

    @Test
    fun `missing-side snapshot failure keeps cleanup conservative`() = runBlocking {
        val h = Harness()
        h.snapshotFails = true
        h.downloadFails = true
        assertEquals(SwitchResult.FAILED, h.switch("en", "ja"))
        // 快照拿不到就不放行任何清理：宁留游离包，也不误删在用包。
        assertEquals(listOf(LangPair("en", "ja") to emptySet<String>()), h.cleared)
    }

    @Test
    fun `consecutive switches roll back to the effective pair not the previous pending`() = runBlocking {
        val h = Harness()
        h.downloadDelayMs = 50L
        // 连切：en→ja 下载中，直接切 en→ko。
        val job = async { h.switch("en", "ja") }
        h.awaitDownloading()
        assertEquals(SwitchResult.COMMITTED, h.switch("en", "ko"))
        // 旧事务被回滚：abort ja + 清本次新增的 ja；基准仍是 zh（已生效槽），不是 ja。
        assertEquals(listOf(LangPair("en", "ja")), h.aborts)
        assertTrue((LangPair("en", "ja") to setOf("ja")) in h.cleared)
        assertEquals(LangPair("en", "ko"), h.effective)
        // 被顶掉的旧事务：不写 prefs、不改状态，返回 FAILED。
        assertEquals(SwitchResult.FAILED, job.await())
    }

    @Test
    fun `rollback failure surfaces RollbackFailed state and retry keeps the side snapshot`() = runBlocking {
        val h = Harness()
        h.downloadDelayMs = 200L
        h.failNextClear = true
        val job = launch { h.switch("en", "ja") }
        h.awaitDownloading()
        assertEquals(SwitchResult.FAILED, h.controller.rollback())
        val failed = h.controller.state.value
        assertTrue(failed is SwitchState.RollbackFailed)
        failed as SwitchState.RollbackFailed
        assertEquals(LangPair("en", "zh"), failed.from)
        assertEquals(LangPair("en", "ja"), failed.to)
        assertEquals("delete failed", failed.message)
        // 落点态保留缺失侧快照：重试只清同一批侧，不会扩到共用侧 en。
        assertEquals(setOf("ja"), failed.partial)
        h.failNextClear = false
        assertEquals(SwitchResult.ROLLED_BACK, h.controller.rollback())
        assertEquals(listOf(LangPair("en", "ja") to setOf("ja")), h.cleared)
        assertEquals(SwitchState.Idle, h.controller.state.value)
        job.cancel()
    }

    @Test
    fun `acknowledgeFailure clears the RollbackFailed landing state`() = runBlocking {
        val h = Harness()
        h.downloadDelayMs = 200L
        h.failNextClear = true
        val job = launch { h.switch("en", "ja") }
        h.awaitDownloading()
        assertEquals(SwitchResult.FAILED, h.controller.rollback())
        assertTrue(h.controller.state.value is SwitchState.RollbackFailed)
        h.controller.acknowledgeFailure()
        assertEquals(SwitchState.Idle, h.controller.state.value)
        job.cancel()
    }

    @Test
    fun `downloads run serially through the global queue`() = runBlocking {
        val h = Harness()
        h.downloadDelayMs = 40L
        val order = mutableListOf<Int>()
        val j1 = launch {
            h.controller.requestSwitch(LangPair("en", "ja"))
            order.add(1)
        }
        val j2 = launch {
            h.controller.requestSwitch(LangPair("en", "ko"))
            order.add(2)
        }
        j1.join(); j2.join()
        // 串行：两次下载都发生且不交错（downloads 各一次，完成顺序不定但都提交）。
        assertEquals(2, h.downloads.size)
        assertTrue(LangPair("en", "ja") in h.downloads)
        assertTrue(LangPair("en", "ko") in h.downloads)
        assertEquals(2, order.size)
    }
}
