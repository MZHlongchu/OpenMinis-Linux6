package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

@Composable
internal fun StreamingMarkdownTextBody(
    content: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    // While streaming, sample `content` at the adaptive throttle interval.
    // produceState + snapshotFlow.conflate() makes the upstream value collection
    // suspend-safe and frees the runtime to drop intermediate values when the
    // collector falls behind. When streaming ends, emit the final value
    // unconditionally so we don't render a stale half-block.
    val displayContent by produceState(initialValue = content, content, isStreaming) {
        if (!isStreaming) {
            value = content
            return@produceState
        }
        snapshotFlow { content }
            .conflate()
            .collect { latest ->
                value = latest
                delay(streamingThrottleFor(latest))
            }
    }
    // [T-android-inline-parse-offmain] Theme snapshot for off-main prewarm.
    val mdColors = currentMdColors()
    var blocks by remember { mutableStateOf<List<MdBlock>>(emptyList()) }
    LaunchedEffect(displayContent) {
        val computed = withContext(Dispatchers.Default) {
            parseMarkdownBlocks(displayContent).also {
                MarkdownParseCaches.prewarm(it, mdColors)
            }
        }
        // If the LE was cancelled while parseMarkdownBlocks was still running
        // (a newer chunk arrived), don't publish stale blocks.
        coroutineContext.ensureActive()
        blocks = computed
    }

    ShardSubIndexScope {
        Column(modifier = modifier) {
            // [T-android-stream-fade] Last block during a live stream gets
            // LocalAppendOnlyFade=true so MdText fades in newly-appended
            // word ranges (mirrors iOS TextFadeAnimator). Every other block
            // — completed prefix, non-streaming sessions — renders opaque.
            // [T-android-stream-fade-seed] Every block goes through the SAME
            // composable shape while streaming — a provider whose VALUE says
            // whether it is the live tail — rather than an if/else that wraps
            // only the last block. The two branches were different groups, so
            // whenever `lastIdx` shifted (a trailing block re-parsed away, the
            // list before it became last) Compose disposed and rebuilt the
            // whole subtree: every MdText lost its fade controller and its
            // already-visible text re-faded from alpha 0. Gated on isStreaming
            // so frozen history composes exactly as before.
            //
            // [T-merge-upstream-1-14-stream-fade-seed] 上游 1.14 的
            // StreamingMarkdownText.kt:815-837 就是这一段；合并把本文件留在了
            // 「只包最后一块」的旧形状（if/else），等于把上游修掉的回归装回来。
            val lastIdx = blocks.size - 1
            if (isStreaming) {
                blocks.forEachIndexed { idx, block ->
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalAppendOnlyFade provides (idx == lastIdx),
                    ) { RenderBlock(block) }
                }
            } else {
                blocks.forEach { RenderBlock(it) }
            }
        }
    }
}

