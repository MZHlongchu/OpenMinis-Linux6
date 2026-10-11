package com.openminis.app.i18n

import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-offline-failure-breaker] 离线翻译的**连续失败**断路器（纯 JVM，无平台依赖）。
 *
 * 单句失败不等于引擎不可用：语言包边界、单条文本的推理报错都是一次性的。旧行为
 * 「任何非取消异常一次即关断」会让 AUTO 档整会话停在模型路径，而 `available`
 * 再也翻不回来（离线路径被跳过，就没有下一次成功去自愈）。这里加预算：
 * **连续**失败达到 [threshold] 才建议关断，任何一次成功立即清零。
 */
internal class OfflineFailureBreaker(private val threshold: Int = DEFAULT_THRESHOLD) {

    /** 原子计数：多个流实例可能在不同线程上喂句（对齐 AccessibilityQueryGuard 的写法）。 */
    private val consecutive = AtomicInteger()

    /** 成功一次 = 预算清零（瞬时失败不累积成永久降级）。 */
    fun onSuccess() {
        consecutive.set(0)
    }

    /** @return true = 连续失败已达阈值，调用方应关断引擎可用性（AUTO 档回落用户模型）。 */
    fun onFailure(): Boolean = consecutive.incrementAndGet() >= threshold

    companion object {
        /** 连续 3 句失败才认定引擎真不可用（一句失败太脆，无限容忍则永不回落）。 */
        const val DEFAULT_THRESHOLD = 3
    }
}

/**
 * [T-mlkit-stream-translate] 思考流/输出流的**实时**翻译管道：句子缓冲 +
 * 逐句翻译。ML Kit 按短句翻译最快（毫秒级），逐 delta 调用会浪费且碎片化——
 * 这里把增长中的流按句子边界（中英文标点 + 换行）切片，完成的句子逐个翻译，
 * [feed] 返回「已翻译完成部分」的全文；尾部残句由 [flush] 在流结束时补上。
 *
 * 不改变原始流：调用方把 [feed] 的返回值作为**翻译覆盖层**渲染（原文照旧），
 * 引擎不可用（无 GMS/下载失败）时本层静默为空、零行为变化；但失败会经
 * [MlKitTranslationEngine.markUnavailable] 翻掉引擎可用性，让 AUTO 档回落
 * 用户模型（见 [translateOrNull]）。
 */
class StreamTranslator(
    private val engine: MlKitTranslationEngine,
    private val src: String,
    private val tgt: String,
) {
    private val buffer = StringBuilder()
    private val translated = StringBuilder()
    private var seq = 0

    val translatedSoFar: String get() = translated.toString()

    /**
     * [T-i18n-avail-fallback] 单句翻译的失败边界。旧实现是 `runCatching{}.getOrNull()`：
     * 失败被整条吞掉，`MlKitTranslationEngine.available` 永远是 true，AUTO 档
     * （引擎默认）于是既不翻译也不回落用户模型——静默失效。
     *
     * 取消（用户中断/重试/页面退出）只说明本句没翻成，不代表引擎不可用，保持
     * 旧行为静默丢弃；其余失败（无 GMS / 语言包缺失 / 推理报错）走
     * [failures] 的**连续失败**预算（[T-offline-failure-breaker]）：单句瞬时失败
     * 不再一次性关断整会话，连续 [OfflineFailureBreaker.DEFAULT_THRESHOLD] 句失败
     * 才翻掉引擎可用性，让 AUTO 档在**下一轮**回到模型路径
     * （`streamModelOn` 判 `!available`）。预算跨流实例共享，故短回复连轮失败也能
     * 累积；任何一次翻译成功都会清零（自愈）。
     */
    private suspend fun translateOrNull(text: String): String? =
        try {
            engine.translate(text, src, tgt).also { if (it.isNotBlank()) failures.onSuccess() }
        } catch (t: Throwable) {
            if (t !is CancellationException && failures.onFailure()) engine.markUnavailable()
            null
        }

    /** 流式喂入一个 delta；返回到目前为止的翻译全文（无新完成句时不变）。 */
    suspend fun feed(delta: String): String {
        if (delta.isEmpty()) return translated.toString()
        buffer.append(delta)
        // 按句子边界切片：中英文句号/叹号/问号/换行。引号/括号跟随句尾。
        while (true) {
            val end = sentenceEnd(buffer) ?: break
            val sentence = buffer.substring(0, end).trim()
            buffer.delete(0, end)
            if (sentence.isEmpty()) continue
            seq++
            val translatedSentence = translateOrNull(sentence)
            if (!translatedSentence.isNullOrBlank()) {
                if (translated.isNotEmpty()) translated.append('\n')
                translated.append(translatedSentence)
            }
        }
        return translated.toString()
    }

    /** 流结束：翻译尾部残句（可能没有句尾标点）。 */
    suspend fun flush(): String {
        val tail = buffer.toString().trim()
        buffer.setLength(0)
        if (tail.isEmpty()) return translated.toString()
        val t = translateOrNull(tail)
        if (!t.isNullOrBlank()) {
            if (translated.isNotEmpty()) translated.append('\n')
            translated.append(t)
        }
        return translated.toString()
    }

    companion object {
        /**
         * [T-offline-failure-breaker] **跨流实例**的失败预算：一次会话里可能开很多个
         * 短流（每轮回复一个），预算必须跟着引擎可用性（会话级）走，否则「短句失败
         * 一次」永远攒不够阈值、也没人再关断。成功一次即清零。
         */
        internal val failures = OfflineFailureBreaker()

        /** 句子边界检测与模型兜底路径共享（见 [ModelStreamTranslator.sentenceBoundary]）。 */
        private fun sentenceEnd(text: StringBuilder): Int? = ModelStreamTranslator.sentenceBoundary(text)
    }
}
