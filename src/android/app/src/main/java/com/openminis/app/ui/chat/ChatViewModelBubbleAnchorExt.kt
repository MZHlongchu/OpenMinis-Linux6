package com.openminis.app.ui.chat

import com.openminis.app.logging.AppLogger

/**
 * [T-android-bubble-anchor] 每次"按锚点裁剪"打一行日志，不带消息正文。
 *
 * ORDINAL 与 NONE 表示这个气泡没有已落库的行（例如排队占位气泡），退回旧的
 * 「数可见用户行」规则——那是唯一还可能切错位置的一条路径，所以降级成 warning，
 * 让它在日志里显形而不是静默。上游 1.14 的落点：
 * `ChatViewModel.kt:8196-8201`（`private fun logBubbleAnchor`）。
 *
 * 本移植把 retry / delete / edit 拆进了各自的 ext 文件，成员私有的写法就跨不过去了，
 * 因此这里写成 ChatViewModel 的 internal 扩展函数；调用点与断言文本与上游一字不差。
 */
internal fun ChatViewModel.logBubbleAnchor(
    op: String,
    bubbleId: String,
    located: BubbleRowLocator.Located,
) {
    val line = "[BubbleAnchor] op=$op bubble=${bubbleId.take(8)} via=${located.via} " +
        "row=${located.row?.id?.take(8) ?: "-"} sortOrder=${located.row?.sortOrder ?: -1}"
    if (located.via == BubbleRowLocator.Via.LINKED) {
        AppLogger.info(ChatViewModel.TAG, line)
    } else {
        AppLogger.warning(ChatViewModel.TAG, line)
    }
}
