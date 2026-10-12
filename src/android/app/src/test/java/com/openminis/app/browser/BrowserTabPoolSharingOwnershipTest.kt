package com.openminis.app.browser

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-w1-pool-ownership] 池子共用的两头，一头都不能松：
 *
 *  - **读的一头**：helper 子会话建 VM 时就 `adoptBrowserTabPool(parentVm.browserTabPool)`。
 *    读取端若还是「谁先读谁建一份」（`by lazy`），借来的引用等于白借——父子各看一套
 *    WebView、登录态各一份，接线在而语义不通（复核者点名的「编译级假绿」）。
 *  - **拆的一头**：`ChatViewModelStore` 淘汰 helper 子会话就是 `ViewModelStore.clear`
 *    → `ChatViewModel.onCleared`。子会话若照着池引用无条件 dispose，拆掉的是**父会话
 *    正在用的**池子（跨会话池销毁）——清空子会话（clearChat）同理。
 *
 * BrowserTabPool 需要 Context 与真 WebView，JVM 单测建不出实例，因此（与
 * [BrowserReleaseAllSemanticsTest] 同一手法）把这两个决定钉成 ChatViewModel.kt /
 * ScheduledAgentRunner.kt 的源码事实：谁改了读取端或拆池守卫，这里就红。
 */
class BrowserTabPoolSharingOwnershipTest {

    private val vmSrc: String by lazy { ProductionSources.read("ui/chat/ChatViewModel.kt") }
    private val schedSrc: String by lazy { ProductionSources.read("scheduled/ScheduledAgentRunner.kt") }

    /** `browserTabPool` 的 getter 主体：从声明到下一个成员。 */
    private val getterBody: String by lazy {
        vmSrc.substringAfter("val browserTabPool: BrowserTabPool")
            .substringBefore("internal val _showBrowserSheet")
    }

    /** `adoptBrowserTabPool` 的函数体。 */
    private val adoptBody: String by lazy {
        vmSrc.substringAfter("fun adoptBrowserTabPool(").substringBefore("\n    }")
    }

    /** `onCleared` 里 `super.onCleared()` 之前的那段（dispose 一定在这段里）。 */
    private val onClearedBody: String by lazy {
        vmSrc.substringAfter("override fun onCleared()").substringBefore("super.onCleared()")
    }

    /** 去掉注释行之后的源码 —— 「还有活的调用」这类断言必须看代码，不看散文。 */
    private fun liveCode(src: String): String = src.lineSequence()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    @Test
    fun `the read side prefers the adopted pool`() {
        assertTrue(
            "读取端必须先用 _browserTabPoolRef 里已有的引用（借来的那份）",
            getterBody.contains("_browserTabPoolRef ?: synchronized(this)"),
        )
        assertTrue(
            "没有才自建，并且自建的那份要打上归属标记",
            getterBody.contains("_ownsBrowserTabPool = true"),
        )
        assertFalse(
            "`by lazy` 只会按自己的初始化器建池，借来的引用会被覆盖 —— 这正是假绿的来源",
            getterBody.contains("by lazy"),
        )
    }

    @Test
    fun `adopting a pool registers it and gives up ownership`() {
        assertTrue("借来的池子必须登记进引用", adoptBody.contains("_browserTabPoolRef = pool"))
        assertTrue("并且清掉自建标记：它不是我们的", adoptBody.contains("_ownsBrowserTabPool = false"))
    }

    @Test
    fun `a borrowed pool is never re-stamped with this session's id`() {
        // 池子的 sessionId 决定标签快照与下载目录落在哪个会话名下。setSession 只允许
        // 出现在「自建」那一支；借来的池子若被重打上子会话的 id，父会话的浏览器状态
        // 会跟着搬到子会话名下（上游同样只在自建时 setSession）。
        val selfBuild = getterBody.substringAfter("BrowserTabPool(context).also")
        assertTrue("自建那一支才打本次会话的 id", selfBuild.contains("setSession(activeSessionId)"))
        assertFalse(
            "读到借用引用那条快路径（自建之前）不得再打 id",
            getterBody.substringBefore("BrowserTabPool(context).also").contains("setSession("),
        )
        assertFalse("adopt 也不得给借来的池子重新打 id", adoptBody.contains("setSession"))
    }

    @Test
    fun `a helper never disposes the shared pool`() {
        assertTrue(
            "onCleared 的 dispose 必须挂在自建标记上",
            onClearedBody.contains("if (_ownsBrowserTabPool) _browserTabPoolRef?.dispose()"),
        )
        assertFalse(
            "顶格一句无条件 dispose 就会把父会话正在用的池子一起拆掉",
            Regex("(?m)^[ \\t]*_browserTabPoolRef\\?\\.dispose\\(\\)").containsMatchIn(onClearedBody),
        )
    }

    @Test
    fun `clearing a chat only destroys a pool we built`() {
        val line = liveCode(vmSrc).lineSequence()
            .firstOrNull { it.contains("destroyAllTabs()") }
            .orEmpty()
        assertTrue("清空会话必须仍有 destroyAllTabs 这一手", line.contains("destroyAllTabs()"))
        assertTrue("helper 的清理守卫不能丢", line.contains("helperConfig == null"))
        assertTrue("而且只对自建池动手：借来的池子归父会话", line.contains("_ownsBrowserTabPool"))
    }

    @Test
    fun `the scheduled helper adopts the parent pool instead of the raw field`() {
        assertTrue(
            "计划任务的子会话必须 adopt 父 VM 的池子",
            schedSrc.contains("it.adoptBrowserTabPool(parentVm.browserTabPool)"),
        )
        assertFalse(
            "不得绕过 adopt 直连 _browserTabPoolRef 这个私有池引用",
            liveCode(schedSrc).contains("_browserTabPoolRef"),
        )
    }
}
