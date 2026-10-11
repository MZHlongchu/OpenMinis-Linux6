package com.openminis.app.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-d4-terminal-tail-structural] / [T-openai-terminal-drain] 收尾判据与「尾部被砍断」
 * 可观测性的形状守卫。
 *
 * 这两条不变量都没有便宜的端到端观察手段，所以与
 * [com.openminis.app.data.repository.BodyGcAlignmentShapeTest] 同口径，直接对源码断言，
 * 把它们钉成「读代码即可确认」：
 *  - 收尾判据必须是纯结构的（第二条连续注释行 / `[DONE]` / 空 `data:` 行），不许再挂在
 *    「usage 落在哪个事件之后」上 —— 那个代理会把「第二个 `response.completed` 才带
 *    usage」误判成 Chat 形状，第一条 keep-alive 就截断随后补发的
 *    `response.output_item.done`。行为侧的钉子在 [ResponsesTerminalTailToolCallTest]
 *    与 [StreamTerminalDrainTest]。
 *  - 终态后 15s drain 砍掉连接时，读取失败必须升到 warn，且尾部冲刷要留下
 *    declared/completed/pending 三个数 —— 真跑要等 15s 以上、日志又没有测试侧捕获口。
 */
class OpenAIResponsesTailShapeTest {

    private val src =
        File("src/main/java/com/openminis/app/provider/openai/OpenAIRawStream.kt").readText()

    @Test
    fun `the end-of-tail break does not depend on where the usage block landed`() {
        assertFalse(
            "usage 位置代理已删除：正是它把「第二次 response.completed 才带 usage」误判成 " +
                "Chat 形状，第一条 keep-alive 就收尾",
            src.contains("usageArrivedAfterTerminal"),
        )
        assertTrue(
            "终态后只认第二条连续注释行（两个 API 形状统一）",
            src.contains("if (postTerminalCommentLines >= 2) break"),
        )
        assertFalse(
            "不许再出现「一条注释行即 break」的形状",
            src.contains("postTerminalCommentLines >= 1"),
        )
        assertTrue(
            "空 `data:` 行仍是结构性收尾信号",
            src.contains("if (terminalEventAtNanos.get() != 0L && sawUsageBlock) break"),
        )
        assertTrue("[DONE] 仍是结构性收尾信号", src.contains("if (payload == \"[DONE]\")"))
        assertTrue(
            "任何真实事件行都要清零注释计数（否则 data 行会被前后夹的注释误伤）",
            src.contains("postTerminalCommentLines = 0"),
        )
    }

    @Test
    fun `one logical call is announced only once so no card can be left streaming`() {
        assertTrue(
            "added 必须先查这条调用是否已宣告过（同 item_id 重放 / 同 call_id 换 item_id）",
            src.contains("Responses API: function_call announced twice"),
        )
        assertTrue(
            "未宣告过的身份记为别名，delta/done 都归到已宣告的身份上",
            src.contains("responsesToolCallAliases[itemId] = announcedKey"),
        )
        assertTrue(
            "done 解析别名后再消费累加器（连同指向它的别名一起）",
            src.contains("val aliasedKey = responsesToolCallAliases[itemId]"),
        )
        assertTrue(
            "消费累加器只此一条路径，别名不会漏删",
            src.contains("fun consumeResponsesToolCall(key: String): ResponsesToolCallAccumulator?"),
        )
        assertTrue(
            "尾部冲刷前必须清掉别名表（别名不该被当成第二条调用冲刷出去）",
            src.contains("responsesToolCallAliases.clear()"),
        )
    }

    @Test
    fun `a post-terminal read failure is warned about and the tail flush names what was cut`() {
        assertTrue(
            "终态后的读取失败必须置位并升到 warn（此前只有一条 info，事后查不出工具调用为何消失）",
            src.contains("postTerminalIoError = true") &&
                src.contains("[T-openai-terminal-drain] connection failed after the terminal event"),
        )
        assertFalse(
            "旧的低调措辞不许留（那正是「把 IOException 当正常 EOF」的写法）",
            src.contains("[T-openai-terminal-drain] connection closed after the terminal event"),
        )
        assertTrue(
            "drain 自己砍的连接要留标记，才能区分「我们砍的」与「relay 正常关闭」",
            src.contains("terminalDrainClosedSocket.set(true)"),
        )
        assertTrue(
            "尾部冲刷的可观测提示：declared / completed / pending 三个数缺一不可",
            src.contains("tail cut after the terminal event without [DONE]") &&
                src.contains("declaredFunctionCalls=") &&
                src.contains("completedFunctionCalls=") &&
                src.contains("pendingAtTail="),
        )
        assertTrue(
            "从未 added 过的 done 丢掉时，(declared > completed) 就是那条证据",
            src.contains("if (declaredCalls > declaredFunctionCallItems) declaredFunctionCallItems = declaredCalls"),
        )
    }
}
