package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-fallback-respects-user-switch] A group fallback inside a running turn must
 * not overwrite a model the user picked while that turn ran.
 *
 * Before the fix the fallback branch of runAgentLoop unconditionally wrote
 * `this@ChatViewModel.currentProvider = next` and persisted
 * `{"type":"entry","entryId":B}`, so a user who switched to model C mid-turn
 * found the session bound to fallback member B afterwards.
 *
 * The loop is a private suspend member that needs a Context, Room and live
 * providers, so the decision is a pure companion predicate (tested directly)
 * and its wiring is pinned as source facts.
 */
class FallbackRespectsUserSwitchTest {

    private val vmSrc by lazy {
        File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt")
            .also { assertTrue("missing ChatViewModel.kt", it.exists()) }.readText()
    }

    @Test
    fun `fallback may rebind only while the class-level provider is unchanged`() {
        val a = Any()
        val c = Any()
        assertTrue(ChatViewModel.fallbackMayRebindSession(a, a))
        assertFalse("user switched to another provider mid-turn", ChatViewModel.fallbackMayRebindSession(c, a))
        assertTrue(ChatViewModel.fallbackMayRebindSession(null, null))
        assertFalse(ChatViewModel.fallbackMayRebindSession(c, null))
    }

    @Test
    fun `the class-level write and persistBinding sit behind the user-switch guard`() {
        // [T-merge-upstream-1-14-loop-is-an-extension] 上游的 runAgentLoop 是
        // ChatViewModel.kt 里的成员函数（`private suspend fun runAgentLoop(`），
        // 本移植把它拆进 ui/chat/ChatViewModelAgentLoopExt.kt，成为
        // `ChatViewModel.runAgentLoop` 扩展函数。因此扫描面与两处写法跟着改，语义不变：
        //  - 基线 `classProviderAtSend` 在扩展里就是类级属性（没有 `this@ChatViewModel`
        //    这个标签，`currentProvider`/`classProviderAtSend` 都是接收者的成员）；
        //  - 类级写是 `this.currentProvider = next`，`this` 即接收者 ChatViewModel；
        //  - 守卫、类级写、persistBinding 三者的先后顺序断言原样保留。
        val loopSrc = File("src/main/java/com/openminis/app/ui/chat/ChatViewModelAgentLoopExt.kt")
            .also { assertTrue("missing ChatViewModelAgentLoopExt.kt", it.exists()) }.readText()
        // The decision itself stays on ChatViewModel (companion); the extension
        // only calls it. Pin that too — the guard anchor above is meaningless if
        // the predicate moved away from the class the loop reaches through.
        assertTrue("fallbackMayRebindSession must stay on ChatViewModel", vmSrc.contains("fun fallbackMayRebindSession("))
        val loop = loopSrc.substringAfter("suspend fun ChatViewModel.runAgentLoop(")
        assertTrue(loop.contains("var classProviderAtSend = currentProvider"))
        val guard = loop.indexOf("val userSwitchedMidTurn = !ChatViewModel.fallbackMayRebindSession(")
        val write = loop.indexOf("this.currentProvider = next")
        val persist = loop.indexOf("persistBinding(\"\"\"{\"type\":\"entry\",\"entryId\":\"\${newEntry.id}\"}\"\"\")")
        assertTrue("guard not found", guard >= 0)
        assertTrue("class-level write must follow the guard", write > guard)
        assertTrue("persistBinding must follow the guard", persist > guard)
        // Our own fallback moves the baseline, so a second fallback in the same
        // turn is still allowed to rebind. Upstream pinned "within 200 chars";
        // this port carries a three-line [T-fallback-respects-user-switch]
        // comment between the two statements (286 chars apart), so the window is
        // widened to a proximity bound — same statements, same else-branch,
        // ordering still pinned.
        val baseline = loop.indexOf("classProviderAtSend = next", write)
        assertTrue("our own fallback must move the baseline right after the write", baseline in (write + 1)..(write + 400))
    }
}
