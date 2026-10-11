package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Starting 8 chats at once on a Pixel 6 left some forever titled "New Chat",
 * and a chat opened mid-run could show replies with no question.
 *
 * [T-android-vm-evict-pending-send] Each new session's view model evicted the
 * previous one while it still waited to send (idle to every busy signal), so
 * 4 of 8 never sent: empty "New Chat" rows. Driven on the real store.
 *
 * [T-android-send-before-load] A send landing while loadSession was still
 * reading the DB had its user message replaced by loadSession's older
 * snapshot: gone from the screen (or from agentHistory), and title generation
 * then found no user message.
 */
class ConcurrentSessionStartTest {

    private val created = mutableListOf<String>()
    private fun id(): String = "s-${UUID.randomUUID()}".also { created += it }
    private fun open(sid: String) = ChatViewModelStore.ownerFor(sid).viewModelStore

    @After
    fun tearDown() = created.forEach { ChatViewModelStore.release(it) }

    @Test
    fun `a session being prepared for its first send is not evicted by later ones`() = runTest {
        val first = id()
        val prepared = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var firstStore: androidx.lifecycle.ViewModelStore? = null
        val holder = launch {
            ChatViewModelStore.holdingForSend(first) {
                firstStore = open(first)
                prepared.complete(Unit)
                release.await()
            }
        }
        prepared.await()
        repeat(7) { open(id()) }
        assertTrue("held session survives 7 later ones", ChatViewModelStore.isLiveStore(first, firstStore!!))
        release.complete(Unit)
        holder.join()
        yield()
        // Once the hold is gone the session is an ordinary idle entry again.
        repeat(4) { open(id()) }
        assertFalse("evictable after the send was handed off", ChatViewModelStore.isLiveStore(first, firstStore!!))
    }

    @Test
    fun `nested holds on one session release only when the last ends`() = runTest {
        val sid = id()
        val store = open(sid)
        ChatViewModelStore.holdingForSend(sid) {
            ChatViewModelStore.holdingForSend(sid) { }
            repeat(5) { open(id()) }
            assertTrue(ChatViewModelStore.isLiveStore(sid, store))
        }
    }

    @Test
    fun `the headless prompt and retry hold the session until the send is handed off`() {
        val runner = ProductionSources.read("debug/HeadlessChatRunner.kt")
        assertTrue(runner.contains("): PromptResult = ChatViewModelStore.holdingForSend(sessionId) {\n        withContext(Dispatchers.Main) {\n            val vm = viewModel(context, sessionId)"))
        assertTrue(runner.contains("): PromptResult = ChatViewModelStore.holdingForSend(sessionId) {\n        withContext(Dispatchers.Main) {\n            val app = app(context)\n            val vm = viewModel(context, sessionId)"))
        val store = ProductionSources.read("ui/chat/ChatViewModelStore.kt")
        assertTrue("held sessions count as busy", store.contains("return (tracked + inFlight + pendingSends.keys).toSet()"))
    }

    @Test
    fun `a send waits for the session load before touching the transcript`() {
        // [T-android-send-before-load-ext-split] 本移植把 send 的实现从上游单体
        // ChatViewModel.kt 拆进了 `ChatViewModelSendExt.kt`（`internal fun
        // ChatViewModel.sendMessage(...)`），loadSession 拆进了
        // `ChatViewModelLoadExt.kt`（扩展函数，比上游在类里少一层缩进）。
        // 扫描面因此改成这两个文件；**断言一条没少**，只是三处随本移植改口径：
        //   - 上屏那一句本移植包了 trimLoadedWindow(...)（窗口化加载），
        //     核心动作仍是「把 userMsg 追加进 _messages」；
        //   - loadSession 的 finally 缩进是 12 空格（不再嵌在类里），所以逐字节的
        //     `"} finally {\n<16 空格>// T201…"` 换成同一件事的结构化判定：
        //     finally 体（到本层闭合花括号为止）第一行是 T201 注释，且开门语句
        //     就在这个 finally 体里。
        val vm = ProductionSources.read("ui/chat/ChatViewModelSendExt.kt")
        val send = vm.substringAfter("internal fun ChatViewModel.sendMessage(")
            .substringAfter("viewModelScope.launch {")
        val gate = send.indexOf("sessionLoaded.first { it }")
        assertTrue("gate present", gate > 0)
        assertTrue("before the session row is ensured", gate < send.indexOf("val activeSessionId = ensureSession()"))
        assertTrue("before the user message is persisted", gate < send.indexOf("chatRepository.appendMessage(activeSessionId, \"user\""))
        assertTrue("before it reaches the screen", gate < send.indexOf("_messages.value = trimLoadedWindow(_messages.value + userMsg)"))
        assertTrue("safe mode skips the load, so it must not wait",
            send.substring(0, gate).contains("!sessionLoaded.value && !com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()"))
        // The flag the gate waits on is raised on every exit of loadSession.
        val load = ProductionSources.read("ui/chat/ChatViewModelLoadExt.kt")
            .substringAfter("internal fun ChatViewModel.loadSession()")
            .substringBefore("\ninternal fun ")
        assertTrue("loadSession still opens the gate from a finally", load.contains("} finally {"))
        val finallyBody = load.substringAfter("} finally {")
            .lines().takeWhile { it.trim() != "}" }.joinToString("\n")
        assertTrue(
            "T201: the finally opens the gate even on an early `return@launch`",
            finallyBody.contains("// T201: open the gate even on early `return@launch`"),
        )
        assertTrue(
            "the gate flag is raised inside that finally",
            finallyBody.contains("sessionLoaded.value = true"),
        )
    }
}
